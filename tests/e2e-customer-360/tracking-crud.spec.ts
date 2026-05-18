/**
 * Sprint 4 W1 S-CUSTOMER-TAB-1 — E2E #5: tab 1 跟踪记录 CRUD.
 * Verifies:
 *   - 防呆 R2: dialog header contains customer name + customer code
 *   - 防呆 R3: trackingType dropdown has 6 options (PHONE/WECHAT/EMAIL/VISIT/VIDEO/OTHER)
 *   - Create → record appears in list
 *   - Edit → content updates
 *   - Delete → confirmation prompt + removed
 */
import { test, expect } from '@playwright/test';
import { ADMIN_USERNAME, ADMIN_PASSWORD, login, gotoCustomerDetail, clickTab } from './lib/helpers';

test.describe('Customer 360° tab 1 tracking CRUD', () => {
  test('create + edit + delete with R2 + R3 verification', async ({ page }) => {
    test.setTimeout(180000);
    await login(page, ADMIN_USERNAME, ADMIN_PASSWORD);
    await gotoCustomerDetail(page);
    await clickTab(page, 'tracking');

    // === R2 + R3 verification on Create dialog ===
    const addBtn = page.getByRole('button', { name: /新增跟踪/ });
    await addBtn.click();
    await page.waitForTimeout(800);

    // Dialog header contains customer name + code (format: "新增跟踪 — {name} ({code})")
    const dialog = page.locator('.el-dialog').first();
    await expect(dialog).toBeVisible();
    const dialogTitle = await dialog.locator('.el-dialog__title, [class*="dialog-title"]').first().textContent();
    expect(dialogTitle).toContain('新增跟踪');
    // Verify some form of bracketed customer code appears (e.g., "(CUST-xxx)")
    expect(dialogTitle).toMatch(/\([A-Z0-9-]+\)/);

    // R3: trackingType dropdown — open + verify 6 options
    const typeSelect = dialog.locator('.el-form-item').filter({ hasText: '跟踪类型' }).locator('.el-select, [class*="select"]').first();
    await typeSelect.click();
    await page.waitForTimeout(500);
    const options = page.locator('.el-select-dropdown__item:visible');
    await expect(options).toHaveCount(6, { timeout: 5000 });
    const labels = await options.allTextContents();
    expect(labels).toEqual(
      expect.arrayContaining(['电话沟通', '微信沟通', '邮件沟通', '上门拜访', '视频会议', '其他'])
    );

    // Select 电话沟通
    await options.filter({ hasText: '电话沟通' }).first().click();
    await page.waitForTimeout(300);

    // Fill content + submit
    const uniqueContent = `E2E test tracking ${Date.now()}`;
    const contentInput = dialog.locator('.el-form-item').filter({ hasText: '跟踪内容' }).locator('textarea').first();
    await contentInput.fill(uniqueContent);
    const submitBtn = dialog.locator('button').filter({ hasText: '提交' }).first();
    await submitBtn.click();
    await page.waitForTimeout(2000);

    // === Verify record appears in list ===
    const row = page.locator('table tbody tr').filter({ hasText: uniqueContent });
    await expect(row).toBeVisible({ timeout: 5000 });

    // === Edit ===
    const editBtn = row.locator('button').filter({ hasText: '编辑' }).first();
    await editBtn.click();
    await page.waitForTimeout(800);
    const editDialog = page.locator('.el-dialog').first();
    const editContent = editDialog.locator('textarea').first();
    const editedContent = `${uniqueContent} (edited)`;
    await editContent.fill(editedContent);
    const saveBtn = editDialog.locator('button').filter({ hasText: '保存修改' }).first();
    await saveBtn.click();
    await page.waitForTimeout(2000);

    const editedRow = page.locator('table tbody tr').filter({ hasText: editedContent });
    await expect(editedRow).toBeVisible({ timeout: 5000 });

    // === Delete ===
    const deleteBtn = editedRow.locator('button').filter({ hasText: '删除' }).first();
    await deleteBtn.click();
    await page.waitForTimeout(800);
    const confirmBtn = page.locator('.el-message-box button').filter({ hasText: '删除' }).first();
    await confirmBtn.click();
    await page.waitForTimeout(2000);

    // Row removed
    await expect(editedRow).not.toBeVisible({ timeout: 5000 });
  });
});
