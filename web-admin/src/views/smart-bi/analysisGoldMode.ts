/**
 * WS6 (#1/#3) — "传 Excel 分析" tab gold-default decision for restaurant tenants.
 *
 * Problem: for restaurant (gold) tenants like qhj, the analysis tab auto-selects
 * the most-recent uploaded batch — which is often a PARTIAL POS slice
 * (e.g. `qhj_pos_2025_part4.csv`, 1 of 4 parts). A partial slice yields no
 * useful charts, so the tab shows "0 个图表" + "暂无 AI 分析" → blank/useless.
 * The tenant's real, full data lives in the GOLD layer (all months, all stores),
 * which the dashboard / 经营分析 already render via `RestaurantGoldGrid`.
 *
 * Fix: when the tenant is a RESTAURANT (gold tenant), default the analysis tab to
 * a GOLD GENERAL CHART SET (门店营收排行 + 渠道占比, via RestaurantGoldGrid) instead
 * of auto-selecting a partial uploaded file. File-upload analysis stays available
 * behind a toggle. Factory tenants are unaffected — they always use file upload.
 *
 * Pure functions (no IO / no Vue) so they are unit-testable; the component wires
 * them to authStore.factoryType and the gold date-range probe.
 */

/** True only for restaurant tenants — they have gold data and should default to it. */
export function shouldDefaultToGold(factoryType: string | null | undefined): boolean {
  return factoryType === 'RESTAURANT';
}

/**
 * Detect a partial / sharded upload file by name, e.g. `qhj_pos_2025_part4.csv`,
 * `sales_part_2.xlsx`, `data-part3`. Matches a `part` token followed by a number,
 * optionally separated by `_`/`-`/space, case-insensitive. Used to (a) explain why
 * the upload tab is blank and (b) prefer a complete upload over a partial one.
 */
export function isPartialUploadName(fileName: string | null | undefined): boolean {
  if (!fileName) return false;
  return /(?:^|[_\- ])part[_\- ]?\d+/i.test(fileName);
}

/**
 * Pick the default batch index from a list of batch file names: prefer the first
 * COMPLETE (non-partial) batch; fall back to index 0 when every batch looks
 * partial (or the list is empty). Keeps recency order otherwise (caller passes
 * names already sorted most-recent-first).
 */
export function pickDefaultBatchIndex(batchFileNames: Array<string | null | undefined>): number {
  if (!batchFileNames.length) return 0;
  const completeIdx = batchFileNames.findIndex((n) => !isPartialUploadName(n));
  return completeIdx >= 0 ? completeIdx : 0;
}
