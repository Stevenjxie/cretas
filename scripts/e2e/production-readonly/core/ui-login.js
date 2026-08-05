'use strict';

const { redactText, sanitizeUrl } = require('./sanitizer');

// 已知会串到别人会话里的租户标记。无 g 标志: 带 g 的正则跨次 test() 会保留 lastIndex。
const STALE_TENANT_MARKER = /liushanmen_admin/i;

function compactLoginData(body) {
  const data = body?.data || {};
  const factoryUser = data.factoryUser || {};
  return {
    success: typeof body?.success === 'boolean' ? body.success : null,
    message: redactText(body?.message || '', 120),
    username: data.username || factoryUser.username || null,
    factoryId: data.factoryId || factoryUser.factoryId || null,
    factoryName: data.factoryName || factoryUser.factoryName || null,
    role: data.role || factoryUser.role || null,
  };
}

async function waitForLoginOutcome(page, matchesLogin, timeoutMs = 20_000) {
  try {
    const response = await page.waitForResponse(
      (candidate) => matchesLogin(candidate.request()),
      { timeout: timeoutMs },
    );
    return { type: 'response', response };
  } catch (error) {
    throw new Error(`UI login request was not observed within ${timeoutMs}ms: ${error?.message || error}`);
  }
}

async function performUiLogin(page, options) {
  const username = options.username;
  const password = options.password;
  const expectedUsername = options.expectedUsername || 'f006_admin';
  const expectedFactoryId = options.expectedFactoryId || 'F006';
  if (!username || !password) throw new Error('E2E_USERNAME and E2E_PASSWORD are required for UI login');

  const usernameInput = page.locator('input[placeholder*="用户名"], input[type="text"]').first();
  const passwordInput = page.locator('input[placeholder*="密码"], input[type="password"]').first();
  await usernameInput.waitFor({ state: 'visible', timeout: 15_000 });
  await usernameInput.fill(username);
  await passwordInput.fill(password);

  const matchesLogin = (request) => request.url().includes('/api/mobile/auth/unified-login')
    && request.method() === 'POST';
  const outcomePromise = waitForLoginOutcome(page, matchesLogin);
  const submit = page.locator('button.login-button, button[type="submit"]').first();
  await submit.click();
  const outcome = await outcomePromise;
  if (outcome.type === 'failure') {
    throw new Error(`UI login request failed before response: ${outcome.request.failure()?.errorText || 'unknown network error'}`);
  }
  const response = outcome.response;
  let body = null;
  try { body = await response.json(); } catch { body = null; }
  const login = compactLoginData(body);
  if (response.status() >= 400 || login.success === false) {
    throw new Error(`UI login failed: HTTP ${response.status()} ${login.message || 'success=false'}`);
  }
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 });

  const stored = await page.evaluate(() => {
    try {
      const value = JSON.parse(localStorage.getItem('cretas_user') || '{}');
      const factoryUser = value.factoryUser || {};
      return {
        username: value.username || factoryUser.username || null,
        factoryId: value.factoryId || factoryUser.factoryId || null,
        factoryName: value.factoryName || factoryUser.factoryName || null,
        role: value.role || factoryUser.role || null,
      };
    } catch {
      return {};
    }
  });
  const pageText = await page.locator('body').innerText().catch(() => '');
  const actualUsername = stored.username || login.username;
  const actualFactoryId = stored.factoryId || login.factoryId;
  const actualFactoryName = stored.factoryName || login.factoryName;
  const businessSuccess = login.success ?? Boolean(actualUsername && actualFactoryId);
  // 这道 canary 抓的是「别的租户残留在本次会话的页面上」。租户名写死成字面量时,
  // 一旦该账号自己成为被测账号, 合法登录会被判成泄漏(本条实测拦下过 liushanmen_admin
  // 的正常登录)。先排除「标记就是当前已认证用户」, 跨租户残留的语义不变。
  const staleTenantDetected = !STALE_TENANT_MARKER.test(String(actualUsername || ''))
    && STALE_TENANT_MARKER.test(pageText);
  if (actualUsername !== expectedUsername) throw new Error(`Tenant login username mismatch: expected ${expectedUsername}, got ${actualUsername}`);
  if (actualFactoryId !== expectedFactoryId) throw new Error(`Tenant factory mismatch: expected ${expectedFactoryId}, got ${actualFactoryId}`);
  if (staleTenantDetected) throw new Error('Stale tenant marker liushanmen_admin detected after clean UI login');
  if (actualFactoryName && !pageText.includes(actualFactoryName)) {
    throw new Error('Factory display name does not match the authenticated factory name');
  }

  return {
    method: 'POST',
    url: sanitizeUrl(response.url()),
    status: response.status(),
    success: businessSuccess,
    responseBodyParsed: body !== null,
    username: actualUsername,
    factoryId: actualFactoryId,
    factoryName: actualFactoryName,
    role: stored.role || login.role,
    staleTenantDetected,
  };
}

module.exports = { compactLoginData, performUiLogin, waitForLoginOutcome };
