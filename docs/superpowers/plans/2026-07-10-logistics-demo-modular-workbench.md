# Logistics Demo Modular Workbench Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single 1203-line logistics demo page with four logistics modules and a detailed, deterministic multi-stop scheduling workbench whose map, cards, manual ordering, vehicle counts, and exports all share one truthful state.

**Architecture:** Pure Vue 3 frontend mock. A deterministic domain engine produces `ScheduleResult` and `RouteTrip[]`; a shared composable owns workflow state; focused components render the map and four steps; secondary pages read the same fixture state. The detailed map uses a sanitized local base image plus a fixed `1917 × 1165` SVG coordinate system and predeclared road segments.

**Tech Stack:** Vue 3, TypeScript 5.9, Pinia-compatible composables, Element Plus, Vitest 4, Vue Test Utils, Playwright.

## Global Constraints

- Work only in `C:\Users\Steve\my-prototype-logistics\.worktrees\logistics-demo-modular` on branch `codex/logistics-demo-modular`.
- Keep the implementation frontend-only: no Java/Python API, database, Flyway, GPS, finance, driver App, or map SDK changes.
- Do not commit the raw customer screenshot. Generate a sanitized detailed map asset containing public map context and only the 13 existing mock stores.
- Map image and SVG share fixed coordinates `1917 × 1165`; no `object-fit: cover` cropping.
- Never fall back from missing road geometry to a two-point straight line.
- `RouteTrip[]` is the only source for cards, map, counts, confirmation, and export.
- Overflow must become a real next trip or an explicit unassigned state; never leave it in the original trip while claiming it moved.
- Customer UI must not contain “会议讲法、Demo、MVP、后续接入、算法路线图”. The logistics demo tenant may show one low-noise “演示数据” badge in the global/header context.
- Tests must report unit/component/smoke/medium separately; do not claim deep E2E in this frontend-only scope.

---

## File Structure

```text
web-admin/src/assets/logistics/
└── suzhou-logistics-map.png

web-admin/src/views/scheduling/logistics/
├── types.ts                         # Shared domain contracts only
├── routeEngine.ts                   # Pure schedule/trip/reorder/CSV logic
├── mockData.ts                      # 13 stores, vehicles, anchors, records
├── roadSegments.ts                  # Fixed segment registry and geometry assembly
├── useLogisticsDemoState.ts         # Shared reactive workflow state
├── __tests__/
│   ├── routeEngine.spec.ts
│   ├── mapFixtures.spec.ts
│   └── useLogisticsDemoState.spec.ts
├── components/
│   ├── LogisticsStepBar.vue
│   ├── LogisticsMap.vue
│   ├── RouteCards.vue
│   ├── StoreDetailDrawer.vue
│   ├── OrderImportStep.vue
│   ├── ManualConfirmStep.vue
│   ├── ExportConfirmStep.vue
│   └── __tests__/
│       ├── LogisticsMap.spec.ts
│       └── workflowSteps.spec.ts
├── workbench/index.vue
├── records/index.vue
├── orders/index.vue
└── resources/index.vue

tests/v1-e2e/web/logistics-demo-medium.spec.ts
```

Modify:

- `web-admin/src/router/index.ts`
- `web-admin/src/components/layout/menuConfig.ts`
- `web-admin/src/components/layout/__tests__/menuConfig.spec.ts`
- `web-admin/src/views/demo/demoRoute.ts`

Remove after cutover:

- `web-admin/src/views/scheduling/logistics-demo/index.vue`

---

### Task 1: Deterministic trip generation engine

**Files:**
- Create: `web-admin/src/views/scheduling/logistics/types.ts`
- Create: `web-admin/src/views/scheduling/logistics/routeEngine.ts`
- Test: `web-admin/src/views/scheduling/logistics/__tests__/routeEngine.spec.ts`

**Interfaces:**
- Consumes: `StoreOrder[]`, `Vehicle[]`, `RoadSegmentRegistry`, `targetLoadPct`.
- Produces: `generateSchedule(input): ScheduleResult`, `reorderTrip(input): RouteTrip`, `buildExportRows(result): ExportRow[]`.

- [ ] **Step 1: Write failing domain tests**

```ts
import { describe, expect, it } from 'vitest';
import { buildExportRows, generateSchedule, reorderTrip } from '../routeEngine';
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

  it('assigns every schedulable store exactly once', () => {
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
```

- [ ] **Step 2: Run tests and confirm RED**

Run:

```powershell
cd web-admin
npx vitest run src/views/scheduling/logistics/__tests__/routeEngine.spec.ts
```

Expected: FAIL because `types.ts` and `routeEngine.ts` do not exist.

- [ ] **Step 3: Implement exact contracts and engine**

`types.ts` must export the following names without aliases:

```ts
export interface MapPoint { x: number; y: number }
export interface StoreOrder {
  id: string; name: string; address: string; area: string;
  boxes: number; pieces: number; weightKg: number; volumeCbm: number;
  window: string; mapAnchor: MapPoint;
}
export interface Vehicle {
  id: string; plate: string; capacityCbm: number; maxWeightKg: number;
  areaCodes: string[]; source: '自有' | '外协'; driverId: string | null;
  driverName: string; backupDrivers: string[]; vehicleBody: string; shift: string;
}
export interface RoadSegment {
  fromId: 'DEPOT' | string; toId: 'DEPOT' | string;
  geometry: MapPoint[]; distanceKm: number;
}
export type RoadSegmentRegistry = Record<string, RoadSegment>;
export interface RouteTrip {
  id: string; routeGroupId: string; tripNo: number;
  vehicleId: string | null; driverId: string | null; storeIds: string[];
  segmentKeys: string[]; geometry: MapPoint[]; segmentDistances: number[];
  totalDistanceKm: number; totalVolumeCbm: number; loadRate: number;
  status: 'draft' | 'needs_vehicle' | 'needs_route_data' | 'confirmed';
}
export interface ScheduleResult {
  trips: RouteTrip[]; unassignedStoreIds: string[];
  assignedVehicleCount: number; additionalVehicleCount: number;
}
export interface ExportRow {
  tripId: string; vehicle: string; driver: string; storeIds: string[];
  storeOrder: string; volume: string; loadRate: string; distance: string;
}
```

`routeEngine.ts` must implement:

```ts
export function segmentKey(fromId: string, toId: string): string {
  return `${fromId}->${toId}`;
}

export function assembleTripGeometry(storeIds: string[], registry: RoadSegmentRegistry) {
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
```

`generateSchedule()` groups candidates by vehicle `areaCodes`, greedily closes a trip at `targetCap`, never exceeds `hardCap`, assigns the primary matching vehicle only to its first trip, and leaves additional trips `needs_vehicle` when no unused matching vehicle exists. `buildExportRows()` iterates `result.trips`, never pre-split store collections.

Use this exact packing rule so the target-load slider is deterministic and testable:

1. Assign each store to the first vehicle, in fixture order, whose `areaCodes` contains the store area; then preserve store input order inside that vehicle group. This prevents overlapping service areas from duplicating a store.
2. Before adding the next store, close the current non-empty trip when the addition would exceed `targetCap`.
3. A single store may exceed `targetCap`, but no trip may exceed `hardCap`; a store over `hardCap` goes to `unassignedStoreIds`.
4. Re-run the complete grouping and packing process whenever `targetLoadPct` changes after routes have been generated.
5. If any required segment is absent, keep `geometry: []`, set `needs_route_data`, and never synthesize a straight line.

- [ ] **Step 4: Run tests and confirm GREEN**

Run the same Vitest command. Expected: all route engine tests PASS.

- [ ] **Step 5: Commit**

```powershell
git add web-admin/src/views/scheduling/logistics/types.ts web-admin/src/views/scheduling/logistics/routeEngine.ts web-admin/src/views/scheduling/logistics/__tests__/routeEngine.spec.ts
git commit -m "feat(logistics): add deterministic multi-trip scheduling engine"
```

---

### Task 2: Sanitized detailed map fixture and road segments

**Files:**
- Create: `web-admin/src/assets/logistics/suzhou-logistics-map.png`
- Create: `web-admin/src/views/scheduling/logistics/mockData.ts`
- Create: `web-admin/src/views/scheduling/logistics/roadSegments.ts`
- Test: `web-admin/src/views/scheduling/logistics/__tests__/mapFixtures.spec.ts`

**Interfaces:**
- Produces: `MOCK_STORES`, `MOCK_VEHICLES`, `MOCK_RECORDS`, `DEPOT_POINT`, `ROAD_SEGMENTS`, `SUPPORTED_ROUTE_ORDERS`.

- [ ] **Step 1: Write failing fixture validation tests**

```ts
import { describe, expect, it } from 'vitest';
import { MOCK_STORES, MOCK_VEHICLES, SUPPORTED_ROUTE_ORDERS } from '../mockData';
import { ROAD_SEGMENTS } from '../roadSegments';
import { assembleTripGeometry, generateSchedule } from '../routeEngine';

describe('logistics map fixtures', () => {
  it('keeps every anchor inside the 1917x1165 coordinate system', () => {
    for (const store of MOCK_STORES) {
      expect(store.mapAnchor.x).toBeGreaterThanOrEqual(0);
      expect(store.mapAnchor.x).toBeLessThanOrEqual(1917);
      expect(store.mapAnchor.y).toBeGreaterThanOrEqual(0);
      expect(store.mapAnchor.y).toBeLessThanOrEqual(1165);
    }
  });

  it('has complete road geometry for every enabled route order', () => {
    for (const order of SUPPORTED_ROUTE_ORDERS) {
      const assembled = assembleTripGeometry(order, ROAD_SEGMENTS);
      expect(assembled.missing, order.join(' -> ')).toEqual([]);
      expect(assembled.geometry.length).toBeGreaterThan(order.length);
      expect(assembled.distances.reduce((sum, value) => sum + value, 0)).toBeGreaterThan(0);
    }
  });

  it('has no customer-only store IDs outside the 13 public mock stores', () => {
    expect(MOCK_STORES.map((store) => store.id)).toEqual([
      'S-001','S-002','S-003','S-004','S-005','S-006','S-007',
      'S-008','S-009','S-010','S-011','S-012','S-013',
    ]);
  });

  it('produces five default trips and schedules all 13 stores exactly once', () => {
    const result = generateSchedule({ stores: MOCK_STORES, vehicles: MOCK_VEHICLES, roadSegments: ROAD_SEGMENTS, targetLoadPct: 88 });
    const ids = result.trips.flatMap((trip) => trip.storeIds);
    expect(result.trips).toHaveLength(5);
    expect(ids).toHaveLength(13);
    expect(new Set(ids).size).toBe(13);
  });
});
```

- [ ] **Step 2: Run tests and confirm RED**

```powershell
cd web-admin
npx vitest run src/views/scheduling/logistics/__tests__/mapFixtures.spec.ts
```

Expected: FAIL because fixture modules do not exist.

- [ ] **Step 3: Create the sanitized map asset**

Use the image generation/edit tool with the customer image as reference and this exact prompt:

```text
Create a sanitized high-detail map background for a logistics scheduling dashboard.
Preserve the Suzhou-area visual structure: Taihu Lake on the west, Yangcheng Lake
on the northeast, realistic arterial roads, expressways, rivers, district labels,
public transport/park landmarks, and a clean commercial Chinese map aesthetic.
Remove every blue store pin, every store-name bubble, personal name, store number,
and customer-specific annotation from the reference. Do not add any delivery routes
or private business labels. Output exactly 1917x1165 pixels, sharp enough for a
desktop dashboard, with no cropping and no watermark invented by the model.
```

Save the approved output as `web-admin/src/assets/logistics/suzhou-logistics-map.png`. Inspect it visually before continuing. Do not copy the raw customer JPG into the repository.

- [ ] **Step 4: Add exact store anchors and supported orders**

Move all 13 existing store and 4 vehicle fixtures out of the old page. Add these map anchors, then visually calibrate only if the numbered marker misses the intended public location:

```ts
export const STORE_ANCHORS = {
  'S-001': { x: 665, y: 210 }, 'S-002': { x: 925, y: 390 },
  'S-003': { x: 932, y: 441 }, 'S-004': { x: 930, y: 500 },
  'S-005': { x: 1490, y: 411 }, 'S-006': { x: 1340, y: 490 },
  'S-007': { x: 1240, y: 535 }, 'S-008': { x: 1000, y: 670 },
  'S-009': { x: 575, y: 575 }, 'S-010': { x: 775, y: 650 },
  'S-011': { x: 650, y: 1040 }, 'S-012': { x: 1200, y: 1060 },
  'S-013': { x: 930, y: 950 },
} as const;

export const SUPPORTED_ROUTE_ORDERS = [
  ['S-001','S-003','S-004'],
  ['S-003','S-001','S-004'],
  ['S-005','S-006','S-007','S-008'],
  ['S-006','S-005','S-007','S-008'],
  ['S-011','S-013'],
  ['S-013','S-011'],
  ['S-002','S-009','S-010'],
  ['S-009','S-002','S-010'],
  ['S-012'],
] as const;
```

`ROAD_SEGMENTS` must define every directed pair required by these orders, including `DEPOT->first`. Every geometry starts at the `fromId` anchor and ends at the `toId` anchor, contains at least one intermediate road waypoint, and has a positive `distanceKm`.

At the default `88%` target, the fixture must produce five trips that collectively contain all 13 store IDs exactly once: 3 stores for V-01, 4 stores plus a real 2-store overflow trip for the V-02 area, 3 stores for V-03, and 1 store for V-04.

- [ ] **Step 5: Run fixture tests and confirm GREEN**

Run the same Vitest command. Expected: all fixture tests PASS.

- [ ] **Step 6: Commit**

```powershell
git add web-admin/src/assets/logistics/suzhou-logistics-map.png web-admin/src/views/scheduling/logistics/mockData.ts web-admin/src/views/scheduling/logistics/roadSegments.ts web-admin/src/views/scheduling/logistics/__tests__/mapFixtures.spec.ts
git commit -m "feat(logistics): add sanitized detailed map fixtures"
```

---

### Task 3: Shared logistics workflow state

**Files:**
- Create: `web-admin/src/views/scheduling/logistics/useLogisticsDemoState.ts`
- Test: `web-admin/src/views/scheduling/logistics/__tests__/useLogisticsDemoState.spec.ts`

**Interfaces:**
- Consumes: Task 1 engine and Task 2 fixtures.
- Produces: `useLogisticsDemoState()` singleton composable with `activeStep`, `scheduleResult`, `selectedTripId`, `selectedStoreId`, and workflow actions.

- [ ] **Step 1: Write failing state tests**

```ts
import { beforeEach, describe, expect, it } from 'vitest';
import { resetLogisticsDemoState, useLogisticsDemoState } from '../useLogisticsDemoState';

describe('useLogisticsDemoState', () => {
  beforeEach(() => resetLogisticsDemoState());

  it('moves from import to map and generates truthful trips', () => {
    const state = useLogisticsDemoState();
    state.importOrders();
    expect(state.imported.value).toBe(true);
    expect(state.activeStep.value).toBe('import');
    state.generateRoutes();
    expect(state.activeStep.value).toBe('map');
    expect(state.scheduleResult.value.trips.length).toBeGreaterThan(4);
    expect(state.scheduleResult.value.additionalVehicleCount).toBeGreaterThanOrEqual(1);
  });

  it('reorders one supported trip and updates export order and distance', () => {
    const state = useLogisticsDemoState();
    state.importOrders();
    state.generateRoutes();
    const trip = state.scheduleResult.value.trips.find((item) => item.storeIds.join(',') === 'S-001,S-003,S-004')!;
    const before = trip.totalDistanceKm;
    state.selectTrip(trip.id);
    expect(state.moveStore('S-003', -1)).toBe(true);
    const after = state.activeTrip.value!;
    expect(after.storeIds).toEqual(['S-003','S-001','S-004']);
    expect(after.totalDistanceKm).not.toBe(before);
    expect(state.exportRows.value.find((row) => row.tripId === after.id)!.storeIds).toEqual(after.storeIds);
  });
});
```

- [ ] **Step 2: Run and confirm RED**

```powershell
cd web-admin
npx vitest run src/views/scheduling/logistics/__tests__/useLogisticsDemoState.spec.ts
```

- [ ] **Step 3: Implement the composable**

Expose these exact actions:

```ts
type LogisticsStep = 'import' | 'map' | 'confirm' | 'export';

export function useLogisticsDemoState() {
  return {
    stores, vehicles, targetLoadPct, activeStep, imported, scheduleResult,
    selectedTripId, selectedStoreId, activeTrip, exportRows,
    importOrders, generateRoutes, setTargetLoad, selectTrip, selectStore,
    moveStore, assignVehicle, assignDriver, confirmTrip, confirmSchedule, reset,
  };
}
export function resetLogisticsDemoState(): void;
```

`moveStore()` first calculates the proposed order, checks `assembleTripGeometry()` for missing segments, returns `false` without mutation when unsupported, and otherwise replaces that one trip and recomputes export rows.

`setTargetLoad()` regenerates `RouteTrip[]` immediately only after import/routes exist. `assignVehicle()` and `assignDriver()` update the selected trip from the mock resource pool, reject a duplicate vehicle/driver conflict, and leave the trip visibly `needs_vehicle` when no valid resource is selected. They demonstrate manual dispatch only; they do not claim external-car procurement or driver-side synchronization.

- [ ] **Step 4: Run tests and confirm GREEN**

- [ ] **Step 5: Commit**

```powershell
git add web-admin/src/views/scheduling/logistics/useLogisticsDemoState.ts web-admin/src/views/scheduling/logistics/__tests__/useLogisticsDemoState.spec.ts
git commit -m "feat(logistics): add shared scheduling workflow state"
```

---

### Task 4: Detailed multi-route map and readable route cards

**Files:**
- Create: `web-admin/src/views/scheduling/logistics/components/LogisticsMap.vue`
- Create: `web-admin/src/views/scheduling/logistics/components/RouteCards.vue`
- Create: `web-admin/src/views/scheduling/logistics/components/StoreDetailDrawer.vue`
- Test: `web-admin/src/views/scheduling/logistics/components/__tests__/LogisticsMap.spec.ts`

**Interfaces:**
- Consumes: `RouteTrip[]`, `StoreOrder[]`, selected trip/store IDs.
- Emits: `select-trip`, `select-store`.

- [ ] **Step 1: Write failing component tests**

```ts
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import LogisticsMap from '../LogisticsMap.vue';
import { MOCK_STORES } from '../../mockData';
import { generateSchedule } from '../../routeEngine';
import { MOCK_VEHICLES } from '../../mockData';
import { ROAD_SEGMENTS } from '../../roadSegments';

describe('LogisticsMap', () => {
  it('renders every trip and numbers only the selected trip nodes', () => {
    const result = generateSchedule({ stores: MOCK_STORES, vehicles: MOCK_VEHICLES, roadSegments: ROAD_SEGMENTS, targetLoadPct: 88 });
    const wrapper = mount(LogisticsMap, { props: { stores: MOCK_STORES, trips: result.trips, selectedTripId: result.trips[0].id, selectedStoreId: null } });
    expect(wrapper.findAll('[data-testid="route-path"]')).toHaveLength(result.trips.length);
    expect(wrapper.findAll('[data-testid="selected-stop-number"]')).toHaveLength(result.trips[0].storeIds.length);
    expect(wrapper.find('[data-testid="map-image"]').attributes('viewBox')).toBe('0 0 1917 1165');
  });
});
```

- [ ] **Step 2: Run and confirm RED**

```powershell
cd web-admin
npx vitest run src/views/scheduling/logistics/components/__tests__/LogisticsMap.spec.ts
```

- [ ] **Step 3: Implement map layers**

`LogisticsMap.vue` must use this layer order:

```vue
<div class="map-stage" style="aspect-ratio: 1917 / 1165">
  <img class="base-map" :src="mapImage" alt="苏州配送地图" />
  <svg data-testid="map-image" viewBox="0 0 1917 1165" preserveAspectRatio="xMidYMid meet">
    <g class="route-layer">
      <template v-for="trip in trips" :key="trip.id">
        <polyline class="route-casing" :points="points(trip.geometry)" />
        <polyline data-testid="route-path" :data-trip-id="trip.id" :class="['route-line', { selected: trip.id === selectedTripId }]" :points="points(trip.geometry)" />
      </template>
    </g>
    <g class="store-layer"><!-- anchors and labels --></g>
    <g class="sequence-layer"><!-- selected trip 1..N --></g>
  </svg>
</div>
```

All routes remain visible. Selected route is thicker; nonselected routes retain at least `opacity: .58`. Route names and card text use `#101828`/`#344054`; only statuses use semantic colors.

- [ ] **Step 4: Implement route cards and store drawer**

Cards render `trip.storeIds` in order, vehicle or “待匹配车辆”, total distance, load rate, and status. The drawer shows pieces, boxes, weight, volume, address, and time window. Do not render provider-switch buttons or roadmap copy.

- [ ] **Step 5: Run component tests and confirm GREEN**

- [ ] **Step 6: Commit**

```powershell
git add web-admin/src/views/scheduling/logistics/components
git commit -m "feat(logistics): render detailed multi-stop route map"
```

---

### Task 5: Four-step scheduling workbench

**Files:**
- Create: `web-admin/src/views/scheduling/logistics/components/LogisticsStepBar.vue`
- Create: `web-admin/src/views/scheduling/logistics/components/OrderImportStep.vue`
- Create: `web-admin/src/views/scheduling/logistics/components/ManualConfirmStep.vue`
- Create: `web-admin/src/views/scheduling/logistics/components/ExportConfirmStep.vue`
- Create: `web-admin/src/views/scheduling/logistics/components/__tests__/workflowSteps.spec.ts`
- Create: `web-admin/src/views/scheduling/logistics/workbench/index.vue`

**Interfaces:**
- Consumes/updates Task 3 shared state.
- Produces the route entry page `/scheduling/logistics/workbench`.

- [ ] **Step 1: Write failing workflow component test**

```ts
import { mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it } from 'vitest';
import Workbench from '../../workbench/index.vue';
import { resetLogisticsDemoState } from '../../useLogisticsDemoState';

describe('logistics workbench steps', () => {
  beforeEach(() => resetLogisticsDemoState());
  it('shows one task stage at a time', async () => {
    const wrapper = mount(Workbench, { global: { stubs: { ElButton: false } } });
    expect(wrapper.find('[data-testid="import-step"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="map-step"]').exists()).toBe(false);
    await wrapper.get('[data-testid="import-orders"]').trigger('click');
    await wrapper.get('[data-testid="generate-routes"]').trigger('click');
    expect(wrapper.find('[data-testid="map-step"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="export-step"]').exists()).toBe(false);
  });
});
```

- [ ] **Step 2: Run and confirm RED**

- [ ] **Step 3: Implement the step shell**

`workbench/index.vue` renders only the active step. Keep the top summary compact. Use a sticky bottom action bar for back/next actions. Show the low-noise `演示数据` badge in the page header only for this mock route.

Step behavior:

- Import: template download, import, field/address validation summary.
- Map: detailed map + route cards + target load + generate.
- Confirm: selected route, ordered stores, vehicle/driver selectors, backup-driver display, supported move buttons, conditional exceptions. A pending overflow trip can be left as “待匹配车辆” or manually assigned from the mock resource pool.
- Export: final rows + confirm + real CSV download.

- [ ] **Step 4: Implement conditional messages**

Render no persistent “防呆提示”. Show a single `需要处理` alert only when `needs_vehicle`, `needs_route_data`, unassigned stores, or vehicle/driver conflict exists.

- [ ] **Step 5: Run tests and confirm GREEN**

- [ ] **Step 6: Commit**

```powershell
git add web-admin/src/views/scheduling/logistics/components web-admin/src/views/scheduling/logistics/workbench
git commit -m "feat(logistics): add four-step scheduling workbench"
```

---

### Task 6: Records, orders, and resources modules

**Files:**
- Create: `web-admin/src/views/scheduling/logistics/records/index.vue`
- Create: `web-admin/src/views/scheduling/logistics/orders/index.vue`
- Create: `web-admin/src/views/scheduling/logistics/resources/index.vue`
- Test: `web-admin/src/views/scheduling/logistics/components/__tests__/workflowSteps.spec.ts`

**Interfaces:**
- Consumes shared mock fixtures/state.
- Produces three routable lightweight modules.

- [ ] **Step 1: Add failing page assertions**

Extend `workflowSteps.spec.ts` to mount each page and assert exact headings and core columns:

```ts
expect(mount(RecordsPage).text()).toContain('调度记录');
expect(mount(OrdersPage).text()).toContain('门店与订单');
expect(mount(ResourcesPage).text()).toContain('车辆与司机');
```

- [ ] **Step 2: Run and confirm RED**

- [ ] **Step 3: Implement records page**

Render date, batch number, store count, trip count, total distance, status, “查看详情”, and “再次导出”. All rows come from `MOCK_RECORDS` plus confirmed current state.

- [ ] **Step 4: Implement orders page**

Render template/import actions and columns: store code/name/address/pieces/boxes/weight/volume/location status. Clicking a row opens the same store drawer.

- [ ] **Step 5: Implement resources page**

Render filters for all/self-owned/outsourced and columns: plate, capacity, max weight, body, driver, backup driver, fixed area, shift, source.

- [ ] **Step 6: Run tests and confirm GREEN**

- [ ] **Step 7: Commit**

```powershell
git add web-admin/src/views/scheduling/logistics/records web-admin/src/views/scheduling/logistics/orders web-admin/src/views/scheduling/logistics/resources web-admin/src/views/scheduling/logistics/components/__tests__/workflowSteps.spec.ts
git commit -m "feat(logistics): add scheduling support modules"
```

---

### Task 7: Route, menu, demo redirect, and legacy cutover

**Files:**
- Modify: `web-admin/src/router/index.ts:1605-1616`
- Modify: `web-admin/src/components/layout/menuConfig.ts:247-264`
- Modify: `web-admin/src/components/layout/__tests__/menuConfig.spec.ts:256-270`
- Modify: `web-admin/src/views/demo/demoRoute.ts`
- Delete: `web-admin/src/views/scheduling/logistics-demo/index.vue`

**Interfaces:**
- Produces four customer-facing routes and preserves the old URL as a hidden redirect.

- [ ] **Step 1: Update menu tests first**

```ts
it('exposes four logistics modules only to LOGISTICS tenants', () => {
  const paths = [
    '/scheduling/logistics/workbench',
    '/scheduling/logistics/records',
    '/scheduling/logistics/orders',
    '/scheduling/logistics/resources',
  ];
  expect(paths.map((path) => findDescendant('/scheduling', path)?.title)).toEqual([
    '排线工作台', '调度记录', '门店与订单', '车辆与司机',
  ]);
  for (const path of paths) {
    expect(findDescendant('/scheduling', path)?.hideForFactoryTypes).toEqual(['FACTORY', 'RESTAURANT']);
  }
});
```

- [ ] **Step 2: Run test and confirm RED**

```powershell
cd web-admin
npx vitest run src/components/layout/__tests__/menuConfig.spec.ts
```

- [ ] **Step 3: Add exact child routes**

Add:

```ts
{
  path: 'logistics/workbench', name: 'LogisticsSchedulingWorkbench',
  component: () => import('@/views/scheduling/logistics/workbench/index.vue'),
  meta: { requiresAuth: true, title: '排线工作台', module: 'scheduling', mockDemo: true, hideForFactoryTypes: ['FACTORY', 'RESTAURANT'] },
},
```

Repeat with names `LogisticsSchedulingRecords`, `LogisticsSchedulingOrders`, and `LogisticsSchedulingResources`. Keep a hidden legacy route:

```ts
{ path: 'logistics-demo', redirect: '/scheduling/logistics/workbench', meta: { hidden: true, mockDemo: true } }
```

- [ ] **Step 4: Update menu and demo redirect**

Menu order and labels:

```ts
{ path: '/scheduling/logistics/workbench', title: '排线工作台', module: 'scheduling', groupLabel: '日常调度', hideForFactoryTypes: ['FACTORY','RESTAURANT'] },
{ path: '/scheduling/logistics/records', title: '调度记录', module: 'scheduling', hideForFactoryTypes: ['FACTORY','RESTAURANT'] },
{ path: '/scheduling/logistics/orders', title: '门店与订单', module: 'scheduling', groupLabel: '基础资料', hideForFactoryTypes: ['FACTORY','RESTAURANT'] },
{ path: '/scheduling/logistics/resources', title: '车辆与司机', module: 'scheduling', hideForFactoryTypes: ['FACTORY','RESTAURANT'] },
```

Change logistics default redirect to `/scheduling/logistics/workbench`.

- [ ] **Step 5: Delete legacy monolith and run tests**

```powershell
npx vitest run src/components/layout/__tests__/menuConfig.spec.ts src/views/scheduling/logistics
npm run build:check
```

Expected: all tests PASS; typecheck and build PASS.

- [ ] **Step 6: Commit**

```powershell
git add web-admin/src/router/index.ts web-admin/src/components/layout/menuConfig.ts web-admin/src/components/layout/__tests__/menuConfig.spec.ts web-admin/src/views/demo/demoRoute.ts web-admin/src/views/scheduling/logistics
git rm web-admin/src/views/scheduling/logistics-demo/index.vue
git commit -m "feat(logistics): split scheduling demo into four modules"
```

---

### Task 8: Medium Playwright workflow and final verification

**Files:**
- Create: `tests/v1-e2e/web/logistics-demo-medium.spec.ts`

**Interfaces:**
- Verifies the customer-visible workflow; does not claim persistence/deep coverage.

- [ ] **Step 1: Write the medium E2E test**

```ts
import { test, expect } from '@playwright/test';

test.describe('物流排线工作台 @medium', () => {
  test('导入→多点排线→调序→导出', async ({ page }) => {
    await page.goto('/demo?tenant=logistics&redirect=/scheduling/logistics/workbench');
    await page.waitForURL(/\/scheduling\/logistics\/workbench/);
    await expect(page.getByRole('heading', { name: '今日排线工作台' })).toBeVisible();

    await page.getByTestId('import-orders').click();
    await expect(page.getByText('13 家校验通过')).toBeVisible();
    await page.getByTestId('generate-routes').click();

    const routePaths = page.getByTestId('route-path');
    await expect(routePaths).toHaveCount(5);
    await expect(page.getByTestId('route-card').first()).toContainText('→');
    await expect(page.getByText(/待匹配车辆/)).toBeVisible();

    await page.getByTestId('route-card').first().click();
    const before = await page.getByTestId('active-store-order').textContent();
    const beforeDistance = await page.getByTestId('active-distance').textContent();
    await page.getByTestId('move-store-up').nth(1).click();
    await expect(page.getByTestId('active-store-order')).not.toHaveText(before || '');
    await expect(page.getByTestId('active-distance')).not.toHaveText(beforeDistance || '');

    await page.getByTestId('next-confirm').click();
    await page.getByTestId('next-export').click();
    const downloadPromise = page.waitForEvent('download');
    await page.getByTestId('export-csv').click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toContain('物流排线结果');
  });

  test('四个物流模块均可进入', async ({ page }) => {
    await page.goto('/demo?tenant=logistics&redirect=/scheduling/logistics/workbench');
    for (const [path, heading] of [
      ['/scheduling/logistics/workbench', '今日排线工作台'],
      ['/scheduling/logistics/records', '调度记录'],
      ['/scheduling/logistics/orders', '门店与订单'],
      ['/scheduling/logistics/resources', '车辆与司机'],
    ] as const) {
      await page.goto(path);
      await expect(page.getByRole('heading', { name: heading })).toBeVisible();
    }
  });
});
```

- [ ] **Step 2: Start the required services and run headed once**

Use the project E2E environment, then run:

```powershell
$env:E2E_BASE_URL='http://localhost:5173'
cd tests/v1-e2e
npx playwright test web/logistics-demo-medium.spec.ts --headed --project=chromium
```

Expected: 2 medium tests PASS with no redirect to `/403`.

- [ ] **Step 3: Run the full scoped verification**

```powershell
cd web-admin
npx vitest run src/views/scheduling/logistics src/components/layout/__tests__/menuConfig.spec.ts
npm run build:check
cd ../../tests/v1-e2e
npx playwright test web/logistics-demo-medium.spec.ts --project=chromium
```

Expected:

- Unit/component tests all PASS.
- `vue-tsc` and Vite build PASS.
- 2 medium E2E tests PASS.
- No deep E2E is claimed.

- [ ] **Step 4: Capture evidence**

Save screenshots for:

1. Import step with 13 stores validated.
2. Detailed all-route map with distinct multi-stop nodes.
3. Selected route with numbered delivery order.
4. Capacity split showing a real next trip and pending vehicle.
5. Export step.

- [ ] **Step 5: Commit**

```powershell
git add tests/v1-e2e/web/logistics-demo-medium.spec.ts
git commit -m "test(logistics): cover modular scheduling workflow"
```

---

## Final Gate

Before PR creation:

```powershell
git status --short
git diff origin/main...HEAD --stat
git log --oneline origin/main..HEAD
git ls-files | Select-String 'a58c80b04bc67663ffdcef58609d75eb|meeting-20260709-logistics'
```

Expected scope:

- Logistics views/components/tests and sanitized asset.
- Router/menu/demo redirect and menu test.
- One new logistics medium E2E file.
- Superpowers spec and plan.
- No backend, database, finance, restaurant, production, or GPS changes.
- The final `git ls-files` privacy check prints no raw customer screenshot or meeting-transcript artifact.

Use `superpowers:verification-before-completion` before claiming complete, then `superpowers:requesting-code-review` before merging.
