<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessageBox } from 'element-plus';
import { vReveal } from '@/composables/useReveal';
import type { ManualOrderRow } from '@/api/logistics';
import ExportConfirmStep from '../components/ExportConfirmStep.vue';
import LogisticsMap from '../components/LogisticsMap.vue';
import LogisticsStepBar from '../components/LogisticsStepBar.vue';
import ManualConfirmStep from '../components/ManualConfirmStep.vue';
import OrderImportStep from '../components/OrderImportStep.vue';
import RouteCards from '../components/RouteCards.vue';
import ScheduleTimetable from '../components/ScheduleTimetable.vue';
import StoreDetailDrawer from '../components/StoreDetailDrawer.vue';
import { useLogisticsScheduling } from '../useLogisticsScheduling';

const state = useLogisticsScheduling();
// 查看路线步的视图切换：地图 / 调度时间表(甘特图)
const mapView = ref<'map' | 'timetable'>('map');
// 排线设置功能栏可折叠 —— 折叠后连同「查看路线」标题、车次总览条一起收起, 腾出竖向空间给地图/线路
// (调度员多数时间只看图不改设置)。
const settingsCollapsed = ref(false);
// 配送线路侧栏可隐藏 —— 隐藏后地图占满整行(核对整体分布时想要更大的图)。与设置折叠相互独立。
const routesHidden = ref(false);

// 地图/线路行的高度按「它在文档里的真实起点」算, 让整个查看路线步恰好铺满一屏、不再整页滚动
// (之前用 CSS 100vh 减固定值猜, 猜少了 → 地图溢出屏幕, 用户被迫滚动/浏览器缩到 80%)。
const mapRowRef = ref<HTMLElement>();
const mapRowHeight = ref(0); // 0 = 让 CSS 接管(窄屏堆叠 / 首帧兜底)
const mapRowStyle = computed(() => (mapRowHeight.value > 0 ? { height: `${mapRowHeight.value}px` } : {}));

function recomputeMapRowHeight(): void {
  const el = mapRowRef.value;
  if (!el) return;
  // 窄屏(≤1180)走 CSS 的竖向堆叠 + auto 高度, 不锁死高度
  if (window.innerWidth <= 1180) { mapRowHeight.value = 0; return; }
  const scroller = document.scrollingElement || document.documentElement;
  const rect = el.getBoundingClientRect();
  // 文档绝对起点 = 视口内 top + 已滚动量, 与当前是否滚动无关(稳定, 不会自激振荡)
  const docTop = rect.top + window.scrollY;
  // 地图行下方实际占用的像素(gap + 上一步/下一步条 + 页面下内边距) —— 直接量而不是猜死数,
  // 自校准任何浏览器/缩放/工具栏差异。这些元素在地图行之后, 高度与地图行高无关, 故稳定。
  const below = Math.max(0, scroller.scrollHeight - (docTop + rect.height));
  mapRowHeight.value = Math.max(440, Math.round(window.innerHeight - docTop - below));
}
const route = useRoute();
const router = useRouter();

// 导出预览是一个纯前端"我已经看过预览"门槛（只要还有车次待匹配车辆，就不能正式确认排程）,
// 不是后端状态，随重新生成/离开导出步重置。
const exportPreviewConfirmed = ref(false);

const canConfirmSchedule = computed(() => state.scheduleResult.value.trips.length > 0
  && state.scheduleResult.value.unassignedStoreIds.length === 0
  && state.scheduleResult.value.trips.every((trip) => trip.status === 'confirmed'));

const exportConfirmed = computed(() => state.plan.value?.status === 'CONFIRMED' || state.plan.value?.status === 'EXPORTED');

const hasExceptions = computed(() => {
  const trips = state.scheduleResult.value.trips;
  return Boolean(state.planError.value)
    || Boolean(state.importError.value)
    || state.scheduleResult.value.unassignedStoreIds.length > 0
    || state.unresolvedOrders.value.length > 0
    || trips.some((trip) => trip.status === 'needs_vehicle' || trip.status === 'needs_driver' || trip.status === 'needs_route_data');
});

const exceptionDescription = computed(() => {
  if (state.planError.value) return state.planError.value;
  if (state.importError.value) return state.importError.value;
  if (state.unresolvedOrders.value.length > 0) {
    return `${state.unresolvedOrders.value.length} 家门店尚未定位，请到「门店与订单」补录经纬度后再生成路线。`;
  }
  if (state.scheduleResult.value.unassignedStoreIds.length > 0) {
    return `${state.scheduleResult.value.unassignedStoreIds.length} 家门店超出所有车辆容量，未分配到任何车次。`;
  }
  return undefined;
});

const WORKBENCH_STEPS = ['import', 'map', 'confirm', 'export'] as const;
type WorkbenchStep = (typeof WORKBENCH_STEPS)[number];

onMounted(async () => {
  // 先抓 URL 里的步骤 —— restore 里 openPlan 会把 activeStep 置成 map 并经 watch 覆盖掉 URL 的 step，
  // 必须在 restore 之前捕获，之后再应用，否则刷新前所在步骤会被冲掉、总是跳「查看路线」。
  const stepFromQuery = typeof route.query.step === 'string' ? route.query.step : null;
  await Promise.all([state.loadVehicles(), state.loadDrivers()]);
  const planIdFromQuery = typeof route.query.planId === 'string' ? route.query.planId : null;
  await state.restore(planIdFromQuery);
  if (stepFromQuery && (WORKBENCH_STEPS as readonly string[]).includes(stepFromQuery)) {
    state.activeStep.value = stepFromQuery as WorkbenchStep;
  } else if (!planIdFromQuery) {
    // 默认打开(无 URL step 且非 planId 深链): 从第一步「导入订单」开始, 不因自动恢复了最近计划
    // 就直接跳到第二步「查看路线」。刷新时 URL 带 step → 上面分支停在原步。
    state.activeStep.value = 'import';
  }
  await nextTick();
  recomputeMapRowHeight();
  window.addEventListener('resize', recomputeMapRowHeight);
});

onUnmounted(() => {
  window.removeEventListener('resize', recomputeMapRowHeight);
});

// 影响地图行起点/可用高度的因素变化后重算(功能栏折叠改变上方高度、切步骤、异常条出现、车次数变化)。
watch(
  () => [settingsCollapsed.value, mapView.value, state.activeStep.value, hasExceptions.value, state.scheduleResult.value.trips.length],
  () => { void nextTick(recomputeMapRowHeight); },
);

// 步骤切换写回 URL，刷新后停在同一步（不再强制跳到「查看路线」）。
watch(() => state.activeStep.value, (step) => {
  if (route.query.step !== step) router.replace({ query: { ...route.query, step } });
});

// 生成/恢复计划后把 planId 写回 URL，刷新页面时可据此恢复（handoff §12.3）。
watch(() => state.plan.value?.id, (planId) => {
  if (planId && route.query.planId !== planId) {
    router.replace({ query: { ...route.query, planId } });
  }
});

// 路线视图当天总览（车次/配送门店/总里程）—— 给调度员一眼看清当天工作量。
const summaryTrips = computed(() => state.scheduleResult.value.trips);
const totalTripCount = computed(() => summaryTrips.value.length);
const totalStopCount = computed(() => summaryTrips.value.reduce((sum, t) => sum + t.storeIds.length, 0));
const totalKm = computed(() => summaryTrips.value.reduce((sum, t) => sum + (t.totalDistanceKm || 0), 0));

async function downloadTemplate(): Promise<void> {
  await state.downloadTemplate();
}

async function uploadFile(file: File): Promise<void> {
  await state.uploadPreview(file);
}

async function commitImport(): Promise<void> {
  await state.commitImport();
}

async function submitManual(payload: { businessDate: string | null; rows: ManualOrderRow[] }): Promise<void> {
  await state.submitManualOrders(payload.businessDate, payload.rows);
}

/** 开始新一天排线：清空当前视图回到第一步录入。当前计划已存库，可随时从计划列表恢复（防呆确认）。 */
async function startNewSchedule(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将清空当前排线视图，回到第一步录入新一天的订单。当前计划已保存到系统，可随时恢复。',
      '新建排线',
      { confirmButtonText: '开始录入', cancelButtonText: '取消', type: 'info' },
    );
  } catch {
    return; // 用户取消
  }
  state.reset();
  await router.replace({ query: {} }); // 清掉 URL 里的 planId，避免刷新又恢复旧计划
  await Promise.all([state.loadVehicles(), state.loadDrivers()]);
}

function handleTargetLoad(value: number): void {
  void state.setTargetLoad(value);
}

function handleOptimizeMode(mode: 'TIME' | 'DISTANCE'): void {
  void state.setOptimizeMode(mode);
}

// 人工确认页现在一次展示全部车次, 每个操作都带 tripId —— 先把该车次设为当前, 再走原子写回。
// (state 里的写操作按 activeTrip 定位; selectTrip 同步设置 selectedTripId, activeTrip 是 computed, 立即生效。)
async function assignVehicle(tripId: string, vehicleId: string | null): Promise<void> {
  state.selectTrip(tripId);
  await state.assignVehicle(vehicleId);
}

async function assignDriver(tripId: string, driverId: string | null): Promise<void> {
  state.selectTrip(tripId);
  await state.assignDriver(driverId);
}

async function handleMoveStore(tripId: string, storeId: string, direction: -1 | 1): Promise<void> {
  state.selectTrip(tripId);
  await state.moveStore(storeId, direction);
}

async function handleMoveToTrip(tripId: string, storeId: string, targetTripId: string | null): Promise<void> {
  state.selectTrip(tripId);
  await state.moveStoreToTrip(storeId, targetTripId);
}

async function confirmTrip(tripId: string): Promise<void> {
  state.selectTrip(tripId);
  await state.confirmTrip();
}

/** 一键确认全部草稿车次 —— 串行确认, 每步拿最新 snapshot 避免版本冲突; 任一失败即停留提示。 */
async function confirmAllTrips(): Promise<void> {
  const draftIds = state.scheduleResult.value.trips.filter((t) => t.status === 'draft').map((t) => t.id);
  for (const id of draftIds) {
    state.selectTrip(id);
    if (!(await state.confirmTrip())) break;
  }
}

async function confirmSchedule(): Promise<void> {
  if (await state.confirmSchedule()) exportPreviewConfirmed.value = false;
}

function confirmExportPreview(): void {
  exportPreviewConfirmed.value = true;
  state.activeStep.value = 'export';
}

async function exportCsv(): Promise<void> {
  await state.exportCsv();
}

async function exportXlsx(): Promise<void> {
  await state.exportXlsx();
}

function back(): void {
  const steps = ['import', 'map', 'confirm', 'export'] as const;
  const index = steps.indexOf(state.activeStep.value);
  if (index > 0) state.activeStep.value = steps[index - 1];
  if (state.activeStep.value !== 'export') exportPreviewConfirmed.value = false;
}

async function next(): Promise<void> {
  if (state.activeStep.value === 'import') await state.generateRoutes();
  else if (state.activeStep.value === 'map') state.activeStep.value = 'confirm';
  else if (state.activeStep.value === 'confirm') state.previewExport();
}
</script>

<template>
  <main class="workbench-page">
    <div v-if="state.analyzing.value" data-testid="ai-analyzing" class="ai-analyzing-overlay">
      <div class="ai-analyzing-card">
        <div class="ai-spark">🧠</div>
        <p class="ai-title">AI 智能分析排班中…</p>
        <p class="ai-sub">正在综合门店位置、货量、车辆容量、司机区域与路线距离生成推荐方案</p>
        <el-progress :percentage="state.analyzeProgress.value" :stroke-width="12" :duration="0" />
      </div>
    </div>
    <header class="page-header">
      <div><h1>配送排程</h1><p>按订单、路线、确认和导出完成当天排程。</p></div>
      <div class="header-actions">
        <el-tag v-if="state.batch.value" effect="plain" type="success">批次 {{ state.batch.value.batchNumber }}</el-tag>
        <el-button
          v-if="state.batch.value || state.plan.value"
          type="primary"
          plain
          @click="startNewSchedule"
        >+ 新建排线</el-button>
      </div>
    </header>
    <LogisticsStepBar :active-step="state.activeStep.value" />
    <el-alert v-if="hasExceptions" data-testid="assignment-issue" title="需要处理" :description="exceptionDescription" type="warning" :closable="false" show-icon />

    <OrderImportStep
      v-if="state.activeStep.value === 'import'"
      :preview="state.preview.value"
      :batch="state.batch.value"
      :uploading="state.uploading.value"
      :committing="state.committing.value"
      :error="state.importError.value"
      @download-template="downloadTemplate"
      @upload-file="uploadFile"
      @commit="commitImport"
      @submit-manual="submitManual"
      @clear-batch="startNewSchedule"
    />

    <section v-else-if="state.activeStep.value === 'map'" data-testid="map-step" class="map-step">
      <div class="view-controls">
        <button
          type="button"
          data-testid="toggle-settings"
          :aria-expanded="!settingsCollapsed"
          @click="settingsCollapsed = !settingsCollapsed"
        >{{ settingsCollapsed ? '展开设置 ▾' : '收起设置 ▴' }}</button>
        <button
          type="button"
          data-testid="toggle-routes"
          :aria-pressed="!routesHidden"
          @click="routesHidden = !routesHidden"
        >{{ routesHidden ? '显示配送线路 ◂' : '隐藏配送线路 ▸' }}</button>
      </div>
      <template v-if="!settingsCollapsed">
        <header class="map-heading"><div><p>第二步</p><h2>查看路线</h2></div></header>
        <div v-if="totalTripCount > 0" v-reveal="0" class="route-summary" data-testid="route-summary">
          <div class="rs-item"><span class="rs-value">{{ totalTripCount }}</span><span class="rs-label">车次</span></div>
          <div class="rs-sep" />
          <div class="rs-item"><span class="rs-value">{{ totalStopCount }}</span><span class="rs-label">配送门店</span></div>
          <div class="rs-sep" />
          <div class="rs-item"><span class="rs-value">{{ totalKm.toFixed(1) }}</span><span class="rs-label">总里程 km</span></div>
        </div>
        <div class="route-settings" data-testid="route-settings">
          <span class="settings-title">排线设置</span>
          <button data-testid="generate-routes" class="generate-button" type="button" @click="state.regeneratePlanAction">重新生成路线</button>
          <label class="view-toggle">视图 <el-radio-group :model-value="mapView" size="small" @update:model-value="(v: 'map'|'timetable') => (mapView = v)"><el-radio-button label="map">地图</el-radio-button><el-radio-button label="timetable">调度时间表</el-radio-button></el-radio-group></label>
          <label class="opt-mode">优化目标 <el-radio-group :model-value="state.optimizeMode.value" size="small" @update:model-value="handleOptimizeMode"><el-radio-button label="DISTANCE">路程最短</el-radio-button><el-radio-button label="TIME">时间最快</el-radio-button></el-radio-group></label>
          <label class="load-ctl">目标装载率 <el-slider :model-value="state.targetLoadPct.value" :min="50" :max="100" :show-tooltip="true" @update:model-value="handleTargetLoad" /></label>
        </div>
      </template>
      <div ref="mapRowRef" class="map-and-routes" :class="{ 'routes-hidden': routesHidden }" :style="mapRowStyle">
        <div class="mr-map">
          <LogisticsMap v-show="mapView === 'map'" :stores="state.stores.value" :trips="state.scheduleResult.value.trips" :selected-trip-id="state.selectedTripId.value" :selected-store-id="state.selectedStoreId.value" @select-trip="state.selectTrip" @select-store="state.selectStore" />
          <ScheduleTimetable v-if="mapView === 'timetable'" :trips="state.scheduleResult.value.trips" :selected-trip-id="state.selectedTripId.value" @select-trip="state.selectTrip" />
        </div>
        <div v-show="!routesHidden" class="mr-routes" data-testid="routes-pane">
          <div class="mr-routes-title">配送线路 <span class="mrt-sub">{{ totalTripCount }} 车次 · 下滑查看全部</span></div>
          <RouteCards :stores="state.stores.value" :trips="state.scheduleResult.value.trips" :selected-trip-id="state.selectedTripId.value" :selected-store-id="state.selectedStoreId.value" @select-trip="state.selectTrip" @select-store="state.selectStore" />
        </div>
      </div>
      <StoreDetailDrawer :stores="state.stores.value" :selected-store-id="state.selectedStoreId.value" @select-store="state.selectStore" />
      <section v-if="state.unresolvedOrders.value.length" data-testid="unresolved-stores" class="unresolved-panel">
        <strong>{{ state.unresolvedOrders.value.length }} 家门店待定位</strong>
        <ul>
          <li v-for="order in state.unresolvedOrders.value" :key="order.id">{{ order.storeName }} · {{ order.address }}</li>
        </ul>
        <router-link to="/scheduling/logistics/orders">前往「门店与订单」补录经纬度</router-link>
      </section>
    </section>

    <ManualConfirmStep
      v-else-if="state.activeStep.value === 'confirm'"
      :trips="state.scheduleResult.value.trips"
      :stores="state.stores.value"
      :vehicles="state.vehiclesView.value"
      :selected-trip-id="state.selectedTripId.value"
      @select-trip="state.selectTrip"
      @move-store="handleMoveStore"
      @move-to-trip="handleMoveToTrip"
      @assign-vehicle="assignVehicle"
      @assign-driver="assignDriver"
      @confirm-trip="confirmTrip"
      @confirm-all="confirmAllTrips"
    />
    <ExportConfirmStep
      v-else
      :rows="state.exportRows.value"
      :confirmed="exportConfirmed"
      :preview-confirmed="exportPreviewConfirmed"
      :can-confirm-schedule="canConfirmSchedule"
      :plan-id="state.plan.value?.id ?? null"
      @confirm-schedule="confirmSchedule"
      @confirm-preview="confirmExportPreview"
      @export-csv="exportCsv"
      @export-xlsx="exportXlsx"
    />

    <footer class="action-bar"><el-button :disabled="state.activeStep.value === 'import'" @click="back">上一步</el-button><button v-if="state.activeStep.value !== 'export'" data-testid="finish-schedule" class="next-button" type="button" @click="next">{{ state.activeStep.value === 'confirm' ? '查看排班预览' : '下一步' }}</button></footer>
  </main>
</template>

<style scoped lang="scss">
.workbench-page { display: grid; gap: 20px; max-width: 2560px; min-height: 100%; padding: 24px; margin: 0 auto; background: #f8fafc; } .ai-analyzing-overlay { position: fixed; inset: 0; z-index: 3000; display: grid; place-items: center; background: rgba(16, 24, 40, 0.55); backdrop-filter: blur(2px); } .ai-analyzing-card { width: min(460px, 90vw); padding: 32px 28px; text-align: center; background: #fff; border-radius: 16px; box-shadow: 0 12px 40px rgba(0,0,0,0.25); } .ai-spark { font-size: 40px; animation: ai-pulse 1.1s ease-in-out infinite; } @keyframes ai-pulse { 0%,100% { transform: scale(1); opacity: 0.85; } 50% { transform: scale(1.18); opacity: 1; } } .ai-title { margin: 12px 0 6px; color: #101828; font-size: 18px; font-weight: 750; } .ai-sub { margin: 0 0 18px; color: #667085; font-size: 13px; line-height: 1.5; }
.page-header, .map-heading, .action-bar { display: flex; align-items: center; justify-content: space-between; gap: 16px; } .header-actions { display: flex; align-items: center; gap: 12px; }
.route-summary { display: flex; align-items: center; gap: 20px; padding: 14px 20px; background: linear-gradient(180deg, #ffffff, #f8fafc); border: 1px solid #e2e8f0; border-radius: 10px; } .route-summary .rs-item { display: flex; flex-direction: column; gap: 2px; } .route-summary .rs-value { color: #0f172a; font-size: 22px; font-weight: 750; line-height: 1.1; font-variant-numeric: tabular-nums; } .route-summary .rs-label { color: #667085; font-size: 12.5px; } .route-summary .rs-sep { width: 1px; height: 28px; background: #e2e8f0; }
/* 左地图 + 右线路(可下滑) 两栏布局 —— 高度跟随视口填满可用竖向空间(而非固定 640) */
/* 高度由 JS(recomputeMapRowHeight)按文档真实起点算, 精确铺满一屏; 下面 clamp 只是 JS 生效前的首帧兜底 */
/* 顶部控制条: 展开/收起设置 + 显示/隐藏配送线路 —— 两个开关放同一处 */
.view-controls { display: flex; justify-content: flex-end; gap: 10px; }
.view-controls button { display: inline-flex; align-items: center; padding: 7px 14px; color: #475569; font: inherit; font-size: 13px; font-weight: 650; background: #fff; border: 1px solid #dbe3ec; border-radius: 8px; cursor: pointer; transition: background 0.15s ease; }
.view-controls button:hover { background: #f0f4f8; }
/* 地图(主) + 配送线路(独立成卡, 跳出地图模块)。隐藏线路后地图占满整行。 */
.map-and-routes { display: flex; gap: 16px; height: clamp(440px, calc(100vh - 460px), 900px); transition: height 0.2s ease; }
.mr-map { position: relative; flex: 1 1 auto; min-width: 0; height: 100%; overflow: hidden; border-radius: 12px; }
.mr-map :deep(.map-stage) { aspect-ratio: auto !important; height: 100% !important; }
.mr-routes { flex: 0 0 400px; height: 100%; display: flex; flex-direction: column; overflow: hidden; padding: 14px 16px; background: #fff; border: 1px solid #e6eaf0; border-radius: 12px; box-shadow: 0 2px 12px rgba(16,24,40,0.05); }
.mr-routes-title { flex: 0 0 auto; display: flex; align-items: baseline; gap: 8px; margin-bottom: 10px; color: #101828; font-size: 15px; font-weight: 700; }
.mrt-sub { color: #98a2b3; font-size: 12px; font-weight: 500; font-variant-numeric: tabular-nums; }
.mr-routes :deep(.route-cards) { grid-template-columns: 1fr; overflow-y: auto; flex: 1 1 auto; padding-right: 6px; align-content: start; }
@media (max-width: 1180px) {
  .map-and-routes { flex-direction: column; height: auto; }
  .mr-map { height: 520px; }
  .mr-routes { flex-basis: auto; width: 100%; height: auto; }
  .mr-routes :deep(.route-cards) { grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); max-height: 420px; }
} h1,h2 { margin: 0; color: #101828; } .page-header p, .map-heading p { margin: 6px 0 0; color: #667085; } .map-step { display: grid; gap: 16px; } .map-heading label { display: grid; grid-template-columns: auto minmax(150px, 260px); align-items: center; gap: 12px; color: #344054; font-size: 14px; font-weight: 650; } .map-controls { display: flex; align-items: center; gap: 20px; flex-wrap: wrap; } .map-heading .opt-mode { grid-template-columns: auto auto; } .route-settings { display: flex; align-items: center; gap: 18px; flex-wrap: wrap; padding: 12px 16px; background: #f4f6f9; border: 1px solid #edf2f7; border-radius: 10px; } .route-settings.collapsed { padding: 6px 12px; } .route-settings .settings-title { color: #101828; font-weight: 700; font-size: 14px; }
.settings-toggle { display: inline-flex; align-items: center; gap: 8px; margin-left: auto; padding: 4px 10px; background: transparent; border: 1px solid #dbe3ec; border-radius: 6px; cursor: pointer; font: inherit; color: #475569; } .settings-toggle:hover { background: #e8edf3; } .settings-chevron { color: #1b65a8; font-size: 12px; font-weight: 650; } .route-settings label { display: grid; grid-template-columns: auto auto; align-items: center; gap: 10px; color: #344054; font-size: 14px; font-weight: 650; } .route-settings .load-ctl { grid-template-columns: auto minmax(120px, 200px); } .route-settings .view-toggle { grid-template-columns: auto auto; } .route-settings .generate-button { padding: 8px 16px; } .generate-button, .next-button { width: fit-content; padding: 10px 18px; color: #fff; font: inherit; font-weight: 650; background: #1b65a8; border: 0; border-radius: 6px; cursor: pointer; } .action-bar { position: sticky; bottom: 0; z-index: 20; padding: 14px 0; background: linear-gradient(to bottom, transparent, #f8fafc 28%); }
.unresolved-panel { display: grid; gap: 8px; padding: 16px 20px; background: #fffaeb; border: 1px solid #fef0c7; border-radius: 10px; } .unresolved-panel strong { color: #b54708; } .unresolved-panel ul { display: grid; gap: 4px; margin: 0; padding-left: 20px; color: #93370d; font-size: 13px; } .unresolved-panel a { width: fit-content; color: #1b65a8; font-weight: 650; }
@media (max-width: 720px) { .workbench-page { padding: 16px; } .page-header,.map-heading { align-items: flex-start; flex-direction: column; } .map-heading label { width: 100%; } }
</style>
