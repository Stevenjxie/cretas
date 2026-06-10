<script setup lang="ts">
// Sprint 4 W2 S-PRICE-1 follow-up (2026-05-18) — 客户价格历史 Dialog.
//
// Read-only modal showing recent sales price entries for (customer × product).
// When customerId is omitted (e.g. invoked from 成品库存视图), shows the product's
// history across all customers.
//
// Backend: CustomerPriceHistoryController GET .../customer-price-history.
import { computed, nextTick, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { formatAmount } from '@/utils/tableFormatters';
import echarts from '@/utils/echarts';
import type { ECharts } from 'echarts/core';
import { listPriceHistory, type PriceHistoryEntry } from '@/api/priceHistory';
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight';
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue';
import { useChartInsight } from '@/composables/useChartInsight';

const props = withDefaults(
  defineProps<{
    /** v-model:visible */
    visible: boolean;
    /** 产品类型 ID (必填) */
    productId: string;
    /** 客户 ID — 可选, 缺省查跨客户历史 */
    customerId?: string;
    /** 头部显示用 — 产品名 */
    productLabel?: string;
    /** 头部显示用 — 客户名 (customerId 缺省时显示 "全部客户") */
    customerLabel?: string;
    /** 默认 20 条 (后端 cap 200) */
    limit?: number;
  }>(),
  {
    customerId: '',
    productLabel: '',
    customerLabel: '',
    limit: 20,
  }
);

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void;
}>();

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
// 用户能否看 price (backend @PriceSensitive 已 mask, 但 UI 也要决定列是否展示)
const canViewPrice = computed(() => permissionStore.canViewPrice);

const loading = ref(false);
const rows = ref<PriceHistoryEntry[]>([]);
const chartRef = ref<HTMLDivElement | null>(null);
let chartInstance: ECharts | null = null;

const headerTitle = computed(() => {
  const product = props.productLabel || props.productId || '产品';
  if (props.customerId) {
    const customer = props.customerLabel || '客户';
    return `价格历史 — ${product} × ${customer}`;
  }
  return `价格历史 — ${product} × 全部客户`;
});

const subTitle = computed(() => {
  if (!rows.value.length) {
    return loading.value ? '加载中…' : '暂无成交记录';
  }
  const prices = rows.value
    .map(r => r.unitPrice)
    .filter((p): p is number => p != null);
  if (!prices.length) {
    return `共 ${rows.value.length} 条记录 (价格字段需 价格查看权限)`;
  }
  const max = Math.max(...prices);
  const min = Math.min(...prices);
  const avg = prices.reduce((a, b) => a + b, 0) / prices.length;
  return `共 ${rows.value.length} 条 | 最新 ${formatAmount(prices[0])} | 区间 ${formatAmount(min)} ~ ${formatAmount(max)} | 平均 ${formatAmount(avg)}`;
});

async function loadHistory() {
  if (!factoryId.value || !props.productId) {
    rows.value = [];
    return;
  }
  loading.value = true;
  try {
    rows.value = await listPriceHistory(factoryId.value, {
      productId: props.productId,
      customerId: props.customerId || undefined,
      limit: props.limit,
    });
  } catch (e: unknown) {
    // axios interceptor 已 toast — 这里仅记空数据.
    rows.value = [];
    const msg = e instanceof Error ? e.message : '加载价格历史失败';
    ElMessage.error(msg);
  } finally {
    loading.value = false;
    await nextTick();
    renderChart();
  }
}

function renderChart() {
  if (!chartRef.value) return;
  // 只在有 price 数据时建 chart
  const withPrice = rows.value
    .filter(r => r.unitPrice != null && r.orderDate)
    .slice() // copy 避免后续 reverse mutate
    .reverse(); // backend DESC, 图表期望左→右时间递增
  // dispose existing
  if (chartInstance && !chartInstance.isDisposed?.()) {
    chartInstance.dispose();
  }
  chartInstance = null;
  if (!canViewPrice.value || withPrice.length < 2) {
    // 单点没意义, 直接不画
    return;
  }
  chartInstance = echarts.init(chartRef.value, 'cretas');
  chartInstance.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params: { name: string; value: number }[]) => {
        if (!Array.isArray(params) || params.length === 0) return '';
        const p = params[0];
        return `${p.name}<br/>成交价: ${formatAmount(Number(p.value))}`;
      },
    },
    grid: { left: 56, right: 16, top: 16, bottom: 32 },
    xAxis: {
      type: 'category',
      data: withPrice.map(r => r.orderDate),
      axisLabel: {
        fontSize: 11,
        rotate: withPrice.length > 8 ? 30 : 0,
      },
    },
    yAxis: {
      type: 'value',
      axisLabel: {
        formatter: (v: number) => `¥${v}`,
      },
    },
    series: [
      {
        type: 'line',
        data: withPrice.map(r => r.unitPrice),
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { width: 2 },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(45, 139, 87, 0.25)' },
              { offset: 1, color: 'rgba(45, 139, 87, 0.02)' },
            ],
          },
        },
      },
    ],
  });
}

function close() {
  emit('update:visible', false);
}

function handleOpenChange(open: boolean) {
  if (open) {
    void loadHistory();
  } else {
    if (chartInstance && !chartInstance.isDisposed?.()) {
      chartInstance.dispose();
    }
    chartInstance = null;
    rows.value = [];
  }
}

// Re-load when input props change while open.
watch(
  () => [props.productId, props.customerId, props.visible],
  ([, , vis]) => {
    if (vis) {
      void loadHistory();
    }
  }
);

// ==================== Chart Insight ====================
// Price history LINE chart (成交单价 over time, TREND family, domain='factory').
// Mirrors the withPrice filter in renderChart(): only rows with unitPrice + orderDate, asc order.
const priceHistorySource = (): { chart: ChartWithMeta } | null => {
  if (!canViewPrice.value) return null;
  const withPrice = rows.value
    .filter((r) => r.unitPrice != null && r.orderDate)
    .slice()
    .reverse(); // backend DESC → asc for chart
  if (withPrice.length < 2) return null;
  return {
    chart: {
      chartType: 'LINE',
      meta: { xDim: 'time', yMetric: 'cost', aggregation: 'avg', domain: 'factory' },
      config: {
        xAxis: { data: withPrice.map((r) => r.orderDate as string) },
        series: [{ type: 'line', data: withPrice.map((r) => r.unitPrice as number) }],
      },
    },
  };
};

const { insight: priceHistoryInsight, loading: priceHistoryInsightLoading } = useChartInsight(
  priceHistorySource,
  () => ({ canViewFinance: canViewPrice.value }),
  { factoryId: () => factoryId.value ?? '', autoTier2: true },
);
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="headerTitle"
    width="700px"
    top="8vh"
    destroy-on-close
    @update:model-value="(v: boolean) => emit('update:visible', v)"
    @open="handleOpenChange(true)"
    @closed="handleOpenChange(false)"
  >
    <div class="ph-sub">{{ subTitle }}</div>

    <!-- Chart (only if canViewPrice + ≥2 priced rows) -->
    <div v-show="canViewPrice && rows.length > 1" ref="chartRef" class="ph-chart" />
    <!-- U6: useChartInsight — Tier1 instant, Tier2 auto on null (飞轮接通) -->
    <ChartInsight v-if="canViewPrice && rows.length > 1" :insight="priceHistoryInsight" :loading="priceHistoryInsightLoading" depth="detailed" />

    <!-- Mask hint for users without price permission -->
    <div v-if="!canViewPrice" class="ph-mask-hint">
 当前角色无 <strong>价格查看</strong> 权限, 表格中 单价 / 总额 字段已被隐藏 (仅显示时间 / 订单号).
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      empty-text="暂无成交记录"
      stripe
      border
      max-height="40vh"
      style="width: 100%; margin-top: 8px"
    >
      <el-table-column prop="orderDate" label="订单日期" width="120" />
      <el-table-column label="来源订单" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ row.sourceOrderNumber || row.sourceSalesOrderId.slice(0, 8) + '…' }}</span>
        </template>
      </el-table-column>
      <el-table-column
        v-if="canViewPrice"
        prop="unitPrice"
        label="成交单价"
        width="130"
        align="right"
      >
        <template #default="{ row }">
          <span :style="{ fontWeight: 600, color: row.unitPrice == null ? '#909399' : '#2d8b57' }">
            {{ formatAmount(row.unitPrice) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        v-if="!props.customerId"
        prop="customerId"
        label="客户 ID"
        min-width="120"
        show-overflow-tooltip
      />
      <el-table-column label="录入时间" width="160">
        <template #default="{ row }">
          <span style="font-size: 12px; color: #606266">
            {{ row.createdAt ? row.createdAt.replace('T', ' ').slice(0, 16) : '-' }}
          </span>
        </template>
      </el-table-column>
    </el-table>

    <template #footer>
      <el-button @click="close">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.ph-sub {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 12px;
  padding: 6px 10px;
  background: var(--el-fill-color-lighter);
  border-radius: 4px;
}
.ph-chart {
  height: 220px;
  width: 100%;
  margin-bottom: 8px;
}
.ph-mask-hint {
  margin-bottom: 8px;
  padding: 8px 12px;
  background: var(--el-color-warning-light-9);
  border: 1px solid var(--el-color-warning-light-7);
  border-radius: 4px;
  color: var(--el-color-warning-dark-2);
  font-size: 12px;
}
</style>
