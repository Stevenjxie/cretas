// plan-c-with-seed-visual.mjs — capture Plan C pages now that demo data is seeded.
import { chromium } from '@playwright/test';
import fs from 'fs';
import path from 'path';

const BASE = 'https://admin.cretaceousfuture.com';
const OUT = path.resolve('tests/e2e-comprehensive/results/plan-c-with-seed');
fs.mkdirSync(OUT, { recursive: true });

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();

  // login
  await page.goto(BASE);
  await page.locator('input[placeholder*="用户名"]').fill('qhj_prod');
  await page.locator('input[placeholder="请输入密码"]').fill('123456');
  await page.locator('button.login-button').click();
  await page.waitForURL((u) => !new URL(String(u)).pathname.startsWith('/login'), { timeout: 30000 });

  // 1. gross-margin page — should show 总营收/总毛利/平均毛利率 + 10 dishes in table
  await page.goto(`${BASE}/restaurant/analytics/gross-margin`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(3500);
  // default daysWindow=30 shows 0 because POS data is 2025. Set to 90.
  // Actually the page doesn't let us pick 365; use select to pick 90
  await page.locator('.el-select').first().click();
  await page.waitForTimeout(800);
  await page.locator('.el-select-dropdown__item:has-text("近 365 天")').click();
  await page.waitForTimeout(3500);
  await page.screenshot({ path: path.join(OUT, 'gross-margin-90d.png'), fullPage: true });
  console.log('Captured gross-margin (90d)');

  // 2. store-comparison page — should show 毛利率 column with real %
  await page.goto(`${BASE}/restaurant/analytics/stores`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(3500);
  // Pick first upload (POS file)
  await page.locator('.el-select').first().click();
  await page.waitForTimeout(1000);
  await page.locator('.el-select-dropdown__item').first().click();
  await page.waitForTimeout(4000);
  await page.screenshot({ path: path.join(OUT, 'store-comparison.png'), fullPage: true });
  console.log('Captured store-comparison');

  // 3. menu-board BCG margin mode — should now have dishes spread across quadrants
  await page.goto(`${BASE}/restaurant/analytics/menu`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(3500);
  await page.locator('.el-select').first().click();
  await page.waitForTimeout(1000);
  await page.locator('.el-select-dropdown__item').first().click();
  await page.waitForTimeout(3500);
  await page.locator('label:has-text("按毛利率")').first().click();
  await page.waitForTimeout(3500);
  await page.screenshot({ path: path.join(OUT, 'bcg-margin-mode.png'), fullPage: false });
  console.log('Captured BCG margin mode');

  await browser.close();
})();
