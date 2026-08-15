/**
 * ⚠ storageState 存在 .auth/ 而不是 test-results/.auth/:
 * outputDir 就是 test-results, Playwright 每轮启动先清空它 ——
 * 登录态因此永远不能跨轮复用, 且 --no-deps 跑子集时
 * 报 'Error reading storage state'(2026-08-15 实测)。
 * Auth setup — logs in via the UI, waits for the server to set the HttpOnly
 * cookie, then saves the storageState (cookies + localStorage) for downstream
 * test projects that declare `dependencies: ['vue-auth']`.
 */
import { test as setup, expect } from '@playwright/test';
import { injectAuthCookie, resolveTokenFromStorageState, E2E_USER, E2E_PASS, E2E_USER_2, E2E_PASS_2 } from './e2e-auth-helper';

const BASE_URL = process.env.E2E_BASE_URL || 'http://localhost:5173';

async function doLogin(page: import('@playwright/test').Page, username: string, password: string, outPath: string) {
  await page.goto(BASE_URL + '/login', { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(2000);
  // \ud83d\udd34 2026-08-15: \u539f\u672c\u7cbe\u786e\u5339\u914d '\u8bf7\u8f93\u5165\u7528\u6237\u540d'\u3002\u4ea7\u54c1\u52a0\u4e86\u624b\u673a\u53f7\u767b\u5f55\u540e, \u767b\u5f55\u9875 placeholder
  // \u53d8\u6210\u300c\u8bf7\u8f93\u5165\u624b\u673a\u53f7\u6216\u7528\u6237\u540d\u300d, \u7cbe\u786e\u5339\u914d\u5931\u914d \u2192 locator.fill \u8d85\u65f6 15s \u2192 vue-auth \u6b65\u9aa4\u5931\u8d25
  // \u2192 \u6240\u6709 dependencies: ['vue-auth'] \u7684\u9879\u76ee(vue-web-admin / p0p1p2 / phase2 / liushanmen /
  // data-fabric\u2026)\u5168\u90e8\u8dd1\u4e0d\u8d77\u6765\u3002\u6574\u5957 web E2E \u56e0\u6b64\u957f\u671f\u5904\u4e8e\u300c\u8d77\u4e0d\u6765\u300d\u72b6\u6001\u3002
  // \u6539\u7528\u5b50\u4e32\u6b63\u5219: \u65e0\u8bba placeholder \u600e\u4e48\u52a0\u524d\u540e\u7f00\u90fd\u80fd\u547d\u4e2d, \u4e0d\u4f1a\u518d\u88ab\u4e00\u6b21\u6587\u6848\u8c03\u6574\u6253\u65ad\u3002
  await page.getByPlaceholder(/\u7528\u6237\u540d/).fill(username);
  await page.getByPlaceholder('\u8bf7\u8f93\u5165\u5bc6\u7801').fill(password);
  await page.waitForTimeout(500);
  await page.getByRole('button', { name: '\u767b \u5f55' }).click();
  await page.waitForTimeout(8000);
  await page.waitForLoadState('networkidle');

  // Verify: user info should be in localStorage (non-sensitive data).
  // The access token is now in an HttpOnly cookie (not readable by JS).
  const user = await page.evaluate(() => localStorage.getItem('cretas_user'));
  console.log(`[auth-setup] ${username}: user=${user ? 'OK' : 'NULL'}, URL=${page.url()}`);

  // Check cookies for the HttpOnly auth token
  const cookies = await page.context().cookies();
  const authCookie = cookies.find(c => c.name === 'cretas_access_token');
  console.log(`[auth-setup] ${username}: auth cookie=${authCookie ? 'SET' : 'MISSING'}`);

  if (!user || !authCookie) {
    // Retry once
    console.log(`[auth-setup] ${username}: auth incomplete, retrying`);
    await page.goto(BASE_URL + '/login', { waitUntil: 'networkidle', timeout: 30000 });
    await page.waitForTimeout(2000);
    // \u540c\u4e0a: \u5b50\u4e32\u5339\u914d, \u89c1\u51fd\u6570\u5f00\u5934\u90a3\u6bb5\u8bf4\u660e\u3002
    await page.getByPlaceholder(/\u7528\u6237\u540d/).fill(username);
    await page.getByPlaceholder('\u8bf7\u8f93\u5165\u5bc6\u7801').fill(password);
    await page.waitForTimeout(500);
    await page.getByRole('button', { name: '\u767b \u5f55' }).click();
    await page.waitForTimeout(10000);
    await page.waitForLoadState('networkidle');

    const user2 = await page.evaluate(() => localStorage.getItem('cretas_user'));
    const cookies2 = await page.context().cookies();
    const authCookie2 = cookies2.find(c => c.name === 'cretas_access_token');
    console.log(`[auth-setup] ${username} retry: user=${user2 ? 'OK' : 'STILL NULL'}, cookie=${authCookie2 ? 'SET' : 'STILL MISSING'}`);
  }

  // 🔴 口令登录失败时的兜底 —— 否则这一步会「成功」地存下一个**没有 cookie 的**状态。
  //
  // 实测长相 (2026-08-15): factory_admin1/123456 在本环境返回「用户名或密码错误」,
  // 本 setup 照样跑完并保存 `cookies: []` 的 storageState, 而且**不报错**。
  // 于是所有 `dependencies: ['vue-auth']` 的项目(vue-web-admin / p0p1p2 / phase2 /
  // liushanmen / data-fabric…)全部停在登录页 —— phase2-verify 6 条挂在
  // 「找不到节点面板/审批表格」, 看起来像工作流设计器坏了, 实际是根本没登录。
  // web-admin 靠 **HttpOnly cookie** 鉴权, 只有 localStorage 是不够的。
  const preSaveCookies = await page.context().cookies();
  if (!preSaveCookies.find((c) => c.name === 'cretas_access_token')) {
    const tk = process.env.E2E_TOKEN || resolveTokenFromStorageState(outPath);
    if (tk) {
      const claims = JSON.parse(Buffer.from(
        tk.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf-8'));
      console.log(`[auth-setup] ${username}: 口令登录拿不到 cookie, 改用已有 token 合成会话 (user=${claims.username}, factory=${claims.factoryId})`);
      await injectAuthCookie(page.context(), page, tk, {
        userId: claims.userId, username: claims.username, role: claims.role,
        factoryId: claims.factoryId, factoryType: 'FACTORY', permissions: ['*:*'],
      }, BASE_URL);
    }
  }

  // Navigate to dashboard to trigger router (ensures correct origin for storageState)
  await page.goto(BASE_URL + '/dashboard', { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(3000);
  console.log(`[auth-setup] ${username}: final URL=${page.url()}`);

  // Save storageState (includes both cookies and localStorage)
  await page.context().storageState({ path: outPath });

  // Verify saved result
  const fs = await import('fs');
  const saved = JSON.parse(fs.readFileSync(outPath, 'utf-8'));
  const cookieCount = saved.cookies?.length || 0;
  const originCount = saved.origins?.length || 0;
  const itemCount = saved.origins?.reduce((n: number, o: { localStorage?: unknown[] }) => n + (o.localStorage?.length || 0), 0) || 0;
  console.log(`[auth-setup] ${username}: saved cookies=${cookieCount}, origins=${originCount}, localStorage items=${itemCount}`);

  // ⛔ 存下一个没有 auth cookie 的 storageState = 把「没登录」伪装成「登录好了」,
  //    下游整片套件会以最难查的方式失败。宁可在这里红。
  const savedAuthCookie = (saved.cookies || []).find(
    (c: { name: string }) => c.name === 'cretas_access_token');
  expect(savedAuthCookie,
    `${username} 的 storageState 里没有 cretas_access_token cookie —— ` +
    '口令登录失败且没有可用的兜底 token(设 E2E_TOKEN 或先跑一次能登录的账号)').toBeTruthy();
}

setup('主账号登录并保存状态', async ({ page }) => {
  await doLogin(page, E2E_USER, E2E_PASS, '.auth/factory-admin.json');
});

setup('第二角色登录并保存状态', async ({ page }) => {
  await doLogin(page, E2E_USER_2, E2E_PASS_2, '.auth/workshop-sup.json');
});
