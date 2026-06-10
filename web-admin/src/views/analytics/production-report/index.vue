<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import echarts from '@/utils/echarts';
import { ElMessage } from 'element-plus';
import { FullScreen, Refresh } from '@element-plus/icons-vue';
import { useChartInsight } from '@/composables/useChartInsight';
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue';

interface ProductionData {
  productTypeId: string;
  productName: string;
  totalQuantity: number;
  unit: string;
}

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const loading = ref(false);
const isFullscreen = ref(false);
let refreshTimer: ReturnType<typeof setInterval> | null = null;

function toggleFullscreen() {
  if (!isFullscreen.value) {
    document.documentElement.requestFullscreen?.();
    isFullscreen.value = true;
    refreshTimer = setInterval(() => { loadProductionData(); }, 30000);
  } else {
    document.exitFullscreen?.();
    isFullscreen.value = false;
    if (refreshTimer) { clearInterval(refreshTimer); refreshTimer = null; }
  }
}

function onFullscreenChange() {
  if (!document.fullscreenElement) {
    isFullscreen.value = false;
    if (refreshTimer) { clearInterval(refreshTimer); refreshTimer = null; }
  }
}

const selectedPeriod = ref('today');
const customDateRange = ref<[Date, Date] | null>(null);
const productionData = ref<ProductionData[]>([]);

// 图表实例
let productionChart: echarts.ECharts | null = null;

const periodOptions = [
  { label: '今日', value: 'today' },
  { label: '本周', value: 'week' },
  { label: '本月', value: 'month' },
  { label: '自定义', value: 'custom' }
];

// 计算日期范围
function getDateRange(): { startDate: string; endDate: string } {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const formatDate = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

  if (selectedPeriod.value === 'custom' && customDateRange.value) {
    return {
      startDate: formatDate(customDateRange.value[0]),
      endDate: formatDate(customDateRange.value[1])
    };
  }

  let startDate: Date;
  const endDate = now;

  switch (selectedPeriod.value) {
    case 'today':
      startDate = new Date(now.getFullYear(), now.getMonth(), now.getDate());
      break;
    case 'week':
      startDate = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);
      break;
    case 'month':
      startDate = new Date(now.getFullYear(), now.getMonth(), 1);
      break;
    default:
      startDate = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  }

  return {
    startDate: formatDate(startDate),
    endDate: formatDate(endDate)
  };
}

let resizeObserver: ResizeObserver | null = null;

onMounted(async () => {
  await nextTick();
  initChart();
  loadProductionData();
  const chartEl = document.getElementById('production-bar-chart');
  if (chartEl && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(handleResize);
    resizeObserver.observe(chartEl);
  }
  window.addEventListener('resize', handleResize);
  document.addEventListener('fullscreenchange', onFullscreenChange);
});

onUnmounted(() => {
  resizeObserver?.disconnect();
  if (resizeRaf) cancelAnimationFrame(resizeRaf);
  window.removeEventListener('resize', handleResize);
  productionChart?.dispose();
  if (refreshTimer) clearInterval(refreshTimer);
  document.removeEventListener('fullscreenchange', onFullscreenChange);
});

async function loadProductionData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const { startDate, endDate } = getDateRange();
    const response = await get(`/${factoryId.value}/reports/production-by-product`, {
      params: { startDate, endDate }
    });
    if (response.success && response.data) {
      // 按产量从大到小排序
      productionData.value = (response.data as ProductionData[]).sort(
        (a, b) => b.totalQuantity - a.totalQuantity
      );
      updateChart();
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载生产数据失败');
    }
  } catch (error) {
    console.error('加载生产数据失败:', error);
    ElMessage.error('加载生产数据失败，请重试');
  } finally {
    loading.value = false;
  }
}

function initChart() {
  const chartEl = document.getElementById('production-bar-chart');
  if (chartEl) {
    productionChart = echarts.init(chartEl, 'cretas');
  }
}

function updateChart() {
  if (!productionChart) return;

  const data = productionData.value;
  // 反转数据，让最大值在顶部
  const reversedData = [...data].reverse();
  const productNames = reversedData.map(d => d.productName);
  const quantities = reversedData.map(d => d.totalQuantity);
  const units = reversedData.map(d => d.unit);

  // 动态计算图表高度，确保每个条形有足够空间
  const barHeight = 40;
  const minHeight = 350;
  const calculatedHeight = Math.max(minHeight, data.length * barHeight + 100);

  const chartEl = document.getElementById('production-bar-chart');
  if (chartEl) {
    chartEl.style.height = `${calculatedHeight}px`;
    productionChart.resize();
  }

  productionChart.setOption({
    title: {
      text: '各产品生产数量统计',
      left: 'center',
      textStyle: {
        fontSize: 16,
        fontWeight: 600
      }
    },
    tooltip: {
      trigger: 'axis',
      confine: true,
      axisPointer: { type: 'shadow' },
      formatter: (params: Record<string, unknown>[]) => {
        const item = params[0] as Record<string, unknown>;
        const unit = units[item.dataIndex as number] || '件';
        return `${item.name}<br/>产量: ${item.value} ${unit}`;
      }
    },
    grid: {
      left: '3%',
      right: '10%',
      bottom: '3%',
      top: '50px',
      containLabel: true
    },
    xAxis: {
      type: 'value',
      name: '数量',
      axisLabel: {
        formatter: (value: number) => {
          if (value >= 10000) {
            return (value / 10000).toFixed(1) + 'w';
          }
          return value.toString();
        }
      }
    },
    yAxis: {
      type: 'category',
      data: productNames,
      axisLabel: {
        interval: 0,
        width: 120,
        overflow: 'truncate',
        ellipsis: '...'
      }
    },
    series: [{
      name: '产量',
      type: 'bar',
      data: quantities,
      barWidth: '60%',
      itemStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
          { offset: 0, color: '#6B8E23' },  // 橄榄绿
          { offset: 1, color: '#9ACD32' }   // 黄绿色
        ]),
        borderRadius: [0, 4, 4, 0]
      },
      label: {
        show: true,
        position: 'right',
        formatter: (params: Record<string, unknown>) => {
          const unit = units[params.dataIndex as number] || '件';
          return `${params.value} ${unit}`;
        },
        fontSize: 12,
        color: '#606266'
      }
    }]
  });
}

// ==================== Chart Insight ====================
// BAR chart: products ranked by production quantity → RANKING family.
// Source uses productionData sorted desc (already sorted on load).
const productionInsightSource = () => {
  if (productionData.value.length < 2) return null;
  return {
    chart: {
      chartType: 'BAR',
      meta: { xDim: 'category' as const, yMetric: 'quantity' as const, aggregation: 'sum' as const, domain: 'factory' as const },
      config: {
        xAxis: { data: productionData.value.map((d) => d.productName) },
        series: [{ type: 'bar', data: productionData.value.map((d) => d.totalQuantity) }],
      },
    },
  };
};

const { insight: productionInsight, loading: productionInsightLoading } = useChartInsight(
  productionInsightSource,
  () => ({ canViewFinance: false }),
  { factoryId: () => factoryId.value ?? '', autoTier2: true },
);

let resizeRaf = 0;
function handleResize() {
  if (resizeRaf) return;
  resizeRaf = requestAnimationFrame(() => {
    resizeRaf = 0;
    productionChart?.resize();
  });
}

function handlePeriodChange() {
  if (selectedPeriod.value !== 'custom') {
    loadProductionData();
  }
}

function handleDateRangeChange() {
  if (customDateRange.value) {
    loadProductionData();
  }
}

function handleRefresh() {
  loadProductionData();
}
</script>

<template>
  <div class="production-report-page" :class="{ 'fullscreen-mode': isFullscreen }">
    <div class="page-header">
      <div class="header-left">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item :to="{ path: '/analytics' }">数据分析</el-breadcrumb-item>
          <el-breadcrumb-item>生产报表</el-breadcrumb-item>
        </el-breadcrumb>
        <h1>车间实时生产报表</h1>
      </div>
      <div class="header-right">
        <el-select
          v-model="selectedPeriod"
          style="width: 120px; margin-right: 12px;"
          @change="handlePeriodChange"
        >
          <el-option
            v-for="opt in periodOptions"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <el-date-picker
          v-if="selectedPeriod === 'custom'"
          v-model="customDateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          style="width: 240px; margin-right: 12px;"
          @change="handleDateRangeChange"
        />
        <el-button type="primary" @click="handleRefresh">
          <el-icon style="margin-right: 4px;"><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button :icon="FullScreen" @click="toggleFullscreen" style="margin-left: 8px;">
          {{ isFullscreen ? '退出全屏' : '全屏投影' }}
        </el-button>
      </div>
    </div>

    <div class="charts-container" v-loading="loading">
      <el-card class="chart-card">
        <template #header>
          <div class="card-header">
            <span>产品生产统计</span>
            <el-tag type="info" size="small">
              共 {{ productionData.length }} 种产品
            </el-tag>
          </div>
        </template>
        <div v-if="productionData.length === 0 && !loading" class="empty-state">
          <el-empty description="暂无生产数据" />
        </div>
        <div v-else id="production-bar-chart" class="chart"></div>
        <!-- U6: useChartInsight — RANKING insight on production quantities -->
        <ChartInsight :insight="productionInsight" :loading="productionInsightLoading" depth="detailed" />
      </el-card>

      <!-- 数据汇总卡片 -->
      <el-row :gutter="16" style="margin-top: 16px;">
        <el-col :span="8">
          <el-card class="summary-card">
            <div class="summary-item">
              <div class="summary-label">产品种类</div>
              <div class="summary-value">{{ productionData.length }}</div>
            </div>
          </el-card>
        </el-col>
        <el-col :span="8">
          <el-card class="summary-card">
            <div class="summary-item">
              <div class="summary-label">总产量</div>
              <div class="summary-value">
                {{ productionData.reduce((sum, d) => sum + d.totalQuantity, 0).toLocaleString() }}
              </div>
            </div>
          </el-card>
        </el-col>
        <el-col :span="8">
          <el-card class="summary-card">
            <div class="summary-item">
              <div class="summary-label">最高产量产品</div>
              <div class="summary-value highlight">
                {{ productionData[0]?.productName || '-' }}
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.production-report-page {
  padding: 20px;
}

.fullscreen-mode {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 9999;
  background: #1a1a2e;
  color: #e0e0e0;
  overflow: auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 24px;

  .header-left {
    h1 {
      margin: 12px 0 0;
      font-size: 20px;
      font-weight: 600;
    }
  }

  .header-right {
    display: flex;
    align-items: center;
  }
}

.charts-container {
  min-height: 400px;
}

.chart-card {
  border-radius: 8px;

  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .chart {
    min-height: 350px;
    width: 100%;
  }

  .empty-state {
    height: 350px;
    display: flex;
    align-items: center;
    justify-content: center;
  }
}

.summary-card {
  border-radius: 8px;

  .summary-item {
    text-align: center;
    padding: 8px 0;

    .summary-label {
      font-size: 14px;
      color: #909399;
      margin-bottom: 8px;
    }

    .summary-value {
      font-size: 24px;
      font-weight: 600;
      color: #303133;

      &.highlight {
        font-size: 16px;
        color: #6B8E23;
      }
    }
  }
}
</style>
