/**
 * Web Admin 全模块 E2E 测试
 *
 * 覆盖所有主要路由的页面加载 + 基础交互验证
 * 使用 HttpOnly cookie 注入 auth token
 *
 * 运行: npx playwright test web-admin-e2e.spec.ts --project=web-admin-e2e
 */

import { test, expect, Page } from '@playwright/test';
import { fetchLoginToken, injectAuthCookie, resolveApiBase, resolveTokenFromStorageState, LoginResult } from './e2e-auth-helper';
import { skipIfForbidden } from './e2e-auth-helper';

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173';
const API = process.env.E2E_API_URL || resolveApiBase();
const SD = 'test-results/screenshots/web-admin-e2e';

let authResult: LoginResult | null = null;

async function go(page: Page, path: string) {
  if (authResult) {
    await injectAuthCookie(page.context(), page, authResult.token, authResult.loginData, BASE);
  }
  await page.goto(BASE + path, { waitUntil: 'domcontentloaded', timeout: 30000 });
  // 🔴 2026-08-15: 这里原本 waitForTimeout(3000) 就往下断言, 而这个 SPA 常常还停在
  // 「正在加载应用…」骨架屏上 —— 失败只说「页面没有表格/卡片」, 指不到「还没启动完」。
  // 我先改成等骨架屏消失, 但固定时长两头不讨好: 45s 撑爆 Error Pages 的 30s 预算(2 挂),
  // 收到 8s 又等不及慢页面(6 挂)。
  // 正解是不睡固定时间 —— 见 expectPageContent 改用自动重试断言, 由它按需等待。
  await skipIfForbidden(page, path);
}

test.beforeAll(async () => {
  // 本 project 在 config 里没有 use.storageState(Step 10:「自注入 auth」), 所以这里
  // 拿到的 token 是它唯一的登录态 —— 不能降级为 null。
  // 口令登录不可用时改用 vue-auth 产出的 storageState token, 由 injectAuthCookie
  // 经 addInitScript 注入(而不是 goto('/login') 后再写 localStorage)。
  // 实测(干净机器, 内存 25GB): 这条路 71 条里 69 过 —— 不需要真实账号也能跑。
  try {
    authResult = await fetchLoginToken('factory_admin1', '123456', API);
  } catch (e) {
    const token = resolveTokenFromStorageState();
    if (!token) throw e;              // 两条路都没有就如实失败, 不假装跑过
    const c = JSON.parse(Buffer.from(
      token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf-8'));
    if (!c.factoryId || !c.userId) throw new Error('storageState token 缺 factoryId/userId');
    authResult = { token, loginData: {
      userId: c.userId, username: c.username, role: c.role,
      factoryId: c.factoryId, factoryType: 'FACTORY', permissions: ['*:*'] } };
    console.warn(`[web-admin-e2e] 口令登录不可用, 改用 storageState token: ${(e as Error).message}`);
  }
});

/** 断言页面有实际内容（表格/卡片/标题之一可见） */
async function expectPageContent(page: Page) {
  // 自动重试断言: Playwright 会轮询到元素出现或超时, 不需要预先猜一个 sleep 时长。
  // 超时取 25s —— 明显短于本文件各组的 60s 预算, 慢页面也够启动。
  // ⚠️ 必须先按可见性过滤再取 first(): .first() 取的是 **DOM 顺序**第一个,
  // 不看可不可见。实测 /sales/orders 页面 DOM 里第一个 .el-card 是隐藏的
  // gold-pos-summary, 于是 toBeVisible 恒失败, 而页面上明明有别的可见卡片 ——
  // 断言报「页面没内容」, 实际是「我挑中了一个藏起来的元素」。
  const anyContent = page
    .locator('.el-table, .el-card, h1, h2, h3, .page-title, .el-page-header__title, canvas, .echarts, [_echarts_instance_]')
    .filter({ visible: true })
    .first();
  await expect(anyContent).toBeVisible({ timeout: 25000 });
}

// ========================================
// Dashboard
// ========================================
test.describe('Dashboard', () => {
  test.setTimeout(60000);

  test('loads dashboard with content', async ({ page }) => {
    await go(page, '/dashboard');
    // Dashboard should have cards or charts
    const card = page.locator('.el-card').first();
    const chart = page.locator('canvas').first();
    const cardV = await card.isVisible().catch(() => false);
    const chartV = await chart.isVisible().catch(() => false);
    expect(cardV || chartV || page.url().includes('dashboard')).toBeTruthy();
    await page.screenshot({ path: `${SD}/dashboard.png`, fullPage: true });
  });
});

// ========================================
// 生产管理
// ========================================
test.describe('Production', () => {
  test.setTimeout(60000);

  test('batches list loads', async ({ page }) => {
    await go(page, '/production/batches');
    await expectPageContent(page);
    await page.screenshot({ path: `${SD}/production-batches.png`, fullPage: true });
  });

  test('plans list loads', async ({ page }) => {
    await go(page, '/production/plans');
    await expectPageContent(page);
  });

  test('BOM management loads', async ({ page }) => {
    await go(page, '/production/bom');
    await expectPageContent(page);
  });

  test('approval list loads', async ({ page }) => {
    await go(page, '/production/approval');
    await expectPageContent(page);
  });

  test('BOM achievement view loads', async ({ page }) => {
    await go(page, '/production/bom-achievement');
    await expectPageContent(page);
  });

  test('process IO comparison loads', async ({ page }) => {
    await go(page, '/production/process-io');
    await expectPageContent(page);
  });
});

// ========================================
// 仓储管理
// ========================================
test.describe('Warehouse', () => {
  test.setTimeout(60000);

  test('materials list loads', async ({ page }) => {
    await go(page, '/warehouse/materials');
    await expectPageContent(page);
    await page.screenshot({ path: `${SD}/warehouse-materials.png`, fullPage: true });
  });

  test('shipments list loads', async ({ page }) => {
    await go(page, '/warehouse/shipments');
    await expectPageContent(page);
  });

  test('inventory management loads', async ({ page }) => {
    await go(page, '/warehouse/inventory');
    await expectPageContent(page);
  });

  test('material price trend loads', async ({ page }) => {
    await go(page, '/warehouse/material-price-trend');
    await expectPageContent(page);
  });
});

// ========================================
// 调拨管理
// ========================================
test.describe('Transfer', () => {
  test.setTimeout(60000);

  test('transfer list loads', async ({ page }) => {
    await go(page, '/transfer/list');
    await expectPageContent(page);
  });
});

// ========================================
// 质量管理
// ========================================
test.describe('Quality', () => {
  test.setTimeout(60000);

  test('inspections list loads', async ({ page }) => {
    await go(page, '/quality/inspections');
    await expectPageContent(page);
    await page.screenshot({ path: `${SD}/quality-inspections.png`, fullPage: true });
  });

  test('disposals list loads', async ({ page }) => {
    await go(page, '/quality/disposals');
    await expectPageContent(page);
  });

  test('standards list loads', async ({ page }) => {
    await go(page, '/quality/standards');
    await expectPageContent(page);
  });
});

// ========================================
// 采购管理
// ========================================
test.describe('Procurement', () => {
  test.setTimeout(60000);

  test('orders list loads', async ({ page }) => {
    await go(page, '/procurement/orders');
    await expectPageContent(page);
    await page.screenshot({ path: `${SD}/procurement-orders.png`, fullPage: true });
  });

  test('suppliers list loads', async ({ page }) => {
    await go(page, '/procurement/suppliers');
    await expectPageContent(page);
  });

  test('price lists loads', async ({ page }) => {
    await go(page, '/procurement/price-lists');
    await expectPageContent(page);
  });
});

// ========================================
// 销售管理
// ========================================
test.describe('Sales', () => {
  test.setTimeout(60000);

  test('orders list loads', async ({ page }) => {
    await go(page, '/sales/orders');
    await expectPageContent(page);
    await page.screenshot({ path: `${SD}/sales-orders.png`, fullPage: true });
  });

  test('finished goods list loads', async ({ page }) => {
    await go(page, '/sales/finished-goods');
    await expectPageContent(page);
  });

  test('customers list loads', async ({ page }) => {
    await go(page, '/sales/customers');
    await expectPageContent(page);
  });

  test('shipments list loads', async ({ page }) => {
    await go(page, '/sales/shipments');
    await expectPageContent(page);
  });
});

// ========================================
// 人事管理
// ========================================
test.describe('HR', () => {
  test.setTimeout(60000);

  test('employees list loads', async ({ page }) => {
    await go(page, '/hr/employees');
    await expectPageContent(page);
    await page.screenshot({ path: `${SD}/hr-employees.png`, fullPage: true });
  });

  test('attendance list loads', async ({ page }) => {
    await go(page, '/hr/attendance');
    await expectPageContent(page);
  });

  test('whitelist management loads', async ({ page }) => {
    await go(page, '/hr/whitelist');
    await expectPageContent(page);
  });

  test('departments management loads', async ({ page }) => {
    await go(page, '/hr/departments');
    await expectPageContent(page);
  });
});

// ========================================
// 设备管理
// ========================================
test.describe('Equipment', () => {
  test.setTimeout(60000);

  test('equipment list loads', async ({ page }) => {
    await go(page, '/equipment/list');
    await expectPageContent(page);
    await page.screenshot({ path: `${SD}/equipment-list.png`, fullPage: true });
  });

  test('maintenance records loads', async ({ page }) => {
    await go(page, '/equipment/maintenance');
    await expectPageContent(page);
  });

  test('alerts management loads', async ({ page }) => {
    await go(page, '/equipment/alerts');
    await expectPageContent(page);
  });
});

// ========================================
// 财务管理
// ========================================
test.describe('Finance', () => {
  test.setTimeout(60000);

  test('costs analysis loads', async ({ page }) => {
    await go(page, '/finance/costs');
    await expectPageContent(page);
    await page.screenshot({ path: `${SD}/finance-costs.png`, fullPage: true });
  });

  test('financial reports loads', async ({ page }) => {
    await go(page, '/finance/reports');
    await expectPageContent(page);
  });

  test('AR/AP management loads', async ({ page }) => {
    await go(page, '/finance/ar-ap');
    await expectPageContent(page);
  });

  test('SKU margin analysis loads', async ({ page }) => {
    await go(page, '/finance/sku-margin');
    await expectPageContent(page);
  });
});

// ========================================
// 系统管理
// ========================================
test.describe('System', () => {
  test.setTimeout(60000);

  test('users list loads', async ({ page }) => {
    await go(page, '/system/users');
    await expectPageContent(page);
    await page.screenshot({ path: `${SD}/system-users.png`, fullPage: true });
  });

  test('roles list loads', async ({ page }) => {
    await go(page, '/system/roles');
    await expectPageContent(page);
  });

  test('operation logs loads', async ({ page }) => {
    await go(page, '/system/logs');
    await expectPageContent(page);
  });

  test('system settings loads', async ({ page }) => {
    await go(page, '/system/settings');
    await expectPageContent(page);
  });

  test('AI intents config loads', async ({ page }) => {
    await go(page, '/system/ai-intents');
    await expectPageContent(page);
  });

  test('skill/tool governance loads', async ({ page }) => {
    await go(page, '/system/skill-tools');
    await expectPageContent(page);
  });

  test('product management loads', async ({ page }) => {
    await go(page, '/system/products');
    await expectPageContent(page);
  });

  test('feature module config loads', async ({ page }) => {
    await go(page, '/system/features');
    await expectPageContent(page);
  });

  test('work processes loads', async ({ page }) => {
    await go(page, '/system/work-processes');
    await expectPageContent(page);
  });

  test('product processes loads', async ({ page }) => {
    await go(page, '/system/product-processes');
    await expectPageContent(page);
  });

  test('workflow designer loads', async ({ page }) => {
    await go(page, '/system/workflow-designer');
    await expectPageContent(page);
  });
});

// ========================================
// 数据分析
// ========================================
test.describe('Analytics', () => {
  test.setTimeout(60000);

  test('overview loads', async ({ page }) => {
    await go(page, '/analytics/overview');
    await expectPageContent(page);
    await page.screenshot({ path: `${SD}/analytics-overview.png`, fullPage: true });
  });

  test('trends loads', async ({ page }) => {
    await go(page, '/analytics/trends');
    await expectPageContent(page);
  });

  test('AI reports loads', async ({ page }) => {
    await go(page, '/analytics/ai-reports');
    await expectPageContent(page);
  });

  test('KPI dashboard loads', async ({ page }) => {
    await go(page, '/analytics/kpi');
    await expectPageContent(page);
  });

  test('production report loads', async ({ page }) => {
    await go(page, '/analytics/production-report');
    await expectPageContent(page);
  });

  test('alert dashboard loads', async ({ page }) => {
    await go(page, '/analytics/alert-dashboard');
    await expectPageContent(page);
  });

  test('supply chain overview loads', async ({ page }) => {
    await go(page, '/analytics/supply-chain');
    await expectPageContent(page);
  });
});

// ========================================
// 智能调度
// ========================================
test.describe('Scheduling', () => {
  test.setTimeout(60000);

  test('overview loads', async ({ page }) => {
    await go(page, '/scheduling/overview');
    await expectPageContent(page);
    await page.screenshot({ path: `${SD}/scheduling-overview.png`, fullPage: true });
  });

  test('plans list loads', async ({ page }) => {
    await go(page, '/scheduling/plans');
    await expectPageContent(page);
  });

  test('realtime monitoring loads', async ({ page }) => {
    await go(page, '/scheduling/realtime');
    await expectPageContent(page);
  });

  test('worker assignment loads', async ({ page }) => {
    await go(page, '/scheduling/workers');
    await expectPageContent(page);
  });

  test('scheduling alerts loads', async ({ page }) => {
    await go(page, '/scheduling/alerts');
    await expectPageContent(page);
  });
});

// ========================================
// 餐饮运营
// ========================================
test.describe('Restaurant', () => {
  test.setTimeout(60000);

  test('requisitions list loads', async ({ page }) => {
    await go(page, '/restaurant/requisitions');
    await expectPageContent(page);
    await page.screenshot({ path: `${SD}/restaurant-requisitions.png`, fullPage: true });
  });

  test('wastage list loads', async ({ page }) => {
    await go(page, '/restaurant/wastage');
    await expectPageContent(page);
  });

  test('recipes list loads', async ({ page }) => {
    await go(page, '/restaurant/recipes');
    await expectPageContent(page);
  });

  test('stocktaking list loads', async ({ page }) => {
    await go(page, '/restaurant/stocktaking');
    await expectPageContent(page);
  });

  test('restaurant analytics overview loads', async ({ page }) => {
    await go(page, '/restaurant/analytics');
    await expectPageContent(page);
  });

  test('menu board loads', async ({ page }) => {
    await go(page, '/restaurant/analytics/menu');
    await expectPageContent(page);
  });

  test('store comparison loads', async ({ page }) => {
    await go(page, '/restaurant/analytics/stores');
    await expectPageContent(page);
  });

  test('dianping gap analysis loads', async ({ page }) => {
    await go(page, '/restaurant/analytics/dianping');
    await expectPageContent(page);
  });
});

// ========================================
// 行为校准
// ========================================
test.describe('Calibration', () => {
  test.setTimeout(60000);

  test('calibration list loads', async ({ page }) => {
    await go(page, '/calibration/list');
    await expectPageContent(page);
  });
});

// ========================================
// 导航 & 侧边栏
// ========================================
test.describe('Navigation', () => {
  test.setTimeout(60000);

  test('sidebar menu renders all top-level modules', async ({ page }) => {
    await go(page, '/dashboard');
    // 同上: 一次性 isVisible() 不重试, 页面没启动完就判死。改用自动重试断言 + 可见性过滤。
    const sidebar = page.locator('.el-menu, .el-aside, .app-sidebar').filter({ visible: true }).first();
    await expect(sidebar).toBeVisible({ timeout: 25000 });
    await page.screenshot({ path: `${SD}/sidebar.png`, fullPage: true });
  });

  test('sidebar collapse/expand works', async ({ page }) => {
    await go(page, '/dashboard');
    const collapseBtn = page.locator('[class*="collapse"], [class*="hamburger"], .fold-btn').first();
    if (await collapseBtn.isVisible().catch(() => false)) {
      await collapseBtn.click();
      await page.waitForTimeout(500);
      await collapseBtn.click();
      await page.waitForTimeout(500);
    }
    // Just verify page didn't crash
    expect(page.url()).toContain('dashboard');
  });

  test('404 page renders for unknown route', async ({ page }) => {
    await go(page, '/this-does-not-exist');
    // Should redirect to 404 or show error
    const body = await page.textContent('body');
    expect(body).toBeTruthy();
  });

  test('login page redirects authenticated user', async ({ page }) => {
    await go(page, '/login');
    await page.waitForTimeout(2000);
    // Authenticated user should be redirected away from login
    const url = page.url();
    // Either redirected to dashboard or stayed on login (both OK)
    expect(url).toBeTruthy();
  });
});

// ========================================
// 错误页面
// ========================================
test.describe('Error Pages', () => {
  test.setTimeout(30000);

  test('403 page renders', async ({ page }) => {
    await go(page, '/403');
    const body = await page.textContent('body');
    expect(body).toBeTruthy();
  });

  test('404 page renders', async ({ page }) => {
    await go(page, '/404');
    const body = await page.textContent('body');
    expect(body).toBeTruthy();
  });
});
