<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import mapImage from '@/assets/logistics/suzhou-logistics-map.png';
import { DEPOT_POINT } from '../mockData';
import type { MapPoint, RouteTrip, StoreOrder } from '../types';
import {
  ensureDriving,
  getAmapKey,
  loadAmap,
  type AMapInstance,
  type AMapLngLat,
  type AMapNamespace,
  type AMapOverlay,
} from '../amapLoader';

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

// 配送中心真实经纬度（一加物流仓库：苏州相城区望亭海亭路197号沐井供应链；与后端 logistics.depot.lng/lat 一致）。
const DEPOT_LNGLAT: [number, number] = [120.476894, 31.437014];

// 「显示全部线路」开关（客户要求）：默认单条线路突出、其余淡化；开启后所有线路满不透明度同时展示。
const showAllRoutes = ref(false);
function toggleAllRoutes(): void {
  showAllRoutes.value = !showAllRoutes.value;
  if (map) renderOverlays();
}

// ============================================================
// 高德真实地图（有 VITE_AMAP_JS_KEY 时启用；加载失败回落下方 SVG 示意图）
// ============================================================
const useAmap = ref(Boolean(getAmapKey()));
// 底图瓦片就绪前显示「地图加载中…」遮罩，避免展示空白网格看着像坏了。
const mapReady = ref(false);
const mapEl = ref<HTMLDivElement>();
let amap: AMapNamespace | null = null;
let map: AMapInstance | null = null;
let overlays: AMapOverlay[] = [];
let ro: ResizeObserver | null = null;
let readyTimer: number | undefined;
// 每次重绘自增；异步驾车路径回调用它判断自己是否已过期（避免旧回调把线画到新一轮上）。
let renderToken = 0;

function compactStoreName(name: string): string {
  return name.replace('配送门店 ', '门店 ');
}

function esc(s: string): string {
  return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c] as string));
}

function depotContent(): string {
  return `<div class="amap-depot"><span class="amap-depot-dot"></span><span class="amap-lbl">配送中心</span></div>`;
}

function storeContent(store: StoreOrder, seq: number | undefined, selected: boolean, labeled: boolean): string {
  const badge = seq != null ? `<span class="amap-seq">${seq}</span>` : '<span class="amap-store-dot"></span>';
  // labeled=false（非选中线路的门店）：只显小灰点，不显名字标签，避免全图挤满店名
  const label = labeled ? `<span class="amap-lbl">${esc(compactStoreName(store.name))}</span>` : '';
  return `<div class="amap-store${selected ? ' selected' : ''}${labeled ? '' : ' dim'}">${badge}${label}</div>`;
}

function clearOverlays(): void {
  if (map && overlays.length) {
    map.remove(overlays);
  }
  overlays = [];
}

function renderOverlays(): void {
  if (!map || !amap) return;
  const AMapRef = amap;
  clearOverlays();
  const token = ++renderToken; // 本轮标识；异步驾车回调据此判断是否过期

  const seqByStore = new Map<string, number>();
  const selectedTrip = props.trips.find((t) => t.id === props.selectedTripId);
  if (showAllRoutes.value) {
    // 显示全部线路时：每条线路的门店都编号(各自线路内 1..n)，让所有送达点都可见(客户要求)
    props.trips.forEach((t) => t.storeIds.forEach((sid, i) => seqByStore.set(sid, i + 1)));
  } else if (selectedTrip) {
    selectedTrip.storeIds.forEach((sid, i) => seqByStore.set(sid, i + 1));
  }
  const storeById = new Map(props.stores.map((s) => [s.id, s]));

  // 配送中心 marker
  const depot = new AMapRef.Marker({
    position: DEPOT_LNGLAT,
    content: depotContent(),
    offset: new AMapRef.Pixel(-9, -9),
    zIndex: 200,
  });
  overlays.push(depot);

  // 门店 marker —— 只有「选中线路的门店」或「被点选的门店」才显示名字标签，其余缩成小灰点，避免全图挤满店名
  const selectedStoreSet = new Set(selectedTrip?.storeIds ?? []);
  props.stores.forEach((store) => {
    if (!Number.isFinite(store.lng) || !Number.isFinite(store.lat)) return;
    const labeled = showAllRoutes.value || selectedStoreSet.has(store.id) || store.id === props.selectedStoreId;
    const marker = new AMapRef.Marker({
      position: [store.lng, store.lat],
      content: storeContent(store, seqByStore.get(store.id), store.id === props.selectedStoreId, labeled),
      offset: new AMapRef.Pixel(-9, -9),
      zIndex: labeled ? 160 : 90,
    });
    marker.on('click', () => emit('select-store', store.id));
    overlays.push(marker);
  });

  map.add(overlays);
  map.setFitView(overlays, false, [48, 48, 48, 48]);

  // 路线：逐车次用高德驾车路径规划画「沿实际道路」的线（异步，失败诚实回落虚线直线）
  props.trips.forEach((trip, index) => {
    const storePts: [number, number][] = [];
    trip.storeIds.forEach((sid) => {
      const s = storeById.get(sid);
      if (s && Number.isFinite(s.lng) && Number.isFinite(s.lat)) {
        storePts.push([s.lng, s.lat]);
      }
    });
    if (!storePts.length) return;
    drawTripRoute(trip.id, index, storePts, token, trip.roadPath);
  });
}

/**
 * 单条车次路线：
 * 1) 后端存好的 roadPath(GCJ-02 沿路 polyline) → 直接画，不调地图 API(缓存命中，回看零调用)。
 * 2) 否则实时调高德驾车路径规划(旧路径)；未就绪/失败 → 虚线直线兜底(诚实标示非实际道路)。
 */
function drawTripRoute(
  tripId: string,
  index: number,
  storePts: [number, number][],
  token: number,
  roadPath?: Array<{ lng: number; lat: number }>,
): void {
  if (!map || !amap) return;
  const AMapRef = amap;
  const selected = tripId === props.selectedTripId;
  const anySelected = props.selectedTripId != null;
  const color = routeColors[index % routeColors.length];

  const addLine = (path: [number, number][] | AMapLngLat[], dashed: boolean): void => {
    if (token !== renderToken || !map) return; // 已被新一轮重绘取代，丢弃
    // 有选中线路时，非选中线路淡化让选中的一目了然
    const showAll = showAllRoutes.value;
    const line = new AMapRef.Polyline({
      path,
      strokeColor: color,
      // 显示全部时：所有线路都清晰(选中略粗)；否则保持「选中突出、其余淡化」。
      strokeWeight: showAll ? (selected ? 6 : 4) : selected ? 7 : anySelected ? 3 : 4,
      strokeOpacity: showAll ? (selected ? 1 : 0.82) : selected ? 1 : anySelected ? 0.22 : 0.6,
      strokeStyle: dashed ? 'dashed' : 'solid',
      showDir: selected, // 选中线路画行驶方向箭头（配送中心→①→②→…），一眼看清先后与方向
      lineJoin: 'round',
      lineCap: 'round',
      cursor: 'pointer',
      zIndex: selected ? 90 : 50,
    });
    line.on('click', () => emit('select-trip', tripId));
    overlays.push(line);
    map.add([line]);
  };

  // 缓存命中（常态）：后端已存沿路 roadPath → 直接画，零地图 API 调用。
  if (roadPath && roadPath.length >= 2) {
    addLine(roadPath.map((p) => [p.lng, p.lat] as [number, number]), false);
    return;
  }

  // roadPath 缺失（极少：后端已可靠为每条车次持久化 roadPath；仅 NEEDS_ROUTE_DATA 等异常态才没有）。
  // 驾车插件由 onMounted 后台懒加载（不阻塞首屏）：已就绪则实时规划画沿路线，未就绪则先画诚实虚线直线。
  const straight: [number, number][] = [DEPOT_LNGLAT, ...storePts];
  if (!(AMapRef as { Driving?: unknown }).Driving) {
    addLine(straight, true);
    return;
  }
  const driving = new AMapRef.Driving({
    policy: (AMapRef.DrivingPolicy && AMapRef.DrivingPolicy.LEAST_DISTANCE) || 0,
  });
  const origin = new AMapRef.LngLat(DEPOT_LNGLAT[0], DEPOT_LNGLAT[1]);
  const last = storePts[storePts.length - 1];
  const dest = new AMapRef.LngLat(last[0], last[1]);
  const waypoints = storePts.slice(0, -1).map(([lng, lat]) => new AMapRef.LngLat(lng, lat));
  driving.search(origin, dest, { waypoints }, (status, result) => {
    if (token !== renderToken) return; // 过期回调丢弃
    const steps = result?.routes?.[0]?.steps;
    if (status === 'complete' && steps && steps.length) {
      const path: AMapLngLat[] = [];
      steps.forEach((st) => { if (st.path) path.push(...st.path); });
      if (path.length >= 2) { addLine(path, false); return; }
    }
    addLine(straight, true); // 驾车规划失败 → 诚实回落虚线直线
  });
}

// 首次 loadAmap 失败(瞬时网络抖动 / 快速刷新打断脚本请求)时等一下重试一次, 再决定回落 SVG,
// 避免偶发失败就永久掉到示意图(loadAmap 内部失败已置空缓存, 可重试)。
async function loadAmapWithRetry(): Promise<AMapNamespace> {
  try {
    return await loadAmap();
  } catch {
    await new Promise((r) => setTimeout(r, 600));
    return await loadAmap();
  }
}

onMounted(async () => {
  if (!useAmap.value || !mapEl.value) return;
  try {
    amap = await loadAmapWithRetry();
    map = new amap.Map(mapEl.value, {
      zoom: 11,
      center: DEPOT_LNGLAT,
      viewMode: '2D',
      lang: 'zh_cn',
      resizeEnable: true,
    });
    // 瓦片渲染完成 → 撤 loading 遮罩。
    map.on('complete', () => { mapReady.value = true; });
    // 容器尺寸变化（两栏 flex 布局在挂载后才定高 / 视图切换）后强制 resize，
    // 修复「构造时容器高度为 0 → 瓦片一直不画 → 白色网格」的经典高德 bug。
    ro = new ResizeObserver(() => { if (map) map.resize(); });
    ro.observe(mapEl.value);
    // 兜底：瓦片慢或 complete 未触发时，2.5s 后也撤遮罩，先露出(可能部分加载的)真实地图，
    // 别让加载遮罩一直挡着显得卡。
    readyTimer = window.setTimeout(() => { mapReady.value = true; }, 2500);
    renderOverlays();
    // 基础地图已出来后，后台懒加载驾车规划插件（不阻塞首屏）——供极少数缺 roadPath 车次实时补线兜底。
    void ensureDriving().then((ok) => { if (ok && map) renderOverlays(); });
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
  if (ro) { ro.disconnect(); ro = null; }
  if (readyTimer) { window.clearTimeout(readyTimer); readyTimer = undefined; }
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
    <template v-if="useAmap">
      <div ref="mapEl" data-testid="amap-el" class="amap-el" />
      <div v-if="!mapReady" class="amap-loading" data-testid="amap-loading" aria-hidden="true">
        <span class="amap-spinner" />
        <span class="amap-loading-txt">地图加载中…</span>
      </div>
      <!-- 显示全部线路开关（客户要求）：一键在「单条突出」与「全部同时展示」间切换 -->
      <button
        v-if="mapReady && props.trips.length > 1"
        type="button"
        class="map-allroutes-btn"
        :class="{ active: showAllRoutes }"
        data-testid="map-show-all-routes"
        @click="toggleAllRoutes"
      >{{ showAllRoutes ? '✓ 全部线路' : '显示全部线路' }}</button>
    </template>

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

/* 显示全部线路开关（客户要求）—— 浮在地图右上角 */
.map-allroutes-btn {
  position: absolute;
  top: 12px;
  right: 12px;
  z-index: 210;
  padding: 6px 12px;
  font-size: 13px;
  font-weight: 650;
  color: #1b65a8;
  background: rgba(255, 255, 255, 0.95);
  border: 1px solid #cfe0f2;
  border-radius: 8px;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(27, 101, 168, 0.14);
  transition: all 0.15s;
}
.map-allroutes-btn:hover { background: #fff; border-color: #1b65a8; }
.map-allroutes-btn.active { color: #fff; background: #1b65a8; border-color: #1b65a8; }

.amap-loading {
  position: absolute;
  inset: 0;
  z-index: 5;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  background: #f4f6f9;
  color: #667085;
  font-size: 13px;
  font-weight: 600;
}

.amap-spinner {
  width: 26px;
  height: 26px;
  border: 3px solid rgba(27, 101, 168, 0.2);
  border-top-color: #1b65a8;
  border-radius: 50%;
  animation: amap-spin 0.8s linear infinite;
}

@keyframes amap-spin {
  to { transform: rotate(360deg); }
}

@media (prefers-reduced-motion: reduce) {
  .amap-spinner { animation-duration: 1.6s; }
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

/* 非选中线路的门店：无名字标签，小灰点，弱化避免全图混乱 */
.amap-store.dim .amap-store-dot {
  width: 11px;
  height: 11px;
  background: #94a3b8;
  border-width: 2px;
  opacity: 0.85;
}
</style>
