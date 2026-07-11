export interface MapPoint { x: number; y: number }

export interface StoreOrder {
  id: string;
  name: string;
  address: string;
  area: string;
  boxes: number;
  pieces: number;
  weightKg: number;
  volumeCbm: number;
  window: string;
  mapAnchor: MapPoint;
  /** 真实经纬度（高德地图底图用；SVG 示意图用 mapAnchor）。 */
  lng: number;
  lat: number;
}

export interface Vehicle {
  id: string;
  plate: string;
  capacityCbm: number;
  maxWeightKg: number;
  areaCodes: string[];
  source: '自有' | '外协';
  driverId: string | null;
  driverName: string;
  backupDrivers: string[];
  vehicleBody: string;
  shift: string;
}

export interface RoadSegment {
  fromId: 'DEPOT' | string;
  toId: 'DEPOT' | string;
  geometry: MapPoint[];
  distanceKm: number;
}

export type RoadSegmentRegistry = Record<string, RoadSegment>;

export interface RouteTrip {
  id: string;
  routeGroupId: string;
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
  loadRate: number;
  status: 'draft' | 'needs_vehicle' | 'needs_driver' | 'needs_route_data' | 'confirmed';
}

export interface ScheduleResult {
  trips: RouteTrip[];
  unassignedStoreIds: string[];
  assignedVehicleCount: number;
  additionalVehicleCount: number;
}

export interface ExportRow {
  tripId: string;
  vehicle: string;
  driver: string;
  storeIds: string[];
  storeOrder: string;
  volume: string;
  loadRate: string;
  distance: string;
}
