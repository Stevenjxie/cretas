<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import { getEfficiencyDashboard, type KPIItem, type EfficiencyDashboard } from '@/api/productionAnalytics';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import type { TableRow } from '@/types/api';
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue';
import { useChartInsight } from '@/composables/useChartInsight';
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

// ==================== 预算 vs 实际 ====================
const activeMainTab = ref('efficiency');
const budgetLoading = ref(false);
const budgetData = ref<TableRow[]>([]);

async function loadBudgetVsActual() {
  if (!factoryId.value) return;
  budgetLoading.value = true;
  try {
    const res = await get(`/${factoryId.value}/production-analytics/budget-vs-actual`);
    if (res.success && res.data) {
      const raw = Array.isArray(res.data) ? res.data : res.data.content || [];
      // 映射后端字段到前端表格列
      budgetData.value = raw.map((item: TableRow) => ({
        ...item,
        period: item.planNumber || '-',
        budgetCost: item.estimatedLaborCost != null && item.estimatedMaterialCost != null
          ? Number(item.estimatedLaborCost) + Number(item.estimatedMaterialCost) + Number(item.estimatedEquipmentCost || 0) + Number(item.estimatedOtherCost || 0)
          : item.estimatedLaborCost,
        actualCost: item.actualLaborCost != null && item.actualMaterialCost != null
          ? Number(item.actualLaborCost) + Number(item.actualMaterialCost) + Number(item.actualEquipmentCost || 0) + Number(item.actualOtherCost || 0)
          : item.actualLaborCost,
      }));
    } else {
      ElMessage.warning(res.message || '加载预算数据失败');
    }
  } catch (e: unknown) {
    if (!(e as { actionHint?: unknown })?.actionHint) ElMessage.error('加载预算 vs 实际数据失败');
    console.error(e);
  } finally {
    budgetLoading.value = false;
  }
}

function onMainTabChange(tab: string) {
  if (tab === 'budget' && budgetData.value.length === 0) {
    loadBudgetVsActual();
  }
}

// ==================== 状态 ====================

const loading = ref(false);
const dashboard = ref<EfficiencyDashboard | null>(null);

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
    const res = await getEfficiencyDashboard({ startDate: fmt(start), endDate: fmt(end) });
    if (res.success && res.data) {
      dashboard.value = res.data;
      renderCharts();
    } else {
      ElMessage.warning(res.message || '加载失败');
    }
  } catch (e: unknown) {
    if (!(e as { actionHint?: unknown })?.actionHint) ElMessage.error('加载人效分析数据失败');
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
  if (kpi.changeType === 'up') return kpi.key === 'defect_rate' ? '#F56C6C' : '#67C23A';
  if (kpi.changeType === 'down') return kpi.key === 'defect_rate' ? '#67C23A' : '#F56C6C';
  return '#d9d9d9';
}

function formatKPIValue(kpi: KPIItem) {
  if (kpi.value >= 10000) return (kpi.value / 10000).toFixed(1) + '万';
  if (Number.isInteger(kpi.value)) return kpi.value.toString();
  return kpi.value.toFixed(1);
}

// ==================== 图表 ====================

const trendChartRef = ref<HTMLDivElement>();
const rankingChartRef = ref<HTMLDivElement>();
const hoursChartRef = ref<HTMLDivElement>();
const heatmapChartRef = ref<HTMLDivElement>();

let trendChart: echarts.ECharts | null = null;
let rankingChart: echarts.ECharts | null = null;
let hoursChart: echarts.ECharts | null = null;
let heatmapChart: echarts.ECharts | null = null;
let renderTimer: ReturnType<typeof setTimeout> | null = null;

function renderCharts() {
  if (!dashboard.value) return;
  if (renderTimer) clearTimeout(renderTimer);
  renderTimer = setTimeout(() => {
    renderTrendChart();
    renderRankingChart();
    renderHoursChart();
    renderHeatmapChart();
  }, 100);
}

function renderTrendChart() {
  if (!trendChartRef.value || !dashboard.value || !hasEffTrendData.value) return;
  trendChart?.dispose();
  trendChart = echarts.init(trendChartRef.value);
  const data = dashboard.value.dailyTrend;
  const dates = data.map(d => String(d.date).slice(5));
  trendChart.setOption({
    tooltip: { trigger: 'axis', confine: true },
    grid: { top: 30, right: 20, bottom: 30, left: 60 },
    xAxis: { type: 'category', data: dates },
    yAxis: { type: 'value', name: '产出/小时' },
    series: [{
      name: '人效', type: 'line', data: data.map(d => d.efficiency), smooth: true,
      itemStyle: { color: '#667eea' },
      areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: 'rgba(102,126,234,0.3)' }, { offset: 1, color: 'rgba(102,126,234,0.02)' }] } },
      markLine: { data: [{ type: 'average', name: '均值', lineStyle: { color: '#67C23A', type: 'dashed' }, label: { formatter: '均值 {c}' } }], silent: true },
    }],
  });
}

function renderRankingChart() {
  if (!rankingChartRef.value || !dashboard.value || !hasRankingData.value) return;
  rankingChart?.dispose();
  rankingChart = echarts.init(rankingChartRef.value);
  const data = dashboard.value.workerRanking.slice(0, 15).reverse();
  const names = data.map(d => String(d.worker_name || '未知'));
  const values = data.map(d => Number(d.efficiency) || 0);
  const maxVal = Math.max(...values, 1);
  rankingChart.setOption({
    tooltip: { trigger: 'axis', confine: true, axisPointer: { type: 'shadow' }, formatter: (params: unknown) => {
      const p = (params as { name: string; value: number }[])[0];
      return `${p.name}: ${p.value} 产出/时`;
    }},
    grid: { top: 10, right: 30, bottom: 10, left: 90 },
    xAxis: { type: 'value', name: '产出/时' },
    yAxis: { type: 'category', data: names, axisLabel: { fontSize: 11 } },
    series: [{
      type: 'bar',
      data: values.map(v => ({
        value: v,
        itemStyle: {
          color: v >= maxVal * 0.8 ? '#67C23A' : v >= maxVal * 0.5 ? '#E6A23C' : '#F56C6C',
        },
      })),
      barMaxWidth: 20,
      label: { show: true, position: 'right', fontSize: 11, formatter: '{c}' },
    }],
  });
}

function renderHoursChart() {
  if (!hoursChartRef.value || !dashboard.value || !hasHoursData.value) return;
  hoursChart?.dispose();
  hoursChart = echarts.init(hoursChartRef.value);
  const data = dashboard.value.hoursByProduct;
  hoursChart.setOption({
    tooltip: { trigger: 'axis', confine: true },
    grid: { top: 20, right: 20, bottom: 60, left: 60 },
    xAxis: { type: 'category', data: data.map(d => d.product_name), axisLabel: { rotate: 30, fontSize: 11 } },
    yAxis: { type: 'value', name: '工时(分钟)' },
    series: [{
      type: 'bar', data: data.map(d => d.total_minutes), barMaxWidth: 40,
      itemStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: '#f093fb' }, { offset: 1, color: '#f5576c' }] } },
    }],
  });
}

function renderHeatmapChart() {
  if (!heatmapChartRef.value || !dashboard.value || !hasHeatmapData.value) return;
  heatmapChart?.dispose();
  heatmapChart = echarts.init(heatmapChartRef.value);
  const raw = dashboard.value.workerProcessCross;

  // 构建热力图数据
  const workers = [...new Set(raw.map(d => String(d.worker_name)))];
  const processes = [...new Set(raw.map(d => String(d.process_category)))];
  const heatData: [number, number, number][] = [];
  let maxOutput = 0;

  for (const row of raw) {
    const wi = workers.indexOf(String(row.worker_name));
    const pi = processes.indexOf(String(row.process_category));
    const val = Number(row.output) || 0;
    if (wi >= 0 && pi >= 0) {
      heatData.push([pi, wi, val]);
      maxOutput = Math.max(maxOutput, val);
    }
  }

  heatmapChart.setOption({
    tooltip: { position: 'top', confine: true, formatter: (params: unknown) => {
      const p = params as { value: [number, number, number] };
      return `${workers[p.value[1]]} × ${processes[p.value[0]]}: ${p.value[2]}`;
    }},
    grid: { top: 10, right: 20, bottom: 60, left: 90 },
    xAxis: { type: 'category', data: processes, axisLabel: { rotate: 30, fontSize: 11 }, splitArea: { show: true } },
    yAxis: { type: 'category', data: workers, axisLabel: { fontSize: 11 }, splitArea: { show: true } },
    visualMap: { min: 0, max: maxOutput || 100, calculable: true, orient: 'horizontal', left: 'center', bottom: 0, inRange: { color: ['#f0f5ff', '#667eea', '#764ba2'] } },
    series: [{
      type: 'heatmap', data: heatData,
      label: { show: heatData.length <= 50, fontSize: 10 },
      emphasis: { itemStyle: { shadowBlur: 10, shadowColor: 'rgba(0,0,0,0.3)' } },
    }],
  });
}

// ==================== 排名表格 ====================

// chart audit P1-4: per-chart hasData computeds (mirror P1-3 in
// ProductionAnalysis.vue). Empty echarts skeleton is replaced by
// <el-empty> "该期暂无XX数据" so users distinguish "no data" from "broken".

const hasEffTrendData = computed(() => {
  const data = dashboard.value?.dailyTrend ?? [];
  return data.some(d => Number(d.efficiency) > 0);
});

const hasRankingData = computed(() => {
  const data = dashboard.value?.workerRanking ?? [];
  return data.some(d => Number(d.efficiency) > 0);
});

const hasHoursData = computed(() => {
  const data = dashboard.value?.hoursByProduct ?? [];
  return data.some(d => Number(d.total_minutes) > 0);
});

const hasHeatmapData = computed(() => {
  const data = dashboard.value?.workerProcessCross ?? [];
  return data.length > 0;
});

const rankingTable = computed(() => {
  if (!dashboard.value) return [];
  return dashboard.value.workerRanking.map((d, i) => ({
    rank: i + 1,
    workerName: d.worker_name,
    totalOutput: d.total_output,
    totalHours: d.total_hours,
    efficiency: d.efficiency,
    yieldRate: d.yield_rate,
    workDays: d.work_days,
  }));
});

// ==================== Chart Insight ====================
// LINE: 人效趋势 (trendChart) — xDim:'time', yMetric:'quantity' (efficiency units/hour), TREND
// BAR:  员工排名  (rankingChart) — xDim:'category', yMetric:'quantity', RANKING
// BAR:  产品工时  (hoursChart)  — xDim:'category', yMetric:'quantity' (minutes), RANKING
// HEATMAP (heatmapChart) — SKIP: exotic chart type, no insight family available.

const effInsightPerms = () => ({ canViewFinance: false }); // no finance metrics in efficiency view

const { insight: effTrendInsight, loading: effTrendInsightLoading } = useChartInsight(
  (): { chart: ChartWithMeta } | null => {
    const data = dashboard.value?.dailyTrend;
    if (!data || data.length < 4) return null;
    return {
      chart: {
        chartType: 'LINE',
        title: '人效趋势',
        meta: { xDim: 'time', yMetric: 'quantity', aggregation: 'avg', domain: 'factory' },
        config: {
          xAxis: { data: data.map(d => String(d.date).slice(5)) },
          series: [{ type: 'line', data: data.map(d => d.efficiency) }],
        },
      },
    };
  },
  effInsightPerms,
  { factoryId: () => factoryId.value ?? '', autoTier2: true },
);

const { insight: effRankingInsight, loading: effRankingInsightLoading } = useChartInsight(
  (): { chart: ChartWithMeta } | null => {
    const data = dashboard.value?.workerRanking;
    if (!data || data.length < 2) return null;
    const top15 = data.slice(0, 15);
    return {
      chart: {
        chartType: 'BAR',
        title: '员工人效排名',
        meta: { xDim: 'category', yMetric: 'quantity', aggregation: 'avg', domain: 'factory' },
        config: {
          xAxis: { data: top15.map(d => String(d.worker_name || '未知')) },
          series: [{ type: 'bar', data: top15.map(d => Number(d.efficiency) || 0) }],
        },
      },
    };
  },
  effInsightPerms,
  { factoryId: () => factoryId.value ?? '', autoTier2: true },
);

const { insight: effHoursInsight, loading: effHoursInsightLoading } = useChartInsight(
  (): { chart: ChartWithMeta } | null => {
    const data = dashboard.value?.hoursByProduct;
    if (!data || data.length < 2) return null;
    return {
      chart: {
        chartType: 'BAR',
        title: '产品工时分布',
        meta: { xDim: 'category', yMetric: 'quantity', aggregation: 'sum', domain: 'factory' },
        config: {
          xAxis: { data: data.map(d => d.product_name) },
          series: [{ type: 'bar', data: data.map(d => d.total_minutes) }],
        },
      },
    };
  },
  effInsightPerms,
  { factoryId: () => factoryId.value ?? '', autoTier2: true },
);

// ==================== 生命周期 ====================

let resizeRaf = 0;
function handleResize() {
  if (resizeRaf) return;
  resizeRaf = requestAnimationFrame(() => {
    trendChart?.resize();
    rankingChart?.resize();
    hoursChart?.resize();
    heatmapChart?.resize();
    resizeRaf = 0;
  });
}

let resizeObserver: ResizeObserver | null = null;

onMounted(() => {
  loadData();
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(handleResize);
    document.querySelectorAll('.chart').forEach(el => resizeObserver!.observe(el));
  }
  window.addEventListener('resize', handleResize);
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  if (renderTimer) clearTimeout(renderTimer);
  window.removeEventListener('resize', handleResize);
  trendChart?.dispose();
  rankingChart?.dispose();
  hoursChart?.dispose();
  heatmapChart?.dispose();
});
</script>

<template>
  <div class="efficiency-analysis">
    <el-tabs v-model="activeMainTab" @tab-change="onMainTabChange" style="margin-bottom: 0;">
      <el-tab-pane label="人效分析" name="efficiency">
        <div v-loading="loading">
    <!-- 顶部控件 -->
    <div class="toolbar">
      <h2 class="page-title">人效分析</h2>
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
        <div class="chart-title">人效趋势</div>
        <div v-show="hasEffTrendData" ref="trendChartRef" class="chart-body" />
        <el-empty v-if="!hasEffTrendData" description="该期暂无人效数据" :image-size="80" class="chart-empty" />
        <!-- Insight: LINE TREND (factory, xDim:time, yMetric:quantity/efficiency) -->
        <ChartInsight :insight="effTrendInsight" :loading="effTrendInsightLoading" depth="detailed" />
      </div>
      <div class="chart-card">
        <div class="chart-title">员工人效排名 (Top 15)</div>
        <div v-show="hasRankingData" ref="rankingChartRef" class="chart-body" />
        <el-empty v-if="!hasRankingData" description="该期暂无员工排名数据" :image-size="80" class="chart-empty" />
        <!-- Insight: BAR RANKING (factory, xDim:category, yMetric:quantity) -->
        <ChartInsight :insight="effRankingInsight" :loading="effRankingInsightLoading" depth="detailed" />
      </div>
      <div class="chart-card">
        <div class="chart-title">产品工时分布</div>
        <div v-show="hasHoursData" ref="hoursChartRef" class="chart-body" />
        <el-empty v-if="!hasHoursData" description="该期暂无工时数据" :image-size="80" class="chart-empty" />
        <!-- Insight: BAR RANKING (factory, xDim:category, yMetric:quantity/minutes) -->
        <ChartInsight :insight="effHoursInsight" :loading="effHoursInsightLoading" depth="detailed" />
      </div>
      <div class="chart-card">
        <div class="chart-title">员工 × 工序 热力图</div>
        <div v-show="hasHeatmapData" ref="heatmapChartRef" class="chart-body" />
        <el-empty v-if="!hasHeatmapData" description="该期暂无员工×工序数据" :image-size="80" class="chart-empty" />
        <!-- Insight: HEATMAP — SKIPPED (exotic chart type, no insight family) -->
      </div>
    </div>

    <!-- 排名表格 -->
    <div class="detail-section" v-if="rankingTable.length">
      <h3>员工人效排名</h3>
      <el-table empty-text="暂无数据" :data="rankingTable" stripe border size="small" style="width: 100%">
        <el-table-column prop="rank" label="排名" width="60" align="center" />
        <el-table-column prop="workerName" label="员工姓名" min-width="120" />
        <el-table-column prop="totalOutput" label="总产出" width="100" align="right" />
        <el-table-column prop="totalHours" label="总工时(h)" width="100" align="right" />
        <el-table-column prop="efficiency" label="人效(产出/h)" width="120" align="right">
          <template #default="{ row }">
            <span :style="{ color: row.efficiency >= 10 ? '#67C23A' : row.efficiency >= 5 ? '#E6A23C' : '#F56C6C', fontWeight: 600 }">
              {{ row.efficiency }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="yieldRate" label="良率(%)" width="90" align="center">
          <template #default="{ row }">{{ row.yieldRate }}%</template>
        </el-table-column>
        <el-table-column prop="workDays" label="出勤天数" width="90" align="center" />
      </el-table>
    </div>

    <!-- 空状态 -->
    <el-empty v-if="!loading && !dashboard?.kpis?.length" description="暂无人效数据，请先提交生产报工和工时记录" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="预算 vs 实际" name="budget">
        <div v-loading="budgetLoading" style="padding: 20px;">
          <div style="display: flex; justify-content: flex-end; margin-bottom: 16px;">
            <el-button :icon="Refresh" @click="loadBudgetVsActual">刷新</el-button>
          </div>
          <el-table
            :data="budgetData"
            empty-text="暂无预算对比数据"
            stripe
            border
            style="width: 100%"
          >
            <el-table-column prop="period" label="期间" width="120" />
            <el-table-column prop="productName" label="产品" min-width="150" show-overflow-tooltip />
            <el-table-column prop="budgetQuantity" label="预算数量" width="110" align="right" />
            <el-table-column prop="actualQuantity" label="实际数量" width="110" align="right" />
            <el-table-column prop="budgetCost" label="预算成本" width="120" align="right">
              <template #default="{ row }">
                {{ row.budgetCost != null ? Number(row.budgetCost).toFixed(2) : '-' }}
              </template>
            </el-table-column>
            <el-table-column prop="actualCost" label="实际成本" width="120" align="right">
              <template #default="{ row }">
                {{ row.actualCost != null ? Number(row.actualCost).toFixed(2) : '-' }}
              </template>
            </el-table-column>
            <el-table-column label="成本差异" width="120" align="right">
              <template #default="{ row }">
                <span
                  v-if="row.budgetCost != null && row.actualCost != null"
                  :style="{ color: row.actualCost > row.budgetCost ? '#F56C6C' : '#67C23A', fontWeight: 600 }"
                >
                  {{ (row.actualCost - row.budgetCost).toFixed(2) }}
                </span>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column label="达成率" width="100" align="center">
              <template #default="{ row }">
                <span v-if="row.budgetQuantity > 0">
                  {{ ((row.actualQuantity / row.budgetQuantity) * 100).toFixed(1) }}%
                </span>
                <span v-else>-</span>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!budgetLoading && budgetData.length === 0" description="暂无预算对比数据" />
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.efficiency-analysis {
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
