/**
 * 质检处置 (不良品处置/放行) API 客户端
 *
 * Backend: com.cretas.aims.controller.QualityDispositionController
 *   /api/mobile/{factoryId}/quality-disposition/{evaluate,execute,pending,{id}/approve,actions}
 * axios baseURL = '/api/mobile' so caller-side URLs start at "/{factoryId}/...".
 *
 * 重要说明 (读代码后确认的真实行为, 不是假设):
 * - 本 controller 只针对 QualityInspection 记录(与 production_batch_id 关联)做处置
 *   决策评估/执行/审批, 写入 DecisionAuditLog 作审计与审批流转。它 **不会** 直接修改
 *   MaterialBatch / FinishedGoodsBatch 的库存状态 —— 那是 AI Tool
 *   `ReleaseDecisionTool` (ai/tool/impl/workdesk) 的独立职责范围, 与本 controller
 *   无代码关联。UI 文案措辞需诚实反映这一点 (只是"记录决策", 不是"自动挪库存")。
 * - `/apply` 端点与 `/execute` 端点语义有重叠 (`/execute` 内部已按配置的审批链判断
 *   是否需要审批并自动转入 PENDING_APPROVAL) —— 本 UI 只走 `/execute` 作为唯一提交
 *   入口, 不使用 `/apply`, 避免同一决策有两条互相不清楚谁生效的提交路径。
 * - 审批级别 (SUPERVISOR / QUALITY_HEAD / FACTORY_MANAGER / MANAGER) 完全由后端
 *   `getActionApprovalLevel()` 决定, 前端不做二次判断, 只展示。
 */
import { get, post } from './request';

export type DispositionActionCode =
  | 'RELEASE'
  | 'CONDITIONAL_RELEASE'
  | 'REWORK'
  | 'SCRAP'
  | 'SPECIAL_APPROVAL'
  | 'HOLD';

/** GET /quality-disposition/actions — 可用处置动作元数据 (含审批级别/适用条件). */
export interface DispositionActionInfo {
  actionCode: DispositionActionCode;
  actionName: string;
  description: string;
  requiresApproval: boolean;
  approvalLevel?: string | null;
  applicableCondition: string;
}

export interface DispositionEvaluationAlternative {
  action: DispositionActionCode;
  description: string;
  requiresApproval: boolean;
}

export interface DispositionInspectionSummary {
  passRate?: number | null;
  defectRate?: number | null;
  inspectionResult?: string | null;
  qualityGrade?: string | null;
  sampleSize?: number | null;
  passCount?: number | null;
  failCount?: number | null;
}

/** POST /quality-disposition/evaluate 响应. */
export interface DispositionEvaluationDTO {
  inspectionId: string;
  productionBatchId: number;
  recommendedAction: DispositionActionCode;
  recommendedActionDescription: string;
  requiresApproval: boolean;
  triggeredRuleName?: string | null;
  ruleConfigId?: string | null;
  ruleVersion?: number | null;
  confidence?: number | null;
  reason: string;
  alternativeActions: DispositionEvaluationAlternative[];
  inspectionSummary: DispositionInspectionSummary;
}

/**
 * POST /quality-disposition/evaluate 请求体 (QualityCheckResultDTO).
 * 后端实际只用 inspectionId 重新查库评估 (其余字段被 @NotNull/@NotBlank 校验但不参与
 * 计算) —— 仍必须完整传值, 从已加载的质检记录行直接映射即可。
 */
export interface QualityCheckResultRequest {
  inspectionId: string;
  productionBatchId: number;
  inspectorId: number;
  inspectionDate?: string;
  sampleSize: number;
  passCount: number;
  failCount: number;
  passRate?: number | null;
  result?: string | null;
  notes?: string | null;
}

/** POST /quality-disposition/execute 请求体. */
export interface ExecuteDispositionRequest {
  batchId: number;
  inspectionId: string;
  actionCode: DispositionActionCode;
  operatorComment?: string;
  executorId: number;
  approverId?: number;
}

/** POST /quality-disposition/execute 响应. */
export interface DispositionResultDTO {
  dispositionId: string;
  status: 'EXECUTED' | 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED';
  executedAction: DispositionActionCode;
  message: string;
  nextSteps: string;
  newBatchStatus?: string | null;
  approvalInitiated: boolean;
  approvalRequestId?: string | null;
  auditLogId: string;
  executedAt: string;
}

/** GET /quality-disposition/pending 单条记录. */
export interface PendingDispositionDTO {
  id: string;
  entityType: string;
  entityId: string;
  productionBatchId?: number | null;
  batchNumber?: string | null;
  decisionMade: DispositionActionCode | string;
  actionDescription?: string | null;
  reason?: string | null;
  executorId?: number | null;
  executorName?: string | null;
  executorRole?: string | null;
  approvalLevel?: string | null;
  urgency?: string | null;
  ruleConfigName?: string | null;
  createdAt: string;
}

/** POST /quality-disposition/{id}/approve 请求体. */
export interface DispositionApproveRequest {
  approved: boolean;
  approverId: number;
  approverName?: string;
  approverRole?: string;
  comment?: string;
  alternativeAction?: string;
  conditions?: string;
}

/** POST /quality-disposition/{id}/approve 响应. */
export interface DispositionApprovalResultDTO {
  applicationId: string;
  batchId?: number | null;
  approved: boolean;
  approverId: number;
  approverName?: string | null;
  comment?: string | null;
  approvedAt: string;
  executionStatus?: string | null;
  newBatchStatus?: string | null;
  message: string;
}

function basePath(factoryId: string): string {
  return `/${factoryId}/quality-disposition`;
}

/** 获取全部可用处置动作 (RELEASE/CONDITIONAL_RELEASE/REWORK/SCRAP/SPECIAL_APPROVAL/HOLD) 及其元数据. */
export async function getAvailableActions(factoryId: string): Promise<DispositionActionInfo[]> {
  const res = await get<DispositionActionInfo[]>(`${basePath(factoryId)}/actions`);
  return res.success && res.data ? res.data : [];
}

/** 评估质检处置建议 (只读, 不改变任何数据). */
export async function evaluateDisposition(
  factoryId: string,
  payload: QualityCheckResultRequest
): Promise<DispositionEvaluationDTO> {
  const res = await post<DispositionEvaluationDTO>(`${basePath(factoryId)}/evaluate`, payload);
  if (!res.success || !res.data) throw new Error(res.message || '评估处置建议失败');
  return res.data;
}

/** 执行处置动作 (若需审批, 后端自动转 PENDING_APPROVAL 并返回 approvalInitiated=true). */
export async function executeDisposition(
  factoryId: string,
  payload: ExecuteDispositionRequest
): Promise<DispositionResultDTO> {
  const res = await post<DispositionResultDTO>(`${basePath(factoryId)}/execute`, payload);
  if (!res.success || !res.data) throw new Error(res.message || '执行处置失败');
  return res.data;
}

/** 获取待审批处置列表 (可选按处置动作 decisionMade 筛选, 后端新重载不影响不传筛选的旧调用方). */
export async function getPendingDispositions(
  factoryId: string,
  filters?: { decisionMade?: DispositionActionCode | string }
): Promise<PendingDispositionDTO[]> {
  const res = await get<PendingDispositionDTO[]>(`${basePath(factoryId)}/pending`, {
    params: { decisionMade: filters?.decisionMade || undefined },
  });
  return res.success && res.data ? res.data : [];
}

/** 审批(批准/拒绝)一条待处置申请; 批准时后端会自动执行对应处置动作. */
export async function approveDisposition(
  factoryId: string,
  id: string,
  payload: DispositionApproveRequest
): Promise<DispositionApprovalResultDTO> {
  const res = await post<DispositionApprovalResultDTO>(`${basePath(factoryId)}/${id}/approve`, payload);
  if (!res.success || !res.data) throw new Error(res.message || '审批处置失败');
  return res.data;
}
