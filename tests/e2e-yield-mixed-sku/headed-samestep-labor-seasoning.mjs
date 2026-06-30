/**
 * 同一道工序 多成本桶共存 深度 E2E (纯-headed, prod F006):
 *   - 熟制道: 调料(seasoning) + 人工(labor) 同道
 *   - 修油道: 副产(byproduct) + 人工(labor) 同道
 *
 * 动机 (独立审计抓到的真实未覆盖场景 + getYield 双计风险):
 *   现有所有测试都把 调料 / 人工 / 副产 分散到不同道 (crossday 显式断言"熟制道无工时/副产";
 *   matrix-fullchain 人工在修油道、调料在熟制道, 从不同道叠加)。但**真实卤味熟制道既耗人工(看锅工人)
 *   又有调料**, 修油道既出副产(肥油)又耗人工 —— 多桶同道是最常见配置, 却无人测过。
 *
 *   verify-first (ClerkProcessEntryServiceImpl):
 *     - writeSeasoningReport (:616) 写 outputQuantity=st.output
 *     - writeLaborReport     (:645) 也写 outputQuantity=st.output
 *     - writeYieldAuxReport  (:682-685) 故意**不**写 output, 注释明言"设 output 会与 seasoning/labor
 *       报工的 output 重复累加 → 虚高产出"。 → 开发者知道 seasoning+labor 都带 output。
 *     - YieldCalculationServiceImpl.calculateSteps: 同道 (同 processEntryStepKey) 分一组,
 *       totalOutput += 每条 report.output (:152), totalInput += 每条 report.input (:144)。
 *   ∴ 同道既有 labor 又有 seasoning → 该道 getYield totalOutput/totalInput **各 2×**。
 *     (rate=out/in 因分子分母同 2× 而保持, 故出成率不暴; 绝对产出/投入翻倍。)
 *
 * 本测试判别:
 *   ① 成本桶互不污染: seasoningCost==cookPerKg×input (不含人工), laborCost==工时×人数×单价 (不含调料)。
 *   ② getYield 该熟制道 totalOutput == 真实物理产出 (NOT 2×) — 双计探针。
 *   若 totalOutput==2×真实 → 抓到 labor/seasoning 同道双写 output 的真 bug。
 *
 * cookPerKg=(50/1000)×20=1.0 元/kg; laborRate 从 cost-settings 读 (F006=28); 1h×2人 → 56.00。
 *
 * Env: E2E_USERNAME E2E_PASSWORD [E2E_ADMIN_URL E2E_FACTORY_ID PLAYWRIGHT_PORT]
 */
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import {
  APP, FACTORY, WEIGHT_UNIT_RE, COUNT_UNIT_RE, arr, num, today,
  startHeaded, setupSkuAndBom,
} from './_headed-helpers.mjs';

const OUT = path.resolve(`.playwright-mcp/samestep-labor-seasoning-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const resultFile = path.join(OUT, 'headed-samestep-labor-seasoning-result.json');

const DOSAGE_G_PER_KG = 50;
const PRICE_1 = 20;
const COOK_PER_KG = (DOSAGE_G_PER_KG / 1000) * PRICE_1; // 1.0
const BP_QTY = 0.05;   // 副产 kg (修油道 — 肥油等)
const BP_PRICE = 4;    // 副产回收单价 → credit = 0.05×4 = 0.2
const BP_CREDIT = BP_QTY * BP_PRICE;

const assertions = [];
const ok = (pass, label, data = {}) => {
  assertions.push({ pass: !!pass, label, ...data });
  console.log(`${pass ? 'PASS' : 'FAIL'} ${label} ${Object.keys(data).length ? JSON.stringify(data) : ''}`);
  return !!pass;
};
const approx = (actual, expected, tol, label, data = {}) => {
  const pass = actual != null && expected != null && Math.abs(Number(actual) - Number(expected)) <= tol;
  assertions.push({ pass, label, actual, expected, tol, ...data });
  console.log(`${pass ? 'PASS' : 'FAIL'} ${label} act=${actual} exp=${expected}`);
  return pass;
};
const r2 = (v) => Math.round((Number(v) + Number.EPSILON) * 100) / 100;

const ctx = await startHeaded(OUT);
const { page, api, shot, helpers } = ctx;
const { selectByText, fillNum, waitSaved } = helpers;
const drawer = () => page.locator('.el-drawer__body');
const activePane = () => drawer().locator('.el-tab-pane:visible').first();

try {
  // 1. SKU + 工序链(含熟制)
  const setup = await setupSkuAndBom(page, { namePrefix: 'SLS', gramsPerUnit: 120, api, shot, minProcesses: 6 });
  const procs = setup.processes.filter((p) => p.isActive !== false)
    .sort((a, b) => Number(a.processOrder || 0) - Number(b.processOrder || 0));
  const mixIdx = procs.findIndex((p) => /熟|卤|煮|腌|入味|调味/.test(String(p.processName || '')));
  ok(mixIdx > 0, '工序链含可达熟制道(承载 labor+seasoning)', { mixName: procs[mixIdx]?.processName, chain: procs.map((p) => p.processName).join('→') });
  if (mixIdx <= 0) throw new Error('no seasoning step');

  // 2. 配 COOKING 调料 (cookPerKg=1.0)
  await configureSeasoning(setup.productTypeId);
  const seaCheck = await api('GET', `/${FACTORY}/bom/recipes/by-product/${encodeURIComponent(setup.productTypeId)}/seasoning`).catch(() => null);
  ok(arr(seaCheck?.seasoningItems).some((i) => String(i.section) === 'COOKING'), '调料 COOKING 行持久化(cookPerKg=1.0)', { status: seaCheck?.status });

  // 3. 读 labor rate (oracle)
  const costSettings = await api('GET', `/${FACTORY}/config/cost-settings`).catch(() => null);
  const laborRate = num(costSettings?.laborHourlyRate) || 26;
  ok(laborRate > 0, 'labor 单价已读(oracle)', { laborRate });

  // 4. 计划 + 单原料批
  const plan = await api('POST', `/${FACTORY}/production-plans`, {
    productTypeId: setup.productTypeId, plannedQuantity: 5, plannedDate: today(0), expectedCompletionDate: today(2),
    sourceType: 'MANUAL', skipProcessReporting: false, customerOrderNumber: `SLS-${Date.now()}`, notes: 'same-step labor+seasoning',
  });
  const planId = String(plan.id || plan.planId), planNumber = String(plan.planNumber || planId);
  await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/start`).catch(() => null);
  ok(!!planId, 'API 前置创建并启动计划', { planNumber });

  const rawBatch = await findRawBatch(setup.rawMaterial);
  ok(!!rawBatch?.batchNumber, '发现重量单位原料批', { batchNumber: rawBatch?.batchNumber });
  if (!rawBatch?.batchNumber) throw new Error('no raw batch');

  // 5. 抽屉
  await openProcessDrawer(planNumber);
  await shot('drawer-open');

  // 6. 首道 1 批
  await gotoTab(procs[0].processName);
  await page.waitForTimeout(700);
  await saveRow(async (row) => {
    await selectByText(row.locator('.el-select').first(), rawBatch.batchNumber);
    const nums = row.locator('.el-input-number');
    await fillNum(nums.nth(0), 1.0); await fillNum(nums.nth(1), 0.9);
  }, 'first');
  let prev = (await ycByOrder(planId, procs[0].processOrder)).map((x) => ({ bn: x.batchNumber, rem: num(x.remaining) ?? 0.3 })).filter((x) => x.bn);
  ok(prev.length >= 1, '首道物化 WIP 批', { bn: prev[0]?.bn });

  // 7. 中间道流转 (单批, 转发 ~85%)。在首个支持副产的道(修油/滚揉/焯水)同时录 副产 + 人工 →
  //    验证 副产(aux 报工, 不带 output) 与 人工(带 output) 同道共存: 桶各自正确 + getYield output 不双计。
  let bpStep = null; // { bn, realOut, realIn } — 副产+人工 道
  let bpStepDone = false;
  for (let i = 1; i < mixIdx; i += 1) {
    const proc = procs[i];
    if (!(await gotoTab(proc.processName))) break;
    await page.waitForTimeout(700);
    const up = prev[0];
    const feed = Math.max(0.05, r2(up.rem * 0.85)), out = r2(feed * 0.9);
    const canByproduct = !bpStepDone && /修油|滚揉|焯水/.test(String(proc.processName || ''));
    let laborOk = false;
    await saveRow(async (row) => {
      await selectByText(row.locator('.el-select').first(), up.bn).catch(() => null);
      const nums = row.locator('.el-input-number');
      await fillNum(nums.nth(0), feed); await fillNum(nums.nth(1), out);
      if (canByproduct) {
        // 副产列 (修油/焯水/滚揉): nth(2)=副产kg, nth(3)=副产单价
        await fillNum(nums.nth(2), BP_QTY).catch(() => null);
        await fillNum(nums.nth(3), BP_PRICE).catch(() => null);
        laborOk = await fillLaborSegment(activePane(), row);
      }
    }, proc.processName);
    if (canByproduct) {
      ok(laborOk, `${proc.processName} 道人工段已填(副产+人工同道)`, { laborOk });
      bpStepDone = true;
    }
    prev = (await ycByOrder(planId, proc.processOrder)).map((x) => ({ bn: x.batchNumber, rem: num(x.remaining) ?? 0.3 })).filter((x) => x.bn);
    if (canByproduct && prev[0]?.bn) bpStep = { bn: prev[0].bn, realOut: out, realIn: feed, processOrder: proc.processOrder, name: proc.processName };
    if (!prev.length) { ok(false, `中间道(${proc.processName})保持批`, {}); break; }
  }

  // 8. 熟制道: 单上游(来源批 expander) + 投入/产出 + 人工段(1h×2人) → 同道 labor+seasoning
  const mixProc = procs[mixIdx];
  const up = prev[0];
  const mixIn = Math.max(0.05, r2(Math.min(up.rem * 0.8, 0.4)));
  const mixOut = r2(mixIn * 0.9);
  await gotoTab(mixProc.processName);
  await page.waitForTimeout(800);
  const pane = activePane();
  await pane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
  await page.waitForTimeout(800);
  const row = pane.locator('table.sp-grid tbody tr.sp-tr').last();
  // 来源批 expander (单源) — trigger 是主行 "+ 来源批" 按钮
  await row.locator('td.sp-td button').filter({ hasText: /来源批/ }).first().click();
  await page.waitForTimeout(800);
  const srcSec = pane.locator('.sp-expand-section').filter({ hasText: /来源批.*混锅/ }).last();
  await srcSec.locator('button').filter({ hasText: /来源批/ }).first().click(); // addUpstreamSource
  await page.waitForTimeout(600);
  await selectByText(srcSec.locator('.el-select').first(), up.bn).catch(() => null);
  await fillNum(srcSec.locator('.el-input-number').first(), mixIn).catch(() => null);
  // 主行 投入/产出
  const mainNums = row.locator('.el-input-number');
  await fillNum(mainNums.nth(0), mixIn);
  await fillNum(mainNums.nth(1), mixOut);
  // 人工段 (08:00-09:00 = 1h, 2 人)
  const laborFilled = await fillLaborSegment(pane, row);
  ok(laborFilled, '熟制道人工段已填(1h×2人)', { laborFilled });
  await shot('mix-labor-seasoning-row');
  await row.locator('button').filter({ hasText: '保存' }).first().click();
  const mixSaved = await waitSaved();
  await page.waitForTimeout(1600);
  ok(mixSaved.saved, '熟制道(labor+seasoning)保存成功', { toast: mixSaved.toast?.slice(0, 120) });
  ok(!/未设置调料配方|暂记\s*0|暂记0/.test(mixSaved.toast || ''), '无误报调料未配 warning', { toast: mixSaved.toast?.slice(0, 120) });

  // 9. 读 yield-card + cost-breakdown + getYield
  const mixCard = (await ycByOrder(planId, mixProc.processOrder))[0];
  ok(!!mixCard?.batchNumber, 'yield-card 含熟制道产出批', { batchNumber: mixCard?.batchNumber, addedCost: mixCard?.addedCost });
  if (!mixCard?.batchNumber) throw new Error('no mix card');

  const cb = await api('GET', `/${FACTORY}/production/batches/${encodeURIComponent(mixCard.batchNumber)}/cost-breakdown`).catch(() => null);
  ok(!!cb, 'cost-breakdown 返回', { seasoning: cb?.seasoningCost, labor: cb?.laborCost, total: cb?.totalCost });

  // ① 成本桶互不污染
  const seasoningExpected = r2(COOK_PER_KG * mixIn);
  approx(r2(num(cb?.seasoningCost)), seasoningExpected, 0.011, '调料桶=cookPerKg×投料 (不被人工污染)', { mixIn, cookPerKg: COOK_PER_KG });
  const laborExpected = r2(1 * 2 * laborRate); // 1h × 2人 × rate (本道 OWN 人工)
  // 熟制批 cost-breakdown.laborCost = 本道人工(56) + 上游修油人工(56, 按消耗比例 aggregateUpstreamLaborSeasoning 传播)。
  // 故 ≥ 本道, 且 ≤ 本道+上游全额; 本道 OWN 人工的精确隔离由下方 addedCost 断言保证 (不被调料污染)。
  const ownLabor = laborExpected;
  const cbLabor = r2(num(cb?.laborCost));
  ok(cbLabor >= ownLabor - 0.011 && cbLabor <= ownLabor + ownLabor + 0.5,
    '熟制批人工=本道+上游修油传播 (≥本道, ≤本道+上游)', { cbLabor, ownLabor, upstreamMax: r2(ownLabor * 2) });

  // addedCost (yield-card 该道新增, 隔离本道贡献) == 调料 + 本道人工 → 证本道两桶各自正确不串
  const addedExpected = r2(seasoningExpected + laborExpected);
  approx(r2(num(mixCard.addedCost)), addedExpected, 0.02, 'yield-card 熟制道 addedCost == 调料+本道人工 (同道两桶相加不串)', { seasoningExpected, laborExpected });

  // ② getYield 双计探针: 该熟制道 totalOutput == 真实产出 (NOT 2×)
  // batchNumber-keyed getYield (getBatchYieldByNumber) → BatchYieldDTO.steps[].totalOutput
  const gy = await api('GET', `/${FACTORY}/production/batches/${encodeURIComponent(mixCard.batchNumber)}/yield-summary`).catch(() => null);
  const steps = arr(gy?.steps);
  const mixStep = steps.find((s) => Number(s.processOrder) === Number(mixProc.processOrder))
    || steps.find((s) => /熟|卤|煮/.test(String(s.processName || '')))
    || (steps.length === 1 ? steps[0] : null);
  if (mixStep) {
    const gyOut = num(mixStep.totalOutput);
    const gyIn = num(mixStep.totalInput);
    console.log('getYield mixStep:', JSON.stringify({ totalOutput: gyOut, totalInput: gyIn, realOut: mixOut, realIn: mixIn }));
    approx(gyOut, mixOut, 0.011, 'getYield 熟制道 totalOutput == 真实产出 (NOT 2× — labor/seasoning 同道不双写 output)', { gyOut, realOut: mixOut, doubled: r2(mixOut * 2) });
    approx(gyIn, mixIn, 0.011, 'getYield 熟制道 totalInput == 真实投料 (NOT 2×)', { gyIn, realIn: mixIn, doubled: r2(mixIn * 2) });
  } else {
    ok(false, 'getYield 未取到熟制道 step (无法验双计)', { batchNumber: mixCard.batchNumber, steps: arr(gy?.steps).map((s) => s.processName) });
  }

  // ③ 副产 + 人工 同道 (修油/焯水/滚揉): 桶各自正确 + getYield output 不双计 (aux 不带 output, labor 带)
  if (bpStep?.bn) {
    const bcb = await api('GET', `/${FACTORY}/production/batches/${encodeURIComponent(bpStep.bn)}/cost-breakdown`).catch(() => null);
    console.log(`${bpStep.name} cost-breakdown:`, JSON.stringify({ byproduct: bcb?.byproductCredit, labor: bcb?.laborCost, net: bcb?.netTotalCost, total: bcb?.totalCost }));
    approx(r2(num(bcb?.byproductCredit)), r2(BP_CREDIT), 0.011, `${bpStep.name} 副产回收=数量×单价 (不被人工污染)`, { BP_QTY, BP_PRICE });
    approx(r2(num(bcb?.laborCost)), r2(1 * 2 * laborRate), 0.011, `${bpStep.name} 人工桶=1h×2人×单价 (副产+人工同道, 桶不串)`, {});
    ok(num(bcb?.netTotalCost) != null && r2(num(bcb?.netTotalCost)) <= r2(num(bcb?.totalCost)), `${bpStep.name} 净成本=总−副产 (净≤总)`, { net: bcb?.netTotalCost, total: bcb?.totalCost });
    // getYield 双计探针: 副产(aux 报工无 output) + 人工(有 output) 同道 → output 只一次
    const bgy = await api('GET', `/${FACTORY}/production/batches/${encodeURIComponent(bpStep.bn)}/yield-summary`).catch(() => null);
    const bsteps = arr(bgy?.steps);
    const bstep = bsteps.find((s) => Number(s.processOrder) === Number(bpStep.processOrder)) || (bsteps.length === 1 ? bsteps[0] : null);
    if (bstep) {
      console.log(`${bpStep.name} getYield:`, JSON.stringify({ totalOutput: bstep.totalOutput, realOut: bpStep.realOut }));
      approx(num(bstep.totalOutput), bpStep.realOut, 0.011, `${bpStep.name} getYield output 不双计 (副产 aux 不写 output, 人工写一次)`, { realOut: bpStep.realOut, doubled: r2(bpStep.realOut * 2) });
    } else {
      ok(false, `${bpStep.name} getYield 未取到 step`, { steps: bsteps.map((s) => s.processName) });
    }
  } else {
    ok(false, '副产+人工 道未成功录入 (无 bpStep)', {});
  }

  // DOM
  const rendered = await drawer().innerText().catch(() => '');
  ok(rendered.includes(String(mixProc.processName || '')), 'DOM 渲染熟制道', {});
  await shot('final');

  await finish('PASS', {
    setup: { name: setup.name, productTypeId: setup.productTypeId }, planNumber,
    oracle: { cookPerKg: COOK_PER_KG, mixIn, mixOut, seasoningExpected, laborExpected, laborRate },
    mix: { batchNumber: mixCard.batchNumber, addedCost: mixCard.addedCost },
    costBreakdown: cb,
    getYieldMixStep: mixStep ? { totalOutput: mixStep.totalOutput, totalInput: mixStep.totalInput } : null,
  });
} catch (error) {
  ok(false, '同道脚本异常', { error: error.message });
  await ctx.shot('error').catch(() => null);
  await finish('FAIL', { error: error.message, stack: error.stack });
}

async function finish(forcedStatus, extra = {}) {
  const failures = assertions.filter((a) => !a.pass);
  const status = forcedStatus === 'PASS' && failures.length === 0 ? 'PASS' : 'FAIL';
  await writeFile(resultFile, JSON.stringify({
    scenario: 'headed-samestep-labor-seasoning', depth: 'deep', target: 'web-admin-prod-F006',
    status, assertions, consoleErrors: ctx.consoleErrors, ...extra,
  }, null, 2), 'utf8').catch(() => null);
  console.log(JSON.stringify({ status, resultFile, failures: failures.map((f) => f.label) }, null, 2));
  await ctx.browser.close().catch(() => null);
  if (status !== 'PASS') process.exitCode = 1;
}

async function yc(planId) { return arr(await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/process-sheet/inventory/yield-card`)); }
async function ycByOrder(planId, order) { return (await yc(planId)).filter((x) => Number(x.processOrder) === Number(order)); }

async function saveRow(fill, errLabel) {
  const pane = activePane();
  await pane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
  await page.waitForTimeout(700);
  const row = pane.locator('table.sp-grid tbody tr.sp-tr').last();
  await fill(row);
  await row.locator('button').filter({ hasText: '保存' }).first().click();
  const w = await waitSaved();
  if (!w.saved) throw new Error(`${errLabel} save failed: ${w.toast}`);
  await page.waitForTimeout(1300);
  return w;
}

async function configureSeasoning(productTypeId) {
  const cur = await api('GET', `/${FACTORY}/bom/recipes/by-product/${encodeURIComponent(productTypeId)}/seasoning`).catch(() => null);
  let recipeId = cur?.bomRecipeId;
  const wasActive = recipeId && String(cur?.status) === 'ACTIVE';
  let saveTarget = recipeId;
  if (wasActive) {
    saveTarget = (await api('POST', `/${FACTORY}/bom/recipes/${recipeId}/clone`).catch(() => null))?.id || recipeId;
  } else if (!recipeId) {
    const recipeMt = (arr(await api('GET', `/${FACTORY}/bom/items/${encodeURIComponent(productTypeId)}`))[0] || {}).materialTypeId;
    saveTarget = recipeMt ? (await api('POST', `/${FACTORY}/bom/recipes`, {
      productTypeId, outputQuantityPerUnit: 120, outputUnit: 'g', overallYieldRate: 90, sourceType: 'MANUAL',
      items: [{ materialTypeId: recipeMt, standardQuantity: 1, materialCategory: 'RAW', unit: 'kg' }],
    }).catch(() => null))?.id : null;
  }
  if (!saveTarget) throw new Error('configureSeasoning: no target');
  await api('PUT', `/${FACTORY}/bom/recipes/${saveTarget}/seasoning`, {
    cookingPotBaseKg: 100, subsequentPotRatio: 0.8,
    seasoningItems: [{ section: 'COOKING', seq: 1, name: '卤料包', dosagePerKgG: DOSAGE_G_PER_KG, priceSource1: PRICE_1, countInSeasoning: true }],
  });
  if (wasActive && saveTarget !== recipeId) await api('POST', `/${FACTORY}/bom/recipes/${saveTarget}/activate`).catch(() => null);
}

async function findRawBatch(rawMaterial) {
  const whs = arr(await api('GET', `/${FACTORY}/factory/warehouses`));
  const rawWh = whs.find((w) => w.code === 'WH-LOG') || whs.find((w) => ['RAW', 'LOGISTICS'].includes(String(w.type)));
  if (!rawWh?.id) throw new Error('no raw warehouse');
  const batches = arr(await api('GET', `/${FACTORY}/material-batches/status/AVAILABLE?warehouseId=${encodeURIComponent(rawWh.id)}&size=200`));
  const eligible = batches.filter((b) => {
    const unit = String(b.quantityUnit || b.unit || '');
    return num(b.currentQuantity ?? b.quantity) > 1 && !/^WIP-|^CLK-/.test(String(b.batchNumber || '')) && WEIGHT_UNIT_RE.test(unit) && !COUNT_UNIT_RE.test(unit);
  });
  const matched = eligible.filter((b) => String(b.materialTypeId || '') === String(rawMaterial?.id || ''));
  const pool = matched.length ? matched : eligible;
  return pool.sort((a, b) => (num(b.currentQuantity ?? b.quantity) ?? 0) - (num(a.currentQuantity ?? a.quantity) ?? 0))[0];
}

async function openProcessDrawer(planNumber) {
  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  const search = page.locator('input[placeholder*="搜索"], input[placeholder*="计划编号"]').first();
  if (await search.isVisible().catch(() => false)) {
    await search.fill(planNumber);
    await page.locator('button').filter({ hasText: /搜索/ }).first().click().catch(() => null);
    await page.waitForTimeout(1800);
  }
  const r = page.locator('.el-table__row').filter({ hasText: planNumber }).first();
  if (!(await r.isVisible().catch(() => false))) throw new Error(`plan row not visible: ${planNumber}`);
  await r.locator('button, .el-button').filter({ hasText: '逐道录入' }).first().click();
  await page.waitForSelector('.el-drawer__body', { timeout: 15000 });
  await page.waitForTimeout(2200);
}

async function gotoTab(name) {
  const t = drawer().locator('.el-tabs__item').filter({ hasText: name }).first();
  if (await t.isVisible().catch(() => false)) { await t.click(); await page.waitForTimeout(1400); return true; }
  return false;
}

// 打开人工 expander (td.sp-td-labor 触发) + 加 1h×2人 段。返回是否填成功。
async function fillLaborSegment(pane, row) {
  try {
    await row.locator('td.sp-td-labor button').first().click();
    await page.waitForTimeout(600);
    const laborSec = pane.locator('.sp-expand-section').filter({ hasText: /工时录入/ }).last();
    await laborSec.locator('button').filter({ hasText: /工时段/ }).first().click();
    await page.waitForTimeout(500);
    const startInp = laborSec.locator('input[placeholder="开始"]').first();
    const endInp = laborSec.locator('input[placeholder="结束"]').first();
    await startInp.click(); await startInp.fill('08:00'); await startInp.press('Enter'); await page.waitForTimeout(300);
    await endInp.click(); await endInp.fill('09:00'); await endInp.press('Enter'); await page.waitForTimeout(300);
    const wc = laborSec.locator('.el-input-number input').first();
    await wc.click(); await wc.fill('2'); await wc.press('Tab'); await page.waitForTimeout(300);
    const t = await laborSec.innerText().catch(() => '');
    console.log('labor section:', t.replace(/\s+/g, ' ').slice(0, 130));
    return /合计工时.*[1-9]|08:00/.test(t);
  } catch (e) { console.log('labor fill error:', e.message); return false; }
}
