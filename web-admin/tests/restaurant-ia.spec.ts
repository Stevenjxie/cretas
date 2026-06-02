/**
 * 餐饮运营组 IA v2 — headed E2E (Task 6 verification).
 *
 * 验证 2026-06-02 「餐饮运营」组重组的 3 件事:
 *   1. 「餐饮运营」组重组为 3 层 (深度分析/日常录入/数据与系统):
 *      - 深度分析: 菜品分析 (/restaurant/analytics/dishes) / 门店对比
 *        (/restaurant/analytics/stores) / 平台口碑 (/restaurant/analytics/platform)
 *      - 日常录入: 配方管理 / 领料管理 / 损耗管理 / 盘点管理
 *      - 数据与系统: 数据完整度 / ETL 状态
 *      移除项: 运营总览 / 菜品四象限 / 菜品毛利分析 / 点评。
 *   2. 旧路由 redirect (函数式, 保留 query):
 *      - /restaurant/analytics → /smart-bi/dashboard
 *      - /restaurant/analytics/menu → /restaurant/analytics/dishes?tab=quadrant
 *      - /restaurant/analytics/gross-margin → /restaurant/analytics/dishes?tab=margin
 *      - /restaurant/analytics/dianping → /restaurant/analytics/platform
 *   3. 平台口碑页 (platform.vue) 明标「本页需接入大众点评 / 美团平台数据」
 *      (禁假数据 — 防呆 Rule 5: 空状态附 next-action, 不编造口碑数据)。
 *
 * Headed per .claude/rules/playwright-headed-mode.md (project 配置在 playwright.config.ts
 * 设 headless:false + zh-CN locale + 1920x1080 viewport — 见 controller 添加的
 * restaurant-ia project)。本 spec 不自带 launchOptions, 由 project / CLI 注入 headed config。
 *
 * 凭证 (per memory reference_prod_no_real_customers_yet):
 *   餐饮: qhj_prod / 123456 (RES_3101_009)
 *
 * 复用现有 e2e-auth-helper (fetchLoginToken + injectAuthCookie) 而非 UI 登录 —
 * 与 e2e-ia-redesign.spec.ts / revenue-report-smoke.spec.ts 同模式。登录 API 返回的
 * factoryType 会写入 cretas_user → 驱动 AppSidebar 的业态门控 (hideForFactoryTypes)。
 *
 * 数据前提 (2026-06-02 实测确认):
 *   「餐饮运营」顶级组 hideForFactoryTypes:['FACTORY'] → 仅 factoryType=RESTAURANT 的
 *   租户可见。qhj_prod 经 #372 修正后在测试后端 (gateway 8097) 已正确解析为
 *   factoryId=RES_3101_009 / factoryType=RESTAURANT (实测 unified-login 返回值 +
 *   注入 cretas_user → authStore.factoryType=RESTAURANT → AppSidebar 渲染该组,
 *   含菜品分析/门店对比/平台口碑/配方/领料/损耗/盘点/数据完整度/ETL 全 9 项)。
 *   故 sidebar 测试**断言**该组渲染 (不再 skip) —— 若该组缺失即真失败 (账号业态错 /
 *   代码未部署), 是诚实信号, 不静默降级。
 *   注: 等 Vue 侧边栏挂载用 auto-waiting `expect(...).toBeVisible()`, 不用 `isVisible()`
 *   瞬时检查 (会在渲染前误判 false)。
 *
 * Java 对每个 username 有 60s 登录限流, 故 beforeAll 登录一次, 整个 spec 共享 token。
 */
import { test, expect, type Page, type BrowserContext } from '@playwright/test';
import { fetchLoginToken, injectAuthCookie, type LoginResult } from '../e2e-auth-helper';

const BASE_URL = process.env.E2E_BASE_URL || 'http://139.196.165.140:8097';
const API_BASE = process.env.E2E_API_BASE || `${BASE_URL}/api/mobile`;
// 餐饮租户 (期望 factoryType=RESTAURANT)
const REST_USER = process.env.E2E_REST_USER || 'qhj_prod';
const REST_PASS = process.env.E2E_REST_PASS || '123456';

let restAuth: LoginResult;

test.beforeAll(async () => {
  restAuth = await fetchLoginToken(REST_USER, REST_PASS, API_BASE);
  expect(restAuth.token, 'restaurant login token').toBeTruthy();
});

/**
 * Inject auth (cookie + cretas_user localStorage incl. factoryType + token in
 * localStorage for the request interceptor), navigate to dashboard.
 * Mirrors e2e-ia-redesign.spec.ts gotoSidebar.
 */
async function injectRest(page: Page, context: BrowserContext) {
  await injectAuthCookie(context, page, restAuth.token, restAuth.loginData, BASE_URL);
  // 请求拦截器也从 localStorage 读 cretas_access_token (per reference_web_admin_token_key)。
  await page.evaluate((tok) => {
    localStorage.setItem('cretas_access_token', tok);
  }, restAuth.token);
}

async function gotoRestaurant(page: Page, context: BrowserContext) {
  await injectRest(page, context);
  await page.goto(`${BASE_URL}/dashboard`, { waitUntil: 'load', timeout: 30_000 });
}

test.describe('餐饮运营组 IA v2 (headed 餐饮租户)', () => {
  test('餐饮运营组呈 3 层, 含菜品分析/平台口碑, 无运营总览/菜品两项', async ({ page, context }) => {
    await gotoRestaurant(page, context);

    // 「餐饮运营」组标题 (el-sub-menu__title)。qhj_prod=RESTAURANT → 必渲染。
    // auto-waiting 断言等 Vue 侧边栏挂载 (不用 isVisible() 瞬时检查)。
    const groupTitle = page.locator('.el-sub-menu__title', { hasText: '餐饮运营' });
    await expect(groupTitle).toBeVisible({ timeout: 15_000 });

    // 确保组展开 (默认折叠则点开), 使子 el-menu-item 进入可见态
    const dishItem = page.getByRole('menuitem', { name: '菜品分析', exact: true });
    if (!(await dishItem.isVisible().catch(() => false))) {
      await groupTitle.click();
      await page.waitForTimeout(600);
    }

    // 重组后子项可见 (深度分析: 菜品分析/门店对比/平台口碑; 日常录入: 配方/领料/损耗/盘点)
    for (const name of ['菜品分析', '门店对比', '平台口碑', '配方管理', '领料管理', '损耗管理', '盘点管理']) {
      await expect(page.getByRole('menuitem', { name, exact: true })).toBeVisible();
    }

    // 移除项不应出现
    await expect(page.getByRole('menuitem', { name: '运营总览' })).toHaveCount(0);
    await expect(page.getByRole('menuitem', { name: '菜品四象限' })).toHaveCount(0);
    await expect(page.getByRole('menuitem', { name: '菜品毛利分析' })).toHaveCount(0);

    await page.screenshot({ path: 'test-results/restaurant-ia-sidebar.png', fullPage: true });
  });

  test('旧路由 redirect: analytics→驾驶舱, menu→dishes?tab=quadrant, gross-margin→dishes?tab=margin, dianping→platform', async ({ page, context }) => {
    await injectRest(page, context);

    // /restaurant/analytics → /smart-bi/dashboard (旧运营总览复用经营驾驶舱)
    await page.goto(`${BASE_URL}/restaurant/analytics`, { waitUntil: 'load', timeout: 30_000 });
    await expect(page).toHaveURL(/\/smart-bi\/dashboard/);

    // /restaurant/analytics/menu → /restaurant/analytics/dishes?tab=quadrant
    await page.goto(`${BASE_URL}/restaurant/analytics/menu`, { waitUntil: 'load', timeout: 30_000 });
    await expect(page).toHaveURL(/\/restaurant\/analytics\/dishes\?tab=quadrant/);

    // /restaurant/analytics/gross-margin → /restaurant/analytics/dishes?tab=margin
    await page.goto(`${BASE_URL}/restaurant/analytics/gross-margin`, { waitUntil: 'load', timeout: 30_000 });
    await expect(page).toHaveURL(/\/restaurant\/analytics\/dishes\?tab=margin/);

    // /restaurant/analytics/dianping → /restaurant/analytics/platform
    await page.goto(`${BASE_URL}/restaurant/analytics/dianping`, { waitUntil: 'load', timeout: 30_000 });
    await expect(page).toHaveURL(/\/restaurant\/analytics\/platform/);
  });

  test('平台口碑页明标需接平台数据 (禁假数据)', async ({ page, context }) => {
    await injectRest(page, context);
    await page.goto(`${BASE_URL}/restaurant/analytics/platform`, { waitUntil: 'load', timeout: 30_000 });
    await expect(page.getByText('需接入大众点评 / 美团平台数据')).toBeVisible();
    await page.screenshot({ path: 'test-results/restaurant-ia-platform.png', fullPage: true });
  });
});
