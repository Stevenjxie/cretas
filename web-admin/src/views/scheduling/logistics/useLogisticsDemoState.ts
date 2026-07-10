import { computed, ref } from 'vue';
import { MOCK_STORES, MOCK_VEHICLES } from './mockData';
import { ROAD_SEGMENTS } from './roadSegments';
import { assembleTripGeometry, buildExportRows, generateSchedule, reorderTrip } from './routeEngine';
import type { RouteTrip, ScheduleResult, StoreOrder, Vehicle } from './types';

export type LogisticsStep = 'import' | 'map' | 'confirm' | 'export';

function emptyScheduleResult(): ScheduleResult {
  return {
    trips: [],
    unassignedStoreIds: [],
    assignedVehicleCount: 0,
    additionalVehicleCount: 0,
  };
}

function cloneStores(): StoreOrder[] {
  return MOCK_STORES.map((store) => ({
    ...store,
    mapAnchor: { ...store.mapAnchor },
  }));
}

function cloneVehicles(): Vehicle[] {
  return MOCK_VEHICLES.map((vehicle) => ({
    ...vehicle,
    areaCodes: [...vehicle.areaCodes],
    backupDrivers: [...vehicle.backupDrivers],
  }));
}

const stores = ref(cloneStores());
const vehicles = ref(cloneVehicles());
const targetLoadPct = ref(88);
const activeStep = ref<LogisticsStep>('import');
const imported = ref(false);
const scheduleResult = ref<ScheduleResult>(emptyScheduleResult());
const selectedTripId = ref<string | null>(null);
const selectedStoreId = ref<string | null>(null);

const activeTrip = computed(() => scheduleResult.value.trips.find((trip) => trip.id === selectedTripId.value) ?? null);
const exportRows = computed(() => buildExportRows(scheduleResult.value));

function statusFor(trip: RouteTrip): RouteTrip['status'] {
  if (assembleTripGeometry(trip.storeIds, ROAD_SEGMENTS).missing.length) return 'needs_route_data';
  return trip.vehicleId ? 'draft' : 'needs_vehicle';
}

function replaceTrips(trips: RouteTrip[]): void {
  scheduleResult.value = {
    ...scheduleResult.value,
    trips,
    assignedVehicleCount: new Set(trips.flatMap((trip) => trip.vehicleId ? [trip.vehicleId] : [])).size,
    additionalVehicleCount: trips.filter((trip) => trip.vehicleId === null).length,
  };
}

function replaceTrip(nextTrip: RouteTrip): void {
  replaceTrips(scheduleResult.value.trips.map((trip) => trip.id === nextTrip.id ? nextTrip : trip));
}

function importOrders(): void {
  imported.value = true;
  selectedStoreId.value = selectedStoreId.value ?? stores.value[0]?.id ?? null;
  activeStep.value = 'import';
}

function generateRoutes(): void {
  if (!imported.value) importOrders();

  scheduleResult.value = generateSchedule({
    stores: stores.value,
    vehicles: vehicles.value,
    roadSegments: ROAD_SEGMENTS,
    targetLoadPct: targetLoadPct.value,
  });
  selectedTripId.value = scheduleResult.value.trips.some((trip) => trip.id === selectedTripId.value)
    ? selectedTripId.value
    : scheduleResult.value.trips[0]?.id ?? null;
  selectedStoreId.value = null;
  activeStep.value = 'map';
}

function setTargetLoad(value: number): void {
  if (!Number.isFinite(value) || value <= 0 || value > 100) {
    throw new RangeError('targetLoadPct must be greater than 0 and at most 100');
  }
  targetLoadPct.value = value;
  if (imported.value && scheduleResult.value.trips.length > 0) generateRoutes();
}

function selectTrip(tripId: string | null): void {
  selectedTripId.value = scheduleResult.value.trips.some((trip) => trip.id === tripId) ? tripId : null;
  const trip = activeTrip.value;
  if (trip && selectedStoreId.value && !trip.storeIds.includes(selectedStoreId.value)) selectedStoreId.value = null;
}

function selectStore(storeId: string | null): void {
  selectedStoreId.value = stores.value.some((store) => store.id === storeId) ? storeId : null;
  const trip = scheduleResult.value.trips.find((item) => item.storeIds.includes(storeId ?? ''));
  if (trip) selectedTripId.value = trip.id;
}

function moveStore(storeId: string, direction: -1 | 1): boolean {
  const trip = activeTrip.value;
  if (!trip) return false;
  const index = trip.storeIds.indexOf(storeId);
  const nextIndex = index + direction;
  if (index < 0 || nextIndex < 0 || nextIndex >= trip.storeIds.length) return false;

  const storeIds = [...trip.storeIds];
  [storeIds[index], storeIds[nextIndex]] = [storeIds[nextIndex], storeIds[index]];
  if (assembleTripGeometry(storeIds, ROAD_SEGMENTS).missing.length) return false;

  replaceTrip(reorderTrip({ trip, storeIds, roadSegments: ROAD_SEGMENTS }));
  return true;
}

function vehicleIsInUse(vehicleId: string, tripId: string): boolean {
  return scheduleResult.value.trips.some((trip) => trip.id !== tripId && trip.vehicleId === vehicleId);
}

function driverIsInUse(driverId: string, tripId: string): boolean {
  return scheduleResult.value.trips.some((trip) => trip.id !== tripId && trip.driverId === driverId);
}

function vehicleById(vehicleId: string): Vehicle | undefined {
  return vehicles.value.find((vehicle) => vehicle.id === vehicleId);
}

function tripWeightKg(trip: RouteTrip): number {
  return trip.storeIds.reduce((total, storeId) => total + (stores.value.find((store) => store.id === storeId)?.weightKg ?? 0), 0);
}

function getVehicleAssignmentIssue(vehicleId: string | null): string | null {
  const trip = activeTrip.value;
  if (!trip || vehicleId === null) return null;
  const vehicle = vehicleById(vehicleId);
  if (!vehicle) return '未找到所选车辆。';
  if (vehicleIsInUse(vehicle.id, trip.id)) return `车辆已被其他车次使用：${vehicle.plate}。`;
  if (vehicle.driverId !== null && driverIsInUse(vehicle.driverId, trip.id)) return `司机已被其他车次使用：${vehicle.driverName}。`;
  const uncoveredAreas = [...new Set(trip.storeIds
    .map((storeId) => stores.value.find((store) => store.id === storeId)?.area)
    .filter((area): area is string => Boolean(area))
    .filter((area) => !vehicle.areaCodes.includes(area)))];
  if (uncoveredAreas.length) return `车辆服务区域不覆盖：${uncoveredAreas.join('、')}。`;
  if (vehicle.capacityCbm < trip.totalVolumeCbm) return `车辆容量不足：需 ${trip.totalVolumeCbm.toFixed(1)} m³，车辆仅 ${vehicle.capacityCbm.toFixed(1)} m³。`;
  const weightKg = tripWeightKg(trip);
  if (vehicle.maxWeightKg < weightKg) return `车辆载重不足：需 ${weightKg} kg，车辆仅 ${vehicle.maxWeightKg} kg。`;
  return null;
}

function getDriverAssignmentIssue(driverId: string | null): string | null {
  const trip = activeTrip.value;
  if (!trip || driverId === null) return null;
  if (!trip.vehicleId) return '请先匹配车辆，再选择该车的司机。';
  const vehicle = vehicleById(trip.vehicleId);
  if (!vehicle || vehicle.driverId !== driverId) return '司机必须属于当前车辆。';
  if (driverIsInUse(driverId, trip.id)) return `司机已被其他车次使用：${vehicle.driverName}。`;
  return null;
}

function assignVehicle(vehicleId: string | null): boolean {
  const trip = activeTrip.value;
  if (!trip) return false;

  if (vehicleId === null) {
    replaceTrip({
      ...trip,
      vehicleId: null,
      driverId: null,
      vehiclePlate: undefined,
      driverName: undefined,
      status: 'needs_vehicle',
    });
    return true;
  }

  const issue = getVehicleAssignmentIssue(vehicleId);
  const vehicle = vehicleById(vehicleId);
  if (issue || !vehicle) return false;

  const nextTrip: RouteTrip = {
    ...trip,
    vehicleId: vehicle.id,
    driverId: vehicle.driverId,
    vehiclePlate: vehicle.plate,
    driverName: vehicle.driverName,
    loadRate: vehicle.capacityCbm > 0 ? trip.totalVolumeCbm / vehicle.capacityCbm : 0,
  };
  replaceTrip({ ...nextTrip, status: statusFor(nextTrip) });
  return true;
}

function assignDriver(driverId: string | null): boolean {
  const trip = activeTrip.value;
  if (!trip) return false;

  if (driverId === null) {
    const nextTrip: RouteTrip = { ...trip, driverId: null, driverName: undefined };
    replaceTrip({ ...nextTrip, status: statusFor(nextTrip) });
    return true;
  }

  const issue = getDriverAssignmentIssue(driverId);
  const vehicle = vehicles.value.find((candidate) => candidate.driverId === driverId);
  if (issue || !vehicle) return false;

  const nextTrip = { ...trip, driverId, driverName: vehicle.driverName };
  replaceTrip({ ...nextTrip, status: statusFor(nextTrip) });
  return true;
}

function confirmTrip(): boolean {
  const trip = activeTrip.value;
  if (!trip || trip.status !== 'draft') return false;
  replaceTrip({ ...trip, status: 'confirmed' });
  activeStep.value = 'confirm';
  return true;
}

function confirmSchedule(): boolean {
  const isResolved = scheduleResult.value.trips.length > 0
    && scheduleResult.value.unassignedStoreIds.length === 0
    && scheduleResult.value.trips.every((trip) => trip.status === 'confirmed');
  if (!isResolved) {
    activeStep.value = 'confirm';
    return false;
  }
  activeStep.value = 'export';
  return true;
}

function previewExport(): boolean {
  if (!scheduleResult.value.trips.length) return false;
  activeStep.value = 'export';
  return true;
}

function reset(): void {
  stores.value = cloneStores();
  vehicles.value = cloneVehicles();
  targetLoadPct.value = 88;
  activeStep.value = 'import';
  imported.value = false;
  scheduleResult.value = emptyScheduleResult();
  selectedTripId.value = null;
  selectedStoreId.value = null;
}

const state = {
  stores,
  vehicles,
  targetLoadPct,
  activeStep,
  imported,
  scheduleResult,
  selectedTripId,
  selectedStoreId,
  activeTrip,
  exportRows,
  importOrders,
  generateRoutes,
  setTargetLoad,
  selectTrip,
  selectStore,
  moveStore,
  getVehicleAssignmentIssue,
  getDriverAssignmentIssue,
  assignVehicle,
  assignDriver,
  confirmTrip,
  confirmSchedule,
  previewExport,
  reset,
};

export function useLogisticsDemoState() {
  return state;
}

export function resetLogisticsDemoState(): void {
  reset();
}
