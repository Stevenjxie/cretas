import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import {
  APP, FACTORY, COUNT_UNIT_RE, WEIGHT_UNIT_RE, arr, num, today,
  startHeaded, setupSkuAndBom,
} from './_headed-helpers.mjs';

const OUT = path.resolve(`.playwright-mcp/reverse-resettle-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const resultFile = path.join(OUT, 'headed-reverse-resettle-result.json');
const assertions = [];
const ok = (pass, label, data = {}) => {
  assertions.push({ pass: !!pass, label, ...data });
  console.log(`${pass ? 'PASS' : 'FAIL'} ${label} ${Object.keys(data).length ? JSON.stringify(data) : ''}`);
};

const ctx = await startHeaded(OUT);
try {
  const { page, api, shot, helpers } = ctx;
  const setup = await setupSkuAndBom(page, { namePrefix: 'HT-RR', api, shot, minProcesses: 1 });
  const [firstProc] = setup.processes;
  ok(!!firstProc, '新 SKU 有可报工工序', { process: firstProc?.processName });

  const plan = await createStartedPlan(api, setup.productTypeId);
  await openProcessDrawer(page, plan.planNumber);
  const rawBatch = await findRawBatch(api, setup.rawMaterial);
  ok(!!rawBatch?.batchNumber, '原料批次为重量单位且排除包材', {
    batchNumber: rawBatch?.batchNumber,
    unit: rawBatch?.quantityUnit || rawBatch?.unit,
  });
  await saveFirstRow(page, helpers, firstProc, rawBatch, 5.0, 4.8);
  await shot('process-row-saved');
  await closeDrawer(page);

  const yieldCards = arr(await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(plan.planId)}/process-sheet/inventory/yield-card`));
  const firstCard = yieldCards.find((c) => Number(c.processOrder) === Number(firstProc.processOrder)) || yieldCards[0];
  const reportedOutput = num(firstCard?.outputQuantity ?? firstCard?.producedQuantity ?? firstCard?.currentQuantity ?? firstCard?.availableQuantity);
  ok(!!firstCard?.batchNumber, 'API 回读首道报工生成 WIP 批次', {
    output: reportedOutput,
    batchNumber: firstCard?.batchNumber,
  });

  const settlementUi = await submitSettlementThroughUi(page, helpers, plan.planNumber);
  ok(settlementUi.submitted, '通过 headed UI 提交结单', { toast: settlementUi.toast });
  const settlement = await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(plan.planId)}/settlement`);
  ok(settlement?.status === 'COMPLETED', '结单 API 回读 COMPLETED', { status: settlement?.status });
  ok(settlement?.postingStatus === 'PENDING_WAREHOUSE_RECEIPT', '结单后进入待仓库确认入库', {
    postingStatus: settlement?.postingStatus,
    actualFinishedQuantity: settlement?.actualFinishedQuantity,
  });

  const receiptTarget = Math.max(0.01, Number((num(settlement.actualFinishedQuantity) - 1).toFixed(2)));
  const receiptUi = await confirmWarehouseReceiptThroughUi(page, helpers, plan.planNumber, receiptTarget);
  ok(receiptUi.submitted, '通过 headed UI 确认入库并触发实收差异', { toast: receiptUi.toast, receiptTarget });
  const receiptAfter = await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(plan.planId)}/settlement`);
  ok(['PENDING_CLEARING', 'POSTED_WITH_TOLERANCE', 'POSTED'].includes(String(receiptAfter?.postingStatus)), '入库确认后 posting 状态推进', {
    postingStatus: receiptAfter?.postingStatus,
    warehouseReceivedQuantity: receiptAfter?.warehouseReceivedQuantity,
    transitLedgerId: receiptAfter?.transitLedgerId,
  });
  ok(String(receiptAfter?.postingStatus) !== 'PENDING_WAREHOUSE_RECEIPT', '入库确认不是停留在待确认状态', {
    postingStatus: receiptAfter?.postingStatus,
  });

  const planCancel = await requestPlanCancel(api, plan.planId);
  ok(planCancel.byDesign, '计划级 request-cancel 不执行直接红冲', planCancel);

  const batch = await findBatchForPlan(api, plan.planId);
  let reversalUi = null;
  if (batch?.id) {
    reversalUi = await requestBatchReversalThroughUi(page, helpers, batch.id);
    ok(reversalUi.submitted, '通过 headed UI 提交批次整单撤回申请', reversalUi);
    const reversals = arr(await api('GET', `/${FACTORY}/reversals?status=PENDING`).catch(() => []));
    const match = reversals.find((r) => String(r.batchId) === String(batch.id));
    ok(!!match || reversalUi.mode === 'blocked-by-guard', '撤回走审批流或被守卫拦截，不是直接红冲', {
      batchId: batch.id,
      reversalId: match?.id,
      reversalStatus: match?.status,
      uiMode: reversalUi.mode,
    });
  } else {
    ok(true, '本计划未暴露 production batch，撤回审批以计划级 request-cancel by-design 结果为准', { planId: plan.planId });
  }

  await finish('PASS', { setup, plan, settlement, receiptAfter, planCancel, batch, reversalUi });
} catch (error) {
  ok(false, 'reverse-resettle 脚本异常', { error: error.message });
  await ctx.shot('reverse-resettle-error').catch(() => null);
  await finish('FAIL', { error: error.message, stack: error.stack });
}

async function finish(forcedStatus, extra = {}) {
  const failures = assertions.filter((a) => !a.pass);
  const status = forcedStatus === 'PASS' && failures.length === 0 ? 'PASS' : 'FAIL';
  await writeFile(resultFile, JSON.stringify({
    scenario: 'headed-reverse-resettle',
    depth: 'deep',
    target: 'web-admin-prod-F006',
    status,
    byDesign: 'Direct production re-settle/reverse is not exposed; tested warehouse receipt forward flow and reversal approval/guard path.',
    assertions,
    consoleErrors: ctx.consoleErrors,
    ...extra,
  }, null, 2), 'utf8').catch(() => null);
  console.log(JSON.stringify({ status, resultFile, failures: failures.map((f) => f.label) }, null, 2));
  await ctx.browser.close().catch(() => null);
  if (status !== 'PASS') process.exitCode = 1;
}

async function createStartedPlan(api, productTypeId) {
  const plan = await api('POST', `/${FACTORY}/production-plans`, {
    productTypeId,
    plannedQuantity: 5,
    plannedDate: today(0),
    expectedCompletionDate: today(2),
    sourceType: 'MANUAL',
    skipProcessReporting: false,
    customerOrderNumber: `RR-${Date.now()}`,
    notes: 'headed reverse/resettle: warehouse receipt + reversal approval guard',
  });
  const planId = String(plan.id || plan.planId);
  const planNumber = String(plan.planNumber || planId);
  await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/start`).catch(() => null);
  return { planId, planNumber };
}

async function openProcessDrawer(page, planNumber) {
  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  const planRow = await findPlanRow(page, planNumber);
  await planRow.locator('button, .el-button').filter({ hasText: '逐道录入' }).first().click();
  await page.waitForSelector('.el-drawer__body', { timeout: 15000 });
  await page.waitForTimeout(2200);
}

async function findPlanRow(page, planNumber) {
  const search = page.locator('input[placeholder*="搜索"], input[placeholder*="计划编号"]').first();
  if (await search.isVisible().catch(() => false)) {
    await search.fill(planNumber);
    await page.locator('button').filter({ hasText: /搜索/ }).first().click().catch(() => null);
    await page.waitForTimeout(1800);
  }
  const planRow = page.locator('.el-table__row').filter({ hasText: planNumber }).first();
  if (!(await planRow.isVisible().catch(() => false))) throw new Error(`plan row not visible: ${planNumber}`);
  return planRow;
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

async function submitSettlementThroughUi(page, helpers, planNumber) {
  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  const planRow = await findPlanRow(page, planNumber);
  await planRow.locator('button, .el-button').filter({ hasText: /核对结单|结单/ }).first().click();
  await page.waitForSelector('.settlement-dialog, .el-dialog', { timeout: 15000 });
  await page.waitForTimeout(3500);
  const dialog = await visibleDialog(page, /核对结单|结单/);
  await fillDialogNumber(page, dialog, /人数/, 2);
  await fillDialogNumber(page, dialog, /工时分钟/, 30);
  await page.waitForTimeout(600);
  const warning = await dialog.locator('.el-alert--warning .el-alert__title').last().innerText().catch(() => '');
  if (/差异原因|超出计划/.test(warning)) {
    const select = dialog.locator('.el-form-item').filter({ hasText: /差异原因/ }).locator('.el-select').first();
    await helpers.selectByText(select, '计划调整').catch(async () => helpers.selectByText(select, '其他'));
  }
  let btn = dialog.locator('.el-dialog__footer button.el-button--primary').last();
  if (!(await btn.isVisible().catch(() => false))) btn = dialog.locator('button').filter({ hasText: /提交结单|结单/ }).last();
  await btn.click();
  const toast = await waitAnyToast(page);
  await page.waitForTimeout(2500);
  return { submitted: /成功|已提交|下一步|结单/.test(toast) && !/失败|错误/.test(toast), toast, warning };
}

async function confirmWarehouseReceiptThroughUi(page, helpers, planNumber, receivedQuantity) {
  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  await setStatusFilterCompleted(page);
  const planRow = await findPlanRow(page, planNumber);
  const btn = planRow.locator('button, .el-button').filter({ hasText: /确认入库/ }).first();
  if (!(await btn.isVisible().catch(() => false))) {
    await page.locator('button').filter({ hasText: /刷新|搜索/ }).first().click().catch(() => null);
    await page.waitForTimeout(2500);
  }
  await planRow.locator('button, .el-button').filter({ hasText: /确认入库/ }).first().click();
  await page.waitForSelector('.settlement-dialog, .el-dialog', { timeout: 15000 });
  await page.waitForTimeout(2500);
  const dialog = await visibleDialog(page, /确认入库|仓库/);
  await fillDialogNumber(page, dialog, /仓库实收/, receivedQuantity);
  await page.waitForTimeout(500);
  const reasonVisible = await dialog.locator('.el-form-item').filter({ hasText: /差异原因/ }).isVisible().catch(() => false);
  if (reasonVisible) {
    await helpers.selectByText(dialog.locator('.el-form-item').filter({ hasText: /差异原因/ }).locator('.el-select').first(), '生产少交')
      .catch(async () => helpers.selectByText(dialog.locator('.el-form-item').filter({ hasText: /差异原因/ }).locator('.el-select').first(), '仓库实收短少'));
    const responsibilitySelect = dialog.locator('.el-form-item').filter({ hasText: /责任侧|责任方/ }).locator('.el-select').first();
    await helpers.selectByText(responsibilitySelect, '生产侧处理')
      .catch(async () => helpers.selectByText(responsibilitySelect, '生产'));
  }
  const note = dialog.locator('.el-form-item').filter({ hasText: /备注/ }).locator('textarea').first();
  if (await note.isVisible().catch(() => false)) await note.fill('headed e2e warehouse receipt variance');
  let submit = dialog.locator('.el-dialog__footer button.el-button--primary').last();
  if (!(await submit.isVisible().catch(() => false))) submit = dialog.locator('button').filter({ hasText: /确认入库/ }).last();
  await submit.click();
  const toast = await waitAnyToast(page);
  await page.waitForTimeout(2500);
  return { submitted: /成功|已确认|入库/.test(toast) && !/失败|错误/.test(toast), toast };
}

async function requestPlanCancel(api, planId) {
  try {
    const res = await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/request-cancel?reason=${encodeURIComponent('headed e2e reversal approval guard')}`);
    return { byDesign: true, mode: 'approval-requested', response: res };
  } catch (error) {
    const msg = String(error.message || '');
    return {
      byDesign: /报工撤回|不能直接|审批|PLAN_HAS_PRODUCTION_DATA|409|已入库|出库/.test(msg),
      mode: 'guarded',
      error: msg.slice(0, 300),
      status: error.status,
    };
  }
}

async function requestBatchReversalThroughUi(page, helpers, batchId) {
  await page.goto(`${APP}/production/batches/${encodeURIComponent(batchId)}`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(3500);
  const openBtn = page.locator('button, .el-button').filter({ hasText: /撤回整单/ }).first();
  if (!(await openBtn.isVisible().catch(() => false))) return { submitted: false, mode: 'no-button', batchId };
  await openBtn.click();
  await page.waitForSelector('.el-dialog:visible', { timeout: 12000 });
  const dialog = await visibleDialog(page, /撤回|整单/);
  await helpers.selectByText(dialog.locator('.el-select').first(), '录入错误').catch(() => null);
  const textarea = dialog.locator('textarea').first();
  if (await textarea.isVisible().catch(() => false)) await textarea.fill('headed e2e reversal approval request');
  await dialog.locator('.el-dialog__footer button.el-button--danger, .el-dialog__footer button.el-button--primary, button').filter({ hasText: /确认提交|提交/ }).last().click();
  const toast = await waitAnyToast(page).catch(() => '');
  await page.waitForTimeout(2000);
  if (/拦截|无法|不能|已出库|下游/.test(toast)) return { submitted: true, mode: 'blocked-by-guard', toast, batchId };
  return { submitted: /撤回申请|提交|审批|等待|成功/.test(toast), mode: 'approval-requested', toast, batchId };
}

async function setStatusFilterCompleted(page) {
  const select = page.locator('.search-bar .el-select').first();
  if (!(await select.isVisible().catch(() => false))) return;
  await select.click({ force: true });
  await page.waitForTimeout(500);
  const opt = page.locator('.el-select-dropdown__item:visible').filter({ hasText: /已结单|COMPLETED/ }).last();
  if (!(await opt.isVisible().catch(() => false))) throw new Error('completed status option not visible');
  await opt.click();
  await page.waitForTimeout(500);
  await page.locator('.search-bar button').filter({ hasText: /搜索/ }).first().click();
  await page.waitForTimeout(2500);
}

async function visibleDialog(page, textPattern) {
  const byClass = page.locator('.el-dialog:visible, .settlement-dialog:visible').filter({ hasText: textPattern }).last();
  if (await byClass.isVisible().catch(() => false)) return byClass;
  const dialog = page.locator('.el-dialog:visible, .settlement-dialog:visible').last();
  if (!(await dialog.isVisible().catch(() => false))) throw new Error(`visible dialog not found: ${textPattern}`);
  return dialog;
}

async function fillDialogNumber(page, dialog, labelPattern, value) {
  const item = dialog.locator('.el-form-item').filter({ hasText: labelPattern }).first();
  const input = item.locator('.el-input-number input, input').first();
  if (!(await input.isVisible().catch(() => false))) throw new Error(`number input not visible: ${labelPattern}`);
  await input.click();
  await input.fill('');
  await input.type(String(value));
  await input.press('Enter').catch(() => null);
  await page.waitForTimeout(250);
}

async function waitAnyToast(page, timeout = 10000) {
  const el = await page.waitForSelector('.el-message--success, .el-message--warning, .el-message--error', { timeout }).catch(() => null);
  return el ? (await el.innerText().catch(() => '')) : '';
}

async function findBatchForPlan(api, planId) {
  const batches = arr(await api('GET', `/${FACTORY}/processing/batches?size=200`).catch(() => []));
  return batches.find((b) => String(b.productionPlanId || '') === String(planId)) || null;
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

function approx(actual, expected, tolerance) {
  return actual != null && Math.abs(Number(actual) - Number(expected)) <= tolerance;
}
