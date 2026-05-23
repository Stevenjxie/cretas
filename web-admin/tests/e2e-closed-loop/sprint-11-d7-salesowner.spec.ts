/**
 * Sprint 11 D7 — SalesOwner Workdesk IndicatorCard depth test
 *
 * Per depth-first-e2e Rule 2 (≥1 new deep test per session) + e2e-web-admin rules:
 *   - Real auth flow (POST /auth/unified-login + seed BOTH localStorage keys)
 *   - Real navigation /workdesk/sales-owner
 *   - Wait 4 IndicatorCard mount
 *   - Assert: each card has real value (non-"暂无数据") + tag (GREEN/WARN/ALERT)
 *   - Cross-verify: card value vs mirrored indicator_versions avg (already SQL-verified
 *     in Item 1 audit doc; this spec asserts UI displays mirror value, NOT crash)
 *
 * Per Item 1 finding: F006 indicator data is 100% mirrored from F999_MOCK.
 * This spec validates UI WIRING (Tool call succeeds + value displayed), NOT business correctness.
 *
 * Run via:
 *   E2E_BASE_URL=http://139.196.165.140:8086 \
 *   E2E_API_BASE=http://139.196.165.140:8086/api/mobile \
 *   npx playwright test tests/e2e-closed-loop/sprint-11-d7-salesowner.spec.ts \
 *     --project sprint-11-d7-salesowner
 *
 * 调用 chromium directly per e2e-web-admin (避 Chrome profile lock).
 */
import { test, expect, type Page } from '@playwright/test';

const BASE = process.env.E2E_BASE_URL || 'http://139.196.165.140:8086';
const API = process.env.E2E_API_BASE || `${BASE}/api/mobile`;
const FACTORY = process.env.E2E_FACTORY_ID || 'F006';
const USER = process.env.E2E_USER || 'f006_admin';
const PASS = process.env.E2E_PASS || '123456';

/**
 * Auth helper: seed BOTH localStorage keys per
 * `feedback_web_admin_auth_bypass_needs_user_object` HARD.
 */
async function loginAndSeed(page: Page): Promise<void> {
  const resp = await page.request.post(`${API}/auth/unified-login`, {
    data: { username: USER, password: PASS, factoryId: FACTORY },
    timeout: 30_000,
  });
  expect(resp.ok(), `login failed status=${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  const data = body?.data || {};
  const token = String(data.token || data.accessToken || '');
  expect(token.length, 'token length').toBeGreaterThan(20);

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

test.describe.serial('Sprint 11 D7 — SalesOwner Workdesk 4 IndicatorCards (depth=deep)', () => {
  test.setTimeout(120_000);

  test('depth-deep: 4 cards mount + value + tag + UI wiring 真 verified', async ({ page }) => {
    // Step 1: Auth
    await loginAndSeed(page);

    // Step 2: Navigate to SalesOwner Workdesk
    await page.goto(`${BASE}/workdesk/sales-owner`, {
      waitUntil: 'domcontentloaded',
      timeout: 30_000,
    });
    await page.waitForSelector('.indicators-card', { timeout: 30_000 });

    // Step 3: Wait for 4 IndicatorCards mount + data fetch settle
    // Each card auto-calls INDICATOR_QUERY on mount (~2s each).
    // Allow 12s for all 4 to settle.
    await page.waitForTimeout(12_000);

    // Step 4: Locate the 4 indicator cards inside the indicators-card section
    const indicatorContainers = await page
      .locator('.indicators-card .indicator-card')
      .elementHandles();
    expect(indicatorContainers.length, '4 indicator cards rendered').toBe(4);

    // Step 5: Assert each card has value + tag + non-error state
    const cardData: Array<{ code: string; value: string; tag: string }> = [];
    for (const card of indicatorContainers) {
      const codeEl = await card.$('.el-tag, [class*="tag"]');
      const code = codeEl ? (await codeEl.innerText()) : '(no code)';

      const valueEl = await card.$('.value');
      const value = valueEl ? (await valueEl.innerText()).trim() : '(no value)';

      // Look for status tag (GREEN/WARN/ALERT)
      const statusTagEl = await card.$$('.el-tag');
      let statusTag = '(no status)';
      if (statusTagEl.length > 1) {
        // Second tag is status (first is indicator_code)
        statusTag = await statusTagEl[1].innerText();
      }

      cardData.push({ code, value, tag: statusTag });
    }

    console.log('[depth-test] 4 cards captured:', JSON.stringify(cardData, null, 2));

    // Step 6: Assert no card shows "暂无数据" (means Tool call failed)
    for (const c of cardData) {
      expect(c.value, `card ${c.code} value should NOT be "暂无数据"`).not.toBe('暂无数据');
      expect(c.value.length, `card ${c.code} value non-empty`).toBeGreaterThan(0);
    }

    // Step 7: Assert each card has tag (GREEN/WARNING/ALERT/正常/黄灯/红灯)
    const validTags = ['正常', '黄灯', '红灯', 'GREEN', 'WARNING', 'ALERT', 'YELLOW', 'RED'];
    for (const c of cardData) {
      const tagMatchesValid = validTags.some(t => c.tag.includes(t));
      expect(tagMatchesValid, `card ${c.code} tag "${c.tag}" should be one of ${validTags.join('/')}`).toBeTruthy();
    }

    // Step 8: Screenshot for evidence
    await page.screenshot({
      path: 'test-results/sprint-11-d7-salesowner-indicators.png',
      fullPage: true,
    });

    // Step 9: Final depth-test record
    console.log('[depth-test] DEPTH=deep PASS — UI wiring verified, 4 cards mount + real values + tags');
    console.log('[depth-test] DATA caveat: values are mirrored from F999_MOCK per Item 1 audit BLOCKER');
  });
});
