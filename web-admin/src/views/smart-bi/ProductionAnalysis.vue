<script setup lang="ts">
/**
 * 生产数据分析页面
 * 基于 SmartBI 组件模式，提供生产 KPI、多维度图表和 AI 洞察
 */
import { ref, shallowRef, onMounted, onUnmounted, nextTick, computed } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import echarts from '@/utils/echarts'
import { get } from '@/api/request'
import { useAuthStore } from '@/store/modules/auth'
import { usePermissionStore } from '@/store/modules/permission'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { formatNumber } from '@/utils/format-number'
import { ElMessage } from 'element-plus'
import type { TableRow } from '@/types/api';
import ChartInsightProvider from '@/views/smart-bi/components/ChartInsightProvider.vue';
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight';

const authStore = useAuthStore()
const permissionStore = usePermissionStore()
const factoryId = computed(() => authStore.factoryId)
const canViewPrice = computed(() => permissionStore.canViewPrice)

const rootRef = ref<HTMLDivElement>()
const period = ref('month')
const dimension = ref('date')
const loading = ref(false)
const kpiData = ref<Array<{ label: string; value: string; gradient: string; change?: string; changeType?: string }>>([])
const charts = ref<Array<{ title: string; option: TableRow }>>([])
const chartRefs = shallowRef<Array<HTMLDivElement | null>>([])
const chartInstances = shallowRef<echarts.ECharts[]>([])
const aiAnalysis = ref('')
const tableData = ref<TableRow[]>([])
const tableColumns = ref<string[]>([])
const renderedAnalysis = computed(() => aiAnalysis.value ? DOMPurify.sanitize(marked(aiAnalysis.value) as string) : '')

let refreshTimer: ReturnType<typeof setInterval> | null = null

let loadingInProgress = false
async function loadData() {
  if (!factoryId.value) {
    console.error('No factoryId available')
    ElMessage.error('无法获取工厂信息，请重新登录')
    return
  }
  if (loadingInProgress) return
  loadingInProgress = true
  loading.value = true
  try {
    const res = await get<TableRow>(`/${factoryId.value}/smart-bi/production-analysis/dashboard`, {
      params: { period: period.value }
    })
    if (res?.success !== false) {
      const data = (res.data || res) as TableRow
      processKPIs((data.dailySummary as TableRow[]) || [])
      buildCharts(data)
      await loadDimensionData()
    }
  } catch (e: unknown) {
    const status = (e as { status?: number })?.status
    if (status === 404) {
      console.info('Production analysis endpoint not available yet')
    } else {
      console.error('Load production analysis failed:', e)
      ElMessage.error('加载生产分析数据失败，请稍后重试')
    }
  } finally {
    loading.value = false
    loadingInProgress = false
  }
}

async function loadDimensionData() {
  try {
    const res = await get<TableRow[]>(`/${factoryId.value}/smart-bi/production-analysis/data`, {
      params: { dimension: dimension.value, period: period.value }
    })
    if (res?.success !== false) {
      const rows = (res.data || res || []) as TableRow[]
      tableData.value = rows
      tableColumns.value = rows.length > 0 ? Object.keys(rows[0]) : []
    }
  } catch (e: unknown) {
    const status = (e as { status?: number })?.status
    if (status !== 404) {
      console.error('Load dimension data failed:', e)
    }
  }
}

function processKPIs(dailySummary: TableRow[]) {
  if (!dailySummary || dailySummary.length === 0) {
    kpiData.value = []
    return
  }
  const totalOutput = dailySummary.reduce((s: number, r) => s + (Number(r['totalOutput'] || r['totalQuantity'] || 0)), 0)
  const avgYield = dailySummary.reduce((s: number, r) => s + (Number(r['avgYieldRate'] || r['yieldRate'] || 0)), 0) / dailySummary.length
  const totalCost = dailySummary.reduce((s: number, r) => s + (Number(r['totalCost'] || 0)), 0)
  const totalBatches = dailySummary.reduce((s: number, r) => s + (Number(r['batchCount'] || 0)), 0)

  const gradients = [
    'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
    'linear-gradient(135deg, #f093fb 0%, #f5576c 100%)',
    'linear-gradient(135deg, #4facfe 0%, #00f2fe 100%)',
    'linear-gradient(135deg, #43e97b 0%, #38f9d7 100%)',
  ]

  // canViewPrice: 非 PRICE_VIEW_ROLES (e.g. viewer) 不显示 "总成本" KPI 卡
  // Aligns with PR #520 UI defense + backend price-stripping policy.
  const cards: Array<{ label: string; value: string; gradient: string }> = [
    { label: '总产量', value: formatNumber(totalOutput), gradient: gradients[0] },
    { label: '平均良率', value: avgYield.toFixed(1) + '%', gradient: gradients[1] },
  ]
  if (canViewPrice.value) {
    cards.push({ label: '总成本', value: '¥' + formatNumber(totalCost), gradient: gradients[2] })
  }
  cards.push({ label: '总批次数', value: totalBatches.toString(), gradient: gradients[3] })
  kpiData.value = cards
}

// formatNumber imported from @/utils/format-number

function buildCharts(data: TableRow) {
  const chartConfigs: Array<{ title: string; option: TableRow }> = []
  const dailySummary = (data.dailySummary || []) as TableRow[]
  const byProduct = (data.byProduct || []) as TableRow[]
  const byEquipment = (data.byEquipment || []) as TableRow[]
  const byPersonnel = (data.byPersonnel || []) as TableRow[]

  // Chart 1: Daily output trend (line)
  if (dailySummary.length > 0) {
    chartConfigs.push({
      title: '日产量趋势',
      option: {
        tooltip: { trigger: 'axis', confine: true },
        legend: { data: ['总产量', '合格产量', '缺陷数'] },
        xAxis: { type: 'category', data: dailySummary.map(r => String(r['date'] || r['日期'] || '')) },
        yAxis: { type: 'value', name: '数量' },
        series: [
          { name: '总产量', type: 'line', data: dailySummary.map(r => Number(r['totalOutput'] || r['totalQuantity'] || 0)), smooth: true, areaStyle: { opacity: 0.1 } },
          { name: '合格产量', type: 'line', data: dailySummary.map(r => Number(r['qualifiedOutput'] || r['qualifiedQuantity'] || 0)), smooth: true },
          { name: '缺陷数', type: 'line', data: dailySummary.map(r => Number(r['defectCount'] || 0)), smooth: true, lineStyle: { type: 'dashed' } },
        ]
      }
    })
  }

  // Chart 2: Yield rate trend (line + markLine)
  if (dailySummary.length > 0) {
    chartConfigs.push({
      title: '良率趋势',
      option: {
        tooltip: { trigger: 'axis', confine: true, formatter: '{b}: {c}%' },
        xAxis: { type: 'category', data: dailySummary.map(r => String(r['date'] || r['日期'] || '')) },
        yAxis: { type: 'value', name: '良率(%)', min: 0, max: 100 },
        series: [{
          name: '良率', type: 'line',
          data: dailySummary.map(r => Number(r['avgYieldRate'] || r['yieldRate'] || 0)),
          smooth: true, areaStyle: { opacity: 0.15 },
          markLine: { data: [{ yAxis: 95, name: '目标', lineStyle: { color: '#E6A23C', type: 'dashed' } }] },
          markPoint: { data: [{ type: 'min', name: '最低' }, { type: 'max', name: '最高' }] }
        }]
      }
    })
  }

  // Chart 3: Product output comparison (bar)
  if (byProduct.length > 0) {
    chartConfigs.push({
      title: '产品产量对比',
      option: {
        tooltip: { trigger: 'axis', confine: true },
        xAxis: { type: 'category', data: byProduct.map(r => String(r['productName'] || '')), axisLabel: { rotate: 30 } },
        yAxis: { type: 'value', name: '产量' },
        series: [{
          type: 'bar',
          data: byProduct.map(r => Number(r['totalOutput'] || r['totalQuantity'] || 0)),
          itemStyle: { borderRadius: [4, 4, 0, 0] }
        }]
      }
    })
  }

  // Chart 4: Equipment efficiency (horizontal bar)
  if (byEquipment.length > 0) {
    chartConfigs.push({
      title: '设备效率排行',
      option: {
        tooltip: { trigger: 'axis', confine: true },
        xAxis: { type: 'value', name: '效率(%)' },
        yAxis: { type: 'category', data: byEquipment.map(r => String(r['equipmentName'] || r['equipmentId'] || '')) },
        series: [{ type: 'bar', data: byEquipment.map(r => Number(r['avgEfficiency'] || 0)) }]
      }
    })
  }

  // Chart 5: Personnel ranking (horizontal bar)
  if (byPersonnel.length > 0) {
    chartConfigs.push({
      title: '人员产出排行',
      option: {
        tooltip: { trigger: 'axis', confine: true },
        xAxis: { type: 'value', name: '产量' },
        yAxis: { type: 'category', data: byPersonnel.map(r => String(r['operatorName'] || r['personnelName'] || '')) },
        series: [{ type: 'bar', data: byPersonnel.map(r => Number(r['totalOutput'] || r['totalQuantity'] || 0)) }]
      }
    })
  }

  charts.value = chartConfigs

  // AI analysis (if included in response)
  if (typeof data.aiAnalysis === 'string') {
    aiAnalysis.value = data.aiAnalysis
  }

  nextTick(() => {
    // Dispose old instances
    chartInstances.value.forEach(c => c?.dispose())
    chartInstances.value = []

    chartConfigs.forEach((cfg, idx) => {
      const el = chartRefs.value[idx]
      if (el) {
        const instance = echarts.init(el, 'cretas')
        instance.setOption(cfg.option)
        chartInstances.value.push(instance)
      }
    })
  })
}

// ==================== Chart Insight helpers ====================
// v-for loop: use ChartInsightProvider per item (safe in v-for; each instance has own setup()).
// Derive ChartWithMeta from each chart's ECharts option.

/**
 * Derive ChartWithMeta from a raw ECharts option for the SmartBI production charts.
 *
 * Heuristics:
 *  - Has xAxis.type='category' with date-like data  → LINE/BAR time TREND
 *  - Has yAxis.type='category' (horizontal bar)     → BAR RANKING by category
 *  - Has xAxis.type='category' with product/equip   → BAR RANKING by category
 *  - Default: quantity metric (no finance)
 *
 * Returns null for unrecognised shapes (ChartInsightProvider renders nothing on null).
 */
function buildChartWithMeta(title: string, option: TableRow): ChartWithMeta | null {
  try {
    const xAxis = option.xAxis as { type?: string; data?: unknown[] } | undefined;
    const yAxis = option.yAxis as { type?: string } | undefined;
    const seriesArr = option.series as Array<{ type?: string; data?: unknown[] }> | undefined;
    if (!seriesArr || seriesArr.length === 0) return null;

    // Determine primary chart type from first series
    const firstSeriesType = (seriesArr[0]?.type ?? '').toLowerCase();
    if (!['line', 'bar'].includes(firstSeriesType)) return null; // skip pie/unknown

    // Horizontal bar: yAxis is category — RANKING by category
    if (yAxis?.type === 'category') {
      const yData = (yAxis as { type?: string; data?: unknown[] }).data;
      if (!Array.isArray(yData) || yData.length < 2) return null;
      const values = seriesArr[0]?.data as number[] | undefined;
      if (!values || values.length < 2) return null;
      return {
        chartType: firstSeriesType.toUpperCase(),
        title,
        meta: { xDim: 'category', yMetric: 'quantity', aggregation: 'sum', domain: 'factory' },
        config: {
          xAxis: { data: yData.map(String) }, // use category labels as xAxis
          series: [{ type: firstSeriesType, data: values }],
        },
      };
    }

    // Regular xAxis category
    if (xAxis?.type === 'category' && Array.isArray(xAxis.data) && xAxis.data.length >= 2) {
      const labels = xAxis.data.map(String);
      // Date-like labels → TREND
      const isTimeSeries = /^\d{4}[-年]|\d{2}[-\/]\d{2}|\d{2}$/.test(labels[0] ?? '');
      const xDim: 'time' | 'category' = isTimeSeries ? 'time' : 'category';

      // For multi-series (日产量趋势), use first series (总产量) for insight
      const primaryData = seriesArr[0]?.data as number[] | undefined;
      if (!primaryData || primaryData.length < 2) return null;

      return {
        chartType: firstSeriesType.toUpperCase(),
        title,
        meta: {
          xDim,
          yMetric: xDim === 'time' ? 'quantity' : 'quantity',
          aggregation: xDim === 'time' ? 'sum' : 'sum',
          domain: 'factory',
        },
        config: {
          xAxis: { data: labels },
          series: [{ type: firstSeriesType, data: primaryData }],
        },
      };
    }

    return null;
  } catch {
    return null;
  }
}

const sbProductionPerms = computed(() => ({ canViewFinance: canViewPrice.value === true }));

let resizeObserver: ResizeObserver | null = null
let resizeRaf = 0
function handleResize() {
  if (resizeRaf) return
  resizeRaf = requestAnimationFrame(() => {
    chartInstances.value.forEach(c => c?.resize())
    resizeRaf = 0
  })
}

onMounted(() => {
  loadData()
  refreshTimer = setInterval(loadData, 60000)
  window.addEventListener('resize', handleResize)
  if (rootRef.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(handleResize)
    resizeObserver.observe(rootRef.value)
  }
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
  resizeObserver?.disconnect()
  resizeObserver = null
  window.removeEventListener('resize', handleResize)
  chartInstances.value.forEach(c => c?.dispose())
})
</script>

<template>
  <div ref="rootRef" class="production-analysis" role="main" aria-label="生产数据分析">
    <div class="page-header">
      <div class="header-left">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item :to="{ path: '/smart-bi/dashboard' }">Smart BI</el-breadcrumb-item>
          <el-breadcrumb-item>生产数据分析</el-breadcrumb-item>
        </el-breadcrumb>
        <h2>生产数据分析</h2>
      </div>
      <div class="controls">
        <el-select v-model="period" placeholder="选择周期" @change="loadData" style="width: 120px">
          <el-option label="本周" value="week" />
          <el-option label="本月" value="month" />
          <el-option label="本季度" value="quarter" />
          <el-option label="本年" value="year" />
        </el-select>
        <el-select v-model="dimension" placeholder="分析维度" @change="loadDimensionData" style="width: 120px; margin-left: 12px">
          <el-option label="按日期" value="date" />
          <el-option label="按产品" value="product" />
          <el-option label="按设备" value="equipment" />
          <el-option label="按人员" value="personnel" />
        </el-select>
        <el-button type="primary" :icon="Refresh" @click="loadData" :loading="loading" style="margin-left: 12px">
          刷新
        </el-button>
      </div>
    </div>

    <!-- Empty state -->
    <el-empty v-if="!loading && kpiData.length === 0 && charts.length === 0" description="暂无生产分析数据" />

    <!-- KPI Cards -->
    <div class="kpi-row" v-if="kpiData.length > 0" aria-label="KPI指标" aria-live="polite" :aria-busy="loading">
      <div v-for="kpi in kpiData" :key="kpi.label" class="kpi-card" :style="{ background: kpi.gradient }">
        <div class="kpi-value">{{ kpi.value }}</div>
        <div class="kpi-label">{{ kpi.label }}</div>
        <div class="kpi-change" v-if="kpi.change">
          <span :class="kpi.changeType === 'up' ? 'change-up' : 'change-down'">
            {{ kpi.changeType === 'up' ? '\u2191' : '\u2193' }} {{ kpi.change }}
          </span>
        </div>
      </div>
    </div>

    <!-- Charts Grid -->
    <div class="charts-grid" v-loading="loading" aria-label="图表区域" :aria-busy="loading">
      <div class="chart-card" v-for="(chart, idx) in charts" :key="idx">
        <h3 class="chart-title">{{ chart.title }}</h3>
        <div :ref="el => chartRefs[idx] = (el as HTMLDivElement)" class="chart-container"></div>
        <!--
          ChartInsightProvider: safe in v-for (each instance has its own setup()).
          buildChartWithMeta derives ChartWithMeta from the ECharts option.
          LINE → TREND family; BAR category → RANKING family.
          Exotic types (pie/scatter/heatmap) → buildChartWithMeta returns null → no render.
        -->
        <ChartInsightProvider
          :chart="buildChartWithMeta(chart.title, chart.option)"
          :perms="sbProductionPerms"
          :factory-id="factoryId ?? ''"
          depth="detailed"
        />
      </div>
    </div>

    <!-- AI Analysis -->
    <div class="ai-section" v-if="aiAnalysis" aria-label="AI分析洞察" aria-live="polite">
      <h3><span class="ai-icon">AI</span> 生产分析洞察</h3>
      <div class="ai-content" v-html="renderedAnalysis"></div>
    </div>

    <!-- Data Table -->
    <div class="data-section" v-if="tableData.length > 0" aria-label="详细数据">
      <h3>详细数据</h3>
      <el-table empty-text="暂无数据" :data="tableData" stripe border style="width: 100%" max-height="400">
        <el-table-column v-for="col in tableColumns" :key="col" :prop="col" :label="col" min-width="120" />
      </el-table>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.production-analysis {
  padding: var(--page-padding);
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
}

.controls {
  display: flex;
  align-items: center;
}

.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.kpi-card {
  padding: 20px;
  border-radius: var(--radius-lg);
  color: #fff;
}

.kpi-value {
  font-size: 28px;
  font-weight: 700;
}

.kpi-label {
  font-size: 14px;
  opacity: 0.9;
  margin-top: 4px;
}

.kpi-change {
  margin-top: 8px;
  font-size: 13px;
}

.change-up {
  color: #dcfce7;
}

.change-down {
  color: #fecaca;
}

.charts-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.chart-card {
  background: #fff;
  border-radius: var(--radius-lg);
  padding: 16px;
  box-shadow: var(--shadow-sm);
}

.chart-title {
  font-size: 15px;
  font-weight: 600;
  margin: 0 0 8px 0;
  color: #303133;
}

.ai-section {
  background: #fff;
  border-radius: var(--radius-lg);
  padding: 20px;
  margin-bottom: 24px;
  box-shadow: var(--shadow-sm);

  h3 {
    margin: 0 0 12px 0;
    color: #303133;
  }
}

.ai-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-md);
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  margin-right: 6px;
  vertical-align: middle;
}

.ai-content {
  line-height: 1.7;
  color: #333;
}

.chart-container {
  height: 350px;
}

.data-section {
  background: #fff;
  border-radius: var(--radius-lg);
  padding: 20px;
  box-shadow: var(--shadow-sm);

  h3 {
    margin: 0 0 12px 0;
    color: #303133;
  }
}

.header-left {
  h2 {
    margin: 12px 0 0;
    font-size: 20px;
    color: #303133;
  }
}

@media (max-width: 1024px) {
  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }

  .charts-grid {
    grid-template-columns: 1fr;
  }

  .chart-container {
    height: 300px;
  }
}

@media (max-width: 768px) {
  .kpi-row {
    grid-template-columns: 1fr;
  }

  .production-analysis {
    padding: 12px;
  }

  .chart-container {
    height: 250px;
  }

  .page-header {
    flex-direction: column;
    gap: 12px;
    align-items: flex-start;
  }
}
</style>
