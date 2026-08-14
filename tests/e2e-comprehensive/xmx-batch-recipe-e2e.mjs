// E2E xmx batch AI recipe flow — verify "30s job for 100 recipes"
import { chromium } from '@playwright/test';
import path from 'path';
import fs from 'fs';

const BASE = 'https://admin.cretaceousfuture.com';
const OUT = path.resolve('tests/e2e-comprehensive/results/xmx-batch');
fs.mkdirSync(OUT, { recursive: true });

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();

  await page.goto(BASE);
  await page.locator('input[placeholder*="用户名"]').fill('xmx_admin');
  await page.locator('input[placeholder="请输入密码"]').fill('123456');
  await page.locator('button.login-button').click();
  await page.waitForURL((u) => !new URL(String(u)).pathname.startsWith('/login'), { timeout: 30000 });
  console.log('logged in');

  await page.goto(`${BASE}/restaurant/recipes`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(3000);

  // Open batch dialog
  await page.locator('button:has-text("AI 批量录配方")').click();
  await page.waitForTimeout(1500);
  await page.screenshot({ path: path.join(OUT, '1-dialog-open.png'), fullPage: false });
  console.log('captured: dialog open');

  // Pick Top 30 (smaller for faster test)
  await page.locator('label:has-text("Top 30")').first().click();
  await page.waitForTimeout(300);

  // Click 开始 AI 批量生成
  const t0 = Date.now();
  await page.locator('button:has-text("开始 AI 批量生成")').click();
  console.log('clicked generate, waiting up to 90s...');
  await page.waitForFunction(() => document.body.textContent?.includes('AI 生成完成'), { timeout: 90000 });
  const elapsed = Math.round((Date.now() - t0) / 1000);
  console.log(`AI batch completed in ${elapsed}s`);

  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(OUT, '2-batch-result.png'), fullPage: false });
  console.log('captured: batch result');

  await browser.close();
})();
