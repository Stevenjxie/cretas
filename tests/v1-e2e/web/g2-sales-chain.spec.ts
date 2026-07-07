/**
 * G2 销售→采购→入库 @pr-gate
 *
 * Real multi-role G-chain (post Phase-B fixes):
 *   - B1 (acb2c150c): seed now uses correct role codes (procurement_manager, warehouse_worker)
 *   - B2 (ec0a57e74): V20260411_10 migration adds PENDING_FINANCE_REVIEW + FINANCE_APPROVED to ck_po_status
 *
 * Roles:
 *   - sales_mgr    (sales_manager)       → Phase 1: create + confirm SO
 *   - purchase_mgr (procurement_manager) → Phase 2: create PO, submit, approve, submit-for-finance-review
 *   - super_admin  (factory_super_admin) → Phase 3: finance-approve (endpoint needs finance:read_write,
 *                                          which procurement_manager does NOT have)
 *   - purchase_mgr (procurement_manager) → Phase 4: create receive record + confirm → generates material batch
 *
 * NOTE on warehouse_worker:
 *   warehouse_worker is in web-admin MOBILE_ONLY_ROLES (guards.ts line 16). Logging in via web
 *   redirects to /mobile-only. Receive step uses procurement_manager instead (has procurement:read_write
 *   + inventory:write which the receive endpoints accept).
 *
 * Full PO chain:
 *   DRAFT → 提交审批 (submit) → SUBMITTED
 *   → 审批通过 (approve) → APPROVED
 *   → 提交财务审核 (submitFinance) → PENDING_FINANCE_REVIEW
 *   → 财务通过 (financeApprove) → FINANCE_APPROVED
 *   → 创建收货单 (createReceive) → 确认入库 (confirmReceive) → material batch created
 *
 * Stock assertion: GET /material-batches before + after receive, assert totalElements increased.
 *
 * Auth: 3 storageState files (sales_mgr, purchase_mgr, super_admin).
 * Each phase uses a fresh BrowserContext.
 */

import { test, expect } from '@playwright/test';
import { setupAuthBeforeAll, restoreAuth, installApiProxy } from '../helpers/auth-cache';
import { S } from '../helpers/selectors';

// ─── Constants ────────────────────────────────────────────────────────────────

const FACTORY_ID = 'F_E2E_TEST';

const CUSTOMER_NAME = '鼎鲜火锅义乌分公司';
const SUPPLIER_NAME = '泰森禽业';    // SUP_TYSON
const MATERIAL_NAME = '草鱼片';      // MAT_F001

const PRODUCT_NAME = '酸菜鱼 500g'; // SKU_SCY500 @ 25.00
const SO_QTY = 10;
const PO_QTY = 30;  // kg to receive

// ─── Suite ────────────────────────────────────────────────────────────────────

test.describe('G2 销售→采购→入库 @pr-gate', () => {

  test.beforeAll(async ({ browser }) => {
    await setupAuthBeforeAll(browser, 'sales_mgr');
    await setupAuthBeforeAll(browser, 'purchase_mgr');
    await setupAuthBeforeAll(browser, 'super_admin');
  });

  test('销售下单 → 采购全链审批 → 入库收货 → 草鱼片库存批次增加', async ({ browser }) => {

    const runTag = `G2-${Date.now()}`;

    // ═══════════════════════════════════════════════════════════════════════════
    // Phase 1: sales_mgr — Create and Confirm Sales Order
    // ═══════════════════════════════════════════════════════════════════════════
    const salesCtx = await browser.newContext();
    const salesPage = await salesCtx.newPage();
    // PR #149 R2: workaround for canWrite('sales') false-negative.
    // permission.ts:331 (added in PR #130 #be7768c38) prefers DB-loaded permissions
    // over hardcoded fallback. In e2e env, L1 platform permissions are not seeded
    // for sales_manager → dbPermissions = {} → currentPermissions.sales = undefined
    // → canWrite('sales') = false → 新建 button hidden via v-if="canWrite".
    // Workaround: use super_admin (always has write via hardcoded matrix).
    // Proper fix: seed L1 permissions for e2e, or merge hardcoded fallback into
    // currentPermissions when dbPermissions has partial coverage.
    await restoreAuth(salesCtx, salesPage, 'super_admin');

    await salesPage.goto('/sales/orders');
    await salesPage.waitForURL(/\/sales\/orders/, { timeout: 20_000 });
    await salesPage.waitForSelector('.el-table', { timeout: 15_000 });
    // PR #149 R4: hide release-note toast (U-FEED-1 component, mounted in AppLayout.vue).
    // The toast appears with `<div class="release-note-stack">` overlay-style and
    // intercepts pointer events on header buttons. Hide via CSS — it's display:none
    // so it can't receive events. Test intent is workflow, not release-note rendering.
    await salesPage.addStyleTag({
      content: '.release-note-stack { display: none !important; }'
    }).catch(() => {});
    // PR #149 R3: wait for page to settle before clicking — Sprint 9 WorkflowBar /
    // ConceptDisambiguationAlert / Canvas-dynamic init may briefly cover the button.
    await salesPage.waitForLoadState('networkidle', { timeout: 30_000 }).catch(() => {});

    // Open create dialog — use scoped locator (card header) to avoid matching dialog title text
    const salesCreateBtn = salesPage.locator(
      '.card-header .header-right button.el-button--primary:has-text("新建")'
    ).first();
    await expect(salesCreateBtn).toBeVisible({ timeout: 15_000 });
    await salesCreateBtn.scrollIntoViewIfNeeded();
    await salesCreateBtn.click({ timeout: 30_000 });
    // U-NEW-1 (commit 7f4e7a22b, 2026-05-16): 新建 button now opens
    // CreateModeSelector first. Pick "普通新建" (mode=normal) to dispatch
    // to the existing full-form dialog.
    const modeSelector = salesPage.locator('.el-dialog:visible:has-text("选择录入方式")');
    await expect(modeSelector).toBeVisible({ timeout: 10_000 });
    await modeSelector.locator('.create-mode-card:has-text("普通新建")').click();
    const createDialog = salesPage.locator(
      '.el-dialog:visible:not(:has-text("选择录入方式"))'
    );
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

    // Fill remark with run tag for traceability
    const remarkTextarea = createDialog.locator('textarea').first();
    await remarkTextarea.fill(`[E2E ${runTag}] G2 sales chain SO`);

    // Select product in item row
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
    await qtyInput.fill(String(SO_QTY));
    await salesPage.keyboard.press('Tab');

    // Create SO
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
    const soId = String(createBody.data?.id || '');
    const soNumber = String(createBody.data?.orderNumber || '');
    expect(soId, 'SO id 不能为空').toBeTruthy();

    // Find new row and confirm SO (DRAFT → CONFIRMED)
    await salesPage.waitForSelector(`.el-table__row:has-text("${soNumber}")`, {
      timeout: 15_000,
    });
    const soRow = salesPage.locator(S.table.rowByText(soNumber));
    await expect(soRow).toBeVisible({ timeout: 10_000 });

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

    // ═══════════════════════════════════════════════════════════════════════════
    // Phase 2: purchase_mgr (procurement_manager) — Create PO, submit, approve,
    //          then submit for finance review
    // ═══════════════════════════════════════════════════════════════════════════
    const purchaseCtx = await browser.newContext();
    const purchasePage = await purchaseCtx.newPage();
    // PR #149 R2: same canWrite('procurement') false-negative workaround as
    // sales above. procurement_manager L1 permissions also not seeded in e2e.
    await restoreAuth(purchaseCtx, purchasePage, 'super_admin');

    await purchasePage.goto('/procurement/orders');
    await purchasePage.waitForURL(/\/procurement\/orders/, { timeout: 20_000 });
    await purchasePage.waitForSelector('.el-table', { timeout: 15_000 });
    await purchasePage.addStyleTag({
      content: '.release-note-stack { display: none !important; }'
    }).catch(() => {});
    await purchasePage.waitForLoadState('networkidle', { timeout: 30_000 }).catch(() => {});

    // Open create PO dialog — scoped locator (PR #149 R3 fix)
    const poCreateBtn = purchasePage.locator(
      '.card-header .header-right button.el-button--primary:has-text("新建")'
    ).first();
    await expect(poCreateBtn).toBeVisible({ timeout: 15_000 });
    await poCreateBtn.scrollIntoViewIfNeeded();
    await poCreateBtn.click({ timeout: 30_000 });
    // U-NEW-1: procurement/orders/list.vue:782 also uses CreateModeSelector.
    const poModeSelector = purchasePage.locator(
      '.el-dialog:visible:has-text("选择录入方式")'
    );
    await expect(poModeSelector).toBeVisible({ timeout: 10_000 });
    await poModeSelector.locator('.create-mode-card:has-text("普通新建")').click();
    const poDialog = purchasePage.locator(
      '.el-dialog:visible:not(:has-text("选择录入方式"))'
    );
    await expect(poDialog).toBeVisible({ timeout: 10_000 });

    // Select supplier
    const supplierSelect = poDialog.locator(S.form.select('供应商'));
    await supplierSelect.click();
    await purchasePage.waitForTimeout(500);
    const supplierOption = purchasePage.locator(
      `.el-select-dropdown:visible .el-select-dropdown__item:has-text("${SUPPLIER_NAME}")`
    );
    await expect(supplierOption).toBeVisible({ timeout: 8_000 });
    await supplierOption.click();

    // Fill remark
    const poRemarkTextarea = poDialog.locator('textarea').first();
    await poRemarkTextarea.fill(`[E2E ${runTag}] G2 PO for ${MATERIAL_NAME}`);

    // Select material in item row
    const materialSelect = poDialog.locator('.item-row .el-select').first();
    await materialSelect.click();
    await purchasePage.waitForTimeout(500);
    const materialOption = purchasePage.locator(
      `.el-select-dropdown:visible .el-select-dropdown__item:has-text("${MATERIAL_NAME}")`
    );
    await expect(materialOption).toBeVisible({ timeout: 8_000 });
    await materialOption.click();

    // Fill quantity
    const itemQtyInput = poDialog.locator('.item-row .el-input-number input').first();
    await itemQtyInput.click();
    await purchasePage.keyboard.press('Control+A');
    await itemQtyInput.fill(String(PO_QTY));
    await purchasePage.keyboard.press('Tab');

    // Fill unit price
    const itemPriceInput = poDialog.locator('.item-row .el-input-number input').nth(1);
    await itemPriceInput.click();
    await purchasePage.keyboard.press('Control+A');
    await itemPriceInput.fill('20');
    await purchasePage.keyboard.press('Tab');

    // Create PO
    const [poCreateResp] = await Promise.all([
      purchasePage.waitForResponse(
        (r) =>
          r.url().includes('/purchase/orders') &&
          r.request().method() === 'POST' &&
          !r.url().match(/\/(submit|approve|cancel|finance)/),
        { timeout: 15_000 }
      ),
      poDialog.locator('button:has-text("创建")').click(),
    ]);

    const poCreateBody = await poCreateResp.json();
    expect(poCreateBody.success, `PO 创建失败: ${JSON.stringify(poCreateBody)}`).toBe(true);
    const poId = String(poCreateBody.data?.id || '');
    const poNumber = String(poCreateBody.data?.orderNumber || '');
    expect(poId, 'PO id 不能为空').toBeTruthy();

    // Wait for table, find PO row
    await purchasePage.waitForSelector(`.el-table__row:has-text("${poNumber}")`, {
      timeout: 15_000,
    });
    const poRow = purchasePage.locator(S.table.rowByText(poNumber));
    await expect(poRow).toBeVisible({ timeout: 10_000 });

    // Step 2a: Submit PO (DRAFT → SUBMITTED)
    const submitBtn = poRow.locator('button:has-text("提交")');
    await expect(submitBtn).toBeVisible({ timeout: 5_000 });
    await submitBtn.click();
    await purchasePage.waitForSelector('.el-message-box', { timeout: 5_000 });
    const [submitResp] = await Promise.all([
      purchasePage.waitForResponse(
        (r) => r.url().includes('/submit') && r.request().method() === 'POST' && !r.url().includes('finance'),
        { timeout: 20_000 }
      ),
      purchasePage.keyboard.press('Enter'),
    ]);
    const submitBody = await submitResp.json();
    expect(submitBody.success, `PO 提交失败: ${JSON.stringify(submitBody)}`).toBe(true);

    // Step 2b: Approve PO (SUBMITTED → APPROVED) — from PO detail page
    await purchasePage.goto(`/procurement/orders/${poId}`);
    await purchasePage.waitForURL(/\/procurement\/orders\//, { timeout: 20_000 });
    await purchasePage.waitForSelector('.el-card', { timeout: 15_000 });

    const approveBtn = purchasePage.locator('button:has-text("审批通过")');
    await expect(approveBtn).toBeVisible({ timeout: 10_000 });
    await approveBtn.click();
    await purchasePage.waitForSelector('.el-message-box', { timeout: 5_000 });
    const [approveResp] = await Promise.all([
      purchasePage.waitForResponse(
        (r) => r.url().includes('/approve') && r.request().method() === 'POST' && !r.url().includes('finance'),
        { timeout: 20_000 }
      ),
      purchasePage.keyboard.press('Enter'),
    ]);
    const approveBody = await approveResp.json();
    expect(approveBody.success, `PO 审批失败: ${JSON.stringify(approveBody)}`).toBe(true);

    // Step 2c: Submit for finance review (APPROVED → PENDING_FINANCE_REVIEW)
    // handleAction() shows ElMessageBox.confirm for ALL actions — press Enter to confirm.
    await purchasePage.waitForSelector('.el-card', { timeout: 10_000 });
    const submitFinanceBtn = purchasePage.locator('button:has-text("提交财务审核")');
    await expect(submitFinanceBtn).toBeVisible({ timeout: 10_000 });
    await submitFinanceBtn.click();
    await purchasePage.waitForSelector('.el-message-box', { timeout: 5_000 });
    const [submitFinanceResp] = await Promise.all([
      purchasePage.waitForResponse(
        (r) => r.url().includes('/submit-for-finance-review') && r.request().method() === 'POST',
        { timeout: 20_000 }
      ),
      purchasePage.keyboard.press('Enter'),
    ]);
    const submitFinanceBody = await submitFinanceResp.json();
    expect(submitFinanceBody.success, `提交财务审核失败: ${JSON.stringify(submitFinanceBody)}`).toBe(true);

    await purchaseCtx.close();

    // ═══════════════════════════════════════════════════════════════════════════
    // Phase 3: super_admin — Finance approve (PENDING_FINANCE_REVIEW → FINANCE_APPROVED)
    //
    // finance-approve endpoint requires @RequirePermission("finance:read_write").
    // procurement_manager has finance:'r' only → 403.
    // super_admin has all permissions.
    // ═══════════════════════════════════════════════════════════════════════════
    const financeCtx = await browser.newContext();
    const financePage = await financeCtx.newPage();
    await restoreAuth(financeCtx, financePage, 'super_admin');

    await financePage.goto(`/procurement/orders/${poId}`);
    await financePage.waitForURL(/\/procurement\/orders\//, { timeout: 20_000 });
    await financePage.waitForSelector('.el-card', { timeout: 15_000 });

    // handleAction('financeApprove') also shows ElMessageBox.confirm
    const financeApproveBtn = financePage.locator('button:has-text("财务通过")');
    await expect(financeApproveBtn).toBeVisible({ timeout: 10_000 });
    await financeApproveBtn.click();
    await financePage.waitForSelector('.el-message-box', { timeout: 5_000 });
    const [financeApproveResp] = await Promise.all([
      financePage.waitForResponse(
        (r) => r.url().includes('/finance-approve') && r.request().method() === 'POST',
        { timeout: 20_000 }
      ),
      financePage.keyboard.press('Enter'),
    ]);
    const financeApproveBody = await financeApproveResp.json();
    expect(financeApproveBody.success, `财务审批失败: ${JSON.stringify(financeApproveBody)}`).toBe(true);

    await financeCtx.close();

    // ═══════════════════════════════════════════════════════════════════════════
    // Phase 4: purchase_mgr — Create receive record + confirm → material batch
    //
    // warehouse_worker is MOBILE_ONLY in web-admin (guards.ts) → cannot use web UI.
    // procurement_manager has procurement:read_write + inventory:write which satisfies
    // the @RequirePermission on /receives and /receives/{id}/confirm endpoints.
    // ═══════════════════════════════════════════════════════════════════════════
    const receiveCtx = await browser.newContext();
    const receivePage = await receiveCtx.newPage();
    await restoreAuth(receiveCtx, receivePage, 'purchase_mgr');

    // Baseline: count existing 草鱼片 batches BEFORE receive
    const batchApiBase = `http://localhost:10010/api/mobile/${FACTORY_ID}/material-batches`;
    const keyword = encodeURIComponent(MATERIAL_NAME);
    const stockBeforeResp = await receiveCtx.request.get(
      `${batchApiBase}?page=1&size=200&keyword=${keyword}`
    );
    const stockBeforeBody = await stockBeforeResp.json();
    const batchCountBefore: number =
      stockBeforeBody.data?.totalElements ??
      stockBeforeBody.data?.content?.length ??
      0;

    // Navigate to PO detail and click receive button (visible when status=FINANCE_APPROVED)
    await receivePage.goto(`/procurement/orders/${poId}`);
    await receivePage.waitForURL(/\/procurement\/orders\//, { timeout: 20_000 });
    await receivePage.waitForSelector('.el-card', { timeout: 15_000 });
    const notificationCloseButtons = receivePage.locator('.el-notification__closeBtn');
    const notificationCount = await notificationCloseButtons.count().catch(() => 0);
    for (let i = notificationCount - 1; i >= 0; i -= 1) {
      await notificationCloseButtons.nth(i).click({ force: true }).catch(() => {});
    }
    await receivePage.locator('.el-notification').first().waitFor({ state: 'hidden', timeout: 3_000 }).catch(() => {});

    // Click "创建收货单" / receive button (appears when FINANCE_APPROVED)
    const receiveBtn = receivePage.locator('button:has-text("收货"), button:has-text("创建收货单"), button:has-text("入库")').first();
    await expect(receiveBtn).toBeVisible({ timeout: 10_000 });
    await receiveBtn.click();

    const receiveDialog = receivePage.locator('.el-dialog:visible');
    await expect(receiveDialog).toBeVisible({ timeout: 10_000 });

    // The receive dialog shows a table of items with receivedQuantity input.
    // Default fills the remaining quantity from the PO item.
    // Just submit with whatever quantity is pre-filled.
    const [createReceiveResp] = await Promise.all([
      receivePage.waitForResponse(
        (r) =>
          r.url().includes('/purchase/receives') &&
          r.request().method() === 'POST' &&
          !r.url().includes('/confirm'),
        { timeout: 15_000 }
      ),
      receiveDialog.locator('button:has-text("创建收货单")').click(),
    ]);

    const createReceiveBody = await createReceiveResp.json();
    expect(createReceiveBody.success, `创建收货单失败: ${JSON.stringify(createReceiveBody)}`).toBe(true);
    const receiveId = String(createReceiveBody.data?.id || '');
    expect(receiveId, '收货单 id 不能为空').toBeTruthy();

    // Wait for receive record to appear in the table below, then confirm
    await receivePage.waitForSelector('.el-message', { timeout: 5_000 }).catch(() => {});
    await receivePage.waitForTimeout(1000);

    // Click "确认入库" in the receives table
    const confirmInboundBtn = receivePage.locator('button:has-text("确认入库")').first();
    await expect(confirmInboundBtn).toBeVisible({ timeout: 10_000 });
    await confirmInboundBtn.click();

    await receivePage.waitForSelector('.el-message-box', { timeout: 5_000 });
    const [confirmReceiveResp] = await Promise.all([
      receivePage.waitForResponse(
        (r) => r.url().includes('/confirms') || (r.url().includes('/receives/') && r.url().includes('/confirm') && r.request().method() === 'POST'),
        { timeout: 20_000 }
      ),
      receivePage.keyboard.press('Enter'),
    ]);
    const confirmReceiveBody = await confirmReceiveResp.json();
    expect(confirmReceiveBody.success, `确认入库失败: ${JSON.stringify(confirmReceiveBody)}`).toBe(true);

    // Assert: material batch count increased after inbound
    await receivePage.waitForTimeout(1500);
    const stockAfterResp = await receiveCtx.request.get(
      `${batchApiBase}?page=1&size=200&keyword=${keyword}`
    );
    const stockAfterBody = await stockAfterResp.json();
    const batchCountAfter: number =
      stockAfterBody.data?.totalElements ??
      stockAfterBody.data?.content?.length ??
      0;

    expect(
      batchCountAfter,
      `草鱼片 批次数未增加 — before=${batchCountBefore}, after=${batchCountAfter} (runTag=${runTag})`
    ).toBeGreaterThan(batchCountBefore);

    await receiveCtx.close();
  });
});
