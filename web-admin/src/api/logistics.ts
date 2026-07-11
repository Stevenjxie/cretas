/**
 * 一加物流排线 MVP — 后端 API 客户端
 *
 * 覆盖 docs/dispatch/2026-07-11-logistics-mvp-closed-loop-handoff.md §11
 * 和 docs/superpowers/specs/2026-07-11-logistics-mvp-backend-closed-loop-spec.md §5。
 *
 * 契约:
 * - 统一前缀 `/api/mobile/{factoryId}/logistics`（baseURL 已含 `/api/mobile`，
 *   本文件内路径均以 `/${factoryId}/logistics/...` 开头，与 scheduling.ts 一致）。
 * - JSON 字段全 camelCase，尽量对齐前端既有 `web-admin/src/views/scheduling/logistics/types.ts`
 *   的字段命名（storeIds/segmentKeys/segmentDistances/totalDistanceKm/totalVolumeCbm/loadRate/status），
 *   减少后续前端接线改动面（field-naming-convention.md）。
 * - 所有可变实体带 `version`（乐观锁）；并发旧版本写 → 后端返回 409，
 *   业务层 ApiError 会带上 message/code，调用方需要处理（不静默 last-write-wins）。
 * - factoryId 一律由调用方从鉴权上下文（authStore.factoryId，参见 scheduling.ts 用法）传入，
 *   本模块不硬编码、不猜测 factoryId。
 * - 本文件只是「后端事实源」的类型化客户端，不含任何 mock/demo fallback —
 *   API 失败时错误由 request.ts 拦截器统一 toast + 抛 ApiError，调用方按需 catch。
 *
 * 注意：Java 后端由并行 agent 在 backend/java/.../logistics/ 下开发，本文件严格只涉及
 * web-admin/src/api/logistics.ts —— 不改 types.ts（避免与既有前端 fixture 类型churn），
 * 不接线到任何 .vue / useLogisticsDemoState.ts（下一阶段任务）。
 */
import { get, post, put } from './request';
import request from './request';
import type { ApiResponse, PageResponse } from '@/types/api';
import type { MapPoint } from '@/views/scheduling/logistics/types';

// ==================== 通用 ====================

/** 后端逐行/逐字段错误（导入预检、写操作校验失败等场景复用） */
export interface RowError {
  rowNumber: number;
  column: string;
  message: string;
}

export interface PageParams {
  page?: number;
  size?: number;
}

// ==================== 订单导入 ====================

export type OrderBatchStatus = 'PREVIEWED' | 'COMMITTED' | 'PLANNED' | 'CANCELLED';

/** preview 阶段单行解析结果（含校验状态，不代表已写库） */
export interface OrderImportPreviewRow {
  rowNumber: number;
  storeCode: string;
  storeName: string;
  address: string;
  areaCode?: string;
  pieces: number;
  boxes: number;
  weightKg: number;
  volumeCbm: number;
  windowStart?: string;
  windowEnd?: string;
  longitude?: number;
  latitude?: number;
  valid: boolean;
  errors: RowError[];
}

/** POST /order-import/preview 响应体 — 不写业务订单，仅解析+校验 */
export interface PreviewResult {
  jobId: string;
  businessDate?: string;
  sourceFilename: string;
  totalRows: number;
  validRows: number;
  errorRows: number;
  rowErrors: RowError[];
  rows: OrderImportPreviewRow[];
}

export interface OrderBatch {
  id: string;
  factoryId: string;
  businessDate: string;
  batchNumber: string;
  sourceFilename: string;
  status: OrderBatchStatus;
  totalRows: number;
  validRows: number;
  errorRows: number;
  createdBy?: number;
  createdAt: string;
  version: number;
}

export type LocationStatus = 'RESOLVED' | 'UNRESOLVED' | 'OUT_OF_BOUNDS';
export type DeliveryOrderStatus = 'IMPORTED' | 'PLANNED' | 'CONFIRMED' | 'CANCELLED';

/** 单个门店/门店订单（后端持久化形态；与前端 fixture StoreOrder 字段不同，见 types.ts 注释） */
export interface LogisticsDeliveryOrder {
  id: string;
  factoryId: string;
  batchId: string;
  storeCode: string;
  storeName: string;
  address: string;
  areaCode?: string;
  pieces: number;
  boxes: number;
  weightKg: number;
  volumeCbm: number;
  windowStart?: string;
  windowEnd?: string;
  longitude?: number;
  latitude?: number;
  locationStatus: LocationStatus;
  status: DeliveryOrderStatus;
  sourceRowNumber: number;
  version: number;
}

/** GET /order-import/template — 返回模板文件 Blob（xlsx），用法同 productionPlan.ts downloadImportTemplate */
export function getImportTemplate(factoryId: string) {
  return request.get<Blob>(`/${factoryId}/logistics/order-import/template`, {
    responseType: 'blob',
  });
}

/** POST /order-import/preview — multipart 上传，仅解析+校验，不写库 */
export function previewOrderImport(factoryId: string, file: File) {
  const formData = new FormData();
  formData.append('file', file);
  return post<PreviewResult>(`/${factoryId}/logistics/order-import/preview`, formData);
}

/** POST /order-import/{jobId}/commit — 用 preview 返回的 jobId 提交，写入订单批次和有效订单（幂等） */
export function commitOrderImport(factoryId: string, jobId: string) {
  return post<OrderBatch>(`/${factoryId}/logistics/order-import/${jobId}/commit`);
}

export function listOrderBatches(factoryId: string, params?: PageParams) {
  return get<PageResponse<OrderBatch>>(`/${factoryId}/logistics/order-batches`, { params });
}

export function getOrderBatch(factoryId: string, batchId: string) {
  return get<OrderBatch>(`/${factoryId}/logistics/order-batches/${batchId}`);
}

export function listOrders(factoryId: string, batchId: string, params?: PageParams) {
  return get<PageResponse<LogisticsDeliveryOrder>>(`/${factoryId}/logistics/orders`, {
    params: { batchId, ...params },
  });
}

/** PUT /orders/{orderId}/location — 补录/修正门店经纬度，回填 locationStatus */
export function updateOrderLocation(factoryId: string, orderId: string, longitude: number, latitude: number) {
  return put<LogisticsDeliveryOrder>(`/${factoryId}/logistics/orders/${orderId}/location`, {
    longitude,
    latitude,
  });
}

// ==================== 资源：车辆 / 司机 ====================

export type VehicleSource = '自有' | '外协';
export type VehicleSourceCode = 'OWNED' | 'OUTSOURCED';

/**
 * 物流车辆列表项 — 车辆基础字段(现有 Vehicle 实体, 见 spec §1) + 1:1 关联的
 * logistics_vehicle_profiles 物流专属字段合并后的读模型。
 */
export interface LogisticsVehicle {
  id: string;
  plateNumber: string;
  driverName?: string;
  driverPhone?: string;
  capacityCbm: number;
  maxWeightKg: number;
  source: VehicleSourceCode;
  bodyType?: string;
  temperatureMode: string;
  serviceAreas: string[];
  availableFrom?: string;
  availableTo?: string;
  active: boolean;
  drivers: LogisticsVehicleDriverBinding[];
  version: number;
}

/** PUT /vehicles/{vehicleId}/profile 请求体 — 只含物流 profile 字段，不改 Vehicle.capacity(kg) 载重语义 */
export interface VehicleProfileUpdate {
  capacityCbm?: number;
  maxWeightKg?: number;
  source?: VehicleSourceCode;
  bodyType?: string;
  serviceAreas?: string[];
  availableFrom?: string;
  availableTo?: string;
  active?: boolean;
  version?: number;
}

export type DriverEmploymentType = 'OWNED' | 'OUTSOURCED';

export interface LogisticsDriver {
  id: string;
  factoryId: string;
  name: string;
  phone?: string;
  employmentType: DriverEmploymentType;
  serviceAreas: string[];
  availableFrom?: string;
  availableTo?: string;
  active: boolean;
  version: number;
}

export interface DriverInput {
  name: string;
  phone?: string;
  employmentType: DriverEmploymentType;
  serviceAreas?: string[];
  availableFrom?: string;
  availableTo?: string;
  active?: boolean;
}

export type VehicleDriverRole = 'PRIMARY' | 'BACKUP';

/** 一车多司机绑定项（PRIMARY/BACKUP + 班次），用于 PUT /vehicles/{vehicleId}/drivers */
export interface LogisticsVehicleDriverBinding {
  driverId: string;
  driverName?: string;
  role: VehicleDriverRole;
  shiftStart?: string;
  shiftEnd?: string;
  priority?: number;
}

export function listVehicles(factoryId: string) {
  return get<LogisticsVehicle[]>(`/${factoryId}/logistics/vehicles`);
}

export function updateVehicleProfile(factoryId: string, vehicleId: string, profile: VehicleProfileUpdate) {
  return put<LogisticsVehicle>(`/${factoryId}/logistics/vehicles/${vehicleId}/profile`, profile);
}

export function listDrivers(factoryId: string) {
  return get<LogisticsDriver[]>(`/${factoryId}/logistics/drivers`);
}

export function createDriver(factoryId: string, driver: DriverInput) {
  return post<LogisticsDriver>(`/${factoryId}/logistics/drivers`, driver);
}

export function updateDriver(factoryId: string, driverId: string, driver: Partial<DriverInput> & { version?: number }) {
  return put<LogisticsDriver>(`/${factoryId}/logistics/drivers/${driverId}`, driver);
}

/** PUT /vehicles/{vehicleId}/drivers — 整体替换该车辆的司机绑定集合（支持一车 2-3 个司机） */
export function setVehicleDrivers(factoryId: string, vehicleId: string, bindings: LogisticsVehicleDriverBinding[]) {
  return put<LogisticsVehicle>(`/${factoryId}/logistics/vehicles/${vehicleId}/drivers`, { bindings });
}

// ==================== 排线计划 ====================

export type PlanStatus = 'DRAFT' | 'NEEDS_ACTION' | 'CONFIRMED' | 'EXPORTED' | 'CANCELLED';
export type TripStatus = 'draft' | 'needs_vehicle' | 'needs_driver' | 'needs_route_data' | 'confirmed';
export type DistanceSource = 'MAINTAINED_MATRIX' | 'MAP_PROVIDER';

/** 单个停靠点（车次内一个门店订单的排位） */
export interface LogisticsStop {
  id: string;
  tripId: string;
  deliveryOrderId: string;
  sequenceNo: number;
  legDistanceKm: number;
  arrivalWindowStart?: string;
  arrivalWindowEnd?: string;
  geometry?: MapPoint[];
  order?: LogisticsDeliveryOrder;
}

/**
 * 单个车次 — 字段名对齐前端既有 `RouteTrip`（types.ts）以减少后续接线改动面：
 * storeIds / segmentKeys / geometry / segmentDistances / totalDistanceKm / totalVolumeCbm / loadRate / status。
 * 新增字段（totalWeightKg / weightLoadRate / stops / version）为后端专属，前端待接线阶段按需使用。
 */
export interface LogisticsTrip {
  id: string;
  planId: string;
  tripNo: number;
  vehicleId: string | null;
  driverId: string | null;
  vehiclePlate?: string;
  driverName?: string;
  storeIds: string[];
  segmentKeys: string[];
  geometry: MapPoint[];
  segmentDistances: number[];
  totalDistanceKm: number;
  totalVolumeCbm: number;
  totalWeightKg: number;
  loadRate: number;
  weightLoadRate: number;
  status: TripStatus;
  stops: LogisticsStop[];
  version: number;
}

export interface LogisticsPlan {
  id: string;
  factoryId: string;
  orderBatchId: string;
  planDate: string;
  planNumber: string;
  targetLoadPct: number;
  status: PlanStatus;
  distanceSource: DistanceSource;
  totalStores: number;
  totalTrips: number;
  totalDistanceKm: number;
  createdBy?: number;
  confirmedBy?: number;
  confirmedAt?: string;
  version: number;
}

/**
 * 完整计划快照 — plan + trips + 未分配门店，前端地图/线路卡/人工调整/导出均应
 * 使用同一份 PlanSnapshot（避免多接口拼装出不一致的视图，见 handoff §12.3）。
 * 字段名对齐前端既有 `ScheduleResult`（trips / unassignedStoreIds / assignedVehicleCount / additionalVehicleCount）。
 */
export interface PlanSnapshot extends LogisticsPlan {
  trips: LogisticsTrip[];
  unassignedStoreIds: string[];
  assignedVehicleCount: number;
  additionalVehicleCount: number;
}

export interface GeneratePlanRequest {
  batchId: string;
  targetLoadPct: number;
}

/** POST /plans/{planId}/trips/{tripId}/stops/move 请求体 — 把门店移动到另一车次指定位置 */
export interface MoveStopRequest {
  deliveryOrderId: string;
  targetTripId: string | null;
  targetIndex: number;
  version?: number;
}

export function generatePlan(factoryId: string, batchId: string, targetLoadPct: number) {
  return post<PlanSnapshot>(`/${factoryId}/logistics/plans/generate`, {
    batchId,
    targetLoadPct,
  } as GeneratePlanRequest);
}

export function listPlans(factoryId: string, params?: PageParams & { status?: PlanStatus }) {
  return get<PageResponse<LogisticsPlan>>(`/${factoryId}/logistics/plans`, { params });
}

export function getPlan(factoryId: string, planId: string) {
  return get<PlanSnapshot>(`/${factoryId}/logistics/plans/${planId}`);
}

/** POST /plans/{planId}/regenerate — 重新生成整个草稿（丢弃人工调整） */
export function regeneratePlan(factoryId: string, planId: string) {
  return post<PlanSnapshot>(`/${factoryId}/logistics/plans/${planId}/regenerate`);
}

/** PUT /trips/{tripId}/stops/reorder — 同车次内整体重排停靠顺序（完整有序 storeIds） */
export function reorderStops(factoryId: string, planId: string, tripId: string, storeIds: string[], version?: number) {
  return put<PlanSnapshot>(`/${factoryId}/logistics/plans/${planId}/trips/${tripId}/stops/reorder`, {
    storeIds,
    version,
  });
}

/** POST /trips/{tripId}/stops/move — 跨车次移动门店到目标车次指定位置（targetTripId=null 时新建待匹配车次） */
export function moveStop(factoryId: string, planId: string, tripId: string, body: MoveStopRequest) {
  return post<PlanSnapshot>(`/${factoryId}/logistics/plans/${planId}/trips/${tripId}/stops/move`, body);
}

export function setTripVehicle(factoryId: string, planId: string, tripId: string, vehicleId: string | null, version?: number) {
  return put<PlanSnapshot>(`/${factoryId}/logistics/plans/${planId}/trips/${tripId}/vehicle`, {
    vehicleId,
    version,
  });
}

export function setTripDriver(factoryId: string, planId: string, tripId: string, driverId: string | null, version?: number) {
  return put<PlanSnapshot>(`/${factoryId}/logistics/plans/${planId}/trips/${tripId}/driver`, {
    driverId,
    version,
  });
}

export function confirmTrip(factoryId: string, planId: string, tripId: string, version?: number) {
  return post<PlanSnapshot>(`/${factoryId}/logistics/plans/${planId}/trips/${tripId}/confirm`, { version });
}

export function confirmPlan(factoryId: string, planId: string, version?: number) {
  return post<PlanSnapshot>(`/${factoryId}/logistics/plans/${planId}/confirm`, { version });
}

/** GET /plans/{planId}/export.csv — 返回 Blob，内容须与计划详情逐字段一致（handoff §16.1 验收项） */
export function exportPlanCsv(factoryId: string, planId: string) {
  return request.get<Blob>(`/${factoryId}/logistics/plans/${planId}/export.csv`, {
    responseType: 'blob',
  });
}

/** GET /plans/{planId}/export.xlsx — 返回 Blob（若后端已提供 Excel 导出工具） */
export function exportPlanXlsx(factoryId: string, planId: string) {
  return request.get<Blob>(`/${factoryId}/logistics/plans/${planId}/export.xlsx`, {
    responseType: 'blob',
  });
}

// Re-export for callers that only need the envelope type alongside this module.
export type { ApiResponse };
