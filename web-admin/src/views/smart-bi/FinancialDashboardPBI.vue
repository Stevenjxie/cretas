<script setup lang="ts">
/**
 * FinancialDashboardPBI - 财务分析看板 (Power BI Style)
 * Orchestrates all 7 financial chart types with AI analysis and PPT export
 */
import { ref, reactive, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import FinanceAnalysis from './FinanceAnalysis.vue';
import FinancePbiGoldView from './components/FinancePbiGoldView.vue';
import { resolveFinanceSection, shouldRenderGoldFinanceView } from './financeDashboardSection';
import { DataAnalysis, VideoPlay, Download, Collection, SetUp, ArrowDown, ArrowRight, Delete } from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import { processEChartsOptions } from '@/utils/echarts-fmt';
import { sparklinePath, sparklineSVG } from '@/utils/sparkline';

/** Deep clone that handles ECharts options with functions (structuredClone can't clone functions) */
function safeClone<T>(obj: T): T {
  try {
    return structuredClone(obj);
  } catch {
    // Fallback: JSON round-trip (drops functions, but processEChartsOptions re-adds formatters)
    try {
      return JSON.parse(JSON.stringify(obj));
    } catch {
      return obj;
    }
  }
}

// Components
import PeriodSelector from '@/components/smartbi/PeriodSelector.vue';
import ChartSkeleton from '@/components/smartbi/ChartSkeleton.vue';
import PresentationMode from '@/components/smartbi/PresentationMode.vue';
import type { Slide } from '@/components/smartbi/PresentationMode.vue';
import SankeyChart from '@/components/smartbi/SankeyChart.vue';
import SmallMultiplesChart from '@/components/smartbi/SmallMultiplesChart.vue';
import BookmarkPanel from '@/components/smartbi/BookmarkPanel.vue';
import ConditionalFormatPanel from '@/components/smartbi/ConditionalFormatPanel.vue';
import SmartBIEmptyState from '@/components/smartbi/SmartBIEmptyState.vue';
import type { BookmarkState } from '@/views/smart-bi/composables/useBookmarks';
import type { SmallMultiplesConfig } from '@/components/smartbi/SmallMultiplesChart.vue';
import ChartInsightProvider from './components/ChartInsightProvider.vue';
import type { ChartWithMeta, ChartMeta, UserPermissions } from './components/chartInsight';

// API
import {
  batchGenerate,
  analyzeChart,
  exportPPT,
  exportPDF,
  exportExcel,
  type ChartResult,
  type DashboardResponse,
  type FinancialDashboardRequest,
} from '@/api/smartbi/financial-dashboard';

// Types from PeriodSelector
import type { PeriodSelection } from '@/components/smartbi/PeriodSelector.vue';

// ---- State ----
const isDarkMode = ref(false);
const activeFilter = ref<{ dimension: string; value: string } | null>(null);
const autoRefreshInterval = ref<number>(0); // 0=off, 60000=1m, 300000=5m, 900000=15m

// Fix 72: Global slicer filters (Power BI / Looker style)
const slicerFilters = ref<Record<string, string[]>>({});
const availableDimensions = ref<{ name: string; values: string[] }[]>([]);
let autoRefreshTimer: ReturnType<typeof setInterval> | null = null;
const useSvgRenderer = ref(false); // D4: SVG renderer toggle
const isInDemoMode = ref(false); // Track demo mode for slicer re-generation

// P4: 顶层 section tab — 财务 PBI 看板(dashboard) / 财务数据分析(analysis)。
// 注意: 与下方 chart activeTab 完全独立 (后者是图表类型 key)。
// 修 #10: 餐饮租户默认落「财务数据分析」(gold 全历史, 进页即见数据); 工厂租户保持 PBI 看板默认。
// 显式 ?section= 永远优先 (保书签 / 工厂用户主动选看板)。
const route = useRoute();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const sectionTab = ref(resolveFinanceSection(route.query, authStore.factoryType));
watch(() => route.query.section, () => { sectionTab.value = resolveFinanceSection(route.query, authStore.factoryType); });

// follow-up (fu2): 餐饮租户在 PBI 看板(dashboard)子页 → 自动渲染 Gold 财务全量视图
// (KPI 卡 + 月度营收趋势 + 门店排行 + 渠道占比), 免上传 / 免手动生成。
// PBI 看板原本 upload_id 绑定, 餐饮租户没传财务 Excel → 只能看到空态。
// 工厂租户保持原 upload-based PBI 看板 (PeriodSelector + 生成看板) 不变。
// 餐饮租户可点「上传财务文件」临时切到 upload 路径 (高级)。
const showUploadForRestaurant = ref(false);
const goldFinanceMode = computed(() =>
  shouldRenderGoldFinanceView(authStore.factoryType, sectionTab.value) && !showUploadForRestaurant.value);

// Tab navigation state
const viewMode = ref<'tab' | 'grid'>('tab');
const activeTab = ref('budget_achievement');
const collapsedGroups = reactive(new Set<string>());
const conclusionsText = ref('');
const conclusionsLoading = ref(false);

// Follow-up interaction state
interface FollowUpItem { question: string; answer: string }
const followUpsByType = ref<Record<string, FollowUpItem[]>>({});
const followUpInput = ref('');
const followUpLoading = ref(false);

// D2: User annotations (persisted in localStorage)
interface Annotation { text: string; x: number; y: number; chartType: string; color: string; id: number }
const annotations = ref<Record<string, Annotation[]>>(
  JSON.parse(localStorage.getItem('financial-dashboard-annotations') || '{}')
);
const annotationDialogVisible = ref(false);
const annotationForm = ref({ text: '', color: '#e6a23c', chartType: '', x: 0, y: 0 });
let annotationIdCounter = Date.now();

function saveAnnotations() {
  localStorage.setItem('financial-dashboard-annotations', JSON.stringify(annotations.value));
}

function openAnnotationDialog(chartType: string, x: number, y: number) {
  annotationForm.value = { text: '', color: '#e6a23c', chartType, x, y };
  annotationDialogVisible.value = true;
}

function confirmAnnotation() {
  const { text, color, chartType, x, y } = annotationForm.value;
  if (!text.trim()) return;
  if (!annotations.value[chartType]) annotations.value[chartType] = [];
  annotations.value[chartType].push({ text: text.trim(), x, y, chartType, color, id: annotationIdCounter++ });
  saveAnnotations();
  annotationDialogVisible.value = false;
  // Re-render chart to show annotation
  const domEl = chartDomRefs.value.get(chartType);
  if (domEl) renderGenericChart(chartType, domEl);
}

function removeAnnotation(chartType: string, annotationId: number) {
  if (!annotations.value[chartType]) return;
  annotations.value[chartType] = annotations.value[chartType].filter(a => a.id !== annotationId);
  saveAnnotations();
  const domEl = chartDomRefs.value.get(chartType);
  if (domEl) renderGenericChart(chartType, domEl);
}

function clearAllAnnotations(chartType: string) {
  annotations.value[chartType] = [];
  saveAnnotations();
  const domEl = chartDomRefs.value.get(chartType);
  if (domEl) renderGenericChart(chartType, domEl);
}

const uploadId = ref<number | null>(null);
const uploadIdInput = ref<string>('');
// 默认「全部历史」: type='year' + allHistory 标记 → 后端 period_type='year'
// (normalizer 对 'year' 不做任何月份过滤, 即返回该数据源全部数据)。
const periodSelection = ref<PeriodSelection>({
  type: 'year',
  year: new Date().getFullYear(),
  value: String(new Date().getFullYear()),
  compareEnabled: false,
  allHistory: true,
});

const isGenerating = ref(false);
const dashboardResponse = ref<DashboardResponse | null>(null);

// Per-chart AI analysis state
const analysisByType = ref<Record<string, string>>({});
const analysisLoadingByType = ref<Record<string, boolean>>({});
const analysisExpandedByType = ref<Record<string, boolean>>({});

// PPT export
const isExportingPPT = ref(false);
const isExportingPDF = ref(false);
const isExportingExcel = ref(false);
const exportStageText = ref('');

// Export progress feedback
const exportProgress = ref({
  visible: false,
  currentStep: 0,
  steps: [] as string[],
  percentage: 0,
});
const EXPORT_STEPS: Record<string, string[]> = {
  ppt: ['准备图表数据', '渲染图表截图', '生成幻灯片', '打包下载'],
  pdf: ['准备页面内容', '渲染图表', '生成PDF', '下载文件'],
  excel: ['整理数据表', '格式化单元格', '生成文件', '下载'],
};
function startExportProgress(type: string) {
  exportProgress.value.steps = EXPORT_STEPS[type] || [];
  exportProgress.value.currentStep = 0;
  exportProgress.value.percentage = 0;
  exportProgress.value.visible = true;
}
async function advanceExportStep() {
  const ep = exportProgress.value;
  ep.currentStep++;
  ep.percentage = Math.round((ep.currentStep / ep.steps.length) * 100);
  await new Promise(r => setTimeout(r, 300));
}
function finishExportProgress() {
  exportProgress.value.percentage = 100;
  exportProgress.value.currentStep = exportProgress.value.steps.length;
  setTimeout(() => { exportProgress.value.visible = false; }, 600);
}

// Presentation mode
const isPresentationVisible = ref(false);

// Bookmarks
const showBookmarkPanel = ref(false);

// Small Multiples
const smallMultiplesVisible = ref(false);
const smallMultiplesDimension = ref('category');

// Conditional Formatting (panel is self-contained via service singleton)

// Chart instance refs for getDataURL
const chartRefSankey = ref<InstanceType<typeof SankeyChart> | null>(null);
// Generic chart DOM refs for existing chart components
const chartDomRefs = ref<Map<string, HTMLElement>>(new Map());

// Lazy rendering: track which chart cards are visible in the viewport
const visibleCharts = reactive(new Set<string>());
let lazyObserver: IntersectionObserver | null = null;
const observedElements = new WeakSet<Element>();

function setupLazyObserver(): void {
  if (lazyObserver) return;
  lazyObserver = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        const chartType = (entry.target as HTMLElement).dataset.chartType;
        if (!chartType) continue;
        if (entry.isIntersecting) {
          visibleCharts.add(chartType);
          // Once visible, render immediately if data exists
          const domEl = chartDomRefs.value.get(chartType);
          if (domEl && getChart(chartType)) {
            requestAnimationFrame(() => renderGenericChart(chartType, domEl));
          }
        }
      }
    },
    { rootMargin: '200px' },
  );
}

/** Mark all charts visible with staggered fade-in delay (initial load). */
function revealAllCharts(): void {
  const allTypes = charts.value.map((c) => c.chartType);
  allTypes.forEach((t, i) => {
    setTimeout(() => visibleCharts.add(t), i * 60);
  });
}

function observeChartCard(el: HTMLElement, chartType: string): void {
  setupLazyObserver();
  if (!lazyObserver || observedElements.has(el)) return;
  el.dataset.chartType = chartType;
  lazyObserver.observe(el);
  observedElements.add(el);
}

function teardownLazyObserver(): void {
  if (lazyObserver) {
    lazyObserver.disconnect();
    lazyObserver = null;
  }
  visibleCharts.clear();
}

// ---- Computed ----
const currentYear = computed(() => periodSelection.value.year);
// 「全部历史」与「单年」都映射 period_type='year' (normalizer 对 'year' 不做月份过滤 → 返回全部数据)。
const periodType = computed(() =>
  periodSelection.value.allHistory || periodSelection.value.type === 'year' ? 'year' : 'month_range');
const startMonth = computed(() => {
  if (periodSelection.value.allHistory || periodSelection.value.type === 'year') return 1;
  if (Array.isArray(periodSelection.value.value)) {
    return parseInt(String(periodSelection.value.value[0]).split('-')[1] || '1', 10);
  }
  return 1;
});
const endMonth = computed(() => {
  if (periodSelection.value.allHistory || periodSelection.value.type === 'year') return 12;
  if (Array.isArray(periodSelection.value.value)) {
    return parseInt(String(periodSelection.value.value[1]).split('-')[1] || '12', 10);
  }
  return 12;
});

const charts = computed(() => dashboardResponse.value?.charts ?? []);

// P1 PERF fix: Pre-index charts by type to avoid O(n) find() per template access (~36+ calls per render)
const chartMap = computed(() => {
  const map = new Map<string, ChartResult>();
  for (const c of charts.value) map.set(c.chartType, c);
  return map;
});

function getChart(chartType: string): ChartResult | null {
  return chartMap.value.get(chartType) ?? null;
}

// P1 PERF fix: Pre-compute anomaly detection results to avoid recalculation in template
const anomalyMap = computed(() => {
  const map = new Map<string, string[]>();
  for (const [type, chart] of chartMap.value) {
    if (chart.kpis?.length) {
      map.set(type, detectAnomalies(chart.kpis));
    }
  }
  return map;
});

function getAnomalies(chartType: string): string[] {
  return anomalyMap.value.get(chartType) ?? [];
}

// ── Phase B Moderate: ChartInsightProvider wiring ─────────────────────────────
// Finance domain; canViewPrice gates absolute ¥ display in insight text.
const insightPerms = computed<UserPermissions>(() => ({
  canViewFinance: permissionStore.canViewPrice,
}));

/**
 * Chart-type → (chartType, meta) mapping for Tier1-coverable slots.
 *
 * Wired (bar/line/pie): LINE→TREND, BAR(ranking)→RANKING, BAR(2-series)→COMPARISON, PIE→PROPORTION.
 * Skipped (exotic): pnl_waterfall, cash_flow_waterfall, cost_flow_sankey, small_multiples, bullet_chart.
 *
 * meta inference: all keys have domain:'finance'. xDim and yMetric are inferred from the chart key.
 */
const FINANCE_CHART_META: Record<string, { chartType: string; meta: ChartMeta }> = {
  // BAR 2-series COMPARISON: actual vs budget
  budget_achievement: {
    chartType: 'comparison',
    meta: { xDim: 'time', yMetric: 'revenue', aggregation: 'sum', domain: 'finance' },
  },
  // LINE×2 COMPARISON: current year vs last year
  yoy_mom_comparison: {
    chartType: 'comparison',
    meta: { xDim: 'time', yMetric: 'revenue', aggregation: 'sum', domain: 'finance' },
  },
  // BAR(2-series) COMPARISON: category this year vs last year
  category_yoy_comparison: {
    chartType: 'comparison',
    meta: { xDim: 'category', yMetric: 'revenue', aggregation: 'sum', domain: 'finance' },
  },
  // PIE/donut PROPORTION: product structure
  category_structure_donut: {
    chartType: 'pie',
    meta: { xDim: 'product', yMetric: 'revenue', aggregation: 'sum', domain: 'finance' },
  },
  // BAR RANKING: channel revenue ranking
  channel_analysis: {
    chartType: 'bar',
    meta: { xDim: 'channel', yMetric: 'revenue', aggregation: 'sum', domain: 'finance' },
  },
  // LINE TREND: cashflow over time
  cashflow_trend: {
    chartType: 'line',
    meta: { xDim: 'time', yMetric: 'cost', aggregation: 'sum', domain: 'finance' },
  },
  // BAR COMPARISON: hr cost analysis (budget vs actual or period vs period)
  hr_cost_analysis: {
    chartType: 'comparison',
    meta: { xDim: 'time', yMetric: 'cost', aggregation: 'sum', domain: 'finance' },
  },
  // SKIPPED: pnl_waterfall → exotic WATERFALL
  // SKIPPED: cash_flow_waterfall → exotic WATERFALL
  // SKIPPED: cost_flow_sankey → exotic SANKEY
  // SKIPPED: small_multiples → exotic small-multiples
  // SKIPPED: bullet_chart → exotic gauge-like, not 1-D series
};

/**
 * Build ChartWithMeta from a ChartResult by extracting series/xAxis from echartsOption.
 * Returns null when:
 *  - chartType is not in FINANCE_CHART_META (exotic or skipped)
 *  - ChartResult is null / has no echartsOption
 */
function buildFinanceChartWithMeta(chartType: string): ChartWithMeta | null {
  const mapping = FINANCE_CHART_META[chartType];
  if (!mapping) return null; // exotic or not wired
  const result = getChart(chartType);
  if (!result?.echartsOption) return null;

  const opt = result.echartsOption as {
    xAxis?: { data?: string[] };
    series?: Array<{ type?: string; name?: string; data?: unknown[] }>;
    [key: string]: unknown;
  };

  // Extract xAxis labels + series from echartsOption
  const xAxis = opt.xAxis as { data?: string[] } | undefined;
  const series = Array.isArray(opt.series) ? opt.series : [];

  return {
    chartType: mapping.chartType,
    title: result.title,
    meta: mapping.meta,
    config: {
      xAxis: xAxis ? { data: xAxis.data ?? [] } : undefined,
      series: series.map((s) => ({
        type: (s as { type?: string }).type,
        name: (s as { name?: string }).name,
        data: Array.isArray((s as { data?: unknown[] }).data) ? (s as { data?: unknown[] }).data : [],
      })),
    },
  };
}

// 去掉与其他页面重复的图表:
// kpi_scorecard → 经营驾驶舱, expense_yoy_budget → 财务分析-成本, gross_margin_trend → 财务分析-利润,
// product_ranking → 销售分析+驾驶舱, ar_aging → 财务分析-应收, variance_analysis → 财务分析-预算
const chartTypes = [
 { key: 'budget_achievement', label: '预算达成分析', icon: '', group: '核心财务', order: 1 },
 { key: 'yoy_mom_comparison', label: '同环比分析', icon: '', group: '核心财务', order: 2 },
 { key: 'pnl_waterfall', label: '损益表瀑布图', icon: '', group: '核心财务', order: 3 },
 { key: 'category_yoy_comparison', label: '品类同期对比', icon: '', group: '品类与渠道', order: 4 },
 { key: 'category_structure_donut',label: '产品结构', icon: '', group: '品类与渠道', order: 5 },
 { key: 'channel_analysis', label: '渠道分析', icon: '', group: '品类与渠道', order: 6 },
 { key: 'cash_flow_waterfall', label: '现金流量瀑布图', icon: '', group: '现金流与成本', order: 7 },
 { key: 'cashflow_trend', label: '现金流趋势', icon: '', group: '现金流与成本', order: 8 },
 { key: 'cost_flow_sankey', label: '成本流向桑基图', icon: '', group: '现金流与成本', order: 9 },
 { key: 'bullet_chart', label: '目标达成进度', icon: '', group: '运营专项', order: 10 },
 { key: 'hr_cost_analysis', label: '人力成本分析', icon: '', group: '运营专项', order: 11 },
 { key: 'small_multiples', label: '多维度对比矩阵', icon: '', group: '运营专项', order: 12 },
];

const chartsByGroup = computed(() => {
  const groups = new Map<string, typeof chartTypes>();
  for (const ct of chartTypes) {
    if (!groups.has(ct.group)) groups.set(ct.group, []);
    groups.get(ct.group)!.push(ct);
  }
  return groups;
});

const presentationSlides = computed<Slide[]>(() => {
  const slides: Slide[] = [{ type: 'cover' }];
  for (const ct of chartTypes) {
    if (getChart(ct.key)) {
      slides.push({ type: 'chart', chartType: ct.key, title: ct.label });
    }
  }
  slides.push({ type: 'conclusion' });
  return slides;
});

// Built-in demo data — comprehensive financial data covering all chart builder requirements:
// - 月份+品类+预算+实际+上年 → budget_achievement, yoy_mom, expense_yoy_budget, category_yoy, category_structure
// - 项目(P&L items) → pnl_waterfall, cost_flow_sankey
// - 毛利率 → gross_margin_trend
const DEMO_RAW_DATA: Record<string, unknown>[] = [
  // ---- 品类月度收入 (6个月 × 3品类 = 18行) ----
  // 统一 schema: 月份, 品类, 项目, 预算, 实际, 上年
  {'月份':'1月','品类':'酱料','项目':'营业收入','预算':2800,'实际':3100,'上年':1800},
  {'月份':'2月','品类':'酱料','项目':'营业收入','预算':2900,'实际':2700,'上年':1900},
  {'月份':'3月','品类':'酱料','项目':'营业收入','预算':3000,'实际':3200,'上年':2000},
  {'月份':'4月','品类':'酱料','项目':'营业收入','预算':3100,'实际':2950,'上年':2100},
  {'月份':'5月','品类':'酱料','项目':'营业收入','预算':3200,'实际':3400,'上年':2200},
  {'月份':'6月','品类':'酱料','项目':'营业收入','预算':3300,'实际':3100,'上年':2300},
  {'月份':'1月','品类':'汤料','项目':'营业收入','预算':2000,'实际':2200,'上年':1500},
  {'月份':'2月','品类':'汤料','项目':'营业收入','预算':2100,'实际':1900,'上年':1600},
  {'月份':'3月','品类':'汤料','项目':'营业收入','预算':2200,'实际':2400,'上年':1700},
  {'月份':'4月','品类':'汤料','项目':'营业收入','预算':2300,'实际':2150,'上年':1800},
  {'月份':'5月','品类':'汤料','项目':'营业收入','预算':2400,'实际':2500,'上年':1900},
  {'月份':'6月','品类':'汤料','项目':'营业收入','预算':2500,'实际':2350,'上年':2000},
  {'月份':'1月','品类':'底料','项目':'营业收入','预算':1500,'实际':1600,'上年':1200},
  {'月份':'2月','品类':'底料','项目':'营业收入','预算':1600,'实际':1400,'上年':1300},
  {'月份':'3月','品类':'底料','项目':'营业收入','预算':1700,'实际':1800,'上年':1400},
  {'月份':'4月','品类':'底料','项目':'营业收入','预算':1800,'实际':1750,'上年':1500},
  {'月份':'5月','品类':'底料','项目':'营业收入','预算':1900,'实际':2000,'上年':1600},
  {'月份':'6月','品类':'底料','项目':'营业收入','预算':2000,'实际':1850,'上年':1700},
  // ---- P&L 费用项目 (月度化，6个月 × 4类费用 = 24行) ----
  {'月份':'1月','项目':'营业成本','预算':2625,'实际':2473,'上年':2065},
  {'月份':'2月','项目':'营业成本','预算':2625,'实际':2520,'上年':2065},
  {'月份':'3月','项目':'营业成本','预算':2625,'实际':2590,'上年':2065},
  {'月份':'4月','项目':'营业成本','预算':2625,'实际':2650,'上年':2065},
  {'月份':'5月','项目':'营业成本','预算':2625,'实际':2780,'上年':2065},
  {'月份':'6月','项目':'营业成本','预算':2625,'实际':2490,'上年':2065},
  {'月份':'1月','项目':'销售费用','预算':600,'实际':565,'上年':472},
  {'月份':'2月','项目':'销售费用','预算':600,'实际':580,'上年':472},
  {'月份':'3月','项目':'销售费用','预算':600,'实际':610,'上年':472},
  {'月份':'4月','项目':'销售费用','预算':600,'实际':550,'上年':472},
  {'月份':'5月','项目':'销售费用','预算':600,'实际':620,'上年':472},
  {'月份':'6月','项目':'销售费用','预算':600,'实际':590,'上年':472},
  {'月份':'1月','项目':'管理费用','预算':450,'实际':424,'上年':354},
  {'月份':'2月','项目':'管理费用','预算':450,'实际':410,'上年':354},
  {'月份':'3月','项目':'管理费用','预算':450,'实际':445,'上年':354},
  {'月份':'4月','项目':'管理费用','预算':450,'实际':430,'上年':354},
  {'月份':'5月','项目':'管理费用','预算':450,'实际':460,'上年':354},
  {'月份':'6月','项目':'管理费用','预算':450,'实际':415,'上年':354},
  {'月份':'1月','项目':'研发费用','预算':300,'实际':283,'上年':236},
  {'月份':'2月','项目':'研发费用','预算':300,'实际':275,'上年':236},
  {'月份':'3月','项目':'研发费用','预算':300,'实际':310,'上年':236},
  {'月份':'4月','项目':'研发费用','预算':300,'实际':290,'上年':236},
  {'月份':'5月','项目':'研发费用','预算':300,'实际':305,'上年':236},
  {'月份':'6月','项目':'研发费用','预算':300,'实际':280,'上年':236},
];

// ---- Methods ----
// Bug #2: track in-flight generate so double-clicks and unmount can cancel/dedupe.
// `isComponentUnmounted` flag prevents state updates after the view is torn down.
let isComponentUnmounted = false;

async function generate(useDemo?: boolean) {
  // Bug #2: dedupe — ignore if a generate is already in flight (prevents double-click canceled errors)
  if (isGenerating.value) {
    ElMessage.info('正在生成中，请稍候...');
    return;
  }
  // If not explicitly passed, use tracked demo mode
  if (useDemo === undefined) useDemo = isInDemoMode.value;
  if (useDemo) isInDemoMode.value = true;
  const id = uploadId.value ?? (uploadIdInput.value ? parseInt(uploadIdInput.value, 10) : null);
  if (!useDemo && !id) {
    ElMessage.warning('请先输入数据源ID或选择已上传的数据');
    return;
  }

  if (id && isNaN(id)) {
    ElMessage.warning('数据源ID格式不正确');
    return;
  }

  isGenerating.value = true;
  analysisByType.value = {};
  analysisExpandedByType.value = {};

  const payload: FinancialDashboardRequest = {
    year: currentYear.value,
    period_type: periodType.value,
    start_month: startMonth.value,
    end_month: endMonth.value,
  };
  if (useDemo) {
    payload.raw_data = DEMO_RAW_DATA;
  } else if (id) {
    payload.upload_id = id;
  }
  // Fix 72: Pass slicer filters
  const activeSlicers = Object.entries(slicerFilters.value).filter(([, vals]) => vals.length > 0);
  if (activeSlicers.length > 0) {
    (payload as Record<string, unknown>).filters = Object.fromEntries(activeSlicers);
  }

  try {
    const resp = await batchGenerate(payload);
    // Bug #2: bail if the component unmounted while the request was in flight
    if (isComponentUnmounted) return;
    if (resp.success) {
      dashboardResponse.value = resp;
      // Fix 72: Extract available filter dimensions from response
      if (resp.availableDimensions) {
        availableDimensions.value = resp.availableDimensions;
      } else if (useDemo) {
        // Extract dimensions from demo data
        const dims = new Map<string, Set<string>>();
        for (const row of DEMO_RAW_DATA) {
          for (const [k, v] of Object.entries(row)) {
            if (typeof v === 'string' && !['月份'].includes(k)) {
              if (!dims.has(k)) dims.set(k, new Set());
              dims.get(k)!.add(v);
            }
          }
        }
        availableDimensions.value = Array.from(dims.entries())
          .filter(([, vals]) => vals.size >= 2 && vals.size <= 20)
          .map(([name, vals]) => ({ name, values: Array.from(vals) }));
      }
      // Reveal all chart cards with staggered fade-in
      nextTick(() => revealAllCharts());
      ElMessage.success(`成功生成 ${resp.successCount} 个图表`);
      // Auto-trigger AI analysis for all charts
      nextTick(() => autoAnalyzeAllCharts());
    } else {
      ElMessage.error('图表生成失败，请检查数据源');
    }
  } catch (err) {
    console.error('generate failed:', err);
    ElMessage.error('请求失败，请稍后重试');
  } finally {
    isGenerating.value = false;
  }
}

/**
 * Auto-analyze all charts after dashboard generation.
 * Requests run with concurrency limit of 3 to avoid API overload.
 * Bug #2: stop iterating if the component unmounts — prevents request cancel noise.
 */
async function autoAnalyzeAllCharts() {
  const allChartTypes = charts.value.map(c => c.chartType);
  if (!allChartTypes.length) return;

  const CONCURRENCY = 3;
  let idx = 0;

  async function next(): Promise<void> {
    while (idx < allChartTypes.length && !isComponentUnmounted) {
      const ct = allChartTypes[idx++];
      if (analysisByType.value[ct]) continue; // already loaded
      await requestAnalysis(ct);
    }
  }

  const workers = Array.from({ length: Math.min(CONCURRENCY, allChartTypes.length) }, () => next());
  await Promise.allSettled(workers);
}

function forceRequestAnalysis(chartType: string) {
  // Clear existing result to force re-fetch
  delete analysisByType.value[chartType];
  requestAnalysis(chartType);
}

async function requestAnalysis(chartType: string) {
  // Guard: skip if component has been unmounted (user navigated away)
  // Prevents TypeError: Failed to fetch when pending requests are cancelled.
  if (isComponentUnmounted) return;

  const chart = getChart(chartType);
  if (!chart) return;

  // Skip if already loaded
  if (analysisByType.value[chartType]) return;

  // Prevent duplicate requests while loading
  if (analysisLoadingByType.value[chartType]) return;

  analysisLoadingByType.value[chartType] = true;

  try {
    const resp = await analyzeChart({
      chart_type: chartType,
      analysis_context: chart.analysisContext || '',
    });
    // After await, re-check unmounted — state updates would be wasted + throw
    if (isComponentUnmounted) return;
    if (resp.success) {
      analysisByType.value[chartType] = resp.analysis || '暂无分析结果';
    } else {
      analysisByType.value[chartType] = resp.error || 'AI分析暂时不可用';
    }
  } catch (err) {
    // Swallow abort-style errors silently when unmounted (expected on navigation).
    if (isComponentUnmounted) return;
    const name = err instanceof Error ? err.name : '';
    // "Failed to fetch" happens when browser aborts a pending fetch on navigation.
    if (name === 'AbortError' || (err instanceof TypeError && String(err).includes('fetch'))) {
      // Silent — expected race on page leave
      return;
    }
    console.error('analyzeChart failed:', err);
    analysisByType.value[chartType] = '分析请求失败，请重试';
  } finally {
    if (!isComponentUnmounted) {
      analysisLoadingByType.value[chartType] = false;
    }
  }
}

function setChartDomRef(chartType: string, el: Element | null) {
  if (el instanceof HTMLElement) {
    chartDomRefs.value.set(chartType, el);
  } else {
    chartDomRefs.value.delete(chartType);
  }
}

function collectChartImages(): Record<string, string> {
  const images: Record<string, string> = {};

  // Collect from SankeyChart
  if (chartRefSankey.value?.chartInstance) {
    try {
      images['cost_flow_sankey'] = chartRefSankey.value.chartInstance.getDataURL({
        type: 'png', pixelRatio: 2, backgroundColor: '#fff',
      });
    } catch (e) { console.warn('Failed to get Sankey image:', e); }
  }

  // Collect from generic DOM refs
  for (const [chartType, domEl] of chartDomRefs.value.entries()) {
    const instance = echarts.getInstanceByDom(domEl);
    if (instance) {
      try {
        images[chartType] = instance.getDataURL({
          type: 'png', pixelRatio: 2, backgroundColor: '#fff',
        });
      } catch (e) { console.warn(`Failed to get image for ${chartType}:`, e); }
    }
  }

  return images;
}

function handleExportCommand(command: string) {
  if (command === 'ppt') handleExportPPT();
  else if (command === 'pdf') handleExportPDF();
  else if (command === 'excel') handleExportExcel();
}

async function handleExportPPT() {
  if (!dashboardResponse.value) {
    ElMessage.warning('请先生成图表后再导出PPT');
    return;
  }

  isExportingPPT.value = true;
  startExportProgress('ppt');
  try {
    exportStageText.value = '正在收集图表数据...';
    await advanceExportStep();
    await nextTick();
    const chartImages = collectChartImages();
    const analysisResults: Record<string, string> = { ...analysisByType.value };

    await advanceExportStep();
    exportStageText.value = '正在生成PPT文件...';
    const blob = await exportPPT({
      upload_id: uploadId.value ?? undefined,
      year: currentYear.value,
      period_type: periodType.value,
      start_month: startMonth.value,
      end_month: endMonth.value,
      chart_images: chartImages,
      analysis_results: analysisResults,
      company_name: '白垩纪科技',
    });

    await advanceExportStep();
    if (blob) {
      exportStageText.value = '正在下载文件...';
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `财务分析看板_${currentYear.value}.pptx`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      await advanceExportStep();
      ElMessage.success('PPT导出成功');
    } else {
      ElMessage.error('PPT导出失败，请检查服务状态');
    }
  } catch (err) {
    console.error('exportPPT failed:', err);
    ElMessage.error('PPT导出失败');
  } finally {
    isExportingPPT.value = false;
    exportStageText.value = '';
    finishExportProgress();
  }
}

async function handleExportPDF() {
  if (!dashboardResponse.value) {
    ElMessage.warning('请先生成图表后再导出PDF');
    return;
  }
  isExportingPDF.value = true;
  startExportProgress('pdf');
  try {
    exportStageText.value = '正在收集图表数据...';
    await advanceExportStep();
    await nextTick();
    const chartImages = collectChartImages();
    const analysisResults: Record<string, string> = { ...analysisByType.value };
    await advanceExportStep();
    exportStageText.value = '正在生成PDF报告...';
    const blob = await exportPDF({
      chart_images: chartImages,
      analysis_results: analysisResults,
      company_name: '白垩纪科技',
      year: currentYear.value,
      period_type: periodType.value,
      start_month: startMonth.value,
      end_month: endMonth.value,
    });
    await advanceExportStep();
    if (blob) {
      exportStageText.value = '正在下载文件...';
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `财务分析报告_${currentYear.value}.pdf`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      await advanceExportStep();
      ElMessage.success('PDF导出成功');
    } else {
      ElMessage.error('PDF导出失败，请检查服务状态');
    }
  } catch (err) {
    console.error('exportPDF failed:', err);
    ElMessage.error('PDF导出失败');
  } finally {
    isExportingPDF.value = false;
    exportStageText.value = '';
    finishExportProgress();
  }
}

async function handleExportExcel() {
  if (!dashboardResponse.value) {
    ElMessage.warning('请先生成图表后再导出Excel');
    return;
  }
  isExportingExcel.value = true;
  startExportProgress('excel');
  try {
    exportStageText.value = '正在整理数据...';
    await advanceExportStep();
    const charts = dashboardResponse.value.charts.map(c => ({
      chartType: c.chartType,
      title: c.title,
      kpis: c.kpis,
      tableData: c.tableData,
    }));
    const analysisResults: Record<string, string> = { ...analysisByType.value };
    await advanceExportStep();
    exportStageText.value = '正在生成Excel文件...';
    const blob = await exportExcel({
      charts,
      analysis_results: analysisResults,
      company_name: '白垩纪科技',
      year: currentYear.value,
      period_type: periodType.value,
      start_month: startMonth.value,
      end_month: endMonth.value,
    });
    await advanceExportStep();
    if (blob) {
      exportStageText.value = '正在下载文件...';
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `财务分析数据_${currentYear.value}.xlsx`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      await advanceExportStep();
      ElMessage.success('Excel导出成功');
    } else {
      ElMessage.error('Excel导出失败，请检查服务状态');
    }
  } catch (err) {
    console.error('exportExcel failed:', err);
    ElMessage.error('Excel导出失败');
  } finally {
    isExportingExcel.value = false;
    exportStageText.value = '';
    finishExportProgress();
  }
}

// Get echartsOption from API response for direct pass-through rendering
function getChartEchartsOption(chart: ChartResult | null): Record<string, unknown> | null {
  if (!chart?.echartsOption || Object.keys(chart.echartsOption).length === 0) return null;
  return chart.echartsOption;
}

// Render a generic chart with echartsOption from API
function renderGenericChart(chartType: string, domEl: Element | null, retries = 0) {
  if (!domEl || !(domEl instanceof HTMLElement)) return;
  const chart = getChart(chartType);
  if (!chart?.echartsOption) return;

  // Skip if container has zero dimensions (not yet visible), retry up to 3 times
  if (domEl.clientWidth === 0 || domEl.clientHeight === 0) {
    if (retries < 3) {
      requestAnimationFrame(() => renderGenericChart(chartType, domEl, retries + 1));
    }
    return;
  }

  let instance = echarts.getInstanceByDom(domEl);
  if (!instance) {
    // D4: SVG renderer option
    const renderer = useSvgRenderer.value ? 'svg' : 'canvas';
    instance = echarts.init(domEl, isDarkMode.value ? 'cretas-dark' : undefined, { renderer });
  }
  const processed = processEChartsOptions(safeClone(chart.echartsOption) as Record<string, unknown>);

  // D2: Merge user annotations as ECharts graphic elements
  const chartAnnotations = annotations.value[chartType] || [];
  if (chartAnnotations.length > 0) {
    const graphicItems = chartAnnotations.flatMap((ann) => [
      // Pin icon background
      {
        type: 'circle' as const,
        shape: { r: 4, cx: 0, cy: 0 },
        left: `${ann.x}%`,
        top: `${ann.y}%`,
        style: { fill: ann.color || '#e6a23c', stroke: '#fff', lineWidth: 1.5 },
        silent: true,
        z: 100,
      },
      // Annotation text label
      {
        type: 'text' as const,
        left: `${ann.x + 1}%`,
        top: `${ann.y - 1}%`,
        style: {
          text: ann.text,
          fontSize: 11,
          fill: ann.color || '#e6a23c',
          fontWeight: 'bold' as const,
          backgroundColor: 'rgba(255,255,255,0.92)',
          padding: [3, 8],
          borderRadius: 4,
          borderColor: ann.color || '#e6a23c',
          borderWidth: 1,
          shadowColor: 'rgba(0,0,0,0.1)',
          shadowBlur: 4,
        },
        silent: true,
        z: 100,
      },
    ]);
    const existing = (processed.graphic as unknown[]) || [];
    processed.graphic = [...(Array.isArray(existing) ? existing : [existing]), ...graphicItems];
  }

  try {
    instance.setOption(processed, { notMerge: true });
  } catch {
    // ECharts dataZoom may throw 'grid.master' TypeError during initial render
    // when grid model isn't fully initialized — retry once after a frame
    requestAnimationFrame(() => {
      try { instance.setOption(processed, { notMerge: true }); } catch { /* ignore */ }
    });
  }

  // B1: Wire cross-chart click handler
  instance.off('click');
  instance.on('click', (params: Record<string, unknown>) => {
    handleChartClick(chartType, params as { name?: string; seriesName?: string; componentType?: string });
  });

  // D2: Double-click to add annotation (opens dialog)
  instance.off('dblclick');
  instance.on('dblclick', (params: Record<string, unknown>) => {
    const event = params.event as { offsetX?: number; offsetY?: number } | undefined;
    if (!event?.offsetX) return;
    const xPct = Math.round((event.offsetX / domEl.clientWidth) * 100);
    const yPct = Math.round((event.offsetY / domEl.clientHeight) * 100);
    openAnnotationDialog(chartType, xPct, yPct);
  });
}

watch(() => charts.value.length, async () => {
  await nextTick();
  setupLazyObserver();
  // Double rAF: first rAF schedules after v-if DOM insertion, second after layout
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      // Observe all chart card containers for lazy rendering
      const cardEls = document.querySelectorAll<HTMLElement>('.chart-card[data-chart-type]');
      for (const cardEl of cardEls) {
        const chartType = cardEl.dataset.chartType;
        if (chartType) observeChartCard(cardEl, chartType);
      }
      // Render all charts that are visible or have DOM refs ready
      for (const [chartType, domEl] of chartDomRefs.value.entries()) {
        if (visibleCharts.has(chartType)) {
          renderGenericChart(chartType, domEl);
        }
      }
      // Fallback: render any charts whose cards are in DOM but observer hasn't fired yet
      setTimeout(() => {
        for (const [chartType, domEl] of chartDomRefs.value.entries()) {
          if (!visibleCharts.has(chartType) && domEl.clientHeight > 0) {
            visibleCharts.add(chartType);
            renderGenericChart(chartType, domEl);
          }
        }
      }, 500);
      // E1: Connect charts for synchronized crosshair
      connectCharts();
      // E2: Start KPI counter animations
      startKpiAnimations();
    });
  });
});

// Note: cleanup is handled in the onBeforeUnmount at bottom of script

const periodLabel = computed(() => dashboardResponse.value?.period?.label || '');

// Bookmark current dashboard state
const currentDashboardState = computed<BookmarkState>(() => ({
  uploadId: uploadId.value ?? undefined,
  periodType: periodType.value,
  year: currentYear.value,
  startMonth: startMonth.value,
  endMonth: endMonth.value,
  chartTypes: charts.value.map((c) => c.chartType),
  filters: {},
}));

// Small multiples config
const smallMultiplesConfig = computed<SmallMultiplesConfig>(() => ({
  splitBy: smallMultiplesDimension.value,
  chartType: 'bar',
  sharedAxes: true,
  maxCells: 12,
}));

// Chart data reshaped for SmallMultiplesChart (flatten all chart KPIs into flat records)
const chartDataForSmallMultiples = computed<Record<string, unknown>[]>(() => {
  const rows: Record<string, unknown>[] = [];
  for (const chart of charts.value) {
    if (chart.kpis?.length) {
      for (const kpi of chart.kpis) {
        rows.push({
          category: chart.chartType,
          month: kpi.label,
          actual: typeof kpi.value === 'number' ? kpi.value : 0,
        });
      }
    }
  }
  return rows;
});

// Bookmark apply handler
function applyBookmark(state: BookmarkState) {
  if (state.uploadId) {
    uploadId.value = state.uploadId;
    uploadIdInput.value = String(state.uploadId);
  }
  showBookmarkPanel.value = false;
  generate();
}

// Conditional formatting change handler
function onFormattingRulesChange(_rules: unknown[]) {
  // Rules are managed by the ConditionalFormattingService singleton; no extra action needed
}

// ---- Tab navigation ----
function toggleGroup(groupName: string) {
  if (collapsedGroups.has(groupName)) collapsedGroups.delete(groupName);
  else collapsedGroups.add(groupName);
}

async function generateConclusions() {
  if (isComponentUnmounted) return;
  conclusionsLoading.value = true;
  try {
    const allAnalysis = Object.entries(analysisByType.value)
      .filter(([, text]) => text)
      .map(([type, text]) => {
        const ct = chartTypes.find(c => c.key === type);
        return `【${ct?.label || type}】\n${text}`;
      })
      .join('\n\n');

    if (!allAnalysis) {
      conclusionsText.value = '请先等待各图表的AI分析完成，然后再生成总结。';
      return;
    }

    const resp = await analyzeChart({
      chart_type: 'overall_summary',
      analysis_context: allAnalysis,
    });
    if (isComponentUnmounted) return;
    if (resp.success) {
      conclusionsText.value = resp.analysis || '暂无分析结论';
    } else {
      conclusionsText.value = resp.error || '分析暂不可用';
    }
  } catch (err) {
    if (isComponentUnmounted) return;
    const name = err instanceof Error ? err.name : '';
    if (name === 'AbortError' || (err instanceof TypeError && String(err).includes('fetch'))) return;
    console.error('generateConclusions failed:', err);
    conclusionsText.value = '生成结论失败，请重试';
  } finally {
    if (!isComponentUnmounted) conclusionsLoading.value = false;
  }
}

async function submitFollowUp(chartType: string) {
  const question = followUpInput.value.trim();
  if (!question) return;

  const chart = getChart(chartType);
  if (!chart) return;

  // Build previous analysis = initial analysis + all follow-up answers
  let fullAnalysis = analysisByType.value[chartType] || '';
  const history = followUpsByType.value[chartType] || [];
  for (const item of history) {
    fullAnalysis += `\n\n用户追问: ${item.question}\n回复: ${item.answer}`;
  }

  if (isComponentUnmounted) return;
  followUpLoading.value = true;
  followUpInput.value = '';

  try {
    const resp = await analyzeChart({
      chart_type: chartType,
      analysis_context: chart.analysisContext || '',
      follow_up_question: question,
      previous_analysis: fullAnalysis,
    });
    if (isComponentUnmounted) return;

    if (!followUpsByType.value[chartType]) followUpsByType.value[chartType] = [];
    followUpsByType.value[chartType].push({
      question,
      answer: resp.success ? (resp.analysis || '暂无回复') : (resp.error || '请求失败'),
    });
  } catch (err) {
    if (isComponentUnmounted) return;
    const name = err instanceof Error ? err.name : '';
    if (name === 'AbortError' || (err instanceof TypeError && String(err).includes('fetch'))) return;
    console.error('followUp failed:', err);
    if (!followUpsByType.value[chartType]) followUpsByType.value[chartType] = [];
    followUpsByType.value[chartType].push({ question, answer: '请求失败，请重试' });
  } finally {
    if (!isComponentUnmounted) followUpLoading.value = false;
  }
}

watch(activeTab, async (newTab) => {
  if (newTab === 'conclusions' || viewMode.value !== 'tab') return;
  // Auto-trigger AI analysis for the active chart
  requestAnalysis(newTab);
  await nextTick();
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      const domEl = chartDomRefs.value.get(newTab);
      if (domEl) renderGenericChart(newTab, domEl);
    });
  });
});

// ---- B3: Dark mode toggle ----
function toggleDarkMode() {
  isDarkMode.value = !isDarkMode.value;
  const theme = isDarkMode.value ? 'cretas-dark' : undefined;
  // Re-render all generic charts with theme change
  nextTick(() => {
    for (const [chartType, domEl] of chartDomRefs.value.entries()) {
      const oldInstance = echarts.getInstanceByDom(domEl);
      if (oldInstance) oldInstance.dispose();
      const newInstance = echarts.init(domEl, theme);
      const chart = getChart(chartType);
      if (chart?.echartsOption) {
        const processed = processEChartsOptions(safeClone(chart.echartsOption) as Record<string, unknown>);
        newInstance.setOption(processed, { notMerge: true });
      }
    }
    // Re-render custom component charts (Sankey)
    for (const compRef of [chartRefSankey]) {
      const inst = compRef.value?.chartInstance;
      if (inst) {
        const domEl = inst.getDom();
        inst.dispose();
        if (domEl) {
          const newInst = echarts.init(domEl, theme);
          const chartType = 'cost_flow_sankey';
          const chart = getChart(chartType);
          if (chart?.echartsOption) {
            const processed = processEChartsOptions(safeClone(chart.echartsOption) as Record<string, unknown>);
            newInst.setOption(processed, { notMerge: true });
          }
        }
      }
    }
  });
}

// ---- B2: Simple drill-down for financial dashboard ----
const drillDownVisible = ref(false);
const drillDownContext = ref({ chartType: '', dimension: '', value: '' });
const drillBreadcrumb = ref<Array<{ dimension: string; value: string }>>([]);

function handleChartDrillDown(chartType: string, params: { name?: string; seriesName?: string }) {
  if (!params.name) return;
  // For financial charts, "drill down" means filtering the period to a specific month/quarter
  const monthMatch = params.name.match(/^(\d{1,2})月$/);
  if (monthMatch) {
    const month = parseInt(monthMatch[1], 10);
    drillDownContext.value = { chartType, dimension: '月份', value: params.name };
    drillBreadcrumb.value.push({ dimension: '月份', value: params.name });
    // Re-generate with narrowed period
    const savedStart = startMonth.value;
    const savedEnd = endMonth.value;
    periodSelection.value = {
      ...periodSelection.value,
      type: 'month_range',
      value: [`${currentYear.value}-${String(month).padStart(2, '0')}`, `${currentYear.value}-${String(month).padStart(2, '0')}`],
    };
    ElMessage.info(`下钻至 ${params.name}`);
    generate();
    drillDownVisible.value = true;
    return;
  }
  // For category names, use cross-filter instead
  handleChartClick(chartType, params);
}

function drillUp() {
  drillBreadcrumb.value.pop();
  if (drillBreadcrumb.value.length === 0) {
    // Restore full year view
    periodSelection.value = {
      ...periodSelection.value,
      type: 'year',
      value: String(currentYear.value),
    };
    drillDownVisible.value = false;
    generate();
  }
}

// ---- B1: Cross-chart filtering ----
function handleChartClick(chartType: string, params: { name?: string; seriesName?: string; componentType?: string }) {
  if (!params.name || params.componentType === 'markLine' || params.componentType === 'markArea') return;

  // Toggle filter: click same → clear, click different → set
  if (activeFilter.value?.value === params.name && activeFilter.value?.dimension === chartType) {
    activeFilter.value = null;
  } else {
    activeFilter.value = { dimension: chartType, value: params.name };
  }
  applyCrossFilter();
}

function applyCrossFilter() {
  for (const [chartType, domEl] of chartDomRefs.value.entries()) {
    const instance = echarts.getInstanceByDom(domEl);
    if (!instance) continue;

    const chart = getChart(chartType);
    if (!chart?.echartsOption) continue;

    if (!activeFilter.value) {
      // Clear filter — re-render original chart
      const processed = processEChartsOptions(safeClone(chart.echartsOption) as Record<string, unknown>);
      instance.setOption(processed, { notMerge: true });
      continue;
    }

    // Find matching data index in xAxis
    const filterVal = activeFilter.value.value;
    const rawOpt = chart.echartsOption as Record<string, unknown>;
    const xData = rawOpt.xAxis;
    const xAxisData = Array.isArray(xData) ? (xData[0] as Record<string, unknown>)?.data : (xData as Record<string, unknown>)?.data;

    if (Array.isArray(xAxisData)) {
      const matchIdx = xAxisData.indexOf(filterVal);
      // Apply opacity dimming via series-level emphasis/downplay
      instance.dispatchAction({ type: 'downplay' });
      if (matchIdx >= 0) {
        // Highlight matched index across all series
        const seriesArr = rawOpt.series;
        if (Array.isArray(seriesArr)) {
          for (let si = 0; si < seriesArr.length; si++) {
            instance.dispatchAction({ type: 'highlight', seriesIndex: si, dataIndex: matchIdx });
          }
        } else {
          instance.dispatchAction({ type: 'highlight', dataIndex: matchIdx });
        }
      }
    }
  }
}

// ---- D1: Auto-refresh ----
function setAutoRefresh(interval: number) {
  autoRefreshInterval.value = interval;
  if (autoRefreshTimer) {
    clearInterval(autoRefreshTimer);
    autoRefreshTimer = null;
  }
  if (interval > 0 && dashboardResponse.value) {
    autoRefreshTimer = setInterval(() => {
      if (!isGenerating.value) generate();
    }, interval);
  }
}

watch(() => autoRefreshInterval.value, (val) => setAutoRefresh(val));

// ---- D3: Anomaly detection (2σ) ----
function detectAnomalies(kpis: Array<{ label: string; value: string | number; unit?: string; trend?: string }>): string[] {
  const numericValues = kpis
    .map(k => typeof k.value === 'number' ? k.value : parseFloat(String(k.value)))
    .filter(v => !isNaN(v));
  if (numericValues.length < 3) return [];

  const mean = numericValues.reduce((a, b) => a + b, 0) / numericValues.length;
  const std = Math.sqrt(numericValues.reduce((a, b) => a + Math.pow(b - mean, 2), 0) / numericValues.length);
  if (std === 0) return [];

  const alerts: string[] = [];
  for (const kpi of kpis) {
    const v = typeof kpi.value === 'number' ? kpi.value : parseFloat(String(kpi.value));
    if (!isNaN(v) && Math.abs(v - mean) > 2 * std) {
      alerts.push(`${kpi.label}: ${kpi.value}${kpi.unit || ''} (偏差显著)`);
    }
  }
  return alerts;
}

// ---- C4: Keyboard navigation between chart cards ----
function handleCardKeydown(event: KeyboardEvent, cardIndex: number) {
  const cards = document.querySelectorAll('.chart-card[tabindex]');
  let nextIdx = -1;
  if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
    nextIdx = Math.min(cardIndex + 1, cards.length - 1);
  } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
    nextIdx = Math.max(cardIndex - 1, 0);
  } else {
    return;
  }
  event.preventDefault();
  (cards[nextIdx] as HTMLElement)?.focus();
}

// ---- E1: Synchronized crosshair (tooltip linkage) ----
// Time-series chart types that share the same x-axis (months)
const timeSeriesChartTypes = [
  'budget_achievement', 'yoy_mom_comparison',
  'category_yoy_comparison', 'cashflow_trend',
  'hr_cost_analysis',
];

function connectCharts() {
  // Wire up mousemove → showTip on sibling charts (throttled to avoid per-frame dispatch to all charts)
  for (const chartType of timeSeriesChartTypes) {
    const domEl = chartDomRefs.value.get(chartType);
    if (!domEl) continue;
    const instance = echarts.getInstanceByDom(domEl);
    if (!instance) continue;

    let lastDispatchedIndex = -1;
    let throttleTimer: ReturnType<typeof setTimeout> | null = null;

    instance.on('mousemove', (params: Record<string, unknown>) => {
      const dataIndex = params.dataIndex as number | undefined;
      if (dataIndex == null || dataIndex === lastDispatchedIndex) return;
      lastDispatchedIndex = dataIndex;
      if (throttleTimer) return; // Skip if throttle pending
      throttleTimer = setTimeout(() => {
        throttleTimer = null;
        for (const sibType of timeSeriesChartTypes) {
          if (sibType === chartType) continue;
          const sibDom = chartDomRefs.value.get(sibType);
          if (!sibDom) continue;
          const sibInst = echarts.getInstanceByDom(sibDom);
          if (!sibInst) continue;
          try { sibInst.dispatchAction({ type: 'showTip', seriesIndex: 0, dataIndex: lastDispatchedIndex }); } catch { /* grid not ready */ }
        }
      }, 32); // ~30fps throttle
    });
    instance.on('mouseout', () => {
      lastDispatchedIndex = -1;
      if (throttleTimer) { clearTimeout(throttleTimer); throttleTimer = null; }
      for (const sibType of timeSeriesChartTypes) {
        if (sibType === chartType) continue;
        const sibDom = chartDomRefs.value.get(sibType);
        if (!sibDom) continue;
        const sibInst = echarts.getInstanceByDom(sibDom);
        try { sibInst?.dispatchAction({ type: 'hideTip' }); } catch { /* grid not ready */ }
      }
    });
  }
}

// ---- E2: Animated KPI counter ----
const kpiRafIds: number[] = [];
const animatedKpiValues = ref<Record<string, string>>({});

function animateKpiValue(key: string, targetStr: string, duration = 800) {
  // Parse numeric portion
  const match = targetStr.match(/^([+-]?)([0-9,.]+)(.*)/);
  if (!match) {
    animatedKpiValues.value[key] = targetStr;
    return;
  }
  const sign = match[1];
  const numStr = match[2].replace(/,/g, '');
  const suffix = match[3];
  const target = parseFloat(numStr);
  if (isNaN(target)) {
    animatedKpiValues.value[key] = targetStr;
    return;
  }

  const decimals = numStr.includes('.') ? numStr.split('.')[1].length : 0;
  const startTime = performance.now();
  animatedKpiValues.value[key] = `${sign}0${suffix}`;

  function tick(now: number) {
    const elapsed = now - startTime;
    const progress = Math.min(elapsed / duration, 1);
    // Ease out cubic
    const eased = 1 - Math.pow(1 - progress, 3);
    const current = target * eased;
    const formatted = decimals > 0 ? current.toFixed(decimals) : Math.round(current).toLocaleString('zh-CN');
    animatedKpiValues.value[key] = `${sign}${formatted}${suffix}`;
    if (progress < 1) {
      kpiRafIds.push(requestAnimationFrame(tick));
    }
  }
  kpiRafIds.push(requestAnimationFrame(tick));
}

function startKpiAnimations() {
  for (const chart of charts.value) {
    if (!chart.kpis?.length) continue;
    for (const kpi of chart.kpis) {
      const key = `${chart.chartType}_${kpi.label}`;
      const val = typeof kpi.value === 'number' ? kpi.value.toFixed(1) : String(kpi.value);
      animateKpiValue(key, val);
    }
  }
}

function getAnimatedKpi(chartType: string, label: string, original: string | number): string {
  const key = `${chartType}_${label}`;
  return animatedKpiValues.value[key] ?? (typeof original === 'number' ? original.toFixed(1) : String(original));
}

// ---- Fix 64: Spotlight / Focus mode (Power BI) ----
const spotlightChart = ref<string | null>(null);

function enterSpotlight(chartType: string) {
  spotlightChart.value = chartType;
  // After overlay mounts, render chart in the spotlight container
  nextTick(() => {
    nextTick(() => {
      const domEl = chartDomRefs.value.get(chartType);
      if (domEl) renderGenericChart(chartType, domEl);
      // Auto-focus overlay so Escape key works
      const overlay = document.querySelector('.spotlight-overlay') as HTMLElement;
      if (overlay) overlay.focus();
    });
  });
}

function exitSpotlight() {
  const ct = spotlightChart.value;
  spotlightChart.value = null;
  // Re-render chart back in original container after teleport unmounts
  if (ct) {
    nextTick(() => {
      nextTick(() => {
        const domEl = chartDomRefs.value.get(ct);
        if (domEl) renderGenericChart(ct, domEl);
      });
    });
  }
}

// Handle Escape key for spotlight
function handleSpotlightKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && spotlightChart.value) exitSpotlight();
}

// ---- E4: Data table drawer per chart ----
const tableVisibleByType = ref<Record<string, boolean>>({});

// ---- Resize all charts when container changes (sidebar toggle) ----
let resizeObserver: ResizeObserver | null = null;
let resizeRafId = 0;

function setupResizeObserver() {
  const container = document.querySelector('.financial-dashboard-pbi');
  if (!container || resizeObserver) return;
  resizeObserver = new ResizeObserver(() => {
    if (resizeRafId) return;
    resizeRafId = requestAnimationFrame(() => {
      resizeRafId = 0;
      for (const [, domEl] of chartDomRefs.value.entries()) {
        const inst = echarts.getInstanceByDom(domEl);
        inst?.resize();
      }
    });
  });
  resizeObserver.observe(container);
}

// Setup after first render
watch(() => charts.value.length, () => {
  nextTick(() => setupResizeObserver());
}, { once: true });

// 自动加载: 若已有数据源 (route query ?upload_id= 或 ?uploadId=), 进页即以「全部历史」
// 生成看板, 免手动点「生成看板」。无数据源时保留引导空态 (PBI 看板是 upload 绑定的, 没有
// upload_id 无法生成 — 餐饮租户默认落 gold「财务数据分析」子页, 见 financeDashboardSection.ts)。
onMounted(() => {
  const q = route.query;
  const rawId = q.upload_id ?? q.uploadId;
  const idStr = Array.isArray(rawId) ? rawId[0] : rawId;
  const id = idStr ? parseInt(String(idStr), 10) : NaN;
  if (!isNaN(id) && id > 0) {
    uploadId.value = id;
    uploadIdInput.value = String(id);
    // periodSelection 默认 allHistory=true → 全部历史
    nextTick(() => generate(false));
  }
});

// Cleanup on unmount
onBeforeUnmount(() => {
  // Bug #2: mark unmount so in-flight generate() stops touching reactive state,
  // avoiding "canceled" console noise when the user navigates away mid-request.
  isComponentUnmounted = true;
  if (autoRefreshTimer) clearInterval(autoRefreshTimer);
  if (resizeRafId) cancelAnimationFrame(resizeRafId);
  kpiRafIds.forEach((id) => cancelAnimationFrame(id));
  kpiRafIds.length = 0;
  resizeObserver?.disconnect();
  resizeObserver = null;
  teardownLazyObserver();
  for (const [, domEl] of chartDomRefs.value.entries()) {
    const instance = echarts.getInstanceByDom(domEl);
    instance?.dispose();
  }
  chartDomRefs.value.clear();
});
</script>

<template>
  <div class="financial-dashboard-pbi" :data-theme="isDarkMode ? 'dark' : 'light'" role="main" aria-label="财务分析看板">
    <!-- P4: 顶层 section tabs — 财务 PBI 看板 / 财务数据分析 (合并自原 /smart-bi/finance) -->
    <el-tabs v-model="sectionTab" class="finance-section-tabs">
    <el-tab-pane label="财务 PBI 看板" name="dashboard">
    <!-- follow-up (fu2): 餐饮租户 Gold 财务全量视图 (免上传 / 免手动生成).
         工厂租户 (或餐饮点「上传财务文件」) 走下方原 upload-based PBI 看板。 -->
    <FinancePbiGoldView
      v-if="goldFinanceMode"
      @switch-to-upload="showUploadForRestaurant = true"
    />

    <template v-else>
    <!-- Top Toolbar -->
    <el-card class="toolbar-card" shadow="never">
      <div class="toolbar-inner">
        <div class="toolbar-left">
          <el-input
            v-model="uploadIdInput"
            placeholder="数据源ID (可选)"
            style="width: 140px"
            size="small"
            clearable
            @change="(v: string) => { uploadId = v ? parseInt(v, 10) : null }"
          />
          <PeriodSelector
            v-model="periodSelection"
            :show-quick-select="true"
            :show-custom-tab="false"
            :show-all-history="true"
            default-type="year"
            size="small"
            style="margin-left: 8px"
          />
          <el-button
            type="primary"
            size="small"
            :loading="isGenerating"
            style="margin-left: 8px"
            @click="generate"
          >
            <el-icon v-if="!isGenerating"><DataAnalysis /></el-icon>
            {{ isGenerating ? '生成中...' : '生成看板' }}
          </el-button>
          <el-button
            size="small"
            :loading="isGenerating"
            style="margin-left: 4px"
            @click="generate(true)"
          >
            演示数据
          </el-button>
        </div>
        <div class="toolbar-right">
          <el-tag v-if="periodLabel" type="info" size="small" style="margin-right: 8px">
            {{ periodLabel }}
          </el-tag>
          <el-segmented
            v-model="viewMode"
            :options="[{ label: 'Tab', value: 'tab' }, { label: 'Grid', value: 'grid' }]"
            size="small"
          />
          <el-button size="small" @click="toggleDarkMode" :title="isDarkMode ? '切换亮色' : '切换暗色'">
 {{ isDarkMode ? '️' : '' }}
          </el-button>
          <el-select
            v-model="autoRefreshInterval"
            size="small"
            placeholder="自动刷新"
            style="width: 100px"
            :disabled="!dashboardResponse"
          >
            <el-option :value="0" label="刷新: 关" />
            <el-option :value="60000" label="1分钟" />
            <el-option :value="300000" label="5分钟" />
            <el-option :value="900000" label="15分钟" />
          </el-select>
          <el-switch
            v-model="smallMultiplesVisible"
            size="small"
            active-text="维度拆分"
            style="margin-right: 4px"
          />
          <el-popover trigger="click" width="520" placement="bottom-end">
            <template #reference>
              <el-button size="small" :disabled="!dashboardResponse">
                <el-icon><SetUp /></el-icon>
                条件格式
              </el-button>
            </template>
            <ConditionalFormatPanel
              table-id="financial-dashboard"
              :available-fields="['value', 'budget', 'actual', 'variance']"
              @change="onFormattingRulesChange"
            />
          </el-popover>
          <el-button
            size="small"
            :disabled="!dashboardResponse"
            @click="showBookmarkPanel = true"
          >
            <el-icon><Collection /></el-icon>
            书签
          </el-button>
          <el-button
            size="small"
            :disabled="!dashboardResponse"
            @click="isPresentationVisible = true"
          >
            <el-icon><VideoPlay /></el-icon>
            演示模式
          </el-button>
          <el-dropdown :disabled="!dashboardResponse" trigger="click" @command="handleExportCommand">
            <el-button size="small" :disabled="!dashboardResponse" :loading="isExportingPPT || isExportingPDF || isExportingExcel">
              <el-icon><Download /></el-icon>
              {{ exportStageText || '导出报告' }} <el-icon v-if="!exportStageText" class="el-icon--right"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="ppt" :disabled="isExportingPPT">
 导出PPT演示文稿
                </el-dropdown-item>
                <el-dropdown-item command="pdf" :disabled="isExportingPDF">
 导出PDF报告
                </el-dropdown-item>
                <el-dropdown-item command="excel" :disabled="isExportingExcel">
 导出Excel数据
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </el-card>

    <!-- Interaction tips -->
    <div v-if="dashboardResponse && !isGenerating" class="interaction-tips">
 <span> <b>单击</b>交叉过滤 | <b>双击</b>添加标注 | <b>方向键</b>卡片导航 | <b>Shift+滚轮</b>缩放 | 图表间<b>Tooltip联动</b></span>
    </div>

    <!-- Fix 72: Global Slicer / Filter bar (Power BI style) -->
    <div v-if="availableDimensions.length > 0 && dashboardResponse && !isGenerating" class="slicer-bar">
      <span class="slicer-label">筛选器</span>
      <div v-for="dim in availableDimensions" :key="dim.name" class="slicer-group">
        <span class="slicer-dim-name">{{ dim.name }}:</span>
        <el-check-tag
          v-for="val in dim.values"
          :key="val"
          :checked="slicerFilters[dim.name]?.includes(val)"
          @change="(checked: boolean) => {
            if (!slicerFilters[dim.name]) slicerFilters[dim.name] = [];
            if (checked) { slicerFilters[dim.name].push(val); }
            else { slicerFilters[dim.name] = slicerFilters[dim.name].filter((v: string) => v !== val); }
            generate();
          }"
        >
          {{ val }}
        </el-check-tag>
      </div>
      <el-button
        v-if="Object.values(slicerFilters).some((v: string[]) => v.length > 0)"
        size="small"
        text
        type="primary"
        @click="slicerFilters = {}; generate()"
      >
        清除全部
      </el-button>
    </div>

    <!-- B2: Drill-down breadcrumb -->
    <div v-if="drillDownVisible" class="cross-filter-bar" style="background: rgba(54,179,126,0.08); color: #1B7A4A">
      <div style="display: flex; align-items: center; gap: 4px">
        <span style="cursor: pointer; text-decoration: underline" @click="drillUp">全年</span>
        <span v-for="(bc, idx) in drillBreadcrumb" :key="idx"> &gt; <b>{{ bc.value }}</b></span>
      </div>
      <el-button size="small" text type="success" @click="drillUp">返回上级</el-button>
    </div>

    <!-- B1: Active cross-filter indicator -->
    <div v-if="activeFilter" class="cross-filter-bar">
      <span>过滤中: <b>{{ activeFilter.value }}</b></span>
      <el-button size="small" text type="primary" @click="activeFilter = null; applyCrossFilter()">清除过滤</el-button>
    </div>

    <!-- Empty state -->
    <div v-if="!dashboardResponse && !isGenerating" class="empty-state">
      <SmartBIEmptyState type="no-data" title="请选择数据源" description="选好数据源后点击「生成看板」(默认「全部历史」, 展示该数据源全部数据)" :show-action="false" />
    </div>

    <!-- Loading skeleton (grid mode) -->
    <div v-if="isGenerating && viewMode === 'grid'" class="charts-grid">
      <el-card v-for="ct in chartTypes" :key="ct.key" class="chart-card chart-card--visible" shadow="hover">
        <template #header>
          <div class="chart-card-header">
            <span>{{ ct.icon }} {{ ct.label }}</span>
          </div>
        </template>
        <ChartSkeleton type="kpi" />
        <ChartSkeleton type="chart" />
      </el-card>
    </div>

    <!-- Charts Grid (grid mode) -->
    <div v-if="dashboardResponse && !isGenerating && viewMode === 'grid'" class="charts-grid">

      <!-- Chart: Cost Flow Sankey -->
      <el-card
        v-if="getChart('cost_flow_sankey')"
        :ref="(el: { $el?: HTMLElement } | null) => { if (el?.$el) observeChartCard(el.$el, 'cost_flow_sankey') }"
        class="chart-card chart-card--wide"
        :class="{ 'chart-card--visible': visibleCharts.has('cost_flow_sankey') }"
        data-chart-type="cost_flow_sankey"
        shadow="hover"
      >
        <template #header>
          <div class="chart-card-header">
 <span> 成本流向图</span>
            <el-button
              size="small"
              text
              type="primary"
              :loading="analysisLoadingByType['cost_flow_sankey']"
              @click="forceRequestAnalysis('cost_flow_sankey')"
            >
              {{ analysisByType['cost_flow_sankey'] ? '重新分析' : 'AI分析' }}
            </el-button>
          </div>
        </template>
        <SankeyChart
          ref="chartRefSankey"
          :nodes="[]"
          :links="[]"
          :echarts-option="getChart('cost_flow_sankey')!.echartsOption ?? {}"
          height="460px"
        />
        <div v-if="analysisLoadingByType['cost_flow_sankey'] || analysisByType['cost_flow_sankey']" class="analysis-panel">
          <ChartSkeleton v-if="analysisLoadingByType['cost_flow_sankey']" type="ai" />
          <div v-else class="analysis-text">
            {{ analysisByType['cost_flow_sankey'] }}
          </div>
        </div>
      </el-card>

      <!-- Generic Charts rendered via echartsOption from API -->
      <template v-for="ct in chartTypes.filter(c => c.key !== 'cost_flow_sankey')" :key="ct.key">
      <el-card
        v-if="getChart(ct.key)"
        :ref="(el: { $el?: HTMLElement } | null) => { if (el?.$el) observeChartCard(el.$el, ct.key) }"
        class="chart-card"
        :class="{ 'chart-card--wide chart-card--scorecard': ct.key === 'kpi_scorecard', 'chart-card--visible': visibleCharts.has(ct.key) }"
        :data-chart-type="ct.key"
        shadow="hover"
        role="region"
        :aria-label="ct.label"
        tabindex="0"
        @keydown="(e: KeyboardEvent) => handleCardKeydown(e, chartTypes.filter(c => !['expense_yoy_budget', 'gross_margin_trend'].includes(c.key)).indexOf(ct))"
      >
        <template #header>
          <div class="chart-card-header">
            <div class="chart-card-title-group">
              <span>{{ ct.icon }} {{ ct.label }}</span>
              <el-tag
                v-if="getAnomalies(ct.key).length > 0"
                type="warning"
                size="small"
                effect="dark"
                class="anomaly-badge"
                :title="getAnomalies(ct.key).join('\n')"
              >
 {{ getAnomalies(ct.key).length }}
              </el-tag>
            </div>
            <div style="display: flex; align-items: center; gap: 4px">
              <!-- Fix 64: Spotlight button -->
              <el-button
                size="small"
                text
                title="聚焦放大"
                @click="enterSpotlight(ct.key)"
              >
 
              </el-button>
              <el-button
                v-if="annotations[ct.key]?.length"
                size="small"
                text
                type="warning"
                :title="`${annotations[ct.key].length} 个标注 (双击图表添加)`"
                @click="clearAllAnnotations(ct.key)"
              >
                <el-icon><Delete /></el-icon>
                {{ annotations[ct.key].length }}
              </el-button>
              <el-button
                v-if="getChart(ct.key)?.tableData"
                size="small"
                text
                type="info"
                @click="tableVisibleByType[ct.key] = !tableVisibleByType[ct.key]"
              >
                {{ tableVisibleByType[ct.key] ? '隐藏表格' : '数据表' }}
              </el-button>
              <el-button
                size="small"
                text
                type="primary"
                :loading="analysisLoadingByType[ct.key]"
                :disabled="!getChart(ct.key)"
                @click="forceRequestAnalysis(ct.key)"
              >
                {{ analysisByType[ct.key] ? '重新分析' : 'AI分析' }}
              </el-button>
            </div>
          </div>
        </template>

        <!-- KPI row from API response -->
        <div v-if="getChart(ct.key)!.kpis?.length" class="generic-kpi-row">
          <div
            v-for="kpi in getChart(ct.key)!.kpis.slice(0, 4)"
            :key="kpi.label"
            class="generic-kpi-chip"
            :class="{
              'kpi-up': kpi.trend === 'up',
              'kpi-down': kpi.trend === 'down',
            }"
            tabindex="0"
            :aria-label="`${kpi.label}: ${kpi.value}${kpi.unit || ''}`"
          >
            <span class="kpi-val">{{ getAnimatedKpi(ct.key, kpi.label, kpi.value) }}{{ kpi.unit }}</span>
            <span class="kpi-lbl">{{ kpi.label }}</span>
            <!-- A3: KPI sparkline -->
            <svg v-if="kpi.sparkline?.length >= 2" class="kpi-sparkline" viewBox="0 0 60 20" preserveAspectRatio="none">
              <path :d="sparklinePath(kpi.sparkline)" fill="none" :stroke="kpi.trend === 'down' ? '#FF5630' : '#36B37E'" stroke-width="1.5" />
            </svg>
          </div>
        </div>

        <!-- Quarterly progress bars (budget_achievement) -->
        <div
          v-if="ct.key === 'budget_achievement' && getChart(ct.key)?.quarterlyProgress?.length"
          class="quarterly-progress-row"
        >
          <div
            v-for="qp in getChart(ct.key)!.quarterlyProgress"
            :key="qp.quarter"
            class="q-progress-item"
          >
            <span class="q-label">{{ qp.quarter }}</span>
            <el-progress
              :percentage="Math.min(qp.rate, 100)"
              :color="qp.rate >= 100 ? '#36B37E' : qp.rate >= 80 ? '#FFAB00' : '#FF5630'"
              :stroke-width="10"
              :text-inside="true"
            />
            <span class="q-rate" :style="{ color: qp.rate >= 100 ? '#36B37E' : qp.rate >= 80 ? '#FFAB00' : '#FF5630' }">{{ qp.rate }}%</span>
          </div>
        </div>

        <!-- ECharts canvas rendered via DOM ref -->
        <div
          :ref="(el) => setChartDomRef(ct.key, el as Element | null)"
          class="generic-chart-canvas"
          role="img"
          :aria-label="`${ct.label}图表`"
          title="单击: 交叉过滤 | 双击: 添加标注"
        />

        <!-- Phase B Moderate: ChartInsightProvider for bar/line/pie slots (exotic slots render nothing) -->
        <!-- Wired: budget_achievement(comparison) yoy_mom_comparison(comparison) category_yoy_comparison(comparison)
                   category_structure_donut(pie) channel_analysis(bar/ranking) cashflow_trend(line/trend) hr_cost_analysis(comparison)
             Skipped exotic: pnl_waterfall(WATERFALL) cash_flow_waterfall(WATERFALL) bullet_chart(gauge-like) small_multiples(exotic) -->
        <ChartInsightProvider
          v-if="buildFinanceChartWithMeta(ct.key)"
          :chart="buildFinanceChartWithMeta(ct.key)"
          :perms="insightPerms"
          :factory-id="authStore.factoryId || ''"
          depth="detailed"
        />

        <!-- Monthly data rows (yoy_mom_comparison) -->
        <div
          v-if="ct.key === 'yoy_mom_comparison' && getChart(ct.key)?.monthlyDataRows"
          class="monthly-data-rows"
        >
          <div class="data-row">
            <span class="data-row-label">本年</span>
            <span
              v-for="(v, idx) in getChart(ct.key)!.monthlyDataRows.currentYear"
              :key="'cy-' + idx"
              class="data-row-cell"
            >{{ v }}</span>
          </div>
          <div class="data-row">
            <span class="data-row-label">上年</span>
            <span
              v-for="(v, idx) in getChart(ct.key)!.monthlyDataRows.lastYear"
              :key="'ly-' + idx"
              class="data-row-cell"
            >{{ v }}</span>
          </div>
        </div>

        <!-- AI Analysis panel — always visible after auto-analysis -->
        <div v-if="analysisLoadingByType[ct.key] || analysisByType[ct.key]" class="analysis-panel">
          <ChartSkeleton v-if="analysisLoadingByType[ct.key]" type="ai" />
          <div v-else class="analysis-text">
            {{ analysisByType[ct.key] }}
          </div>
        </div>

        <!-- E4: Data table drawer (Fix 71: with inline sparklines) -->
        <div v-if="tableVisibleByType[ct.key] && getChart(ct.key)?.tableData" class="chart-data-table">
          <table>
            <thead>
              <tr>
                <th v-for="col in (getChart(ct.key)!.tableData as Record<string, unknown>)?.headers as string[] ?? []" :key="col">{{ col }}</th>
                <th style="min-width:80px">趋势</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, rIdx) in (getChart(ct.key)!.tableData as Record<string, unknown>)?.rows as Record<string, unknown>[] ?? []" :key="rIdx">
                <td>{{ (row as Record<string, unknown>).label }}</td>
                <td v-for="(val, vIdx) in ((row as Record<string, unknown>).values as unknown[] ?? [])" :key="vIdx">{{ val }}</td>
                <!-- Fix 71: Inline sparkline for each row -->
                <td>
                  <span
                    v-if="Array.isArray((row as Record<string, unknown>).values)"
                    v-html="sparklineSVG(
                      ((row as Record<string, unknown>).values as unknown[]).map((c: unknown) => parseFloat(String(c)) || 0),
                      -1, 80, 22, '#2D8B57'
                    )"
                  />
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </el-card>
      </template>

    </div>

    <!-- Tab mode: Loading -->
    <div v-if="isGenerating && viewMode === 'tab'" class="dashboard-layout">
      <aside class="dash-sidebar">
        <div v-for="[groupName] in chartsByGroup" :key="groupName" class="nav-group">
          <div class="nav-group-title">{{ groupName }}</div>
        </div>
      </aside>
      <main class="dash-content">
        <el-card shadow="hover">
          <ChartSkeleton type="kpi" />
          <ChartSkeleton type="chart" />
        </el-card>
      </main>
    </div>

    <!-- Tab mode: Dashboard layout with sidebar -->
    <div v-if="dashboardResponse && !isGenerating && viewMode === 'tab'" class="dashboard-layout">
      <!-- Sidebar navigation -->
      <aside class="dash-sidebar">
        <div v-for="[groupName, items] in chartsByGroup" :key="groupName" class="nav-group">
          <div class="nav-group-title" @click="toggleGroup(groupName)">
            <span>{{ groupName }}</span>
            <el-icon :size="12"><component :is="collapsedGroups.has(groupName) ? ArrowRight : ArrowDown" /></el-icon>
          </div>
          <div v-show="!collapsedGroups.has(groupName)" class="nav-group-items">
            <div
              v-for="ct in items"
              :key="ct.key"
              class="nav-tab-item"
              :class="{ active: activeTab === ct.key, available: !!getChart(ct.key) }"
              @click="activeTab = ct.key"
            >
              <span class="nav-icon">{{ ct.icon }}</span>
              <span class="nav-label">{{ ct.label }}</span>
              <span class="nav-order">{{ String(ct.order).padStart(2, '0') }}</span>
            </div>
          </div>
        </div>
        <div class="nav-group">
          <div
            class="nav-tab-item"
            :class="{ active: activeTab === 'conclusions' }"
            @click="activeTab = 'conclusions'"
          >
 <span class="nav-icon"></span>
            <span class="nav-label">分析结论与建议</span>
            <span class="nav-order">19</span>
          </div>
        </div>
      </aside>

      <!-- Content area -->
      <main class="dash-content">
        <!-- Conclusions tab -->
        <template v-if="activeTab === 'conclusions'">
          <el-card shadow="hover">
            <template #header>
              <div class="chart-card-header">
 <span> 分析结论与建议</span>
                <el-button size="small" type="primary" :loading="conclusionsLoading" @click="generateConclusions">
                  {{ conclusionsText ? '重新生成' : '生成结论' }}
                </el-button>
              </div>
            </template>
            <ChartSkeleton v-if="conclusionsLoading" type="ai" />
            <div v-else-if="conclusionsText" class="analysis-text">{{ conclusionsText }}</div>
            <div v-else style="text-align: center; padding: 40px; color: #909399">
              <p>汇总所有图表的分析结果，生成整体结论与建议</p>
              <el-button type="primary" style="margin-top: 16px" @click="generateConclusions">生成分析结论</el-button>
            </div>
          </el-card>
        </template>

        <!-- Active chart tab -->
        <template v-else-if="getChart(activeTab)">
          <!-- KPI row -->
          <div v-if="getChart(activeTab)!.kpis?.length" class="tab-kpi-row">
            <div
              v-for="kpi in getChart(activeTab)!.kpis"
              :key="kpi.label"
              class="generic-kpi-chip"
              :class="{ 'kpi-up': kpi.trend === 'up', 'kpi-down': kpi.trend === 'down' }"
            >
              <span class="kpi-val">{{ getAnimatedKpi(activeTab, kpi.label, kpi.value) }}{{ kpi.unit }}</span>
              <span class="kpi-lbl">{{ kpi.label }}</span>
              <svg v-if="kpi.sparkline?.length >= 2" class="kpi-sparkline" viewBox="0 0 60 20" preserveAspectRatio="none">
                <path :d="sparklinePath(kpi.sparkline)" fill="none" :stroke="kpi.trend === 'down' ? '#FF5630' : '#36B37E'" stroke-width="1.5" />
              </svg>
            </div>
          </div>

          <!-- Quarterly progress bars (budget_achievement) -->
          <div
            v-if="activeTab === 'budget_achievement' && getChart(activeTab)?.quarterlyProgress?.length"
            class="quarterly-progress-row"
            style="margin-bottom: 16px"
          >
            <div v-for="qp in getChart(activeTab)!.quarterlyProgress" :key="qp.quarter" class="q-progress-item">
              <span class="q-label">{{ qp.quarter }}</span>
              <el-progress
                :percentage="Math.min(qp.rate, 100)"
                :color="qp.rate >= 100 ? '#36B37E' : qp.rate >= 80 ? '#FFAB00' : '#FF5630'"
                :stroke-width="10"
                :text-inside="true"
              />
              <span class="q-rate" :style="{ color: qp.rate >= 100 ? '#36B37E' : qp.rate >= 80 ? '#FFAB00' : '#FF5630' }">{{ qp.rate }}%</span>
            </div>
          </div>

          <!-- Chart -->
          <el-card shadow="hover" class="tab-chart-card">
            <template #header>
              <div class="chart-card-header">
                <div class="chart-card-title-group">
                  <span>{{ chartTypes.find(c => c.key === activeTab)?.icon }} {{ chartTypes.find(c => c.key === activeTab)?.label }}</span>
                  <el-tag
                    v-if="getAnomalies(activeTab).length > 0"
                    type="warning"
                    size="small"
                    effect="dark"
                    class="anomaly-badge"
                    :title="getAnomalies(activeTab).join('\n')"
                  >
 {{ getAnomalies(activeTab).length }}
                  </el-tag>
                </div>
                <div style="display: flex; align-items: center; gap: 4px">
 <el-button size="small" text title="聚焦放大" @click="enterSpotlight(activeTab)"></el-button>
                  <el-button
                    v-if="annotations[activeTab]?.length"
                    size="small"
                    text
                    type="warning"
                    :title="`${annotations[activeTab].length} 个标注`"
                    @click="clearAllAnnotations(activeTab)"
                  >
                    <el-icon><Delete /></el-icon> {{ annotations[activeTab].length }}
                  </el-button>
                  <el-button
                    v-if="getChart(activeTab)?.tableData"
                    size="small"
                    text
                    type="info"
                    @click="tableVisibleByType[activeTab] = !tableVisibleByType[activeTab]"
                  >
                    {{ tableVisibleByType[activeTab] ? '隐藏表格' : '数据表' }}
                  </el-button>
                  <el-button
                    size="small"
                    text
                    type="primary"
                    :loading="analysisLoadingByType[activeTab]"
                    @click="forceRequestAnalysis(activeTab)"
                  >
                    {{ analysisByType[activeTab] ? '重新分析' : 'AI分析' }}
                  </el-button>
                </div>
              </div>
            </template>
            <div
              :ref="(el) => setChartDomRef(activeTab, el as Element | null)"
              class="generic-chart-canvas"
              style="height: 480px"
              role="img"
              :aria-label="`${chartTypes.find(c => c.key === activeTab)?.label}图表`"
              title="单击: 交叉过滤 | 双击: 添加标注"
            />
          </el-card>

          <!-- Monthly data rows (yoy_mom_comparison) -->
          <div
            v-if="activeTab === 'yoy_mom_comparison' && getChart(activeTab)?.monthlyDataRows"
            class="monthly-data-rows"
            style="margin-top: 16px"
          >
            <div class="data-row">
              <span class="data-row-label">本年</span>
              <span
                v-for="(v, idx) in getChart(activeTab)!.monthlyDataRows.currentYear"
                :key="'cy-' + idx"
                class="data-row-cell"
              >{{ v }}</span>
            </div>
            <div class="data-row">
              <span class="data-row-label">上年</span>
              <span
                v-for="(v, idx) in getChart(activeTab)!.monthlyDataRows.lastYear"
                :key="'ly-' + idx"
                class="data-row-cell"
              >{{ v }}</span>
            </div>
          </div>

          <!-- Data table -->
          <el-card
            v-if="tableVisibleByType[activeTab] && getChart(activeTab)?.tableData"
            shadow="hover"
            style="margin-top: 16px"
          >
            <template #header>
              <div class="chart-card-header"><span>数据明细</span></div>
            </template>
            <div class="chart-data-table">
              <table>
                <thead>
                  <tr>
                    <th v-for="col in (getChart(activeTab)!.tableData as Record<string, unknown>)?.headers as string[] ?? []" :key="col">{{ col }}</th>
                    <th style="min-width:80px">趋势</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(row, rIdx) in (getChart(activeTab)!.tableData as Record<string, unknown>)?.rows as Record<string, unknown>[] ?? []" :key="rIdx">
                    <td>{{ (row as Record<string, unknown>).label }}</td>
                    <td v-for="(val, vIdx) in ((row as Record<string, unknown>).values as unknown[] ?? [])" :key="vIdx">{{ val }}</td>
                    <td>
                      <span
                        v-if="Array.isArray((row as Record<string, unknown>).values)"
                        v-html="sparklineSVG(
                          ((row as Record<string, unknown>).values as unknown[]).map((c: unknown) => parseFloat(String(c)) || 0),
                          -1, 80, 22, '#2D8B57'
                        )"
                      />
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </el-card>

          <!-- AI Analysis (always visible) + Follow-up conversation -->
          <el-card shadow="hover" style="margin-top: 16px" class="analysis-card-tab">
            <template #header>
              <div class="chart-card-header">
                <span>AI 分析</span>
                <el-button
                  v-if="analysisByType[activeTab]"
                  size="small"
                  text
                  type="primary"
                  :loading="analysisLoadingByType[activeTab]"
                  @click="forceRequestAnalysis(activeTab); followUpsByType[activeTab] = []"
                >
                  重新分析
                </el-button>
              </div>
            </template>

            <!-- Initial analysis -->
            <ChartSkeleton v-if="analysisLoadingByType[activeTab]" type="ai" />
            <div v-else-if="analysisByType[activeTab]" class="analysis-text">{{ analysisByType[activeTab] }}</div>
            <div v-else style="text-align: center; padding: 24px; color: #909399">
              <p style="margin-bottom: 12px">点击下方按钮获取该图表的 AI 分析</p>
              <el-button type="primary" size="small" @click="requestAnalysis(activeTab)">生成 AI 分析</el-button>
            </div>

            <!-- Follow-up conversation history -->
            <div v-if="followUpsByType[activeTab]?.length" class="follow-up-history">
              <div v-for="(item, idx) in followUpsByType[activeTab]" :key="idx" class="follow-up-item">
                <div class="follow-up-question">
                  <span class="follow-up-role">追问</span>
                  <span>{{ item.question }}</span>
                </div>
                <div class="follow-up-answer analysis-text">{{ item.answer }}</div>
              </div>
            </div>

            <!-- Follow-up loading -->
            <div v-if="followUpLoading" class="follow-up-item" style="margin-top: 12px">
              <ChartSkeleton type="ai" />
            </div>

            <!-- Follow-up input -->
            <div v-if="analysisByType[activeTab]" class="follow-up-input">
              <el-input
                v-model="followUpInput"
                placeholder="对此分析有疑问？输入追问..."
                size="small"
                :disabled="followUpLoading"
                @keyup.enter="submitFollowUp(activeTab)"
              />
              <el-button
                type="primary"
                size="small"
                :loading="followUpLoading"
                :disabled="!followUpInput.trim()"
                @click="submitFollowUp(activeTab)"
              >
                发送
              </el-button>
            </div>
          </el-card>
        </template>

        <!-- No data -->
        <template v-else>
          <SmartBIEmptyState message="该图表暂无数据，请检查数据源" />
        </template>
      </main>
    </div>

    <!-- Small Multiples Section -->
    <el-card
      v-if="smallMultiplesVisible && dashboardResponse && !isGenerating"
      class="dashboard-card"
      shadow="hover"
      style="margin-top: 16px"
    >
      <template #header>
        <div class="chart-card-header">
 <span> 维度对比 (Small Multiples)</span>
          <el-select
            v-model="smallMultiplesDimension"
            size="small"
            placeholder="选择拆分维度"
            style="width: 130px"
          >
            <el-option label="品类" value="category" />
            <el-option label="月份" value="month" />
            <el-option label="部门" value="department" />
          </el-select>
        </div>
      </template>
      <SmallMultiplesChart
        :data="chartDataForSmallMultiples"
        :config="smallMultiplesConfig"
        x-field="month"
        y-field="actual"
        height="500px"
      />
    </el-card>
    </template>
    </el-tab-pane>

    <!-- P4: 财务数据分析 (embed FinanceAnalysis, 原 /smart-bi/finance) -->
    <el-tab-pane label="财务数据分析" name="analysis">
      <FinanceAnalysis />
    </el-tab-pane>
    </el-tabs>

    <!-- Bookmark Drawer -->
    <el-drawer
      v-model="showBookmarkPanel"
      title="视图书签"
      direction="rtl"
      size="350px"
    >
      <BookmarkPanel
        dashboard-id="financial-dashboard"
        :current-state="currentDashboardState"
        @apply="applyBookmark"
        @close="showBookmarkPanel = false"
      />
    </el-drawer>

    <!-- D2: Annotation Dialog -->
    <el-dialog
      v-model="annotationDialogVisible"
      title="添加数据标注"
      width="400px"
      :close-on-click-modal="true"
    >
      <el-form label-position="top">
        <el-form-item label="标注内容">
          <el-input
            v-model="annotationForm.text"
            placeholder="输入标注文字"
            maxlength="50"
            show-word-limit
            @keyup.enter="confirmAnnotation"
          />
        </el-form-item>
        <el-form-item label="标注颜色">
          <div style="display: flex; gap: 8px">
            <span
              v-for="c in ['#e6a23c', '#FF5630', '#36B37E', '#2D8B57', '#6B778C']"
              :key="c"
              style="width: 28px; height: 28px; border-radius: 50%; cursor: pointer; border: 2px solid transparent; transition: border-color 0.2s"
              :style="{ background: c, borderColor: annotationForm.color === c ? '#333' : 'transparent' }"
              @click="annotationForm.color = c"
            />
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="annotationDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmAnnotation" :disabled="!annotationForm.text.trim()">添加标注</el-button>
      </template>
    </el-dialog>

    <!-- Presentation Mode -->
    <PresentationMode
      :visible="isPresentationVisible"
      :slides="presentationSlides"
      :chart-results="charts"
      company-name="白垩纪科技"
      :period="periodLabel"
      @close="isPresentationVisible = false"
    />

    <!-- Fix 64: Spotlight overlay -->
    <Teleport to="body">
      <div
        v-if="spotlightChart"
        class="spotlight-overlay"
        @click.self="exitSpotlight"
        @keydown="handleSpotlightKeydown"
        tabindex="0"
      >
        <div class="spotlight-container">
          <div class="spotlight-header">
            <span>{{ chartTypes.find(c => c.key === spotlightChart)?.icon }} {{ chartTypes.find(c => c.key === spotlightChart)?.label }}</span>
 <el-button size="small" text @click="exitSpotlight"> 退出聚焦</el-button>
          </div>
          <div
            :ref="(el) => { if (spotlightChart) setChartDomRef(spotlightChart, el as Element | null) }"
            class="spotlight-chart-canvas"
          />
        </div>
      </div>
    </Teleport>

    <!-- Export progress dialog -->
    <el-dialog v-model="exportProgress.visible" title="导出进度" width="400px" :close-on-click-modal="false" :show-close="false">
      <el-steps :active="exportProgress.currentStep" finish-status="success" simple style="margin-bottom: 20px">
        <el-step v-for="step in exportProgress.steps" :key="step" :title="step" />
      </el-steps>
      <el-progress :percentage="exportProgress.percentage" :stroke-width="8" />
    </el-dialog>
  </div>
</template>

<style scoped>
.financial-dashboard-pbi {
  padding: 16px;
  min-height: 100vh;
  background: #f5f7fa;
}

/* Toolbar */
.toolbar-card {
  margin-bottom: 16px;
}

:deep(.toolbar-card .el-card__body) {
  padding: 12px 16px;
}

.toolbar-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
}

.toolbar-left,
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

/* Empty state */
.empty-state {
  display: flex;
  justify-content: center;
  padding: 80px 0;
}

/* Charts grid */
.charts-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.chart-card--wide {
  grid-column: 1 / -1;
}

@media (max-width: 1200px) {
  .charts-grid {
    grid-template-columns: 1fr;
  }
  .chart-card--wide {
    grid-column: 1;
  }
}

/* Chart card */

/* C4: Focus ring for keyboard navigation */
.chart-card:focus {
  outline: 2px solid #2D8B57;
  outline-offset: 2px;
  box-shadow: 0 0 0 4px rgba(45, 139, 87, 0.15);
}

.chart-card:focus:not(:focus-visible) {
  outline: none;
  box-shadow: none;
}

.chart-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  font-size: 14px;
  color: var(--el-text-color-primary, #303133);
}

/* Generic KPI row */
.generic-kpi-row {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.generic-kpi-chip {
  flex: 1;
  min-width: 100px;
  background: rgba(45, 139, 87, 0.06);
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.generic-kpi-chip.kpi-up {
  background: rgba(54, 179, 126, 0.08);
}

.generic-kpi-chip.kpi-down {
  background: rgba(255, 86, 48, 0.08);
}

/* A6: KPI typography upgrade — Power BI scale */
.kpi-val {
  font-size: 28px;
  font-weight: 700;
  color: var(--el-color-primary, #2D8B57);
  font-feature-settings: "tnum";
  letter-spacing: -0.5px;
  line-height: 1.2;
}

.kpi-lbl {
  font-size: 11px;
  color: var(--el-text-color-secondary, #909399);
  margin-top: 4px;
}

/* A3: Sparkline */
.kpi-sparkline {
  width: 60px;
  height: 20px;
  margin-top: 4px;
}

/* A6: Trend border accent */
.generic-kpi-chip.kpi-up {
  border-left: 3px solid var(--el-color-success, #36B37E);
}

.generic-kpi-chip.kpi-down {
  border-left: 3px solid var(--el-color-danger, #FF5630);
}

.generic-kpi-chip {
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  transition: transform 0.25s cubic-bezier(0.4, 0, 0.2, 1), box-shadow 0.25s cubic-bezier(0.4, 0, 0.2, 1);
}

.generic-kpi-chip:hover {
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

/* Quarterly progress bars (budget_achievement) */
.quarterly-progress-row {
  display: flex;
  gap: 12px;
  padding: 4px 16px 8px;
}

.q-progress-item {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.q-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-regular, #606266);
  white-space: nowrap;
}

.q-progress-item :deep(.el-progress) {
  flex: 1;
  min-width: 0;
}

.q-rate {
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}

/* Monthly data rows (yoy_mom_comparison) */
.monthly-data-rows {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 16px;
  background: rgba(45, 139, 87, 0.03);
  border-radius: 6px;
  margin-top: 4px;
}

.data-row {
  display: flex;
  align-items: center;
  gap: 4px;
}

.data-row-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--el-text-color-secondary, #909399);
  width: 36px;
  flex-shrink: 0;
}

.data-row-cell {
  flex: 1;
  text-align: center;
  font-size: 11px;
  color: var(--el-text-color-regular, #606266);
}

/* Chart card title group */
.chart-card-title-group {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  overflow: hidden;
}

.chart-card-title-group .chart-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* D3: Anomaly badge */
.anomaly-badge {
  font-size: 11px;
  cursor: help;
}

/* Interaction tips */
.interaction-tips {
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  text-align: center;
  margin-bottom: 8px;
}

.interaction-tips b {
  color: var(--el-text-color-regular, #606266);
}

/* B1: Cross-filter bar */
.cross-filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: rgba(45, 139, 87, 0.08);
  border-radius: 8px;
  padding: 8px 16px;
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--el-color-primary, #2D8B57);
}

/* Analysis panel */
.analysis-panel {
  margin-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter, #f0f0f0);
  padding-top: 12px;
}

.analysis-text {
  font-size: 13px;
  color: var(--el-text-color-regular, #606266);
  line-height: 1.7;
  white-space: pre-wrap;
  background: rgba(45, 139, 87, 0.04);
  border-radius: 6px;
  padding: 12px;
}

/* ---- B3: Dark mode ---- */
.financial-dashboard-pbi[data-theme="dark"] {
  background: var(--bg-color-page);
  color: var(--text-color-primary);
}

.financial-dashboard-pbi[data-theme="dark"] .toolbar-card,
.financial-dashboard-pbi[data-theme="dark"] .chart-card,
.financial-dashboard-pbi[data-theme="dark"] .dashboard-card {
  background: var(--bg-color-overlay);
  border-color: var(--border-color);
}

.financial-dashboard-pbi[data-theme="dark"] :deep(.el-card) {
  background: var(--bg-color-overlay);
  border-color: var(--border-color);
  color: var(--text-color-primary);
}

.financial-dashboard-pbi[data-theme="dark"] :deep(.el-card__header) {
  border-bottom-color: var(--border-color);
}

.financial-dashboard-pbi[data-theme="dark"] .chart-card-header {
  color: var(--text-color-primary);
}

.financial-dashboard-pbi[data-theme="dark"] .generic-kpi-chip {
  background: rgba(255, 255, 255, 0.06);
}

.financial-dashboard-pbi[data-theme="dark"] .kpi-val {
  color: var(--color-primary-light);
}

.financial-dashboard-pbi[data-theme="dark"] .kpi-lbl {
  color: var(--text-color-secondary);
}

.financial-dashboard-pbi[data-theme="dark"] .analysis-text {
  background: rgba(255, 255, 255, 0.04);
  color: var(--text-color-regular);
}

.financial-dashboard-pbi[data-theme="dark"] .monthly-data-rows {
  background: rgba(255, 255, 255, 0.04);
}

.financial-dashboard-pbi[data-theme="dark"] .data-row-cell,
.financial-dashboard-pbi[data-theme="dark"] .data-row-label {
  color: var(--text-color-secondary);
}

.financial-dashboard-pbi[data-theme="dark"] .cross-filter-bar {
  background: rgba(76, 154, 255, 0.12);
  color: var(--color-primary-light);
}

/* ---- Chart canvas base height (overridden by responsive breakpoints) ---- */
.generic-chart-canvas {
  height: 360px;
  width: 100%;
}

/* ---- C2: Responsive breakpoints ---- */
@media (max-width: 768px) {
  .charts-grid {
    grid-template-columns: 1fr;
    gap: 12px;
  }
  .chart-card--wide {
    grid-column: 1;
  }
  .toolbar-inner {
    flex-direction: column;
    align-items: stretch;
  }
  .toolbar-left,
  .toolbar-right {
    justify-content: center;
  }
  .generic-kpi-row {
    gap: 6px;
  }
  .generic-kpi-chip {
    min-width: 80px;
    padding: 8px 8px;
  }
  .kpi-val {
    font-size: 20px;
  }
  .quarterly-progress-row {
    flex-wrap: wrap;
  }
  .generic-chart-canvas {
    height: 260px !important;
  }
}

@media (max-width: 480px) {
  .financial-dashboard-pbi {
    padding: 8px;
  }
  .generic-kpi-row {
    flex-direction: column;
  }
  .generic-kpi-chip {
    flex-direction: row;
    justify-content: space-between;
    align-items: center;
  }
  .kpi-val {
    font-size: 18px;
  }
  .kpi-lbl {
    font-size: 10px;
  }
  .generic-chart-canvas {
    height: 220px !important;
  }
  .toolbar-right {
    flex-wrap: wrap;
    justify-content: flex-start;
  }
}

/* ---- Lazy load fade-in ---- */
.chart-card {
  overflow: hidden;
  opacity: 0;
  transform: translateY(8px);
  transition: opacity 0.4s ease, transform 0.4s ease, box-shadow 0.25s ease;
}
.chart-card--visible {
  opacity: 1;
  transform: translateY(0);
}

/* ---- E5: Card hover micro-interactions ---- */
.chart-card--visible:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 28px rgba(45, 139, 87, 0.12), 0 2px 8px rgba(0, 0, 0, 0.06);
}

/* ---- E7: KPI Scorecard full-width header ---- */
.chart-card--scorecard {
  order: -1; /* always first in grid */
}
.chart-card--scorecard .generic-kpi-row {
  gap: 16px;
}
.chart-card--scorecard .generic-kpi-chip {
  padding: 16px 20px;
  border-radius: 12px;
  min-width: 140px;
}
.chart-card--scorecard .kpi-val {
  font-size: 32px;
  letter-spacing: -1px;
}
.chart-card--scorecard .kpi-lbl {
  font-size: 12px;
  font-weight: 500;
}
.chart-card--scorecard .kpi-sparkline {
  width: 80px;
  height: 24px;
}

/* ---- E4: Data table ---- */
.chart-data-table {
  margin-top: 12px;
  border-top: 1px solid #f0f0f0;
  padding-top: 12px;
  overflow-x: auto;
  max-height: 240px;
  overflow-y: auto;
}
.chart-data-table table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.chart-data-table th {
  background: rgba(45, 139, 87, 0.06);
  padding: 6px 10px;
  text-align: left;
  font-weight: 600;
  color: var(--el-text-color-primary, #303133);
  border-bottom: 2px solid var(--el-border-color, #e8e8e8);
  position: sticky;
  top: 0;
  white-space: nowrap;
}
.chart-data-table td {
  padding: 5px 10px;
  border-bottom: 1px solid var(--el-border-color-lighter, #f0f0f0);
  color: var(--el-text-color-regular, #606266);
  white-space: nowrap;
}
.chart-data-table tr:hover td {
  background: rgba(45, 139, 87, 0.03);
}

/* ---- E6: Print stylesheet ---- */
@media print {
  .financial-dashboard-pbi {
    background: #fff !important;
    padding: 0 !important;
  }
  .toolbar-card,
  .interaction-tips,
  .cross-filter-bar,
  .analysis-panel,
  .chart-data-table {
    display: none !important;
  }
  .charts-grid {
    display: block !important;
  }
  .chart-card {
    opacity: 1 !important;
    transform: none !important;
    break-inside: avoid;
    page-break-inside: avoid;
    margin-bottom: 16px;
    box-shadow: none !important;
    border: 1px solid #e0e0e0 !important;
  }
  .chart-card:hover {
    transform: none !important;
    box-shadow: none !important;
  }
  .generic-chart-canvas {
    height: 280px !important;
  }
  .kpi-val {
    color: #000 !important;
  }
}

/* Dark mode additions for E4/E5 */
.financial-dashboard-pbi[data-theme="dark"] .chart-card--visible:hover {
  box-shadow: 0 8px 28px rgba(76, 154, 255, 0.15), 0 2px 8px rgba(0, 0, 0, 0.2);
}
.financial-dashboard-pbi[data-theme="dark"] .chart-data-table th {
  background: rgba(76, 154, 255, 0.1);
  color: var(--text-color-primary);
  border-bottom-color: var(--border-color);
}
.financial-dashboard-pbi[data-theme="dark"] .chart-data-table td {
  color: var(--text-color-secondary);
  border-bottom-color: var(--border-color);
}
.financial-dashboard-pbi[data-theme="dark"] .chart-data-table tr:hover td {
  background: rgba(76, 154, 255, 0.06);
}

/* ---- Glassmorphism chart cards ---- */
.chart-card :deep(.el-card__body) {
  background: rgba(255, 255, 255, 0.78);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid rgba(255, 255, 255, 0.25);
}

/* ---- Dark mode glassmorphism variant ---- */
.financial-dashboard-pbi[data-theme="dark"] .chart-card :deep(.el-card__body) {
  background: rgba(30, 30, 46, 0.72);
  border-color: rgba(255, 255, 255, 0.08);
}

/* ---- Additional responsive: tablet toolbar row ---- */
@media (max-width: 1200px) {
  .toolbar-row {
    flex-wrap: wrap;
    gap: 8px;
  }
}

@media (max-width: 768px) {
  .chart-card-header h3 {
    font-size: 14px !important;
  }
  .toolbar-row {
    flex-wrap: wrap;
    gap: 8px;
  }
}

@media (max-width: 480px) {
  .generic-kpi-row {
    flex-wrap: wrap;
  }
  .generic-kpi-chip {
    min-width: 120px;
    flex: 1 1 45%;
  }
}

/* ---- Fix 72: Slicer / filter bar ---- */
.slicer-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  background: rgba(45, 139, 87, 0.04);
  border: 1px solid rgba(45, 139, 87, 0.1);
  border-radius: 8px;
  padding: 8px 16px;
  margin-bottom: 12px;
}
.slicer-label {
  font-size: 12px;
  font-weight: 600;
  color: #909399;
  white-space: nowrap;
}
.slicer-group {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}
.slicer-dim-name {
  font-size: 12px;
  color: #606266;
  white-space: nowrap;
  margin-right: 2px;
}
.slicer-bar :deep(.el-check-tag) {
  font-size: 12px;
  padding: 2px 10px;
  border-radius: 12px;
}

/* ---- Fix 64: Spotlight overlay ---- */
.spotlight-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  background: rgba(0, 0, 0, 0.65);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}
.spotlight-container {
  position: relative;
  width: 90vw;
  height: 85vh;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  cursor: default;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.spotlight-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  border-bottom: 1px solid #f0f0f0;
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  flex-shrink: 0;
}
.spotlight-chart-canvas {
  flex: 1;
  width: 100%;
  min-height: 0;
}

/* ---- Tab mode: Sidebar + Content layout ---- */
.dashboard-layout {
  display: flex;
  gap: 16px;
  min-height: calc(100vh - 120px);
}

.dash-sidebar {
  width: 220px;
  flex-shrink: 0;
  background: #1a2332;
  border-radius: 12px;
  color: #c8d0da;
  padding: 12px 0;
  overflow-y: auto;
  position: sticky;
  top: 16px;
  max-height: calc(100vh - 120px);
}

.nav-group {
  margin-bottom: 4px;
}

.nav-group-title {
  padding: 10px 16px 6px;
  font-size: 11px;
  font-weight: 600;
  color: #5a6778;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: space-between;
  user-select: none;
  transition: color 0.2s;
}

.nav-group-title:hover {
  color: #8899aa;
}

.nav-group-items {
  display: flex;
  flex-direction: column;
}

.nav-tab-item {
  padding: 9px 16px;
  font-size: 13px;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  border-left: 3px solid transparent;
  color: #9aa5b4;
  transition: all 0.2s ease;
  user-select: none;
}

.nav-tab-item:hover {
  background: rgba(255, 255, 255, 0.05);
  color: #c8d0da;
}

.nav-tab-item.active {
  background: rgba(45, 139, 87, 0.15);
  color: #5ede9a;
  border-left-color: #2D8B57;
  font-weight: 500;
}

.nav-tab-item.available {
  color: #c8d0da;
}

.nav-tab-item:not(.available):not(.active) {
  opacity: 0.5;
}

.nav-icon {
  font-size: 14px;
  flex-shrink: 0;
  width: 20px;
  text-align: center;
}

.nav-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-order {
  margin-left: auto;
  font-size: 11px;
  opacity: 0.4;
  flex-shrink: 0;
}

.dash-content {
  flex: 1;
  min-width: 0;
}

.tab-kpi-row {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.tab-chart-card {
  margin-bottom: 0;
}

/* Dark mode sidebar */
.financial-dashboard-pbi[data-theme="dark"] .dash-sidebar {
  background: #111827;
  border: 1px solid rgba(255, 255, 255, 0.06);
}

/* Responsive: collapse sidebar on small screens */
@media (max-width: 1024px) {
  .dashboard-layout {
    flex-direction: column;
  }
  .dash-sidebar {
    width: 100%;
    max-height: none;
    position: static;
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    padding: 8px;
  }
  .nav-group {
    flex: 1;
    min-width: 200px;
  }
  .nav-group-items {
    flex-direction: row;
    flex-wrap: wrap;
  }
  .nav-tab-item {
    padding: 6px 12px;
    border-left: none;
    border-bottom: 2px solid transparent;
    font-size: 12px;
  }
  .nav-tab-item.active {
    border-bottom-color: #2D8B57;
    border-left-color: transparent;
  }
}

@media print {
  .dash-sidebar {
    display: none !important;
  }
  .dashboard-layout {
    display: block !important;
  }
}

/* ---- Follow-up conversation ---- */
.follow-up-history {
  margin-top: 16px;
  border-top: 1px dashed var(--el-border-color-lighter, #e8e8e8);
  padding-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.follow-up-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.follow-up-question {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  color: var(--el-text-color-primary, #303133);
}

.follow-up-role {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  color: #fff;
  background: #2D8B57;
  border-radius: 4px;
  padding: 1px 6px;
  line-height: 1.5;
}

.follow-up-answer {
  margin-left: 0;
}

.follow-up-input {
  display: flex;
  gap: 8px;
  margin-top: 16px;
  border-top: 1px solid var(--el-border-color-lighter, #f0f0f0);
  padding-top: 12px;
}

.follow-up-input .el-input {
  flex: 1;
}

/* Dark mode follow-up */
.financial-dashboard-pbi[data-theme="dark"] .follow-up-history {
  border-top-color: var(--border-color);
}

.financial-dashboard-pbi[data-theme="dark"] .follow-up-role {
  background: #1B7A4A;
}

.financial-dashboard-pbi[data-theme="dark"] .follow-up-input {
  border-top-color: var(--border-color);
}
</style>
