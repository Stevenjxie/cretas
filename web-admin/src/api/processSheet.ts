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
  /**
   * 2B.2 端口身份 (多产出/多投入必带, 区分同 SKU 出现在不同投入端口)。可空 (legacy 单产出/非 workflow)。
   */
  workflowPortId?: string;
  /** 对应 workflow 物料 Cell (节点) id。 */
  materialNodeId?: string;
  /** 该端口物料 SKU (原料 RawMaterialType id)。 */
  skuId?: string;
}

/** 新报工契约：按物料汇总的实际投料量，正式提交时由后端自动分摊生产库批次。 */
export interface MaterialInputTotal {
  materialTypeId: string;
  quantity: number;
  unit: 'kg';
  workflowPortId?: string;
  materialNodeId?: string;
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
  /**
   * ①c 成品库存(FG)投料 (成品作投料来源)。true 时 sourceBatchNumber 指向常驻成品库存
   * (FinishedGoodsBatch.batchNumber), 后端保存不写 MaterialConsumption, 小结时经
   * consumeForFeedStrict 严格扣减成品 (loud-fail)。与 semiFinished 互斥。默认 false。
   */
  finishedGoods?: boolean;

  // 2B.2 端口身份 (多投入合流必带, 区分同 SKU 出现在不同投入端口)。可空 (legacy 单产出/非 workflow)。
  /** 对应 workflow 投入端口 id。 */
  workflowPortId?: string;
  /** 对应 workflow 物料 Cell (节点) id。 */
  materialNodeId?: string;
  /** 该端口物料 SKU (半成品/成品 ProductType id)。 */
  skuId?: string;
}

/**
 * 2B.2 多产出行 (mirrors ProcessSheetRowRequest.OutputLine Java DTO).
 * 一个产出 = 一个产品 + 数量。产品/端口由 workflow 产出端口固定 (只读, fool-proof Rule 2/3
 * — 操作员不能自由选产品), 操作员只填数量 (+可选成品重)。
 */
export interface OutputLine {
  /** 产出产品 (半成品/成品 ProductType id)。 */
  productTypeId: string;
  /** 对应 workflow 产出端口 id (B3 逐产出对齐端口类型/单位)。可空 (非 workflow 计划)。 */
  workflowPortId?: string;
  /** 产出数量 (产出单位, 如 盒/kg) → 直接作为该产出的入库量。 */
  quantity: number;
  /** 产出单位。 */
  unit?: string;
  /** 产出是否成品 (true → FG, false → 半成品 SFI)。 */
  finished: boolean;
  /** 对应 workflow 产出物料 Cell (节点) id (端口身份的另一半, 区分同 SKU 多端口)。 */
  materialNodeId?: string;
  /** 可选成品重(kg): 多数多产出场景留空按 quantity 入库。 */
  productWeight?: number;
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
  /** 兼容旧接口：等同 outputUnit。 */
  unit?: string;
  inputUnit?: string;
  outputUnit?: string;
  /** 多工时时段, 后端 Σ 得总工时 */
  laborSegments?: LaborSegment[];
  /** 原料领料 (修油首道): 消耗原料 MaterialBatch */
  rawMaterialInputs?: RawInput[];
  /** 新路径只传总量，不传来源批次；旧 rawMaterialInputs 继续兼容。 */
  materialInputTotals?: MaterialInputTotal[];
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
  /**
   * 2B.2 多产出 (fan-out): 一道工序一次报工同时产出多个产品 (如熟成鸡装箱同产 350g/400g,
   * 筛选同产 合格半成品+不合格损耗)。多产出 = 两组独立事实: input allocations (本请求的
   * rawMaterialInputs/upstreamSources, 一次全量, 不按重量/数量拆分/推断) + output lines
   * (本字段, 逐产出各自入库)。为空 / size<=1 → 走原单产出路径 (向后兼容, F006 现有流不受影响)。
   * 多产出时顶层 outputQuantity 仅为满足后端 @NotNull (发 Σquantity), 实际逐 output 用各自 quantity。
   */
  outputs?: OutputLine[];
  /**
   * 2B.2 内部标记 (合成产出行专用): FE 不发送 —— 仅在 GET rows 重载读回的 payload 中出现,
   * 标记本行是多产出分解出的产出行 (true)。前端据此按 multiOutputBaseRowId 把 N 个持久化行
   * 归组显示为一条多产出录入行。
   */
  multiOutputMember?: boolean;
  /** 2B.2 内部: 多产出组的 base clientRowId (= 原始 clientRowId, 各产出行 clientRowId = base#i)。FE 不发送。 */
  multiOutputBaseRowId?: string;
  /** 2B.2 本行产出对应的 workflow 产出端口 id (端口身份; 单产出/legacy 可空)。随 payload 持久化供 FE 重载映射。 */
  workflowPortId?: string;
  /** 2B.2 本行产出对应的 workflow 产出物料 Cell (节点) id。 */
  materialNodeId?: string;
  /**
   * G2: 本工序自定义字段值 (如 {baume: 12.5, remark: "..."})。key 集合受该工序
   * WorkProcess.customFieldSchema 约束 —— 未配置 schema 的工序传任何 key 都会被后端 400 拒绝
   * (见 ProcessSheetServiceImpl#validateCustomFields)。
   */
  customFields?: Record<string, unknown>;
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
  /**
   * 2B.2 多产出: 本次报工分解出的各产出批次明细 (单产出时为 null/undefined)。FE 重载过程单会
   * 拿到 N 行持久化行, 此处仅为保存后的即时反馈。顶层 batchId/batchNumber 取首产出为代表
   * (成本相关字段 rowTotalCost/unitPrice/yieldRate 顶层为 null — 工序不处理成本分摊)。
   */
  outputs?: OutputResult[] | null;
  submissionStatus?: 'DRAFT' | 'SUBMITTED';
  inputAllocations?: ProductionInputAllocation[];
}

export interface ProductionInputAllocation {
  materialTypeId: string;
  materialBatchId: string;
  batchNumber: string | null;
  quantity: number;
  unit: string;
  allocationOrder: number;
}

/** 单个产出的物化结果 (mirrors ProcessSheetRowResult.OutputResult Java DTO)。 */
export interface OutputResult {
  clientRowId: string;
  /** 端口身份: 对应 workflow 产出端口 id。 */
  workflowPortId: string | null;
  /** 端口身份: 对应 workflow 产出物料 Cell (节点) id。 */
  materialNodeId: string | null;
  /** 产出 SKU (= productTypeId)。 */
  productTypeId: string;
  /** 生成的产出批次 id (generatedBatchId)。 */
  batchId: number | null;
  /** 生成的产出批次号。 */
  batchNumber: string | null;
  quantity: number;
  unit: string | null;
  /** 该产出行物化成本 (existing 机制副产, Workflow 不干预成本字段)。 */
  rowTotalCost: number | null;
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
  /** ② 批次下拉补品名: 产品类型名称 (getInventory 填充; getInventoryYieldCard 留 null) */
  productTypeName?: string | null;
  /** ② 批次下拉补生产日期: WIP 批次生产日期 (ISO "YYYY-MM-DD"; getInventory 填充) */
  productionDate?: string | null;
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
  /** 流程日期: 该工序实际操作日期 (ISO "YYYY-MM-DD"; 取自逐工序录入表单「流程日期」, getInventory 留 null) */
  processDate?: string | null;
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
  submissionStatus?: 'DRAFT' | 'SUBMITTED' | null;
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

/** 只保存草稿，不解析或占用生产库批次。 */
export function saveDraftRow(
  factoryId: string,
  planId: string,
  body: ProcessSheetRowRequest,
): Promise<ApiResponse<ProcessSheetRowResult>> {
  return post<ProcessSheetRowResult>(`${sheetBase(factoryId, planId)}/row/draft`, body);
}

/** 正式报工；后端锁定生产库并按 FEFO 自动分摊来源批次。 */
export function submitRow(
  factoryId: string,
  planId: string,
  body: ProcessSheetRowRequest,
): Promise<ApiResponse<ProcessSheetRowResult>> {
  return post<ProcessSheetRowResult>(`${sheetBase(factoryId, planId)}/row/submit`, body, {
    _handledErrorCodes: ['PRODUCTION_STOCK_SHORTAGE'],
  });
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
// 2B Task F1: workflow 快照 → clerk 过程单配置 (mirrors WorkflowClerkSheetConfigDTO Java DTO)
// =========================================================================

/**
 * 工序端口 (mirrors WorkflowClerkSheetConfigDTO.PortDescriptor Java DTO).
 * `materialName`/`unit` 为 null 时说明 skuId 指向的物料/产品已被删除 (skuResolved=false) —
 * FE 应显示 "SKU 已失效, 请回 Workflow 配置" 提示, 不得崩溃 (fool-proof Rule 5)。
 */
export interface WorkflowPortDescriptor {
  workflowPortId: string;
  /** 2B.2 端口身份: workflow 物料 Cell 节点 id。 */
  materialNodeId?: string;
  materialKind: 'RAW_MATERIAL' | 'SEMI_FINISHED' | 'FINISHED_GOOD';
  skuId: string;
  materialName: string | null;
  unit: string | null;
  /** 固定包装成品的 SKU 单位净重（克/基本单位）；缺失时前端不得猜测重量换算。 */
  gramsPerUnit?: number | null;
  required: boolean;
  /** false = skuId 已无法解析 (物料/产品被删除)。 */
  skuResolved: boolean;
  /** 仅 output 端口有意义: materialKind === 'FINISHED_GOOD'。 */
  finished: boolean;
}

/**
 * 单道工序描述符 (mirrors WorkflowClerkSheetConfigDTO.ProcessDescriptor Java DTO).
 * `output` 为 null 时说明该 workflow 任务无产出端口 (理论不应出现于可报工任务, 防御性)。
 */
export interface WorkflowProcessDescriptor {
  workflowNodeId: string;
  workProcessId: string;
  processName: string | null;
  /** 工序类别 (WorkProcess.processCategory, 熟制/注射/…) — Slice C: workflow 计划报工据此驱动锅数录入。 */
  processCategory: string | null;
  defaultCostCategory: string | null;
  processOrder: number;
  plannedUnit: string | null;
  allowMultipleUpstreamSources: boolean;
  allowFinishedGoodsSource: boolean;
  /** 原样透传 WorkProcess.customFieldSchema (JSON, 与 legacy ProcessSheetCustomFieldDef[] 同源)。 */
  customFieldSchema: unknown | null;
  inputs: WorkflowPortDescriptor[];
  /** 首个产出端口。向后兼容单产出 FE; 多产出时 == outputs[0]。 */
  output: WorkflowPortDescriptor | null;
  /**
   * 2B.2: 全部产出端口 (按 ordinal 排序)。单产出时 size==1。多产出时 (length>1) FE 逐端口
   * 录入 N 条产出 (产品只读=端口 SKU, 只填数量) —— 这是"是否多产出"的唯一判据, 不是 toggle。
   */
  outputs: WorkflowPortDescriptor[];
}

/**
 * workflow 批次快照投影 (mirrors WorkflowClerkSheetConfigDTO Java DTO).
 * 计划没有 workflow 批次 (legacy 计划) 时后端 data 为 null, FE 据此回落原
 * `getProductWorkProcesses` 路径 — additive, 不改变 legacy 行为。
 */
export interface WorkflowClerkSheetConfig {
  workflowBatchId: number;
  workflowInstanceId: number;
  productTypeId: string;
  processes: WorkflowProcessDescriptor[];
}

/**
 * 2B Task B2: 该计划关联的 workflow 批次快照投影 (供 ProcessSheet.vue `resolveProcesses()` 消费).
 * GET /{factoryId}/production-plans/{planId}/process-sheet/workflow-config
 *
 * 只有 success=true 且 data=null 才表示 legacy 计划；请求失败或 Workflow 快照损坏必须阻断，禁止回落。
 */
export function getWorkflowSheetConfig(
  factoryId: string,
  planId: string,
): Promise<ApiResponse<WorkflowClerkSheetConfig | null>> {
  return get<WorkflowClerkSheetConfig | null>(`${sheetBase(factoryId, planId)}/workflow-config`);
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
  /** 原料类型 id (raw_material_types.id, 后端 AVAILABLE 端点返回)。workflow 逐道报工按此过滤到本道所需原料。 */
  materialTypeId?: string | null;
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
  /** ② 生产日期 (逐道投料下拉展示; 仅 picker 过滤查询填充, ISO "YYYY-MM-DD") */
  productionDate?: string | null;
  /** ② 单位成本 (逐道投料下拉展示; 诚实 null = 成本未知; 仅 picker 过滤查询填充) */
  unitCost?: number | null;
  /**
   * ② 每盒/份标准克重 (ProductType.gramsPerUnit, "1 份/盒 = X 克")。
   * 计数单位 (盒/个/件/只) 半成品作 kg 道投料来源时, 前端据此把 kg⇄盒 折算 (余 N 盒 ≈ M kg)。
   * 诚实 null: 未配每盒克重 → null (前端据此拦截盒装投料, 禁止臆造)。
   */
  gramsPerUnit?: number | null;
}

/**
 * ①c 成品作投料来源 — 可投料成品库存项 (mirrors FinishedGoodsStockItem Java DTO)。
 * GET /{factoryId}/finished-goods/inventory
 */
export interface FinishedGoodsStockItem {
  batchNumber: string;
  productTypeId?: string | null;
  productTypeName?: string | null;
  productionDate?: string | null;
  availableQuantity: number;
  unit?: string | null;
  /** 单位成本 (诚实 null = 成本未知; 区别于售价) */
  unitCost?: number | null;
  /**
   * 每盒/份标准克重 (ProductType.gramsPerUnit, "1 份/盒 = X 克")。
   * 计数单位 (盒/个/件/只) 成品作 kg 道投料来源时, 前端据此把 kg⇄盒 折算 (余 N 盒 ≈ M kg)。
   * 诚实 null: 未配每盒克重 → null (前端据此拦截盒装投料, 禁止臆造 1盒=1kg)。
   */
  gramsPerUnit?: number | null;
}

/**
 * 半成品重量库存查询防呆过滤 (07-01 客户会议需求)。省略字段 → 后端不按该维度过滤。
 */
export interface SemiFinishedInventoryFilter {
  /**
   * 同族: 计划产品类型 id → 后端解析成"产品族"(以原料为主自动识别), 只列同族半成品。
   * 注意不是按 productTypeId 精确匹配: 熟制前半成品在同族内通用 (卤猪蹄/椒麻猪蹄/猪蹄冠共用"猪蹄"半成品),
   * 猪蹄计划显示所有猪蹄族半成品 (跨兄弟成品), 但不显牛肉。
   */
  productTypeId?: string;
  /** 阶段: 当前道工序序 → 只列 processOrder 严格小于此值的更早阶段半成品 (防回锅)。 */
  maxProcessOrder?: number;
}

/**
 * 工厂级半成品重量库存快照 (全状态; 调用方按 availableQuantity>0 过滤可投料项)。
 * GET /{factoryId}/semi-finished/inventory
 * (⚠️ 不要在路径前加 /api/mobile — baseURL 已在 request.ts 设置, 见文件顶注释)
 *
 * @param filter 可选防呆过滤 (同族 productTypeId→family + 阶段 maxProcessOrder)。省略 → 全量快照 (向后兼容)。
 */
export function getSemiFinishedInventory(
  factoryId: string,
  filter?: SemiFinishedInventoryFilter,
): Promise<ApiResponse<SemiFinishedStockItem[]>> {
  const params: Record<string, string | number> = {};
  if (filter?.productTypeId) params.productTypeId = filter.productTypeId;
  if (filter?.maxProcessOrder != null) params.maxProcessOrder = filter.maxProcessOrder;
  return get<SemiFinishedStockItem[]>(`/${factoryId}/semi-finished/inventory`, { params });
}

/**
 * ①c 成品作投料来源 — 工厂级可投料成品库存 (AVAILABLE 且可用量>0)。
 * GET /{factoryId}/finished-goods/inventory[?productTypeId=]
 * (⚠️ 不要在路径前加 /api/mobile — baseURL 已在 request.ts 设置, 见文件顶注释)
 *
 * @param productTypeId 可选产品族过滤 (传当前计划产品 → 后端解析成族键仅返回同族成品; 成品是终态无阶段过滤)。
 */
export function getFinishedGoodsInventory(
  factoryId: string,
  productTypeId?: string,
): Promise<ApiResponse<FinishedGoodsStockItem[]>> {
  const params: Record<string, string> = {};
  if (productTypeId) params.productTypeId = productTypeId;
  return get<FinishedGoodsStockItem[]>(`/${factoryId}/finished-goods/inventory`, { params });
}
