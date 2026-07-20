<template>
  <el-dialog
    :model-value="modelValue"
    title="导入供应商"
    width="960px"
    destroy-on-close
    :close-on-click-modal="false"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <el-steps :active="preview ? 2 : selectedFile ? 1 : 0" finish-status="success" simple>
      <el-step title="选择文件" />
      <el-step title="确认字段映射" />
      <el-step title="预览并确认" />
    </el-steps>

    <el-alert class="zero-write-alert" type="info" :closable="false" show-icon>
      <template #title>选择文件、字段识别和预览阶段不会创建或修改供应商</template>
    </el-alert>

    <div class="mode-row">
      <el-radio-group v-model="mode" :disabled="Boolean(preview)">
        <el-radio-button value="STANDARD">标准模板</el-radio-button>
        <el-radio-button value="SMART">智能识别</el-radio-button>
      </el-radio-group>
      <span class="mode-help">
        {{ mode === 'STANDARD' ? '严格按标准列名识别' : '支持常见中文别名和任意列顺序，映射需人工确认' }}
      </span>
    </div>

    <el-upload
      v-if="!preview"
      drag
      action="#"
      accept=".xlsx,.xls"
      :auto-upload="false"
      :limit="1"
      :on-change="handleFileChange"
      :on-remove="resetFile"
    >
      <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
      <div class="el-upload__text">拖入 Excel 或<em>点击选择</em></div>
      <template #tip>
        <div class="el-upload__tip">仅支持 xlsx/xls，最大 10MB；请勿上传带宏文件。</div>
      </template>
    </el-upload>

    <template v-if="selectedFile && !preview">
      <div class="mapping-title">
        <strong>源列 → 供应商字段</strong>
        <el-tag v-if="missingRequired.length" type="danger" effect="plain">
          缺少必填映射：{{ missingRequired.join('、') }}
        </el-tag>
        <el-tag v-else type="success" effect="plain">必填字段映射完整</el-tag>
      </div>
      <el-table :data="mappingRows" border size="small" max-height="300">
        <el-table-column prop="sourceHeader" label="Excel 源列" min-width="220" />
        <el-table-column label="目标字段" min-width="240">
          <template #default="{ row }">
            <el-select v-model="mapping[row.sourceHeader]" clearable placeholder="忽略此列" style="width: 100%">
              <el-option
                v-for="field in SUPPLIER_IMPORT_FIELDS"
                :key="field.key"
                :label="`${field.label}${field.required ? '（必填）' : ''}`"
                :value="field.key"
                :disabled="fieldMappedElsewhere(field.key, row.sourceHeader)"
              />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="识别状态" width="140">
          <template #default="{ row }">
            <el-tag v-if="mapping[row.sourceHeader]" type="success" effect="plain">已确认</el-tag>
            <el-tag v-else type="info" effect="plain">忽略</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <div class="local-check">
        本地预检：有效候选 {{ localCounts.valid }}，字段错误 {{ localCounts.error }}，空行 {{ localCounts.ignored }}。
        数据库重复项将在“生成预览”时只读核对。
      </div>
    </template>

    <template v-if="preview">
      <div class="preview-counts">
        <el-tag effect="plain">总行数 {{ previewCount('TOTAL') }}</el-tag>
        <el-tag type="success" effect="plain">可新增 {{ previewCount('VALID') }}</el-tag>
        <el-tag type="warning" effect="plain">疑似重复 {{ previewCount('DUPLICATE') }}</el-tag>
        <el-tag type="danger" effect="plain">字段错误 {{ previewCount('ERROR') }}</el-tag>
        <el-tag type="info" effect="plain">空行/忽略 {{ previewCount('IGNORED') }}</el-tag>
      </div>
      <el-table :data="preview.rows" border stripe max-height="420" row-key="rowNumber">
        <el-table-column width="52" align="center">
          <template #default="{ row }">
            <el-checkbox v-model="row.selected" :disabled="row.classification !== 'VALID'" />
          </template>
        </el-table-column>
        <el-table-column prop="rowNumber" label="原行号" width="82" />
        <el-table-column label="供应商名称" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ row.data?.name || '-' }}</template>
        </el-table-column>
        <el-table-column label="联系人" width="120">
          <template #default="{ row }">{{ row.data?.contactPerson || '-' }}</template>
        </el-table-column>
        <el-table-column label="联系电话" width="150">
          <template #default="{ row }">{{ row.data?.phone || '-' }}</template>
        </el-table-column>
        <el-table-column label="地址" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.data?.address || '-' }}</template>
        </el-table-column>
        <el-table-column label="分类" width="110">
          <template #default="{ row }">
            <el-tag :type="classificationTag(row.classification)" effect="plain">
              {{ classificationLabel(row.classification) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            {{ errorText(row.errors) || '校验通过' }}
          </template>
        </el-table-column>
      </el-table>
    </template>

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="$emit('update:modelValue', false)">取消</el-button>
        <el-button v-if="preview" @click="resetFile">重新选择</el-button>
        <el-button
          v-if="preview && previewCount('ERROR') + previewCount('DUPLICATE') > 0"
          @click="downloadErrors"
        >下载错误报告</el-button>
        <el-button
          v-if="selectedFile && !preview"
          type="primary"
          :loading="previewing"
          :disabled="missingRequired.length > 0"
          @click="buildPreview"
        >生成预览</el-button>
        <el-button
          v-if="preview"
          type="primary"
          :loading="confirming"
          :disabled="selectedValidRows.length === 0 || selectedValidRows.length > MAX_CONFIRM_ROWS"
          @click="confirmImport"
        >确认新增 {{ selectedValidRows.length }} 条（单次最多 {{ MAX_CONFIRM_ROWS }} 条）</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { UploadFilled } from '@element-plus/icons-vue';
import { ElMessage, type TagProps, type UploadFile } from 'element-plus';
import {
  confirmSupplierImport,
  downloadSupplierImportErrors,
  previewSupplierImport,
  type SupplierImportClassification,
  type SupplierImportMode,
  type SupplierImportPreview,
} from '@/api/supplierManagement';
import {
  SUPPLIER_IMPORT_FIELDS,
  mappedSupplierRows,
  missingRequiredMappings,
  parseSupplierWorkbook,
  suggestSupplierMappings,
  type SupplierColumnMapping,
  type SupplierImportField,
} from './supplierImport';

const props = defineProps<{ modelValue: boolean; factoryId: string }>();
const emit = defineEmits<{ (event: 'update:modelValue', value: boolean): void; (event: 'imported'): void }>();

const mode = ref<SupplierImportMode>('STANDARD');
const selectedFile = ref<File | null>(null);
const headers = ref<string[]>([]);
const localRows = ref<ReturnType<typeof mappedSupplierRows>>([]);
const mapping = ref<SupplierColumnMapping>({});
const preview = ref<SupplierImportPreview | null>(null);
const previewing = ref(false);
const confirming = ref(false);
const MAX_CONFIRM_ROWS = 1000;

const mappingRows = computed(() => headers.value.map((sourceHeader) => ({ sourceHeader })));
const missingRequired = computed(() => missingRequiredMappings(mapping.value));
const localCounts = computed(() => ({
  valid: localRows.value.filter((row) => !row.ignored && row.errors.length === 0).length,
  error: localRows.value.filter((row) => !row.ignored && row.errors.length > 0).length,
  ignored: localRows.value.filter((row) => row.ignored).length,
}));
const selectedValidRows = computed(() => preview.value?.rows.filter(
  (row) => row.classification === 'VALID' && row.selected,
) ?? []);

watch(mode, async () => {
  if (!selectedFile.value || preview.value) return;
  mapping.value = suggestSupplierMappings(headers.value, mode.value);
  await refreshLocalRows();
});

watch(mapping, refreshLocalRows, { deep: true });

function fieldMappedElsewhere(field: SupplierImportField, currentHeader: string): boolean {
  return Object.entries(mapping.value).some(([header, mapped]) => header !== currentHeader && mapped === field);
}

async function refreshLocalRows(): Promise<void> {
  if (!selectedFile.value) return;
  const table = await parseSupplierWorkbook(selectedFile.value);
  localRows.value = mappedSupplierRows(table, mapping.value);
}

async function handleFileChange(upload: UploadFile): Promise<void> {
  try {
    const file = upload.raw as File | undefined;
    if (!file) return;
    const table = await parseSupplierWorkbook(file);
    if (!table.headers.length) throw new Error('未识别到 Excel 表头');
    if (table.rows.length > 5000) throw new Error('单次最多导入 5000 行');
    selectedFile.value = file;
    headers.value = table.headers;
    mapping.value = suggestSupplierMappings(table.headers, mode.value);
    localRows.value = mappedSupplierRows(table, mapping.value);
  } catch (error) {
    resetFile();
    ElMessage.error((error as Error).message);
  }
}

function resetFile(): void {
  selectedFile.value = null;
  headers.value = [];
  mapping.value = {};
  localRows.value = [];
  preview.value = null;
}

async function buildPreview(): Promise<void> {
  if (!selectedFile.value || missingRequired.value.length) return;
  previewing.value = true;
  try {
    const result = await previewSupplierImport(
      props.factoryId,
      selectedFile.value,
      mode.value,
      Object.fromEntries(Object.entries(mapping.value).filter((entry) => entry[1])),
    );
    result.rows = result.rows.map((row) => ({
      ...row,
      selected: row.classification === 'VALID',
    }));
    preview.value = result;
  } finally {
    previewing.value = false;
  }
}

async function confirmImport(): Promise<void> {
  if (!preview.value || !selectedValidRows.value.length) return;
  if (selectedValidRows.value.length > MAX_CONFIRM_ROWS) {
    ElMessage.warning(`单次最多确认 ${MAX_CONFIRM_ROWS} 条，请减少勾选数量后重试`);
    return;
  }
  confirming.value = true;
  try {
    const rowKey = selectedValidRows.value.map((row) => row.rowNumber).sort((a, b) => a - b).join('-');
    const result = await confirmSupplierImport(props.factoryId, {
      fileDigest: preview.value.fileDigest,
      idempotencyKey: `supplier-import:${preview.value.fileDigest}:${rowKey}`,
      rows: selectedValidRows.value.flatMap((row) => row.data ? [row.data] : []),
    });
    ElMessage.success(`导入完成：新增 ${result.createdCount}，跳过 ${result.skippedCount}，失败 ${result.failedCount}`);
    emit('imported');
    emit('update:modelValue', false);
  } finally {
    confirming.value = false;
  }
}

async function downloadErrors(): Promise<void> {
  if (preview.value) await downloadSupplierImportErrors(props.factoryId, preview.value);
}

function classificationLabel(value: SupplierImportClassification): string {
  return { VALID: '可新增', DUPLICATE: '疑似重复', ERROR: '字段错误', IGNORED: '已忽略' }[value];
}

function classificationTag(value: SupplierImportClassification): TagProps['type'] {
  return { VALID: 'success', DUPLICATE: 'warning', ERROR: 'danger', IGNORED: 'info' }[value] as TagProps['type'];
}

function previewCount(kind: string): number {
  if (!preview.value) return 0;
  return preview.value.counts[kind] ?? preview.value.counts[kind.toLowerCase()] ?? 0;
}

function errorText(errors?: Record<string, string>): string {
  return errors ? Object.values(errors).filter(Boolean).join('；') : '';
}
</script>

<style scoped lang="scss">
.zero-write-alert { margin: 16px 0; }
.mode-row { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.mode-help, .local-check { color: var(--el-text-color-secondary); font-size: 13px; }
.mapping-title, .preview-counts { display: flex; align-items: center; gap: 8px; margin: 18px 0 10px; }
.local-check { margin-top: 10px; }
.dialog-footer { display: flex; justify-content: flex-end; gap: 8px; }
</style>
