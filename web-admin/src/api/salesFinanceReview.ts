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
  /**
   * P1 #32: 委外加工费独立科目 (@PriceSensitive).
   * 六扇门有委外工序时此字段单列委外加工成本.
   * 当前 WorkProcess/WorkProcessTask 无 is_outsourced 列 → 恒为 null (诚实占位).
   * 待后端 WorkProcess 接入委外费用数据后自动填充.
   */
  processingFee: number | null;
  /**
   * SP12: 实际成本三分拆分 (材料逐料 + 人工 + 制费).
   * 来自生产真实领料 (MaterialConsumption) + 报工 (批次 laborCost/equipmentCost).
   * 诚实 null: 订单未投产 / 未领料 / 未报工时该对象为 null 或各组分 null.
   */
  actualCostSplit: ActualCostSplit | null;
}

/** SP12: 逐原料/辅料/包材实际领料明细 (来自 MaterialConsumption 按物料聚合). */
export interface MaterialCostLine {
  materialTypeId: string | null;
  materialName: string | null;
  category: string | null;
  /** 实际领料用量合计 (Σ quantity). */
  actualQuantity: number | null;
  unit: string | null;
  /** 移动均价单价 (= amount / actualQuantity, @PriceSensitive). */
  unitPrice: number | null;
  /** 该物料实际金额合计 (Σ totalCost, @PriceSensitive). */
  amount: number | null;
}

/**
 * SP12: 实际成本三分拆分 (材料 / 人工 / 制费).
 * 金额字段 @PriceSensitive — 非财务/管理角色脱敏为 null.
 */
export interface ActualCostSplit {
  /** 材料成本合计 (= Σ materials[].amount). 无领料时 null. */
  materialCost: number | null;
  /** 人工成本合计 (= Σ 关联批次 laborCost). 无报工时 null. */
  laborCost: number | null;
  /** 制费合计 (= Σ 关联批次 equipmentCost + otherCost). 无数据时 null. */
  overheadCost: number | null;
  /** 实际成本合计 (= 非 null 组分之和). 全 null 时 null. */
  totalActualCost: number | null;
  /** 关联到的生产批次数 (0=未投产). */
  batchCount: number | null;
  /** 逐料实际领料明细. */
  materials: MaterialCostLine[];
  /** 数据缺失提示 (null=数据完整). */
  dataSourceHint: string | null;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

/**
 * 运营报价单摘要 — 三层价格链路: 研发预估价 (OperationalQuote.unitPrice).
 *
 * 数据来源: GET /quotes/active?customerId=&productTypeId=
 * 状态 APPROVED + 未过期 + 同客户同产品的最新有效报价.
 * unitPrice = @PriceSensitive, 仅财务/管理角色可见 (后端脱敏 null).
 *
 * 安全约束 (镜像 EstimatePriceCheckResult doc):
 *   此接口只在财审页使用 (RBAC = finance:read_write / finance:read).
 *   销售/仓管角色不应看到 unitPrice 绝对值, 因此该端点需要 finance 权限.
 */
export interface OperationalQuoteSummary {
  id: string;
  quoteNo: string;
  productTypeId: string | null;
  /** 运营报价建议售价 (@PriceSensitive — 非财务角色为 null). */
  unitPrice: number | null;
  unit: string | null;
  status: string;
  validUntil: string | null;
  approvedAt: string | null;
  approverName: string | null;
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

// ============================================================================
// 三层价格: 运营报价 (研发预估价)
// ============================================================================

/**
 * 获取指定产品 + 客户的有效运营报价 (APPROVED + 未过期).
 * 用于财审页三层价格同屏对比:
 *   ① 研发预估价 (OperationalQuote.unitPrice) ← 本端点
 *   ② 下单价     (SalesOrderItem.unitPrice)
 *   ③ 实际成本价  (LineCostBreakdown.actualCostPerUnit)
 *
 * 端点: GET /quotes/active?customerId=&productTypeId=
 * 权限: 财审页已有 finance:read_write / finance:read — 不额外要求.
 */
export function getActiveQuotes(
  factoryId: string,
  customerId: string,
  productTypeId: string,
): Promise<ApiResponse<OperationalQuoteSummary[]>> {
  return get<OperationalQuoteSummary[]>(`/${factoryId}/quotes/active`, {
    params: { customerId, productTypeId },
  });
}
