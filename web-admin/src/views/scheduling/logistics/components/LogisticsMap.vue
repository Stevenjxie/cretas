<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import mapImage from '@/assets/logistics/suzhou-logistics-map.png';
import { DEPOT_POINT } from '../mockData';
import type { MapPoint, RouteTrip, StoreOrder } from '../types';
import { getAmapKey, loadAmap, type AMapInstance, type AMapNamespace, type AMapOverlay } from '../amapLoader';

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

// 配送中心真实经纬度（与后端 logistics.depot.lng/lat 默认一致）。
const DEPOT_LNGLAT: [number, number] = [120.62, 31.3];

// ============================================================
// 高德真实地图（有 VITE_AMAP_JS_KEY 时启用；加载失败回落下方 SVG 示意图）
// ============================================================
const useAmap = ref(Boolean(getAmapKey()));
const mapEl = ref<HTMLDivElement>();
let amap: AMapNamespace | null = null;
let map: AMapInstance | null = null;
let overlays: AMapOverlay[] = [];

function compactStoreName(name: string): string {
  return name.replace('配送门店 ', '门店 ');
}

function esc(s: string): string {
  return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c] as string));
}

function depotContent(): string {
  return `<div class="amap-depot"><span class="amap-depot-dot"></span><span class="amap-lbl">配送中心</span></div>`;
}

function storeContent(store: StoreOrder, seq: number | undefined, selected: boolean): string {
  const badge = seq != null ? `<span class="amap-seq">${seq}</span>` : '<span class="amap-store-dot"></span>';
  return `<div class="amap-store${selected ? ' selected' : ''}">${badge}<span class="amap-lbl">${esc(compactStoreName(store.name))}</span></div>`;
}

function clearOverlays(): void {
  if (map && overlays.length) {
    map.remove(overlays);
  }
  overlays = [];
}

function renderOverlays(): void {
  if (!map || !amap) return;
  clearOverlays();

  const AMapRef = amap;
  const seqByStore = new Map<string, number>();
  const selectedTrip = props.trips.find((t) => t.id === props.selectedTripId);
  if (selectedTrip) {
    selectedTrip.storeIds.forEach((sid, i) => seqByStore.set(sid, i + 1));
  }
  const storeById = new Map(props.stores.map((s) => [s.id, s]));

  // 路线 polyline：配送中心 → 各门店（按 storeIds 顺序）
  props.trips.forEach((trip, index) => {
    const path: [number, number][] = [DEPOT_LNGLAT];
    trip.storeIds.forEach((sid) => {
      const s = storeById.get(sid);
      if (s && Number.isFinite(s.lng) && Number.isFinite(s.lat)) {
        path.push([s.lng, s.lat]);
      }
    });
    if (path.length < 2) return;
    const selected = trip.id === props.selectedTripId;
    const line = new AMapRef.Polyline({
      path,
      strokeColor: routeColors[index % routeColors.length],
      strokeWeight: selected ? 7 : 4,
      strokeOpacity: selected ? 1 : 0.55,
      lineJoin: 'round',
      lineCap: 'round',
      cursor: 'pointer',
      zIndex: selected ? 90 : 50,
    });
    line.on('click', () => emit('select-trip', trip.id));
    overlays.push(line);
  });

  // 配送中心 marker
  const depot = new AMapRef.Marker({
    position: DEPOT_LNGLAT,
    content: depotContent(),
    offset: new AMapRef.Pixel(-9, -9),
    zIndex: 200,
  });
  overlays.push(depot);

  // 门店 marker
  props.stores.forEach((store) => {
    if (!Number.isFinite(store.lng) || !Number.isFinite(store.lat)) return;
    const marker = new AMapRef.Marker({
      position: [store.lng, store.lat],
      content: storeContent(store, seqByStore.get(store.id), store.id === props.selectedStoreId),
      offset: new AMapRef.Pixel(-9, -9),
      zIndex: store.id === props.selectedStoreId ? 160 : 120,
    });
    marker.on('click', () => emit('select-store', store.id));
    overlays.push(marker);
  });

  map.add(overlays);
  map.setFitView(overlays, false, [48, 48, 48, 48]);
}

onMounted(async () => {
  if (!useAmap.value || !mapEl.value) return;
  try {
    amap = await loadAmap();
    map = new amap.Map(mapEl.value, {
      zoom: 11,
      center: DEPOT_LNGLAT,
      viewMode: '2D',
      lang: 'zh_cn',
    });
    renderOverlays();
  } catch (e) {
    // 无 key / 域名未白名单 / 网络失败 → 诚实回落 SVG 示意图，不阻塞工作台
    // eslint-disable-next-line no-console
    console.warn('[LogisticsMap] 高德地图加载失败，回落示意图：', e);
    useAmap.value = false;
    amap = null;
    map = null;
  }
});

watch(
  () => [props.stores, props.trips, props.selectedTripId, props.selectedStoreId],
  () => {
    if (map) renderOverlays();
  },
  { deep: true },
);

onBeforeUnmount(() => {
  clearOverlays();
  if (map) {
    map.destroy();
    map = null;
  }
});

// ============================================================
// SVG 示意图 fallback（测试环境 / 无 key / 加载失败）
// ============================================================
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
</script>

<template>
  <div class="map-stage" :style="useAmap ? 'aspect-ratio: 16 / 10' : 'aspect-ratio: 1917 / 1165'">
    <div v-if="useAmap" ref="mapEl" data-testid="amap-el" class="amap-el" />

    <template v-else>
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
    </template>
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

.amap-el {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
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

<!-- 高德 marker content 是脱离 scoped 作用域的独立 DOM，样式必须全局 -->
<style lang="scss">
.amap-depot,
.amap-store {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
  font-size: 12px;
  font-weight: 700;
  color: #101828;
}

.amap-depot .amap-lbl,
.amap-store .amap-lbl {
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid #edf2f7;
  border-radius: 8px;
  padding: 1px 6px;
  box-shadow: 0 1px 4px rgba(27, 101, 168, 0.14);
}

.amap-depot-dot,
.amap-store-dot,
.amap-seq {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 3px solid #ffffff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.25);
  flex: 0 0 auto;
}

.amap-depot-dot {
  background: #1a2332;
}

.amap-store-dot {
  background: #1b65a8;
}

.amap-seq {
  background: #1b65a8;
  color: #ffffff;
  font-size: 11px;
  font-weight: 800;
}

.amap-store.selected .amap-store-dot,
.amap-store.selected .amap-seq {
  outline: 2px solid #1b65a8;
  outline-offset: 1px;
}

.amap-store.selected .amap-lbl {
  border-color: #1b65a8;
  color: #1b65a8;
}
</style>
