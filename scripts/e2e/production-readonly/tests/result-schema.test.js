'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createScenarioResult, validateScenarioResult, assertScenarioResult } = require('../core/result-schema');

test('creates and validates the canonical scenario evidence shape', () => {
  const result = createScenarioResult('tenant-isolation', 'https://admin.example/dashboard');
  result.result = 'PASS';
  assert.deepEqual(validateScenarioResult(result), []);
  assert.equal(assertScenarioResult(result), result);
});

test('rejects unknown status and negative write counts', () => {
  const result = createScenarioResult('unsafe');
  result.result = 'OK';
  result.actualBusinessWrites = -1;
  const errors = validateScenarioResult(result);
  assert.ok(errors.some((error) => error.includes('result must be one of')));
  assert.ok(errors.some((error) => error.includes('actualBusinessWrites')));
});
