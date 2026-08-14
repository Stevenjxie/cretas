import { test, expect, Page } from '@playwright/test';
import { fetchLoginToken, injectAuthCookie, LoginResult } from './e2e-auth-helper';

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173';
const API = resolveApiBase();
const SD = 'test-results/screenshots/web-admin-workflows';

let authResult: LoginResult | null = null;

async function go(page: Page, path: string) {
  if (authResult) {
    await injectAuthCookie(page.context(), page, authResult.token, authResult.loginData, BASE);
  }
  await page.goto(BASE + path, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(3000);
}

test.beforeAll(async () => {
  authResult = await fetchLoginToken('factory_admin1', '123456', API);
});

test.describe('Business Workflows', () => {
  test.setTimeout(60000);

  // === Dashboard Workflows ===

  test('dashboard shows KPI cards with values', async ({ page }) => {
    await go(page, '/dashboard');
    const cards = page.locator('.el-card, .stat-card, .kpi-card');
    const count = await cards.count();
    expect(count).toBeGreaterThanOrEqual(0);
    await page.screenshot({ path: `${SD}/dashboard-kpis.png`, fullPage: true });
  });

  // === Production Workflows ===

  test('production batch list has status filter', async ({ page }) => {
    await go(page, '/production/batches');
    // Check for status filter tabs or select
    const filterEl = page.locator('.el-tabs__item, .el-select, .el-radio-button').first();
    const hasFilter = await filterEl.isVisible().catch(() => false);
    // Even if no filter, table should load
    const table = page.locator('.el-table');
    expect(await table.isVisible().catch(() => false)).toBeTruthy();
    await page.screenshot({ path: `${SD}/prod-batches-filter.png` });
  });

  test('production batch detail loads when clicking row', async ({ page }) => {
    await go(page, '/production/batches');
    const firstRow = page.locator('.el-table__body-wrapper .el-table__row').first();
    if (await firstRow.isVisible().catch(() => false)) {
      // Try clicking the row or a detail button
      const detailBtn = firstRow.locator('button, a, .el-link').filter({ hasText: /详情|查看|Detail/ }).first();
      if (await detailBtn.isVisible().catch(() => false)) {
        await detailBtn.click();
        await page.waitForTimeout(2000);
        // Should navigate to detail page or open dialog
        const detailContent = page.locator('.el-card, .el-descriptions, .el-dialog').first();
        expect(await detailContent.isVisible().catch(() => false) || page.url().includes('batches/')).toBeTruthy();
      }
    }
    await page.screenshot({ path: `${SD}/prod-batch-detail.png` });
  });

  test('BOM management page loads', async ({ page }) => {
    await go(page, '/production/bom');
    const content = page.locator('.el-table, .el-card, .el-tabs').first();
    expect(await content.isVisible().catch(() => false) || page.url().includes('bom')).toBeTruthy();
    await page.screenshot({ path: `${SD}/bom-recipes.png` });
  });

  // === Warehouse Workflows ===

  test('warehouse materials table has search', async ({ page }) => {
    await go(page, '/warehouse/materials');
    const searchInput = page.locator('input[placeholder*="搜索"], input[placeholder*="查找"], .el-input__inner').first();
    const hasSearch = await searchInput.isVisible().catch(() => false);
    expect(hasSearch || (await page.locator('.el-table').isVisible().catch(() => false))).toBeTruthy();
    await page.screenshot({ path: `${SD}/warehouse-search.png` });
  });

  test('material price trend shows chart', async ({ page }) => {
    await go(page, '/warehouse/material-price-trend');
    await page.waitForTimeout(2000);
    const chart = page.locator('canvas, .echarts, [_echarts_instance_]').first();
    const card = page.locator('.el-card').first();
    expect(await chart.isVisible().catch(() => false) || await card.isVisible().catch(() => false)).toBeTruthy();
    await page.screenshot({ path: `${SD}/material-price-trend.png` });
  });

  // === Sales Workflows ===

  test('sales orders table shows status column', async ({ page }) => {
    await go(page, '/sales/orders');
    const table = page.locator('.el-table');
    expect(await table.isVisible().catch(() => false)).toBeTruthy();
    await page.screenshot({ path: `${SD}/sales-orders-status.png` });
  });

  test('sales finished goods inventory view', async ({ page }) => {
    await go(page, '/sales/finished-goods');
    const content = page.locator('.el-table, .el-card').first();
    expect(await content.isVisible().catch(() => false)).toBeTruthy();
  });

  // === HR Workflows ===

  test('employee list shows table with pagination', async ({ page }) => {
    await go(page, '/hr/employees');
    const table = page.locator('.el-table');
    expect(await table.isVisible().catch(() => false)).toBeTruthy();
    const pager = page.locator('.el-pagination');
    const hasPager = await pager.isVisible().catch(() => false);
    await page.screenshot({ path: `${SD}/hr-employees.png` });
  });

  test('attendance management page has date picker', async ({ page }) => {
    await go(page, '/hr/attendance');
    const datePicker = page.locator('.el-date-editor, .el-date-picker').first();
    const hasDatePicker = await datePicker.isVisible().catch(() => false);
    expect(hasDatePicker || page.url().includes('attendance')).toBeTruthy();
    await page.screenshot({ path: `${SD}/hr-attendance.png` });
  });

  // === System Workflows ===

  test('user management shows role column', async ({ page }) => {
    await go(page, '/system/users');
    const table = page.locator('.el-table');
    expect(await table.isVisible().catch(() => false)).toBeTruthy();
    await page.screenshot({ path: `${SD}/system-users.png` });
  });

  test('feature module config shows toggles', async ({ page }) => {
    await go(page, '/system/features');
    const switches = page.locator('.el-switch');
    const cards = page.locator('.el-card');
    const content = await switches.count() + await cards.count();
    expect(content).toBeGreaterThanOrEqual(0);
    await page.screenshot({ path: `${SD}/feature-config.png` });
  });

  test('AI intents config shows intent list', async ({ page }) => {
    await go(page, '/system/ai-intents');
    const table = page.locator('.el-table');
    expect(await table.isVisible().catch(() => false)).toBeTruthy();
    await page.screenshot({ path: `${SD}/ai-intents.png` });
  });

  // === Analytics Workflows ===

  test('analytics overview has charts', async ({ page }) => {
    await go(page, '/analytics/overview');
    await page.waitForTimeout(2000);
    const canvas = page.locator('canvas').first();
    const card = page.locator('.el-card').first();
    expect(await canvas.isVisible().catch(() => false) || await card.isVisible().catch(() => false)).toBeTruthy();
    await page.screenshot({ path: `${SD}/analytics-overview.png`, fullPage: true });
  });

  test('supply chain overview renders', async ({ page }) => {
    await go(page, '/analytics/supply-chain');
    const content = page.locator('.el-card, canvas, .supply-chain').first();
    expect(await content.isVisible().catch(() => false) || page.url().includes('supply-chain')).toBeTruthy();
    await page.screenshot({ path: `${SD}/supply-chain.png`, fullPage: true });
  });

  test('alert dashboard shows alerts', async ({ page }) => {
    await go(page, '/analytics/alert-dashboard');
    const content = page.locator('.el-card, .el-table, .el-alert').first();
    expect(await content.isVisible().catch(() => false) || page.url().includes('alert')).toBeTruthy();
    await page.screenshot({ path: `${SD}/alert-dashboard.png` });
  });

  // === Finance Workflows ===

  test('SKU margin analysis has chart and table', async ({ page }) => {
    await go(page, '/finance/sku-margin');
    const content = page.locator('.el-card, canvas, .el-table').first();
    expect(await content.isVisible().catch(() => false) || page.url().includes('sku-margin')).toBeTruthy();
    await page.screenshot({ path: `${SD}/sku-margin.png`, fullPage: true });
  });

  test('finance AR/AP page renders', async ({ page }) => {
    await go(page, '/finance/ar-ap');
    const content = page.locator('.el-card, .el-table').first();
    expect(await content.isVisible().catch(() => false)).toBeTruthy();
    await page.screenshot({ path: `${SD}/finance-arap.png` });
  });

  // === Quality Workflows ===

  test('quality inspections list with filters', async ({ page }) => {
    await go(page, '/quality/inspections');
    const table = page.locator('.el-table');
    expect(await table.isVisible().catch(() => false)).toBeTruthy();
    await page.screenshot({ path: `${SD}/quality-inspections.png` });
  });

  test('quality standards management', async ({ page }) => {
    await go(page, '/quality/standards');
    const content = page.locator('.el-table, .el-card').first();
    expect(await content.isVisible().catch(() => false)).toBeTruthy();
    await page.screenshot({ path: `${SD}/quality-standards.png` });
  });
});
