/**
 * 餐饮营销员阶梯提成 API client (#59 Phase 2).
 *
 * Backend: RestaurantCommissionController @ /api/mobile/{factoryId}/restaurant/commission
 *
 * - GET                 列表 (status / repId filter, 分页)
 * - GET /summary         某营销员某月累计汇总 (repId + month)
 * - PUT /{id}/mark-paid  标记发放
 *
 * @since 2026-06-04 (feature #59 Phase 2)
 */
import request from './request'

export type CommissionStatus = 'PENDING' | 'PAID' | 'CANCELLED'

export const COMMISSION_STATUS_LABELS: Record<CommissionStatus, string> = {
  PENDING: '待发放',
  PAID: '已发放',
  CANCELLED: '已取消',
}

export interface RestaurantCommission {
  id: string
  factoryId: string
  visitId: string
  repId: number
  ruleId: string
  tierSnapshot?: number | null
  rateSnapshot?: number | null   // @PriceSensitive — 无权限角色被后端剥离为 null
  visitRevenue?: number | null   // @PriceSensitive
  commissionAmount?: number | null // @PriceSensitive
  cumulativeRevenueAtCalc?: number | null // @PriceSensitive
  status: CommissionStatus
  paidAt?: string | null
  createdAt?: string
}

export interface RestaurantRepCommissionSummary {
  id?: string
  factoryId?: string
  repId: number
  month?: string
  periodKey?: string
  hasData?: boolean
  cumulativeRevenue?: number | null // @PriceSensitive
  currentTier?: number | null
  attributedVisitCount: number
}

export interface CommissionListData {
  content: RestaurantCommission[]
  totalElements: number
  totalPages: number
  count: number
}

const base = (factoryId: string) => `/${factoryId}/restaurant/commission`

export function listCommissions(
  factoryId: string,
  params: { status?: CommissionStatus; repId?: number; page?: number; size?: number } = {},
) {
  return request.get<any, { success: boolean; data: CommissionListData }>(
    base(factoryId),
    { params },
  )
}

export function getRepSummary(factoryId: string, repId: number, month?: string) {
  return request.get<any, { success: boolean; data: RestaurantRepCommissionSummary }>(
    `${base(factoryId)}/summary`,
    { params: { repId, month } },
  )
}

export function markCommissionPaid(factoryId: string, id: string) {
  return request.put<any, { success: boolean; data: RestaurantCommission; message: string }>(
    `${base(factoryId)}/${id}/mark-paid`,
  )
}
