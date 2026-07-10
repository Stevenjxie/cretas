import { describe, expect, it } from 'vitest';
import { assembleTripGeometry, buildExportRows, generateSchedule, reorderTrip, segmentKey } from '../routeEngine';
import type { RoadSegmentRegistry, StoreOrder, Vehicle } from '../types';

const stores: StoreOrder[] = [
  { id: 'A', name: 'A店', area: '吴中', volumeCbm: 4, weightKg: 400, boxes: 1, pieces: 1, address: 'A', window: '', mapAnchor: { x: 100, y: 100 } },
  { id: 'B', name: 'B店', area: '吴中', volumeCbm: 4, weightKg: 400, boxes: 1, pieces: 1, address: 'B', window: '', mapAnchor: { x: 200, y: 100 } },
  { id: 'C', name: 'C店', area: '吴中', volumeCbm: 3, weightKg: 300, boxes: 1, pieces: 1, address: 'C', window: '', mapAnchor: { x: 300, y: 100 } },
];
const vehicles: Vehicle[] = [
  { id: 'V1', plate: '苏E·TEST', capacityCbm: 10, maxWeightKg: 3000, areaCodes: ['吴中'], source: '自有', driverId: 'D1', driverName: '赵明', backupDrivers: [], vehicleBody: '双温车', shift: '08:00-18:00' },
];
const segments: RoadSegmentRegistry = {
  'DEPOT->A': { fromId: 'DEPOT', toId: 'A', geometry: [{ x: 50, y: 50 }, { x: 100, y: 100 }], distanceKm: 10 },
  'DEPOT->B': { fromId: 'DEPOT', toId: 'B', geometry: [{ x: 50, y: 50 }, { x: 200, y: 100 }], distanceKm: 14 },
  'A->B': { fromId: 'A', toId: 'B', geometry: [{ x: 100, y: 100 }, { x: 200, y: 100 }], distanceKm: 12 },
  'B->C': { fromId: 'B', toId: 'C', geometry: [{ x: 200, y: 100 }, { x: 300, y: 100 }], distanceKm: 9 },
  'DEPOT->C': { fromId: 'DEPOT', toId: 'C', geometry: [{ x: 50, y: 50 }, { x: 300, y: 100 }], distanceKm: 18 },
};

describe('generateSchedule', () => {
  it('creates a real next trip and removes overflow from the first trip', () => {
    const result = generateSchedule({ stores, vehicles, roadSegments: segments, targetLoadPct: 88 });
    expect(result.trips).toHaveLength(2);
    expect(result.trips[0].storeIds).toEqual(['A', 'B']);
    expect(result.trips[1].storeIds).toEqual(['C']);
    expect(result.trips[0].totalVolumeCbm).toBe(8);
    expect(result.trips[1].vehicleId).toBeNull();
    expect(result.additionalVehicleCount).toBe(1);
    expect(buildExportRows(result).map((row) => row.storeIds)).toEqual([['A', 'B'], ['C']]);
  });

  it('changes trip membership when target load changes', () => {
    const at70 = generateSchedule({ stores, vehicles, roadSegments: segments, targetLoadPct: 70 });
    const at98 = generateSchedule({ stores, vehicles, roadSegments: segments, targetLoadPct: 98 });
    expect(at70.trips.map((trip) => trip.storeIds)).not.toEqual(at98.trips.map((trip) => trip.storeIds));
  });

  it('assigns each schedulable store exactly once', () => {
    const result = generateSchedule({ stores, vehicles, roadSegments: segments, targetLoadPct: 88 });
    const ids = result.trips.flatMap((trip) => trip.storeIds);
    expect(ids.sort()).toEqual(['A', 'B', 'C']);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('does not invent a straight line when road geometry is missing', () => {
    const result = generateSchedule({ stores, vehicles, roadSegments: {}, targetLoadPct: 88 });
    expect(result.trips[0].geometry).toEqual([]);
    expect(result.trips[0].status).toBe('needs_route_data');
  });
});

describe('deterministic grouping and trip operations', () => {
  it('builds segment keys and joins registered geometry without duplicate points', () => {
    expect(segmentKey('A', 'B')).toBe('A->B');
    expect(assembleTripGeometry(['A', 'B'], segments)).toEqual({
      keys: ['DEPOT->A', 'A->B'],
      geometry: [{ x: 50, y: 50 }, { x: 100, y: 100 }, { x: 200, y: 100 }],
      distances: [10, 12],
      missing: [],
    });
  });

  it('uses the first matching vehicle and preserves store input order', () => {
    const overlappingVehicles = [
      { ...vehicles[0], id: 'V1', areaCodes: ['吴中', '相城'] },
      { ...vehicles[0], id: 'V2', areaCodes: ['吴中'] },
    ];
    const result = generateSchedule({ stores: [...stores].reverse(), vehicles: overlappingVehicles, roadSegments: segments, targetLoadPct: 100 });
    expect(result.trips[0].vehicleId).toBe('V1');
    expect(result.trips.map((trip) => trip.storeIds)).toEqual([['C', 'B'], ['A']]);
    expect(result.trips[1].vehicleId).toBe('V2');
    expect(result.trips.flatMap((trip) => trip.storeIds)).toEqual(['C', 'B', 'A']);
  });

  it('allows one target overflow but rejects a store over hard cap', () => {
    const largeStores = [
      { ...stores[0], volumeCbm: 9 },
      { ...stores[1], volumeCbm: 11 },
    ];
    const result = generateSchedule({ stores: largeStores, vehicles, roadSegments: segments, targetLoadPct: 80 });
    expect(result.trips.map((trip) => trip.storeIds)).toEqual([['A']]);
    expect(result.unassignedStoreIds).toEqual(['B']);
  });

  it('does not assign an overflow trip to a matching vehicle with insufficient capacity', () => {
    const overflowStores = [
      { ...stores[0], volumeCbm: 6 },
      { ...stores[1], volumeCbm: 4 },
    ];
    const mixedCapacityVehicles = [
      { ...vehicles[0], id: 'V1', capacityCbm: 10 },
      { ...vehicles[0], id: 'V2', capacityCbm: 3 },
    ];

    const result = generateSchedule({ stores: overflowStores, vehicles: mixedCapacityVehicles, roadSegments: segments, targetLoadPct: 60 });

    expect(result.trips.map((trip) => trip.vehicleId)).toEqual(['V1', null]);
    expect(result.trips[1].totalVolumeCbm).toBe(4);
  });

  it('reserves every non-empty group primary before assigning overflow vehicles', () => {
    const groupedStores = [
      { ...stores[0], id: 'A1', area: 'AREA-A', volumeCbm: 6 },
      { ...stores[1], id: 'A2', area: 'AREA-A', volumeCbm: 6 },
      { ...stores[2], id: 'B1', area: 'AREA-B', volumeCbm: 2 },
    ];
    const groupedVehicles = [
      { ...vehicles[0], id: 'V1', areaCodes: ['AREA-A'] },
      { ...vehicles[0], id: 'V2', areaCodes: ['AREA-A', 'AREA-B'] },
    ];

    const result = generateSchedule({ stores: groupedStores, vehicles: groupedVehicles, roadSegments: {}, targetLoadPct: 60 });

    expect(result.trips.map((trip) => trip.vehicleId)).toEqual(['V1', null, 'V2']);
    expect(result.trips.filter((trip) => trip.vehicleId === 'V2')).toHaveLength(1);
  });

  it.each([0, -1, 101, Number.NaN])('rejects invalid target load percentage %s', (targetLoadPct) => {
    expect(() => generateSchedule({ stores, vehicles, roadSegments: segments, targetLoadPct })).toThrow(RangeError);
  });

  it('never packs a valid target load beyond the primary vehicle hard cap', () => {
    const hardCapStores = [
      { ...stores[0], volumeCbm: 6 },
      { ...stores[1], volumeCbm: 5 },
    ];

    const result = generateSchedule({ stores: hardCapStores, vehicles, roadSegments: segments, targetLoadPct: 100 });

    expect(result.trips.map((trip) => trip.storeIds)).toEqual([['A'], ['B']]);
    expect(result.trips.every((trip) => trip.totalVolumeCbm <= vehicles[0].capacityCbm)).toBe(true);
  });

  it('reorders a trip and recalculates its route geometry', () => {
    const original = generateSchedule({ stores, vehicles, roadSegments: segments, targetLoadPct: 100 }).trips[0];
    const reordered = reorderTrip({ trip: original, storeIds: ['B', 'A'], roadSegments: segments });
    expect(reordered.storeIds).toEqual(['B', 'A']);
    expect(reordered.segmentKeys).toEqual(['DEPOT->B', 'B->A']);
    expect(reordered.status).toBe('needs_route_data');
  });

  it('exports every generated trip without splitting store collections', () => {
    const result = generateSchedule({ stores, vehicles, roadSegments: segments, targetLoadPct: 88 });
    const rows = buildExportRows(result);
    expect(rows).toHaveLength(result.trips.length);
    expect(rows[0]).toMatchObject({ vehicle: '苏E·TEST', driver: '赵明', storeOrder: 'A, B', volume: '8.00', loadRate: '80%', distance: '22.00 km' });
  });
});
