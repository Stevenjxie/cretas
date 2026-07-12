import type { MapPoint, StoreOrder, Vehicle } from './types';

export const DEPOT_POINT: MapPoint = { x: 965, y: 600 };

export const STORE_ANCHORS = {
  'S-001': { x: 665, y: 210 },
  'S-002': { x: 925, y: 390 },
  'S-003': { x: 932, y: 441 },
  'S-004': { x: 930, y: 500 },
  'S-005': { x: 1490, y: 411 },
  'S-006': { x: 1340, y: 490 },
  'S-007': { x: 1240, y: 535 },
  'S-008': { x: 1000, y: 670 },
  'S-009': { x: 575, y: 575 },
  'S-010': { x: 775, y: 650 },
  'S-011': { x: 650, y: 1040 },
  'S-012': { x: 1200, y: 1060 },
  'S-013': { x: 930, y: 950 },
} as const satisfies Record<string, MapPoint>;

// lng/lat 与后端 DEMO seed (V20261028_05) 对齐 —— 苏州各区真实范围坐标（高德地图底图用）。
export const MOCK_STORES: StoreOrder[] = [
  { id: 'S-001', name: '配送门店 01', address: '苏州市相城区配送点 01', area: '相城', boxes: 18, pieces: 126, weightKg: 540, volumeCbm: 1.8, window: '09:30-12:00', mapAnchor: STORE_ANCHORS['S-001'], lng: 120.630, lat: 31.430 },
  { id: 'S-002', name: '配送门店 02', address: '苏州市虎丘区配送点 02', area: '虎丘', boxes: 28, pieces: 210, weightKg: 830, volumeCbm: 2.7, window: '10:00-13:30', mapAnchor: STORE_ANCHORS['S-002'], lng: 120.560, lat: 31.332 },
  { id: 'S-003', name: '配送门店 03', address: '苏州市姑苏区配送点 03', area: '姑苏', boxes: 22, pieces: 158, weightKg: 690, volumeCbm: 2.3, window: '10:30-14:00', mapAnchor: STORE_ANCHORS['S-003'], lng: 120.628, lat: 31.310 },
  { id: 'S-004', name: '配送门店 04', address: '苏州市姑苏区配送点 04', area: '姑苏', boxes: 16, pieces: 120, weightKg: 480, volumeCbm: 1.6, window: '11:00-15:00', mapAnchor: STORE_ANCHORS['S-004'], lng: 120.612, lat: 31.292 },
  { id: 'S-005', name: '配送门店 05', address: '苏州市吴中区配送点 05', area: '吴中', boxes: 20, pieces: 145, weightKg: 620, volumeCbm: 2.1, window: '13:00-16:00', mapAnchor: STORE_ANCHORS['S-005'], lng: 120.660, lat: 31.242 },
  { id: 'S-006', name: '配送门店 06', address: '苏州市工业园区配送点 06', area: '园区', boxes: 15, pieces: 98, weightKg: 410, volumeCbm: 1.4, window: '13:30-17:00', mapAnchor: STORE_ANCHORS['S-006'], lng: 120.720, lat: 31.322 },
  { id: 'S-007', name: '配送门店 07', address: '苏州市工业园区配送点 07', area: '园区', boxes: 30, pieces: 235, weightKg: 920, volumeCbm: 3.2, window: '14:00-17:30', mapAnchor: STORE_ANCHORS['S-007'], lng: 120.742, lat: 31.302 },
  { id: 'S-008', name: '配送门店 08', address: '苏州市吴中区配送点 08', area: '吴中', boxes: 17, pieces: 134, weightKg: 510, volumeCbm: 1.7, window: '15:00-18:00', mapAnchor: STORE_ANCHORS['S-008'], lng: 120.635, lat: 31.232 },
  { id: 'S-009', name: '配送门店 09', address: '苏州市高新区配送点 09', area: '高新', boxes: 24, pieces: 176, weightKg: 720, volumeCbm: 2.4, window: '09:00-12:00', mapAnchor: STORE_ANCHORS['S-009'], lng: 120.540, lat: 31.302 },
  { id: 'S-010', name: '配送门店 10', address: '苏州市高新区配送点 10', area: '高新', boxes: 21, pieces: 161, weightKg: 640, volumeCbm: 2.2, window: '10:00-13:00', mapAnchor: STORE_ANCHORS['S-010'], lng: 120.552, lat: 31.282 },
  { id: 'S-011', name: '配送门店 11', address: '苏州市吴中区配送点 11', area: '吴中', boxes: 13, pieces: 86, weightKg: 360, volumeCbm: 1.2, window: '15:00-19:00', mapAnchor: STORE_ANCHORS['S-011'], lng: 120.602, lat: 31.222 },
  { id: 'S-012', name: '配送门店 12', address: '苏州市吴江区配送点 12', area: '吴江', boxes: 26, pieces: 190, weightKg: 810, volumeCbm: 2.8, window: '16:00-20:00', mapAnchor: STORE_ANCHORS['S-012'], lng: 120.640, lat: 31.162 },
  { id: 'S-013', name: '配送门店 13', address: '苏州市吴中区配送点 13', area: '吴中', boxes: 22, pieces: 168, weightKg: 700, volumeCbm: 2, window: '16:30-20:00', mapAnchor: STORE_ANCHORS['S-013'], lng: 120.615, lat: 31.252 },
];

export const MOCK_VEHICLES: Vehicle[] = [
  { id: 'V-01', plate: '苏E·D001', capacityCbm: 10, maxWeightKg: 3200, areaCodes: ['姑苏', '相城'], source: '自有', driverId: 'D-001', driverName: '司机甲', backupDrivers: ['司机乙'], vehicleBody: '双温车', shift: '08:00-18:00' },
  { id: 'V-02', plate: '苏E·D002', capacityCbm: 10, maxWeightKg: 3200, areaCodes: ['园区', '吴中'], source: '自有', driverId: 'D-002', driverName: '司机丙', backupDrivers: ['司机丁'], vehicleBody: '双温车', shift: '09:00-20:00' },
  { id: 'V-03', plate: '苏E·D003', capacityCbm: 10, maxWeightKg: 3000, areaCodes: ['高新', '虎丘'], source: '自有', driverId: 'D-003', driverName: '司机戊', backupDrivers: ['司机甲'], vehicleBody: '双温车', shift: '08:30-18:30' },
  { id: 'V-04', plate: '外协·苏E04', capacityCbm: 12, maxWeightKg: 3800, areaCodes: ['吴江', '昆山'], source: '外协', driverId: 'D-004', driverName: '外协司机', backupDrivers: ['外协调度确认'], vehicleBody: '双温车', shift: '按单确认' },
];

export const SUPPORTED_ROUTE_ORDERS = [
  ['S-001', 'S-003', 'S-004'],
  ['S-003', 'S-001', 'S-004'],
  ['S-005', 'S-006', 'S-007', 'S-008'],
  ['S-006', 'S-005', 'S-007', 'S-008'],
  ['S-011', 'S-013'],
  ['S-013', 'S-011'],
  ['S-002', 'S-009', 'S-010'],
  ['S-009', 'S-002', 'S-010'],
  ['S-012'],
] as const;

export const MOCK_RECORDS = [
  {
    id: 'REC-001',
    date: '2026-07-10',
    batchNo: 'SCH-20260710-01',
    storeCount: 13,
    tripCount: 5,
    totalDistanceKm: 182.4,
    status: '已确认',
  },
];
