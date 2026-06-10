/**
 * SP5 W6 — 毛利红线配置 API — GrossMarginConfigController
 *
 * 后端基础路径: /api/mobile/{factoryId}/pricing/gross-margin-configs
 * RBAC: 读写均需 finance:read_write (财务经理 / 工厂超管)。
 *
 * targetGrossMargin 是 0-1 小数 (e.g. 0.30 = 30%)。前端以百分比展示/录入，
 * 提交前换算为小数。productTypeId = null 表示工厂级全局默认红线。
 */
import { get, post, put, del } from './request';
import type { ApiResponse } from '@/types/api';

// ============================================================================
// 类型
// ============================================================================

export interface FactoryGrossMarginConfig {
  id: string;
  factoryId: string;
  /** null 表示工厂级全局默认红线 */
  productTypeId: string | null;
  /** 目标毛利率 (0-1 小数, e.g. 0.30 = 30%) */
  targetGrossMargin: number;
  description: string | null;
  isActive: boolean;
  createdBy: number;
  createdAt: string;
  updatedAt: string | null;
}

export interface CreateGrossMarginConfigRequest {
  /** null = 工厂级全局默认红线 */
  productTypeId: string | null;
  /** 目标毛利率 (0-1 小数) */
  targetGrossMargin: number;
  isActive: boolean;
  description?: string | null;
}

export interface UpdateGrossMarginConfigRequest {
  /** 目标毛利率 (0-1 小数). null = 不修改 */
  targetGrossMargin?: number | null;
  isActive?: boolean | null;
  description?: string | null;
}

// ============================================================================
// API 方法
// ============================================================================

/** 列出工厂全部毛利红线配置 (含已禁用). */
export function listGrossMarginConfigs(
  factoryId: string,
): Promise<ApiResponse<FactoryGrossMarginConfig[]>> {
  return get<FactoryGrossMarginConfig[]>(`/${factoryId}/pricing/gross-margin-configs`);
}

/** 查询单条毛利红线配置. */
export function getGrossMarginConfig(
  factoryId: string,
  id: string,
): Promise<ApiResponse<FactoryGrossMarginConfig>> {
  return get<FactoryGrossMarginConfig>(`/${factoryId}/pricing/gross-margin-configs/${id}`);
}

/** 创建毛利红线配置. */
export function createGrossMarginConfig(
  factoryId: string,
  req: CreateGrossMarginConfigRequest,
): Promise<ApiResponse<FactoryGrossMarginConfig>> {
  return post<FactoryGrossMarginConfig>(`/${factoryId}/pricing/gross-margin-configs`, req);
}

/** 更新毛利红线配置 (null = 不修改). */
export function updateGrossMarginConfig(
  factoryId: string,
  id: string,
  req: UpdateGrossMarginConfigRequest,
): Promise<ApiResponse<FactoryGrossMarginConfig>> {
  return put<FactoryGrossMarginConfig>(`/${factoryId}/pricing/gross-margin-configs/${id}`, req);
}

/** 删除毛利红线配置 (软删除). */
export function deleteGrossMarginConfig(
  factoryId: string,
  id: string,
): Promise<ApiResponse<void>> {
  return del<void>(`/${factoryId}/pricing/gross-margin-configs/${id}`);
}
