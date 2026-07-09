<template>
  <section class="chart-card" aria-label="经营分析图表">
    <h3 v-if="chart.title" class="chart-title">{{ chart.title }}</h3>
    <div class="chart-scroll">
      <div ref="chartRef" class="chart-canvas" />
    </div>
  </section>
</template>

<script setup lang="ts">
import { init, type ECharts, type EChartsOption } from 'echarts'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { ChartPayload } from '../types'

const props = defineProps<{
  chart: ChartPayload
}>()

const chartRef = ref<HTMLDivElement | null>(null)
let instance: ECharts | null = null

function renderChart(): void {
  if (!chartRef.value) return
  if (!instance) {
    instance = init(chartRef.value, 'default')
  }
  instance.setOption(props.chart.option as EChartsOption, true)
}

function resizeChart(): void {
  instance?.resize()
}

onMounted(async () => {
  await nextTick()
  renderChart()
  window.addEventListener('resize', resizeChart)
})

watch(
  () => props.chart.option,
  async () => {
    await nextTick()
    renderChart()
  },
  { deep: true },
)

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeChart)
  instance?.dispose()
  instance = null
})
</script>
