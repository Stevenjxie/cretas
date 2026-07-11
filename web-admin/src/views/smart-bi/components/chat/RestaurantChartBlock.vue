<template>
  <section class="restaurant-chart-block" aria-label="经营分析图表">
    <h4 v-if="chart.title" class="chart-block-title">{{ chart.title }}</h4>
    <p v-if="chartError" class="chart-block-error">图表暂不可用</p>
    <div v-else ref="chartRef" class="chart-block-canvas" />
  </section>
</template>

<script setup lang="ts">
/**
 * Renders one synthesis chart ({type, title, option}) via web-admin's shared
 * echarts wrapper. Mirrors mobile-rest-ai/src/components/ChartBlock.vue's
 * self-managed <div ref> + onMounted/watch lifecycle (simpler + safer than
 * AIQuery.vue's getElementById-polling approach for this small chat panel).
 */
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type { ECharts } from 'echarts/core';
import echarts from '@/utils/echarts';
import { processEChartsOptions } from '@/utils/echarts-fmt';
import type { SynthesisChart } from '@/api/smartbi/restaurant-synthesis';

const props = defineProps<{
  chart: SynthesisChart;
}>();

const chartRef = ref<HTMLDivElement | null>(null);
const chartError = ref(false);
let instance: ECharts | null = null;

function renderChart(): void {
  if (!chartRef.value) return;
  chartError.value = false;
  try {
    if (!instance) {
      instance = echarts.init(chartRef.value, 'cretas');
    }
    const resolved = processEChartsOptions(props.chart.option);
    instance.setOption(resolved as Record<string, unknown>, true);
  } catch (error) {
    console.warn('[RestaurantChartBlock] failed to render chart', error);
    chartError.value = true;
  }
}

function resizeChart(): void {
  instance?.resize();
}

onMounted(async () => {
  await nextTick();
  renderChart();
  window.addEventListener('resize', resizeChart);
});

watch(
  () => props.chart.option,
  async () => {
    await nextTick();
    renderChart();
  },
  { deep: true },
);

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeChart);
  instance?.dispose();
  instance = null;
});
</script>

<style scoped>
.restaurant-chart-block {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dotted #d4cdb8;
}
.chart-block-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 13px;
  font-weight: 600;
  color: #2d4a3e;
  margin: 0 0 8px;
}
.chart-block-error {
  font-size: 12px;
  color: #a8a29e;
  font-style: italic;
}
.chart-block-canvas {
  width: 100%;
  height: 260px;
}
</style>
