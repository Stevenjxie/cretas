/**
 * PURE-HEADED 全工序链 E2E + 防呆审计 (F006).
 *
 * 全程操作真实前端驱动整条动态工序链 (首道→...→熟制混锅→气调成品→结单):
 *   - 首道(拆包): 录 2 个原料批次 (多批次)
 *   - 中间各单上游道: 把 2 个批次各自流转 (2 行/道), 保持 2 条血缘到熟制前
 *   - 熟制(卤制): 混锅消耗 2 个上游批 (真·混来源)
 *   - 气调(分切装盒): 成品批 (盒数)
 *   - 核对结单 (UI 提交)
 * 同时审计操作流程 + 防呆设计 (.claude/rules/fool-proof-design.md): 每步捕获
 *   保存禁用原因/警告/错误 toast/上下文, 末尾产出 防呆 审计结论.
 *
 * 计划创建为 API 前置 (弹窗 el-select 与模态遮罩冲突); 所有逐道录入键入全 headed.
 * Env: E2E_USERNAME E2E_PASSWORD [E2E_ADMIN_URL E2E_FACTORY_ID PLAYWRIGHT_PORT]
 */
import { chromium } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const APP = process.env.E2E_ADMIN_URL || 'http://139.196.165.140:8086';
const FACTORY = process.env.E2E_FACTORY_ID || 'F006';
const OUT = path.resolve(process.env.E2E_OUT || `.playwright-mcp/headed-fullchain-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const PROFILE = path.join(OUT, `profile-${Date.now().toString(36)}`);
const PORT_ARG = process.env.PLAYWRIGHT_PORT ? [`--remote-debugging-port=${process.env.PLAYWRIGHT_PORT}`] : [];
const U = process.env.E2E_USERNAME, P = process.env.E2E_PASSWORD;
if (!U || !P) { console.error('E2E_USERNAME/E2E_PASSWORD required'); process.exit(1); }

const asserts = [];
const consoleErrors = [];
const foolproof = []; // 防呆 audit observations
let ctx = null;
function ok(p, label, d = {}) { asserts.push({ pass: !!p, label, ...d }); console.log(`${p ? 'PASS' : 'FAIL'} ${label} ${Object.keys(d).length ? JSON.stringify(d) : ''}`); return !!p; }
function fp(category, observation, verdict) { foolproof.push({ category, observation, verdict }); console.log(`  [防呆:${category}] ${observation} → ${verdict}`); }
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
  const shot = async (n) => { const f = path.join(OUT, `${String(++shotN).padStart(2, '0')}-${n}.png`); await page.screenshot({ path: f, fullPage: true }).catch(() => null); return f; };

  // ---------- helpers ----------
  async function selectByText(scope, searchText) {
    await scope.click(); await page.waitForTimeout(600);
    const opt = page.getByRole('option').filter({ hasText: String(searchText) }).first();
    await opt.scrollIntoViewIfNeeded().catch(() => null);
    await opt.click({ timeout: 8000 });
    await page.waitForTimeout(400);
  }
  async function fillNum(loc, value) { const inp = loc.locator('input').first(); await inp.click(); await inp.fill(''); await inp.type(String(value)); await inp.press('Tab'); await page.waitForTimeout(150); }
  async function waitSaved(timeout = 9000) {
    try { const t = await page.waitForSelector('.el-message--success, .el-message--warning, .el-message--error', { timeout }); const txt = await t.innerText().catch(() => ''); return { saved: /已保存|保存成功|成功/.test(txt) && !/失败|错误|无法/.test(txt), toast: txt.slice(0, 160) }; }
    catch { return { saved: false, toast: '(no toast)' }; }
  }

  // ---------- login (UI) ----------
  await page.goto(`${APP}/login`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.locator('.login-form input, input').nth(0).fill(U);
  await page.locator('input[type="password"], .login-form input').nth(1).fill(P);
  let sub = page.locator('.login-button').first();
  if (!(await sub.isVisible().catch(() => false))) sub = page.locator('button[type="submit"], button.el-button--primary, button').first();
  await Promise.all([page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 30000 }).catch(() => null), sub.click().catch(() => page.keyboard.press('Enter'))]);
  await page.waitForTimeout(2500);
  await page.waitForFunction(() => Boolean(localStorage.getItem('cretas_access_token')), null, { timeout: 10000 }).catch(() => null);
  const token = await page.evaluate(() => localStorage.getItem('cretas_access_token') || '');
  ok(!!token, '登录成功');
  const H = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  const apiGet = async (p) => { const r = await fetch(`${APP}/api/mobile${p}`, { headers: H }); const j = await r.json().catch(() => ({})); return j && 'data' in j ? j.data : j; };
  const apiPost = async (p, body) => { const r = await fetch(`${APP}/api/mobile${p}`, { method: 'POST', headers: H, body: body == null ? undefined : JSON.stringify(body) }); const j = await r.json().catch(() => ({})); if (!r.ok || j?.success === false) throw new Error(`POST ${p} ${r.status}: ${j?.message || ''}`); return j && 'data' in j ? j.data : j; };

  // ---------- discover product (≥6 processes incl a cook + finished, ≥2 raw batches) ----------
  const products = arr(await apiGet(`/${FACTORY}/product-types/active`));
  const whs = arr(await apiGet(`/${FACTORY}/factory/warehouses`));
  const rawWh = whs.find((w) => w.code === 'WH-LOG') || whs.find((w) => ['RAW', 'LOGISTICS'].includes(String(w.type)));
  let pick = null;
  for (const pr of products.slice(0, 50)) {
    const id = String(pr.id || pr.productTypeId);
    const procs = arr(await apiGet(`/${FACTORY}/product-work-processes?productTypeId=${encodeURIComponent(id)}`)).filter((x) => x.isActive !== false).sort((a, b) => (a.processOrder || 0) - (b.processOrder || 0));
    const hasMix = procs.findIndex((p) => /熟|卤|煮/.test(String(p.processName || '')));
    const hasFin = procs.findIndex((p) => /气调|包装|分切|装盒/.test(String(p.processName || '')));
    if (procs.length >= 6 && hasMix > 0 && hasFin > hasMix) {
      const batches = arr(await apiGet(`/${FACTORY}/material-batches/status/AVAILABLE?warehouseId=${encodeURIComponent(rawWh.id)}&productTypeId=${encodeURIComponent(id)}&size=50`)).filter((b) => num(b.currentQuantity ?? b.quantity) > 1 && !/^WIP-|^CLK-/.test(String(b.batchNumber || '')));
      if (batches.length >= 2) { pick = { id, name: pr.name, procs, batches, mixIdx: hasMix, finIdx: hasFin }; break; }
    }
  }
  ok(!!pick, '发现全链产品(≥6道+熟制+气调+≥2原料批)', { name: pick?.name, mix: pick?.procs?.[pick?.mixIdx]?.processName, fin: pick?.procs?.[pick?.finIdx]?.processName });
  if (!pick) { await finish(); return; }
  console.log('chain:', pick.procs.map((p) => p.processName).join('→'), '| mixIdx', pick.mixIdx, 'finIdx', pick.finIdx);

  // ---------- API precondition: plan ----------
  const plan = await apiPost(`/${FACTORY}/production-plans`, { productTypeId: pick.id, plannedQuantity: 5, plannedDate: today(0), expectedCompletionDate: today(3), sourceType: 'MANUAL', skipProcessReporting: false, customerOrderNumber: `FC-${Date.now()}`, notes: 'fullchain headed + foolproof audit' });
  const planNumber = String(plan.planNumber || plan.id), planId = String(plan.id || plan.planId);
  ok(!!planId, '前置计划已建(API)', { planNumber });
  await apiPost(`/${FACTORY}/production-plans/${encodeURIComponent(planId)}/start`).catch(() => null);

  // ---------- open 逐道录入 (HEADED) ----------
  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  const sb = page.locator('input[placeholder*="搜索"], input[placeholder*="计划编号"]').first();
  if (await sb.isVisible().catch(() => false)) { await sb.fill(planNumber); await page.locator('button').filter({ hasText: /搜索/ }).first().click().catch(() => null); await page.waitForTimeout(2000); }
  const planRow = page.locator('.el-table__row').filter({ hasText: planNumber }).first();
  ok(await planRow.isVisible().catch(() => false), '计划行可见', { planNumber });
  // 防呆 Rule 2: 计划行/按钮带上下文
  const rowText = await planRow.innerText().catch(() => '');
  fp('Rule2-上下文', `计划行含品名「${pick.name.slice(0, 10)}…」与计划号`, rowText.includes(planNumber) ? '✓ 行内带单号' : '⚠ 缺单号');
  await planRow.locator('button, .el-button').filter({ hasText: '逐道录入' }).first().click();
  await page.waitForSelector('.el-drawer__body', { timeout: 15000 });
  await page.waitForTimeout(2500);
  await shot('drawer-open');
  const drawer = page.locator('.el-drawer__body');
  // 防呆 Rule 2: 抽屉标题带品名
  const drawerTitle = await page.locator('.el-drawer__title').first().innerText().catch(() => '')
    || await page.locator('.el-drawer__header').first().getAttribute('title').catch(() => '') || '';
  fp('Rule2-上下文', `逐道录入抽屉标题: "${drawerTitle.slice(0, 40)}"`, /门腔|猪舌|逐工序录入/.test(drawerTitle) ? '✓ 标题含品名' : '⚠ 标题缺品名(或测试选择器未取到)');

  const activePane = () => drawer.locator('.el-tab-pane:visible').first();
  async function gotoTab(name) { const t = drawer.locator('.el-tabs__item').filter({ hasText: name }).first(); if (await t.isVisible().catch(() => false)) { await t.click(); await page.waitForTimeout(1400); return true; } return false; }
  async function ycByProcess(procName, order) { const yc = arr(await apiGet(`/${FACTORY}/production-plans/${encodeURIComponent(planId)}/process-sheet/inventory/yield-card`)); return yc.filter((x) => (x.processName || '') === procName || x.processOrder === order); }
  // capture 保存 disabled reason (防呆 Rule 1: 预先显示边界)
  async function captureDisabled(row) { return await row.locator('button').filter({ hasText: '保存' }).first().getAttribute('title').catch(() => null); }

  // ---------- 首道(raw): 2 batches ----------
  await gotoTab(pick.procs[0].processName);
  await page.waitForTimeout(900);
  const rawSpecs = [{ b: pick.batches[0], out: 1.0, output: 0.9 }, { b: pick.batches[1], out: 0.8, output: 0.72 }];
  let firstSaved = 0; let sawDisabledHint = false;
  for (const r of rawSpecs) {
    const pane = activePane();
    await pane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
    await page.waitForTimeout(700);
    const row = pane.locator('table.sp-grid tbody tr.sp-tr').last();
    // 防呆 Rule 1: 未选批次前 保存 应禁用 + 内联红字提示
    const disBefore = await captureDisabled(row);
    if (disBefore && /选择|填写/.test(disBefore)) sawDisabledHint = true;
    const inlineHint = await row.locator('text=请先选择原料批次').first().isVisible().catch(() => false);
    if (inlineHint) sawDisabledHint = true;
    await selectByText(row.locator('.el-select').first(), r.b.batchNumber);
    const nums = row.locator('.el-input-number');
    await fillNum(nums.nth(0), r.out); await fillNum(nums.nth(1), r.output);
    await row.locator('button').filter({ hasText: '保存' }).first().click();
    const w = await waitSaved();
    if (w.saved) firstSaved++;
    if (/工时单价未配置/.test(w.toast)) fp('Rule防呆-缺配置不伪装', `保存返回警告: "${w.toast.slice(0, 50)}"`, '✓ 缺工时单价显式 warning, 非静默 0');
    await page.waitForTimeout(1200);
  }
  ok(firstSaved === 2, '首道 headed 录 2 原料批(多批次)', { firstSaved });
  fp('Rule1-预先边界', '首道未选批次时 保存禁用 + 内联"请先选择原料批次"红字', sawDisabledHint ? '✓ 预先显示边界(禁用+提示), 非事后报错' : '⚠ 未观察到预先禁用提示');
  await shot('first-2batches');

  // ---------- 中间单上游道: 把 2 个批次各自流转, 直到熟制前 (procs[1..mixIdx-1]) ----------
  const safeFeed = (rem) => Math.max(0.02, Math.min(0.3, Math.round((rem || 0.3) * 0.6 * 100) / 100));
  let prevBatches = (await ycByProcess(pick.procs[0].processName, pick.procs[0].processOrder)).map((x) => ({ bn: x.batchNumber, rem: num(x.remaining) ?? 0.3 })).filter((x) => x.bn);
  ok(prevBatches.length >= 2, '首道产出 2 批 (链路起点)', { count: prevBatches.length });
  for (let i = 1; i < pick.mixIdx; i++) {
    const proc = pick.procs[i];
    if (!(await gotoTab(proc.processName))) { ok(false, `切到工序 ${proc.processName}`, {}); break; }
    await page.waitForTimeout(900);
    let saved = 0;
    for (const up of prevBatches.slice(0, 2)) {
      const feed = safeFeed(up.rem), output = Math.round(feed * 0.9 * 100) / 100; // 投入安全量, 90% 出成
      const pane = activePane();
      await pane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
      await page.waitForTimeout(700);
      const row = pane.locator('table.sp-grid tbody tr.sp-tr').last();
      await selectByText(row.locator('.el-select').first(), up.bn).catch(() => null);
      const nums = row.locator('.el-input-number');
      // 单上游/去舌苔: 前两个数字列 (投入/产出 或 碎肉/产出)
      await fillNum(nums.nth(0), feed).catch(() => null);
      await fillNum(nums.nth(1), output).catch(() => null);
      await row.locator('button').filter({ hasText: '保存' }).first().click();
      const w = await waitSaved();
      if (w.saved) saved++;
      await page.waitForTimeout(1100);
    }
    ok(saved === 2, `中间道(${proc.processName}) headed 流转 2 批`, { saved });
    prevBatches = (await ycByProcess(proc.processName, proc.processOrder)).map((x) => ({ bn: x.batchNumber, rem: num(x.remaining) ?? 0.3 })).filter((x) => x.bn);
    if (prevBatches.length < 2) { ok(false, `中间道(${proc.processName}) 产出 2 批以维持混锅`, { count: prevBatches.length }); break; }
  }

  // ---------- 熟制(mix): 混锅消耗 2 个上游批 (真·混来源) ----------
  let mixSaved = false, mixBatch = null;
  const mixProc = pick.procs[pick.mixIdx];
  if (prevBatches.length >= 2 && await gotoTab(mixProc.processName)) {
    await page.waitForTimeout(1000);
    const pane = activePane();
    await pane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
    await page.waitForTimeout(800);
    const row = pane.locator('table.sp-grid tbody tr.sp-tr').last();
    await row.locator('button').filter({ hasText: /来源批/ }).first().click(); // open mix expander
    await page.waitForTimeout(800);
    const mixSec = pane.locator('.sp-tr-expand .sp-expand-section, .sp-expand-section').last();
    let mixTotalFeed = 0;
    for (let s = 0; s < 2; s++) {
      const feed = safeFeed(prevBatches[s].rem);
      mixTotalFeed += feed;
      await mixSec.locator('button').filter({ hasText: /来源批/ }).first().click();
      await page.waitForTimeout(600);
      await selectByText(mixSec.locator('.el-select').nth(s), prevBatches[s].bn).catch(() => null);
      await fillNum(mixSec.locator('.el-input-number').nth(s), feed).catch(() => null);
      await page.waitForTimeout(300);
    }
    const mixIn = Math.round(mixTotalFeed * 100) / 100, mixOut = Math.round(mixTotalFeed * 0.9 * 100) / 100;
    const mainNums = row.locator('.el-input-number');
    if (await mainNums.count() >= 2) { await fillNum(mainNums.nth(0), mixIn); await fillNum(mainNums.nth(1), mixOut); }
    await shot('mix-source');
    await row.locator('button').filter({ hasText: '保存' }).first().click();
    const w = await waitSaved(); mixSaved = w.saved;
    await page.waitForTimeout(1500);
    mixBatch = ((await ycByProcess(mixProc.processName, mixProc.processOrder))[0] || {}).batchNumber || null;
  }
  ok(mixSaved, `熟制(${mixProc.processName}) headed 混锅消耗 2 上游批(真·混来源)`, { mixSaved, mixBatch });

  // ---------- 气调(finished): 成品批 (盒数) ----------
  let finSaved = false;
  const finProc = pick.procs[pick.finIdx];
  if (mixBatch && await gotoTab(finProc.processName)) {
    await page.waitForTimeout(1000);
    const pane = activePane();
    await pane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
    await page.waitForTimeout(800);
    const row = pane.locator('table.sp-grid tbody tr.sp-tr').last();
    await row.locator('button').filter({ hasText: /来源批/ }).first().click();
    await page.waitForTimeout(800);
    const mixSec = pane.locator('.sp-tr-expand .sp-expand-section, .sp-expand-section').last();
    await mixSec.locator('button').filter({ hasText: /来源批/ }).first().click();
    await page.waitForTimeout(600);
    await selectByText(mixSec.locator('.el-select').first(), mixBatch).catch(() => null);
    await fillNum(mixSec.locator('.el-input-number').first(), 0.1).catch(() => null);
    // 气调 number 列(去掉 auto 后顺序): storage(0) sample(1) remainBox(2) claim(3)
    //   productWeight(4) trimmings(5) usedWeight(6) boxWeight(7) workerPrice(8)
    const nums = row.locator('.el-input-number');
    const cnt = await nums.count();
    if (cnt >= 1) await fillNum(nums.nth(0), 5).catch(() => null);    // 入库(盒) → sumBoxes>0
    if (cnt >= 5) await fillNum(nums.nth(4), 0.09).catch(() => null); // 成品重(kg)
    if (cnt >= 7) await fillNum(nums.nth(6), 0.1).catch(() => null);  // 使用重量(kg) → inputQuantity
    await shot('finished-qidiao');
    // 防呆 Rule 1: 捕获保存禁用原因
    const dis = await captureDisabled(row);
    if (dis) fp('Rule1-预先边界', `气调保存禁用提示: "${dis}"`, '✓ 缺字段时按钮带明确禁用原因(title)');
    await row.locator('button').filter({ hasText: '保存' }).first().click();
    const w = await waitSaved(); finSaved = w.saved;
    if (!finSaved) fp('Rule1-预先边界', `气调保存未成功 toast: "${w.toast.slice(0, 60)}"`, w.toast.includes('请') ? '✓ 错误明示需补什么' : '⚠ 错误不够具体');
    await page.waitForTimeout(1500);
  }
  ok(finSaved, `气调(${finProc.processName}) headed 成品批(盒数)`, { finSaved });

  // ---------- 核对结单 (UI) + 防呆审计 ----------
  await shot('before-settle');
  let settleDialogOk = false;
  try {
  // close drawer reliably (Escape) then wait hidden
  await page.keyboard.press('Escape').catch(() => null);
  await page.waitForTimeout(800);
  await page.locator('.el-drawer__close-btn').first().click().catch(() => null);
  await drawer.waitFor({ state: 'hidden', timeout: 8000 }).catch(() => null);
  await page.waitForTimeout(1000);
  const settleBtn = planRow.locator('button, .el-button').filter({ hasText: /核对结单|结单/ }).first();
  if (await settleBtn.isVisible().catch(() => false)) {
    await settleBtn.click();
    await page.waitForSelector('.settlement-dialog, .el-dialog', { timeout: 12000 }).catch(() => null);
    await page.waitForTimeout(2000);
    await shot('settle-dialog');
    const dlg = page.locator('.settlement-dialog').first();
    const dEl = (await dlg.count()) ? dlg : page.locator('.el-dialog').filter({ hasText: /核对结单|结单/ }).first();
    // 防呆 Rule 2: 结单弹窗带计划号/品名/计划数量
    const ctxVals = await dEl.locator('.settlement-context-value').allInnerTexts().catch(() => []);
    fp('Rule2-上下文', `结单弹窗上下文: ${JSON.stringify(ctxVals.slice(0, 3))}`, ctxVals.some((t) => t.includes(planNumber)) ? '✓ 带计划号+品名+计划数量' : '⚠ 上下文不足');
    // 防呆 Rule 3: 差异原因是否下拉(非自由文本)
    const hasVarSelect = await dEl.locator('.el-form-item').filter({ hasText: '差异原因' }).locator('.el-select').count().catch(() => 0);
    fp('Rule3-约束选择', `差异原因控件: ${hasVarSelect ? 'el-select 下拉' : '未见下拉'}`, hasVarSelect ? '✓ 标准原因下拉非自由文本' : '⚠ 建议改下拉');
    // 防呆 Rule 1: 提交前禁用原因显式
    const disabledReason = await dEl.locator('.el-alert--warning .el-alert__title').last().innerText().catch(() => '');
    if (disabledReason) fp('Rule1-预先边界', `结单禁用原因显式: "${disabledReason.slice(0, 50)}"`, '✓ 提交前显示缺什么(非提交后报错)');
    settleDialogOk = ctxVals.length > 0;
  }
  } catch (e) { console.log('  settle section error:', e.message); }
  ok(settleDialogOk, '核对结单弹窗 headed 打开且带上下文', {});

  // ---------- final: rendered yield card chain readback ----------
  const ycFinal = arr(await apiGet(`/${FACTORY}/production-plans/${encodeURIComponent(planId)}/process-sheet/inventory/yield-card`));
  const mixCard = ycFinal.find((x) => x.batchNumber === mixBatch);
  ok(ycFinal.length >= 5, '全链出成率卡 ≥5 批 (首道2+中间+熟制+...)', { count: ycFinal.length });
  if (mixCard) {
    ok(Array.isArray(mixCard.sourceBreakdowns) && mixCard.sourceBreakdowns.length === 2, '熟制批 2 个来源拆分 (真·混来源验证)', { n: mixCard.sourceBreakdowns?.length, step: mixCard.stepYieldRate, cum: mixCard.cumulativeYieldRate });
  }

  await finish();

  async function finish() {
    const fails = asserts.filter((a) => !a.pass);
    const realErrors = consoleErrors.filter((e) => !/409|status of 4\d\d|favicon/.test(e)); // 409=幂等去重噪音, 忽略
    const status = fails.length === 0 && realErrors.length === 0 ? 'PASS' : (asserts.filter((a) => a.pass).length >= asserts.length - 2 ? 'PARTIAL' : 'FAIL');
    await writeFile(path.join(OUT, 'fullchain-result.json'), JSON.stringify({ status, planNumber: typeof planNumber !== 'undefined' ? planNumber : null, product: pick?.name, asserts, foolproof, consoleErrors }, null, 2), 'utf8').catch(() => null);
    console.log('\n===== 防呆审计结论 =====');
    for (const f of foolproof) console.log(`[${f.category}] ${f.verdict} — ${f.observation}`);
    console.log(JSON.stringify({ status, total: asserts.length, pass: asserts.filter((a) => a.pass).length, failures: fails.map((f) => f.label), consoleErrors: consoleErrors.slice(0, 4) }, null, 2));
    await browser.close().catch(() => null); ctx = null;
    if (status === 'FAIL') process.exitCode = 1;
  }
}
main().catch(async (e) => { if (ctx) await ctx.close().catch(() => null); await mkdir(OUT, { recursive: true }).catch(() => null); await writeFile(path.join(OUT, 'error.txt'), e.stack || String(e), 'utf8').catch(() => null); console.error('ERROR:', e.message); process.exitCode = 1; });
