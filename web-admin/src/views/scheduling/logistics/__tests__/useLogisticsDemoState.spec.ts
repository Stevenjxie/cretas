import { beforeEach, describe, expect, it } from 'vitest';
import { resetLogisticsDemoState, useLogisticsDemoState } from '../useLogisticsDemoState';

function prepareRoutes() {
  const state = useLogisticsDemoState();
  state.importOrders();
  state.generateRoutes();
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

  it('selects stores, confirms a viable trip, and resets the singleton', () => {
    const state = prepareRoutes();
    const trip = state.scheduleResult.value.trips.find((item) => item.status === 'draft')!;

    state.selectStore(trip.storeIds[0]);
    expect(state.selectedStoreId.value).toBe(trip.storeIds[0]);
    expect(state.selectedTripId.value).toBe(trip.id);
    expect(state.confirmTrip()).toBe(true);
    expect(state.activeTrip.value).toMatchObject({ status: 'confirmed' });

    state.confirmSchedule();
    expect(state.activeStep.value).toBe('export');
    state.reset();
    expect(state.imported.value).toBe(false);
    expect(state.scheduleResult.value.trips).toEqual([]);
  });
});
