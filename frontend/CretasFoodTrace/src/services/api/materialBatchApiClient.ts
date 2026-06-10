import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

/**
 * 原材料批次管理API客户端
 * 总计22个API - 路径：/api/mobile/{factoryId}/material-batches/*
 */

/**
 * 后端统一响应格式
 */
export interface ApiResponse<T> {
  success: boolean;
  code: number;
  message: string;
  data: T;
}

export interface MaterialBatch {
  id: string;
  batchNumber: string;
  factoryId: string;
  materialTypeId: string;
  materialName?: string;        // 原料名称
  materialCode?: string;        // 原料代码
  materialCategory?: string;    // 原料类别
  storageType?: 'fresh' | 'frozen' | 'dry';  // 存储类型(来自原料类型)
  inboundQuantity: number;
  remainingQuantity: number;
  reservedQuantity: number;
  usedQuantity: number;
  unitPrice: number;
  totalCost: number;
  supplierId: string;
  supplierName?: string;        // 供应商名称
  inboundDate: string;
  expiryDate?: string;
  productionDate?: string;
  status: 'available' | 'reserved' | 'depleted' | 'expired' | 'fresh' | 'frozen';
  qualityGrade?: string;
  storageLocation?: string;
  notes?: string;
  movingAvgPrice?: number;      // 移动均价
  factoryNumber?: string | null;  // SP4: 厂号 (VARCHAR 100)
  originPlace?: string | null;    // SP4: 产地 (VARCHAR 200)
  createdAt: string;
  updatedAt?: string;
  createdByName?: string;       // 创建人姓名
}

export interface ConvertToFrozenRequest {
  convertedBy: number;
  convertedDate: string;
  storageLocation: string;
  notes?: string;
}

export interface UndoFrozenRequest {
  operatorId: number;
  reason: string;
}

// 库存预警综合接口返回的单条预警
export interface InventoryAlert {
  alertType: 'LOW_STOCK' | 'EXPIRING' | 'EXPIRED';
  batchId: string | null;
  materialTypeName: string;
  batchNumber: string | null;
  currentQuantity: number | null;
  minQuantity: number | null;
  expiryDate: string | null;
  daysUntilExpiry: number | null;
  storageLocation: string | null;
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
}

class MaterialBatchApiClient {
  private getPath(factoryId?: string) {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/material-batches`;
  }

  // 1. 获取原材料批次列表（分页）
  async getMaterialBatches(params?: { factoryId?: string; [key: string]: any }): Promise<ApiResponse<{ content: MaterialBatch[]; totalElements: number; totalPages: number }>> {
    const { factoryId, ...query } = params || {};
    return await apiClient.get(this.getPath(factoryId), { params: query });
  }

  // 2. 创建原材料批次
  async createBatch(data: any, factoryId?: string): Promise<ApiResponse<MaterialBatch>> {
    return await apiClient.post(this.getPath(factoryId), data);
  }

  // 3. 批量创建材料批次
  async batchCreate(batches: any[], factoryId?: string): Promise<ApiResponse<MaterialBatch[]>> {
    return await apiClient.post(`${this.getPath(factoryId)}/batch`, { batches });
  }

  // 4. 获取原材料批次详情
  async getBatchById(batchId: string, factoryId?: string): Promise<{ success: boolean; data: MaterialBatch; message?: string }> {
    return await apiClient.get(`${this.getPath(factoryId)}/${batchId}`);
  }

  // 5. 更新原材料批次
  async updateBatch(batchId: string, data: any, factoryId?: string): Promise<ApiResponse<MaterialBatch>> {
    return await apiClient.put(`${this.getPath(factoryId)}/${batchId}`, data);
  }

  // 6. 删除原材料批次
  async deleteBatch(batchId: string, factoryId?: string): Promise<ApiResponse<void>> {
    return await apiClient.delete(`${this.getPath(factoryId)}/${batchId}`);
  }

  // 7. 预留批次材料（创建生产计划时）
  async reserveBatch(batchId: string, quantity: number, productionPlanId: number, factoryId?: string): Promise<ApiResponse<MaterialBatch>> {
    return await apiClient.post(`${this.getPath(factoryId)}/${batchId}/reserve`, null, {
      params: { quantity, productionPlanId }
    });
  }

  // 8. 释放预留材料（取消生产计划时）
  async releaseBatch(batchId: string, quantity: number, productionPlanId: number, factoryId?: string): Promise<ApiResponse<MaterialBatch>> {
    return await apiClient.post(`${this.getPath(factoryId)}/${batchId}/release`, null, {
      params: { quantity, productionPlanId }
    });
  }

  // 9. 消耗批次材料（生产完成时）- 新增
  async consumeBatch(batchId: string, quantity: number, productionPlanId: number, factoryId?: string): Promise<ApiResponse<MaterialBatch>> {
    return await apiClient.post(`${this.getPath(factoryId)}/${batchId}/consume`, null, {
      params: { quantity, productionPlanId }
    });
  }

  // 10. 使用批次材料（旧接口，保留兼容）
  async useBatch(batchId: string, quantity: number, productionPlanId?: number, factoryId?: string): Promise<ApiResponse<MaterialBatch>> {
    return await apiClient.post(`${this.getPath(factoryId)}/${batchId}/use`, null, {
      params: { quantity, productionPlanId }
    });
  }

  // 10. 调整批次数量
  async adjustBatch(batchId: string, newQuantity: number, reason: string, factoryId?: string): Promise<ApiResponse<MaterialBatch>> {
    return await apiClient.post(`${this.getPath(factoryId)}/${batchId}/adjust`, null, {
      params: { newQuantity, reason }
    });
  }

  // 11. 更新批次状态
  async updateBatchStatus(batchId: string, status: string, factoryId?: string): Promise<ApiResponse<MaterialBatch>> {
    return await apiClient.put(`${this.getPath(factoryId)}/${batchId}/status`, { status });
  }

  // 12. 按材料类型获取批次
  async getBatchesByMaterialType(materialTypeId: string, factoryId?: string): Promise<ApiResponse<MaterialBatch[]>> {
    return await apiClient.get(`${this.getPath(factoryId)}/material-type/${materialTypeId}`);
  }

  // 13. 按状态获取批次 (可选 productTypeId BOM 过滤 — 防呆)
  async getBatchesByStatus(
    status: string,
    factoryId?: string,
    productTypeId?: string,
  ): Promise<ApiResponse<MaterialBatch[]>> {
    // 后端枚举使用大写，需要转换
    const upperStatus = status.toUpperCase();
    const params: Record<string, string> = {};
    if (productTypeId) params['productTypeId'] = productTypeId;
    return await apiClient.get(`${this.getPath(factoryId)}/status/${upperStatus}`, {
      params: Object.keys(params).length > 0 ? params : undefined,
    });
  }

  // 14. 获取FIFO批次（先进先出）
  async getFifoBatches(materialTypeId: string, quantity: number, factoryId?: string): Promise<ApiResponse<MaterialBatch[]>> {
    return await apiClient.get(`${this.getPath(factoryId)}/fifo/${materialTypeId}`, {
      params: { quantity }
    });
  }

  // 14b. 获取FEFO批次（先到期先出）
  async getFefoBatches(materialTypeId: string, quantity?: number, factoryId?: string): Promise<ApiResponse<MaterialBatch[]>> {
    return await apiClient.get(`${this.getPath(factoryId)}/fefo/${materialTypeId}`, {
      params: { quantity }
    });
  }

  // 15. 获取即将过期的批次
  async getExpiringBatches(days?: number, factoryId?: string): Promise<{ success: boolean; data: MaterialBatch[]; message?: string }> {
    return await apiClient.get(`${this.getPath(factoryId)}/expiring`, { params: { days } });
  }

  // 16. 获取已过期的批次
  async getExpiredBatches(factoryId?: string): Promise<{ success: boolean; data: MaterialBatch[]; message?: string }> {
    return await apiClient.get(`${this.getPath(factoryId)}/expired`);
  }

  // 17. 处理过期批次（自动处理所有已过期批次，或指定 batchIds 和 action 仅处理部分）
  async handleExpiredBatches(
    options?: { batchIds?: string[]; action?: string },
    factoryId?: string
  ): Promise<ApiResponse<number>> {
    return await apiClient.post(`${this.getPath(factoryId)}/handle-expired`, options ?? {});
  }

  // 18. 获取批次使用历史
  async getBatchUsageHistory(batchId: string, factoryId?: string): Promise<ApiResponse<any[]>> {
    return await apiClient.get(`${this.getPath(factoryId)}/${batchId}/usage-history`);
  }

  // 19. 获取低库存警告
  async getLowStockBatches(factoryId?: string): Promise<{ success: boolean; data: MaterialBatch[]; message?: string }> {
    return await apiClient.get(`${this.getPath(factoryId)}/low-stock`);
  }

  // 19b. 获取综合库存预警（低库存 + 即将过期 + 已过期）
  async getInventoryWarnings(days?: number, factoryId?: string): Promise<{ success: boolean; data: InventoryAlert[]; message?: string }> {
    return await apiClient.get(`${this.getPath(factoryId)}/inventory/alerts`, {
      params: days !== undefined ? { days } : undefined,
    });
  }

  // 20. 获取库存统计
  async getInventoryStatistics(factoryId?: string): Promise<ApiResponse<any>> {
    return await apiClient.get(`${this.getPath(factoryId)}/inventory/statistics`);
  }

  // 21. 获取库存价值
  async getInventoryValuation(factoryId?: string): Promise<ApiResponse<any>> {
    return await apiClient.get(`${this.getPath(factoryId)}/inventory/valuation`);
  }

  // 22. 导出库存报表
  async exportInventory(params?: { factoryId?: string; [key: string]: any }) {
    const { factoryId, ...query } = params || {};
    return await apiClient.get(`${this.getPath(factoryId)}/export`, {
      params: query,
      responseType: 'blob'
    });
  }

  // 23. 转冻品
  async convertToFrozen(batchId: string, request: ConvertToFrozenRequest, factoryId?: string): Promise<ApiResponse<MaterialBatch>> {
    return await apiClient.post(`${this.getPath(factoryId)}/${batchId}/convert-to-frozen`, request);
  }

  // 24. 撤销转冻品
  async undoFrozen(batchId: string, request: UndoFrozenRequest, factoryId?: string): Promise<ApiResponse<MaterialBatch>> {
    return await apiClient.post(`${this.getPath(factoryId)}/${batchId}/undo-frozen`, request);
  }
}

export const materialBatchApiClient = new MaterialBatchApiClient();
