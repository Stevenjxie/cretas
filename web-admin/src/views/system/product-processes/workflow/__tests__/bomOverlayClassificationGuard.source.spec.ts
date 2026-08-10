import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const source = readFileSync(
  resolve(__dirname, '../ProductProcessWorkflowEditor.vue'),
  'utf-8',
);

/**
 * 拓扑研判(derivedWorkflowClassification)必须同时剥掉浮层的节点和边。
 *
 * 只剥节点不剥边会出这个错: 包材浮层边是「真实产出 → bom-overlay:pack:x」,
 * 那个真实产出因此进了 outgoing 集合, 而 classifyWorkflowTopology 用
 * `!outgoing.has(node.id)` 判定终端产出 —— 结构完整的工艺会被研判成 INCOMPLETE,
 * 画布上的「系统研判」标签跟着错。
 */
describe('拓扑研判不被 BOM 浮层污染', () => {
  // ⚠️ 截取范围按**语句真实结尾**算, 不能用固定字符数。
  //    原来写的是 `start + 700`: 2026-08-10 给这个 computed 的节点 mapper 加了两个字段
  //    (isByproduct / substituteOfNodeId) 后, `stripBomOverlayEdges(flowEdges.value)`
  //    被挤出 700 字窗口, 这道闸当场判红 —— 红的原因却和它要守的东西毫无关系。
  //    固定字符数只会把同一次失败推迟到下一次改动。
  const start = source.indexOf('const derivedWorkflowClassification');
  const end = source.indexOf('\n));', start);
  const block = source.slice(start, end < 0 ? start : end + 4);

  it('截取到的确实是那条 computed 的完整语句', () => {
    // 没有这条自检, 上面截空/截歪时正例断言会静默失效, 反例断言则可能扫到无关代码。
    expect(start).toBeGreaterThan(-1);
    expect(end).toBeGreaterThan(start);
    expect(block).toMatch(/^const derivedWorkflowClassification = computed\(/);
    expect(block.trimEnd()).toMatch(/\)\);$/);
    expect(block.length).toBeLessThan(2000);
  });

  it('研判的节点入参剥离浮层', () => {
    expect(block).toMatch(/stripBomOverlay\(flowNodes\.value\)/);
  });

  it('研判的边入参也剥离浮层 —— 只剥节点不剥边等于没剥', () => {
    expect(block).toMatch(/stripBomOverlayEdges\(flowEdges\.value\)/);
    expect(block).not.toMatch(/[^s]flowEdges\.value\.map\(/);
  });
});
