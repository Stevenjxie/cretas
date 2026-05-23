/**
 * Sprint 11 全流程真实 UI/UX 审计 — 2026-05-23
 *
 * Owner: AI 工厂 chat (worktree sprint11-indicator)
 * Goal: Prove what the customer ACTUALLY SEES in the browser when they type
 *       4 restaurant-flavored phrases on SalesOwnerWorkdesk chat input.
 *
 * NOT API curl. NOT paperwork. REAL browser UI:
 *   - login via POST /auth/unified-login + seed BOTH localStorage keys
 *     (per feedback_web_admin_auth_bypass_needs_user_object HARD)
 *   - navigate /workdesk/sales-owner
 *   - fill chat input (textarea) with phrase
 *   - click 发送 button
 *   - wait for .result-card / .formatted-output to render
 *   - screenshot fullPage + capture innerText of result + screenshot
 *
 * 12 cases = 4 phrases × 3 accounts:
 *   Phrases (restaurant-flavor, mealclaw-target):
 *     1. 帮我看上月损溢异常
 *     2. 损益分析
 *     3. 上月成本
 *     4. 哪个菜亏钱
 *   Accounts:
 *     A. qhj_warehouse_mgr / RES_3101_009 (restaurant — main target)
 *     B. f006_admin       / F006          (manufacturer baseline)
 *     C. warehouse_mgr1   / F001          (sister/baseline)
 *
 * Outputs (per phase):
 *   P2: 12 PNG + 1 video .webm (--video=on)
 *   P3 input: ui-text-12.json with each case's rendered textContent
 *
 * Run via:
 *   npx playwright test tests/e2e-customer-journey/full-customer-flow-2026-05-23.spec.ts \
 *     --project full-customer-flow-2026-05-23 \
 *     --workers=1
 */
import { test, expect, type Page } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

const BASE = process.env.E2E_BASE_URL || 'http://139.196.165.140:8086';
const API  = process.env.E2E_API_BASE || `${BASE}/api/mobile`;

type Account = { label: string; username: string; password: string; factoryId: string };
const ACCOUNTS: Account[] = [
  { label: 'qhj_warehouse_mgr',  username: 'qhj_warehouse_mgr', password: '123456', factoryId: 'RES_3101_009' },
  { label: 'f006_admin',         username: 'f006_admin',        password: '123456', factoryId: 'F006' },
  { label: 'warehouse_mgr1_F001', username: 'warehouse_mgr1',   password: '123456', factoryId: 'F001' },
];

const PHRASES = [
  '帮我看上月损溢异常',
  '损益分析',
  '上月成本',
  '哪个菜亏钱',
];

// Sprint 12 P3 re-run target — write to sprint-12-routing-fix dir for diff vs Sprint 11
// (env override allows re-pointing back at sprint-11 for ad-hoc verification)
const OUT_DIR = process.env.UX_AUDIT_DIR
    ? path.resolve(process.env.UX_AUDIT_DIR)
    : path.resolve(process.cwd(), '..', 'docs', 'audits', 'sprint-12-routing-fix', 'screenshots-after');
const JSON_OUT = process.env.UX_AUDIT_JSON
    ? path.resolve(process.env.UX_AUDIT_JSON)
    : path.resolve(process.cwd(), '..', 'docs', 'audits', 'sprint-12-routing-fix', 'ui-text-12-after.json');

// Per-case capture container
type CaseCapture = {
  caseId: string;
  account: string;
  factoryId: string;
  phrase: string;
  status: 'PASS' | 'FAIL' | 'AUTH_FAIL' | 'TIMEOUT';
  loginStatus?: number;
  loginBody?: string;
  resultCardPresent?: boolean;
  formattedTextOuterHTML?: string;
  formattedTextInnerText?: string;
  errorAlertText?: string;
  pngPath?: string;
  error?: string;
};
const captures: CaseCapture[] = [];

async function loginAndSeed(page: Page, acct: Account): Promise<{ ok: boolean; status: number; bodyShort: string }> {
  try {
    const resp = await page.request.post(`${API}/auth/unified-login`, {
      data: { username: acct.username, password: acct.password, factoryId: acct.factoryId },
      timeout: 30_000,
      failOnStatusCode: false,
    });
    const status = resp.status();
    let body: any = null;
    let bodyShort = '';
    try {
      body = await resp.json();
      bodyShort = JSON.stringify(body).slice(0, 400);
    } catch {
      bodyShort = (await resp.text()).slice(0, 400);
    }
    if (!resp.ok() || !body?.data?.token && !body?.data?.accessToken) {
      return { ok: false, status, bodyShort };
    }
    const data = body.data;
    const token = String(data.token || data.accessToken || '');
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
    return { ok: true, status, bodyShort };
  } catch (e: any) {
    return { ok: false, status: 0, bodyShort: `EXC ${e?.message || e}` };
  }
}

test.beforeAll(() => {
  fs.mkdirSync(OUT_DIR, { recursive: true });
});

test.afterAll(() => {
  fs.writeFileSync(JSON_OUT, JSON.stringify(captures, null, 2));
  console.log(`[ux-audit] wrote ${captures.length} captures to ${JSON_OUT}`);
});

test.describe.serial('Sprint 11 UX audit — 12 cases (4 phrases × 3 accounts)', () => {
  test.setTimeout(180_000);

  for (const acct of ACCOUNTS) {
    for (let pIdx = 0; pIdx < PHRASES.length; pIdx++) {
      const phrase = PHRASES[pIdx];
      const caseId = `${acct.label}__phrase${pIdx + 1}`;

      test(`${caseId} — "${phrase}"`, async ({ page }) => {
        const cap: CaseCapture = {
          caseId,
          account: acct.label,
          factoryId: acct.factoryId,
          phrase,
          status: 'FAIL',
        };
        try {
          // Step 1: Auth
          const auth = await loginAndSeed(page, acct);
          cap.loginStatus = auth.status;
          cap.loginBody = auth.bodyShort;
          if (!auth.ok) {
            cap.status = 'AUTH_FAIL';
            cap.error = `auth status=${auth.status} body=${auth.bodyShort}`;
            console.log(`[${caseId}] AUTH_FAIL: ${cap.error}`);
            // Still screenshot the login page state so we have visual proof
            const pngPath = path.join(OUT_DIR, `${caseId}.png`);
            await page.screenshot({ path: pngPath, fullPage: true });
            cap.pngPath = pngPath;
            captures.push(cap);
            return;
          }

          // Step 2: Navigate SalesOwnerWorkdesk
          await page.goto(`${BASE}/workdesk/sales-owner`, {
            waitUntil: 'domcontentloaded',
            timeout: 30_000,
          });

          // Wait for chat input to mount
          await page.waitForSelector('.chat-input textarea', { timeout: 20_000 });

          // The page auto-triggers triggerFollowupQuery() onMounted, which sets
          // loading=true and disables the send button. Wait for that initial
          // query to settle (button no longer .is-loading) — up to 90s.
          await page.waitForFunction(() => {
            const btn = document.querySelector('.chat-input button.el-button--primary');
            if (!btn) return false;
            const isLoading = btn.classList.contains('is-loading');
            return !isLoading;
          }, { timeout: 90_000 }).catch(() => {
            console.log(`[${caseId}] WARN: initial mount query never settled, proceeding anyway`);
          });

          // Step 3: Fill phrase in textarea
          const textarea = page.locator('.chat-input textarea').first();
          await textarea.click();
          await textarea.fill(phrase);

          // Brief wait for v-model to register (enables :disabled binding)
          await page.waitForTimeout(500);

          // Step 4: Click send button. Use force:true as fallback if button still
          // shows loading state due to a slow second query (we still want to
          // capture the actual UX, including the "stuck loading" state).
          const sendBtn = page.locator('.chat-input button:has-text("发送")').first();
          try {
            await sendBtn.click({ timeout: 10_000 });
          } catch (clickErr) {
            console.log(`[${caseId}] WARN: normal click failed, trying force click`);
            await sendBtn.click({ force: true, timeout: 10_000 });
          }

          // Step 5: Wait for either .result-card OR .error-alert OR loading->done
          // Loading card stays until response. Wait up to 120s for result/error.
          await page.waitForFunction(() => {
            const result = document.querySelector('.result-card');
            const errorAlert = document.querySelector('.error-alert');
            const loading = document.querySelector('.loading-card');
            // success: result-card present
            // error: error-alert present
            // also accept: loading gone + indicator response received
            return !!result || !!errorAlert || (!loading && !!document.querySelector('.indicators-card'));
          }, { timeout: 120_000 });

          // Settle 2s for v-html render
          await page.waitForTimeout(2_000);

          // Step 6: Capture result-card formatted-output
          const resultCard = page.locator('.result-card').first();
          cap.resultCardPresent = (await resultCard.count()) > 0;
          if (cap.resultCardPresent) {
            const formatted = page.locator('.formatted-output').first();
            if ((await formatted.count()) > 0) {
              cap.formattedTextOuterHTML = await formatted.evaluate(el => el.outerHTML);
              cap.formattedTextInnerText = await formatted.evaluate(el => (el as HTMLElement).innerText);
            }
          }
          const errorAlert = page.locator('.error-alert').first();
          if ((await errorAlert.count()) > 0) {
            cap.errorAlertText = await errorAlert.evaluate(el => (el as HTMLElement).innerText);
          }

          // Step 7: Screenshot fullPage (extended timeout for font-loading hangs)
          const pngPath = path.join(OUT_DIR, `${caseId}.png`);
          await page.screenshot({ path: pngPath, fullPage: true, timeout: 60_000 });
          cap.pngPath = pngPath;

          cap.status = 'PASS';
          console.log(`[${caseId}] PASS — result=${cap.resultCardPresent}, innerText.length=${cap.formattedTextInnerText?.length || 0}`);
        } catch (e: any) {
          cap.status = e?.message?.includes('Timeout') ? 'TIMEOUT' : 'FAIL';
          cap.error = String(e?.message || e).slice(0, 500);
          console.log(`[${caseId}] ${cap.status}: ${cap.error}`);
          try {
            const pngPath = path.join(OUT_DIR, `${caseId}_FAIL.png`);
            await page.screenshot({ path: pngPath, fullPage: true });
            cap.pngPath = pngPath;
          } catch {}
        } finally {
          captures.push(cap);
        }
      });
    }
  }
});
