/**
 * Gold data date-range probe.
 *
 * Calls Python /api/smartbi/gold/data-range directly via pythonFetch
 * (matches analysisResults.ts / gold.ts pattern — the Java gateway does not
 * proxy /smart-bi/gold/* routes). pythonFetch auto-converts snake_case →
 * camelCase, so the caller receives { minDate, maxDate, dayCount }.
 *
 * Used by Dashboard.vue (经营驾驶舱) to default a restaurant tenant's date
 * picker to its real data window (e.g. 2025 full year) instead of the empty
 * current month — so 餐饮 owners see charts on all existing data without
 * having to upload or pick a range first.
 */
import { pythonFetch } from './common';

export interface GoldDataRange {
  factoryId: string;
  minDate: string | null;
  maxDate: string | null;
  dayCount: number;
}

export async function getGoldDataRange(factoryId: string): Promise<GoldDataRange> {
  const params = new URLSearchParams({ factory_id: factoryId });
  return (await pythonFetch(
    `/api/smartbi/gold/data-range?${params.toString()}`,
    { method: 'GET' },
  )) as GoldDataRange;
}
