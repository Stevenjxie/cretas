import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

/**
 * 退货管理API客户端
 * 路径：/api/mobile/{factoryId}/return-orders/*
 */

// ========== 类型定义 ==========

export type ReturnType = 'PURCHASE_RETURN' | 'SALES_RETURN';
// 六扇门 Tier0 #16: 退货财审门. 状态机 APPROVED → FINANCE_APPROVED → COMPLETED.
export type ReturnOrderStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'FINANCE_APPROVED' | 'REJECTED' | 'PROCESSING' | 'COMPLETED';

export interface ReturnOrder {
  id: string;
  factoryId: string;
  returnNumber: string;
  returnType: ReturnType;
  status: ReturnOrderStatus;
  counterpartyId: string;
  sourceOrderId?: string;
  returnDate: string;
  totalAmount: number;
  reason?: string;
  createdBy?: number;
  approvedBy?: number;
  approvedAt?: string;
  remark?: string;
  items?: ReturnOrderItem[];
  createdAt: string;
  updatedAt?: string;
}

export interface ReturnOrderItem {
  id: number;
  materialTypeId?: string;
  productTypeId?: string;
  itemName?: string;
  quantity: number;
  unitPrice?: number;
  lineAmount?: number;
  batchNumber?: string;
  reason?: string;
}

export interface CreateReturnOrderRequest {
  returnType: ReturnType;
  counterpartyId: string;
  sourceOrderId?: string;
  returnDate: string;
  reason?: string;
  remark?: string;
  items: {
    materialTypeId?: string;
    productTypeId?: string;
    itemName?: string;
    quantity: number;
    unitPrice?: number;
    batchNumber?: string;
    reason?: string;
  }[];
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

// ========== API客户端类 ==========

class ReturnOrderApiClient {
  private getPath(factoryId?: string) {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/return-orders`;
  }

  /** 创建退货单 */
  async createReturnOrder(data: CreateReturnOrderRequest, factoryId?: string): Promise<{ success: boolean; data: ReturnOrder }> {
    return apiClient.post(this.getPath(factoryId), data);
  }

  /** 退货单列表 */
  async getReturnOrders(params?: {
    returnType?: ReturnType;
    status?: ReturnOrderStatus;
    page?: number;
    size?: number;
  }, factoryId?: string): Promise<{ success: boolean; data: PageResponse<ReturnOrder> }> {
    return apiClient.get(this.getPath(factoryId), { params });
  }

  /** 退货单详情 */
  async getReturnOrder(returnOrderId: string, factoryId?: string): Promise<{ success: boolean; data: ReturnOrder }> {
    return apiClient.get(this.getPath(factoryId) + `/${returnOrderId}`);
  }

  /** 提交审批 */
  async submitReturnOrder(returnOrderId: string, factoryId?: string): Promise<{ success: boolean; data: ReturnOrder }> {
    return apiClient.post(this.getPath(factoryId) + `/${returnOrderId}/submit`);
  }

  /** 审批通过 */
  async approveReturnOrder(returnOrderId: string, factoryId?: string): Promise<{ success: boolean; data: ReturnOrder }> {
    return apiClient.post(this.getPath(factoryId) + `/${returnOrderId}/approve`);
  }

  /**
   * 财务审批 (六扇门 Tier0 #16): APPROVED → FINANCE_APPROVED.
   * 退货跟钱有关，业务审批后必须先经财务审批才能完成。仅财务角色 (finance:read_write) 可调用。
   */
  async financeApproveReturnOrder(returnOrderId: string, factoryId?: string): Promise<{ success: boolean; data: ReturnOrder }> {
    return apiClient.post(this.getPath(factoryId) + `/${returnOrderId}/finance-approve`);
  }

  /** 驳回 */
  async rejectReturnOrder(returnOrderId: string, factoryId?: string): Promise<{ success: boolean; data: ReturnOrder }> {
    return apiClient.post(this.getPath(factoryId) + `/${returnOrderId}/reject`);
  }

  /** 完成 */
  async completeReturnOrder(returnOrderId: string, factoryId?: string): Promise<{ success: boolean; data: ReturnOrder }> {
    return apiClient.post(this.getPath(factoryId) + `/${returnOrderId}/complete`);
  }

  /** 退货统计 */
  async getStatistics(factoryId?: string): Promise<{ success: boolean; data: Record<string, unknown> }> {
    return apiClient.get(this.getPath(factoryId) + '/statistics');
  }
}

export const returnOrderApiClient = new ReturnOrderApiClient();
