import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

/**
 * 调拨管理API客户端
 * 路径：/api/mobile/{factoryId}/transfers/*
 */

// ========== 类型定义 ==========

export interface InternalTransfer {
  id: string;
  transferNumber: string;
  transferType: 'HQ_TO_BRANCH' | 'BRANCH_TO_BRANCH' | 'BRANCH_TO_HQ';
  sourceFactoryId: string;
  targetFactoryId: string;
  sourceFactory?: { id: string; name: string };
  targetFactory?: { id: string; name: string };
  status: 'DRAFT' | 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'SHIPPED' | 'RECEIVED' | 'CONFIRMED' | 'CANCELLED';
  totalAmount: number;
  transferDate: string;
  expectedArrivalDate?: string;
  requestedBy?: string;
  approvedBy?: string;
  remark?: string;
  items?: TransferItem[];
  createdAt: string;
  updatedAt?: string;
}

export interface TransferItem {
  id: string;
  itemType: 'RAW_MATERIAL' | 'FINISHED_GOODS';
  materialTypeId?: string;
  productTypeId?: string;
  materialTypeName?: string;
  productTypeName?: string;
  quantity: number;
  receivedQuantity: number;
  unitPrice: number;
  unit: string;
  materialPackagingSpecId?: string;
  packageQuantitySnapshot?: number;
  packageUnitSnapshot?: string;
  inventoryBaseUnitSnapshot?: string;
  packageToBaseFactorSnapshot?: number;
}

export interface CreateTransferRequest {
  transferType: string;
  targetFactoryId: string;
  expectedArrivalDate?: string;
  remark?: string;
  items: {
    itemType: string;
    materialTypeId?: string;
    productTypeId?: string;
    materialPackagingSpecId?: string;
    quantity: number;
    unitPrice: number;
    unit: string;
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

class TransferApiClient {
  private getPath(factoryId?: string) {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/transfers`;
  }

  /** 获取调拨单列表 */
  async getTransfers(params?: { page?: number; size?: number; status?: string; direction?: string }, factoryId?: string): Promise<{ success: boolean; data: PageResponse<InternalTransfer> }> {
    return apiClient.get(this.getPath(factoryId), { params });
  }

  /** 获取调拨单详情 */
  async getTransfer(transferId: string, factoryId?: string): Promise<{ success: boolean; data: InternalTransfer }> {
    return apiClient.get(this.getPath(factoryId) + `/${transferId}`);
  }

  /** 创建调拨单 */
  async createTransfer(data: CreateTransferRequest, factoryId?: string): Promise<{ success: boolean; data: InternalTransfer }> {
    return apiClient.post(this.getPath(factoryId), data);
  }

  /** 提交申请 */
  async requestTransfer(transferId: string, factoryId?: string): Promise<{ success: boolean; data: InternalTransfer }> {
    return apiClient.post(this.getPath(factoryId) + `/${transferId}/request`);
  }

  /** 审批通过 */
  async approveTransfer(transferId: string, factoryId?: string): Promise<{ success: boolean; data: InternalTransfer }> {
    return apiClient.post(this.getPath(factoryId) + `/${transferId}/approve`);
  }

  /** 驳回 */
  async rejectTransfer(transferId: string, factoryId?: string): Promise<{ success: boolean; data: InternalTransfer }> {
    return apiClient.post(this.getPath(factoryId) + `/${transferId}/reject`);
  }

  /** 确认发运 */
  async shipTransfer(transferId: string, factoryId?: string): Promise<{ success: boolean; data: InternalTransfer }> {
    return apiClient.post(this.getPath(factoryId) + `/${transferId}/ship`);
  }

  /** 确认签收 */
  async receiveTransfer(transferId: string, factoryId?: string): Promise<{ success: boolean; data: InternalTransfer }> {
    return apiClient.post(this.getPath(factoryId) + `/${transferId}/receive`);
  }

  /** 确认入库 */
  async confirmTransfer(transferId: string, factoryId?: string): Promise<{ success: boolean; data: InternalTransfer }> {
    return apiClient.post(this.getPath(factoryId) + `/${transferId}/confirm`);
  }

  /** 取消 */
  async cancelTransfer(transferId: string, factoryId?: string): Promise<{ success: boolean; data: InternalTransfer }> {
    return apiClient.post(this.getPath(factoryId) + `/${transferId}/cancel`);
  }
}

export const transferApiClient = new TransferApiClient();
