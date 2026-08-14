// Debug BCG substring match
import { chromium } from '@playwright/test';
const BASE = 'https://admin.cretaceousfuture.com';

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();

  const apiCalls = [];
  page.on('response', (r) => {
    if (r.url().includes('/gross-margin')) apiCalls.push({ url: r.url(), status: r.status() });
  });

  await page.goto(BASE);
  await page.locator('input[placeholder*="用户名"]').fill('qhj_prod');
  await page.locator('input[placeholder="请输入密码"]').fill('123456');
  await page.locator('button.login-button').click();
  await page.waitForURL((u) => !new URL(String(u)).pathname.startsWith('/login'), { timeout: 30000 });

  await page.goto(`${BASE}/restaurant/analytics/menu`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(3500);
  await page.locator('.el-select').first().click();
  await page.waitForTimeout(1200);
  await page.locator('.el-select-dropdown__item').first().click();
  await page.waitForTimeout(4500);

  await page.locator('label:has-text("按毛利率")').first().click();
  await page.waitForTimeout(6000); // longer wait for margin load

  console.log('gross-margin API calls:', apiCalls.length);
  apiCalls.forEach(c => console.log(' ', c.status, c.url));

  // Read quadrant text
  const cards = await page.locator('.quadrant-card').allTextContents();
  console.log('Quadrants:', cards.map(c => c.replace(/\s+/g, ' ').slice(0, 60)));

  // Read first few items from table
  const items = await page.locator('.el-table__body tr').allTextContents();
  console.log('First 3 table rows:');
  items.slice(0, 3).forEach((t, i) => console.log(` ${i+1}.`, t.replace(/\s+/g, ' ').slice(0, 120)));

  await browser.close();
})();
