import { describe, expect, it } from 'vitest';
import type { WorkflowResolutionCandidate } from '@/api/productionPlan';
import {
  resolvePlanWorkflowCandidates,
  workflowCandidateBindingProductTypeId,
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
});
