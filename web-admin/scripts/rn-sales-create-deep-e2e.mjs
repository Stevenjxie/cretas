import { chromium } from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';

const API = process.env.E2E_API_URL || 'https://www.cretaceousfuture.com/api/mobile';
const RN_BASE_URL = process.env.RN_BASE_URL || 'http://localhost:3010';
const FACTORY_ID = process.env.E2E_FACTORY_ID || 'F006';
const PASSWORD = process.env.E2E_PASSWORD || '123456';
const RUN_ID = `RN-SALES-CREATE-${Date.now()}`;
const OUT_DIR = path.resolve('.playwright-mcp', `codex-${RUN_ID}`);

const accounts = {
  sales: process.env.E2E_SALES_USER || 'f006_sales_mgr',
  finance: process.env.E2E_FINANCE_USER || 'f006_finance_mgr',
};

const evidence = {
  runId: RUN_ID,
  api: API,
  rnBaseUrl: RN_BASE_URL,
  factoryId: FACTORY_ID,
  startedAt: new Date().toISOString(),
  steps: [],
  screenshots: [],
  audit: [],
  created: {},
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

async function api(session, method, route, body) {
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
  assert(res.ok && json?.success !== false, `${method} ${route} failed`, { status: res.status, body: json });
  return json;
}

async function screenshot(page, name) {
  const file = path.join(OUT_DIR, `${name}.png`);
  await page.screenshot({ path: file, fullPage: true });
  evidence.screenshots.push(file);
  return file;
}

async function rnLogin(page, username) {
  page.on('dialog', async (dialog) => {
    evidence.steps.push({
      name: 'browser dialog',
      status: 'INFO',
      detail: { type: dialog.type(), message: dialog.message() },
      at: new Date().toISOString(),
    });
    await dialog.accept();
  });

  await page.goto(RN_BASE_URL, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1000);
  const loginBtn = page.locator('[data-testid="landing-login-btn"]');
  if (await loginBtn.count()) await loginBtn.first().click();
  await page.locator('[data-testid="login-username-input"]').waitFor({ timeout: 30000 });
  await page.locator('[data-testid="login-username-input"]').fill(username);
  await page.locator('[data-testid="login-password-input"]').fill(PASSWORD);
  await page.locator('[data-testid="login-submit-btn"]').click();
  await page.locator('[data-testid="sm-tab-sales"], [role="tab"]').first().waitFor({ timeout: 60000 });
}

async function auditScreenshots() {
  for (const file of evidence.screenshots) {
    const stat = await fs.stat(file);
    assert(stat.size > 10_000, 'Screenshot file too small/blank', { file, size: stat.size });
    evidence.audit.push({
      file,
      size: stat.size,
      visualCheck: 'captured before/fill/after states for second-pass review',
    });
  }
  logStep('second-pass screenshot audit manifest', 'PASS', { count: evidence.audit.length });
}

async function openSelector(page, testId, fallbackText) {
  const byTestId = page.locator(`[data-testid="${testId}"]`);
  if (await byTestId.count()) {
    await byTestId.first().click({ force: true });
    return;
  }
  await page.getByText(fallbackText, { exact: true }).last().click({ force: true });
}

async function confirmAppDialog(page) {
  const confirm = page.locator('[data-testid="app-dialog-btn-1"]');
  await confirm.waitFor({ timeout: 15000 });
  await confirm.click();
  await page.waitForTimeout(1000);
  const ok = page.locator('[data-testid="app-dialog-btn-0"]');
  if (await ok.count()) await ok.first().click().catch(() => {});
}

async function approveFinanceTodoInRn(type, refId, prefix) {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true });
  const page = await context.newPage();

  try {
    await rnLogin(page, accounts.finance);
    await page.locator('[data-testid="main-tab-oa-todo"]').waitFor({ timeout: 60000 });
    await page.locator('[data-testid="main-tab-oa-todo"]').click();
    await page.locator('[data-testid="oa-todo-list"]').waitFor({ timeout: 60000 });
    const card = page.locator(`[data-testid="oa-todo-card-${type}-${refId}"]`);
    await card.waitFor({ timeout: 60000 });
    await screenshot(page, `${prefix}-todo-before`);

    await card.locator(`[aria-label="oa-todo-detail-${type}-${refId}"], [data-testid="oa-todo-detail-action"]`).first().click();
    await page.locator('[data-testid="oa-detail-scroll"]').waitFor({ timeout: 60000 });
    await screenshot(page, `${prefix}-todo-detail`);
    await page.locator('[data-testid="oa-detail-scroll"]').evaluate((el) => {
      el.scrollTop = el.scrollHeight;
    });
    await page.waitForTimeout(300);
    await screenshot(page, `${prefix}-todo-detail-bottom`);
    await page.locator('[data-testid="oa-detail-approve"]').click();
    await confirmAppDialog(page);
    await page.locator('[data-testid="oa-todo-list"]').waitFor({ timeout: 60000 }).catch(() => {});
    await screenshot(page, `${prefix}-todo-after`);
  } finally {
    await context.close();
    await browser.close();
  }
}

async function main() {
  await fs.mkdir(OUT_DIR, { recursive: true });
  const sales = await loginApi(accounts.sales);
  const finance = await loginApi(accounts.finance);
  logStep('login sales role by API', 'PASS', { username: accounts.sales, role: sales.user.role });
  logStep('login finance role by API', 'PASS', { username: accounts.finance, role: finance.user.role });

  const customers = firstArray(await api(sales, 'GET', '/customers/active'));
  const products = firstArray(await api(sales, 'GET', '/product-types/active'));
  assert(customers.length > 0, 'No active customer for F006 sales order create');
  assert(products.length > 0, 'No active product for F006 sales order create');
  const customer = customers[0];
  const product = products[0];
  const unit = product.unit || 'kg';
  const price = Number(product.unitPrice ?? 18.88);
  const quantity = Number(process.env.E2E_SALES_QTY || 10000);
  const requiredDeliveryDate = new Date(Date.now() + 2 * 24 * 3600 * 1000).toISOString().slice(0, 10);
  const remark = `${RUN_ID} sales mobile create`;
  logStep('load real customer and product fixtures', 'PASS', {
    customerId: customer.id,
    customerName: customer.name,
    productTypeId: product.id,
    productName: product.name,
    unit,
    price,
  });

  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true });
  const page = await context.newPage();

  try {
    await rnLogin(page, accounts.sales);
    await page.locator('[data-testid="sm-tab-sales"]').click();
    await page.locator('[data-testid="sales-order-list-screen"]').waitFor({ timeout: 60000 });
    await screenshot(page, 'sales-list-before-create');

    await page.locator('[data-testid="sales-order-create-action"]').click();
    await page.locator('[data-testid="sales-order-create-screen"]').waitFor({ timeout: 60000 });
    await screenshot(page, 'sales-create-empty');

    await openSelector(page, 'sales-create-customer', '请选择客户');
    await page.locator(`[data-testid="sales-create-customer-option-${customer.id}"]`).waitFor({ timeout: 30000 });
    await page.locator(`[data-testid="sales-create-customer-option-${customer.id}"]`).click();

    await page.locator('[data-testid="sales-create-required-date"]').fill(requiredDeliveryDate);
    await page.locator('[data-testid="sales-create-remark"]').fill(remark);

    await openSelector(page, 'sales-create-product-0', '请选择产品');
    await page.locator(`[data-testid="sales-create-product-option-${product.id}"]`).waitFor({ timeout: 30000 });
    await page.locator(`[data-testid="sales-create-product-option-${product.id}"]`).click();
    await page.locator('[data-testid="sales-create-quantity-0"]').fill(String(quantity));
    await page.locator('[data-testid="sales-create-unit-0"]').fill(unit);
    await page.locator('[data-testid="sales-create-unit-price-0"]').fill(String(price));
    await page.mouse.wheel(0, 520);
    await page.waitForTimeout(300);
    await screenshot(page, 'sales-create-filled');

    await page.locator('[data-testid="sales-create-submit"]').click();
    await page.waitForTimeout(2500);
    await screenshot(page, 'sales-create-after-submit');
  } finally {
    await context.close();
    await browser.close();
  }

  const list = firstArray(await api(sales, 'GET', '/sales/orders?page=1&size=80'));
  const created = list.find((order) => String(order.remark || '').includes(RUN_ID));
  assert(created, 'Sales order created from RN UI was not found by API readback', {
    runId: RUN_ID,
    latest: list.slice(0, 8).map((order) => ({ id: order.id, orderNumber: order.orderNumber, remark: order.remark, status: order.status })),
  });
  evidence.created.salesOrderId = created.id;
  evidence.created.salesOrderNumber = created.orderNumber;
  evidence.created.status = created.status;
  evidence.created.totalAmount = created.totalAmount;
  assert(created.status === 'DRAFT', 'New sales order should remain draft before explicit confirmation', { status: created.status });
  assert(Number(created.totalAmount) >= quantity * price - 0.01, 'Sales order amount did not reflect UI quantity x price', {
    totalAmount: created.totalAmount,
    expectedMin: quantity * price,
  });
  logStep('RN UI create sales order and API readback', 'PASS', {
    id: created.id,
    orderNumber: created.orderNumber,
    status: created.status,
    totalAmount: created.totalAmount,
  });

  const confirmBrowser = await chromium.launch({ headless: true });
  const confirmContext = await confirmBrowser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true });
  const confirmPage = await confirmContext.newPage();

  try {
    await rnLogin(confirmPage, accounts.sales);
    await confirmPage.locator('[data-testid="sm-tab-sales"]').click();
    await confirmPage.locator('[data-testid="sales-order-list-screen"]').waitFor({ timeout: 60000 });
    await confirmPage.locator(`[data-testid="sales-order-card-${created.id}"]`).waitFor({ timeout: 60000 });
    await screenshot(confirmPage, 'sales-list-before-confirm');

    await confirmPage.locator(`[data-testid="sales-order-confirm-${created.id}"]`).click();
    await confirmPage.waitForTimeout(2000);
    await screenshot(confirmPage, 'sales-list-after-confirm');
  } finally {
    await confirmContext.close();
    await confirmBrowser.close();
  }

  const confirmed = dataOf(await api(sales, 'GET', `/sales/orders/${created.id}`));
  const validConfirmedStatuses = new Set(['CONFIRMED', 'PENDING_FINANCE_REVIEW', 'FINANCE_APPROVED']);
  assert(validConfirmedStatuses.has(confirmed.status), 'RN confirm did not move sales order out of draft', {
    id: created.id,
    status: confirmed.status,
  });
  evidence.created.statusAfterConfirm = confirmed.status;
  logStep('RN UI confirm sales order and API readback', 'PASS', {
    id: created.id,
    orderNumber: confirmed.orderNumber,
    status: confirmed.status,
  });

  if (confirmed.status === 'PENDING_FINANCE_REVIEW') {
    const todos = firstArray(await api(finance, 'GET', '/my-todos'));
    const todo = todos.find((item) => item.type === 'SALES_FINANCE_REVIEW' && String(item.refId) === String(created.id));
    assert(todo, 'Sales finance todo not visible after RN confirm', {
      orderId: created.id,
      todos: todos.slice(0, 12),
    });
    logStep('sales finance todo visible after RN confirm', 'PASS', {
      type: todo.type,
      refId: todo.refId,
      title: todo.title,
    });

    await approveFinanceTodoInRn('SALES_FINANCE_REVIEW', created.id, 'finance-sales');
    const financeApproved = dataOf(await api(finance, 'GET', `/sales/orders/${created.id}`));
    assert(financeApproved.status === 'FINANCE_APPROVED', 'RN finance approval did not approve sales order', {
      orderId: created.id,
      status: financeApproved.status,
    });
    evidence.created.statusAfterFinance = financeApproved.status;
    logStep('RN finance approve sales todo and API readback', 'PASS', {
      id: created.id,
      orderNumber: financeApproved.orderNumber,
      status: financeApproved.status,
    });
  } else {
    logStep('sales finance todo not required by policy', 'PASS', {
      orderId: created.id,
      status: confirmed.status,
    });
  }

  await auditScreenshots();
  evidence.result = 'PASS';
  await writeEvidence();
  console.log(`RESULT ${evidence.result}`);
  console.log(`Evidence: ${path.join(OUT_DIR, 'result.json')}`);
}

main().catch(async (err) => {
  logStep('fatal', 'FAIL', { message: err.message, detail: err.detail, stack: err.stack });
  evidence.result = 'FAIL';
  await writeEvidence();
  console.error(err);
  console.error(`Evidence: ${path.join(OUT_DIR, 'result.json')}`);
  process.exit(1);
});
