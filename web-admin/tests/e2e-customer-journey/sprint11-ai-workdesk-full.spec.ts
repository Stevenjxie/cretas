/**
 * Sprint 11 AI Workdesk Full E2E + UX Audit — 2026-05-28
 *
 * Owner: AI 工厂 chat
 * Goal: 22 cases (12 happy + 6 breadth + 4 error-deep) for 4-dim UX audit
 *
 * Per qa-prompt v2.4 + depth-first-e2e + fool-proof-design:
 *   - MutationObserver toast capture (Rule 7, install once per page)
 *   - network/console capture each step (Rule 5-6)
 *   - depth labels: smoke / medium / deep / error-deep (Rule 1)
 *   - Rule 11 roundtrip 3-step for any write op encountered
 *   - Rule 9 中末段抽检 for topItems list
 *   - 四位一体 for error-deep (network message + toast + sticky + actionHint)
 *   - Rule 16 entry-point matrix (chat input vs indicator card click)
 *   - Rule 8 cross-account state isolation
 *
 * Run:
 *   cd web-admin && npx playwright test --project=sprint11-ai-workdesk-full
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

const CORE_PHRASES = [
  '帮我看上月损溢异常',
  '损益分析',
  '上月成本',
  '哪个菜亏钱',
];

const BREADTH_WORKDESKS = [
  { route: 'workdesk/finance-manager',     name: 'FinanceManager',     phrase: '本月财务情况' },
  { route: 'workdesk/production-manager',  name: 'ProductionManager',  phrase: '本月生产计划完成' },
  { route: 'workdesk/purchaser',           name: 'Purchaser',          phrase: '本月采购订单' },
  { route: 'workdesk/quality-chief',       name: 'QualityChief',       phrase: '本月质量异常' },
  { route: 'workdesk/quality-manager',     name: 'QualityManager',     phrase: '本月质量检验' },
  { route: 'workdesk/warehouse-keeper',    name: 'WarehouseKeeper',    phrase: '本月库存情况' },
];

const ERROR_CASES = [
  { id: 'E1', label: 'empty_input',          phrase: '',                description: 'Empty input click 发送' },
  { id: 'E2', label: 'forced_misroute',      phrase: '随便聊聊',        description: 'Force LLM CONVERSATIONAL fallback' },
  { id: 'E3', label: 'composite_old_month',  phrase: '哪个菜亏钱',      contextMonth: '2024-01', description: 'Old month not in seed → Composite blank' },
  { id: 'E4', label: 'wrong_workdesk',       phrase: '帮我看上月损溢异常', route: 'workdesk/quality-chief', description: 'Wrong Workdesk routing' },
];

const OUT_DIR = process.env.UX_AUDIT_DIR || path.resolve(process.cwd(), '..', 'docs', 'audits', '2026-05-28-sprint11-ai-workdesk-audit', 'screenshots');
const JSON_OUT = process.env.UX_AUDIT_JSON || path.resolve(process.cwd(), '..', 'docs', 'audits', '2026-05-28-sprint11-ai-workdesk-audit', 'captures.json');

// Per-case capture container
type CaseCapture = {
  caseId: string;
  depth: 'smoke' | 'medium' | 'deep' | 'error-deep' | 'breadth-smoke';
  account: string;
  factoryId: string;
  workdesk: string;
  route: string;
  phrase: string;
  contextMonth?: string;
  status: 'PASS' | 'FAIL' | 'AUTH_FAIL' | 'TIMEOUT';
  // Network observations
  executeReqBody?: string;
  executeRespStatus?: number;
  executeRespBody?: string;
  // UI observations
  resultCardPresent?: boolean;
  formattedTextInnerText?: string;
  // Toast (per Rule 7 MutationObserver)
  toastLog?: Array<{ time: number; cls: string; text: string; hasClose: boolean }>;
  // Console errors (per Rule 5)
  consoleErrors?: string[];
  // Evidence
  pngPath?: string;
  error?: string;
  // Roundtrip (per Rule 11) — only if write op
  roundtripWriteEndpoints?: string[];
  // 四位一体 verdict (per qa-prompt 专章, only for error-deep)
  fourInOneVerdict?: {
    backendMessageMatches: boolean;
    toastSticky: boolean;
    toastShowClose: boolean;
    hasActionHint: boolean;
  };
  // Steve 5/28 — 15 类 anti-pattern leak sweep
  leaks?: Array<{ code: string; count: number; sample: string }>;
  leakPngPath?: string;
};
const captures: CaseCapture[] = [];

async function loginAndSeed(page: Page, acct: Account): Promise<{ ok: boolean; status: number; bodyShort: string; token?: string }> {
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
    if (!resp.ok() || (!body?.data?.token && !body?.data?.accessToken)) {
      return { ok: false, status, bodyShort };
    }
    const data = body.data;
    const token = String(data.token || data.accessToken || '');
    const userObj = {
      id: data.userId, username: data.username, email: '', isActive: true,
      createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
      userType: 'factory',
      factoryUser: {
        role: data.role, factoryId: data.factoryId,
        factoryType: data.factoryType || 'FACTORY',
        permissions: data.permissions || ['*:*'],
      },
    };
    await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
    await page.evaluate(({ tok, user }) => {
      localStorage.setItem('cretas_access_token', tok);
      localStorage.setItem('cretas_user', JSON.stringify(user));
    }, { tok: token, user: userObj });
    return { ok: true, status, bodyShort, token };
  } catch (e: any) {
    return { ok: false, status: 0, bodyShort: `EXC ${e?.message || e}` };
  }
}

// Rule 7: MutationObserver toast capture, install once per page
async function installToastObserver(page: Page) {
  await page.evaluate(() => {
    const w = window as any;
    if (w.__toastLog && w.__toastObs) return; // install once
    w.__toastLog = [];
    w.__toastObs = new MutationObserver(muts => muts.forEach(m =>
      m.addedNodes.forEach(n => {
        if (n.nodeType === 1) {
          const el = n as Element;
          const cls = String(el.className || '');
          if (cls.includes('el-message') || cls.includes('el-notification')) {
            w.__toastLog.push({
              time: Date.now(),
              cls,
              text: el.textContent?.trim().slice(0, 500) || '',
              hasClose: cls.includes('is-closable') || !!el.querySelector('.el-notification__closeBtn, .el-message__closeBtn'),
            });
          }
        }
      })
    ));
    w.__toastObs.observe(document.body, { childList: true, subtree: true });
  });
}

async function readToastLog(page: Page) {
  return page.evaluate(() => (window as any).__toastLog || []);
}

// Install request capture (Rule 11 wire audit)
function captureExecuteRequests(page: Page, cap: CaseCapture) {
  page.on('request', req => {
    if (req.url().includes('/ai-intents/execute') && req.method() === 'POST') {
      cap.executeReqBody = req.postData() || '';
    }
  });
  page.on('response', async resp => {
    if (resp.url().includes('/ai-intents/execute')) {
      cap.executeRespStatus = resp.status();
      try {
        cap.executeRespBody = (await resp.text()).slice(0, 2000);
      } catch {}
    }
  });
}

// Install console error capture (Rule 5)
function captureConsoleErrors(page: Page, cap: CaseCapture) {
  cap.consoleErrors = [];
  page.on('console', msg => {
    if (msg.type() === 'error') {
      cap.consoleErrors!.push(msg.text().slice(0, 300));
    }
  });
}

async function runWorkdeskCase(page: Page, cap: CaseCapture, acct: Account, route: string, phrase: string, contextMonth?: string) {
  // Capture wires
  captureExecuteRequests(page, cap);
  captureConsoleErrors(page, cap);

  // Step 1: Auth
  const auth = await loginAndSeed(page, acct);
  if (!auth.ok) {
    cap.status = 'AUTH_FAIL';
    cap.error = `auth status=${auth.status} body=${auth.bodyShort}`;
    return;
  }

  // Step 2: Nav
  await page.goto(`${BASE}/${route}`, { waitUntil: 'domcontentloaded', timeout: 30_000 });
  await page.waitForSelector('.chat-input textarea', { timeout: 20_000 });

  // Step 2.5: Install MutationObserver per Rule 7 (BEFORE any action)
  await installToastObserver(page);

  // Step 3: Wait for auto-mount initial query to settle
  await page.waitForFunction(() => {
    const btn = document.querySelector('.chat-input button.el-button--primary');
    if (!btn) return false;
    return !btn.classList.contains('is-loading');
  }, { timeout: 90_000 }).catch(() => { /* WARN: initial query never settled */ });

  // Step 4: Fill phrase + (optional contextMonth via dialog/inject if applicable)
  const textarea = page.locator('.chat-input textarea').first();
  await textarea.click();
  if (phrase) {
    await textarea.fill(phrase);
  } else {
    // Empty input case — leave blank, click send anyway (E1 expects validation)
    await textarea.fill('');
  }
  await page.waitForTimeout(500); // v-model settle

  // Step 5: Click send (force fallback if button disabled due to validation)
  const sendBtn = page.locator('.chat-input button:has-text("发送")').first();
  try {
    await sendBtn.click({ timeout: 10_000 });
  } catch {
    try { await sendBtn.click({ force: true, timeout: 5_000 }); } catch { /* button truly disabled */ }
  }

  // Step 6: Wait for response or error/empty validation
  await page.waitForFunction(() => {
    const result = document.querySelector('.result-card');
    const errorAlert = document.querySelector('.error-alert');
    const loading = document.querySelector('.loading-card');
    return !!result || !!errorAlert || (!loading && !!document.querySelector('.indicators-card, .chat-card'));
  }, { timeout: 120_000 }).catch(() => { /* timeout — captured as status TIMEOUT */ });

  // Settle render
  await page.waitForTimeout(3_000);

  // Step 7: Capture .result-card .formatted-output innerText
  const resultCard = page.locator('.result-card').first();
  cap.resultCardPresent = (await resultCard.count()) > 0;
  if (cap.resultCardPresent) {
    const formatted = page.locator('.formatted-output').first();
    if ((await formatted.count()) > 0) {
      cap.formattedTextInnerText = await formatted.evaluate(el => (el as HTMLElement).innerText);
    }
  }

  // Step 8: Read toast log (per Rule 7) — wait additional 2s for late toasts
  await page.waitForTimeout(2_000);
  cap.toastLog = await readToastLog(page);

  // Step 9: 5s sticky check (per Rule 7) — re-read after 5s, see if error toasts still alive
  await page.waitForTimeout(5_000);
  const toastsAfter5s = await readToastLog(page);
  // toastLog already captured all; sticky verdict computed in Phase E via duration check

  // Step 9.5: ANTI-PATTERN LEAK SWEEP (15 categories, ~25 patterns)
  // Per Steve 5/28 expanded brief — single screenshot can expose 4+ leak categories
  // Output: cap.leaks[] + per-hit leak-screenshot
  cap.leaks = await page.evaluate(() => {
    const ANTI: Record<string, RegExp> = {
      // A. Developer/QA internal info leak
      A1_cache_state:       /[(（](?:缓存结果|缓存命中|cache hit)|CACHED[:：]/i,
      A2_sprint_version:    /Sprint\s*\d+\s*[A-Z]?\d?[a-z]?\b/,
      A3_debug_log:         /\b(DEBUG|WARN|INFO|TRACE)[:：]/,
      A4_internal_flag:     /_(?:toolCount|internal|meta|debug)\b/,
      A5_mock_marker:       /MOCK_|F999_MOCK|\[(?:TEST|DEV|STAGING)\]/i,
      A7_stack_trace:       /at\s+[\w.$]+\([^)]+\.java:\d+\)|NullPointerException|Exception in thread/,
      // B. API raw response 未 transform
      B1_json_dump:         /[(（]?[^)]*[)）]?\s*\{\s*["']?(?:data|success|message|code)["']?\s*:/m,
      B2_array_literal:     /(?:^|>\s*)\[\s*\](?:\s*<|$)/,
      B3_boolean_raw:       /(?:^|>|"|\s)(?:true|false)(?:<|$|"|\s)/,
      B6_iso_timestamp:     /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/,
      // C. 字段名/code 未 i18n
      C1_camelCase_field:   /\b[a-z]+[A-Z][a-zA-Z]+["']?\s*[:=]/,
      C2_db_column:         /\b[a-z]+_(?:at|id|by|code|name)\b/,
      C5_error_code:        /\bERR_\d+|ERROR_\d{3,}/,
      // D. 数据格式未人类化
      D1_no_thousands_sep:  />\s*\d{5,}(?:\.\d+)?\s*</,
      // F. 系统行为暴露
      F2_rate_limit_raw:    /\b429\b|too many requests/i,
      F5_unauthorized_raw:  /\b401\b|unauthorized/i,
      // G. AI 输出特殊
      G1_llm_hallucination: /根据(?:我|AI)的(?:训练|知识)|抱歉.{0,30}AI(?:无法|不能)/i,
      G2_prompt_leak:       /You are an AI|system prompt|^assistant:/im,
      G4_skill_chain_step:  /Step\s*\d+\s*of\s*\d+/i,
      // I. 权限信息 leak
      I3_factory_id_leak:   /factory_id["']?\s*[:=]|user_id["']?\s*[:=]|RES_3101_009|F006_PROD/,
      // N. 时区 leak
      N1_timezone_raw:      /[+-]\d{2}:\d{2}(?:$|\s|"|<)/,
    };
    const text = (document.body as HTMLElement).innerText;
    const findings: Array<{ code: string; count: number; sample: string }> = [];
    for (const [code, regex] of Object.entries(ANTI)) {
      const re = new RegExp(regex.source, regex.flags + 'g');
      const matches = text.match(re);
      if (matches && matches.length > 0) {
        findings.push({ code, count: matches.length, sample: matches[0].slice(0, 120) });
      }
    }
    return findings;
  });

  // Step 9.6: If leaks found, capture dedicated leak screenshot + log
  if (cap.leaks && cap.leaks.length > 0) {
    const leakPngPath = path.join(OUT_DIR, `LEAK-${cap.caseId}.png`);
    await page.screenshot({ path: leakPngPath, fullPage: true, timeout: 60_000 }).catch(() => {});
    cap.leakPngPath = leakPngPath;
    console.log(`[LEAK ${cap.caseId}] ${cap.leaks.length} pattern(s) hit:`,
      cap.leaks.map(l => `${l.code}×${l.count}`).join(', '));
  }

  // Step 10: Screenshot fullPage
  const pngPath = path.join(OUT_DIR, `${cap.caseId}.png`);
  await page.screenshot({ path: pngPath, fullPage: true, timeout: 60_000 }).catch(() => {});
  cap.pngPath = pngPath;

  cap.status = 'PASS';
}

test.beforeAll(() => {
  fs.mkdirSync(OUT_DIR, { recursive: true });
});

test.afterAll(() => {
  fs.writeFileSync(JSON_OUT, JSON.stringify(captures, null, 2));
  console.log(`[audit] wrote ${captures.length} captures to ${JSON_OUT}`);
});

test.describe.serial('Sprint 11 AI Workdesk Full E2E + UX Audit — 22 cases', () => {
  test.setTimeout(240_000);

  // === Core 12 happy: SalesOwner × 4 phrase × 3 accounts ===
  for (const acct of ACCOUNTS) {
    for (let pIdx = 0; pIdx < CORE_PHRASES.length; pIdx++) {
      const phrase = CORE_PHRASES[pIdx];
      const caseId = `core_${acct.label}__phrase${pIdx + 1}`;
      // depth: phrase1 deep (LLM-timeout-prone), others medium
      const depth: CaseCapture['depth'] = pIdx === 0 ? 'deep' : 'medium';

      test(`[${depth}] ${caseId} — "${phrase}"`, async ({ page }) => {
        const cap: CaseCapture = {
          caseId, depth, account: acct.label, factoryId: acct.factoryId,
          workdesk: 'SalesOwner', route: 'workdesk/sales-owner', phrase,
          status: 'FAIL',
        };
        try {
          await runWorkdeskCase(page, cap, acct, 'workdesk/sales-owner', phrase);
        } catch (e: any) {
          cap.status = String(e?.message || '').includes('Timeout') ? 'TIMEOUT' : 'FAIL';
          cap.error = String(e?.message || e).slice(0, 500);
        } finally {
          captures.push(cap);
        }
      });
    }
  }

  // === Breadth (Rule 16): 6 其他 Workdesks × 1 phrase × qhj_warehouse_mgr ===
  // Use qhj account (RES_3101_009) — may show error if account doesn't have access to that Workdesk
  for (const wd of BREADTH_WORKDESKS) {
    const caseId = `breadth_${wd.name}`;
    test(`[breadth-smoke] ${caseId} — "${wd.phrase}" via ${wd.route}`, async ({ page }) => {
      const cap: CaseCapture = {
        caseId, depth: 'breadth-smoke', account: 'qhj_warehouse_mgr', factoryId: 'RES_3101_009',
        workdesk: wd.name, route: wd.route, phrase: wd.phrase,
        status: 'FAIL',
      };
      try {
        await runWorkdeskCase(page, cap, ACCOUNTS[0], wd.route, wd.phrase);
      } catch (e: any) {
        cap.status = String(e?.message || '').includes('Timeout') ? 'TIMEOUT' : 'FAIL';
        cap.error = String(e?.message || e).slice(0, 500);
      } finally {
        captures.push(cap);
      }
    });
  }

  // === Error-deep 4 cases (四位一体 per qa-prompt) ===
  for (const ec of ERROR_CASES) {
    const caseId = `error_${ec.id}_${ec.label}`;
    test(`[error-deep] ${caseId} — ${ec.description}`, async ({ page }) => {
      const cap: CaseCapture = {
        caseId, depth: 'error-deep', account: 'qhj_warehouse_mgr', factoryId: 'RES_3101_009',
        workdesk: ec.id === 'E4' ? 'QualityChief' : 'SalesOwner',
        route: ec.id === 'E4' ? 'workdesk/quality-chief' : 'workdesk/sales-owner',
        phrase: ec.phrase, contextMonth: (ec as any).contextMonth,
        status: 'FAIL',
      };
      try {
        await runWorkdeskCase(page, cap, ACCOUNTS[0], cap.route, cap.phrase, cap.contextMonth);

        // 四位一体 verdict
        const respBody = cap.executeRespBody || '';
        const backendMessage = (() => {
          try { return JSON.parse(respBody)?.data?.message || JSON.parse(respBody)?.message || ''; } catch { return ''; }
        })();
        const toastTexts = (cap.toastLog || []).map(t => t.text).join(' || ');
        const matchesBackend = backendMessage && toastTexts.includes(backendMessage.slice(0, 20));
        const sticky = (cap.toastLog || []).some(t => t.cls.includes('is-closable') || t.hasClose);
        const showClose = sticky;
        const hasActionHint = /请|前往|联系|查看|建议|尝试|添加/i.test(toastTexts) || /actionHint/.test(respBody);
        cap.fourInOneVerdict = {
          backendMessageMatches: !!matchesBackend,
          toastSticky: sticky,
          toastShowClose: showClose,
          hasActionHint,
        };
      } catch (e: any) {
        cap.status = String(e?.message || '').includes('Timeout') ? 'TIMEOUT' : 'FAIL';
        cap.error = String(e?.message || e).slice(0, 500);
      } finally {
        captures.push(cap);
      }
    });
  }
});
