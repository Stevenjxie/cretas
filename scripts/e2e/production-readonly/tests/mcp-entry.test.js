'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { renderBundle } = require('../build-mcp-entry');

test('MCP filename entry is a single async page function with no top-level execution', () => {
  const source = fs.readFileSync(path.join(__dirname, '..', 'mcp-entry.js'), 'utf8');
  const entry = vm.runInNewContext(`(${source})`);
  assert.equal(typeof entry, 'function');
  assert.equal(entry.constructor.name, 'AsyncFunction');
  assert.match(source, /runSuiteWithPage\(page/);
  assert.equal(source, renderBundle(), 'mcp-entry.js must match the deterministic bundle from canonical core/scenarios');
  assert.doesNotMatch(source, /require\(['"]node:|import\(|\bprocess\.(?:env|cwd)/);
  assert.doesNotMatch(source, /chromium\.launch/);
});
