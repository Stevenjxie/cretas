<template>
  <div class="analytics-strip" v-if="chartData.trend.length > 0 || chartData.ranking.length > 0">
    <el-row :gutter="16">
      <el-col :xs="24" :sm="12">
        <div class="chart-card">
          <div class="chart-title">
            <el-icon><TrendCharts /></el-icon>
            <span>{{ trendTitle }}</span>
            <span class="chart-meta">{{ trendMeta }}</span>
          </div>
          <div class="chart-body" :id="`chart-trend-${uid}`" v-if="chartData.trend.length > 0"></div>
          <div v-else class="chart-empty">暂无足够数据绘制趋势</div>
          <!-- U6: useChartInsight — TREND insight -->
          <ChartInsight :insight="trendInsight" :loading="trendInsightLoading" depth="detailed" />
        </div>
      </el-col>
      <el-col :xs="24" :sm="12">
        <div class="chart-card">
          <div class="chart-title">
            <el-icon><DataAnalysis /></el-icon>
            <span>{{ rankingTitle }}</span>
            <span class="chart-meta">Top {{ chartData.ranking.length }}</span>
          </div>
          <div class="chart-body" :id="`chart-ranking-${uid}`" v-if="chartData.ranking.length > 0"></div>
          <div v-else class="chart-empty">暂无排行数据</div>
          <!-- U6: useChartInsight — RANKING insight -->
          <ChartInsight :insight="rankingInsight" :loading="rankingInsightLoading" depth="detailed" />
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onBeforeUnmount } from 'vue';
import { TrendCharts, DataAnalysis } from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import { useChartInsight } from '@/composables/useChartInsight';
import { useFactoryId } from '@/composables/useFactoryId';
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue';

interface TrendPoint { date: string; value: number }
interface RankingItem { name: string; value: number }

const props = defineProps<{
  // List data to aggregate
  rows: Record<string, unknown>[];
  // Date field (e.g. 'requisitionDate', 'wastageDate')
  dateField: string;
  // Value field to sum per day (numeric). If null, counts rows per day.
  valueField?: string | null;
  // Category field for ranking (e.g. 'type', 'productTypeId', 'rawMaterialTypeId')
  categoryField: string;
  // Optional mapping from category id → display name
  categoryNameMap?: Record<string, string>;
  // Titles
  trendTitle?: string;
  rankingTitle?: string;
  // Formatting
  valueUnit?: string;   // '元' or '单' etc
  isCurrency?: boolean;
  topN?: number;
}>();

const factoryId = useFactoryId();

// Generate a unique DOM id so multiple strips on same page don't collide
const uid = Math.random().toString(36).slice(2, 10);

const chartData = computed(() => {
  const rows = props.rows || [];
  const dateField = props.dateField;
  const valueField = props.valueField || null;
  const catField = props.categoryField;
  const topN = props.topN || 10;

  // ── Trend: group by day ──
  const byDay = new Map<string, number>();
  for (const r of rows) {
    const dRaw = r[dateField];
    if (!dRaw) continue;
    const day = String(dRaw).slice(0, 10);
    const v = valueField ? Number(r[valueField]) || 0 : 1;
    byDay.set(day, (byDay.get(day) || 0) + v);
  }
  const trend: TrendPoint[] = Array.from(byDay.entries())
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, value]) => ({ date, value }));

  // ── Ranking: group by category ──
  const byCat = new Map<string, number>();
  for (const r of rows) {
    const cat = String(r[catField] ?? '未分类');
    if (!cat) continue;
    const v = valueField ? Number(r[valueField]) || 0 : 1;
    byCat.set(cat, (byCat.get(cat) || 0) + v);
  }
  const ranking: RankingItem[] = Array.from(byCat.entries())
    .sort((a, b) => b[1] - a[1])
    .slice(0, topN)
    .map(([key, value]) => ({
      name: props.categoryNameMap?.[key] || key,
      value,
    }));

  return { trend, ranking };
});

const trendMeta = computed(() => {
  const t = chartData.value.trend;
  if (t.length === 0) return '';
  const total = t.reduce((s, p) => s + p.value, 0);
  const unit = props.valueUnit || '';
  return props.isCurrency
    ? `合计 ¥${total.toLocaleString('zh-CN', { maximumFractionDigits: 0 })}`
    : `合计 ${total.toLocaleString('zh-CN')} ${unit}`;
});

let trendChart: echarts.ECharts | null = null;
let rankingChart: echarts.ECharts | null = null;

function renderTrend() {
  const el = document.getElementById(`chart-trend-${uid}`);
  if (!el || chartData.value.trend.length === 0) return;
  if (!trendChart) trendChart = echarts.init(el);
  const t = chartData.value.trend;
  trendChart.setOption({
    tooltip: {
      trigger: 'axis', confine: true,
      formatter: (p: Record<string, unknown>[]) => {
        const pt = p[0];
        const v = Number(pt.value);
        const fmt = props.isCurrency
          ? `¥${v.toLocaleString('zh-CN', { maximumFractionDigits: 2 })}`
          : `${v.toLocaleString('zh-CN')} ${props.valueUnit || ''}`;
        return `${pt.axisValue}<br/>${fmt}`;
      },
    },
    grid: { left: 50, right: 20, top: 20, bottom: 40 },
    xAxis: {
      type: 'category',
      data: t.map(p => p.date.slice(5)),  // MM-DD
      axisLabel: { interval: t.length > 14 ? 'auto' : 0, fontSize: 11 },
    },
    yAxis: {
      type: 'value', min: 0,
      axisLabel: {
        formatter: (v: number) => props.isCurrency
          ? (v >= 1e4 ? (v / 1e4).toFixed(1) + '万' : String(Math.round(v)))
          : String(Math.round(v)),
      },
    },
    series: [{
      type: 'line', smooth: true,
      data: t.map(p => p.value),
      itemStyle: { color: '#2D8B57' },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(45, 139, 87, 0.3)' },
          { offset: 1, color: 'rgba(45, 139, 87, 0.02)' },
        ]),
      },
    }],
  });
}

function renderRanking() {
  const el = document.getElementById(`chart-ranking-${uid}`);
  if (!el || chartData.value.ranking.length === 0) return;
  if (!rankingChart) rankingChart = echarts.init(el);
  const r = chartData.value.ranking.slice().reverse();  // bar chart bottom-up
  rankingChart.setOption({
    tooltip: {
      trigger: 'axis', confine: true,
      formatter: (p: Record<string, unknown>[]) => {
        const pt = p[0];
        const v = Number(pt.value);
        const fmt = props.isCurrency
          ? `¥${v.toLocaleString('zh-CN', { maximumFractionDigits: 2 })}`
          : `${v.toLocaleString('zh-CN')} ${props.valueUnit || ''}`;
        return `${pt.name}: ${fmt}`;
      },
    },
    grid: { left: 110, right: 30, top: 10, bottom: 30 },
    xAxis: {
      type: 'value',
      axisLabel: {
        formatter: (v: number) => props.isCurrency
          ? (v >= 1e4 ? (v / 1e4).toFixed(1) + '万' : String(Math.round(v)))
          : String(Math.round(v)),
      },
    },
    yAxis: {
      type: 'category',
      data: r.map(i => i.name.slice(0, 12)),
      axisLabel: { fontSize: 11, overflow: 'truncate', width: 100 },
    },
    series: [{
      type: 'bar',
      data: r.map(i => i.value),
      itemStyle: { color: '#E6A23C', borderRadius: [0, 4, 4, 0] },
      label: { show: true, position: 'right', fontSize: 11,
        formatter: (p: Record<string, unknown>) => {
          const v = Number(p.value);
          return props.isCurrency
            ? (v >= 1e4 ? '¥' + (v / 1e4).toFixed(1) + '万' : '¥' + Math.round(v))
            : String(Math.round(v));
        },
      },
    }],
  });
}

// ==================== Chart Insight ====================
// Call 1 — TREND (LINE): trend points over time, domain='restaurant'.
const trendInsightSource = () => {
  const t = chartData.value.trend;
  if (t.length < 2) return null;
  return {
    chart: {
      chartType: 'LINE',
      meta: { xDim: 'time' as const, yMetric: 'revenue' as const, aggregation: 'sum' as const, domain: 'restaurant' as const },
      config: {
        xAxis: { data: t.map((p) => p.date) },
        series: [{ type: 'line', data: t.map((p) => p.value) }],
      },
    },
  };
};

// Call 2 — RANKING (BAR): category ranking by value, domain='restaurant'.
const rankingInsightSource = () => {
  const r = chartData.value.ranking;
  if (r.length < 2) return null;
  return {
    chart: {
      chartType: 'BAR',
      meta: { xDim: 'store' as const, yMetric: 'revenue' as const, aggregation: 'sum' as const, domain: 'restaurant' as const },
      config: {
        xAxis: { data: r.map((i) => i.name) },
        series: [{ type: 'bar', data: r.map((i) => i.value) }],
      },
    },
  };
};

const { insight: trendInsight, loading: trendInsightLoading } = useChartInsight(
  trendInsightSource,
  () => ({ canViewFinance: false }),
  { factoryId: () => factoryId.value, autoTier2: true },
);

const { insight: rankingInsight, loading: rankingInsightLoading } = useChartInsight(
  rankingInsightSource,
  () => ({ canViewFinance: false }),
  { factoryId: () => factoryId.value, autoTier2: true },
);

watch(chartData, () => { nextTick(() => { renderTrend(); renderRanking(); }); }, { deep: true, immediate: true });

const resizeHandler = () => { trendChart?.resize(); rankingChart?.resize(); };
if (typeof window !== 'undefined') window.addEventListener('resize', resizeHandler);

onBeforeUnmount(() => {
  if (typeof window !== 'undefined') window.removeEventListener('resize', resizeHandler);
  trendChart?.dispose();
  rankingChart?.dispose();
  trendChart = null;
  rankingChart = null;
});
</script>

<style scoped lang="scss">
.analytics-strip {
  margin-top: 12px;
  margin-bottom: 16px;
}
.chart-card {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px 16px;
  height: 100%;
  .chart-title {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 14px;
    font-weight: 600;
    color: #303133;
    margin-bottom: 10px;
    .chart-meta {
      margin-left: auto;
      font-size: 12px;
      font-weight: normal;
      color: #909399;
    }
  }
  .chart-body { height: 240px; }
  .chart-empty {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 240px;
    color: #c0c4cc;
    font-size: 13px;
  }
}
</style>
