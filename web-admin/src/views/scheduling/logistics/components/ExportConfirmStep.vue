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
  <section data-testid="export-step" class="export-step"><header><p>第四步</p><h2>确认排班调度</h2><span class="sub">核对当天各车次的车辆、司机、门店顺序，确认后即为当天正式排班。可另存 CSV / Excel 发给司机。</span></header>
    <p v-if="confirmed" data-testid="export-confirmed" class="confirmed-row">当天排班已正式确认。</p>
    <p v-else-if="previewConfirmed" data-testid="export-preview-confirmed" class="preview-row">已核对排班预览；仍有未确认或待匹配车辆的车次，请回到「人工确认」逐一确认后再正式确认当天排班。</p>
    <p v-if="pendingRows.length" data-testid="pending-export-row" class="pending-row">待匹配车辆：{{ pendingRows.map((row) => row.tripLabel).join('、') }}</p>
    <el-table :data="previewRows" stripe><el-table-column prop="tripLabel" label="车次" width="180" /><el-table-column prop="vehicle" label="车辆" /><el-table-column prop="driver" label="司机" /><el-table-column prop="storeOrder" label="门店顺序" min-width="260" /><el-table-column prop="volume" label="准备体积" /><el-table-column prop="loadRate" label="装载率" /><el-table-column prop="distance" label="配送里程" /></el-table>
    <div class="actions">
      <el-button v-if="canConfirmSchedule" data-testid="confirm-schedule" type="primary" size="large" @click="emit('confirm-schedule')">确认当天排班</el-button>
      <el-button v-else data-testid="confirm-export-preview" type="primary" size="large" @click="emit('confirm-preview')">确认排班预览</el-button>
      <span class="export-hint">另存：</span>
      <el-button data-testid="export-csv" :disabled="!canExport" @click="emit('export-csv')">下载 CSV</el-button>
      <el-button data-testid="export-xlsx" :disabled="!canExport" @click="emit('export-xlsx')">下载 Excel</el-button>
    </div>
  </section>
</template>

<style scoped lang="scss">.export-step { display: grid; gap: 20px; } header p { margin: 0 0 6px; color: #1b65a8; font-size: 13px; font-weight: 750; } h2 { margin: 0; color: #101828; } .sub { display: block; margin-top: 6px; color: #667085; font-size: 13px; font-weight: 400; } .actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; } .export-hint { margin-left: 8px; color: #98a2b3; font-size: 13px; } .pending-row { margin: 0; padding: 10px 12px; color: #b54708; font-weight: 600; background: #fffaeb; border-radius: 8px; } .confirmed-row { margin: 0; padding: 10px 12px; color: #027a48; font-weight: 600; background: #ecfdf3; border-radius: 8px; } .preview-row { margin: 0; padding: 10px 12px; color: #175cd3; font-weight: 600; background: #eff8ff; border-radius: 8px; }</style>
