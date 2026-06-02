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
  outputQuantity: number;
  processCategory: string;
  approvalStatus: string;
  isSupplemental: boolean;
  createdAt: string;
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
}
