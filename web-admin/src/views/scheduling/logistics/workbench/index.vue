<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { vReveal } from '@/composables/useReveal';
import type { ManualOrderRow } from '@/api/logistics';
import CapacityDiagnosisBanner from '../components/CapacityDiagnosisBanner.vue';
import ExportConfirmStep from '../components/ExportConfirmStep.vue';
import LogisticsMap from '../components/LogisticsMap.vue';
import LogisticsStepBar from '../components/LogisticsStepBar.vue';
import OrderImportStep from '../components/OrderImportStep.vue';
import RouteCards from '../components/RouteCards.vue';
import ScheduleTimetable from '../components/ScheduleTimetable.vue';
import StoreDetailDrawer from '../components/StoreDetailDrawer.vue';
import { useLogisticsScheduling } from '../useLogisticsScheduling';

const state = useLogisticsScheduling();
// 配送线路(右列)可隐藏 —— 用地图/线路之间的竖向 knob 手柄折叠, 隐藏后左侧地图区响应式撑满整行。
const routesHidden = ref(false);
// 调度时间表(甘特, 左列下方)可隐藏 —— 用地图/甘特之间的横向 knob 手柄折叠, 隐藏后地图向下变高。
const ganttHidden = ref(false);
// 有车次进入门店编辑态 → 右列(配送线路)加宽, 放得下 上移/下移 + 移至 控件(地图相应缩)。
const routesEditing = ref(false);
// 路线「脏」标记: 用户调了门店顺序/移了车次后, 显示的路线/距离/ETA 已失效, 必须先「重新生成路线」重算才能进下一步。
const routeDirty = ref(false);
// 运力充足(SUFFICIENT) → 控制条内绿 pill; 不足/不可服务 → 顶部完整 banner(带「去管理车辆」动作)。
const capacityOk = computed(() => state.capacityDiagnosis.value?.verdict === 'SUFFICIENT');

// 地图/线路行的高度按「它在文档里的真实起点」算, 让整个查看路线步恰好铺满一屏、不再整页滚动
// (之前用 CSS 100vh 减固定值猜, 猜少了 → 地图溢出屏幕, 用户被迫滚动/浏览器缩到 80%)。
const mapRowRef = ref<HTMLElement>();
const actionBarRef = ref<HTMLElement>();
const mapRowHeight = ref(0); // 0 = 让 CSS 接管(窄屏堆叠 / 首帧兜底)
const mapRowStyle = computed(() => (mapRowHeight.value > 0 ? { height: `${mapRowHeight.value}px` } : {}));

function recomputeMapRowHeight(): void {
  const el = mapRowRef.value;
  if (!el) return;
  // 窄屏(≤1180)走 CSS 的竖向堆叠 + auto 高度, 不锁死高度
  if (window.innerWidth <= 1180) { mapRowHeight.value = 0; return; }
  const rect = el.getBoundingClientRect(); // 视口内位置; 主体行上方元素不受它高度影响, 故 rect.top 稳定
  // 主体行(左=地图+甘特, 右=配送线路)整块铺满一屏 —— 甘特在左列内部(封顶+滚动), 折叠右列/甘特只在行内重分配,
  // 不改行高。下方只需预留「底部操作条 + 页面下内边距 + 间距」。⚠️ 不用 scrollHeight 反推(min-height:100% 会自我参照)。
  const barH = actionBarRef.value?.getBoundingClientRect().height ?? 64;
  const reserve = barH + 14 /*页面下 padding*/ + 12 /*grid gap*/;
  mapRowHeight.value = Math.max(420, Math.round(window.innerHeight - rect.top - reserve));
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

const WORKBENCH_STEPS = ['import', 'map', 'export'] as const;
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
  () => [state.activeStep.value, hasExceptions.value, state.scheduleResult.value.trips.length],
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
// 确认进度(原「人工确认」步的顶部计数, 合并后放查看路线控制条)
const confirmedTripCount = computed(() => summaryTrips.value.filter((t) => t.status === 'confirmed').length);
const unmatchedTripCount = computed(() => summaryTrips.value.filter((t) => !t.vehicleId || !t.driverId).length);
const confirmableTripCount = computed(() => summaryTrips.value.filter((t) => t.status === 'draft').length);

async function downloadTemplate(): Promise<void> {
  await state.downloadTemplate();
}

async function uploadFile(file: File, columnMapping?: Record<number, string>): Promise<void> {
  await state.uploadPreview(file, columnMapping);
}

async function pasteImport(payload: { rawText: string; businessDate: string | null; columnMapping?: Record<number, string> }): Promise<void> {
  await state.pastePreview(payload);
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

/** 运力诊断条「去管理车辆」next action（fool-proof-design Rule 5：不留 dead-end）—— 跳到车辆资源页补运力/补区域覆盖。 */
function goManageVehicles(): void {
  void router.push('/scheduling/logistics/resources');
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
  routeDirty.value = true; // 改了门店顺序 → 路线/距离/ETA 失效, 进下一步前须重算
}

async function handleMoveToTrip(tripId: string, storeId: string, targetTripId: string | null): Promise<void> {
  state.selectTrip(tripId);
  await state.moveStoreToTrip(storeId, targetTripId);
  routeDirty.value = true; // 移了车次 → 两条线路都变, 进下一步前须重算
}

/** 重新生成路线 —— 重算后清除脏标记(路线/距离/ETA 已刷新)。 */
async function regenerateRoutes(): Promise<void> {
  await state.regeneratePlanAction();
  routeDirty.value = false;
}

async function confirmTrip(tripId: string): Promise<void> {
  state.selectTrip(tripId);
  await state.confirmTrip();
}

/** 一键确认全部车次 —— 后端一次性确认(实时, 乐观更新立即全标已确认), 不再逐个串行请求。 */
async function confirmAllTrips(): Promise<void> {
  await state.confirmAllTrips();
}

async function confirmSchedule(): Promise<void> {
  if (await state.confirmSchedule()) {
    exportPreviewConfirmed.value = false;
    ElMessage.success('当天排班已确认，可下载 CSV / Excel 发给司机');
  }
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
  const steps = ['import', 'map', 'export'] as const;
  const index = steps.indexOf(state.activeStep.value as typeof steps[number]);
  if (index > 0) state.activeStep.value = steps[index - 1];
  if (state.activeStep.value !== 'export') exportPreviewConfirmed.value = false;
}

async function next(): Promise<void> {
  if (state.activeStep.value === 'import') {
    await state.generateRoutes();
    routeDirty.value = false; // 新生成的计划路线是最新的
  } else if (state.activeStep.value === 'map') {
    // 脏闸: 改过门店/车次但没重算 → 阻止进下一步(路线/距离/ETA 会不准)
    if (routeDirty.value) {
      ElMessage.warning('已调整门店顺序 / 车次，请先点「重新生成路线」重算后再进入下一步');
      return;
    }
    // 「查看并确认路线」→ 确认排班预览(原 map→confirm→export 两步已合并为一步)
    state.previewExport();
  }
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
    <!-- 运力充足(SUFFICIENT) 挪进查看路线控制条的绿 pill; 这里只在「不足/不可服务」时显示完整 banner(带动作)。 -->
    <CapacityDiagnosisBanner v-if="!capacityOk" :diagnosis="state.capacityDiagnosis.value" @manage-vehicles="goManageVehicles" />
    <el-alert v-if="hasExceptions" data-testid="assignment-issue" title="需要处理" :description="exceptionDescription" type="warning" :closable="false" show-icon />

    <OrderImportStep
      v-if="state.activeStep.value === 'import'"
      :preview="state.preview.value"
      :batch="state.batch.value"
      :orders="state.orders.value"
      :uploading="state.uploading.value"
      :committing="state.committing.value"
      :error="state.importError.value"
      @download-template="downloadTemplate"
      @upload-file="uploadFile"
      @preview-paste="pasteImport"
      @commit="commitImport"
      @submit-manual="submitManual"
      @clear-batch="startNewSchedule"
    />

    <section v-else-if="state.activeStep.value === 'map'" data-testid="map-step" class="map-step">
      <!-- 控制条: 统计 + 运力充足 pill(左) | 优化目标 + 重新生成路线(右)。「查看路线」标题不再重复(步骤条已标)。 -->
      <div class="route-controlbar">
        <div class="rc-left">
          <div v-if="totalTripCount > 0" v-reveal="0" class="rc-summary" data-testid="route-summary">
            <span class="rc-stat"><strong>{{ totalTripCount }}</strong>车次</span>
            <span class="rc-dot">·</span>
            <span class="rc-stat"><strong>{{ totalStopCount }}</strong>门店</span>
            <span class="rc-dot">·</span>
            <span class="rc-stat"><strong>{{ totalKm.toFixed(1) }}</strong>km</span>
          </div>
          <span v-if="capacityOk && state.capacityDiagnosis.value" class="cap-pill" data-testid="capacity-pill">✓ {{ state.capacityDiagnosis.value.message }}</span>
          <span v-if="totalTripCount > 0" class="cfm-pill" data-testid="confirm-progress">已确认 {{ confirmedTripCount }}/{{ totalTripCount }}<template v-if="unmatchedTripCount > 0"> · 待匹配 {{ unmatchedTripCount }}</template></span>
        </div>
        <div class="rc-right route-settings" data-testid="route-settings">
          <button v-if="confirmableTripCount > 0 && unmatchedTripCount === 0" type="button" class="confirm-all-btn" data-testid="confirm-all-trips" @click="confirmAllTrips">✓ 一键确认全部（{{ confirmableTripCount }}）</button>
          <label class="opt-mode">优化目标 <el-radio-group :model-value="state.optimizeMode.value" size="small" @update:model-value="handleOptimizeMode"><el-radio-button label="DISTANCE">路程最短</el-radio-button><el-radio-button label="TIME">时间最快</el-radio-button></el-radio-group></label>
          <button data-testid="generate-routes" class="generate-button" :class="{ 'dirty-pulse': routeDirty }" type="button" @click="regenerateRoutes">{{ routeDirty ? '⚠ 重新生成路线' : '重新生成路线' }}</button>
        </div>
      </div>

      <!-- 主体: 左(地图 + 甘特) | 竖向 knob 手柄 | 右(配送线路)。折叠任一侧, 地图响应式变大。 -->
      <div ref="mapRowRef" class="rv-body" :class="{ 'routes-hidden': routesHidden, 'routes-editing': routesEditing }" :style="mapRowStyle">
        <div class="rv-left">
          <div class="mr-map">
            <LogisticsMap :stores="state.stores.value" :trips="state.scheduleResult.value.trips" :selected-trip-id="state.selectedTripId.value" :selected-store-id="state.selectedStoreId.value" @select-trip="state.selectTrip" @select-store="state.selectStore" />
          </div>
          <!-- 甘特横向 knob 折叠手柄 + 甘特(图例/时间轴固定, 车辆行内部滚动 —— 车多也不挤地图) -->
          <template v-if="totalTripCount > 0">
            <div class="h-handle" data-testid="toggle-gantt" :title="ganttHidden ? '展开调度时间表' : '收起调度时间表'" @click="ganttHidden = !ganttHidden">
              <div class="knob-h"><span>{{ ganttHidden ? '▴' : '▾' }}</span><em>调度时间表</em></div>
            </div>
            <div v-show="!ganttHidden" class="gantt-body" data-testid="timetable-band">
              <ScheduleTimetable :trips="state.scheduleResult.value.trips" :selected-trip-id="state.selectedTripId.value" scrollable @select-trip="state.selectTrip" />
            </div>
          </template>
        </div>
        <!-- 竖向 knob 折叠手柄(地图/甘特 与 配送线路 之间) -->
        <div class="v-handle" data-testid="toggle-routes" :title="routesHidden ? '展开配送线路' : '隐藏配送线路'" @click="routesHidden = !routesHidden">
          <div class="knob-v"><span>{{ routesHidden ? '‹' : '›' }}</span><em>{{ routesHidden ? '展开线路' : '配送线路' }}</em></div>
        </div>
        <div v-show="!routesHidden" class="rv-right" data-testid="routes-pane">
          <div class="mr-routes-title">配送线路 · 派车与确认 <span class="mrt-sub">{{ totalTripCount }} 车次 · 逐条或一键确认</span></div>
          <RouteCards
            :stores="state.stores.value"
            :trips="state.scheduleResult.value.trips"
            :vehicles="state.vehiclesView.value"
            :selected-trip-id="state.selectedTripId.value"
            :selected-store-id="state.selectedStoreId.value"
            @select-trip="state.selectTrip"
            @select-store="state.selectStore"
            @assign-vehicle="assignVehicle"
            @assign-driver="assignDriver"
            @confirm-trip="confirmTrip"
            @move-store="handleMoveStore"
            @move-to-trip="handleMoveToTrip"
            @editing-change="routesEditing = $event"
          />
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

    <footer ref="actionBarRef" class="action-bar">
      <el-button :disabled="state.activeStep.value === 'import'" @click="back">上一步</el-button>
      <div class="ab-right">
        <span v-if="state.activeStep.value === 'map' && routeDirty" class="dirty-hint" data-testid="route-dirty-hint">已调整门店 / 车次，请先「重新生成路线」重算</span>
        <button v-if="state.activeStep.value !== 'export'" data-testid="finish-schedule" class="next-button" type="button" :disabled="state.activeStep.value === 'map' && routeDirty" @click="next">{{ state.activeStep.value === 'map' ? '查看排班预览' : '下一步' }}</button>
      </div>
    </footer>
  </main>
</template>

<style scoped lang="scss">
.workbench-page { display: grid; gap: 12px; max-width: 2560px; min-height: 100%; padding: 12px 24px 14px; margin: 0 auto; background: #f8fafc; } .ai-analyzing-overlay { position: fixed; inset: 0; z-index: 3000; display: grid; place-items: center; background: rgba(16, 24, 40, 0.55); backdrop-filter: blur(2px); } .ai-analyzing-card { width: min(460px, 90vw); padding: 32px 28px; text-align: center; background: #fff; border-radius: 16px; box-shadow: 0 12px 40px rgba(0,0,0,0.25); } .ai-spark { font-size: 40px; animation: ai-pulse 1.1s ease-in-out infinite; } @keyframes ai-pulse { 0%,100% { transform: scale(1); opacity: 0.85; } 50% { transform: scale(1.18); opacity: 1; } } .ai-title { margin: 12px 0 6px; color: #101828; font-size: 18px; font-weight: 750; } .ai-sub { margin: 0 0 18px; color: #667085; font-size: 13px; line-height: 1.5; }
.page-header, .map-heading, .action-bar { display: flex; align-items: center; justify-content: space-between; gap: 16px; } .header-actions { display: flex; align-items: center; gap: 12px; }
/* 控制条: 统计 + 运力 pill(左) | 优化目标 + 重新生成路线(右) */
.route-controlbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; padding: 9px 16px; background: linear-gradient(180deg, #ffffff, #f8fafc); border: 1px solid #e2e8f0; border-radius: 10px; }
.route-controlbar .route-settings { padding: 0; background: transparent; border: 0; border-radius: 0; }
.rc-left { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.rc-summary { display: flex; align-items: baseline; gap: 8px; color: #667085; font-size: 13px; }
.rc-summary .rc-stat { color: #667085; } .rc-summary .rc-stat strong { margin-right: 4px; color: #0f172a; font-size: 19px; font-weight: 750; font-variant-numeric: tabular-nums; } .rc-summary .rc-dot { color: #cbd5e1; }
.cap-pill { font-size: 12px; color: #027a48; background: #ecfdf3; border: 1px solid #a6f4c5; padding: 3px 10px; border-radius: 20px; white-space: nowrap; }
.cfm-pill { font-size: 12.5px; color: #175cd3; background: #eff8ff; border: 1px solid #b2ddff; padding: 3px 11px; border-radius: 20px; font-weight: 650; white-space: nowrap; }
.confirm-all-btn { padding: 8px 15px; color: #fff; font: inherit; font-size: 13px; font-weight: 650; background: #027a48; border: 0; border-radius: 6px; cursor: pointer; transition: background 0.15s ease; white-space: nowrap; }
.confirm-all-btn:hover { background: #026a3e; }
/* 路线脏闸: 改了门店/车次未重算时, 高亮「重新生成路线」+ 禁用「下一步」+ 提示 */
.ab-right { display: flex; align-items: center; gap: 12px; }
.dirty-hint { color: #b54708; font-size: 12.5px; font-weight: 600; }
.next-button:disabled { background: #98a2b3; cursor: not-allowed; }
.generate-button.dirty-pulse { background: #f79009; animation: dirty-pulse 1.2s ease-in-out infinite; }
.generate-button.dirty-pulse:hover { background: #dc7a06; }
@keyframes dirty-pulse { 0% { box-shadow: 0 0 0 0 rgba(247,144,9,0.5); } 70% { box-shadow: 0 0 0 8px rgba(247,144,9,0); } 100% { box-shadow: 0 0 0 0 rgba(247,144,9,0); } }

/* 主体行: 左(地图 + 甘特) | 竖向 knob 手柄 | 右(配送线路)。高度由 JS 铺满一屏; clamp 是首帧兜底。 */
.rv-body { display: flex; gap: 10px; height: clamp(420px, calc(100vh - 300px), 900px); transition: height 0.2s ease; }
.rv-left { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.mr-map { position: relative; flex: 1 1 auto; min-height: 0; overflow: hidden; border-radius: 12px; }
.mr-map :deep(.map-stage) { aspect-ratio: auto !important; height: 100% !important; }
.gantt-body { flex: none; }

/* 甘特横向 knob 折叠手柄(与竖向手柄统一样式) */
.h-handle { flex: none; position: relative; height: 22px; display: flex; align-items: center; justify-content: center; cursor: pointer; }
.h-handle::before { content: ''; position: absolute; left: 6px; right: 6px; top: 50%; height: 2px; transform: translateY(-50%); background: #dbe3ec; border-radius: 2px; }
.knob-h { position: relative; display: flex; align-items: center; gap: 8px; padding: 5px 16px; background: #fff; border: 1px solid #cdd9e8; border-radius: 10px; color: #1b65a8; box-shadow: 0 3px 10px rgba(27,101,168,0.16); transition: all 0.15s ease; }
.knob-h span { font-size: 14px; font-weight: 800; line-height: 1; } .knob-h em { font-style: normal; font-size: 12.5px; font-weight: 650; color: #5a6b80; }
.h-handle:hover .knob-h { background: #1b65a8; border-color: #1b65a8; color: #fff; } .h-handle:hover .knob-h em { color: #eaf2fb; }

/* 竖向 knob 折叠手柄(地图/甘特 与 配送线路 之间) */
.v-handle { flex: none; width: 26px; position: relative; display: flex; align-items: center; justify-content: center; cursor: pointer; }
.v-handle::before { content: ''; position: absolute; top: 8px; bottom: 8px; left: 50%; width: 2px; transform: translateX(-50%); background: #dbe3ec; border-radius: 2px; }
.knob-v { position: relative; display: flex; flex-direction: column; align-items: center; gap: 5px; padding: 12px 0; width: 24px; background: #fff; border: 1px solid #cdd9e8; border-radius: 10px; color: #1b65a8; box-shadow: 0 3px 10px rgba(27,101,168,0.16); transition: all 0.15s ease; }
.knob-v span { font-size: 15px; font-weight: 800; line-height: 1; } .knob-v em { font-style: normal; font-size: 11px; font-weight: 600; writing-mode: vertical-rl; letter-spacing: 2px; color: #5a6b80; }
.v-handle:hover .knob-v { background: #1b65a8; border-color: #1b65a8; color: #fff; box-shadow: 0 4px 14px rgba(27,101,168,0.32); } .v-handle:hover .knob-v em { color: #eaf2fb; }

/* 右列: 配送线路, 占满全高, 卡片内部滚动 */
.rv-right { flex: 0 0 360px; height: 100%; display: flex; flex-direction: column; overflow: hidden; padding: 14px 16px; background: #fff; border: 1px solid #e6eaf0; border-radius: 12px; box-shadow: 0 2px 12px rgba(16,24,40,0.05); transition: flex-basis 0.22s ease; }
.rv-body.routes-editing .rv-right { flex-basis: 560px; }
.mr-routes-title { flex: 0 0 auto; display: flex; align-items: baseline; gap: 8px; margin-bottom: 10px; color: #101828; font-size: 15px; font-weight: 700; }
.mrt-sub { color: #98a2b3; font-size: 12px; font-weight: 500; font-variant-numeric: tabular-nums; }
.rv-right :deep(.route-cards) { grid-template-columns: 1fr; overflow-y: auto; flex: 1 1 auto; padding-right: 6px; align-content: start; }
@media (max-width: 1180px) {
  .rv-body { flex-direction: column; height: auto; }
  .rv-left { gap: 8px; }
  .mr-map { height: 480px; }
  .v-handle { display: none; }
  .rv-right { flex-basis: auto; width: 100%; height: auto; }
  .rv-right :deep(.route-cards) { grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); max-height: 420px; }
} h1,h2 { margin: 0; color: #101828; } .page-header p, .map-heading p { margin: 4px 0 0; color: #667085; } .map-step { display: grid; gap: 12px; } .map-heading label { display: grid; grid-template-columns: auto minmax(150px, 260px); align-items: center; gap: 12px; color: #344054; font-size: 14px; font-weight: 650; } .map-controls { display: flex; align-items: center; gap: 20px; flex-wrap: wrap; } .map-heading .opt-mode { grid-template-columns: auto auto; } .route-settings { display: flex; align-items: center; gap: 18px; flex-wrap: wrap; padding: 12px 16px; background: #f4f6f9; border: 1px solid #edf2f7; border-radius: 10px; } .route-settings.collapsed { padding: 6px 12px; } .route-settings .settings-title { color: #101828; font-weight: 700; font-size: 14px; }
.settings-toggle { display: inline-flex; align-items: center; gap: 8px; margin-left: auto; padding: 4px 10px; background: transparent; border: 1px solid #dbe3ec; border-radius: 6px; cursor: pointer; font: inherit; color: #475569; } .settings-toggle:hover { background: #e8edf3; } .settings-chevron { color: #1b65a8; font-size: 12px; font-weight: 650; } .route-settings label { display: grid; grid-template-columns: auto auto; align-items: center; gap: 10px; color: #344054; font-size: 14px; font-weight: 650; } .route-settings .view-toggle { grid-template-columns: auto auto; } .route-settings .generate-button { padding: 8px 16px; } .generate-button, .next-button { width: fit-content; padding: 10px 18px; color: #fff; font: inherit; font-weight: 650; background: #1b65a8; border: 0; border-radius: 6px; cursor: pointer; } .action-bar { position: sticky; bottom: 0; z-index: 20; padding: 14px 0; background: linear-gradient(to bottom, transparent, #f8fafc 28%); }
.unresolved-panel { display: grid; gap: 8px; padding: 16px 20px; background: #fffaeb; border: 1px solid #fef0c7; border-radius: 10px; } .unresolved-panel strong { color: #b54708; } .unresolved-panel ul { display: grid; gap: 4px; margin: 0; padding-left: 20px; color: #93370d; font-size: 13px; } .unresolved-panel a { width: fit-content; color: #1b65a8; font-weight: 650; }
@media (max-width: 720px) { .workbench-page { padding: 16px; } .page-header,.map-heading { align-items: flex-start; flex-direction: column; } .map-heading label { width: 100%; } }
</style>
