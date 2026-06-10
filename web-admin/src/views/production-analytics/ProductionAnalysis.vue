<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import { getProductionDashboard, type KPIItem, type ProductionDashboard } from '@/api/productionAnalytics';
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue';
import { useChartInsight } from '@/composables/useChartInsight';
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight';

// ==================== 状态 ====================

const loading = ref(false);
const dashboard = ref<ProductionDashboard | null>(null);

const dateRange = ref<[Date, Date]>([
  new Date(Date.now() - 6 * 86400000),
  new Date(),
]);

const shortcuts = [
  { text: '今日', value: () => { const d = new Date(); return [d, d] as [Date, Date]; } },
  { text: '近7日', value: () => [new Date(Date.now() - 6 * 86400000), new Date()] as [Date, Date] },
  { text: '近30日', value: () => [new Date(Date.now() - 29 * 86400000), new Date()] as [Date, Date] },
];

// ==================== 数据加载 ====================

async function loadData() {
  loading.value = true;
  try {
    const [start, end] = dateRange.value;
    const fmt = (d: Date | string) => {
      if (typeof d === 'string') return d.split('T')[0];
      return d.toISOString().split('T')[0];
    };
    const res = await getProductionDashboard({ startDate: fmt(start), endDate: fmt(end) });
    if (res.success && res.data) {
      dashboard.value = res.data;
      renderCharts();
    } else {
      ElMessage.warning(res.message || '加载失败');
    }
  } catch (e: unknown) {
    if (!(e as { actionHint?: unknown })?.actionHint) ElMessage.error('加载生产分析数据失败');
    console.error(e);
  } finally {
    loading.value = false;
  }
}

function onDateChange() {
  loadData();
}

// ==================== KPI ====================

const gradientStyles: Record<string, { bg: string; color: string }> = {
  purple: { bg: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)', color: '#fff' },
  pink: { bg: 'linear-gradient(135deg, #f093fb 0%, #f5576c 100%)', color: '#fff' },
  blue: { bg: 'linear-gradient(135deg, #4facfe 0%, #00f2fe 100%)', color: '#fff' },
  green: { bg: 'linear-gradient(135deg, #43e97b 0%, #38f9d7 100%)', color: '#fff' },
};

function kpiStyle(kpi: KPIItem) {
  const s = gradientStyles[kpi.gradient] || gradientStyles.purple;
  return { background: s.bg, color: s.color };
}

function changeIcon(kpi: KPIItem) {
  if (kpi.changeType === 'up') return '↑';
  if (kpi.changeType === 'down') return '↓';
  return '—';
}

function changeColor(kpi: KPIItem) {
  if (kpi.changeType === 'up') return kpi.key === 'defect_rate' ? '#ff4d4f' : '#52c41a';
  if (kpi.changeType === 'down') return kpi.key === 'defect_rate' ? '#52c41a' : '#ff4d4f';
  return '#d9d9d9';
}

function formatKPIValue(kpi: KPIItem) {
  if (kpi.value >= 10000) return (kpi.value / 10000).toFixed(1) + '万';
  if (Number.isInteger(kpi.value)) return kpi.value.toString();
  return kpi.value.toFixed(1);
}

// ==================== 图表 ====================

const trendChartRef = ref<HTMLDivElement>();
const yieldChartRef = ref<HTMLDivElement>();
const productChartRef = ref<HTMLDivElement>();
const processChartRef = ref<HTMLDivElement>();

let trendChart: echarts.ECharts | null = null;
let yieldChart: echarts.ECharts | null = null;
let productChart: echarts.ECharts | null = null;
let processChart: echarts.ECharts | null = null;
let renderTimer: ReturnType<typeof setTimeout> | null = null;

function renderCharts() {
  if (!dashboard.value) return;
  if (renderTimer) clearTimeout(renderTimer);
  renderTimer = setTimeout(() => {
    renderTrendChart();
    renderYieldChart();
    renderProductChart();
    renderProcessChart();
  }, 100);
}

function renderTrendChart() {
  if (!trendChartRef.value || !dashboard.value || !hasTrendData.value) return;
  if (!trendChart || trendChart.isDisposed?.()) trendChart = echarts.init(trendChartRef.value, 'cretas');
  const data = dashboard.value.dailyTrend;
  const dates = data.map(d => String(d.date).slice(5));
  trendChart.setOption({
    tooltip: { trigger: 'axis', confine: true },
    legend: { data: ['产出', '良品', '不良'], bottom: 0 },
    grid: { top: 30, right: 20, bottom: 40, left: 60 },
    xAxis: { type: 'category', data: dates },
    yAxis: { type: 'value', name: '数量' },
    series: [
      { name: '产出', type: 'line', data: data.map(d => d.output), smooth: true, areaStyle: { opacity: 0.15 }, itemStyle: { color: '#5470c6' } },
      { name: '良品', type: 'line', data: data.map(d => d.good), smooth: true, areaStyle: { opacity: 0.1 }, itemStyle: { color: '#91cc75' } },
      { name: '不良', type: 'line', data: data.map(d => d.defect), smooth: true, areaStyle: { opacity: 0.1 }, itemStyle: { color: '#ee6666' } },
    ],
  });
}

function renderYieldChart() {
  if (!yieldChartRef.value || !dashboard.value || !hasYieldData.value) return;
  if (!yieldChart || yieldChart.isDisposed?.()) yieldChart = echarts.init(yieldChartRef.value, 'cretas');
  const data = dashboard.value.dailyTrend;
  const dates = data.map(d => String(d.date).slice(5));
  const yieldRates = data.map(d => {
    const output = Number(d.output) || 0;
    const good = Number(d.good) || 0;
    return output > 0 ? Number(((good / output) * 100).toFixed(1)) : 0;
  });
  yieldChart.setOption({
    tooltip: { trigger: 'axis', confine: true, formatter: '{b}<br/>{a}: {c}%' },
    grid: { top: 30, right: 20, bottom: 30, left: 60 },
    xAxis: { type: 'category', data: dates },
    yAxis: { type: 'value', name: '良率(%)', min: Math.max(0, Math.min(...yieldRates) - 5), max: 100 },
    series: [{
      name: '良率', type: 'line', data: yieldRates, smooth: true,
      itemStyle: { color: '#f5576c' },
      areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: 'rgba(245,87,108,0.3)' }, { offset: 1, color: 'rgba(245,87,108,0.02)' }] } },
      markLine: { data: [{ yAxis: 95, name: '目标', lineStyle: { color: '#52c41a', type: 'dashed' }, label: { formatter: '目标 95%' } }], silent: true },
      markPoint: { data: [{ type: 'max', name: '最高' }, { type: 'min', name: '最低' }], symbolSize: 40 },
    }],
  });
}

function renderProductChart() {
  if (!productChartRef.value || !dashboard.value || !hasProductData.value) return;
  if (!productChart || productChart.isDisposed?.()) productChart = echarts.init(productChartRef.value, 'cretas');
  const data = dashboard.value.byProduct;
  productChart.setOption({
    tooltip: { trigger: 'axis', confine: true },
    grid: { top: 20, right: 20, bottom: 60, left: 60 },
    xAxis: { type: 'category', data: data.map(d => d.product_name), axisLabel: { rotate: 30, fontSize: 11 } },
    yAxis: { type: 'value', name: '产出量' },
    series: [{
      type: 'bar', data: data.map(d => d.output), barMaxWidth: 40,
      itemStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: '#667eea' }, { offset: 1, color: '#764ba2' }] } },
    }],
  });
}

function renderProcessChart() {
  if (!processChartRef.value || !dashboard.value || !hasProcessData.value) return;
  if (!processChart || processChart.isDisposed?.()) processChart = echarts.init(processChartRef.value, 'cretas');
  const data = dashboard.value.byProcess;
  processChart.setOption({
    tooltip: { trigger: 'item', confine: true, formatter: '{b}: {c} ({d}%)' },
    legend: { orient: 'vertical', right: 10, top: 'center' },
    series: [{
      type: 'pie', radius: ['35%', '65%'], center: ['40%', '50%'],
      data: data.map(d => ({ name: d.process_category, value: d.output })),
      emphasis: { itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0,0,0,0.2)' } },
      label: { formatter: '{b}\n{d}%' },
    }],
  });
}

// chart audit P1-3: per-chart hasData computeds. 8 echarts in
// production-analytics + efficiency-analysis previously rendered nude
// axes + legend without "暂无数据" placeholder when data was 0/null,
// looking like the system was broken. Now render <el-empty> branch when
// the underlying series has no meaningful data.

const hasTrendData = computed(() => {
  const data = dashboard.value?.dailyTrend ?? [];
  return data.some(d => Number(d.output) > 0 || Number(d.good) > 0 || Number(d.defect) > 0);
});

const hasYieldData = computed(() => {
  const data = dashboard.value?.dailyTrend ?? [];
  return data.some(d => Number(d.output) > 0);
});

const hasProductData = computed(() => {
  const data = dashboard.value?.byProduct ?? [];
  return data.some(d => Number(d.output) > 0);
});

const hasProcessData = computed(() => {
  const data = dashboard.value?.byProcess ?? [];
  return data.some(d => Number(d.output) > 0);
});

// ==================== Chart Insight ====================
// LINE 1: 日产出趋势 (trendChart) — multi-series 产出/良品/不良, xDim:'time', TREND
//         Multi-series: use 总产量 (series[0]) as the primary metric for insight.
// LINE 2: 良率趋势    (yieldChart) — xDim:'time', yMetric:'pct', TREND
// BAR:    产品产出    (productChart) — xDim:'category', yMetric:'quantity', RANKING
// PIE:    工序分布    (processChart) — wired as PIE PROPORTION family

const prodInsightPerms = () => ({ canViewFinance: false }); // no finance metrics in production view

const { insight: prodTrendInsight, loading: prodTrendInsightLoading } = useChartInsight(
  (): { chart: ChartWithMeta } | null => {
    const data = dashboard.value?.dailyTrend;
    if (!data || data.length < 4) return null;
    return {
      chart: {
        chartType: 'LINE',
        title: '日产出趋势',
        meta: { xDim: 'time', yMetric: 'quantity', aggregation: 'sum', domain: 'factory' },
        config: {
          xAxis: { data: data.map(d => String(d.date).slice(5)) },
          // Feed primary series (总产量) only — TREND reads first series values
          series: [{ type: 'line', data: data.map(d => d.output) }],
        },
      },
    };
  },
  prodInsightPerms,
  { factoryId: () => '', autoTier2: true },
);

const { insight: prodYieldInsight, loading: prodYieldInsightLoading } = useChartInsight(
  (): { chart: ChartWithMeta } | null => {
    const data = dashboard.value?.dailyTrend;
    if (!data || data.length < 4) return null;
    const yieldRates = data.map(d => {
      const output = Number(d.output) || 0;
      const good = Number(d.good) || 0;
      return output > 0 ? Number(((good / output) * 100).toFixed(1)) : 0;
    });
    return {
      chart: {
        chartType: 'LINE',
        title: '良率趋势',
        meta: { xDim: 'time', yMetric: 'pct', aggregation: 'avg', domain: 'factory' },
        config: {
          xAxis: { data: data.map(d => String(d.date).slice(5)) },
          series: [{ type: 'line', data: yieldRates }],
        },
      },
    };
  },
  prodInsightPerms,
  { factoryId: () => '', autoTier2: true },
);

const { insight: prodProductInsight, loading: prodProductInsightLoading } = useChartInsight(
  (): { chart: ChartWithMeta } | null => {
    const data = dashboard.value?.byProduct;
    if (!data || data.length < 2) return null;
    return {
      chart: {
        chartType: 'BAR',
        title: '产品产出对比',
        meta: { xDim: 'category', yMetric: 'quantity', aggregation: 'sum', domain: 'factory' },
        config: {
          xAxis: { data: data.map(d => d.product_name) },
          series: [{ type: 'bar', data: data.map(d => d.output) }],
        },
      },
    };
  },
  prodInsightPerms,
  { factoryId: () => '', autoTier2: true },
);

const { insight: prodProcessInsight, loading: prodProcessInsightLoading } = useChartInsight(
  (): { chart: ChartWithMeta } | null => {
    const data = dashboard.value?.byProcess;
    if (!data || data.length < 2) return null;
    return {
      chart: {
        chartType: 'pie',
        title: '工序产出分布',
        meta: { xDim: 'category', yMetric: 'quantity', aggregation: 'sum', domain: 'factory' },
        config: {
          series: [{
            type: 'pie',
            data: data.map(d => ({ name: d.process_category, value: d.output })),
          }],
        },
      },
    };
  },
  prodInsightPerms,
  { factoryId: () => '', autoTier2: true },
);

// ==================== 明细数据 ====================

const detailData = computed(() => {
  if (!dashboard.value) return [];
  return dashboard.value.byProduct.map((d, i) => ({
    index: i + 1,
    productName: d.product_name,
    output: d.output,
    good: d.good,
    defect: d.defect,
    yieldRate: Number(d.output) > 0 ? ((Number(d.good) / Number(d.output)) * 100).toFixed(1) + '%' : '—',
    reportCount: d.report_count,
  }));
});

// ==================== 生命周期 ====================

let resizeRaf = 0;
function handleResize() {
  if (resizeRaf) return;
  resizeRaf = requestAnimationFrame(() => {
    trendChart?.resize();
    yieldChart?.resize();
    productChart?.resize();
    processChart?.resize();
    resizeRaf = 0;
  });
}

let resizeObserver: ResizeObserver | null = null;

onMounted(() => {
  loadData();
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(handleResize);
    document.querySelectorAll('.chart-body').forEach(el => resizeObserver!.observe(el));
  }
  window.addEventListener('resize', handleResize);
});

onBeforeUnmount(() => {
  if (resizeRaf) cancelAnimationFrame(resizeRaf);
  resizeObserver?.disconnect();
  if (renderTimer) clearTimeout(renderTimer);
  window.removeEventListener('resize', handleResize);
  trendChart?.dispose();
  yieldChart?.dispose();
  productChart?.dispose();
  processChart?.dispose();
});
</script>

<template>
  <div class="production-analysis" v-loading="loading">
    <!-- 顶部控件 -->
    <div class="toolbar">
      <h2 class="page-title">生产数据分析</h2>
      <div class="toolbar-actions">
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          :shortcuts="shortcuts"
          value-format="YYYY-MM-DD"
          @change="onDateChange"
          style="width: 280px"
        />
        <el-button :icon="Refresh" @click="loadData" circle />
      </div>
    </div>

    <!-- KPI 卡片 -->
    <div class="kpi-grid" v-if="dashboard?.kpis?.length">
      <div
        v-for="kpi in dashboard.kpis"
        :key="kpi.key"
        class="kpi-card"
        :style="kpiStyle(kpi)"
      >
        <div class="kpi-label">{{ kpi.label }}</div>
        <div class="kpi-value">{{ formatKPIValue(kpi) }}<span class="kpi-unit">{{ kpi.unit }}</span></div>
        <div class="kpi-change" :style="{ color: changeColor(kpi) }">
          {{ changeIcon(kpi) }} {{ Math.abs(kpi.change).toFixed(1) }}% 环比
        </div>
      </div>
    </div>

    <!-- 图表 2x2 -->
    <div class="charts-grid">
      <div class="chart-card">
        <div class="chart-title">日产出趋势</div>
        <div v-show="hasTrendData" ref="trendChartRef" class="chart-body" />
        <el-empty v-if="!hasTrendData" description="该期暂无生产报工" :image-size="80" class="chart-empty" />
        <!-- Insight: LINE TREND (factory, xDim:time, yMetric:quantity/总产量 primary series) -->
        <ChartInsight :insight="prodTrendInsight" :loading="prodTrendInsightLoading" depth="detailed" />
      </div>
      <div class="chart-card">
        <div class="chart-title">良率趋势</div>
        <div v-show="hasYieldData" ref="yieldChartRef" class="chart-body" />
        <el-empty v-if="!hasYieldData" description="该期暂无良率数据" :image-size="80" class="chart-empty" />
        <!-- Insight: LINE TREND (factory, xDim:time, yMetric:pct/良率) -->
        <ChartInsight :insight="prodYieldInsight" :loading="prodYieldInsightLoading" depth="detailed" />
      </div>
      <div class="chart-card">
        <div class="chart-title">产品产出对比</div>
        <div v-show="hasProductData" ref="productChartRef" class="chart-body" />
        <el-empty v-if="!hasProductData" description="该期暂无产品产出" :image-size="80" class="chart-empty" />
        <!-- Insight: BAR RANKING (factory, xDim:category, yMetric:quantity) -->
        <ChartInsight :insight="prodProductInsight" :loading="prodProductInsightLoading" depth="detailed" />
      </div>
      <div class="chart-card">
        <div class="chart-title">工序产出分布</div>
        <div v-show="hasProcessData" ref="processChartRef" class="chart-body" />
        <el-empty v-if="!hasProcessData" description="该期暂无工序数据" :image-size="80" class="chart-empty" />
        <!-- Insight: PIE PROPORTION (factory, xDim:category, yMetric:quantity) -->
        <ChartInsight :insight="prodProcessInsight" :loading="prodProcessInsightLoading" depth="detailed" />
      </div>
    </div>

    <!-- 明细表格 -->
    <div class="detail-section" v-if="detailData.length">
      <h3>产品明细数据</h3>
      <el-table empty-text="暂无数据" :data="detailData" stripe border size="small" style="width: 100%">
        <el-table-column prop="index" label="#" width="50" align="center" />
        <el-table-column prop="productName" label="产品名称" min-width="140" />
        <el-table-column prop="output" label="总产出" width="100" align="right" />
        <el-table-column prop="good" label="良品数" width="100" align="right" />
        <el-table-column prop="defect" label="不良数" width="100" align="right" />
        <el-table-column prop="yieldRate" label="良率" width="80" align="center" />
        <el-table-column prop="reportCount" label="报工数" width="80" align="center" />
      </el-table>
    </div>

    <!-- 空状态 -->
    <el-empty v-if="!loading && !dashboard?.kpis?.length" description="暂无生产数据，请先提交生产报工" />
  </div>
</template>

<style scoped>
.production-analysis {
  padding: 20px;
  min-height: calc(100vh - 64px);
  background: #f5f7fa;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: #1d2129;
  margin: 0;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.kpi-card {
  border-radius: 12px;
  padding: 20px;
  position: relative;
  overflow: hidden;
}

.kpi-label {
  font-size: 13px;
  opacity: 0.85;
  margin-bottom: 8px;
}

.kpi-value {
  font-size: 28px;
  font-weight: 700;
  line-height: 1.2;
}

.kpi-unit {
  font-size: 14px;
  font-weight: 400;
  margin-left: 4px;
  opacity: 0.8;
}

.kpi-change {
  margin-top: 8px;
  font-size: 12px;
  background: rgba(255, 255, 255, 0.2);
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
}

.charts-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.chart-card {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.chart-title {
  font-size: 14px;
  font-weight: 600;
  color: #1d2129;
  margin-bottom: 12px;
}

.chart-body {
  height: 300px;
}

.chart-empty {
  height: 300px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.detail-section {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.detail-section h3 {
  font-size: 16px;
  font-weight: 600;
  color: #1d2129;
  margin: 0 0 16px 0;
}

@media (max-width: 1200px) {
  .kpi-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .charts-grid {
    grid-template-columns: 1fr;
  }
}
</style>
