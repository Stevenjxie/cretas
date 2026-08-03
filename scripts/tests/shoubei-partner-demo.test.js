'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..', '..');
const partnerPath = path.join(root, 'web-admin', 'public', 'restaurant-ai-demo', 'shoubei', 'index.html');
const guidePath = path.join(root, 'web-admin', 'public', 'restaurant-ai-demo', 'index.html');
const html = fs.readFileSync(partnerPath, 'utf8');
const guide = fs.readFileSync(guidePath, 'utf8');

test('partner page frames Shoubei and Cretas as complementary layers', () => {
  [
    '不重复收银',
    '上下游能力叠加，不是功能对打',
    '扫呗记录“生意怎么发生”',
    '白垩纪帮助经营者判断“接下来做什么”',
    '材料未呈现”不等于对方没有能力',
    '真实扫呗接口字段、授权与时效仍需双方联调验证',
  ].forEach((contract) => assert.ok(html.includes(contract), `missing contract: ${contract}`));
});

test('page acknowledges Shoubei capabilities and limits Cretas claims to provable value', () => {
  [
    '聚合支付与强支付',
    '会员、CRM 与营销',
    '商品、订单与库存',
    '多门店与多渠道',
    'AI 店长与智能体方向',
    '明天 / 下周 / 下月预测',
    'FactBook 数字来源约束',
    '技能与工时排班建议',
    '预览、确认与审计回执',
  ].forEach((capability) => assert.ok(html.includes(capability), `missing capability: ${capability}`));
  assert.doesNotMatch(html, /扫呗做不到|扫呗没有\s*AI|扫呗没有数据分析|扫呗没有库存|已经接入扫呗|已接通扫呗/i);
});

test('five-minute demo and joint verification checklist are complete', () => {
  assert.equal((html.match(/data-step="/g) || []).length, 6);
  assert.equal((html.match(/data-step-panel="/g) || []).length, 6);
  ['00:00', '00:45', '01:30', '02:40', '03:40', '04:35'].forEach((time) => assert.ok(html.includes(time)));
  ['有哪些聚合数据可以授权', '数据多久更新一次', '如何做租户与隐私隔离', '有没有联调沙箱', '样板门店怎么选', '共同成功指标是什么'].forEach((question) => assert.ok(html.includes(question)));
});

test('public pages link correctly and contain no credentials or fake live values', () => {
  assert.ok(guide.includes('href="/restaurant-ai-demo/shoubei/"'));
  ['/restaurant-ai-demo/', '/dashboard'].forEach((route) => assert.ok(html.includes(`href="${route}"`)));
  assert.doesNotMatch(html, /qhj_prod|f006_admin|RES_3101_009|MOCK_REST|password\s*[=:]|密码\s*[=:]/i);
  assert.ok(html.includes('页面不承载生产实时数字'));
});

test('inline interaction script parses as JavaScript', () => {
  const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((match) => match[1]);
  assert.equal(scripts.length, 1);
  assert.doesNotThrow(() => new vm.Script(scripts[0]));
});
