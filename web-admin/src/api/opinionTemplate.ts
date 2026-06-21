/**
 * Opinion Template API client — Sprint 4 W2 Chat J (C-OPINION-1).
 *
 * Backend: OpinionTemplateController @ /api/mobile/{factoryId}/opinion-templates
 *
 * 用途: 审批 dialog 常用语 dropdown + admin 自定义模板 CRUD.
 *
 * @since 2026-05-16
 */
import request from './request'

// ==================== Wire types ====================

/** 与 ApprovalChainConfig.DecisionType 同枚举 (字符串字段, 后端 String 储存). */
export type DecisionType =
  | 'FORCE_INSERT'
  | 'QUALITY_RELEASE'
  | 'QUALITY_EXCEPTION'
  | 'BATCH_STATUS_CHANGE'
  | 'SUPPLIER_APPROVAL'
  | 'SUPPLIER_STATUS_CHANGE'
  | 'MATERIAL_DISPOSAL'
  | 'PRODUCTION_PLAN_CHANGE'
  | 'EQUIPMENT_STATUS_CHANGE'
  | 'CUSTOM'

export interface OpinionTemplate {
  id: string
  /** NULL = 系统预设 (全工厂共享); 非 NULL = 工厂自定义. */
  factoryId: string | null
  decisionType: DecisionType
  content: string
  sortOrder: number
  isActive: boolean
  createdAt?: string
  updatedAt?: string
}

export interface CreateOpinionTemplateRequest {
  decisionType: DecisionType
  content: string
  sortOrder?: number
  isActive?: boolean
}

export interface UpdateOpinionTemplateRequest {
  decisionType?: DecisionType
  content?: string
  sortOrder?: number
  isActive?: boolean
}

interface ApiResponse<T> {
  success: boolean
  data: T
  message?: string
  code?: string
}

// ==================== API methods ====================

const base = (factoryId: string) => `/${factoryId}/opinion-templates`

/** 弹框用: 工厂可见模板 (自定义 + 系统预设), 按 sortOrder. */
export const listAvailable = (factoryId: string, decisionType: DecisionType) =>
  request.get<ApiResponse<OpinionTemplate[]>>(
    `${base(factoryId)}/available?decisionType=${encodeURIComponent(decisionType)}`,
  )

/** Admin: 列出某工厂自定义模板. */
export const listByFactory = (factoryId: string) =>
  request.get<ApiResponse<OpinionTemplate[]>>(base(factoryId))

/** Admin: 列出系统预设. */
export const listSystemPresets = (factoryId: string) =>
  request.get<ApiResponse<OpinionTemplate[]>>(`${base(factoryId)}/system-presets`)

export const getById = (factoryId: string, id: string) =>
  request.get<ApiResponse<OpinionTemplate>>(`${base(factoryId)}/${id}`)

export const createTemplate = (factoryId: string, payload: CreateOpinionTemplateRequest) =>
  request.post<ApiResponse<OpinionTemplate>>(base(factoryId), payload)

export const updateTemplate = (
  factoryId: string,
  id: string,
  payload: UpdateOpinionTemplateRequest,
) => request.put<ApiResponse<OpinionTemplate>>(`${base(factoryId)}/${id}`, payload)

export const deleteTemplate = (factoryId: string, id: string) =>
  request.delete<ApiResponse<void>>(`${base(factoryId)}/${id}`)
