<script setup lang="ts">
import type { SeasoningBindingView, SeasoningProcessView } from '@/api/bom';
import { Edit, Delete, Plus } from '@element-plus/icons-vue';
import { otherProcessUsages } from './seasoningModel';

const props = defineProps<{
  process: SeasoningProcessView;
  processes: SeasoningProcessView[];
  expanded: boolean;
  editable: boolean;
}>();

const emit = defineEmits<{
  toggle: [];
  add: [process: SeasoningProcessView];
  edit: [process: SeasoningProcessView, binding: SeasoningBindingView];
  delete: [binding: SeasoningBindingView];
}>();

function priceOf(binding: SeasoningBindingView): number | null {
  return binding.priceSnapshot ?? binding.priceSource1 ?? null;
}

function bindingName(binding: SeasoningBindingView): string {
  return binding.materialName || binding.name;
}
</script>

<template>
  <el-card
    :data-testid="`seasoning-process-${process.workProcessId}`"
    shadow="never"
    class="process-card"
  >
    <template #header>
      <button type="button" class="process-card__header" @click="emit('toggle')">
        <span class="process-card__order">{{ process.processOrder }}</span>
        <span class="process-card__title">{{ process.processName }}</span>
        <el-tag size="small" type="info">{{ process.bindings.length }} 种调料</el-tag>
        <span class="process-card__chevron">{{ expanded ? '收起' : '展开' }}</span>
      </button>
    </template>

    <div v-show="expanded" class="process-card__body">
      <div class="process-card__actions">
        <span>每 1 kg 本工序半成品投入量</span>
        <el-button
          v-if="editable"
          data-testid="add-seasoning-binding"
          type="primary"
          size="small"
          :icon="Plus"
          @click="emit('add', process)"
        >添加调料</el-button>
      </div>
      <el-table :data="process.bindings" size="small" border empty-text="本工序暂无调料">
        <el-table-column label="调料" min-width="170">
          <template #default="{ row }">
            <strong>{{ bindingName(row) }}</strong>
            <div
              v-if="otherProcessUsages(processes, row.materialTypeId, process.workProcessId).length"
              class="reuse-hint"
            >另用于 {{ otherProcessUsages(processes, row.materialTypeId, process.workProcessId).length }} 个工序</div>
          </template>
        </el-table-column>
        <el-table-column label="投入量" width="130" align="right">
          <template #default="{ row }">{{ Number(row.dosagePerKgG).toFixed(4) }} g/kg</template>
        </el-table-column>
        <el-table-column label="锅序" min-width="150">
          <template #default="{ row }">
            {{ row.subsequentPotRatio == null ? '不按锅序' : `首锅 100% · 后续 ${Number(row.subsequentPotRatio * 100).toFixed(2)}%` }}
          </template>
        </el-table-column>
        <el-table-column label="自动单价" width="120" align="right">
          <template #default="{ row }">{{ priceOf(row) == null ? '保存时自动带入' : `¥${Number(priceOf(row)).toFixed(4)}` }}</template>
        </el-table-column>
        <el-table-column label="计入成本" width="90" align="center">
          <template #default="{ row }">{{ row.countInSeasoning ? '计入' : '不计入' }}</template>
        </el-table-column>
        <el-table-column v-if="editable" label="操作" width="100" align="center">
          <template #default="{ row }">
            <el-button link type="primary" :icon="Edit" @click="emit('edit', process, row)" />
            <el-button link type="danger" :icon="Delete" @click="emit('delete', row)" />
          </template>
        </el-table-column>
      </el-table>
    </div>
  </el-card>
</template>

<style scoped>
.process-card { margin-bottom: 10px; }
.process-card :deep(.el-card__header) { padding: 0; }
.process-card__header { width: 100%; min-height: 52px; padding: 9px 12px; border: 0; background: transparent; display: flex; align-items: center; gap: 9px; cursor: pointer; text-align: left; }
.process-card__order { width: 26px; height: 26px; border-radius: 50%; display: inline-flex; align-items: center; justify-content: center; background: var(--el-color-primary-light-9); color: var(--el-color-primary); font-weight: 700; }
.process-card__title { flex: 1; font-weight: 600; }
.process-card__chevron, .process-card__actions, .reuse-hint { color: var(--el-text-color-secondary); font-size: 12px; }
.process-card__actions { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.reuse-hint { margin-top: 2px; }
</style>
