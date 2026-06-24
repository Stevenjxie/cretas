<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { getInventory, getRows, type ProcessSheetInventoryItem, type ProcessSheetRowView } from '@/api/processSheet';
import { getProductWorkProcesses } from '@/api/processProduction';
import { PROCESS_SHEET_CONFIG } from './PROCESS_SHEET_CONFIG';
import ProcessDataTable from './ProcessDataTable.vue';
import InventoryTable from './InventoryTable.vue';

// -------------------------------------------------------------------------
// View mode: 'grid' (电子表格) | 'card' (卡片)
// Persisted in localStorage so the preference survives page refreshes.
// -------------------------------------------------------------------------
const VIEW_MODE_KEY = 'sp-f-process-sheet-view';
const savedView = localStorage.getItem(VIEW_MODE_KEY);
const viewMode = ref<'grid' | 'card'>(savedView === 'card' ? 'card' : 'grid');

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
}>();
const emit = defineEmits<{
  (e: 'submitted'): void;
}>();

// -------------------------------------------------------------------------
// 工序链 — 动态从产品工序配置(ProductWorkProcess)解析 (G0)
// -------------------------------------------------------------------------
type ProcEntry = { code: string; order: number; label: string };

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
  { code: 'xiuyou',   order: 1, label: '修油' },
  { code: 'chaoshui', order: 2, label: '焯水' },
  { code: 'shuzhi',   order: 3, label: '熟制' },
];

const PROCESSES = ref<ProcEntry[]>([...FALLBACK_PROCESSES]);

// upstream chain: 链中前一道有列定义的工序 (跳过未铺工序 → 折叠接线自动保持)
const upstreamCodeOf = computed<Record<string, string | null>>(() => {
  const map: Record<string, string | null> = {};
  PROCESSES.value.forEach((p, i) => { map[p.code] = i > 0 ? PROCESSES.value[i - 1].code : null; });
  return map;
});

/**
 * 动态解析产品工序链 (G0): 取该产品 ProductWorkProcess (按 processOrder),
 * 映射 processName → 列定义 key, 过滤掉无列定义的工序 (未铺的滚揉/去舌苔/气调),
 * 排序后即得本产品在电子表格里能录的工序链。失败或 <1 道可录 → 回退切片。
 */
async function resolveProcesses() {
  try {
    const resp = await getProductWorkProcesses(props.factoryId, props.productTypeId);
    const items = resp.data || [];
    const mapped: ProcEntry[] = items
      .map((it) => ({ code: nameToConfigCode(it.processName) as string, order: it.processOrder, label: it.processName }))
      .filter((p): p is ProcEntry => !!p.code && !!PROCESS_SHEET_CONFIG[p.code])
      .sort((a, b) => a.order - b.order);
    if (mapped.length >= 1) PROCESSES.value = mapped;
  } catch (e) {
    console.warn('[ProcessSheet] resolveProcesses 失败, 回退切片', e);
  }
}

// -------------------------------------------------------------------------
// State
// -------------------------------------------------------------------------
const activeTab = ref<string>(FALLBACK_PROCESSES[0].code);
const loading = ref(false);

// inventory/rows/refs per process: processCode → ... (动态填充, 键随 PROCESSES 变)
const inventoryMap = ref<Record<string, ProcessSheetInventoryItem[]>>({});
const initialRowsMap = ref<Record<string, ProcessSheetRowView[]>>({});
const inventoryTableRefs = ref<Record<string, InstanceType<typeof InventoryTable> | null>>({});

// -------------------------------------------------------------------------
// Load all data on mount
// -------------------------------------------------------------------------
async function loadAll() {
  if (!props.factoryId || !props.planId) return;
  loading.value = true;
  try {
    // G0: 先解析本产品工序链 (动态), 再加载各道库存/行
    await resolveProcesses();
    if (!PROCESSES.value.some((p) => p.code === activeTab.value)) {
      activeTab.value = PROCESSES.value[0]?.code ?? FALLBACK_PROCESSES[0].code;
    }
    await Promise.all(
      PROCESSES.value.map(async (proc) => {
        // load inventory (including upstream for dropdown purposes)
        const [invResp, rowsResp] = await Promise.all([
          getInventory(props.factoryId, props.planId, proc.code),
          getRows(props.factoryId, props.planId, proc.code),
        ]);
        inventoryMap.value[proc.code] = invResp.data || [];
        initialRowsMap.value[proc.code] = rowsResp.data || [];
      })
    );
  } catch (e) {
    console.error('[ProcessSheet] loadAll error', e);
  } finally {
    loading.value = false;
  }
}

onMounted(loadAll);

// -------------------------------------------------------------------------
// After a row is saved: refresh inventory of this process + active tab inventory table
// -------------------------------------------------------------------------
async function onRowSaved(processCode: string) {
  // Refresh inventory for this process and all downstream processes
  const toRefresh = PROCESSES.value.map((p) => p.code);
  await Promise.all(
    toRefresh.map(async (code) => {
      try {
        const resp = await getInventory(props.factoryId, props.planId, code);
        inventoryMap.value[code] = resp.data || [];
      } catch {
        // ignore
      }
    })
  );
  // Also trigger the InventoryTable component's refresh if in view
  inventoryTableRefs.value[processCode]?.refresh?.();
}

// Helper: get upstream inventory items for a given process
function upstreamItems(processCode: string): ProcessSheetInventoryItem[] {
  const upCode = upstreamCodeOf.value[processCode];
  if (!upCode) return [];
  return inventoryMap.value[upCode] || [];
}
</script>

<template>
  <div v-loading="loading" style="height:100%;display:flex;flex-direction:column">
    <!-- Header -->
    <div style="padding:0 4px 12px;flex-shrink:0;display:flex;align-items:flex-start;justify-content:space-between;gap:12px">
      <div>
        <div style="font-size:15px;font-weight:600;color:#303133">
          逐工序电子表格
          <span v-if="productName" style="font-weight:400;color:#606266;margin-left:8px">{{ productName }}</span>
          <span v-if="plannedQuantity" style="font-size:12px;color:#909399;margin-left:8px">计划 {{ plannedQuantity }} kg</span>
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

    <!-- Tabs -->
    <el-tabs v-model="activeTab" style="flex:1;overflow:hidden;display:flex;flex-direction:column" tab-position="top">
      <el-tab-pane
        v-for="proc in PROCESSES"
        :key="proc.code"
        :label="proc.label"
        :name="proc.code"
        style="height:100%;overflow-y:auto;padding:4px 0"
      >
        <!-- Vertical stack: data-entry table (full width) → 半成品库存 (full width below) -->
        <div style="display:flex;flex-direction:column;gap:16px">
          <!-- Data entry table — full width -->
          <ProcessDataTable
            :factory-id="factoryId"
            :plan-id="planId"
            :process-code="proc.code"
            :process-order="proc.order"
            :product-type-id="productTypeId"
            :upstream-items="upstreamItems(proc.code)"
            :own-inventory-items="inventoryMap[proc.code]"
            :initial-rows="initialRowsMap[proc.code]"
            :view-mode="viewMode"
            @row-saved="onRowSaved(proc.code)"
          />

          <!-- 半成品库存 — full width below the grid -->
          <div>
            <div style="font-size:12px;font-weight:600;color:#606266;margin-bottom:6px">
              {{ proc.label }} 半成品库存
            </div>
            <InventoryTable
              :ref="(el: any) => inventoryTableRefs[proc.code] = el"
              :factory-id="factoryId"
              :plan-id="planId"
              :process-code="proc.code"
            />
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
