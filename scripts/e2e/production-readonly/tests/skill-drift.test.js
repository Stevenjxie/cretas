'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const repoRoot = path.resolve(__dirname, '..', '..', '..', '..');

test('canonical skill and compatibility pointer both route production to the shared harness', () => {
  const canonicalPath = path.join(repoRoot, '.agents', 'skills', 'e2e-web-admin', 'SKILL.md');
  const pointerPath = path.join(repoRoot, '.claude', 'skills', 'e2e-web-admin', 'SKILL.md');
  const canonical = fs.readFileSync(canonicalPath, 'utf8');
  const pointer = fs.readFileSync(pointerPath, 'utf8');
  assert.match(canonical, /scripts\/e2e\/production-readonly\/mcp-entry\.js/);
  assert.match(canonical, /actualBusinessWrites.*0/);
  assert.match(pointer, /CANONICAL: \.agents\/skills\/e2e-web-admin\/SKILL\.md/);
  assert.match(pointer, /scripts\/e2e\/production-readonly/);
  assert.doesNotMatch(pointer, /chromium\.launch|CRUD|create\/edit\/delete/i);
});
