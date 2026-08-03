'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { waitForLoginOutcome } = require('../core/ui-login');

test('waits for the login response through Playwright without host timer globals', async () => {
  const request = { method: () => 'POST', url: () => 'https://example.test/api/mobile/auth/unified-login' };
  const response = { request: () => request };
  const page = {
    waitForResponse: async (predicate, options) => {
      assert.equal(options.timeout, 1234);
      assert.equal(predicate(response), true);
      return response;
    },
  };
  const outcome = await waitForLoginOutcome(page, (candidate) => candidate === request, 1234);
  assert.deepEqual(outcome, { type: 'response', response });
});
