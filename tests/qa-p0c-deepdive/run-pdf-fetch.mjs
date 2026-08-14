// P0-C PDF byte-diff deep dive — F006 reproduction
// Branch: qa/p0c-pdf-bytediff-deepdive  •  Env: test 8097  •  Date: 2026-05-13
// Strategy: 1 browser context per user, login once, fetch PDF via direct /pdf API call.

import { createRequire } from 'node:module';
import { writeFile, mkdir } from 'node:fs/promises';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require('C:/Users/Steve/my-prototype-logistics/web-admin/node_modules/playwright');

const __dirname = dirname(fileURLToPath(import.meta.url));
const BASE = process.env.E2E_ADMIN_URL || 'http://139.196.165.140:8097';
const PASSWORD = '123456';
const FACTORY_ID = process.env.E2E_FACTORY_ID || 'F006';
const ADMIN_USER = process.env.E2E_ADMIN_USER || ADMIN_USER;
const WAREHOUSE_USER = process.env.E2E_WAREHOUSE_USER || WAREHOUSE_USER;
console.log(`Accounts: admin=${ADMIN_USER}  warehouse=${WAREHOUSE_USER}`);

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
        if (!page.url().includes('/login')) return 'OK';
      }
    } catch (e) {
      if (attempt === 2) return 'LOGIN_ERROR:' + e.message.slice(0, 80);
    }
    await page.waitForTimeout(3000);
  }
  return 'LOGIN_TIMEOUT';
}

// Fetch from inside browser (uses cookies + token automatically)
async function fetchOrderList(page) {
  return page.evaluate(async ({ base, factoryId }) => {
    const bearer = localStorage.getItem('cretas_access_token') || sessionStorage.getItem('cretas_access_token');
    if (!bearer) return { error: 'no_token' };
    // Try multiple page sizes — pick the largest order set, sort by totalAmount to find one with prices
    const resp = await fetch(`${base}/api/mobile/${factoryId}/purchase/orders?page=1&size=20`,
      { headers: { Authorization: `Bearer ${bearer}` } });
    if (!resp.ok) return { error: 'list_status_' + resp.status, statusText: resp.statusText };
    const j = await resp.json();
    const items = j?.data?.content || j?.data?.items || j?.data || [];
    // Return ALL ids + their key fields so we can pick best target
    return {
      tokenLen: bearer.length,
      count: items.length,
      items: items.slice(0, 20).map((it) => ({
        id: it.id,
        orderNumber: it.orderNumber,
        status: it.status,
        totalAmount: it.totalAmount,
        itemCount: (it.items?.length) ?? null,
      })),
    };
  }, { base: BASE, factoryId: FACTORY_ID });
}

async function downloadPdf(page, orderId) {
  return page.evaluate(async ({ base, factoryId, orderId }) => {
    const bearer = localStorage.getItem('cretas_access_token') || sessionStorage.getItem('cretas_access_token');
    if (!bearer) return { ok: false, reason: 'no_token' };
    const url = `${base}/api/mobile/${factoryId}/purchase/orders/${orderId}/pdf`;
    const t0 = Date.now();
    const resp = await fetch(url, { headers: { Authorization: `Bearer ${bearer}` } });
    if (!resp.ok) return { ok: false, status: resp.status, statusText: resp.statusText, url };
    const buf = await resp.arrayBuffer();
    const bytes = new Uint8Array(buf);
    let bin = '';
    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return {
      ok: true,
      status: resp.status,
      size: bytes.length,
      headStr: new TextDecoder().decode(bytes.slice(0, 16)),
      base64: btoa(bin),
      url,
      latencyMs: Date.now() - t0,
      contentType: resp.headers.get('content-type'),
      contentDisposition: resp.headers.get('content-disposition'),
    };
  }, { base: BASE, factoryId: FACTORY_ID, orderId });
}

async function main() {
  await mkdir(__dirname, { recursive: true });
  console.log(`[${new Date().toISOString()}] P0-C deepdive — F006 PDF reproduction`);
  console.log(`Base: ${BASE}`);
  console.log(`Factory: ${FACTORY_ID}`);

  const browser = await chromium.launch({ headless: true });
  const evidence = { startedAt: new Date().toISOString(), env: BASE, factoryId: FACTORY_ID, roles: [] };

  // Step 1: Login as admin first, find an order with prices populated
  let targetOrderId = null;
  let targetOrderNumber = null;
  let adminCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const adminPage = await adminCtx.newPage();
  const adminLogin = await loginInPage(adminPage, ADMIN_USER);
  evidence.adminLogin = adminLogin;
  if (adminLogin === 'OK') {
    const list = await fetchOrderList(adminPage);
    evidence.adminOrderList = list;
    // Prefer order with non-null/non-zero totalAmount
    const withPrice = list.items?.find((it) => it.totalAmount && Number(it.totalAmount) > 0);
    const target = withPrice || list.items?.[0];
    if (target) {
      targetOrderId = target.id;
      targetOrderNumber = target.orderNumber;
      evidence.target = target;
      console.log(`Target order: ${target.orderNumber} (id=${target.id}, totalAmount=${target.totalAmount})`);
      // Download admin PDF
      const adminPdf = await downloadPdf(adminPage, target.id);
      evidence.roles.push({ role: 'admin', username: ADMIN_USER, loginResult: adminLogin, pdfMeta: { ...adminPdf, base64: undefined } });
      if (adminPdf.ok) {
        await writeFile(`${__dirname}/admin.pdf`, Buffer.from(adminPdf.base64, 'base64'));
        console.log(`admin.pdf: ${adminPdf.size}B, head=${JSON.stringify(adminPdf.headStr)}, latency=${adminPdf.latencyMs}ms`);
      }
    }
  }
  await adminCtx.close();

  // Step 2: Login as warehouse, download same order PDF
  if (targetOrderId) {
    const whCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    const whPage = await whCtx.newPage();
    const whLogin = await loginInPage(whPage, WAREHOUSE_USER);
    evidence.whLogin = whLogin;
    if (whLogin === 'OK') {
      const whPdf = await downloadPdf(whPage, targetOrderId);
      evidence.roles.push({ role: 'warehouse', username: WAREHOUSE_USER, loginResult: whLogin, pdfMeta: { ...whPdf, base64: undefined } });
      if (whPdf.ok) {
        await writeFile(`${__dirname}/warehouse.pdf`, Buffer.from(whPdf.base64, 'base64'));
        console.log(`warehouse.pdf: ${whPdf.size}B, head=${JSON.stringify(whPdf.headStr)}, latency=${whPdf.latencyMs}ms`);
      }
    }
    await whCtx.close();
  }

  await browser.close();
  evidence.finishedAt = new Date().toISOString();
  await writeFile(`${__dirname}/fetch-evidence.json`, JSON.stringify(evidence, null, 2));
  console.log(`\nDone. fetch-evidence.json written.`);
  if (evidence.roles.length === 2 && evidence.roles[0].pdfMeta?.ok && evidence.roles[1].pdfMeta?.ok) {
    const adminSize = evidence.roles[0].pdfMeta.size;
    const whSize = evidence.roles[1].pdfMeta.size;
    console.log(`SIZE DELTA: admin=${adminSize}B, warehouse=${whSize}B, delta=${adminSize - whSize}B`);
  }
}

main().catch((e) => { console.error('FATAL:', e); process.exit(1); });
