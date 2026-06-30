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
  /**
   * 半成品库存(SFI)投料 (半成品直接产成品)。true 时 sourceBatchNumber 指向常驻半成品库存
   * (SemiFinishedInventory.intermediateBatchNo), 后端保存不写 MaterialConsumption,
   * 小结时经 consumeClerkSemi 扣减常驻 SFI。默认 false (普通 in-plan 在制 WIP 引用)。
   */
  semiFinished?: boolean;
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
  /** 该工序实际操作日 (跨天: 焯水/熟制各记各日) → 后端成本报工按真实日期归集. ISO "YYYY-MM-DD" */
  processDate?: string;
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
  /** SP-G G3a: 副产品列表 (气调: 料头等) */
  byproducts?: Array<{ name: string; quantity: number; unit: string; unitPrice?: number }>;
  /** SP-G G3a: 留样数量 (气调: 留样盒数) */
  sampleRetainQuantity?: number;
  /** SP-G G3a: 包装明细 (来自产品-工序配置, 气调不在此录) */
  packagingDetail?: Array<Record<string, unknown>>;
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
 *
 * getInventory (per-process) 只填基础 6 字段; getInventoryYieldCard (plan-wide)
 * 额外填充双出成率扩展字段 (processOrder / processName / unit / stepYieldRate / cumulativeYieldRate).
 */
export interface ProcessSheetInventorySourceBreakdown {
  sourceBatchNumber?: string | null;
  feedQuantity?: number | null;
  sourceProducedQuantity?: number | null;
  sourceConsumedRatio?: number | null;
  inheritedRawEquivalentQuantity?: number | null;
  inheritedCost?: number | null;
}

export interface ProcessSheetInventoryItem {
  batchNumber: string;
  produced: number;
  used: number;
  remaining: number;
  status: 'ACTIVE' | 'DEPLETED' | 'COMPLETED';
  unitPrice?: number | null;
  rowTotalCost?: number | null;
  inputQuantity?: number | null;
  sourceBatchNumber?: string | null;
  feedQuantity?: number | null;
  sourceProducedQuantity?: number | null;
  sourceConsumedRatio?: number | null;
  inheritedRawEquivalentQuantity?: number | null;
  inheritedCost?: number | null;
  addedCost?: number | null;
  sourceBreakdowns?: ProcessSheetInventorySourceBreakdown[] | null;
  // F006 双出成率扩展字段 (getInventoryYieldCard 填充; getInventory 兼容留 null)
  /** 链内工序序号 */
  processOrder?: number | null;
  /** 工序名称 */
  processName?: string | null;
  /** 本道产出单位 */
  unit?: string | null;
  /** 对上工序出成率 (%) = 本道产出 / 本道投入 × 100; null = 无投入数据或除数为0 */
  stepYieldRate?: number | null;
  /** 对原料累计出成率 (%) = 本道产出(折算首道单位) / 首道投入 × 100; null = 跨单位无折算系数 */
  cumulativeYieldRate?: number | null;
}

/**
 * F006 双出成率: 计划级半成品库存卡 (所有工序汇总视图).
 * GET /{factoryId}/production-plans/{planId}/process-sheet/inventory/yield-card
 *
 * 注意: 路径不含 ?process= 参数 — 返回该计划所有工序的 WIP 行, 按 processOrder 升序.
 * (⚠️ 不要在路径前加 /api/mobile — baseURL 已在 request.ts 设置, 见文件顶注释)
 */
export function getInventoryYieldCard(
  factoryId: string,
  planId: string,
): Promise<ApiResponse<ProcessSheetInventoryItem[]>> {
  return get<ProcessSheetInventoryItem[]>(`${sheetBase(factoryId, planId)}/inventory/yield-card`);
}

/**
 * 行级操作记录 (mirrors ProcessSheetRowHistoryView Java DTO).
 * 某一行的一次变更 (CREATE / UPDATE / DELETE).
 */
export interface ProcessSheetRowHistoryView {
  id: number;
  /** 操作类型 */
  operation: 'CREATE' | 'UPDATE' | 'DELETE';
  /** 变更前字段快照 (CREATE 时 null) */
  beforeValue: Record<string, unknown> | null;
  /** 变更后字段快照 (DELETE 时 null) */
  afterValue: Record<string, unknown> | null;
  /** 人类可读摘要: "字段: 旧→新" 列表 */
  diffSummary: string | null;
  /** 操作人 userId (可能为 null) */
  operatorId: number | null;
  /** 变更时间 (ISO datetime) */
  createdAt: string;
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
  /**
   * BY_STOCK 小结时间戳 (ISO-8601 字符串)。
   * null = 未小结 (可编辑); 非 null = 已小结转结到批次 (前端折叠只读)。
   */
  interimSettledAt: string | null;
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
 * GET /{factoryId}/production-plans/{planId}/process-sheet/inventory?process={process}[&processOrder={n}]
 *
 * processOrder (可选): SP-F role-mode fix — role-mode 下多道普通工序共享同一 archetype
 * process_code (如 'chaoshui'), 传 processOrder (链内唯一) 隔离各道库存; 不传则后端 code-only 回退.
 */
export function getInventory(
  factoryId: string,
  planId: string,
  process: string,
  processOrder?: number,
): Promise<ApiResponse<ProcessSheetInventoryItem[]>> {
  return get<ProcessSheetInventoryItem[]>(`${sheetBase(factoryId, planId)}/inventory`, {
    params: { process, ...(processOrder !== undefined ? { processOrder } : {}) },
  });
}

/**
 * 回读本工序所有已存行 (用于重开/编辑时恢复表格状态).
 * GET /{factoryId}/production-plans/{planId}/process-sheet/rows?process={process}[&processOrder={n}]
 *
 * processOrder (可选): 同 getInventory — role-mode 下隔离同 archetype 多工序的行; 不传则后端 code-only 回退.
 */
export function getRows(
  factoryId: string,
  planId: string,
  process: string,
  processOrder?: number,
): Promise<ApiResponse<ProcessSheetRowView[]>> {
  return get<ProcessSheetRowView[]>(`${sheetBase(factoryId, planId)}/rows`, {
    params: { process, ...(processOrder !== undefined ? { processOrder } : {}) },
  });
}

/**
 * SP-G P3: 读取某一行的操作记录时间线 (行级 diff 审计, 时间倒序).
 * GET /{factoryId}/production-plans/{planId}/process-sheet/row/{clientRowId}/history?process={process}
 */
export function getRowHistory(
  factoryId: string,
  planId: string,
  process: string,
  clientRowId: string,
): Promise<ApiResponse<ProcessSheetRowHistoryView[]>> {
  return get<ProcessSheetRowHistoryView[]>(
    `${sheetBase(factoryId, planId)}/row/${encodeURIComponent(clientRowId)}/history`,
    { params: { process } },
  );
}

// =========================================================================
// Raw material batch (for 修油 首道 原料领料 dropdown)
// =========================================================================

/**
 * 可用原料批次 (status=AVAILABLE).
 * Mirrors the pattern used by production/plans/list.vue §loadWipAndMaterialOptions.
 * GET /{factoryId}/material-batches/status/AVAILABLE
 */
export interface RawMaterialBatchOption {
  id: string;
  batchNumber: string | null;
  materialName: string | null;
  materialTypeName: string | null;
  warehouseId?: string | null;
  currentQuantity: number | string | null;
  quantity: number | string | null;
  quantityUnit: string | null;
  unit: string | null;
  unitPrice: number | null;
  /** Present when the backend returns it; 'PRODUCTION_BATCH' means WIP/clerk batch. */
  sourceDocType?: string | null;
}

export function getAvailableRawBatches(
  factoryId: string,
  params: { warehouseId?: string; productTypeId?: string } = {},
): Promise<ApiResponse<RawMaterialBatchOption[] | { content: RawMaterialBatchOption[] }>> {
  return get<RawMaterialBatchOption[] | { content: RawMaterialBatchOption[] }>(
    `/${factoryId}/material-batches/status/AVAILABLE`,
    { params: { size: 200, ...params } },
  );
}

// =========================================================================
// 半成品库存 (SFI) — 逐道录入混锅可选常驻半成品作投料来源 (半成品直接产成品)
// =========================================================================

/**
 * 工厂级半成品重量库存项 (mirrors WipRowDTO from /semi-finished/inventory).
 * 仅重量字段, 不含成本 (后端 C3 视图刻意不暴露 unitCost)。
 */
export interface SemiFinishedStockItem {
  intermediateBatchNo: string;
  sourceWorkProcessTaskId?: number | null;
  processOrder?: number | null;
  processName?: string | null;
  productTypeId?: string | null;
  producedQuantity?: number | null;
  consumedQuantity?: number | null;
  availableQuantity: number;
  unit?: string | null;
  status?: string | null;
  productTypeName?: string | null;
  batchId?: number | null;
}

/**
 * 工厂级半成品重量库存快照 (全状态; 调用方按 availableQuantity>0 过滤可投料项)。
 * GET /{factoryId}/semi-finished/inventory
 * (⚠️ 不要在路径前加 /api/mobile — baseURL 已在 request.ts 设置, 见文件顶注释)
 */
export function getSemiFinishedInventory(
  factoryId: string,
): Promise<ApiResponse<SemiFinishedStockItem[]>> {
  return get<SemiFinishedStockItem[]>(`/${factoryId}/semi-finished/inventory`);
}
