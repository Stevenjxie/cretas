<script setup lang="ts">
/**
 * FactoryCostAnalysis.vue — 工厂生产成本分析
 *
 * 消费 Python factory gold 端点:
 *   GET /api/smartbi/factory/cost-structure     → 成本结构饼图 + 趋势
 *   GET /api/smartbi/factory/yield-trend        → 出成率趋势折线
 *   GET /api/smartbi/factory/product-cost-compare → 产品成本对比柱形
 *
 * RBAC: 成本绝对值仅 canViewPrice 角色可见 (同 RestaurantFinanceContent 模式).
 *       后端已脱敏 (null), 前端再 v-if canViewPrice 隐藏整个成本卡区.
 *
 * 空状态: 无 gold 数据 → 白话提示 "该工厂暂无生产成本数据 — 报工填了成本才会显示".
 * 不碰后端 gold/ETL.
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useChartInsight } from '@/composables/useChartInsight';
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue';
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight';
import {
  getFactoryCostStructure,
  getFactoryYieldTrend,
  getFactoryProductCostCompare,
  type CostStructureData,
  type YieldTrendData,
  type ProductCostCompareData,
} from '@/api/smartbi/factoryGold';

// ── Stores ────────────────────────────────────────────────────────────────────

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);

// ── Date range ────────────────────────────────────────────────────────────────

const dateRange = ref<[Date, Date]>([
  new Date(Date.now() - 29 * 86400000),
  new Date(),
]);

const shortcuts = [
  { text: '近7天',  value: () => [new Date(Date.now() - 6 * 86400000),  new Date()] as [Date, Date] },
  { text: '近30天', value: () => [new Date(Date.now() - 29 * 86400000), new Date()] as [Date, Date] },
  { text: '近90天', value: () => [new Date(Date.now() - 89 * 86400000), new Date()] as [Date, Date] },
];

function fmtDate(d: Date | string): string {
  if (typeof d === 'string') return d.split('T')[0];
  return d.toISOString().split('T')[0];
}

function getQueryDates() {
  const [start, end] = dateRange.value;
  return { startDate: fmtDate(start), endDate: fmtDate(end) };
}

// ── State ─────────────────────────────────────────────────────────────────────

const loading = ref(false);
const loadError = ref('');

const costData   = ref<CostStructureData | null>(null);
const yieldData  = ref<YieldTrendData   | null>(null);
const productData = ref<ProductCostCompareData | null>(null);

// ── Derived empty checks ──────────────────────────────────────────────────────

/** True when backend returned rows (even if costs are null due to RBAC) */
const hasAnyCostRows = computed(() => (costData.value?.rows?.length ?? 0) > 0);
const hasYieldTrend  = computed(() => (yieldData.value?.trend?.length ?? 0) >= 2);
const hasProducts    = computed(() => (productData.value?.products?.length ?? 0) > 0);

/** True if any of the 3 endpoints returned data */
const hasAnyData = computed(() => hasAnyCostRows.value || hasYieldTrend.value || hasProducts.value);

// ── Cost structure helpers ────────────────────────────────────────────────────

/** Build [材料, 人工, 设备, 其他] pie slices from summary */
const costPieSlices = computed(() => {
  const s = costData.value?.summary;
  if (!s || !canViewPrice.value) return [];
  const entries = [
    { name: '材料成本', value: s.totalMaterialCost  ?? 0 },
    { name: '人工成本', value: s.totalLaborCost     ?? 0 },
    { name: '设备成本', value: s.totalEquipmentCost ?? 0 },
    { name: '其他成本', value: s.totalOtherCost     ?? 0 },
  ];
  return entries.filter(e => e.value > 0);
});

const hasCostPie = computed(() => costPieSlices.value.length > 0);

// ── Chart refs ────────────────────────────────────────────────────────────────

const costPieRef    = ref<HTMLDivElement>();
const costTrendRef  = ref<HTMLDivElement>();
const yieldTrendRef = ref<HTMLDivElement>();
const productBarRef = ref<HTMLDivElement>();

let costPieChart:    echarts.ECharts | null = null;
let costTrendChart:  echarts.ECharts | null = null;
let yieldTrendChart: echarts.ECharts | null = null;
let productBarChart: echarts.ECharts | null = null;

let renderTimer: ReturnType<typeof setTimeout> | null = null;

// ── Render functions ──────────────────────────────────────────────────────────

function renderCostPie() {
  if (!costPieRef.value || !hasCostPie.value) return;
  if (!costPieChart || costPieChart.isDisposed?.()) {
    costPieChart = echarts.init(costPieRef.value, 'cretas');
  }
  const total = costPieSlices.value.reduce((s, e) => s + e.value, 0);
  costPieChart.setOption({
    tooltip: {
      trigger: 'item',
      formatter: ({ name, value, percent }: { name: string; value: number; percent: number }) =>
        `${name}: ¥${value.toFixed(0)} (${percent.toFixed(1)}%)`,
    },
    legend: { orient: 'vertical', right: '5%', top: 'center', textStyle: { fontSize: 12 } },
    series: [{
      type: 'pie',
      radius: ['35%', '62%'],
      center: ['38%', '50%'],
      data: costPieSlices.value,
      label: { formatter: '{d}%', fontSize: 12 },
      emphasis: { itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0,0,0,0.2)' } },
    }],
    graphic: [{
      type: 'text',
      left: 'center',
      top: 'center',
      style: { text: `¥${(total / 10000).toFixed(1)}万`, fontSize: 14, fontWeight: 'bold', fill: '#1d2129' },
    }],
  });
}

function renderCostTrend() {
  if (!costTrendRef.value || !hasAnyCostRows.value || !canViewPrice.value) return;
  if (!costTrendChart || costTrendChart.isDisposed?.()) {
    costTrendChart = echarts.init(costTrendRef.value, 'cretas');
  }
  const rows = costData.value!.rows;
  const dates = rows.map(r => r.statDate.slice(5)); // MM-DD
  costTrendChart.setOption({
    tooltip: { trigger: 'axis', confine: true },
    legend: { data: ['材料', '人工', '设备', '其他'], bottom: 0, type: 'scroll' },
    grid: { top: 30, right: 20, bottom: 40, left: 60 },
    xAxis: { type: 'category', data: dates, axisLabel: { fontSize: 11 } },
    yAxis: { type: 'value', name: '成本(¥)' },
    series: [
      { name: '材料', type: 'bar', stack: 'cost', data: rows.map(r => r.totalMaterialCost  ?? 0), itemStyle: { color: '#5470c6' } },
      { name: '人工', type: 'bar', stack: 'cost', data: rows.map(r => r.totalLaborCost     ?? 0), itemStyle: { color: '#91cc75' } },
      { name: '设备', type: 'bar', stack: 'cost', data: rows.map(r => r.totalEquipmentCost ?? 0), itemStyle: { color: '#fac858' } },
      { name: '其他', type: 'bar', stack: 'cost', data: rows.map(r => r.totalOtherCost     ?? 0), itemStyle: { color: '#ee6666' } },
    ],
  });
}

function renderYieldTrend() {
  if (!yieldTrendRef.value || !hasYieldTrend.value) return;
  if (!yieldTrendChart || yieldTrendChart.isDisposed?.()) {
    yieldTrendChart = echarts.init(yieldTrendRef.value, 'cretas');
  }
  const points = yieldData.value!.trend;
  const dates = points.map(p => p.statDate.slice(5));
  const rates = points.map(p => p.avgYieldRate != null ? Number(p.avgYieldRate.toFixed(1)) : null);
  const minRate = rates.filter((v): v is number => v !== null);
  yieldTrendChart.setOption({
    tooltip: { trigger: 'axis', confine: true, formatter: '{b}<br/>出成率: {c}%' },
    grid: { top: 30, right: 20, bottom: 30, left: 60 },
    xAxis: { type: 'category', data: dates, axisLabel: { fontSize: 11 } },
    yAxis: {
      type: 'value',
      name: '出成率(%)',
      min: minRate.length ? Math.max(0, Math.min(...minRate) - 5) : 0,
      max: 100,
    },
    series: [{
      name: '出成率',
      type: 'line',
      data: rates,
      smooth: true,
      connectNulls: false,
      itemStyle: { color: '#f5576c' },
      areaStyle: {
        color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [{ offset: 0, color: 'rgba(245,87,108,0.3)' }, { offset: 1, color: 'rgba(245,87,108,0.02)' }] },
      },
      markLine: {
        data: [{ yAxis: 80, name: '基准', lineStyle: { color: '#52c41a', type: 'dashed' }, label: { formatter: '基准 80%' } }],
        silent: true,
      },
      markPoint: {
        data: [{ type: 'max', name: '最高' }, { type: 'min', name: '最低' }],
        symbolSize: 36,
      },
    }],
  });
}

function renderProductBar() {
  if (!productBarRef.value || !hasProducts.value) return;
  if (!productBarChart || productBarChart.isDisposed?.()) {
    productBarChart = echarts.init(productBarRef.value, 'cretas');
  }
  const prods = productData.value!.products.slice(0, 10); // top 10
  // Use productTypeId as label if product name not available (gold uses ID)
  const labels = prods.map(p => p.productTypeId);
  const canShow = canViewPrice.value;

  if (canShow) {
    productBarChart.setOption({
      tooltip: {
        trigger: 'axis',
        confine: true,
        formatter: (params: Array<{ name: string; value: number; dataIndex?: number }>) => {
          const p = prods[params[0]?.dataIndex ?? 0];
          if (!p) return '';
          return `${p.productTypeId}<br/>
            总成本: ¥${(p.totalCost ?? 0).toFixed(0)}<br/>
            批次: ${p.batchCount}<br/>
            实际产出: ${(p.totalActualQty ?? 0).toFixed(1)} kg<br/>
            出成率: ${p.avgYieldRate?.toFixed(1) ?? '—'}%`;
        },
      },
      grid: { top: 20, right: 20, bottom: 70, left: 70 },
      xAxis: {
        type: 'category',
        data: labels,
        axisLabel: { rotate: 30, fontSize: 11, overflow: 'truncate', width: 80 },
      },
      yAxis: { type: 'value', name: '总成本(¥)' },
      series: [{
        type: 'bar',
        data: prods.map(p => p.totalCost ?? 0),
        barMaxWidth: 50,
        itemStyle: {
          color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [{ offset: 0, color: '#667eea' }, { offset: 1, color: '#764ba2' }] },
        },
      }],
    });
  } else {
    // Non-price role: show yield rate only (not sensitive)
    productBarChart.setOption({
      tooltip: { trigger: 'axis', confine: true, formatter: '{b}<br/>出成率: {c}%' },
      grid: { top: 20, right: 20, bottom: 70, left: 60 },
      xAxis: {
        type: 'category',
        data: labels,
        axisLabel: { rotate: 30, fontSize: 11, overflow: 'truncate', width: 80 },
      },
      yAxis: { type: 'value', name: '出成率(%)', min: 0, max: 100 },
      series: [{
        type: 'bar',
        data: prods.map(p => p.avgYieldRate ?? 0),
        barMaxWidth: 50,
        itemStyle: { color: '#5470c6' },
      }],
    });
  }
}

function renderAll() {
  if (renderTimer) clearTimeout(renderTimer);
  renderTimer = setTimeout(() => {
    renderCostPie();
    renderCostTrend();
    renderYieldTrend();
    renderProductBar();
  }, 80);
}

// ── Data loading ──────────────────────────────────────────────────────────────

async function loadData() {
  const fid = factoryId.value;
  if (!fid) {
    loadError.value = '无法获取工厂 ID，请重新登录';
    return;
  }
  loading.value = true;
  loadError.value = '';
  const { startDate, endDate } = getQueryDates();

  try {
    const [costRes, yieldRes, productRes] = await Promise.all([
      getFactoryCostStructure({ factoryId: fid, startDate, endDate }),
      getFactoryYieldTrend({ factoryId: fid, startDate, endDate }),
      getFactoryProductCostCompare({ factoryId: fid, startDate, endDate }),
    ]);

    if (costRes.success) {
      costData.value = costRes.data;
    } else {
      console.warn('[FactoryCostAnalysis] cost-structure error:', costRes.message);
    }

    if (yieldRes.success) {
      yieldData.value = yieldRes.data;
    } else {
      console.warn('[FactoryCostAnalysis] yield-trend error:', yieldRes.message);
    }

    if (productRes.success) {
      productData.value = productRes.data;
    } else {
      console.warn('[FactoryCostAnalysis] product-cost-compare error:', productRes.message);
    }

    if (!costRes.success && !yieldRes.success && !productRes.success) {
      ElMessage.warning(costRes.message || '加载失败');
    }

    renderAll();
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '加载成本数据失败';
    loadError.value = msg;
    ElMessage.error(msg);
  } finally {
    loading.value = false;
  }
}

function onDateChange() {
  loadData();
}

// ── Summary KPI display ───────────────────────────────────────────────────────

const summaryKpis = computed(() => {
  const s = costData.value?.summary ?? {};
  const rows = costData.value?.rows ?? [];
  const batchCount = 'batchCount' in s ? (s as { batchCount: number }).batchCount : 0;
  const totalActualQty = 'totalActualQty' in s ? (s as { totalActualQty: number | null }).totalActualQty : null;
  const avgYield = rows.length
    ? rows.reduce((sum, r) => sum + (r.avgYieldRate ?? 0), 0) / rows.filter(r => r.avgYieldRate !== null).length
    : null;

  return [
    { label: '生产批次', value: batchCount, unit: '批', color: 'purple', showAlways: true },
    { label: '实际产出', value: totalActualQty, unit: 'kg', color: 'blue', showAlways: true },
    { label: '平均出成率', value: avgYield != null ? Number(avgYield.toFixed(1)) : null, unit: '%', color: 'green', showAlways: true },
  ];
});

const gradientStyles: Record<string, { bg: string; color: string }> = {
  purple: { bg: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)', color: '#fff' },
  blue:   { bg: 'linear-gradient(135deg, #4facfe 0%, #00f2fe 100%)', color: '#fff' },
  green:  { bg: 'linear-gradient(135deg, #43e97b 0%, #38f9d7 100%)', color: '#fff' },
  orange: { bg: 'linear-gradient(135deg, #fa8231 0%, #f7b731 100%)', color: '#fff' },
};

// ── Chart Insight wiring (useChartInsight per-chart) ──────────────────────────

const costInsightPerms = computed(() => ({ canViewFinance: canViewPrice.value }));

// 1. 成本结构饼图 (PIE PROPORTION, yMetric:cost)
const { insight: costPieInsight, loading: costPieInsightLoading } = useChartInsight(
  (): { chart: ChartWithMeta } | null => {
    if (!hasCostPie.value) return null;
    return {
      chart: {
        chartType: 'pie',
        title: '成本结构占比',
        meta: { xDim: 'category', yMetric: 'cost', aggregation: 'sum', domain: 'factory' },
        config: {
          series: [{ type: 'pie', data: costPieSlices.value }],
        },
      },
    };
  },
  () => costInsightPerms.value,
  { factoryId: () => factoryId.value, autoTier2: true },
);

// 2. 成本趋势堆叠柱图 (BAR TREND, yMetric:cost)
const { insight: costTrendInsight, loading: costTrendInsightLoading } = useChartInsight(
  (): { chart: ChartWithMeta } | null => {
    const rows = costData.value?.rows;
    if (!rows || rows.length < 2 || !canViewPrice.value) return null;
    return {
      chart: {
        chartType: 'BAR',
        title: '每日成本趋势',
        meta: { xDim: 'time', yMetric: 'cost', aggregation: 'sum', domain: 'factory' },
        config: {
          xAxis: { data: rows.map(r => r.statDate.slice(5)) },
          series: [{ type: 'bar', data: rows.map(r => r.totalCost ?? 0) }],
        },
      },
    };
  },
  () => costInsightPerms.value,
  { factoryId: () => factoryId.value, autoTier2: true },
);

// 3. 出成率趋势折线 (LINE TREND, yMetric:pct)
const { insight: yieldInsight, loading: yieldInsightLoading } = useChartInsight(
  (): { chart: ChartWithMeta } | null => {
    const points = yieldData.value?.trend;
    if (!points || points.length < 4) return null;
    return {
      chart: {
        chartType: 'LINE',
        title: '出成率趋势',
        meta: { xDim: 'time', yMetric: 'pct', aggregation: 'avg', domain: 'factory' },
        config: {
          xAxis: { data: points.map(p => p.statDate.slice(5)) },
          series: [{ type: 'line', data: points.map(p => p.avgYieldRate ?? 0) }],
        },
      },
    };
  },
  () => ({ canViewFinance: false }), // yield is not price-sensitive
  { factoryId: () => factoryId.value, autoTier2: true },
);

// 4. 产品成本对比柱图 (BAR RANKING, yMetric:cost or pct depending on RBAC)
const { insight: productInsight, loading: productInsightLoading } = useChartInsight(
  (): { chart: ChartWithMeta } | null => {
    const prods = productData.value?.products;
    if (!prods || prods.length < 2) return null;
    const top = prods.slice(0, 10);
    if (canViewPrice.value) {
      return {
        chart: {
          chartType: 'BAR',
          title: '产品总成本对比',
          meta: { xDim: 'product', yMetric: 'cost', aggregation: 'sum', domain: 'factory' },
          config: {
            xAxis: { data: top.map(p => p.productTypeId) },
            series: [{ type: 'bar', data: top.map(p => p.totalCost ?? 0) }],
          },
        },
      };
    }
    // Non-price role: expose yield rate comparison instead
    return {
      chart: {
        chartType: 'BAR',
        title: '产品出成率对比',
        meta: { xDim: 'product', yMetric: 'pct', aggregation: 'avg', domain: 'factory' },
        config: {
          xAxis: { data: top.map(p => p.productTypeId) },
          series: [{ type: 'bar', data: top.map(p => p.avgYieldRate ?? 0) }],
        },
      },
    };
  },
  () => costInsightPerms.value,
  { factoryId: () => factoryId.value, autoTier2: true },
);

// ── Lifecycle ─────────────────────────────────────────────────────────────────

let resizeRaf = 0;
let resizeObserver: ResizeObserver | null = null;

function handleResize() {
  if (resizeRaf) return;
  resizeRaf = requestAnimationFrame(() => {
    costPieChart?.resize();
    costTrendChart?.resize();
    yieldTrendChart?.resize();
    productBarChart?.resize();
    resizeRaf = 0;
  });
}

onMounted(() => {
  loadData();
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(handleResize);
    document.querySelectorAll('.fca-chart-body').forEach(el => resizeObserver!.observe(el));
  }
  window.addEventListener('resize', handleResize);
});

onBeforeUnmount(() => {
  if (resizeRaf) cancelAnimationFrame(resizeRaf);
  resizeObserver?.disconnect();
  if (renderTimer) clearTimeout(renderTimer);
  window.removeEventListener('resize', handleResize);
  costPieChart?.dispose();
  costTrendChart?.dispose();
  yieldTrendChart?.dispose();
  productBarChart?.dispose();
});
</script>

<template>
  <div class="fca-root" v-loading="loading">
    <!-- ── 顶部工具栏 ─────────────────────────────────────── -->
    <div class="fca-toolbar">
      <h2 class="fca-title">生产成本分析</h2>
      <div class="fca-toolbar-actions">
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
        <el-button :icon="Refresh" circle @click="loadData" />
      </div>
    </div>

    <!-- ── 加载错误 ───────────────────────────────────────── -->
    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      :closable="false"
      class="fca-error"
    />

    <!-- ── 全局空状态: 无任何 gold 数据 ────────────────────── -->
    <template v-if="!loading && !loadError && !hasAnyData">
      <div class="fca-empty-state">
        <el-icon class="fca-empty-icon"><i class="fca-empty-icon-char">📊</i></el-icon>
        <div class="fca-empty-title">该工厂暂无生产成本数据</div>
        <div class="fca-empty-desc">
          报工时填写材料、人工成本后，生产成本分析将在此处展示。
        </div>
      </div>
    </template>

    <!-- ── 主体内容 ───────────────────────────────────────── -->
    <template v-else-if="hasAnyData || loading">
      <!-- KPI 摘要行 -->
      <div class="fca-kpi-row" v-if="hasAnyCostRows">
        <div
          v-for="kpi in summaryKpis"
          :key="kpi.label"
          class="fca-kpi-card"
          :style="{ background: gradientStyles[kpi.color]?.bg, color: gradientStyles[kpi.color]?.color }"
        >
          <div class="fca-kpi-label">{{ kpi.label }}</div>
          <div class="fca-kpi-value">
            {{ kpi.value != null ? kpi.value.toLocaleString() : '—' }}
            <span class="fca-kpi-unit">{{ kpi.unit }}</span>
          </div>
        </div>
      </div>

      <!-- ── 成本块 (仅 canViewPrice) ─────────────────────── -->
      <div v-if="canViewPrice" class="fca-section">
        <div class="fca-section-title">成本结构</div>

        <!-- 无成本数据空状态 -->
        <div v-if="!hasAnyCostRows && !loading" class="fca-inline-empty">
          <el-empty
            description="该时间范围暂无成本数据，请在报工时填写材料/人工成本后再来查看"
            :image-size="80"
          />
        </div>

        <div v-else class="fca-charts-row">
          <!-- 成本饼图 -->
          <div class="fca-chart-card fca-chart-half">
            <div class="fca-chart-title">成本结构占比</div>
            <div v-show="hasCostPie" ref="costPieRef" class="fca-chart-body" />
            <el-empty
              v-if="!hasCostPie && !loading"
              description="暂无成本拆分数据（可能报工时未填写各项成本）"
              :image-size="60"
              class="fca-chart-empty"
            />
            <ChartInsight
              :insight="costPieInsight"
              :loading="costPieInsightLoading"
              depth="detailed"
            />
          </div>

          <!-- 每日成本趋势堆叠柱 -->
          <div class="fca-chart-card fca-chart-half">
            <div class="fca-chart-title">每日成本趋势（堆叠）</div>
            <div v-show="hasAnyCostRows" ref="costTrendRef" class="fca-chart-body" />
            <el-empty
              v-if="!hasAnyCostRows && !loading"
              description="暂无成本数据"
              :image-size="60"
              class="fca-chart-empty"
            />
            <ChartInsight
              :insight="costTrendInsight"
              :loading="costTrendInsightLoading"
              depth="detailed"
            />
          </div>
        </div>
      </div>

      <!-- RBAC 遮挡：无价格权限 -->
      <div v-else class="fca-rbac-mask">
        <el-icon><i class="el-icon--lock">🔒</i></el-icon>
        <span>成本数据仅工厂管理员/超级管理员可见，如需查看请联系管理员开通权限</span>
      </div>

      <!-- ── 出成率趋势 (无 RBAC, 所有角色可见) ────────────── -->
      <div class="fca-section">
        <div class="fca-section-title">出成率趋势</div>
        <div class="fca-chart-card fca-chart-full">
          <div class="fca-chart-title">出成率趋势（日度）</div>
          <div v-show="hasYieldTrend" ref="yieldTrendRef" class="fca-chart-body" />
          <el-empty
            v-if="!hasYieldTrend && !loading"
            :image-size="80"
            class="fca-chart-empty"
          >
            <template #description>
              <span>该时间范围暂无出成率数据<br/>（需有 ≥ 2 天的报工记录且记录了实际产出量）</span>
            </template>
          </el-empty>
          <ChartInsight
            :insight="yieldInsight"
            :loading="yieldInsightLoading"
            depth="detailed"
          />
        </div>
      </div>

      <!-- ── 产品成本对比 ───────────────────────────────────── -->
      <div class="fca-section">
        <div class="fca-section-title">
          {{ canViewPrice ? '产品成本对比' : '产品出成率对比' }}
        </div>
        <div class="fca-chart-card fca-chart-full">
          <div class="fca-chart-title">
            {{ canViewPrice ? '各产品总成本 (Top 10)' : '各产品出成率 (Top 10)' }}
          </div>
          <div v-show="hasProducts" ref="productBarRef" class="fca-chart-body" />
          <el-empty
            v-if="!hasProducts && !loading"
            description="暂无产品成本对比数据"
            :image-size="80"
            class="fca-chart-empty"
          />
          <ChartInsight
            :insight="productInsight"
            :loading="productInsightLoading"
            depth="detailed"
          />
        </div>
      </div>

      <!-- ── 产品明细表格 (canViewPrice 或 仅出成率) ─────────── -->
      <div class="fca-section fca-detail-section" v-if="hasProducts">
        <div class="fca-section-title">产品明细</div>
        <el-table :data="productData!.products" stripe border size="small" empty-text="暂无数据">
          <el-table-column prop="productTypeId" label="产品" min-width="140" />
          <el-table-column prop="batchCount" label="批次数" width="80" align="right" />
          <el-table-column label="实际产出(kg)" width="110" align="right">
            <template #default="{ row }">
              {{ row.totalActualQty != null ? row.totalActualQty.toFixed(1) : '—' }}
            </template>
          </el-table-column>
          <el-table-column label="出成率" width="90" align="center">
            <template #default="{ row }">
              {{ row.avgYieldRate != null ? row.avgYieldRate.toFixed(1) + '%' : '—' }}
            </template>
          </el-table-column>
          <!-- 成本列仅 canViewPrice 可见 -->
          <template v-if="canViewPrice">
            <el-table-column label="材料成本(¥)" width="110" align="right">
              <template #default="{ row }">
                {{ row.totalMaterialCost != null ? row.totalMaterialCost.toFixed(0) : '—' }}
              </template>
            </el-table-column>
            <el-table-column label="人工成本(¥)" width="110" align="right">
              <template #default="{ row }">
                {{ row.totalLaborCost != null ? row.totalLaborCost.toFixed(0) : '—' }}
              </template>
            </el-table-column>
            <el-table-column label="总成本(¥)" width="100" align="right">
              <template #default="{ row }">
                {{ row.totalCost != null ? row.totalCost.toFixed(0) : '—' }}
              </template>
            </el-table-column>
            <el-table-column label="单位成本(¥/kg)" width="120" align="right">
              <template #default="{ row }">
                {{ row.costPerUnit != null ? row.costPerUnit.toFixed(2) : '—' }}
              </template>
            </el-table-column>
          </template>
        </el-table>
      </div>
    </template>
  </div>
</template>

<style scoped>
.fca-root {
  padding: 20px;
  min-height: calc(100vh - 64px);
  background: #f5f7fa;
}

/* ── Toolbar ─────────────────────────────────── */
.fca-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.fca-title {
  font-size: 20px;
  font-weight: 600;
  color: #1d2129;
  margin: 0;
}

.fca-toolbar-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.fca-error {
  margin-bottom: 16px;
}

/* ── Empty state ─────────────────────────────── */
.fca-empty-state {
  text-align: center;
  padding: 80px 24px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.fca-empty-icon {
  font-size: 56px;
  margin-bottom: 12px;
}

.fca-empty-icon-char {
  font-style: normal;
  font-size: 56px;
}

.fca-empty-title {
  font-size: 18px;
  font-weight: 600;
  color: #1d2129;
  margin-bottom: 8px;
}

.fca-empty-desc {
  font-size: 14px;
  color: #86909c;
  line-height: 1.7;
}

/* ── KPI row ─────────────────────────────────── */
.fca-kpi-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.fca-kpi-card {
  border-radius: 12px;
  padding: 20px;
  position: relative;
  overflow: hidden;
}

.fca-kpi-label {
  font-size: 13px;
  opacity: 0.85;
  margin-bottom: 8px;
}

.fca-kpi-value {
  font-size: 28px;
  font-weight: 700;
  line-height: 1.2;
}

.fca-kpi-unit {
  font-size: 14px;
  font-weight: 400;
  margin-left: 4px;
  opacity: 0.8;
}

/* ── Sections ────────────────────────────────── */
.fca-section {
  margin-bottom: 20px;
}

.fca-section-title {
  font-size: 16px;
  font-weight: 600;
  color: #1d2129;
  margin-bottom: 12px;
  padding-left: 12px;
  border-left: 3px solid #5470c6;
}

.fca-inline-empty {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

/* ── Charts ──────────────────────────────────── */
.fca-charts-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.fca-chart-card {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.fca-chart-half {
  /* inside .fca-charts-row grid — inherits 50% */
}

.fca-chart-full {
  width: 100%;
}

.fca-chart-title {
  font-size: 14px;
  font-weight: 600;
  color: #1d2129;
  margin-bottom: 12px;
}

.fca-chart-body {
  height: 300px;
}

.fca-chart-empty {
  height: 300px;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* ── RBAC mask ───────────────────────────────── */
.fca-rbac-mask {
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  margin-bottom: 20px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  display: flex;
  align-items: center;
  gap: 10px;
  color: #86909c;
  font-size: 14px;
  border-left: 4px solid #f59e0b;
}

/* ── Detail table ────────────────────────────── */
.fca-detail-section {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

/* ── Responsive ──────────────────────────────── */
@media (max-width: 1200px) {
  .fca-kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }
  .fca-charts-row {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .fca-kpi-row {
    grid-template-columns: 1fr;
  }
  .fca-toolbar {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
}
</style>
