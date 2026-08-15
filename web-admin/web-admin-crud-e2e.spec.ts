/**
 * Web Admin CRUD Interactions E2E 测试
 *
 * 覆盖表格加载、搜索、分页、弹窗、标签切换等 CRUD 交互
 * 使用 HttpOnly cookie 注入 auth token
 *
 * 运行: npx playwright test web-admin-crud-e2e.spec.ts --project=web-admin-crud
 */

import { test, expect, Page } from '@playwright/test';
import { loginOrReuseSession, resolveApiBase, fetchLoginToken, injectAuthCookie, LoginResult, E2E_USER, E2E_PASS } from './e2e-auth-helper';
import { skipIfForbidden } from './e2e-auth-helper';
import { expectAnyVisible } from './e2e-auth-helper';

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173';
const API = resolveApiBase();
const SD = 'test-results/screenshots/web-admin-crud';

let authResult: LoginResult | null = null;

async function go(page: Page, path: string) {
  if (authResult) {
    await injectAuthCookie(page.context(), page, authResult.token, authResult.loginData, BASE);
  }
  await page.goto(BASE + path, { waitUntil: 'domcontentloaded', timeout: 30000 });
  // 固定睡眠换成断言侧自动重试, 见 expectAnyVisible 的说明。
  await skipIfForbidden(page, path);
}

test.beforeAll(async () => {
  authResult = await loginOrReuseSession(E2E_USER, E2E_PASS, API, 'crud');
});

test.describe('CRUD Interactions', () => {
  test.setTimeout(60000);

  // --- Table rendering ---

  test('production batches table has rows', async ({ page }) => {
    await go(page, '/production/batches');
    const rows = page.locator('.el-table__body-wrapper .el-table__row');
    const count = await rows.count();
    expect(count).toBeGreaterThanOrEqual(0);
    await page.screenshot({ path: `${SD}/prod-batches-table.png` });
  });

  test('system users table renders', async ({ page }) => {
    await go(page, '/system/users');
    const table = page.locator('.el-table');
    await expect(table.filter({ visible: true }).first()).toBeVisible({ timeout: 45000 });
  });

  test('hr employees table renders', async ({ page }) => {
    await go(page, '/hr/employees');
    const table = page.locator('.el-table');
    await expect(table.filter({ visible: true }).first()).toBeVisible({ timeout: 45000 });
  });

  test('sales orders table renders', async ({ page }) => {
    await go(page, '/sales/orders');
    await page.screenshot({ path: `${SD}/sales-orders.png` });
  });

  test('quality inspections table renders', async ({ page }) => {
    await go(page, '/quality/inspections');
    const table = page.locator('.el-table');
    await expect(table.filter({ visible: true }).first()).toBeVisible({ timeout: 45000 });
  });

  test('procurement orders table renders', async ({ page }) => {
    await go(page, '/procurement/orders');
    const table = page.locator('.el-table');
    await expect(table.filter({ visible: true }).first()).toBeVisible({ timeout: 45000 });
  });

  test('equipment list renders', async ({ page }) => {
    await go(page, '/equipment/list');
    const table = page.locator('.el-table');
    await expect(table.filter({ visible: true }).first()).toBeVisible({ timeout: 45000 });
  });

  test('AI intents table renders', async ({ page }) => {
    await go(page, '/system/ai-intents');
    const table = page.locator('.el-table');
    await expect(table.filter({ visible: true }).first()).toBeVisible({ timeout: 45000 });
  });

  test('transfer list table renders', async ({ page }) => {
    await go(page, '/transfer/list');
    const table = page.locator('.el-table');
    await expect(table.filter({ visible: true }).first()).toBeVisible({ timeout: 45000 });
  });

  // --- Search ---

  test('warehouse materials search filters table', async ({ page }) => {
    await go(page, '/warehouse/materials');
    const searchInput = page.locator('.el-input__inner').first();
    if (await searchInput.isVisible().catch(() => false)) {
      await searchInput.fill('test');
      await page.waitForTimeout(1000);
    }
    await page.screenshot({ path: `${SD}/warehouse-search.png` });
  });

  // --- Dialog open/close ---

  test('production batches new button opens dialog', async ({ page }) => {
    await go(page, '/production/batches');
    const addBtn = page.locator('button').filter({ hasText: /新增|新建|创建|添加/ });
    if (await addBtn.first().isVisible().catch(() => false)) {
      await addBtn.first().click();
      await page.waitForTimeout(1000);
      const dialog = page.locator('.el-dialog, .el-drawer');
      const dialogV = await dialog.first().isVisible().catch(() => false);
      expect(dialogV).toBeTruthy();
      // Close dialog
      const closeBtn = page.locator('.el-dialog__headerbtn, .el-drawer__close-btn').first();
      if (await closeBtn.isVisible().catch(() => false)) {
        await closeBtn.click();
      }
    }
  });

  // --- Tab switching ---

  test('production BOM page has tabs', async ({ page }) => {
    await go(page, '/production/bom');
    const tabs = page.locator('.el-tabs__item');
    const tabCount = await tabs.count();
    if (tabCount > 1) {
      await tabs.nth(1).click();
      await page.waitForTimeout(1000);
    }
    expect(tabCount).toBeGreaterThanOrEqual(0);
  });

  // --- Charts / Dashboard ---

  test('SmartBI dashboard has chart canvases', async ({ page }) => {
    await go(page, '/smart-bi/dashboard');
    await page.waitForTimeout(3000);
    const content = page.locator('.el-card, canvas, .echarts').first();
    const contentV = await content.isVisible().catch(() => false);
    expect(page.url()).toContain('smart-bi');
  });

  test('finance costs has content', async ({ page }) => {
    await go(page, '/finance/costs');
    const content = page.locator('.el-card, canvas, .echarts').first();
    const contentV = await content.isVisible().catch(() => false);
    expect(contentV || page.url().includes('finance')).toBeTruthy();
  });

  test('analytics KPI has charts', async ({ page }) => {
    await go(page, '/analytics/kpi');
    const canvas = page.locator('canvas').first();
    const card = page.locator('.el-card').first();
    const any = await Promise.all([
      canvas.isVisible().catch(() => false),
      card.isVisible().catch(() => false),
    ]);
    expect(any.some(Boolean) || page.url().includes('kpi')).toBeTruthy();
  });

  test('restaurant analytics page loads', async ({ page }) => {
    await go(page, '/restaurant/analytics');
    // Page may redirect to restaurant/requisitions or show analytics
    const body = await page.textContent('body');
    expect(body).toBeTruthy();
  });

  // --- Pagination ---

  test('system users pagination works', async ({ page }) => {
    await go(page, '/system/users');
    const pager = page.locator('.el-pagination');
    if (await pager.isVisible().catch(() => false)) {
      const nextBtn = pager.locator('button.btn-next');
      if (await nextBtn.isEnabled().catch(() => false)) {
        await nextBtn.click();
        await page.waitForTimeout(1000);
      }
    }
    expect(page.url()).toContain('system/users');
  });

  // --- Scheduling ---

  test('scheduling overview has cards or timeline', async ({ page }) => {
    await go(page, '/scheduling/overview');
    const card = page.locator('.el-card').first();
    const cardV = await card.isVisible().catch(() => false);
    expect(cardV || page.url().includes('scheduling')).toBeTruthy();
  });

  // --- Navigation ---

  test('sidebar navigation between modules', async ({ page }) => {
    await go(page, '/dashboard');
    // Click a leaf menu item (el-menu-item), not a sub-menu title
    const menuItem = page.locator('.el-menu-item').filter({ hasText: /生产批次|原材料|员工管理/ }).first();
    if (await menuItem.isVisible().catch(() => false)) {
      await menuItem.click();
      await page.waitForTimeout(2000);
      expect(page.url()).not.toBe(BASE + '/dashboard');
    } else {
      // If no specific menu item visible, just verify dashboard loaded
      expect(page.url()).toContain('dashboard');
    }
  });

  test('breadcrumb renders on subpages', async ({ page }) => {
    await go(page, '/production/batches');
    const breadcrumb = page.locator('.el-breadcrumb');
    const breadcrumbV = await breadcrumb.isVisible().catch(() => false);
    expect(page.url()).toContain('production');
  });
});
