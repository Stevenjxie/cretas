<script setup lang="ts">
/**
 * MetricsTrendChart - AI 行为校准指标趋势图组件
 * 使用 ECharts 绘制多指标折线图
 */
import { ref, watch, onMounted, onUnmounted, computed } from 'vue';
import echarts from '@/utils/echarts';
import type { EChartsOption, ECharts } from 'echarts';
import type { TrendDataPoint, TimeGranularity } from '@/types/calibration';
import { useChartInsight } from '@/composables/useChartInsight';
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue';

interface Props {
  title?: string;
  data: TrendDataPoint[];
  height?: number;
  loading?: boolean;
  showGranularity?: boolean;
  defaultGranularity?: TimeGranularity;
  showLegend?: boolean;
  smooth?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  title: '',
  height: 400,
  loading: false,
  showGranularity: true,
  defaultGranularity: 'day',
  showLegend: true,
  smooth: true
});

const emit = defineEmits<{
  (e: 'granularityChange', value: TimeGranularity): void;
}>();

const chartRef = ref<HTMLDivElement | null>(null);
const chartInstance = ref<ECharts | null>(null);
let resizeObserver: ResizeObserver | null = null;
let rafId = 0;
const granularity = ref<TimeGranularity>(props.defaultGranularity);

// 指标配置
const metricsConfig = [
  { key: 'conciseness', name: '简洁性', color: '#409eff', unit: '%' },
  { key: 'successRate', name: '成功率', color: '#67c23a', unit: '%' },
  { key: 'efficiency', name: '推理效率', color: '#e6a23c', unit: '' },
  { key: 'compositeScore', name: '综合得分', color: '#f56c6c', unit: '' }
];

// 时间粒度选项
const granularityOptions = [
  { label: '小时', value: 'hour' },
  { label: '天', value: 'day' },
  { label: '周', value: 'week' },
  { label: '月', value: 'month' }
];

const chartOptions = computed<EChartsOption>(() => {
  const dates = props.data.map(d => d.date);

  const series = metricsConfig.map(config => ({
    name: config.name,
    type: 'line' as const,
    data: props.data.map(d => d[config.key as keyof TrendDataPoint] as number),
    smooth: props.smooth,
    symbol: 'circle',
    symbolSize: 6,
    lineStyle: {
      width: 2,
      color: config.color
    },
    itemStyle: {
      color: config.color
    },
    emphasis: {
      focus: 'series' as const,
      itemStyle: {
        borderWidth: 2
      }
    }
  }));

  return {
    tooltip: {
      trigger: 'axis',
      confine: true,
      backgroundColor: 'rgba(255, 255, 255, 0.95)',
      borderColor: '#ebeef5',
      borderWidth: 1,
      textStyle: {
        color: '#303133'
      },
      formatter: (params) => {
        if (!Array.isArray(params)) return '';
        const date = (params[0] as { axisValue?: string })?.axisValue || '';
        let html = `<div style="font-weight: 600; margin-bottom: 8px;">${date}</div>`;
        params.forEach((param) => {
          if (param.value !== null && param.value !== undefined) {
            const config = metricsConfig.find(c => c.name === param.seriesName);
            const unit = config?.unit || '';
            html += `
              <div style="display: flex; align-items: center; gap: 8px; margin: 4px 0;">
                <span style="display: inline-block; width: 10px; height: 10px; border-radius: 50%; background: ${param.color};"></span>
                <span>${param.seriesName}: </span>
                <span style="font-weight: 600;">${param.value}${unit}</span>
              </div>
            `;
          }
        });
        return html;
      }
    },
    legend: props.showLegend ? {
      bottom: 0,
      icon: 'circle',
      itemWidth: 10,
      itemHeight: 10,
      textStyle: {
        color: '#606266',
        fontSize: 12
      }
    } : undefined,
    grid: {
      top: 20,
      right: 20,
      bottom: props.showLegend ? 50 : 20,
      left: 60,
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: dates,
      boundaryGap: false,
      axisLine: {
        lineStyle: {
          color: '#dcdfe6'
        }
      },
      axisTick: {
        show: false
      },
      axisLabel: {
        color: '#909399',
        fontSize: 11,
        rotate: dates.length > 15 ? 45 : 0
      }
    },
    yAxis: {
      type: 'value',
      splitLine: {
        lineStyle: {
          color: '#ebeef5',
          type: 'dashed'
        }
      },
      axisLine: {
        show: false
      },
      axisTick: {
        show: false
      },
      axisLabel: {
        color: '#909399',
        fontSize: 11
      }
    },
    series
  };
});

function initChart() {
  if (!chartRef.value) return;

  chartInstance.value = echarts.init(chartRef.value, 'cretas') as unknown as ECharts;
  chartInstance.value.setOption(chartOptions.value);
}

function updateChart() {
  if (chartInstance.value) {
    chartInstance.value.setOption(chartOptions.value, true);
  }
}

function handleResize() {
  if (rafId) return;
  rafId = requestAnimationFrame(() => {
    rafId = 0;
    chartInstance.value?.resize();
  });
}

function onGranularityChange(value: TimeGranularity) {
  emit('granularityChange', value);
}

// Lifecycle
onMounted(() => {
  initChart();
  if (chartRef.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(handleResize);
    resizeObserver.observe(chartRef.value);
  }
  window.addEventListener('resize', handleResize);
});

onUnmounted(() => {
  resizeObserver?.disconnect();
  resizeObserver = null;
  if (rafId) cancelAnimationFrame(rafId);
  window.removeEventListener('resize', handleResize);
  chartInstance.value?.dispose();
});

// Watch for data changes
watch(() => props.data, updateChart, { deep: true });
watch(chartOptions, updateChart, { deep: true });

// ==================== Chart Insight ====================
// Multi-series LINE over time (4 AI calibration metrics).
// Normalise to a single TREND chart: xAxis=dates, series[0]=compositeScore (综合得分)
// used as the representative series for the insight engine.
// Null when <4 data points (TREND_MIN_POINTS guard handled internally by composable).
const metricsInsightSource = () => {
  const dates = props.data.map((d) => d.date);
  if (dates.length < 2) return null;
  return {
    chart: {
      chartType: 'LINE',
      meta: { xDim: 'time' as const, yMetric: 'quantity' as const, aggregation: 'avg' as const, domain: 'factory' as const },
      config: {
        xAxis: { data: dates },
        series: [{ type: 'line', data: props.data.map((d) => d.compositeScore as number) }],
      },
    },
  };
};

const { insight: metricsInsight, loading: metricsInsightLoading } = useChartInsight(
  metricsInsightSource,
  () => ({ canViewFinance: false }),
  { factoryId: () => '', autoTier2: true },
);

// Expose methods
defineExpose({
  resize: handleResize,
  getInstance: () => chartInstance.value
});
</script>

<template>
  <div class="metrics-trend-chart" v-loading="loading">
    <div v-if="title || showGranularity" class="chart-header">
      <h3 v-if="title">{{ title }}</h3>
      <el-radio-group
        v-if="showGranularity"
        v-model="granularity"
        size="small"
        @change="onGranularityChange"
      >
        <el-radio-button
          v-for="opt in granularityOptions"
          :key="opt.value"
          :value="opt.value"
        >
          {{ opt.label }}
        </el-radio-button>
      </el-radio-group>
    </div>
    <div class="chart-wrapper">
      <div ref="chartRef" :style="{ width: '100%', height: height + 'px' }"></div>
      <div v-if="!data || data.length === 0" class="no-data">
        <el-empty description="暂无数据" :image-size="80" />
      </div>
    </div>
    <ChartInsight :insight="metricsInsight" :loading="metricsInsightLoading" depth="detailed" />
  </div>
</template>

<style lang="scss" scoped>
.metrics-trend-chart {
  width: 100%;

  .chart-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 16px;
    padding: 0 4px;

    h3 {
      margin: 0;
      font-size: 16px;
      font-weight: 600;
      color: #303133;
    }
  }

  .chart-wrapper {
    position: relative;

    .no-data {
      position: absolute;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      background: rgba(255, 255, 255, 0.9);
    }
  }
}
</style>
