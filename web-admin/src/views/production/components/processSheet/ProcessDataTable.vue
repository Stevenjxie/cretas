<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Delete, Check, Warning, ArrowDown, ArrowRight } from '@element-plus/icons-vue';
import {
  saveRow, deleteRow, getAvailableRawBatches,
  type ProcessSheetInventoryItem,
  type LaborSegment,
  type UpstreamRef,
  type ProcessSheetRowView,
  type ProcessSheetRowRequest,
  type RawMaterialBatchOption,
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
  /** WIP inventory items for THIS process (for grid 剩余 column on saved rows) */
  ownInventoryItems?: ProcessSheetInventoryItem[];
  /** Existing saved rows loaded from backend on mount */
  initialRows: ProcessSheetRowView[];
  /** Layout mode toggled in the drawer header. Default: 'grid'. */
  viewMode?: 'grid' | 'card';
}>();
const emit = defineEmits<{
  (e: 'row-saved'): void;
}>();

// -------------------------------------------------------------------------
// Internal row type
// -------------------------------------------------------------------------
interface SheetRow {
  clientRowId: string;
  batchNumber: string | null;
  rowStatus: 'SAVED' | 'DRAFT' | 'UNSAVED';
  saving: boolean;
  deleting: boolean;
  /** Generic per-process scalar fields: date / number / daterange ([start,end]) values keyed by ColDef.key */
  fields: Record<string, string | number | [string, string] | null>;
  /** 修油: selected raw material batch id → rawMaterialInputs[0].materialBatchId */
  rawBatchId: string;
  /** 修油: out-weight (kg) → rawMaterialInputs[0].quantity */
  rawBatchQty: number | null;
  /** 焯水: single upstream WIP batch number */
  upstreamBatch: string;
  /** 熟制: multi-source upstream WIP refs */
  upstreamSources: UpstreamRef[];
  /** Multi-segment labor entries */
  laborSegments: LaborSegment[];
  /** 熟制: number of pots */
  potCount: number;
  /** 熟制: per-pot raw kg when potCount > 1 */
  potRawKgs: (number | null)[];
  /** Whether the labor expander is open for this row */
  laborExpanded: boolean;
  /** Whether the 混锅/pot detail expander is open (shuzhi) */
  mixExpanded: boolean;
}

// -------------------------------------------------------------------------
// Column config
// -------------------------------------------------------------------------
const cols = computed(() => PROCESS_SHEET_CONFIG[props.processCode] || []);
const isShuZhi = computed(() => props.processCode === 'shuzhi');
const isXiuYou = computed(() => props.processCode === 'xiuyou');

// -------------------------------------------------------------------------
// Raw material batch options (for 修油 首道)
// -------------------------------------------------------------------------
const rawBatchOptions = ref<RawMaterialBatchOption[]>([]);
const rawBatchLoading = ref(false);

function extractRawBatches(
  data: RawMaterialBatchOption[] | { content: RawMaterialBatchOption[] } | null | undefined,
): RawMaterialBatchOption[] {
  if (!data) return [];
  const all: RawMaterialBatchOption[] = Array.isArray(data)
    ? data
    : (typeof data === 'object' && 'content' in data && Array.isArray(data.content))
      ? data.content
      : [];
  // 修油 首道只能领用真实原料批次，不能领 WIP/半成品批次。
  // 过滤策略（双重防御）:
  //   1. 优先用 sourceDocType === 'PRODUCTION_BATCH'（后端已返回时最可靠）。
  //   2. 兜底用 batchNumber 前缀 — SP-F 文员逐道录入产生的 WIP 批均以
  //      "WIP-" 或 "CLK-" 开头 (CLK-W- / CLK-B- 是后端 ProcessSheetService 的命名方案).
  return all.filter((b) => {
    if (b.sourceDocType != null) return b.sourceDocType !== 'PRODUCTION_BATCH';
    const bn = b.batchNumber ?? '';
    return !bn.startsWith('WIP-') && !bn.startsWith('CLK-');
  });
}

function rawBatchAvailable(batch: RawMaterialBatchOption): number {
  return Number(batch.currentQuantity ?? batch.quantity ?? 0) || 0;
}

function rawBatchLabel(batch: RawMaterialBatchOption): string {
  const name = batch.materialName || batch.materialTypeName || '原料';
  const qty = rawBatchAvailable(batch);
  const unit = batch.quantityUnit || batch.unit || 'kg';
  const price = batch.unitPrice != null ? ` ¥${Number(batch.unitPrice).toFixed(2)}/${unit}` : '';
  return `${name} | ${batch.batchNumber || batch.id} | 余${qty}${unit}${price}`;
}

async function loadRawBatches() {
  if (!isXiuYou.value || !props.factoryId) return;
  rawBatchLoading.value = true;
  try {
    const resp = await getAvailableRawBatches(props.factoryId);
    rawBatchOptions.value = extractRawBatches(resp.data).filter((b) => rawBatchAvailable(b) > 0);
  } catch {
    rawBatchOptions.value = [];
  } finally {
    rawBatchLoading.value = false;
  }
}

// -------------------------------------------------------------------------
// Helpers
// -------------------------------------------------------------------------

/** Returns today as YYYY-MM-DD (local time). */
function todayStr(): string {
  const d = new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function blankRow(): SheetRow {
  // Default daterange fields to [today, today] for each daterange col in this process.
  const today = todayStr();
  const daterangeDefaults: Record<string, [string, string]> = {};
  for (const col of PROCESS_SHEET_CONFIG[props.processCode] ?? []) {
    if (col.type === 'daterange') {
      daterangeDefaults[col.key] = [today, today];
    }
  }
  return {
    clientRowId: genClientRowId(props.processCode),
    batchNumber: null,
    rowStatus: 'UNSAVED',
    saving: false,
    deleting: false,
    fields: { ...daterangeDefaults },
    rawBatchId: '',
    rawBatchQty: null,
    upstreamBatch: '',
    upstreamSources: [],
    laborSegments: [],
    potCount: 1,
    potRawKgs: [],
    laborExpanded: false,
    mixExpanded: false,
  };
}

function hydrateRow(view: ProcessSheetRowView): SheetRow {
  const p = view.payload;
  const row = blankRow();
  row.clientRowId = view.clientRowId;
  row.batchNumber = view.batchNumber;
  row.rowStatus = view.rowStatus;

  if (isXiuYou.value) {
    row.rawBatchId = p.rawMaterialInputs?.[0]?.materialBatchId ?? '';
    row.rawBatchQty = p.rawMaterialInputs?.[0]?.quantity ?? null;
    row.fields['output'] = p.outputQuantity ?? null;
  }
  if (props.processCode === 'chaoshui') {
    row.upstreamBatch = p.upstreamSources?.[0]?.sourceBatchNumber ?? '';
    row.fields['before'] = p.inputQuantity ?? null;
    row.fields['after'] = p.outputQuantity ?? null;
  }
  if (isShuZhi.value) {
    row.upstreamSources = (p.upstreamSources ?? []).map((s) => ({ ...s }));
    row.fields['input'] = p.inputQuantity ?? null;
    row.fields['output'] = p.outputQuantity ?? null;
    row.potCount = p.potCount ?? 1;
    row.potRawKgs = (p.potRawKgs ?? []).map((v) => v);
  }
  row.laborSegments = (p.laborSegments ?? []).map((s) => ({ ...s }));

  // Generic date / daterange fields
  const today = todayStr();
  for (const col of cols.value) {
    if (col.type === 'date' && !(col.key in row.fields)) {
      row.fields[col.key] = null;
    }
    if (col.type === 'daterange') {
      // Payload stores daterange as [start, end] array under col.key.
      // Try to recover it; fall back to [today, today] for older rows that
      // predate the daterange feature (they had a scalar string or null).
      const stored = p[col.key as keyof typeof p];
      if (Array.isArray(stored) && stored.length === 2) {
        row.fields[col.key] = [String(stored[0]), String(stored[1])];
      } else if (typeof stored === 'string' && stored) {
        // Legacy single-date value: treat as both start and end.
        row.fields[col.key] = [stored, stored];
      } else {
        row.fields[col.key] = [today, today];
      }
    }
  }
  return row;
}

// -------------------------------------------------------------------------
// Rows state
// -------------------------------------------------------------------------
// Initialise empty; the watch below populates rows once the async fetch in
// the parent (ProcessSheet.vue → loadAll → getRows) resolves and the
// initialRows prop arrives.  Without a watch, rows was set ONCE at setup()
// time when initialRows was still [] (the fetch hadn't returned yet).
const rows = ref<SheetRow[]>([]);

// Re-hydrate saved rows whenever the parent delivers them.
// Guard: only apply when rows is still in its initial-load state (all
// UNSAVED rows means no user edits yet), so we don't clobber a row the
// user has already started filling in after the sheet was opened.
watch(
  () => props.initialRows,
  (incoming) => {
    // If the user has already added unsaved rows, don't overwrite them.
    // Only hydrate on the first non-empty delivery (initial load).
    const hasUserEdits = rows.value.some((r) => r.rowStatus === 'UNSAVED');
    if (hasUserEdits && rows.value.length > 0) return;
    rows.value = (incoming ?? []).map(hydrateRow);
  },
  { immediate: true, deep: false },
);

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
  // For SAVED rows with a batchNumber: look up in own-process inventory.
  // This is the authoritative value and matches what the 半成品库存 table shows.
  if (row.rowStatus === 'SAVED' && row.batchNumber && props.ownInventoryItems?.length) {
    const inv = props.ownInventoryItems.find((b) => b.batchNumber === row.batchNumber);
    if (inv != null) return inv.remaining;
  }
  // Fallback for chaoshui unsaved rows: derive from upstream usage (original logic).
  if (props.processCode === 'chaoshui') {
    const inv = props.upstreamItems.find((b) => b.batchNumber === row.upstreamBatch);
    return inv ? inv.remaining : null;
  }
  return null;
}

function upstreamWarning(row: SheetRow): string | null {
  if (props.processCode === 'chaoshui') {
    const inv = props.upstreamItems.find((b) => b.batchNumber === row.upstreamBatch);
    if (!inv) return null;
    const usage = (row.fields['before'] as number) ?? 0;
    if (usage > inv.remaining) return `用量 ${usage}kg 超出剩余 ${inv.remaining}kg`;
  }
  if (isShuZhi.value) {
    const warnings: string[] = [];
    for (const src of row.upstreamSources) {
      const inv = props.upstreamItems.find((b) => b.batchNumber === src.sourceBatchNumber);
      if (inv && src.feedQuantityKg > inv.remaining) {
        warnings.push(`${src.sourceBatchNumber} 超出剩余 ${inv.remaining}kg`);
      }
    }
    return warnings.length ? warnings.join('; ') : null;
  }
  return null;
}

// -------------------------------------------------------------------------
// Save-disabled reason (fool-proof gate)
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
    if (row.upstreamSources.some((s) => !s.sourceBatchNumber || !s.feedQuantityKg)) return '请补全所有来源批次及投料量';
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
function buildRequest(row: SheetRow): ProcessSheetRowRequest & Record<string, unknown> {
  const base: ProcessSheetRowRequest & Record<string, unknown> = {
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

  // Append daterange fields so the backend stores them in row_payload JSON.
  for (const col of cols.value) {
    if (col.type === 'daterange') {
      base[col.key] = row.fields[col.key] ?? null;
    }
  }

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
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '保存失败';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
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
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '删除失败';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    row.deleting = false;
  }
}

// -------------------------------------------------------------------------
// 熟制: multi-source helpers
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
// 熟制: pot helpers
// -------------------------------------------------------------------------
function onPotCountChange(row: SheetRow, val: number) {
  row.potCount = val;
  row.potRawKgs = Array.from({ length: val }, (_, i) => row.potRawKgs[i] ?? null);
}

// -------------------------------------------------------------------------
// Lifecycle
// -------------------------------------------------------------------------
onMounted(() => {
  if (isXiuYou.value) loadRawBatches();
});
</script>

<template>
  <div class="sp-grid-wrap">

    <!-- ====================================================================
         CARD LAYOUT
         One card per row. Same row model + editors as the grid, different
         visual wrapper. Expandable labor / mix sections rendered inline.
         ==================================================================== -->
    <template v-if="viewMode === 'card'">
      <div v-for="(row, ri) in rows" :key="row.clientRowId" class="sp-card"
           :class="{ 'sp-card-saved': row.rowStatus === 'SAVED', 'sp-card-draft': row.rowStatus === 'DRAFT' }">

        <!-- Card header: row index + status tag + batch + warning + actions -->
        <div class="sp-card-header">
          <span class="sp-card-idx">#{{ ri + 1 }}</span>
          <el-tag
            :type="row.rowStatus === 'SAVED' ? 'success' : row.rowStatus === 'DRAFT' ? 'warning' : 'info'"
            size="small" style="white-space:nowrap">
            {{ row.rowStatus === 'SAVED' ? '已物化' : row.rowStatus === 'DRAFT' ? '草稿' : '新建' }}
          </el-tag>
          <el-tooltip v-if="upstreamWarning(row)" :content="upstreamWarning(row)!" placement="top">
            <el-icon style="color:#e6a23c;cursor:pointer"><Warning /></el-icon>
          </el-tooltip>
          <span v-if="row.batchNumber" class="sp-card-batchnum">{{ row.batchNumber }}</span>
          <span v-else class="sp-card-batchnum sp-card-batchnum-pending">(保存后生成批次号)</span>
          <div style="flex:1" />
          <!-- Actions -->
          <el-button
            type="primary" size="small" :icon="Check"
            :loading="row.saving"
            :disabled="!!saveDisabledReason(row)"
            :title="saveDisabledReason(row) || '保存此行'"
            @click="handleSave(row)"
            style="padding:3px 8px">保存</el-button>
          <el-button
            type="danger" link size="small" :icon="Delete"
            :loading="row.deleting"
            @click="handleDelete(row)"
            style="margin-left:4px" />
        </div>

        <!-- Card body: field grid -->
        <div class="sp-card-body">

          <!-- 修油: raw-material batch dropdown + out-weight -->
          <template v-if="isXiuYou">
            <div class="sp-card-field">
              <label class="sp-card-label">原料批次</label>
              <el-select
                v-model="row.rawBatchId"
                :loading="rawBatchLoading"
                placeholder="选原料批次"
                filterable clearable
                style="width:100%" size="small">
                <el-option
                  v-for="b in rawBatchOptions" :key="b.id"
                  :label="rawBatchLabel(b)" :value="b.id"
                  :disabled="rawBatchAvailable(b) <= 0" />
                <template #empty>
                  <span style="padding:8px;color:#909399;font-size:12px">暂无可用原料批次</span>
                </template>
              </el-select>
            </div>
            <div class="sp-card-field">
              <label class="sp-card-label">出库重量(kg)</label>
              <el-input-number
                v-model="row.rawBatchQty"
                :min="0" :precision="2"
                controls-position="right"
                style="width:160px" size="small" />
            </div>
          </template>

          <!-- 焯水: single upstream dropdown -->
          <template v-else-if="processCode === 'chaoshui'">
            <div class="sp-card-field">
              <label class="sp-card-label">修油批次</label>
              <el-select
                v-model="row.upstreamBatch"
                placeholder="选修油批次"
                filterable clearable
                style="width:100%" size="small">
                <el-option
                  v-for="item in upstreamItems" :key="item.batchNumber"
                  :label="`${item.batchNumber} (余${item.remaining}kg)`"
                  :value="item.batchNumber"
                  :disabled="item.remaining <= 0" />
              </el-select>
            </div>
          </template>

          <!-- 熟制: multi-source expander -->
          <template v-else-if="isShuZhi">
            <div class="sp-card-field sp-card-field-full">
              <label class="sp-card-label">焯水来源(混锅)</label>
              <el-button link size="small" @click="row.mixExpanded = !row.mixExpanded" style="font-size:12px">
                <el-icon style="margin-right:3px"><component :is="row.mixExpanded ? ArrowDown : ArrowRight" /></el-icon>
                {{ row.upstreamSources.length === 0 ? '+ 来源批' : `${row.upstreamSources.length} 批 · ${row.upstreamSources.reduce((s,x) => s + (x.feedQuantityKg||0), 0).toFixed(1)}kg` }}
              </el-button>
            </div>
            <!-- Mix expanded inline -->
            <div v-if="row.mixExpanded" class="sp-card-field sp-card-field-full sp-card-expand-section">
              <div style="margin-bottom:6px;display:flex;align-items:center;gap:8px">
                <span style="font-size:12px;font-weight:600;color:#303133">焯水来源批 (混锅)</span>
                <el-button size="small" :icon="Plus" @click="addUpstreamSource(row)">+ 来源批</el-button>
              </div>
              <div v-for="(src, si) in row.upstreamSources" :key="si"
                   style="display:flex;align-items:center;gap:8px;margin-bottom:6px;flex-wrap:wrap">
                <el-select
                  v-model="src.sourceBatchNumber"
                  placeholder="选焯水批次" filterable clearable
                  style="width:220px" size="small">
                  <el-option
                    v-for="item in upstreamItems" :key="item.batchNumber"
                    :label="`${item.batchNumber} (余${item.remaining}kg)`"
                    :value="item.batchNumber"
                    :disabled="item.remaining <= 0" />
                </el-select>
                <el-input-number
                  v-model="src.feedQuantityKg"
                  :min="0" :precision="2"
                  placeholder="投料kg"
                  controls-position="right"
                  size="small" style="width:120px" />
                <span v-if="src.sourceBatchNumber" style="font-size:11px;color:#909399">
                  {{ (() => { const inv = upstreamItems.find(b => b.batchNumber === src.sourceBatchNumber); return inv ? `余${inv.remaining}kg` : ''; })() }}
                </span>
                <el-button link type="danger" :icon="Delete" @click="removeUpstreamSource(row, si)" />
              </div>
              <div v-if="row.upstreamSources.length === 0" style="color:#909399;font-size:12px;margin:4px 0">
                暂无来源批，点击 + 来源批 添加
              </div>
              <!-- Pot count -->
              <div style="margin-top:12px;display:flex;align-items:center;gap:8px;flex-wrap:wrap">
                <span style="font-size:12px;font-weight:600;color:#303133">锅数:</span>
                <el-input-number
                  :model-value="row.potCount"
                  @update:model-value="(v: number) => onPotCountChange(row, v)"
                  :min="1" :precision="0" size="small" style="width:80px" />
                <template v-if="row.potCount > 1">
                  <div v-for="pi in row.potCount" :key="pi"
                       style="display:flex;align-items:center;gap:4px">
                    <span style="font-size:12px;color:#606266">第{{ pi }}锅(kg):</span>
                    <el-input-number
                      v-model="row.potRawKgs[pi - 1]"
                      :min="0" :precision="2" size="small" style="width:100px" />
                  </div>
                </template>
              </div>
            </div>
          </template>

          <!-- Generic columns from config (skip special-cased keys) -->
          <template v-for="col in cols" :key="col.key">
            <div
              v-if="!['rawBatch','outWeight','upstreamBatch','batch'].includes(col.key)"
              class="sp-card-field"
              :class="{ 'sp-card-field-auto': col.type === 'auto' || col.type === 'readonly' }">
              <label class="sp-card-label">{{ col.label }}</label>

              <el-input-number
                v-if="col.type === 'number'"
                :model-value="(row.fields[col.key] as number) ?? undefined"
                @update:model-value="(v: number) => row.fields[col.key] = v"
                :min="0" :precision="2"
                controls-position="right"
                style="width:160px" size="small" />

              <el-date-picker
                v-else-if="col.type === 'date'"
                :model-value="(row.fields[col.key] as string) || undefined"
                @update:model-value="(v: string) => row.fields[col.key] = v"
                type="date" value-format="YYYY-MM-DD"
                style="width:160px" size="small" />

              <!-- daterange picker: card mode full-width -->
              <el-date-picker
                v-else-if="col.type === 'daterange'"
                :model-value="(row.fields[col.key] as [string,string]) || null"
                @update:model-value="(v: [string,string] | null) => row.fields[col.key] = v ?? null"
                type="daterange"
                range-separator="~"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                value-format="YYYY-MM-DD"
                style="width:100%" size="small" />

              <span v-else-if="col.type === 'auto' && col.autoCalc === 'yield'" class="sp-readonly">
                {{ calcYield(row) != null ? calcYield(row)!.toFixed(2) + '%' : '—' }}
              </span>

              <span v-else-if="col.type === 'auto' && col.autoCalc === 'remaining'" class="sp-readonly"
                :style="{ color: calcRemaining(row) != null && calcRemaining(row)! <= 0 ? '#f56c6c' : undefined }">
                {{ calcRemaining(row) != null ? calcRemaining(row)!.toFixed(2) : '—' }}
              </span>

              <!-- totalHours shown in the labor expander below; skip inline -->
              <span v-else-if="col.type === 'auto' && col.autoCalc === 'totalHours'" />

              <span v-else-if="col.type === 'readonly' || col.type === 'text'" class="sp-readonly">
                {{ row.fields[col.key] ?? '—' }}
              </span>
            </div>
          </template>

          <!-- Labor expander -->
          <div class="sp-card-field sp-card-field-full">
            <label class="sp-card-label">工时</label>
            <el-button link size="small" @click="row.laborExpanded = !row.laborExpanded" style="font-size:12px">
              <el-icon style="margin-right:3px"><component :is="row.laborExpanded ? ArrowDown : ArrowRight" /></el-icon>
              {{ calcTotalHours(row).toFixed(1) }}h · {{ row.laborSegments.length }}段
            </el-button>
          </div>
          <div v-if="row.laborExpanded" class="sp-card-field sp-card-field-full sp-card-expand-section">
            <div style="font-size:12px;font-weight:600;color:#303133;margin-bottom:8px">
              工时录入 — {{ row.batchNumber || '(未保存行)' }}
            </div>
            <WorkHoursTable v-model="row.laborSegments" />
          </div>

        </div><!-- /.sp-card-body -->
      </div><!-- /v-for cards -->

      <!-- Add row button (card mode) -->
      <div style="margin-top:8px">
        <el-button :icon="Plus" @click="addRow" style="width:100%" plain>+ 新增行</el-button>
      </div>
    </template>

    <!-- ====================================================================
         GRID LAYOUT (original flat spreadsheet table)
         ==================================================================== -->
    <template v-else>
    <!-- Flat spreadsheet table -->
    <div class="sp-table-scroll">
      <table class="sp-grid">
        <!-- ================================================================
             Header row
             ================================================================ -->
        <thead>
          <tr>
            <th class="sp-th sp-th-status">状态</th>

            <!-- 修油: raw batch + out-weight cols appear before generic cols -->
            <template v-if="isXiuYou">
              <th class="sp-th">原料批次</th>
              <th class="sp-th sp-th-num">出库重量(kg)</th>
            </template>

            <!-- 焯水: upstream single-select -->
            <template v-else-if="processCode === 'chaoshui'">
              <th class="sp-th">修油批次</th>
            </template>

            <!-- 熟制: multi-source (rendered as expander cell) -->
            <template v-else-if="isShuZhi">
              <th class="sp-th">焯水来源(混锅)</th>
            </template>

            <!-- Generic cols from config (skip special-cased keys) -->
            <template v-for="col in cols" :key="col.key">
              <th v-if="!['rawBatch','outWeight','upstreamBatch','batch'].includes(col.key)"
                  class="sp-th"
                  :class="{
                    'sp-th-num': col.type === 'number' || col.type === 'auto',
                    'sp-th-date': col.type === 'date',
                    'sp-th-daterange': col.type === 'daterange',
                  }">
                {{ col.label }}
              </th>
            </template>

            <!-- System batch (readonly) -->
            <th class="sp-th sp-th-batch">批次号</th>
            <!-- Labor expander trigger -->
            <th class="sp-th sp-th-labor">工时</th>
            <!-- Actions -->
            <th class="sp-th sp-th-actions">操作</th>
          </tr>
        </thead>

        <tbody>
          <template v-for="(row, ri) in rows" :key="row.clientRowId">
            <!-- ============================================================
                 Main data row
                 ============================================================ -->
            <tr :class="['sp-tr', ri % 2 === 0 ? 'sp-tr-even' : 'sp-tr-odd', { 'sp-tr-saved': row.rowStatus === 'SAVED', 'sp-tr-draft': row.rowStatus === 'DRAFT' }]">

              <!-- Status tag -->
              <td class="sp-td sp-td-status">
                <el-tag
                  :type="row.rowStatus === 'SAVED' ? 'success' : row.rowStatus === 'DRAFT' ? 'warning' : 'info'"
                  size="small" style="white-space:nowrap">
                  {{ row.rowStatus === 'SAVED' ? '已物化' : row.rowStatus === 'DRAFT' ? '草稿' : '新建' }}
                </el-tag>
                <el-tooltip v-if="upstreamWarning(row)" :content="upstreamWarning(row)!" placement="top">
                  <el-icon style="color:#e6a23c;margin-left:3px;cursor:pointer"><Warning /></el-icon>
                </el-tooltip>
              </td>

              <!-- ---- 修油: raw-material batch dropdown ---- -->
              <template v-if="isXiuYou">
                <td class="sp-td">
                  <el-select
                    v-model="row.rawBatchId"
                    :loading="rawBatchLoading"
                    placeholder="选原料批次"
                    filterable
                    clearable
                    style="width:220px"
                    size="small">
                    <el-option
                      v-for="b in rawBatchOptions"
                      :key="b.id"
                      :label="rawBatchLabel(b)"
                      :value="b.id"
                      :disabled="rawBatchAvailable(b) <= 0" />
                    <template #empty>
                      <span style="padding:8px;color:#909399;font-size:12px">暂无可用原料批次</span>
                    </template>
                  </el-select>
                </td>
                <td class="sp-td sp-td-num">
                  <el-input-number
                    v-model="row.rawBatchQty"
                    :min="0" :precision="2"
                    controls-position="right"
                    style="width:110px" size="small" />
                </td>
              </template>

              <!-- ---- 焯水: single upstream dropdown ---- -->
              <template v-else-if="processCode === 'chaoshui'">
                <td class="sp-td">
                  <el-select
                    v-model="row.upstreamBatch"
                    placeholder="选修油批次"
                    filterable clearable
                    style="width:200px" size="small">
                    <el-option
                      v-for="item in upstreamItems"
                      :key="item.batchNumber"
                      :label="`${item.batchNumber} (余${item.remaining}kg)`"
                      :value="item.batchNumber"
                      :disabled="item.remaining <= 0" />
                  </el-select>
                </td>
              </template>

              <!-- ---- 熟制: multi-source expander cell ---- -->
              <template v-else-if="isShuZhi">
                <td class="sp-td">
                  <el-button
                    link size="small"
                    @click="row.mixExpanded = !row.mixExpanded"
                    style="font-size:12px">
                    <el-icon style="margin-right:3px"><component :is="row.mixExpanded ? ArrowDown : ArrowRight" /></el-icon>
                    {{ row.upstreamSources.length === 0 ? '+ 来源批' : `${row.upstreamSources.length} 批 · ${row.upstreamSources.reduce((s,x) => s + (x.feedQuantityKg||0), 0).toFixed(1)}kg` }}
                  </el-button>
                </td>
              </template>

              <!-- ---- Generic columns from config ---- -->
              <template v-for="col in cols" :key="col.key">
                <td
                  v-if="!['rawBatch','outWeight','upstreamBatch','batch'].includes(col.key)"
                  class="sp-td"
                  :class="{
                    'sp-td-num': col.type === 'number' || col.type === 'auto',
                    'sp-td-date': col.type === 'date',
                    'sp-td-daterange': col.type === 'daterange',
                  }">

                  <!-- number input -->
                  <el-input-number
                    v-if="col.type === 'number'"
                    :model-value="(row.fields[col.key] as number) ?? undefined"
                    @update:model-value="(v: number) => row.fields[col.key] = v"
                    :min="0" :precision="2"
                    controls-position="right"
                    style="width:110px" size="small" />

                  <!-- date picker (single) -->
                  <el-date-picker
                    v-else-if="col.type === 'date'"
                    :model-value="(row.fields[col.key] as string) || undefined"
                    @update:model-value="(v: string) => row.fields[col.key] = v"
                    type="date" value-format="YYYY-MM-DD"
                    style="width:130px" size="small" />

                  <!-- daterange picker: 开始日期 ~ 结束日期 -->
                  <el-date-picker
                    v-else-if="col.type === 'daterange'"
                    :model-value="(row.fields[col.key] as [string,string]) || null"
                    @update:model-value="(v: [string,string] | null) => row.fields[col.key] = v ?? null"
                    type="daterange"
                    range-separator="~"
                    start-placeholder="开始"
                    end-placeholder="结束"
                    value-format="YYYY-MM-DD"
                    style="width:230px" size="small" />

                  <!-- auto: yield -->
                  <span v-else-if="col.type === 'auto' && col.autoCalc === 'yield'" class="sp-readonly">
                    {{ calcYield(row) != null ? calcYield(row)!.toFixed(2) + '%' : '—' }}
                  </span>

                  <!-- auto: remaining -->
                  <span v-else-if="col.type === 'auto' && col.autoCalc === 'remaining'" class="sp-readonly"
                    :style="{ color: calcRemaining(row) != null && calcRemaining(row)! <= 0 ? '#f56c6c' : undefined }">
                    {{ calcRemaining(row) != null ? calcRemaining(row)!.toFixed(2) : '—' }}
                  </span>

                  <!-- auto: totalHours — shown in dedicated labor column instead -->
                  <span v-else-if="col.type === 'auto' && col.autoCalc === 'totalHours'" />

                  <!-- readonly / text -->
                  <span v-else-if="col.type === 'readonly' || col.type === 'text'" class="sp-readonly">
                    {{ row.fields[col.key] ?? '—' }}
                  </span>
                </td>
              </template>

              <!-- Batch number (readonly) -->
              <td class="sp-td sp-td-batch">
                <span class="sp-readonly sp-batch-num">{{ row.batchNumber || '(保存后生成)' }}</span>
              </td>

              <!-- Labor expander trigger -->
              <td class="sp-td sp-td-labor">
                <el-button link size="small" @click="row.laborExpanded = !row.laborExpanded" style="font-size:12px">
                  <el-icon style="margin-right:3px"><component :is="row.laborExpanded ? ArrowDown : ArrowRight" /></el-icon>
                  {{ calcTotalHours(row).toFixed(1) }}h·{{ row.laborSegments.length }}段
                </el-button>
              </td>

              <!-- Row actions -->
              <td class="sp-td sp-td-actions">
                <el-button
                  type="primary" size="small" :icon="Check"
                  :loading="row.saving"
                  :disabled="!!saveDisabledReason(row)"
                  :title="saveDisabledReason(row) || '保存此行'"
                  @click="handleSave(row)"
                  style="padding:3px 8px">保存</el-button>
                <el-button
                  type="danger" link size="small" :icon="Delete"
                  :loading="row.deleting"
                  @click="handleDelete(row)"
                  style="margin-left:4px" />
              </td>
            </tr>

            <!-- ============================================================
                 Labor expander row
                 ============================================================ -->
            <tr v-if="row.laborExpanded" :key="row.clientRowId + '-labor'"
                :class="['sp-tr-expand', ri % 2 === 0 ? 'sp-tr-even' : 'sp-tr-odd']">
              <td :colspan="999" class="sp-td-expand">
                <div class="sp-expand-section">
                  <div class="sp-expand-title">工时录入 — {{ row.batchNumber || '(未保存行)' }}</div>
                  <WorkHoursTable v-model="row.laborSegments" />
                </div>
              </td>
            </tr>

            <!-- ============================================================
                 熟制: 混锅来源 + 锅数 expander row
                 ============================================================ -->
            <tr v-if="isShuZhi && row.mixExpanded" :key="row.clientRowId + '-mix'"
                :class="['sp-tr-expand', ri % 2 === 0 ? 'sp-tr-even' : 'sp-tr-odd']">
              <td :colspan="999" class="sp-td-expand">
                <div class="sp-expand-section">
                  <div class="sp-expand-title">
                    焯水来源批 (混锅) — {{ row.batchNumber || '(未保存行)' }}
                    <el-button size="small" :icon="Plus" style="margin-left:8px" @click="addUpstreamSource(row)">
                      + 来源批
                    </el-button>
                  </div>

                  <!-- Multi-source rows -->
                  <div v-for="(src, si) in row.upstreamSources" :key="si"
                       style="display:flex;align-items:center;gap:8px;margin-bottom:6px">
                    <el-select
                      v-model="src.sourceBatchNumber"
                      placeholder="选焯水批次" filterable clearable
                      style="width:220px" size="small">
                      <el-option
                        v-for="item in upstreamItems" :key="item.batchNumber"
                        :label="`${item.batchNumber} (余${item.remaining}kg)`"
                        :value="item.batchNumber"
                        :disabled="item.remaining <= 0" />
                    </el-select>
                    <el-input-number
                      v-model="src.feedQuantityKg"
                      :min="0" :precision="2"
                      placeholder="投料kg"
                      controls-position="right"
                      size="small" style="width:120px" />
                    <span v-if="src.sourceBatchNumber" style="font-size:11px;color:#909399">
                      {{ (() => { const inv = upstreamItems.find(b => b.batchNumber === src.sourceBatchNumber); return inv ? `余${inv.remaining}kg` : ''; })() }}
                    </span>
                    <el-button link type="danger" :icon="Delete" @click="removeUpstreamSource(row, si)" />
                  </div>
                  <div v-if="row.upstreamSources.length === 0" style="color:#909399;font-size:12px;margin:4px 0">
                    暂无来源批，点击 + 来源批 添加
                  </div>

                  <!-- Pot count -->
                  <div style="margin-top:12px;display:flex;align-items:center;gap:8px;flex-wrap:wrap">
                    <span style="font-size:12px;font-weight:600;color:#303133">锅数:</span>
                    <el-input-number
                      :model-value="row.potCount"
                      @update:model-value="(v: number) => onPotCountChange(row, v)"
                      :min="1" :precision="0" size="small" style="width:80px" />
                    <template v-if="row.potCount > 1">
                      <div v-for="pi in row.potCount" :key="pi"
                           style="display:flex;align-items:center;gap:4px">
                        <span style="font-size:12px;color:#606266">第{{ pi }}锅(kg):</span>
                        <el-input-number
                          v-model="row.potRawKgs[pi - 1]"
                          :min="0" :precision="2" size="small" style="width:100px" />
                      </div>
                    </template>
                  </div>
                </div>
              </td>
            </tr>

          </template><!-- end v-for rows -->
        </tbody>
      </table>
    </div><!-- /.sp-table-scroll -->

    <!-- Add row button (grid mode) -->
    <div style="margin-top:8px">
      <el-button :icon="Plus" @click="addRow" style="width:100%" plain>
        + 新增行
      </el-button>
    </div>
    </template><!-- /grid layout -->

  </div>
</template>

<style scoped>
/* -------------------------------------------------------------------------
   Spreadsheet grid
   ------------------------------------------------------------------------- */
.sp-grid-wrap {
  display: flex;
  flex-direction: column;
}

.sp-table-scroll {
  overflow-x: auto;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
}

.sp-grid {
  border-collapse: collapse;
  width: 100%;
  font-size: 12px;
  min-width: 700px;
}

/* Header */
.sp-th {
  position: sticky;
  top: 0;
  z-index: 2;
  background: #f5f7fa;
  color: #606266;
  font-weight: 600;
  border: 1px solid #dcdfe6;
  padding: 6px 8px;
  white-space: nowrap;
  text-align: left;
}
.sp-th-status    { width: 72px; }
.sp-th-num       { text-align: right; width: 120px; }
.sp-th-date      { width: 140px; }
.sp-th-daterange { width: 240px; }
.sp-th-batch     { width: 160px; min-width: 130px; }
.sp-th-labor     { width: 100px; }
.sp-th-actions   { width: 120px; text-align: center; }

/* Body cells */
.sp-td {
  border: 1px solid #ebeef5;
  padding: 5px 6px;
  vertical-align: middle;
}
.sp-td-status    { width: 72px; text-align: center; }
.sp-td-num       { text-align: right; }
.sp-td-date      {}
.sp-td-daterange {}
.sp-td-batch     { color: #409eff; font-weight: 600; font-size: 11px; }
.sp-td-labor   { text-align: center; }
.sp-td-actions { text-align: center; white-space: nowrap; }

/* Row alternating background */
.sp-tr-even { background: #ffffff; }
.sp-tr-odd  { background: #fafafa; }
.sp-tr-saved .sp-td-status { background: #f0f9eb; }
.sp-tr-draft .sp-td-status { background: #fdf6ec; }

/* Expand rows */
.sp-tr-expand td {
  border: none;
  border-bottom: 1px solid #ebeef5;
}
.sp-td-expand {
  padding: 0 12px 8px;
}
.sp-expand-section {
  background: #f8f9fa;
  border: 1px solid #e8eaed;
  border-radius: 4px;
  padding: 10px 12px;
  margin: 4px 0;
}
.sp-expand-title {
  font-size: 12px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}

/* Readonly display */
.sp-readonly {
  color: #606266;
  font-size: 12px;
  display: inline-block;
}
.sp-batch-num {
  color: #409eff;
  font-size: 11px;
  word-break: break-all;
}

/* -------------------------------------------------------------------------
   Card layout
   ------------------------------------------------------------------------- */
.sp-card {
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  margin-bottom: 10px;
  overflow: hidden;
  background: #fff;
}
.sp-card-saved {
  border-color: #b3e19d;
}
.sp-card-draft {
  border-color: #f5dab1;
}

.sp-card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #f5f7fa;
  border-bottom: 1px solid #ebeef5;
  flex-wrap: wrap;
}
.sp-card-idx {
  font-size: 12px;
  color: #909399;
  min-width: 22px;
}
.sp-card-batchnum {
  font-size: 11px;
  color: #409eff;
  font-weight: 600;
  word-break: break-all;
}
.sp-card-batchnum-pending {
  color: #c0c4cc;
  font-weight: 400;
}

.sp-card-body {
  padding: 10px 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px 16px;
}

.sp-card-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 160px;
}
/* Full-width fields (upstream source expander / labor / mix) */
.sp-card-field-full {
  flex: 1 1 100%;
  min-width: 100%;
}
/* Auto/readonly fields can be narrower */
.sp-card-field-auto {
  min-width: 110px;
}

.sp-card-label {
  font-size: 11px;
  color: #909399;
  font-weight: 600;
  white-space: nowrap;
}

/* Inline expand sections within card (labor / mix) */
.sp-card-expand-section {
  background: #f8f9fa;
  border: 1px solid #e8eaed;
  border-radius: 4px;
  padding: 10px 12px;
}
</style>
