<script setup lang="ts">
/**
 * SmartBI SankeyChart - Power BI-level Sankey Flow Diagram
 * Features: Gradient flow lines, rich tooltips, node labels, emphasis, draggable nodes
 */
import { ref, watch, onMounted, onUnmounted, computed } from 'vue';
import echarts from '@/utils/echarts';
import type { EChartsOption, ECharts } from 'echarts';
import { processEChartsOptions } from '@/utils/echarts-fmt';

// Types
export interface SankeyNode {
  name: string;
  value?: number;
  itemStyle?: { color: string };
  depth?: number;
}

export interface SankeyLink {
  source: string;
  target: string;
  value: number;
  lineStyle?: { color?: string; opacity?: number };
}

interface Props {
  title?: string;
  nodes: SankeyNode[];
  links: SankeyLink[];
  height?: string;
  loading?: boolean;
  orient?: 'horizontal' | 'vertical';
  draggable?: boolean;
  showLabels?: boolean;
  labelPosition?: 'left' | 'right' | 'inside';
  colorMode?: 'source' | 'target' | 'gradient';
  highlightMode?: 'adjacency' | 'none';
  unit?: string;
  echartsOption?: Record<string, unknown>;
}

const props = withDefaults(defineProps<Props>(), {
  title: '',
  height: '500px',
  loading: false,
  orient: 'horizontal',
  draggable: true,
  showLabels: true,
  labelPosition: 'right',
  colorMode: 'gradient',
  highlightMode: 'adjacency',
  unit: '',
});

const emit = defineEmits<{
  (e: 'nodeClick', node: SankeyNode): void;
  (e: 'linkClick', link: SankeyLink): void;
}>();

const chartRef = ref<HTMLDivElement | null>(null);
const chartInstance = ref<ECharts | null>(null);
let resizeObserver: ResizeObserver | null = null;
let rafId = 0;

// Color palette - assigned by node depth
const PALETTE = ['#2D8B57', '#36B37E', '#FFAB00', '#FF5630', '#6B778C', '#00B8D9', '#6554C0', '#FF8B00'];

// Compute node depth map for color assignment
const nodeDepthMap = computed(() => {
  const map = new Map<string, number>();
  props.nodes.forEach((n) => {
    if (n.depth !== undefined) {
      map.set(n.name, n.depth);
    }
  });
  // If no depth info, infer from links
  if (map.size === 0) {
    const inDegree = new Map<string, number>();
    const outDegree = new Map<string, number>();
    props.nodes.forEach((n) => {
      inDegree.set(n.name, 0);
      outDegree.set(n.name, 0);
    });
    props.links.forEach((l) => {
      inDegree.set(l.target, (inDegree.get(l.target) || 0) + 1);
      outDegree.set(l.source, (outDegree.get(l.source) || 0) + 1);
    });
    // BFS to assign depth
    const queue: string[] = [];
    props.nodes.forEach((n) => {
      if ((inDegree.get(n.name) || 0) === 0) {
        queue.push(n.name);
        map.set(n.name, 0);
      }
    });
    while (queue.length > 0) {
      const curr = queue.shift()!;
      const currDepth = map.get(curr) || 0;
      props.links.forEach((l) => {
        if (l.source === curr && !map.has(l.target)) {
          map.set(l.target, currDepth + 1);
          queue.push(l.target);
        }
      });
    }
  }
  return map;
});

// Assign colors to nodes
const nodeColorMap = computed(() => {
  const map = new Map<string, string>();
  const depthColorMap = new Map<number, string>();
  let colorIndex = 0;
  props.nodes.forEach((n) => {
    if (n.itemStyle?.color) {
      map.set(n.name, n.itemStyle.color);
    } else {
      const depth = nodeDepthMap.value.get(n.name) || 0;
      if (!depthColorMap.has(depth)) {
        depthColorMap.set(depth, PALETTE[colorIndex % PALETTE.length]);
        colorIndex++;
      }
      map.set(n.name, depthColorMap.get(depth)!);
    }
  });
  return map;
});

// KPI summary
const kpiSummary = computed(() => {
  const totalFlow = props.links.reduce((sum, l) => sum + l.value, 0);
  const largestFlow = props.links.reduce((max, l) => (l.value > max.value ? l : max), props.links[0]);
  return {
    totalFlow,
    largestFlow: largestFlow ? `${largestFlow.source}→${largestFlow.target}` : '-',
    largestFlowValue: largestFlow?.value || 0,
    pathCount: props.links.length,
  };
});

// Flow aggregation per node
const nodeFlowMap = computed(() => {
  const inflow = new Map<string, number>();
  const outflow = new Map<string, number>();
  const inflowCount = new Map<string, number>();
  const outflowCount = new Map<string, number>();
  props.links.forEach((l) => {
    inflow.set(l.target, (inflow.get(l.target) || 0) + l.value);
    outflow.set(l.source, (outflow.get(l.source) || 0) + l.value);
    inflowCount.set(l.target, (inflowCount.get(l.target) || 0) + 1);
    outflowCount.set(l.source, (outflowCount.get(l.source) || 0) + 1);
  });
  return { inflow, outflow, inflowCount, outflowCount };
});

function formatValue(v: number): string {
  if (v >= 10000) return (v / 10000).toFixed(1) + '万';
  if (v >= 1000) return (v / 1000).toFixed(1) + '千';
  return v.toFixed(0);
}

const chartOptions = computed<EChartsOption>(() => {
  if (props.echartsOption && Object.keys(props.echartsOption).length > 0) {
    return processEChartsOptions(props.echartsOption) as EChartsOption;
  }

  const { inflow, outflow, inflowCount, outflowCount } = nodeFlowMap.value;

  const dedupedNodes = Array.from(props.nodes.reduce((map, node) => {
    const existing = map.get(node.name);
    if (existing) {
      existing.value = (existing.value || 0) + (node.value || 0);
      existing.depth = existing.depth ?? node.depth;
      return map;
    }
    map.set(node.name, { ...node });
    return map;
  }, new Map<string, SankeyNode>()).values());

  // Build colored nodes
  const coloredNodes = dedupedNodes.map((n) => ({
    name: n.name,
    value: n.value,
    depth: n.depth,
    itemStyle: {
      color: nodeColorMap.value.get(n.name) || PALETTE[0],
      borderColor: nodeColorMap.value.get(n.name) || PALETTE[0],
    },
  }));

  // Build links with gradient coloring
  const coloredLinks = props.links.map((l) => {
    const srcColor = nodeColorMap.value.get(l.source) || PALETTE[0];
    const tgtColor = nodeColorMap.value.get(l.target) || PALETTE[1];
    let lineStyle: Record<string, unknown> = {
      opacity: l.lineStyle?.opacity ?? 0.4,
    };
    if (props.colorMode === 'gradient') {
      lineStyle.color = {
        type: 'linear',
        x: 0, y: 0, x2: 1, y2: 0,
        colorStops: [
          { offset: 0, color: srcColor },
          { offset: 1, color: tgtColor },
        ],
      };
    } else if (props.colorMode === 'source') {
      lineStyle.color = srcColor;
    } else {
      lineStyle.color = tgtColor;
    }
    if (l.lineStyle?.color) lineStyle.color = l.lineStyle.color;
    return {
      source: l.source,
      target: l.target,
      value: l.value,
      lineStyle,
    };
  });

  const totalFlow = kpiSummary.value.totalFlow;

  const options: EChartsOption = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'item',
      confine: true,
      backgroundColor: 'rgba(255, 255, 255, 0.97)',
      borderColor: '#ebeef5',
      borderWidth: 1,
      textStyle: { color: '#303133', fontSize: 13 },
      formatter: ((rawParams: unknown) => {
        const params = rawParams as Record<string, unknown>;
        const p = params as { dataType?: string; data?: Record<string, unknown>; name?: string; value?: number };
        if (p.dataType === 'node') {
          const name = p.data?.name as string;
          const inflowVal = inflow.get(name) || 0;
          const outflowVal = outflow.get(name) || 0;
          const netVal = inflowVal - outflowVal;
          const iCount = inflowCount.get(name) || 0;
          const oCount = outflowCount.get(name) || 0;
          const color = nodeColorMap.value.get(name) || PALETTE[0];
          return `
            <div style="font-weight:700;font-size:14px;margin-bottom:8px;border-bottom:2px solid ${color};padding-bottom:4px;">${name}</div>
            <div style="line-height:1.8;">
              <span style="color:#909399;">流入：</span><b>${formatValue(inflowVal)}${props.unit}</b> <span style="color:#c0c4cc;">(${iCount}条)</span><br/>
              <span style="color:#909399;">流出：</span><b>${formatValue(outflowVal)}${props.unit}</b> <span style="color:#c0c4cc;">(${oCount}条)</span><br/>
              <span style="color:#909399;">净额：</span><b style="color:${netVal >= 0 ? '#36B37E' : '#FF5630'}">${netVal >= 0 ? '+' : ''}${formatValue(netVal)}${props.unit}</b>
            </div>`;
        } else if (p.dataType === 'edge') {
          const d = p.data as { source?: string; target?: string; value?: number };
          const srcTotal = outflow.get(d.source || '') || 1;
          const pct = ((d.value || 0) / srcTotal * 100).toFixed(1);
          return `
            <div style="font-weight:700;font-size:14px;margin-bottom:6px;">${d.source} → ${d.target}</div>
            <div style="line-height:1.8;">
              <span style="color:#909399;">流量：</span><b>${formatValue(d.value || 0)}${props.unit}</b><br/>
              <span style="color:#909399;">占来源：</span><b style="color:#2D8B57;">${pct}%</b><br/>
              <span style="color:#909399;">占总量：</span><b style="color:#6B778C;">${((d.value || 0) / Math.max(totalFlow, 1) * 100).toFixed(1)}%</b>
            </div>`;
        }
        return String(p.name || '');
      }) as never,
    },
    series: [
      {
        type: 'sankey',
        // layout: 'none' isn't in echarts type defs but accepted at runtime
        ...{ layout: 'none' as const },
        orient: props.orient,
        draggable: props.draggable,
        nodeAlign: 'justify',
        nodeGap: 16,
        nodeWidth: 24,
        left: '6%',
        right: '6%',
        top: '5%',
        bottom: '5%',
        data: coloredNodes,
        links: coloredLinks,
        label: (props.showLabels
          ? {
              show: true,
              position: props.labelPosition,
              fontSize: 12,
              color: '#303133',
              fontWeight: 500,
              formatter: (rawParams: unknown) => {
                const params = rawParams as Record<string, unknown>;
                const name = params.name as string;
                const nodeVal = (props.nodes.find((n) => n.name === name)?.value) ||
                  outflow.get(name) || inflow.get(name) || 0;
                const pct = totalFlow > 0 ? (nodeVal / totalFlow * 100).toFixed(0) : '0';
                return `{name|${name}}\n{val|${formatValue(nodeVal)}${props.unit} · ${pct}%}`;
              },
              rich: {
                name: { fontSize: 12, fontWeight: 600, color: '#303133' },
                val: { fontSize: 11, color: '#909399' },
              },
            }
          : { show: false }) as never,
        lineStyle: { curveness: 0.5 },
        emphasis: {
          focus: props.highlightMode === 'adjacency' ? 'adjacency' : 'none',
          lineStyle: { opacity: 0.8 },
          label: { fontSize: 13, fontWeight: 700 },
        },
        itemStyle: { borderWidth: 1 },
      },
    ],
  };

  return options;
});

function initChart() {
  if (!chartRef.value) return;
  chartInstance.value = echarts.init(chartRef.value, 'cretas') as unknown as ECharts;
  chartInstance.value.setOption(chartOptions.value);

  chartInstance.value.on('click', (params: Record<string, unknown>) => {
    const p = params as { dataType?: string; data?: Record<string, unknown> };
    if (p.dataType === 'node') {
      const nodeName = p.data?.name as string;
      const node = props.nodes.find((n) => n.name === nodeName);
      if (node) emit('nodeClick', node);
    } else if (p.dataType === 'edge') {
      const d = p.data as { source?: string; target?: string; value?: number };
      const link = props.links.find((l) => l.source === d.source && l.target === d.target);
      if (link) emit('linkClick', link);
    }
  });
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

watch(() => props.nodes, updateChart, { deep: true });
watch(() => props.links, updateChart, { deep: true });
watch(chartOptions, updateChart, { deep: true });

defineExpose({
  chartInstance,
  resize: handleResize,
  getInstance: () => chartInstance.value,
});
</script>

<template>
  <div v-loading="loading" class="sankey-chart">
    <div v-if="title" class="chart-header">
      <h3>{{ title }}</h3>
    </div>

    <!-- KPI Summary Row -->
    <div v-if="nodes.length > 0" class="kpi-row">
      <div class="kpi-item">
        <span class="kpi-label">总流量</span>
        <span class="kpi-value">{{ formatValue(kpiSummary.totalFlow) }}{{ unit }}</span>
      </div>
      <div class="kpi-item">
        <span class="kpi-label">最大流向</span>
        <span class="kpi-value kpi-value--highlight">{{ kpiSummary.largestFlow }}</span>
      </div>
      <div class="kpi-item">
        <span class="kpi-label">最大流量</span>
        <span class="kpi-value">{{ formatValue(kpiSummary.largestFlowValue) }}{{ unit }}</span>
      </div>
      <div class="kpi-item">
        <span class="kpi-label">路径数</span>
        <span class="kpi-value">{{ kpiSummary.pathCount }}</span>
      </div>
    </div>

    <div class="chart-wrapper" :style="{ position: 'relative', width: '100%', height }">
      <div
        ref="chartRef"
        role="img"
        :aria-label="title || '桑基图'"
        style="width: 100%; height: 100%"
      ></div>
      <div v-if="!loading && nodes.length === 0 && !(echartsOption && Object.keys(echartsOption).length > 0)" class="chart-empty">
        <el-empty description="暂无数据" :image-size="80" />
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.sankey-chart {
  width: 100%;

  .chart-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;
    padding: 0 4px;

    h3 {
      margin: 0;
      font-size: 16px;
      font-weight: 600;
      color: #303133;
    }
  }

  .kpi-row {
    display: flex;
    gap: 0;
    margin-bottom: 12px;
    background: #f5f7fa;
    border-radius: 8px;
    overflow: hidden;
    border: 1px solid #ebeef5;

    .kpi-item {
      flex: 1;
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 10px 8px;
      border-right: 1px solid #ebeef5;

      &:last-child {
        border-right: none;
      }
    }

    .kpi-label {
      font-size: 11px;
      color: #909399;
      margin-bottom: 4px;
      white-space: nowrap;
    }

    .kpi-value {
      font-size: 14px;
      font-weight: 700;
      color: #303133;

      &--highlight {
        font-size: 12px;
        color: #2D8B57;
        font-weight: 600;
      }
    }
  }

  .chart-wrapper {
    position: relative;
  }

  .chart-empty {
    position: absolute;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
  }
}
</style>
