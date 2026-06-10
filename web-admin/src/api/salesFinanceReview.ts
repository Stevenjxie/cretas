/**
 * Sprint4-H F-AR-1 — 销售订单财务审核 API client.
 *
 * <p>对接 SalesController 端点 (Sprint 2-J + Sprint4-H):
 *   - GET  /orders/by-status?status=PENDING_FINANCE_REVIEW
 *   - GET  /orders/{id}
 *   - GET  /orders/{id}/cost-breakdown  (新增, Sprint4-H F-AR-1)
 *   - POST /orders/{id}/finance-approve  body: { notes, estimatedCost }
 *   - POST /orders/{id}/finance-reject   body: { notes }  (必填)
 *
 * <p>RBAC: approve/reject 后端 @RequirePermission({"finance:read_write", "sales:read_write"}).
 * cost-breakdown 后端 @RequirePermission({"finance:read_write", "finance:read", "sales:read_write"}).
 * 前端按钮 v-if 用 permissionStore.canWrite('finance').
 */
import { get, post } from './request';
import type { ApiResponse } from '@/types/api';

// ============================================================================
// 类型
// ============================================================================

export type SalesOrderStatus =
  | 'DRAFT'
  | 'CONFIRMED'
  | 'PENDING_FINANCE_REVIEW'
  | 'FINANCE_APPROVED'
  | 'FINANCE_REJECTED'
  | 'IN_PRODUCTION'
  | 'PARTIAL_SHIPPED'
  | 'SHIPPED'
  | 'COMPLETED'
  | 'CANCELLED';

export interface SalesOrderSummary {
  id: string;
  factoryId: string;
  orderNumber: string;
  customerId: string;
  customerName?: string | null;
  status: SalesOrderStatus;
  totalAmount: number | null;
  orderDate: string;
  requiredDeliveryDate?: string | null;
  salesperson?: string | null;
  remark?: string | null;
  financeReviewedBy?: number | null;
  financeReviewedAt?: string | null;
  financeReviewNotes?: string | null;
  estimatedCost?: number | null;
  estimatedProfit?: number | null;
  createdAt: string;
  updatedAt?: string | null;
}

export interface LineCostBreakdown {
  productId: string | null;
  productName: string | null;
  quantity: number;
  unitPrice: number | null;
  lineAmount: number | null;
  bomStandardUnitCost: number | null;
  bomStandardLineCost: number | null;
  actualLineCost: number | null;
  /** SP3: 单位标准成本 (BOM 标准，@PriceSensitive — 非财务角色为 null) */
  standardCostPerUnit: number | null;
  /** SP3: 单位实际成本 (@PriceSensitive) */
  actualCostPerUnit: number | null;
  /** SP3: 行级成本偏差率 (实际 vs BOM 标准，正值=超支，@PriceSensitive) */
  variancePct: number | null;
  /** SP3: 是否在阈值内 (true=未超支，false=超支，null=数据不足) */
  belowThreshold: boolean | null;
}

export interface FinanceCostBreakdown {
  totalAmount: number | null;
  bomStandardCost: number | null;
  currentEstimatedCost: number | null;
  currentEstimatedProfit: number | null;
  actualCost: number | null;
  actualProfit: number | null;
  profitMarginEstimated: number | null;
  profitMarginActual: number | null;
  dataSourceHint: string | null;
  lines: LineCostBreakdown[];
  /** SP3: 整单成本偏差率 (实际 vs BOM 标准，@PriceSensitive) */
  variancePct: number | null;
  /** SP3: 整单成本偏差绝对值 (@PriceSensitive) */
  varianceAbsolute: number | null;
  /** SP3: 是否在工厂方差阈值内 (true=未超支，false=超支，null=数据不足) */
  belowThreshold: boolean | null;
  /** SP3: 超支时的告警信息 (null=未超支或数据不足) */
  alarmMessage: string | null;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

// ============================================================================
// API 方法 — baseURL = /api/mobile (request.ts:109)
// ============================================================================

/** 待财审销售单列表 (PENDING_FINANCE_REVIEW). 分页. */
export function listPendingFinanceReview(
  factoryId: string,
  params: { page?: number; size?: number } = {},
): Promise<ApiResponse<PageResponse<SalesOrderSummary>>> {
  return get<PageResponse<SalesOrderSummary>>(
    `/${factoryId}/sales/orders/by-status`,
    {
      params: {
        status: 'PENDING_FINANCE_REVIEW',
        page: params.page ?? 1,
        size: params.size ?? 20,
      },
    },
  );
}

/** 销售订单详情. */
export function getOrderDetail(
  factoryId: string,
  orderId: string,
): Promise<ApiResponse<SalesOrderSummary>> {
  return get<SalesOrderSummary>(`/${factoryId}/sales/orders/${orderId}`);
}

/** 财务成本核算 (Sprint4-H F-AR-1) — BOM 标准 + 预估 + 实际 + 利润对比. */
export function getOrderCostBreakdown(
  factoryId: string,
  orderId: string,
): Promise<ApiResponse<FinanceCostBreakdown>> {
  return get<FinanceCostBreakdown>(
    `/${factoryId}/sales/orders/${orderId}/cost-breakdown`,
  );
}

/**
 * 财务审核通过. 可选 estimatedCost (覆盖当前 SalesOrder.estimatedCost).
 * (PENDING_FINANCE_REVIEW → FINANCE_APPROVED, 触发供应链联动).
 */
export function financeApprove(
  factoryId: string,
  orderId: string,
  opts: { notes?: string; estimatedCost?: number } = {},
): Promise<ApiResponse<SalesOrderSummary>> {
  return post<SalesOrderSummary>(
    `/${factoryId}/sales/orders/${orderId}/finance-approve`,
    {
      notes: opts.notes ?? null,
      estimatedCost: opts.estimatedCost ?? null,
    },
  );
}

/** 财务驳回 (PENDING_FINANCE_REVIEW → FINANCE_REJECTED). notes 必填. */
export function financeReject(
  factoryId: string,
  orderId: string,
  notes: string,
): Promise<ApiResponse<SalesOrderSummary>> {
  return post<SalesOrderSummary>(
    `/${factoryId}/sales/orders/${orderId}/finance-reject`,
    { notes },
  );
}

// ============================================================================
// B3 售价趋势
// ============================================================================

/** 单条售价趋势记录. */
export interface SalesPriceTrendDTO {
  orderNumber: string;
  orderDate: string; // ISO date string, e.g. "2026-05-20"
  unitPrice: number | null;
  quantity: number | null;
  unit: string | null;
}

/**
 * B3 获取产品最近 N 笔成交售价 (财审辅助).
 *
 * @param factoryId     工厂 ID
 * @param productTypeId 产品类型 ID
 * @param limit         最多条数，默认 10
 */
export function getProductPriceTrend(
  factoryId: string,
  productTypeId: string,
  limit = 10,
): Promise<ApiResponse<SalesPriceTrendDTO[]>> {
  return get<SalesPriceTrendDTO[]>(`/${factoryId}/sales/orders/price-trend`, {
    params: { productTypeId, limit },
  });
}
