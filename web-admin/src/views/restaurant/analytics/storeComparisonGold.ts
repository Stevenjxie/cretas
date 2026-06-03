/**
 * WS3 Task 2 — pure transform from the WS1 gold `/restaurant-ops/store-comparison`
 * payload to the existing `StoreComparisonData` shape store-comparison.vue renders.
 *
 * Gold endpoint returns (after pythonFetch snake→camel):
 *   { stores: [{ name, revenue, orderCount, avgTicket, discountPct }],
 *     medianRevenue, weakStores: [name,...] }
 *
 * Legacy `StoreComparisonData` (from CSV path):
 *   { stores: StoreMetrics[{name,revenue,orderCount,avgTicket,discountPct}],
 *     weakStores, medianRevenue }
 *
 * Follow-up fix (fix/fu-discount): gold now DOES carry per-store `discountPct`
 * (折扣率). agg_daily holds discount_amount + gross_amount at per-store grain,
 * so the store-comparison query computes discountPct = discount/gross*100 per
 * store. We pass it straight through (default 0 only when the payload omits it,
 * e.g. a pre-fix backend that hasn't shipped the field yet). RBAC-nulled
 * revenue / avgTicket are coerced to 0 so the table/chart render without NaN
 * (the page is also gated behind canViewPrice). discountPct is a rate (not an
 * absolute amount) so RBAC leaves it visible.
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
  /** 折扣率 % (discount/gross*100). A rate, not an amount → RBAC leaves it
   *  visible. Optional/null only for a pre-fix backend that omits the field. */
  discountPct?: number | null
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
    // gold store-comparison now computes per-store 折扣率 from agg_daily
    // (discount_amount / gross_amount). Default 0 only if the field is absent
    // (pre-fix backend).
    discountPct: s.discountPct ?? 0,
  }))

  return {
    stores,
    weakStores: Array.isArray(payload.weakStores) ? payload.weakStores : [],
    medianRevenue: payload.medianRevenue ?? 0,
  }
}
