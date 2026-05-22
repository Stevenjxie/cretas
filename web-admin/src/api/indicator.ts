/**
 * 指标中心 API 客户端 — Phase 1 Track B.3 (2026-05-22).
 *
 * 后端 PR #155 IndicatorController (parallel track):
 *   GET    /api/mobile/{factoryId}/indicators                      (?category=)
 *   GET    /api/mobile/{factoryId}/indicators/tree
 *   GET    /api/mobile/{factoryId}/indicators/{code}
 *   GET    /api/mobile/{factoryId}/indicators/{code}/value
 *   GET    /api/mobile/{factoryId}/indicators/{code}/versions      (?page=&size=&windowStart=&windowEnd=)
 *   GET    /api/mobile/{factoryId}/indicators/thresholds/{code}
 *   POST   /api/mobile/{factoryId}/indicators/{code}/recompute
 *
 * axios baseURL = '/api/mobile' (per request.ts), 调用端路径以 /{factoryId}/... 开头.
 *
 * 字段命名: camelCase (per .claude/rules/field-naming-convention.md).
 * 类型: 数值字段统一 `number`, BigDecimal 序列化为 JSON number (per Rule 4).
 * 时间: `string` ISO-8601 (Jackson LocalDateTime/LocalDate 输出, per Rule 11).
 */
import { get, post } from './request';
import { ApiError } from '@/types/api';
import type { ApiResponse, PageResponse } from '@/types/api';

// ==================== 类型定义 (mirror Java DTOs from PR #155) ====================

/**
 * 指标分类
 *
 * Java entity Indicator.category: 30 char string. 已知值: FACTORY / RESTAURANT / QUALITY.
 * 留 string 兼容未来新增分类.
 */
export type IndicatorCategory = 'FACTORY' | 'RESTAURANT' | 'QUALITY' | string;

/**
 * 计算策略
 *
 * Java entity Indicator.computeStrategy: REALTIME / CACHED / PRECOMPUTED.
 */
export type ComputeStrategy = 'REALTIME' | 'CACHED' | 'PRECOMPUTED' | string;

/**
 * 告警级别
 *
 * Java entity IndicatorThreshold.alertLevel: GREEN / YELLOW / RED (三色预警).
 * IndicatorVersion 可能含 null (尚未计算 threshold 时).
 */
export type AlertLevel = 'GREEN' | 'YELLOW' | 'RED' | null;

/**
 * 阈值运算符
 *
 * Java entity IndicatorThreshold.operator: GT / GTE / LT / LTE / EQ / BETWEEN.
 */
export type ThresholdOperator = 'GT' | 'GTE' | 'LT' | 'LTE' | 'EQ' | 'BETWEEN';

/**
 * 指标列表项 (GET /indicators)
 *
 * Mirror Java IndicatorListResponse (PR #155).
 */
export interface IndicatorListResponse {
  id: string;
  code: string;
  name: string;
  description?: string;
  category: IndicatorCategory;
  unit?: string;
  isActive: boolean;
  computeStrategy: ComputeStrategy;
  cacheTtlSeconds?: number;
  lastValue?: number | null;
  lastComputedAt?: string | null;
  displayOrder?: number | null;
}

/**
 * 指标详情 (GET /indicators/{code})
 *
 * Mirror Java IndicatorDetailResponse (PR #155).
 * 比 List 多 config jsonb (业务方扩展字段).
 */
export interface IndicatorDetailResponse extends IndicatorListResponse {
  factoryId: string;
  config?: Record<string, unknown>;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * 指标当前值 (GET /indicators/{code}/value)
 *
 * Mirror Java IndicatorValueResponse (PR #155).
 * 包含 alertLevel 命中结果便于 UI 显示色带.
 */
export interface IndicatorValueResponse {
  code: string;
  name: string;
  value: number | null;
  unit?: string;
  alertLevel: AlertLevel;
  computedAt?: string | null;
  fromCache: boolean;
}

/**
 * 指标树节点 (GET /indicators/tree)
 *
 * Mirror Java IndicatorTreeNodeResponse (PR #155).
 * 递归结构 — 后端已组装好 children, 前端直接给 el-tree.
 */
export interface IndicatorTreeNodeResponse {
  /** 树节点 id (indicator_tree_nodes.id, 非 indicators.id) */
  nodeId: string;
  /** 指标 id (indicators.id) — 用于查 detail/value */
  indicatorId: string;
  code: string;
  name: string;
  category: IndicatorCategory;
  unit?: string;
  /** 当前权重 (用于聚合计算, 加权平均等) */
  weight?: number | null;
  /** 节点在树中的深度, 根=0 */
  depth: number;
  /** 物化路径, 形如 "root-id/parent-id/this-id" */
  materializedPath?: string;
  /** 当前快照值 (从 indicators.last_value) */
  lastValue?: number | null;
  lastComputedAt?: string | null;
  alertLevel?: AlertLevel;
  displayOrder?: number | null;
  children?: IndicatorTreeNodeResponse[];
}

/**
 * 阈值 (GET /indicators/thresholds/{code})
 *
 * Mirror Java ThresholdResponse (PR #155).
 * 一个指标有多条阈值 (GREEN/YELLOW/RED 各一条).
 */
export interface ThresholdResponse {
  id: string;
  indicatorId: string;
  indicatorCode?: string;
  alertLevel: 'GREEN' | 'YELLOW' | 'RED';
  operator: ThresholdOperator;
  thresholdValue: number;
  thresholdValueUpper?: number | null;
  isActive: boolean;
}

/**
 * 历史快照 (GET /indicators/{code}/versions)
 *
 * Mirror Java IndicatorVersionResponse (PR #155).
 */
export interface IndicatorVersionResponse {
  id: string;
  indicatorId: string;
  indicatorCode?: string;
  periodStart: string;
  periodEnd: string;
  value: number;
  computedAt: string;
  alertLevel: AlertLevel;
  computeSource?: string;
}

/**
 * Versions 查询参数
 */
export interface VersionsQuery {
  page?: number;
  size?: number;
  windowStart?: string; // ISO date "YYYY-MM-DD"
  windowEnd?: string;   // ISO date "YYYY-MM-DD"
}

// ==================== API 函数 (7 个) ====================

/**
 * 1. 获取指标列表 (按分类筛选)
 *
 * GET /api/mobile/{factoryId}/indicators?category=FACTORY
 */
export async function listIndicators(
  factoryId: string,
  category?: IndicatorCategory
): Promise<IndicatorListResponse[]> {
  const params = category ? { category } : undefined;
  const res = await get<IndicatorListResponse[]>(`/${factoryId}/indicators`, { params });
  if (!res.success) {
    throw new ApiError(res.message || '指标列表加载失败', res.code);
  }
  return res.data || [];
}

/**
 * 2. 获取指标树 (父子结构, 后端已组装 children)
 *
 * GET /api/mobile/{factoryId}/indicators/tree
 */
export async function getIndicatorTree(
  factoryId: string
): Promise<IndicatorTreeNodeResponse[]> {
  const res = await get<IndicatorTreeNodeResponse[]>(`/${factoryId}/indicators/tree`);
  if (!res.success) {
    throw new ApiError(res.message || '指标树加载失败', res.code);
  }
  return res.data || [];
}

/**
 * 3. 获取指标详情
 *
 * GET /api/mobile/{factoryId}/indicators/{code}
 */
export async function getIndicatorDetail(
  factoryId: string,
  code: string
): Promise<IndicatorDetailResponse> {
  const res = await get<IndicatorDetailResponse>(
    `/${factoryId}/indicators/${encodeURIComponent(code)}`
  );
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '指标详情加载失败', res.code);
  }
  return res.data;
}

/**
 * 4. 获取指标当前值 (含告警级别, 走缓存优先)
 *
 * GET /api/mobile/{factoryId}/indicators/{code}/value
 */
export async function getIndicatorValue(
  factoryId: string,
  code: string
): Promise<IndicatorValueResponse> {
  const res = await get<IndicatorValueResponse>(
    `/${factoryId}/indicators/${encodeURIComponent(code)}/value`
  );
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '指标值加载失败', res.code);
  }
  return res.data;
}

/**
 * 5. 获取指标历史快照 (分页 + 时间窗筛选)
 *
 * GET /api/mobile/{factoryId}/indicators/{code}/versions?page=0&size=20&windowStart=2026-05-01&windowEnd=2026-05-31
 */
export async function listIndicatorVersions(
  factoryId: string,
  code: string,
  query?: VersionsQuery
): Promise<PageResponse<IndicatorVersionResponse>> {
  const params: Record<string, string | number> = {};
  if (query?.page !== undefined) params.page = query.page;
  if (query?.size !== undefined) params.size = query.size;
  if (query?.windowStart) params.windowStart = query.windowStart;
  if (query?.windowEnd) params.windowEnd = query.windowEnd;

  const res = await get<PageResponse<IndicatorVersionResponse>>(
    `/${factoryId}/indicators/${encodeURIComponent(code)}/versions`,
    { params: Object.keys(params).length ? params : undefined }
  );
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '历史快照加载失败', res.code);
  }
  return res.data;
}

/**
 * 6. 获取指标阈值配置 (GREEN/YELLOW/RED 三色配置)
 *
 * GET /api/mobile/{factoryId}/indicators/thresholds/{code}
 *
 * Note: 后端路径是 /thresholds/{code} (不是 /{code}/thresholds), per PR #155 spec.
 */
export async function getIndicatorThresholds(
  factoryId: string,
  code: string
): Promise<ThresholdResponse[]> {
  const res = await get<ThresholdResponse[]>(
    `/${factoryId}/indicators/thresholds/${encodeURIComponent(code)}`
  );
  if (!res.success) {
    throw new ApiError(res.message || '阈值加载失败', res.code);
  }
  return res.data || [];
}

/**
 * 7. 强制重算指标 (需要 indicator:write 权限)
 *
 * POST /api/mobile/{factoryId}/indicators/{code}/recompute
 *
 * 返回新计算的值. 后端写 indicator_versions + 更新 indicators.last_value/last_computed_at.
 */
export async function recomputeIndicator(
  factoryId: string,
  code: string
): Promise<IndicatorValueResponse> {
  const res = await post<IndicatorValueResponse>(
    `/${factoryId}/indicators/${encodeURIComponent(code)}/recompute`
  );
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '重算失败', res.code);
  }
  return res.data;
}

/**
 * Re-export ApiResponse for caller convenience.
 */
export type { ApiResponse };
