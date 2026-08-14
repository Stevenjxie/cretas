/**
 * L1 SPA Navigation Test: Uses Vue Router's push() for fast client-side navigation
 * instead of page.goto() which causes full page reloads (20-35s each).
 *
 * Strategy: Login → wait for SPA to mount → router.push() for each route → check result
 * Each route takes ~2-3 seconds instead of 20-35s.
 */
import { chromium } from 'playwright';
import { writeFileSync, readFileSync, existsSync } from 'fs';

const BASE = 'http://139.196.165.140:8086';
const PASSWORD = '123456';
const SETUP_FILE = 'tests/e2e-comprehensive/results/R0-setup.json';

const ROUTES = [
  '/dashboard', '/dashboard/production-progress',
  '/production/batches', '/production/plans', '/production/bom',
  '/production/approval', '/production/bom-achievement', '/production/process-io',
  '/production/material-requisitions',
  '/warehouse/materials', '/warehouse/shipments', '/warehouse/inventory',
  '/warehouse/reusable-containers', '/warehouse/material-price-trend',
  '/transfer/list',
  '/quality/inspections', '/quality/disposals', '/quality/standards',
  '/procurement/orders', '/procurement/suppliers', '/procurement/price-lists',
  '/sales/orders', '/sales/quotes', '/sales/finished-goods',
  '/sales/customers', '/sales/shipments',
  '/hr/employees', '/hr/attendance', '/hr/whitelist', '/hr/departments',
  '/equipment/list', '/equipment/maintenance', '/equipment/alerts',
  '/finance/costs', '/finance/reports', '/finance/ar-ap',
  '/finance/sku-margin', '/finance/invoices', '/finance/payments',
  '/rd/samples', '/rd/converted',
  '/system/users', '/system/roles', '/system/logs', '/system/settings',
  '/system/ai-intents', '/system/skill-tools', '/system/products',
  '/system/features', '/system/pos', '/system/work-processes',
  '/system/product-processes', '/system/workflow-designer',
  '/system/smartbi-config', '/system/smartbi-config/data-sources',
  '/system/smartbi-config/chart-templates', '/system/badge-generator',
  '/analytics/overview', '/analytics/trends', '/analytics/ai-reports',
  '/analytics/kpi', '/analytics/production-report', '/analytics/alert-dashboard',
  '/analytics/supply-chain',
  '/calibration/list',
  '/scheduling/overview', '/scheduling/plans', '/scheduling/realtime',
  '/scheduling/workers', '/scheduling/alerts',
  '/restaurant/requisitions', '/restaurant/wastage', '/restaurant/recipes',
  '/restaurant/stocktaking', '/restaurant/analytics',
  '/restaurant/analytics/menu', '/restaurant/analytics/stores',
  '/restaurant/analytics/dianping',
  '/production-analytics/production', '/production-analytics/efficiency',
  '/smart-bi/dashboard', '/smart-bi/finance', '/smart-bi/sales',
  '/smart-bi/query', '/smart-bi/query-templates', '/smart-bi/analysis',
  '/smart-bi/upload', '/smart-bi/data-completeness', '/smart-bi/food-kb-feedback',
  '/smart-bi/calibration', '/smart-bi/financial-dashboard',
  '/smart-bi/whatif', '/smart-bi/restaurant-v2',
  '/canvas-editor',
];

// Load setup
const setup = existsSync(SETUP_FILE) ? JSON.parse(readFileSync(SETUP_FILE, 'utf8')) : null;
const WEB_ACCOUNTS = setup ? setup.accounts.filter(a => a.web && a.created) : [
  { username: 'e2e_factory_admin', roleCode: 'factory_super_admin' },
];
const MOBILE_ACCOUNTS = setup ? setup.accounts.filter(a => !a.web && a.created) : [];

async function loginAndWait(page, username) {
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForSelector('input.el-input__inner', { timeout: 30000 });
  await page.fill('input.el-input__inner[placeholder*="用户名"]', username);
  await page.fill('input[type="password"]', PASSWORD);
  await page.click('button.login-button');

  // Wait for SPA to redirect from /login
  for (let i = 0; i < 30; i++) {
    await page.waitForTimeout(1000);
    const url = page.url();
    if (url.includes('/mobile-only')) return 'MOBILE_ONLY';
    if (!url.includes('/login')) {
      // Wait for Vue app to fully mount
      for (let j = 0; j < 20; j++) {
        await page.waitForTimeout(500);
        const hasMenu = await page.evaluate(() => !!document.querySelector('.el-menu,.app-sidebar'));
        if (hasMenu) return 'OK';
      }
      return 'OK'; // SPA loaded but menu not found (still OK)
    }
  }
  return 'LOGIN_FAILED';
}

async function spaNavigate(page, route) {
  // Use Vue Router push for client-side navigation (no page reload)
  const result = await page.evaluate(async (path) => {
    try {
      const router = window.__vue_app__?.config?.globalProperties?.$router;
      if (!router) return { error: 'no_router' };
      await router.push(path);
      return { url: window.location.pathname };
    } catch (e) {
      return { error: e.message };
    }
  }, route);

  if (result.error === 'no_router') {
    // Fallback: use window.location for navigation (still SPA, just different trigger)
    await page.evaluate((path) => { window.location.hash = ''; window.history.pushState({}, '', path); }, route);
    await page.waitForTimeout(500);
    // Trigger Vue router by doing a real navigation
    await page.evaluate((path) => {
      const event = new PopStateEvent('popstate');
      window.dispatchEvent(event);
    }, route);
  }

  // Wait up to 5 seconds for route to resolve
  for (let i = 0; i < 10; i++) {
    await page.waitForTimeout(500);
    const url = page.url();
    if (url.includes('/403')) return '403';
    if (url.includes('/login')) return 'REDIRECT_LOGIN';
    if (url.includes('/404')) return '404';
  }

  // Check final state
  const url = page.url();
  if (url.includes('/403')) return '403';
  if (url.includes('/login')) return 'REDIRECT_LOGIN';

  const hasToast = !!(await page.$('.el-message--error'));
  if (hasToast) return 'ERROR_TOAST';
  return 'OK';
}

async function testAccount(account) {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.route('**fonts.googleapis.com**', r => r.fulfill({ status: 200, body: '' }));
  await ctx.route('**fonts.gstatic.com**', r => r.fulfill({ status: 200, body: '' }));
  const page = await ctx.newPage();

  const netErrors = new Set();
  page.on('response', r => {
    if (r.status() >= 400 && !r.url().includes('.map') && !r.url().includes('favicon') && !r.url().includes('sockjs'))
      netErrors.add(`${r.status()} ${r.url().replace(BASE, '').split('?')[0]}`);
  });

  const loginResult = await loginAndWait(page, account.username);
  if (loginResult !== 'OK') {
    console.log(`  [${loginResult === 'MOBILE_ONLY' ? 'PASS' : 'FAIL'}] ${account.username} → ${loginResult}`);
    await browser.close();
    return { account, loginResult, routes: [] };
  }

  const routeResults = [];
  let pass = 0, fail = 0;
  const startTime = Date.now();

  for (let i = 0; i < ROUTES.length; i++) {
    const route = ROUTES[i];
    const t0 = Date.now();
    const status = await spaNavigate(page, route);
    const elapsed = ((Date.now() - t0) / 1000).toFixed(1);

    // For FACTORY type, restaurant routes should be 403
    const isRestaurant = route.startsWith('/restaurant/');
    const isOK = (status === 'OK') || (status === '403' && isRestaurant);
    if (isOK) pass++; else fail++;

    routeResults.push({ route, status, elapsed });
    const icon = isOK ? 'PASS' : 'FAIL';
    console.log(`  [${icon}] (${i+1}/${ROUTES.length}) ${route.padEnd(42)} ${status.padEnd(14)} ${elapsed}s`);

    // If session expired, re-login
    if (status === 'REDIRECT_LOGIN') {
      await loginAndWait(page, account.username);
    }
  }

  const totalElapsed = ((Date.now() - startTime) / 1000).toFixed(0);
  console.log(`  Summary: ${pass}/${ROUTES.length} PASS, ${fail} FAIL, ${totalElapsed}s total, ${netErrors.size} network errors`);

  await browser.close();
  return { account, loginResult, routes: routeResults, netErrors: [...netErrors] };
}

async function run() {
  console.log('='.repeat(80));
  console.log('L1 SPA NAVIGATION TEST — Round 1');
  console.log(`Accounts: ${WEB_ACCOUNTS.length} web + ${MOBILE_ACCOUNTS.length} mobile | Routes: ${ROUTES.length}`);
  console.log('='.repeat(80));

  const allResults = [];

  // Test mobile-only accounts first (quick)
  console.log('\n--- MOBILE-ONLY ---');
  for (const acct of MOBILE_ACCOUNTS) {
    const r = await testAccount(acct);
    allResults.push(r);
  }

  // Test web accounts
  for (const acct of WEB_ACCOUNTS) {
    console.log(`\n--- ${acct.username} (${acct.roleCode}) ---`);
    const r = await testAccount(acct);
    allResults.push(r);
  }

  // Summary
  let totalPass = 0, totalFail = 0;
  const failures = [];
  for (const r of allResults) {
    for (const rt of r.routes) {
      const isRestaurant = rt.route.startsWith('/restaurant/');
      if (rt.status === 'OK' || (rt.status === '403' && isRestaurant)) totalPass++;
      else {
        totalFail++;
        failures.push(`${r.account.username}(${r.account.roleCode}) ${rt.route}: ${rt.status}`);
      }
    }
  }

  console.log('\n' + '='.repeat(80));
  console.log(`TOTAL: ${totalPass}/${totalPass + totalFail} PASS | ${totalFail} FAIL`);
  if (failures.length) {
    console.log('\nFAILURES:');
    failures.forEach(f => console.log(`  ${f}`));
  }
  console.log('='.repeat(80));

  writeFileSync('tests/e2e-comprehensive/results/e2e-L1-R1.json',
    JSON.stringify({ timestamp: new Date().toISOString(), results: allResults, totalPass, totalFail, failures }, null, 2));
  console.log('\nResults → tests/e2e-comprehensive/results/e2e-L1-R1.json');
}

run().catch(e => { console.error('FATAL:', e); process.exit(1); });
