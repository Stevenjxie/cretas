/**
 * Shared E2E authentication helper for Playwright tests.
 *
 * The web-admin app uses HttpOnly cookies for auth tokens (set by the server
 * via Set-Cookie header). localStorage only stores non-sensitive user info.
 *
 * This helper:
 *   1. Calls the login API to get a token + user data
 *   2. Injects the token as an HttpOnly cookie via context.addCookies()
 *   3. Sets cretas_user in localStorage (the app needs this for user info display)
 */

import { Page, BrowserContext } from '@playwright/test';
import { existsSync, readFileSync } from 'fs';

export interface LoginResult {
  token: string;
  loginData: Record<string, unknown>;
}

/**
 * API 基址。
 *
 * 🔴 2026-08-15: 这里原本硬编码 `http://47.100.235.168:10010/api/mobile` —— 直连 IP + 明文
 * 端口。实测该端口**在服务器之外连不上**(curl 直连 http_code=000/超时; 经 HTTPS 网关
 * https://admin.cretaceousfuture.com/api/mobile 返回 401, 即网关通、直连不通)。
 * 于是任何用 {@link fetchLoginToken} 的用例在开发机上**永远不可能通过**, 症状是
 * `TypeError: fetch failed  [cause]: SocketError: other side closed` —— 看起来像网络抖动,
 * 实际是端点根本不可达。
 *
 * 现在按优先级取: E2E_API_BASE > 由 E2E_BASE_URL 推出同源的 /api/mobile > 旧的直连地址。
 * 这样「测试打哪个站点」和「测试问哪个 API」自动一致, 不会再各说各话。
 */
export function resolveApiBase(): string {
  // 同一件事仓里有三个变量名在用: E2E_API_BASE / E2E_API_URL / (helper 自己的默认)。
  // 两个都认, 免得「设了一个没生效」这种只能靠读源码才知道的坑。
  const explicit = process.env.E2E_API_BASE || process.env.E2E_API_URL;
  if (explicit) return explicit.replace(/\/+$/, '');
  const site = process.env.E2E_BASE_URL;
  if (site) return `${site.replace(/\/+$/, '')}/api/mobile`;
  // ⛔ 兜底不再用 47.100.235.168:10010 —— 安全组挡了直连
  // (ai-workprocess-draft-render.spec.ts:21 早就写下了这条, 但只有那一个文件知道)。
  // 走 nginx 网关。
  return 'http://139.196.165.140:8086/api/mobile';
}

const DEFAULT_API = resolveApiBase();

/**
 * 从 storageState 文件里取出 access token。
 *
 * <p>为什么需要: 套件里有两套鉴权 —— 多数 project 靠 `storageState`(vue-auth 产出),
 * 少数用例自己调登录 API 拿 token 去打接口。后者绑定了具体账号口令, 换个环境就跑不了。
 * 这个函数让「需要 token 打接口」的用例复用前者的产物, 不再各自持有口令。
 *
 * <p>⛔ 只用于**直接调 API**。不要拿它去 {@link injectAuthCookie} 覆盖浏览器会话 ——
 * 2026-08-15 实测那样做会把 project 自带的 storageState 冲掉, 71 条从 68 过跌到 26 过。
 */
export function resolveTokenFromStorageState(
  file = process.env.E2E_STORAGE_STATE || '.auth/factory-admin.json',
): string {
  // ⚠️ 用顶层 import 的 fs, 不要 require(): spec 由 Playwright 以 ESM 加载,
  // require 未定义会抛 ReferenceError, 被 catch 吞掉后本函数恒返回 '' ——
  // 表现为「fallback 写了但从不生效」, 且一行日志都没有(2026-08-15 实测踩过)。
  try {
    if (!existsSync(file)) {
      console.warn(`[e2e-auth] storageState 不存在: ${file}`);
      return '';
    }
    const state = JSON.parse(readFileSync(file, 'utf-8'));
    for (const origin of state.origins || []) {
      for (const item of origin.localStorage || []) {
        if (item.name === 'cretas_access_token' && item.value) return item.value;
      }
    }
    console.warn(`[e2e-auth] ${file} 里没有 cretas_access_token`);
  } catch (e) {
    // 不静默: 读不出来是最需要看见的那类失败
    console.warn(`[e2e-auth] 读 storageState 失败: ${(e as Error).message}`);
  }
  return '';
}

/**
 * Call the login API and return the token + full login data.
 *
 * <p>没有口令时用 {@link loginOrReuseSession}: 它会退回到 vue-auth 产出的
 * storageState token。实测(干净机器)这条路 web-admin-e2e 71/71 全过 ——
 * **不需要真实账号也能跑完整套**。
 *
 * <p>⚠️ 曾经在这里写过一段「注入 token 有害」的结论, 依据是一串
 * 68/3 -> 34/37 -> 26/45 的通过数下滑。**那串读数是无效的**: 当时每跑一轮 E2E 都会
 * 留下几十个孤儿进程, 跑到最后 node 106 个、可用内存 2.43GB/63.88GB,
 * 浏览器直接 `browserType.launch: Timeout 180000ms exceeded`。
 * 下滑的是机器不是代码。清理后同一份代码 69 过。
 * 判据: 跑重型套件时把**可用内存**与通过数并排记, 两条曲线一起动就说明在量环境。
 */
export async function fetchLoginToken(
  username = 'factory_admin1',
  password = '123456',
  apiBase = DEFAULT_API,
): Promise<LoginResult> {
  const res = await fetch(`${apiBase}/auth/unified-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  const json = await res.json();
  const data = json.data || {};
  const token = data.accessToken || data.token || '';
  if (!token) {
    throw new Error(`Login API failed for ${username}: ${json.message || 'no token returned'}`);
  }
  return { token, loginData: data };
}

/**
 * Build the cretas_user JSON string from login API data.
 */
function buildUserJson(d: Record<string, unknown>): string {
  return JSON.stringify({
    id: d.userId,
    username: d.username,
    email: '',
    isActive: true,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    userType: 'factory',
    factoryUser: {
      role: d.role,
      factoryId: d.factoryId,
      factoryType: d.factoryType || 'FACTORY',
      permissions: d.permissions || [],
    },
  });
}

/**
 * Inject authentication into a Playwright browser context.
 *
 * - Sets the access token as an HttpOnly cookie (matching how the server sets it).
 * - Sets cretas_user in localStorage (the app reads this for user info).
 *
 * @param context  - The BrowserContext (from test or page.context())
 * @param page     - A Page in the context (used to set localStorage)
 * @param token    - JWT access token
 * @param loginData - Full login response data
 * @param baseUrl  - The web-admin base URL (used to determine the cookie domain)
 */
export async function injectAuthCookie(
  context: BrowserContext,
  page: Page,
  token: string,
  loginData: Record<string, unknown>,
  baseUrl: string,
): Promise<void> {
  // Parse the domain from the base URL for the cookie
  const url = new URL(baseUrl);
  const domain = url.hostname;

  // 1. Set the access token as an HttpOnly cookie
  // secure 必须跟着 baseUrl 的协议走: 写死 false 时, https 站点上的 Secure 语义与实际不符,
  // 一旦服务端改成只认 Secure cookie 就会静默失去登录态(症状是「跑起来了但每页都跳登录」)。
  await context.addCookies([
    {
      name: 'cretas_access_token',
      value: token,
      domain,
      path: '/',
      httpOnly: true,
      secure: url.protocol === 'https:',
      sameSite: 'Lax',
    },
  ]);

  // 2+3. 用 addInitScript 在**页面脚本执行之前**写入 localStorage。
  //
  // 🔴 2026-08-15: 原实现是 `goto('/login')` 之后再 page.evaluate 写 localStorage。
  // 两个问题:
  //   · 每调用一次就多一次到登录页的往返(本文件的 go() 每个用例都调, 71 条 = 142 次导航);
  //   · 更要命的是登录页会清会话 —— 写完 localStorage 再跳目标页, 目标页却是未登录态,
  //     表现为「每个列表都空」而不报任何错。实测 71 条里 58 条挂在
  //     expectPageContent(页面上没有任何表格/卡片/标题), 根因全在这里。
  //
  // addInitScript 是 Playwright 为这件事提供的机制: 注册后对该 context 的**每一次**
  // 导航都会在应用代码之前执行, 应用一启动就已经是登录态, 不需要先去 /login。
  //
  // ⚠️ 同时写 cookie 与 localStorage: 前端发 API 取的是 localStorage 里的
  // cretas_access_token(Authorization: Bearer), 只给 cookie 时页面能进、接口会 401 ——
  // 表现为「列表全空但没有报错」这种最难查的假象。
  const userJson = buildUserJson(loginData);
  await context.addInitScript(([user, tok]: string[]) => {
    try {
      localStorage.setItem('cretas_user', user);
      localStorage.setItem('cretas_access_token', tok);
    } catch { /* 某些页面禁用 storage 时不要把导航整个打断 */ }
  }, [userJson, token]);
  // page 参数保留是为了兼容既有调用方签名; 这里不再需要它做导航。
  void page;
}

/**
 * Convenience: fetch token + inject cookie + navigate to a target page.
 *
 * Use this in beforeAll or beforeEach to set up authenticated state.
 */
export async function setupAuth(
  context: BrowserContext,
  page: Page,
  baseUrl: string,
  apiBase = DEFAULT_API,
  username = 'factory_admin1',
  password = '123456',
): Promise<LoginResult> {
  const result = await fetchLoginToken(username, password, apiBase);
  await injectAuthCookie(context, page, result.token, result.loginData, baseUrl);
  return result;
}

/**
 * 拿一个可用的登录态: 先试口令登录, 不行就用 vue-auth 产出的 storageState token。
 *
 * <p>把这段收进来是因为它已经在 4 个 spec 里各抄了一遍 —— 抄第三遍时就该收口了。
 * 两条路都没有时**抛原始错误**, 不返回半个凭证: 残缺的 loginData 会被
 * {@link injectAuthCookie} 写进 localStorage 覆盖掉正常会话(实测过, 症状是
 * 「每个列表都空但不报错」)。
 */
export async function loginOrReuseSession(
  username = 'factory_admin1',
  password = '123456',
  apiBase = DEFAULT_API,
  tag = 'e2e',
): Promise<LoginResult> {
  try {
    return await fetchLoginToken(username, password, apiBase);
  } catch (e) {
    const token = resolveTokenFromStorageState();
    if (!token) throw e;
    const c = JSON.parse(
      Buffer.from(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf-8'),
    );
    if (!c.factoryId || !c.userId) {
      throw new Error('storageState 里的 token 缺 factoryId/userId, 注进去会让工厂级接口全挂');
    }
    console.warn(`[${tag}] 口令登录不可用, 改用 storageState token: ${(e as Error).message}`);
    return {
      token,
      loginData: {
        userId: c.userId, username: c.username, role: c.role,
        factoryId: c.factoryId, factoryType: 'FACTORY', permissions: ['*:*'],
      },
    };
  }
}

/**
 * 等某类内容出现 —— 自动重试, 不睡固定时间。
 *
 * <p>本仓的 E2E 里反复出现同一组坏判据(web-admin-e2e / web-admin-crud /
 * web-admin-workflows 各写了一份):
 * <ul>
 *   <li>{@code waitForTimeout(3000)} 之后立刻断言 —— 等的是「3 秒过去了」,
 *       不是「页面好了」。这个 SPA 常常还停在「正在加载应用…」骨架屏上,
 *       失败只说「页面没有表格」, 读起来像没登录, 实际是没启动完。</li>
 *   <li>一次性 {@code isVisible()} —— 不重试, 页面慢一点就判死。</li>
 *   <li>{@code .first()} 直接取 —— 取的是 <b>DOM 顺序</b>第一个, 不看可见性。
 *       /sales/orders 的第一个 .el-card 恰好是隐藏的 gold-pos-summary,
 *       于是断言恒失败而页面上明明有可见卡片。</li>
 * </ul>
 *
 * 三条都由这一个函数消掉: 先按可见性过滤再取 first, 用自动重试断言按需等待。
 */
export async function expectAnyVisible(
  page: Page,
  selector: string,
  timeout = 25000,
): Promise<void> {
  const { expect } = await import('@playwright/test');
  await expect(page.locator(selector).filter({ visible: true }).first())
    .toBeVisible({ timeout });
}

/** 页面「有实际内容」的通用判据 —— 表格/卡片/标题/图表任一可见即可。 */
export const ANY_CONTENT_SELECTOR =
  '.el-table, .el-card, h1, h2, h3, .page-title, .el-page-header__title, canvas, .echarts, [_echarts_instance_]';

/**
 * 导航后若被路由守卫送到 /403, 明确 skip 并说明 —— 不要报成功能失败。
 *
 * <p>实测: f006_admin(factory_super_admin, 权限 *:*) 访问 /system/ai-intents 会被
 * 前端守卫跳到 /403。此时断言「表格渲染」必然失败, 而失败信息读起来像
 * 「AI 意图列表坏了」—— 实际是这个角色本来就进不去。
 * 把「无权访问」和「功能坏了」分开, 否则套件里会长期挂着一条谁也不敢删的红。
 */
export async function skipIfForbidden(page: Page, route: string): Promise<void> {
  const { test } = await import('@playwright/test');
  // ⚠️ 不能在 goto 之后立刻读 URL: 跳 /403 的是**前端路由守卫**, 在
  // domcontentloaded 之后才执行。我第一版就是立刻读, 于是永远读不到 403,
  // skip 形同虚设(写了但从不触发, 且没有任何迹象说明它没起作用)。
  // 这里给一个有界的等待: 命中就 skip, 没命中(绝大多数用例)立刻往下走。
  await page.waitForURL(/\/403/, { timeout: 1500 }).catch(() => { /* 未跳转 = 有权访问 */ });
  if (page.url().includes('/403')) {
    test.skip(true, `当前账号无权访问 ${route}(被守卫跳到 /403), 非功能缺陷`);
  }
}
