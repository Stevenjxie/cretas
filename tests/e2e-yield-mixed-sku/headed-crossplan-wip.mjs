import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import {
  APP, FACTORY, COUNT_UNIT_RE, WEIGHT_UNIT_RE, arr, num, today,
  startHeaded, setupSkuAndBom,
} from './_headed-helpers.mjs';

const OUT = path.resolve(`.playwright-mcp/crossplan-wip-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const resultFile = path.join(OUT, 'headed-crossplan-wip-result.json');
const assertions = [];
const ok = (pass, label, data = {}) => {
  assertions.push({ pass: !!pass, label, ...data });
  console.log(`${pass ? 'PASS' : 'FAIL'} ${label} ${Object.keys(data).length ? JSON.stringify(data) : ''}`);
};

const ctx = await startHeaded(OUT);
try {
  const { page, api, shot, helpers } = ctx;
  const setup = await setupSkuAndBom(page, { namePrefix: 'HT-XP', api, shot, minProcesses: 2 });
  const [firstProc, secondProc] = setup.processes;
  ok(!!firstProc && !!secondProc, '新 SKU 有至少 2 道工序', {
    processes: setup.processes.slice(0, 2).map((p) => p.processName),
  });

  const planA = await createStartedPlan(api, setup.productTypeId, 'XP-A');
  await openProcessDrawer(page, planA.planNumber);
  const rawA = await findRawBatch(api, setup.rawMaterial);
  await saveFirstRow(page, helpers, firstProc, rawA, 1.0, 0.9);
  await shot('plan-a-first-row');
  const wipA = await firstWip(api, planA.planId, firstProc);
  ok(!!wipA?.batchNumber, '计划 A 首道产出 WIP_A', { wipA: wipA?.batchNumber });
  await closeDrawer(page);

  const planB = await createStartedPlan(api, setup.productTypeId, 'XP-B');
  await openProcessDrawer(page, planB.planNumber);
  const rawB = await findRawBatch(api, setup.rawMaterial);
  await saveFirstRow(page, helpers, firstProc, rawB, 0.8, 0.72);
  await shot('plan-b-first-row');
  const wipB = await firstWip(api, planB.planId, firstProc);
  ok(!!wipB?.batchNumber, '计划 B 首道产出自己的 WIP_B', { wipB: wipB?.batchNumber });

  await gotoTab(page, secondProc.processName);
  const pane = activePane(page);
  await pane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
  await page.waitForTimeout(800);
  const row = pane.locator('table.sp-grid tbody tr.sp-tr').last();
  await row.locator('.el-select').first().click();
  await page.waitForTimeout(900);
  const optionTexts = await page.locator('.el-select-dropdown__item:visible').allInnerTexts().catch(() => []);
  const joined = optionTexts.join('\n');
  ok(joined.includes(wipB.batchNumber), '计划 B 上游下拉包含本计划 WIP_B', {
    wipB: wipB.batchNumber,
    options: optionTexts.slice(0, 6),
  });
  ok(!joined.includes(wipA.batchNumber), '计划 B 上游下拉不包含计划 A 的 WIP_A(plan-scope 隔离)', {
    wipA: wipA.batchNumber,
    options: optionTexts.slice(0, 6),
  });
  await shot('plan-b-upstream-dropdown');

  const invB = arr(await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planB.planId)}/process-sheet/inventory?process=chaoshui&processOrder=${secondProc.processOrder}`).catch(() => []));
  ok(!invB.some((x) => x.batchNumber === wipA.batchNumber), 'API inventory 同样不暴露 WIP_A 给计划 B', {
    invCount: invB.length,
  });

  await finish('PASS', { setup, planA, planB, wipA, wipB, optionTexts });
} catch (error) {
  ok(false, 'crossplan 脚本异常', { error: error.message });
  await ctx.shot('crossplan-error').catch(() => null);
  await finish('FAIL', { error: error.message, stack: error.stack });
}

async function finish(forcedStatus, extra = {}) {
  const failures = assertions.filter((a) => !a.pass);
  const status = forcedStatus === 'PASS' && failures.length === 0 ? 'PASS' : 'FAIL';
  await writeFile(resultFile, JSON.stringify({
    scenario: 'headed-crossplan-wip',
    depth: 'deep',
    target: 'web-admin-prod-F006',
    status,
    byDesign: 'ProcessSheet inventory is plan-scoped; cross-plan WIP consumption is intentionally not available.',
    assertions,
    consoleErrors: ctx.consoleErrors,
    ...extra,
  }, null, 2), 'utf8').catch(() => null);
  console.log(JSON.stringify({ status, resultFile, failures: failures.map((f) => f.label) }, null, 2));
  await ctx.browser.close().catch(() => null);
  if (status !== 'PASS') process.exitCode = 1;
}

async function createStartedPlan(api, productTypeId, prefix) {
  const plan = await api('POST', `/${FACTORY}/production-plans`, {
    productTypeId,
    plannedQuantity: 5,
    plannedDate: today(0),
    expectedCompletionDate: today(2),
    sourceType: 'MANUAL',
    skipProcessReporting: false,
    customerOrderNumber: `${prefix}-${Date.now()}`,
    notes: `headed crossplan isolation ${prefix}`,
  });
  const planId = String(plan.id || plan.planId);
  const planNumber = String(plan.planNumber || planId);
  await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/start`).catch(() => null);
  return { planId, planNumber };
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
  const planRow = page.locator('.el-table__row').filter({ hasText: planNumber }).first();
  if (!(await planRow.isVisible().catch(() => false))) throw new Error(`plan row not visible: ${planNumber}`);
  await planRow.locator('button, .el-button').filter({ hasText: '逐道录入' }).first().click();
  await page.waitForSelector('.el-drawer__body', { timeout: 15000 });
  await page.waitForTimeout(2200);
}

async function closeDrawer(page) {
  await page.locator('.el-drawer__close-btn, .el-drawer__headerbtn').first().click().catch(() => null);
  await page.waitForTimeout(1000);
}

async function gotoTab(page, processName) {
  const tab = page.locator('.el-drawer__body .el-tabs__item').filter({ hasText: processName }).first();
  if (!(await tab.isVisible().catch(() => false))) throw new Error(`process tab not visible: ${processName}`);
  await tab.click();
  await page.waitForTimeout(1200);
}

function activePane(page) {
  return page.locator('.el-drawer__body .el-tab-pane:visible').first();
}

async function saveFirstRow(page, helpers, process, rawBatch, input, output) {
  await gotoTab(page, process.processName);
  const pane = activePane(page);
  await pane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
  await page.waitForTimeout(800);
  const row = pane.locator('table.sp-grid tbody tr.sp-tr').last();
  await helpers.selectByText(row.locator('.el-select').first(), rawBatch.batchNumber);
  const nums = row.locator('.el-input-number');
  await helpers.fillNum(nums.nth(0), input);
  await helpers.fillNum(nums.nth(1), output);
  await row.locator('button').filter({ hasText: '保存' }).first().click();
  const saved = await helpers.waitSaved();
  if (!saved.saved) throw new Error(`first row save failed: ${saved.toast}`);
  await page.waitForTimeout(1400);
}

async function firstWip(api, planId, process) {
  const cards = arr(await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/process-sheet/inventory/yield-card`));
  return cards.find((c) => Number(c.processOrder) === Number(process.processOrder));
}

async function findRawBatch(api, rawMaterial) {
  if (rawMaterial?._preferredBatch?.batchNumber) return rawMaterial._preferredBatch;
  const warehouses = arr(await api('GET', `/${FACTORY}/factory/warehouses`));
  const rawWh = warehouses.find((w) => w.code === 'WH-LOG') || warehouses.find((w) => ['RAW', 'LOGISTICS'].includes(String(w.type)));
  const batches = arr(await api('GET', `/${FACTORY}/material-batches/status/AVAILABLE?warehouseId=${encodeURIComponent(rawWh.id)}&size=200`));
  return batches.find((b) => {
    const unit = String(b.quantityUnit || b.unit || '');
    return num(b.currentQuantity ?? b.quantity) > 1
      && !/^WIP-|^CLK-/.test(String(b.batchNumber || ''))
      && WEIGHT_UNIT_RE.test(unit)
      && !COUNT_UNIT_RE.test(unit)
      && String(b.materialTypeId || '') === String(rawMaterial?.id || '');
  });
}
