<script setup lang="ts">
import { computed, ref } from 'vue';
import type { StoreOrder } from '../types';

const props = defineProps<{ stores: StoreOrder[]; imported: boolean }>();
const emit = defineEmits<{ (event: 'import-sample'): void }>();

const validStoreCount = computed(() => props.stores.filter((store) => store.name && store.address && store.window).length);
const importValidation = ref('可选择 CSV 文件进行本地字段校验。');

const requiredHeaders = ['门店编号', '门店名称', '配送地址', '时间窗'];

async function validateCsv(event: Event): Promise<void> {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  if (!file.name.toLowerCase().endsWith('.csv')) {
    importValidation.value = '请选择 CSV 文件；当前页面仅在浏览器内校验文件。';
    return;
  }
  const lines = (await file.text()).replace(/^\uFEFF/, '').split(/\r?\n/).filter(Boolean);
  const headers = lines[0]?.split(',').map((header) => header.trim()) ?? [];
  const missing = requiredHeaders.filter((header) => !headers.includes(header));
  if (missing.length) {
    importValidation.value = `缺少必填列：${missing.join('、')}`;
    return;
  }
  const rowCount = Math.max(lines.length - 1, 0);
  const missingValue = lines.slice(1).findIndex((line) => {
    const values = line.split(',').map((value) => value.trim());
    return requiredHeaders.some((header) => !values[headers.indexOf(header)]);
  });
  if (missingValue >= 0) {
    const values = lines[missingValue + 1].split(',').map((value) => value.trim());
    const missingHeader = requiredHeaders.find((header) => !values[headers.indexOf(header)]);
    importValidation.value = `第 ${missingValue + 2} 行缺少${missingHeader}`;
    return;
  }
  importValidation.value = rowCount > 0 ? `已读取 ${rowCount} 行，字段校验通过。` : '文件没有可导入的数据行。';
}

function downloadTemplate(): void {
  const content = '门店编号,门店名称,配送地址,时间窗\nS-001,示例门店,苏州市,09:00-12:00\n';
  const anchor = document.createElement('a');
  anchor.href = URL.createObjectURL(new Blob([content], { type: 'text/csv;charset=utf-8' }));
  anchor.download = '配送订单导入模板.csv';
  anchor.click();
  URL.revokeObjectURL(anchor.href);
}
</script>

<template>
  <section data-testid="import-step" class="step-panel">
    <div class="panel-heading">
      <div><p>第一步</p><h2>导入配送订单</h2><span>核对订单字段与配送地址后开始排程。</span></div>
      <el-button plain @click="downloadTemplate">下载导入模板</el-button>
    </div>
    <div class="validation-card">
      <strong>{{ validStoreCount }} / {{ stores.length }} 家门店信息完整</strong>
      <span>{{ validStoreCount === 13 ? '13 家门店的订单字段与配送地址已通过校验。' : '请补全缺失的门店信息。' }}</span>
    </div>
    <label class="file-input">选择 CSV 文件<input data-testid="csv-input" type="file" accept=".csv,text/csv" @change="validateCsv"></label>
    <p data-testid="import-validation" class="import-validation">{{ importValidation }}</p>
    <p class="persistence-note">文件只在当前浏览器中校验，不会持久化或替换排程数据。</p>
    <button data-testid="import-orders" class="primary-button" type="button" @click="emit('import-sample')">{{ imported ? '重新导入示例订单' : '导入示例订单' }}</button>
  </section>
</template>

<style scoped lang="scss">
.step-panel { display: grid; gap: 20px; min-height: 340px; padding: 28px; background: #fff; border: 1px solid #eaecf0; border-radius: 12px; }
.panel-heading { display: flex; justify-content: space-between; gap: 20px; } p { margin: 0 0 6px; color: #1b65a8; font-size: 13px; font-weight: 750; } h2 { margin: 0; color: #101828; } span { color: #667085; line-height: 1.6; }
.validation-card { display: grid; gap: 6px; padding: 20px; background: #f0f7ff; border-radius: 10px; } strong { color: #101828; font-size: 18px; }
.primary-button { width: fit-content; padding: 10px 18px; color: #fff; font: inherit; font-weight: 650; background: #1b65a8; border: 0; border-radius: 6px; cursor: pointer; }
.file-input { display: grid; gap: 8px; width: fit-content; color: #344054; font-size: 14px; font-weight: 650; }
.import-validation, .persistence-note { margin: 0; color: #475467; font-size: 14px; }
</style>
