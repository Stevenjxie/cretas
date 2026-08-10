import { describe, expect, it } from 'vitest';
import { classifyWorkflowTopology, type WorkflowTopologyNode } from '../workflowClassification';

const raw = (id: string): WorkflowTopologyNode => ({ id, kind: 'RAW_MATERIAL', skuId: id });
const process = (id: string): WorkflowTopologyNode => ({ id, kind: 'PROCESS' });
const finished = (id: string): WorkflowTopologyNode => ({ id, kind: 'FINISHED_GOOD', skuId: id });
const byproduct = (id: string): WorkflowTopologyNode =>
  ({ id, kind: 'FINISHED_GOOD', skuId: id, isByproduct: true });
/** 第二个原料声明自己是第一个的替代料 —— 载体在物料节点上, 与后端同一个字段。 */
const substituteOf = (id: string, mainNodeId: string): WorkflowTopologyNode =>
  ({ id, kind: 'RAW_MATERIAL', skuId: id, substituteOfNodeId: mainNodeId });

describe('Workflow topology classification', () => {
  it('classifies one output as product Workflow regardless of one or many inputs', () => {
    expect(classifyWorkflowTopology(
      [raw('R1'), raw('R2'), process('P'), finished('F1')],
      [{ source: 'R1', target: 'P' }, { source: 'R2', target: 'P' }, { source: 'P', target: 'F1' }],
    ).type).toBe('PRODUCT');
  });

  it('classifies one root and multiple outputs as raw split Workflow', () => {
    expect(classifyWorkflowTopology(
      [raw('R1'), process('P'), finished('F1'), finished('F2')],
      [{ source: 'R1', target: 'P' }, { source: 'P', target: 'F1' }, { source: 'P', target: 'F2' }],
    ).type).toBe('RAW_SPLIT');
  });

  it('classifies multiple roots and multiple outputs as joint production Workflow', () => {
    const result = classifyWorkflowTopology(
      [raw('R1'), raw('R2'), process('P'), finished('F1'), finished('F2')],
      [{ source: 'R1', target: 'P' }, { source: 'R2', target: 'P' }, { source: 'P', target: 'F1' }, { source: 'P', target: 'F2' }],
    );

    expect(result).toMatchObject({ type: 'JOINT_PRODUCTION', rootInputCount: 2, terminalOutputCount: 2 });
  });

  // 🔴 前端是第二份实现。它以前两样都不认(既不合并替代组, 也不过滤副产) ——
  //    于是画布顶部的「系统研判」标签会和后端 WorkflowTopologyClassifier 的结论相反。
  //    这两条用例就是把两份实现钉在同一个口径上。
  it('互为替代的根原料合成一个逻辑投入 —— 与后端 logicalRootCount 同口径', () => {
    const result = classifyWorkflowTopology(
      [raw('R1'), substituteOf('R2', 'R1'), process('P'), finished('F1'), finished('F2')],
      [{ source: 'R1', target: 'P' }, { source: 'R2', target: 'P' },
        { source: 'P', target: 'F1' }, { source: 'P', target: 'F2' }],
    );

    // 同一张图不带替代关系时是 JOINT_PRODUCTION/rootInputCount 2(见上一条用例),
    // 声明替代后两个根合成一个 ⇒ 原料分流。
    expect(result).toMatchObject({ type: 'RAW_SPLIT', rootInputCount: 1, terminalOutputCount: 2 });
  });

  it('副产不计入终端产出 —— 主成品 + 副产是单产出', () => {
    const result = classifyWorkflowTopology(
      [raw('R1'), process('P'), finished('F1'), byproduct('F-BY')],
      [{ source: 'R1', target: 'P' }, { source: 'P', target: 'F1' }, { source: 'P', target: 'F-BY' }],
    );

    expect(result).toMatchObject({ type: 'PRODUCT', terminalOutputCount: 1 });
  });

  it('keeps an unfinished graph unclassified until it has a terminal finished output', () => {
    expect(classifyWorkflowTopology([raw('R1'), process('P')], [{ source: 'R1', target: 'P' }]).type)
      .toBe('INCOMPLETE');
  });
});
