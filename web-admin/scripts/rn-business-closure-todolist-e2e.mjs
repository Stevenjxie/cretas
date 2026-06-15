import { chromium } from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';

const API = process.env.E2E_API_URL || 'https://www.cretaceousfuture.com/api/mobile';
const RN_BASE_URL = process.env.RN_BASE_URL || 'http://localhost:3010';
const FACTORY_ID = process.env.E2E_FACTORY_ID || 'F006';
const PASSWORD = process.env.E2E_PASSWORD || '123456';
const RUN_ID = process.env.E2E_RUN_ID || `RN-TODOLIST-${Date.now()}`;
const OUT_DIR = path.resolve('.playwright-mcp', `codex-${RUN_ID}`);
const SSH_HOST = process.env.E2E_DB_SSH_HOST || 'root@47.100.235.168';
const PSQL = process.env.E2E_DB_PSQL || 'PGPASSWORD=cretas123 psql -U cretas_user -h 127.0.0.1 -d cretas_prod_db';

const accounts = {
  admin: 'f006_admin',
  sales: 'f006_sales_mgr',
  procurement: 'f006_procurement_mgr',
  warehouse: 'f006_warehouse_mgr',
  finance: 'f006_finance_mgr',
};

const evidence = {
  runId: RUN_ID,
  api: API,
  rnBaseUrl: RN_BASE_URL,
  factoryId: FACTORY_ID,
  startedAt: new Date().toISOString(),
  steps: [],
  created: {},
  screenshots: [],
  audit: [],
};

function logStep(name, status, detail = {}) {
  const step = { name, status, detail, at: new Date().toISOString() };
  evidence.steps.push(step);
  console.log(`[${status}] ${name}`, JSON.stringify(detail));
}

function assert(condition, message, detail = {}) {
  if (!condition) {
    const err = new Error(message);
    err.detail = detail;
    throw err;
  }
}

function dataOf(json) {
  return json?.data ?? json;
}

function firstArray(payload) {
  const data = dataOf(payload);
  if (Array.isArray(data)) return data;
  if (Array.isArray(data?.content)) return data.content;
  if (Array.isArray(data?.items)) return data.items;
  if (Array.isArray(data?.records)) return data.records;
  return [];
}

function today(offsetDays = 0) {
  const d = new Date(Date.now() + offsetDays * 24 * 3600 * 1000);
  return d.toISOString().slice(0, 10);
}

function month() {
  return today().slice(0, 7);
}

function sqlString(value) {
  if (value == null) return 'NULL';
  return `'${String(value).replaceAll("'", "''")}'`;
}

function sqlId(prefix) {
  return `${prefix}-${RUN_ID}-${randomUUID().slice(0, 8)}`.slice(0, 63);
}

function runSql(sql) {
  const script = `${PSQL} -v ON_ERROR_STOP=1 -At <<'SQL'\n${sql}\nSQL`;
  return execFileSync('ssh', [SSH_HOST, script], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

async function writeEvidence() {
  evidence.finishedAt = new Date().toISOString();
  await fs.mkdir(OUT_DIR, { recursive: true });
  await fs.writeFile(path.join(OUT_DIR, 'result.json'), JSON.stringify(evidence, null, 2), 'utf8');
}

async function loginApi(username) {
  const res = await fetch(`${API}/auth/unified-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username,
      password: PASSWORD,
      factoryId: FACTORY_ID,
      deviceInfo: { platform: 'Web', deviceId: RUN_ID },
    }),
  });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { json = { raw: text }; }
  assert(res.ok && dataOf(json)?.token, `API login failed for ${username}`, { status: res.status, body: json });
  return { username, token: dataOf(json).token, user: dataOf(json) };
}

async function api(session, method, route, body, options = {}) {
  const res = await fetch(`${API}/${FACTORY_ID}${route}`, {
    method,
    headers: {
      Authorization: `Bearer ${session.token}`,
      'Content-Type': 'application/json',
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { json = { raw: text }; }
  if (!options.allowStatus?.includes(res.status)) {
    assert(res.ok && json?.success !== false, `${method} ${route} failed`, { status: res.status, body: json });
  }
  return { status: res.status, body: json };
}

async function requireTodo(session, type, refId) {
  const todos = firstArray((await api(session, 'GET', '/my-todos')).body);
  const todo = todos.find(t => t.type === type && String(t.refId) === String(refId));
  assert(todo, `Todo ${type} not visible`, { refId, todos: todos.slice(0, 12) });
  logStep(`todo visible by API: ${type}`, 'PASS', { refId, refNumber: todo.refNumber, needDetail: todo.needDetail, amount: todo.amount });
  return todo;
}

async function createSalesFinanceTodo(sessions) {
  const customers = firstArray((await api(sessions.sales, 'GET', '/customers?page=1&size=10')).body);
  const products = firstArray((await api(sessions.sales, 'GET', '/product-types/active')).body);
  assert(customers.length > 0 && products.length > 0, 'Missing sales customer/product seed data');
  const customer = customers[0];
  const product = products[0];
  const created = dataOf((await api(sessions.sales, 'POST', '/sales/orders', {
    customerId: customer.id,
    orderDate: today(),
    requiredDeliveryDate: today(1),
    remark: `${RUN_ID} sales finance todo`,
    items: [{
      productTypeId: product.id,
      productName: product.name,
      quantity: 1,
      unit: product.unit || '盒',
      unitPrice: 12.34,
      taxRate: 13,
      remark: RUN_ID,
    }],
  })).body);
  await api(sessions.sales, 'POST', `/sales/orders/${created.id}/confirm`);
  const pending = dataOf((await api(sessions.sales, 'POST', `/sales/orders/${created.id}/submit-for-review`)).body);
  assert(pending.status === 'PENDING_FINANCE_REVIEW', 'Sales order did not enter finance review', { status: pending.status });
  evidence.created.salesOrderId = created.id;
  evidence.created.salesOrderNumber = created.orderNumber;
  logStep('create sales finance todo', 'PASS', { id: created.id, orderNumber: created.orderNumber });
  await requireTodo(sessions.finance, 'SALES_FINANCE_REVIEW', created.id);
  return { id: created.id };
}

async function createReturnFinanceTodo(sessions) {
  const customers = firstArray((await api(sessions.sales, 'GET', '/customers?page=1&size=10')).body);
  const products = firstArray((await api(sessions.sales, 'GET', '/product-types/active')).body);
  assert(customers.length > 0 && products.length > 0, 'Missing return customer/product seed data');
  const created = dataOf((await api(sessions.sales, 'POST', '/return-orders', {
    returnType: 'SALES_RETURN',
    counterpartyId: customers[0].id,
    returnDate: today(),
    reason: `${RUN_ID} return finance reason`,
    withGoods: false,
    remark: RUN_ID,
    items: [{
      productTypeId: products[0].id,
      itemName: products[0].name,
      quantity: 1,
      unitPrice: 12.34,
      reason: RUN_ID,
    }],
  })).body);
  await api(sessions.sales, 'POST', `/return-orders/${created.id}/submit`);
  const approved = dataOf((await api(sessions.sales, 'POST', `/return-orders/${created.id}/approve`)).body);
  assert(approved.status === 'APPROVED', 'Return order did not enter finance review', { status: approved.status });
  evidence.created.returnOrderId = created.id;
  evidence.created.returnNumber = created.returnNumber;
  logStep('create return finance todo', 'PASS', { id: created.id, returnNumber: created.returnNumber });
  await requireTodo(sessions.finance, 'RETURN_FINANCE_REVIEW', created.id);
  return { id: created.id };
}

async function createPriceAnomalyTodo(sessions) {
  const suppliers = firstArray((await api(sessions.procurement, 'GET', '/suppliers/active')).body);
  const materials = firstArray((await api(sessions.procurement, 'GET', '/raw-material-types/active')).body);
  const warehouses = firstArray((await api(sessions.warehouse, 'GET', '/factory/warehouses')).body);
  assert(suppliers.length > 0 && materials.length > 0 && warehouses.length > 0, 'Missing supplier/material/warehouse seed data');
  const supplier = suppliers[0];
  const material = materials.find(m => Number(m.movingAvgPrice ?? 0) > 0) ?? materials[0];
  const warehouse = warehouses.find(w => w.type === 'RAW' || w.type === 'MATERIAL') ?? warehouses[warehouses.length - 1];
  const noteNumber = `DN-${RUN_ID}`.slice(0, 48);
  const note = dataOf((await api(sessions.warehouse, 'POST', '/warehouse/supplier-delivery-notes/manual', {
    supplierId: supplier.id,
    supplierName: supplier.name,
    deliveryDate: today(),
    warehouseId: warehouse.id,
    noteNumber,
    lines: [{
      ingredientName: material.name,
      rawMaterialTypeId: material.id,
      quantity: 1,
      unit: material.unit || 'kg',
      unitPrice: 999,
      baselineUnitPrice: 1,
      priceAnomalyFlag: true,
      priceAnomalyReasonCode: 'PRICE_INCREASE',
      priceAnomalyExplanation: `${RUN_ID} supplier price anomaly explanation`,
      qcResult: 'PASS',
    }],
  })).body);
  const submitted = await api(sessions.warehouse, 'POST', `/warehouse/supplier-delivery-notes/${note.id}/price-anomaly/submit`, undefined, { allowStatus: [409] });
  assert(submitted.status !== 409, 'Price anomaly submit rejected', { noteId: note.id, body: submitted.body });
  evidence.created.priceAnomalyNoteId = note.id;
  evidence.created.priceAnomalyNoteNumber = note.noteNumber || noteNumber;
  logStep('create price anomaly todo', 'PASS', { id: note.id, noteNumber: note.noteNumber || noteNumber });
  await requireTodo(sessions.finance, 'PRICE_ANOMALY', note.id);
  return { id: note.id };
}

async function seedStocktakeTodo(sessions) {
  const warehouses = firstArray((await api(sessions.warehouse, 'GET', '/factory/warehouses')).body);
  assert(warehouses.length > 0, 'Missing warehouse seed data');
  const id = sqlId('ST');
  const stocktakeNo = `ST-${RUN_ID}`.slice(0, 50);
  runSql(`
INSERT INTO factory_stocktakes
(id, factory_id, stocktake_no, warehouse_id, period_month, status, initiated_by, initiated_at, submitted_by, submitted_at, notes, created_at, updated_at)
VALUES
(${sqlString(id)}, ${sqlString(FACTORY_ID)}, ${sqlString(stocktakeNo)}, ${sqlString(warehouses[0].id)}, ${sqlString(month())}, 'PENDING_APPROVAL',
 ${Number(sessions.warehouse.user.id)}, NOW(), ${Number(sessions.warehouse.user.id)}, NOW(), ${sqlString(RUN_ID)}, NOW(), NOW());
`);
  evidence.created.stocktakeId = id;
  evidence.created.stocktakeNo = stocktakeNo;
  logStep('seed stocktake pending approval by SQL', 'PASS', { id, stocktakeNo, warehouseId: warehouses[0].id });
  await requireTodo(sessions.finance, 'STOCKTAKE_APPROVAL', id);
  return { id };
}

async function seedTransitReceipt(sessions) {
  const products = firstArray((await api(sessions.sales, 'GET', '/product-types/active')).body);
  assert(products.length > 0, 'Missing product seed data for production settlement');
  const product = products[0];
  const planId = sqlId('PLANID');
  const settlementId = sqlId('SETTLE');
  const planNumber = `PLAN-${RUN_ID}`.slice(0, 50);
  runSql(`
INSERT INTO production_plans
(id, created_at, updated_at, created_by, factory_id, plan_number, plan_type, planned_quantity, product_type_id,
 status, actual_quantity, planned_date, expected_completion_date, priority, notes, vflag, is_locked,
 plan_source_type, source_order_ids, skip_process_reporting, start_time, end_time)
VALUES
(${sqlString(planId)}, NOW(), NOW(), ${Number(sessions.warehouse.user.id)}, ${sqlString(FACTORY_ID)}, ${sqlString(planNumber)},
 'FROM_INVENTORY', 2.00, ${sqlString(product.id)}, 'COMPLETED', 2.00, CURRENT_DATE, CURRENT_DATE, 5,
 ${sqlString(`${RUN_ID} production transit seed`)}, 'CREATED', false, 'NORMAL', '[]'::jsonb, true, NOW(), NOW());

INSERT INTO production_settlements
(id, factory_id, production_plan_id, plan_number, idempotency_key, planned_quantity, actual_finished_quantity,
 actual_semi_finished_quantity, quantity_unit, plan_status_after, posting_status, posting_message, settled_by,
 settled_at, created_at, updated_at)
VALUES
(${sqlString(settlementId)}, ${sqlString(FACTORY_ID)}, ${sqlString(planId)}, ${sqlString(planNumber)}, ${sqlString(`${RUN_ID}:settle`)},
 2.00, 2.00, 0.00, ${sqlString(product.unit || '盒')}, 'COMPLETED', 'PENDING_WAREHOUSE_RECEIPT',
 ${sqlString(`${RUN_ID} waiting warehouse receipt`)}, ${Number(sessions.warehouse.user.id)}, NOW(), NOW(), NOW());
`);
  evidence.created.productionPlanId = planId;
  evidence.created.productionSettlementId = settlementId;
  evidence.created.productionPlanNumber = planNumber;
  const rows = firstArray((await api(sessions.warehouse, 'GET', '/warehouse/transit-ledgers?status=PENDING_CONFIRMATION')).body);
  const row = rows.find(x => String(x.id) === String(planId));
  assert(row, 'Seeded transit receipt not visible by API', { planId, rows: rows.slice(0, 8) });
  logStep('seed warehouse transit receipt by SQL and verify API queue', 'PASS', { planId, sourceNumber: row.sourceNumber, reportedQuantity: row.reportedQuantity });
  return { id: planId, receivedQuantity: Number(row.reportedQuantity ?? 2) };
}

async function rnLogin(page, username) {
  await page.goto(RN_BASE_URL, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1000);
  const loginBtn = page.locator('[data-testid="landing-login-btn"]');
  if (await loginBtn.count()) await loginBtn.first().click();
  await page.locator('[data-testid="login-username-input"]').waitFor({ timeout: 30000 });
  await page.locator('[data-testid="login-username-input"]').fill(username);
  await page.locator('[data-testid="login-password-input"]').fill(PASSWORD);
  await page.locator('[data-testid="login-submit-btn"]').click();
  await page.locator('[role="tab"], [data-testid="wh-tab-inventory"]').first().waitFor({ timeout: 60000 });
}

async function screenshot(page, name) {
  const file = path.join(OUT_DIR, `${name}.png`);
  await page.screenshot({ path: file, fullPage: true });
  evidence.screenshots.push(file);
  return file;
}

async function confirmDialog(page) {
  const confirm = page.locator('[data-testid="app-dialog-btn-1"]');
  await confirm.waitFor({ timeout: 15000 });
  await confirm.click();
  await page.waitForTimeout(1200);
  const ok = page.locator('[data-testid="app-dialog-btn-0"]');
  if (await ok.count()) await ok.first().click().catch(() => {});
}

async function approveTodo(page, type, refId, prefix, useDetail = false) {
  await page.locator('[data-testid="main-tab-oa-todo"]').waitFor({ timeout: 60000 });
  await page.locator('[data-testid="main-tab-oa-todo"]').click();
  await page.locator('[data-testid="oa-todo-list"]').waitFor({ timeout: 60000 });
  const card = page.locator(`[data-testid="oa-todo-card-${type}-${refId}"]`);
  await card.waitFor({ timeout: 60000 });
  await screenshot(page, `${prefix}-before`);
  if (useDetail) {
    await card.locator(`[aria-label="oa-todo-detail-${type}-${refId}"]`).click();
    await page.locator('[data-testid="oa-detail-scroll"]').waitFor({ timeout: 60000 });
    await screenshot(page, `${prefix}-detail`);
    await page.locator('[data-testid="oa-detail-approve"]').click();
    await confirmDialog(page);
    await page.waitForTimeout(1000);
  } else {
    await card.locator(`[aria-label="oa-todo-approve-${type}-${refId}"], [data-testid="oa-todo-approve-action"]`).first().click();
    await confirmDialog(page);
  }
  await page.locator('[data-testid="oa-todo-list"]').waitFor({ timeout: 60000 }).catch(() => {});
  await screenshot(page, `${prefix}-after`);
}

async function rnApproveFinanceTodos(sessions, targets) {
  await fs.mkdir(OUT_DIR, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true });
  const page = await context.newPage();
  try {
    await rnLogin(page, accounts.finance);
    await approveTodo(page, 'SALES_FINANCE_REVIEW', targets.sales.id, 'finance-sales', false);
    const so = dataOf((await api(sessions.finance, 'GET', `/sales/orders/${targets.sales.id}`)).body);
    assert(so.status === 'FINANCE_APPROVED', 'Sales finance approval did not persist', { status: so.status });
    logStep('RN approve sales finance todo and readback', 'PASS', { id: targets.sales.id, status: so.status });

    await approveTodo(page, 'RETURN_FINANCE_REVIEW', targets.returnOrder.id, 'finance-return', false);
    const ro = dataOf((await api(sessions.finance, 'GET', `/return-orders/${targets.returnOrder.id}`)).body);
    assert(ro.status === 'FINANCE_APPROVED', 'Return finance approval did not persist', { status: ro.status });
    logStep('RN approve return finance todo and readback', 'PASS', { id: targets.returnOrder.id, status: ro.status });

    await approveTodo(page, 'PRICE_ANOMALY', targets.priceAnomaly.id, 'finance-price-anomaly', false);
    const note = dataOf((await api(sessions.finance, 'GET', `/warehouse/supplier-delivery-notes/${targets.priceAnomaly.id}`)).body);
    assert(note.priceAnomalyApprovalStatus === 'APPROVED', 'Price anomaly approval did not persist', { status: note.priceAnomalyApprovalStatus });
    logStep('RN approve price anomaly todo and readback', 'PASS', { id: targets.priceAnomaly.id, status: note.priceAnomalyApprovalStatus });

    await approveTodo(page, 'STOCKTAKE_APPROVAL', targets.stocktake.id, 'finance-stocktake', true);
    const stocktake = dataOf((await api(sessions.finance, 'GET', `/stocktakes/${targets.stocktake.id}`)).body);
    assert(stocktake.status === 'APPROVED', 'Stocktake approval did not persist', { status: stocktake.status });
    logStep('RN approve stocktake todo via detail and readback', 'PASS', { id: targets.stocktake.id, status: stocktake.status });
  } finally {
    await context.close();
    await browser.close();
  }
}

async function rnConfirmTransit(sessions, transit) {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true });
  const page = await context.newPage();
  try {
    await rnLogin(page, accounts.warehouse);
    await page.locator('[data-testid="wh-tab-inventory"]').click();
    await page.locator('[data-testid="wh-quick-action-transit"]').waitFor({ timeout: 60000 });
    await page.locator('[data-testid="wh-quick-action-transit"]').click();
    await page.locator('[data-testid="transit-ledger-list"]').waitFor({ timeout: 60000 });
    await page.locator(`[data-testid="transit-card-${transit.id}"]`).waitFor({ timeout: 60000 });
    await screenshot(page, 'warehouse-transit-before');
    await page.locator(`[data-testid="transit-received-${transit.id}"]`).fill(String(transit.receivedQuantity));
    await screenshot(page, 'warehouse-transit-filled');
    await page.locator(`[data-testid="transit-confirm-${transit.id}"]`).click();
    await page.waitForTimeout(1500);
    const ok = page.locator('[data-testid="app-dialog-btn-0"]');
    if (await ok.count()) await ok.first().click().catch(() => {});
    await screenshot(page, 'warehouse-transit-after');
    const settlement = dataOf((await api(sessions.warehouse, 'GET', `/production-plans/${transit.id}/settlement`)).body);
    assert(settlement.postingStatus === 'POSTED_TO_FINISHED_GOODS', 'Transit receipt did not post finished goods', {
      postingStatus: settlement.postingStatus,
      warehouseReceivedQuantity: settlement.warehouseReceivedQuantity,
    });
    logStep('RN confirm warehouse transit receipt and readback', 'PASS', {
      id: transit.id,
      postingStatus: settlement.postingStatus,
      warehouseReceivedQuantity: settlement.warehouseReceivedQuantity,
    });
  } finally {
    await context.close();
    await browser.close();
  }
}

async function auditScreenshots() {
  for (const file of evidence.screenshots) {
    const stat = await fs.stat(file);
    assert(stat.size > 10_000, 'Screenshot file too small/blank', { file, size: stat.size });
    evidence.audit.push({
      file,
      size: stat.size,
      visualCheck: 'captured for second-pass manual review: before/detail/after states, no coordinate-only action',
    });
  }
  logStep('second-pass screenshot audit manifest', 'PASS', { count: evidence.audit.length });
}

async function main() {
  await fs.mkdir(OUT_DIR, { recursive: true });
  const sessions = {};
  for (const [key, username] of Object.entries(accounts)) {
    sessions[key] = await loginApi(username);
  }
  logStep('login all roles', 'PASS', Object.fromEntries(Object.entries(sessions).map(([k, v]) => [k, v.user.role])));

  const targets = {
    sales: await createSalesFinanceTodo(sessions),
    returnOrder: await createReturnFinanceTodo(sessions),
    priceAnomaly: await createPriceAnomalyTodo(sessions),
    stocktake: await seedStocktakeTodo(sessions),
  };
  const transit = await seedTransitReceipt(sessions);

  await rnApproveFinanceTodos(sessions, targets);
  await rnConfirmTransit(sessions, transit);
  await auditScreenshots();

  evidence.result = 'PASS';
  await writeEvidence();
  console.log(`RESULT ${evidence.result}`);
  console.log(`Evidence: ${path.join(OUT_DIR, 'result.json')}`);
}

main().catch(async err => {
  logStep('fatal', 'FAIL', { message: err.message, detail: err.detail, stack: err.stack });
  evidence.result = 'FAIL';
  await writeEvidence();
  console.error(err);
  console.error(`Evidence: ${path.join(OUT_DIR, 'result.json')}`);
  process.exit(1);
});
