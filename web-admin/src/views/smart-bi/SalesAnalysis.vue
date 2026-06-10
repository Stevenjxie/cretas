<script setup lang="ts">
/**
 * SmartBI 销售分析页面
 * 提供销售数据的多维度分析，包含筛选、KPI、排行榜和图表
 * Phase 6: Enhanced with DynamicChartRenderer + ChartTypeSelector
 */
import { ref, computed, onMounted, watch, onUnmounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { formatNumber, formatCount, formatAxisValue } from '@/utils/format-number';
import { toApiDateString } from '@/utils/dateFormat';
import { ElMessage } from 'element-plus';
import {
  Refresh,
  TrendCharts,
  User,
  Calendar,
  Filter,
  Download,
  Document,
  View
} from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import DynamicChartRenderer from '@/components/smartbi/DynamicChartRenderer.vue';
import ChartTypeSelector from '@/components/smartbi/ChartTypeSelector.vue';
import SmartBIEmptyState from '@/components/smartbi/SmartBIEmptyState.vue';
import RestaurantPhaseIIPlaceholder from '@/components/smartbi/RestaurantPhaseIIPlaceholder.vue';
import RestaurantSalesContent from '@/components/smartbi/RestaurantSalesContent.vue';
import ChartSkeleton from '@/components/smartbi/ChartSkeleton.vue';
import type { ChartConfig as SmartBIChartConfig } from '@/types/smartbi';
import ChartInsightProvider from './components/ChartInsightProvider.vue';
import type { ChartWithMeta } from './components/chartInsight';
import {
  getUploadHistory,
  getUploadTableData,
  getDynamicAnalysis,
  type UploadHistoryItem,
  type DynamicAnalysisResponse,
  type TableDataResponse
} from '@/api/smartbi';
import {
  recommendChart,
  batchBuildCharts,
  buildChart,
} from '@/api/smartbi/python-service';
import { getGoldDataRange } from '@/api/smartbi/dataRange';
import { resolveAllHistoryRange } from './analysisDefaults';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);
const isRestaurantTenant = computed(() => authStore.factoryType === 'RESTAURANT');
// Phase IIa rollback flag (spec §5.7): default ON. Ops can flip
// VITE_PHASE_IIA_ENABLED=false to fall back to RestaurantPhaseIIPlaceholder
// without a code revert. The placeholder file stays in repo for this reason.
const phaseIIaEnabled = computed(() => import.meta.env.VITE_PHASE_IIA_ENABLED !== 'false');
const rootRef = ref<HTMLDivElement>();
const trendChartRef = ref<HTMLDivElement | null>(null);
const pieChartRef = ref<HTMLDivElement | null>(null);

// ==================== 筛选条件 (localStorage 记忆) ====================

const FILTER_STORAGE_KEY = 'smartbi-sales-filters';

function loadSavedFilters() {
  try {
    const saved = localStorage.getItem(FILTER_STORAGE_KEY);
    if (saved) return JSON.parse(saved);
  } catch { /* ignore */ }
  return null;
}

function saveFilters() {
  try {
    localStorage.setItem(FILTER_STORAGE_KEY, JSON.stringify({
      dimensionType: dimensionType.value,
      categoryFilter: categoryFilter.value,
      dataSource: selectedDataSource.value,
    }));
  } catch { /* ignore */ }
}

const savedFilters = loadSavedFilters();
const dateRange = ref<[Date, Date] | null>(null);
const dimensionType = ref<'daily' | 'weekly' | 'monthly'>(savedFilters?.dimensionType || 'daily');
const categoryFilter = ref<string>(savedFilters?.categoryFilter || 'all');

// 加载状态
const loading = ref(false);
const loadError = ref('');
const kpiLoading = ref(false);
const rankingLoading = ref(false);
const trendLoading = ref(false);
const pieLoading = ref(false);

// AbortController: cancel in-flight requests when filters change
let salesAbortController: AbortController | null = null;

// 日期快捷选项
const shortcuts = [
  {
    text: '最近7天',
    value: () => {
      const end = new Date();
      const start = new Date();
      start.setTime(start.getTime() - 3600 * 1000 * 24 * 7);
      return [start, end];
    }
  },
  {
    text: '最近30天',
    value: () => {
      const end = new Date();
      const start = new Date();
      start.setTime(start.getTime() - 3600 * 1000 * 24 * 30);
      return [start, end];
    }
  },
  {
    text: '本月',
    value: () => {
      const end = new Date();
      const start = new Date(end.getFullYear(), end.getMonth(), 1);
      return [start, end];
    }
  },
  {
    text: '本季度',
    value: () => {
      const end = new Date();
      const quarter = Math.floor(end.getMonth() / 3);
      const start = new Date(end.getFullYear(), quarter * 3, 1);
      return [start, end];
    }
  }
];

// KPI 卡片数据 (来自 API 的 KPICard 结构)
interface KPICard {
  key: string;
  title: string;
  value: string;
  rawValue: number | null;
  unit: string;
  change: number | null;
  changeRate: number | null;
  trend: 'up' | 'down' | 'flat';
  status: string;
  compareText: string;
}

const kpiCards = ref<KPICard[]>([]);

// 是否有真实销售数据 (区分"加载失败"和"无数据")
const hasSystemData = ref(false);

// No real system sales data — all KPIs zero or no KPI cards
const noSystemSalesData = computed(() => {
  if (loading.value || kpiLoading.value) return false;
  if (selectedDataSource.value !== 'system') return false;
  if (kpiCards.value.length === 0) return true;
  return kpiCards.value.every(c => c.rawValue == null || c.rawValue === 0);
});

// 销售员排行 (来自 API)
interface SalesPersonRank {
  name: string;
  avatar?: string;
  sales: number;
  orderCount: number;
  growth: number;
}
const salesPersonRanking = ref<SalesPersonRank[]>([]);
const hasRankingOrderData = computed(() => salesPersonRanking.value.some(r => r.orderCount > 0));
const hasRankingGrowthData = computed(() => salesPersonRanking.value.some(r => r.growth !== 0));

function salesBarWidth(sales: number): number {
  const max = salesPersonRanking.value.length > 0
    ? Math.max(...salesPersonRanking.value.map(r => r.sales))
    : 1;
  return max > 0 ? Math.round((sales / max) * 100) : 0;
}

// 图表数据 (来自 API 的 ChartConfig 结构)
interface ChartConfig {
  chartType: string;
  title: string;
  xAxisField: string;
  yAxisField: string;
  seriesField?: string;
  data: Array<TableRow>;
  options?: TableRow;
}
const trendChartConfig = ref<ChartConfig | null>(null);
const pieChartConfig = ref<ChartConfig | null>(null);

// ==================== KPI Sparkline ====================

/** Extract sparkline data from trendChartConfig for each KPI card */
const kpiSparklineMap = computed<Record<number, number[]>>(() => {
  const config = trendChartConfig.value;
  if (!config || !config.data || config.data.length < 2) return {};

  const xAxisField = config.xAxisField || (config as TableRow).xaxisField as string || 'date';
  const yAxisField = config.yAxisField || (config as TableRow).yaxisField as string || 'value';

  // Primary series: y-axis values from trend chart → assign to first KPI card
  const salesData = config.data.map(item => Number(item[yAxisField]) || 0);
  if (salesData.length < 2) return {};

  // Also try extracting order count series if present
  const orderData = config.data.map(item => Number(item['orderCount'] || item['count']) || 0);
  const hasOrders = orderData.some(v => v > 0);

  const result: Record<number, number[]> = { 0: salesData };
  if (hasOrders) result[2] = orderData; // 3rd KPI card (typically orders)
  return result;
});

function sparklinePath(data: number[]): string {
  if (!data || data.length < 2) return '';
  const width = 60, height = 22, pad = 2;
  const min = Math.min(...data), max = Math.max(...data);
  const range = max - min || 1;
  const points = data.map((v, i) => {
    const x = pad + (i / (data.length - 1)) * (width - pad * 2);
    const y = height - pad - ((v - min) / range) * (height - pad * 2);
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });
  return `M ${points.join(' L ')}`;
}

function sparklineColor(data: number[]): string {
  if (!data || data.length < 2) return '#909399';
  return data[data.length - 1] >= data[0] ? '#36B37E' : '#FF5630';
}

/** Sparkline tooltip: latest / min / max */
function sparklineTooltip(data: number[]): string {
  if (!data || data.length < 2) return '';
  const latest = data[data.length - 1];
  const min = Math.min(...data);
  const max = Math.max(...data);
  return `最新: ${formatNumber(latest)}<br>最低: ${formatNumber(min)}<br>最高: ${formatNumber(max)}`;
}

// 产品类别
const categories = ref([
  { value: 'all', label: '全部类别' },
  { value: 'frozen_meat', label: '冷冻肉类' },
  { value: 'seafood', label: '海鲜产品' },
  { value: 'frozen_food', label: '速冻食品' },
  { value: 'dairy', label: '乳制品' }
]);

// 图表实例 (legacy fallback)
let trendChart: echarts.ECharts | null = null;
let pieChart: echarts.ECharts | null = null;

// ==================== Phase 6: Dynamic Chart Exploration ====================

// DynamicChartRenderer configs for main charts
const trendDynamicConfig = ref<SmartBIChartConfig | null>(null);
const pieDynamicConfig = ref<SmartBIChartConfig | null>(null);
const useDynamicTrend = ref(false);
const useDynamicPie = ref(false);

// Phase 6: Legacy ChartConfig narrowing helpers — pieDynamicConfig is a union of
// LegacyChartConfig | DynamicChartConfig | DashboardChartConfig; only LegacyChartConfig
// has top-level `data` and `xAxisField`. Compute typed views the template can safely use.
const pieLegacyData = computed<Array<Record<string, unknown>>>(() => {
  const cfg = pieDynamicConfig.value;
  if (!cfg) return [];
  if ('data' in cfg && Array.isArray(cfg.data)) return cfg.data;
  return [];
});
const pieXAxisField = computed<string>(() => {
  const cfg = pieDynamicConfig.value;
  if (!cfg) return 'name';
  if ('xAxisField' in cfg && typeof cfg.xAxisField === 'string') return cfg.xAxisField;
  return 'name';
});

// Data source selection (uploaded data)
const dataSources = ref<UploadHistoryItem[]>([]);
const selectedDataSource = ref<string>('system');
const isDynamicMode = computed(() => selectedDataSource.value !== 'system');

// AI insights from dynamic data
const aiInsights = ref<string[]>([]);

// Data preview dialog
const showDataPreview = ref(false);
const previewLoading = ref(false);
const previewPage = ref(1);
const previewData = ref<TableDataResponse>({
  headers: [],
  data: [],
  total: 0,
  page: 0,
  size: 50,
  totalPages: 0
});

// Multi-chart exploration
interface ExplorationChart {
  id: string;
  chartType: string;
  title: string;
  config: SmartBIChartConfig;
  xField?: string;
  yFields?: string[];
}
const explorationCharts = ref<ExplorationChart[]>([]);
const explorationLoading = ref(false);

// ==================== ChartInsightProvider: runtime type→meta mapping ====================

const EXOTIC_CHART_TYPES_SALES = new Set(['scatter', 'waterfall', 'radar', 'heatmap', 'gauge', 'funnel', 'sankey']);

/**
 * trendDynamicConfig is set to chartType:'line' by updateTrendChart().
 * User can switch it to 'bar' via handleChartTypeSwitch. Never exotic.
 * LegacyChartConfig format: { chartType, data[], xAxisField, yAxisField }
 */
const trendChartInsightMeta = computed<ChartWithMeta | null>(() => {
  const cfg = trendDynamicConfig.value;
  if (!cfg) return null;
  const ct = ((cfg as Record<string, unknown>)['chartType'] as string ?? 'line').toLowerCase();
  if (EXOTIC_CHART_TYPES_SALES.has(ct)) return null;
  if (ct !== 'bar' && ct !== 'line' && ct !== 'pie') return null;

  const c = cfg as Record<string, unknown>;
  let xData: string[] = [];
  let seriesData: Array<{ type?: string; data?: unknown[] }> = [];

  if (Array.isArray(c['data']) && typeof c['xAxisField'] === 'string') {
    xData = (c['data'] as Record<string, unknown>[]).map(r => String(r[c['xAxisField'] as string] ?? ''));
  } else if (c['xAxis'] && Array.isArray((c['xAxis'] as Record<string, unknown>)['data'])) {
    xData = ((c['xAxis'] as Record<string, unknown>)['data']) as string[];
  }

  if (Array.isArray(c['series'])) {
    seriesData = c['series'] as Array<{ type?: string; data?: unknown[] }>;
  } else if (Array.isArray(c['data']) && typeof c['yAxisField'] === 'string') {
    seriesData = [{ type: ct, data: (c['data'] as Record<string, unknown>[]).map(r => Number(r[c['yAxisField'] as string] ?? 0)) }];
  }

  return {
    chartType: ct,
    title: typeof c['title'] === 'string' ? c['title'] : '销售趋势',
    meta: { xDim: 'time', yMetric: 'revenue', aggregation: 'sum', domain: 'factory' },
    config: { xAxis: { data: xData }, series: seriesData },
  };
});

/**
 * pieDynamicConfig is always set to chartType:'pie' by updatePieChart().
 * LegacyChartConfig format: { chartType:'pie', data[], xAxisField (label), yAxisField (value) }
 */
const pieChartInsightMeta = computed<ChartWithMeta | null>(() => {
  const cfg = pieDynamicConfig.value;
  if (!cfg) return null;
  const c = cfg as Record<string, unknown>;
  const xf = typeof c['xAxisField'] === 'string' ? c['xAxisField'] : 'name';
  const yf = typeof c['yAxisField'] === 'string' ? c['yAxisField'] : 'value';

  const rows = Array.isArray(c['data']) ? (c['data'] as Record<string, unknown>[]) : [];
  const labels = rows.map(r => String(r[xf] ?? ''));
  const values = rows.map(r => Number(r[yf] ?? 0));

  return {
    chartType: 'pie',
    title: typeof c['title'] === 'string' ? c['title'] : '产品类别销售占比',
    meta: { xDim: 'category', yMetric: 'quantity', aggregation: 'sum', domain: 'factory' },
    config: { xAxis: { data: labels }, series: [{ type: 'pie', data: values }] },
  };
});

/**
 * Build ChartWithMeta for a single Sales exploration chart item.
 */
function salesExplorationChartMeta(chart: ExplorationChart): ChartWithMeta | null {
  const ct = (chart.chartType ?? '').toLowerCase();
  if (EXOTIC_CHART_TYPES_SALES.has(ct)) return null;
  if (ct !== 'bar' && ct !== 'line' && ct !== 'pie') return null;

  const c = chart.config as Record<string, unknown>;
  let xData: string[] = [];
  let seriesData: Array<{ type?: string; data?: unknown[] }> = [];

  if (c['xAxis'] && Array.isArray((c['xAxis'] as Record<string, unknown>)['data'])) {
    xData = ((c['xAxis'] as Record<string, unknown>)['data']) as string[];
  } else if (Array.isArray(c['data']) && chart.xField) {
    xData = (c['data'] as Record<string, unknown>[]).map(r => String(r[chart.xField as string] ?? ''));
  }

  if (Array.isArray(c['series'])) {
    seriesData = c['series'] as Array<{ type?: string; data?: unknown[] }>;
  } else if (Array.isArray(c['data']) && chart.yFields?.length) {
    const yf = chart.yFields[0];
    seriesData = [{ type: ct, data: (c['data'] as Record<string, unknown>[]).map(r => Number(r[yf] ?? 0)) }];
  }

  const meta = ct === 'line'
    ? { xDim: 'time' as const, yMetric: 'revenue' as const, aggregation: 'sum' as const, domain: 'factory' as const }
    : { xDim: 'category' as const, yMetric: 'revenue' as const, aggregation: 'sum' as const, domain: 'factory' as const };

  return {
    chartType: ct,
    title: chart.title,
    meta,
    config: { xAxis: { data: xData }, series: seriesData },
  };
}

// Data info for ChartTypeSelector
const dataInfo = ref<{
  numericColumns: string[];
  categoricalColumns: string[];
  dateColumns: string[];
  rowCount: number;
}>({
  numericColumns: [],
  categoricalColumns: [],
  dateColumns: [],
  rowCount: 0,
});

// Raw dynamic data for chart rebuilding
const dynamicRawData = ref<TableRow[]>([]);

// ==================== Data Source Functions ====================

async function loadDataSources() {
  try {
    const res = await getUploadHistory();
    if (res.success && res.data) {
      dataSources.value = res.data.filter(
        (item: UploadHistoryItem) => item.status === 'COMPLETED' || item.status === 'SUCCESS'
      );
    }
  } catch (error) {
    console.warn('加载数据源列表失败:', error);
  }
}

async function onDataSourceChange(sourceId: string) {
  saveFilters();
  if (sourceId === 'system') {
    aiInsights.value = [];
    explorationCharts.value = [];
    dynamicRawData.value = [];
    useDynamicTrend.value = false;
    useDynamicPie.value = false;
    trendDynamicConfig.value = null;
    pieDynamicConfig.value = null;
    loadSalesData();
  } else {
    await loadDynamicSalesData(Number(sourceId));
  }
}

async function loadDynamicSalesData(uploadId: number) {
  loading.value = true;
  loadError.value = '';
  aiInsights.value = [];

  // Reset chart state from any prior system data to avoid stale zero-value charts
  useDynamicTrend.value = false;
  useDynamicPie.value = false;
  trendDynamicConfig.value = null;
  pieDynamicConfig.value = null;
  trendChartConfig.value = null;
  pieChartConfig.value = null;
  if (trendChart) trendChart.clear();
  if (pieChart) pieChart.clear();

  try {
    const res = await getDynamicAnalysis(uploadId, 'sales');
    if (res.success && res.data) {
      const data = res.data as DynamicAnalysisResponse;

      // Update AI insights
      if (data.insights) {
        aiInsights.value = data.insights;
      }

      // Update KPI cards from dynamic data
      if (data.kpiCards && data.kpiCards.length > 0) {
        kpiCards.value = data.kpiCards.map((kpi, idx) => ({
          key: `dynamic-${idx}`,
          title: humanizeKpiTitle(kpi.title || '', idx),
          value: kpi.value || '0',
          rawValue: kpi.rawValue || 0,
          unit: '',
          change: 0,
          changeRate: 0,
          trend: 'flat' as const,
          status: 'green',
          compareText: '',
        }));
      }

      // Update charts from dynamic data
      if (data.charts && data.charts.length > 0) {
        updateChartsFromDynamicData(data.charts);
      }

      // Note: legacy ECharts already cleared at function start

      // Fetch raw data for exploration charts
      try {
        const tableRes = await getUploadTableData(uploadId, 0, 200);
        if (tableRes.success && tableRes.data && tableRes.data.data.length > 0) {
          buildExplorationCharts(tableRes.data.data as TableRow[]);
        }
      } catch (e) {
        console.warn('加载探索图表数据失败:', e);
      }
    } else {
      loadError.value = res.message || '加载分析数据失败';
    }
  } catch (error) {
    console.error('加载动态数据失败:', error);
    loadError.value = '加载分析数据失败，请检查网络连接后重试';
  } finally {
    loading.value = false;
  }
}

function updateChartsFromDynamicData(charts: DynamicAnalysisResponse['charts']) {
  if (charts.length === 0) return;

  // First chart as trend
  const firstChart = charts[0];
  const labels1 = firstChart.data?.labels || [];
  const datasets1 = firstChart.data?.datasets || [];

  if (firstChart.type === 'pie') {
    const pieData = labels1.map((label, idx) => ({
      name: label,
      value: datasets1[0]?.data?.[idx] || 0
    }));
    trendDynamicConfig.value = {
      chartType: 'pie',
      title: firstChart.title,
      xAxisField: 'name',
      yAxisField: 'value',
      data: pieData,
    } as SmartBIChartConfig;
  } else {
    trendDynamicConfig.value = {
      chartType: firstChart.type || 'bar',
      title: firstChart.title || '',
      xAxis: { data: labels1 },
      series: datasets1.map(ds => ({
        name: ds.label,
        type: firstChart.type || 'bar',
        data: ds.data,
      })),
    } as SmartBIChartConfig;
  }
  useDynamicTrend.value = true;

  // Second chart as pie (if available)
  if (charts.length > 1) {
    const secondChart = charts[1];
    const labels2 = secondChart.data?.labels || [];
    const datasets2 = secondChart.data?.datasets || [];

    const pieData2 = labels2.map((label, idx) => ({
      name: label,
      value: datasets2[0]?.data?.[idx] || 0
    }));
    pieDynamicConfig.value = {
      chartType: secondChart.type || 'pie',
      title: secondChart.title,
      xAxisField: 'name',
      yAxisField: 'value',
      data: pieData2,
    } as SmartBIChartConfig;
    useDynamicPie.value = true;
  }
}

// ==================== Exploration Charts ====================

async function buildExplorationCharts(data: TableRow[]) {
  if (!data || data.length === 0) return;

  explorationLoading.value = true;
  dynamicRawData.value = data;

  try {
    const recResult = await recommendChart(data);

    if (recResult.success && recResult.recommendations && recResult.recommendations.length > 0) {
      if (recResult.dataInfo) {
        dataInfo.value = {
          numericColumns: recResult.dataInfo.numericColumns || [],
          categoricalColumns: recResult.dataInfo.categoricalColumns || [],
          dateColumns: recResult.dataInfo.dateColumns || [],
          rowCount: recResult.dataInfo.rowCount || data.length,
        };
      }

      // P2 PERF fix: Sample data for chart building to avoid 4x payload duplication
      const chartData = data.length > 1000 ? data.slice(0, 1000) : data;
      const topRecs = recResult.recommendations.slice(0, 4);
      const plans = topRecs.map(rec => ({
        chartType: rec.chartType,
        data: chartData,
        xField: rec.xField || dataInfo.value.categoricalColumns[0] || '',
        yFields: rec.yFields || dataInfo.value.numericColumns.slice(0, 2),
        title: rec.reason || `${rec.chartType} 图表`,
      }));

      const batchResult = await batchBuildCharts(plans);

      if (batchResult.success && batchResult.charts.length > 0) {
        explorationCharts.value = batchResult.charts
          .filter(c => c.success && c.config)
          .map((c, idx) => ({
            id: `explore-${idx}`,
            chartType: c.chartType,
            title: plans[idx]?.title || c.chartType,
            config: c.config as unknown as SmartBIChartConfig,
            xField: plans[idx]?.xField,
            yFields: plans[idx]?.yFields,
          }));
      }
    }
  } catch (error) {
    console.warn('构建探索图表失败:', error);
  } finally {
    explorationLoading.value = false;
  }
}

async function handleChartTypeSwitch(chartId: string, newType: string) {
  const chart = explorationCharts.value.find(c => c.id === chartId);
  if (!chart || dynamicRawData.value.length === 0) return;

  try {
    const result = await buildChart({
      chartType: newType,
      data: dynamicRawData.value,
      xField: chart.xField,
      yFields: chart.yFields,
      title: chart.title,
    });

    if (result.success && result.option) {
      const idx = explorationCharts.value.findIndex(c => c.id === chartId);
      if (idx >= 0) {
        explorationCharts.value[idx] = {
          ...chart,
          chartType: newType,
          config: result.option as unknown as SmartBIChartConfig,
        };
      }
    } else {
      ElMessage.warning('图表类型切换失败: ' + (result.error || '未知错误'));
    }
  } catch (error) {
    console.warn('图表类型切换失败:', error);
  }
}

async function handleChartRefresh(chartId: string) {
  if (dynamicRawData.value.length === 0) return;
  const chart = explorationCharts.value.find(c => c.id === chartId);
  if (!chart) return;

  const types = ['bar', 'line', 'pie', 'area', 'scatter', 'waterfall', 'radar'];
  const alternatives = types.filter(t => t !== chart.chartType);
  const randomType = alternatives[Math.floor(Math.random() * alternatives.length)];
  await handleChartTypeSwitch(chartId, randomType);
}

// ==================== Data Preview ====================

async function openDataPreview() {
  if (selectedDataSource.value === 'system') {
    ElMessage.warning('请先选择一个上传的数据源');
    return;
  }
  showDataPreview.value = true;
  previewPage.value = 1;
  await loadPreviewData();
}

async function loadPreviewData() {
  const uploadId = Number(selectedDataSource.value);
  if (!uploadId) return;

  previewLoading.value = true;
  try {
    const res = await getUploadTableData(uploadId, previewPage.value - 1, 50);
    if (res.success && res.data) {
      previewData.value = res.data;
    } else {
      ElMessage.error(res.message || '获取数据失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载预览数据失败:', error);
  } finally {
    previewLoading.value = false;
  }
}

function handlePreviewPageChange(page: number) {
  previewPage.value = page;
  loadPreviewData();
}

function closeDataPreview() {
  showDataPreview.value = false;
}

onMounted(async () => {
  // Restaurant tenants render <RestaurantPhaseIIPlaceholder> per template
  // v-if. Skip all data fetching to avoid 404 spam against factory-only
  // /smart-bi/analysis/sales endpoint.
  if (isRestaurantTenant.value) {
    return;
  }

  // WS4 #11: 默认全部历史 (而非近30天 — qhj 等租户真实数据落在历史区间,
  // 近30天常空 → "所选区间无销售数据")。探 gold 数据窗; 失败回落宽窗 (绝不近30天)。
  let allHistory: [string, string];
  try {
    const probe = factoryId.value ? await getGoldDataRange(factoryId.value) : null;
    allHistory = resolveAllHistoryRange(probe);
  } catch {
    allHistory = resolveAllHistoryRange(null);
  }
  dateRange.value = [new Date(allHistory[0]), new Date(allHistory[1])];

  // 加载数据源列表
  await loadDataSources();

  // Restore saved data source if available
  if (savedFilters?.dataSource && savedFilters.dataSource !== 'system') {
    const exists = dataSources.value.some(d => String(d.id) === savedFilters.dataSource);
    if (exists) {
      selectedDataSource.value = savedFilters.dataSource;
    }
  }

  loadSalesData();
  initCharts();
});

// 监听筛选条件变化 + 持久化
// Restaurant tenants render placeholder (no filter UI) — skip watcher to
// avoid 404 spam if hot-reload retains stale state.
watch([dateRange, dimensionType, categoryFilter], () => {
  if (isRestaurantTenant.value) return;
  saveFilters();
  loadSalesData();
});

// Track overview-call failure so we can skip the fan-out fallback below.
// Issue #230: when /analysis/sales returns 410 (or any error), the 3 dimension
// fallbacks would each re-hit the same broken endpoint and amplify console noise
// + waste 3 more requests per filter change. Dedupe by skipping fan-out on failure.
let overviewFailed = false;

async function loadSalesData() {
  // Cancel previous in-flight request
  if (salesAbortController) salesAbortController.abort();
  salesAbortController = new AbortController();
  const signal = salesAbortController.signal;

  loading.value = true;
  loadError.value = '';
  kpiLoading.value = true;
  rankingLoading.value = true;
  trendLoading.value = true;
  pieLoading.value = true;
  overviewFailed = false;

  if (!factoryId.value) {
    loadError.value = '未找到工厂ID，请重新登录';
    loading.value = false;
    kpiLoading.value = false;
    rankingLoading.value = false;
    trendLoading.value = false;
    pieLoading.value = false;
    return;
  }

  try {
    // Load overview data first (contains KPIs, charts, rankings in unified response)
    await loadOverviewData(signal);

    // Load dimension-specific data in parallel ONLY if overview succeeded but
    // didn't populate them. Skip entirely on overview failure (issue #230 — 410
    // storm dedupe) since the 3 dimension calls hit the same endpoint and would
    // produce identical errors + 3× wasted requests + 3× console.warn spam.
    const tasks: Promise<void>[] = [];
    if (!overviewFailed) {
      if (salesPersonRanking.value.length === 0) {
        tasks.push(loadRankingData());
      } else {
        rankingLoading.value = false;
      }
      if (!trendChartConfig.value) {
        tasks.push(loadTrendData());
      } else {
        trendLoading.value = false;
      }
      if (!pieChartConfig.value) {
        tasks.push(loadProductData());
      } else {
        pieLoading.value = false;
      }
    } else {
      rankingLoading.value = false;
      trendLoading.value = false;
      pieLoading.value = false;
    }
    if (tasks.length > 0) {
      await Promise.allSettled(tasks);
    }
    // Auto-switch to uploaded data if system data is empty and uploads exist
    const allZero = kpiCards.value.length === 0 || kpiCards.value.every(c => c.rawValue == null || c.rawValue === 0);
    const validUpload = dataSources.value.find(ds => ds.id && ds.fileName);
    if (allZero && validUpload) {
      selectedDataSource.value = String(validUpload.id);
      await loadDynamicSalesData(validUpload.id);
      return;
    }
  } catch (error) {
    // Ignore aborted requests (user changed filters quickly)
    if (error instanceof DOMException && error.name === 'AbortError') return;
    if (signal?.aborted) return;
    loadError.value = '加载销售数据失败，请检查网络连接后重试';
  } finally {
    if (!signal?.aborted) {
      loading.value = false;
      kpiLoading.value = false;
      rankingLoading.value = false;
      trendLoading.value = false;
      pieLoading.value = false;
    }
  }
}

/**
 * Parse unified backend response and extract KPIs, rankings, charts.
 * The backend may return data in either:
 *   1. Unified format: { overview: { kpiCards, rankings, charts } }
 *   2. Direct format: { kpiCards, rankings, charts }  (from getComprehensiveAnalysis)
 */
function parseUnifiedResponse(data: TableRow) {
  // Extract KPI cards from various response shapes
  const overview = data.overview as TableRow | undefined;
  const kpiSource = overview?.kpiCards || data.kpiCards;
  if (Array.isArray(kpiSource) && kpiSource.length > 0) {
    kpiCards.value = (kpiSource as KPICard[]).map(card => ({
      ...card,
      // Ensure null safety for numeric fields
      rawValue: card.rawValue ?? null,
      change: card.change ?? null,
      changeRate: card.changeRate ?? null,
      trend: card.trend || 'flat',
      status: card.status || 'green',
      compareText: card.compareText || '',
      // Format value: show "--" if value is empty/null
      value: card.value || (card.rawValue != null ? String(card.rawValue) : '--'),
    }));
    hasSystemData.value = true;
  }

  // Extract rankings (may be in overview.rankings or data.rankings)
  const rankingsSource = (overview?.rankings || data.rankings) as Record<string, unknown[]> | undefined;
  if (rankingsSource) {
    const spRanking = rankingsSource.salesperson || rankingsSource.sales_person;
    if (Array.isArray(spRanking) && spRanking.length > 0) {
      salesPersonRanking.value = spRanking.map((item: TableRow) => ({
        name: String(item.name || ''),
        sales: Number(item.value || item.sales || 0),
        orderCount: Number(item.orderCount || item.count || 0),
        growth: Number(item.growthRate || item.growth || 0),
      }));
    }
  }

  // Extract charts (may be in overview.charts or data.charts)
  const chartsSource = (overview?.charts || data.charts) as Record<string, ChartConfig> | undefined;
  if (chartsSource && typeof chartsSource === 'object') {
    // Try to find trend chart
    for (const [key, chart] of Object.entries(chartsSource)) {
      if (!chart) continue;
      const chartObj = chart as ChartConfig;
      if (key.includes('trend') || key.includes('趋势') || chartObj.chartType?.toLowerCase() === 'line') {
        trendChartConfig.value = chartObj;
        updateTrendChart();
      } else if (key.includes('pie') || key.includes('分布') || key.includes('占比') || chartObj.chartType?.toLowerCase() === 'pie') {
        pieChartConfig.value = chartObj;
        updatePieChart();
      }
    }
  }

  // Also check for chartList (legacy format)
  const chartList = (overview?.chartList || data.chartList) as ChartConfig[] | undefined;
  if (Array.isArray(chartList)) {
    for (const chart of chartList) {
      if (!chart) continue;
      if (!trendChartConfig.value && (chart.chartType === 'line' || chart.title?.includes('趋势'))) {
        trendChartConfig.value = chart;
        updateTrendChart();
      } else if (!pieChartConfig.value && (chart.chartType === 'pie' || chart.title?.includes('分布') || chart.title?.includes('占比'))) {
        pieChartConfig.value = chart;
        updatePieChart();
      }
    }
  }
}

/**
 * Load overview data (unified endpoint, no dimension).
 * API: GET /{factoryId}/smart-bi/analysis/sales
 *
 * The backend may return a comprehensive response that includes KPIs, rankings,
 * and charts all at once.
 */
async function loadOverviewData(signal?: AbortSignal) {
  if (!factoryId.value || !dateRange.value) return;
  try {
    const params: Record<string, string> = {
      startDate: formatDate(dateRange.value[0]),
      endDate: formatDate(dateRange.value[1]),
      groupBy: dimensionType.value, // daily/weekly/monthly
    };
    const response = await get(`/${factoryId.value}/smart-bi/analysis/sales`, { params, signal, _silent: true } as never);
    if (response.success && response.data) {
      // Handle double-wrapped response: interceptor wraps {code,data:{...}} into {success,data:{code,data:{...}}}
      const raw = response.data as TableRow;
      const actualData = (raw.data && typeof raw.data === 'object' && !Array.isArray(raw.data) && raw.code)
        ? raw.data as TableRow
        : raw;
      parseUnifiedResponse(actualData);
    } else {
      // Mark failure so fan-out is skipped (issue #230 dedupe)
      overviewFailed = true;
      // Don't show error toast here, just log
      console.warn('Sales overview returned non-success:', response.message);
    }
  } catch (error) {
    // Don't mark abort as failure — user just changed filters mid-flight
    if (error instanceof DOMException && error.name === 'AbortError') return;
    if (signal?.aborted) return;
    // Mark failure so fan-out is skipped (issue #230 dedupe). Covers 410
    // SMARTBI_MIGRATED, 5xx, network error, etc — fan-out would just re-hit
    // the same endpoint with the same outcome.
    overviewFailed = true;
    // PR #468 §6 P1-A: surface error to user (previously silent → stale-data UX gap).
    // 410 SMARTBI_MIGRATED + 401/403 are surfaced by axios interceptor already; this
    // covers 5xx, network, parse errors. ElMessage dedupes by message + duration.
    const msg = error instanceof Error ? error.message : '未知错误';
    ElMessage.error(`加载销售概览失败: ${msg}`);
    console.warn('加载销售概览失败:', error);
  } finally {
    kpiLoading.value = false;
  }
}

/**
 * Load salesperson ranking (dimension-specific fallback).
 * API: GET /{factoryId}/smart-bi/analysis/sales?dimension=salesperson
 */
async function loadRankingData() {
  if (!factoryId.value || !dateRange.value) return;
  try {
    const params = {
      startDate: formatDate(dateRange.value[0]),
      endDate: formatDate(dateRange.value[1]),
      dimension: 'salesperson'
    };
    const response = await get(`/${factoryId.value}/smart-bi/analysis/sales`, { params, _silent: true } as never);
    if (response.success && response.data) {
      const data = response.data as TableRow;
      // May return { ranking: [...] } or unified response with overview.rankings
      if (Array.isArray(data.ranking)) {
        salesPersonRanking.value = (data.ranking as TableRow[]).map(item => ({
          name: String(item.name || ''),
          sales: Number(item.value || item.sales || 0),
          orderCount: Number(item.orderCount || item.count || 0),
          growth: Number(item.growthRate || item.growth || 0),
        }));
      } else {
        // Try parsing as unified response
        parseUnifiedResponse(data);
      }
    }
  } catch (error) {
    console.warn('加载销售员排行失败:', error);
  } finally {
    rankingLoading.value = false;
  }
}

/**
 * Load trend chart data (dimension-specific fallback).
 * API: GET /{factoryId}/smart-bi/analysis/sales?dimension=trend
 */
async function loadTrendData() {
  if (!factoryId.value || !dateRange.value) return;
  try {
    const params = {
      startDate: formatDate(dateRange.value[0]),
      endDate: formatDate(dateRange.value[1]),
      dimension: 'trend'
    };
    const response = await get(`/${factoryId.value}/smart-bi/analysis/sales`, { params, _silent: true } as never);
    if (response.success && response.data) {
      const data = response.data as TableRow;
      if (data.chart) {
        trendChartConfig.value = data.chart as ChartConfig;
        updateTrendChart();
      } else {
        // Try parsing as unified response
        parseUnifiedResponse(data);
      }
    }
  } catch (error) {
    console.warn('加载趋势图失败:', error);
  } finally {
    trendLoading.value = false;
  }
}

/**
 * Load product distribution chart (dimension-specific fallback).
 * API: GET /{factoryId}/smart-bi/analysis/sales?dimension=product
 */
async function loadProductData() {
  if (!factoryId.value || !dateRange.value) return;
  try {
    const params = {
      startDate: formatDate(dateRange.value[0]),
      endDate: formatDate(dateRange.value[1]),
      dimension: 'product'
    };
    const response = await get(`/${factoryId.value}/smart-bi/analysis/sales`, { params, _silent: true } as never);
    if (response.success && response.data) {
      const data = response.data as TableRow;
      if (data.chart) {
        pieChartConfig.value = data.chart as ChartConfig;
        updatePieChart();
      } else if (data.ranking) {
        // Product dimension may return ranking data; build pie from it
        const ranking = data.ranking as Array<{ name: string; value: number }>;
        if (ranking.length > 0) {
          pieChartConfig.value = {
            chartType: 'pie',
            title: '产品类别销售占比',
            xAxisField: 'name',
            yAxisField: 'value',
            data: ranking.map(r => ({ name: r.name, value: Number(r.value || 0) })),
          };
          updatePieChart();
        }
      } else {
        parseUnifiedResponse(data);
      }
    }
  } catch (error) {
    console.warn('加载产品分布图失败:', error);
  } finally {
    pieLoading.value = false;
  }
}

// PR #468 §6 P1-A: 委托给 toApiDateString — el-date-picker 配 value-format="YYYY-MM-DD"
// 时 v-model 实际是 string, 旧实现 .toISOString() 抛 TypeError → 用户拿到陈旧数据.
const formatDate = toApiDateString;

function initCharts() {
  initTrendChart();
  initPieChart();
  window.addEventListener('resize', handleResize);
  if (rootRef.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(handleResize);
    resizeObserver.observe(rootRef.value);
  }
}

function initTrendChart() {
  const chartDom = trendChartRef.value;
  if (!chartDom) return;

  trendChart = echarts.init(chartDom, 'cretas');
}

function initPieChart() {
  const chartDom = pieChartRef.value;
  if (!chartDom) return;

  pieChart = echarts.init(chartDom, 'cretas');
}

/**
 * 根据 API 返回的 ChartConfig 更新趋势图
 * Phase 6: Also sets trendDynamicConfig for DynamicChartRenderer
 */
function updateTrendChart() {
  const config = trendChartConfig.value;
  if (!config || !config.data || config.data.length === 0) {
    useDynamicTrend.value = false;
    trendDynamicConfig.value = null;
    if (trendChart) {
      trendChart.setOption({
        title: {
          text: '暂无趋势数据',
          left: 'center',
          top: 'center',
          textStyle: { color: '#909399', fontSize: 14 }
        }
      });
    }
    return;
  }

  // 从 API 数据中提取 X 轴和 Y 轴数据
  // Note: Jackson serializes xAxisField as xaxisField (lowercase), handle both
  const xAxisField = config.xAxisField || (config as TableRow).xaxisField as string || 'date';
  const yAxisField = config.yAxisField || (config as TableRow).yaxisField as string || 'value';

  const xAxisData = config.data.map(item => String(item[xAxisField] || ''));
  const salesData = config.data.map(item => Number(item[yAxisField]) || 0);

  // 尝试获取订单数据
  const orderData = config.data.map(item => Number(item['orderCount'] || item['count']) || 0);
  const hasOrderData = orderData.some(v => v > 0);

  // Phase 6: Set DynamicChartRenderer config (LegacyChartConfig format)
  // Pass resolved field names so DynamicChartRenderer gets correct xAxisField/yAxisField
  trendDynamicConfig.value = {
    chartType: 'line',
    title: '销售趋势',
    xAxisField,
    yAxisField,
    data: config.data,
  } as SmartBIChartConfig;
  useDynamicTrend.value = true;

  // Legacy fallback: still update raw echarts
  if (!trendChart) return;

  const option: echarts.EChartsOption = {
    tooltip: {
      trigger: 'axis',
      confine: true,
      axisPointer: { type: 'cross' }
    },
    legend: {
      data: hasOrderData ? ['销售额', '订单数'] : ['销售额'],
      bottom: 0
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '15%',
      top: '10%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: xAxisData
    },
    yAxis: hasOrderData ? [
      {
        type: 'value',
        name: '销售额',
        axisLabel: {
          formatter: formatAxisValue
        }
      },
      {
        type: 'value',
        name: '订单数',
        axisLabel: {
          formatter: '{value}'
        }
      }
    ] : [
      {
        type: 'value',
        name: '销售额',
        axisLabel: {
          formatter: formatAxisValue
        }
      }
    ],
    series: hasOrderData ? [
      {
        name: '销售额',
        type: 'line',
        smooth: true,
        data: salesData,
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(64, 158, 255, 0.3)' },
            { offset: 1, color: 'rgba(64, 158, 255, 0.05)' }
          ])
        },
        lineStyle: { width: 3, color: '#2D8B57' },
        itemStyle: { color: '#2D8B57' },
        markPoint: salesData.some(v => v < 0) ? {
          data: [
            { type: 'min', name: '最低值', symbol: 'pin', symbolSize: 40, label: { formatter: '{c}', fontSize: 10 } }
          ],
          itemStyle: { color: '#FF5630' }
        } : undefined,
        markLine: salesData.some(v => v < 0) ? {
          silent: true,
          data: [{ yAxis: 0, lineStyle: { color: '#E6A23C', type: 'dashed', width: 1 } }],
          label: { show: false }
        } : undefined
      },
      {
        name: '订单数',
        type: 'bar',
        yAxisIndex: 1,
        data: orderData,
        barWidth: '40%',
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#36B37E' },
            { offset: 1, color: '#95d475' }
          ]),
          borderRadius: [4, 4, 0, 0]
        }
      }
    ] : [
      {
        name: '销售额',
        type: 'line',
        smooth: true,
        data: salesData,
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(64, 158, 255, 0.3)' },
            { offset: 1, color: 'rgba(64, 158, 255, 0.05)' }
          ])
        },
        lineStyle: { width: 3, color: '#2D8B57' },
        itemStyle: { color: '#2D8B57' },
        markPoint: salesData.some(v => v < 0) ? {
          data: [
            { type: 'min', name: '最低值', symbol: 'pin', symbolSize: 40, label: { formatter: '{c}', fontSize: 10 } }
          ],
          itemStyle: { color: '#FF5630' }
        } : undefined,
        markLine: salesData.some(v => v < 0) ? {
          silent: true,
          data: [{ yAxis: 0, lineStyle: { color: '#E6A23C', type: 'dashed', width: 1 } }],
          label: { show: false }
        } : undefined
      }
    ]
  };

  trendChart.setOption(option, true);
}

/**
 * 根据 API 返回的 ChartConfig 更新饼图
 * Phase 6: Also sets pieDynamicConfig for DynamicChartRenderer
 */
function updatePieChart() {
  const config = pieChartConfig.value;
  if (!config || !config.data || config.data.length === 0) {
    useDynamicPie.value = false;
    pieDynamicConfig.value = null;
    if (pieChart) {
      pieChart.setOption({
        title: {
          text: '暂无产品分布数据',
          left: 'center',
          top: 'center',
          textStyle: { color: '#909399', fontSize: 14 }
        }
      });
    }
    return;
  }

  // Phase 6: Auto-detect field names if xAxisField/yAxisField don't match data
  const sample = config.data[0] as TableRow | undefined;
  let xField = config.xAxisField || 'name';
  let yField = config.yAxisField || 'value';
  if (sample) {
    // If declared fields don't exist in data, auto-detect
    if (!(xField in sample)) {
      const stringFields = Object.keys(sample).filter(k => typeof sample[k] === 'string');
      if (stringFields.length > 0) xField = stringFields[0];
    }
    if (!(yField in sample)) {
      const numFields = Object.keys(sample).filter(k => typeof sample[k] === 'number' && k !== xField);
      if (numFields.length > 0) yField = numFields[0];
    }
  }

  pieDynamicConfig.value = {
    chartType: 'pie',
    title: '产品类别销售占比',
    xAxisField: xField,
    yAxisField: yField,
    data: config.data,
  } as SmartBIChartConfig;
  useDynamicPie.value = true;

  // Legacy fallback: still update raw echarts
  if (!pieChart) return;

  // 预定义颜色
  const colors = ['#2D8B57', '#36B37E', '#E6A23C', '#FF5630', '#909399', '#00CED1', '#FF69B4', '#8A2BE2'];

  // 从 API 数据中提取饼图数据
  const pieData = config.data.map((item, index) => ({
    value: Number(item[yField]) || 0,
    name: String(item[xField] || `类别${index + 1}`),
    itemStyle: { color: colors[index % colors.length] }
  }));

  const option: echarts.EChartsOption = {
    tooltip: {
      trigger: 'item',
      confine: true,
      formatter: '{b}: {c}万 ({d}%)'
    },
    legend: {
      orient: 'vertical',
      right: '5%',
      top: 'center'
    },
    series: [
      {
        type: 'pie',
        radius: ['40%', '70%'],
        center: ['35%', '50%'],
        avoidLabelOverlap: false,
        label: {
          show: false,
          position: 'center'
        },
        emphasis: {
          label: {
            show: true,
            fontSize: 18,
            fontWeight: 'bold'
          },
          itemStyle: {
            shadowBlur: 10,
            shadowOffsetX: 0,
            shadowColor: 'rgba(0, 0, 0, 0.2)'
          },
          scaleSize: 8,
        },
        labelLine: { show: false },
        data: pieData
      }
    ]
  };

  pieChart.setOption(option, true);
}

let resizeObserver: ResizeObserver | null = null;
let resizeRaf = 0;
function handleResize() {
  if (resizeRaf) return;
  resizeRaf = requestAnimationFrame(() => {
    trendChart?.resize();
    pieChart?.resize();
    resizeRaf = 0;
  });
}

/**
 * Format KPI card value for display.
 * Shows "--" for null/undefined values (truly missing data).
 * Shows the backend-formatted value if available.
 */

/**
 * Apr 24 P0-1 fix: backend dedupe_column_names adds _2/_3 suffixes to duplicate
 * columns; these leak into KPI titles as "数量金额_3". Rewrite to a more
 * human-friendly form. Pattern: keeps base name + appends (指标 N) for N>=2.
 * Also maps common anonymous/ambiguous labels ("数量金额" alone) by position.
 */
function humanizeKpiTitle(rawTitle: string, idx: number): string {
  if (!rawTitle) return `指标 ${idx + 1}`;
  const t = rawTitle.trim();
  // Match trailing _N digit suffix from dedupe
  const m = t.match(/^(.+?)_(\d+)$/);
  if (m) {
    const base = m[1];
    const n = m[2];
    return `${base} (指标 ${n})`;
  }
  return t;
}

function formatKpiValue(card: KPICard): string {
  // Prefer rawValue for consistent formatting
  if (card.rawValue != null) {
    if (card.rawValue === 0) {
      if (card.unit === '%') return '0.0%';
      return '0';
    }
    // Count-type KPIs: integer display, no decimals
    const countUnits = ['单', '个', '条', '次', '人', '家'];
    if (card.unit && countUnits.includes(card.unit)) {
      return formatCount(card.rawValue) + card.unit;
    }
    const formatted = formatMoney(card.rawValue);
    if (card.unit === '元') {
      // Only append 元 when value isn't already abbreviated (万/亿)
      return formatted.includes('万') || formatted.includes('亿') ? formatted : formatted + '元';
    }
    return formatted + (card.unit || '');
  }
  // rawValue missing — truly no data
  return '--';
}

function formatMoney(value: number | null | undefined): string {
  if (value == null) return '--';
  if (value === 0) return '0';
  return formatNumber(value, 1);
}

function formatPercent(value: number | null | undefined): string {
  if (value == null) return '--';
  return (value >= 0 ? '+' : '') + value.toFixed(1) + '%';
}

function getGrowthClass(value: number): string {
  return value >= 0 ? 'growth-up' : 'growth-down';
}

async function handleExport() {
  try {
    const XLSX = await import('xlsx');
    const wb = XLSX.utils.book_new();

    // 1. KPI summary sheet
    if (kpiCards.value.length > 0) {
      const kpiRows = kpiCards.value.map(card => ({
        '指标': card.title,
        '数值': card.value,
        '原始值': card.rawValue ?? '',
        '变化率': card.changeRate != null ? `${card.changeRate >= 0 ? '+' : ''}${Number(card.changeRate).toFixed(1)}%` : '',
        '趋势': card.trend === 'up' ? '上升' : card.trend === 'down' ? '下降' : '持平',
      }));
      const kpiWs = XLSX.utils.json_to_sheet(kpiRows);
      XLSX.utils.book_append_sheet(wb, kpiWs, 'KPI汇总');
    }

    // 2. Sales ranking sheet
    if (salesPersonRanking.value.length > 0) {
      const rankRows = salesPersonRanking.value.map((person, idx) => ({
        '排名': idx + 1,
        '销售员': person.name,
        '销售额': person.sales,
        '订单数': person.orderCount,
        '增长率': `${person.growth >= 0 ? '+' : ''}${person.growth.toFixed(1)}%`,
      }));
      const rankWs = XLSX.utils.json_to_sheet(rankRows);
      XLSX.utils.book_append_sheet(wb, rankWs, '销售员排行');
    }

    // 3. Trend data sheet (if available)
    if (trendChartConfig.value?.data?.length) {
      const trendWs = XLSX.utils.json_to_sheet(trendChartConfig.value.data as TableRow[]);
      XLSX.utils.book_append_sheet(wb, trendWs, '趋势数据');
    }

    // 4. Product distribution data (if available)
    if (pieChartConfig.value?.data?.length) {
      const pieWs = XLSX.utils.json_to_sheet(pieChartConfig.value.data as TableRow[]);
      XLSX.utils.book_append_sheet(wb, pieWs, '产品分布');
    }

    // 5. Dynamic exploration data (if available)
    if (dynamicRawData.value.length > 0) {
      const dynWs = XLSX.utils.json_to_sheet(dynamicRawData.value);
      XLSX.utils.book_append_sheet(wb, dynWs, '原始数据');
    }

    // 6. AI insights sheet (if available)
    if (aiInsights.value.length > 0) {
      const insightRows = aiInsights.value.map((insight, idx) => ({
        '序号': idx + 1,
        '洞察': insight,
      }));
      const insightWs = XLSX.utils.json_to_sheet(insightRows);
      XLSX.utils.book_append_sheet(wb, insightWs, 'AI洞察');
    }

    const fileName = `销售分析报告-${new Date().toISOString().slice(0, 10)}.xlsx`;
    XLSX.writeFile(wb, fileName);
    ElMessage.success(`已导出: ${fileName}`);
  } catch (error) {
    console.error('Export failed:', error);
    ElMessage.error('导出失败，请重试');
  }
}

function handleRefresh() {
  loadSalesData();
}

function resetFilters() {
  dateRange.value = null;
  dimensionType.value = 'daily';
  categoryFilter.value = 'all';
  localStorage.removeItem(FILTER_STORAGE_KEY);
  loadSalesData();
}

onUnmounted(() => {
  if (resizeRaf) { cancelAnimationFrame(resizeRaf); resizeRaf = 0; }
  resizeObserver?.disconnect();
  resizeObserver = null;
  window.removeEventListener('resize', handleResize);
  if (salesAbortController) { salesAbortController.abort(); salesAbortController = null; }
  trendChart?.dispose();
  pieChart?.dispose();
});
</script>

<template>
  <!-- Phase IIa (2026-05-14): real restaurant analytics replace the placeholder.
       Rollback path (spec §5.7): VITE_PHASE_IIA_ENABLED=false falls back to the
       placeholder without a code revert. RestaurantPhaseIIPlaceholder.vue stays
       in repo so this flag flip works. -->
  <RestaurantPhaseIIPlaceholder
    v-if="isRestaurantTenant && !phaseIIaEnabled"
    pageName="销售数据分析"
  />
  <RestaurantSalesContent v-else-if="isRestaurantTenant && phaseIIaEnabled" />
  <div v-else ref="rootRef" class="sales-analysis-page">
    <div class="page-header">
      <div class="header-left">
        <h1>销售分析</h1>
      </div>
      <div class="header-right">
        <el-button :icon="Download" @click="handleExport">导出报表</el-button>
        <el-button type="primary" :icon="Refresh" @click="handleRefresh">刷新</el-button>
      </div>
    </div>

    <!-- 筛选栏 -->
    <el-card class="filter-card">
      <div class="filter-bar">
        <!-- Phase 6: 数据源选择器 -->
        <div class="filter-item">
          <span class="filter-label">
            <el-icon><Document /></el-icon>
            数据源
          </span>
          <el-select
            v-model="selectedDataSource"
            placeholder="选择数据源"
            style="width: 240px"
            @change="onDataSourceChange"
          >
            <el-option label="系统数据" value="system" />
            <el-option
              v-for="ds in dataSources"
              :key="ds.id"
              :label="`${ds.fileName || '未命名文件'}${ds.sheetName ? ' - ' + ds.sheetName : ''}`"
              :value="String(ds.id)"
            >
              <div class="datasource-option">
                <span>{{ ds.fileName }}</span>
                <span class="datasource-meta">{{ ds.sheetName }} · {{ ds.rowCount }}行</span>
              </div>
            </el-option>
          </el-select>
        </div>

        <div class="filter-item">
          <span class="filter-label">
            <el-icon><Calendar /></el-icon>
            日期范围
          </span>
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :shortcuts="shortcuts"
            value-format="YYYY-MM-DD"
            :disabled="selectedDataSource !== 'system'"
          />
        </div>
        <div class="filter-item">
          <span class="filter-label">
            <el-icon><TrendCharts /></el-icon>
            统计维度
          </span>
          <el-radio-group v-model="dimensionType" :disabled="selectedDataSource !== 'system'">
            <el-radio-button value="daily">按日</el-radio-button>
            <el-radio-button value="weekly">按周</el-radio-button>
            <el-radio-button value="monthly">按月</el-radio-button>
          </el-radio-group>
        </div>
        <div class="filter-item">
          <span class="filter-label">
            <el-icon><Filter /></el-icon>
            产品类别
          </span>
          <el-select v-model="categoryFilter" placeholder="选择类别" :disabled="selectedDataSource !== 'system'">
            <el-option
              v-for="cat in categories"
              :key="cat.value"
              :label="cat.label"
              :value="cat.value"
            />
          </el-select>
        </div>

        <!-- 查看原始数据按钮 -->
        <div class="filter-item" v-if="selectedDataSource !== 'system'">
          <el-button :icon="View" @click="openDataPreview">
            查看原始数据
          </el-button>
        </div>
        <div class="filter-item" v-if="selectedDataSource === 'system' && (dateRange || dimensionType !== 'daily' || categoryFilter !== 'all')">
          <el-button text type="info" size="small" @click="resetFilters">
            重置筛选
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- 加载错误提示 -->
    <el-alert
      v-if="loadError"
      type="error"
      :title="loadError"
      show-icon
      closable
      style="margin-bottom: 16px"
      @close="loadError = ''"
    >
      <el-button size="small" type="primary" @click="handleRefresh" style="margin-top: 8px">重试</el-button>
    </el-alert>

    <!-- 系统数据为空提示 -->
    <el-alert
      v-if="noSystemSalesData && !loading"
      type="info"
      title="暂无系统销售数据"
      description="系统销售数据为空，请联系管理员导入数据，或切换到已上传的 Excel 数据源进行分析。"
      show-icon
      :closable="false"
      style="margin-bottom: 16px"
    />

    <!-- KPI 卡片 -->
    <el-row v-if="kpiLoading && kpiCards.length === 0" :gutter="16" class="kpi-section">
      <el-col :xs="12" :sm="8" class="kpi-col-5" v-for="i in 5" :key="'skel-'+i">
        <el-card class="kpi-card"><ChartSkeleton type="kpi" /></el-card>
      </el-col>
    </el-row>
    <el-row v-else :gutter="16" class="kpi-section">
      <el-col
        v-for="card in kpiCards"
        :key="card.key"
        :xs="12"
        :sm="8"
        class="kpi-col-5"
      >
        <el-card class="kpi-card" :class="['kpi-accent-' + (kpiCards.indexOf(card) % 5), { 'kpi-no-data': card.rawValue == null }]" role="group" :aria-label="card.title + ': ' + formatKpiValue(card)">
          <div class="kpi-label">{{ card.title }}</div>
          <div class="kpi-value-row">
            <div class="kpi-value">{{ formatKpiValue(card) }}</div>
            <el-tooltip v-if="kpiSparklineMap[kpiCards.indexOf(card)]?.length >= 2" :content="sparklineTooltip(kpiSparklineMap[kpiCards.indexOf(card)])" placement="top" :show-after="300" raw-content>
              <svg class="kpi-sparkline" width="60" height="22" viewBox="0 0 60 22">
                <path :d="sparklinePath(kpiSparklineMap[kpiCards.indexOf(card)])" fill="none" :stroke="sparklineColor(kpiSparklineMap[kpiCards.indexOf(card)])" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </el-tooltip>
          </div>
          <div
            class="kpi-trend"
            :class="card.trend === 'up' ? 'growth-up' : card.trend === 'down' ? 'growth-down' : ''"
          >
            <!-- Skip changeRate display when main value is already a % (avoid duplicate) -->
            <span v-if="card.unit !== '%' && card.changeRate != null && card.changeRate !== 0">
              {{ card.changeRate >= 0 ? '+' : '' }}{{ Number(card.changeRate).toFixed(1) }}%
            </span>
            <span v-else-if="card.rawValue == null" class="no-data-text">暂无数据</span>
            <span v-else-if="card.compareText" class="compare-text">{{ card.compareText }}</span>
          </div>
        </el-card>
      </el-col>
      <!-- 无数据时的占位 -->
      <el-col v-if="kpiCards.length === 0 && !kpiLoading" :span="24">
        <SmartBIEmptyState type="no-data" :show-action="false" />
      </el-col>
    </el-row>

    <!-- 销售员排行榜 -->
    <el-row :gutter="16" class="content-section">
      <el-col :xs="24" :lg="10">
        <el-card class="ranking-card" v-loading="rankingLoading">
          <template #header>
            <div class="card-header">
              <el-icon><User /></el-icon>
              <span>销售员排行榜</span>
            </div>
          </template>
          <template v-if="salesPersonRanking.length > 0">
            <el-table :data="salesPersonRanking" stripe :show-header="true" size="small" :max-height="400">
              <el-table-column label="排名" width="60" align="center">
                <template #default="{ $index }">
                  <div
                    class="rank-badge"
                    :class="{ 'top-3': $index < 3 }"
                  >
                    {{ $index + 1 }}
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="销售员" prop="name" width="100" />
              <el-table-column v-if="canViewPrice" label="销售额" prop="sales" min-width="140">
                <template #default="{ row }">
                  <div class="sales-bar-cell">
                    <div class="sales-bar-bg">
                      <div class="sales-bar-fill" :style="{ width: salesBarWidth(row.sales) + '%' }"></div>
                    </div>
                    <span class="sales-bar-value">{{ formatMoney(row.sales) }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column v-if="hasRankingOrderData" label="订单数" prop="orderCount" width="80" align="center">
                <template #default="{ row }">
                  {{ row.orderCount > 0 ? row.orderCount : '--' }}
                </template>
              </el-table-column>
              <el-table-column v-if="hasRankingGrowthData" label="增长" width="80" align="right">
                <template #default="{ row }">
                  <span v-if="row.growth !== 0" :class="getGrowthClass(row.growth)">
                    {{ formatPercent(row.growth) }}
                  </span>
                  <span v-else style="color: #909399;">--</span>
                </template>
              </el-table-column>
            </el-table>
          </template>
          <div v-else-if="!rankingLoading" class="empty-ranking">
            <SmartBIEmptyState type="no-data" :show-action="false" />
          </div>
        </el-card>
      </el-col>

      <!-- 图表区 -->
      <el-col :xs="24" :lg="14">
        <el-card class="chart-card" v-loading="trendLoading">
          <template #header>
            <div class="card-header">
              <el-icon><TrendCharts /></el-icon>
              <span>销售趋势</span>
            </div>
          </template>
          <!-- Phase 6: DynamicChartRenderer when config available -->
          <DynamicChartRenderer
            v-if="useDynamicTrend && trendDynamicConfig"
            :config="trendDynamicConfig"
            :height="320"
          />
          <!-- Chart Insight: line→TREND; bar switch stays wired (exotic types → null) -->
          <ChartInsightProvider
            v-if="useDynamicTrend && trendDynamicConfig"
            :chart="trendChartInsightMeta"
            :perms="{ canViewFinance: canViewPrice }"
            :factory-id="factoryId ?? ''"
            depth="detailed"
          />
          <!-- Empty state for dynamic mode with no charts -->
          <el-empty
            v-else-if="isDynamicMode && !trendLoading"
            description="当前数据源暂无趋势图表，请查看下方探索图表"
            :image-size="80"
          />
          <!-- Legacy ECharts for system data mode -->
          <div v-else id="sales-trend-chart" ref="trendChartRef" class="chart-container"></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 产品占比图 -->
    <el-row :gutter="16" class="pie-section">
      <el-col :span="24">
        <el-card class="chart-card" v-loading="pieLoading">
          <template #header>
            <div class="card-header">
              <el-icon><TrendCharts /></el-icon>
              <span>产品类别销售占比</span>
            </div>
          </template>
          <!-- Phase 6: DynamicChartRenderer when config available (skip for single category) -->
          <DynamicChartRenderer
            v-if="useDynamicPie && pieDynamicConfig && pieLegacyData.length > 1"
            :config="pieDynamicConfig"
            :height="350"
          />
          <!-- Chart Insight: pie→PROPORTION (always wired) -->
          <ChartInsightProvider
            v-if="useDynamicPie && pieDynamicConfig && pieLegacyData.length > 1"
            :chart="pieChartInsightMeta"
            :perms="{ canViewFinance: canViewPrice }"
            :factory-id="factoryId ?? ''"
            depth="detailed"
          />
          <!-- Single category: show stat instead of useless full-circle pie -->
          <div v-else-if="useDynamicPie && pieDynamicConfig && pieLegacyData.length === 1" class="single-category-stat">
            <el-icon :size="40" color="#2D8B57"><TrendCharts /></el-icon>
            <div class="stat-info">
              <div class="stat-label">{{ pieLegacyData[0]?.[pieXAxisField] || '产品类别' }}</div>
              <div class="stat-value">100%</div>
              <div class="stat-hint">仅1个产品类别，无需占比分析</div>
            </div>
          </div>
          <!-- Empty state for dynamic mode with no charts -->
          <el-empty
            v-else-if="isDynamicMode && !pieLoading"
            description="当前数据源暂无产品分布图表"
            :image-size="80"
          />
          <!-- Legacy ECharts for system data mode -->
          <div v-else id="sales-pie-chart" ref="pieChartRef" class="pie-chart-container"></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Phase 6: AI 洞察面板 (仅动态数据源显示) -->
    <el-card v-if="aiInsights.length > 0" class="insight-card">
      <template #header>
        <div class="card-header">
          <el-icon><TrendCharts /></el-icon>
          <span>AI 智能洞察</span>
          <el-tag type="success" size="small" style="margin-left: 8px">来自上传数据</el-tag>
        </div>
      </template>
      <div class="insight-list">
        <div
          v-for="(insight, index) in aiInsights"
          :key="index"
          class="insight-item"
        >
          <el-icon class="insight-icon"><TrendCharts /></el-icon>
          <span>{{ insight }}</span>
        </div>
      </div>
    </el-card>

    <!-- Phase 6: 动态图表探索面板 (仅动态数据源显示) -->
    <el-card v-if="explorationCharts.length > 0 || explorationLoading" class="exploration-card">
      <template #header>
        <div class="card-header">
          <el-icon><TrendCharts /></el-icon>
          <span>数据图表探索</span>
          <el-tag type="info" size="small" style="margin-left: 8px">自动推荐</el-tag>
        </div>
      </template>
      <div v-loading="explorationLoading" class="exploration-grid">
        <div
          v-for="chart in explorationCharts"
          :key="chart.id"
          class="exploration-chart-item"
        >
          <div class="exploration-chart-header">
            <span class="exploration-chart-title">{{ chart.title }}</span>
            <ChartTypeSelector
              :current-type="chart.chartType"
              :numeric-columns="dataInfo.numericColumns"
              :categorical-columns="dataInfo.categoricalColumns"
              :date-columns="dataInfo.dateColumns"
              :row-count="dataInfo.rowCount"
              @switch-type="(newType: string) => handleChartTypeSwitch(chart.id, newType)"
              @refresh="handleChartRefresh(chart.id)"
            />
          </div>
          <DynamicChartRenderer
            :config="chart.config"
            :height="280"
          />
          <!-- Chart Insight per exploration chart: each instance has its own setup() → v-for safe -->
          <ChartInsightProvider
            :chart="salesExplorationChartMeta(chart)"
            :perms="{ canViewFinance: canViewPrice }"
            :factory-id="factoryId ?? ''"
            depth="detailed"
          />
        </div>
        <SmartBIEmptyState v-if="!explorationLoading && explorationCharts.length === 0" type="no-charts" :show-action="false" />
      </div>
    </el-card>

    <!-- Phase 6: 数据预览对话框 -->
    <el-dialog
      v-model="showDataPreview"
      title="数据预览"
      width="85%"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <div v-loading="previewLoading" class="preview-container">
        <div class="preview-info">
          <span>共 {{ previewData.total }} 条数据</span>
          <span>当前第 {{ previewPage }} / {{ previewData.totalPages || 1 }} 页</span>
        </div>
        <el-table
          :data="previewData.data"
          stripe
          border
          height="450"
          style="width: 100%"
        >
          <el-table-column
            v-for="header in previewData.headers"
            :key="header"
            :label="header"
            :prop="header"
            min-width="120"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              {{ row[header] ?? '-' }}
            </template>
          </el-table-column>
        </el-table>
        <div class="preview-pagination">
          <el-pagination
            v-model:current-page="previewPage"
            :page-size="50"
            :total="previewData.total"
            layout="total, prev, pager, next, jumper"
            @current-change="handlePreviewPageChange"
          />
        </div>
      </div>
      <template #footer>
        <el-button @click="closeDataPreview">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.sales-analysis-page {
  padding: 20px;
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
    gap: 12px;
  }
}

// 筛选栏
.filter-card {
  margin-bottom: 16px;
  border-radius: 8px;

  :deep(.el-card__body) {
    padding: 16px;
  }

  .filter-bar {
    display: flex;
    flex-wrap: wrap;
    gap: 24px;
    align-items: center;
  }

  .filter-item {
    display: flex;
    align-items: center;
    gap: 8px;

    .filter-label {
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 13px;
      color: var(--el-text-color-regular, #606266);
      white-space: nowrap;
    }
  }
}

// KPI 卡片
.kpi-section {
  margin-bottom: 16px;

  .el-col {
    margin-bottom: 16px;
  }

  // 5-column responsive layout
  .kpi-col-5 {
    @media (min-width: 992px) {
      flex: 0 0 20%;
      max-width: 20%;
    }
  }
}

.kpi-card {
  border-radius: 8px;
  text-align: center;
  padding: 8px 0;
  border-top: 3px solid transparent;
  transition: transform 0.25s cubic-bezier(0.4, 0, 0.2, 1), box-shadow 0.25s cubic-bezier(0.4, 0, 0.2, 1);

  &.kpi-accent-0 { border-top-color: #2D8B57; }
  &.kpi-accent-1 { border-top-color: #36B37E; }
  &.kpi-accent-2 { border-top-color: #FFAB00; }
  &.kpi-accent-3 { border-top-color: #6554C0; }
  &.kpi-accent-4 { border-top-color: #FF5630; }

  &:hover {
    transform: translateY(-2px);
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  }

  &.kpi-no-data {
    opacity: 0.7;

    .kpi-value {
      color: var(--el-text-color-secondary, #909399);
    }
  }

  .kpi-label {
    font-size: 13px;
    color: var(--el-text-color-secondary, #909399);
    margin-bottom: 8px;
  }

  .kpi-value-row {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    margin-bottom: 8px;
  }

  .kpi-sparkline {
    flex-shrink: 0;
    opacity: 0.85;
  }

  .kpi-value {
    font-size: 28px;
    font-weight: 600;
    color: var(--el-text-color-primary, #303133);
  }

  .kpi-trend {
    font-size: 14px;
    font-weight: 500;

    &.growth-up {
      color: var(--el-color-success, #36B37E);
    }

    &.growth-down {
      color: var(--el-color-danger, #FF5630);
    }

    .compare-text {
      margin-left: 4px;
      font-size: 12px;
      color: var(--el-text-color-secondary, #909399);
      font-weight: normal;
    }

    .no-data-text {
      color: var(--el-text-color-placeholder, #C0C4CC);
      font-size: 12px;
      font-weight: normal;
    }
  }
}

// Sales ranking inline progress bar
.sales-bar-cell {
  display: flex;
  align-items: center;
  gap: 8px;

  .sales-bar-bg {
    flex: 1;
    height: 6px;
    background: var(--el-fill-color-light, #f0f2f5);
    border-radius: 3px;
    overflow: hidden;

    .sales-bar-fill {
      height: 100%;
      background: linear-gradient(90deg, var(--el-color-primary, #2D8B57), var(--el-color-primary-light-3, #79bbff));
      border-radius: 3px;
      transition: width 0.6s cubic-bezier(0.4, 0, 0.2, 1);
    }
  }

  .sales-bar-value {
    flex-shrink: 0;
    font-size: 12px;
    font-weight: 500;
    color: var(--el-text-color-primary, #303133);
    font-variant-numeric: tabular-nums;
    min-width: 50px;
    text-align: right;
  }
}

.empty-ranking {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;
}

// 内容区
.content-section {
  margin-bottom: 16px;

  .el-col {
    margin-bottom: 16px;
  }
}

.ranking-card, .chart-card {
  border-radius: 8px;
  height: 100%;

  .card-header {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;

    .el-icon {
      color: var(--el-color-primary, #2D8B57);
    }
  }
}

.ranking-card {
  :deep(.el-table) {
    .el-table__row {
      transition: background-color 0.15s ease;
      cursor: default;

      &:hover > td {
        background-color: var(--el-color-primary-light-9, #ecf5ff) !important;
      }

      .sales-bar-fill {
        transition: width 0.6s cubic-bezier(0.4, 0, 0.2, 1), filter 0.2s ease;
      }

      &:hover .sales-bar-fill {
        filter: brightness(1.1);
      }
    }
  }
}

.rank-badge {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--el-fill-color, #f0f2f5);
  color: var(--el-text-color-secondary, #909399);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;

  &.top-3 {
    background: linear-gradient(135deg, #FFD700, #FFA500);
    color: #fff;
  }
}

.growth-up {
  color: var(--el-color-success, #36B37E);
}

.growth-down {
  color: var(--el-color-danger, #FF5630);
}

.chart-container {
  height: 320px;
  width: 100%;
}

.pie-section {
  margin-bottom: 16px;
}

.pie-chart-container {
  height: 350px;
  width: 100%;
}

.single-category-stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 200px;
  gap: 16px;

  .stat-info {
    text-align: center;
  }

  .stat-label {
    font-size: 16px;
    font-weight: 600;
    color: var(--el-text-color-primary, #303133);
    margin-bottom: 4px;
  }

  .stat-value {
    font-size: 32px;
    font-weight: 700;
    color: var(--el-color-primary, #2D8B57);
    margin-bottom: 4px;
  }

  .stat-hint {
    font-size: 12px;
    color: var(--el-text-color-secondary, #909399);
  }
}

// Phase 6: 数据源选择器
.datasource-option {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;

  .datasource-meta {
    font-size: 12px;
    color: var(--el-text-color-secondary, #909399);
  }
}

// Phase 6: AI 洞察面板
.insight-card {
  margin-top: 16px;
  border-radius: 8px;
  border-left: 4px solid var(--el-color-success, #36B37E);

  .card-header {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;

    .el-icon {
      color: var(--el-color-success, #36B37E);
    }
  }
}

.insight-list {
  .insight-item {
    display: flex;
    align-items: flex-start;
    gap: 10px;
    padding: 12px 0;
    border-bottom: 1px solid var(--el-border-color-lighter, #f0f2f5);
    line-height: 1.6;
    color: var(--el-text-color-primary, #303133);

    &:last-child {
      border-bottom: none;
    }

    .insight-icon {
      flex-shrink: 0;
      margin-top: 2px;
      color: var(--el-color-success, #36B37E);
    }
  }
}

// Phase 6: 动态图表探索面板
.exploration-card {
  margin-top: 16px;
  border-radius: 8px;
  border-left: 4px solid var(--el-color-primary, #2D8B57);

  .card-header {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;

    .el-icon {
      color: var(--el-color-primary, #2D8B57);
    }
  }
}

.exploration-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  min-height: 100px;
}

.exploration-chart-item {
  background: var(--el-fill-color-lighter, #fafbfc);
  border-radius: 8px;
  padding: 12px;
  border: 1px solid var(--el-border-color-light, #ebeef5);
  transition: box-shadow 0.3s;

  &:hover {
    box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  }
}

.exploration-chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.exploration-chart-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-primary, #303133);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 200px;
}

// Phase 6: 数据预览对话框
.preview-container {
  .preview-info {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 16px;
    padding: 8px 12px;
    background: var(--el-fill-color-light, #f5f7fa);
    border-radius: 4px;
    font-size: 13px;
    color: var(--el-text-color-regular, #606266);
  }

  .preview-pagination {
    display: flex;
    justify-content: flex-end;
    margin-top: 16px;
  }
}

// 响应式
@media (max-width: 1366px) {
  .chart-container {
    height: 280px;
  }

  .pie-chart-container {
    height: 300px;
  }

  .exploration-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .filter-bar {
    flex-direction: column;
    align-items: flex-start !important;
  }

  .filter-item {
    width: 100%;
  }

  .chart-container {
    height: 240px;
  }

  .pie-chart-container {
    height: 260px;
  }

  .exploration-grid {
    grid-template-columns: 1fr;
  }
}

// 图表卡片悬浮效果
.ranking-card,
.chart-card {
  transition: transform 0.2s ease, box-shadow 0.2s ease;

  &:hover {
    transform: translateY(-2px);
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.1);
  }
}
</style>
