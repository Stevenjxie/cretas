<template>
  <el-select
    v-if="batches.length > 0 || alwaysShow"
    :model-value="modelValue"
    @update:model-value="onChange"
    style="width: 300px; margin-right: 8px;"
    size="small"
  >
    <!-- T6 Phase-2 融合选项 (Phase 2 「全部(按时间融合)」sentinel) -->
    <el-option
      value="__FUSED__"
      label="全部(按时间融合)"
    >
      <div style="display: flex; justify-content: space-between; align-items: center;">
        <span>
          <el-tag size="small" type="primary" style="margin-right: 6px;">时间融合</el-tag>
          全部数据
        </span>
        <span style="color: var(--color-text-secondary, #909399); font-size: 12px; margin-left: 12px;">
          自动合并所有上传
        </span>
      </div>
    </el-option>
    <el-option
      v-for="(batch, idx) in batches"
      :key="idx"
      :label="formatBatchLabel(batch)"
      :value="idx"
    >
      <div style="display: flex; justify-content: space-between; align-items: center;">
        <span>
          <el-tag v-if="isAutoSyncBatch(batch)" size="small" type="success" style="margin-right: 6px;">自动同步</el-tag>
          {{ safeBatchName(batch) }}
        </span>
        <span style="color: var(--color-text-secondary, #909399); font-size: 12px; margin-left: 12px;">
          {{ batch.uploadTime }} · {{ batch.sheetCount }} 表
        </span>
      </div>
    </el-option>
  </el-select>
</template>

<script setup lang="ts">
import type { UploadHistoryItem } from '@/api/smartbi';

export type UploadSwitcherValue = number | '__FUSED__';

interface UploadBatch {
  fileName: string;
  uploadTime: string;
  sheetCount: number;
  totalRows: number;
  uploads: UploadHistoryItem[];
  uploadId?: number;
  id?: number;
}

defineProps<{
  batches: UploadBatch[];
  /** Current selected value — numeric batch index or '__FUSED__' sentinel */
  modelValue: UploadSwitcherValue;
  formatBatchLabel: (batch: UploadBatch) => string;
  isAutoSyncBatch: (batch: UploadBatch) => boolean;
  safeBatchName: (batch: UploadBatch) => string;
  /** When true, show switcher even when batches list is empty */
  alwaysShow?: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: UploadSwitcherValue): void;
  (e: 'change', value: UploadSwitcherValue): void;
}>();

const onChange = (v: UploadSwitcherValue) => {
  emit('update:modelValue', v);
  // Important: emit 'change' separately so parent's selectBatch (which holds
  // Phase 6 dropdown async race guards in its enrichSheet + idleEnrichNext
  // callbacks) fires after v-model has settled.
  emit('change', v);
};
</script>
