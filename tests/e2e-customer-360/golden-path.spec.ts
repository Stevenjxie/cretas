/**
 * Sprint 4 W1 S-CUSTOMER-TAB-1 — E2E #1: 21 tab golden path.
 * Verifies all 21 tabs render (or show placeholder), tab change triggers URL ?tab= update,
 * browser back returns to list (not stacked between tabs).
 */
import { test, expect } from '@playwright/test';
import {
  ADMIN_USERNAME, ADMIN_PASSWORD, login, gotoCustomerDetail,
  TAB_KEYS, TAB_LABEL_MAP, DEFER_TABS, clickTab,
} from './lib/helpers';

test.describe('Customer 360° golden path', () => {
  test('21 tabs all switch + URL updates', async ({ page }) => {
    test.setTimeout(120000);
    await login(page, ADMIN_USERNAME, ADMIN_PASSWORD);
    await gotoCustomerDetail(page);

    // Wait for el-tabs to render
    await expect(page.locator('.el-tabs__item').first()).toBeVisible({ timeout: 10000 });
    const tabItems = page.locator('.el-tabs__item');
    await expect(tabItems).toHaveCount(21);

    for (const key of TAB_KEYS) {
      await clickTab(page, key);
      // URL contains ?tab=<key>
      await expect(page).toHaveURL(new RegExp(`tab=${key}`));
      // For defer tabs: PlaceholderTab visible
      if (DEFER_TABS.has(key)) {
        await expect(page.locator('.el-empty')).toBeVisible({ timeout: 5000 });
      } else {
        // Real tab: either data or empty/skeleton/error — but the tab pane must be active
        const activeTab = page.locator('.el-tabs__item.is-active').first();
        await expect(activeTab).toContainText(TAB_LABEL_MAP[key]);
      }
    }

    // Browser back → list page (not stacked between tabs)
    await page.goBack();
    await expect(page).toHaveURL(/\/sales\/customers(\?|$)/);
  });
});
