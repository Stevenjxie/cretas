/**
 * WS4 Task 1 — shared "all-history" default date-range resolution.
 *
 * 子页 (销售/趋势/KPI/财务) 默认时间窗从硬编码"近30天/本年"改成"全部历史"
 * (修 #10/#11/#12 — qhj 等租户真实数据落在历史区间, 默认窗常常空 → "所选区间无销售数据")。
 *
 * 复用方式: 调 `getGoldDataRange(factoryId)` 拿真实数据窗 [minDate, maxDate],
 * 失败 (或无数据) 时回落到一个足够宽的窗口 (default 730 天) — 绝不回到"近30天"。
 *
 * 这层是纯函数 (无 IO), 方便单测; IO (getGoldDataRange) 由调用方注入。
 */

/** ISO YYYY-MM-DD for a Date. */
export function isoDate(d: Date): string {
  return d.toISOString().slice(0, 10);
}

/**
 * Wide fallback range ending today, `days` long. Used when the gold data-range
 * probe returns nothing (null bounds) or throws. 730d (~2y) safely covers a
 * tenant's full single-year POS history without re-introducing the "近30天" trap.
 */
export function wideFallbackRange(days = 730, now: Date = new Date()): [string, string] {
  const end = new Date(now);
  const start = new Date(now);
  start.setDate(start.getDate() - (days - 1));
  return [isoDate(start), isoDate(end)];
}

export interface GoldRangeLike {
  minDate: string | null;
  maxDate: string | null;
  dayCount?: number;
}

/**
 * Resolve the default [start, end] (YYYY-MM-DD) "all history" window from a gold
 * data-range probe result. When the probe has real bounds, use them verbatim;
 * otherwise fall back to a wide window (never "近30天").
 */
export function resolveAllHistoryRange(
  probe: GoldRangeLike | null | undefined,
  fallbackDays = 730,
  now: Date = new Date(),
): [string, string] {
  if (probe && probe.minDate && probe.maxDate) {
    return [probe.minDate, probe.maxDate];
  }
  return wideFallbackRange(fallbackDays, now);
}
