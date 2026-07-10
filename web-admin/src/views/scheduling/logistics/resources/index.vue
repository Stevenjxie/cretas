<script setup lang="ts">
import { computed, ref } from 'vue';
import { useLogisticsDemoState } from '../useLogisticsDemoState';

type ResourceFilter = 'all' | 'owned' | 'outsourced';

const state = useLogisticsDemoState();
const filter = ref<ResourceFilter>('all');

const filteredVehicles = computed(() => state.vehicles.value.filter((vehicle) => (
  filter.value === 'all'
  || (filter.value === 'owned' && vehicle.source === '自有')
  || (filter.value === 'outsourced' && vehicle.source === '外协')
)));
</script>

<template>
  <main class="support-page">
    <header class="page-header">
      <div>
        <h1>车辆与司机</h1>
        <p>查看自有和外协车辆，以及可调度的司机安排。</p>
      </div>
      <el-radio-group v-model="filter" aria-label="车辆来源筛选">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="owned">自有</el-radio-button>
        <el-radio-button value="outsourced">外协</el-radio-button>
      </el-radio-group>
    </header>

    <el-card shadow="never">
      <el-table :data="filteredVehicles" stripe>
        <el-table-column prop="plate" label="车牌号" min-width="130" />
        <el-table-column label="容量" min-width="90"><template #default="{ row }">{{ row.capacityCbm }} m³</template></el-table-column>
        <el-table-column label="最大载重" min-width="110"><template #default="{ row }">{{ row.maxWeightKg }} kg</template></el-table-column>
        <el-table-column prop="vehicleBody" label="车厢" min-width="110" />
        <el-table-column prop="driverName" label="司机" min-width="100" />
        <el-table-column label="备班司机" min-width="150"><template #default="{ row }">{{ row.backupDrivers.join('、') }}</template></el-table-column>
        <el-table-column label="固定区域" min-width="150"><template #default="{ row }">{{ row.areaCodes.join('、') }}</template></el-table-column>
        <el-table-column prop="shift" label="班次" min-width="120" />
        <el-table-column prop="source" label="来源" min-width="90"><template #default="{ row }"><el-tag :type="row.source === '自有' ? 'primary' : 'warning'" effect="plain">{{ row.source }}</el-tag></template></el-table-column>
      </el-table>
    </el-card>
  </main>
</template>

<style scoped lang="scss">
.support-page { display: grid; gap: 20px; max-width: 1440px; min-height: 100%; padding: 24px; margin: 0 auto; background: #f8fafc; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }.page-header h1 { margin: 0; color: #101828; font-size: 24px; }.page-header p { margin: 8px 0 0; color: #667085; }
@media (max-width: 720px) { .support-page { padding: 16px; }.page-header { flex-direction: column; } }
</style>
