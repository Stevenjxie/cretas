<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { post } from '@/api/request';
import { bomSeasoningApi, type SeasoningProcessView } from '@/api/bom';
import { convertUnit } from '@/api/unitContract';
import { canonicalUnitCode, displayUnit } from '@/utils/unitPricing';
import type { SeasoningMaterialOption } from './SeasoningBindingDialog.vue';

interface OcrResponse {
  success: boolean;
  extractedText?: string;
  fieldValues?: Record<string, unknown>;
  confidence?: number;
  message?: string;
}

interface ImportRow {
  key: string;
  detectedName: string;
  materialTypeId: string;
  workflowProcessNodeId: string;
  quantity: number | null;
  error: string;
}

const props = defineProps<{
  modelValue: boolean;
  factoryId: string;
  recipeId: string;
  revision: number;
  processes: SeasoningProcessView[];
  materials: SeasoningMaterialOption[];
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  applied: [];
}>();

const recognizing = ref(false);
const applying = ref(false);
const rows = ref<ImportRow[]>([]);
const sourceFileName = ref('');
const defaultProcessNodeId = ref('');
const ocrSummary = ref('');

const readyRows = computed(() => rows.value.filter((row) => (
  row.materialTypeId && row.workflowProcessNodeId && Number(row.quantity) > 0 && !row.error
)));

watch(() => props.modelValue, (visible) => {
  if (!visible) return;
  rows.value = [];
  sourceFileName.value = '';
  defaultProcessNodeId.value = '';
  ocrSummary.value = '';
});

function normalizeName(value: unknown): string {
  return String(value || '')
    .replace(/[（）()【】\[\]\s]/g, '')
    .replace(/配料表|配料|辅料|食品添加剂/g, '')
    .trim()
    .toLowerCase();
}

function valueCandidates(fieldValues: Record<string, unknown> | undefined): string[] {
  const value = fieldValues?.auxiliaryMaterials;
  if (Array.isArray(value)) {
    return value.map((item) => (
      typeof item === 'object' && item !== null
        ? String((item as Record<string, unknown>).name || (item as Record<string, unknown>).materialName || '')
        : String(item)
    )).filter(Boolean);
  }
  if (typeof value === 'string') {
    return value.split(/[、,，;；\n]/).map((item) => item.trim()).filter(Boolean);
  }
  return [];
}

function matchMaterial(name: string): SeasoningMaterialOption | undefined {
  const normalized = normalizeName(name);
  if (!normalized) return undefined;
  return props.materials.find((material) => normalizeName(material.name) === normalized)
    || props.materials.find((material) => (
      normalizeName(material.name).includes(normalized)
      || normalized.includes(normalizeName(material.name))
    ));
}

function buildRows(result: OcrResponse): ImportRow[] {
  const extractedText = result.extractedText || '';
  const detected = valueCandidates(result.fieldValues);
  const directlyMentioned = props.materials
    .filter((material) => extractedText.includes(material.name))
    .map((material) => material.name);
  const unique = [...new Set([...detected, ...directlyMentioned])];
  return unique.map((name, index) => {
    const material = matchMaterial(name);
    return {
      key: `${index}-${name}`,
      detectedName: name,
      materialTypeId: material?.id || '',
      workflowProcessNodeId: defaultProcessNodeId.value,
      quantity: null as number | null,
      error: material ? '' : '未匹配物料档案',
    };
  });
}

function readAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || '').split(',')[1] || '');
    reader.onerror = () => reject(reader.error || new Error('文件读取失败'));
    reader.readAsDataURL(file);
  });
}

async function onFileSelected(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('当前支持 JPG、PNG、WebP 等图片文件');
    return;
  }
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning('图片不能超过 10MB');
    return;
  }
  sourceFileName.value = file.name;
  recognizing.value = true;
  rows.value = [];
  try {
    const imageBase64 = await readAsBase64(file);
    const response = await post<OcrResponse>(`/${props.factoryId}/form-assistant/ocr`, {
      imageBase64,
      entityType: 'BOM_AUXILIARY_BULK_IMPORT',
      formFields: [{
        name: 'auxiliaryMaterials',
        title: '辅料清单',
        type: 'array',
        description: '提取配料表中的全部辅料、调味料和食品添加剂名称，每项只返回物料名称',
        required: true,
      }],
    });
    const result = response.data;
    if (!response.success || !result?.success) {
      throw new Error(result?.message || response.message || 'AI 识别失败');
    }
    rows.value = buildRows(result);
    ocrSummary.value = `识别到 ${rows.value.length} 项，其中 ${rows.value.filter((row) => row.materialTypeId).length} 项已匹配物料档案。`;
    if (!rows.value.length) ElMessage.warning('未识别到可匹配的辅料，请换一张更清晰的配料表图片');
  } catch (error: unknown) {
    ElMessage.error((error as { message?: string }).message || 'AI 识别失败');
  } finally {
    recognizing.value = false;
  }
}

function applyDefaultProcess(value: string) {
  rows.value = rows.value.map((row) => ({
    ...row,
    workflowProcessNodeId: row.workflowProcessNodeId || value,
  }));
}

function materialUnit(materialTypeId: string): string {
  return displayUnit(props.materials.find((material) => material.id === materialTypeId)?.unit);
}

async function dosageInLegacyGrams(row: ImportRow): Promise<number> {
  const material = props.materials.find((item) => item.id === row.materialTypeId);
  const unit = canonicalUnitCode(material?.unit);
  const quantity = Number(row.quantity);
  if (unit === 'g') return quantity;
  if (unit === 'kg') return quantity * 1000;
  const response = await convertUnit(props.factoryId, {
    quantity,
    fromUnit: unit,
    toUnit: 'g',
    scene: 'PRODUCTION',
    scale: 6,
    roundingMode: 'HALF_UP',
  });
  const grams = response.success ? Number(response.data?.quantity) : Number.NaN;
  if (!Number.isFinite(grams) || grams <= 0) {
    throw new Error(`“${material?.name || row.detectedName}”的档案单位缺少权威成本换算关系`);
  }
  return grams;
}

async function applyRows() {
  if (!readyRows.value.length) {
    ElMessage.warning('请至少完成一行的物料、工序和用量');
    return;
  }
  applying.value = true;
  let revision = props.revision;
  let applied = 0;
  try {
    const prepared: Array<{
      row: ImportRow;
      process: SeasoningProcessView;
      dosagePerKgG: number;
    }> = [];
    const seen = new Set<string>();
    for (const row of readyRows.value) {
      const process = props.processes.find((item) => item.workflowProcessNodeId === row.workflowProcessNodeId);
      if (!process || process.standardUsageSupported !== true) {
        throw new Error(`工序“${process?.processName || '未知'}”缺少可用产出基准`);
      }
      const material = props.materials.find((item) => item.id === row.materialTypeId);
      if (!material || (!Number(material.movingAvgPrice) && !Number(material.unitPrice))) {
        throw new Error(`“${material?.name || row.detectedName}”缺少有效成本价格`);
      }
      const identity = `${process.workflowProcessNodeId}:${row.materialTypeId}`;
      if (seen.has(identity) || process.bindings.some((binding) => binding.materialTypeId === row.materialTypeId)) {
        throw new Error(`“${material.name}”已在工序“${process.processName}”配置`);
      }
      seen.add(identity);
      const dosagePerKgG = await dosageInLegacyGrams(row);
      prepared.push({ row, process, dosagePerKgG });
    }

    for (const { row, process, dosagePerKgG } of prepared) {
      const response = await bomSeasoningApi.createBinding(
        props.factoryId,
        props.recipeId,
        process.workProcessId,
        {
          workflowProcessNodeId: process.workflowProcessNodeId,
          materialTypeId: row.materialTypeId,
          dosagePerKgG,
          subsequentPotRatio: null,
          countInSeasoning: true,
          remark: `AI 文件识别导入：${sourceFileName.value}`,
          substitutes: [],
          expectedRevision: revision,
        },
      );
      if (!response.success || !response.data) throw new Error(response.message || '批量保存失败');
      revision = response.data.seasoningRevision;
      applied += 1;
    }
    ElMessage.success(`已写入 ${applied} 条工序辅料配置`);
    emit('update:modelValue', false);
    emit('applied');
  } catch (error: unknown) {
    ElMessage.error(`${(error as { message?: string }).message || '批量保存失败'}；已成功写入 ${applied} 条，请刷新后继续处理剩余项`);
    if (applied) emit('applied');
  } finally {
    applying.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="AI 识别并批量添加辅料"
    width="860px"
    class="auxiliary-ai-import"
    @close="emit('update:modelValue', false)"
  >
    <div class="import-intro">
      <div>
        <strong>上传配料表或标签图片</strong>
        <p>AI 只识别并匹配现有辅料档案，不会创建新物料。保存前请补充工序和每份用量。</p>
      </div>
      <label class="file-button">
        <input
          type="file"
          name="auxiliary-image"
          accept="image/*"
          data-testid="auxiliary-ai-file"
          @change="onFileSelected"
        />
        {{ recognizing ? '正在识别…' : '选择图片' }}
      </label>
    </div>

    <el-alert
      v-if="sourceFileName"
      :title="ocrSummary || `正在处理 ${sourceFileName}`"
      :closable="false"
      :type="rows.some((row) => row.error) ? 'warning' : 'success'"
      show-icon
      role="status"
      aria-live="polite"
      class="import-status"
    />

    <div v-if="rows.length" class="batch-toolbar">
      <span>统一设置工序</span>
      <el-select
        v-model="defaultProcessNodeId"
        placeholder="选择后应用到尚未设置的行"
        style="width: 280px"
        @change="applyDefaultProcess"
      >
        <el-option
          v-for="process in processes"
          :key="process.workflowProcessNodeId"
          :label="`${process.processOrder}. ${process.processName}`"
          :value="process.workflowProcessNodeId"
        />
      </el-select>
      <span class="batch-count">可写入 {{ readyRows.length }} / {{ rows.length }} 项</span>
    </div>

    <el-table v-if="rows.length" :data="rows" border size="small" max-height="430">
      <el-table-column prop="detectedName" label="AI 识别名称" min-width="150" />
      <el-table-column label="匹配辅料档案" min-width="220">
        <template #default="{ row }">
          <el-select v-model="row.materialTypeId" filterable style="width: 100%" @change="row.error = ''">
            <el-option v-for="material in materials" :key="material.id" :label="material.name" :value="material.id" />
          </el-select>
          <span v-if="row.error" class="row-error">{{ row.error }}</span>
        </template>
      </el-table-column>
      <el-table-column label="投入工序" min-width="190">
        <template #default="{ row }">
          <el-select v-model="row.workflowProcessNodeId" style="width: 100%">
            <el-option
              v-for="process in processes"
              :key="process.workflowProcessNodeId"
              :label="`${process.processOrder}. ${process.processName}`"
              :value="process.workflowProcessNodeId"
            />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="每份用量" min-width="160">
        <template #default="{ row }">
          <div class="quantity-cell">
            <el-input-number v-model="row.quantity" :min="0.000001" :precision="4" :controls="false" />
            <span>{{ materialUnit(row.materialTypeId) || '档案单位' }}</span>
          </div>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-else-if="!recognizing" description="选择一张配料表图片开始识别" :image-size="72" />

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="!readyRows.length" :loading="applying" @click="applyRows">
        写入 {{ readyRows.length }} 条辅料
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.import-intro { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 14px 16px; border: 1px solid var(--el-color-primary-light-7); border-radius: 8px; background: var(--el-color-primary-light-9); }
.import-intro p { margin: 5px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
.file-button { display: inline-flex; align-items: center; justify-content: center; min-width: 94px; height: 32px; padding: 0 14px; border-radius: 4px; background: var(--el-color-primary); color: #fff; cursor: pointer; touch-action: manipulation; white-space: nowrap; }
.file-button:hover { background: var(--el-color-primary-light-3); }
.file-button:focus-within { outline: 2px solid var(--el-color-primary-light-3); outline-offset: 2px; }
.file-button input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.import-status { margin: 12px 0; }
.batch-toolbar { display: flex; align-items: center; gap: 10px; margin: 12px 0; }
.batch-count { margin-left: auto; color: var(--el-text-color-secondary); font-size: 12px; }
.quantity-cell { display: flex; align-items: center; gap: 8px; }
.quantity-cell :deep(.el-input-number) { width: 110px; }
.quantity-cell span { color: var(--el-text-color-regular); font-size: 12px; white-space: nowrap; }
.row-error { display: block; margin-top: 4px; color: var(--el-color-danger); font-size: 12px; }
:global(.auxiliary-ai-import .el-dialog__body) { overscroll-behavior: contain; }
@media (max-width: 720px) {
  .import-intro, .batch-toolbar { align-items: stretch; flex-direction: column; }
  .batch-count { margin-left: 0; }
}
</style>
