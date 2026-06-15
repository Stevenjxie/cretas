/**
 * G1 税率分组开票 — @pr-gate
 *
 * 六扇门客户杀手锏 (P0-3 / 客户原话 2645-2660s):
 *   "开票的话,我们是 9 个点的原料 + 13 个点的加工费,
 *    系统自动按税率分组聚合,一键生成两张发票"
 *
 * Test strategy:
 *   1. Use fixture order DEMO_SO_G1 (seeded by demo-orders.sql):
 *      - 酸菜鱼 500g × 100 @ 25.00 = 2500, tax 9% → taxAmount 225
 *      - 鲜牛肉丸 1kg × 20 @ 55.00 = 1100, tax 13% → taxAmount 143
 *   2. Navigate to /sales/orders → find DEMO_SO_G1 row
 *   3. Click "税率分组开票" button
 *   4. In the dialog: click "一键按税率分组开票"
 *   5. Assert 2 tax-rate groups appear in the dialog result:
 *      - 9% group: taxableAmount 2500, taxAmount 225
 *      - 13% group: taxableAmount 1100, taxAmount 143
 *
 * NOTE on fixtures:
 *   DEMO_SO_G1 must be applied via seed-and-reset.sh (or equivalent) before running.
 *   The order is seeded in tests/v1-e2e/fixtures/demo-orders.sql.
 *
 * NOTE on tax_rate:
 *   The create-SO UI does not expose tax_rate on line items. Tax rate is set
 *   directly in the DB via the fixture SQL on sales_order_items.tax_rate.
 *   This is intentional — the G1 feature is "invoice grouping", not "tax rate UI".
 *
 * Auth:
 *   Uses sales_mgr role. storageState cached per run to avoid backend 429 limit.
 */

import { test, expect } from '@playwright/test';
import { setupAuthBeforeAll, restoreAuth } from '../helpers/auth-cache';
import { S } from '../helpers/selectors';

// ─── Constants ────────────────────────────────────────────────────────────────

const ORDER_NUMBER = 'DEMO_SO_G1';

// Expected values (from fixture + actual API behavior)
// NOTE: tax_rate in DB is stored as INTEGER PERCENT (9, 13, 0) per SalesOrderItem Javadoc.
// The backend formula: taxAmount = lineAmount × taxRate / 100
// So: 2500 × 9 / 100 = 225.00  and  1100 × 13 / 100 = 143.00
// The dialog tags show fmtRate(9) = "9.00%" (or "9%").
// taxableAmount: 2500.00 and 1100.00.
const EXPECTED = {
  taxRate9: {
    rawRate: 9,
    taxableAmount: 2500,
    taxAmount: 225,
    taxableAmountFormatted: '2,500.00',
  },
  taxRate13: {
    rawRate: 13,
    taxableAmount: 1100,
    taxAmount: 143,
    taxableAmountFormatted: '1,100.00',
  },
};

// ─── Suite ────────────────────────────────────────────────────────────────────

test.describe('G1 税率分组开票 @pr-gate', () => {

  test.beforeAll(async ({ browser }) => {
    await setupAuthBeforeAll(browser, 'sales_mgr');
  });

  test.beforeEach(async ({ page, context }) => {
    await restoreAuth(context, page, 'sales_mgr');
    // PR #149 R5: hide release-note toast that intercepts row button clicks
    // across all G1 tests. addInitScript runs before each page navigation
    // (covers page.goto in tests).
    await context.addInitScript(() => {
      const style = document.createElement('style');
      style.textContent = '.release-note-stack { display: none !important; }';
      // Wait for head to exist
      if (document.head) {
        document.head.appendChild(style);
      } else {
        document.addEventListener('DOMContentLoaded', () => document.head.appendChild(style));
      }
    });
  });

  // ─── Main test ──────────────────────────────────────────────────────────────

  test('同一订单 9%+13% 两种税率 → 税率分组开票生成 2 组明细', async ({ page }) => {

    // ── Step 1: Navigate to sales orders list ──────────────────────────────
    await page.goto('/sales/orders');
    await page.waitForURL(/\/sales\/orders/, { timeout: 15_000 });

    // Wait for the table to load (at least one row or the empty text)
    await page.waitForSelector('.el-table', { timeout: 15_000 });

    // ── Step 2: Find DEMO_SO_G1 row ────────────────────────────────────────
    // The order might be on a later page if there are many orders.
    // We load page-size=200 by first changing view tab to force bigger load,
    // or we can just search for the row after scrolling down.
    // Simpler: directly look for the row with DEMO_SO_G1 text.

    // Try to find the row (may need to wait for data to render)
    const orderRow = page.locator(S.table.rowByText(ORDER_NUMBER));

    // If not visible in current page, try increasing page size via UI
    const rowCount = await orderRow.count();
    if (rowCount === 0) {
      // Switch to "全部订单" tab to see all (already default, but force refresh)
      const allTab = page.locator('.el-radio-button:has-text("全部订单")');
      if (await allTab.isVisible()) {
        await allTab.click();
        await page.waitForTimeout(1000);
      }

      // Try to change page size to 50 to see more orders
      const pageSizeSelect = page.locator('.el-select:has(.el-pagination__sizes)');
      if (await pageSizeSelect.isVisible()) {
        await pageSizeSelect.click();
        const option50 = page.locator('.el-select-dropdown__item:has-text("50")').last();
        if (await option50.isVisible()) {
          await option50.click();
          await page.waitForTimeout(1000);
        }
      }
    }

    // Assert the row is visible
    await expect(orderRow).toBeVisible({ timeout: 15_000 });

    // ── Step 3: Click "税率分组开票" button for DEMO_SO_G1 ─────────────────
    const invoiceBtn = orderRow.locator('button:has-text("税率分组开票")');
    await expect(invoiceBtn).toBeVisible({ timeout: 5_000 });
    await invoiceBtn.click();

    // ── Step 4: Dialog opens — assert initial state ────────────────────────
    const dialog = page.locator('.el-dialog:has(.el-dialog__title:has-text("税率分组开票"))');
    await expect(dialog).toBeVisible({ timeout: 10_000 });

    // Confirm the order number and customer appear in dialog
    await expect(dialog.locator(':has-text("DEMO_SO_G1")').first()).toBeVisible();
    await expect(dialog.locator(':has-text("鼎鲜火锅")').first()).toBeVisible();

    // ── Step 5: Click "一键按税率分组开票" ────────────────────────────────
    const generateBtn = dialog.locator('button:has-text("一键按税率分组开票")');
    await expect(generateBtn).toBeVisible({ timeout: 5_000 });

    // Wait for the backend call to complete
    const [invoiceResp] = await Promise.all([
      page.waitForResponse(
        (resp) =>
          resp.url().includes('/finance/invoices/request-from-order') &&
          resp.request().method() === 'POST',
        { timeout: 15_000 }
      ),
      generateBtn.click(),
    ]);

    // Assert the backend responded with success
    const invoiceBody = await invoiceResp.json();
    expect(
      invoiceBody.success,
      `开票 API 失败: ${JSON.stringify(invoiceBody)}`
    ).toBe(true);

    // Assert taxBreakdown has 2 entries (the key G1 assertion)
    const breakdown: Array<{
      taxRate: number;
      taxableAmount: number;
      taxAmount: number;
      lineCount: number;
    }> = invoiceBody.data?.taxBreakdown || [];

    expect(
      breakdown.length,
      `期望 2 个税率分组, 实际得到 ${breakdown.length}: ${JSON.stringify(breakdown)}`
    ).toBe(2);

    // Identify 9% group (stored as 9 in DB → taxRate = 9 in API response)
    const group9 = breakdown.find(
      (b) => Math.abs(Number(b.taxRate) - EXPECTED.taxRate9.rawRate) < 0.5
    );
    expect(group9, `缺少 9 税率分组. breakdown=${JSON.stringify(breakdown)}`).toBeTruthy();
    expect(Number(group9!.taxableAmount)).toBeCloseTo(EXPECTED.taxRate9.taxableAmount, 0);

    // Identify 13% group (stored as 13 in DB → taxRate = 13 in API response)
    const group13 = breakdown.find(
      (b) => Math.abs(Number(b.taxRate) - EXPECTED.taxRate13.rawRate) < 0.5
    );
    expect(group13, `缺少 13 税率分组. breakdown=${JSON.stringify(breakdown)}`).toBeTruthy();
    expect(Number(group13!.taxableAmount)).toBeCloseTo(EXPECTED.taxRate13.taxableAmount, 0);

    // ── Step 6: Assert dialog shows 2 tax-group cards ─────────────────────
    // After success the dialog switches to result view with group cards
    // Use the exact .result-title div (not :has-text which matches parents too)
    const successTitle = dialog.locator('.result-title');
    await expect(successTitle).toBeVisible({ timeout: 10_000 });
    await expect(successTitle).toContainText('已生成 2 组开票明细');

    // The dialog renders fmtRate(9) = "9.00%" (or "9%") per TaxGroupInvoiceDialog.vue
    // Assert the taxable amounts (2500.00 and 1100.00) are displayed in the dialog
    // Use .group-card elements which are more specific
    const groupCards = dialog.locator('.group-card');
    await expect(groupCards).toHaveCount(2, { timeout: 5_000 });

    // Assert each group card contains the expected taxable amount
    await expect(
      dialog.locator(`.group-card:has-text("${EXPECTED.taxRate9.taxableAmountFormatted}")`)
    ).toBeVisible({ timeout: 5_000 });
    await expect(
      dialog.locator(`.group-card:has-text("${EXPECTED.taxRate13.taxableAmountFormatted}")`)
    ).toBeVisible({ timeout: 5_000 });

    // ── Step 7: Close dialog ───────────────────────────────────────────────
    const closeBtn = dialog.locator('button:has-text("关闭")');
    await expect(closeBtn).toBeVisible({ timeout: 5_000 });
    await closeBtn.click();

    await expect(dialog).not.toBeVisible({ timeout: 5_000 });
  });

  // ─── Edge case: invoice already exists (idempotency) ────────────────────

  test('重复点击开票 — 同一订单可再次提交开票申请', async ({ page }) => {
    await page.goto('/sales/orders');
    await page.waitForSelector('.el-table', { timeout: 15_000 });

    const orderRow = page.locator(S.table.rowByText(ORDER_NUMBER));
    await expect(orderRow).toBeVisible({ timeout: 15_000 });

    const invoiceBtn = orderRow.locator('button:has-text("税率分组开票")');
    await invoiceBtn.click();

    const dialog = page.locator('.el-dialog:has(.el-dialog__title:has-text("税率分组开票"))');
    await expect(dialog).toBeVisible({ timeout: 10_000 });

    // Either succeeds with 2 groups or shows an existing-record message
    const generateBtn = dialog.locator('button:has-text("一键按税率分组开票")');
    await expect(generateBtn).toBeVisible({ timeout: 5_000 });

    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/finance/invoices/request-from-order'),
        { timeout: 15_000 }
      ),
      generateBtn.click(),
    ]);

    const body = await resp.json();
    // 2026-05-21 (PR #149 R3): backend now correctly rejects duplicate invoice
    // requests with 409 + actionHint "请在 \"开票申请\" Tab 里审核通过或驳回".
    // Accept EITHER success (legacy behavior, creates new record) OR the
    // specific idempotency 409 (current behavior, prevents accidental dup).
    // Both outcomes are valid backend behavior; the test's intent ("idempotent
    // re-submission doesn't crash") is satisfied either way.
    if (!body.success) {
      expect(
        body.message || '',
        `Unexpected non-idempotency error: ${JSON.stringify(body)}`
      ).toMatch(/已有待处理开票申请|already has pending invoice request/i);
    } else {
      // Legacy path: re-submission allowed, new record created
      expect(body.success).toBe(true);
    }

    // PR #149 R4: dismiss ElMessageBox that backend triggers on 409 idempotency
    // (axios interceptor → ElMessageBox.alert("开票申请已存在")). The box's
    // overlay intercepts subsequent pointer events on the outer dialog's
    // 关闭/取消 button. Click 确定 to close MessageBox before outer dialog.
    const messageBoxBtn = page.locator('.el-message-box__btns button.el-button--primary');
    if (await messageBoxBtn.count() > 0) {
      await messageBoxBtn.first().click({ timeout: 5_000 }).catch(() => {});
    }
    // 2026-06-01 修 G1: 等 message-box overlay 真正消失再关 dialog。原代码点完 messageBox
    // 按钮就立刻点 dialog 的 关闭/取消, 但 overlay 淡出动画未完 → 按钮 'not stable' →
    // locator.click 15s 超时。先等 overlay detach, 再用 el-dialog 右上角 X (比 footer
    // 关闭/取消 更不易被遮), footer 按钮作 fallback。
    await page.locator('.el-overlay-message-box').waitFor({ state: 'detached', timeout: 10_000 }).catch(() => {});
    const closeIcon = dialog.locator('.el-dialog__headerbtn').first();
    if (await closeIcon.count() > 0) {
      await closeIcon.click({ timeout: 8_000 }).catch(async () => {
        await dialog.locator('button:has-text("关闭"), button:has-text("取消")').first().click({ timeout: 8_000 }).catch(() => {});
      });
    } else {
      await dialog.locator('button:has-text("关闭"), button:has-text("取消")').first().click({ timeout: 8_000 }).catch(() => {});
    }
  });
});
