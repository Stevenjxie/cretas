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
 * Call the login API and return the token + full login data.
 *
 * <p>⛔ 试过并撤掉: 加一个 {@code E2E_ACCESS_TOKEN} 分支「直接复用既有会话、不发登录请求」。
 * 想法成立, 实现有害 —— 同一套 71 条用例, 不注入 <b>68 过 / 3 挂</b>,
 * 注入(只带 username) <b>34 过 / 37 挂</b>, 注入(从 JWT 解出完整用户) <b>26 过 / 45 挂</b>。
 * 每改一次更差, 说明问题不在参数而在这条路本身: {@link #injectAuthCookie} 会
 * {@code goto('/login')} 并重写 localStorage, 与 project 已经带的 storageState 打架。
 * 而失败信息只说「等不到元素」, 指不到根因。
 *
 * <p>要在没有口令的情况下跑, 用 Playwright 自己的机制: 准备好 storageState 文件后
 * {@code npx playwright test --project <name> --no-deps}(跳过 vue-auth 登录步骤)。
 * 实测这条路是 68/71。
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

  // 2. Navigate to the base URL so localStorage is on the right origin
  await page.goto(baseUrl + '/login', { waitUntil: 'domcontentloaded', timeout: 15000 });

  // 3. Set cretas_user + cretas_access_token in localStorage.
  // 🔴 原来只写 cretas_user。但前端发 API 时取的是 localStorage 里的
  // cretas_access_token(Authorization: Bearer), 只给 cookie 时页面能进、接口会 401 ——
  // 表现为「列表全空但没有报错」这种最难查的假象。两处都写。
  const userJson = buildUserJson(loginData);
  await page.evaluate(([user, tok]) => {
    localStorage.setItem('cretas_user', user);
    localStorage.setItem('cretas_access_token', tok);
  }, [userJson, token]);
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
