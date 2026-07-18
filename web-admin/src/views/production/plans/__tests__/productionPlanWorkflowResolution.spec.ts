import { describe, expect, it } from 'vitest';
import type { WorkflowResolutionCandidate } from '@/api/productionPlan';
import {
  resolvePlanWorkflowCandidates,
  workflowCandidateBindingProductTypeId,
  workflowCandidateExtraOutputs,
  workflowCandidateProcessSummary,
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
  it('single selection only accepts a single-output Workflow regardless of input count', () => {
    const result = resolvePlanWorkflowCandidates(['FG-A'], [
      candidate(1, ['FG-A']),
      candidate(2, ['FG-A', 'FG-B']),
    ]);

    expect(result.mode).toBe('SINGLE_OUTPUT');
    expect(result.candidates.map((item) => item.workflowId)).toEqual([1]);
  });

  it('single selection rejects a multi-output Workflow even when it contains the product', () => {
    expect(resolvePlanWorkflowCandidates(['FG-A'], [candidate(2, ['FG-A', 'FG-B'])]))
      .toEqual({ mode: 'NONE', candidates: [] });
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
});
