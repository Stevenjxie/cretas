import { apiClient } from './apiClient';
import { requireFactoryId } from '../../utils/factoryIdHelper';

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

  // --- Process Tasks ---

  async getActiveTasks(factoryId?: string): Promise<ApiEnvelope<ProcessTaskItem[] | { content: ProcessTaskItem[] }>> {
    const base = this.getBase(factoryId);
    return apiClient.get<ApiEnvelope<ProcessTaskItem[] | { content: ProcessTaskItem[] }>>(`${base}/process-tasks/active`);
  }

  async getTasks(params: { status?: string; productTypeId?: string; page?: number; size?: number }, factoryId?: string) {
    const base = this.getBase(factoryId);
    return apiClient.get(`${base}/process-tasks`, { params });
  }

  async getTaskById(taskId: string, factoryId?: string) {
    const base = this.getBase(factoryId);
    return apiClient.get(`${base}/process-tasks/${taskId}`);
  }

  async getTaskSummary(taskId: string, factoryId?: string) {
    const base = this.getBase(factoryId);
    return apiClient.get(`${base}/process-tasks/${taskId}/summary`);
  }

  async getRunOverview(productionRunId: string, factoryId?: string) {
    const base = this.getBase(factoryId);
    return apiClient.get(`${base}/process-tasks/run/${productionRunId}`);
  }

  async updateTaskStatus(taskId: string, status: string, factoryId?: string) {
    const base = this.getBase(factoryId);
    return apiClient.put(`${base}/process-tasks/${taskId}/status`, { status });
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

  async submitNormalReport(data: SubmitProcessReportPayload, factoryId?: string) {
    const base = this.getBase(factoryId);
    return apiClient.post(`${base}/process-work-reporting/normal`, data);
  }

  async submitSupplement(data: SubmitProcessReportPayload, factoryId?: string) {
    const base = this.getBase(factoryId);
    return apiClient.post(`${base}/process-work-reporting/supplement`, data);
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

  /** 根据产品关联的工序，一键生成工序任务 */
  async generateTasksFromProduct(
    data: {
      productTypeId: string;
      sourceCustomerName?: string;
      plannedQuantities?: Record<string, number>;
    },
    factoryId?: string,
  ) {
    const base = this.getBase(factoryId);
    return apiClient.post(`${base}/process-tasks/generate-from-product`, data);
  }
}

export const processTaskApiClient = new ProcessTaskApiClient();
