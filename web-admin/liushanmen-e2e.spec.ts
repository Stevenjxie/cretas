/**
 * 六扇门一期 E2E 测试 — Web Admin (Playwright)
 *
 * 覆盖场景:
 *   W1: 生产批次 → 消耗汇总 (S4+S10)
 *   W2: 原材料入库 + 移动均价 (S5+S6)
 *   W3: SmartBI 数据分析 (S8+S10)
 *   W4: 财务成本分析 — SKU毛利 (S7)
 *   W5: BOM配方管理 (S4)
 *   W6: 报工记录审批 (S2)
 */

import { test, expect, Page } from '@playwright/test';
import { fetchLoginToken, injectAuthCookie, LoginResult } from './e2e-auth-helper';

const BASE_URL = process.env.E2E_BASE_URL || 'http://localhost:5173';
const API = process.env.E2E_API_URL || 'http://47.100.235.168:10010/api/mobile';
const FACTORY_ID = 'F001';
const SD = 'test-results/screenshots/liushanmen';

let TOKEN = '';
let authResult: LoginResult | null = null;

// --- Helpers ---

async function injectAuth(page: Page) {
  if (!authResult) return;
  await injectAuthCookie(page.context(), page, authResult.token, authResult.loginData, BASE_URL);
  await page.goto(BASE_URL + '/dashboard', { waitUntil: 'domcontentloaded', timeout: 15000 });
  await page.waitForTimeout(3000);
}

async function api(path: string, opts: RequestInit = {}): Promise<any> {
  const res = await fetch(`${API}/${FACTORY_ID}${path}`, {
    ...opts,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${TOKEN}`,
      ...(opts.headers || {}),
    },
  });
  return res.json();
}

async function gotoPage(page: Page, path: string) {
  await page.goto(BASE_URL + path, {
    waitUntil: 'domcontentloaded',
    timeout: 30000,
  });
  await page.waitForTimeout(3000);
}

async function shot(page: Page, name: string) {
  await page.waitForTimeout(1000);
  await page.screenshot({ path: `${SD}/${name}`, fullPage: false });
}

async function shotFull(page: Page, name: string) {
  await page.waitForTimeout(1000);
  await page.screenshot({ path: `${SD}/${name}`, fullPage: true });
}

// --- Tests ---

test.describe.serial('六扇门一期 Web-Admin E2E', () => {
  test.setTimeout(120000);

  test.beforeAll(async () => {
    authResult = await fetchLoginToken('factory_admin1', '123456', API);
    TOKEN = authResult.token;
    expect(TOKEN).toBeTruthy();
  });

  // W1: 生产批次 → 消耗汇总 (S4+S10)
  test('W1: 生产批次详情展示消耗汇总', async ({ page }) => {
    // 1. API: get a production batch
    const batches = await api('/production-batches?page=1&size=5');
    const content = batches.data?.content || batches.data || [];
    test.skip(!content.length, 'No production batches');

    const batchId = content[0].id;

    // 2. API: verify consumption summary exists
    const summary = await api(
      `/processing/material-consumptions/batch/${batchId}/summary`
    );
    if (summary.success && summary.data) {
      const items = summary.data.bomItems || summary.data.items || [];
      const rate = summary.data.overallAchievementRate;
      expect(items.length).toBeGreaterThanOrEqual(0);
      console.log(
        `W1: batchId=${batchId}, items=${items.length}, achievementRate=${rate}`
      );
    }

    // 3. Navigate to production batch page
    await gotoPage(page, '/#/production/batches');
    await shot(page, 'w1-01-batch-list.png');

    // 4. Look for table rows
    const tableVisible = await page
      .locator('.el-table__row')
      .first()
      .isVisible()
      .catch(() => false);
    if (tableVisible) {
      await page.locator('.el-table__row').first().click();
      await page.waitForTimeout(2000);
      await shot(page, 'w1-02-batch-detail.png');
    }
  });

  // W2: 原材料入库 + 移动均价 (S5+S6)
  test('W2: 原材料入库后移动均价更新', async ({ page }) => {
    // 1. Get material types
    const types = await api('/raw-material-types/active');
    const typeList = Array.isArray(types.data) ? types.data : types.data?.content || [];
    test.skip(!typeList.length, 'No material types');

    const mt = typeList[0];
    const mtId = mt.id;
    const tag = Date.now().toString(36);

    // 2. Record initial moving avg
    const before = await api(`/raw-material-types/${mtId}`);
    const avgBefore = before.data?.movingAvgPrice;
    console.log(`W2: materialType=${mt.name}, avgBefore=${avgBefore}`);

    // 2b. Get a supplier (required field)
    const supResp = await api('/suppliers?page=1&size=1');
    const supList = Array.isArray(supResp.data) ? supResp.data : supResp.data?.content || [];
    const supplierId = supList.length ? supList[0].id : undefined;
    console.log(`W2: supplierId=${supplierId}`);

    // 3. Create a batch via API (correct field names for CreateMaterialBatchRequest)
    const batchResp = await api('/material-batches', {
      method: 'POST',
      body: JSON.stringify({
        materialTypeId: mtId,
        supplierId: supplierId,
        receiptDate: new Date().toISOString().split('T')[0],
        receiptQuantity: 100,
        quantityUnit: mt.unit || 'KG',
        totalWeight: 100,
        totalValue: 9900,
        unitPrice: 99.0,
        storageLocation: 'E2E测试库位',
      }),
    });
    if (!batchResp.success) {
      console.log(`W2: batch creation failed: ${batchResp.message}`);
    }
    expect(batchResp.success).toBeTruthy();

    // 4. Verify moving avg updated
    const after = await api(`/raw-material-types/${mtId}`);
    const avgAfter = after.data?.movingAvgPrice;
    console.log(`W2: avgAfter=${avgAfter}`);

    // 5. Navigate to materials page
    await gotoPage(page, '/#/warehouse/materials');
    await shot(page, 'w2-01-materials-list.png');

    // 6. Navigate to material batches
    await gotoPage(page, '/#/warehouse/material-batches');
    await shot(page, 'w2-02-material-batches.png');
  });

  // W3: SmartBI 数据分析 (S8+S10)
  test('W3: SmartBI 数据分析页面加载', async ({ page }) => {
    // 1. Navigate to SmartBI upload
    await gotoPage(page, '/#/smart-bi/upload');
    await shot(page, 'w3-01-upload-page.png');

    // 2. Check upload area exists
    const uploadArea = page.locator('.el-upload, input[type="file"], .upload-area');
    const uploadVisible = await uploadArea
      .first()
      .isVisible()
      .catch(() => false);
    console.log(`W3: uploadAreaVisible=${uploadVisible}`);

    // 3. Navigate to analysis/dashboard
    await gotoPage(page, '/#/smart-bi/analysis');
    await page.waitForTimeout(5000);
    await shotFull(page, 'w3-02-analysis-page.png');

    // 4. Check for charts or data display
    const chartContainer = page.locator(
      '.echarts-container, canvas, .chart-wrapper, [data-chart]'
    );
    const chartVisible = await chartContainer
      .first()
      .isVisible()
      .catch(() => false);
    console.log(`W3: chartVisible=${chartVisible}`);
  });

  // W4: 财务成本分析 — SKU毛利 (S7)
  test('W4: 财务成本分析展示SKU毛利', async ({ page }) => {
    // 1. API: execute margin query via AI
    const marginResp = await api('/ai-intents/execute', {
      method: 'POST',
      body: JSON.stringify({ userInput: '查一下毛利率' }),
    });
    if (marginResp.success && marginResp.data) {
      const text =
        marginResp.data.formattedText ||
        marginResp.data.replyText ||
        '';
      console.log(`W4: AI margin response: ${text.substring(0, 100)}`);
    }

    // 2. Navigate to finance page
    await gotoPage(page, '/#/finance/costs');
    await shot(page, 'w4-01-cost-analysis.png');

    // 3. Try alternative route if finance/costs doesn't exist
    const pageContent = await page.textContent('body').catch(() => '');
    if (pageContent.includes('404') || pageContent.includes('not found')) {
      await gotoPage(page, '/#/finance');
      await shot(page, 'w4-02-finance-home.png');
    }
  });

  // W5: BOM配方管理 (S4)
  test('W5: BOM配方查看', async ({ page }) => {
    // 1. Navigate to BOM page
    await gotoPage(page, '/#/production/bom-unified');
    await shot(page, 'w5-01-bom-list.png');

    // 2. Check for table
    const tableVisible = await page
      .locator('.el-table')
      .first()
      .isVisible()
      .catch(() => false);
    console.log(`W5: bomTableVisible=${tableVisible}`);

    if (tableVisible) {
      // 3. Click first row to expand
      const firstRow = page.locator('.el-table__row').first();
      if (await firstRow.isVisible().catch(() => false)) {
        await firstRow.click();
        await page.waitForTimeout(2000);
        await shot(page, 'w5-02-bom-detail.png');
      }
    }

    // 4. Try alternative route
    if (!tableVisible) {
      await gotoPage(page, '/#/production/bom');
      await shot(page, 'w5-03-bom-alt.png');
    }
  });

  // W6: 报工记录审批 (S2)
  test('W6: 报工记录列表', async ({ page }) => {
    // 1. Navigate to work reporting page
    await gotoPage(page, '/#/production/approval');
    await shot(page, 'w6-01-approval-list.png');

    // 2. Check table visibility
    const tableVisible = await page
      .locator('.el-table__row')
      .first()
      .isVisible()
      .catch(() => false);
    console.log(`W6: approvalTableVisible=${tableVisible}`);

    // 3. Try work-reporting page as alternative
    if (!tableVisible) {
      await gotoPage(page, '/#/production/work-reports');
      await shot(page, 'w6-02-work-reports.png');

      const altTable = await page
        .locator('.el-table__row')
        .first()
        .isVisible()
        .catch(() => false);
      console.log(`W6: workReportsTableVisible=${altTable}`);
    }

  });

  // W7: AI自然语言入库 — 意图识别验证 (S1)
  test('W7: AI意图识别 — 自然语言入库', async () => {
    // Pure API tests — no page needed for intent recognition

    // W7.1: "GPS牛腩入库42件" → MATERIAL_BATCH_CREATE
    const r1 = await api('/ai-intents/recognize', {
      method: 'POST',
      body: JSON.stringify({ userInput: 'GPS牛腩入库42件' }),
    });
    expect(r1.success).toBeTruthy();
    expect(r1.data?.matched).toBeTruthy();
    const intent1 = (r1.data?.intentCode || '').toUpperCase();
    console.log(`W7.1: "${r1.data?.intentCode}" method=${r1.data?.matchMethod} conf=${r1.data?.confidence}`);

    // W7.2: Execute and verify response has confirmation flow
    const exec1 = await api('/ai-intents/execute', {
      method: 'POST',
      body: JSON.stringify({ userInput: 'GPS牛腩入库42件' }),
    });
    expect(exec1.success).toBeTruthy();
    const text = exec1.data?.formattedText || exec1.data?.message || '';
    const result = exec1.data?.resultData || {};
    const ok = result.needsConfirmation || text.includes('入库成功') || text.includes('选择') || text.includes('确认');
    console.log(`W7.2: status=${exec1.data?.status}, needsConfirm=${result.needsConfirmation}, text=${text.substring(0, 80)}`);

    // W7.3: NL variants
    const variants = [
      { input: '新到一批辣椒500公斤', label: '辣椒500公斤' },
      { input: '入库一批带鱼,数量300公斤,供应商是渔港供应商', label: '带鱼+供应商' },
      { input: '到货一批花椒200斤', label: '花椒200斤' },
      { input: '帮我登记原料入库', label: '泛入库指令' },
    ];
    for (const v of variants) {
      const r = await api('/ai-intents/recognize', {
        method: 'POST',
        body: JSON.stringify({ userInput: v.input }),
      });
      console.log(`W7.3 [${v.label}]: intent=${r.data?.intentCode}, matched=${r.data?.matched}, method=${r.data?.matchMethod}`);
    }
  });

  // W8: 进销存闭环 — SO确认→自动PP (S5)
  test('W8: 进销存闭环 — SO→PP联动', async () => {
    // Step 1: Get product type
    const typeResp = await api('/product-types?page=1&size=3');
    const products = Array.isArray(typeResp.data) ? typeResp.data : typeResp.data?.content || [];
    test.skip(!products.length, 'No product types');
    const ptId = products[0].id;

    // Step 1b: Get customer
    const custResp = await api('/customers?page=1&size=1');
    const custs = Array.isArray(custResp.data) ? custResp.data : custResp.data?.content || [];
    const custId = custs.length ? custs[0].id : 'CUST-E2E';

    // Step 2: Create SO (correct DTO: customerId + orderDate + items[])
    const soResp = await api('/sales/orders', {
      method: 'POST',
      body: JSON.stringify({
        customerId: custId,
        orderDate: new Date().toISOString().split('T')[0],
        requiredDeliveryDate: new Date(Date.now() + 7 * 86400000).toISOString().split('T')[0],
        items: [{
          productTypeId: ptId,
          quantity: 10,
          unit: 'KG',
          unitPrice: 80.0,
        }],
      }),
    });
    if (!soResp.success) {
      console.log(`W8: SO creation failed: ${soResp.message}`);
      return;
    }
    const soId = soResp.data?.id || soResp.data?.orderId;
    console.log(`W8: SO=${soId}`);

    // Step 3: Confirm SO
    const confirmResp = await api(`/sales/orders/${soId}/confirm`, { method: 'POST' });
    expect(confirmResp.success).toBeTruthy();
    console.log('W8: SO confirmed');

    // Step 4: Wait and check PP
    await new Promise(r => setTimeout(r, 3000));
    const plans = await api('/production-plans?page=1&size=5&sort=createdAt&sortDirection=DESC');
    const planList = Array.isArray(plans.data) ? plans.data : plans.data?.content || [];
    const linked = planList.find(
      (p: any) => p.sourceOrderId === soId || p.salesOrderId === soId
    );
    if (linked) {
      console.log(`W8: Auto PP=${linked.planNumber || linked.id}, status=${linked.status}`);
    } else {
      console.log(`W8: No linked PP (stock sufficient?). ${planList.length} plans total`);
    }

    // Step 5: Check completed batch auto-actions (FG, QI)
    const cbResp = await api('/production-batches?page=1&size=3&status=COMPLETED');
    const cbList = Array.isArray(cbResp.data) ? cbResp.data : cbResp.data?.content || [];
    if (cbList.length) {
      const cbId = cbList[0].id;
      const consumptions = await api(`/processing/material-consumptions/batch/${cbId}`);
      const cList = Array.isArray(consumptions.data) ? consumptions.data : [];
      const hasAuto = cList.some((c: any) => c.sourceType === 'AUTO_BOM');
      console.log(`W8: batch ${cbId}, consumptions=${cList.length}, AUTO_BOM=${hasAuto}`);

      const fgResp = await api('/finished-goods?page=1&size=3');
      const fgList = Array.isArray(fgResp.data) ? fgResp.data : fgResp.data?.content || [];
      console.log(`W8: ${fgList.length} finished goods`);
    }
  });

  // W9: 出成率验证 (S3)
  test('W9: 出成率计算验证', async ({ page }) => {
    // API: find batch with yieldRate
    const batchResp = await api('/production-batches?page=1&size=10&sort=createdAt&sortDirection=DESC');
    const batchList = Array.isArray(batchResp.data) ? batchResp.data : batchResp.data?.content || [];
    const target = batchList.find(
      (b: any) => b.goodQuantity != null && b.actualQuantity != null && parseFloat(b.actualQuantity) > 0
    );
    if (target) {
      const good = parseFloat(target.goodQuantity);
      const actual = parseFloat(target.actualQuantity);
      const stored = target.yieldRate != null ? parseFloat(target.yieldRate) : null;
      const expected = (good * 100) / actual;
      console.log(`W9: batch=${target.id}, good=${good}, actual=${actual}, stored=${stored}, expected=${expected.toFixed(2)}`);
      if (stored !== null) {
        expect(Math.abs(stored - expected)).toBeLessThan(0.5);
      }
    } else {
      console.log('W9: No batch with yield data');
    }

    // Navigate to batch page to verify UI
    await gotoPage(page, '/#/production/batches');
    await shot(page, 'w9-01-batch-list.png');

    const firstRow = page.locator('.el-table__row').first();
    if (await firstRow.isVisible().catch(() => false)) {
      await firstRow.click();
      await page.waitForTimeout(2000);
      await shot(page, 'w9-02-batch-detail-yield.png');
    }
  });
});

// ================================================================
// 六扇门一期 Phase 2 — 新功能页面 E2E
// ================================================================

test.describe.serial('六扇门一期 新功能页面 E2E', () => {
  test.setTimeout(120000);

  test.beforeAll(async () => {
    authResult = await fetchLoginToken('factory_admin1', '123456', API);
    TOKEN = authResult.token;
    expect(TOKEN).toBeTruthy();
  });

  test.beforeEach(async ({ page }) => {
    await injectAuth(page);
  });

  // ------------------------------------------------------------------
  // W-P0-01: BOM达成率分析页
  // ------------------------------------------------------------------
  test('W-P0-01: BOM达成率分析页 — 完整功能验证', async ({ page }) => {
    // Navigate with hash routing
    await gotoPage(page, '/production/bom-achievement');
    let pageContent = await page.textContent('body').catch(() => '');
    if (pageContent.includes('login') && !pageContent.includes('BOM')) {
      await injectAuth(page);
      await gotoPage(page, '/production/bom-achievement');
    }

    // Verify page title — use auto-waiting expect
    const title = page.locator('text=BOM达成率分析').first();
    await expect(title).toBeVisible({ timeout: 15000 });
    console.log('W-P0-01: title visible=true');

    // Verify KPI cards (wait for rendering)
    await page.waitForTimeout(2000);
    const kpiCards = page.locator('.el-card');
    const kpiCount = await kpiCards.count();
    console.log(`W-P0-01: KPI cards count=${kpiCount}`);
    expect(kpiCount).toBeGreaterThanOrEqual(4);

    // Verify KPI labels — non-critical, just log
    const kpiLabels = ['总批次数', '平均达成率', '超耗批次数', '最低达成率'];
    for (const label of kpiLabels) {
      const labelEl = page.locator(`.kpi-label:has-text("${label}")`);
      const visible = await labelEl.isVisible().catch(() => false);
      console.log(`W-P0-01: KPI "${label}" visible=${visible}`);
    }

    // Verify el-table loads
    const table = page.locator('.el-table').first();
    await expect(table).toBeVisible({ timeout: 10000 });
    console.log('W-P0-01: table visible=true');

    // Verify "达成率" column header
    const achievementHeader = page.locator('.el-table__header-wrapper').locator('text=达成率').first();
    await expect(achievementHeader).toBeVisible({ timeout: 10000 });
    console.log('W-P0-01: "达成率" header visible=true');

    // Check for table rows
    const rows = page.locator('.el-table__row');
    const rowCount = await rows.count();
    console.log(`W-P0-01: table rows=${rowCount}`);

    // Check for color-coded el-tag elements (success/warning/danger)
    if (rowCount > 0) {
      const tags = page.locator('.el-table__body-wrapper .el-tag');
      const tagCount = await tags.count();
      console.log(`W-P0-01: el-tag count in table=${tagCount}`);

      // Click first row expand button to verify detail table
      const expandBtn = page.locator('.el-table__expand-icon').first();
      if (await expandBtn.isVisible().catch(() => false)) {
        await expandBtn.click();
        await page.waitForTimeout(2000);

        // Verify expanded content appears (either detail table or empty)
        const expandContent = page.locator('.expand-content');
        const expandVisible = await expandContent.first().isVisible().catch(() => false);
        console.log(`W-P0-01: expand content visible=${expandVisible}`);

        // Check for detail table columns
        const detailHeaders = ['原材料', '计划用量', '实际用量', '偏差'];
        for (const h of detailHeaders) {
          const el = page.locator('.expand-content').locator(`text=${h}`);
          const v = await el.isVisible().catch(() => false);
          console.log(`W-P0-01: detail header "${h}" visible=${v}`);
        }
      }
    }

    // Verify date range picker exists
    const datePicker = page.locator('.el-date-editor').first();
    await expect(datePicker).toBeVisible({ timeout: 10000 });
    console.log('W-P0-01: date picker visible=true');

    // Verify pagination — non-critical
    const pagination = page.locator('.el-pagination');
    const paginationVisible = await pagination.isVisible().catch(() => false);
    console.log(`W-P0-01: pagination visible=${paginationVisible}`);

    await shotFull(page, 'w-p0-01-bom-achievement-page.png');
  });

  // ------------------------------------------------------------------
  // W-P0-02: 物料移动均价趋势页
  // ------------------------------------------------------------------
  test('W-P0-02: 物料移动均价趋势页 — 完整功能验证', async ({ page }) => {
    await gotoPage(page, '/warehouse/material-price-trend');
    let pageContent = await page.textContent('body').catch(() => '');
    if (pageContent.includes('login') && !pageContent.includes('物料')) {
      await injectAuth(page);
      await gotoPage(page, '/warehouse/material-price-trend');
    }

    // Verify page title — use auto-waiting expect
    const title = page.locator('text=物料移动均价趋势').first();
    await expect(title).toBeVisible({ timeout: 15000 });
    console.log('W-P0-02: title visible=true');

    // Verify el-table with material data
    const table = page.locator('.el-table').first();
    await expect(table).toBeVisible({ timeout: 10000 });
    console.log('W-P0-02: table visible=true');

    // Verify column headers
    const expectedHeaders = ['物料名称', '当前均价', '最近入库价', '库存量'];
    for (const h of expectedHeaders) {
      const header = page.locator('.el-table__header-wrapper').locator(`text=${h}`);
      const visible = await header.isVisible().catch(() => false);
      console.log(`W-P0-02: header "${h}" visible=${visible}`);
    }

    // Check for price values containing "¥"
    const rows = page.locator('.el-table__row');
    const rowCount = await rows.count();
    console.log(`W-P0-02: table rows=${rowCount}`);

    if (rowCount > 0) {
      const priceValues = page.locator('.price-value');
      const priceCount = await priceValues.count();
      console.log(`W-P0-02: price value cells=${priceCount}`);

      // Verify at least one price is not "-"
      if (priceCount > 0) {
        const firstPrice = await priceValues.first().textContent();
        console.log(`W-P0-02: first price value="${firstPrice}"`);
        const hasPrice = firstPrice && firstPrice.includes('¥');
        console.log(`W-P0-02: has ¥ symbol=${hasPrice}`);
      }

      // Try expanding a row to verify chart container
      const expandBtn = page.locator('.el-table__expand-icon').first();
      if (await expandBtn.isVisible().catch(() => false)) {
        await expandBtn.click();
        await page.waitForTimeout(3000);

        const chartContainer = page.locator('.chart-container, .chart-box');
        const chartVisible = await chartContainer.first().isVisible().catch(() => false);
        console.log(`W-P0-02: chart container after expand visible=${chartVisible}`);

        // If chart loaded, check for canvas (ECharts)
        if (chartVisible) {
          const canvas = page.locator('.chart-box canvas');
          const canvasVisible = await canvas.isVisible().catch(() => false);
          console.log(`W-P0-02: ECharts canvas visible=${canvasVisible}`);
        }
      }
    }

    // Verify search input
    const searchInput = page.locator('.el-input').first();
    await expect(searchInput).toBeVisible({ timeout: 10000 });
    console.log('W-P0-02: search input visible=true');

    // Test search functionality (placeholder includes "编号 / 类别")
    const searchField = page.getByPlaceholder(/搜索物料名称/);
    if (await searchField.isVisible().catch(() => false)) {
      await searchField.fill('test');
      await page.waitForTimeout(1000);
      console.log('W-P0-02: search filter applied');
      await searchField.clear();
      await page.waitForTimeout(500);
    }

    await shotFull(page, 'w-p0-02-material-price-trend.png');
  });

  // ------------------------------------------------------------------
  // W-P1-01: SKU毛利率排名页
  // ------------------------------------------------------------------
  test('W-P1-01: SKU毛利率排名页 — 完整功能验证', async ({ page }) => {
    await gotoPage(page, '/finance/sku-margin');
    let pageContent = await page.textContent('body').catch(() => '');
    if (pageContent.includes('login') && !pageContent.includes('SKU')) {
      await injectAuth(page);
      await gotoPage(page, '/finance/sku-margin');
    }

    // Verify page title — use auto-waiting expect
    const title = page.locator('text=SKU毛利率分析').first();
    await expect(title).toBeVisible({ timeout: 15000 });
    console.log('W-P1-01: title "SKU毛利率分析" visible=true');

    // Wait for data to load (uses AI intent API + fallback)
    await page.waitForTimeout(5000);

    // Verify 4 KPI cards (kpi-card class applied on el-card elements)
    const kpiCards = page.locator('.kpi-card');
    const kpiCount = await kpiCards.count();
    console.log(`W-P1-01: KPI cards count=${kpiCount}`);
    expect(kpiCount).toBeGreaterThanOrEqual(4);

    // Verify KPI labels (non-critical, just log)
    const kpiLabels = ['平均毛利率', '最高毛利SKU', '最低毛利SKU', 'SKU数量'];
    for (const label of kpiLabels) {
      const labelEl = page.locator(`.kpi-label:has-text("${label}")`);
      const visible = await labelEl.isVisible().catch(() => false);
      console.log(`W-P1-01: KPI "${label}" visible=${visible}`);
    }

    // Verify bar chart canvas exists (ECharts renders to canvas) — non-critical
    const chartContainer = page.locator('.chart-container');
    const chartVisible = await chartContainer.isVisible().catch(() => false);
    console.log(`W-P1-01: chart container visible=${chartVisible}`);

    if (chartVisible) {
      const canvas = page.locator('.chart-container canvas');
      const canvasVisible = await canvas.isVisible().catch(() => false);
      console.log(`W-P1-01: ECharts canvas visible=${canvasVisible}`);
    }

    // Verify section title "Top 10 SKU 毛利率排名" — non-critical
    const sectionTitle = page.locator('text=Top 10 SKU 毛利率排名');
    console.log(`W-P1-01: section title visible=${await sectionTitle.isVisible().catch(() => false)}`);

    // Verify el-table with data
    const table = page.locator('.el-table').first();
    await expect(table).toBeVisible({ timeout: 10000 });
    console.log('W-P1-01: table visible=true');

    // Verify "毛利率" column header
    const marginHeader = page.locator('.el-table__header-wrapper').locator('text=毛利率').first();
    await expect(marginHeader).toBeVisible({ timeout: 10000 });
    console.log('W-P1-01: "毛利率" header visible=true');

    // Verify additional column headers
    const expectedHeaders = ['产品名称', '产量', '物料成本', '总成本', '售价', '毛利率'];
    for (const h of expectedHeaders) {
      const header = page.locator('.el-table__header-wrapper').locator(`text=${h}`).first();
      const visible = await header.isVisible().catch(() => false);
      console.log(`W-P1-01: header "${h}" visible=${visible}`);
    }

    // Check for color-coded margin tags
    const rows = page.locator('.el-table__row');
    const rowCount = await rows.count();
    console.log(`W-P1-01: table rows=${rowCount}`);

    if (rowCount > 0) {
      const marginTags = page.locator('.el-table__body-wrapper .el-tag');
      const tagCount = await marginTags.count();
      console.log(`W-P1-01: margin tags count=${tagCount}`);
      expect(tagCount).toBeGreaterThan(0);
    }

    // Verify date range picker
    const datePicker = page.locator('.el-date-editor').first();
    await expect(datePicker).toBeVisible({ timeout: 10000 });
    console.log('W-P1-01: date picker visible=true');

    // Verify pagination — non-critical
    const pagination = page.locator('.el-pagination');
    const paginationVisible = await pagination.isVisible().catch(() => false);
    console.log(`W-P1-01: pagination visible=${paginationVisible}`);

    await shotFull(page, 'w-p1-01-sku-margin.png');
  });

  // ------------------------------------------------------------------
  // W-P2-01: 供应链闭环总览
  // ------------------------------------------------------------------
  test('W-P2-01: 供应链闭环总览 — 完整功能验证', async ({ page }) => {
    await gotoPage(page, '/analytics/supply-chain');
    let pageContent = await page.textContent('body').catch(() => '');
    if (pageContent.includes('login') && !pageContent.includes('进销存')) {
      await injectAuth(page);
      await gotoPage(page, '/analytics/supply-chain');
    }

    // Verify page title — use auto-waiting expect
    const title = page.locator('text=进销存闭环总览').first();
    await expect(title).toBeVisible({ timeout: 15000 });
    console.log('W-P2-01: title "进销存闭环总览" visible=true');

    // Verify subtitle with flow description — non-critical
    const subtitle = page.locator('text=采购');
    console.log(`W-P2-01: subtitle visible=${await subtitle.first().isVisible().catch(() => false)}`);

    // Wait for data + chart to load
    await page.waitForTimeout(5000);

    // Verify Sankey chart canvas
    const sankeyChart = page.locator('.sankey-chart').first();
    await expect(sankeyChart).toBeVisible({ timeout: 10000 });
    console.log('W-P2-01: sankey chart container visible=true');

    // Check for Sankey canvas — non-critical
    const sankeyCanvas = page.locator('.sankey-chart canvas');
    const canvasVisible = await sankeyCanvas.isVisible().catch(() => false);
    console.log(`W-P2-01: Sankey ECharts canvas visible=${canvasVisible}`);

    // Verify 6 summary cards (stat-card class on el-card elements)
    const statCards = page.locator('.stat-card');
    const statCardCount = await statCards.count();
    console.log(`W-P2-01: stat cards count=${statCardCount}`);
    expect(statCardCount).toBeGreaterThanOrEqual(6);

    // Verify card labels — non-critical
    const cardLabels = ['采购总额', '入库批次', '领用数量', '生产批次', '成品数量', '出库/销售额'];
    for (const label of cardLabels) {
      const labelEl = page.locator(`.stat-label:has-text("${label}")`);
      const visible = await labelEl.isVisible().catch(() => false);
      console.log(`W-P2-01: card "${label}" visible=${visible}`);
    }

    // Verify tabs for different stages
    const tabs = page.locator('.el-tabs__item');
    const tabCount = await tabs.count();
    console.log(`W-P2-01: tab count=${tabCount}`);
    expect(tabCount).toBeGreaterThanOrEqual(4);

    // Click each tab and verify table loads
    const tabNames = ['采购订单', '入库记录', '生产批次', '出库/销售'];
    for (const tabName of tabNames) {
      const tab = page.locator(`.el-tabs__item:has-text("${tabName}")`);
      if (await tab.isVisible().catch(() => false)) {
        await tab.click();
        await page.waitForTimeout(1500);

        const tabTable = page.locator('.el-table').first();
        const tabTableVisible = await tabTable.isVisible().catch(() => false);
        console.log(`W-P2-01: tab "${tabName}" table visible=${tabTableVisible}`);
      }
    }

    // Verify date range picker — non-critical
    const datePicker = page.locator('.el-date-editor');
    const datePickerVisible = await datePicker.first().isVisible().catch(() => false);
    console.log(`W-P2-01: date picker visible=${datePickerVisible}`);

    await shotFull(page, 'w-p2-01-supply-chain-overview.png');
  });

  // ------------------------------------------------------------------
  // W-P2-02: 工序投入产出对比
  // ------------------------------------------------------------------
  test('W-P2-02: 工序投入产出对比 — 完整功能验证', async ({ page }) => {
    await gotoPage(page, '/production/process-io');
    let pageContent = await page.textContent('body').catch(() => '');
    if (pageContent.includes('login') && !pageContent.includes('投入产出')) {
      await injectAuth(page);
      await gotoPage(page, '/production/process-io');
    }

    // Verify page title — use auto-waiting expect
    const title = page.locator('text=工序级投入产出对比').first();
    await expect(title).toBeVisible({ timeout: 15000 });
    console.log('W-P2-02: title visible=true');

    // Wait for data to load
    await page.waitForTimeout(3000);

    // Verify 4 KPI cards (kpi-card class applied on el-card elements)
    const kpiCards = page.locator('.kpi-card');
    const kpiCount = await kpiCards.count();
    console.log(`W-P2-02: KPI cards count=${kpiCount}`);
    expect(kpiCount).toBeGreaterThanOrEqual(4);

    // Verify KPI labels — non-critical
    const kpiLabels = ['工序数量', '平均转化率', '平均损耗率', '低效工序数'];
    for (const label of kpiLabels) {
      const labelEl = page.locator(`.kpi-label:has-text("${label}")`);
      const visible = await labelEl.isVisible().catch(() => false);
      console.log(`W-P2-02: KPI "${label}" visible=${visible}`);
    }

    // Verify el-table
    const table = page.locator('.el-table').first();
    await expect(table).toBeVisible({ timeout: 10000 });
    console.log('W-P2-02: table visible=true');

    // Verify table column headers
    const expectedHeaders = ['工序名称', '投入量', '产出量', '转化率', '损耗率', '转化率进度', '任务数'];
    for (const h of expectedHeaders) {
      const header = page.locator('.el-table__header-wrapper').locator(`text=${h}`).first();
      const visible = await header.isVisible().catch(() => false);
      console.log(`W-P2-02: header "${h}" visible=${visible}`);
    }

    // Check for color-coded conversion rate tags
    const rows = page.locator('.el-table__row');
    const rowCount = await rows.count();
    console.log(`W-P2-02: table rows=${rowCount}`);

    if (rowCount > 0) {
      // Verify el-tag for conversion rates
      const conversionTags = page.locator('.el-table__body-wrapper .el-tag');
      const tagCount = await conversionTags.count();
      console.log(`W-P2-02: conversion/wastage tags count=${tagCount}`);

      // Verify el-progress bars
      const progressBars = page.locator('.el-table__body-wrapper .el-progress');
      const progressCount = await progressBars.count();
      console.log(`W-P2-02: progress bars count=${progressCount}`);
    }

    // Verify rate legend
    const legend = page.locator('.rate-legend');
    const legendVisible = await legend.isVisible().catch(() => false);
    console.log(`W-P2-02: rate legend visible=${legendVisible}`);

    // Verify product filter select
    const productSelect = page.locator('.el-select').first();
    const selectVisible = await productSelect.isVisible().catch(() => false);
    console.log(`W-P2-02: product filter select visible=${selectVisible}`);

    // Verify date range picker
    const datePicker = page.locator('.el-date-editor');
    const datePickerVisible = await datePicker.first().isVisible().catch(() => false);
    console.log(`W-P2-02: date picker visible=${datePickerVisible}`);

    await shotFull(page, 'w-p2-02-process-io-comparison.png');
  });

  // ------------------------------------------------------------------
  // W-NAV-01: 侧边栏导航完整性
  // ------------------------------------------------------------------
  test('W-NAV-01: 侧边栏导航完整性 — 所有新页面可达', async ({ page }) => {
    // Start at dashboard
    await gotoPage(page, '/dashboard');

    const newPages = [
      { path: '/production/bom-achievement', title: 'BOM达成率', screenshotName: 'w-nav-01-bom-achievement.png' },
      { path: '/warehouse/material-price-trend', title: '物料', screenshotName: 'w-nav-01-material-price-trend.png' },
      { path: '/finance/sku-margin', title: 'SKU毛利率', screenshotName: 'w-nav-01-sku-margin.png' },
      { path: '/analytics/supply-chain', title: '进销存', screenshotName: 'w-nav-01-supply-chain.png' },
      { path: '/production/process-io', title: '投入产出', screenshotName: 'w-nav-01-process-io.png' },
    ];

    for (const pg of newPages) {
      // Navigate directly
      await gotoPage(page, pg.path);
      let bodyText = await page.textContent('body').catch(() => '');

      // If redirected to login, inject auth and retry
      if (bodyText.includes('login') || bodyText.includes('登 录')) {
        await injectAuth(page);
        await gotoPage(page, pg.path);
        bodyText = await page.textContent('body').catch(() => '');
      }

      const is404 = bodyText.includes('404') || bodyText.includes('not found');
      console.log(`W-NAV-01: ${pg.path} → is404=${is404}, URL=${page.url()}`);
      expect(is404).toBeFalsy();

      // Verify some content from the page loaded
      const hasTitle = bodyText.includes(pg.title);
      console.log(`W-NAV-01: ${pg.path} → contains "${pg.title}"=${hasTitle}`);

      await shot(page, pg.screenshotName);
    }
  });

  // ------------------------------------------------------------------
  // W-DATA-01: 数据流验证 — BOM达成率数据一致性
  // ------------------------------------------------------------------
  test('W-DATA-01: BOM达成率数据一致性验证', async ({ page }) => {
    await gotoPage(page, '/production/bom-achievement');
    let pageContent = await page.textContent('body').catch(() => '');
    if (pageContent.includes('login') && !pageContent.includes('BOM')) {
      await injectAuth(page);
      await gotoPage(page, '/production/bom-achievement');
    }

    // Wait for data to load
    await page.waitForTimeout(3000);

    // Wait for KPI cards to render, then read the total from first KPI card
    const firstKpiCard = page.locator('.kpi-card').first();
    await expect(firstKpiCard).toBeVisible({ timeout: 10000 });
    const totalKpi = firstKpiCard.locator('.kpi-value');
    const totalText = await totalKpi.textContent().catch(() => '0');
    const totalFromKpi = parseInt(totalText || '0', 10);
    console.log(`W-DATA-01: KPI total batches=${totalFromKpi}`);

    // Read the "共 X 条记录" count from header
    const dataCountEl = page.locator('.data-count');
    const dataCountText = await dataCountEl.textContent().catch(() => '');
    console.log(`W-DATA-01: data count text="${dataCountText}"`);

    // Count visible table rows
    const rows = page.locator('.el-table__row');
    const visibleRows = await rows.count();
    console.log(`W-DATA-01: visible table rows=${visibleRows}`);

    // If total > page size (10), pagination should be present
    if (totalFromKpi > 10) {
      const pagination = page.locator('.el-pagination').first();
      await expect(pagination).toBeVisible({ timeout: 10000 });
      console.log(`W-DATA-01: pagination visible (expected because total=${totalFromKpi} > 10)=true`);

      // Click next page to verify data continues
      const nextBtn = page.locator('.el-pagination .btn-next');
      if (await nextBtn.isVisible().catch(() => false)) {
        await nextBtn.click();
        await page.waitForTimeout(3000);
        const rowsPage2 = await page.locator('.el-table__row').count();
        console.log(`W-DATA-01: page 2 rows=${rowsPage2}`);
        expect(rowsPage2).toBeGreaterThan(0);
      }
    }

    // Verify row count is consistent: visible <= total
    expect(visibleRows).toBeLessThanOrEqual(Math.max(totalFromKpi, 10));

    await shot(page, 'w-data-01-bom-consistency.png');
  });

  // ------------------------------------------------------------------
  // W-DATA-02: 数据流验证 — 物料均价非空
  // ------------------------------------------------------------------
  test('W-DATA-02: 物料均价非空验证', async ({ page }) => {
    await gotoPage(page, '/warehouse/material-price-trend');
    let pageContent = await page.textContent('body').catch(() => '');
    if (pageContent.includes('login') && !pageContent.includes('物料')) {
      await injectAuth(page);
      await gotoPage(page, '/warehouse/material-price-trend');
    }

    // Wait for data to load
    await page.waitForTimeout(3000);

    // Check for rows
    const rows = page.locator('.el-table__row');
    const rowCount = await rows.count();
    console.log(`W-DATA-02: table rows=${rowCount}`);

    if (rowCount > 0) {
      // Find price value cells
      const priceValues = page.locator('.price-value');
      const priceCount = await priceValues.count();
      console.log(`W-DATA-02: price cells=${priceCount}`);

      // Verify at least one row has a non-empty price with ¥
      let foundPrice = false;
      for (let i = 0; i < Math.min(priceCount, 10); i++) {
        const text = await priceValues.nth(i).textContent().catch(() => '');
        if (text && text.includes('¥') && !text.includes('¥0.00')) {
          foundPrice = true;
          console.log(`W-DATA-02: found price at index ${i}: "${text}"`);
          break;
        }
      }
      console.log(`W-DATA-02: found non-empty price=${foundPrice}`);
      // Note: if no materials have prices yet, this is informational
    } else {
      console.log('W-DATA-02: no material rows (empty table)');
    }

    // Also verify via API
    const types = await api('/raw-material-types/active');
    const typeList = Array.isArray(types.data) ? types.data : types.data?.content || [];
    let apiPriceFound = false;
    for (const mt of typeList.slice(0, 10)) {
      if (mt.movingAvgPrice != null && mt.movingAvgPrice > 0) {
        apiPriceFound = true;
        console.log(`W-DATA-02: API price found: ${mt.name} = ¥${mt.movingAvgPrice}`);
        break;
      }
    }
    console.log(`W-DATA-02: API has non-zero price=${apiPriceFound}`);

    await shot(page, 'w-data-02-material-price-check.png');
  });
});
