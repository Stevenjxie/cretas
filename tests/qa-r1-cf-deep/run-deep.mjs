// R1 Part 3 — Customer-facing Deep E2E
// Branch: qa/r1-customer-facing-deep
// Env: test (8097) per spec §4 (override via E2E_ADMIN_URL=http://139.196.165.140:8097)
// Scope: PR #423 v-if defense + PR #413 PDF + PR #414 收货数量 col, admin × warehouse_mgr1
// Date: 2026-05-13
// Architecture: 1 browser context per user (1 login, multi-navigation) → avoids 60s rate-limit

import { createRequire } from 'node:module';
import { writeFile, mkdir } from 'node:fs/promises';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require('C:/Users/Steve/my-prototype-logistics/web-admin/node_modules/playwright');

const __dirname = dirname(fileURLToPath(import.meta.url));
const SCREENSHOT_DIR = `${__dirname}/screenshots`;
const PDF_DIR = `${__dirname}/pdfs`;
const OUTPUT_JSON = `${__dirname}/round-1-cf-deep.json`;
const FACTORY_ID = 'F001';
const BASE = process.env.E2E_ADMIN_URL || 'http://139.196.165.140:8097';
const PASSWORD = process.env.E2E_PASSWORD || '123456';

const ROLES = [
  { username: 'factory_admin1', role: 'factory_super_admin', expectsPriceVisible: true },
  { username: 'warehouse_mgr1', role: 'warehouse_manager', expectsPriceVisible: false },
];

const PAGES = [
  { key: 'procurement_orders', path: '/procurement/orders', pr: '#423+#413' },
  { key: 'procurement_receives', path: '/procurement/receives', pr: '#423+#414' },
  { key: 'sales_orders', path: '/sales/orders', pr: '#423' },
];

// ============================================================
// Login with retry (uses inlined login flow — does NOT use helpers BASE)
// ============================================================
async function loginInPage(page, username) {
  for (let attempt = 1; attempt <= 2; attempt++) {
    try {
      await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 30000 });
      await page.waitForSelector('input.el-input__inner', { timeout: 30000 });
      await page.fill('input.el-input__inner[placeholder*="用户名"]', username);
      await page.fill('input[type="password"]', PASSWORD);
      await page.click('button.login-button');
      for (let i = 0; i < 25; i++) {
        await page.waitForTimeout(1000);
        const url = page.url();
        if (!url.includes('/login')) {
          if (url.includes('/mobile-only')) return 'MOBILE_ONLY';
          return 'OK';
        }
      }
    } catch (e) {
      if (attempt === 2) return 'LOGIN_ERROR:' + e.message.slice(0, 80);
    }
    await page.waitForTimeout(3000);
  }
  return 'LOGIN_TIMEOUT';
}

async function navigateAndWait(page, path) {
  try {
    await page.goto(`${BASE}${path}`, { waitUntil: 'domcontentloaded', timeout: 30000 });
    // Wait for table OR substantial render
    for (let i = 0; i < 30; i++) {
      await page.waitForTimeout(500);
      const ready = await page.evaluate(() => {
        const hasTable = !!document.querySelector('.el-table__body-wrapper');
        const hasContent = (document.body?.innerText?.length || 0) > 200;
        return hasTable || hasContent;
      });
      if (ready) break;
    }
    await page.waitForTimeout(2000); // settle for v-if rendering
    return 'OK';
  } catch (e) {
    return 'NAV_ERROR:' + e.message.slice(0, 80);
  }
}

async function safeScreenshot(page, path) {
  try {
    await page.screenshot({ path, fullPage: false, timeout: 10000 });
    return true;
  } catch (e) {
    return false;
  }
}

async function readListSnapshot(page) {
  return page.evaluate(() => {
    const tables = Array.from(document.querySelectorAll('.el-table'));
    if (!tables.length) return { error: 'no_table_found' };
    const tbl = tables[0];
    const headers = Array.from(tbl.querySelectorAll('.el-table__header th .cell, .el-table__header-wrapper th .cell'))
      .map((c) => c.textContent.trim()).filter(Boolean);
    const rowEls = Array.from(tbl.querySelectorAll('.el-table__body tr.el-table__row')).slice(0, 5);
    const rows = rowEls.map((r) => {
      const cells = Array.from(r.querySelectorAll('td .cell'));
      const obj = {};
      headers.forEach((h, i) => { obj[h] = cells[i] ? cells[i].textContent.trim() : ''; });
      obj._maskedSpans = Array.from(r.querySelectorAll('.price-masked')).map((s) => s.textContent.trim());
      return obj;
    });
    return { headers, rows, rowCount: rows.length, totalMaskedSpans: rows.reduce((a, r) => a + (r._maskedSpans?.length || 0), 0) };
  });
}

async function openReceivesDetail(page) {
  const clicked = await page.evaluate(() => {
    const row = document.querySelector('.el-table__body tr.el-table__row');
    if (!row) return false;
    const btn = Array.from(row.querySelectorAll('button')).find((b) => /详情/.test(b.textContent || ''));
    if (!btn) return false;
    btn.click();
    return true;
  });
  if (!clicked) return { opened: false, reason: 'detail_button_not_found' };
  for (let i = 0; i < 20; i++) {
    await page.waitForTimeout(500);
    const visible = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.el-dialog')).some((d) => d.offsetParent !== null));
    if (visible) break;
  }
  await page.waitForTimeout(1500);
  return page.evaluate(() => {
    const dialogs = Array.from(document.querySelectorAll('.el-dialog'))
      .filter((d) => d.offsetParent !== null);
    if (!dialogs.length) return { opened: false, reason: 'dialog_not_visible' };
    const dialog = dialogs[0];
    const tables = Array.from(dialog.querySelectorAll('.el-table'));
    const dialogData = tables.map((tbl) => {
      const headers = Array.from(tbl.querySelectorAll('th .cell')).map((c) => c.textContent.trim());
      const rowEls = Array.from(tbl.querySelectorAll('tr.el-table__row'));
      const rows = rowEls.map((r) => {
        const cells = Array.from(r.querySelectorAll('td .cell'));
        const obj = {};
        headers.forEach((h, i) => { obj[h] = cells[i] ? cells[i].textContent.trim() : ''; });
        obj._maskedSpans = Array.from(r.querySelectorAll('.price-masked')).map((s) => s.textContent.trim());
        return obj;
      });
      return { headers, rows };
    });
    return { opened: true, dialogData };
  });
}

async function closeDialog(page) {
  await page.evaluate(() => {
    const dialog = Array.from(document.querySelectorAll('.el-dialog')).find((d) => d.offsetParent !== null);
    if (dialog) {
      const close = dialog.querySelector('.el-dialog__headerbtn, button[aria-label="Close"]');
      if (close) close.click();
    }
  });
  await page.waitForTimeout(500);
}

async function getTokenAndOrderIds(page) {
  return page.evaluate(async ({ base, factoryId }) => {
    const tokenKey = 'cretas_access_token';
    const bearer = localStorage.getItem(tokenKey) || sessionStorage.getItem(tokenKey);
    if (!bearer) return { error: 'no_token' };
    try {
      const resp = await fetch(`${base}/api/mobile/${factoryId}/purchase/orders?page=1&size=3`,
        { headers: { Authorization: `Bearer ${bearer}` } });
      if (!resp.ok) return { error: 'list_status_' + resp.status };
      const j = await resp.json();
      const items = j?.data?.content || j?.data?.items || j?.data || [];
      return {
        tokenLen: bearer.length,
        ids: items.slice(0, 3).map((it) => ({ id: it.id, orderNumber: it.orderNumber, status: it.status })),
      };
    } catch (e) {
      return { error: String(e) };
    }
  }, { base: BASE, factoryId: FACTORY_ID });
}

async function downloadPdfDirect(page, orderId, label) {
  const result = await page.evaluate(async ({ base, factoryId, orderId }) => {
    try {
      const bearer = localStorage.getItem('cretas_access_token') || sessionStorage.getItem('cretas_access_token');
      if (!bearer) return { ok: false, reason: 'no_token' };
      const url = `${base}/api/mobile/${factoryId}/purchase/orders/${orderId}/pdf`;
      const resp = await fetch(url, { headers: { Authorization: `Bearer ${bearer}` } });
      if (!resp.ok) return { ok: false, status: resp.status, statusText: resp.statusText, url };
      const buf = await resp.arrayBuffer();
      const bytes = new Uint8Array(buf);
      const hex = Array.from(bytes.slice(0, 8)).map((b) => b.toString(16).padStart(2, '0')).join(' ');
      const head = new TextDecoder().decode(bytes.slice(0, 8));
      let bin = '';
      for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
      const base64 = btoa(bin);
      return { ok: true, status: resp.status, size: bytes.length, headHex: hex, headStr: head, base64, url };
    } catch (e) {
      return { ok: false, reason: 'fetch_error', message: String(e) };
    }
  }, { base: BASE, factoryId: FACTORY_ID, orderId });
  if (result.ok) {
    const bin = Buffer.from(result.base64, 'base64');
    await mkdir(PDF_DIR, { recursive: true });
    const pdfPath = `${PDF_DIR}/${label}.pdf`;
    await writeFile(pdfPath, bin);
    result.pdfPath = pdfPath;
    delete result.base64;
    const text = bin.toString('latin1');
    result.containsPrice = /单价|小计|合计/.test(text);
    const priceMatches = text.match(/(单价|小计|合计)[\s\S]{0,60}/g) || [];
    result.priceSnippets = priceMatches.slice(0, 3);
  }
  return result;
}

// ============================================================
// Run all cells for one user (1 login, 3 page nav + PDF)
// ============================================================
async function runUserSession(browser, role) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, acceptDownloads: true });
  const page = await ctx.newPage();
  const allApiCalls = [];
  const allConsoleErrors = [];
  page.on('response', (resp) => {
    const u = resp.url();
    if (u.includes('/api/mobile/')) allApiCalls.push({ url: u, status: resp.status(), ts: Date.now() });
  });
  page.on('console', (msg) => {
    if (msg.type() === 'error') allConsoleErrors.push({ text: msg.text().slice(0, 200), ts: Date.now() });
  });

  const session = { user: role.username, role: role.role, cells: [] };

  // === Login ===
  const loginStart = Date.now();
  const loginResult = await loginInPage(page, role.username);
  session.loginResult = loginResult;
  session.loginDurationMs = Date.now() - loginStart;
  if (loginResult !== 'OK') {
    await safeScreenshot(page, `${SCREENSHOT_DIR}/${role.username}-login-FAIL.png`);
    session.cells.push({ cellId: `${role.username}-login`, verdict: 'FAIL_LOGIN', loginResult });
    await ctx.close();
    return session;
  }

  // === 3 page cells (medium) ===
  for (const pg of PAGES) {
    const cellStart = Date.now();
    const apiBefore = allApiCalls.length;
    const consoleBefore = allConsoleErrors.length;
    const cellId = `${pg.key}-${role.username}`;
    const ev = {
      cellId,
      role: role.username,
      page: pg.path,
      pr: pg.pr,
      depth: 'medium',
      ts: new Date().toISOString(),
    };
    const navResult = await navigateAndWait(page, pg.path);
    ev.navResult = navResult;
    await safeScreenshot(page, `${SCREENSHOT_DIR}/${cellId}-list.png`);
    ev.list = await readListSnapshot(page);

    if (pg.path === '/procurement/receives') {
      ev.detail = await openReceivesDetail(page);
      if (ev.detail?.opened) {
        await safeScreenshot(page, `${SCREENSHOT_DIR}/${cellId}-detail.png`);
        await closeDialog(page);
      }
    }

    ev.apiCalls = allApiCalls.slice(apiBefore);
    ev.consoleErrors = allConsoleErrors.slice(consoleBefore);
    ev.errorApiCalls = ev.apiCalls.filter((c) => c.status >= 400);
    ev.durationMs = Date.now() - cellStart;
    ev.verdict = ev.list?.error ? 'FAIL_DOM' : 'PASS';
    session.cells.push(ev);
  }

  // === Deep cell — PDF download (Rule 11 wire+roundtrip) ===
  const pdfStart = Date.now();
  const pdfApiBefore = allApiCalls.length;
  const pdfEv = {
    cellId: `pdf-deep-${role.username}`,
    role: role.username,
    page: '/procurement/orders',
    pr: '#413+#423',
    depth: 'deep',
    ts: new Date().toISOString(),
  };
  // Make sure we're on /procurement/orders (last cell was sales/orders)
  await navigateAndWait(page, '/procurement/orders');
  const tokenAndIds = await getTokenAndOrderIds(page);
  pdfEv.tokenAndIds = tokenAndIds;
  if (tokenAndIds?.ids?.length) {
    const target = tokenAndIds.ids[0];
    pdfEv.targetOrder = target;
    const dl = await downloadPdfDirect(page, target.id, `${pdfEv.cellId}-${target.orderNumber}`);
    pdfEv.download = dl;
  }
  pdfEv.apiCalls = allApiCalls.slice(pdfApiBefore);
  pdfEv.errorApiCalls = pdfEv.apiCalls.filter((c) => c.status >= 400);
  pdfEv.durationMs = Date.now() - pdfStart;
  pdfEv.verdict = pdfEv.download?.ok ? 'PASS' : 'FAIL_PDF';
  session.cells.push(pdfEv);

  await ctx.close();
  return session;
}

// ============================================================
// Main
// ============================================================
async function main() {
  await mkdir(SCREENSHOT_DIR, { recursive: true });
  const startedAt = new Date().toISOString();
  console.log(`[${startedAt}] R1 part 3 chat3 — Customer-facing deep E2E`);
  console.log(`Base: ${BASE}`);

  const browser = await chromium.launch({ headless: true });
  const sessions = [];
  for (const role of ROLES) {
    console.log(`\n[${new Date().toISOString()}] Running session for ${role.username}`);
    try {
      const s = await runUserSession(browser, role);
      sessions.push(s);
      for (const c of s.cells) {
        console.log(`  ${c.cellId} verdict=${c.verdict} rows=${c.list?.rows?.length || 0} masked=${c.list?.totalMaskedSpans || 0} apiErrors=${c.errorApiCalls?.length || 0}`);
        if (c.download) console.log(`    PDF: status=${c.download.status} size=${c.download.size} containsPrice=${c.download.containsPrice}`);
      }
    } catch (e) {
      console.error(`  Session ERROR: ${e.message}`);
      sessions.push({ user: role.username, error: e.message });
    }
  }
  await browser.close();

  const allCells = sessions.flatMap((s) => s.cells || []);
  const summary = {
    round: 'R1-part3-chat3',
    spec: 'docs/qa-specs/2026-05-12-smartbi-python-port-deep-e2e-spec.md §3.2',
    env: BASE,
    startedAt,
    finishedAt: new Date().toISOString(),
    branch: 'qa/r1-customer-facing-deep',
    cellCount: allCells.length,
    depthBreakdown: {
      smoke: allCells.filter((c) => c.depth === 'smoke').length,
      medium: allCells.filter((c) => c.depth === 'medium').length,
      deep: allCells.filter((c) => c.depth === 'deep').length,
    },
    pass: allCells.filter((c) => c.verdict === 'PASS').length,
    fail: allCells.filter((c) => c.verdict?.startsWith('FAIL')).length,
    sessions,
  };

  await writeFile(OUTPUT_JSON, JSON.stringify(summary, null, 2));
  console.log(`\n[${summary.finishedAt}] Done. Wrote ${OUTPUT_JSON}`);
  console.log(`Summary: pass=${summary.pass} fail=${summary.fail} depth=${JSON.stringify(summary.depthBreakdown)}`);
}

main().catch((e) => {
  console.error('FATAL:', e);
  process.exit(1);
});
