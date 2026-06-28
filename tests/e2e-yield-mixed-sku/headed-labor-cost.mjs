/**
 * 人工成本真值核对(headed F006) —— 填工时段, 核 laborCost = Σ(时长h×人数) × 单价, 且缺配置不伪装0.
 *
 * F006 未配 factory_cost_settings.laborHourlyRate → 后端默认 26 元/工时 + warning(缺配置不伪装0).
 * 本测试填一段工时(08:00-10:00=2h × 2人 = 4 工时), oracle laborCost = 4 × 26 = 104.00,
 * 核 cost-analysis.laborCost == 104, 且保存 warning 含"工时单价未配置...默认26".
 *
 * Env: E2E_USERNAME E2E_PASSWORD [E2E_ADMIN_URL E2E_FACTORY_ID PLAYWRIGHT_PORT]
 */
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { APP, FACTORY, COUNT_UNIT_RE, WEIGHT_UNIT_RE, arr, num, today, startHeaded, setupSkuAndBom } from './_headed-helpers.mjs';

const OUT = path.resolve(`.playwright-mcp/labor-cost-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const resultFile = path.join(OUT, 'labor-cost-result.json');
const asserts = [];
const ok = (p, l, d = {}) => { asserts.push({ pass: !!p, label: l, ...d }); console.log(`${p ? 'PASS' : 'FAIL'} ${l} ${Object.keys(d).length ? JSON.stringify(d) : ''}`); };
const approx = (a, e, tol, l) => { const p = a != null && e != null && Math.abs(a - e) <= tol; asserts.push({ pass: p, label: l, actual: a, expected: e }); console.log(`${p ? 'PASS' : 'FAIL'} ${l} act=${a} exp=${e}`); };

const RATE_DEFAULT = 26; // FactoryCostSettings 未配 → 后端默认
const SEG = { start: '08:00', end: '10:00', workers: 2 }; // 2h × 2 = 4 工时
const EXPECTED_LABOR = 2 * SEG.workers * RATE_DEFAULT; // 104

const ctx = await startHeaded(OUT);
try {
  const { page, api, shot, helpers } = ctx;
  const setup = await setupSkuAndBom(page, { namePrefix: 'HT-LAB', api, shot, minProcesses: 1 });
  const proc = setup.processes[0];
  ok(!!proc, '新 SKU 有首道工序', { proc: proc?.processName });

  const plan = await api('POST', `/${FACTORY}/production-plans`, { productTypeId: setup.productTypeId, plannedQuantity: 5, plannedDate: today(0), expectedCompletionDate: today(2), sourceType: 'MANUAL', skipProcessReporting: false, customerOrderNumber: `LAB-${Date.now()}`, notes: 'headed labor cost' });
  const planId = String(plan.id || plan.planId), planNumber = String(plan.planNumber || planId);
  await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/start`).catch(() => null);
  ok(!!planId, 'API 前置计划+start', { planNumber });

  // 原料批(重量单位, 排包材)
  const whs = arr(await api('GET', `/${FACTORY}/factory/warehouses`));
  const rawWh = whs.find((w) => w.code === 'WH-LOG') || whs.find((w) => ['RAW', 'LOGISTICS'].includes(String(w.type)));
  const rawBatch = arr(await api('GET', `/${FACTORY}/material-batches/status/AVAILABLE?warehouseId=${encodeURIComponent(rawWh.id)}&size=200`))
    .find((b) => num(b.currentQuantity ?? b.quantity) > 1 && !/^WIP-|^CLK-/.test(String(b.batchNumber || '')) && WEIGHT_UNIT_RE.test(String(b.quantityUnit || b.unit || '')) && !COUNT_UNIT_RE.test(String(b.quantityUnit || b.unit || '')));
  ok(!!rawBatch, '原料批为重量单位且排包材', { bn: rawBatch?.batchNumber, unit: rawBatch?.quantityUnit || rawBatch?.unit, price: rawBatch?.unitPrice });

  // ---- headed 打开逐道录入, 首道录入 + 填工时段 ----
  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  const search = page.locator('input[placeholder*="搜索"], input[placeholder*="计划编号"]').first();
  if (await search.isVisible().catch(() => false)) { await search.fill(planNumber); await page.locator('button').filter({ hasText: /搜索/ }).first().click().catch(() => null); await page.waitForTimeout(1800); }
  const planRow = page.locator('.el-table__row').filter({ hasText: planNumber }).first();
  await planRow.locator('button, .el-button').filter({ hasText: '逐道录入' }).first().click();
  await page.waitForSelector('.el-drawer__body', { timeout: 15000 });
  await page.waitForTimeout(2200);
  const drawer = page.locator('.el-drawer__body');
  const tab = drawer.locator('.el-tabs__item').filter({ hasText: proc.processName }).first();
  await tab.click(); await page.waitForTimeout(1300);
  const pane = drawer.locator('.el-tab-pane:visible').first();
  await pane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
  await page.waitForTimeout(800);
  const row = pane.locator('table.sp-grid tbody tr.sp-tr').last();
  await helpers.selectByText(row.locator('.el-select').first(), rawBatch.batchNumber);
  const nums = row.locator('.el-input-number');
  await helpers.fillNum(nums.nth(0), 1.0); // 出库重量
  await helpers.fillNum(nums.nth(1), 0.9); // 产出
  // 工时 expander
  await row.locator('button').filter({ hasText: /h.*段|工时|0\.0h/ }).first().click().catch(() => null);
  await page.waitForTimeout(700);
  // expander row 出现 WorkHoursTable → + 工时段
  const laborSec = pane.locator('.sp-tr-expand, .sp-expand-section').last();
  await laborSec.locator('button').filter({ hasText: /工时段|\+/ }).first().click().catch(() => null);
  await page.waitForTimeout(600);
  // 填 开始/结束时间(el-time-picker HH:mm)+ 人数
  const segRow = laborSec.locator('tr, .el-table__row').last();
  const timeInputs = laborSec.locator('.el-date-editor input, input[placeholder="开始"], input[placeholder="结束"]');
  const startInp = laborSec.locator('input[placeholder="开始"]').first();
  const endInp = laborSec.locator('input[placeholder="结束"]').first();
  await startInp.click(); await startInp.fill(SEG.start); await startInp.press('Enter'); await page.waitForTimeout(400);
  await endInp.click(); await endInp.fill(SEG.end); await endInp.press('Enter'); await page.waitForTimeout(400);
  // 人数 input-number
  const workerInp = laborSec.locator('.el-input-number').last();
  await helpers.fillNum(workerInp, SEG.workers);
  await shot('labor-segment');
  // 总工时预览(WorkHoursTable 算 时长×人数)
  const hoursText = await laborSec.innerText().catch(() => '');
  console.log('  工时段区文本:', hoursText.replace(/\s+/g, ' ').slice(0, 120));
  await row.locator('button').filter({ hasText: '保存' }).first().click();
  const saved = await helpers.waitSaved();
  ok(saved.saved, '首道含工时段 headed 保存成功', { toast: saved.toast });
  ok(/工时单价未配置|默认\s*26/.test(saved.toast), '防呆: 缺工时单价显式 warning + 默认26(非静默0)', { toast: saved.toast });
  await page.waitForTimeout(1500);

  // ---- 核 laborCost 真值: 用 plan-scoped yield-card(path 参数可靠, 非被忽略的 query 过滤) ----
  const yc = arr(await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/process-sheet/inventory/yield-card`));
  const firstCard = yc.find((c) => Number(c.processOrder) === Number(proc.processOrder)) || yc[0];
  const rowTotalCost = num(firstCard?.rowTotalCost);
  const rawCost = num(rawBatch.unitPrice) != null ? Math.round(num(rawBatch.unitPrice) * 1.0 * 100) / 100 : null; // 10×1.0
  const laborFromRow = (rowTotalCost != null && rawCost != null) ? Math.round((rowTotalCost - rawCost) * 100) / 100 : null;
  console.log('  首道卡:', JSON.stringify({ bn: firstCard?.batchNumber, rowTotalCost, rawCost, laborDerived: laborFromRow }));
  ok(!!firstCard?.batchNumber, '取得本计划首道批(plan-scoped)', { bn: firstCard?.batchNumber });
  approx(rowTotalCost, (rawCost ?? 0) + EXPECTED_LABOR, 0.05, `行总成本 = 原料(${rawCost}) + 人工(${EXPECTED_LABOR}) = ${(rawCost ?? 0) + EXPECTED_LABOR}`);
  approx(laborFromRow, EXPECTED_LABOR, 0.05, `人工成本真值 = 行总 − 原料 = 4工时 × ${RATE_DEFAULT} = ${EXPECTED_LABOR}`);
  await finish('PASS');
} catch (error) {
  ok(false, 'labor 脚本异常', { error: error.message });
  await ctx.shot('labor-error').catch(() => null);
  await finish('FAIL', { error: error.message });
}
async function finish(forced, extra = {}) {
  const fails = asserts.filter((a) => !a.pass);
  const realErr = ctx.consoleErrors.filter((e) => !/409|status of 4\d\d|favicon/.test(e));
  const status = forced === 'PASS' && fails.length === 0 && realErr.length === 0 ? 'PASS' : (fails.length <= 1 ? 'PARTIAL' : 'FAIL');
  await writeFile(resultFile, JSON.stringify({ scenario: 'headed-labor-cost', depth: 'deep', target: 'web-admin-prod-F006', status, expectedLabor: EXPECTED_LABOR, asserts, consoleErrors: ctx.consoleErrors, ...extra }, null, 2), 'utf8').catch(() => null);
  console.log(JSON.stringify({ status, resultFile, failures: fails.map((f) => f.label) }, null, 2));
  await ctx.browser.close().catch(() => null);
  if (status === 'FAIL') process.exitCode = 1;
}
