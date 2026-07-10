<script setup lang="ts">
import type { ExportRow } from '../types';

defineProps<{ rows: ExportRow[] }>();
const emit = defineEmits<{ (event: 'confirm'): void }>();

function downloadCsv(rows: ExportRow[]): void {
  const header = ['车次', '车辆', '司机', '门店顺序', '体积', '装载率', '里程'];
  const lines = rows.map((row) => [row.tripId, row.vehicle, row.driver, row.storeOrder, row.volume, row.loadRate, row.distance]
    .map((value) => `"${String(value).replaceAll('"', '""')}"`).join(','));
  const anchor = document.createElement('a');
  anchor.href = URL.createObjectURL(new Blob([`\ufeff${header.join(',')}\n${lines.join('\n')}`], { type: 'text/csv;charset=utf-8' }));
  anchor.download = '配送排程.csv';
  anchor.click();
  URL.revokeObjectURL(anchor.href);
}
</script>

<template>
  <section data-testid="export-step" class="export-step"><header><p>第四步</p><h2>导出排程</h2></header>
    <el-table :data="rows" stripe><el-table-column prop="tripId" label="车次" /><el-table-column prop="vehicle" label="车辆" /><el-table-column prop="driver" label="司机" /><el-table-column prop="storeOrder" label="门店顺序" /><el-table-column prop="loadRate" label="装载率" /></el-table>
    <div><el-button type="primary" @click="emit('confirm')">确认排程</el-button><el-button :disabled="!rows.length" @click="downloadCsv(rows)">下载 CSV</el-button></div>
  </section>
</template>

<style scoped lang="scss">.export-step { display: grid; gap: 20px; } p { margin: 0 0 6px; color: #1b65a8; font-size: 13px; font-weight: 750; } h2 { margin: 0; color: #101828; }</style>
