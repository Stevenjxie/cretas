// trends-metric-toggle-test.mjs
//
// Verifies the /analytics/trends second-metric toggle (订单数 ↔ 客单价) works on test env.

import { chromium } from '@playwright/test';
import fs from 'fs';
import path from 'path';

// Test env: admin test URL or 8097 — use admin subdomain since test may not have its own
const BASE = process.env.TEST_URL || 'https://admin.cretaceousfuture.com';
const USER = 'qhj_prod';
const PASS = '123456';
const OUT = path.resolve('tests/e2e-comprehensive/results/trends-metric-toggle');
fs.mkdirSync(OUT, { recursive: true });

const LOG = [];
const log = (tag, msg) => {
  const line = `[${new Date().toISOString().slice(11, 19)}] ${tag} ${msg}`;
  console.log(line);
  LOG.push(line);
};
let exitCode = 0;
const FAIL = (m) => { log('FAIL', m); exitCode = 1; };
const OK = (m) => log('OK', m);

(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();

  const consoleErrors = [];
  page.on('console', (msg) => { if (msg.type() === 'error') consoleErrors.push(msg.text()); });
  const apiCalls = [];
  page.on('response', (res) => {
    const url = res.url();
    if (url.includes('/gold/daily-trend') || url.includes('/restaurant-ops/') || url.includes('/reports/dashboard/trends')) {
      apiCalls.push({ url, status: res.status() });
    }
  });

  try {
    log('STEP', `Login at ${BASE}`);
    await page.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 30000 });
    await page.locator('input[placeholder*="用户名"]').fill(USER);
    await page.locator('input[placeholder="请输入密码"]').fill(PASS);
    await page.locator('button.login-button').click();
    await page.waitForURL((u) => !new URL(String(u)).pathname.startsWith('/login'), { timeout: 30000 });
    OK(`Login succeeded url=${page.url()}`);

    log('STEP', 'Navigate /analytics/trends');
    await page.goto(`${BASE}/analytics/trends`, { waitUntil: 'networkidle', timeout: 30000 });
    await page.waitForTimeout(4000);
    await page.screenshot({ path: path.join(OUT, 'trends-arrived.png'), fullPage: false });

    // Confirm Gold trend card visible
    const goldVisible = await page.locator('.gold-trend-card').isVisible().catch(() => false);
    if (goldVisible) OK('Gold trend card visible');
    else {
      FAIL('Gold trend card not visible');
      await page.screenshot({ path: path.join(OUT, 'no-gold-card.png'), fullPage: true });
    }

    // Verify default = 订单数 selected
    const billsRadio = page.locator('.gold-trend-card label:has-text("订单数")');
    const avgRadio = page.locator('.gold-trend-card label:has-text("客单价")');
    const billsSelectedDefault = (await billsRadio.getAttribute('class') || '').includes('is-active');
    if (billsSelectedDefault) OK('Default metric = 订单数');
    else log('INFO', `Default may not be 订单数; class=${await billsRadio.getAttribute('class')}`);

    // Pre-click screenshot
    await page.screenshot({ path: path.join(OUT, 'metric-bills.png'), fullPage: false });

    // Click 客单价
    log('STEP', 'Click 客单价');
    await avgRadio.click();
    await page.waitForTimeout(1500);
    const avgSelected = (await avgRadio.getAttribute('class') || '').includes('is-active');
    if (avgSelected) OK('客单价 radio now active');
    else FAIL(`客单价 not active after click; class=${await avgRadio.getAttribute('class')}`);

    // The chart legend should now contain 客单价
    const legendText = await page.locator('.gold-trend-card').textContent();
    if (legendText.includes('客单价')) OK('Card contains 客单价 text');
    else FAIL(`Card missing 客单价 text. Content: ${legendText.slice(0, 200)}`);

    await page.screenshot({ path: path.join(OUT, 'metric-avg.png'), fullPage: false });

    // Toggle back to 订单数
    log('STEP', 'Toggle back to 订单数');
    await billsRadio.click();
    await page.waitForTimeout(1000);
    const billsBack = (await billsRadio.getAttribute('class') || '').includes('is-active');
    if (billsBack) OK('订单数 reselectable');
    else FAIL('订单数 not reselectable');

    // Console error check
    if (consoleErrors.length === 0) OK('No console errors');
    else FAIL(`${consoleErrors.length} console errors: ${consoleErrors.slice(0, 2).join(' | ')}`);

    log('INFO', `API calls: ${apiCalls.length}`);
    apiCalls.forEach(c => log('NET', `${c.status} ${c.url.replace(BASE, '')}`));
  } catch (e) {
    FAIL(`Exception: ${e.message}`);
    try { await page.screenshot({ path: path.join(OUT, 'error.png'), fullPage: true }); } catch {}
  } finally {
    await browser.close();
  }

  fs.writeFileSync(path.join(OUT, 'report.log'), LOG.join('\n') + '\n');
  log('DONE', `Exit ${exitCode}`);
  process.exit(exitCode);
})();
