// AI 智能录配方 端到端测试
import { chromium } from '@playwright/test';
import path from 'path';

const BASE = 'https://admin.cretaceousfuture.com';
const OUT = path.resolve('tests/e2e-comprehensive/results/ai-draft');
import fs from 'fs';
fs.mkdirSync(OUT, { recursive: true });

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();

  await page.goto(BASE);
  await page.locator('input[placeholder*="用户名"]').fill('qhj_prod');
  await page.locator('input[placeholder="请输入密码"]').fill('123456');
  await page.locator('button.login-button').click();
  await page.waitForURL((u) => !new URL(String(u)).pathname.startsWith('/login'), { timeout: 30000 });

  await page.goto(`${BASE}/restaurant/recipes`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(3000);

  // Click AI 智能录配方 button
  await page.locator('button:has-text("AI 智能录配方")').click();
  await page.waitForTimeout(1500);

  await page.screenshot({ path: path.join(OUT, 'ai-draft-empty.png'), fullPage: false });
  console.log('captured: empty dialog');

  // Fill dish name + generate
  await page.locator('input[placeholder*="宫保鸡丁"]').fill('水煮肉片');
  await page.locator('button:has-text("AI 生成草稿")').click();
  console.log('clicked generate, waiting for LLM...');
  await page.waitForTimeout(20000);

  await page.screenshot({ path: path.join(OUT, 'ai-draft-result.png'), fullPage: false });
  console.log('captured: result');

  // Check if result shows
  const bodyText = await page.locator('.el-dialog__body').textContent();
  console.log('Dialog content snippet:', bodyText?.substring(0, 500));

  await browser.close();
})();
