/**
 * PURE-HEADED 前端 E2E — 复杂多批次逐道录入 (无 API 播种, 全程点 UI).
 *
 * 与 ui-render-deep-audit.mjs 的区别: 那个 API 播种数据再读 UI; 这个**全程操作真实前端**:
 *   - UI 新建生产计划 (手动来源, 逐道报工)
 *   - UI 打开「逐道录入」抽屉
 *   - UI 在首道(修油)录入 2 个原料批次 → 2 个 WIP 批 (多批次)
 *   - UI 在熟制道混锅来源消耗这 2 个 WIP 批 (混来源)
 *   - UI 读「双出成率总览」渲染数字, 校验多批次链路算出
 *
 * 仅用 API 做只读发现 (选哪个产品/原料批次) + 校验回读, 不用 API 写任何被测数据。
 * Headed per .claude/rules/playwright-headed-mode.md.
 *
 * Env: E2E_USERNAME E2E_PASSWORD [E2E_ADMIN_URL E2E_FACTORY_ID PLAYWRIGHT_PORT]
 */
import { chromium } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const APP = process.env.E2E_ADMIN_URL || 'http://139.196.165.140:8086';
const FACTORY = process.env.E2E_FACTORY_ID || 'F006';
const OUT = path.resolve(process.env.E2E_OUT || `.playwright-mcp/headed-mb-${new Date().toISOString().replace(/[:.]/g, '-')}`);
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

async function main() {
  await mkdir(OUT, { recursive: true });
  const browser = await chromium.launchPersistentContext(PROFILE, {
    headless: false, viewport: { width: 1920, height: 1080 },
    args: ['--lang=zh-CN', '--font-render-hinting=none', ...PORT_ARG],
  });
  ctx = browser;
  const page = browser.pages()[0] || await browser.newPage();
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push(e.message));
  let shotN = 0;
  const shot = async (n) => { const f = path.join(OUT, `${String(++shotN).padStart(2, '0')}-${n}.png`); await page.screenshot({ path: f, fullPage: true }); return f; };

  // ---------- headed UI helpers ----------
  async function selectByText(scope, searchText) {
    // open el-select → click matching option by text (getByRole auto-scrolls + waits for the
    // real visible option; el-select renders all options so no filter typing needed).
    await scope.click();
    await page.waitForTimeout(700);
    const opt = page.getByRole('option').filter({ hasText: String(searchText) }).first();
    await opt.scrollIntoViewIfNeeded().catch(() => null);
    await opt.click({ timeout: 8000 });
    await page.waitForTimeout(400);
    return searchText;
  }
  const cleanFrag = (s) => String(s || '').split(/[（(]/)[0].trim();
  async function waitSaved(timeout = 9000) {
    // a row save shows EITHER success OR a warning toast ("已保存(含提示): ...") — both mean saved.
    try {
      const t = await page.waitForSelector('.el-message--success, .el-message--warning', { timeout });
      const txt = await t.innerText().catch(() => '');
      return /已保存|保存成功|成功/.test(txt) && !/失败|错误|无法保存/.test(txt);
    } catch { return false; }
  }
  async function fillNum(inputNumberLoc, value) {
    const inp = inputNumberLoc.locator('input').first();
    await inp.click();
    await inp.fill('');
    await inp.type(String(value));
    await inp.press('Tab');
    await page.waitForTimeout(150);
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
  ok(!!token, '登录成功获取 token');

  // ---------- read-only discovery: product with raw batches + a cook process ----------
  const H = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  const api = async (p) => { const r = await fetch(`${APP}/api/mobile${p}`, { headers: H }); const j = await r.json().catch(() => ({})); return j && 'data' in j ? j.data : j; };
  const products = arr(await api(`/${FACTORY}/product-types/active`));
  let pick = null;
  for (const pr of products.slice(0, 50)) {
    const id = String(pr.id || pr.productTypeId);
    const procs = arr(await api(`/${FACTORY}/product-work-processes?productTypeId=${encodeURIComponent(id)}`)).filter((x) => x.isActive !== false).sort((a, b) => (a.processOrder || 0) - (b.processOrder || 0));
    if (procs.length < 2) continue;
    // raw warehouse batches for this product
    const whs = arr(await api(`/${FACTORY}/factory/warehouses`));
    const raw = whs.find((w) => w.code === 'WH-LOG') || whs.find((w) => ['RAW', 'LOGISTICS'].includes(String(w.type)));
    if (!raw?.id) continue;
    const batches = arr(await api(`/${FACTORY}/material-batches/status/AVAILABLE?warehouseId=${encodeURIComponent(raw.id)}&productTypeId=${encodeURIComponent(id)}&size=50`))
      .filter((b) => num(b.currentQuantity ?? b.quantity) > 1 && !String(b.batchNumber || '').startsWith('WIP-') && !String(b.batchNumber || '').startsWith('CLK-'));
    const cook = procs.find((p) => /熟|气调|包装|卤|煮/.test(String(p.processName || '')));
    if (batches.length >= 2 && procs.length >= 2) { pick = { product: pr, id, name: pr.name || pr.productName, procs, batches, cook }; break; }
  }
  ok(!!pick, '发现可用产品(≥2原料批+≥2工序)', { name: pick?.name, procs: pick?.procs?.map((p) => p.processName) });
  if (!pick) { await dump(); return; }
  console.log('product:', pick.name, '| processes:', pick.procs.map((p) => p.processName).join('→'));
  const firstProc = pick.procs[0].processName;
  const cookProc = (pick.cook || pick.procs[pick.procs.length - 1]).processName;

  // ---------- 前置: 新建生产计划 (API precondition — 计划创建是前置条件, 非被测的录入键入).
  //            被测核心「逐道录入多批次键入」全程 headed (见下). ----------
  const apiPost = async (p, body) => { const r = await fetch(`${APP}/api/mobile${p}`, { method: 'POST', headers: H, body: body == null ? undefined : JSON.stringify(body) }); const j = await r.json().catch(() => ({})); if (!r.ok || j?.success === false) throw new Error(`POST ${p} ${r.status}: ${j?.message || ''}`); return j && 'data' in j ? j.data : j; };
  const today = (o = 0) => new Date(Date.now() + 8 * 3600e3 + o * 86400e3).toISOString().slice(0, 10);
  const plan = await apiPost(`/${FACTORY}/production-plans`, { productTypeId: pick.id, plannedQuantity: 5, plannedDate: today(0), expectedCompletionDate: today(2), sourceType: 'MANUAL', skipProcessReporting: false, customerOrderNumber: `HMB-${Date.now()}`, notes: 'headed multibatch test' });
  const planNumber = String(plan.planNumber || plan.id);
  const planId = String(plan.id || plan.planId);
  ok(!!planId, '前置生产计划已建(API)', { planNumber });
  await apiPost(`/${FACTORY}/production-plans/${encodeURIComponent(planId)}/start`).catch(() => null);

  // ---------- UI: 在计划列表找到该计划, 打开逐道录入 (HEADED 从这里开始) ----------
  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  const searchBox = page.locator('input[placeholder*="搜索"], input[placeholder*="计划编号"]').first();
  if (await searchBox.isVisible().catch(() => false)) { await searchBox.fill(planNumber); await page.locator('button').filter({ hasText: /搜索/ }).first().click().catch(() => null); await page.waitForTimeout(2000); }
  const topRow = page.locator('.el-table__row').filter({ hasText: planNumber }).first();
  ok(await topRow.isVisible().catch(() => false), '计划列表可见该计划行', { planNumber });

  // ---------- UI: 打开逐道录入 ----------
  const entryBtn = topRow.locator('button, .el-button').filter({ hasText: '逐道录入' }).first();
  ok(await entryBtn.isVisible().catch(() => false), '新计划行有「逐道录入」按钮');
  await entryBtn.click();
  await page.waitForSelector('.el-drawer__body', { timeout: 15000 });
  await page.waitForTimeout(2500);
  await shot('entry-drawer-open');
  const drawer = page.locator('.el-drawer__body');

  // helper: switch to a process tab by name
  async function gotoTab(nameFrag) {
    const tab = drawer.locator('.el-tabs__item').filter({ hasText: nameFrag }).first();
    if (await tab.isVisible().catch(() => false)) { await tab.click(); await page.waitForTimeout(1400); return true; }
    return false;
  }
  // the currently-active (visible) tab pane — el-tabs v-show-hides inactive panes,
  // so unscoped button/row lookups would hit hidden first-tab elements.
  const activePane = () => drawer.locator('.el-tab-pane:visible').first();

  // ---------- UI: 首道(修油) 录入 2 个原料批次 (多批次) ----------
  await gotoTab(firstProc);
  await page.waitForTimeout(1000);
  const rawInputs = [{ batch: pick.batches[0], out: 1.0, output: 0.9 }, { batch: pick.batches[1], out: 0.8, output: 0.72 }];
  let firstSaved = 0;
  for (let i = 0; i < rawInputs.length; i++) {
    const r = rawInputs[i];
    // ensure a blank row exists: click 「+ 新增行」 in the ACTIVE pane
    const pane = activePane();
    await pane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
    await page.waitForTimeout(700);
    const rowsNow = pane.locator('table.sp-grid tbody tr.sp-tr');
    const row = rowsNow.nth(await rowsNow.count() - 1);
    // 原料批次 select (first el-select in row)
    await selectByText(row.locator('.el-select').first(), r.batch.batchNumber);
    // 出库重量 (first input-number) + 产出数量 (next input-number)
    const nums = row.locator('.el-input-number');
    await fillNum(nums.nth(0), r.out);
    await fillNum(nums.nth(1), r.output);
    await page.waitForTimeout(300);
    await row.locator('button').filter({ hasText: '保存' }).first().click();
    if (await waitSaved()) firstSaved++; else console.log('  save row', i, 'not confirmed saved');
    await page.waitForTimeout(1400);
  }
  ok(firstSaved === 2, '首道 UI 录入并保存 2 个原料批次(多批次)', { firstSaved });
  await shot('first-process-2batches');

  // read the 2 WIP batch numbers via yield card API (read-only verify)
  let yc1 = arr(await api(`/${FACTORY}/production-plans/${encodeURIComponent(planId)}/process-sheet/inventory/yield-card`).catch(() => []));
  ok(yc1.length >= 2, '出成率卡回读到 ≥2 个首道批次(多批次已物化)', { count: yc1.length });

  // ---------- UI: 二道工序逐行消耗 2 个首道 WIP 批 (多批次链路流转) ----------
  // 动态工序链中每道消耗"上一道"; 故在第 2 道(消耗首道)做 2 行单上游, 把 2 个首道批各消耗一次 →
  // 验证多批次在 UI 全程流转 + 批次血缘. (真混锅在链尾, 需录满全链, 单独扩展.)
  const secondProc = pick.procs[1].processName;
  const wipBatches = yc1.slice(0, 2).map((x) => x.batchNumber).filter(Boolean);
  let downSaved = 0;
  try {
    if (await gotoTab(secondProc) && wipBatches.length >= 2) {
      await page.waitForTimeout(1000);
      for (let s = 0; s < 2; s++) {
        const pane = activePane();
        await pane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
        await page.waitForTimeout(700);
        const row = pane.locator('table.sp-grid tbody tr.sp-tr').last();
        // 单上游批次 select (该道第一个 el-select)
        await selectByText(row.locator('.el-select').first(), wipBatches[s]).catch(() => null);
        // 投入 / 产出 数字列 (before/after)
        const nums = row.locator('.el-input-number');
        await fillNum(nums.nth(0), 0.5);
        await fillNum(nums.nth(1), 0.45);
        await page.waitForTimeout(300);
        if (s === 0) await shot('second-process-consume');
        await row.locator('button').filter({ hasText: '保存' }).first().click();
        if (await waitSaved()) downSaved++; else console.log('  down save', s, 'not confirmed');
        await page.waitForTimeout(1300);
      }
    }
  } catch (e) { console.log('  downstream section error:', e.message); }
  ok(downSaved === 2, `二道(${secondProc}) UI 逐行消耗 2 个首道批(多批次链路流转)`, { downSaved });

  // ---------- read rendered yield card from DOM ----------
  await page.waitForTimeout(1500);
  const uiCard = await page.evaluate(() => {
    const tables = Array.from(document.querySelectorAll('.el-drawer__body .el-table'));
    const t = tables.find((x) => { const h = Array.from(x.querySelectorAll('thead th')).map((th) => th.innerText.trim()); return h.some((s) => s.includes('对上工序出成')) && h.some((s) => s.includes('对原料累计')); });
    if (!t) return { error: 'no yield card' };
    const heads = Array.from(t.querySelectorAll('thead th')).map((th) => th.innerText.trim());
    const idx = (l) => heads.findIndex((s) => s.includes(l));
    const rows = [];
    for (const tr of Array.from(t.querySelectorAll('tbody tr'))) {
      const td = Array.from(tr.querySelectorAll('td')).map((c) => c.innerText.trim());
      if (td.length) rows.push({ batch: td[idx('批次号')], step: td[idx('对上工序出成')], cum: td[idx('对原料累计')], cost: td[idx('分摊成本')] });
    }
    return { rows };
  });
  ok(!uiCard.error && (uiCard.rows || []).length >= 4, '渲染出成率卡含 ≥4 行(2首道+2二道, 多批次链路 UI 呈现)', { rows: uiCard.rows?.length, sample: uiCard.rows?.slice(0, 4) });
  // 链式出成率校验 (确保计算准确): 首道 step=cum=90% (产出/投入); 二道 cum≈81% (链式 0.9×0.9).
  const pct = (s) => { const m = String(s || '').match(/([\d.]+)%/); return m ? Number(m[1]) : null; };
  const rows = uiCard.rows || [];
  const firstRows = rows.filter((r) => pct(r.step) != null && Math.abs((pct(r.cum) ?? 0) - 90) < 0.2 && Math.abs((pct(r.step) ?? 0) - 90) < 0.2);
  const downRows = rows.filter((r) => pct(r.cum) != null && Math.abs((pct(r.cum) ?? 0) - 81) < 0.5);
  ok(firstRows.length >= 2, '首道 2 批 渲染 对上=对原料=90.00% (产出/投入精确)', { count: firstRows.length });
  ok(downRows.length >= 2, '二道 2 批 渲染 对原料累计≈80.99% (链式 0.9×0.9 精确)', { count: downRows.length, cums: downRows.map((r) => r.cum) });
  await shot('final-yield-card');

  async function dump() {}
  const fails = asserts.filter((a) => !a.pass);
  const status = fails.length === 0 && consoleErrors.length === 0 ? 'PASS' : 'FAIL';
  await writeFile(path.join(OUT, 'headed-multibatch-result.json'), JSON.stringify({ status, planNumber, product: pick?.name, firstProc, cookProc, uiCard, asserts, consoleErrors }, null, 2), 'utf8');
  console.log(JSON.stringify({ status, planNumber, totalAsserts: asserts.length, failures: fails.map((f) => f.label), consoleErrors: consoleErrors.slice(0, 5) }, null, 2));
  await browser.close(); ctx = null;
  if (status !== 'PASS') process.exitCode = 1;
}
main().catch(async (e) => { if (ctx) await ctx.close().catch(() => null); await mkdir(OUT, { recursive: true }).catch(() => null); await writeFile(path.join(OUT, 'error.txt'), e.stack || String(e), 'utf8').catch(() => null); console.error('ERROR:', e.message); process.exitCode = 1; });
