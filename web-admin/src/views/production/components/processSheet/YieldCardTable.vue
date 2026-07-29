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
import { computed, h, ref, watch } from 'vue';
import { getInventoryYieldCard, type ProcessSheetInventoryItem } from '@/api/processSheet';
import { normalizeMassQuantityForReporting } from '@/utils/processSheetUnits';

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

const processFilters = computed(() => Array.from(new Set(rows.value
  .map((row) => row.processName || '未命名工序')))
  .sort((a, b) => a.localeCompare(b, 'zh-CN'))
  .map((value) => ({ text: value, value })));

const statusFilters = [
  { text: '可用', value: 'ACTIVE' },
  { text: '成品', value: 'COMPLETED' },
  { text: '耗尽', value: 'DEPLETED' },
];

function filterProcess(value: string, row: ProcessSheetInventoryItem): boolean {
  return (row.processName || '未命名工序') === value;
}

function filterStatus(value: string, row: ProcessSheetInventoryItem): boolean {
  return row.status === value;
}

/**
 * 表头仍是一个真按钮 —— 触屏和键盘要有明确的可点区域与名字。
 *
 * 但它不再自带任何视觉外壳: 客户反馈「每个表头套了独立边框盒子」, 那个盒子感来自
 * 加深的表头底色 + 逐格右边框 + 每格一个带 ↕ 的按钮三层叠加。现在按钮完全继承单元格
 * 的字体与颜色, 排序方向交回 el-table 自己的箭头, 看上去就是生产计划列表那种通栏表头。
 */
function sortableHeader({ column }: { column: { label?: string } }) {
  const label = column.label || '本列';
  return h(
    'button',
    {
      type: 'button',
      class: 'yield-sort-trigger',
      'aria-label': `按${label}排序，连续操作可切换升序和降序`,
      title: '点击切换升序和降序',
    },
    label,
  );
}

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

function reportingQuantity(row: ProcessSheetInventoryItem, field: 'produced' | 'used' | 'remaining'): string {
  const normalized = normalizeMassQuantityForReporting(Number(row[field] ?? 0), row.unit);
  return `${fmtQty(normalized.quantity)}${normalized.unit ? ` ${normalized.unit}` : ''}`;
}

function reportingUnitPrice(row: ProcessSheetInventoryItem): number | null | undefined {
  if (row.unitPrice == null) return row.unitPrice;
  const unit = row.unit?.trim().toLowerCase();
  return unit === 'g' || unit === '克' ? row.unitPrice * 1000 : row.unitPrice;
}

function fmtDate(v: string | null | undefined): string {
  if (!v) return '—';
  // 保留 datetime 截断以兼容旧/异常响应；待接口契约测试及历史数据都保证 LocalDate 后删除。
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
  <div v-if="rows.length > 0" class="yield-card-scroll-hint">
    点击表头可升/降序，漏斗可筛选；表格可左右滑动查看完整字段 →
  </div>
  <el-table
    :data="rows"
    v-loading="loading"
    size="small"
    border
    stripe
    table-layout="fixed"
    class="yield-card-table"
    empty-text="暂无半成品库存记录"
    style="width: 100%"
    :row-class-name="() => ''"
  >
    <el-table-column prop="processOrder" label="序" width="58" align="center" sortable :render-header="sortableHeader" />
    <el-table-column prop="processDate" label="流程日期" width="118" align="center" sortable :render-header="sortableHeader">
      <template #default="{ row }">
        {{ fmtDate(row.processDate) }}
      </template>
    </el-table-column>
    <el-table-column
      prop="processName"
      label="工序"
      min-width="180"
      show-overflow-tooltip
      sortable
      :render-header="sortableHeader"
      :filters="processFilters"
      :filter-method="filterProcess"
    >
      <template #default="{ row }">
        {{ row.processName || '—' }}
      </template>
    </el-table-column>
    <el-table-column prop="batchNumber" label="批次号" min-width="230" show-overflow-tooltip sortable :render-header="sortableHeader" />
    <el-table-column prop="sourceBatchNumber" label="来源批次" min-width="210" show-overflow-tooltip sortable :render-header="sortableHeader">
      <template #default="{ row }">
        {{ row.sourceBatchNumber || '—' }}
      </template>
    </el-table-column>
    <el-table-column prop="feedQuantity" label="领用(kg)" width="110" align="right" sortable :render-header="sortableHeader">
      <template #default="{ row }">
        {{ fmtQty(row.feedQuantity) }}
      </template>
    </el-table-column>
    <el-table-column prop="sourceConsumedRatio" label="领用占比" width="110" align="right" sortable :render-header="sortableHeader">
      <template #default="{ row }">
        {{ fmtRate(row.sourceConsumedRatio) }}
      </template>
    </el-table-column>
    <el-table-column prop="inheritedRawEquivalentQuantity" label="继承原料(kg)" width="132" align="right" sortable :render-header="sortableHeader">
      <template #default="{ row }">
        {{ fmtQty(row.inheritedRawEquivalentQuantity, 2) }}
      </template>
    </el-table-column>
    <el-table-column prop="produced" label="产出" width="100" align="right" sortable :render-header="sortableHeader">
      <template #default="{ row }">
        {{ reportingQuantity(row, 'produced') }}
      </template>
    </el-table-column>
    <el-table-column prop="used" label="已用" width="100" align="right" sortable :render-header="sortableHeader">
      <template #default="{ row }">
        {{ reportingQuantity(row, 'used') }}
      </template>
    </el-table-column>
    <el-table-column prop="remaining" label="剩余" width="100" align="right" sortable :render-header="sortableHeader">
      <template #default="{ row }">
        <span :style="{ color: (row.remaining ?? 0) <= 0 ? '#f56c6c' : '#67c23a' }">
          {{ reportingQuantity(row, 'remaining') }}
        </span>
      </template>
    </el-table-column>
    <el-table-column prop="rowTotalCost" label="分摊成本" width="116" align="right" sortable :render-header="sortableHeader">
      <template #default="{ row }">
        {{ fmtMoney(row.rowTotalCost) }}
      </template>
    </el-table-column>
    <el-table-column prop="unitPrice" label="单价(¥)" width="105" align="right" sortable :render-header="sortableHeader">
      <template #default="{ row }">
        {{ fmtPrice(reportingUnitPrice(row)) }}
      </template>
    </el-table-column>
    <el-table-column prop="stepYieldRate" label="对上工序出成" width="132" align="right" sortable :render-header="sortableHeader">
      <template #default="{ row }">
        <span :style="{ color: rateColor(row.stepYieldRate), fontWeight: 'bold' }">
          {{ fmtRate(row.stepYieldRate) }}
        </span>
      </template>
    </el-table-column>
    <el-table-column prop="cumulativeYieldRate" label="对原料累计" width="122" align="right" sortable :render-header="sortableHeader">
      <template #default="{ row }">
        <span :style="{ color: rateColor(row.cumulativeYieldRate) }">
          {{ fmtRate(row.cumulativeYieldRate) }}
        </span>
      </template>
    </el-table-column>
    <el-table-column
      prop="status"
      label="状态"
      width="100"
      align="center"
      :filters="statusFilters"
      :filter-method="filterStatus"
    >
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

.yield-card-scroll-hint {
  margin: 0 0 6px;
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
  text-align: right;
}

/*
  通栏表头 —— 与生产计划列表 (.business-list-table) 同一套观感。
  不再覆盖表头底色/字重, 也不再逐格加深右边框: 那三层叠加正是客户说的「独立边框盒子」。
*/
.yield-card-table {
  :deep(.el-table__header th.el-table__cell) {
    height: 42px;
    padding: 0;
  }

  :deep(.el-table__body td.el-table__cell) {
    height: 44px;
    padding: 0;
  }

  :deep(.el-table__cell .cell) {
    padding: 0 10px;
    line-height: 18px;
    font-variant-numeric: tabular-nums;
  }

  :deep(.el-table__column-filter-trigger) {
    margin-left: 5px;
    color: #697586;
  }

  /* 按钮只提供点击热区与无障碍名字, 视觉上完全等同于普通表头文字 */
  :deep(.yield-sort-trigger) {
    display: inline-flex;
    align-items: center;
    justify-content: inherit;
    min-height: 32px;
    padding: 0;
    border: 0;
    background: transparent;
    color: inherit;
    font: inherit;
    font-weight: inherit;
    cursor: pointer;
    touch-action: manipulation;
  }

  :deep(.yield-sort-trigger:hover) {
    color: var(--el-color-primary, #409eff);
  }

  :deep(.yield-sort-trigger:focus-visible) {
    outline: 2px solid var(--el-color-primary, #409eff);
    outline-offset: 2px;
  }
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
