/**
 * API 响应类型定义
 */

/**
 * Default response payload type for API helpers (get/post/put/del).
 *
 * Tier 1 vue-tsc cleanup (2026-05-09): legacy call sites that don't supply a
 * `<ResponseType>` parameter to get/post/put/del default to `ApiData`. This is
 * intentionally `any` — using `unknown` would force ~800 call sites to add
 * type assertions in the same PR, which is impractical. Callers writing NEW
 * code should still supply explicit type parameters.
 *
 * Roadmap: as Tier 2/3 cleanup proceeds, individual modules can replace
 * `ApiData` defaults with concrete types (e.g. via per-module `apiClient.ts`
 * wrappers).
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type ApiData = any;

/**
 * Generic row type for table/list data whose schema is dynamic, comes from
 * an API call without a strongly-typed contract, or is a heterogeneous union
 * of related shapes (e.g., a "view detail" dialog that displays multiple
 * record types).
 *
 * Tier 2 vue-tsc cleanup (2026-05-10): replaces ~90 sites of
 * `ref<Record<string, unknown>>[]` and `ref<Record<string, unknown>>` that
 * triggered TS2339 on every `row.field` access. `Record<string, unknown>`
 * required casts at every read site; `Record<string, any>` matches the
 * pragmatic ApiData philosophy from Tier 1.
 *
 * Per project rules: prefer a typed interface (`User`, `Order`, etc.) when
 * the row shape is known and stable. Use `TableRow` only when the shape is
 * genuinely dynamic or when a quick cleanup is more valuable than designing
 * a new interface. New strongly-typed code should NOT default to `TableRow`.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type TableRow = Record<string, any>;

// 统一API响应格式
export interface ApiResponse<T = ApiData> {
  success: boolean;
  data: T;
  message: string;
  code?: string;
}

// 分页响应
export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;  // 当前页码 (0-based)
  size: number;
}

// 分页请求参数
export interface PageRequest {
  page?: number;
  size?: number;
  sort?: string;
}

// API 错误
export class ApiError extends Error {
  code?: string;
  status?: number;
  /**
   * R24 P2 audit follow-up: marker that distinguishes business 409s
   * (BusinessException(409).withHint(actionHint)) from vanilla optimistic-lock
   * 409 (GlobalExceptionHandler emits no actionHint). Callers checking
   * `status===409` should also check `!actionHint` before treating as
   * optimistic-lock conflict — otherwise R18/R21/R23 invariant violations
   * trigger the wrong "并发编辑冲突" dialog on top of the interceptor's
   * rich toast.
   */
  actionHint?: string | null;

  constructor(message: string, code?: string, status?: number, actionHint?: string | null) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
    this.actionHint = actionHint ?? null;
  }
}

// 常用业务类型
export interface Factory {
  id: string;
  factoryId: string;
  name: string;
  address?: string;
  phone?: string;
  email?: string;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ProductType {
  id: string;
  factoryId: string;
  name: string;
  code: string;
  category?: string;
  unit: string;
  unitPrice?: number;
  description?: string;
  isActive: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface MaterialType {
  id: string;
  factoryId: string;
  name: string;
  code: string;
  unit: string;
  category?: string;
  unitPrice?: number;
}

export interface MaterialBatch {
  id: string;
  batchNumber: string;
  factoryId: string;
  materialTypeId: string;
  materialTypeName?: string;
  quantity: number;
  remainingQuantity: number;
  unitPrice?: number;
  supplierId?: string;
  supplierName?: string;
  receivedDate: string;
  expiryDate?: string;
  storageLocation?: string;
  status: 'available' | 'reserved' | 'depleted';
  createdAt: string;
  updatedAt: string;
}

export interface ProductionBatch {
  id: number;
  batchNumber: string;
  factoryId: string;
  productTypeId: string;
  productTypeName?: string;
  plannedQuantity: number;
  actualQuantity?: number;
  status: 'pending' | 'in_progress' | 'completed' | 'cancelled';
  startTime?: string;
  endTime?: string;
  supervisorId?: number;
  supervisorName?: string;
  notes?: string;
  createdAt: string;
  updatedAt: string;
}

export interface QualityInspection {
  id: string;
  factoryId: string;
  batchId: number;
  batchNumber?: string;
  inspectorId: number;
  inspectorName?: string;
  inspectionType: 'incoming' | 'process' | 'final';
  result: 'pass' | 'fail' | 'conditional';
  score?: number;
  notes?: string;
  inspectedAt: string;
  createdAt: string;
}

export interface Equipment {
  id: string;
  factoryId: string;
  name: string;
  code: string;
  type?: string;
  location?: string;
  status: 'running' | 'idle' | 'maintenance' | 'fault';
  lastMaintenanceDate?: string;
  nextMaintenanceDate?: string;
}

export interface EquipmentAlert {
  id: string;
  factoryId: string;
  equipmentId: string;
  equipmentName?: string;
  alertType: 'warning' | 'error' | 'critical';
  title: string;
  description?: string;
  status: 'active' | 'acknowledged' | 'resolved';
  triggeredAt: string;
  resolvedAt?: string;
}

export interface Supplier {
  id: string;
  factoryId: string;
  name: string;
  code: string;
  contactPerson?: string;
  phone?: string;
  email?: string;
  address?: string;
  rating?: number;
  status: 'active' | 'inactive';
}

export interface Customer {
  id: string;
  factoryId: string;
  name: string;
  code: string;
  contactPerson?: string;
  phone?: string;
  email?: string;
  address?: string;
  status: 'active' | 'inactive';
}

export interface Shipment {
  id: string;
  factoryId: string;
  shipmentNumber: string;
  customerId?: string;
  customerName?: string;
  batchIds?: number[];
  totalQuantity: number;
  status: 'pending' | 'shipped' | 'delivered' | 'cancelled';
  shippedAt?: string;
  deliveredAt?: string;
  createdAt: string;
}

export interface TimeclockRecord {
  id: number;
  userId: number;
  factoryId: string;
  clockInTime?: string;
  clockOutTime?: string;
  workDuration?: number;
  status: 'clocked_in' | 'clocked_out' | 'absent';
  notes?: string;
  createdAt: string;
}

export interface Department {
  id: string;
  factoryId: string;
  name: string;
  code: string;
  description?: string;
  managerUserId?: number;
  managerName?: string;
  isActive: boolean;
}

// Dashboard 统计类型
export interface DashboardOverview {
  todayOutput: number;
  completedBatches: number;
  activeEquipment: number;
  pendingTasks: number;
  qualityRate: number;
  equipmentUtilization: number;
  // 后端 /reports/dashboard/overview 实际返回的额外字段 (见 ReportService.getDashboardOverview)。
  // R42 BUG-14 修过此处 fallback 链, R76 升级类型避免 `as any`.
  plannedOutput?: number;
  completionRate?: number;
  summary?: {
    activeAlerts?: number;
    [key: string]: unknown;
  };
  alerts?: {
    active?: number;
    [key: string]: unknown;
  };
}

export interface ProductionStats {
  period: string;
  totalOutput: number;
  completedBatches: number;
  inProgressBatches: number;
  /** Batches pending start (not yet in progress). Optional — may be absent from older API responses. */
  pendingBatches?: number;
  avgEfficiency: number;
  trend: Array<{ date: string; output: number }>;
}

export interface QualityStats {
  passRate: number;
  failRate: number;
  conditionalRate: number;
  totalInspections: number;
  /** Inspections recorded today (subset of totalInspections). */
  todayInspections?: number;
  /** Number of batches with failed quality outcomes. */
  failedBatches?: number;
  byType: {
    incoming: { pass: number; fail: number };
    process: { pass: number; fail: number };
    final: { pass: number; fail: number };
  };
}

export interface EquipmentStats {
  totalEquipment: number;
  running: number;
  idle: number;
  maintenance: number;
  fault: number;
  activeAlerts: number;
  avgUtilization: number;
}
