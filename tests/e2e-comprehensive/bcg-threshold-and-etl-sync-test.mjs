// bcg-threshold-and-etl-sync-test.mjs
//
// Verifies two new features (Plan C Phase 8):
//  1. BCG 毛利模式 threshold slider on /restaurant/analytics/menu
//  2. 立即同步 (ETL) button on /restaurant/analytics/gross-margin

import { chromium } from '@playwright/test';
import fs from 'fs';
import path from 'path';

const BASE = 'https://admin.cretaceousfuture.com';
const USER = 'qhj_prod';
const PASS = '123456';
const OUT = path.resolve('tests/e2e-comprehensive/results/bcg-threshold-etl-sync');
fs.mkdirSync(OUT, { recursive: true });

const LOG = [];
const log = (t, m) => { const l = `[${new Date().toISOString().slice(11,19)}] ${t} ${m}`; console.log(l); LOG.push(l); };
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
    if (url.includes('/restaurant-ops/') || url.includes('/smartbi/')) apiCalls.push({ url, status: res.status(), method: res.request().method() });
  });

  try {
    // Login
    log('STEP', 'Login');
    await page.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 30000 });
    await page.locator('input[placeholder*="用户名"]').fill(USER);
    await page.locator('input[placeholder="请输入密码"]').fill(PASS);
    await page.locator('button.login-button').click();
    await page.waitForURL((u) => !new URL(String(u)).pathname.startsWith('/login'), { timeout: 30000 });
    OK(`Login ok url=${page.url()}`);

    // ====== TEST 1: BCG threshold slider ======
    log('STEP', 'Navigate /restaurant/analytics/menu');
    await page.goto(`${BASE}/restaurant/analytics/menu`, { waitUntil: 'networkidle', timeout: 30000 });
    await page.waitForTimeout(4000);
    await page.locator('.el-select').first().click();
    await page.waitForTimeout(1200);
    await page.locator('.el-select-dropdown__item').first().click();
    await page.waitForTimeout(3000);

    // Initially in revenue mode — slider should NOT be visible
    const sliderBefore = await page.locator('.margin-threshold-box').isVisible().catch(() => false);
    if (!sliderBefore) OK('Threshold slider hidden in revenue mode');
    else FAIL('Threshold slider leaked into revenue mode');

    // Click 按毛利率
    await page.locator('label:has-text("按毛利率")').first().click();
    await page.waitForTimeout(3000);

    const sliderAfter = await page.locator('.margin-threshold-box').isVisible().catch(() => false);
    if (sliderAfter) OK('Threshold slider visible in margin mode');
    else FAIL('Threshold slider missing in margin mode');

    // Capture counts at default 50%
    const cards50 = await page.locator('.quadrant-card').allTextContents();
    log('INFO', `@50% quadrants: ${cards50.map(c => c.replace(/\s+/g, ' ').slice(0, 40)).join(' | ')}`);
    await page.screenshot({ path: path.join(OUT, 'bcg-threshold-50.png'), fullPage: false });

    // Move slider to 30% — evaluate the internal Vue ref by finding the slider runway and clicking at 20% position
    // Simpler: click at specific x position on slider bar
    const slider = page.locator('.margin-threshold-box .el-slider__runway');
    const box = await slider.boundingBox();
    if (!box) {
      FAIL('Slider runway not found');
    } else {
      // Slider range 0.2 to 0.8. Position 0 = 0.2, position 1 = 0.8. 0.3 = (0.3-0.2)/(0.8-0.2) = 16.67%
      await page.mouse.click(box.x + box.width * 0.1667, box.y + box.height / 2);
      await page.waitForTimeout(2000);
      const label30 = await page.locator('.threshold-value').textContent();
      log('INFO', `After slider→0.3 click: ${label30}`);
      if (label30 && /2[0-9]%|3[0-5]%/.test(label30)) OK(`Slider moved to ~30% (${label30})`);
      else FAIL(`Slider didn't move as expected. Got: ${label30}`);
    }

    const cards30 = await page.locator('.quadrant-card').allTextContents();
    log('INFO', `@low threshold quadrants: ${cards30.map(c => c.replace(/\s+/g, ' ').slice(0, 40)).join(' | ')}`);
    await page.screenshot({ path: path.join(OUT, 'bcg-threshold-low.png'), fullPage: false });

    // Verify chart markLine label updated
    const pageText = await page.content();
    const markLineMatch = pageText.match(/毛利率分界:\s*(\d+)%/);
    if (markLineMatch) OK(`Chart markLine shows 毛利率分界: ${markLineMatch[1]}%`);

    // ====== TEST 2: ETL sync button on gross-margin ======
    log('STEP', 'Navigate /restaurant/analytics/gross-margin');
    await page.goto(`${BASE}/restaurant/analytics/gross-margin`, { waitUntil: 'networkidle', timeout: 30000 });
    await page.waitForTimeout(3000);

    const syncBtn = page.locator('button:has-text("立即同步")');
    if (await syncBtn.count() > 0) OK('立即同步 button rendered');
    else {
      FAIL('立即同步 button not found');
      throw new Error('missing sync button');
    }
    await page.screenshot({ path: path.join(OUT, 'gross-margin-with-sync.png'), fullPage: false });

    const beforeNet = apiCalls.length;
    log('STEP', 'Click 立即同步');
    await syncBtn.click();

    // Wait for ETL POST to complete (can take a few seconds for real data)
    await page.waitForTimeout(10000);

    const etlCalls = apiCalls.slice(beforeNet).filter(c => c.url.includes('/restaurant-ops/etl') && c.method === 'POST');
    if (etlCalls.length > 0) OK(`ETL POST called (status ${etlCalls[0].status})`);
    else FAIL('No POST /restaurant-ops/etl call detected');

    // Check for success toast or error toast
    const successToast = await page.locator('.el-message--success').count();
    const errorToast = await page.locator('.el-message--error').count();
    if (successToast > 0) OK('Success toast displayed');
    else if (errorToast > 0) {
      const errMsg = await page.locator('.el-message--error').first().textContent().catch(() => '');
      log('INFO', `Error toast: ${errMsg}`);
    }

    await page.screenshot({ path: path.join(OUT, 'after-etl-sync.png'), fullPage: false });

    if (consoleErrors.length === 0) OK('No console errors');
    else FAIL(`${consoleErrors.length} console errors: ${consoleErrors.slice(0, 2).join(' | ')}`);

    log('INFO', `API calls: ${apiCalls.length}`);
    apiCalls.forEach(c => log('NET', `${c.method} ${c.status} ${c.url.replace(BASE, '').slice(0, 100)}`));
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
