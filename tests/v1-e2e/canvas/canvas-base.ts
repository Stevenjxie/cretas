/**
 * canvas-base.ts — shared infrastructure for Canvas Tab E2E rounds.
 *
 * Auth strategy:
 *   - Test env: http://139.196.165.140:8097 (web), api at same host (nginx proxy
 *     forwards /api/mobile/** to 47.100.235.168:10011).
 *   - We use direct API calls via fetch (faster than Playwright UI for high-volume
 *     round runs). Auth = `f006_admin` / `123456` / F006 (FACTORY).
 *   - For UI smoke rounds we still drive Playwright through baseURL/login.
 *
 * Per `feedback_web_admin_auth_bypass_needs_user_object.md` HARD:
 *   if seeding for UI, set BOTH `cretas_access_token` AND `cretas_user` object
 *   in localStorage so route guards (guards.ts:71) treat session as authenticated.
 *
 * NOTE: This framework is read-mostly + idempotent-write. Every entity we create
 * is prefixed `E2E_R_` and cleaned by helpers/cleanup at suite teardown.
 */

import type { APIRequestContext, BrowserContext, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

// ─── Environment ──────────────────────────────────────────────────────────────

export const TEST_BASE_URL =
  process.env.CANVAS_E2E_BASE_URL || 'http://139.196.165.140:8097';

export const TEST_FACTORY_ID = process.env.CANVAS_E2E_FACTORY_ID || 'F006';

// f006_admin per CLAUDE.md test creds policy (passwords not committed; this is
// the documented test account on test 10011 env per memory).
export const TEST_USERNAME = process.env.CANVAS_E2E_USER || 'f006_admin';
export const TEST_PASSWORD = process.env.CANVAS_E2E_PASS || '123456';

// E2E entity prefix — every created entity uses this prefix so cleanup is safe.
export const E2E_PREFIX = 'E2E_R_';

// ─── Token cache ──────────────────────────────────────────────────────────────
//
// Backend rate-limits login to ~5 attempts per 60s. Playwright spawns workers
// in separate processes, so module-level cache alone is not enough. We persist
// tokens to disk under test-results/canvas-auth.json — first process to login
// writes the token, others read it. Token TTL = 24h so the file is good for
// the entire test run.

const AUTH_CACHE_FILE = path.join(
  __dirname,
  '..',
  'test-results',
  'canvas-auth.json',
);

interface AuthCache {
  token: string;
  user: Record<string, unknown>;
  /** ms epoch when token was acquired */
  acquiredAt: number;
}

let memCache: AuthCache | null = null;

function readDiskCache(): AuthCache | null {
  try {
    if (!fs.existsSync(AUTH_CACHE_FILE)) return null;
    const raw = fs.readFileSync(AUTH_CACHE_FILE, 'utf-8');
    const parsed = JSON.parse(raw);
    // Reject if older than 23h (token TTL is 24h, leave 1h buffer)
    if (Date.now() - parsed.acquiredAt > 23 * 3600 * 1000) return null;
    return parsed;
  } catch {
    return null;
  }
}

function writeDiskCache(cache: AuthCache): void {
  try {
    const dir = path.dirname(AUTH_CACHE_FILE);
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(AUTH_CACHE_FILE, JSON.stringify(cache));
  } catch {
    /* swallow — degrade to in-memory cache */
  }
}

/**
 * Login once and cache the access token. Multi-process safe via disk cache.
 *
 * Backend: POST /api/mobile/auth/unified-login
 */
export async function loginAndGetToken(
  request: APIRequestContext,
): Promise<{ token: string; user: Record<string, unknown> }> {
  if (memCache) return { token: memCache.token, user: memCache.user };
  const disk = readDiskCache();
  if (disk) {
    memCache = disk;
    return { token: disk.token, user: disk.user };
  }
  const resp = await request.post(`${TEST_BASE_URL}/api/mobile/auth/unified-login`, {
    data: {
      username: TEST_USERNAME,
      password: TEST_PASSWORD,
      factoryId: TEST_FACTORY_ID,
      userType: 'FACTORY',
    },
    timeout: 10_000,
  });
  if (!resp.ok()) {
    throw new Error(`Login failed: ${resp.status()} ${await resp.text()}`);
  }
  const body = await resp.json();
  if (!body.success || !body.data?.token) {
    throw new Error(`Login response missing token: ${JSON.stringify(body)}`);
  }
  const user = {
    userId: body.data.userId,
    username: body.data.username,
    factoryId: body.data.factoryId,
    factoryName: body.data.factoryName,
    factoryType: body.data.factoryType,
    role: body.data.role,
    permissions: body.data.permissions,
  };
  memCache = { token: body.data.token, user, acquiredAt: Date.now() };
  writeDiskCache(memCache);
  return { token: memCache.token, user: memCache.user };
}

// ─── Browser auth seeding ─────────────────────────────────────────────────────

/**
 * Seed BOTH localStorage keys per `feedback_web_admin_auth_bypass_needs_user_object.md`.
 * Token alone is NOT enough — guards.ts:71 also checks user object.
 */
export async function seedBrowserAuth(
  context: BrowserContext,
  page: Page,
): Promise<void> {
  const { token, user } = await loginAndGetToken(
    context.request as unknown as APIRequestContext,
  );
  // Need to navigate to origin before setting localStorage
  await page.goto(`${TEST_BASE_URL}/login`);
  await page.evaluate(
    ([t, u]) => {
      window.localStorage.setItem('cretas_access_token', t as string);
      window.localStorage.setItem('cretas_user', JSON.stringify(u));
    },
    [token, user] as const,
  );
}

// ─── API caller ───────────────────────────────────────────────────────────────

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

/**
 * One-shot API call with auto-auth + JSON shape parse.
 *
 * Returns: { status, body }. Caller asserts.
 */
export async function callApi(
  request: APIRequestContext,
  method: HttpMethod,
  path: string,
  payload?: unknown,
): Promise<{ status: number; body: any }> {
  const { token } = await loginAndGetToken(request);
  const url = path.startsWith('http')
    ? path
    : `${TEST_BASE_URL}/api/mobile${path}`;
  const opts: Parameters<APIRequestContext['fetch']>[1] = {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    timeout: 15_000,
  };
  if (payload !== undefined) {
    (opts as any).data = payload;
  }
  const resp = await request.fetch(url, opts);
  const httpStatus = resp.status();
  let body: any = null;
  try {
    body = await resp.json();
  } catch {
    body = await resp.text();
  }
  // Cretas backend often returns HTTP 200 with body.code = 4xx for validation
  // errors. Normalise: if body.success === false and body.code is a number,
  // use body.code as the effective status. This lets axis-helpers stay simple.
  let status = httpStatus;
  if (
    body && typeof body === 'object' && body.success === false &&
    typeof body.code === 'number' && body.code >= 400
  ) {
    status = body.code;
  }
  return { status, body };
}

// ─── Round naming ─────────────────────────────────────────────────────────────

/**
 * Generate a unique entity name for a specific round.
 *
 * Format: E2E_R_{tabKey}_{roundIdx}_{nonce}
 * - tabKey: short module identifier
 * - roundIdx: 01-20
 * - nonce: 8-char timestamp suffix for uniqueness across runs
 */
export function roundName(tabKey: string, roundIdx: number): string {
  const idx = String(roundIdx).padStart(2, '0');
  const nonce = Date.now().toString(36).slice(-6);
  return `${E2E_PREFIX}${tabKey}_${idx}_${nonce}`;
}

// ─── Cleanup helper ───────────────────────────────────────────────────────────

/**
 * Best-effort cleanup of all entities with E2E_R_ prefix for a given Tab.
 * Caller passes a list resource + delete URL pattern. Cleanup never throws —
 * it logs failures and moves on so test teardown stays green.
 */
export async function cleanupE2eEntities(
  request: APIRequestContext,
  listPath: string,
  deletePath: (id: string) => string,
  nameField: 'name' | 'ruleName' | 'templateName' | 'strategyName' | 'ruleCode' | 'code' | 'fieldCode' = 'ruleName',
  extractId: (item: any) => string = (i) => i.id,
): Promise<{ deleted: number; failed: number }> {
  let deleted = 0;
  let failed = 0;
  try {
    const list = await callApi(request, 'GET', listPath);
    if (list.status !== 200) return { deleted: 0, failed: 0 };
    const items: any[] = Array.isArray(list.body.data)
      ? list.body.data
      : list.body.data?.content || [];
    for (const item of items) {
      const name = String(item[nameField] || '');
      // Case-insensitive prefix check (some endpoints lowercase the code).
      if (!name.toLowerCase().startsWith(E2E_PREFIX.toLowerCase())) continue;
      const id = extractId(item);
      if (!id) continue;
      const r = await callApi(request, 'DELETE', deletePath(id));
      if (r.status >= 200 && r.status < 300) deleted++;
      else failed++;
    }
  } catch {
    /* swallow */
  }
  return { deleted, failed };
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Reset the token cache. Used between Tab suites if a 401 is observed.
 */
export function resetAuthCache(): void {
  memCache = null;
  try {
    if (fs.existsSync(AUTH_CACHE_FILE)) fs.unlinkSync(AUTH_CACHE_FILE);
  } catch {
    /* swallow */
  }
}

/**
 * Sleep helper (ms).
 */
export function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}
