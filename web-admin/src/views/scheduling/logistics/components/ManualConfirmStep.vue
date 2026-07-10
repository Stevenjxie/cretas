<script setup lang="ts">
import { computed } from 'vue';
import type { RouteTrip, StoreOrder, Vehicle } from '../types';

const props = defineProps<{ trip: RouteTrip | null; stores: StoreOrder[]; vehicles: Vehicle[] }>();
const emit = defineEmits<{
  (event: 'move-store', storeId: string, direction: -1 | 1): void;
  (event: 'assign-vehicle', vehicleId: string | null): void;
  (event: 'assign-driver', driverId: string | null): void;
  (event: 'confirm-trip'): void;
}>();

const orderedStores = computed(() => props.trip?.storeIds.map((id) => props.stores.find((store) => store.id === id)).filter(Boolean) as StoreOrder[] ?? []);
const selectedVehicle = computed(() => props.vehicles.find((vehicle) => vehicle.id === props.trip?.vehicleId) ?? null);
const driverOptions = computed(() => props.vehicles.filter((vehicle) => vehicle.id === props.trip?.vehicleId && vehicle.driverId));
</script>

<template>
  <section data-testid="confirm-step" class="confirm-step">
    <template v-if="trip">
      <header><p>第三步</p><h2>人工确认</h2><span>确认门店顺序、车辆和司机后提交该车次。</span></header>
      <div class="confirm-grid">
        <section class="card"><h3>配送顺序</h3>
          <ol><li v-for="(store, index) in orderedStores" :key="store.id"><span>{{ index + 1 }}</span>{{ store.name }}
            <el-button text :disabled="index === 0" @click="emit('move-store', store.id, -1)">上移</el-button>
            <el-button text :disabled="index === orderedStores.length - 1" @click="emit('move-store', store.id, 1)">下移</el-button>
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
.confirm-step { display: grid; gap: 20px; } header p { margin: 0 0 6px; color: #1b65a8; font-size: 13px; font-weight: 750; } h2,h3 { margin: 0; color: #101828; } header span { color: #667085; } .confirm-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; } .card { padding: 20px; background: #fff; border: 1px solid #eaecf0; border-radius: 12px; } ol { display: grid; gap: 10px; padding-left: 24px; } li { display: flex; align-items: center; gap: 8px; color: #344054; } li > span { display: grid; width: 22px; height: 22px; place-items: center; color: #fff; font-size: 12px; background: #1b65a8; border-radius: 50%; } label { display: grid; gap: 7px; margin-top: 16px; color: #344054; font-size: 14px; font-weight: 650; } .backup { padding: 10px; color: #475467; background: #f9fafb; border-radius: 8px; } @media (max-width: 760px) { .confirm-grid { grid-template-columns: 1fr; } }
</style>
