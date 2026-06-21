/**
 * Sales Target Hub API client — Canvas Phase B Tab 2 (销售目标中心).
 *
 * Backend: CanvasSalesTargetController @ /api/mobile/{factoryId}/canvas-sales-target
 *
 * Wraps Sprint 7 T5 CommissionRule + adds:
 *   - Tier ladder config (JSONB list of {minAmount, maxAmount, rate})
 *   - Period config (MONTHLY / QUARTERLY)
 *   - Leaderboard formula hint
 *
 * @since 2026-05-22 (Canvas Phase B)
 */
import request from './request'

// ==================== Types ====================

export type PeriodType = 'MONTHLY' | 'QUARTERLY'

export const PERIOD_TYPE_LABELS: Record<PeriodType, string> = {
  MONTHLY: '按月',
  QUARTERLY: '按季度',
}

export interface CommissionTier {
  minAmount: number
  maxAmount?: number | null
  rate: number
}

export interface CommissionRule {
  id?: string
  factoryId: string
  salesId?: number | null
  customerType?: string | null
  percentage: number
  effectiveFrom: string
  effectiveTo?: string | null
  active: boolean
  createdBy?: number
  createdAt?: string
  updatedAt?: string
  version: number
  tierConfig?: CommissionTier[] | null
  periodType: PeriodType
  leaderboardFormula?: string | null
}

export interface SalesTargetOverview {
  totalRules: number
  activeRules: number
  tieredRules: number
  monthlyRules: number
  quarterlyRules: number
}

export interface CommissionPreview {
  orderAmount: number
  ruleId: string
  commission: number
  rate?: number
  mode: 'FLAT' | 'TIER'
  matchedTier?: CommissionTier | null
  note?: string
}

// ==================== Endpoints ====================

const base = (factoryId: string) => `/${factoryId}/canvas-sales-target`

export function getOverview(factoryId: string) {
  return request.get<any, { success: boolean; data: SalesTargetOverview }>(
    `${base(factoryId)}/overview`,
  )
}

export function listRules(factoryId: string) {
  return request.get<any, { success: boolean; data: CommissionRule[] }>(
    `${base(factoryId)}/rules`,
  )
}

export function getRule(factoryId: string, id: string) {
  return request.get<any, { success: boolean; data: CommissionRule }>(
    `${base(factoryId)}/rules/${id}`,
  )
}

export function createRule(factoryId: string, body: Partial<CommissionRule>) {
  return request.post<any, { success: boolean; data: CommissionRule }>(
    `${base(factoryId)}/rules`, body,
  )
}

export function updateRule(factoryId: string, id: string, body: Partial<CommissionRule>) {
  return request.put<any, { success: boolean; data: CommissionRule }>(
    `${base(factoryId)}/rules/${id}`, body,
  )
}

export function deleteRule(factoryId: string, id: string) {
  return request.delete<any, { success: boolean; data: { id: string; deleted: boolean } }>(
    `${base(factoryId)}/rules/${id}`,
  )
}

export function previewCommission(factoryId: string, id: string, orderAmount: number) {
  return request.post<any, { success: boolean; data: CommissionPreview }>(
    `${base(factoryId)}/rules/${id}/preview`, { orderAmount },
  )
}
