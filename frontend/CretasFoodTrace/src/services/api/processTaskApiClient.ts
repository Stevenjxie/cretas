import { apiClient } from './apiClient';
import { requireFactoryId } from '../../utils/factoryIdHelper';
import type { PageResponse, WorkProcessTask } from './yieldReportApi';

// ========== Types ==========

export interface ProcessTaskItem {
  id: string;
  factoryId: string;
  batchNumber?: string;
  productTypeId: string;
  productTypeName?: string;
  workProcessId: string;
  processName?: string;
  processCategory?: string;
  unit: string;
  batchId?: number;
  productionBatchId?: number;
  workProcessTaskId?: number;
  processOrder?: number;
  inputUnit?: string;
  outputUnit?: string;
  plannedUnit?: string;
  productionRunId?: string;
  sourceDocType?: string;
  sourceDocId?: string;
  plannedQuantity: number;
  completedQuantity: number;
  pendingQuantity: number;
  inputQuantity?: number;
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'CLOSED' | 'SUPPLEMENTING' | string;
  assignedWorkerIds?: string;
  workflowVersionId?: string;
  previousTerminalStatus?: string;
  createdAt?: string;
  updatedAt?: string;
}

interface ApiEnvelope<T> {
  success: boolean;
  data?: T;
  message?: string;
}

interface ProcessCheckinResponseData {
  employeeName?: string;
}

interface ProcessCheckinRecord {
  id: number;
  processTaskId?: string | number;
  employeeId: number;
  employeeName?: string;
  workerName?: string;
  checkInTime?: string;
  checkOutTime?: string;
  status?: string;
  processName?: string;
}

interface TaskWorkerRecord {
  id?: number;
  employeeId?: number;
  name?: string;
  fullName?: string;
}

export interface ProcessTaskSummary {
  task?: ProcessTaskItem;
  // Backend returns flat shape — task fields at top level
  taskId?: string;
  id?: string;
  processName?: string;
  productName?: string;
  productTypeName?: string;
  processCategory?: string;
  plannedQuantity?: number;
  completedQuantity?: number;
  pendingQuantity?: number;
  inputQuantity?: number;
  unit?: string;
  status?: string;
  productionRunId?: string;
  totalReported?: number;
  approvedTotal?: number;
  pendingTotal?: number;
  rejectedTotal?: number;
  workerCount?: number;
  totalWorkers?: number;
  totalReports?: number;
}

export interface WorkerSummary {
  workerId: number;
  workerName: string;
  totalQuantity: number;
  approvedQuantity: number;
  pendingQuantity: number;
  reportCount: number;
}

export interface RunOverview {
  productionRunId: string;
  tasks: ProcessTaskItem[];
  overallProgress: number;
  completedTasks: number;
  totalTasks: number;
}

export interface ProcessReportItem {
  id: number;
  processTaskId: string;
  reportDate: string;
  processCategory?: string;
  productName?: string;
  batchId?: number;
  workProcessTaskId?: number;
  processOrder?: number;
  productTypeId?: string;
  inputQuantity?: number;
  inputUnit?: string;
  sourceWipNo?: string;
  outputQuantity: number;
  outputUnit?: string;
  totalWorkers?: number;
  totalWorkMinutes?: number;
  productionStartTime?: string;
  productionEndTime?: string;
  reportMode?: 'MODE_1' | 'MODE_2' | 'MODE_3';
  photos?: string[];
  notes?: string;
  customFields?: Record<string, unknown>;
  reporterName?: string;
  isSupplemental: boolean;
  approvalStatus: string;
  approvedBy?: number;
  approvedAt?: string;
  rejectedReason?: string;
  reversalOfId?: number;
  createdAt?: string;
}

export interface ApprovalItem {
  id: number;
  reporterName: string;
  reportDate: string;
  processCategory: string;
  productName?: string;
  batchId?: number;
  workProcessTaskId?: number;
  processOrder?: number;
  productTypeId?: string;
  inputQuantity?: number;
  inputUnit?: string;
  sourceWipNo?: string;
  outputQuantity: number;
  outputUnit?: string;
  totalWorkers?: number;
  totalWorkMinutes?: number;
  productionStartTime?: string;
  productionEndTime?: string;
  reportMode?: 'MODE_1' | 'MODE_2' | 'MODE_3';
  photos?: string[];
  notes?: string;
  customFields?: Record<string, unknown>;
  isSupplemental: boolean;
  processTaskId: string;
}

export interface SubmitProcessReportPayload {
  processTaskId: string;
  outputQuantity: number;
  reporterName?: string;
  targetWorkerId?: number;
  processCategory?: string;
  notes?: string;
  reportMode?: 'MODE_1' | 'MODE_2' | 'MODE_3';
  batchNumber?: string;
  batchId?: number;
  workProcessTaskId?: number;
  inputQuantity?: number;
  inputUnit?: string;
  sourceWipNo?: string;
  outputUnit?: string;
  totalWorkers?: number;
  totalWorkMinutes?: number;
  reportDate?: string;
  productionStartTime?: string;
  productionEndTime?: string;
  photos?: string[];
  workerIds?: number[];
  customFields?: Record<string, unknown>;
}

// ========== API Client ==========

class ProcessTaskApiClient {
  private getBase(factoryId?: string) {
    const fid = factoryId || requireFactoryId();
    return `/api/mobile/${fid}`;
  }

  private toProcessTaskItem(task: WorkProcessTask): ProcessTaskItem {
    return {
      id: String(task.id),
      factoryId: task.factoryId,
      batchNumber: task.batchNumber ?? undefined,
      productTypeId: task.productTypeId,
      productTypeName: task.productTypeName ?? undefined,
      workProcessId: task.workProcessId,
      processName: task.processName ?? undefined,
      processCategory: task.processCategory ?? undefined,
      unit: task.plannedUnit ?? task.outputUnit ?? '',
      batchId: task.productionBatchId,
      productionBatchId: task.productionBatchId,
      workProcessTaskId: task.id,
      processOrder: task.processOrder,
      outputUnit: task.outputUnit ?? undefined,
      plannedUnit: task.plannedUnit ?? undefined,
      productionRunId: `BATCH-${task.productionBatchId}`,
      plannedQuantity: task.plannedQuantity ?? 0,
      completedQuantity: task.actualQuantity ?? 0,
      pendingQuantity: 0,
      status: task.status,
      createdAt: task.createdAt ?? undefined,
      updatedAt: task.updatedAt ?? undefined,
    };
  }

  private parseTaskId(taskId: string): number {
    const normalized = taskId.startsWith('WPT-') ? taskId.slice(4) : taskId;
    const parsed = Number(normalized);
    if (!Number.isInteger(parsed) || parsed <= 0) {
      throw new Error(`无效的工序任务ID: ${taskId}`);
    }
    return parsed;
  }

  private parseBatchId(productionRunId: string): number {
    const normalized = productionRunId.startsWith('BATCH-')
      ? productionRunId.slice(6)
      : productionRunId;
    const parsed = Number(normalized);
    if (!Number.isInteger(parsed) || parsed <= 0) {
      throw new Error(`无效的生产批次ID: ${productionRunId}`);
    }
    return parsed;
  }

  // --- Canonical Work Process Tasks ---

  async getActiveTasks(factoryId?: string): Promise<ApiEnvelope<ProcessTaskItem[] | { content: ProcessTaskItem[] }>> {
    const base = this.getBase(factoryId);
    const response = await apiClient.get<ApiEnvelope<PageResponse<WorkProcessTask>>>(
      `${base}/work-process-tasks`,
      { params: { page: 1, size: 1000 } },
    );
    const tasks = (response.data?.content ?? [])
      .filter(task => task.status === 'PENDING' || task.status === 'IN_PROGRESS')
      .map(task => this.toProcessTaskItem(task));
    return { ...response, data: tasks };
  }

  async getTasks(
    params: { status?: string; productTypeId?: string; page?: number; size?: number },
    factoryId?: string,
  ): Promise<ApiEnvelope<{ content: ProcessTaskItem[]; totalElements?: number }>> {
    const base = this.getBase(factoryId);
    const response = await apiClient.get<ApiEnvelope<PageResponse<WorkProcessTask>>>(
      `${base}/work-process-tasks`,
      { params: { status: params.status, page: params.page ?? 1, size: params.size ?? 50 } },
    );
    const content = (response.data?.content ?? [])
      .filter(task => !params.productTypeId || task.productTypeId === params.productTypeId)
      .map(task => this.toProcessTaskItem(task));
    return {
      ...response,
      data: { content, totalElements: params.productTypeId ? content.length : response.data?.totalElements },
    };
  }

  async getTaskById(taskId: string, factoryId?: string): Promise<ApiEnvelope<ProcessTaskItem>> {
    const base = this.getBase(factoryId);
    const response = await apiClient.get<ApiEnvelope<WorkProcessTask>>(
      `${base}/work-process-tasks/${this.parseTaskId(taskId)}`,
    );
    return { ...response, data: response.data ? this.toProcessTaskItem(response.data) : undefined };
  }

  async getTaskSummary(taskId: string, factoryId?: string): Promise<ApiEnvelope<ProcessTaskSummary>> {
    const taskResponse = await this.getTaskById(taskId, factoryId);
    const task = taskResponse.data;
    if (!task) return { ...taskResponse, data: undefined };
    return {
      ...taskResponse,
      data: {
        task,
        taskId: task.id,
        processName: task.processName,
        productName: task.productTypeName,
        plannedQuantity: task.plannedQuantity,
        completedQuantity: task.completedQuantity,
        pendingQuantity: 0,
        unit: task.unit,
        status: task.status,
        productionRunId: task.productionRunId,
      },
    };
  }

  async getRunOverview(productionRunId: string, factoryId?: string): Promise<ApiEnvelope<RunOverview>> {
    const base = this.getBase(factoryId);
    const batchId = this.parseBatchId(productionRunId);
    const response = await apiClient.get<ApiEnvelope<WorkProcessTask[]>>(
      `${base}/production/batches/${batchId}/work-process-tasks`,
    );
    const tasks = (response.data ?? []).map(task => this.toProcessTaskItem(task));
    const completedTasks = tasks.filter(task => task.status === 'COMPLETED').length;
    const overallProgress = tasks.length === 0 ? 0 : Math.round((completedTasks / tasks.length) * 100);
    return {
      ...response,
      data: { productionRunId: `BATCH-${batchId}`, tasks, overallProgress, completedTasks, totalTasks: tasks.length },
    };
  }

  // --- Work Reporting (Process Mode) ---

  async getPendingApprovals(params: { page?: number; size?: number } = {}, factoryId?: string) {
    const base = this.getBase(factoryId);
    return apiClient.get(`${base}/process-work-reporting/pending-approval`, { params });
  }

  async approveReport(reportId: number, factoryId?: string) {
    const base = this.getBase(factoryId);
    return apiClient.put(`${base}/process-work-reporting/${reportId}/approve`);
  }

  async rejectReport(reportId: number, reason: string, factoryId?: string) {
    const base = this.getBase(factoryId);
    return apiClient.put(`${base}/process-work-reporting/${reportId}/reject`, { reason });
  }

  async batchApprove(reportIds: number[], factoryId?: string) {
    const base = this.getBase(factoryId);
    return apiClient.put(`${base}/process-work-reporting/batch-approve`, reportIds);
  }

  async getReportsByTask(taskId: string, factoryId?: string) {
    const base = this.getBase(factoryId);
    return apiClient.get(`${base}/process-work-reporting/by-task/${taskId}`);
  }

  async getWorkersByTask(taskId: string, factoryId?: string): Promise<ApiEnvelope<TaskWorkerRecord[]>> {
    const base = this.getBase(factoryId);
    return apiClient.get<ApiEnvelope<TaskWorkerRecord[]>>(`${base}/process-work-reporting/by-task/${taskId}/workers`);
  }

  // --- Process Checkin (工序模式签到) ---

  async processCheckin(data: { employeeId: number; processName?: string; processCategory?: string; checkinMethod?: string; processTaskId?: string }, factoryId?: string): Promise<ApiEnvelope<ProcessCheckinResponseData>> {
    const base = this.getBase(factoryId);
    return apiClient.post<ApiEnvelope<ProcessCheckinResponseData>>(`${base}/process-checkin`, data);
  }

  async processCheckout(checkinRecordId: number, factoryId?: string): Promise<ApiEnvelope<null>> {
    const base = this.getBase(factoryId);
    return apiClient.post<ApiEnvelope<null>>(`${base}/process-checkin/checkout/${checkinRecordId}`);
  }

  async getActiveCheckins(factoryId?: string): Promise<ApiEnvelope<ProcessCheckinRecord[]>> {
    const base = this.getBase(factoryId);
    return apiClient.get<ApiEnvelope<ProcessCheckinRecord[]>>(`${base}/process-checkin/active`);
  }

}

export const processTaskApiClient = new ProcessTaskApiClient();
