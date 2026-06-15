import { chromium } from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';

const API = process.env.E2E_API_URL || 'https://www.cretaceousfuture.com/api/mobile';
const RN_BASE_URL = process.env.RN_BASE_URL || 'http://localhost:3010';
const FACTORY_ID = process.env.E2E_FACTORY_ID || 'F006';
const PASSWORD = process.env.E2E_PASSWORD || '123456';
const RUN_ID = `RN-DEEP-${Date.now()}`;
const OUT_DIR = path.resolve('.playwright-mcp', `codex-${RUN_ID}`);

const accounts = {
  procurement: 'f006_procurement_mgr',
  warehouse: 'f006_warehouse_mgr',
  finance: 'f006_finance_mgr',
  cashier: 'f006_cashier',
  admin: 'f006_admin',
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

async function setupPurchaseChain(sessions) {
  const suppliers = firstArray((await api(sessions.procurement, 'GET', '/suppliers/active')).body);
  const materials = firstArray((await api(sessions.procurement, 'GET', '/raw-material-types/active')).body);
  assert(suppliers.length > 0, 'No active supplier found for F006');
  assert(materials.length > 0, 'No active raw material type found for F006');

  const supplier = suppliers[0];
  const material = materials[0];
  const unit = material.unit || 'kg';
  const tomorrow = new Date(Date.now() + 24 * 3600 * 1000).toISOString().slice(0, 10);
  const today = new Date().toISOString().slice(0, 10);

  const orderPayload = {
    supplierId: supplier.id,
    orderDate: today,
    expectedDeliveryDate: tomorrow,
    purchaseType: 'DIRECT',
    remark: `${RUN_ID} purchase finance todo`,
    settlementType: 'IMMEDIATE',
    items: [{
      materialTypeId: material.id,
      materialName: material.name,
      quantity: 1,
      unit,
      unitPrice: 12.34,
      remark: RUN_ID,
    }],
  };
  const createdOrder = dataOf((await api(sessions.procurement, 'POST', '/purchase/orders', orderPayload)).body);
  const orderId = createdOrder.id;
  evidence.created.purchaseOrderId = orderId;
  evidence.created.purchaseOrderNumber = createdOrder.orderNumber;
  logStep('create purchase order via procurement API', 'PASS', { orderId, orderNumber: createdOrder.orderNumber });

  await api(sessions.procurement, 'POST', `/purchase/orders/${orderId}/submit`);
  logStep('submit purchase order via procurement API', 'PASS', { orderId });

  let approved;
  const procurementApprove = await api(sessions.procurement, 'POST', `/purchase/orders/${orderId}/approve`, undefined, { allowStatus: [403, 409, 500] });
  if (procurementApprove.status >= 400) {
    logStep('procurement approve purchase order', 'WARN', { status: procurementApprove.status, body: procurementApprove.body });
    approved = dataOf((await api(sessions.admin, 'POST', `/purchase/orders/${orderId}/approve`)).body);
  } else {
    approved = dataOf(procurementApprove.body);
  }
  logStep('business approve purchase order', 'PASS', { orderId, status: approved.status });

  const pendingFinance = dataOf((await api(sessions.procurement, 'POST', `/purchase/orders/${orderId}/submit-for-finance-review`)).body);
  assert(pendingFinance.status === 'PENDING_FINANCE_REVIEW', 'PO did not enter finance review', { status: pendingFinance.status });
  logStep('submit purchase order for finance review', 'PASS', { orderId, status: pendingFinance.status });

  const financeTodos = firstArray((await api(sessions.finance, 'GET', '/my-todos')).body);
  const financeTodo = financeTodos.find(t => t.type === 'PURCHASE_FINANCE_REVIEW' && String(t.refId) === String(orderId));
  assert(financeTodo, 'Finance todo not generated for purchase order', { orderId, todos: financeTodos.slice(0, 8) });
  logStep('finance todo generated and visible by API', 'PASS', { type: financeTodo.type, refId: financeTodo.refId, title: financeTodo.title });

  return { supplier, material, unit, orderId };
}

async function createReceiveAndPayment(sessions, chain) {
  const today = new Date().toISOString().slice(0, 10);
  const receivePayload = {
    purchaseOrderId: chain.orderId,
    supplierId: chain.supplier.id,
    receiveDate: today,
    remark: `${RUN_ID} warehouse receive`,
    items: [{
      materialTypeId: chain.material.id,
      materialName: chain.material.name,
      receivedQuantity: 1,
      unit: chain.unit,
      unitPrice: 12.34,
      qcResult: 'PASS',
      remark: RUN_ID,
    }],
  };

  let receiveSession = sessions.warehouse;
  let createdReceiveResp = await api(receiveSession, 'POST', '/purchase/receives', receivePayload, { allowStatus: [403] });
  if (createdReceiveResp.status === 403) {
    logStep('warehouse create purchase receive permission', 'FAIL', { status: 403, body: createdReceiveResp.body });
    receiveSession = sessions.admin;
    createdReceiveResp = await api(receiveSession, 'POST', '/purchase/receives', receivePayload);
  } else {
    logStep('warehouse create purchase receive permission', 'PASS', { status: createdReceiveResp.status });
  }

  const receive = dataOf(createdReceiveResp.body);
  const receiveId = receive.id;
  evidence.created.receiveId = receiveId;
  logStep('create purchase receive record', 'PASS', { receiveId, actor: receiveSession.username });

  let confirmResp = await api(sessions.warehouse, 'POST', `/purchase/receives/${receiveId}/confirm`, undefined, { allowStatus: [403] });
  if (confirmResp.status === 403) {
    logStep('warehouse confirm purchase receive permission', 'FAIL', { status: 403, body: confirmResp.body });
    confirmResp = await api(sessions.admin, 'POST', `/purchase/receives/${receiveId}/confirm`);
  } else {
    logStep('warehouse confirm purchase receive permission', 'PASS', { status: confirmResp.status });
  }
  const confirmedReceive = dataOf(confirmResp.body);
  logStep('confirm purchase receive and generate raw material inventory', 'PASS', { receiveId, status: confirmedReceive.status });

  const receives = firstArray((await api(sessions.procurement, 'GET', `/purchase/receives/by-order/${chain.orderId}`)).body);
  assert(receives.some(r => String(r.id) === String(receiveId)), 'Receive record not persisted by order', { receiveId, count: receives.length });
  logStep('read back receive record by purchase order', 'PASS', { receiveId, count: receives.length });

  const payment = dataOf((await api(sessions.procurement, 'POST', '/payment-requests', {
    purchaseOrderId: chain.orderId,
    supplierId: chain.supplier.id,
    amount: 12.34,
    paymentMethod: 'BANK_TRANSFER',
    remark: `${RUN_ID} payment disburse todo`,
  })).body);
  const paymentId = payment.id;
  evidence.created.paymentRequestId = paymentId;
  evidence.created.paymentRequestNumber = payment.requestNumber;
  logStep('create payment request via procurement API', 'PASS', { paymentId, requestNumber: payment.requestNumber });

  await api(sessions.procurement, 'PUT', `/payment-requests/${paymentId}/submit`);
  const approvedPayment = dataOf((await api(sessions.finance, 'PUT', `/payment-requests/${paymentId}/finance-approve`, { reviewNote: RUN_ID })).body);
  assert(approvedPayment.status === 'APPROVED', 'Payment request did not enter APPROVED', { status: approvedPayment.status });
  logStep('finance approve payment request via API to create cashier todo', 'PASS', { paymentId, status: approvedPayment.status });

  const cashierTodos = firstArray((await api(sessions.cashier, 'GET', '/my-todos')).body);
  const cashierTodo = cashierTodos.find(t => t.type === 'PAYMENT_DISBURSE' && String(t.refId) === String(paymentId));
  assert(cashierTodo, 'Cashier payment todo not generated', { paymentId, todos: cashierTodos.slice(0, 8) });
  logStep('cashier todo generated and visible by API', 'PASS', { type: cashierTodo.type, refId: cashierTodo.refId, title: cashierTodo.title });

  return { paymentId };
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
  await page.locator('[role="tab"], [data-testid="main-tab-oa-todo"]').first().waitFor({ timeout: 60000 });
}

async function clickTodoApprove(page, type, refId, screenshotPrefix) {
  const tab = page.locator('[data-testid="main-tab-oa-todo"]');
  await tab.waitFor({ timeout: 60000 });
  await tab.click();
  await page.locator('[data-testid="oa-todo-list"]').waitFor({ timeout: 60000 });
  const card = page.locator(`[data-testid="oa-todo-card-${type}-${refId}"]`);
  await card.waitFor({ timeout: 60000 });
  const beforePath = path.join(OUT_DIR, `${screenshotPrefix}-todo-before.png`);
  await page.screenshot({ path: beforePath, fullPage: true });
  evidence.screenshots.push(beforePath);

  const action = card.locator(`[aria-label="oa-todo-approve-${type}-${refId}"], [data-testid="oa-todo-approve-action"]`).first();
  await action.click();
  await page.locator('[data-testid="app-dialog-btn-1"]').waitFor({ timeout: 15000 });
  await page.locator('[data-testid="app-dialog-btn-1"]').click();
  await page.waitForTimeout(1500);
  const ok = page.locator('[data-testid="app-dialog-btn-0"]');
  if (await ok.count()) {
    await ok.first().click().catch(() => {});
  }
  const afterPath = path.join(OUT_DIR, `${screenshotPrefix}-todo-after.png`);
  await page.screenshot({ path: afterPath, fullPage: true });
  evidence.screenshots.push(afterPath);
}

async function withBrowser(work) {
  await fs.mkdir(OUT_DIR, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  try {
    await work(browser);
  } finally {
    await browser.close();
  }
}

async function rnApproveFinance(sessions, chain) {
  await withBrowser(async browser => {
    const financeContext = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true });
    const financePage = await financeContext.newPage();
    await rnLogin(financePage, accounts.finance);
    await clickTodoApprove(financePage, 'PURCHASE_FINANCE_REVIEW', chain.orderId, 'finance-purchase');
    await financeContext.close();

    const po = dataOf((await api(sessions.finance, 'GET', `/purchase/orders/${chain.orderId}`)).body);
    assert(po.status === 'FINANCE_APPROVED', 'RN finance approval did not persist PO status', { orderId: chain.orderId, status: po.status });
    logStep('RN finance approves purchase todo and API persists status', 'PASS', { orderId: chain.orderId, status: po.status });
  });
}

async function rnConfirmCashierPayment(sessions, payment) {
  await withBrowser(async browser => {
    const cashierContext = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true });
    const cashierPage = await cashierContext.newPage();
    await rnLogin(cashierPage, accounts.cashier);
    await clickTodoApprove(cashierPage, 'PAYMENT_DISBURSE', payment.paymentId, 'cashier-payment');
    await cashierContext.close();

    const pr = firstArray((await api(sessions.cashier, 'GET', '/payment-requests?status=PAID')).body)
      .find(x => String(x.id) === String(payment.paymentId));
    assert(pr?.status === 'PAID', 'RN cashier payment did not persist PAID status', { paymentId: payment.paymentId, found: pr });
    logStep('RN cashier confirms payment todo and API persists PAID status', 'PASS', { paymentId: payment.paymentId, status: pr.status });
  });
}

async function main() {
  await fs.mkdir(OUT_DIR, { recursive: true });
  const sessions = {};
  for (const key of ['admin', 'warehouse', 'finance', 'cashier']) {
    const username = accounts[key];
    sessions[key] = await loginApi(username);
  }
  try {
    sessions.procurement = await loginApi(accounts.procurement);
  } catch (err) {
    logStep('login f006 procurement manager', 'FAIL', { message: err.message, detail: err.detail });
    sessions.procurement = sessions.admin;
    sessions.procurement.username = accounts.admin;
    logStep('fallback procurement actor to factory admin for setup only', 'WARN', { reason: 'f006_procurement_mgr login 500', actor: accounts.admin });
  }
  logStep('login all business roles', 'PASS', Object.fromEntries(Object.entries(sessions).map(([k, v]) => [k, v.user.role])));

  const chain = await setupPurchaseChain(sessions);
  await rnApproveFinance(sessions, chain);
  const payment = await createReceiveAndPayment(sessions, chain);
  await rnConfirmCashierPayment(sessions, payment);

  evidence.result = evidence.steps.some(s => s.status === 'FAIL') ? 'FAIL_WITH_FALLBACK' : 'PASS';
  await writeEvidence();
  console.log(`RESULT ${evidence.result}`);
  console.log(`Evidence: ${path.join(OUT_DIR, 'result.json')}`);
  if (evidence.result !== 'PASS') process.exitCode = 2;
}

main().catch(async err => {
  logStep('fatal', 'FAIL', { message: err.message, detail: err.detail, stack: err.stack });
  evidence.result = 'FAIL';
  await writeEvidence();
  console.error(err);
  console.error(`Evidence: ${path.join(OUT_DIR, 'result.json')}`);
  process.exit(1);
});
