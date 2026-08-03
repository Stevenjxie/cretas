'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { ROUTES } = require('../config/routes');
const { SCENARIOS, resolveScenarios } = require('../core/run-suite');

test('registers the restaurant live command dashboard as a production read-only scenario', () => {
  assert.equal(ROUTES.dashboard, '/dashboard');
  const scenario = SCENARIOS.find((candidate) => candidate.id === 'restaurant-live-command-readonly');
  assert.ok(scenario);
  assert.equal(resolveScenarios(['restaurant-live-command-readonly'])[0].path, ROUTES.dashboard);
  assert.deepEqual(scenario.expectedTransmissionLabels, [
    '今日经营汇总',
    '预订 / POS / 客流',
    '预测 FactBook',
    '大模型解释',
  ]);
});
