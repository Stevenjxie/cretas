/**
 * 退货单 API client — typed wrapper around 7 backend endpoints.
 *
 * Backend: ReturnOrderController @RequestMapping
 *   "/api/mobile/{factoryId}/return-orders"
 *
 * axios baseURL = '/api/mobile' (see request.ts:109), so caller-side
 * URLs start with "/{factoryId}/return-orders/...".
 *
 * Status machine (ReturnOrderStatus.java) — 六扇门 Tier0 #16 财审门:
 *   DRAFT → SUBMITTED → (APPROVED → FINANCE_APPROVED | REJECTED) → COMPLETED
 *   退货跟钱有关, 业务审批后必须先财务审批 (finance:read_write) 才能完成.
 *
 * Customer business rule (第四次:956-1037, T-RTA #571):
 *   withGoods=TRUE  → 有食物退货 (库存入库总仓 + 完成时触发 AR 冲减)
 *   withGoods=FALSE → 无食物退款 (审批立即触发 AR 冲减, 无库存动作)
 *
 * This module follows the pattern in customerTracking.ts — typed
 * functions, ApiResponse envelope unwrap, axios interceptor handles
 * error toasts.
 */
import { get, post } from './request';
import type { ApiResponse } from '@/types/api';

// ============================================================================
// Types
// ============================================================================

export type ReturnType = 'PURCHASE_RETURN' | 'SALES_RETURN';

export type ReturnOrderStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'APPROVED'
  // 六扇门 Tier0 #16: 退货财审门 APPROVED → FINANCE_APPROVED → COMPLETED.
  | 'FINANCE_APPROVED'
  | 'REJECTED'
  | 'PROCESSING'
  | 'COMPLETED';

export interface ReturnOrderItem {
  id?: number;
  returnOrderId?: string;
  materialTypeId?: string | null;
  productTypeId?: string | null;
  itemName?: string | null;
  quantity: number;
  unitPrice?: number | null;
  lineAmount?: number | null;
  batchNumber?: string | null;
  reason?: string | null;
}

export interface ReturnOrder {
  id: string;
  factoryId: string;
  returnNumber: string;
  returnType: ReturnType;
  status: ReturnOrderStatus;
  counterpartyId: string;
  sourceOrderId?: string | null;
  returnDate: string;
  totalAmount?: number | null;
  reason?: string | null;
  withGoods: boolean;
  createdBy: number;
  approvedBy?: number | null;
  approvedAt?: string | null;
  remark?: string | null;
  items?: ReturnOrderItem[];
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateReturnOrderItemRequest {
  materialTypeId?: string | null;
  productTypeId?: string | null;
  itemName?: string | null;
  quantity: number;
  unitPrice?: number | null;
  batchNumber?: string | null;
  reason?: string | null;
}

export interface CreateReturnOrderRequest {
  returnType: ReturnType;
  counterpartyId: string;
  sourceOrderId?: string | null;
  returnDate: string; // YYYY-MM-DD
  reason?: string | null;
  /**
   * T-RTA #571: TRUE=有食物 (库存入库 + 完成时 AR 冲减),
   * FALSE=无食物 (审批立即 AR 冲减). Default TRUE if omitted.
   */
  withGoods?: boolean;
  remark?: string | null;
  items: CreateReturnOrderItemRequest[];
  customFields?: Record<string, unknown>;
}

export interface ReturnOrderPage {
  content: ReturnOrder[];
  totalElements: number;
  totalPages: number;
}

export interface ReturnOrderListParams {
  returnType?: ReturnType;
  status?: ReturnOrderStatus;
  page?: number;
  size?: number;
}

// ============================================================================
// API methods
// ============================================================================

function basePath(factoryId: string): string {
  return `/${factoryId}/return-orders`;
}

/** POST / — create a return order (status=DRAFT). */
export async function createReturnOrder(
  factoryId: string,
  body: CreateReturnOrderRequest,
): Promise<ReturnOrder> {
  const res = await post<ReturnOrder>(basePath(factoryId), body);
  if (!res.success || !res.data) {
    throw new Error(res.message || '创建退货单失败');
  }
  return res.data;
}

/** GET / — list return orders (paged). */
export async function listReturnOrders(
  factoryId: string,
  params: ReturnOrderListParams = {},
): Promise<ReturnOrderPage> {
  const res = await get<ReturnOrderPage>(basePath(factoryId), { params });
  if (!res.success || !res.data) {
    return { content: [], totalElements: 0, totalPages: 0 };
  }
  return res.data;
}

/** GET /{id} — return order details (includes items). */
export async function getReturnOrder(
  factoryId: string,
  returnOrderId: string,
): Promise<ReturnOrder> {
  const res = await get<ReturnOrder>(`${basePath(factoryId)}/${returnOrderId}`);
  if (!res.success || !res.data) {
    throw new Error(res.message || '加载退货单失败');
  }
  return res.data;
}

/** POST /{id}/submit — DRAFT → SUBMITTED. */
export async function submitReturnOrder(
  factoryId: string,
  returnOrderId: string,
): Promise<ReturnOrder> {
  const res = await post<ReturnOrder>(
    `${basePath(factoryId)}/${returnOrderId}/submit`,
  );
  if (!res.success || !res.data) {
    throw new Error(res.message || '提交退货单失败');
  }
  return res.data;
}

/** POST /{id}/approve — SUBMITTED → APPROVED (无食物时立即触发 AR 冲减). */
export async function approveReturnOrder(
  factoryId: string,
  returnOrderId: string,
): Promise<ReturnOrder> {
  const res = await post<ReturnOrder>(
    `${basePath(factoryId)}/${returnOrderId}/approve`,
  );
  if (!res.success || !res.data) {
    throw new Error(res.message || '审批退货单失败');
  }
  return res.data;
}

/**
 * POST /{id}/finance-approve — APPROVED → FINANCE_APPROVED.
 * 六扇门 Tier0 #16: 退货跟钱有关, 业务审批后必须先财务审批才能完成. 仅财务角色 (finance:read_write).
 */
export async function financeApproveReturnOrder(
  factoryId: string,
  returnOrderId: string,
): Promise<ReturnOrder> {
  const res = await post<ReturnOrder>(
    `${basePath(factoryId)}/${returnOrderId}/finance-approve`,
  );
  if (!res.success || !res.data) {
    throw new Error(res.message || '财务审批退货单失败');
  }
  return res.data;
}

/** POST /{id}/reject — SUBMITTED → REJECTED. */
export async function rejectReturnOrder(
  factoryId: string,
  returnOrderId: string,
): Promise<ReturnOrder> {
  const res = await post<ReturnOrder>(
    `${basePath(factoryId)}/${returnOrderId}/reject`,
  );
  if (!res.success || !res.data) {
    throw new Error(res.message || '驳回退货单失败');
  }
  return res.data;
}

/** POST /{id}/complete — FINANCE_APPROVED/PROCESSING → COMPLETED (有食物时触发 AR 冲减). */
export async function completeReturnOrder(
  factoryId: string,
  returnOrderId: string,
): Promise<ReturnOrder> {
  const res = await post<ReturnOrder>(
    `${basePath(factoryId)}/${returnOrderId}/complete`,
  );
  if (!res.success || !res.data) {
    throw new Error(res.message || '完成退货单失败');
  }
  return res.data;
}

/** GET /statistics — aggregate counts/amounts by type & status. */
export async function getReturnOrderStatistics(
  factoryId: string,
): Promise<Record<string, unknown>> {
  const res: ApiResponse<Record<string, unknown>> = await get(
    `${basePath(factoryId)}/statistics`,
  );
  if (!res.success || !res.data) {
    throw new Error(res.message || '加载退货统计失败');
  }
  return res.data;
}
