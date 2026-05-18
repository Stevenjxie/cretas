/**
 * Sprint 4 W1 S-CUSTOMER-TAB-1 — E2E #3: URL state restore.
 * Bookmark /sales/customers/:id?tab=invoices → on load, lands on invoices tab.
 * Refresh page → still on invoices tab.
 */
import { test, expect } from '@playwright/test';
import { ADMIN_USERNAME, ADMIN_PASSWORD, login, gotoCustomerDetail, TAB_LABEL_MAP } from './lib/helpers';

test.describe('Customer 360° URL state restore', () => {
  test('?tab=invoices land on invoices tab + refresh keeps it', async ({ page }) => {
    test.setTimeout(60000);
    await login(page, ADMIN_USERNAME, ADMIN_PASSWORD);

    const customerId = await gotoCustomerDetail(page, undefined, 'invoices');

    // Verify "开票" tab is active
    const activeTab = page.locator('.el-tabs__item.is-active').first();
    await expect(activeTab).toContainText(TAB_LABEL_MAP['invoices']);
    await expect(page).toHaveURL(/tab=invoices/);

    // Refresh
    await page.reload();
    await page.waitForTimeout(2000);

    // Still on invoices tab
    const activeAfterRefresh = page.locator('.el-tabs__item.is-active').first();
    await expect(activeAfterRefresh).toContainText(TAB_LABEL_MAP['invoices']);
    await expect(page).toHaveURL(/tab=invoices/);
  });

  test('?tab=salesUserHist restores tab 20', async ({ page }) => {
    test.setTimeout(60000);
    await login(page, ADMIN_USERNAME, ADMIN_PASSWORD);
    await gotoCustomerDetail(page, undefined, 'salesUserHist');
    const activeTab = page.locator('.el-tabs__item.is-active').first();
    await expect(activeTab).toContainText(TAB_LABEL_MAP['salesUserHist']);
  });
});
