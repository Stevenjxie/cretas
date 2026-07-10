<script setup lang="ts">
import { computed, ref } from 'vue';
import ExportConfirmStep from '../components/ExportConfirmStep.vue';
import LogisticsMap from '../components/LogisticsMap.vue';
import LogisticsStepBar from '../components/LogisticsStepBar.vue';
import ManualConfirmStep from '../components/ManualConfirmStep.vue';
import OrderImportStep from '../components/OrderImportStep.vue';
import RouteCards from '../components/RouteCards.vue';
import StoreDetailDrawer from '../components/StoreDetailDrawer.vue';
import { useLogisticsDemoState } from '../useLogisticsDemoState';

const state = useLogisticsDemoState();
const assignmentIssue = ref('');
const exportConfirmed = ref(false);
const exportPreviewConfirmed = ref(false);
const canConfirmSchedule = computed(() => state.scheduleResult.value.trips.length > 0
  && state.scheduleResult.value.unassignedStoreIds.length === 0
  && state.scheduleResult.value.trips.every((trip) => trip.status === 'confirmed'));

const hasExceptions = computed(() => {
  const trips = state.scheduleResult.value.trips;
  const assignedVehicles = trips.filter((trip) => trip.vehicleId).map((trip) => trip.vehicleId);
  const assignedDrivers = trips.filter((trip) => trip.driverId).map((trip) => trip.driverId);
  const hasConflict = new Set(assignedVehicles).size !== assignedVehicles.length
    || new Set(assignedDrivers).size !== assignedDrivers.length;
  return Boolean(assignmentIssue.value)
    || state.scheduleResult.value.unassignedStoreIds.length > 0
    || trips.some((trip) => trip.status === 'needs_vehicle' || trip.status === 'needs_route_data')
    || hasConflict;
});

function handleTargetLoad(value: number): void {
  state.setTargetLoad(value);
}

function importSampleOrders(): void {
  state.importOrders();
  exportConfirmed.value = false;
  exportPreviewConfirmed.value = false;
}

function confirmSchedule(): void {
  if (state.confirmSchedule()) exportConfirmed.value = true;
}

function confirmExportPreview(): void {
  exportPreviewConfirmed.value = true;
  state.activeStep.value = 'export';
}

function assignVehicle(vehicleId: string | null): void {
  const issue = state.getVehicleAssignmentIssue(vehicleId);
  if (issue) {
    assignmentIssue.value = issue;
    return;
  }
  if (state.assignVehicle(vehicleId)) assignmentIssue.value = '';
}

function assignDriver(driverId: string | null): void {
  const issue = state.getDriverAssignmentIssue(driverId);
  if (issue) {
    assignmentIssue.value = issue;
    return;
  }
  if (state.assignDriver(driverId)) assignmentIssue.value = '';
}

function handleMoveStore(storeId: string, direction: -1 | 1): void {
  if (!state.moveStore(storeId, direction)) {
    assignmentIssue.value = '当前门店顺序缺少可用道路数据，未调整配送顺序。';
    return;
  }
  assignmentIssue.value = '';
}

function back(): void {
  const steps = ['import', 'map', 'confirm', 'export'] as const;
  const index = steps.indexOf(state.activeStep.value);
  if (index > 0) state.activeStep.value = steps[index - 1];
  if (state.activeStep.value !== 'export') {
    exportConfirmed.value = false;
    exportPreviewConfirmed.value = false;
  }
}

function next(): void {
  if (state.activeStep.value === 'import') state.generateRoutes();
  else if (state.activeStep.value === 'map') state.activeStep.value = 'confirm';
  else if (state.activeStep.value === 'confirm') state.previewExport();
}
</script>

<template>
  <main class="workbench-page">
    <header class="page-header"><div><h1>配送排程</h1><p>按订单、路线、确认和导出完成当天排程。</p></div><el-tag effect="plain" type="info">演示数据</el-tag></header>
    <LogisticsStepBar :active-step="state.activeStep.value" />
    <el-alert v-if="hasExceptions" data-testid="assignment-issue" title="需要处理" :description="assignmentIssue || undefined" type="warning" :closable="false" show-icon />

    <OrderImportStep v-if="state.activeStep.value === 'import'" :stores="state.stores.value" :imported="state.imported.value" @import-sample="importSampleOrders" />

    <section v-else-if="state.activeStep.value === 'map'" data-testid="map-step" class="map-step">
      <header class="map-heading"><div><p>第二步</p><h2>查看路线</h2></div><label>目标装载率 <el-slider :model-value="state.targetLoadPct.value" :min="50" :max="100" :show-tooltip="true" @update:model-value="handleTargetLoad" /></label></header>
      <LogisticsMap :stores="state.stores.value" :trips="state.scheduleResult.value.trips" :selected-trip-id="state.selectedTripId.value" :selected-store-id="state.selectedStoreId.value" @select-trip="state.selectTrip" @select-store="state.selectStore" />
      <RouteCards :stores="state.stores.value" :trips="state.scheduleResult.value.trips" :selected-trip-id="state.selectedTripId.value" :selected-store-id="state.selectedStoreId.value" @select-trip="state.selectTrip" @select-store="state.selectStore" />
      <StoreDetailDrawer :stores="state.stores.value" :selected-store-id="state.selectedStoreId.value" @select-store="state.selectStore" />
      <button data-testid="generate-routes" class="generate-button" type="button" @click="state.generateRoutes">重新生成路线</button>
    </section>

    <ManualConfirmStep v-else-if="state.activeStep.value === 'confirm'" :trip="state.activeTrip.value" :stores="state.stores.value" :vehicles="state.vehicles.value" @move-store="handleMoveStore" @assign-vehicle="assignVehicle" @assign-driver="assignDriver" @confirm-trip="state.confirmTrip" />
    <ExportConfirmStep v-else :rows="state.exportRows.value" :confirmed="exportConfirmed" :preview-confirmed="exportPreviewConfirmed" :can-confirm-schedule="canConfirmSchedule" @confirm-schedule="confirmSchedule" @confirm-preview="confirmExportPreview" />

    <footer class="action-bar"><el-button :disabled="state.activeStep.value === 'import'" @click="back">上一步</el-button><button v-if="state.activeStep.value !== 'export'" data-testid="finish-schedule" class="next-button" type="button" @click="next">{{ state.activeStep.value === 'confirm' ? '查看导出预览' : '下一步' }}</button></footer>
  </main>
</template>

<style scoped lang="scss">
.workbench-page { display: grid; gap: 20px; max-width: 1440px; min-height: 100%; padding: 24px; margin: 0 auto; background: #f8fafc; }
.page-header, .map-heading, .action-bar { display: flex; align-items: center; justify-content: space-between; gap: 16px; } h1,h2 { margin: 0; color: #101828; } .page-header p, .map-heading p { margin: 6px 0 0; color: #667085; } .map-step { display: grid; gap: 16px; } .map-heading label { display: grid; grid-template-columns: auto minmax(150px, 260px); align-items: center; gap: 12px; color: #344054; font-size: 14px; font-weight: 650; } .generate-button, .next-button { width: fit-content; padding: 10px 18px; color: #fff; font: inherit; font-weight: 650; background: #1b65a8; border: 0; border-radius: 6px; cursor: pointer; } .action-bar { position: sticky; bottom: 0; z-index: 20; padding: 14px 0; background: linear-gradient(to bottom, transparent, #f8fafc 28%); } @media (max-width: 720px) { .workbench-page { padding: 16px; } .page-header,.map-heading { align-items: flex-start; flex-direction: column; } .map-heading label { width: 100%; } }
</style>
