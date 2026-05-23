/**
 * Sprint 11 MealClaw Audit — Q7/Q8 UI-Level Playwright Evidence
 *
 * Purpose: 真 browser 复现 customer-visible 失败. PM Q1-Q6 evidence (curl level)
 * 显示 prod AIChat 4 phrase 返回 IDENTICAL 错误信息:
 *   "部分数据不可用: P&L 一页纸 / 档口损溢 / 成本刚性. 已基于可用数据生成分析,
 *    不可用部分需明确标注."
 * customer-visible text 100% 是 error dump. 本 spec 用 Playwright UI 截图 + 录屏
 * 作为 Q6 Option B 决策的 visual evidence.
 *
 * 测试账号: qhj_warehouse_mgr / 123456 (factory RES_3101_009, role=warehouse_manager)
 *   - 通过 prod web-admin (139.196.165.140:8086) login
 *   - 进 Warehouse Keeper Workdesk (复用通用 chat 输入 + /ai-intents/execute)
 *
 * 测试 4 phrase (per Q1 PM finding):
 *   - 帮我看上月损溢异常
 *   - 损益分析
 *   - 上月成本
 *   - 哪个菜亏钱
 *
 * 期望: UI 直接显示 "部分数据不可用..." 错误文本作为客户答案 (不是友好提示)
 *
 * Run:
 *   cd web-admin
 *   npx playwright test --project=mealclaw-customer-ui --reporter=list
 *
 * Override env:
 *   E2E_BASE_URL=http://139.196.165.140:8086  (prod web-admin, default)
 *   E2E_API_BASE=http://139.196.165.140:8086/api/mobile  (default same host)
 *   E2E_USER=qhj_warehouse_mgr (default)
 *   E2E_PASS=123456 (default)
 *   E2E_FACTORY_ID=RES_3101_009 (default)
 *
 * Output (committed):
 *   docs/audits/sprint-11-mealclaw-screenshots/*.png  — 4 phrase + 4 UX state
 *   test-results/mealclaw-customer-ui/.../*.webm     — Playwright auto recording
 */
import { test, expect, type Page, type BrowserContext } from '@playwright/test';
import path from 'path';
import { fileURLToPath } from 'url';
import { setupAuth } from '../../e2e-auth-helper';

// ESM doesn't have __dirname/__filename — recompute from import.meta.url.
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const BASE_URL = process.env.E2E_BASE_URL || 'http://139.196.165.140:8086';
const API_BASE = process.env.E2E_API_BASE || `${BASE_URL}/api/mobile`;
const USER = process.env.E2E_USER || 'qhj_warehouse_mgr';
const PASS = process.env.E2E_PASS || '123456';
const FACTORY_ID = process.env.E2E_FACTORY_ID || 'RES_3101_009';

// Screenshot output goes under repo root docs/, computed from spec file path.
// spec file: <repo>/web-admin/tests/e2e-customer-journey/mealclaw-customer.spec.ts
// target:    <repo>/docs/audits/sprint-11-mealclaw-screenshots
const SCREENSHOT_DIR = path.resolve(
  __dirname,
  '..', '..', '..', 'docs', 'audits', 'sprint-11-mealclaw-screenshots',
);

const PHRASES: { id: string; text: string; safeId: string }[] = [
  { id: 'P1', text: '帮我看上月损溢异常', safeId: 'phrase-1-shangyue-sunyi' },
  { id: 'P2', text: '损益分析',           safeId: 'phrase-2-sunyi-fenxi' },
  { id: 'P3', text: '上月成本',           safeId: 'phrase-3-shangyue-chengben' },
  { id: 'P4', text: '哪个菜亏钱',         safeId: 'phrase-4-nage-cai-kuiqian' },
];

const TARGET_WORKDESK = '/workdesk/warehouse-keeper';

const FORMATTED_OUTPUT_SELECTOR = '.formatted-output';
const ERROR_ALERT_SELECTOR = '.el-alert--error';
const LOADING_SELECTOR = '.loading-card';
// Chat input is a textarea (per WarehouseKeeperWorkdesk.vue template).
const CHAT_INPUT_SELECTOR = '.chat-input textarea';
const SEND_BUTTON_SELECTOR = '.chat-input button';

/**
 * Setup auth + navigate to target workdesk.
 * Disables auto-trigger by clearing default chat input.
 */
async function gotoWorkdesk(page: Page, context: BrowserContext): Promise<void> {
  await setupAuth(context, page, BASE_URL, API_BASE, USER, PASS);
  await page.goto(`${BASE_URL}${TARGET_WORKDESK}`, {
    waitUntil: 'domcontentloaded',
    timeout: 30000,
  });
  // Workdesk auto-triggers default query on mount — wait it out + clear chat
  // so our test typing isn't mixed with the default response.
  await page.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => {});
  await page.waitForTimeout(8000); // let auto-trigger settle (it returns ~3-5s)
}

/**
 * Send a phrase into the chat input + listen for /ai-intents/execute network
 * response. Returns object describing rendered UI + raw network body. The
 * network body is the AUTHORITATIVE record (PM Q1-Q5 used curl-level evidence);
 * UI text captures what the user actually sees on screen.
 */
async function sendPhraseAndWait(page: Page, phrase: string): Promise<{
  formattedText: string | null;
  errorText: string | null;
  htmlSnippet: string;
  bodyTextSnippet: string;
  apiStatus: number | null;
  apiBody: string | null;
}> {
  // Pre-arm response listener for /ai-intents/execute (the customer-facing call).
  let apiStatus: number | null = null;
  let apiBody: string | null = null;
  const apiPromise = page
    .waitForResponse(
      (r) => r.url().includes('/ai-intents/execute') && r.request().method() === 'POST',
      { timeout: 30000 },
    )
    .then(async (response) => {
      apiStatus = response.status();
      try {
        apiBody = await response.text();
      } catch {
        apiBody = '(could not read body)';
      }
    })
    .catch(() => {
      // timeout — leave nulls
    });

  const input = page.locator(CHAT_INPUT_SELECTOR).first();
  await input.click();
  // Use fill() instead of clear() + type — fill() replaces the existing text.
  await input.fill(phrase);
  await page.waitForTimeout(300);

  // Click send button (text matches "发送" — substring tolerant of suffix).
  const sendBtn = page.locator(SEND_BUTTON_SELECTOR).filter({ hasText: /发送/ });
  await sendBtn.click();

  // Wait for API + either UI output OR error.
  await apiPromise;
  await Promise.race([
    page.waitForSelector(FORMATTED_OUTPUT_SELECTOR, { timeout: 30000 }).catch(() => null),
    page.waitForSelector(ERROR_ALERT_SELECTOR, { timeout: 30000 }).catch(() => null),
    page.waitForTimeout(30000),
  ]);
  // Extra settle for any post-render UI updates.
  await page.waitForTimeout(1500);

  const formattedEl = page.locator(FORMATTED_OUTPUT_SELECTOR).first();
  const errorEl = page.locator(ERROR_ALERT_SELECTOR).first();
  const formattedText = (await formattedEl.isVisible().catch(() => false))
    ? ((await formattedEl.textContent()) ?? '').trim()
    : null;
  const errorText = (await errorEl.isVisible().catch(() => false))
    ? ((await errorEl.textContent()) ?? '').trim()
    : null;
  const bodyTextSnippet = ((await page.locator('body').textContent()) ?? '').slice(0, 2000);
  const htmlSnippet = (await page.content()).slice(0, 3000);

  return { formattedText, errorText, htmlSnippet, bodyTextSnippet, apiStatus, apiBody };
}

test.describe('Sprint 11 MealClaw Audit — Q7/Q8 UI-Level Customer Journey', () => {
  test.setTimeout(180000);

  for (const { id, text, safeId } of PHRASES) {
    test(`Q7-${id}: Customer asks "${text}" — capture UI response`, async ({ page, context }) => {
      await gotoWorkdesk(page, context);
      const result = await sendPhraseAndWait(page, text);

      // Capture full-page screenshot regardless of result (visual evidence).
      const ssPath = path.join(SCREENSHOT_DIR, `${safeId}-happy-path.png`);
      await page.screenshot({ path: ssPath, fullPage: true });

      console.log(`\n--- Q7-${id} ${text} ---`);
      console.log(`screenshot:               ${ssPath}`);
      console.log(`API status:               ${result.apiStatus}`);
      console.log(`API body (raw):           ${result.apiBody?.slice(0, 800) ?? '(no body)'}`);
      console.log(`formatted-output visible: ${result.formattedText !== null}`);
      console.log(`error-alert visible:      ${result.errorText !== null}`);
      console.log(`UI formattedText:         ${result.formattedText?.slice(0, 300) ?? '(null)'}`);
      console.log(`UI errorText:             ${result.errorText?.slice(0, 300) ?? '(null)'}`);
      console.log(`body snippet:             ${result.bodyTextSnippet.slice(0, 400)}`);

      test.info().annotations.push({
        type: 'api-status',
        description: String(result.apiStatus),
      });
      test.info().annotations.push({
        type: 'api-body',
        description: (result.apiBody ?? '(null)').slice(0, 1500),
      });

      test.info().annotations.push({
        type: 'ui-formatted-output',
        description: result.formattedText ?? '(not rendered)',
      });
      test.info().annotations.push({
        type: 'ui-error-alert',
        description: result.errorText ?? '(not rendered)',
      });

      // ASSERTION: spec verifies SOMETHING was rendered (output or error). Pass if
      // either appears — we're capturing visual evidence, not failing on shape.
      // (Q1 PM evidence already confirms formattedText = "部分数据不可用..." dump.)
      expect(result.formattedText !== null || result.errorText !== null,
        `Q7-${id}: expected UI to render either formatted-output OR error-alert. Body snippet: ${result.bodyTextSnippet.slice(0, 500)}`,
      ).toBeTruthy();
    });
  }

  test('Q8-UX: 6 UX state audit (loading / error rendered / mobile 320 / readability 200%)', async ({ page, context }) => {
    await gotoWorkdesk(page, context);

    // State 1: input filled, send clicked, loading state captured (use fast snapshot)
    const input = page.locator(CHAT_INPUT_SELECTOR).first();
    await input.click();
    await input.fill(PHRASES[0].text);
    await page.waitForTimeout(200);
    const sendBtn = page.locator(SEND_BUTTON_SELECTOR).filter({ hasText: /发送/ });
    // Concurrent: click + screenshot to capture loading
    await Promise.all([
      sendBtn.click(),
      (async () => {
        // small wait so loading card renders
        await page.waitForTimeout(800);
        const ss1 = path.join(SCREENSHOT_DIR, 'ux-1-loading-state.png');
        await page.screenshot({ path: ss1, fullPage: true });
        console.log(`UX-1 loading: ${ss1}`);
      })(),
    ]);

    // State 2: wait for response (error or output) — captures rendered error dump
    await Promise.race([
      page.waitForSelector(FORMATTED_OUTPUT_SELECTOR, { timeout: 30000 }).catch(() => null),
      page.waitForSelector(ERROR_ALERT_SELECTOR, { timeout: 30000 }).catch(() => null),
    ]);
    await page.waitForTimeout(2000);
    const ss2 = path.join(SCREENSHOT_DIR, 'ux-2-error-rendered.png');
    await page.screenshot({ path: ss2, fullPage: true });
    console.log(`UX-2 error rendered: ${ss2}`);

    // State 3: mobile viewport 320×568 (responsive audit)
    await page.setViewportSize({ width: 320, height: 568 });
    await page.waitForTimeout(800);
    const ss3 = path.join(SCREENSHOT_DIR, 'ux-3-mobile-320.png');
    await page.screenshot({ path: ss3, fullPage: true });
    console.log(`UX-3 mobile 320px: ${ss3}`);

    // State 4: readability at 200% zoom
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.evaluate(() => {
      (document.body.style as unknown as Record<string, string>).zoom = '200%';
    });
    await page.waitForTimeout(500);
    const ss4 = path.join(SCREENSHOT_DIR, 'ux-4-readability-200pct.png');
    await page.screenshot({ path: ss4, fullPage: true });
    console.log(`UX-4 readability 200%: ${ss4}`);

    // State 5: reset zoom + capture entire chat thread (workdesk + chat card + result)
    await page.evaluate(() => {
      (document.body.style as unknown as Record<string, string>).zoom = '100%';
    });
    await page.waitForTimeout(500);
    const ss5 = path.join(SCREENSHOT_DIR, 'ux-5-full-thread.png');
    await page.screenshot({ path: ss5, fullPage: true });
    console.log(`UX-5 full thread: ${ss5}`);

    // State 6: zoom into just the error/formatted-output card (cropped)
    const formattedEl = page.locator(FORMATTED_OUTPUT_SELECTOR).first();
    const errorEl = page.locator(ERROR_ALERT_SELECTOR).first();
    const cropTarget = (await formattedEl.isVisible().catch(() => false))
      ? formattedEl
      : (await errorEl.isVisible().catch(() => false))
        ? errorEl
        : null;
    if (cropTarget) {
      const ss6 = path.join(SCREENSHOT_DIR, 'ux-6-output-card-cropped.png');
      await cropTarget.screenshot({ path: ss6 });
      console.log(`UX-6 cropped output card: ${ss6}`);
    } else {
      console.log('UX-6 SKIP — no output or error element to crop');
    }
  });
});
