/**
 * Pure transform — builds the restaurant KPI看板 view-model from the WS1 Gold
 * reads (kpi-summary + finance-summary + trend-bundle).
 *
 * Why this exists
 * ---------------
 * The old KPI看板 for restaurant tenants queried the 领料/损耗/盘点
 * (requisition/wastage/stocktaking) ops-summary, which is empty for a POS-only
 * restaurant (e.g. 青花椒). This surfaces the GOLD 经营 KPIs the boss actually
 * cares about: 总营收 / 订单数 / 客单价 / 门店数 / 数据天数 / 峰值月.
 *
 * RBAC contract
 * -------------
 * Revenue / 客单价 are monetary → the gold endpoints null them for non
 * price-view roles (`_apply_rbac_strip`). This transform NEVER produces NaN:
 * a null/absent money value yields `value: null` and the renderer shows "—"
 * (or the caller hides money cards entirely for non-price roles).
 *
 * Kept a PURE function (no Vue, no fetch) so it's unit-testable without
 * mounting the component — mirrors goldTrendBundle.ts / menuQuadrantGold.ts.
 */

/** Subset of /gold/kpi-summary we consume (camelCased by pythonFetch). */
export interface KpiSummaryLike {
  revenue?: number | null;
  billCount?: number | null;
  itemCount?: number | null;
  customerCount?: number | null;
  storeCount?: number | null;
  dayCount?: number | null;
  avgBillValue?: number | null;
  avgPerCapita?: number | null;
}

/** Subset of /gold/finance-summary we consume. */
export interface FinanceSummaryLike {
  storeCount?: number | null;
  topStores?: Array<{ storeName?: string | null; revenue?: number | null }> | null;
}

/** Subset of /gold/trend-bundle we consume (for the peak month). */
export interface TrendBundleLike {
  monthlyTrend?: Array<{ month?: string | null; revenue?: number | null }> | null;
}

export type KpiKind = 'money' | 'count' | 'text';

export interface KpiBoardItem {
  key: string;
  label: string;
  /** numeric value, or null when unknown / RBAC-stripped (renderer shows "—"). */
  value: number | null;
  /** text payload for `kind === 'text'` (e.g. 峰值月 "2025-08"). */
  text?: string | null;
  kind: KpiKind;
  /** money cards are hidden for non price-view roles. */
  money: boolean;
  /** optional helper line under the value (e.g. 客单价口径说明). */
  hint?: string;
}

export interface RestaurantKpiBoard {
  /** True when there is ANY non-money signal (orders/stores/days) — used to
   *  decide whether to render the board at all vs an honest empty state. */
  hasData: boolean;
  items: KpiBoardItem[];
  /** 营收最高门店 (name) — null when no store revenue or RBAC-stripped. */
  topStoreName: string | null;
  /** 峰值月 "YYYY-MM" — highest-revenue month, null when unknown/stripped. */
  peakMonth: string | null;
}

function _finiteOrNull(v: number | null | undefined): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

/** Pick the highest-revenue month from the monthly trend (null when none). */
function _peakMonth(bundle: TrendBundleLike | null | undefined): string | null {
  const months = Array.isArray(bundle?.monthlyTrend) ? bundle!.monthlyTrend! : [];
  let best: { month: string; revenue: number } | null = null;
  for (const m of months) {
    const rev = _finiteOrNull(m?.revenue);
    const label = m?.month ? String(m.month) : '';
    // RBAC may null revenue → skip (can't rank an unknown-revenue month).
    if (rev === null || !label) continue;
    if (best === null || rev > best.revenue) best = { month: label, revenue: rev };
  }
  return best ? best.month : null;
}

/**
 * Build the restaurant KPI board view-model.
 *
 * @param kpi        /gold/kpi-summary payload (camelCased) or null
 * @param finance    /gold/finance-summary payload (camelCased) or null
 * @param bundle     /gold/trend-bundle payload (camelCased) or null
 * @param canViewPrice  whether the current role may see monetary KPIs
 */
export function buildRestaurantKpiBoard(
  kpi: KpiSummaryLike | null | undefined,
  finance: FinanceSummaryLike | null | undefined,
  bundle: TrendBundleLike | null | undefined,
  canViewPrice: boolean,
): RestaurantKpiBoard {
  const revenue = _finiteOrNull(kpi?.revenue);
  const billCount = _finiteOrNull(kpi?.billCount);
  const customerCount = _finiteOrNull(kpi?.customerCount);
  const dayCount = _finiteOrNull(kpi?.dayCount);
  // store count: kpi-summary is authoritative; fall back to finance-summary.
  const storeCount = _finiteOrNull(kpi?.storeCount) ?? _finiteOrNull(finance?.storeCount);
  const avgBillValue = _finiteOrNull(kpi?.avgBillValue);

  // top store (by revenue) — null when no store data or revenue RBAC-stripped.
  let topStoreName: string | null = null;
  const stores = Array.isArray(finance?.topStores) ? finance!.topStores! : [];
  if (stores.length > 0) {
    const named = stores.find((s) => s?.storeName);
    topStoreName = named?.storeName ? String(named.storeName) : null;
  }

  const peakMonth = _peakMonth(bundle);

  const items: KpiBoardItem[] = [
    {
      key: 'revenue',
      label: '总营收',
      value: revenue,
      kind: 'money',
      money: true,
      hint: '全部历史累计',
    },
    {
      key: 'billCount',
      label: '订单数',
      value: billCount,
      kind: 'count',
      money: false,
      hint: '全部历史',
    },
    {
      key: 'avgBillValue',
      label: '客单价',
      value: avgBillValue,
      kind: 'money',
      money: true,
      hint: '营收 / 订单数',
    },
    {
      key: 'storeCount',
      label: '门店数',
      value: storeCount,
      kind: 'count',
      money: false,
    },
    {
      key: 'dayCount',
      label: '数据天数',
      value: dayCount,
      kind: 'count',
      money: false,
    },
    {
      key: 'peakMonth',
      label: '峰值月',
      value: null,
      text: peakMonth,
      kind: 'text',
      // peak month is *derived from* revenue ranking → treat as money-sensitive
      // (hide for non price-view roles, where monthly revenue is RBAC-nulled
      // and the ranking can't be computed anyway).
      money: true,
    },
  ];

  // hasData: at least one non-money signal present (orders / stores / days /
  // customers). Money-only signals don't count because non-price roles see
  // them nulled — they must still get a useful board.
  const hasData =
    (billCount ?? 0) > 0 ||
    (storeCount ?? 0) > 0 ||
    (dayCount ?? 0) > 0 ||
    (customerCount ?? 0) > 0;

  // Drop money cards entirely for non price-view roles (avoid a row of "—").
  const visibleItems = canViewPrice ? items : items.filter((i) => !i.money);

  return {
    hasData,
    items: visibleItems,
    topStoreName: canViewPrice ? topStoreName : null,
    peakMonth: canViewPrice ? peakMonth : null,
  };
}
