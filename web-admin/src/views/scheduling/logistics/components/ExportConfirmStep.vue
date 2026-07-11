<script setup lang="ts">
import { computed } from 'vue';
import type { ExportRow } from '../types';

const props = defineProps<{
  rows: ExportRow[];
  confirmed: boolean;
  previewConfirmed: boolean;
  canConfirmSchedule: boolean;
  planId?: string | null;
}>();
const emit = defineEmits<{
  (event: 'confirm-schedule'): void;
  (event: 'confirm-preview'): void;
  (event: 'export-csv'): void;
  (event: 'export-xlsx'): void;
}>();

const previewRows = computed(() => props.rows.map((row) => ({
  ...row,
  vehicle: row.vehicle || '待匹配车辆',
  driver: row.driver || '待匹配司机',
})));
const pendingRows = computed(() => previewRows.value.filter((row) => row.vehicle === '待匹配车辆'));
const canExport = computed(() => Boolean(props.rows.length && props.planId));
</script>

<template>
  <section data-testid="export-step" class="export-step"><header><p>第四步</p><h2>导出排程</h2></header>
    <p v-if="confirmed" data-testid="export-confirmed" class="confirmed-row">排程已正式确认并导出。</p>
    <p v-else-if="previewConfirmed" data-testid="export-preview-confirmed" class="preview-row">已确认导出预览；待匹配车辆车次仍需补车后才能正式确认。</p>
    <p v-if="pendingRows.length" data-testid="pending-export-row" class="pending-row">待匹配车辆：{{ pendingRows.map((row) => row.tripId).join('、') }}</p>
    <el-table :data="previewRows" stripe><el-table-column prop="tripId" label="车次" /><el-table-column prop="vehicle" label="车辆" /><el-table-column prop="driver" label="司机" /><el-table-column prop="storeOrder" label="门店顺序" /><el-table-column prop="volume" label="准备体积" /><el-table-column prop="loadRate" label="装载率" /><el-table-column prop="distance" label="配送里程" /></el-table>
    <div>
      <el-button v-if="canConfirmSchedule" data-testid="confirm-schedule" type="primary" @click="emit('confirm-schedule')">确认排程</el-button>
      <el-button v-else data-testid="confirm-export-preview" type="primary" @click="emit('confirm-preview')">确认导出预览</el-button>
      <el-button data-testid="export-csv" :disabled="!canExport" @click="emit('export-csv')">下载 CSV</el-button>
      <el-button data-testid="export-xlsx" :disabled="!canExport" @click="emit('export-xlsx')">下载 Excel</el-button>
    </div>
  </section>
</template>

<style scoped lang="scss">.export-step { display: grid; gap: 20px; } p { margin: 0 0 6px; color: #1b65a8; font-size: 13px; font-weight: 750; } h2 { margin: 0; color: #101828; } .pending-row { padding: 10px 12px; color: #b54708; background: #fffaeb; border-radius: 8px; } .confirmed-row { padding: 10px 12px; color: #027a48; background: #ecfdf3; border-radius: 8px; } .preview-row { padding: 10px 12px; color: #175cd3; background: #eff8ff; border-radius: 8px; }</style>
