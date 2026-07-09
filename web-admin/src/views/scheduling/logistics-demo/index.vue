<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import {
  Aim,
  ArrowDown,
  ArrowUp,
  Box,
  Download,
  Finished,
  Guide,
  Location,
  MapLocation,
  OfficeBuilding,
  Refresh,
  Upload,
  Van,
  Warning,
} from '@element-plus/icons-vue';

type Provider = 'amap' | 'tencent' | 'baidu';
type RouteStatus = 'draft' | 'locked' | 'risk';

interface StoreOrder {
  id: string;
  name: string;
  address: string;
  area: string;
  boxes: number;
  pieces: number;
  weightKg: number;
  volumeCbm: number;
  x: number;
  y: number;
  window: string;
}

interface Vehicle {
  id: string;
  plate: string;
  driver: string;
  backupDrivers: string;
  vehicleBody: string;
  capacityCbm: number;
  maxWeightKg: number;
  area: string;
  shift: string;
  source: '自有' | '外包';
}

interface RoutePlan {
  id: string;
  name: string;
  vehicleId: string;
  storeIds: string[];
  distanceKm: number;
  status: RouteStatus;
  note: string;
}

const providers: Array<{ key: Provider; label: string; status: string }> = [
  { key: 'amap', label: '高德地图', status: '可接入路径规划 / 地理编码' },
  { key: 'tencent', label: '腾讯地图', status: '可接入路线矩阵 / 逆地址' },
  { key: 'baidu', label: '百度地图', status: '可接入坐标转换 / 路线服务' },
];

const activeProvider = ref<Provider>('amap');
const imported = ref(false);
const generated = ref(false);
const activeStoreId = ref('S-001');
const activeRouteId = ref('R-01');
const targetLoad = ref(88);

const AREA_TO_VEHICLE: Record<string, string> = {
  相城: 'V-01',
  姑苏: 'V-01',
  园区: 'V-02',
  吴中: 'V-02',
  高新: 'V-03',
  虎丘: 'V-03',
  吴江: 'V-04',
  昆山: 'V-04',
};

const stores = ref<StoreOrder[]>([
  { id: 'S-001', name: '渝八两苏州相城东桥店', address: '相城区东桥镇长平路', area: '相城', boxes: 18, pieces: 126, weightKg: 540, volumeCbm: 1.8, x: 40, y: 27, window: '09:30-12:00' },
  { id: 'S-002', name: 'No.1606江苏苏州虎丘浅湾商业中心店', address: '虎丘区浅湾商业中心', area: '虎丘', boxes: 28, pieces: 210, weightKg: 830, volumeCbm: 2.7, x: 48, y: 42, window: '10:00-13:30' },
  { id: 'S-003', name: '渝八两苏州繁花中心店', address: '姑苏区人民路繁花中心', area: '姑苏', boxes: 22, pieces: 158, weightKg: 690, volumeCbm: 2.3, x: 56, y: 43, window: '10:30-14:00' },
  { id: 'S-004', name: 'No.1439江苏苏州姑苏区印巷美食城店', address: '姑苏区印巷美食城', area: '姑苏', boxes: 16, pieces: 120, weightKg: 480, volumeCbm: 1.6, x: 53, y: 51, window: '11:00-15:00' },
  { id: 'S-005', name: '苏州市吴中区浦田打工楼店', address: '吴中区浦田路', area: '吴中', boxes: 20, pieces: 145, weightKg: 620, volumeCbm: 2.1, x: 78, y: 41, window: '13:00-16:00' },
  { id: 'S-006', name: '苏州唯亭店', address: '工业园区唯亭镇', area: '园区', boxes: 15, pieces: 98, weightKg: 410, volumeCbm: 1.4, x: 69, y: 48, window: '13:30-17:00' },
  { id: 'S-007', name: 'No.5554苏州新光天地店', address: '工业园区新光天地', area: '园区', boxes: 30, pieces: 235, weightKg: 920, volumeCbm: 3.2, x: 64, y: 56, window: '14:00-17:30' },
  { id: 'S-008', name: 'No.5518江苏苏州双湖广场店', address: '吴中区双湖广场', area: '吴中', boxes: 17, pieces: 134, weightKg: 510, volumeCbm: 1.7, x: 48, y: 63, window: '15:00-18:00' },
  { id: 'S-009', name: '苏州高新区港龙城商场店', address: '高新区港龙城', area: '高新', boxes: 24, pieces: 176, weightKg: 720, volumeCbm: 2.4, x: 26, y: 58, window: '09:00-12:00' },
  { id: 'S-010', name: '苏州高新区金鹰商业广场店', address: '高新区金鹰商业广场', area: '高新', boxes: 21, pieces: 161, weightKg: 640, volumeCbm: 2.2, x: 35, y: 67, window: '10:00-13:00' },
  { id: 'S-011', name: 'No.4108苏州吴中浦街店', address: '吴中区浦街', area: '吴中', boxes: 13, pieces: 86, weightKg: 360, volumeCbm: 1.2, x: 32, y: 84, window: '15:00-19:00' },
  { id: 'S-012', name: 'No.4219江苏苏州同里古镇店', address: '吴江区同里古镇', area: '吴江', boxes: 26, pieces: 190, weightKg: 810, volumeCbm: 2.8, x: 66, y: 83, window: '16:00-20:00' },
  { id: 'S-013', name: 'No.4477苏州吴中越溪街道店', address: '吴中区越溪街道', area: '吴中', boxes: 22, pieces: 168, weightKg: 700, volumeCbm: 2.0, x: 44, y: 74, window: '16:30-20:00' },
]);

const vehicles = ref<Vehicle[]>([
  { id: 'V-01', plate: '苏E·31L8P', driver: '赵明', backupDrivers: '夜班: 周师傅', vehicleBody: '双温车', capacityCbm: 10, maxWeightKg: 3200, area: '姑苏 / 相城', shift: '08:00-18:00', source: '自有' },
  { id: 'V-02', plate: '苏E·7K92F', driver: '李蓉', backupDrivers: '可调: 王磊', vehicleBody: '双温车', capacityCbm: 10, maxWeightKg: 3200, area: '园区 / 吴中', shift: '09:00-20:00', source: '自有' },
  { id: 'V-03', plate: '苏U·6Q28A', driver: '王磊', backupDrivers: '备用: 赵明', vehicleBody: '双温车', capacityCbm: 10, maxWeightKg: 3000, area: '高新 / 虎丘', shift: '08:30-18:30', source: '自有' },
  { id: 'V-04', plate: '外协·苏E83Q', driver: '顺达物流 / 陈师傅', backupDrivers: '外协调度确认', vehicleBody: '双温车', capacityCbm: 12, maxWeightKg: 3800, area: '吴江 / 昆山', shift: '按单确认', source: '外包' },
]);

const routeTemplates = [
  { id: 'R-01', vehicleId: 'V-01' },
  { id: 'R-02', vehicleId: 'V-02' },
  { id: 'R-03', vehicleId: 'V-03' },
  { id: 'R-04', vehicleId: 'V-04' },
];

const routes = ref<RoutePlan[]>(buildAreaRoutes());

const activeStore = computed(() => stores.value.find((store) => store.id === activeStoreId.value) ?? stores.value[0]);
const activeRoute = computed(() => routes.value.find((route) => route.id === activeRouteId.value) ?? routes.value[0]);
const activeVehicle = computed(() => vehicleById(activeRoute.value.vehicleId));
const routeStores = computed(() => activeRoute.value.storeIds.map(storeById).filter(Boolean) as StoreOrder[]);
const activeProviderLabel = computed(() => providers.find((provider) => provider.key === activeProvider.value)?.label ?? '高德地图');
const routeStoreSet = computed(() => new Set(activeRoute.value.storeIds));

const totalVolume = computed(() => stores.value.reduce((sum, store) => sum + store.volumeCbm, 0));
const totalWeight = computed(() => stores.value.reduce((sum, store) => sum + store.weightKg, 0));
const totalDistance = computed(() => routes.value.reduce((sum, route) => sum + route.distanceKm, 0));
const neededVehicles = computed(() => routes.value.length);
const activeRouteLoad = computed(() => routeLoad(activeRoute.value));
const activeFittedStores = computed(() => activeRouteLoad.value.fitted.map(storeById).filter(Boolean) as StoreOrder[]);
const activeOverflowStores = computed(() => activeRouteLoad.value.overflow.map(storeById).filter(Boolean) as StoreOrder[]);
const activeFittedVolume = computed(() => activeRouteLoad.value.fittedVolume);
const activeOverflowVolume = computed(() => activeOverflowStores.value.reduce((sum, store) => sum + store.volumeCbm, 0));
const activeFittedWeight = computed(() => activeFittedStores.value.reduce((sum, store) => sum + store.weightKg, 0));
const activeLoadRate = computed(() => Math.round((activeFittedVolume.value / activeVehicle.value.capacityCbm) * 100));
const activeWeightRate = computed(() => Math.round((activeFittedWeight.value / activeVehicle.value.maxWeightKg) * 100));
const activePolyline = computed(() => activeFittedStores.value.map((store) => `${store.x},${store.y}`).join(' '));

const exportRows = computed(() => routes.value.map((route) => {
  const vehicle = vehicleById(route.vehicleId);
  const assignedStores = route.storeIds.map(storeById).filter(Boolean) as StoreOrder[];
  const volume = assignedStores.reduce((sum, store) => sum + store.volumeCbm, 0);
  return {
    line: route.name,
    vehicle: vehicle.plate,
    driver: vehicle.driver,
    order: assignedStores.map((store, index) => `${index + 1}.${store.name}`).join(' -> '),
    stores: assignedStores.length,
    volume: `${volume.toFixed(1)} / ${vehicle.capacityCbm} 方`,
    loadRate: `${Math.round((volume / vehicle.capacityCbm) * 100)}%`,
    distance: `${route.distanceKm.toFixed(1)} km`,
  };
}));

function storeById(id: string): StoreOrder | undefined {
  return stores.value.find((store) => store.id === id);
}

function vehicleById(id: string): Vehicle {
  return vehicles.value.find((vehicle) => vehicle.id === id) ?? vehicles.value[0];
}

function estimateRouteDistance(storeIds: string[]): number {
  const routeStores = storeIds.map(storeById).filter(Boolean) as StoreOrder[];
  if (!routeStores.length) return 0;
  const depot = { x: 52, y: 47 };
  const pointDistance = (left: { x: number; y: number }, right: { x: number; y: number }) => (
    Math.hypot(left.x - right.x, left.y - right.y)
  );
  const legDistance = routeStores.reduce((sum, store, index) => {
    const previous = index === 0 ? depot : routeStores[index - 1];
    return sum + pointDistance(previous, store);
  }, 0);
  const returnDistance = pointDistance(routeStores[routeStores.length - 1], depot) * 0.45;
  return Number(((legDistance + returnDistance) * 1.28 + 12).toFixed(1));
}

function vehicleAreaFallback(vehicle: Vehicle): string {
  return vehicle.area.split('/')[0]?.trim() || vehicle.area;
}

function routeNameFor(vehicle: Vehicle, storeIds: string[]): string {
  const areas = storeIds
    .map(storeById)
    .filter(Boolean)
    .map((store) => (store as StoreOrder).area)
    .filter((area, index, list) => list.indexOf(area) === index);
  if (areas.length >= 2) return `${areas[0]}-${areas[1]}线`;
  if (areas.length === 1) return `${areas[0]}线`;
  return `${vehicleAreaFallback(vehicle)}线`;
}

// 按车辆容量顺序装车：装满 capacityCbm 就不再装，剩余门店自动顺延下一车次。
// 直接对应客户原话「超过10个方，它自动默认一下一辆车不给你装了」。
function routeLoad(route: RoutePlan): { cap: number; fittedVolume: number; fitted: string[]; overflow: string[] } {
  const vehicle = vehicleById(route.vehicleId);
  const cap = vehicle.capacityCbm;
  const fitted: string[] = [];
  const overflow: string[] = [];
  let cum = 0;
  let full = false;
  for (const id of route.storeIds) {
    const store = storeById(id);
    if (!store) continue;
    if (!full && cum + store.volumeCbm <= cap) {
      fitted.push(id);
      cum += store.volumeCbm;
    } else {
      full = true;
      overflow.push(id);
    }
  }
  return { cap, fittedVolume: cum, fitted, overflow };
}

function assessRoute(route: RoutePlan): Pick<RoutePlan, 'status' | 'note'> {
  const vehicle = vehicleById(route.vehicleId);
  const assignedStores = route.storeIds.map(storeById).filter(Boolean) as StoreOrder[];
  if (!assignedStores.length) {
    return { status: 'draft', note: '今日暂无该区域订单' };
  }
  const load = routeLoad(route);
  if (load.overflow.length) {
    return { status: 'risk', note: `超 ${load.cap} 方上限，${load.overflow.length} 家自动分下一车次` };
  }
  if (vehicle.source === '外包') {
    return { status: 'risk', note: '外协车源，建议调度员确认派车' };
  }
  const loadRate = Math.round((load.fittedVolume / vehicle.capacityCbm) * 100);
  if (loadRate < targetLoad.value - 15) {
    return { status: 'draft', note: '装载率偏低，可合并后续订单' };
  }
  return { status: 'draft', note: '按目标装载率预排，可人工调整顺序' };
}

function buildAreaRoutes(): RoutePlan[] {
  return routeTemplates.map((template) => {
    const vehicle = vehicleById(template.vehicleId);
    const storeIds = stores.value
      .filter((store) => AREA_TO_VEHICLE[store.area] === vehicle.id)
      .map((store) => store.id);
    const route: RoutePlan = {
      id: template.id,
      vehicleId: template.vehicleId,
      name: routeNameFor(vehicle, storeIds),
      storeIds,
      distanceKm: estimateRouteDistance(storeIds),
      status: 'draft',
      note: '',
    };
    return { ...route, ...assessRoute(route) };
  });
}

function rebalanceRoutes() {
  routes.value = buildAreaRoutes();
  if (!routes.value.some((route) => route.id === activeRouteId.value)) {
    activeRouteId.value = routes.value[0]?.id ?? 'R-01';
  }
}

function onTargetLoadChange() {
  imported.value = true;
  generated.value = true;
  rebalanceRoutes();
  ElMessage.success(`已按 ${targetLoad.value}% 目标装载率重新预排线路`);
}

function escapeCsv(value: string | number): string {
  const text = String(value);
  if (!/[",\r\n]/.test(text)) return text;
  return `"${text.replace(/"/g, '""')}"`;
}

function downloadCsv(filename: string, rows: Array<Array<string | number>>) {
  const csv = rows.map((row) => row.map(escapeCsv).join(',')).join('\r\n');
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}

function downloadImportTemplate() {
  downloadCsv('一加物流订单导入模板.csv', [
    ['门店名称', '门店地址', '区域', '件数', '箱数', '重量kg', '体积方', '配送时间窗', '经度', '纬度'],
    ['渝八两苏州相城东桥店', '相城区东桥镇长平路', '相城', 126, 18, 540, 1.8, '09:30-12:00', '后续由客户提供或地图解析', '后续由客户提供或地图解析'],
    ['No.1606江苏苏州虎丘浅湾商业中心店', '虎丘区浅湾商业中心', '虎丘', 210, 28, 830, 2.7, '10:00-13:30', '后续由客户提供或地图解析', '后续由客户提供或地图解析'],
  ]);
  ElMessage.success('已下载订单导入模板样例');
}

function importOrders() {
  imported.value = true;
  activeStoreId.value = stores.value[0].id;
  ElMessage.success('已导入今日门店订单模板，地图点位已生成');
}

function generateRoutes() {
  imported.value = true;
  generated.value = true;
  activeRouteId.value = routes.value[0].id;
  rebalanceRoutes();
  ElMessage.success('已按装载率、区域和车辆容量预排线路');
}

function selectStore(store: StoreOrder) {
  activeStoreId.value = store.id;
  const parentRoute = routes.value.find((route) => route.storeIds.includes(store.id));
  if (parentRoute) activeRouteId.value = parentRoute.id;
}

function selectRoute(route: RoutePlan) {
  activeRouteId.value = route.id;
  activeStoreId.value = route.storeIds[0] ?? activeStoreId.value;
}

function moveStore(storeId: string, direction: -1 | 1) {
  const target = routes.value.find((route) => route.id === activeRouteId.value);
  if (!target) return;
  const index = target.storeIds.indexOf(storeId);
  const nextIndex = index + direction;
  if (index < 0 || nextIndex < 0 || nextIndex >= target.storeIds.length) return;
  const nextIds = [...target.storeIds];
  [nextIds[index], nextIds[nextIndex]] = [nextIds[nextIndex], nextIds[index]];
  routes.value = routes.value.map((route) => {
    if (route.id !== target.id) return route;
    const nextRoute = { ...route, storeIds: nextIds, distanceKm: estimateRouteDistance(nextIds) };
    return { ...nextRoute, ...assessRoute(nextRoute) };
  });
}

function lockRoute() {
  routes.value = routes.value.map((route) => route.id === activeRouteId.value ? { ...route, status: 'locked' } : route);
  ElMessage.success(`${activeRoute.value.name} 已人工确认`);
}

function exportPlan() {
  if (!generated.value) {
    ElMessage.warning('请先按规则预排线路，再导出线路表');
    return;
  }
  downloadCsv('一加物流线路表.csv', [
    ['线路', '车辆', '司机', '门店数', '方数', '公里数', '配送顺序'],
    ...exportRows.value.map((row) => [row.line, row.vehicle, row.driver, row.stores, row.volume, row.distance, row.order]),
  ]);
  ElMessage.success('已导出线路表 CSV');
}

function resetDemo() {
  imported.value = false;
  generated.value = false;
  activeStoreId.value = 'S-001';
  activeRouteId.value = 'R-01';
  targetLoad.value = 88;
  routes.value = buildAreaRoutes();
}

function statusLabel(status: RouteStatus) {
  const map: Record<RouteStatus, string> = {
    draft: '待确认',
    locked: '已确认',
    risk: '需人工',
  };
  return map[status];
}

function statusType(status: RouteStatus) {
  const map: Record<RouteStatus, 'warning' | 'success' | 'danger'> = {
    draft: 'warning',
    locked: 'success',
    risk: 'danger',
  };
  return map[status];
}
</script>

<template>
  <div class="route-demo-page">
    <section class="page-hero">
      <div class="hero-title">
        <div class="eyebrow">
          <el-icon><Guide /></el-icon>
          一加物流 · 每日订单地图排线 Demo
        </div>
        <h1>导入门店订单，地图成点，快速形成车辆线路框架</h1>
        <p>电脑端网页 Demo；首期不依赖 GPS 实时轨迹，先用订单地址、货量体积、车辆容量和路线距离跑通排线框架。</p>
        <div class="diff-strip">
          <span class="diff-chip">导入即出图</span>
          <span class="diff-chip">超载自动分车</span>
          <span class="diff-chip">拖一下就调顺序</span>
          <span class="diff-tag">调度员零培训上手 · 不像传统 TMS 那样要录一堆字段</span>
        </div>
      </div>
      <div class="hero-actions">
        <el-button :icon="Download" @click="downloadImportTemplate">模板样例</el-button>
        <el-button :icon="Upload" @click="importOrders">导入订单模板</el-button>
        <el-button type="primary" :icon="Aim" @click="generateRoutes">按规则预排线路</el-button>
        <el-button :icon="Download" @click="exportPlan">导出线路表</el-button>
        <el-button :icon="Refresh" @click="resetDemo">重置</el-button>
      </div>
    </section>

    <section class="summary-grid">
      <div class="summary-card">
        <el-icon><OfficeBuilding /></el-icon>
        <div><strong>{{ stores.length }}</strong><span>今日门店点位</span></div>
      </div>
      <div class="summary-card">
        <el-icon><Box /></el-icon>
        <div><strong>{{ totalVolume.toFixed(1) }} 方</strong><span>{{ totalWeight.toLocaleString() }} kg</span></div>
      </div>
      <div class="summary-card">
        <el-icon><Van /></el-icon>
        <div><strong>{{ neededVehicles }} 辆</strong><span>推荐车辆数</span></div>
      </div>
      <div class="summary-card">
        <el-icon><Guide /></el-icon>
        <div><strong>{{ totalDistance.toFixed(1) }} km</strong><span>模拟总里程</span></div>
      </div>
    </section>

    <section class="main-grid">
      <main class="map-panel">
        <div class="panel-toolbar">
          <div>
            <h2>苏州门店地图排线</h2>
            <span>{{ activeProviderLabel }} 示意底图，后续替换真实 SDK / 路径服务</span>
          </div>
          <el-segmented v-model="activeProvider" :options="providers.map((item) => ({ label: item.label, value: item.key }))" />
        </div>

        <div class="map-shell">
          <div class="map-layer">
            <span class="water west">太湖</span>
            <span class="water east">阳澄湖</span>
            <span class="road r1"></span>
            <span class="road r2"></span>
            <span class="road r3"></span>
            <span class="road r4"></span>
            <span class="district d1">高新区</span>
            <span class="district d2">姑苏区</span>
            <span class="district d3">工业园区</span>
            <span class="district d4">吴江区</span>

            <svg class="route-svg" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
              <polyline v-if="generated" :points="activePolyline" />
            </svg>

            <button
              v-for="store in stores"
              :key="store.id"
              class="store-pin"
              :class="{
                active: store.id === activeStoreId,
                route: routeStoreSet.has(store.id),
                hidden: !imported && store.id !== activeStoreId,
              }"
              type="button"
              :style="{ left: `${store.x}%`, top: `${store.y}%` }"
              @click="selectStore(store)"
            >
              <span class="pin-dot"></span>
              <span class="pin-label">{{ store.name }}</span>
            </button>
          </div>

          <aside class="store-detail">
            <div class="detail-heading">
              <el-icon><MapLocation /></el-icon>
              <div>
                <h3>{{ activeStore.name }}</h3>
                <span>{{ activeStore.address }}</span>
              </div>
            </div>
            <div class="detail-grid">
              <div><span>件数</span><strong>{{ activeStore.pieces }}</strong></div>
              <div><span>箱数</span><strong>{{ activeStore.boxes }}</strong></div>
              <div><span>重量</span><strong>{{ activeStore.weightKg }} kg</strong></div>
              <div><span>体积</span><strong>{{ activeStore.volumeCbm }} 方</strong></div>
            </div>
            <div class="detail-line">
              <span>配送时间窗</span>
              <strong>{{ activeStore.window }}</strong>
            </div>
            <div class="provider-note">
              <el-icon><Location /></el-icon>
              <span>{{ providers.find((item) => item.key === activeProvider)?.status }}</span>
            </div>
          </aside>
        </div>
      </main>

      <aside class="route-panel">
        <div class="panel-heading">
          <h2>推荐线路</h2>
          <span>演示按 {{ targetLoad }}% 目标装载率预排</span>
        </div>

        <div class="target-control">
          <span>目标装载率</span>
          <el-slider v-model="targetLoad" :min="70" :max="98" :step="1" @change="onTargetLoadChange" />
        </div>

        <button
          v-for="route in routes"
          :key="route.id"
          class="route-card"
          :class="{ active: route.id === activeRouteId }"
          type="button"
          @click="selectRoute(route)"
        >
          <div class="route-card-head">
            <strong>{{ route.name }}</strong>
            <el-tag :type="statusType(route.status)" size="small">{{ statusLabel(route.status) }}</el-tag>
          </div>
          <div class="route-card-meta">
            {{ vehicleById(route.vehicleId).plate }} · {{ route.storeIds.length }} 家 · {{ route.distanceKm }} km
          </div>
          <div class="route-card-note">{{ route.note }}</div>
        </button>
      </aside>
    </section>

    <section class="operation-grid">
      <div class="operation-panel">
        <div class="panel-heading horizontal">
          <div>
            <h2>当前线路人工调整</h2>
            <span>{{ activeRoute.name }} · {{ activeVehicle.plate }} · {{ activeVehicle.driver }}</span>
          </div>
          <el-button type="primary" :icon="Finished" @click="lockRoute">确认本线路</el-button>
        </div>

        <div class="route-kpi-grid">
          <div><span>装载率</span><strong>{{ activeLoadRate }}%<em v-if="activeOverflowStores.length" class="over-flag"> · 超 {{ activeOverflowVolume.toFixed(1) }} 方顺延</em></strong></div>
          <div><span>重量利用</span><strong>{{ activeWeightRate }}%</strong></div>
          <div><span>线路里程</span><strong>{{ activeRoute.distanceKm }} km</strong></div>
          <div><span>司机班次</span><strong>{{ activeVehicle.shift }}</strong></div>
          <div><span>替班司机</span><strong>{{ activeVehicle.backupDrivers }}</strong></div>
          <div><span>车型规则</span><strong>{{ activeVehicle.vehicleBody }} · 按总方数</strong></div>
        </div>

        <div class="sequence-list">
          <div v-for="(store, index) in activeFittedStores" :key="store.id" class="sequence-row">
            <span class="sequence-index">{{ index + 1 }}</span>
            <div>
              <strong>{{ store.name }}</strong>
              <span>{{ store.volumeCbm }} 方 · {{ store.weightKg }} kg · {{ store.window }}</span>
            </div>
            <div class="sequence-actions">
              <el-button :icon="ArrowUp" circle size="small" :disabled="index === 0" @click="moveStore(store.id, -1)" />
              <el-button :icon="ArrowDown" circle size="small" :disabled="index === activeFittedStores.length - 1" @click="moveStore(store.id, 1)" />
            </div>
          </div>

          <template v-if="activeOverflowStores.length">
            <div class="overflow-divider">
              <el-icon><Warning /></el-icon>
              <span>超 {{ activeVehicle.capacityCbm }} 方上限（本车已装 {{ activeFittedVolume.toFixed(1) }} 方），以下 {{ activeOverflowStores.length }} 家自动分下一车次</span>
            </div>
            <div v-for="store in activeOverflowStores" :key="store.id" class="sequence-row overflow">
              <span class="sequence-index overflow">↓</span>
              <div>
                <strong>{{ store.name }}</strong>
                <span>{{ store.volumeCbm }} 方 · {{ store.weightKg }} kg · 顺延下一车次</span>
              </div>
            </div>
          </template>
        </div>
      </div>

      <div class="operation-panel">
        <div class="panel-heading">
          <h2>导出结果预览</h2>
          <span>后续可导出 Excel，给调度员和司机做线路顺序、公里数核对</span>
        </div>
        <el-table :data="exportRows" size="small" border>
          <el-table-column prop="line" label="线路" width="110" />
          <el-table-column prop="vehicle" label="车辆" width="105" />
          <el-table-column prop="driver" label="司机" width="120" />
          <el-table-column prop="stores" label="门店" width="70" />
          <el-table-column prop="volume" label="方数" width="115" />
          <el-table-column prop="distance" label="公里数" width="105" />
          <el-table-column prop="order" label="配送顺序" min-width="260" show-overflow-tooltip />
        </el-table>
      </div>
    </section>

    <section class="risk-band">
      <div>
        <el-icon><Warning /></el-icon>
        <strong>GPS 接入说明</strong>
        <span>当前 Demo 不需要实时 GPS。正式接入时只需要车辆定位 API、车牌/司机映射、时间戳、经纬度、速度/方向；路径规划可走高德/腾讯/百度任一供应商。</span>
      </div>
      <div>
        <el-icon><Aim /></el-icon>
        <strong>排线算法路线图</strong>
        <span>先按容量、门店距离、区域和人工固定司机做规则推荐；真实上线后再逐步加入路径矩阵、时间窗和多目标优化。</span>
      </div>
      <div>
        <el-icon><Van /></el-icon>
        <strong>车辆规则说明</strong>
        <span>客户车辆按双温车处理，首期不拆冷冻车/常温车类型；一车多司机先作为班次和替班司机字段维护。</span>
      </div>
    </section>
  </div>
</template>

<style scoped lang="scss">
.route-demo-page {
  min-height: calc(100vh - 72px);
  padding: 20px;
  background: #f4f6f9;
  color: #1a2332;
}

.page-hero,
.summary-card,
.map-panel,
.route-panel,
.operation-panel,
.risk-band {
  border: 1px solid #edf2f7;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.06);
}

.page-hero {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  padding: 22px 24px;
}

.eyebrow,
.hero-actions,
.summary-card,
.panel-toolbar,
.detail-heading,
.provider-note,
.route-card-head,
.panel-heading.horizontal,
.risk-band > div {
  display: flex;
  align-items: center;
}

.eyebrow {
  gap: 8px;
  color: #1b65a8;
  font-size: 13px;
  font-weight: 700;
}

h1,
h2,
h3,
p {
  margin: 0;
  letter-spacing: 0;
}

h1 {
  margin-top: 8px;
  font-size: 25px;
  line-height: 1.28;
}

.hero-title p {
  margin-top: 10px;
  color: #6f7f92;
  font-size: 14px;
}

.hero-actions {
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
  max-width: 520px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-top: 16px;
}

.summary-card {
  gap: 14px;
  min-height: 86px;
  padding: 16px;

  .el-icon {
    display: grid;
    width: 42px;
    height: 42px;
    place-items: center;
    border-radius: 8px;
    background: #e8f1ff;
    color: #1b65a8;
    font-size: 21px;
  }

  strong,
  span {
    display: block;
  }

  strong {
    font-size: 22px;
  }

  span {
    margin-top: 3px;
    color: #7a8599;
    font-size: 13px;
  }
}

.main-grid {
  display: grid;
  grid-template-columns: minmax(620px, 1fr) 340px;
  gap: 16px;
  margin-top: 16px;
}

.map-panel,
.route-panel,
.operation-panel {
  min-width: 0;
  padding: 18px;
}

.panel-toolbar {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.panel-toolbar h2,
.panel-heading h2 {
  font-size: 17px;
}

.panel-toolbar span,
.panel-heading span {
  display: block;
  margin-top: 4px;
  color: #7a8599;
  font-size: 12px;
}

.map-shell {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 310px;
  gap: 14px;
}

.map-layer {
  position: relative;
  min-height: 560px;
  overflow: hidden;
  border: 1px solid #dde6ef;
  border-radius: 8px;
  background:
    linear-gradient(90deg, rgba(81, 165, 205, 0.38), rgba(81, 165, 205, 0.12) 18%, transparent 30%),
    radial-gradient(circle at 77% 22%, rgba(81, 165, 205, 0.45), transparent 16%),
    radial-gradient(circle at 82% 74%, rgba(81, 165, 205, 0.34), transparent 12%),
    linear-gradient(135deg, rgba(102, 176, 116, 0.18), transparent 28%),
    #f7f6ef;
}

.water,
.district {
  position: absolute;
  color: rgba(26, 35, 50, 0.45);
  font-size: 13px;
  font-weight: 700;
  pointer-events: none;
}

.water.west { left: 8%; top: 23%; }
.water.east { right: 12%; top: 22%; }
.district.d1 { left: 18%; top: 55%; }
.district.d2 { left: 48%; top: 49%; }
.district.d3 { right: 21%; top: 46%; }
.district.d4 { left: 58%; bottom: 13%; }

.road {
  position: absolute;
  height: 5px;
  border-radius: 999px;
  background: #e5a642;
  opacity: 0.72;
  pointer-events: none;
}

.road.r1 { left: 20%; top: 42%; width: 66%; transform: rotate(-5deg); }
.road.r2 { left: 34%; top: 20%; width: 5px; height: 70%; transform: rotate(8deg); }
.road.r3 { left: 12%; top: 72%; width: 72%; transform: rotate(4deg); }
.road.r4 { left: 60%; top: 30%; width: 5px; height: 54%; transform: rotate(-16deg); }

.route-svg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;

  polyline {
    fill: none;
    stroke: #dd5a32;
    stroke-linecap: round;
    stroke-linejoin: round;
    stroke-width: 0.75;
    stroke-dasharray: 2 1.2;
  }
}

.store-pin {
  position: absolute;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: #1a4f93;
  cursor: pointer;
  transform: translate(-10px, -10px);

  &.hidden {
    opacity: 0.28;
  }

  &.route .pin-label {
    border-color: #dd5a32;
  }

  &.active {
    z-index: 4;

    .pin-dot {
      background: #dd5a32;
      box-shadow: 0 0 0 5px rgba(221, 90, 50, 0.2);
    }

    .pin-label {
      color: #17212f;
      font-weight: 800;
    }
  }
}

.pin-dot {
  width: 18px;
  height: 18px;
  border: 3px solid #fff;
  border-radius: 999px 999px 999px 2px;
  background: #2f6fd0;
  box-shadow: 0 4px 10px rgba(26, 35, 50, 0.2);
  transform: rotate(-45deg);
}

.pin-label {
  max-width: 210px;
  padding: 4px 8px;
  overflow: hidden;
  border: 1px solid #9ebbe4;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.88);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.store-detail {
  min-width: 0;
  padding: 16px;
  border: 1px solid #edf2f7;
  border-radius: 8px;
  background: #fbfcfe;
}

.detail-heading {
  gap: 10px;

  .el-icon {
    color: #dd5a32;
    font-size: 24px;
  }

  h3 {
    font-size: 16px;
    line-height: 1.35;
  }

  span {
    display: block;
    margin-top: 4px;
    color: #7a8599;
    font-size: 12px;
  }
}

.detail-grid,
.route-kpi-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 16px;

  div {
    padding: 12px;
    border-radius: 8px;
    background: #fff;
  }

  span,
  strong {
    display: block;
  }

  span {
    color: #7a8599;
    font-size: 12px;
  }

  strong {
    margin-top: 4px;
    font-size: 16px;
  }
}

.detail-line,
.provider-note {
  margin-top: 12px;
  padding: 12px;
  border-radius: 8px;
  background: #eef6f1;
}

.detail-line span,
.detail-line strong {
  display: block;
}

.detail-line span {
  color: #708095;
  font-size: 12px;
}

.detail-line strong {
  margin-top: 4px;
}

.provider-note {
  gap: 8px;
  color: #27623c;
  font-size: 12px;
}

.panel-heading {
  margin-bottom: 14px;
}

.panel-heading.horizontal {
  justify-content: space-between;
  gap: 12px;
}

.target-control {
  padding: 12px;
  border-radius: 8px;
  background: #f6f8fa;

  span {
    color: #667386;
    font-size: 12px;
  }
}

.route-card {
  width: 100%;
  margin-top: 10px;
  padding: 13px;
  border: 1px solid #edf2f7;
  border-radius: 8px;
  background: #fff;
  color: inherit;
  text-align: left;
  cursor: pointer;

  &.active,
  &:hover {
    border-color: #1b65a8;
    box-shadow: 0 8px 22px rgba(27, 101, 168, 0.10);
  }
}

.route-card-head {
  justify-content: space-between;
  gap: 8px;
}

.route-card-meta,
.route-card-note {
  margin-top: 8px;
  color: #667386;
  font-size: 12px;
}

.operation-grid {
  display: grid;
  grid-template-columns: minmax(480px, 0.95fr) minmax(560px, 1.05fr);
  gap: 16px;
  margin-top: 16px;
}

.sequence-list {
  display: grid;
  gap: 10px;
  margin-top: 14px;
}

.sequence-row {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid #edf2f7;
  border-radius: 8px;
  background: #fff;
}

.sequence-index {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 999px;
  background: #1b65a8;
  color: #fff;
  font-weight: 800;
}

.sequence-row strong,
.sequence-row span {
  display: block;
}

.sequence-row strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sequence-row div > span {
  margin-top: 4px;
  color: #7a8599;
  font-size: 12px;
}

.sequence-actions {
  display: flex;
  gap: 6px;
}

.risk-band {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-top: 16px;
  padding: 16px 18px;

  > div {
    align-items: flex-start;
    gap: 10px;
  }

  .el-icon {
    margin-top: 2px;
    color: #dd5a32;
    font-size: 20px;
  }

  strong,
  span {
    display: block;
  }

  strong {
    flex: 0 0 auto;
    min-width: 120px;
  }

  span {
    color: #667386;
    font-size: 13px;
    line-height: 1.6;
  }
}

.diff-strip {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
}

.diff-chip {
  padding: 4px 11px;
  border-radius: 999px;
  background: #e8f1ff;
  color: #1b65a8;
  font-size: 12px;
  font-weight: 700;
}

.diff-tag {
  color: #27623c;
  font-size: 12px;
  font-weight: 600;
}

.over-flag {
  color: #b23c17;
  font-size: 12px;
  font-style: normal;
  font-weight: 700;
}

.overflow-divider {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 2px;
  padding: 10px 12px;
  border: 1px dashed #dd5a32;
  border-radius: 8px;
  background: #fdf1ec;
  color: #b23c17;
  font-size: 12px;
  font-weight: 700;

  .el-icon {
    color: #dd5a32;
    font-size: 15px;
  }
}

.sequence-row.overflow {
  border-style: dashed;
  border-color: #f0c3b2;
  background: #fdf6f3;
}

.sequence-index.overflow {
  background: #dd5a32;
}

@media (max-width: 1280px) {
  .main-grid,
  .operation-grid,
  .map-shell {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 860px) {
  .route-demo-page {
    padding: 12px;
  }

  .page-hero,
  .panel-toolbar,
  .panel-heading.horizontal {
    flex-direction: column;
    align-items: stretch;
  }

  .hero-actions {
    justify-content: flex-start;
  }

  .summary-grid,
  .risk-band {
    grid-template-columns: 1fr;
  }

  .map-layer {
    min-height: 460px;
  }
}
</style>
