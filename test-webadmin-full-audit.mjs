/**
 * Full audit: 94 routes (factory_admin1) + 7 accounts permission matrix
 */
import { chromium } from 'playwright';
import { writeFileSync } from 'fs';

const BASE = 'http://139.196.165.140:8086';

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

const ACCOUNTS = [
  { username: 'factory_admin1', desc: '工厂总监', expectLogin: true },
  { username: 'hr_admin1', desc: 'HR管理员', expectLogin: true },
  { username: 'dispatcher1', desc: '调度员', expectLogin: true },
  { username: 'warehouse_mgr1', desc: '仓库经理', expectLogin: true },
  { username: 'finance_mgr1', desc: '财务经理', expectLogin: true },
  { username: 'viewer1', desc: '只读查看者', expectLogin: true },
  { username: 'operator1', desc: '操作员(仅移动端)', expectLogin: false },
  { username: 'restaurant_admin1', desc: '餐饮管理', expectLogin: true },
];

const MULTI_ROUTES = [
  '/dashboard', '/procurement/orders', '/sales/orders', '/hr/employees',
  '/finance/costs', '/system/users', '/analytics/overview',
  '/restaurant/requisitions', '/smart-bi/dashboard', '/smart-bi/query',
  '/canvas-editor', '/calibration/list',
];

async function login(page, username) {
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('input.el-input__inner', { timeout: 15000 });
  await page.fill('input.el-input__inner[placeholder*="用户名"]', username);
  await page.fill('input[type="password"]', '123456');
  await page.click('button.login-button');
  for (let i = 0; i < 12; i++) {
    await page.waitForTimeout(1000);
    const url = page.url();
    if (!url.includes('/login')) {
      // /mobile-only counts as "not logged in for web"
      if (url.includes('/mobile-only')) return false;
      return true;
    }
  }
  return false;
}

async function checkPage(page, route) {
  const resp = await page.goto(`${BASE}${route}`, { waitUntil: 'domcontentloaded', timeout: 20000 });
  await page.waitForTimeout(2500);
  const url = page.url();
  if (url.includes('/403')) return '403';
  if (url.includes('/login')) return 'REDIRECT_LOGIN';
  if (url.includes('/404')) return '404';
  if (url.includes('/mobile-only')) return 'MOBILE_ONLY';
  const hasToast = !!(await page.$('.el-message--error'));
  if (hasToast) return 'ERROR_TOAST';
  const bodyLen = await page.evaluate(() => document.body?.innerText?.trim()?.length || 0);
  if (bodyLen < 10) return 'BLANK';
  return 'OK';
}

async function run() {
  // ===== PHASE 1: Full scan =====
  console.log('=' .repeat(80));
  console.log('PHASE 1: factory_admin1 full 94-route scan');
  console.log('='.repeat(80));

  const browser1 = await chromium.launch({ headless: true });
  const ctx1 = await browser1.newContext({ viewport: { width: 1920, height: 1080 } });
  const page1 = await ctx1.newPage();

  const consoleErrors = new Set();
  const networkErrors = new Set();
  page1.on('console', msg => { if (msg.type() === 'error') consoleErrors.add(msg.text().substring(0, 150)); });
  page1.on('response', r => {
    if (r.status() >= 400 && !r.url().includes('.map') && !r.url().includes('favicon') && !r.url().includes('sockjs'))
      networkErrors.add(`${r.status()} ${r.url().replace(BASE, '').split('?')[0]}`);
  });

  const ok = await login(page1, 'factory_admin1');
  if (!ok) { console.error('LOGIN FAILED'); await browser1.close(); process.exit(1); }

  const results = { pass: 0, fail403: 0, errorToast: 0, other: 0, details: [] };
  for (let i = 0; i < ROUTES.length; i++) {
    const r = ROUTES[i];
    try {
      const status = await checkPage(page1, r);
      if (status === 'OK') results.pass++;
      else if (status === '403') results.fail403++;
      else if (status === 'ERROR_TOAST') results.errorToast++;
      else results.other++;
      if (status !== 'OK' && status !== '403') results.details.push({ route: r, status });
      const icon = status === 'OK' ? 'PASS' : status === '403' ? ' 403' : 'FAIL';
      console.log(`[${icon}] (${i+1}/${ROUTES.length}) ${r}`);
      if (status === 'REDIRECT_LOGIN') { await login(page1, 'factory_admin1'); }
    } catch (e) {
      results.other++;
      results.details.push({ route: r, status: 'TIMEOUT' });
      console.log(`[ERR!] (${i+1}/${ROUTES.length}) ${r} - ${e.message.substring(0,60)}`);
    }
  }

  console.log('\n--- PHASE 1 SUMMARY ---');
  console.log(`PASS: ${results.pass} | 403(design): ${results.fail403} | ERROR_TOAST: ${results.errorToast} | OTHER: ${results.other}`);
  if (results.details.length) {
    console.log('Issues:');
    results.details.forEach(d => console.log(`  ${d.route}: ${d.status}`));
  }
  console.log(`Console errors: ${consoleErrors.size} unique`);
  if (consoleErrors.size) [...consoleErrors].forEach((e, i) => console.log(`  ${i+1}. ${e}`));
  console.log(`Network errors: ${networkErrors.size} unique`);
  if (networkErrors.size) [...networkErrors].forEach((e, i) => console.log(`  ${i+1}. ${e}`));
  await browser1.close();

  // ===== PHASE 2: Multi-account =====
  console.log('\n' + '='.repeat(80));
  console.log('PHASE 2: Multi-account login + permission test');
  console.log('='.repeat(80));

  const accountResults = [];
  for (const acct of ACCOUNTS) {
    const browser2 = await chromium.launch({ headless: true });
    const page2 = await browser2.newPage();
    const loginOk = await login(page2, acct.username);

    const r = { ...acct, loginOk, pages: {} };

    if (acct.expectLogin && !loginOk) r.verdict = 'UNEXPECTED_FAIL';
    else if (!acct.expectLogin && loginOk) r.verdict = 'UNEXPECTED_SUCCESS';
    else r.verdict = 'AS_EXPECTED';

    if (loginOk) {
      for (const route of MULTI_ROUTES) {
        try { r.pages[route] = await checkPage(page2, route); }
        catch { r.pages[route] = 'TIMEOUT'; }
        if (r.pages[route] === 'REDIRECT_LOGIN') {
          const relogin = await login(page2, acct.username);
          if (!relogin) break;
        }
      }
    }
    accountResults.push(r);
    const okCount = Object.values(r.pages).filter(s => s === 'OK').length;
    const f403 = Object.values(r.pages).filter(s => s === '403').length;
    const errs = Object.values(r.pages).filter(s => s !== 'OK' && s !== '403').length;
    console.log(`${acct.username.padEnd(22)} Login: ${loginOk ? 'YES' : 'NO '} [${r.verdict.padEnd(16)}] OK:${String(okCount).padStart(2)} 403:${String(f403).padStart(2)} Err:${errs}`);
    await browser2.close();
  }

  // Permission matrix
  const loggedIn = accountResults.filter(a => a.loginOk);
  if (loggedIn.length) {
    console.log('\n--- PERMISSION MATRIX ---');
    const hdr = 'Route'.padEnd(30) + loggedIn.map(a => a.username.substring(0,14).padEnd(16)).join('');
    console.log(hdr);
    console.log('-'.repeat(hdr.length));
    for (const route of MULTI_ROUTES) {
      const cells = loggedIn.map(a => (a.pages[route] || '-').padEnd(16));
      console.log(route.padEnd(30) + cells.join(''));
    }
  }

  // Unexpected
  const unexpected = accountResults.filter(a => a.verdict !== 'AS_EXPECTED');
  if (unexpected.length) {
    console.log('\n--- UNEXPECTED ---');
    unexpected.forEach(a => console.log(`  ${a.username}: expected login=${a.expectLogin}, got ${a.loginOk}`));
  }

  // Write JSON
  writeFileSync('test-webadmin-full-audit-results.json', JSON.stringify({
    timestamp: new Date().toISOString(),
    phase1: { ...results, consoleErrors: [...consoleErrors], networkErrors: [...networkErrors] },
    phase2: accountResults,
  }, null, 2));
  console.log('\nResults -> test-webadmin-full-audit-results.json');
}

run().catch(e => { console.error('FATAL:', e); process.exit(1); });
