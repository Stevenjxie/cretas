/**
 * 共享 headed 测试 helper —— 从已验证的 headed 脚本提炼.
 * 复用方:headed-crossday-cost / headed-crossplan-wip / headed-reverse-resettle.
 *
 * 全部针对 prod F006 (默认), 账号 f006_admin/123456.
 */
import { chromium } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

export const APP = process.env.E2E_ADMIN_URL || 'http://139.196.165.140:8086';
export const FACTORY = process.env.E2E_FACTORY_ID || 'F006';
export const arr = (d) => Array.isArray(d) ? d : (d && (d.content || d.records || d.items)) || [];
export const num = (v) => { const n = Number(v); return Number.isFinite(n) ? n : null; };
export const today = (o = 0) => new Date(Date.now() + 8 * 3600e3 + o * 86400e3).toISOString().slice(0, 10);
export const COUNT_UNIT_RE = /件|个|只|pcs|pc$/i; // 计数单位包材, 原料发现需排除(避免把包材当原料投产污染)
export const WEIGHT_UNIT_RE = /kg|g|千克|公斤|克|斤/i;

/** 启动 headed 持久化上下文 + 登录, 返回 { browser, page, token, api, shot, helpers }. */
export async function startHeaded(outDir) {
  const U = process.env.E2E_USERNAME, P = process.env.E2E_PASSWORD;
  if (!U || !P) throw new Error('E2E_USERNAME/E2E_PASSWORD required');
  const PROFILE = path.join(outDir, `profile-${Date.now().toString(36)}`);
  const PORT_ARG = process.env.PLAYWRIGHT_PORT ? [`--remote-debugging-port=${process.env.PLAYWRIGHT_PORT}`] : [];
  await mkdir(outDir, { recursive: true });
  const browser = await chromium.launchPersistentContext(PROFILE, { headless: false, viewport: { width: 1920, height: 1080 }, args: ['--lang=zh-CN', '--font-render-hinting=none', ...PORT_ARG] });
  const page = browser.pages()[0] || await browser.newPage();
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push(e.message));

  // login
  await page.goto(`${APP}/login`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.locator('.login-form input, input').nth(0).fill(U);
  await page.locator('input[type="password"], .login-form input').nth(1).fill(P);
  let sb = page.locator('.login-button').first();
  if (!(await sb.isVisible().catch(() => false))) sb = page.locator('button[type="submit"], button.el-button--primary, button').first();
  await Promise.all([page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 30000 }).catch(() => null), sb.click().catch(() => page.keyboard.press('Enter'))]);
  await page.waitForTimeout(2500);
  await page.waitForFunction(() => Boolean(localStorage.getItem('cretas_access_token')), null, { timeout: 10000 }).catch(() => null);
  const token = await page.evaluate(() => localStorage.getItem('cretas_access_token') || '');
  if (!token) throw new Error('login produced no token');

  const H = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  const api = async (method, p, body) => {
    const r = await fetch(`${APP}/api/mobile${p}`, { method, headers: H, body: body == null ? undefined : JSON.stringify(body) });
    const t = await r.text(); let j = null; try { j = t ? JSON.parse(t) : null; } catch { j = { raw: t }; }
    if (!r.ok || j?.success === false) { const e = new Error(`${method} ${p} ${r.status}: ${j?.message || t.slice(0, 200)}`); e.status = r.status; e.body = j; throw e; }
    return j && 'data' in j ? j.data : j;
  };
  let shotN = 0;
  const shot = async (n) => { const f = path.join(outDir, `${String(++shotN).padStart(2, '0')}-${n}.png`); await page.screenshot({ path: f, fullPage: true }).catch(() => null); return f; };

  return { browser, page, token, api, shot, consoleErrors, helpers: makeHelpers(page) };
}

/** UI 交互 helper(基于已验证 pattern). */
export function makeHelpers(page) {
  // filterable el-select: open → click matching option by text (getByRole auto-scroll/visible).
  async function selectByText(scope, searchText) {
    await scope.click(); await page.waitForTimeout(700);
    const opt = page.getByRole('option').filter({ hasText: String(searchText) }).first();
    await opt.scrollIntoViewIfNeeded().catch(() => null);
    await opt.click({ timeout: 8000 });
    await page.waitForTimeout(400);
  }
  // 模态内 el-select 退路: 键盘流 (type → ArrowDown → Enter).
  async function selectByKeyboard(scope, searchText) {
    const inp = scope.locator('input').first();
    await inp.click(); await page.waitForTimeout(300);
    await inp.fill(''); await inp.type(String(searchText), { delay: 25 }); await page.waitForTimeout(700);
    await inp.press('ArrowDown'); await page.waitForTimeout(200); await inp.press('Enter'); await page.waitForTimeout(400);
  }
  async function fillNum(loc, value) { const inp = loc.locator('input').first(); await inp.click(); await inp.fill(''); await inp.type(String(value)); await inp.press('Tab'); await page.waitForTimeout(180); }
  const activePane = () => page.locator('.el-drawer__body .el-tab-pane:visible').first();
  async function gotoTab(name) { const t = page.locator('.el-drawer__body .el-tabs__item').filter({ hasText: name }).first(); if (await t.isVisible().catch(() => false)) { await t.click(); await page.waitForTimeout(1400); return true; } return false; }
  async function waitSaved(timeout = 9000) {
    try { const tEl = await page.waitForSelector('.el-message--success, .el-message--warning, .el-message--error', { timeout }); const txt = await tEl.innerText().catch(() => ''); return { saved: /已保存|保存成功|成功|已提交/.test(txt) && !/失败|错误|无法保存/.test(txt), toast: txt.slice(0, 160) }; }
    catch { return { saved: false, toast: '(no toast)' }; }
  }
  return { selectByText, selectByKeyboard, fillNum, activePane, gotoTab, waitSaved };
}

export async function setupSkuAndBom(page, {
  namePrefix = 'HT-SKU',
  gramsPerUnit = 80,
  bomLines = null,
  api = null,
  shot = null,
  minProcesses = 3,
  copyProcesses = true,
  createRecipeForProcessEntry = true,
} = {}) {
  const helpers = makeHelpers(page);
  const apiCall = api || await makeApiFromPage(page);
  const screenshot = shot || (async () => null);
  const ts = Date.now().toString(36);
  const name = `${namePrefix}-${ts}`;
  const code = `${namePrefix.replace(/[^A-Za-z0-9]/g, '').slice(0, 12).toUpperCase()}-${ts}`.slice(0, 32);

  await page.goto(`${APP}/system/products`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  await page.locator('button').filter({ hasText: /新增产品|新建产品|新增/ }).first().click();
  await page.waitForSelector('.el-dialog:visible', { timeout: 12000 });
  const productDialog = page.locator('.el-dialog:visible').first();

  await productDialog.locator('.el-form-item').filter({ hasText: '产品名称' }).locator('input').first().fill(name);
  const codeInput = productDialog.locator('.el-form-item').filter({ hasText: /^产品编码|^编号|^编码/ }).locator('input').first();
  if (await codeInput.isVisible().catch(() => false)) {
    await codeInput.fill(code).catch(() => null);
  }

  await selectAllowCreateOption(page, productDialog.locator('.el-form-item').filter({ hasText: /^单位/ }).locator('.el-select').first(), '盒');

  const categorySelect = productDialog.locator('.el-form-item').filter({ hasText: '产品大类' }).locator('.el-select').first();
  const categoryValue = await categorySelect.locator('input').first().inputValue().catch(() => '');
  if (!categoryValue) {
    await helpers.selectByText(categorySelect, '成品').catch(async () => {
      await categorySelect.click();
      await page.getByRole('option').filter({ hasText: '成品' }).first().click({ timeout: 5000 });
      await page.waitForTimeout(400);
    });
  }

  const gramsInput = productDialog.locator('.el-form-item').filter({ hasText: '标准克重' }).locator('input').first();
  if (await gramsInput.isVisible().catch(() => false)) {
    await gramsInput.click();
    await gramsInput.fill(String(gramsPerUnit));
    await gramsInput.press('Tab').catch(() => null);
  }
  await screenshot('setup-sku-form');
  await productDialog.locator('.el-dialog__header, .el-dialog__title').first().click().catch(() => null);
  await page.waitForTimeout(300);
  await productDialog.locator('.el-dialog__footer button.el-button--primary').last().click();
  const skuToast = await waitToast(page, /产品已新增|新增成功|成功/, 10000);
  await page.waitForTimeout(1200);

  const products = arr(await apiCall('GET', `/${FACTORY}/product-types/active`));
  const product = products.find((p) => (p.name || '') === name);
  if (!product?.id) throw new Error(`setupSkuAndBom: SKU API readback failed for ${name}`);
  await screenshot('setup-sku-created');

  let processTemplate = null;
  let processes = [];
  if (copyProcesses) {
    processTemplate = await findProcessTemplate(apiCall, product.id, minProcesses);
    if (!processTemplate) {
      throw new Error(`setupSkuAndBom: no existing product has >=${minProcesses} active work processes`);
    }
    processes = await copyProductProcesses(apiCall, processTemplate, product.id);
    const readback = arr(await apiCall('GET', `/${FACTORY}/product-work-processes?productTypeId=${encodeURIComponent(product.id)}`))
      .filter((p) => p.isActive !== false)
      .sort((a, b) => (a.processOrder || 0) - (b.processOrder || 0));
    if (readback.length < minProcesses) {
      throw new Error(`setupSkuAndBom: copied process readback too short (${readback.length})`);
    }
    processes = readback;
  }

  const selectedRaw = await chooseWeightRawMaterial(apiCall);
  const lines = bomLines?.length ? bomLines : [{
    materialCategory: 'RAW',
    materialTypeId: selectedRaw.id,
    materialName: selectedRaw.name,
    standardQuantity: 100,
    yieldRate: 90,
  }];

  await page.goto(`${APP}/production/bom`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(3000);
  await selectBomProduct(page, product);
  await screenshot('setup-bom-product-selected');

  const createdLines = [];
  for (let i = 0; i < lines.length; i += 1) {
    const line = normalizeBomLine(lines[i], selectedRaw);
    await addBomLine(page, helpers, line);
    await page.waitForTimeout(1200);
    const readback = arr(await apiCall('GET', `/${FACTORY}/bom/items/${product.id}`));
    const match = readback.find((item) => (
      String(item.materialTypeId || '') === String(line.materialTypeId || '')
      || String(item.materialName || '') === String(line.materialName || '')
    ) && String(item.materialCategory || 'RAW') === String(line.materialCategory || 'RAW'));
    if (!match) throw new Error(`setupSkuAndBom: BOM line ${line.materialName} not found by API readback`);
    createdLines.push(match);
    await screenshot(`setup-bom-line-${i + 1}`);
  }

  let bomRecipe = null;
  if (createRecipeForProcessEntry) {
    bomRecipe = await ensureCurrentBomRecipe(apiCall, product, createdLines);
  }

  return {
    product,
    productTypeId: product.id,
    name,
    skuToast,
    processTemplate,
    processes,
    rawMaterial: selectedRaw,
    bomItems: createdLines,
    bomRecipe,
  };
}

async function makeApiFromPage(page) {
  const token = await page.evaluate(() => localStorage.getItem('cretas_access_token') || '');
  if (!token) throw new Error('setupSkuAndBom: no auth token in localStorage');
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  return async (method, p, body) => {
    const r = await fetch(`${APP}/api/mobile${p}`, {
      method,
      headers,
      body: body == null ? undefined : JSON.stringify(body),
    });
    const text = await r.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { json = { raw: text }; }
    if (!r.ok || json?.success === false) {
      throw new Error(`${method} ${p} ${r.status}: ${json?.message || text.slice(0, 200)}`);
    }
    return json && 'data' in json ? json.data : json;
  };
}

async function waitToast(page, pattern, timeout = 9000) {
  const el = await page.waitForSelector('.el-message--success, .el-message--warning, .el-message--error', { timeout });
  const text = await el.innerText().catch(() => '');
  if (pattern && !pattern.test(text)) throw new Error(`unexpected toast: ${text}`);
  return text;
}

async function selectAllowCreateOption(page, select, text) {
  const input = select.locator('input').first();
  await input.click();
  await page.waitForTimeout(250);
  await input.fill('');
  await input.pressSequentially(text, { delay: 60 });
  await page.waitForTimeout(700);
  const option = page.getByRole('option').filter({ hasText: text }).first();
  if (await option.isVisible().catch(() => false)) {
    await option.click().catch(async () => input.press('Enter'));
  } else {
    await input.press('Enter').catch(() => null);
  }
  await page.waitForTimeout(400);
}

async function findProcessTemplate(apiCall, excludeProductId, minProcesses) {
  const products = arr(await apiCall('GET', `/${FACTORY}/product-types/active`));
  for (const pr of products) {
    const id = String(pr.id || pr.productTypeId || '');
    if (!id || id === String(excludeProductId)) continue;
    const procs = arr(await apiCall('GET', `/${FACTORY}/product-work-processes?productTypeId=${encodeURIComponent(id)}`))
      .filter((p) => p.isActive !== false)
      .sort((a, b) => (a.processOrder || 0) - (b.processOrder || 0));
    const hasCook = procs.some((p) => /熟|卤|煮|气调|包装|装盒/.test(String(p.processName || '')));
    if (procs.length >= minProcesses && hasCook) {
      return { product: pr, id, name: pr.name || pr.productName, procs };
    }
  }
  return null;
}

async function copyProductProcesses(apiCall, template, productTypeId) {
  const copied = [];
  for (const source of template.procs) {
    const payload = {
      productTypeId,
      workProcessId: source.workProcessId,
      processOrder: source.processOrder,
      unitOverride: source.unitOverride ?? null,
      estimatedMinutesOverride: source.estimatedMinutesOverride ?? null,
      responsibleWorkerId: source.responsibleWorkerId ?? null,
      assigneeWorkerIds: source.assigneeWorkerIds ?? null,
      isActive: source.isActive !== false,
      reportingRequired: source.reportingRequired !== false,
      allowSemiFinishedInjection: Boolean(source.allowSemiFinishedInjection),
      allowMultipleUpstreamSources: Boolean(source.allowMultipleUpstreamSources),
      defaultCostCategory: source.defaultCostCategory ?? null,
      packagingTemplate: source.packagingTemplate ?? null,
      auxAllocMethod: source.auxAllocMethod ?? null,
      standardYieldRate: source.standardYieldRate ?? null,
      auxUnitPrice: source.auxUnitPrice ?? null,
      auxBasis: source.auxBasis ?? null,
    };
    copied.push(await apiCall('POST', `/${FACTORY}/product-work-processes`, payload));
  }
  return copied;
}

async function chooseWeightRawMaterial(apiCall) {
  const candidates = [
    ...arr(await apiCall('GET', `/${FACTORY}/raw-material-types?materialKind=%E5%8E%9F%E6%96%99&size=200`).catch(() => [])),
    ...arr(await apiCall('GET', `/${FACTORY}/raw-material-types/active`).catch(() => [])),
  ];
  const warehouses = arr(await apiCall('GET', `/${FACTORY}/factory/warehouses`).catch(() => []));
  const preferredWarehouses = [
    ...warehouses.filter((w) => w.code === 'WH-LOG' || w.type === 'LOGISTICS'),
    ...warehouses.filter((w) => w.code === 'WH-RAW' || w.type === 'RAW'),
  ];
  for (const warehouse of preferredWarehouses) {
    const batches = arr(await apiCall('GET', `/${FACTORY}/material-batches/status/AVAILABLE?warehouseId=${encodeURIComponent(warehouse.id)}&size=200`).catch(() => []));
    const batch = batches.find((b) => {
      const unit = String(b.quantityUnit || b.unit || '');
      return num(b.currentQuantity ?? b.quantity) > 1
        && WEIGHT_UNIT_RE.test(unit)
        && !COUNT_UNIT_RE.test(unit)
        && !/^WIP-|^CLK-/.test(String(b.batchNumber || ''));
    });
    if (batch) {
      const raw = candidates.find((m) => (
        String(m.id || '') === String(batch.materialTypeId || batch.rawMaterialTypeId || '')
        || String(m.name || '') === String(batch.materialName || batch.materialTypeName || '')
      ));
      if (raw) return { ...raw, _preferredBatch: batch, _preferredWarehouse: warehouse };
    }
  }
  const raw = candidates.find((m) => {
    const unit = String(m.unit || m.quantityUnit || '');
    const kind = String(m.materialKind || m.category || m.type || m.materialCategory || '');
    return WEIGHT_UNIT_RE.test(unit) && !COUNT_UNIT_RE.test(unit) && !/包材|PACKAGING/i.test(kind);
  });
  if (!raw?.id) throw new Error('setupSkuAndBom: no weight-unit raw material found');
  return raw;
}

function normalizeBomLine(line, fallbackRaw) {
  const category = line.materialCategory || line.category || 'RAW';
  const material = line.materialType || fallbackRaw;
  return {
    materialCategory: category,
    materialTypeId: line.materialTypeId || material?.id,
    materialName: line.materialName || material?.name,
    standardQuantity: line.standardQuantity ?? 100,
    yieldRate: line.yieldRate ?? 90,
    unit: category === 'PACKAGING' ? (line.unit || material?.unit || 'pcs') : 'g',
  };
}

async function selectBomProduct(page, product) {
  const input = page.locator('.header-card .el-select input').first();
  const targetUrlPart = `/bom/items/${product.id}`;
  const waitForTargetLoad = page.waitForResponse((r) => r.url().includes(targetUrlPart), { timeout: 12000 }).catch(() => null);
  await input.click();
  await page.waitForTimeout(500);
  await input.fill('');
  await input.pressSequentially(product.name, { delay: 20 });
  await page.waitForTimeout(900);
  const option = page.locator('.el-select-dropdown__item:visible').filter({ hasText: product.name }).first();
  if (!(await option.isVisible().catch(() => false))) {
    throw new Error(`BOM product option not visible for ${product.name}`);
  }
  // Element Plus updates its model from the real pointer/click path. Raw DOM
  // dispatchEvent calls can leave the dropdown open without firing the BOM watcher.
  await option.click({ force: true });
  let response = await waitForTargetLoad;
  if (!response) {
    await input.click();
    await page.waitForTimeout(300);
    await input.fill('');
    await input.pressSequentially(product.name, { delay: 20 });
    await page.waitForTimeout(700);
    const waitAgain = page.waitForResponse((r) => r.url().includes(targetUrlPart), { timeout: 12000 }).catch(() => null);
    await page.evaluate((name) => {
      const item = Array.from(document.querySelectorAll('.el-select-dropdown__item'))
        .find((el) => el.textContent?.includes(name));
      item?.click();
    }, product.name);
    response = await waitAgain;
  }
  if (!response) throw new Error(`BOM product select did not load ${product.id}`);
  await page.keyboard.press('Escape').catch(() => null);
  await page.locator('.page-title').first().click().catch(() => null);
  await page.waitForTimeout(800);
}

async function addBomLine(page, helpers, line) {
  const materialCard = page.locator('.table-card').filter({ hasText: '原辅料需求明细表' }).first();
  await materialCard.locator('button').filter({ hasText: '添加' }).first().click();
  await page.waitForSelector('.el-dialog:visible', { timeout: 10000 });
  await page.waitForTimeout(700);
  const dialog = page.locator('.el-dialog:visible').filter({ hasText: /添加原辅料|编辑原辅料|物料名称/ }).first();
  const form = (await dialog.count()) ? dialog : page.locator('.el-dialog:visible').first();

  const categorySelect = form.locator('.el-form-item').filter({ hasText: '物料类别' }).locator('.el-select').first();
  await helpers.selectByText(categorySelect, categoryLabel(line.materialCategory)).catch(() => null);
  await page.waitForTimeout(300);

  const linkedSelect = form.locator('.el-form-item').filter({ hasText: '关联原料' }).locator('.el-select').first();
  if (line.materialTypeId || line.materialName) {
    await helpers.selectByText(linkedSelect, line.materialName).catch(async () => {
      await linkedSelect.click();
      await page.waitForTimeout(500);
      await page.locator('.el-select-dropdown__item:visible').filter({ hasText: line.materialName }).first().click({ force: true });
    });
  }
  const nameInput = form.locator('.el-form-item').filter({ hasText: '物料名称' }).locator('input').first();
  if (!(await nameInput.inputValue().catch(() => '')).trim()) {
    await nameInput.fill(line.materialName);
  }
  await helpers.fillNum(form.locator('.el-form-item').filter({ hasText: '成品含量' }).locator('.el-input-number').first(), line.standardQuantity);
  await helpers.fillNum(form.locator('.el-form-item').filter({ hasText: /出成率%/ }).locator('.el-input-number').first(), line.yieldRate);
  await form.locator('.el-dialog__header, .el-dialog__title').first().click().catch(() => null);
  await page.waitForTimeout(250);
  await form.locator('.el-dialog__footer button.el-button--primary').last().click();
  const toast = await waitToast(page, /Added successfully|Updated successfully|成功|已新增|保存/, 10000);
  if (/失败|错误|Operation failed/.test(toast)) throw new Error(`BOM save failed: ${toast}`);
}

function categoryLabel(value) {
  if (value === 'PACKAGING') return '包材';
  if (value === 'AUXILIARY') return '辅料';
  return '原料';
}

async function ensureCurrentBomRecipe(apiCall, product, bomItems) {
  if (!bomItems.length) return null;
  const req = {
    productTypeId: product.id,
    productName: product.name,
    overallYieldRate: 100,
    outputQuantityPerUnit: 1,
    outputUnit: product.unit || '份',
    sourceType: 'MANUAL',
    notes: 'E2E compatibility recipe generated from headed bom_items setup',
    items: bomItems.map((item, idx) => ({
      materialTypeId: item.materialTypeId,
      standardQuantity: Number(item.standardQuantity ?? 0),
      yieldRate: item.yieldRate == null ? null : Number(item.yieldRate),
      unit: item.unit || (item.materialCategory === 'PACKAGING' ? 'pcs' : 'g'),
      unitPrice: item.unitPrice == null ? null : Number(item.unitPrice),
      taxRate: item.taxRate == null ? null : Number(item.taxRate),
      materialCategory: item.materialCategory || 'RAW',
      sortOrder: item.sortOrder ?? idx,
      isOptional: item.isOptional ?? false,
      substituteGroup: item.substituteGroup ?? null,
      remark: item.notes ?? null,
      perPortion: item.perPortion ?? false,
      semiFinishedRefCode: item.semiFinishedRefCode ?? null,
      subProductTypeId: item.subProductTypeId ?? null,
    })),
  };
  const recipe = await apiCall('POST', `/${FACTORY}/bom/recipes`, req);
  const recipeId = recipe?.id;
  if (!recipeId) throw new Error('setupSkuAndBom: bom recipe create returned no id');
  return await apiCall('POST', `/${FACTORY}/bom/recipes/${encodeURIComponent(recipeId)}/activate`).catch(() => recipe);
}
