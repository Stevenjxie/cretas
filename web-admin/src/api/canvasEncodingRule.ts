/**
 * Canvas-EncodingRule API client (Phase BCP3 — 2026-05-22).
 *
 * Backend: CanvasEncodingRuleController @ /api/mobile/{factoryId}/canvas-encoding-rule
 *
 * Endpoints:
 *   GET    /                列出工厂级 + 系统级编码规则
 *   GET    /{id}            查询单个
 *   POST   /                创建
 *   PUT    /{id}            修改 (PATCH semantics + AUD-4 P1 optimistic lock)
 *   DELETE /{id}            软删除
 */
import request from './request'

export type ResetCycle = 'DAILY' | 'MONTHLY' | 'YEARLY' | 'NEVER'

export const RESET_CYCLE_LABELS: Record<ResetCycle, string> = {
  DAILY: '每日重置',
  MONTHLY: '每月重置',
  YEARLY: '每年重置',
  NEVER: '不重置',
}

export interface EncodingRule {
  id: string
  factoryId?: string | null
  entityType: string
  ruleName: string
  ruleDescription?: string
  encodingPattern: string
  prefix?: string
  dateFormat?: string
  sequenceLength?: number
  resetCycle?: ResetCycle
  currentSequence?: number
  lastResetDate?: string
  separator?: string
  includeFactoryCode?: boolean
  enabled?: boolean
  version?: number
  optLockVersion?: number
  createdBy?: number
  createdAt?: string
  updatedAt?: string
}

const base = (factoryId: string) => `/${factoryId}/canvas-encoding-rule`

export const canvasEncodingRuleApi = {
  list(factoryId: string) {
    return request.get<EncodingRule[]>(base(factoryId))
  },

  getById(factoryId: string, id: string) {
    return request.get<EncodingRule>(`${base(factoryId)}/${id}`)
  },

  create(factoryId: string, payload: Partial<EncodingRule>) {
    return request.post<EncodingRule>(base(factoryId), payload)
  },

  update(factoryId: string, id: string, payload: Partial<EncodingRule> & { version?: number }) {
    return request.put<EncodingRule>(`${base(factoryId)}/${id}`, payload)
  },

  remove(factoryId: string, id: string) {
    return request.delete<void>(`${base(factoryId)}/${id}`)
  },
}
