/**
 * auth-cache.mjs — Reusable storageState caching for e2e-comprehensive suite.
 *
 * Ported from tests/v1-e2e/helpers/auth-cache.ts (2026-04-15).
 *
 * Backend enforces ~5 logins / 60s → parallel multi-role tests hit 429 instantly.
 * This helper writes/reads auth state per username to avoid re-login.
 *
 * Differences vs v1-e2e TS version:
 * - No Vite proxy installApiProxy (e2e-comprehensive runs against 139 production
 *   which doesn't use Vite proxy)
 * - Keyed by username (not role), matching R0-setup.json account format
 * - ESM / .mjs / Playwright's chromium directly (not @playwright/test)
 *
 * Usage:
 *   import { setupAuthOnce, restoreAuth } from './lib/auth-cache.mjs';
 *
 *   // Called once at start of run (idempotent — skips if file exists):
 *   await setupAuthOnce(browser, 'e2e_factory_admin');
 *   await setupAuthOnce(browser, 'e2e_sales_mgr');
 *
 *   // In each test, instead of loginAndWait:
 *   const ctx = await browser.newContext(...);
 *   const page = await ctx.newPage();
 *   await restoreAuth(ctx, page, 'e2e_factory_admin');
 *   // now `page` is logged in, ready to navigate
 */

import fs from 'node:fs';
import path from 'node:path';
import { BASE, PASSWORD } from './helpers.mjs';

const AUTH_CACHE_DIR = 'tests/e2e-comprehensive/results/auth-cache';

export function getAuthFile(username) {
  return path.join(AUTH_CACHE_DIR, `${username}-auth.json`);
}

/**
 * Clear all cached auth files (useful for full-refresh runs).
 */
export function clearAuthCache() {
  if (!fs.existsSync(AUTH_CACHE_DIR)) return;
  for (const file of fs.readdirSync(AUTH_CACHE_DIR)) {
    if (file.endsWith('-auth.json')) {
      fs.unlinkSync(path.join(AUTH_CACHE_DIR, file));
    }
  }
}

/**
 * Login once as `username` and save storageState to disk.
 * Skips if cache file already exists (idempotent — re-running doesn't re-login).
 *
 * Call in the test runner before creating contexts.
 *
 * @param {import('playwright').Browser} browser
 * @param {string} username
 * @returns {Promise<{cached: boolean, authFile: string}>}
 */
// Track last-login timestamp across all setupAuthOnce calls (module-level).
// Rate limit is ~5 logins / 60s per IP, so we space successive logins by
// RATE_LIMIT_DELAY_MS to stay well under threshold on fresh runs.
const RATE_LIMIT_DELAY_MS = 15000;
let lastLoginTs = 0;

export async function setupAuthOnce(browser, username) {
  if (!fs.existsSync(AUTH_CACHE_DIR)) {
    fs.mkdirSync(AUTH_CACHE_DIR, { recursive: true });
  }
  const authFile = getAuthFile(username);
  if (fs.existsSync(authFile)) {
    return { cached: true, authFile };
  }

  // Rate-limit guard: wait if previous login was recent
  const elapsedSinceLastLogin = Date.now() - lastLoginTs;
  if (lastLoginTs > 0 && elapsedSinceLastLogin < RATE_LIMIT_DELAY_MS) {
    const waitMs = RATE_LIMIT_DELAY_MS - elapsedSinceLastLogin;
    await new Promise(resolve => setTimeout(resolve, waitMs));
  }

  const context = await browser.newContext({ locale: 'zh-CN' });
  await context.route(/fonts\.(googleapis|gstatic)\.com/, r => r.abort());
  const page = await context.newPage();

  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForSelector('input.el-input__inner', { timeout: 30000 });
  await page.fill('input.el-input__inner[placeholder*="用户名"]', username);
  await page.fill('input[type="password"]', PASSWORD);

  // Register waitForResponse BEFORE click
  const [loginResp] = await Promise.all([
    page.waitForResponse(r => r.url().includes('/auth/unified-login'), { timeout: 15000 }),
    page.click('button.login-button'),
  ]);
  lastLoginTs = Date.now();

  // Rate-limit detection: 429 returns HTML not JSON → surface a clearer error
  const contentType = loginResp.headers()['content-type'] || '';
  if (!contentType.includes('application/json')) {
    const status = loginResp.status();
    await context.close();
    throw new Error(`Login for ${username} returned non-JSON (status ${status}, content-type ${contentType}) — likely hit rate limit (429). Space out calls or pre-warm cache.`);
  }
  const body = await loginResp.json();
  if (!body.success) {
    await context.close();
    throw new Error(`Login failed for ${username}: ${JSON.stringify(body)}`);
  }
  await page.waitForURL('**/dashboard', { timeout: 15000 });

  for (let i = 0; i < 30; i++) {
    await page.waitForTimeout(500);
    if (await page.evaluate(() => !!document.querySelector('.el-menu,.app-sidebar'))) break;
  }

  await context.storageState({ path: authFile });
  await context.close();
  return { cached: false, authFile };
}

/**
 * Restore storageState (cookies + localStorage) into the current test's context.
 *
 * @param {import('playwright').BrowserContext} context
 * @param {import('playwright').Page} page
 * @param {string} username
 */
export async function restoreAuth(context, page, username) {
  const authFile = getAuthFile(username);
  if (!fs.existsSync(authFile)) {
    throw new Error(`Auth cache missing for ${username} — call setupAuthOnce first`);
  }
  const state = JSON.parse(fs.readFileSync(authFile, 'utf-8'));

  // Restore cookies
  if (state.cookies?.length) {
    await context.addCookies(state.cookies);
  }

  // Restore localStorage — must navigate to the origin first
  if (state.origins?.length) {
    await page.goto(BASE);
    for (const origin of state.origins) {
      for (const item of (origin.localStorage || [])) {
        await page.evaluate(
          ([k, v]) => window.localStorage.setItem(k, v),
          [item.name, item.value],
        );
      }
    }
  }
}

/**
 * Create a fresh context with auth already restored for `username`.
 * Convenience one-shot for simple tests.
 *
 * @param {import('playwright').Browser} browser
 * @param {string} username
 * @param {object} [contextOptions]  — passed to browser.newContext
 * @returns {Promise<{context: import('playwright').BrowserContext, page: import('playwright').Page}>}
 */
export async function newContextWithAuth(browser, username, contextOptions = {}) {
  const context = await browser.newContext({
    viewport: { width: 1280, height: 800 },
    locale: 'zh-CN',
    ...contextOptions,
  });
  await context.route(/fonts\.(googleapis|gstatic)\.com/, r => r.abort());
  const page = await context.newPage();
  await restoreAuth(context, page, username);
  return { context, page };
}
