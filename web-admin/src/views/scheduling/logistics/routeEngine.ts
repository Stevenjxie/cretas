import type {
  ExportRow,
  MapPoint,
  RoadSegmentRegistry,
  RouteTrip,
  ScheduleResult,
  StoreOrder,
  Vehicle,
} from './types';

export interface GenerateScheduleInput {
  stores: StoreOrder[];
  vehicles: Vehicle[];
  roadSegments: RoadSegmentRegistry;
  targetLoadPct: number;
}

export interface ReorderTripInput {
  trip: RouteTrip;
  storeIds: string[];
  roadSegments: RoadSegmentRegistry;
}

export function segmentKey(fromId: string, toId: string): string {
  return `${fromId}->${toId}`;
}

export function assembleTripGeometry(storeIds: string[], registry: RoadSegmentRegistry): {
  keys: string[];
  geometry: MapPoint[];
  distances: number[];
  missing: string[];
} {
  const pairs = storeIds.map((toId, index) => ({ fromId: index === 0 ? 'DEPOT' : storeIds[index - 1], toId }));
  const keys = pairs.map(({ fromId, toId }) => segmentKey(fromId, toId));
  const missing = keys.filter((key) => !registry[key]);
  if (missing.length) return { keys, geometry: [], distances: [], missing };
  const segments = keys.map((key) => registry[key]);
  return {
    keys,
    geometry: segments.flatMap((segment, index) => index === 0 ? segment.geometry : segment.geometry.slice(1)),
    distances: segments.map((segment) => segment.distanceKm),
    missing: [],
  };
}

function createTrip(
  routeGroupId: string,
  tripNo: number,
  stores: StoreOrder[],
  vehicle: Vehicle | null,
  registry: RoadSegmentRegistry,
): RouteTrip {
  const storeIds = stores.map((store) => store.id);
  const route = assembleTripGeometry(storeIds, registry);
  const totalVolumeCbm = stores.reduce((total, store) => total + store.volumeCbm, 0);
  const totalDistanceKm = route.distances.reduce((total, distance) => total + distance, 0);
  const status = route.missing.length ? 'needs_route_data' : vehicle ? 'draft' : 'needs_vehicle';
  return {
    id: `${routeGroupId}-trip-${tripNo}`,
    routeGroupId,
    tripNo,
    vehicleId: vehicle?.id ?? null,
    driverId: vehicle?.driverId ?? null,
    vehiclePlate: vehicle?.plate,
    driverName: vehicle?.driverName,
    storeIds,
    segmentKeys: route.keys,
    geometry: route.geometry,
    segmentDistances: route.distances,
    totalDistanceKm,
    totalVolumeCbm,
    loadRate: vehicle && vehicle.capacityCbm > 0 ? totalVolumeCbm / vehicle.capacityCbm : 0,
    status,
  };
}

function matchingVehicle(vehicle: Vehicle, stores: StoreOrder[]): boolean {
  const totalVolumeCbm = stores.reduce((total, store) => total + store.volumeCbm, 0);
  return totalVolumeCbm <= vehicle.capacityCbm
    && stores.every((store) => vehicle.areaCodes.includes(store.area));
}

export function generateSchedule({ stores, vehicles, roadSegments, targetLoadPct }: GenerateScheduleInput): ScheduleResult {
  if (!Number.isFinite(targetLoadPct) || targetLoadPct <= 0 || targetLoadPct > 100) {
    throw new RangeError('targetLoadPct must be greater than 0 and at most 100');
  }

  const groups = new Map<string, StoreOrder[]>();
  const unassignedStoreIds: string[] = [];

  for (const store of stores) {
    const vehicle = vehicles.find((candidate) => candidate.areaCodes.includes(store.area));
    if (!vehicle || store.volumeCbm > (vehicle.capacityCbm || 0)) {
      unassignedStoreIds.push(store.id);
      continue;
    }
    const group = groups.get(vehicle.id) ?? [];
    group.push(store);
    groups.set(vehicle.id, group);
  }

  const trips: RouteTrip[] = [];
  const usedVehicleIds = new Set(groups.keys());
  for (const [groupVehicleId, groupedStores] of groups) {
    const primaryVehicle = vehicles.find((vehicle) => vehicle.id === groupVehicleId) ?? null;
    if (!primaryVehicle) continue;
    const targetCap = primaryVehicle.capacityCbm * targetLoadPct / 100;
    let current: StoreOrder[] = [];
    let currentVolumeCbm = 0;
    const packed: StoreOrder[][] = [];
    for (const store of groupedStores) {
      const nextVolumeCbm = currentVolumeCbm + store.volumeCbm;
      if (current.length && nextVolumeCbm > primaryVehicle.capacityCbm) {
        packed.push(current);
        current = [];
        currentVolumeCbm = 0;
      } else if (current.length && nextVolumeCbm > targetCap) {
        packed.push(current);
        current = [];
        currentVolumeCbm = 0;
      }
      current.push(store);
      currentVolumeCbm += store.volumeCbm;
    }
    if (current.length) packed.push(current);

    packed.forEach((tripStores, index) => {
      const vehicle = index === 0
        ? primaryVehicle
        : vehicles.find((candidate) => !usedVehicleIds.has(candidate.id) && matchingVehicle(candidate, tripStores)) ?? null;
      if (vehicle) usedVehicleIds.add(vehicle.id);
      trips.push(createTrip(groupVehicleId, index + 1, tripStores, vehicle, roadSegments));
    });
  }

  return {
    trips,
    unassignedStoreIds,
    assignedVehicleCount: usedVehicleIds.size,
    additionalVehicleCount: trips.filter((trip) => trip.vehicleId === null).length,
  };
}

export function reorderTrip({ trip, storeIds, roadSegments }: ReorderTripInput): RouteTrip {
  const route = assembleTripGeometry(storeIds, roadSegments);
  const totalDistanceKm = route.distances.reduce((total, distance) => total + distance, 0);
  return {
    ...trip,
    storeIds,
    segmentKeys: route.keys,
    geometry: route.geometry,
    segmentDistances: route.distances,
    totalDistanceKm,
    status: route.missing.length ? 'needs_route_data' : trip.status,
  };
}

/**
 * 构建确认/导出表行。
 * @param storeName storeId → 门店名解析器(不传则回退 id, 供纯算法测试用)。
 *   确认/导出页必须传, 否则「门店顺序」列会显 UUID (nonsense)。
 */
export function buildExportRows(result: ScheduleResult, storeName?: (id: string) => string): ExportRow[] {
  const nameOf = storeName ?? ((id: string) => id);
  return result.trips.map((trip, index) => ({
    tripId: trip.id,
    tripLabel: `线路 ${String(index + 1).padStart(2, '0')}${trip.vehicleTripSeq && trip.vehicleTripSeq > 1 ? ` · 该车第 ${trip.vehicleTripSeq} 趟` : ''}`,
    vehicle: trip.vehiclePlate ?? trip.vehicleId ?? '',
    driver: trip.driverName ?? trip.driverId ?? '',
    storeIds: [...trip.storeIds],
    storeOrder: trip.storeIds.map((id) => nameOf(id)).join(' → '),
    volume: trip.totalVolumeCbm.toFixed(2),
    loadRate: `${Math.round(trip.loadRate * 100)}%`,
    distance: `${trip.totalDistanceKm.toFixed(2)} km`,
  }));
}
