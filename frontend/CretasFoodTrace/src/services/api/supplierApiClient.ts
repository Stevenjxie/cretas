import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

/**
 * 供应商管理API客户端 - MVP精简版
 * MVP保留：8个核心API
 * 已移除：10个高级API（统计、财务、导入导出功能）
 * 路径：/api/mobile/{factoryId}/suppliers/*
 */

// ========== 类型定义 ==========

export interface Supplier {
  id: string;
  factoryId: string;
  supplierCode: string;
  code: string; // 别名，指向supplierCode
  name: string;
  contactPerson?: string;
  phone?: string;
  email?: string;
  address?: string;
  businessType?: string;
  creditLevel?: string;
  creditLimit?: number;
  currentBalance?: number;
  deliveryArea?: string;
  paymentTerms?: string;
  rating?: number;
  isActive: boolean;
  createdAt: string;
  updatedAt?: string;
  _count?: {
    materialBatches: number;
  };
}

export interface SupplierStats {
  totalBatches: number;
  activeBatches: number;
  totalPurchaseValue: number;
  averageDeliveryDays?: number;
  qualityScore?: number;
  recentBatches: any[];
}

export interface CreateSupplierRequest {
  supplierCode: string;
  name: string;
  contactPerson?: string;
  phone?: string;
  email?: string;
  address?: string;
  businessType?: string;
  creditLevel?: string;
  creditLimit?: number;
  deliveryArea?: string;
  paymentTerms?: string;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

// ========== API客户端类 ==========

/** 供应关系 (GET /suppliers/{id}/materials 返回形状, 只列本屏用到的字段) */
export interface SupplierMaterialRelation {
  id: string;
  supplierId: string;
  materialTypeId: string;
  materialName?: string;
  purchaseUnit?: string;
  defaultPurchasePrice?: number | null;
  active?: boolean;
}

/**
 * 供应关系上的采购包装规格（「1 箱 = 20 kg」这一行）。
 * 字段与 web-admin `SupplierPurchaseSpec` 同源 —— 只声明 RN 用得到的那些。
 */
export interface SupplierPurchaseSpec {
  id: string;
  supplierMaterialId: string;
  materialTypeId: string;
  name: string;
  /** 下单单位。后端要求请求里的数量单位与它**逐字相等**, 否则 400。 */
  purchasePackageUnit: string;
  inventoryBaseUnit: string;
  factor: number;
  quotedPrice?: number | null;
  /**
   * 该规格没有自己的报价时, 后端用本规格行的换算系数把供应关系价折算过来的参考价。
   * ⛔ 单位换算一律由后端做, 前端只搬数 —— 与 web-admin 同一条纪律。
   */
  derivedPrice?: number | null;
  defaultSpec?: boolean | null;
  active?: boolean | null;
}

class SupplierApiClient {
  private getPath(factoryId?: string) {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/suppliers`;
  }

  /**
   * 1. 获取供应商列表（分页）
   * GET /api/mobile/{factoryId}/suppliers
   */
  async getSuppliers(params?: {
    factoryId?: string;
    page?: number;
    size?: number;
    sortBy?: string;
    sortDirection?: 'ASC' | 'DESC';
    keyword?: string;
    isActive?: boolean;
  }): Promise<{data: Supplier[]}> {
    const { factoryId, ...queryParams } = params || {};
    // apiClient拦截器已统一返回data
    const apiResponse = await apiClient.get<any>(
      `${this.getPath(factoryId)}`,
      { params: queryParams }
    );
    
    // 处理分页响应：apiResponse.data.content
    if (apiResponse.data?.content) {
      return { data: apiResponse.data.content };
    }
    
    // 兼容直接返回数组的情况
    if (Array.isArray(apiResponse.data)) {
      return { data: apiResponse.data };
    }
    
    // 防御性编程：兼容旧格式
    if (Array.isArray(apiResponse)) {
      return { data: apiResponse };
    }
    
    console.warn('[SupplierAPI] 未预期的响应格式:', apiResponse);
    return { data: [] };
  }

  /**
   * 2. 创建供应商
   * POST /api/mobile/{factoryId}/suppliers
   */
  async createSupplier(
    request: CreateSupplierRequest,
    factoryId?: string
  ): Promise<{data: Supplier}> {
    const response = await apiClient.post<{ code: number; data: Supplier; message: string; success: boolean }>(
      `${this.getPath(factoryId)}`,
      request
    );
    return { data: response.data };
  }

  /**
   * 3. 获取供应商详情
   * GET /api/mobile/{factoryId}/suppliers/{supplierId}
   */
  async getSupplierById(supplierId: string, factoryId?: string): Promise<Supplier> {
    const response = await apiClient.get<{ code: number; data: Supplier; message: string; success: boolean }>(
      `${this.getPath(factoryId)}/${supplierId}`
    );
    return response.data;
  }

  /**
   * 4. 更新供应商
   * PUT /api/mobile/{factoryId}/suppliers/{supplierId}
   */
  async updateSupplier(
    supplierId: string,
    request: Partial<CreateSupplierRequest>,
    factoryId?: string
  ): Promise<Supplier> {
    const response = await apiClient.put<{ code: number; data: Supplier; message: string; success: boolean }>(
      `${this.getPath(factoryId)}/${supplierId}`,
      request
    );
    return response.data;
  }

  /**
   * 5. 删除供应商
   * DELETE /api/mobile/{factoryId}/suppliers/{supplierId}
   */
  async deleteSupplier(supplierId: string, factoryId?: string): Promise<void> {
    await apiClient.delete(`${this.getPath(factoryId)}/${supplierId}`);
  }

  /**
   * 6. 获取活跃供应商列表
   * GET /api/mobile/{factoryId}/suppliers/active
   */
  async getActiveSuppliers(factoryId?: string): Promise<Supplier[]> {
    const response = await apiClient.get<{ code: number; data: Supplier[]; message: string; success: boolean }>(
      `${this.getPath(factoryId)}/active`
    );
    return response.data || [];
  }

  /**
   * 6b. 取该供应商【可供的原料】(供应关系)
   * GET /api/mobile/{factoryId}/suppliers/{supplierId}/materials
   *
   * 用途: 新建采购单时把物料选择器收敛到「这个供应商真的能供」的范围。
   * 不收敛的话用户能选到没有供应关系的物料, 提交时后端抛 409
   * 「该供应商未启用所选物料的供应关系」/「供应商与物料的供应关系不存在」——
   * 表单填完才被拒, 属于防呆反模式(界面提供了走不通的选项)。
   */
  async getSupplierMaterials(supplierId: string, factoryId?: string): Promise<SupplierMaterialRelation[]> {
    if (!supplierId || !supplierId.trim()) return [];
    const response = await apiClient.get<{ code: number; data: SupplierMaterialRelation[]; message: string; success: boolean }>(
      `${this.getPath(factoryId)}/${supplierId}/materials`
    );
    // active !== false —— 与 web-admin 的 resolveSupplierMaterialRelations 同口径
    return (response.data || []).filter((row) => row.active !== false);
  }

  /**
   * 6b. 取某条供应关系上【已启用的采购包装规格】
   * GET /api/mobile/{factoryId}/suppliers/{supplierId}/materials/{relationId}/purchase-specs
   *
   * 用途: 新建采购单时判断这一行要不要选规格。后端
   * `PurchaseServiceImpl.applySupplierPurchaseContract` 的口径是:
   * 该供应关系只要有【启用中】的采购规格, 请求就**必须**带 `purchasePackagingSpecId`,
   * 否则 422「该供应关系已配置采购包装规格，必须选择具体规格」。
   *
   * ⚠️ 取不到时**返回 null 而不是空数组** —— 两者对下游是相反的意思:
   * 空数组 = 「确认没有规格, 走原料包装那条路」; null = 「不知道」。
   * 把「不知道」当成「没有」正是这个缺陷的形状: 界面照常放行, 提交时才被 422 拒,
   * 而那个 422 在 RN 上无法满足(见 PurchaseOrderCreateScreen 的规格选择器)。
   */
  async getSupplierPurchaseSpecs(
    supplierId: string,
    relationId: string,
    factoryId?: string,
  ): Promise<SupplierPurchaseSpec[] | null> {
    if (!supplierId?.trim() || !relationId?.trim()) return null;
    try {
      const response = await apiClient.get<{ code: number; data: SupplierPurchaseSpec[]; message: string; success: boolean }>(
        `${this.getPath(factoryId)}/${supplierId}/materials/${relationId}/purchase-specs`
      );
      // active !== false —— 与后端 findByFactoryIdAndSupplierMaterialIdAndActiveTrue 同口径:
      // 只有【启用中】的规格才会让后端要求必选, 停用的不算。
      return (response.data || []).filter((row) => row.active !== false);
    } catch {
      return null;
    }
  }

  /**
   * 7. 搜索供应商
   * GET /api/mobile/{factoryId}/suppliers/search
   */
  async searchSuppliers(params: {
    keyword: string;
    factoryId?: string;
    businessType?: string;
    creditLevel?: string;
    isActive?: boolean;
  }): Promise<Supplier[]> {
    const { factoryId, ...queryParams } = params;
    const response = await apiClient.get<{ code: number; data: Supplier[]; message: string; success: boolean }>(
      `${this.getPath(factoryId)}/search`,
      { params: queryParams }
    );
    return response.data || [];
  }

  /**
   * 8. 切换供应商状态
   * PUT /api/mobile/{factoryId}/suppliers/{supplierId}/status
   */
  async toggleSupplierStatus(
    supplierId: string,
    isActive: boolean,
    factoryId?: string
  ): Promise<Supplier> {
    const response = await apiClient.put<{ code: number; data: Supplier; message: string; success: boolean }>(
      `${this.getPath(factoryId)}/${supplierId}/status`,
      {},
      { params: { isActive: isActive } }
    );
    return response.data;
  }

  // ===== 新增功能 (Phase 3) =====

  /**
   * 9. 按材料类型筛选供应商
   * GET /api/mobile/{factoryId}/suppliers/by-material
   */
  async getSuppliersByMaterial(params: {
    materialType: string;
    factoryId?: string;
  }): Promise<Supplier[]> {
    const { factoryId, materialType } = params;
    const response = await apiClient.get<{ code: number; data: Supplier[]; message: string; success: boolean }>(
      `${this.getPath(factoryId)}/by-material`,
      { params: { materialType } }
    );
    return response.data || [];
  }

  /**
   * 10. 更新供应商评级
   * PUT /api/mobile/{factoryId}/suppliers/{supplierId}/rating
   */
  async updateSupplierRating(params: {
    supplierId: string;
    rating: number;
    notes?: string;
    factoryId?: string;
  }): Promise<Supplier> {
    const { factoryId, supplierId, ...body } = params;
    const response = await apiClient.put<{ code: number; data: Supplier; message: string; success: boolean }>(
      `${this.getPath(factoryId)}/${supplierId}/rating`,
      body
    );
    return response.data;
  }

  /**
   * 11. 获取供应商统计信息
   * GET /api/mobile/{factoryId}/suppliers/{supplierId}/statistics
   */
  async getSupplierStatistics(
    supplierId: string,
    factoryId?: string
  ): Promise<SupplierStats> {
    const response = await apiClient.get<{ code: number; data: SupplierStats; message: string; success: boolean }>(
      `${this.getPath(factoryId)}/${supplierId}/statistics`
    );
    return response.data;
  }

  /**
   * 12. 获取供应商供货历史
   * GET /api/mobile/{factoryId}/suppliers/{supplierId}/history
   */
  async getSupplierHistory(
    supplierId: string,
    factoryId?: string
  ): Promise<{
    batches: any[];
    totalBatches: number;
    totalValue: number;
    averageDeliveryDays: number;
  }> {
    const response = await apiClient.get<{ code: number; data: {
      batches: any[];
      totalBatches: number;
      totalValue: number;
      averageDeliveryDays: number;
    }; message: string; success: boolean }>(
      `${this.getPath(factoryId)}/${supplierId}/history`
    );
    return response.data;
  }

  // ===== 保留供后续版本的功能 =====
  /*
   * 以下功能暂不实现，详见 .claude/rules/unused-api-endpoints.md:
   *
   * - checkSupplierCodeExists - 检查供应商代码是否存在
   * - updateCreditLimit - 更新供应商信用额度
   * - getSuppliersWithOutstandingBalance - 获取有欠款的供应商
   * - getRatingDistribution - 获取供应商评级分布
   * - exportSuppliers - 导出供应商列表
   * - importSuppliers - 批量导入供应商
   */
}

export const supplierApiClient = new SupplierApiClient();
