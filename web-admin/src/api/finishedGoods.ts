/**
 * 成品库存 API 客户端 (T126 Phase 1)
 *
 * Base: /api/mobile/{factoryId}/sales/finished-goods
 * RBAC: 写操作需 production:read_write 或 sales:read_write
 *
 * Endpoints:
 *  GET  /finished-goods              — 列表 (已由 list.vue 直接用 get 调用)
 *  GET  /finished-goods/{id}         — 详情 (已由 detail.vue 直接用 get 调用)
 *  POST /finished-goods/opening      — 期初入库
 *  PUT  /finished-goods/{batchId}    — 编辑备注/库位/过期日/单价
 *  POST /finished-goods/{batchId}/adjust — 调整库存 (+/-)
 *  POST /finished-goods/{batchId}/void   — 作废批次
 */
import { post, put } from './request';

// ---- DTO types ----

export interface FgOpeningRequest {
  productTypeId: string;
  batchNumber: string;
  producedQuantity: number;
  unit: string;
  productionDate: string; // ISO date string (YYYY-MM-DD)
  remark?: string;
}

export interface FgBatch {
  id: string;
  batchNumber: string;
  productTypeId: string;
  productName?: string;
  producedQuantity: number;
  shippedQuantity: number;
  reservedQuantity: number;
  unit: string;
  productionDate?: string;
  expireDate?: string;
  storageLocation?: string;
  unitPrice?: number | null;
  status?: string;
  remark?: string;
}

export interface FgUpdateRequest {
  remark?: string;
  storageLocation?: string;
  expireDate?: string; // ISO date string (YYYY-MM-DD) or null
  unitPrice?: number | null;
}

export type FgAdjustmentReferenceType =
  | 'OPENING_CORRECTION'
  | 'STOCKTAKE'
  | 'SCRAP'
  | 'OTHER';

export interface FgAdjustRequest {
  adjustmentQuantity: number; // positive = add, negative = remove
  reason: string;
  referenceType: FgAdjustmentReferenceType;
}

// ---- API functions ----

/**
 * POST /finished-goods/opening
 * 期初入库 — 用于系统上线 / Excel 结存导入
 * 409: dup batchNumber  404: unknown productTypeId
 */
export function createOpeningFgBatch(factoryId: string, data: FgOpeningRequest) {
  return post<FgBatch>(`/${factoryId}/sales/finished-goods/opening`, data);
}

/**
 * PUT /finished-goods/{batchId}
 * 编辑字段 (备注 / 库位 / 过期日 / 成本单价). producedQuantity/unit 不可改.
 */
export function updateFgBatch(
  factoryId: string,
  batchId: string,
  data: FgUpdateRequest
) {
  return put<FgBatch>(`/${factoryId}/sales/finished-goods/${batchId}`, data);
}

/**
 * POST /finished-goods/{batchId}/adjust
 * 调整库存 (+/- adjustmentQuantity)
 * 422: resulting available < 0
 */
export function adjustFgBatch(
  factoryId: string,
  batchId: string,
  data: FgAdjustRequest
) {
  return post<FgBatch>(`/${factoryId}/sales/finished-goods/${batchId}/adjust`, data);
}

/**
 * POST /finished-goods/{batchId}/void
 * 作废批次 (no body)
 * 409: shippedQuantity > 0 OR reservedQuantity > 0
 */
export function voidFgBatch(factoryId: string, batchId: string) {
  return post<void>(`/${factoryId}/sales/finished-goods/${batchId}/void`, {});
}
