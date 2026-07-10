import { DEPOT_POINT, STORE_ANCHORS } from './mockData';
import type { MapPoint, RoadSegment, RoadSegmentRegistry } from './types';

const POINTS: Record<string, MapPoint> = { DEPOT: DEPOT_POINT, ...STORE_ANCHORS };

function segment(fromId: string, toId: string, distanceKm: number, waypoints: MapPoint[]): RoadSegment {
  return {
    fromId,
    toId,
    geometry: [POINTS[fromId], ...waypoints, POINTS[toId]],
    distanceKm,
  };
}

export const ROAD_SEGMENTS: RoadSegmentRegistry = {
  'DEPOT->S-001': segment('DEPOT', 'S-001', 17.6, [{ x: 830, y: 490 }, { x: 720, y: 330 }]),
  'S-001->S-003': segment('S-001', 'S-003', 8.4, [{ x: 760, y: 265 }, { x: 850, y: 350 }]),
  'S-003->S-004': segment('S-003', 'S-004', 4.2, [{ x: 970, y: 470 }]),
  'DEPOT->S-003': segment('DEPOT', 'S-003', 11.8, [{ x: 910, y: 545 }, { x: 900, y: 480 }]),
  'S-003->S-001': segment('S-003', 'S-001', 8.8, [{ x: 860, y: 360 }, { x: 750, y: 280 }]),
  'S-001->S-004': segment('S-001', 'S-004', 12.1, [{ x: 760, y: 320 }, { x: 850, y: 420 }]),

  'DEPOT->S-005': segment('DEPOT', 'S-005', 24.6, [{ x: 1100, y: 560 }, { x: 1300, y: 480 }]),
  'S-005->S-006': segment('S-005', 'S-006', 7.8, [{ x: 1440, y: 450 }]),
  'S-006->S-007': segment('S-006', 'S-007', 6.3, [{ x: 1290, y: 520 }]),
  'S-007->S-008': segment('S-007', 'S-008', 12.4, [{ x: 1140, y: 600 }, { x: 1080, y: 650 }]),
  'DEPOT->S-006': segment('DEPOT', 'S-006', 19.7, [{ x: 1100, y: 530 }, { x: 1240, y: 500 }]),
  'S-006->S-005': segment('S-006', 'S-005', 8.1, [{ x: 1400, y: 450 }]),
  'S-005->S-007': segment('S-005', 'S-007', 13.2, [{ x: 1400, y: 480 }, { x: 1300, y: 520 }]),

  'DEPOT->S-011': segment('DEPOT', 'S-011', 26.4, [{ x: 830, y: 760 }, { x: 720, y: 930 }]),
  'S-011->S-013': segment('S-011', 'S-013', 10.6, [{ x: 740, y: 1000 }, { x: 840, y: 970 }]),
  'DEPOT->S-013': segment('DEPOT', 'S-013', 20.8, [{ x: 950, y: 720 }, { x: 930, y: 840 }]),
  'S-013->S-011': segment('S-013', 'S-011', 10.9, [{ x: 840, y: 970 }, { x: 740, y: 1000 }]),

  'DEPOT->S-002': segment('DEPOT', 'S-002', 12.6, [{ x: 950, y: 500 }]),
  'S-002->S-009': segment('S-002', 'S-009', 18.9, [{ x: 800, y: 450 }, { x: 680, y: 540 }]),
  'S-009->S-010': segment('S-009', 'S-010', 9.7, [{ x: 650, y: 620 }, { x: 700, y: 650 }]),
  'DEPOT->S-009': segment('DEPOT', 'S-009', 18.2, [{ x: 820, y: 590 }, { x: 680, y: 580 }]),
  'S-009->S-002': segment('S-009', 'S-002', 19.3, [{ x: 680, y: 500 }, { x: 800, y: 430 }]),
  'S-002->S-010': segment('S-002', 'S-010', 14.1, [{ x: 900, y: 480 }, { x: 820, y: 580 }]),

  'DEPOT->S-012': segment('DEPOT', 'S-012', 31.5, [{ x: 1050, y: 800 }, { x: 1150, y: 950 }]),
};
