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

  it('single selection falls back to the smallest multi-output superset', () => {
    const result = resolvePlanWorkflowCandidates(['FG-A'], [
      candidate(2, ['FG-A', 'FG-B']),
      candidate(3, ['FG-A', 'FG-B', 'FG-C']),
    ]);

    expect(result.mode).toBe('SINGLE_OUTPUT');
    expect(result.candidates.map((item) => item.workflowId)).toEqual([2]);
  });

  it('single selection keeps all candidates in the smallest superset layer', () => {
    const result = resolvePlanWorkflowCandidates(['FG-A'], [
      candidate(2, ['FG-A', 'FG-B']),
      candidate(3, ['FG-A', 'FG-B', 'FG-C']),
      candidate(4, ['FG-A', 'FG-D']),
    ]);

    expect(result.candidates.map((item) => item.workflowId)).toEqual([2, 4]);
  });

  it('multiple selections accept one shared multi-output Workflow', () => {
    const result = resolvePlanWorkflowCandidates(['FG-A', 'FG-B'], [
      candidate(1, ['FG-A']),
      candidate(2, ['FG-A', 'FG-B', 'FG-C']),
    ]);

    expect(result.mode).toBe('SHARED_MULTI_OUTPUT');
    expect(result.candidates.map((item) => item.workflowId)).toEqual([2]);
  });

  it('keeps all exact candidates and excludes supersets from the same decision layer', () => {
    const exactA = candidate(2, ['FG-A', 'FG-B']);
    const exactB = candidate(3, ['FG-A', 'FG-B']);
    const superset = candidate(4, ['FG-A', 'FG-B', 'FG-C']);

    expect(resolvePlanWorkflowCandidates(
      ['FG-A', 'FG-B'], [superset, exactA, exactB],
    ).candidates).toEqual([exactA, exactB]);
  });

  it('keeps only the smallest superset layer when no exact graph exists', () => {
    const smallA = candidate(5, ['FG-A', 'FG-B', 'FG-C']);
    const large = candidate(6, ['FG-A', 'FG-B', 'FG-C', 'FG-D']);
    const smallB = candidate(7, ['FG-A', 'FG-B', 'FG-E']);

    expect(resolvePlanWorkflowCandidates(
      ['FG-A', 'FG-B'], [large, smallA, smallB],
    ).candidates).toEqual([smallA, smallB]);
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

  it('does not guess an input topology when an older response lacks the logical count', () => {
    const legacy = candidate(14, ['FG-A']);
    legacy.workflowType = 'SINGLE_OUTPUT_PRODUCT';

    expect(workflowCandidateTopologyLabel(legacy)).toBe('单成品工序链 · 投入关系待确认');
  });
});
