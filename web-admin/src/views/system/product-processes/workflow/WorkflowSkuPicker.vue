<template>
  <el-select
    class="nodrag nowheel workflow-sku-picker"
    :data-testid="testId"
    :model-value="modelValue"
    :placeholder="placeholder || '选择或现场创建 SKU'"
    filterable
    :disabled="disabled"
    :size="size || 'small'"
    :filter-method="handleFilter"
    @visible-change="handleVisibleChange"
    @change="(value: string) => emit('change', value)"
  >
    <el-option-group label="半成品">
      <el-option class="create-option" label="＋ 现场创建半成品 SKU" value="__CREATE__" />
      <el-option
        v-for="option in filteredSemiOptions"
        :key="option.id"
        :label="optionLabel(option)"
        :value="option.id"
      />
    </el-option-group>
    <el-option-group label="成品">
      <el-option
        v-for="option in filteredFinishedOptions"
        :key="option.id"
        :label="optionLabel(option)"
        :value="option.id"
      />
    </el-option-group>
  </el-select>
</template>

<script setup lang="ts">
import { usePinyinFilter } from './pinyinInitials';

/**
 * 半成品/成品两级产品选择器，供「物料 Cell」和「工序 Cell 产出行」共用一套组件，
 * 保证两处的分组、现场创建入口、单位联动、拼音首字母搜索行为完全一致。
 *
 * 为什么用「分组 el-select」而不是 el-cascader：
 * - 值类型直接是 skuId（string），跟现有 data.skuId / port.skuId 绑定方式一致，
 *   不需要 cascader 的 [level1, level2] 路径数组 <-> skuId 的来回转换。
 * - el-cascader 选中一个已绑定 SKU 时，要从 skuId 反查它属于半成品还是成品分支
 *   才能还原选中路径（hydrate 时尤其麻烦）；分组 el-select 不存在这个问题，
 *   value 本身就是 skuId，选哪个组只影响“看起来在哪一屏”，不影响值的形状。
 * - el-select 同样原生支持 filterable + filter-method，拼音首字母搜索两者代价相同。
 */

export interface WorkflowSkuPickerOption {
  id: string;
  name: string;
  unit?: string;
  code?: string;
}

const props = defineProps<{
  modelValue: string;
  semiOptions: WorkflowSkuPickerOption[];
  finishedOptions: WorkflowSkuPickerOption[];
  disabled?: boolean;
  size?: 'small' | 'default' | 'large';
  placeholder?: string;
  testId?: string;
}>();

const emit = defineEmits<{
  change: [skuId: string];
}>();

// 拼音首字母搜索：复用全局共享的 usePinyinFilter composable (#2)，两组各自独立
// 过滤，但共用同一个 query（半成品/成品是同一个搜索框驱动的两个分组）。
const semiFilter = usePinyinFilter(
  () => props.semiOptions,
  (option) => [option.name, option.code],
);
const finishedFilter = usePinyinFilter(
  () => props.finishedOptions,
  (option) => [option.name, option.code],
);

function handleFilter(query: string): void {
  semiFilter.handleFilter(query);
  finishedFilter.handleFilter(query);
}

function handleVisibleChange(visible: boolean): void {
  semiFilter.handleVisibleChange(visible);
  finishedFilter.handleVisibleChange(visible);
}

function optionLabel(option: WorkflowSkuPickerOption): string {
  return `${option.name} · ${option.unit || '-'}`;
}

// 现场创建入口固定放在「半成品」分组第一位，不参与过滤（搜索时也始终可见，
// 保证找不到匹配 SKU 时仍有创建出口，符合 fool-proof-design Rule 5：不留死胡同）。
const filteredSemiOptions = semiFilter.filtered;
const filteredFinishedOptions = finishedFilter.filtered;
</script>

<style scoped>
.workflow-sku-picker { width: 100%; }
.create-option { color: #409eff; font-weight: 600; }
</style>
