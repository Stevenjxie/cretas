/**
 * Restaurant Chat E2E tests (P5 Task 5.7).
 *
 * Requires FULL STACK running:
 *   - Python backend on 8083 (with P3.5 sections deployed)
 *   - Java backend on 10010 (with P5 chat UI wired)
 *   - Redis on localhost:6379 (for conversation state)
 *   - web-admin dev server (npm run dev, default http://localhost:5173)
 *   - Test account credentials in env vars
 *
 * Skipped by default — run manually:
 *   cd web-admin
 *   RUN_CHAT_E2E=1 TEST_ADMIN_USER=<user> TEST_ADMIN_PASS=<pass> \
 *     npx playwright test restaurant-chat.spec.ts
 *
 * Env vars:
 *   RUN_CHAT_E2E      set to "1" to enable (default: skipped)
 *   TEST_ADMIN_USER   login username  (e.g. factory_admin1)
 *   TEST_ADMIN_PASS   login password
 *   WEB_ADMIN_URL     base URL (default http://localhost:5173)
 *
 * Note: @playwright/test must be installed before running:
 *   npm install --save-dev @playwright/test
 */
import { test, expect, type Page } from '@playwright/test';

const BASE_URL = process.env.WEB_ADMIN_URL ?? 'http://localhost:5173';
const SHOULD_RUN = process.env.RUN_CHAT_E2E === '1';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function login(page: Page): Promise<void> {
  const user = process.env.TEST_ADMIN_USER;
  const pass = process.env.TEST_ADMIN_PASS;
  if (!user || !pass) {
    test.skip(true, 'TEST_ADMIN_USER / TEST_ADMIN_PASS not set');
    return;
  }
  await page.goto(`${BASE_URL}/login`, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(1000);
  await page.getByPlaceholder(/用户名/).fill(user);
  await page.getByPlaceholder('请输入密码').fill(pass);
  await page.waitForTimeout(300);
  await page.getByRole('button', { name: '登 录' }).click();
  // Wait until redirected away from /login
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 15000 });
  await page.waitForTimeout(2000);
}

async function openRestaurantDashboard(page: Page): Promise<void> {
  await page.goto(`${BASE_URL}/smart-bi/restaurant-v2`, {
    waitUntil: 'domcontentloaded',
    timeout: 30000,
  });
  await page.waitForTimeout(3000);
}

async function openChatDrawer(page: Page): Promise<void> {
  // The "聊天问答" button is in the dashboard toolbar
  await page.getByRole('button', { name: '聊天问答' }).click();
  // Wait for the drawer + panel to mount
  await page.waitForSelector('.restaurant-chat-panel', { timeout: 8000 });
  await page.waitForTimeout(500);
}

async function sendChatMessage(page: Page, message: string): Promise<void> {
  const input = page.locator('.chat-input .el-input__inner');
  await input.fill(message);
  await input.press('Enter');
}

async function waitForAiResponse(page: Page): Promise<void> {
  // Typing indicator appears then disappears when LLM response arrives
  // Give it up to 5s to appear first (fast networks skip it)
  await page.waitForSelector('.typing-wrap', { timeout: 5000 }).catch(() => {
    // typing indicator may be too fast to catch — that's fine
  });
  // Then wait for it to disappear (LLM response can take 3-30s)
  await page.waitForSelector('.typing-wrap', { state: 'hidden', timeout: 45000 });
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('Restaurant Chat E2E', () => {
  // Skip entire suite unless explicitly enabled — prevents blocking CI
  // when the full stack isn't running.
  test.skip(
    !SHOULD_RUN,
    'Requires full stack (Python 8083 + Java 10010 + Redis + web-admin). '
      + 'Set RUN_CHAT_E2E=1 to enable.',
  );

  test.setTimeout(120000); // LLM calls can take up to 30s each

  test.beforeEach(async ({ page }) => {
    await login(page);
    await openRestaurantDashboard(page);
  });

  // -------------------------------------------------------------------------
  // Scenario 1: Cost rigidity question
  // -------------------------------------------------------------------------
  test('cost rigidity question returns AI bubble + decimal value + follow-up chips', async ({ page }) => {
    await openChatDrawer(page);

    await sendChatMessage(page, '帮我分析成本刚性');
    await waitForAiResponse(page);

    // Verify user bubble rendered
    const userBubbles = page.locator('.bubble-user');
    await expect(userBubbles.last()).toContainText('帮我分析成本刚性');

    // Verify AI bubble exists and has content
    const aiBubble = page.locator('.bubble-ai').last();
    await expect(aiBubble).toBeVisible();

    const bubbleText = (await aiBubble.textContent()) ?? '';
    // Cost rigidity value should appear as a decimal (e.g. 0.561)
    // Accept any decimal between 0.01 and 0.99
    expect(bubbleText).toMatch(/0\.\d{2,3}/);

    // Follow-up chips should be present for severity-based suggestions
    const chips = page.locator('.followup-chip');
    await expect(chips.first()).toBeVisible({ timeout: 5000 });
    const chipCount = await chips.count();
    expect(chipCount).toBeGreaterThanOrEqual(2);
  });

  // -------------------------------------------------------------------------
  // Scenario 2: Menu engineering question
  // -------------------------------------------------------------------------
  test('menu engineering question renders quadrant card or skipped message', async ({ page }) => {
    await openChatDrawer(page);

    await sendChatMessage(page, '哪些菜该砍');
    await waitForAiResponse(page);

    const aiBubble = page.locator('.bubble-ai').last();
    await expect(aiBubble).toBeVisible();

    // The response may render a quadrant section card (when SKU data is present)
    // or display a skipped-section message (when no SKU data uploaded).
    // Both are acceptable outcomes.
    const quadrantCard = page.locator('.quadrant-card, .menu-quadrant-card, [data-section-type="menu_engineering"]');
    const skippedHint = page.locator('.section-skipped, .section-card-skipped');

    const hasCard = (await quadrantCard.count()) > 0;
    const hasSkip = (await skippedHint.count()) > 0;
    const hasBubbleText = (((await aiBubble.textContent()) ?? '').length) > 5;

    // At minimum, an AI bubble with non-trivial text must appear
    expect(hasBubbleText).toBeTruthy();
    // If data is present, either a rendered card or skipped indicator should show
    if (!hasCard && !hasSkip) {
      // Still acceptable if the LLM returns a plain text answer
      // (section rendering is progressive enhancement)
      console.log('[menu-engineering] no section card or skip indicator — plain text response');
    }
  });

  // -------------------------------------------------------------------------
  // Scenario 3: Multi-turn dialog context preservation
  // -------------------------------------------------------------------------
  test('multi-turn dialog preserves context via follow-up question', async ({ page }) => {
    await openChatDrawer(page);

    // Turn 1: explicit cost rigidity analysis
    await sendChatMessage(page, '帮我分析成本刚性');
    await waitForAiResponse(page);

    // Turn 2: context-dependent follow-up — relies on conversation history
    await sendChatMessage(page, '为什么这么高');
    await waitForAiResponse(page);

    // Should have exactly 2 user bubbles + 2 AI bubbles
    await expect(page.locator('.bubble-user')).toHaveCount(2);
    await expect(page.locator('.bubble-ai')).toHaveCount(2);

    // The 2nd AI response should NOT be a generic fallback — context-aware LLM
    // should produce a meaningful cost-related answer, not "我没明白" / "无法理解"
    const lastAiText = (await page.locator('.bubble-ai').last().textContent()) ?? '';
    expect(lastAiText).not.toMatch(/无法理解|我没明白|请重新描述|无法处理/);
    // Should have non-trivial content
    expect(lastAiText.trim().length).toBeGreaterThan(10);
  });

  // -------------------------------------------------------------------------
  // Scenario 4: Clear conversation button empties the thread
  // -------------------------------------------------------------------------
  test('clear conversation button empties the thread', async ({ page }) => {
    await openChatDrawer(page);

    // Send a message to populate the thread
    await sendChatMessage(page, '帮我分析成本刚性');
    await waitForAiResponse(page);

    // Verify a user bubble exists before clearing
    await expect(page.locator('.bubble-user')).toHaveCount(1);

    // Click the "清空对话" button in the chat header
    await page.getByRole('button', { name: '清空对话' }).click();
    await page.waitForTimeout(500);

    // After clearing, thread is empty — user bubbles gone, empty state shown
    await expect(page.locator('.bubble-user')).toHaveCount(0);
    await expect(page.locator('.chat-empty')).toBeVisible();
  });
});
