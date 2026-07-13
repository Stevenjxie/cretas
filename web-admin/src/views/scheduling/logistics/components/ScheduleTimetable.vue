<script setup lang="ts">
import { computed } from 'vue';
import { vReveal } from '@/composables/useReveal';
import type { RouteTrip } from '../types';
import { fmtHm } from '../routeEta';

const props = defineProps<{
  trips: RouteTrip[];
  selectedTripId: string | null;
  /** 车辆行内部滚动(图例/时间轴固定)—— 车多时不撑高，用于查看路线页左列常显甘特。 */
  scrollable?: boolean;
}>();

const emit = defineEmits<{ (event: 'select-trip', tripId: string): void }>();

const routeColors = ['#1B65A8', '#7C3AED', '#C2410C', '#047857', '#BE185D', '#0E7490'];

// 时间轴范围：06:00–20:00（分钟），按实际车次出发/返仓自动收放但不小于该窗口。
const AXIS_MIN_DEFAULT = 6 * 60;
const AXIS_MAX_DEFAULT = 20 * 60;

const timedTrips = computed(() => props.trips.filter((t) => t.plannedDepartMin != null && t.returnToDepotMin != null));

const axis = computed(() => {
  let lo = AXIS_MIN_DEFAULT;
  let hi = AXIS_MAX_DEFAULT;
  for (const t of timedTrips.value) {
    if (t.plannedDepartMin != null) lo = Math.min(lo, t.plannedDepartMin);
    if (t.returnToDepotMin != null) hi = Math.max(hi, t.returnToDepotMin);
  }
  // 向下/上取整到整点，留边
  lo = Math.floor(lo / 60) * 60;
  hi = Math.ceil(hi / 60) * 60;
  return { lo, hi, span: Math.max(60, hi - lo) };
});

/** 整点刻度。 */
const ticks = computed(() => {
  const out: number[] = [];
  for (let m = axis.value.lo; m <= axis.value.hi; m += 60) out.push(m);
  return out;
});

interface Row {
  vehicleId: string;
  plate: string;
  trips: RouteTrip[];
}

/** 按车辆分组，组内按出发时刻排序（= 该车当天的趟次顺序）。车牌用车次自带的 vehiclePlate。 */
const rows = computed<Row[]>(() => {
  const byVeh = new Map<string, RouteTrip[]>();
  for (const t of props.trips) {
    if (!t.vehicleId) continue;
    const list = byVeh.get(t.vehicleId) ?? [];
    list.push(t);
    byVeh.set(t.vehicleId, list);
  }
  const out: Row[] = [];
  for (const [vehicleId, ts] of byVeh) {
    ts.sort((a, b) => (a.plannedDepartMin ?? 0) - (b.plannedDepartMin ?? 0));
    out.push({ vehicleId, plate: ts[0]?.vehiclePlate ?? vehicleId, trips: ts });
  }
  out.sort((a, b) => a.vehicleId.localeCompare(b.vehicleId));
  return out;
});

function leftPct(min: number): number {
  return ((min - axis.value.lo) / axis.value.span) * 100;
}
function widthPct(from: number, to: number): number {
  return Math.max(1.5, ((to - from) / axis.value.span) * 100);
}
function barStyle(trip: RouteTrip, index: number): Record<string, string> {
  const dep = trip.plannedDepartMin ?? axis.value.lo;
  const ret = trip.returnToDepotMin ?? dep;
  return {
    left: `${leftPct(dep)}%`,
    width: `${widthPct(dep, ret)}%`,
    '--bar-color': routeColors[index % routeColors.length],
  };
}
function tripColorIndex(trip: RouteTrip): number {
  return props.trips.findIndex((t) => t.id === trip.id);
}
</script>

<template>
  <section class="timetable" data-testid="schedule-timetable" aria-label="调度时间表">
    <div v-if="!timedTrips.length" class="empty">暂无排班时刻数据 —— 生成路线后显示每车每趟的出发/返仓时间表。</div>
    <template v-else>
      <div class="tt-legend">
        <span class="lg"><i class="sw depart" />🚚 出发</span>
        <span class="lg"><i class="sw reload" />🏭 返仓·装货</span>
        <span class="lg"><i class="sw late" />迟到回仓</span>
        <span class="tip">横向 = 当天时间；每行一辆车，条 = 一趟配送（含返仓）。多条 = 回仓补货多车次。</span>
      </div>

      <div class="tt-scroll" :class="{ scroll: scrollable }">
        <div class="tt-axis" :class="{ sticky: scrollable }">
          <div class="tt-rowlabel" />
          <div class="tt-track">
            <span v-for="m in ticks" :key="m" class="tt-tick" :style="{ left: `${leftPct(m)}%` }">{{ fmtHm(m) }}</span>
          </div>
        </div>

        <div v-for="(row, i) in rows" :key="row.vehicleId" v-reveal="Math.min(i, 8)" class="tt-row" data-testid="tt-vehicle-row">
          <div class="tt-rowlabel"><strong>{{ row.plate }}</strong><span>{{ row.trips.length }} 趟</span></div>
          <div class="tt-track">
            <span v-for="m in ticks" :key="'g'+m" class="tt-grid" :style="{ left: `${leftPct(m)}%` }" />
            <button
              v-for="trip in row.trips"
              :key="trip.id"
              type="button"
              class="tt-bar"
              :class="{ selected: trip.id === selectedTripId, late: trip.lateReturn }"
              :style="barStyle(trip, tripColorIndex(trip))"
              :title="`第${trip.vehicleTripSeq ?? '?'}趟 · ${trip.storeIds.length}店 · 出发${fmtHm(trip.plannedDepartMin!)}→返仓${fmtHm(trip.returnToDepotMin!)}${trip.lateReturn ? ' · 迟到回仓' : ''}`"
              @click="emit('select-trip', trip.id)"
            >
              <span class="tt-bar-label">第{{ trip.vehicleTripSeq ?? '?' }}趟 · {{ trip.storeIds.length }}店</span>
            </button>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped lang="scss">
.timetable { width: 100%; padding: 12px 14px; background: #fff; border: 1px solid #edf2f7; border-radius: 10px; overflow-x: auto; }
/* scrollable: 车辆行内部滚动(图例在外, 时间轴 sticky 固定), 车多时不撑高地图 */
.tt-scroll.scroll { max-height: 320px; overflow-y: auto; }
.tt-scroll.scroll::-webkit-scrollbar { width: 8px; } .tt-scroll.scroll::-webkit-scrollbar-thumb { background: #cbd5e1; border-radius: 4px; }
.tt-axis.sticky { position: sticky; top: 0; z-index: 2; background: #fff; }
.empty { padding: 28px; color: #667085; text-align: center; }
.tt-legend { display: flex; flex-wrap: wrap; align-items: center; gap: 14px; margin-bottom: 12px; color: #475467; font-size: 12.5px; }
.tt-legend .lg { display: inline-flex; align-items: center; gap: 5px; font-weight: 650; }
.tt-legend .sw { width: 14px; height: 10px; border-radius: 3px; }
.tt-legend .sw.depart { background: #1b65a8; }
.tt-legend .sw.reload { background: repeating-linear-gradient(45deg, #cbd5e1 0 3px, #fff 3px 6px); }
.tt-legend .sw.late { background: #b42318; }
.tt-legend .tip { color: #98a2b3; font-weight: 500; }
.tt-axis, .tt-row { display: grid; grid-template-columns: 120px 1fr; align-items: center; }
.tt-axis { height: 22px; margin-bottom: 4px; }
.tt-rowlabel { display: flex; flex-direction: column; padding-right: 10px; color: #101828; font-size: 13px; }
.tt-rowlabel span { color: #667085; font-size: 11px; }
.tt-track { position: relative; height: 46px; min-width: 640px; }
.tt-axis .tt-track { height: 22px; }
.tt-tick { position: absolute; top: 0; transform: translateX(-50%); color: #98a2b3; font-size: 11px; white-space: nowrap; font-variant-numeric: tabular-nums; }
.tt-bar-label, .tt-rowlabel span { font-variant-numeric: tabular-nums; }
.tt-grid { position: absolute; top: 0; bottom: 0; width: 1px; background: #f1f3f5; }
.tt-row { border-top: 1px dashed #eef1f4; }
.tt-bar { position: absolute; top: 11px; height: 24px; display: flex; align-items: center; padding: 0 6px; color: #fff; font-size: 11px; font-weight: 700; background: var(--bar-color); border: 2px solid transparent; border-radius: 6px; cursor: pointer; overflow: hidden; white-space: nowrap; box-shadow: 0 1px 3px rgba(0,0,0,0.18); }
.tt-bar:hover { filter: brightness(1.06); }
.tt-bar.selected { border-color: #101828; box-shadow: 0 0 0 2px rgba(16,24,40,0.15); z-index: 2; }
.tt-bar.late { outline: 2px solid #b42318; outline-offset: 1px; }
.tt-bar-label { overflow: hidden; text-overflow: ellipsis; }
</style>
