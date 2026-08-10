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
 * - 只认完全匹配：图的终端产出集合必须等于所选成品集合（2026-08-10 D6）；
 * - 没有完全匹配就返回 NONE，不再退回「额外联产成品最少」的超集候选；
 * - 完全匹配有多张时全部保留，交给用户按工序链显式选择。
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
    return coversRequested;
  });

  // 🔴 2026-08-10 (D6): 只认精确匹配, 不再退回「额外联产成品最少」的超集候选。
  //
  // 后端写入侧(requireResolutionForAnchor / resolvePinnedPlanOutputContract /
  // assertPinnedWorkflowCoversOutputs)已收紧为相等判定; 这里若保留兜底, 候选列表会把
  // 联产图摆给用户, 点下去才被后端拒 —— 同一条规则两处口径打架。
  //
  // 注意: 后端**发现路径**(resolveForOutputs)仍是包含语义, 那是给 BOM 复制与辅料
  // 工作台用的。本文件服务的是建计划, 用精确口径。
  const samePriority = matching.filter((candidate) => {
    const outputs = workflowCandidateOutputIds(candidate);
    return outputs.length === requested.length && requested.every((id) => outputs.includes(id));
  });

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
  if (candidate.workflowType === 'RAW_MATERIAL_SPLIT') return '1→多 · 单投入多成品';
  if (candidate.workflowType === 'JOINT_PRODUCTION') return '多→多 · 多投入联产';
  if (candidate.workflowType === 'SINGLE_OUTPUT_PRODUCT') {
    if (candidate.logicalRootInputCount != null && candidate.logicalRootInputCount > 1) {
      return '多→1 · 多投入单产出';
    }
    if (candidate.logicalRootInputCount === 1) return '1→1 · 单投入单产出';
    return '单成品工序链 · 投入关系待确认';
  }
  return '工序链拓扑待确认';
}
