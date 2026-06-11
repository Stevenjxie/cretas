<script setup lang="ts">
/**
 * ProductionAnalysis.vue — 生产数据分析
 *
 * A4 迁移 (2026-06-11): 趋势图 / 产品图 数据源改读 gold
 * (getFactoryYieldTrend + getFactoryProductCostCompare via useProductionGold).
 * KPI 行保留 Java 路径 (MoM 对比数据 gold 无法提供).
 * gold_read_cache 由 Python 层自动处理，前端无需额外操作。
 */
import { ref, onMounted, onBeforeUnmount, computed } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import { useAuthStore } from '@/store/modules/auth';
import { getProductionDashboard, type KPIItem, type ProductionDashboard } from '@/api/productionAnalytics';
import { useProductionGold } from '@/composables/useProductionGold';
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue';
import { useChartInsight } from '@/composables/useChartInsight';
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight';

// ==================== 状态 ====================

// KPI row comes from Java (MoM comparison not available in gold)
const kpiLoading = ref(false);
const dashboard = ref<ProductionDashboard | null>(null);

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const dateRange = ref<[Date, Date]>([
  new Date(Date.now() - 6 * 86400000),
  new Date(),
]);

const shortcuts = [
  { text: '今日', value: () => { const d = new Date(); return [d, d] as [Date, Date]; } },
  { text: '近7日', value: () => [new Date(Date.now() - 6 * 86400000), new Date()] as [Date, Date] },
  { text: '近30日', value: () => [new Date(Date.now() - 29 * 86400000), new Date()] as [Date, Date] },
];

function fmtDate(d: Date | string): string {
  if (typeof d === 'string') return d.split('T')[0];
  return d.toISOString().split('T')[0];
}

function getQueryDates() {
  const [start, end] = dateRange.value;
  return { startDate: fmtDate(start), endDate: fmtDate(end) };
}

// ==================== Gold 数据源 (A4 迁移) ====================

const gold = useProductionGold(factoryId);

// Expose gold loading flag for unified spinner
const loading = computed(() => kpiLoading.value || gold.loading.value);

// ==================== 数据加载 ====================

async function loadKpi() {
  kpiLoading.value = true;
  try {
    const { startDate, endDate } = getQueryDates();
    const res = await getProductionDashboard({ startDate, endDate });
    if (res.success && res.data) {
      dashboard.value = res.data;
    } else {
      ElMessage.warning(res.message || '加载 KPI 失败');
    }
  } catch (e: unknown) {
    if (!(e as { actionHint?: unknown })?.actionHint) ElMessage.error('加载生产 KPI 失败');
    console.error(e);
  } finally {
    kpiLoading.value = false;
  }
}

async function loadData() {
  const { startDate, endDate } = getQueryDates();
  // Parallel: KPI (Java) + gold (Python)
  await Promise.all([
    loadKpi(),
    gold.reload({ startDate, endDate }),
  ]);
  renderCharts();
}

function onDateChange() {
  loadData();
}

// ==================== Gold 错误提示 ====================

// Show gold error once if it persists after load
const _goldErrorShown = ref(false);
function maybeShowGoldError() {
  if (gold.loadError.value && !_goldErrorShown.value) {
    _goldErrorShown.value = true;
    ElMessage.error({ message: gold.loadError.value, duration: 0, showClose: true });
  }
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
  if (renderTimer) clearTimeout(renderTimer);
  renderTimer = setTimeout(() => {
    renderTrendChart();
    renderYieldChart();
    renderProductChart();
    renderProcessChart();
    maybeShowGoldError();
  }, 100);
}

// ── 日产出趋势 — 来自 gold yield-trend (totalActualQty / totalGoodQty per day) ──

function renderTrendChart() {
  if (!trendChartRef.value || !hasTrendData.value) return;
  if (!trendChart || trendChart.isDisposed?.()) trendChart = echarts.init(trendChartRef.value, 'cretas');
  const trend = gold.yieldData.value!.trend;
  const dates = trend.map(p => p.statDate.slice(5));
  trendChart.setOption({
    tooltip: { trigger: 'axis', confine: true },
    legend: { data: ['实际产出(kg)', '良品(kg)'], bottom: 0 },
    grid: { top: 30, right: 20, bottom: 40, left: 60 },
    xAxis: { type: 'category', data: dates },
    yAxis: { type: 'value', name: 'kg' },
    series: [
      { name: '实际产出(kg)', type: 'line', data: trend.map(p => p.totalActualQty ?? 0), smooth: true, areaStyle: { opacity: 0.15 }, itemStyle: { color: '#5470c6' } },
      { name: '良品(kg)', type: 'line', data: trend.map(p => p.totalGoodQty ?? 0), smooth: true, areaStyle: { opacity: 0.1 }, itemStyle: { color: '#91cc75' } },
    ],
  });
}

// ── 出成率趋势 — 来自 gold yield-trend (avgYieldRate per day) ──

function renderYieldChart() {
  if (!yieldChartRef.value || !hasYieldData.value) return;
  if (!yieldChart || yieldChart.isDisposed?.()) yieldChart = echarts.init(yieldChartRef.value, 'cretas');
  const trend = gold.yieldData.value!.trend;
  const dates = trend.map(p => p.statDate.slice(5));
  const rates = trend.map(p => p.avgYieldRate != null ? Number(p.avgYieldRate.toFixed(1)) : 0);
  const minRate = rates.filter(v => v > 0);
  yieldChart.setOption({
    tooltip: { trigger: 'axis', confine: true, formatter: '{b}<br/>出成率: {c}%' },
    grid: { top: 30, right: 20, bottom: 30, left: 60 },
    xAxis: { type: 'category', data: dates },
    yAxis: { type: 'value', name: '出成率(%)', min: minRate.length ? Math.max(0, Math.min(...minRate) - 5) : 0, max: 100 },
    series: [{
      name: '出成率', type: 'line', data: rates, smooth: true, connectNulls: false,
      itemStyle: { color: '#f5576c' },
      areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: 'rgba(245,87,108,0.3)' }, { offset: 1, color: 'rgba(245,87,108,0.02)' }] } },
      markLine: { data: [{ yAxis: 80, name: '基准', lineStyle: { color: '#52c41a', type: 'dashed' }, label: { formatter: '基准 80%' } }], silent: true },
      markPoint: { data: [{ type: 'max', name: '最高' }, { type: 'min', name: '最低' }], symbolSize: 40 },
    }],
  });
}

// ── 产品产出 — 来自 gold product-cost-compare (totalActualQty per product) ──

function renderProductChart() {
  if (!productChartRef.value || !hasProductData.value) return;
  if (!productChart || productChart.isDisposed?.()) productChart = echarts.init(productChartRef.value, 'cretas');
  const prods = gold.productData.value!.products.slice(0, 10);
  productChart.setOption({
    tooltip: {
      trigger: 'axis',
      confine: true,
      formatter: (params: Array<{ name: string; dataIndex: number }>) => {
        const p = prods[params[0]?.dataIndex ?? 0];
        if (!p) return '';
        return `${p.productTypeId}<br/>实际产出: ${(p.totalActualQty ?? 0).toFixed(1)} kg<br/>批次: ${p.batchCount}<br/>出成率: ${p.avgYieldRate?.toFixed(1) ?? '—'}%`;
      },
    },
    grid: { top: 20, right: 20, bottom: 70, left: 60 },
    xAxis: { type: 'category', data: prods.map(p => p.productTypeId), axisLabel: { rotate: 30, fontSize: 11, overflow: 'truncate', width: 80 } },
    yAxis: { type: 'value', name: '实际产出(kg)' },
    series: [{
      type: 'bar', data: prods.map(p => p.totalActualQty ?? 0), barMaxWidth: 40,
      itemStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: '#667eea' }, { offset: 1, color: '#764ba2' }] } },
    }],
  });
}

// ── 工序分布 — 保留 Java byProcess (gold 无工序维度) ──

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

// ==================== hasData computeds (gold-sourced where available) ====================

// 日产出趋势: gold yield-trend totalActualQty
const hasTrendData = computed(() => {
  const trend = gold.yieldData.value?.trend ?? [];
  return trend.some(p => (p.totalActualQty ?? 0) > 0 || (p.totalGoodQty ?? 0) > 0);
});

// 出成率趋势: gold yield-trend avgYieldRate
const hasYieldData = computed(() => gold.hasYieldTrend.value);

// 产品产出对比: gold product-cost-compare totalActualQty
const hasProductData = computed(() => gold.hasProducts.value);

// 工序分布: Java byProcess (gold 无工序维度)
const hasProcessData = computed(() => {
  const data = dashboard.value?.byProcess ?? [];
  return data.some(d => Number(d.output) > 0);
});

// ==================== Chart Insight ====================
// LINE 1: 日产出趋势 (trendChart) — gold totalActualQty, xDim:'time', TREND
// LINE 2: 出成率趋势 (yieldChart) — gold avgYieldRate, xDim:'time', yMetric:'pct', TREND
// BAR:    产品产出   (productChart) — gold totalActualQty, xDim:'product', RANKING
// PIE:    工序分布   (processChart) — Java byProcess, PIE PROPORTION

const prodInsightPerms = () => ({ canViewFinance: false }); // 生产视图无财务指标

const { insight: prodTrendInsight, loading: prodTrendInsightLoading } = useChartInsight(
  (): { chart: ChartWithMeta } | null => {
    const trend = gold.yieldData.value?.trend;
    if (!trend || trend.length < 4) return null;
    return {
      chart: {
        chartType: 'LINE',
        title: '日产出趋势',
        meta: { xDim: 'time', yMetric: 'quantity', aggregation: 'sum', domain: 'factory' },
        config: {
          xAxis: { data: trend.map(p => p.statDate.slice(5)) },
          series: [{ type: 'line', data: trend.map(p => p.totalActualQty ?? 0) }],
        },
      },
    };
  },
  prodInsightPerms,
  { factoryId: () => factoryId.value, autoTier2: true },
);

const { insight: prodYieldInsight, loading: prodYieldInsightLoading } = useChartInsight(
  (): { chart: ChartWithMeta } | null => {
    const trend = gold.yieldData.value?.trend;
    if (!trend || trend.length < 4) return null;
    return {
      chart: {
        chartType: 'LINE',
        title: '出成率趋势',
        meta: { xDim: 'time', yMetric: 'pct', aggregation: 'avg', domain: 'factory' },
        config: {
          xAxis: { data: trend.map(p => p.statDate.slice(5)) },
          series: [{ type: 'line', data: trend.map(p => p.avgYieldRate ?? 0) }],
        },
      },
    };
  },
  prodInsightPerms,
  { factoryId: () => factoryId.value, autoTier2: true },
);

const { insight: prodProductInsight, loading: prodProductInsightLoading } = useChartInsight(
  (): { chart: ChartWithMeta } | null => {
    const prods = gold.productData.value?.products;
    if (!prods || prods.length < 2) return null;
    const top = prods.slice(0, 10);
    return {
      chart: {
        chartType: 'BAR',
        title: '产品实际产出对比',
        meta: { xDim: 'product', yMetric: 'quantity', aggregation: 'sum', domain: 'factory' },
        config: {
          xAxis: { data: top.map(p => p.productTypeId) },
          series: [{ type: 'bar', data: top.map(p => p.totalActualQty ?? 0) }],
        },
      },
    };
  },
  prodInsightPerms,
  { factoryId: () => factoryId.value, autoTier2: true },
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
  { factoryId: () => factoryId.value, autoTier2: true },
);

// ==================== 明细数据 (gold product-cost-compare) ====================

const detailData = computed(() => {
  const prods = gold.productData.value?.products ?? [];
  return prods.map((p, i) => ({
    index: i + 1,
    productName: p.productTypeId,
    output: p.totalActualQty != null ? p.totalActualQty.toFixed(1) : '—',
    good: p.totalGoodQty != null ? p.totalGoodQty.toFixed(1) : '—',
    defect: '—', // gold 聚合层无不良数据 (依赖报工原始记录)
    yieldRate: p.avgYieldRate != null ? p.avgYieldRate.toFixed(1) + '%' : '—',
    reportCount: p.batchCount,
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
    // Re-observe after next tick so chart-body elements are rendered
    setTimeout(() => {
      document.querySelectorAll('.chart-body').forEach(el => resizeObserver!.observe(el));
    }, 200);
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
      <h2 class="page-title">生产数据分析 <span v-if="gold.hasAnyData.value" class="data-badge">· gold 数据源</span></h2>
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
        <div class="chart-title">日产出趋势 <span class="chart-source">gold</span></div>
        <div v-show="hasTrendData" ref="trendChartRef" class="chart-body" />
        <el-empty v-if="!hasTrendData && !gold.loading.value" description="该期暂无生产报工数据（gold ETL 需先跑报工）" :image-size="80" class="chart-empty" />
        <!-- Insight: LINE TREND (factory, xDim:time, yMetric:quantity, gold totalActualQty) -->
        <ChartInsight :insight="prodTrendInsight" :loading="prodTrendInsightLoading" depth="detailed" />
      </div>
      <div class="chart-card">
        <div class="chart-title">出成率趋势 <span class="chart-source">gold</span></div>
        <div v-show="hasYieldData" ref="yieldChartRef" class="chart-body" />
        <el-empty v-if="!hasYieldData && !gold.loading.value" description="该期暂无出成率数据（需 ≥ 2 天报工且记录实际产出）" :image-size="80" class="chart-empty" />
        <!-- Insight: LINE TREND (factory, xDim:time, yMetric:pct, gold avgYieldRate) -->
        <ChartInsight :insight="prodYieldInsight" :loading="prodYieldInsightLoading" depth="detailed" />
      </div>
      <div class="chart-card">
        <div class="chart-title">产品实际产出对比 <span class="chart-source">gold</span></div>
        <div v-show="hasProductData" ref="productChartRef" class="chart-body" />
        <el-empty v-if="!hasProductData && !gold.loading.value" description="该期暂无产品产出数据" :image-size="80" class="chart-empty" />
        <!-- Insight: BAR RANKING (factory, xDim:product, yMetric:quantity, gold totalActualQty) -->
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

    <!-- 明细表格 (gold product-cost-compare) -->
    <div class="detail-section" v-if="detailData.length">
      <h3>产品明细数据 <span class="chart-source">gold</span></h3>
      <el-table empty-text="暂无数据" :data="detailData" stripe border size="small" style="width: 100%">
        <el-table-column prop="index" label="#" width="50" align="center" />
        <el-table-column prop="productName" label="产品" min-width="140" />
        <el-table-column prop="output" label="实际产出(kg)" width="120" align="right" />
        <el-table-column prop="good" label="良品(kg)" width="100" align="right" />
        <el-table-column prop="yieldRate" label="出成率" width="90" align="center" />
        <el-table-column prop="reportCount" label="批次数" width="80" align="center" />
      </el-table>
    </div>

    <!-- 空状态: 无任何 gold 数据且 KPI 也为空 -->
    <el-empty
      v-if="!loading && !gold.hasAnyData.value && !dashboard?.kpis?.length"
      description="暂无生产数据 — 请先提交生产报工并确认 gold ETL 已运行"
    />
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

.data-badge {
  font-size: 12px;
  font-weight: 400;
  color: #5470c6;
  opacity: 0.8;
}

.chart-source {
  font-size: 10px;
  font-weight: 400;
  color: #fff;
  background: #5470c6;
  padding: 1px 5px;
  border-radius: 4px;
  margin-left: 4px;
  vertical-align: middle;
  opacity: 0.85;
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
