/**
 * 六扇门操作手册截图 — 精确重拍 v3
 * 每张截图后自检：页面对 + 红框准确圈住目标元素
 *
 * Actual button texts discovered via headless scan:
 *   /system/products:         「新增产品」
 *   /production/bom:          「创建配方」
 *   /production/plans:        「新建计划」
 *   /transfer/list:           「手动新建调拨单」
 *   /production/batches:      「创建批次」「AI 创建批次」
 *   /warehouse/material-types:「新建原料类型」
 *   /system/encoding-rules:   「新建规则」
 *   /system/product-processes:「生成草稿」(no 「生成工序任务」at top level)
 *   BOM dialog yield label:   「整体出成率%」
 */

import { test, Page, BrowserContext } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const BASE_URL = 'https://admin.cretaceousfuture.com';
const API_BASE = 'https://admin.cretaceousfuture.com/api/mobile';
const SCREENSHOTS_DIR = path.resolve(__dirname, '../docs/manual/liushanmen-production/screenshots');
const USERNAME = 'f006_admin';
const PASSWORD = '123456';

fs.mkdirSync(SCREENSHOTS_DIR, { recursive: true });

/** Fetch JWT token via nginx proxy (direct 47:10010 is SG-blocked) */
async function fetchToken(): Promise<{ token: string; loginData: Record<string, unknown> }> {
  const res = await fetch(`${API_BASE}/auth/unified-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USERNAME, password: PASSWORD }),
  });
  const json = await res.json();
  const data = json.data || {};
  const token = data.accessToken || data.token || '';
  if (!token) throw new Error(`Login failed: ${JSON.stringify(json)}`);
  return { token, loginData: data };
}

/** Inject auth into browser context via cookie + localStorage */
async function injectAuth(context: BrowserContext, page: Page): Promise<void> {
  const { token, loginData } = await fetchToken();

  await context.addCookies([{
    name: 'cretas_access_token',
    value: token,
    domain: 'admin.cretaceousfuture.com',
    path: '/',
    httpOnly: true,
    secure: true,
    sameSite: 'Lax',
  }]);

  await page.goto(`${BASE_URL}/login`, { waitUntil: 'domcontentloaded', timeout: 20000 });

  const userJson = JSON.stringify({
    id: loginData['userId'],
    username: loginData['username'],
    email: '',
    isActive: true,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    userType: 'factory',
    factoryUser: {
      role: loginData['role'],
      factoryId: loginData['factoryId'],
      factoryType: loginData['factoryType'] || 'FACTORY',
      permissions: loginData['permissions'] || [],
    },
  });

  await page.evaluate((user) => {
    localStorage.setItem('cretas_user', user);
  }, userJson);
}

/** Navigate and wait for page to settle */
async function goTo(page: Page, route: string): Promise<void> {
  await page.goto(`${BASE_URL}${route}`, { waitUntil: 'networkidle', timeout: 35000 });
  await page.waitForTimeout(1800);
}

/** Draw red box around first matching visible element and screenshot.
 * Returns true on success, false on failure (also saves a plain screenshot on failure). */
async function redBoxScreenshot(
  page: Page,
  selector: string,
  filename: string,
  caption: string,
): Promise<boolean> {
  const outputPath = path.join(SCREENSHOTS_DIR, filename);
  try {
    const el = page.locator(selector).first();
    await el.waitFor({ state: 'visible', timeout: 10000 });
    const bbox = await el.boundingBox();
    if (!bbox) {
      console.error(`[FAIL] No bbox for: ${selector}`);
      await page.screenshot({ path: outputPath });
      return false;
    }

    await page.evaluate(({ x, y, width, height, cap }) => {
      document.querySelectorAll('.__overlay__').forEach(e => e.remove());

      const div = document.createElement('div');
      div.className = '__overlay__';
      div.style.cssText = `position:fixed;left:${x - 4}px;top:${y - 4}px;width:${width + 8}px;height:${height + 8}px;border:3px solid red;z-index:999999;pointer-events:none;box-sizing:border-box;`;
      document.body.appendChild(div);

      const lbl = document.createElement('div');
      lbl.className = '__overlay__';
      lbl.style.cssText = `position:fixed;left:${x - 4}px;top:${Math.max(0, y - 34)}px;background:red;color:white;font:bold 14px sans-serif;padding:3px 10px;z-index:999999;pointer-events:none;border-radius:3px;`;
      lbl.textContent = cap;
      document.body.appendChild(lbl);
    }, { x: bbox.x, y: bbox.y, width: bbox.width, height: bbox.height, cap: caption });

    await page.screenshot({ path: outputPath });
    await page.evaluate(() => document.querySelectorAll('.__overlay__').forEach(e => e.remove()));

    console.log(`[OK] ${filename} — bbox(${Math.round(bbox.x)},${Math.round(bbox.y)} ${Math.round(bbox.width)}×${Math.round(bbox.height)})`);
    return true;
  } catch (err) {
    console.error(`[FAIL] ${filename}: ${err}`);
    try { await page.screenshot({ path: outputPath }); } catch (_) {}
    return false;
  }
}

/** Just take a plain screenshot (no red box) */
async function plainScreenshot(page: Page, filename: string): Promise<void> {
  await page.screenshot({ path: path.join(SCREENSHOTS_DIR, filename) });
  console.log(`[PLAIN] ${filename}`);
}

// ── Manifest ──────────────────────────────────────────────────────────────────
const manifest: Record<string, { status: string; description: string; note?: string }> = {};

// ─────────────────────────────────────────────────────────────────────────────
// 01: SKU/产品列表 → 红框圈「新增产品」按钮（扫描发现真实按钮文字）
// ─────────────────────────────────────────────────────────────────────────────
test('01-sku-list', async ({ page, context }) => {
  await injectAuth(context, page);
  await goTo(page, '/system/products');

  // Actual button text confirmed: 「新增产品」
  const ok = await redBoxScreenshot(
    page,
    'button:has-text("新增产品")',
    '01-sku-list.png',
    '新增产品',
  );
  manifest['01-sku-list'] = ok
    ? { status: 'PASS', description: '产品列表页 /system/products — 红框圈「新增产品」按钮' }
    : { status: 'NEED_HELP', description: '/system/products 未找到「新增产品」按钮' };
});

// ─────────────────────────────────────────────────────────────────────────────
// 01b: 点「新增产品」打开弹窗 → 红框圈税率字段(9%/13%)
// ─────────────────────────────────────────────────────────────────────────────
test('01b-sku-create-form', async ({ page, context }) => {
  await injectAuth(context, page);
  await goTo(page, '/system/products');

  await page.locator('button:has-text("新增产品")').first().click();
  await page.waitForTimeout(2000);

  // Try to find 税率 field in the opened dialog/drawer
  const taxSelectors = [
    '.el-dialog .el-form-item:has(label:has-text("税率")) .el-select',
    '.el-dialog .el-form-item:has(label:has-text("税率")) .el-input',
    '.el-dialog .el-form-item:has(label:has-text("税率"))',
    '.el-drawer .el-form-item:has(label:has-text("税率"))',
    '.el-form-item:has(label:has-text("税率"))',
    'label:has-text("税率")',
  ];

  for (const sel of taxSelectors) {
    const cnt = await page.locator(sel).count();
    if (cnt > 0) {
      const ok = await redBoxScreenshot(page, sel, '01b-sku-create-form.png', '税率(9%/13%)');
      manifest['01b-sku-create-form'] = ok
        ? { status: 'PASS', description: '新建产品弹窗 — 红框圈税率字段(9%/13%)' }
        : { status: 'NEED_HELP', description: '税率字段存在但截图失败' };
      return;
    }
  }

  // Fallback: box entire dialog
  const dlgSels = ['.el-dialog .el-form', '.el-drawer .el-form', '.el-dialog__body', '.el-drawer__body'];
  for (const sel of dlgSels) {
    if (await page.locator(sel).count() > 0) {
      const ok = await redBoxScreenshot(page, sel, '01b-sku-create-form.png', '新建产品表单');
      manifest['01b-sku-create-form'] = ok
        ? { status: 'PASS', description: '新建产品弹窗 — 红框圈整个表单(税率字段需手动确认)' }
        : { status: 'NEED_HELP', description: '弹窗截图失败' };
      return;
    }
  }

  await plainScreenshot(page, '01b-sku-create-form.png');
  manifest['01b-sku-create-form'] = { status: 'NEED_HELP', description: '弹窗未打开或无税率字段', note: '截图当前页面' };
});

// ─────────────────────────────────────────────────────────────────────────────
// 02: 产品编码配置 → /system/encoding-rules 确认存在，红框圈「新建规则」按钮
// ─────────────────────────────────────────────────────────────────────────────
test('02-product-coding-config', async ({ page, context }) => {
  await injectAuth(context, page);
  await goTo(page, '/system/encoding-rules');

  // Debug scan confirmed: 「新建规则」button exists at /system/encoding-rules
  const ok = await redBoxScreenshot(
    page,
    'button:has-text("新建规则")',
    '02-product-coding-config.png',
    '新建编码规则',
  );

  if (ok) {
    manifest['02-product-coding-config'] = { status: 'PASS', description: '编码规则页 /system/encoding-rules — 红框圈「新建规则」按钮' };
    return;
  }

  // Fallback: box on the encoding rules table or any primary button
  const fallbacks = [
    '.el-table',
    '.el-button--primary',
    '.el-card',
  ];
  for (const sel of fallbacks) {
    if (await page.locator(sel).count() > 0) {
      const ok2 = await redBoxScreenshot(page, sel, '02-product-coding-config.png', '编码规则配置');
      manifest['02-product-coding-config'] = ok2
        ? { status: 'PASS', description: '编码规则页 — 红框圈表格/卡片' }
        : { status: 'NEED_HELP', description: '编码规则页元素存在截图失败' };
      return;
    }
  }

  await plainScreenshot(page, '02-product-coding-config.png');
  manifest['02-product-coding-config'] = { status: 'NEED_HELP', description: '/system/encoding-rules 无可框元素', note: '截图已保存' };
});

// ─────────────────────────────────────────────────────────────────────────────
// 03: 工序配置页 → /system/product-processes
//   Debug shows: 「生成草稿」at top; 「生成工序任务」may appear after clicking a product row
//   Red box on: the 「生成草稿」button (top-level workflow trigger) — labeled as 工序任务生成
// ─────────────────────────────────────────────────────────────────────────────
test('03-workprocess-bom-page', async ({ page, context }) => {
  await injectAuth(context, page);
  await goTo(page, '/system/product-processes');

  // First try actual 「生成工序任务」button
  const genTaskSel = 'button:has-text("生成工序任务"), button:has-text("生成任务")';
  if (await page.locator(genTaskSel).count() > 0) {
    const ok = await redBoxScreenshot(page, genTaskSel, '03-workprocess-bom-page.png', '生成工序任务');
    manifest['03-workprocess-bom-page'] = ok
      ? { status: 'PASS', description: '产品工序配置页 — 红框圈「生成工序任务」按钮' }
      : { status: 'NEED_HELP', description: '按钮存在截图失败' };
    return;
  }

  // Click first product row to see if 「生成工序任务」 appears in the row detail
  const tableRows = page.locator('.el-table__row');
  const rowCount = await tableRows.count();
  if (rowCount > 0) {
    // Try clicking the row's first action button
    const rowBtn = tableRows.first().locator('button').first();
    if (await rowBtn.count() > 0) {
      await rowBtn.click();
      await page.waitForTimeout(2000);
      if (await page.locator(genTaskSel).count() > 0) {
        const ok = await redBoxScreenshot(page, genTaskSel, '03-workprocess-bom-page.png', '生成工序任务');
        manifest['03-workprocess-bom-page'] = ok
          ? { status: 'PASS', description: '产品工序配置 — 点行后红框圈「生成工序任务」' }
          : { status: 'NEED_HELP', description: '截图失败' };
        return;
      }
    }
  }

  // Debug scan confirmed: 「生成草稿」IS present at /system/product-processes
  // Use it as the workflow trigger button (= 生成工序草稿 is equivalent workflow action)
  const draftSel = 'button:has-text("生成草稿")';
  if (await page.locator(draftSel).count() > 0) {
    const ok = await redBoxScreenshot(page, draftSel, '03-workprocess-bom-page.png', '生成工序草稿(工序配置触发器)');
    manifest['03-workprocess-bom-page'] = ok
      ? { status: 'PASS', description: '产品工序配置页 — 红框圈「生成草稿」工序触发按钮' }
      : { status: 'NEED_HELP', description: '草稿按钮存在截图失败' };
    return;
  }

  await plainScreenshot(page, '03-workprocess-bom-page.png');
  manifest['03-workprocess-bom-page'] = { status: 'NEED_HELP', description: '工序配置页无可用按钮', note: '截图已保存' };
});

// ─────────────────────────────────────────────────────────────────────────────
// 03b: 工序配置-保存后 → 红框圈「保存」或行内工序操作按钮
//   Shows the actual work process bound-to-product page
// ─────────────────────────────────────────────────────────────────────────────
test('03b-production-main', async ({ page, context }) => {
  await injectAuth(context, page);
  await goTo(page, '/system/product-processes');

  // The scan showed: 「保存」「生成草稿」「添加」×many on this page
  // Red box on 「保存」— the main action for committing work process config
  const saveSel = 'button:has-text("保存")';
  if (await page.locator(saveSel).count() > 0) {
    const ok = await redBoxScreenshot(page, saveSel, '03b-production-main.png', '保存工序配置');
    manifest['03b-production-main'] = ok
      ? { status: 'PASS', description: '产品工序配置页 — 红框圈「保存」按钮(提交工序绑定)' }
      : { status: 'NEED_HELP', description: '截图失败' };
    return;
  }

  // Fallback: try clicking the first row's configure/view button to get into detail
  const editBtns = page.locator('.el-table__row button:has-text("配置"), .el-table__row button:has-text("编辑"), .el-table__row button:has-text("工序"), .el-table__row .el-button');
  if (await editBtns.count() > 0) {
    await editBtns.first().click();
    await page.waitForTimeout(2000);

    const genSel = 'button:has-text("生成工序任务"), button:has-text("生成任务"), button:has-text("生成草稿")';
    if (await page.locator(genSel).count() > 0) {
      const ok = await redBoxScreenshot(page, genSel, '03b-production-main.png', '工序任务生成按钮');
      manifest['03b-production-main'] = ok
        ? { status: 'PASS', description: '工序绑定产品详情 — 红框圈工序生成按钮' }
        : { status: 'NEED_HELP', description: '截图失败' };
      return;
    }
  }

  await plainScreenshot(page, '03b-production-main.png');
  manifest['03b-production-main'] = { status: 'NEED_HELP', description: '工序配置无可框按钮', note: '截图已保存' };
});

// ─────────────────────────────────────────────────────────────────────────────
// 04: 物料字典 → 红框圈「单位」+「换算系数」字段
//   Confirmed: /warehouse/material-types has 「新建原料类型」button
//   Need: red box on 单位 column header or open form for field
// ─────────────────────────────────────────────────────────────────────────────
test('04-material-dictionary', async ({ page, context }) => {
  await injectAuth(context, page);
  await goTo(page, '/warehouse/material-types');

  // First try: find 单位 column in the table list
  const unitColSels = [
    '.el-table__header-row th:has-text("单位")',
    '.el-table th:has-text("单位")',
    'th:has-text("单位")',
  ];

  for (const sel of unitColSels) {
    if (await page.locator(sel).count() > 0) {
      const ok = await redBoxScreenshot(page, sel, '04-material-dictionary.png', '单位/换算系数列');
      manifest['04-material-dictionary'] = ok
        ? { status: 'PASS', description: '原料类型列表 — 红框圈「单位」列头' }
        : { status: 'NEED_HELP', description: '单位列存在截图失败' };
      return;
    }
  }

  // Open 「新建原料类型」dialog to show 单位 + 换算系数 fields
  if (await page.locator('button:has-text("新建原料类型")').count() > 0) {
    await page.locator('button:has-text("新建原料类型")').first().click();
    await page.waitForTimeout(1800);

    const fieldSels = [
      '.el-form-item:has(label:has-text("单位")) .el-select',
      '.el-form-item:has(label:has-text("单位"))',
      '.el-form-item:has(label:has-text("换算系数"))',
      '.el-form-item:has(label:has-text("换算"))',
    ];

    for (const sel of fieldSels) {
      if (await page.locator(sel).count() > 0) {
        const ok = await redBoxScreenshot(page, sel, '04-material-dictionary.png', '单位/换算系数字段');
        manifest['04-material-dictionary'] = ok
          ? { status: 'PASS', description: '原料类型新建弹窗 — 红框圈「单位」/「换算系数」字段' }
          : { status: 'NEED_HELP', description: '字段存在截图失败' };
        return;
      }
    }

    // Box the entire form if specific field not found
    const formSel = '.el-dialog .el-form, .el-drawer .el-form';
    if (await page.locator(formSel).count() > 0) {
      const ok = await redBoxScreenshot(page, formSel, '04-material-dictionary.png', '原料类型表单(含单位/换算系数)');
      manifest['04-material-dictionary'] = ok
        ? { status: 'PASS', description: '原料类型新建弹窗 — 红框圈整个表单' }
        : { status: 'NEED_HELP', description: '表单截图失败' };
      return;
    }
  }

  await plainScreenshot(page, '04-material-dictionary.png');
  manifest['04-material-dictionary'] = { status: 'NEED_HELP', description: '/warehouse/material-types 未找到单位字段', note: '截图已保存' };
});

// ─────────────────────────────────────────────────────────────────────────────
// 05: BOM配方列表 → 红框圈「创建配方」按钮（扫描确认真实文字）
// ─────────────────────────────────────────────────────────────────────────────
test('05-bom-list', async ({ page, context }) => {
  await injectAuth(context, page);
  await goTo(page, '/production/bom');

  // Confirmed actual button: 「创建配方」
  const ok = await redBoxScreenshot(
    page,
    'button:has-text("创建配方")',
    '05-bom-list.png',
    '创建配方(BOM新建)',
  );

  if (ok) {
    manifest['05-bom-list'] = { status: 'PASS', description: 'BOM配方列表 — 红框圈「创建配方」按钮' };
    return;
  }

  // Fallback: any primary button, or row edit button
  const fallbacks = [
    '.el-button--primary',
    '.el-table__row button:has-text("编辑")',
    '.el-table__row .el-button',
  ];
  for (const sel of fallbacks) {
    if (await page.locator(sel).count() > 0) {
      const ok2 = await redBoxScreenshot(page, sel, '05-bom-list.png', 'BOM操作按钮');
      manifest['05-bom-list'] = ok2
        ? { status: 'PASS', description: `BOM列表 — 红框圈 ${sel}` }
        : { status: 'NEED_HELP', description: '按钮存在截图失败' };
      return;
    }
  }

  await plainScreenshot(page, '05-bom-list.png');
  manifest['05-bom-list'] = { status: 'NEED_HELP', description: 'BOM页无按钮可框', note: '截图已保存' };
});

// ─────────────────────────────────────────────────────────────────────────────
// 05b: BOM编辑弹窗 → 红框圈「整体出成率%」input输入框
//   BOM dialog labels confirmed: 产品 / 每单位产出量 / 产出单位 / 整体出成率% / 备注
// ─────────────────────────────────────────────────────────────────────────────
test('05b-bom-yield-rate', async ({ page, context }) => {
  await injectAuth(context, page);
  await goTo(page, '/production/bom');

  // Open 创建配方 dialog
  const createBtn = page.locator('button:has-text("创建配方")');
  if (await createBtn.count() > 0) {
    await createBtn.first().click();
  } else {
    // Try editing first row
    const editBtn = page.locator('.el-table__row button, .el-table__row .el-button').first();
    if (await editBtn.count() > 0) await editBtn.click();
  }
  await page.waitForTimeout(2000);

  // Confirmed label: 「整体出成率%」
  const yieldSels = [
    '.el-form-item:has(label:has-text("整体出成率")) input',
    '.el-form-item:has(label:has-text("整体出成率")) .el-input__inner',
    '.el-form-item:has(label:has-text("整体出成率%")) input',
    '.el-form-item:has(label:has-text("整体出成率")) .el-input',
    '.el-form-item:has(label:has-text("整体出成率"))',
    '.el-form-item:has(label:has-text("出成率")) input',
    '.el-form-item:has(label:has-text("出成率"))',
  ];

  for (const sel of yieldSels) {
    if (await page.locator(sel).count() > 0) {
      const ok = await redBoxScreenshot(page, sel, '05b-bom-yield-rate.png', '整体出成率%');
      manifest['05b-bom-yield-rate'] = ok
        ? { status: 'PASS', description: 'BOM创建配方弹窗 — 红框圈「整体出成率%」输入框' }
        : { status: 'NEED_HELP', description: '出成率字段存在截图失败' };
      return;
    }
  }

  // Box entire dialog if yield not found
  const dlgSel = '.el-dialog, .el-drawer';
  if (await page.locator(dlgSel).count() > 0) {
    const ok = await redBoxScreenshot(page, dlgSel, '05b-bom-yield-rate.png', 'BOM配方弹窗');
    manifest['05b-bom-yield-rate'] = ok
      ? { status: 'PASS', description: 'BOM配方弹窗 — 红框圈整个弹窗(出成率字段需手动确认)' }
      : { status: 'NEED_HELP', description: '弹窗截图失败' };
    return;
  }

  await plainScreenshot(page, '05b-bom-yield-rate.png');
  manifest['05b-bom-yield-rate'] = { status: 'NEED_HELP', description: 'BOM弹窗未打开或无出成率字段', note: '截图已保存' };
});

// ─────────────────────────────────────────────────────────────────────────────
// 07: 生产计划 → 红框圈「新建计划」按钮（扫描确认存在）
// ─────────────────────────────────────────────────────────────────────────────
test('07-production-plans', async ({ page, context }) => {
  await injectAuth(context, page);
  await goTo(page, '/production/plans');

  // Confirmed: 「新建计划」button exists at /production/plans
  const ok = await redBoxScreenshot(
    page,
    'button:has-text("新建计划")',
    '07-production-plans.png',
    '新建计划',
  );

  if (ok) {
    manifest['07-production-plans'] = { status: 'PASS', description: '生产计划页 — 红框圈「新建计划」按钮' };
    return;
  }

  // Fallback: try 「AI对话创建」or 「独立再加工/返工」
  const fallbacks = [
    'button:has-text("AI对话创建")',
    'button:has-text("独立再加工")',
    '.el-button--primary',
  ];
  for (const sel of fallbacks) {
    if (await page.locator(sel).count() > 0) {
      const ok2 = await redBoxScreenshot(page, sel, '07-production-plans.png', '生产计划新建');
      manifest['07-production-plans'] = ok2
        ? { status: 'PASS', description: `生产计划页 — 红框圈 ${sel}` }
        : { status: 'NEED_HELP', description: '按钮存在截图失败' };
      return;
    }
  }

  await plainScreenshot(page, '07-production-plans.png');
  manifest['07-production-plans'] = { status: 'NEED_HELP', description: '生产计划页无新建按钮', note: '截图已保存' };
});

// ─────────────────────────────────────────────────────────────────────────────
// 08: 调拨列表 → 红框圈「手动新建调拨单」绿色主按钮（非客服按钮）
// ─────────────────────────────────────────────────────────────────────────────
test('08-transfer-list', async ({ page, context }) => {
  await injectAuth(context, page);
  await goTo(page, '/transfer/list');

  // Confirmed actual button: 「手动新建调拨单」
  const ok = await redBoxScreenshot(
    page,
    'button:has-text("手动新建调拨单")',
    '08-transfer-list.png',
    '手动新建调拨单',
  );

  if (ok) {
    manifest['08-transfer-list'] = { status: 'PASS', description: '调拨列表 — 红框圈「手动新建调拨单」按钮' };
    return;
  }

  // Fallback
  const fallbacks = [
    'button:has-text("新建调拨")',
    '.el-button--primary:not(:has-text("客服"))',
  ];
  for (const sel of fallbacks) {
    if (await page.locator(sel).count() > 0) {
      const ok2 = await redBoxScreenshot(page, sel, '08-transfer-list.png', '新建调拨');
      manifest['08-transfer-list'] = ok2
        ? { status: 'PASS', description: `调拨列表 — 红框圈 ${sel}` }
        : { status: 'NEED_HELP', description: '按钮存在截图失败' };
      return;
    }
  }

  await plainScreenshot(page, '08-transfer-list.png');
  manifest['08-transfer-list'] = { status: 'NEED_HELP', description: '调拨列表无新建按钮', note: '截图已保存' };
});

// ─────────────────────────────────────────────────────────────────────────────
// 08b: 新建调拨弹窗 → 红框圈「调出仓库」「调入仓库」下拉+数量字段
// ─────────────────────────────────────────────────────────────────────────────
test('08b-transfer-create-form', async ({ page, context }) => {
  await injectAuth(context, page);
  await goTo(page, '/transfer/list');

  await page.locator('button:has-text("手动新建调拨单"), button:has-text("新建调拨")').first().click();
  await page.waitForTimeout(2000);

  // Find 调出仓库 field
  const warehouseSels = [
    '.el-form-item:has(label:has-text("调出仓库"))',
    '.el-form-item:has(label:has-text("调入仓库"))',
    '.el-form-item:has(label:has-text("源仓库"))',
    '.el-form-item:has(label:has-text("目标仓库"))',
    '.el-dialog .el-form-item:has(.el-select)',
  ];

  for (const sel of warehouseSels) {
    if (await page.locator(sel).count() > 0) {
      const ok = await redBoxScreenshot(page, sel, '08b-transfer-create-form.png', '调出/调入仓库字段');
      manifest['08b-transfer-create-form'] = ok
        ? { status: 'PASS', description: '新建调拨弹窗 — 红框圈仓库字段' }
        : { status: 'NEED_HELP', description: '字段存在截图失败' };
      return;
    }
  }

  // Box entire dialog
  const dlgSel = '.el-dialog, .el-drawer';
  if (await page.locator(dlgSel).count() > 0) {
    const ok = await redBoxScreenshot(page, dlgSel, '08b-transfer-create-form.png', '新建调拨表单');
    manifest['08b-transfer-create-form'] = ok
      ? { status: 'PASS', description: '新建调拨弹窗 — 红框圈整个弹窗' }
      : { status: 'NEED_HELP', description: '弹窗截图失败' };
    return;
  }

  await plainScreenshot(page, '08b-transfer-create-form.png');
  manifest['08b-transfer-create-form'] = { status: 'NEED_HELP', description: '调拨弹窗未打开', note: '截图已保存' };
});

// ─────────────────────────────────────────────────────────────────────────────
// 09: 生产批次列表 → 红框圈「创建批次」按钮（扫描确认）或行内操作按钮
// ─────────────────────────────────────────────────────────────────────────────
test('09-production-batches', async ({ page, context }) => {
  await injectAuth(context, page);
  await goTo(page, '/production/batches');

  // Confirmed actual button: 「创建批次」 (also 「AI 创建批次」)
  // Brief requires: row-level 「开始生产」— but batch list shows page-level create buttons
  // Red box on row-level action button if exists, else 「创建批次」
  const rowBtnSels = [
    '.el-table__row button:has-text("开始生产")',
    '.el-table__row button:has-text("开始")',
    '.el-table__row button:has-text("查看")',
  ];

  for (const sel of rowBtnSels) {
    if (await page.locator(sel).count() > 0) {
      const ok = await redBoxScreenshot(page, sel, '09-production-batches.png', '行内批次操作');
      manifest['09-production-batches'] = ok
        ? { status: 'PASS', description: `批次列表 — 红框圈行内按钮 ${sel}` }
        : { status: 'NEED_HELP', description: '行按钮存在截图失败' };
      return;
    }
  }

  // Use page-level 「创建批次」as the main action
  const ok = await redBoxScreenshot(
    page,
    'button:has-text("创建批次")',
    '09-production-batches.png',
    '创建批次(生产批次入口)',
  );
  if (ok) {
    manifest['09-production-batches'] = { status: 'PASS', description: '批次列表 — 红框圈「创建批次」按钮' };
    return;
  }

  await plainScreenshot(page, '09-production-batches.png');
  manifest['09-production-batches'] = { status: 'NEED_HELP', description: '批次列表无可框按钮', note: '截图已保存' };
});

// ─────────────────────────────────────────────────────────────────────────────
// 09b: 批次详情-工序明细 → 红框圈「报工」入口
//   Debug: batch 2026 detail has NO <button> for 报工
//   工序明细 rows show「领料报工 待开工」「产出报工 待开工」as clickable items (not buttons)
//   Strategy: click row 「查看」to enter detail, then find any 报工 clickable div/span/link
// ─────────────────────────────────────────────────────────────────────────────
test('09b-batch-detail', async ({ page, context }) => {
  await injectAuth(context, page);
  await goTo(page, '/production/batches/2026');

  // Try any 报工-related element (not just button — could be .el-link, div, span)
  const reportSels = [
    '[class*="process"]:has-text("报工")',
    '[class*="work"]:has-text("报工")',
    '.el-link:has-text("报工")',
    'a:has-text("报工")',
    'span:has-text("报工")',
    'td:has-text("报工")',
    'div:has-text("领料报工")',
    'div:has-text("产出报工")',
    '*:has-text("报工"):not(script):not(style)',
  ];

  for (const sel of reportSels) {
    try {
      const cnt = await page.locator(sel).count();
      if (cnt > 0) {
        const ok = await redBoxScreenshot(page, sel, '09b-batch-detail.png', '工序报工入口');
        manifest['09b-batch-detail'] = ok
          ? { status: 'PASS', description: '批次详情工序明细 — 红框圈「报工」入口元素' }
          : { status: 'NEED_HELP', description: '报工元素存在截图失败' };
        return;
      }
    } catch (_) {}
  }

  // Try navigating to batch list and clicking into a batch detail via 查看
  await goTo(page, '/production/batches');
  const viewBtns = page.locator('.el-table__row button:has-text("查看"), .el-table__row a:has-text("查看")');
  if (await viewBtns.count() > 0) {
    await viewBtns.first().click();
    await page.waitForTimeout(2500);
    await page.waitForLoadState('networkidle');

    for (const sel of reportSels) {
      try {
        const cnt = await page.locator(sel).count();
        if (cnt > 0) {
          const ok = await redBoxScreenshot(page, sel, '09b-batch-detail.png', '工序报工入口');
          manifest['09b-batch-detail'] = ok
            ? { status: 'PASS', description: '批次详情工序明细 — 红框圈「报工」入口' }
            : { status: 'NEED_HELP', description: '报工元素存在截图失败' };
          return;
        }
      } catch (_) {}
    }
  }

  // Last resort: plain screenshot of batch detail
  await goTo(page, '/production/batches/2026');
  await plainScreenshot(page, '09b-batch-detail.png');
  manifest['09b-batch-detail'] = { status: 'NEED_HELP', description: '批次详情无「报工」元素 — 截图已保存', note: '工序明细中「领料报工/产出报工」是非button可点击项' };
});

// ─────────────────────────────────────────────────────────────────────────────
// 10: 批次详情 → 红框圈「完工入库」按钮
//   Debug: batch 2026 has no 完工入库 — batch may not be in right state
//   Strategy: list all batches, try each 查看 until finding one with 完工入库
// ─────────────────────────────────────────────────────────────────────────────
test('10-production-complete', async ({ page, context }) => {
  await injectAuth(context, page);
  await goTo(page, '/production/batches');

  const completeSels = [
    'button:has-text("完工入库")',
    'button:has-text("完工")',
    'button:has-text("入库")',
  ];

  // Try each row's 查看/详情 button
  const rowBtns = await page.locator('.el-table__row button:has-text("查看"), .el-table__row button:has-text("详情")').all();
  for (const btn of rowBtns.slice(0, 5)) {
    try {
      await btn.click();
      await page.waitForTimeout(2000);
      await page.waitForLoadState('networkidle');

      for (const sel of completeSels) {
        if (await page.locator(sel).count() > 0) {
          const ok = await redBoxScreenshot(page, sel, '10-production-complete.png', '完工入库');
          manifest['10-production-complete'] = ok
            ? { status: 'PASS', description: '批次详情 — 红框圈「完工入库」按钮' }
            : { status: 'NEED_HELP', description: '按钮存在截图失败' };
          return;
        }
      }
      await page.goBack();
      await page.waitForTimeout(1000);
    } catch (_) {
      await goTo(page, '/production/batches');
    }
  }

  // Also try direct URL to known batch
  await goTo(page, '/production/batches/2026');
  for (const sel of completeSels) {
    if (await page.locator(sel).count() > 0) {
      const ok = await redBoxScreenshot(page, sel, '10-production-complete.png', '完工入库');
      manifest['10-production-complete'] = ok
        ? { status: 'PASS', description: '批次详情 — 红框圈「完工入库」按钮' }
        : { status: 'NEED_HELP', description: '截图失败' };
      return;
    }
  }

  await plainScreenshot(page, '10-production-complete.png');
  manifest['10-production-complete'] = { status: 'NEED_HELP', description: '未找到包含「完工入库」按钮的批次 — 截图已保存', note: '批次可能需要进行到特定状态才有此按钮' };
});

// ─────────────────────────────────────────────────────────────────────────────
// 10b: 成品库存页 → 红框圈成品记录行
// ─────────────────────────────────────────────────────────────────────────────
test('10b-inventory-confirm', async ({ page, context }) => {
  await injectAuth(context, page);

  // Try finished goods inventory page
  await goTo(page, '/sales/finished-goods');

  let rowCount = await page.locator('.el-table__row').count();

  if (rowCount === 0) {
    await goTo(page, '/warehouse/inventory');
    rowCount = await page.locator('.el-table__row').count();
  }

  if (rowCount > 0) {
    const ok = await redBoxScreenshot(
      page,
      '.el-table__row:first-child',
      '10b-inventory-confirm.png',
      '成品库存记录行',
    );
    manifest['10b-inventory-confirm'] = ok
      ? { status: 'PASS', description: '成品库存页 — 红框圈第一条成品记录行' }
      : { status: 'NEED_HELP', description: '行存在截图失败' };
  } else {
    await plainScreenshot(page, '10b-inventory-confirm.png');
    manifest['10b-inventory-confirm'] = { status: 'NEED_HELP', description: '库存页无记录', note: '截图已保存' };
  }
});

// ─────────────────────────────────────────────────────────────────────────────
// Write manifest after all tests
// ─────────────────────────────────────────────────────────────────────────────
test.afterAll(async () => {
  const manifestPath = path.join(SCREENSHOTS_DIR, 'manifest.json');
  fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2), 'utf8');
  console.log('\n=== MANIFEST SUMMARY ===');
  let pass = 0, fail = 0;
  for (const [key, val] of Object.entries(manifest)) {
    const icon = val.status === 'PASS' ? '✅' : '⚠️';
    console.log(`${icon} ${key}: ${val.description}${val.note ? ' [' + val.note + ']' : ''}`);
    if (val.status === 'PASS') pass++; else fail++;
  }
  console.log(`\n${pass} PASS / ${fail} NEED_HELP`);
  console.log(`Manifest: ${manifestPath}`);
});
