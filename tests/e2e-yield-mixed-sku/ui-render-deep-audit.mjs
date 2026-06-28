/**
 * UI-layer DEEP E2E — verifies the production process-sheet yield/cost numbers
 * that the CUSTOMER ACTUALLY SEES in web-admin are accurate.
 *
 * Gap this closes vs prod-business-flow-audit.mjs (API-only):
 *   - That test asserts BACKEND numbers via API. It never reads the rendered DOM,
 *     so frontend display bugs (#1 漏字段, #7 结单弹窗丢混SKU行) are invisible to it.
 *
 * This test does a THREE-WAY check per number:
 *     oracle (first-principles, computed in this script)
 *       == yield-card API (backend)
 *       == rendered UI text (DOM in the 双出成率总览 yield card)
 *   If all three agree, the number is trustworthy end-to-end.
 *   If UI != API  -> frontend display bug.
 *   If API != oracle -> backend calc bug.
 *
 * Plus: submits the 核对结单 settlement THROUGH THE UI (not API) to validate the
 * mixed-SKU row-preservation fix (#7) end-to-end.
 *
 * Headed Playwright per .claude/rules/playwright-headed-mode.md.
 *
 * Env: E2E_USERNAME E2E_PASSWORD [E2E_ADMIN_URL E2E_FACTORY_ID PLAYWRIGHT_PORT]
 */
import { chromium } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const APP_URL = process.env.E2E_ADMIN_URL || 'http://139.196.165.140:8086';
const FACTORY_ID = process.env.E2E_FACTORY_ID || 'F006';
const OUT_DIR = path.resolve(process.env.E2E_OUT || `.playwright-mcp/ui-deep-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const PROFILE_DIR = path.join(OUT_DIR, `profile-${Date.now().toString(36)}`);
const TS = new Date().toISOString().replace(/[-:.TZ]/g, '').slice(0, 14);
const AUDIT_PREFIX = `UiDeep-${TS}`;
const PORT_ARG = process.env.PLAYWRIGHT_PORT ? [`--remote-debugging-port=${process.env.PLAYWRIGHT_PORT}`] : [];

const screenshots = [];
const consoleErrors = [];
const apiCalls = [];
const assertions = [];
let browserContext = null;

function reqEnv(name) {
  const v = process.env[name];
  if (!v) throw new Error(`${name} is required`);
  return v;
}
const USERNAME = reqEnv('E2E_USERNAME');
const PASSWORD = reqEnv('E2E_PASSWORD');

function today(off = 0) {
  const d = new Date(Date.now() + 8 * 3600 * 1000 + off * 86400 * 1000);
  return d.toISOString().slice(0, 10);
}
function num(v) {
  if (v == null || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}
function assert(pass, label, detail = {}) {
  assertions.push({ label, pass: Boolean(pass), ...detail });
  if (!pass) console.log(`  [FAIL] ${label} ${JSON.stringify(detail)}`);
  return Boolean(pass);
}
function approx(actual, expected, tol, label, extra = {}) {
  const pass = actual != null && expected != null && Math.abs(actual - expected) <= tol;
  assertions.push({ label, pass, actual, expected, tol, ...extra });
  if (!pass) console.log(`  [FAIL] ${label}: actual=${actual} expected=${expected} tol=${tol}`);
  return pass;
}
function unwrap(json) {
  if (!json || typeof json !== 'object') return json;
  if ('success' in json && json.success === false) throw new Error(json.message || JSON.stringify(json));
  return 'data' in json ? json.data : json;
}
function arr(p) {
  if (Array.isArray(p)) return p;
  for (const k of ['content', 'records', 'items']) if (Array.isArray(p?.[k])) return p[k];
  return [];
}
function pickRawWarehouse(ws) {
  return ws.find((w) => w.code === 'WH-LOG' && w.isActive !== false)
    || ws.find((w) => ['RAW', 'LOGISTICS'].includes(String(w.type || '')) && w.isActive !== false);
}
function processCodeFor(item, index) {
  const name = String(item.processName || '').toLowerCase();
  const cat = String(item.defaultCostCategory || '').toUpperCase();
  if (index === 0) return 'xiuyou';
  if (name.includes('滚揉')) return 'gunrou';
  if (name.includes('去舌')) return 'qushetou';
  if (name.includes('熟') || cat === 'SEASONING') return 'shuzhi';
  if (name.includes('气调') || name.includes('包装') || cat === 'PACKAGING') return 'qidiao';
  return 'chaoshui';
}

async function main() {
  await mkdir(OUT_DIR, { recursive: true });
  const browser = await chromium.launchPersistentContext(PROFILE_DIR, {
    headless: false,
    viewport: { width: 1920, height: 1080 },
    args: ['--lang=zh-CN', '--font-render-hinting=none', ...PORT_ARG],
  });
  browserContext = browser;
  const page = browser.pages()[0] || await browser.newPage();
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push(e.message));

  let shotN = 0;
  async function shot(name, full = true) {
    const file = path.join(OUT_DIR, `${String(++shotN).padStart(2, '0')}-${name}.png`);
    await page.screenshot({ path: file, fullPage: full });
    screenshots.push(file);
  }

  // ---- login (UI) ----
  await page.goto(`${APP_URL}/login`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.locator('.login-form input, input').nth(0).fill(USERNAME);
  await page.locator('.login-form input[type="password"], input[type="password"], .login-form input').nth(1).fill(PASSWORD);
  let submit = page.locator('.login-button').first();
  if (!(await submit.isVisible().catch(() => false))) submit = page.locator('button[type="submit"], button.el-button--primary, button').first();
  await Promise.all([
    page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 30000 }).catch(() => null),
    submit.click().catch(() => page.keyboard.press('Enter')),
  ]);
  await page.waitForTimeout(2500);
  await page.waitForFunction(() => Boolean(localStorage.getItem('cretas_access_token')), null, { timeout: 10000 }).catch(() => null);
  const token = await page.evaluate(() => localStorage.getItem('cretas_access_token') || localStorage.getItem('token') || '');
  if (!token) throw new Error('login produced no access token');
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

  async function api(method, p, body) {
    const res = await fetch(`${APP_URL}/api/mobile${p}`, { method, headers, body: body == null ? undefined : JSON.stringify(body) });
    const text = await res.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { json = { raw: text }; }
    apiCalls.push({ method, p, status: res.status, ok: res.ok, message: json?.message });
    if (!res.ok) throw new Error(`${method} ${p} -> ${res.status}: ${text.slice(0, 400)}`);
    return unwrap(json);
  }

  // ---- discover product + processes + raw batches (same proven logic) ----
  const products = arr(await api('GET', `/${FACTORY_ID}/product-types/active`));
  const candidates = [];
  for (const pr of products.slice(0, 80)) {
    const id = String(pr.id || pr.productTypeId || '');
    if (!id) continue;
    const procs = arr(await api('GET', `/${FACTORY_ID}/product-work-processes?productTypeId=${encodeURIComponent(id)}`))
      .filter((it) => it.isActive !== false)
      .sort((a, b) => Number(a.processOrder || 0) - Number(b.processOrder || 0));
    if (procs.length >= 3) candidates.push({ product: pr, processes: procs });
    if (candidates.length >= 8) break;
  }
  if (!candidates.length) throw new Error('no product with >=3 processes');
  const uniqOrders = (c) => { const o = c.processes.slice(0, 4).map((p, i) => Number(p.processOrder || i + 1)); return new Set(o).size === o.length; };
  const rich = candidates.find((c) => uniqOrders(c) && c.processes.some((p) => /熟|气调|包装/.test(String(p.processName || '')))) || candidates.find(uniqOrders) || candidates[0];
  const second = candidates.find((c) => String(c.product.id) !== String(rich.product.id)) || rich;
  const p1 = String(rich.product.id);
  const p2 = String(second.product.id || rich.product.id);
  const chain = rich.processes.slice(0, 4).map((it, i) => ({ order: i + 1, name: it.processName || `工序${i + 1}`, code: processCodeFor(it, i) }));
  while (chain.length < 4) chain.push({ order: chain.length + 1, name: `补充${chain.length + 1}`, code: chain.length === 2 ? 'shuzhi' : 'chaoshui' });
  chain[0].code = 'xiuyou';
  if (!chain.slice(2).some((p) => p.code === 'shuzhi')) chain[2].code = 'shuzhi';

  const warehouses = arr(await api('GET', `/${FACTORY_ID}/factory/warehouses`));
  const rawWh = pickRawWarehouse(warehouses);
  if (!rawWh?.id) throw new Error('no raw warehouse');
  async function rawBatches(ptid) {
    const r = await api('GET', `/${FACTORY_ID}/material-batches/status/AVAILABLE?warehouseId=${encodeURIComponent(rawWh.id)}&productTypeId=${encodeURIComponent(ptid)}&size=200`);
    return arr(r).map((b) => ({
      id: String(b.id || ''), batchNumber: b.batchNumber || b.id,
      currentQuantity: num(b.currentQuantity ?? b.availableQuantity ?? b.quantity),
      unitPrice: num(b.unitPrice ?? b.price),
      unit: String(b.quantityUnit || b.unit || ''),
    })).filter((b) => b.id && (b.currentQuantity == null || b.currentQuantity > 0.5)
      && !/件|个|只|pcs|pc$/i.test(b.unit)); // 排除计数单位包材(避免把吸塑盒当原料投产)
  }
  const rb1 = await rawBatches(p1);
  const rb2 = p2 === p1 ? rb1 : await rawBatches(p2);
  const r1 = rb1[0];
  const r2 = rb2.find((b) => b.id !== r1?.id) || rb2[0];
  if (!r1 || !r2) throw new Error(`need 2 raw batches: p1=${rb1.length} p2=${rb2.length}`);

  // ---- create plan + seed 5-row mixed-SKU chain via API ----
  const plan = await api('POST', `/${FACTORY_ID}/production-plans`, {
    productTypeId: p1, plannedQuantity: 1.2, plannedDate: today(0), expectedCompletionDate: today(2),
    customerOrderNumber: AUDIT_PREFIX, priority: 5, sourceType: 'MANUAL',
    notes: `${AUDIT_PREFIX} UI-layer deep audit`, processName: chain.map((p) => p.name).join('→'),
    skipProcessReporting: false, isMixedBatch: true,
  });
  const planId = String(plan.id || plan.planId);
  const planNumber = String(plan.planNumber || planId);
  console.log(`plan ${planNumber} (${planId})`);
  await api('POST', `/${FACTORY_ID}/production-plans/${encodeURIComponent(planId)}/start`).catch(() => null);

  async function saveRow(label, body) {
    const r = await api('POST', `/${FACTORY_ID}/production-plans/${encodeURIComponent(planId)}/process-sheet/row`, {
      unit: 'kg', seasoningStep: body.processCode === 'shuzhi', finished: body.processCode === 'qidiao',
      idempotencyKey: `${AUDIT_PREFIX}-${body.clientRowId}`, ...body,
    });
    return r;
  }
  const first = chain[0], mid = chain[1];
  const cook = chain.find((p) => p.code === 'shuzhi') || chain[2];
  if (cook.order <= mid.order) cook.order = mid.order + 1;

  const a = await saveRow('A', { clientRowId: 'first-a', processCode: first.code, processOrder: first.order, processName: first.name, processDate: today(0), productTypeId: p1, inputQuantity: 0.32, outputQuantity: 0.30, rawMaterialInputs: [{ materialBatchId: r1.id, quantity: 0.32 }] });
  const b = await saveRow('B', { clientRowId: 'first-b', processCode: first.code, processOrder: first.order, processName: first.name, processDate: today(0), productTypeId: p2, inputQuantity: 0.24, outputQuantity: 0.22, rawMaterialInputs: [{ materialBatchId: r2.id, quantity: 0.24 }] });
  const c = await saveRow('C', { clientRowId: 'mid-c', processCode: mid.code, processOrder: mid.order, processName: mid.name, processDate: today(1), productTypeId: p1, inputQuantity: 0.18, outputQuantity: 0.16, upstreamSources: [{ sourceBatchNumber: a.batchNumber, feedQuantityKg: 0.18 }] });
  const d = await saveRow('D', { clientRowId: 'mid-d', processCode: mid.code, processOrder: mid.order, processName: mid.name, processDate: today(1), productTypeId: p2, inputQuantity: 0.12, outputQuantity: 0.11, upstreamSources: [{ sourceBatchNumber: b.batchNumber, feedQuantityKg: 0.12 }] });
  const e = await saveRow('E', { clientRowId: 'cook-e', processCode: cook.code, processOrder: cook.order, processName: cook.name, processDate: today(2), productTypeId: p1, inputQuantity: 0.14, outputQuantity: 0.13, upstreamSources: [{ sourceBatchNumber: c.batchNumber, feedQuantityKg: 0.08 }, { sourceBatchNumber: d.batchNumber, feedQuantityKg: 0.06 }], potCount: 2, potRawKgs: [0.08, 0.06] });

  // ---- INDEPENDENT ORACLE (first-principles, from documented algorithm) ----
  const aRawPrice = num(a.rowTotalCost) / 0.32; // raw cost / input
  const bRawPrice = num(b.rowTotalCost) / 0.24;
  const oracle = {};
  oracle.A = { rawEq: 0.32, step: 0.30 / 0.32 * 100, cum: 0.30 / 0.32 * 100, cost: num(a.rowTotalCost) };
  oracle.B = { rawEq: 0.24, step: 0.22 / 0.24 * 100, cum: 0.22 / 0.24 * 100, cost: num(b.rowTotalCost) };
  const cRawEq = 0.18 / 0.30 * 0.32;
  const dRawEq = 0.12 / 0.22 * 0.24;
  oracle.C = { rawEq: cRawEq, step: 0.16 / 0.18 * 100, cum: 0.16 / cRawEq * 100 };
  oracle.D = { rawEq: dRawEq, step: 0.11 / 0.12 * 100, cum: 0.11 / dRawEq * 100 };
  const eRawEq = 0.08 / 0.16 * cRawEq + 0.06 / 0.11 * dRawEq;
  oracle.E = { rawEq: eRawEq, step: 0.13 / 0.14 * 100, cum: 0.13 / eRawEq * 100 };
  console.log('oracle', JSON.stringify(oracle, (k, v) => typeof v === 'number' ? Number(v.toFixed(4)) : v));

  // ---- API yield-card (backend truth) ----
  const yc = arr(await api('GET', `/${FACTORY_ID}/production-plans/${encodeURIComponent(planId)}/process-sheet/inventory/yield-card`));
  const byBatch = Object.fromEntries(yc.map((x) => [x.batchNumber, x]));
  const cards = { A: byBatch[a.batchNumber], B: byBatch[b.batchNumber], C: byBatch[c.batchNumber], D: byBatch[d.batchNumber], E: byBatch[e.batchNumber] };

  // 1) ORACLE vs API (backend calc accuracy)
  for (const k of ['A', 'B', 'C', 'D', 'E']) {
    const o = oracle[k], cd = cards[k];
    assert(Boolean(cd), `yield-card 含批次 ${k}`, { batch: { A: a, B: b, C: c, D: d, E: e }[k].batchNumber });
    if (!cd) continue;
    approx(num(cd.stepYieldRate), o.step, 0.06, `[API==oracle] ${k} 对上工序出成率`);
    approx(num(cd.cumulativeYieldRate), o.cum, 0.12, `[API==oracle] ${k} 对原料累计出成率`);
    approx(num(cd.inheritedRawEquivalentQuantity), o.rawEq, 0.01, `[API==oracle] ${k} 继承原料折算`);
  }
  assert(Array.isArray(cards.E?.sourceBreakdowns) && cards.E.sourceBreakdowns.length === 2, 'E 混来源两个来源拆分', { n: cards.E?.sourceBreakdowns?.length });

  // 1b) COST CLEANLINESS (verifies the scale-2 inheritedCost fix is LIVE):
  //   addedCost 不出现负数/亚分级噪音, 继承成本 <= 行总成本, 成本均为 scale-2.
  function scale2Clean(v) { return v == null || Math.abs(Number(v) - Math.round(Number(v) * 100) / 100) < 1e-9; }
  for (const row of yc) {
    const added = num(row.addedCost), inh = num(row.inheritedCost), rtc = num(row.rowTotalCost);
    if (added != null) {
      assert(added >= -0.0001, `成本修复: ${row.batchNumber} addedCost 非负(无负舍入噪音)`, { addedCost: added });
      assert(scale2Clean(added), `成本修复: ${row.batchNumber} addedCost 为 scale-2(无亚分级噪音)`, { addedCost: added });
    }
    if (inh != null) assert(scale2Clean(inh), `成本修复: ${row.batchNumber} inheritedCost 为 scale-2`, { inheritedCost: inh });
    if (inh != null && rtc != null) assert(inh <= rtc + 0.0001, `成本修复: ${row.batchNumber} 继承成本<=行总成本`, { inheritedCost: inh, rowTotalCost: rtc });
  }

  // ---- HEADED UI readback: open 逐道录入 drawer, read rendered yield card ----
  await page.goto(`${APP_URL}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  const searchBox = page.locator('input[placeholder*="搜索计划"], input[placeholder*="计划编号"], input[placeholder*="搜索"]').first();
  if (await searchBox.isVisible().catch(() => false)) {
    await searchBox.fill(planNumber);
    await page.locator('button').filter({ hasText: /搜索/ }).first().click().catch(() => null);
    await page.waitForTimeout(2000);
  }
  const planRow = page.locator('.el-table__row').filter({ hasText: planNumber }).first();
  assert(await planRow.isVisible().catch(() => false), '计划列表可见新建计划行', { planNumber });
  const entryBtn = planRow.locator('button, .el-button').filter({ hasText: '逐道录入' }).first();
  assert(await entryBtn.isVisible().catch(() => false), '未完成计划行显示「逐道录入」按钮');
  await entryBtn.click();
  await page.waitForSelector('.el-drawer__body', { timeout: 15000 });
  await page.waitForTimeout(3000); // let yield card load
  await shot('process-entry-drawer');

  // Read the YieldCardTable by locating the el-table whose header has BOTH 对上工序出成 + 对原料累计
  const uiCards = await page.evaluate(() => {
    const tables = Array.from(document.querySelectorAll('.el-drawer__body .el-table'));
    const target = tables.find((t) => {
      const heads = Array.from(t.querySelectorAll('thead th')).map((h) => (h.innerText || '').trim());
      return heads.some((x) => x.includes('对上工序出成')) && heads.some((x) => x.includes('对原料累计'));
    });
    if (!target) return { error: 'yield card table not found in drawer' };
    const heads = Array.from(target.querySelectorAll('thead th')).map((h) => (h.innerText || '').trim());
    const idx = (label) => heads.findIndex((x) => x.includes(label));
    const cBatch = idx('批次号'), cRaw = idx('继承原料'), cCost = idx('分摊成本'), cStep = idx('对上工序出成'), cCum = idx('对原料累计');
    const out = [];
    for (const tr of Array.from(target.querySelectorAll('tbody tr'))) {
      const tds = Array.from(tr.querySelectorAll('td')).map((td) => (td.innerText || '').trim());
      if (!tds.length) continue;
      out.push({ batch: tds[cBatch], inheritedRaw: tds[cRaw], cost: tds[cCost], step: tds[cStep], cum: tds[cCum] });
    }
    return { headers: heads, rows: out };
  });
  assert(!uiCards.error, '抽屉内找到双出成率总览表', { error: uiCards.error });
  const uiByBatch = Object.fromEntries((uiCards.rows || []).map((r) => [r.batch, r]));

  // 2) UI vs API (frontend display accuracy) — DOM text must equal API value formatted like the component
  function pctStr(v) { return v == null ? '—' : Number(v).toFixed(2) + '%'; }
  function moneyStr(v) { return v == null ? '—' : '¥' + Number(v).toFixed(2); }
  function qty2Str(v) { return v == null ? '—' : Number(v).toFixed(2); }
  for (const k of ['A', 'B', 'C', 'D', 'E']) {
    const batch = { A: a, B: b, C: c, D: d, E: e }[k].batchNumber;
    const ui = uiByBatch[batch];
    const cd = cards[k];
    assert(Boolean(ui), `[UI] 渲染显示批次 ${k} 行`, { batch });
    if (!ui || !cd) continue;
    assert(ui.step === pctStr(num(cd.stepYieldRate)), `[UI==API] ${k} 对上工序出成率渲染`, { ui: ui.step, api: pctStr(num(cd.stepYieldRate)) });
    assert(ui.cum === pctStr(num(cd.cumulativeYieldRate)), `[UI==API] ${k} 对原料累计出成率渲染`, { ui: ui.cum, api: pctStr(num(cd.cumulativeYieldRate)) });
    assert(ui.inheritedRaw === qty2Str(num(cd.inheritedRawEquivalentQuantity)), `[UI==API] ${k} 继承原料折算渲染`, { ui: ui.inheritedRaw, api: qty2Str(num(cd.inheritedRawEquivalentQuantity)) });
    assert(ui.cost === moneyStr(num(cd.rowTotalCost)), `[UI==API] ${k} 分摊成本渲染`, { ui: ui.cost, api: moneyStr(num(cd.rowTotalCost)) });
    // honesty rule: cost/yield must NOT be silently 0 — either a real value or '—'
    assert(ui.cost !== '¥0.00' || num(cd.rowTotalCost) === 0, `[防呆] ${k} 成本非伪0`, { ui: ui.cost });
  }

  // close drawer
  await page.keyboard.press('Escape').catch(() => null);
  await page.locator('.el-drawer__close-btn, .el-drawer__header button').first().click().catch(() => null);
  await page.waitForTimeout(1500);

  // ---- HEADED UI: open 核对结单 settlement dialog, validate mixed-SKU rows (#7), submit via UI ----
  const settleBtn = planRow.locator('button, .el-button').filter({ hasText: /核对结单|生产结单|结单/ }).first();
  assert(await settleBtn.isVisible().catch(() => false), '未完成计划行显示「核对结单」按钮');
  await settleBtn.click();
  await page.waitForSelector('.settlement-dialog, .el-dialog', { timeout: 15000 });
  await page.waitForTimeout(2500);
  await shot('settlement-dialog');

  const dialog = page.locator('.settlement-dialog').first();
  const dialogEl = (await dialog.count()) ? dialog : page.locator('.el-dialog').filter({ hasText: /核对结单|结单/ }).first();
  // settlement context prefill
  const ctxValues = await dialogEl.locator('.settlement-context-value').allInnerTexts().catch(() => []);
  assert(ctxValues.some((t) => t.includes(planNumber)), '结单弹窗上下文显示计划单号(防呆 Rule2)', { ctxValues });
  // mixed-SKU raw rows preserved (#7): expect >= 2 raw consumption rows
  const rawRowCount = await dialogEl.locator('.settlement-consumption-row').count().catch(() => 0);
  assert(rawRowCount >= 2, '结单弹窗保留混SKU原料行(#7) >=2行', { rawRowCount });
  // read the prefilled actual finished quantity input value
  const actualInputVal = await dialogEl.locator('.el-input-number input').first().inputValue().catch(() => '');
  approx(num(actualInputVal), 0.13, 0.005, '结单弹窗实际产量预填=0.13', { actualInputVal });

  // The UI settlement dialog deliberately REQUIRES labor (人数+工时分钟) — no laborDeferredReason
  // escape hatch in UI (by-design 防呆). Fill them before submit.
  async function fillFormItemNumber(labelText, value) {
    const item = dialogEl.locator('.el-form-item').filter({ has: page.locator(`.el-form-item__label:text-is("${labelText}")`) }).first();
    let input = item.locator('input').first();
    if (!(await input.count())) input = dialogEl.locator(`.el-form-item:has(.el-form-item__label:has-text("${labelText}")) input`).first();
    await input.click();
    await input.fill(String(value));
    await input.press('Enter');
    await page.waitForTimeout(300);
  }
  await fillFormItemNumber('人数', 2);
  await fillFormItemNumber('工时分钟', 30);
  await page.waitForTimeout(500);
  // capture any remaining disabled reason for diagnostics
  const disabledReason = await dialogEl.locator('.el-alert--warning .el-alert__title').last().innerText().catch(() => '');
  if (disabledReason) console.log('  settlement disabled reason:', disabledReason);

  // submit settlement THROUGH UI — prefer dialog footer primary button, fallback to text
  let confirmBtn = dialogEl.locator('.el-dialog__footer button.el-button--primary, .el-drawer__footer button.el-button--primary').last();
  if (!(await confirmBtn.isVisible().catch(() => false))) {
    confirmBtn = dialogEl.locator('button').filter({ hasText: /确定|提交|保存|确认结单|结单/ }).last();
  }
  let uiSubmitOk = false, uiToast = '';
  if (await confirmBtn.isVisible().catch(() => false)) {
    await confirmBtn.click();
    try {
      const t = await page.waitForSelector('.el-message--success', { timeout: 8000 });
      uiToast = await t.innerText();
      uiSubmitOk = true;
    } catch {
      const err = await page.$('.el-message--error');
      uiToast = err ? await err.innerText() : '(no toast)';
    }
    await page.waitForTimeout(1500);
  }
  await shot('after-settlement-submit');
  assert(uiSubmitOk, '通过UI提交结单成功(验证#7端到端)', { uiToast });

  // ---- verify settlement persisted correctly via API (calc accuracy of settlement) ----
  await page.waitForTimeout(1500);
  const after = await api('GET', `/${FACTORY_ID}/production-plans/${encodeURIComponent(planId)}/settlement`).catch((err) => ({ error: err.message }));
  approx(num(after?.actualFinishedQuantity), 0.13, 0.005, '结单回读实际成品数量=0.13', { after: after?.actualFinishedQuantity });
  assert(after?.status === 'COMPLETED', '结单后状态 COMPLETED', { status: after?.status });
  assert(after?.postingStatus === 'PENDING_WAREHOUSE_RECEIPT', '结单后待仓库确认入库', { postingStatus: after?.postingStatus });
  // raw consumption total preserved through UI submit (mixed SKU both SKUs counted)
  const rawTotal = (after?.rawMaterialConsumptions || []).reduce((s, l) => s + Number(l.quantity || 0), 0);
  if (after?.rawMaterialConsumptions) approx(rawTotal, 0.56, 0.01, '结单原料领用合计=0.56(混SKU双原料都计入)', { rawTotal });

  // ---- summary ----
  const failures = assertions.filter((x) => !x.pass);
  const result = {
    status: failures.length === 0 && consoleErrors.length === 0 ? 'PASS' : 'FAIL',
    schema_v3: { depth: 'deep', layer: 'UI+API+oracle three-way' },
    appUrl: APP_URL, factoryId: FACTORY_ID, auditPrefix: AUDIT_PREFIX,
    planNumber, planId,
    product: { p1, p2 }, chain,
    oracle, apiYieldCard: yc, uiCards, uiToast,
    settlementAfter: after,
    assertions, failures, consoleErrors, apiCalls, screenshots,
    playwright: { headed: true, viewport: '1920x1080', port: process.env.PLAYWRIGHT_PORT || null },
  };
  await writeFile(path.join(OUT_DIR, 'ui-render-deep-result.json'), JSON.stringify(result, null, 2), 'utf8');
  console.log(JSON.stringify({ status: result.status, planNumber, totalAssertions: assertions.length, failures: failures.map((f) => f.label), consoleErrors }, null, 2));
  await browser.close();
  browserContext = null;
  if (result.status !== 'PASS') process.exitCode = 1;
}

main().catch(async (err) => {
  if (browserContext) await browserContext.close().catch(() => null);
  await mkdir(OUT_DIR, { recursive: true }).catch(() => null);
  await writeFile(path.join(OUT_DIR, 'ui-render-deep-error.txt'), err.stack || String(err), 'utf8').catch(() => null);
  console.error(err);
  process.exitCode = 1;
});
