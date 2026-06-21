/**
 * Workflow Variable Library API client — Sprint 4 W2 Chat J (C-WF-VAR-1).
 *
 * Backend: WorkflowVariableController @ /api/mobile/{factoryId}/workflow-variables
 *
 * @since 2026-05-17
 */
import request from './request'

export type VarType = 'STRING' | 'NUMBER' | 'DECIMAL' | 'BOOLEAN' | 'DATE' | 'DATETIME'
export type VarCategory = 'OWN' | 'ORDER' | 'CUSTOMER' | 'BUSINESS' | 'CUSTOM'

export interface WorkflowVariableDef {
  id: string
  /** NULL = 系统预设 (全工厂共享); 非 NULL = 工厂自定义. */
  factoryId: string | null
  varName: string
  varType: VarType
  category: VarCategory
  description?: string
  sampleValue?: string
  isActive: boolean
  createdAt?: string
  updatedAt?: string
}

export interface TestExpressionRequest {
  spel: string
  sampleContext?: Record<string, unknown>
}

export interface TestExpressionResult {
  success: boolean
  /** 求值成功时携带. */
  result?: unknown
  resultType?: string
  /** 求值失败时携带 R5 友好错误. */
  errorMessage?: string
  actionHint?: string
}

interface ApiResponse<T> {
  success: boolean
  data: T
  message?: string
  code?: string
}

const base = (factoryId: string) => `/${factoryId}/workflow-variables`

/** 编辑器使用: 工厂可见变量 (自定义 + 系统预设). */
export const listAvailableVariables = (factoryId: string) =>
  request.get<ApiResponse<WorkflowVariableDef[]>>(`${base(factoryId)}/available`)

export const listByFactory = (factoryId: string) =>
  request.get<ApiResponse<WorkflowVariableDef[]>>(base(factoryId))

export const listSystemPresets = (factoryId: string) =>
  request.get<ApiResponse<WorkflowVariableDef[]>>(`${base(factoryId)}/system-presets`)

export const getVariableById = (factoryId: string, id: string) =>
  request.get<ApiResponse<WorkflowVariableDef>>(`${base(factoryId)}/${id}`)

export const createVariable = (factoryId: string, payload: Partial<WorkflowVariableDef>) =>
  request.post<ApiResponse<WorkflowVariableDef>>(base(factoryId), payload)

export const updateVariable = (
  factoryId: string,
  id: string,
  payload: Partial<WorkflowVariableDef>,
) => request.put<ApiResponse<WorkflowVariableDef>>(`${base(factoryId)}/${id}`, payload)

export const deleteVariable = (factoryId: string, id: string) =>
  request.delete<ApiResponse<void>>(`${base(factoryId)}/${id}`)

/**
 * R5 友好测试器 — sandbox SpEL 求值, 返结果 / 错误 + actionHint.
 * PropertyPanel "测试表达式" button 调此接口.
 */
export const testExpression = (factoryId: string, req: TestExpressionRequest) =>
  request.post<ApiResponse<TestExpressionResult>>(`${base(factoryId)}/test`, req)
