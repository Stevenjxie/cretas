import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { BOM_OVERLAY_PREFIX, isBomOverlayNode, stripBomOverlay, stripBomOverlayEdges } from '../bomOverlay';

describe('BOM 浮层节点与工艺定义隔离', () => {
  it('浮层节点 id 带固定前缀', () => {
    expect(isBomOverlayNode({ id: `${BOM_OVERLAY_PREFIX}aux:p1` })).toBe(true);
  });

  it('工艺节点不被误判为浮层', () => {
    expect(isBomOverlayNode({ id: 'process-1' })).toBe(false);
    expect(isBomOverlayNode({ id: 'material-7' })).toBe(false);
  });

  it('stripBomOverlay 滤掉浮层, 原样保留工艺节点与顺序', () => {
    const input = [
      { id: 'material-1' },
      { id: `${BOM_OVERLAY_PREFIX}aux:process-1` },
      { id: 'process-1' },
      { id: `${BOM_OVERLAY_PREFIX}pack:out-1` },
      { id: 'out-1' },
    ];
    expect(stripBomOverlay(input).map((n) => n.id)).toEqual(['material-1', 'process-1', 'out-1']);
  });

  it('没有浮层时返回等值数组', () => {
    const input = [{ id: 'a' }, { id: 'b' }];
    expect(stripBomOverlay(input).map((n) => n.id)).toEqual(['a', 'b']);
  });
});

describe('stripBomOverlayEdges 滤掉浮层边(source 或 target 任一端是浮层)', () => {
  it('普通工艺边原样保留', () => {
    const input = [{ id: 'e1', source: 'material-1', target: 'process-1' }];
    expect(stripBomOverlayEdges(input).map((e) => e.id)).toEqual(['e1']);
  });

  it('source 是浮层的边被丢弃(辅料 cell → 工序方向)', () => {
    const input = [{ id: 'e-aux', source: `${BOM_OVERLAY_PREFIX}aux:p1`, target: 'process-1' }];
    expect(stripBomOverlayEdges(input).map((e) => e.id)).toEqual([]);
  });

  it('target 是浮层的边被丢弃(产出 → 包材 cell 方向)', () => {
    const input = [{ id: 'e-pack', source: 'out-1', target: `${BOM_OVERLAY_PREFIX}pack:out-1` }];
    expect(stripBomOverlayEdges(input).map((e) => e.id)).toEqual([]);
  });

  it('保留幸存边的顺序与原对象引用', () => {
    const survivor1 = { id: 'e1', source: 'material-1', target: 'process-1' };
    const overlayEdge = { id: 'e-aux', source: `${BOM_OVERLAY_PREFIX}aux:p1`, target: 'process-1' };
    const survivor2 = { id: 'e2', source: 'process-1', target: 'out-1' };
    const result = stripBomOverlayEdges([survivor1, overlayEdge, survivor2]);
    expect(result).toEqual([survivor1, survivor2]);
    expect(result[0]).toBe(survivor1);
    expect(result[1]).toBe(survivor2);
  });
});

describe('编辑器序列化不带浮层', () => {
  const source = readFileSync(
    resolve(__dirname, '../ProductProcessWorkflowEditor.vue'),
    'utf-8',
  );

  it('序列化工艺定义时先剥离浮层节点', () => {
    // 钉死「nodes: 后面必须经过 stripBomOverlay」, 换成裸 flowNodes 就红
    expect(source).toMatch(/nodes:\s*stripBomOverlay\(flowNodes\.value\)\.map\(serializeFlowNode\)/);
    expect(source).not.toMatch(/nodes:\s*flowNodes\.value\.map\(serializeFlowNode\)/);
  });

  it('序列化工艺定义时先剥离浮层边', () => {
    // 钉死「edges: 后面必须经过 stripBomOverlayEdges」, 换成裸 flowEdges 就红
    expect(source).toMatch(/edges:\s*stripBomOverlayEdges\(flowEdges\.value\)\.map\(serializeFlowEdge\)/);
    expect(source).not.toMatch(/edges:\s*flowEdges\.value\.map\(serializeFlowEdge\)/);
  });
});
