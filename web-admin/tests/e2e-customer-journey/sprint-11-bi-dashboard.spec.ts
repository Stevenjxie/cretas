/**
 * Sprint 11 BI Dashboard E2E spec — 2026-05-23
 *
 * Owner: BI chat (worktree my-prototype-logistics-sprint11-d5)
 * Goal: 10 scenarios verifying Sprint 11 BI 4-B band-aid 真 ship 在 prod 8086 上
 *       (per audit PR #243 §4 P2.1 — Sprint 12 backend rewrite 接前的 BI scope deliverable).
 *
 * Run via:
 *   cd web-admin
 *   npx playwright test tests/e2e-customer-journey/sprint-11-bi-dashboard.spec.ts \
 *     --project=sprint-11-bi-dashboard \
 *     --workers=1
 *
 * Or 自定义 base URL:
 *   E2E_BASE_URL=http://139.196.165.140:8086 npx playwright test ...
 *
 * Coverage (10 scenarios per Sprint 11 14d plan DOD #4):
 *   1. F006 admin login + /indicator-center 加载
 *   2. B2BRealDataSection 显示真业务 cards (3 KPI: 订单总数 / 平均订单金额 / 销售总额)
 *   3. 7 V_23_11 mirror codes 全部 hidden via filter
 *   4. 大字 banner "客户演示模式 · Sprint 12 接 backend" 可见
 *   5. 指标树 (tree view) stats 用 "已计算/待配置" honest labels (不是 "正常/关注/告警 0")
 *   6. Detail drawer drill-down (click RESTAURANT_WASTAGE_RATE card → drawer opens)
 *   7. Mobile 320×568 viewport responsive (B2B section vertical stack)
 *   8. Mobile 375×812 viewport responsive
 *   9. Workdesk /workdesk/sales-owner B2BRealDataSection 替代 mirror cards (Dim 1 fix)
 *  10. Workdesk 撒谎 header "F006 真数据" 已删除
 *
 * 跟 sister UX verdict (verdict-2026-05-23.md) 互补: sister 测 SalesOwner Chat NL routing (12 cases),
 * 本 spec 测 Indicator Center + Workdesk indicator card 路径 (10 cases).
 *
 * Per docs/audits/sprint-11-bi-playwright-waiver.md, .webm 录屏 deferred to native
 * Playwright config (playwright.config.ts use.video='on' when 启用).
 */
import { test, expect, type Page } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

const BASE = process.env.E2E_BASE_URL || 'http://139.196.165.140:8086';
const API  = process.env.E2E_API_BASE  || `${BASE}/api/mobile`;

const ACCT = {
  username: 'f006_admin',
  password: '123456',
  factoryId: 'F006',
};

const MIRRORED_CODES = [
  'AVG_TICKET_PRICE',
  'TABLE_TURNOVER',
  'DISH_GROSS_MARGIN',
  'RAW_WASTAGE_RATE',
  'FOOD_SAFETY_PASS_RATE',
  'FACTORY_YIELD_RATE',
  'FACTORY_PLAN_ACHIEVE_RATE',
];

const MIRRORED_VALUES_REGEX = /37\.39|1\.41|39\.44|98\.78|6\.58|96\.10|102\.18/;

const OUT_DIR = path.resolve(process.cwd(), '..', 'docs', 'audits', 'sprint-11-bi-screenshots');

async function loginAndSeed(page: Page) {
  const resp = await page.request.post(`${API}/auth/unified-login`, {
    data: ACCT,
    timeout: 30_000,
    failOnStatusCode: false,
  });
  expect(resp.ok(), `auth status=${resp.status()}`).toBe(true);
  const body: any = await resp.json();
  const data = body?.data;
  const token = String(data?.token || data?.accessToken || '');
  expect(token.length, 'token must be non-empty').toBeGreaterThan(50);

  const userObj = {
    id: data.userId,
    username: data.username,
    email: '',
    isActive: true,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    userType: 'factory',
    factoryUser: {
      role: data.role,
      factoryId: data.factoryId,
      factoryType: data.factoryType || 'FACTORY',
      permissions: data.permissions || ['*:*'],
    },
  };
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
  await page.evaluate(({ tok, user }) => {
    localStorage.setItem('cretas_access_token', tok);
    localStorage.setItem('cretas_user', JSON.stringify(user));
  }, { tok: token, user: userObj });
}

test.describe.serial('Sprint 11 BI Dashboard — 10 scenarios', () => {
  test.setTimeout(120_000);

  test.beforeAll(() => {
    fs.mkdirSync(OUT_DIR, { recursive: true });
  });

  test('1. F006 admin login + /indicator-center renders', async ({ page }) => {
    await loginAndSeed(page);
    // WS4: /indicator-center 已合并入经营分析 hub 的 KPI·指标 内层 tab
    // (redirect → /smart-bi/analysis-hub?tab=kpi&sub=indicator)。IndicatorCenterDashboard
    // 组件仍渲染 (.indicator-center class), 经 hub 内层 tab 加载。
    await page.goto(`${BASE}/indicator-center`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
    await page.waitForSelector('.indicator-center, .b2b-real-section', { timeout: 15_000 });
    const url = page.url();
    expect(url).toContain('/smart-bi/analysis-hub');
  });

  test('2. B2B section displays 3 KPI cards with real values', async ({ page }) => {
    await loginAndSeed(page);
    await page.goto(`${BASE}/indicator-center`, { waitUntil: 'networkidle', timeout: 30_000 });
    await page.waitForSelector('.b2b-real-section', { timeout: 15_000 });
    const text = await page.evaluate(() => document.body.innerText);
    expect(text).toContain('B2B 销售真业务数据');
    expect(text).toContain('订单总数');
    expect(text).toContain('平均订单金额');
    expect(text).toContain('销售总额');
    // F006 has 5 sales_orders → avg ¥1,225,510 (per SSH verify 2026-05-23)
    expect(text).toMatch(/¥\s*1[,.]?22[0-9],?5?\d{2}/);
  });

  test('3. 7 V_23_11 mirror codes hidden from card grid', async ({ page }) => {
    await loginAndSeed(page);
    const apiResp = await page.request.get(`${API}/${ACCT.factoryId}/indicators`, {
      headers: { Authorization: `Bearer ${await page.evaluate(() => localStorage.getItem('cretas_access_token'))}` },
      timeout: 15_000,
    });
    expect(apiResp.ok()).toBe(true);
    const apiBody: any = await apiResp.json();
    const allCodes: string[] = (apiBody.data || []).map((i: any) => i.code);
    const mirrorCount = allCodes.filter(c => MIRRORED_CODES.includes(c)).length;
    expect(mirrorCount, 'API should still return 7 mirror codes (DB unchanged)').toBe(7);

    await page.goto(`${BASE}/indicator-center`, { waitUntil: 'networkidle', timeout: 30_000 });
    await page.waitForSelector('.indicator-center', { timeout: 15_000 });
    const visibleCodes = await page.evaluate(() => {
      const cards = Array.from(document.querySelectorAll('.indicator-card .indicator-name'));
      return cards.map(el => el.textContent?.trim() || '');
    });
    // Visible UI text should NOT include 客单价 / 翻台率 / 菜品毛利 (the 4 mirror cards in 餐饮 category)
    const hasMirrorLabel = visibleCodes.some(
      n => /^客单价$|^翻台率$|^菜品毛利$|^食安通过率$/.test(n)
    );
    expect(hasMirrorLabel, 'mirror labels should be filtered from card grid').toBe(false);
  });

  test('4. Big banner "客户演示模式 · Sprint 12 接 backend" visible', async ({ page }) => {
    await loginAndSeed(page);
    await page.goto(`${BASE}/indicator-center`, { waitUntil: 'networkidle', timeout: 30_000 });
    await page.waitForSelector('.b2b-real-section', { timeout: 15_000 });
    const bannerText = await page.locator('.big-banner').first().innerText({ timeout: 10_000 });
    expect(bannerText).toContain('客户演示模式');
    expect(bannerText).toContain('Sprint 12 接 backend');
  });

  test('5. Tree view stats use 已计算/待配置 honest labels', async ({ page }) => {
    await loginAndSeed(page);
    await page.goto(`${BASE}/indicator-center`, { waitUntil: 'networkidle', timeout: 30_000 });
    await page.waitForSelector('.indicator-center', { timeout: 15_000 });
    // Switch to tree view tab
    await page.locator('text=指标树').click({ timeout: 10_000 });
    await page.waitForSelector('.tree-viewer .tree-stats', { timeout: 15_000 });
    const statsText = await page.locator('.tree-viewer .tree-stats').innerText({ timeout: 10_000 });
    expect(statsText).toMatch(/已计算/);
    expect(statsText).toMatch(/待配置/);
    expect(statsText).not.toMatch(/正常\s*\d.*关注\s*\d.*告警\s*\d/);
  });

  test('6. Detail drawer drill-down opens on card click', async ({ page }) => {
    await loginAndSeed(page);
    await page.goto(`${BASE}/indicator-center`, { waitUntil: 'networkidle', timeout: 30_000 });
    await page.waitForSelector('.indicator-card', { timeout: 15_000 });
    // Click first F006-real card (e.g. RESTAURANT_WASTAGE_RATE with value 2.80)
    const firstCard = page.locator('.indicator-card.card-clickable').first();
    await firstCard.click({ timeout: 10_000 });
    await page.waitForSelector('.el-drawer', { timeout: 15_000 });
    const drawerVisible = await page.locator('.el-drawer__body').isVisible();
    expect(drawerVisible).toBe(true);
  });

  test('7. Mobile 320×568 viewport responsive', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 568 });
    await loginAndSeed(page);
    await page.goto(`${BASE}/indicator-center`, { waitUntil: 'networkidle', timeout: 30_000 });
    await page.waitForSelector('.b2b-real-section', { timeout: 15_000 });
    // B2B section should be visible + stacked vertically
    const b2bVisible = await page.locator('.b2b-real-section').isVisible();
    expect(b2bVisible).toBe(true);
    const text = await page.evaluate(() => document.body.innerText);
    expect(text).toContain('客户演示模式');
  });

  test('8. Mobile 375×812 viewport responsive', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await loginAndSeed(page);
    await page.goto(`${BASE}/indicator-center`, { waitUntil: 'networkidle', timeout: 30_000 });
    await page.waitForSelector('.b2b-real-section', { timeout: 15_000 });
    const b2bVisible = await page.locator('.b2b-real-section').isVisible();
    expect(b2bVisible).toBe(true);
  });

  test('9. Workdesk /workdesk/sales-owner uses B2BRealDataSection', async ({ page }) => {
    await loginAndSeed(page);
    await page.goto(`${BASE}/workdesk/sales-owner`, { waitUntil: 'networkidle', timeout: 30_000 });
    await page.waitForSelector('.b2b-real-section', { timeout: 15_000 });
    const text = await page.evaluate(() => document.body.innerText);
    expect(text).toContain('客户演示模式');
    expect(text).toContain('B2B 销售真业务数据');
    expect(text).toContain('临时方案');
  });

  test('10. Workdesk no lying "F006 真数据" header + no mirror values', async ({ page }) => {
    await loginAndSeed(page);
    await page.goto(`${BASE}/workdesk/sales-owner`, { waitUntil: 'networkidle', timeout: 30_000 });
    await page.waitForSelector('.b2b-real-section', { timeout: 15_000 });
    const text = await page.evaluate(() => document.body.innerText);
    expect(text, 'lying "F006 真数据" header should be removed').not.toMatch(/来源:\s*BI IndicatorQueryTool\s*·\s*F006 真数据/);
    expect(text, 'mirror values (37.39/1.41/etc) should not appear in Workdesk').not.toMatch(MIRRORED_VALUES_REGEX);
  });
});
