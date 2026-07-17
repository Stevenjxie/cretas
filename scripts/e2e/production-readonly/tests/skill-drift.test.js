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

test('project Playwright skill routes direct MCP production runs to the canonical guarded entry', () => {
  const skillPath = path.join(repoRoot, '.agents', 'skills', 'project-playwright-e2e', 'SKILL.md');
  const skill = fs.readFileSync(skillPath, 'utf8');
  assert.match(skill, /browser_run_code_unsafe/);
  assert.match(skill, /scripts\/e2e\/production-readonly\/mcp-entry\.js/);
  assert.match(skill, /before-send mutation guard/);
  assert.match(skill, /Production business writes must be zero/);
  assert.match(skill, /nonprod-business-flow-audit\.mjs/);
  assert.doesNotMatch(skill, /Prefer standalone Node scripts over persistent browser-profile MCP sessions/);
  assert.doesNotMatch(skill, /1\. Read `\.mcp\.json`/);
});
