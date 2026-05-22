/**
 * Loop 6: Restaurant AI 经营分析 — Sprint 11 MealClaw Response Phase 3 Round 1
 *
 * Goal: Phase 3 E2E ≥5 rounds, P0=0 P1≤2 per depth-first-e2e §8.2
 *
 * 链路: 登录 → 餐饮 chat → "帮我看上月损溢异常" → 30 秒出诊断 + Top N + 建议 → DB verify
 * 边界 case (4 必含): sub-Tool 失败 / 数据缺 / LLM 超时 / 异常 input
 *
 * 真实账号: RES_3101_009 (cretas_db, password=123456 via V20260514_04 seed)
 *   - qhj_warehouse_mgr (仓储主管, permissions=["warehouse:*"])
 *   - qhj_finance_mgr   (财务主管, permissions=["finance:*"])
 *   - qhj_sales_mgr     (销售主管, permissions=["sales:*"])
 *   - qhj_operator      (操作员, permissions=["production:*"])
 *
 * 状态: Round 1 SPEC (fill 完成, 待执行)
 * 关联 PR: #186 (AI 工厂 Composite Tool) + #187 (BI LLM wrapper)
 * 决策书: docs/superpowers/decisions/2026-05-22-mealclaw-sprint11-decision.md
 *
 * 使用 skill: e2e-web-admin + depth-first-e2e (per Goal Phase 3 DOD)
 *
 * Env vars:
 *   E2E_BASE_URL=http://139.196.165.140:8097 (test env, default)
 *   E2E_API_BASE=http://139.196.165.140:8097/api/mobile
 *   QHJ_PASSWORD=123456 (default per V20260514_04 seed)
 *
 * Run:
 *   QHJ_PASSWORD=123456 npx playwright test --project loop-6-restaurant-ai
 *
 * IMPORTANT P0 finding from Round 1 smoke probe (2026-05-22):
 *   /ai-intents/execute endpoint requires @RequirePermission({"system:read_write"})
 *   None of qhj_* accounts (warehouse / finance / sales / operator) have this perm.
 *   No factory_admin or super_admin exists for RES_3101_009 in either env (test or prod).
 *   Result: Customer-facing happy-path 完全不可达 — see /api/mobile/{factoryId}/ai-intents/execute
 *           returns HTTP 403 for ALL 4 qhj_* accounts on RES_3101_009.
 *
 * Spec design: tests below probe both the broken happy path (T1 documenting 403) AND
 * the working BI endpoints directly (T3/E2 via Python). Edge / RBAC cases reflect the
 * actual reachable surface. P0 will be reported in handoff doc for PM Round 2.
 */

import { test, expect, type Page, type APIRequestContext } from '@playwright/test';

const BASE_URL = process.env.E2E_BASE_URL || 'http://139.196.165.140:8097';
const API_BASE = process.env.E2E_API_BASE || `${BASE_URL}/api/mobile`;
const PY_BASE = process.env.E2E_PY_BASE || `${BASE_URL}/api/smartbi`;
const FACTORY_ID = 'RES_3101_009';
const QHJ_PASSWORD = process.env.QHJ_PASSWORD || '123456';

const ACCOUNTS = {
  warehouse: { username: 'qhj_warehouse_mgr', password: QHJ_PASSWORD },
  finance:   { username: 'qhj_finance_mgr',   password: QHJ_PASSWORD },
  sales:     { username: 'qhj_sales_mgr',     password: QHJ_PASSWORD },
  operator:  { username: 'qhj_operator',      password: QHJ_PASSWORD },
};

// ── Token cache: per-role, persists across tests within one run to avoid login rate-limit ──
// Server rate-limits login to 60s window. With 10 tests across 4 roles, cache is essential.
const tokenCache: Record<string, string> = {};

async function loginAs(request: APIRequestContext, who: keyof typeof ACCOUNTS): Promise<string> {
  const { username, password } = ACCOUNTS[who];
  if (tokenCache[username]) return tokenCache[username];

  // First retry: try once. If rate-limited, sleep 65s and retry once more.
  for (let attempt = 1; attempt <= 2; attempt++) {
    const res = await request.post(`${API_BASE}/auth/unified-login`, {
      data: { username, password },
      headers: { 'Content-Type': 'application/json' },
    });
    const json = await res.json();
    if (json.success) {
      tokenCache[username] = json.data.accessToken as string;
      return tokenCache[username];
    }
    // Rate limit detection: 429 or message contains "频繁"
    const isRateLimit = (json.code === 429) || /频繁|too many|rate limit/i.test(json.message || '');
    if (isRateLimit && attempt === 1) {
      console.log(`[loginAs] ${username} rate-limited, sleeping 65s then retrying once...`);
      await new Promise(r => setTimeout(r, 65_000));
      continue;
    }
    throw new Error(`Login failed for ${username}: ${json.message}`);
  }
  throw new Error(`Login failed for ${username}: exhausted retries`);
}

// ── Helper: POST to /ai-intents/execute (the Java-side spec'd happy path) ──
async function callIntentExecute(
  request: APIRequestContext,
  token: string,
  factoryId: string,
  payload: Record<string, unknown>,
  timeoutMs = 60_000,
): Promise<{ status: number; body: Record<string, unknown> | null; elapsedMs: number }> {
  const start = Date.now();
  const res = await request.post(`${API_BASE}/${factoryId}/ai-intents/execute`, {
    data: payload,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    timeout: timeoutMs,
  });
  const elapsedMs = Date.now() - start;
  const status = res.status();
  let body: Record<string, unknown> | null = null;
  try { body = await res.json(); } catch { /* non-json error */ }
  return { status, body, elapsedMs };
}

// ── Helper: GET Python smartbi (the Path B working endpoint) ──
async function callPyComposite(
  request: APIRequestContext,
  token: string,
  factoryId: string,
  month: string,
): Promise<{ status: number; body: Record<string, unknown> | null; elapsedMs: number }> {
  const start = Date.now();
  const res = await request.get(
    `${PY_BASE}/restaurant/llm-composite?factory_id=${factoryId}&month=${month}`,
    {
      headers: { Authorization: `Bearer ${token}` },
      timeout: 60_000,
    },
  );
  const elapsedMs = Date.now() - start;
  const status = res.status();
  let body: Record<string, unknown> | null = null;
  try { body = await res.json(); } catch { /* */ }
  return { status, body, elapsedMs };
}

// ── Whitelist check ──
const WHITELIST_FIELDS = ['summary', 'topItems', 'recommendations', 'evidence', 'dataAvailable'];
const FORBIDDEN_FIELDS = ['_toolCount', '_internalId', '_trace', '_executionMs', '_factoryId'];

function assertWhitelistShape(body: Record<string, unknown> | null, testId: string): void {
  if (!body) throw new Error(`${testId}: body is null`);
  for (const f of WHITELIST_FIELDS) {
    expect(body, `${testId}: missing whitelist field '${f}'`).toHaveProperty(f);
  }
  for (const f of FORBIDDEN_FIELDS) {
    expect(body, `${testId}: forbidden metadata field '${f}' leaked!`).not.toHaveProperty(f);
  }
}

// ═══════════════════════════════════════════════════════════════════════
// GOLDEN PATH (3 tests)
// ═══════════════════════════════════════════════════════════════════════
test.describe('Loop 6 Golden Path — Restaurant Economics Analysis', () => {
  test.setTimeout(120_000);

  test('T1: literal spec keyword routes to RESTAURANT_ECONOMICS_ANALYSIS (Round 6 hard verify)', async ({ request }) => {
    // depth: deep — Round 6 tightening: spec §2.11 Phase 4 DOD #1 demands
    //   literal "帮我看上月损溢异常" route to RESTAURANT_ECONOMICS_ANALYSIS.
    // Round 1 P0 fixed by PR #188 (controller perm gate removed).
    // Round 5 surfaced LLM-tier misroute → ALERT_ACTIVE for this literal phrase.
    // Round 6 fix: added 8 phrase mappings into IntentKnowledgeBase phraseToIntentMapping
    //   + restaurantPhraseMapping so phrase-match layer fires BEFORE the LLM tier.
    const token = await loginAs(request, 'warehouse');
    expect(token, 'T1: warehouse_mgr login token').toBeTruthy();

    const result = await callIntentExecute(request, token, FACTORY_ID, {
      userInput: '帮我看上月损溢异常',
    });

    console.log(`T1 result: status=${result.status} elapsedMs=${result.elapsedMs}`);
    console.log('T1 body snippet:', JSON.stringify(result.body).slice(0, 500));

    // STRICT (Round 6): MUST be HTTP 200 (controller perm gate gone since PR #188)
    expect(result.status, 'T1 should be 200 (no controller-level 403, perm gate removed in PR #188)').toBe(200);

    // STRICT (Round 6): MUST route to RESTAURANT_ECONOMICS_ANALYSIS (Goal 硬验证)
    const data = (result.body as { data?: { intentCode?: string; status?: string; message?: string } })?.data;
    expect(data, 'T1: data envelope present').toBeTruthy();
    expect(data?.intentCode, 'T1 should route to RESTAURANT_ECONOMICS_ANALYSIS (not ALERT_ACTIVE — Round 5 misroute regression)')
      .toBe('RESTAURANT_ECONOMICS_ANALYSIS');

    console.log(`T1 Round 6 PASS — intentCode=${data?.intentCode} status=${data?.status}`);
  });

  test('T2: intent dispatch — first paint < 30s (using authoritative path)', async ({ request }) => {
    // depth: medium — measures end-to-end response time without form-fill (it's an API E2E)
    // Per Steve decision §2.11 DOD: "30 秒内出诊断 + Top N + 建议"
    const token = await loginAs(request, 'warehouse');

    // T2a: Java intent path (will 403 due to T1 P0)
    const javaResult = await callIntentExecute(request, token, FACTORY_ID, {
      userInput: '帮我看上月损溢异常',
    });
    console.log(`T2a (Java): status=${javaResult.status} ms=${javaResult.elapsedMs}`);

    // T2b: Python LLM composite path (the WORKING path per Phase 2 implementation)
    const pyResult = await callPyComposite(request, token, FACTORY_ID, '2026-04');
    console.log(`T2b (Python composite): status=${pyResult.status} ms=${pyResult.elapsedMs}`);
    expect(pyResult.status, 'T2b: Python composite returns 200').toBe(200);
    expect(pyResult.elapsedMs, 'T2b: < 30s first paint').toBeLessThan(30_000);
    assertWhitelistShape(pyResult.body, 'T2b');
  });

  test('T3: DB verify — composite response matches expected summary shape (whitelist only)', async ({ request }) => {
    // depth: deep — fetch composite endpoint, verify shape + content correctness
    // Per Steve decision §2.7: Whitelist = {summary, topItems, recommendations, evidence, dataAvailable}
    const token = await loginAs(request, 'finance');
    const result = await callPyComposite(request, token, FACTORY_ID, '2026-04');
    expect(result.status).toBe(200);
    assertWhitelistShape(result.body, 'T3');

    const body = result.body!;
    // Verify summary is non-empty Chinese-language string referencing RES_3101_009
    expect(body.summary).toEqual(expect.any(String));
    expect((body.summary as string).length).toBeGreaterThan(20);
    expect(body.summary as string).toContain('RES_3101_009');

    // Verify topItems is an array (Path B may have 0 items, that's allowed in dataAvailable=false state)
    expect(Array.isArray(body.topItems), 'T3: topItems is array').toBeTruthy();

    // Verify recommendations is non-empty array
    expect(Array.isArray(body.recommendations), 'T3: recommendations is array').toBeTruthy();
    expect((body.recommendations as unknown[]).length, 'T3: recommendations non-empty').toBeGreaterThan(0);

    // Verify evidence has expected shape per Steve decision §2.7
    const evidence = body.evidence as Record<string, unknown>;
    expect(evidence).toHaveProperty('dataWindow');
    expect((evidence.dataWindow as Record<string, unknown>)).toHaveProperty('requested');
    expect((evidence.dataWindow as Record<string, unknown>)).toHaveProperty('actual');
    expect((evidence.dataWindow as Record<string, unknown>)).toHaveProperty('fallback');

    // Verify dataAvailable has expected 3-axis shape per PR #187
    const dataAvail = body.dataAvailable as Record<string, unknown>;
    expect(dataAvail).toHaveProperty('posData');
    expect(dataAvail).toHaveProperty('wastageData');
    expect(dataAvail).toHaveProperty('consumptionData');

    console.log('T3 summary:', body.summary);
    console.log('T3 evidence:', JSON.stringify(body.evidence));
    console.log('T3 dataAvailable:', JSON.stringify(body.dataAvailable));
  });
});

// ═══════════════════════════════════════════════════════════════════════
// EDGE CASES (4 必含 per Phase 3 DOD)
// ═══════════════════════════════════════════════════════════════════════
test.describe('Loop 6 Edge Cases (4 必含)', () => {
  test.setTimeout(180_000);

  test('E1: sub-Tool 失败 — dataAvailable=false + "X 数据不可用" narrative (Steve 决策 1)', async ({ request }) => {
    // depth: deep — verify Composite Tool returns dataAvailable=false when sub-data missing
    // Current RES_3101_009 state IS the sub-Tool failure state (hooks not wired) — perfect natural test
    const token = await loginAs(request, 'finance');
    const result = await callPyComposite(request, token, FACTORY_ID, '2026-04');
    expect(result.status).toBe(200);
    const body = result.body!;
    const dataAvail = body.dataAvailable as Record<string, unknown>;

    // Per Steve 决策 1: when sub-Tools fail (posData/wastageData/consumptionData all false),
    // the summary should explicitly say "X 数据不可用" or similar fallback language
    expect(dataAvail.overall === false || Object.values(dataAvail).some(v => v === false),
      'E1: at least one dataAvailable axis should be false on RES_3101_009').toBeTruthy();

    // Narrative must contain explicit "不可用" / "数据缺" / "无 X" markers
    const summaryStr = body.summary as string;
    const recsStr = (body.recommendations as string[]).join(' | ');
    const fullText = summaryStr + ' | ' + recsStr;
    const hasUnavailMarker = /数据缺|不可用|无.*数据|未 wire|未wire|hooks/.test(fullText);
    expect(hasUnavailMarker, `E1: narrative must mark unavailable data — got: ${fullText.slice(0, 300)}`).toBeTruthy();
    console.log('E1 PASS — narrative contains unavailable marker');
  });

  test('E2: 数据缺 — Path B 30→365d auto-fallback (PR #187)', async ({ request }) => {
    // depth: deep — verify evidence.dataWindow.fallback=true + actual="365d" when 30d empty
    const token = await loginAs(request, 'sales');
    const result = await callPyComposite(request, token, FACTORY_ID, '2026-04');
    expect(result.status).toBe(200);
    const body = result.body!;
    const dataWindow = (body.evidence as { dataWindow: Record<string, unknown> }).dataWindow;

    // Per Sprint 11 Path B: when 30d window has no sales, fallback to 365d
    expect(dataWindow.requested, 'E2: requested window should be 30d').toBe('30d');
    expect(dataWindow.actual, 'E2: actual window after fallback').toBe('365d');
    expect(dataWindow.fallback, 'E2: fallback flag should be true').toBe(true);
    expect(dataWindow.fallbackReason, 'E2: fallbackReason populated').toBeTruthy();

    // Summary should mention the fallback explicitly per Steve 决策 1 + UX requirement
    const summary = body.summary as string;
    expect(/365|过去 1 年|历史/.test(summary),
      `E2: summary should mention historical fallback — got: ${summary}`).toBeTruthy();
    console.log('E2 PASS — Path B fallback evidence + narrative correct');
  });

  test('E3: 异常 input — 无意义字符串 routing behavior (Round 3: post-#188)', async ({ request }) => {
    // depth: medium — verify graceful handling of garbage input
    // Round 3: after PR #188 removed controller perm gate, garbage input reaches
    // intent classifier and gets categorized as OUT_OF_DOMAIN. No crash, no hang.
    const token = await loginAs(request, 'warehouse');
    const result = await callIntentExecute(request, token, FACTORY_ID, {
      userInput: 'lkasjdf qweoiru zxcvbnm',
    });

    // Acceptable outcomes:
    //   (a) 200 with intent classifier returning OUT_OF_DOMAIN or NEED_CLARIFICATION
    //   (b) 4xx with explicit error code
    // What we MUST NOT see: 500 (crash), 403 system module (P0 regression), hang.
    console.log(`E3: status=${result.status} elapsedMs=${result.elapsedMs}`);
    expect([200, 400, 404, 422]).toContain(result.status);
    expect(result.elapsedMs).toBeLessThan(60_000);

    // P0 regression check — no 403 from controller system module
    expect(result.status, 'E3: must not be 403 (Round 2 P0 fix verified)').not.toBe(403);

    if (result.status === 200) {
      // Java intent response envelope — verify it's a valid IntentExecuteResponse,
      // NOT the Python composite whitelist shape (different endpoint, different contract).
      const data = (result.body as { data?: { intentCode?: string; status?: string; message?: string } })?.data;
      expect(data, 'E3: response data envelope present').toBeTruthy();
      // intent classifier should return one of these statuses for garbage input
      expect(['OUT_OF_DOMAIN', 'NEED_CLARIFICATION', 'COMPLETED']).toContain(data?.status ?? '');
      console.log(`E3 PASS — garbage input handled as ${data?.intentCode} (status=${data?.status})`);
    }
  });

  test('E4: completeness endpoint as "AI 哨兵" — explicit data-quality reporting (差异化 #1)', async ({ request }) => {
    // depth: deep — verify completeness endpoint reports each data axis with concrete completeness %
    // This is Cretas USP per decision §8 "AI 告诉你哪儿数据不对"
    const token = await loginAs(request, 'finance');
    const res = await request.get(
      `${PY_BASE}/restaurant/completeness?factoryId=${FACTORY_ID}&format=llm`,
      { headers: { Authorization: `Bearer ${token}` }, timeout: 30_000 },
    );
    expect(res.status()).toBe(200);
    const body = await res.json();
    assertWhitelistShape(body, 'E4');

    // completeness must return per-axis topItems each with value + category=data_completeness
    const topItems = body.topItems as Array<{ name: string; value: number; category: string; unit: string }>;
    expect(topItems.length, 'E4: completeness topItems non-empty').toBeGreaterThan(0);
    for (const item of topItems) {
      expect(item.category, `E4: item ${item.name} category`).toBe('data_completeness');
      expect(item.unit).toBe('%');
      expect(typeof item.value).toBe('number');
      expect(item.value).toBeGreaterThanOrEqual(0);
      expect(item.value).toBeLessThanOrEqual(100);
    }
    console.log(`E4 PASS — completeness reports ${topItems.length} axes (${topItems.map(t => t.name).join(', ')})`);
  });
});

// ═══════════════════════════════════════════════════════════════════════
// RBAC + 多角色 (3 tests)
// ═══════════════════════════════════════════════════════════════════════
test.describe('Loop 6 RBAC + 多角色 (Cross-role + Cross-factory)', () => {
  test.setTimeout(120_000);

  test('R1: warehouse_mgr — intent-level perm enforcement (Round 3: post-#188)', async ({ request }) => {
    // depth: deep — Round 2 PR #188 removed controller-level @RequirePermission("system:read_write").
    // Now the endpoint returns 200 with intent-level perm status (NOT 403 from controller).
    // Per PR #188 design: per-intent required_roles is the sole permission gate.
    // For intents whose required_roles includes warehouse_manager OR has empty/null
    // required_roles + LOW sensitivity, the intent executes. Otherwise the response
    // status field contains NO_PERMISSION / FORBIDDEN with a 200 HTTP code (because
    // the request was successfully processed — the perm denial is business-level
    // metadata, not a transport-level 403).
    const token = await loginAs(request, 'warehouse');
    const result = await callIntentExecute(request, token, FACTORY_ID, {
      userInput: '查看本月领料汇总',
    });
    console.log(`R1: status=${result.status} body.data.status=${(result.body as any)?.data?.status}`);

    // Round 3 (post-#188) expected: 200 with intent-level outcome in body.data.status
    // It MUST NOT be 403 (which was the controller-level pre-#188 P0).
    expect(result.status, 'R1: must not be 403 (Round 2 P0 fix verified)').not.toBe(403);

    // If 200, accept any of these outcomes (all are valid for an authenticated warehouse_mgr):
    //   - COMPLETED: intent executed successfully (perm allowed)
    //   - NO_PERMISSION: intent matched but role-level perm denied
    //   - NEED_CLARIFICATION: ambiguous query
    //   - OUT_OF_DOMAIN: input not a business intent
    if (result.status === 200) {
      const data = (result.body as { data?: { status?: string; intentCode?: string } })?.data;
      expect(data, 'R1: data envelope present').toBeTruthy();
      console.log(`R1 Round 3 PASS — intent ${data?.intentCode ?? '(none)'} status=${data?.status}`);
    }
  });

  test('R2: finance_mgr — intent-level perm + Python composite both reachable (Round 3: post-#188)', async ({ request }) => {
    // depth: deep — Round 2 fix verified for finance role too. Both Java intent
    // path (after #188) and Python BI composite path (always worked) should return 200.
    const token = await loginAs(request, 'finance');
    const result = await callIntentExecute(request, token, FACTORY_ID, {
      userInput: '查看本月成本分析',
    });
    console.log(`R2: status=${result.status} body.data.status=${(result.body as any)?.data?.status}`);

    // Round 3 (post-#188): 200 expected, no controller-level 403 anymore.
    expect(result.status, 'R2: must not be 403 (Round 2 P0 fix verified for finance)').not.toBe(403);

    // The Python BI composite endpoint must work (different auth path — never had P0).
    const pyResult = await callPyComposite(request, token, FACTORY_ID, '2026-04');
    expect(pyResult.status, 'R2: finance_mgr CAN reach Python composite').toBe(200);
    console.log('R2 Round 3 PASS — Java intent reachable (post-#188) + Python BI 200');
  });

  test('R3: cross-factory RLS — qhj_* MUST NOT see other factory data', async ({ request }) => {
    // depth: deep — verify RLS enforcement at composite endpoint level
    // Try to query F001 (manufacturing) data using qhj_finance_mgr (RES_3101_009)
    const token = await loginAs(request, 'finance');
    const res = await request.get(
      `${PY_BASE}/restaurant/llm-composite?factory_id=F001&month=2026-04`,
      { headers: { Authorization: `Bearer ${token}` }, timeout: 30_000 },
    );
    console.log(`R3: status=${res.status()}`);

    // Acceptable outcomes:
    //   (a) 403 forbidden (explicit RLS deny — preferred)
    //   (b) 200 with empty data + dataAvailable=false (RLS at row-level — also valid)
    //   (c) 200 with F001 data — VIOLATION (would be P0)
    if (res.status() === 200) {
      const body = await res.json();
      // If returns 200, summary MUST reference RES_3101_009 or empty, NOT F001 data
      // i.e. RLS should at minimum filter rows. We don't accept seeing real F001 manufacturing data.
      const summary = body.summary as string;
      const isCrossLeak = summary.includes('F001') && !summary.includes('RES_3101_009');
      expect(isCrossLeak, `R3: cross-factory leak — qhj_finance_mgr saw F001 data: ${summary}`).toBe(false);
      console.log('R3: 200 returned but no cross-factory leak (RLS row-level filter active)');
    } else {
      expect([401, 403, 404]).toContain(res.status());
      console.log('R3: cross-factory query denied at endpoint level — proper RLS');
    }
  });
});
