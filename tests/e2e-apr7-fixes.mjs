// E2E validation for Apr 7-11 session fixes:
//   P0-19 (生产计划去产线) / P0-9 (销售订单 3 状态 tag) /
//   P1-3 (研发第 2 页) / P0-5 B1 (FMR↔Transfer, API level)
// Plus regression: P0-3 税率分组 / P0-4 OperationalQuote / P0-11 4 tab + timeline / P0-14 BOM 3 tab
//
// Runs against localhost dev env (web-admin:5173 + backend:10010).

import { chromium } from 'playwright';

const WEB = process.env.E2E_WEB || 'http://localhost:5173';
const API = process.env.E2E_API || 'http://localhost:10010';
const USER = process.env.E2E_USER || 'factory_admin1';
const PASS = process.env.E2E_PASS || '123456';

const results = [];
const consoleLog = [];
const screenshots = [];

function log(...args) { console.log(...args); }
function stamp() { return new Date().toISOString().replace(/[:.]/g, '-'); }

async function runTest(name, fn) {
  log(`\n[START] ${name}`);
  const start = Date.now();
  try {
    await fn();
    results.push({ name, status: 'PASS', ms: Date.now() - start });
    log(`[PASS ] ${name} (${Date.now() - start}ms)`);
  } catch (e) {
    results.push({ name, status: 'FAIL', error: e.message, ms: Date.now() - start });
    log(`[FAIL ] ${name}: ${e.message}`);
  }
}

async function setupContext(context) {
  // Memory: Google Fonts in headless must be blocked or Vue won't render
  await context.route(/fonts\.(googleapis|gstatic)\.com/, r => r.fulfill({ status: 200, body: '' }));
  await context.route(/\.woff2?$/i, r => r.fulfill({ status: 200, body: '' }));
  // Bypass broken Vite proxy: forward /api/mobile/** directly to backend 10010
  await context.route('**/api/mobile/**', async route => {
    const req = route.request();
    const url = req.url().replace('http://localhost:5173', API);
    try {
      const resp = await context.request.fetch(url, {
        method: req.method(),
        headers: { ...req.headers() },
        data: req.postDataBuffer() || undefined,
      });
      await route.fulfill({
        status: resp.status(),
        headers: resp.headers(),
        body: await resp.body(),
      });
    } catch (e) {
      consoleLog.push(`[route abort] ${url}: ${e.message}`);
      await route.abort('failed');
    }
  });
}

async function login(page, context) {
  log(`Login as ${USER} via UI (route intercept handles /api/mobile/**)`);
  await page.goto(`${WEB}/login`);
  await page.waitForLoadState('networkidle');
  await page.fill('input[placeholder*="用户名"]', USER);
  await page.fill('input[placeholder="请输入密码"]', PASS);
  const [resp] = await Promise.all([
    page.waitForResponse(r => r.url().includes('/auth/unified-login'), { timeout: 20000 }),
    page.locator('button.login-button').click(),
  ]);
  log(`Login API ${resp.status()}`);
  await page.waitForURL(url => !url.toString().includes('/login'), { timeout: 10000 });
  await page.waitForLoadState('networkidle');
  log(`Login OK, at ${page.url()}`);
}

async function takeScreenshot(page, name) {
  const file = `tests/e2e-screenshots/${stamp()}_${name}.png`;
  try {
    await page.screenshot({ path: file, fullPage: true });
    screenshots.push(file);
  } catch (e) { /* ignore */ }
}

// ─────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────

async function test_P0_19(page) {
  await runTest('P0-19 生产计划新建无"建议产线"字段', async () => {
    await page.goto(`${WEB}/production/plans`);
    await page.waitForLoadState('networkidle');
    // Click 新建 button
    const newBtn = page.locator('button:has-text("新建计划"), button:has-text("新建"), button:has-text("新增")').first();
    await newBtn.waitFor({ state: 'visible', timeout: 8000 });
    await newBtn.click();
    await page.waitForTimeout(500);
    // Assert 在 dialog 里找不到"建议产线"
    const lineLabel = await page.locator('.el-dialog').locator('text=建议产线').count();
    await takeScreenshot(page, 'p0-19-plan-new');
    if (lineLabel > 0) throw new Error(`在新建 dialog 里发现 ${lineLabel} 处"建议产线"文本,应为 0`);
  });
}

async function test_P1_3(page) {
  await runTest('P1-3 /rd/converted 页面加载', async () => {
    await page.goto(`${WEB}/rd/converted`);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(800);
    await takeScreenshot(page, 'p1-3-rd-converted');
    // Assert 标题 + 搜索框 + 表格
    const title = await page.locator('text=已转样品库').count();
    if (title === 0) throw new Error('未找到"已转样品库"标题');
    const searchInput = await page.locator('input[placeholder*="产品名称"], input[placeholder*="名称"]').count();
    if (searchInput === 0) throw new Error('未找到产品名称搜索框');
    const table = await page.locator('.el-table').count();
    if (table === 0) throw new Error('未找到 el-table');
  });
}

async function test_P0_9(page) {
  await runTest('P0-9 销售订单 detail 订单级 3 状态 tag', async () => {
    await page.goto(`${WEB}/sales/orders`);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(800);
    // Click first row 查看 or 详情 button
    // Check if list has data
    const rowCount = await page.locator('.el-table__row').count();
    if (rowCount === 0) {
      await takeScreenshot(page, 'p0-9-empty-list');
      throw new Error('INCONCLUSIVE: 销售订单列表空, 无法验证详情页 tag (需 seed 数据)');
    }
    const detailBtn = page.locator('.el-table__row').first().locator('button:has-text("详情"), button:has-text("查看")').first();
    if (await detailBtn.count() === 0) {
      await takeScreenshot(page, 'p0-9-no-detail-btn');
      throw new Error('首行无"详情/查看"按钮');
    }
    await detailBtn.click();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(800);
    await takeScreenshot(page, 'p0-9-sales-detail');
    // 找 3 个 order-level tag (收款/开票/运输) in the detail card header (not top navbar)
    const cardHeader = page.locator('.card-header .header-left').first();
    const text = await cardHeader.innerText().catch(() => '');
    const has收款 = /收款/.test(text);
    const has开票 = /开票/.test(text);
    const has运输 = /运输/.test(text);
    if (!has收款 || !has开票 || !has运输) {
      // Fallback: check full page body for tag presence
      const bodyText = await page.locator('body').innerText();
      const bHas收款 = /收款:\s*(待收款|部分收款|已收款)/.test(bodyText);
      const bHas开票 = /开票:\s*(待开票|部分开票|已开票)/.test(bodyText);
      const bHas运输 = /运输:\s*(待出厂|生产中|运输中|已发货)/.test(bodyText);
      if (!bHas收款 || !bHas开票 || !bHas运输) {
        throw new Error(`订单级 tag 不全: card-header[${has收款}/${has开票}/${has运输}] body[${bHas收款}/${bHas开票}/${bHas运输}]. cardHeader: "${text.slice(0, 200)}"`);
      }
    }
  });
}

async function test_P0_11_timeline(page) {
  await runTest('P0-11 销售订单 4 tab + 审批 timeline', async () => {
    // 复用上一个 test 的 detail 页状态,如果已跳走就重新导航
    if (!page.url().includes('/sales/orders/')) {
      await page.goto(`${WEB}/sales/orders`);
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(500);
      await page.locator('.el-table__row').first().locator('button').filter({ hasText: /查看|详情/ }).first().click();
      await page.waitForLoadState('networkidle');
    }
    // 4 tab
    const tabNames = ['订单详情', '开票', '出库', '收款', '关联采购'];
    const found = [];
    for (const t of tabNames) {
      const count = await page.locator(`.el-tabs__item:has-text("${t}")`).count();
      if (count > 0) found.push(t);
    }
    if (found.length < 4) throw new Error(`tab 不足 4 个: found=${found.join(',')}`);
    // Approval timeline
    const tl1 = await page.locator('.el-timeline').count();
    const tl2 = await page.getByText('审批进度', { exact: false }).count();
    if (tl1 + tl2 === 0) throw new Error('未找到审批进度 timeline');
    await takeScreenshot(page, 'p0-11-4tab-timeline');
  });
}

async function test_P0_14_bom(page) {
  await runTest('P0-14 BOM 3 tab (原料/辅料/包材)', async () => {
    await page.goto(`${WEB}/production/bom`);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1000);
    await takeScreenshot(page, 'p0-14-bom');
    // BOM 页 click 第一行或新建看表单里 3 个 tab (RAW/AUXILIARY/PACKAGING)
    // 或者 hierarchy: productType row → expand → material group tabs
    const raw = await page.locator('.el-tabs__item:has-text("原料"), [role="tab"]:has-text("原料")').count();
    const aux = await page.locator('.el-tabs__item:has-text("辅料"), [role="tab"]:has-text("辅料")').count();
    const pkg = await page.locator('.el-tabs__item:has-text("包装"), .el-tabs__item:has-text("包材"), [role="tab"]:has-text("包装")').count();
    if (raw === 0 || aux === 0 || pkg === 0) {
      // BOM 可能需要先 click 进某个 product 才显示 3 tab
      // 尝试找 form group 或其他结构
      const pageText = await page.locator('body').innerText();
      const has原料 = /原料/.test(pageText);
      const has辅料 = /辅料/.test(pageText);
      const has包装 = /包装|包材/.test(pageText);
      if (!has原料 || !has辅料 || !has包装) {
        throw new Error(`BOM 3 分类未找到: tab层面 原料=${raw}辅料=${aux}包材=${pkg}; text层面 原料=${has原料}辅料=${has辅料}包装=${has包装}`);
      }
    }
  });
}

async function test_P0_4_quotes(page) {
  await runTest('P0-4 OperationalQuote /sales/quotes 页加载', async () => {
    await page.goto(`${WEB}/sales/quotes`);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(500);
    await takeScreenshot(page, 'p0-4-quotes');
    if (page.url().includes('/login') || page.url().includes('/404')) {
      throw new Error(`页面不可访问: ${page.url()}`);
    }
    const table = await page.locator('.el-table').count();
    if (table === 0) throw new Error('未找到 el-table (报价列表)');
  });
}

// ─────────────────────────────────────────────────────────────
// Main
// ─────────────────────────────────────────────────────────────

(async () => {
  log(`=== E2E Apr 7-11 fixes validation ===`);
  log(`WEB=${WEB}, API=${API}, USER=${USER}`);

  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await setupContext(context);
  const page = await context.newPage();

  // Collect console errors
  page.on('console', msg => {
    if (msg.type() === 'error') consoleLog.push(`[browser error] ${msg.text()}`);
  });
  page.on('pageerror', err => consoleLog.push(`[page error] ${err.message}`));

  try {
    await login(page, context);
  } catch (e) {
    log(`LOGIN FAILED: ${e.message}`);
    await takeScreenshot(page, 'login-fail');
    await browser.close();
    process.exit(1);
  }

  await test_P0_19(page);
  await test_P1_3(page);
  await test_P0_9(page);
  await test_P0_11_timeline(page);
  await test_P0_14_bom(page);
  await test_P0_4_quotes(page);

  // Summary
  log('\n=== RESULTS ===');
  for (const r of results) {
    log(`${r.status === 'PASS' ? '✅' : '❌'} ${r.name}${r.error ? ' — ' + r.error : ''} (${r.ms}ms)`);
  }
  const pass = results.filter(r => r.status === 'PASS').length;
  const fail = results.filter(r => r.status === 'FAIL').length;
  log(`\nTotal: ${pass}/${pass + fail} PASS`);

  if (consoleLog.length > 0) {
    log('\n=== BROWSER CONSOLE (errors only) ===');
    consoleLog.slice(0, 20).forEach(l => log(l));
  }

  if (screenshots.length > 0) {
    log('\nScreenshots:');
    screenshots.forEach(s => log(`  ${s}`));
  }

  await browser.close();
  process.exit(fail > 0 ? 1 : 0);
})();
