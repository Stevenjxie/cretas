/**
 * SP-F 逐工序电子表格 API
 *
 * Base URL is /api/mobile (set in request.ts — `baseURL: '/api/mobile'`).
 * Paths here start with `/${factoryId}/...` WITHOUT a leading `/api/mobile`.
 * Adding `/api/mobile` here would double the prefix and cause factory-guard
 * to read the literal string "api" as the factoryId → 403 on every call.
 * (See feedback_web_admin_double_api_mobile_prefix in project memory.)
 *
 * Endpoint base: /{factoryId}/production-plans/{planId}/process-sheet/...
 */
import { get, post, del } from './request';
import type { ApiResponse } from '@/types/api';

// =========================================================================
// TS interfaces — mirror backend DTOs exactly (Java camelCase → TS camelCase)
// =========================================================================

/** 工时时段 (mirrors ProcessSheetRowRequest.LaborSegment) */
export interface LaborSegment {
  startTime: string;   // ISO time string, e.g. "08:30"
  endTime: string;
  workerCount: number;
}

/** 原料领料项 (mirrors ProcessSheetRowRequest.RawInput) */
export interface RawInput {
  materialBatchId: string;
  quantity: number;
}

/** 上游混锅来源引用, 按真实 batchNumber (mirrors ProcessSheetRowRequest.UpstreamRef) */
export interface UpstreamRef {
  sourceBatchNumber: string;
  feedQuantityKg: number;
}

/**
 * 增量单行请求体 (mirrors ProcessSheetRowRequest Java DTO).
 * 一行 = 一个批次的一道工序. 上游引用走持久化的 batchNumber (跨请求).
 */
export interface ProcessSheetRowRequest {
  /** 客户端稳定行 id, 用作 upsert 键 (工厂+计划+工序+clientRowId 四元组唯一) */
  clientRowId: string;
  /** 工序代码: "xiuyou" | "chaoshui" | "shuzhi" | ... */
  processCode: string;
  processOrder: number;
  processName?: string;
  productTypeId: string;
  /** 可空 — 首存时系统生成 (CLK-W-/CLK-B-), re-save 时传已有值 */
  batchNumber?: string;
  /** 切片内均 false (未到气调成品批) */
  finished: boolean;
  /** 投料重量 (修油=出库重量, 焯水/熟制=从上游来) */
  inputQuantity?: number;
  /** 产出数量; >0 才物化 WIP 批 */
  outputQuantity: number;
  unit?: string;   // 默认 "kg"
  /** 多工时时段, 后端 Σ 得总工时 */
  laborSegments?: LaborSegment[];
  /** 原料领料 (修油首道): 消耗原料 MaterialBatch */
  rawMaterialInputs?: RawInput[];
  /** 混锅来源 (熟制): 多个上游焯水批按 batchNumber 引用 */
  upstreamSources?: UpstreamRef[];
  potCount?: number;
  /** 逐锅原料重量; potCount > 1 时必填 */
  potRawKgs?: number[];
  /** true 时触发 RecipeCostCalculator (熟制调料成本) */
  seasoningStep: boolean;
  /** 可选防双击 key (同 clientRowId 在同一 saveRow 内使用) */
  idempotencyKey?: string;
}

/**
 * 增量单行响应 (mirrors ProcessSheetRowResult Java DTO).
 * batchNumber 由系统生成/确认, 作下游行上游下拉的选项.
 */
export interface ProcessSheetRowResult {
  clientRowId: string;
  /** 物化的 ProductionBatch.id; null = outputQty<=0 未物化 */
  batchId: number | null;
  /** 系统生成/确认的批次号 (下游下拉用此值) */
  batchNumber: string | null;
  /** outputQty / inputQty × 100 */
  yieldRate: number | null;
  /** 该行物化成本 (kg 级) */
  rowTotalCost: number | null;
  /** rowTotalCost / outputQty (= WIP 批单价) */
  unitPrice: number | null;
  /** true = update-in-place 覆盖已有行, false = 新建 */
  updated: boolean;
  /** false = outputQty<=0, 未生成 WIP 批 (非法上游) */
  materialized: boolean;
  /** 软预警: 调料配方缺失 / 超量 / labor rate fallback 等 */
  warnings: string[];
}

/**
 * 半成品库存项 (mirrors ProcessSheetInventoryItem Java DTO).
 * 由后端经 process_sheet_rows join 派生, 供上游下拉 + 库存子表.
 */
export interface ProcessSheetInventoryItem {
  batchNumber: string;
  produced: number;
  used: number;
  remaining: number;
  status: 'ACTIVE' | 'DEPLETED';
  unitPrice: number;
}

/**
 * 已存行回读视图 (mirrors ProcessSheetRowView Java DTO).
 * row_payload 原样返回, 供前端重建行状态.
 */
export interface ProcessSheetRowView {
  clientRowId: string;
  batchNumber: string | null;
  batchId: number | null;
  /** DRAFT = outputQty<=0 未物化; SAVED = 已物化 */
  rowStatus: 'SAVED' | 'DRAFT';
  materialized: boolean;
  /** 原始录入 payload (row_payload JSON 原样回读) */
  payload: ProcessSheetRowRequest;
}

// =========================================================================
// API functions
// =========================================================================

const sheetBase = (factoryId: string, planId: string) =>
  `/${factoryId}/production-plans/${planId}/process-sheet`;

/**
 * 增量保存单行 (upsert — update-in-place by clientRowId).
 * POST /{factoryId}/production-plans/{planId}/process-sheet/row
 */
export function saveRow(
  factoryId: string,
  planId: string,
  body: ProcessSheetRowRequest,
): Promise<ApiResponse<ProcessSheetRowResult>> {
  return post<ProcessSheetRowResult>(`${sheetBase(factoryId, planId)}/row`, body);
}

/**
 * 删除单行及其全部物化产物 (产出WIP批 / 成本边 / 报工).
 * 有下游消耗时返 409 + actionHint.
 * DELETE /{factoryId}/production-plans/{planId}/process-sheet/row/{clientRowId}
 */
export function deleteRow(
  factoryId: string,
  planId: string,
  clientRowId: string,
): Promise<ApiResponse<void>> {
  return del<void>(`${sheetBase(factoryId, planId)}/row/${encodeURIComponent(clientRowId)}`);
}

/**
 * 读半成品库存 (经 process_sheet_rows join, 范围限本计划).
 * 仅列 materialized && remaining>0 的 WIP 批供上游下拉.
 * GET /{factoryId}/production-plans/{planId}/process-sheet/inventory?process={process}
 */
export function getInventory(
  factoryId: string,
  planId: string,
  process: string,
): Promise<ApiResponse<ProcessSheetInventoryItem[]>> {
  return get<ProcessSheetInventoryItem[]>(`${sheetBase(factoryId, planId)}/inventory`, {
    params: { process },
  });
}

/**
 * 回读本工序所有已存行 (用于重开/编辑时恢复表格状态).
 * GET /{factoryId}/production-plans/{planId}/process-sheet/rows?process={process}
 */
export function getRows(
  factoryId: string,
  planId: string,
  process: string,
): Promise<ApiResponse<ProcessSheetRowView[]>> {
  return get<ProcessSheetRowView[]>(`${sheetBase(factoryId, planId)}/rows`, {
    params: { process },
  });
}
