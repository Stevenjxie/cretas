<script setup lang="ts">
/**
 * F006 双出成率: 计划级半成品库存卡 (全工序汇总视图).
 *
 * 调用 getInventoryYieldCard() 获取该计划所有工序的 WIP 行, 展示:
 *   - 工序序号 / 工序名 / 批次号 / 产出量 / 已用 / 剩余 / 单价
 *   - stepYieldRate (对上工序出成率 %)
 *   - cumulativeYieldRate (对原料累计出成率 %)
 *
 * null 值显示为 "—" (诚实, 不造假 — per .claude/rules/api-response-handling.md).
 */
import { ref, watch } from 'vue';
import { getInventoryYieldCard, type ProcessSheetInventoryItem } from '@/api/processSheet';

const props = defineProps<{
  factoryId: string;
  planId: string;
}>();

const rows = ref<ProcessSheetInventoryItem[]>([]);
const loading = ref(false);

function fmtRate(v: number | null | undefined): string {
  if (v == null) return '—';
  return v.toFixed(2) + '%';
}

function fmtQty(v: number | null | undefined): string {
  if (v == null) return '—';
  return String(v);
}

function fmtPrice(v: number | null | undefined): string {
  if (v == null) return '—';
  return v.toFixed(2);
}

function rateColor(v: number | null | undefined): string {
  if (v == null) return '';
  if (v >= 90) return '#67c23a';
  if (v >= 75) return '#e6a23c';
  return '#f56c6c';
}

async function refresh() {
  if (!props.factoryId || !props.planId) return;
  loading.value = true;
  try {
    const resp = await getInventoryYieldCard(props.factoryId, props.planId);
    rows.value = resp.data || [];
  } catch {
    rows.value = [];
  } finally {
    loading.value = false;
  }
}

watch(
  () => [props.factoryId, props.planId],
  () => { void refresh(); },
  { immediate: true },
);
defineExpose({ refresh });
</script>

<template>
  <el-table
    :data="rows"
    v-loading="loading"
    size="small"
    border
    style="width: 100%"
    :row-class-name="() => ''"
  >
    <el-table-column prop="processOrder" label="序" width="48" align="center" />
    <el-table-column prop="processName" label="工序" min-width="90">
      <template #default="{ row }">
        {{ row.processName || '—' }}
      </template>
    </el-table-column>
    <el-table-column prop="batchNumber" label="批次号" min-width="150" />
    <el-table-column label="产出" width="80" align="right">
      <template #default="{ row }">
        {{ fmtQty(row.produced) }}{{ row.unit ? ' ' + row.unit : '' }}
      </template>
    </el-table-column>
    <el-table-column label="已用" width="80" align="right">
      <template #default="{ row }">
        {{ fmtQty(row.used) }}
      </template>
    </el-table-column>
    <el-table-column label="剩余" width="80" align="right">
      <template #default="{ row }">
        <span :style="{ color: (row.remaining ?? 0) <= 0 ? '#f56c6c' : '#67c23a' }">
          {{ fmtQty(row.remaining) }}
        </span>
      </template>
    </el-table-column>
    <el-table-column label="单价(¥)" width="86" align="right">
      <template #default="{ row }">
        {{ fmtPrice(row.unitPrice) }}
      </template>
    </el-table-column>
    <el-table-column label="对上工序出成" width="110" align="right">
      <template #default="{ row }">
        <span :style="{ color: rateColor(row.stepYieldRate), fontWeight: 'bold' }">
          {{ fmtRate(row.stepYieldRate) }}
        </span>
      </template>
    </el-table-column>
    <el-table-column label="对原料累计" width="100" align="right">
      <template #default="{ row }">
        <span :style="{ color: rateColor(row.cumulativeYieldRate) }">
          {{ fmtRate(row.cumulativeYieldRate) }}
        </span>
      </template>
    </el-table-column>
    <el-table-column prop="status" label="状态" width="72" align="center">
      <template #default="{ row }">
        <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
          {{ row.status === 'ACTIVE' ? '可用' : '耗尽' }}
        </el-tag>
      </template>
    </el-table-column>
  </el-table>
  <div
    v-if="rows.length === 0 && !loading"
    style="text-align: center; color: #909399; padding: 12px; font-size: 12px"
  >
    暂无半成品库存记录
  </div>
</template>
