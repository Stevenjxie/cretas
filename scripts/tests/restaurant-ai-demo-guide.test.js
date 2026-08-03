'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const repoRoot = path.resolve(__dirname, '..', '..');
const guidePath = path.join(repoRoot, 'web-admin', 'public', 'restaurant-ai-demo', 'index.html');
const html = fs.readFileSync(guidePath, 'utf8');

test('restaurant AI demo guide contains the complete truthful demo contract', () => {
  const requiredSections = [
    'id="truth"',
    'id="chain"',
    'id="roles"',
    'id="charts"',
    'id="guardrails"',
    'id="demo"',
    'id="boundaries"',
    'id="checklist"',
  ];
  requiredSections.forEach((section) => assert.ok(html.includes(section), `missing ${section}`));

  [
    '60 秒只读自动刷新',
    '分钟级准实时',
    '不是 WebSocket',
    '所有业务数字仍由 FactBook',
    'actual&lt;target ≠ 缺人',
    '预览、精确确认、权限、版本/幂等校验和审计回执',
    '经营汇总、预订/POS/客流、预测 FactBook、按需 LLM',
    '结构示意，不是生产实时数字',
  ].forEach((contract) => assert.ok(html.includes(contract), `missing contract: ${contract}`));
});

test('guide covers every restaurant department and both demo modes', () => {
  ['老板', '店长', '人事', '采购', '厨师长', '市场', '财务'].forEach((role) => {
    assert.ok(html.includes(role), `missing role: ${role}`);
  });
  assert.ok(html.includes('15 分钟完整演示'));
  assert.ok(html.includes('3 分钟亮点演示'));
  assert.equal((html.match(/data-demo-panel=/g) || []).length, 2);
  assert.equal((html.match(/data-panel=/g) || []).length, 7);
});

test('guide exposes real app routes without credentials or fake live values', () => {
  [
    '/login',
    '/dashboard',
    '/restaurant/ops',
    '/restaurant/staffing',
    '/smart-bi/analysis?tab=query',
    '/restaurant/data-completeness',
  ].forEach((route) => assert.ok(html.includes(`href="${route}"`), `missing route: ${route}`));
  assert.doesNotMatch(html, /qhj_prod|f006_admin|RES_3101_009|MOCK_REST/i);
  assert.doesNotMatch(html, /password\s*[=:]|密码\s*[=:]/i);
  assert.ok(html.includes('本页为说明与演示脚本，不承载生产实时数字'));
});

test('inline interaction script parses as JavaScript', () => {
  const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((match) => match[1]);
  assert.equal(scripts.length, 1);
  assert.doesNotThrow(() => new vm.Script(scripts[0]));
});
