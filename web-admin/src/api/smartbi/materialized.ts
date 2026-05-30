/**
 * Materialized analytics API client.
 * Endpoints (via pythonFetch to Python 8083, proxied at /smartbi-api):
 *   GET  /api/smartbi/analytics/cached/{uploadId}
 *   POST /api/smartbi/analytics/materialize/{uploadId}
 *   GET  /api/smartbi/analytics/list-factory-templates?factory_id=X
 */
import { getFactoryId, pythonFetch } from './common';

export interface MaterializedKpis {
  [key: string]: string | number | boolean | null;
}

// Note: pythonFetch (common.ts) auto-converts snake_case → camelCase via transformKeys.
// Interfaces below use camelCase to match the runtime shape.
export interface MaterializedResult {
  code: string;
  title: string;
  data: Record<string, unknown>;
  chartConfig: Record<string, unknown> | null;
  kpis: MaterializedKpis;
  insightText: string | null;
  domain: string;
  schemaVersion: number;
  createdAt: string | null;
}

export interface MaterializedCacheResponse {
  success: boolean;
  cached: boolean;
  uploadId: number;
  factoryId: string;
  count: number;
  results: MaterializedResult[];
}

export interface MaterializeTriggerResponse {
  success: boolean;
  uploadId: number;
  factoryId: string;
  domain: string;
  totalTemplates: number;
  applied: number;
  skipped: number;
  errored: number;
  saved: number;
  wallMs: number;
}

export function fetchCachedAnalytics(uploadId: number | string): Promise<MaterializedCacheResponse> {
  return pythonFetch(`/api/smartbi/analytics/cached/${uploadId}`, {
    method: 'GET',
  }) as Promise<MaterializedCacheResponse>;
}

export function triggerMaterialization(uploadId: number | string): Promise<MaterializeTriggerResponse> {
  return pythonFetch(`/api/smartbi/analytics/materialize/${uploadId}`, {
    method: 'POST',
    timeoutMs: 120_000,  // 200K-row materialize can take 50-60s
  }) as Promise<MaterializeTriggerResponse>;
}

/** 措施③ recommendation chip — one materialized template for the factory. */
export interface FactoryTemplate {
  code: string;
  title: string;
  materializationCount: number;  // snake_case materialization_count → camelCase via transformKeys
  isRecommended: boolean;        // is_recommended → isRecommended
}

export interface ListFactoryTemplatesResponse {
  success: boolean;
  factoryId: string;
  count: number;
  templates: FactoryTemplate[];
}

/**
 * 措施③ "猜你想问" guidance: list the analysis templates that already have
 * materialized (pre-computed) results for the current factory, ordered by
 * popularity (top 3 flagged is_recommended). Clicking a chip pins the user's
 * input to a known template phrase → hits the materialized cache (0 token).
 */
export function listFactoryTemplates(): Promise<ListFactoryTemplatesResponse> {
  const factoryId = getFactoryId();
  return pythonFetch(
    `/api/smartbi/analytics/list-factory-templates?factory_id=${encodeURIComponent(factoryId)}`,
    { method: 'GET' },
  ) as Promise<ListFactoryTemplatesResponse>;
}
