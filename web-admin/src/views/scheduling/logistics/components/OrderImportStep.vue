<script setup lang="ts">
import { computed, ref } from 'vue';
import type { OrderBatch, PreviewResult } from '@/api/logistics';

const props = defineProps<{
  preview: PreviewResult | null;
  batch: OrderBatch | null;
  uploading: boolean;
  committing: boolean;
  error: string | null;
}>();

const emit = defineEmits<{
  (event: 'download-template'): void;
  (event: 'upload-file', file: File): void;
  (event: 'commit'): void;
}>();

const fileInput = ref<HTMLInputElement | null>(null);
const selectedFileName = ref('');

const canCommit = computed(() => Boolean(props.preview) && props.preview!.validRows > 0 && !props.committing);
const rowErrorPreview = computed(() => (props.preview?.rowErrors ?? []).slice(0, 20));

function handleFileChange(event: Event): void {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  selectedFileName.value = file.name;
  emit('upload-file', file);
}

function resetFileInput(): void {
  selectedFileName.value = '';
  if (fileInput.value) fileInput.value.value = '';
}
</script>

<template>
  <section data-testid="import-step" class="step-panel">
    <div class="panel-heading">
      <div><p>第一步</p><h2>导入配送订单</h2><span>上传当天订单文件，系统校验后再提交写入数据库。</span></div>
      <el-button plain @click="emit('download-template')">下载导入模板</el-button>
    </div>

    <div v-if="batch" class="batch-card" data-testid="import-batch-summary">
      <strong>已导入批次 {{ batch.batchNumber }}</strong>
      <span>{{ batch.validRows }} / {{ batch.totalRows }} 行有效，业务日期 {{ batch.businessDate }}</span>
    </div>

    <label class="file-input">选择订单文件（CSV / Excel）
      <input
        ref="fileInput"
        data-testid="csv-input"
        type="file"
        accept=".csv,text/csv,.xlsx,.xls,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        :disabled="uploading"
        @change="handleFileChange"
      >
    </label>
    <p v-if="selectedFileName" class="selected-file">已选择：{{ selectedFileName }}</p>
    <p v-if="uploading" data-testid="import-uploading" class="import-status">正在解析文件…</p>

    <div v-if="preview" data-testid="import-preview" class="validation-card">
      <strong>{{ preview.validRows }} / {{ preview.totalRows }} 行校验通过</strong>
      <span v-if="preview.errorRows > 0">{{ preview.errorRows }} 行存在字段错误，提交后仅写入有效行。</span>
      <span v-else>全部行校验通过，可以提交导入。</span>
      <ul v-if="rowErrorPreview.length" data-testid="import-row-errors" class="row-error-list">
        <li v-for="(rowError, index) in rowErrorPreview" :key="`${rowError.rowNumber}-${rowError.column}-${index}`">
          第 {{ rowError.rowNumber }} 行 · {{ rowError.column }}：{{ rowError.message }}
        </li>
      </ul>
      <p v-if="preview.rowErrors.length > rowErrorPreview.length" class="row-error-more">
        还有 {{ preview.rowErrors.length - rowErrorPreview.length }} 条错误未显示。
      </p>
    </div>

    <p v-if="error" data-testid="import-error" class="import-error">{{ error }}</p>
    <p v-if="!preview && !batch && !uploading" class="empty-hint">尚未导入任何订单文件——下方地图与线路将保持空白，不会使用示例数据。</p>

    <button
      data-testid="commit-import"
      class="primary-button"
      type="button"
      :disabled="!canCommit"
      @click="emit('commit'); resetFileInput()"
    >
      {{ committing ? '正在提交…' : '提交导入' }}
    </button>
  </section>
</template>

<style scoped lang="scss">
.step-panel { display: grid; gap: 20px; min-height: 340px; padding: 28px; background: #fff; border: 1px solid #eaecf0; border-radius: 12px; }
.panel-heading { display: flex; justify-content: space-between; gap: 20px; } p { margin: 0 0 6px; color: #1b65a8; font-size: 13px; font-weight: 750; } h2 { margin: 0; color: #101828; } span { color: #667085; line-height: 1.6; }
.batch-card { display: grid; gap: 4px; padding: 16px 20px; background: #ecfdf3; border-radius: 10px; } .batch-card strong { color: #027a48; font-size: 15px; }
.validation-card { display: grid; gap: 6px; padding: 20px; background: #f0f7ff; border-radius: 10px; } strong { color: #101828; font-size: 18px; }
.row-error-list { display: grid; gap: 4px; margin: 6px 0 0; padding-left: 20px; color: #b42318; font-size: 13px; }
.row-error-more { margin: 0; color: #b42318; font-size: 13px; }
.primary-button { width: fit-content; padding: 10px 18px; color: #fff; font: inherit; font-weight: 650; background: #1b65a8; border: 0; border-radius: 6px; cursor: pointer; }
.primary-button:disabled { background: #98a2b3; cursor: not-allowed; }
.file-input { display: grid; gap: 8px; width: fit-content; color: #344054; font-size: 14px; font-weight: 650; }
.selected-file, .import-status, .empty-hint { margin: 0; color: #475467; font-size: 14px; }
.import-error { margin: 0; padding: 10px 12px; color: #b42318; background: #fef3f2; border-radius: 8px; font-size: 14px; }
</style>
