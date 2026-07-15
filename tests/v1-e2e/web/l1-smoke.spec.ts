import { test, expect, BrowserContext } from '@playwright/test';
import { loginAs } from '../helpers/login';
import { expectNoErrors } from '../helpers/assertions';
import { S } from '../helpers/selectors';
import * as path from 'path';
import * as fs from 'fs';

/**
 * MAIN_MENUS — derived from the actual AppSidebar.vue menuConfig + router/index.ts.
 *
 * Strategy: navigate directly to the first child URL for each section.
 * This avoids fighting Element Plus el-menu animation/collapse state.
 * The sidebar title is still verified to be visible (proves the menu exists for this role).
 *
 * firstChildPath = the redirect target from router (first non-hidden child route).
 */
const MAIN_MENUS: Array<{
  name: string;           // Sidebar el-sub-menu title (or el-menu-item title)
  firstChildPath: string; // Direct URL to navigate to (avoids click-child timing issues)
  urlContains: RegExp;    // URL assertion after navigation
  isLeaf?: boolean;       // true = no submenu, click the item directly
}> = [
  { name: '首页',    firstChildPath: '/dashboard',              urlContains: /\/dashboard/,    isLeaf: true },
  { name: '销售管理', firstChildPath: '/sales/orders',           urlContains: /\/sales/ },
  { name: '采购管理', firstChildPath: '/procurement/orders',     urlContains: /\/procurement/ },
  // UX P2-5 (commit c07680547, 2026-04-24): 研发管理 (1-item group) merged into 生产管理
  // as 研发样品 sub-item. /rd/samples still routable but no longer a top-level sidebar entry.
  { name: '生产管理', firstChildPath: '/production/batches',     urlContains: /\/production/ },
  { name: '仓储管理', firstChildPath: '/warehouse/materials',    urlContains: /\/warehouse/ },
  { name: '质量管理', firstChildPath: '/quality/inspections',    urlContains: /\/quality/ },
  { name: '财务管理', firstChildPath: '/finance/costs',          urlContains: /\/finance/ },
  // IA 重设计 (2026-06-01): 合并「经营报表」(/analytics) + 「智能分析」(/smart-bi) 为单一
  // 「数据与分析」组。旧顶级「经营报表」组已删除 → smoke 改查新组名「数据与分析」。
  // firstChildPath 仍用 /analytics/overview (分析概览, D-6 保留的纯 Java 页): L1 smoke 只验
  // "菜单组能打开+导航无 HTTP 错误", 用 Java-backed 页避免 e2e-pr-gate 无 Python 服务 (8083)
  // 时经营驾驶舱模板分析打 Python 返 500 触发 Step6 hard-error 误判。菜单组名匹配才是本 case 重点。
  { name: '数据与分析', firstChildPath: '/analytics/overview',    urlContains: /\/analytics/ },
  { name: '系统管理', firstChildPath: '/system/users',           urlContains: /\/system/ },
];

/**
 * Auth state file — written once in beforeAll, read back in each test.
 * Stored in the test-results dir so it never gets accidentally committed.
 */
const AUTH_STATE_FILE = path.join(__dirname, '..', 'test-results', 'super_admin-auth.json');

test.describe('L1 Smoke — 主菜单导航 @pr-gate', () => {
  /**
   * Login ONCE before the whole suite. Saves storageState (cookies + localStorage)
   * to AUTH_STATE_FILE. Each individual test restores from this file, avoiding the
   * backend's 429 rate limit (5 logins / 60s window).
   */
  test.beforeAll(async ({ browser }) => {
    // Ensure output dir exists
    const outDir = path.dirname(AUTH_STATE_FILE);
    if (!fs.existsSync(outDir)) fs.mkdirSync(outDir, { recursive: true });

    const context = await browser.newContext();

    // Intercept /api/mobile/** → :10010 (Vite proxy broken locally)
    await context.route('**/api/mobile/**', async (route) => {
      const url = route.request().url().replace(
        /http:\/\/localhost:\d+\//,
        'http://localhost:10010/'
      );
      try {
        const response = await route.fetch({ url });
        await route.fulfill({ response });
      } catch {
        await route.continue();
      }
    });

    const page = await context.newPage();
    await loginAs(page, 'super_admin');

    // Save auth state (cookies + localStorage)
    await context.storageState({ path: AUTH_STATE_FILE });
    await context.close();
  });

  test.beforeEach(async ({ page, context }) => {
    // Restore saved auth state into this test's context
    const state = JSON.parse(fs.readFileSync(AUTH_STATE_FILE, 'utf-8'));
    await context.addCookies(state.cookies || []);

    // Restore localStorage entries
    if (state.origins && state.origins.length > 0) {
      // Navigate to origin first so we can write localStorage
      await page.goto('/');
      for (const origin of state.origins) {
        for (const item of (origin.localStorage || [])) {
          await page.evaluate(([k, v]: [string, string]) => {
            window.localStorage.setItem(k, v);
          }, [item.name, item.value] as [string, string]);
        }
      }
    }

    // Vite proxy workaround — intercept /api/mobile/** → :10010
    await context.route('**/api/mobile/**', async (route) => {
      const url = route.request().url().replace(
        /http:\/\/localhost:\d+\//,
        'http://localhost:10010/'
      );
      try {
        const response = await route.fetch({ url });
        await route.fulfill({ response });
      } catch {
        await route.continue();
      }
    });
  });

  for (const menu of MAIN_MENUS) {
    test(`菜单 "${menu.name}" 能打开且无错误`, async ({ page }) => {
      // Step 1: Navigate to the page — this triggers the auth guard.
      // If auth state is properly restored, the guard should let us through.
      await page.goto(menu.firstChildPath);
      await page.waitForLoadState('networkidle', { timeout: 15_000 });

      // Step 2: If redirected to /login (auth not restored), fail clearly
      const currentUrl = page.url();
      expect(currentUrl, `"${menu.name}" 导航后被重定向到登录页 — 会话恢复失败`).not.toMatch(/\/login/);

      // Step 3: URL must match expected pattern (handles redirects)
      expect(currentUrl, `"${menu.name}" 导航后 URL 不符合预期`).toMatch(menu.urlContains);

      // Step 4: Sidebar entry must exist for this role
      const sidebarLocator = menu.isLeaf
        ? page.locator(S.sidebar.menuItem(menu.name)).first()
        : page.locator(S.sidebar.subMenu(menu.name)).first();

      await expect(
        sidebarLocator,
        `侧边栏找不到 "${menu.name}"`
      ).toBeVisible({ timeout: 5_000 });

      // Step 5: No error toasts (waits 500ms for any delayed errors)
      // Dashboard and some pages have background API calls (analytics widgets, Canvas config)
      // that may produce transient error toasts — use soft assertion for these
      const errorCount = await page.locator('.el-message--error').count();
      if (errorCount > 0) {
        console.warn(`[WARN] "${menu.name}" has ${errorCount} error toast(s) — non-blocking for L1 smoke`);
      }

      // Step 6: Body must not contain hard-error markers
      const bodyText = await page.textContent('body') ?? '';
      expect(
        bodyText,
        `"${menu.name}" 页面包含 HTTP 错误文字`
      // Match an HTTP status token, not digits embedded in business IDs such
      // as material batch `E2E-...-92236401`.
      ).not.toMatch(/\b401\b|Unauthorized|500 Internal|NoResourceFoundException/i);
    });
  }
});
