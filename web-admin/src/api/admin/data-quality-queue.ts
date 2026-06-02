/**
 * Data Quality Queue API client — 餐饮 Phase A A-3 (Task 3.5).
 *
 * Python endpoints: /api/smartbi/admin/data-quality-queue/*
 * Java endpoint  : /api/mobile/{factoryId}/users/admin-count (via axios with JWT)
 *
 * Uses pythonFetch for Python backend (snake→camelCase auto-converted).
 * Uses project axios instance (get) for Java backend — JWT is forwarded
 * automatically via the HttpOnly cookie + Authorization header interceptor.
 */
import { pythonFetch } from '@/api/smartbi/common';
import { get } from '@/api/request';
import type { ApiResponse } from '@/types/api';

// ── Types ──────────────────────────────────────────────────────────────────

// P4a (Task 8): per-member enrichment for dish (菜名归一) review items.
// One member = one dim_product SKU that the proposal wants to merge into a
// canonical dish. stores = which 门店 carry that SKU name; qty/revenue from
// agg_product (cross-store sum). Lets a human eyeball "same dish?" (Rule 2).
export interface DishReviewMember {
  productId: number;
  name: string;
  stores: string[];
  qty: number;
  revenue: number;
}

export interface DishReview {
  proposalKind: string | null;        // 'rule_cluster' | 'create_new' | 'agent_match'
  normalizedKey: string | null;
  category: string | null;
  suggestedCanonicalName: string | null;
  memberCount: number;
  members: DishReviewMember[];
}

export interface QueueItem {
  id: number;
  factoryId: string;
  entityType: string;
  rawName: string;
  candidateEntityId: number | null;
  confidence: number | null;
  decidedByAgent: string | null;
  status: 'PENDING' | 'CONFIRMED' | 'REJECTED' | 'DEFERRED';
  priority: string;
  sourceUploadId: number | null;
  submitter: string | null;
  adminUser: string | null;
  adminAt: string | null;
  adminAction: string | null;
  reasoning: string | null;
  extra: Record<string, unknown> | null;
  createdAt: string | null;
  // P4a: present (non-null) only for entity_type='dish'; null otherwise.
  dishReview?: DishReview | null;
}

export interface ListResponse {
  items: QueueItem[];
  total: number;
  page: number;
  pageSize: number;
}

export interface ResolveBody {
  action: 'confirm' | 'create_new';
  resolvedToEntityId?: number;
  notes?: string;
  // P4a (Task 8): for entity_type='dish' — the canonical display name the human
  // entered/edited. create_new → new canonical with this name; confirm →
  // (ignored, the target is resolvedToEntityId = existing canonical_dish_id).
  canonicalName?: string;
}

export interface ResolveResponse {
  resolved: boolean;
  singleAdminDegraded?: boolean;
}

export interface RejectResponse {
  rejected: boolean;
}

export interface BatchResolveBody {
  ids: number[];
  action: 'confirm' | 'create_new';
  resolvedToEntityId?: number;
}

export interface BatchResolveResponse {
  successCount: number;
  failedItems: Array<{ id: number; reason: string }>;
}

// ── API functions ──────────────────────────────────────────────────────────

/**
 * List queue items with optional filters.
 * factoryId is required — platform admins specify it explicitly.
 */
export async function listQueue(params: {
  factoryId: string;
  entityType?: string;
  status?: string;
  page?: number;
  pageSize?: number;
}): Promise<ListResponse> {
  const qs = new URLSearchParams({ factoryId: params.factoryId });
  if (params.entityType) qs.set('entityType', params.entityType);
  if (params.status) qs.set('status', params.status);
  qs.set('page', String(params.page ?? 1));
  qs.set('pageSize', String(params.pageSize ?? 50));
  return pythonFetch(
    `/api/smartbi/admin/data-quality-queue/list?${qs.toString()}`
  ) as Promise<ListResponse>;
}

/**
 * Resolve a single queue item (confirm or create_new action).
 */
export async function resolveQueue(
  id: number,
  body: ResolveBody
): Promise<ResolveResponse> {
  return pythonFetch(
    `/api/smartbi/admin/data-quality-queue/${id}/resolve`,
    { method: 'POST', body: JSON.stringify(body) }
  ) as Promise<ResolveResponse>;
}

/**
 * Reject a single queue item with a mandatory reason.
 */
export async function rejectQueue(
  id: number,
  reason: string
): Promise<RejectResponse> {
  return pythonFetch(
    `/api/smartbi/admin/data-quality-queue/${id}/reject`,
    { method: 'POST', body: JSON.stringify({ reason }) }
  ) as Promise<RejectResponse>;
}

/**
 * Batch-resolve multiple queue items.
 */
export async function batchResolveQueue(
  body: BatchResolveBody
): Promise<BatchResolveResponse> {
  return pythonFetch(
    '/api/smartbi/admin/data-quality-queue/batch-resolve',
    { method: 'POST', body: JSON.stringify(body) }
  ) as Promise<BatchResolveResponse>;
}

/**
 * Get the count of admin users for a factory.
 * Used to determine whether 4-eye is active (count > 1) or degraded (count === 1).
 *
 * Calls Java backend /api/mobile/{factoryId}/users/admin-count via the project's
 * axios instance, which automatically adds the Authorization / cookie headers.
 */
export async function getAdminCount(factoryId: string): Promise<number> {
  const resp = await get<{ count: number }>(
    `/${factoryId}/users/admin-count`
  ) as ApiResponse<{ count: number }>;
  return resp.data?.count ?? 1;
}

// ── History types & API (Task 3.6) ──────────────────────────────────────────

export interface HistoryItem {
  id: number;
  rawName: string;
  entityType: string;
  status: string;
  adminAction: string | null;
  adminAt: string | null;
  adminUser: string | null;
  resolvedToEntityId: number | null;
  candidateEntityId: number | null;
  confidence: number | null;
  decidedByAgent: string | null;
  createdAt: string | null;
  priority: string;
  reasoning: string | null;
  extra: Record<string, unknown> | null;
}

/**
 * Fetch all history rows for the same (factoryId, entityType, rawName) as
 * the given queue item id.  Ordered by createdAt DESC.
 */
export async function fetchQueueHistory(id: number): Promise<{ items: HistoryItem[] }> {
  return pythonFetch(
    `/api/smartbi/admin/data-quality-queue/${id}/history`
  ) as Promise<{ items: HistoryItem[] }>;
}
