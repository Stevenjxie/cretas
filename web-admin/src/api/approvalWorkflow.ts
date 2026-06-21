/**
 * Approval Workflow API client — Sprint 3 Track-I (C-APPROVAL-EDITOR-1).
 *
 * Backend: ApprovalWorkflowController @ /api/mobile/{factoryId}/approval-workflows
 * 服务 graph-native 审批工作流配置 (跟 ApprovalChainConfig flat-list 互补).
 *
 * @since 2026-05-16
 */
import request from './request'

// ==================== Wire types ====================

/**
 * DecisionType — Sprint 5 PR #55 H 扩 enum 14→32 + CUSTOM (Round 13 §10 HJ 115+
 * workflow finding). Sprint 6 W3-B (2026-05-19) admin UI 加入 dropdown 全部 32 项,
 * 通过 /decision-types-meta endpoint 拿后端元数据 (中文名/分类/默认审批角色).
 */
export type DecisionType =
  // 生产 / 工序 (6)
  | 'FORCE_INSERT'
  | 'PRODUCTION_PLAN_CHANGE'
  | 'EQUIPMENT_STATUS_CHANGE'
  | 'BOM_VERSION_APPROVAL'
  | 'ECN_APPROVAL'
  | 'WORK_ORDER_APPROVAL'
  // 质检 / 物料 (5)
  | 'QUALITY_RELEASE'
  | 'QUALITY_EXCEPTION'
  | 'BATCH_STATUS_CHANGE'
  | 'MATERIAL_DISPOSAL'
  | 'MATERIAL_REQUISITION_APPROVAL'
  // 采购 / 供应商 (5)
  | 'SUPPLIER_APPROVAL'
  | 'SUPPLIER_STATUS_CHANGE'
  | 'PURCHASE_ORDER_APPROVAL'
  | 'PURCHASE_PAYMENT_APPROVAL'
  | 'PURCHASE_RETURN_APPROVAL'
  // 销售 / 客户 (5)
  | 'SALES_ORDER_APPROVAL'
  | 'SALES_RETURN_APPROVAL'
  | 'SALES_DISCOUNT_APPROVAL'
  | 'CUSTOMER_CREDIT_APPROVAL'
  | 'INVOICE_ISSUANCE_APPROVAL'
  // 财务 / 凭证 (5)
  | 'VOUCHER_APPROVAL'
  | 'EXPENSE_APPROVAL'
  | 'BUDGET_APPROVAL'
  | 'PAYMENT_APPROVAL'
  | 'TAX_FILING_APPROVAL'
  // 人事 / 工资 (4)
  | 'LEAVE_APPROVAL'
  | 'OVERTIME_APPROVAL'
  | 'WAGE_RECORD_APPROVAL'
  | 'HIRE_APPROVAL'
  // 仓储 / 调拨 (3)
  | 'INVENTORY_TRANSFER_APPROVAL'
  | 'INVENTORY_ADJUSTMENT_APPROVAL'
  | 'WASTAGE_APPROVAL'
  // 其他 (1)
  | 'CUSTOM'

/**
 * DecisionType 业务分类 — 后端 DecisionTypeMetadata.Category enum 镜像.
 * Admin UI dropdown 用 category 分组渲染.
 */
export type DecisionTypeCategory =
  | 'PRODUCTION'
  | 'QUALITY_MATERIAL'
  | 'PURCHASE_SUPPLIER'
  | 'SALES_CUSTOMER'
  | 'FINANCE_VOUCHER'
  | 'HR_WAGE'
  | 'WAREHOUSE_TRANSFER'
  | 'OTHER'

/**
 * DecisionType 元数据 — 后端 DecisionTypeMetadata DTO 镜像.
 * Sprint 6 W3-B: 取代 admin UI hardcode 12 个 dropdown 选项的旧方式.
 */
export interface DecisionTypeMetadataDTO {
  decisionType: DecisionType
  chineseName: string
  description: string
  category: DecisionTypeCategory
  defaultApproverRoles: string[]
  /** moduleCode null 表示该 DecisionType 不参与 moduleCode lookup (e.g. CUSTOM) */
  moduleCode: string | null
  /** Sprint 6 W3-B 是否已 ship backend trigger 接入 — false 表 admin 可配置但 service 未调用 */
  wired: boolean
}

export type PublishStatus = 'draft' | 'published' | 'archived'

export type NodeType =
  | 'start'
  | 'approval'
  | 'condition'
  | 'parallel'
  | 'join'
  | 'notify'
  | 'end'

export interface NodePosition {
  x?: number
  y?: number
}

export interface ApprovalWorkflowNode {
  id: string
  type: NodeType
  label?: string
  position?: NodePosition
  config?: Record<string, unknown>
  allowedNextTypes?: string[]
}

export interface ApprovalWorkflowEdge {
  id: string
  source: string
  target: string
  condition?: string
  label?: string
  priority?: number
}

/**
 * Entity wire shape (matches backend ApprovalWorkflow entity).
 * Note: backend stores nodes/edges as JSONB strings; this DTO has
 * them deserialized as arrays for editor convenience.
 */
export interface ApprovalWorkflowDTO {
  id: string
  factoryId: string
  decisionType: DecisionType
  name: string
  description?: string
  nodesJson: string // backend JSONB column raw; editor parses
  edgesJson: string
  startNodeId: string
  version: number
  publishStatus: PublishStatus
  enabled: boolean
  priority: number
  createdAt?: string
  updatedAt?: string
}

export interface CreateWorkflowRequest {
  decisionType: DecisionType
  name: string
  description?: string
  nodes: ApprovalWorkflowNode[]
  edges: ApprovalWorkflowEdge[]
  startNodeId: string
  priority?: number
  enabled?: boolean
}

export interface UpdateWorkflowRequest {
  name?: string
  description?: string
  nodes?: ApprovalWorkflowNode[]
  edges?: ApprovalWorkflowEdge[]
  startNodeId?: string
  priority?: number
  enabled?: boolean
}

export interface ValidateResult {
  valid: boolean
  errors: string[]
  warnings: string[]
}

export interface StatisticsResult {
  data: Record<DecisionType, number>
  totalTypes: number
  totalWorkflows: number
}

export interface ApiResponse<T> {
  success: boolean
  data?: T
  message?: string
  code?: string
}

// ==================== API methods ====================

const base = (factoryId: string) => `/${factoryId}/approval-workflows`

export const getAllWorkflows = (factoryId: string) =>
  request.get<ApiResponse<ApprovalWorkflowDTO[]>>(base(factoryId))

export const getWorkflowsByDecisionType = (factoryId: string, decisionType: DecisionType) =>
  request.get<ApiResponse<ApprovalWorkflowDTO[]>>(`${base(factoryId)}/by-type/${decisionType}`)

export const getWorkflowById = (factoryId: string, id: string) =>
  request.get<ApiResponse<ApprovalWorkflowDTO>>(`${base(factoryId)}/${id}`)

export const createWorkflow = (factoryId: string, payload: CreateWorkflowRequest) =>
  request.post<ApiResponse<ApprovalWorkflowDTO>>(base(factoryId), payload)

export const updateWorkflow = (factoryId: string, id: string, payload: UpdateWorkflowRequest) =>
  request.put<ApiResponse<ApprovalWorkflowDTO>>(`${base(factoryId)}/${id}`, payload)

export const deleteWorkflow = (factoryId: string, id: string) =>
  request.delete<ApiResponse<void>>(`${base(factoryId)}/${id}`)

export const publishWorkflow = (factoryId: string, id: string) =>
  request.patch<ApiResponse<ApprovalWorkflowDTO>>(`${base(factoryId)}/${id}/publish`)

export const archiveWorkflow = (factoryId: string, id: string) =>
  request.patch<ApiResponse<ApprovalWorkflowDTO>>(`${base(factoryId)}/${id}/archive`)

export const toggleWorkflow = (factoryId: string, id: string, enabled: boolean) =>
  request.patch<ApiResponse<ApprovalWorkflowDTO>>(
    `${base(factoryId)}/${id}/toggle?enabled=${enabled}`,
  )

export const validateWorkflow = (factoryId: string, payload: CreateWorkflowRequest) =>
  request.post<ApiResponse<ValidateResult>>(`${base(factoryId)}/validate`, payload)

export const getStatistics = (factoryId: string) =>
  request.get<ApiResponse<StatisticsResult>>(`${base(factoryId)}/statistics`)

export const getDecisionTypes = (factoryId: string) =>
  request.get<ApiResponse<DecisionType[]>>(`${base(factoryId)}/decision-types`)

/**
 * Sprint 6 W3-B (2026-05-19): 取 32 个 DecisionType 完整元数据 (中文名/分类/默认角色).
 * Admin UI dropdown 用此, 取代之前 hardcode 12 个选项.
 */
export const getDecisionTypesMetadata = (factoryId: string) =>
  request.get<ApiResponse<DecisionTypeMetadataDTO[]>>(`${base(factoryId)}/decision-types-meta`)
