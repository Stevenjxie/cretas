<script setup lang="ts">
/**
 * SmartBI RankingChart - Horizontal Bar Chart for Rankings
 * Features: Rank display, value formatting, click-to-drill-down
 */
import { ref, watch, onMounted, onUnmounted, computed } from 'vue';
import echarts from '@/utils/echarts';
import type { EChartsOption, ECharts } from 'echarts';

// Types
export interface RankingItem {
  id: string | number;
  name: string;
  value: number;
  previousValue?: number; // For rank change calculation
  targetValue?: number; // Secondary comparison bar
  extra?: Record<string, unknown>;
}

export interface RankChangeData {
  [id: string]: number; // positive = up, negative = down, 0 = unchanged
}

interface Props {
  title?: string;
  data: RankingItem[];
  height?: number;
  maxItems?: number;
  showRank?: boolean;
  valueUnit?: string;
  barColor?: string | string[];
  showPercentage?: boolean;
  clickable?: boolean;
  loading?: boolean;
  /** Show rank change arrows vs previous period */
  showRankChange?: boolean;
  /** Show gold/silver/bronze medals for top 3 */
  showMedals?: boolean;
  /** Show secondary target bar behind main bar */
  showTargetBar?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  title: '',
  height: 400,
  maxItems: 10,
  showRank: true,
  valueUnit: '',
  barColor: '#2D8B57',
  showPercentage: false,
  clickable: true,
  showRankChange: false,
  showMedals: true,
  showTargetBar: false
});

const emit = defineEmits<{
  (e: 'itemClick', item: RankingItem): void;
}>();

const chartRef = ref<HTMLDivElement | null>(null);
const chartInstance = ref<ECharts | null>(null);
let resizeObserver: ResizeObserver | null = null;
let rafId = 0;

const MEDAL_ICONS = ['', '', ''];

// Process data for display
const processedData = computed(() => {
  const sorted = [...props.data]
    .sort((a, b) => b.value - a.value)
    .slice(0, props.maxItems);
  return sorted;
});

// Compute rank changes based on previousValue if available
const rankChanges = computed<number[]>(() => {
  if (!props.showRankChange) return [];
  const current = processedData.value;
  // Build previous ranking from previousValue
  const withPrev = current.map(d => ({ ...d, prevSort: d.previousValue ?? d.value }));
  const prevSorted = [...withPrev].sort((a, b) => b.prevSort - a.prevSort);

  return current.map((item, currRank) => {
    const prevRank = prevSorted.findIndex(p => p.id === item.id);
    if (prevRank === -1) return 0;
    return prevRank - currRank; // positive = improved rank
  });
});

const maxValue = computed(() => {
  const targetMax = props.showTargetBar
    ? Math.max(...processedData.value.map(d => d.targetValue ?? 0), 0)
    : 0;
  return Math.max(...processedData.value.map(d => d.value), targetMax, 1);
});

// Medal or rank label for Y-axis
const getRankPrefix = (index: number): string => {
  if (props.showMedals && index < 3) return MEDAL_ICONS[index] + ' ';
  if (props.showRank) return `${index + 1}. `;
  return '';
};

// Gradient colors
const getBarColor = (index: number): string => {
  if (Array.isArray(props.barColor)) {
    return props.barColor[index % props.barColor.length];
  }
  // Medal colors for top 3
  if (props.showMedals && index === 0) return '#f6c94e';
  if (props.showMedals && index === 1) return '#b0b7c3';
  if (props.showMedals && index === 2) return '#cd7f32';
  return props.barColor as string;
};

// Rank change indicator text
const getRankChangeText = (change: number): string => {
  if (change > 0) return `↑${change}`;
  if (change < 0) return `↓${Math.abs(change)}`;
  return '—';
};

const chartOptions = computed<EChartsOption>(() => {
  const data = processedData.value;
  const names = data.map((d, i) => `${getRankPrefix(i)}${d.name}`);
  const values = data.map(d => d.value);
  const targetValues = data.map(d => d.targetValue ?? 0);
  const changes = rankChanges.value;

  const options: EChartsOption = {
    tooltip: {
      trigger: 'axis',
      confine: true,
      axisPointer: {
        type: 'shadow'
      },
      backgroundColor: 'rgba(255, 255, 255, 0.95)',
      borderColor: '#ebeef5',
      borderWidth: 1,
      textStyle: {
        color: '#303133'
      },
      formatter: (params) => {
        if (!Array.isArray(params) || params.length === 0) return '';
        const param = params[0];
        const reversedIdx = data.length - 1 - (param.dataIndex as number);
        const item = data[reversedIdx];
        if (!item) return '';
        const percentage = ((item.value / maxValue.value) * 100).toFixed(1);
        const changeText = props.showRankChange && changes[reversedIdx] !== undefined
          ? getRankChangeText(changes[reversedIdx])
          : '';
        const changeColor = (changes[reversedIdx] ?? 0) > 0 ? '#67c23a' : (changes[reversedIdx] ?? 0) < 0 ? '#f56c6c' : '#909399';
        return `
          <div style="font-weight: 600; margin-bottom:4px;">${item.name}</div>
          <div style="margin-top: 4px; display:flex; flex-direction:column; gap:2px;">
            <div>
              <span style="display: inline-block; width: 10px; height: 10px; border-radius: 50%; background: ${param.color}; margin-right: 8px;"></span>
              ${item.value.toLocaleString()}${props.valueUnit}
            </div>
            <div style="color:#909399;">占比: <span style="font-weight:600;color:#e6a23c;">${percentage}%</span></div>
            ${changeText ? `<div style="color:${changeColor}; font-weight:600;">排名变化: ${changeText}</div>` : ''}
            ${item.targetValue !== undefined ? `<div style="color:#909399;">目标: ${item.targetValue.toLocaleString()}${props.valueUnit}</div>` : ''}
          </div>
        `;
      }
    },
    grid: {
      top: 10,
      right: 100,
      bottom: 10,
      left: 10,
      containLabel: true
    },
    xAxis: {
      type: 'value',
      max: maxValue.value,
      show: false
    },
    yAxis: {
      type: 'category',
      data: names.reverse(),
      inverse: false,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: {
        color: '#606266',
        fontSize: 13,
        formatter: (value: string) => {
          return value.length > 18 ? value.slice(0, 18) + '...' : value;
        }
      }
    },
    series: [
      // Target bar (secondary, lighter, behind)
      ...(props.showTargetBar
        ? [{
            type: 'bar' as const,
            name: '目标',
            data: targetValues.slice().reverse().map((v) => ({
              value: v,
              itemStyle: {
                color: 'rgba(144, 147, 153, 0.2)',
                borderRadius: [0, 4, 4, 0]
              }
            })),
            barWidth: '60%',
            barGap: '-100%',
            label: { show: false },
            emphasis: { disabled: true }
          }]
        : []),
      // Main value bar
      {
        type: 'bar',
        name: '数值',
        data: values.reverse().map((v, i) => ({
          value: v,
          itemStyle: {
            color: new (echarts as unknown as { graphic: { LinearGradient: new (x0: number, y0: number, x1: number, y1: number, stops: { offset: number; color: string }[]) => object } }).graphic.LinearGradient(0, 0, 1, 0, [
              { offset: 0, color: getBarColor(data.length - 1 - i) + 'aa' },
              { offset: 1, color: getBarColor(data.length - 1 - i) }
            ]),
            borderRadius: [0, 4, 4, 0]
          }
        })),
        barWidth: '60%',
        label: {
          show: true,
          position: 'right',
          color: '#606266',
          fontSize: 12,
          formatter: (params) => {
            const value = params.value as number;
            const reversedIdx = data.length - 1 - (params.dataIndex as number);
            const pctStr = props.showPercentage
              ? ` (${((value / maxValue.value) * 100).toFixed(1)}%)`
              : '';
            const changeStr = props.showRankChange && changes[reversedIdx] !== undefined
              ? ` ${getRankChangeText(changes[reversedIdx])}`
              : '';
            return `${value.toLocaleString()}${props.valueUnit}${pctStr}${changeStr}`;
          }
        },
        emphasis: {
          itemStyle: {
            shadowBlur: 12,
            shadowColor: 'rgba(0, 0, 0, 0.25)'
          }
        }
      }
    ]
  };

  return options;
});

function initChart() {
  if (!chartRef.value) return;

  chartInstance.value = echarts.init(chartRef.value, 'cretas') as unknown as ECharts;
  chartInstance.value.setOption(chartOptions.value);

  // Click event
  if (props.clickable) {
    chartInstance.value.on('click', (params) => {
      if (params.componentType === 'series' && params.dataIndex !== undefined && params.seriesName !== '目标') {
        const reversedIndex = processedData.value.length - 1 - (params.dataIndex as number);
        const item = processedData.value[reversedIndex];
        if (item) {
          emit('itemClick', item);
        }
      }
    });
  }
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

// Expose methods
defineExpose({
  resize: handleResize,
  getInstance: () => chartInstance.value
});
</script>

<template>
  <div v-loading="loading" class="ranking-chart">
    <div v-if="title" class="chart-header">
      <h3>{{ title }}</h3>
      <el-tag v-if="maxItems < data.length" type="info" size="small">
        Top {{ maxItems }}
      </el-tag>
    </div>
    <div class="chart-wrapper" :style="{ position: 'relative', width: '100%', height: height + 'px' }">
      <div ref="chartRef" role="img" :aria-label="title || '排行图'" style="width: 100%; height: 100%"></div>
      <div v-if="!loading && data.length === 0" class="chart-empty">
        <el-empty description="暂无数据" :image-size="80" />
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.ranking-chart {
  width: 100%;

  .chart-empty {
    position: absolute;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
  }

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
}
</style>
