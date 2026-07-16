import type { WorkflowResolutionCandidate } from '@/api/productionPlan';

export type PlanWorkflowResolutionMode = 'SINGLE_OUTPUT' | 'SHARED_MULTI_OUTPUT' | 'NONE';

export interface PlanWorkflowResolution {
  mode: PlanWorkflowResolutionMode;
  candidates: WorkflowResolutionCandidate[];
}

function uniqueIds(ids: readonly string[]): string[] {
  return [...new Set(ids.filter(Boolean))];
}

export function workflowCandidateOutputIds(candidate: WorkflowResolutionCandidate): string[] {
  const terminalIds = (candidate.terminalOutputs || []).map((output) => output.productTypeId);
  return uniqueIds(terminalIds.length > 0 ? terminalIds : (candidate.outputProductTypeIds || []));
}

/**
 * 计划匹配只看已发布 Workflow 的真实终端产出，不看 owner：
 * - 单选必须命中恰好一个终端产出的 Workflow；
 * - 多选必须由同一个多终端 Workflow 同时覆盖。
 */
export function resolvePlanWorkflowCandidates(
  requestedProductTypeIds: readonly string[],
  candidates: readonly WorkflowResolutionCandidate[],
): PlanWorkflowResolution {
  const requested = uniqueIds(requestedProductTypeIds);
  if (requested.length === 0) return { mode: 'NONE', candidates: [] };

  const matching = candidates.filter((candidate) => {
    const outputs = workflowCandidateOutputIds(candidate);
    const coversRequested = requested.every((id) => outputs.includes(id));
    if (!coversRequested) return false;
    return requested.length === 1 ? outputs.length === 1 : outputs.length > 1;
  });

  return {
    mode: matching.length === 0
      ? 'NONE'
      : requested.length === 1 ? 'SINGLE_OUTPUT' : 'SHARED_MULTI_OUTPUT',
    candidates: matching,
  };
}

export function workflowCandidateBindingProductTypeId(
  candidate: WorkflowResolutionCandidate,
  requestedProductTypeIds: readonly string[],
): string | null {
  return candidate.bindingProductTypeId
    || candidate.ownerProductTypeId
    || (requestedProductTypeIds.length === 1 ? requestedProductTypeIds[0] : null);
}

export function workflowCandidateDisplayName(candidate: WorkflowResolutionCandidate): string {
  return candidate.bindingProductName
    || candidate.ownerProductName
    || `Workflow #${candidate.workflowId}`;
}
