import { beforeEach, describe, expect, it } from 'vitest';
import { MOCK_STORES, MOCK_VEHICLES } from '../mockData';
import { resetLogisticsDemoState, useLogisticsDemoState } from '../useLogisticsDemoState';

function prepareRoutes() {
  const state = useLogisticsDemoState();
  state.importOrders();
  state.generateRoutes();
  return state;
}

function prepareConfirmedRoutes() {
  const state = useLogisticsDemoState();
  state.stores.value = state.stores.value.filter((store) => !['S-011', 'S-013'].includes(store.id));
  state.importOrders();
  state.generateRoutes();
  for (const trip of [...state.scheduleResult.value.trips]) {
    state.selectTrip(trip.id);
    expect(state.confirmTrip()).toBe(true);
  }
  expect(state.scheduleResult.value.trips.every((trip) => trip.status === 'confirmed')).toBe(true);
  return state;
}

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
    const state = prepareRoutes();
    const trip = state.scheduleResult.value.trips.find((item) => item.storeIds.join(',') === 'S-001,S-003,S-004')!;
    const before = trip.totalDistanceKm;
    state.selectTrip(trip.id);

    expect(state.moveStore('S-003', -1)).toBe(true);

    const after = state.activeTrip.value!;
    expect(after.storeIds).toEqual(['S-003', 'S-001', 'S-004']);
    expect(after.totalDistanceKm).not.toBe(before);
    expect(state.exportRows.value.find((row) => row.tripId === after.id)!.storeIds).toEqual(after.storeIds);
  });

  it('refuses an unsupported move without mutating the trip or export rows', () => {
    const state = prepareRoutes();
    const trip = state.scheduleResult.value.trips.find((item) => item.storeIds.join(',') === 'S-001,S-003,S-004')!;
    const before = { storeIds: [...trip.storeIds], totalDistanceKm: trip.totalDistanceKm };
    state.selectTrip(trip.id);

    expect(state.moveStore('S-004', -1)).toBe(false);

    expect(state.activeTrip.value).toMatchObject(before);
    expect(state.exportRows.value.find((row) => row.tripId === trip.id)!.storeIds).toEqual(before.storeIds);
  });

  it('regenerates routes after target-load changes only once routes exist', () => {
    const state = useLogisticsDemoState();
    state.setTargetLoad(70);
    expect(state.scheduleResult.value.trips).toEqual([]);

    state.importOrders();
    expect(state.scheduleResult.value.trips).toEqual([]);
    state.generateRoutes();
    const at70 = state.scheduleResult.value.trips.map((trip) => trip.storeIds);

    state.setTargetLoad(98);

    expect(state.targetLoadPct.value).toBe(98);
    expect(state.scheduleResult.value.trips.map((trip) => trip.storeIds)).not.toEqual(at70);
    expect(state.exportRows.value.map((row) => row.storeIds)).toEqual(state.scheduleResult.value.trips.map((trip) => trip.storeIds));
  });

  it('manually assigns only free mock resources and keeps conflicts visibly pending', () => {
    const state = prepareRoutes();
    const primary = state.scheduleResult.value.trips.find((trip) => trip.vehicleId === 'V-01')!;
    const pending = state.scheduleResult.value.trips.find((trip) => trip.status === 'needs_vehicle')!;

    state.selectTrip(pending.id);
    expect(state.assignVehicle('V-01')).toBe(false);
    expect(state.activeTrip.value).toMatchObject({ vehicleId: null, status: 'needs_vehicle' });

    state.selectTrip(primary.id);
    expect(state.assignVehicle(null)).toBe(true);
    expect(state.activeTrip.value).toMatchObject({ vehicleId: null, driverId: null, status: 'needs_vehicle' });

    state.selectTrip(pending.id);
    expect(state.assignVehicle('V-01')).toBe(true);
    expect(state.activeTrip.value).toMatchObject({ vehicleId: 'V-01', driverId: 'D-001', status: 'draft' });

    state.selectTrip(primary.id);
    expect(state.assignDriver('D-001')).toBe(false);
    expect(state.activeTrip.value).toMatchObject({ driverId: null, status: 'needs_vehicle' });
  });

  it('selects stores and refuses formal confirmation while the schedule is unresolved', () => {
    const state = prepareRoutes();
    const trip = state.scheduleResult.value.trips.find((item) => item.status === 'draft')!;

    state.selectStore(trip.storeIds[0]);
    expect(state.selectedStoreId.value).toBe(trip.storeIds[0]);
    expect(state.selectedTripId.value).toBe(trip.id);
    expect(state.confirmTrip()).toBe(true);
    expect(state.activeTrip.value).toMatchObject({ status: 'confirmed' });

    expect(state.confirmSchedule()).toBe(false);
    expect(state.activeStep.value).toBe('confirm');
    expect(state.scheduleResult.value.trips.some((item) => item.status === 'needs_vehicle')).toBe(true);
  });

  it('previews unresolved export rows without changing pending trip statuses', () => {
    const state = prepareRoutes();
    const statuses = state.scheduleResult.value.trips.map((trip) => trip.status);

    expect(state.previewExport()).toBe(true);

    expect(state.activeStep.value).toBe('export');
    expect(state.scheduleResult.value.trips.map((trip) => trip.status)).toEqual(statuses);
    expect(state.scheduleResult.value.trips.some((trip) => trip.status === 'needs_vehicle')).toBe(true);
  });

  it.each([
    ['needs_vehicle', (state: ReturnType<typeof useLogisticsDemoState>) => {
      state.scheduleResult.value.trips[0].status = 'needs_vehicle';
    }],
    ['needs_route_data', (state: ReturnType<typeof useLogisticsDemoState>) => {
      state.scheduleResult.value.trips[0].status = 'needs_route_data';
    }],
    ['draft', (state: ReturnType<typeof useLogisticsDemoState>) => {
      state.scheduleResult.value.trips[0].status = 'draft';
    }],
    ['unassigned stores', (state: ReturnType<typeof useLogisticsDemoState>) => {
      state.scheduleResult.value.unassignedStoreIds = ['S-PENDING'];
    }],
  ])('blocks formal confirmation for %s', (_condition, arrange) => {
    const state = prepareConfirmedRoutes();
    arrange(state);

    expect(state.confirmSchedule()).toBe(false);
    expect(state.activeStep.value).toBe('confirm');
  });

  it('formally confirms only a resolved schedule whose trips are all confirmed', () => {
    const state = prepareConfirmedRoutes();

    expect(state.confirmSchedule()).toBe(true);
    expect(state.activeStep.value).toBe('export');
    expect(state.scheduleResult.value.trips.every((trip) => trip.status === 'confirmed')).toBe(true);
  });

  it('restores deeply cloned store and vehicle fixtures on reset', () => {
    const state = useLogisticsDemoState();
    const original = {
      storeName: MOCK_STORES[0].name,
      mapAnchorX: MOCK_STORES[0].mapAnchor.x,
      vehiclePlate: MOCK_VEHICLES[0].plate,
      areaCodes: [...MOCK_VEHICLES[0].areaCodes],
      backupDrivers: [...MOCK_VEHICLES[0].backupDrivers],
    };

    state.stores.value[0].name = 'mutated store';
    state.stores.value[0].mapAnchor.x = -1;
    state.vehicles.value[0].plate = 'mutated plate';
    state.vehicles.value[0].areaCodes.push('mutated area');
    state.vehicles.value[0].backupDrivers[0] = 'mutated driver';

    expect(MOCK_STORES[0].name).toBe(original.storeName);
    expect(MOCK_STORES[0].mapAnchor.x).toBe(original.mapAnchorX);
    expect(MOCK_VEHICLES[0].plate).toBe(original.vehiclePlate);
    expect(MOCK_VEHICLES[0].areaCodes).toEqual(original.areaCodes);
    expect(MOCK_VEHICLES[0].backupDrivers).toEqual(original.backupDrivers);

    resetLogisticsDemoState();

    expect(state.stores.value[0].name).toBe(original.storeName);
    expect(state.stores.value[0].mapAnchor.x).toBe(original.mapAnchorX);
    expect(state.vehicles.value[0].plate).toBe(original.vehiclePlate);
    expect(state.vehicles.value[0].areaCodes).toEqual(original.areaCodes);
    expect(state.vehicles.value[0].backupDrivers).toEqual(original.backupDrivers);
  });
});
