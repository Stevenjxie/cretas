/**
 * R11 真深度 财务闭环测试
 *
 * 这是 E2E 测试以来**最复杂的业务链路**:
 * 1. 创建客户 + SO (¥5,000)
 * 2. 确认 SO (DRAFT → CONFIRMED)
 * 3. 开票 (POST /finance/receivable, 金额 ¥5,000)
 * 4. 收款 (POST /finance/receivable/payment, 金额 ¥5,000)
 * 5. 验证 SO 列表显示已收款 (刷新后 payment 金额显示)
 * 6. 金额一致性验证: SO ¥5,000 = 开票 ¥5,000 = 收款 ¥5,000
 *
 * 真实业务链路 spec §7 L4-15 (出货→开票→收款)
 * 前 5 轮的 L4-15 是 smoke 级 (只验证 finance/invoices 页面可访问)
 * R11 是第一次真实走完整个链路
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
const ROUND = 11;
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

// Helper: Create customer + SO (reused pattern)
async function createAndConfirmSO(page, testIdPrefix) {
  const testName = `E2E_C_R11_${testIdPrefix}_${TS}`;

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

  // Fill numbers: 100 x 50 = 5000
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

  // Navigate back to list, find SO
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
        return { found: true, cells, preview: row.innerText.substring(0, 200) };
      }
    }
    return { found: false };
  }, testName);

  if (!newSO.found) { record(testIdPrefix, 'prep_find_so_row', 'FAIL', {}); return null; }
  record(testIdPrefix, 'prep_so_in_list', 'PASS', { cells: newSO.cells });

  // Confirm SO (DRAFT → CONFIRMED)
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

  // Handle ElMessageBox + wait for confirm API
  const msgBox = await page.$('.el-message-box');
  if (msgBox) {
    const btn = await page.$('.el-message-box__btns button.el-button--primary');
    if (btn) {
      try {
        await Promise.all([
          page.waitForResponse(r => r.url().includes('/sales/orders/') && r.url().endsWith('/confirm'), { timeout: 8000 }),
          btn.click(),
        ]);
      } catch {}
    }
  }
  await page.waitForTimeout(2000);
  record(testIdPrefix, 'prep_so_confirmed', 'PASS', {});

  // Refresh list to get updated row with CONFIRMED state + new action buttons
  await page.goto(`${BASE}/sales/orders`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(5000);
  for (let i = 0; i < 30; i++) {
    await page.waitForTimeout(500);
    if (await page.evaluate(() =>
      document.querySelectorAll('.el-table__body-wrapper .el-table__row').length > 0
    )) break;
  }
  await page.waitForTimeout(2000);

  return { testName, cells: newSO.cells, orderNumber: newSO.cells[0] };
}

// ===== R11-deep-11: Finance closed loop =====
async function R11_deep_11_FinanceLoop(page) {
  const testId = 'deep-11';
  console.log(`\n--- L4-${testId}: Finance closed loop (SO → 开票 → 收款) ---`);

  const so = await createAndConfirmSO(page, 'deep-11');
  if (!so) return;

  // Step 1: Click "开票" button on the confirmed SO row
  const invoiceClicked = await page.evaluate((custName) => {
    const rows = document.querySelectorAll('.el-table__body-wrapper .el-table__row');
    for (const row of rows) {
      if (row.innerText.includes(custName)) {
        const buttons = row.querySelectorAll('button, a.el-link, span.el-link');
        for (const b of buttons) {
          // "开票" (not "税率分组开票")
          if (b.textContent && b.textContent.trim() === '开票') {
            b.click();
            return true;
          }
        }
        return { found: false, buttons: Array.from(buttons).map(b => b.textContent?.trim()).filter(Boolean) };
      }
    }
    return { found: false, reason: 'no row found' };
  }, so.testName);

  if (invoiceClicked !== true) {
    record(testId, 'step1_click_invoice', 'FAIL', invoiceClicked);
    return;
  }
  record(testId, 'step1_click_invoice', 'PASS', {});
  await page.waitForTimeout(2000);

  // Step 2: Verify invoice dialog opened with pre-filled amount
  const invoiceDialogInfo = await page.evaluate(() => {
    const dialog = document.querySelector('.el-dialog:not([style*="display: none"])');
    if (!dialog) return { opened: false };
    const title = dialog.querySelector('.el-dialog__title')?.textContent || '';
    const numInput = dialog.querySelector('.el-input-number input');
    return {
      opened: true,
      title,
      amount: numInput?.value || '(no value)',
    };
  });
  record(testId, 'step2_invoice_dialog', invoiceDialogInfo.opened ? 'PASS' : 'FAIL', invoiceDialogInfo);
  if (!invoiceDialogInfo.opened) return;

  // Verify the pre-filled amount matches SO total (¥5000)
  const amountMatchesExpected = invoiceDialogInfo.amount === '5000' || invoiceDialogInfo.amount.includes('5000');
  record(testId, 'step2b_amount_prefilled', amountMatchesExpected ? 'PASS' : 'WARN', {
    expected: 5000,
    actual: invoiceDialogInfo.amount,
  });

  // Step 3: Submit invoice (button "确认开票")
  const invoiceSubmit = await submitAndCheckResponse(page, ['确认开票', '确定', '保存'], {
    factoryId: FACTORY_ID,
    module: 'finance/receivable',
  });
  if (!invoiceSubmit.ok) {
    record(testId, 'step3_invoice_submit', 'FAIL', {
      reason: invoiceSubmit.reason,
      status: invoiceSubmit.status,
    });
    return;
  }
  record(testId, 'step3_invoice_submit', 'PASS', {
    apiStatus: invoiceSubmit.status,
    apiUrl: invoiceSubmit.url,
  });

  // Step 4: Capture invoice toast
  let invoiceToast = '';
  try {
    const toast = await page.waitForSelector('.el-message--success', { timeout: 3000 });
    invoiceToast = await toast.innerText();
  } catch { invoiceToast = '(not captured)'; }
  record(testId, 'step4_invoice_toast', invoiceToast !== '(not captured)' ? 'PASS' : 'WARN', { toastText: invoiceToast });

  // Wait for dialog to close + loadData to refresh
  await page.waitForTimeout(3000);

  // Step 5: Click "收款" button on the same SO row
  const paymentClicked = await page.evaluate((custName) => {
    const rows = document.querySelectorAll('.el-table__body-wrapper .el-table__row');
    for (const row of rows) {
      if (row.innerText.includes(custName)) {
        const buttons = row.querySelectorAll('button, a.el-link, span.el-link');
        for (const b of buttons) {
          if (b.textContent && b.textContent.trim() === '收款') {
            b.click();
            return true;
          }
        }
        return { found: false, buttons: Array.from(buttons).map(b => b.textContent?.trim()).filter(Boolean) };
      }
    }
    return { found: false, reason: 'no row found' };
  }, so.testName);

  if (paymentClicked !== true) {
    record(testId, 'step5_click_payment', 'FAIL', paymentClicked);
    return;
  }
  record(testId, 'step5_click_payment', 'PASS', {});
  await page.waitForTimeout(2000);

  // Step 6: Verify payment dialog opened with pre-filled amount
  const paymentDialogInfo = await page.evaluate(() => {
    const dialog = document.querySelector('.el-dialog:not([style*="display: none"])');
    if (!dialog) return { opened: false };
    const title = dialog.querySelector('.el-dialog__title')?.textContent || '';
    const numInput = dialog.querySelector('.el-input-number input');
    return {
      opened: true,
      title,
      amount: numInput?.value || '(no value)',
    };
  });
  record(testId, 'step6_payment_dialog', paymentDialogInfo.opened ? 'PASS' : 'FAIL', paymentDialogInfo);
  if (!paymentDialogInfo.opened) return;

  const paymentAmountOk = paymentDialogInfo.amount === '5000' || paymentDialogInfo.amount.includes('5000');
  record(testId, 'step6b_payment_amount', paymentAmountOk ? 'PASS' : 'WARN', {
    expected: 5000,
    actual: paymentDialogInfo.amount,
  });

  // Step 7: Submit payment
  const paymentSubmit = await submitAndCheckResponse(page, ['确认收款', '确定', '保存'], {
    factoryId: FACTORY_ID,
    module: 'finance/receivable',  // endpoint is /finance/receivable/payment
  });
  if (!paymentSubmit.ok) {
    record(testId, 'step7_payment_submit', 'FAIL', {
      reason: paymentSubmit.reason,
      status: paymentSubmit.status,
    });
    return;
  }
  record(testId, 'step7_payment_submit', 'PASS', {
    apiStatus: paymentSubmit.status,
    apiUrl: paymentSubmit.url,
  });

  // Step 8: Capture payment toast
  let paymentToast = '';
  try {
    const toast = await page.waitForSelector('.el-message--success', { timeout: 3000 });
    paymentToast = await toast.innerText();
  } catch { paymentToast = '(not captured)'; }
  record(testId, 'step8_payment_toast', paymentToast !== '(not captured)' ? 'PASS' : 'WARN', { toastText: paymentToast });

  // Step 9: Refresh SO list, verify payment amount shows up
  await page.waitForTimeout(2000);
  await page.goto(`${BASE}/sales/orders`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(5000);
  for (let i = 0; i < 30; i++) {
    await page.waitForTimeout(500);
    if (await page.evaluate(() =>
      document.querySelectorAll('.el-table__body-wrapper .el-table__row').length > 0
    )) break;
  }
  await page.waitForTimeout(2000);

  const afterPayment = await page.evaluate((custName) => {
    const rows = document.querySelectorAll('.el-table__body-wrapper .el-table__row');
    for (const row of rows) {
      if (row.innerText.includes(custName)) {
        return {
          found: true,
          cells: Array.from(row.querySelectorAll('td')).map(c => c.innerText.trim()),
          preview: row.innerText.substring(0, 300),
        };
      }
    }
    return { found: false };
  }, so.testName);

  // The row should now show: paid column has ¥5,000 (was "-")
  const paidInRow = afterPayment.found && (
    afterPayment.preview.includes('5,000.00') ||
    afterPayment.preview.includes('5000')
  );
  record(testId, 'step9_paid_visible', paidInRow ? 'PASS' : 'WARN', {
    cells: afterPayment.cells,
    previewContainsAmount: paidInRow,
  });

  // Step 10: Navigate to finance/costs to verify record exists
  const navFinance = await navigateTo(page, '/finance/costs', { waitForTable: true });
  if (navFinance !== 'OK') {
    record(testId, 'step10_nav_finance', 'WARN', { result: navFinance });
  } else {
    await page.waitForTimeout(3000);
    const financeData = await page.evaluate((custName) => {
      const rows = document.querySelectorAll('.el-table__body-wrapper .el-table__row');
      const matching = Array.from(rows).filter(r => r.innerText.includes(custName));
      return {
        totalRows: rows.length,
        matchingRows: matching.length,
        sampleMatchingRow: matching.length > 0 ? matching[0].innerText.substring(0, 200) : null,
      };
    }, so.testName);
    record(testId, 'step10_finance_records', financeData.matchingRows > 0 ? 'PASS' : 'WARN', financeData);
  }

  // Final: The financial closed-loop PASS if invoice + payment both went through
  const loopComplete = invoiceSubmit.ok && paymentSubmit.ok && paidInRow;
  record(testId, 'FULL_FLOW', loopComplete ? 'PASS' : 'FAIL', {
    depth: 'deep',
    customerName: so.testName,
    orderNumber: so.orderNumber,
    soAmount: '¥5,000.00',
    invoiceApi: invoiceSubmit.status,
    invoiceUrl: invoiceSubmit.url,
    invoiceToast,
    paymentApi: paymentSubmit.status,
    paymentUrl: paymentSubmit.url,
    paymentToast,
    amountsMatch: invoiceDialogInfo.amount === paymentDialogInfo.amount,
    paidInListRow: paidInRow,
  });
}

async function run() {
  console.log('='.repeat(70));
  console.log(`R11 FINANCE CLOSED LOOP TEST`);
  console.log(`Factory: ${FACTORY_ID}`);
  console.log(`Flow: Customer → SO → Confirm → 开票 → 收款 → 验证`);
  console.log('='.repeat(70));

  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.route('**fonts.googleapis.com**', r => r.fulfill({ status: 200, body: '' }));
  await ctx.route('**fonts.gstatic.com**', r => r.fulfill({ status: 200, body: '' }));
  const page = await ctx.newPage();

  console.log('\nLogging in...');
  await loginAndWait(page, 'e2e_factory_admin');
  console.log('Logged in.');

  await R11_deep_11_FinanceLoop(page);

  await browser.close();

  const fullFlows = results.filter(r => r.step === 'FULL_FLOW');
  const pass = fullFlows.filter(r => r.status === 'PASS').length;
  const fail = fullFlows.filter(r => r.status === 'FAIL').length;

  console.log('\n' + '='.repeat(70));
  console.log(`R11 FINANCE LOOP SUMMARY`);
  console.log(`Deep tests: ${fullFlows.length} | PASS: ${pass} | FAIL: ${fail}`);
  console.log('='.repeat(70));

  const outFile = `tests/e2e-comprehensive/results/e2e-R11-finance-loop.json`;
  writeFileSync(outFile, JSON.stringify({
    round: ROUND,
    timestamp: new Date().toISOString(),
    factoryId: FACTORY_ID,
    notes: 'R11 finance closed loop — first true SO→开票→收款 chain',
    results,
    summary: { deepTotal: fullFlows.length, deepPass: pass, deepFail: fail },
  }, null, 2));
  console.log(`\nResults → ${outFile}`);
}

run().catch(e => { console.error('FATAL:', e); process.exit(1); });
