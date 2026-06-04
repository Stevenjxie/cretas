/**
 * G2 餐饮目标拆分 + 达成率预警 API client.
 * All requests go through pythonFetch which auto-transforms
 * snake_case response keys to camelCase.
 * Request body is camelCase; URL query params need manual snake_case conversion.
 */
import { pythonFetch } from '@/api/smartbi/common';

const BASE = '/api/smartbi/restaurant-targets';

// ── Request / Response types ──────────────────────────────────────────────────

export interface TargetUpsertRequest {
  kpiKind: string;          // 'revenue' | 'bill_count'
  level: string;            // 'year' | 'month' | 'week' | 'day'
  periodKey: string;        // '2026', '2026-06', '2026-W23', '2026-06-03'
  targetValue: number;      // must be > 0
  storeId?: number | null;  // null = 全品牌汇总
  reason?: string | null;   // dropdown value or null
}

export interface AchievementPoint {
  periodKey: string;
  target: number | null;
  actual: number | null;
  achievementRate: number | null;
  dataMissing: boolean;
  // Fix 3: in-progress period flags. A week/month/year period whose last day
  // is still in the future is INCOMPLETE — its rate only covers the elapsed
  // days, so a partial-period low percentage is NOT a real low-achievement
  // signal. FE shows "进行中 (已过 N/总 M 天)" instead of a bare percentage.
  periodComplete?: boolean;
  inProgress?: boolean;
  daysElapsed?: number;
  daysTotal?: number;
}

export interface AchievementResponse {
  factoryId: string;
  kpiKind: string;
  level: string;
  points: AchievementPoint[];
  periodWithoutTarget: string[];
}

export interface AlertTimelineEntry {
  date: string;
  achievementRate: number | null;
  status: 'OK' | 'WARN' | 'CRITICAL' | 'NO_TARGET' | 'DATA_MISSING';
  target: number | null;
  actual: number | null;
}

export interface AlertSummary {
  OK?: number;
  WARN?: number;
  CRITICAL?: number;
  NO_TARGET?: number;
  DATA_MISSING?: number;
}

export interface AlertResponse {
  factoryId: string;
  kpiKind: string;
  lookbackDays: number;
  configExists: boolean;
  timeline: AlertTimelineEntry[];
  summary: AlertSummary;
}

export interface AlertConfigRequest {
  kpiKind: string;
  level: string;
  warnThreshold: number;     // e.g. 0.80
  criticalThreshold: number; // e.g. 0.60; must be < warnThreshold
  storeId?: number | null;
}

export interface ApiEnvelope<T> {
  success: boolean;
  data: T;
  message: string;
}

// ── API functions ─────────────────────────────────────────────────────────────

export async function upsertTarget(
  req: TargetUpsertRequest,
): Promise<ApiEnvelope<{ id: number; periodKey: string; targetValue: number; updatedAt: string }>> {
  return pythonFetch(`${BASE}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });
}

export async function fetchAchievement(params: {
  startDate: string;
  endDate: string;
  kpiKind?: string;
  level?: string;
  storeId?: number | null;
}): Promise<ApiEnvelope<AchievementResponse>> {
  // pythonFetch does NOT auto-convert request query params; manual snake_case.
  const qp = new URLSearchParams({
    start_date: params.startDate,
    end_date: params.endDate,
    kpi_kind: params.kpiKind ?? 'revenue',
    level: params.level ?? 'day',
  });
  if (params.storeId != null) {
    qp.set('store_id', String(params.storeId));
  }
  return pythonFetch(`${BASE}/achievement?${qp.toString()}`);
}

export async function fetchAlerts(params?: {
  lookbackDays?: number;
  kpiKind?: string;
}): Promise<ApiEnvelope<AlertResponse>> {
  const qp = new URLSearchParams({
    lookback_days: String(params?.lookbackDays ?? 7),
    kpi_kind: params?.kpiKind ?? 'revenue',
  });
  return pythonFetch(`${BASE}/alerts?${qp.toString()}`);
}

export async function upsertAlertConfig(
  req: AlertConfigRequest,
): Promise<ApiEnvelope<{ id: number; warnThreshold: number; criticalThreshold: number }>> {
  return pythonFetch(`${BASE}/alert-config`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });
}

// ── #58 Phase 1: 周/日拆分 + 滚动预测 + pace 预警 ────────────────────────────────

export interface DecomposeWeeksRequest {
  kpiKind?: string;          // 'revenue' (default) | 'bill_count'
  periodKey: string;         // month key '2026-06'
  storeId?: number | null;
  cascadeToDays?: boolean;   // also split each week into 7 days
}

export interface DecomposedTargetRow {
  id: number;
  periodKey: string;
  targetValue: number;
}

export interface DecomposeWeeksResponse {
  factoryId: string;
  periodKey: string;
  level: string;
  basis?: string;            // 'historical_week_share' | 'days_in_month_fallback'
  monthTarget?: number;
  weeks: DecomposedTargetRow[];
  dayCascade?: Array<{ periodKey: string; basis?: string; days: DecomposedTargetRow[] }>;
  message?: string;
}

export interface ForecastPoint {
  date: string;
  forecastAmount: number | null;  // null when RBAC-stripped for non-price role
  lowerBound: number | null;
  upperBound: number | null;
}

export interface ForecastResponse {
  factoryId: string;
  storeId: number | null;
  modelType: string;              // 'linear_trend' | 'mean_fallback' | 'no_data'
  anchorDate: string | null;
  windowDays: number;
  horizonDays: number;
  points: ForecastPoint[];
  message?: string;
}

export interface PaceAlertResponse {
  factoryId: string;
  kpiKind: string;
  periodType: string;
  periodKey: string;
  storeId: number | null;
  alertLevel: 'OK' | 'WARN' | 'CRIT' | 'NO_TARGET';
  targetAmount: number | null;
  actualAmount: number | null;
  completionPct: number | null;   // 0..n
  elapsedPct: number | null;      // 0..1
  paceGapPct: number | null;      // completion - elapsed (negative = behind)
  daysElapsed?: number;
  daysTotal?: number;
  dataMissing?: boolean;
  message?: string;
}

export async function decomposeWeeks(
  req: DecomposeWeeksRequest,
): Promise<ApiEnvelope<DecomposeWeeksResponse>> {
  return pythonFetch(`${BASE}/decompose-weeks`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });
}

export async function fetchForecast(params?: {
  horizonDays?: number;
  windowDays?: number;
  storeId?: number | null;
  persist?: boolean;
}): Promise<ApiEnvelope<ForecastResponse>> {
  const qp = new URLSearchParams({
    horizon_days: String(params?.horizonDays ?? 30),
    window_days: String(params?.windowDays ?? 90),
    persist: String(params?.persist ?? false),
  });
  if (params?.storeId != null) qp.set('store_id', String(params.storeId));
  return pythonFetch(`${BASE}/forecast?${qp.toString()}`);
}

export async function fetchPaceAlerts(params?: {
  kpiKind?: string;
  storeId?: number | null;
  periodType?: string;
}): Promise<ApiEnvelope<PaceAlertResponse>> {
  const qp = new URLSearchParams({
    kpi_kind: params?.kpiKind ?? 'revenue',
    period_type: params?.periodType ?? 'month',
  });
  if (params?.storeId != null) qp.set('store_id', String(params.storeId));
  return pythonFetch(`${BASE}/pace-alerts?${qp.toString()}`);
}

export async function suppressAlert(
  alertId: number,
  suppressUntil: string,  // YYYY-MM-DD
): Promise<ApiEnvelope<{ id: number; periodType: string; periodKey: string; suppressedUntil: string }>> {
  return pythonFetch(`${BASE}/alerts/${alertId}/suppress`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ suppressUntil }),
  });
}
