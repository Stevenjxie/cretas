import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

/**
 * 销售管理API客户端
 * 路径：/api/mobile/{factoryId}/sales/*
 */

// ========== 类型定义 ==========

export interface SalesOrder {
  id: string;
  factoryId: string;
  orderNumber: string;
  customerId: string;
  customerName?: string;
  status:
    | 'DRAFT'
    | 'CONFIRMED'
    | 'PENDING_FINANCE_REVIEW'
    | 'FINANCE_APPROVED'
    | 'FINANCE_REJECTED'
    | 'PROCESSING'
    | 'PARTIAL_DELIVERED'
    | 'COMPLETED'
    | 'CANCELLED';
  totalAmount: number;
  orderDate: string;
  expectedDeliveryDate?: string;
  remark?: string;
  createdBy?: string;
  items?: SalesOrderItem[];
  deliveryRecords?: SalesDeliveryRecord[];
  // Sprint3-G S-LOCK-1: 后端 SalesOrder @Transient @JsonProperty 派生 (聚合 items[]).
  // chip 显示 锁/备/缺. NOT @PriceSensitive — inventory 数据非价格.
  lockedQty?: number;
  reservedQty?: number;
  shortageQty?: number;
  createdAt: string;
  updatedAt?: string;
}

export interface SalesOrderItem {
  id: string;
  productTypeId: string;
  productTypeName?: string;
  quantity: number;
  deliveredQuantity: number;
  unitPrice: number;
  unit: string;
  totalPrice: number;
  // Sprint3-G S-LOCK-1: 行级 lockedQty / reservedQty 来自 sales_order_items 列;
  // shortageQty 后端 @Transient getter (quantity - reservedQty, clamp ≥0).
  lockedQty?: number;
  reservedQty?: number;
  shortageQty?: number;
}

export interface SalesDeliveryRecord {
  id: string;
  deliveryNumber: string;
  deliveryDate: string;
  deliveredBy?: string;
  status: string;
  remark?: string;
  items?: SalesDeliveryItem[];
}

export interface SalesDeliveryItem {
  id: string;
  productTypeId: string;
  productTypeName?: string;
  deliveredQuantity: number;
  unit: string;
  batchNumber?: string;
}

export interface FinishedGoodsBatch {
  id: string;
  factoryId: string;
  batchNumber: string;
  productTypeId: string;
  productTypeName?: string;
  totalQuantity: number;
  availableQuantity: number;
  reservedQuantity: number;
  unit: string;
  productionDate: string;
  expiryDate?: string;
  unitCost?: number;
  storageLocation?: string;
  createdAt: string;
}

export interface CreateSalesOrderRequest {
  customerId: string;
  orderDate?: string;
  requiredDeliveryDate?: string;
  expectedDeliveryDate?: string;
  remark?: string;
  items: {
    productTypeId: string;
    productName?: string;
    quantity: number;
    unitPrice: number;
    unit: string;
    taxRate?: number;
  }[];
}

export interface CreateDeliveryRequest {
  /** 关联的销售订单ID（可选，支持无单出库） */
  salesOrderId?: string;
  customerId: string;
  deliveryDate: string;
  deliveryAddress?: string;
  logisticsCompany?: string;
  trackingNumber?: string;
  remark?: string;
  items: CreateDeliveryItemRequest[];
}

export interface CreateDeliveryItemRequest {
  productTypeId: string;
  productName?: string;
  deliveredQuantity: number;
  unit: string;
  unitPrice?: number;
  remark?: string;
}

export interface SalesStatistics {
  [key: string]: unknown;
}

// ========== Sprint 2 Track E (S-MRP-1 / N31) — 缺料分析报告 ==========

export interface MaterialNeed {
  materialTypeId: string;
  materialTypeName?: string;
  requiredQuantity: number;
  wastageRate?: number;
  sourceUnit?: string;
}

export interface FgLineItem {
  productTypeId: string;
  productTypeName?: string;
  requiredQuantity: number;
  availableQuantity: number;
  shortfallQuantity: number;
}

export interface MaterialShortageItem {
  materialTypeId: string;
  materialTypeName?: string;
  requiredQuantity: number;
  availableQuantity: number;
  shortfallQuantity: number;
}

export interface ShortagePriceComparison {
  bomStandardPrice?: number;
  movingAvgPrice?: number;
  currentPrice?: number;
  priceAlert?: string;
}

export interface ShortageProcurementSuggestion {
  materialId: string;
  materialName?: string;
  suggestedQty: number;
  unit?: string;
  suggestedSupplierId?: string;
  suggestedSupplierName?: string;
  estimatedPrice?: number;
  estimatedTotal?: number;
  leadDays?: number;
  priceComparison?: ShortagePriceComparison;
}

export interface ShortageProductionSuggestion {
  productId: string;
  productName?: string;
  plannedQty: number;
  workProcessIds?: string[];
  workProcessNames?: string[];
  startDate?: string;
  endDate?: string;
}

export type ShortageAnalysisStatus = 'PENDING' | 'COMPLETED' | 'FAILED' | 'NOT_AVAILABLE';

export interface ShortageReport {
  id?: string;
  factoryId: string;
  salesOrderId: string;
  analysisStatus: ShortageAnalysisStatus;
  totalRequired?: MaterialNeed[];
  /** 后端 column 名 = available, 实际承载 FG 行项目匹配 (LineItemMatch). */
  available?: FgLineItem[];
  shortage?: MaterialShortageItem[];
  procurementSuggestions?: ShortageProcurementSuggestion[];
  productionSuggestions?: ShortageProductionSuggestion[];
  analysisSummary?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

// ========== API客户端类 ==========

class SalesApiClient {
  private getPath(factoryId?: string) {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/sales`;
  }

  // ==================== 销售订单 ====================

  /** 获取销售单列表 */
  async getOrders(params?: { page?: number; size?: number }, factoryId?: string): Promise<{ success: boolean; data: PageResponse<SalesOrder> }> {
    return apiClient.get(this.getPath(factoryId) + '/orders', { params });
  }

  /** 按状态查询销售单列表 */
  async getOrdersByStatus(status: SalesOrder['status'], params?: { page?: number; size?: number }, factoryId?: string): Promise<{ success: boolean; data: PageResponse<SalesOrder> }> {
    return apiClient.get(this.getPath(factoryId) + '/orders/by-status', { params: { status, ...params } });
  }

  /** 获取销售单详情 */
  async getOrder(orderId: string, factoryId?: string): Promise<{ success: boolean; data: SalesOrder }> {
    return apiClient.get(this.getPath(factoryId) + `/orders/${orderId}`);
  }

  /** 创建销售单 */
  async createOrder(data: CreateSalesOrderRequest, factoryId?: string): Promise<{ success: boolean; data: SalesOrder }> {
    return apiClient.post(this.getPath(factoryId) + '/orders', data);
  }

  /** 确认销售单 */
  async confirmOrder(orderId: string, factoryId?: string): Promise<{ success: boolean; data: SalesOrder }> {
    return apiClient.post(this.getPath(factoryId) + `/orders/${orderId}/confirm`);
  }

  /** 取消销售单 */
  async cancelOrder(orderId: string, factoryId?: string): Promise<{ success: boolean; data: SalesOrder }> {
    return apiClient.post(this.getPath(factoryId) + `/orders/${orderId}/cancel`);
  }

  // ==================== 发货/出库 ====================

  /** 创建发货单 (POST /deliveries) */
  async createDelivery(data: CreateDeliveryRequest, factoryId?: string): Promise<{ success: boolean; data: SalesDeliveryRecord }> {
    return apiClient.post(this.getPath(factoryId) + '/deliveries', data);
  }

  /** 获取发货单列表 (GET /deliveries) */
  async getDeliveries(params?: { page?: number; size?: number }, factoryId?: string): Promise<{ success: boolean; data: PageResponse<SalesDeliveryRecord> }> {
    return apiClient.get(this.getPath(factoryId) + '/deliveries', { params });
  }

  /** 获取发货单详情 (GET /deliveries/{deliveryId}) */
  async getDelivery(deliveryId: string, factoryId?: string): Promise<{ success: boolean; data: SalesDeliveryRecord }> {
    return apiClient.get(this.getPath(factoryId) + `/deliveries/${deliveryId}`);
  }

  /** 发货确认，扣减成品库存 (POST /deliveries/{deliveryId}/ship) */
  async shipDelivery(deliveryId: string, factoryId?: string): Promise<{ success: boolean; data: SalesDeliveryRecord }> {
    return apiClient.post(this.getPath(factoryId) + `/deliveries/${deliveryId}/ship`);
  }

  /** 签收确认 (POST /deliveries/{deliveryId}/delivered) */
  async confirmDelivered(deliveryId: string, factoryId?: string): Promise<{ success: boolean; data: SalesDeliveryRecord }> {
    return apiClient.post(this.getPath(factoryId) + `/deliveries/${deliveryId}/delivered`);
  }

  /** 按销售订单查询发货记录 (GET /deliveries/by-order/{orderId}) */
  async getDeliveriesByOrder(orderId: string, factoryId?: string): Promise<{ success: boolean; data: SalesDeliveryRecord[] }> {
    return apiClient.get(this.getPath(factoryId) + `/deliveries/by-order/${orderId}`);
  }

  // ==================== 成品库存 ====================

  /** 获取成品库存列表 */
  async getFinishedGoods(params?: { page?: number; size?: number }, factoryId?: string): Promise<{ success: boolean; data: PageResponse<FinishedGoodsBatch> }> {
    return apiClient.get(this.getPath(factoryId) + '/finished-goods', { params });
  }

  /** 查询可用成品批次（按产品） */
  async getAvailableBatches(productTypeId: string, factoryId?: string): Promise<{ success: boolean; data: FinishedGoodsBatch[] }> {
    return apiClient.get(this.getPath(factoryId) + '/finished-goods/available', { params: { productTypeId } });
  }

  // ==================== 统计 ====================

  /** 销售统计数据 */
  async getStatistics(factoryId?: string): Promise<{ success: boolean; data: SalesStatistics }> {
    return apiClient.get(this.getPath(factoryId) + '/statistics');
  }

  // ==================== 缺料分析 (Sprint 2 Track E / N31) ====================

  /**
   * 查询销售订单的缺料分析报告 (S-MRP-1)。
   * 报告由后端 SalesOrderShortageReportListener 在财务审核通过后异步生成。
   * 未生成时返回 analysisStatus='NOT_AVAILABLE' (HTTP 200) — 前端展示占位。
   */
  async getShortageReport(orderId: string, factoryId?: string): Promise<{ success: boolean; data: ShortageReport }> {
    return apiClient.get(this.getPath(factoryId) + `/orders/${orderId}/shortage-report`);
  }
}

export const salesApiClient = new SalesApiClient();
