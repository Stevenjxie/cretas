<script setup lang="ts">
import { computed } from 'vue';
import mapImage from '@/assets/logistics/suzhou-logistics-map.png';
import { DEPOT_POINT } from '../mockData';
import type { MapPoint, RouteTrip, StoreOrder } from '../types';

const props = defineProps<{
  stores: StoreOrder[];
  trips: RouteTrip[];
  selectedTripId: string | null;
  selectedStoreId: string | null;
}>();

const emit = defineEmits<{
  (event: 'select-trip', tripId: string): void;
  (event: 'select-store', storeId: string): void;
}>();

const routeColors = ['#1B65A8', '#7C3AED', '#C2410C', '#047857', '#BE185D', '#0E7490'];

const storesById = computed(() => new Map(props.stores.map((store) => [store.id, store])));
const selectedStops = computed(() => {
  const selectedTrip = props.trips.find((trip) => trip.id === props.selectedTripId);
  if (!selectedTrip) return [];
  return selectedTrip.storeIds
    .map((storeId) => storesById.value.get(storeId))
    .filter((store): store is StoreOrder => Boolean(store));
});

function points(geometry: MapPoint[]): string {
  return geometry.map(({ x, y }) => `${x},${y}`).join(' ');
}

function hasRouteGeometry(trip: RouteTrip): boolean {
  return trip.geometry.length >= 2;
}

function routeStyle(index: number): Record<string, string> {
  return { '--route-color': routeColors[index % routeColors.length] };
}

function compactStoreName(name: string): string {
  return name.replace('配送门店 ', '门店 ');
}
</script>

<template>
  <div class="map-stage" style="aspect-ratio: 1917 / 1165">
    <img
      data-testid="base-map"
      class="base-map"
      :src="mapImage"
      alt=""
      aria-hidden="true"
    >
    <svg
      data-testid="map-image"
      class="map-overlay"
      viewBox="0 0 1917 1165"
      preserveAspectRatio="xMidYMid meet"
      role="group"
      aria-label="配送路线与门店"
    >
      <g class="route-layer">
        <template v-for="(trip, index) in trips" :key="trip.id">
          <template v-if="hasRouteGeometry(trip)">
            <polyline
              :class="['route-casing', { selected: trip.id === selectedTripId }]"
              :style="routeStyle(index)"
              :points="points(trip.geometry)"
            />
            <polyline
              data-testid="route-path"
              :data-trip-id="trip.id"
              :class="['route-line', { selected: trip.id === selectedTripId }]"
              :style="routeStyle(index)"
              :points="points(trip.geometry)"
              role="button"
              tabindex="0"
              :aria-label="`选择线路 ${index + 1}`"
              @click.stop="emit('select-trip', trip.id)"
              @keydown.enter.prevent="emit('select-trip', trip.id)"
              @keydown.space.prevent="emit('select-trip', trip.id)"
            />
          </template>
        </template>
      </g>

      <g class="store-layer">
        <g class="depot-anchor" :transform="`translate(${DEPOT_POINT.x} ${DEPOT_POINT.y})`">
          <circle class="depot-halo" r="22" />
          <circle class="depot-dot" r="12" />
          <rect class="anchor-label-bg" x="18" y="-18" width="112" height="36" rx="10" />
          <text class="depot-label" x="30" y="6">配送中心</text>
        </g>

        <g
          v-for="store in stores"
          :key="store.id"
          data-testid="store-anchor"
          :data-store-id="store.id"
          :class="['store-anchor', { selected: store.id === selectedStoreId }]"
          :transform="`translate(${store.mapAnchor.x} ${store.mapAnchor.y})`"
          role="button"
          tabindex="0"
          :aria-label="`查看${store.name}`"
          @click.stop="emit('select-store', store.id)"
          @keydown.enter.prevent="emit('select-store', store.id)"
          @keydown.space.prevent="emit('select-store', store.id)"
        >
          <circle class="store-halo" r="20" />
          <circle class="store-dot" r="10" />
          <rect class="anchor-label-bg" x="16" y="-17" width="108" height="34" rx="10" />
          <text class="store-label" x="27" y="6">{{ compactStoreName(store.name) }}</text>
        </g>
      </g>

      <g class="sequence-layer">
        <g
          v-for="(store, index) in selectedStops"
          :key="store.id"
          data-testid="selected-stop-number"
          :data-store-id="store.id"
          class="sequence-badge"
          :transform="`translate(${store.mapAnchor.x} ${store.mapAnchor.y})`"
          aria-hidden="true"
        >
          <circle r="18" />
          <text y="1">{{ index + 1 }}</text>
        </g>
      </g>
    </svg>
  </div>
</template>

<style scoped lang="scss">
.map-stage {
  position: relative;
  width: 100%;
  overflow: hidden;
  background: #f4f6f9;
  border: 1px solid #edf2f7;
  border-radius: 10px;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.06);
}

.base-map,
.map-overlay {
  position: absolute;
  inset: 0;
  display: block;
  width: 100%;
  height: 100%;
}

.base-map {
  object-fit: contain;
}

.route-casing,
.route-line {
  fill: none;
  stroke-linecap: round;
  stroke-linejoin: round;
  vector-effect: non-scaling-stroke;
}

.route-casing {
  stroke: rgba(255, 255, 255, 0.96);
  stroke-width: 10;
  opacity: 0.84;
  pointer-events: none;

  &.selected {
    stroke-width: 14;
    opacity: 1;
  }
}

.route-line {
  stroke: var(--route-color);
  stroke-width: 5;
  opacity: 0.66;
  cursor: pointer;
  transition: stroke-width 0.2s ease, opacity 0.2s ease;

  &:hover,
  &:focus-visible {
    opacity: 0.86;
    outline: none;
  }

  &.selected {
    stroke-width: 9;
    opacity: 1;
  }
}

.anchor-label-bg {
  fill: rgba(255, 255, 255, 0.94);
  stroke: #edf2f7;
  stroke-width: 2;
  vector-effect: non-scaling-stroke;
}

.depot-halo,
.store-halo {
  fill: rgba(255, 255, 255, 0.92);
  stroke: rgba(27, 101, 168, 0.18);
  stroke-width: 2;
  vector-effect: non-scaling-stroke;
}

.depot-dot {
  fill: #1a2332;
  stroke: #ffffff;
  stroke-width: 3;
  vector-effect: non-scaling-stroke;
}

.store-anchor {
  cursor: pointer;

  &:focus-visible {
    outline: none;
  }

  &.selected .store-halo,
  &:hover .store-halo,
  &:focus-visible .store-halo {
    fill: rgba(27, 101, 168, 0.18);
    stroke: #1b65a8;
  }
}

.store-dot {
  fill: #1b65a8;
  stroke: #ffffff;
  stroke-width: 3;
  vector-effect: non-scaling-stroke;
}

.depot-label,
.store-label {
  fill: #101828;
  font-size: 22px;
  font-weight: 700;
  pointer-events: none;
}

.sequence-badge {
  pointer-events: none;

  circle {
    fill: #1b65a8;
    stroke: #ffffff;
    stroke-width: 4;
    vector-effect: non-scaling-stroke;
  }

  text {
    fill: #ffffff;
    font-size: 20px;
    font-weight: 800;
    text-anchor: middle;
    dominant-baseline: middle;
    pointer-events: none;
  }

}

@media (max-width: 1440px) {
  .depot-label,
  .store-label {
    font-size: 24px;
  }
}
</style>
