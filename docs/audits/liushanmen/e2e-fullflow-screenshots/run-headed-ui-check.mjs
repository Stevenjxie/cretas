import { chromium } from 'playwright';
import fs from 'node:fs/promises';
import path from 'node:path';

const OUT = path.resolve('docs/audits/liushanmen/e2e-fullflow-screenshots');
const BASE = process.env.E2E_ADMIN_URL || 'http://139.196.165.140:8086';
const profileDir = path.resolve('.pw-cache-codex-fullflow');

const routes = [
  ['01-dashboard', '/dashboard'],
  ['02-sales-orders', '/sales/orders'],
  ['03-sales-order-detail', '/sales/orders/d2eab1c6-c29c-4f23-8b4b-6378b19c0334'],
  ['04-procurement-payment-requests', '/procurement/payment-requests'],
  ['05-sales-payment-requests-old-path', '/sales/payment-requests'],
  ['06-production-plans', '/production/plans'],
  ['07-production-batches', '/production/batches'],
  ['08-production-reversals', '/production/reversals'],
  ['09-warehouse-material-types', '/warehouse/material-types'],
  ['10-rd-samples', '/rd/samples'],
  ['11-production-bom', '/production/bom'],
  ['12-finance-inventory-ledger', '/finance/inventory-ledger'],
];

const probes = [
  '关联客户',
  '包材规格',
  '价位选料',
  '最低价',
  '最高价',
  'packQtyPerProduct',
  '付款申请',
  '撤回',
  'BOM',
];

async function main() {
  await fs.mkdir(OUT, { recursive: true });
  const browser = await chromium.launchPersistentContext(profileDir, {
    headless: false,
    viewport: { width: 1920, height: 1080 },
    args: [
      '--lang=zh-CN',
      '--font-render-hinting=none',
      '--remote-debugging-port=9222',
    ],
    locale: 'zh-CN',
  });
  const page = await browser.newPage();
  const consoleMessages = [];
  const networkFailures = [];
  page.on('console', (msg) => {
    if (['error', 'warning'].includes(msg.type())) {
      consoleMessages.push(`[${msg.type()}] ${msg.text()}`);
    }
  });
  page.on('requestfailed', (req) => {
    networkFailures.push(`${req.method()} ${req.url()} :: ${req.failure()?.errorText || 'failed'}`);
  });

  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
  await page.screenshot({ path: path.join(OUT, '00-login.png'), fullPage: true });

  const inputs = page.locator('input');
  await inputs.nth(0).fill('f006_admin');
  await inputs.nth(1).fill('123456');
  await page.locator('button').filter({ hasText: /登|录|Login/i }).first().click();
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 15000 }).catch(() => {});
  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});

  const results = [];
  for (const [name, route] of routes) {
    const url = `${BASE}${route}`;
    await page.goto(url, { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('networkidle', { timeout: 12000 }).catch(() => {});
    await page.waitForTimeout(800);
    const screenshot = path.join(OUT, `${name}.png`);
    await page.screenshot({ path: screenshot, fullPage: true });
    const text = await page.locator('body').innerText({ timeout: 5000 }).catch(() => '');
    results.push({
      name,
      route,
      finalUrl: page.url(),
      title: await page.title().catch(() => ''),
      found: Object.fromEntries(probes.map((p) => [p, text.includes(p)])),
      textSample: text.replace(/\s+/g, ' ').slice(0, 500),
    });
  }

  await page.goto(`${BASE}/warehouse/material-types`, { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle', { timeout: 12000 }).catch(() => {});
  await page.locator('button').filter({ hasText: /新建|新增|添加/ }).first().click().catch(() => {});
  await page.waitForTimeout(800);
  await page.screenshot({ path: path.join(OUT, '13-material-types-create-dialog.png'), fullPage: true });
  const materialDialogText = await page.locator('body').innerText().catch(() => '');

  await page.goto(`${BASE}/production/bom`, { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle', { timeout: 12000 }).catch(() => {});
  await page.screenshot({ path: path.join(OUT, '14-bom-page.png'), fullPage: true });
  const bomText = await page.locator('body').innerText().catch(() => '');

  await fs.writeFile(path.join(OUT, 'ui-check-result.json'), JSON.stringify({
    base: BASE,
    headed: true,
    viewport: '1920x1080',
    lang: 'zh-CN',
    fontRenderHinting: 'none',
    routes: results,
    dialogs: {
      materialTypesCreate: Object.fromEntries(probes.map((p) => [p, materialDialogText.includes(p)])),
      bomPage: Object.fromEntries(probes.map((p) => [p, bomText.includes(p)])),
    },
    consoleMessages,
    networkFailures,
  }, null, 2), 'utf8');

  await browser.close();
}

main().catch(async (err) => {
  await fs.mkdir(OUT, { recursive: true });
  await fs.writeFile(path.join(OUT, 'ui-check-error.txt'), `${err.stack || err.message || err}\n`, 'utf8');
  process.exitCode = 1;
});
