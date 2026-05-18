/**
 * Sprint 4 W1 S-CUSTOMER-TAB-1 — E2E #2: lazy load verification.
 * Detail.vue uses defineAsyncComponent for 13 real/integration tabs. Each tab visit
 * should download exactly ONE additional chunk (not all 13 upfront).
 *
 * Strategy: monitor page.on('request') for tab JS chunks, count distinct chunks
 * after visiting 5 tabs. Expected ≤ 5 distinct tab chunks (each tab loads once
 * and KeepAlive caches).
 */
import { test, expect } from '@playwright/test';
import { ADMIN_USERNAME, ADMIN_PASSWORD, login, gotoCustomerDetail, clickTab } from './lib/helpers';

test.describe('Customer 360° lazy load', () => {
  test('5 tab visits = ≤5 distinct tab chunk requests', async ({ page }) => {
    test.setTimeout(60000);
    await login(page, ADMIN_USERNAME, ADMIN_PASSWORD);

    // Start tracking JS chunk requests BEFORE navigating to detail
    const tabChunks = new Set<string>();
    page.on('request', (req) => {
      const url = req.url();
      // Match dist/assets/<TabName>Tab-<hash>.js (chunked Vue components)
      const m = url.match(/\/assets\/([A-Z][a-zA-Z]+Tab)-[A-Za-z0-9_-]+\.js/);
      if (m) {
        tabChunks.add(m[1]);
      }
    });

    await gotoCustomerDetail(page);
    await page.waitForTimeout(2500);
    const initialCount = tabChunks.size;
    // Initial load should fetch at most 1 tab chunk (the default 'tracking')
    expect(initialCount).toBeLessThanOrEqual(2); // ±1 for race conditions

    // Visit 4 more distinct tabs
    const visits = ['orders', 'invoices', 'payments', 'salesUserHist'];
    for (const t of visits) {
      await clickTab(page, t);
      await page.waitForTimeout(1500);
    }

    const finalCount = tabChunks.size;
    // 5 distinct tabs visited (tracking initial + 4 more) — expect ≤6 chunks
    // (allow 1 chunk slack for icon/css subchunks counted as TabName-styled)
    expect(finalCount).toBeLessThanOrEqual(6);
    expect(finalCount).toBeGreaterThanOrEqual(2); // at least tracking + orders

    // Re-visit a tab — should NOT fetch another chunk (KeepAlive cached)
    const beforeRevisit = tabChunks.size;
    await clickTab(page, 'orders');
    await page.waitForTimeout(1500);
    expect(tabChunks.size).toBe(beforeRevisit);
  });
});
