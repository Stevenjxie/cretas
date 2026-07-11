/**
 * SmartBI Gold Reads API client.
 *
 * Wraps the 5 /api/smartbi/gold/* endpoints exposed by the Python backend
 * (week 4 Phase B v0 of Unified Data Layer v1 spec).
 *
 * NOTE: pythonFetch auto-converts snake_case → camelCase via transformKeys
 * (see common.ts line ~190). So these interfaces describe the CAMELCASE
 * shape the caller actually receives, not the raw Python JSON.
 */
import { pythonFetch, PYTHON_LLM_TIMEOUT_MS } from './common';

export interface DateRangeQuery {
  factoryId: string;
  startDate: string; // YYYY-MM-DD
  endDate: string;
}

/**
 * All-history-capable variant: dates are optional. Omitting both bounds means
 * "all history" (the gold backend's `_parse_range` treats a missing bound as
 * open). Used by KPI看板 / 分析概览 headers which default to the tenant's full
 * data span rather than a fixed window.
 */
export interface OptionalDateRangeQuery {
  factoryId: string;
  startDate?: string; // YYYY-MM-DD; omit = all history
  endDate?: string;
}

export interface FinanceSummary {
  factoryId: string;
  startDate: string;
  endDate: string;
  totalRevenue: number;
  billCount: number;
  avgBillValue: number | null;
  storeCount: number;
  dayCount: number;
  topStores: Array<{
    storeId: number;
    storeName: string;
    revenue: number;
    billCount: number;
  }>;
}

export interface DailyTrend {
  factoryId: string;
  startDate: string;
  endDate: string;
  points: Array<{
    date: string;
    revenue: number;
    billCount: number;
    avgBillValue: number | null;
  }>;
}

export interface TopProducts {
  factoryId: string;
  startMonth: string;
  endMonth: string;
  topProducts: Array<{
    productId: number;
    productName: string;
    qtySold: number;
    revenue: number;
    billCount: number;
    // 数据织网 Sub-Project C Day 24-25 POC: optional cell-level provenance.
    // All four fields are nullable — the backend returns null when
    // field_provenance has no matching row (prod-OFF state, where
    // SMARTBI_ENABLE_PROVENANCE=0). When populated, FE renders
    // <TrustIndicator>; when null, FE shows a muted placeholder.
    confidence?: number | null;
    source?: string | null;
    sourceUploadId?: number | null;
    entityId?: string | null;
    fieldName?: string | null;
  }>;
}

export interface ChannelBreakdown {
  factoryId: string;
  startDate: string;
  endDate: string;
  totalAmount: number;
  channels: Array<{
    channelId: number;
    channelName: string;
    amount: number;
    billCount: number;
    sharePct: number;
  }>;
}

export interface DiscountBreakdown {
  factoryId: string;
  startDate: string;
  endDate: string;
  totalAmount: number;
  discounts: Array<{
    discountId: number;
    discountName: string;
    amount: number;
    billCount: number;
    sharePct: number;
  }>;
}

export interface KpiSummary {
  factoryId: string;
  startDate: string;
  endDate: string;
  revenue: number;
  billCount: number;
  itemCount: number;
  customerCount: number;
  storeCount: number;
  dayCount: number;
  avgBillValue: number | null;
  itemsPerBill: number | null;
  avgPerCapita: number | null;
}

const _q = (args: DateRangeQuery & { topN?: number }): string => {
  const p = new URLSearchParams({
    factory_id: args.factoryId,
    start_date: args.startDate,
    end_date: args.endDate,
  });
  if (args.topN !== undefined) p.set('top_n', String(args.topN));
  return p.toString();
};

/**
 * Query builder that OMITS empty date bounds (all-history). Use for the
 * optional-date endpoints (kpi-summary / finance-summary / trend-bundle) where
 * a missing start/end means "全部历史".
 */
const _qOptional = (args: OptionalDateRangeQuery & { topN?: number; topNStores?: number }): string => {
  const p = new URLSearchParams({ factory_id: args.factoryId });
  if (args.startDate) p.set('start_date', args.startDate);
  if (args.endDate) p.set('end_date', args.endDate);
  if (args.topN !== undefined) p.set('top_n', String(args.topN));
  if (args.topNStores !== undefined) p.set('top_n_stores', String(args.topNStores));
  return p.toString();
};

/** Weekday/weekend average revenue split (from /trend-bundle). */
export interface TrendBundleWeekdayWeekend {
  weekdayAvg: number | null;
  weekendAvg: number | null;
  weekdayDays: number;
  weekendDays: number;
}

/** /api/smartbi/gold/trend-bundle response (camelCased). */
export interface TrendBundle {
  factoryId: string;
  startDate: string | null;
  endDate: string | null;
  dailyTrend: Array<{ date: string; revenue: number | null; billCount: number }>;
  weekdayWeekend: TrendBundleWeekdayWeekend;
  monthlyTrend: Array<{ month: string; revenue: number | null }>;
}

export async function getFinanceSummary(args: OptionalDateRangeQuery & { topNStores?: number }): Promise<FinanceSummary> {
  // Dates optional: omitting both = 全部历史 (gold _parse_range treats a missing
  // bound as open). Existing callers pass concrete dates → unchanged behavior.
  return (await pythonFetch(`/api/smartbi/gold/finance-summary?${_qOptional(args)}`, {
    timeoutMs: PYTHON_LLM_TIMEOUT_MS,
  })) as FinanceSummary;
}

export async function getDailyTrend(args: DateRangeQuery): Promise<DailyTrend> {
  return (await pythonFetch(`/api/smartbi/gold/daily-trend?${_q(args)}`, {
    timeoutMs: PYTHON_LLM_TIMEOUT_MS,
  })) as DailyTrend;
}

export async function getTopProducts(args: DateRangeQuery & { topN?: number }): Promise<TopProducts> {
  return (await pythonFetch(`/api/smartbi/gold/top-products?${_q(args)}`, {
    timeoutMs: PYTHON_LLM_TIMEOUT_MS,
  })) as TopProducts;
}

export async function getChannelBreakdown(args: DateRangeQuery & { topN?: number }): Promise<ChannelBreakdown> {
  return (await pythonFetch(`/api/smartbi/gold/channel-breakdown?${_q(args)}`, {
    timeoutMs: PYTHON_LLM_TIMEOUT_MS,
  })) as ChannelBreakdown;
}

export async function getDiscountBreakdown(args: DateRangeQuery & { topN?: number }): Promise<DiscountBreakdown> {
  return (await pythonFetch(`/api/smartbi/gold/discount-breakdown?${_q(args)}`, {
    timeoutMs: PYTHON_LLM_TIMEOUT_MS,
  })) as DiscountBreakdown;
}

/** 堂食 vs 外卖 revenue split (from /order-type-mix, agg_daily_order_type_meal.order_type). */
export interface OrderTypeMix {
  factoryId: string;
  startDate: string | null;
  endDate: string | null;
  // Nullable: RBAC price-strip (backend smartbi_compat/_rbac_strip.py) nulls
  // any leaf key whose name contains "revenue" for roles outside
  // PRICE_VIEW_ROLES — this also matches "revenue_pct"/"revenue_estimated"
  // (substring match on the field name, not just the raw amount fields), so
  // every revenue-named field here must be treated as nullable.
  totalRevenue: number | null;
  /** true when per-type revenue is missing at the source and was estimated
   * from overall avg ticket × bill_count (禁降级: surfaced honestly, never silent). */
  revenueEstimated: boolean | null;
  estimationNote: string | null;
  orderTypes: Array<{
    orderType: string; // '堂食' | '外卖' | ...
    revenue: number | null;
    billCount: number;
    revenuePct: number | null; // 0-100
    revenueEstimated: boolean | null;
  }>;
}

export async function getOrderTypeMix(args: OptionalDateRangeQuery): Promise<OrderTypeMix> {
  // Dates optional: omit both = 全部历史 (mirrors getKpiSummary/getFinanceSummary).
  return (await pythonFetch(`/api/smartbi/gold/order-type-mix?${_qOptional(args)}`, {
    timeoutMs: PYTHON_LLM_TIMEOUT_MS,
  })) as OrderTypeMix;
}

export async function getKpiSummary(args: OptionalDateRangeQuery): Promise<KpiSummary> {
  // Dates optional: omit both = 全部历史. Existing callers pass concrete dates.
  return (await pythonFetch(`/api/smartbi/gold/kpi-summary?${_qOptional(args)}`, {
    timeoutMs: PYTHON_LLM_TIMEOUT_MS,
  })) as KpiSummary;
}

export async function getTrendBundle(args: OptionalDateRangeQuery): Promise<TrendBundle> {
  // 趋势分析合一 — dailyTrend + weekdayWeekend + monthlyTrend in one round-trip.
  // Dates optional: omit both = 全部历史. Revenue fields RBAC-nulled for
  // non price-view roles.
  return (await pythonFetch(`/api/smartbi/gold/trend-bundle?${_qOptional(args)}`, {
    timeoutMs: PYTHON_LLM_TIMEOUT_MS,
  })) as TrendBundle;
}
