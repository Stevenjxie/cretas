// Canvas-Phase C — 枚举字典 API client (2026-05-22).
//
// Backend: CanvasEnumDictionaryController
//   @RequestMapping "/api/mobile/{factoryId}/canvas-enum-dictionary"
//   GET    /                          — list (optional ?category=)
//   GET    /categories                — distinct categories for factoryId
//   GET    /resolve?category=         — resolver entry (enabled only, fallback global)
//   POST   /                          — create new
//   PUT    /{id}                      — update (PATCH semantics, Map body)
//   DELETE /{id}                      — soft delete
//
// axios baseURL = '/api/mobile' so caller-side URLs start at "/{factoryId}/...".
import { get, post, put, del } from './request';

// ==================== Types ====================

/** 8 标准类别 (UPPER_SNAKE_CASE). 不限制 — 任意工厂可新增 category. */
export type EnumCategory =
  | 'CANCEL_REASON'
  | 'RETURN_REASON'
  | 'APPROVAL_OPINION'
  | 'DEFECT_SEVERITY'
  | 'NONCONFORM_TYPE'
  | 'WASTAGE_REASON'
  | 'RECALL_LEVEL'
  | 'URGENCY_LEVEL'
  | string;

/** UI display labels for 8 standard categories. */
export const EnumCategoryLabels: Record<string, string> = {
  CANCEL_REASON: '取消原因',
  RETURN_REASON: '退货原因',
  APPROVAL_OPINION: '审批意见',
  DEFECT_SEVERITY: '缺陷严重度',
  NONCONFORM_TYPE: '不合格类型',
  WASTAGE_REASON: '损耗原因',
  RECALL_LEVEL: '召回等级',
  URGENCY_LEVEL: '紧急程度',
};

/** EnumDictionary entity — 后端字段对齐. */
export interface EnumDictionary {
  id: string;
  factoryId: string;
  category: string;
  code: string;
  label: string;
  displayOrder: number;
  enabled: boolean;
  parentCode?: string | null;
  description?: string | null;
  locale: string;
  version: number;
  createdAt?: string;
  updatedAt?: string;
}

/** Create payload — 必填字段. */
export interface CreateEnumRequest {
  category: string;
  code: string;
  label: string;
  displayOrder?: number;
  enabled?: boolean;
  parentCode?: string | null;
  description?: string | null;
  locale?: string;
}

/** Update payload — PATCH 语义, 任何字段可选 + version 用于乐观锁. */
export interface UpdateEnumRequest {
  label?: string;
  displayOrder?: number;
  enabled?: boolean;
  parentCode?: string | null;
  description?: string | null;
  locale?: string;
  /** 客户端提交时携带的 version 字段, 用于 AUD-4 乐观锁检查. */
  version?: number;
}

// ==================== API methods ====================

const base = (factoryId: string) => `/${factoryId}/canvas-enum-dictionary`;

/** GET / — list (optional category filter). */
export async function listEnums(
  factoryId: string,
  category?: string,
): Promise<EnumDictionary[]> {
  if (!factoryId) return [];
  const url = category
    ? `${base(factoryId)}?category=${encodeURIComponent(category)}`
    : base(factoryId);
  const res = await get<EnumDictionary[]>(url);
  return res.success && Array.isArray(res.data) ? res.data : [];
}

/** GET /categories — distinct categories. */
export async function listCategories(factoryId: string): Promise<string[]> {
  if (!factoryId) return [];
  const res = await get<string[]>(`${base(factoryId)}/categories`);
  return res.success && Array.isArray(res.data) ? res.data : [];
}

/** GET /resolve — resolver (enabled only, fallback global). */
export async function resolveEnum(
  factoryId: string,
  category: string,
): Promise<EnumDictionary[]> {
  if (!factoryId || !category) return [];
  const res = await get<EnumDictionary[]>(
    `${base(factoryId)}/resolve?category=${encodeURIComponent(category)}`,
  );
  return res.success && Array.isArray(res.data) ? res.data : [];
}

/** POST / — create new enum value. */
export async function createEnum(
  factoryId: string,
  payload: CreateEnumRequest,
): Promise<EnumDictionary> {
  const res = await post<EnumDictionary>(base(factoryId), payload);
  if (!res.success) {
    throw new Error(res.message || '创建枚举值失败');
  }
  return res.data as EnumDictionary;
}

/** PUT /{id} — update existing (PATCH semantics). */
export async function updateEnum(
  factoryId: string,
  id: string,
  payload: UpdateEnumRequest,
): Promise<EnumDictionary> {
  const res = await put<EnumDictionary>(`${base(factoryId)}/${id}`, payload);
  if (!res.success) {
    throw new Error(res.message || '更新枚举值失败');
  }
  return res.data as EnumDictionary;
}

/** DELETE /{id} — soft delete. */
export async function deleteEnum(factoryId: string, id: string): Promise<void> {
  const res = await del<void>(`${base(factoryId)}/${id}`);
  if (!res.success) {
    throw new Error(res.message || '删除枚举值失败');
  }
}
