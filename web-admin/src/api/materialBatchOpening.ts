/**
 * 期初建账 (opening inventory onboarding) API client.
 *
 * Backend: POST /api/mobile/{factoryId}/material-batches/opening
 * Creates opening material batches (no accounts-payable / no purchase order)
 * and posts a single 借1403原材料/贷4001实收资本 accounting voucher.
 *
 * Steve 要求: 期初建账 UI 必须在 仓储管理(warehouse) 模块下, 不是 finance —
 * 仓管录入的是"库存数量+来源单价", 记账是这个动作的副作用, 不是主流程。
 *
 * @since 2026-07-03 (六膳门 建账 — 215 条物料期初库存)
 */
import { get, post } from '@/api/request';
import type { ApiResponse } from '@/types/api';

// ==================== Types ====================

export interface OpeningInventoryItem {
  materialTypeId: string;
  warehouseId: string;
  quantity: number;
  /** null/undefined = 无单价, 后端仅建数量不计入记账金额 (honest — 不假设 0 元) */
  unitPrice: number | null;
  batchNumber?: string;
  productionDate?: string;
  expiryDate?: string;
}

export interface OpeningInventoryRequest {
  items: OpeningInventoryItem[];
  remark?: string;
}

export interface OpeningInventoryResult {
  createdBatches: number;
  voucherId: string | null;
  skippedNoPriceCount: number;
}

/** 物料主数据 (原材料类型字典) 精简字段, 用于建账页物料下拉。 */
export interface MaterialTypeOption {
  id: string;
  name: string;
  code?: string;
  category?: string;
}

// ==================== API functions ====================

/** 创建期初库存批次 + 记账凭证 (借1403原材料/贷4001实收资本)。 */
export function createOpeningInventory(factoryId: string, body: OpeningInventoryRequest) {
  return post<OpeningInventoryResult>(`/${factoryId}/material-batches/opening`, body);
}

/** 原材料类型字典 (与 raw-material-types/active 同源, 建账物料下拉复用)。 */
export function listActiveMaterialTypes(factoryId: string): Promise<ApiResponse<MaterialTypeOption[]>> {
  return get<MaterialTypeOption[]>(`/${factoryId}/raw-material-types/active`);
}
