<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { getInventory, type ProcessSheetInventoryItem } from '@/api/processSheet';
import { normalizeMassQuantityForReporting } from '@/utils/processSheetUnits';

const props = defineProps<{
  factoryId: string;
  planId: string;
  processCode: string;
  /** SP-F role-mode fix: 链内唯一工序序号; 传给后端双键过滤, 隔离同 archetype 多工序库存。 */
  processOrder?: number;
}>();

const rows = ref<ProcessSheetInventoryItem[]>([]);
const loading = ref(false);
const errorMessage = ref('');
let refreshSeq = 0;

const displayRows = computed(() => rows.value.map((row) => {
  const produced = normalizeMassQuantityForReporting(Number(row.produced), row.unit);
  const used = normalizeMassQuantityForReporting(Number(row.used), row.unit);
  const remaining = normalizeMassQuantityForReporting(Number(row.remaining), row.unit);
  const sourceUnit = row.unit?.trim().toLowerCase();
  return {
    ...row,
    produced: produced.quantity,
    used: used.quantity,
    remaining: remaining.quantity,
    unit: produced.unit,
    unitPrice: row.unitPrice == null
      ? row.unitPrice
      : ((sourceUnit === 'g' || sourceUnit === '克') ? row.unitPrice * 1000 : row.unitPrice),
  };
}));

async function refresh() {
  if (!props.factoryId || !props.planId) return;
  const seq = ++refreshSeq;
  loading.value = true;
  try {
    const resp = await getInventory(props.factoryId, props.planId, props.processCode, props.processOrder);
    if (seq !== refreshSeq) return;
    rows.value = resp.data || [];
    errorMessage.value = '';
  } catch {
    if (seq !== refreshSeq) return;
    rows.value = [];
    errorMessage.value = '半成品库存加载失败，请刷新重试。';
  } finally {
    if (seq === refreshSeq) loading.value = false;
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
  <el-alert
    v-if="errorMessage"
    type="error"
    show-icon
    :closable="false"
    style="margin-bottom:8px"
  >
    <template #default>
      <span>{{ errorMessage }}</span>
      <el-button link type="primary" size="small" @click="refresh">刷新重试</el-button>
    </template>
  </el-alert>
  <el-table
    :data="displayRows"
    v-loading="loading"
    size="small"
    border
    empty-text="暂无半成品库存记录"
    style="width:100%"
  >
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
  <div v-if="rows.length === 0 && !loading && !errorMessage" style="text-align:center;color:#909399;padding:12px;font-size:12px">
    暂无半成品库存；保存当前工序有效产出后会生成批次库存。
  </div>
</template>
