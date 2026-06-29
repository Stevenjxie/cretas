/**
 * 深度: 填"空"成本信息 — 副产(肥油/料头变现) + 人工 (F006 之前都为空).
 * Headed 录一道带副产(byproductQty×byproductPrice)的工序, 核 副产回收 = 数量×单价 进入成本口径(冲减),
 * 并填工时段核 laborCost>0。三方: oracle(qty×price) == 后端成本端点 == (DOM 渲染).
 *
 * Env: E2E_USERNAME E2E_PASSWORD [PLAYWRIGHT_PORT]
 */
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { startHeaded, APP, FACTORY, arr, num, today, WEIGHT_UNIT_RE, COUNT_UNIT_RE, setupSkuAndBom } from './_headed-helpers.mjs';

const OUT = path.resolve(`.playwright-mcp/byproduct-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const BP_QTY = 0.5, BP_PRICE = 4; // 副产 0.5kg × 4元/kg = 2.00 回收
const asserts = [];
const ok = (p, l, d = {}) => { asserts.push({ pass: !!p, label: l, ...d }); console.log(`${p ? 'PASS' : 'FAIL'} ${l} ${Object.keys(d).length ? JSON.stringify(d) : ''}`); return !!p; };
const approx = (a, e, tol, l) => { const p = a != null && e != null && Math.abs(a - e) <= tol; asserts.push({ pass: p, label: l, actual: a, expected: e }); console.log(`${p ? 'PASS' : 'FAIL'} ${l} act=${a} exp=${e}`); return p; };

const ctx = await startHeaded(OUT);
const { page, api, shot, helpers } = ctx;
const { selectByText, fillNum, waitSaved } = helpers;
const drawer = () => page.locator('.el-drawer__body');
const activePane = () => drawer().locator('.el-tab-pane:visible').first();
async function gotoTab(name) { const t = drawer().locator('.el-tabs__item').filter({ hasText: name }).first(); if (await t.isVisible().catch(() => false)) { await t.click(); await page.waitForTimeout(1300); return true; } return false; }
async function ycByOrder(planId, order) { const yc = arr(await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/process-sheet/inventory/yield-card`)); return yc.filter((x) => Number(x.processOrder) === Number(order)); }

try {
  const setup = await setupSkuAndBom(page, { namePrefix: 'BYP', gramsPerUnit: 100, api, shot, minProcesses: 3 });
  const procs = setup.processes;
  // 找一道带副产列的工序 (修油/滚揉/焯水), 非首道
  const bpProc = procs.slice(1).find((p) => /修油|滚揉|焯水/.test(String(p.processName || ''))) || procs[1];
  ok(!!bpProc, `找到副产工序: ${bpProc?.processName}`, { chain: procs.map((p) => p.processName).join('→') });

  const plan = await api('POST', `/${FACTORY}/production-plans`, { productTypeId: setup.productTypeId, plannedQuantity: 5, plannedDate: today(0), expectedCompletionDate: today(2), sourceType: 'MANUAL', skipProcessReporting: false, customerOrderNumber: `BYP-${Date.now()}`, notes: 'byproduct+labor deep' });
  const planId = String(plan.id || plan.planId), planNumber = String(plan.planNumber || planId);
  await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/start`).catch(() => null);

  const whs = arr(await api('GET', `/${FACTORY}/factory/warehouses`));
  const rawWh = whs.find((w) => w.code === 'WH-LOG') || whs.find((w) => ['RAW', 'LOGISTICS'].includes(String(w.type)));
  const raw = arr(await api('GET', `/${FACTORY}/material-batches/status/AVAILABLE?warehouseId=${encodeURIComponent(rawWh.id)}&size=200`))
    .find((b) => num(b.currentQuantity ?? b.quantity) > 1 && !/^WIP-|^CLK-/.test(String(b.batchNumber || '')) && WEIGHT_UNIT_RE.test(String(b.quantityUnit || b.unit || '')) && !COUNT_UNIT_RE.test(String(b.quantityUnit || b.unit || '')));
  ok(!!raw, '原料批', { bn: raw?.batchNumber });

  // open drawer
  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  const sb = page.locator('input[placeholder*="搜索"], input[placeholder*="计划编号"]').first();
  if (await sb.isVisible().catch(() => false)) { await sb.fill(planNumber); await page.locator('button').filter({ hasText: /搜索/ }).first().click().catch(() => null); await page.waitForTimeout(1800); }
  await page.locator('.el-table__row').filter({ hasText: planNumber }).first().locator('button, .el-button').filter({ hasText: '逐道录入' }).first().click();
  await page.waitForSelector('.el-drawer__body', { timeout: 15000 });
  await page.waitForTimeout(2200);

  // 首道: 原料 → WIP
  await gotoTab(procs[0].processName); await page.waitForTimeout(700);
  let p = activePane();
  await p.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
  await page.waitForTimeout(700);
  let row = p.locator('table.sp-grid tbody tr.sp-tr').last();
  await selectByText(row.locator('.el-select').first(), raw.batchNumber);
  let nums = row.locator('.el-input-number');
  await fillNum(nums.nth(0), 1.0); await fillNum(nums.nth(1), 0.9);
  await row.locator('button').filter({ hasText: '保存' }).first().click();
  ok((await waitSaved()).saved, '首道保存', {});
  await page.waitForTimeout(1200);

  // 副产工序: 上游 WIP → 填 副产(kg) + 副产回收单价 + 工时段
  const up = (await ycByOrder(planId, procs[0].processOrder))[0]?.batchNumber;
  ok(!!up, '首道产出 WIP 批', { up });
  await gotoTab(bpProc.processName); await page.waitForTimeout(800);
  p = activePane();
  await p.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
  await page.waitForTimeout(700);
  row = p.locator('table.sp-grid tbody tr.sp-tr').last();
  await selectByText(row.locator('.el-select').first(), up);
  nums = row.locator('.el-input-number');
  // 列序(滚揉/修油): before(0) after(1) byproductQty(2) byproductPrice(3)
  await fillNum(nums.nth(0), 0.9);   // 投入
  await fillNum(nums.nth(1), 0.81);  // 产出
  await fillNum(nums.nth(2), BP_QTY);   // 副产(kg)
  await fillNum(nums.nth(3), BP_PRICE); // 副产回收单价
  await shot('byproduct-filled');
  // 工时段 expander (labor, 之前为空)
  await row.locator('button').filter({ hasText: /h.*段|工时|0\.0h/ }).first().click().catch(() => null);
  await page.waitForTimeout(700);
  const laborSec = p.locator('.sp-tr-expand, .sp-expand-section').last();
  await laborSec.locator('button').filter({ hasText: /工时段|\+/ }).first().click().catch(() => null);
  await page.waitForTimeout(500);
  const tInputs = laborSec.locator('.el-date-editor input, input[placeholder="开始"], input[placeholder="结束"]');
  if (await tInputs.count() >= 2) {
    await tInputs.nth(0).fill('08:00').catch(() => null);
    await tInputs.nth(1).fill('10:00').catch(() => null);
    const wc = laborSec.locator('.el-input-number input').last();
    await wc.fill('2').catch(() => null); await wc.press('Tab').catch(() => null);
  }
  await shot('byproduct-labor-filled');
  await row.locator('button').filter({ hasText: '保存' }).first().click();
  const saved = await waitSaved();
  ok(saved.saved, `副产工序(${bpProc.processName})保存`, { toast: saved.toast.slice(0, 40) });
  await page.waitForTimeout(1500);

  // ---- 验证: 副产回收 + labor 进入成本 ----
  const bpBatch = (await ycByOrder(planId, bpProc.processOrder))[0]?.batchNumber;
  ok(!!bpBatch, '副产道产出批', { bpBatch });
  const oracleCredit = BP_QTY * BP_PRICE; // 2.00

  // 拉成本拆分端点 (完整成本模型: 分桶 + 副产冲减 + 净成本)
  let cb = null, endpointUsed = null;
  for (const ep of [`/${FACTORY}/production/batches/${encodeURIComponent(bpBatch)}/cost-breakdown`, `/${FACTORY}/processing/batches/${encodeURIComponent(bpBatch)}/cost-breakdown`]) {
    const r = await api('GET', ep).catch(() => null);
    if (r && typeof r === 'object' && (r.byproductCredit != null || r.totalCost != null)) { cb = r; endpointUsed = ep; break; }
  }
  ok(!!cb, '取到成本拆分端点', { endpointUsed });
  if (cb) {
    const credit = num(cb.byproductCredit), labor = num(cb.laborCost), total = num(cb.totalCost), net = num(cb.netTotalCost);
    console.log('cost-breakdown:', JSON.stringify({ total, raw: cb.rawMaterialCost, labor, seasoning: cb.seasoningCost, packaging: cb.packagingCost, credit, net }));
    // ① 副产回收 = 数量×单价 (肥油/料头变现冲减, 之前为空)
    approx(credit, oracleCredit, 0.02, `副产回收 == 数量×单价 (${BP_QTY}×${BP_PRICE}=${oracleCredit})`);
    // ② 人工成本>0 (填了工时段, 之前为空)
    ok(labor != null && labor > 0, '人工成本>0 (填了工时段, 之前为空)', { laborCost: labor });
    // ③ 总成本 = 料+工+调+包 各分桶之和 (完整成本等式)
    const compSum = (num(cb.rawMaterialCost) ?? 0) + (num(cb.laborCost) ?? 0) + (num(cb.seasoningCost) ?? 0) + (num(cb.packagingCost) ?? 0);
    approx(compSum, total, 0.02, '总成本 = 料+工+调+包 分桶之和');
    // ④ 净成本 = 总成本 - 副产回收 (副产真冲减)
    if (net != null && credit != null) approx(net, total - credit, 0.02, '净成本 = 总成本 - 副产回收');
    // ⑤ 副产明细 value = 数量×单价
    const bp0 = (cb.byproducts || [])[0];
    if (bp0) approx(num(bp0.value), num(bp0.quantity) * num(bp0.unitPrice), 0.02, '副产明细 value = 数量×单价');
  }
  const foundCredit = cb ? num(cb.byproductCredit) : null, foundLabor = cb ? num(cb.laborCost) : null;

  const fails = asserts.filter((a) => !a.pass);
  const status = fails.length === 0 ? 'PASS' : (fails.length <= 2 ? 'PARTIAL' : 'FAIL');
  await writeFile(path.join(OUT, 'byproduct-result.json'), JSON.stringify({ scenario: 'byproduct-labor-deep', depth: 'deep', status, planNumber, bpProc: bpProc.processName, oracleCredit, foundCredit, foundLabor, endpointUsed, asserts, consoleErrors: ctx.consoleErrors }, null, 2), 'utf8').catch(() => null);
  console.log(`\n${JSON.stringify({ status, total: asserts.length, pass: asserts.filter((a) => a.pass).length, failures: fails.map((f) => f.label) }, null, 2)}`);
} catch (e) { console.error('ERROR:', e.message, e.stack); ok(false, 'byproduct 异常', { e: e.message }); await shot('error'); }
finally { await ctx.browser.close().catch(() => null); }
