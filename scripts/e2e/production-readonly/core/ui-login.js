'use strict';

const { redactText, sanitizeUrl } = require('./sanitizer');

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

function waitForLoginOutcome(page, matchesLogin, timeoutMs = 20_000) {
  return new Promise((resolve, reject) => {
    const cleanup = () => {
      clearTimeout(timer);
      page.off('response', onResponse);
      page.off('requestfailed', onFailed);
    };
    const onResponse = (response) => {
      if (!matchesLogin(response.request())) return;
      cleanup();
      resolve({ type: 'response', response });
    };
    const onFailed = (request) => {
      if (!matchesLogin(request)) return;
      cleanup();
      resolve({ type: 'failure', request });
    };
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error(`UI login request was not observed within ${timeoutMs}ms`));
    }, timeoutMs);
    page.on('response', onResponse);
    page.on('requestfailed', onFailed);
  });
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
  const staleTenantDetected = /liushanmen_admin/i.test(pageText);
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
