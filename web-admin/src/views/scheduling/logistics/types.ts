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
  /** 后端存好的沿实际道路 polyline (GCJ-02 {lng,lat})；有则地图直接画、免实时调地图 API。 */
  roadPath?: Array<{ lng: number; lat: number }>;
  /** 总行驶时长 (分钟)。 */
  durationMin?: number;
  /** 多车次: 计划出发时刻 (分钟, 480=08:00)。 */
  plannedDepartMin?: number;
  /** 多车次: 预计返仓时刻 (分钟)。 */
  returnToDepotMin?: number;
  /** 多车次: 迟到回仓 (晚于司机班次/车可用截止)。 */
  lateReturn?: boolean;
  /** 多车次: 同车当天第几趟 (1-based)。 */
  vehicleTripSeq?: number;
  segmentDistances: number[];
  totalDistanceKm: number;
  totalVolumeCbm: number;
  loadRate: number;
  status: 'draft' | 'needs_vehicle' | 'needs_driver' | 'needs_route_data' | 'confirmed';
  /** 该车次的乐观锁版本 —— 车次级写操作(改序/换车/换司机/确认)必须回传这个, 不是 plan.version。
   *  后端 assertTripVersion 校验的是 trip.version; 用 plan.version 会在第一次改动后就 409。 */
  version: number;
}

export interface ScheduleResult {
  trips: RouteTrip[];
  unassignedStoreIds: string[];
  assignedVehicleCount: number;
  additionalVehicleCount: number;
}

export interface ExportRow {
  tripId: string;
  /** 可读车次号(线路序号 + 该车第几趟), 给确认/导出表展示, 不显 UUID。 */
  tripLabel: string;
  vehicle: string;
  driver: string;
  storeIds: string[];
  storeOrder: string;
  volume: string;
  loadRate: string;
  distance: string;
}
