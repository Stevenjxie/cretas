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
 * 测试账号 —— 仓库里**只写用户名, 不写口令**(CLAUDE.md: 测试凭证不提交代码仓库)。
 *
 * 优先消费仓库既有约定 `.env.test.example` 里的 `TEST_FACTORY_ADMIN_*` /
 * `TEST_WORKSHOP_SUP_*`; `E2E_USER`/`E2E_PASS` 作为一次性覆盖。
 *
 * ⛔ 原默认值 `factory_admin1 / 123456` 是**死值**: 迁移 V20261029_68 把工厂域收敛成
 * 只剩 F006 + LIUSHANMEN, F001 连同 factory_admin1 / workshop_sup1 一并物理删除
 * (`.env.test.example` 开头就记着这件事)。实测生产库 130 个用户里根本没有它,
 * 两个登录端点都返回 401 —— 而整套 spec 一直硬编着它。
 *
 * 没配口令时**不去打登录接口**(空口令只会换来一个 401 噪音), 直接走会话注入:
 * 见 loginOrReuseSession / injectRnSession。配了就走真登录。
 */
export const E2E_USER =
  process.env.E2E_USER || process.env.TEST_FACTORY_ADMIN_USER || 'f006_admin';
export const E2E_PASS =
  process.env.E2E_PASS || process.env.TEST_FACTORY_ADMIN_PASS || '';
/** 第二个角色(车间主管), 用于角色可见性对比用例。 */
export const E2E_USER_2 =
  process.env.E2E_USER_2 || process.env.TEST_WORKSHOP_SUP_USER || E2E_USER;
export const E2E_PASS_2 =
  process.env.E2E_PASS_2 || process.env.TEST_WORKSHOP_SUP_PASS || E2E_PASS;

/** 有没有配口令 —— 没配就别去打登录接口, 直接走注入。 */
export const hasPasswordCredentials = (): boolean => E2E_PASS.length > 0;


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
  username = E2E_USER,
  password = E2E_PASS,
  apiBase = DEFAULT_API,
): Promise<LoginResult> {
  if (!password) {
    throw new Error(
      `未配置 ${username} 的 E2E 口令；请设置 E2E_PASS/TEST_FACTORY_ADMIN_PASS，或使用 loginOrReuseSession 复用受控 storageState`,
    );
  }
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
  username = E2E_USER,
  password = E2E_PASS,
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
  username = E2E_USER,
  password = E2E_PASS,
  apiBase = DEFAULT_API,
  tag = 'e2e',
): Promise<LoginResult> {
  let loginError: unknown;
  if (password) {
    try {
      return await fetchLoginToken(username, password, apiBase);
    } catch (e) {
      loginError = e;
    }
  }

  const token = resolveTokenFromStorageState();
  if (!token) {
    if (loginError instanceof Error) throw loginError;
    throw new Error(
      `未配置 ${username} 的 E2E 口令，且 storageState 中没有可复用 token`,
    );
  }

  try {
    const c = JSON.parse(
      Buffer.from(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf-8'),
    );
    if (!c.factoryId || !c.userId) {
      throw new Error('storageState 里的 token 缺 factoryId/userId, 注进去会让工厂级接口全挂');
    }
    const reason = loginError instanceof Error
      ? `口令登录不可用: ${loginError.message}`
      : '未配置口令';
    console.warn(`[${tag}] ${reason}, 改用 storageState token`);
    return {
      token,
      loginData: {
        userId: c.userId, username: c.username, role: c.role,
        factoryId: c.factoryId, factoryType: 'FACTORY', permissions: ['*:*'],
      },
    };
  } catch (e) {
    throw new Error(`无法复用 storageState token: ${(e as Error).message}`);
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
  // ⚠️ 不能只看 URL: 这个 SPA 是**原地渲染** 403 组件的, 地址栏仍停在原路由。
  // 实测 /system/ai-intents 的失败快照里 heading 就是 "403 / 访问被拒绝",
  // 而 page.url() 依然是 /system/ai-intents —— 只查 URL 的版本永远不触发。
  // 两个信号都认: 跳转到 /403, 或页面上出现 403 组件。
  //
  // ⚠️ 窗口不能是固定的 2s。Promise.race 里每个分支都 .catch(() => false), 所以**最先
  // 到期的那个 false 就赢了** —— 实际窗口是 min(各 timeout)。这个 SPA 从 domcontentloaded
  // 到路由守卫渲染出 403 要好几秒, 2s 版本的 skip 从来没触发过, 表现成 `.el-table` 等满
  // 45s 再失败(2026-08-15 实测 /system/ai-intents 就是这样, 快照里明明写着 403)。
  //
  // 正确的判据是**赛跑**: 403 标记先出现 → skip; 页面正常内容先出现 → 不是 403, 立刻往下走。
  // 这样正常用例不会被拖上固定等待, 而 403 用例有足够长的窗口(30s)显形。
  //
  // ⚠️ 必须有**硬上限**: 既没有 403 标记、也没有业务容器的页面是存在的(404 页就是),
  // 两个分支都等满就把整条用例的预算烧光 —— 我第一版把上限放到 30s, web-admin-e2e
  // 当场从 71 passed 掉到 53 passed。上限 12s: 够 SPA 启动(实测 3-5s), 又不至于噬满预算。
  const BUDGET = 12000;
  // 三个信号里**任意一个**命中即算 403。不用 Promise.any: 它要 ES2021 lib,
  // 而独立 tsc 检查这个文件时会报 TS2550。这里手写等价物 —— 失败的分支永不 resolve,
  // 所以不会像 `.catch(() => false)` 那样让「先到期的失败」赢掉比赛。
  const sawForbidden = new Promise<boolean>((resolve) => {
    [
      page.waitForURL(/\/403/, { timeout: BUDGET }),
      page.getByRole('heading', { name: '403' }).waitFor({ timeout: BUDGET }),
      page.getByText('访问被拒绝').waitFor({ timeout: BUDGET }),
    ].forEach((pr) => { pr.then(() => resolve(true)).catch(() => { /* 这一路没命中 */ }); });
  });
  const gaveUp = new Promise<boolean>((r) => setTimeout(() => r(false), BUDGET));
  // ⚠️ 这里**不能**用 ANY_CONTENT_SELECTOR: 它含 h1/h2/h3, 而 403 页面自己就是
  // `<h1>403</h1><h2>访问被拒绝</h2>` —— 用它当"正常内容"信号, 403 页会自证清白。
  // 只认 403 页上不会出现的容器类元素。
  const sawContent = page.locator('.el-table, .el-card, canvas, .echarts, .el-tabs, .el-descriptions, .el-form')
    .filter({ visible: true }).first()
    .waitFor({ timeout: BUDGET })
    .then(() => false);

  const forbidden = await Promise.race([sawForbidden, sawContent, gaveUp]).catch(() => false);
  if (forbidden || page.url().includes('/403')) {
    test.skip(true, `当前账号无权访问 ${route}(渲染 403/访问被拒绝), 非功能缺陷`);
  }
}

/**
 * 给 RN(Expo web) 注入登录态 —— 口令不可用时的兜底。
 *
 * <p>背景: RN 的两个套件(`rn-expo-web` / `liushanmen-rn-e2e`)都靠 `factory_admin1/123456`
 * 走界面登录。该账号在本环境返回「用户名或密码错误」, 于是**这两个套件一条都跑不起来** ——
 * 而失败长相是「登录页卡住」, 很容易误读成 RN 应用坏了。
 *
 * <p>RN 在 web 上的存储长相(实测, Expo web localStorage):
 * `StorageService.setSecureItem` 在 `Platform.OS === 'web'` 时走 AsyncStorage,
 * 而 AsyncStorage 的 web 后端就是**明文 localStorage**, 键名不加前缀:
 *   `secure_access_token` / `secure_refresh_token` / `secure_token_type` / `secure_token_expiry`
 * 外加 zustand 持久化的 `auth-storage-v3`(partialize: user / tokens / isAuthenticated)。
 *
 * <p>⚠️ 用 `addInitScript` 而不是先 goto 再 evaluate: 后者会先渲染一次未登录的应用,
 * 应用启动时读不到 token 就把自己推去登录页(web-admin 那边踩过同样的坑)。
 */
export async function injectRnSession(
  context: BrowserContext,
  token: string,
  loginData: Record<string, unknown>,
  opts: { refreshToken?: string; role?: string; expiresInSec?: number } = {},
): Promise<void> {
  const role = opts.role || (loginData.role as string) || 'operator';
  const expiresIn = opts.expiresInSec ?? 24 * 3600;
  const user = {
    id: loginData.userId,
    username: loginData.username,
    email: '',
    isActive: true,
    userType: 'factory',
    factoryUser: {
      role,
      factoryId: loginData.factoryId,
      factoryName: loginData.factoryName || '',
      factoryType: loginData.factoryType || 'FACTORY',
      businessDomain: 'FACTORY',
      permissions: loginData.permissions || ['*:*'],
    },
  };
  const tokens = {
    accessToken: token,
    refreshToken: opts.refreshToken || token,
    expiresIn,
    tokenType: 'Bearer',
  };
  const persisted = { state: { user, tokens, isAuthenticated: true }, version: 0 };
  const expiryMs = String(Date.now() + expiresIn * 1000);

  await context.addInitScript(
    ([tok, refresh, expiry, persistedJson]: string[]) => {
      try {
        localStorage.setItem('secure_access_token', tok);
        localStorage.setItem('secure_refresh_token', refresh);
        localStorage.setItem('secure_token_type', 'Bearer');
        localStorage.setItem('secure_token_expiry', expiry);
        localStorage.setItem('auth-storage-v3', persistedJson);
      } catch { /* storage 被禁用时不要打断导航 */ }
    },
    [tokens.accessToken, tokens.refreshToken, expiryMs, JSON.stringify(persisted)],
  );
}
