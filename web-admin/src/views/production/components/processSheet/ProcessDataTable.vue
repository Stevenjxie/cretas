<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Delete, Check, Warning, ArrowDown, ArrowRight, Clock, Loading, QuestionFilled } from '@element-plus/icons-vue';
import {
  saveDraftRow, submitRow, deleteRow, getAvailableRawBatches, getRowHistory, getSemiFinishedInventory,
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
  type WorkflowProcessDescriptor,
  type WorkflowPortDescriptor,
  type MaterialInputTotal,
  type ProcessSheetByproduct,
} from '@/api/processSheet';
import { listWarehouses, type FactoryWarehouse } from '@/api/factoryWarehouse';
import type { ProcessSheetCustomFieldDef } from '@/api/processProduction';
import { PROCESS_SHEET_CONFIG, GENERIC_FALLBACK_COLS, genClientRowId, type ColDef } from './PROCESS_SHEET_CONFIG';
import WorkHoursTable from './WorkHoursTable.vue';
import { boxAvailableKg, isCountUnit, countUnitFeedWarning, countUnitLabelSuffix } from '@/utils/feedUnitConversion';
import {
  formatFeedPlaceholder,
  formatProcessOutput,
  formatSourceFeedSummary,
  normalizeMassQuantityForReporting,
  resolveProcessSheetUnits,
  resolveWorkflowProcessSheetUnits,
  workflowPortDisplayUnit,
  withProcessSheetUnits,
} from '@/utils/processSheetUnits';
import { buildEqualPotWeightsKg } from './potAllocation';

type PortSelectionMode = NonNullable<WorkflowPortDescriptor['selectionGroupMode']>;

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
  /** 张权 R4: 本工序是否被配置为「半成品注入工序」(ProductWorkProcess.allowSemiFinishedInjection)。
   * true → 逐道录入显示半成品(SFI)/成品(FG) 投料选择器 (config-driven gating, 见 showSfi)。 */
  allowSemiFinishedInjection?: boolean;
  /** 是否允许本道工序从多个上游批次混批投料。false 时仍可单选一个上游批次。 */
  allowMultipleUpstreamSources?: boolean;
  /**
   * G1 混批去硬编码: 本工序是否为链内第一道 (无上游, 如原料领料入口)。
   * false/undefined → 走 `!isFirstProcess` 主判据 (config-driven, 由父组件按 processOrder ===
   * 链内最小 processOrder 算出); undefined (父组件未传) 时只靠下方 archetype 兜底判断
   * (与既有 5-archetype 硬编码等价, 保证零回归)。true → 明确首道, 不显上游来源选择器
   * (除非命中 archetype 兜底, 如 xiuyou 从不在 archetype 集合内所以不受影响)。
   */
  isFirstProcess?: boolean;
  /**
   * G2 自定义字段: 本工序 WorkProcess.customFieldSchema (只含 enabled=true 项由父组件预筛选,
   * 也可整份原样传入 — 本组件自行按 enabled 过滤)。渲染为额外的通用录入列 (追加到 cols 之后),
   * 值收集进保存请求的 customFields map。
   */
  customFieldSchema?: ProcessSheetCustomFieldDef[] | null;
  /** 本工序配置的投入单位（优先产品-工序单位覆盖）。 */
  inputUnit?: string;
  /** 本工序配置的产出单位；未配置时沿用投入单位。 */
  outputUnit?: string | null;
  /** 是否允许本道工序选择成品库存批次作为投料来源。 */
  allowFinishedGoodsSource?: boolean;
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
  /**
   * 2B Task F2 (additive, fool-proof): 本工序对应的 workflow 端口上下文 (计划产出 SKU/单位 +
   * 所需原料类型), 仅 workflow-activated 计划的 ProcessSheet.vue 会透传非 null 值; legacy
   * 计划恒为 null/undefined。只读展示用 —— 不改变 saveRow 请求形状, 不影响任何现有 picker。
   */
  workflowContext?: WorkflowProcessDescriptor | null;
  /** 真实工序类别，仅作其他工序语义展示；锅数由 seasoningPotEnabled 唯一驱动。 */
  processCategory?: string | null;
  /** BOM 调料配方是否对本工序显式开启锅序计算。 */
  seasoningPotEnabled?: boolean;
  /** BOM 调料配方是否对本工序配置了调料明细。 */
  seasoningConfigured?: boolean;
  /** 后端已存行证明前一道工序至少有一条正式报工；只限制正式提交，草稿仍可保存。 */
  upstreamSubmissionReady?: boolean;
  /** 前一道真实工序名，用于给出可执行的阻断提示。 */
  upstreamSubmissionMessage?: string;
}>(), {
  allowSemiFinishedInjection: false,
  allowMultipleUpstreamSources: false,
  customFieldSchema: () => [],
  allowFinishedGoodsSource: false,
  upstreamItems: () => [],
  ownInventoryItems: () => [],
  initialRows: () => [],
  viewMode: 'grid',
  workflowContext: null,
  upstreamSubmissionReady: true,
});
const emit = defineEmits<{
  (e: 'row-saved', submissionStatus: 'DRAFT' | 'SUBMITTED' | 'LEGACY' | null): void;
}>();

// -------------------------------------------------------------------------
// 2B.2 多产出 (fan-out): 一个产出端口条目。产品/端口由 workflow 产出端口固定 (只读, fool-proof
// Rule 2/3 — 操作员不能自由选产品), 只填数量。batchNumber 保存/重载后填充 (只读展示)。
// -------------------------------------------------------------------------
interface MultiOutputLine {
  workflowPortId: string;
  /** 端口身份: workflow 物料 Cell 节点 id (随请求发给后端记录)。 */
  materialNodeId: string;
  productTypeId: string;
  /** 只读展示品名; 端口 SKU 已失效时兜底显 productTypeId, 不崩溃。 */
  materialName: string;
  unit: string;
  /** SKU 单位净重；计数型成品缺失时只展示明确错误，不猜重量。 */
  gramsPerUnit: number | null;
  finished: boolean;
  required: boolean;
  selected: boolean;
  quantity: number | null;
  /** 一条产出对应一段开始/结束时间；总工时由这两个值即时计算。 */
  startTime: string;
  endTime: string;
  workerCount: number;
  /** 副产是该产出的附属事实，单位固定只读。 */
  byproductQuantity: number | null;
  byproductUnit: string;
  byproductUnitPrice: number | null;
  /** 仅产出维度无法统一时显示并提交，值域 (0, 100]。 */
  costAllocationRatio: number | null;
  /** 保存后系统生成的产出批次号 (重载回显); 未保存为 null。 */
  batchNumber: string | null;
}

interface MaterialInputTotalLine extends Omit<MaterialInputTotal, 'quantity'> {
  materialName: string;
  quantity: number | null;
  selected: boolean;
}

interface SelectableUpstreamRef extends UpstreamRef {
  selected: boolean;
}

// -------------------------------------------------------------------------
// Internal row type
// -------------------------------------------------------------------------
interface SheetRow {
  clientRowId: string;
  batchNumber: string | null;
  rowStatus: 'SAVED' | 'DRAFT' | 'UNSAVED';
  submissionStatus: 'DRAFT' | 'SUBMITTED' | 'LEGACY' | null;
  materialized: boolean;
  /** 历史行保存时的真实产出单位；仅用于只读显示换算，不改写 payload。 */
  persistedOutputUnit: string;
  blockingMessage: string | null;
  /** 已小结时间 (ISO-8601); null = 未小结，可编辑 */
  interimSettledAt: string | null;
  saving: boolean;
  deleting: boolean;
  /** Generic per-process scalar fields: date / number / daterange ([start,end]) values keyed by ColDef.key */
  fields: Record<string, string | number | [string, string] | null>;
  /** 一次报工记录组的生产日期；Workflow 端口模式始终显示并发送为 processDate。 */
  productionDate: string;
  /** 修油: selected raw material batch id → rawMaterialInputs[0].materialBatchId */
  rawBatchId: string;
  /** 修油: out-weight (kg) → rawMaterialInputs[0].quantity */
  rawBatchQty: number | null;
  /** 新报工路径：只录各原料总投料量，由后端正式提交时自动分摊生产库批次。 */
  materialInputTotals: MaterialInputTotalLine[];
  /** 已存在显式原料批次的旧行保持原选择器，不能在回显时改写为新契约。 */
  legacyExplicitRawInput: boolean;
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
  upstreamSources: SelectableUpstreamRef[];
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
  /**
   * 2B.2 多产出 (fan-out): 本工序 workflow 产出端口 > 1 时填充 (N 项, 一项一个产出端口)。
   * 单产出/legacy 恒为空数组, 不影响任何现有单产出字段/逻辑。
   */
  multiOutputs: MultiOutputLine[];
}

// -------------------------------------------------------------------------
// Column config
// -------------------------------------------------------------------------
/** G2 自定义字段: 只取 enabled=true 项 (防呆: 曾配置后又关闭的字段不再显示, 但历史值仍随
 *  row_payload/customFields 保留, 不因隐藏而丢失 —— 重新开启后仍能看到)。 */
const enabledCustomFields = computed(() =>
  (props.customFieldSchema || []).filter((f) => f.enabled),
);
/** G2 自定义字段列描述符 (追加到 archetype/generic 列之后), key 与 label/type 直接来自 schema。 */
const customFieldCols = computed<ColDef[]>(() =>
  enabledCustomFields.value.map((f) => ({ key: f.key, label: f.label, type: f.type })),
);
/** A: 自定义字段列 key 集合 (O(1) 判定, 供列头 `?` info 提示区分自定义列 vs archetype/generic 列)。 */
const customFieldKeySet = computed(() => new Set(customFieldCols.value.map((c) => c.key)));
function isCustomFieldCol(key: string): boolean {
  return customFieldKeySet.value.has(key);
}
/** G2: 已知 archetype 无列定义时的通用兜底 (真正自定义命名、未映射的新工序); 追加已启用的自定义字段列。 */
const processUnits = computed(() => props.workflowContext
  ? resolveWorkflowProcessSheetUnits(props.workflowContext)
  : resolveProcessSheetUnits({
    defaultUnit: props.inputUnit,
    defaultOutputUnit: props.outputUnit,
    // 老气调配置未填 outputUnit 时，保持既有 kg → 盒口径；显式配置优先。
    fallbackOutputUnit: props.processCode === 'qidiao' ? '盒' : undefined,
  }));
const cols = computed(() => [
  ...withProcessSheetUnits(PROCESS_SHEET_CONFIG[props.processCode] || GENERIC_FALLBACK_COLS, processUnits.value),
  ...customFieldCols.value,
]);
const firstProcessInputLabel = computed(() =>
  cols.value.find((col) => col.key === 'outWeight')?.label || `出库数量(${processUnits.value.inputUnit})`,
);
const isShuZhi = computed(() => props.processCode === 'shuzhi');
/** 锅数录入只由 BOM 调料配方的显式工序参数驱动，不再猜测工序名或类别。 */
const needsPotCount = computed(() => props.seasoningPotEnabled === true);
const needsSeasoningInputKg = computed(() => props.seasoningConfigured === true);
const isXiuYou = computed(() => props.processCode === 'xiuyou');
/** 单上游 WIP 工序: 焯水 + 滚揉. 两者结构完全相同 (before/after 字段, 单 upstream). */
const isSingleUpstream = computed(() =>
  props.processCode === 'chaoshui' || props.processCode === 'gunrou',
);
const isQuSheTou = computed(() => props.processCode === 'qushetou');
const isQidiao = computed(() => props.processCode === 'qidiao');
/** 已登记列定义的 archetype processCode 集合 (与 PROCESS_SHEET_CONFIG 的 key 一致)。 */
const KNOWN_ARCHETYPES = new Set(['xiuyou', 'chaoshui', 'gunrou', 'qushetou', 'shuzhi', 'qidiao']);
/**
 * G1 混批去硬编码: 本工序是否显示上游来源选择器 (单选/混选)。
 *
 * 主判据 (config-driven): `!props.isFirstProcess` —— 父组件按产品工序链动态算出"是否为链内
 * 第一道", 任何非首道工序(不论真实工序名/processCode)均可用。
 * 兜底 (archetype, 向后兼容零回归): 现有 5 个已登记 archetype 即使 isFirstProcess 未传
 * (父组件旧版本 / 单测直接挂载不传该 prop) 也保持可用, 与本次改动前完全一致。
 */
const supportsUpstreamSources = computed(() =>
  props.isFirstProcess === false
  || isSingleUpstream.value || isQuSheTou.value || isShuZhi.value || isQidiao.value,
);
/** 非首道 Workflow 真正来自上游库存的端口；RAW 原料继续走首道领料/投料总量契约。 */
const workflowUpstreamInputs = computed(() => {
  if (!props.workflowContext || props.isFirstProcess === true) return [];
  return props.workflowContext.inputs.filter((port) => port.materialKind !== 'RAW_MATERIAL');
});
const isMultiSource = computed(() => supportsUpstreamSources.value && (
  props.allowMultipleUpstreamSources === true || workflowUpstreamInputs.value.length > 1
));
const isSingleSource = computed(() => supportsUpstreamSources.value && !isMultiSource.value);

// -------------------------------------------------------------------------
// 2B Task F2 (additive, fool-proof Rule 2/3): workflow 端口上下文只读展示。
// 不改变任何现有 picker / buildRequest / saveRow 逻辑 —— 只在录入区顶部提示
// "这道工序该产什么(SKU/单位/半成品|成品)" + "需要哪些原料类型", 客户自己填数量/选批次。
// -------------------------------------------------------------------------
/** 本工序 workflow 计划产出端口 (null = 无 workflow 上下文, 或该 workflow 任务无产出端口)。 */
const workflowOutput = computed(() => props.workflowContext?.output ?? null);
/** 本工序 workflow 声明的原料类型 (仅 RAW_MATERIAL kind, 供领料 picker 旁提示"需要哪些原料")。 */
const workflowRawInputs = computed(() =>
  (props.workflowContext?.inputs ?? []).filter((p) => p.materialKind === 'RAW_MATERIAL'),
);
/** 已被删除/重建、无法再解析的 Workflow 原料。报工页不能临时换料，必须阻断并回配置修复。 */
const unresolvedWorkflowRawInputs = computed(() =>
  workflowRawInputs.value.filter((p) => !p.skuId || p.skuResolved === false),
);
const workflowConfigHref = computed(() =>
  props.productTypeId
    ? `/system/product-processes?productTypeId=${encodeURIComponent(props.productTypeId)}`
    : '/system/product-processes',
);
/** 产出摘要文字: "{品名}（{单位}）" — 品名/单位缺失 (SKU 已失效) 时用 skuId 兜底, 不崩溃。 */
const workflowOutputLabel = computed(() => {
  const out = workflowOutput.value;
  if (!out) return '';
  const name = out.materialName || `(未命名 SKU: ${out.skuId})`;
  const unit = workflowPortDisplayUnit(out);
  return unit ? `${name}（${unit}）` : name;
});
/** 需要原料类型摘要文字 (供领料 picker 旁提示)。 */
const workflowRawInputsLabel = computed(() =>
  workflowRawInputs.value.map((p) => p.materialName || p.skuId).join('、'),
);
/** 2B.2 多产出横幅摘要文字: "{品名1}（{单位1}） + {品名2}（{单位2}）..."。 */
const workflowOutputsLabel = computed(() =>
  outputPorts.value
    .map((p) => {
      const name = p.materialName || `(未命名 SKU: ${p.skuId})`;
      const unit = workflowPortDisplayUnit(p);
      return unit ? `${name}（${unit}）` : name;
    })
    .join(' + '),
);

// -------------------------------------------------------------------------
// 2B.2 多产出 (fan-out): 一道工序一次报工同时产出多个产品 (如装箱复称同产 350g/400g 成品,
// 筛选同产 合格半成品+不合格损耗)。是否多产出**由 workflow 图自动决定** (端口数量),
// 不是开关 —— outputs.length > 1 时渲染 N 个产出端口的录入行, 产品/单位/成品|半成品全部
// 只读取自端口 (fool-proof Rule 2/3: 不给操作员自由选产品), 只填数量。
// -------------------------------------------------------------------------
/** 本工序全部产出端口。优先取 outputs[]; 回落单产出 output (legacy/未升级投影兜底)。 */
const outputPorts = computed<WorkflowPortDescriptor[]>(() => {
  const ports = props.workflowContext?.outputs;
  if (Array.isArray(ports) && ports.length > 0) return ports;
  const single = props.workflowContext?.output;
  return single ? [single] : [];
});
/** 多产出判据: 产出端口数 > 1。 */
const isMultiOutput = computed(() => outputPorts.value.length > 1);
/** Workflow 单/多产出统一使用端口行录入；legacy 计划继续走原列配置。 */
const isPortOutputMode = computed(() => props.workflowContext != null && outputPorts.value.length > 0);
const byproductReportingUnit = computed(() => {
  const unit = processUnits.value.inputUnit.trim().toLowerCase();
  return unit === 'g' || unit === '克' ? 'g' : 'kg';
});
/**
 * G1 混批去硬编码: processCode 不属于任何已登记 archetype 且本工序确实显示上游来源选择器
 * (supportsUpstreamSources) —— 真正自定义命名、未映射的新非首道工序。这类工序沿用 熟制
 * (shuzhi) 的单 input/output 字段形状 (见 GENERIC_FALLBACK_COLS + 下方
 * calcYield/buildRequest/hydrateRow/saveDisabledReason 里 `isGenericUpstream` 分支),
 * 但不含 熟制 专属的锅数(potCount)录入。绑定 supportsUpstreamSources 而非直接判 isFirstProcess,
 * 避免"支持来源选择器"和"用哪种字段形状"两个判据在边界 case 下不一致。
 */
const isGenericUpstream = computed(() =>
  !KNOWN_ARCHETYPES.has(props.processCode) && supportsUpstreamSources.value,
);

/**
 * 2B.2 多产出: 每个 archetype 里"代表产出"的列 key —— 多产出时这些列被专门的「多产出」
 * 录入块取代 (见模板), 必须从通用 cols 渲染中排除 (否则同时出现两套产出录入, 数据来源打架)。
 * 输入侧字段 (before/input/usedWeight/scrap 等) 不受影响, 照常渲染 (多产出的输入分配不变)。
 */
const MULTI_OUTPUT_HIDDEN_KEYS_BY_ARCHETYPE: Record<string, string[]> = {
  xiuyou: ['output', 'feedWeight', 'yieldRate'],
  gunrou: ['after', 'yieldRate'],
  chaoshui: ['after', 'yieldRate'],
  qushetou: ['scrap', 'output', 'input', 'yieldRate'],
  shuzhi: ['output', 'yieldRate'],
  qidiao: [
    'actualProd', 'sample', 'storage', 'remainBox', 'productWeight', 'inboundWeight',
  ],
};
/** 通用兜底 (isGenericUpstream, 结构同熟制) 的产出列。 */
const MULTI_OUTPUT_HIDDEN_KEYS_GENERIC = ['output', 'yieldRate'];
/** 列排除集合 (基础特殊字段 + 多产出时的产出列)。card/grid 两套模板 (th+td) 共用同一份, 保证列对齐。 */
const excludedColKeys = computed<string[]>(() => {
  const base = ['rawBatch', 'outWeight', 'upstreamBatch', 'batch'];
  if (!isPortOutputMode.value) return base;
  const archetypeKeys = MULTI_OUTPUT_HIDDEN_KEYS_BY_ARCHETYPE[props.processCode]
    ?? (isGenericUpstream.value ? MULTI_OUTPUT_HIDDEN_KEYS_GENERIC : []);
  const outputOwned = [
    ...archetypeKeys,
    'byproductQty', 'byproductPrice', 'totalHours', 'yieldRate',
    // Workflow 报工组统一显示一个生产日期，避免与旧 archetype 日期列重复。
    ...cols.value.filter((col) => col.type === 'date' || col.type === 'daterange').map((col) => col.key),
  ];
  return [...new Set([...base, ...outputOwned])];
});

// Bug 1 修复: 来源批次选择器的 label 必须反映真实工序链, 不能按 archetype processCode 硬编码
// (硬编码在 role-mode / 关键词回退下必错 —— 同一 archetype 可能对应不同真实工序名)。
/** 本工序真实显示名 (缺省兜底用 processCode, 理论上父组件总会传, 兜底只防御性)。 */
const ownProcessName = computed(() => props.processLabel || props.processCode);
/** 上游(前置)工序真实显示名; 链起步道(无上游)兜底通用「上游」。 */
const upstreamProcessName = computed(() => props.upstreamProcessLabel || '上游');
const sourceTitle = computed(() => `${upstreamProcessName.value}${isMultiSource.value ? '来源(混批)' : '批次'}`);
const supportsExternalStockFeed = computed(() => processUnits.value.inputUnit.toLowerCase() === 'kg');
const sourcePickerPlaceholder = computed(() => supportsExternalStockFeed.value
  ? `选${upstreamProcessName.value}批次/半成品`
  : `选${upstreamProcessName.value}在制批次（常驻半成品/成品仅支持kg投入）`);

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
    // raw-centric 多SKU (2026-07-13): workflow 计划的 productTypeId 是"影子"锚点产品 (如 raw-centric
    // 的原料猪蹄run), 无 BOM → 按 productTypeId 查 AVAILABLE 返 0 → 下拉空 "暂无可用原料批次"。
    // workflow 模式改按本道 workflow 声明的原料类型 (workflowContext.inputs RAW_MATERIAL 的 skuId=
    // raw_material_types.id) 过滤: 不传 productTypeId 拉全仓可用原料, 再客户端筛到本道所需原料类型。
    // legacy 计划 (无 workflowContext) 保持原按 productTypeId 查 BOM 原料的行为 (不回归)。
    const wfRawTypeIds = new Set(
      workflowRawInputs.value.map((p) => p.skuId).filter((id): id is string => !!id),
    );
    const useWorkflowRaw = wfRawTypeIds.size > 0;
    const resp = await getAvailableRawBatches(
      props.factoryId,
      useWorkflowRaw ? {} : { productTypeId: props.productTypeId },
    );
    if (seq !== rawBatchLoadSeq) return;
    const allowed = new Set(warehouseIds);
    const candidates = extractRawBatches(resp.data)
      .filter((b) => rawBatchAvailable(b) > 0)
      // workflow 模式: 只保留本道声明的原料类型 (防呆 Rule 3: 收敛选择, 不让操作员选错原料)。
      .filter((b) => !useWorkflowRaw || (b.materialTypeId != null && wfRawTypeIds.has(b.materialTypeId)));
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

function portSelectionMode(port?: WorkflowPortDescriptor): PortSelectionMode {
  return port?.selectionGroupMode ?? 'ALL_REQUIRED';
}

function portSelectedByDefault(port?: WorkflowPortDescriptor): boolean {
  return !port?.selectionGroupId || portSelectionMode(port) === 'ALL_REQUIRED';
}

function portSelectionDisabled(port?: WorkflowPortDescriptor): boolean {
  return !port?.selectionGroupId || portSelectionMode(port) === 'ALL_REQUIRED';
}

function portSelectionSummary(port?: WorkflowPortDescriptor): string {
  if (!port?.selectionGroupId) return '全部必选';
  const modeLabel: Record<PortSelectionMode, string> = {
    ALL_REQUIRED: '全部必选',
    EXACTLY_ONE: '互相替代（选 1）',
    AT_LEAST_ONE: '至少选 1',
    OPTIONAL: '可选',
  };
  return `${port.selectionGroupLabel || '端口关系'} · ${modeLabel[portSelectionMode(port)]}`;
}

function portById(portId?: string): WorkflowPortDescriptor | undefined {
  if (!portId) return undefined;
  return [...(props.workflowContext?.inputs ?? []), ...outputPorts.value]
    .find((port) => port.workflowPortId === portId);
}

function setPortSelected(
  row: SheetRow,
  port: WorkflowPortDescriptor | undefined,
  selected: boolean,
): void {
  if (!port || portSelectionDisabled(port)) return;
  const groupId = port.selectionGroupId;
  const update = (line: { workflowPortId?: string; selected: boolean }): void => {
    const candidate = portById(line.workflowPortId);
    if (!candidate) return;
    if (portSelectionMode(port) === 'EXACTLY_ONE'
      && candidate.selectionGroupId === groupId) {
      line.selected = false;
    }
    if (candidate.workflowPortId === port.workflowPortId) line.selected = selected;
  };
  row.materialInputTotals.forEach(update);
  row.upstreamSources.forEach(update);
  row.multiOutputs.forEach(update);
}

function selectedWorkflowPortIds(row: SheetRow): Set<string> {
  return new Set([
    ...row.materialInputTotals.filter((line) => line.selected).map((line) => line.workflowPortId || ''),
    ...row.upstreamSources.filter((line) => line.selected).map((line) => line.workflowPortId || ''),
    ...row.multiOutputs.filter((line) => line.selected).map((line) => line.workflowPortId || ''),
  ].filter(Boolean));
}

function selectionGroupReason(row: SheetRow, direction: 'INPUT' | 'OUTPUT'): string | null {
  const ports = direction === 'INPUT' ? (props.workflowContext?.inputs ?? []) : outputPorts.value;
  const explicitGroups = new Map<string, WorkflowPortDescriptor[]>();
  ports.forEach((port) => {
    if (!port.selectionGroupId) return;
    explicitGroups.set(port.selectionGroupId, [...(explicitGroups.get(port.selectionGroupId) ?? []), port]);
  });
  const selectedIds = selectedWorkflowPortIds(row);
  for (const groupPorts of explicitGroups.values()) {
    const first = groupPorts[0];
    const mode = portSelectionMode(first);
    const selectedCount = groupPorts.filter((port) => selectedIds.has(port.workflowPortId)).length;
    const defaultMin = mode === 'OPTIONAL' ? 0 : mode === 'ALL_REQUIRED' ? groupPorts.length : 1;
    const defaultMax = mode === 'EXACTLY_ONE' ? 1 : groupPorts.length;
    const min = first.selectionGroupMinSelections ?? defaultMin;
    const max = first.selectionGroupMaxSelections ?? defaultMax;
    if (selectedCount >= min && selectedCount <= max) continue;
    const label = first.selectionGroupLabel || (direction === 'INPUT' ? '投入关系' : '产出关系');
    if (mode === 'ALL_REQUIRED') return `“${label}”要求全部选用，请检查每条${direction === 'INPUT' ? '投入' : '产出'}`;
    if (mode === 'EXACTLY_ONE') return `“${label}”要求且只能选择 1 项，当前已选 ${selectedCount} 项`;
    if (mode === 'AT_LEAST_ONE') return `“${label}”至少选择 1 项，当前尚未选择`;
    return `“${label}”最多选择 ${max} 项，当前已选 ${selectedCount} 项`;
  }
  return null;
}

function blankRow(): SheetRow {
  // Default daterange fields to [today, today] for each daterange col in this process.
  const today = todayStr();
  const daterangeDefaults: Record<string, [string, string]> = {};
  for (const col of cols.value) {
    if (col.type === 'daterange') {
      daterangeDefaults[col.key] = [today, today];
    }
  }
  return {
    clientRowId: genClientRowId(props.processCode),
    batchNumber: null,
    rowStatus: 'UNSAVED',
    submissionStatus: null,
    materialized: false,
    persistedOutputUnit: processUnits.value.outputUnit,
    blockingMessage: null,
    interimSettledAt: null,
    saving: false,
    deleting: false,
    fields: { ...daterangeDefaults },
    productionDate: todayStr(),
    rawBatchId: '',
    rawBatchQty: null,
    materialInputTotals: workflowRawInputs.value.map((port): MaterialInputTotalLine => ({
      materialTypeId: port.skuId,
      materialName: port.materialName || port.skuId,
      quantity: null,
      unit: workflowPortDisplayUnit(port) || processUnits.value.inputUnit,
      workflowPortId: port.workflowPortId || undefined,
      materialNodeId: port.materialNodeId || undefined,
      selected: portSelectedByDefault(port),
    })),
    legacyExplicitRawInput: false,
    upstreamBatch: '',
    upstreamSemiFinished: false,
    upstreamFinishedGoods: false,
    upstreamSources: initWorkflowUpstreamSources(),
    laborSegments: [],
    potCount: 1,
    potRawKgs: [],
    laborExpanded: false,
    mixExpanded: workflowUpstreamInputs.value.length > 1,
    multiOutputs: initMultiOutputs(),
  };
}

/**
 * Workflow 单/多产出统一按当前工序产出端口初始化 N 条产出条目。产品、单位和成品类型
 * 只读取端口；操作员只录实际数量、时间和副产事实。legacy 工序仍返回空数组。
 */
function initMultiOutputs(): MultiOutputLine[] {
  if (!isPortOutputMode.value) return [];
  return outputPorts.value.map((p): MultiOutputLine => ({
    workflowPortId: p.workflowPortId,
    materialNodeId: p.materialNodeId ?? '',
    productTypeId: p.skuId,
    materialName: p.materialName || `(未命名 SKU: ${p.skuId})`,
    unit: workflowPortDisplayUnit(p),
    gramsPerUnit: p.gramsPerUnit ?? null,
    finished: p.finished === true,
    required: p.required === true,
    selected: portSelectedByDefault(p),
    quantity: null,
    startTime: '',
    endTime: '',
    workerCount: 1,
    byproductQuantity: null,
    byproductUnit: byproductReportingUnit.value,
    byproductUnitPrice: null,
    costAllocationRatio: null,
    batchNumber: null,
  }));
}

function sourceIdentity(port?: WorkflowPortDescriptor): Pick<UpstreamRef, 'workflowPortId' | 'materialNodeId' | 'skuId'> {
  return port ? {
    workflowPortId: port.workflowPortId || undefined,
    materialNodeId: port.materialNodeId || undefined,
    skuId: port.skuId || undefined,
  } : {};
}

function blankUpstreamSource(port?: WorkflowPortDescriptor, selected = portSelectedByDefault(port)): SelectableUpstreamRef {
  return { sourceBatchNumber: '', feedQuantityKg: 0, selected, ...sourceIdentity(port) };
}

function initWorkflowUpstreamSources(): SelectableUpstreamRef[] {
  return workflowUpstreamInputs.value.map((port) => blankUpstreamSource(port));
}

function sourcePort(src: UpstreamRef, fallbackIndex?: number): WorkflowPortDescriptor | undefined {
  return workflowUpstreamInputs.value.find((port) => port.workflowPortId === src.workflowPortId)
    || workflowUpstreamInputs.value.find((port) => port.skuId === src.skuId)
    || (fallbackIndex != null ? workflowUpstreamInputs.value[fallbackIndex] : undefined);
}

function hydrateUpstreamSources(sources: UpstreamRef[] | undefined): SelectableUpstreamRef[] {
  if (!sources?.length) return initWorkflowUpstreamSources();
  const hydrated = sources.map((source, index) => {
    const port = sourcePort(source, index);
    return { ...sourceIdentity(port), ...source, selected: true };
  });
  for (const port of workflowUpstreamInputs.value) {
    if (!hydrated.some((source) => source.workflowPortId === port.workflowPortId)) {
      hydrated.push(blankUpstreamSource(port));
    }
  }
  return hydrated;
}

function submittedUpstreamSources(row: SheetRow): UpstreamRef[] {
  return row.upstreamSources.filter((source) => source.selected).map((source, index) => {
    const port = sourcePort(source, index);
    const { selected: _selected, ...requestSource } = source;
    return { ...sourceIdentity(port), ...requestSource };
  });
}

function singleWorkflowSourceIdentity(): Pick<UpstreamRef, 'workflowPortId' | 'materialNodeId' | 'skuId'> {
  return sourceIdentity(workflowUpstreamInputs.value[0]);
}

function hydrateRow(view: ProcessSheetRowView): SheetRow {
  const p = view.payload;
  const row = blankRow();
  row.clientRowId = view.clientRowId;
  row.batchNumber = view.batchNumber;
  row.rowStatus = view.rowStatus;
  row.submissionStatus = view.submissionStatus ?? null;
  row.materialized = view.materialized === true;
  row.persistedOutputUnit = p.outputUnit || p.unit || processUnits.value.outputUnit;
  row.interimSettledAt = view.interimSettledAt ?? null;
  row.productionDate = p.processDate || todayStr();

  if (isXiuYou.value) {
    const explicitRaw = p.rawMaterialInputs?.[0];
    row.legacyExplicitRawInput = explicitRaw != null && !(p.materialInputTotals?.length);
    row.rawBatchId = explicitRaw?.materialBatchId ?? '';
    row.rawBatchQty = explicitRaw?.quantity ?? null;
    if (p.materialInputTotals?.length) {
      const savedLines = p.materialInputTotals.map((item): MaterialInputTotalLine => {
        const port = workflowRawInputs.value.find((candidate) =>
          candidate.workflowPortId === item.workflowPortId || candidate.skuId === item.materialTypeId,
        );
        return {
          ...item,
          materialName: port?.materialName || item.materialTypeId,
          unit: port ? (workflowPortDisplayUnit(port) || item.unit) : item.unit,
          selected: true,
        };
      });
      const savedByPort = new Map(savedLines.map((line) => [line.workflowPortId, line]));
      row.materialInputTotals = workflowRawInputs.value.map((port) => savedByPort.get(port.workflowPortId) ?? ({
          materialTypeId: port.skuId,
          materialName: port.materialName || port.skuId,
          quantity: null,
          unit: workflowPortDisplayUnit(port) || processUnits.value.inputUnit,
          workflowPortId: port.workflowPortId || undefined,
          materialNodeId: port.materialNodeId || undefined,
          selected: portSelectedByDefault(port),
        }));
      savedLines.forEach((line) => {
        if (!row.materialInputTotals.some((candidate) => candidate.workflowPortId === line.workflowPortId)) {
          row.materialInputTotals.push(line);
        }
      });
    }
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
  if (isShuZhi.value || isGenericUpstream.value) {
    if (isMultiSource.value) {
      row.upstreamSources = hydrateUpstreamSources(p.upstreamSources);
    } else {
      row.upstreamBatch = p.upstreamSources?.[0]?.sourceBatchNumber ?? '';
      row.upstreamSemiFinished = p.upstreamSources?.[0]?.semiFinished ?? false;
      row.upstreamFinishedGoods = p.upstreamSources?.[0]?.finishedGoods ?? false;
    }
    row.fields['input'] = p.inputQuantity ?? null;
    row.fields['output'] = p.outputQuantity ?? null;
    // 锅数(potCount)是 熟制 专属 UI (isShuZhi 才渲染 v-if), generic 也镜像 hydrate 无害
    // (值存在但不渲染, isMultiSource 混锅面板对 generic 同样可用不含锅数录入)。
    row.potCount = p.potCount ?? 1;
    row.potRawKgs = (p.potRawKgs ?? []).map((v) => v);
  }
  if (isQidiao.value) {
    if (isMultiSource.value) {
      row.upstreamSources = hydrateUpstreamSources(p.upstreamSources);
    } else {
      row.upstreamBatch = p.upstreamSources?.[0]?.sourceBatchNumber ?? '';
      row.upstreamSemiFinished = p.upstreamSources?.[0]?.semiFinished ?? false;
      row.upstreamFinishedGoods = p.upstreamSources?.[0]?.finishedGoods ?? false;
    }
    // 成品报工只恢复两项事实；入库、剩余和重量始终重新派生，禁止把历史手填字段当真值。
    row.fields['actualProd'] = p.outputQuantity ?? null;
    row.fields['sample'] = p.sampleRetainQuantity ?? 0;
  }
  row.laborSegments = (p.laborSegments ?? []).map((s) => ({ ...s }));

  // Workflow 单产出也使用与多产出相同的端口行；旧顶层字段回填到唯一产出行。
  if (isPortOutputMode.value && !isMultiOutput.value && row.multiOutputs[0]) {
    const line = row.multiOutputs[0];
    const segment = p.laborSegments?.[0];
    const byproduct = p.byproducts?.[0];
    line.quantity = p.outputQuantity ?? null;
    line.startTime = segment?.startTime ?? '';
    line.endTime = segment?.endTime ?? '';
    line.workerCount = segment?.workerCount ?? 1;
    line.byproductQuantity = byproduct?.quantity ?? null;
    line.byproductUnit = byproduct?.unit || byproductReportingUnit.value;
    line.byproductUnitPrice = byproduct?.unitPrice ?? null;
    line.costAllocationRatio = p.costAllocationRatio ?? null;
    line.batchNumber = view.batchNumber;
  }

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

  // G2 自定义字段: 从 payload.customFields 回填 (mirror isQidiao 的 payloadFields 回填模式)。
  // 只回填当前 enabled 的字段 key —— 若某字段后来被工序设置里关闭 (enabled=false), 值仍在
  // 后端 row_payload.customFields 里但本组件不再渲染对应列, 也就不回填 (重新启用后再次可见)。
  const savedCustomFields = p.customFields as Record<string, unknown> | undefined;
  if (savedCustomFields) {
    for (const def of enabledCustomFields.value) {
      if (def.key in savedCustomFields) {
        row.fields[def.key] = savedCustomFields[def.key] as string | number | null;
      }
    }
  }
  return row;
}

/**
 * 2B.2 多产出: base clientRowId 内的产出序号 (`${base}#${i}` → i)。非多产出行/无 `#` → -1
 * (兜底排最前, 理论不出现 —— 多产出组员必带 `#`)。
 */
function multiOutputMemberIndex(clientRowId: string): number {
  const idx = clientRowId.lastIndexOf('#');
  if (idx < 0) return -1;
  const n = Number(clientRowId.slice(idx + 1));
  return Number.isFinite(n) ? n : -1;
}

/**
 * 2B.2: 由一个已持久化的多产出成员行 view 还原一条 MultiOutputLine。品名/单位/成品|半成品
 * 优先取「当前」workflow 端口 (workflowPortId 匹配) —— 若该端口后来被重新配置/删除
 * (skuResolved=false 或端口消失), 退回 payload 里保存的原值, 不崩溃 (fool-proof Rule 5)。
 */
function multiOutputLineFromView(view: ProcessSheetRowView): MultiOutputLine {
  const p = view.payload;
  const portId = p.workflowPortId || '';
  const port = portId ? outputPorts.value.find((op) => op.workflowPortId === portId) : undefined;
  const segment = p.laborSegments?.[0];
  const byproduct = p.byproducts?.[0];
  return {
    workflowPortId: portId,
    materialNodeId: port?.materialNodeId ?? p.materialNodeId ?? '',
    productTypeId: p.productTypeId,
    materialName: port?.materialName || p.productTypeId,
    unit: port ? workflowPortDisplayUnit(port) : (p.unit || p.outputUnit || ''),
    gramsPerUnit: port?.gramsPerUnit ?? null,
    finished: port ? port.finished === true : p.finished === true,
    required: port?.required ?? true,
    selected: true,
    quantity: p.outputQuantity ?? null,
    startTime: segment?.startTime ?? '',
    endTime: segment?.endTime ?? '',
    workerCount: segment?.workerCount ?? 1,
    byproductQuantity: byproduct?.quantity ?? null,
    byproductUnit: byproduct?.unit || byproductReportingUnit.value,
    byproductUnitPrice: byproduct?.unitPrice ?? null,
    costAllocationRatio: p.costAllocationRatio ?? null,
    batchNumber: view.batchNumber,
  };
}

/**
 * 2B.2: 把后端 GET rows 返回的扁平行列表按多产出组归组显示。一次多产出报工在后端拆成
 * N 个持久化行 (clientRowId = `${base}#0..N-1`, 首行 #0 承载全部实际投入), 前端把它们
 * 合并还原成**一条** SheetRow (row.multiOutputs = N 项), 而不是 N 条独立的表格行 ——
 * 否则操作员会看到"同一次报工"被拆成好几行, 且再保存会因 clientRowId 不是 base 而对不上。
 * 非多产出行 (payload.multiOutputMember 不为 true) 原样走既有 hydrateRow, 不受影响。
 */
function buildDisplayRows(views: ProcessSheetRowView[]): SheetRow[] {
  if (!isMultiOutput.value) return views.map(hydrateRow);

  const groups = new Map<string, ProcessSheetRowView[]>();
  const singles: ProcessSheetRowView[] = [];
  for (const v of views) {
    const isMember = v.payload?.multiOutputMember === true;
    const base = isMember ? (v.payload.multiOutputBaseRowId || v.clientRowId.split('#')[0]) : null;
    if (!base) {
      singles.push(v);
      continue;
    }
    const arr = groups.get(base) ?? [];
    arr.push(v);
    groups.set(base, arr);
  }

  const result: SheetRow[] = singles.map(hydrateRow);
  for (const [base, members] of groups) {
    const sorted = [...members].sort(
      (a, b) => multiOutputMemberIndex(a.clientRowId) - multiOutputMemberIndex(b.clientRowId),
    );
    const first = sorted[0]; // #0 承载全部实际投入 (carryInputs=true, 见后端 synthesizeOutputRequest)
    // 用 base 覆盖 clientRowId 复用既有 hydrateRow 还原输入侧字段 (上游/原料/工时/自定义字段等,
    // 与单产出完全一致的逻辑) —— 再保存时 buildRequest 发的 clientRowId 就是这个 base。
    const row = hydrateRow({ ...first, clientRowId: base });
    const savedOutputs = sorted.map(multiOutputLineFromView);
    const savedByPort = new Map(savedOutputs.map((line) => [line.workflowPortId, line]));
    row.multiOutputs = initMultiOutputs().map((line) => savedByPort.get(line.workflowPortId) ?? line);
    savedOutputs.forEach((line) => {
      if (!row.multiOutputs.some((candidate) => candidate.workflowPortId === line.workflowPortId)) {
        row.multiOutputs.push(line);
      }
    });
    result.push(row);
  }
  return result;
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
      rows.value = buildDisplayRows(normalizedIncoming);
      return;
    }

    // Same plan/process: if the user has already added unsaved rows, don't overwrite them.
    // This keeps in-progress edits safe while still clearing rows when the drawer is reused
    // for another plan/process.
    const hasUserEdits = rows.value.some((r) => r.rowStatus === 'UNSAVED');
    if (hasUserEdits && rows.value.length > 0) return;
    rows.value = buildDisplayRows(normalizedIncoming);
  },
  { immediate: true, deep: false },
);

function addRow() {
  if (addRowBlockedReason.value) {
    ElMessage({ message: addRowBlockedReason.value, type: 'warning', duration: 0, showClose: true });
    return;
  }
  rows.value.push(blankRow());
}

// -------------------------------------------------------------------------
// 已小结行折叠 (BY_STOCK 小结后转结到批次，计划保持开放)
// -------------------------------------------------------------------------

/** 已小结 (interimSettledAt != null): 只读，默认折叠。 */
const settledRows = computed(() => rows.value.filter((r) => r.interimSettledAt != null));
function isReadOnlyRow(row: SheetRow): boolean {
  return row.submissionStatus === 'LEGACY' || row.materialized;
}
const historicalRows = computed(() => rows.value.filter((r) =>
  r.interimSettledAt == null && isReadOnlyRow(r),
));
/** 未小结 (interimSettledAt == null): 正常可编辑。 */
const activeRows  = computed(() => rows.value.filter((r) =>
  r.interimSettledAt == null && !isReadOnlyRow(r),
));

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
  // 2B.2 多产出: 单产出的 row.fields['output']/['after'] 从未被填 (多产出录入在 row.multiOutputs
  // 里), 用逐产出摘要取代, 而不是落进下面任何 archetype 分支返回误导性的 "—"。
  if (isMultiOutput.value) {
    return row.multiOutputs
      .map((o) => `${o.materialName} ${o.quantity ?? '—'}${o.unit}`)
      .join(' + ');
  }
  if (isXiuYou.value) {
    const out = row.fields['output'];
    return formatHistoricalProcessOutput(out as number | null, row);
  }
  if (isSingleUpstream.value) {
    const after = row.fields['after'];
    return formatHistoricalProcessOutput(after as number | null, row);
  }
  if (isQuSheTou.value) {
    const out = row.fields['output'];
    return formatHistoricalProcessOutput(out as number | null, row);
  }
  if (isShuZhi.value) {
    const out = row.fields['output'];
    return formatHistoricalProcessOutput(out as number | null, row);
  }
  if (isQidiao.value) {
    const n = finishedActualQuantity(row);
    return `实产 ${n ?? '—'} ${processUnits.value.outputUnit}`;
  }
  return '—';
}

function formatHistoricalProcessOutput(quantity: number | null, row: SheetRow): string {
  if (quantity == null) return '—';
  const target = processUnits.value.outputUnit.trim().toLowerCase();
  if (target === 'kg' || target === '千克' || target === '公斤') {
    const normalized = normalizeMassQuantityForReporting(Number(quantity), row.persistedOutputUnit);
    return formatProcessOutput(normalized.quantity, normalized.unit || processUnits.value.outputUnit);
  }
  return formatProcessOutput(quantity, processUnits.value.outputUnit);
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

function singleSourceUsage(row: SheetRow): number {
  if (isSingleUpstream.value) return (row.fields['before'] as number) ?? 0;
  if (isQuSheTou.value) return calcReverseInput(row) ?? 0;
  if (isShuZhi.value || isGenericUpstream.value) return (row.fields['input'] as number) ?? 0;
  if (isQidiao.value) return resolvedFinishedInputKg(row) ?? 0;
  return 0;
}

/**
 * 成品工序不再要求操作员重复填写「使用重量」。选中的上游来源本身就是本次要结转的
 * WIP/SFI/FG 库存，因此以该来源当前可用量作为实际投入；计数单位只有在 SKU 已配置
 * 标准克重时才允许折算。任何无法可靠解析的来源均返回 null，由正式报工入口 fail-closed。
 */
function sourceAvailableKg(
  quantity: number,
  unit: string | null | undefined,
  gramsPerUnit?: number | null,
): number | null {
  if (!Number.isFinite(quantity) || quantity <= 0) return null;
  const normalized = (unit || processUnits.value.inputUnit).trim().toLowerCase();
  if (normalized === 'kg' || normalized === '千克') return quantity;
  if (normalized === 'g' || normalized === '克') return quantity / 1000;
  if (isCountUnit(unit)) return boxAvailableKg(quantity, gramsPerUnit);
  return null;
}

function resolvedFinishedInputKg(row: SheetRow): number | null {
  if (!row.upstreamBatch) return null;
  if (row.upstreamFinishedGoods) {
    const source = fgOptions.value.find((item) => item.batchNumber === row.upstreamBatch);
    return source ? sourceAvailableKg(fgAvailable(source), source.unit, source.gramsPerUnit) : null;
  }
  if (row.upstreamSemiFinished) {
    const source = sfiOptions.value.find((item) => item.intermediateBatchNo === row.upstreamBatch);
    return source ? sourceAvailableKg(sfiAvailable(source), source.unit, source.gramsPerUnit) : null;
  }
  const source = props.upstreamItems.find((item) => item.batchNumber === row.upstreamBatch);
  return source ? sourceAvailableKg(source.remaining, source.unit) : null;
}

function formalSubmitBlockedReason(row: SheetRow): string | null {
  if (props.upstreamSubmissionReady === false) {
    return props.upstreamSubmissionMessage
      || `上游工序尚未正式报工，请先完成「${upstreamProcessName.value}」后再提交本道工序`;
  }
  if (requiresManualCostAllocation(row)) {
    const activeOutputs = row.multiOutputs.filter((output) => output.selected && output.quantity != null && output.quantity > 0);
    if (activeOutputs.some((output) => output.costAllocationRatio == null || output.costAllocationRatio <= 0)) {
      return '产出单位无法统一折算，请填写每项大于 0 的成本分摊比例';
    }
    const totalRatio = activeOutputs.reduce((sum, output) => sum + (output.costAllocationRatio || 0), 0);
    if (Math.abs(totalRatio - 100) > 0.01) return `成本分摊比例合计必须为 100%，当前为 ${Number(totalRatio.toFixed(4))}%`;
  }
  if (!isQidiao.value || isMultiSource.value) return null;
  const inputKg = resolvedFinishedInputKg(row);
  if (inputKg != null && inputKg > 0) return null;
  return '无法从所选上游库存确定实际投入量，请刷新库存或重新选择上游批次后再正式报工';
}

/** Mirrors the inputQuantity selected by buildRequest, for pre-save pot validation/preview. */
function potInputQuantity(row: SheetRow): number {
  if (isXiuYou.value) return usesAutoMaterialTotals(row) ? materialInputTotalKg(row) : (row.rawBatchQty ?? 0);
  if (isMultiSource.value) {
    const totalFeed = row.upstreamSources
      .filter((source) => source.selected)
      .reduce((sum, source) => sum + (source.feedQuantityKg || 0), 0);
    if (isSingleUpstream.value) return (row.fields['before'] as number) ?? totalFeed;
    if (isQuSheTou.value) return calcReverseInput(row) ?? totalFeed;
    if (isShuZhi.value || isGenericUpstream.value) return (row.fields['input'] as number) ?? totalFeed;
    if (isQidiao.value) return totalFeed;
  }
  return singleSourceUsage(row);
}

function calcYield(row: SheetRow): number | null {
  let input: number | null = null;
  let output: number | null = null;

  if (isXiuYou.value) {
    input = usesAutoMaterialTotals(row) ? materialInputTotalKg(row) : row.rawBatchQty;
    output = (row.fields['output'] as number) ?? null;
  } else if (isMultiSource.value) {
    input = row.upstreamSources
      .filter((source) => source.selected)
      .reduce((sum, src) => sum + (src.feedQuantityKg || 0), 0);
    output = (row.fields['output'] as number) ?? null;
  } else if (isSingleUpstream.value) {
    // 焯水 + 滚揉: before/after 字段
    input = (row.fields['before'] as number) ?? null;
    output = (row.fields['after'] as number) ?? null;
  } else if (isQuSheTou.value) {
    // 去舌苔: 分母是反推投入量 (scrap + output), 分子是 output
    input = calcReverseInput(row);
    output = (row.fields['output'] as number) ?? null;
  } else if (isShuZhi.value || isGenericUpstream.value) {
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
    const rawMinutes = (eh * 60 + em) - (sh * 60 + sm);
    const minutes = rawMinutes < 0 ? rawMinutes + 24 * 60 : rawMinutes;
    return sum + (minutes / 60) * (seg.workerCount || 0);
  }, 0);
}

function outputLineTotalHours(line: MultiOutputLine): number {
  if (!line.startTime || !line.endTime) return 0;
  const [sh, sm] = line.startTime.split(':').map(Number);
  const [eh, em] = line.endTime.split(':').map(Number);
  if (![sh, sm, eh, em].every(Number.isFinite)) return 0;
  const rawMinutes = (eh * 60 + em) - (sh * 60 + sm);
  const minutes = rawMinutes < 0 ? rawMinutes + 24 * 60 : rawMinutes;
  return (minutes / 60) * Math.max(1, line.workerCount || 1);
}

function outputLineYield(row: SheetRow, line: MultiOutputLine): number | null {
  const outputWeightKg = outputLineWeightKg(line);
  const inputWeightKg = reportingInputWeightKg(row);
  if (outputWeightKg != null && inputWeightKg != null && inputWeightKg > 0) {
    return Math.round((outputWeightKg / inputWeightKg) * 10000) / 100;
  }
  const comparableInput = reportingInputQuantityForUnit(row, line.unit);
  if (comparableInput == null || comparableInput <= 0 || line.quantity == null) return null;
  return Math.round((line.quantity / comparableInput) * 10000) / 100;
}

function outputLineLaborSegments(line: MultiOutputLine): LaborSegment[] | undefined {
  if (!line.startTime || !line.endTime) return undefined;
  return [{ startTime: line.startTime, endTime: line.endTime, workerCount: Math.max(1, line.workerCount || 1) }];
}

function outputLineByproducts(line: MultiOutputLine): ProcessSheetByproduct[] | undefined {
  if (line.byproductQuantity == null || line.byproductQuantity <= 0) return undefined;
  return [{
    name: '副产',
    quantity: line.byproductQuantity,
    unit: line.byproductUnit,
    ...(line.byproductUnitPrice != null ? { unitPrice: line.byproductUnitPrice } : {}),
  }];
}

function calcRemaining(row: SheetRow): number | null {
  // For SAVED rows with a batchNumber: look up in own-process inventory.
  // This is the authoritative value and matches what the 半成品库存 table shows.
  if (row.rowStatus === 'SAVED' && row.batchNumber && props.ownInventoryItems?.length) {
    const inv = props.ownInventoryItems.find((b) => b.batchNumber === row.batchNumber);
    if (inv != null) return inv.remaining;
  }
  // Fallback for single-source upstream unsaved rows: derive from selected source.
  if (isSingleSource.value) {
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
// 成品报工派生值：SKU/Workflow 单位与净重是唯一真值。
// -------------------------------------------------------------------------

const finishedOutputPort = computed(() => {
  const port = props.workflowContext?.output ?? null;
  return port?.finished ? port : null;
});

function finishedActualQuantity(row: SheetRow): number | null {
  const value = row.fields['actualProd'];
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function finishedSampleQuantity(row: SheetRow): number {
  const value = row.fields['sample'];
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function finishedInboundQuantity(row: SheetRow): number | null {
  const actual = finishedActualQuantity(row);
  if (actual == null) return null;
  return Math.max(0, actual - finishedSampleQuantity(row));
}

function quantityWeightKg(quantity: number | null, unit: string, gramsPerUnit: number | null): number | null {
  if (quantity == null) return null;
  const normalized = normalizedReportingUnit(unit);
  if (normalized === 'kg') return quantity;
  if (normalized === 'g') return quantity / 1000;
  if (gramsPerUnit == null || gramsPerUnit <= 0) return null;
  return quantity * gramsPerUnit / 1000;
}

function normalizedReportingUnit(unit: string): string {
  const normalized = unit.trim().toLowerCase();
  if (normalized === '千克' || normalized === '公斤') return 'kg';
  if (normalized === '克') return 'g';
  return normalized;
}

function canConvertOutputToKg(line: MultiOutputLine): boolean {
  const unit = normalizedReportingUnit(line.unit);
  return unit === 'kg' || unit === 'g' || (line.gramsPerUnit != null && line.gramsPerUnit > 0);
}

function requiresManualCostAllocation(row: SheetRow): boolean {
  const selectedOutputs = row.multiOutputs.filter((line) => line.selected);
  if (selectedOutputs.length <= 1) return false;
  if (selectedOutputs.every(canConvertOutputToKg)) return false;
  const dimensions = new Set(selectedOutputs.map((line) => {
    const unit = normalizedReportingUnit(line.unit);
    return unit === 'kg' || unit === 'g' ? 'mass' : unit;
  }));
  return dimensions.size > 1;
}

function reportingInputFacts(row: SheetRow): Array<{ quantity: number; unit: string; gramsPerUnit: number | null }> {
  if (usesAutoMaterialTotals(row)) {
    return row.materialInputTotals
      .filter((item) => item.selected && item.quantity != null && item.quantity > 0)
      .map((item) => {
        const port = workflowRawInputs.value.find((candidate) =>
          candidate.workflowPortId === item.workflowPortId || candidate.skuId === item.materialTypeId,
        );
        return { quantity: item.quantity!, unit: item.unit, gramsPerUnit: port?.gramsPerUnit ?? null };
      });
  }
  if (isMultiSource.value) {
    return row.upstreamSources
      .filter((source) => source.selected && source.feedQuantityKg > 0)
      .map((source) => {
        const port = sourcePort(source);
        return {
          quantity: source.feedQuantityKg,
          unit: sourcePortUnit(source),
          gramsPerUnit: port?.gramsPerUnit ?? null,
        };
      });
  }
  const quantity = potInputQuantity(row);
  const port = workflowUpstreamInputs.value[0];
  return quantity > 0 ? [{
    quantity,
    unit: port ? workflowPortDisplayUnit(port) : processUnits.value.inputUnit,
    gramsPerUnit: port?.gramsPerUnit ?? null,
  }] : [];
}

function reportingInputWeightKg(row: SheetRow): number | null {
  const facts = reportingInputFacts(row);
  if (!facts.length) return null;
  let total = 0;
  for (const fact of facts) {
    const weight = quantityWeightKg(fact.quantity, fact.unit, fact.gramsPerUnit);
    if (weight == null) return null;
    total += weight;
  }
  return total;
}

function reportingInputQuantityForUnit(row: SheetRow, unit: string): number | null {
  const target = normalizedReportingUnit(unit);
  const facts = reportingInputFacts(row);
  if (!facts.length || facts.some((fact) => normalizedReportingUnit(fact.unit) !== target)) return null;
  return facts.reduce((sum, fact) => sum + fact.quantity, 0);
}

function finishedWeightKg(row: SheetRow, inbound = false): number | null {
  const quantity = inbound ? finishedInboundQuantity(row) : finishedActualQuantity(row);
  return quantityWeightKg(
    quantity,
    processUnits.value.outputUnit,
    finishedOutputPort.value?.gramsPerUnit ?? null,
  );
}

function formattedWeight(value: number | null): string {
  if (value == null) return '未配置单位净重，无法计算成品重量';
  return `${Number(value.toFixed(6))} kg`;
}

function unitNetWeightText(): string {
  const unit = processUnits.value.outputUnit;
  if (unit.trim().toLowerCase() === 'kg' || unit.trim() === '千克') return '单位净重：按 kg 记账';
  const grams = finishedOutputPort.value?.gramsPerUnit;
  return grams != null && grams > 0 ? `单位净重：${Number(grams.toFixed(3))}g/${unit}` : '单位净重：未配置';
}

function outputLineWeightKg(line: MultiOutputLine): number | null {
  return quantityWeightKg(line.quantity, line.unit, line.gramsPerUnit);
}

function outputLinePrecision(line: MultiOutputLine): number {
  return line.finished && line.unit.trim().toLowerCase() !== 'kg' && line.unit.trim() !== '千克' ? 0 : 6;
}

function fieldPrecision(key: string): number {
  if (!isQidiao.value || (key !== 'actualProd' && key !== 'sample')) return 2;
  const unit = processUnits.value.outputUnit.trim().toLowerCase();
  return unit === 'kg' || unit === '千克' ? 6 : 0;
}

function usesAutoMaterialTotals(row: SheetRow): boolean {
  return isXiuYou.value && workflowRawInputs.value.length > 0 && !row.legacyExplicitRawInput;
}

function submittedMaterialInputTotals(row: SheetRow): MaterialInputTotal[] {
  return row.materialInputTotals
    .filter((item) => item.selected && item.quantity != null && item.quantity > 0)
    .map(({ materialName: _materialName, selected: _selected, quantity, ...item }) => ({ ...item, quantity: quantity! }));
}

function materialInputTotalKg(row: SheetRow): number {
  // Workflow 原料端口可能同时使用 g / kg；页面汇总必须与后端库存分摊使用同一质量口径。
  // 非质量维度保留旧兼容值，正式提交仍由后端按端口快照做最终校验与换算。
  return reportingInputWeightKg(row)
    ?? submittedMaterialInputTotals(row).reduce((sum, item) => sum + item.quantity, 0);
}

function upstreamWarning(row: SheetRow): string | null {
  if (isSingleSource.value) {
    const usage = singleSourceUsage(row);
    if (row.upstreamFinishedGoods) {
      // ①c FG 投料 max 守卫: 计数单位(盒装)成品经每盒克重折算 kg 可投量; kg 源按原 kg 比较。
      const fg = fgOptions.value.find((f) => f.batchNumber === row.upstreamBatch);
      if (!fg) return null;
      if (isCountUnit(fg.unit)) return countUnitFeedWarning(fg.unit, fg.gramsPerUnit, fgAvailable(fg), usage, '该成品来源');
      if (usage > fgAvailable(fg)) return `用量 ${usage}${processUnits.value.inputUnit} 超出成品库存余 ${fgAvailable(fg)}${fg.unit || 'kg'}`;
      return null;
    }
    if (row.upstreamSemiFinished) {
      // SFI 投料 max 守卫: 计数单位(盒装)半成品经每盒克重折算; kg 源按原 kg 比较。
      const sfi = sfiOptions.value.find((s) => s.intermediateBatchNo === row.upstreamBatch);
      if (!sfi) return null;
      if (isCountUnit(sfi.unit)) return countUnitFeedWarning(sfi.unit, sfi.gramsPerUnit, sfiAvailable(sfi), usage, '该半成品来源');
      if (usage > sfiAvailable(sfi)) return `用量 ${usage}${processUnits.value.inputUnit} 超出半成品库存余 ${sfiAvailable(sfi)}${sfi.unit || 'kg'}`;
      return null;
    }
    const inv = props.upstreamItems.find((b) => b.batchNumber === row.upstreamBatch);
    if (!inv) return null;
    if (usage > inv.remaining) return `用量 ${usage}${processUnits.value.inputUnit} 超出剩余 ${inv.remaining}${inv.unit || processUnits.value.inputUnit}`;
  }
  if (isMultiSource.value) {
    const warnings: string[] = [];
    for (const src of row.upstreamSources) {
      if (!src.selected) continue;
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
        warnings.push(`${src.sourceBatchNumber} 超出剩余 ${inv.remaining}${inv.unit || processUnits.value.inputUnit}`);
      }
    }
    return warnings.length ? warnings.join('; ') : null;
  }
  return null;
}

// -------------------------------------------------------------------------
// Save-disabled reason (fool-proof gate)
// -------------------------------------------------------------------------
function draftSaveDisabledReason(row: SheetRow): string | null {
  if (isReadOnlyRow(row)) return '已入账或历史数据只读，不能直接修改';
  if (row.submissionStatus === 'SUBMITTED') return '该行已正式报工，不能直接修改';
  if (unresolvedWorkflowRawInputs.value.length > 0) {
    return '当前计划的 Workflow 原料已失效，请先重新绑定原料并重新创建生产计划';
  }
  return null;
}

function rowCompletenessReason(row: SheetRow): string | null {
  const draftReason = draftSaveDisabledReason(row);
  if (draftReason) return draftReason;
  if (isPortOutputMode.value && !row.productionDate) return '请选择生产日期';
  const inputGroupReason = selectionGroupReason(row, 'INPUT');
  if (inputGroupReason) return inputGroupReason;
  const outputGroupReason = selectionGroupReason(row, 'OUTPUT');
  if (outputGroupReason) return outputGroupReason;
  // 2B.2 多产出: 单产出字段(下方 'output'/'after'/'usedWeight'+sumBoxes 等)已被「多产出」录入块
  // 取代、不再渲染 —— 下方 `&& !isMultiOutput.value` 跳过那些"填了单产出输出吗"检查(它们永远
  // 是 null, 会永久卡住保存), 改用本函数末尾的多产出专属检查。输入侧完整性检查(上游批次/原料
  // 批次/投入重量等)照常执行, 不受影响 —— 多产出的投入分配方式与单产出完全一致。
  if (isXiuYou.value) {
    if (usesAutoMaterialTotals(row)) {
      const missing = row.materialInputTotals.find((item) => item.selected && (item.quantity == null || item.quantity <= 0));
      if (missing) return `请填写「${missing.materialName}」的投料总量`;
    } else {
      if (!row.rawBatchId) return '请选择原料批次';
      if (row.rawBatchQty == null) return '请填写出库重量';
    }
    if (!isPortOutputMode.value && (row.fields['output'] as number) == null) return '请填写产出数量';
  } else if (isMultiSource.value) {
    const selectedSources = row.upstreamSources.filter((source) => source.selected);
    if (selectedSources.length === 0 && workflowUpstreamInputs.value.length === 0) return '请添加上游来源批';
    if (selectedSources.some((s) => !s.sourceBatchNumber || !s.feedQuantityKg)) return '请补全所有已选来源批次及投料量';
    if (workflowUpstreamInputs.value.length > 0 && selectedSources.some((s) => !s.workflowPortId || !s.skuId)) {
      return 'Workflow 投入来源缺少端口身份，请重新打开报工组后再填写';
    }
    if (isSingleUpstream.value) {
      const processLabel = ownProcessName.value;
      if ((row.fields['before'] as number) == null) return `请填写${processLabel}前重量`;
      if (!isPortOutputMode.value && (row.fields['after'] as number) == null) return `请填写${processLabel}后重量`;
    } else if (isQuSheTou.value) {
      if (!isPortOutputMode.value) {
        if ((row.fields['scrap'] as number) == null) return '请填写碎肉重量';
        if ((row.fields['output'] as number) == null) return '请填写产出重量';
      }
    } else if (isShuZhi.value || isGenericUpstream.value) {
      if (!isPortOutputMode.value && (row.fields['output'] as number) == null) return '请填写产出数量';
    } else if (isQidiao.value) {
      if (!isPortOutputMode.value && (finishedActualQuantity(row) ?? 0) <= 0) return '请填写实际生产数量';
      if (!isPortOutputMode.value && finishedSampleQuantity(row) > (finishedActualQuantity(row) ?? 0)) {
        return '留样数量不能大于实际生产数量';
      }
    }
  } else if (isSingleSource.value) {
    if (!row.upstreamBatch) return `请选择${upstreamProcessName.value}批次`;
    if (isSingleUpstream.value) {
      const processLabel = ownProcessName.value;
      if ((row.fields['before'] as number) == null) return `请填写${processLabel}前重量`;
      if (!isPortOutputMode.value && (row.fields['after'] as number) == null) return `请填写${processLabel}后重量`;
    } else if (isQuSheTou.value) {
      if (!isPortOutputMode.value) {
        if ((row.fields['scrap'] as number) == null) return '请填写碎肉重量';
        if ((row.fields['output'] as number) == null) return '请填写产出重量';
      }
    } else if (isShuZhi.value || isGenericUpstream.value) {
      if ((row.fields['input'] as number) == null) return '请填写投入重量';
      if (!isPortOutputMode.value && (row.fields['output'] as number) == null) return '请填写产出数量';
    } else if (isQidiao.value) {
      if (!isPortOutputMode.value && (finishedActualQuantity(row) ?? 0) <= 0) return '请填写实际生产数量';
      if (!isPortOutputMode.value && finishedSampleQuantity(row) > (finishedActualQuantity(row) ?? 0)) {
        return '留样数量不能大于实际生产数量';
      }
    }
  }
  // 2B.2 多产出: required 端口必须填数量；可选端口允许留空且不会发送。
  if (isPortOutputMode.value) {
    if (row.multiOutputs.length === 0) return '本工序 workflow 未配置产出端口, 无法录入';
    const missing = row.multiOutputs.find((o) => o.selected && (o.quantity == null || o.quantity <= 0));
    if (missing) return `请填写「${missing.materialName}」的产出数量`;
    const incompleteTime = row.multiOutputs.find((o) => o.selected && Boolean(o.startTime) !== Boolean(o.endTime));
    if (incompleteTime) return `请补全「${incompleteTime.materialName}」的开始和结束时间`;
  }
  if (needsSeasoningInputKg.value) {
    try {
      buildEqualPotWeightsKg(
        potInputQuantity(row),
        processUnits.value.inputUnit,
        needsPotCount.value ? row.potCount : 1,
      );
    } catch (error) {
      return error instanceof Error ? error.message : '锅数配置无效';
    }
  }
  // 🔒 防呆 Rule 1 — 超投/单位不匹配 must-fix 不是 advisory: upstreamWarning() 命中时同样 disable 保存,
  // 不能只在提示区显示警告文字却仍放行保存(2026-07-02 GATE-HANDBACK 阻断项②)。
  const w = upstreamWarning(row);
  if (w) return w;
  return null;
}

function submitDisabledReason(row: SheetRow): string | null {
  return rowCompletenessReason(row) || formalSubmitBlockedReason(row);
}

// -------------------------------------------------------------------------
// Build request
// -------------------------------------------------------------------------
function buildRequest(row: SheetRow): ProcessSheetRowRequest & Record<string, unknown> {
  // 2B (BLOCKING fix): when this process carries a workflow output port, `finished`/`unit`
  // MUST come from the port — not the name-keyword archetype heuristic (processCode === 'qidiao'
  // / hardcoded 'kg'/'盒' below). Backend ProcessSheetServiceImpl#validateWorkflowRowIfApplicable
  // 409s (WORKFLOW_ROW_OUTPUT_KIND_MISMATCH / _UNIT_MISMATCH) when the saved row disagrees with
  // the port, which previously dead-ended the clerk on every save for any workflow whose
  // finishing process name isn't literally "气调" (or whose output unit isn't 'kg'/'盒').
  // Legacy rows (workflowContext null/undefined) are completely unaffected — same as before.
  const wfOutput = props.workflowContext?.output ?? null;
  const base: ProcessSheetRowRequest & Record<string, unknown> = {
    clientRowId: row.clientRowId,
    processCode: props.processCode,
    processOrder: props.processOrder,
    productTypeId: wfOutput?.skuId || props.productTypeId,
    batchNumber: row.batchNumber ?? undefined,
    // ⭐ 气调成品批 (legacy archetype heuristic) — overridden below by the workflow port when present.
    finished: wfOutput ? wfOutput.finished === true : props.processCode === 'qidiao',
    outputQuantity: 0,
    inputUnit: processUnits.value.inputUnit,
    outputUnit: processUnits.value.outputUnit,
    unit: processUnits.value.outputUnit,
    seasoningStep: needsSeasoningInputKg.value,
    laborSegments: !isPortOutputMode.value && row.laborSegments.length ? row.laborSegments : undefined,
  };

  // Append daterange fields so the backend stores them in row_payload JSON.
  for (const col of cols.value) {
    if (col.type === 'daterange') {
      base[col.key] = row.fields[col.key] ?? null;
    }
  }

  // 跨天: 取本工序日期列(daterange)的开始日作 processDate →
  // 后端把成本报工(SEASONING/LABOR)归到该工序真实操作日 (非录入当天)。
  if (isPortOutputMode.value) {
    base.processDate = row.productionDate;
  } else {
    const dateCol = cols.value.find((c) => c.type === 'daterange' || c.type === 'date');
    if (dateCol) {
      const v = row.fields[dateCol.key];
      const start = Array.isArray(v) ? v[0] : (typeof v === 'string' ? v : null);
      if (start) base.processDate = start;
    }
  }

  if (isXiuYou.value) {
    if (usesAutoMaterialTotals(row)) {
      const totals = submittedMaterialInputTotals(row);
      base.materialInputTotals = totals.length ? totals : undefined;
      base.inputQuantity = totals.length ? materialInputTotalKg(row) : undefined;
    } else {
      base.rawMaterialInputs = [{ materialBatchId: row.rawBatchId, quantity: row.rawBatchQty! }];
      base.inputQuantity = row.rawBatchQty ?? undefined;
    }
    base.outputQuantity = (row.fields['output'] as number) ?? 0;
    // SP-G G3c: 副产 (修油 — 肥油等)
    const bpQty = (row.fields['byproductQty'] as number) ?? 0;
    if (bpQty > 0) {
      const bpPrice = (row.fields['byproductPrice'] as number) ?? undefined;
      base.byproducts = [{ name: '副产', quantity: bpQty, unit: 'kg', ...(bpPrice != null ? { unitPrice: bpPrice } : {}) }];
    }
  } else if (isMultiSource.value) {
    const totalFeed = row.upstreamSources
      .filter((source) => source.selected)
      .reduce((s, r) => s + (r.feedQuantityKg || 0), 0);
    base.upstreamSources = submittedUpstreamSources(row);
    if (isSingleUpstream.value) {
      const before = (row.fields['before'] as number) ?? totalFeed;
      base.inputQuantity = before;
      base.outputQuantity = (row.fields['after'] as number) ?? 0;
      const bpQty = (row.fields['byproductQty'] as number) ?? 0;
      if (bpQty > 0) {
        const bpPrice = (row.fields['byproductPrice'] as number) ?? undefined;
        base.byproducts = [{ name: '副产', quantity: bpQty, unit: 'kg', ...(bpPrice != null ? { unitPrice: bpPrice } : {}) }];
      }
    } else if (isQuSheTou.value) {
      const output = (row.fields['output'] as number) ?? 0;
      const scrap = (row.fields['scrap'] as number) ?? 0;
      const inputQty = scrap + output;
      base.inputQuantity = inputQty || totalFeed;
      base.outputQuantity = output;
    } else if (isShuZhi.value || isGenericUpstream.value) {
      base.inputQuantity = (row.fields['input'] as number) ?? totalFeed;
      base.outputQuantity = (row.fields['output'] as number) ?? 0;
    } else if (isQidiao.value) {
      const actualProd = finishedActualQuantity(row) ?? 0;
      base.inputQuantity = totalFeed || undefined;
      base.outputQuantity = actualProd;
      const sample = finishedSampleQuantity(row);
      if (sample > 0) {
        base.sampleRetainQuantity = Math.round(sample);
      }
      base.actualProd = actualProd;
      base.sample = sample;
      base.storage = finishedInboundQuantity(row);
      base.remainBox = finishedInboundQuantity(row);
      base.productWeight = finishedWeightKg(row);
      base.inboundWeight = finishedWeightKg(row, true);
    }
  } else if (isSingleUpstream.value) {
    // 焯水 + 滚揉: 结构相同. feedQuantityKg = before (领用量 = 投入量).
    // semiFinished: 单上游选了半成品库存 (SFI) → 后端 ③=F 纯 SFI 路径 (SAVED_SFI, 小结 SFI in/out)。
    // finishedGoods (①c): 单上游选了成品库存 (FG) → 后端小结 consumeForFeedStrict 严格扣减成品。
    base.upstreamSources = [{ sourceBatchNumber: row.upstreamBatch, feedQuantityKg: (row.fields['before'] as number) ?? 0, semiFinished: row.upstreamSemiFinished, finishedGoods: row.upstreamFinishedGoods, ...singleWorkflowSourceIdentity() }];
    base.inputQuantity = (row.fields['before'] as number) ?? undefined;
    base.outputQuantity = (row.fields['after'] as number) ?? 0;
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
    base.upstreamSources = [{ sourceBatchNumber: row.upstreamBatch, feedQuantityKg: inputQty, semiFinished: row.upstreamSemiFinished, finishedGoods: row.upstreamFinishedGoods, ...singleWorkflowSourceIdentity() }];
    base.inputQuantity = inputQty;
    base.outputQuantity = output;
  } else if (isShuZhi.value || isGenericUpstream.value) {
    const inputQty = (row.fields['input'] as number) ?? 0;
    base.upstreamSources = [{
      sourceBatchNumber: row.upstreamBatch,
      feedQuantityKg: inputQty,
      semiFinished: row.upstreamSemiFinished,
      finishedGoods: row.upstreamFinishedGoods,
      ...singleWorkflowSourceIdentity(),
    }];
    base.inputQuantity = inputQty;
    base.outputQuantity = (row.fields['output'] as number) ?? 0;
  } else if (isQidiao.value) {
    const actualProd = finishedActualQuantity(row) ?? 0;
    const inputKg = resolvedFinishedInputKg(row);
    base.upstreamSources = [{
      sourceBatchNumber: row.upstreamBatch,
      // 操作员不重复填写“使用重量”；由所选上游库存的实时可用量自动带入。
      // 正式报工前 formalSubmitBlockedReason 会保证这里绝不以 0/null 提交。
      feedQuantityKg: inputKg ?? 0,
      semiFinished: row.upstreamSemiFinished,
      finishedGoods: row.upstreamFinishedGoods,
      ...singleWorkflowSourceIdentity(),
    }];
    base.inputQuantity = inputKg ?? undefined;
    base.outputQuantity = actualProd;
    const sample = finishedSampleQuantity(row);
    if (sample > 0) {
      base.sampleRetainQuantity = Math.round(sample);
    }
    base.actualProd = actualProd;
    base.sample = sample;
    base.storage = finishedInboundQuantity(row);
    base.remainBox = finishedInboundQuantity(row);
    base.productWeight = finishedWeightKg(row);
    base.inboundWeight = finishedWeightKg(row, true);
  }

  // G2 自定义字段: 收集**启用**字段进 customFields map。后端按 WorkProcess.customFieldSchema
  // 白名单校验 key (在 schema 即放行, 不校验 value); 前端只对 enabledCustomFields 发 key,
  // 天然不会给未开启自定义字段的工序发多余 key。
  //
  // B (2026-07-09 让"清空"真生效): 启用字段**即使单元格为空也带上该 key (值发 null)**, 不再省略。
  //   与后端 mergeCustomFieldsFromPrior 的语义配合:
  //   - 启用字段清空 → 发 {key: null} → putAll 覆盖旧值成 null → 真清掉 ✅
  //   - 启用但未改的字段 → hydrateRow 已载入旧值, 原样回发 → 保留 ✅
  //   - 禁用字段 → 不在 enabledCustomFields, 仍缺席 → 后端保留旧值 (F2 不变) ✅
  //   若之前省略空值, 后端会把"缺席 key"当"保留旧值", 导致启用字段清不掉 (F2(b) 的 wart)。
  if (enabledCustomFields.value.length > 0) {
    const customFields: Record<string, unknown> = {};
    for (const def of enabledCustomFields.value) {
      const v = row.fields[def.key];
      // 空单元格显式发 null (可清空); 非空发原值。绝不省略启用字段的 key。
      customFields[def.key] = (v === undefined || v === '') ? null : v;
    }
    base.customFields = customFields;
  }

  // Workflow 端口在所有 archetype 分支之后再次覆盖，确保请求的 SKU 与三处单位只有一个来源。
  if (wfOutput?.unit) {
    base.productTypeId = wfOutput.skuId;
    base.inputUnit = processUnits.value.inputUnit;
    base.outputUnit = processUnits.value.outputUnit;
    base.unit = processUnits.value.outputUnit;
  }

  // 2B.2 多产出 (fan-out): 产品/端口由 workflow 产出端口固定 (只读), 操作员只填数量 — 覆盖
  // 掉上面任何 archetype 分支写的单产出 outputQuantity/unit/finished/productTypeId (backend
  // ProcessSheetServiceImpl 一看到 outputs.length>1 就直接走 saveMultiOutputRow 分支, 不再理会
  // 顶层这些字段; 顶层 outputQuantity 仅需满足 @NotNull, 这里发 Σquantity 作诚实展示值)。
  // 输入侧分配 (rawMaterialInputs/upstreamSources/inputQuantity/laborSegments 等, 上面各 archetype
  // 分支已按原逻辑填好) 原样发送不变 —— 后端首产出行(#0)承载全部实际投入, 一次全量扣减。
  if (isMultiOutput.value) {
    const submittedOutputs = row.multiOutputs.filter((o) => o.selected && o.quantity != null && o.quantity > 0);
    base.outputs = submittedOutputs.map((o) => {
      const productWeight = o.finished ? outputLineWeightKg(o) : null;
      return {
        productTypeId: o.productTypeId,
        workflowPortId: o.workflowPortId || undefined,
        materialNodeId: o.materialNodeId || undefined,
        quantity: o.quantity ?? 0,
        unit: o.unit || undefined,
        finished: o.finished,
        laborSegments: outputLineLaborSegments(o),
        byproducts: outputLineByproducts(o),
        ...(requiresManualCostAllocation(row) && o.costAllocationRatio != null
          ? { costAllocationRatio: o.costAllocationRatio }
          : {}),
        ...(productWeight != null ? { productWeight } : {}),
      };
    });
    // 顶层实际产量只代表 Workflow 主产出，不能把不同单位的产出相加。
    base.outputQuantity = submittedOutputs.find(
      (o) => o.workflowPortId === props.workflowContext?.output?.workflowPortId,
    )?.quantity ?? 0;
  } else if (isPortOutputMode.value && row.multiOutputs[0]) {
    // 单产出与多产出共用同一行编辑器，但继续发送旧顶层契约，保证历史后端兼容。
    const output = row.multiOutputs[0];
    const productWeight = output.finished ? outputLineWeightKg(output) : null;
    base.productTypeId = output.productTypeId;
    base.workflowPortId = output.workflowPortId || undefined;
    base.materialNodeId = output.materialNodeId || undefined;
    base.outputQuantity = output.quantity ?? 0;
    base.outputUnit = output.unit;
    base.unit = output.unit;
    base.finished = output.finished;
    base.laborSegments = outputLineLaborSegments(output);
    base.byproducts = outputLineByproducts(output);
    if (productWeight != null) base.productWeight = productWeight;
  }

  if (needsSeasoningInputKg.value) {
    const inputQuantity = Number(base.inputQuantity);
    base.potCount = needsPotCount.value ? row.potCount : 1;
    base.potRawKgs = buildEqualPotWeightsKg(
      inputQuantity,
      processUnits.value.inputUnit,
      needsPotCount.value ? row.potCount : 1,
    );
  }

  return base;
}

// -------------------------------------------------------------------------
// Save / delete handlers
// -------------------------------------------------------------------------
function formalSubmitSummary(row: SheetRow): string {
  const inputs = usesAutoMaterialTotals(row)
    ? row.materialInputTotals
      .filter((item) => item.selected && item.quantity != null && item.quantity > 0)
      .map((item) => `${item.materialName} ${item.quantity}${item.unit}`)
    : row.upstreamSources.some((item) => item.selected)
      ? row.upstreamSources.filter((item) => item.selected).map((item) => `${item.sourceBatchNumber} ${item.feedQuantityKg}${processUnits.value.inputUnit}`)
      : row.upstreamBatch
        ? [`${row.upstreamBatch} ${singleSourceUsage(row)}${processUnits.value.inputUnit}`]
        : row.rawBatchQty != null
          ? [`原料 ${row.rawBatchQty}${processUnits.value.inputUnit}`]
          : [];
  const outputs = row.multiOutputs
    .filter((item) => item.selected && item.quantity != null && item.quantity > 0)
    .map((item) => `${item.materialName} ${item.quantity}${item.unit}`);
  return [
    `生产日期：${row.productionDate || '未填写'}`,
    `库存扣减：${inputs.join('；') || '无'}`,
    `产出入库：${outputs.join('；') || '无'}`,
    '投入明细按本次报工组只扣减一次，不会按产出数量重复扣减。',
  ].join('\n');
}

async function handleSave(row: SheetRow, action: 'draft' | 'submit') {
  if (row.saving) return;
  const reason = action === 'draft' ? draftSaveDisabledReason(row) : submitDisabledReason(row);
  if (reason) {
    if (action === 'submit') row.blockingMessage = reason;
    // 防呆 4位一体: type:error (非 warning) + sticky (duration:0) + showClose + next-action 明示
    ElMessage({ message: reason, type: 'error', duration: 0, showClose: true });
    return;
  }
  if (action === 'submit') {
    if (isPortOutputMode.value) {
      try {
        await ElMessageBox.confirm(formalSubmitSummary(row), '确认正式报工', {
          confirmButtonText: '确认报工',
          cancelButtonText: '返回核对',
          type: 'warning',
          distinguishCancelAndClose: true,
        });
      } catch {
        return;
      }
    }
  }
  row.saving = true;
  if (action === 'submit') row.blockingMessage = null;
  try {
    const req = buildRequest(row);
    const resp = action === 'draft'
      ? await saveDraftRow(props.factoryId, props.planId, req)
      : await submitRow(props.factoryId, props.planId, req);
    const result = resp.data;
    if (result?.batchNumber) row.batchNumber = result.batchNumber;
    // 2B.2 多产出: 逐产出批次号回填 (按 workflowPortId 对齐; 缺失时按序号兜底, 理论不出现)。
    if (isMultiOutput.value && result?.outputs?.length) {
      const byPort = new Map(result.outputs.map((o) => [o.workflowPortId, o]));
      row.multiOutputs = row.multiOutputs.map((o, i) => {
        const matched = byPort.get(o.workflowPortId) ?? result.outputs![i];
        return matched ? { ...o, batchNumber: matched.batchNumber } : o;
      });
    }
    row.submissionStatus = result?.submissionStatus ?? (action === 'submit' ? 'SUBMITTED' : 'DRAFT');
    row.rowStatus = action === 'submit' && result?.materialized ? 'SAVED' : 'DRAFT';
    if (result?.warnings?.length) {
      ElMessage({ message: `${action === 'draft' ? '草稿已保存' : '正式报工成功'}(含提示): ` + result.warnings.join('; '), type: 'warning', duration: 0, showClose: true });
    } else {
      ElMessage.success(`${action === 'draft' ? '草稿已保存' : '正式报工成功'}${row.batchNumber ? ' — ' + row.batchNumber : ''}`);
    }
    emit('row-saved', row.submissionStatus);
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : (action === 'draft' ? '草稿保存失败' : '正式报工失败');
    const code = typeof e === 'object' && e != null && 'code' in e ? String((e as { code?: unknown }).code ?? '') : '';
    if (action === 'submit' && code === 'PRODUCTION_STOCK_SHORTAGE') {
      row.blockingMessage = msg || '当前只能保存草稿，生产库中投料量不足，请联系仓管补料';
      ElMessage({ message: row.blockingMessage, type: 'error', duration: 0, showClose: true });
      return;
    }
    // 并发双提交/慢响应重试: 行已被首个请求保存成功(后端 409 + 回滚 loser), 幂等当成功处理,
    // 不给用户看错误 (fool-proof Rule 4 幂等防重复)。刷新本行状态即可。
    if (/该行已存在|并发提交/.test(msg)) {
      row.rowStatus = 'SAVED';
      row.submissionStatus = action === 'submit' ? 'SUBMITTED' : 'DRAFT';
      ElMessage.success(action === 'submit' ? '正式报工成功' : '草稿已保存');
      emit('row-saved', row.submissionStatus);
    } else {
      ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
    }
  } finally {
    row.saving = false;
  }
}

async function handleDelete(row: SheetRow) {
  if (isReadOnlyRow(row)) {
    ElMessage({ message: '已入账或历史数据只读，不能删除', type: 'error', duration: 0, showClose: true });
    return;
  }
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
    // 2B.2 多产出: 后端从不持久化裸 base clientRowId (只有 base#0..N-1 各产出行) —— 删除端点
    // 按精确 clientRowId 查找, 传裸 base 会 404 "工序行不存在"。传首个成员 base#0 的真实
    // clientRowId, 后端据其 payload.multiOutputBaseRowId 反查同组、级联删除整组 (含反物化/
    // SFI冲销), 不会留下幻库存。
    const targetClientRowId = isMultiOutput.value && row.multiOutputs.length > 0
      ? `${row.clientRowId}#0`
      : row.clientRowId;
    await deleteRow(props.factoryId, props.planId, targetClientRowId);
    rows.value = rows.value.filter((r) => r !== row);
    emit('row-saved', null);
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
    // 2B.2 多产出: 操作记录按持久化行的真实 clientRowId 记 (base#0..N-1), 裸 base 查不到任何
    // 记录。首个成员 (#0) 承载全部实际投入, 是本次报工的代表行, 取它的历史。
    const targetClientRowId = isMultiOutput.value && row.multiOutputs.length > 0
      ? `${row.clientRowId}#0`
      : row.clientRowId;
    const resp = await getRowHistory(props.factoryId, props.planId, props.processCode, targetClientRowId);
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
function addUpstreamSource(row: SheetRow, template?: SelectableUpstreamRef) {
  const port = template ? sourcePort(template) : workflowUpstreamInputs.value[0];
  row.upstreamSources = [...row.upstreamSources, blankUpstreamSource(port, template?.selected)];
}
function removeUpstreamSource(row: SheetRow, idx: number) {
  const current = row.upstreamSources[idx];
  if (current?.workflowPortId) {
    const samePortCount = row.upstreamSources.filter((source) => source.workflowPortId === current.workflowPortId).length;
    if (samePortCount <= 1) {
      row.upstreamSources[idx] = blankUpstreamSource(sourcePort(current), current.selected);
      return;
    }
  }
  const next = [...row.upstreamSources];
  next.splice(idx, 1);
  row.upstreamSources = next;
}

function sourcePortName(src: UpstreamRef): string {
  const port = sourcePort(src);
  return port?.materialName || port?.skuId || '上游物料';
}

function sourcePortUnit(src: UpstreamRef): string {
  const port = sourcePort(src);
  return port?.unit?.trim() || processUnits.value.inputUnit;
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

const addRowBlockedReason = computed(() => {
  if (!props.workflowContext || props.isFirstProcess || !supportsUpstreamSources.value) return null;
  if (sfiLoading.value || fgLoading.value) return '正在加载可用上游库存，请稍候';
  const hasSource = props.upstreamItems.some((item) => item.remaining > 0)
    || sfiOptions.value.length > 0
    || fgOptions.value.length > 0;
  return hasSource ? null : '暂无可用上游库存，请先完成上游报工或联系仓管补料';
});

/**
 * 是否提供「半成品库存(SFI)」投料选项。
 *
 * config-driven (张权 R4): 该工序被配置为「半成品注入工序」(allowSemiFinishedInjection=true) → 显 picker,
 * 让客户自己决定注入点 (如把「滚揉」标为注入工序, 从中段起步选库里已有半成品/成品接续生产)。
 *
 * archetype 兜底 (back-compat): 现有混锅道 (熟制/气调) + 单上游道 (焯水/滚揉/去舌苔) 保持显 picker,
 * 保证历史产品工序零回归 —— 即使未配置 flag 也不丢失现有能力。
 */
const showSfi = computed(() => supportsExternalStockFeed.value
  && (props.allowSemiFinishedInjection || supportsUpstreamSources.value));
/** ①c 是否提供「成品库存(FG)」投料选项；必须按产品工序显式开启。 */
const showFg = computed(() => showSfi.value && props.allowFinishedGoodsSource === true);

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
    `余${item.remaining}${item.unit || processUnits.value.inputUnit}`, costText(item.unitPrice)];
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

function sourceMatchesSku(actualProductTypeId: string | null | undefined, src: UpstreamRef): boolean {
  // 旧库存投影没有 productTypeId 时继续显示，由后端在正式保存时做最终端口校验。
  return !src.skuId || !actualProductTypeId || actualProductTypeId === src.skuId;
}

function wipOptionsForSource(src: UpstreamRef): ProcessSheetInventoryItem[] {
  return wipOptionsDisplay.value.filter((item) => sourceMatchesSku(item.productTypeId, src));
}

function sfiOptionsForSource(src: UpstreamRef): SemiFinishedStockItem[] {
  return sfiOptionsDisplay.value.filter((item) => sourceMatchesSku(item.productTypeId, src));
}

function fgOptionsForSource(src: UpstreamRef): FinishedGoodsStockItem[] {
  return fgOptionsDisplay.value.filter((item) => sourceMatchesSku(item.productTypeId, src));
}
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
  if (wip) return `余${wip.remaining}${wip.unit || processUnits.value.inputUnit}`;
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
}

function potSplitHint(row: SheetRow): string {
  try {
    const weights = buildEqualPotWeightsKg(potInputQuantity(row), processUnits.value.inputUnit, row.potCount);
    return `系统将投入量等分为 ${row.potCount} 锅，每锅 ${weights[0].toFixed(2)} kg`;
  } catch (error) {
    return error instanceof Error ? error.message : '请先填写投入量';
  }
}

watch(
  () => [props.factoryId, props.processCode, props.productTypeId, props.allowSemiFinishedInjection, props.allowFinishedGoodsSource,
    // raw-centric: workflow 原料类型到位后必须重跑 loadRawBatches (否则 workflowRawInputs 尚空 → 误按 productTypeId 查空)。
    workflowRawInputs.value.map((p) => p.skuId).join(',')] as const,
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

// -------------------------------------------------------------------------
// Bug 1 修复 (fix/process-entry-cache-and-blend-cost, F006 现场走查): 半成品(SFI)/成品(FG)/
// 原料批次余量是全局共享的常驻库存 (可被任意其它 process tab 选作投料来源), 但上面的 watch
// 只在本组件挂载 / (factoryId, processCode, productTypeId) 变化时加载一次 —— 逐工序对话框内
// 各 process tab 的 ProcessDataTable 实例长期挂载不销毁 (el-tabs 只是切换可见性), 这三份 key
// 此后永不再变, 于是 sfiOptions/fgOptions/rawBatchOptions 从首次加载后就再没刷新过。
//
// 若另一 tab 保存了一行改变这些余量的产出 (如 焯水 postSfiOutput 把 SFI 锚余量从
// 1.05kg 推到 2.03kg), 本 tab 的下拉/校验 (upstreamWarning) 仍读旧的 1.05kg → 假阳性拦截
// "用量 2.03kg 超出半成品库存余 1.05kg"。真实 Postgres 数据从未错，纯前端缓存未失效，
// 手动整页刷新才能看到新值 —— 违反 fool-proof-design Rule 1 (预先显示正确边界)。
//
// ProcessSheet.vue 在任一 tab 保存后, 对全部 process tab 的 ProcessDataTable 实例调用此方法,
// 重新拉取三类共享余量 (不清空现有值, 避免刷新期间下拉短暂清空/跳动), 消除跨 tab 假阳性拦截。
// -------------------------------------------------------------------------
function refreshSharedInventories() {
  if (isXiuYou.value) void loadRawBatches();
  if (showSfi.value) void loadSfiOptions();
  if (showFg.value) void loadFgOptions();
}

defineExpose({ hasUnsavedRows, refreshSharedInventories });
</script>

<template>
  <div class="sp-grid-wrap">

    <!-- ====================================================================
         2B Task F2 (additive, fool-proof Rule 2/3/5): workflow 计划产出/所需原料只读展示。
         仅 workflowContext 非 null (workflow-activated 计划) 时渲染; legacy 计划该 prop 恒为
         null, 这块整体不出现, 不影响任何现有布局/行为。
         ==================================================================== -->
    <el-alert
      v-if="workflowContext"
      class="sp-workflow-banner"
      type="info"
      :closable="false"
      show-icon
    >
      <template #title>
        <!-- 2B.2 多产出: 产出端口 > 1 时汇总展示全部产出 (品名+单位), 而不是只显首个端口。 -->
        <span v-if="isMultiOutput">
          多产出 (本道同时产 {{ outputPorts.length }} 个产品)：{{ workflowOutputsLabel }}
        </span>
        <span v-else-if="workflowOutput">
          计划产出：{{ workflowOutputLabel }}
          <el-tag size="small" :type="workflowOutput.finished ? 'success' : 'warning'" style="margin-left:6px">
            {{ workflowOutput.finished ? '成品' : '半成品' }}
          </el-tag>
        </span>
        <span v-else style="color:#909399">本工序 workflow 未配置产出端口</span>
        <span v-if="workflowRawInputsLabel" style="margin-left:12px;color:#606266">
          需要原料：{{ workflowRawInputsLabel }}
        </span>
      </template>
    </el-alert>
    <el-alert
      v-if="outputPorts.some((p) => p.skuResolved === false)"
      class="sp-workflow-banner sp-workflow-banner-warning"
      type="error"
      :closable="false"
      show-icon
      title="产出 SKU 已失效，请回 Workflow 配置"
    />
    <el-alert
      v-if="unresolvedWorkflowRawInputs.length > 0"
      data-testid="workflow-input-invalid"
      class="sp-workflow-banner sp-workflow-banner-warning"
      type="error"
      :closable="false"
      show-icon
    >
      <template #title>
        <span>当前计划绑定的原料已失效，暂不能报工</span>
      </template>
      <div>
        失效原料：{{ unresolvedWorkflowRawInputs.map((p) => p.materialName || p.skuId).join('、') }}。
        报工页只填写各原料实际投入量，不能临时更换原料。
        <el-link :href="workflowConfigHref" type="primary" underline="always">
          去产品-工序配置重新绑定
        </el-link>
        ，然后重新创建生产计划。
      </div>
    </el-alert>

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

      <div v-if="historicalRows.length > 0" class="sp-settled-section" data-testid="legacy-readonly-row">
        <div class="sp-settled-header">
          <span>已入账/历史数据（只读） · {{ historicalRows.length }} 行</span>
          <el-tag type="info" size="small" style="margin-left:8px">不可修改</el-tag>
        </div>
        <div v-for="row in historicalRows" :key="row.clientRowId" class="sp-card sp-card-settled">
          <div class="sp-card-header">
            <el-tag type="info" size="small">历史记录</el-tag>
            <span v-if="row.batchNumber" class="sp-card-batchnum">{{ row.batchNumber }}</span>
            <span class="sp-settled-summary">{{ settledRowSummary(row) }}</span>
            <div style="flex:1" />
            <el-button
              v-if="hasHistory(row)"
              link size="small" :icon="Clock"
              title="查看操作记录"
              aria-label="查看操作记录"
              @click="openHistory(row)" />
          </div>
        </div>
      </div>

      <div v-for="(row, ri) in activeRows" :key="row.clientRowId" class="sp-card"
           :class="{ 'sp-card-saved': row.rowStatus === 'SAVED', 'sp-card-draft': row.rowStatus === 'DRAFT' }">

        <!-- Card header: row index + status tag + batch + warning + actions -->
        <div class="sp-card-header">
          <span class="sp-card-idx">#{{ ri + 1 }}</span>
          <el-tag
            :type="row.submissionStatus === 'SUBMITTED' ? 'success' : row.rowStatus === 'DRAFT' ? 'warning' : 'info'"
            size="small" style="white-space:nowrap">
            {{ row.submissionStatus === 'SUBMITTED' ? '已正式报工' : row.rowStatus === 'DRAFT' ? '草稿' : '新建' }}
          </el-tag>
          <el-tooltip v-if="upstreamWarning(row)" :content="upstreamWarning(row)!" placement="top">
            <el-icon style="color:#e6a23c;cursor:pointer"><Warning /></el-icon>
          </el-tooltip>
          <span v-if="row.batchNumber" class="sp-card-batchnum">{{ row.batchNumber }}</span>
          <span v-else class="sp-card-batchnum sp-card-batchnum-pending">(保存后生成批次号)</span>
          <div style="flex:1" />
          <!-- Actions -->
          <el-button
            size="small"
            :loading="row.saving"
              :disabled="!!draftSaveDisabledReason(row) || row.saving"
              :title="draftSaveDisabledReason(row) || '只保存草稿，不占用生产库库存'"
            @click="handleSave(row, 'draft')"
            style="padding:3px 8px">保存草稿</el-button>
          <el-button
            type="primary" size="small" :icon="Check"
            :loading="row.saving"
            :disabled="!!submitDisabledReason(row) || row.saving"
            :title="submitDisabledReason(row) || '正式报工并由系统自动分摊生产库批次'"
            @click="handleSave(row, 'submit')"
            style="padding:3px 8px">正式报工</el-button>
          <el-button
            v-if="hasHistory(row)"
            link size="small" :icon="Clock"
            title="查看操作记录"
            aria-label="查看操作记录"
            @click="openHistory(row)"
            style="margin-left:4px" />
          <el-button
            v-if="row.submissionStatus !== 'SUBMITTED'"
            type="danger" link size="small" :icon="Delete"
            :loading="row.deleting"
            title="删除本行"
            aria-label="删除本行"
            @click="handleDelete(row)"
            style="margin-left:4px" />
        </div>

        <el-alert
          v-if="row.blockingMessage"
          :title="row.blockingMessage"
          type="error"
          :closable="false"
          show-icon
          style="margin:8px 12px 0"
        />

        <!-- Card body: field grid -->
        <div class="sp-card-body">

          <div
            v-if="isPortOutputMode"
            data-testid="production-date"
            class="sp-card-field sp-card-field-full sp-reporting-date"
          >
            <label class="sp-card-label">生产日期</label>
            <el-date-picker
              v-model="row.productionDate"
              type="date"
              value-format="YYYY-MM-DD"
              style="width:160px"
              size="small"
            />
          </div>
          <div v-if="isPortOutputMode" class="sp-card-field sp-card-field-full sp-port-section-note">
            <strong>投入明细</strong>
            <span>按 Workflow 端口逐行录入；不同物料或批次新增投入行，每条投入在本报工组中只扣减一次。</span>
          </div>

          <!-- 修油: raw-material batch dropdown + out-weight -->
          <template v-if="isXiuYou">
            <template v-if="usesAutoMaterialTotals(row)">
              <div
                v-for="item in row.materialInputTotals"
                :key="item.workflowPortId || item.materialTypeId"
                data-testid="material-input-total"
                class="sp-card-field"
                :class="{ 'sp-port-unselected': !item.selected }"
              >
                <el-checkbox
                  :model-value="item.selected"
                  :disabled="portSelectionDisabled(portById(item.workflowPortId))"
                  data-testid="port-selected"
                  @change="(selected: boolean) => setPortSelected(row, portById(item.workflowPortId), selected)"
                >选用</el-checkbox>
                <span class="sp-port-selection-hint">{{ portSelectionSummary(portById(item.workflowPortId)) }}</span>
                <label class="sp-card-label">{{ item.materialName }} · 投料总量</label>
                <el-input-number
                  v-model="item.quantity"
                  :disabled="!item.selected"
                  :min="0"
                  :precision="6"
                  controls-position="right"
                  style="width:160px"
                  size="small"
                />
                <span data-testid="input-unit-readonly" class="sp-fixed-unit">{{ item.unit }}</span>
                <span style="font-size:11px;color:#909399">来源批次由系统按生产库入库顺序自动分摊</span>
              </div>
            </template>
            <template v-else>
            <div data-testid="legacy-raw-batch-picker" class="sp-card-field">
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
                <!-- 防呆(load-race 修复): loading 时用独立 #loading slot, 不落回 #empty —— 否则
                     el-select 内部逻辑 (loading || 无选项 → 渲染 #empty) 会在"还在加载"时也显示
                     "暂无可用批次", 让操作员误以为真无库存, 首次打开必闪现空态。 -->
                <template #loading>
                  <span style="padding:8px;color:#909399;font-size:12px">
                    <el-icon class="is-loading" style="vertical-align:-2px"><Loading /></el-icon> 加载中，请稍候…
                  </span>
                </template>
                <template #empty>
                  <span style="padding:8px;color:#909399;font-size:12px">暂无可用原料批次，请先入库/领料</span>
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
              <label class="sp-card-label">{{ firstProcessInputLabel }}</label>
              <el-input-number
                v-model="row.rawBatchQty"
                :min="0" :precision="2"
                controls-position="right"
                style="width:160px" size="small" />
            </div>
            </template>
          </template>

          <!-- 单来源上游: 含半成品库存(SFI)/成品库存(FG)选项 -->
          <template v-else-if="isSingleSource && !isQuSheTou">
            <div class="sp-card-field">
              <label class="sp-card-label">{{ sourceTitle }}</label>
              <el-select
                :model-value="singleUpstreamSelectKey(row)"
                @change="(v: string) => onSingleUpstreamSelect(row, v)"
                :placeholder="sourcePickerPlaceholder"
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
          <template v-else-if="isSingleSource && isQuSheTou">
            <div class="sp-card-field">
              <label class="sp-card-label">{{ sourceTitle }}</label>
              <el-select
                :model-value="singleUpstreamSelectKey(row)"
                @change="(v: string) => onSingleUpstreamSelect(row, v)"
                :placeholder="sourcePickerPlaceholder"
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

          <!-- 多来源混批: configured by ProductWorkProcess.allowMultipleUpstreamSources -->
          <template v-else-if="isMultiSource">
            <div data-testid="upstream-sources-toggle" class="sp-card-field sp-card-field-full">
              <label class="sp-card-label">{{ sourceTitle }}</label>
              <el-button link size="small" @click="row.mixExpanded = !row.mixExpanded" style="font-size:12px">
                <el-icon style="margin-right:3px"><component :is="row.mixExpanded ? ArrowDown : ArrowRight" /></el-icon>
                {{ formatSourceFeedSummary(row.upstreamSources.length, row.upstreamSources.reduce((s, x) => s + (x.feedQuantityKg || 0), 0), processUnits.inputUnit) }}
              </el-button>
            </div>
            <!-- Mix expanded inline -->
            <div v-if="row.mixExpanded" class="sp-card-field sp-card-field-full sp-card-expand-section">
              <div style="margin-bottom:6px;display:flex;align-items:center;gap:8px">
                <span style="font-size:12px;font-weight:600;color:#303133">{{ sourceTitle }}</span>
                <el-button v-if="workflowUpstreamInputs.length === 0" size="small" :icon="Plus" @click="addUpstreamSource(row)">+ 来源批</el-button>
              </div>
              <div v-for="(src, si) in row.upstreamSources" :key="`${src.workflowPortId || 'legacy'}-${si}`"
              data-testid="upstream-source-line"
                   :class="{ 'sp-port-unselected': !src.selected }"
                   style="display:flex;align-items:center;gap:8px;margin-bottom:6px;flex-wrap:wrap">
                <el-checkbox
                  :model-value="src.selected"
                  :disabled="portSelectionDisabled(sourcePort(src))"
                  data-testid="port-selected"
                  @change="(selected: boolean) => setPortSelected(row, sourcePort(src), selected)"
                >选用</el-checkbox>
                <span data-testid="input-port-name" class="sp-fixed-port-name">{{ sourcePortName(src) }}</span>
                <el-select
                  :model-value="srcSelectKey(src)"
                  @change="(v: string) => onUpstreamSelect(src, v)"
                  :placeholder="sourcePickerPlaceholder" filterable clearable
                  :filter-method="onBatchSelectFilter"
                  @visible-change="onBatchSelectVisibleChange"
                  :loading="sfiLoading"
                  style="width:220px" size="small">
                  <el-option-group label="本计划在制半成品">
                    <el-option
                      v-for="item in wipOptionsForSource(src)" :key="item.batchNumber"
                      :label="wipLabel(item)"
                      :value="srcKey(SRC_WIP, item.batchNumber)"
                      :disabled="item.remaining <= 0" />
                  </el-option-group>
                  <el-option-group v-if="sfiOptionsForSource(src).length" :label="sfiGroupLabel">
                    <el-option
                      v-for="s in sfiOptionsForSource(src)" :key="'sfi-' + s.intermediateBatchNo"
                      :label="sfiLabel(s)"
                      :value="srcKey(SRC_SFI, s.intermediateBatchNo)"
                      :disabled="sfiAvailable(s) <= 0" />
                  </el-option-group>
                  <el-option-group v-if="fgOptionsForSource(src).length" :label="fgGroupLabel">
                    <el-option
                      v-for="f in fgOptionsForSource(src)" :key="'fg-' + f.batchNumber"
                      :label="fgLabel(f)"
                      :value="srcKey(SRC_FG, f.batchNumber)"
                      :disabled="fgAvailable(f) <= 0" />
                  </el-option-group>
                </el-select>
                <el-input-number
                  v-model="src.feedQuantityKg"
                  :min="0" :precision="2"
                  :placeholder="formatFeedPlaceholder(sourcePortUnit(src))"
                  controls-position="right"
                  size="small" style="width:120px" />
                <span data-testid="input-unit-readonly" class="sp-fixed-unit">{{ sourcePortUnit(src) }}</span>
                <span v-if="src.sourceBatchNumber" style="font-size:11px;color:#909399">
                  {{ srcRemainingLabel(src) }}
                </span>
                <el-button link type="danger" :icon="Delete" @click="removeUpstreamSource(row, si)" />
                <el-button v-if="src.workflowPortId" link type="primary" :icon="Plus" @click="addUpstreamSource(row, src)">同物料再加批次</el-button>
              </div>
              <div v-if="row.upstreamSources.length === 0" style="color:#909399;font-size:12px;margin:4px 0">
                暂无来源批，点击 + 来源批 添加
              </div>
            </div>
          </template>

          <div
            v-if="needsPotCount"
            data-testid="seasoning-pot-count"
            class="sp-card-field sp-card-field-full sp-card-expand-section"
          >
            <label class="sp-card-label">锅数</label>
            <el-input-number
              :model-value="row.potCount"
              @update:model-value="(v: number) => onPotCountChange(row, v)"
              :min="1"
              :precision="0"
              size="small"
              style="width:100px"
            />
            <span style="font-size:12px;color:#606266">{{ potSplitHint(row) }}</span>
          </div>

          <!-- Generic columns from config (skip special-cased keys) -->
          <template v-for="col in cols" :key="col.key">
            <div
              v-if="!excludedColKeys.includes(col.key)"
              class="sp-card-field"
              :class="{ 'sp-card-field-auto': col.type === 'auto' || col.type === 'readonly' }">
              <label class="sp-card-label">
                {{ col.label }}
                <!-- A: 自定义字段列头轻提示 (由管理员在工序配置里定义) -->
                <el-tooltip v-if="isCustomFieldCol(col.key)" content="自定义字段（由管理员在「工序配置」里定义）" placement="top">
                  <el-icon class="sp-th-custom-hint"><QuestionFilled /></el-icon>
                </el-tooltip>
              </label>

              <el-input-number
                v-if="col.type === 'number'"
                :model-value="(row.fields[col.key] as number) ?? undefined"
                @update:model-value="(v: number) => row.fields[col.key] = v"
                :min="0" :precision="fieldPrecision(col.key)"
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
                {{ calcReverseInput(row) != null ? `${calcReverseInput(row)!.toFixed(2)}${processUnits.inputUnit}` : '—' }}
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

              <span v-else-if="col.type === 'auto' && col.autoCalc === 'finishedInbound'" class="sp-readonly">
                {{ finishedInboundQuantity(row) ?? '—' }} {{ processUnits.outputUnit }}
              </span>

              <span v-else-if="col.type === 'auto' && col.autoCalc === 'finishedRemaining'" class="sp-readonly">
                {{ finishedInboundQuantity(row) ?? '—' }} {{ processUnits.outputUnit }}
              </span>

              <span v-else-if="col.type === 'auto' && col.autoCalc === 'finishedWeight'" class="sp-readonly">
                {{ formattedWeight(finishedWeightKg(row)) }}
                <small style="display:block;color:#909399">{{ unitNetWeightText() }}</small>
              </span>

              <span v-else-if="col.type === 'auto' && col.autoCalc === 'finishedInboundWeight'" class="sp-readonly">
                {{ formattedWeight(finishedWeightKg(row, true)) }}
              </span>

              <span v-else-if="col.type === 'readonly' || col.type === 'text'" class="sp-readonly">
                {{ row.fields[col.key] ?? '—' }}
              </span>
            </div>
          </template>

          <!-- ============================================================
               2B.2 多产出 (fan-out): N 个产出端口各自只读品名(+成品/半成品标签) + 填数量。
               产品/端口由 workflow 图固定 (fool-proof Rule 2/3 — 不给操作员自由选产品), 始终展开
               (核心必填录入, 不折叠隐藏)。仅 isMultiOutput (产出端口>1) 时渲染, 单产出工序不受影响。
               ============================================================ -->
          <div v-if="isPortOutputMode" class="sp-card-field sp-card-field-full sp-card-expand-section">
            <div class="sp-output-section-title">
              <span>产出明细 — {{ row.multiOutputs.length }} 项</span>
              <span>SKU 与单位由 Workflow 固定，不可选择</span>
            </div>
            <div
              v-for="(o, oi) in row.multiOutputs"
              :key="o.workflowPortId || oi"
              data-testid="workflow-output-line"
              class="sp-output-line"
              :class="{ 'sp-port-unselected': !o.selected }"
            >
              <div class="sp-output-line-head">
                <el-checkbox
                  :model-value="o.selected"
                  :disabled="portSelectionDisabled(portById(o.workflowPortId))"
                  data-testid="port-selected"
                  @change="(selected: boolean) => setPortSelected(row, portById(o.workflowPortId), selected)"
                >选用</el-checkbox>
                <strong>{{ o.materialName }}</strong>
                <el-tag size="small" :type="o.finished ? 'success' : 'warning'">
                  {{ o.finished ? '成品' : '半成品' }}
                </el-tag>
                <span v-if="o.batchNumber" class="sp-readonly sp-batch-num">{{ o.batchNumber }}</span>
              </div>
              <div class="sp-output-fields">
                <label data-testid="output-start-time">开始时间<el-time-picker v-model="o.startTime" value-format="HH:mm" format="HH:mm" placeholder="开始" size="small" /></label>
                <label data-testid="output-end-time">结束时间<el-time-picker v-model="o.endTime" value-format="HH:mm" format="HH:mm" placeholder="结束" size="small" /></label>
                <label data-testid="output-worker-count">人数<el-input-number v-model="o.workerCount" :min="1" :precision="0" controls-position="right" size="small" /></label>
                <label data-testid="output-quantity">产出数量<span class="sp-inline-input"><el-input-number v-model="o.quantity" :min="0" :precision="outputLinePrecision(o)" controls-position="right" size="small" /><span data-testid="output-unit-readonly" class="sp-fixed-unit">{{ o.unit }}</span></span></label>
                <label>出成率<span class="sp-readonly">{{ outputLineYield(row, o) == null ? '—' : `${outputLineYield(row, o)!.toFixed(2)}%` }}</span></label>
                <label data-testid="byproduct-quantity">副产数量<span class="sp-inline-input"><el-input-number v-model="o.byproductQuantity" :min="0" :precision="6" controls-position="right" size="small" /><span data-testid="byproduct-unit-readonly" class="sp-fixed-unit">{{ o.byproductUnit }}</span></span></label>
                <label data-testid="byproduct-unit-price">副产回收单价<el-input-number v-model="o.byproductUnitPrice" :min="0" :precision="4" controls-position="right" size="small" /></label>
                <label v-if="requiresManualCostAllocation(row)" data-testid="cost-allocation-ratio">成本分摊比例(%)<el-input-number v-model="o.costAllocationRatio" :min="0" :max="100" :precision="4" controls-position="right" size="small" /></label>
                <label>总工时<span class="sp-readonly">{{ outputLineTotalHours(o).toFixed(2) }} h</span></label>
              </div>
              <div v-if="o.finished" class="sp-output-weight-hint">
                {{ outputLineWeightKg(o) == null ? '未配置单位净重，无法计算成品重量' : `成品重量 ${formattedWeight(outputLineWeightKg(o))}` }}
              </div>
            </div>
          </div>

          <!-- Labor expander -->
          <div v-if="!isPortOutputMode" class="sp-card-field sp-card-field-full">
            <label class="sp-card-label">工时</label>
            <el-button link size="small" @click="row.laborExpanded = !row.laborExpanded" style="font-size:12px">
              <el-icon style="margin-right:3px"><component :is="row.laborExpanded ? ArrowDown : ArrowRight" /></el-icon>
              {{ calcTotalHours(row).toFixed(1) }}h · {{ row.laborSegments.length }}段
            </el-button>
          </div>
          <div v-if="!isPortOutputMode && row.laborExpanded" class="sp-card-field sp-card-field-full sp-card-expand-section">
            <div style="font-size:12px;font-weight:600;color:#303133;margin-bottom:8px">
              工时录入 — {{ row.batchNumber || '(未保存行)' }}
            </div>
            <WorkHoursTable v-model="row.laborSegments" />
          </div>

        </div><!-- /.sp-card-body -->
      </div><!-- /v-for cards -->

      <!-- Add row button (card mode) -->
      <div style="margin-top:8px">
        <el-alert
          v-if="addRowBlockedReason && !sfiLoading && !fgLoading"
          :title="addRowBlockedReason"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom:8px"
        />
        <el-button
          data-testid="add-process-row"
          :icon="Plus"
          :disabled="!!addRowBlockedReason"
          :title="addRowBlockedReason || '新增报工行'"
          @click="addRow"
          style="width:100%"
          plain>+ 新增行</el-button>
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
            <th v-if="isPortOutputMode" class="sp-th sp-th-date">生产日期</th>

            <!-- 修油: raw batch + out-weight cols appear before generic cols -->
            <template v-if="isXiuYou">
              <th class="sp-th">{{ workflowRawInputs.length ? '投料物料' : '原料批次' }}</th>
              <th class="sp-th sp-th-num">{{ workflowRawInputs.length ? '投料总量' : firstProcessInputLabel }}</th>
            </template>

            <!-- 单来源上游 -->
            <template v-else-if="isSingleSource && !isQuSheTou">
              <th class="sp-th">{{ sourceTitle }}</th>
            </template>

            <!-- 去舌苔单来源上游 -->
            <template v-else-if="isSingleSource && isQuSheTou">
              <th class="sp-th">{{ sourceTitle }}</th>
            </template>

            <!-- 多来源混批 (rendered as expander cell) -->
            <template v-else-if="isMultiSource">
              <th class="sp-th">{{ sourceTitle }}</th>
            </template>

            <th v-if="needsPotCount" class="sp-th sp-th-num">锅数（系统等分）</th>

            <!-- Generic cols from config (skip special-cased keys) -->
            <template v-for="col in cols" :key="col.key">
              <th v-if="!excludedColKeys.includes(col.key)"
                  class="sp-th"
                  :class="{
                    'sp-th-num': col.type === 'number' || col.type === 'auto',
                    'sp-th-date': col.type === 'date',
                    'sp-th-daterange': col.type === 'daterange',
                  }">
                {{ col.label }}
                <!-- A: 自定义字段列头轻提示 (由管理员在工序配置里定义) -->
                <el-tooltip v-if="isCustomFieldCol(col.key)" content="自定义字段（由管理员在「工序配置」里定义）" placement="top">
                  <el-icon class="sp-th-custom-hint"><QuestionFilled /></el-icon>
                </el-tooltip>
              </th>
            </template>

            <!-- System batch (readonly) -->
            <th class="sp-th sp-th-batch">批次号</th>
            <!-- Labor expander trigger -->
            <th v-if="!isPortOutputMode" class="sp-th sp-th-labor">工时</th>
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
              <td v-if="isPortOutputMode" data-testid="production-date" class="sp-td sp-td-date">
                <el-date-picker
                  v-model="row.productionDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  style="width:130px"
                  size="small"
                />
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

        <tbody v-if="historicalRows.length > 0" data-testid="legacy-readonly-row">
          <tr class="sp-settled-banner">
            <td :colspan="999">已入账/历史数据（只读） · {{ historicalRows.length }} 行，不可修改</td>
          </tr>
          <tr v-for="row in historicalRows" :key="row.clientRowId" class="sp-tr sp-tr-settled">
            <td class="sp-td"><el-tag type="info" size="small">历史记录</el-tag></td>
            <td class="sp-td">{{ settledRowSummary(row) }}</td>
            <td class="sp-td sp-td-batch"><span class="sp-readonly sp-batch-num">{{ row.batchNumber || '—' }}</span></td>
            <td class="sp-td" :colspan="999">
              <el-button
                v-if="hasHistory(row)"
                link size="small" :icon="Clock"
                title="查看操作记录"
                aria-label="查看操作记录"
                @click="openHistory(row)" />
            </td>
          </tr>
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
                  :type="row.submissionStatus === 'SUBMITTED' ? 'success' : row.rowStatus === 'DRAFT' ? 'warning' : 'info'"
                  size="small" style="white-space:nowrap">
                  {{ row.submissionStatus === 'SUBMITTED' ? '已正式报工' : row.rowStatus === 'DRAFT' ? '草稿' : '新建' }}
                </el-tag>
                <el-tooltip v-if="upstreamWarning(row)" :content="upstreamWarning(row)!" placement="top">
                  <el-icon style="color:#e6a23c;margin-left:3px;cursor:pointer"><Warning /></el-icon>
                </el-tooltip>
              </td>

              <!-- ---- 修油: raw-material batch dropdown ---- -->
              <template v-if="isXiuYou">
                <template v-if="usesAutoMaterialTotals(row)">
                  <td class="sp-td">
                    <div v-for="item in row.materialInputTotals" :key="item.workflowPortId || item.materialTypeId">
                      {{ item.materialName }}
                    </div>
                  </td>
                  <td class="sp-td sp-td-num">
                    <div
                      v-for="item in row.materialInputTotals"
                      :key="item.workflowPortId || item.materialTypeId"
                      data-testid="material-input-total"
                      :class="{ 'sp-port-unselected': !item.selected }"
                      style="display:flex;align-items:center;gap:4px;margin-bottom:4px"
                    >
                      <el-checkbox
                        :model-value="item.selected"
                        :disabled="portSelectionDisabled(portById(item.workflowPortId))"
                        data-testid="port-selected"
                        @change="(selected: boolean) => setPortSelected(row, portById(item.workflowPortId), selected)"
                      >选用</el-checkbox>
                      <el-input-number
                        v-model="item.quantity"
                        :disabled="!item.selected"
                        :min="0"
                        :precision="6"
                        controls-position="right"
                        style="width:110px"
                        size="small"
                      />
                      <span data-testid="input-unit-readonly" class="sp-fixed-unit">{{ item.unit }}</span>
                    </div>
                  </td>
                </template>
                <template v-else>
                <td class="sp-td">
                  <el-select
                    data-testid="legacy-raw-batch-picker"
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
                    <!-- 防呆(load-race 修复): 见卡片视图同款注释 — 独立 #loading slot 防"加载中"误显"暂无" -->
                    <template #loading>
                      <span style="padding:8px;color:#909399;font-size:12px">
                        <el-icon class="is-loading" style="vertical-align:-2px"><Loading /></el-icon> 加载中，请稍候…
                      </span>
                    </template>
                    <template #empty>
                      <span style="padding:8px;color:#909399;font-size:12px">暂无可用原料批次，请先入库/领料</span>
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
              </template>

              <!-- ---- 单来源上游 dropdown — 含半成品库存(SFI)/成品库存(FG) ---- -->
              <template v-else-if="isSingleSource && !isQuSheTou">
                <td class="sp-td">
                  <el-select
                    :model-value="singleUpstreamSelectKey(row)"
                    @change="(v: string) => onSingleUpstreamSelect(row, v)"
                    :placeholder="sourcePickerPlaceholder"
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
              <template v-else-if="isSingleSource && isQuSheTou">
                <td class="sp-td">
                  <el-select
                    :model-value="singleUpstreamSelectKey(row)"
                    @change="(v: string) => onSingleUpstreamSelect(row, v)"
                    :placeholder="sourcePickerPlaceholder"
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

              <!-- ---- 多来源混批 expander cell ---- -->
              <template v-else-if="isMultiSource">
                <td class="sp-td">
                  <el-button
                    link size="small"
                    @click="row.mixExpanded = !row.mixExpanded"
                    style="font-size:12px">
                    <el-icon style="margin-right:3px"><component :is="row.mixExpanded ? ArrowDown : ArrowRight" /></el-icon>
                    {{ formatSourceFeedSummary(row.upstreamSources.length, row.upstreamSources.reduce((s, x) => s + (x.feedQuantityKg || 0), 0), processUnits.inputUnit) }}
                  </el-button>
                </td>
              </template>

              <td v-if="needsPotCount" data-testid="seasoning-pot-count" class="sp-td sp-td-num">
                <el-input-number
                  :model-value="row.potCount"
                  @update:model-value="(v: number) => onPotCountChange(row, v)"
                  :min="1"
                  :precision="0"
                  size="small"
                  style="width:80px"
                />
                <div style="margin-top:3px;font-size:11px;color:#606266;white-space:nowrap">
                  {{ potSplitHint(row) }}
                </div>
              </td>

              <!-- ---- Generic columns from config ---- -->
              <template v-for="col in cols" :key="col.key">
                <td
                  v-if="!excludedColKeys.includes(col.key)"
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
                    :min="0" :precision="fieldPrecision(col.key)"
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
                    {{ calcReverseInput(row) != null ? `${calcReverseInput(row)!.toFixed(2)}${processUnits.inputUnit}` : '—' }}
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

                  <span v-else-if="col.type === 'auto' && col.autoCalc === 'finishedInbound'" class="sp-readonly">
                    {{ finishedInboundQuantity(row) ?? '—' }} {{ processUnits.outputUnit }}
                  </span>

                  <span v-else-if="col.type === 'auto' && col.autoCalc === 'finishedRemaining'" class="sp-readonly">
                    {{ finishedInboundQuantity(row) ?? '—' }} {{ processUnits.outputUnit }}
                  </span>

                  <span v-else-if="col.type === 'auto' && col.autoCalc === 'finishedWeight'" class="sp-readonly">
                    {{ formattedWeight(finishedWeightKg(row)) }}
                    <small style="display:block;color:#909399">{{ unitNetWeightText() }}</small>
                  </span>

                  <span v-else-if="col.type === 'auto' && col.autoCalc === 'finishedInboundWeight'" class="sp-readonly">
                    {{ formattedWeight(finishedWeightKg(row, true)) }}
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
              <td v-if="!isPortOutputMode" class="sp-td sp-td-labor">
                <el-button link size="small" @click="row.laborExpanded = !row.laborExpanded" style="font-size:12px">
                  <el-icon style="margin-right:3px"><component :is="row.laborExpanded ? ArrowDown : ArrowRight" /></el-icon>
                  {{ calcTotalHours(row).toFixed(1) }}h·{{ row.laborSegments.length }}段
                </el-button>
              </td>

              <!-- Row actions -->
              <td class="sp-td sp-td-actions">
                <el-button
                  size="small"
                  :loading="row.saving"
                          :disabled="!!draftSaveDisabledReason(row) || row.saving"
                          :title="draftSaveDisabledReason(row) || '只保存草稿，不占用生产库库存'"
                  @click="handleSave(row, 'draft')"
                  style="padding:3px 6px">保存草稿</el-button>
                <el-button
                  type="primary" size="small" :icon="Check"
                  :loading="row.saving"
                  :disabled="!!submitDisabledReason(row) || row.saving"
                  :title="submitDisabledReason(row) || '正式报工并自动分摊生产库批次'"
                  @click="handleSave(row, 'submit')"
                  style="padding:3px 6px;margin-left:4px">正式报工</el-button>
                <el-button
                  v-if="hasHistory(row)"
                  link size="small" :icon="Clock"
                  title="查看操作记录"
                  aria-label="查看操作记录"
                  @click="openHistory(row)"
                  style="margin-left:4px" />
                <el-button
                  v-if="row.submissionStatus !== 'SUBMITTED'"
                  type="danger" link size="small" :icon="Delete"
                  :loading="row.deleting"
                  title="删除本行"
                  aria-label="删除本行"
                  @click="handleDelete(row)"
                  style="margin-left:4px" />
              </td>
            </tr>

            <tr v-if="row.blockingMessage" :key="row.clientRowId + '-blocking'" class="sp-tr-expand">
              <td :colspan="999" class="sp-td-expand">
                <el-alert :title="row.blockingMessage" type="error" :closable="false" show-icon />
              </td>
            </tr>

            <!-- ============================================================
                 2B.2 多产出 (fan-out) row — 始终展开 (核心必填录入, 不折叠隐藏, 与 labor/mix
                 expander 的"点击展开"不同: 多产出没有默认值, 操作员必须逐项看到才能填数量)。
                 产品/端口由 workflow 图固定, 只读; 只填数量。
                 ============================================================ -->
            <tr v-if="isPortOutputMode" :key="row.clientRowId + '-multiout'"
                :class="['sp-tr-expand', ri % 2 === 0 ? 'sp-tr-even' : 'sp-tr-odd']">
              <td :colspan="999" class="sp-td-expand">
                <div class="sp-expand-section">
                  <div class="sp-output-section-title">
                    <span>产出明细 — {{ row.multiOutputs.length }} 项</span>
                    <span>投入按本报工组只扣减一次；SKU 与单位由 Workflow 固定</span>
                  </div>
                  <div
                    v-for="(o, oi) in row.multiOutputs"
                    :key="o.workflowPortId || oi"
                    data-testid="workflow-output-line"
                    class="sp-output-line"
                    :class="{ 'sp-port-unselected': !o.selected }"
                  >
                    <div class="sp-output-line-head">
                      <el-checkbox
                        :model-value="o.selected"
                        :disabled="portSelectionDisabled(portById(o.workflowPortId))"
                        data-testid="port-selected"
                        @change="(selected: boolean) => setPortSelected(row, portById(o.workflowPortId), selected)"
                      >选用</el-checkbox>
                      <strong>{{ o.materialName }}</strong>
                      <el-tag size="small" :type="o.finished ? 'success' : 'warning'">{{ o.finished ? '成品' : '半成品' }}</el-tag>
                      <span v-if="o.batchNumber" class="sp-readonly sp-batch-num">{{ o.batchNumber }}</span>
                    </div>
                    <div class="sp-output-fields">
                      <label data-testid="output-start-time">开始时间<el-time-picker v-model="o.startTime" value-format="HH:mm" format="HH:mm" placeholder="开始" size="small" /></label>
                      <label data-testid="output-end-time">结束时间<el-time-picker v-model="o.endTime" value-format="HH:mm" format="HH:mm" placeholder="结束" size="small" /></label>
                      <label data-testid="output-worker-count">人数<el-input-number v-model="o.workerCount" :min="1" :precision="0" controls-position="right" size="small" /></label>
                      <label data-testid="output-quantity">产出数量<span class="sp-inline-input"><el-input-number v-model="o.quantity" :min="0" :precision="outputLinePrecision(o)" controls-position="right" size="small" /><span data-testid="output-unit-readonly" class="sp-fixed-unit">{{ o.unit }}</span></span></label>
                      <label>出成率<span class="sp-readonly">{{ outputLineYield(row, o) == null ? '—' : `${outputLineYield(row, o)!.toFixed(2)}%` }}</span></label>
                      <label data-testid="byproduct-quantity">副产数量<span class="sp-inline-input"><el-input-number v-model="o.byproductQuantity" :min="0" :precision="6" controls-position="right" size="small" /><span data-testid="byproduct-unit-readonly" class="sp-fixed-unit">{{ o.byproductUnit }}</span></span></label>
                      <label data-testid="byproduct-unit-price">副产回收单价<el-input-number v-model="o.byproductUnitPrice" :min="0" :precision="4" controls-position="right" size="small" /></label>
                      <label v-if="requiresManualCostAllocation(row)" data-testid="cost-allocation-ratio">成本分摊比例(%)<el-input-number v-model="o.costAllocationRatio" :min="0" :max="100" :precision="4" controls-position="right" size="small" /></label>
                      <label>总工时<span class="sp-readonly">{{ outputLineTotalHours(o).toFixed(2) }} h</span></label>
                    </div>
                  </div>
                </div>
              </td>
            </tr>

            <!-- ============================================================
                 Labor expander row
                 ============================================================ -->
            <tr v-if="!isPortOutputMode && row.laborExpanded" :key="row.clientRowId + '-labor'"
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
            <tr v-if="isMultiSource && !isQidiao && row.mixExpanded" :key="row.clientRowId + '-mix'"
                :class="['sp-tr-expand', ri % 2 === 0 ? 'sp-tr-even' : 'sp-tr-odd']">
              <td :colspan="999" class="sp-td-expand">
                <div class="sp-expand-section">
                  <div class="sp-expand-title">
                      {{ sourceTitle }} — {{ row.batchNumber || '(未保存行)' }}
                    <el-button v-if="workflowUpstreamInputs.length === 0" size="small" :icon="Plus" style="margin-left:8px" @click="addUpstreamSource(row)">
                      + 来源批
                    </el-button>
                  </div>

                  <!-- Multi-source rows -->
                  <div v-for="(src, si) in row.upstreamSources" :key="`${src.workflowPortId || 'legacy'}-${si}`"
                       data-testid="upstream-source-line"
                       :class="{ 'sp-port-unselected': !src.selected }"
                       style="display:flex;align-items:center;gap:8px;margin-bottom:6px">
                    <el-checkbox
                      :model-value="src.selected"
                      :disabled="portSelectionDisabled(sourcePort(src))"
                      data-testid="port-selected"
                      @change="(selected: boolean) => setPortSelected(row, sourcePort(src), selected)"
                    >选用</el-checkbox>
                    <span data-testid="input-port-name" class="sp-fixed-port-name">{{ sourcePortName(src) }}</span>
                    <el-select
                      :model-value="srcSelectKey(src)"
                      @change="(v: string) => onUpstreamSelect(src, v)"
                        :placeholder="sourcePickerPlaceholder" filterable clearable
                      :filter-method="onBatchSelectFilter"
                      @visible-change="onBatchSelectVisibleChange"
                      :loading="sfiLoading"
                      style="width:220px" size="small">
                      <el-option-group label="本计划在制半成品">
                        <el-option
                          v-for="item in wipOptionsForSource(src)" :key="item.batchNumber"
                          :label="wipLabel(item)"
                          :value="srcKey(SRC_WIP, item.batchNumber)"
                          :disabled="item.remaining <= 0" />
                      </el-option-group>
                      <el-option-group v-if="sfiOptionsForSource(src).length" :label="sfiGroupLabel">
                        <el-option
                          v-for="s in sfiOptionsForSource(src)" :key="'sfi-' + s.intermediateBatchNo"
                          :label="sfiLabel(s)"
                          :value="srcKey(SRC_SFI, s.intermediateBatchNo)"
                          :disabled="sfiAvailable(s) <= 0" />
                      </el-option-group>
                      <el-option-group v-if="fgOptionsForSource(src).length" :label="fgGroupLabel">
                        <el-option
                          v-for="f in fgOptionsForSource(src)" :key="'fg-' + f.batchNumber"
                          :label="fgLabel(f)"
                          :value="srcKey(SRC_FG, f.batchNumber)"
                          :disabled="fgAvailable(f) <= 0" />
                      </el-option-group>
                    </el-select>
                    <el-input-number
                      v-model="src.feedQuantityKg"
                      :min="0" :precision="2"
                      :placeholder="formatFeedPlaceholder(sourcePortUnit(src))"
                      controls-position="right"
                      size="small" style="width:120px" />
                    <span data-testid="input-unit-readonly" class="sp-fixed-unit">{{ sourcePortUnit(src) }}</span>
                    <span v-if="src.sourceBatchNumber" style="font-size:11px;color:#909399">
                      {{ srcRemainingLabel(src) }}
                    </span>
                    <el-button link type="danger" :icon="Delete" @click="removeUpstreamSource(row, si)" />
                    <el-button v-if="src.workflowPortId" link type="primary" :icon="Plus" @click="addUpstreamSource(row, src)">同物料再加批次</el-button>
                  </div>
                  <div v-if="row.upstreamSources.length === 0" style="color:#909399;font-size:12px;margin:4px 0">
                    暂无来源批，点击 + 来源批 添加
                  </div>

                </div>
              </td>
            </tr>

            <!-- ============================================================
                 气调: 混锅来源 expander row (SP-G G3b)
                 ============================================================ -->
            <tr v-if="isMultiSource && isQidiao && row.mixExpanded" :key="row.clientRowId + '-mix'"
                :class="['sp-tr-expand', ri % 2 === 0 ? 'sp-tr-even' : 'sp-tr-odd']">
              <td :colspan="999" class="sp-td-expand">
                <div class="sp-expand-section">
                  <div class="sp-expand-title">
                      {{ sourceTitle }} — {{ row.batchNumber || '(未保存行)' }}
                    <el-button v-if="workflowUpstreamInputs.length === 0" size="small" :icon="Plus" style="margin-left:8px" @click="addUpstreamSource(row)">
                      + 来源批
                    </el-button>
                  </div>

                  <!-- Multi-source rows -->
                  <div v-for="(src, si) in row.upstreamSources" :key="`${src.workflowPortId || 'legacy'}-${si}`"
                       data-testid="upstream-source-line"
                       :class="{ 'sp-port-unselected': !src.selected }"
                       style="display:flex;align-items:center;gap:8px;margin-bottom:6px">
                    <el-checkbox
                      :model-value="src.selected"
                      :disabled="portSelectionDisabled(sourcePort(src))"
                      data-testid="port-selected"
                      @change="(selected: boolean) => setPortSelected(row, sourcePort(src), selected)"
                    >选用</el-checkbox>
                    <span data-testid="input-port-name" class="sp-fixed-port-name">{{ sourcePortName(src) }}</span>
                    <el-select
                      :model-value="srcSelectKey(src)"
                      @change="(v: string) => onUpstreamSelect(src, v)"
                        :placeholder="sourcePickerPlaceholder" filterable clearable
                      :filter-method="onBatchSelectFilter"
                      @visible-change="onBatchSelectVisibleChange"
                      :loading="sfiLoading"
                      style="width:220px" size="small">
                      <el-option-group label="本计划在制半成品">
                        <el-option
                          v-for="item in wipOptionsForSource(src)" :key="item.batchNumber"
                          :label="wipLabel(item)"
                          :value="srcKey(SRC_WIP, item.batchNumber)"
                          :disabled="item.remaining <= 0" />
                      </el-option-group>
                      <el-option-group v-if="sfiOptionsForSource(src).length" :label="sfiGroupLabel">
                        <el-option
                          v-for="s in sfiOptionsForSource(src)" :key="'sfi-' + s.intermediateBatchNo"
                          :label="sfiLabel(s)"
                          :value="srcKey(SRC_SFI, s.intermediateBatchNo)"
                          :disabled="sfiAvailable(s) <= 0" />
                      </el-option-group>
                      <el-option-group v-if="fgOptionsForSource(src).length" :label="fgGroupLabel">
                        <el-option
                          v-for="f in fgOptionsForSource(src)" :key="'fg-' + f.batchNumber"
                          :label="fgLabel(f)"
                          :value="srcKey(SRC_FG, f.batchNumber)"
                          :disabled="fgAvailable(f) <= 0" />
                      </el-option-group>
                    </el-select>
                    <el-input-number
                      v-model="src.feedQuantityKg"
                      :min="0" :precision="2"
                      :placeholder="formatFeedPlaceholder(sourcePortUnit(src))"
                      controls-position="right"
                      size="small" style="width:120px" />
                    <span data-testid="input-unit-readonly" class="sp-fixed-unit">{{ sourcePortUnit(src) }}</span>
                    <span v-if="src.sourceBatchNumber" style="font-size:11px;color:#909399">
                      {{ srcRemainingLabel(src) }}
                    </span>
                    <el-button link type="danger" :icon="Delete" @click="removeUpstreamSource(row, si)" />
                    <el-button v-if="src.workflowPortId" link type="primary" :icon="Plus" @click="addUpstreamSource(row, src)">同物料再加批次</el-button>
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
      <el-alert
        v-if="addRowBlockedReason && !sfiLoading && !fgLoading"
        :title="addRowBlockedReason"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom:8px"
      />
      <el-button
        data-testid="add-process-row"
        :icon="Plus"
        :disabled="!!addRowBlockedReason"
        :title="addRowBlockedReason || '新增报工行'"
        @click="addRow"
        style="width:100%"
        plain>
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

/* 2B Task F2: workflow 计划产出/所需原料只读展示条 */
.sp-workflow-banner {
  margin-bottom: 8px;
}
.sp-workflow-banner-warning {
  margin-top: -4px;
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

/* A: 自定义字段列头 `?` info 图标 (轻提示, 不抢占空间) */
.sp-th-custom-hint {
  font-size: 12px;
  color: #c0c4cc;
  cursor: help;
  vertical-align: -1px;
  margin-left: 2px;
}

/* Inline expand sections within card (labor / mix) */
.sp-card-expand-section {
  background: #f8f9fa;
  border: 1px solid #e8eaed;
  border-radius: 4px;
  padding: 10px 12px;
}

.sp-reporting-date {
  padding-bottom: 8px;
  border-bottom: 1px solid #ebeef5;
}
.sp-port-section-note {
  flex-direction: row;
  align-items: center;
  gap: 10px;
  color: #606266;
  font-size: 12px;
}
.sp-port-section-note strong {
  color: #303133;
}
.sp-fixed-unit {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 8px;
  border-radius: 4px;
  background: #f5f7fa;
  color: #909399;
  font-size: 12px;
  white-space: nowrap;
}
.sp-output-section-title {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
  color: #303133;
  font-size: 12px;
  font-weight: 600;
}
.sp-output-section-title span:last-child {
  color: #909399;
  font-weight: 400;
}
.sp-output-line {
  padding: 10px 0;
  border-top: 1px solid #ebeef5;
}
.sp-port-unselected { opacity: 0.58; }
.sp-port-selection-hint { color: #909399; font-size: 11px; white-space: nowrap; }
.sp-output-line:first-of-type {
  border-top: 0;
}
.sp-output-line-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  color: #303133;
  font-size: 12px;
}
.sp-output-fields {
  display: grid;
  grid-template-columns: repeat(4, minmax(150px, 1fr));
  gap: 8px 12px;
}
.sp-output-fields > label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  color: #909399;
  font-size: 11px;
  font-weight: 600;
}
.sp-output-fields :deep(.el-date-editor),
.sp-output-fields :deep(.el-input-number) {
  width: 100%;
}
.sp-inline-input {
  display: flex;
  align-items: center;
  gap: 6px;
}
.sp-inline-input :deep(.el-input-number) {
  flex: 1;
  min-width: 90px;
}
.sp-output-weight-hint {
  margin-top: 6px;
  color: #606266;
  font-size: 11px;
}

@media (max-width: 1200px) {
  .sp-output-fields {
    grid-template-columns: repeat(2, minmax(150px, 1fr));
  }
}
</style>
