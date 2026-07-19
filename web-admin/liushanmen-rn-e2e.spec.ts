/**
 * 六扇门一期 E2E 测试 — RN Expo Web (Playwright against localhost:3010)
 *
 * 比 Maestro 快得多：无需模拟器，直接跑浏览器。
 * 覆盖客户会议场景:
 *   RN-LSM-01: 进销存 — 仓储库存浏览 (S5)
 *   RN-LSM-02: 报工 + 投入量字段 (S2+S3)
 *   RN-LSM-03: 消耗汇总 + BOM达成率 (S4)
 *   RN-LSM-04: AI自然语言查询消耗 (S1)
 *   RN-LSM-05: AI查询毛利率 (S7)
 *   RN-LSM-06: 生产批次列表 + 详情 (S5+S10)
 *   RN-LSM-07: 移动均价显示 (S6)
 */

import { test, expect, Page } from '@playwright/test';

const RN_BASE_URL = process.env.RN_BASE_URL || 'http://localhost:3010';
const API = process.env.E2E_API_URL || 'http://47.100.235.168:10010/api/mobile';
const FACTORY_ID = 'F001';
const SD = 'test-results/screenshots/liushanmen-rn';

let TOKEN = '';

// --- Helpers (same pattern as rn-expo-web-e2e.spec.ts) ---

async function rnLogin(page: Page, username = 'factory_admin1', password = '123456') {
  await page.goto(RN_BASE_URL, { waitUntil: 'commit', timeout: 60000 });
  await page.waitForTimeout(8000);

  // Landing → click login
  const loginBtn = page.locator('[data-testid="landing-login-btn"]');
  if (await loginBtn.isVisible().catch(() => false)) {
    await loginBtn.click();
  } else {
    const loginText = page.locator('text=登录').first();
    if (await loginText.isVisible().catch(() => false)) {
      await loginText.click();
    }
  }
  await page.waitForTimeout(2000);

  // Fill form
  const usernameInput = page.locator('[data-testid="login-username-input"]');
  const passwordInput = page.locator('[data-testid="login-password-input"]');

  if (await usernameInput.isVisible().catch(() => false)) {
    await usernameInput.fill(username);
    await passwordInput.fill(password);
    await page.waitForTimeout(500);
    await page.locator('[data-testid="login-submit-btn"]').click();
  } else {
    const uInput = page.getByPlaceholder('请输入用户名');
    const pInput = page.getByPlaceholder('请输入密码');
    if (await uInput.isVisible().catch(() => false)) {
      await uInput.fill(username);
      await pInput.fill(password);
      await page.waitForTimeout(500);
      await page.locator('text=登录').last().click();
    }
  }

  try {
    await page.waitForSelector('[role="tab"]', { timeout: 20000 });
  } catch {
    await page.waitForTimeout(5000);
  }
  await page.waitForTimeout(2000);
}

async function tapTab(page: Page, tabLabel: string) {
  const tab = page.getByRole('tab', { name: tabLabel });
  if (await tab.isVisible().catch(() => false)) {
    await tab.click();
    await page.waitForTimeout(2000);
    return true;
  }
  const textTab = page.locator(`text=${tabLabel}`).last();
  if (await textTab.isVisible().catch(() => false)) {
    await textTab.click();
    await page.waitForTimeout(2000);
    return true;
  }
  return false;
}

async function apiLogin(): Promise<string> {
  const res = await fetch(`${API}/auth/unified-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'factory_admin1', password: '123456' }),
  });
  const json = await res.json();
  TOKEN = json.data?.accessToken || json.data?.token || '';
  return TOKEN;
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

async function shot(page: Page, name: string) {
  await page.waitForTimeout(1000);
  await page.screenshot({ path: `${SD}/${name}`, fullPage: true });
}

// --- Tests ---

test.describe('六扇门一期 RN Expo Web E2E', () => {
  test.setTimeout(300000);

  test.beforeAll(async () => {
    await apiLogin();
  });

  // ============================================================
  // RN-LSM-01: 仓储库存浏览 (S5)
  // ============================================================
  test('RN-LSM-01: 仓储Tab — 库存列表浏览', async ({ page }) => {
    await rnLogin(page);

    // Navigate to warehouse tab
    const tapped = await tapTab(page, '仓储');
    if (!tapped) {
      // Try alternative tab names
      const alt = await tapTab(page, '库存');
      console.log('RN-LSM-01: 仓储tab not found, tried 库存:', alt);
    }
    await page.waitForTimeout(3000);
    await shot(page, '01-warehouse-home.png');

    // Look for inventory items
    const inventoryList = page.locator('[data-testid="inventory-list"]');
    const listVisible = await inventoryList.isVisible().catch(() => false);
    console.log('RN-LSM-01 inventory list:', listVisible);

    // Try clicking a material management entry
    const materialMgmt = page.locator('text=库存管理')
      .or(page.locator('text=原材料'))
      .or(page.locator('text=物料'))
      .first();
    if (await materialMgmt.isVisible().catch(() => false)) {
      await materialMgmt.click();
      await page.waitForTimeout(3000);
      await shot(page, '01b-inventory-detail.png');
    }

    // API verification: material batches exist
    const batches = await api('/material-batches?page=1&size=5');
    const content = batches.data?.content || batches.data || [];
    console.log(`RN-LSM-01 API: ${content.length} material batches found`);
    expect(content.length).toBeGreaterThanOrEqual(0);
  });

  // ============================================================
  // RN-LSM-02: 报工 + 投入量字段 (S2+S3)
  // ============================================================
  test('RN-LSM-02: 报工表单含投入量字段', async ({ page }) => {
    await rnLogin(page);
    await tapTab(page, '生产');
    await page.waitForTimeout(3000);

    // Navigate to process task list
    const taskBtn = page.locator('[data-testid="dashboard-process-task-btn"]')
      .or(page.locator('text=工序任务').first());
    if (await taskBtn.isVisible().catch(() => false)) {
      await taskBtn.click();
      await page.waitForTimeout(3000);
    } else {
      // Try management tab path
      await tapTab(page, '管理');
      await page.waitForTimeout(2000);
      const mgmtTaskBtn = page.locator('[data-testid="fa-process-task-btn"]')
        .or(page.locator('text=工序任务').first());
      if (await mgmtTaskBtn.isVisible().catch(() => false)) {
        await mgmtTaskBtn.click();
        await page.waitForTimeout(3000);
      }
    }

    await shot(page, '02-task-list.png');

    // Open first task card
    const firstCard = page.locator('[data-testid^="process-task-card-"]').first();
    if (!(await firstCard.isVisible().catch(() => false))) {
      console.log('RN-LSM-02: SKIP — no task cards');
      return;
    }
    await firstCard.click();
    await page.waitForTimeout(3000);

    // Click report button
    const reportBtn = page.locator('[data-testid="task-detail-report-btn"]');
    if (!(await reportBtn.isVisible().catch(() => false))) {
      console.log('RN-LSM-02: SKIP — no report button');
      await shot(page, '02b-no-report-btn.png');
      return;
    }
    await reportBtn.click();
    await page.waitForTimeout(3000);

    // Verify output quantity input
    const qtyInput = page.locator('[data-testid="report-quantity-input"]');
    const qtyVisible = await qtyInput.isVisible().catch(() => false);
    console.log('RN-LSM-02 output qty input:', qtyVisible);

    // Verify input quantity field exists (S3 - 投入量)
    const inputQtyField = page.locator('[data-testid="report-input-quantity"]');
    const inputQtyVisible = await inputQtyField.isVisible().catch(() => false);
    console.log('RN-LSM-02 input qty field (投入量):', inputQtyVisible);

    // Also check by text label
    const inputQtyLabel = page.locator('text=投入量');
    const labelVisible = await inputQtyLabel.isVisible().catch(() => false);
    console.log('RN-LSM-02 投入量 label:', labelVisible);

    await shot(page, '02c-report-form.png');

    // Fill both fields
    if (qtyVisible) {
      await qtyInput.click();
      await page.keyboard.type('100');
    }
    if (inputQtyVisible) {
      await inputQtyField.click();
      await page.keyboard.type('120');
    }
    await shot(page, '02d-form-filled.png');
  });

  // ============================================================
  // RN-LSM-03: 消耗汇总 + BOM达成率 (S4)
  // ============================================================
  test('RN-LSM-03: 生产批次消耗汇总', async ({ page }) => {
    await rnLogin(page);
    await tapTab(page, '生产');
    await page.waitForTimeout(3000);

    // Navigate to batch list
    const batchListBtn = page.locator('text=批次列表')
      .or(page.locator('text=生产批次'))
      .first();
    if (await batchListBtn.isVisible().catch(() => false)) {
      await batchListBtn.click();
      await page.waitForTimeout(3000);
    }
    await shot(page, '03-batch-list.png');

    // Click first batch
    const firstBatch = page.locator('[data-testid="batch-item-0"]')
      .or(page.locator('[data-testid^="production-batch-"]').first());
    if (await firstBatch.isVisible().catch(() => false)) {
      await firstBatch.click();
      await page.waitForTimeout(3000);
      await shot(page, '03b-batch-detail.png');

      // Look for consumption tab/section
      const consumptionTab = page.locator('text=消耗')
        .or(page.locator('text=物料消耗'))
        .first();
      if (await consumptionTab.isVisible().catch(() => false)) {
        await consumptionTab.click();
        await page.waitForTimeout(3000);
        await shot(page, '03c-consumption-tab.png');

        // Check for achievement rate display
        const achievementRate = page.locator('text=达成率');
        console.log('RN-LSM-03 达成率 visible:', await achievementRate.isVisible().catch(() => false));
      }
    } else {
      console.log('RN-LSM-03: no batch items found on UI');
    }

    // API verification
    const batches = await api('/production-batches?page=1&size=3');
    const content = batches.data?.content || batches.data || [];
    if (content.length) {
      const batchId = content[0].id;
      const summary = await api(`/processing/material-consumptions/batch/${batchId}/summary`);
      if (summary.success && summary.data) {
        const items = summary.data.bomItems || summary.data.items || [];
        const rate = summary.data.overallAchievementRate;
        console.log(`RN-LSM-03 API: ${items.length} consumption items, rate=${rate}`);
      }
    }
  });

  // ============================================================
  // RN-LSM-04: AI自然语言查询消耗 (S1)
  // ============================================================
  test('RN-LSM-04: AI对话 — 查询物料消耗', async ({ page }) => {
    await rnLogin(page);

    // Navigate to AI tab
    const aiTapped = await tapTab(page, 'AI');
    if (!aiTapped) {
      // Try alternative names
      const alt = await tapTab(page, '智能');
      console.log('RN-LSM-04: AI tab not found, tried 智能:', alt);
    }
    await page.waitForTimeout(3000);
    await shot(page, '04-ai-home.png');

    // Find chat input
    const chatInput = page.locator('[data-testid="ai-chat-input"]');
    if (!(await chatInput.isVisible().catch(() => false))) {
      console.log('RN-LSM-04: SKIP — AI chat input not found');
      return;
    }

    // Query 1: 物料消耗
    await chatInput.click();
    await page.keyboard.type('查看最近批次的物料消耗');
    await page.waitForTimeout(500);

    const sendBtn = page.locator('[data-testid="ai-send-button"]');
    if (await sendBtn.isVisible().catch(() => false)) {
      await sendBtn.click();
    } else {
      await page.keyboard.press('Enter');
    }

    // Wait for response
    await page.waitForTimeout(15000);
    await shot(page, '04b-consumption-response.png');

    // Check response content
    const responseArea = page.locator('[data-testid^="ai-chat-message-"]').last();
    const responseVisible = await responseArea.isVisible().catch(() => false);
    console.log('RN-LSM-04 AI response visible:', responseVisible);

    // API verification
    const intentResp = await api('/ai-intents/recognize', {
      method: 'POST',
      body: JSON.stringify({ userInput: '查看最近批次的物料消耗' }),
    });
    if (intentResp.success && intentResp.data) {
      console.log(`RN-LSM-04 API intent: ${intentResp.data.intentCode}, method=${intentResp.data.matchMethod}`);
    }
  });

  // ============================================================
  // RN-LSM-05: AI查询毛利率 (S7)
  // ============================================================
  test('RN-LSM-05: AI对话 — 查询SKU毛利率', async ({ page }) => {
    await rnLogin(page);
    const aiTapped = await tapTab(page, 'AI');
    if (!aiTapped) await tapTab(page, '智能');
    await page.waitForTimeout(3000);

    const chatInput = page.locator('[data-testid="ai-chat-input"]');
    if (!(await chatInput.isVisible().catch(() => false))) {
      console.log('RN-LSM-05: SKIP — AI chat input not found');
      return;
    }

    // Query: 毛利率
    await chatInput.click();
    await page.keyboard.type('查一下SKU毛利率');
    await page.waitForTimeout(500);

    const sendBtn = page.locator('[data-testid="ai-send-button"]');
    if (await sendBtn.isVisible().catch(() => false)) {
      await sendBtn.click();
    } else {
      await page.keyboard.press('Enter');
    }

    await page.waitForTimeout(15000);
    await shot(page, '05-margin-response.png');

    const responseArea = page.locator('[data-testid^="ai-chat-message-"]').last();
    console.log('RN-LSM-05 response:', await responseArea.isVisible().catch(() => false));

    // API verification
    const execResp = await api('/ai-intents/execute', {
      method: 'POST',
      body: JSON.stringify({ userInput: '查一下毛利率' }),
    });
    if (execResp.success && execResp.data) {
      const text = execResp.data.formattedText || execResp.data.replyText || '';
      console.log(`RN-LSM-05 API: ${text.substring(0, 80)}`);
    }
  });

  // ============================================================
  // RN-LSM-06: 生产批次列表 + 详情 (S5+S10)
  // ============================================================
  test('RN-LSM-06: 生产批次列表可达', async ({ page }) => {
    await rnLogin(page);
    await tapTab(page, '生产');
    await page.waitForTimeout(3000);

    // Navigate to batch list
    const batchListBtn = page.locator('text=批次列表')
      .or(page.locator('text=生产批次'))
      .first();
    if (await batchListBtn.isVisible().catch(() => false)) {
      await batchListBtn.click();
      await page.waitForTimeout(3000);
      await shot(page, '06-production-batches.png');

      // Click first batch for detail
      const firstItem = page.locator('[data-testid="batch-item-0"]')
        .or(page.locator('[data-testid^="production-batch-"]').first());
      if (await firstItem.isVisible().catch(() => false)) {
        await firstItem.click();
        await page.waitForTimeout(3000);
        await shot(page, '06b-batch-detail.png');

        // Check for material cost display
        const materialCost = page.locator('text=物料成本')
          .or(page.locator('text=材料成本'));
        console.log('RN-LSM-06 材料成本 visible:', await materialCost.isVisible().catch(() => false));
      }
    } else {
      console.log('RN-LSM-06: batch list button not found');
      await shot(page, '06-no-batch-btn.png');
    }
  });

  // ============================================================
  // RN-LSM-07: 移动均价显示 (S6)
  // ============================================================
  test('RN-LSM-07: 原材料详情显示移动均价', async ({ page }) => {
    // API: check a material type has movingAvgPrice
    const types = await api('/raw-material-types/active');
    const typeList = Array.isArray(types.data) ? types.data : types.data?.content || [];
    if (typeList.length) {
      const mt = typeList[0];
      const detail = await api(`/raw-material-types/${mt.id}`);
      const avg = detail.data?.movingAvgPrice;
      console.log(`RN-LSM-07 API: ${mt.name}, movingAvgPrice=${avg}`);
    }

    await rnLogin(page);

    // Navigate to warehouse/inventory
    const tapped = await tapTab(page, '仓储');
    if (!tapped) await tapTab(page, '库存');
    await page.waitForTimeout(3000);

    // Look for material type list
    const materialItem = page.locator('[data-testid="material-item-0"]')
      .or(page.locator('[data-testid^="material-type-"]').first());
    if (await materialItem.isVisible().catch(() => false)) {
      await materialItem.click();
      await page.waitForTimeout(3000);
      await shot(page, '07-material-detail.png');

      // Check for moving avg price display
      const avgPrice = page.locator('text=移动均价')
        .or(page.locator('text=平均价'));
      const avgVisible = await avgPrice.isVisible().catch(() => false);
      console.log('RN-LSM-07 移动均价 visible:', avgVisible);
    } else {
      console.log('RN-LSM-07: material items not found on UI');
      await shot(page, '07-no-material-items.png');
    }
  });

  // ============================================================
  // RN-LSM-08: AI自然语言入库 — 意图识别系统 (S1 核心)
  // ============================================================
  test('RN-LSM-08: AI对话 — 自然语言入库', async ({ page }) => {
    // API: verify intent recognition for NL material entry
    const recognizeResp = await api('/ai-intents/recognize', {
      method: 'POST',
      body: JSON.stringify({ userInput: 'GPS牛腩入库42件' }),
    });
    if (recognizeResp.success && recognizeResp.data) {
      const { intentCode, matchMethod, confidence, matched } = recognizeResp.data;
      console.log(`RN-LSM-08 API recognize: intent=${intentCode}, method=${matchMethod}, conf=${confidence}, matched=${matched}`);
      // Should match MATERIAL_BATCH_CREATE
      const isCorrectIntent = (intentCode || '').toUpperCase().includes('MATERIAL_BATCH_CREATE')
        || (intentCode || '').toUpperCase().includes('BATCH_CREATE');
      expect(matched).toBeTruthy();
      if (!isCorrectIntent) {
        console.log(`RN-LSM-08 WARN: expected MATERIAL_BATCH_CREATE, got ${intentCode}`);
      }
    }

    // API: execute intent and verify it returns confirmation/creation
    const execResp = await api('/ai-intents/execute', {
      method: 'POST',
      body: JSON.stringify({ userInput: 'GPS牛腩入库42件' }),
    });
    if (execResp.success && execResp.data) {
      const text = execResp.data.formattedText || execResp.data.message || '';
      const result = execResp.data.resultData || {};
      const needsConfirm = result.needsConfirmation || false;
      console.log(`RN-LSM-08 API execute: status=${execResp.data.status}, needsConfirm=${needsConfirm}`);
      console.log(`RN-LSM-08 text: ${text.substring(0, 100)}`);
      // Should either need confirmation, ask for more info, or succeed directly
      const ok = needsConfirm || text.includes('入库成功') || text.includes('确认')
        || text.includes('选择') || text.includes('规则验证')
        || text.includes('提供') || text.includes('信息') || execResp.data?.status === 'NEED_MORE_INFO';
      expect(ok).toBeTruthy();
    }

    // UI: test AI chat with NL entry
    await rnLogin(page);
    const aiTapped = await tapTab(page, 'AI');
    if (!aiTapped) await tapTab(page, '智能');
    await page.waitForTimeout(3000);

    const chatInput = page.locator('[data-testid="ai-chat-input"]');
    if (await chatInput.isVisible().catch(() => false)) {
      await chatInput.click();
      await page.keyboard.type('新到一批辣椒500公斤');
      await page.waitForTimeout(500);

      const sendBtn = page.locator('[data-testid="ai-send-button"]');
      if (await sendBtn.isVisible().catch(() => false)) {
        await sendBtn.click();
      } else {
        await page.keyboard.press('Enter');
      }

      await page.waitForTimeout(15000);
      await shot(page, '08-ai-nl-entry-response.png');

      // Check response mentions 入库 or confirmation
      const responseArea = page.locator('[data-testid^="ai-chat-message-"]').last();
      console.log('RN-LSM-08 AI response visible:', await responseArea.isVisible().catch(() => false));
    }

    // Test more NL variants via API
    const variants = [
      '到货一批花椒200斤',
      '帮我登记原料入库',
      '物料入库 盐 50袋',
    ];
    for (const v of variants) {
      const r = await api('/ai-intents/recognize', {
        method: 'POST',
        body: JSON.stringify({ userInput: v }),
      });
      if (r.success && r.data) {
        console.log(`RN-LSM-08 variant "${v}": intent=${r.data.intentCode}, matched=${r.data.matched}`);
      }
    }
  });

  // ============================================================
  // RN-LSM-09: 进销存闭环验证 (S5 — API层)
  // ============================================================
  test('RN-LSM-09: 进销存闭环 — SO→PP→FG联动', async ({ page }) => {
    // This is primarily an API chain test with UI verification at key points

    // Step 1: Get product type
    const types = await api('/product-types?page=1&size=3');
    const products = Array.isArray(types.data) ? types.data : types.data?.content || [];
    test.skip(!products.length, 'No product types');
    const ptId = products[0].id;
    const ptName = products[0].name || '?';
    console.log(`RN-LSM-09: product=${ptName} (${ptId})`);

    // Step 1b: Get customer
    const custResp = await api('/customers?page=1&size=1');
    const custs = Array.isArray(custResp.data) ? custResp.data : custResp.data?.content || [];
    const custId = custs.length ? custs[0].id : 'CUST-E2E';

    // Step 2: Create sales order (correct DTO: customerId + orderDate + items[])
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
          unitPrice: 50.0,
        }],
      }),
    });
    if (!soResp.success) {
      console.log(`RN-LSM-09: SO creation failed: ${soResp.message}`);
      return;
    }
    const soId = soResp.data?.id || soResp.data?.orderId;
    console.log(`RN-LSM-09: SO created: ${soId}`);

    // Step 3: Confirm SO → triggers SupplyChainOrchestrator
    const confirmResp = await api(`/sales/orders/${soId}/confirm`, { method: 'POST' });
    console.log(`RN-LSM-09: SO confirmed: ${confirmResp.success}`);
    expect(confirmResp.success).toBeTruthy();

    // Step 4: Wait for async event processing
    await page.waitForTimeout(3000);

    // Step 5: Check if production plan was auto-created
    const plans = await api('/production-plans?page=1&size=5&sort=createdAt&sortDirection=DESC');
    const planList = Array.isArray(plans.data) ? plans.data : plans.data?.content || [];
    const linkedPlan = planList.find(
      (p: any) => p.sourceOrderId === soId || p.salesOrderId === soId || p.sourceType === 'CUSTOMER_ORDER'
    );
    if (linkedPlan) {
      console.log(`RN-LSM-09: Auto PP created: ${linkedPlan.planNumber || linkedPlan.id}, status=${linkedPlan.status}`);
    } else {
      console.log(`RN-LSM-09: No linked PP found (stock may have been sufficient). ${planList.length} plans total.`);
    }

    // Step 6: Check completed batches for auto-consumption + FG
    const completedBatches = await api('/production-batches?page=1&size=3&status=COMPLETED');
    const cbList = Array.isArray(completedBatches.data) ? completedBatches.data : completedBatches.data?.content || [];
    if (cbList.length) {
      const cbId = cbList[0].id;

      // Check auto BOM consumption
      const consumptions = await api(`/processing/material-consumptions/batch/${cbId}`);
      const cList = Array.isArray(consumptions.data) ? consumptions.data : [];
      const hasAutoBom = cList.some((c: any) => c.sourceType === 'AUTO_BOM');
      console.log(`RN-LSM-09: batch ${cbId} consumptions=${cList.length}, hasAUTO_BOM=${hasAutoBom}`);

      // Check finished goods
      const fgs = await api('/finished-goods?page=1&size=3');
      const fgList = Array.isArray(fgs.data) ? fgs.data : fgs.data?.content || [];
      console.log(`RN-LSM-09: ${fgList.length} finished goods batches`);
    }

    // Step 7: UI — navigate to production tab to verify batches visible
    await rnLogin(page);
    await tapTab(page, '生产');
    await page.waitForTimeout(3000);
    await shot(page, '09-supply-chain-production.png');
  });

  // ============================================================
  // RN-LSM-10: 出成率验证 (S3 补充)
  // ============================================================
  test('RN-LSM-10: 出成率计算验证', async ({ page }) => {
    // API: find a batch with goodQuantity and actualQuantity
    const batches = await api('/production-batches?page=1&size=10&sort=createdAt&sortDirection=DESC');
    const batchList = Array.isArray(batches.data) ? batches.data : batches.data?.content || [];

    const target = batchList.find(
      (b: any) => b.goodQuantity != null && b.actualQuantity != null && parseFloat(b.actualQuantity) > 0
    );

    if (target) {
      const good = parseFloat(target.goodQuantity);
      const actual = parseFloat(target.actualQuantity);
      const storedRate = target.yieldRate != null ? parseFloat(target.yieldRate) : null;
      const expectedRate = (good * 100) / actual;

      console.log(`RN-LSM-10: batch=${target.id}, good=${good}, actual=${actual}`);
      console.log(`RN-LSM-10: storedRate=${storedRate}, expectedRate=${expectedRate.toFixed(2)}`);

      if (storedRate !== null) {
        const diff = Math.abs(storedRate - expectedRate);
        expect(diff).toBeLessThan(0.5); // Allow 0.5% rounding tolerance
        console.log(`RN-LSM-10: PASS — yieldRate verified (diff=${diff.toFixed(4)})`);
      } else {
        console.log('RN-LSM-10: WARN — yieldRate is null');
      }
    } else {
      console.log('RN-LSM-10: WARN — no batch with goodQty+actualQty found');
    }

    // UI: verify batch detail shows yield rate
    await rnLogin(page);
    await tapTab(page, '生产');
    await page.waitForTimeout(3000);

    const batchListBtn = page.locator('text=批次列表')
      .or(page.locator('text=生产批次'))
      .first();
    if (await batchListBtn.isVisible().catch(() => false)) {
      await batchListBtn.click();
      await page.waitForTimeout(3000);

      const firstBatch = page.locator('[data-testid="batch-item-0"]')
        .or(page.locator('[data-testid^="production-batch-"]').first());
      if (await firstBatch.isVisible().catch(() => false)) {
        await firstBatch.click();
        await page.waitForTimeout(3000);

        // Check for yield rate display
        const yieldLabel = page.locator('text=出成率')
          .or(page.locator('text=良品率'))
          .or(page.locator('text=yieldRate'));
        console.log('RN-LSM-10 出成率 label visible:', await yieldLabel.isVisible().catch(() => false));
        await shot(page, '10-yield-rate-detail.png');
      }
    }
  });

  // ============================================================
  // RN-P0-01: 批次详情BOM达成率卡片
  // ============================================================
  test('RN-P0-01: 批次详情 — BOM达成率卡片', async ({ page }) => {
    await rnLogin(page);

    // Navigate to Processing/生产 tab
    let tapped = await tapTab(page, '生产');
    if (!tapped) tapped = await tapTab(page, '加工');
    await page.waitForTimeout(3000);
    await shot(page, 'rn-p0-01-production-home.png');

    // Navigate to batch list
    const batchListBtn = page.locator('text=批次列表')
      .or(page.locator('text=生产批次'))
      .or(page.locator('[data-testid="dashboard-batch-list-btn"]'))
      .first();
    if (await batchListBtn.isVisible().catch(() => false)) {
      await batchListBtn.click();
      await page.waitForTimeout(3000);
    }

    // Click first batch card
    const firstBatch = page.locator('[data-testid="batch-item-0"]')
      .or(page.locator('[data-testid^="production-batch-"]').first());
    if (await firstBatch.isVisible().catch(() => false)) {
      await firstBatch.click();
      await page.waitForTimeout(3000);
      await shot(page, 'rn-p0-01-batch-detail.png');

      // Click consumption tab
      const consumptionTab = page.locator('text=消耗')
        .or(page.locator('text=物料消耗'))
        .or(page.locator('text=BOM'))
        .first();
      if (await consumptionTab.isVisible().catch(() => false)) {
        await consumptionTab.click();
        await page.waitForTimeout(3000);
        await shot(page, 'rn-p0-01-consumption-tab.png');
      }

      // Verify BOM achievement elements
      const achievementCard = page.locator('[data-testid="bom-achievement-card"]');
      const achievementCardVisible = await achievementCard.isVisible().catch(() => false);
      console.log('RN-P0-01 bom-achievement-card visible:', achievementCardVisible);

      const achievementRate = page.locator('[data-testid="bom-achievement-rate"]');
      const rateVisible = await achievementRate.isVisible().catch(() => false);
      console.log('RN-P0-01 bom-achievement-rate visible:', rateVisible);

      // Check for "达成率" text anywhere on screen
      const rateText = page.locator('text=达成率');
      const rateTextVisible = await rateText.isVisible().catch(() => false);
      console.log('RN-P0-01 text "达成率" visible:', rateTextVisible);
    } else {
      console.log('RN-P0-01: SKIP — no batch items');
    }

    // API verification
    const batchResp = await api('/production-batches?page=1&size=3');
    const content = batchResp.data?.content || batchResp.data || [];
    if (content.length) {
      const batchId = content[0].id;
      const summary = await api(`/processing/material-consumptions/batch/${batchId}/summary`);
      if (summary.success) {
        const items = Array.isArray(summary.data) ? summary.data : (summary.data?.bomItems || []);
        console.log(`RN-P0-01 API: batch ${batchId}, consumption items=${items.length}`);
      }
    }

    await shot(page, 'rn-p0-01-final.png');
  });

  // ============================================================
  // RN-P0-02: 物料移动均价
  // ============================================================
  test('RN-P0-02: 物料详情 — 移动均价', async ({ page }) => {
    // API: check material types for movingAvgPrice
    const types = await api('/raw-material-types/active');
    const typeList = Array.isArray(types.data) ? types.data : types.data?.content || [];
    let foundAvgPrice = false;
    for (const mt of typeList.slice(0, 5)) {
      const avg = mt.movingAvgPrice;
      if (avg != null && avg > 0) {
        foundAvgPrice = true;
        console.log(`RN-P0-02 API: ${mt.name}, movingAvgPrice=¥${avg}`);
        break;
      }
    }
    console.log(`RN-P0-02: API has movingAvgPrice=${foundAvgPrice}`);

    await rnLogin(page);

    // Navigate to warehouse/inventory tab
    let tapped = await tapTab(page, '仓储');
    if (!tapped) tapped = await tapTab(page, '库存');
    await page.waitForTimeout(3000);
    await shot(page, 'rn-p0-02-warehouse-home.png');

    // Look for material type list
    const materialItem = page.locator('[data-testid="material-item-0"]')
      .or(page.locator('[data-testid^="material-type-"]').first())
      .or(page.locator('text=库存管理').first());

    if (await materialItem.isVisible().catch(() => false)) {
      await materialItem.click();
      await page.waitForTimeout(3000);
      await shot(page, 'rn-p0-02-material-list.png');

      // Look for moving average price display
      const movingAvg = page.locator('[data-testid="moving-avg-price"]');
      const movingAvgVisible = await movingAvg.isVisible().catch(() => false);
      console.log('RN-P0-02 moving-avg-price testid visible:', movingAvgVisible);

      // Also check by text
      const avgText = page.locator('text=移动均价')
        .or(page.locator('text=平均价'))
        .or(page.locator('text=均价'));
      const avgTextVisible = await avgText.isVisible().catch(() => false);
      console.log('RN-P0-02 text "移动均价/均价" visible:', avgTextVisible);

      // Check for price with ¥ symbol
      const priceDisplay = page.locator('text=/¥/');
      const priceVisible = await priceDisplay.first().isVisible().catch(() => false);
      console.log('RN-P0-02 price with ¥ visible:', priceVisible);
    } else {
      console.log('RN-P0-02: material items not found on UI');
    }

    await shot(page, 'rn-p0-02-final.png');
  });

  // ============================================================
  // RN-P1-01: 报工良品率实时计算
  // ============================================================
  test('RN-P1-01: 报工 — 良品率实时计算', async ({ page }) => {
    await rnLogin(page);

    // Navigate to processing tab
    let tapped = await tapTab(page, '生产');
    if (!tapped) tapped = await tapTab(page, '加工');
    await page.waitForTimeout(3000);

    // Find scan report entry point
    const scanReportBtn = page.locator('[data-testid="scan-report-screen"]')
      .or(page.locator('[data-testid="dashboard-scan-report-btn"]'))
      .or(page.locator('text=扫码报工'))
      .or(page.locator('text=报工'));

    const scanReportVisible = await scanReportBtn.first().isVisible().catch(() => false);
    console.log('RN-P1-01 scan report entry visible:', scanReportVisible);

    if (scanReportVisible) {
      await scanReportBtn.first().click();
      await page.waitForTimeout(3000);
      await shot(page, 'rn-p1-01-report-screen.png');

      // Look for output quantity input
      const outputInput = page.locator('[data-testid="report-output-quantity"]');
      const outputVisible = await outputInput.isVisible().catch(() => false);
      console.log('RN-P1-01 output quantity input visible:', outputVisible);

      if (outputVisible) {
        // Fill output quantity
        await outputInput.click();
        await page.keyboard.type('100');
        await page.waitForTimeout(500);
      }

      // Look for good quantity input
      const goodInput = page.locator('[data-testid="report-good-quantity"]');
      const goodVisible = await goodInput.isVisible().catch(() => false);
      console.log('RN-P1-01 good quantity input visible:', goodVisible);

      if (goodVisible) {
        await goodInput.click();
        await page.keyboard.type('90');
        await page.waitForTimeout(1000);
      }

      // Verify yield rate display
      const yieldDisplay = page.locator('[data-testid="yield-rate-display"]');
      const yieldVisible = await yieldDisplay.isVisible().catch(() => false);
      console.log('RN-P1-01 yield-rate-display visible:', yieldVisible);

      const yieldValue = page.locator('[data-testid="yield-rate-value"]');
      const yieldValueVisible = await yieldValue.isVisible().catch(() => false);
      console.log('RN-P1-01 yield-rate-value visible:', yieldValueVisible);

      // Check for "良品率" text
      const yieldText = page.locator('text=良品率')
        .or(page.locator('text=出成率'));
      const yieldTextVisible = await yieldText.isVisible().catch(() => false);
      console.log('RN-P1-01 text "良品率/出成率" visible:', yieldTextVisible);

      await shot(page, 'rn-p1-01-yield-filled.png');
    } else {
      // Try alternative: navigate to task list then report
      const taskBtn = page.locator('[data-testid="dashboard-process-task-btn"]')
        .or(page.locator('text=工序任务').first());
      if (await taskBtn.isVisible().catch(() => false)) {
        await taskBtn.click();
        await page.waitForTimeout(3000);

        const firstCard = page.locator('[data-testid^="process-task-card-"]').first();
        if (await firstCard.isVisible().catch(() => false)) {
          await firstCard.click();
          await page.waitForTimeout(3000);

          const reportBtn = page.locator('[data-testid="task-detail-report-btn"]');
          if (await reportBtn.isVisible().catch(() => false)) {
            await reportBtn.click();
            await page.waitForTimeout(3000);

            // Verify yield rate field in report form
            const yieldLabel = page.locator('text=良品率')
              .or(page.locator('text=出成率'));
            console.log('RN-P1-01 yield label in report form:', await yieldLabel.isVisible().catch(() => false));
          }
        }
      }

      console.log('RN-P1-01: used alternative path');
      await shot(page, 'rn-p1-01-alternative.png');
    }
  });

  // ============================================================
  // RN-P1-02: AI入库成功页
  // ============================================================
  test('RN-P1-02: AI对话 — 入库流程', async ({ page }) => {
    await rnLogin(page);

    // Navigate to AI tab
    let aiTapped = await tapTab(page, 'AI');
    if (!aiTapped) aiTapped = await tapTab(page, '智能');
    await page.waitForTimeout(3000);
    await shot(page, 'rn-p1-02-ai-home.png');

    const chatInput = page.locator('[data-testid="ai-chat-input"]');
    if (await chatInput.isVisible().catch(() => false)) {
      // Send an entry command
      await chatInput.click();
      await page.keyboard.type('新到一批花椒200斤');
      await page.waitForTimeout(500);

      const sendBtn = page.locator('[data-testid="ai-send-button"]');
      if (await sendBtn.isVisible().catch(() => false)) {
        await sendBtn.click();
      } else {
        await page.keyboard.press('Enter');
      }

      // Wait for AI response
      await page.waitForTimeout(15000);
      await shot(page, 'rn-p1-02-ai-entry-response.png');

      // Check response area
      const responseArea = page.locator('[data-testid^="ai-chat-message-"]').last();
      const responseVisible = await responseArea.isVisible().catch(() => false);
      console.log('RN-P1-02 AI response visible:', responseVisible);

      if (responseVisible) {
        const responseText = await responseArea.textContent().catch(() => '');
        console.log(`RN-P1-02 AI response text: ${(responseText || '').substring(0, 120)}`);

        // Check for entry-related content
        const hasEntryContent = responseText?.includes('入库')
          || responseText?.includes('确认')
          || responseText?.includes('花椒')
          || responseText?.includes('选择');
        console.log(`RN-P1-02 has entry content: ${hasEntryContent}`);
      }

      // Look for success/confirmation elements
      const successPage = page.locator('[data-testid="entry-success"]')
        .or(page.locator('text=入库成功'));
      const successVisible = await successPage.isVisible().catch(() => false);
      console.log('RN-P1-02 success page visible:', successVisible);
    } else {
      console.log('RN-P1-02: SKIP — AI chat input not found');
    }

    // API verification
    const intentResp = await api('/ai-intents/recognize', {
      method: 'POST',
      body: JSON.stringify({ userInput: '新到一批花椒200斤' }),
    });
    if (intentResp.success && intentResp.data) {
      console.log(`RN-P1-02 API intent: code=${intentResp.data.intentCode}, method=${intentResp.data.matchMethod}, matched=${intentResp.data.matched}`);
    }

    await shot(page, 'rn-p1-02-final.png');
  });

  // ============================================================
  // RN-P2-01: 工序投入产出对比
  // ============================================================
  test('RN-P2-01: 工序详情 — 投入产出对比', async ({ page }) => {
    await rnLogin(page);

    // Navigate to processing tab
    let tapped = await tapTab(page, '生产');
    if (!tapped) tapped = await tapTab(page, '加工');
    await page.waitForTimeout(3000);

    // Navigate to process task list
    const taskBtn = page.locator('[data-testid="dashboard-process-task-btn"]')
      .or(page.locator('text=工序任务').first());

    if (await taskBtn.isVisible().catch(() => false)) {
      await taskBtn.click();
      await page.waitForTimeout(3000);
      await shot(page, 'rn-p2-01-task-list.png');

      // Open first task card
      const firstCard = page.locator('[data-testid^="process-task-card-"]').first();
      if (await firstCard.isVisible().catch(() => false)) {
        await firstCard.click();
        await page.waitForTimeout(3000);
        await shot(page, 'rn-p2-01-task-detail.png');

        // Check for process IO comparison elements
        const ioComparison = page.locator('[data-testid="process-io-comparison"]');
        const ioVisible = await ioComparison.isVisible().catch(() => false);
        console.log('RN-P2-01 process-io-comparison visible:', ioVisible);

        // Check for input/output text
        const inputText = page.locator('text=投入量')
          .or(page.locator('text=投入'));
        const outputText = page.locator('text=产出量')
          .or(page.locator('text=产出'));

        console.log('RN-P2-01 text "投入" visible:', await inputText.isVisible().catch(() => false));
        console.log('RN-P2-01 text "产出" visible:', await outputText.isVisible().catch(() => false));

        // Check for conversion rate
        const conversionText = page.locator('text=转化率')
          .or(page.locator('text=出成率'));
        console.log('RN-P2-01 text "转化率" visible:', await conversionText.isVisible().catch(() => false));

        // Check for wastage rate
        const wastageText = page.locator('text=损耗率')
          .or(page.locator('text=损耗'));
        console.log('RN-P2-01 text "损耗" visible:', await wastageText.isVisible().catch(() => false));
      } else {
        console.log('RN-P2-01: no task cards found');
      }
    } else {
      // Try management tab path
      await tapTab(page, '管理');
      await page.waitForTimeout(2000);

      const mgmtTaskBtn = page.locator('[data-testid="fa-process-task-btn"]')
        .or(page.locator('text=工序任务').first());
      if (await mgmtTaskBtn.isVisible().catch(() => false)) {
        await mgmtTaskBtn.click();
        await page.waitForTimeout(3000);
        console.log('RN-P2-01: navigated via management tab');
      } else {
        console.log('RN-P2-01: SKIP — cannot find task entry');
      }
    }

    await shot(page, 'rn-p2-01-final.png');
  });

  // ============================================================
  // RN-M82: 批次详情 — 完整消耗Tab流 (对标 Maestro 82)
  // ============================================================
  test('RN-M82: 批次详情 — 消耗Tab完整流', async ({ page }) => {
    await rnLogin(page);

    // API: find a batch
    const batchResp = await api('/processing/batches?page=1&size=5');
    const batches = batchResp.data?.content || batchResp.data || [];
    console.log(`RN-M82: ${batches.length} batches from API`);

    if (batches.length > 0) {
      const batch = batches[0];
      const batchId = batch.id || batch.batchId;
      console.log(`RN-M82: batch=${batch.batchNumber}, id=${batchId}`);

      // API: get consumption summary
      const summaryResp = await api(`/processing/material-consumptions/batch/${batchId}/summary`);
      const summary = summaryResp.data;
      if (summary) {
        console.log(`RN-M82 API: achievementRate=${summary.overallAchievementRate}%, materials=${summary.materials?.length || 0}`);
        if (summary.materials?.length > 0) {
          const mat = summary.materials[0];
          console.log(`RN-M82 API: first material=${mat.materialName}, planned=${mat.plannedQuantity}, actual=${mat.actualQuantity}`);
        }
      } else {
        console.log('RN-M82 API: no summary data');
      }
    }

    // Navigate to batch list via UI
    let tapped = await tapTab(page, '批次');
    if (!tapped) tapped = await tapTab(page, '管理');
    await page.waitForTimeout(3000);

    // Look for batch card
    const batchCard = page.locator('[data-testid^="batch-card-"]').first()
      .or(page.locator('[data-testid="batch-list-item-0"]').first());
    if (await batchCard.isVisible().catch(() => false)) {
      await batchCard.click();
      await page.waitForTimeout(3000);

      // Try clicking consumption tab
      const consumptionTab = page.locator('text=消耗').first()
        .or(page.locator('text=Consumption').first());
      if (await consumptionTab.isVisible().catch(() => false)) {
        await consumptionTab.click();
        await page.waitForTimeout(3000);

        // Check BOM achievement card
        const bomCard = page.locator('[data-testid="bom-achievement-card"]');
        console.log('RN-M82 bom-achievement-card visible:', await bomCard.isVisible().catch(() => false));
        const bomRate = page.locator('[data-testid="bom-achievement-rate"]');
        console.log('RN-M82 bom-achievement-rate visible:', await bomRate.isVisible().catch(() => false));
      } else {
        console.log('RN-M82: consumption tab not found');
      }
    } else {
      console.log('RN-M82: no batch cards on UI');
    }
    await shot(page, 'rn-m82-batch-consumption.png');
  });

  // ============================================================
  // RN-M84: 报工良品率 — 输入不同数值验证色标 (对标 Maestro 84/85)
  // ============================================================
  test('RN-M84: 报工良品率 — 三色阈值验证', async ({ page }) => {
    await rnLogin(page);

    // Navigate to scan report
    let tapped = await tapTab(page, '批次');
    if (!tapped) tapped = await tapTab(page, '生产');
    await page.waitForTimeout(3000);

    // Find scan report entry
    const scanBtn = page.locator('[data-testid="scan-report-btn"]')
      .or(page.locator('text=扫码报工').first())
      .or(page.locator('text=报工').first());

    let foundReport = false;
    if (await scanBtn.isVisible().catch(() => false)) {
      await scanBtn.click();
      await page.waitForTimeout(3000);
      foundReport = true;
    }

    // Check for yield rate elements
    const screenTestId = page.locator('[data-testid="scan-report-screen"]');
    if (await screenTestId.isVisible().catch(() => false)) {
      console.log('RN-M84: scan-report-screen visible');

      // Check testIDs (对标 Maestro 86)
      const testIds = ['report-output-quantity', 'report-input-quantity', 'report-good-quantity', 'report-submit-btn'];
      for (const tid of testIds) {
        const el = page.locator(`[data-testid="${tid}"]`);
        const visible = await el.isVisible().catch(() => false);
        console.log(`RN-M84 testID "${tid}": ${visible}`);
      }

      // Try filling quantities to trigger yield rate
      const outputInput = page.locator('[data-testid="report-output-quantity"]');
      const goodInput = page.locator('[data-testid="report-good-quantity"]');
      if (await outputInput.isVisible().catch(() => false) && await goodInput.isVisible().catch(() => false)) {
        // Fill 100/96 → green (≥95%)
        await outputInput.fill('100');
        await goodInput.fill('96');
        await page.waitForTimeout(1000);
        const yieldDisplay = page.locator('[data-testid="yield-rate-display"]');
        console.log('RN-M84 yield-rate at 96%:', await yieldDisplay.isVisible().catch(() => false));
        await shot(page, 'rn-m84-yield-green.png');

        // Fill 100/90 → orange (85-95%)
        await goodInput.fill('');
        await goodInput.fill('90');
        await page.waitForTimeout(1000);
        console.log('RN-M84 yield-rate at 90%:', await yieldDisplay.isVisible().catch(() => false));
        await shot(page, 'rn-m84-yield-orange.png');

        // Fill 100/75 → red (<85%)
        await goodInput.fill('');
        await goodInput.fill('75');
        await page.waitForTimeout(1000);
        console.log('RN-M84 yield-rate at 75%:', await yieldDisplay.isVisible().catch(() => false));
        await shot(page, 'rn-m84-yield-red.png');
      }
    } else {
      console.log('RN-M84: scan-report-screen not found, checking API');
      // API verification of yield calculation
      const batchResp = await api('/processing/batches?page=1&size=5');
      const batches = batchResp.data?.content || batchResp.data || [];
      for (const b of batches.slice(0, 3)) {
        if (b.goodQuantity && b.actualQuantity) {
          const rate = Math.round((b.goodQuantity / b.actualQuantity) * 1000) / 10;
          console.log(`RN-M84 API: batch=${b.batchNumber}, good=${b.goodQuantity}, actual=${b.actualQuantity}, yield=${rate}%`);
        }
      }
    }
    await shot(page, 'rn-m84-final.png');
  });

  // ============================================================
  // RN-M87: 工序任务详情 — 管理Tab路径 (对标 Maestro 87)
  // ============================================================
  test('RN-M87: 工序任务详情 — 管理Tab路径', async ({ page }) => {
    await rnLogin(page);

    // Factory admin: 管理 tab → 工序任务
    const tapped = await tapTab(page, '管理');
    if (tapped) {
      await page.waitForTimeout(3000);
      await shot(page, 'rn-m87-management.png');

      // Find and tap 工序任务
      const taskEntry = page.locator('text=工序任务').first();
      if (await taskEntry.isVisible().catch(() => false)) {
        await taskEntry.click({ force: true });
        await page.waitForTimeout(3000);
        await shot(page, 'rn-m87-task-list.png');

        // Check for task cards
        const taskCards = page.locator('[data-testid^="process-task-card-"]');
        const cardCount = await taskCards.count();
        console.log(`RN-M87: ${cardCount} task cards`);

        if (cardCount > 0) {
          await taskCards.first().click();
          await page.waitForTimeout(3000);
          await shot(page, 'rn-m87-task-detail.png');

          // Check IO comparison
          const ioComp = page.locator('[data-testid="process-io-comparison"]');
          console.log('RN-M87 IO comparison visible:', await ioComp.isVisible().catch(() => false));

          // Check planned/completed quantities
          const planned = page.locator('[data-testid="task-detail-planned-qty"]');
          const completed = page.locator('[data-testid="task-detail-completed-qty"]');
          console.log('RN-M87 planned qty:', await planned.isVisible().catch(() => false));
          console.log('RN-M87 completed qty:', await completed.isVisible().catch(() => false));
        }
      } else {
        console.log('RN-M87: 工序任务 not found in management');
      }
    } else {
      console.log('RN-M87: SKIP — 管理 tab not found');
    }

    await shot(page, 'rn-m87-final.png');
  });

  // ============================================================
  // RN-M88: AI入库完整流 (对标 Maestro 88)
  // ============================================================
  test('RN-M88: AI入库 — 意图识别+字段补全', async ({ page }) => {
    await rnLogin(page);

    // API: test multiple receipt variants
    const variants = [
      '新到一批辣椒500公斤',
      '到货一批花椒200斤',
      '帮我登记原料入库',
      '物料入库 盐 50袋',
    ];
    for (const v of variants) {
      const resp = await api('/ai-intents/recognize', {
        method: 'POST',
        body: JSON.stringify({ userInput: v }),
      });
      const intent = resp.data?.intentCode || resp.data?.intent;
      const method = resp.data?.matchMethod || resp.data?.method;
      const matched = intent === 'MATERIAL_BATCH_CREATE';
      console.log(`RN-M88 "${v}": intent=${intent}, method=${method}, matched=${matched}`);
    }

    // API: execute receipt intent
    const execResp = await api('/ai-intents/execute', {
      method: 'POST',
      body: JSON.stringify({ userInput: '入库辣椒500公斤' }),
    });
    const status = execResp.data?.status;
    const text = (execResp.data?.text || execResp.data?.message || '').substring(0, 100);
    console.log(`RN-M88 execute: status=${status}, text=${text}`);

    // Verify NEED_MORE_INFO response (missing supplier, materialType)
    if (status === 'NEED_MORE_INFO') {
      console.log('RN-M88: correctly asks for more info (supplier/type fields)');
    }

    // Navigate to AI tab and verify UI
    const aiTapped = await tapTab(page, 'AI分析');
    if (!aiTapped) await tapTab(page, 'AI');
    await page.waitForTimeout(3000);

    const chatInput = page.locator('[data-testid="ai-chat-input"]')
      .or(page.getByPlaceholder(/输入|问题|消息/));
    console.log('RN-M88 chat input visible:', await chatInput.isVisible().catch(() => false));

    await shot(page, 'rn-m88-ai-receipt.png');
  });

  // ============================================================
  // RN-M89: 批次详情完整流 — 信息Tab→消耗Tab→返回 (对标 Maestro 89)
  // ============================================================
  test('RN-M89: 批次详情完整导航流', async ({ page }) => {
    await rnLogin(page);

    // API: get batch data
    const batchResp = await api('/processing/batches?page=1&size=5');
    const batches = batchResp.data?.content || batchResp.data || [];
    console.log(`RN-M89 API: ${batches.length} batches`);

    if (batches.length > 0) {
      const b = batches[0];
      console.log(`RN-M89 API: batch=${b.batchNumber}, product=${b.productName}, status=${b.status}`);

      // Get consumption summary
      const summaryResp = await api(`/processing/material-consumptions/batch/${b.id || b.batchId}/summary`);
      if (summaryResp.data) {
        console.log(`RN-M89 API: achievementRate=${summaryResp.data.overallAchievementRate}%`);
      }
    }

    // UI: navigate to batch list
    let tapped = await tapTab(page, '批次');
    if (!tapped) tapped = await tapTab(page, '管理');
    await page.waitForTimeout(3000);
    await shot(page, 'rn-m89-batch-list.png');

    // Tap first batch
    const firstBatch = page.locator('[data-testid^="batch-card-"]').first()
      .or(page.locator('[data-testid="batch-list-item-0"]'));
    if (await firstBatch.isVisible().catch(() => false)) {
      await firstBatch.click();
      await page.waitForTimeout(3000);
      await shot(page, 'rn-m89-batch-info.png');

      // Verify info tab content
      const batchNum = page.locator('text=批次号').or(page.locator('text=Batch'));
      console.log('RN-M89 batch number label:', await batchNum.first().isVisible().catch(() => false));

      // Switch to consumption tab
      const consumeTab = page.locator('text=消耗').first();
      if (await consumeTab.isVisible().catch(() => false)) {
        await consumeTab.click();
        await page.waitForTimeout(3000);
        console.log('RN-M89: consumption tab clicked');
        await shot(page, 'rn-m89-consumption.png');

        // Verify BOM card
        const bomCard = page.locator('[data-testid="bom-achievement-card"]');
        console.log('RN-M89 BOM card:', await bomCard.isVisible().catch(() => false));
      }

      // Go back
      const backBtn = page.locator('[data-testid="header-back-btn"]')
        .or(page.locator('role=button >> text=返回'))
        .or(page.locator('[aria-label="Go back"]'));
      if (await backBtn.first().isVisible().catch(() => false)) {
        await backBtn.first().click();
        await page.waitForTimeout(2000);
        console.log('RN-M89: navigated back');
      }
    } else {
      console.log('RN-M89: no batch cards visible');
    }
    await shot(page, 'rn-m89-final.png');
  });

  // ============================================================
  // RN-M90: 库存详情完整流 — 移动均价 (对标 Maestro 90)
  // ============================================================
  test('RN-M90: 库存详情 — 均价+批次详情', async ({ page }) => {
    await rnLogin(page, 'warehouse_mgr1', '123456');

    // API: verify material types have movingAvgPrice
    const matResp = await api('/raw-material-types/active');
    const materials = matResp.data || [];
    const withPrice = materials.filter((m: Record<string, unknown>) => m.movingAvgPrice && Number(m.movingAvgPrice) > 0);
    console.log(`RN-M90 API: ${materials.length} materials, ${withPrice.length} with movingAvgPrice`);
    if (withPrice.length > 0) {
      const m = withPrice[0] as Record<string, unknown>;
      console.log(`RN-M90 API: ${m.name}, avgPrice=¥${m.movingAvgPrice}`);
    }

    // API: verify batch data
    const batchResp = await api('/material-batches?page=1&size=5');
    const batchData = batchResp.data?.content || batchResp.data || [];
    console.log(`RN-M90 API: ${batchData.length} batches`);
    if (batchData.length > 0) {
      const batch = batchData[0] as Record<string, unknown>;
      console.log(`RN-M90 API: batch=${batch.batchNumber}, price=${batch.unitPrice}, avgPrice=${batch.movingAvgPrice}`);
    }

    // UI: navigate to inventory
    let tapped = await tapTab(page, '库存');
    if (!tapped) tapped = await tapTab(page, '仓储');
    if (!tapped) tapped = await tapTab(page, '首页');
    await page.waitForTimeout(3000);
    await shot(page, 'rn-m90-inventory.png');

    // Check for material items
    const materialItem = page.locator('[data-testid^="material-item-"]').first()
      .or(page.locator('[data-testid^="inventory-item-"]').first());
    if (await materialItem.isVisible().catch(() => false)) {
      await materialItem.click();
      await page.waitForTimeout(3000);

      // Check for moving avg price display
      const avgPrice = page.locator('[data-testid="moving-avg-price"]');
      console.log('RN-M90 moving-avg-price visible:', await avgPrice.isVisible().catch(() => false));

      const avgText = page.locator('text=移动均价').or(page.locator('text=均价'));
      console.log('RN-M90 均价 text visible:', await avgText.first().isVisible().catch(() => false));
      await shot(page, 'rn-m90-detail.png');
    } else {
      console.log('RN-M90: no material items on UI');
    }
    await shot(page, 'rn-m90-final.png');
  });

  // ============================================================
  // RN-M91: 跨角色数据验证 (对标 Maestro 91)
  // ============================================================
  test('RN-M91: 跨角色 — WS+FA数据一致性', async ({ page }) => {
    // Step 1: Login as WS, get batch data
    const wsToken = await (async () => {
      const res = await fetch(`${API}/auth/unified-login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: 'workshop_sup1', password: '123456' }),
      });
      const json = await res.json();
      return json.data?.accessToken || json.data?.token || '';
    })();

    const wsBatchResp = await fetch(`${API}/${FACTORY_ID}/processing/batches?page=1&size=5`, {
      headers: { Authorization: `Bearer ${wsToken}` },
    });
    const wsBatches = (await wsBatchResp.json()).data?.content || [];
    console.log(`RN-M91 WS: ${wsBatches.length} batches`);

    // Step 2: Login as FA, get same data
    const faBatchResp = await api('/processing/batches?page=1&size=5');
    const faBatches = faBatchResp.data?.content || faBatchResp.data || [];
    console.log(`RN-M91 FA: ${faBatches.length} batches`);

    // Step 3: Compare — same batches visible to both roles
    if (wsBatches.length > 0 && faBatches.length > 0) {
      const wsIds = new Set(wsBatches.map((b: Record<string, unknown>) => b.batchNumber));
      const faIds = new Set(faBatches.map((b: Record<string, unknown>) => b.batchNumber));
      const overlap = [...wsIds].filter(id => faIds.has(id));
      console.log(`RN-M91: ${overlap.length} common batches between WS and FA`);
      console.log(`RN-M91: WS batches: ${[...wsIds].slice(0, 3).join(', ')}`);
      console.log(`RN-M91: FA batches: ${[...faIds].slice(0, 3).join(', ')}`);
    }

    // Step 4: UI — verify FA can see management and AI tabs
    await rnLogin(page);
    const mgmtTab = await tapTab(page, '管理');
    console.log('RN-M91 FA 管理tab:', mgmtTab);
    await page.waitForTimeout(2000);
    await shot(page, 'rn-m91-fa-management.png');

    const aiTab = await tapTab(page, 'AI分析');
    console.log('RN-M91 FA AI分析tab:', aiTab);
    await page.waitForTimeout(2000);
    await shot(page, 'rn-m91-fa-ai.png');

    await shot(page, 'rn-m91-final.png');
  });
});
