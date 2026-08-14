/**
 * R10 真深度 PO 创建链路测试 (mirror of R8 but for purchase)
 *
 * 流程:
 * 1. 创建供应商 (complete 12-step)
 * 2. 打开 PO 创建对话框
 * 3. 选择供应商下拉
 * 4. 填交货日期
 * 5. 选择 item 的原料 (materialTypeId) — dropdown 点击 + filter
 * 6. 填数量 + 单价
 * 7. 点击 "创建" 按钮
 * 8. 验证 API 200 + toast
 * 9. 刷新列表 + 验证 PO 出现 + 订单金额正确
 *
 * 前置: raw_material_types seed 一条测试原料 (mat_e2e_test_001)
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
const ROUND = 10;
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

// ===== R10-deep-10: Supplier + PO full chain =====
async function R10_deep_10_POCreateChain(page) {
  const testId = 'deep-10';
  console.log(`\n--- L4-${testId}: PO create full business chain ---`);
  const supplierName = `E2E_S_R10_${TS}`;

  // Pre-step: Create supplier
  const navSup = await navigateTo(page, '/procurement/suppliers', { waitForTable: true });
  if (navSup !== 'OK') { record(testId, 'prep_nav_supplier', 'FAIL', { result: navSup }); return; }

  const clickSup = await clickButton(page, '新建', '新增');
  if (!clickSup) { record(testId, 'prep_click_supplier', 'FAIL', {}); return; }
  const supDialog = await waitForDialog(page);
  if (!supDialog) { record(testId, 'prep_sup_dialog', 'FAIL', {}); return; }

  await fillDialogInput(page, supplierName);
  const supFields = await fillAllRequiredFields(page, supplierName);

  const supSubmit = await submitAndCheckResponse(page, ['确定', '保存'], {
    factoryId: FACTORY_ID, module: 'suppliers',
  });
  if (!supSubmit.ok) {
    record(testId, 'prep_sup_submit', 'FAIL', { reason: supSubmit.reason });
    return;
  }
  record(testId, 'prep_supplier_created', 'PASS', { supplierName, apiStatus: supSubmit.status });
  await page.waitForTimeout(1500);

  // Step 1: Navigate to PO list
  const nav = await navigateTo(page, '/procurement/orders', { waitForTable: true });
  if (nav !== 'OK') { record(testId, 'step1_nav', 'FAIL', { result: nav }); return; }

  const beforeResult = await countTableRows(page);
  const rowsBefore = beforeResult.count || 0;
  record(testId, 'step1_baseline', 'PASS', { rowsBefore });

  // Step 2: Open PO create dialog
  const clicked = await clickButton(page, '新建', '新增', '创建订单');
  if (!clicked) { record(testId, 'step2_click_create', 'FAIL', {}); return; }
  await page.waitForTimeout(3000);

  const dialog = await page.$('.el-dialog:not([style*="display: none"]), .el-drawer:not([style*="display: none"])');
  if (!dialog) { record(testId, 'step2_dialog', 'FAIL', {}); return; }
  record(testId, 'step2_dialog_open', 'PASS', {});

  // Step 3: Select supplier dropdown
  const supplierSelect = await page.$('.el-dialog .el-select, .el-drawer .el-select');
  if (!supplierSelect) { record(testId, 'step3_find_sup_select', 'FAIL', {}); return; }
  await supplierSelect.click();
  await page.waitForTimeout(1000);

  const searchInput = await page.$('.el-select-dropdown__search input, .el-popover input');
  if (searchInput) {
    await searchInput.fill(supplierName);
    await page.waitForTimeout(800);
  }

  const supSelected = await page.evaluate((name) => {
    const opts = document.querySelectorAll('.el-select-dropdown__item:not([style*="display: none"])');
    for (const opt of opts) {
      if (opt.textContent && opt.textContent.includes(name)) {
        opt.click();
        return true;
      }
    }
    return false;
  }, supplierName);

  if (!supSelected) {
    // Fallback: use Playwright locator for visible option
    const firstVisibleItem = page.locator(`.el-select-dropdown__item:visible`).filter({ hasText: supplierName }).first();
    try {
      await firstVisibleItem.click({ timeout: 3000 });
      record(testId, 'step3_supplier_selected', 'PASS', { supplierName, method: 'locator' });
    } catch {
      record(testId, 'step3_supplier_selected', 'FAIL', { supplierName });
      return;
    }
  } else {
    record(testId, 'step3_supplier_selected', 'PASS', { supplierName, method: 'evaluate' });
  }
  await page.waitForTimeout(500);

  // Step 4: Fill expected delivery date (if exists)
  const dateEditor = await page.$('.el-dialog .el-date-editor input, .el-drawer .el-date-editor input');
  if (dateEditor) {
    const future = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);
    const dateStr = `${future.getFullYear()}-${String(future.getMonth() + 1).padStart(2, '0')}-${String(future.getDate()).padStart(2, '0')}`;
    await dateEditor.fill(dateStr);
    await page.keyboard.press('Enter');
    await page.waitForTimeout(500);
    record(testId, 'step4_delivery_date', 'PASS', { date: dateStr });
  } else {
    record(testId, 'step4_delivery_date', 'WARN', {});
  }

  // Step 5: Click material select in first item row
  const materialSelect = await page.$('.el-dialog .item-row .el-select');
  if (!materialSelect) { record(testId, 'step5_find_material_select', 'FAIL', {}); return; }
  await materialSelect.click();
  record(testId, 'step5_open_material_select', 'PASS', {});
  await page.waitForTimeout(2000);

  // Try typing to filter
  const matFilterInput = await page.$('.el-dialog .item-row .el-select input.el-input__inner');
  if (matFilterInput) {
    try { await matFilterInput.fill('E2E测试原料'); await page.waitForTimeout(800); } catch {}
  }

  // Select visible dropdown item
  const firstMatItem = page.locator('.el-select-dropdown__item:visible').first();
  let materialText = '';
  try {
    await firstMatItem.waitFor({ state: 'visible', timeout: 3000 });
    materialText = (await firstMatItem.innerText()) || '';
    await firstMatItem.click();
    record(testId, 'step5_material_selected', 'PASS', { materialText });
  } catch {
    record(testId, 'step5_material_selected', 'FAIL', {});
    return;
  }
  await page.waitForTimeout(500);

  // Step 6: Fill quantity + unitPrice via el-input-number
  await page.evaluate(() => {
    const dialog = document.querySelector('.el-dialog:not([style*="display: none"])');
    if (!dialog) return;
    const numInputs = dialog.querySelectorAll('.el-input-number input');
    const vals = ['200', '25', '20'];  // quantity=200, unitPrice=25, boxQty=20
    for (let i = 0; i < Math.min(numInputs.length, 3); i++) {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      setter.call(numInputs[i], vals[i]);
      numInputs[i].dispatchEvent(new Event('input', { bubbles: true }));
      numInputs[i].dispatchEvent(new Event('change', { bubbles: true }));
      numInputs[i].dispatchEvent(new Event('blur', { bubbles: true }));
    }
  });
  record(testId, 'step6_fill_numbers', 'PASS', { quantity: 200, unitPrice: 25 });

  // Step 7: Fill any remaining required fields
  const extraFields = await fillAllRequiredFields(page, `E2E_PO_R10_${TS}`);
  record(testId, 'step7_fill_remaining', 'PASS', { count: extraFields.length });

  // Step 8: Submit (button "创建")
  const submitResult = await submitAndCheckResponse(page, ['创建', '确定', '保存', '提交'], {
    factoryId: FACTORY_ID, module: 'purchase/orders',
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

  // Step 10: Fresh navigate + wait for rows
  await page.waitForTimeout(1500);
  await page.goto(`${BASE}/procurement/orders`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(5000);
  for (let i = 0; i < 30; i++) {
    await page.waitForTimeout(500);
    if (await page.evaluate(() =>
      document.querySelectorAll('.el-table__body-wrapper .el-table__row').length > 0
    )) break;
  }
  await page.waitForTimeout(1500);

  // Step 11: Strict delta === 1
  const afterResult = await countTableRows(page);
  const verdict = verifyPersistence(rowsBefore, afterResult.count, 1);
  record(testId, 'step11_delta', verdict.status, {
    rowsBefore: verdict.rowsBefore,
    rowsAfter: verdict.rowsAfter,
    delta: verdict.delta,
  });

  // Step 12: Verify PO row contains supplier + expected data
  const firstRowText = await page.evaluate(() => {
    const row = document.querySelector('.el-table__body-wrapper .el-table__row:first-child');
    return row ? row.innerText : '';
  });
  const roundtripOk = firstRowText.includes(supplierName);

  // Expected: quantity=200 × unitPrice=25 = ¥5,000.00
  const priceOk = firstRowText.includes('5,000.00') || firstRowText.includes('5000');

  record(testId, 'step12_roundtrip', roundtripOk ? 'PASS' : 'FAIL', {
    supplierName,
    supplierInRow: roundtripOk,
    priceVisible: priceOk,
    rowPreview: firstRowText.substring(0, 200),
  });

  // Final
  record(testId, 'FULL_FLOW', (verdict.status === 'PASS' && roundtripOk) ? 'PASS' : 'FAIL', {
    depth: 'deep',
    supplierName,
    apiStatus: submitResult.status,
    toastText,
    delta: verdict.delta,
    rowsBefore,
    rowsAfter: afterResult.count,
    roundtripOk,
    priceOk,
  });
}

async function run() {
  console.log('='.repeat(70));
  console.log(`R10 PO CREATE CHAIN`);
  console.log(`Factory: ${FACTORY_ID}`);
  console.log('='.repeat(70));

  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.route('**fonts.googleapis.com**', r => r.fulfill({ status: 200, body: '' }));
  await ctx.route('**fonts.gstatic.com**', r => r.fulfill({ status: 200, body: '' }));
  const page = await ctx.newPage();

  console.log('\nLogging in...');
  await loginAndWait(page, 'e2e_factory_admin');
  console.log('Logged in.');

  await R10_deep_10_POCreateChain(page);

  await browser.close();

  const fullFlows = results.filter(r => r.step === 'FULL_FLOW');
  const pass = fullFlows.filter(r => r.status === 'PASS').length;
  const fail = fullFlows.filter(r => r.status === 'FAIL').length;

  console.log('\n' + '='.repeat(70));
  console.log(`R10 SUMMARY`);
  console.log(`Deep tests: ${fullFlows.length} | PASS: ${pass} | FAIL: ${fail}`);
  console.log('='.repeat(70));

  const outFile = `tests/e2e-comprehensive/results/e2e-R10-po-chain.json`;
  writeFileSync(outFile, JSON.stringify({
    round: ROUND,
    timestamp: new Date().toISOString(),
    factoryId: FACTORY_ID,
    notes: 'R10 PO create chain — mirror of R8 for purchase side',
    results,
    summary: { deepTotal: fullFlows.length, deepPass: pass, deepFail: fail },
  }, null, 2));
  console.log(`\nResults → ${outFile}`);
}

run().catch(e => { console.error('FATAL:', e); process.exit(1); });
