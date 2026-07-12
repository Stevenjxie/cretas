<template>
  <div class="material-node" :class="[`kind-${kind.toLowerCase()}`, { selected }]">
    <Handle v-if="kind !== 'RAW_MATERIAL'" type="target" :position="Position.Left" id="input" />
    <Handle v-if="kind !== 'FINISHED_GOOD'" type="source" :position="Position.Right" id="output" />

    <div class="node-heading">
      <span class="kind-mark">{{ kindMark }}</span>
      <div>
        <div class="kind-label">{{ kindLabel }}</div>
        <div class="material-name">{{ data.name || '未命名物料' }}</div>
      </div>
      <el-tag size="small" :type="data.skuId ? 'success' : 'warning'">
        {{ data.skuId ? '已绑定' : '待绑定' }}
      </el-tag>
    </div>

    <div class="identity-row">
      <span>SKU</span>
      <strong>{{ data.skuCode || data.skuId || '尚未选择' }}</strong>
    </div>
    <div v-if="data.specification" class="specification">{{ data.specification }}</div>

    <el-select
      v-if="kind === 'RAW_MATERIAL' && canWrite"
      class="nodrag nowheel raw-selector"
      :model-value="data.skuId"
      placeholder="选择入口原料 SKU"
      filterable
      size="small"
      :filter-method="handleRawFilter"
      @visible-change="handleRawVisibleChange"
      @change="(value: string) => emit('selectRawSku', value)"
    >
      <el-option
        v-for="option in filteredRawMaterialOptions"
        :key="option.id"
        :label="`${option.name} · ${option.unit || '-'}`"
        :value="option.id"
      />
    </el-select>

    <WorkflowSkuPicker
      v-if="(kind === 'SEMI_FINISHED' || kind === 'FINISHED_GOOD') && canWrite"
      class="sku-selector"
      test-id="material-sku-select"
      :model-value="data.skuId"
      :semi-options="semiOptions"
      :finished-options="finishedOptions"
      @change="(value) => emit('selectSku', value)"
    />

    <div v-if="kind !== 'FINISHED_GOOD' && canWrite" class="node-actions nodrag">
      <el-button size="small" text type="primary" @click="emit('addNext')">+ 后续工序</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { Handle, Position } from '@vue-flow/core';
import { matchesSearchText } from './pinyinInitials';
import WorkflowSkuPicker, { type WorkflowSkuPickerOption } from './WorkflowSkuPicker.vue';
import type { MaterialNodeData, ProductProcessNodeKind } from './types';

const props = defineProps<{
  kind: Exclude<ProductProcessNodeKind, 'PROCESS'>;
  data: MaterialNodeData;
  selected?: boolean;
  canWrite: boolean;
  rawMaterialOptions: Array<{ id: string; name: string; unit?: string }>;
  semiOptions: WorkflowSkuPickerOption[];
  finishedOptions: WorkflowSkuPickerOption[];
}>();

const emit = defineEmits<{
  addNext: [];
  selectRawSku: [skuId: string];
  selectSku: [skuId: string];
}>();

// 原料下拉的拼音首字母搜索：filter-method 只负责生成过滤后的候选列表，
// 不像 el-cascader/el-select 默认 filterable 那样只能按 label 原文匹配。
const rawFilterQuery = ref('');

function handleRawFilter(query: string): void {
  rawFilterQuery.value = query || '';
}

function handleRawVisibleChange(visible: boolean): void {
  if (!visible) rawFilterQuery.value = '';
}

const filteredRawMaterialOptions = computed(() => {
  const query = rawFilterQuery.value.trim();
  if (!query) return props.rawMaterialOptions;
  return props.rawMaterialOptions.filter((option) => matchesSearchText(query, option.name));
});

const kindLabel = computed(() => ({
  RAW_MATERIAL: '原料 Cell',
  SEMI_FINISHED: '半成品 Cell',
  FINISHED_GOOD: '成品 Cell',
})[props.kind]);

const kindMark = computed(() => ({
  RAW_MATERIAL: '原',
  SEMI_FINISHED: '半',
  FINISHED_GOOD: '成',
})[props.kind]);
</script>

<style scoped>
.material-node {
  width: 210px;
  padding: 12px;
  border: 1px solid #d8e4ef;
  border-left: 4px solid #67c23a;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.08);
}
.material-node.selected { box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.28); }
/* 原料=蓝灰, 半成品=绿(默认), 成品=紫 */
.material-node.kind-raw_material { border-left-color: #6b7c93; }
.material-node.kind-finished_good { border-left-color: #8b5cf6; }
.node-heading { display: flex; align-items: flex-start; gap: 8px; }
.kind-mark {
  display: grid; place-items: center; width: 28px; height: 28px; flex: 0 0 auto;
  border-radius: 7px; color: #2f8a3d; background: #edf9e8; font-weight: 700;
}
.kind-raw_material .kind-mark { color: #4a5b73; background: #eef1f6; }
.kind-finished_good .kind-mark { color: #7141d8; background: #f2ecff; }
.node-heading > div { min-width: 0; flex: 1; }
.kind-label { color: #7a8599; font-size: 11px; }
.material-name { margin-top: 2px; color: #1a2332; font-size: 14px; font-weight: 650; line-height: 1.35; }
.identity-row {
  display: flex; justify-content: space-between; gap: 8px; margin-top: 12px;
  padding: 8px; border-radius: 7px; background: #f7f9fc; font-size: 11px; color: #7a8599;
}
.identity-row strong { overflow: hidden; color: #344054; text-overflow: ellipsis; white-space: nowrap; }
.specification { margin-top: 6px; color: #7a8599; font-size: 11px; }
.raw-selector, .sku-selector { width: 100%; margin-top: 8px; }
.node-actions { display: flex; justify-content: flex-end; margin-top: 6px; }
</style>
