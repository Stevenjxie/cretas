/**
 * L2 CRUD Test — Round 2 (improved)
 *
 * Improvements over R1:
 * - API response interception instead of toast polling
 * - Row count persistence verification (before/after)
 * - More modules tested
 *
 * Modules: dashboard, production, warehouse, customers, suppliers,
 *          sales orders, procurement orders, employees, quality
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
const ROUND = 2;
const results = [];
const TS = Date.now().toString(36);

function record(module, action, status, evidence = {}) {
  const r = { module, action, status, evidence, timestamp: new Date().toISOString() };
  results.push(r);
  const icon = status === 'PASS' ? '✓' : status === 'FAIL' ? '✗' : status === 'SKIP' ? '-' : '⚠';
  console.log(`  [${icon}] ${module}/${action}: ${status}`);
  for (const [k, v] of Object.entries(evidence)) {
    if (v !== undefined) console.log(`      ${k}: ${typeof v === 'string' ? v : JSON.stringify(v)}`);
  }
}

async function loginAndInit(page, username) {
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForSelector('input.el-input__inner', { timeout: 30000 });
  await page.fill('input.el-input__inner[placeholder*="用户名"]', username);
  await page.fill('input[type="password"]', PASSWORD);
  await page.click('button.login-button');
  // R3 fix: throw on login failure instead of silently passing
  try {
    await page.waitForURL('**/dashboard', { timeout: 30000 });
  } catch (e) {
    const currentUrl = page.url();
    throw new Error(`Login failed for ${username}: expected /dashboard, got ${currentUrl}`);
  }
  for (let i = 0; i < 30; i++) {
    await page.waitForTimeout(500);
    const hasMenu = await page.evaluate(() => !!document.querySelector('.el-menu,.app-sidebar'));
    if (hasMenu) return true;
  }
  throw new Error(`Login succeeded for ${username} but menu never rendered in 15s`);
}

// ===== CRUD: Create entity in a module =====
// R3 fix: removed zombie parameters entityName/extraFields (P0-5)
async function testCRUD(page, { module, path }) {
  console.log(`\n--- CRUD: ${module} ---`);

  // Navigate
  const nav = await navigateTo(page, path, { waitForTable: true });
  if (nav !== 'OK') {
    record(module, 'navigate', nav === 'TIMEOUT' ? 'FAIL' : nav, { result: nav });
    return;
  }

  // Count rows before — new return type { count, error }
  const beforeResult = await countTableRows(page);
  if (beforeResult.error) {
    record(module, 'list', 'FAIL', { error: beforeResult.error });
    return;
  }
  const rowsBefore = beforeResult.count;
  record(module, 'list', 'PASS', { rows: rowsBefore });

  // Click create button
  const clicked = await clickButton(page, '新建', '新增', '添加', '创建');
  if (!clicked) {
    record(module, 'create_button', 'SKIP', { reason: 'No create button found' });
    return;
  }

  // Wait for dialog
  const dialog = await waitForDialog(page);
  if (!dialog) {
    record(module, 'open_dialog', 'SKIP', { reason: 'No dialog opened — may use separate page' });
    return;
  }

  // Fill name (first input) then all required fields
  const testName = `E2E_${module}_R${ROUND}_${TS}`;
  const filled = await fillDialogInput(page, testName);
  if (!filled) {
    record(module, 'fill_form', 'FAIL', { reason: 'No input found in dialog' });
    return;
  }

  // Fill all other required fields with test data
  const extraFilled = await fillAllRequiredFields(page, testName);
  if (extraFilled.length > 0) {
    record(module, 'fill_required_fields', 'PASS', {
      count: extraFilled.length,
      fields: extraFilled.map(f => `${f.label}=${f.value}`),
    });
  }

  // Submit and check API response
  const submitResult = await submitAndCheckResponse(page);
  if (submitResult.ok) {
    record(module, 'create', 'PASS', {
      filled: testName,
      apiStatus: submitResult.status,
      apiUrl: submitResult.url || submitResult.reason,
    });
  } else {
    record(module, 'create', submitResult.reason === 'no_submit_button' ? 'SKIP' : 'WARNING', {
      filled: testName,
      reason: submitResult.reason,
      apiStatus: submitResult.status,
      errors: submitResult.errors,
    });
    // Don't return — still try persistence check
  }

  // Verify persistence: reload current page and count rows
  await page.waitForTimeout(2000);
  // Close any open dialog first
  await page.keyboard.press('Escape');
  await page.waitForTimeout(1000);
  // Navigate fresh to the list page (more reliable than reload)
  try {
    await page.goto(`${BASE}${path}`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  } catch (e) {
    record(module, 'persistence', 'FAIL', { reason: `goto failed: ${(e.message || '').substring(0, 80)}` });
    return;
  }
  // Wait for table to render (up to 25s)
  for (let i = 0; i < 50; i++) {
    await page.waitForTimeout(500);
    if (await page.evaluate(() => !!document.querySelector('.el-table__body-wrapper')).catch(() => false)) break;
  }
  // Extra wait for row data to load
  await page.waitForTimeout(2000);
  const afterResult = await countTableRows(page);
  if (afterResult.error) {
    record(module, 'persistence', 'FAIL', { error: afterResult.error });
    return;
  }
  // R3 fix: strict delta === 1 check (was: rowsAfter > rowsBefore, which passed delta=6)
  const verdict = verifyPersistence(rowsBefore, afterResult.count, 1);
  record(module, 'persistence', verdict.status, {
    rowsBefore: verdict.rowsBefore,
    rowsAfter: verdict.rowsAfter,
    delta: verdict.delta,
    note: verdict.note,
    submitResultOk: submitResult.ok,
  });
}

// ===== Simple list-only test (no create) =====
// R3 note: spec classifies these as L1-adjacent (page accessibility), not L2 CRUD
async function testListOnly(page, module, path) {
  console.log(`\n--- LIST: ${module} ---`);
  // Use SPA navigation (faster + avoids page.goto timeout after heavy DOM state)
  try {
    await page.evaluate((p) => {
      const router = window.__vue_app__?.config?.globalProperties?.$router;
      if (router) router.push(p);
      else window.location.href = p;
    }, path);
    await page.waitForTimeout(3000);
    // Check if we landed on the right page
    const url = page.url();
    if (url.includes('/403')) { record(module, 'navigate', '403', {}); return; }
    if (url.includes('/login')) { record(module, 'navigate', 'LOGIN', {}); return; }
    if (url.includes('/404')) { record(module, 'navigate', '404', {}); return; }
    // Wait for table
    for (let i = 0; i < 20; i++) {
      await page.waitForTimeout(500);
      if (await page.evaluate(() => !!document.querySelector('.el-table')).catch(() => false)) break;
    }
  } catch (e) {
    record(module, 'navigate', 'FAIL', { result: 'ERROR: ' + (e.message || '').substring(0, 60) });
    return;
  }
  const result = await countTableRows(page);
  if (result.error) {
    record(module, 'list', 'FAIL', { error: result.error });
    return;
  }
  record(module, 'list', 'PASS', {
    rows: result.count,
    note: result.count === 0 ? 'Empty (new factory)' : '',
    testType: 'page-accessibility',  // R3 fix: honestly mark this as not full CRUD
  });
}

async function testDashboard(page) {
  console.log('\n--- Dashboard ---');
  await page.waitForTimeout(2000);
  const content = await page.evaluate(() => ({
    length: document.body?.innerText?.trim()?.length || 0,
    hasCards: !!document.querySelector('.el-card, [class*="stat"], [class*="dashboard"]'),
    hasMenu: !!document.querySelector('.el-menu,.app-sidebar'),
  }));
  record('dashboard', 'render', content.length > 50 ? 'PASS' : 'FAIL', content);
}

async function run() {
  console.log('='.repeat(70));
  console.log(`L2 CRUD TEST — Round ${ROUND}`);
  console.log(`Factory: ${FACTORY_ID} | Account: e2e_factory_admin`);
  console.log(`Improvements: API response interception + row persistence check`);
  console.log('='.repeat(70));

  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.route('**fonts.googleapis.com**', r => r.fulfill({ status: 200, body: '' }));
  await ctx.route('**fonts.gstatic.com**', r => r.fulfill({ status: 200, body: '' }));
  const page = await ctx.newPage();

  console.log('\nLogging in as e2e_factory_admin...');
  await loginAndInit(page, 'e2e_factory_admin');
  console.log('Logged in.');

  // Dashboard
  await testDashboard(page);

  // CRUD tests with API response interception
  await testCRUD(page, { module: 'customers', path: '/sales/customers' });
  await testCRUD(page, { module: 'suppliers', path: '/procurement/suppliers' });

  // List-only tests (no create dialog or empty factory)
  await testListOnly(page, 'sales_orders', '/sales/orders');
  await testListOnly(page, 'procurement_orders', '/procurement/orders');
  await testListOnly(page, 'production_batches', '/production/batches');
  await testListOnly(page, 'warehouse_materials', '/warehouse/materials');
  await testListOnly(page, 'employees', '/hr/employees');
  await testListOnly(page, 'quality_inspections', '/quality/inspections');
  await testListOnly(page, 'equipment', '/equipment/list');
  await testListOnly(page, 'finance_costs', '/finance/costs');

  await browser.close();

  // Summary
  const pass = results.filter(r => r.status === 'PASS').length;
  const fail = results.filter(r => r.status === 'FAIL').length;
  const warn = results.filter(r => r.status === 'WARNING').length;
  const skip = results.filter(r => r.status === 'SKIP').length;

  console.log('\n' + '='.repeat(70));
  console.log(`L2 CRUD — Round ${ROUND} SUMMARY`);
  console.log(`PASS: ${pass} | FAIL: ${fail} | WARNING: ${warn} | SKIP: ${skip}`);
  console.log('='.repeat(70));

  if (fail > 0) {
    console.log('\nFAILURES:');
    results.filter(r => r.status === 'FAIL').forEach(r =>
      console.log(`  ${r.module}/${r.action}: ${JSON.stringify(r.evidence)}`));
  }

  const outFile = `tests/e2e-comprehensive/results/e2e-L2-R${ROUND}.json`;
  writeFileSync(outFile, JSON.stringify({
    round: ROUND, timestamp: new Date().toISOString(), factoryId: FACTORY_ID,
    results, summary: { pass, fail, warn, skip }
  }, null, 2));
  console.log(`\nResults → ${outFile}`);
}

run().catch(e => { console.error('FATAL:', e); process.exit(1); });
