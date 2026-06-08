#!/usr/bin/env node
// 测试 ux-advisory.js 的路径检测逻辑
const { spawnSync } = require('child_process');
const path = require('path');
const assert = require('assert');

const SCRIPT = path.join(__dirname, '..', 'ux-advisory.js');

function run(filePath) {
  return spawnSync('node', [SCRIPT], {
    env: {
      ...process.env,
      CLAUDE_TOOL_INPUT: JSON.stringify({ file_path: filePath }),
    },
    encoding: 'utf8',
  });
}

// T1: processing 路径 tsx → 触发 advisory
const t1 = run('C:/Users/Steve/my-prototype-logistics/frontend/CretasFoodTrace/src/screens/processing/YieldStepReportScreen.tsx');
assert(t1.stdout.includes('UX Advisory'), `T1 fail: processing tsx should trigger advisory, got: "${t1.stdout.trim()}"`);
assert.strictEqual(t1.status, 0, 'T1 fail: exit code should be 0');

// T2: warehouse 路径 tsx → 触发 advisory
const t2 = run('frontend/CretasFoodTrace/src/screens/warehouse/inbound/WHInboundDetailScreen.tsx');
assert(t2.stdout.includes('UX Advisory'), `T2 fail: warehouse tsx should trigger advisory`);

// T3: quality-inspector 路径 tsx → 触发 advisory
const t3 = run('frontend/CretasFoodTrace/src/screens/quality-inspector/QIBatchSelectScreen.tsx');
assert(t3.stdout.includes('UX Advisory'), `T3 fail: quality-inspector tsx should trigger advisory`);

// T4: factory-admin 路径 tsx → 静默
const t4 = run('frontend/CretasFoodTrace/src/screens/factory-admin/home/HomeScreen.tsx');
assert.strictEqual(t4.stdout.trim(), '', `T4 fail: factory-admin tsx should be silent, got: "${t4.stdout.trim()}"`);
assert.strictEqual(t4.status, 0, 'T4 fail: exit code should be 0');

// T5: processing 路径但非 tsx → 静默
const t5 = run('frontend/CretasFoodTrace/src/screens/processing/yieldReportApi.ts');
assert.strictEqual(t5.stdout.trim(), '', `T5 fail: .ts file in processing should be silent`);

// T6: 空 file_path → 静默
const t6 = run('');
assert.strictEqual(t6.stdout.trim(), '', `T6 fail: empty path should be silent`);

// T7: CLAUDE_TOOL_INPUT 不含 file_path → 静默
const t7 = spawnSync('node', [SCRIPT], {
  env: { ...process.env, CLAUDE_TOOL_INPUT: JSON.stringify({ old_string: 'x' }) },
  encoding: 'utf8',
});
assert.strictEqual(t7.stdout.trim(), '', `T7 fail: missing file_path should be silent`);

// T8: advisory 输出包含所有 4 大检查类别
const t8 = run('screens/processing/SomeScreen.tsx');
assert(t8.stdout.includes('触摸交互'), 'T8 fail: missing 触摸交互 section');
assert(t8.stdout.includes('上下文显示'), 'T8 fail: missing 上下文显示 section');
assert(t8.stdout.includes('边界防呆'), 'T8 fail: missing 边界防呆 section');
assert(t8.stdout.includes('Dead-end'), 'T8 fail: missing Dead-end section');

console.log('✅ All 8 tests passed');
