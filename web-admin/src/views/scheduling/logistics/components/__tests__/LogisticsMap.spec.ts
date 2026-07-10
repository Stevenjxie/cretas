import { mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it } from 'vitest';
import { MOCK_STORES, MOCK_VEHICLES } from '../../mockData';
import { ROAD_SEGMENTS } from '../../roadSegments';
import { generateSchedule } from '../../routeEngine';
import type { RouteTrip } from '../../types';
import LogisticsMap from '../LogisticsMap.vue';
import RouteCards from '../RouteCards.vue';
import StoreDetailDrawer from '../StoreDetailDrawer.vue';

const schedule = generateSchedule({
  stores: MOCK_STORES,
  vehicles: MOCK_VEHICLES,
  roadSegments: ROAD_SEGMENTS,
  targetLoadPct: 88,
});

const mapProps = () => ({
  stores: MOCK_STORES,
  trips: schedule.trips,
  selectedTripId: schedule.trips[0].id,
  selectedStoreId: null,
});

function activateNativeButton(element: Element, key: 'Enter' | ' '): void {
  const button = element as HTMLButtonElement;
  const keydown = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
  const shouldRunDefault = button.dispatchEvent(keydown);
  button.dispatchEvent(new KeyboardEvent('keyup', { key, bubbles: true, cancelable: true }));
  if (shouldRunDefault) button.click();
}

describe('LogisticsMap', () => {
  beforeEach(() => {
    expect(schedule.trips).toHaveLength(5);
  });

  it('keeps the image decorative and exposes the interactive SVG as a labelled group', () => {
    const wrapper = mount(LogisticsMap, { props: mapProps() });
    const image = wrapper.get('[data-testid="base-map"]');
    const svg = wrapper.get('[data-testid="map-image"]');

    expect(image.attributes('alt')).toBe('');
    expect(image.attributes('aria-hidden')).toBe('true');
    expect(svg.attributes('role')).toBe('group');
    expect(svg.attributes('role')).not.toBe('img');
    expect(svg.attributes('aria-label')).toBe('配送路线与门店');
  });

  it('renders every trip geometry in the exact shared map coordinate system', () => {
    const wrapper = mount(LogisticsMap, { props: mapProps() });
    const svg = wrapper.get('[data-testid="map-image"]');
    const routePaths = wrapper.findAll('[data-testid="route-path"]');

    expect(svg.attributes('viewBox')).toBe('0 0 1917 1165');
    expect(svg.attributes('preserveAspectRatio')).toBe('xMidYMid meet');
    expect(wrapper.get('[data-testid="base-map"]').attributes('src')).toContain('suzhou-logistics-map.png');
    expect(routePaths).toHaveLength(schedule.trips.length);
    routePaths.forEach((path, index) => {
      expect(path.attributes('points')).toBe(
        schedule.trips[index].geometry.map(({ x, y }) => `${x},${y}`).join(' '),
      );
    });
  });

  it('shows all 13 store anchors and numbers only the selected trip in order', () => {
    const wrapper = mount(LogisticsMap, { props: mapProps() });
    const numbers = wrapper.findAll('[data-testid="selected-stop-number"]');

    expect(wrapper.findAll('[data-testid="store-anchor"]')).toHaveLength(13);
    expect(numbers).toHaveLength(schedule.trips[0].storeIds.length);
    expect(numbers.map((node) => node.text())).toEqual(['1', '2', '3']);
    expect(numbers.map((node) => node.attributes('data-store-id'))).toEqual(schedule.trips[0].storeIds);
  });

  it('keeps selected sequence badges visual-only and out of the interaction tree', async () => {
    const wrapper = mount(LogisticsMap, { props: mapProps() });
    const numbers = wrapper.findAll('[data-testid="selected-stop-number"]');

    for (const number of numbers) {
      expect(number.attributes('aria-hidden')).toBe('true');
      expect(number.attributes('tabindex')).toBeUndefined();
      expect(number.attributes('role')).toBeUndefined();
    }

    await numbers[0].trigger('click');
    expect(wrapper.emitted('select-store')).toBeUndefined();
  });

  it('emits exact trip and store selection events', async () => {
    const wrapper = mount(LogisticsMap, { props: mapProps() });
    const secondTrip = schedule.trips[1];
    const store = MOCK_STORES[4];

    await wrapper.get(`[data-testid="route-path"][data-trip-id="${secondTrip.id}"]`).trigger('click');
    await wrapper.get(`[data-testid="store-anchor"][data-store-id="${store.id}"]`).trigger('click');

    expect(wrapper.emitted('select-trip')).toEqual([[secondTrip.id]]);
    expect(wrapper.emitted('select-store')).toEqual([[store.id]]);
  });

  it('renders no route geometry when a trip has no geometry', () => {
    const missingGeometryTrip: RouteTrip = {
      ...schedule.trips[0],
      id: 'missing-geometry-trip',
      geometry: [],
      status: 'needs_route_data',
    };
    const wrapper = mount(LogisticsMap, {
      props: {
        ...mapProps(),
        trips: [missingGeometryTrip],
        selectedTripId: missingGeometryTrip.id,
      },
    });

    expect(wrapper.findAll('[data-testid="route-path"]')).toHaveLength(0);
    expect(wrapper.findAll('.route-casing')).toHaveLength(0);
  });
});

describe('RouteCards', () => {
  it('renders ordered store chains, vehicle fallback, distance, and load', () => {
    const wrapper = mount(RouteCards, { props: mapProps() });
    const cards = wrapper.findAll('[data-testid="route-card"]');
    const pendingCard = cards.find((card) => card.attributes('data-trip-id') === schedule.trips[3].id);

    expect(cards).toHaveLength(schedule.trips.length);
    expect(cards[0].findAll('[data-testid="route-store"]').map((store) => store.text())).toEqual([
      '配送门店 01', '配送门店 03', '配送门店 04',
    ]);
    expect(cards[0].findAll('.chain-arrow')).toHaveLength(2);
    expect(cards[0].text()).toContain(schedule.trips[0].vehiclePlate);
    expect(cards[0].text()).toContain(`${schedule.trips[0].totalDistanceKm.toFixed(1)} km`);
    expect(cards[0].text()).toContain(`${Math.round(schedule.trips[0].loadRate * 100)}%`);
    expect(pendingCard?.text()).toContain('待匹配车辆');
  });

  it('emits exact card and store selection events', async () => {
    const wrapper = mount(RouteCards, { props: mapProps() });
    const trip = schedule.trips[2];
    const storeId = trip.storeIds[1];

    await wrapper.get(`[data-testid="route-card"][data-trip-id="${trip.id}"]`).trigger('click');
    await wrapper.get(`[data-testid="route-store"][data-store-id="${storeId}"]`).trigger('click');

    expect(wrapper.emitted('select-trip')).toEqual([[trip.id]]);
    expect(wrapper.emitted('select-store')).toEqual([[storeId]]);
  });

  it('selects a trip from the dedicated native route button by keyboard', () => {
    const wrapper = mount(RouteCards, { props: mapProps() });
    const trip = schedule.trips[1];
    const routeButton = wrapper.get(`[data-testid="route-select"][data-trip-id="${trip.id}"]`);

    expect(routeButton.element.tagName).toBe('BUTTON');
    activateNativeButton(routeButton.element, 'Enter');
    activateNativeButton(routeButton.element, ' ');

    expect(wrapper.emitted('select-trip')).toEqual([[trip.id], [trip.id]]);
  });

  it('emits only store selection when a store chip is activated with Enter or Space', () => {
    const wrapper = mount(RouteCards, { props: mapProps() });
    const storeId = schedule.trips[0].storeIds[1];
    const storeButton = wrapper.get(`[data-testid="route-store"][data-store-id="${storeId}"]`);

    expect(storeButton.element.tagName).toBe('BUTTON');
    activateNativeButton(storeButton.element, 'Enter');
    activateNativeButton(storeButton.element, ' ');

    expect(wrapper.emitted('select-store')).toEqual([[storeId], [storeId]]);
    expect(wrapper.emitted('select-trip')).toBeUndefined();
  });
});

describe('StoreDetailDrawer', () => {
  it('shows the selected store pieces, boxes, weight, volume, address, and window', () => {
    const store = MOCK_STORES[6];
    const wrapper = mount(StoreDetailDrawer, {
      props: { stores: MOCK_STORES, selectedStoreId: store.id },
    });

    expect(wrapper.get('[data-testid="store-detail-drawer"]').text()).toContain(store.name);
    expect(wrapper.get('[data-testid="store-pieces"]').text()).toContain(String(store.pieces));
    expect(wrapper.get('[data-testid="store-boxes"]').text()).toContain(String(store.boxes));
    expect(wrapper.get('[data-testid="store-weight"]').text()).toContain(`${store.weightKg} kg`);
    expect(wrapper.get('[data-testid="store-volume"]').text()).toContain(`${store.volumeCbm} m³`);
    expect(wrapper.get('[data-testid="store-address"]').text()).toContain(store.address);
    expect(wrapper.get('[data-testid="store-window"]').text()).toContain(store.window);
  });

  it('clears the selected store through the exact select-store event', async () => {
    const wrapper = mount(StoreDetailDrawer, {
      props: { stores: MOCK_STORES, selectedStoreId: MOCK_STORES[0].id },
    });

    await wrapper.get('[data-testid="close-store-drawer"]').trigger('click');

    expect(wrapper.emitted('select-store')).toEqual([[null]]);
  });

  it('closes through the scrim as well as the close button', async () => {
    const wrapper = mount(StoreDetailDrawer, {
      props: { stores: MOCK_STORES, selectedStoreId: MOCK_STORES[0].id },
    });

    await wrapper.get('[data-testid="drawer-scrim"]').trigger('click');
    await wrapper.get('[data-testid="close-store-drawer"]').trigger('click');

    expect(wrapper.emitted('select-store')).toEqual([[null], [null]]);
  });
});
