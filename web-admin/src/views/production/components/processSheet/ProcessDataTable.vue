<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Delete, Check, Warning, ArrowDown, ArrowRight, Clock } from '@element-plus/icons-vue';
import {
  saveRow, deleteRow, getAvailableRawBatches, getRowHistory, getSemiFinishedInventory,
  getFinishedGoodsInventory,
  type ProcessSheetInventoryItem,
  type LaborSegment,
  type UpstreamRef,
  type ProcessSheetRowView,
  type ProcessSheetRowRequest,
  type ProcessSheetRowHistoryView,
  type RawMaterialBatchOption,
  type SemiFinishedStockItem,
  type SemiFinishedInventoryFilter,
  type FinishedGoodsStockItem,
} from '@/api/processSheet';
import { listWarehouses, type FactoryWarehouse } from '@/api/factoryWarehouse';
import { PROCESS_SHEET_CONFIG, genClientRowId } from './PROCESS_SHEET_CONFIG';
import WorkHoursTable from './WorkHoursTable.vue';
import { calculateLaborPerBox } from '@/utils/processSheetLaborCost';
import { isCountUnit, countUnitFeedWarning, countUnitLabelSuffix } from '@/utils/feedUnitConversion';

// -------------------------------------------------------------------------
// Props & emits
// -------------------------------------------------------------------------
const props = withDefaults(defineProps<{
  factoryId: string;
  planId: string;
  processCode: string;
  processOrder: number;
  productTypeId: string;
  /** 本工序真实显示名 (来自 G0 动态工序链 ProductWorkProcess.processName, 如"去舌胎膜"); 与
   * archetype processCode 不同 —— processCode 只是列定义 key, 可能多个真实工序共享同一 archetype。 */
  processLabel?: string;
  /** Bug 1 修复: 上游(前置)工序真实显示名 (G0 动态链前一道的真实名称, 由父组件按链序传入)。
   * 链起步道(无上游, 如从半成品起步)时为 undefined。禁止在本组件内部按 processCode 猜测上游名。 */
  upstreamProcessLabel?: string;
  /** WIP inventory items from the upstream process (for dropdown + remaining calc) */
  upstreamItems?: ProcessSheetInventoryItem[];
  /** WIP inventory items for THIS process (for grid 剩余 column on saved rows) */
  ownInventoryItems?: ProcessSheetInventoryItem[];
  /** Existing saved rows loaded from backend on mount */
  initialRows?: ProcessSheetRowView[];
  /** Layout mode toggled in the drawer header. Default: 'grid'. */
  viewMode?: 'grid' | 'card';
}>(), {
  upstreamItems: () => [],
  ownInventoryItems: () => [],
  initialRows: () => [],
  viewMode: 'grid',
});
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
  /** 已小结时间 (ISO-8601); null = 未小结，可编辑 */
  interimSettledAt: string | null;
  saving: boolean;
  deleting: boolean;
  /** Generic per-process scalar fields: date / number / daterange ([start,end]) values keyed by ColDef.key */
  fields: Record<string, string | number | [string, string] | null>;
  /** 修油: selected raw material batch id → rawMaterialInputs[0].materialBatchId */
  rawBatchId: string;
  /** 修油: out-weight (kg) → rawMaterialInputs[0].quantity */
  rawBatchQty: number | null;
  /** 焯水: single upstream WIP batch number (WIP batchNumber 或 SFI intermediateBatchNo) */
  upstreamBatch: string;
  /**
   * 焯水/滚揉/去舌苔 单上游: 所选来源是否为常驻半成品库存 (SFI)。
   * true → upstreamBatch 指向 SemiFinishedInventory.intermediateBatchNo, buildPayload 写
   * upstreamSources[0].semiFinished=true → 后端走 ③=F 纯 SFI 路径 (SAVED_SFI, 小结 SFI in/out)。
   * false → in-plan 在制 WIP。链起步道可仅选 SFI (upstreamItems 空)。
   */
  upstreamSemiFinished: boolean;
  /**
   * ①c 焯水/滚揉/去舌苔 单上游: 所选来源是否为常驻成品库存 (FG)。
   * true → upstreamBatch 指向 FinishedGoodsBatch.batchNumber, buildRequest 写 upstreamSources[0].finishedGoods=true
   * → 后端小结经 consumeForFeedStrict 严格扣减成品。与 upstreamSemiFinished 互斥。
   */
  upstreamFinishedGoods: boolean;
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
/** 单上游 WIP 工序: 焯水 + 滚揉. 两者结构完全相同 (before/after 字段, 单 upstream). */
const isSingleUpstream = computed(() =>
  props.processCode === 'chaoshui' || props.processCode === 'gunrou',
);
const isQuSheTou = computed(() => props.processCode === 'qushetou');
const isQidiao = computed(() => props.processCode === 'qidiao');

// Bug 1 修复: 来源批次选择器的 label 必须反映真实工序链, 不能按 archetype processCode 硬编码
// (硬编码在 role-mode / 关键词回退下必错 —— 同一 archetype 可能对应不同真实工序名)。
/** 本工序真实显示名 (缺省兜底用 processCode, 理论上父组件总会传, 兜底只防御性)。 */
const ownProcessName = computed(() => props.processLabel || props.processCode);
/** 上游(前置)工序真实显示名; 链起步道(无上游)兜底通用「上游」。 */
const upstreamProcessName = computed(() => props.upstreamProcessLabel || '上游');

// -------------------------------------------------------------------------
// Raw material batch options (for 修油 首道)
// -------------------------------------------------------------------------
const rawBatchOptions = ref<RawMaterialBatchOption[]>([]);
const rawBatchLoading = ref(false);
const consumableWarehouseIds = ref<string[]>([]);
// 未落仓(warehouseId 为空)因而被隐藏的批次数 — 后端 ensureRawMaterialWarehouse 强制要求
// RAW/LOGISTICS/WORKSHOP 仓库, 空 warehouseId 的批次保存必 409。展示这个数字而不是静默隐藏,
// 让用户知道"为什么少了几条", 而不是以为数据丢了 (Rule 1: 预先显示边界)。
const rawBatchExcludedNoWarehouseCount = ref(0);
let rawBatchLoadSeq = 0;

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

// 报工消耗可领用的仓库: 原料仓/物流仓 (WH-LOG/RAW/LOGISTICS) + 生产仓 (WH-WKS/WORKSHOP)。
// 领料调拨后, 原料已实际搬到生产仓 (batch.warehouseId=WH-WKS, sourceDocType=MATERIAL_REQUISITION),
// 报工必须能选到生产仓里的这些料 (后端 ensureRawMaterialWarehouse 已接受 WORKSHOP 批次)。WIP/半成品
// 批 (sourceDocType=PRODUCTION_BATCH) 由 extractRawBatches 过滤掉, 不会被误当原料领用。
function pickConsumableWarehouseIds(warehouses: FactoryWarehouse[]): string[] {
  return warehouses
    .filter((w) => w.isActive !== false)
    .filter(
      (w) =>
        w.type === 'RAW' ||
        w.type === 'LOGISTICS' ||
        w.type === 'WORKSHOP' ||
        w.code === 'WH-LOG' ||
        w.code === 'WH-RAW' ||
        w.code === 'WH-WKS',
    )
    .map((w) => w.id);
}

async function ensureConsumableWarehouseIds(): Promise<string[] | null> {
  if (consumableWarehouseIds.value.length > 0) return consumableWarehouseIds.value;
  const resp = await listWarehouses(props.factoryId);
  const warehouses = Array.isArray(resp.data) ? resp.data : [];
  const ids = pickConsumableWarehouseIds(warehouses);
  if (ids.length === 0) {
    ElMessage.error('未配置原料仓/物流仓/生产仓，无法加载原料批次');
    return null;
  }
  consumableWarehouseIds.value = ids;
  return ids;
}

async function loadRawBatches() {
  if (!isXiuYou.value || !props.factoryId) return;
  const seq = ++rawBatchLoadSeq;
  rawBatchLoading.value = true;
  try {
    const warehouseIds = await ensureConsumableWarehouseIds();
    if (!warehouseIds) {
      rawBatchOptions.value = [];
      return;
    }
    // 工厂全仓拉一次 (不限单仓), 再按可领用仓集合过滤 → 同时覆盖原料仓 + 生产仓, 排除成品/质检等其它仓。
    const resp = await getAvailableRawBatches(props.factoryId, {
      productTypeId: props.productTypeId,
    });
    if (seq !== rawBatchLoadSeq) return;
    const allowed = new Set(warehouseIds);
    const candidates = extractRawBatches(resp.data).filter((b) => rawBatchAvailable(b) > 0);
    // 后端 ensureRawMaterialWarehouse 强制要求非空 warehouseId 且属于 RAW/LOGISTICS/WORKSHOP,
    // 否则保存必 409 "只能从原料仓/物流仓/生产仓领用"。不落仓(null warehouseId)的批次一律
    // 不提供选择 —— 提供了也是选完就被后端拒绝的死路 (fool-proof-design Rule 5: 不做 dead-end)。
    rawBatchExcludedNoWarehouseCount.value = candidates.filter((b) => !b.warehouseId).length;
    rawBatchOptions.value = candidates.filter((b) => !!b.warehouseId && allowed.has(b.warehouseId));
  } catch (err) {
    if (seq !== rawBatchLoadSeq) return;
    rawBatchOptions.value = [];
    ElMessage.error(err instanceof Error ? err.message : '原料批次加载失败');
  } finally {
    if (seq === rawBatchLoadSeq) rawBatchLoading.value = false;
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
    interimSettledAt: null,
    saving: false,
    deleting: false,
    fields: { ...daterangeDefaults },
    rawBatchId: '',
    rawBatchQty: null,
    upstreamBatch: '',
    upstreamSemiFinished: false,
    upstreamFinishedGoods: false,
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
  row.interimSettledAt = view.interimSettledAt ?? null;

  if (isXiuYou.value) {
    row.rawBatchId = p.rawMaterialInputs?.[0]?.materialBatchId ?? '';
    row.rawBatchQty = p.rawMaterialInputs?.[0]?.quantity ?? null;
    row.fields['output'] = p.outputQuantity ?? null;
    // SP-G G3c: 副产 hydrate (修油)
    const bp0 = p.byproducts?.[0];
    if (bp0) {
      row.fields['byproductQty']   = bp0.quantity ?? null;
      row.fields['byproductPrice'] = bp0.unitPrice ?? null;
    }
  }
  if (isSingleUpstream.value) {
    // 焯水 + 滚揉: 结构相同 (before → inputQuantity, after → outputQuantity)
    row.upstreamBatch = p.upstreamSources?.[0]?.sourceBatchNumber ?? '';
    row.upstreamSemiFinished = p.upstreamSources?.[0]?.semiFinished ?? false;
    row.upstreamFinishedGoods = p.upstreamSources?.[0]?.finishedGoods ?? false;
    row.fields['before'] = p.inputQuantity ?? null;
    row.fields['after'] = p.outputQuantity ?? null;
    // SP-G G3c: 副产 hydrate (焯水 + 滚揉)
    const bp0 = p.byproducts?.[0];
    if (bp0) {
      row.fields['byproductQty']   = bp0.quantity ?? null;
      row.fields['byproductPrice'] = bp0.unitPrice ?? null;
    }
  }
  if (isQuSheTou.value) {
    // 去舌苔: output + scrap → input 反推. inputQuantity = scrap + output.
    row.upstreamBatch = p.upstreamSources?.[0]?.sourceBatchNumber ?? '';
    row.upstreamSemiFinished = p.upstreamSources?.[0]?.semiFinished ?? false;
    row.upstreamFinishedGoods = p.upstreamSources?.[0]?.finishedGoods ?? false;
    row.fields['output'] = p.outputQuantity ?? null;
    // scrap = inputQuantity - outputQuantity (反推恢复, 若无法恢复则留 null)
    const inp = p.inputQuantity ?? null;
    const out = p.outputQuantity ?? null;
    row.fields['scrap'] = inp != null && out != null ? inp - out : null;
  }
  if (isShuZhi.value) {
    row.upstreamSources = (p.upstreamSources ?? []).map((s) => ({ ...s }));
    row.fields['input'] = p.inputQuantity ?? null;
    row.fields['output'] = p.outputQuantity ?? null;
    row.potCount = p.potCount ?? 1;
    row.potRawKgs = (p.potRawKgs ?? []).map((v) => v);
  }
  if (isQidiao.value) {
    row.upstreamSources = (p.upstreamSources ?? []).map((s) => ({ ...s }));
    row.fields['usedWeight'] = p.inputQuantity ?? null;
    // outputQuantity = actualProd (盒数); restored from payload into auto-calc fields
    const payloadFields = p as unknown as Record<string, unknown>;
    row.fields['storage']       = payloadFields['storage']       as number ?? null;
    row.fields['sample']        = payloadFields['sample']        as number ?? null;
    row.fields['remainBox']     = payloadFields['remainBox']     as number ?? null;
    row.fields['claim']         = payloadFields['claim']         as number ?? null;
    row.fields['productWeight'] = payloadFields['productWeight'] as number ?? null;
    row.fields['trimmings']     = payloadFields['trimmings']     as number ?? null;
    row.fields['boxWeight']     = payloadFields['boxWeight']     as number ?? null;
    row.fields['workerPrice']   = payloadFields['workerPrice']   as number ?? null;
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
const rowScopeKey = computed(() =>
  `${props.factoryId}|${props.planId}|${props.productTypeId}|${props.processCode}|${props.processOrder}`,
);
let lastRowScopeKey = '';

function normalizeInitialRows(value: ProcessSheetRowView[] | null | undefined): ProcessSheetRowView[] {
  return Array.isArray(value) ? value : [];
}

// Re-hydrate saved rows whenever the parent delivers them.
// Guard: only apply when rows is still in its initial-load state (all
// UNSAVED rows means no user edits yet), so we don't clobber a row the
// user has already started filling in after the sheet was opened.
watch(
  () => [rowScopeKey.value, props.initialRows] as const,
  ([scopeKey, incoming]) => {
    const normalizedIncoming = normalizeInitialRows(incoming);
    if (scopeKey !== lastRowScopeKey) {
      lastRowScopeKey = scopeKey;
      rows.value = normalizedIncoming.map(hydrateRow);
      return;
    }

    // Same plan/process: if the user has already added unsaved rows, don't overwrite them.
    // This keeps in-progress edits safe while still clearing rows when the drawer is reused
    // for another plan/process.
    const hasUserEdits = rows.value.some((r) => r.rowStatus === 'UNSAVED');
    if (hasUserEdits && rows.value.length > 0) return;
    rows.value = normalizedIncoming.map(hydrateRow);
  },
  { immediate: true, deep: false },
);

function addRow() {
  rows.value.push(blankRow());
}

// -------------------------------------------------------------------------
// 已小结行折叠 (BY_STOCK 小结后转结到批次，计划保持开放)
// -------------------------------------------------------------------------

/** 已小结 (interimSettledAt != null): 只读，默认折叠。 */
const settledRows = computed(() => rows.value.filter((r) => r.interimSettledAt != null));
/** 未小结 (interimSettledAt == null): 正常可编辑。 */
const activeRows  = computed(() => rows.value.filter((r) => r.interimSettledAt == null));

/** 已小结区块展开状态 (默认折叠 → 操作员看到干净的录入界面)。 */
const settledExpanded = ref(false);

/** 格式化小结时间 (ISO-8601 → "YYYY-MM-DD HH:mm")。 */
function formatSettledAt(iso: string | null): string {
  if (!iso) return '';
  return iso.replace('T', ' ').slice(0, 16);
}

/**
 * 已小结行的产出摘要 (单行文字)。
 * 按工序类型提取最关键的一个数字给操作员一眼看清楚。
 */
function settledRowSummary(row: SheetRow): string {
  if (isXiuYou.value) {
    const out = row.fields['output'];
    return out != null ? `产出 ${Number(out).toFixed(2)} kg` : '—';
  }
  if (isSingleUpstream.value) {
    const after = row.fields['after'];
    return after != null ? `产出 ${Number(after).toFixed(2)} kg` : '—';
  }
  if (isQuSheTou.value) {
    const out = row.fields['output'];
    return out != null ? `产出 ${Number(out).toFixed(2)} kg` : '—';
  }
  if (isShuZhi.value) {
    const out = row.fields['output'];
    return out != null ? `产出 ${Number(out).toFixed(2)} kg` : '—';
  }
  if (isQidiao.value) {
    const n = calcSumBoxes(row);
    return `实产 ${n} 盒`;
  }
  return '—';
}

// -------------------------------------------------------------------------
// Auto-calc helpers
// -------------------------------------------------------------------------
/** 反推投入量: scrap + output. 去舌苔专用. */
function calcReverseInput(row: SheetRow): number | null {
  const scrap = (row.fields['scrap'] as number) ?? null;
  const output = (row.fields['output'] as number) ?? null;
  if (scrap == null || output == null) return null;
  return scrap + output;
}

function calcYield(row: SheetRow): number | null {
  let input: number | null = null;
  let output: number | null = null;

  if (isXiuYou.value) {
    input = row.rawBatchQty;
    output = (row.fields['output'] as number) ?? null;
  } else if (isSingleUpstream.value) {
    // 焯水 + 滚揉: before/after 字段
    input = (row.fields['before'] as number) ?? null;
    output = (row.fields['after'] as number) ?? null;
  } else if (isQuSheTou.value) {
    // 去舌苔: 分母是反推投入量 (scrap + output), 分子是 output
    input = calcReverseInput(row);
    output = (row.fields['output'] as number) ?? null;
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
  // Fallback for single-upstream / qushetou unsaved rows: derive from upstream usage.
  if (isSingleUpstream.value || isQuSheTou.value) {
    if (row.upstreamFinishedGoods) {
      const fg = fgOptions.value.find((f) => f.batchNumber === row.upstreamBatch);
      return fg ? fgAvailable(fg) : null;
    }
    if (row.upstreamSemiFinished) {
      const sfi = sfiOptions.value.find((s) => s.intermediateBatchNo === row.upstreamBatch);
      return sfi ? sfiAvailable(sfi) : null;
    }
    const inv = props.upstreamItems.find((b) => b.batchNumber === row.upstreamBatch);
    return inv ? inv.remaining : null;
  }
  return null;
}

// -------------------------------------------------------------------------
// 气调 AutoCalc helpers (SP-G G3b)
// -------------------------------------------------------------------------

/** 实际生产(盒) = 入库 + 留样 + 剩余 + 领用 */
function calcSumBoxes(row: SheetRow): number {
  return ((row.fields['storage'] as number) || 0)
    + ((row.fields['sample'] as number) || 0)
    + ((row.fields['remainBox'] as number) || 0)
    + ((row.fields['claim'] as number) || 0);
}

/** 总重量(kg) = 成品重 + 料头 */
function calcSumWeight(row: SheetRow): number {
  return ((row.fields['productWeight'] as number) || 0)
    + ((row.fields['trimmings'] as number) || 0);
}

/** 出成率(%) = 成品重 / 使用重量 × 100 */
function calcYieldByProductWeight(row: SheetRow): number | null {
  const usedWeight = (row.fields['usedWeight'] as number) ?? 0;
  const productWeight = (row.fields['productWeight'] as number) ?? 0;
  if (!usedWeight) return null;
  return Math.round((productWeight / usedWeight) * 10000) / 100;
}

/** Per-box labor preview = worker-hours * hourly rate * box kg / allocated kg. */
function calcLaborPerBox(row: SheetRow): number | null {
  const productWeight = (row.fields['productWeight'] as number) ?? 0;
  const totalWeight = calcSumWeight(row);
  const actualBoxes = calcSumBoxes(row);
  const boxWeightGrams = (row.fields['boxWeight'] as number) ?? null;
  const estimatedWeightFromBoxes = boxWeightGrams && actualBoxes
    ? (boxWeightGrams / 1000) * actualBoxes
    : null;

  return calculateLaborPerBox({
    hourlyRate: (row.fields['workerPrice'] as number) ?? null,
    totalHours: calcTotalHours(row),
    boxWeightGrams,
    allocationWeightKg: productWeight || totalWeight || estimatedWeightFromBoxes,
    actualBoxes,
  });
}

function upstreamWarning(row: SheetRow): string | null {
  if (isSingleUpstream.value) {
    // 焯水 + 滚揉: 用量 = before
    const usage = (row.fields['before'] as number) ?? 0;
    if (row.upstreamFinishedGoods) {
      // ①c FG 投料 max 守卫: 计数单位(盒装)成品经每盒克重折算 kg 可投量; kg 源按原 kg 比较。
      const fg = fgOptions.value.find((f) => f.batchNumber === row.upstreamBatch);
      if (!fg) return null;
      if (isCountUnit(fg.unit)) return countUnitFeedWarning(fg.unit, fg.gramsPerUnit, fgAvailable(fg), usage, '该成品来源');
      if (usage > fgAvailable(fg)) return `用量 ${usage}kg 超出成品库存余 ${fgAvailable(fg)}${fg.unit || 'kg'}`;
      return null;
    }
    if (row.upstreamSemiFinished) {
      // SFI 投料 max 守卫: 计数单位(盒装)半成品经每盒克重折算; kg 源按原 kg 比较。
      const sfi = sfiOptions.value.find((s) => s.intermediateBatchNo === row.upstreamBatch);
      if (!sfi) return null;
      if (isCountUnit(sfi.unit)) return countUnitFeedWarning(sfi.unit, sfi.gramsPerUnit, sfiAvailable(sfi), usage, '该半成品来源');
      if (usage > sfiAvailable(sfi)) return `用量 ${usage}kg 超出半成品库存余 ${sfiAvailable(sfi)}${sfi.unit || 'kg'}`;
      return null;
    }
    const inv = props.upstreamItems.find((b) => b.batchNumber === row.upstreamBatch);
    if (!inv) return null;
    if (usage > inv.remaining) return `用量 ${usage}kg 超出剩余 ${inv.remaining}kg`;
  }
  if (isQuSheTou.value) {
    // 去舌苔: 用量 = 投入量 = scrap + output
    const usage = calcReverseInput(row) ?? 0;
    if (row.upstreamFinishedGoods) {
      const fg = fgOptions.value.find((f) => f.batchNumber === row.upstreamBatch);
      if (!fg) return null;
      if (isCountUnit(fg.unit)) return countUnitFeedWarning(fg.unit, fg.gramsPerUnit, fgAvailable(fg), usage, '该成品来源');
      if (usage > fgAvailable(fg)) return `用量 ${usage}kg 超出成品库存余 ${fgAvailable(fg)}${fg.unit || 'kg'}`;
      return null;
    }
    if (row.upstreamSemiFinished) {
      const sfi = sfiOptions.value.find((s) => s.intermediateBatchNo === row.upstreamBatch);
      if (!sfi) return null;
      if (isCountUnit(sfi.unit)) return countUnitFeedWarning(sfi.unit, sfi.gramsPerUnit, sfiAvailable(sfi), usage, '该半成品来源');
      if (usage > sfiAvailable(sfi)) return `用量 ${usage}kg 超出半成品库存余 ${sfiAvailable(sfi)}${sfi.unit || 'kg'}`;
      return null;
    }
    const inv = props.upstreamItems.find((b) => b.batchNumber === row.upstreamBatch);
    if (!inv) return null;
    if (usage > inv.remaining) return `用量 ${usage}kg 超出剩余 ${inv.remaining}kg`;
  }
  if (isShuZhi.value || isQidiao.value) {
    const warnings: string[] = [];
    for (const src of row.upstreamSources) {
      const usage = src.feedQuantityKg;
      if (src.finishedGoods) {
        // ①c FG 投料 max 守卫: 计数单位(盒装)成品经每盒克重折算 kg 可投量; kg 源按原 kg 比较。
        const fg = fgOptions.value.find((f) => f.batchNumber === src.sourceBatchNumber);
        if (!fg) continue;
        if (isCountUnit(fg.unit)) {
          const w = countUnitFeedWarning(fg.unit, fg.gramsPerUnit, fgAvailable(fg), usage, src.sourceBatchNumber);
          if (w) warnings.push(w);
          continue;
        }
        if (usage > fgAvailable(fg)) {
          warnings.push(`${src.sourceBatchNumber} 超出成品库存余 ${fgAvailable(fg)}${fg.unit || 'kg'}`);
        }
        continue;
      }
      if (src.semiFinished) {
        // SFI 投料 max 守卫: 计数单位(盒装)半成品经每盒克重折算; kg 源按原 kg 比较。
        const sfi = sfiOptions.value.find((s) => s.intermediateBatchNo === src.sourceBatchNumber);
        if (!sfi) continue;
        if (isCountUnit(sfi.unit)) {
          const w = countUnitFeedWarning(sfi.unit, sfi.gramsPerUnit, sfiAvailable(sfi), usage, src.sourceBatchNumber);
          if (w) warnings.push(w);
          continue;
        }
        if (usage > sfiAvailable(sfi)) {
          warnings.push(`${src.sourceBatchNumber} 超出半成品库存余 ${sfiAvailable(sfi)}${sfi.unit || 'kg'}`);
        }
        continue;
      }
      const inv = props.upstreamItems.find((b) => b.batchNumber === src.sourceBatchNumber);
      if (inv && usage > inv.remaining) {
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
  } else if (isSingleUpstream.value) {
    // 焯水 + 滚揉: before/after 校验 — label 用真实工序名 (Bug 1 修复, 不再按 archetype 硬编码)
    const processLabel = ownProcessName.value;
    const upstreamLabel = `${upstreamProcessName.value}批次`;
    if (!row.upstreamBatch) return `请选择${upstreamLabel}`;
    if ((row.fields['before'] as number) == null) return `请填写${processLabel}前重量`;
    if ((row.fields['after'] as number) == null) return `请填写${processLabel}后重量`;
  } else if (isQuSheTou.value) {
    if (!row.upstreamBatch) return `请选择${upstreamProcessName.value}批次`;
    if ((row.fields['scrap'] as number) == null) return '请填写碎肉重量';
    if ((row.fields['output'] as number) == null) return '请填写产出重量';
  } else if (isShuZhi.value) {
    if (row.upstreamSources.length === 0) return '请添加上游来源批';
    if (row.upstreamSources.some((s) => !s.sourceBatchNumber || !s.feedQuantityKg)) return '请补全所有来源批次及投料量';
    if ((row.fields['output'] as number) == null) return '请填写产出数量';
    if (row.potCount > 1) {
      const filled = row.potRawKgs.filter((v) => v != null && v > 0);
      if (filled.length < row.potCount) return `请填写所有 ${row.potCount} 锅的原料重量`;
    }
  } else if (isQidiao.value) {
    if (row.upstreamSources.length === 0) return `请添加${upstreamProcessName.value}来源批`;
    if (row.upstreamSources.some((s) => !s.sourceBatchNumber || !s.feedQuantityKg)) return '请补全所有来源批次及投料量';
    if ((row.fields['usedWeight'] as number) == null) return '请填写使用重量';
    if (calcSumBoxes(row) <= 0) return '请填写入库/留样/剩余/领用至少一项(实际生产需>0)';
  }
  // 🔒 防呆 Rule 1 — 超投/单位不匹配 must-fix 不是 advisory: upstreamWarning() 命中时同样 disable 保存,
  // 不能只在提示区显示警告文字却仍放行保存(2026-07-02 GATE-HANDBACK 阻断项②)。
  const w = upstreamWarning(row);
  if (w) return w;
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
    finished: props.processCode === 'qidiao', // ⭐ 气调成品批
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

  // 跨天: 取本工序日期列(daterange)的开始日作 processDate →
  // 后端把成本报工(SEASONING/LABOR)归到该工序真实操作日 (非录入当天)。
  const dateCol = cols.value.find((c) => c.type === 'daterange' || c.type === 'date');
  if (dateCol) {
    const v = row.fields[dateCol.key];
    const start = Array.isArray(v) ? v[0] : (typeof v === 'string' ? v : null);
    if (start) base.processDate = start;
  }

  if (isXiuYou.value) {
    base.rawMaterialInputs = [{ materialBatchId: row.rawBatchId, quantity: row.rawBatchQty! }];
    base.inputQuantity = row.rawBatchQty ?? undefined;
    base.outputQuantity = (row.fields['output'] as number) ?? 0;
    base.unit = 'kg';
    // SP-G G3c: 副产 (修油 — 肥油等)
    const bpQty = (row.fields['byproductQty'] as number) ?? 0;
    if (bpQty > 0) {
      const bpPrice = (row.fields['byproductPrice'] as number) ?? undefined;
      base.byproducts = [{ name: '副产', quantity: bpQty, unit: 'kg', ...(bpPrice != null ? { unitPrice: bpPrice } : {}) }];
    }
  } else if (isSingleUpstream.value) {
    // 焯水 + 滚揉: 结构相同. feedQuantityKg = before (领用量 = 投入量).
    // semiFinished: 单上游选了半成品库存 (SFI) → 后端 ③=F 纯 SFI 路径 (SAVED_SFI, 小结 SFI in/out)。
    // finishedGoods (①c): 单上游选了成品库存 (FG) → 后端小结 consumeForFeedStrict 严格扣减成品。
    base.upstreamSources = [{ sourceBatchNumber: row.upstreamBatch, feedQuantityKg: (row.fields['before'] as number) ?? 0, semiFinished: row.upstreamSemiFinished, finishedGoods: row.upstreamFinishedGoods }];
    base.inputQuantity = (row.fields['before'] as number) ?? undefined;
    base.outputQuantity = (row.fields['after'] as number) ?? 0;
    base.unit = 'kg';
    // SP-G G3c: 副产 (焯水/滚揉 — 肥油等)
    const bpQty = (row.fields['byproductQty'] as number) ?? 0;
    if (bpQty > 0) {
      const bpPrice = (row.fields['byproductPrice'] as number) ?? undefined;
      base.byproducts = [{ name: '副产', quantity: bpQty, unit: 'kg', ...(bpPrice != null ? { unitPrice: bpPrice } : {}) }];
    }
  } else if (isQuSheTou.value) {
    // 去舌苔: inputQuantity = scrap + output (反推); feedQuantityKg = 同 inputQuantity.
    const output = (row.fields['output'] as number) ?? 0;
    const scrap = (row.fields['scrap'] as number) ?? 0;
    const inputQty = scrap + output;
    // semiFinished: 去舌苔单上游选了半成品库存 (SFI) → 后端 ③=F 纯 SFI 路径。
    // finishedGoods (①c): 单上游选了成品库存 (FG) → 小结严格扣减成品。
    base.upstreamSources = [{ sourceBatchNumber: row.upstreamBatch, feedQuantityKg: inputQty, semiFinished: row.upstreamSemiFinished, finishedGoods: row.upstreamFinishedGoods }];
    base.inputQuantity = inputQty;
    base.outputQuantity = output;
    base.unit = 'kg';
  } else if (isShuZhi.value) {
    base.upstreamSources = row.upstreamSources;
    const totalFeed = row.upstreamSources.reduce((s, r) => s + (r.feedQuantityKg || 0), 0);
    base.inputQuantity = (row.fields['input'] as number) ?? totalFeed;
    base.outputQuantity = (row.fields['output'] as number) ?? 0;
    base.unit = 'kg';
  } else if (isQidiao.value) {
    // ⭐ outputQuantity = 盒数 (actualProd), NOT kg
    const actualProd = calcSumBoxes(row);
    base.upstreamSources = row.upstreamSources;
    base.inputQuantity = (row.fields['usedWeight'] as number) ?? undefined;
    base.outputQuantity = actualProd;            // ⭐⭐ 盒数!
    base.unit = '盒';
    // 副产品: 料头
    const trimmings = (row.fields['trimmings'] as number) ?? 0;
    if (trimmings > 0) {
      base.byproducts = [{ name: '料头', quantity: trimmings, unit: 'kg' }];
    }
    // 留样盒数 (Integer)
    const sample = (row.fields['sample'] as number) ?? 0;
    if (sample > 0) {
      base.sampleRetainQuantity = Math.round(sample);
    }
    // Persist numeric fields into payload JSON (backend stores row_payload)
    for (const key of ['storage', 'sample', 'remainBox', 'claim', 'productWeight', 'trimmings', 'usedWeight', 'boxWeight', 'workerPrice'] as const) {
      base[key] = row.fields[key] ?? null;
    }
  }
  return base;
}

// -------------------------------------------------------------------------
// Save / delete handlers
// -------------------------------------------------------------------------
async function handleSave(row: SheetRow) {
  if (row.saving) return;
  const reason = saveDisabledReason(row);
  if (reason) {
    // 防呆 4位一体: type:error (非 warning) + sticky (duration:0) + showClose + next-action 明示
    ElMessage({ message: reason, type: 'error', duration: 0, showClose: true });
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
    // 并发双提交/慢响应重试: 行已被首个请求保存成功(后端 409 + 回滚 loser), 幂等当成功处理,
    // 不给用户看错误 (fool-proof Rule 4 幂等防重复)。刷新本行状态即可。
    if (/该行已存在|并发提交/.test(msg)) {
      row.rowStatus = 'SAVED';
      ElMessage.success('已保存');
      emit('row-saved');
    } else {
      ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
    }
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
// SP-G P3: 操作记录 (行级 diff 时间线)
// -------------------------------------------------------------------------
const historyVisible = ref(false);
const historyLoading = ref(false);
const historyRows = ref<ProcessSheetRowHistoryView[]>([]);
const historyBatchLabel = ref('');

const OP_LABEL: Record<string, string> = { CREATE: '新建', UPDATE: '修改', DELETE: '删除' };
const OP_TYPE: Record<string, 'success' | 'warning' | 'danger'> = {
  CREATE: 'success', UPDATE: 'warning', DELETE: 'danger',
};

/** 仅已保存过的行 (SAVED/DRAFT) 才有服务端操作记录; UNSAVED 行无历史。 */
function hasHistory(row: SheetRow): boolean {
  return row.rowStatus === 'SAVED' || row.rowStatus === 'DRAFT';
}

async function openHistory(row: SheetRow) {
  historyVisible.value = true;
  historyLoading.value = true;
  historyRows.value = [];
  historyBatchLabel.value = row.batchNumber || '(未生成批次号)';
  try {
    const resp = await getRowHistory(props.factoryId, props.planId, props.processCode, row.clientRowId);
    historyRows.value = resp.data || [];
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '加载操作记录失败';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    historyLoading.value = false;
  }
}

function formatHistoryTime(iso: string): string {
  if (!iso) return '';
  // Trim to "YYYY-MM-DD HH:mm:ss" from ISO datetime (handles trailing micros).
  return iso.replace('T', ' ').slice(0, 19);
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
// 半成品库存 (SFI) 投料来源 — 混锅可选常驻半成品 (半成品直接产成品)
// 仅 熟制 / 气调 (多来源混锅) 工序提供 SFI 选项; 其余工序不加载。
// -------------------------------------------------------------------------
const sfiOptions = ref<SemiFinishedStockItem[]>([]);
const sfiLoading = ref(false);
let sfiLoadSeq = 0;

// ①c 成品库存 (FG) 投料来源 — 与 SFI 平行 (07-01 客户: 选批次看到库里所有成品和半成品)。
const fgOptions = ref<FinishedGoodsStockItem[]>([]);
const fgLoading = ref(false);
let fgLoadSeq = 0;

/** 是否多来源混锅工序 (熟制 / 气调) — 多源混锅提供 SFI 投料选项。 */
const isMultiSource = computed(() => isShuZhi.value || isQidiao.value);

/**
 * 是否提供「半成品库存(SFI)」投料选项。
 * 原仅限混锅道 (熟制/气调); 现扩到单上游道 (焯水/滚揉/去舌苔) —— 混批原料可选半成品库存
 * 不再限混锅道, 支持"从滚揉起步选半成品" (链起步道 upstreamItems 空, 仅 SFI 可选)。
 */
const showSfi = computed(() => isMultiSource.value || isSingleUpstream.value || isQuSheTou.value);
/** ①c 是否提供「成品库存(FG)」投料选项 (与 SFI 同工序集: 混锅 + 单上游道)。 */
const showFg = computed(() => showSfi.value);

// 下拉选项用「类型::批次号」复合值, 让来源类型由选中的 OPTION 显式携带, 而非按字符串值反查
// (规避 in-plan WIP 批号与 SFI/FG 批号偶然相同时的误判)。
const SRC_WIP = 'wip';
const SRC_SFI = 'sfi';
const SRC_FG = 'fg';   // ①c 成品库存
function srcKey(kind: string, batchNo: string): string {
  return `${kind}::${batchNo}`;
}

function sfiAvailable(item: SemiFinishedStockItem): number {
  return Number(item.availableQuantity ?? 0) || 0;
}

function fgAvailable(item: FinishedGoodsStockItem): number {
  return Number(item.availableQuantity ?? 0) || 0;
}

/** ② 成本文字: 有值 → "成本{n}"; null → "成本未知" (诚实, 不显 ¥0)。 */
function costText(cost: number | null | undefined): string {
  return cost != null ? `成本${Number(cost)}` : '成本未知';
}

/** ② 日期文字: null → '无生产日期' 占位 (下拉对齐)。 */
function dateText(d: string | null | undefined): string {
  return d ? String(d).slice(0, 10) : '无生产日期';
}

/**
 * ② 在制 WIP 选项标签: "{品名} | {批号} | {生产日期} | 余{remaining}kg | {成本}"。
 * 品名/生产日期由后端 getInventory 回填 (缺失时降级只显批号+余量, 不 crash)。
 */
function wipLabel(item: ProcessSheetInventoryItem): string {
  const name = item.productTypeName || '在制';
  const parts = [name, item.batchNumber, dateText(item.productionDate),
    `余${item.remaining}kg`, costText(item.unitPrice)];
  return parts.join(' | ');
}

/** ② 半成品库存选项标签: "半成品: {品名} | {批号} | {生产日期} | 余{available}{unit} | {成本}"。 */
function sfiLabel(item: SemiFinishedStockItem): string {
  const name = item.productTypeName || item.processName || '半成品';
  const unit = item.unit || 'kg';
  // 盒装半成品作 kg 道投料来源时展示折算 (余 N 盒 ≈ M kg) 或缺克重警告 (防呆 pre-display)。
  const conv = countUnitLabelSuffix(item.unit, item.gramsPerUnit, sfiAvailable(item));
  return `半成品: ${name} | ${item.intermediateBatchNo} | ${dateText(item.productionDate)} `
    + `| 余${sfiAvailable(item)}${unit}${conv} | ${costText(item.unitCost)}`;
}

/** ②/①c 成品库存选项标签: "成品: {品名} | {批号} | {生产日期} | 余{available}{unit} | {成本}"。 */
function fgLabel(item: FinishedGoodsStockItem): string {
  const name = item.productTypeName || '成品';
  const unit = item.unit || 'kg';
  // 盒装成品作 kg 道投料来源时展示折算 (余 N 盒 ≈ M kg) 或缺克重警告 (防呆 pre-display)。
  const conv = countUnitLabelSuffix(item.unit, item.gramsPerUnit, fgAvailable(item));
  return `成品: ${name} | ${item.batchNumber} | ${dateText(item.productionDate)} `
    + `| 余${fgAvailable(item)}${unit}${conv} | ${costText(item.unitCost)}`;
}

/** ①c 加载可投料成品库存 (产品族过滤; 成品是终态无阶段过滤)。 */
async function loadFgOptions() {
  if (!showFg.value || !props.factoryId) return;
  const seq = ++fgLoadSeq;
  fgLoading.value = true;
  try {
    // 防呆过滤 (07-01): 传当前计划 productTypeId → 后端解析成产品族仅返回同族成品 (猪蹄计划不显牛肉)。
    const resp = await getFinishedGoodsInventory(props.factoryId, props.productTypeId);
    if (seq !== fgLoadSeq) return;
    const all = Array.isArray(resp.data) ? resp.data : [];
    fgOptions.value = all.filter((f) => fgAvailable(f) > 0);
  } catch (err) {
    if (seq !== fgLoadSeq) return;
    fgOptions.value = [];
    // 非阻断: FG 投料是增量能力, 加载失败仅丢失该选项, 不影响 in-plan WIP / SFI 投料。
    ElMessage({ message: err instanceof Error ? err.message : '成品库存加载失败', type: 'warning', duration: 3000 });
  } finally {
    if (seq === fgLoadSeq) fgLoading.value = false;
  }
}

async function loadSfiOptions() {
  if (!showSfi.value || !props.factoryId) return;
  const seq = ++sfiLoadSeq;
  sfiLoading.value = true;
  try {
    // 防呆过滤 (07-01 客户会议): 只列同族产品(猪蹄族不显牛肉) + 对应阶段(防回锅) 的半成品。
    //   同族: 传当前计划 productTypeId → 后端解析成"产品族"(以原料为主自动识别) 仅返回同族半成品。
    //         不是按 productTypeId 精确匹配 — 熟制前半成品在同族内通用 (兄弟猪蹄成品共用"猪蹄"半成品),
    //         故猪蹄计划能看到所有猪蹄族半成品, 但不显牛肉。
    //   阶段: 仅当本道 processOrder 为正整数时传 → 后端仅返回更早阶段 (processOrder < 本道)。
    //         processOrder 缺失/为0 (链起步/未配) → 省略, 只按同族过滤 (务实: 不因缺阶段信息而清空可选项)。
    const filter: SemiFinishedInventoryFilter = { productTypeId: props.productTypeId };
    if (typeof props.processOrder === 'number' && props.processOrder > 0) {
      filter.maxProcessOrder = props.processOrder;
    }
    const resp = await getSemiFinishedInventory(props.factoryId, filter);
    if (seq !== sfiLoadSeq) return;
    const all = Array.isArray(resp.data) ? resp.data : [];
    sfiOptions.value = all.filter((s) => sfiAvailable(s) > 0);
  } catch (err) {
    if (seq !== sfiLoadSeq) return;
    sfiOptions.value = [];
    // 非阻断: SFI 投料是增量能力, 加载失败仅丢失该选项, 不影响 in-plan 在制 WIP 投料。
    ElMessage({ message: err instanceof Error ? err.message : '半成品库存加载失败', type: 'warning', duration: 3000 });
  } finally {
    if (seq === sfiLoadSeq) sfiLoading.value = false;
  }
}

// -------------------------------------------------------------------------
// Bug 3 (2026-07 现场走查): 来源批次下拉默认列出同族全部批次 (即便已按产品族/阶段
// 过滤, 大厂仍可能有几百条同族在库批次), 低文化素质操作员被淹没。
// 方案: 未搜索时只显示按生产日期倒序最近 N 条 (最可能用到的排前面); 打字搜索时
// 对全量按下拉展示文案(品名/批号/日期)子串过滤。WIP(本计划在制半成品)本就计划域
// 内很小, 不裁剪数量, 但同样纳入统一查询词过滤 (自定义 filter-method 会关闭 el-select
// 原生过滤, 三组都要手动过滤, 否则 WIP 组会变成"打字也不过滤"的倒退)。
// 多个 el-select 实例(每行/每来源一个)共享同一个查询词 ref; 下拉打开时重置, 避免上一个
// 下拉的过滤态串到下一个实例。
// -------------------------------------------------------------------------
const BATCH_OPTION_DEFAULT_LIMIT = 30;
const batchSearchQuery = ref('');

function sortByRecentDesc<T extends { productionDate?: string | null }>(items: T[]): T[] {
  return [...items].sort((a, b) => (b.productionDate || '').localeCompare(a.productionDate || ''));
}

const wipOptionsDisplay = computed(() => {
  const q = batchSearchQuery.value.trim().toLowerCase();
  if (!q) return props.upstreamItems;
  return props.upstreamItems.filter((item) => wipLabel(item).toLowerCase().includes(q));
});
const sfiOptionsSorted = computed(() => sortByRecentDesc(sfiOptions.value));
const sfiOptionsDisplay = computed(() => {
  const q = batchSearchQuery.value.trim().toLowerCase();
  if (!q) return sfiOptionsSorted.value.slice(0, BATCH_OPTION_DEFAULT_LIMIT);
  return sfiOptionsSorted.value.filter((s) => sfiLabel(s).toLowerCase().includes(q));
});
const fgOptionsSorted = computed(() => sortByRecentDesc(fgOptions.value));
const fgOptionsDisplay = computed(() => {
  const q = batchSearchQuery.value.trim().toLowerCase();
  if (!q) return fgOptionsSorted.value.slice(0, BATCH_OPTION_DEFAULT_LIMIT);
  return fgOptionsSorted.value.filter((f) => fgLabel(f).toLowerCase().includes(q));
});
/** 组标题显示 "总数/已限N条", 让用户知道还有更多、该打字搜索 (Rule 1: 预先显示边界)。 */
const sfiGroupLabel = computed(() => sfiOptions.value.length > sfiOptionsDisplay.value.length
  ? `半成品库存 (可直接产成品, 显示最近${BATCH_OPTION_DEFAULT_LIMIT}/共${sfiOptions.value.length}条, 可搜索品名/批号)`
  : '半成品库存 (可直接产成品)');
const fgGroupLabel = computed(() => fgOptions.value.length > fgOptionsDisplay.value.length
  ? `成品库存 (可直接产成品, 显示最近${BATCH_OPTION_DEFAULT_LIMIT}/共${fgOptions.value.length}条, 可搜索品名/批号)`
  : '成品库存 (可直接产成品)');

function onBatchSelectFilter(query: string) {
  batchSearchQuery.value = query;
}
function onBatchSelectVisibleChange(visible: boolean) {
  if (visible) batchSearchQuery.value = '';
}

/**
 * 当前来源行的复合下拉值 (从已存的 src 反推, 供 :model-value 显示选中项)。
 * semiFinished 标记由 src 自身携带 (保存往返保留), 不按字符串值反查 → 无 WIP↔SFI 碰撞。
 */
function srcSelectKey(src: UpstreamRef): string {
  if (!src.sourceBatchNumber) return '';
  const kind = src.finishedGoods ? SRC_FG : (src.semiFinished ? SRC_SFI : SRC_WIP);
  return srcKey(kind, src.sourceBatchNumber);
}

/**
 * 用户在混锅来源下拉改选后: 由选中 OPTION 的复合值「类型::批次号」显式拆出来源类型 (wip/sfi/fg) 与批次号,
 * 据此置 sourceBatchNumber + semiFinished + finishedGoods (三者互斥)。后端据这两个标记决定保存时是否写消耗边
 * + 小结时走严格 SFI/FG 出库 (consumeClerkSemiStrict / consumeForFeedStrict) 还是 in-plan 在制 WIP 边。
 */
function onUpstreamSelect(src: UpstreamRef, key: string | null | undefined) {
  if (!key) {
    src.sourceBatchNumber = '';
    src.semiFinished = false;
    src.finishedGoods = false;
    return;
  }
  const sep = key.indexOf('::');
  if (sep < 0) {
    // 兜底 (理论不出现): 无前缀 → 当 in-plan WIP
    src.sourceBatchNumber = key;
    src.semiFinished = false;
    src.finishedGoods = false;
    return;
  }
  const kind = key.slice(0, sep);
  src.semiFinished = kind === SRC_SFI;
  src.finishedGoods = kind === SRC_FG;
  src.sourceBatchNumber = key.slice(sep + 2);
}

// -------------------------------------------------------------------------
// 单上游道 (焯水/滚揉/去舌苔) — WIP + SFI 复合值选择 (镜像混锅的 wip::/sfi:: 方案)
// upstreamBatch (单字符串) 无法自身区分 WIP↔SFI, 故 semiFinished 由选中 OPTION 显式携带,
// 单独存 row.upstreamSemiFinished, 规避 WIP 批号与 SFI 批号偶然相同的误判。
// -------------------------------------------------------------------------

/** 单上游当前选中的复合下拉值 (从 upstreamBatch + upstreamSemiFinished + upstreamFinishedGoods 反推)。 */
function singleUpstreamSelectKey(row: SheetRow): string {
  if (!row.upstreamBatch) return '';
  const kind = row.upstreamFinishedGoods ? SRC_FG : (row.upstreamSemiFinished ? SRC_SFI : SRC_WIP);
  return srcKey(kind, row.upstreamBatch);
}

/** 用户改选单上游: 由复合值「类型::批次号」显式拆出批次号 + semiFinished + finishedGoods 标记 (三者互斥)。 */
function onSingleUpstreamSelect(row: SheetRow, key: string | null | undefined) {
  if (!key) {
    row.upstreamBatch = '';
    row.upstreamSemiFinished = false;
    row.upstreamFinishedGoods = false;
    return;
  }
  const sep = key.indexOf('::');
  if (sep < 0) {
    // 兜底 (理论不出现): 无前缀 → 当 in-plan WIP
    row.upstreamBatch = key;
    row.upstreamSemiFinished = false;
    row.upstreamFinishedGoods = false;
    return;
  }
  const kind = key.slice(0, sep);
  row.upstreamSemiFinished = kind === SRC_SFI;
  row.upstreamFinishedGoods = kind === SRC_FG;
  row.upstreamBatch = key.slice(sep + 2);
}

/** 来源批余量提示文字 (兼顾 in-plan 在制 WIP 与常驻 SFI / 成品FG)。 */
function srcRemainingLabel(src: UpstreamRef): string {
  const wip = props.upstreamItems.find((b) => b.batchNumber === src.sourceBatchNumber);
  if (wip) return `余${wip.remaining}kg`;
  const fg = fgOptions.value.find((f) => f.batchNumber === src.sourceBatchNumber);
  if (fg) return `成品余${fgAvailable(fg)}${fg.unit || 'kg'}`;
  const sfi = sfiOptions.value.find((s) => s.intermediateBatchNo === src.sourceBatchNumber);
  if (sfi) return `半成品余${sfiAvailable(sfi)}${sfi.unit || 'kg'}`;
  return '';
}

// -------------------------------------------------------------------------
// 熟制: pot helpers
// -------------------------------------------------------------------------
function onPotCountChange(row: SheetRow, val: number) {
  row.potCount = val;
  row.potRawKgs = Array.from({ length: val }, (_, i) => row.potRawKgs[i] ?? null);
}

watch(
  () => [props.factoryId, props.processCode, props.productTypeId] as const,
  () => {
    rawBatchOptions.value = [];
    consumableWarehouseIds.value = [];
    rawBatchLoadSeq++;
    rawBatchLoading.value = false;
    if (isXiuYou.value) void loadRawBatches();
    // 混锅工序 (熟制/气调) + 单上游道 (焯水/滚揉/去舌苔) 加载常驻半成品库存供 SFI 投料下拉
    sfiOptions.value = [];
    sfiLoadSeq++;
    sfiLoading.value = false;
    if (showSfi.value) void loadSfiOptions();
    // ①c 同工序集加载常驻成品库存供 FG 投料下拉
    fgOptions.value = [];
    fgLoadSeq++;
    fgLoading.value = false;
    if (showFg.value) void loadFgOptions();
  },
  { immediate: true },
);

// -------------------------------------------------------------------------
// 未保存草稿检测 (防呆 Rule: 关闭「逐工序录入」抽屉前警示未保存内容)
// -------------------------------------------------------------------------
// rowStatus === 'UNSAVED' 只在用户主动「+新增」加了一行且还没保存成功时出现
// (初始加载/hydrate 来的行是 SAVED/DRAFT, 空表不会自动插一行占位)，可直接当
// "本工序有未保存草稿行" 的信号，供父组件(ProcessSheet → list.vue 抽屉)聚合。
const hasUnsavedRows = computed(() => rows.value.some((r) => r.rowStatus === 'UNSAVED'));

defineExpose({ hasUnsavedRows });
</script>

<template>
  <div class="sp-grid-wrap">

    <!-- ====================================================================
         CARD LAYOUT
         One card per row. Same row model + editors as the grid, different
         visual wrapper. Expandable labor / mix sections rendered inline.
         ==================================================================== -->
    <template v-if="viewMode === 'card'">

      <!-- ====== 已小结区块 (默认折叠，点击展开只读历史行) ====== -->
      <div v-if="settledRows.length > 0" class="sp-settled-section">
        <div class="sp-settled-header" @click="settledExpanded = !settledExpanded">
          <el-icon style="margin-right:4px"><component :is="settledExpanded ? ArrowDown : ArrowRight" /></el-icon>
          <span>已小结 {{ settledRows.length }} 道（已转结到生产批次，计划保持开放）</span>
          <el-tag type="info" size="small" style="margin-left:8px">只读</el-tag>
        </div>
        <template v-if="settledExpanded">
          <div v-for="row in settledRows" :key="row.clientRowId" class="sp-card sp-card-settled">
            <div class="sp-card-header">
              <el-tag type="info" size="small" style="white-space:nowrap">已小结</el-tag>
              <span v-if="row.batchNumber" class="sp-card-batchnum">{{ row.batchNumber }}</span>
              <span class="sp-settled-summary">{{ settledRowSummary(row) }}</span>
              <span v-if="row.interimSettledAt" class="sp-settled-ts">
                小结于 {{ formatSettledAt(row.interimSettledAt) }}
              </span>
              <div style="flex:1" />
              <el-button
                v-if="hasHistory(row)"
                link size="small" :icon="Clock"
                title="操作记录"
                @click="openHistory(row)" />
            </div>
          </div>
        </template>
      </div>

      <div v-for="(row, ri) in activeRows" :key="row.clientRowId" class="sp-card"
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
            :disabled="!!saveDisabledReason(row) || row.saving"
            :title="saveDisabledReason(row) || '保存此行'"
            @click="handleSave(row)"
            style="padding:3px 8px">保存</el-button>
          <el-button
            v-if="hasHistory(row)"
            link size="small" :icon="Clock"
            title="操作记录"
            @click="openHistory(row)"
            style="margin-left:4px" />
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
              <!-- 防呆: 未选批次时内联红色提示 (Rule 1 + 4位一体: 预先显示边界) -->
              <span v-if="!row.rawBatchId" style="display:block;margin-top:3px;font-size:11px;color:#f56c6c">
                请先选择原料批次，再保存此行
              </span>
              <!-- 防呆: 未落仓批次已从下拉隐藏, 显式提示原因 (Rule 5: 不做 dead-end) -->
              <span v-if="rawBatchExcludedNoWarehouseCount > 0" style="display:block;margin-top:3px;font-size:11px;color:#e6a23c">
                另有 {{ rawBatchExcludedNoWarehouseCount }} 个批次未落仓，无法领用（请先完成入库/领料）
              </span>
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

          <!-- 焯水 + 滚揉: single upstream dropdown (结构相同) — 含半成品库存(SFI)选项 -->
          <template v-else-if="isSingleUpstream">
            <div class="sp-card-field">
              <label class="sp-card-label">{{ upstreamProcessName }}批次</label>
              <el-select
                :model-value="singleUpstreamSelectKey(row)"
                @change="(v: string) => onSingleUpstreamSelect(row, v)"
                :placeholder="`选${upstreamProcessName}批次/半成品`"
                filterable clearable
                :filter-method="onBatchSelectFilter"
                @visible-change="onBatchSelectVisibleChange"
                :loading="sfiLoading"
                style="width:100%" size="small">
                <el-option-group v-if="wipOptionsDisplay.length" label="本计划在制半成品">
                  <el-option
                    v-for="item in wipOptionsDisplay" :key="item.batchNumber"
                    :label="wipLabel(item)"
                    :value="srcKey(SRC_WIP, item.batchNumber)"
                    :disabled="item.remaining <= 0" />
                </el-option-group>
                <el-option-group v-if="sfiOptionsDisplay.length" :label="sfiGroupLabel">
                  <el-option
                    v-for="s in sfiOptionsDisplay" :key="'sfi-' + s.intermediateBatchNo"
                    :label="sfiLabel(s)"
                    :value="srcKey(SRC_SFI, s.intermediateBatchNo)"
                    :disabled="sfiAvailable(s) <= 0" />
                </el-option-group>
                <el-option-group v-if="fgOptionsDisplay.length" :label="fgGroupLabel">
                  <el-option
                    v-for="f in fgOptionsDisplay" :key="'fg-' + f.batchNumber"
                    :label="fgLabel(f)"
                    :value="srcKey(SRC_FG, f.batchNumber)"
                    :disabled="fgAvailable(f) <= 0" />
                </el-option-group>
              </el-select>
            </div>
          </template>

          <!-- 去舌苔: single upstream dropdown — 含半成品库存(SFI)选项 -->
          <template v-else-if="isQuSheTou">
            <div class="sp-card-field">
              <label class="sp-card-label">{{ upstreamProcessName }}批次</label>
              <el-select
                :model-value="singleUpstreamSelectKey(row)"
                @change="(v: string) => onSingleUpstreamSelect(row, v)"
                :placeholder="`选${upstreamProcessName}批次/半成品`"
                filterable clearable
                :filter-method="onBatchSelectFilter"
                @visible-change="onBatchSelectVisibleChange"
                :loading="sfiLoading"
                style="width:100%" size="small">
                <el-option-group v-if="wipOptionsDisplay.length" label="本计划在制半成品">
                  <el-option
                    v-for="item in wipOptionsDisplay" :key="item.batchNumber"
                    :label="wipLabel(item)"
                    :value="srcKey(SRC_WIP, item.batchNumber)"
                    :disabled="item.remaining <= 0" />
                </el-option-group>
                <el-option-group v-if="sfiOptionsDisplay.length" :label="sfiGroupLabel">
                  <el-option
                    v-for="s in sfiOptionsDisplay" :key="'sfi-' + s.intermediateBatchNo"
                    :label="sfiLabel(s)"
                    :value="srcKey(SRC_SFI, s.intermediateBatchNo)"
                    :disabled="sfiAvailable(s) <= 0" />
                </el-option-group>
                <el-option-group v-if="fgOptionsDisplay.length" :label="fgGroupLabel">
                  <el-option
                    v-for="f in fgOptionsDisplay" :key="'fg-' + f.batchNumber"
                    :label="fgLabel(f)"
                    :value="srcKey(SRC_FG, f.batchNumber)"
                    :disabled="fgAvailable(f) <= 0" />
                </el-option-group>
              </el-select>
            </div>
          </template>

          <!-- 熟制 / 气调: multi-source expander -->
          <template v-else-if="isShuZhi || isQidiao">
            <div class="sp-card-field sp-card-field-full">
              <label class="sp-card-label">{{ upstreamProcessName }}来源(混锅)</label>
              <el-button link size="small" @click="row.mixExpanded = !row.mixExpanded" style="font-size:12px">
                <el-icon style="margin-right:3px"><component :is="row.mixExpanded ? ArrowDown : ArrowRight" /></el-icon>
                {{ row.upstreamSources.length === 0 ? '+ 来源批' : `${row.upstreamSources.length} 批 · ${row.upstreamSources.reduce((s,x) => s + (x.feedQuantityKg||0), 0).toFixed(1)}kg` }}
              </el-button>
            </div>
            <!-- Mix expanded inline -->
            <div v-if="row.mixExpanded" class="sp-card-field sp-card-field-full sp-card-expand-section">
              <div style="margin-bottom:6px;display:flex;align-items:center;gap:8px">
                <span style="font-size:12px;font-weight:600;color:#303133">{{ upstreamProcessName }}来源批 (混锅)</span>
                <el-button size="small" :icon="Plus" @click="addUpstreamSource(row)">+ 来源批</el-button>
              </div>
              <div v-for="(src, si) in row.upstreamSources" :key="si"
                   style="display:flex;align-items:center;gap:8px;margin-bottom:6px;flex-wrap:wrap">
                <el-select
                  :model-value="srcSelectKey(src)"
                  @change="(v: string) => onUpstreamSelect(src, v)"
                  :placeholder="`选${upstreamProcessName}批次`" filterable clearable
                  :filter-method="onBatchSelectFilter"
                  @visible-change="onBatchSelectVisibleChange"
                  :loading="sfiLoading"
                  style="width:220px" size="small">
                  <el-option-group label="本计划在制半成品">
                    <el-option
                      v-for="item in wipOptionsDisplay" :key="item.batchNumber"
                      :label="wipLabel(item)"
                      :value="srcKey(SRC_WIP, item.batchNumber)"
                      :disabled="item.remaining <= 0" />
                  </el-option-group>
                  <el-option-group v-if="sfiOptionsDisplay.length" :label="sfiGroupLabel">
                    <el-option
                      v-for="s in sfiOptionsDisplay" :key="'sfi-' + s.intermediateBatchNo"
                      :label="sfiLabel(s)"
                      :value="srcKey(SRC_SFI, s.intermediateBatchNo)"
                      :disabled="sfiAvailable(s) <= 0" />
                  </el-option-group>
                  <el-option-group v-if="fgOptionsDisplay.length" :label="fgGroupLabel">
                    <el-option
                      v-for="f in fgOptionsDisplay" :key="'fg-' + f.batchNumber"
                      :label="fgLabel(f)"
                      :value="srcKey(SRC_FG, f.batchNumber)"
                      :disabled="fgAvailable(f) <= 0" />
                  </el-option-group>
                </el-select>
                <el-input-number
                  v-model="src.feedQuantityKg"
                  :min="0" :precision="2"
                  placeholder="投料kg"
                  controls-position="right"
                  size="small" style="width:120px" />
                <span v-if="src.sourceBatchNumber" style="font-size:11px;color:#909399">
                  {{ srcRemainingLabel(src) }}
                </span>
                <el-button link type="danger" :icon="Delete" @click="removeUpstreamSource(row, si)" />
              </div>
              <div v-if="row.upstreamSources.length === 0" style="color:#909399;font-size:12px;margin:4px 0">
                暂无来源批，点击 + 来源批 添加
              </div>
              <!-- Pot count (熟制 only) -->
              <template v-if="isShuZhi">
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
              </template>
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

              <span v-else-if="col.type === 'auto' && col.autoCalc === 'reverseInput'" class="sp-readonly">
                {{ calcReverseInput(row) != null ? calcReverseInput(row)!.toFixed(2) + 'kg' : '—' }}
              </span>

              <span v-else-if="col.type === 'auto' && col.autoCalc === 'yield'" class="sp-readonly">
                {{ calcYield(row) != null ? calcYield(row)!.toFixed(2) + '%' : '—' }}
              </span>

              <span v-else-if="col.type === 'auto' && col.autoCalc === 'remaining'" class="sp-readonly"
                :style="{ color: calcRemaining(row) != null && calcRemaining(row)! <= 0 ? '#f56c6c' : undefined }">
                {{ calcRemaining(row) != null ? calcRemaining(row)!.toFixed(2) : '—' }}
              </span>

              <!-- totalHours shown in the labor expander below; skip inline -->
              <span v-else-if="col.type === 'auto' && col.autoCalc === 'totalHours'" />

              <!-- auto: sumBoxes (气调: 实际生产=入库+留样+剩余+领用) -->
              <span v-else-if="col.type === 'auto' && col.autoCalc === 'sumBoxes'" class="sp-readonly">
                {{ calcSumBoxes(row) }}
              </span>

              <!-- auto: sumWeight (气调: 总重量=成品重+料头) -->
              <span v-else-if="col.type === 'auto' && col.autoCalc === 'sumWeight'" class="sp-readonly">
                {{ calcSumWeight(row).toFixed(2) }}
              </span>

              <!-- auto: yieldByProductWeight (气调: 出成率=成品重/使用重量×100) -->
              <span v-else-if="col.type === 'auto' && col.autoCalc === 'yieldByProductWeight'" class="sp-readonly">
                {{ calcYieldByProductWeight(row) != null ? calcYieldByProductWeight(row)!.toFixed(2) + '%' : '—' }}
              </span>

              <!-- auto: laborPerBox (气调: 每盒人工费=工时单价×总工时/实际生产) -->
              <span v-else-if="col.type === 'auto' && col.autoCalc === 'laborPerBox'" class="sp-readonly">
                {{ calcLaborPerBox(row) != null ? '¥' + calcLaborPerBox(row)!.toFixed(4) : '—' }}
              </span>

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

            <!-- 焯水 + 滚揉: upstream single-select -->
            <template v-else-if="isSingleUpstream">
              <th class="sp-th">{{ upstreamProcessName }}批次</th>
            </template>

            <!-- 去舌苔: upstream single-select -->
            <template v-else-if="isQuSheTou">
              <th class="sp-th">{{ upstreamProcessName }}批次</th>
            </template>

            <!-- 熟制: multi-source (rendered as expander cell) -->
            <template v-else-if="isShuZhi">
              <th class="sp-th">{{ upstreamProcessName }}来源(混锅)</th>
            </template>

            <!-- 气调: multi-source (rendered as expander cell) -->
            <template v-else-if="isQidiao">
              <th class="sp-th">{{ upstreamProcessName }}来源(混锅)</th>
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

        <!-- ================================================================
             已小结区块 (BY_STOCK 小结后已转结行，默认折叠，只读显示)
             ================================================================ -->
        <tbody v-if="settledRows.length > 0">
          <!-- Banner row: 折叠/展开控制 -->
          <tr class="sp-settled-banner">
            <td :colspan="999">
              <el-button link size="small" @click="settledExpanded = !settledExpanded"
                         style="font-size:12px;padding:2px 4px">
                <el-icon style="margin-right:4px"><component :is="settledExpanded ? ArrowDown : ArrowRight" /></el-icon>
                已小结 {{ settledRows.length }} 道（已转结到生产批次，计划保持开放）
              </el-button>
            </td>
          </tr>
          <!-- Settled rows: compact read-only -->
          <template v-if="settledExpanded">
            <tr v-for="row in settledRows" :key="row.clientRowId" class="sp-tr sp-tr-settled">
              <td class="sp-td sp-td-status">
                <el-tag type="info" size="small" style="white-space:nowrap">已小结</el-tag>
              </td>
              <td class="sp-td" style="color:#606266;font-size:12px">
                {{ settledRowSummary(row) }}
              </td>
              <td class="sp-td sp-td-batch">
                <span class="sp-readonly sp-batch-num">{{ row.batchNumber || '—' }}</span>
              </td>
              <td class="sp-td" style="font-size:11px;color:#c0c4cc;white-space:nowrap">
                {{ formatSettledAt(row.interimSettledAt) }}
              </td>
              <!-- 已小结只读行: colspan=999 铺满剩余列, 不用 .sp-td-actions (sticky right:0
                   在跨列 colspan 单元格上会产生错位; 该按钮只是查看历史, 非防呆焦点)。 -->
              <td class="sp-td" style="text-align:center;white-space:nowrap" :colspan="999">
                <el-button
                  v-if="hasHistory(row)"
                  link size="small" :icon="Clock"
                  title="操作记录"
                  @click="openHistory(row)" />
              </td>
            </tr>
          </template>
        </tbody>

        <tbody>
          <template v-for="(row, ri) in activeRows" :key="row.clientRowId">
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
                  <!-- 防呆: 未选批次时内联红色提示 (Rule 1 + 4位一体: 预先显示边界) -->
                  <div v-if="!row.rawBatchId" style="margin-top:2px;font-size:11px;color:#f56c6c;white-space:nowrap">
                    请先选择原料批次
                  </div>
                  <!-- 防呆: 未落仓批次已从下拉隐藏, 显式提示原因 (Rule 5: 不做 dead-end) -->
                  <el-tooltip
                    v-if="rawBatchExcludedNoWarehouseCount > 0"
                    :content="`另有 ${rawBatchExcludedNoWarehouseCount} 个批次未落仓，无法领用（请先完成入库/领料）`"
                    placement="top">
                    <el-icon style="color:#e6a23c;margin-top:2px;cursor:pointer"><Warning /></el-icon>
                  </el-tooltip>
                </td>
                <td class="sp-td sp-td-num">
                  <el-input-number
                    v-model="row.rawBatchQty"
                    :min="0" :precision="2"
                    controls-position="right"
                    style="width:110px" size="small" />
                </td>
              </template>

              <!-- ---- 焯水 + 滚揉: single upstream dropdown (结构相同) — 含半成品库存(SFI) ---- -->
              <template v-else-if="isSingleUpstream">
                <td class="sp-td">
                  <el-select
                    :model-value="singleUpstreamSelectKey(row)"
                    @change="(v: string) => onSingleUpstreamSelect(row, v)"
                    :placeholder="`选${upstreamProcessName}批次/半成品`"
                    filterable clearable
                    :filter-method="onBatchSelectFilter"
                    @visible-change="onBatchSelectVisibleChange"
                    :loading="sfiLoading"
                    style="width:200px" size="small">
                    <el-option-group v-if="wipOptionsDisplay.length" label="本计划在制半成品">
                      <el-option
                        v-for="item in wipOptionsDisplay"
                        :key="item.batchNumber"
                        :label="wipLabel(item)"
                        :value="srcKey(SRC_WIP, item.batchNumber)"
                        :disabled="item.remaining <= 0" />
                    </el-option-group>
                    <el-option-group v-if="sfiOptionsDisplay.length" :label="sfiGroupLabel">
                      <el-option
                        v-for="s in sfiOptionsDisplay" :key="'sfi-' + s.intermediateBatchNo"
                        :label="sfiLabel(s)"
                        :value="srcKey(SRC_SFI, s.intermediateBatchNo)"
                        :disabled="sfiAvailable(s) <= 0" />
                    </el-option-group>
                    <el-option-group v-if="fgOptionsDisplay.length" :label="fgGroupLabel">
                      <el-option
                        v-for="f in fgOptionsDisplay" :key="'fg-' + f.batchNumber"
                        :label="fgLabel(f)"
                        :value="srcKey(SRC_FG, f.batchNumber)"
                        :disabled="fgAvailable(f) <= 0" />
                    </el-option-group>
                  </el-select>
                </td>
              </template>

              <!-- ---- 去舌苔: single upstream dropdown — 含半成品库存(SFI) ---- -->
              <template v-else-if="isQuSheTou">
                <td class="sp-td">
                  <el-select
                    :model-value="singleUpstreamSelectKey(row)"
                    @change="(v: string) => onSingleUpstreamSelect(row, v)"
                    :placeholder="`选${upstreamProcessName}批次/半成品`"
                    filterable clearable
                    :filter-method="onBatchSelectFilter"
                    @visible-change="onBatchSelectVisibleChange"
                    :loading="sfiLoading"
                    style="width:200px" size="small">
                    <el-option-group v-if="wipOptionsDisplay.length" label="本计划在制半成品">
                      <el-option
                        v-for="item in wipOptionsDisplay"
                        :key="item.batchNumber"
                        :label="wipLabel(item)"
                        :value="srcKey(SRC_WIP, item.batchNumber)"
                        :disabled="item.remaining <= 0" />
                    </el-option-group>
                    <el-option-group v-if="sfiOptionsDisplay.length" :label="sfiGroupLabel">
                      <el-option
                        v-for="s in sfiOptionsDisplay" :key="'sfi-' + s.intermediateBatchNo"
                        :label="sfiLabel(s)"
                        :value="srcKey(SRC_SFI, s.intermediateBatchNo)"
                        :disabled="sfiAvailable(s) <= 0" />
                    </el-option-group>
                    <el-option-group v-if="fgOptionsDisplay.length" :label="fgGroupLabel">
                      <el-option
                        v-for="f in fgOptionsDisplay" :key="'fg-' + f.batchNumber"
                        :label="fgLabel(f)"
                        :value="srcKey(SRC_FG, f.batchNumber)"
                        :disabled="fgAvailable(f) <= 0" />
                    </el-option-group>
                  </el-select>
                </td>
              </template>

              <!-- ---- 熟制 / 气调: multi-source expander cell ---- -->
              <template v-else-if="isShuZhi || isQidiao">
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

                  <!-- auto: reverseInput (去舌苔: input = scrap + output) -->
                  <span v-else-if="col.type === 'auto' && col.autoCalc === 'reverseInput'" class="sp-readonly">
                    {{ calcReverseInput(row) != null ? calcReverseInput(row)!.toFixed(2) + 'kg' : '—' }}
                  </span>

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

                  <!-- auto: sumBoxes (气调: 实际生产=入库+留样+剩余+领用) -->
                  <span v-else-if="col.type === 'auto' && col.autoCalc === 'sumBoxes'" class="sp-readonly">
                    {{ calcSumBoxes(row) }}
                  </span>

                  <!-- auto: sumWeight (气调: 总重量=成品重+料头) -->
                  <span v-else-if="col.type === 'auto' && col.autoCalc === 'sumWeight'" class="sp-readonly">
                    {{ calcSumWeight(row).toFixed(2) }}
                  </span>

                  <!-- auto: yieldByProductWeight (气调: 出成率=成品重/使用重量×100) -->
                  <span v-else-if="col.type === 'auto' && col.autoCalc === 'yieldByProductWeight'" class="sp-readonly">
                    {{ calcYieldByProductWeight(row) != null ? calcYieldByProductWeight(row)!.toFixed(2) + '%' : '—' }}
                  </span>

                  <!-- auto: laborPerBox (气调: 每盒人工费=工时单价×总工时/实际生产) -->
                  <span v-else-if="col.type === 'auto' && col.autoCalc === 'laborPerBox'" class="sp-readonly">
                    {{ calcLaborPerBox(row) != null ? '¥' + calcLaborPerBox(row)!.toFixed(4) : '—' }}
                  </span>

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
                  :disabled="!!saveDisabledReason(row) || row.saving"
                  :title="saveDisabledReason(row) || '保存此行'"
                  @click="handleSave(row)"
                  style="padding:3px 8px">保存</el-button>
                <el-button
                  v-if="hasHistory(row)"
                  link size="small" :icon="Clock"
                  title="操作记录"
                  @click="openHistory(row)"
                  style="margin-left:4px" />
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
                    {{ upstreamProcessName }}来源批 (混锅) — {{ row.batchNumber || '(未保存行)' }}
                    <el-button size="small" :icon="Plus" style="margin-left:8px" @click="addUpstreamSource(row)">
                      + 来源批
                    </el-button>
                  </div>

                  <!-- Multi-source rows -->
                  <div v-for="(src, si) in row.upstreamSources" :key="si"
                       style="display:flex;align-items:center;gap:8px;margin-bottom:6px">
                    <el-select
                      :model-value="srcSelectKey(src)"
                      @change="(v: string) => onUpstreamSelect(src, v)"
                      :placeholder="`选${upstreamProcessName}批次`" filterable clearable
                      :filter-method="onBatchSelectFilter"
                      @visible-change="onBatchSelectVisibleChange"
                      :loading="sfiLoading"
                      style="width:220px" size="small">
                      <el-option-group label="本计划在制半成品">
                        <el-option
                          v-for="item in wipOptionsDisplay" :key="item.batchNumber"
                          :label="wipLabel(item)"
                          :value="srcKey(SRC_WIP, item.batchNumber)"
                          :disabled="item.remaining <= 0" />
                      </el-option-group>
                      <el-option-group v-if="sfiOptionsDisplay.length" :label="sfiGroupLabel">
                        <el-option
                          v-for="s in sfiOptionsDisplay" :key="'sfi-' + s.intermediateBatchNo"
                          :label="sfiLabel(s)"
                          :value="srcKey(SRC_SFI, s.intermediateBatchNo)"
                          :disabled="sfiAvailable(s) <= 0" />
                      </el-option-group>
                      <el-option-group v-if="fgOptionsDisplay.length" :label="fgGroupLabel">
                        <el-option
                          v-for="f in fgOptionsDisplay" :key="'fg-' + f.batchNumber"
                          :label="fgLabel(f)"
                          :value="srcKey(SRC_FG, f.batchNumber)"
                          :disabled="fgAvailable(f) <= 0" />
                      </el-option-group>
                    </el-select>
                    <el-input-number
                      v-model="src.feedQuantityKg"
                      :min="0" :precision="2"
                      placeholder="投料kg"
                      controls-position="right"
                      size="small" style="width:120px" />
                    <span v-if="src.sourceBatchNumber" style="font-size:11px;color:#909399">
                      {{ srcRemainingLabel(src) }}
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

            <!-- ============================================================
                 气调: 混锅来源 expander row (SP-G G3b)
                 ============================================================ -->
            <tr v-if="isQidiao && row.mixExpanded" :key="row.clientRowId + '-mix'"
                :class="['sp-tr-expand', ri % 2 === 0 ? 'sp-tr-even' : 'sp-tr-odd']">
              <td :colspan="999" class="sp-td-expand">
                <div class="sp-expand-section">
                  <div class="sp-expand-title">
                    {{ upstreamProcessName }}来源批 (混锅) — {{ row.batchNumber || '(未保存行)' }}
                    <el-button size="small" :icon="Plus" style="margin-left:8px" @click="addUpstreamSource(row)">
                      + 来源批
                    </el-button>
                  </div>

                  <!-- Multi-source rows -->
                  <div v-for="(src, si) in row.upstreamSources" :key="si"
                       style="display:flex;align-items:center;gap:8px;margin-bottom:6px">
                    <el-select
                      :model-value="srcSelectKey(src)"
                      @change="(v: string) => onUpstreamSelect(src, v)"
                      :placeholder="`选${upstreamProcessName}批次`" filterable clearable
                      :filter-method="onBatchSelectFilter"
                      @visible-change="onBatchSelectVisibleChange"
                      :loading="sfiLoading"
                      style="width:220px" size="small">
                      <el-option-group label="本计划在制半成品">
                        <el-option
                          v-for="item in wipOptionsDisplay" :key="item.batchNumber"
                          :label="wipLabel(item)"
                          :value="srcKey(SRC_WIP, item.batchNumber)"
                          :disabled="item.remaining <= 0" />
                      </el-option-group>
                      <el-option-group v-if="sfiOptionsDisplay.length" :label="sfiGroupLabel">
                        <el-option
                          v-for="s in sfiOptionsDisplay" :key="'sfi-' + s.intermediateBatchNo"
                          :label="sfiLabel(s)"
                          :value="srcKey(SRC_SFI, s.intermediateBatchNo)"
                          :disabled="sfiAvailable(s) <= 0" />
                      </el-option-group>
                      <el-option-group v-if="fgOptionsDisplay.length" :label="fgGroupLabel">
                        <el-option
                          v-for="f in fgOptionsDisplay" :key="'fg-' + f.batchNumber"
                          :label="fgLabel(f)"
                          :value="srcKey(SRC_FG, f.batchNumber)"
                          :disabled="fgAvailable(f) <= 0" />
                      </el-option-group>
                    </el-select>
                    <el-input-number
                      v-model="src.feedQuantityKg"
                      :min="0" :precision="2"
                      placeholder="投料kg"
                      controls-position="right"
                      size="small" style="width:120px" />
                    <span v-if="src.sourceBatchNumber" style="font-size:11px;color:#909399">
                      {{ srcRemainingLabel(src) }}
                    </span>
                    <el-button link type="danger" :icon="Delete" @click="removeUpstreamSource(row, si)" />
                  </div>
                  <div v-if="row.upstreamSources.length === 0" style="color:#909399;font-size:12px;margin:4px 0">
                    暂无来源批，点击 + 来源批 添加
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

    <!-- ====================================================================
         SP-G P3: 操作记录 (行级 diff 时间线)
         ==================================================================== -->
    <el-dialog
      v-model="historyVisible"
      title="操作记录"
      width="560px"
      append-to-body>
      <div style="margin-bottom:8px;font-size:12px;color:#909399">
        批次: <span style="color:#409eff;font-weight:600">{{ historyBatchLabel }}</span>
      </div>
      <div v-loading="historyLoading">
        <el-empty
          v-if="!historyLoading && historyRows.length === 0"
          description="暂无操作记录" :image-size="60" />
        <el-timeline v-else>
          <el-timeline-item
            v-for="h in historyRows" :key="h.id"
            :type="OP_TYPE[h.operation] || 'primary'"
            :timestamp="formatHistoryTime(h.createdAt)"
            placement="top">
            <div style="display:flex;align-items:center;gap:8px;margin-bottom:4px">
              <el-tag :type="OP_TYPE[h.operation] || 'info'" size="small">
                {{ OP_LABEL[h.operation] || h.operation }}
              </el-tag>
              <span v-if="h.operatorId != null" style="font-size:11px;color:#909399">
                操作人 #{{ h.operatorId }}
              </span>
            </div>
            <div style="font-size:12px;color:#606266;white-space:pre-wrap;word-break:break-all">
              {{ h.diffSummary || '(无变更摘要)' }}
            </div>
          </el-timeline-item>
        </el-timeline>
      </div>
      <template #footer>
        <el-button @click="historyVisible = false">关闭</el-button>
      </template>
    </el-dialog>

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
/* 🔴 防呆 Rule 1: 操作/保存列必须始终可见, 不能被横向滚动藏起来 —
   低文化素质操作员发现不了藏在滚动区外的保存按钮, 数据可能"静默不保存"。
   position:sticky + right:0 把该列钉在可视区右缘, 无论横向滚多远都够得着。 */
.sp-th-actions   {
  width: 120px;
  text-align: center;
  position: sticky;
  right: 0;
  z-index: 4; /* 高于 .sp-th 的 z-index:2 — 表头此列同时 sticky top + right (左上/右上双固定角) */
  box-shadow: -2px 0 4px rgba(0, 0, 0, 0.08);
}

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
/* 🔴 防呆 Rule 1 (同上): 每行的保存/操作列同样钉在右缘, 与表头 sticky 呼应,
   保证滚到再远也能一眼看到、点到"保存"按钮。 */
.sp-td-actions {
  text-align: center;
  white-space: nowrap;
  position: sticky;
  right: 0;
  z-index: 1;
  box-shadow: -2px 0 4px rgba(0, 0, 0, 0.06);
  /* 显式背景色 (与下方 .sp-tr-even/odd 一致) — sticky 定位后仍需不透明背景,
     否则横向滚动时后面的单元格内容会从缝隙透出来。 */
  background: #ffffff;
}

/* Row alternating background */
.sp-tr-even { background: #ffffff; }
.sp-tr-odd  { background: #fafafa; }
.sp-tr-even .sp-td-actions { background: #ffffff; }
.sp-tr-odd  .sp-td-actions { background: #fafafa; }
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
   已小结区块
   ------------------------------------------------------------------------- */

/* Grid: 已小结 banner row */
.sp-settled-banner td {
  background: #f0f4ff;
  border: 1px solid #dcdfe6;
  border-left: 3px solid #909399;
  padding: 5px 10px;
  font-size: 12px;
  color: #606266;
}

/* Grid: 已小结 compact rows */
.sp-tr-settled td {
  background: #fafbfd;
  color: #909399;
  font-style: italic;
}
.sp-tr-settled .sp-td-batch {
  color: #a0a8b8;
}

/* Card: 已小结 collapsible section */
.sp-settled-section {
  margin-bottom: 10px;
  border: 1px solid #dcdfe6;
  border-left: 3px solid #909399;
  border-radius: 4px;
  overflow: hidden;
}
.sp-settled-header {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  background: #f0f4ff;
  font-size: 12px;
  color: #606266;
  cursor: pointer;
  user-select: none;
}
.sp-settled-header:hover {
  background: #e8edf8;
}
.sp-card-settled {
  border-color: #c0c4cc;
  background: #fafbfd;
}
.sp-card-settled .sp-card-header {
  background: #f5f5f7;
}
.sp-settled-summary {
  font-size: 12px;
  color: #606266;
  margin-left: 8px;
}
.sp-settled-ts {
  font-size: 11px;
  color: #c0c4cc;
  margin-left: 8px;
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
