import { chromium } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const APP_URL = process.env.E2E_ADMIN_URL || 'http://139.196.165.140:8086';
const FACTORY_ID = process.env.E2E_FACTORY_ID || 'F006';
const OUT_DIR = path.resolve(process.env.E2E_OUT || `.playwright-mcp/codex-${new Date().toISOString().replace(/[:.]/g, '-')}-prod-business-flow`);
const PROFILE_DIR = path.join(OUT_DIR, `profile-${Date.now().toString(36)}`);
const TS = new Date().toISOString().replace(/[-:.TZ]/g, '').slice(0, 14);
const AUDIT_PREFIX = `CodexAudit-${TS}`;
const PORT_ARG = process.env.PLAYWRIGHT_PORT ? [`--remote-debugging-port=${process.env.PLAYWRIGHT_PORT}`] : [];

const screenshots = [];
const consoleErrors = [];
const apiCalls = [];
const assertions = [];
let browserContext = null;

function requiredEnv(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
}

const USERNAME = requiredEnv('E2E_USERNAME');
const PASSWORD = requiredEnv('E2E_PASSWORD');

function today(offsetDays = 0) {
  const d = new Date(Date.now() + 8 * 60 * 60 * 1000 + offsetDays * 24 * 60 * 60 * 1000);
  return d.toISOString().slice(0, 10);
}

function num(v) {
  if (v == null || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

function approx(actual, expected, tolerance, label) {
  const pass = actual != null && expected != null && Math.abs(actual - expected) <= tolerance;
  assertions.push({ label, pass, actual, expected, tolerance });
  return pass;
}

function assertTrue(pass, label, detail = {}) {
  assertions.push({ label, pass: Boolean(pass), ...detail });
}

function unwrap(json) {
  if (!json || typeof json !== 'object') return json;
  if ('success' in json && json.success === false) {
    throw new Error(json.message || JSON.stringify(json));
  }
  return 'data' in json ? json.data : json;
}

function contentArray(pageLike) {
  if (Array.isArray(pageLike)) return pageLike;
  if (Array.isArray(pageLike?.content)) return pageLike.content;
  if (Array.isArray(pageLike?.records)) return pageLike.records;
  if (Array.isArray(pageLike?.items)) return pageLike.items;
  return [];
}

function processCodeFor(item, index) {
  const name = String(item.processName || '').toLowerCase();
  const category = String(item.defaultCostCategory || '').toUpperCase();
  if (index === 0) return 'xiuyou';
  if (name.includes('滚揉')) return 'gunrou';
  if (name.includes('去舌')) return 'qushetou';
  if (name.includes('熟') || category === 'SEASONING') return 'shuzhi';
  if (name.includes('气调') || name.includes('包装') || category === 'PACKAGING') return 'qidiao';
  return 'chaoshui';
}

function pickProductName(p) {
  return p.name || p.productName || p.typeName || p.displayName || p.code || p.id;
}

function pickRawWarehouse(warehouses) {
  return warehouses.find((w) => w.code === 'WH-LOG' && w.isActive !== false)
    || warehouses.find((w) => ['RAW', 'LOGISTICS'].includes(String(w.type || '')) && w.isActive !== false);
}

async function main() {
  await mkdir(OUT_DIR, { recursive: true });
  const browser = await chromium.launchPersistentContext(PROFILE_DIR, {
    headless: false,
    viewport: { width: 1920, height: 1080 },
    args: ['--lang=zh-CN', ...PORT_ARG],
  });
  browserContext = browser;
  const page = browser.pages()[0] || await browser.newPage();
  page.on('console', (msg) => {
    if (['error'].includes(msg.type())) consoleErrors.push(msg.text());
  });
  page.on('pageerror', (err) => consoleErrors.push(err.message));

  async function shot(name, fullPage = true) {
    const file = path.join(OUT_DIR, `${String(screenshots.length + 1).padStart(2, '0')}-${name}.png`);
    await page.screenshot({ path: file, fullPage });
    screenshots.push(file);
    return file;
  }

  async function login() {
    await page.goto(`${APP_URL}/login`, { waitUntil: 'domcontentloaded', timeout: 45000 });
    await page.locator('.login-form input, input').nth(0).fill(USERNAME);
    await page.locator('.login-form input[type="password"], input[type="password"], .login-form input').nth(1).fill(PASSWORD);
    let submit = page.locator('.login-button').first();
    if (!(await submit.isVisible().catch(() => false))) {
      submit = page.locator('button[type="submit"], button.el-button--primary, button').first();
    }
    if (await submit.isVisible().catch(() => false)) {
      await Promise.all([
        page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 30000 }).catch(() => null),
        submit.click(),
      ]);
    } else {
      await Promise.all([
        page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 30000 }).catch(() => null),
        page.keyboard.press('Enter'),
      ]);
    }
    await page.waitForTimeout(2500);
    await shot('after-login-dashboard', false);
    await page.waitForFunction(() => Boolean(localStorage.getItem('cretas_access_token')), null, { timeout: 10000 }).catch(() => null);
    const token = await page.evaluate(() => localStorage.getItem('cretas_access_token') || localStorage.getItem('token') || '');
    if (!token) throw new Error('login succeeded visually but no access token was found in localStorage');
    return token;
  }

  const token = await login();
  const headers = {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
  };

  async function api(method, apiPath, body) {
    const res = await fetch(`${APP_URL}/api/mobile${apiPath}`, {
      method,
      headers,
      body: body == null ? undefined : JSON.stringify(body),
    });
    const text = await res.text();
    let json = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {
      json = { raw: text };
    }
    apiCalls.push({ method, apiPath, status: res.status, ok: res.ok, message: json?.message });
    if (!res.ok) {
      throw new Error(`${method} ${apiPath} failed ${res.status}: ${text.slice(0, 500)}`);
    }
    return unwrap(json);
  }

  const products = contentArray(await api('GET', `/${FACTORY_ID}/product-types/active`));
  const candidates = [];
  for (const p of products.slice(0, 80)) {
    const id = String(p.id || p.productTypeId || '');
    if (!id) continue;
    const processes = contentArray(await api('GET', `/${FACTORY_ID}/product-work-processes?productTypeId=${encodeURIComponent(id)}`))
      .filter((it) => it.isActive !== false)
      .sort((a, b) => Number(a.processOrder || 0) - Number(b.processOrder || 0));
    if (processes.length >= 3) candidates.push({ product: p, processes });
    if (candidates.length >= 8) break;
  }
  if (candidates.length < 1) throw new Error('No active product with at least 3 configured processes was found');
  const hasUniqueOrders = (c) => {
    const orders = c.processes.slice(0, 4).map((p, i) => Number(p.processOrder || i + 1));
    return new Set(orders).size === orders.length;
  };
  const rich = candidates.find((c) => hasUniqueOrders(c) && c.processes.some((p) => /熟|气调|包装/.test(String(p.processName || ''))))
    || candidates.find(hasUniqueOrders)
    || candidates.find((c) => c.processes.some((p) => /熟|气调|包装/.test(String(p.processName || ''))))
    || candidates[0];
  const secondProduct = candidates.find((c) => String(c.product.id) !== String(rich.product.id)) || rich;
  const p1 = String(rich.product.id);
  const p2 = String(secondProduct.product.id || rich.product.id);
  const chain = rich.processes.slice(0, 4).map((it, index) => ({
    order: index + 1,
    configuredOrder: Number(it.processOrder || index + 1),
    name: it.processName || `工序${index + 1}`,
    code: processCodeFor(it, index),
  }));
  while (chain.length < 4) {
    chain.push({ order: chain.length + 1, name: `补充工序${chain.length + 1}`, code: chain.length === 2 ? 'shuzhi' : 'chaoshui' });
  }
  chain[0].code = 'xiuyou';
  if (!chain.slice(2).some((p) => p.code === 'shuzhi')) chain[2].code = 'shuzhi';

  const warehouses = contentArray(await api('GET', `/${FACTORY_ID}/factory/warehouses`));
  const rawWarehouse = pickRawWarehouse(warehouses);
  if (!rawWarehouse?.id) {
    throw new Error('No WH-LOG/RAW/LOGISTICS warehouse was found for raw material selection');
  }
  async function loadRawBatchesForProduct(productTypeId) {
    const rawResp = await api(
      'GET',
      `/${FACTORY_ID}/material-batches/status/AVAILABLE?warehouseId=${encodeURIComponent(rawWarehouse.id)}&productTypeId=${encodeURIComponent(productTypeId)}&size=200`,
    );
    return contentArray(rawResp)
      .map((b) => ({
        id: String(b.id || ''),
        productTypeId,
        batchNumber: b.batchNumber || b.materialBatchNumber || b.batchNo || b.id,
        materialName: b.materialName || b.materialTypeName || b.name,
        warehouseId: b.warehouseId || rawWarehouse.id,
        currentQuantity: num(b.currentQuantity ?? b.availableQuantity ?? b.quantity),
        unitPrice: num(b.unitPrice ?? b.price),
      }))
      .filter((b) => b.id && (b.currentQuantity == null || b.currentQuantity > 0.5));
  }
  const rawBatchesP1 = await loadRawBatchesForProduct(p1);
  const rawBatchesP2 = p2 === p1 ? rawBatchesP1 : await loadRawBatchesForProduct(p2);
  const r1 = rawBatchesP1[0];
  const r2 = rawBatchesP2.find((b) => b.id !== r1?.id) || rawBatchesP2[0];
  if (!r1 || !r2) {
    throw new Error(`Need raw material batches for both mixed SKUs; ${p1}=${rawBatchesP1.length}, ${p2}=${rawBatchesP2.length}`);
  }
  const rawBatches = [r1, r2];

  const plan = await api('POST', `/${FACTORY_ID}/production-plans`, {
    productTypeId: String(rich.product.id),
    plannedQuantity: 1.2,
    plannedDate: today(0),
    expectedCompletionDate: today(2),
    customerOrderNumber: AUDIT_PREFIX,
    priority: 5,
    sourceType: 'MANUAL',
    notes: `${AUDIT_PREFIX} headed prod business flow audit`,
    processName: chain.map((p) => p.name).join('→'),
    skipProcessReporting: false,
    isMixedBatch: true,
  });
  const planId = String(plan.id || plan.planId);
  const planNumber = String(plan.planNumber || planId);
  assertTrue(Boolean(planId), '生产计划创建返回 planId', { planNumber });

  await api('POST', `/${FACTORY_ID}/production-plans/${encodeURIComponent(planId)}/start`).catch((err) => {
    assertions.push({ label: '生产计划 start 可执行', pass: false, error: String(err.message || err) });
  });

  const rowMap = new Map();
  async function saveRow(label, body) {
    const result = await api('POST', `/${FACTORY_ID}/production-plans/${encodeURIComponent(planId)}/process-sheet/row`, {
      unit: 'kg',
      seasoningStep: body.processCode === 'shuzhi',
      finished: body.processCode === 'qidiao',
      idempotencyKey: `${AUDIT_PREFIX}-${body.clientRowId}`,
      ...body,
    });
    assertTrue(result?.materialized !== false, `${label} 已物化 WIP/成品批`, { batchNumber: result?.batchNumber });
    assertTrue(Boolean(result?.batchNumber), `${label} 返回批次号`, { batchNumber: result?.batchNumber });
    if (num(result?.rowTotalCost) != null) {
      approx(num(result.unitPrice), num(result.rowTotalCost) / num(body.outputQuantity), 0.02, `${label} 单价 = 行总成本 / 产出`);
    }
    rowMap.set(label, { request: body, result });
    return result;
  }

  const first = chain[0];
  const mid = chain[1];
  const cook = chain.find((p) => p.code === 'shuzhi') || chain[2];
  if (cook.order <= mid.order) cook.order = mid.order + 1;

  const a = await saveRow('首道-A-原料批1', {
    clientRowId: `${AUDIT_PREFIX}-first-a`,
    processCode: first.code,
    processOrder: first.order,
    processName: first.name,
    processDate: today(0),
    productTypeId: p1,
    inputQuantity: 0.32,
    outputQuantity: 0.30,
    rawMaterialInputs: [{ materialBatchId: r1.id, quantity: 0.32 }],
  });
  const b = await saveRow('首道-B-混SKU原料批2', {
    clientRowId: `${AUDIT_PREFIX}-first-b`,
    processCode: first.code,
    processOrder: first.order,
    processName: first.name,
    processDate: today(0),
    productTypeId: p2,
    inputQuantity: 0.24,
    outputQuantity: 0.22,
    rawMaterialInputs: [{ materialBatchId: r2.id, quantity: 0.24 }],
  });
  const c = await saveRow('二道-C-消耗A', {
    clientRowId: `${AUDIT_PREFIX}-mid-c`,
    processCode: mid.code,
    processOrder: mid.order,
    processName: mid.name,
    processDate: today(1),
    productTypeId: p1,
    inputQuantity: 0.18,
    outputQuantity: 0.16,
    upstreamSources: [{ sourceBatchNumber: a.batchNumber, feedQuantityKg: 0.18 }],
  });
  const d = await saveRow('二道-D-消耗B', {
    clientRowId: `${AUDIT_PREFIX}-mid-d`,
    processCode: mid.code,
    processOrder: mid.order,
    processName: mid.name,
    processDate: today(1),
    productTypeId: p2,
    inputQuantity: 0.12,
    outputQuantity: 0.11,
    upstreamSources: [{ sourceBatchNumber: b.batchNumber, feedQuantityKg: 0.12 }],
  });
  const e = await saveRow('三道-E-混SKU混批次混工序跨天', {
    clientRowId: `${AUDIT_PREFIX}-cook-e`,
    processCode: cook.code,
    processOrder: cook.order,
    processName: cook.name,
    processDate: today(2),
    productTypeId: p1,
    inputQuantity: 0.14,
    outputQuantity: 0.13,
    upstreamSources: [
      { sourceBatchNumber: c.batchNumber, feedQuantityKg: 0.08 },
      { sourceBatchNumber: d.batchNumber, feedQuantityKg: 0.06 },
    ],
    potCount: 2,
    potRawKgs: [0.08, 0.06],
  });

  const rowsFirst = contentArray(await api('GET', `/${FACTORY_ID}/production-plans/${encodeURIComponent(planId)}/process-sheet/rows?process=${first.code}&processOrder=${first.order}`));
  const rowsMid = contentArray(await api('GET', `/${FACTORY_ID}/production-plans/${encodeURIComponent(planId)}/process-sheet/rows?process=${mid.code}&processOrder=${mid.order}`));
  const rowsCook = contentArray(await api('GET', `/${FACTORY_ID}/production-plans/${encodeURIComponent(planId)}/process-sheet/rows?process=${cook.code}&processOrder=${cook.order}`));
  assertTrue(rowsFirst.length >= 2, '首道 rows 回读包含两个原料/SKU来源', { count: rowsFirst.length });
  assertTrue(rowsMid.length >= 2, '二道 rows 回读包含两个上游批次', { count: rowsMid.length });
  assertTrue(rowsCook.length >= 1, '混锅工序 rows 回读成功', { count: rowsCook.length });

  const invFirst = contentArray(await api('GET', `/${FACTORY_ID}/production-plans/${encodeURIComponent(planId)}/process-sheet/inventory?process=${first.code}&processOrder=${first.order}`));
  const invMid = contentArray(await api('GET', `/${FACTORY_ID}/production-plans/${encodeURIComponent(planId)}/process-sheet/inventory?process=${mid.code}&processOrder=${mid.order}`));
  const yieldCard = contentArray(await api('GET', `/${FACTORY_ID}/production-plans/${encodeURIComponent(planId)}/process-sheet/inventory/yield-card`));
  assertTrue(invFirst.some((x) => x.batchNumber === a.batchNumber), '首道库存表包含批次 A');
  assertTrue(invFirst.some((x) => x.batchNumber === b.batchNumber), '首道库存表包含批次 B');
  assertTrue(invMid.some((x) => x.batchNumber === c.batchNumber), '二道库存表包含批次 C');
  assertTrue(invMid.some((x) => x.batchNumber === d.batchNumber), '二道库存表包含批次 D');

  const cardA = yieldCard.find((x) => x.batchNumber === a.batchNumber);
  const cardB = yieldCard.find((x) => x.batchNumber === b.batchNumber);
  const cardC = yieldCard.find((x) => x.batchNumber === c.batchNumber);
  const cardD = yieldCard.find((x) => x.batchNumber === d.batchNumber);
  const cardE = yieldCard.find((x) => x.batchNumber === e.batchNumber);
  assertTrue(Boolean(cardE), '双出成率总览包含混锅批次 E', { batchNumber: e.batchNumber });
  assertTrue(Array.isArray(cardE?.sourceBreakdowns) && cardE.sourceBreakdowns.length === 2, '混锅批次 E 有两个来源拆分', { sourceBreakdowns: cardE?.sourceBreakdowns });

  approx(num(cardA?.stepYieldRate), 0.30 / 0.32 * 100, 0.05, '批次 A 对上工序出成率');
  approx(num(cardA?.cumulativeYieldRate), 0.30 / 0.32 * 100, 0.05, '批次 A 对原料累计出成率');
  approx(num(cardB?.stepYieldRate), 0.22 / 0.24 * 100, 0.05, '批次 B 对上工序出成率');

  const cRawEq = 0.18 / 0.30 * 0.32;
  const dRawEq = 0.12 / 0.22 * 0.24;
  approx(num(cardC?.inheritedRawEquivalentQuantity), cRawEq, 0.005, '批次 C 继承原料折算');
  approx(num(cardC?.cumulativeYieldRate), 0.16 / cRawEq * 100, 0.08, '批次 C 对原料累计出成率');
  approx(num(cardD?.inheritedRawEquivalentQuantity), dRawEq, 0.005, '批次 D 继承原料折算');

  const eRawEq = 0.08 / 0.16 * cRawEq + 0.06 / 0.11 * dRawEq;
  approx(num(cardE?.stepYieldRate), 0.13 / 0.14 * 100, 0.08, '批次 E 对上工序出成率');
  approx(num(cardE?.inheritedRawEquivalentQuantity), eRawEq, 0.008, '批次 E 混来源继承原料折算合计');
  approx(num(cardE?.cumulativeYieldRate), 0.13 / eRawEq * 100, 0.10, '批次 E 对原料累计出成率');

  for (const [label, entry] of rowMap.entries()) {
    const cost = num(entry.result?.rowTotalCost);
    if (cost != null) assertTrue(cost >= 0, `${label} 行总成本非负`, { rowTotalCost: cost });
  }
  const eInheritedCost = num(cardE?.inheritedCost);
  const eRowCost = num(cardE?.rowTotalCost);
  if (eInheritedCost != null && eRowCost != null) {
    assertTrue(eRowCost + 0.01 >= eInheritedCost, '混锅批次 E 总成本不小于继承成本', { rowTotalCost: eRowCost, inheritedCost: eInheritedCost });
  }

  const settlementBefore = await api('GET', `/${FACTORY_ID}/production-plans/${encodeURIComponent(planId)}/settlement`).catch((err) => ({ error: err.message }));
  assertTrue(Boolean(settlementBefore?.error), '结单前 settlement 正确提示尚未结单');

  const settlementPrefill = await api('GET', `/${FACTORY_ID}/production-plans/${encodeURIComponent(planId)}/settlement-prefill`);
  const prefill = settlementPrefill?.prefill || {};
  const prefillIssues = settlementPrefill?.audit?.issues || [];
  approx(num(prefill.actualFinishedQuantity), 0.13, 0.005, '结单预填末道实际成品数量');
  const rawPrefillTotal = (prefill.rawMaterialConsumptions || [])
    .reduce((sum, line) => sum + Number(line.quantity || 0), 0);
  approx(rawPrefillTotal, 0.56, 0.005, '结单预填原料实际领用合计');
  assertTrue(prefillIssues.every((it) => it.severity !== 'BLOCKER'), '结单预填无阻塞问题', { issues: prefillIssues });

  const settlementSubmit = await api('POST', `/${FACTORY_ID}/production-plans/${encodeURIComponent(planId)}/settle`, {
    ...prefill,
    idempotencyKey: `${AUDIT_PREFIX}-settle`,
    actualFinishedQuantity: prefill.actualFinishedQuantity ?? 0.13,
    actualSemiFinishedQuantity: prefill.actualSemiFinishedQuantity ?? 0,
    quantityUnit: prefill.quantityUnit || 'kg',
    laborDeferredReason: prefill.laborDeferredReason || '工时稍后补录',
    rawMaterialConsumptions: prefill.rawMaterialConsumptions || [],
    semiFinishedConsumptions: prefill.semiFinishedConsumptions || [],
    auxiliaryConsumptions: prefill.auxiliaryConsumptions || [],
    laborSegments: prefill.laborSegments || [],
  });
  approx(num(settlementSubmit.actualFinishedQuantity), 0.13, 0.005, '提交结单后实际成品数量');
  assertTrue(settlementSubmit.status === 'COMPLETED', '提交结单后生产计划完成', { status: settlementSubmit.status });
  assertTrue(settlementSubmit.postingStatus === 'PENDING_WAREHOUSE_RECEIPT', '提交结单后等待仓库确认入库', { postingStatus: settlementSubmit.postingStatus });

  const settlementAfter = await api('GET', `/${FACTORY_ID}/production-plans/${encodeURIComponent(planId)}/settlement`);
  approx(num(settlementAfter.actualFinishedQuantity), 0.13, 0.005, '结单回读实际成品数量');
  assertTrue(settlementAfter.postingStatus === 'PENDING_WAREHOUSE_RECEIPT', '结单回读 posting 状态正确', { postingStatus: settlementAfter.postingStatus });

  await page.goto(`${APP_URL}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2200);
  const search = page.locator('input[placeholder*="搜索"], input').first();
  if (await search.isVisible().catch(() => false)) {
    await search.fill(planNumber);
    await page.locator('button').filter({ hasText: /搜索/ }).first().click().catch(() => null);
    await page.waitForTimeout(2200);
  }
  await shot('production-plan-list-searched');
  const body = await page.locator('body').innerText().catch(() => '');
  assertTrue(body.includes(planNumber), '生产计划列表可显示新建计划', { planNumber });
  const rowLocator = page.locator('.el-table__row').filter({ hasText: planNumber }).first();
  if (await rowLocator.isVisible().catch(() => false)) {
    await rowLocator.locator('button').filter({ hasText: /逐道录入|逐工序|录入/ }).first().click().catch(() => null);
    await page.waitForTimeout(2500);
    await shot('process-sheet-drawer');
    const drawerText = await page.locator('.el-drawer, .el-dialog, body').last().innerText().catch(() => '');
    assertTrue(/双出成率|库存|出成率|继承原料|分摊成本|成本/.test(drawerText), '逐道录入弹窗显示出成率/库存/成本上下文');
    assertTrue(drawerText.includes(a.batchNumber) || drawerText.includes(c.batchNumber) || drawerText.includes(e.batchNumber), '逐道录入弹窗显示已保存批次号');
  } else {
    assertions.push({ label: '生产计划列表可定位新建计划所在行并打开逐道录入', pass: false, planNumber });
  }

  const result = {
    status: assertions.every((a) => a.pass) && consoleErrors.length === 0 ? 'PASS' : 'FAIL',
    appUrl: APP_URL,
    factoryId: FACTORY_ID,
    playwright: {
      headed: true,
      viewport: '1920x1080',
      port: process.env.PLAYWRIGHT_PORT || null,
      chatId: process.env.PLAYWRIGHT_CHAT_ID || null,
    },
    auditPrefix: AUDIT_PREFIX,
    selected: {
      planId,
      planNumber,
      product: { id: p1, name: pickProductName(rich.product) },
      secondProduct: { id: p2, name: pickProductName(secondProduct.product) },
      chain,
      rawWarehouse,
      rawBatches: [r1, r2],
    },
    createdRows: Object.fromEntries(rowMap.entries()),
    readback: {
      rowsFirstCount: rowsFirst.length,
      rowsMidCount: rowsMid.length,
      rowsCookCount: rowsCook.length,
      invFirst,
      invMid,
      yieldCard,
      settlementBefore,
      settlementPrefill,
      settlementSubmit,
      settlementAfter,
    },
    expectedMath: {
      cRawEq,
      dRawEq,
      eRawEq,
      eStepYield: 0.13 / 0.14 * 100,
      eCumulativeYield: 0.13 / eRawEq * 100,
    },
    assertions,
    consoleErrors,
    apiCalls,
    screenshots,
  };

  await writeFile(path.join(OUT_DIR, 'prod-business-flow-result.json'), JSON.stringify(result, null, 2), 'utf8');
  console.log(JSON.stringify({
    status: result.status,
    outDir: OUT_DIR,
    planNumber,
    failures: assertions.filter((a) => !a.pass),
    consoleErrors,
  }, null, 2));
  await browser.close();
  browserContext = null;
  if (result.status !== 'PASS') process.exitCode = 1;
}

main().catch(async (err) => {
  if (browserContext) await browserContext.close().catch(() => null);
  await mkdir(OUT_DIR, { recursive: true }).catch(() => null);
  await writeFile(path.join(OUT_DIR, 'prod-business-flow-error.txt'), err.stack || String(err), 'utf8').catch(() => null);
  console.error(err);
  process.exitCode = 1;
});
