import { describe, expect, it } from 'vitest';
import {
  classifyCanvasTopology,
  terminalOutputLabels,
  type CanvasNodeLike,
} from '../workflowClassification';

/**
 * ⚠️ 这个文件与 workflowClassification.spec.ts 的区别就是这次改动的重点:
 * 那个文件**直接构造** WorkflowTopologyNode(已经是分类器的入参形状), 于是
 * 「分类器认得某个字段 / 画布传不到那个字段」这类断层它一条都照不出 ——
 * 2026-08-10 就是这么全绿着把 isByproduct / substituteOfNodeId 漏在真实路径外的。
 *
 * 这里喂的是**真实画布节点**(Vue Flow 节点, kind/skuId/isByproduct/substituteOfNodeId
 * 都埋在 node.data 里), 走的是 .vue 实际调用的那个函数。
 */

const raw = (id: string, extra: Record<string, unknown> = {}): CanvasNodeLike =>
  ({ id, data: { kind: 'RAW_MATERIAL', skuId: id, name: `${id} 原料`, ...extra } });
const process = (id: string): CanvasNodeLike =>
  ({ id, data: { kind: 'PROCESS', processName: '原料处理' } });
const finished = (id: string, name: string, extra: Record<string, unknown> = {}): CanvasNodeLike =>
  ({ id, data: { kind: 'FINISHED_GOOD', skuId: id, name, ...extra } });

describe('画布节点 → 研判(真实输入路径)', () => {
  it('kind/skuId 从 node.data 里读得到 —— 单产出', () => {
    const result = classifyCanvasTopology(
      [raw('R1'), process('P'), finished('F1', '成品甲')],
      [{ source: 'R1', target: 'P' }, { source: 'P', target: 'F1' }],
    );

    expect(result).toMatchObject({ type: 'PRODUCT', terminalOutputCount: 1 });
    expect(result.terminalOutputSkuIds).toEqual(['F1']);
  });

  it('isByproduct 从真实 node.data 传得到 —— 主成品 + 副产仍是单产出', () => {
    const result = classifyCanvasTopology(
      [raw('R1'), process('P'), finished('F1', '成品甲'), finished('BY', '下脚料', { isByproduct: true })],
      [{ source: 'R1', target: 'P' }, { source: 'P', target: 'F1' }, { source: 'P', target: 'BY' }],
    );

    expect(result.type).toBe('PRODUCT');
    expect(result.terminalOutputSkuIds).toEqual(['F1']);
  });

  it('substituteOfNodeId 从真实 node.data 传得到 —— 两个互替根原料算一个逻辑投入', () => {
    const nodes = [
      raw('R1'), raw('R2', { substituteOfNodeId: 'R1' }), process('P'),
      finished('F1', '成品甲'), finished('F2', '成品乙'),
    ];
    const edges = [
      { source: 'R1', target: 'P' }, { source: 'R2', target: 'P' },
      { source: 'P', target: 'F1' }, { source: 'P', target: 'F2' },
    ];

    expect(classifyCanvasTopology(nodes, edges))
      .toMatchObject({ type: 'RAW_SPLIT', rootInputCount: 1, terminalOutputCount: 2 });

    // 阴性对照: 去掉替代声明, 同一张图必须变成联产 —— 否则上面的绿是「两条路都一样」而不是字段生效。
    const withoutSubstitute = [raw('R1'), raw('R2'), process('P'),
      finished('F1', '成品甲'), finished('F2', '成品乙')];
    expect(classifyCanvasTopology(withoutSubstitute, edges))
      .toMatchObject({ type: 'JOINT_PRODUCTION', rootInputCount: 2 });
  });

  it('终端产出 skuId 升序 —— 与后端 WorkflowTopology(TreeSet) 同口径', () => {
    const result = classifyCanvasTopology(
      [raw('R1'), process('P'), finished('Z-9', '尾货'), finished('A-1', '头货')],
      [{ source: 'R1', target: 'P' }, { source: 'P', target: 'Z-9' }, { source: 'P', target: 'A-1' }],
    );

    expect(result.terminalOutputSkuIds).toEqual(['A-1', 'Z-9']);
  });
});

describe('顶部「本图产出：A、B」的名字', () => {
  it('名字取画布节点上的 data.name, 顺序与 terminalOutputSkuIds 一致', () => {
    const nodes = [raw('R1'), process('P'), finished('Z-9', '尾货'), finished('A-1', '头货')];
    const classification = classifyCanvasTopology(nodes, [
      { source: 'R1', target: 'P' }, { source: 'P', target: 'Z-9' }, { source: 'P', target: 'A-1' },
    ]);

    expect(terminalOutputLabels(nodes, classification)).toEqual(['头货', '尾货']);
  });

  it('副产不出现在「本图产出」里', () => {
    const nodes = [
      raw('R1'), process('P'), finished('F1', '成品甲'),
      finished('BY', '下脚料', { isByproduct: true }),
    ];
    const classification = classifyCanvasTopology(nodes, [
      { source: 'R1', target: 'P' }, { source: 'P', target: 'F1' }, { source: 'P', target: 'BY' },
    ]);

    expect(terminalOutputLabels(nodes, classification)).toEqual(['成品甲']);
  });

  it('名字缺失时退回 skuId, 不显示空字符串', () => {
    const nodes: CanvasNodeLike[] = [
      raw('R1'), process('P'),
      { id: 'F1', data: { kind: 'FINISHED_GOOD', skuId: 'F1', name: '   ' } },
    ];
    const classification = classifyCanvasTopology(nodes, [
      { source: 'R1', target: 'P' }, { source: 'P', target: 'F1' },
    ]);

    expect(terminalOutputLabels(nodes, classification)).toEqual(['F1']);
  });

  it('画布还没有终端产出时给空数组 —— 顶部据此显示「尚未画出终端产出」', () => {
    const nodes = [raw('R1'), process('P')];
    const classification = classifyCanvasTopology(nodes, [{ source: 'R1', target: 'P' }]);

    expect(classification.type).toBe('INCOMPLETE');
    expect(terminalOutputLabels(nodes, classification)).toEqual([]);
  });
});
