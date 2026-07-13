<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { vReveal } from '@/composables/useReveal';
import type { RouteTrip, StoreOrder, Vehicle } from '../types';

const props = defineProps<{
  stores: StoreOrder[];
  trips: RouteTrip[];
  vehicles?: Vehicle[];
  selectedTripId: string | null;
  selectedStoreId: string | null;
  /** 只读展示(调度记录详情视图): 隐藏派车/司机下拉、确认按钮、门店编辑, 车辆/司机以文字显示。 */
  readonly?: boolean;
}>();

const emit = defineEmits<{
  (event: 'select-trip', tripId: string): void;
  (event: 'select-store', storeId: string): void;
  (event: 'assign-vehicle', tripId: string, vehicleId: string | null): void;
  (event: 'assign-driver', tripId: string, driverId: string | null): void;
  (event: 'confirm-trip', tripId: string): void;
  (event: 'move-store', tripId: string, storeId: string, direction: -1 | 1): void;
  (event: 'move-to-trip', tripId: string, storeId: string, targetTripId: string | null): void;
  /** 有任意车次进入门店编辑态 → 父级把右列加宽以放下编辑控件。 */
  (event: 'editing-change', editing: boolean): void;
}>();

// ========== 派车 / 司机 / 确认 / 门店编辑(原「人工确认」步合并进来) ==========
/** 某车次可选司机 = 该车辆自身绑定的司机(车辆↔司机在车辆主数据里)。 */
function driverOptions(trip: RouteTrip): Vehicle[] {
  return (props.vehicles ?? []).filter((v) => v.id === trip.vehicleId && v.driverId);
}

/** 逐车确认的即时反馈: 点击后按钮立即 loading, 直到该车次 status 变 confirmed(新 snapshot 到达)后清除。 */
const confirmingIds = ref<Set<string>>(new Set());
function onConfirmTrip(tripId: string): void {
  const next = new Set(confirmingIds.value);
  next.add(tripId);
  confirmingIds.value = next;
  emit('confirm-trip', tripId);
}
watch(() => props.trips, (trips) => {
  if (!confirmingIds.value.size) return;
  const next = new Set(confirmingIds.value);
  let changed = false;
  for (const id of next) {
    const t = trips.find((x) => x.id === id);
    if (!t || t.status === 'confirmed') { next.delete(id); changed = true; }
  }
  if (changed) confirmingIds.value = next;
}, { deep: true });

/** 门店顺序编辑态(防呆闸): 点「调整门店顺序」才进编辑态, 才出现上移/下移 + 移至其他车次。 */
const editingIds = ref<Set<string>>(new Set());
function isEditing(tripId: string): boolean {
  return editingIds.value.has(tripId);
}
function toggleEdit(tripId: string): void {
  const next = new Set(editingIds.value);
  if (next.has(tripId)) next.delete(tripId); else next.add(tripId);
  editingIds.value = next;
}
// 编辑态开合 → 通知父级加宽/还原右列(放下 上移/下移 + 移至 控件)
watch(() => editingIds.value.size, (n) => emit('editing-change', n > 0));

/** 固定「线路 NN」编号(按 trips 原始顺序), 移至下拉里标目标车次用。 */
const lineNoById = computed(() => {
  const m = new Map<string, string>();
  props.trips.forEach((t, i) => m.set(t.id, String(i + 1).padStart(2, '0')));
  return m;
});
function lineNo(tripId: string): string {
  return lineNoById.value.get(tripId) ?? '--';
}

function otherTrips(trip: RouteTrip): RouteTrip[] {
  return props.trips.filter((c) => c.id !== trip.id);
}
function onMoveToTrip(trip: RouteTrip, storeId: string, event: Event): void {
  const select = event.target as HTMLSelectElement;
  const value = select.value;
  select.value = ''; // 重置回占位项, 避免下拉停留在"已选中"错觉
  if (!value) return;
  emit('move-to-trip', trip.id, storeId, value === '__new__' ? null : value);
}

const storesById = computed(() => new Map(props.stores.map((store) => [store.id, store])));

const statusLabels: Record<RouteTrip['status'], string> = {
  draft: '待确认',
  needs_vehicle: '待匹配车辆',
  needs_driver: '待匹配司机',
  needs_route_data: '缺少路线数据',
  confirmed: '已确认',
};

function storeName(storeId: string): string {
  return storesById.value.get(storeId)?.name ?? storeId;
}

function selectTrip(tripId: string): void {
  emit('select-trip', tripId);
}

// ========== 档1-C 时间窗可行性（预计到达 + 超窗标红） ==========
const DWELL_MIN = 10; // 每站卸货停留（分钟，假设）

function parseHm(hm: string | undefined): number | null {
  if (!hm) return null;
  const m = /^(\d{1,2}):(\d{2})/.exec(hm.trim());
  if (!m) return null;
  return Number(m[1]) * 60 + Number(m[2]);
}

function fmtHm(min: number): string {
  const t = ((Math.round(min) % 1440) + 1440) % 1440;
  return `${String(Math.floor(t / 60)).padStart(2, '0')}:${String(t % 60).padStart(2, '0')}`;
}

/** 门店 window 字符串 "HH:MM-HH:MM" → {start,end}（分钟）。 */
function storeWindow(storeId: string): { start: number | null; end: number | null } {
  const w = storesById.value.get(storeId)?.window ?? '';
  const [s, e] = w.split('-');
  return { start: parseHm(s), end: parseHm(e) };
}

interface StopEta {
  storeId: string;
  etaMin: number | null;
  late: boolean;
}

/**
 * 每站预计到达（分钟）: 用车次真实总时长(高德)按各段里程比例分摊 + 每站停留。
 * 出发时间取"首站按其窗口开始到达"倒推，之后累计；某站预计到达晚于其窗口结束 → 迟到标红。
 * 纯前端估算(标"预计")，非承诺。缺时长/里程/窗口 → 不判定(不误报)。
 */
function tripEtas(trip: RouteTrip): StopEta[] {
  const ids = trip.storeIds;
  const segs = trip.segmentDistances ?? [];
  const total = segs.reduce((a, b) => a + (b || 0), 0);
  const dur = trip.durationMin;
  if (!ids.length || !dur || total <= 0 || segs.length < ids.length) {
    return ids.map((storeId): StopEta => ({ storeId, etaMin: null, late: false }));
  }
  // 首站窗口开始 - 首段行驶 = 出发；使 arrival(首站)=窗口开始（准点）。
  const firstStart = storeWindow(ids[0]).start;
  const travelTo = (i: number): number => {
    const cum = segs.slice(0, i + 1).reduce((a, b) => a + (b || 0), 0);
    return dur * (cum / total) + i * DWELL_MIN;
  };
  const depart = (firstStart ?? 8 * 60) - travelTo(0);
  return ids.map((storeId, i) => {
    const eta = depart + travelTo(i);
    const end = storeWindow(storeId).end;
    return { storeId, etaMin: eta, late: end != null && eta > end };
  });
}

function tripLateCount(trip: RouteTrip): number {
  return tripEtas(trip).filter((e) => e.late).length;
}

function etaLabel(eta: StopEta): string {
  return eta.etaMin == null ? '' : `预计 ${fmtHm(eta.etaMin)}`;
}

const etasByTrip = computed(() => {
  const m = new Map<string, StopEta[]>();
  for (const t of props.trips) m.set(t.id, tripEtas(t));
  return m;
});

function etaAt(tripId: string, i: number): StopEta | undefined {
  return etasByTrip.value.get(tripId)?.[i];
}

function etaTitleFor(tripId: string, i: number): string {
  const e = etaAt(tripId, i);
  return e ? etaLabel(e) : '';
}
</script>

<template>
  <section class="route-cards" aria-label="配送线路">
    <article
      v-for="(trip, index) in trips"
      :key="trip.id"
      v-reveal="Math.min(index, 8)"
      data-testid="route-card"
      :data-trip-id="trip.id"
      :class="['route-card', { selected: trip.id === selectedTripId }]"
      @click="selectTrip(trip.id)"
    >
      <header class="card-header">
        <button
          type="button"
          data-testid="route-select"
          :data-trip-id="trip.id"
          class="route-select-button"
          :aria-pressed="trip.id === selectedTripId"
          :aria-label="`选择线路 ${index + 1}，第 ${trip.tripNo} 趟配送`"
          @click.stop="selectTrip(trip.id)"
        >
          <span class="route-eyebrow">线路 {{ String(index + 1).padStart(2, '0') }}</span>
          <span class="route-title">第 {{ trip.tripNo }} 趟配送</span>
        </button>
        <span :class="['route-status', `status-${trip.status}`]">
          {{ statusLabels[trip.status] }}
        </span>
      </header>

      <!-- 只读展示(调度记录详情): 车辆/司机以文字显示 -->
      <div v-if="readonly" class="vehicle-row">
        <span class="field-label">车辆</span>
        <strong>{{ trip.vehiclePlate ?? '—' }}</strong>
        <span class="field-label">司机</span>
        <strong>{{ trip.driverName ?? '—' }}</strong>
        <span v-if="trip.vehicleTripSeq && trip.vehicleTripSeq > 1" class="trip-seq">该车第 {{ trip.vehicleTripSeq }} 趟</span>
      </div>
      <!-- 可编辑(排线工作台): 派车/司机下拉 -->
      <div v-else class="assign-row" @click.stop>
        <div class="assign-fld">
          <span class="field-label">车辆</span>
          <el-select
            :model-value="trip.vehicleId"
            clearable
            size="small"
            placeholder="待匹配车辆"
            data-testid="confirm-vehicle-select"
            @update:model-value="emit('assign-vehicle', trip.id, $event)"
          >
            <el-option v-for="v in (vehicles ?? [])" :key="v.id" :label="`${v.plate} · ${v.capacityCbm}m³`" :value="v.id" />
          </el-select>
        </div>
        <div class="assign-fld">
          <span class="field-label">司机</span>
          <el-select
            :model-value="trip.driverId"
            clearable
            size="small"
            placeholder="请选择司机"
            :disabled="!trip.vehicleId"
            data-testid="confirm-driver-select"
            @update:model-value="emit('assign-driver', trip.id, $event)"
          >
            <el-option v-for="v in driverOptions(trip)" :key="v.driverId ?? ''" :label="v.driverName" :value="v.driverId" />
          </el-select>
        </div>
        <span v-if="trip.vehicleTripSeq && trip.vehicleTripSeq > 1" class="trip-seq" data-testid="trip-seq">该车第 {{ trip.vehicleTripSeq }} 趟</span>
      </div>

      <div v-if="trip.plannedDepartMin != null" class="schedule-row" data-testid="trip-schedule">
        <span class="sched-item">🚚 出发 {{ fmtHm(trip.plannedDepartMin) }}</span>
        <span v-if="trip.returnToDepotMin != null" class="sched-item" :class="{ late: trip.lateReturn }">
          🏭 返仓 {{ fmtHm(trip.returnToDepotMin) }}<span v-if="trip.lateReturn"> · 迟到回仓</span>
        </span>
      </div>

      <p
        v-if="tripLateCount(trip) > 0"
        data-testid="route-late-warning"
        class="late-warning"
      >
        ⚠️ {{ tripLateCount(trip) }} 家门店预计晚于配送时间（预计到达为估算）。可在「人工确认」拖动调整门店顺序，或为其改派车辆。
      </p>

      <div class="chain-head">
        <span class="chain-title">门店顺序（{{ trip.storeIds.length }}）</span>
        <template v-if="!readonly">
          <button
            v-if="!isEditing(trip.id)"
            type="button"
            class="edit-btn"
            data-testid="edit-stops"
            @click.stop="toggleEdit(trip.id)"
          >✎ 调整门店顺序</button>
          <button v-else type="button" class="done-btn" data-testid="edit-stops-done" @click.stop="toggleEdit(trip.id)">完成</button>
        </template>
      </div>

      <!-- 编辑态: 上移/下移 + 移至其他车次(防呆闸: 点「调整门店顺序」才出现) -->
      <div v-if="isEditing(trip.id)" class="store-chain edit" @click.stop>
        <template v-for="(storeId, storeIndex) in trip.storeIds" :key="storeId">
          <div class="estop" :class="{ late: etaAt(trip.id, storeIndex)?.late }">
            <span class="estop-nm">{{ storeIndex + 1 }}. {{ storeName(storeId) }}</span>
            <span v-if="etaAt(trip.id, storeIndex)?.etaMin != null" class="estop-eta">{{ etaTitleFor(trip.id, storeIndex) }}</span>
            <span class="estop-ops">
              <button type="button" class="mv-btn" :disabled="storeIndex === 0" title="上移" @click="emit('move-store', trip.id, storeId, -1)">▲</button>
              <button type="button" class="mv-btn" :disabled="storeIndex === trip.storeIds.length - 1" title="下移" @click="emit('move-store', trip.id, storeId, 1)">▼</button>
              <select
                v-if="otherTrips(trip).length"
                class="move-sel"
                aria-label="移至其他车次"
                @change="onMoveToTrip(trip, storeId, $event)"
              >
                <option value="">移至…</option>
                <option v-for="c in otherTrips(trip)" :key="c.id" :value="c.id">线路 {{ lineNo(c.id) }} · {{ c.vehiclePlate ?? '待匹配' }}</option>
                <option value="__new__">新建待匹配车次</option>
              </select>
            </span>
          </div>
          <span v-if="storeIndex < trip.storeIds.length - 1" class="chain-arrow" aria-hidden="true">↓</span>
        </template>
      </div>

      <!-- 只读态: 竖向门店列表(名左/预计右), 站间向下箭头 -->
      <div v-else class="store-chain" :aria-label="`线路 ${index + 1} 门店顺序`">
        <template v-for="(storeId, storeIndex) in trip.storeIds" :key="storeId">
          <button
            type="button"
            data-testid="route-store"
            :data-store-id="storeId"
            :class="['store-chip', {
              selected: storeId === selectedStoreId,
              late: etaAt(trip.id, storeIndex)?.late,
            }]"
            :title="etaTitleFor(trip.id, storeIndex)"
            @click.stop="emit('select-store', storeId)"
          >
            <span class="chip-name">{{ storeName(storeId) }}</span>
            <span
              v-if="etaAt(trip.id, storeIndex)?.etaMin != null"
              class="chip-eta"
            >{{ etaTitleFor(trip.id, storeIndex) }}<span v-if="etaAt(trip.id, storeIndex)?.late"> · 迟到</span></span>
          </button>
          <span v-if="storeIndex < trip.storeIds.length - 1" class="chain-arrow" aria-hidden="true">↓</span>
        </template>
      </div>

      <dl class="route-metrics">
        <div>
          <dt>里程</dt>
          <dd>{{ trip.totalDistanceKm.toFixed(1) }} km</dd>
        </div>
        <div v-if="trip.durationMin != null">
          <dt>时长</dt>
          <dd>{{ Math.round(trip.durationMin) }} 分钟</dd>
        </div>
        <div>
          <dt>装载</dt>
          <dd>{{ trip.totalVolumeCbm.toFixed(1) }} m³ · {{ Math.round(trip.loadRate * 100) }}%</dd>
        </div>
      </dl>

      <!-- 确认此线路(原「人工确认」的逐条确认): 待匹配车/司机则禁用并提示, 已确认则灰化。只读视图不显示。 -->
      <div v-if="!readonly" class="card-confirm" @click.stop>
        <button
          type="button"
          class="confirm-btn"
          :class="{ done: trip.status === 'confirmed' }"
          data-testid="confirm-trip"
          :disabled="trip.status !== 'draft' || confirmingIds.has(trip.id)"
          @click="onConfirmTrip(trip.id)"
        >{{ trip.status === 'confirmed' ? '✓ 已确认' : (confirmingIds.has(trip.id) ? '确认中…' : (trip.status === 'draft' ? '✓ 确认此线路' : statusLabels[trip.status])) }}</button>
      </div>
    </article>
  </section>
</template>

<style scoped lang="scss">
.route-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 12px;
  width: 100%;
}

.route-card {
  min-width: 0;
  padding: 16px;
  color: #101828;
  background: #ffffff;
  border: 1px solid #edf2f7;
  border-radius: 10px;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.06);
  cursor: pointer;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;

  &:hover,
  &:focus-within {
    border-color: rgba(27, 101, 168, 0.48);
    box-shadow: 0 6px 18px rgba(27, 101, 168, 0.12);
    outline: none;
    transform: translateY(-1px);
  }

  &.selected {
    border-color: #1b65a8;
    box-shadow: 0 0 0 2px rgba(27, 101, 168, 0.12), 0 6px 18px rgba(27, 101, 168, 0.12);
  }
}

/* 等宽数字 —— 里程/时长/装载率/出发返仓时刻不因字宽跳动，数据表更整齐专业。 */
.route-title, .sched-item, .chip-eta, .trip-seq, .route-metrics dd {
  font-variant-numeric: tabular-nums;
  font-feature-settings: 'tnum' 1;
}

.card-header,
.vehicle-row,
.route-metrics {
  display: flex;
  align-items: center;
}

.card-header {
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;

}

.route-select-button {
  display: grid;
  min-width: 0;
  padding: 0;
  text-align: left;
  background: transparent;
  border: 0;
  border-radius: 6px;
  cursor: pointer;

  &:focus-visible {
    outline: 2px solid #1b65a8;
    outline-offset: 4px;
  }
}

.route-eyebrow {
  color: #344054;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.05em;
}

.route-title {
  margin-top: 2px;
  color: #101828;
  font-size: 16px;
  font-weight: 700;
  line-height: 1.4;
}

.route-status {
  flex: 0 0 auto;
  padding: 4px 9px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}

.status-draft {
  color: #175cd3;
  background: #eff8ff;
}

.status-needs_vehicle {
  color: #b54708;
  background: #fffaeb;
}

.status-needs_driver {
  color: #b54708;
  background: #fef6ee;
}

.status-needs_route_data {
  color: #b42318;
  background: #fef3f2;
}

.status-confirmed {
  color: #027a48;
  background: #ecfdf3;
}

.vehicle-row {
  gap: 10px;
  min-height: 24px;
  color: #344054;
  font-size: 14px;

  strong {
    color: #101828;
    font-weight: 700;
  }
}

.field-label {
  color: #344054;
}

.trip-seq {
  padding: 2px 8px;
  color: #7c3aed;
  font-size: 11.5px;
  font-weight: 700;
  background: #f5f3ff;
  border: 1px solid #ddd6fe;
  border-radius: 999px;
}

.schedule-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 8px 0 2px;
}

.sched-item {
  padding: 3px 9px;
  color: #344054;
  font-size: 12.5px;
  font-weight: 650;
  background: #f4f6f9;
  border-radius: 8px;
}

.sched-item.late {
  color: #b42318;
  background: #fef3f2;
  border: 1px solid #fecdca;
}

/* 竖向门店列表：每站一行(门店名左 / 预计到达右)，站间用向下箭头 ↓ 表示配送先后。 */
.store-chain {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 3px;
  margin: 14px 0;
  padding: 10px;
  background: #f4f6f9;
  border-radius: 10px;
}

.store-chip {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  padding: 7px 10px;
  color: #344054;
  font: inherit;
  font-size: 13px;
  font-weight: 650;
  line-height: 1.35;
  text-align: left;
  background: #ffffff;
  border: 1px solid #edf2f7;
  border-radius: 8px;
  cursor: pointer;

  &:hover,
  &:focus-visible,
  &.selected {
    color: #101828;
    border-color: #1b65a8;
    outline: none;
  }

  &.late {
    color: #b42318;
    background: #fef3f2;
    border-color: #fda29b;
  }
}

.chip-name {
  flex: 1 1 auto;
  min-width: 0;
  color: #101828;
}

.chip-eta {
  flex: 0 0 auto;
  white-space: nowrap;
  font-size: 11px;
  font-weight: 600;
  color: #667085;
}

.store-chip.late .chip-eta {
  color: #b42318;
  font-weight: 700;
}

.late-warning {
  margin: 10px 0 0;
  padding: 7px 10px;
  color: #b42318;
  font-size: 12.5px;
  font-weight: 650;
  background: #fef3f2;
  border: 1px solid #fecdca;
  border-radius: 8px;
}

.chain-arrow {
  text-align: center;
  color: #98a2b3;
  font-size: 12px;
  font-weight: 800;
  line-height: 1;
}

.route-metrics {
  justify-content: space-between;
  gap: 12px;
  margin: 0;
  padding-top: 12px;
  border-top: 1px solid #edf2f7;

  div {
    min-width: 0;
  }

  dt {
    margin-bottom: 3px;
    color: #344054;
    font-size: 12px;
  }

  dd {
    margin: 0;
    color: #101828;
    font-size: 14px;
    font-weight: 750;
    white-space: nowrap;
  }
}

/* ===== 合并进来的「派车 / 确认 / 门店编辑」 ===== */
.assign-row { display: flex; align-items: flex-end; gap: 10px; flex-wrap: wrap; margin: 4px 0 2px; }
.assign-fld { display: flex; flex-direction: column; gap: 3px; flex: 1 1 120px; min-width: 0; }
.assign-fld .field-label { color: #667085; font-size: 11px; font-weight: 600; }
.assign-fld :deep(.el-select) { width: 100%; }

.chain-head { display: flex; align-items: center; justify-content: space-between; margin: 12px 0 4px; }
.chain-title { color: #344054; font-size: 12.5px; font-weight: 650; }
.edit-btn { font-size: 11px; color: #1b65a8; background: #fff; border: 1px solid #cfe3fb; border-radius: 6px; padding: 3px 9px; cursor: pointer; font-weight: 600; }
.edit-btn:hover { background: #eff6fd; }
.done-btn { font-size: 11px; color: #fff; background: #1b65a8; border: 0; border-radius: 6px; padding: 4px 12px; cursor: pointer; font-weight: 600; }

.store-chain.edit { flex-direction: column; align-items: stretch; }
.estop { display: flex; align-items: center; gap: 8px; background: #fff; border: 1px solid #edf2f7; border-radius: 8px; padding: 6px 9px; }
.estop.late { background: #fef3f2; border-color: #fecdca; }
.estop-nm { flex: 1 1 auto; min-width: 0; color: #101828; font-size: 12.5px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.estop-eta { flex: 0 0 auto; color: #98a2b3; font-size: 11px; }
.estop-ops { flex: 0 0 auto; display: flex; align-items: center; gap: 4px; }
.mv-btn { width: 22px; height: 22px; display: grid; place-items: center; color: #475569; background: #fff; border: 1px solid #dbe3ec; border-radius: 5px; cursor: pointer; font-size: 10px; }
.mv-btn:hover:not(:disabled) { background: #eef4fc; color: #1b65a8; }
.mv-btn:disabled { color: #cbd5e1; cursor: not-allowed; }
.move-sel { max-width: 112px; padding: 3px 5px; color: #344054; font-size: 11.5px; background: #fff; border: 1px solid #dbe3ec; border-radius: 6px; cursor: pointer; }

.card-confirm { margin-top: 12px; }
.confirm-btn { width: 100%; padding: 9px; color: #fff; font: inherit; font-size: 13px; font-weight: 650; background: #1b65a8; border: 0; border-radius: 8px; cursor: pointer; transition: background 0.15s ease; }
.confirm-btn:hover:not(:disabled) { background: #14507f; }
.confirm-btn:disabled { cursor: not-allowed; }
.confirm-btn.done { color: #027a48; background: #f0fdf6; border: 1px solid #a6f4c5; }
.confirm-btn:disabled:not(.done) { color: #fff; background: #98a2b3; }

@media (max-width: 720px) {
  .route-cards {
    grid-template-columns: 1fr;
  }
}
</style>
