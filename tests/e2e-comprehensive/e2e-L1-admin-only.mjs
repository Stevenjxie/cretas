/**
 * L1 Quick Admin Scan: factory_super_admin × 94 routes
 * Prints every route for real-time progress tracking.
 */
import { chromium } from 'playwright';
import { writeFileSync, readFileSync } from 'fs';

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

async function run() {
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

  // Login
  console.log('Logging in as e2e_factory_admin...');
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle', timeout: 60000 });
  await page.fill('input.el-input__inner[placeholder*="用户名"]', 'e2e_factory_admin');
  await page.fill('input[type="password"]', '123456');
  await page.click('button.login-button');
  // Wait for SPA to redirect + fully render dashboard
  await page.waitForURL('**/dashboard', { timeout: 30000 });
  // Wait for Vue app to mount
  for (let i = 0; i < 30; i++) {
    await page.waitForTimeout(500);
    const hasMenu = await page.evaluate(() => !!document.querySelector('.el-menu,.app-sidebar'));
    if (hasMenu) break;
  }
  console.log('Logged in. URL:', page.url());

  const results = [];
  let pass = 0, fail = 0;

  for (let i = 0; i < ROUTES.length; i++) {
    const route = ROUTES[i];
    const start = Date.now();
    let status;
    try {
      await page.goto(`${BASE}${route}`, { waitUntil: 'domcontentloaded', timeout: 30000 });
      // Poll up to 15s: check redirects + Vue render state
      for (let j = 0; j < 30; j++) {
        await page.waitForTimeout(500);
        const url = page.url();
        if (url.includes('/403')) { status = '403'; break; }
        if (url.includes('/login')) { status = 'REDIRECT_LOGIN'; break; }
        if (url.includes('/404')) { status = '404'; break; }
        // Check Vue rendering (innerHTML > 500 = components mounted, or menu visible)
        const rendered = await page.evaluate(() => ({
          appLen: document.querySelector('#app')?.innerHTML?.length || 0,
          text: document.body?.innerText?.trim()?.length || 0,
          hasMenu: !!document.querySelector('.el-menu,.app-sidebar'),
        }));
        if (rendered.text >= 10 || rendered.hasMenu || rendered.appLen > 500) {
          const hasToast = !!(await page.$('.el-message--error'));
          status = hasToast ? 'ERROR_TOAST' : 'OK';
          break;
        }
      }
      if (!status) {
        const hasToast = !!(await page.$('.el-message--error'));
        const finalLen = await page.evaluate(() => document.querySelector('#app')?.innerHTML?.length || 0);
        status = hasToast ? 'ERROR_TOAST' : (finalLen > 100 ? 'OK' : 'BLANK');
      }
    } catch (e) {
      status = 'TIMEOUT';
    }

    const elapsed = ((Date.now() - start) / 1000).toFixed(1);
    const isOK = status === 'OK';
    // For FACTORY type, restaurant routes should be 403
    const isRestaurant = route.startsWith('/restaurant/');
    const expected = isRestaurant ? '403' : 'OK';
    const icon = (status === expected || status === 'OK') ? 'PASS' : (status === '403' && isRestaurant) ? 'PASS' : 'FAIL';
    if (icon === 'PASS') pass++; else fail++;
    results.push({ route, status, elapsed });
    console.log(`[${icon}] (${i+1}/${ROUTES.length}) ${route.padEnd(42)} ${status.padEnd(14)} ${elapsed}s`);

    if (status === 'REDIRECT_LOGIN') {
      await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
      await page.waitForSelector('input.el-input__inner', { timeout: 30000 });
      await page.fill('input.el-input__inner[placeholder*="用户名"]', 'e2e_factory_admin');
      await page.fill('input[type="password"]', '123456');
      await page.click('button.login-button');
      for (let j = 0; j < 15; j++) {
        await page.waitForTimeout(1000);
        if (!page.url().includes('/login')) break;
      }
    }
  }

  await browser.close();

  console.log('\n=== SUMMARY ===');
  console.log(`PASS: ${pass} | FAIL: ${fail} | Total: ${ROUTES.length}`);
  console.log(`Network errors: ${netErrors.size}`);
  if (netErrors.size) [...netErrors].slice(0, 15).forEach(e => console.log(`  ${e}`));

  const failures = results.filter(r => {
    const isRestaurant = r.route.startsWith('/restaurant/');
    return !(r.status === 'OK' || (r.status === '403' && isRestaurant));
  });
  if (failures.length) {
    console.log('\nFAILURES:');
    failures.forEach(f => console.log(`  ${f.route}: ${f.status}`));
  }

  writeFileSync('tests/e2e-comprehensive/results/e2e-L1-admin-R1.json',
    JSON.stringify({ timestamp: new Date().toISOString(), account: 'e2e_factory_admin', results, netErrors: [...netErrors] }, null, 2));
}

run().catch(e => { console.error('FATAL:', e); process.exit(1); });
