// trends-tooltip-visual.mjs
// Capture chart tooltip with hover to visually confirm series-aware formatter.
import { chromium } from '@playwright/test';
import path from 'path';
import fs from 'fs';

const BASE = 'https://admin.cretaceousfuture.com';
const OUT = path.resolve('tests/e2e-comprehensive/results/plan-c-polish');
fs.mkdirSync(OUT, { recursive: true });

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();

  await page.goto(BASE);
  await page.locator('input[placeholder*="用户名"]').fill('qhj_prod');
  await page.locator('input[placeholder="请输入密码"]').fill('123456');
  await page.locator('button.login-button').click();
  await page.waitForURL((u) => !new URL(String(u)).pathname.startsWith('/login'), { timeout: 30000 });

  // Ensure mode = 客单价
  await page.evaluate(() => localStorage.setItem('trends.secondMetric.v1', 'avg'));

  await page.goto(`${BASE}/analytics/trends`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(4000);

  // Find chart canvas and hover mid-x
  const chart = await page.locator('#gold-revenue-chart').boundingBox();
  if (chart) {
    await page.mouse.move(chart.x + chart.width * 0.5, chart.y + chart.height * 0.5);
    await page.waitForTimeout(1500);
    await page.screenshot({ path: path.join(OUT, 'tooltip-avg-mode.png'), fullPage: false });
    console.log('Tooltip screenshot captured (avg mode).');
  }

  // Switch to bills and re-capture
  await page.locator('.gold-trend-card label:has-text("订单数")').click();
  await page.waitForTimeout(2000);
  if (chart) {
    await page.mouse.move(chart.x + chart.width * 0.5, chart.y + chart.height * 0.5);
    await page.waitForTimeout(1500);
    await page.screenshot({ path: path.join(OUT, 'tooltip-bills-mode.png'), fullPage: false });
    console.log('Tooltip screenshot captured (bills mode).');
  }

  await browser.close();
})();
