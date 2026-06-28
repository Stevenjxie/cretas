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
