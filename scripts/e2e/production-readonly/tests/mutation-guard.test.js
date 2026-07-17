'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { installMutationGuard } = require('../core/mutation-guard');

class FakeContext {
  constructor() { this.listeners = new Map(); }
  async route(_pattern, handler) { this.handler = handler; }
  async unroute(_pattern, handler) { assert.equal(handler, this.handler); }
  on(name, handler) { this.listeners.set(name, handler); }
  off(name, handler) { assert.equal(this.listeners.get(name), handler); this.listeners.delete(name); }
}

function fakeRoute(method, url) {
  const request = { method: () => method, url: () => url };
  const calls = [];
  return {
    request,
    calls,
    route: {
      request: () => request,
      continue: async () => calls.push('continue'),
      abort: async (reason) => calls.push(`abort:${reason}`),
    },
  };
}

test('blocks an unexpected mutation before send and records the active scenario', async () => {
  const context = new FakeContext();
  const guard = await installMutationGuard(context, { scenarioRef: { value: 'bom-readonly' } });
  const attempt = fakeRoute('PATCH', 'https://admin.example/api/mobile/F006/boms/276');
  await context.handler(attempt.route);
  assert.deepEqual(attempt.calls, ['abort:blockedbyclient']);
  assert.equal(guard.actualBusinessWrites, 0);
  assert.equal(guard.blockedMutationAttempts.length, 1);
  assert.equal(guard.blockedMutationAttempts[0].scenario, 'bom-readonly');
  assert.equal(guard.blockedMutationAttempts[0].blockedBeforeSend, true);
  await guard.dispose();
});

test('allows UI login and exact read-only query POSTs', async () => {
  const context = new FakeContext();
  const guard = await installMutationGuard(context);
  const login = fakeRoute('POST', 'https://admin.example/api/mobile/auth/unified-login');
  const query = fakeRoute('POST', 'https://admin.example/api/mobile/F006/list-summary/purchaseOrder');
  await context.handler(login.route);
  await context.handler(query.route);
  assert.deepEqual(login.calls, ['continue']);
  assert.deepEqual(query.calls, ['continue']);
  assert.equal(guard.authRequests.length, 1);
  assert.equal(guard.readonlyPostRequests.length, 1);
  assert.equal(guard.blockedMutationAttempts.length, 0);
  await guard.dispose();
});
