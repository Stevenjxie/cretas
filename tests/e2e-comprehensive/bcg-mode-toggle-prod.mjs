// bcg-mode-toggle-prod.mjs
//
// Verifies the menu-board BCG real-version (毛利模式) toggle on prod.
// - Logs in as qhj_prod (RES_3101_009) via https://www.cretaceousfuture.com
// - Navigates to /restaurant/analytics/menu
// - Selects a POS upload
// - Switches mode to 按毛利率 (BCG 真实版)
// - Verifies:
//   1. Radio toggle triggers a /restaurant-ops/gross-margin network call
//   2. Quadrant summary cards re-render (count values change OR desc changes)
//   3. No console errors
//   4. Screenshots before/after mode switch
//
// Isolation: fresh chromium instance, no userDataDir — safe to run
// concurrently with other Playwright sessions.

import { chromium } from '@playwright/test';
import fs from 'fs';
import path from 'path';

const BASE = 'https://admin.cretaceousfuture.com';
const USER = 'qhj_prod';
const PASS = '123456';
const FACTORY = 'RES_3101_009';
const OUT = path.resolve('tests/e2e-comprehensive/results/bcg-toggle');
fs.mkdirSync(OUT, { recursive: true });

const LOG = [];
const log = (tag, msg) => {
  const line = `[${new Date().toISOString().slice(11, 19)}] ${tag} ${msg}`;
  console.log(line);
  LOG.push(line);
};

let exitCode = 0;
const FAIL = (msg) => { log('FAIL', msg); exitCode = 1; };
const OK = (msg) => log('OK', msg);

(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({
    viewport: { width: 1440, height: 900 },
  });
  const page = await ctx.newPage();

  const consoleErrors = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text());
  });
  const networkCalls = [];
  page.on('response', (res) => {
    const url = res.url();
    if (url.includes('/restaurant-ops/') || url.includes('/smartbi/')) {
      networkCalls.push({ url, status: res.status() });
    }
  });

  try {
    // === Step 1: Login ===
    log('STEP', 'Loading login page');
    await page.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 30000 });
    await page.waitForTimeout(1000);

    // Check if already redirected to login
    const url = page.url();
    log('INFO', `URL after goto: ${url}`);

    // Find login form — use specific placeholders from login/index.vue
    await page.locator('input[placeholder*="用户名"]').fill(USER);
    await page.locator('input[placeholder="请输入密码"]').fill(PASS);

    const userVal = await page.locator('input[placeholder*="用户名"]').inputValue();
    const passVal = await page.locator('input[placeholder="请输入密码"]').inputValue();
    log('INFO', `Form filled: username=${userVal} password=${'*'.repeat(passVal.length)}`);

    await page.locator('button.login-button').click();
    await page.waitForTimeout(500);
    // Capture early screenshot in case login fails
    await page.screenshot({ path: path.join(OUT, 'post-click.png'), fullPage: false });
    // Login redirects to /dashboard (or /restaurant dashboard)
    try {
      await page.waitForURL((u) => {
        const url = new URL(String(u));
        return !url.pathname.startsWith('/login');
      }, { timeout: 30000 });
      OK(`Login succeeded, url=${page.url()}`);
    } catch (e) {
      const bodyText = await page.locator('body').textContent();
      const snippet = bodyText.replace(/\s+/g, ' ').slice(0, 400);
      FAIL(`Login did not redirect. URL=${page.url()}. Body snippet: ${snippet}`);
      await page.screenshot({ path: path.join(OUT, 'login-stuck.png'), fullPage: true });
      throw e;
    }
    await page.waitForTimeout(2000);

    // === Step 2: Navigate to menu-board ===
    log('STEP', 'Navigating to /restaurant/analytics/menu');
    await page.goto(`${BASE}/restaurant/analytics/menu`, { waitUntil: 'networkidle', timeout: 30000 });
    await page.waitForTimeout(5000);
    await page.screenshot({ path: path.join(OUT, 'menu-board-arrived.png'), fullPage: true });

    // Verify page loaded
    log('INFO', `After nav url=${page.url()}`);
    const title = await page.locator('.page-title').first().textContent().catch(() => '');
    if (title.includes('菜品四象限')) OK(`Page title: ${title}`);
    else {
      const body = (await page.locator('body').textContent()).replace(/\s+/g, ' ').slice(0, 300);
      FAIL(`Unexpected title: ${title}. Body: ${body}`);
    }

    // === Step 3: Wait for upload selector to populate, pick first upload ===
    const uploadSelect = page.locator('.el-select').first();
    await uploadSelect.click();
    await page.waitForTimeout(1500);
    const firstOption = page.locator('.el-select-dropdown__item').first();
    const uploadLabel = await firstOption.textContent().catch(() => '');
    log('INFO', `Upload options visible. First: ${uploadLabel.trim()}`);
    await firstOption.click();
    await page.waitForTimeout(3000);

    // Check quadrant cards render in revenue mode
    const revCardsText = await page.locator('.quadrant-card').allTextContents().catch(() => []);
    if (revCardsText.length === 4) OK(`Revenue mode: 4 quadrant cards rendered`);
    else FAIL(`Revenue mode: expected 4 cards, got ${revCardsText.length}`);
    const revDesc0 = revCardsText[0] || '';
    log('INFO', `Revenue mode quadrant[0] text: ${revDesc0.replace(/\s+/g, ' ').slice(0, 120)}`);

    await page.screenshot({ path: path.join(OUT, 'before-revenue-mode.png'), fullPage: false });
    OK('Screenshot saved: before-revenue-mode.png');

    // === Step 4: Click 按毛利率 (BCG 真实版) ===
    log('STEP', 'Clicking 按毛利率 radio button');
    const marginRadio = page.locator('label:has-text("按毛利率")').first();
    const exists = await marginRadio.count();
    if (exists === 0) {
      FAIL('按毛利率 radio not found — BCG real-version NOT deployed to prod');
      throw new Error('missing radio');
    }
    OK('按毛利率 radio found');

    // Clear network call tracker for the margin call
    const beforeNet = networkCalls.length;
    await marginRadio.click();
    await page.waitForTimeout(4000);

    // === Step 5: Verify /gross-margin API was called ===
    const marginCalls = networkCalls.slice(beforeNet).filter(c => c.url.includes('/gross-margin'));
    if (marginCalls.length > 0) {
      OK(`/gross-margin API called (status ${marginCalls[0].status})`);
    } else {
      FAIL('No /gross-margin API call detected after mode switch');
    }

    // === Step 6: Verify quadrant re-classification ===
    const marginCardsText = await page.locator('.quadrant-card').allTextContents().catch(() => []);
    if (marginCardsText.length === 4) OK(`Margin mode: 4 quadrant cards rendered`);
    else FAIL(`Margin mode: expected 4 cards, got ${marginCardsText.length}`);

    const marginDesc0 = marginCardsText[0] || '';
    log('INFO', `Margin mode quadrant[0] text: ${marginDesc0.replace(/\s+/g, ' ').slice(0, 120)}`);

    // Desc should differ: revenue mode says "高销量 + 高收入", margin mode says "高销量 + 高毛利"
    if (marginDesc0.includes('毛利')) OK('Quadrant desc updated to 毛利 semantics');
    else FAIL(`Quadrant desc did NOT switch to margin semantics. Got: ${marginDesc0}`);

    await page.screenshot({ path: path.join(OUT, 'after-margin-mode.png'), fullPage: false });
    OK('Screenshot saved: after-margin-mode.png');

    // === Step 7: Console error check ===
    if (consoleErrors.length === 0) {
      OK('No console errors during test');
    } else {
      FAIL(`${consoleErrors.length} console errors: ${consoleErrors.slice(0, 3).join(' | ')}`);
    }

    // Log all gross-margin / restaurant-ops network activity
    log('INFO', `Total restaurant-ops calls: ${networkCalls.length}`);
    networkCalls.forEach(c => log('NET', `${c.status} ${c.url.replace(BASE, '')}`));

  } catch (e) {
    FAIL(`Exception: ${e.message}`);
    try { await page.screenshot({ path: path.join(OUT, 'error.png'), fullPage: false }); } catch {}
  } finally {
    await browser.close();
  }

  // Write log file
  fs.writeFileSync(path.join(OUT, 'report.log'), LOG.join('\n') + '\n');
  log('DONE', `Exit ${exitCode}. Log: ${path.join(OUT, 'report.log')}`);
  process.exit(exitCode);
})();
