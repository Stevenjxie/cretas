'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { redactText, sanitizeUrl, sanitizeValue, summarizePayload } = require('../core/sanitizer');

test('redacts credentials and personal data from text and URLs', () => {
  const jwt = 'eyJabcdefghijklmno.abcdefghijk.abcdefghijk';
  const text = redactText(`Bearer secret-token ${jwt} owner@example.com 13987654321`);
  assert.doesNotMatch(text, /secret-token|eyJ|owner@example|13987654321/);
  assert.match(text, /REDACTED/);

  const url = sanitizeUrl('https://example.test/api?token=abc&factoryId=F006');
  assert.doesNotMatch(url, /token=abc/);
  assert.match(url, /factoryId=F006/);
});

test('summarizes payload shape without retaining arbitrary strings', () => {
  const summary = summarizePayload({ factoryId: 'F006', password: 'secret', supplierName: 'Sensitive Supplier', quantity: 3 });
  assert.equal(summary.fields.factoryId, 'F006');
  assert.equal(summary.fields.password, '[REDACTED]');
  assert.equal(summary.fields.supplierName, '<string:18>');
  assert.equal(summary.fields.quantity, 3);

  const sanitized = sanitizeValue({ accessToken: 'abc', username: 'f006_admin', nested: { email: 'owner@example.com' } });
  assert.equal(sanitized.accessToken, '[REDACTED]');
  assert.equal(sanitized.username, '[REDACTED]');
  assert.equal(sanitizeValue({ displayedUsername: true }).displayedUsername, true);
  assert.equal(sanitized.nested.email, '[REDACTED_EMAIL]');
});
