<script setup lang="ts">
/**
 * What-If Pricing Simulator
 * Interactive tool for simulating revenue/profit impact of price, cost, and traffic changes.
 * Features: slider controls, triple-line chart, sensitivity heatmap, scenario comparison.
 */
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage } from 'element-plus';
import { TrendCharts, DataAnalysis, Warning, InfoFilled } from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import { useChartResize } from '@/composables/useChartResize';
import { formatNumber } from '@/utils/format-number';
import {
  getUploadHistory,
  type UploadHistoryItem,
} from '@/api/smartbi/upload';
import {
  simulateWhatIf,
  type WhatIfScenarioResult,
  type WhatIfCostStructure,
  type WhatIfSensitivityCell,
  type WhatIfComparisonChart,
} from '@/api/smartbi/python-service';
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight';
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue';
import { useChartInsight } from '@/composables/useChartInsight';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
// canViewPrice: PR #520 pattern — entire simulator renders revenue/cost/profit;
// hide for non-PRICE_VIEW_ROLES (e.g. viewer) to avoid misleading empty UI.
// Defense-in-depth: outer <template v-if> wrapper (this PR) + inner per-cell v-if (#598).
const canViewPrice = computed(() => permissionStore.canViewPrice);

// --- Container ref for chart resize ---
const containerRef = ref<HTMLElement>();
useChartResize(containerRef, () => {
  impactChart?.resize();
  heatmapChart?.resize();
  comparisonChart?.resize();
});

// --- Upload selection ---
const uploads = ref<UploadHistoryItem[]>([]);
const selectedUploadId = ref<number | null>(null);
const loading = ref(false);

// --- Slider controls ---
const priceChange = ref(0);
const costChange = ref(0);
const trafficChange = ref(0);
const elasticity = ref(-1.2);

// --- Simulation results ---
const costStructure = ref<WhatIfCostStructure | null>(null);
const scenarioResult = ref<WhatIfScenarioResult | null>(null);
const sensitivityMatrix = ref<WhatIfSensitivityCell[]>([]);
const comparisonData = ref<WhatIfComparisonChart | null>(null);
const error = ref<string | null>(null);

// --- Saved scenarios ---
interface SavedScenario {
  id: number;
  name: string;
  priceChangePct: number;
  costChangePct: number;
  trafficChangePct: number;
  result: WhatIfScenarioResult;
}
const savedScenarios = ref<SavedScenario[]>([]);
let nextScenarioId = 1;

// --- Chart instances ---
let impactChart: echarts.ECharts | null = null;
let heatmapChart: echarts.ECharts | null = null;
let comparisonChart: echarts.ECharts | null = null;
const impactChartRef = ref<HTMLElement>();
const heatmapChartRef = ref<HTMLElement>();
const comparisonChartRef = ref<HTMLElement>();

// --- Load upload history ---
async function loadUploads() {
  try {
    const res = await getUploadHistory({ size: 200 });
    if (res.success && res.data) {
      uploads.value = res.data;
      if (res.data.length > 0 && !selectedUploadId.value) {
        selectedUploadId.value = res.data[0].id;
      }
    }
  } catch (e) {
    console.warn('Failed to load uploads:', e);
  }
}

// --- Run simulation ---
async function runSimulation() {
  if (!selectedUploadId.value) {
    ElMessage.warning('请先选择一个数据集');
    return;
  }
  loading.value = true;
  error.value = null;

  try {
    const res = await simulateWhatIf({
      uploadId: selectedUploadId.value,
      factoryId: factoryId.value,
      scenarios: [{
        name: 'custom',
        priceChangePct: priceChange.value,
        costChangePct: costChange.value,
        trafficChangePct: trafficChange.value,
      }],
      elasticity: elasticity.value,
    });

    if (!res.success) {
      error.value = res.error || '模拟失败';
      return;
    }

    costStructure.value = res.costStructure || null;
    scenarioResult.value = res.scenarios?.[0] || null;
    sensitivityMatrix.value = res.sensitivityMatrix || [];
    comparisonData.value = res.comparisonChart || null;

    await nextTick();
    renderImpactChart();
    renderHeatmap();
    renderComparisonChart();
  } catch (e) {
    error.value = e instanceof Error ? e.message : '模拟请求失败';
  } finally {
    loading.value = false;
  }
}

// --- Save current scenario ---
function saveScenario() {
  if (!scenarioResult.value) {
    ElMessage.warning('请先运行模拟');
    return;
  }
  const name = `方案 ${nextScenarioId}`;
  savedScenarios.value.push({
    id: nextScenarioId++,
    name,
    priceChangePct: priceChange.value,
    costChangePct: costChange.value,
    trafficChangePct: trafficChange.value,
    result: { ...scenarioResult.value },
  });
  ElMessage.success(`已保存: ${name}`);
}

function removeScenario(id: number) {
  savedScenarios.value = savedScenarios.value.filter(s => s.id !== id);
}

function loadScenario(s: SavedScenario) {
  priceChange.value = s.priceChangePct;
  costChange.value = s.costChangePct;
  trafficChange.value = s.trafficChangePct;
  runSimulation();
}

// --- Format helpers ---
function fmtMoney(val: number): string {
  if (Math.abs(val) >= 1e8) return (val / 1e8).toFixed(2) + '亿';
  if (Math.abs(val) >= 1e4) return (val / 1e4).toFixed(1) + '万';
  return val.toFixed(0);
}

function fmtPct(val: number): string {
  const sign = val >= 0 ? '+' : '';
  return sign + val.toFixed(1) + '%';
}

function fmtMargin(val: number): string {
  return (val * 100).toFixed(1) + '%';
}

// --- Impact chart (revenue/cost/profit triple line) ---
function renderImpactChart() {
  if (!impactChartRef.value) return;
  if (!impactChart) {
    impactChart = echarts.init(impactChartRef.value, 'cretas');
  }

  // Generate data points across price range
  const priceSteps = [];
  for (let p = -30; p <= 30; p += 5) {
    priceSteps.push(p);
  }

  const cs = costStructure.value;
  if (!cs) return;

  const el = elasticity.value;
  const costMult = 1 + costChange.value / 100;

  const revenues: number[] = [];
  const costs: number[] = [];
  const profits: number[] = [];

  for (const p of priceSteps) {
    const priceMult = 1 + p / 100;
    const trafficAdj = trafficChange.value + el * p;
    const trafficMult = 1 + trafficAdj / 100;
    const rev = cs.totalRevenue * priceMult * trafficMult;
    const cost = cs.totalCost * costMult * trafficMult;
    revenues.push(Math.round(rev));
    costs.push(Math.round(cost));
    profits.push(Math.round(rev - cost));
  }

  const option: echarts.EChartsOption = {
    title: { text: '价格变动影响分析', left: 'center', textStyle: { fontSize: 14 } },
    tooltip: {
      trigger: 'axis',
      formatter: (params: unknown) => {
        const items = params as Array<{ seriesName: string; value: number; axisValue: string }>;
        let html = `价格变动: ${items[0].axisValue}%<br/>`;
        for (const item of items) {
          html += `${item.seriesName}: ${fmtMoney(item.value)}<br/>`;
        }
        return html;
      },
    },
    legend: { bottom: 0 },
    grid: { left: 60, right: 20, top: 40, bottom: 40 },
    xAxis: {
      type: 'category',
      data: priceSteps.map(p => `${p >= 0 ? '+' : ''}${p}`),
      name: '价格变动(%)',
    },
    yAxis: {
      type: 'value',
      axisLabel: {
        formatter: (v: number) => fmtMoney(v),
      },
    },
    series: [
      { name: '收入', type: 'line', data: revenues, smooth: true, itemStyle: { color: '#409EFF' } },
      { name: '成本', type: 'line', data: costs, smooth: true, itemStyle: { color: '#E6A23C' } },
      { name: '毛利', type: 'line', data: profits, smooth: true, itemStyle: { color: '#67C23A' } },
    ],
  };

  impactChart.setOption(option, true);
}

// --- Sensitivity heatmap ---
function renderHeatmap() {
  if (!heatmapChartRef.value || sensitivityMatrix.value.length === 0) return;
  if (!heatmapChart) {
    heatmapChart = echarts.init(heatmapChartRef.value, 'cretas');
  }

  const priceSteps = [...new Set(sensitivityMatrix.value.map(c => c.priceChangePct))].sort((a, b) => a - b);
  const costSteps = [...new Set(sensitivityMatrix.value.map(c => c.costChangePct))].sort((a, b) => a - b);

  // Build data: [xIndex, yIndex, value]
  const data: Array<[number, number, number]> = [];
  let minVal = Infinity;
  let maxVal = -Infinity;

  for (const cell of sensitivityMatrix.value) {
    const xi = priceSteps.indexOf(cell.priceChangePct);
    const yi = costSteps.indexOf(cell.costChangePct);
    const margin = cell.grossMargin * 100;
    data.push([xi, yi, Math.round(margin * 10) / 10]);
    minVal = Math.min(minVal, margin);
    maxVal = Math.max(maxVal, margin);
  }

  const option: echarts.EChartsOption = {
    title: { text: '敏感性分析 (毛利率%)', left: 'center', textStyle: { fontSize: 14 } },
    tooltip: {
      position: 'top',
      formatter: (params: unknown) => {
        const p = params as { value: [number, number, number] };
        const price = priceSteps[p.value[0]];
        const cost = costSteps[p.value[1]];
        return `价格 ${price >= 0 ? '+' : ''}${price}%, 成本 ${cost >= 0 ? '+' : ''}${cost}%<br/>毛利率: ${p.value[2]}%`;
      },
    },
    grid: { left: 80, right: 60, top: 40, bottom: 60 },
    xAxis: {
      type: 'category',
      data: priceSteps.map(p => `${p >= 0 ? '+' : ''}${p}%`),
      name: '价格变动',
      splitArea: { show: true },
    },
    yAxis: {
      type: 'category',
      data: costSteps.map(c => `${c >= 0 ? '+' : ''}${c}%`),
      name: '成本变动',
      splitArea: { show: true },
    },
    visualMap: {
      min: Math.floor(minVal),
      max: Math.ceil(maxVal),
      calculable: true,
      orient: 'vertical',
      right: 0,
      top: 'center',
      inRange: {
        color: ['#F56C6C', '#E6A23C', '#f4e925', '#67C23A', '#409EFF'],
      },
    },
    series: [{
      name: '毛利率',
      type: 'heatmap',
      data,
      label: {
        show: true,
        formatter: (params: unknown) => {
          const p = params as { value: [number, number, number] };
          return `${p.value[2]}`;
        },
        fontSize: 11,
      },
      emphasis: {
        itemStyle: { shadowBlur: 10, shadowColor: 'rgba(0,0,0,0.5)' },
      },
    }],
  };

  heatmapChart.setOption(option, true);
}

// --- Comparison chart (optimistic/baseline/pessimistic) ---
function renderComparisonChart() {
  if (!comparisonChartRef.value || !comparisonData.value) return;
  if (!comparisonChart) {
    comparisonChart = echarts.init(comparisonChartRef.value, 'cretas');
  }

  const cd = comparisonData.value;

  const option: echarts.EChartsOption = {
    title: { text: '三种情景对比', left: 'center', textStyle: { fontSize: 14 } },
    tooltip: {
      trigger: 'axis',
      formatter: (params: unknown) => {
        const items = params as Array<{ seriesName: string; value: number; axisValue: string }>;
        let html = `${items[0].axisValue}<br/>`;
        for (const item of items) {
          html += `${item.seriesName}: ${fmtMoney(item.value)}<br/>`;
        }
        return html;
      },
    },
    legend: { bottom: 0 },
    grid: { left: 60, right: 20, top: 40, bottom: 40 },
    xAxis: { type: 'category', data: cd.labels },
    yAxis: {
      type: 'value',
      axisLabel: { formatter: (v: number) => fmtMoney(v) },
    },
    series: [
      { name: '收入', type: 'bar', data: cd.revenues, itemStyle: { color: '#409EFF' } },
      { name: '成本', type: 'bar', data: cd.costs, itemStyle: { color: '#E6A23C' } },
      { name: '毛利', type: 'bar', data: cd.profits, itemStyle: { color: '#67C23A' } },
    ],
  };

  comparisonChart.setOption(option, true);
}

// --- Watch sliders for live update (debounced) ---
let debounceTimer: ReturnType<typeof setTimeout> | null = null;

function debouncedSimulate() {
  if (debounceTimer) clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => {
    if (selectedUploadId.value) {
      runSimulation();
    }
  }, 500);
}

watch([priceChange, costChange, trafficChange], () => {
  debouncedSimulate();
});

// --- Lifecycle ---
onMounted(async () => {
  await loadUploads();
  if (selectedUploadId.value) {
    await runSimulation();
  }
});

onUnmounted(() => {
  impactChart?.dispose();
  heatmapChart?.dispose();
  comparisonChart?.dispose();
  if (debounceTimer) clearTimeout(debounceTimer);
});

// ==================== Chart Insight ====================
// Impact LINE chart (revenue series across price-change range, TREND family, domain='finance').
// We derive the revenue data points the same way renderImpactChart does, using revenue series only.
const impactLineSource = (): { chart: ChartWithMeta } | null => {
  const cs = costStructure.value;
  if (!cs) return null;
  const priceSteps: number[] = [];
  for (let p = -30; p <= 30; p += 5) priceSteps.push(p);
  const el = elasticity.value;
  const costMult = 1 + costChange.value / 100;
  void costMult; // cost not needed for revenue-only series
  const revenues: number[] = priceSteps.map((p) => {
    const priceMult = 1 + p / 100;
    const trafficAdj = trafficChange.value + el * p;
    const trafficMult = 1 + trafficAdj / 100;
    return Math.round(cs.totalRevenue * priceMult * trafficMult);
  });
  if (revenues.length < 4) return null;
  return {
    chart: {
      chartType: 'LINE',
      meta: { xDim: 'time', yMetric: 'revenue', aggregation: 'sum', domain: 'finance' },
      config: {
        xAxis: { data: priceSteps.map((p) => `${p >= 0 ? '+' : ''}${p}`) },
        series: [{ name: '收入', type: 'line', data: revenues }],
      },
    },
  };
};

// Comparison BAR chart (三情景对比: optimistic/baseline/pessimistic, COMPARISON family, domain='finance').
const comparisonBarSource = (): { chart: ChartWithMeta } | null => {
  const cd = comparisonData.value;
  if (!cd || cd.labels.length < 2) return null;
  return {
    chart: {
      chartType: 'comparison',
      meta: { xDim: 'category', yMetric: 'revenue', aggregation: 'sum', domain: 'finance' },
      config: {
        xAxis: { data: cd.labels },
        series: [
          { name: '收入', type: 'bar', data: cd.revenues },
          { name: '成本', type: 'bar', data: cd.costs },
        ],
      },
    },
  };
};

const whatIfPerms = () => ({ canViewFinance: canViewPrice.value });

const { insight: impactLineInsight, loading: impactLineInsightLoading } = useChartInsight(
  impactLineSource,
  whatIfPerms,
  { factoryId: () => factoryId.value ?? '', autoTier2: true },
);

const { insight: comparisonBarInsight, loading: comparisonBarInsightLoading } = useChartInsight(
  comparisonBarSource,
  whatIfPerms,
  { factoryId: () => factoryId.value ?? '', autoTier2: true },
);
</script>

<template>
  <div ref="containerRef" class="whatif-simulator">
    <!-- Header -->
    <div class="page-header">
      <h2>
        <el-icon><TrendCharts /></el-icon>
        What-If 定价模拟器
      </h2>
      <p class="subtitle">调整价格、成本、客流参数，实时查看收入和利润影响</p>
    </div>

    <template v-if="canViewPrice">
    <!-- Data source + controls row -->
    <el-row :gutter="16">
      <!-- Left: Controls -->
      <el-col :span="8">
        <el-card shadow="hover">
          <template #header>
            <div class="card-header">
              <span>参数设置</span>
              <el-select
                v-model="selectedUploadId"
                placeholder="选择数据集"
                style="width: 200px; margin-left: auto;"
                @change="runSimulation"
                filterable
              >
                <el-option
                  v-for="u in uploads"
                  :key="u.id"
                  :label="`${u.fileName} - ${u.sheetName}`"
                  :value="u.id"
                />
              </el-select>
            </div>
          </template>

          <div class="slider-group">
            <div class="slider-item">
              <div class="slider-label">
                <span>价格调整</span>
                <el-tag :type="priceChange > 0 ? 'danger' : priceChange < 0 ? 'success' : 'info'" size="small">
                  {{ fmtPct(priceChange) }}
                </el-tag>
              </div>
              <el-slider v-model="priceChange" :min="-30" :max="30" :step="1" :marks="{ '-30': '-30%', 0: '0', 30: '+30%' }" />
            </div>

            <div class="slider-item">
              <div class="slider-label">
                <span>成本调整</span>
                <el-tag :type="costChange > 0 ? 'danger' : costChange < 0 ? 'success' : 'info'" size="small">
                  {{ fmtPct(costChange) }}
                </el-tag>
              </div>
              <el-slider v-model="costChange" :min="-20" :max="20" :step="1" :marks="{ '-20': '-20%', 0: '0', 20: '+20%' }" />
            </div>

            <div class="slider-item">
              <div class="slider-label">
                <span>客流调整</span>
                <el-tag :type="trafficChange > 0 ? 'success' : trafficChange < 0 ? 'danger' : 'info'" size="small">
                  {{ fmtPct(trafficChange) }}
                </el-tag>
              </div>
              <el-slider v-model="trafficChange" :min="-50" :max="50" :step="1" :marks="{ '-50': '-50%', 0: '0', 50: '+50%' }" />
            </div>

            <div class="slider-item">
              <div class="slider-label">
                <span>价格弹性系数</span>
                <el-tooltip content="价格变动1%对客流的影响。餐饮行业通常为 -1.0 ~ -1.5">
                  <el-icon><InfoFilled /></el-icon>
                </el-tooltip>
                <el-tag type="info" size="small" style="margin-left: auto;">{{ elasticity }}</el-tag>
              </div>
              <el-slider v-model="elasticity" :min="-3" :max="0" :step="0.1" />
            </div>
          </div>

          <div class="action-row">
            <el-button type="primary" @click="runSimulation" :loading="loading">
              运行模拟
            </el-button>
            <el-button @click="saveScenario" :disabled="!scenarioResult">
              保存方案
            </el-button>
          </div>
        </el-card>

        <!-- Cost structure card -->
        <el-card v-if="costStructure && canViewPrice" shadow="hover" style="margin-top: 16px;">
          <template #header>
            <span>成本结构 (自动检测)</span>
          </template>
          <div class="cost-items">
            <div class="cost-row">
              <span>总收入</span>
              <span class="cost-val">{{ fmtMoney(costStructure.totalRevenue) }}</span>
            </div>
            <div class="cost-row">
              <span>总成本</span>
              <span class="cost-val">{{ fmtMoney(costStructure.totalCost) }}</span>
            </div>
            <el-divider />
            <div class="cost-row">
              <span>原料占比</span>
              <span class="cost-val">{{ fmtMargin(costStructure.ingredientRatio) }}</span>
            </div>
            <div class="cost-row">
              <span>人工占比</span>
              <span class="cost-val">{{ fmtMargin(costStructure.laborRatio) }}</span>
            </div>
            <div class="cost-row">
              <span>租金占比</span>
              <span class="cost-val">{{ fmtMargin(costStructure.rentRatio) }}</span>
            </div>
            <div class="cost-row highlight">
              <span>毛利率</span>
              <span class="cost-val">{{ fmtMargin(costStructure.grossMargin) }}</span>
            </div>
          </div>
        </el-card>
      </el-col>

      <!-- Right: Results -->
      <el-col :span="16">
        <!-- KPI impact cards -->
        <el-row :gutter="12" v-if="scenarioResult" class="kpi-row">
          <el-col v-if="canViewPrice" :span="6">
            <el-card shadow="hover" class="kpi-card">
              <div class="kpi-title">预计收入</div>
              <div class="kpi-value">{{ fmtMoney(scenarioResult.projectedRevenue) }}</div>
              <div :class="['kpi-delta', scenarioResult.revenueImpact >= 0 ? 'up' : 'down']">
                {{ fmtPct(scenarioResult.revenueImpactPct) }}
                <span class="kpi-abs">({{ scenarioResult.revenueImpact >= 0 ? '+' : '' }}{{ fmtMoney(scenarioResult.revenueImpact) }})</span>
              </div>
            </el-card>
          </el-col>
          <el-col v-if="canViewPrice" :span="6">
            <el-card shadow="hover" class="kpi-card">
              <div class="kpi-title">预计毛利</div>
              <div class="kpi-value">{{ fmtMoney(scenarioResult.projectedGrossProfit) }}</div>
              <div :class="['kpi-delta', scenarioResult.grossProfitImpact >= 0 ? 'up' : 'down']">
                {{ fmtPct(scenarioResult.grossProfitImpactPct) }}
              </div>
            </el-card>
          </el-col>
          <el-col :span="6">
            <el-card shadow="hover" class="kpi-card">
              <div class="kpi-title">预计毛利率</div>
              <div class="kpi-value">{{ fmtMargin(scenarioResult.projectedGrossMargin) }}</div>
              <div class="kpi-delta" style="color: #909399;">
                基准: {{ fmtMargin(scenarioResult.currentGrossMargin) }}
              </div>
            </el-card>
          </el-col>
          <el-col :span="6">
            <el-card shadow="hover" class="kpi-card">
              <div class="kpi-title">盈亏平衡点</div>
              <div class="kpi-value">
                {{ scenarioResult.breakevenPriceChangePct !== null ? fmtPct(scenarioResult.breakevenPriceChangePct) : 'N/A' }}
              </div>
              <div class="kpi-delta" style="color: #909399;">
                价格调整至此毛利不变
              </div>
            </el-card>
          </el-col>
        </el-row>

        <!-- Error message -->
        <el-alert v-if="error" :title="error" type="error" show-icon style="margin-bottom: 16px;" />

        <!-- Impact chart -->
        <el-card shadow="hover" style="margin-bottom: 16px;">
          <div ref="impactChartRef" class="chart-container" />
          <!-- U6: useChartInsight — Tier1 instant, Tier2 auto on null (飞轮接通) -->
          <ChartInsight :insight="impactLineInsight" :loading="impactLineInsightLoading" depth="detailed" />
        </el-card>

        <!-- Heatmap + Comparison -->
        <el-row :gutter="16">
          <el-col :span="14">
            <el-card shadow="hover">
              <div ref="heatmapChartRef" class="chart-container" />
            </el-card>
          </el-col>
          <el-col :span="10">
            <el-card shadow="hover">
              <div ref="comparisonChartRef" class="chart-container" />
              <!-- U6: useChartInsight — Tier1 instant, Tier2 auto on null (飞轮接通) -->
              <ChartInsight :insight="comparisonBarInsight" :loading="comparisonBarInsightLoading" depth="detailed" />
            </el-card>
          </el-col>
        </el-row>
      </el-col>
    </el-row>

    <!-- Saved scenarios -->
    <el-card v-if="savedScenarios.length > 0" shadow="hover" style="margin-top: 16px;">
      <template #header>
        <div class="card-header">
          <el-icon><DataAnalysis /></el-icon>
          <span style="margin-left: 8px;">已保存方案对比</span>
        </div>
      </template>
      <el-table :data="savedScenarios" stripe>
        <el-table-column prop="name" label="方案" width="100" />
        <el-table-column label="价格" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.priceChangePct > 0 ? 'danger' : row.priceChangePct < 0 ? 'success' : 'info'" size="small">
              {{ fmtPct(row.priceChangePct) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="成本" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.costChangePct > 0 ? 'danger' : row.costChangePct < 0 ? 'success' : 'info'" size="small">
              {{ fmtPct(row.costChangePct) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="客流" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.trafficChangePct > 0 ? 'success' : row.trafficChangePct < 0 ? 'danger' : 'info'" size="small">
              {{ fmtPct(row.trafficChangePct) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="预计收入" align="right">
          <template #default="{ row }">{{ fmtMoney(row.result.projectedRevenue) }}</template>
        </el-table-column>
        <el-table-column label="收入变化" align="right">
          <template #default="{ row }">
            <span :style="{ color: row.result.revenueImpact >= 0 ? '#67C23A' : '#F56C6C' }">
              {{ fmtPct(row.result.revenueImpactPct) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="预计毛利" align="right">
          <template #default="{ row }">{{ fmtMoney(row.result.projectedGrossProfit) }}</template>
        </el-table-column>
        <el-table-column label="毛利率" align="right">
          <template #default="{ row }">{{ fmtMargin(row.result.projectedGrossMargin) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="loadScenario(row)">加载</el-button>
            <el-button type="danger" link size="small" @click="removeScenario(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    </template>
    <el-empty v-else description="您没有查看价格/成本数据的权限" />
  </div>
</template>

<style scoped>
.whatif-simulator {
  padding: 20px;
}

.page-header {
  margin-bottom: 20px;
}
.page-header h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 4px;
  font-size: 20px;
}
.page-header .subtitle {
  margin: 0;
  color: #909399;
  font-size: 13px;
}

.card-header {
  display: flex;
  align-items: center;
}

.slider-group {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.slider-item {
  padding: 0 8px;
}

.slider-label {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 13px;
  color: #606266;
}

.action-row {
  display: flex;
  gap: 12px;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid #ebeef5;
}

.cost-items {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.cost-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  color: #606266;
}
.cost-row.highlight {
  font-weight: 600;
  color: #303133;
}
.cost-val {
  font-variant-numeric: tabular-nums;
}

.kpi-row {
  margin-bottom: 16px;
}

.kpi-card {
  text-align: center;
}
.kpi-title {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}
.kpi-value {
  font-size: 22px;
  font-weight: 600;
  color: #303133;
  font-variant-numeric: tabular-nums;
}
.kpi-delta {
  font-size: 12px;
  margin-top: 4px;
}
.kpi-delta.up {
  color: #67C23A;
}
.kpi-delta.down {
  color: #F56C6C;
}
.kpi-abs {
  color: #909399;
  font-size: 11px;
}

.chart-container {
  width: 100%;
  height: 360px;
}

:deep(.el-divider) {
  margin: 4px 0;
}
</style>
