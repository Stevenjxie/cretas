<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { getInventory, getRows, type ProcessSheetInventoryItem, type ProcessSheetRowView } from '@/api/processSheet';
import { getProductWorkProcesses, type ProcessSheetCustomFieldDef } from '@/api/processProduction';
import { PROCESS_SHEET_CONFIG } from './PROCESS_SHEET_CONFIG';
import ProcessDataTable from './ProcessDataTable.vue';
import InventoryTable from './InventoryTable.vue';
import YieldCardTable from './YieldCardTable.vue';
import { formatPlannedInput } from '@/utils/processSheetUnits';

// -------------------------------------------------------------------------
// View mode: 'grid' (电子表格) | 'card' (卡片)
// Persisted in localStorage so the preference survives page refreshes.
// 🔴 防呆 Rule 1: 默认卡片视图 — 这是「逐工序录入」操作员抽屉, 密集 ~13 列表格
//   把保存按钮挤出可视区外(横向滚动才看得到), 低文化素质操作员发现不了。卡片布局
//   逐字段纵向排列, 保存按钮永远在卡片头部可见, 无需横向滚动。已有明确偏好(用户
//   手动切过一次)时尊重 localStorage, 未设置过才用卡片默认。
// -------------------------------------------------------------------------
const VIEW_MODE_KEY = 'sp-f-process-sheet-view';
const savedView = localStorage.getItem(VIEW_MODE_KEY);
const viewMode = ref<'grid' | 'card'>(savedView === 'grid' ? 'grid' : 'card');

function onViewModeChange(val: string | number | boolean) {
  const mode = val as 'grid' | 'card';
  viewMode.value = mode;
  localStorage.setItem(VIEW_MODE_KEY, mode);
}

// -------------------------------------------------------------------------
// Props
// -------------------------------------------------------------------------
const props = defineProps<{
  factoryId: string;
  planId: string;
  productTypeId: string;
  productName?: string;
  plannedQuantity?: number;
  plannedUnit?: string | null;
}>();
const emit = defineEmits<{
  (e: 'submitted'): void;
}>();

// -------------------------------------------------------------------------
// 工序链 — 动态从产品工序配置(ProductWorkProcess)解析 (G0)
// -------------------------------------------------------------------------
// SP-F role-mode fix: code(archetype) 不是唯一工序标识 —— role-mode 下多道普通工序
// (修油/滚揉/焯水/去舌苔) 可全映射到同一 archetype code (如 'chaoshui')。用 order
// (产品工序链内唯一) 作唯一 key, code 仅用于①查列定义 PROCESS_SHEET_CONFIG[code]
// ②透传给后端作 process_code 列 + 双键过滤的 archetype 分量。
type ProcEntry = {
  code: string;
  order: number;
  label: string;
  allowInjection: boolean;
  allowMultipleUpstreamSources: boolean;
  /** G1 混批去硬编码: 本工序是否为链内第一道 (排序后 index 0) —— config-driven 主判据,
   *  透传给 ProcessDataTable 的 isFirstProcess prop (archetype 硬编码仍作兜底, 见组件内注释)。 */
  isFirstProcess: boolean;
  /** G2 自定义字段: 本工序 WorkProcess.customFieldSchema (join 只读字段), 透传给 ProcessDataTable。 */
  customFieldSchema: ProcessSheetCustomFieldDef[] | null;
  /** 5988: 本工序是否允许成品库存作投料来源 (透传给 ProcessDataTable 的 allowFinishedGoodsSource prop)。 */
  allowFinishedGoodsSource: boolean;
  /** 本工序配置的投入/产出单位。产出单位为空时由表格沿用投入单位。 */
  inputUnit: string;
  outputUnit: string | null;
};

/** 唯一工序 key = 链内唯一 processOrder 的字符串形式 (不用 code, code 会碰撞)。 */
function procKey(p: ProcEntry): string {
  return String(p.order);
}

// ---- Role-first mapping (SP-G A): defaultCostCategory → archetype ----
// When any process in a product's process list has a non-null defaultCostCategory,
// we use role mode for that product — every process maps via this table.
// Processes whose category is not listed default to 'chaoshui' (generic processing step).
const ROLE_TO_ARCHETYPE: Record<string, string> = {
  RAW_MATERIAL: 'xiuyou',
  SEASONING:    'shuzhi',
  PACKAGING:    'qidiao',
};

// ---- Name-keyword fallback (unchanged, safety net for products without roles) ----
// 工序名关键词 → PROCESS_SHEET_CONFIG key。后端 ProductWorkProcess 无 processCode (前端约定),
// 真实工序名常带前缀 (如「叮咚-猪舌-修油」「气调包装」「领料」), 故按关键词**子串**匹配 (非精确)。
// 关键词互不为子串, 子串匹配安全。新工序加列定义时在此登记关键词。
const PROCESS_NAME_TO_CODE: Record<string, string> = {
  修油: 'xiuyou', 滚揉: 'gunrou', 焯水: 'chaoshui',
  去舌苔: 'qushetou', 熟制: 'shuzhi', 气调: 'qidiao',
};
const PROCESS_KEYWORDS = Object.keys(PROCESS_NAME_TO_CODE);
function nameToConfigCode(processName: string): string | undefined {
  const kw = PROCESS_KEYWORDS.find((k) => processName.includes(k));
  return kw ? PROCESS_NAME_TO_CODE[kw] : undefined;
}

// 回退切片 (动态解析失败/无可映射工序时, 保持现状, 零回归)
const FALLBACK_PROCESSES: ProcEntry[] = [
  { code: 'xiuyou',   order: 1, label: '修油', allowInjection: false, allowMultipleUpstreamSources: false, isFirstProcess: true,  customFieldSchema: null, allowFinishedGoodsSource: false, inputUnit: 'kg', outputUnit: null },
  { code: 'chaoshui', order: 2, label: '焯水', allowInjection: false, allowMultipleUpstreamSources: false, isFirstProcess: false, customFieldSchema: null, allowFinishedGoodsSource: false, inputUnit: 'kg', outputUnit: null },
  { code: 'shuzhi',   order: 3, label: '熟制', allowInjection: false, allowMultipleUpstreamSources: true,  isFirstProcess: false, customFieldSchema: null, allowFinishedGoodsSource: false, inputUnit: 'kg', outputUnit: null },
];

const PROCESSES = ref<ProcEntry[]>([...FALLBACK_PROCESSES]);

// upstream chain: 链中前一道工序 (按 PROCESSES 顺序; 第一道 → null)。
// SP-F role-mode fix: 键改用唯一 procKey (order), 不再用 code (会碰撞)。
const upstreamKeyOf = computed<Record<string, string | null>>(() => {
  const map: Record<string, string | null> = {};
  PROCESSES.value.forEach((p, i) => {
    map[procKey(p)] = i > 0 ? procKey(PROCESSES.value[i - 1]) : null;
  });
  return map;
});

/**
 * 动态解析产品工序链 (G0 + SP-G A 真自由配置):
 *
 * 取该产品 ProductWorkProcess (按 processOrder), 两阶段映射:
 *
 * 1. Role mode (SP-G A): 若本产品任意工序有非 null 的 defaultCostCategory,
 *    则对每道工序用 ROLE_TO_ARCHETYPE[defaultCostCategory] 映射; 未登记的
 *    角色回退 'chaoshui' (通用加工步骤). Role mode 所有 code 均有列定义 → 不过滤。
 *
 * 2. Name-keyword fallback: 产品所有工序均无 defaultCostCategory 时,
 *    按工序名关键词子串匹配. 未匹配工序不再丢弃 (Bug A 修), 改按位置默认:
 *      - 排序后 index 0 (首道) → 'xiuyou' (原料领料入口)
 *      - 其余 → 'chaoshui' (通用加工步骤)
 *    理由: 只有首道是领料 (消耗原料); 其余工序即使名字像 "修油" (xiuyou 关键词)
 *    也应是普通工序, 因为不在首位。
 *
 * 失败或 <1 道可录 → 回退切片.
 */
async function resolveProcesses() {
  try {
    const resp = await getProductWorkProcesses(props.factoryId, props.productTypeId);
    const items = resp.data || [];

    // Determine if any process has a role configured (non-null defaultCostCategory)
    const hasRoles = items.some((it) => it.defaultCostCategory != null);

    // Pre-sort by processOrder so position-based default (index 0 = 首道) is correct.
    const sorted = [...items].sort((a, b) => a.processOrder - b.processOrder);

    const mapped: ProcEntry[] = sorted
      .map((it, idx) => {
        let code: string;
        if (hasRoles) {
          // Role mode: map via defaultCostCategory; unknown roles fall back to 'chaoshui'
          code = it.defaultCostCategory != null
            ? (ROLE_TO_ARCHETYPE[it.defaultCostCategory] ?? 'chaoshui')
            : 'chaoshui';
        } else {
          // Name-keyword fallback — no longer drops unmatched processes (Bug A fix).
          // nameToConfigCode returns undefined for names like "去舌胎膜" / "拆包".
          // Position-based default: 首道 → 'xiuyou', 其余 → 'chaoshui'.
          // Additionally guard: even if keyword matches 'xiuyou' for a non-first process,
          // treat it as 'chaoshui' to avoid assigning raw-material intake role to mid-chain steps.
          const kw = nameToConfigCode(it.processName);
          if (idx === 0) {
            // 链起步道: 按工序名 ACTUAL archetype 决定录入形态, 不再无条件强制 xiuyou (领料)。
            // 修油 / 无名称匹配 → 'xiuyou' (原料领料入口); 滚揉/焯水/去舌苔/熟制/气调 → 各自 archetype
            // (单上游 / 混锅), 使"从滚揉起步选半成品"可行 (链起步道 upstream 空, 仅 SFI 可选)。
            code = (kw && kw !== 'xiuyou') ? kw : 'xiuyou';
          } else {
            // Non-first: use keyword match only if it is NOT xiuyou, else 'chaoshui'
            code = (kw && kw !== 'xiuyou') ? kw : 'chaoshui';
          }
        }
        // 张权 R4: 该工序是否被配置为「半成品注入工序」→ 透传给 ProcessDataTable 作 config-driven picker gating。
        // G1: idx===0 (排序后链内第一道) → isFirstProcess=true, 作 supportsUpstreamSources 的
        // config-driven 主判据 (archetype 硬编码仍作兜底, 见 ProcessDataTable 内注释)。
        // G2: customFieldSchema 直接透传 (join 只读字段, 未开启则 null)。
        return {
          code,
          order: it.processOrder,
          label: it.processName,
          allowInjection: it.allowSemiFinishedInjection === true,
          allowMultipleUpstreamSources: it.allowMultipleUpstreamSources === true,
          isFirstProcess: idx === 0,
          customFieldSchema: it.customFieldSchema ?? null,
          allowFinishedGoodsSource: it.allowFinishedGoodsSource === true,
          inputUnit: it.unitOverride?.trim() || it.defaultUnit?.trim() || 'kg',
          outputUnit: it.defaultOutputUnit?.trim() || null,
        };
      })
      // Role mode: code always valid. Name mode: code is always 'xiuyou'|'chaoshui'|known keyword.
      // Filter only truly missing config entries (should not happen, but defensive).
      .filter((p): p is ProcEntry => !!PROCESS_SHEET_CONFIG[p.code]);

    if (mapped.length >= 1) PROCESSES.value = mapped;
  } catch (e) {
    console.warn('[ProcessSheet] resolveProcesses 失败, 回退切片', e);
  }
}

// -------------------------------------------------------------------------
// State
// -------------------------------------------------------------------------
// SP-F role-mode fix: activeTab + 所有 per-process map 一律用唯一 procKey (order),
// 不用 code —— 否则同 archetype 多工序 tab/库存/行/refs 全碰撞。
const activeTab = ref<string>(procKey(FALLBACK_PROCESSES[0]));
const loading = ref(false);

// inventory/rows/refs per process: procKey(order) → ... (动态填充, 键随 PROCESSES 变)
const inventoryMap = ref<Record<string, ProcessSheetInventoryItem[]>>({});
const initialRowsMap = ref<Record<string, ProcessSheetRowView[]>>({});
const inventoryTableRefs = ref<Record<string, InstanceType<typeof InventoryTable> | null>>({});
const dataTableRefs = ref<Record<string, InstanceType<typeof ProcessDataTable> | null>>({});
// F006 双出成率总览 (全工序汇总卡 — 张权需求: 一眼看全链对上工序/对原料率)
const yieldCardRef = ref<InstanceType<typeof YieldCardTable> | null>(null);
const yieldOverviewActive = ref<string[]>(['yield']);
let loadAllSeq = 0;

// -------------------------------------------------------------------------
// Load all data on mount
// -------------------------------------------------------------------------
async function loadAll() {
  if (!props.factoryId || !props.planId) return;
  const seq = ++loadAllSeq;
  loading.value = true;
  try {
    // G0: 先解析本产品工序链 (动态), 再加载各道库存/行
    await resolveProcesses();
    if (seq !== loadAllSeq) return;
    if (!PROCESSES.value.some((p) => procKey(p) === activeTab.value)) {
      activeTab.value = PROCESSES.value[0] ? procKey(PROCESSES.value[0]) : procKey(FALLBACK_PROCESSES[0]);
    }
    await Promise.all(
      PROCESSES.value.map(async (proc) => {
        // load inventory (including upstream for dropdown purposes)。
        // SP-F role-mode fix: 传 proc.order 让后端双键过滤, 隔离同 archetype 多工序;
        // 结果按唯一 procKey 入 map (不用 proc.code, 会碰撞)。
        const [invResp, rowsResp] = await Promise.all([
          getInventory(props.factoryId, props.planId, proc.code, proc.order),
          getRows(props.factoryId, props.planId, proc.code, proc.order),
        ]);
        if (seq !== loadAllSeq) return;
        inventoryMap.value[procKey(proc)] = Array.isArray(invResp.data) ? invResp.data : [];
        initialRowsMap.value[procKey(proc)] = Array.isArray(rowsResp.data) ? rowsResp.data : [];
      })
    );
  } catch (e) {
    if (seq !== loadAllSeq) return;
    console.error('[ProcessSheet] loadAll error', e);
  } finally {
    if (seq === loadAllSeq) loading.value = false;
  }
}

watch(
  () => [props.factoryId, props.planId, props.productTypeId],
  () => {
    loadAllSeq++;
    inventoryMap.value = {};
    initialRowsMap.value = {};
    inventoryTableRefs.value = {};
    void loadAll();
  },
  { immediate: true },
);

// -------------------------------------------------------------------------
// After a row is saved: refresh inventory of this process + active tab inventory table
// -------------------------------------------------------------------------
async function onRowSaved(savedProc: ProcEntry) {
  // Refresh inventory for ALL processes (a saved row can affect downstream dropdowns)。
  // SP-F role-mode fix: 按唯一 procKey 刷新 + 传 order 双键过滤。
  await Promise.all(
    PROCESSES.value.map(async (p) => {
      try {
        const resp = await getInventory(props.factoryId, props.planId, p.code, p.order);
        inventoryMap.value[procKey(p)] = resp.data || [];
      } catch {
        // ignore
      }
    })
  );
  // Also trigger the InventoryTable component's refresh if in view (keyed by procKey)
  inventoryTableRefs.value[procKey(savedProc)]?.refresh?.();
  // Bug 1 修复 (fix/process-entry-cache-and-blend-cost, F006 现场走查): 半成品(SFI)/成品(FG)/
  // 原料批次余量是跨 process tab 共享的常驻库存, 每个 tab 的 ProcessDataTable 只在挂载时加载
  // 一次 (el-tabs 不销毁其它 tab)。一个 tab 保存了改变这些余量的产出后, 其它 tab (甚至同一
  // tab 下次录入) 的下拉/校验仍停在旧余量, 造成假阳性"用量超出库存"拦截。这里对全部 tab
  // (不仅是刚保存的 savedProc) 触发刷新, 因为 SFI/FG 可能被任意后续工序选作投料来源。
  for (const p of PROCESSES.value) {
    dataTableRefs.value[procKey(p)]?.refreshSharedInventories?.();
  }
  // F006 双出成率总览: 保存后刷新全工序汇总卡 (出成率随录入更新)
  yieldCardRef.value?.refresh?.();
}

// Helper: get upstream inventory items for a given process (keyed by unique procKey)
function upstreamItems(proc: ProcEntry): ProcessSheetInventoryItem[] {
  const upKey = upstreamKeyOf.value[procKey(proc)];
  if (!upKey) return [];
  return inventoryMap.value[upKey] || [];
}

// Bug 1 (2026-07 现场走查): 来源批次下拉的 label 曾在 ProcessDataTable 内部按 archetype
// processCode 硬编码(如"gunrou → 修油批次"), 与 G0 动态工序链的真实前置工序名不符——
// role-mode/关键词回退下同一 archetype 可能对应不同真实工序名, 硬编码必错。改为按 PROCESSES
// 实际顺序取前一道的真实 label(工序真实名称), 传给子组件动态渲染, 不再由子组件自己猜测。
function upstreamLabelOf(proc: ProcEntry): string | undefined {
  const idx = PROCESSES.value.findIndex((p) => procKey(p) === procKey(proc));
  return idx > 0 ? PROCESSES.value[idx - 1].label : undefined;
}

// -------------------------------------------------------------------------
// 未保存草稿聚合 (防呆): 任一工序 tab 有未保存草稿行 → 整个抽屉视为 dirty，
// 父组件 (list.vue 抽屉) 关闭前据此二次确认。
// -------------------------------------------------------------------------
const hasUnsavedRows = computed(() =>
  Object.values(dataTableRefs.value).some((tbl) => tbl?.hasUnsavedRows === true)
);

defineExpose({ hasUnsavedRows });
</script>

<template>
  <div v-loading="loading" style="height:100%;display:flex;flex-direction:column">
    <!-- Header -->
    <div style="padding:0 4px 12px;flex-shrink:0;display:flex;align-items:flex-start;justify-content:space-between;gap:12px">
      <div>
        <div style="font-size:15px;font-weight:600;color:#303133">
          逐工序电子表格
          <span v-if="productName" style="font-weight:400;color:#606266;margin-left:8px">{{ productName }}</span>
          <span v-if="plannedQuantity" style="font-size:12px;color:#909399;margin-left:8px">
            {{ formatPlannedInput(plannedQuantity, plannedUnit) }}
            <el-tooltip content="计划数量按首道工序投入单位；最终成品单位看末道工序产出单位" placement="top">
              <span style="margin-left:3px;cursor:help">?</span>
            </el-tooltip>
          </span>
        </div>
        <div style="font-size:12px;color:#909399;margin-top:4px">
          每行独立保存 · 保存后自动生成批次号 · 可随时追加
        </div>
      </div>
      <!-- View-mode toggle: applies to all process tabs simultaneously -->
      <el-segmented
        :model-value="viewMode"
        :options="[{ label: '电子表格', value: 'grid' }, { label: '卡片', value: 'card' }]"
        size="small"
        style="flex-shrink:0;align-self:center"
        @change="onViewModeChange"
      />
    </div>

    <!-- F006 双出成率总览 — 全工序汇总 (对上工序 / 对原料), 默认展开, 可折叠腾空间 -->
    <el-collapse v-model="yieldOverviewActive" style="flex-shrink:0;margin-bottom:8px">
      <el-collapse-item name="yield">
        <template #title>
          <span style="font-size:12px;font-weight:600;color:#606266">双出成率总览 — 全工序（对上工序 / 对原料）</span>
        </template>
        <YieldCardTable ref="yieldCardRef" :factory-id="factoryId" :plan-id="planId" />
      </el-collapse-item>
    </el-collapse>

    <!-- Tabs -->
    <el-tabs v-model="activeTab" style="flex:1;overflow:hidden;display:flex;flex-direction:column" tab-position="top">
      <!-- SP-F role-mode fix: tab :key/:name 用唯一 procKey(order), 不用 code (同 archetype 会碰撞);
           per-process map 取值一律 procKey(proc); process-code 仍传 archetype 给列定义+后端。 -->
      <el-tab-pane
        v-for="proc in PROCESSES"
        :key="procKey(proc)"
        :label="proc.label"
        :name="procKey(proc)"
        style="height:100%;overflow-y:auto;padding:4px 0"
      >
        <!-- Vertical stack: data-entry table (full width) → 半成品库存 (full width below) -->
        <div style="display:flex;flex-direction:column;gap:16px">
          <!-- Data entry table — full width -->
          <ProcessDataTable
            :ref="(el: any) => dataTableRefs[procKey(proc)] = el"
            :factory-id="factoryId"
            :plan-id="planId"
            :process-code="proc.code"
            :process-order="proc.order"
            :process-label="proc.label"
            :allow-semi-finished-injection="proc.allowInjection"
            :allow-multiple-upstream-sources="proc.allowMultipleUpstreamSources"
            :is-first-process="proc.isFirstProcess"
            :custom-field-schema="proc.customFieldSchema"
            :allow-finished-goods-source="proc.allowFinishedGoodsSource"
            :input-unit="proc.inputUnit"
            :output-unit="proc.outputUnit"
            :upstream-process-label="upstreamLabelOf(proc)"
            :product-type-id="productTypeId"
            :upstream-items="upstreamItems(proc)"
            :own-inventory-items="inventoryMap[procKey(proc)]"
            :initial-rows="initialRowsMap[procKey(proc)] || []"
            :view-mode="viewMode"
            @row-saved="onRowSaved(proc)"
          />

          <!-- 半成品库存 — full width below the grid -->
          <div>
            <div style="font-size:12px;font-weight:600;color:#606266;margin-bottom:6px">
              {{ proc.label }} 半成品库存
            </div>
            <InventoryTable
              :ref="(el: any) => inventoryTableRefs[procKey(proc)] = el"
              :factory-id="factoryId"
              :plan-id="planId"
              :process-code="proc.code"
              :process-order="proc.order"
            />
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
