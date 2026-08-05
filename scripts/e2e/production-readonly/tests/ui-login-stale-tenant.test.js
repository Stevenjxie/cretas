'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { performUiLogin } = require('../core/ui-login');

/**
 * 串租户 canary 此前零测试覆盖 —— 全套 38 条绿灯对它一个字都没说明。
 * 它的语义是「别的租户残留在本次会话的页面上」, 两个方向都必须钉住:
 *   - 登录的是 A, 页面上却出现 B 的痕迹  → 必须抛(这是它存在的理由)
 *   - 登录的就是 B 本人                  → 不得抛(租户名写死时, 这里会误伤合法登录)
 */
function fakePage({ pageText, stored, loginBody }) {
  const locatorStub = {
    first: () => locatorStub,
    waitFor: async () => {},
    fill: async () => {},
    click: async () => {},
    innerText: async () => pageText,
  };
  return {
    locator: () => locatorStub,
    waitForResponse: async () => ({
      request: () => ({}),
      status: () => 200,
      url: () => 'https://example.test/api/mobile/auth/unified-login',
      json: async () => loginBody,
    }),
    waitForURL: async () => {},
    evaluate: async () => stored,
  };
}

test('foreign tenant residue after a clean login still throws', async () => {
  const page = fakePage({
    pageText: '欢迎回来，f006_admin 工厂: F006 测试工厂 —— 残留: liushanmen_admin',
    stored: { username: 'f006_admin', factoryId: 'F006', factoryName: '测试工厂' },
    loginBody: { success: true, data: { username: 'f006_admin', factoryId: 'F006' } },
  });

  await assert.rejects(
    () => performUiLogin(page, {
      username: 'f006_admin',
      password: 'irrelevant',
      expectedUsername: 'f006_admin',
      expectedFactoryId: 'F006',
    }),
    /Stale tenant marker/,
  );
});

test('the marked tenant logging in as itself is not residue', async () => {
  const page = fakePage({
    pageText: '欢迎回来，liushanmen_admin 工厂: LIUSHANMEN 六膳门',
    stored: { username: 'liushanmen_admin', factoryId: 'LIUSHANMEN', factoryName: '六膳门' },
    loginBody: { success: true, data: { username: 'liushanmen_admin', factoryId: 'LIUSHANMEN' } },
  });

  const result = await performUiLogin(page, {
    username: 'liushanmen_admin',
    password: 'irrelevant',
    expectedUsername: 'liushanmen_admin',
    expectedFactoryId: 'LIUSHANMEN',
  });

  assert.equal(result.staleTenantDetected, false);
  assert.equal(result.username, 'liushanmen_admin');
  assert.equal(result.factoryId, 'LIUSHANMEN');
});
