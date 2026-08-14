import { test, expect } from '@playwright/test';
import path from 'path';
import fs from 'fs';

const SCREENSHOT_DIR = path.resolve(__dirname, '../../../test-screenshots/smartbi-audit');

// Ensure directory exists
if (!fs.existsSync(SCREENSHOT_DIR)) {
  fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });
}

function ss(name: string): string {
  return path.join(SCREENSHOT_DIR, name);
}

// Single test that does everything - avoids page state reset between serial tests
test('Take all SmartBI screenshots', async ({ page }) => {
  // ========================================
  // Step 0: Login
  // ========================================
  console.log('=== Logging in ===');
  await page.goto('/login');
  await page.waitForLoadState('networkidle');

  // Fill login form
  const usernameInput = page.locator('input[placeholder*="用户名"]');
  const passwordInput = page.locator('input[placeholder="请输入密码"]');

  if (await usernameInput.isVisible({ timeout: 5000 }).catch(() => false)) {
    await usernameInput.fill('factory_admin1');
    await passwordInput.fill('123456');
  } else {
    const inputs = page.locator('.login-form input, .el-form input');
    await inputs.nth(0).fill('factory_admin1');
    await inputs.nth(1).fill('123456');
  }

  // Click login
  const loginBtn = page.locator('button.login-button, button[type="submit"]').first();
  if (await loginBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
    await loginBtn.click();
  } else {
    await page.locator('button').filter({ hasText: /登.*录|Login/i }).first().click();
  }

  // Wait for redirect
  await page.waitForURL(url => !url.pathname.includes('/login'), { timeout: 15000 });
  console.log('Login successful, URL:', page.url());
  expect(page.url()).not.toContain('/login');

  // Wait for dashboard to fully load
  await page.waitForTimeout(3000);

  // ========================================
  // 1. General Dashboard (首页)
  // ========================================
  console.log('=== Screenshot 01: Dashboard ===');
  await page.goto('/dashboard');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);

  await page.screenshot({ path: ss('01-dashboard.png'), fullPage: false });
  console.log('  01-dashboard.png captured');

  // Scroll down for bottom half
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await page.waitForTimeout(1500);
  await page.screenshot({ path: ss('01-dashboard-bottom.png'), fullPage: false });
  console.log('  01-dashboard-bottom.png captured');

  // ========================================
  // 2. SmartBI Dashboard (经营驾驶舱)
  // ========================================
  console.log('=== Screenshot 02: SmartBI Dashboard ===');
  await page.goto('/smart-bi/dashboard');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(5000); // Wait for data to load

  await page.screenshot({ path: ss('02-smartbi-dashboard.png'), fullPage: false });
  console.log('  02-smartbi-dashboard.png captured');

  // Scroll down for bottom
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await page.waitForTimeout(1500);
  await page.screenshot({ path: ss('02-smartbi-dashboard-bottom.png'), fullPage: false });
  console.log('  02-smartbi-dashboard-bottom.png captured');

  // ========================================
  // 3. SmartBI Analysis (智能数据分析)
  // ========================================
  console.log('=== Screenshot 03: SmartBI Analysis ===');
  await page.goto('/smart-bi/analysis');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(10000); // Wait for enrichment to load

  // Scroll to top first
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(500);

  // Top - KPIs + sheet tabs
  await page.screenshot({ path: ss('03-analysis-top.png'), fullPage: false });
  console.log('  03-analysis-top.png captured');

  // Scroll to charts area
  await page.evaluate(() => window.scrollBy(0, 700));
  await page.waitForTimeout(2000);
  await page.screenshot({ path: ss('03-analysis-charts.png'), fullPage: false });
  console.log('  03-analysis-charts.png captured');

  // Scroll more to AI analysis
  await page.evaluate(() => window.scrollBy(0, 900));
  await page.waitForTimeout(2000);
  await page.screenshot({ path: ss('03-analysis-ai.png'), fullPage: false });
  console.log('  03-analysis-ai.png captured');

  console.log('=== All screenshots captured ===');
});
