<script setup lang="ts">
import { computed } from 'vue';
import type { RouteTrip, StoreOrder } from '../types';

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

function vehicleLabel(trip: RouteTrip): string {
  return trip.vehiclePlate ?? trip.vehicleId ?? '待匹配车辆';
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

      <div class="vehicle-row">
        <span class="field-label">车辆</span>
        <strong>{{ vehicleLabel(trip) }}</strong>
      </div>

      <p
        v-if="tripLateCount(trip) > 0"
        data-testid="route-late-warning"
        class="late-warning"
      >
        ⚠️ {{ tripLateCount(trip) }} 家门店预计晚于配送时间（预计到达为估算）。可在「人工确认」拖动调整门店顺序，或为其改派车辆。
      </p>

      <div class="store-chain" :aria-label="`线路 ${index + 1} 门店顺序`">
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
          <span v-if="storeIndex < trip.storeIds.length - 1" class="chain-arrow" aria-hidden="true">→</span>
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

.store-chain {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  min-height: 68px;
  margin: 14px 0;
  padding: 10px;
  background: #f4f6f9;
  border-radius: 10px;
}

.store-chip {
  display: inline-flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  padding: 4px 7px;
  color: #344054;
  font: inherit;
  font-size: 13px;
  font-weight: 650;
  line-height: 1.35;
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

.chip-eta {
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
  color: #344054;
  font-weight: 800;
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

@media (max-width: 720px) {
  .route-cards {
    grid-template-columns: 1fr;
  }
}
</style>
