<script setup lang="ts">
/**
 * 部门驾驶舱的趋势图（③ 区）。
 *
 * 沿用仓里既有的 echarts 用法（`@/utils/echarts` 共享实例 + init/watch/resize/dispose），
 * 不另造封装。
 *
 * 硬要求：**图表必须带 标题 · 单位 · 期间 · 一句话结论**。只给一条曲线让人自己看
 * 是把解读成本推给读者；那句结论由 `trendTakeaway()` 从数据算出来，不是写死的话术。
 */
import { computed, onMounted, onUnmounted, ref, watch, nextTick } from 'vue';
import echarts from '@/utils/echarts';
import type { ECharts } from 'echarts';
import { trendTakeaway, type TrendPoint } from './trendTakeaway';

const props = defineProps<{
  title: string;
  unit: string;
  points: TrendPoint[];
  loading?: boolean;
  money?: boolean;
  /** 无价格权限时不渲染金额曲线 —— 画一条全是 0 的线比不画更误导 */
  masked?: boolean;
  color?: string;
}>();

const el = ref<HTMLDivElement | null>(null);
let chart: ECharts | null = null;
let ro: ResizeObserver | null = null;

const periodText = computed(() => {
  if (!props.points.length) return '';
  return `${props.points[0].date} 至 ${props.points[props.points.length - 1].date}`;
});

const takeaway = computed(() => trendTakeaway(props.points, {
  unit: props.unit,
  money: props.money,
}));

function render() {
  if (!el.value || props.masked) return;
  if (!chart) chart = echarts.init(el.value);
  chart.setOption({
    grid: { left: 8, right: 12, top: 16, bottom: 4, containLabel: true },
    tooltip: {
      trigger: 'axis',
      valueFormatter: (v: unknown) =>
        `${props.money ? '¥' : ''}${Number(v ?? 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}`,
    },
    xAxis: {
      type: 'category',
      data: props.points.map((p) => p.date),
      axisLabel: { fontSize: 11, color: '#7A8599' },
      axisLine: { lineStyle: { color: '#EDF2F7' } },
    },
    yAxis: {
      type: 'value',
      axisLabel: { fontSize: 11, color: '#7A8599' },
      splitLine: { lineStyle: { color: '#EDF2F7' } },
    },
    series: [{
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: props.points.map((p) => p.value),
      lineStyle: { width: 2, color: props.color || '#1B65A8' },
      areaStyle: { opacity: 0.08, color: props.color || '#1B65A8' },
    }],
  }, true);
}

function resize() { chart?.resize(); }

onMounted(async () => {
  await nextTick();
  render();
  if (el.value) {
    ro = new ResizeObserver(resize);
    ro.observe(el.value);
  }
});

onUnmounted(() => {
  ro?.disconnect();
  ro = null;
  chart?.dispose();
  chart = null;
});

watch(() => [props.points, props.masked], async () => {
  await nextTick();
  if (props.masked) {
    chart?.dispose();
    chart = null;
    return;
  }
  render();
}, { deep: true });
</script>

<template>
  <div class="trend-block">
    <div class="trend-head">
      <span class="trend-title">{{ title }}</span>
      <span class="trend-meta">
        单位：{{ unit }}<template v-if="periodText"> · {{ periodText }}</template>
      </span>
    </div>

    <div v-if="masked" class="trend-masked">
      当前角色无价格查看权限，金额趋势不予展示
    </div>
    <template v-else>
      <div class="trend-takeaway">{{ takeaway }}</div>
      <div ref="el" class="trend-canvas" v-loading="loading"></div>
    </template>
  </div>
</template>

<style lang="scss" scoped>
.trend-block { display: flex; flex-direction: column; gap: 8px; }
.trend-head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.trend-title { font-size: 15px; font-weight: 600; }
.trend-meta {
  font-size: 11px; color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}
.trend-takeaway { font-size: 13px; color: var(--el-text-color-primary); }
.trend-canvas { width: 100%; height: 220px; }
.trend-masked {
  font-size: 13px; color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-radius: 8px; padding: 24px; text-align: center;
}
</style>
