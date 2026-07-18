/**
 * Production Plan API
 * Import/Export and reference data endpoints for production plans
 */
import request from './request'
import { get, post } from './request'
import type { ProductionDocumentTrace } from '@/types/productionDocumentTrace'

/** Download the Excel import template (returns Blob) */
export function downloadImportTemplate(factoryId: string) {
  return request.get(`/${factoryId}/production-plans/import-template`, {
    responseType: 'blob',
  })
}

/** Import production plans from an Excel file */
export function importProductionPlans(factoryId: string, formData: FormData) {
  return post<{
    totalCount: number
    successCount: number
    failureCount: number
    failureDetails?: Array<{ rowNumber: number; reason: string }>
  }>(`/${factoryId}/production-plans/import`, formData)
}

/** Export production plans as Excel (returns Blob) */
export function exportProductionPlans(factoryId: string, params?: Record<string, string>) {
  return request.get(`/${factoryId}/production-plans/export`, {
    params,
    responseType: 'blob',
  })
}

/** Get all production lines for a factory */
export function getProductionLines(factoryId: string, status?: string) {
  return get<Array<{ id: string; name: string; status: string; [key: string]: unknown }>>(`/${factoryId}/scheduling/production-lines`, {
    params: status ? { status } : undefined
  })
}

/** Get supervisors (workshop supervisors) for a factory */
export function getSupervisors(factoryId: string) {
  return get<Array<{ id: number; username: string; realName?: string; [key: string]: unknown }>>(`/${factoryId}/users`, {
    params: { role: 'WORKSHOP_SUPERVISOR' },
  })
}

/**
 * 工厂级"免工序报工默认值" (Fable 审计修复 — 多租户安全, 问题1).
 * 新建计划对话框据此初始化"免工序报工"开关: F006=true (默认两点), 其他工厂=false (默认逐道)。
 */
export function getReportModeDefault(factoryId: string) {
  return get<boolean>(`/${factoryId}/production-plans/report-mode-default`)
}

export interface MaterialAdvisoryItem {
  materialTypeId: string
  materialName: string
  requiredQuantity: number | null
  availableQuantity: number | null
  shortageQuantity: number | null
  unit: string | null
  message: string
}

export interface ProductionPlanMaterialAdvisory {
  planId: string
  planNumber: string
  hasWarning: boolean
  message: string
  warnings: MaterialAdvisoryItem[]
}

export function getMaterialAdvisory(factoryId: string, planId: string) {
  return get<ProductionPlanMaterialAdvisory>(`/${factoryId}/production-plans/${planId}/material-advisory`)
}

export function getProductionDocumentTrace(factoryId: string, planId: string) {
  return get<ProductionDocumentTrace>(`/${factoryId}/production-plans/${planId}/document-trace`)
}

export interface ProductionSettlementStatus {
  settlementId: string
  productionPlanId: string
  planNumber: string
  status: string
  plannedQuantity: number
  actualFinishedQuantity: number
  actualSemiFinishedQuantity: number
  quantityUnit?: string | null
  postingStatus: string
  postingMessage?: string | null
  warehouseReceivedQuantity?: number | null
  warehouseVarianceQuantity?: number | null
  finishedGoodsBatchId?: string | null
  transitLedgerId?: string | null
  warnings?: string[]
}

export interface ProductionWarehouseReceiptRequest {
  idempotencyKey: string
  receivedQuantity: number
  quantityUnit?: string | null
  varianceReason?: string | null
  responsibilitySide?: string | null
  varianceNote?: string | null
}

export interface ProductionWarehouseReceiptResponse {
  settlementId: string
  productionPlanId: string
  planNumber: string
  productionReportedQuantity: number
  warehouseReceivedQuantity: number
  varianceQuantity: number
  toleranceQuantity: number
  quantityUnit: string
  postingStatus: string
  finishedGoodsBatchId?: string | null
  transitLedgerId?: string | null
  message?: string | null
  warnings?: string[]
}

export interface ProductionTransitClearingRequest {
  clearingReason: string
  clearingNote?: string | null
}

export function getProductionSettlement(factoryId: string, planId: string) {
  return get<ProductionSettlementStatus>(`/${factoryId}/production-plans/${planId}/settlement`)
}

export function confirmProductionWarehouseReceipt(
  factoryId: string,
  planId: string,
  data: ProductionWarehouseReceiptRequest,
) {
  return post<ProductionWarehouseReceiptResponse>(`/${factoryId}/production-plans/${planId}/warehouse-receipt`, data)
}

export function clearProductionTransitLedger(
  factoryId: string,
  planId: string,
  data: ProductionTransitClearingRequest,
) {
  return post<ProductionWarehouseReceiptResponse>(`/${factoryId}/production-plans/${planId}/transit-ledger/clear`, data)
}

/** SP2: WIP 半成品可用库存列表 (GET /wip/available) */
export interface WipInventoryItem {
  id: number
  intermediateBatchNo: string
  productTypeId: string
  producedQuantity: number
  consumedQuantity: number
  availableQuantity: number
  unit: string | null
  status: string
  accumulatedCost: number | null
  unitCost: number | null
  batchId: number | null
  createdAt: string | null
}

export function listAvailableWip(factoryId: string) {
  return get<WipInventoryItem[]>(`/${factoryId}/wip/available`)
}

/** SP2: 创建二次加工计划 (POST /processing/secondary-plan) */
export interface CreateSecondaryPlanRequest {
  wipId: number
  quantity: number
  productTypeId: string
  plannedDate?: string
}

export function createSecondaryPlan(factoryId: string, data: CreateSecondaryPlanRequest) {
  return post<Record<string, unknown>>(`/${factoryId}/processing/secondary-plan`, data)
}

/** 中转挂账对账 DTO */
export interface ProductionTransitLedgerItem {
  id: string
  factoryId: string
  settlementId: string
  productionPlanId: string
  planNumber: string
  ledgerType: string
  reportedQuantity: number
  confirmedQuantity: number
  varianceQuantity: number
  toleranceQuantity: number
  quantityUnit: string | null
  varianceReason: string
  responsibilitySide: string
  status: string
  note: string | null
  createdBy: number | null
  createdAt: string | null
  updatedAt: string | null
}

/**
 * 查询工厂全部中转挂账记录 (web-admin 对账页)
 * GET /api/mobile/{factoryId}/production-plans/transit-ledgers
 */
export function listTransitLedgers(factoryId: string, status?: string) {
  return get<ProductionTransitLedgerItem[]>(
    `/${factoryId}/production-plans/transit-ledgers`,
    status ? { params: { status } } : undefined,
  )
}

/** 生产汇总 — 批次层面的统计行 */
export interface ProductionSummaryBatch {
  batchNumber: string
  processOrder: number
  processName: string
  produced: number
  remaining: number
  status: string
  cumulativeYieldRate: number | null
}

/**
 * 生产计划阅读汇总 DTO
 * GET /api/mobile/{factoryId}/production-plans/{planId}/production-summary
 */
export interface ProductionSummaryDTO {
  planId: string
  planNumber: string
  productTypeId: string
  productName: string
  totalRawInput: number
  totalFinishedOutput: number
  /** 成品总重(kg) — 末道行录入 productWeight 的 Σ; 未录时 null */
  totalFinishedWeight: number | null
  remainingSemiFinished: number
  /** 真实总出成率(%) — weight-based; 成品重未录时 null */
  realYieldRate: number | null
  /** 成品重量未录入时的提示文字; realYieldRate 为 null 时填充 */
  yieldNote: string | null
  totalCost: number | null
  priceMasked: boolean
  batches: ProductionSummaryBatch[]
}

/**
 * 查询生产计划阅读汇总 (总投入原料 / 产出 / 剩余半成品折回原料当量 / 真实出成率 / 成本)
 * GET /api/mobile/{factoryId}/production-plans/{planId}/production-summary
 */
export function getProductionSummary(factoryId: string, planId: string) {
  return get<ProductionSummaryDTO>(`/${factoryId}/production-plans/${planId}/production-summary`)
}

/** 生产计划基础 DTO */
export interface ProductionPlanBase {
  id: string
  planNumber: string
  status?: string
  planName?: string
  sourceType?: string
  [key: string]: unknown
}

/**
 * 小结 (存货生产 SAFETY_STOCK 计划专用): 增量入库成品 + 扣料, 计划继续挂起
 * POST /{factoryId}/production-plans/{planId}/interim-settle
 */
export function interimSettle(factoryId: string, planId: string) {
  return post<Record<string, unknown>>(`/${factoryId}/production-plans/${planId}/interim-settle`)
}

/** 撤销小结申请/审批记录。 */
export interface InterimSettleReversalRequest {
  id: string
  factoryId: string
  productionPlanId: string
  sessionSeq: number
  settlementPostedAt: string
  reason: string
  status: 'PENDING_APPROVAL' | 'EXECUTED' | 'REJECTED'
  requestedBy?: number
  requestedAt: string
  approvedBy?: number
  approvedAt?: string
  rejectReason?: string
  executedAt?: string
  affectedBatchNumbers?: string
}

/**
 * 撤销小结-申请 (存货生产 SAFETY_STOCK): 创建撤销申请 (待审批, 零库存副作用)。
 *   1天时间窗内可申请; reason 必填。执行在审批通过时进行。
 * POST /{factoryId}/production-plans/{planId}/interim-settle/reverse
 * @param reason     撤销原因 (必填)
 * @param sessionSeq 指定小结次序 (缺省 = 最近一次)
 */
export function requestReverseInterimSettle(
  factoryId: string, planId: string, reason: string, sessionSeq?: number
) {
  return post<InterimSettleReversalRequest>(
    `/${factoryId}/production-plans/${planId}/interim-settle/reverse`,
    sessionSeq != null ? { reason, sessionSeq } : { reason }
  )
}

/**
 * 撤销小结-审批通过 (STOCKTAKE_APPROVAL_ROLES): 审批 → 内联执行逆转 (下游已消耗仍 loud-fail)。
 * POST /{factoryId}/production-plans/interim-settle-reversal-requests/{requestId}/approve
 */
export function approveReversalRequest(factoryId: string, requestId: string) {
  return post<InterimSettleReversalRequest>(
    `/${factoryId}/production-plans/interim-settle-reversal-requests/${requestId}/approve`
  )
}

/**
 * 撤销小结-驳回 (STOCKTAKE_APPROVAL_ROLES): 关闭申请 (零副作用)。
 * POST /{factoryId}/production-plans/interim-settle-reversal-requests/{requestId}/reject
 */
export function rejectReversalRequest(factoryId: string, requestId: string, reason: string) {
  return post<InterimSettleReversalRequest>(
    `/${factoryId}/production-plans/interim-settle-reversal-requests/${requestId}/reject`,
    { reason }
  )
}

/**
 * 撤销小结-申请列表 (审批中心 + 审计): 工厂级, 可选 status / planId 过滤。
 * GET /{factoryId}/production-plans/interim-settle-reversal-requests
 */
export function listReversalRequests(
  factoryId: string,
  params?: { status?: 'PENDING_APPROVAL' | 'EXECUTED' | 'REJECTED'; planId?: string; page?: number; size?: number }
) {
  return get<{ content: InterimSettleReversalRequest[]; totalElements: number }>(
    `/${factoryId}/production-plans/interim-settle-reversal-requests`,
    { params }
  )
}

/**
 * 停产 (存货生产 SAFETY_STOCK 计划专用): 关闭计划, 不可再小结
 * POST /{factoryId}/production-plans/{planId}/stop-production
 */
export function stopProduction(factoryId: string, planId: string) {
  return post<Record<string, unknown>>(`/${factoryId}/production-plans/${planId}/stop-production`)
}

/** Workflow 终端成品。 */
export interface WorkflowResolutionTerminal {
  productTypeId: string
  productName: string
  unit: string
}

export interface WorkflowResolutionPreviewNode {
  id: string
  kind: 'RAW_MATERIAL' | 'PROCESS' | 'SEMI_FINISHED' | 'FINISHED_GOOD'
  label: string
  unit?: string | null
}

export interface WorkflowResolutionPreviewEdge {
  id?: string | null
  source: string
  target: string
}

/**
 * 工序图解析候选 — 覆盖所选成品集合的 workflow 及其 owner 信息。
 */
export interface WorkflowResolutionCandidate {
  workflowId: number
  definitionVersion: number
  /** 兼容旧 owner-centric DTO；新 DTO 可改用 bindingProductTypeId。 */
  ownerProductTypeId?: string
  ownerProductName?: string
  ownerProductCategory?: string
  ownerUnit?: string
  /** 计划真正绑定的兼容锚点，不代表 Workflow 类型。 */
  bindingProductTypeId?: string
  bindingProductName?: string
  /** 后端按图派生的只读类型。 */
  workflowType?: 'SINGLE_OUTPUT_PRODUCT' | 'RAW_MATERIAL_SPLIT' | 'JOINT_PRODUCTION'
  rootInputProductTypeIds?: string[]
  /** 中间工序的拓扑顺序，用于候选主标题；Workflow 名称只作辅助。 */
  processSteps?: string[]
  /** 只读、安全裁剪后的 Cell 图，不包含公式或可编辑配置。 */
  previewNodes?: WorkflowResolutionPreviewNode[]
  previewEdges?: WorkflowResolutionPreviewEdge[]
  plannedUnit: string | null
  terminalOutputs?: WorkflowResolutionTerminal[]
  /** 兼容拟定的精简 resolve DTO。 */
  outputProductTypeIds?: string[]
  exactMatch: boolean
}

/**
 * POST /product-process-workflows/resolve-by-outputs 响应体。
 * 前端不以 owner 模式决定业务语义，而是复核候选的终端产出集合：
 * 完全匹配优先；没有完全匹配时接受覆盖所选成品的最小产出超集。
 * union 同时兼容现网旧 DTO 与拟定的新 DTO。
 */
export type WorkflowOutputResolutionMode =
  | 'SELF_WORKFLOW'
  | 'RAW_OWNED'
  | 'SINGLE_OUTPUT'
  | 'MULTI_OUTPUT'
  | 'SHARED_MULTI_OUTPUT'
  | 'NONE'

export interface WorkflowOutputResolution {
  requestedProductTypeIds: string[]
  resolutionMode: WorkflowOutputResolutionMode
  message?: string | null
  candidates: WorkflowResolutionCandidate[]
}

/**
 * 按所选「生产成品」集合解析可用的工序图 (Workflow) 候选。
 * POST /{factoryId}/product-process-workflows/resolve-by-outputs
 */
export function resolveWorkflowByOutputs(factoryId: string, productTypeIds: string[]) {
  return post<WorkflowOutputResolution>(
    `/${factoryId}/product-process-workflows/resolve-by-outputs`,
    { productTypeIds }
  )
}
