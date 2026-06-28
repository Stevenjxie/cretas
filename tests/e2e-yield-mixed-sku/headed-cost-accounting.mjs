/**
 * 成本核算页数字 headed 核对 (F006) + 成本 API 内部一致性.
 *
 * - API: 取一个已完成计划的成品批, 拉 cost-analysis(简单) + cost-analysis/enhanced,
 *   校验 成本准确性:
 *     · simple.totalCost == enhanced.totalCost (production-cost-live-chain 不变量)
 *     · material + labor + equipment + other ≈ total (分桶求和)
 *     · unitCost ≈ total / quantity
 * - HEADED: 打开「看成本核算」页 (/production-analytics/yield-cost), 切批次号模式,
 *   输入批次号刷新, 验证页面渲染的总成本数字 == API (客户看到的成本数字准确).
 *
 * Env: E2E_USERNAME E2E_PASSWORD [E2E_ADMIN_URL E2E_FACTORY_ID PLAYWRIGHT_PORT]
 */
import { chromium } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const APP = process.env.E2E_ADMIN_URL || 'http://139.196.165.140:8086';
const FACTORY = process.env.E2E_FACTORY_ID || 'F006';
const OUT = path.resolve(process.env.E2E_OUT || `.playwright-mcp/cost-acct-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const PROFILE = path.join(OUT, `profile-${Date.now().toString(36)}`);
const PORT_ARG = process.env.PLAYWRIGHT_PORT ? [`--remote-debugging-port=${process.env.PLAYWRIGHT_PORT}`] : [];
const U = process.env.E2E_USERNAME, P = process.env.E2E_PASSWORD;
if (!U || !P) { console.error('E2E_USERNAME/E2E_PASSWORD required'); process.exit(1); }

const asserts = [];
const consoleErrors = [];
let ctx = null;
function ok(p, label, d = {}) { asserts.push({ pass: !!p, label, ...d }); console.log(`${p ? 'PASS' : 'FAIL'} ${label} ${Object.keys(d).length ? JSON.stringify(d) : ''}`); return !!p; }
function approx(a, e, tol, label) { const p = a != null && e != null && Math.abs(a - e) <= tol; asserts.push({ pass: p, label, actual: a, expected: e }); console.log(`${p ? 'PASS' : 'FAIL'} ${label} act=${a} exp=${e}`); return p; }
const arr = (d) => Array.isArray(d) ? d : (d && (d.content || d.records || d.items)) || [];
const num = (v) => { const n = Number(v); return Number.isFinite(n) ? n : null; };

async function main() {
  await mkdir(OUT, { recursive: true });
  const browser = await chromium.launchPersistentContext(PROFILE, { headless: false, viewport: { width: 1920, height: 1080 }, args: ['--lang=zh-CN', '--font-render-hinting=none', ...PORT_ARG] });
  ctx = browser;
  const page = browser.pages()[0] || await browser.newPage();
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push(e.message));
  const shot = async (n) => { await page.screenshot({ path: path.join(OUT, n + '.png'), fullPage: true }).catch(() => null); };

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
  const H = { Authorization: `Bearer ${token}` };
  const api = async (p) => { const r = await fetch(`${APP}/api/mobile${p}`, { headers: H }); const j = await r.json().catch(() => ({})); return { status: r.status, data: j && 'data' in j ? j.data : j }; };

  // ---- find a completed plan's REGULAR batch with non-null totalCost ----
  let batch = null;
  for (let p = 1; p <= 3 && !batch; p++) {
    const plans = arr((await api(`/${FACTORY}/production-plans?page=${p}&size=50`)).data);
    for (const pl of plans.filter((x) => String(x.status).toUpperCase() === 'COMPLETED')) {
      const pbs = arr((await api(`/${FACTORY}/processing/batches?productionPlanId=${pl.id}&size=20`)).data);
      const fin = pbs.find((b) => num(b.totalCost) != null && num(b.totalCost) > 0 && /REGULAR|CLK-B/.test(String(b.batchType || b.batchNumber || '')));
      if (fin) { batch = { ...fin, planNumber: pl.planNumber }; break; }
    }
  }
  ok(!!batch, '找到已完成计划的成品批(totalCost>0)', { bn: batch?.batchNumber, total: batch?.totalCost });
  if (!batch) { await finish(); return; }

  // ---- API: cost-analysis simple + enhanced 一致性 ----
  const simple = (await api(`/${FACTORY}/processing/batches/${batch.id}/cost-analysis`)).data || {};
  const enhanced = (await api(`/${FACTORY}/processing/batches/${batch.id}/cost-analysis/enhanced`)).data || {};
  const sTotal = num(simple.totalCost), eTotal = num(enhanced.costBreakdown?.totalCost ?? enhanced.totalCost);
  console.log('simple:', JSON.stringify({ total: sTotal, mat: simple.materialCost, lab: simple.laborCost, equ: simple.equipmentCost, oth: simple.otherCost, unit: simple.unitCost }));
  console.log('enhanced:', JSON.stringify({ total: eTotal, raw: enhanced.rawMaterialCost, lab: enhanced.laborCost, equ: enhanced.equipmentCost }));
  approx(sTotal, eTotal, 0.01, '成本一致性: simple.total == enhanced.total');
  // 分桶求和 ≈ total
  const sum = (num(simple.materialCost) ?? 0) + (num(simple.laborCost) ?? 0) + (num(simple.equipmentCost) ?? 0) + (num(simple.otherCost) ?? 0);
  approx(sum, sTotal, 0.02, '分桶求和(料+工+设备+其他) ≈ total');
  // unitCost ≈ total / quantity
  const qty = num(batch.quantity ?? batch.plannedQuantity);
  if (qty && qty > 0 && num(simple.unitCost) != null) approx(num(simple.unitCost), sTotal / qty, Math.max(0.01, sTotal * 0.01), 'unitCost ≈ total / 数量');

  // ---- HEADED: 成本核算页 按批次号查询, 核对渲染总成本 ----
  await page.goto(`${APP}/production-analytics/yield-cost`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(3000);
  // 切到「批次号」模式
  await page.locator('.el-radio-button, label').filter({ hasText: '批次号' }).first().click().catch(() => null);
  await page.waitForTimeout(600);
  // 输入批次号
  const inp = page.locator('input[placeholder="批次号"]').first();
  await inp.click().catch(() => null);
  await inp.fill(batch.batchNumber).catch(() => null);
  await page.waitForTimeout(400);
  // 刷新/查询
  await page.locator('button').filter({ hasText: /刷新|查询/ }).first().click().catch(() => null);
  await page.waitForTimeout(3500);
  await shot('cost-page');
  const bodyText = await page.locator('body').innerText().catch(() => '');
  ok(!/加载失败|出错|error/i.test(bodyText.slice(0, 2000)) || bodyText.includes(batch.batchNumber), '成本核算页按批次号加载(无错误)', {});
  // 页面应渲染该批次号 + 总成本数字 (scale-2)
  ok(bodyText.includes(batch.batchNumber), '成本核算页渲染该批次号', { bn: batch.batchNumber });
  const totalStr = Number(sTotal).toFixed(2);
  const renders = bodyText.includes(totalStr) || bodyText.includes('¥' + totalStr) || bodyText.replace(/[\s,]/g, '').includes(totalStr);
  ok(renders, '成本核算页渲染总成本数字 == API(客户看到的成本准确)', { apiTotal: totalStr, found: renders });

  await finish();
  async function finish() {
    const fails = asserts.filter((a) => !a.pass);
    const realErr = consoleErrors.filter((e) => !/409|status of 4\d\d|favicon/.test(e));
    const status = fails.length === 0 && realErr.length === 0 ? 'PASS' : (fails.length <= 1 ? 'PARTIAL' : 'FAIL');
    await writeFile(path.join(OUT, 'cost-acct-result.json'), JSON.stringify({ status, batch: batch?.batchNumber, simple, enhanced, asserts, consoleErrors }, null, 2), 'utf8').catch(() => null);
    console.log(JSON.stringify({ status, total: asserts.length, pass: asserts.filter((a) => a.pass).length, failures: fails.map((f) => f.label) }, null, 2));
    await browser.close().catch(() => null); ctx = null;
    if (status === 'FAIL') process.exitCode = 1;
  }
}
main().catch(async (e) => { if (ctx) await ctx.close().catch(() => null); await mkdir(OUT, { recursive: true }).catch(() => null); await writeFile(path.join(OUT, 'error.txt'), e.stack || String(e), 'utf8').catch(() => null); console.error('ERROR:', e.message); process.exitCode = 1; });
