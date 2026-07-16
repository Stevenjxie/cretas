import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

/**
 * 原材料类型管理API客户端
 * 总计13个API - 路径：/api/mobile/{factoryId}/raw-material-types/*
 */

/**
 * 原材料类型接口
 * 字段名与后端 RawMaterialTypeDTO 保持一致
 *
 * 注意：后端支持接收旧字段名（通过 @JsonAlias）:
 * - materialCode -> code
 * - shelfLife -> shelfLifeDays
 * - description -> notes
 */
export interface MaterialType {
  id: string;
  factoryId: string;
  code: string;               // 原材料编码（原 materialCode）
  name: string;
  category?: string;
  unit: string;
  unitPrice?: number;         // 单价（后端字段）
  storageType?: string;       // fresh, frozen, dry
  shelfLifeDays?: number;     // 保质期天数（原 shelfLife）
  minStock?: number;          // 最低库存（后端字段）
  maxStock?: number;          // 最高库存（后端字段）
  currentStock?: number;      // 当前库存（后端字段）
  totalValue?: number;        // 库存总价值（后端字段）
  isActive: boolean;
  notes?: string;             // 备注（原 description）
  createdAt: string;
  updatedAt?: string;
  // W-ABA-1 抄码品标记 (六扇门 F006 卤制品工厂刚需)
  // true → 每箱重量不一, 采购不录箱数, 入库按实际称重
  isAbacaPackaging?: boolean;
  abacaUnitPerBox?: string;     // 箱重区间描述, UI 提示用 (如 "约 10-15kg/箱")
  abacaDefaultUnit?: string;    // 入库默认计量单位 (kg / g)
  // 关联信息（后端字段）
  factoryName?: string;
  createdByName?: string;
  totalBatches?: number;
  primaryCode?: string;
}

export interface MaterialCodeSegmentNode {
  id: number;
  segmentCode: string;
  segmentLabel: string;
  level: 1 | 2 | 3;
  parentCode: string | null;
  isActive: boolean;
  children?: MaterialCodeSegmentNode[];
}

/**
 * 创建原材料类型请求参数
 * 字段名与后端 RawMaterialTypeDTO 保持一致
 *
 * 注意：后端通过 @JsonAlias 也支持旧字段名：
 * - materialCode, shelfLife, description
 */
export interface CreateMaterialTypeRequest {
  code?: string;              // 可选，由后端自动生成或用户提供
  name: string;
  category: string;
  unit: string;
  unitPrice?: number;         // 单价
  storageType: string;        // fresh, frozen, dry
  shelfLifeDays?: number;     // 保质期天数
  minStock?: number;          // 最低库存
  maxStock?: number;          // 最高库存
  notes?: string;             // 备注
  segmentCode: string;        // 必填 L3 累计10位编码；后端据此生成16位编码
}

/**
 * 更新原材料类型请求参数（所有字段可选）
 */
export interface UpdateMaterialTypeRequest extends Partial<CreateMaterialTypeRequest> {
  isActive?: boolean;
}

class MaterialTypeApiClient {
  private getPath(factoryId?: string) {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/raw-material-types`;
  }

  async getMaterialTypes(params?: { factoryId?: string; isActive?: boolean }): Promise<{ data: MaterialType[] }> {
    const { factoryId, ...query } = params || {};
    return await apiClient.get(this.getPath(factoryId), { params: query });
  }

  async createMaterialType(data: CreateMaterialTypeRequest, factoryId?: string): Promise<MaterialType> {
    const response = await apiClient.post<{ code: number; data: MaterialType; message: string; success: boolean }>(this.getPath(factoryId), data);
    return response.data;
  }

  async getMaterialSegmentTree(factoryId?: string): Promise<MaterialCodeSegmentNode[]> {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    const response = await apiClient.get<{ code: number; data: MaterialCodeSegmentNode[]; message: string; success: boolean }>(
      `/api/mobile/${currentFactoryId}/material-segments/tree`,
    );
    return response.data || [];
  }

  async getMaterialTypeById(id: string, factoryId?: string): Promise<MaterialType> {
    const response = await apiClient.get<{ code: number; data: MaterialType; message: string; success: boolean }>(`${this.getPath(factoryId)}/${id}`);
    return response.data;
  }

  async updateMaterialType(id: string, data: UpdateMaterialTypeRequest, factoryId?: string): Promise<MaterialType> {
    const response = await apiClient.put<{ code: number; data: MaterialType; message: string; success: boolean }>(`${this.getPath(factoryId)}/${id}`, data);
    return response.data;
  }

  async deleteMaterialType(id: string, factoryId?: string): Promise<void> {
    return await apiClient.delete(`${this.getPath(factoryId)}/${id}`);
  }

  async getActiveMaterialTypes(factoryId?: string): Promise<{ data: MaterialType[] }> {
    return await apiClient.get(`${this.getPath(factoryId)}/active`);
  }

  async getMaterialTypesByCategory(category: string, factoryId?: string): Promise<MaterialType[]> {
    const response = await apiClient.get<{ code: number; data: MaterialType[]; message: string; success: boolean }>(`${this.getPath(factoryId)}/category/${category}`);
    return response.data || [];
  }

  async getMaterialTypesByStorageType(storageType: string, factoryId?: string): Promise<MaterialType[]> {
    const response = await apiClient.get<{ code: number; data: MaterialType[]; message: string; success: boolean }>(`${this.getPath(factoryId)}/storage-type/${storageType}`);
    return response.data || [];
  }

  async searchMaterialTypes(keyword: string, factoryId?: string): Promise<{ data: { content: MaterialType[] } }> {
    return await apiClient.get(`${this.getPath(factoryId)}/search`, { params: { keyword } });
  }

  /**
   * 检查原材料编码是否已存在
   * @param code 要检查的编码
   * @param factoryId 工厂ID（可选）
   * @param excludeId 排除的ID（用于编辑时）
   */
  async checkCodeExists(code: string, factoryId?: string, excludeId?: string): Promise<{ exists: boolean }> {
    const params: Record<string, string> = { code };
    if (excludeId) {
      params.excludeId = excludeId;
    }
    const response = await apiClient.get<{ code: number; data: { exists: boolean }; message: string; success: boolean }>(`${this.getPath(factoryId)}/check-code`, { params });
    return response.data;
  }

  /**
   * @deprecated 请使用 checkCodeExists，此方法将在后续版本中移除
   */
  async checkMaterialCodeExists(materialCode: string, factoryId?: string): Promise<{ exists: boolean }> {
    return this.checkCodeExists(materialCode, factoryId);
  }

  async getCategories(factoryId?: string): Promise<string[]> {
    const response = await apiClient.get<{ code: number; data: string[]; message: string; success: boolean }>(`${this.getPath(factoryId)}/categories`);
    return response.data || [];
  }

  async getLowStockMaterials(factoryId?: string): Promise<MaterialType[]> {
    const response = await apiClient.get<{ code: number; data: MaterialType[]; message: string; success: boolean }>(`${this.getPath(factoryId)}/low-stock`);
    return response.data || [];
  }

  async batchUpdateStatus(ids: string[], isActive: boolean, factoryId?: string): Promise<void> {
    return await apiClient.put(`${this.getPath(factoryId)}/batch/status`, { ids, isActive });
  }
}

export const materialTypeApiClient = new MaterialTypeApiClient();
