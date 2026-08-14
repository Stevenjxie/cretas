/**
 * R9-deep-8 真深度 SO 财务审核流转测试
 *
 * 延续 R9 SO 状态机测试:
 * R9-deep-7: DRAFT → CONFIRMED ✅ (已完成)
 * R9-deep-8: CONFIRMED → PENDING_FINANCE_REVIEW → FINANCE_APPROVED ← 本轮
 * R9-deep-9: DRAFT → CANCELLED ✅ (已完成)
 *
 * 关键差异 vs R9-deep-7:
 * 1. 按钮在 detail 页面, 不在 list 行
 * 2. 使用 ElMessageBox.prompt 不是 confirm (finance-approve 需要可选 notes)
 * 3. 两阶段状态流转: CONFIRMED → PENDING_FINANCE_REVIEW → FINANCE_APPROVED
 *
 * 后端 endpoints (from SalesController.java):
 * - POST /orders/{orderId}/submit-for-review    CONFIRMED → PENDING_FINANCE_REVIEW
 * - POST /orders/{orderId}/finance-approve       PENDING_FINANCE_REVIEW → FINANCE_APPROVED
 */
import { chromium } from 'playwright';
import { writeFileSync, readFileSync, existsSync } from 'fs';
import {
  BASE, PASSWORD, navigateTo, countTableRows,
  clickButton, waitForDialog, submitAndCheckResponse, fillDialogInput,
  fillAllRequiredFields,
} from './lib/helpers.mjs';

const SETUP_FILE = 'tests/e2e-comprehensive/results/R0-setup.json';
const setup = existsSync(SETUP_FILE) ? JSON.parse(readFileSync(SETUP_FILE, 'utf8')) : null;
const FACTORY_ID = setup?.factoryId || 'FOOD_3101_048';
const ROUND = 9.8;
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

// Reusable: Create customer + SO + confirm (returns so object with orderNumber + orderId)
async function createAndConfirmSO(page, testIdPrefix) {
  const testName = `E2E_C_R98_${testIdPrefix}_${TS}`;

  // Create customer
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

  // Create SO
  const navSO = await navigateTo(page, '/sales/orders', { waitForTable: true });
  if (navSO !== 'OK') { record(testIdPrefix, 'prep_nav_so', 'FAIL', {}); return null; }
  if (!(await clickButton(page, '新建', '新增', '创建订单'))) { record(testIdPrefix, 'prep_click_so', 'FAIL', {}); return null; }
  await page.waitForTimeout(3000);

  // Select customer
  const customerSelect = await page.$('.el-dialog .el-select');
  if (!customerSelect) { record(testIdPrefix, 'prep_find_cust_sel', 'FAIL', {}); return null; }
  await customerSelect.click();
  await page.waitForTimeout(1000);
  const searchInput = await page.$('.el-select-dropdown__search input, .el-popover input');
  if (searchInput) { await searchInput.fill(testName); await page.waitForTimeout(800); }
  const selected = await page.evaluate((name) => {
    const opts = document.querySelectorAll('.el-select-dropdown__item');
    for (const opt of opts) {
      if (opt.textContent && opt.textContent.includes(name)) { opt.click(); return true; }
    }
    return false;
  }, testName);
  if (!selected) { record(testIdPrefix, 'prep_select_customer', 'FAIL', {}); return null; }
  await page.waitForTimeout(500);

  // Fill date
  const dateEditor = await page.$('.el-dialog .el-date-editor input');
  if (dateEditor) {
    const future = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);
    const dateStr = `${future.getFullYear()}-${String(future.getMonth() + 1).padStart(2, '0')}-${String(future.getDate()).padStart(2, '0')}`;
    await dateEditor.fill(dateStr);
    await page.keyboard.press('Enter');
    await page.waitForTimeout(500);
  }

  // Fill address
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

  // Select product
  const productSelect = await page.$('.el-dialog .item-row .el-select');
  if (!productSelect) { record(testIdPrefix, 'prep_find_prod_sel', 'FAIL', {}); return null; }
  await productSelect.click();
  await page.waitForTimeout(2000);
  const filterInput = await page.$('.el-dialog .item-row .el-select input.el-input__inner');
  if (filterInput) { try { await filterInput.fill('E2E测试产品'); await page.waitForTimeout(800); } catch {} }
  try {
    await page.locator('.el-select-dropdown__item:visible').first().click({ timeout: 3000 });
  } catch { record(testIdPrefix, 'prep_select_product', 'FAIL', {}); return null; }
  await page.waitForTimeout(500);

  // Fill numbers
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

  // Submit SO
  const soSubmit = await submitAndCheckResponse(page, ['创建', '确定'], { factoryId: FACTORY_ID, module: 'sales/orders' });
  if (!soSubmit.ok) { record(testIdPrefix, 'prep_so_submit', 'FAIL', { reason: soSubmit.reason }); return null; }
  record(testIdPrefix, 'prep_so_created', 'PASS', { apiStatus: soSubmit.status });
  await page.waitForTimeout(1500);

  // Navigate back to list, find SO and extract orderId from URL
  await page.goto(`${BASE}/sales/orders`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(3000);
  for (let i = 0; i < 30; i++) {
    await page.waitForTimeout(500);
    if (await page.$('.el-table__body-wrapper .el-table__row')) break;
  }

  // Get SO's orderNumber + id (from detail link)
  const newSO = await page.evaluate((custName) => {
    const rows = document.querySelectorAll('.el-table__body-wrapper .el-table__row');
    for (const row of rows) {
      if (row.innerText.includes(custName)) {
        const cells = Array.from(row.querySelectorAll('td')).map(c => c.innerText.trim());
        return { found: true, cells, orderNumber: cells[0] };
      }
    }
    return { found: false };
  }, testName);

  if (!newSO.found) { record(testIdPrefix, 'prep_find_so_row', 'FAIL', {}); return null; }
  record(testIdPrefix, 'prep_so_in_list', 'PASS', { orderNumber: newSO.orderNumber });

  // Confirm SO (via row action)
  const confirmClicked = await page.evaluate((custName) => {
    const rows = document.querySelectorAll('.el-table__body-wrapper .el-table__row');
    for (const row of rows) {
      if (row.innerText.includes(custName)) {
        const buttons = row.querySelectorAll('button, a.el-link, span.el-link');
        for (const b of buttons) {
          if (b.textContent && b.textContent.trim() === '确认') { b.click(); return true; }
        }
      }
    }
    return false;
  }, testName);
  if (!confirmClicked) { record(testIdPrefix, 'prep_click_confirm', 'FAIL', {}); return null; }
  await page.waitForTimeout(1000);

  // Handle confirm dialog + wait for API
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
  record(testIdPrefix, 'prep_so_confirmed', 'PASS', {});

  return { testName, orderNumber: newSO.orderNumber };
}

// Extract orderId from URL after clicking 详情
async function navigateToSODetail(page, testName) {
  // Navigate to list, find SO, click 详情
  await page.goto(`${BASE}/sales/orders`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(3000);
  for (let i = 0; i < 30; i++) {
    await page.waitForTimeout(500);
    if (await page.$('.el-table__body-wrapper .el-table__row')) break;
  }
  await page.waitForTimeout(1500);

  const detailClicked = await page.evaluate((custName) => {
    const rows = document.querySelectorAll('.el-table__body-wrapper .el-table__row');
    for (const row of rows) {
      if (row.innerText.includes(custName)) {
        const buttons = row.querySelectorAll('button, a.el-link, span.el-link');
        for (const b of buttons) {
          if (b.textContent && b.textContent.trim() === '详情') { b.click(); return true; }
        }
      }
    }
    return false;
  }, testName);

  if (!detailClicked) return null;
  await page.waitForTimeout(3000);
  // URL should now be /sales/orders/{uuid}
  const url = page.url();
  const match = url.match(/\/sales\/orders\/([a-f0-9-]+)/i);
  return match ? match[1] : null;
}

// ===== R9-deep-8: CONFIRMED → PENDING_FINANCE_REVIEW → FINANCE_APPROVED =====
async function R9_deep_8_financeReview(page) {
  const testId = 'deep-8';
  console.log(`\n--- L4-${testId}: SO finance review flow ---`);

  const so = await createAndConfirmSO(page, 'deep-8');
  if (!so) return;

  // Step 1: Navigate to SO detail page
  const orderId = await navigateToSODetail(page, so.testName);
  if (!orderId) {
    record(testId, 'step1_nav_detail', 'FAIL', { note: 'Could not navigate to detail page' });
    return;
  }
  record(testId, 'step1_nav_detail', 'PASS', { orderId });
  await page.waitForTimeout(2000);

  // Step 2: Verify detail page shows CONFIRMED state + "提交财务审核" button exists
  const initialState = await page.evaluate(() => {
    const text = document.body.innerText || '';
    const submitBtn = Array.from(document.querySelectorAll('button')).find(
      b => b.textContent && b.textContent.trim() === '提交财务审核'
    );
    return {
      statusText: text.includes('已确认') ? 'CONFIRMED' :
                  text.includes('待财务审核') ? 'PENDING_FINANCE_REVIEW' :
                  text.includes('财务已批准') ? 'FINANCE_APPROVED' : 'UNKNOWN',
      hasSubmitForReviewBtn: !!submitBtn,
    };
  });
  record(testId, 'step2_initial_state', initialState.statusText === 'CONFIRMED' && initialState.hasSubmitForReviewBtn ? 'PASS' : 'FAIL', initialState);
  if (initialState.statusText !== 'CONFIRMED') return;

  // Step 3: Click "提交财务审核" button
  const submitClicked = await page.evaluate(() => {
    const btns = Array.from(document.querySelectorAll('button'));
    const btn = btns.find(b => b.textContent && b.textContent.trim() === '提交财务审核');
    if (btn) { btn.click(); return true; }
    return false;
  });
  if (!submitClicked) {
    record(testId, 'step3_click_submit_review', 'FAIL', {});
    return;
  }
  record(testId, 'step3_click_submit_review', 'PASS', {});

  // Step 4: Handle ElMessageBox confirm + wait for submit-for-review API
  await page.waitForTimeout(1000);
  const msgBox = await page.$('.el-message-box');
  if (msgBox) {
    const confirmBtn = await page.$('.el-message-box__btns button.el-button--primary');
    if (confirmBtn) {
      try {
        const [response] = await Promise.all([
          page.waitForResponse(
            r => r.url().endsWith('/submit-for-review') && r.request().method() === 'POST',
            { timeout: 10000 }
          ),
          confirmBtn.click(),
        ]);
        record(testId, 'step4_submit_review_api', 'PASS', {
          status: response.status(),
          url: response.url().replace(BASE, '').split('?')[0],
        });
      } catch {
        record(testId, 'step4_submit_review_api', 'WARN', {});
      }
    }
  }

  // Step 5: Capture "提交财务审核成功" toast
  let toast1 = '';
  try {
    const t = await page.waitForSelector('.el-message--success', { timeout: 3000 });
    toast1 = await t.innerText();
  } catch { toast1 = '(not captured)'; }
  record(testId, 'step5_submit_toast', toast1 !== '(not captured)' ? 'PASS' : 'WARN', { toastText: toast1 });

  // Step 6: Verify state changed to PENDING_FINANCE_REVIEW (detail page auto-refreshes via loadOrder)
  await page.waitForTimeout(2500);
  const afterSubmit = await page.evaluate(() => {
    const text = document.body.innerText || '';
    const approveBtn = Array.from(document.querySelectorAll('button')).find(
      b => b.textContent && b.textContent.trim().includes('审核通过')
    );
    return {
      statusText: text.includes('待财务审核') ? 'PENDING_FINANCE_REVIEW' :
                  text.includes('财务已批准') ? 'FINANCE_APPROVED' :
                  text.includes('已确认') ? 'CONFIRMED_NO_CHANGE' : 'UNKNOWN',
      hasApproveBtn: !!approveBtn,
    };
  });
  record(testId, 'step6_state_pending', afterSubmit.statusText === 'PENDING_FINANCE_REVIEW' ? 'PASS' : 'FAIL', afterSubmit);
  if (afterSubmit.statusText !== 'PENDING_FINANCE_REVIEW') return;

  // Step 7: Click "审核通过" page button (exclude any message-box buttons if present)
  const approveClicked = await page.evaluate(() => {
    // Find buttons NOT inside el-message-box
    const btns = Array.from(document.querySelectorAll('button'));
    const pageBtns = btns.filter(b => !b.closest('.el-message-box'));
    const btn = pageBtns.find(b => b.textContent && b.textContent.trim() === '审核通过');
    if (btn) { btn.click(); return true; }
    return false;
  });
  if (!approveClicked) {
    record(testId, 'step7_click_approve', 'FAIL', {});
    return;
  }
  record(testId, 'step7_click_approve', 'PASS', {});

  // Step 8: Handle ElMessageBox PROMPT (not confirm) — has input field + 审核通过 button
  await page.waitForTimeout(1500);
  const promptBox = await page.$('.el-message-box');
  if (!promptBox) {
    record(testId, 'step8_find_prompt', 'FAIL', {});
    return;
  }

  // Optionally fill notes textarea
  const notesInput = await page.$('.el-message-box__input input, .el-message-box__input textarea');
  if (notesInput) {
    await notesInput.fill('E2E 自动化测试审核备注');
  }

  // Click confirm button (text: "审核通过")
  const promptConfirmBtn = await page.$('.el-message-box__btns button.el-button--primary');
  if (!promptConfirmBtn) {
    record(testId, 'step8_prompt_confirm_btn', 'FAIL', {});
    return;
  }

  try {
    const [approveResponse] = await Promise.all([
      page.waitForResponse(
        r => r.url().endsWith('/finance-approve') && r.request().method() === 'POST',
        { timeout: 10000 }
      ),
      promptConfirmBtn.click(),
    ]);
    record(testId, 'step8_approve_api', 'PASS', {
      status: approveResponse.status(),
      url: approveResponse.url().replace(BASE, '').split('?')[0],
    });
  } catch {
    record(testId, 'step8_approve_api', 'WARN', {});
  }

  // Step 9: Capture "审核通过成功" toast
  let toast2 = '';
  try {
    const t = await page.waitForSelector('.el-message--success', { timeout: 3000 });
    toast2 = await t.innerText();
  } catch { toast2 = '(not captured)'; }
  record(testId, 'step9_approve_toast', toast2 !== '(not captured)' ? 'PASS' : 'WARN', { toastText: toast2 });

  // Step 10: Verify final state is FINANCE_APPROVED
  await page.waitForTimeout(2500);
  const finalState = await page.evaluate(() => {
    const text = document.body.innerText || '';
    return {
      statusText: text.includes('财务已批准') ? 'FINANCE_APPROVED' :
                  text.includes('待财务审核') ? 'STILL_PENDING' : 'UNKNOWN',
    };
  });
  record(testId, 'step10_final_state', finalState.statusText === 'FINANCE_APPROVED' ? 'PASS' : 'FAIL', finalState);

  // Final: Full flow PASS
  const allStagesOk = afterSubmit.statusText === 'PENDING_FINANCE_REVIEW' && finalState.statusText === 'FINANCE_APPROVED';
  record(testId, 'FULL_FLOW', allStagesOk ? 'PASS' : 'FAIL', {
    depth: 'deep',
    customerName: so.testName,
    orderNumber: so.orderNumber,
    orderId,
    stateTransitions: 'CONFIRMED → PENDING_FINANCE_REVIEW → FINANCE_APPROVED',
    submitToast: toast1,
    approveToast: toast2,
    finalState: finalState.statusText,
  });
}

async function run() {
  console.log('='.repeat(70));
  console.log(`R9-deep-8 SO FINANCE REVIEW FLOW`);
  console.log(`Factory: ${FACTORY_ID}`);
  console.log(`Transitions: CONFIRMED → PENDING_FINANCE_REVIEW → FINANCE_APPROVED`);
  console.log('='.repeat(70));

  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.route('**fonts.googleapis.com**', r => r.fulfill({ status: 200, body: '' }));
  await ctx.route('**fonts.gstatic.com**', r => r.fulfill({ status: 200, body: '' }));
  const page = await ctx.newPage();

  console.log('\nLogging in...');
  await loginAndWait(page, 'e2e_factory_admin');
  console.log('Logged in.');

  await R9_deep_8_financeReview(page);

  await browser.close();

  const fullFlows = results.filter(r => r.step === 'FULL_FLOW');
  const pass = fullFlows.filter(r => r.status === 'PASS').length;
  const fail = fullFlows.filter(r => r.status === 'FAIL').length;

  console.log('\n' + '='.repeat(70));
  console.log(`R9-deep-8 SUMMARY`);
  console.log(`Deep tests: ${fullFlows.length} | PASS: ${pass} | FAIL: ${fail}`);
  console.log('='.repeat(70));

  const outFile = `tests/e2e-comprehensive/results/e2e-R9-deep-8-finance-review.json`;
  writeFileSync(outFile, JSON.stringify({
    round: ROUND,
    timestamp: new Date().toISOString(),
    factoryId: FACTORY_ID,
    notes: 'R9-deep-8 — SO finance review 2-stage state machine',
    results,
    summary: { deepTotal: fullFlows.length, deepPass: pass, deepFail: fail },
  }, null, 2));
  console.log(`\nResults → ${outFile}`);
}

run().catch(e => { console.error('FATAL:', e); process.exit(1); });
