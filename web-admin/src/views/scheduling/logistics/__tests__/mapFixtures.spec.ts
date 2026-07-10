import { describe, expect, it } from 'vitest';
import { DEPOT_POINT, MOCK_STORES, MOCK_VEHICLES, SUPPORTED_ROUTE_ORDERS } from '../mockData';
import { ROAD_SEGMENTS } from '../roadSegments';
import { assembleTripGeometry, generateSchedule, segmentKey } from '../routeEngine';

const MAP_WIDTH = 1917;
const MAP_HEIGHT = 1165;

describe('logistics map fixtures', () => {
  it('keeps every anchor inside the 1917x1165 coordinate system', () => {
    for (const point of [DEPOT_POINT, ...MOCK_STORES.map((store) => store.mapAnchor)]) {
      expect(point.x).toBeGreaterThanOrEqual(0);
      expect(point.x).toBeLessThanOrEqual(MAP_WIDTH);
      expect(point.y).toBeGreaterThanOrEqual(0);
      expect(point.y).toBeLessThanOrEqual(MAP_HEIGHT);
    }
  });

  it('has complete road-following geometry for every enabled route order', () => {
    const anchors = new Map([
      ['DEPOT', DEPOT_POINT],
      ...MOCK_STORES.map((store) => [store.id, store.mapAnchor] as const),
    ]);

    for (const order of SUPPORTED_ROUTE_ORDERS) {
      const assembled = assembleTripGeometry([...order], ROAD_SEGMENTS);
      expect(assembled.missing, order.join(' -> ')).toEqual([]);
      expect(assembled.geometry.length).toBeGreaterThan(order.length);
      expect(assembled.distances.reduce((sum, value) => sum + value, 0)).toBeGreaterThan(0);

      const ids = ['DEPOT', ...order];
      for (let index = 1; index < ids.length; index += 1) {
        const fromId = ids[index - 1];
        const toId = ids[index];
        const segment = ROAD_SEGMENTS[segmentKey(fromId, toId)];
        expect(segment.geometry.length).toBeGreaterThanOrEqual(3);
        expect(segment.geometry[0]).toEqual(anchors.get(fromId));
        expect(segment.geometry.at(-1)).toEqual(anchors.get(toId));
        expect(segment.distanceKm).toBeGreaterThan(0);
      }
    }
  });

  it('uses only the 13 public mock store IDs', () => {
    expect(MOCK_STORES.map((store) => store.id)).toEqual([
      'S-001', 'S-002', 'S-003', 'S-004', 'S-005', 'S-006', 'S-007',
      'S-008', 'S-009', 'S-010', 'S-011', 'S-012', 'S-013',
    ]);
  });

  it('produces five default trips and schedules all 13 stores exactly once', () => {
    const result = generateSchedule({
      stores: MOCK_STORES,
      vehicles: MOCK_VEHICLES,
      roadSegments: ROAD_SEGMENTS,
      targetLoadPct: 88,
    });
    const ids = result.trips.flatMap((trip) => trip.storeIds);

    expect(result.trips).toHaveLength(5);
    expect(ids).toHaveLength(13);
    expect(new Set(ids).size).toBe(13);
    expect(result.trips.map((trip) => trip.storeIds)).toEqual([
      ['S-001', 'S-003', 'S-004'],
      ['S-002', 'S-009', 'S-010'],
      ['S-005', 'S-006', 'S-007', 'S-008'],
      ['S-011', 'S-013'],
      ['S-012'],
    ]);
    expect(result.trips[2].vehicleId).toBe('V-02');
    expect(result.trips[3]).toMatchObject({ vehicleId: null, status: 'needs_vehicle' });
  });
});
