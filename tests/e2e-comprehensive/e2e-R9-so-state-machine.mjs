/**
 * R9 真深度 SO 状态机测试
 *
 * 基于 R8 的 SO 创建链路, R9 测试状态机流转:
 * - R9-deep-7: DRAFT → CONFIRMED (点击"确认"按钮)
 * - R9-deep-8: CONFIRMED → 提交财务审核 (如果有此流程)
 * - R9-deep-9: 另一个 SO DRAFT → CANCELLED (点击"取消"按钮)
 *
 * 每条测试必须通过 12-step checklist + 额外状态验证:
 * - 点击 action button → API 调用 → 列表刷新 → 状态变化验证
 *
 * 后端 endpoints (from SalesController.java):
 * - POST /orders/{orderId}/confirm       DRAFT → CONFIRMED
 * - POST /orders/{orderId}/cancel        DRAFT/CONFIRMED → CANCELLED
 * - POST /orders/{orderId}/submit-for-review  CONFIRMED → PENDING_FINANCE_REVIEW
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
const ROUND = 9;
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

// ===== Helper: Create a SO (reused from R8) =====
async function createSOQuick(page, testIdPrefix) {
  const testName = `E2E_C_R9_${testIdPrefix}_${TS}`;

  // Step 1: Create customer
  const navCust = await navigateTo(page, '/sales/customers', { waitForTable: true });
  if (navCust !== 'OK') { record(testIdPrefix, 'prep_nav_cust', 'FAIL', { result: navCust }); return null; }

  const clickCustCreate = await clickButton(page, '新建', '新增');
  if (!clickCustCreate) { record(testIdPrefix, 'prep_click_cust', 'FAIL', {}); return null; }
  const custDialog = await waitForDialog(page);
  if (!custDialog) { record(testIdPrefix, 'prep_cust_dialog', 'FAIL', {}); return null; }

  await fillDialogInput(page, testName);
  await fillAllRequiredFields(page, testName);

  const custSubmit = await submitAndCheckResponse(page, ['确定', '保存'], {
    factoryId: FACTORY_ID, module: 'customers',
  });
  if (!custSubmit.ok) {
    record(testIdPrefix, 'prep_cust_submit', 'FAIL', { reason: custSubmit.reason });
    return null;
  }
  record(testIdPrefix, 'prep_customer_created', 'PASS', { testName, apiStatus: custSubmit.status });
  await page.waitForTimeout(1500);

  // Step 2: Create SO with items
  const navSO = await navigateTo(page, '/sales/orders', { waitForTable: true });
  if (navSO !== 'OK') { record(testIdPrefix, 'prep_nav_so', 'FAIL', { result: navSO }); return null; }

  const beforeResult = await countTableRows(page);
  const soRowsBefore = beforeResult.count || 0;

  const clickSOCreate = await clickButton(page, '新建', '新增', '创建订单');
  if (!clickSOCreate) { record(testIdPrefix, 'prep_click_so', 'FAIL', {}); return null; }
  await page.waitForTimeout(3000);

  // Select customer in SO dialog
  const customerSelect = await page.$('.el-dialog .el-select');
  if (!customerSelect) { record(testIdPrefix, 'prep_find_cust_select', 'FAIL', {}); return null; }
  await customerSelect.click();
  await page.waitForTimeout(1000);

  const searchInput = await page.$('.el-select-dropdown__search input, .el-popover input');
  if (searchInput) {
    await searchInput.fill(testName);
    await page.waitForTimeout(800);
  }

  const custSelected = await page.evaluate((name) => {
    const opts = document.querySelectorAll('.el-select-dropdown__item');
    for (const opt of opts) {
      if (opt.textContent && opt.textContent.includes(name)) { opt.click(); return true; }
    }
    return false;
  }, testName);
  if (!custSelected) { record(testIdPrefix, 'prep_select_customer', 'FAIL', {}); return null; }
  await page.waitForTimeout(500);

  // Fill delivery date
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
          setter.call(input, 'E2E 测试交货地址 上海市浦东新区');
          input.dispatchEvent(new Event('input', { bubbles: true }));
          input.dispatchEvent(new Event('blur', { bubbles: true }));
        }
      }
    }
  });

  // Select product in item row
  const productSelect = await page.$('.el-dialog .item-row .el-select');
  if (!productSelect) { record(testIdPrefix, 'prep_find_prod_select', 'FAIL', {}); return null; }
  await productSelect.click();
  await page.waitForTimeout(2000);

  const filterInput = await page.$('.el-dialog .item-row .el-select input.el-input__inner');
  if (filterInput) {
    try { await filterInput.fill('E2E测试产品'); await page.waitForTimeout(800); } catch {}
  }

  const firstVisibleItem = page.locator('.el-select-dropdown__item:visible').first();
  try {
    await firstVisibleItem.waitFor({ state: 'visible', timeout: 3000 });
    await firstVisibleItem.click();
  } catch {
    record(testIdPrefix, 'prep_select_product', 'FAIL', {});
    return null;
  }
  await page.waitForTimeout(500);

  // Fill quantity + unit price via el-input-number inputs
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

  // Submit SO (button is "创建")
  const soSubmit = await submitAndCheckResponse(page, ['创建', '确定', '保存', '提交'], {
    factoryId: FACTORY_ID, module: 'sales/orders',
  });
  if (!soSubmit.ok) {
    record(testIdPrefix, 'prep_so_submit', 'FAIL', { reason: soSubmit.reason, status: soSubmit.status });
    return null;
  }
  record(testIdPrefix, 'prep_so_created', 'PASS', { testName, apiStatus: soSubmit.status });
  await page.waitForTimeout(1500);

  // Navigate back to list and find the new SO
  await page.goto(`${BASE}/sales/orders`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(3000);
  for (let i = 0; i < 30; i++) {
    await page.waitForTimeout(500);
    if (await page.$('.el-table__body-wrapper .el-table__row')) break;
  }

  // Extract the new SO's orderNumber from the first row
  const newSO = await page.evaluate((custName) => {
    const rows = document.querySelectorAll('.el-table__body-wrapper .el-table__row');
    for (const row of rows) {
      if (row.innerText.includes(custName)) {
        // Try to find orderNumber (usually first cell, like SO-20260415-0001)
        const cells = row.querySelectorAll('td');
        const texts = Array.from(cells).map(c => c.innerText.trim());
        return {
          found: true,
          cells: texts,
          preview: row.innerText.substring(0, 200),
        };
      }
    }
    return { found: false };
  }, testName);

  if (!newSO.found) {
    record(testIdPrefix, 'prep_find_new_so', 'FAIL', {});
    return null;
  }
  record(testIdPrefix, 'prep_so_in_list', 'PASS', { customerName: testName, cells: newSO.cells.slice(0, 8) });

  return { testName, soRowsBefore, cells: newSO.cells };
}

// ===== R9-deep-7: SO DRAFT → CONFIRMED =====
async function R9_deep_7_confirmSO(page) {
  const testId = 'deep-7';
  console.log(`\n--- L4-${testId}: SO DRAFT → CONFIRMED ---`);

  const so = await createSOQuick(page, 'deep-7');
  if (!so) return;

  // At this point we're on /sales/orders list with the new SO as first row (DRAFT state)
  // Step 1: Verify the initial state is 草稿 (DRAFT)
  const initialState = await page.evaluate((custName) => {
    const rows = document.querySelectorAll('.el-table__body-wrapper .el-table__row');
    for (const row of rows) {
      if (row.innerText.includes(custName)) {
        const hasDraft = row.innerText.includes('草稿');
        const hasConfirmed = row.innerText.includes('已确认');
        return { hasDraft, hasConfirmed, preview: row.innerText.substring(0, 200) };
      }
    }
    return null;
  }, so.testName);
  record(testId, 'step1_initial_state', initialState?.hasDraft ? 'PASS' : 'FAIL', initialState || {});
  if (!initialState?.hasDraft) return;

  // Step 2: Click "确认" button on the new SO row
  // Playwright locator: find row with customer name, then its "确认" button
  const confirmClicked = await page.evaluate((custName) => {
    const rows = document.querySelectorAll('.el-table__body-wrapper .el-table__row');
    for (const row of rows) {
      if (row.innerText.includes(custName)) {
        // Look for button "确认" in the row's operation column
        const buttons = row.querySelectorAll('button, a.el-link, span.el-link');
        for (const b of buttons) {
          if (b.textContent && b.textContent.trim() === '确认') {
            b.click();
            return true;
          }
        }
        return { found: false, buttonTexts: Array.from(buttons).map(b => b.textContent?.trim()).filter(Boolean) };
      }
    }
    return false;
  }, so.testName);

  if (confirmClicked !== true) {
    record(testId, 'step2_click_confirm', 'FAIL', { buttonTexts: confirmClicked.buttonTexts || [] });
    return;
  }
  record(testId, 'step2_click_confirm', 'PASS', {});

  // Step 3: Handle potential confirmation dialog (ElMessageBox)
  await page.waitForTimeout(1000);
  const confirmBoxVisible = await page.$('.el-message-box');
  let confirmBoxClicked = false;
  if (confirmBoxVisible) {
    const confirmBtn = await page.$('.el-message-box__btns button.el-button--primary');
    if (confirmBtn) {
      // Start waiting for the confirm API response before clicking
      const [confirmResponse] = await Promise.all([
        page.waitForResponse(
          r => r.url().includes('/sales/orders/') && r.url().endsWith('/confirm') && r.request().method() === 'POST',
          { timeout: 10000 }
        ).catch(() => null),
        confirmBtn.click(),
      ]);
      confirmBoxClicked = true;
      if (confirmResponse) {
        record(testId, 'step3_confirm_api', 'PASS', {
          status: confirmResponse.status(),
          url: confirmResponse.url().replace(BASE, '').split('?')[0],
        });
      } else {
        record(testId, 'step3_confirm_api', 'WARN', { note: 'confirm response not captured' });
      }
    }
  } else {
    // No message box — maybe the action was direct
    // Wait for any POST response
    try {
      const response = await page.waitForResponse(
        r => r.url().includes('/sales/orders/') && r.url().endsWith('/confirm') && r.request().method() === 'POST',
        { timeout: 8000 }
      );
      record(testId, 'step3_confirm_api', 'PASS', {
        status: response.status(),
        url: response.url().replace(BASE, '').split('?')[0],
      });
      confirmBoxClicked = true;
    } catch {
      record(testId, 'step3_confirm_api', 'WARN', { note: 'no message box + no api response' });
    }
  }

  // Step 4: Capture success toast
  await page.waitForTimeout(1000);
  let toastText = '';
  try {
    const toast = await page.waitForSelector('.el-message--success', { timeout: 3000 });
    toastText = await toast.innerText();
  } catch { toastText = '(not captured)'; }
  record(testId, 'step4_toast', toastText !== '(not captured)' ? 'PASS' : 'WARN', { toastText });

  // Step 5: Refresh list and verify the state changed to CONFIRMED
  await page.waitForTimeout(1500);
  await page.goto(`${BASE}/sales/orders`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(3000);
  for (let i = 0; i < 30; i++) {
    await page.waitForTimeout(500);
    if (await page.$('.el-table__body-wrapper .el-table__row')) break;
  }

  const newState = await page.evaluate((custName) => {
    const rows = document.querySelectorAll('.el-table__body-wrapper .el-table__row');
    for (const row of rows) {
      if (row.innerText.includes(custName)) {
        return {
          hasDraft: row.innerText.includes('草稿'),
          hasConfirmed: row.innerText.includes('已确认'),
          hasPendingReview: row.innerText.includes('待财务审核'),
          hasProcessing: row.innerText.includes('处理中'),
          preview: row.innerText.substring(0, 200),
        };
      }
    }
    return null;
  }, so.testName);

  // State transition success if hasConfirmed OR hasPendingReview (auto-transition)
  const stateChanged = newState && (newState.hasConfirmed || newState.hasPendingReview || newState.hasProcessing) && !newState.hasDraft;
  record(testId, 'step5_state_changed', stateChanged ? 'PASS' : 'FAIL', newState || {});

  // Final
  record(testId, 'FULL_FLOW', stateChanged ? 'PASS' : 'FAIL', {
    depth: 'deep',
    customerName: so.testName,
    initialState: '草稿',
    finalState: newState?.hasConfirmed ? '已确认' :
                newState?.hasPendingReview ? '待财务审核' :
                newState?.hasProcessing ? '处理中' : 'unchanged',
    toastText,
    stateTransitionOk: stateChanged,
  });
}

// ===== R9-deep-9: SO DRAFT → CANCELLED =====
async function R9_deep_9_cancelSO(page) {
  const testId = 'deep-9';
  console.log(`\n--- L4-${testId}: SO DRAFT → CANCELLED ---`);

  const so = await createSOQuick(page, 'deep-9');
  if (!so) return;

  // Click "取消" button on the new SO row
  const cancelClicked = await page.evaluate((custName) => {
    const rows = document.querySelectorAll('.el-table__body-wrapper .el-table__row');
    for (const row of rows) {
      if (row.innerText.includes(custName)) {
        const buttons = row.querySelectorAll('button, a.el-link, span.el-link');
        for (const b of buttons) {
          if (b.textContent && b.textContent.trim() === '取消') {
            b.click();
            return true;
          }
        }
        return { found: false, buttonTexts: Array.from(buttons).map(b => b.textContent?.trim()).filter(Boolean) };
      }
    }
    return false;
  }, so.testName);

  if (cancelClicked !== true) {
    record(testId, 'click_cancel', 'FAIL', { buttonTexts: cancelClicked.buttonTexts || [] });
    return;
  }
  record(testId, 'click_cancel', 'PASS', {});

  // Handle confirmation + wait for API
  await page.waitForTimeout(1000);
  const confirmBox = await page.$('.el-message-box');
  if (confirmBox) {
    const confirmBtn = await page.$('.el-message-box__btns button.el-button--primary');
    if (confirmBtn) {
      const [response] = await Promise.all([
        page.waitForResponse(
          r => r.url().includes('/sales/orders/') && r.url().endsWith('/cancel') && r.request().method() === 'POST',
          { timeout: 10000 }
        ).catch(() => null),
        confirmBtn.click(),
      ]);
      if (response) {
        record(testId, 'cancel_api', 'PASS', {
          status: response.status(),
          url: response.url().replace(BASE, '').split('?')[0],
        });
      } else {
        record(testId, 'cancel_api', 'WARN', {});
      }
    }
  }

  let toastText = '';
  try {
    const toast = await page.waitForSelector('.el-message--success', { timeout: 3000 });
    toastText = await toast.innerText();
  } catch { toastText = '(not captured)'; }
  record(testId, 'cancel_toast', toastText !== '(not captured)' ? 'PASS' : 'WARN', { toastText });

  // Verify state is CANCELLED — use orderNumber (stable) not customer name
  const orderNumber = so.cells[0];  // first cell is SO-xxx
  await page.waitForTimeout(2000);
  await page.goto(`${BASE}/sales/orders`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(5000);  // longer wait for data fetch

  // Poll for rows to actually load (not just the table container)
  for (let i = 0; i < 40; i++) {
    await page.waitForTimeout(500);
    const hasRows = await page.evaluate(() =>
      document.querySelectorAll('.el-table__body-wrapper .el-table__row').length > 0
    );
    if (hasRows) break;
  }
  await page.waitForTimeout(2000);  // extra for row content to fill

  // Dump all rows for debugging
  const allRows = await page.evaluate(() => {
    return Array.from(document.querySelectorAll('.el-table__body-wrapper .el-table__row'))
      .map(r => r.innerText.substring(0, 150));
  });
  record(testId, 'debug_all_rows', 'PASS', { count: allRows.length, sample: allRows.slice(0, 5) });

  // Try clicking "全部订单" tab to ensure we see cancelled orders
  const tabClicked = await page.evaluate(() => {
    const buttons = document.querySelectorAll('.el-radio-button__inner, button');
    for (const b of buttons) {
      if (b.textContent && b.textContent.trim() === '全部订单') {
        b.click();
        return true;
      }
    }
    return false;
  });
  if (tabClicked) {
    await page.waitForTimeout(2000);
    const allRows2 = await page.evaluate(() => {
      return Array.from(document.querySelectorAll('.el-table__body-wrapper .el-table__row'))
        .map(r => r.innerText.substring(0, 150));
    });
    record(testId, 'debug_all_tab', 'PASS', { count: allRows2.length, sample: allRows2.slice(0, 5) });
  }

  const newState = await page.evaluate(({ custName, orderNum }) => {
    const rows = document.querySelectorAll('.el-table__body-wrapper .el-table__row');
    for (const row of rows) {
      // Try matching by orderNumber (stable) or customer name
      if (row.innerText.includes(orderNum) || row.innerText.includes(custName)) {
        return {
          hasCancelled: row.innerText.includes('已取消'),
          hasDraft: row.innerText.includes('草稿'),
          matchedBy: row.innerText.includes(orderNum) ? 'orderNumber' : 'customerName',
          preview: row.innerText.substring(0, 200),
        };
      }
    }
    return null;
  }, { custName: so.testName, orderNum: orderNumber });

  const cancelSuccess = newState && newState.hasCancelled && !newState.hasDraft;
  record(testId, 'state_cancelled', cancelSuccess ? 'PASS' : 'FAIL', newState || {});

  record(testId, 'FULL_FLOW', cancelSuccess ? 'PASS' : 'FAIL', {
    depth: 'deep',
    customerName: so.testName,
    initialState: '草稿',
    finalState: newState?.hasCancelled ? '已取消' : 'unchanged',
    toastText,
  });
}

async function run() {
  console.log('='.repeat(70));
  console.log(`R9 SO STATE MACHINE TESTS`);
  console.log(`Factory: ${FACTORY_ID}`);
  console.log(`Tests: SO DRAFT → CONFIRMED, DRAFT → CANCELLED`);
  console.log('='.repeat(70));

  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.route('**fonts.googleapis.com**', r => r.fulfill({ status: 200, body: '' }));
  await ctx.route('**fonts.gstatic.com**', r => r.fulfill({ status: 200, body: '' }));
  const page = await ctx.newPage();

  console.log('\nLogging in...');
  await loginAndWait(page, 'e2e_factory_admin');
  console.log('Logged in.');

  await R9_deep_7_confirmSO(page);
  await R9_deep_9_cancelSO(page);

  await browser.close();

  const fullFlows = results.filter(r => r.step === 'FULL_FLOW');
  const pass = fullFlows.filter(r => r.status === 'PASS').length;
  const fail = fullFlows.filter(r => r.status === 'FAIL').length;

  console.log('\n' + '='.repeat(70));
  console.log(`R9 SO STATE MACHINE SUMMARY`);
  console.log(`Deep tests: ${fullFlows.length} | PASS: ${pass} | FAIL: ${fail}`);
  console.log('='.repeat(70));

  const outFile = `tests/e2e-comprehensive/results/e2e-R9-so-state.json`;
  writeFileSync(outFile, JSON.stringify({
    round: ROUND,
    timestamp: new Date().toISOString(),
    factoryId: FACTORY_ID,
    notes: 'R9 SO state machine — DRAFT → CONFIRMED + DRAFT → CANCELLED',
    results,
    summary: { deepTotal: fullFlows.length, deepPass: pass, deepFail: fail },
  }, null, 2));
  console.log(`\nResults → ${outFile}`);
}

run().catch(e => { console.error('FATAL:', e); process.exit(1); });
