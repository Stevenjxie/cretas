'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { ROUTES } = require('../config/routes');
const { SCENARIOS, resolveScenarios } = require('../core/run-suite');

test('registers the restaurant live command dashboard as a production read-only scenario', () => {
  assert.equal(ROUTES.dashboard, '/dashboard');
  assert.ok(SCENARIOS.some((scenario) => scenario.id === 'restaurant-live-command-readonly'));
  assert.equal(resolveScenarios(['restaurant-live-command-readonly'])[0].path, ROUTES.dashboard);
});
