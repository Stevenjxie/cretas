<script setup lang="ts">
import { computed } from 'vue';
import type { RouteTrip, StoreOrder, Vehicle } from '../types';
import { etaLabel, parseWindow, tripEtas, type StopEta } from '../routeEta';

const props = defineProps<{ trip: RouteTrip | null; stores: StoreOrder[]; vehicles: Vehicle[]; trips?: RouteTrip[] }>();
const emit = defineEmits<{
  (event: 'move-store', storeId: string, direction: -1 | 1): void;
  (event: 'move-to-trip', storeId: string, targetTripId: string | null): void;
  (event: 'assign-vehicle', vehicleId: string | null): void;
  (event: 'assign-driver', driverId: string | null): void;
  (event: 'confirm-trip'): void;
}>();

const orderedStores = computed(() => props.trip?.storeIds.map((id) => props.stores.find((store) => store.id === id)).filter(Boolean) as StoreOrder[] ?? []);
const storeById = computed(() => new Map(props.stores.map((s) => [s.id, s])));
// 每站预计到达 + 迟到（复用与步骤2/后端优化器一致的到达模型）
const etas = computed<StopEta[]>(() =>
  props.trip ? tripEtas(props.trip, (id) => parseWindow(storeById.value.get(id)?.window)) : [],
);
const lateCount = computed(() => etas.value.filter((e) => e.late).length);
function etaAt(i: number): StopEta | undefined {
  return etas.value[i];
}
const selectedVehicle = computed(() => props.vehicles.find((vehicle) => vehicle.id === props.trip?.vehicleId) ?? null);
const driverOptions = computed(() => props.vehicles.filter((vehicle) => vehicle.id === props.trip?.vehicleId && vehicle.driverId));
/** 可作为跨车次移动目标的其他车次（不含当前车次本身） */
const otherTrips = computed(() => (props.trips ?? []).filter((candidate) => candidate.id !== props.trip?.id));

function moveToTrip(storeId: string, event: Event): void {
  const select = event.target as HTMLSelectElement;
  const value = select.value;
  select.value = ''; // 重置为占位项，避免下拉框停留在"已选中"的错觉
  if (!value) return; // 占位项本身不触发移动
  emit('move-to-trip', storeId, value === '__new__' ? null : value);
}
</script>

<template>
  <section data-testid="confirm-step" class="confirm-step">
    <template v-if="trip">
      <header><p>第三步</p><h2>人工确认</h2><span>确认门店顺序、车辆和司机后提交该车次。</span></header>
      <div class="confirm-grid">
        <section class="card"><h3>配送顺序</h3>
          <p v-if="lateCount > 0" data-testid="confirm-late-warning" class="late-warning">
            ⚠️ {{ lateCount }} 家门店预计晚于配送时间（下方标红）。可上移/下移调整顺序，或将其移至其他车次。
          </p>
          <ol><li v-for="(store, index) in orderedStores" :key="store.id" :class="{ late: etaAt(index)?.late }"><span>{{ index + 1 }}</span>
            <span class="store-info"><span class="store-name">{{ store.name }}</span><span v-if="etaAt(index)?.etaMin != null" class="store-eta">{{ etaLabel(etaAt(index)) }}<span v-if="etaAt(index)?.late"> · 迟到</span></span></span>
            <el-button text :disabled="index === 0" @click="emit('move-store', store.id, -1)">上移</el-button>
            <el-button text :disabled="index === orderedStores.length - 1" @click="emit('move-store', store.id, 1)">下移</el-button>
            <select
              v-if="otherTrips.length"
              class="move-to-trip-select"
              aria-label="移至其他车次"
              @change="moveToTrip(store.id, $event)"
            >
              <option value="">移至其他车次…</option>
              <option v-for="candidate in otherTrips" :key="candidate.id" :value="candidate.id">第 {{ candidate.tripNo }} 趟</option>
              <option value="__new__">新建待匹配车次</option>
            </select>
          </li></ol>
        </section>
        <section class="card"><h3>车辆与司机</h3>
          <label>车辆<el-select :model-value="trip.vehicleId" clearable placeholder="待匹配车辆" @update:model-value="emit('assign-vehicle', $event)"><el-option v-for="vehicle in vehicles" :key="vehicle.id" :label="`${vehicle.plate} · ${vehicle.capacityCbm}m³`" :value="vehicle.id" /></el-select></label>
          <label>司机<el-select :model-value="trip.driverId" clearable placeholder="请选择司机" @update:model-value="emit('assign-driver', $event)"><el-option v-for="vehicle in driverOptions" :key="vehicle.driverId" :label="vehicle.driverName" :value="vehicle.driverId" /></el-select></label>
          <p v-if="selectedVehicle" class="backup">备用司机：{{ selectedVehicle.backupDrivers.join('、') }}</p>
          <p v-else class="backup">当前车次可保留为待匹配车辆。</p>
        </section>
      </div>
      <el-button type="primary" :disabled="trip.status !== 'draft'" @click="emit('confirm-trip')">确认该车次</el-button>
    </template>
    <p v-else>请先选择一个车次。</p>
  </section>
</template>

<style scoped lang="scss">
.confirm-step { display: grid; gap: 20px; } header p { margin: 0 0 6px; color: #1b65a8; font-size: 13px; font-weight: 750; } h2,h3 { margin: 0; color: #101828; } header span { color: #667085; } .confirm-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; } .card { padding: 20px; background: #fff; border: 1px solid #eaecf0; border-radius: 12px; } ol { display: grid; gap: 10px; padding-left: 24px; } li { display: flex; align-items: center; gap: 8px; color: #344054; } li > span:first-child { display: grid; width: 22px; height: 22px; place-items: center; color: #fff; font-size: 12px; background: #1b65a8; border-radius: 50%; flex: 0 0 auto; } .store-info { display: grid; gap: 1px; } .store-name { color: #344054; } .store-eta { font-size: 11px; font-weight: 600; color: #667085; } li.late .store-name { color: #b42318; font-weight: 700; } li.late .store-eta { color: #b42318; font-weight: 700; } li.late > span:first-child { background: #b42318; } .late-warning { margin: 0 0 12px; padding: 8px 10px; color: #b42318; font-size: 12.5px; font-weight: 650; background: #fef3f2; border: 1px solid #fecdca; border-radius: 8px; } label { display: grid; gap: 7px; margin-top: 16px; color: #344054; font-size: 14px; font-weight: 650; } .backup { padding: 10px; color: #475467; background: #f9fafb; border-radius: 8px; } .move-to-trip-select { padding: 4px 6px; color: #344054; font-size: 12px; background: #f9fafb; border: 1px solid #eaecf0; border-radius: 6px; } @media (max-width: 760px) { .confirm-grid { grid-template-columns: 1fr; } }
</style>
