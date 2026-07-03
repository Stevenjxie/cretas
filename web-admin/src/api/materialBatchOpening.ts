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

/**
 * Field names mirror backend OpeningInventoryResult.java 1:1 — do NOT rename
 * without checking the DTO first (2026-07-03 bug: this interface used to have
 * createdBatches/skippedNoPriceCount which don't exist on the backend response,
 * so res.data.createdBatches was always undefined → toast showed "已建 undefined 条").
 */
export interface OpeningInventoryResult {
  /** 幂等业务键 (回显; 重复提交用它命中) */
  batchKey: string;
  /** 是否幂等命中 (true=之前已建过, 本次未新建) */
  idempotentHit: boolean;
  /** 本次建立的批次数 */
  createdCount: number;
  /** 计入期初凭证的行数 (有单价) */
  pricedCount: number;
  /** 未录单价的行数 (诚实-null: 建了批次但不计入凭证金额) */
  uncostedCount: number;
  /** 期初存货总价值 (Σ数量×单价, 即凭证借方 1403 金额) */
  totalOpeningValue: number;
  /** 期初凭证ID (全部行未录价时为 null) */
  voucherId: string | null;
  /** 期初凭证号 (全部行未录价时为 null) */
  voucherNumber: string | null;
  /** 建立的批次ID列表 */
  batchIds: string[];
  /** 建立的批次号列表 */
  batchNumbers: string[];
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
