<template>
  <el-dialog
    :model-value="modelValue"
    title="从上传解析生成草稿送货单"
    width="900px"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <!-- Step 1: 选择/粘贴解析结果 -->
    <template v-if="!mapping">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 16px"
        title="复用 SmartBI Excel/CSV 解析结果"
      >
        <template #default>
          上传 Excel/CSV 或粘贴表格内容，系统会自动识别「供应商 / 送货日期 / 食材 / 规格 / 单位 /
          数量 / 单价 / 金额 / 备注」并生成<strong>草稿</strong>送货单供人工确认。<strong>不会自动入库。</strong>
        </template>
      </el-alert>

      <el-upload
        drag
        action=""
        :auto-upload="false"
        :limit="1"
        accept=".xlsx,.xls,.csv,text/csv,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        :on-change="onFileChange"
        :show-file-list="false"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖拽 Excel/CSV 文件到此处，或<em>点击上传</em></div>
      </el-upload>

      <el-divider>或粘贴表格 (首行表头，制表符/逗号分隔)</el-divider>
      <el-input
        v-model="pasteText"
        type="textarea"
        :rows="6"
        placeholder="供应商,送货日期,食材名称,规格,单位,数量,单价,金额,备注&#10;鲜丰农产,2026-06-01,土豆,中号,kg,50,3.2,160,新鲜"
      />
    </template>

    <!-- Step 2: 映射结果复核 -->
    <template v-else>
      <el-alert
        v-if="mapping.overallNeedsReview"
        type="warning"
        show-icon
        :closable="false"
        style="margin-bottom: 12px"
        title="部分字段置信度较低或缺失，已标记「待确认」。请人工核对/补全后再保存草稿。"
      />
      <el-alert
        v-else
        type="success"
        show-icon
        :closable="false"
        style="margin-bottom: 12px"
        title="字段识别完成，请核对后保存为草稿（仍需后续人工确认入库）。"
      />

      <!-- 头部字段 -->
      <el-form label-width="90px" class="head-form">
        <el-row :gutter="16">
          <el-col :span="10">
            <el-form-item label="供应商">
              <el-select
                v-model="form.supplierId"
                filterable
                clearable
                placeholder="选择供应商绑定"
                style="width: 100%"
                @change="onSupplierChange"
              >
                <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
              </el-select>
              <div class="detect-hint">
                <ConfTag :result="mapping.supplierName" />
                <span v-if="mapping.supplierName.value">识别: {{ mapping.supplierName.value }}</span>
                <span v-else class="pending-text">未识别，待确认</span>
              </div>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="送货日期">
              <el-date-picker
                v-model="form.deliveryDate"
                type="date"
                value-format="YYYY-MM-DD"
                placeholder="选择日期"
                style="width: 100%"
              />
              <div class="detect-hint">
                <ConfTag :result="mapping.deliveryDate" />
                <span v-if="mapping.deliveryDate.value">识别: {{ mapping.deliveryDate.value }}</span>
                <span v-else class="pending-text">未识别，待确认</span>
              </div>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="送货单号">
              <el-input v-model="form.noteNumber" placeholder="可选" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <!-- 非持久化检测提示 (规格/备注) -->
      <el-alert
        v-if="mapping.detectedNonPersisted.length > 0"
        type="info"
        :closable="false"
        style="margin-bottom: 12px"
      >
        <template #title>
          已识别「{{ nonPersistedLabels }}」列，但当前草稿对象暂不保存该字段（仅供核对）。
        </template>
      </el-alert>

      <!-- 行项复核表 -->
      <el-table :data="form.lines" border size="small" max-height="360" style="width: 100%">
        <el-table-column label="#" type="index" width="44" />
        <el-table-column label="食材名称" min-width="150">
          <template #default="{ row }">
            <el-input v-model="row.ingredientName" placeholder="待确认" size="small" />
          </template>
        </el-table-column>
        <el-table-column v-if="hasSpec" label="规格" min-width="90">
          <template #default="{ row }">
            <span class="readonly-cell">{{ row.spec || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="110">
          <template #default="{ row }">
            <el-input-number
              v-model="row.quantity"
              :precision="4"
              :step="1"
              :min="0"
              size="small"
              controls-position="right"
              style="width: 92px"
              @change="recalc(row)"
            />
          </template>
        </el-table-column>
        <el-table-column label="单位" width="76">
          <template #default="{ row }">
            <el-input v-model="row.unit" size="small" style="width: 60px" placeholder="—" />
          </template>
        </el-table-column>
        <el-table-column v-if="canViewPrice" label="单价" width="110">
          <template #default="{ row }">
            <el-input-number
              v-model="row.unitPrice"
              :precision="4"
              :step="1"
              :min="0"
              size="small"
              controls-position="right"
              style="width: 92px"
              @change="recalc(row)"
            />
          </template>
        </el-table-column>
        <el-table-column v-if="canViewPrice" label="金额" width="100" align="right">
          <template #default="{ row }">{{ row.lineAmount != null ? '¥' + row.lineAmount : '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="92">
          <template #default="{ row }">
            <el-tag v-if="row.needsReview" size="small" type="warning">待确认</el-tag>
            <el-tag v-else size="small" type="success">已识别</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="60">
          <template #default="{ $index }">
            <el-button link type="danger" size="small" @click="removeLine($index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="!canViewPrice" class="rbac-note">
        当前角色无金额查看权限，单价/金额列已隐藏，且不会写入草稿。
      </div>
    </template>

    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button v-if="mapping" @click="resetMapping">重新选择</el-button>
      <el-button v-if="!mapping" type="primary" :disabled="!canParse" @click="runParse">
        解析并预览
      </el-button>
      <el-button
        v-else
        type="primary"
        :loading="saving"
        :disabled="form.lines.length === 0"
        @click="saveDraft"
      >
        保存为草稿
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, h, type FunctionalComponent } from 'vue';
import { UploadFilled } from '@element-plus/icons-vue';
import { ElMessage, ElTag, type UploadFile } from 'element-plus';
import { get } from '@/api/request';
import { usePermissionStore } from '@/store/modules/permission';
import {
  createManualNote,
  type SupplierDeliveryNoteDto,
  type SupplierDeliveryNoteLineDto,
} from '@/api/restaurant/supplierDeliveryNote';
import { handleCatchError } from '@/utils/errorToast';
import {
  detectImportFileKind,
  mapParsedTableToDraft,
  parseExcelArrayBuffer,
  parseCsv,
  CONFIDENCE_THRESHOLD,
  type ParsedTable,
  type ImportMappingResult,
  type HeadFieldResult,
  type MappedDraftLine,
} from './supplierDeliveryImport';

const props = defineProps<{ modelValue: boolean; factoryId: string }>();
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void;
  (e: 'created', note: SupplierDeliveryNoteDto): void;
}>();

const permissionStore = usePermissionStore();
const canViewPrice = computed(() => permissionStore.canViewPrice);

const pasteText = ref('');
const selectedFile = ref<File | null>(null);
const mapping = ref<ImportMappingResult | null>(null);
const saving = ref(false);
const suppliers = ref<Array<{ id: string; name: string }>>([]);

interface ReviewLine extends SupplierDeliveryNoteLineDto {
  spec: string | null;
  needsReview: boolean;
}

const form = reactive<{
  supplierId: string;
  supplierName: string;
  deliveryDate: string;
  noteNumber: string;
  lines: ReviewLine[];
}>({ supplierId: '', supplierName: '', deliveryDate: '', noteNumber: '', lines: [] });

const canParse = computed(() => !!selectedFile.value || pasteText.value.trim().length > 0);
const hasSpec = computed(() => form.lines.some((l) => l.spec));
const nonPersistedLabels = computed(() =>
  (mapping.value?.detectedNonPersisted || [])
    .map((f) => ({ spec: '规格', remark: '备注' }[f as 'spec' | 'remark']))
    .join(' / '),
);

/** 置信度小标签 (待确认 / 已识别)。 */
const ConfTag: FunctionalComponent<{ result: HeadFieldResult }> = (p) =>
  h(
    ElTag,
    { size: 'small', type: p.result.needsReview ? 'warning' : 'success' },
    () => (p.result.needsReview ? '待确认' : `${Math.round(p.result.confidence * 100)}%`),
  );

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      resetAll();
      loadSuppliers();
    }
  },
);

function resetAll() {
  pasteText.value = '';
  selectedFile.value = null;
  mapping.value = null;
  form.supplierId = '';
  form.supplierName = '';
  form.deliveryDate = '';
  form.noteNumber = '';
  form.lines = [];
}

function resetMapping() {
  mapping.value = null;
  form.lines = [];
}

async function loadSuppliers() {
  try {
    const resp = await get<Array<{ id: string; name: string }>>(
      `/${props.factoryId}/suppliers/active`,
    );
    if (resp.success && Array.isArray(resp.data)) {
      suppliers.value = resp.data;
    } else {
      suppliers.value = [];
      ElMessage.warning('供应商列表暂不可用，可先保留识别出的供应商名称保存草稿，稍后再绑定供应商档案');
    }
  } catch (e) {
    suppliers.value = [];
    handleCatchError(e, '供应商列表加载失败，可先保留识别出的供应商名称保存草稿，稍后再绑定供应商档案');
  }
}

function onFileChange(file: UploadFile) {
  selectedFile.value = (file.raw as File) || null;
}

async function readFileText(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ''));
    reader.onerror = () => reject(reader.error);
    reader.readAsText(file);
  });
}

function splitPasted(text: string): { headers: string[]; rows: Record<string, unknown>[] } {
  // 制表符分隔 (Excel 复制) 优先, 否则交给 CSV 解析器。
  if (text.includes('\t')) {
    const lines = text.split(/\r?\n/).filter((l) => l.trim() !== '');
    const headers = (lines[0] || '').split('\t').map((h) => h.trim());
    const rows = lines.slice(1).map((l) => {
      const cells = l.split('\t');
      const obj: Record<string, unknown> = {};
      headers.forEach((h, i) => (obj[h] = cells[i] ?? ''));
      return obj;
    });
    return { headers, rows };
  }
  const parsed = parseCsv(text);
  return { headers: parsed.headers, rows: parsed.rows as Record<string, unknown>[] };
}

async function readFileArrayBuffer(file: File): Promise<ArrayBuffer> {
  return file.arrayBuffer();
}

async function parseSelectedFile(file: File): Promise<ParsedTable> {
  const kind = detectImportFileKind(file.name, file.type);
  if (kind === 'csv') {
    const text = await readFileText(file);
    return parseCsv(text);
  }
  if (kind === 'excel') {
    const buffer = await readFileArrayBuffer(file);
    return parseExcelArrayBuffer(buffer);
  }
  throw new Error('仅支持 .xlsx / .xls / .csv 文件，请确认供应商进货表格式后重新上传');
}

async function runParse() {
  try {
    let table: ParsedTable;
    if (selectedFile.value) {
      table = await parseSelectedFile(selectedFile.value);
    } else {
      table = splitPasted(pasteText.value);
    }
    if (!table.headers.length || !table.rows.length) {
      ElMessage.warning('未解析到有效表头或数据行');
      return;
    }
    const result = mapParsedTableToDraft(table);
    mapping.value = result;

    // 预填表单 (仍可编辑)。
    form.supplierName = result.supplierName.value || '';
    form.deliveryDate = result.deliveryDate.value || '';
    // 若识别供应商名能在列表匹配到, 自动绑定 supplierId。
    if (result.supplierName.value) {
      const matched = suppliers.value.find((s) => s.name === result.supplierName.value);
      if (matched) form.supplierId = matched.id;
    }
    form.lines = result.lines.map((l: MappedDraftLine) => ({
      ingredientName: l.ingredientName || '',
      quantity: l.quantity,
      unit: l.unit,
      unitPrice: l.unitPrice,
      lineAmount: l.lineAmount,
      spec: l.spec,
      needsReview: l.needsReview,
    }));
  } catch (e) {
    if (e instanceof Error) {
      ElMessage.error(e.message);
    } else {
      handleCatchError(e, '解析失败');
    }
  }
}

function recalc(row: ReviewLine) {
  if (row.quantity != null && row.unitPrice != null) {
    row.lineAmount = Number((Number(row.quantity) * Number(row.unitPrice)).toFixed(2));
  } else {
    row.lineAmount = null;
  }
}

function removeLine(idx: number) {
  form.lines.splice(idx, 1);
}

function onSupplierChange(id: string) {
  const s = suppliers.value.find((x) => x.id === id);
  form.supplierName = s ? s.name : '';
}

async function saveDraft() {
  const validLines = form.lines.filter((l) => l.ingredientName && l.ingredientName.trim());
  if (validLines.length === 0) {
    ElMessage.warning('请至少补全一行食材名称');
    return;
  }
  saving.value = true;
  try {
    const lines: SupplierDeliveryNoteLineDto[] = validLines.map((l) => ({
      ingredientName: String(l.ingredientName).trim(),
      quantity: l.quantity != null ? Number(l.quantity) : null,
      unit: l.unit ? String(l.unit) : null,
      // 金额 RBAC fail-closed: 无金额权限不写入单价/金额。
      unitPrice: canViewPrice.value && l.unitPrice != null ? Number(l.unitPrice) : null,
      lineAmount: canViewPrice.value && l.lineAmount != null ? Number(l.lineAmount) : null,
    }));
    const resp = await createManualNote(props.factoryId, {
      supplierId: form.supplierId || undefined,
      supplierName: form.supplierName || undefined,
      deliveryDate: form.deliveryDate || undefined,
      noteNumber: form.noteNumber || undefined,
      lines,
    });
    if (resp.success && resp.data) {
      ElMessage.success('已生成草稿，请进入详情人工确认');
      emit('created', resp.data);
      emit('update:modelValue', false);
    }
  } catch (e) {
    handleCatchError(e, '保存草稿失败');
  } finally {
    saving.value = false;
  }
}

// 暴露阈值给模板调试 (避免未使用告警)。
void CONFIDENCE_THRESHOLD;
</script>

<style scoped lang="scss">
.head-form {
  margin-bottom: 4px;
}
.detect-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.pending-text {
  color: var(--el-color-warning);
}
.readonly-cell {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.rbac-note {
  margin-top: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
