// Canvas-Thresholds Phase A — 阈值参数中心 API client (2026-05-21).
//
// Backend: CanvasThresholdsController @RequestMapping "/api/mobile/{factoryId}/canvas-thresholds"
//   GET    /                            — list (optional ?category=)
//   GET    /{key}                       — get single by thresholdKey
//   POST   /                            — create new
//   PUT    /{id}                        — update (PATCH semantics, Map body)
//   DELETE /{id}                        — soft delete
//   POST   /reset-to-default?category=  — batch reset to default within a category
//
// axios baseURL = '/api/mobile' so caller-side URLs start at "/{factoryId}/...".
import { get, post, put, del } from './request';

// ==================== Types ====================

/** 阈值分类 — 6 大类对应 6 个 service 类的 hard-coded 常量. */
export type ThresholdCategory = 'INVENTORY' | 'IOT' | 'CREDIT' | 'BOM' | 'SHORTAGE' | 'AI';

/** Display labels for category — used by sub-tabs / breadcrumbs. */
export const ThresholdCategoryLabels: Record<ThresholdCategory, string> = {
  INVENTORY: '库存健康',
  IOT: 'IoT 冷链/环境',
  CREDIT: '客户信用',
  BOM: 'BOM 递归',
  SHORTAGE: '缺料分析',
  AI: 'AI 路由',
};

/** 阈值值类型 — 决定 thresholdValue 字符串如何被运行时解析. */
export type ThresholdValueType = 'INTEGER' | 'DECIMAL' | 'DOUBLE' | 'STRING';

/** FactoryThreshold entity — 后端 entity 字段对齐. */
export interface FactoryThreshold {
  id: string;
  factoryId: string;
  thresholdKey: string;
  category: ThresholdCategory;
  valueType: ThresholdValueType;
  /** 当前值 (字符串形式; 按 valueType 解析). */
  thresholdValue: string;
  /** Service 层 hard-coded baseline (用于 reset). */
  defaultValue: string;
  description?: string;
  unit?: string;
  minValue?: string | null;
  maxValue?: string | null;
  enabled: boolean;
  version: number;
  createdAt?: string;
  updatedAt?: string;
}

/** Create payload — 必填字段. */
export interface CreateThresholdRequest {
  thresholdKey: string;
  category: ThresholdCategory;
  valueType: ThresholdValueType;
  thresholdValue: string;
  defaultValue: string;
  description?: string;
  unit?: string;
  minValue?: string | null;
  maxValue?: string | null;
  enabled?: boolean;
}

/** Update payload — PATCH 语义, 任何字段可选 + version 用于乐观锁. */
export interface UpdateThresholdRequest {
  thresholdValue?: string;
  defaultValue?: string;
  description?: string;
  unit?: string;
  minValue?: string | null;
  maxValue?: string | null;
  enabled?: boolean;
  /** 客户端提交时携带的 version 字段, 用于 AUD-4 乐观锁检查. */
  version?: number;
}

// ==================== API methods ====================

const base = (factoryId: string) => `/${factoryId}/canvas-thresholds`;

/** GET / — list (optional category filter). */
export async function listThresholds(
  factoryId: string,
  category?: ThresholdCategory,
): Promise<FactoryThreshold[]> {
  if (!factoryId) return [];
  const url = category
    ? `${base(factoryId)}?category=${encodeURIComponent(category)}`
    : base(factoryId);
  const res = await get<FactoryThreshold[]>(url);
  return res.success && Array.isArray(res.data) ? res.data : [];
}

/** GET /{key} — get single threshold by thresholdKey. */
export async function getThreshold(
  factoryId: string,
  thresholdKey: string,
): Promise<FactoryThreshold> {
  const res = await get<FactoryThreshold>(
    `${base(factoryId)}/${encodeURIComponent(thresholdKey)}`,
  );
  if (!res.success) {
    throw new Error(res.message || '查询阈值失败');
  }
  return res.data as FactoryThreshold;
}

/** POST / — create new threshold. */
export async function createThreshold(
  factoryId: string,
  payload: CreateThresholdRequest,
): Promise<FactoryThreshold> {
  const res = await post<FactoryThreshold>(base(factoryId), payload);
  if (!res.success) {
    throw new Error(res.message || '创建阈值失败');
  }
  return res.data as FactoryThreshold;
}

/** PUT /{id} — update existing (PATCH semantics). */
export async function updateThreshold(
  factoryId: string,
  id: string,
  payload: UpdateThresholdRequest,
): Promise<FactoryThreshold> {
  const res = await put<FactoryThreshold>(`${base(factoryId)}/${id}`, payload);
  if (!res.success) {
    throw new Error(res.message || '更新阈值失败');
  }
  return res.data as FactoryThreshold;
}

/** DELETE /{id} — soft delete. */
export async function deleteThreshold(factoryId: string, id: string): Promise<void> {
  const res = await del<void>(`${base(factoryId)}/${id}`);
  if (!res.success) {
    throw new Error(res.message || '删除阈值失败');
  }
}

/** POST /reset-to-default?category= — batch reset within a category. Returns updated count. */
export async function resetCategoryToDefault(
  factoryId: string,
  category: ThresholdCategory,
): Promise<number> {
  const res = await post<number>(
    `${base(factoryId)}/reset-to-default?category=${encodeURIComponent(category)}`,
    null,
  );
  if (!res.success) {
    throw new Error(res.message || '重置阈值失败');
  }
  return typeof res.data === 'number' ? res.data : 0;
}
