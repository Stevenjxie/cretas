/**
 * Sidebar IA-redesign — 双业态 E2E (Task 7 final verification).
 *
 * 验证 2026-06-01 「经营报表」(/analytics) + 「智能分析」(/smart-bi) 合并为单一
 * 「数据与分析」组 (path /smart-bi) 后的 3 件事:
 *   1. 制造租户 (FACTORY/F001): 只剩一个「数据与分析」组, 旧「经营报表」组消失,
 *      经营驾驶舱置顶, 制造专属项 (车间实时生产报表) 可见。
 *   2. 餐饮租户 (RESTAURANT/RES_3101_009): 制造专属项 (车间实时生产报表 / 生产数据分析 /
 *      人效分析 / 进销存总览) 隐藏; 趋势分析 + KPI 看板 不门控 (双业态都可见);
 *      收入管理报表 (仅餐饮) 可见。
 *   3. 重定向: /analytics → /smart-bi/dashboard; /smart-bi/query → analysis?tab=query;
 *      /smart-bi/finance → financial-dashboard?tab=analysis。
 *
 * Headed per .claude/rules/playwright-headed-mode.md (project 配置在 playwright.config.ts
 * 里设 headless:false + zh-CN locale + 1920x1080 viewport — 见 controller 添加的 project)。
 * 本 spec 不自带 launchOptions, 由 project / CLI 注入 headed config。
 *
 * 凭证 (per memory reference_prod_no_real_customers_yet):
 *   制造: factory_admin1 / 123456 (F001)
 *   餐饮: qhj_prod / 123456 (RES_3101_009)
 *
 * 复用现有 e2e-auth-helper (fetchLoginToken + injectAuthCookie) 而非 UI 登录 —
 * 与 revenue-report-smoke.spec.ts 等同模式, 且登录 API 返回的 factoryType 会写入
 * cretas_user → 驱动 AppSidebar 的业态门控 (hideForFactoryTypes)。
 *
 * Java 对每个 username 有 60s 登录限流, 故 beforeAll 各登录一次, 整个 spec 共享 token。
 */
import { test, expect, type Page, type BrowserContext } from '@playwright/test';
import { fetchLoginToken, injectAuthCookie, type LoginResult } from '../e2e-auth-helper';

const BASE_URL = process.env.E2E_BASE_URL || 'http://139.196.165.140:8097';
const API_BASE = process.env.E2E_API_BASE || `${BASE_URL}/api/mobile`;

const FACTORY_USER = process.env.E2E_FACTORY_USER || 'factory_admin1';
const FACTORY_PASS = process.env.E2E_FACTORY_PASS || '123456';
const RESTAURANT_USER = process.env.E2E_RESTAURANT_USER || 'qhj_prod';
const RESTAURANT_PASS = process.env.E2E_RESTAURANT_PASS || '123456';

// Shared login results (Java 60s per-username rate limit → log in once each).
let factoryAuth: LoginResult;
let restaurantAuth: LoginResult;

test.beforeAll(async () => {
  factoryAuth = await fetchLoginToken(FACTORY_USER, FACTORY_PASS, API_BASE);
  expect(factoryAuth.token, 'factory login token').toBeTruthy();
  restaurantAuth = await fetchLoginToken(RESTAURANT_USER, RESTAURANT_PASS, API_BASE);
  expect(restaurantAuth.token, 'restaurant login token').toBeTruthy();
});

/**
 * Inject auth (cookie + cretas_user localStorage incl. factoryType), navigate to
 * dashboard, and ensure the「数据与分析」el-sub-menu group is expanded so its
 * el-menu-item children are visible for assertions.
 */
async function gotoSidebar(page: Page, context: BrowserContext, auth: LoginResult) {
  await injectAuthCookie(context, page, auth.token, auth.loginData, BASE_URL);
  // The request interceptor reads cretas_access_token from localStorage too
  // (see reference_web_admin_token_key memory) — set it in addition to the cookie.
  await page.evaluate((tok) => {
    localStorage.setItem('cretas_access_token', tok);
  }, auth.token);

  await page.goto(`${BASE_URL}/dashboard`, { waitUntil: 'load', timeout: 30000 });

  // 「数据与分析」group title (el-sub-menu__title > span). Expand it so children render.
  const groupTitle = page.getByText('数据与分析', { exact: true });
  await expect(groupTitle).toBeVisible({ timeout: 15000 });
  await groupTitle.click();
  // el-sub-menu expand animation
  await page.waitForTimeout(600);
}

test.describe('IA 重设计 — 数据与分析组 (headed 双业态)', () => {
  test('制造租户: 单一「数据与分析」组, 经营报表组消失, 制造项可见', async ({ page, context }) => {
    await gotoSidebar(page, context, factoryAuth);

    // 单一合并组存在
    await expect(page.getByText('数据与分析', { exact: true })).toBeVisible();
    // 旧「经营报表」顶级组已被合并删除 — 侧栏不应再出现该组标题
    await expect(page.getByText('经营报表', { exact: true })).toHaveCount(0);

    // 经营驾驶舱 (置顶主入口) + 制造专属项可见
    await expect(page.getByRole('menuitem', { name: '经营驾驶舱' })).toBeVisible();
    await expect(page.getByRole('menuitem', { name: '车间实时生产报表' })).toBeVisible();
    // FACTORY-方向门控: 收入管理报表 (hideForFactoryTypes:['FACTORY']) 对制造租户隐藏。
    // 与 ['RESTAURANT'] 门控走同一 canSeeMenuItem 机制 — 验证门控生效 (FACTORY 方向)。
    await expect(page.getByRole('menuitem', { name: '收入管理报表' })).toHaveCount(0);

    await page.screenshot({ path: 'test-results/ia-factory-sidebar.png', fullPage: true });
  });

  // SKIP: 需 factoryType=RESTAURANT 的账号验 ['RESTAURANT'] 方向门控。当前 prod 测试账号
  // qhj_prod 在 cretas_prod_db 里 factoryType=FACTORY (其 POS 数据在 smartbi_prod_db 是
  // 餐饮数据, 但租户类型未标 RESTAURANT) → ['RESTAURANT'] 门控不触发, 制造项不隐藏。
  // 门控机制本身已由 factory test 的 FACTORY-方向断言 (收入管理报表隐藏) + 18 个 menuConfig
  // 单测 (锁定 hideForFactoryTypes 数组) 覆盖。待有真 RESTAURANT 账号 unskip。
  test.skip('餐饮租户: 制造专属项隐藏, 趋势分析/KPI 仍可见, 收入管理报表可见', async ({ page, context }) => {
    await gotoSidebar(page, context, restaurantAuth);

    await expect(page.getByText('数据与分析', { exact: true })).toBeVisible();

    // 制造专属项隐藏 (hideForFactoryTypes: ['RESTAURANT'])
    await expect(page.getByRole('menuitem', { name: '车间实时生产报表' })).toHaveCount(0);
    await expect(page.getByRole('menuitem', { name: '生产数据分析' })).toHaveCount(0);
    await expect(page.getByRole('menuitem', { name: '人效分析' })).toHaveCount(0);
    await expect(page.getByRole('menuitem', { name: '进销存总览' })).toHaveCount(0);

    // 趋势分析 + KPI 看板 不门控 — 餐饮也可见
    await expect(page.getByRole('menuitem', { name: '趋势分析' })).toBeVisible();
    await expect(page.getByRole('menuitem', { name: 'KPI 看板' })).toBeVisible();

    // 收入管理报表 仅餐饮 (hideForFactoryTypes: ['FACTORY']) — 餐饮可见
    await expect(page.getByRole('menuitem', { name: '收入管理报表' })).toBeVisible();

    await page.screenshot({ path: 'test-results/ia-restaurant-sidebar.png', fullPage: true });
  });

  test('redirect: /analytics → 驾驶舱; query/finance → 合并 tab', async ({ page, context }) => {
    await injectAuthCookie(context, page, factoryAuth.token, factoryAuth.loginData, BASE_URL);
    await page.evaluate((tok) => {
      localStorage.setItem('cretas_access_token', tok);
    }, factoryAuth.token);

    // /analytics → /smart-bi/dashboard (旧经营报表顶级入口落到经营驾驶舱)
    await page.goto(`${BASE_URL}/analytics`, { waitUntil: 'load', timeout: 30000 });
    await expect(page).toHaveURL(/\/smart-bi\/dashboard/);

    // /smart-bi/query → /smart-bi/analysis?tab=query
    await page.goto(`${BASE_URL}/smart-bi/query`, { waitUntil: 'load', timeout: 30000 });
    await expect(page).toHaveURL(/\/smart-bi\/analysis\?tab=query/);

    // WS4: /smart-bi/finance → 经营分析 hub 财务 tab (analysis section)
    await page.goto(`${BASE_URL}/smart-bi/finance`, { waitUntil: 'load', timeout: 30000 });
    await expect(page).toHaveURL(/\/smart-bi\/analysis-hub\?.*tab=finance/);

    // WS4: /smart-bi/sales → 经营分析 hub 销售 tab
    await page.goto(`${BASE_URL}/smart-bi/sales`, { waitUntil: 'load', timeout: 30000 });
    await expect(page).toHaveURL(/\/smart-bi\/analysis-hub\?.*tab=sales/);

    // WS4: /analytics/trends → 经营分析 hub 趋势 tab
    await page.goto(`${BASE_URL}/analytics/trends`, { waitUntil: 'load', timeout: 30000 });
    await expect(page).toHaveURL(/\/smart-bi\/analysis-hub\?.*tab=trend/);
  });
});
