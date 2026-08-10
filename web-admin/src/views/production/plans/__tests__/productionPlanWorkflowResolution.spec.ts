import { describe, expect, it } from 'vitest';
import type { WorkflowResolutionCandidate } from '@/api/productionPlan';
import {
  resolvePlanWorkflowCandidates,
  workflowCandidateBindingProductTypeId,
  workflowCandidateExtraOutputs,
  workflowCandidateProcessSummary,
  workflowCandidateTopologyLabel,
} from '../productionPlanWorkflowResolution';

function candidate(
  workflowId: number,
  outputs: string[],
  ownerProductTypeId = `OWNER-${workflowId}`,
): WorkflowResolutionCandidate {
  return {
    workflowId,
    definitionVersion: 1,
    ownerProductTypeId,
    plannedUnit: 'kg',
    terminalOutputs: outputs.map((productTypeId) => ({ productTypeId, productName: productTypeId, unit: 'kg' })),
    exactMatch: true,
  };
}

describe('production plan Workflow resolution', () => {
  it('single selection prefers an exact single-output Workflow over a multi-output superset', () => {
    const result = resolvePlanWorkflowCandidates(['FG-A'], [
      candidate(1, ['FG-A']),
      candidate(2, ['FG-A', 'FG-B']),
    ]);

    expect(result.mode).toBe('SINGLE_OUTPUT');
    expect(result.candidates.map((item) => item.workflowId)).toEqual([1]);
  });

  // 以下三条原本断言「没有精确图时退回最小超集」(原名 single selection falls back to the
  // smallest multi-output superset / keeps all candidates in the smallest superset layer /
  // multiple selections accept one shared multi-output Workflow)。2026-08-10 (D6) 建计划侧
  // 改为精确匹配, 超集候选摆出来点下去必被后端拒, 因此断言反向。
  it('single selection no longer falls back to a multi-output superset', () => {
    const result = resolvePlanWorkflowCandidates(['FG-A'], [
      candidate(2, ['FG-A', 'FG-B']),
      candidate(3, ['FG-A', 'FG-B', 'FG-C']),
    ]);

    expect(result.mode).toBe('NONE');
    expect(result.candidates).toEqual([]);
  });

  it('single selection drops the whole smallest superset layer instead of offering it', () => {
    const result = resolvePlanWorkflowCandidates(['FG-A'], [
      candidate(2, ['FG-A', 'FG-B']),
      candidate(3, ['FG-A', 'FG-B', 'FG-C']),
      candidate(4, ['FG-A', 'FG-D']),
    ]);

    expect(result.candidates).toEqual([]);
  });

  it('multiple selections reject a shared Workflow that also produces extra outputs', () => {
    const result = resolvePlanWorkflowCandidates(['FG-A', 'FG-B'], [
      candidate(1, ['FG-A']),
      candidate(2, ['FG-A', 'FG-B', 'FG-C']),
    ]);

    expect(result.mode).toBe('NONE');
    expect(result.candidates).toEqual([]);
  });

  it('multiple selections accept a Workflow whose terminal set equals the selection', () => {
    const exact = candidate(22, ['FG-A', 'FG-B']);
    const result = resolvePlanWorkflowCandidates(['FG-A', 'FG-B'], [candidate(1, ['FG-A']), exact]);

    expect(result.mode).toBe('SHARED_MULTI_OUTPUT');
    expect(result.candidates).toEqual([exact]);
  });

  it('keeps all exact candidates and excludes supersets from the same decision layer', () => {
    const exactA = candidate(2, ['FG-A', 'FG-B']);
    const exactB = candidate(3, ['FG-A', 'FG-B']);
    const superset = candidate(4, ['FG-A', 'FG-B', 'FG-C']);

    expect(resolvePlanWorkflowCandidates(
      ['FG-A', 'FG-B'], [superset, exactA, exactB],
    ).candidates).toEqual([exactA, exactB]);
  });

  // 原名 keeps only the smallest superset layer when no exact graph exists —— 同上, D6 之后
  // 没有精确图就没有候选, 不再挑「最小的那层超集」。
  it('offers nothing when only supersets exist, no matter how small the smallest layer is', () => {
    const smallA = candidate(5, ['FG-A', 'FG-B', 'FG-C']);
    const large = candidate(6, ['FG-A', 'FG-B', 'FG-C', 'FG-D']);
    const smallB = candidate(7, ['FG-A', 'FG-B', 'FG-E']);

    expect(resolvePlanWorkflowCandidates(
      ['FG-A', 'FG-B'], [large, smallA, smallB],
    )).toEqual({ mode: 'NONE', candidates: [] });
  });

  it('multiple selections reject unrelated single-output Workflows', () => {
    expect(resolvePlanWorkflowCandidates(
      ['FG-A', 'FG-B'],
      [candidate(1, ['FG-A']), candidate(2, ['FG-B'])],
    )).toEqual({ mode: 'NONE', candidates: [] });
  });

  it('supports the planned compact DTO aliases without unsafe casts', () => {
    const compact: WorkflowResolutionCandidate = {
      workflowId: 8,
      definitionVersion: 3,
      bindingProductTypeId: 'BIND-8',
      plannedUnit: 'kg',
      outputProductTypeIds: ['FG-A', 'FG-B'],
      exactMatch: true,
    };

    expect(resolvePlanWorkflowCandidates(['FG-A', 'FG-B'], [compact]).candidates).toEqual([compact]);
    expect(workflowCandidateBindingProductTypeId(compact, ['FG-A', 'FG-B'])).toBe('BIND-8');
  });

  it('uses the middle process chain as the candidate identity and exposes extra outputs', () => {
    const item = candidate(9, ['FG-A', 'FG-B', 'FG-C']);
    item.processSteps = ['原料处理', '熟成', '定量包装'];

    expect(workflowCandidateProcessSummary(item)).toBe('原料处理 → 熟成 → 定量包装');
    expect(workflowCandidateExtraOutputs(item, ['FG-A', 'FG-B'])).toEqual(['FG-C']);
  });

  it('preserves the exact Chinese process names returned by the fixed Workflow revision', () => {
    const item = candidate(15, ['5855d1de-07e3-46d0-ae17-89e00413978d']);
    item.processSteps = [
      'SOP-20260723-01-黄油鸡-原料处理',
      'SOP-20260723-01-黄油鸡-定量包装',
    ];

    expect(workflowCandidateProcessSummary(item)).toBe(
      'SOP-20260723-01-黄油鸡-原料处理 → SOP-20260723-01-黄油鸡-定量包装',
    );
    expect(workflowCandidateProcessSummary(item)).not.toContain('???');
  });

  it('labels the four production topologies from logical inputs and terminal outputs', () => {
    const oneToOne = candidate(10, ['FG-A']);
    oneToOne.workflowType = 'SINGLE_OUTPUT_PRODUCT';
    oneToOne.logicalRootInputCount = 1;
    oneToOne.rootInputProductTypeIds = ['RAW-A', 'RAW-B', 'RAW-C', 'RAW-D'];

    const manyToOne = candidate(11, ['FG-A']);
    manyToOne.workflowType = 'SINGLE_OUTPUT_PRODUCT';
    manyToOne.logicalRootInputCount = 2;

    const oneToMany = candidate(12, ['FG-A', 'FG-B']);
    oneToMany.workflowType = 'RAW_MATERIAL_SPLIT';
    oneToMany.logicalRootInputCount = 1;

    const manyToMany = candidate(13, ['FG-A', 'FG-B']);
    manyToMany.workflowType = 'JOINT_PRODUCTION';
    manyToMany.logicalRootInputCount = 2;

    expect(workflowCandidateTopologyLabel(oneToOne)).toBe('1→1 · 单投入单产出');
    expect(workflowCandidateTopologyLabel(manyToOne)).toBe('多→1 · 多投入单产出');
    expect(workflowCandidateTopologyLabel(oneToMany)).toBe('1→多 · 单投入多成品');
    expect(workflowCandidateTopologyLabel(manyToMany)).toBe('多→多 · 多投入联产');
  });

  it('无精确候选时不再退回联产超集 —— 与后端写入侧 D6 同口径', () => {
    const joint = candidate(20, ['FG-1', 'FG-2']);

    const resolution = resolvePlanWorkflowCandidates(['FG-1'], [joint]);

    expect(resolution.mode).toBe('NONE');
    expect(resolution.candidates).toEqual([]);
  });

  it('勾齐联产图的全部成品才给候选', () => {
    const joint = candidate(21, ['FG-1', 'FG-2']);

    const resolution = resolvePlanWorkflowCandidates(['FG-1', 'FG-2'], [joint]);

    expect(resolution.mode).toBe('SHARED_MULTI_OUTPUT');
    expect(resolution.candidates).toEqual([joint]);
  });

  it('does not guess an input topology when an older response lacks the logical count', () => {
    const legacy = candidate(14, ['FG-A']);
    legacy.workflowType = 'SINGLE_OUTPUT_PRODUCT';

    expect(workflowCandidateTopologyLabel(legacy)).toBe('单成品工序链 · 投入关系待确认');
  });
});
