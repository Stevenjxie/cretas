<script setup lang="ts">
import { computed } from 'vue';
import type { StoreOrder } from '../types';

const props = defineProps<{ stores: StoreOrder[]; imported: boolean }>();
const emit = defineEmits<{ (event: 'import'): void }>();

const validStoreCount = computed(() => props.stores.filter((store) => store.name && store.address && store.window).length);

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
    <button data-testid="import-orders" class="primary-button" type="button" @click="emit('import')">
      {{ imported ? '重新导入订单' : '导入订单' }}
    </button>
  </section>
</template>

<style scoped lang="scss">
.step-panel { display: grid; gap: 20px; min-height: 340px; padding: 28px; background: #fff; border: 1px solid #eaecf0; border-radius: 12px; }
.panel-heading { display: flex; justify-content: space-between; gap: 20px; } p { margin: 0 0 6px; color: #1b65a8; font-size: 13px; font-weight: 750; } h2 { margin: 0; color: #101828; } span { color: #667085; line-height: 1.6; }
.validation-card { display: grid; gap: 6px; padding: 20px; background: #f0f7ff; border-radius: 10px; } strong { color: #101828; font-size: 18px; }
.primary-button { width: fit-content; padding: 10px 18px; color: #fff; font: inherit; font-weight: 650; background: #1b65a8; border: 0; border-radius: 6px; cursor: pointer; }
</style>
