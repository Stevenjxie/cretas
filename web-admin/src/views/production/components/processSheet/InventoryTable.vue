<script setup lang="ts">
import { ref, watch } from 'vue';
import { getInventory, type ProcessSheetInventoryItem } from '@/api/processSheet';

const props = defineProps<{
  factoryId: string;
  planId: string;
  processCode: string;
  /** SP-F role-mode fix: 链内唯一工序序号; 传给后端双键过滤, 隔离同 archetype 多工序库存。 */
  processOrder?: number;
}>();

const rows = ref<ProcessSheetInventoryItem[]>([]);
const loading = ref(false);

async function refresh() {
  if (!props.factoryId || !props.planId) return;
  loading.value = true;
  try {
    const resp = await getInventory(props.factoryId, props.planId, props.processCode, props.processOrder);
    rows.value = resp.data || [];
  } catch {
    rows.value = [];
  } finally {
    loading.value = false;
  }
}

watch(
  () => [props.factoryId, props.planId, props.processCode, props.processOrder],
  () => { void refresh(); },
  { immediate: true },
);
defineExpose({ refresh });
</script>

<template>
  <el-table :data="rows" v-loading="loading" size="small" border style="width:100%">
    <el-table-column prop="batchNumber" label="批次号" min-width="160" />
    <el-table-column prop="produced" label="产出(kg)" width="90" align="right" />
    <el-table-column prop="used" label="已用(kg)" width="90" align="right" />
    <el-table-column prop="remaining" label="剩余(kg)" width="90" align="right">
      <template #default="{ row }">
        <span :style="{ color: row.remaining <= 0 ? '#f56c6c' : '#67c23a' }">
          {{ row.remaining }}
        </span>
      </template>
    </el-table-column>
    <el-table-column prop="unitPrice" label="单价(¥/kg)" width="100" align="right">
      <template #default="{ row }">
        {{ row.unitPrice ? row.unitPrice.toFixed(2) : '—' }}
      </template>
    </el-table-column>
    <el-table-column prop="status" label="状态" width="80" align="center">
      <template #default="{ row }">
        <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
          {{ row.status === 'ACTIVE' ? '可用' : '耗尽' }}
        </el-tag>
      </template>
    </el-table-column>
  </el-table>
  <div v-if="rows.length === 0 && !loading" style="text-align:center;color:#909399;padding:12px;font-size:12px">
    暂无半成品库存
  </div>
</template>
