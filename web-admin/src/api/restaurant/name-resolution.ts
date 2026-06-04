/**
 * API client for POS dish-name resolution admin endpoints (#61 Phase 1).
 * Consumes Python endpoints under /api/smartbi/restaurant/name-resolution/*:
 *   GET  /unresolved   — pending queue rows (sorted revenue_at_risk DESC)
 *   POST /confirm      — bind pos_name → product_type (+ ETL re-run)
 *   POST /reject
 *   POST /skip
 *   POST /run-backfill
 *   GET  /stats
 *
 * factory_id is read server-side from the JWT (require_admin), so the client
 * does NOT pass factoryId. Uses pythonFetch (snake→camel auto-convert).
 */
import { pythonFetch } from '@/api/smartbi/common';

export interface UnresolvedItem {
  posName: string;
  displayName: string | null;
  occurrenceCount: number;
  revenueAtRisk: number;
  bestCandidateId: string | null;
  bestCandidateName: string | null;
  bestConfidence: number | null;
  status: string;
}

export interface UnresolvedResponse {
  success: boolean;
  data: { items: UnresolvedItem[]; total: number };
}

export interface CoverageStats {
  matched: number;
  total: number;
  coveragePct: number;
}

export interface BackfillCounts {
  totalPosNames: number;
  alreadyResolved: number;
  resolvedAuto: number;
  queued: number;
}

const BASE = '/api/smartbi/restaurant/name-resolution';

export async function fetchUnresolved(): Promise<UnresolvedResponse> {
  return pythonFetch(`${BASE}/unresolved`) as Promise<UnresolvedResponse>;
}

export async function fetchCoverageStats(): Promise<{ success: boolean; data: CoverageStats }> {
  return pythonFetch(`${BASE}/stats`) as Promise<{ success: boolean; data: CoverageStats }>;
}

export async function confirmBinding(
  posName: string,
  productTypeId: string,
): Promise<{ success: boolean; data: Record<string, unknown>; message?: string }> {
  return pythonFetch(`${BASE}/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ posName, productTypeId }),
  }) as Promise<{ success: boolean; data: Record<string, unknown>; message?: string }>;
}

export async function rejectBinding(posName: string): Promise<{ success: boolean }> {
  return pythonFetch(`${BASE}/reject`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ posName }),
  }) as Promise<{ success: boolean }>;
}

export async function skipBinding(posName: string): Promise<{ success: boolean }> {
  return pythonFetch(`${BASE}/skip`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ posName }),
  }) as Promise<{ success: boolean }>;
}

export async function runBackfill(): Promise<{ success: boolean; data: BackfillCounts }> {
  return pythonFetch(`${BASE}/run-backfill`, { method: 'POST' }) as Promise<{
    success: boolean;
    data: BackfillCounts;
  }>;
}

/** Product-type options for the confirm dropdown. Reuses recipes-side endpoint. */
export interface ProductTypeOption {
  id: string;
  name: string;
}

export async function fetchProductTypes(): Promise<ProductTypeOption[]> {
  const res = (await pythonFetch('/api/smartbi/restaurant-ops/product-types')) as {
    success: boolean;
    data?: { products?: ProductTypeOption[] };
  };
  return res?.data?.products ?? [];
}
