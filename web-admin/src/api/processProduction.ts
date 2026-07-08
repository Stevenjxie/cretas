/**
 * Process-centric production mode API client
 * Work processes, product-process associations, process tasks, approvals
 */
import { get, post, put, del } from './request';

// === Work Processes ===

export function getWorkProcesses(factoryId: string, params?: Record<string, unknown>) {
  return get<{ content: WorkProcessItem[]; totalElements: number }>(
    `/${factoryId}/work-processes`, { params }
  );
}

export function getActiveWorkProcesses(factoryId: string) {
  return get<WorkProcessItem[]>(`/${factoryId}/work-processes/active`);
}

export function createWorkProcess(factoryId: string, data: Partial<WorkProcessItem>) {
  return post<WorkProcessItem>(`/${factoryId}/work-processes`, data);
}

export function updateWorkProcess(factoryId: string, id: string, data: Partial<WorkProcessItem>) {
  return put<WorkProcessItem>(`/${factoryId}/work-processes/${id}`, data);
}

export function deleteWorkProcess(factoryId: string, id: string) {
  return del(`/${factoryId}/work-processes/${id}`);
}

export function toggleWorkProcessStatus(factoryId: string, id: string) {
  return put<WorkProcessItem>(`/${factoryId}/work-processes/${id}/toggle-status`);
}

/** C5: Fetch duplicate clusters — groups sharing same (processName, processCategory, unit). */
export function getWorkProcessDuplicates(factoryId: string) {
  return get<WorkProcessDuplicateGroup[]>(`/${factoryId}/work-processes/duplicates`);
}

// === Product-Work Process Associations ===

export function getProductWorkProcesses(factoryId: string, productTypeId: string) {
  return get<ProductWorkProcessItem[]>(
    `/${factoryId}/product-work-processes`, { params: { productTypeId } }
  );
}

export function createProductWorkProcess(factoryId: string, data: Partial<ProductWorkProcessItem>) {
  return post<ProductWorkProcessItem>(`/${factoryId}/product-work-processes`, data);
}

export function deleteProductWorkProcess(factoryId: string, id: number) {
  return del(`/${factoryId}/product-work-processes/${id}`);
}

export function batchSortProductWorkProcesses(
  factoryId: string,
  items: Array<{ id: number; processOrder: number }>
) {
  return put(`/${factoryId}/product-work-processes/batch-sort`, { items });
}

export function updateProductWorkProcess(
  factoryId: string,
  id: number,
  data: Partial<ProductWorkProcessItem>
) {
  return put<ProductWorkProcessItem>(`/${factoryId}/product-work-processes/${id}`, data);
}

export function getProductWorkProcessRecommendation(
  factoryId: string,
  productTypeId: string,
  limit = 5
) {
  return get<ProductWorkProcessRecommendation>(
    `/${factoryId}/product-types/${productTypeId}/work-process-recommendation`,
    { params: { limit } }
  );
}

// === Process Tasks ===

export function getActiveTasks(factoryId: string) {
  return get<ProcessTaskItem[]>(`/${factoryId}/process-tasks/active`);
}

export function getProcessTasks(factoryId: string, params?: Record<string, unknown>) {
  return get<{ content: ProcessTaskItem[]; totalElements: number }>(
    `/${factoryId}/process-tasks`, { params }
  );
}

export function createProcessTask(factoryId: string, data: Partial<ProcessTaskItem>) {
  return post<ProcessTaskItem>(`/${factoryId}/process-tasks`, data);
}

export function generateTasksFromProduct(factoryId: string, data: { productTypeId: string; sourceCustomerName?: string }) {
  return post<ProcessTaskItem[]>(`/${factoryId}/process-tasks/generate-from-product`, data);
}

export function updateTaskStatus(factoryId: string, taskId: string, status: string, notes?: string) {
  return put<ProcessTaskItem>(`/${factoryId}/process-tasks/${taskId}/status`, { status, notes });
}

export function closeTask(factoryId: string, taskId: string, notes?: string) {
  return put<ProcessTaskItem>(`/${factoryId}/process-tasks/${taskId}/close`, null, {
    params: { notes }
  });
}

export function getRunOverview(factoryId: string, runId: string) {
  return get(`/${factoryId}/process-tasks/run/${runId}`);
}

// === 半成品库存 (WIP) — G6/G7 Wave 4 ===

/**
 * 某生产批次的半成品库存 (WIP) 列表 — 每道工序中间品存量 (产出/已领/余额/状态)。
 * 端点: YieldReportController GET /production/batches/{batchId}/wip。无 WIP → 空数组。
 */
export function getBatchWip(factoryId: string, batchId: string | number) {
  return get<WipRowItem[]>(`/${factoryId}/production/batches/${batchId}/wip`);
}

// === 工序任务 (WorkProcessTask) — T140 批次详情工序明细 ===

/**
 * 查询某生产批次的工序任务列表 (按 processOrder 升序)。
 * 端点: WorkProcessTaskController GET /production/batches/{batchId}/work-process-tasks。
 * 无任务 (批次未 spawn 工序) → 空数组。
 */
export function getBatchWorkProcessTasks(factoryId: string, batchId: string | number) {
  return get<WorkProcessTaskItem[]>(`/${factoryId}/production/batches/${batchId}/work-process-tasks`);
}

// === 单元 F (F006 REQ-21): 分订单出成率聚合 ===

/**
 * 一张销售订单下全部生产批次的出成率聚合 (分订单分产品分工序)。
 * 端点: OrderYieldController GET /production/orders/{orderId}/yield-summary。
 * 单位不可比 → 总投入/总产出/整体出成率为 null (前端显 "—")。订单无批次 → batchCount 0。
 */
export function getOrderYieldSummary(factoryId: string, orderId: string) {
  return get<OrderYieldSummary>(
    `/${factoryId}/production/orders/${orderId}/yield-summary`
  );
}

// === Approval ===

export function getPendingApprovals(factoryId: string, params?: Record<string, unknown>) {
  return get<{ content: ApprovalItem[]; totalElements: number }>(
    `/${factoryId}/process-work-reporting/pending-approval`, { params }
  );
}

export function approveReport(factoryId: string, reportId: number) {
  return put(`/${factoryId}/process-work-reporting/${reportId}/approve`);
}

export function rejectReport(factoryId: string, reportId: number, reason: string) {
  return put(`/${factoryId}/process-work-reporting/${reportId}/reject`, { reason });
}

export function batchApproveReports(factoryId: string, reportIds: number[]) {
  return put(`/${factoryId}/process-work-reporting/batch-approve`, reportIds);
}

// === Types ===

/** C5: A cluster of duplicate work-processes sharing the same name + category + unit. */
export interface WorkProcessDuplicateGroup {
  processName: string;
  processCategory: string | null;
  unit: string;
  /** Always ≥ 2 members. */
  members: WorkProcessItem[];
}

export interface WorkProcessItem {
  id: string;
  processName: string;
  processCategory: string;
  unit: string;
  estimatedMinutes: number | null;
  sortOrder: number;
  isActive: boolean;
  standardYieldMin: number | null;   // P0-3: 标准出成率下限 (0.30 = 30%)
  standardYieldMax: number | null;   // P0-3: 标准出成率上限 (1.35 = 135%, 超收预检基准)
  needsInput: boolean;               // 该工序是否需录投入量 (默认 true)
  outputUnit: string | null;         // 产出单位 (kg→盒/份; 空则沿用 unit)
  standardHourlyRate: number | null; // 标准时薪 (元/小时; null=未配置, 绝不默认 0)
  createdAt: string;
  updatedAt: string;
}

export interface ProductWorkProcessItem {
  id: number;
  productTypeId: string;
  workProcessId: string;
  processOrder: number;
  unitOverride: string | null;
  estimatedMinutesOverride: number | null;
  processName: string;
  processCategory: string;
  defaultUnit: string;
  defaultEstimatedMinutes: number | null;
  /** 默认责任小组长 ID（primary，向后兼容）。null = 未设置；发送 -1 表示清空。 */
  responsibleWorkerId?: number | null;
  /** 只读：后端 join 填充，前端不发送。 */
  responsibleWorkerName?: string | null;
  /**
   * T121 多人负责列表（worker id 数组）。
   * 写操作：前端传此数组，后端对 join 表做 upsert；非空时覆盖 responsibleWorkerId。
   * 读操作：后端填充 join 表结果，供多选下拉回显。
   * null / 空数组 → 回退到 responsibleWorkerId 单值语义。
   */
  assigneeWorkerIds?: number[] | null;
  /**
   * Wave2 可配置报工粒度: 是否需要报工。
   * true (默认) = 逐道报; false = 该工序免报 (spawn 跳过, 不生成报工任务, 配置保留供溯源)。
   * 六扇门只在领料/产出两点报工, 中间工序标 false。
   */
  reportingRequired?: boolean;
  /**
   * 半成品注入工序 (张权 R4): 是否可从中段起步选库里已有半成品(SFI)/成品(FG) 直接投料。
   * true = 该工序逐道录入显示 SFI/FG 选择器 (跳过前段工序接续生产);
   * false/undefined (默认) = 普通工序 (仅现有 archetype 兜底会显 picker)。
   */
  allowSemiFinishedInjection?: boolean;
  /** 是否允许本道工序从多个上游批次混批投料。 */
  allowMultipleUpstreamSources?: boolean;
  /** 是否允许本道工序选择成品库存批次作为投料来源。 */
  allowFinishedGoodsSource?: boolean;
  /** 工序默认成本类别 (报工自动继承; 防呆: 操作员不手填)。RAW_MATERIAL/SEASONING/AUXILIARY/PACKAGING/OTHER */
  defaultCostCategory?: string | null;
  /** 工序默认包装明细模板 [{name,cost}] (膜/气体/标签/其他); 报工自动继承。 */
  packagingTemplate?: Array<{ name: string; cost: number }> | null;
  /** 工序辅料分摊方式 BY_OUTPUT/FIXED_RATIO; 报工自动继承。 */
  auxAllocMethod?: string | null;
  /** 段2(B) 标准出成率 (配方率, 0.85=85%); 投料-产出对账基准 (实际报工率 vs 标准 → 抓多投/误差)。 */
  standardYieldRate?: number | null;
  /** 段2(B) 辅料标准单价 (元/kg); 离线按配方算好, 非实时 BOM; 未配=null 视为 0 不崩。 */
  auxUnitPrice?: number | null;
  /** 段2(B) 元/kg 乘哪侧 kg: INPUT(投入侧)|OUTPUT(产出侧); 保水工序 output>input 必须显式。 */
  auxBasis?: string | null;
}

export interface RecommendedWorkProcess {
  workProcessId: string;
  processName: string;
  processCategory: string | null;
  unit: string | null;
  estimatedMinutes: number | null;
  processOrder: number;
  score: number;
  reason: 'HISTORY' | 'LLM' | string;
}

export interface ProductWorkProcessRecommendation {
  productTypeId: string;
  source: 'HISTORY' | 'LLM' | 'NO_HISTORY' | 'LLM_UNAVAILABLE' | 'NO_CATALOG' | 'LLM_EMPTY' | 'LLM_NO_MATCH' | string;
  notice: string;
  message: string;
  recommendations: RecommendedWorkProcess[];
}

export interface ProcessTaskItem {
  id: string;
  factoryId: string;
  productionRunId: string;
  productTypeId: string;
  workProcessId: string;
  sourceCustomerName: string | null;
  sourceDocType: string;
  plannedQuantity: number;
  completedQuantity: number;
  pendingQuantity: number;
  unit: string;
  startDate: string | null;
  expectedEndDate: string | null;
  status: string;
  estimatedProgress: number;
  confirmedProgress: number;
  targetReached: boolean;
  processName?: string;
  productName?: string;
  createdAt: string;
}

export interface ApprovalItem {
  id: number;
  processTaskId: string;
  workerId: number;
  reporterName: string;
  reportDate: string;
  inputQuantity?: number | null;
  warehouseOutQuantity?: number | null;
  feedInQuantity?: number | null;
  carryoverQuantity?: number | null;
  sourceWipNo?: string | null;
  outputQuantity?: number | null;
  reportKind?: 'INPUT' | 'SEGMENT' | 'OUTPUT' | string | null;
  workProcessTaskId?: number | null;
  processOrder?: number | null;
  inputUnit?: string | null;
  outputUnit?: string | null;
  totalWorkers?: number | null;
  totalWorkMinutes?: number | null;
  productionStartTime?: string | null;
  productionEndTime?: string | null;
  reportMode?: 'MODE_1' | 'MODE_2' | 'MODE_3' | string | null;
  processCategory: string;
  productName?: string | null;
  approvalStatus: string;
  isSupplemental: boolean;
  notes?: string | null;
  photos?: string[] | null;
  laborSegments?: Array<Record<string, unknown>> | null;
  byproducts?: Array<Record<string, unknown>> | null;
  wasteQuantity?: number | null;
  sampleRetainQuantity?: number | null;
  customFields?: Record<string, unknown> | null;
  createdAt: string;
}

// mirror backend dto/WorkProcessTaskDTO.java — T140 batch detail 工序明细
export interface WorkProcessTaskItem {
  id: number;
  factoryId: string;
  productionBatchId: number;
  productWorkProcessId: number;
  workProcessId: string;
  productTypeId: string;
  processOrder: number;
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'SKIPPED' | 'CANCELLED' | string;
  processName: string | null;
  processCategory: string | null;
  plannedQuantity: number | null;
  plannedUnit: string | null;
  plannedStartAt: string | null;
  plannedEndAt: string | null;
  estimatedMinutes: number | null;
  actualQuantity: number | null;
  actualStartAt: string | null;
  actualEndAt: string | null;
  actualMinutes: number | null;
  assignedTo: number | null;       // 责任人 user ID
  assignedToName?: string | null;  // T142: 责任人姓名 (后端 batch join, 无 N+1)
  completedBy: number | null;
  completedAt: string | null;
  standardYieldMin: number | null; // 标准出成率下限
  standardYieldMax: number | null; // 标准出成率上限
  outputUnit: string | null;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
}

// mirror backend dto/yield/WipRowDTO.java
export interface WipRowItem {
  intermediateBatchNo: string;             // 工序批次号
  sourceWorkProcessTaskId: number | null;  // 哪道任务产出
  processOrder: number | null;             // 第几道
  processName: string | null;              // 工序名 (join; null fallback)
  productTypeId: string | null;
  producedQuantity: number | null;         // 该道总产出
  consumedQuantity: number | null;         // 已被下道领走
  availableQuantity: number | null;        // 余额 = produced − consumed
  unit: string | null;
  status: 'AVAILABLE' | 'DEPLETED' | 'RETURNED' | string;
  // 段1: 双出成率 (单位不可比 → null; 首道两率相等)
  stepYieldRate: number | null;            // 对上工序出成率 (小数, 如 0.8889 = 88.89%)
  cumulativeYieldRate: number | null;      // 对原料累计出成率 (小数)
}

// mirror backend dto/yield/BatchYieldDTO.java (单元 F 聚合用到的子集)
export interface BatchYieldRow {
  batchId: number | null;
  batchNumber: string | null;
  firstStepInput: number | null;
  lastStepOutput: number | null;
  firstStepInputUnit: string | null;
  lastStepOutputUnit: string | null;
  cumulativeYieldRate: number | null;      // 累计出成率 (跨单位不可比 → null)
  complete: boolean | null;
  inProgress: boolean | null;
  totalLaborCost: number | null;
  totalMaterialCost: number | null;
  totalCost: number | null;
}

// mirror backend dto/yield/OrderYieldSummaryDTO.java
export interface OrderYieldSummary {
  orderId: string;
  batches: BatchYieldRow[];
  totalFirstInput: number | null;          // 单位不可比 → null
  totalLastOutput: number | null;
  overallYieldRate: number | null;         // 整体出成率 (单位不可比 → null)
  firstInputUnit: string | null;
  lastOutputUnit: string | null;
  totalLaborCost: number | null;
  totalMaterialCost: number | null;
  totalCost: number | null;
  batchCount: number;
}
