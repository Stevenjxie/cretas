'use strict';

const RESULTS = new Set(['PASS', 'CONFIRMED_DEFECT', 'PARTIAL_DEFECT', 'UNVERIFIED', 'TOOL_ERROR']);
const ROOT_CAUSES = new Set(['frontend', 'backend', 'data', 'config', 'tool', 'none']);

function createScenarioResult(scenario, url = '') {
  return {
    scenario,
    result: 'UNVERIFIED',
    url,
    pageEvidence: [],
    apiEvidence: [],
    consoleErrors: [],
    consoleWarnings: [],
    httpErrors: [],
    blockedMutationAttempts: [],
    actualBusinessWrites: 0,
    rootCauseClass: 'none',
    screenshots: [],
    durationMs: 0,
  };
}

function validateScenarioResult(value) {
  const errors = [];
  if (!value || typeof value !== 'object') return ['result must be an object'];
  if (!value.scenario || typeof value.scenario !== 'string') errors.push('scenario must be a non-empty string');
  if (!RESULTS.has(value.result)) errors.push(`result must be one of ${[...RESULTS].join(', ')}`);
  if (typeof value.url !== 'string') errors.push('url must be a string');
  for (const key of ['pageEvidence', 'apiEvidence', 'consoleErrors', 'consoleWarnings', 'httpErrors', 'blockedMutationAttempts', 'screenshots']) {
    if (!Array.isArray(value[key])) errors.push(`${key} must be an array`);
  }
  if (!Number.isInteger(value.actualBusinessWrites) || value.actualBusinessWrites < 0) {
    errors.push('actualBusinessWrites must be a non-negative integer');
  }
  if (!ROOT_CAUSES.has(value.rootCauseClass)) errors.push(`rootCauseClass must be one of ${[...ROOT_CAUSES].join(', ')}`);
  if (!Number.isFinite(value.durationMs) || value.durationMs < 0) errors.push('durationMs must be a non-negative number');
  return errors;
}

function assertScenarioResult(value) {
  const errors = validateScenarioResult(value);
  if (errors.length) throw new Error(`Invalid scenario result: ${errors.join('; ')}`);
  return value;
}

module.exports = { RESULTS, ROOT_CAUSES, createScenarioResult, validateScenarioResult, assertScenarioResult };
