// Take viewport-only screenshots to show KPI cards clearly
import { chromium } from '@playwright/test';
import path from 'path';

const BASE = 'https://admin.cretaceousfuture.com';
const OUT = path.resolve('tests/e2e-comprehensive/results/plan-c-with-seed');

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();

  await page.goto(BASE);
  await page.locator('input[placeholder*="用户名"]').fill('qhj_prod');
  await page.locator('input[placeholder="请输入密码"]').fill('123456');
  await page.locator('button.login-button').click();
  await page.waitForURL((u) => !new URL(String(u)).pathname.startsWith('/login'), { timeout: 30000 });

  // gross-margin 365d
  await page.goto(`${BASE}/restaurant/analytics/gross-margin`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(3000);
  await page.locator('.el-select').first().click();
  await page.waitForTimeout(800);
  await page.locator('.el-select-dropdown__item:has-text("近 365 天")').click();
  await page.waitForTimeout(4000);
  await page.screenshot({ path: path.join(OUT, 'gross-margin-viewport.png'), fullPage: false });
  console.log('gross-margin viewport captured');

  // store-comparison
  await page.goto(`${BASE}/restaurant/analytics/stores`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(3000);
  await page.locator('.el-select').first().click();
  await page.waitForTimeout(1000);
  await page.locator('.el-select-dropdown__item').first().click();
  await page.waitForTimeout(4500);
  await page.screenshot({ path: path.join(OUT, 'store-comparison-viewport.png'), fullPage: false });
  console.log('store-comparison viewport captured');

  await browser.close();
})();
