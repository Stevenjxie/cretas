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
import { computed, ref, watch } from 'vue';
import { getInventoryYieldCard, type ProcessSheetInventoryItem } from '@/api/processSheet';

const props = defineProps<{
  factoryId: string;
  planId: string;
}>();

const rows = ref<ProcessSheetInventoryItem[]>([]);
const loading = ref(false);
const errorMessage = ref('');
let refreshSeq = 0;

const missingYieldCount = computed(() =>
  rows.value.filter((row) => row.cumulativeYieldRate == null).length,
);

const missingCostCount = computed(() =>
  rows.value.filter((row) => row.rowTotalCost == null || row.unitPrice == null).length,
);

function fmtRate(v: number | null | undefined): string {
  if (v == null) return '—';
  return v.toFixed(2) + '%';
}

function fmtQty(v: number | null | undefined, digits?: number): string {
  if (v == null) return '—';
  if (digits != null) return Number(v).toFixed(digits);
  return String(v);
}

function fmtPrice(v: number | null | undefined): string {
  if (v == null) return '—';
  return v.toFixed(2);
}

function fmtDate(v: string | null | undefined): string {
  if (!v) return '—';
  // 后端已是 ISO "YYYY-MM-DD" (LocalDate); 兼容偶发 datetime 字符串只取日期部分。
  return v.slice(0, 10);
}

function fmtMoney(v: number | null | undefined): string {
  if (v == null) return '—';
  return '¥' + Number(v).toFixed(2);
}

function rateColor(v: number | null | undefined): string {
  if (v == null) return '';
  if (v >= 90) return '#67c23a';
  if (v >= 75) return '#e6a23c';
  return '#f56c6c';
}

async function refresh() {
  if (!props.factoryId || !props.planId) return;
  const seq = ++refreshSeq;
  loading.value = true;
  errorMessage.value = '';
  try {
    const resp = await getInventoryYieldCard(props.factoryId, props.planId);
    if (seq !== refreshSeq) return;
    rows.value = resp.data || [];
  } catch {
    if (seq !== refreshSeq) return;
    rows.value = [];
    errorMessage.value = '出成率数据加载失败，请刷新重试。';
  } finally {
    if (seq === refreshSeq) loading.value = false;
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
  <el-alert
    v-if="errorMessage"
    type="error"
    show-icon
    :closable="false"
    class="yield-card-error"
  >
    <template #title>
      出成率数据加载失败
    </template>
    <template #default>
      <span>{{ errorMessage }}</span>
      <el-button link type="primary" size="small" @click="refresh">刷新重试</el-button>
    </template>
  </el-alert>
  <div v-if="missingYieldCount > 0 || missingCostCount > 0" class="yield-card-warnings">
    <el-alert
      v-if="missingYieldCount > 0"
      type="warning"
      show-icon
      :closable="false"
      class="yield-card-warning"
    >
      <template #title>
        有 {{ missingYieldCount }} 行累计出成率未显示
      </template>
      <template #default>
        检查来源批次、原料投入或 SKU 标准克重配置；未确认来源时系统不会硬算百分比。
      </template>
    </el-alert>
    <el-alert
      v-if="missingCostCount > 0"
      type="warning"
      show-icon
      :closable="false"
      class="yield-card-warning"
    >
      <template #title>
        有 {{ missingCostCount }} 行成本未显示
      </template>
      <template #default>
        检查来源半成品单价、工序成本或成品成本结算数据。
      </template>
    </el-alert>
  </div>
  <el-table
    :data="rows"
    v-loading="loading"
    size="small"
    border
    empty-text="暂无半成品库存记录"
    style="width: 100%"
    :row-class-name="() => ''"
  >
    <el-table-column prop="processOrder" label="序" width="48" align="center" />
    <el-table-column label="流程日期" width="100" align="center">
      <template #default="{ row }">
        {{ fmtDate(row.processDate) }}
      </template>
    </el-table-column>
    <el-table-column prop="processName" label="工序" min-width="90" show-overflow-tooltip>
      <template #default="{ row }">
        {{ row.processName || '—' }}
      </template>
    </el-table-column>
    <el-table-column prop="batchNumber" label="批次号" min-width="150" show-overflow-tooltip />
    <el-table-column label="来源批次" min-width="140" show-overflow-tooltip>
      <template #default="{ row }">
        {{ row.sourceBatchNumber || '—' }}
      </template>
    </el-table-column>
    <el-table-column label="领用(kg)" width="92" align="right">
      <template #default="{ row }">
        {{ fmtQty(row.feedQuantity) }}
      </template>
    </el-table-column>
    <el-table-column label="领用占比" width="92" align="right">
      <template #default="{ row }">
        {{ fmtRate(row.sourceConsumedRatio) }}
      </template>
    </el-table-column>
    <el-table-column label="继承原料(kg)" width="116" align="right">
      <template #default="{ row }">
        {{ fmtQty(row.inheritedRawEquivalentQuantity, 2) }}
      </template>
    </el-table-column>
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
    <el-table-column label="分摊成本" width="100" align="right">
      <template #default="{ row }">
        {{ fmtMoney(row.rowTotalCost) }}
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
        <el-tag :type="row.status === 'ACTIVE' ? 'success' : (row.status === 'COMPLETED' ? 'primary' : 'info')" size="small">
          {{ row.status === 'ACTIVE' ? '可用' : (row.status === 'COMPLETED' ? '成品' : '耗尽') }}
        </el-tag>
      </template>
    </el-table-column>
  </el-table>
  <div v-if="rows.length === 0 && !loading && !errorMessage" class="yield-card-empty-hint">
    暂无半成品库存；保存任一工序有效产出后会生成全工序出成率汇总。
  </div>
</template>

<style scoped>
.yield-card-warnings {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 8px;
}

.yield-card-error {
  margin-bottom: 8px;
}

.yield-card-warning {
  --el-alert-padding: 6px 10px;
}

.yield-card-empty-hint {
  color: #909399;
  font-size: 12px;
  padding: 10px 0;
  text-align: center;
}
</style>
