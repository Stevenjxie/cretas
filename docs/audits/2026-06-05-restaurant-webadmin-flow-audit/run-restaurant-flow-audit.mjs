import { createRequire } from 'module';
import fs from 'fs/promises';
import path from 'path';
const require = createRequire('C:/Users/Steve/my-prototype-logistics/web-admin/package.json');
const { chromium } = require('playwright');
const BASE = process.env.E2E_BASE_URL || 'http://139.196.165.140:8086';
const API = process.env.E2E_API_BASE || `${BASE}/api/mobile`;
const USER = process.env.E2E_USER || 'qhj_prod';
const PASS = process.env.E2E_PASS || '123456';
const OUT = process.env.E2E_OUT || 'docs/audits/2026-06-05-restaurant-webadmin-flow-audit/evidence';
const routes = [
  { id: '00-dashboard', path: '/smart-bi/dashboard', purpose: '餐饮经营总览消费 POS/财务数据' },
  { id: '01-upload', path: '/smart-bi/upload', purpose: 'Excel/CSV POS 文件上传入口' },
  { id: '02-analysis', path: '/smart-bi/analysis', purpose: '上传文件分析与问数入口' },
  { id: '03-upload-status', path: '/smart-bi/upload-status', purpose: 'POS/Excel 上传状态' },
  { id: '04-role-kpi', path: '/restaurant/analytics/role-kpi', purpose: '店长 6 KPI 看板' },
  { id: '05-dishes', path: '/restaurant/analytics/dishes', purpose: '菜品成本/毛利/四象限' },
  { id: '06-targets', path: '/restaurant/analytics/targets', purpose: '目标拆分与达成' },
  { id: '07-price-anomaly', path: '/restaurant/price-anomaly', purpose: '供应商价格异常预警' },
  { id: '08-wastage', path: '/restaurant/wastage', purpose: '损耗责任制' },
  { id: '09-recipes', path: '/restaurant/recipes', purpose: '配方管理' },
  { id: '10-requisitions', path: '/restaurant/requisitions', purpose: '餐饮领料' },
  { id: '11-stocktaking', path: '/restaurant/stocktaking', purpose: '餐饮盘点' },
  { id: '12-supplier-delivery', path: '/restaurant/supplier-delivery', purpose: '供应商进货/OCR/手工录入' },
  { id: '13-name-resolution', path: '/restaurant/admin/name-resolution', purpose: 'POS 菜品名称解析人工裁决' },
  { id: '14-data-completeness', path: '/restaurant/data-completeness', purpose: '餐饮数据完整度' },
  { id: '15-etl-status', path: '/restaurant/admin/etl-status', purpose: '餐饮 ETL 状态' },
  { id: '16-commission', path: '/restaurant/commission', purpose: '营销员提成' },
  { id: '17-health-report', path: '/smart-bi/health-report', purpose: 'AI 经营体检' },
];
async function login(context, page) {
  const res = await fetch(`${API}/auth/unified-login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: USER, password: PASS }) });
  const json = await res.json();
  const data = json.data || {};
  const token = data.accessToken || data.token || '';
  if (!token) throw new Error(`login failed: ${JSON.stringify(json).slice(0, 500)}`);
  const domain = new URL(BASE).hostname;
  await context.addCookies([{ name: 'cretas_access_token', value: token, domain, path: '/', httpOnly: true, secure: false, sameSite: 'Lax' }]);
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.evaluate(({ d, token }) => { localStorage.setItem('cretas_access_token', token); localStorage.setItem('cretas_user', JSON.stringify({ id: d.userId, username: d.username, email: '', isActive: true, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), userType: 'factory', factoryUser: { role: d.role, factoryId: d.factoryId, factoryType: d.factoryType || 'RESTAURANT', permissions: d.permissions || [] } })); }, { d: data, token });
  return data;
}
function classifyText(text) { return { hasUpload: /(上传|拖拽|Excel|CSV|文件|数据类型|开始分析)/i.test(text), hasWriteAction: /(新增|新建|创建|录入|提交|确认|保存|审批|导入|上传|解析|裁决|盘点|领料)/.test(text), hasEmpty: /(暂无|无数据|未配置|不足|去配置|请先|空)/.test(text), hasError: /(403|404|500|无权限|页面不存在|系统错误|Network Error)/i.test(text) }; }
await fs.mkdir(OUT, { recursive: true });
const browser = await chromium.launch({ headless: process.env.HEADLESS === '1' ? true : false });
const context = await browser.newContext({ viewport: { width: 1440, height: 950 }, recordVideo: { dir: OUT, size: { width: 1440, height: 950 } } });
const page = await context.newPage();
const consoleMessages = [];
const failedResponses = [];
page.on('console', (msg) => { if (['error', 'warning'].includes(msg.type())) consoleMessages.push(`[${msg.type()}] ${msg.text()}`); });
page.on('pageerror', (err) => consoleMessages.push(`[pageerror] ${err.message}`));
page.on('response', (response) => { const status = response.status(); if (status >= 400) failedResponses.push({ status, url: response.url() }); });
const loginData = await login(context, page);
const results = [];
for (const route of routes) {
  const startedFailures = failedResponses.length;
  await page.goto(`${BASE}${route.path}`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForLoadState('networkidle', { timeout: 12000 }).catch(() => {});
  await page.waitForTimeout(1200);
  const screenshot = path.join(OUT, `${route.id}.png`);
  await page.screenshot({ path: screenshot, fullPage: true });
  const text = (await page.locator('body').innerText({ timeout: 5000 }).catch(() => '')).replace(/\s+/g, ' ').trim();
  const buttons = await page.locator('button:visible').evaluateAll((els) => els.map((el) => el.textContent?.trim()).filter(Boolean).slice(0, 30)).catch(() => []);
  results.push({ ...route, finalUrl: page.url(), textSnippet: text.slice(0, 1200), buttons, signals: classifyText(text), failedResponses: failedResponses.slice(startedFailures), screenshot });
}
await context.close(); await browser.close();
const summary = { target: BASE, account: { username: USER, factoryId: loginData.factoryId, role: loginData.role, factoryType: loginData.factoryType }, generatedAt: new Date().toISOString(), consoleMessages, results };
await fs.writeFile(path.join(OUT, 'result.json'), JSON.stringify(summary, null, 2), 'utf8');
console.log(JSON.stringify({ account: summary.account, routeCount: results.length, pagesWithFailures: results.filter((r) => r.signals.hasError || r.failedResponses.length).map((r) => r.id), out: OUT }, null, 2));

