'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { classifyMutation } = require('../core/mutation-guard');

test('allows only exact authentication and documented query POST contracts', () => {
  assert.equal(classifyMutation('GET', 'https://admin.example/api/mobile/F006/skus').kind, 'safe-method');
  assert.equal(classifyMutation('POST', 'https://admin.example/api/mobile/auth/unified-login').kind, 'auth');
  assert.equal(classifyMutation('POST', 'https://admin.example/api/mobile/F006/list-summary/purchaseOrder').kind, 'readonly-post');
  assert.equal(classifyMutation('POST', 'https://admin.example/api/mobile/F006/ai/chat').kind, 'business-mutation');
  assert.equal(classifyMutation('PUT', 'https://admin.example/api/mobile/F006/skus/1').kind, 'business-mutation');
  assert.equal(classifyMutation('DELETE', 'https://admin.example/api/mobile/F006/boms/276').kind, 'business-mutation');
});

test('does not allow a suffix or prefix near an allowlisted path', () => {
  assert.equal(classifyMutation('POST', 'https://admin.example/api/mobile/F006/list-summary/purchaseOrder/delete').kind, 'business-mutation');
  assert.equal(classifyMutation('POST', 'https://admin.example/api/mobile/F006/not-list-summary/purchaseOrder').kind, 'business-mutation');
});
