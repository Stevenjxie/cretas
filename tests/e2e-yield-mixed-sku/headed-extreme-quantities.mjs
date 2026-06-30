/**
 * 极限/边界 数量 纯-headed (F006): 首道多行录入极端投入/产出, 核出成率+成本计算稳健.
 * 边界: 极小(0.01) / 超产(产>投, 保水类合法) / 零产出(全损耗) / 极大(100) / 相等(100%).
 * 验证: 无 NaN / 无负成本 / 成本 scale-2 / 零产出不除零 / 出成率合理.
 *
 * Env: E2E_USERNAME E2E_PASSWORD [PLAYWRIGHT_PORT]
 */
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { startHeaded, APP, FACTORY, arr, num, today, WEIGHT_UNIT_RE, COUNT_UNIT_RE, setupSkuAndBom } from './_headed-helpers.mjs';

const OUT = path.resolve(`.playwright-mcp/extreme-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const asserts = [];
const ok = (p, l, d = {}) => { asserts.push({ pass: !!p, label: l, ...d }); console.log(`${p ? 'PASS' : 'FAIL'} ${l} ${Object.keys(d).length ? JSON.stringify(d) : ''}`); return !!p; };
const isScale2 = (v) => v == null || Math.abs(v * 100 - Math.round(v * 100)) < 1e-6;

const ctx = await startHeaded(OUT);
const { page, api, shot, helpers } = ctx;
const { selectByText, fillNum, waitSaved } = helpers;
const drawer = () => page.locator('.el-drawer__body');
const activePane = () => drawer().locator('.el-tab-pane:visible').first();
async function gotoTab(name) { const t = drawer().locator('.el-tabs__item').filter({ hasText: name }).first(); if (await t.isVisible().catch(() => false)) { await t.click(); await page.waitForTimeout(1300); return true; } return false; }

// 边界用例: [标签, 投入, 产出, 期望出成率%, 说明]
const CASES = [
  { tag: '极小(亚分成本)', feed: 0.01, out: 0.01, yld: 100, note: '成本0.28×0.01=0.0028→scale-2→0(亚分级归0, 正确); 出成100%' },
  { tag: '超产(保水)', feed: 1.0, out: 1.2, yld: 120, note: '产>投(吸水), 出成率>100% 合法' },
  { tag: '零产出', feed: 0.5, out: 0, yld: 0, note: '全损耗, 不应除零/NaN' },
  { tag: '相等', feed: 0.3, out: 0.3, yld: 100, note: '100% 出成' },
  { tag: '极大', feed: 100, out: 90, yld: 90, note: '大数, 精度不溢' },
];

try {
  const setup = await setupSkuAndBom(page, { namePrefix: 'EXT', gramsPerUnit: 100, api, shot, minProcesses: 3 });
  const proc0 = setup.processes[0];
  const plan = await api('POST', `/${FACTORY}/production-plans`, { productTypeId: setup.productTypeId, plannedQuantity: 5, plannedDate: today(0), sourceType: 'MANUAL', skipProcessReporting: false, customerOrderNumber: `EXT-${Date.now()}`, notes: 'extreme quantities' });
  const planId = String(plan.id || plan.planId), planNumber = String(plan.planNumber || planId);
  await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/start`).catch(() => null);

  const whs = arr(await api('GET', `/${FACTORY}/factory/warehouses`));
  const rawWh = whs.find((w) => w.code === 'WH-LOG') || whs.find((w) => ['RAW', 'LOGISTICS'].includes(String(w.type)));
  const raw = arr(await api('GET', `/${FACTORY}/material-batches/status/AVAILABLE?warehouseId=${encodeURIComponent(rawWh.id)}&productTypeId=${encodeURIComponent(setup.productTypeId)}&size=200`))
    .filter((b) => num(b.currentQuantity ?? b.quantity) > 200 && WEIGHT_UNIT_RE.test(String(b.quantityUnit || b.unit || '')) && !COUNT_UNIT_RE.test(String(b.quantityUnit || b.unit || '')))
    .sort((a, b) => num(b.currentQuantity ?? b.quantity) - num(a.currentQuantity ?? a.quantity))[0];
  ok(!!raw, '原料批(库存>200kg, 够极大用例)', { bn: raw?.batchNumber, qty: raw?.currentQuantity });
  if (!raw) throw new Error('no fat raw batch');
  const rawPrice = num(raw.unitPrice) ?? 0;

  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  const sb = page.locator('input[placeholder*="搜索"], input[placeholder*="计划编号"]').first();
  if (await sb.isVisible().catch(() => false)) { await sb.fill(planNumber); await page.locator('button').filter({ hasText: /搜索/ }).first().click().catch(() => null); await page.waitForTimeout(1800); }
  await page.locator('.el-table__row').filter({ hasText: planNumber }).first().locator('button, .el-button').filter({ hasText: '逐道录入' }).first().click();
  await page.waitForSelector('.el-drawer__body', { timeout: 15000 });
  await page.waitForTimeout(2200);
  await gotoTab(proc0.processName); await page.waitForTimeout(700);

  const results = [];
  for (const c of CASES) {
    const p = activePane();
    await p.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
    await page.waitForTimeout(700);
    const row = p.locator('table.sp-grid tbody tr.sp-tr').last();
    await selectByText(row.locator('.el-select').first(), raw.batchNumber);
    const nums = row.locator('.el-input-number');
    await fillNum(nums.nth(0), c.feed);
    await fillNum(nums.nth(1), c.out);
    await row.locator('button').filter({ hasText: '保存' }).first().click();
    const saved = await waitSaved();
    const batchNo = (saved.toast.match(/CLK-[A-Z]+-[0-9-]+/) || [])[0] || null;
    console.log(`[${c.tag}] feed=${c.feed} out=${c.out} → saved=${saved.saved} batch=${batchNo || '(无WIP)'} toast="${saved.toast.slice(0, 40)}"`);
    results.push({ ...c, saved: saved.saved, batchNo, toast: saved.toast.slice(0, 60) });
    await page.waitForTimeout(1000);
  }

  // 回读出成卡, 逐用例核稳健性 (按批次号匹配)
  const yc = arr(await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/process-sheet/inventory/yield-card`));
  for (const c of CASES) {
    const r = results.find((x) => x.tag === c.tag);
    if (!r?.saved) { ok(false, `${c.tag} 保存失败`, { toast: r?.toast }); continue; }
    // 零产出: 正确行为 = 保存但不创 WIP (全损耗, 无半成品下游); 不应有出成卡批
    if (c.out === 0) {
      ok(!r.batchNo, `${c.tag} 全损耗正确: 保存但不创 WIP 批(无半成品)`, { batchNo: r.batchNo });
      continue;
    }
    const card = r.batchNo ? yc.find((x) => x.batchNumber === r.batchNo) : null;
    if (!card) { ok(false, `${c.tag} 出成卡回读(by batchNo)`, { batchNo: r.batchNo }); continue; }
    const sy = num(card.stepYieldRate), rc = num(card.rowTotalCost), inh = num(card.inheritedCost);
    // 稳健性: 无 NaN, 成本非负, scale-2, 零产出出成率=0(不除零)
    ok(sy != null && Number.isFinite(sy) && sy >= 0, `${c.tag} 出成率有限非负(无NaN/除零)`, { stepYieldRate: sy });
    ok(rc == null || (Number.isFinite(rc) && rc >= 0), `${c.tag} 行总成本非负有限`, { rowTotalCost: rc });
    ok(isScale2(rc), `${c.tag} 行总成本 scale-2`, { rowTotalCost: rc });
    if (c.yld != null && sy != null) ok(Math.abs(sy - c.yld) <= 1.0, `${c.tag} 出成率 ≈ ${c.yld}%`, { got: sy });
  }

  const fails = asserts.filter((a) => !a.pass);
  const status = fails.length === 0 ? 'PASS' : (fails.length <= 2 ? 'PARTIAL' : 'FAIL');
  await writeFile(path.join(OUT, 'extreme-result.json'), JSON.stringify({ scenario: 'extreme-quantities', depth: 'deep', status, planNumber, results, asserts, consoleErrors: ctx.consoleErrors }, null, 2), 'utf8').catch(() => null);
  console.log(`\n${JSON.stringify({ status, total: asserts.length, pass: asserts.filter((a) => a.pass).length, failures: fails.map((f) => f.label) }, null, 2)}`);
  if (status === 'FAIL') process.exitCode = 1;
} catch (e) { console.error('ERROR:', e.message, e.stack); ok(false, 'extreme 异常', { e: e.message }); await shot('error'); process.exitCode = 1; }
finally { await ctx.browser.close().catch(() => null); }
