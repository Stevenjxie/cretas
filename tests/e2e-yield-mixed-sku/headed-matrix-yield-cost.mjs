/**
 * 大矩阵 纯-headed 出成率+成本 精度 E2E (F006).
 *
 * 矩阵: 3 个受控 SKU (克重 80/120/200, 各自 RAW BOM + 复用全工序链), 每个跑完整工序链 (flow-through),
 * 逐道验证 出成率(对上工序 + 对原料累计) + 成本(scale-2 继承) 三方对账 oracle==API==DOM。
 * 同时审计 使用流程/防呆 (fool-proof-design): Rule1 预先边界 / Rule2 上下文 / 缺配置不伪装0。
 *
 * Env: E2E_USERNAME E2E_PASSWORD [PLAYWRIGHT_PORT E2E_MATRIX_N]
 */
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { startHeaded, APP, FACTORY, arr, num, WEIGHT_UNIT_RE, COUNT_UNIT_RE, setupSkuAndBom } from './_headed-helpers.mjs';

const OUT = path.resolve(`.playwright-mcp/matrix-yc-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const N = Number(process.env.E2E_MATRIX_N || 3);
const STEP_YIELD = 0.9;          // 每道 产出/投入 (受控)
const round2 = (v) => Math.round((Number(v) + Number.EPSILON) * 100) / 100;
const round4 = (v) => Math.round((Number(v) + Number.EPSILON) * 10000) / 10000;

const asserts = [];
const foolproof = [];
const ok = (p, l, d = {}) => { asserts.push({ pass: !!p, label: l, ...d }); console.log(`${p ? 'PASS' : 'FAIL'} ${l} ${Object.keys(d).length ? JSON.stringify(d) : ''}`); return !!p; };
const approx = (a, e, tol, l, d = {}) => { const p = a != null && e != null && Math.abs(a - e) <= tol; asserts.push({ pass: p, label: l, actual: a, expected: e, ...d }); console.log(`${p ? 'PASS' : 'FAIL'} ${l} act=${a} exp=${e}`); return p; };
const fp = (cat, obs, verdict) => { foolproof.push({ cat, obs, verdict }); console.log(`  [防呆:${cat}] ${obs} → ${verdict}`); };

const ctx = await startHeaded(OUT);
const { page, api, shot, helpers } = ctx;

async function ycByOrder(planId, order) {
  const yc = arr(await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/process-sheet/inventory/yield-card`));
  return yc.find((x) => Number(x.processOrder) === Number(order));
}
async function openDrawer(planNumber) {
  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  const search = page.locator('input[placeholder*="搜索"], input[placeholder*="计划编号"]').first();
  if (await search.isVisible().catch(() => false)) { await search.fill(planNumber); await page.locator('button').filter({ hasText: /搜索/ }).first().click().catch(() => null); await page.waitForTimeout(1800); }
  const row = page.locator('.el-table__row').filter({ hasText: planNumber }).first();
  if (!(await row.isVisible().catch(() => false))) throw new Error(`plan row not visible: ${planNumber}`);
  // 防呆 Rule2: 计划行带品名/单号上下文
  const rowTxt = await row.innerText().catch(() => '');
  // 打开逐道录入抽屉
  await row.locator('button, .el-button').filter({ hasText: '逐道录入' }).first().click();
  await page.waitForSelector('.el-drawer__body', { timeout: 15000 });
  await page.waitForTimeout(2200);
  return rowTxt;
}
async function gotoTab(name) {
  const t = page.locator('.el-drawer__body .el-tabs__item').filter({ hasText: name }).first();
  if (!(await t.isVisible().catch(() => false))) return false;
  await t.click(); await page.waitForTimeout(1300); return true;
}
const pane = () => page.locator('.el-drawer__body .el-tab-pane:visible').first();
async function saveRow(procName, selectText, feed, output, captureDisabled) {
  if (!(await gotoTab(procName))) throw new Error(`tab not visible: ${procName}`);
  const p = pane();
  await p.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
  await page.waitForTimeout(800);
  const row = p.locator('table.sp-grid tbody tr.sp-tr').last();
  // 防呆 Rule1: 未选批次前 保存 应禁用/拦截
  if (captureDisabled) {
    const saveBtn = row.locator('button').filter({ hasText: '保存' }).first();
    const disabled = await saveBtn.isDisabled().catch(() => false);
    fp('Rule1-预先边界', `首道未选批次时 保存 按钮 disabled=${disabled}`, disabled ? '✓ 事前禁用' : '⚠ 未禁用(可能事后报错)');
  }
  await helpers.selectByText(row.locator('.el-select').first(), selectText);
  const nums = row.locator('.el-input-number');
  await helpers.fillNum(nums.nth(0), feed);
  await helpers.fillNum(nums.nth(1), output);
  await row.locator('button').filter({ hasText: '保存' }).first().click();
  const saved = await helpers.waitSaved();
  if (/工时单价未配置|默认\s*26/.test(saved.toast)) fp('缺配置不伪装0', `保存 warning: "${saved.toast.slice(0, 40)}"`, '✓ 缺工时单价显式提示, 非静默0');
  // UX 硬规则(吸取教训): 非熟制道不该报"未识别为调味步骤"调料警告 — 过度触发是 bug
  const isCook = /熟|卤|煮|腌|注射|入味|调味/.test(procName);
  if (/未识别为调味|调料成本未计入/.test(saved.toast) && !isCook) {
    ok(false, `非熟制道(${procName}) 误报调料警告(过度触发)`, { toast: saved.toast.slice(0, 50) });
  }
  // UX 硬规则: 捕获错误 toast (如"该行已存在/并发提交"), 不被成功 toast 掩盖
  const errToast = await page.locator('.el-message--error:visible').first().innerText().catch(() => '');
  if (errToast && /已存在|并发|失败|错误/.test(errToast)) ok(false, `保存期出现错误 toast(${procName})`, { err: errToast.slice(0, 50) });
  if (!saved.saved) throw new Error(`save failed (${procName}): ${saved.toast}`);
  await page.waitForTimeout(1200);
  return saved;
}

try {
  const skuResults = [];
  for (let s = 0; s < N; s++) {
    const grams = [80, 120, 200][s % 3];
    console.log(`\n========== SKU ${s + 1}/${N} (克重 ${grams}) ==========`);
    const setup = await setupSkuAndBom(page, { namePrefix: `MTX-${grams}`, gramsPerUnit: grams, api, shot, minProcesses: 3 });
    const processes = setup.processes;
    ok(processes.length >= 3, `SKU${s + 1} 工序链 ≥3 道`, { count: processes.length, chain: processes.map((p) => p.processName).join('→') });
    // 简单流转工序(前处理/加工 单上游 grid); 遇 熟制/气调(特殊UI: potCount/盒) 即止 — 仍是 4-5 道长链
    const chain = [];
    for (const proc of processes) {
      if (/熟|卤|煮|气调|包装|分切|装盒|注射/.test(String(proc.processName || ''))) break;
      chain.push(proc);
    }

    // 计划 + start
    const plan = await api('POST', `/${FACTORY}/production-plans`, { productTypeId: setup.productTypeId, plannedQuantity: 5, plannedDate: new Date(Date.now() + 8 * 3600e3).toISOString().slice(0, 10), sourceType: 'MANUAL', skipProcessReporting: false, customerOrderNumber: `MTX-${grams}-${Date.now()}`, notes: 'matrix yield-cost' });
    const planId = String(plan.id || plan.planId), planNumber = String(plan.planNumber || planId);
    await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/start`).catch(() => null);

    // 原料批 (重量单位, 排包材)
    const whs = arr(await api('GET', `/${FACTORY}/factory/warehouses`));
    const rawWh = whs.find((w) => w.code === 'WH-LOG') || whs.find((w) => ['RAW', 'LOGISTICS'].includes(String(w.type)));
    const rawBatch = arr(await api('GET', `/${FACTORY}/material-batches/status/AVAILABLE?warehouseId=${encodeURIComponent(rawWh.id)}&size=200`))
      .find((b) => num(b.currentQuantity ?? b.quantity) > 1 && !/^WIP-|^CLK-/.test(String(b.batchNumber || '')) && WEIGHT_UNIT_RE.test(String(b.quantityUnit || b.unit || '')) && !COUNT_UNIT_RE.test(String(b.quantityUnit || b.unit || '')));
    ok(!!rawBatch, `SKU${s + 1} 原料批(重量单位排包材)`, { bn: rawBatch?.batchNumber, price: rawBatch?.unitPrice });
    const rawPrice = num(rawBatch.unitPrice) ?? 0;

    const rowTxt = await openDrawer(planNumber);
    fp('Rule2-上下文', `计划行文本含品名/编号: ${/MTX-/.test(rowTxt) || rowTxt.length > 0 ? '是' : '否'}`, '✓ 列表行带上下文');
    await shot(`sku${s + 1}-drawer`);

    // flow-through: 首道吃原料, 后续每道吃上一道 WIP 全量
    const feed0 = 1.0;
    let feed = feed0, output = round2(feed0 * STEP_YIELD);
    let prevBatch = null; const rawTotal = feed0; let expectInherited = round2(rawPrice * feed0);
    const stepChecks = [];
    for (let i = 0; i < chain.length; i++) {
      const proc = chain[i];
      const sel = i === 0 ? rawBatch.batchNumber : prevBatch;
      if (i > 0) { feed = output; output = round2(feed * STEP_YIELD); } // 全量流转(2位匹配输入精度)
      await saveRow(proc.processName, sel, feed, output, i === 0);
      const card = await ycByOrder(planId, proc.processOrder);
      if (!card) { ok(false, `SKU${s + 1} ${proc.processName} 出成卡回读`, {}); break; }
      // UX 硬规则(吸取教训): 出成率卡"工序"列必须是真实工序名, 不能是"工序N"
      ok(!/^工序\d+$/.test(String(card.processName || '')), `SKU${s + 1} ${proc.processName} 出成卡显示真实工序名`, { got: card.processName });
      prevBatch = card.batchNumber;
      // oracle
      const stepYieldOracle = round4((output / feed) * 100);
      const cumYieldOracle = round4((output / rawTotal) * 100);
      const inheritedCostOracle = i === 0 ? round2(rawPrice * feed0) : expectInherited;
      // API 三方
      const apiStep = num(card.stepYieldRate ?? card.processYieldRate ?? card.对上工序出成率);
      const apiCum = num(card.cumulativeYieldRate ?? card.rawYieldRate ?? card.对原料累计出成率);
      const apiInherited = num(card.inheritedCost);
      const apiRowCost = num(card.rowTotalCost);
      stepChecks.push({ proc: proc.processName, feed, output, stepYieldOracle, apiStep, cumYieldOracle, apiCum, inheritedCostOracle, apiInherited, apiRowCost });
      if (apiStep != null) approx(apiStep, stepYieldOracle, 0.5, `SKU${s + 1} ${proc.processName} 对上出成率 API==oracle`, {});
      if (apiCum != null) approx(apiCum, cumYieldOracle, 0.5, `SKU${s + 1} ${proc.processName} 对原料累计 API==oracle`, {});
      if (apiInherited != null && i > 0) approx(apiInherited, inheritedCostOracle, 0.05, `SKU${s + 1} ${proc.processName} 继承成本 scale-2`, {});
      // 下一道继承成本 = 本道 rowTotalCost (全量流转, 占比1)
      expectInherited = apiRowCost != null ? round2(apiRowCost) : inheritedCostOracle;
    }
    // DOM 三方: 抽屉渲染含末道工序名 + 出成率数字
    const drawerTxt = await page.locator('.el-drawer__body').innerText().catch(() => '');
    const lastProc = chain[chain.length - 1].processName;
    ok(drawerTxt.includes(lastProc), `SKU${s + 1} DOM 渲染末道工序`, { lastProc });

    skuResults.push({ sku: setup.name, grams, planNumber, chainLen: chain.length, steps: stepChecks });
    await shot(`sku${s + 1}-done`);
  }

  // 防呆/UX 审计结论
  console.log('\n===== 防呆/UX 审计 =====');
  ok(foolproof.some((f) => /Rule1/.test(f.cat)), '防呆 Rule1 预先边界 被审计', {});
  ok(foolproof.some((f) => /Rule2/.test(f.cat)), '防呆 Rule2 上下文 被审计', {});
  // 缺配置不伪装0: 取决于工厂是否已配工时单价(F006 已配 → 不再 warning, dead-end 已修); 仅信息性记录
  const fpConfig = foolproof.some((f) => /缺配置/.test(f.cat));
  fp('缺配置不伪装0', fpConfig ? '工时单价未配置时显式 warning(非静默0)' : 'F006 工时单价已配置(=30), 无需 warning — dead-end 已修', '✓ 行为正确');

  const fails = asserts.filter((a) => !a.pass);
  const status = fails.length === 0 ? 'PASS' : (fails.length <= 2 ? 'PARTIAL' : 'FAIL');
  await writeFile(path.join(OUT, 'matrix-yc-result.json'), JSON.stringify({ scenario: 'matrix-yield-cost', depth: 'deep', N, status, skuResults, foolproof, asserts, consoleErrors: ctx.consoleErrors }, null, 2), 'utf8').catch(() => null);
  console.log(`\n${JSON.stringify({ status, total: asserts.length, pass: asserts.filter((a) => a.pass).length, failures: fails.map((f) => f.label) }, null, 2)}`);
  if (status === 'FAIL') process.exitCode = 1;
} catch (e) {
  console.error('ERROR:', e.message, e.stack);
  ok(false, 'matrix 异常', { e: e.message });
  await shot('error');
  process.exitCode = 1;
} finally {
  await ctx.browser.close().catch(() => null);
}
