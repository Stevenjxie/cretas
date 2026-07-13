<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { vReveal } from '@/composables/useReveal';
import type { RouteTrip, StoreOrder, Vehicle } from '../types';
import { etaLabel, parseWindow, tripEtas, type StopEta } from '../routeEta';

// 人工确认 = 一次核对 *全部* 车次: 紧凑列表(方案 A) 一行看全整体匹配进度, 点击行才展开门店明细核对顺序,
// 逐一确认或批量/一键确认全部。(卡片式改进前版本每张卡固定展开, 4 车次一屏放不下要滚动。)
const props = defineProps<{
  trips: RouteTrip[];
  stores: StoreOrder[];
  vehicles: Vehicle[];
  selectedTripId: string | null;
  /** 车次操作错误(如 409 版本冲突), 非空时列表上方显示 sticky 错误提示。 */
  error?: string | null;
}>();

const emit = defineEmits<{
  (event: 'select-trip', tripId: string): void;
  (event: 'move-store', tripId: string, storeId: string, direction: -1 | 1): void;
  (event: 'move-to-trip', tripId: string, storeId: string, targetTripId: string | null): void;
  (event: 'assign-vehicle', tripId: string, vehicleId: string | null): void;
  (event: 'assign-driver', tripId: string, driverId: string | null): void;
  (event: 'confirm-trip', tripId: string): void;
  (event: 'confirm-all'): void;
}>();

const storeById = computed(() => new Map(props.stores.map((s) => [s.id, s])));
function storeName(id: string): string {
  return storeById.value.get(id)?.name ?? id;
}

// 每个车次每站的预计到达/迟到（复用与步骤2/后端优化器一致的到达模型）
const etasByTrip = computed(() => {
  const m = new Map<string, StopEta[]>();
  for (const t of props.trips) m.set(t.id, tripEtas(t, (id) => parseWindow(storeById.value.get(id)?.window)));
  return m;
});
function etaAt(tripId: string, i: number): StopEta | undefined {
  return etasByTrip.value.get(tripId)?.[i];
}
function lateCount(tripId: string): number {
  return (etasByTrip.value.get(tripId) ?? []).filter((e) => e.late).length;
}

/** 某车次可选司机 = 该车辆自身司机（车辆↔司机绑定在车辆主数据里）。 */
function driverOptions(trip: RouteTrip): Vehicle[] {
  return props.vehicles.filter((v) => v.id === trip.vehicleId && v.driverId);
}
function backupDriversText(trip: RouteTrip): string {
  return props.vehicles.find((v) => v.id === trip.vehicleId)?.backupDrivers?.join('、') ?? '';
}

const statusLabels: Record<RouteTrip['status'], string> = {
  draft: '待确认',
  needs_vehicle: '待匹配车辆',
  needs_driver: '待匹配司机',
  needs_route_data: '缺少路线数据',
  confirmed: '已确认',
};

/** 装载率 → 徽标颜色(仅展示用, 不影响业务判断)。 */
function loadRateTagType(rate: number): 'success' | 'warning' | 'danger' {
  const pct = rate * 100;
  if (pct >= 90) return 'danger';
  if (pct >= 80) return 'warning';
  return 'success';
}

/** 车次固定的「线路 NN」编号 —— 按全量 trips 的原始顺序算, 不随筛选变动。 */
const lineLabelById = computed(() => {
  const m = new Map<string, string>();
  props.trips.forEach((t, i) => m.set(t.id, String(i + 1).padStart(2, '0')));
  return m;
});
function lineLabel(tripId: string): string {
  return lineLabelById.value.get(tripId) ?? '--';
}

function storesPreview(trip: RouteTrip): string {
  const names = trip.storeIds.map(storeName);
  if (names.length <= 2) return names.join(' → ');
  return `${names[0]} → … → ${names[names.length - 1]}`;
}

// 顶部进度: 总车次 / 已确认 / 待匹配车辆或司机
const totalTrips = computed(() => props.trips.length);
const confirmedCount = computed(() => props.trips.filter((t) => t.status === 'confirmed').length);
const unmatchedCount = computed(() => props.trips.filter((t) => !t.vehicleId || !t.driverId).length);
// 还有「可确认但未确认」的草稿车次时, 才显示一键确认
const confirmableTrips = computed(() => props.trips.filter((t) => t.status === 'draft'));

// ------------------------------------------------------------------
// 筛选: 全部 / 待确认 / 待匹配(车或司机) / 已确认
// ------------------------------------------------------------------
type StatusFilter = 'all' | 'draft' | 'unmatched' | 'confirmed';
const statusFilter = ref<StatusFilter>('all');
const unmatchedTripsList = computed(() =>
  props.trips.filter((t) => t.status === 'needs_vehicle' || t.status === 'needs_driver' || t.status === 'needs_route_data'),
);
const confirmedTripsList = computed(() => props.trips.filter((t) => t.status === 'confirmed'));
const filteredTrips = computed(() => {
  switch (statusFilter.value) {
    case 'draft': return confirmableTrips.value;
    case 'unmatched': return unmatchedTripsList.value;
    case 'confirmed': return confirmedTripsList.value;
    default: return props.trips;
  }
});
function setStatusFilter(v: string | number | boolean): void {
  statusFilter.value = v as StatusFilter;
}

// ------------------------------------------------------------------
// 展开/收起: 未确认车次默认展开(需要处理), 已确认默认收起(不用看)。
// 只在「第一次见到某车次 id」时套用默认值, 之后用户手动折叠/展开不会被覆盖。
// ------------------------------------------------------------------
const expandedIds = ref<Set<string>>(new Set());
const seenTripIds = new Set<string>();
function isExpanded(tripId: string): boolean {
  return expandedIds.value.has(tripId);
}
function toggleExpand(tripId: string): void {
  const next = new Set(expandedIds.value);
  if (next.has(tripId)) next.delete(tripId); else next.add(tripId);
  expandedIds.value = next;
}

// ------------------------------------------------------------------
// 批量选择(仅待确认车次可选) + 「确认选中」
// ------------------------------------------------------------------
const selectedIds = ref<Set<string>>(new Set());
function toggleSelect(tripId: string): void {
  const next = new Set(selectedIds.value);
  if (next.has(tripId)) next.delete(tripId); else next.add(tripId);
  selectedIds.value = next;
}
const selectableTrips = computed(() => filteredTrips.value.filter((t) => t.status === 'draft'));
const allSelectableSelected = computed(
  () => selectableTrips.value.length > 0 && selectableTrips.value.every((t) => selectedIds.value.has(t.id)),
);
const someSelectableSelected = computed(
  () => !allSelectableSelected.value && selectableTrips.value.some((t) => selectedIds.value.has(t.id)),
);
function toggleSelectAll(): void {
  const next = new Set(selectedIds.value);
  if (allSelectableSelected.value) selectableTrips.value.forEach((t) => next.delete(t.id));
  else selectableTrips.value.forEach((t) => next.add(t.id));
  selectedIds.value = next;
}
const selectedDraftCount = computed(
  () => confirmableTrips.value.filter((t) => selectedIds.value.has(t.id)).length,
);

// 逐车次确认的即时反馈：点击后按钮立即转 loading（避免后端返回慢时用户以为没响应），
// 直到该车次 status 变 confirmed（新 snapshot 到达）后自动清除。
// 「确认选中」复用同一套 loading 集合, 但内部用队列**串行**逐个 emit confirm-trip ——
// 父级 confirmTrip(tripId) 内部是 selectTrip(id) 再 confirmTrip() 一步, 并发触发会互相
// 抢 selectedTripId, 必须等上一个真正变 confirmed 才发下一个, 等价于「依次手动点」。
const confirmingIds = ref<Set<string>>(new Set());
const batchQueue = ref<string[]>([]);
const batchConfirming = computed(() => batchQueue.value.length > 0);

function onConfirmTrip(tripId: string): void {
  const next = new Set(confirmingIds.value);
  next.add(tripId);
  confirmingIds.value = next;
  emit('confirm-trip', tripId);
}

function runNextInQueue(): void {
  const nextId = batchQueue.value[0];
  if (nextId) onConfirmTrip(nextId);
}

/** 「确认选中(N)」— 若选中的正好是全部待确认车次, 直接走 confirm-all(后端一次性确认); 否则串行队列。 */
function confirmSelectedTrips(): void {
  if (batchConfirming.value) return;
  const ids = confirmableTrips.value.filter((t) => selectedIds.value.has(t.id)).map((t) => t.id);
  if (!ids.length) return;
  const isAllDraft = ids.length === confirmableTrips.value.length;
  if (isAllDraft && unmatchedCount.value === 0) {
    emit('confirm-all');
    return;
  }
  batchQueue.value = ids;
  runNextInQueue();
}

watch(() => props.trips, (trips) => {
  // 单车确认 loading 清除
  if (confirmingIds.value.size) {
    const next = new Set(confirmingIds.value);
    let changed = false;
    for (const id of next) {
      const t = trips.find((x) => x.id === id);
      if (!t || t.status === 'confirmed') { next.delete(id); changed = true; }
    }
    if (changed) confirmingIds.value = next;
  }

  // 批量队列: 队首确认成功后, 出队并串行发下一个
  if (batchQueue.value.length) {
    const headId = batchQueue.value[0];
    const head = trips.find((x) => x.id === headId);
    if (!head || head.status === 'confirmed') {
      batchQueue.value = batchQueue.value.slice(1);
      if (batchQueue.value.length) nextTick(() => runNextInQueue());
    }
  }

  // 选中集合: 车次消失或已确认后自动移出勾选(避免"确认选中"计数对不上)
  if (selectedIds.value.size) {
    const ids = new Set(trips.map((t) => t.id));
    const next = new Set(selectedIds.value);
    let changed = false;
    for (const id of next) {
      const t = trips.find((x) => x.id === id);
      if (!ids.has(id) || !t || t.status !== 'draft') { next.delete(id); changed = true; }
    }
    if (changed) selectedIds.value = next;
  }

  // 展开状态默认值: 新出现的车次, 未确认默认展开, 已确认默认收起
  for (const t of trips) {
    if (!seenTripIds.has(t.id)) {
      seenTripIds.add(t.id);
      if (t.status !== 'confirmed') {
        const next = new Set(expandedIds.value);
        next.add(t.id);
        expandedIds.value = next;
      }
    }
  }
}, { deep: true, immediate: true });

function otherTrips(trip: RouteTrip): RouteTrip[] {
  return props.trips.filter((c) => c.id !== trip.id);
}

function moveToTrip(trip: RouteTrip, storeId: string, event: Event): void {
  const select = event.target as HTMLSelectElement;
  const value = select.value;
  select.value = ''; // 重置为占位项, 避免下拉框停留在"已选中"的错觉
  if (!value) return;
  emit('move-to-trip', trip.id, storeId, value === '__new__' ? null : value);
}
</script>

<template>
  <section data-testid="confirm-step" class="confirm-step">
    <el-alert
      v-if="error"
      data-testid="confirm-error-alert"
      type="error"
      :closable="false"
      show-icon
      :title="error"
      class="confirm-error"
    />

    <header class="confirm-head">
      <div><p>第三步</p><h2>人工确认</h2><span>为每个车次匹配车辆、司机，核对配送顺序后逐一确认（或一键确认全部）。</span></div>
      <div class="confirm-head-actions">
        <el-button
          v-if="selectedDraftCount > 0 || batchConfirming"
          data-testid="confirm-selected-trips"
          :loading="batchConfirming"
          :disabled="batchConfirming"
          @click="confirmSelectedTrips"
        >{{ batchConfirming ? '确认中…' : `确认选中（${selectedDraftCount}）` }}</el-button>
        <el-button
          v-if="confirmableTrips.length > 0 && unmatchedCount === 0"
          type="primary"
          data-testid="confirm-all-trips"
          @click="emit('confirm-all')"
        >一键确认全部（{{ confirmableTrips.length }}）</el-button>
      </div>
    </header>

    <div v-if="totalTrips > 0" class="confirm-toolbar">
      <div class="confirm-progress" data-testid="confirm-progress">
        <div class="cp-item"><span class="cp-value">{{ totalTrips }}</span><span class="cp-label">车次</span></div>
        <div class="cp-sep" />
        <div class="cp-item"><span class="cp-value">{{ confirmedCount }}/{{ totalTrips }}</span><span class="cp-label">已确认</span></div>
        <div class="cp-sep" />
        <div class="cp-item" :class="{ warn: unmatchedCount > 0 }"><span class="cp-value">{{ unmatchedCount }}</span><span class="cp-label">待匹配车辆/司机</span></div>
      </div>

      <el-radio-group
        :model-value="statusFilter"
        size="small"
        data-testid="confirm-status-filter"
        class="confirm-filter"
        @update:model-value="setStatusFilter"
      >
        <el-radio-button label="all">全部 {{ totalTrips }}</el-radio-button>
        <el-radio-button label="draft">待确认 {{ confirmableTrips.length }}</el-radio-button>
        <el-radio-button label="unmatched">待匹配 {{ unmatchedTripsList.length }}</el-radio-button>
        <el-radio-button label="confirmed">已确认 {{ confirmedTripsList.length }}</el-radio-button>
      </el-radio-group>
    </div>

    <p v-if="totalTrips === 0" class="empty-hint" data-testid="confirm-empty">还没有生成任何车次，请先回到「查看路线」生成配送方案。</p>
    <p v-else-if="filteredTrips.length === 0" class="empty-hint" data-testid="confirm-filter-empty">当前筛选条件下没有车次，试试切换上方筛选。</p>

    <div v-else class="trip-table" data-testid="confirm-trip-table">
      <div class="tt-head tt-grid">
        <span class="tt-check">
          <el-checkbox
            :model-value="allSelectableSelected"
            :indeterminate="someSelectableSelected"
            :disabled="selectableTrips.length === 0 || batchConfirming"
            aria-label="全选待确认车次"
            @change="toggleSelectAll"
          />
        </span>
        <span>车次</span>
        <span>门店数</span>
        <span>里程</span>
        <span>装载</span>
        <span>配送顺序预览</span>
        <span>车辆</span>
        <span>司机</span>
        <span>状态</span>
        <span>操作</span>
      </div>

      <article
        v-for="(trip, index) in filteredTrips"
        :key="trip.id"
        v-reveal="Math.min(index, 8)"
        data-testid="confirm-trip-card"
        :data-trip-id="trip.id"
        :class="['trip-row', { selected: trip.id === selectedTripId, confirmed: trip.status === 'confirmed' }]"
      >
        <div class="tt-row-main tt-grid" @click="emit('select-trip', trip.id)">
          <span class="tt-cell tt-check" @click.stop>
            <el-checkbox
              :model-value="selectedIds.has(trip.id)"
              :disabled="trip.status !== 'draft' || batchConfirming"
              aria-label="选中该车次"
              @change="toggleSelect(trip.id)"
            />
          </span>
          <button type="button" class="tt-cell tt-route" data-field="车次" @click.stop="toggleExpand(trip.id)">
            <span class="chevron" :class="{ open: isExpanded(trip.id) }">▶</span>
            <span class="tt-route-label">线路 {{ lineLabel(trip.id) }}</span>
            <span
              v-if="trip.vehicleTripSeq && trip.vehicleTripSeq > 1"
              class="tc-seq"
              :title="`该车第 ${trip.vehicleTripSeq} 趟 · 回仓补货`"
            >第 {{ trip.vehicleTripSeq }} 趟</span>
          </button>
          <span class="tt-cell" data-field="门店数">{{ trip.storeIds.length }} 店</span>
          <span class="tt-cell" data-field="里程">{{ trip.totalDistanceKm.toFixed(1) }} km</span>
          <span class="tt-cell" data-field="装载"><el-tag :type="loadRateTagType(trip.loadRate)" size="small">{{ Math.round(trip.loadRate * 100) }}%</el-tag></span>
          <span class="tt-cell tt-preview" data-field="配送顺序">{{ storesPreview(trip) }}</span>
          <span class="tt-cell" data-field="车辆" @click.stop>
            <el-select
              :model-value="trip.vehicleId"
              clearable
              size="small"
              placeholder="待匹配车辆"
              data-testid="confirm-vehicle-select"
              @update:model-value="emit('assign-vehicle', trip.id, $event)"
            >
              <el-option v-for="v in vehicles" :key="v.id" :label="`${v.plate} · ${v.capacityCbm}m³`" :value="v.id" />
            </el-select>
          </span>
          <span class="tt-cell" data-field="司机" @click.stop>
            <el-select
              :model-value="trip.driverId"
              clearable
              size="small"
              placeholder="请选择司机"
              data-testid="confirm-driver-select"
              :disabled="!trip.vehicleId"
              @update:model-value="emit('assign-driver', trip.id, $event)"
            >
              <el-option v-for="v in driverOptions(trip)" :key="v.driverId ?? ''" :label="v.driverName" :value="v.driverId" />
            </el-select>
          </span>
          <span class="tt-cell" data-field="状态"><span :class="['tc-status', `status-${trip.status}`]">{{ statusLabels[trip.status] }}</span></span>
          <span class="tt-cell tt-actions" data-field="操作" @click.stop>
            <el-button
              type="primary"
              size="small"
              :plain="trip.status === 'confirmed'"
              data-testid="confirm-trip"
              :loading="confirmingIds.has(trip.id)"
              :disabled="trip.status !== 'draft' || confirmingIds.has(trip.id) || batchConfirming"
              @click="onConfirmTrip(trip.id)"
            >{{ trip.status === 'confirmed' ? '✓ 已确认' : (confirmingIds.has(trip.id) ? '确认中…' : '确认') }}</el-button>
          </span>
        </div>

        <div v-if="isExpanded(trip.id)" class="tt-detail">
          <p v-if="lateCount(trip.id) > 0" data-testid="confirm-late-warning" class="late-warning">
            ⚠️ {{ lateCount(trip.id) }} 家门店预计晚于配送时间（下方标红）。可上移/下移调整，或移至其他车次。
          </p>
          <ol>
            <li v-for="(storeId, i) in trip.storeIds" :key="storeId" class="stop" :class="{ late: etaAt(trip.id, i)?.late }">
              <div class="stop-main">
                <span class="seq-no">{{ i + 1 }}</span>
                <span class="store-name">{{ storeName(storeId) }}</span>
                <span v-if="etaAt(trip.id, i)?.etaMin != null" class="store-eta">{{ etaLabel(etaAt(trip.id, i)) }}<span v-if="etaAt(trip.id, i)?.late"> · 迟到</span></span>
              </div>
              <div class="stop-actions">
                <el-button text size="small" :disabled="i === 0" @click.stop="emit('move-store', trip.id, storeId, -1)">上移</el-button>
                <el-button text size="small" :disabled="i === trip.storeIds.length - 1" @click.stop="emit('move-store', trip.id, storeId, 1)">下移</el-button>
                <select
                  v-if="otherTrips(trip).length"
                  class="move-to-trip-select"
                  aria-label="移至其他车次"
                  @click.stop
                  @change="moveToTrip(trip, storeId, $event)"
                >
                  <option value="">移至其他车次…</option>
                  <option v-for="c in otherTrips(trip)" :key="c.id" :value="c.id">第 {{ c.tripNo }} 趟</option>
                  <option value="__new__">新建待匹配车次</option>
                </select>
              </div>
            </li>
          </ol>
          <p v-if="backupDriversText(trip)" class="backup">备用司机：{{ backupDriversText(trip) }}</p>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped lang="scss">
.confirm-step { display: grid; gap: 16px; }
.confirm-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.confirm-head p { margin: 0 0 6px; color: #1b65a8; font-size: 13px; font-weight: 750; }
.confirm-head h2 { margin: 0; color: #1a2332; }
.confirm-head span { color: #7a8599; font-size: 13px; }
.confirm-head-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }

.confirm-error { border-radius: 10px; }

.confirm-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }

.confirm-progress { display: flex; align-items: center; gap: 20px; padding: 12px 20px; background: linear-gradient(180deg, #ffffff, #f8fafc); border: 1px solid #edf2f7; border-radius: 10px; box-shadow: 0 2px 12px rgba(27,101,168,0.06); }
.cp-item { display: flex; flex-direction: column; gap: 2px; }
.cp-value { color: #1a2332; font-size: 20px; font-weight: 750; line-height: 1.1; font-variant-numeric: tabular-nums; }
.cp-label { color: #7a8599; font-size: 12px; }
.cp-item.warn .cp-value { color: #b54708; }
.cp-sep { width: 1px; height: 26px; background: #edf2f7; }

.confirm-filter { flex-shrink: 0; }

.empty-hint { padding: 40px 20px; text-align: center; color: #7a8599; background: #fff; border: 1px dashed #d0d5dd; border-radius: 12px; }

/* ---- 紧凑列表(方案 A): 车次一行看全, 点击行展开门店明细 ---- */
.trip-table { background: #fff; border: 1px solid #edf2f7; border-radius: 10px; box-shadow: 0 2px 12px rgba(27,101,168,0.06); overflow: hidden; }

.tt-grid { display: grid; grid-template-columns: 28px 128px 56px 76px 64px 1fr 150px 140px 96px 108px; align-items: center; gap: 10px; }

.tt-head { padding: 11px 16px; color: #475467; font-size: 11.5px; font-weight: 700; background: #f4f7fa; border-bottom: 2px solid #dfe4ea; }

.trip-row { border-bottom: 1px solid #e4e8ee; }
.trip-row:last-child { border-bottom: none; }
/* 斑马纹: 隔行浅底, 让每一行边界分明 */
.trip-row:nth-child(even) .tt-row-main { background: #f8fafc; }
.trip-row.selected { background: #ecf5ff; }
.trip-row.selected .tt-row-main { background: transparent; }
.trip-row.confirmed .tt-route-label { color: #1e8449; }

.tt-row-main { padding: 12px 16px; cursor: pointer; transition: background-color 0.15s ease; }
.tt-row-main:hover { background: #eef4fc; }
.trip-row.selected .tt-row-main:hover { background: #e3eefb; }

/* 列竖线: 各列之间加浅分隔线, 让列边界清晰(表头 + 数据行都加) */
.tt-grid > * { position: relative; }
.tt-grid > *:not(:last-child)::after { content: ''; position: absolute; top: -12px; bottom: -12px; right: -5px; width: 1px; background: #eef1f5; }
.tt-head .tt-grid > *:not(:last-child)::after { top: -11px; bottom: -11px; }

.tt-cell { min-width: 0; color: #1a2332; font-size: 12.5px; font-variant-numeric: tabular-nums; }
.tt-cell .el-select { width: 100%; }
.tt-check { display: flex; align-items: center; }

.tt-route { display: flex; align-items: center; gap: 6px; padding: 0; color: #1a2332; font-size: 13.5px; font-weight: 750; background: none; border: none; cursor: pointer; text-align: left; font-family: inherit; }
.tt-route:hover { color: #1b65a8; }
.chevron { display: inline-block; font-size: 10px; color: #7a8599; transition: transform 0.15s ease; }
.chevron.open { transform: rotate(90deg); }
.tt-route-label { white-space: nowrap; }

.tt-preview { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #7a8599; }

.tt-actions { display: flex; justify-content: flex-end; }

.tt-detail { padding: 4px 16px 16px 46px; background: #fafbfc; border-top: 1px dashed #edf2f7; }

/* 复用: 状态徽标 / 停靠点 / 迟到警告 / 移至其他车次 / 备用司机提示 */
.tc-seq { padding: 1px 6px; color: #7c3aed; font-size: 10.5px; font-weight: 700; background: #f5f3ff; border: 1px solid #ddd6fe; border-radius: 999px; white-space: nowrap; }
.tc-status { padding: 3px 9px; border-radius: 999px; font-size: 12px; font-weight: 700; white-space: nowrap; }
.status-draft { color: #175cd3; background: #eff8ff; }
.status-needs_vehicle { color: #b54708; background: #fffaeb; }
.status-needs_driver { color: #b54708; background: #fef6ee; }
.status-needs_route_data { color: #b42318; background: #fef3f2; }
.status-confirmed { color: #027a48; background: #ecfdf3; }

.tt-detail ol { display: grid; gap: 8px; margin: 0 0 10px; padding: 0; list-style: none; max-width: 720px; }
.stop { display: grid; gap: 6px; padding: 8px 10px; background: #fff; border: 1px solid #edf2f7; border-radius: 8px; }
.stop.late { background: #fef3f2; border-color: #fecdca; }
.stop-main { display: flex; align-items: baseline; gap: 8px; min-width: 0; }
.seq-no { align-self: center; display: grid; width: 22px; height: 22px; place-items: center; color: #fff; font-size: 12px; background: #1b65a8; border-radius: 50%; flex: 0 0 auto; }
.store-name { flex: 1 1 auto; min-width: 0; color: #344054; font-size: 13.5px; font-weight: 600; overflow-wrap: anywhere; line-height: 1.35; }
.store-eta { flex: 0 0 auto; font-size: 11px; font-weight: 600; color: #7a8599; white-space: nowrap; }
.stop.late .store-name { color: #b42318; font-weight: 700; }
.stop.late .store-eta { color: #b42318; font-weight: 700; }
.stop.late .seq-no { background: #b42318; }
.stop-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; padding-left: 30px; }
.late-warning { margin: 0 0 10px; padding: 8px 10px; color: #b42318; font-size: 12.5px; font-weight: 650; background: #fef3f2; border: 1px solid #fecdca; border-radius: 8px; }
.move-to-trip-select { max-width: 130px; padding: 4px 6px; color: #344054; font-size: 12px; background: #fff; border: 1px solid #edf2f7; border-radius: 6px; }
.backup { margin: 8px 0 0; padding: 8px 10px; color: #475467; font-size: 12.5px; background: #fff; border: 1px dashed #edf2f7; border-radius: 8px; }

@media (max-width: 1180px) {
  .tt-grid { grid-template-columns: 24px 112px 52px 68px 58px 130px 122px 84px 92px; }
  .tt-preview { display: none; }
  .trip-table { overflow-x: auto; }
}

@media (max-width: 640px) {
  .confirm-toolbar { flex-direction: column; align-items: stretch; }
  .tt-head { display: none; }
  .tt-grid { display: flex; flex-wrap: wrap; gap: 8px 14px; }
  .tt-row-main[class] { padding: 12px 14px; }
  .tt-row-main [data-field] { position: relative; padding-top: 13px; min-width: 92px; }
  .tt-row-main [data-field]::before { content: attr(data-field); position: absolute; top: -1px; left: 0; color: #94a3b8; font-size: 9.5px; }
  .tt-route { min-width: 100%; }
  .tt-actions { min-width: 100%; justify-content: flex-start; }
  .tt-detail { padding-left: 16px; }
}
</style>
