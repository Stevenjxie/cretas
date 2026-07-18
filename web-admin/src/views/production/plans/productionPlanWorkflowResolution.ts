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

  const exact = matching.filter((candidate) => {
    const outputs = workflowCandidateOutputIds(candidate);
    return outputs.length === requested.length && requested.every((id) => outputs.includes(id));
  });
  const smallestOutputCount = matching.length > 0
    ? Math.min(...matching.map((item) => workflowCandidateOutputIds(item).length))
    : Number.POSITIVE_INFINITY;
  const samePriority = exact.length > 0
    ? exact
    : matching.filter(
        (candidate) => workflowCandidateOutputIds(candidate).length === smallestOutputCount,
      );

  return {
    mode: samePriority.length === 0
      ? 'NONE'
      : requested.length === 1 ? 'SINGLE_OUTPUT' : 'SHARED_MULTI_OUTPUT',
    candidates: samePriority,
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

export function workflowCandidateProcessSummary(candidate: WorkflowResolutionCandidate): string {
  const steps = (candidate.processSteps || []).map((step) => step.trim()).filter(Boolean);
  return steps.length > 0 ? steps.join(' → ') : '未配置可识别的中间工序';
}

export function workflowCandidateExtraOutputs(
  candidate: WorkflowResolutionCandidate,
  requestedProductTypeIds: readonly string[],
): string[] {
  const requested = new Set(uniqueIds(requestedProductTypeIds));
  return workflowCandidateOutputIds(candidate).filter((id) => !requested.has(id));
}

export function workflowCandidateTopologyLabel(candidate: WorkflowResolutionCandidate): string {
  if (candidate.workflowType === 'RAW_MATERIAL_SPLIT') return '单逻辑投入 · 多成品分产';
  if (candidate.workflowType === 'JOINT_PRODUCTION') return '多逻辑投入 · 联产';
  return '单成品工序链';
}
