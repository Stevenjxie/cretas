import { test, expect, type Page, type TestInfo } from '@playwright/test';

const BASE_URL = process.env.WEB_ADMIN_URL ?? 'https://admin.cretaceousfuture.com';
const START_URL = `${BASE_URL}/demo?tenant=rest&redirect=/smart-bi/query`;
const SHOULD_RUN = process.env.RUN_RESTAURANT_OWNER_AI_DEMO_E2E === '1';

type IntentResponseSummary = {
  ok: boolean;
  statusCode: number;
  intentCode: string | null;
  source: string | null;
  sessionId: string | null;
  message: string;
};

function normalizeText(value: unknown): string {
  return String(value ?? '').replace(/\s+/g, ' ').trim();
}

function parsePostData(raw: string | null): Record<string, unknown> {
  if (!raw) return {};
  try {
    return JSON.parse(raw) as Record<string, unknown>;
  } catch {
    return { parseError: raw };
  }
}

function unwrapIntentBody(body: unknown): Record<string, unknown> {
  const asRecord = body as Record<string, unknown>;
  return (asRecord?.data as Record<string, unknown>) ?? asRecord ?? {};
}

function summarizeIntentResponse(body: unknown, statusCode: number, ok: boolean): IntentResponseSummary {
  const data = unwrapIntentBody(body);
  const resultData = (data.resultData as Record<string, unknown>) ?? {};
  return {
    ok,
    statusCode,
    intentCode: (data.intentCode as string | undefined) ?? null,
    source: ((resultData.source ?? resultData.advisorSource) as string | undefined) ?? null,
    sessionId: ((resultData.sessionId ?? resultData.session_id) as string | undefined) ?? null,
    message: normalizeText(data.message),
  };
}

function findVisibleInput(page: Page) {
  return page.locator('.input-area textarea, .input-area input').first();
}

async function assistantBlocks(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const candidates = [
      ...document.querySelectorAll('.message-item.assistant'),
      ...document.querySelectorAll('.message.assistant'),
      ...document.querySelectorAll('.chat-message.assistant'),
      ...document.querySelectorAll('[class*="assistant"]'),
    ];
    const seen = new Set<Element>();
    return candidates
      .filter((el) => {
        if (seen.has(el)) return false;
        seen.add(el);
        return Boolean((el.textContent ?? '').trim());
      })
      .map((el) => (el.textContent ?? '').trim())
      .filter(Boolean);
  });
}

async function waitForAssistantDone(page: Page, previousCount: number): Promise<void> {
  await page.waitForFunction(
    ({ previousCount: countBefore }) => {
      const items = [
        ...document.querySelectorAll('.message-item.assistant'),
        ...document.querySelectorAll('.message.assistant'),
        ...document.querySelectorAll('.chat-message.assistant'),
        ...document.querySelectorAll('[class*="assistant"]'),
      ].filter((el) => (el.textContent ?? '').trim());
      const loading = Boolean(document.querySelector('.input-area .el-button.is-loading'));
      const bodyTail = (document.body.innerText ?? '').slice(-800);
      return items.length > countBefore && !loading && !/思考中|分析中|正在|加载中/.test(bodyTail);
    },
    { previousCount },
    { timeout: 180_000 },
  );
  await page.waitForTimeout(500);
}

async function openDemo(page: Page): Promise<void> {
  await page.goto(START_URL, { waitUntil: 'domcontentloaded', timeout: 60_000 });
  await page.waitForLoadState('networkidle', { timeout: 60_000 }).catch(() => undefined);
  await page.waitForURL(/smart-bi\/(query|analysis)/, { timeout: 60_000 }).catch(() => undefined);
  await findVisibleInput(page).waitFor({ state: 'visible', timeout: 60_000 });
}

async function sendPrompt(page: Page, prompt: string) {
  const previous = await assistantBlocks(page);
  const requestPromise = page.waitForRequest((request) => request.url().includes('/ai-intents/execute'), {
    timeout: 60_000,
  });
  const responsePromise = page.waitForResponse((response) => response.url().includes('/ai-intents/execute'), {
    timeout: 180_000,
  });

  const input = findVisibleInput(page);
  await input.fill(prompt);
  await page.locator('.input-area .el-button').last().click();

  const [request, response] = await Promise.all([requestPromise, responsePromise]);
  const rawResponse = await response.json().catch(() => ({}));
  await waitForAssistantDone(page, previous.length);
  const after = await assistantBlocks(page);

  return {
    prompt,
    requestBody: parsePostData(request.postData()),
    response: summarizeIntentResponse(rawResponse, response.status(), response.ok()),
    answer: after[after.length - 1] ?? '',
  };
}

async function attachScreenshot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const screenshot = await page.screenshot({ fullPage: true });
  await testInfo.attach(name, { body: screenshot, contentType: 'image/png' });
}

test.describe('restaurant owner AI demo', () => {
  test.skip(
    !SHOULD_RUN,
    'Set RUN_RESTAURANT_OWNER_AI_DEMO_E2E=1 to verify the deployed restaurant demo owner-AI flow.',
  );

  test.setTimeout(240_000);

  test('keeps package scenario through manual follow-up questions', async ({ page }, testInfo) => {
    await openDemo(page);

    const first = await sendPrompt(page, '根据菜品毛利和成本，帮我算一个适合今天推的小套餐');
    const second = await sendPrompt(page, '如果换一个小食，怎么重新算？');
    const third = await sendPrompt(page, '明天怎么判断这个套餐要不要停？');
    await attachScreenshot(page, testInfo, 'package-context-final');

    expect(first.requestBody.context).toMatchObject({ ownerActionScenario: 'package' });
    expect(first.response).toMatchObject({
      ok: true,
      intentCode: 'RESTAURANT_OWNER_ACTION_CHAT',
      source: 'restaurant_owner_action',
    });
    expect(first.response.sessionId).toBeTruthy();

    expect(second.requestBody.context).toMatchObject({
      ownerActionScenario: 'package',
      ownerActionSessionId: first.response.sessionId,
    });
    expect(third.requestBody.context).toMatchObject({
      ownerActionScenario: 'package',
      ownerActionSessionId: first.response.sessionId,
    });

    for (const step of [second, third]) {
      expect(step.response).toMatchObject({
        ok: true,
        intentCode: 'RESTAURANT_OWNER_ACTION_CHAT',
        source: 'restaurant_owner_action',
      });
    }
    expect(normalizeText(second.answer)).toMatch(/小食|重新|换|毛利|成本/);
    expect(normalizeText(third.answer)).toMatch(/停|判断|继续|明天|复盘/);
  });

  test('routes rainy dine-in and takeout planning to external-event response', async ({ page }, testInfo) => {
    await openDemo(page);

    const result = await sendPrompt(page, '今天下雨，堂食和外卖应该分别怎么安排？');
    await attachScreenshot(page, testInfo, 'weather-takeout-final');

    expect(result.requestBody.context).toMatchObject({
      ownerActionScenario: 'external_event_response',
    });
    expect(result.response).toMatchObject({
      ok: true,
      intentCode: 'RESTAURANT_OWNER_ACTION_CHAT',
      source: 'restaurant_owner_action',
    });
    expect(normalizeText(result.answer)).toMatch(/下雨|天气|堂食|外卖|配送|雨/);
  });

  test('keeps revenue-growth scenario for weekly revenue decline follow-ups', async ({ page }, testInfo) => {
    await openDemo(page);

    const first = await sendPrompt(page, '这周营收比上周差，老板应该先做什么？');
    const second = await sendPrompt(page, '帮我选一个营收杠杆继续拆');
    await attachScreenshot(page, testInfo, 'revenue-growth-follow-up-final');

    expect(first.requestBody.context).toMatchObject({
      ownerActionScenario: 'revenue_growth',
    });
    expect(first.response).toMatchObject({
      ok: true,
      intentCode: 'RESTAURANT_OWNER_ACTION_CHAT',
      source: 'restaurant_owner_action',
    });
    expect(first.response.sessionId).toBeTruthy();

    expect(second.requestBody.context).toMatchObject({
      ownerActionScenario: 'revenue_growth',
      ownerActionSessionId: first.response.sessionId,
    });
    expect(second.response).toMatchObject({
      ok: true,
      intentCode: 'RESTAURANT_OWNER_ACTION_CHAT',
      source: 'restaurant_owner_action',
    });
    expect(normalizeText(second.answer)).toMatch(/营收|上周|客单|翻台|外卖|套餐|杠杆/);
  });
});
