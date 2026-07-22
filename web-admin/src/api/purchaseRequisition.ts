/**
 * 请购单 API 客户端 — Sprint 6 W2-A (P-REQUISITION-1 frontend).
 *
 * 对接 Sprint 5 Track D (PR #56) backend
 *   PurchaseRequisitionController @ /api/mobile/{factoryId}/purchase-requisitions
 *
 * State machine:
 *   DRAFT ──submit──> PENDING_APPROVAL ──approve──> APPROVED ──convertToPO──> CONVERTED_TO_PO
 *                          │                            │
 *                          └──reject─────────> REJECTED ┘
 *
 * RBAC: 列表/详情/创建/提交 → procurement:read 或 procurement:read_write
 *       审批/驳回/转 PO → procurement:read_write
 *
 * Sprint 6 W4 integrations (deferred):
 *   - vflag listener for workflow approval events
 *   - AI Tool wrapping (PurchaseRequisitionTool)
 *   - Canvas-Workflow ApprovalChainConfig integration
 */
import { get, post, del } from './request';
import type { ApiResponse } from '@/types/api';

// ============================================================================
// 类型
// ============================================================================

export type PurchaseRequisitionStatus =
  | 'DRAFT'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'CONVERTED_TO_PO'
  | 'REJECTED';

/**
 * 请购行项目 — JSONB shape from backend entity (Sprint 5 MVP).
 * Sprint 6 W4 可拆 RequisitionItem 关系表后改 typed DTO.
 */
export interface RequisitionItem {
  materialTypeId: string;
  materialName?: string | null;
  quantity: number;
  unit: string;
  requiredQuantity?: number | null;
  availableQuantity?: number | null;
  reservedQuantity?: number | null;
  shortfallQuantity?: number | null;
  sourceProductionPlanId?: string | null;
  sourceSalesOrderId?: string | null;
  sourceSalesOrderItemId?: string | null;
  suggestedSupplierId?: string | null;
  remark?: string | null;
  // 允许后端透传扩展字段 (Canvas dynamic fields 等)
  [key: string]: unknown;
}

export interface PurchaseRequisition {
  id: string;
  factoryId: string;
  requisitionNumber: string;
  requesterId: number;
  requesterDeptId?: string | null;
  sourceType?: 'PRODUCTION_PLAN_SHORTAGE' | string | null;
  sourceId?: string | null;
  sourceNo?: string | null;
  requestedItems: RequisitionItem[];
  status: PurchaseRequisitionStatus;
  expectedDate?: string | null;
  reason?: string | null;
  approverId?: number | null;
  approvedAt?: string | null;
  rejectionReason?: string | null;
  convertedPoId?: string | null;
  convertedAt?: string | null;
  remark?: string | null;
  version: number;
  createdAt: string;
  updatedAt?: string | null;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export interface CreatePurchaseRequisitionRequest {
  requesterDeptId?: string | null;
  requestedItems: RequisitionItem[];
  expectedDate?: string | null;
  reason?: string | null;
  remark?: string | null;
}

export interface PurchaseOrderSummary {
  id: string;
  orderNumber: string;
  supplierId: string;
  factoryId: string;
}

// ============================================================================
// API 方法 — baseURL = /api/mobile (request.ts:109)
// ============================================================================

/** 创建请购单草稿 (status=DRAFT). */
export function createRequisition(
  factoryId: string,
  request: CreatePurchaseRequisitionRequest,
): Promise<ApiResponse<PurchaseRequisition>> {
  return post<PurchaseRequisition>(
    `/${factoryId}/purchase-requisitions`,
    request,
  );
}

/**
 * 请购单列表 — 可按 status / requesterId 过滤.
 *
 * - my-list: 传 requesterId = 当前用户
 * - pending-approval: 传 status = 'PENDING_APPROVAL'
 * - list: 不传过滤
 */
export function listRequisitions(
  factoryId: string,
  params: {
    status?: PurchaseRequisitionStatus;
    requesterId?: number;
    page?: number;
    size?: number;
  } = {},
): Promise<ApiResponse<PageResponse<PurchaseRequisition>>> {
  const query: Record<string, string | number> = {
    page: params.page ?? 1,
    size: params.size ?? 20,
  };
  if (params.status) query.status = params.status;
  if (params.requesterId !== undefined) query.requesterId = params.requesterId;
  return get<PageResponse<PurchaseRequisition>>(
    `/${factoryId}/purchase-requisitions`,
    { params: query },
  );
}

/** 请购单详情. */
export function getRequisition(
  factoryId: string,
  requisitionId: string,
): Promise<ApiResponse<PurchaseRequisition>> {
  return get<PurchaseRequisition>(
    `/${factoryId}/purchase-requisitions/${requisitionId}`,
  );
}

/** 提交审批 (DRAFT → PENDING_APPROVAL). */
export function submitRequisition(
  factoryId: string,
  requisitionId: string,
): Promise<ApiResponse<PurchaseRequisition>> {
  return post<PurchaseRequisition>(
    `/${factoryId}/purchase-requisitions/${requisitionId}/submit`,
  );
}

/** 审批通过 (PENDING_APPROVAL → APPROVED). */
export function approveRequisition(
  factoryId: string,
  requisitionId: string,
): Promise<ApiResponse<PurchaseRequisition>> {
  return post<PurchaseRequisition>(
    `/${factoryId}/purchase-requisitions/${requisitionId}/approve`,
  );
}

/** 审批驳回 (PENDING_APPROVAL → REJECTED). rejectionReason 必填. */
export function rejectRequisition(
  factoryId: string,
  requisitionId: string,
  rejectionReason: string,
): Promise<ApiResponse<PurchaseRequisition>> {
  return post<PurchaseRequisition>(
    `/${factoryId}/purchase-requisitions/${requisitionId}/reject`,
    { rejectionReason },
  );
}

/** 转 PO (APPROVED → CONVERTED_TO_PO). supplierId 必填 (UI 让操作人选). */
export function convertToPO(
  factoryId: string,
  requisitionId: string,
  supplierId: string,
): Promise<ApiResponse<PurchaseOrderSummary>> {
  return post<PurchaseOrderSummary>(
    `/${factoryId}/purchase-requisitions/${requisitionId}/convert-to-po`,
    { supplierId },
  );
}

/**
 * 删除请购单草稿 (仅 DRAFT 可删) — Bug #6 (PR #96 backend, 2026-05-20).
 *
 * 状态机约束: 仅 DRAFT 状态可删 (软删除 via deletedAt).
 * 非 DRAFT 返 400 + actionHint 引导用户走 reject (驳回) 流程.
 * 仅 requester 本人可删 (一致于 submitRequisition).
 *
 * @throws 400 if 非 DRAFT 状态
 * @throws 403 if 跨工厂或非 requester
 * @throws 404 if 不存在
 */
export function deleteRequisition(
  factoryId: string,
  requisitionId: string,
): Promise<ApiResponse<void>> {
  return del<void>(
    `/${factoryId}/purchase-requisitions/${requisitionId}`,
  );
}

// ============================================================================
// UI helpers
// ============================================================================

export const REQUISITION_STATUS_MAP: Record<
  PurchaseRequisitionStatus,
  { text: string; type: '' | 'success' | 'warning' | 'info' | 'danger' }
> = {
  DRAFT: { text: '草稿', type: 'info' },
  PENDING_APPROVAL: { text: '待审批', type: 'warning' },
  APPROVED: { text: '已审批', type: 'success' },
  CONVERTED_TO_PO: { text: '已转PO', type: '' },
  REJECTED: { text: '已驳回', type: 'danger' },
};
