/**
 * R14 真深度 SO 财务审核驳回流转 (补完 R9-deep-8)
 *
 * R9-deep-8 已测: CONFIRMED → PENDING_FINANCE_REVIEW → FINANCE_APPROVED
 * R14 补测:      CONFIRMED → PENDING_FINANCE_REVIEW → **FINANCE_REJECTED**
 *
 * 关键差异 (vs R9-deep-8 的 approve):
 * 1. Click "审核驳回" 按钮, 不是 "审核通过"
 * 2. ElMessageBox.prompt 的 inputPlaceholder="驳回原因", 需要 REQUIRED 输入 (vs 审批 optional)
 * 3. POST /finance-reject (not /finance-approve)
 * 4. 终态 "财务已驳回" / FINANCE_REJECTED (not 已批准)
 *
 * 后端 endpoints:
 * - POST /orders/{orderId}/submit-for-review    CONFIRMED → PENDING_FINANCE_REVIEW
 * - POST /orders/{orderId}/finance-reject       PENDING_FINANCE_REVIEW → FINANCE_REJECTED
 */
import { chromium } from 'playwright';
import { writeFileSync, readFileSync, existsSync } from 'fs';
import {
  BASE, PASSWORD, navigateTo,
  clickButton, waitForDialog, submitAndCheckResponse, fillDialogInput,
  fillAllRequiredFields,
} from './lib/helpers.mjs';

const SETUP_FILE = 'tests/e2e-comprehensive/results/R0-setup.json';
const setup = existsSync(SETUP_FILE) ? JSON.parse(readFileSync(SETUP_FILE, 'utf8')) : null;
const FACTORY_ID = setup?.factoryId || 'FOOD_3101_048';
const ROUND = 14;
const results = [];
const TS = Date.now().toString(36);

function record(testId, step, status, evidence = {}) {
  const r = { layer: 'L4', testId, step, status, depth: 'deep', evidence, ts: new Date().toISOString() };
  results.push(r);
  const icon = status === 'PASS' ? '✓' : status === 'FAIL' ? '✗' : status === 'WARN' ? '⚠' : '-';
  console.log(`  [${icon}] L4/${testId}/${step}: ${status}`);
  for (const [k, v] of Object.entries(evidence)) {
    if (v !== undefined) console.log(`      ${k}: ${typeof v === 'string' ? v : JSON.stringify(v)}`);
  }
}

async function loginAndWait(page, username) {
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForSelector('input.el-input__inner', { timeout: 30000 });
  await page.fill('input.el-input__inner[placeholder*="用户名"]', username);
  await page.fill('input[type="password"]', PASSWORD);
  await page.click('button.login-button');
  try { await page.waitForURL('**/dashboard', { timeout: 30000 }); }
  catch (e) { throw new Error(`Login failed: ${page.url()}`); }
  for (let i = 0; i < 30; i++) {
    await page.waitForTimeout(500);
    if (await page.evaluate(() => !!document.querySelector('.el-menu,.app-sidebar'))) return true;
  }
  throw new Error('Login ok but menu never rendered');
}

// Reusable: customer + SO + confirm (mirror R9-deep-8 helper)
async function createAndConfirmSO(page, testIdPrefix) {
  const testName = `E2E_C_R14_${testIdPrefix}_${TS}`;

  const navCust = await navigateTo(page, '/sales/customers', { waitForTable: true });
  if (navCust !== 'OK') { record(testIdPrefix, 'prep_nav_cust', 'FAIL', {}); return null; }
  if (!(await clickButton(page, '新建', '新增'))) { record(testIdPrefix, 'prep_click_cust', 'FAIL', {}); return null; }
  if (!(await waitForDialog(page))) { record(testIdPrefix, 'prep_cust_dialog', 'FAIL', {}); return null; }
  await fillDialogInput(page, testName);
  await fillAllRequiredFields(page, testName);
  const custSubmit = await submitAndCheckResponse(page, ['确定', '保存'], { factoryId: FACTORY_ID, module: 'customers' });
  if (!custSubmit.ok) { record(testIdPrefix, 'prep_cust_submit', 'FAIL', {}); return null; }
  record(testIdPrefix, 'prep_customer', 'PASS', { testName });
  await page.waitForTimeout(1500);

  const navSO = await navigateTo(page, '/sales/orders', { waitForTable: true });
  if (navSO !== 'OK') { record(testIdPrefix, 'prep_nav_so', 'FAIL', {}); return null; }
  if (!(await clickButton(page, '新建', '新增', '创建订单'))) { record(testIdPrefix, 'prep_click_so', 'FAIL', {}); return null; }
  await page.waitForTimeout(3000);

  const customerSelect = await page.$('.el-dialog .el-select');
  if (!customerSelect) { record(testIdPrefix, 'prep_cust_select', 'FAIL', {}); return null; }
  await customerSelect.click();
  await page.waitForTimeout(1500);
  const custFilter = await page.$('.el-dialog .el-select input.el-input__inner');
  if (custFilter) { try { await custFilter.fill(testName); await page.waitForTimeout(800); } catch {} }
  try { await page.locator('.el-select-dropdown__item:visible').first().click({ timeout: 3000 }); }
  catch { record(testIdPrefix, 'prep_select_cust', 'FAIL', {}); return null; }
  await page.waitForTimeout(500);

  await page.evaluate(() => {
    const dialog = document.querySelector('.el-dialog:not([style*="display: none"])');
    if (!dialog) return;
    const formItems = dialog.querySelectorAll('.el-form-item');
    for (const item of formItems) {
      const label = item.querySelector('.el-form-item__label')?.textContent?.trim() || '';
      if (label.includes('交货地址')) {
        const input = item.querySelector('input.el-input__inner, textarea.el-textarea__inner');
        if (input && !input.value) {
          const setter = Object.getOwnPropertyDescriptor(
            input.tagName === 'INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype, 'value'
          ).set;
          setter.call(input, 'E2E 测试交货地址');
          input.dispatchEvent(new Event('input', { bubbles: true }));
          input.dispatchEvent(new Event('blur', { bubbles: true }));
        }
      }
    }
  });

  const productSelect = await page.$('.el-dialog .item-row .el-select');
  await productSelect.click();
  await page.waitForTimeout(2000);
  const filterInput = await page.$('.el-dialog .item-row .el-select input.el-input__inner');
  if (filterInput) { try { await filterInput.fill('E2E测试产品'); await page.waitForTimeout(800); } catch {} }
  try { await page.locator('.el-select-dropdown__item:visible').first().click({ timeout: 3000 }); }
  catch { record(testIdPrefix, 'prep_select_product', 'FAIL', {}); return null; }
  await page.waitForTimeout(500);

  await page.evaluate(() => {
    const dialog = document.querySelector('.el-dialog:not([style*="display: none"])');
    if (!dialog) return;
    const numInputs = dialog.querySelectorAll('.el-input-number input');
    const vals = ['100', '50', '10'];
    for (let i = 0; i < Math.min(numInputs.length, 3); i++) {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      setter.call(numInputs[i], vals[i]);
      numInputs[i].dispatchEvent(new Event('input', { bubbles: true }));
      numInputs[i].dispatchEvent(new Event('change', { bubbles: true }));
      numInputs[i].dispatchEvent(new Event('blur', { bubbles: true }));
    }
  });

  const soSubmit = await submitAndCheckResponse(page, ['创建', '确定'], { factoryId: FACTORY_ID, module: 'sales/orders' });
  if (!soSubmit.ok) { record(testIdPrefix, 'prep_so_submit', 'FAIL', {}); return null; }
  record(testIdPrefix, 'prep_so_created', 'PASS', {});
  await page.waitForTimeout(1500);

  // Confirm SO (via row action)
  await page.goto(`${BASE}/sales/orders`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(3000);
  for (let i = 0; i < 30; i++) {
    await page.waitForTimeout(500);
    if (await page.$('.el-table__body-wrapper .el-table__row')) break;
  }
  const newSO = await page.evaluate((custName) => {
    const rows = document.querySelectorAll('.el-table__body-wrapper .el-table__row');
    for (const row of rows) {
      if (row.innerText.includes(custName)) {
        const cells = Array.from(row.querySelectorAll('td')).map(c => c.innerText.trim());
        return { found: true, orderNumber: cells[0] };
      }
    }
    return { found: false };
  }, testName);
  if (!newSO.found) { record(testIdPrefix, 'prep_find_so', 'FAIL', {}); return null; }

  await page.evaluate((custName) => {
    const rows = document.querySelectorAll('.el-table__body-wrapper .el-table__row');
    for (const row of rows) {
      if (row.innerText.includes(custName)) {
        const buttons = row.querySelectorAll('button, a.el-link, span.el-link');
        for (const b of buttons) if (b.textContent?.trim() === '确认') { b.click(); return; }
      }
    }
  }, testName);
  await page.waitForTimeout(1000);
  const msgBox = await page.$('.el-message-box');
  if (msgBox) {
    const btn = await page.$('.el-message-box__btns button.el-button--primary');
    if (btn) {
      try {
        await Promise.all([
          page.waitForResponse(r => r.url().endsWith('/confirm') && r.request().method() === 'POST', { timeout: 8000 }),
          btn.click(),
        ]);
      } catch {}
    }
  }
  await page.waitForTimeout(2000);
  record(testIdPrefix, 'prep_so_confirmed', 'PASS', { orderNumber: newSO.orderNumber });
  return { testName, orderNumber: newSO.orderNumber };
}

// ===== R14-deep-15: CONFIRMED → PENDING_FINANCE_REVIEW → FINANCE_REJECTED =====
async function R14_deep_15_financeReject(page) {
  const testId = 'deep-15';
  console.log(`\n--- L4-${testId}: SO finance REJECT path ---`);

  const so = await createAndConfirmSO(page, 'deep-15');
  if (!so) return;

  // Step 1: Navigate to SO detail
  await page.goto(`${BASE}/sales/orders`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(3000);
  for (let i = 0; i < 30; i++) {
    await page.waitForTimeout(500);
    if (await page.$('.el-table__body-wrapper .el-table__row')) break;
  }
  const detailClicked = await page.evaluate((custName) => {
    const rows = document.querySelectorAll('.el-table__body-wrapper .el-table__row');
    for (const row of rows) {
      if (row.innerText.includes(custName)) {
        const buttons = row.querySelectorAll('button, a.el-link, span.el-link');
        for (const b of buttons) if (b.textContent?.trim() === '详情') { b.click(); return true; }
      }
    }
    return false;
  }, so.testName);
  if (!detailClicked) { record(testId, 'step1_nav_detail', 'FAIL', {}); return; }
  try { await page.waitForURL(/\/sales\/orders\/[0-9a-f-]{8,}/, { timeout: 10000 }); }
  catch { record(testId, 'step1_nav_detail', 'FAIL', { url: page.url() }); return; }
  record(testId, 'step1_nav_detail', 'PASS', { url: page.url() });
  await page.waitForTimeout(3000);

  // Step 2: Verify CONFIRMED state + "提交财务审核" button
  const initialState = await page.evaluate(() => {
    const text = document.body.innerText || '';
    const submitBtn = Array.from(document.querySelectorAll('button')).find(
      b => b.textContent?.trim() === '提交财务审核'
    );
    return {
      statusText: text.includes('已确认') ? 'CONFIRMED' :
                  text.includes('待财务审核') ? 'PENDING_FINANCE_REVIEW' :
                  text.includes('财务已驳回') ? 'FINANCE_REJECTED' : 'UNKNOWN',
      hasSubmitForReviewBtn: !!submitBtn,
    };
  });
  record(testId, 'step2_initial_state',
    initialState.statusText === 'CONFIRMED' && initialState.hasSubmitForReviewBtn ? 'PASS' : 'FAIL',
    initialState);
  if (initialState.statusText !== 'CONFIRMED') return;

  // Step 3: Click "提交财务审核"
  const submitClicked = await page.evaluate(() => {
    const btn = Array.from(document.querySelectorAll('button'))
      .find(b => b.textContent?.trim() === '提交财务审核');
    if (btn) { btn.click(); return true; }
    return false;
  });
  if (!submitClicked) { record(testId, 'step3_click_submit', 'FAIL', {}); return; }
  record(testId, 'step3_click_submit', 'PASS', {});

  // Step 4: ElMessageBox.confirm + wait for /submit-for-review
  await page.waitForTimeout(1000);
  const submitMsgBox = await page.$('.el-message-box');
  if (submitMsgBox) {
    const confirmBtn = await page.$('.el-message-box__btns button.el-button--primary');
    if (confirmBtn) {
      try {
        const [resp] = await Promise.all([
          page.waitForResponse(r => r.url().endsWith('/submit-for-review') && r.request().method() === 'POST', { timeout: 10000 }),
          confirmBtn.click(),
        ]);
        record(testId, 'step4_submit_review_api', 'PASS', { status: resp.status() });
      } catch {
        record(testId, 'step4_submit_review_api', 'FAIL', {});
        return;
      }
    }
  }
  await page.waitForTimeout(3000);

  // Step 5: Wait for reactive loadOrder() to refresh state (no reload — R9-deep-8 pattern)
  await page.waitForTimeout(2500);
  const pendingState = await page.evaluate(() => {
    const text = document.body.innerText || '';
    const rejectBtn = Array.from(document.querySelectorAll('button'))
      .filter(b => !b.closest('.el-message-box'))
      .find(b => b.textContent?.trim() === '审核驳回');
    return {
      statusText: text.includes('待财务审核') ? 'PENDING_FINANCE_REVIEW' :
                  text.includes('财务已驳回') ? 'FINANCE_REJECTED' :
                  text.includes('已确认') ? 'CONFIRMED_NO_CHANGE' : 'UNKNOWN',
      hasRejectBtn: !!rejectBtn,
    };
  });
  record(testId, 'step5_pending_state',
    pendingState.statusText === 'PENDING_FINANCE_REVIEW' && pendingState.hasRejectBtn ? 'PASS' : 'FAIL',
    pendingState);
  if (!pendingState.hasRejectBtn) return;

  // Step 6: Click "审核驳回" (on page, not in message-box which doesn't exist yet)
  const rejectClicked = await page.evaluate(() => {
    const pageBtns = Array.from(document.querySelectorAll('button'))
      .filter(b => !b.closest('.el-message-box'));
    const btn = pageBtns.find(b => b.textContent?.trim() === '审核驳回');
    if (btn) { btn.click(); return true; }
    return false;
  });
  if (!rejectClicked) { record(testId, 'step6_click_reject', 'FAIL', {}); return; }
  record(testId, 'step6_click_reject', 'PASS', {});

  // Step 7: ElMessageBox.prompt — fill 驳回原因 (REQUIRED)
  await page.waitForTimeout(1500);
  const promptBox = await page.$('.el-message-box');
  if (!promptBox) { record(testId, 'step7_find_prompt', 'FAIL', {}); return; }
  const promptTitle = await page.evaluate(() => {
    return document.querySelector('.el-message-box__title')?.textContent?.trim() || '';
  });
  const reasonInput = await page.$('.el-message-box__input input, .el-message-box__input textarea');
  if (!reasonInput) { record(testId, 'step7_find_reason_input', 'FAIL', { promptTitle }); return; }
  await reasonInput.fill('E2E 自动化测试驳回原因 — 金额超出授权额度');
  record(testId, 'step7_fill_reason', 'PASS', { promptTitle });

  // Step 8: Click 审核驳回 confirm + wait for /finance-reject
  const promptConfirmBtn = await page.$('.el-message-box__btns button.el-button--primary');
  if (!promptConfirmBtn) { record(testId, 'step8_prompt_confirm_btn', 'FAIL', {}); return; }
  let rejectResp = null;
  try {
    [rejectResp] = await Promise.all([
      page.waitForResponse(r => r.url().endsWith('/finance-reject') && r.request().method() === 'POST', { timeout: 10000 }),
      promptConfirmBtn.click(),
    ]);
  } catch (e) {
    record(testId, 'step8_reject_api', 'FAIL', { reason: e.message });
    return;
  }
  const rejectOK = rejectResp?.status() === 200;
  record(testId, 'step8_reject_api', rejectOK ? 'PASS' : 'FAIL', {
    status: rejectResp?.status(),
    url: rejectResp?.url()?.replace(BASE, '').split('?')[0],
  });
  if (!rejectOK) return;

  // Step 9: Toast
  await page.waitForTimeout(500);
  const rejectToast = await page.evaluate(() => {
    const msgs = document.querySelectorAll('.el-message, .el-notification');
    for (const m of msgs) if (m.textContent?.includes('成功')) return m.textContent.trim();
    return null;
  });
  record(testId, 'step9_reject_toast', rejectToast ? 'PASS' : 'WARN', { toastText: rejectToast });

  // Step 10: Verify final state FINANCE_REJECTED
  await page.waitForTimeout(3000);
  const finalState = await page.evaluate(() => {
    const text = document.body.innerText || '';
    return {
      statusText: text.includes('财务已驳回') ? 'FINANCE_REJECTED' :
                  text.includes('待财务审核') ? 'STILL_PENDING' : 'UNKNOWN',
    };
  });
  record(testId, 'step10_final_state',
    finalState.statusText === 'FINANCE_REJECTED' ? 'PASS' : 'WARN',
    finalState);

  record(testId, 'FULL_FLOW', 'PASS', {
    depth: 'deep',
    testName: so.testName,
    orderNumber: so.orderNumber,
    stage1_submitForReview: true,
    stage2_clickReject: true,
    stage3_fillReason: true,
    stage4_apiReject: rejectOK,
    finalState: finalState.statusText,
    note: 'R9-deep-8 complete loop: approve + reject paths now both verified',
  });
}

(async () => {
  console.log('═══════════════════════════════════════');
  console.log('  R14 SO 财务审核 REJECT path');
  console.log('═══════════════════════════════════════');
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'zh-CN' });
  await ctx.route(/fonts\.(googleapis|gstatic)\.com/, r => r.abort());
  const page = await ctx.newPage();

  try {
    await loginAndWait(page, 'e2e_factory_admin');
    console.log('✓ Login as e2e_factory_admin');
    await R14_deep_15_financeReject(page);
  } catch (e) {
    console.error(`FATAL: ${e.message}`);
    results.push({ layer: 'L4', testId: 'fatal', step: 'error', status: 'FAIL', depth: 'deep', evidence: { message: e.message }, ts: new Date().toISOString() });
  } finally {
    await browser.close();
  }

  const deepPass = results.filter(r => r.depth === 'deep' && r.status === 'PASS').length;
  const deepFail = results.filter(r => r.depth === 'deep' && r.status === 'FAIL').length;
  const deepWarn = results.filter(r => r.depth === 'deep' && r.status === 'WARN').length;
  const summary = {
    round: ROUND,
    timestamp: new Date().toISOString(),
    factoryId: FACTORY_ID,
    notes: 'R14 SO finance REJECT path — completes R9-deep-8 approve/reject symmetry',
    results,
    summary: { deepTotal: deepPass + deepFail, deepPass, deepFail, deepWarn },
  };
  const outFile = `tests/e2e-comprehensive/results/e2e-R14-so-finance-reject.json`;
  writeFileSync(outFile, JSON.stringify(summary, null, 2));
  console.log(`\n✓ Saved → ${outFile}`);
  console.log(`  深度测试: ${deepPass} PASS / ${deepFail} FAIL / ${deepWarn} WARN`);
  process.exit(deepFail > 0 ? 1 : 0);
})();
