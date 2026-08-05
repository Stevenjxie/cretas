import { describe, expect, it } from 'vitest';
import {
  BOM_OVERLAY_PREFIX,
  BYP_OVERLAY_SOURCE_HANDLE,
  BYP_OVERLAY_TARGET_HANDLE,
  deriveBomOverlay,
  isBomOverlayNode,
  stripBomOverlay,
  stripBomOverlayEdges,
} from '../bomOverlay';

const finishedNode = {
  id: 'out-1',
  kind: 'FINISHED_GOOD' as const,
  position: { x: 100, y: 200 },
  data: { name: '干式熟成鸡 400g', baseUnit: '袋' },
};

const processNode = {
  id: 'proc-1',
  kind: 'PROCESS' as const,
  position: { x: 0, y: 200 },
  data: { processName: '装箱' },
};

const derive = (byproductByOutput?: Record<string, { rows: never[] } | { rows: unknown[] }>) =>
  deriveBomOverlay({
    workflowNodes: [processNode, finishedNode],
    auxiliaryByProcess: {},
    packagingByOutput: {},
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    byproductByOutput: byproductByOutput as any,
  });

describe('副产 cell 派生', () => {
  it('终端产出派生副产 cell', () => {
    const byp = derive().nodes.find((n) => n.type === 'bomByproduct');
    expect(byp?.id).toBe(`${BOM_OVERLAY_PREFIX}byp:out-1`);
    expect(byp?.data.outputNodeId).toBe('out-1');
  });

  it('没有副产也派生空 cell —— 「没声明」与「不能声明」必须能区分', () => {
    const byp = derive().nodes.find((n) => n.type === 'bomByproduct');
    expect(byp?.data.rows).toEqual([]);
  });

  it('工序节点不派生副产 cell —— 副产是产出声明, 不挂在工序上', () => {
    const attached = derive().edges
      .filter((e) => e.id.includes(':byp:'))
      .map((e) => e.source);
    expect(attached).toEqual(['out-1']);
  });

  it('分母用产出 SKU 的基本单位, 不硬编码', () => {
    const byp = derive().nodes.find((n) => n.type === 'bomByproduct');
    expect(byp?.data.baseUnit).toBe('袋');
  });

  it('边从真实产出指向副产 cell, 两端 handle 都用共享常量', () => {
    const edge = derive().edges.find((e) => e.id.includes(':byp:'));
    expect(edge?.source).toBe('out-1');
    expect(edge?.target).toBe(`${BOM_OVERLAY_PREFIX}byp:out-1`);
    expect(edge?.sourceHandle).toBe(BYP_OVERLAY_SOURCE_HANDLE);
    expect(edge?.targetHandle).toBe(BYP_OVERLAY_TARGET_HANDLE);
  });

  it('副产 cell 与包材 cell 不重叠 —— 一个在下方一个在右侧', () => {
    const { nodes } = derive();
    const byp = nodes.find((n) => n.type === 'bomByproduct');
    const pack = nodes.find((n) => n.type === 'bomPackaging');
    expect(byp?.position).not.toEqual(pack?.position);
  });

  it('副产浮层同样被工艺定义序列化剥离 —— 否则会改写 revision hash', () => {
    expect(isBomOverlayNode({ id: `${BOM_OVERLAY_PREFIX}byp:out-1` })).toBe(true);
    const { nodes, edges } = derive();
    expect(stripBomOverlay(nodes).map((n) => n.id)).toEqual([]);
    expect(stripBomOverlayEdges(edges)).toEqual([]);
  });

  it('不传 byproductByOutput 时按空派生, 不抛错(老调用方兼容)', () => {
    expect(() => deriveBomOverlay({
      workflowNodes: [finishedNode],
      auxiliaryByProcess: {},
      packagingByOutput: {},
    })).not.toThrow();
  });
});
