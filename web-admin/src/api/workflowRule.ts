/**
 * WorkflowRule API client — Sprint 4 Wave 1 (C-WF-RULE-1).
 *
 * Backend: WorkflowRuleController @ /api/mobile/{factoryId}/workflow-rules
 * 服务 ApprovalWorkflow condition 节点的用户友好规则配置.
 *
 * @since 2026-05-16
 */
import request from './request'

export type WorkflowRuleType =
  | 'AMOUNT_THRESHOLD'
  | 'DEPT_MATCH'
  | 'ROLE_MATCH'
  | 'SPEL_CUSTOM'

export interface AmountThresholdExpr {
  field?: string
  op: '>' | '>=' | '<' | '<=' | '==' | '!='
  value: number | string
}

export interface InListExpr {
  field?: string
  in: string[]
}

export interface SpelExpr {
  spel: string
}

export type WorkflowRuleExpression = AmountThresholdExpr | InListExpr | SpelExpr | Record<string, unknown>

export interface WorkflowRuleDTO {
  id: string
  factoryId: string
  workflowId: string
  nodeId: string
  edgeId?: string | null
  ruleType: WorkflowRuleType
  expression: string // JSONB string from backend
  trueTargetNodeId?: string | null
  falseTargetNodeId?: string | null
  priority: number
  enabled: boolean
  description?: string
  createdAt?: string
  updatedAt?: string
}

export interface WorkflowRuleRequest {
  workflowId: string
  nodeId: string
  edgeId?: string | null
  ruleType: WorkflowRuleType
  expression: WorkflowRuleExpression
  trueTargetNodeId?: string | null
  falseTargetNodeId?: string | null
  priority?: number
  enabled?: boolean
  description?: string
}

export interface RuleTestResult {
  ruleId: string
  ruleType: WorkflowRuleType
  result: boolean
  mockContext: Record<string, unknown>
  expression: string
}

export interface ApiResponse<T> {
  success: boolean
  data?: T
  message?: string
  code?: string
}

const base = (factoryId: string) => `/${factoryId}/workflow-rules`

export const listRulesByWorkflow = (factoryId: string, workflowId: string) =>
  request.get<ApiResponse<WorkflowRuleDTO[]>>(`${base(factoryId)}?workflowId=${workflowId}`)

export const listRulesByNode = (factoryId: string, workflowId: string, nodeId: string) =>
  request.get<ApiResponse<WorkflowRuleDTO[]>>(
    `${base(factoryId)}/by-node?workflowId=${workflowId}&nodeId=${encodeURIComponent(nodeId)}`,
  )

export const getRuleById = (factoryId: string, id: string) =>
  request.get<ApiResponse<WorkflowRuleDTO>>(`${base(factoryId)}/${id}`)

export const createRule = (factoryId: string, payload: WorkflowRuleRequest) =>
  request.post<ApiResponse<WorkflowRuleDTO>>(base(factoryId), payload)

export const updateRule = (factoryId: string, id: string, payload: WorkflowRuleRequest) =>
  request.put<ApiResponse<WorkflowRuleDTO>>(`${base(factoryId)}/${id}`, payload)

export const deleteRule = (factoryId: string, id: string) =>
  request.delete<ApiResponse<void>>(`${base(factoryId)}/${id}`)

export const testRule = (factoryId: string, id: string, mockContext: Record<string, unknown>) =>
  request.post<ApiResponse<RuleTestResult>>(`${base(factoryId)}/${id}/test`, mockContext)

/**
 * Parse expression JSONB string from backend to typed object.
 * Returns empty object on parse fail.
 */
export const parseExpression = (json: string | null | undefined): WorkflowRuleExpression => {
  if (!json) return {} as WorkflowRuleExpression
  try {
    return JSON.parse(json) as WorkflowRuleExpression
  } catch {
    return {} as WorkflowRuleExpression
  }
}

export const RULE_TYPE_LABELS: Record<WorkflowRuleType, string> = {
  AMOUNT_THRESHOLD: '金额阈值',
  DEPT_MATCH: '部门匹配',
  ROLE_MATCH: '角色匹配',
  SPEL_CUSTOM: '自定义 SpEL',
}

export const AMOUNT_OPS: Array<{ value: AmountThresholdExpr['op']; label: string }> = [
  { value: '>', label: '> 大于' },
  { value: '>=', label: '>= 大于等于' },
  { value: '<', label: '< 小于' },
  { value: '<=', label: '<= 小于等于' },
  { value: '==', label: '== 等于' },
  { value: '!=', label: '!= 不等于' },
]
