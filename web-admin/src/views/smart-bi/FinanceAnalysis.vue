<script setup lang="ts">
/**
 * SmartBI 财务分析页面
 * 提供财务数据分析，包含利润、成本、应收、应付、预算等模块
 * Phase 6: Enhanced with DynamicChartRenderer + ChartTypeSelector for dynamic chart views
 */
import { ref, computed, onMounted, watch, onUnmounted, nextTick } from 'vue';
import { useChartResize } from '@/composables/useChartResize';
import { useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import {
  getUploadHistory,
  deduplicateUploads,
  getDynamicAnalysis,
  getUploadTableData,
  type UploadHistoryItem,
  type DynamicAnalysisResponse,
  type TableDataResponse
} from '@/api/smartbi';
import {
  recommendChart,
  batchBuildCharts,
  buildChart,
} from '@/api/smartbi/python-service';
import { getFinanceSummary, type FinanceSummary } from '@/api/smartbi/gold';
import { getGoldDataRange } from '@/api/smartbi/dataRange';
import { resolveAllHistoryRange } from './analysisDefaults';
import { ElMessage } from 'element-plus';
import {
  Refresh,
  TrendCharts,
  Wallet,
  Money,
  CreditCard,
  Document,
  Warning,
  WarningFilled,
  SuccessFilled,
  InfoFilled,
  Calendar,
  View,
  Close,
  Clock
} from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import { formatNumber, formatAxisValue } from '@/utils/format-number';
import { toApiDateString } from '@/utils/dateFormat';
import DynamicChartRenderer from '@/components/smartbi/DynamicChartRenderer.vue';
import ChartTypeSelector from '@/components/smartbi/ChartTypeSelector.vue';
import SmartBIEmptyState from '@/components/smartbi/SmartBIEmptyState.vue';
import RestaurantPhaseIIPlaceholder from '@/components/smartbi/RestaurantPhaseIIPlaceholder.vue';
import RestaurantFinanceContent from '@/components/smartbi/RestaurantFinanceContent.vue';
import RestaurantKitchenCostContent from '@/components/smartbi/RestaurantKitchenCostContent.vue';
import TemplateGrid from './components/TemplateGrid.vue';
import type { ChartConfig } from '@/types/smartbi';
import ChartInsightProvider from './components/ChartInsightProvider.vue';
import type { ChartWithMeta } from './components/chartInsight';
// Day 9 数据织网 Sub-Project A: capability-driven card visibility
import { useCapability } from '@/composables/useCapability';
import CapabilityGate from '@/components/CapabilityGate.vue';
import UnlockMoreCTA from '@/components/UnlockMoreCTA.vue';
import type { TableRow } from '@/types/api';

const route = useRoute();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
// canViewPrice: PR #520 pattern — entire view renders 利润/成本/应收/应付/预算 KPIs;
// hide for non-PRICE_VIEW_ROLES (e.g. viewer) to avoid misleading empty UI.
// Defense-in-depth: outer <template v-if> wrapper (this PR) + inner per-cell v-if (#598).
const canViewPrice = computed(() => permissionStore.canViewPrice);
// Apr 24 2026 UX P0-1: restaurant tenants 没有 cost/profit 数据 (Silver 无),
// 顶部 KPI 改显 Gold 营收/订单/客单价/门店. Manufacturing tenants 保持原样.
const isRestaurantTenant = computed(() => authStore.factoryType === 'RESTAURANT');
// Phase IIa rollback flag (spec §5.7): default ON. Ops can flip
// VITE_PHASE_IIA_ENABLED=false to fall back to RestaurantPhaseIIPlaceholder
// without a code revert. The placeholder file stays in repo for this reason.
const phaseIIaEnabled = computed(() => import.meta.env.VITE_PHASE_IIA_ENABLED !== 'false');
// Phase IIb rollback flag (spec §5.7): default ON. Ops can flip
// VITE_PHASE_IIB_ENABLED=false to hide the 成本运营 tab without a code revert.
const phaseIIbEnabled = computed(() => import.meta.env.VITE_PHASE_IIB_ENABLED !== 'false');

// 分析类型
// Phase IIb (2026-05-15): restaurants now see TWO tabs — 营收概览 (profit, Phase IIa)
// and 成本运营 (kitchen-cost, NEW). Factory tenants see all 5 legacy tabs (profit/cost/...).
type AnalysisType = 'profit' | 'cost' | 'receivable' | 'payable' | 'budget' | 'kitchen-cost';
const validTabs: AnalysisType[] = ['profit', 'cost', 'receivable', 'payable', 'budget', 'kitchen-cost'];
const initTab = validTabs.includes(route.query.tab as AnalysisType) ? (route.query.tab as AnalysisType) : 'profit';
const analysisType = ref<AnalysisType>(initTab);
// Phase IIb (2026-05-15): restaurants see {profit, kitchen-cost} only.
// Whitelist replaces the IIa "force to profit" pattern. cost/receivable/payable/budget
// remain hidden for restaurant tenants (factory-only data).
const RESTAURANT_ALLOWED_TABS: AnalysisType[] = ['profit', 'kitchen-cost'];
if (isRestaurantTenant.value && !RESTAURANT_ALLOWED_TABS.includes(analysisType.value)) {
  analysisType.value = 'profit';
}
watch(() => route.query.tab, (newTab) => {
  if (isRestaurantTenant.value && newTab && !RESTAURANT_ALLOWED_TABS.includes(newTab as AnalysisType)) {
    analysisType.value = 'profit';
  }
});
// Also guard programmatic type switches.
watch(analysisType, (newType) => {
  if (isRestaurantTenant.value && !RESTAURANT_ALLOWED_TABS.includes(newType)) {
    analysisType.value = 'profit';
  }
});

// 日期范围
const dateRange = ref<[Date, Date] | null>(null);

// Phase IIb (2026-05-15): default 365-day range for the restaurant kitchen-cost
// sub-component (mirrors RestaurantFinanceContent.vue default). Computed off
// `new Date()` once — accept staleness during the user's session.
const restaurantDefaultDateRange = computed(() => {
  const end = new Date();
  const start = new Date();
  start.setTime(start.getTime() - 3600 * 1000 * 24 * 365);
  return {
    startDate: toApiDateString(start),
    endDate: toApiDateString(end),
  };
});

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
  },
  {
    text: '本年',
    value: () => {
      const end = new Date();
      const start = new Date(end.getFullYear(), 0, 1);
      return [start, end];
    }
  }
];

// 加载状态
const loading = ref(false);
const loadError = ref('');
const noSystemData = ref(false);

// AbortController: cancel in-flight requests when filters change
let financeAbortController: AbortController | null = null;

// Data quality check: flag extreme values or all-zero tabs
const dataQualityWarning = computed(() => {
  if (selectedDataSource.value !== 'system') return '';
  if (loading.value) return '';
  const kpi = kpiData.value;
  // Extremely negative profit values indicate incorrect data
  if ((kpi.grossProfit && kpi.grossProfit < -100000000) || (kpi.netProfit && kpi.netProfit < -100000000)) {
    return '系统财务数据可能不完整，显示的利润值异常偏大。建议切换到"上传数据"模式获取准确分析。';
  }
  // Per-tab zero checks
  const tab = analysisType.value;
  if (tab === 'cost' && kpi.totalCost === 0 && kpi.materialCost === 0 && kpi.laborCost === 0) {
    return '当前无成本数据，请确认系统数据已导入或切换到上传数据模式。';
  }
  if (tab === 'receivable' && kpi.totalReceivable === 0 && kpi.receivableAge30 === 0 && kpi.receivableAge90Plus === 0) {
    return '当前无应收账款数据，请确认系统数据已导入或切换到上传数据模式。';
  }
  if (tab === 'payable' && kpi.totalPayable === 0 && kpi.payableAge30 === 0 && kpi.payableAge90Plus === 0) {
    return '当前无应付账款数据，请确认系统数据已导入或切换到上传数据模式。';
  }
  if (tab === 'budget' && kpi.budgetTotal === 0 && kpi.budgetUsed === 0 && kpi.budgetRemaining === 0) {
    return '当前无预算数据，请确认系统数据已导入或切换到上传数据模式。';
  }
  return '';
});

// Whether the current tab has no chart to display (after loading completes)
const noChartForTab = computed(() => {
  if (loading.value) return false;
  if (selectedDataSource.value !== 'system') return false;
  if (!chartConfig.value && !mainDynamicConfig.value) return true;
  // Also treat charts with empty or all-zero data as "no chart"
  if (chartConfig.value && 'data' in chartConfig.value) {
    const data = (chartConfig.value as TableRow).data;
    if (Array.isArray(data)) {
      if (data.length === 0) return true;
      // All-zero data: every numeric value in every item is 0
      const allZero = data.every((item: TableRow) =>
        Object.values(item).every(v => typeof v === 'string' || v === 0 || v === null)
      );
      if (allZero) return true;
    }
  }
  return false;
});

// 数据源选择 — default empty, will be set after loading sources
const dataSources = ref<UploadHistoryItem[]>([]);
const selectedDataSource = ref<string>('');

// AI 洞察
const aiInsights = ref<string[]>([]);
const insightTimestamp = ref<Date | null>(null);

// Infer insight severity from content keywords for visual differentiation
function getInsightType(text: string): { icon: typeof WarningFilled; tagType: '' | 'success' | 'warning' | 'danger' | 'info' } {
  if (/增长|上升|改善|提升|超额|达标/.test(text)) return { icon: SuccessFilled, tagType: 'success' };
  if (/下降|亏损|减少|低于|恶化|不足|严重/.test(text)) return { icon: WarningFilled, tagType: 'danger' };
  if (/注意|关注|波动|风险|异常/.test(text)) return { icon: Warning, tagType: 'warning' };
  return { icon: InfoFilled, tagType: 'info' };
}

function formatInsightTime(date: Date | string) {
  const d = new Date(date);
  return `分析生成于 ${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/**
 * Parse insight text and add citation references linking to exploration charts.
 * Matches chart titles found in explorationCharts against insight sentences.
 */
function parseInsightCitations(content: string): Array<{ text: string; chartIndex?: number; chartTitle?: string }> {
  if (!explorationCharts.value.length) return [{ text: content }];

  // Build keyword patterns from actual chart titles
  const patterns = explorationCharts.value.map((chart, idx) => ({
    // Match if the insight contains 2+ chars from the chart title
    keywords: chart.title.replace(/[()（）\[\]【】]/g, ''),
    chartIndex: idx,
    title: chart.title
  }));

  const sentences = content.split(/(?<=[。；;！!？?\n])/);
  const result: Array<{ text: string; chartIndex?: number; chartTitle?: string }> = [];
  const usedCharts = new Set<number>();

  for (const sentence of sentences) {
    if (!sentence.trim()) continue;
    let matched = false;
    for (const p of patterns) {
      // Check if the insight sentence contains significant keywords from the chart title
      const titleWords = p.keywords.split(/[\s,，、/]+/).filter(w => w.length >= 2);
      const matchCount = titleWords.filter(w => sentence.includes(w)).length;
      if (matchCount >= 1 && !usedCharts.has(p.chartIndex)) {
        usedCharts.add(p.chartIndex);
        result.push({ text: sentence, chartIndex: p.chartIndex, chartTitle: p.title });
        matched = true;
        break;
      }
    }
    if (!matched) {
      result.push({ text: sentence });
    }
  }
  return result;
}

function scrollToChart(chartIndex: number) {
  const chartElements = document.querySelectorAll('.exploration-chart-item');
  if (chartElements[chartIndex]) {
    chartElements[chartIndex].scrollIntoView({ behavior: 'smooth', block: 'center' });
  }
}

// 数据预览对话框
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

// 财务 KPI 数据
interface FinanceKPI {
  // 利润相关
  grossProfit: number;
  grossProfitMargin: number | null;
  netProfit: number;
  netProfitMargin: number | null;
  // 成本相关
  totalCost: number;
  costGrowth: number;
  materialCost: number;
  laborCost: number;
  overheadCost: number;
  // 应收相关
  totalReceivable: number;
  receivableAge30: number;
  receivableAge60: number;
  receivableAge90Plus: number;
  // 应付相关
  totalPayable: number;
  payableAge30: number;
  payableAge60: number;
  payableAge90Plus: number;
  // 预算相关
  budgetTotal: number;
  budgetUsed: number;
  budgetRemaining: number;
  budgetUsageRate: number;
}

const kpiData = ref<FinanceKPI>({
  grossProfit: 0,
  grossProfitMargin: null,
  netProfit: 0,
  netProfitMargin: null,
  totalCost: 0,
  costGrowth: 0,
  materialCost: 0,
  laborCost: 0,
  overheadCost: 0,
  totalReceivable: 0,
  receivableAge30: 0,
  receivableAge60: 0,
  receivableAge90Plus: 0,
  totalPayable: 0,
  payableAge30: 0,
  payableAge60: 0,
  payableAge90Plus: 0,
  budgetTotal: 0,
  budgetUsed: 0,
  budgetRemaining: 0,
  budgetUsageRate: 0
});

// 预警列表
interface WarningItem {
  level: 'danger' | 'warning' | 'info';
  title: string;
  description: string;
  amount?: number;
}
const warnings = ref<WarningItem[]>([]);

// 图表配置 (从 API 获取) - local type, distinct from @/types/smartbi ChartConfig
interface ChartConfig_Local {
  chartType: string;
  title?: string;
  xAxisField?: string;
  yAxisField?: string;
  seriesField?: string;
  data?: Array<TableRow>;
  options?: TableRow;
}

interface DynamicChartConfig {
  chartType: string;
  title?: string;
  subTitle?: string;
  xAxis?: {
    type: string;
    name?: string;
    data?: string[];
  };
  yAxis?: Array<{
    type: string;
    name?: string;
    position?: string;
    min?: number;
    max?: number;
    axisLabel?: TableRow;
  }>;
  legend?: {
    show?: boolean;
    data?: string[];
    position?: string;
    orient?: string;
  };
  series?: Array<{
    name?: string;
    type: string;
    data?: unknown[];
    yAxisIndex?: number;
    stack?: string;
    smooth?: boolean;
    areaStyle?: boolean;
    itemStyle?: TableRow;
    label?: TableRow;
  }>;
  tooltip?: {
    trigger?: string;
    axisPointer?: TableRow;
    formatter?: string;
  };
  options?: TableRow;
}

const chartConfig = ref<ChartConfig_Local | DynamicChartConfig | null>(null);
const secondaryChartConfig = ref<ChartConfig_Local | DynamicChartConfig | null>(null);

// ==================== Phase 6: Dynamic Chart Exploration ====================

// Main chart rendered via DynamicChartRenderer (when we have a typed config)
const mainDynamicConfig = ref<ChartConfig | null>(null);
const useDynamicRenderer = ref(false);

// Multi-chart exploration from dynamic data sources
interface ExplorationChart {
  id: string;
  chartType: string;
  title: string;
  config: ChartConfig;
  xField?: string;
  yFields?: string[];
}
const explorationCharts = ref<ExplorationChart[]>([]);
const explorationLoading = ref(false);

// ==================== ChartInsightProvider: runtime type→meta mapping ====================

/** EXOTIC types we refuse to guess insight for (no insight = no fabrication). */
const EXOTIC_CHART_TYPES = new Set(['scatter', 'waterfall', 'radar', 'heatmap', 'gauge', 'funnel', 'sankey']);

/**
 * Build ChartWithMeta for the main dynamic chart.
 * Runtime chart type is only known from mainDynamicConfig.value?.chartType.
 * BAR/LINE/PIE → infer meta + extract config.
 * EXOTIC → null (renders nothing, honest).
 */
const mainChartInsightMeta = computed<ChartWithMeta | null>(() => {
  const cfg = mainDynamicConfig.value;
  if (!cfg) return null;
  const ct = (cfg.chartType ?? '').toLowerCase();
  if (EXOTIC_CHART_TYPES.has(ct)) return null;
  if (ct !== 'bar' && ct !== 'line' && ct !== 'pie') return null;

  // Infer meta from runtime chart type
  const meta = ct === 'line'
    ? { xDim: 'time' as const, yMetric: 'revenue' as const, aggregation: 'sum' as const, domain: 'finance' as const }
    : ct === 'pie'
      ? { xDim: 'category' as const, yMetric: 'revenue' as const, aggregation: 'sum' as const, domain: 'finance' as const }
      : /* bar */ { xDim: 'category' as const, yMetric: 'revenue' as const, aggregation: 'sum' as const, domain: 'finance' as const };

  // Normalize config: ChartConfig may be legacy (data/xAxisField) or ECharts-style (xAxis.data/series)
  const c = cfg as Record<string, unknown>;
  let xData: string[] = [];
  let seriesData: Array<{ type?: string; data?: unknown[] }> = [];

  if (Array.isArray(c['xAxis'] && (c['xAxis'] as Record<string, unknown>)['data'])) {
    xData = ((c['xAxis'] as Record<string, unknown>)['data']) as string[];
  } else if (Array.isArray(c['data']) && typeof c['xAxisField'] === 'string') {
    xData = (c['data'] as Record<string, unknown>[]).map(r => String(r[c['xAxisField'] as string] ?? ''));
  }

  if (Array.isArray(c['series'])) {
    seriesData = c['series'] as Array<{ type?: string; data?: unknown[] }>;
  } else if (Array.isArray(c['data']) && typeof c['yAxisField'] === 'string') {
    seriesData = [{ type: ct, data: (c['data'] as Record<string, unknown>[]).map(r => Number(r[c['yAxisField'] as string] ?? 0)) }];
  }

  return {
    chartType: ct,
    title: typeof cfg.title === 'string' ? cfg.title : undefined,
    meta,
    config: { xAxis: { data: xData }, series: seriesData },
  };
});

/**
 * Build ChartWithMeta for a single exploration chart item.
 * Called per-chart in the v-for exploration loop.
 * chart.chartType is set at build time (could be bar/line/pie/scatter/etc.)
 */
function explorationChartInsightMeta(chart: ExplorationChart): ChartWithMeta | null {
  const ct = (chart.chartType ?? '').toLowerCase();
  if (EXOTIC_CHART_TYPES.has(ct)) return null;
  if (ct !== 'bar' && ct !== 'line' && ct !== 'pie') return null;

  const meta = ct === 'line'
    ? { xDim: 'time' as const, yMetric: 'revenue' as const, aggregation: 'sum' as const, domain: 'finance' as const }
    : { xDim: 'category' as const, yMetric: 'revenue' as const, aggregation: 'sum' as const, domain: 'finance' as const };

  const c = chart.config as unknown as Record<string, unknown>;
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

/**
 * Build exploration charts from uploaded dynamic data
 * Uses Python recommend + batch build for multi-chart views
 */
async function buildExplorationCharts(data: TableRow[]) {
  if (!data || data.length === 0) return;

  explorationLoading.value = true;
  dynamicRawData.value = data;

  try {
    // Step 1: Get chart recommendations from Python
    const recResult = await recommendChart(data);

    if (recResult.success && recResult.recommendations && recResult.recommendations.length > 0) {
      // Store data info for ChartTypeSelector
      if (recResult.dataInfo) {
        dataInfo.value = {
          numericColumns: recResult.dataInfo.numericColumns || [],
          categoricalColumns: recResult.dataInfo.categoricalColumns || [],
          dateColumns: recResult.dataInfo.dateColumns || [],
          rowCount: recResult.dataInfo.rowCount || data.length,
        };
      }

      // Step 2: Build charts for top 4 recommendations
      // P2 PERF fix: Sample data for chart building to avoid sending full dataset 4x
      // Charts render identically with 1000 rows; avoids ~4x payload duplication
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
            config: c.config as unknown as ChartConfig,
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

/**
 * Handle chart type switch from ChartTypeSelector
 */
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
          config: result.option as unknown as ChartConfig,
        };
      }
    } else {
      ElMessage.warning('图表类型切换失败: ' + (result.error || '未知错误'));
    }
  } catch (error) {
    console.warn('图表类型切换失败:', error);
  }
}

/**
 * Refresh a single exploration chart (re-recommend)
 */
async function handleChartRefresh(chartId: string) {
  if (dynamicRawData.value.length === 0) return;

  const chart = explorationCharts.value.find(c => c.id === chartId);
  if (!chart) return;

  // Randomly pick a different chart type
  const types = ['bar', 'line', 'pie', 'area', 'scatter', 'waterfall', 'radar'];
  const currentType = chart.chartType;
  const alternatives = types.filter(t => t !== currentType);
  const randomType = alternatives[Math.floor(Math.random() * alternatives.length)];

  await handleChartTypeSwitch(chartId, randomType);
}

/**
 * Convert main chart config to DynamicChartRenderer-compatible format
 */
function convertToDynamicConfig(config: ChartConfig_Local | DynamicChartConfig | null): ChartConfig | null {
  if (!config) return null;

  // If it already has series[], it's a DynamicChartConfig - use directly
  if ('series' in config && Array.isArray(config.series)) {
    return config as unknown as ChartConfig;
  }

  // If it's a ChartConfig_Local with data[], convert to LegacyChartConfig format
  if ('data' in config && Array.isArray(config.data)) {
    return config as unknown as ChartConfig;
  }

  return null;
}

// Container ref for ResizeObserver-based chart resize
const financePageRef = ref<HTMLElement>();

// 图表实例 (kept for legacy fallback)
let mainChart: echarts.ECharts | null = null;

// 分析类型配置
// Apr 24 UX: 成本/应收/应付/预算 tabs render 0 for restaurants (no fact_cost_line +
// no A/R, A/P, budget data sources for restaurants). Hidden instead of showing
// misleading zeros.
// Phase IIb (2026-05-15): restaurants see {profit, kitchen-cost} — overview + ops.
// Factory tenants see the 5 legacy tabs (profit/cost/receivable/payable/budget).
// `restaurantOnly: true` flag = visible only to restaurant tenants.
interface AnalysisTypeEntry {
  type: AnalysisType;
  label: string;
  icon: typeof TrendCharts;
  /** When true: only restaurant tenants see this tab. When false/undefined: factory tenants see it. */
  restaurantOnly?: boolean;
}
const allAnalysisTypes: AnalysisTypeEntry[] = [
  { type: 'profit', label: '利润分析', icon: TrendCharts },
  { type: 'cost', label: '成本分析', icon: Wallet },
  { type: 'receivable', label: '应收分析', icon: Money },
  { type: 'payable', label: '应付分析', icon: CreditCard },
  { type: 'budget', label: '预算分析', icon: Document },
  // Phase IIb: 餐饮专属。Label 跟 RestaurantFinanceContent header 标签呼应：
  // profit → 营收概览，kitchen-cost → 成本运营。
  { type: 'kitchen-cost', label: '成本运营', icon: Wallet, restaurantOnly: true },
];
const analysisTypes = computed(() => {
  if (isRestaurantTenant.value) {
    // Restaurant: show 'profit' (营收概览, IIa) + 'kitchen-cost' (成本运营, IIb if enabled)
    return allAnalysisTypes.filter(t =>
      t.type === 'profit' || (t.type === 'kitchen-cost' && phaseIIbEnabled.value),
    );
  }
  // Factory tenants: filter out restaurant-only tabs
  return allAnalysisTypes.filter(t => !t.restaurantOnly);
});

// Gold-backed 营收/订单/客单价/门店 KPIs for restaurant tenants.
// Replaces the 毛利润/净利润 legacy row when Silver has no cost data.
const goldFinSummary = ref<FinanceSummary | null>(null);
const goldFinFallbackLabel = ref<string>('');
async function loadGoldFinSummary() {
  if (!factoryId.value || !isRestaurantTenant.value) {
    goldFinSummary.value = null;
    goldFinFallbackLabel.value = '';
    return;
  }
  // PR #468 §6 P1-A: dateRange 运行时是 [string, string] (value-format="YYYY-MM-DD"),
  // 旧 iso(d: Date) 在用户改日期后会 TypeError. 用 toApiDateString 容忍 string/Date.
  const [s, e] = dateRange.value
    ? [toApiDateString(dateRange.value[0]), toApiDateString(dateRange.value[1])]
    : (() => {
        const now = new Date();
        const start = new Date(); start.setTime(start.getTime() - 3600 * 1000 * 24 * 365);
        return [toApiDateString(start), toApiDateString(now)];
      })();
  try {
    const r = await getFinanceSummary({
      factoryId: factoryId.value, startDate: s, endDate: e, topNStores: 3,
    });
    if (r && r.billCount > 0) {
      goldFinSummary.value = r;
      goldFinFallbackLabel.value = '';
      return;
    }
    // Fallback chain — same pattern as RestaurantV2 / Trends
    const y = new Date().getFullYear();
    const chain: Array<[string, string, string]> = [
      [`${y - 1}-01-01`, `${y - 1}-12-31`, `${y - 1}全年`],
      [`${y - 2}-01-01`, `${y - 2}-12-31`, `${y - 2}全年`],
    ];
    for (const [cs, ce, label] of chain) {
      const fb = await getFinanceSummary({ factoryId: factoryId.value, startDate: cs, endDate: ce, topNStores: 3 });
      if (fb && fb.billCount > 0) {
        goldFinSummary.value = fb;
        goldFinFallbackLabel.value = label;
        return;
      }
    }
    goldFinSummary.value = r;
    goldFinFallbackLabel.value = '';
  } catch {
    goldFinSummary.value = null;
    goldFinFallbackLabel.value = '';
  }
}
watch(dateRange, () => { if (isRestaurantTenant.value) loadGoldFinSummary(); });

const { fetchCapability } = useCapability();

onMounted(async () => {
  // Restaurant tenants render <RestaurantPhaseIIPlaceholder> per template
  // v-if. Skip all data fetching to avoid 404 spam against factory-only
  // endpoints (/smart-bi/analysis/finance, /smartbi-api/capability/...).
  if (isRestaurantTenant.value) {
    return;
  }

  // Day 9 数据织网 Sub-Project A: prime capability cache (fire-and-forget,
  // useCapability handles errors and is fail-open). Drives <CapabilityGate>
  // visibility for KPI cards below.
  fetchCapability();

  // WS4 #10: 默认全部历史 (而非近365天 — 财务数据可能落在更早区间)。探 gold 数据窗,
  // 失败回落宽窗。绝不回到固定 N 天窗。
  let allHistory: [string, string];
  try {
    const probe = factoryId.value ? await getGoldDataRange(factoryId.value) : null;
    allHistory = resolveAllHistoryRange(probe);
  } catch {
    allHistory = resolveAllHistoryRange(null);
  }
  dateRange.value = [new Date(allHistory[0]), new Date(allHistory[1])];

  // Load data source list in background (for dropdown)
  loadDataSources();

  // Always default to system data for reliable KPI display
  selectedDataSource.value = 'system';
  await loadFinanceData();

  // Initialize legacy chart container (only needed for system data fallback)
  await nextTick();
  initChart();

  // Fire Gold fetch for restaurant tenants (parallel, doesn't block init)
  if (isRestaurantTenant.value) loadGoldFinSummary();
});

// 加载数据源列表
async function loadDataSources() {
  try {
    const res = await getUploadHistory();
    if (res.success && res.data) {
      // 只显示处理成功的上传记录，按 fileName+sheetName 去重
      dataSources.value = deduplicateUploads(res.data.filter(
        (item: UploadHistoryItem) => item.status === 'COMPLETED' || item.status === 'SUCCESS'
      ));
    }
  } catch (error) {
    console.warn('加载数据源列表失败:', error);
    // 不显示错误提示，因为可能是 PostgreSQL 功能未启用
  }
}

// 数据源切换处理
async function onDataSourceChange(sourceId: string) {
  if (sourceId === 'system') {
    // 切换回系统数据
    aiInsights.value = [];
    explorationCharts.value = [];
    dynamicRawData.value = [];
    useDynamicRenderer.value = false;
    mainDynamicConfig.value = null;
    await loadFinanceData();
    // Re-init legacy chart after switching back to system data
    await nextTick();
    initChart();
  } else {
    // 加载上传的动态数据
    await loadDynamicData(Number(sourceId));
  }
}

// 加载动态数据
async function loadDynamicData(uploadId: number) {
  loading.value = true;
  loadError.value = '';
  aiInsights.value = [];
  // Reset previous data to avoid showing stale values
  resetData();

  try {
    const res = await getDynamicAnalysis(uploadId, 'finance');

    if (res.success && res.data) {
      const data = res.data as DynamicAnalysisResponse;

      // 更新 AI 洞察
      if (data.insights) {
        aiInsights.value = data.insights;
        insightTimestamp.value = new Date();
      }

      // 更新 KPI 数据 (从 kpiCards 提取)
      if (data.kpiCards && data.kpiCards.length > 0) {
        updateKpiFromDynamicData(data.kpiCards);
      }

      // 更新图表
      if (data.charts && data.charts.length > 0) {
        updateChartFromDynamicData(data.charts);
      }

      // Phase 6: Fetch raw data for exploration charts + KPI fallback
      try {
        const tableRes = await getUploadTableData(uploadId, 0, 2000);
        if (tableRes.success && tableRes.data && tableRes.data.data.length > 0) {
          const rawRows = tableRes.data.data as TableRow[];
          buildExplorationCharts(rawRows);

          // P1-1 fallback: if backend returned no/empty KPIs, compute from raw data
          const kpi = kpiData.value;
          const allZero = kpi.grossProfit === 0 && kpi.netProfit === 0 &&
            kpi.totalCost === 0 && kpi.totalReceivable === 0 &&
            kpi.totalPayable === 0 && kpi.budgetTotal === 0;
          if (allZero) {
            computeKpiFromRawData(rawRows);
          }
        }
      } catch (e) {
        console.warn('加载探索图表数据失败:', e);
      }

      // 清空预警 (动态数据暂不支持预警)
      warnings.value = [];
    } else {
      loadError.value = res.message || '加载分析数据失败';
    }
  } catch (error) {
    console.error('加载动态数据失败:', error);
    loadError.value = '加载分析数据失败，请检查网络连接后重试';
  } finally {
    loading.value = false;
    generateSmartWarnings();
  }
}

// 从动态数据更新 KPI
function updateKpiFromDynamicData(kpiCards: DynamicAnalysisResponse['kpiCards']) {
  // 重置 KPI 数据
  resetData();

  // Track whether we matched any KPI cards by title
  let matchedCount = 0;

  for (const kpi of kpiCards) {
    const title = kpi.title?.toLowerCase() || '';
    const value = kpi.rawValue ?? 0;

    if (title.includes('毛利') && !title.includes('率')) {
      kpiData.value.grossProfit = value;
      matchedCount++;
    } else if (title.includes('毛利') && title.includes('率')) {
      kpiData.value.grossProfitMargin = value;
      matchedCount++;
    } else if (title.includes('净利') && !title.includes('率')) {
      kpiData.value.netProfit = value;
      matchedCount++;
    } else if (title.includes('净利') && title.includes('率')) {
      kpiData.value.netProfitMargin = value;
      matchedCount++;
    } else if (title.includes('成本') || title.includes('cost')) {
      kpiData.value.totalCost = value;
      matchedCount++;
    } else if (title.includes('收入') || title.includes('revenue') || title.includes('销售额')) {
      // 如果没有毛利数据，用收入作为近似
      if (kpiData.value.grossProfit === 0) {
        kpiData.value.grossProfit = value;
      }
      matchedCount++;
    } else if (title.includes('利润') || title.includes('profit')) {
      // 通用利润字段
      if (kpiData.value.netProfit === 0) {
        kpiData.value.netProfit = value;
      }
      matchedCount++;
    } else if (title.includes('预算') || title.includes('budget')) {
      kpiData.value.budgetTotal = value;
      matchedCount++;
    } else if (title.includes('应收') || title.includes('receivable')) {
      kpiData.value.totalReceivable = value;
      matchedCount++;
    } else if (title.includes('应付') || title.includes('payable')) {
      kpiData.value.totalPayable = value;
      matchedCount++;
    }
  }

  // If no KPI titles matched, assign first available cards to primary financial KPIs
  if (matchedCount === 0 && kpiCards.length > 0) {
    // no KPI title matches, using positional fallback
    if (kpiCards[0]) kpiData.value.grossProfit = kpiCards[0].rawValue ?? 0;
    if (kpiCards[1]) kpiData.value.netProfit = kpiCards[1].rawValue ?? 0;
    if (kpiCards[2]) kpiData.value.totalCost = kpiCards[2].rawValue ?? 0;
  }
}

// P1-1: Compute KPIs from raw uploaded data when backend returns empty kpiCards
// Handles two layouts:
//   A) Column-oriented: column names contain financial keywords (e.g. "营业收入" column)
//   B) Row-oriented (profit tables): a text column contains row labels like "营业收入"
//      These labels may have encoding issues but partial Chinese text survives
function computeKpiFromRawData(rows: TableRow[]) {
  if (!rows.length) return;
  const columns = Object.keys(rows[0]);

  const keywordMap: Array<{ keywords: string[]; field: keyof typeof kpiData.value; isRate?: boolean }> = [
    { keywords: ['毛利率', '毛利润率'], field: 'grossProfitMargin', isRate: true },
    { keywords: ['毛利'], field: 'grossProfit' },
    { keywords: ['净利率', '净利润率'], field: 'netProfitMargin', isRate: true },
    { keywords: ['净利'], field: 'netProfit' },
    { keywords: ['营业收入', '营业收', '销售收入', '主营收入'], field: 'grossProfit' },
    { keywords: ['营业成本', '营业成', '主营成本', '成本合计'], field: 'totalCost' },
    { keywords: ['应收'], field: 'totalReceivable' },
    { keywords: ['应付'], field: 'totalPayable' },
    { keywords: ['预算'], field: 'budgetTotal' },
  ];

  // Strategy A: Column-oriented — scan column names for keywords
  let matched = false;
  for (const col of columns) {
    for (const mapping of keywordMap) {
      if (mapping.keywords.some(kw => col.includes(kw))) {
        if ((kpiData.value[mapping.field] as number) !== 0) continue;
        if (mapping.isRate) {
          const lastRow = rows[rows.length - 1];
          const val = Number(lastRow[col]);
          if (!isNaN(val)) (kpiData.value[mapping.field] as number) = val > 1 ? val : val * 100;
        } else {
          let sum = 0;
          for (const row of rows) { const val = Number(row[col]); if (!isNaN(val)) sum += val; }
          (kpiData.value[mapping.field] as number) = sum;
        }
        matched = true;
        break;
      }
    }
  }
  // Only skip Strategy B if primary KPIs (grossProfit or netProfit) were populated
  const primaryPopulated = kpiData.value.grossProfit !== 0 || kpiData.value.netProfit !== 0;
  if (matched && primaryPopulated) {
    // KPI fallback Strategy A (column names) applied
    return;
  }

  // Strategy B: Row-oriented (profit tables) — scan ALL text column values for keywords
  // Find text columns: have diverse string values (not just numbers-as-strings)
  const textCols: string[] = [];
  const numericCols: string[] = [];
  for (const col of columns) {
    const sampleVals = rows.slice(0, Math.min(20, rows.length)).map(r => r[col]);
    const nonNull = sampleVals.filter(v => v !== null && v !== undefined && String(v) !== '');
    if (nonNull.length === 0) continue;

    const numericCount = nonNull.filter(v => !isNaN(Number(v))).length;
    if (numericCount >= nonNull.length * 0.7) {
      numericCols.push(col);
    } else {
      // Check it has Chinese-like text (non-ASCII chars, length > 1)
      const hasText = nonNull.some(v => String(v).length > 1 && /[^\x00-\x7F]/.test(String(v)));
      if (hasText) textCols.push(col);
    }
  }

  // Prefer an "annual total actual" column for the value
  // Collect candidates containing '合' + ('实际'|'年'), then rank:
  //   1. Contains '本年实际' (best: annual actual total)
  //   2. Contains '实际' but not '预算' (actual, not budget)
  //   3. Any other match (fallback)
  let totalCol = '';
  const totalCandidates: string[] = [];
  for (const col of numericCols) {
    if (col.includes('合') && (col.includes('实') || col.includes('年'))) {
      totalCandidates.push(col);
    }
  }
  if (totalCandidates.length > 0) {
    // Rank by preference: 本年实际 > 实际 (non-预算) > anything
    totalCol = totalCandidates.find(c => c.includes('本年实际'))
      || totalCandidates.find(c => c.includes('实际') && !c.includes('预算'))
      || totalCandidates.find(c => c.includes('实际'))
      || totalCandidates[0];
  }

  if (textCols.length > 0 && numericCols.length > 0) {
    for (const row of rows) {
      // Check all text columns for keyword matches
      let rowLabel = '';
      for (const tc of textCols) {
        const val = String(row[tc] || '').trim();
        if (val && val.length > 1) { rowLabel = val; break; }
      }
      if (!rowLabel) continue;

      for (const mapping of keywordMap) {
        if (mapping.keywords.some(kw => rowLabel.includes(kw))) {
          if ((kpiData.value[mapping.field] as number) !== 0) continue;

          if (mapping.isRate) {
            // For rates, prefer the total column or last numeric column
            const src = totalCol || numericCols[numericCols.length - 1];
            const val = Number(row[src]);
            if (!isNaN(val) && val !== 0) {
              (kpiData.value[mapping.field] as number) = Math.abs(val) <= 1 ? val * 100 : val;
            }
          } else {
            // For amounts, prefer annual total column; fallback to sum of numeric cols
            if (totalCol) {
              const val = Number(row[totalCol]);
              if (!isNaN(val)) (kpiData.value[mapping.field] as number) = val;
            } else {
              let rowSum = 0;
              for (const nc of numericCols) {
                const val = Number(row[nc]);
                if (!isNaN(val)) rowSum += val;
              }
              (kpiData.value[mapping.field] as number) = rowSum;
            }
          }
          break;
        }
      }
    }
    // KPI fallback Strategy B (row labels) applied
  }
}

// 从动态数据更新图表
function updateChartFromDynamicData(charts: DynamicAnalysisResponse['charts']) {
  if (charts.length === 0) return;

  // 使用第一个图表 - convert to DynamicChartRenderer format
  const chart = charts[0];
  const labels = chart.data?.labels || [];
  const datasets = chart.data?.datasets || [];

  if (chart.type === 'pie') {
    // 转换饼图为 LegacyChartConfig 格式给 DynamicChartRenderer
    const pieData = labels.map((label, idx) => ({
      name: label,
      value: datasets[0]?.data?.[idx] || 0
    }));
    mainDynamicConfig.value = {
      chartType: 'pie',
      title: chart.title,
      xAxisField: 'name',
      yAxisField: 'value',
      data: pieData,
    } as ChartConfig;
    useDynamicRenderer.value = true;
  } else {
    // 转换柱状/折线为 DashboardChartConfig 格式
    mainDynamicConfig.value = {
      chartType: chart.type,
      title: chart.title || '',
      xAxis: { data: labels },
      series: datasets.map(ds => ({
        name: ds.label,
        type: chart.type || 'bar',
        data: ds.data,
      })),
    } as ChartConfig;
    useDynamicRenderer.value = true;
  }

  // Legacy fallback: still update raw echarts if it's initialized
  if (mainChart) {
    let option: echarts.EChartsOption;

    if (chart.type === 'pie') {
      const pieData = labels.map((label, idx) => ({
        name: label,
        value: datasets[0]?.data?.[idx] || 0
      }));
      option = {
        tooltip: { trigger: 'item', confine: true, formatter: '{b}: {c} ({d}%)' },
        legend: { orient: 'vertical', right: '10%', top: 'center' },
        series: [{
          type: 'pie',
          radius: ['40%', '70%'],
          center: ['40%', '50%'],
          data: pieData,
          emphasis: {
            itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0, 0, 0, 0.5)' }
          }
        }]
      };
    } else {
      option = {
        tooltip: {
          trigger: 'axis',
          confine: true,
          axisPointer: { type: chart.type === 'line' ? 'cross' : 'shadow' }
        },
        grid: { left: '3%', right: '4%', bottom: '15%', top: '10%', containLabel: true },
        xAxis: { type: 'category', data: labels },
        yAxis: {
          type: 'value',
          axisLabel: { formatter: formatAxisValue }
        },
        series: datasets.map(ds => ({
          name: ds.label,
          type: chart.type as 'bar' | 'line',
          data: ds.data,
          smooth: chart.type === 'line',
          itemStyle: { color: '#2D8B57' }
        }))
      };
    }

    mainChart.setOption(option, true);
  }
}

// Only reload system finance data when analysis type or date range changes
// Debounced to prevent double-fire when date picker emits start+end separately
let financeWatchTimer: ReturnType<typeof setTimeout> | null = null;
watch([analysisType, dateRange], () => {
  // Restaurant tenants show placeholder — skip reactive reload.
  if (isRestaurantTenant.value) return;
  if (selectedDataSource.value === 'system') {
    if (financeWatchTimer) clearTimeout(financeWatchTimer);
    financeWatchTimer = setTimeout(() => loadFinanceData(), 300);
  }
});

// PR #468 §6 P1-A sister site: el-date-picker value-format="YYYY-MM-DD" emits string,
// 旧实现 .toISOString() 抛 TypeError. 同 SalesAnalysis.vue 修复.
const formatDate = toApiDateString;

async function loadFinanceData() {
  if (!factoryId.value || !dateRange.value) return;

  // Cancel previous in-flight request
  if (financeAbortController) financeAbortController.abort();
  financeAbortController = new AbortController();
  const signal = financeAbortController.signal;

  loading.value = true;
  loadError.value = '';
  // Reset previous chart/warnings to avoid stale data when switching analysis types
  chartConfig.value = null;
  warnings.value = [];
  mainDynamicConfig.value = null;
  useDynamicRenderer.value = false;
  try {
    const startDate = formatDate(dateRange.value[0]);
    const endDate = formatDate(dateRange.value[1]);

    const response = await get(
      `/${factoryId.value}/smart-bi/analysis/finance`,
      {
        params: {
          startDate,
          endDate,
          analysisType: analysisType.value
        },
        signal,
        _silent: true,
      } as never
    );

    if (response.success && response.data) {
      // Handle double-wrapped response: interceptor wraps {code,data:{...}} into {success,data:{code,data:{...}}}
      const raw = response.data as TableRow;
      const data = (raw.data && typeof raw.data === 'object' && !Array.isArray(raw.data) && raw.code)
        ? raw.data as TableRow
        : raw;

      // 提取 metrics 数据到 kpiData
      if (data.metrics) {
        if (Array.isArray(data.metrics)) {
          // Backend returns List<MetricResult> — convert metricCode→value map
          const metricCodeMap: Record<string, string> = {
            'AR_BALANCE': 'totalReceivable',
            'COLLECTION_RATE': 'collectionRate',
            'AGING_30_RATIO': 'receivableAge30Ratio',
            'AGING_60_RATIO': 'receivableAge60Ratio',
            'AGING_90_RATIO': 'receivableAge90Ratio',
            'AP_BALANCE': 'totalPayable',
            'BUDGET_TOTAL': 'budgetTotal',
            'BUDGET_USED': 'budgetUsed',
            'BUDGET_REMAINING': 'budgetRemaining',
            'BUDGET_USAGE_RATE': 'budgetUsageRate',
            // Backend actual codes (getBudgetMetrics returns these):
            'BUDGET_EXECUTION': 'budgetUsageRate',
            'BUDGET_VARIANCE': '_budgetVariance',
            'BUDGET_VARIANCE_RATE': '_budgetVarianceRate',
            'GROSS_PROFIT': 'grossProfit',
            'GROSS_MARGIN': 'grossProfitMargin',
            'NET_PROFIT': 'netProfit',
            'NET_MARGIN': 'netProfitMargin',
            'TOTAL_COST': 'totalCost',
          };
          const flat: TableRow = {};
          for (const m of data.metrics as Array<TableRow>) {
            const code = String(m.metricCode || '');
            const mappedKey = metricCodeMap[code];
            if (mappedKey) flat[mappedKey] = m.value;
          }
          // Derive budgetTotal and budgetUsed from BUDGET_REMAINING + BUDGET_EXECUTION
          // remaining = budget - actual, execution = actual/budget * 100
          // → actual = remaining * execution / (100 - execution), budget = remaining + actual
          const remaining = Number(flat.budgetRemaining || 0);
          const execution = Number(flat.budgetUsageRate || 0);
          if (remaining !== 0 && execution > 0 && execution < 100 && !flat.budgetTotal) {
            const actual = remaining * execution / (100 - execution);
            flat.budgetTotal = remaining + actual;
            flat.budgetUsed = actual;
          } else if (remaining !== 0 && execution >= 100 && !flat.budgetTotal) {
            // Over-budget: variance = actual - budget (negative remaining means overspent)
            const variance = Number(flat._budgetVariance || 0);
            if (variance !== 0) {
              // actual = budget + variance, remaining = budget - actual = -variance
              const budget = -remaining + variance !== 0 ? (-remaining * 100) / execution : 0;
              flat.budgetTotal = budget;
              flat.budgetUsed = budget + variance;
            }
          }
          updateKpiDataFromMetrics(flat);
        } else {
          updateKpiDataFromMetrics(data.metrics as TableRow);
        }
      }

      // Extract aging bucket AMOUNTS from agingChart.data into KPI cards
      if (data.agingChart && typeof data.agingChart === 'object') {
        const agingData = (data.agingChart as TableRow).data;
        if (Array.isArray(agingData)) {
          const isPayable = analysisType.value === 'payable';
          for (const bucket of agingData as Array<TableRow>) {
            const label = String(bucket.agingBucket || '');
            const amount = Number(bucket.amount || 0);
            if (isPayable) {
              if (label.includes('0-30')) kpiData.value.payableAge30 = amount;
              else if (label.includes('31-60') || label.includes('30-60')) kpiData.value.payableAge60 = amount;
              else if (label.includes('90')) kpiData.value.payableAge90Plus = amount;
            } else {
              if (label.includes('0-30')) kpiData.value.receivableAge30 = amount;
              else if (label.includes('31-60') || label.includes('30-60')) kpiData.value.receivableAge60 = amount;
              else if (label.includes('90')) kpiData.value.receivableAge90Plus = amount;
            }
          }
        }
      }

      // 提取 overview 数据 (当没有 analysisType 时)
      if (data.overview) {
        const overview = data.overview as TableRow;
        if (overview.kpiCards) {
          updateKpiDataFromKpiCards(overview.kpiCards as Array<TableRow>);
        }
        // Extract chart from overview.charts map — pick chart matching current analysisType
        if (overview.charts && typeof overview.charts === 'object') {
          const charts = overview.charts as TableRow;
          const chartKeys = Object.keys(charts);
          // Map analysis type to chart keyword priorities
          const keywordMap: Record<string, string[]> = {
            profit: ['利润', '趋势'],
            cost: ['成本', '结构'],
            receivable: ['应收', '账龄'],
            payable: ['应付', '账龄'],
            budget: ['预算', '执行', '达成'],
          };
          const keywords = keywordMap[analysisType.value] || ['趋势', '利润'];
          const matchKey = chartKeys.find(k => keywords.some(kw => k.includes(kw)));
          // No fallback to chartKeys[0] — prevents budget tab showing profit chart
          if (matchKey && charts[matchKey]) {
            chartConfig.value = charts[matchKey] as ChartConfig | DynamicChartConfig;
            updateChart();
          }
        }
        // Extract AI insights as warnings — filtered by current analysisType
        if (overview.aiInsights && Array.isArray(overview.aiInsights)) {
          const typeKeywords: Record<string, string[]> = {
            profit: ['利润', '毛利', '净利', '营收', '收入', '盈利', 'profit', 'revenue', 'margin'],
            cost: ['成本', '费用', '支出', '开销', 'cost', 'expense'],
            receivable: ['应收', '回款', '账龄', '逾期', 'receivable', 'overdue'],
            payable: ['应付', '付款', '账期', 'payable', 'payment'],
            budget: ['预算', '达成', '偏差', 'budget', 'variance'],
          };
          const keywords = typeKeywords[analysisType.value] || [];
          const allInsights = (overview.aiInsights as Array<TableRow>).map(insight => ({
            level: String(insight.level || 'info').toLowerCase() === 'red' ? 'danger' as const : 'warning' as const,
            title: String(insight.category || ''),
            description: String(insight.message || ''),
            amount: 0
          }));
          // Filter: only show warnings relevant to current tab (by keyword match in title or description)
          const filtered = keywords.length > 0
            ? allInsights.filter(w => keywords.some(kw => w.title.includes(kw) || w.description.includes(kw)))
            : allInsights;
          // Only show warnings relevant to current tab — no fallback to unrelated warnings
          warnings.value = filtered;
        }
      }

      // Also try profitMetrics for KPI data
      if (data.profitMetrics && Array.isArray(data.profitMetrics)) {
        updateKpiDataFromKpiCards(data.profitMetrics as Array<TableRow>);
      }

      // 提取图表配置 (按analysisType优先匹配, 避免budget显示profit趋势)
      if (!chartConfig.value) {
        // Type-specific chart priority map
        const chartPriorityMap: Record<string, string[]> = {
          profit: ['trendChart', 'waterfall', 'comparison'],
          cost: ['costStructure', 'structureChart', 'trendChart'],
          receivable: ['receivableAging', 'agingChart', 'trendChart'],
          payable: ['agingChart', 'structureChart', 'trendChart'],
          budget: ['waterfall', 'comparison', 'structureChart', 'trendChart'],
        };
        const priorities = chartPriorityMap[analysisType.value] || Object.keys(chartPriorityMap.profit);
        // Only try type-specific keys — no generic fallback to avoid cross-type chart leaking
        for (const key of priorities) {
          if (data[key]) {
            chartConfig.value = data[key] as ChartConfig | DynamicChartConfig;
            updateChart();
            break;
          }
        }
      }

      // 提取预警列表 (if not already set from overview.aiInsights)
      if (warnings.value.length === 0) {
        if (data.warnings) {
          warnings.value = data.warnings as WarningItem[];
        } else if (data.overdueRanking) {
          const ranking = data.overdueRanking as Array<TableRow>;
          warnings.value = ranking.slice(0, 5).map((item, index) => ({
            level: index < 2 ? 'danger' : 'warning',
            title: String(item.customerName || '未知客户'),
            description: `逾期 ${item.overdueDays || 0} 天`,
            amount: Number(item.overdueAmount || 0)
          })) as WarningItem[];
        }
      }

      // Check if system data is effectively empty (no charts, all KPIs zero)
      const hasChart = !!chartConfig.value;
      const hasNonZeroKpi = Object.values(kpiData.value).some(v => typeof v === 'number' && v !== 0);
      noSystemData.value = !hasChart && !hasNonZeroKpi;

      // Auto-switch to uploaded data if system data is empty and uploads exist
      if (noSystemData.value && dataSources.value.length > 0) {
        const firstUpload = dataSources.value[0];
        selectedDataSource.value = String(firstUpload.id);
        await loadDynamicData(firstUpload.id);
        return;
      }
    } else {
      loadError.value = response.message || '加载财务数据失败';
      resetData();
    }
  } catch (error) {
    // Ignore aborted requests (user changed filters quickly)
    if (error instanceof DOMException && error.name === 'AbortError') return;
    if (signal?.aborted) return;
    console.warn('加载系统财务数据失败:', error);
    resetData();
    // 如果有上传数据，自动切换而非显示错误横幅
    if (dataSources.value.length > 0) {
      const firstUpload = dataSources.value[0];
      selectedDataSource.value = String(firstUpload.id);
      await loadDynamicData(firstUpload.id);
      return;
    }
    // 无上传数据时才显示友好提示（info 而非 error）
    loadError.value = '系统财务数据暂不可用，请上传 Excel 数据进行分析';
  } finally {
    if (!signal?.aborted) {
      loading.value = false;
      generateSmartWarnings();
    }
  }
}

function updateKpiDataFromMetrics(metrics: TableRow) {
  // 利润相关
  if (metrics.grossProfit !== undefined) kpiData.value.grossProfit = Number(metrics.grossProfit);
  if (metrics.netProfit !== undefined) kpiData.value.netProfit = Number(metrics.netProfit);
  // Margin: backend returns 0 when revenue=0 (division undefined). Treat as null when
  // margin=0 but profit is non-zero — indicates incalculable, not a real 0% margin.
  if (metrics.grossProfitMargin !== undefined) {
    const v = Number(metrics.grossProfitMargin);
    kpiData.value.grossProfitMargin = (v === 0 && kpiData.value.grossProfit !== 0) ? null : v;
  }
  if (metrics.netProfitMargin !== undefined) {
    const v = Number(metrics.netProfitMargin);
    kpiData.value.netProfitMargin = (v === 0 && kpiData.value.netProfit !== 0) ? null : v;
  }

  // 成本相关
  if (metrics.totalCost !== undefined) kpiData.value.totalCost = Number(metrics.totalCost);
  if (metrics.costGrowth !== undefined) kpiData.value.costGrowth = Number(metrics.costGrowth);
  if (metrics.materialCost !== undefined) kpiData.value.materialCost = Number(metrics.materialCost);
  if (metrics.laborCost !== undefined) kpiData.value.laborCost = Number(metrics.laborCost);
  if (metrics.overheadCost !== undefined) kpiData.value.overheadCost = Number(metrics.overheadCost);

  // 应收相关
  if (metrics.totalReceivable !== undefined) kpiData.value.totalReceivable = Number(metrics.totalReceivable);
  if (metrics.receivableAge30 !== undefined) kpiData.value.receivableAge30 = Number(metrics.receivableAge30);
  if (metrics.receivableAge60 !== undefined) kpiData.value.receivableAge60 = Number(metrics.receivableAge60);
  if (metrics.receivableAge90Plus !== undefined) kpiData.value.receivableAge90Plus = Number(metrics.receivableAge90Plus);
  // 支持不同的字段命名
  if (metrics.within30Days !== undefined) kpiData.value.receivableAge30 = Number(metrics.within30Days);
  if (metrics.days30To60 !== undefined) kpiData.value.receivableAge60 = Number(metrics.days30To60);
  if (metrics.over90Days !== undefined) kpiData.value.receivableAge90Plus = Number(metrics.over90Days);

  // 应付相关
  if (metrics.totalPayable !== undefined) kpiData.value.totalPayable = Number(metrics.totalPayable);
  if (metrics.payableAge30 !== undefined) kpiData.value.payableAge30 = Number(metrics.payableAge30);
  if (metrics.payableAge60 !== undefined) kpiData.value.payableAge60 = Number(metrics.payableAge60);
  if (metrics.payableAge90Plus !== undefined) kpiData.value.payableAge90Plus = Number(metrics.payableAge90Plus);

  // 预算相关
  if (metrics.budgetTotal !== undefined) kpiData.value.budgetTotal = Number(metrics.budgetTotal);
  if (metrics.budgetUsed !== undefined) kpiData.value.budgetUsed = Number(metrics.budgetUsed);
  if (metrics.budgetRemaining !== undefined) kpiData.value.budgetRemaining = Number(metrics.budgetRemaining);
  if (metrics.budgetUsageRate !== undefined) kpiData.value.budgetUsageRate = Number(metrics.budgetUsageRate);
  // 支持不同的字段命名
  if (metrics.totalBudget !== undefined) kpiData.value.budgetTotal = Number(metrics.totalBudget);
  if (metrics.usedBudget !== undefined) kpiData.value.budgetUsed = Number(metrics.usedBudget);
  if (metrics.remainingBudget !== undefined) kpiData.value.budgetRemaining = Number(metrics.remainingBudget);
  if (metrics.usageRate !== undefined) kpiData.value.budgetUsageRate = Number(metrics.usageRate);
}

function updateKpiDataFromKpiCards(kpiCards: Array<TableRow>) {
  for (const card of kpiCards) {
    const label = String(card.title || card.metricName || card.label || '');
    const rawVal = card.rawValue ?? card.value ?? 0;
    const value = typeof rawVal === 'string' ? Number(String(rawVal).replace(/[,%]/g, '')) : Number(rawVal);

    if (label.includes('毛利') && label.includes('率')) {
      kpiData.value.grossProfitMargin = (value === 0 && kpiData.value.grossProfit !== 0) ? null : value;
    } else if (label.includes('毛利')) {
      kpiData.value.grossProfit = value;
    } else if (label.includes('净利') && label.includes('率')) {
      kpiData.value.netProfitMargin = (value === 0 && kpiData.value.netProfit !== 0) ? null : value;
    } else if (label.includes('净利')) {
      kpiData.value.netProfit = value;
    } else if (label.includes('总成本')) {
      kpiData.value.totalCost = value;
    } else if (label.includes('应收')) {
      kpiData.value.totalReceivable = value;
    } else if (label.includes('应付')) {
      kpiData.value.totalPayable = value;
    } else if (label.includes('预算') && label.includes('使用')) {
      kpiData.value.budgetUsageRate = value;
    } else if (label.includes('预算')) {
      kpiData.value.budgetTotal = value;
    }
  }
}

// 基于 KPI 数据自动生成预警（补充后端 AI 未覆盖的场景）
function generateSmartWarnings() {
  const autoWarnings: WarningItem[] = [];
  const kpi = kpiData.value;

  // Apr 24 2026 UX P0-1 guard: 如果营收为 0, 说明没数据而不是"负利润" — 不发预警.
  // 以前 profit=0 + revenue=0 会触发 "毛利率 0.0% 低于行业基准 25-35%" 假警报,
  // 误导用户以为生意糟糕,实际是 Silver 没 cost 数据导致 profit 字段全 0.
  const hasRevenueForProfit = (kpi.grossProfit != null && kpi.grossProfit !== 0)
    || (kpi.netProfit != null && kpi.netProfit !== 0)
    || (kpi.grossProfitMargin != null && kpi.grossProfitMargin !== 0);

  if (analysisType.value === 'profit' && hasRevenueForProfit) {
    if (kpi.grossProfit < 0) {
      autoWarnings.push({
        level: 'danger',
        title: '毛利润为负',
        description: `毛利润 ${formatMoney(kpi.grossProfit)}，建议检查营业收入和成本结构`,
      });
    }
    if (kpi.netProfit < 0) {
      autoWarnings.push({
        level: 'danger',
        title: '净利润为负',
        description: `净利润 ${formatMoney(kpi.netProfit)}，企业处于亏损状态，需关注费用控制`,
      });
    }
    if (kpi.grossProfitMargin != null && kpi.grossProfitMargin < 25 && kpi.grossProfitMargin > 0) {
      autoWarnings.push({
        level: 'warning',
        title: '毛利率低于行业基准',
        description: `毛利率 ${kpi.grossProfitMargin.toFixed(1)}%，食品加工行业基准为 25-35%`,
      });
    }
  }

  if (analysisType.value === 'receivable' && kpi.receivableAge90Plus != null && kpi.receivableAge90Plus > 0) {
    autoWarnings.push({
      level: 'danger',
      title: '存在高风险逾期应收',
      description: `90天以上应收账款 ${formatMoney(kpi.receivableAge90Plus)}，建议加强催收`,
      amount: kpi.receivableAge90Plus,
    });
  }

  if (analysisType.value === 'budget') {
    if (kpi.budgetUsageRate > 90) {
      autoWarnings.push({
        level: 'danger',
        title: '预算超支风险',
        description: `预算使用率已达 ${kpi.budgetUsageRate.toFixed(1)}%，剩余 ${formatMoney(kpi.budgetRemaining)}，建议严控支出`,
      });
    } else if (kpi.budgetUsageRate > 75) {
      autoWarnings.push({
        level: 'warning',
        title: '预算使用率偏高',
        description: `预算使用率 ${kpi.budgetUsageRate.toFixed(1)}%，建议关注后续支出节奏`,
      });
    }
  }

  if (analysisType.value === 'cost' && kpi.costGrowth > 20) {
    autoWarnings.push({
      level: 'warning',
      title: '成本增速过快',
      description: `成本环比增长 ${kpi.costGrowth.toFixed(1)}%，超过20%预警线`,
    });
  }

  // Append auto-warnings, dedup by title to avoid duplicating backend AI warnings
  if (autoWarnings.length > 0) {
    const existingTitles = new Set(warnings.value.map(w => w.title));
    const unique = autoWarnings.filter(w => !existingTitles.has(w.title));
    if (unique.length > 0) {
      warnings.value = [...warnings.value, ...unique];
    }
  }
}

function resetData() {
  kpiData.value = {
    grossProfit: 0,
    grossProfitMargin: null,
    netProfit: 0,
    netProfitMargin: null,
    totalCost: 0,
    costGrowth: 0,
    materialCost: 0,
    laborCost: 0,
    overheadCost: 0,
    totalReceivable: 0,
    receivableAge30: 0,
    receivableAge60: 0,
    receivableAge90Plus: 0,
    totalPayable: 0,
    payableAge30: 0,
    payableAge60: 0,
    payableAge90Plus: 0,
    budgetTotal: 0,
    budgetUsed: 0,
    budgetRemaining: 0,
    budgetUsageRate: 0
  };
  warnings.value = [];
  chartConfig.value = null;
}

function initChart() {
  const chartDom = document.getElementById('finance-main-chart');
  if (!chartDom) return;

  mainChart = echarts.init(chartDom, 'cretas');
}

function updateChart() {
  // Skip rendering if chart data is all zeros (e.g. empty budget/payable)
  if (chartConfig.value && 'data' in chartConfig.value) {
    const data = (chartConfig.value as TableRow).data;
    if (Array.isArray(data) && data.length > 0) {
      const allZero = data.every((item: TableRow) =>
        Object.values(item).every(v => typeof v === 'string' || v === 0 || v === null)
      );
      if (allZero) {
        chartConfig.value = null;
        mainDynamicConfig.value = null;
        useDynamicRenderer.value = false;
        return;
      }
    }
  }

  // Phase 6: Try DynamicChartRenderer first
  const dynamicCfg = convertToDynamicConfig(chartConfig.value);
  if (dynamicCfg) {
    mainDynamicConfig.value = dynamicCfg;
    useDynamicRenderer.value = true;
    return;
  }

  // Fallback to raw echarts.init
  useDynamicRenderer.value = false;
  if (!mainChart || !chartConfig.value) return;

  const config = chartConfig.value;
  const option = buildEChartsOption(config);

  if (option) {
    mainChart.setOption(option, true);
  }
}

function buildEChartsOption(config: ChartConfig_Local | DynamicChartConfig): echarts.EChartsOption | null {
  // 检查是否是 DynamicChartConfig
  if ('series' in config && Array.isArray(config.series)) {
    return buildFromDynamicConfig(config as DynamicChartConfig);
  }

  // ChartConfig_Local 格式
  const localConfig = config as ChartConfig_Local;
  if (!localConfig.data || localConfig.data.length === 0) {
    return getEmptyChartOption();
  }

  const chartType = localConfig.chartType?.toLowerCase() || 'bar';

  if (chartType === 'pie') {
    return buildPieChart(localConfig);
  } else {
    return buildAxisChart(localConfig);
  }
}

function buildFromDynamicConfig(config: DynamicChartConfig): echarts.EChartsOption {
  const option: echarts.EChartsOption = {
    tooltip: {
      trigger: (config.tooltip?.trigger as 'none' | 'item' | 'axis' | undefined) || 'axis',
      confine: true,
      axisPointer: config.tooltip?.axisPointer || { type: 'shadow' },
      formatter: (params: unknown) => {
        const items = Array.isArray(params) ? params : [params];
        if (items.length === 0) return '';
        const title = (items[0] as TableRow).axisValueLabel || (items[0] as TableRow).name || '';
        const lines = items.map((p: TableRow) => {
          const val = Number(p.value ?? 0);
          const name = String(p.seriesName || '');
          const color = String((p.color as string) || '#333');
          const isRate = name.includes('率') || name.includes('rate') || name.includes('Rate');
          const formatted = isRate ? val.toFixed(1) + '%' : formatAxisValue(val);
          return `<span style="display:inline-block;width:10px;height:10px;border-radius:50%;background:${color};margin-right:6px;"></span>${name}: <b>${formatted}</b>`;
        });
        return `<div style="font-weight:600;margin-bottom:4px">${title}</div>${lines.join('<br/>')}`;
      }
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '15%',
      top: '10%',
      containLabel: true
    }
  };

  // 设置标题
  if (config.title) {
    option.title = { text: config.title, subtext: config.subTitle };
  }

  // 设置图例
  if (config.legend) {
    option.legend = {
      show: config.legend.show !== false,
      data: config.legend.data,
      bottom: config.legend.position === 'bottom' ? 0 : undefined,
      top: config.legend.position === 'top' ? 0 : undefined,
      orient: config.legend.orient as 'horizontal' | 'vertical' || 'horizontal'
    };
  }

  // 设置 X 轴
  if (config.xAxis) {
    const xData = config.xAxis.data || [];
    const needsRotation = xData.length > 6 || xData.some((s: string) => String(s).length > 8);
    option.xAxis = {
      type: config.xAxis.type as 'category' | 'value' || 'category',
      name: config.xAxis.name,
      data: xData,
      axisLabel: {
        rotate: needsRotation ? 30 : 0,
        fontSize: 11,
        interval: 0
      }
    };
    // Add dataZoom for dense data
    if (xData.length > 12) {
      option.dataZoom = [
        { type: 'inside', start: 0, end: Math.min(100, Math.round(12 / xData.length * 100)) },
        { type: 'slider', start: 0, end: Math.min(100, Math.round(12 / xData.length * 100)), bottom: 5 }
      ];
      (option.grid as TableRow).bottom = '20%';
    }
  }

  // 设置 Y 轴
  if (config.yAxis && config.yAxis.length > 0) {
    option.yAxis = config.yAxis.map(axis => ({
      type: axis.type as 'value' | 'category' || 'value',
      name: axis.name,
      position: axis.position as 'left' | 'right' || undefined,
      min: axis.min,
      max: axis.max,
      axisLabel: {
        ...(axis.axisLabel || {}),
        formatter: formatAxisValue
      }
    })) as echarts.EChartsOption['yAxis'];
  }

  // 设置系列
  if (config.series && config.series.length > 0) {
    option.series = config.series.map(s => {
      const seriesItem: echarts.SeriesOption = {
        name: s.name,
        type: s.type as 'line' | 'bar' | 'pie' || 'bar',
        data: s.data,
        yAxisIndex: s.yAxisIndex || 0,
        stack: s.stack,
        smooth: s.smooth,
        itemStyle: s.itemStyle,
        label: s.label ? ({
          ...s.label,
          // Prevent label overlap on dense bar charts: only show label if bar is wide enough
          formatter: (params: { value: number | string }) => formatAxisValue(Number(params.value)),
          fontSize: 10,
        } as never) : undefined
      };

      if (s.areaStyle && s.type === 'line') {
        (seriesItem as echarts.LineSeriesOption).areaStyle = {};
      }

      return seriesItem;
    }) as echarts.SeriesOption[];
  }

  return option;
}

function buildPieChart(config: ChartConfig_Local): echarts.EChartsOption {
  const xField = config.xAxisField || (config as TableRow).xaxisField as string || 'name';
  const yField = config.yAxisField || (config as TableRow).yaxisField as string || 'value';

  const pieData = config.data?.map(item => ({
    name: String(item[xField] || ''),
    value: Number(item[yField] || 0)
  })) || [];

  return {
    tooltip: {
      trigger: 'item',
      confine: true,
      formatter: '{b}: {c} ({d}%)'
    },
    legend: {
      orient: 'vertical',
      right: '10%',
      top: 'center'
    },
    series: [
      {
        type: 'pie',
        radius: ['40%', '70%'],
        center: ['40%', '50%'],
        data: pieData,
        emphasis: {
          itemStyle: {
            shadowBlur: 10,
            shadowOffsetX: 0,
            shadowColor: 'rgba(0, 0, 0, 0.5)'
          }
        }
      }
    ]
  };
}

function buildAxisChart(config: ChartConfig_Local): echarts.EChartsOption {
  const xField = config.xAxisField || (config as TableRow).xaxisField as string || 'name';
  const yField = config.yAxisField || (config as TableRow).yaxisField as string || 'value';
  const chartType = config.chartType?.toLowerCase() || 'bar';

  const xData = config.data?.map(item => String(item[xField] || '')) || [];
  const yData = config.data?.map(item => Number(item[yField] || 0)) || [];

  // Truncate long X-axis labels and rotate if many items
  const needsRotation = xData.length > 6 || xData.some(s => s.length > 8);
  const truncatedXData = xData.map(s => s.length > 12 ? s.slice(0, 12) + '…' : s);

  return {
    tooltip: {
      trigger: 'axis',
      confine: true,
      axisPointer: { type: chartType === 'line' ? 'cross' : 'shadow' },
      formatter: (params: unknown) => {
        const items = Array.isArray(params) ? params : [params];
        const lines = items.map((p: TableRow) => {
          const val = Number(p.value || 0);
          const formatted = formatAxisValue(val);
          return `${p.seriesName || ''}: ${formatted}`;
        });
        // Use original label (not truncated) in tooltip
        const idx = (items[0] as TableRow)?.dataIndex as number;
        return `<strong>${xData[idx] || ''}</strong><br/>${lines.join('<br/>')}`;
      }
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: needsRotation ? '20%' : '15%',
      top: '10%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: truncatedXData,
      axisLabel: {
        rotate: needsRotation ? 30 : 0,
        fontSize: 11,
        interval: 0
      }
    },
    yAxis: {
      type: 'value',
      axisLabel: {
        formatter: formatAxisValue
      }
    },
    series: [
      {
        type: chartType as 'bar' | 'line',
        data: yData,
        smooth: chartType === 'line',
        itemStyle: {
          color: '#2D8B57'
        }
      }
    ]
  };
}

function getEmptyChartOption(): echarts.EChartsOption {
  return {
    title: {
      text: '暂无数据',
      left: 'center',
      top: 'center',
      textStyle: {
        color: '#909399',
        fontSize: 14
      }
    }
  };
}

// ResizeObserver-based chart resize (also handles sidebar toggle)
useChartResize(financePageRef, () => {
  mainChart?.resize();
});

function formatMoney(value: number | null | undefined): string {
  if (value == null) return '--';
  return formatNumber(value, 1);
}

function formatPercent(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return 'N/A';
  return value.toFixed(1) + '%';
}

function getWarningTagType(level: string): 'danger' | 'warning' | 'info' {
  return level as 'danger' | 'warning' | 'info';
}

function handleRefresh() {
  if (selectedDataSource.value && selectedDataSource.value !== 'system') {
    loadDynamicData(Number(selectedDataSource.value));
  } else {
    loadFinanceData();
  }
}

// ==================== 数据预览功能 ====================

/**
 * 打开数据预览对话框
 */
async function openDataPreview() {
  if (selectedDataSource.value === 'system') {
    ElMessage.warning('请先选择一个上传的数据源');
    return;
  }

  showDataPreview.value = true;
  previewPage.value = 1;
  await loadPreviewData();
}

/**
 * 加载预览数据（分页）
 */
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

/**
 * 预览分页变化
 */
function handlePreviewPageChange(page: number) {
  previewPage.value = page;
  loadPreviewData();
}

/**
 * 关闭数据预览
 */
function closeDataPreview() {
  showDataPreview.value = false;
}

onUnmounted(() => {
  if (financeAbortController) financeAbortController.abort();
  if (financeWatchTimer) clearTimeout(financeWatchTimer);
  mainChart?.dispose();
});
</script>

<template>
  <!-- Phase IIa (2026-05-14): real restaurant analytics replace the placeholder.
       Rollback path (spec §5.7): VITE_PHASE_IIA_ENABLED=false falls back to the
       placeholder without a code revert. RestaurantPhaseIIPlaceholder.vue stays
       in repo so this flag flip works. -->
  <RestaurantPhaseIIPlaceholder
    v-if="isRestaurantTenant && !phaseIIaEnabled"
    pageName="财务数据分析"
  />
  <!-- Phase IIb (2026-05-15): restaurant wrapper hosts BOTH 营收概览 (IIa)
       and 成本运营 (IIb) under a shared tab switcher (when phaseIIbEnabled).
       Pre-IIb: only 营收概览 mounted directly. Spec §5.2 7-step restructure. -->
  <div v-else-if="isRestaurantTenant && phaseIIaEnabled" class="restaurant-finance-wrapper">
    <!-- Tab switcher (only show when more than one tab — IIb-disabled fallback hides it) -->
    <el-card v-if="analysisTypes.length > 1" class="type-switch-card">
      <div class="type-switch">
        <div
          v-for="item in analysisTypes"
          :key="item.type"
          class="type-item"
          :class="{ active: analysisType === item.type }"
          @click="analysisType = item.type"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </div>
      </div>
    </el-card>
    <!-- 营收概览 (Phase IIa) — listens for 'goto-kitchen-cost' cross-link emit -->
    <RestaurantFinanceContent
      v-if="analysisType === 'profit'"
      @goto-kitchen-cost="analysisType = 'kitchen-cost'"
    />
    <!-- 成本运营 (Phase IIb) — listens for 'goto-overview' to return to 营收概览 -->
    <RestaurantKitchenCostContent
      v-else-if="phaseIIbEnabled && analysisType === 'kitchen-cost'"
      :factory-id="factoryId || ''"
      :date-range="restaurantDefaultDateRange"
      @goto-overview="analysisType = 'profit'"
    />
  </div>
  <div v-else ref="financePageRef" class="finance-analysis-page">
    <div class="page-header">
      <div class="header-left">
        <h1>财务分析</h1>
      </div>
      <div class="header-right">
        <el-button type="primary" :icon="Refresh" @click="handleRefresh" :loading="loading">刷新</el-button>
      </div>
    </div>

    <!-- WS4 #9: Gold 预览页已删除 (探索期临时入口), CTA banner 随之移除。 -->

    <template v-if="canViewPrice">
    <!-- 筛选栏 -->
    <el-card class="filter-card">
      <div class="filter-bar">
        <!-- 数据源选择器 -->
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
              :label="`${ds.fileName}${ds.sheetName ? ' - ' + ds.sheetName : ''}`"
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

        <!-- 查看原始数据按钮 -->
        <div class="filter-item" v-if="selectedDataSource !== 'system'">
          <el-button :icon="View" @click="openDataPreview">
            查看原始数据
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- 分析类型切换 (餐饮租户只有利润1个,直接隐藏切换器) -->
    <el-card v-if="analysisTypes.length > 1" class="type-switch-card">
      <div class="type-switch">
        <div
          v-for="item in analysisTypes"
          :key="item.type"
          class="type-item"
          :class="{ active: analysisType === item.type }"
          @click="analysisType = item.type"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </div>
      </div>
    </el-card>

    <!-- Apr 24 UX: 餐饮租户 tabs 隐藏说明 -->
    <el-alert
      v-else-if="isRestaurantTenant"
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 16px;"
    >
      <template #title>
        餐饮门店财务分析聚焦 <strong>营收与利润</strong>。成本 / 应收 / 应付 / 预算 分析将在 v2 (accounting_import 功能上线后) 加入。
      </template>
    </el-alert>

    <!-- 加载错误提示 (友好模式：系统不可用时显示 info；真实错误显示 error) -->
    <el-alert
      v-if="loadError"
      :type="loadError.includes('暂不可用') ? 'info' : 'error'"
      :title="loadError"
      show-icon
      closable
      style="margin-bottom: 16px"
      @close="loadError = ''"
    >
      <template v-if="!loadError.includes('暂不可用')">
        <el-button size="small" type="primary" @click="handleRefresh" style="margin-top: 8px">重试</el-button>
      </template>
      <template v-else-if="dataSources.length === 0">
        <el-button size="small" type="primary" @click="$router.push('/smart-bi/analysis')" style="margin-top: 8px">上传数据</el-button>
      </template>
    </el-alert>

    <!-- 系统数据为空提示 -->
    <el-alert
      v-if="noSystemData && selectedDataSource === 'system' && !loading"
      type="info"
      title="暂无系统财务数据"
      description="系统财务数据表为空，请联系管理员导入数据，或切换到已上传的 Excel 数据源进行分析。"
      show-icon
      :closable="false"
      style="margin-bottom: 16px"
    />

    <!-- 数据质量警告 -->
    <el-alert
      v-if="dataQualityWarning && !loading"
      type="warning"
      title="数据质量提示"
      :description="dataQualityWarning"
      show-icon
      :closable="true"
      style="margin-bottom: 16px"
    />

    <!-- 餐饮租户: Gold-backed 营收/订单/客单价/门店 代替 毛利润/净利润 (P0-1 fix) -->
    <el-row v-if="isRestaurantTenant && analysisType === 'profit'" :gutter="16" class="kpi-section">
      <el-col :span="24" style="margin-bottom: 8px; display: flex; align-items: center; gap: 8px;">
        <el-tag size="small" type="success">Gold · finance_summary</el-tag>
        <el-tag v-if="goldFinFallbackLabel" size="small" type="warning" effect="plain">
          所选区间无数据,已显示 {{ goldFinFallbackLabel }}
        </el-tag>
        <span style="color: #909399; font-size: 12px;">
          餐饮门店 POS 汇总数据 (Silver 暂未覆盖 cost/profit 字段, 预计 v2 加入)
        </span>
      </el-col>
      <el-col v-if="canViewPrice" :xs="24" :sm="12" :md="6">
        <CapabilityGate card-id="finance_revenue_mgmt" :requires="['date', 'gross_amount']">
        <el-card class="kpi-card kpi-accent-0">
          <div class="kpi-label">总营收</div>
          <div class="kpi-value">¥{{ goldFinSummary ? goldFinSummary.totalRevenue.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '--' }}</div>
          <div class="kpi-sub">覆盖 {{ goldFinSummary?.dayCount ?? 0 }} 天</div>
        </el-card>
        </CapabilityGate>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card class="kpi-card kpi-accent-1">
          <div class="kpi-label">POS 订单数</div>
          <div class="kpi-value">{{ goldFinSummary ? goldFinSummary.billCount.toLocaleString('zh-CN') : '--' }}</div>
          <div class="kpi-sub">{{ goldFinSummary?.storeCount ?? 0 }} 家门店</div>
        </el-card>
      </el-col>
      <el-col v-if="canViewPrice" :xs="24" :sm="12" :md="6">
        <el-card class="kpi-card kpi-accent-2">
          <div class="kpi-label">客单价</div>
          <div class="kpi-value">¥{{ goldFinSummary?.avgBillValue != null ? goldFinSummary.avgBillValue.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '--' }}</div>
          <div class="kpi-sub">营收 / 账单数</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card class="kpi-card kpi-accent-3">
          <div class="kpi-label">门店数</div>
          <div class="kpi-value">{{ goldFinSummary?.storeCount ?? '--' }}</div>
          <div class="kpi-sub">有营收的门店</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 财务 KPI (manufacturing tenants 原路径) -->
    <el-row v-else-if="canViewPrice" :gutter="16" class="kpi-section" v-loading="loading">
      <!-- 利润分析 KPI -->
      <template v-if="analysisType === 'profit'">
        <el-col :xs="24" :sm="12" :md="6">
          <CapabilityGate card-id="finance_pnl" :requires="['date', 'gross_amount', 'discount_amount', 'net_amount']">
          <el-card class="kpi-card kpi-accent-0">
            <div class="kpi-label">毛利润</div>
            <div class="kpi-value" :style="{ color: kpiData.grossProfit < 0 ? '#dc2626' : undefined }">{{ formatMoney(kpiData.grossProfit) }}<small class="kpi-unit">元</small></div>
            <div class="kpi-sub">毛利率 {{ formatPercent(kpiData.grossProfitMargin) }}</div>
          </el-card>
          </CapabilityGate>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6">
          <CapabilityGate card-id="finance_pnl" :requires="['date', 'gross_amount', 'discount_amount', 'net_amount']">
          <el-card class="kpi-card kpi-accent-1">
            <div class="kpi-label">净利润</div>
            <div class="kpi-value" :style="{ color: kpiData.netProfit < 0 ? '#dc2626' : undefined }">{{ formatMoney(kpiData.netProfit) }}<small class="kpi-unit">元</small></div>
            <div class="kpi-sub">净利率 {{ formatPercent(kpiData.netProfitMargin) }}</div>
          </el-card>
          </CapabilityGate>
        </el-col>
      </template>

      <!-- 成本分析 KPI -->
      <template v-if="analysisType === 'cost'">
        <el-col :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card kpi-accent-0">
            <div class="kpi-label">总成本</div>
            <div class="kpi-value">{{ formatMoney(kpiData.totalCost) }}<small class="kpi-unit">元</small></div>
            <div class="kpi-sub" :class="kpiData.costGrowth >= 0 ? 'growth-down' : 'growth-up'">
              环比 {{ kpiData.costGrowth >= 0 ? '+' : '' }}{{ kpiData.costGrowth }}%
            </div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card kpi-accent-1">
            <div class="kpi-label">原材料成本</div>
            <div class="kpi-value">{{ formatMoney(kpiData.materialCost) }}<small class="kpi-unit">元</small></div>
            <div class="kpi-sub">占比 {{ kpiData.totalCost > 0 ? ((kpiData.materialCost / kpiData.totalCost) * 100).toFixed(0) : 0 }}%</div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card kpi-accent-2">
            <div class="kpi-label">人工成本</div>
            <div class="kpi-value">{{ formatMoney(kpiData.laborCost) }}<small class="kpi-unit">元</small></div>
            <div class="kpi-sub">占比 {{ kpiData.totalCost > 0 ? ((kpiData.laborCost / kpiData.totalCost) * 100).toFixed(0) : 0 }}%</div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card kpi-accent-3">
            <div class="kpi-label">间接成本</div>
            <div class="kpi-value">{{ formatMoney(kpiData.overheadCost) }}<small class="kpi-unit">元</small></div>
            <div class="kpi-sub">占比 {{ kpiData.totalCost > 0 ? ((kpiData.overheadCost / kpiData.totalCost) * 100).toFixed(0) : 0 }}%</div>
          </el-card>
        </el-col>
      </template>

      <!-- 应收分析 KPI -->
      <template v-if="analysisType === 'receivable'">
        <el-col :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card kpi-accent-0">
            <div class="kpi-label">应收总额</div>
            <div class="kpi-value">{{ formatMoney(kpiData.totalReceivable) }}<small class="kpi-unit">元</small></div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card success kpi-accent-1">
            <div class="kpi-label">30天内</div>
            <div class="kpi-value">{{ formatMoney(kpiData.receivableAge30) }}<small class="kpi-unit">元</small></div>
            <div class="kpi-sub">正常账期</div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card warning kpi-accent-2">
            <div class="kpi-label">逾期30-60天</div>
            <div class="kpi-value">{{ formatMoney(kpiData.receivableAge60) }}<small class="kpi-unit">元</small></div>
            <div class="kpi-sub">需关注</div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card danger kpi-accent-4">
            <div class="kpi-label">逾期90天+</div>
            <div class="kpi-value">{{ formatMoney(kpiData.receivableAge90Plus) }}<small class="kpi-unit">元</small></div>
            <div class="kpi-sub">高风险</div>
          </el-card>
        </el-col>
      </template>

      <!-- 应付分析 KPI -->
      <template v-if="analysisType === 'payable'">
        <el-col :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card kpi-accent-0">
            <div class="kpi-label">应付总额</div>
            <div class="kpi-value">{{ formatMoney(kpiData.totalPayable) }}<small class="kpi-unit">元</small></div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card success kpi-accent-1">
            <div class="kpi-label">30天内</div>
            <div class="kpi-value">{{ formatMoney(kpiData.payableAge30) }}<small class="kpi-unit">元</small></div>
            <div class="kpi-sub">正常账期</div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card warning kpi-accent-2">
            <div class="kpi-label">30-60天</div>
            <div class="kpi-value">{{ formatMoney(kpiData.payableAge60) }}<small class="kpi-unit">元</small></div>
            <div class="kpi-sub">即将到期</div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card danger kpi-accent-4">
            <div class="kpi-label">逾期90天+</div>
            <div class="kpi-value">{{ formatMoney(kpiData.payableAge90Plus) }}<small class="kpi-unit">元</small></div>
            <div class="kpi-sub">需立即处理</div>
          </el-card>
        </el-col>
      </template>

      <!-- 预算分析 KPI -->
      <template v-if="analysisType === 'budget'">
        <el-col :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card kpi-accent-0">
            <div class="kpi-label">年度预算</div>
            <div class="kpi-value">{{ formatMoney(kpiData.budgetTotal) }}<small class="kpi-unit">元</small></div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card kpi-accent-3">
            <div class="kpi-label">已使用</div>
            <div class="kpi-value">{{ formatMoney(kpiData.budgetUsed) }}<small class="kpi-unit">元</small></div>
            <div class="kpi-sub">使用率 {{ formatPercent(kpiData.budgetUsageRate) }}</div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card success kpi-accent-1">
            <div class="kpi-label">剩余预算</div>
            <div class="kpi-value">{{ formatMoney(kpiData.budgetRemaining) }}<small class="kpi-unit">元</small></div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card kpi-accent-2">
            <div class="kpi-label">预算进度</div>
            <el-progress
              :percentage="kpiData.budgetUsageRate"
              :status="kpiData.budgetUsageRate === 0 ? undefined : kpiData.budgetUsageRate > 90 ? 'exception' : kpiData.budgetUsageRate > 75 ? 'warning' : 'success'"
              :stroke-width="12"
            />
          </el-card>
        </el-col>
      </template>
    </el-row>

    <!-- 图表和预警 -->
    <el-row :gutter="16" class="content-section">
      <el-col :xs="24" :lg="16">
        <el-card class="chart-card">
          <template #header>
            <div class="card-header">
              <el-icon><TrendCharts /></el-icon>
              <span>{{ analysisTypes.find(t => t.type === analysisType)?.label }}图表</span>
            </div>
          </template>
          <!-- Phase 6: DynamicChartRenderer when config available -->
          <DynamicChartRenderer
            v-if="useDynamicRenderer && mainDynamicConfig"
            :config="mainDynamicConfig"
            :height="360"
          />
          <!-- Chart Insight: runtime type mapped to meta (exotic types → null → renders nothing) -->
          <ChartInsightProvider
            v-if="useDynamicRenderer && mainDynamicConfig"
            :chart="mainChartInsightMeta"
            :perms="{ canViewFinance: canViewPrice }"
            :factory-id="factoryId ?? ''"
            depth="detailed"
          />
          <!-- No chart data for this tab -->
          <div v-else-if="noChartForTab" class="chart-empty-hint">
            <el-icon style="font-size: 40px; color: #C0C4CC;"><TrendCharts /></el-icon>
            <p style="color: #909399; margin-top: 12px;">
              当前{{ analysisTypes.find(t => t.type === analysisType)?.label || '' }}暂无图表数据
            </p>
            <p v-if="dataSources.length > 0" style="color: #2D8B57; font-size: 12px; cursor: pointer;" @click="selectedDataSource = String(dataSources[0].id)">
              切换到上传数据查看分析
            </p>
          </div>
          <!-- Legacy fallback: raw echarts container -->
          <div v-else id="finance-main-chart" class="chart-container"></div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="8">
        <el-card class="warning-card">
          <template #header>
            <div class="card-header">
              <el-icon><Warning /></el-icon>
              <span>预警提醒</span>
            </div>
          </template>
          <div class="warning-list">
            <div
              v-for="(item, index) in warnings"
              :key="index"
              class="warning-item"
            >
              <el-tag :type="getWarningTagType(item.level)" size="small">
                {{ item.level === 'danger' ? '严重' : item.level === 'warning' ? '警告' : '提示' }}
              </el-tag>
              <div class="warning-content">
                <div class="warning-title">{{ item.title }}</div>
                <div class="warning-desc">{{ item.description }}</div>
                <div v-if="item.amount" class="warning-amount">
                  涉及金额: {{ formatMoney(item.amount) }}
                </div>
              </div>
            </div>
            <div v-if="warnings.length === 0" class="no-warning-hint">
              <template v-if="dataQualityWarning">
                <el-icon style="font-size: 20px; color: #E6A23C;"><Warning /></el-icon>
                <span style="color: #909399; font-size: 13px; margin-left: 6px;">暂无数据，无法生成预警分析</span>
              </template>
              <template v-else>
                <el-icon style="font-size: 20px; color: #67C23A;"><View /></el-icon>
                <span style="color: #909399; font-size: 13px; margin-left: 6px;">当前无风险预警，经营状况良好</span>
              </template>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- AI 洞察面板 (仅动态数据源显示) -->
    <el-card v-if="aiInsights.length > 0" class="insight-card">
      <template #header>
        <div class="card-header">
          <el-icon><TrendCharts /></el-icon>
          <span>AI 智能洞察</span>
          <el-tag type="success" size="small" style="margin-left: 8px">来自上传数据</el-tag>
          <span v-if="insightTimestamp" class="insight-header-timestamp">
            生成于 {{ insightTimestamp.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}
          </span>
        </div>
      </template>
      <div class="insight-list" role="list">
        <div
          v-for="(insight, index) in aiInsights"
          :key="index"
          class="insight-item"
          role="listitem"
        >
          <el-tag :type="getInsightType(insight).tagType" size="small" style="flex-shrink:0">
            <el-icon><component :is="getInsightType(insight).icon" /></el-icon>
          </el-tag>
          <span class="insight-content">
            <template v-for="(seg, si) in parseInsightCitations(insight)" :key="si">
              <span
                v-if="seg.chartIndex != null"
                class="insight-citation"
                :title="'来源: ' + seg.chartTitle"
                @click="scrollToChart(seg.chartIndex)"
              >{{ seg.text }}<sup>[{{ seg.chartIndex + 1 }}]</sup></span>
              <span v-else>{{ seg.text }}</span>
            </template>
          </span>
        </div>
        <div class="insight-meta" v-if="insightTimestamp">
          <el-icon><Clock /></el-icon>
          <span class="insight-timestamp">{{ formatInsightTime(insightTimestamp) }}</span>
          <span class="insight-citation-legend" v-if="explorationCharts.length > 0">
            <span v-for="(chart, ci) in explorationCharts" :key="ci" class="citation-ref" @click="scrollToChart(ci)">
              [{{ ci + 1 }}] {{ chart.title }}
            </span>
          </span>
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
            :chart="explorationChartInsightMeta(chart)"
            :perms="{ canViewFinance: canViewPrice }"
            :factory-id="factoryId ?? ''"
            depth="detailed"
          />
        </div>
        <SmartBIEmptyState v-if="!explorationLoading && explorationCharts.length === 0" type="no-charts" :show-action="false" />
      </div>
    </el-card>

    <!-- 数据预览对话框 -->
    <el-dialog
      v-model="showDataPreview"
      title="数据预览"
      width="85%"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <div v-loading="previewLoading" class="preview-container">
        <!-- 数据信息 -->
        <div class="preview-info">
          <span>共 {{ previewData.total }} 条数据</span>
          <span>当前第 {{ previewPage }} / {{ previewData.totalPages || 1 }} 页</span>
        </div>

        <!-- 数据表格 -->
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

        <!-- 分页 -->
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

    <!-- Week 6 Template Surfacing: show analysis results for this page -->
    <TemplateGrid page-key="finance" :factory-id="factoryId || ''" />

    <!-- Day 9 数据织网 Sub-Project A: unlock more analyses CTA -->
    <UnlockMoreCTA />
    </template>
    <el-empty v-else description="您没有查看价格/财务数据的权限" />
  </div>
</template>

<style lang="scss" scoped>
.finance-analysis-page {
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

// 分析类型切换
.type-switch-card {
  margin-bottom: 16px;
  border-radius: 8px;

  :deep(.el-card__body) {
    padding: 12px 16px;
  }
}

.type-switch {
  display: flex;
  gap: 12px;
  overflow-x: auto;

  .type-item {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 20px;
    border-radius: 8px;
    background: var(--el-fill-color-light, #f5f7fa);
    cursor: pointer;
    transition: all 0.3s;
    white-space: nowrap;

    .el-icon {
      font-size: 18px;
      color: var(--el-text-color-secondary, #909399);
    }

    span {
      font-size: 14px;
      color: var(--el-text-color-regular, #606266);
    }

    &:hover {
      background: var(--el-color-primary-light-9, #ecf5ff);

      .el-icon, span {
        color: var(--el-color-primary, #2D8B57);
      }
    }

    &.active {
      background: var(--el-color-primary, #2D8B57);

      .el-icon, span {
        color: #fff;
      }
    }
  }
}

// KPI 卡片
.kpi-section {
  margin-bottom: 16px;

  .el-col {
    margin-bottom: 16px;
  }
}

.kpi-card {
  border-radius: 8px;
  text-align: center;
  padding: 8px 0;
  border-top: 3px solid var(--el-color-primary, #2D8B57);
  border-left: none;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
  cursor: default;

  &:hover {
    transform: translateY(-2px);
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.1);
  }

  &.success {
    border-top-color: var(--el-color-success, #67C23A);
  }

  &.warning {
    border-top-color: var(--el-color-warning, #E6A23C);
  }

  &.danger {
    border-top-color: var(--el-color-danger, #F56C6C);
  }

  &.kpi-accent-0 { border-top-color: #2D8B57; }
  &.kpi-accent-1 { border-top-color: #36B37E; }
  &.kpi-accent-2 { border-top-color: #FFAB00; }
  &.kpi-accent-3 { border-top-color: #6554C0; }
  &.kpi-accent-4 { border-top-color: #FF5630; }

  .kpi-label {
    font-size: 13px;
    color: var(--el-text-color-secondary, #909399);
    margin-bottom: 8px;
  }

  .kpi-value {
    font-size: 26px;
    font-weight: 600;
    white-space: nowrap;
    color: var(--el-text-color-primary, #303133);
    margin-bottom: 4px;

    .kpi-unit {
      font-size: 14px;
      font-weight: 400;
      color: var(--el-text-color-secondary, #909399);
      margin-left: 2px;
    }
  }

  .kpi-sub {
    font-size: 12px;
    color: var(--el-text-color-secondary, #909399);

    &.growth-up {
      color: var(--el-color-success, #67C23A);
    }

    &.growth-down {
      color: var(--el-color-danger, #F56C6C);
    }
  }
}

// 内容区
.content-section {
  .el-col {
    margin-bottom: 16px;
  }
}

.chart-card, .warning-card {
  border-radius: 8px;
  height: 100%;
  transition: transform 0.25s cubic-bezier(0.4, 0, 0.2, 1), box-shadow 0.25s cubic-bezier(0.4, 0, 0.2, 1);

  &:hover {
    transform: translateY(-2px);
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.1);
  }

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

.chart-container {
  height: 360px;
  width: 100%;
}

.chart-empty-hint {
  height: 360px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.warning-list {
  .warning-item {
    display: flex;
    gap: 12px;
    padding: 14px 0;
    border-bottom: 1px solid var(--el-border-color-lighter, #f0f2f5);

    &:last-child {
      border-bottom: none;
    }

    .el-tag {
      flex-shrink: 0;
    }

    .warning-content {
      flex: 1;

      .warning-title {
        font-weight: 500;
        color: var(--el-text-color-primary, #303133);
        margin-bottom: 4px;
      }

      .warning-desc {
        font-size: 13px;
        color: var(--el-text-color-secondary, #909399);
        margin-bottom: 4px;
      }

      .warning-amount {
        font-size: 12px;
        color: var(--el-text-color-regular, #606266);
      }
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

// 数据源选择器
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

// AI 洞察面板
.insight-card {
  margin-top: 16px;
  border-radius: 8px;
  border-left: 4px solid var(--el-color-success, #67C23A);

  .card-header {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;

    .el-icon {
      color: var(--el-color-success, #67C23A);
    }
  }
}

.insight-header-timestamp {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  font-weight: 400;
}

.insight-list {
  .insight-item {
    display: flex;
    align-items: flex-start;
    gap: 10px;
    padding: 12px 16px;
    border-bottom: 1px solid var(--el-border-color-lighter, #f0f2f5);
    line-height: 1.6;
    color: var(--el-text-color-primary, #303133);
    transition: background 0.2s;

    &:hover {
      background: var(--el-fill-color-lighter, #fafafa);
    }

    &:last-child {
      border-bottom: none;
    }

    .insight-content {
      flex: 1;
      min-width: 200px;
    }
  }
}

.insight-meta {
  margin-top: 12px;
  padding: 10px 16px 0;
  border-top: 1px solid var(--el-border-color-lighter, #f0f2f5);
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;

  .insight-timestamp {
    margin-right: 12px;
  }

  .insight-citation-legend {
    display: flex;
    gap: 10px;
    margin-left: auto;
    flex-wrap: wrap;

    .citation-ref {
      cursor: pointer;
      color: var(--el-color-primary, #409EFF);
      transition: opacity 0.2s;

      &:hover {
        opacity: 0.7;
        text-decoration: underline;
      }
    }
  }
}

.insight-citation {
  cursor: pointer;
  color: var(--el-color-primary, #409EFF);
  transition: color 0.2s;

  &:hover {
    text-decoration: underline;
  }

  sup {
    font-size: 10px;
    margin-left: 1px;
    font-weight: 600;
  }
}

// 数据预览对话框
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
    height: 300px;
  }

  .chart-empty-state {
    height: 300px;
  }
}

@media (max-width: 1366px) {
  .exploration-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .type-switch {
    flex-wrap: nowrap;
    overflow-x: auto;
    padding-bottom: 8px;

    &::-webkit-scrollbar {
      height: 4px;
    }
  }

  .filter-bar {
    flex-direction: column;
    align-items: flex-start !important;
  }

  .filter-item {
    width: 100%;
  }

  .chart-container {
    height: 260px;
  }

  .chart-empty-state {
    height: 260px;
  }

  .exploration-grid {
    grid-template-columns: 1fr;
  }
}

.gold-cta {
  margin-bottom: 16px;
}
.gold-cta-row {
  display: flex;
  align-items: center;
  gap: 16px;
  justify-content: space-between;
  flex-wrap: wrap;
}
</style>
