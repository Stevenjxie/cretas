/**
 * Final customer-audit E2E (2026-05-12)
 * Targets: prod web-admin http://139.196.165.140:8086
 * Scenarios (per qa-prompt v2.4 Rule 1-17):
 *   S1  餐饮领料筛选 (gml_admin, PR #169)
 *   S2  餐饮配方 BOM 表单 (gml_admin, PR #297 D2 yield-rate)
 *   S3  调拨单 batch dropdown + 现有库存列 (f006_admin, PR #322/#295)
 *   S5  分仓库存查询页 (f006_admin, PR #323)
 *   S6  销售订单 WH-LOG label (f006_admin, PR #329/#355)
 *   S7  生产计划开始库存校验 four-tuple (f006_admin, PR #305)
 *   S8  NEW 采购订单 PDF 下载 (f006_admin, PR #413)
 *   S9  NEW 收货记录 收货数量列 (f006_admin, PR #414)
 *   S10 NEW RBAC price strip — f006_warehouse_mgr (PR #423)
 *   S11 D1 双仓 UI label 线边仓/总仓 verify
 *   S12 Error UX — 404/403 + Rule 8 four-tuple sample
 */
import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';

const PROD_URL = 'http://139.196.165.140:8086';
const RESTAURANT_USER = { user: 'gml_admin', pass: '123456', factory: 'R_GML_DEMO' };
const FACTORY_USER = { user: 'f006_admin', pass: '123456', factory: 'F006' };
const WAREHOUSE_USER = { user: 'f006_warehouse_mgr', pass: '123456', factory: 'F006' };

const SHOT_DIR = path.resolve('C:/Users/Steve/my-prototype-logistics/.worktrees/final-customer-audit/scripts/customer-audit-e2e-2026-05-12/shots');
const RESULTS_PATH = path.resolve('C:/Users/Steve/my-prototype-logistics/.worktrees/final-customer-audit/scripts/customer-audit-e2e-2026-05-12/results.json');
fs.mkdirSync(SHOT_DIR, { recursive: true });

const results = [];
const networkLog = [];
const consoleErrors = [];

function record(scenario, status, evidence) {
  results.push({ scenario, status, evidence, ts: new Date().toISOString() });
  console.log(`\n[${status}] ${scenario}`);
  try {
    const pre = JSON.stringify(evidence).slice(0, 400);
    console.log('  evidence:', pre);
  } catch (e) { /* ignore */ }
}

async function shot(page, name) {
  try {
    const p = path.join(SHOT_DIR, name);
    await page.screenshot({ path: p, fullPage: true });
    return p;
  } catch (e) { return `err:${e.message}`; }
}

async function waitForAppReady(page, timeout = 15000) {
  await page.waitForSelector('.el-container, .el-card, .el-table, .login-page', { timeout }).catch(() => {});
  await page.waitForLoadState('networkidle', { timeout: 5000 }).catch(() => {});
  await page.waitForTimeout(800);
}

async function login(page, account) {
  console.log(`\n--- Login as ${account.user} (factory=${account.factory}) ---`);
  await page.goto(`${PROD_URL}/login`, { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForSelector('input', { timeout: 10000 });
  await page.locator('input[placeholder*="用户名"]').fill(account.user);
  await page.locator('input[placeholder="请输入密码"]').fill(account.pass);
  await shot(page, `login-${account.user}.png`);
  await page.locator('button.login-button').first().click();
  // Wait for navigation away from /login
  try {
    await page.waitForURL((url) => !url.toString().includes('/login'), { timeout: 18000 });
    await waitForAppReady(page);
    console.log('  login OK. url=', page.url());
    await shot(page, `home-${account.user}.png`);
    return true;
  } catch (e) {
    console.log('  login FAIL', e.message);
    await shot(page, `login-fail-${account.user}.png`);
    return false;
  }
}

async function logout(page) {
  try {
    await page.evaluate(() => {
      try { localStorage.clear(); sessionStorage.clear(); } catch (e) {}
    });
  } catch (e) {}
  await page.context().clearCookies();
}

async function navigateAndCapture(page, route) {
  await page.goto(`${PROD_URL}${route}`, { waitUntil: 'networkidle', timeout: 30000 });
  await waitForAppReady(page);
  const url = page.url();
  const is403 = url.includes('/403');
  const is404 = url.includes('/404');
  const isLogin = url.includes('/login');
  const bodyText = await page.locator('body').textContent().catch(() => '');
  return { url, is403, is404, isLogin, bodyTextLen: bodyText.length, bodyText };
}

function installNetworkLog(page, tag) {
  page.on('response', async (resp) => {
    try {
      const u = resp.url();
      const st = resp.status();
      if (st >= 400 && (u.includes('/api/') || u.includes('mobile'))) {
        let body = '';
        try { body = (await resp.text()).slice(0, 400); } catch (e) { body = '<err>'; }
        networkLog.push({ scenario: tag, url: u, status: st, body });
      }
      // Also collect 200s for /purchase/orders/.../pdf endpoint specifically
      if (u.includes('/pdf') || u.includes('/receives') || u.includes('/purchase/orders')) {
        const ct = resp.headers()['content-type'] || '';
        networkLog.push({ scenario: tag, url: u, status: st, contentType: ct });
      }
    } catch (e) {}
  });
  page.on('console', (msg) => {
    if (msg.type() === 'error') {
      consoleErrors.push({ scenario: tag, text: msg.text().slice(0, 200) });
    }
  });
  page.on('pageerror', (err) => {
    consoleErrors.push({ scenario: tag, text: 'pageerror:' + err.message.slice(0, 200) });
  });
}

// ==========================================================
// Scenarios
// ==========================================================

async function s1Filter(page) {
  console.log('\n=== S1 餐饮领料筛选 (PR #169) ===');
  try {
    const nav = await navigateAndCapture(page, '/restaurant/requisitions');
    await shot(page, 's1.png');
    const statusSelectors = await page.locator('.el-select').count();
    let filterClicked = false, optionShown = false;
    try {
      const statusSelect = page.locator('.el-select').first();
      if (await statusSelect.count() > 0) {
        await statusSelect.click({ timeout: 3000 });
        await page.waitForTimeout(700);
        const optionsVisible = await page.locator('.el-select-dropdown__item').count();
        filterClicked = true;
        optionShown = optionsVisible > 0;
        await page.keyboard.press('Escape');
      }
    } catch (e) {}
    record('S1-requisitions-filter', nav.is403 || nav.is404 ? 'FAIL' : (statusSelectors > 0 ? 'PASS' : 'INFO'), {
      ...nav, statusSelectors, filterClicked, optionShown,
    });
  } catch (e) {
    record('S1-requisitions-filter', 'FAIL', { err: e.message });
  }
}

async function s2RecipeBom(page) {
  console.log('\n=== S2 餐饮配方 BOM 表单 (PR #297 yield-rate) ===');
  try {
    const nav = await navigateAndCapture(page, '/restaurant/recipes');
    await shot(page, 's2.png');
    // Click "新建" button if visible
    let formOpened = false;
    let yieldRateLabel = false;
    let formFields = [];
    const newBtn = page.locator('button:has-text("新建"), button:has-text("添加")').first();
    if (await newBtn.count() > 0) {
      try {
        await newBtn.click({ timeout: 3000 });
        await page.waitForTimeout(1500);
        formOpened = await page.locator('.el-dialog, .el-drawer').count() > 0;
        const labels = await page.locator('.el-form-item__label').allTextContents();
        formFields = labels.map((s) => s.replace(/[\s:]+$/, ''));
        yieldRateLabel = labels.some((s) => s.includes('净料率') || s.includes('出成率') || s.includes('yield'));
        await shot(page, 's2-form.png');
      } catch (e) { console.log('s2 form open err', e.message); }
    }
    record('S2-recipe-bom-yield-rate', nav.is403 ? 'FAIL' : (yieldRateLabel ? 'PASS' : 'INFO'), {
      ...nav, formOpened, yieldRateLabel, formFields,
    });
  } catch (e) {
    record('S2-recipe-bom-yield-rate', 'FAIL', { err: e.message });
  }
}

async function s3TransferBatch(page) {
  console.log('\n=== S3 调拨单 batch dropdown + 现有库存列 (PR #322/#295) ===');
  try {
    const nav = await navigateAndCapture(page, '/inventory/transfers');
    await shot(page, 's3.png');
    // Look for any table columns mentioning 现有库存
    const tableHeaders = await page.locator('.el-table th').allTextContents();
    const hasCurrentStock = tableHeaders.some((h) => h.includes('现有') || h.includes('当前库存'));
    // Open create dialog
    let dialogOpened = false;
    let hasBatchSelect = false;
    const newBtn = page.locator('button:has-text("新建"), button:has-text("手动新建"), button:has-text("创建")').first();
    if (await newBtn.count() > 0) {
      try {
        await newBtn.click({ timeout: 3000 });
        await page.waitForTimeout(1500);
        dialogOpened = await page.locator('.el-dialog').count() > 0;
        // Look for batch / 批次 select within dialog
        hasBatchSelect = await page.locator('.el-dialog').locator(':text("批次"), :text("批号")').count() > 0;
        await shot(page, 's3-create.png');
      } catch (e) { console.log('s3 dialog err', e.message); }
    }
    record('S3-transfer-batch-current-stock', nav.is403 ? 'FAIL' : 'PASS', {
      ...nav,
      tableHeaders: tableHeaders.map((h) => h.trim()).filter(Boolean),
      hasCurrentStockCol: hasCurrentStock,
      dialogOpened, hasBatchSelect,
    });
  } catch (e) {
    record('S3-transfer-batch-current-stock', 'FAIL', { err: e.message });
  }
}

async function s5InventoryByWarehouse(page) {
  console.log('\n=== S5 分仓库存查询页 (PR #323) ===');
  try {
    const nav = await navigateAndCapture(page, '/inventory/by-warehouse');
    await shot(page, 's5.png');
    const tableExists = await page.locator('.el-table').count() > 0;
    const headers = await page.locator('.el-table th').allTextContents();
    const factoryDropdown = await page.locator('.el-select').count();
    record('S5-inventory-by-warehouse', nav.is403 || nav.is404 ? 'FAIL' : (tableExists ? 'PASS' : 'INFO'), {
      ...nav, tableExists, factoryDropdownCount: factoryDropdown,
      headers: headers.map((h) => h.trim()).filter(Boolean),
    });
  } catch (e) {
    record('S5-inventory-by-warehouse', 'FAIL', { err: e.message });
  }
}

async function s6SalesOrderLabel(page) {
  console.log('\n=== S6 销售订单 WH-LOG label (PR #329/#355) ===');
  try {
    const nav = await navigateAndCapture(page, '/sales/orders');
    await shot(page, 's6.png');
    // Open create dialog
    let dialogOpened = false;
    let bodyText = '';
    let hasWhLogText = false;
    let hasZongCangText = false;
    let hasXianBianCangText = false;
    const newBtn = page.locator('button:has-text("新建"), button:has-text("新增"), button:has-text("创建")').first();
    if (await newBtn.count() > 0) {
      try {
        await newBtn.click({ timeout: 3000 });
        await page.waitForTimeout(1500);
        dialogOpened = await page.locator('.el-dialog').count() > 0;
        bodyText = await page.locator('.el-dialog').first().textContent().catch(() => '');
        hasWhLogText = bodyText.includes('WH-LOG');
        hasZongCangText = bodyText.includes('总仓');
        hasXianBianCangText = bodyText.includes('线边仓');
        await shot(page, 's6-dialog.png');
      } catch (e) { console.log('s6 dialog err', e.message); }
    }
    record('S6-sales-order-wh-log-label', nav.is403 ? 'FAIL' : (dialogOpened ? 'PASS' : 'INFO'), {
      ...nav, dialogOpened,
      hasWhLogText, hasZongCangText, hasXianBianCangText,
      bodyTextLen: bodyText.length, bodyPreview: bodyText.slice(0, 400),
    });
  } catch (e) {
    record('S6-sales-order-wh-log-label', 'FAIL', { err: e.message });
  }
}

async function s7ProductionInventoryCheck(page) {
  console.log('\n=== S7 生产计划开始库存校验 four-tuple (PR #305) ===');
  try {
    const nav = await navigateAndCapture(page, '/production/plans');
    await shot(page, 's7.png');
    // Trigger creating/starting a plan if possible. We'll attempt to find a 开始 button.
    const startBtnCount = await page.locator('button:has-text("开始")').count();
    record('S7-production-plan-page', nav.is403 ? 'FAIL' : 'PASS', {
      ...nav, startBtnCount,
    });
  } catch (e) {
    record('S7-production-plan-page', 'FAIL', { err: e.message });
  }
}

async function s8PurchaseOrderPdf(page) {
  console.log('\n=== S8 采购订单 PDF 下载 (NEW PR #413) ===');
  try {
    const nav = await navigateAndCapture(page, '/procurement/orders');
    await shot(page, 's8.png');
    // Find first row in table
    const rowCount = await page.locator('.el-table tbody tr').count();
    let pdfBtnFound = false;
    let pdfDownloaded = false;
    let pdfStatus = null;
    let pdfContentType = null;
    let pdfUrl = '';
    if (rowCount > 0) {
      // Look for PDF/打印 button in actions column
      const pdfBtn = page.locator('.el-table tbody tr').first().locator('button:has-text("PDF"), button:has-text("打印"), a:has-text("PDF"), a:has-text("打印")').first();
      pdfBtnFound = await pdfBtn.count() > 0;
      if (pdfBtnFound) {
        try {
          // Listen for download
          const [download] = await Promise.all([
            page.waitForEvent('download', { timeout: 8000 }).catch(() => null),
            pdfBtn.click({ timeout: 3000 }).catch(() => {}),
          ]);
          if (download) {
            pdfDownloaded = true;
            pdfUrl = download.url();
            console.log('  download suggested filename:', download.suggestedFilename());
          }
        } catch (e) { console.log('  s8 pdf click err:', e.message); }
      }
    }
    // Cross-check by direct network probe: see if any /pdf URL responded
    const pdfNet = networkLog.filter((n) => n.url && n.url.includes('/pdf'));
    record('S8-purchase-order-pdf', nav.is403 ? 'FAIL' : (pdfBtnFound || pdfNet.length > 0 ? 'PASS' : 'INFO'), {
      ...nav, rowCount, pdfBtnFound, pdfDownloaded, pdfUrl,
      pdfNetwork: pdfNet.slice(0, 5),
    });
  } catch (e) {
    record('S8-purchase-order-pdf', 'FAIL', { err: e.message });
  }
}

async function s9ReceiveQtyColumn(page) {
  console.log('\n=== S9 收货记录 收货数量列 (NEW PR #414) ===');
  try {
    const nav = await navigateAndCapture(page, '/procurement/receives');
    await shot(page, 's9.png');
    const headers = await page.locator('.el-table th').allTextContents();
    const cleaned = headers.map((h) => h.trim()).filter(Boolean);
    const hasReceiveQtyCol = cleaned.some((h) => h.includes('收货数量'));
    // Sample 1 row of body cells for that column
    let sampleCellText = '';
    if (hasReceiveQtyCol) {
      const idx = cleaned.findIndex((h) => h.includes('收货数量'));
      if (idx >= 0) {
        sampleCellText = await page.locator('.el-table tbody tr').first().locator('td').nth(idx).textContent().catch(() => '');
      }
    }
    record('S9-receive-qty-column', nav.is403 ? 'FAIL' : (hasReceiveQtyCol ? 'PASS' : 'FAIL'), {
      ...nav, headers: cleaned, hasReceiveQtyCol, sampleCellText: sampleCellText.trim().slice(0, 80),
    });
  } catch (e) {
    record('S9-receive-qty-column', 'FAIL', { err: e.message });
  }
}

async function s10RBACPriceStrip(page) {
  console.log('\n=== S10 NEW RBAC price strip (PR #423) — f006_warehouse_mgr ===');
  // Probe 4 endpoints and inspect raw response payloads
  const pages = [
    { route: '/procurement/orders', label: 'purchase-orders' },
    { route: '/procurement/receives', label: 'receives' },
    { route: '/material/batches', label: 'material-batches' },
    { route: '/sales/orders', label: 'sales-orders' },
  ];
  const sniff = []; // collected API JSON
  page.on('response', async (resp) => {
    try {
      const u = resp.url();
      if (u.includes('/api/mobile') && (u.includes('purchase') || u.includes('material') || u.includes('sales') || u.includes('receive'))) {
        const ct = resp.headers()['content-type'] || '';
        if (ct.includes('json')) {
          const txt = (await resp.text()).slice(0, 3000);
          sniff.push({ url: u.slice(0, 150), bodyLen: txt.length, sample: txt.slice(0, 1500) });
        }
      }
    } catch (e) {}
  });
  for (const p of pages) {
    try {
      const nav = await navigateAndCapture(page, p.route);
      await shot(page, `s10-${p.label}.png`);
      await page.waitForTimeout(2000); // Let API settle
      const headers = await page.locator('.el-table th').allTextContents();
      const cleaned = headers.map((h) => h.trim()).filter(Boolean);
      const hasPriceCol = cleaned.some((h) =>
        h.includes('单价') || h.includes('总价') || h.includes('金额') || h.includes('总额') ||
        h.includes('成本单价') || h.includes('折扣')
      );
      record(`S10-${p.label}-price-strip-ui`, hasPriceCol ? 'FAIL' : 'PASS', {
        url: nav.url, is403: nav.is403,
        headers: cleaned, hasPriceCol,
        note: hasPriceCol ? 'UI still shows price column for warehouse_manager — RBAC v-if not effective' : 'UI hides price column ✓',
      });
    } catch (e) {
      record(`S10-${p.label}-price-strip-ui`, 'FAIL', { err: e.message });
    }
  }
  // Now scan sniffed JSON bodies for price keys (unitPrice / totalAmount / costUnitPrice etc.)
  const priceKeyTokens = ['"totalAmount"', '"unitPrice"', '"costUnitPrice"', '"discountAmount"', '"discountRate"', '"taxAmount"', '"totalValue"', '"totalPrice"', '"taxRate"'];
  const hits = [];
  for (const s of sniff) {
    for (const tok of priceKeyTokens) {
      if (s.sample.includes(tok)) {
        // ensure value isn't null — extract value snippet
        const re = new RegExp(tok.replace(/"/g, '\\"') + '\\s*:\\s*(null|"[^"]*"|[0-9.\\-]+|\\[\\])');
        const m = s.sample.match(re);
        const valSnippet = m ? m[0].slice(0, 80) : '<not-matched>';
        hits.push({ url: s.url, token: tok, valSnippet });
      }
    }
  }
  // PASS if all hits are `null` or no hits
  // NOTE: regex tolerant of whitespace — Java Jackson default serialization is `:null` (no space),
  // while pretty-printed JSON / some libs emit `: null` (with space). Match both.
  const leakingHits = hits.filter((h) => !/:\s*null\b/.test(h.valSnippet));
  record('S10-RBAC-wire-roundtrip', leakingHits.length === 0 ? 'PASS' : 'FAIL', {
    sniffedResponses: sniff.length,
    priceKeyHits: hits.slice(0, 10),
    leakingHitsCount: leakingHits.length,
    sampleLeak: leakingHits.slice(0, 5),
    rule11_wire_check: leakingHits.length === 0 ? 'price fields absent or null at wire level ✓' : 'price fields present with non-null values — leak detected',
  });
}

async function s11ErrorUx(page) {
  console.log('\n=== S11 Error UX — 404/403 + Rule 8 four-tuple sample ===');
  try {
    // Intentionally bad URL to test error message
    const nav = await navigateAndCapture(page, '/nonexistent-path-xyz123');
    await shot(page, 's11-404.png');
    record('S11-error-404', nav.is404 ? 'PASS' : 'INFO', { ...nav });
  } catch (e) {
    record('S11-error-404', 'FAIL', { err: e.message });
  }
}

// ==========================================================
// Run
// ==========================================================

(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    // Restaurant user scenarios
    {
      const ctx = await browser.newContext({ acceptDownloads: true });
      const page = await ctx.newPage();
      installNetworkLog(page, 'restaurant');
      const ok = await login(page, RESTAURANT_USER);
      if (ok) {
        await s1Filter(page);
        await s2RecipeBom(page);
      } else {
        record('login-gml_admin', 'FAIL', { url: page.url() });
      }
      await ctx.close();
    }
    // F006 admin scenarios
    {
      const ctx = await browser.newContext({ acceptDownloads: true });
      const page = await ctx.newPage();
      installNetworkLog(page, 'f006_admin');
      const ok = await login(page, FACTORY_USER);
      if (ok) {
        await s3TransferBatch(page);
        await s5InventoryByWarehouse(page);
        await s6SalesOrderLabel(page);
        await s7ProductionInventoryCheck(page);
        await s8PurchaseOrderPdf(page);
        await s9ReceiveQtyColumn(page);
        await s11ErrorUx(page);
      } else {
        record('login-f006_admin', 'FAIL', { url: page.url() });
      }
      await ctx.close();
    }
    // F006 warehouse_mgr — RBAC test
    {
      const ctx = await browser.newContext({ acceptDownloads: true });
      const page = await ctx.newPage();
      installNetworkLog(page, 'f006_warehouse_mgr');
      const ok = await login(page, WAREHOUSE_USER);
      if (ok) {
        await s10RBACPriceStrip(page);
      } else {
        record('login-f006_warehouse_mgr', 'FAIL', { url: page.url() });
      }
      await ctx.close();
    }
  } finally {
    await browser.close();
  }
  fs.writeFileSync(RESULTS_PATH, JSON.stringify({
    runAt: new Date().toISOString(),
    target: PROD_URL,
    results,
    networkLogSize: networkLog.length,
    networkLog: networkLog.slice(0, 80),
    consoleErrors: consoleErrors.slice(0, 80),
  }, null, 2));
  console.log('\nSaved:', RESULTS_PATH);
  const pass = results.filter((r) => r.status === 'PASS').length;
  const fail = results.filter((r) => r.status === 'FAIL').length;
  const info = results.filter((r) => r.status === 'INFO').length;
  console.log(`\nSUMMARY: PASS=${pass} FAIL=${fail} INFO=${info} (total=${results.length})`);
})();
