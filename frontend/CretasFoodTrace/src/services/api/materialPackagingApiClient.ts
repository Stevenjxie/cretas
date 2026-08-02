import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

/**
 * 原料包装层级 API 客户端.
 *
 * 后端: MaterialPackagingHierarchyController
 * 路径: /api/mobile/{factoryId}/material-packaging/*
 *
 * 一个原料一条记录, 一级必填, 二/三级可选.
 * 例: 三文鱼 一级 kg, 10 kg/箱 (二级 箱), 12 箱/柜 (三级 柜).
 */

export interface MaterialPackagingHierarchy {
  id?: string;
  factoryId?: string;
  materialTypeId: string;
  level1Unit: string;
  level1PerLevel2?: number | null;
  level2Unit?: string | null;
  level2PerLevel3?: number | null;
  level3Unit?: string | null;
  notes?: string | null;
  packagingSpecs?: MaterialPackagingSpec[];
  createdAt?: string;
  updatedAt?: string;
}

export interface MaterialPackagingSpec {
  id: string;
  name: string;
  packageUnit: string;
  baseUnit: string;
  conversionFactor: number;
  defaultSpec?: boolean | null;
  active?: boolean | null;
  sortOrder?: number | null;
}

export const materialPackagingApiClient = {
  /**
   * 一次取回本工厂**全部**原料的规格层级。
   *
   * 库存列表要给每个批次显示多单位换算, 逐个调 getByMaterial 会打出几十个请求;
   * 后端 MaterialPackagingHierarchyController 本来就有整厂 list 接口, 这里补上客户端。
   * F006 实测 305 个原料里只有 35 条有层级, 返回体很小。
   */
  async list(factoryId?: string): Promise<MaterialPackagingHierarchy[]> {
    const fid = getCurrentFactoryId(factoryId);
    if (!fid) {
      throw new Error('factoryId 必填');
    }
    const response = await apiClient.get<{ success: boolean; data: MaterialPackagingHierarchy[]; message: string }>(
      `/api/mobile/${fid}/material-packaging`,
    );
    return response?.data ?? [];
  },

  async getByMaterial(materialTypeId: string, factoryId?: string): Promise<MaterialPackagingHierarchy | null> {
    const fid = getCurrentFactoryId(factoryId);
    if (!fid) {
      throw new Error('factoryId 必填');
    }
    const response = await apiClient.get<{ success: boolean; data: MaterialPackagingHierarchy | null; message: string }>(
      `/api/mobile/${fid}/material-packaging/by-material/${materialTypeId}`,
    );
    return response?.data || null;
  },

  async upsert(materialTypeId: string, payload: Partial<MaterialPackagingHierarchy>, factoryId?: string): Promise<MaterialPackagingHierarchy> {
    const fid = getCurrentFactoryId(factoryId);
    if (!fid) {
      throw new Error('factoryId 必填');
    }
    const response = await apiClient.put<{ success: boolean; data: MaterialPackagingHierarchy; message: string }>(
      `/api/mobile/${fid}/material-packaging/by-material/${materialTypeId}`,
      payload,
    );
    return response.data;
  },

  async delete(materialTypeId: string, factoryId?: string): Promise<void> {
    const fid = getCurrentFactoryId(factoryId);
    if (!fid) {
      throw new Error('factoryId 必填');
    }
    await apiClient.delete(`/api/mobile/${fid}/material-packaging/by-material/${materialTypeId}`);
  },
};
