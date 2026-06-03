/**
 * WS4b #12 — pure transform from the WS1 gold `/api/smartbi/gold/trend-bundle`
 * payload to the view-model the 趋势分析 page renders (revenue trend line +
 * weekday/weekend comparison).
 *
 * The gold endpoint returns (after pythonFetch snake→camel):
 *   {
 *     dailyTrend:   [{ date, revenue, billCount }],
 *     weekdayWeekend: { weekdayAvg, weekendAvg, weekdayDays, weekendDays },
 *     monthlyTrend: [{ month, revenue }],
 *   }
 *
 * For the main trend visualization we prefer the MONTHLY series (less noisy
 * across long history); when no monthly points exist we fall back to the DAILY
 * series. The weekday-vs-weekend block is rendered as a 2-bar comparison.
 *
 * Keeping this a PURE function (no Vue, no fetch) so it's unit-testable without
 * mounting the component, matching the menuQuadrantGold.ts / resolveDishesTab.ts
 * test style.
 *
 * Honest-empty contract: returns null when there is no usable revenue data
 * (no monthly AND no daily points) — the caller then shows an empty state
 * rather than an all-zero chart.
 */

/** Raw monthly point as returned by /gold/trend-bundle (camelCased). */
export interface GoldMonthlyPoint {
  month: string;
  /** revenue may be null when RBAC strips it for non-price-view roles. */
  revenue: number | null;
}

/** Raw daily point as returned by /gold/trend-bundle (camelCased). */
export interface GoldDailyPoint {
  date: string;
  revenue: number | null;
  billCount?: number | null;
}

/** Raw weekday/weekend block (camelCased). */
export interface GoldWeekdayWeekend {
  weekdayAvg: number | null;
  weekendAvg: number | null;
  weekdayDays?: number | null;
  weekendDays?: number | null;
}

/** Full trend-bundle payload (camelCased). */
export interface GoldTrendBundlePayload {
  dailyTrend?: GoldDailyPoint[] | null;
  weekdayWeekend?: GoldWeekdayWeekend | null;
  monthlyTrend?: GoldMonthlyPoint[] | null;
}

export type TrendGranularity = 'monthly' | 'daily';

/** View-model the component renders. */
export interface TrendBundleViewModel {
  /** Which series the main line chart uses (monthly preferred). */
  granularity: TrendGranularity;
  /** X-axis category labels (months "YYYY-MM" or dates "YYYY-MM-DD"). */
  categories: string[];
  /** Revenue values aligned with `categories`. */
  revenue: number[];
  /** Weekday/weekend average comparison; null when no usable averages. */
  weekdayWeekend: {
    weekdayAvg: number;
    weekendAvg: number;
    weekdayDays: number;
    weekendDays: number;
  } | null;
  /** Number of underlying points (informational header tag). */
  pointCount: number;
}

function _num(v: number | null | undefined): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}

/**
 * Convert the gold trend-bundle payload into the render view-model.
 * Returns null when there is no usable revenue series (honest empty).
 */
export function goldTrendBundleToViewModel(
  payload: GoldTrendBundlePayload | null | undefined,
): TrendBundleViewModel | null {
  if (!payload || typeof payload !== 'object') return null;

  const monthly = Array.isArray(payload.monthlyTrend) ? payload.monthlyTrend : [];
  const daily = Array.isArray(payload.dailyTrend) ? payload.dailyTrend : [];

  // Prefer monthly (less noisy over long history); fall back to daily.
  let granularity: TrendGranularity;
  let categories: string[];
  let revenue: number[];

  if (monthly.length > 0) {
    granularity = 'monthly';
    // Backend returns monthly sorted; sort defensively for stable display.
    const sorted = [...monthly].sort((a, b) => String(a.month).localeCompare(String(b.month)));
    categories = sorted.map((p) => String(p.month));
    revenue = sorted.map((p) => _num(p.revenue));
  } else if (daily.length > 0) {
    granularity = 'daily';
    const sorted = [...daily].sort((a, b) => String(a.date).localeCompare(String(b.date)));
    categories = sorted.map((p) => String(p.date));
    revenue = sorted.map((p) => _num(p.revenue));
  } else {
    // No monthly AND no daily → honest empty.
    return null;
  }

  // Weekday/weekend comparison — only meaningful when at least one side has a
  // non-zero average. RBAC-nulled or absent averages collapse to 0.
  let ww: TrendBundleViewModel['weekdayWeekend'] = null;
  if (payload.weekdayWeekend && typeof payload.weekdayWeekend === 'object') {
    const wkAvg = _num(payload.weekdayWeekend.weekdayAvg);
    const weAvg = _num(payload.weekdayWeekend.weekendAvg);
    if (wkAvg > 0 || weAvg > 0) {
      ww = {
        weekdayAvg: wkAvg,
        weekendAvg: weAvg,
        weekdayDays: _num(payload.weekdayWeekend.weekdayDays),
        weekendDays: _num(payload.weekdayWeekend.weekendDays),
      };
    }
  }

  return {
    granularity,
    categories,
    revenue,
    weekdayWeekend: ww,
    pointCount: granularity === 'monthly' ? monthly.length : daily.length,
  };
}
