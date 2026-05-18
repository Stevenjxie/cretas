/**
 * Sprint 4 W1 S-CUSTOMER-TAB-1 — shared E2E helpers.
 */
import { type Page, expect } from '@playwright/test';

export const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5173';

// Default test account. Override via env when running against prod-like environments.
export const ADMIN_USERNAME = process.env.E2E_ADMIN_USER || 'factory_admin1';
export const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASS || '123456';

// Receptionist / low-privilege account (no canViewPrice) — for RBAC mask test.
export const RECEPTIONIST_USERNAME = process.env.E2E_NOPRICE_USER || 'sales_receptionist1';
export const RECEPTIONIST_PASSWORD = process.env.E2E_NOPRICE_PASS || '123456';

// Test customer (must exist in DB). For F006 environment use 六腾门 customer.
export const TEST_CUSTOMER_ID = process.env.E2E_CUSTOMER_ID || '';

export async function login(page: Page, username: string, password: string): Promise<void> {
  await page.goto(BASE_URL);
  await page.waitForTimeout(1500);

  // Already logged in — skip form
  if (!page.url().includes('login') && page.url() !== BASE_URL + '/') {
    return;
  }

  const usernameInput = page.locator('input[type="text"], input[placeholder*="用户名"], input[placeholder*="账号"]').first();
  const passwordInput = page.locator('input[type="password"]').first();
  await usernameInput.fill(username);
  await passwordInput.fill(password);
  await page.waitForTimeout(300);

  const loginBtn = page.locator('button').filter({ hasText: /登录|Login|登 录/ }).first();
  await loginBtn.click();
  await page.waitForURL((url) => !url.toString().includes('login'), { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(1500);
}

/**
 * Navigate to a customer's 360° detail page. If TEST_CUSTOMER_ID is set, use it;
 * otherwise pick the first customer from /sales/customers list page.
 */
export async function gotoCustomerDetail(page: Page, customerId?: string, tab?: string): Promise<string> {
  let id = customerId || TEST_CUSTOMER_ID;
  if (!id) {
    // Land on list page and click first row
    await page.goto(`${BASE_URL}/sales/customers`);
    await page.waitForTimeout(2000);
    const firstRow = page.locator('table tbody tr').first();
    await firstRow.waitFor({ state: 'visible', timeout: 10000 });
    // Click the "查看" / detail link if present, else the name cell
    const detailLink = firstRow.locator('a, button').filter({ hasText: /查看|详情|Detail/ }).first();
    if (await detailLink.count() > 0) {
      await detailLink.click();
    } else {
      await firstRow.locator('td').first().click();
    }
    await page.waitForURL(/\/sales\/customers\/[^\/]+/, { timeout: 10000 });
    const m = page.url().match(/\/sales\/customers\/([^?\/]+)/);
    id = m?.[1] || '';
  } else {
    const target = `${BASE_URL}/sales/customers/${id}${tab ? `?tab=${tab}` : ''}`;
    await page.goto(target);
    await page.waitForTimeout(2000);
  }
  await expect(page).toHaveURL(/\/sales\/customers\/[^\/]+/);
  return id;
}

/** All 21 tab keys in canonical order (matches detail.vue TAB_DEFS). */
export const TAB_KEYS = [
  'tracking', 'wechat', 'call', 'sms', 'audio', 'email',
  'orders', 'samples', 'quotes', 'products',
  'campaign', 'opportunity',
  'itemStats', 'shipAddr',
  'invoices', 'payments', 'returns',
  'aftersales', 'priceMemory',
  'salesUserHist', 'attachments',
] as const;

export const TAB_LABEL_MAP: Record<string, string> = {
  tracking: '跟踪记录',
  wechat: '微信记录',
  call: '通话记录',
  sms: '短信记录',
  audio: '谈话录音',
  email: '邮件列表',
  orders: '销售单',
  samples: '样品单',
  quotes: '报价单',
  products: '产品',
  campaign: '活动管理',
  opportunity: '商机管理',
  itemStats: '商品统计',
  shipAddr: '收件地址',
  invoices: '开票',
  payments: '收款',
  returns: '退货',
  aftersales: '售后',
  priceMemory: '价格记忆',
  salesUserHist: '业务员变更',
  attachments: '文件附件',
};

export const DEFER_TABS = new Set([
  'wechat', 'call', 'sms', 'audio', 'email', 'campaign', 'opportunity', 'aftersales',
]);

export const MASK_TABS = ['orders', 'quotes', 'itemStats', 'invoices', 'payments', 'returns'];

export async function clickTab(page: Page, tabKey: string): Promise<void> {
  const label = TAB_LABEL_MAP[tabKey];
  if (!label) throw new Error(`Unknown tab key: ${tabKey}`);
  const tab = page.locator('.el-tabs__item').filter({ hasText: new RegExp(`^${label}$`) }).first();
  await tab.click();
  await page.waitForTimeout(800); // allow lazy-load + KeepAlive activation
}
