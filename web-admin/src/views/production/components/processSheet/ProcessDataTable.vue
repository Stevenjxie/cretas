<script setup lang="ts">
import { ref, computed } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Delete, Check, Warning } from '@element-plus/icons-vue';
import {
  saveRow, deleteRow,
  type ProcessSheetInventoryItem,
  type LaborSegment,
  type UpstreamRef,
  type ProcessSheetRowView,
  type ProcessSheetRowRequest,
} from '@/api/processSheet';
import { PROCESS_SHEET_CONFIG, genClientRowId } from './PROCESS_SHEET_CONFIG';
import WorkHoursTable from './WorkHoursTable.vue';

// -------------------------------------------------------------------------
// Props & emits
// -------------------------------------------------------------------------
const props = defineProps<{
  factoryId: string;
  planId: string;
  processCode: string;
  processOrder: number;
  productTypeId: string;
  /** WIP inventory items from the upstream process (for dropdown + remaining calc) */
  upstreamItems: ProcessSheetInventoryItem[];
  /** Existing saved rows loaded from backend on mount */
  initialRows: ProcessSheetRowView[];
}>();
const emit = defineEmits<{
  (e: 'row-saved'): void;
}>();

// -------------------------------------------------------------------------
// Internal row type
// -------------------------------------------------------------------------
interface SheetRow {
  clientRowId: string;
  // display-only
  batchNumber: string | null;
  rowStatus: 'SAVED' | 'DRAFT' | 'UNSAVED';
  saving: boolean;
  deleting: boolean;
  // per-process fields (stored flat, mapped to request on save)
  fields: Record<string, string | number | null>;
  // xiuyou: raw material batch id
  rawBatchId: string;
  rawBatchQty: number | null;
  // chaoshui / shuzhi: upstream sources
  upstreamBatch: string;          // chaoshui: single; shuzhi: ignored
  upstreamSources: UpstreamRef[]; // shuzhi: multi
  // labor
  laborSegments: LaborSegment[];
  // pot
  potCount: number;
  potRawKgs: (number | null)[];
  // expand labor sub-table
  laborExpanded: boolean;
}

// -------------------------------------------------------------------------
// Column config
// -------------------------------------------------------------------------
const cols = computed(() => PROCESS_SHEET_CONFIG[props.processCode] || []);
const isShuZhi = computed(() => props.processCode === 'shuzhi');
const isXiuYou = computed(() => props.processCode === 'xiuyou');

// -------------------------------------------------------------------------
// Helpers
// -------------------------------------------------------------------------
function blankRow(): SheetRow {
  return {
    clientRowId: genClientRowId(props.processCode),
    batchNumber: null,
    rowStatus: 'UNSAVED',
    saving: false,
    deleting: false,
    fields: {},
    rawBatchId: '',
    rawBatchQty: null,
    upstreamBatch: '',
    upstreamSources: [],
    laborSegments: [],
    potCount: 1,
    potRawKgs: [],
    laborExpanded: false,
  };
}

function hydrateRow(view: ProcessSheetRowView): SheetRow {
  const p = view.payload;
  const row = blankRow();
  row.clientRowId = view.clientRowId;
  row.batchNumber = view.batchNumber;
  row.rowStatus = view.rowStatus;

  // field hydration from payload
  if (isXiuYou.value) {
    row.rawBatchId = p.rawMaterialInputs?.[0]?.materialBatchId ?? '';
    row.rawBatchQty = p.rawMaterialInputs?.[0]?.quantity ?? null;
    row.fields['output'] = p.outputQuantity;
    row.fields['prodDate'] = p.unit ?? '';  // reuse unit for date? no — use rawInput qty
    // prodDate comes from processDate field in payload, but we stored in fields below
  }
  if (props.processCode === 'chaoshui') {
    row.upstreamBatch = p.upstreamSources?.[0]?.sourceBatchNumber ?? '';
    row.fields['before'] = p.inputQuantity ?? null;
    row.fields['after'] = p.outputQuantity;
  }
  if (isShuZhi.value) {
    row.upstreamSources = p.upstreamSources ?? [];
    row.fields['input'] = p.inputQuantity ?? null;
    row.fields['output'] = p.outputQuantity;
  }
  row.laborSegments = p.laborSegments ?? [];
  row.potCount = p.potCount ?? 1;
  row.potRawKgs = p.potRawKgs?.map((v) => v) ?? [];

  // generic fields stored in fields map
  for (const col of cols.value) {
    if (['rawBatch', 'outWeight', 'upstreamBatch', 'batch', 'yieldRate', 'remain', 'totalHours', 'feedWeight'].includes(col.key)) continue;
    if (col.type === 'number' || col.type === 'date') {
      if (!(col.key in row.fields)) {
        // fallback: try to find from standard payload
      }
    }
  }
  return row;
}

// -------------------------------------------------------------------------
// Rows state
// -------------------------------------------------------------------------
const rows = ref<SheetRow[]>(props.initialRows.map(hydrateRow));

function addRow() {
  rows.value.push(blankRow());
}

// -------------------------------------------------------------------------
// Auto-calc helpers
// -------------------------------------------------------------------------
function calcYield(row: SheetRow): number | null {
  let input: number | null = null;
  let output: number | null = null;

  if (isXiuYou.value) {
    input = row.rawBatchQty;
    output = (row.fields['output'] as number) ?? null;
  } else if (props.processCode === 'chaoshui') {
    input = (row.fields['before'] as number) ?? null;
    output = (row.fields['after'] as number) ?? null;
  } else if (isShuZhi.value) {
    input = (row.fields['input'] as number) ?? null;
    output = (row.fields['output'] as number) ?? null;
  }
  if (input == null || input === 0 || output == null) return null;
  return Math.round((output / input) * 10000) / 100;
}

function calcTotalHours(row: SheetRow): number {
  return row.laborSegments.reduce((sum, seg) => {
    if (!seg.startTime || !seg.endTime) return sum;
    const [sh, sm] = seg.startTime.split(':').map(Number);
    const [eh, em] = seg.endTime.split(':').map(Number);
    const mins = (eh * 60 + em) - (sh * 60 + sm);
    return sum + Math.max(0, mins / 60) * (seg.workerCount || 0);
  }, 0);
}

function calcRemaining(row: SheetRow): number | null {
  const batchNum = isShuZhi.value
    ? null  // shuzhi: no single upstream batch
    : (props.processCode === 'chaoshui' ? row.upstreamBatch : null);
  if (!batchNum) return null;
  const inv = props.upstreamItems.find((b) => b.batchNumber === batchNum);
  return inv ? inv.remaining : null;
}

// Soft over-limit warning for upstream batch
function upstreamWarning(row: SheetRow): string | null {
  if (props.processCode === 'chaoshui') {
    const inv = props.upstreamItems.find((b) => b.batchNumber === row.upstreamBatch);
    if (!inv) return null;
    const usage = (row.fields['before'] as number) ?? 0;
    if (usage > inv.remaining) {
      return `用量 ${usage}kg 超出剩余 ${inv.remaining}kg`;
    }
  }
  if (isShuZhi.value) {
    const warnings: string[] = [];
    for (const src of row.upstreamSources) {
      const inv = props.upstreamItems.find((b) => b.batchNumber === src.sourceBatchNumber);
      if (inv && src.feedQuantityKg > inv.remaining) {
        warnings.push(`${src.sourceBatchNumber} 用量 ${src.feedQuantityKg}kg 超出剩余 ${inv.remaining}kg`);
      }
    }
    return warnings.length ? warnings.join('; ') : null;
  }
  return null;
}

// -------------------------------------------------------------------------
// Save disabled reason
// -------------------------------------------------------------------------
function saveDisabledReason(row: SheetRow): string | null {
  if (isXiuYou.value) {
    if (!row.rawBatchId) return '请选择原料批次';
    if (row.rawBatchQty == null) return '请填写出库重量';
    if ((row.fields['output'] as number) == null) return '请填写产出数量';
  } else if (props.processCode === 'chaoshui') {
    if (!row.upstreamBatch) return '请选择修油批次';
    if ((row.fields['before'] as number) == null) return '请填写焯水前重量';
    if ((row.fields['after'] as number) == null) return '请填写焯水后重量';
  } else if (isShuZhi.value) {
    if (row.upstreamSources.length === 0) return '请添加焯水来源批';
    const incomplete = row.upstreamSources.some((s) => !s.sourceBatchNumber || !s.feedQuantityKg);
    if (incomplete) return '请补全所有来源批次及投料量';
    if ((row.fields['output'] as number) == null) return '请填写产出数量';
    if (row.potCount > 1) {
      const filled = row.potRawKgs.filter((v) => v != null && v > 0);
      if (filled.length < row.potCount) return `请填写所有 ${row.potCount} 锅的原料重量`;
    }
  }
  return null;
}

// -------------------------------------------------------------------------
// Build request
// -------------------------------------------------------------------------
function buildRequest(row: SheetRow): ProcessSheetRowRequest {
  const base: ProcessSheetRowRequest = {
    clientRowId: row.clientRowId,
    processCode: props.processCode,
    processOrder: props.processOrder,
    productTypeId: props.productTypeId,
    batchNumber: row.batchNumber ?? undefined,
    finished: false,
    outputQuantity: 0,
    seasoningStep: props.processCode === 'shuzhi',
    laborSegments: row.laborSegments.length ? row.laborSegments : undefined,
    potCount: row.potCount > 1 ? row.potCount : undefined,
    potRawKgs: row.potCount > 1 ? (row.potRawKgs.filter(Boolean) as number[]) : undefined,
  };

  if (isXiuYou.value) {
    base.rawMaterialInputs = [{ materialBatchId: row.rawBatchId, quantity: row.rawBatchQty! }];
    base.inputQuantity = row.rawBatchQty ?? undefined;
    base.outputQuantity = (row.fields['output'] as number) ?? 0;
    base.unit = 'kg';
  } else if (props.processCode === 'chaoshui') {
    base.upstreamSources = [{ sourceBatchNumber: row.upstreamBatch, feedQuantityKg: (row.fields['before'] as number) ?? 0 }];
    base.inputQuantity = (row.fields['before'] as number) ?? undefined;
    base.outputQuantity = (row.fields['after'] as number) ?? 0;
    base.unit = 'kg';
  } else if (isShuZhi.value) {
    base.upstreamSources = row.upstreamSources;
    const totalFeed = row.upstreamSources.reduce((s, r) => s + (r.feedQuantityKg || 0), 0);
    base.inputQuantity = (row.fields['input'] as number) ?? totalFeed;
    base.outputQuantity = (row.fields['output'] as number) ?? 0;
    base.unit = 'kg';
  }
  return base;
}

// -------------------------------------------------------------------------
// Save / delete handlers
// -------------------------------------------------------------------------
async function handleSave(row: SheetRow) {
  const reason = saveDisabledReason(row);
  if (reason) {
    ElMessage({ message: reason, type: 'warning', duration: 0, showClose: true });
    return;
  }
  row.saving = true;
  try {
    const req = buildRequest(row);
    const resp = await saveRow(props.factoryId, props.planId, req);
    const result = resp.data;
    if (result?.batchNumber) row.batchNumber = result.batchNumber;
    row.rowStatus = result?.materialized ? 'SAVED' : 'DRAFT';
    if (result?.warnings?.length) {
      ElMessage({ message: '已保存(含提示): ' + result.warnings.join('; '), type: 'warning', duration: 0, showClose: true });
    } else {
      ElMessage.success(`已保存${row.batchNumber ? ' — ' + row.batchNumber : ''}`);
    }
    emit('row-saved');
  } catch (e: any) {
    ElMessage({ message: e.message || '保存失败', type: 'error', duration: 0, showClose: true });
  } finally {
    row.saving = false;
  }
}

async function handleDelete(row: SheetRow) {
  if (row.rowStatus === 'UNSAVED') {
    rows.value = rows.value.filter((r) => r !== row);
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认删除${row.batchNumber ? ' 批次 ' + row.batchNumber : ''}这行记录？下游如已引用将返回错误。`,
      '删除确认', { type: 'warning' }
    );
  } catch {
    return;
  }
  row.deleting = true;
  try {
    await deleteRow(props.factoryId, props.planId, row.clientRowId);
    rows.value = rows.value.filter((r) => r !== row);
    emit('row-saved');
  } catch (e: any) {
    ElMessage({ message: e.message || '删除失败', type: 'error', duration: 0, showClose: true });
  } finally {
    row.deleting = false;
  }
}

// -------------------------------------------------------------------------
// Shuzhi multi-source helpers
// -------------------------------------------------------------------------
function addUpstreamSource(row: SheetRow) {
  row.upstreamSources = [...row.upstreamSources, { sourceBatchNumber: '', feedQuantityKg: 0 }];
}
function removeUpstreamSource(row: SheetRow, idx: number) {
  const next = [...row.upstreamSources];
  next.splice(idx, 1);
  row.upstreamSources = next;
}

// -------------------------------------------------------------------------
// Pot helpers
// -------------------------------------------------------------------------
function onPotCountChange(row: SheetRow, val: number) {
  row.potCount = val;
  row.potRawKgs = Array.from({ length: val }, (_, i) => row.potRawKgs[i] ?? null);
}
</script>

<template>
  <div>
    <!-- Per-row cards (table-of-cards style for complex sub-tables) -->
    <div v-for="(row, ri) in rows" :key="row.clientRowId"
         style="border:1px solid #dcdfe6;border-radius:6px;padding:12px;margin-bottom:12px;background:#fff">

      <!-- Row header -->
      <div style="display:flex;align-items:center;gap:8px;margin-bottom:10px">
        <el-tag :type="row.rowStatus === 'SAVED' ? 'success' : row.rowStatus === 'DRAFT' ? 'warning' : 'info'" size="small">
          {{ row.rowStatus === 'SAVED' ? '已物化' : row.rowStatus === 'DRAFT' ? '草稿' : '未保存' }}
        </el-tag>
        <span v-if="row.batchNumber" style="font-size:12px;color:#606266;font-weight:600">
          {{ row.batchNumber }}
        </span>
        <span style="flex:1" />
        <span style="font-size:11px;color:#909399">行{{ ri + 1 }}</span>
      </div>

      <!-- Soft over-limit warning -->
      <el-alert v-if="upstreamWarning(row)" type="warning" :closable="false" show-icon
        :title="upstreamWarning(row)!" style="margin-bottom:8px;padding:4px 8px" />

      <!-- Fields grid -->
      <el-form label-position="top" size="small" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:4px 12px">

        <!-- xiuyou: raw batch dropdown -->
        <el-form-item v-if="isXiuYou" label="原料批次">
          <el-input v-model="row.rawBatchId" placeholder="原料批次 ID" style="width:100%" clearable />
        </el-form-item>

        <!-- xiuyou: out weight -->
        <el-form-item v-if="isXiuYou" label="出库重量(kg)">
          <el-input-number v-model="row.rawBatchQty" :min="0" :precision="2" style="width:100%" />
        </el-form-item>

        <!-- chaoshui: single upstream dropdown -->
        <el-form-item v-if="processCode === 'chaoshui'" label="修油批次">
          <el-select v-model="row.upstreamBatch" placeholder="选修油批次" style="width:100%" clearable>
            <el-option v-for="item in upstreamItems" :key="item.batchNumber"
              :label="`${item.batchNumber} (余${item.remaining}kg)`"
              :value="item.batchNumber"
              :disabled="item.remaining <= 0" />
          </el-select>
        </el-form-item>

        <!-- chaoshui: before / after -->
        <el-form-item v-if="processCode === 'chaoshui'" label="焯水前(kg)">
          <el-input-number
            :model-value="(row.fields['before'] as number)"
            @update:model-value="(v: number) => row.fields['before'] = v"
            :min="0" :precision="2" style="width:100%" />
        </el-form-item>
        <el-form-item v-if="processCode === 'chaoshui'" label="焯水后(kg)">
          <el-input-number
            :model-value="(row.fields['after'] as number)"
            @update:model-value="(v: number) => row.fields['after'] = v"
            :min="0" :precision="2" style="width:100%" />
        </el-form-item>

        <!-- shuzhi: input / output -->
        <el-form-item v-if="isShuZhi" label="投入总量(kg)">
          <el-input-number
            :model-value="(row.fields['input'] as number)"
            @update:model-value="(v: number) => row.fields['input'] = v"
            :min="0" :precision="2" style="width:100%" />
        </el-form-item>

        <!-- xiuyou / shuzhi: output -->
        <el-form-item v-if="isXiuYou || isShuZhi" label="产出(kg)">
          <el-input-number
            :model-value="(row.fields['output'] as number)"
            @update:model-value="(v: number) => row.fields['output'] = v"
            :min="0" :precision="2" style="width:100%" />
        </el-form-item>

        <!-- batch (readonly) -->
        <el-form-item label="系统批次号">
          <el-input :model-value="row.batchNumber || '(保存后生成)'" readonly style="width:100%" />
        </el-form-item>

        <!-- date -->
        <el-form-item v-for="col in cols.filter(c => c.type === 'date')" :key="col.key" :label="col.label">
          <el-date-picker
            :model-value="(row.fields[col.key] as string) || ''"
            @update:model-value="(v: string) => { row.fields[col.key] = v }"
            type="date" value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>

        <!-- auto: yield -->
        <el-form-item label="出成率(%)">
          <el-input :model-value="calcYield(row) != null ? String(calcYield(row)) : '—'" readonly style="width:100%" />
        </el-form-item>

        <!-- auto: remaining (chaoshui only) -->
        <el-form-item v-if="processCode === 'chaoshui'" label="剩余量(kg)">
          <el-input :model-value="calcRemaining(row) != null ? String(calcRemaining(row)) : '—'" readonly style="width:100%" />
        </el-form-item>

        <!-- auto: total hours -->
        <el-form-item label="总工时(h)">
          <el-input :model-value="calcTotalHours(row).toFixed(2)" readonly style="width:100%" />
        </el-form-item>

      </el-form>

      <!-- shuzhi: 混锅 multi-source -->
      <div v-if="isShuZhi" style="margin-top:8px">
        <div style="font-size:12px;font-weight:600;color:#303133;margin-bottom:4px">
          焯水来源批(混锅)
          <el-button size="small" :icon="Plus" style="margin-left:8px" @click="addUpstreamSource(row)">+ 来源批</el-button>
        </div>
        <div v-for="(src, si) in row.upstreamSources" :key="si"
             style="display:flex;align-items:center;gap:8px;margin-bottom:4px">
          <el-select v-model="src.sourceBatchNumber" placeholder="选焯水批次" style="width:220px" clearable>
            <el-option v-for="item in upstreamItems" :key="item.batchNumber"
              :label="`${item.batchNumber} (余${item.remaining}kg)`"
              :value="item.batchNumber"
              :disabled="item.remaining <= 0" />
          </el-select>
          <el-input-number v-model="src.feedQuantityKg" :min="0" :precision="2" placeholder="投料kg" style="width:130px" />
          <el-button link type="danger" :icon="Delete" @click="removeUpstreamSource(row, si)" />
          <span v-if="src.feedQuantityKg && src.sourceBatchNumber" style="font-size:11px;color:#909399">
            {{ (() => { const inv = upstreamItems.find(b => b.batchNumber === src.sourceBatchNumber); return inv ? `余${inv.remaining}kg` : ''; })() }}
          </span>
        </div>
      </div>

      <!-- shuzhi: 多锅 pot breakdown -->
      <div v-if="isShuZhi" style="margin-top:8px">
        <div style="font-size:12px;font-weight:600;color:#303133;margin-bottom:4px">
          锅数
          <el-input-number :model-value="row.potCount" @update:model-value="(v: number) => onPotCountChange(row, v)"
            :min="1" :precision="0" size="small" style="width:80px;margin-left:8px" />
        </div>
        <div v-if="row.potCount > 1" style="display:flex;flex-wrap:wrap;gap:6px;margin-top:4px">
          <el-form-item v-for="pi in row.potCount" :key="pi" :label="`第${pi}锅(kg)`" style="width:120px">
            <el-input-number
              v-model="row.potRawKgs[pi - 1]"
              :min="0" :precision="2" size="small" style="width:115px" />
          </el-form-item>
        </div>
      </div>

      <!-- Labor sub-table (collapsible) -->
      <div style="margin-top:8px">
        <el-button size="small" link @click="row.laborExpanded = !row.laborExpanded">
          {{ row.laborExpanded ? '▲' : '▼' }} 工时录入 ({{ row.laborSegments.length }} 段 · {{ calcTotalHours(row).toFixed(2) }}h)
        </el-button>
        <div v-if="row.laborExpanded" style="margin-top:6px">
          <WorkHoursTable v-model="row.laborSegments" />
        </div>
      </div>

      <!-- Row actions -->
      <div style="display:flex;align-items:center;gap:8px;margin-top:10px;border-top:1px solid #f0f0f0;padding-top:10px">
        <el-button
          type="primary" size="small" :icon="Check"
          :loading="row.saving"
          :disabled="!!saveDisabledReason(row)"
          :title="saveDisabledReason(row) || '保存此行'"
          @click="handleSave(row)"
        >保存</el-button>
        <el-tooltip v-if="saveDisabledReason(row)" :content="saveDisabledReason(row)!" placement="top">
          <el-icon style="color:#e6a23c"><Warning /></el-icon>
        </el-tooltip>
        <el-button type="danger" size="small" :icon="Delete" :loading="row.deleting"
          @click="handleDelete(row)">删除</el-button>
      </div>
    </div>

    <!-- Add row button -->
    <el-button :icon="Plus" @click="addRow" style="width:100%;margin-top:4px" dashed>
      + 新增行
    </el-button>
  </div>
</template>
