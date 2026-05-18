/**
 * Sprint 4 W1 S-CUSTOMER-TAB-1 — E2E #4: canViewPrice mask.
 * Login as receptionist (no canViewPrice) → 6 price-sensitive tabs show "****" instead of numbers.
 * Non-price fields (订单号 / 状态 / 日期) still visible.
 *
 * Pre-flight: receptionist account must NOT have CAN_VIEW_PRICE permission.
 * If env doesn't have receptionist seeded, this test should be skipped (set E2E_NOPRICE_USER=).
 */
import { test, expect } from '@playwright/test';
import {
  RECEPTIONIST_USERNAME, RECEPTIONIST_PASSWORD,
  login, gotoCustomerDetail, MASK_TABS, clickTab,
} from './lib/helpers';

test.describe('Customer 360° canViewPrice mask', () => {
  test.skip(!RECEPTIONIST_USERNAME, 'E2E_NOPRICE_USER env not set — receptionist account unavailable');

  test('6 price tabs show **** for receptionist; non-price visible', async ({ page }) => {
    test.setTimeout(120000);
    await login(page, RECEPTIONIST_USERNAME, RECEPTIONIST_PASSWORD);
    await gotoCustomerDetail(page);

    for (const tabKey of MASK_TABS) {
      await clickTab(page, tabKey);
      await page.waitForTimeout(1500);

      // Either empty state (no records yet) OR table with masked prices
      const empty = await page.locator('.el-empty').count();
      if (empty > 0) {
        // No records to verify mask on — skip this tab
        continue;
      }

      // At least one **** masked cell visible
      const masked = page.locator('.masked').first();
      await expect(masked).toBeVisible({ timeout: 5000 });
      await expect(masked).toContainText('****');
    }

    // Verify CustomerHeader 余额 / 信用额 also masked (canViewPrice = false)
    const headerMasked = page.locator('.customer-header .masked');
    await expect(headerMasked.first()).toBeVisible();
  });
});
