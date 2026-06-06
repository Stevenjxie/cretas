/**
 * Cross-End Phase 1 — Web 建单→写 shared-state.json @post-deploy
 *
 * This spec is Phase 1 of the cross-end handshake E2E test:
 *   1. sales_mgr creates a Sales Order (SO) via UI
 *   2. super_admin creates a Production Plan from the SO via UI
 *   3. super_admin generates an FMR from the plan via API (same as G3 Phase 3)
 *   4. Key codes (orderCode, planCode, fmrCode, batchNumber) are written to
 *      `.shared-state.json` for Phase 2 (RN app) to pick up.
 *   5. Final test verifies `.shared-state.json` has the correct structure.
 *
 * Relationship to G3:
 *   - This is a SUBSET of G3 (Phases 1–3 only — no FMR execution or plan completion).
 *   - The primary goal is to emit `.shared-state.json` rather than test the full chain.
 *   - G3 remains the authoritative closed-loop production test.
 *
 * Auth: sales_mgr (SO creation) + super_admin (plan + FMR generation).
 * Output: tests/v1-e2e/test-results/shared-state.json (via helpers/shared-state.ts)
 *
 * Pre-requisites (same as G3):
 *   - Seed data from Task 2: factory F_E2E_TEST, product 酸菜鱼 500g, customer 鼎鲜火锅义乌分公司
 */

import { test, expect } from '@playwright/test';
import * as path from 'path';
import * as fs from 'fs';
import { setupAuthBeforeAll, restoreAuth, installApiProxy } from '../helpers/auth-cache';
import { S } from '../helpers/selectors';
import { writeState, readState, clearState } from '../helpers/shared-state';

// ─── Constants ─────────────────────────────────────────────────────────────────

const FACTORY_ID    = 'F_E2E_TEST';
const PRODUCT_NAME  = '酸菜鱼 500g';
const CUSTOMER_NAME = '鼎鲜火锅义乌分公司';
const PLAN_QTY      = 5;
const PLAN_DATE     = '2026-04-20';

// State shared across tests in this suite (module-level, same worker)
let soNumber   = '';
let planId     = '';
let planNumber = '';
let fmrId      = '';
let fmrNo      = '';

// ─── Suite ─────────────────────────────────────────────────────────────────────

test.describe('Cross-End Phase 1 — Web 建单→写 shared state @post-deploy', () => {

  test.beforeAll(async ({ browser }) => {
    await setupAuthBeforeAll(browser, 'sales_mgr');
    await setupAuthBeforeAll(browser, 'super_admin');
    // Clear any stale shared state from previous run
    clearState();
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // Test 1: Create SO → Production Plan → FMR
  // ═══════════════════════════════════════════════════════════════════════════

  test('创建 SO → 生产计划 → FMR', async ({ browser }) => {

    const runTag = `CE1-${Date.now()}`;

    // ─── Phase 1: sales_mgr — Create SO ───────────────────────────────────────
    const salesCtx  = await browser.newContext();
    const salesPage = await salesCtx.newPage();
    await restoreAuth(salesCtx, salesPage, 'sales_mgr');

    await salesPage.goto('/sales/orders');
    await salesPage.waitForURL(/\/sales\/orders/, { timeout: 20_000 });
    await salesPage.waitForSelector('.el-table', { timeout: 15_000 });

    // Open create dialog
    await salesPage.click('button:has-text("新建")');
    const createDialog = salesPage.locator('.el-dialog:visible');
    await expect(createDialog).toBeVisible({ timeout: 10_000 });

    // Select customer
    const customerSelect = createDialog.locator(S.form.select('客户'));
    await customerSelect.click();
    await salesPage.waitForTimeout(500);
    const customerOption = salesPage.locator(
      `.el-select-dropdown:visible .el-select-dropdown__item:has-text("${CUSTOMER_NAME}")`
    );
    await expect(customerOption).toBeVisible({ timeout: 8_000 });
    await customerOption.click();

    // Fill remark
    const remarkArea = createDialog.locator('textarea').first();
    await remarkArea.fill(`[E2E ${runTag}] cross-end SO`);

    // Select product
    const productSelect = createDialog.locator('.item-row .el-select').first();
    await productSelect.click();
    await salesPage.waitForTimeout(500);
    const productOption = salesPage.locator(
      `.el-select-dropdown:visible .el-select-dropdown__item:has-text("${PRODUCT_NAME}")`
    );
    await expect(productOption).toBeVisible({ timeout: 8_000 });
    await productOption.click();

    // Set quantity
    const qtyInput = createDialog.locator('.el-input-number input').first();
    await qtyInput.click();
    await salesPage.keyboard.press('Control+A');
    await qtyInput.fill(String(PLAN_QTY));
    await salesPage.keyboard.press('Tab');

    // Submit SO creation — capture response for soNumber + soId
    const [createResp] = await Promise.all([
      salesPage.waitForResponse(
        (r) =>
          r.url().includes('/sales/orders') &&
          r.request().method() === 'POST' &&
          !r.url().includes('/confirm'),
        { timeout: 15_000 }
      ),
      createDialog.locator('button:has-text("创建")').click(),
    ]);

    const createBody = await createResp.json();
    expect(createBody.success, `SO 创建失败: ${JSON.stringify(createBody)}`).toBe(true);
    soNumber = String(createBody.data?.orderNumber || '');
    const soId = String(createBody.data?.id || '');
    expect(soId,     'SO id 不能为空').toBeTruthy();
    expect(soNumber, 'SO orderNumber 不能为空').toBeTruthy();

    // Confirm SO (DRAFT → CONFIRMED) if button is available
    await salesPage.waitForSelector(`.el-table__row:has-text("${soNumber}")`, {
      timeout: 15_000,
    });
    const soRow      = salesPage.locator(S.table.rowByText(soNumber));
    const confirmBtn = soRow.locator('button:has-text("确认")');
    if (await confirmBtn.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await confirmBtn.click();
      await salesPage.waitForSelector('.el-message-box', { timeout: 5_000 });
      const [confirmResp] = await Promise.all([
        salesPage.waitForResponse(
          (r) => r.url().includes('/confirm') && r.request().method() === 'POST',
          { timeout: 15_000 }
        ),
        salesPage.keyboard.press('Enter'),
      ]);
      const confirmBody = await confirmResp.json();
      expect(confirmBody.success, `SO 确认失败: ${JSON.stringify(confirmBody)}`).toBe(true);
    }

    await salesCtx.close();

    // ─── Phase 2: super_admin — Create Production Plan ────────────────────────
    const adminCtx  = await browser.newContext();
    const adminPage = await adminCtx.newPage();
    await restoreAuth(adminCtx, adminPage, 'super_admin');

    await adminPage.goto('/production/plans');
    await adminPage.waitForURL(/\/production\/plans/, { timeout: 20_000 });
    await adminPage.waitForSelector('.el-table', { timeout: 15_000 });

    await adminPage.click('button:has-text("新建计划")');
    const planDialog = adminPage.locator('.el-dialog:visible');
    await expect(planDialog).toBeVisible({ timeout: 10_000 });

    // Select product type
    const productTypeSelect = planDialog.locator(S.form.select('产品类型'));
    await productTypeSelect.click();
    await adminPage.waitForTimeout(500);
    const planProductOption = adminPage.locator(
      `.el-select-dropdown:visible .el-select-dropdown__item:has-text("${PRODUCT_NAME}")`
    );
    await expect(planProductOption).toBeVisible({ timeout: 8_000 });
    await planProductOption.click();

    // Quantity
    const planQtyInput = planDialog.locator(S.form.input('计划数量')).or(
      planDialog.locator('.el-input-number input').first()
    );
    await planQtyInput.click();
    await adminPage.keyboard.press('Control+A');
    await planQtyInput.fill(String(PLAN_QTY));
    await adminPage.keyboard.press('Tab');

    // Planned production date (label: 计划生产日)
    const planDateInput = planDialog.locator(S.form.input('计划生产日'));
    await planDateInput.click();
    await adminPage.keyboard.press('Control+A');
    await adminPage.keyboard.type(PLAN_DATE);
    await adminPage.keyboard.press('Enter');

    // Notes
    const notesArea = planDialog.locator('textarea').first();
    await notesArea.fill(`[E2E ${runTag}] cross-end production plan`).catch(() => {});

    // Submit plan
    const [planCreateResp] = await Promise.all([
      adminPage.waitForResponse(
        (r) =>
          r.url().includes('/production-plans') &&
          r.request().method() === 'POST' &&
          !r.url().includes('/start') &&
          !r.url().includes('/complete') &&
          !r.url().includes('/cancel') &&
          !r.url().includes('/create-batch') &&
          !r.url().includes('/generate-transfer') &&
          !r.url().includes('/sales-orders'),
        { timeout: 20_000 }
      ),
      planDialog.locator('button:has-text("确定")').last().click(),
    ]);

    const planBody = await planCreateResp.json();
    expect(planBody.success, `生产计划创建失败: ${JSON.stringify(planBody)}`).toBe(true);
    planId     = String(planBody.data?.id          || '');
    planNumber = String(planBody.data?.planNumber  || '');
    expect(planId,     '生产计划 id 不能为空').toBeTruthy();
    expect(planNumber, '生产计划 planNumber 不能为空').toBeTruthy();

    // ─── Phase 3: super_admin — Generate FMR via API ──────────────────────────
    // Same rationale as G3: canWrite UI button is unreliable; API is equivalent.
    const fmrApiResp = await adminCtx.request.post(
      `http://localhost:10010/api/mobile/${FACTORY_ID}/material-requisitions/generate`,
      {
        data: { productionPlanId: planId },
        headers: { 'Content-Type': 'application/json' },
      }
    );
    const fmrGenBody = await fmrApiResp.json();
    expect(fmrGenBody.success, `FMR API 生成失败: ${JSON.stringify(fmrGenBody)}`).toBe(true);
    fmrId = String(fmrGenBody.data?.id             || '');
    fmrNo = String(fmrGenBody.data?.requisitionNo  || '');
    expect(fmrId, 'FMR id 不能为空').toBeTruthy();
    expect(fmrNo, 'FMR requisitionNo 不能为空').toBeTruthy();

    // Navigate to FMR list — verify row appears in UI
    await adminPage.goto('/production/material-requisitions');
    await adminPage.waitForURL(/\/production\/material-requisitions/, { timeout: 20_000 });
    await adminPage.waitForSelector('.el-table', { timeout: 15_000 });
    await adminPage.waitForSelector(`.el-table__row:has-text("${fmrNo}")`, { timeout: 15_000 });
    const fmrRow = adminPage.locator(S.table.rowByText(fmrNo));
    await expect(fmrRow).toBeVisible({ timeout: 10_000 });

    await adminCtx.close();
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // Test 2: Write shared-state.json
  // ═══════════════════════════════════════════════════════════════════════════

  test('写 shared-state.json', async ({ browser }) => {
    // The preceding test must have populated these module-level variables.
    expect(soNumber,   'Test 1 did not set soNumber'  ).toBeTruthy();
    expect(planNumber, 'Test 1 did not set planNumber').toBeTruthy();
    expect(fmrNo,      'Test 1 did not set fmrNo'     ).toBeTruthy();

    // Derive a batch number following the project convention: B{YYYYMMDD}-ACY-001
    const today      = new Date();
    const datePart   = today.toISOString().slice(0, 10).replace(/-/g, '');
    const batchNumber = `B${datePart}-ACY-001`;

    writeState({
      orderCode:   soNumber,
      planCode:    planNumber,
      fmrCode:     fmrNo,
      batchNumber,
      productId:   1,
      productName: PRODUCT_NAME,
      quantity:    PLAN_QTY,
    });

    // The helper (shared-state.ts) writes to tests/v1-e2e/.shared-state.json
    const CANONICAL_FILE = path.join(__dirname, '..', '.shared-state.json');
    expect(
      fs.existsSync(CANONICAL_FILE),
      `.shared-state.json 未写入: ${CANONICAL_FILE}`
    ).toBe(true);
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // Test 3: Verify shared-state.json structure
  // ═══════════════════════════════════════════════════════════════════════════

  test('验证 shared-state.json 结构', async () => {
    const state = readState();
    expect(state, 'readState() returned null — .shared-state.json missing or invalid').not.toBeNull();

    // Required fields
    expect(state!.createdAt,    'createdAt 缺失').toBeTruthy();
    expect(state!.orderCode,    'orderCode 缺失').toBeTruthy();
    expect(state!.planCode,     'planCode 缺失').toBeTruthy();
    expect(state!.fmrCode,      'fmrCode 缺失').toBeTruthy();
    expect(state!.batchNumber,  'batchNumber 缺失').toBeTruthy();
    expect(state!.productName,  'productName 缺失').toBeTruthy();
    expect(typeof state!.quantity, 'quantity 应为 number').toBe('number');

    // Value consistency with what was written
    expect(state!.orderCode,   '').toBe(soNumber);
    expect(state!.planCode,    '').toBe(planNumber);
    expect(state!.fmrCode,     '').toBe(fmrNo);
    expect(state!.productName, '').toBe(PRODUCT_NAME);
    expect(state!.quantity,    '').toBe(PLAN_QTY);

    // createdAt is a valid ISO timestamp
    const ts = Date.parse(state!.createdAt);
    expect(isNaN(ts), 'createdAt 不是合法的 ISO 时间戳').toBe(false);
  });
});
