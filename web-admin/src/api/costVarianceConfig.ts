/**
 * SP3 方差阈值配置 API — ProductCostVarianceConfigController
 *
 * 后端基础路径: /api/mobile/{factoryId}/bom/variance-configs
 * RBAC: 查询需 finance:read 或 system:read_write; 写操作需 system:read_write
 *
 * productTypeId = null 表示工厂级全局默认阈值 (10%)
 */
import { get, post, put, del } from './request';
import type { ApiResponse } from '@/types/api';

// ============================================================================
// 类型
// ============================================================================

export interface ProductCostVarianceConfig {
  id: string;
  factoryId: string;
  /** null 表示工厂级全局默认阈值 */
  productTypeId: number | null;
  productTypeName: string | null;
  /** 方差阈值百分比，例如 10.00 表示 10% */
  varianceThreshold: number;
  isActive: boolean;
  remark: string | null;
  createdAt: string;
  updatedAt: string | null;
}

export interface CreateProductCostVarianceConfigRequest {
  /** null = 工厂级全局阈值 */
  productTypeId: number | null;
  varianceThreshold: number;
  isActive: boolean;
  remark?: string | null;
}

export interface UpdateProductCostVarianceConfigRequest {
  productTypeId?: number | null;
  varianceThreshold?: number | null;
  isActive?: boolean | null;
  remark?: string | null;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

// ============================================================================
// API 方法
// ============================================================================

/** 查询方差配置列表 (分页). */
export function listVarianceConfigs(
  factoryId: string,
  params: { page?: number; size?: number } = {},
): Promise<ApiResponse<PageResponse<ProductCostVarianceConfig>>> {
  return get<PageResponse<ProductCostVarianceConfig>>(
    `/${factoryId}/bom/variance-configs`,
    {
      params: {
        page: params.page ?? 1,
        size: params.size ?? 20,
      },
    },
  );
}

/** 查询单条方差配置. */
export function getVarianceConfig(
  factoryId: string,
  id: string,
): Promise<ApiResponse<ProductCostVarianceConfig>> {
  return get<ProductCostVarianceConfig>(`/${factoryId}/bom/variance-configs/${id}`);
}

/** 创建方差配置. */
export function createVarianceConfig(
  factoryId: string,
  req: CreateProductCostVarianceConfigRequest,
): Promise<ApiResponse<ProductCostVarianceConfig>> {
  return post<ProductCostVarianceConfig>(`/${factoryId}/bom/variance-configs`, req);
}

/** 更新方差配置 (null = 不修改). */
export function updateVarianceConfig(
  factoryId: string,
  id: string,
  req: UpdateProductCostVarianceConfigRequest,
): Promise<ApiResponse<ProductCostVarianceConfig>> {
  return put<ProductCostVarianceConfig>(`/${factoryId}/bom/variance-configs/${id}`, req);
}

/** 删除方差配置 (软删除). */
export function deleteVarianceConfig(
  factoryId: string,
  id: string,
): Promise<ApiResponse<void>> {
  return del<void>(`/${factoryId}/bom/variance-configs/${id}`);
}
