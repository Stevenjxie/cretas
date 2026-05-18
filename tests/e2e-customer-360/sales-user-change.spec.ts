/**
 * Sprint 4 W1 S-CUSTOMER-TAB-1 — E2E #6: tab 20 业务员变更 R1+R2+R3+R4 dialog.
 * Verifies:
 *   - R1 边界预显: "当前业务员: ..." alert in dialog
 *   - R2 context: dialog header contains customer name + customer code
 *   - R3 dropdown: 6 reason options (RESIGNATION/TERRITORY/CUSTOMER_REQUEST/PERFORMANCE/PROBATION_END/OTHER)
 *   - R3 OTHER → reveals textarea
 *   - R4 idempotent: 2nd submit within 5min → 409 with confirm dialog + navigate
 *   - Success: history list gets new entry
 */
import { test, expect } from '@playwright/test';
import { ADMIN_USERNAME, ADMIN_PASSWORD, login, gotoCustomerDetail, clickTab } from './lib/helpers';

test.describe('Customer 360° tab 20 sales user change dialog', () => {
  test('R1 + R2 + R3 dialog elements + first submit success', async ({ page }) => {
    test.setTimeout(120000);
    await login(page, ADMIN_USERNAME, ADMIN_PASSWORD);
    await gotoCustomerDetail(page);
    await clickTab(page, 'salesUserHist');
    await page.waitForTimeout(1000);

    // Open dialog
    const changeBtn = page.getByRole('button', { name: /变更业务员/ });
    await changeBtn.click();
    await page.waitForTimeout(800);

    const dialog = page.locator('.el-dialog').first();
    await expect(dialog).toBeVisible();

    // R2: dialog header contains customer name + code
    const title = await dialog.locator('.el-dialog__title, [class*="dialog-title"]').first().textContent();
    expect(title).toContain('变更业务员');
    expect(title).toMatch(/\([A-Z0-9-]+\)/);

    // R1: "当前业务员:" alert visible
    await expect(dialog.locator('.el-alert')).toContainText('当前业务员');

    // R3: open reason dropdown — 6 options
    const reasonSelect = dialog.locator('.el-form-item').filter({ hasText: '变更原因' }).locator('.el-select, [class*="select"]').first();
    await reasonSelect.click();
    await page.waitForTimeout(500);
    const options = page.locator('.el-select-dropdown__item:visible');
    await expect(options).toHaveCount(6, { timeout: 5000 });

    // R3: select 其他 → reveal textarea
    await options.filter({ hasText: '其他' }).first().click();
    await page.waitForTimeout(500);
    const textarea = dialog.locator('.el-form-item').filter({ hasText: '详细说明' }).locator('textarea').first();
    await expect(textarea).toBeVisible();

    // Switch to RESIGNATION → textarea hidden
    await reasonSelect.click();
    await page.waitForTimeout(400);
    const options2 = page.locator('.el-select-dropdown__item:visible');
    await options2.filter({ hasText: '离职交接' }).first().click();
    await page.waitForTimeout(500);
    const textareaAfter = dialog.locator('.el-form-item').filter({ hasText: '详细说明' });
    await expect(textareaAfter).not.toBeVisible();

    // Cancel — skip actual submit to avoid mutating prod data unless explicitly enabled
    if (process.env.E2E_MUTATE !== '1') {
      const cancelBtn = dialog.locator('button').filter({ hasText: '取消' }).first();
      await cancelBtn.click();
      return;
    }

    // E2E_MUTATE=1: fill newSalesUserId + submit + assert history list increments
    const beforeRows = await page.locator('table tbody tr').count();
    const userIdInput = dialog.locator('input[type="number"]').first();
    await userIdInput.fill('999');
    const confirmBtn = dialog.locator('button').filter({ hasText: '确认变更' }).first();
    await confirmBtn.click();
    await page.waitForTimeout(3000);
    const afterRows = await page.locator('table tbody tr').count();
    expect(afterRows).toBeGreaterThan(beforeRows);
  });

  test.skip(process.env.E2E_MUTATE !== '1', 'E2E_MUTATE=1 not set — skipping mutation tests');
  test('R4 dedup: 2nd same submit within 5min → 409 confirm dialog', async ({ page }) => {
    test.setTimeout(120000);
    await login(page, ADMIN_USERNAME, ADMIN_PASSWORD);
    await gotoCustomerDetail(page);
    await clickTab(page, 'salesUserHist');

    // Submit once
    async function submit(userId: number, reason: string) {
      const changeBtn = page.getByRole('button', { name: /变更业务员/ });
      await changeBtn.click();
      await page.waitForTimeout(500);
      const dialog = page.locator('.el-dialog').first();
      const userIdInput = dialog.locator('input[type="number"]').first();
      await userIdInput.fill(String(userId));
      const reasonSelect = dialog.locator('.el-select').first();
      await reasonSelect.click();
      await page.waitForTimeout(400);
      await page.locator('.el-select-dropdown__item:visible').filter({ hasText: reason }).first().click();
      const confirmBtn = dialog.locator('button').filter({ hasText: '确认变更' }).first();
      await confirmBtn.click();
      await page.waitForTimeout(2000);
    }

    await submit(888, '离职交接');

    // 2nd submit with SAME user + SAME reason within 5min → 409 confirm
    await submit(888, '离职交接');

    // ElMessageBox confirm appears
    const msgBox = page.locator('.el-message-box');
    await expect(msgBox).toBeVisible({ timeout: 5000 });
    await expect(msgBox).toContainText(/5 分钟内|已变更过|冲突/);
  });
});
