<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import echarts from '@/utils/echarts';
import TemplateGrid from '@/views/smart-bi/components/TemplateGrid.vue';
import { getDailyTrend, type DailyTrend } from '@/api/smartbi/gold';
import { getGoldDataRange } from '@/api/smartbi/dataRange';
import { resolveAllHistoryRange } from '@/views/smart-bi/analysisDefaults';
// Day 9 数据织网 Sub-Project A: capability-driven card visibility
import { useCapability } from '@/composables/useCapability';
import CapabilityGate from '@/components/CapabilityGate.vue';
import UnlockMoreCTA from '@/components/UnlockMoreCTA.vue';

const { fetchCapability } = useCapability();

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);
// Apr 24 2026 UX P1-11: restaurant tenants have no production/quality/cost
// tracking — don't show the 3 zero-value charts for them. Gold POS trend
// card above is the relevant content; legacy charts only matter for
// manufacturing tenants (type=FACTORY).
const isRestaurantTenant = computed(() => authStore.factoryType === 'RESTAURANT');

const loading = ref(false);
// WS4 #12: 默认"全部历史" (而非近7天) — 餐饮 POS 趋势第一眼即看到完整历史曲线。
const selectedPeriod = ref('all');

// 趋势数据
const trendData = ref({
  productionTrend: [] as { date: string; value: number }[],
  qualityTrend: [] as { date: string; value: number }[],
  costTrend: [] as { date: string; value: number }[],
  materialTrend: [] as { date: string; value: number }[],
  equipmentTrend: [] as { date: string; value: number }[]
});

// 图表实例
let productionChart: echarts.ECharts | null = null;
let qualityChart: echarts.ECharts | null = null;
let costChart: echarts.ECharts | null = null;
let resizeRaf = 0;

const periodOptions = [
  { label: '全部历史', value: 'all' },
  { label: '近7天', value: 'week' },
  { label: '近30天', value: 'month' },
  { label: '近90天', value: 'quarter' },
  { label: '2025全年', value: 'y2025' },
  { label: '2024全年', value: 'y2024' },
];

// Apr 21 2026: detect empty state to show CTA pointing users to AI 问答
// for POS/sales data (this page is only for production-batch sourced
// metrics; empty for factories without 生产批次).
const isEmptyAll = computed(() => {
  const arrs = [
    trendData.value.productionTrend,
    trendData.value.qualityTrend,
    trendData.value.costTrend,
  ];
  return arrs.every(a => {
    if (!Array.isArray(a) || a.length === 0) return true;
    return a.every((d: Record<string, unknown>) => {
      const v = Number((d as { value?: unknown }).value ?? 0);
      return !Number.isFinite(v) || v === 0;
    });
  });
});

let resizeObserver: ResizeObserver | null = null;

// v1.2 Week 9 Gold flip (趋势): restaurant tenants see POS-derived
// revenue+orders trend backed by /gold/daily-trend. Manufacturing tenants
// get empty points → the section hides and legacy production charts show.
const goldTrend = ref<DailyTrend | null>(null);
let goldRevenueChart: echarts.ECharts | null = null;
// Apr 24 Plan C Phase 8: second-axis metric toggle. Data already in /gold/daily-trend
// — 3 fields per point: revenue, bill_count, avg_bill_value.
type TrendMetric = 'bills' | 'avg';
const TREND_METRIC_KEY = 'trends.secondMetric.v1';
const trendSecondMetric = ref<TrendMetric>(
  (() => {
    try { const v = localStorage.getItem(TREND_METRIC_KEY); return (v === 'avg' || v === 'bills') ? v : 'bills'; }
    catch { return 'bills'; }
  })()
);
watch(trendSecondMetric, (v) => {
  try { localStorage.setItem(TREND_METRIC_KEY, v); } catch { /* quota/privacy mode */ }
});

function _periodToDateRange(period: string): [string, string] {
  const iso = (d: Date): string => d.toISOString().slice(0, 10);
  if (period === 'y2025') return ['2025-01-01', '2025-12-31'];
  if (period === 'y2024') return ['2024-01-01', '2024-12-31'];
  const end = new Date();
  const start = new Date();
  const days = period === 'week' ? 7 : period === 'quarter' ? 90 : 30;
  start.setDate(start.getDate() - (days - 1));
  return [iso(start), iso(end)];
}

// WS4 #12: 'all' = 全部历史 — 探 gold 数据窗拿真实 [min, max]; 失败回落宽窗。
async function _resolvePrimaryRange(period: string): Promise<[string, string]> {
  if (period === 'all') {
    try {
      const probe = factoryId.value ? await getGoldDataRange(factoryId.value) : null;
      return resolveAllHistoryRange(probe);
    } catch {
      return resolveAllHistoryRange(null);
    }
  }
  return _periodToDateRange(period);
}

// Auto-fallback label shown when Gold chart falls back to a prior range.
const goldTrendFallbackLabel = ref<string>('');

// Apr 25 2026 UX P3 (C-3): 365-day daily granularity creates "noise wall".
// Auto-aggregate to weekly (90-365 days) or monthly (>365 days). Granularity
// tag in card header tells user data is summarized, not raw daily.
type TrendGranularity = 'daily' | 'weekly' | 'monthly';
type TrendPoint = DailyTrend['points'][number];
const trendGranularity = ref<TrendGranularity>('daily');

function aggregatePoints(points: TrendPoint[]): { points: TrendPoint[]; granularity: TrendGranularity } {
  if (!points || points.length === 0) return { points, granularity: 'daily' };
  if (points.length <= 90) return { points, granularity: 'daily' };

  const granularity: TrendGranularity = points.length <= 365 ? 'weekly' : 'monthly';
  const buckets = new Map<string, TrendPoint[]>();

  for (const p of points) {
    const d = new Date(p.date);
    if (isNaN(d.getTime())) continue;
    let key: string;
    if (granularity === 'weekly') {
      // ISO week bucket: Monday-aligned, label = Monday's YYYY-MM-DD
      const day = d.getDay() || 7; // Sunday = 7
      const monday = new Date(d);
      monday.setDate(d.getDate() - day + 1);
      key = monday.toISOString().slice(0, 10);
    } else {
      key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
    }
    if (!buckets.has(key)) buckets.set(key, []);
    buckets.get(key)!.push(p);
  }

  const aggregated: TrendPoint[] = Array.from(buckets.entries())
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([date, pts]) => {
      const revenueSum = pts.reduce((s, p) => s + (Number(p.revenue) || 0), 0);
      const billCountSum = pts.reduce((s, p) => s + (Number(p.billCount) || 0), 0);
      // avg bill value = sum revenue / sum bills (correct weighted avg, not mean of means)
      const avgBillValue = billCountSum > 0 ? revenueSum / billCountSum : null;
      return { date, revenue: revenueSum, billCount: billCountSum, avgBillValue };
    });

  return { points: aggregated, granularity };
}

// Apr 25 2026 UX P2 (C-3): banner must explicitly name BOTH the originally
// requested range and the actual range being shown, otherwise user thinks
// they are seeing data for the period the dropdown says (e.g. picks 2024全年
// but chart shows 2025 data with only "已显示 2025全年" hint).
const selectedPeriodLabel = computed(() => {
  const opt = periodOptions.find(o => o.value === selectedPeriod.value);
  return opt ? opt.label : selectedPeriod.value;
});

async function loadGoldTrend() {
  if (!factoryId.value) return;
  // P1-14 perf: cache last-found non-empty range per factory in localStorage
  // so subsequent visits skip the serial empty-range fallback ladder (~700ms).
  // Primary range is still tried first; cached fallback used only when primary empty.
  const iso = (d: Date): string => d.toISOString().slice(0, 10);
  const y = new Date().getFullYear();
  const primary = await _resolvePrimaryRange(selectedPeriod.value);
  const cacheKey = `goldTrend.lastRange.${factoryId.value}`;
  const cached = (() => {
    try { return JSON.parse(localStorage.getItem(cacheKey) || 'null') as { s: string; e: string; label: string } | null; }
    catch { return null; }
  })();

  // 1. Always try user's primary selection first
  try {
    const resp = await getDailyTrend({ factoryId: factoryId.value, startDate: primary[0], endDate: primary[1] });
    if (resp && resp.points && resp.points.length > 0) {
      goldTrend.value = resp;
      goldTrendFallbackLabel.value = '';
      updateGoldChart();
      return;
    }
  } catch { /* fall through */ }

  // 2. If cache has a known-good range, try it next (skip empty intermediates)
  if (cached && (cached.s !== primary[0] || cached.e !== primary[1])) {
    try {
      const resp = await getDailyTrend({ factoryId: factoryId.value, startDate: cached.s, endDate: cached.e });
      if (resp && resp.points && resp.points.length > 0) {
        goldTrend.value = resp;
        goldTrendFallbackLabel.value = cached.label;
        updateGoldChart();
        return;
      }
    } catch { /* fall through */ }
  }

  // 3. Standard fallback ladder (also populates cache)
  const fallbackChain: Array<[string, string, string]> = [
    (() => { const e = new Date(); const s = new Date(); s.setDate(s.getDate() - 89); return [iso(s), iso(e), '近90天'] as [string, string, string]; })(),
    [`${y - 1}-01-01`, `${y - 1}-12-31`, `${y - 1}全年`],
    [`${y - 2}-01-01`, `${y - 2}-12-31`, `${y - 2}全年`],
  ];
  for (const [s, e, label] of fallbackChain) {
    try {
      const resp = await getDailyTrend({ factoryId: factoryId.value, startDate: s, endDate: e });
      if (resp && resp.points && resp.points.length > 0) {
        goldTrend.value = resp;
        goldTrendFallbackLabel.value = label;
        updateGoldChart();
        try { localStorage.setItem(cacheKey, JSON.stringify({ s, e, label })); } catch { /* quota/privacy mode ignore */ }
        return;
      }
    } catch { /* try next */ }
  }
  goldTrend.value = null;
  goldTrendFallbackLabel.value = '';
}

function updateGoldChart() {
  if (!goldRevenueChart || !goldTrend.value) return;
  // Apr 25 UX P3: aggregate weekly/monthly when range too dense for daily display
  const agg = aggregatePoints(goldTrend.value.points);
  trendGranularity.value = agg.granularity;
  const pts = agg.points;
  const secondMetric = trendSecondMetric.value;
  const secondMeta = secondMetric === 'bills'
    ? { name: '订单数', color: '#E6A23C', data: pts.map(p => p.billCount), unit: '' }
    : { name: '客单价', color: '#409EFF', data: pts.map(p => p.avgBillValue ?? 0), unit: '元' };
  // Series-aware tooltip: 营收 always gets ¥ + thousands + 万; second metric
  // formats per its nature (整数 for bills, 2-decimal ¥ for avg).
  const fmtRevenue = (v: number) => v >= 10000 ? `¥${(v / 10000).toFixed(2)}万` : `¥${v.toLocaleString('zh-CN', { maximumFractionDigits: 0 })}`;
  const fmtSecond = secondMetric === 'bills'
    ? (v: number) => String(Math.round(v))
    : (v: number) => `¥${v.toFixed(2)}`;
  const granularitySuffix = agg.granularity === 'weekly' ? ' · 按周' : agg.granularity === 'monthly' ? ' · 按月' : '';
  goldRevenueChart.setOption({
    title: { text: `POS 营收趋势 (Gold)${granularitySuffix}`, left: 'center', textStyle: { fontSize: 14 } },
    tooltip: {
      trigger: 'axis', confine: true,
      formatter: (params: Array<{ seriesName: string; value: number; axisValue: string; marker: string }>) => {
        if (!Array.isArray(params) || params.length === 0) return '';
        const lines = [params[0].axisValue];
        for (const p of params) {
          const v = typeof p.value === 'number' ? p.value : Number(p.value);
          const formatted = p.seriesName === '营收' ? fmtRevenue(v) : fmtSecond(v);
          lines.push(`${p.marker} ${p.seriesName}: ${formatted}`);
        }
        return lines.join('<br/>');
      },
    },
    legend: { data: ['营收', secondMeta.name], top: 24 },
    grid: { top: 60, left: 60, right: 60, bottom: 40 },
    xAxis: { type: 'category', data: pts.map(p => p.date) },
    yAxis: [
      { type: 'value', name: '营收', axisLabel: { formatter: (v: number) => v >= 10000 ? `${(v / 10000).toFixed(1)}万` : String(v) } },
      { type: 'value', name: secondMeta.name + (secondMeta.unit ? `(${secondMeta.unit})` : '') },
    ],
    series: [
      {
        name: '营收',
        type: 'line',
        smooth: true,
        areaStyle: { opacity: 0.3 },
        itemStyle: { color: '#67C23A' },
        data: pts.map(p => Number(p.revenue)),
      },
      {
        name: secondMeta.name,
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        itemStyle: { color: secondMeta.color },
        data: secondMeta.data,
      },
    ],
  }, true);
}

function onTrendMetricChange() {
  updateGoldChart();
}

onMounted(() => {
  // Day 9 数据织网 Sub-Project A: prime capability cache (fire-and-forget,
  // useCapability handles errors and is fail-open).
  fetchCapability();
  loadTrendData();
  loadGoldTrend();
  initCharts();
  const goldEl = document.getElementById('gold-revenue-chart');
  if (goldEl) {
    goldRevenueChart = echarts.init(goldEl, 'cretas');
    updateGoldChart();
  }
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(handleResize);
    document.querySelectorAll('.chart').forEach(el => resizeObserver!.observe(el));
  }
  window.addEventListener('resize', handleResize);
});

watch(selectedPeriod, () => {
  loadTrendData();
  loadGoldTrend();
});

async function loadTrendData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    // 工厂生产趋势 (Java reports) 仅认 week/month/quarter/year; 'all' (全部历史) 映射到 year
    // 作其最宽窗。餐饮 POS 趋势 (loadGoldTrend) 才真正按 gold 数据窗取全部历史。
    const javaPeriod = selectedPeriod.value === 'all' ? 'year' : selectedPeriod.value;
    const response = await get(`/${factoryId.value}/reports/dashboard/trends`, {
      params: { period: javaPeriod }
    });
    if (response.success && response.data) {
      trendData.value = response.data;
      updateCharts();
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载趋势数据失败');
    }
  } catch (error) {
    console.error('加载趋势数据失败:', error);
    ElMessage.error('加载趋势数据失败，请重试');
  } finally {
    loading.value = false;
  }
}

function initCharts() {
  const productionEl = document.getElementById('production-chart');
  const qualityEl = document.getElementById('quality-chart');
  const costEl = document.getElementById('cost-chart');

  if (productionEl) {
    productionChart = echarts.init(productionEl, 'cretas');
  }
  if (qualityEl) {
    qualityChart = echarts.init(qualityEl, 'cretas');
  }
  if (costEl) {
    costChart = echarts.init(costEl, 'cretas');
  }
}

function updateCharts() {
  // 生产趋势图
  if (productionChart) {
    const data = trendData.value.productionTrend || [];
    productionChart.setOption({
      title: { text: '生产趋势', left: 'center' },
      tooltip: { trigger: 'axis', confine: true },
      xAxis: {
        type: 'category',
        data: data.map((d: Record<string, unknown>) => d.date || d.label)
      },
      yAxis: { type: 'value', name: '产量' },
      series: [{
        name: '产量',
        type: 'line',
        smooth: true,
        data: data.map((d: Record<string, unknown>) => d.output || d.value || 0),
        areaStyle: { opacity: 0.3 },
        itemStyle: { color: '#409EFF' }
      }]
    });
  }

  // 质量趋势图
  if (qualityChart) {
    const data = trendData.value.qualityTrend || [];
    qualityChart.setOption({
      title: { text: '质量趋势', left: 'center' },
      tooltip: { trigger: 'axis', confine: true, formatter: '{b}: {c}%' },
      xAxis: {
        type: 'category',
        data: data.map((d: Record<string, unknown>) => d.date || d.label)
      },
      yAxis: { type: 'value', name: '合格率 (%)', max: 100 },
      series: [{
        name: '合格率',
        type: 'line',
        smooth: true,
        data: data.map((d: Record<string, unknown>) => (Number(d.passRate || d.value || 0) * 100).toFixed(1)),
        areaStyle: { opacity: 0.3 },
        itemStyle: { color: '#67C23A' }
      }]
    });
  }

  // 成本趋势图
  if (costChart) {
    const data = trendData.value.costTrend || [];
    costChart.setOption({
      title: { text: '成本趋势', left: 'center' },
      tooltip: { trigger: 'axis', confine: true },
      xAxis: {
        type: 'category',
        data: data.map((d: Record<string, unknown>) => d.date || d.label)
      },
      yAxis: { type: 'value', name: '成本 (元)' },
      series: [{
        name: '总成本',
        type: 'bar',
        data: data.map((d: Record<string, unknown>) => d.totalCost || d.value || 0),
        itemStyle: { color: '#E6A23C' }
      }]
    });
  }
}

function handleResize() {
  if (resizeRaf) return;
  resizeRaf = requestAnimationFrame(() => {
    resizeRaf = 0;
    productionChart?.resize();
    qualityChart?.resize();
    costChart?.resize();
    goldRevenueChart?.resize();
  });
}

function handleRefresh() {
  loadTrendData();
  loadGoldTrend();
}

onUnmounted(() => {
  resizeObserver?.disconnect();
  if (resizeRaf) cancelAnimationFrame(resizeRaf);
  window.removeEventListener('resize', handleResize);
  productionChart?.dispose();
  qualityChart?.dispose();
  costChart?.dispose();
  goldRevenueChart?.dispose();
  productionChart = null;
  qualityChart = null;
  goldRevenueChart = null;
  costChart = null;
});
</script>

<template>
  <div class="trends-page">
    <div class="page-header">
      <div class="header-left">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item :to="{ path: '/analytics' }">数据分析</el-breadcrumb-item>
          <el-breadcrumb-item>趋势分析</el-breadcrumb-item>
        </el-breadcrumb>
        <h1>趋势分析</h1>
      </div>
      <div class="header-right">
        <el-select v-model="selectedPeriod" style="width: 120px; margin-right: 12px;">
          <el-option v-for="opt in periodOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-button type="primary" @click="handleRefresh">刷新</el-button>
      </div>
    </div>

    <el-alert
      v-if="isEmptyAll && !goldTrend && !isRestaurantTenant"
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 16px;"
    >
      <template #title>
        暂无生产数据 — 本页展示「生产批次」汇总的产量/良品率/成本趋势。请在
        <el-link type="primary" href="/production/batches" :underline="false">生产管理 → 批次</el-link>
        录入批次后自动生成。若需查看「POS/销售数据」趋势（如营业额、订单量），请前往
        <el-link type="primary" href="/smart-bi/query" :underline="false">智能BI → AI 问答</el-link>
        询问「近 N 天营业额趋势」。
      </template>
    </el-alert>

    <!-- v1.2 Week 9 Gold flip: POS revenue+orders trend for restaurant tenants -->
    <CapabilityGate v-if="canViewPrice" card-id="trends_monthly" :requires="['date', 'net_amount']">
    <el-card v-show="goldTrend" class="chart-card gold-trend-card" style="margin-bottom: 16px; border-top: 3px solid #67C23A;">
      <template #header>
        <div style="display: flex; align-items: center; gap: 8px; font-weight: 600; flex-wrap: wrap;">
 <span> POS 营收趋势</span>
          <el-tag size="small" type="success">Gold · daily_trend</el-tag>
          <el-tag v-if="trendGranularity === 'weekly'" size="small" type="info" effect="plain"
                  title="范围 90-365 天时,自动按周聚合以减少视觉噪音">按周聚合</el-tag>
          <el-tag v-else-if="trendGranularity === 'monthly'" size="small" type="info" effect="plain"
                  title="范围超过 365 天时,自动按月聚合以减少视觉噪音">按月聚合</el-tag>
          <el-tag v-if="goldTrendFallbackLabel" size="small" type="warning" effect="plain">
            {{ selectedPeriodLabel }}无数据,已自动展示 {{ goldTrendFallbackLabel }}
          </el-tag>
          <el-radio-group v-model="trendSecondMetric" size="small" @change="onTrendMetricChange" style="margin-left: auto;">
            <el-radio-button value="bills">订单数</el-radio-button>
            <el-radio-button value="avg">客单价</el-radio-button>
          </el-radio-group>
          <span v-if="goldTrend" style="color: #909399; font-size: 12px;">
            {{ goldTrend.points.length }} 天
          </span>
        </div>
      </template>
      <div id="gold-revenue-chart" class="chart" style="height: 320px;"></div>
    </el-card>
    </CapabilityGate>

    <!-- 餐饮租户不需要生产/质量/成本图 (P1-11) — 隐藏占屏的 0 值 chart -->
    <div v-if="!isRestaurantTenant" class="charts-container" v-loading="loading">
      <el-row :gutter="16">
        <el-col :span="24">
          <el-card class="chart-card">
            <div id="production-chart" class="chart"></div>
          </el-card>
        </el-col>
      </el-row>

      <el-row :gutter="16">
        <el-col :span="canViewPrice ? 12 : 24">
          <el-card class="chart-card">
            <div id="quality-chart" class="chart"></div>
          </el-card>
        </el-col>
        <el-col v-if="canViewPrice" :span="12">
          <el-card class="chart-card">
            <div id="cost-chart" class="chart"></div>
          </el-card>
        </el-col>
      </el-row>
    </div>

    <!-- Week 6 Template Surfacing: show analysis results for this page -->
    <TemplateGrid page-key="trend" :factory-id="factoryId || ''" />

    <UnlockMoreCTA />
  </div>
</template>

<style lang="scss" scoped>
.trends-page {
  padding: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 24px;

  .header-left {
    h1 {
      margin: 12px 0 0;
      font-size: 20px;
      font-weight: 600;
    }
  }

  .header-right {
    display: flex;
    align-items: center;
  }
}

.charts-container {
  .el-row {
    margin-bottom: 16px;
  }
}

.chart-card {
  border-radius: 8px;

  .chart {
    height: 350px;
    width: 100%;
  }
}
</style>
