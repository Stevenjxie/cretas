<script setup lang="ts">
/**
 * ExpenseYoYBudgetChart - Chart 4: Expense Year-over-Year vs Budget Analysis
 * Features: Dual-axis grouped bar (last year / current year) + achievement rate line
 * KPI cards: 本年费用合计, 上年费用合计, 同比变化%, 预算达成率%
 */
import { ref, watch, onMounted, onUnmounted, computed } from 'vue';
import echarts from '@/utils/echarts';
import type { ECharts } from 'echarts';
import KPICard from './KPICard.vue';
import type { TrendDirection, StatusType } from './KPICard.vue';

export interface ExpenseMonthData {
  month: string;
  budget: number;
  actual: number;
  lastYear: number;
}

interface Props {
  title?: string;
  data: ExpenseMonthData[];
  height?: number;
  loading?: boolean;
  unit?: string;
}

const props = withDefaults(defineProps<Props>(), {
  title: '费用同比及预算达成分析',
  height: 420,
  loading: false,
  unit: '万元',
});

const chartRef = ref<HTMLDivElement | null>(null);
const chartInstance = ref<ECharts | null>(null);
let resizeObserver: ResizeObserver | null = null;
let rafId = 0;

// ---- Computed KPIs ----
const totalActual = computed(() =>
  props.data.reduce((s, d) => s + (d.actual || 0), 0)
);
const totalLastYear = computed(() =>
  props.data.reduce((s, d) => s + (d.lastYear || 0), 0)
);
const totalBudget = computed(() =>
  props.data.reduce((s, d) => s + (d.budget || 0), 0)
);
const yoyChange = computed(() => {
  if (totalLastYear.value === 0) return 0;
  return Math.round(((totalActual.value - totalLastYear.value) / totalLastYear.value) * 1000) / 10;
});
const budgetAchievementRate = computed(() => {
  if (totalBudget.value === 0) return 0;
  return Math.round((totalActual.value / totalBudget.value) * 1000) / 10;
});

function formatNumber(v: number): string {
  if (Math.abs(v) >= 10000) return (v / 10000).toFixed(1) + '亿';
  if (Math.abs(v) >= 1000) return (v / 1000).toFixed(1) + '千';
  return v.toFixed(1);
}

const kpiList = computed(() => {
  const yoyTrend: TrendDirection = yoyChange.value > 0 ? 'up' : yoyChange.value < 0 ? 'down' : 'flat';
  const budgetStatus: StatusType =
    budgetAchievementRate.value >= 100 ? 'success' :
    budgetAchievementRate.value >= 80 ? 'warning' : 'danger';

  return [
    {
      title: '本年费用合计',
      value: formatNumber(totalActual.value),
      unit: props.unit,
      status: 'info' as StatusType,
      trend: 'flat' as TrendDirection,
    },
    {
      title: '上年费用合计',
      value: formatNumber(totalLastYear.value),
      unit: props.unit,
      status: 'info' as StatusType,
      trend: 'flat' as TrendDirection,
    },
    {
      title: '同比变化',
      value: (yoyChange.value > 0 ? '+' : '') + yoyChange.value.toFixed(1),
      unit: '%',
      status: (yoyChange.value > 10 ? 'danger' : yoyChange.value > 0 ? 'warning' : 'success') as StatusType,
      trend: yoyTrend,
    },
    {
      title: '预算达成率',
      value: budgetAchievementRate.value.toFixed(1),
      unit: '%',
      status: budgetStatus,
      trend: 'flat' as TrendDirection,
    },
  ];
});

// ---- ECharts Option ----
function buildOption() {
  const months = props.data.map(d => d.month);
  const lastYearData = props.data.map(d => d.lastYear);
  const actualData = props.data.map(d => d.actual);
  const achievementData = props.data.map(d =>
    d.budget > 0 ? Math.round((d.actual / d.budget) * 1000) / 10 : 0
  );
  const yoyLabels = props.data.map(d => {
    if (d.lastYear === 0) return '';
    const change = ((d.actual - d.lastYear) / d.lastYear) * 100;
    const sign = change >= 0 ? '↑' : '↓';
    return `${sign}${Math.abs(change).toFixed(1)}%`;
  });

  // Quarter markArea backgrounds (3 months each for 12-month data)
  const markAreaData: [Record<string, unknown>, Record<string, unknown>][] = [];
  const quarterColors = [
    'rgba(45, 139, 87, 0.04)',
    'rgba(103, 194, 58, 0.04)',
    'rgba(230, 162, 60, 0.04)',
    'rgba(245, 108, 108, 0.04)',
  ];
  if (months.length === 12) {
    for (let q = 0; q < 4; q++) {
      markAreaData.push([
        { xAxis: months[q * 3], itemStyle: { color: quarterColors[q] } },
        { xAxis: months[q * 3 + 2] },
      ]);
    }
  }

  return {
    grid: { top: 20, right: 60, bottom: 60, left: 60, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: unknown[]) => {
        const ps = params as Array<{ seriesName: string; value: number | string; color: string }>;
        const month = (params as Array<{ axisValue: string }>)[0]?.axisValue || '';
        let html = `<div style="font-weight:600;margin-bottom:4px;">${month}</div>`;
        for (const p of ps) {
          const val = typeof p.value === 'number' ? p.value.toFixed(1) : p.value;
          const suffix = p.seriesName === '预算达成率' ? '%' : props.unit;
          html += `<div style="display:flex;align-items:center;gap:6px;">
            <span style="display:inline-block;width:10px;height:10px;border-radius:50%;background:${p.color}"></span>
            <span>${p.seriesName}:</span><strong>${val}${suffix}</strong>
          </div>`;
        }
        return html;
      },
    },
    legend: {
      bottom: 0,
      data: ['上年费用', '本年费用', '预算达成率'],
      itemWidth: 14,
      itemHeight: 10,
    },
    xAxis: {
      type: 'category',
      data: months,
      axisLabel: { fontSize: 11 },
    },
    yAxis: [
      {
        type: 'value',
        name: props.unit,
        nameTextStyle: { fontSize: 11, color: '#909399' },
        axisLabel: { fontSize: 11 },
        splitLine: { lineStyle: { type: 'dashed', color: '#f0f0f0' } },
      },
      {
        type: 'value',
        name: '达成率(%)',
        nameTextStyle: { fontSize: 11, color: '#909399' },
        axisLabel: { fontSize: 11, formatter: '{value}%' },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: '上年费用',
        type: 'bar',
        yAxisIndex: 0,
        data: lastYearData,
        barMaxWidth: 28,
        itemStyle: { color: '#36B37E', borderRadius: [2, 2, 0, 0] },
        markArea: markAreaData.length
          ? { silent: true, data: markAreaData }
          : undefined,
      },
      {
        name: '本年费用',
        type: 'bar',
        yAxisIndex: 0,
        data: actualData,
        barMaxWidth: 28,
        itemStyle: { color: '#FFAB00', borderRadius: [2, 2, 0, 0] },
        label: {
          show: true,
          position: 'top',
          formatter: (p: { dataIndex: number }) => yoyLabels[p.dataIndex] || '',
          fontSize: 10,
          color: '#606266',
        },
      },
      {
        name: '预算达成率',
        type: 'line',
        yAxisIndex: 1,
        data: achievementData,
        smooth: true,
        symbol: 'circle',
        symbolSize: 8,
        lineStyle: { color: '#2D8B57', width: 2 },
        itemStyle: { color: '#E8B931' },
        label: {
          show: true,
          position: 'top',
          formatter: (p: { value: number }) =>
            p.value >= 100 ? `${p.value}% ` : `${p.value}%`,
          fontSize: 11,
          color: (p: { value: number }) => p.value >= 100 ? '#36B37E' : '#2D8B57',
        },
      },
    ],
  };
}

function initChart() {
  if (!chartRef.value) return;
  // Defer if container has zero dimensions (not yet laid out)
  if (chartRef.value.clientWidth === 0 || chartRef.value.clientHeight === 0) {
    requestAnimationFrame(() => initChart());
    return;
  }
  if (chartInstance.value) {
    chartInstance.value.dispose();
  }
  chartInstance.value = echarts.init(chartRef.value) as unknown as ECharts;
  chartInstance.value.setOption(buildOption());
}

function scheduleResize() {
  if (rafId) cancelAnimationFrame(rafId);
  rafId = requestAnimationFrame(() => {
    chartInstance.value?.resize();
  });
}

watch(() => props.data, () => {
  if (chartInstance.value) {
    chartInstance.value.setOption(buildOption(), { notMerge: true });
  }
}, { deep: true });

watch(() => props.loading, (loading) => {
  if (!chartInstance.value) return;
  if (loading) {
    chartInstance.value.showLoading({ text: '加载中...', color: '#2D8B57' });
  } else {
    chartInstance.value.hideLoading();
    chartInstance.value.setOption(buildOption(), { notMerge: true });
  }
});

onMounted(() => {
  initChart();
  resizeObserver = new ResizeObserver(scheduleResize);
  if (chartRef.value) resizeObserver.observe(chartRef.value);
});

onUnmounted(() => {
  resizeObserver?.disconnect();
  if (rafId) cancelAnimationFrame(rafId);
  chartInstance.value?.dispose();
  chartInstance.value = null;
});

// Expose chart instance for parent getDataURL calls
defineExpose({ chartInstance });
</script>

<template>
  <div class="expense-yoy-budget-chart">
    <!-- KPI Cards Row -->
    <div class="kpi-row">
      <KPICard
        v-for="kpi in kpiList"
        :key="kpi.title"
        :title="kpi.title"
        :value="kpi.value"
        :unit="kpi.unit"
        :status="kpi.status"
        :trend="kpi.trend"
        class="kpi-card-item"
      />
    </div>

    <!-- Chart -->
    <div
      ref="chartRef"
      :style="{ height: `${height}px`, width: '100%' }"
    />
  </div>
</template>

<style scoped>
.expense-yoy-budget-chart {
  width: 100%;
}

.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

@media (max-width: 900px) {
  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }
}

.kpi-card-item {
  min-width: 0;
}
</style>
