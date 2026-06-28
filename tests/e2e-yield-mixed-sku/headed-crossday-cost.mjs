import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import {
  APP, FACTORY, COUNT_UNIT_RE, WEIGHT_UNIT_RE, arr, num, today,
  startHeaded, setupSkuAndBom,
} from './_headed-helpers.mjs';

const OUT = path.resolve(`.playwright-mcp/crossday-cost-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const resultFile = path.join(OUT, 'headed-crossday-cost-result.json');
const assertions = [];
const ok = (pass, label, data = {}) => {
  assertions.push({ pass: !!pass, label, ...data });
  console.log(`${pass ? 'PASS' : 'FAIL'} ${label} ${Object.keys(data).length ? JSON.stringify(data) : ''}`);
};

const ctx = await startHeaded(OUT);
try {
  const { page, api, shot, helpers } = ctx;
  const setup = await setupSkuAndBom(page, { namePrefix: 'HT-CD', api, shot, minProcesses: 3 });
  const processes = setup.processes.slice(0, 3);
  ok(processes.length >= 3, '新 SKU 有至少 3 道工序', { processes: processes.map((p) => p.processName) });

  const plan = await api('POST', `/${FACTORY}/production-plans`, {
    productTypeId: setup.productTypeId,
    plannedQuantity: 5,
    plannedDate: today(0),
    expectedCompletionDate: today(2),
    sourceType: 'MANUAL',
    skipProcessReporting: false,
    customerOrderNumber: `CD-${Date.now()}`,
    notes: 'headed crossday cost attribution',
  });
  const planId = String(plan.id || plan.planId);
  const planNumber = String(plan.planNumber || planId);
  await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/start`).catch(() => null);
  ok(!!planId, 'API 前置创建并启动计划', { planNumber });

  await openProcessDrawer(page, planNumber);
  await shot('crossday-drawer-open');
  const rawBatch = await findRawBatch(api, setup.productTypeId, setup.rawMaterial);
  ok(!!rawBatch?.batchNumber, '发现重量单位原料批次，已排除计数单位包材', {
    batchNumber: rawBatch?.batchNumber,
    unit: rawBatch?.quantityUnit || rawBatch?.unit,
  });

  const dates = [today(-2), today(-1), today(0)];
  await saveFirstProcessRow(page, helpers, processes[0], rawBatch, { input: 1.0, output: 0.9, date: dates[0] });
  await shot('crossday-row-1');
  let cards = arr(await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/process-sheet/inventory/yield-card`));
  const firstBatch = cards.find((c) => Number(c.processOrder) === Number(processes[0].processOrder))?.batchNumber;
  ok(!!firstBatch, '首道保存后生成 WIP 批', { firstBatch });

  await saveSingleUpstreamRow(page, helpers, processes[1], firstBatch, { input: 0.5, output: 0.45, date: dates[1] });
  await shot('crossday-row-2');
  cards = arr(await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/process-sheet/inventory/yield-card`));
  const secondBatch = cards.find((c) => Number(c.processOrder) === Number(processes[1].processOrder))?.batchNumber;
  ok(!!secondBatch, '二道保存后生成 WIP 批', { secondBatch });

  await saveSingleUpstreamRow(page, helpers, processes[2], secondBatch, { input: 0.25, output: 0.22, date: dates[2] });
  await shot('crossday-row-3');

  const rowChecks = [];
  for (let i = 0; i < processes.length; i += 1) {
    const rows = await getRowsForProcess(api, planId, processes[i]);
    const processDate = rows[0]?.payload?.processDate || rows[0]?.payload?.prodDate?.[0] || rows[0]?.payload?.date?.[0];
    rowChecks.push({ process: processes[i].processName, expected: dates[i], actual: processDate });
    ok(processDate === dates[i], `processDate 回读等于录入历史日: ${processes[i].processName}`, {
      expected: dates[i],
      actual: processDate,
    });
  }

  cards = arr(await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/process-sheet/inventory/yield-card`));
  const renderedText = await page.locator('.el-drawer__body').innerText().catch(() => '');
  ok(cards.length >= 3, 'API yield-card 有三道产出卡', { count: cards.length });
  ok(processes.slice(0, 3).every((p) => renderedText.includes(p.processName)), 'DOM 渲染包含三道工序 tab/内容', {
    processes: processes.map((p) => p.processName),
  });

  await finish('PASS', { setup, planId, planNumber, rowChecks, yieldCard: cards });
} catch (error) {
  ok(false, 'crossday 脚本异常', { error: error.message });
  await ctx.shot('crossday-error').catch(() => null);
  await finish('FAIL', { error: error.message, stack: error.stack });
}

async function finish(forcedStatus, extra = {}) {
  const failures = assertions.filter((a) => !a.pass);
  const status = forcedStatus === 'PASS' && failures.length === 0 ? 'PASS' : 'FAIL';
  await writeFile(resultFile, JSON.stringify({
    scenario: 'headed-crossday-cost',
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

async function openProcessDrawer(page, planNumber) {
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

async function gotoTab(page, processName) {
  const drawer = page.locator('.el-drawer__body');
  const tab = drawer.locator('.el-tabs__item').filter({ hasText: processName }).first();
  if (!(await tab.isVisible().catch(() => false))) throw new Error(`process tab not visible: ${processName}`);
  await tab.click();
  await page.waitForTimeout(1200);
}

function activePane(page) {
  return page.locator('.el-drawer__body .el-tab-pane:visible').first();
}

async function saveFirstProcessRow(page, helpers, process, rawBatch, values) {
  await gotoTab(page, process.processName);
  const pane = activePane(page);
  await pane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
  await page.waitForTimeout(800);
  const row = pane.locator('table.sp-grid tbody tr.sp-tr').last();
  await setDateRange(page, row, values.date);
  await helpers.selectByText(row.locator('.el-select').first(), rawBatch.batchNumber);
  const nums = row.locator('.el-input-number');
  await helpers.fillNum(nums.nth(0), values.input);
  await helpers.fillNum(nums.nth(1), values.output);
  await row.locator('button').filter({ hasText: '保存' }).first().click();
  const saved = await helpers.waitSaved();
  if (!saved.saved) throw new Error(`first row save failed: ${saved.toast}`);
  await page.waitForTimeout(1400);
}

async function saveSingleUpstreamRow(page, helpers, process, upstreamBatchNumber, values) {
  await gotoTab(page, process.processName);
  const pane = activePane(page);
  await pane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
  await page.waitForTimeout(800);
  const row = pane.locator('table.sp-grid tbody tr.sp-tr').last();
  await setDateRange(page, row, values.date);
  await helpers.selectByText(row.locator('.el-select').first(), upstreamBatchNumber);
  const nums = row.locator('.el-input-number');
  await helpers.fillNum(nums.nth(0), values.input);
  await helpers.fillNum(nums.nth(1), values.output);
  await row.locator('button').filter({ hasText: '保存' }).first().click();
  const saved = await helpers.waitSaved();
  if (!saved.saved) throw new Error(`single upstream row save failed: ${saved.toast}`);
  await page.waitForTimeout(1400);
}

async function setDateRange(page, row, date) {
  const picker = row.locator('.el-date-editor--daterange').first();
  if (!(await picker.isVisible().catch(() => false))) return;
  await picker.click();
  await page.waitForSelector('.el-picker-panel:visible', { timeout: 5000 });
  const day = String(Number(date.slice(-2)));
  const cells = page.locator('.el-picker-panel:visible .el-date-table td:not(.disabled)').filter({ hasText: new RegExp(`^\\s*${day}\\s*$`) });
  await cells.first().click();
  await page.waitForTimeout(200);
  await cells.first().click().catch(async () => {
    await page.locator('.el-picker-panel:visible .el-date-table td:not(.disabled)').filter({ hasText: new RegExp(`^\\s*${day}\\s*$`) }).first().click();
  });
  await page.waitForTimeout(300);
  await row.locator('td').first().click().catch(() => null);
}

async function findRawBatch(api, productTypeId, rawMaterial = null) {
  if (rawMaterial?._preferredBatch?.batchNumber) return rawMaterial._preferredBatch;
  const warehouses = arr(await api('GET', `/${FACTORY}/factory/warehouses`));
  const rawWh = warehouses.find((w) => w.code === 'WH-LOG') || warehouses.find((w) => ['RAW', 'LOGISTICS'].includes(String(w.type)));
  if (!rawWh?.id) throw new Error('no raw/logistics warehouse');
  const batches = arr(await api('GET', `/${FACTORY}/material-batches/status/AVAILABLE?warehouseId=${encodeURIComponent(rawWh.id)}&size=200`));
  return batches.find((b) => {
    const unit = String(b.quantityUnit || b.unit || '');
    const materialMatches = !rawMaterial?.name
      || String(b.materialName || b.materialTypeName || '').includes(rawMaterial.name)
      || String(rawMaterial.name).includes(String(b.materialName || b.materialTypeName || ''));
    return num(b.currentQuantity ?? b.quantity) > 1
      && !/^WIP-|^CLK-/.test(String(b.batchNumber || ''))
      && WEIGHT_UNIT_RE.test(unit)
      && !COUNT_UNIT_RE.test(unit)
      && materialMatches;
  });
}

async function getRowsForProcess(api, planId, process) {
  const codes = ['xiuyou', 'gunrou', 'chaoshui', 'qushetou', 'shuzhi', 'qidiao'];
  for (const code of codes) {
    const rows = arr(await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/process-sheet/rows?process=${code}&processOrder=${process.processOrder}`).catch(() => []));
    if (rows.length) return rows;
  }
  return [];
}
