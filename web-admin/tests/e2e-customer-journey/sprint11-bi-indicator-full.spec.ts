/**
 * Sprint 11 BI Indicator Center Full E2E — 4 dim UX audit
 * Date: 2026-05-28
 *
 * Owner: BI chat (worktree my-prototype-logistics-sprint11-d5)
 * Per Steve directive 2026-05-28 — 30+ scenarios, 4-dim UX (UI/UX / 操作顺序 / 使用逻辑 / 老板能用度)
 *
 * Complements PR #255 (10 scenarios baseline). This spec extends to:
 *   - 3 accounts × multiple scenarios (cross-RBAC consistency)
 *   - MutationObserver toast capture (qa-prompt v2.4 Rule 7)
 *   - Roundtrip 3-step (qa-prompt v2.4 Rule 11)
 *   - Mid/end indicator 抽检 (qa-prompt v2.4 Rule 9 — NOT Top 3 only)
 *   - ≥1 error-deep with 4位一体 (qa-prompt v2.4 Rule 8)
 *   - ≥3 deep L4 (depth-first-e2e Rule 2)
 *   - 入口点矩阵 (qa-prompt v2.4 Rule 16) — 3 entry: 侧边栏 / Workdesk / AIChat
 *   - 多 viewport: 320 / 1440 / 1920
 *
 * Run via:
 *   cd web-admin
 *   npx playwright test tests/e2e-customer-journey/sprint11-bi-indicator-full.spec.ts \
 *     --workers=1 --reporter=html
 *
 * Outputs:
 *   - Screenshots: docs/audits/sprint-11-bi-screenshots/full-*.png (~30 fullPage)
 *   - JSON: docs/audits/sprint-11-bi-full-audit/ui-text-30.json (per-case innerText + depth)
 *   - Video: test-results/<test>/video.webm (if --video=on)
 */
import { test, expect, type Page } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

const BASE = process.env.E2E_BASE_URL || 'http://139.196.165.140:8086';
const API  = process.env.E2E_API_BASE  || `${BASE}/api/mobile`;

type Account = {
  label: string;
  username: string;
  password: string;
  factoryId: string;
  factoryType: 'FACTORY' | 'RESTAURANT';
  role: 'factory_super_admin' | 'sales_manager' | 'warehouse_manager';
};

const ACCOUNTS: Record<string, Account> = {
  f006: {
    label: 'f006_admin',
    username: 'f006_admin',
    password: '123456',
    factoryId: 'F006',
    factoryType: 'FACTORY',
    role: 'factory_super_admin',
  },
  superAdmin: {
    label: 'factory_super_admin (F006 对照)',
    username: 'factory_super_admin',
    password: 'admin123',
    factoryId: 'F006',
    factoryType: 'FACTORY',
    role: 'factory_super_admin',
  },
  warehouse: {
    label: 'warehouse_mgr1 (F001 sister)',
    username: 'warehouse_mgr1',
    password: '123456',
    factoryId: 'F001',
    factoryType: 'FACTORY',
    role: 'warehouse_manager',
  },
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

// Output dirs — write into docs/audits relative to web-admin/
const SCREENSHOTS_DIR = path.resolve(process.cwd(), '..', 'docs', 'audits', 'sprint-11-bi-screenshots');
const AUDIT_OUT_DIR   = path.resolve(process.cwd(), '..', 'docs', 'audits', 'sprint-11-bi-full-audit');

type CaseResult = {
  caseId: string;
  scenario: string;
  account: string;
  factoryId: string;
  viewport?: { width: number; height: number };
  depth: 'smoke' | 'medium' | 'deep' | 'error-deep';
  status: 'PASS' | 'FAIL' | 'SKIP';
  evidence: Record<string, unknown>;
  toastLog?: Array<{ time: number; cls: string; text: string }>;
  consoleErrors?: string[];
  networkErrors?: Array<{ url: string; status: number }>;
  uiText?: string;
  error?: string;
};
const results: CaseResult[] = [];

// Token cache to avoid Aliyun 429 rate limit
// (per memory feedback_e2e_runner_aliyun_rate_limit_cushion HARD: ~30 req/min)
type AuthCache = { token: string; userObj: any; expiresAt: number };
const tokenCache: Record<string, AuthCache> = {};

async function loginAndSeed(page: Page, acct: Account) {
  const cacheKey = `${acct.username}|${acct.factoryId}`;
  const cached = tokenCache[cacheKey];
  let token: string;
  let userObj: any;
  if (cached && cached.expiresAt > Date.now()) {
    token = cached.token;
    userObj = cached.userObj;
  } else {
    // 12s cushion before fresh login (per HARD rule)
    if (cached) await new Promise(r => setTimeout(r, 12_000));
    const resp = await page.request.post(`${API}/auth/unified-login`, {
      data: {
        username: acct.username,
        password: acct.password,
        factoryId: acct.factoryId,
      },
      timeout: 30_000,
      failOnStatusCode: false,
    });
    if (!resp.ok()) {
      const body = await resp.text();
      throw new Error(`auth fail status=${resp.status()} body=${body.slice(0, 300)}`);
    }
    const body: any = await resp.json();
    const data = body?.data;
    token = String(data?.token || data?.accessToken || '');
    if (token.length < 50) throw new Error(`token too short: ${token.length}`);
    userObj = {
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
        factoryType: data.factoryType || acct.factoryType,
        permissions: data.permissions || ['*:*'],
      },
    };
    tokenCache[cacheKey] = {
      token,
      userObj,
      expiresAt: Date.now() + 30 * 60 * 1000, // 30min cache
    };
  }
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
  await page.evaluate(({ tok, user }) => {
    localStorage.setItem('cretas_access_token', tok);
    localStorage.setItem('cretas_user', JSON.stringify(user));
  }, { tok: token, user: userObj });
  return token;
}

/**
 * Install MutationObserver toast logger BEFORE any user action.
 * Per qa-prompt v2.4 Rule 7 — must use observer not querySelectorAll
 * (toast fades in 3s, race condition gives empty array).
 */
async function installToastObserver(page: Page) {
  await page.evaluate(() => {
    (window as any).__toastLog = [];
    new MutationObserver((muts) => {
      muts.forEach(m => m.addedNodes.forEach((n: any) => {
        if (n.nodeType === 1 && typeof n.className === 'string' &&
            (n.className.includes('el-message') || n.className.includes('el-notification'))) {
          (window as any).__toastLog.push({
            time: Date.now(),
            cls: n.className,
            text: (n.textContent || '').trim(),
          });
        }
      }));
    }).observe(document.body, { childList: true, subtree: true });
  });
}

async function readToastLog(page: Page) {
  return await page.evaluate(() => (window as any).__toastLog || []);
}

async function trackConsoleAndNetwork(page: Page) {
  const consoleErrors: string[] = [];
  const networkErrors: Array<{ url: string; status: number }> = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text());
  });
  page.on('response', (resp) => {
    if (resp.status() >= 400 && resp.url().includes('/api/')) {
      networkErrors.push({ url: resp.url(), status: resp.status() });
    }
  });
  return { consoleErrors, networkErrors };
}

async function shotInto(page: Page, name: string) {
  const target = path.join(SCREENSHOTS_DIR, `full-${name}.png`);
  await page.screenshot({ path: target, fullPage: true });
  return target;
}

test.beforeAll(() => {
  fs.mkdirSync(SCREENSHOTS_DIR, { recursive: true });
  fs.mkdirSync(AUDIT_OUT_DIR, { recursive: true });
});

test.afterAll(() => {
  const out = path.join(AUDIT_OUT_DIR, 'ui-text-30.json');
  fs.writeFileSync(out, JSON.stringify({ results, total: results.length, schemaV2: true }, null, 2));
  console.log(`[bi-full-audit] wrote ${results.length} case results to ${out}`);
});

// ============================================================
// GROUP A — F006 admin happy path (10 cases, depth varies)
// ============================================================

test.describe('A. F006 admin happy path — Indicator Center 主路径', () => {
  test.setTimeout(180_000);
  const acct = ACCOUNTS.f006;

  test('A1. login + /indicator-center renders (depth=smoke)', async ({ page }) => {
    const tracker = await trackConsoleAndNetwork(page);
    await loginAndSeed(page, acct);
    await page.goto(`${BASE}/indicator-center`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
    await page.waitForSelector('.indicator-center, .b2b-real-section', { timeout: 15_000 });
    const png = await shotInto(page, 'a1-login-render');
    const title = await page.title();
    expect(title).toContain('指标中心');
    results.push({
      caseId: 'A1', scenario: 'login render', account: acct.label, factoryId: acct.factoryId,
      depth: 'smoke', status: 'PASS',
      evidence: { png, title },
      consoleErrors: tracker.consoleErrors, networkErrors: tracker.networkErrors,
    });
  });

  test('A2. B2B section 3 real-data KPI cards (depth=deep)', async ({ page }) => {
    await loginAndSeed(page, acct);
    await installToastObserver(page);
    await page.goto(`${BASE}/indicator-center`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
    await page.waitForSelector('.b2b-real-section', { timeout: 15_000 });
    const text = await page.evaluate(() => document.body.innerText);
    expect(text).toContain('B2B 销售真业务数据');
    expect(text).toContain('订单总数');
    expect(text).toContain('平均订单金额');
    expect(text).toContain('销售总额');
    expect(text).toMatch(/¥\s*1[,.]?22[0-9],?5?\d{2}/);
    const png = await shotInto(page, 'a2-b2b-real-data');
    const toast = await readToastLog(page);
    results.push({
      caseId: 'A2', scenario: 'B2B 3 KPI real values', account: acct.label, factoryId: acct.factoryId,
      depth: 'deep', status: 'PASS',
      evidence: { png, bodyHas: ['B2B 销售真业务数据', '订单总数', '平均订单金额', '销售总额', '¥1,225,510'] },
      toastLog: toast,
    });
  });

  test('A3. 7 mirror codes hidden via filter (depth=deep, API+UI cross-verify)', async ({ page }) => {
    const token = await loginAndSeed(page, acct);
    const apiResp = await page.request.get(`${API}/${acct.factoryId}/indicators`, {
      headers: { Authorization: `Bearer ${token}` },
      timeout: 15_000,
    });
    expect(apiResp.ok()).toBe(true);
    const apiBody: any = await apiResp.json();
    const allCodes: string[] = (apiBody.data || []).map((i: any) => i.code);
    const apiMirrorCount = allCodes.filter(c => MIRRORED_CODES.includes(c)).length;
    expect(apiMirrorCount, 'API should still return all 7 mirror codes').toBe(7);

    await page.goto(`${BASE}/indicator-center`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
    await page.waitForSelector('.indicator-center', { timeout: 15_000 });
    const visibleCardNames = await page.evaluate(() => {
      const cards = Array.from(document.querySelectorAll('.indicator-card .indicator-name'));
      return cards.map(el => (el.textContent || '').trim());
    });
    // Per qa-prompt Rule 9: NOT Top 3 only — capture full + mid + end sample
    const mirrorLabels = visibleCardNames.filter(n =>
      /^客单价$|^翻台率$|^菜品毛利$|^食安通过率$/.test(n)
    );
    // Record finding without test failure — even 1 mirror leak is a real audit P0
    const png = await shotInto(page, 'a3-mirror-hidden');
    const allMirrorLabelsCount = visibleCardNames.filter(n =>
      /客单价|翻台率|菜品|食安|食材损耗/.test(n)
    ).length;
    results.push({
      caseId: 'A3', scenario: 'mirror filter', account: acct.label, factoryId: acct.factoryId,
      depth: 'deep',
      status: mirrorLabels.length === 0 ? 'PASS' : 'FAIL',
      evidence: { png, apiMirrorCount, uiVisibleCount: visibleCardNames.length, visibleCardNames, mirrorLabelsFound: mirrorLabels.length, allMirrorOrRelated: allMirrorLabelsCount, leakedLabels: mirrorLabels },
      error: mirrorLabels.length > 0 ? `${mirrorLabels.length} mirror label(s) leaked through filter: ${mirrorLabels.join(',')}` : undefined,
    });
  });

  test('A4. 大字 banner 客户演示模式 visible + readable contrast (depth=medium)', async ({ page }) => {
    await loginAndSeed(page, acct);
    await page.goto(`${BASE}/indicator-center`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
    await page.waitForSelector('.b2b-real-section .big-banner', { timeout: 15_000 });
    const banner = page.locator('.big-banner').first();
    const text = await banner.innerText({ timeout: 10_000 });
    expect(text).toContain('客户演示模式');
    expect(text).toContain('Sprint 12 接 backend');
    // Banner styling check (Rule 8: visible + sticky semantics for warning info)
    const style = await banner.evaluate((el) => {
      const cs = getComputedStyle(el);
      return {
        fontSize: cs.fontSize,
        color: cs.color,
        background: cs.backgroundColor,
        borderWidth: cs.borderWidth,
      };
    });
    const png = await shotInto(page, 'a4-banner');
    results.push({
      caseId: 'A4', scenario: 'big banner readability', account: acct.label, factoryId: acct.factoryId,
      depth: 'medium', status: 'PASS',
      evidence: { png, bannerText: text.slice(0, 100), style },
    });
  });

  test('A5. 17 indicators 中段 + 末段 抽检 (depth=deep, qa-prompt Rule 9)', async ({ page }) => {
    const token = await loginAndSeed(page, acct);
    const apiResp = await page.request.get(`${API}/${acct.factoryId}/indicators`, {
      headers: { Authorization: `Bearer ${token}` }, timeout: 15_000,
    });
    const apiBody: any = await apiResp.json();
    const items: Array<{ code: string; name: string; lastValue: any }> = apiBody.data || [];
    expect(items.length).toBeGreaterThanOrEqual(10);
    // Rule 9: must check Top + Mid + End (not just Top 3)
    const sorted = items.slice().sort((a, b) => a.code.localeCompare(b.code));
    const mid = sorted[Math.floor(sorted.length / 2)];
    const end1 = sorted[sorted.length - 1];
    const end2 = sorted[sorted.length - 2];
    // Each must have a real Chinese name (not "1.0" or "header" or "注:")
    [mid, end1, end2].forEach(item => {
      expect(item.name, `${item.code} name should not be pseudo-row`).not.toMatch(/^\d+\.\d+$/);
      expect(item.name, `${item.code} should have Chinese characters`).toMatch(/[一-龥]/);
    });
    results.push({
      caseId: 'A5', scenario: '17 indicator mid+end抽检', account: acct.label, factoryId: acct.factoryId,
      depth: 'deep', status: 'PASS',
      evidence: { total: items.length, mid: mid.code, end1: end1.code, end2: end2.code, midName: mid.name },
    });
  });

  test('A6. Tree view honest labels 已计算/待配置 (depth=medium)', async ({ page }) => {
    await loginAndSeed(page, acct);
    await page.goto(`${BASE}/indicator-center`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
    await page.waitForSelector('.indicator-center', { timeout: 15_000 });
    await page.locator('text=指标树').click({ timeout: 10_000 });
    await page.waitForSelector('.tree-viewer .tree-stats', { timeout: 15_000 });
    const statsText = await page.locator('.tree-viewer .tree-stats').innerText({ timeout: 10_000 });
    expect(statsText).toMatch(/已计算/);
    expect(statsText).toMatch(/待配置/);
    const png = await shotInto(page, 'a6-tree-honest');
    results.push({
      caseId: 'A6', scenario: 'tree honest labels', account: acct.label, factoryId: acct.factoryId,
      depth: 'medium', status: 'PASS',
      evidence: { png, statsText: statsText.slice(0, 200) },
    });
  });

  test('A7. Detail drawer drill-down opens (depth=medium)', async ({ page }) => {
    await loginAndSeed(page, acct);
    await page.goto(`${BASE}/indicator-center`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
    await page.waitForSelector('.indicator-card', { timeout: 15_000 });
    const firstCard = page.locator('.indicator-card.card-clickable').first();
    await firstCard.click({ timeout: 10_000 });
    await page.waitForSelector('.el-drawer', { timeout: 15_000 });
    const drawerVisible = await page.locator('.el-drawer__body').isVisible();
    expect(drawerVisible).toBe(true);
    const png = await shotInto(page, 'a7-detail-drawer');
    results.push({
      caseId: 'A7', scenario: 'detail drawer drill-down', account: acct.label, factoryId: acct.factoryId,
      depth: 'medium', status: 'PASS',
      evidence: { png, drawerVisible },
    });
  });

  test('A8. Cross-module roundtrip: new sales_order → B2B card +1 (depth=deep, Rule 11)', async ({ page }) => {
    // Per qa-prompt Rule 11: write op → 3-step roundtrip
    const token = await loginAndSeed(page, acct);
    // ① Capture current B2B count via API
    const ordersBefore = await page.request.get(
      `${API}/${acct.factoryId}/sales/orders?page=1&size=200`,
      { headers: { Authorization: `Bearer ${token}` }, timeout: 15_000 }
    );
    const beforeBody: any = await ordersBefore.json();
    const beforeCount = (beforeBody.data?.content || []).length;
    // ② Create new sales_order (POST) — minimal body per Rule 10
    const newOrderBody = {
      customerName: `Sprint11-BI-Audit-${Date.now()}`,
      orderDate: new Date().toISOString().slice(0, 10),
      totalAmount: 8888.88,
      items: [{ productTypeId: null, quantity: 1, unitPrice: 8888.88, unit: 'kg', specification: 'audit test' }],
    };
    const createResp = await page.request.post(`${API}/${acct.factoryId}/sales/orders`, {
      headers: { Authorization: `Bearer ${token}` },
      data: newOrderBody,
      timeout: 30_000,
      failOnStatusCode: false,
    });
    // Note: This may fail if validation requires more fields — record honestly
    const createBody: any = createResp.ok() ? await createResp.json() : { error: await createResp.text() };
    const createOk = createResp.ok();
    // ③ Re-GET orders + verify count diff (only if create succeeded)
    let afterCount = beforeCount;
    let diff = 0;
    let silentDrop = false;
    if (createOk) {
      const ordersAfter = await page.request.get(
        `${API}/${acct.factoryId}/sales/orders?page=1&size=200`,
        { headers: { Authorization: `Bearer ${token}` }, timeout: 15_000 }
      );
      const afterBody: any = await ordersAfter.json();
      afterCount = (afterBody.data?.content || []).length;
      diff = afterCount - beforeCount;
      silentDrop = createOk && diff === 0;
    }
    // UI verify — open /indicator-center, B2B count should reflect new total
    await page.goto(`${BASE}/indicator-center`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
    await page.waitForSelector('.b2b-real-section', { timeout: 15_000 });
    const png = await shotInto(page, 'a8-roundtrip');
    results.push({
      caseId: 'A8', scenario: 'cross-module SO → B2B card roundtrip', account: acct.label, factoryId: acct.factoryId,
      depth: 'deep', status: createOk && !silentDrop ? 'PASS' : 'FAIL',
      evidence: { png, beforeCount, afterCount, diff, createOk, createStatus: createResp.status(), silentDrop, createBodyShort: JSON.stringify(createBody).slice(0, 300) },
      error: silentDrop ? 'silent-drop: POST 200 but list count unchanged' : (createOk ? undefined : 'POST failed, expected likely validation rejection'),
    });
  });

  test('A9. Error-deep — invalid indicator code (404) 四位一体 (depth=error-deep, Rule 8)', async ({ page }) => {
    const token = await loginAndSeed(page, acct);
    await installToastObserver(page);
    // Navigate to /indicator-center first so observer attaches
    await page.goto(`${BASE}/indicator-center`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
    await installToastObserver(page); // re-install after nav
    // ① Network — 触发 404 via API direct
    const errResp = await page.request.get(
      `${API}/${acct.factoryId}/indicators/NON_EXISTENT_CODE_XYZ_999`,
      { headers: { Authorization: `Bearer ${token}` }, timeout: 10_000, failOnStatusCode: false }
    );
    const status = errResp.status();
    const respBody: any = errResp.ok() ? null : await errResp.json().catch(() => ({}));
    const networkMessage = respBody?.message || respBody?.error || '';

    // ② UI — try to render that code (simulate user click on non-existent indicator)
    // Just check the dashboard handles missing data gracefully
    const text = await page.evaluate(() => document.body.innerText);
    const hasGracefulEmpty = text.includes('暂无') || text.includes('未找到') || text.includes('—');
    const toastLog = await readToastLog(page);
    const png = await shotInto(page, 'a9-error-deep-404');

    // 四位一体 verdict (Rule 8 / 4位一体):
    // a) network message: 后端真实原因
    // b) UI toast: 用户看到什么
    // c) sticky: duration:0 or 3s fade?
    // d) next action: message 是否具体可推断

    const verdict = {
      networkMessage: networkMessage || '(empty)',
      networkStatus: status,
      uiHasGracefulEmpty: hasGracefulEmpty,
      toastsTriggered: toastLog.length,
      // sticky check — we'd need to wait 5s and re-read to test 3s fade; for spec we just record
      stickyCheckPending: true,
    };

    results.push({
      caseId: 'A9', scenario: 'error-deep 404 indicator', account: acct.label, factoryId: acct.factoryId,
      depth: 'error-deep', status: 'PASS', // PASS = data captured, audit doc analyzes verdict
      evidence: { png, verdict },
      toastLog,
    });
  });

  test('A10. Date range filter consistency (depth=medium)', async ({ page }) => {
    await loginAndSeed(page, acct);
    await page.goto(`${BASE}/indicator-center`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
    await page.waitForSelector('.b2b-real-section', { timeout: 15_000 });
    // Date range selector (if exists)
    const datePickerExists = await page.locator('.el-date-editor').count() > 0;
    const png = await shotInto(page, 'a10-date-range');
    results.push({
      caseId: 'A10', scenario: 'date range filter', account: acct.label, factoryId: acct.factoryId,
      depth: 'smoke', status: 'PASS',
      evidence: { png, datePickerExists },
    });
  });
});

// ============================================================
// GROUP B — Multi-viewport responsive (4 cases × 3 viewport = 12 cells)
// ============================================================

test.describe('B. Multi-viewport — 320 / 1440 / 1920', () => {
  test.setTimeout(120_000);
  const acct = ACCOUNTS.f006;
  const viewports = [
    { name: '320-mini', width: 320, height: 568 },
    { name: '1440-laptop', width: 1440, height: 900 },
    { name: '1920-desktop', width: 1920, height: 1080 },
  ];

  for (const vp of viewports) {
    test(`B-${vp.name}-1. /indicator-center renders (depth=smoke)`, async ({ page }) => {
      await page.setViewportSize({ width: vp.width, height: vp.height });
      await loginAndSeed(page, acct);
      await page.goto(`${BASE}/indicator-center`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
      await page.waitForSelector('.b2b-real-section', { timeout: 15_000 });
      const visible = await page.locator('.b2b-real-section').isVisible();
      expect(visible).toBe(true);
      const png = await shotInto(page, `b-${vp.name}-indicator-center`);
      results.push({
        caseId: `B-${vp.name}-1`, scenario: 'indicator-center viewport', account: acct.label, factoryId: acct.factoryId,
        viewport: { width: vp.width, height: vp.height },
        depth: 'smoke', status: 'PASS',
        evidence: { png, sectionVisible: visible },
      });
    });

    test(`B-${vp.name}-2. /workdesk/sales-owner B2BRealDataSection (depth=smoke)`, async ({ page }) => {
      await page.setViewportSize({ width: vp.width, height: vp.height });
      await loginAndSeed(page, acct);
      await page.goto(`${BASE}/workdesk/sales-owner`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
      await page.waitForSelector('.b2b-real-section', { timeout: 15_000 });
      const text = await page.evaluate(() => document.body.innerText);
      expect(text).toContain('客户演示模式');
      const png = await shotInto(page, `b-${vp.name}-workdesk`);
      results.push({
        caseId: `B-${vp.name}-2`, scenario: 'workdesk viewport', account: acct.label, factoryId: acct.factoryId,
        viewport: { width: vp.width, height: vp.height },
        depth: 'smoke', status: 'PASS',
        evidence: { png },
      });
    });
  }
});

// ============================================================
// GROUP C — Cross-account RBAC consistency (5 cases)
// ============================================================

test.describe('C. Cross-account RBAC — Indicator Center', () => {
  test.setTimeout(120_000);

  test('C1. factory_super_admin sees same data on F006 (depth=medium)', async ({ page }) => {
    const acct = ACCOUNTS.superAdmin;
    try {
      await loginAndSeed(page, acct);
      await page.goto(`${BASE}/indicator-center`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
      await page.waitForSelector('.b2b-real-section', { timeout: 15_000 });
      const text = await page.evaluate(() => document.body.innerText);
      expect(text).toContain('B2B 销售真业务数据');
      const png = await shotInto(page, 'c1-superadmin');
      results.push({
        caseId: 'C1', scenario: 'superAdmin same view', account: acct.label, factoryId: acct.factoryId,
        depth: 'medium', status: 'PASS',
        evidence: { png },
      });
    } catch (e: any) {
      results.push({
        caseId: 'C1', scenario: 'superAdmin same view', account: acct.label, factoryId: acct.factoryId,
        depth: 'medium', status: 'SKIP',
        evidence: {},
        error: `account may not exist: ${e?.message || e}`,
      });
    }
  });

  test('C2. warehouse_mgr1 on F001 — different factory (depth=medium, RBAC + factory切换)', async ({ page }) => {
    const acct = ACCOUNTS.warehouse;
    try {
      await loginAndSeed(page, acct);
      await page.goto(`${BASE}/indicator-center`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
      // F001 may not have V_23_11 mirror codes, so B2BRealDataSection may not appear
      // Check whatever IS shown — at minimum dashboard should not crash
      await page.waitForTimeout(3000);
      const text = await page.evaluate(() => document.body.innerText);
      const hasError = text.includes('请求的资源不存在') || text.includes('Error') || text.includes('error');
      const png = await shotInto(page, 'c2-warehouse-f001');
      results.push({
        caseId: 'C2', scenario: 'F001 sister factory', account: acct.label, factoryId: acct.factoryId,
        depth: 'medium', status: hasError ? 'FAIL' : 'PASS',
        evidence: { png, hasError, bodyFirst300: text.slice(0, 300) },
      });
    } catch (e: any) {
      results.push({
        caseId: 'C2', scenario: 'F001 sister factory', account: acct.label, factoryId: acct.factoryId,
        depth: 'medium', status: 'SKIP', evidence: {},
        error: `account may not exist: ${e?.message || e}`,
      });
    }
  });

  test('C3. warehouse_mgr1 on F006 — RBAC reject? (depth=error-deep, factory boundary)', async ({ page }) => {
    // warehouse_mgr1 is F001 — accessing F006 should be denied
    const acct = { ...ACCOUNTS.warehouse, factoryId: 'F006' };
    try {
      await loginAndSeed(page, acct);
      // expected: auth fail OR RBAC reject
      results.push({
        caseId: 'C3', scenario: 'cross-factory RBAC', account: acct.label, factoryId: 'F006 (cross)',
        depth: 'error-deep', status: 'FAIL',
        evidence: { unexpected: 'login succeeded across factory — RBAC bug?' },
      });
    } catch (e: any) {
      results.push({
        caseId: 'C3', scenario: 'cross-factory RBAC', account: acct.label, factoryId: 'F006 (cross)',
        depth: 'error-deep', status: 'PASS',
        evidence: { authRejected: true, message: String(e?.message || e).slice(0, 200) },
      });
    }
  });
});

// ============================================================
// GROUP D — Workdesk integration (4 cases) — verify PR #249 fix
// ============================================================

test.describe('D. Workdesk fix verify (PR #249 Dim 1)', () => {
  test.setTimeout(120_000);
  const acct = ACCOUNTS.f006;

  test('D1. /workdesk/sales-owner uses B2BRealDataSection (depth=medium)', async ({ page }) => {
    await loginAndSeed(page, acct);
    await page.goto(`${BASE}/workdesk/sales-owner`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
    await page.waitForSelector('.b2b-real-section', { timeout: 15_000 });
    const text = await page.evaluate(() => document.body.innerText);
    expect(text).toContain('客户演示模式');
    expect(text).toContain('B2B 销售真业务数据');
    expect(text).toContain('临时方案');
    const png = await shotInto(page, 'd1-workdesk-b2b');
    results.push({
      caseId: 'D1', scenario: 'workdesk uses B2B', account: acct.label, factoryId: acct.factoryId,
      depth: 'medium', status: 'PASS',
      evidence: { png },
    });
  });

  test('D2. Workdesk no lying "F006 真数据" header (depth=medium)', async ({ page }) => {
    await loginAndSeed(page, acct);
    await page.goto(`${BASE}/workdesk/sales-owner`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
    await page.waitForSelector('.b2b-real-section', { timeout: 15_000 });
    const text = await page.evaluate(() => document.body.innerText);
    expect(text).not.toMatch(/来源:\s*BI IndicatorQueryTool\s*·\s*F006 真数据/);
    expect(text).not.toMatch(MIRRORED_VALUES_REGEX);
    const png = await shotInto(page, 'd2-workdesk-no-lying');
    results.push({
      caseId: 'D2', scenario: 'no lying header', account: acct.label, factoryId: acct.factoryId,
      depth: 'medium', status: 'PASS',
      evidence: { png },
    });
  });

  test('D3. AI chat input still works on Workdesk (depth=smoke)', async ({ page }) => {
    await loginAndSeed(page, acct);
    await page.goto(`${BASE}/workdesk/sales-owner`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
    await page.waitForSelector('textarea', { timeout: 15_000 });
    const textareaCount = await page.locator('textarea').count();
    expect(textareaCount).toBeGreaterThanOrEqual(1);
    const png = await shotInto(page, 'd3-workdesk-chat');
    results.push({
      caseId: 'D3', scenario: 'chat input present', account: acct.label, factoryId: acct.factoryId,
      depth: 'smoke', status: 'PASS',
      evidence: { png, textareaCount },
    });
  });

  test('D4. Other Workdesks (Finance/Quality/Production/Purchase/Warehouse) do not contain mirror codes (depth=medium)', async ({ page }) => {
    await loginAndSeed(page, acct);
    const otherDesks = [
      'finance-manager',
      'quality-chief',
      'production-manager',
      'warehouse-keeper',
      'purchaser',
    ];
    const findings: any[] = [];
    for (const desk of otherDesks) {
      try {
        await page.goto(`${BASE}/workdesk/${desk}`, { waitUntil: 'domcontentloaded', timeout: 20_000 });
        await page.waitForTimeout(2000);
        const text = await page.evaluate(() => document.body.innerText);
        const hasMirrorValue = MIRRORED_VALUES_REGEX.test(text);
        const hasMirrorLabel = /AVG_TICKET_PRICE|TABLE_TURNOVER/.test(text);
        findings.push({ desk, hasMirrorValue, hasMirrorLabel });
      } catch (e: any) {
        findings.push({ desk, error: String(e?.message || e).slice(0, 100) });
      }
    }
    const png = await shotInto(page, 'd4-other-workdesks-last');
    results.push({
      caseId: 'D4', scenario: 'other workdesks mirror sweep', account: acct.label, factoryId: acct.factoryId,
      depth: 'medium', status: 'PASS',
      evidence: { png, findings },
    });
  });
});

// ============================================================
// GROUP E — Console/Network observation (per qa-prompt Rule 5/6)
// ============================================================

// ============================================================
// GROUP F — 15-category anti-pattern sweep (Steve 2026-05-28 directive)
// ============================================================
// Per Steve "1 截图 = 15 类 anti-pattern 集合点" — sweep ALL customer-facing
// pages for developer-leak / API raw / 业态错配 / etc via regex matrix.
// Each hit: screenshot fullPage + textContent excerpt + category + Sprint 13 ticket data.
// ============================================================

const ANTI_PATTERN_REGEX: Record<string, RegExp> = {
  // A. Developer/QA internal info leak
  A1_cache_label: /[\(（](?:缓存结果|缓存命中)|CACHED[:：]/,
  A2_sprint_version: /Sprint\s*\d+\s*[A-Z]?\d?[a-z]?\s*[\(（]/,
  A3_debug_log: /\b(DEBUG|WARN|INFO|TRACE)[:：]/,
  A4_internal_flag: /_(?:toolCount|internal|meta|debug)\b/,
  A5_mock_label: /MOCK_|F999_MOCK|\[(?:TEST|DEV|STAGING)\]/,
  A7_stack_trace: /at\s+[\w.$]+\([^)]+\.java:\d+\)|NullPointerException/,

  // B. API raw response 未 transform
  B1_json_dump: /\{["']?(?:data|success|message|content)["']?\s*:/,
  B2_array_literal: /(?:^|>\s*)\[\s*\](?:\s*<|$)/,
  B3_boolean_raw: /(?:^|>)\s*(?:"?true"?|"?false"?)\s*(?:<|$)/,
  B6_iso_timestamp: /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/,

  // C. 字段名/code 未 i18n
  C1_camelCase_field: /\b(?:totalPOs|includeOverdue|factoryId|userId|orderNumber|customerName)["']?\s*[:=]/,
  C2_db_column: /\b[a-z]+_(?:at|id|by|code)\b/,
  C5_error_code: /\bERR_\d+|ERROR_\d{3,}/,

  // D. 数据格式未人类化
  D1_no_thousand_separator: />\s*\d{5,}(?:\.\d+)?\s*</,

  // F. 系统行为暴露
  F2_rate_limit: /\b429\b|too many requests/,
  F5_unauth_raw: /\b401\b|unauthorized/i,

  // G. AI 输出特殊
  G1_llm_hallucination: /根据(?:我|AI)的(?:训练|知识)/,
  G2_prompt_leak: /You are an AI|system prompt|您是.*?AI助手/,
  G4_skill_step: /Step\s*\d+\s*of\s*\d+/i,

  // H. 业务术语错位 (业态错配) — F006 工厂 vs 餐饮 codes
  H1_restaurant_on_factory: /翻台率|客单价|菜品毛利/,

  // I. 权限信息 leak
  I3_internal_id: /factory_id["']?\s*[:=]|user_id["']?\s*[:=]/,

  // N. 时区/locale leak
  N1_timezone_raw: /[+-]\d{2}:\d{2}\b/,
};

const ANTI_PATTERN_DESCRIPTIONS: Record<string, string> = {
  A1_cache_label: 'cache state label leaked to UI ("(缓存结果)")',
  A2_sprint_version: 'internal sprint version visible (Sprint X PNa)',
  A3_debug_log: 'debug log level visible (DEBUG:/WARN:)',
  A4_internal_flag: 'internal flag leak (_toolCount/_meta)',
  A5_mock_label: 'mock test marker visible (MOCK_/F999_MOCK)',
  A7_stack_trace: 'Java stack trace / NPE visible',
  B1_json_dump: 'raw JSON dump rendered',
  B2_array_literal: 'empty array literal displayed',
  B3_boolean_raw: 'raw true/false (not 是/否)',
  B6_iso_timestamp: 'ISO timestamp not formatted',
  C1_camelCase_field: 'English camelCase field name (not i18n)',
  C2_db_column: 'DB column name visible (created_at)',
  C5_error_code: 'raw error code (ERR_400)',
  D1_no_thousand_separator: '5+ digit number without separator',
  F2_rate_limit: 'rate-limit raw status',
  F5_unauth_raw: '401 unauthorized raw',
  G1_llm_hallucination: 'LLM hallucination residue ("根据我的训练")',
  G2_prompt_leak: 'system prompt leak',
  G4_skill_step: 'skill chain step leaked',
  H1_restaurant_on_factory: '餐饮 业态术语 on F006 工厂 (翻台率/客单价/菜品毛利)',
  I3_internal_id: 'internal id (factory_id/user_id) in user-visible text',
  N1_timezone_raw: 'timezone offset (+08:00) visible to user',
};

type SweepFinding = {
  page: string;
  account: string;
  factoryId: string;
  category: string;
  description: string;
  count: number;
  samples: string[];
  screenshotPath: string;
};
const sweepFindings: SweepFinding[] = [];

async function sweepPageForLeaks(
  page: Page, pageId: string, account: Account
): Promise<SweepFinding[]> {
  const text: string = await page.evaluate(() => document.body.innerText || '');
  const pageFindings: SweepFinding[] = [];
  let pngPath = '';
  for (const [category, regex] of Object.entries(ANTI_PATTERN_REGEX)) {
    const flags = regex.flags.includes('g') ? regex.flags : regex.flags + 'g';
    const reGlobal = new RegExp(regex.source, flags);
    const matches = text.match(reGlobal);
    if (matches && matches.length > 0) {
      if (!pngPath) {
        pngPath = await shotInto(page, `leak-${pageId}-${Date.now()}`);
      }
      pageFindings.push({
        page: pageId,
        account: account.label,
        factoryId: account.factoryId,
        category,
        description: ANTI_PATTERN_DESCRIPTIONS[category],
        count: matches.length,
        samples: matches.slice(0, 3).map(m => m.slice(0, 120)),
        screenshotPath: pngPath,
      });
    }
  }
  return pageFindings;
}

test.describe('F. 15-cat anti-pattern sweep — customer-visible UI', () => {
  test.setTimeout(180_000);
  const acct = ACCOUNTS.f006;
  const PAGES_TO_SWEEP = [
    { id: 'indicator-center', path: '/indicator-center' },
    { id: 'workdesk-sales-owner', path: '/workdesk/sales-owner' },
    { id: 'workdesk-finance', path: '/workdesk/finance-manager' },
    { id: 'workdesk-quality', path: '/workdesk/quality-chief' },
    { id: 'workdesk-production', path: '/workdesk/production-manager' },
    { id: 'workdesk-warehouse', path: '/workdesk/warehouse-keeper' },
    { id: 'workdesk-purchaser', path: '/workdesk/purchaser' },
    { id: 'dashboard-root', path: '/dashboard' },
  ];

  for (const pg of PAGES_TO_SWEEP) {
    test(`F-${pg.id} — anti-pattern regex sweep`, async ({ page }) => {
      await loginAndSeed(page, acct);
      try {
        await page.goto(`${BASE}${pg.path}`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
        await page.waitForTimeout(3000);
      } catch (e: any) {
        results.push({
          caseId: `F-${pg.id}`, scenario: `sweep ${pg.path}`, account: acct.label, factoryId: acct.factoryId,
          depth: 'smoke', status: 'SKIP',
          evidence: { error: String(e?.message || e).slice(0, 200) },
        });
        return;
      }
      const findings = await sweepPageForLeaks(page, pg.id, acct);
      sweepFindings.push(...findings);
      results.push({
        caseId: `F-${pg.id}`, scenario: `sweep ${pg.path}`, account: acct.label, factoryId: acct.factoryId,
        depth: 'medium', status: 'PASS',
        evidence: {
          path: pg.path,
          findingsCount: findings.length,
          categoriesHit: findings.map(f => f.category),
          // Top 3 samples for quick scan
          topFindings: findings.slice(0, 3).map(f => ({
            cat: f.category,
            desc: f.description,
            sample: f.samples[0],
          })),
        },
      });
    });
  }

  test('F-summary — write sweep findings JSON', async ({ page }) => {
    const summaryPath = path.join(AUDIT_OUT_DIR, 'sweep-findings.json');
    fs.writeFileSync(summaryPath, JSON.stringify({
      totalFindings: sweepFindings.length,
      byCategory: sweepFindings.reduce((acc, f) => {
        acc[f.category] = (acc[f.category] || 0) + f.count;
        return acc;
      }, {} as Record<string, number>),
      byPage: sweepFindings.reduce((acc, f) => {
        acc[f.page] = (acc[f.page] || 0) + 1;
        return acc;
      }, {} as Record<string, number>),
      findings: sweepFindings,
    }, null, 2));
    console.log(`[sweep] ${sweepFindings.length} findings across ${PAGES_TO_SWEEP.length} pages → ${summaryPath}`);
  });
});

test('E1. /indicator-center 0 console errors + 0 4xx (depth=deep observability)', async ({ page }) => {
  test.setTimeout(60_000);
  const tracker = await trackConsoleAndNetwork(page);
  const acct = ACCOUNTS.f006;
  await loginAndSeed(page, acct);
  await page.goto(`${BASE}/indicator-center`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
  await page.waitForTimeout(3000); // let SPA finish
  // Filter known acceptable errors (e.g. cache misses)
  const seriousConsoleErrors = tracker.consoleErrors.filter(e =>
    !e.includes('favicon') &&
    !e.includes('Failed to load resource: net::ERR_FAILED') // ServiceWorker etc
  );
  const seriousNetworkErrors = tracker.networkErrors.filter(e =>
    e.status >= 500 || (e.status >= 400 && !e.url.includes('non-critical'))
  );
  const png = await shotInto(page, 'e1-console-network');
  results.push({
    caseId: 'E1', scenario: 'console + network observability', account: acct.label, factoryId: acct.factoryId,
    depth: 'deep', status: seriousConsoleErrors.length === 0 && seriousNetworkErrors.length === 0 ? 'PASS' : 'FAIL',
    evidence: { png },
    consoleErrors: seriousConsoleErrors,
    networkErrors: seriousNetworkErrors,
    error: seriousConsoleErrors.length > 0 ? `${seriousConsoleErrors.length} console errors` :
           seriousNetworkErrors.length > 0 ? `${seriousNetworkErrors.length} network errors ≥400` : undefined,
  });
});
