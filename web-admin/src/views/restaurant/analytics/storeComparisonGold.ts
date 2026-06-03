/**
 * WS3 Task 2 — pure transform from the WS1 gold `/restaurant-ops/store-comparison`
 * payload to the existing `StoreComparisonData` shape store-comparison.vue renders.
 *
 * Gold endpoint returns (after pythonFetch snake→camel):
 *   { stores: [{ name, revenue, orderCount, avgTicket }], medianRevenue,
 *     weakStores: [name,...] }
 *
 * Legacy `StoreComparisonData` (from CSV path):
 *   { stores: StoreMetrics[{name,revenue,orderCount,avgTicket,discountPct}],
 *     weakStores, medianRevenue }
 *
 * The only gap: gold has NO `discountPct` (折扣率) — the daily-agg gold path
 * doesn't carry per-store discount. We default it to 0 (honest: this path has
 * no discount data; the 折扣率 column shows 0.0% and the "折扣高" status never
 * fires off gold data). RBAC-nulled revenue / avgTicket are coerced to 0 so the
 * table/chart render without NaN (the page is also gated behind canViewPrice).
 *
 * Pure function (no Vue / no fetch) → unit-testable without mounting.
 */
import type { StoreComparisonData, StoreMetrics } from '@/types/restaurant-analytics'

export interface GoldStore {
  name: string
  /** revenue / avgTicket may be null when RBAC strips them for non-price roles. */
  revenue: number | null
  orderCount: number
  avgTicket: number | null
}

export interface GoldStoreComparisonPayload {
  stores: GoldStore[]
  medianRevenue: number | null
  weakStores: string[]
}

/**
 * Convert the gold store-comparison payload into the legacy
 * `StoreComparisonData` shape. Returns null when there are no stores (honest
 * empty — caller shows empty state rather than an empty table/chart).
 */
export function goldStoreComparisonToData(
  payload: GoldStoreComparisonPayload | null | undefined,
): StoreComparisonData | null {
  if (!payload || !Array.isArray(payload.stores) || payload.stores.length === 0) {
    return null
  }

  const stores: StoreMetrics[] = payload.stores.map((s) => ({
    name: s.name,
    revenue: s.revenue ?? 0,
    orderCount: s.orderCount ?? 0,
    avgTicket: s.avgTicket ?? 0,
    discountPct: 0, // gold daily-agg path has no per-store discount data
  }))

  return {
    stores,
    weakStores: Array.isArray(payload.weakStores) ? payload.weakStores : [],
    medianRevenue: payload.medianRevenue ?? 0,
  }
}
