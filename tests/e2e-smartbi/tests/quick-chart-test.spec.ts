/**
 * Quick chart rendering test after composable refactoring.
 * Validates: upload (or existing data) → tab switch → chart render → enrichment.
 */
import { test, expect } from '@playwright/test';
import path from 'path';

const BASE_URL = 'http://localhost:5174';
const TEST_FILE = path.resolve(__dirname, '../../../Test.xlsx');
const SS_DIR = path.resolve(__dirname, '../../../test-screenshots');

test('SmartBI upload + chart render', async ({ page }) => {
  test.setTimeout(180_000);

  const jsErrors: string[] = [];
  const consoleLogs: string[] = [];
  page.on('pageerror', (err) => jsErrors.push(err.message));
  page.on('console', (msg) => {
    const text = msg.text();
    if (text.includes('enrich') || text.includes('Enrich') || text.includes('chart') ||
        text.includes('Chart') || text.includes('batch') || text.includes('KPI') ||
        text.includes('insight') || text.includes('recommend') || text.includes('cache') ||
        text.includes('DashScope') || text.includes('403') || text.includes('失败') ||
        text.includes('factoryId') || text.includes('Factory')) {
      consoleLogs.push(`[${msg.type()}] ${text.slice(0, 300)}`);
    }
  });

  // 1. Login
  await page.goto(`${BASE_URL}/login`);
  await page.fill('input[placeholder*="用户名"]', 'factory_admin1');
  await page.fill('input[placeholder="请输入密码"]', '123456');
  await page.click('button:has-text("登 录")');
  await page.waitForURL('**/dashboard**', { timeout: 10_000 }).catch(() => {});
  await page.waitForTimeout(1000);

  // 2. Navigate to SmartBI analysis
  await page.goto(`${BASE_URL}/smart-bi/analysis`);
  await page.waitForTimeout(2000);

  // 3. Check if data already exists from a previous upload
  const hasExistingData = await page.locator('text=/成功处理.*Sheet/').isVisible().catch(() => false);
  const hasUploadZone = await page.locator('text=拖拽 Excel 文件到此处').isVisible().catch(() => false);

  if (hasExistingData) {
    console.log('Existing upload found, skipping upload step');
  } else if (hasUploadZone) {
    console.log('Upload zone visible, uploading Test.xlsx...');
    await page.locator('input[type="file"]').setInputFiles(TEST_FILE);
    await page.waitForTimeout(500);
    await page.locator('button:has-text("开始分析")').click();

    const successBanner = page.locator('text=/成功处理.*Sheet/');
    await expect(successBanner).toBeVisible({ timeout: 90_000 });
    console.log('Upload:', await successBanner.textContent());
  } else {
    // Maybe the page is still loading
    await page.waitForTimeout(3000);
    console.log('Page state unclear, taking screenshot...');
    await page.screenshot({ path: path.join(SS_DIR, 'e2e-page-state.png') });
  }

  // 4. Verify tabs exist
  const tabs = page.locator('.el-tabs__item');
  await expect(tabs.first()).toBeVisible({ timeout: 10_000 });
  const tabCount = await tabs.count();
  console.log('Tabs:', tabCount);
  expect(tabCount).toBeGreaterThanOrEqual(2);

  // 5. Click first data sheet tab (tab-0 is the first data sheet)
  const firstTab = tabs.first();
  await firstTab.click();
  await page.waitForTimeout(1000);

  // Find the active tab pane
  const activePane = page.locator('.el-tab-pane').first();
  await page.screenshot({ path: path.join(SS_DIR, 'e2e-step5-active-tab.png') });

  // 6. Wait for enrichment to complete (charts/KPIs appear)
  console.log('Waiting for enrichment (charts/KPIs)...');
  const chartIndicator = page.locator('canvas, .dynamic-chart-card, .chart-card, .kpi-card');
  try {
    await expect(chartIndicator.first()).toBeVisible({ timeout: 120_000 });
    console.log('Chart/KPI content rendered!');
  } catch {
    console.log('No chart/KPI content after 120s');
  }

  await page.waitForTimeout(5000); // Extra settle time for all charts to render

  // 7. Take enriched screenshot
  await page.screenshot({ path: path.join(SS_DIR, 'e2e-step7-enriched.png'), fullPage: true });

  // 8. Count visible elements
  const canvasCount = await page.locator('canvas').count();
  const chartCards = await page.locator('.dynamic-chart-card, .chart-card').count();
  const kpiCards = await page.locator('.kpi-card').count();
  console.log(`Enrichment results: canvases=${canvasCount}, chartCards=${chartCards}, kpiCards=${kpiCards}`);

  // Check chart count text
  const chartCountText = await page.locator('text=/\\d+ 个图表/').textContent().catch(() => 'N/A');
  console.log('Chart count text:', chartCountText);

  // Check AI analysis text content
  const aiSection = page.locator('.ai-analysis-section, .ai-section');
  const aiText = await aiSection.first().textContent().catch(() => '');
  const aiPreview = (aiText || '').slice(0, 300);
  console.log('AI text preview:', aiPreview);

  // 9. Scroll down and screenshot
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(SS_DIR, 'e2e-step9-scrolled.png'), fullPage: true });

  // 10. Report JS errors
  const criticalErrors = jsErrors.filter(e =>
    !e.includes('Factory ID unavailable') &&
    !e.includes('insertBefore')
  );
  console.log('Critical JS errors:', criticalErrors.length ? criticalErrors : 'none');
  console.log('Total JS errors:', jsErrors.length);

  // 11. Print enrichment console logs
  console.log(`\n=== Console Logs (${consoleLogs.length}) ===`);
  for (const log of consoleLogs.slice(0, 40)) {
    console.log(log);
  }

  // 12. Assertions
  // At minimum, we expect some canvas elements from chart rendering
  expect(canvasCount + chartCards).toBeGreaterThanOrEqual(1);
});
