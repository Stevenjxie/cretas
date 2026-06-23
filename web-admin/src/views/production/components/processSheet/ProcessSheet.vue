<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { getInventory, getRows, type ProcessSheetInventoryItem, type ProcessSheetRowView } from '@/api/processSheet';
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
// 3 processes in chain order
// -------------------------------------------------------------------------
const PROCESSES = [
  { code: 'xiuyou',   order: 1, label: '修油' },
  { code: 'chaoshui', order: 2, label: '焯水' },
  { code: 'shuzhi',   order: 3, label: '熟制' },
] as const;

// upstream chain: xiuyou→null, chaoshui→xiuyou, shuzhi→chaoshui
const upstreamCodeOf: Record<string, string | null> = {
  xiuyou:   null,
  chaoshui: 'xiuyou',
  shuzhi:   'chaoshui',
};

// -------------------------------------------------------------------------
// State
// -------------------------------------------------------------------------
const activeTab = ref<string>('xiuyou');
const loading = ref(false);

// inventory per process: processCode → items
const inventoryMap = ref<Record<string, ProcessSheetInventoryItem[]>>({
  xiuyou:   [],
  chaoshui: [],
  shuzhi:   [],
});

// initial rows per process (loaded once on mount)
const initialRowsMap = ref<Record<string, ProcessSheetRowView[]>>({
  xiuyou:   [],
  chaoshui: [],
  shuzhi:   [],
});

// refs to InventoryTable components so we can call .refresh()
const inventoryTableRefs = ref<Record<string, InstanceType<typeof InventoryTable> | null>>({
  xiuyou:   null,
  chaoshui: null,
  shuzhi:   null,
});

// -------------------------------------------------------------------------
// Load all data on mount
// -------------------------------------------------------------------------
async function loadAll() {
  if (!props.factoryId || !props.planId) return;
  loading.value = true;
  try {
    await Promise.all(
      PROCESSES.map(async (proc) => {
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
  const toRefresh = PROCESSES.map((p) => p.code);
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
  const upCode = upstreamCodeOf[processCode];
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
        <el-row :gutter="12" style="height:100%">
          <!-- Left: data entry table -->
          <el-col :span="16" style="height:100%;overflow-y:auto">
            <ProcessDataTable
              :factory-id="factoryId"
              :plan-id="planId"
              :process-code="proc.code"
              :process-order="proc.order"
              :product-type-id="productTypeId"
              :upstream-items="upstreamItems(proc.code)"
              :initial-rows="initialRowsMap[proc.code]"
              :view-mode="viewMode"
              @row-saved="onRowSaved(proc.code)"
            />
          </el-col>

          <!-- Right: inventory sub-table -->
          <el-col :span="8" style="height:100%;overflow-y:auto">
            <div style="font-size:12px;font-weight:600;color:#606266;margin-bottom:6px">
              {{ proc.label }} 半成品库存
            </div>
            <InventoryTable
              :ref="(el: any) => inventoryTableRefs[proc.code] = el"
              :factory-id="factoryId"
              :plan-id="planId"
              :process-code="proc.code"
            />
          </el-col>
        </el-row>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
