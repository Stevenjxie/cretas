import { isAxiosError } from 'axios';
import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

/**
 * 出货记录API客户端
 * 路径：/api/mobile/{factoryId}/shipments/*
 *
 * 业务场景：管理产品出货记录，追踪物流状态
 */

// ========== 类型定义 ==========

export interface ShipmentRecord {
  id: string;
  factoryId: string;
  shipmentNumber: string;
  customerId: string;
  orderNumber?: string;
  productName: string;
  quantity: number;
  unit: string;
  unitPrice?: number;
  totalAmount?: number;
  shipmentDate: string;
  deliveryAddress: string;
  logisticsCompany?: string;
  trackingNumber?: string;
  status: 'pending' | 'shipped' | 'delivered' | 'returned';
  notes?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface ShipmentStats {
  total: number;
  pending: number;
  shipped: number;
  delivered: number;
  returned: number;
}

// ========== API客户端类 ==========

class ShipmentApiClient {
  private getPath(factoryId?: string) {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/shipments`;
  }

  /**
   * 1. 获取出货记录列表（分页）
   * GET /api/mobile/{factoryId}/shipments
   */
  async getShipments(params?: {
    factoryId?: string;
    page?: number;
    size?: number;
    status?: string;
  }): Promise<{ data: ShipmentRecord[]; totalElements: number; totalPages: number }> {
    const { factoryId, ...queryParams } = params || {};
    const apiResponse = await apiClient.get<any>(
      `${this.getPath(factoryId)}`,
      { params: queryParams }
    );

    // 处理后端返回格式
    // 格式1: { data: ShipmentRecord[] } (直接数组包装)
    if (apiResponse.data && Array.isArray(apiResponse.data)) {
      return {
        data: apiResponse.data,
        totalElements: apiResponse.totalElements || apiResponse.data.length,
        totalPages: apiResponse.totalPages || 1
      };
    }
    // 格式2: Spring Boot Page { content: [...], totalElements, totalPages }
    if (apiResponse.content && Array.isArray(apiResponse.content)) {
      return {
        data: apiResponse.content,
        totalElements: apiResponse.totalElements || apiResponse.content.length,
        totalPages: apiResponse.totalPages || 1
      };
    }
    // 格式3: 直接数组
    if (Array.isArray(apiResponse)) {
      return { data: apiResponse, totalElements: apiResponse.length, totalPages: 1 };
    }
    // 格式4: { success: true, data: { content: [...] } } (统一响应 + Spring Page)
    if (apiResponse.data && apiResponse.data.content && Array.isArray(apiResponse.data.content)) {
      return {
        data: apiResponse.data.content,
        totalElements: apiResponse.data.totalElements || apiResponse.data.content.length,
        totalPages: apiResponse.data.totalPages || 1
      };
    }

    console.warn('[ShipmentApiClient] 未知的响应格式:', apiResponse);
    return { data: [], totalElements: 0, totalPages: 0 };
  }

  /**
   * 2. 获取单个出货记录
   * GET /api/mobile/{factoryId}/shipments/{id}
   */
  async getShipmentById(id: string, factoryId?: string): Promise<ShipmentRecord> {
    const response = await apiClient.get<any>(
      `${this.getPath(factoryId)}/${id}`
    );
    return response.data || response;
  }

  /**
   * 7. 按客户查询出货记录
   * GET /api/mobile/{factoryId}/shipments/customer/{customerId}
   */
  async getShipmentsByCustomer(
    customerId: string,
    factoryId?: string
  ): Promise<ShipmentRecord[]> {
    const response = await apiClient.get<any>(
      `${this.getPath(factoryId)}/customer/${customerId}`
    );
    return response.data || response || [];
  }

  /**
   * 8. 按日期范围查询
   * GET /api/mobile/{factoryId}/shipments/date-range
   */
  async getShipmentsByDateRange(params: {
    startDate: string;
    endDate: string;
    factoryId?: string;
  }): Promise<ShipmentRecord[]> {
    const { factoryId, ...queryParams } = params;
    const response = await apiClient.get<any>(
      `${this.getPath(factoryId)}/date-range`,
      { params: queryParams }
    );
    return response.data || response || [];
  }

  /**
   * 9. 物流追踪查询
   * GET /api/mobile/{factoryId}/shipments/tracking/{trackingNumber}
   */
  async getShipmentByTrackingNumber(
    trackingNumber: string,
    factoryId?: string
  ): Promise<ShipmentRecord | null> {
    try {
      const response = await apiClient.get<any>(
        `${this.getPath(factoryId)}/tracking/${trackingNumber}`
      );
      return response.data || response;
    } catch (error) {
      if (isAxiosError(error) && error.response?.status === 404) {
        return null;
      }
      throw error;
    }
  }

  /**
   * 10. 获取出货统计
   * GET /api/mobile/{factoryId}/shipments/stats
   */
  async getShipmentStats(factoryId?: string): Promise<ShipmentStats> {
    const response = await apiClient.get<any>(
      `${this.getPath(factoryId)}/stats`
    );
    return response.data || response;
  }

}

export const shipmentApiClient = new ShipmentApiClient();
export default shipmentApiClient;
