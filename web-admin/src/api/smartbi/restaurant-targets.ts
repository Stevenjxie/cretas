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
