/**
 * 生产报工类型定义
 */

export type ReportType = 'PROGRESS' | 'HOURS';
export type ReportStatus = 'SUBMITTED' | 'APPROVED' | 'REJECTED';
export type CheckinMethod = 'NFC' | 'QR' | 'MANUAL';

export interface WorkReportSubmitRequest {
  reportType: ReportType;
  batchId?: number;
  reportDate: string;  // ISO date: YYYY-MM-DD
  schemaId?: string;
  reporterName?: string;
  processCategory?: string;
  productName?: string;
  outputQuantity?: number;
  goodQuantity?: number;
  defectQuantity?: number;
  totalWorkMinutes?: number;
  totalWorkers?: number;
  operationVolume?: number;
  hourEntries?: HourEntry[];
  nonProductionEntries?: Record<string, unknown>[];
  productionStartTime?: string;  // HH:mm
  productionEndTime?: string;    // HH:mm
  customFields?: Record<string, unknown>;
  photos?: string[];
}

export interface HourEntry {
  fullTimeWorkers?: number;
  fullTimeHours?: number;
  hourlyWorkers?: number;
  hourlyHours?: number;
  dailyWorkers?: number;
  dailyHours?: number;
}

export interface WorkReportResponse {
  id: number;
  factoryId: string;
  batchId?: number;
  batchNumber?: string;
  workerId: number;
  reportType: ReportType;
  schemaId?: string;
  reportDate: string;
  reporterName?: string;
  processCategory?: string;
  productName?: string;
  outputQuantity?: number;
  goodQuantity?: number;
  defectQuantity?: number;
  totalWorkMinutes?: number;
  totalWorkers?: number;
  operationVolume?: number;
  hourEntries?: HourEntry[];
  nonProductionEntries?: Record<string, unknown>[];
  productionStartTime?: string;
  productionEndTime?: string;
  customFields?: Record<string, unknown>;
  photos?: string[];
  status: ReportStatus;
  rejectionReason?: string;
  syncedToSmartbi: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CheckinRequest {
  batchId?: number;
  processTaskId?: string;
  employeeId: number;
  checkinMethod: CheckinMethod;
  assignedBy?: number;
}

export interface CheckoutRequest {
  batchId?: number;
  processTaskId?: string;
  employeeId: number;
}

export interface BatchWorkSessionResponse {
  id: number;
  batchId: number;
  employeeId: number;
  employeeName?: string;
  workMinutes?: number;
  checkInTime?: string;
  checkOutTime?: string;
  status: string;
  processName?: string;
  checkinMethod?: string;
  notes?: string;
}

export interface CheckinWorkerDTO {
  sessionId: number;
  batchId: number;
  employeeId: number;
  fullName: string | null;
  position: string | null;
  hireType: string | null;
  hireTypeLabel: string | null;
  checkinMethod: string | null;
  checkInTime: string | null;
  checkOutTime: string | null;
  status: string;
}

export interface FormSchemaField {
  key: string;
  label: string;
  type: 'text' | 'integer' | 'decimal' | 'date' | 'time' | 'select' | 'photo_array' | 'table';
  required: boolean;
  options_source?: string;
  max?: number;
  columns?: FormSchemaField[];
}

export interface FormSchema {
  fields: FormSchemaField[];
}

export interface FormTemplateResponse {
  id: string;
  name: string;
  entityType: string;
  schemaJson: string;
  isActive: boolean;
  version: number;
}
