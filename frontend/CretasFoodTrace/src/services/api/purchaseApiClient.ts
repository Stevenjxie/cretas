import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

/**
 * 采购管理API客户端
 * 路径：/api/mobile/{factoryId}/purchase/*
 */

// ========== 类型定义 ==========

export interface PurchaseOrder {
  id: string;
  factoryId: string;
  orderNumber: string;
  supplierId: string;
  supplierName?: string;
  status:
    | 'DRAFT'
    | 'SUBMITTED'
    | 'APPROVED'
    | 'REJECTED'
    | 'PENDING_FINANCE_REVIEW'
    | 'FINANCE_APPROVED'
    | 'FINANCE_REJECTED'
    | 'PARTIAL_RECEIVED'
    | 'COMPLETED'
    | 'CANCELLED'
    | 'CLOSED';
  financeReviewedBy?: number;
  financeReviewedAt?: string;
  financeReviewNotes?: string;
  totalAmount: number;
  orderDate: string;
  expectedDeliveryDate?: string;
  remark?: string;
  createdBy?: string;
  approvedBy?: string;
  items?: PurchaseOrderItem[];
  receiveRecords?: PurchaseReceiveRecord[];
  createdAt: string;
  updatedAt?: string;
}

export interface PurchaseOrderItem {
  id: string;
  materialTypeId: string;
  materialTypeName?: string;
  quantity: number;
  receivedQuantity: number;
  unitPrice: number;
  unit: string;
  materialPackagingSpecId?: string;
  purchasePackageUnitSnapshot?: string;
  inventoryBaseUnitSnapshot?: string;
  packageToBaseFactorSnapshot?: number;
  inventoryQuantitySnapshot?: number;
  totalPrice: number;
  remark?: string;
}

export interface PurchaseReceiveRecord {
  id: string;
  receiveNumber: string;
  receiveDate: string;
  receivedBy?: string;
  status: string;
  remark?: string;
  items?: PurchaseReceiveItem[];
}

export interface PurchaseReceiveItem {
  id: string;
  materialTypeId: string;
  materialTypeName?: string;
  receivedQuantity: number;
  unit: string;
  batchNumber?: string;
}

export interface CreatePurchaseOrderRequest {
  supplierId: string;
  expectedDeliveryDate?: string;
  remark?: string;
  items: {
    materialTypeId: string;
    supplierMaterialId?: string;
    purchasePackagingSpecId?: string;
    materialPackagingSpecId?: string;
    quantity: number;
    unitPrice: number;
    unit: string;
  }[];
}

export interface CreateReceiveRecordRequest {
  /** 关联采购订单ID（可选，支持无单入库） */
  purchaseOrderId?: string;
  supplierId: string;
  receiveDate: string; // ISO date string: YYYY-MM-DD
  warehouseId?: string;
  remark?: string;
  items: {
    purchaseOrderItemId?: number;
    materialTypeId: string;
    materialPackagingSpecId?: string;
    materialName?: string;
    receivedQuantity: number;
    unit: string;
    unitPrice?: number;
    qcResult?: string;
    remark?: string;
  }[];
}

// ========== Sprint2-J P-FIN-1: 三价对比 + 财务审核 ==========

export interface MaterialPriceComparison {
  materialTypeId: string;
  materialName: string | null;
  materialCode: string | null;
  unit: string | null;
  bomStandardPrice: number | null;
  movingAvgPrice: number | null;
  currentPrice: number | null;
  varianceFromBom: number | null;
  varianceFromAvg: number | null;
  priceAlert: boolean;
  bomProductNames: string | null;
  dataSourceHint: string | null;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

// ========== 价格表类型定义 ==========

export type PriceType = 'PURCHASE_PRICE' | 'SELLING_PRICE' | 'TRANSFER_PRICE';

export interface PriceListItem {
  id: number;
  priceListId: string;
  materialTypeId: string | null;
  productTypeId: string | null;
  itemName: string | null;
  unit: string | null;
  standardPrice: number;
  minPrice: number | null;
  maxPrice: number | null;
  remark: string | null;
}

export interface PriceList {
  id: string;
  factoryId: string;
  name: string;
  priceType: PriceType;
  effectiveFrom: string;
  effectiveTo: string | null;
  isActive: boolean;
  createdBy: number | null;
  remark: string | null;
  items: PriceListItem[];
  createdAt: string;
  updatedAt: string | null;
}

// ========== API客户端类 ==========

class PurchaseApiClient {
  private getPath(factoryId?: string) {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/purchase`;
  }

  /** 获取采购单列表 */
  async getOrders(params?: { page?: number; size?: number; status?: string }, factoryId?: string): Promise<{ success: boolean; data: PageResponse<PurchaseOrder> }> {
    return apiClient.get(this.getPath(factoryId) + '/orders', { params });
  }

  /** 获取采购单详情 */
  async getOrder(orderId: string, factoryId?: string): Promise<{ success: boolean; data: PurchaseOrder }> {
    return apiClient.get(this.getPath(factoryId) + `/orders/${orderId}`);
  }

  /**
   * 按订单号查采购单详情 (PDF QR 扫码场景).
   *
   * 用法: 扫 PDF QR 拿到 orderNumber 字符串 (如 PO-20260514-001) → 调本接口
   * 拿订单 + 关联明细 → 跳入库收货页. 后端 GET /orders/by-number/{orderNumber}.
   */
  async getOrderByNumber(orderNumber: string, factoryId?: string): Promise<{ success: boolean; data: PurchaseOrder }> {
    return apiClient.get(this.getPath(factoryId) + `/orders/by-number/${encodeURIComponent(orderNumber)}`);
  }

  /** 创建采购单 */
  async createOrder(data: CreatePurchaseOrderRequest, factoryId?: string): Promise<{ success: boolean; data: PurchaseOrder }> {
    return apiClient.post(this.getPath(factoryId) + '/orders', data);
  }

  /** 提交审批 */
  async submitOrder(orderId: string, factoryId?: string): Promise<{ success: boolean; data: PurchaseOrder }> {
    return apiClient.post(this.getPath(factoryId) + `/orders/${orderId}/submit`);
  }

  /** 审批通过 */
  async approveOrder(orderId: string, factoryId?: string): Promise<{ success: boolean; data: PurchaseOrder }> {
    return apiClient.post(this.getPath(factoryId) + `/orders/${orderId}/approve`);
  }

  /** 驳回 */
  async rejectOrder(orderId: string, factoryId?: string): Promise<{ success: boolean; data: PurchaseOrder }> {
    return apiClient.post(this.getPath(factoryId) + `/orders/${orderId}/reject`);
  }

  /** 取消 */
  async cancelOrder(orderId: string, factoryId?: string): Promise<{ success: boolean; data: PurchaseOrder }> {
    return apiClient.post(this.getPath(factoryId) + `/orders/${orderId}/cancel`);
  }

  /** 编辑草稿采购单 */
  async updateOrder(orderId: string, data: CreatePurchaseOrderRequest, factoryId?: string): Promise<{ success: boolean; data: PurchaseOrder }> {
    return apiClient.put(this.getPath(factoryId) + `/orders/${orderId}`, data);
  }

  /** 按状态查询采购单列表 */
  async getOrdersByStatus(status: PurchaseOrder['status'], params?: { page?: number; size?: number }, factoryId?: string): Promise<{ success: boolean; data: PageResponse<PurchaseOrder> }> {
    return apiClient.get(this.getPath(factoryId) + '/orders/by-status', { params: { status, ...params } });
  }

  // ========== Sprint2-J P-FIN-1: 财务审核 ==========

  /** 提交财务审核 (从 APPROVED → PENDING_FINANCE_REVIEW; approveOrder 自动触发条件满足时也会直达此状态) */
  async submitForFinanceReview(orderId: string, factoryId?: string): Promise<{ success: boolean; data: PurchaseOrder }> {
    return apiClient.post(this.getPath(factoryId) + `/orders/${orderId}/submit-for-finance-review`);
  }

  /** 财务审核通过 (PENDING_FINANCE_REVIEW → FINANCE_APPROVED) */
  async financeApprove(orderId: string, notes?: string, factoryId?: string): Promise<{ success: boolean; data: PurchaseOrder }> {
    return apiClient.post(this.getPath(factoryId) + `/orders/${orderId}/finance-approve`, { notes });
  }

  /** 财务驳回 (PENDING_FINANCE_REVIEW → FINANCE_REJECTED). notes 必填. */
  async financeReject(orderId: string, notes: string, factoryId?: string): Promise<{ success: boolean; data: PurchaseOrder }> {
    return apiClient.post(this.getPath(factoryId) + `/orders/${orderId}/finance-reject`, { notes });
  }

  /** 采购单三价对比 (priceAlert=true → 红行) */
  async getOrderPriceComparison(orderId: string, factoryId?: string): Promise<{ success: boolean; data: MaterialPriceComparison[] }> {
    return apiClient.get(this.getPath(factoryId) + `/orders/${orderId}/price-comparison`);
  }

  // ========== 入库单 (receives) ==========

  /** 创建入库单 */
  async createReceive(data: CreateReceiveRecordRequest, factoryId?: string): Promise<{ success: boolean; data: PurchaseReceiveRecord }> {
    return apiClient.post(this.getPath(factoryId) + '/receives', data);
  }

  /** 入库单列表 */
  async getReceives(params?: { page?: number; size?: number }, factoryId?: string): Promise<{ success: boolean; data: PageResponse<PurchaseReceiveRecord> }> {
    return apiClient.get(this.getPath(factoryId) + '/receives', { params });
  }

  /** 入库单详情 */
  async getReceive(receiveId: string, factoryId?: string): Promise<{ success: boolean; data: PurchaseReceiveRecord }> {
    return apiClient.get(this.getPath(factoryId) + `/receives/${receiveId}`);
  }

  /** 确认入库（触发物料批次创建） */
  async confirmReceive(receiveId: string, factoryId?: string): Promise<{ success: boolean; data: PurchaseReceiveRecord }> {
    return apiClient.post(this.getPath(factoryId) + `/receives/${receiveId}/confirm`);
  }

  /** 按采购单查询关联入库记录 */
  async getReceivesByOrder(orderId: string, factoryId?: string): Promise<{ success: boolean; data: PurchaseReceiveRecord[] }> {
    return apiClient.get(this.getPath(factoryId) + `/receives/by-order/${orderId}`);
  }

  // ========== 统计 ==========

  /** 采购统计数据 */
  async getStatistics(factoryId?: string): Promise<{ success: boolean; data: Record<string, unknown> }> {
    return apiClient.get(this.getPath(factoryId) + '/statistics');
  }

  // ========== 价格表 (price-lists) ==========

  /** 获取价格表列表（分页） */
  async getPriceLists(
    params?: { page?: number; size?: number },
    factoryId?: string,
  ): Promise<{ success: boolean; data: PageResponse<PriceList> }> {
    const basePath = this.getPriceListPath(factoryId);
    return apiClient.get(basePath, { params });
  }

  /** 获取当前生效的价格表列表 */
  async getEffectivePriceLists(factoryId?: string): Promise<{ success: boolean; data: PriceList[] }> {
    const basePath = this.getPriceListPath(factoryId);
    return apiClient.get(basePath + '/effective');
  }

  /** 获取价格表详情 */
  async getPriceList(
    priceListId: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: PriceList }> {
    const basePath = this.getPriceListPath(factoryId);
    return apiClient.get(basePath + `/${priceListId}`);
  }

  /** 删除价格表 */
  async deletePriceList(
    priceListId: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: null }> {
    const basePath = this.getPriceListPath(factoryId);
    return apiClient.delete(basePath + `/${priceListId}`);
  }

  private getPriceListPath(factoryId?: string): string {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/price-lists`;
  }
}

export const purchaseApiClient = new PurchaseApiClient();
