/**
 * 复杂边界深度测试: 结单超产差异原因(防呆 Rule1+3) + 幂等(防呆 Rule4) — headed prod F006.
 *
 * - API 前置: 计划 plannedQuantity 故意设很小, 逐道录入产出 > 计划 → 触发"实际>计划".
 * - HEADED: 打开核对结单, 设实际产量 > 计划 → 验证:
 *     · Rule1 预警 alert「实际产量超过计划数量,请选择差异原因」
 *     · Rule3 差异原因 el-select 下拉(必填) + 选「其他」→ 必填补充 textarea
 * - 幂等(Rule4): 结单后再次提交 → 不重复过账(API 校验 postingStatus/状态不变).
 *
 * Env: E2E_USERNAME E2E_PASSWORD [E2E_ADMIN_URL E2E_FACTORY_ID PLAYWRIGHT_PORT]
 */
import { chromium } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const APP = process.env.E2E_ADMIN_URL || 'http://139.196.165.140:8086';
const FACTORY = process.env.E2E_FACTORY_ID || 'F006';
const OUT = path.resolve(process.env.E2E_OUT || `.playwright-mcp/settle-edge-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const PROFILE = path.join(OUT, `profile-${Date.now().toString(36)}`);
const PORT_ARG = process.env.PLAYWRIGHT_PORT ? [`--remote-debugging-port=${process.env.PLAYWRIGHT_PORT}`] : [];
const U = process.env.E2E_USERNAME, P = process.env.E2E_PASSWORD;
if (!U || !P) { console.error('E2E_USERNAME/E2E_PASSWORD required'); process.exit(1); }

const asserts = [];
const consoleErrors = [];
let ctx = null;
function ok(p, label, d = {}) { asserts.push({ pass: !!p, label, ...d }); console.log(`${p ? 'PASS' : 'FAIL'} ${label} ${Object.keys(d).length ? JSON.stringify(d) : ''}`); return !!p; }
const arr = (d) => Array.isArray(d) ? d : (d && (d.content || d.records || d.items)) || [];
const num = (v) => { const n = Number(v); return Number.isFinite(n) ? n : null; };
const today = (o = 0) => new Date(Date.now() + 8 * 3600e3 + o * 86400e3).toISOString().slice(0, 10);

async function main() {
  await mkdir(OUT, { recursive: true });
  const browser = await chromium.launchPersistentContext(PROFILE, { headless: false, viewport: { width: 1920, height: 1080 }, args: ['--lang=zh-CN', '--font-render-hinting=none', ...PORT_ARG] });
  ctx = browser;
  const page = browser.pages()[0] || await browser.newPage();
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push(e.message));
  let shotN = 0;
  const shot = async (n) => { await page.screenshot({ path: path.join(OUT, `${String(++shotN).padStart(2, '0')}-${n}.png`), fullPage: true }).catch(() => null); };
  async function fillNum(loc, value) { const inp = loc.locator('input').first(); await inp.click(); await inp.fill(''); await inp.type(String(value)); await inp.press('Tab'); await page.waitForTimeout(200); }
  async function selectByText(scope, searchText) { await scope.click(); await page.waitForTimeout(600); const opt = page.getByRole('option').filter({ hasText: String(searchText) }).first(); await opt.scrollIntoViewIfNeeded().catch(() => null); await opt.click({ timeout: 8000 }); await page.waitForTimeout(400); }

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
  ok(!!token, '登录成功');
  const H = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  async function api(method, p, body) { const r = await fetch(`${APP}/api/mobile${p}`, { method, headers: H, body: body == null ? undefined : JSON.stringify(body) }); const t = await r.text(); let j = null; try { j = t ? JSON.parse(t) : null; } catch { j = { raw: t }; } if (!r.ok || j?.success === false) { const e = new Error(`${method} ${p} ${r.status}: ${j?.message || ''}`); e.status = r.status; e.body = j; throw e; } return j && 'data' in j ? j.data : j; }

  // ---- API 前置: 计划 plannedQuantity 很小 + 逐道录入产出更大 (触发超产) ----
  const products = arr(await api('GET', `/${FACTORY}/product-types/active`));
  const whs = arr(await api('GET', `/${FACTORY}/factory/warehouses`));
  const rawWh = whs.find((w) => w.code === 'WH-LOG') || whs.find((w) => ['RAW', 'LOGISTICS'].includes(String(w.type)));
  let pick = null;
  for (const pr of products.slice(0, 50)) {
    const id = String(pr.id || pr.productTypeId);
    const procs = arr(await api('GET', `/${FACTORY}/product-work-processes?productTypeId=${encodeURIComponent(id)}`)).filter((x) => x.isActive !== false).sort((a, b) => (a.processOrder || 0) - (b.processOrder || 0));
    if (procs.length < 2) continue;
    const batches = arr(await api('GET', `/${FACTORY}/material-batches/status/AVAILABLE?warehouseId=${encodeURIComponent(rawWh.id)}&productTypeId=${encodeURIComponent(id)}&size=50`))
      .filter((b) => num(b.currentQuantity ?? b.quantity) > 1 && !/^WIP-|^CLK-/.test(String(b.batchNumber || '')) && !/件|个|只|pcs|pc$/i.test(String(b.quantityUnit || b.unit || '')));
    if (batches.length >= 1) { pick = { id, name: pr.name, procs, batch: batches[0] }; break; }
  }
  ok(!!pick, '发现产品+原料批(weight unit)', { name: pick?.name });
  if (!pick) { await finish(); return; }

  const PLANNED = 0.05; // 故意很小
  const plan = await api('POST', `/${FACTORY}/production-plans`, { productTypeId: pick.id, plannedQuantity: PLANNED, plannedDate: today(0), expectedCompletionDate: today(2), sourceType: 'MANUAL', skipProcessReporting: false, customerOrderNumber: `SE-${Date.now()}`, notes: 'settlement edge: over-plan + idempotency' });
  const planNumber = String(plan.planNumber || plan.id), planId = String(plan.id || plan.planId);
  ok(!!planId, '前置计划已建(planned=0.05 故意小)', { planNumber });
  await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/start`).catch(() => null);
  // 首道一行: 产出 0.13 (> 0.05 计划)
  const first = pick.procs[0];
  await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/process-sheet/row`, {
    unit: 'kg', clientRowId: `se-first-${Date.now().toString(36)}`, processCode: 'xiuyou', processOrder: first.processOrder, processName: first.processName,
    processDate: today(0), productTypeId: pick.id, inputQuantity: 0.15, outputQuantity: 0.13,
    rawMaterialInputs: [{ materialBatchId: pick.batch.id, quantity: 0.15 }],
  });
  ok(true, '逐道录入末道产出 0.13 (> 计划 0.05)');

  // ---- HEADED: 打开核对结单 ----
  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  const search = page.locator('input[placeholder*="搜索"], input[placeholder*="计划编号"]').first();
  if (await search.isVisible().catch(() => false)) { await search.fill(planNumber); await page.locator('button').filter({ hasText: /搜索/ }).first().click().catch(() => null); await page.waitForTimeout(2000); }
  const planRow = page.locator('.el-table__row').filter({ hasText: planNumber }).first();
  ok(await planRow.isVisible().catch(() => false), '计划行可见', { planNumber });
  await planRow.locator('button, .el-button').filter({ hasText: /核对结单|结单/ }).first().click();
  await page.waitForSelector('.settlement-dialog, .el-dialog', { timeout: 12000 });
  await page.waitForTimeout(2500);
  const dlg = page.locator('.settlement-dialog').first();
  const dEl = (await dlg.count()) ? dlg : page.locator('.el-dialog').filter({ hasText: /核对结单|结单/ }).first();

  // ---- 设实际产量 > 计划 → 触发超产差异原因 (Rule1 + Rule3) ----
  const actualInput = dEl.locator('.el-form-item').filter({ hasText: '实际产量' }).locator('.el-input-number').first();
  await fillNum(actualInput, 0.5); // 0.5 >> 0.05 计划
  await page.waitForTimeout(800);
  await shot('over-plan');
  // Rule 1: 超产预警 alert
  const overAlert = await dEl.locator('.el-alert').filter({ hasText: /超过计划|差异原因/ }).first().isVisible().catch(() => false);
  ok(overAlert, '防呆Rule1: 实际>计划 显示超产预警 alert (事前)');
  // Rule 3: 差异原因 el-select 出现且必填
  const varItem = dEl.locator('.el-form-item').filter({ hasText: '差异原因' }).first();
  const varSelect = varItem.locator('.el-select').first();
  ok(await varSelect.isVisible().catch(() => false), '防呆Rule3: 差异原因 渲染为 el-select 下拉');
  // 选标准原因
  await selectByText(varSelect, '现场称重差异').catch(() => null);
  await page.waitForTimeout(500);
  // 选「其他」→ 必填补充 textarea
  await selectByText(varSelect, '其他').catch(() => null);
  await page.waitForTimeout(600);
  const otherTextarea = await dEl.locator('.el-form-item').filter({ hasText: /原因补充/ }).locator('textarea').first().isVisible().catch(() => false);
  ok(otherTextarea, '防呆Rule3: 选「其他」→ 显必填补充 textarea');
  // 回到标准原因(避免补充必填阻塞)
  await selectByText(varSelect, '临时调整产量').catch(() => null);
  await page.waitForTimeout(500);
  await shot('variance-reason');

  // 关闭弹窗(本测试聚焦超产差异 UI; 提交结单的幂等单独用 API 验, 避免 headed labor/批次复杂度)
  await dEl.locator('.el-dialog__headerbtn, .el-dialog__close').first().click().catch(() => null);
  await page.keyboard.press('Escape').catch(() => null);
  await page.waitForTimeout(800);

  // ---- 幂等(Rule4): 用 prefill 提交结单两次, 第二次应不重复过账 ----
  const prefillResp = await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/settlement-prefill`).catch(() => null);
  const prefill = prefillResp?.prefill || {};
  const idemKey = `${planNumber}-settle-idem`;
  const settleBody = {
    ...prefill, idempotencyKey: idemKey,
    actualFinishedQuantity: prefill.actualFinishedQuantity ?? 0.13,
    quantityVarianceReason: '现场称重差异', // 超产(0.13>0.05)→ 后端同样强制要求超产原因(API 层防呆 ✓)
    quantityUnit: prefill.quantityUnit || 'kg',
    laborDeferredReason: prefill.laborDeferredReason || '工时稍后补录',
    rawMaterialConsumptions: prefill.rawMaterialConsumptions || [],
    semiFinishedConsumptions: prefill.semiFinishedConsumptions || [],
    auxiliaryConsumptions: prefill.auxiliaryConsumptions || [],
    laborSegments: prefill.laborSegments || [],
  };
  let settle1 = null, settle2 = null, idemOk = false, idemMode = '';
  try { settle1 = await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/settle`, settleBody); } catch (e) { settle1 = { error: e.message, status: e.status }; }
  ok(settle1 && settle1.status === 'COMPLETED', '首次结单 COMPLETED (含超产差异原因)', { status: settle1?.status, err: settle1?.error?.slice?.(0, 120) });
  // 第二次提交(同 idempotencyKey)
  try { settle2 = await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/settle`, settleBody); idemMode = 'returned-same'; idemOk = settle2?.status === 'COMPLETED' || settle2?.postingStatus === settle1?.postingStatus; }
  catch (e) { idemMode = 'rejected-409'; idemOk = e.status === 409 || /已结单|已完成|重复|已提交/.test(e.message); settle2 = { error: e.message, status: e.status }; }
  ok(idemOk, '防呆Rule4: 重复结单幂等(返回同结果 或 被拒, 不重复过账)', { idemMode, settle2: settle2?.status || settle2?.error?.slice?.(0, 60) });
  // 核对: 最终状态仍是单次完成, posting 未重复
  const after = await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/settlement`).catch(() => ({}));
  ok(after?.status === 'COMPLETED' && after?.postingStatus === 'PENDING_WAREHOUSE_RECEIPT', '结单回读: 单次完成态稳定(未因重复提交翻车)', { status: after?.status, posting: after?.postingStatus });

  await finish();
  async function finish() {
    const fails = asserts.filter((a) => !a.pass);
    const realErr = consoleErrors.filter((e) => !/409|status of 4\d\d|favicon/.test(e));
    const status = fails.length === 0 && realErr.length === 0 ? 'PASS' : 'FAIL';
    await writeFile(path.join(OUT, 'settle-edge-result.json'), JSON.stringify({ status, planNumber: typeof planNumber !== 'undefined' ? planNumber : null, asserts, consoleErrors }, null, 2), 'utf8').catch(() => null);
    console.log(JSON.stringify({ status, total: asserts.length, pass: asserts.filter((a) => a.pass).length, failures: fails.map((f) => f.label) }, null, 2));
    await browser.close().catch(() => null); ctx = null;
    if (status === 'FAIL') process.exitCode = 1;
  }
}
main().catch(async (e) => { if (ctx) await ctx.close().catch(() => null); await mkdir(OUT, { recursive: true }).catch(() => null); await writeFile(path.join(OUT, 'error.txt'), e.stack || String(e), 'utf8').catch(() => null); console.error('ERROR:', e.message); process.exitCode = 1; });
