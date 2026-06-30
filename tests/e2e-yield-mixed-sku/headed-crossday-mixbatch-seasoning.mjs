/**
 * 组合深度 E2E (纯-headed, prod F006): 跨天 + 混批(真·混锅) + 调料成本 三维叠加。
 *
 * 这是 headed-multibatch-entry.mjs 自己点名的"单独扩展"(真混锅在链尾) + headed-crossday-cost.mjs
 * (跨天 processDate) + headed-seasoning-cost.mjs (调料精确 oracle) 三者的交集。
 *
 * 现有测试的盲区:
 *   - matrix-fullchain 做了 熟制混锅(2上游), 但调料只断言 seasoningCost>0, 不验"按混锅总投料的精确值";
 *     也从不让混锅录在与上游 WIP 不同的日子。
 *   - seasoning-cost 验了精确 oracle, 但熟制只消耗 1 个上游批(非混锅)。
 *   - crossday-cost 验了跨天 processDate, 但是单批单上游链(无混锅、无调料)。
 *
 * 本测试的判别性不变量 (verify-first, 见 ClerkProcessEntryServiceImpl.computeSeasoningCost
 *   + buildPotRawKgs + RecipeCostCalculator):
 *   逐道录入路径熟制道无 potCount → buildPotRawKgs = [st.inputQuantity] (单锅) →
 *   cookingTotal = inputQuantity × cookPerKg × 1。混锅时 inputQuantity = Σ来源批投料。
 *   ∴ seasoningCost == cookPerKg × (feedA + feedB), 与日期无关、与混锅源数无关只看总投料。
 *   若调料只按单源/某一锅计 → actual ≠ cookPerKg×总投料 → 本测试 FAIL (抓 bug)。
 *   resolveReportDate(st)=st.processDate → 跨天: 各行 processDate 回读=录入历史日。
 *
 * cookPerKg = (dosagePerKgG/1000) × max(p1,p2) = (50/1000)×20 = 1.0 元/kg。
 *
 * Env: E2E_USERNAME E2E_PASSWORD [E2E_ADMIN_URL E2E_FACTORY_ID PLAYWRIGHT_PORT]
 * Headed per .claude/rules/playwright-headed-mode.md.
 */
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import {
  APP, FACTORY, WEIGHT_UNIT_RE, COUNT_UNIT_RE, arr, num, today,
  startHeaded, setupSkuAndBom,
} from './_headed-helpers.mjs';

const OUT = path.resolve(`.playwright-mcp/crossday-mixbatch-seasoning-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const resultFile = path.join(OUT, 'headed-crossday-mixbatch-seasoning-result.json');

// 调料配方常量 (COOKING 单料) → cookPerKg 已知
const DOSAGE_G_PER_KG = 50;
const PRICE_1 = 20;
const COOK_PER_KG = (DOSAGE_G_PER_KG / 1000) * PRICE_1; // = 1.0 元/kg

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
  // ── 1. 建 SKU + BOM + 工序链(含熟制) ──────────────────────────────
  const setup = await setupSkuAndBom(page, { namePrefix: 'CDMS', gramsPerUnit: 120, api, shot, minProcesses: 6 });
  const procs = setup.processes
    .filter((p) => p.isActive !== false)
    .sort((a, b) => Number(a.processOrder || 0) - Number(b.processOrder || 0));
  const mixIdx = procs.findIndex((p) => /熟|卤|煮|腌|注射|入味|调味/.test(String(p.processName || '')));
  ok(mixIdx > 0, '工序链含可达的熟制/调味道(混锅落点)', {
    mixIdx, mixName: procs[mixIdx]?.processName, chain: procs.map((p) => p.processName).join('→'),
  });
  if (mixIdx <= 0) throw new Error('no reachable seasoning step in chain');

  // ── 2. 配 COOKING 调料配方 (cookPerKg=1.0) — 复用 clone→save→activate 版本流 ──
  await configureSeasoning(setup.productTypeId);
  const seaCheck = await api('GET', `/${FACTORY}/bom/recipes/by-product/${encodeURIComponent(setup.productTypeId)}/seasoning`).catch(() => null);
  const cookingRows = arr(seaCheck?.seasoningItems).filter((i) => String(i.section) === 'COOKING' && i.countInSeasoning !== false);
  ok(cookingRows.length >= 1, '调料配方 COOKING 行持久化(cookPerKg=1.0)', {
    status: seaCheck?.status, items: arr(seaCheck?.seasoningItems).map((i) => `${i.name}:${i.dosagePerKgG}g@${i.priceSource1}`).join('/'),
  });

  // ── 3. 建计划 (今天创建; 跨天靠各行 processDate 回填历史日, 非 plannedDate) ──
  const plan = await api('POST', `/${FACTORY}/production-plans`, {
    productTypeId: setup.productTypeId,
    plannedQuantity: 5,
    plannedDate: today(0),
    expectedCompletionDate: today(2),
    sourceType: 'MANUAL',
    skipProcessReporting: false,
    customerOrderNumber: `CDMS-${Date.now()}`,
    notes: 'crossday + mixbatch + seasoning combination',
  });
  const planId = String(plan.id || plan.planId);
  const planNumber = String(plan.planNumber || planId);
  await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/start`).catch(() => null);
  ok(!!planId, 'API 前置创建并启动跨天计划', { planNumber });

  // ── 4. 找 2 个重量单位原料批 ─────────────────────────────────────
  const raws = await findRawBatches(setup.rawMaterial);
  ok(raws.length >= 2, '发现 ≥2 个重量单位原料批(排除计数单位包材)', {
    batches: raws.slice(0, 2).map((b) => b.batchNumber),
  });
  if (raws.length < 2) throw new Error('need >=2 raw batches');

  // ── 5. 打开逐道录入抽屉 ─────────────────────────────────────────
  await openProcessDrawer(planNumber);
  await shot('drawer-open');

  // ── 6. 首道: 2 个原料批 录在不同日子 (跨天 + 混批起源) ──────────
  const dayA = today(-2), dayB = today(-1), dayMix = today(0);
  await gotoTab(procs[0].processName);
  await page.waitForTimeout(700);
  let firstSaved = 0;
  const firstSpecs = [
    { batch: raws[0], date: dayA, feed: 1.0, out: 0.9 },
    { batch: raws[1], date: dayB, feed: 0.8, out: 0.72 },
  ];
  for (const fs of firstSpecs) {
    const pane = activePane();
    await pane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
    await page.waitForTimeout(700);
    const row = pane.locator('table.sp-grid tbody tr.sp-tr').last();
    await setDateRange(row, fs.date);
    await selectByText(row.locator('.el-select').first(), fs.batch.batchNumber);
    const nums = row.locator('.el-input-number');
    await fillNum(nums.nth(0), fs.feed);
    await fillNum(nums.nth(1), fs.out);
    await row.locator('button').filter({ hasText: '保存' }).first().click();
    const w = await waitSaved();
    if (w.saved) firstSaved += 1;
    await page.waitForTimeout(1300);
  }
  ok(firstSaved === 2, '首道 UI 录入并保存 2 个原料批(跨 day-2/day-1)', { firstSaved });
  await shot('first-2batches-crossday');

  // ── 7. 中间道流转到熟制前 (保持 2 批存活) ──────────────────────
  let prev = (await ycByOrder(planId, procs[0].processOrder))
    .map((x) => ({ bn: x.batchNumber, rem: num(x.remaining) ?? 0.3 })).filter((x) => x.bn);
  ok(prev.length >= 2, '首道物化 2 个 WIP 批', { count: prev.length });

  for (let i = 1; i < mixIdx; i += 1) {
    const proc = procs[i];
    if (!(await gotoTab(proc.processName))) break;
    await page.waitForTimeout(700);
    const ups = prev.slice(0, 2);
    for (const up of ups) {
      const pane = activePane();
      await pane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
      await page.waitForTimeout(700);
      const row = pane.locator('table.sp-grid tbody tr.sp-tr').last();
      await setDateRange(row, dayB);
      await selectByText(row.locator('.el-select').first(), up.bn).catch(() => null);
      // 转发大部分余量 (非 0.3 上限钳制) 以保证熟制道仍有可观投料 → oracle 数值远离 0.02 floor,
      // 让 mixIn≠mixOut、feedA≠feedB, 调料 oracle 判别更强 (避免小数值 rounding 巧合).
      const feed = Math.max(0.05, r2(up.rem * 0.85)), out = r2(feed * 0.9);
      const nums = row.locator('.el-input-number');
      await fillNum(nums.nth(0), feed);
      await fillNum(nums.nth(1), out);
      await row.locator('button').filter({ hasText: '保存' }).first().click();
      await waitSaved();
      await page.waitForTimeout(1100);
    }
    prev = (await ycByOrder(planId, proc.processOrder))
      .map((x) => ({ bn: x.batchNumber, rem: num(x.remaining) ?? 0.3 })).filter((x) => x.bn);
    if (prev.length < 2) { ok(false, `中间道(${proc.processName})维持 2 批`, { count: prev.length }); break; }
  }

  // ── 8. 熟制 真·混锅: 消耗 2 上游, 录在 day0 (跨天叠加) ──────────
  const mixProc = procs[mixIdx];
  if (prev.length < 2) throw new Error('lost batches before seasoning step');
  await gotoTab(mixProc.processName);
  await page.waitForTimeout(800);
  const pane = activePane();
  await pane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
  await page.waitForTimeout(800);
  const mixRow = pane.locator('table.sp-grid tbody tr.sp-tr').last();
  await setDateRange(mixRow, dayMix);
  await mixRow.locator('button').filter({ hasText: /来源批/ }).first().click();
  await page.waitForTimeout(800);
  const mixSec = pane.locator('.sp-tr-expand .sp-expand-section, .sp-expand-section').last();
  const feeds = [];
  const mixFrac = [0.7, 0.45]; // 故意不等分摊 → feedA≠feedB, 强化"按总投料非单源"判别
  for (let k = 0; k < 2; k += 1) {
    const feed = Math.max(0.05, r2(Math.min(prev[k].rem * mixFrac[k], 0.5)));
    feeds.push(feed);
    await mixSec.locator('button').filter({ hasText: /来源批/ }).first().click();
    await page.waitForTimeout(600);
    await selectByText(mixSec.locator('.el-select').nth(k), prev[k].bn).catch(() => null);
    await fillNum(mixSec.locator('.el-input-number').nth(k), feed).catch(() => null);
    await page.waitForTimeout(300);
  }
  const feedA = feeds[0], feedB = feeds[1];
  const mixIn = r2(feedA + feedB);
  const mixOut = r2(mixIn * 0.9);
  const mainNums = mixRow.locator('.el-input-number');
  await fillNum(mainNums.nth(0), mixIn);
  await fillNum(mainNums.nth(1), mixOut);
  await shot('mix-seasoning-row');
  await mixRow.locator('button').filter({ hasText: '保存' }).first().click();
  const mixSaved = await waitSaved();
  await page.waitForTimeout(1600);
  ok(mixSaved.saved, '熟制混锅行保存成功(2 上游, day0)', { feeds, mixIn, mixOut, toast: mixSaved.toast?.slice(0, 120) });

  // ── 9. UX: 无误报"未设置调料配方/暂记0"警告 (调料已配) ──────────
  ok(!/未设置调料配方|调料成本暂记\s*0|暂记0|无调料明细/.test(mixSaved.toast || ''),
    '混锅保存 toast 未误报调料未配(配方已存在, 防呆 Rule)', { toast: mixSaved.toast?.slice(0, 160) });

  // ── 10. 读 yield-card + cost-breakdown 验三方 ──────────────────
  const mixCard = (await ycByOrder(planId, mixProc.processOrder))[0];
  ok(!!mixCard?.batchNumber, 'plan-scoped yield-card 含熟制混锅产出批', {
    batchNumber: mixCard?.batchNumber, inheritedCost: mixCard?.inheritedCost, addedCost: mixCard?.addedCost,
  });
  if (!mixCard?.batchNumber) throw new Error('no mix yield card');

  const cb = await api('GET', `/${FACTORY}/production/batches/${encodeURIComponent(mixCard.batchNumber)}/cost-breakdown`).catch(() => null);
  ok(!!cb, 'cost-breakdown 端点返回混锅批分桶', { totalCost: cb?.totalCost });

  // 核心不变量 ①: 调料成本 == cookPerKg × 总投料 (混锅总投料, 投料-based)
  const seasoningExpected = r2(COOK_PER_KG * mixIn);
  const seasoningActual = r2(num(cb?.seasoningCost));
  approx(seasoningActual, seasoningExpected, 0.011,
    '调料成本精确 == cookPerKg × 混锅总投料(feedA+feedB) (核心判别)', {
      cookPerKg: COOK_PER_KG, mixIn, feedA, feedB,
    });

  // 核心不变量 ②: 判别性 — 调料按"总投料"而非"单一源". 若按单源 feedA 则值不同.
  const seasoningIfSingle = r2(COOK_PER_KG * feedA);
  const discriminative = Math.abs(seasoningExpected - seasoningIfSingle) >= 0.01;
  ok(discriminative, '判别前提: 总投料 vs 单源 调料值可区分(feedA<总投料)', {
    seasoningExpected, seasoningIfSingle, feedA, mixIn,
  });
  if (discriminative) {
    ok(Math.abs(seasoningActual - seasoningIfSingle) >= 0.01,
      '调料 NOT 仅按单一源 feedA 计 (确认按混锅总投料)', { seasoningActual, seasoningIfSingle });
  }
  // 核心不变量 ②b: 调料按投料(input)非产出(output). mixIn≠mixOut 时可判别.
  const seasoningIfOutput = r2(COOK_PER_KG * mixOut);
  if (Math.abs(seasoningExpected - seasoningIfOutput) >= 0.01) {
    ok(Math.abs(seasoningActual - seasoningIfOutput) >= 0.01,
      '调料按投料(input)非产出(output) (投料-based 确认)', { seasoningActual, seasoningIfOutput, mixIn, mixOut });
  }

  // ③: yield-card addedCost ≈ 调料 (熟制道未填工时/副产 → addedCost 主要是调料), 三方旁证
  const addedCost = r2(num(mixCard.addedCost));
  approx(addedCost, seasoningExpected, 0.02,
    'yield-card addedCost ≈ 调料 oracle (熟制道无工时/副产, addedCost≈seasoning)', { addedCost });

  // ④: 混批 2 源分摊 — 继承成本>0, 源明细 2 条, 占比和≈100%
  const srcs = arr(mixCard.sourceBreakdowns || mixCard.sources || cb?.sources || []);
  ok(num(mixCard.inheritedCost) > 0, '混锅继承成本>0 (2 源原料成本分摊上来)', { inheritedCost: mixCard.inheritedCost });
  ok(srcs.length >= 2, '混锅产出批含 ≥2 条来源明细(真混批血缘)', { count: srcs.length, sample: srcs.slice(0, 2) });
  const pctSum = srcs.reduce((acc, s) => acc + (num(s.percentage ?? s.ratio ?? s.proportion ?? s.weightPct) ?? 0), 0);
  if (srcs.length >= 2 && pctSum > 0) {
    approx(pctSum, 100, 1.0, '2 源占比和 ≈ 100% (分摊闭合)', { pctSum });
  }

  // ⑤: 原料成本桶>0 (跨天混批两源都流入)
  ok(num(cb?.rawMaterialCost) > 0, '原料成本桶>0 (跨天2源原料成本均流入)', { rawMaterialCost: cb?.rawMaterialCost });

  // ── 11. 跨天: processDate 回读 == 录入历史日 ────────────────────
  const firstRows = await getRowsForProcess(planId, procs[0]);
  const firstDates = [...new Set(firstRows.map(rowDate).filter(Boolean))].sort();
  ok(firstDates.length === 2 && firstDates[0] === dayA && firstDates[1] === dayB,
    '首道 2 行 processDate 回读 == 跨天录入日(day-2, day-1)', { expected: [dayA, dayB], actual: firstDates });

  const mixRows = await getRowsForProcess(planId, mixProc);
  const mixDate = rowDate(mixRows[0]);
  ok(mixDate === dayMix, '熟制混锅行 processDate 回读 == day0(跨天叠加调料不串日)', { expected: dayMix, actual: mixDate });

  // ── 12. DOM 渲染 (UX) ───────────────────────────────────────────
  const rendered = await drawer().innerText().catch(() => '');
  ok(rendered.includes(String(mixProc.processName || '')), 'DOM 渲染含熟制工序 tab/内容', { processName: mixProc.processName });
  await shot('final');

  await finish('PASS', {
    setup: { name: setup.name, productTypeId: setup.productTypeId },
    planNumber, planId,
    oracle: { cookPerKg: COOK_PER_KG, feedA, feedB, mixIn, seasoningExpected },
    mix: { batchNumber: mixCard.batchNumber, seasoningActual, addedCost, inheritedCost: mixCard.inheritedCost, sources: srcs.length },
    costBreakdown: cb,
    crossday: { firstDates, mixDate },
  });
} catch (error) {
  ok(false, '组合脚本异常', { error: error.message });
  await ctx.shot('error').catch(() => null);
  await finish('FAIL', { error: error.message, stack: error.stack });
}

// ─────────────────────────────────────────────────────────────────
async function finish(forcedStatus, extra = {}) {
  const failures = assertions.filter((a) => !a.pass);
  const status = forcedStatus === 'PASS' && failures.length === 0 ? 'PASS' : 'FAIL';
  await writeFile(resultFile, JSON.stringify({
    scenario: 'headed-crossday-mixbatch-seasoning',
    depth: 'deep',
    target: 'web-admin-prod-F006',
    status,
    assertions,
    consoleErrors: ctx.consoleErrors,
    ...extra,
  }, null, 2), 'utf8').catch(() => null);
  console.log(JSON.stringify({ status, resultFile, failures: failures.map((f) => f.label) }, null, 2));
  await ctx.browser.close().catch(() => null);
  if (status !== 'PASS') process.exitCode = 1;
}

async function yc(planId) {
  return arr(await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/process-sheet/inventory/yield-card`));
}
async function ycByOrder(planId, order) {
  return (await yc(planId)).filter((x) => Number(x.processOrder) === Number(order));
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
    saveTarget = recipeMt
      ? (await api('POST', `/${FACTORY}/bom/recipes`, {
        productTypeId, outputQuantityPerUnit: 120, outputUnit: 'g', overallYieldRate: 90, sourceType: 'MANUAL',
        items: [{ materialTypeId: recipeMt, standardQuantity: 1, materialCategory: 'RAW', unit: 'kg' }],
      }).catch(() => null))?.id
      : null;
  }
  if (!saveTarget) throw new Error('configureSeasoning: no recipe target');
  await api('PUT', `/${FACTORY}/bom/recipes/${saveTarget}/seasoning`, {
    cookingPotBaseKg: 100,
    subsequentPotRatio: 0.8,
    seasoningItems: [{ section: 'COOKING', seq: 1, name: '卤料包', dosagePerKgG: DOSAGE_G_PER_KG, priceSource1: PRICE_1, countInSeasoning: true }],
  });
  if (wasActive && saveTarget !== recipeId) {
    await api('POST', `/${FACTORY}/bom/recipes/${saveTarget}/activate`).catch(() => null);
  }
}

async function findRawBatches(rawMaterial) {
  const whs = arr(await api('GET', `/${FACTORY}/factory/warehouses`));
  const rawWh = whs.find((w) => w.code === 'WH-LOG') || whs.find((w) => ['RAW', 'LOGISTICS'].includes(String(w.type)));
  if (!rawWh?.id) throw new Error('no raw/logistics warehouse');
  const batches = arr(await api('GET', `/${FACTORY}/material-batches/status/AVAILABLE?warehouseId=${encodeURIComponent(rawWh.id)}&size=200`));
  const eligible = batches.filter((b) => {
    const unit = String(b.quantityUnit || b.unit || '');
    return num(b.currentQuantity ?? b.quantity) > 1
      && !/^WIP-|^CLK-/.test(String(b.batchNumber || ''))
      && WEIGHT_UNIT_RE.test(unit) && !COUNT_UNIT_RE.test(unit);
  });
  // 优先同 BOM 原料类型, 不足则放宽到任意重量单位批 (确保拿到 2 个独立批)
  const matched = eligible.filter((b) => String(b.materialTypeId || '') === String(rawMaterial?.id || ''));
  const pool = matched.length >= 2 ? matched : eligible;
  // 按库存量降序, 避开被前序测试耗尽的批 (§13)
  return pool.sort((a, b) => (num(b.currentQuantity ?? b.quantity) ?? 0) - (num(a.currentQuantity ?? a.quantity) ?? 0));
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
  const row = page.locator('.el-table__row').filter({ hasText: planNumber }).first();
  if (!(await row.isVisible().catch(() => false))) throw new Error(`plan row not visible: ${planNumber}`);
  await row.locator('button, .el-button').filter({ hasText: '逐道录入' }).first().click();
  await page.waitForSelector('.el-drawer__body', { timeout: 15000 });
  await page.waitForTimeout(2200);
}

async function gotoTab(name) {
  const t = drawer().locator('.el-tabs__item').filter({ hasText: name }).first();
  if (await t.isVisible().catch(() => false)) { await t.click(); await page.waitForTimeout(1400); return true; }
  return false;
}

async function setDateRange(row, date) {
  const picker = row.locator('.el-date-editor--daterange').first();
  if (!(await picker.isVisible().catch(() => false))) return false;
  await picker.click();
  await page.waitForSelector('.el-picker-panel:visible', { timeout: 5000 }).catch(() => null);
  const day = String(Number(date.slice(-2)));
  const cells = page.locator('.el-picker-panel:visible .el-date-table td:not(.disabled)')
    .filter({ hasText: new RegExp(`^\\s*${day}\\s*$`) });
  await cells.first().click();
  await page.waitForTimeout(200);
  await cells.first().click().catch(() => null); // range start == end (same day)
  await page.waitForTimeout(300);
  await row.locator('td').first().click().catch(() => null);
  return true;
}

async function getRowsForProcess(planId, process) {
  const codes = ['xiuyou', 'gunrou', 'chaoshui', 'qushetou', 'shuzhi', 'qidiao', 'baozhuang', 'fenqie', 'zhuanghe', 'yanzhi', 'zhushe'];
  for (const code of codes) {
    const rows = arr(await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/process-sheet/rows?process=${code}&processOrder=${process.processOrder}`).catch(() => []));
    if (rows.length) return rows;
  }
  return [];
}

function rowDate(row) {
  if (!row) return null;
  const p = row.payload || row;
  return p.processDate
    || (Array.isArray(p.prodDate) ? p.prodDate[0] : p.prodDate)
    || (Array.isArray(p.date) ? p.date[0] : p.date)
    || null;
}
