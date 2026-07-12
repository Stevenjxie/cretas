<script setup lang="ts">
import { computed } from 'vue';
import { vReveal } from '@/composables/useReveal';
import type { RouteTrip, StoreOrder, Vehicle } from '../types';
import { etaLabel, parseWindow, tripEtas, type StopEta } from '../routeEta';

// 人工确认 = 一次核对 *全部* 车次: 每个车次卡内匹配车辆+司机、核对配送顺序, 逐一确认或一键确认全部。
// (旧版一次只展示一个已选车次, 调度员看不到整体匹配, 需在步骤2逐个点选再回来, 不符合实际排班。)
const props = defineProps<{
  trips: RouteTrip[];
  stores: StoreOrder[];
  vehicles: Vehicle[];
  selectedTripId: string | null;
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
function vehicleOf(trip: RouteTrip): Vehicle | null {
  return props.vehicles.find((v) => v.id === trip.vehicleId) ?? null;
}
function backupDriversText(trip: RouteTrip): string {
  return vehicleOf(trip)?.backupDrivers?.join('、') ?? '';
}

const statusLabels: Record<RouteTrip['status'], string> = {
  draft: '待确认',
  needs_vehicle: '待匹配车辆',
  needs_driver: '待匹配司机',
  needs_route_data: '缺少路线数据',
  confirmed: '已确认',
};

// 顶部进度: 总车次 / 已确认 / 待匹配车辆或司机
const totalTrips = computed(() => props.trips.length);
const confirmedCount = computed(() => props.trips.filter((t) => t.status === 'confirmed').length);
const unmatchedCount = computed(() => props.trips.filter((t) => !t.vehicleId || !t.driverId).length);
// 还有「可确认但未确认」的草稿车次时, 才显示一键确认
const confirmableTrips = computed(() => props.trips.filter((t) => t.status === 'draft'));

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
    <header class="confirm-head">
      <div><p>第三步</p><h2>人工确认</h2><span>为每个车次匹配车辆、司机，核对配送顺序后逐一确认（或一键确认全部）。</span></div>
      <el-button
        v-if="confirmableTrips.length > 0 && unmatchedCount === 0"
        type="primary"
        data-testid="confirm-all-trips"
        @click="emit('confirm-all')"
      >一键确认全部（{{ confirmableTrips.length }}）</el-button>
    </header>

    <div v-if="totalTrips > 0" class="confirm-progress" data-testid="confirm-progress">
      <div class="cp-item"><span class="cp-value">{{ totalTrips }}</span><span class="cp-label">车次</span></div>
      <div class="cp-sep" />
      <div class="cp-item"><span class="cp-value">{{ confirmedCount }}/{{ totalTrips }}</span><span class="cp-label">已确认</span></div>
      <div class="cp-sep" />
      <div class="cp-item" :class="{ warn: unmatchedCount > 0 }"><span class="cp-value">{{ unmatchedCount }}</span><span class="cp-label">待匹配车辆/司机</span></div>
    </div>

    <div v-if="totalTrips > 0" class="trip-list">
      <article
        v-for="(trip, index) in trips"
        :key="trip.id"
        v-reveal="Math.min(index, 8)"
        data-testid="confirm-trip-card"
        :data-trip-id="trip.id"
        :class="['trip-card', { selected: trip.id === selectedTripId, confirmed: trip.status === 'confirmed' }]"
        @click="emit('select-trip', trip.id)"
      >
        <header class="trip-card-head">
          <div class="tc-title">
            <span class="tc-eyebrow">线路 {{ String(index + 1).padStart(2, '0') }}</span>
            <span class="tc-trip">第 {{ trip.tripNo }} 趟配送
              <span v-if="trip.vehicleTripSeq && trip.vehicleTripSeq > 1" class="tc-seq">该车第 {{ trip.vehicleTripSeq }} 趟 · 回仓补货</span>
            </span>
          </div>
          <div class="tc-stats">
            <span class="tc-stat">{{ trip.storeIds.length }} 店</span>
            <span class="tc-stat">{{ trip.totalDistanceKm.toFixed(1) }} km</span>
            <span class="tc-stat">装载 {{ Math.round(trip.loadRate * 100) }}%</span>
            <span :class="['tc-status', `status-${trip.status}`]">{{ statusLabels[trip.status] }}</span>
          </div>
        </header>

        <div class="trip-card-body">
          <section class="tc-col tc-sequence">
            <h4>配送顺序</h4>
            <p v-if="lateCount(trip.id) > 0" data-testid="confirm-late-warning" class="late-warning">
              ⚠️ {{ lateCount(trip.id) }} 家门店预计晚于配送时间（下方标红）。可上移/下移调整，或移至其他车次。
            </p>
            <ol>
              <li v-for="(storeId, i) in trip.storeIds" :key="storeId" :class="{ late: etaAt(trip.id, i)?.late }">
                <span class="seq-no">{{ i + 1 }}</span>
                <span class="store-info">
                  <span class="store-name">{{ storeName(storeId) }}</span>
                  <span v-if="etaAt(trip.id, i)?.etaMin != null" class="store-eta">{{ etaLabel(etaAt(trip.id, i)) }}<span v-if="etaAt(trip.id, i)?.late"> · 迟到</span></span>
                </span>
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
              </li>
            </ol>
          </section>

          <section class="tc-col tc-assign">
            <h4>车辆与司机</h4>
            <label>车辆
              <el-select
                :model-value="trip.vehicleId"
                clearable
                placeholder="待匹配车辆"
                data-testid="confirm-vehicle-select"
                @click.stop
                @update:model-value="emit('assign-vehicle', trip.id, $event)"
              >
                <el-option v-for="v in vehicles" :key="v.id" :label="`${v.plate} · ${v.capacityCbm}m³`" :value="v.id" />
              </el-select>
            </label>
            <label>司机
              <el-select
                :model-value="trip.driverId"
                clearable
                placeholder="请选择司机"
                data-testid="confirm-driver-select"
                :disabled="!trip.vehicleId"
                @click.stop
                @update:model-value="emit('assign-driver', trip.id, $event)"
              >
                <el-option v-for="v in driverOptions(trip)" :key="v.driverId ?? ''" :label="v.driverName" :value="v.driverId" />
              </el-select>
            </label>
            <p v-if="backupDriversText(trip)" class="backup">备用司机：{{ backupDriversText(trip) }}</p>
            <p v-else class="backup">先选车辆再匹配司机。未匹配可保留为待处理。</p>

            <el-button
              class="confirm-trip-btn"
              type="primary"
              :plain="trip.status === 'confirmed'"
              data-testid="confirm-trip"
              :disabled="trip.status !== 'draft'"
              @click.stop="emit('confirm-trip', trip.id)"
            >{{ trip.status === 'confirmed' ? '✓ 已确认' : '确认该车次' }}</el-button>
          </section>
        </div>
      </article>
    </div>

    <p v-else class="empty-hint" data-testid="confirm-empty">还没有生成任何车次，请先回到「查看路线」生成配送方案。</p>
  </section>
</template>

<style scoped lang="scss">
.confirm-step { display: grid; gap: 16px; }
.confirm-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.confirm-head p { margin: 0 0 6px; color: #1b65a8; font-size: 13px; font-weight: 750; }
.confirm-head h2 { margin: 0; color: #101828; }
.confirm-head span { color: #667085; font-size: 13px; }

.confirm-progress { display: flex; align-items: center; gap: 20px; padding: 12px 20px; background: linear-gradient(180deg, #ffffff, #f8fafc); border: 1px solid #e2e8f0; border-radius: 10px; }
.cp-item { display: flex; flex-direction: column; gap: 2px; }
.cp-value { color: #0f172a; font-size: 20px; font-weight: 750; line-height: 1.1; font-variant-numeric: tabular-nums; }
.cp-label { color: #667085; font-size: 12px; }
.cp-item.warn .cp-value { color: #b54708; }
.cp-sep { width: 1px; height: 26px; background: #e2e8f0; }

/* 全部车次列表: 宽屏两列铺开, 一屏看全整体匹配 */
.trip-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(460px, 1fr)); gap: 14px; }
.trip-card { padding: 16px 18px; background: #fff; border: 1px solid #eaecf0; border-radius: 12px; box-shadow: 0 2px 12px rgba(27,101,168,0.05); cursor: pointer; transition: border-color 0.2s ease, box-shadow 0.2s ease; }
.trip-card:hover, .trip-card:focus-within { border-color: rgba(27,101,168,0.42); box-shadow: 0 6px 18px rgba(27,101,168,0.1); }
.trip-card.selected { border-color: #1b65a8; box-shadow: 0 0 0 2px rgba(27,101,168,0.12), 0 6px 18px rgba(27,101,168,0.1); }
.trip-card.confirmed { background: #fbfffc; border-color: #b7f0cf; }

.trip-card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding-bottom: 12px; margin-bottom: 12px; border-bottom: 1px solid #f0f2f5; }
.tc-title { display: grid; gap: 2px; min-width: 0; }
.tc-eyebrow { color: #344054; font-size: 12px; font-weight: 700; letter-spacing: 0.05em; }
.tc-trip { color: #101828; font-size: 16px; font-weight: 750; font-variant-numeric: tabular-nums; }
.tc-seq { margin-left: 6px; padding: 2px 8px; color: #7c3aed; font-size: 11px; font-weight: 700; background: #f5f3ff; border: 1px solid #ddd6fe; border-radius: 999px; }
.tc-stats { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
.tc-stat { padding: 3px 8px; color: #344054; font-size: 12px; font-weight: 650; background: #f4f6f9; border-radius: 8px; font-variant-numeric: tabular-nums; }
.tc-status { padding: 3px 9px; border-radius: 999px; font-size: 12px; font-weight: 700; }
.status-draft { color: #175cd3; background: #eff8ff; }
.status-needs_vehicle { color: #b54708; background: #fffaeb; }
.status-needs_driver { color: #b54708; background: #fef6ee; }
.status-needs_route_data { color: #b42318; background: #fef3f2; }
.status-confirmed { color: #027a48; background: #ecfdf3; }

.trip-card-body { display: grid; grid-template-columns: 1.4fr 1fr; gap: 18px; }
.tc-col h4 { margin: 0 0 10px; color: #101828; font-size: 13.5px; font-weight: 700; }

.tc-sequence ol { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.tc-sequence li { display: flex; align-items: center; gap: 8px; color: #344054; }
.seq-no { display: grid; width: 22px; height: 22px; place-items: center; color: #fff; font-size: 12px; background: #1b65a8; border-radius: 50%; flex: 0 0 auto; }
.store-info { display: grid; gap: 1px; flex: 1 1 auto; min-width: 0; }
.store-name { color: #344054; font-size: 13.5px; }
.store-eta { font-size: 11px; font-weight: 600; color: #667085; }
li.late .store-name { color: #b42318; font-weight: 700; }
li.late .store-eta { color: #b42318; font-weight: 700; }
li.late .seq-no { background: #b42318; }
.late-warning { margin: 0 0 10px; padding: 8px 10px; color: #b42318; font-size: 12.5px; font-weight: 650; background: #fef3f2; border: 1px solid #fecdca; border-radius: 8px; }
.move-to-trip-select { max-width: 120px; padding: 4px 6px; color: #344054; font-size: 12px; background: #f9fafb; border: 1px solid #eaecf0; border-radius: 6px; }

.tc-assign label { display: grid; gap: 6px; margin-bottom: 12px; color: #344054; font-size: 13.5px; font-weight: 650; }
.tc-assign .backup { margin: 0 0 12px; padding: 8px 10px; color: #475467; font-size: 12.5px; background: #f9fafb; border-radius: 8px; }
.confirm-trip-btn { width: 100%; }

.empty-hint { padding: 40px 20px; text-align: center; color: #667085; background: #fff; border: 1px dashed #d0d5dd; border-radius: 12px; }

@media (max-width: 1180px) {
  .trip-list { grid-template-columns: 1fr; }
}
@media (max-width: 640px) {
  .trip-card-body { grid-template-columns: 1fr; gap: 14px; }
}
</style>
