<!--
  IndicatorTrendPane — Tab 5: 趋势图 (时间窗内 measurements → ECharts line chart)。

  默认显示近 30 天, 可自定义时间窗。
-->
<template>
  <div class="pane">
    <div class="toolbar">
      <el-date-picker
        v-model="dateRange"
        type="daterange"
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        :shortcuts="dateShortcuts"
        format="YYYY-MM-DD"
        value-format="YYYY-MM-DD"
      />
      <el-button :icon="Refresh" @click="loadData">刷新</el-button>
    </div>

    <el-empty
      v-if="!loading && measurements.length === 0"
      description="时间窗内无快照数据"
    />

    <div v-show="measurements.length > 0" ref="chartRef" class="chart-container" />
    <ChartInsight :insight="indicatorTrendInsight" :loading="indicatorTrendInsightLoading" depth="detailed" />

    <el-table
      v-if="measurements.length > 0"
      :data="measurements"
      border
      style="margin-top: 16px"
      max-height="240"
    >
      <el-table-column prop="computedAt" label="计算时刻" min-width="180">
        <template #default="{ row }">
          {{ formatTs(row.computedAt) }}
        </template>
      </el-table-column>
      <el-table-column prop="value" label="值" min-width="120">
        <template #default="{ row }">
          <strong>{{ row.value }}</strong>
          <span class="unit">{{ detail.unit || '' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="alertLevel" label="告警级别" min-width="120">
        <template #default="{ row }">
          <el-tag v-if="row.alertLevel" :type="levelType(row.alertLevel)" size="small">
            {{ levelLabel(row.alertLevel) }}
          </el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="computeSource" label="计算源" min-width="200" show-overflow-tooltip />
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  TitleComponent,
  DataZoomComponent,
  LegendComponent,
  MarkLineComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

import {
  indicatorsApi,
  type IndicatorDetail,
  type IndicatorVersion,
  type AlertLevel,
} from '@/api/canvasIndicators'
import { useChartInsight } from '@/composables/useChartInsight'
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue'

echarts.use([
  LineChart,
  GridComponent,
  TooltipComponent,
  TitleComponent,
  DataZoomComponent,
  LegendComponent,
  MarkLineComponent,
  CanvasRenderer,
])

interface Props {
  factoryId: string
  detail: IndicatorDetail
}
const props = defineProps<Props>()

const measurements = ref<IndicatorVersion[]>([])
const chartRef = ref<HTMLDivElement | null>(null)
const dateRange = ref<[string, string] | null>(null)
const loading = ref(false)
let chart: echarts.ECharts | null = null

const dateShortcuts = [
  { text: '近 7 天', value: () => last(7) },
  { text: '近 30 天', value: () => last(30) },
  { text: '近 90 天', value: () => last(90) },
]

function last(days: number): [Date, Date] {
  const end = new Date()
  const start = new Date()
  start.setDate(end.getDate() - days)
  return [start, end]
}

async function loadData() {
  loading.value = true
  try {
    const from = dateRange.value ? `${dateRange.value[0]}T00:00:00` : undefined
    const to = dateRange.value ? `${dateRange.value[1]}T23:59:59` : undefined
    const resp = await indicatorsApi.listMeasurements(
      props.factoryId,
      props.detail.id,
      from,
      to,
    )
    measurements.value = (resp.data ?? []) as IndicatorVersion[]
    await nextTick()
    renderChart()
  } catch (err) {
    console.error('measurements failed', err)
  } finally {
    loading.value = false
  }
}

function renderChart() {
  if (!chartRef.value) return
  if (measurements.value.length === 0) {
    chart?.dispose()
    chart = null
    return
  }
  if (!chart) {
    chart = echarts.init(chartRef.value)
  }
  const data = measurements.value.map((v) => [v.computedAt, Number(v.value)])
  // GREEN/YELLOW/RED 阈值线 (取主阈值)
  const greenThr = props.detail.thresholds.find((t) => t.alertLevel === 'GREEN')
  const yellowThr = props.detail.thresholds.find((t) => t.alertLevel === 'YELLOW')
  const redThr = props.detail.thresholds.find((t) => t.alertLevel === 'RED')
  const markLines: Array<{ yAxis: number; lineStyle: { color: string; type: string }; label: { formatter: string; position: 'middle' } }> = []
  if (greenThr) {
    markLines.push({
      yAxis: Number(greenThr.thresholdValue),
      lineStyle: { color: '#67C23A', type: 'dashed' },
      label: { formatter: `GREEN ${greenThr.thresholdValue}`, position: 'middle' },
    })
  }
  if (yellowThr && yellowThr.operator !== 'BETWEEN') {
    markLines.push({
      yAxis: Number(yellowThr.thresholdValue),
      lineStyle: { color: '#E6A23C', type: 'dashed' },
      label: { formatter: `YELLOW ${yellowThr.thresholdValue}`, position: 'middle' },
    })
  }
  if (redThr && redThr.operator !== 'BETWEEN') {
    markLines.push({
      yAxis: Number(redThr.thresholdValue),
      lineStyle: { color: '#F56C6C', type: 'dashed' },
      label: { formatter: `RED ${redThr.thresholdValue}`, position: 'middle' },
    })
  }

  chart.setOption({
    title: { text: `${props.detail.name} 趋势`, left: 'center', textStyle: { fontSize: 14 } },
    tooltip: {
      trigger: 'axis',
      formatter: (params: Array<{ axisValueLabel: string; value: [string, number] }>) => {
        const p = params[0]
        return `${p.axisValueLabel}<br/><strong>${p.value[1]}</strong> ${props.detail.unit || ''}`
      },
    },
    grid: { left: 50, right: 24, top: 50, bottom: 40 },
    xAxis: { type: 'time' },
    yAxis: {
      type: 'value',
      name: props.detail.unit || '',
      nameTextStyle: { fontSize: 11 },
    },
    dataZoom: [{ type: 'inside' }],
    series: [
      {
        name: props.detail.name,
        type: 'line',
        smooth: true,
        symbolSize: 6,
        data,
        markLine: { silent: true, symbol: 'none', data: markLines },
      },
    ],
  })
}

function levelLabel(l: AlertLevel) {
  return l === 'GREEN' ? '正常' : l === 'YELLOW' ? '黄色预警' : '红色预警'
}

function levelType(l: AlertLevel): 'success' | 'warning' | 'danger' {
  return l === 'GREEN' ? 'success' : l === 'YELLOW' ? 'warning' : 'danger'
}

function formatTs(ts: string): string {
  try {
    return new Date(ts).toLocaleString('zh-CN', { hour12: false })
  } catch {
    return ts
  }
}

// ==================== Chart Insight ====================
// LINE over time: measurements sorted by computedAt → TREND family.
// Null when <2 points (engine handles TREND_MIN_POINTS=4 guard internally).
const indicatorTrendSource = () => {
  if (measurements.value.length < 2) return null;
  const sorted = [...measurements.value].sort(
    (a, b) => new Date((a as IndicatorVersion).computedAt).getTime() - new Date((b as IndicatorVersion).computedAt).getTime(),
  );
  return {
    chart: {
      chartType: 'LINE',
      meta: { xDim: 'time' as const, yMetric: 'quantity' as const, aggregation: 'avg' as const, domain: 'factory' as const },
      config: {
        xAxis: { data: sorted.map((v) => (v as IndicatorVersion).computedAt.slice(0, 10)) },
        series: [{ type: 'line', data: sorted.map((v) => Number((v as IndicatorVersion).value)) }],
      },
    },
  };
};

const { insight: indicatorTrendInsight, loading: indicatorTrendInsightLoading } = useChartInsight(
  indicatorTrendSource,
  () => ({ canViewFinance: false }),
  { factoryId: () => props.factoryId, autoTier2: true },
);

watch(() => props.detail.id, () => loadData())

onMounted(() => {
  loadData()
})

onBeforeUnmount(() => {
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.pane {
  padding: 8px 0;
}

.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.chart-container {
  width: 100%;
  height: 340px;
  background: var(--el-bg-color);
}

.unit {
  margin-left: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.muted {
  color: var(--el-text-color-disabled);
}
</style>
