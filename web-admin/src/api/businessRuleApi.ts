// web-admin/src/api/businessRuleApi.ts
//
// Canvas-Rules Phase 4a — Business Rule Engine API client.
//
// Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §5
// Backend: backend/.../controller/CanvasRuleController.java
//
// Endpoints (mounted at /api/mobile/{factoryId}/canvas-rules):
//   GET    /                       — list rules (optional scope filter)
//   POST   /                       — create rule
//   PUT    /{id}                   — update rule
//   POST   /{id}/toggle            — enable/disable rule
//   DELETE /{id}                   — soft-delete rule
//   POST   /{id}/test-evaluate     — dry-run rule against sample input
//   GET    /{id}/logs              — paged execution log history
//
import request from './request'
import type { PageResponse } from '@/types/api'

// ---------- Enums ----------

/** Canvas-Rules scope enum, mirrors backend RuleScope.java */
export type RuleScope = 'ORDER' | 'INVENTORY' | 'CUSTOMER' | 'CUSTOM'

/** Canvas-Rules action type enum, mirrors backend RuleActionType.java */
export type RuleActionType = 'LOG' | 'REJECT' | 'MODIFY' | 'TRIGGER_WORKFLOW'

// ---------- Entity shapes ----------

/**
 * Mirror of backend `BusinessRule` entity (camelCase JSON).
 *
 * actionConfigJson payload differs by actionType:
 *  - LOG: { level: 'INFO' | 'WARN' | 'ERROR', message: string }
 *  - REJECT: { reason: string, actionHint?: string, severity?: 'WARNING' | 'BLOCKING' }
 *  - MODIFY: { field: string, value?: unknown, valueSpel?: string }
 *  - TRIGGER_WORKFLOW: { workflowCode: string, ctx?: Record<string, unknown> }
 */
export interface BusinessRule {
  id?: string
  factoryId?: string
  ruleCode: string
  ruleName?: string
  scope: RuleScope
  conditionSpel?: string
  actionType: RuleActionType
  actionConfigJson?: Record<string, unknown>
  priority?: number
  enabled?: boolean
  createdAt?: string
  updatedAt?: string
  deletedAt?: string | null
}

/** Mirror of backend `RuleExecutionLog` entity. */
export interface RuleExecutionLog {
  id?: string
  ruleId: string
  factoryId: string
  triggerEvent?: string
  inputJson?: Record<string, unknown>
  resultJson?: Record<string, unknown>
  executedAt?: string
  createdAt?: string
}

/**
 * Test-evaluate dry-run response (shape inferred from RuleEvaluationResult.java).
 * Sister chat may extend; this matches the records exposed by RuleEngine.evaluate.
 */
export interface RuleEvaluationResult {
  shouldReject: boolean
  rejectReason?: string | null
  rejectRuleCode?: string | null
  rejectActionHint?: string | null
  rejectSeverity?: string | null
  modifications: Array<{
    ruleId?: string
    field?: string
    oldValue?: unknown
    newValue?: unknown
  }>
  executedRules: string[]
}

// ---------- Path helper ----------

const base = (factoryId: string) => `/${factoryId}/canvas-rules`

// ---------- Endpoints ----------

/** GET /{factoryId}/canvas-rules — list rules, optionally filtered by scope. */
export const listBusinessRules = (factoryId: string, scope?: RuleScope) =>
  request.get<BusinessRule[]>(base(factoryId), {
    params: scope ? { scope } : undefined,
  })

/** POST /{factoryId}/canvas-rules — create new rule. */
export const createBusinessRule = (factoryId: string, body: Partial<BusinessRule>) =>
  request.post<BusinessRule>(base(factoryId), body)

/** PUT /{factoryId}/canvas-rules/{id} — update existing rule. */
export const updateBusinessRule = (
  factoryId: string,
  id: string,
  body: Partial<BusinessRule>,
) => request.put<BusinessRule>(`${base(factoryId)}/${id}`, body)

/** DELETE /{factoryId}/canvas-rules/{id} — soft-delete rule. */
export const deleteBusinessRule = (factoryId: string, id: string) =>
  request.delete<void>(`${base(factoryId)}/${id}`)

/** POST /{factoryId}/canvas-rules/{id}/toggle — flip enabled flag. */
export const toggleBusinessRule = (factoryId: string, id: string) =>
  request.post<BusinessRule>(`${base(factoryId)}/${id}/toggle`)

/**
 * POST /{factoryId}/canvas-rules/{id}/test-evaluate — dry-run rule against sample input.
 * Does NOT write logs, NOT mutate, NOT trigger workflows.
 */
export const testEvaluateBusinessRule = (
  factoryId: string,
  id: string,
  sampleInput: Record<string, unknown>,
) => request.post<RuleEvaluationResult>(`${base(factoryId)}/${id}/test-evaluate`, sampleInput)

/** GET /{factoryId}/canvas-rules/{id}/logs — paged execution log history. */
export const listBusinessRuleLogs = (
  factoryId: string,
  id: string,
  page = 0,
  size = 20,
) => request.get<PageResponse<RuleExecutionLog>>(`${base(factoryId)}/${id}/logs`, {
  params: { page, size },
})

// ---------- Convenience constants ----------

/** Display labels for scope enum (used in dropdowns / table columns). */
export const SCOPE_LABELS: Record<RuleScope, string> = {
  ORDER: '订单',
  INVENTORY: '库存',
  CUSTOMER: '客户',
  CUSTOM: '自定义',
}

/** Display labels for action-type enum. */
export const ACTION_TYPE_LABELS: Record<RuleActionType, string> = {
  LOG: '记录日志',
  REJECT: '拒绝操作',
  MODIFY: '修改字段',
  TRIGGER_WORKFLOW: '触发流程',
}
