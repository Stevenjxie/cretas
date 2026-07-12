/**
 * 一加物流排线 —— 后端 API 事实源 composable。
 *
 * 替代 `useLogisticsDemoState.ts`（前端 fixture）成为四个物流页面（workbench/orders/resources/records）
 * 的共享状态源。设计上尽量沿用旧 composable 的字段命名（stores/vehicles/scheduleResult/activeTrip/
 * exportRows/activeStep/selectedTripId/selectedStoreId 等）与模块级单例模式，
 * 让既有的展示型组件（LogisticsMap/RouteCards/StoreDetailDrawer/ManualConfirmStep）
 * 尽量不改或只做最小改动即可切换数据源（见 handoff §12.2）。
 *
 * 状态规则（handoff §12.3，硬约束）：
 * - 未导入 → 空态，不自动注入 13 家 mock 门店（`orders`/`stores` 初始即为空数组）。
 * - API 失败 → 保留当前已知状态 + 暴露 `*Error`，不静默回退 mock、不吞错误。
 * - 地图/路线卡/人工调整/确认/导出全部读同一份后端 `PlanSnapshot`（`plan` ref 是唯一权威来源，
 *   所有展示用视图都是从它 derive 出来的 computed，不在多处各自维护副本）。
 *
 * 地图坐标（handoff §12.4）：门店经纬度通过 `mapProjection.ts` 的集中投影适配器换算成
 * 现有 1917×1165 像素坐标系；未定位（UNRESOLVED）门店不在地图上伪造点位，只出现在
 * `unresolvedOrders` 列表中；超出标定边界（OUT_OF_BOUNDS）的门店仍然渲染（clamp 到边缘），
 * 由调用方（LogisticsMap 使用方）自行判断是否需要额外提示。
 */
import { computed, ref } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { ApiError } from '@/types/api';
import type { PageResponse } from '@/types/api';
import {
  commitOrderImport,
  createOrdersManual,
  confirmPlan as apiConfirmPlan,
  confirmTrip as apiConfirmTrip,
  createDriver as apiCreateDriver,
  exportPlanCsv as apiExportPlanCsv,
  exportPlanXlsx as apiExportPlanXlsx,
  generatePlan as apiGeneratePlan,
  getImportTemplate,
  getOrderBatch,
  getPlan as apiGetPlan,
  listDrivers as apiListDrivers,
  listOrderBatches,
  listOrders,
  listPlans as apiListPlans,
  listVehicles as apiListVehicles,
  moveStop as apiMoveStop,
  previewOrderImport,
  regeneratePlan as apiRegeneratePlan,
  reorderStops as apiReorderStops,
  setTripDriver as apiSetTripDriver,
  setTripVehicle as apiSetTripVehicle,
  setVehicleDrivers as apiSetVehicleDrivers,
  updateDriver as apiUpdateDriver,
  updateOrderLocation,
  updateVehicleProfile as apiUpdateVehicleProfile,
} from '@/api/logistics';
import type {
  DriverInput,
  LogisticsDeliveryOrder,
  LogisticsDriver,
  LogisticsPlan,
  LogisticsVehicle,
  LogisticsVehicleDriverBinding,
  ManualOrderRow,
  OrderBatch,
  PlanSnapshot,
  PreviewResult,
  RouteOptimizeMode,
  VehicleProfileUpdate,
} from '@/api/logistics';
import { projectLonLat } from './mapProjection';
import { buildExportRows } from './routeEngine';
import type { ExportRow, RouteTrip, ScheduleResult, StoreOrder, Vehicle } from './types';

export type LogisticsStep = 'import' | 'map' | 'confirm' | 'export';

// ==================== 内部工具 ====================

function requireFactoryId(): string {
  const factoryId = useAuthStore().factoryId;
  if (!factoryId) {
    throw new ApiError('未获取到工厂身份，请重新登录', 'NO_FACTORY_ID');
  }
  return factoryId;
}

function errorMessage(err: unknown): string {
  if (err instanceof ApiError) return err.message;
  if (err instanceof Error) return err.message;
  return '请求失败，请稍后重试';
}

function isOptimisticLockConflict(err: unknown): boolean {
  return err instanceof ApiError && err.status === 409;
}

function triggerDownload(blob: Blob, filename: string): void {
  const anchor = document.createElement('a');
  anchor.href = URL.createObjectURL(blob);
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(anchor.href);
}

function formatWindow(start?: string, end?: string): string {
  if (!start && !end) return '未设置';
  return `${start ?? '不限'} - ${end ?? '不限'}`;
}

/** LogisticsDeliveryOrder（后端持久化形态） → StoreOrder（既有地图/卡片组件期望的视图模型） */
function toStoreOrderView(order: LogisticsDeliveryOrder): StoreOrder {
  const { point } = projectLonLat(order.longitude as number, order.latitude as number);
  return {
    id: order.id,
    name: order.storeName,
    address: order.address,
    area: order.areaCode ?? '',
    boxes: order.boxes,
    pieces: order.pieces,
    weightKg: order.weightKg,
    volumeCbm: order.volumeCbm,
    window: formatWindow(order.windowStart, order.windowEnd),
    mapAnchor: point,
    lng: order.longitude as number,
    lat: order.latitude as number,
  };
}

/** LogisticsVehicle（后端 profile + 绑定合并读模型） → Vehicle（既有人工确认步骤期望的视图模型） */
function toVehicleView(vehicle: LogisticsVehicle): Vehicle {
  const primary = vehicle.drivers.find((binding) => binding.role === 'PRIMARY') ?? null;
  const backups = vehicle.drivers
    .filter((binding) => binding.role === 'BACKUP')
    .map((binding) => binding.driverName || binding.driverId);
  return {
    id: vehicle.id,
    plate: vehicle.plateNumber,
    capacityCbm: vehicle.capacityCbm,
    maxWeightKg: vehicle.maxWeightKg,
    areaCodes: vehicle.serviceAreas,
    source: vehicle.source === 'OWNED' ? '自有' : '外协',
    driverId: primary?.driverId ?? null,
    driverName: primary?.driverName || '',
    backupDrivers: backups,
    vehicleBody: vehicle.bodyType || vehicle.temperatureMode,
    shift: primary?.shiftStart && primary?.shiftEnd
      ? `${primary.shiftStart}-${primary.shiftEnd}`
      : (vehicle.availableFrom && vehicle.availableTo ? `${vehicle.availableFrom}-${vehicle.availableTo}` : '未设置'),
  };
}

/** LogisticsTrip（后端车次） → RouteTrip（既有地图/路线卡/人工确认组件期望的视图模型） */
function toRouteTripView(trip: PlanSnapshot['trips'][number]): RouteTrip {
  return {
    id: trip.id,
    routeGroupId: trip.planId,
    tripNo: trip.tripNo,
    vehicleId: trip.vehicleId,
    driverId: trip.driverId,
    vehiclePlate: trip.vehiclePlate,
    driverName: trip.driverName,
    storeIds: trip.storeIds,
    segmentKeys: trip.segmentKeys,
    geometry: trip.geometry,
    roadPath: trip.roadPath,
    durationMin: trip.totalDurationMin,
    plannedDepartMin: trip.plannedDepartMin,
    returnToDepotMin: trip.returnToDepotMin,
    lateReturn: trip.lateReturn,
    vehicleTripSeq: trip.vehicleTripSeq,
    segmentDistances: trip.segmentDistances,
    totalDistanceKm: trip.totalDistanceKm,
    totalVolumeCbm: trip.totalVolumeCbm,
    loadRate: trip.loadRate,
    status: trip.status,
  };
}

// ==================== 模块级单例状态 ====================

// 导入
const batch = ref<OrderBatch | null>(null);
const batches = ref<OrderBatch[]>([]);
const preview = ref<PreviewResult | null>(null);
const uploading = ref(false);
const committing = ref(false);
const importError = ref<string | null>(null);

// 订单（当前批次全量——供地图/工作台使用；size 取足够大一次性拉完，约 200 家门店的量级）
const orders = ref<LogisticsDeliveryOrder[]>([]);
const ordersLoading = ref(false);
const ordersError = ref<string | null>(null);

// 订单（门店与订单页 —— 真实后端分页）
const ordersPage = ref<PageResponse<LogisticsDeliveryOrder> | null>(null);

// 资源
const vehicles = ref<LogisticsVehicle[]>([]);
const drivers = ref<LogisticsDriver[]>([]);
const resourcesLoading = ref(false);
const resourcesError = ref<string | null>(null);

// 排线计划（唯一权威快照）
const plan = ref<PlanSnapshot | null>(null);
const planLoading = ref(false);
const planError = ref<string | null>(null);
const targetLoadPct = ref(88);
// 排线优化目标：路程最短(默认) / 时间最快
const optimizeMode = ref<RouteOptimizeMode>('DISTANCE');
// 「AI 分析排班中」进度动画状态（上传订单后生成路线时）
const analyzing = ref(false);
const analyzeProgress = ref(0);

// 调度记录
const planHistory = ref<PageResponse<LogisticsPlan> | null>(null);
const recordsLoading = ref(false);
const recordsError = ref<string | null>(null);

// UI 选中态
const activeStep = ref<LogisticsStep>('import');
const selectedTripId = ref<string | null>(null);
const selectedStoreId = ref<string | null>(null);

// ==================== 派生视图 ====================

/** 地图/路线卡/门店详情使用的门店集合——只含已定位（RESOLVED/OUT_OF_BOUNDS）门店，UNRESOLVED 不在此列 */
const stores = computed<StoreOrder[]>(() => orders.value
  .filter((order) => order.locationStatus !== 'UNRESOLVED' && order.longitude != null && order.latitude != null)
  .map(toStoreOrderView));

/** 未定位门店——不得在地图上伪造点位，展示在独立的"待定位"列表中 */
const unresolvedOrders = computed(() => orders.value.filter((order) => order.locationStatus === 'UNRESOLVED'));

/** 超出地图标定边界但仍有坐标的门店 id 集合，供地图层附加提示使用 */
const outOfBoundsOrderIds = computed(() => new Set(
  orders.value.filter((order) => order.locationStatus === 'OUT_OF_BOUNDS').map((order) => order.id),
));

const vehiclesView = computed<Vehicle[]>(() => vehicles.value.map(toVehicleView));

const scheduleResult = computed<ScheduleResult>(() => ({
  trips: plan.value?.trips.map(toRouteTripView) ?? [],
  unassignedStoreIds: plan.value?.unassignedStoreIds ?? [],
  assignedVehicleCount: plan.value?.assignedVehicleCount ?? 0,
  additionalVehicleCount: plan.value?.additionalVehicleCount ?? 0,
}));

const activeTrip = computed<RouteTrip | null>(() => scheduleResult.value.trips.find((trip) => trip.id === selectedTripId.value) ?? null);
const exportRows = computed<ExportRow[]>(() => {
  const nameById = new Map(stores.value.map((s) => [s.id, s.name]));
  return buildExportRows(scheduleResult.value, (id) => nameById.get(id) ?? id);
});

// ==================== 动作：订单导入 ====================

async function downloadTemplate(): Promise<void> {
  try {
    const factoryId = requireFactoryId();
    const response = await getImportTemplate(factoryId);
    triggerDownload(
      new Blob([response.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }),
      '物流订单导入模板.xlsx',
    );
  } catch (err) {
    importError.value = errorMessage(err);
  }
}

async function uploadPreview(file: File): Promise<void> {
  importError.value = null;
  uploading.value = true;
  try {
    const factoryId = requireFactoryId();
    const res = await previewOrderImport(factoryId, file);
    preview.value = res.data;
  } catch (err) {
    importError.value = errorMessage(err);
    preview.value = null;
  } finally {
    uploading.value = false;
  }
}

async function commitImport(): Promise<boolean> {
  if (!preview.value) return false;
  importError.value = null;
  committing.value = true;
  try {
    const factoryId = requireFactoryId();
    const res = await commitOrderImport(factoryId, preview.value.jobId);
    batch.value = res.data;
    preview.value = null;
    await loadOrders(res.data.id);
    // 停留在导入步——用户显式点"下一步"再生成路线（与原 UX 流程一致，
    // 也让目标装载率在生成前有机会先调整默认值）。
    return true;
  } catch (err) {
    importError.value = errorMessage(err);
    return false;
  } finally {
    committing.value = false;
  }
}

/**
 * 手动录入订单（非文件）：结构化行 → 后端复用文件导入校验（返回 preview + jobId）→ 直接 commit 写库。
 * 返回 preview（含逐行错误）供 UI 展示；若有有效行则 batch 已创建。
 */
async function submitManualOrders(businessDate: string | null, rows: ManualOrderRow[]): Promise<PreviewResult | null> {
  importError.value = null;
  committing.value = true;
  try {
    const factoryId = requireFactoryId();
    const res = await createOrdersManual(factoryId, businessDate, rows);
    preview.value = res.data;
    // 有有效行才落库（与文件导入一致：无有效行只回报错误，不建空批次上下文）。
    if (res.data.validRows > 0) {
      const committed = await commitOrderImport(factoryId, res.data.jobId);
      batch.value = committed.data;
      preview.value = null;
      await loadOrders(committed.data.id);
    }
    return res.data;
  } catch (err) {
    importError.value = errorMessage(err);
    return null;
  } finally {
    committing.value = false;
  }
}

async function loadBatches(): Promise<void> {
  try {
    const factoryId = requireFactoryId();
    const res = await listOrderBatches(factoryId, { page: 0, size: 30 });
    batches.value = res.data.content;
  } catch (err) {
    ordersError.value = errorMessage(err);
  }
}

/** 供地图/工作台使用的"全量"读取——size 取足够大一次性拉完当天批次（客户场景约 200 家门店） */
async function loadOrders(batchId: string): Promise<void> {
  ordersLoading.value = true;
  ordersError.value = null;
  try {
    const factoryId = requireFactoryId();
    const res = await listOrders(factoryId, batchId, { page: 0, size: 1000 });
    orders.value = res.data.content;
  } catch (err) {
    ordersError.value = errorMessage(err);
  } finally {
    ordersLoading.value = false;
  }
}

/** 供「门店与订单」页做真实后端分页展示 */
async function loadOrdersPage(batchId: string, page = 0, size = 20): Promise<void> {
  ordersLoading.value = true;
  ordersError.value = null;
  try {
    const factoryId = requireFactoryId();
    const res = await listOrders(factoryId, batchId, { page, size });
    ordersPage.value = res.data;
  } catch (err) {
    ordersError.value = errorMessage(err);
  } finally {
    ordersLoading.value = false;
  }
}

async function updateLocation(orderId: string, longitude: number, latitude: number): Promise<boolean> {
  try {
    const factoryId = requireFactoryId();
    const res = await updateOrderLocation(factoryId, orderId, longitude, latitude);
    const updated = res.data;
    orders.value = orders.value.map((order) => (order.id === updated.id ? updated : order));
    if (ordersPage.value) {
      ordersPage.value = {
        ...ordersPage.value,
        content: ordersPage.value.content.map((order) => (order.id === updated.id ? updated : order)),
      };
    }
    return true;
  } catch (err) {
    ordersError.value = errorMessage(err);
    return false;
  }
}

// ==================== 动作：资源（车辆/司机） ====================

async function loadVehicles(): Promise<void> {
  resourcesLoading.value = true;
  resourcesError.value = null;
  try {
    const factoryId = requireFactoryId();
    const res = await apiListVehicles(factoryId);
    vehicles.value = res.data;
  } catch (err) {
    resourcesError.value = errorMessage(err);
  } finally {
    resourcesLoading.value = false;
  }
}

async function loadDrivers(): Promise<void> {
  try {
    const factoryId = requireFactoryId();
    const res = await apiListDrivers(factoryId);
    drivers.value = res.data;
  } catch (err) {
    resourcesError.value = errorMessage(err);
  }
}

async function loadResources(): Promise<void> {
  await Promise.all([loadVehicles(), loadDrivers()]);
}

async function saveVehicleProfile(vehicleId: string, profile: VehicleProfileUpdate): Promise<boolean> {
  try {
    const factoryId = requireFactoryId();
    const res = await apiUpdateVehicleProfile(factoryId, vehicleId, profile);
    vehicles.value = vehicles.value.map((vehicle) => (vehicle.id === res.data.id ? res.data : vehicle));
    return true;
  } catch (err) {
    resourcesError.value = errorMessage(err);
    return false;
  }
}

async function addDriver(input: DriverInput): Promise<boolean> {
  try {
    const factoryId = requireFactoryId();
    const res = await apiCreateDriver(factoryId, input);
    drivers.value = [...drivers.value, res.data];
    return true;
  } catch (err) {
    resourcesError.value = errorMessage(err);
    return false;
  }
}

async function saveDriver(driverId: string, input: Partial<DriverInput> & { version?: number }): Promise<boolean> {
  try {
    const factoryId = requireFactoryId();
    const res = await apiUpdateDriver(factoryId, driverId, input);
    drivers.value = drivers.value.map((driver) => (driver.id === res.data.id ? res.data : driver));
    return true;
  } catch (err) {
    resourcesError.value = errorMessage(err);
    return false;
  }
}

async function saveVehicleDrivers(vehicleId: string, bindings: LogisticsVehicleDriverBinding[]): Promise<boolean> {
  try {
    const factoryId = requireFactoryId();
    const res = await apiSetVehicleDrivers(factoryId, vehicleId, bindings);
    vehicles.value = vehicles.value.map((vehicle) => (vehicle.id === res.data.id ? res.data : vehicle));
    return true;
  } catch (err) {
    resourcesError.value = errorMessage(err);
    return false;
  }
}

// ==================== 动作：排线计划 ====================

function applyPlanSnapshot(snapshot: PlanSnapshot): void {
  plan.value = snapshot;
  selectedTripId.value = snapshot.trips.some((trip) => trip.id === selectedTripId.value)
    ? selectedTripId.value
    : snapshot.trips[0]?.id ?? null;
  if (selectedStoreId.value) {
    const stillVisible = snapshot.trips.some((trip) => trip.storeIds.includes(selectedStoreId.value as string))
      || snapshot.unassignedStoreIds.includes(selectedStoreId.value);
    if (!stillVisible) selectedStoreId.value = null;
  }
}

async function reloadPlan(): Promise<void> {
  if (!plan.value) return;
  try {
    const factoryId = requireFactoryId();
    const res = await apiGetPlan(factoryId, plan.value.id);
    applyPlanSnapshot(res.data);
  } catch (err) {
    planError.value = errorMessage(err);
  }
}

async function generateRoutes(): Promise<void> {
  if (!batch.value) return;
  planLoading.value = true;
  planError.value = null;
  // 「AI 分析排班中」进度动画 —— 上传订单后生成路线时给用户明确的智能分析反馈 (至少 2.5s)。
  analyzing.value = true;
  analyzeProgress.value = 0;
  // 借这段 loading 在后台预热高德地图 SDK + 驾车插件，等切到地图步时已就绪、秒出（不阻塞生成）。
  void import('./amapLoader').then((m) => m.loadAmap().then(() => m.ensureDriving())).catch(() => {});
  const startedAt = Date.now();
  const MIN_MS = 2600;
  const timer = window.setInterval(() => {
    if (analyzeProgress.value < 92) analyzeProgress.value = Math.min(92, analyzeProgress.value + 4);
  }, 90);
  try {
    const factoryId = requireFactoryId();
    const res = await apiGeneratePlan(factoryId, batch.value.id, targetLoadPct.value, optimizeMode.value);
    const elapsed = Date.now() - startedAt;
    if (elapsed < MIN_MS) await new Promise((r) => setTimeout(r, MIN_MS - elapsed));
    analyzeProgress.value = 100;
    await new Promise((r) => setTimeout(r, 280)); // 让 100% 停留片刻
    applyPlanSnapshot(res.data);
    activeStep.value = 'map';
  } catch (err) {
    planError.value = errorMessage(err);
  } finally {
    window.clearInterval(timer);
    analyzing.value = false;
    planLoading.value = false;
  }
}

/** 切换排线优化目标(时间最快/路程最短)；若已有计划则立即按新目标重新生成。 */
async function setOptimizeMode(mode: RouteOptimizeMode): Promise<void> {
  if (mode !== 'TIME' && mode !== 'DISTANCE') return;
  if (optimizeMode.value === mode) return;
  optimizeMode.value = mode;
  // 已有计划 → 真重建 (regenerate)，不能走 generate (幂等短路不会应用新模式)。
  if (plan.value) await regeneratePlanAction();
  else if (batch.value) await generateRoutes();
}

async function setTargetLoad(value: number): Promise<void> {
  if (!Number.isFinite(value) || value <= 0 || value > 100) {
    throw new RangeError('targetLoadPct must be greater than 0 and at most 100');
  }
  targetLoadPct.value = value;
  if (plan.value) await regeneratePlanAction();
  else if (batch.value) await generateRoutes();
}

function selectTrip(tripId: string | null): void {
  selectedTripId.value = scheduleResult.value.trips.some((trip) => trip.id === tripId) ? tripId : null;
  const trip = activeTrip.value;
  if (trip && selectedStoreId.value && !trip.storeIds.includes(selectedStoreId.value)) {
    selectedStoreId.value = null;
  }
}

function selectStore(storeId: string | null): void {
  selectedStoreId.value = storeId && orders.value.some((order) => order.id === storeId) ? storeId : null;
  const trip = scheduleResult.value.trips.find((candidate) => candidate.storeIds.includes(selectedStoreId.value ?? ''));
  if (trip) selectedTripId.value = trip.id;
}

/** 同车次内上/下移停靠顺序——原子写回后端，返回后用最新 plan snapshot 整体替换本地状态 */
async function moveStore(storeId: string, direction: -1 | 1): Promise<boolean> {
  const trip = activeTrip.value;
  if (!trip || !plan.value) return false;
  const index = trip.storeIds.indexOf(storeId);
  const nextIndex = index + direction;
  if (index < 0 || nextIndex < 0 || nextIndex >= trip.storeIds.length) return false;

  const storeIds = [...trip.storeIds];
  [storeIds[index], storeIds[nextIndex]] = [storeIds[nextIndex], storeIds[index]];

  try {
    const factoryId = requireFactoryId();
    const res = await apiReorderStops(factoryId, plan.value.id, trip.id, storeIds, plan.value.version);
    applyPlanSnapshot(res.data);
    planError.value = null;
    return true;
  } catch (err) {
    if (await handlePlanConflict(err)) return false;
    planError.value = errorMessage(err);
    return false;
  }
}

/** 把门店移动到另一车次末尾（targetTripId=null 时后端新建待匹配车次） */
async function moveStoreToTrip(storeId: string, targetTripId: string | null): Promise<boolean> {
  if (!plan.value) return false;
  const sourceTrip = scheduleResult.value.trips.find((trip) => trip.storeIds.includes(storeId));
  if (!sourceTrip) return false;
  const targetTrip = targetTripId ? scheduleResult.value.trips.find((trip) => trip.id === targetTripId) : null;
  const targetIndex = targetTrip ? targetTrip.storeIds.length : 0;

  try {
    const factoryId = requireFactoryId();
    const res = await apiMoveStop(factoryId, plan.value.id, sourceTrip.id, {
      deliveryOrderId: storeId,
      targetTripId,
      targetIndex,
      version: plan.value.version,
    });
    applyPlanSnapshot(res.data);
    planError.value = null;
    return true;
  } catch (err) {
    if (await handlePlanConflict(err)) return false;
    planError.value = errorMessage(err);
    return false;
  }
}

async function assignVehicle(vehicleId: string | null): Promise<boolean> {
  const trip = activeTrip.value;
  if (!trip || !plan.value) return false;
  try {
    const factoryId = requireFactoryId();
    const res = await apiSetTripVehicle(factoryId, plan.value.id, trip.id, vehicleId, plan.value.version);
    applyPlanSnapshot(res.data);
    planError.value = null;
    return true;
  } catch (err) {
    if (await handlePlanConflict(err)) return false;
    planError.value = errorMessage(err);
    return false;
  }
}

async function assignDriver(driverId: string | null): Promise<boolean> {
  const trip = activeTrip.value;
  if (!trip || !plan.value) return false;
  try {
    const factoryId = requireFactoryId();
    const res = await apiSetTripDriver(factoryId, plan.value.id, trip.id, driverId, plan.value.version);
    applyPlanSnapshot(res.data);
    planError.value = null;
    return true;
  } catch (err) {
    if (await handlePlanConflict(err)) return false;
    planError.value = errorMessage(err);
    return false;
  }
}

async function confirmTrip(): Promise<boolean> {
  const trip = activeTrip.value;
  if (!trip || !plan.value) return false;
  try {
    const factoryId = requireFactoryId();
    const res = await apiConfirmTrip(factoryId, plan.value.id, trip.id, plan.value.version);
    applyPlanSnapshot(res.data);
    activeStep.value = 'confirm';
    planError.value = null;
    return true;
  } catch (err) {
    if (await handlePlanConflict(err)) return false;
    planError.value = errorMessage(err);
    return false;
  }
}

async function confirmSchedule(): Promise<boolean> {
  if (!plan.value) return false;
  try {
    const factoryId = requireFactoryId();
    const res = await apiConfirmPlan(factoryId, plan.value.id, plan.value.version);
    applyPlanSnapshot(res.data);
    activeStep.value = 'export';
    planError.value = null;
    return true;
  } catch (err) {
    if (await handlePlanConflict(err)) return false;
    planError.value = errorMessage(err);
    return false;
  }
}

function previewExport(): boolean {
  if (!plan.value || plan.value.trips.length === 0) return false;
  activeStep.value = 'export';
  return true;
}

async function exportCsv(): Promise<void> {
  if (!plan.value) return;
  try {
    const factoryId = requireFactoryId();
    const res = await apiExportPlanCsv(factoryId, plan.value.id);
    triggerDownload(new Blob([res.data], { type: 'text/csv;charset=utf-8' }), `${plan.value.planNumber || plan.value.id}.csv`);
  } catch (err) {
    planError.value = errorMessage(err);
  }
}

async function exportXlsx(): Promise<void> {
  if (!plan.value) return;
  try {
    const factoryId = requireFactoryId();
    const res = await apiExportPlanXlsx(factoryId, plan.value.id);
    triggerDownload(
      new Blob([res.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }),
      `${plan.value.planNumber || plan.value.id}.xlsx`,
    );
  } catch (err) {
    planError.value = errorMessage(err);
  }
}

function planSignature(trips: PlanSnapshot['trips']): string {
  return trips.map((t) => `${t.vehicleId ?? '-'}:${t.storeIds.join(',')}`).join('|');
}

/**
 * 乐观锁冲突（计划被另一处并发修改，版本号对不上 → 后端 409「数据已被其他用户修改」）自愈：
 * 自动拉取最新计划刷新本地版本，用户重试即可成功——不设 planError 横幅、不额外堆 toast
 * （全局请求拦截器已弹一条 grouping 去重的提示）。返回 true = 已作为冲突处理，调用方直接 return。
 */
async function handlePlanConflict(err: unknown): Promise<boolean> {
  if (!isOptimisticLockConflict(err) || !plan.value) return false;
  await reloadPlan();
  return true;
}

async function regeneratePlanAction(): Promise<void> {
  if (!plan.value) return;
  planLoading.value = true;
  planError.value = null;
  const before = planSignature(plan.value.trips);
  try {
    const factoryId = requireFactoryId();
    // 带上当前优化目标 + 装载率 → 后端覆盖存储值并真重建 (不是 generate 幂等短路)。
    const res = await apiRegeneratePlan(factoryId, plan.value.id, optimizeMode.value, targetLoadPct.value);
    applyPlanSnapshot(res.data);
    // 反馈: 重新生成是确定性优化, 若方案未变(已是当前优化目标下最优)给出明确提示, 避免"点了没反应"的困惑。
    const modeLabel = optimizeMode.value === 'TIME' ? '时间最快' : '路程最短';
    const { ElMessage } = await import('element-plus');
    if (planSignature(res.data.trips) === before) {
      ElMessage({
        message: `已按「${modeLabel}」重新生成 —— 当前已是最优方案，路线无变化。如需改变，可调整目标装载率/优化目标，或在「人工确认」手动调整。`,
        type: 'info',
        duration: 6000,
        showClose: true,
      });
    } else {
      ElMessage({ message: `已按「${modeLabel}」重新生成路线`, type: 'success', duration: 3000 });
    }
  } catch (err) {
    if (await handlePlanConflict(err)) return;
    planError.value = errorMessage(err);
  } finally {
    planLoading.value = false;
  }
}

// ==================== 动作：调度记录 / 恢复 ====================

async function loadPlanHistory(page = 0, size = 20): Promise<void> {
  recordsLoading.value = true;
  recordsError.value = null;
  try {
    const factoryId = requireFactoryId();
    const res = await apiListPlans(factoryId, { page, size });
    planHistory.value = res.data;
  } catch (err) {
    recordsError.value = errorMessage(err);
  } finally {
    recordsLoading.value = false;
  }
}

/** 打开一个已存在的计划（调度记录「查看详情」/ 页面刷新恢复共用） */
async function openPlan(planId: string): Promise<boolean> {
  planLoading.value = true;
  planError.value = null;
  try {
    const factoryId = requireFactoryId();
    const res = await apiGetPlan(factoryId, planId);
    applyPlanSnapshot(res.data);
    const batchRes = await getOrderBatch(factoryId, res.data.orderBatchId);
    batch.value = batchRes.data;
    await loadOrders(batchRes.data.id);
    activeStep.value = (res.data.status === 'CONFIRMED' || res.data.status === 'EXPORTED') ? 'export' : 'map';
    return true;
  } catch (err) {
    planError.value = errorMessage(err);
    return false;
  } finally {
    planLoading.value = false;
  }
}

/**
 * 页面刷新/重新进入后的状态恢复（handoff §12.3）。
 * 优先用 URL 中的 planId；否则尝试恢复最近一个未取消的计划（"最新草稿"）。
 * 找不到任何历史计划时静默留空（这是合法的初始空态，不弹错误）。
 */
async function restore(planIdFromQuery?: string | null): Promise<void> {
  if (planIdFromQuery) {
    await openPlan(planIdFromQuery);
    return;
  }
  if (plan.value) return; // 内存中已有状态（同一 SPA 会话内在模块间切换），无需重新拉取
  try {
    const factoryId = requireFactoryId();
    const res = await apiListPlans(factoryId, { page: 0, size: 1 });
    const latest = res.data.content[0];
    if (latest && latest.status !== 'CANCELLED') {
      await openPlan(latest.id);
    }
  } catch (err) {
    // 没有历史计划 / 未登录时属于正常空态，不弹错误 toast，只留控制台痕迹便于排查。
    console.warn('[logistics] restore: no existing plan to resume', err);
  }
}

function reset(): void {
  batch.value = null;
  batches.value = [];
  preview.value = null;
  orders.value = [];
  ordersPage.value = null;
  vehicles.value = [];
  drivers.value = [];
  plan.value = null;
  planHistory.value = null;
  activeStep.value = 'import';
  selectedTripId.value = null;
  selectedStoreId.value = null;
  targetLoadPct.value = 88;
  optimizeMode.value = 'DISTANCE';
  importError.value = null;
  ordersError.value = null;
  resourcesError.value = null;
  planError.value = null;
  recordsError.value = null;
}

// ==================== 导出 ====================

const state = {
  // 导入
  batch,
  batches,
  preview,
  uploading,
  committing,
  importError,

  // 订单
  orders,
  ordersLoading,
  ordersError,
  ordersPage,
  stores,
  unresolvedOrders,
  outOfBoundsOrderIds,

  // 资源
  vehicles,
  drivers,
  resourcesLoading,
  resourcesError,
  vehiclesView,

  // 计划
  plan,
  planLoading,
  planError,
  targetLoadPct,
  optimizeMode,
  setOptimizeMode,
  analyzing,
  analyzeProgress,
  scheduleResult,
  activeTrip,
  exportRows,

  // 记录
  planHistory,
  recordsLoading,
  recordsError,

  // UI 选中态
  activeStep,
  selectedTripId,
  selectedStoreId,

  // 动作
  downloadTemplate,
  uploadPreview,
  commitImport,
  submitManualOrders,
  loadBatches,
  loadOrders,
  loadOrdersPage,
  updateLocation,

  loadVehicles,
  loadDrivers,
  loadResources,
  saveVehicleProfile,
  addDriver,
  saveDriver,
  saveVehicleDrivers,

  generateRoutes,
  setTargetLoad,
  selectTrip,
  selectStore,
  moveStore,
  moveStoreToTrip,
  assignVehicle,
  assignDriver,
  confirmTrip,
  confirmSchedule,
  previewExport,
  exportCsv,
  exportXlsx,
  regeneratePlanAction,
  reloadPlan,

  loadPlanHistory,
  openPlan,
  restore,
  reset,
};

export function useLogisticsScheduling() {
  return state;
}

export function resetLogisticsScheduling(): void {
  reset();
}
