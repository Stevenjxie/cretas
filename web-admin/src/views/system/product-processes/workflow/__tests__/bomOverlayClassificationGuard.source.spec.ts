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
  const block = source.slice(
    source.indexOf('const derivedWorkflowClassification'),
    source.indexOf('const derivedWorkflowClassification') + 700,
  );

  it('研判的节点入参剥离浮层', () => {
    expect(block).toMatch(/stripBomOverlay\(flowNodes\.value\)/);
  });

  it('研判的边入参也剥离浮层 —— 只剥节点不剥边等于没剥', () => {
    expect(block).toMatch(/stripBomOverlayEdges\(flowEdges\.value\)/);
    expect(block).not.toMatch(/[^s]flowEdges\.value\.map\(/);
  });
});
