/**
 * Shared helpers for E2E comprehensive tests
 * - Login, page check, evidence collection, permission matrix
 */

// ===== CONSTANTS =====

export const BASE = process.env.E2E_ADMIN_URL || 'http://139.196.165.140:8086';
export const PASSWORD = process.env.E2E_PASSWORD || '123456';

// Route → Module mapping (from web-admin/src/router/index.ts)
export const ROUTE_MODULE_MAP = {
  '/dashboard': 'dashboard',
  '/dashboard/production-progress': 'dashboard',
  '/production/batches': 'production',
  '/production/plans': 'production',
  '/production/bom': 'production',
  '/production/approval': 'production',
  '/production/bom-achievement': 'production',
  '/production/process-io': 'production',
  '/production/material-requisitions': 'production',
  '/warehouse/materials': 'warehouse',
  '/warehouse/shipments': 'warehouse',
  '/warehouse/inventory': 'warehouse',
  '/warehouse/reusable-containers': 'warehouse',
  '/warehouse/material-price-trend': 'warehouse',
  '/transfer/list': 'warehouse',
  '/quality/inspections': 'quality',
  '/quality/disposals': 'quality',
  '/quality/standards': 'quality',
  '/procurement/orders': 'procurement',
  '/procurement/suppliers': 'procurement',
  '/procurement/price-lists': 'procurement',
  '/sales/orders': 'sales',
  '/sales/quotes': 'sales',
  '/sales/finished-goods': 'sales',
  '/sales/customers': 'sales',
  '/sales/shipments': 'sales',
  '/hr/employees': 'hr',
  '/hr/attendance': 'hr',
  '/hr/whitelist': 'hr',
  '/hr/departments': 'hr',
  '/equipment/list': 'equipment',
  '/equipment/maintenance': 'equipment',
  '/equipment/alerts': 'equipment',
  '/finance/costs': 'finance',
  '/finance/reports': 'finance',
  '/finance/ar-ap': 'finance',
  '/finance/sku-margin': 'finance',
  '/finance/invoices': 'finance',
  '/finance/payments': 'finance',
  '/rd/samples': 'production',       // R&D under production module
  '/rd/converted': 'production',
  '/system/users': 'system',
  '/system/roles': 'system',
  '/system/logs': 'system',
  '/system/settings': 'system',
  '/system/ai-intents': 'system',
  '/system/skill-tools': 'system',
  '/system/products': 'system',
  '/system/features': 'system',
  '/system/pos': 'system',
  '/system/work-processes': 'system',
  '/system/product-processes': 'system',
  '/system/workflow-designer': 'system',
  '/system/smartbi-config': 'system',
  '/system/smartbi-config/data-sources': 'system',
  '/system/smartbi-config/chart-templates': 'system',
  '/system/badge-generator': 'system',
  '/analytics/overview': 'analytics',
  '/analytics/trends': 'analytics',
  '/analytics/ai-reports': 'analytics',
  '/analytics/kpi': 'analytics',
  '/analytics/production-report': 'analytics',
  '/analytics/alert-dashboard': 'analytics',
  '/analytics/supply-chain': 'analytics',
  '/calibration/list': 'system',       // calibration under system
  '/scheduling/overview': 'scheduling',
  '/scheduling/plans': 'scheduling',
  '/scheduling/realtime': 'scheduling',
  '/scheduling/workers': 'scheduling',
  '/scheduling/alerts': 'scheduling',
  '/restaurant/requisitions': 'restaurant',
  '/restaurant/wastage': 'restaurant',
  '/restaurant/recipes': 'restaurant',
  '/restaurant/stocktaking': 'restaurant',
  '/restaurant/analytics': 'restaurant',
  '/restaurant/analytics/menu': 'restaurant',
  '/restaurant/analytics/stores': 'restaurant',
  '/restaurant/analytics/dianping': 'restaurant',
  '/production-analytics/production': 'analytics',
  '/production-analytics/efficiency': 'analytics',
  '/smart-bi/dashboard': 'analytics',
  '/smart-bi/finance': 'analytics',
  '/smart-bi/sales': 'analytics',
  '/smart-bi/query': 'analytics',
  '/smart-bi/query-templates': 'analytics',
  '/smart-bi/analysis': 'analytics',
  '/smart-bi/upload': 'analytics',
  '/smart-bi/data-completeness': 'analytics',
  '/smart-bi/food-kb-feedback': 'analytics',
  '/smart-bi/calibration': 'analytics',
  '/smart-bi/financial-dashboard': 'analytics',
  '/smart-bi/whatif': 'analytics',
  '/smart-bi/restaurant-v2': 'analytics',
  '/canvas-editor': '__roles__',  // special: checked by meta.roles, not module
};

// All 94 testable routes
export const ALL_ROUTES = Object.keys(ROUTE_MODULE_MAP);

// Permission matrix from store/modules/permission.ts (authoritative)
export const PERMISSION_MATRIX = {
  factory_super_admin: { dashboard:'rw',production:'rw',warehouse:'rw',quality:'rw',procurement:'rw',sales:'rw',hr:'rw',equipment:'rw',finance:'rw',system:'rw',analytics:'rw',scheduling:'rw',restaurant:'rw' },
  platform_admin:      { dashboard:'rw',production:'rw',warehouse:'rw',quality:'rw',procurement:'rw',sales:'rw',hr:'rw',equipment:'rw',finance:'rw',system:'rw',analytics:'rw',scheduling:'rw',restaurant:'rw' },
  hr_admin:            { dashboard:'r',production:'-',warehouse:'-',quality:'-',procurement:'-',sales:'-',hr:'rw',equipment:'-',finance:'-',system:'r',analytics:'-',scheduling:'-',restaurant:'-' },
  procurement_manager: { dashboard:'r',production:'r',warehouse:'r',quality:'-',procurement:'rw',sales:'-',hr:'-',equipment:'-',finance:'r',system:'-',analytics:'-',scheduling:'-',restaurant:'-' },
  sales_manager:       { dashboard:'r',production:'r',warehouse:'r',quality:'-',procurement:'-',sales:'rw',hr:'-',equipment:'-',finance:'r',system:'-',analytics:'r',scheduling:'-',restaurant:'-' },
  dispatcher:          { dashboard:'rw',production:'rw',warehouse:'r',quality:'r',procurement:'r',sales:'r',hr:'r',equipment:'r',finance:'r',system:'r',analytics:'rw',scheduling:'rw',restaurant:'r' },
  production_manager:  { dashboard:'rw',production:'rw',warehouse:'r',quality:'r',procurement:'r',sales:'r',hr:'r',equipment:'r',finance:'r',system:'r',analytics:'rw',scheduling:'rw',restaurant:'r' },
  warehouse_manager:   { dashboard:'r',production:'r',warehouse:'rw',quality:'-',procurement:'r',sales:'r',hr:'-',equipment:'-',finance:'-',system:'-',analytics:'-',scheduling:'r',restaurant:'-' },
  equipment_admin:     { dashboard:'r',production:'r',warehouse:'-',quality:'-',procurement:'-',sales:'-',hr:'-',equipment:'rw',finance:'-',system:'-',analytics:'-',scheduling:'-',restaurant:'-' },
  quality_manager:     { dashboard:'r',production:'r',warehouse:'-',quality:'rw',procurement:'-',sales:'-',hr:'-',equipment:'-',finance:'-',system:'-',analytics:'-',scheduling:'-',restaurant:'-' },
  finance_manager:     { dashboard:'r',production:'-',warehouse:'-',quality:'-',procurement:'-',sales:'r',hr:'-',equipment:'-',finance:'rw',system:'-',analytics:'r',scheduling:'-',restaurant:'-' },
  restaurant_manager:  { dashboard:'r',production:'-',warehouse:'-',quality:'-',procurement:'r',sales:'-',hr:'-',equipment:'-',finance:'r',system:'-',analytics:'r',scheduling:'-',restaurant:'rw' },
  workshop_supervisor: { dashboard:'r',production:'r',warehouse:'r',quality:'w',procurement:'-',sales:'-',hr:'r',equipment:'r',finance:'-',system:'-',analytics:'-',scheduling:'r',restaurant:'-' },
  // R4 fix: added team_leader + group_leader (were missing, caused all routes 403)
  team_leader:         { dashboard:'r',production:'r',warehouse:'-',quality:'-',procurement:'-',sales:'-',hr:'r',equipment:'r',finance:'-',system:'-',analytics:'-',scheduling:'r',restaurant:'-' },
  group_leader:        { dashboard:'r',production:'w',warehouse:'-',quality:'-',procurement:'-',sales:'-',hr:'-',equipment:'r',finance:'-',system:'-',analytics:'-',scheduling:'-',restaurant:'-' },
  viewer:              { dashboard:'r',production:'r',warehouse:'r',quality:'r',procurement:'r',sales:'r',hr:'-',equipment:'r',finance:'-',system:'-',analytics:'r',scheduling:'r',restaurant:'r' },
};

// FACTORY type blocks restaurant module
export const FACTORY_TYPE_FILTER = { restaurant: '-' };

// finance_manager route whitelist (guards.ts:22-33)
export const FINANCE_MANAGER_WHITELIST = [
  '/dashboard',
  '/smart-bi/dashboard',
  '/smart-bi/finance',
  '/smart-bi/financial-dashboard',
  '/smart-bi/sales',
  '/smart-bi/query',
  '/smart-bi/query-templates',
  '/smart-bi/analysis',
  '/403',
  '/404',
];

// canvas-editor allowed roles
export const CANVAS_EDITOR_ROLES = ['platform_admin', 'permission_admin'];

// Mobile-only roles
export const MOBILE_ONLY_ROLES = ['operator', 'quality_inspector', 'warehouse_worker'];

// ===== EXPECTED RESULT CALCULATOR =====

/**
 * Calculate expected result for a route given role and factory type
 * @returns 'OK' | '403' | '403_whitelist' | 'MOBILE_ONLY'
 */
export function expectedResult(route, role, factoryType = 'FACTORY') {
  // Mobile-only roles → redirected at login
  if (MOBILE_ONLY_ROLES.includes(role)) return 'MOBILE_ONLY';

  // finance_manager whitelist check (happens before module check in guards)
  if (role === 'finance_manager') {
    const allowed = FINANCE_MANAGER_WHITELIST.some(
      prefix => route === prefix || route.startsWith(prefix + '/')
    );
    if (!allowed) return '403_whitelist';
  }

  // canvas-editor: checked by meta.roles
  if (route === '/canvas-editor') {
    return CANVAS_EDITOR_ROLES.includes(role) ? 'OK' : '403';
  }

  // Module permission check
  const module = ROUTE_MODULE_MAP[route];
  if (!module || module === '__roles__') return 'OK'; // no module restriction

  // Apply factory type filter
  if (factoryType === 'FACTORY' && FACTORY_TYPE_FILTER[module] === '-') return '403';
  if (factoryType === 'RESTAURANT') {
    const restFilter = { production:'-', warehouse:'-', quality:'-', equipment:'-', scheduling:'-' };
    if (restFilter[module] === '-') return '403';
  }

  // Role permission
  const perms = PERMISSION_MATRIX[role];
  if (!perms) return '403';
  const level = perms[module];
  if (!level || level === '-') return '403';

  return 'OK';
}

// ===== BROWSER HELPERS =====

export async function login(page, username, password = PASSWORD) {
  try {
    await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 30000 });
    // Wait up to 30s for Vue to render login form
    await page.waitForSelector('input.el-input__inner', { timeout: 30000 });
    await page.fill('input.el-input__inner[placeholder*="用户名"]', username);
    await page.fill('input[type="password"]', password);
    await page.click('button.login-button');

    for (let i = 0; i < 20; i++) {
      await page.waitForTimeout(1000);
      const url = page.url();
      if (!url.includes('/login')) {
        if (url.includes('/mobile-only')) return 'MOBILE_ONLY';
        return 'OK';
      }
    }
    return 'LOGIN_FAILED';
  } catch (e) {
    return 'LOGIN_ERROR';
  }
}

export async function checkPage(page, route, expectedHint) {
  try {
    await page.goto(`${BASE}${route}`, { waitUntil: 'domcontentloaded', timeout: 30000 });
    // Poll for up to 15s: check URL redirects + Vue render state
    for (let i = 0; i < 30; i++) {
      await page.waitForTimeout(500);
      const url = page.url();
      if (url.includes('/403')) return '403';
      if (url.includes('/login')) return 'REDIRECT_LOGIN';
      if (url.includes('/404')) return '404';
      if (url.includes('/mobile-only')) return 'MOBILE_ONLY';
      // Check if Vue app has rendered (innerHTML > 100 means components mounted)
      const rendered = await page.evaluate(() => {
        const app = document.querySelector('#app');
        const len = app?.innerHTML?.length || 0;
        return { len, hasMenu: !!document.querySelector('.el-menu,.app-sidebar'), text: document.body?.innerText?.trim()?.length || 0 };
      });
      if (rendered.text >= 10 || rendered.hasMenu || rendered.len > 500) {
        // Page rendered — check for errors
        const hasToast = !!(await page.$('.el-message--error'));
        if (hasToast) return 'ERROR_TOAST';
        return 'OK';
      }
    }
    // Final check after 15s
    const hasToast = !!(await page.$('.el-message--error'));
    if (hasToast) return 'ERROR_TOAST';
    const finalLen = await page.evaluate(() => document.querySelector('#app')?.innerHTML?.length || 0);
    if (finalLen > 100) return 'OK';
    return 'BLANK';
  } catch (e) {
    return 'TIMEOUT';
  }
}

export function resultIcon(actual, expected) {
  if (actual === expected) return '✓';
  if (actual === 'OK' && (expected === 'OK')) return '✓';
  if (actual === '403' && (expected === '403' || expected === '403_whitelist')) return '✓';
  if (actual === '403_whitelist') return '✓'; // not a real status, just for expected
  return '✗';
}

export function isPass(actual, expected) {
  if (actual === expected) return true;
  if (actual === '403' && expected === '403_whitelist') return true;
  if (actual === 'OK' && expected === 'OK') return true;
  return false;
}

// ===== R2 HELPERS =====

/**
 * Navigate to a path using page.goto with generous timeout.
 * Waits for Vue SPA to render (table, menu, or #app content).
 * @returns 'OK' | '403' | 'TIMEOUT' | 'ERROR: ...'
 */
export async function navigateTo(page, path, { waitForTable = false, timeout = 60000 } = {}) {
  try {
    await page.goto(`${BASE}${path}`, { waitUntil: 'domcontentloaded', timeout });
    for (let i = 0; i < 50; i++) {
      await page.waitForTimeout(500);
      const url = page.url();
      if (url.includes('/403')) return '403';
      if (url.includes('/login')) return 'LOGIN';
      const state = await page.evaluate(() => ({
        table: !!document.querySelector('.el-table'),
        menu: !!document.querySelector('.el-menu,.app-sidebar'),
        app: (document.querySelector('#app')?.innerHTML?.length || 0) > 500,
      })).catch(() => ({ table: false, menu: false, app: false }));
      if (waitForTable && state.table) return 'OK';
      if (!waitForTable && (state.table || state.menu || state.app)) return 'OK';
    }
    return 'TIMEOUT';
  } catch (e) {
    return 'ERROR: ' + (e.message || '').substring(0, 60);
  }
}

/**
 * Count table rows on current page.
 * @returns { count: number } on success, or { error: string, count: null } on failure.
 * R3 fix: Don't silently return 0 on error — violates CLAUDE.md "禁止降级处理".
 */
export async function countTableRows(page) {
  try {
    const count = await page.evaluate(() =>
      document.querySelectorAll('.el-table__body-wrapper .el-table__row').length
    );
    return { count, error: null };
  } catch (e) {
    return { count: null, error: (e.message || '').substring(0, 100) };
  }
}

/**
 * Verify persistence with strict delta check.
 * R3 fix: rowsAfter > rowsBefore was too lax (delta=6 passed).
 * Now requires exactly delta === expectedDelta (default 1 for single create).
 * @returns { status: 'PASS'|'FAIL'|'WARNING', rowsBefore, rowsAfter, delta, note }
 */
export function verifyPersistence(rowsBefore, rowsAfter, expectedDelta = 1) {
  const delta = rowsAfter - rowsBefore;
  if (delta === expectedDelta) {
    return { status: 'PASS', rowsBefore, rowsAfter, delta, note: `expected delta=${expectedDelta}` };
  }
  if (delta === 0) {
    return { status: 'FAIL', rowsBefore, rowsAfter, delta, note: 'No persistence — record not created' };
  }
  // delta unexpected (too high / negative) — likely duplicate submit or dirty data
  return { status: 'WARNING', rowsBefore, rowsAfter, delta,
    note: `Unexpected delta (expected ${expectedDelta}, got ${delta}). Possible duplicate submit / dirty data / pagination issue.` };
}

/**
 * Click a button matching one of the given text patterns.
 * Falls back to primary icon button if no text match.
 * @returns clicked button text or null
 */
export async function clickButton(page, ...texts) {
  await page.waitForTimeout(1500);
  for (const text of texts) {
    const btn = await page.$(`button:has-text("${text}")`);
    if (btn && await btn.isVisible().catch(() => false)) {
      await btn.click();
      return text;
    }
  }
  // Fallback: primary button with icon
  const primary = await page.$('button.el-button--primary:has(.el-icon)');
  if (primary && await primary.isVisible().catch(() => false)) {
    const text = await primary.innerText().catch(() => 'primary-btn');
    await primary.click();
    return text;
  }
  return null;
}

/**
 * Wait for dialog/drawer to open.
 */
export async function waitForDialog(page, timeout = 8000) {
  for (let i = 0; i < timeout / 500; i++) {
    await page.waitForTimeout(500);
    const d = await page.$('.el-dialog:not([style*="display: none"]), .el-drawer');
    if (d) return d;
  }
  return null;
}

/**
 * Submit form and verify via hybrid approach:
 * 1. Primary: intercept API POST/PUT response
 * 2. Fallback: check validation errors, dialog closed, toast
 * @returns { ok: boolean, status: number, reason: string }
 */
export async function submitAndCheckResponse(page, submitTexts = ['确定', '保存', '提交'], options = {}) {
  const { factoryId = null, module: expectedModule = null } = options;
  const submitBtn = await findButton(page, submitTexts);
  if (!submitBtn) return { ok: false, status: 0, reason: 'no_submit_button' };

  // R3 fix: narrow filter to exact business endpoint if factoryId+module provided
  // Otherwise fall back to generic /api/ filter (less precise)
  const urlFilter = (factoryId && expectedModule)
    ? new RegExp(`/api/mobile/${factoryId}/${expectedModule}(/|\\?|$)`)
    : null;

  // Set up API response listener before clicking
  let apiResponse = null;
  const responsePromise = page.waitForResponse(
    r => {
      const url = r.url();
      const method = r.request().method();
      if (!['POST', 'PUT', 'PATCH'].includes(method)) return false;
      if (url.includes('/auth/')) return false;
      if (url.includes('/refresh')) return false;
      if (urlFilter) return urlFilter.test(url);
      // R3 fix: narrower generic filter — exclude common noise endpoints
      return url.includes('/api/mobile/') &&
             !url.includes('/search') &&
             !url.includes('/lookup');
    },
    { timeout: 10000 }
  ).then(r => { apiResponse = r; }).catch(() => {});

  await submitBtn.click();

  // Wait for either API response or 5s fallback window
  await Promise.race([responsePromise, page.waitForTimeout(5000)]);

  // Signal 1: API response intercepted
  if (apiResponse) {
    const status = apiResponse.status();
    // R3 fix: strict body.success !== false (undefined NO LONGER treated as success)
    let bodyOk = true;
    let bodyReason = '';
    try {
      const body = await apiResponse.json();
      if (body) {
        if (body.success === false) { bodyOk = false; bodyReason = body.message || 'success=false'; }
        // Legacy endpoints without success field: only trust HTTP status
        // (removed the `undefined === pass` loophole)
      }
    } catch { /* non-JSON */ }
    return {
      ok: status >= 200 && status < 400 && bodyOk,
      status,
      reason: bodyOk ? 'api_response' : `api_body_error: ${bodyReason}`,
      url: apiResponse.url().replace(BASE, '').split('?')[0],
    };
  }

  // Signal 2: Check for validation errors (form didn't submit)
  const validationErrors = await page.$$eval(
    '.el-form-item__error',
    els => els.map(e => e.textContent?.trim()).filter(Boolean)
  ).catch(() => []);
  if (validationErrors.length > 0) {
    return { ok: false, status: 0, reason: 'validation_error', errors: validationErrors };
  }

  // Signal 3: Dialog closed (submit succeeded, API not intercepted)
  const dialogGone = !(await page.$('.el-dialog:not([style*="display: none"])'));
  if (dialogGone) return { ok: true, status: 0, reason: 'dialog_closed' };

  // Signal 4: Success toast visible
  const toast = await page.$('.el-message--success');
  if (toast) return { ok: true, status: 0, reason: 'toast_success' };

  return { ok: false, status: 0, reason: 'no_signal_after_5s' };
}

/**
 * Fill all visible required fields in a dialog with test data.
 * Detects required fields by `.is-required` class on form items.
 */
export async function fillAllRequiredFields(page, baseName) {
  const filled = [];
  const formItems = await page.$$('.el-dialog .el-form-item.is-required, .el-drawer .el-form-item.is-required');
  for (let i = 0; i < formItems.length; i++) {
    const label = await formItems[i].$eval('.el-form-item__label', el => el.textContent?.trim()).catch(() => '');

    // R3 fix: handle Element Plus compound fields (el-select/date-picker/checkbox/radio)
    // Priority: text input → textarea → date-picker → select → radio → checkbox

    // Try text input
    let input = await formItems[i].$('input.el-input__inner:not([readonly])');
    let isTextarea = false;
    if (!input) {
      input = await formItems[i].$('textarea.el-textarea__inner');
      isTextarea = true;
    }

    // el-select (readonly input + dropdown options)
    const elSelect = await formItems[i].$('.el-select:not(.is-disabled)');
    // el-date-picker
    const dateInput = await formItems[i].$('input[placeholder*="日期"], .el-date-editor input');
    // el-radio / el-checkbox
    const firstRadio = await formItems[i].$('.el-radio:not(.is-disabled)');
    const firstCheckbox = await formItems[i].$('.el-checkbox:not(.is-disabled)');

    try {
      if (input && !isTextarea && !dateInput) {
        const current = await input.inputValue().catch(() => '');
        if (!current) {
          const value = label.includes('电话') || label.includes('手机') ? `138${Date.now().toString().slice(-8)}`
            : label.includes('邮箱') || label.includes('email') ? `e2e_${Date.now()}@test.com`
            : label.includes('地址') || label.includes('收货') ? 'E2E测试地址_上海市浦东新区'
            : `${baseName}_${i}`;
          await input.fill(value);
          // R3 fix: trigger blur to activate Element Plus validation (rules often use trigger: 'blur')
          await input.evaluate(el => el.blur());
          filled.push({ label, type: 'input', value });
        }
      } else if (isTextarea && input) {
        const current = await input.evaluate(el => el.value).catch(() => '');
        if (!current) {
          const value = label.includes('地址') || label.includes('收货') ? 'E2E测试地址_上海市浦东新区'
            : label.includes('备注') || label.includes('描述') ? 'E2E 测试数据'
            : `${baseName}_textarea`;
          await input.fill(value);
          await input.evaluate(el => el.blur());
          filled.push({ label, type: 'textarea', value });
        }
      } else if (dateInput) {
        const current = await dateInput.inputValue().catch(() => '');
        if (!current) {
          // R3 fix: fill date picker with today's date in YYYY-MM-DD format
          const today = new Date().toISOString().split('T')[0];
          await dateInput.fill(today);
          await page.keyboard.press('Enter');
          filled.push({ label, type: 'date', value: today });
        }
      } else if (elSelect) {
        // R3 fix: el-select — click to open dropdown, pick first non-disabled option
        const currentText = await elSelect.$eval('.el-select__placeholder, input', el => el.textContent || el.value).catch(() => '');
        if (!currentText || currentText.includes('请选择')) {
          await elSelect.click();
          await page.waitForTimeout(500);
          const firstOption = await page.$('.el-select-dropdown__item:not(.is-disabled)');
          if (firstOption) {
            const optText = await firstOption.innerText().catch(() => '');
            await firstOption.click();
            await page.waitForTimeout(300);
            filled.push({ label, type: 'select', value: optText });
          } else {
            await page.keyboard.press('Escape'); // close dropdown if no options
          }
        }
      } else if (firstRadio) {
        // R3 fix: click first radio option
        const checked = await firstRadio.evaluate(el => el.classList.contains('is-checked')).catch(() => false);
        if (!checked) {
          await firstRadio.click();
          filled.push({ label, type: 'radio', value: 'first option' });
        }
      } else if (firstCheckbox) {
        const checked = await firstCheckbox.evaluate(el => el.classList.contains('is-checked')).catch(() => false);
        if (!checked) {
          await firstCheckbox.click();
          filled.push({ label, type: 'checkbox', value: 'checked' });
        }
      }
    } catch (e) {
      // R3 fix: don't silently swallow — record the failure
      filled.push({ label, type: 'error', value: `Failed: ${(e.message || '').substring(0, 60)}` });
    }
  }
  return filled;
}

async function findButton(page, texts) {
  for (const text of texts) {
    const btn = await page.$(`button:has-text("${text}")`);
    if (btn && await btn.isVisible().catch(() => false)) return btn;
  }
  return null;
}

/**
 * Fill the first input in a dialog/drawer.
 */
export async function fillDialogInput(page, value, nth = 0) {
  const inputs = await page.$$('.el-dialog input.el-input__inner, .el-drawer input.el-input__inner');
  if (inputs[nth]) {
    await inputs[nth].fill(value);
    return true;
  }
  return false;
}
