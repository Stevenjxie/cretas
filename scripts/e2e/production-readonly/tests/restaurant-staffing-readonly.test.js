'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { ROUTES } = require('../config/routes');
const { SCENARIOS, resolveScenarios } = require('../core/run-suite');

test('registers the restaurant staffing page as a production read-only scenario', () => {
  assert.equal(ROUTES.restaurantStaffing, '/restaurant/staffing');
  assert.ok(SCENARIOS.some((scenario) => scenario.id === 'restaurant-staffing-readonly'));
  assert.equal(resolveScenarios(['restaurant-staffing-readonly'])[0].path, ROUTES.restaurantStaffing);
});
