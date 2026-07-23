<script setup lang="ts">
import { computed } from 'vue';
import { Operation } from '@element-plus/icons-vue';
import type { BusinessTableColumn } from './businessTableColumns';

const props = withDefaults(defineProps<{
  modelValue: string[];
  columns: BusinessTableColumn[];
  max: number;
  fixedSummary?: string;
}>(), {
  fixedSummary: '主字段、状态和操作固定显示',
});

const emit = defineEmits<{
  (event: 'update:modelValue', value: string[]): void;
  (event: 'reset'): void;
}>();

const selection = computed<string[]>({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value.slice(0, props.max)),
});

function optionDisabled(key: string): boolean {
  return !selection.value.includes(key) && selection.value.length >= props.max;
}
</script>

<template>
  <el-popover placement="bottom-end" :width="320" trigger="click">
    <template #reference>
      <el-button :icon="Operation">
        显示列 {{ modelValue.length }}/{{ max }}
      </el-button>
    </template>
    <div class="column-selector">
      <div class="column-selector__heading">
        <div>
          <strong>选择补充列</strong>
          <p>{{ fixedSummary }}，最多再显示 {{ max }} 项。</p>
        </div>
        <el-button link type="primary" @click="emit('reset')">恢复默认</el-button>
      </div>
      <el-checkbox-group v-model="selection" class="column-selector__options">
        <el-checkbox
          v-for="column in columns"
          :key="column.key"
          :value="column.key"
          :disabled="optionDisabled(column.key)"
        >
          {{ column.label }}
        </el-checkbox>
      </el-checkbox-group>
      <div v-if="modelValue.length >= max" class="column-selector__limit">
        已达到 {{ max }} 项上限；请先取消一项再选择。
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
.column-selector {
  display: grid;
  gap: 12px;
}

.column-selector__heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.column-selector__heading p {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.column-selector__options {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px 12px;
}

.column-selector__options :deep(.el-checkbox) {
  margin-right: 0;
}

.column-selector__limit {
  color: var(--el-color-warning-dark-2);
  font-size: 12px;
}
</style>
