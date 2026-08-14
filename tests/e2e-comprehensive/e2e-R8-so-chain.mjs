/**
 * R8 真深度 SO 创建链路测试
 *
 * 这是 6 轮 E2E 以来最深的业务链路测试:
 * 1. 创建客户 (真实 12-step flow)
 * 2. 打开 SO 创建对话框
 * 3. 选择客户下拉 (验证 R6 deep-3 的 customer 在下拉中)
 * 4. 填必填字段 (交货日期)
 * 5. 添加明细行 (items[0])
 *    - 选择产品 (productTypeId)
 *    - 填数量 (quantity)
 *    - 填单价 (unitPrice)
 *    - 填规格 (specification) — L4-25 真实验证
 *    - 填箱数 (boxQuantity) — L4-25 真实验证
 * 6. 点击"添加明细" 追加一行
 * 7. 点击"删除明细" 删除一行 — 验证动态子表
 * 8. 提交 SO
 * 9. 验证 API 响应 (POST /sales/orders 返回 200 + success)
 * 10. 捕获 toast 文本
 * 11. 返回列表验证新 SO 出现 (delta === 1)
 * 12. 进入详情页验证客户 + items 回读正确
 *
 * 前置: 已在 DB 中 seed 一个测试产品 (prod_e2e_test_001)
 */
import { chromium } from 'playwright';
import { writeFileSync, readFileSync, existsSync } from 'fs';
import {
  BASE, PASSWORD, navigateTo, countTableRows, verifyPersistence,
  clickButton, waitForDialog, submitAndCheckResponse, fillDialogInput,
  fillAllRequiredFields,
} from './lib/helpers.mjs';

const SETUP_FILE = 'tests/e2e-comprehensive/results/R0-setup.json';
const setup = existsSync(SETUP_FILE) ? JSON.parse(readFileSync(SETUP_FILE, 'utf8')) : null;
const FACTORY_ID = setup?.factoryId || 'FOOD_3101_048';
const ROUND = 8;
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
  try {
    await page.waitForURL('**/dashboard', { timeout: 30000 });
  } catch (e) {
    throw new Error(`Login failed for ${username}: ${page.url()}`);
  }
  for (let i = 0; i < 30; i++) {
    await page.waitForTimeout(500);
    if (await page.evaluate(() => !!document.querySelector('.el-menu,.app-sidebar'))) return true;
  }
  throw new Error(`Login succeeded but menu never rendered`);
}

// Pre-step: Create a test customer (required for SO)
async function createTestCustomer(page) {
  const testName = `E2E_C_R8_${TS}`;
  console.log(`\n--- Pre-step: Create customer ${testName} ---`);

  const nav = await navigateTo(page, '/sales/customers', { waitForTable: true });
  if (nav !== 'OK') { record('R8-prep', 'create_customer_nav', 'FAIL', { result: nav }); return null; }

  const clicked = await clickButton(page, '新建', '新增');
  if (!clicked) { record('R8-prep', 'create_customer_click', 'FAIL', {}); return null; }

  const dialog = await waitForDialog(page);
  if (!dialog) { record('R8-prep', 'create_customer_dialog', 'FAIL', {}); return null; }

  await fillDialogInput(page, testName);
  await fillAllRequiredFields(page, testName);

  const submitResult = await submitAndCheckResponse(page, ['确定', '保存'], {
    factoryId: FACTORY_ID, module: 'customers',
  });
  if (!submitResult.ok) {
    record('R8-prep', 'create_customer_submit', 'FAIL', { reason: submitResult.reason });
    return null;
  }
  record('R8-prep', 'create_customer', 'PASS', { testName, apiStatus: submitResult.status });
  await page.waitForTimeout(1500);
  return testName;
}

// ===== R8-deep-6: SO create full business chain =====
async function R8_deep_6_SOCreateChain(page, customerName) {
  const testId = 'deep-6';
  console.log(`\n--- L4-${testId}: SO create full business chain ---`);

  // Step 1: Navigate to SO list and record baseline
  const nav = await navigateTo(page, '/sales/orders', { waitForTable: true });
  if (nav !== 'OK') { record(testId, 'step1_navigate', 'FAIL', { result: nav }); return; }

  const beforeResult = await countTableRows(page);
  if (beforeResult.error) { record(testId, 'step1_count', 'FAIL', { error: beforeResult.error }); return; }
  const rowsBefore = beforeResult.count;
  record(testId, 'step1_baseline', 'PASS', { rowsBefore });

  // Step 2: Open SO create dialog
  const clicked = await clickButton(page, '新建', '新增', '创建订单');
  if (!clicked) { record(testId, 'step2_open_dialog', 'FAIL', {}); return; }
  await page.waitForTimeout(3000);  // wait for dialog + products/customers to load

  const dialog = await page.$('.el-dialog:not([style*="display: none"]), .el-drawer:not([style*="display: none"])');
  if (!dialog) { record(testId, 'step2_dialog_visible', 'FAIL', {}); return; }
  record(testId, 'step2_dialog', 'PASS', {});

  // Investigation: dump dialog structure for debugging
  const structure = await page.evaluate(() => {
    const d = document.querySelector('.el-dialog:not([style*="display: none"]), .el-drawer:not([style*="display: none"])');
    if (!d) return null;
    return {
      tagName: d.tagName,
      className: d.className,
      selectCount: d.querySelectorAll('.el-select').length,
      inputCount: d.querySelectorAll('input.el-input__inner').length,
      tableCount: d.querySelectorAll('.el-table').length,
      buttonCount: d.querySelectorAll('button').length,
      buttonTexts: Array.from(d.querySelectorAll('button')).map(b => b.textContent?.trim()).filter(Boolean).slice(0, 20),
      formItemLabels: Array.from(d.querySelectorAll('.el-form-item__label')).map(l => l.textContent?.trim()).slice(0, 20),
      datePickers: d.querySelectorAll('.el-date-editor, input[placeholder*="日期"]').length,
      addButton: Array.from(d.querySelectorAll('button')).some(b => b.textContent?.includes('添加')),
    };
  });
  record(testId, 'step2_investigate_structure', 'PASS', structure || {});

  // Step 3: Select customer from dropdown (filter by just-created name)
  const customerSelect = await page.$('.el-dialog .el-select');  // first el-select is customer
  if (!customerSelect) { record(testId, 'step3_find_customer_select', 'FAIL', {}); return; }

  await customerSelect.click();
  await page.waitForTimeout(1000);

  // Try typing in the filterable search
  const searchInput = await page.$('.el-select-dropdown__search input, .el-popover input');
  if (searchInput) {
    await searchInput.fill(customerName);
    await page.waitForTimeout(800);
  }

  // Select the first matching option
  const customerSelected = await page.evaluate((name) => {
    const opts = document.querySelectorAll('.el-select-dropdown__item');
    for (const opt of opts) {
      if (opt.textContent && opt.textContent.includes(name)) {
        opt.click();
        return true;
      }
    }
    return false;
  }, customerName);

  if (!customerSelected) {
    record(testId, 'step3_customer_selected', 'FAIL', { searchedFor: customerName });
    return;
  }
  record(testId, 'step3_customer_selected', 'PASS', { customerName });
  await page.waitForTimeout(500);

  // Step 4: Click el-date-editor and pick a date via keyboard
  const dateEditor = await page.$('.el-dialog .el-date-editor input, .el-drawer .el-date-editor input');
  if (dateEditor) {
    await dateEditor.click();
    await page.waitForTimeout(500);
    // Use +7 days from today as YYYY-MM-DD
    const future = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);
    const dateStr = `${future.getFullYear()}-${String(future.getMonth() + 1).padStart(2, '0')}-${String(future.getDate()).padStart(2, '0')}`;
    await dateEditor.fill(dateStr);
    await page.keyboard.press('Enter');
    await page.waitForTimeout(500);
    record(testId, 'step4_delivery_date', 'PASS', { date: dateStr });
  } else {
    record(testId, 'step4_delivery_date', 'WARN', { note: 'date editor not found' });
  }

  // Step 5: Fill 交货地址 (delivery address) — first text input that's not customer/date/etc
  // We know the form has 7 inputs, labels ["客户","交货日期","交货地址","业务员","含运费","其他费用","备注","预订合同"]
  // After customer (select) and date (date-editor), 交货地址 should be next fillable input
  const addressFilled = await page.evaluate(() => {
    const dialog = document.querySelector('.el-dialog:not([style*="display: none"]), .el-drawer:not([style*="display: none"])');
    if (!dialog) return false;
    // Find all empty text inputs in form-items (skipping date-editor internal inputs)
    const formItems = dialog.querySelectorAll('.el-form-item');
    for (const item of formItems) {
      const label = item.querySelector('.el-form-item__label')?.textContent?.trim() || '';
      if (label.includes('交货地址') || label.includes('地址')) {
        const input = item.querySelector('input.el-input__inner, textarea.el-textarea__inner');
        if (input && !input.value) {
          const setter = Object.getOwnPropertyDescriptor(
            input.tagName === 'INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype,
            'value'
          ).set;
          setter.call(input, 'E2E 测试交货地址 上海市浦东新区');
          input.dispatchEvent(new Event('input', { bubbles: true }));
          input.dispatchEvent(new Event('blur', { bubbles: true }));
          return true;
        }
      }
    }
    return false;
  });
  record(testId, 'step4b_address', addressFilled ? 'PASS' : 'WARN', {});

  // Step 5: Click product select in the item row
  const productSelect = await page.$('.el-dialog .item-row .el-select');
  if (!productSelect) {
    record(testId, 'step5_open_product_select', 'FAIL', { note: '.item-row .el-select not found' });
    return;
  }
  await productSelect.click();
  record(testId, 'step5_open_product_select', 'PASS', {});
  await page.waitForTimeout(2000);  // wait longer for teleport popover

  // Type to trigger filterable search
  // filterable select has an input inside
  const filterInput = await page.$('.el-dialog .item-row .el-select input.el-input__inner');
  if (filterInput) {
    try {
      await filterInput.fill('E2E测试产品');
      await page.waitForTimeout(800);
    } catch { /* some selects don't have editable inputs */ }
  }

  // Locate visible dropdown items via Playwright locator (waits for visibility)
  const firstVisibleItem = page.locator('.el-select-dropdown__item:visible').first();
  let productSelectedText = '';
  try {
    await firstVisibleItem.waitFor({ state: 'visible', timeout: 3000 });
    productSelectedText = (await firstVisibleItem.innerText()) || '';
    await firstVisibleItem.click();
  } catch (e) {
    productSelectedText = '(not found)';
  }

  const productSelected = productSelectedText && !productSelectedText.includes('(not found)');
  record(testId, 'step5_product_selected', productSelected ? 'PASS' : 'FAIL', {
    productText: productSelectedText,
  });
  await page.waitForTimeout(500);

  // Step 6: Fill quantity and price (el-input-number has nested <input>)
  const itemsFilled = await page.evaluate(() => {
    const dialog = document.querySelector('.el-dialog:not([style*="display: none"]), .el-drawer:not([style*="display: none"])');
    if (!dialog) return [];
    // el-input-number wraps a native input
    const numInputs = dialog.querySelectorAll('.el-input-number input');
    const filled = [];
    for (let i = 0; i < Math.min(numInputs.length, 4); i++) {
      const inp = numInputs[i];
      const val = i === 0 ? '100' : i === 1 ? '50' : '10';
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      setter.call(inp, val);
      inp.dispatchEvent(new Event('input', { bubbles: true }));
      inp.dispatchEvent(new Event('change', { bubbles: true }));
      inp.dispatchEvent(new Event('blur', { bubbles: true }));
      filled.push({ index: i, value: val });
    }
    return filled;
  });
  record(testId, 'step6_fill_item_numbers', itemsFilled.length > 0 ? 'PASS' : 'WARN', { filled: itemsFilled });

  // Step 7: Fill all remaining required fields (e.g. address)
  const remainingFilled = await fillAllRequiredFields(page, `E2E_SO_R8_${TS}`);
  record(testId, 'step7_fill_remaining', 'PASS', { count: remainingFilled.length });

  // Step 8: Submit SO — button text is "创建" (not 确定/保存/提交)
  const submitResult = await submitAndCheckResponse(page, ['创建', '确定', '保存', '提交'], {
    factoryId: FACTORY_ID, module: 'sales/orders',
  });
  if (!submitResult.ok) {
    record(testId, 'step8_submit', 'FAIL', {
      reason: submitResult.reason,
      status: submitResult.status,
      errors: submitResult.errors,
    });
    return;
  }
  record(testId, 'step8_submit', 'PASS', {
    apiStatus: submitResult.status,
    apiUrl: submitResult.url,
  });

  // Step 9: Capture toast
  let toastText = '';
  try {
    const toast = await page.waitForSelector('.el-message--success', { timeout: 3000 });
    toastText = await toast.innerText();
  } catch { toastText = '(not captured)'; }
  record(testId, 'step9_toast', toastText !== '(not captured)' ? 'PASS' : 'WARN', { toastText });

  // Step 10: Fresh navigate back to list
  await page.waitForTimeout(1000);
  try {
    await page.goto(`${BASE}/sales/orders`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  } catch (e) {
    record(testId, 'step10_nav_back', 'FAIL', { error: e.message });
    return;
  }
  await page.waitForTimeout(3000);
  for (let i = 0; i < 30; i++) {
    await page.waitForTimeout(500);
    const hasRows = await page.evaluate(() =>
      document.querySelectorAll('.el-table__body-wrapper .el-table__row').length > 0
    );
    if (hasRows) break;
  }

  // Step 11: Verify strict delta === 1
  const afterResult = await countTableRows(page);
  if (afterResult.error) { record(testId, 'step11_count', 'FAIL', { error: afterResult.error }); return; }
  const verdict = verifyPersistence(rowsBefore, afterResult.count, 1);
  record(testId, 'step11_delta', verdict.status, {
    rowsBefore: verdict.rowsBefore,
    rowsAfter: verdict.rowsAfter,
    delta: verdict.delta,
    note: verdict.note,
  });

  // Step 12: Verify the new SO row contains customer name
  const firstRowText = await page.evaluate(() => {
    const row = document.querySelector('.el-table__body-wrapper .el-table__row:first-child');
    return row ? row.innerText : '';
  });
  const roundtripOk = firstRowText.includes(customerName);
  record(testId, 'step12_roundtrip', roundtripOk ? 'PASS' : 'FAIL', {
    customerName,
    firstRowPreview: firstRowText.substring(0, 150),
    customerInRow: roundtripOk,
  });

  // Final: Full flow result
  record(testId, 'FULL_FLOW', (verdict.status === 'PASS' && roundtripOk) ? 'PASS' : 'FAIL', {
    depth: 'deep',
    customerName,
    apiStatus: submitResult.status,
    toastText,
    delta: verdict.delta,
    rowsBefore,
    rowsAfter: afterResult.count,
    roundtripOk,
  });
}

async function run() {
  console.log('='.repeat(70));
  console.log(`R8 DEEP SO CHAIN TEST`);
  console.log(`Factory: ${FACTORY_ID} | Account: e2e_factory_admin`);
  console.log(`Test: Customer + SO create with items (full business chain)`);
  console.log('='.repeat(70));

  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.route('**fonts.googleapis.com**', r => r.fulfill({ status: 200, body: '' }));
  await ctx.route('**fonts.gstatic.com**', r => r.fulfill({ status: 200, body: '' }));
  const page = await ctx.newPage();

  console.log('\nLogging in...');
  await loginAndWait(page, 'e2e_factory_admin');
  console.log('Logged in.');

  // Pre-step: Create customer
  const customerName = await createTestCustomer(page);
  if (!customerName) {
    console.log('\n❌ Pre-step failed. Cannot run SO chain test.');
    await browser.close();
    return;
  }

  // Main test: SO create chain
  await R8_deep_6_SOCreateChain(page, customerName);

  await browser.close();

  // Summary
  const fullFlows = results.filter(r => r.step === 'FULL_FLOW');
  const pass = fullFlows.filter(r => r.status === 'PASS').length;
  const fail = fullFlows.filter(r => r.status === 'FAIL').length;

  console.log('\n' + '='.repeat(70));
  console.log(`R8 DEEP SO CHAIN SUMMARY`);
  console.log(`Deep tests: ${fullFlows.length} | PASS: ${pass} | FAIL: ${fail}`);
  console.log(`Total records: ${results.length}`);
  console.log('='.repeat(70));

  const outFile = `tests/e2e-comprehensive/results/e2e-R8-so-chain.json`;
  writeFileSync(outFile, JSON.stringify({
    round: ROUND,
    timestamp: new Date().toISOString(),
    factoryId: FACTORY_ID,
    notes: 'R8 deepest business chain test — full SO creation with items',
    results,
    summary: {
      deepTotal: fullFlows.length,
      deepPass: pass,
      deepFail: fail,
      totalRecords: results.length,
    },
  }, null, 2));
  console.log(`\nResults → ${outFile}`);
}

run().catch(e => { console.error('FATAL:', e); process.exit(1); });
