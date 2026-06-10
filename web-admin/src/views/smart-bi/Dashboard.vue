<script setup lang="ts">
/**
 * SmartBI 经营驾驶舱
 * 展示企业经营核心 KPI、排行榜、趋势图表和 AI 洞察
 */
import { ref, computed, onMounted, watch, nextTick } from 'vue';
import { useChartResize } from '@/composables/useChartResize';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { getGoldDataRange } from '@/api/smartbi/dataRange';
import { ElMessage } from 'element-plus';
import {
  TrendCharts,
  DataLine,
  Histogram,
  ChatDotRound,
  Refresh,
  ArrowUp,
  ArrowDown,
  Medal,
  Location,
  Goods,
  InfoFilled,
  User,
  Clock,
  Loading
} from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import { formatNumber, formatCount, formatAxisValue } from '@/utils/format-number';
import { CHART_COLORS } from '@/constants/chart-colors';
import { sparklinePath } from '@/utils/sparkline';
import SmartBIEmptyState from '@/components/smartbi/SmartBIEmptyState.vue';
import ChartSkeleton from '@/components/smartbi/ChartSkeleton.vue';
import { enhanceChartDefaults } from '@/composables/useChartEnhancer';
import TemplateGrid from './components/TemplateGrid.vue';
import RestaurantGoldGrid from './components/RestaurantGoldGrid.vue';
import ValueFeedbackStrip from './components/ValueFeedbackStrip.vue';
import { buildChartInsight } from './components/chartInsight';
import type { InsightResult, ChartWithMeta } from './components/chartInsight';
import ChartInsight from './components/ChartInsight.vue';
// Day 8 数据织网 Sub-Project A: capability-driven card visibility
import { useCapability } from '@/composables/useCapability';
import CapabilityGate from '@/components/CapabilityGate.vue';
import UnlockMoreCTA from '@/components/UnlockMoreCTA.vue';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canUpload = computed(() => permissionStore.canWrite('analytics'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

// ==================== 类型定义 ====================

import type {
  RankingItem,
  AIInsightResponse,
  DashboardChartConfig as ChartConfig,
  ChartConfig as AnyChartConfig,
  DashboardResponse
} from '@/types/smartbi';

// 前端使用的部门排行数据
interface DepartmentRank {
  name: string;
  sales: number;
  growth: number;
  alertLevel: string;
}

// 前端使用的区域排行数据
interface RegionRank {
  name: string;
  sales: number;
  percentage: number;
}

// 前端使用的 AI 洞察
interface AIInsight {
  type: 'success' | 'warning' | 'danger' | 'info';
  title: string;
  content: string;
  suggestion?: string;
}

// ==================== 状态 ====================

// 暗色模式
const isDarkMode = ref(false);

function toggleDarkMode() {
  isDarkMode.value = !isDarkMode.value;
  // Re-init charts with correct theme
  if (dashboardData.value?.charts) {
    nextTick(() => {
      if (trendChart) { trendChart.dispose(); trendChart = null; }
      if (pieChart) { pieChart.dispose(); pieChart = null; }
      initCharts(dashboardData.value!.charts as Record<string, ChartConfig>);
    });
  }
}

// 加载状态
const loading = ref(false);
const hasError = ref(false);
const errorMessage = ref('');
// Apr 24 UX: separate LLM insights loading state so insight card shows
// skeleton/"生成中" instead of the "暂无分析" empty state during the 1-8s
// async LLM call (Python cold-start on first visit of day is 5-10s).
const insightsLoading = ref(false);
const insightsTookLong = ref(false);
// Phase 9 Apr 24: live-streaming text from SSE endpoint for the
// custom-range path. While streaming, shows as a "正在生成..." insight
// that updates character-by-character; finalizes on done event.
const streamingInsightText = ref('');
const streamingInsightMeta = ref<{ source?: string; tokens_used_today?: number } | null>(null);

// WS2 (2026-06-02): 数据源选择器已移除 — 驾驶舱永远从 gold 层自动聚合 (默认全部历史)。
// 不再让用户挑某一次上传的 CSV 作为 KPI 来源 (误导且复杂)。所有数据加载走
// loadDashboardData() (gold/executive 路径)。保留 dateRange 选择器供用户收窄区间。

// Dynamic data AI insights (kept as empty placeholder — dynamic-upload path removed)
const dynamicInsights = ref<string[]>([]);

// Dashboard 数据
const dashboardData = ref<DashboardResponse | null>(null);

// P1 PERF fix: AbortController to cancel pending requests on data source switch / unmount
let abortController: AbortController | null = null;
function getSignal(): AbortSignal {
  if (abortController) abortController.abort();
  abortController = new AbortController();
  return abortController.signal;
}

// KPI 数据 (从 kpiCards 提取)
const kpiData = computed(() => {
  // Always-populated default so the template's `kpiData.revenueLabel || ...`
  // chain has a real string to hit instead of `undefined` → Chinese fallback
  // which masked xlsx column titles. If data hasn't loaded yet, labels stay
  // as the generic Chinese strings; once data loads they get overwritten.
  const defaultKpi = {
    totalRevenue: null as number | null,
    revenueGrowth: null as number | null,
    revenueLabel: '本月销售额',
    totalProfit: null as number | null,
    profitGrowth: null as number | null,
    profitLabel: '本月利润',
    profitUnit: undefined as string | undefined,
    orderCount: null as number | null,
    orderGrowth: null as number | null,
    orderLabel: '订单数量',
    customerCount: null as number | null,
    customerGrowth: null as number | null,
    customerLabel: '活跃客户',
    customerUnit: undefined as string | undefined,
  };

  if (!dashboardData.value?.kpiCards || dashboardData.value.kpiCards.length === 0) {
    return defaultKpi;
  }

  const cards = dashboardData.value.kpiCards;
  const findCard = (key: string) => cards.find(c => c.key === key);
  // Also match by title text for dynamic data where key might be the title itself
  const findByTitle = (keyword: string) => cards.find(c =>
    (c.title || '').toLowerCase().includes(keyword)
  );

  // Gold-mode mapping (restaurant POS): total_revenue / bill_count / avg_bill_value / store_count.
  // Restaurants don't track profit or unique customers at this layer, so relabel slots 2-4 accordingly.
  const goldRev = findCard('total_revenue');
  const goldBills = findCard('bill_count');
  const goldAvg = findCard('avg_bill_value');
  const goldStores = findCard('store_count');
  if (goldRev && goldBills && goldAvg && goldStores) {
    return {
      totalRevenue: goldRev.rawValue ?? null,
      revenueGrowth: null as number | null,
      // 用后端 KPICard title (「总营收」) 而非模板默认「本月销售额」—— 餐厅默认全量
      // 区间下「本月」措辞不准 (May 29 2026, Gold 分支原先漏设此 label)。
      revenueLabel: goldRev.title ?? '营业额',
      totalProfit: goldAvg.rawValue ?? null,
      profitGrowth: null as number | null,
      profitLabel: '客单价',
      profitUnit: '元',
      orderCount: goldBills.rawValue ?? null,
      orderGrowth: null as number | null,
      customerCount: goldStores.rawValue ?? null,
      customerGrowth: null as number | null,
      customerLabel: '门店数',
      customerUnit: '家',
    };
  }

  const salesCard = findCard('SALES_AMOUNT') || findCard('REVENUE') || findCard('销售额')
    || findByTitle('销售') || findByTitle('收入') || findByTitle('revenue');
  const profitCard = findCard('PROFIT') || findCard('PROFIT_AMOUNT') || findCard('利润')
    || findByTitle('利润') || findByTitle('profit');
  const orderCard = findCard('ORDER_COUNT') || findCard('ORDERS') || findCard('订单数')
    || findByTitle('订单') || findByTitle('order');
  const customerCard = findCard('CUSTOMER_COUNT') || findCard('ACTIVE_CUSTOMERS') || findCard('客户数')
    || findByTitle('客户') || findByTitle('customer');

  // If no recognized KPI cards matched but we have kpiCards, use them in order as fallback.
  // Keep the original (humanized) titles instead of relabeling to 销售/利润/订单/客户 —
  // the positional fallback fires for xlsx uploads where cards[0..3] are typically 4
  // different "数量金额" columns (not revenue/profit/orders/customers), so relabeling
  // misleads users.
  const hasMatch = salesCard || profitCard || orderCard || customerCard;
  if (!hasMatch && cards.length > 0) {
    return {
      totalRevenue: cards[0]?.rawValue ?? null,
      revenueGrowth: cards[0]?.changeRate ?? null,
      revenueLabel: cards[0]?.title || '指标 1',
      totalProfit: cards[1]?.rawValue ?? null,
      profitGrowth: cards[1]?.changeRate ?? null,
      profitLabel: cards[1]?.title || '指标 2',
      orderCount: cards[2]?.rawValue ?? null,
      orderGrowth: cards[2]?.changeRate ?? null,
      orderLabel: cards[2]?.title || '指标 3',
      customerCount: cards[3]?.rawValue ?? null,
      customerGrowth: cards[3]?.changeRate ?? null,
      customerLabel: cards[3]?.title || '指标 4',
    };
  }

  // Fallback: if profit/customer KPIs not available, use TARGET_COMPLETION/MOM_GROWTH
  const targetCard = findCard('TARGET_COMPLETION') || findByTitle('目标') || findByTitle('完成率');
  const growthCard = findCard('MOM_GROWTH') || findByTitle('环比') || findByTitle('增长');

  // If a card matched but its title is more specific than the fixed Chinese label
  // (e.g. "数量金额 (指标 4)"), prefer the actual card title so users see what the
  // column really represents. Chinese fallback kicks in only when no card matched.
  return {
    totalRevenue: salesCard?.rawValue ?? null,
    revenueGrowth: salesCard?.changeRate ?? null,
    revenueLabel: salesCard?.title || '本月销售额',
    totalProfit: profitCard?.rawValue ?? (targetCard?.rawValue ?? null),
    profitGrowth: profitCard?.changeRate ?? null,
    profitLabel: profitCard?.title || (targetCard ? '目标完成率' : '本月利润'),
    profitUnit: profitCard ? '' : (targetCard ? '%' : ''),
    orderCount: orderCard?.rawValue ?? null,
    orderGrowth: orderCard?.changeRate ?? null,
    orderLabel: orderCard?.title || '订单数量',
    customerCount: customerCard?.rawValue ?? (growthCard?.rawValue ?? null),
    customerGrowth: customerCard?.changeRate ?? null,
    customerLabel: customerCard?.title || (growthCard ? '环比增长' : '活跃客户'),
    customerUnit: customerCard ? '' : (growthCard ? '%' : ''),
  };
});

// WS2 (2026-06-02): gold-mode 检测 — 当后端返回 gold KPI 卡 (total_revenue /
// bill_count / avg_bill_value / store_count) 时, KPI 区直接出数, 不经
// <CapabilityGate> 的上传能力门控 (gold 聚合无需 upload-capability 判定)。
// 制造业 / xlsx 上传路径 (无 gold 卡) 保持原 CapabilityGate 行为不变。
const isGoldMode = computed(() => {
  const cards = dashboardData.value?.kpiCards;
  if (!cards || cards.length === 0) return false;
  const has = (key: string) => cards.some(c => c.key === key);
  return has('total_revenue') && has('bill_count') && has('avg_bill_value') && has('store_count');
});

// 部门排行数据 (从 rankings 提取)
const departmentRanking = computed<DepartmentRank[]>(() => {
  if (!dashboardData.value?.rankings) return [];

  const deptRankings = dashboardData.value.rankings['department']
    || dashboardData.value.rankings['sales_person']
    || dashboardData.value.rankings['部门']
    || [];

  return deptRankings.map(item => ({
    name: item.name,
    sales: item.value,
    growth: item.completionRate != null ? item.completionRate - 100 : 0,
    alertLevel: item.alertLevel
  }));
});

// 区域排行数据 (从 rankings 提取)
const regionRanking = computed<RegionRank[]>(() => {
  if (!dashboardData.value?.rankings) return [];

  const regionRankings = dashboardData.value.rankings['region']
    || dashboardData.value.rankings['区域']
    || [];

  // 计算总值用于百分比
  const total = regionRankings.reduce((sum, item) => sum + item.value, 0);

  return regionRankings.map(item => ({
    name: item.name,
    sales: item.value,
    percentage: total > 0 ? Math.round((item.value / total) * 100) : 0
  }));
});

// Normalize LLM insight markdown → clean prose for the plain-text (pre-line) panel.
// Applied BOTH to streamed deltas AND to cached/server insights at display time
// (the dashboard often loads aiInsights from cache, bypassing the stream handler).
// - strips **bold**/##/`code`/> quotes/[CJK entity] brackets (keeps [1][2] citations)
// - breaks inline "· " bullets + trailing 注: clause onto their own lines
// - store-name middle-dots (蜀三味·IFS, no trailing space) are NOT split
function cleanInsightText(s: string): string {
  if (!s) return s;
  let t = s;
  t = t.replace(/[\[【]([^\[\]【】]*[一-鿿][^\[\]【】]*)[\]】]/g, '$1');
  t = t.replace(/\*\*([^*]+)\*\*/g, '$1');
  t = t.replace(/__([^_]+)__/g, '$1');
  t = t.replace(/\*([^*]+)\*/g, '$1');
  t = t.replace(/_([^_]+)_/g, '$1');
  t = t.replace(/^#{1,6}\s+/gm, '');
  t = t.replace(/^[-*+]\s+/gm, '· ');
  t = t.replace(/^\d+\.\s+/gm, '· ');
  t = t.replace(/^>\s*/gm, '');
  t = t.replace(/```[\s\S]*?```/g, '');
  t = t.replace(/`([^`]+)`/g, '$1');
  t = t.replace(/([^\n \t])[ \t]*·[ \t]+/g, '$1\n· ');
  t = t.replace(/([。！？])[ \t]*(注\s*[:：])/g, '$1\n$2');
  t = t.replace(/\n{3,}/g, '\n\n');
  t = t.split('\n').map((l) => l.trim()).join('\n');
  t = t.replace(/^\n+/, '').replace(/\n+$/, '');
  return t;
}

// AI 洞察 (从 aiInsights 提取, 去重 — 精确去重 + 相似内容去重)
const aiInsights = computed<AIInsight[]>(() => {
  if (!dashboardData.value?.aiInsights) return [];

  const seen = new Set<string>();
  const seenKeywords: string[][] = []; // track key metrics to avoid similar insights

  // Extract key numbers from a message (e.g. "-34.7%" → "34.7")
  const extractNumbers = (text: string) => {
    const matches = text.match(/[\d.]+%/g) || [];
    return matches.map(m => m.replace('%', ''));
  };

  return dashboardData.value.aiInsights
    .filter(insight => {
      const key = cleanInsightText(insight.message);
      if (seen.has(key)) return false;
      seen.add(key);

      // Similarity check: skip if another insight mentions the same key percentages
      const nums = extractNumbers(key);
      if (nums.length > 0) {
        for (const prev of seenKeywords) {
          const overlap = nums.filter(n => prev.includes(n));
          if (overlap.length >= 1 && overlap.length >= nums.length * 0.5) return false;
        }
        seenKeywords.push(nums);
      }
      return true;
    })
    .map(insight => ({
      type: mapInsightLevel(insight.level),
      title: insight.category || getCategoryTitle(insight.level),
      content: cleanInsightText(insight.message),
      suggestion: insight.actionSuggestion
    }));
});

// ==================== KPI Sparkline 数据 ====================

/**
 * Extract sparkline data arrays from the sales_trend chart series.
 * Returns up to 4 sparkline arrays for each KPI card position.
 * If no trend chart data, falls back to empty arrays.
 */
const kpiSparklines = computed(() => {
  const empty = { revenue: [] as number[], profit: [] as number[], orders: [] as number[], customers: [] as number[], labels: [] as string[] };
  if (!dashboardData.value?.charts) return empty;

  const charts = dashboardData.value.charts;
  const trendChartCfg = charts['sales_trend'] || charts['销售趋势'];
  if (!trendChartCfg) return empty;

  // Normalize legacy format
  const normalized = normalizeLegacyChart(trendChartCfg as ChartConfig);
  const series = ('series' in normalized && Array.isArray(normalized.series)) ? normalized.series : [];

  if (series.length === 0) return empty;

  // Extract xAxis labels (dates) for sparkline tooltips
  const xAxisData = (normalized as unknown as Record<string, unknown>).xAxis;
  const labels: string[] = Array.isArray(xAxisData)
    ? ((xAxisData[0] as Record<string, unknown>)?.data as string[] || [])
    : ((xAxisData as Record<string, unknown>)?.data as string[] || []);

  // Try to match series by name to KPI slots
  const findSeries = (keywords: string[]) => {
    for (const kw of keywords) {
      const s = series.find((ser: Record<string, unknown>) =>
        typeof ser.name === 'string' && ser.name.toLowerCase().includes(kw)
      );
      if (s && Array.isArray(s.data)) return s.data.map(Number).filter(Number.isFinite);
    }
    return [];
  };

  const revenue = findSeries(['销售', '收入', '营收', 'revenue', 'sales']);
  const profit = findSeries(['利润', '净利', 'profit']);
  const orders = findSeries(['订单', 'order']);
  const customers = findSeries(['客户', 'customer']);

  // Fallback: if only 1 series, use it for revenue sparkline; if 2+ assign by position
  if (!revenue.length && series.length >= 1 && Array.isArray(series[0].data)) {
    return {
      revenue: series[0].data.map(Number).filter(Number.isFinite),
      profit: series.length >= 2 && Array.isArray(series[1].data) ? series[1].data.map(Number).filter(Number.isFinite) : [],
      orders: [],
      customers: [],
      labels,
    };
  }

  return { revenue, profit, orders, customers, labels };
});

/** Cached sparkline SVG paths and colors — avoid re-computation on each render */
const kpiSparklinePaths = computed(() => {
  const s = kpiSparklines.value;
  return {
    revenue: { path: sparklinePath(s.revenue), color: s.revenue.length >= 2 ? (s.revenue[s.revenue.length - 1] >= s.revenue[0] ? '#36B37E' : '#FF5630') : '#909399' },
    profit: { path: sparklinePath(s.profit), color: s.profit.length >= 2 ? (s.profit[s.profit.length - 1] >= s.profit[0] ? '#36B37E' : '#FF5630') : '#909399' },
    orders: { path: sparklinePath(s.orders), color: s.orders.length >= 2 ? (s.orders[s.orders.length - 1] >= s.orders[0] ? '#36B37E' : '#FF5630') : '#909399' },
    customers: { path: sparklinePath(s.customers), color: s.customers.length >= 2 ? (s.customers[s.customers.length - 1] >= s.customers[0] ? '#36B37E' : '#FF5630') : '#909399' },
  };
});

// Detect if dashboard has any meaningful data (from system or dynamic source)
const hasData = computed(() => {
  const kd = kpiData.value;
  // Check if any KPI has a non-null value (including zero, which is valid data)
  return kd.totalRevenue !== null || kd.totalProfit !== null || kd.orderCount !== null || kd.customerCount !== null
    || dynamicInsights.value.length > 0
    || (dashboardData.value?.kpiCards && dashboardData.value.kpiCards.length > 0);
});

function goToUpload() {
  router.push({ name: 'SmartBIAnalysis' });
}

// 快捷问答 — 按 factoryType 切换 (餐饮 vs 制造业)
// 餐饮 8 问 keep in sync with web-admin/src/views/smart-bi/AIQuery.vue quickQuestions (Apr 24 RAG polish)
const isRestaurantTenant = computed(() => authStore.factoryType === 'RESTAURANT');
const restaurantQuickQuestions = [
  { text: '畅销品 Top 5', icon: Goods },
  { text: '哪家店业绩最好', icon: Location },
  { text: '员工里谁最厉害', icon: Medal },
  { text: '外卖占比多少', icon: TrendCharts },
  { text: '慢销菜品', icon: ArrowDown },
  { text: '周末周中对比', icon: Clock },
  { text: '峰值月份', icon: DataLine },
  { text: '优惠券使用情况', icon: Histogram }
];
const manufacturingQuickQuestions = [
  { text: '本月销售额如何?', icon: TrendCharts },
  { text: '哪个部门业绩最好?', icon: Histogram },
  { text: '利润率变化趋势如何?', icon: DataLine },
  { text: '客户增长情况怎样?', icon: User }
];
const quickQuestions = computed(() =>
  isRestaurantTenant.value ? restaurantQuickQuestions : manufacturingQuickQuestions
);

// 图表 DOM refs
const dashboardRef = ref<HTMLElement>();
const trendChartRef = ref<HTMLDivElement | null>(null);
const pieChartRef = ref<HTMLDivElement | null>(null);

// 图表实例
let trendChart: echarts.ECharts | null = null;
let pieChart: echarts.ECharts | null = null;
const hasTrendData = ref(false);
const hasPieData = ref(false);
// C Apr 17 2026: 当上传的数据无时间列 (如销量汇总报表) 时, "销售趋势" 标题误导.
// 根据 x-axis 第一个值判断: 像日期 → 趋势; 否则 → 按类别分布/排行
const trendChartTitle = ref('销售趋势');

// Cross-filter state
const crossFilterValue = ref<string | null>(null);

// AI insight generation timestamp
const insightTimestamp = ref<Date | null>(null);
const insightsExpanded = ref(false);
const INSIGHT_COLLAPSE_LIMIT = 3;

// Chart titles for citation references
const chartTitles = ['销售趋势', '产品类别占比'];

// ==================== 数据驱动图表洞察 (chart-insight engine, Tier 1) ====================
// Reads dashboardData.value?.charts independently — does NOT touch echarts init paths.
// Returns InsightResult | null; null → v-if renders nothing (honest-null, no fabrication).

/** 销售趋势 / 按类别排行 chart insight (TREND or RANKING family). */
const trendInsight = computed<InsightResult | null>(() => {
  const charts = dashboardData.value?.charts as Record<string, unknown> | undefined;
  if (!charts) return null;
  const raw = charts['sales_trend'] || charts['销售趋势'];
  if (!raw) return null;
  const cfg = raw as Record<string, unknown>;
  // Determine if the x-axis contains time labels (mirror initTrendChart isTime regex ~line 1159)
  const xAxisData: unknown[] = (
    (cfg.xAxis as Record<string, unknown> | undefined)?.data as unknown[] | undefined
  ) ?? [];
  const firstX = xAxisData.length > 0 ? String(xAxisData[0]) : '';
  const isTime =
    /^\d{4}[-/]\d{1,2}/.test(firstX) ||
    /\d{1,4}[年月日]/.test(firstX) ||
    /^Q[1-4]$/i.test(firstX) ||
    /^\d{4}$/.test(firstX);
  // Extract first numeric series data (parallel to xAxis.data)
  const series = cfg.series as Array<Record<string, unknown>> | undefined;
  const firstSeries = series?.[0];
  const rawData = Array.isArray(firstSeries?.data) ? (firstSeries!.data as unknown[]) : [];
  const numericData = rawData
    .map((v) => (typeof v === 'number' && isFinite(v) ? v : null))
    .filter((v): v is number => v !== null);
  if (numericData.length === 0) return null;
  const labels = xAxisData.map(String);
  const chart: ChartWithMeta = {
    chartType: isTime ? 'LINE' : 'BAR',
    meta: {
      xDim: isTime ? 'time' : 'product',
      yMetric: 'revenue',
      aggregation: 'sum',
      domain: 'restaurant',
    },
    config: {
      xAxis: { data: labels },
      series: [{ type: isTime ? 'line' : 'bar', data: numericData }],
    },
  };
  const perms = { canViewFinance: permissionStore.canWrite('finance') };
  return buildChartInsight(chart, perms);
});

/** 产品类别占比 pie chart insight — normalized to BAR shape so RANKING fires (not pie-null). */
const categoryInsight = computed<InsightResult | null>(() => {
  const charts = dashboardData.value?.charts as Record<string, unknown> | undefined;
  if (!charts) return null;
  // Mirror the pie resolution logic from initCharts ~line 1104
  let raw: unknown =
    charts['category_distribution'] ||
    charts['产品占比'] ||
    charts['类别分布'] ||
    charts['产品销售占比'] ||
    charts['产品分布'];
  if (!raw) {
    // Fallback: first pie-type chart
    for (const cfg of Object.values(charts)) {
      const c = cfg as Record<string, unknown>;
      if (
        String(c.chartType).toLowerCase() === 'pie' ||
        (Array.isArray(c.series) &&
          String((c.series as Record<string, unknown>[])[0]?.type).toLowerCase() === 'pie')
      ) {
        raw = cfg;
        break;
      }
    }
  }
  if (!raw) return null;
  const cfg = raw as Record<string, unknown>;
  const series = cfg.series as Array<Record<string, unknown>> | undefined;
  const firstSeries = series?.[0];
  if (!firstSeries) return null;
  const rawData = Array.isArray(firstSeries.data) ? (firstSeries.data as unknown[]) : [];
  // Extract names + values — support both [{name, value}] and parallel xAxis.data + numeric array
  const categoryNames: string[] = [];
  const categoryValues: number[] = [];
  for (let i = 0; i < rawData.length; i++) {
    const d = rawData[i];
    if (typeof d === 'number' && isFinite(d) && d > 0) {
      // Parallel array format: names come from xAxis.data
      const xData = (cfg.xAxis as Record<string, unknown> | undefined)?.data as unknown[] | undefined;
      const name = xData?.[i] != null ? String(xData[i]) : `类别${i + 1}`;
      categoryNames.push(name);
      categoryValues.push(d);
    } else if (d !== null && typeof d === 'object') {
      const obj = d as Record<string, unknown>;
      const v = typeof obj.value === 'number' && isFinite(obj.value) ? obj.value : 0;
      if (v > 0) {
        const name = obj.name != null ? String(obj.name) : `类别${i + 1}`;
        categoryNames.push(name);
        categoryValues.push(v);
      }
    }
  }
  if (categoryValues.length < 2) return null;
  // Normalize to BAR shape so RANKING family fires (PIE chartType → null per engine spec)
  const chart: ChartWithMeta = {
    chartType: 'BAR',
    meta: {
      xDim: 'category',
      yMetric: 'revenue',
      aggregation: 'sum',
      domain: 'restaurant',
    },
    config: {
      xAxis: { data: categoryNames },
      series: [{ type: 'bar', data: categoryValues }],
    },
  };
  const perms = { canViewFinance: permissionStore.canWrite('finance') };
  return buildChartInsight(chart, perms);
});

function formatInsightTime(date: Date | string) {
  const d = new Date(date);
  return `分析生成于 ${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/**
 * Parse insight content and return segments with citation references.
 * Matches keywords related to chart titles (e.g., "销售", "趋势" → chart 0, "占比", "类别" → chart 1).
 */
function parseInsightCitations(content: string): Array<{ text: string; chartIndex?: number; chartTitle?: string }> {
  // Keyword → chart index mapping
  const keywordMap: Array<{ keywords: RegExp; chartIndex: number }> = [
    { keywords: /销售趋势|营收趋势|收入趋势|同比|环比|增长趋势|月度.*趋势|趋势.*变化/, chartIndex: 0 },
    { keywords: /类别占比|产品.*占比|品类.*分布|分类.*比例|占比.*分布|产品结构/, chartIndex: 1 },
  ];

  // Split by sentences (Chinese period, semicolon). NOT on \n — cleanInsightText
  // inserts \n before bullets/注 for the pre-line panel; splitting on \n here would
  // create whitespace-only segments that get dropped below, re-joining the lines.
  const sentences = content.split(/(?<=[。；;！!？?])/);
  const result: Array<{ text: string; chartIndex?: number; chartTitle?: string }> = [];
  const usedCharts = new Set<number>();

  for (const sentence of sentences) {
    if (!sentence.trim()) continue;
    let matched = false;
    for (const mapping of keywordMap) {
      if (mapping.keywords.test(sentence) && !usedCharts.has(mapping.chartIndex)) {
        usedCharts.add(mapping.chartIndex);
        result.push({
          text: sentence,
          chartIndex: mapping.chartIndex,
          chartTitle: chartTitles[mapping.chartIndex]
        });
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
  const ref = chartIndex === 0 ? trendChartRef.value : pieChartRef.value;
  if (ref) {
    ref.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }
}

// Stagger reveal for KPI cards

// ==================== 生命周期 ====================

// FIX-12 (Apr 16 2026) — persist dashboard state across refresh:
//  C) remember selected uploadId so 刷新后 user doesn't have to re-select
//  A) cache the loaded KPI/chart payload (TTL 5min) so refresh shows data instantly
//     while a fresh API call runs silently in the background.
function cacheKeyFor(factoryId: string, sourceId: string | number) {
  return `smartbi-dashboard:${factoryId}:${sourceId}`;
}
function savedRangeKey(factoryId: string) {
  return `smartbi-dashboard-range:${factoryId}`;
}

// Date range override — null means 默认 period=month (server side).
// Needed because qhj 2025 historical data is invisible under 本月 default.
// When set, routes through /executive/custom (same Gold-cutover code path).
const dateRange = ref<[string, string] | null>(null);

// Apr 24 2026 UX fallback: when 本月 is empty AND user didn't pick a range,
// auto-probe 近90天 → 上年 → 前年 and silently load the first non-empty range.
// Tag below the picker explains the switch. Pattern mirrors Trends/RestaurantV2.
const fallbackRangeLabel = ref<string>('');
const fallbackDateRange = ref<[string, string] | null>(null);

// WS follow-up (2026-06-03): gold 层真实 [min,max] 区间, 用于诚实区分「全部历史」与
// 用户收窄后的「自定义区间」。之前 label 无条件写「全部历史」即使范围已被收窄
// (e.g. 实测某浏览器残留收窄区间显示「全部历史 · 2026-02-01 至 2026-04-30」误导)。
const goldFullRange = ref<[string, string] | null>(null);
const isFullHistory = computed(() =>
  !!dateRange.value && !!goldFullRange.value &&
  dateRange.value[0] === goldFullRange.value[0] &&
  dateRange.value[1] === goldFullRange.value[1]
);
function savedFallbackKey(factoryId: string) {
  return `smartbi-dashboard-fallback:${factoryId}`;
}

const dateRangeShortcuts = [
  {
    text: '本月',
    value: () => {
      const end = new Date();
      const start = new Date();
      start.setDate(1);
      return [fmtYmd(start), fmtYmd(end)];
    },
  },
  {
    text: '本年',
    value: () => {
      const end = new Date();
      const start = new Date();
      start.setMonth(0, 1);
      return [fmtYmd(start), fmtYmd(end)];
    },
  },
  {
    text: '近 12 个月',
    value: () => {
      const end = new Date();
      const start = new Date();
      start.setFullYear(start.getFullYear() - 1);
      return [fmtYmd(start), fmtYmd(end)];
    },
  },
  {
    text: '近 24 个月',
    value: () => {
      const end = new Date();
      const start = new Date();
      start.setFullYear(start.getFullYear() - 2);
      return [fmtYmd(start), fmtYmd(end)];
    },
  },
  { text: '2025 全年', value: () => ['2025-01-01', '2025-12-31'] },
  { text: '2024 全年', value: () => ['2024-01-01', '2024-12-31'] },
];

function fmtYmd(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function onDateRangeChange(range: [string, string] | null) {
  if (!factoryId.value) return;
  if (range) {
    try { localStorage.setItem(savedRangeKey(factoryId.value), JSON.stringify(range)); } catch {}
  } else {
    // WS2: cleared range → revert to all-history gold default (re-probe min/max).
    try { localStorage.removeItem(savedRangeKey(factoryId.value)); } catch {}
    applyAllHistoryDefault().then(() => loadDashboardData());
    return;
  }
  // Always gold-backed (data-source picker removed) — the range drives /executive/custom.
  loadDashboardData();
}

// WS follow-up (2026-06-03): 收窄后一键回到全部历史 — 清掉持久化的收窄区间 +
// 重新套用 gold 全量 [min,max] + 重载。
function resetToFullHistory() {
  if (!factoryId.value) return;
  try { localStorage.removeItem(savedRangeKey(factoryId.value)); } catch {}
  applyAllHistoryDefault().then(() => loadDashboardData());
}
function putCached(factoryId: string, sourceId: string | number, data: unknown) {
  try {
    localStorage.setItem(cacheKeyFor(factoryId, sourceId), JSON.stringify({ ts: Date.now(), data }));
  } catch { /* quota exceeded — ignore */ }
}

const { fetchCapability } = useCapability();

// WS2 (2026-06-02): 默认全部历史 — 探测 Gold (agg_daily) 真实 [min,max] 区间,
// 默认显示全部已有数据 (不再只取「最近有数据的年」)。老板登录即见全量经营图表,
// 不需先上传/选时间。保留 dateRange 选择器供用户收窄。探测失败/无数据 → 不伪造区间,
// 落回 period=month + 空状态 CTA。
// 探测一次 gold 真实区间, 缓存到 goldFullRange (供 label 诚实判断 + 收窄后一键回全量)。
// fail-open: 探测失败留 null, label 退回旧文案, 不阻塞加载。
async function probeGoldFullRange(): Promise<void> {
  if (!factoryId.value || goldFullRange.value) return;
  try {
    const dr = await getGoldDataRange(factoryId.value);
    if (dr.minDate && dr.maxDate) goldFullRange.value = [dr.minDate, dr.maxDate];
  } catch {
    // 探测失败 → 不阻塞, label 退回旧文案。
  }
}
async function applyAllHistoryDefault(): Promise<boolean> {
  await probeGoldFullRange();
  if (goldFullRange.value) {
    dateRange.value = [goldFullRange.value[0], goldFullRange.value[1]];
    return true;
  }
  return false;
}

onMounted(async () => {
  // Day 8 数据织网 Sub-Project A: prime capability cache (fire-and-forget,
  // useCapability handles errors and is fail-open).
  fetchCapability();

  // Probe the gold full range up-front so the label can honestly distinguish
  // 「全部历史」from a user-narrowed 「自定义区间」(needed even when we honor a saved range below).
  await probeGoldFullRange();

  // Restore an explicit date range from localStorage (per factory) if the user
  // previously narrowed it. Otherwise default to all-history gold below.
  if (factoryId.value) {
    const rawRange = localStorage.getItem(savedRangeKey(factoryId.value));
    if (rawRange) {
      try {
        const parsed = JSON.parse(rawRange) as [string, string];
        if (Array.isArray(parsed) && parsed.length === 2 && parsed[0] && parsed[1]) {
          dateRange.value = parsed;
        }
      } catch { /* ignore */ }
    }
  }

  // User had a saved explicit range → honor it (gold /executive/custom path).
  if (dateRange.value && factoryId.value) {
    await loadDashboardData();
    return;
  }

  // Default = all history from the gold layer.
  await applyAllHistoryDefault();
  await loadDashboardData();
});

// P1 PERF fix: Watch only the charts sub-object, not the entire dashboardData deeply.
// deep:true on dashboardData caused full chart re-init when aiInsights arrived from LLM.
watch(() => dashboardData.value?.charts, (newCharts) => {
  if (newCharts) {
    nextTick(() => {
      initCharts(newCharts);
    });
  }
});

// ==================== API 调用 ====================

// Apr 24 2026 UX fallback helper: probe historical date ranges when current
// month is empty. Returns true if a non-empty range was loaded into dashboardData.
// Populates localStorage cache so subsequent visits skip the serial probe.
//
// Perf (Apr 24 late): cache hit still serial (1 req), cache miss parallelizes
// the 3 ladder probes (近90天 / 上年 / 前年) via Promise.allSettled — prior
// serial version added ~900ms on cold start. Picks non-empty in priority order
// (closest range first).
async function tryFallbackRanges(): Promise<boolean> {
  if (!factoryId.value) return false;
  const y = new Date().getFullYear();
  const iso = (d: Date): string => d.toISOString().slice(0, 10);
  const cacheKey = savedFallbackKey(factoryId.value);
  const cached = (() => {
    try { return JSON.parse(localStorage.getItem(cacheKey) || 'null') as { s: string; e: string; label: string } | null; }
    catch { return null; }
  })();

  type ProbeRange = [string, string, string];
  const extractData = (resp: { success?: boolean; data?: unknown }): DashboardResponse | null => {
    if (!resp.success || !resp.data) return null;
    const raw = resp.data as Record<string, unknown>;
    return (raw.data && typeof raw.data === 'object' && !Array.isArray(raw.data) && raw.code)
      ? (raw.data as DashboardResponse) : (resp.data as DashboardResponse);
  };
  const hasRealKpi = (d: DashboardResponse | null): boolean =>
    !!d && (d.kpiCards || []).some(c => c.rawValue != null && c.rawValue !== 0);
  // Apr 25 2026 P1 fix: chart-aware predicate. The Apr 23 Gold KPI flip
  // started returning non-empty kpiCards from the year fallback, but the
  // executive endpoint's `charts` payload (sales_trend / category_distribution)
  // can still be empty for the same range. When that happens, the user sees
  // "本月销售额 2064万" above + "暂无图表 — 数据正在分析中" below — visual
  // contradiction. Now the fallback chain prefers a range with BOTH non-empty
  // KPI AND non-empty charts (two-pass: first chart-and-kpi, then kpi-only).
  const hasNonEmptyCharts = (d: DashboardResponse | null): boolean => {
    if (!d) return false;
    const charts = d.charts || {};
    return Object.keys(charts).length > 0 && Object.values(charts).some(c => {
      const cfg = c as unknown as Record<string, unknown>;
      const series = cfg.series;
      if (Array.isArray(series) && series.length > 0) {
        return series.some(s => Array.isArray((s as Record<string, unknown>).data) && ((s as Record<string, unknown>).data as unknown[]).length > 0);
      }
      const data = cfg.data;
      return Array.isArray(data) && data.length > 0;
    });
  };

  const applyFound = (data: DashboardResponse, s: string, e: string, label: string) => {
    dashboardData.value = data;
    fallbackRangeLabel.value = label;
    fallbackDateRange.value = [s, e];
    if (factoryId.value) putCached(factoryId.value, 'system', data);
    try { localStorage.setItem(cacheKey, JSON.stringify({ s, e, label })); } catch {}
  };

  // 1. Cache first (synchronously serial — fastest path when known-good range cached)
  // Apr 25 2026: cache hit must satisfy BOTH KPI and charts — otherwise we'd
  // pin the user on a chart-empty range forever. If cached range fails the
  // chart check, fall through to ladder probe (a different range may have charts).
  if (cached) {
    try {
      const resp = await get(`/${factoryId.value}/smart-bi/dashboard/executive/custom?startDate=${cached.s}&endDate=${cached.e}`);
      const data = extractData(resp);
      if (hasRealKpi(data) && hasNonEmptyCharts(data)) {
        applyFound(data!, cached.s, cached.e, cached.label);
        return true;
      }
    } catch { /* fall through to parallel probe */ }
  }

  // 2. Ladder probes in parallel (cache missed or empty)
  const ladder: ProbeRange[] = [
    (() => { const e = new Date(); const s = new Date(); s.setDate(s.getDate() - 89); return [iso(s), iso(e), '近90天']; })(),
    [`${y - 1}-01-01`, `${y - 1}-12-31`, `${y - 1}全年`],
    [`${y - 2}-01-01`, `${y - 2}-12-31`, `${y - 2}全年`],
  ];
  // De-dupe against cache range (avoid re-firing the probe that just failed)
  const ladderFiltered = cached
    ? ladder.filter(([s, e]) => !(s === cached.s && e === cached.e))
    : ladder;

  const results = await Promise.allSettled(
    ladderFiltered.map(async ([s, e, label]) => {
      const resp = await get(`/${factoryId.value}/smart-bi/dashboard/executive/custom?startDate=${s}&endDate=${e}`);
      return { s, e, label, data: extractData(resp) };
    })
  );

  // Apr 25 2026 P1 fix: two-pass selection in ladder priority order.
  // Pass 1: prefer a range where BOTH KPI and charts are non-empty (no visual
  // contradiction). Pass 2: fall back to any range with non-empty KPI (legacy
  // behaviour — at least the KPI strip shows real numbers, charts may render
  // "暂无图表" if the server has no chart data for any range).
  for (const r of results) {
    if (r.status === 'fulfilled' && hasRealKpi(r.value.data) && hasNonEmptyCharts(r.value.data)) {
      applyFound(r.value.data!, r.value.s, r.value.e, r.value.label);
      return true;
    }
  }
  for (const r of results) {
    if (r.status === 'fulfilled' && hasRealKpi(r.value.data)) {
      applyFound(r.value.data!, r.value.s, r.value.e, r.value.label);
      return true;
    }
  }
  return false;
}

async function loadDashboardData() {
  if (!factoryId.value) {
    ElMessage.warning('未获取到工厂ID，请重新登录');
    return;
  }

  // Ensure AbortController exists so loadLLMInsights() can read the signal
  getSignal();
  loading.value = true;
  hasError.value = false;
  errorMessage.value = '';

  try {
    // If user picked a custom date range, route through /executive/custom
    // (which calls salesAnalysisService.getSalesOverview with the Gold-cutover code path).
    // Else fall back to the default period=month behavior.
    const url = dateRange.value
      ? `/${factoryId.value}/smart-bi/dashboard/executive/custom?startDate=${dateRange.value[0]}&endDate=${dateRange.value[1]}`
      : `/${factoryId.value}/smart-bi/dashboard/executive?period=month`;
    const response = await get(url);

    if (response.success && response.data) {
      // Handle double-wrapped response: interceptor wraps {code,data:{...}} into {success,data:{code,data:{...}}}
      const raw = response.data as Record<string, unknown>;
      const actualData = (raw.data && typeof raw.data === 'object' && !Array.isArray(raw.data) && raw.code)
        ? raw.data
        : raw;
      dashboardData.value = actualData as DashboardResponse;
      // FIX-12: cache system-view payload so 刷新 shows instant (5min TTL)
      if (factoryId.value) putCached(factoryId.value, 'system', actualData);
      // data loaded

      // Auto-switch: if system data is effectively empty (no KPIs with real values),
      // fall back to the best available uploaded data source
      const kpiCards = (actualData as DashboardResponse).kpiCards || [];
      const hasRealKpi = kpiCards.some(c => c.rawValue != null && c.rawValue !== 0);
      const charts = (actualData as DashboardResponse).charts || {};
      const hasCharts = Object.keys(charts).length > 0 &&
        Object.values(charts).some(c =>
          (c && 'series' in c && Array.isArray(c.series) && c.series.length > 0) ||
          (c && 'data' in c && Array.isArray(c.data) && c.data.length > 0)
        );

      // Apr 24 2026 UX P0-2/P0-3 fix: never auto-switch to uploads — picking a
      // random smoke Excel (e.g. gamma1c with 李四/王五/1001.0) as "dashboard
      // KPIs" is misleading. Apr 24 UX continuation: instead, when 本月 is
      // empty AND user hasn't picked a range, silently probe 近90天 → 上年 →
      // 前年 to show genuine historical Gold data (same fallback pattern as
      // Trends/RestaurantV2 KPI strip). A warning tag explains the switch.
      // Apr 25 2026 P1 fix: also trigger fallback when KPI is non-empty but
      // charts are empty — prior logic pinned the user on an all-chart-empty
      // range, creating a "本月销售额 2064万 + 暂无图表" contradiction.
      // WS2 (2026-06-02): data-source picker removed — never auto-switch to an
      // uploaded Excel. When the gold view is empty AND no explicit range,
      // silently probe 近90天 → 上年 → 前年 for genuine historical gold data
      // (a warning tag explains the switch). If all empty → keep the gold empty
      // state + CTA (no upload fallback).
      if (!dateRange.value && (!hasRealKpi || !hasCharts)) {
        await tryFallbackRanges();
      } else {
        fallbackRangeLabel.value = '';
        fallbackDateRange.value = null;
      }

      // Async load LLM insights (non-blocking, renders after KPIs+charts)
      loadLLMInsights();
    } else {
      throw new Error(response.message || '获取驾驶舱数据失败');
    }
  } catch (error) {
    console.error('加载驾驶舱数据失败:', error);
    hasError.value = true;
    errorMessage.value = error instanceof Error ? error.message : '加载数据失败，请稍后重试';
    ElMessage.error(errorMessage.value);
    dashboardData.value = null;

    // Apr 24 2026 UX P0-2/P0-3 fix: don't fallback to upload on error either.
    // Silent override with random upload's dynamic analysis masked the real
    // failure and showed misleading KPI. Keep the error visible to the user.
  } finally {
    loading.value = false;
  }
}

async function loadLLMInsights() {
  if (!factoryId.value || !dashboardData.value) return;
  const signal = abortController?.signal;
  insightsLoading.value = true;
  insightsTookLong.value = false;
  streamingInsightText.value = '';
  streamingInsightMeta.value = null;
  // Show "冷启中" hint after 5s (typical Python warm ~2s, cold ~8-10s)
  const longRunTimer = setTimeout(() => { insightsTookLong.value = true; }, 5000);
  try {
    const effectiveRange = dateRange.value || fallbackDateRange.value;

    // Phase 9 Apr 24: for custom-range path, prefer SSE streaming so first
    // token appears ~2-3s instead of user waiting 8-10s for full response.
    // Fall back to legacy JSON for period=month path (agent not wired there).
    if (effectiveRange) {
      const ok = await loadLLMInsightsStream(effectiveRange[0], effectiveRange[1], signal);
      if (ok) return;
      // SSE failed → fall through to legacy JSON path as backup
    }

    const insightsUrl = effectiveRange
      ? `/${factoryId.value}/smart-bi/dashboard/executive/insights/custom?startDate=${effectiveRange[0]}&endDate=${effectiveRange[1]}`
      : `/${factoryId.value}/smart-bi/dashboard/executive/insights?period=month`;
    const res = await get(insightsUrl, { timeout: 120000, signal });
    if (signal?.aborted) return;
    if (res.success && res.data) {
      const raw = res.data as Record<string, unknown>;
      const insights = (raw.data && Array.isArray(raw.data)) ? raw.data : (Array.isArray(raw) ? raw : []);
      if (insights.length > 0 && dashboardData.value) {
        const existing = dashboardData.value.aiInsights || [];
        dashboardData.value = {
          ...dashboardData.value,
          aiInsights: [...existing, ...insights]
        };
        insightTimestamp.value = new Date();
      }
    }
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') return;
    console.warn('LLM insights load failed (non-critical):', e);
  } finally {
    clearTimeout(longRunTimer);
    insightsLoading.value = false;
    insightsTookLong.value = false;
  }
}

// Phase 9 Apr 24: SSE streaming for LLM insights. Returns true on success
// (user sees tokens streaming live), false if the stream fails early so
// caller can fall back to the legacy JSON endpoint.
async function loadLLMInsightsStream(
  startDate: string,
  endDate: string,
  signal: AbortSignal | undefined,
): Promise<boolean> {
  if (!factoryId.value) return false;
  // cleanInsightText hoisted to component scope (used here for streamed deltas
  // AND in the aiInsights computed for cached/server insights).
  // Use the same key that request.ts interceptor reads
  const authHeader = localStorage.getItem('cretas_access_token') || '';
  const url = `/api/mobile/${factoryId.value}/smart-bi/dashboard/executive/insights/custom/stream?startDate=${startDate}&endDate=${endDate}`;

  try {
    const resp = await fetch(url, {
      method: 'GET',
      headers: {
        'Authorization': authHeader ? `Bearer ${authHeader}` : '',
        'Accept': 'text/event-stream',
      },
      signal,
    });
    if (!resp.ok || !resp.body) return false;

    const reader = resp.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    let accumulated = '';
    let done = false;
    let gotAnyDelta = false;

    while (!done) {
      const { value, done: readerDone } = await reader.read();
      if (readerDone) break;
      buffer += decoder.decode(value, { stream: true });
      // Cancel reader if the request was aborted mid-stream (navigation / reload),
      // not just break — otherwise fetch connection keeps consuming bytes.
      if (signal?.aborted) {
        try { await reader.cancel(); } catch { /* ignore */ }
        return gotAnyDelta;
      }
      // Parse SSE events (separated by blank line)
      let idx;
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const eventBlock = buffer.slice(0, idx).trim();
        buffer = buffer.slice(idx + 2);
        if (!eventBlock.startsWith('data:')) continue;
        const dataStr = eventBlock.replace(/^data:\s*/, '').trim();
        if (!dataStr) continue;
        try {
          const event = JSON.parse(dataStr);
          if (signal?.aborted) {
            try { await reader.cancel(); } catch { /* ignore */ }
            return gotAnyDelta;
          }
          if (event.type === 'meta') {
            streamingInsightMeta.value = {
              source: event.source,
              tokens_used_today: event.tokens_used_today,
            };
          } else if (event.type === 'delta' && event.text) {
            accumulated += event.text;
            streamingInsightText.value = cleanInsightText(accumulated);
            gotAnyDelta = true;
          } else if (event.type === 'done') {
            // Finalize: append as regular insight
            if (accumulated && dashboardData.value) {
              const existing = dashboardData.value.aiInsights || [];
              dashboardData.value = {
                ...dashboardData.value,
                aiInsights: [
                  ...existing,
                  { level: 'normal', category: 'AI 洞察', message: cleanInsightText(accumulated), actionSuggestion: null } as never
                ],
              };
              insightTimestamp.value = new Date();
            }
            streamingInsightText.value = '';
            done = true;
          } else if (event.type === 'error') {
            console.warn('SSE error event:', event.message);
            return gotAnyDelta;  // If we got some text, count as partial success
          }
        } catch { /* malformed event, skip */ }
      }
    }
    return gotAnyDelta;
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') return false;
    console.warn('SSE stream failed (will fallback):', e);
    return false;
  }
}

// ==================== 数据源管理 (WS2: 上传数据源路径已移除) ====================
// WS2 (2026-06-02): 驾驶舱永远从 gold 层聚合, 不再让用户选某次上传作为 KPI 来源。
// 原 loadDataSources / onDataSourceChange / loadDynamicDashboardData 已删除。

// ==================== 图表初始化 ====================

/**
 * Convert backend LegacyChartConfig (data[] + xAxisField/yAxisField) to
 * DashboardChartConfig format (series[] + xAxis.data) that the render
 * functions expect.
 */
function normalizeLegacyChart(config: AnyChartConfig): ChartConfig {
  if ('series' in config && Array.isArray(config.series)) return config as ChartConfig; // already in new format
  if (!('data' in config) || !Array.isArray(config.data) || config.data.length === 0) return config as ChartConfig;

  const legacy = config as { chartType: string; title?: string; xAxisField?: string; xaxisField?: string; yAxisField?: string; yaxisField?: string; data: Array<Record<string, unknown>> };
  const xField = legacy.xAxisField || legacy.xaxisField || 'date';
  const yField = legacy.yAxisField || legacy.yaxisField || 'amount';

  const xData = legacy.data.map(d => String(d[xField] || ''));
  const yData = legacy.data.map(d => Number(d[yField]) || 0);

  return {
    chartType: legacy.chartType,
    title: legacy.title,
    xAxis: { data: xData },
    series: [{
      name: legacy.title || '数据',
      type: (legacy.chartType || 'line').toLowerCase(),
      data: yData,
    }],
  } as ChartConfig;
}

function initCharts(charts?: Record<string, AnyChartConfig>) {
  const trend = charts?.['sales_trend'] || charts?.['销售趋势'];
  let pie = charts?.['category_distribution'] || charts?.['产品占比']
    || charts?.['类别分布'] || charts?.['产品销售占比'] || charts?.['产品分布'];
  // Fallback: find first pie-type chart by scanning all entries
  if (!pie && charts) {
    for (const [, cfg] of Object.entries(charts)) {
      const c = cfg as unknown as Record<string, unknown>;
      if (String(c.chartType).toLowerCase() === 'pie' || (Array.isArray(c.series) && String((c.series as Record<string, unknown>[])[0]?.type).toLowerCase() === 'pie')) {
        pie = cfg;
        break;
      }
    }
  }
  initTrendChart(trend ? normalizeLegacyChart(trend) : undefined);
  initPieChart(pie ? normalizeLegacyChart(pie) : undefined);
  connectCharts();
}

function initTrendChart(chartConfig?: ChartConfig) {
  if (!trendChartRef.value) return;

  if (trendChart) {
    trendChart.dispose();
  }
  trendChart = echarts.init(trendChartRef.value, isDarkMode.value ? 'cretas-dark' : 'cretas');

  // 如果有后端数据，使用后端数据
  hasTrendData.value = !!(chartConfig && chartConfig.series && chartConfig.series.length > 0);

  // C Apr 17 2026: 根据 x-axis 第一个值判断是"趋势"(时间) 还是"排行"(类别)
  // Apr 24 2026: drop zero/null datapoints so "all values = 0" doesn't produce a flat
  // line-at-zero chart that looks broken. Re-evaluate hasTrendData after filtering.
  if (hasTrendData.value && chartConfig) {
    const xData = (chartConfig.xAxis as { data?: unknown[] } | undefined)?.data || [];
    for (const s of chartConfig.series) {
      if (Array.isArray(s.data)) {
        const zipped = (s.data as (number | null | undefined)[]).map((v, i) => ({
          x: xData[i], v: (typeof v === 'number' && isFinite(v) && v !== 0) ? v : null
        }));
        const kept = zipped.filter(z => z.v !== null);
        if (kept.length > 0 && kept.length !== zipped.length) {
          s.data = kept.map(z => z.v as number);
          // Keep xAxis alignment — need to rebuild it too
          (chartConfig.xAxis as { data?: unknown[] }).data = kept.map(z => z.x);
        }
      }
    }
    const seriesHasData = chartConfig.series.some(s =>
      Array.isArray(s.data) && (s.data as unknown[]).length > 0
    );
    hasTrendData.value = seriesHasData;
  }

  if (hasTrendData.value) {
    const xData = (chartConfig?.xAxis as { data?: unknown[] } | undefined)?.data || [];
    const firstX = xData.length > 0 ? String(xData[0]) : '';
    const isTime = /^\d{4}[-/]\d{1,2}/.test(firstX) ||
                   /\d{1,4}[年月日]/.test(firstX) ||
                   /^Q[1-4]$/i.test(firstX) ||
                   /^\d{4}$/.test(firstX);
    trendChartTitle.value = isTime ? '销售趋势' : '按类别排行';
  } else {
    trendChartTitle.value = '销售趋势';
  }

  if (hasTrendData.value) {
    const option: echarts.EChartsOption = {
      tooltip: {
        trigger: 'axis',
        confine: true,
        axisPointer: { type: 'cross' }
      },
      legend: {
        data: chartConfig.legend?.data || chartConfig.series.map(s => s.name),
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
        data: chartConfig.xAxis?.data || []
      },
      yAxis: (chartConfig.series.length > 1 && chartConfig.series.some(s => s.yAxisIndex != null)) ? [
        {
          type: 'value',
          name: chartConfig.series[0]?.name || '销售额',
          axisLabel: {
            formatter: formatAxisValue
          }
        },
        {
          type: 'value',
          name: chartConfig.series[1]?.name || '利润',
          axisLabel: {
            formatter: formatAxisValue
          }
        }
      ] : {
        type: 'value',
        axisLabel: {
          formatter: formatAxisValue
        }
      },
      series: chartConfig.series.map((s, index) => ({
        name: s.name,
        type: 'line',
        smooth: true,
        yAxisIndex: s.yAxisIndex ?? 0,
        data: s.data,
        areaStyle: index === 0 ? {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(45, 139, 87, 0.3)' },
            { offset: 1, color: 'rgba(45, 139, 87, 0.05)' }
          ])
        } : undefined,
        lineStyle: { width: 3, color: index === 0 ? '#2D8B57' : '#36B37E' },
        itemStyle: { color: index === 0 ? '#2D8B57' : '#36B37E' }
      }))
    };
    enhanceChartDefaults(option as Record<string, unknown>);
    trendChart.setOption(option);
  }
}

function initPieChart(chartConfig?: ChartConfig) {
  if (!pieChartRef.value) return;

  if (pieChart) {
    pieChart.dispose();
  }
  pieChart = echarts.init(pieChartRef.value, isDarkMode.value ? 'cretas-dark' : 'cretas');

  // 如果有后端数据，使用后端数据
  hasPieData.value = !!(chartConfig && chartConfig.series && chartConfig.series.length > 0);
  if (hasPieData.value) {
    const seriesData = chartConfig!.series[0];
    // 假设后端返回的数据格式是 { name, data } 或 { data: [{name, value}] }
    const pieDataRaw = Array.isArray(seriesData.data)
      ? seriesData.data.map((value, index) => {
          // Support multiple data formats: number, {value}, {name, value}
          const isObj = typeof value === 'object' && value !== null;
          const numValue = typeof value === 'number' ? value : (isObj ? Number((value as Record<string, unknown>).value || 0) : 0);
          const nameFromData = isObj ? String((value as Record<string, unknown>).name || '') : '';
          return {
            value: numValue,
            name: nameFromData || chartConfig.xAxis?.data?.[index] || seriesData.name || `产品${index + 1}`,
            itemStyle: { color: getPieColor(index) }
          };
        })
      : [];
    // Drop zero-value and sentinel "合计/total" slices that pollute the donut with "0%" labels.
    const pieData = pieDataRaw.filter(p =>
      p.value > 0 && !/^(合计|总计|total|grand[_ ]?total)$/i.test(p.name || '')
    );
    // All-zero or only-sentinels => fall back to empty state instead of rendering a useless donut.
    if (pieData.length === 0) {
      hasPieData.value = false;
      return;
    }

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
              fontSize: 16,
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
    enhanceChartDefaults(option as Record<string, unknown>);
    pieChart.setOption(option);

    // Cross-filter: click pie slice → highlight in trend chart
    pieChart.on('click', handlePieClick);
  }
}

function getPieColor(index: number): string {
  return CHART_COLORS[index % CHART_COLORS.length];
}

// Cross-filter: click pie slice → highlight matching xAxis index in trend chart
function handlePieClick(params: { name?: string }) {
  if (!params.name) return;
  // Toggle: click same → clear
  if (crossFilterValue.value === params.name) {
    crossFilterValue.value = null;
    clearCrossFilter();
  } else {
    crossFilterValue.value = params.name;
    applyCrossFilter(params.name);
  }
}

function applyCrossFilter(categoryName: string) {
  if (!trendChart) return;
  // Try to match category name in trend xAxis data
  const option = trendChart.getOption() as Record<string, unknown>;
  const xAxis = option.xAxis;
  const xData = Array.isArray(xAxis) ? (xAxis[0] as Record<string, unknown>)?.data : (xAxis as Record<string, unknown>)?.data;
  if (!Array.isArray(xData)) return;

  // For pie → trend, we highlight the series whose name matches the category
  // Since trend uses time-series xAxis, highlight all data points of matching series
  trendChart.dispatchAction({ type: 'downplay' });
  const seriesOpt = option.series;
  if (Array.isArray(seriesOpt)) {
    const matchIdx = seriesOpt.findIndex((s: Record<string, unknown>) =>
      typeof s.name === 'string' && s.name.includes(categoryName)
    );
    if (matchIdx >= 0) {
      trendChart.dispatchAction({ type: 'highlight', seriesIndex: matchIdx });
    }
  }

  // Also highlight the clicked pie slice
  if (pieChart) {
    pieChart.dispatchAction({ type: 'downplay' });
    const pieSeries = (pieChart.getOption() as Record<string, unknown>).series;
    if (Array.isArray(pieSeries) && pieSeries[0]) {
      const pieData = (pieSeries[0] as Record<string, unknown>).data as Array<Record<string, unknown>>;
      if (Array.isArray(pieData)) {
        const pieIdx = pieData.findIndex(d => d.name === categoryName);
        if (pieIdx >= 0) {
          pieChart.dispatchAction({ type: 'highlight', dataIndex: pieIdx });
        }
      }
    }
  }
}

function clearCrossFilter() {
  trendChart?.dispatchAction({ type: 'downplay' });
  pieChart?.dispatchAction({ type: 'downplay' });
}

// ECharts connect — tooltip linkage between trend and pie charts
function connectCharts() {
  if (trendChart && pieChart) {
    echarts.connect([trendChart, pieChart]);
  }
}

// ResizeObserver-based chart resize (also handles sidebar toggle)
useChartResize(dashboardRef, () => {
  trendChart?.resize();
  pieChart?.resize();
});

// ==================== 工具函数 ====================

function mapInsightLevel(level: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (level) {
    case 'GREEN': return 'success';
    case 'YELLOW': return 'warning';
    case 'RED': return 'danger';
    case 'INFO':
    default: return 'info';
  }
}

function getCategoryTitle(level: string): string {
  switch (level) {
    case 'GREEN': return '正向趋势';
    case 'YELLOW': return '需要关注';
    case 'RED': return '风险预警';
    case 'INFO':
    default: return '数据洞察';
  }
}

function formatMoney(value: number | null | undefined): string {
  if (value == null) return '--';
  return formatNumber(value, 1);
}

/**
 * Format KPI display value - shows actual number including 0, or '--' if null/no-data
 */
function formatKpiValue(value: number | null | undefined): string {
  if (value === null || value === undefined) return '--';
  return formatMoney(value);
}

/** Sparkline tooltip: shows per-point values with date labels, plus summary */
function sparklineTooltip(data: number[], labels?: string[]): string {
  if (!data || data.length < 2) return '';
  const latest = data[data.length - 1];
  const min = Math.min(...data);
  const max = Math.max(...data);

  // Build per-point detail rows (show last N points to keep tooltip compact)
  const maxPoints = 8;
  const startIdx = Math.max(0, data.length - maxPoints);
  const pointRows: string[] = [];
  for (let i = startIdx; i < data.length; i++) {
    const label = labels && labels[i] ? labels[i] : `#${i + 1}`;
    const marker = i === data.length - 1 ? ' <b>(最新)</b>' : '';
    pointRows.push(`${label}: ${formatMoney(data[i])}${marker}`);
  }
  if (startIdx > 0) {
    pointRows.unshift(`<span style="color:#999">...前${startIdx}项已省略</span>`);
  }

  return pointRows.join('<br>')
    + `<br><hr style="margin:4px 0;border:none;border-top:1px solid rgba(255,255,255,0.15)">`
    + `最高: ${formatMoney(max)} / 最低: ${formatMoney(min)}`;
}

function formatPercent(value: number): string {
  return (value >= 0 ? '+' : '') + value.toFixed(1) + '%';
}

function getGrowthClass(value: number): string {
  return value >= 0 ? 'growth-up' : 'growth-down';
}

function goToAIQuery(question?: string) {
  // WS4 把 /smart-bi/query 改成 redirect (→ /smart-bi/analysis?tab=query), 原命名路由
  // 'SmartBIQuery' 不再 resolve, router.push({name}) 会抛错导致快捷问答 chip 卡住不跳 (#6)。
  // 改用 path /smart-bi/query (redirect 保留 query → 目标页自动读 q 并提交)。
  if (question) {
    router.push({ path: '/smart-bi/query', query: { q: question } });
  } else {
    router.push({ path: '/smart-bi/query' });
  }
}

function getInsightTagType(type: string): 'success' | 'warning' | 'danger' | 'info' {
  return type as 'success' | 'warning' | 'danger' | 'info';
}

function handleRefresh() {
  // WS2: always gold-backed (data-source picker removed).
  loadDashboardData();
}

// ==================== 生命周期清理 ====================

import { onUnmounted } from 'vue';
onUnmounted(() => {
  // Cancel any pending API requests (prevents console errors after navigation)
  if (abortController) abortController.abort();
  trendChart?.dispose();
  pieChart?.dispose();
});
</script>

<template>
  <div ref="dashboardRef" class="smart-bi-dashboard" :data-theme="isDarkMode ? 'dark' : 'light'" role="main" aria-label="经营驾驶舱">
    <div class="page-header">
      <div class="header-left">
        <h1>经营驾驶舱</h1>
        <span class="subtitle">智能数据分析 · 业务经营一站式洞察</span>
      </div>
      <div class="header-right">
 <el-button size="small" @click="toggleDarkMode" :title="isDarkMode ? '切换亮色' : '切换暗色'" :aria-label="isDarkMode ? '切换亮色模式' : '切换暗色模式'">{{ isDarkMode ? '️' : '' }}</el-button>
        <el-button type="primary" :icon="Refresh" @click="handleRefresh" :loading="loading">刷新数据</el-button>
        <el-button type="success" :icon="ChatDotRound" @click="goToAIQuery()">AI 问答</el-button>
      </div>
    </div>

    <!-- 时间范围 (WS2: 数据源选择器已移除 — 永远从 gold 层聚合, 默认全部历史) -->
    <el-card class="datasource-card">
      <div class="datasource-bar">
        <div class="datasource-item">
          <span class="datasource-label">
            <el-icon><Clock /></el-icon>
            时间范围
          </span>
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始"
            end-placeholder="结束"
            :shortcuts="dateRangeShortcuts"
            value-format="YYYY-MM-DD"
            style="width: 280px"
            clearable
            @change="onDateRangeChange"
          />
          <span v-if="dateRange && !fallbackRangeLabel && isFullHistory" class="datasource-meta" style="margin-left: 8px;">全部历史 · {{ dateRange[0] }} 至 {{ dateRange[1] }}</span>
          <span v-else-if="dateRange && !fallbackRangeLabel" class="datasource-meta" style="margin-left: 8px;">自定义区间 · {{ dateRange[0] }} 至 {{ dateRange[1] }}<el-button link type="primary" size="small" style="margin-left: 4px;" @click="resetToFullHistory">查看全部历史</el-button></span>
          <span v-else-if="fallbackRangeLabel" class="datasource-meta" style="margin-left: 8px; color: #E6A23C;">本月无数据 · 显示 {{ fallbackRangeLabel }}</span>
          <span v-else class="datasource-meta" style="margin-left: 8px;">默认: 本月</span>
        </div>
      </div>
    </el-card>

    <!-- 错误状态 -->
    <el-alert
      v-if="hasError"
      :title="errorMessage"
      type="error"
      show-icon
      closable
      class="error-alert"
      @close="hasError = false"
    >
      <el-button size="small" type="primary" @click="handleRefresh" style="margin-top: 8px;">重试</el-button>
    </el-alert>

    <!-- Empty state guidance when no data -->
    <SmartBIEmptyState
      v-if="!loading && !hasError && !hasData"
      :type="canUpload ? 'no-data' : 'read-only'"
      :showAction="canUpload"
      @action="goToUpload"
    />

    <!-- Apr 24 UX: fallback-range notice (本月 empty, auto-switched to historical) -->
    <el-alert
      v-if="fallbackRangeLabel && !dateRange && !loading"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 12px;"
    >
      <template #title>
        本月暂无销售数据,已自动显示 <strong>{{ fallbackRangeLabel }}</strong> 的历史数据。如需查看其他区间,请使用上方时间范围选择器。
      </template>
    </el-alert>

    <!-- KPI 卡片区 -->
    <el-row v-if="loading && !kpiData.totalRevenue" :gutter="16" class="kpi-section" aria-label="KPI指标加载中">
      <el-col :xs="24" :sm="12" :md="6" v-for="i in 4" :key="i">
        <el-card class="kpi-card"><ChartSkeleton type="kpi" /></el-card>
      </el-col>
    </el-row>
    <el-row v-else :gutter="16" class="kpi-section kpi-fade-in" aria-label="KPI指标" aria-live="polite" :aria-busy="loading">
      <el-col v-if="canViewPrice" :xs="24" :sm="12" :md="6">
        <CapabilityGate card-id="dashboard_revenue_month" :requires="['date', 'net_amount']" :force="isGoldMode">
        <el-card class="kpi-card revenue">
          <div class="kpi-icon">
            <el-icon><DataLine /></el-icon>
          </div>
          <div class="kpi-content">
            <div class="kpi-label">{{ kpiData.revenueLabel || '本月销售额' }}</div>
            <div class="kpi-value-row">
              <div class="kpi-value">{{ formatKpiValue(kpiData.totalRevenue) }}</div>
              <el-tooltip v-if="kpiSparklines.revenue.length >= 2" :content="sparklineTooltip(kpiSparklines.revenue, kpiSparklines.labels)" placement="top" :show-after="300" raw-content>
                <svg class="kpi-sparkline" width="60" height="22" viewBox="0 0 60 22">
                  <path :d="kpiSparklinePaths.revenue.path" fill="none" :stroke="kpiSparklinePaths.revenue.color" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </el-tooltip>
            </div>
            <div class="kpi-trend" :class="getGrowthClass(kpiData.revenueGrowth!)" v-if="kpiData.totalRevenue !== null && kpiData.revenueGrowth != null && kpiData.revenueGrowth !== 0">
              <el-icon v-if="kpiData.revenueGrowth >= 0"><ArrowUp /></el-icon>
              <el-icon v-else><ArrowDown /></el-icon>
              <span>{{ formatPercent(kpiData.revenueGrowth) }}</span>
              <span class="vs-label">环比</span>
            </div>
            <div class="kpi-trend" v-else-if="kpiData.totalRevenue === null">
              <span class="vs-label">暂无数据</span>
            </div>
          </div>
        </el-card>
        </CapabilityGate>
      </el-col>
      <el-col v-if="canViewPrice" :xs="24" :sm="12" :md="6">
        <CapabilityGate card-id="dashboard_avg_bill" :requires="['source_bill_no', 'net_amount']" :force="isGoldMode">
        <el-card class="kpi-card profit">
          <div class="kpi-icon">
            <el-icon><Histogram /></el-icon>
          </div>
          <div class="kpi-content">
            <div class="kpi-label">{{ kpiData.profitLabel || '本月利润' }}</div>
            <div class="kpi-value-row">
              <div class="kpi-value">{{ kpiData.profitUnit === '%' ? (kpiData.totalProfit != null ? kpiData.totalProfit.toFixed(1) + '%' : '--') : formatKpiValue(kpiData.totalProfit) }}</div>
              <el-tooltip v-if="kpiSparklines.profit.length >= 2" :content="sparklineTooltip(kpiSparklines.profit, kpiSparklines.labels)" placement="top" :show-after="300" raw-content>
                <svg class="kpi-sparkline" width="60" height="22" viewBox="0 0 60 22">
                  <path :d="kpiSparklinePaths.profit.path" fill="none" :stroke="kpiSparklinePaths.profit.color" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </el-tooltip>
            </div>
            <div class="kpi-trend" :class="getGrowthClass(kpiData.profitGrowth!)" v-if="kpiData.totalProfit !== null && kpiData.profitGrowth != null && kpiData.profitGrowth !== 0">
              <el-icon v-if="kpiData.profitGrowth >= 0"><ArrowUp /></el-icon>
              <el-icon v-else><ArrowDown /></el-icon>
              <span>{{ formatPercent(kpiData.profitGrowth) }}</span>
              <span class="vs-label">环比</span>
            </div>
            <div class="kpi-trend" v-else-if="kpiData.totalProfit === null">
              <span class="vs-label">暂无数据</span>
            </div>
          </div>
        </el-card>
        </CapabilityGate>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <CapabilityGate card-id="dashboard_order_count" :requires="['date', 'source_bill_no']" :force="isGoldMode">
        <el-card class="kpi-card orders">
          <div class="kpi-icon">
            <el-icon><Goods /></el-icon>
          </div>
          <div class="kpi-content">
            <div class="kpi-label">{{ kpiData.orderLabel || '订单数量' }}</div>
            <div class="kpi-value-row">
              <div class="kpi-value">{{ kpiData.orderCount != null ? formatCount(kpiData.orderCount) : '--' }}</div>
              <el-tooltip v-if="kpiSparklines.orders.length >= 2" :content="sparklineTooltip(kpiSparklines.orders, kpiSparklines.labels)" placement="top" :show-after="300" raw-content>
                <svg class="kpi-sparkline" width="60" height="22" viewBox="0 0 60 22">
                  <path :d="kpiSparklinePaths.orders.path" fill="none" :stroke="kpiSparklinePaths.orders.color" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </el-tooltip>
            </div>
            <div class="kpi-trend" :class="getGrowthClass(kpiData.orderGrowth!)" v-if="kpiData.orderCount !== null && kpiData.orderGrowth != null && kpiData.orderGrowth !== 0">
              <el-icon v-if="kpiData.orderGrowth >= 0"><ArrowUp /></el-icon>
              <el-icon v-else><ArrowDown /></el-icon>
              <span>{{ formatPercent(kpiData.orderGrowth) }}</span>
              <span class="vs-label">环比</span>
            </div>
            <div class="kpi-trend" v-else-if="kpiData.orderCount === null">
              <span class="vs-label">暂无数据</span>
            </div>
          </div>
        </el-card>
        </CapabilityGate>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <CapabilityGate card-id="dashboard_active_customers" :requires="['customer_count']" :force="isGoldMode">
        <el-card class="kpi-card customers">
          <div class="kpi-icon">
            <el-icon><Medal /></el-icon>
          </div>
          <div class="kpi-content">
            <div class="kpi-label">{{ kpiData.customerLabel || '活跃客户' }}</div>
            <div class="kpi-value-row">
              <div class="kpi-value" :class="kpiData.customerUnit === '%' && kpiData.customerCount != null ? getGrowthClass(kpiData.customerCount) : ''">{{ kpiData.customerUnit === '%' ? (kpiData.customerCount != null ? (kpiData.customerCount >= 0 ? '+' : '') + kpiData.customerCount.toFixed(1) + '%' : '--') : (kpiData.customerCount != null ? formatCount(kpiData.customerCount) : '--') }}</div>
              <el-tooltip v-if="kpiSparklines.customers.length >= 2" :content="sparklineTooltip(kpiSparklines.customers, kpiSparklines.labels)" placement="top" :show-after="300" raw-content>
                <svg class="kpi-sparkline" width="60" height="22" viewBox="0 0 60 22">
                  <path :d="kpiSparklinePaths.customers.path" fill="none" :stroke="kpiSparklinePaths.customers.color" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </el-tooltip>
            </div>
            <div class="kpi-trend" :class="getGrowthClass(kpiData.customerGrowth!)" v-if="kpiData.customerCount !== null && kpiData.customerGrowth != null && kpiData.customerGrowth !== 0">
              <el-icon v-if="kpiData.customerGrowth >= 0"><ArrowUp /></el-icon>
              <el-icon v-else><ArrowDown /></el-icon>
              <span>{{ formatPercent(kpiData.customerGrowth) }}</span>
              <span class="vs-label">环比</span>
            </div>
            <div class="kpi-trend" v-else-if="kpiData.customerCount === null">
              <span class="vs-label">暂无数据</span>
            </div>
          </div>
        </el-card>
        </CapabilityGate>
      </el-col>
    </el-row>

    <!-- 排行榜区 -->
    <el-row :gutter="16" class="ranking-section" v-loading="loading" aria-label="排行榜">
      <el-col v-if="departmentRanking.length > 0" :xs="24" :md="regionRanking.length > 0 ? 12 : 24">
        <CapabilityGate card-id="dashboard_dept_ranking" :requires="['staff_name', 'net_amount']">
        <el-card class="ranking-card">
          <template #header>
            <div class="card-header">
              <el-icon><Medal /></el-icon>
              <span>部门业绩排行</span>
            </div>
          </template>
          <div class="ranking-list">
            <div
              v-for="(item, index) in departmentRanking"
              :key="item.name"
              class="ranking-item"
            >
              <div class="rank-badge" :class="'rank-' + (index + 1)">
                {{ index + 1 }}
              </div>
              <div class="rank-info">
                <div class="rank-name">{{ item.name }}</div>
                <div class="rank-value">{{ formatMoney(item.sales) }}</div>
              </div>
              <div class="rank-growth" :class="getGrowthClass(item.growth)">
                {{ formatPercent(item.growth) }}
              </div>
            </div>
          </div>
        </el-card>
        </CapabilityGate>
      </el-col>
      <!-- P2-18: 区域销售分布空时整个 col 隐藏, 部门排行 col 自动扩宽到 24. 避免大片"暂无区域销售数据" 占屏 -->
      <el-col
        v-if="regionRanking.length > 0"
        :xs="24"
        :md="departmentRanking.length > 0 ? 12 : 24"
      >
        <CapabilityGate card-id="dashboard_region_sales" :requires="['store_name', 'net_amount']">
        <el-card class="ranking-card">
          <template #header>
            <div class="card-header">
              <el-icon><Location /></el-icon>
              <span>区域销售分布</span>
            </div>
          </template>
          <div class="ranking-list">
            <div
              v-for="item in regionRanking"
              :key="item.name"
              class="ranking-item region-item"
            >
              <div class="region-name">{{ item.name }}</div>
              <div class="region-bar-wrapper">
                <div class="region-bar" :class="'rank-bar-' + Math.min(regionRanking.indexOf(item), 3)" :style="{ width: item.percentage + '%' }"></div>
              </div>
              <div class="region-value">
                <span class="value">{{ formatMoney(item.sales) }}</span>
                <span class="percent">{{ item.percentage }}%</span>
              </div>
            </div>
          </div>
        </el-card>
        </CapabilityGate>
      </el-col>
    </el-row>

    <!-- Cross-filter indicator -->
    <div v-if="crossFilterValue" class="cross-filter-bar">
      <span>已筛选: <strong>{{ crossFilterValue }}</strong></span>
      <el-button size="small" text type="primary" @click="crossFilterValue = null; clearCrossFilter()">清除过滤</el-button>
    </div>

    <!-- 图表区 -->
    <!-- Apr 25 2026 P1 fix: hide chart cards visually (v-show) when no chart
         data comes back from the executive endpoint. Showing "暂无图表 - 数据
         正在分析中,图表即将生成..." right under "本月销售额 2064万" was a
         visual contradiction (Apr 24 audit finding). Gold-backed KPI cutover
         added kpiCards but did not include sales_trend / category_distribution
         payloads — the Week 6 TemplateGrid below already provides chart
         analytics from the materialised templates, so the empty placeholder
         here is dead space and confuses the customer. Use v-show (not v-if)
         so the chart-container ref stays in the DOM, allowing initCharts to
         find the ref when data does arrive (e.g. user picks a different
         range or upload). Skeletons during loading still render so the
         layout doesn't pop on first paint. -->
    <el-row
      v-show="loading || hasTrendData || hasPieData"
      :gutter="16"
      class="chart-section"
      aria-label="图表区域"
    >
      <el-col :xs="24" :lg="14" v-show="loading || hasTrendData">
        <CapabilityGate card-id="dashboard_sales_trend" :requires="['date', 'net_amount']">
        <el-card class="chart-card">
          <template #header>
            <div class="card-header">
              <el-icon><TrendCharts /></el-icon>
              <span>{{ trendChartTitle }}</span>
            </div>
          </template>
          <ChartSkeleton v-if="loading && !hasTrendData" type="chart" />
          <div ref="trendChartRef" class="chart-container" v-show="hasTrendData"></div>
          <ChartInsight v-if="trendInsight" :insight="trendInsight" depth="detailed" />
        </el-card>
        </CapabilityGate>
      </el-col>
      <el-col :xs="24" :lg="10" v-show="loading || hasPieData">
        <CapabilityGate card-id="dashboard_product_share" :requires="['combo_string', 'net_amount']">
        <el-card class="chart-card">
          <template #header>
            <div class="card-header">
              <el-icon><Histogram /></el-icon>
              <span>产品类别占比</span>
            </div>
          </template>
          <ChartSkeleton v-if="loading && !hasPieData" type="chart" />
          <div ref="pieChartRef" class="chart-container" v-show="hasPieData"></div>
          <ChartInsight v-if="categoryInsight" :insight="categoryInsight" depth="detailed" />
        </el-card>
        </CapabilityGate>
      </el-col>
    </el-row>

    <!-- AI 洞察区 -->
    <el-row :gutter="16" class="insight-section" aria-label="AI智能洞察" aria-live="polite" :aria-busy="loading">
      <el-col :span="24">
        <el-card class="insight-card" v-loading="loading">
          <template #header>
            <div class="card-header">
              <el-icon><ChatDotRound /></el-icon>
              <span>AI 智能洞察</span>
              <span v-if="insightTimestamp" class="insight-header-timestamp">
                生成于 {{ insightTimestamp.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}
              </span>
            </div>
          </template>
          <!-- Phase 9 Apr 24: live streaming insight text (SSE) — shows chars arriving -->
          <div v-if="streamingInsightText" class="insight-streaming" role="status">
            <el-tag size="small" type="info">
              <el-icon class="is-loading"><Loading /></el-icon> 生成中
            </el-tag>
            <div class="streaming-text">{{ streamingInsightText }}<span class="cursor">▌</span></div>
          </div>

          <!-- Apr 24 UX: skeleton while LLM insights are being fetched (1-10s async) -->
          <div v-else-if="insightsLoading && aiInsights.length === 0" class="insight-loading" role="status">
            <el-skeleton :rows="3" animated />
            <p class="insight-loading-hint">
              <el-icon class="is-loading"><Loading /></el-icon>
              {{ insightsTookLong ? 'AI 分析首次运行需 5-10 秒 (大模型冷启动)...' : 'AI 智能洞察生成中...' }}
            </p>
          </div>

          <div class="insight-list" role="list" v-else-if="aiInsights.length > 0">
            <div
              v-for="(insight, index) in (insightsExpanded ? aiInsights : aiInsights.slice(0, INSIGHT_COLLAPSE_LIMIT))"
              :key="index"
              class="insight-item"
              :class="'insight-' + insight.type"
              role="listitem"
            >
              <el-tag :type="getInsightTagType(insight.type)" size="small">
                {{ insight.title }}
              </el-tag>
              <span class="insight-content">
                <template v-for="(seg, si) in parseInsightCitations(insight.content)" :key="si">
                  <span
                    v-if="seg.chartIndex != null"
                    class="insight-citation"
                    :title="'来源: ' + seg.chartTitle"
                    @click="scrollToChart(seg.chartIndex)"
                  >{{ seg.text }}<sup>[{{ seg.chartIndex + 1 }}]</sup></span>
                  <span v-else>{{ seg.text }}</span>
                </template>
              </span>
              <span v-if="insight.suggestion" class="insight-suggestion">
                <el-icon aria-label="建议" role="img"><InfoFilled /></el-icon> {{ insight.suggestion }}
              </span>
            </div>
            <div class="insight-meta" v-if="insightTimestamp">
              <el-icon><Clock /></el-icon>
              <span class="insight-timestamp">{{ formatInsightTime(insightTimestamp) }}</span>
              <span class="insight-citation-legend" v-if="chartTitles.length > 0">
                <span v-for="(title, ci) in chartTitles" :key="ci" class="citation-ref" @click="scrollToChart(ci)">
                  [{{ ci + 1 }}] {{ title }}
                </span>
              </span>
            </div>
            <div v-if="aiInsights.length > INSIGHT_COLLAPSE_LIMIT" class="insight-toggle">
              <el-button text type="primary" size="small" @click="insightsExpanded = !insightsExpanded">
                {{ insightsExpanded ? '收起' : `展开更多 (${aiInsights.length - INSIGHT_COLLAPSE_LIMIT} 条)` }}
                <el-icon class="toggle-icon" :class="{ expanded: insightsExpanded }"><ArrowDown /></el-icon>
              </el-button>
            </div>
          </div>
          <SmartBIEmptyState v-else type="no-analysis" :show-action="false" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 快捷问答入口 -->
    <el-row :gutter="16" class="quick-qa-section" aria-label="快捷问答">
      <el-col :span="24">
        <el-card class="quick-qa-card">
          <template #header>
            <div class="card-header">
              <el-icon><ChatDotRound /></el-icon>
              <span>快捷问答</span>
            </div>
          </template>
          <div class="quick-questions">
            <el-button
              v-for="(q, index) in quickQuestions"
              :key="index"
              round
              @click="goToAIQuery(q.text)"
            >
              <el-icon><component :is="q.icon" /></el-icon>
              {{ q.text }}
            </el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- #56 价值可视化回馈回路: 本月价值回馈条 (诊断引擎已算出的省钱/改善金额, 月度+年化
         两口径)。业态门控仅 RESTAURANT。解决"门店看不到价值 → 配合度崩塌"死因。 -->
    <ValueFeedbackStrip
      v-if="isRestaurantTenant"
      :factory-id="factoryId || ''"
      :show="isRestaurantTenant"
    />

    <!-- 餐饮业态: Gold 全量营收分析 (门店排行 + 渠道占比), 取代通用 schema-driven
         模板卡 — 后者对青花椒误选"星级分"显示累计 SUM bug 且老板不关心 (May 29 2026,
         per 合理性分析: 营收类指标老板每天必看, 星级评价稀疏过时降级)。制造业仍用模板卡。 -->
    <RestaurantGoldGrid
      v-if="isRestaurantTenant"
      :factory-id="factoryId || ''"
      :date-range="dateRange"
    />
    <!-- Week 6 Template Surfacing: show analysis results for this page -->
    <TemplateGrid v-else page-key="dashboard" :factory-id="factoryId || ''" />

    <!-- Day 8 数据织网 Sub-Project A: bottom CTA prompting users to unlock
         capability-gated cards by uploading more comprehensive data -->
    <UnlockMoreCTA />
  </div>
</template>

<style lang="scss" scoped>
.smart-bi-dashboard {
  padding: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;

  .header-left {
    h1 {
      margin: 0;
      font-size: 24px;
      font-weight: 600;
      color: var(--el-text-color-primary, #1A2332);
    }

    .subtitle {
      font-size: 13px;
      color: var(--color-text-secondary);
    }
  }

  .header-right {
    display: flex;
    gap: 12px;
  }
}

// 数据源选择器
.datasource-card {
  margin-bottom: 16px;
  border-radius: 8px;

  :deep(.el-card__body) {
    padding: 12px 16px;
  }

  .datasource-bar {
    display: flex;
    align-items: center;
    gap: 16px;
  }

  .datasource-item {
    display: flex;
    align-items: center;
    gap: 8px;

    .datasource-label {
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 13px;
      color: var(--el-text-color-regular, #4A5568);
      white-space: nowrap;
    }
  }
}

.datasource-option {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;

  .datasource-meta {
    font-size: 12px;
    color: #7A8599;
  }
}

.error-alert {
  margin-bottom: 16px;
}

.empty-state-card {
  margin-bottom: 24px;
  border-radius: 12px;
  text-align: center;
  padding: 20px 0;
}

// KPI 卡片区
.kpi-section {
  margin-bottom: 16px;

  .el-col {
    margin-bottom: 16px;
  }
}

.kpi-card {
  border-radius: var(--radius-lg);
  border: none;
  box-shadow: var(--shadow-md);
  display: flex;
  padding: 20px;
  gap: 16px;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
  cursor: default;

  &:hover {
    transform: translateY(-2px);
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.1);
  }

  :deep(.el-card__body) {
    padding: 0;
    display: flex;
    gap: 16px;
    width: 100%;
  }

  .kpi-icon {
    width: 56px;
    height: 56px;
    border-radius: var(--radius-lg);
    display: flex;
    align-items: center;
    justify-content: center;

    .el-icon {
      font-size: 28px;
      color: #fff;
    }
  }

  &.revenue .kpi-icon {
    background: linear-gradient(135deg, #2D8B57, #4C9AFF);
  }

  &.profit .kpi-icon {
    background: linear-gradient(135deg, #36B37E, #57D9A3);
  }

  &.orders .kpi-icon {
    background: linear-gradient(135deg, #FFAB00, #FFC400);
  }

  &.customers .kpi-icon {
    background: linear-gradient(135deg, #FF5630, #FF8B6A);
  }

  .kpi-content {
    flex: 1;

    .kpi-label {
      font-size: 13px;
      color: var(--color-text-secondary);
      margin-bottom: 4px;
    }

    .kpi-value-row {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 4px;
    }

    .kpi-sparkline {
      flex-shrink: 0;
      opacity: 0.85;
    }

    .kpi-value {
      font-size: var(--font-size-2xl);
      font-weight: 600;
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
      color: var(--el-text-color-primary, #1A2332);

      &.growth-up { color: var(--el-color-success, #36B37E); }
      &.growth-down { color: var(--el-color-danger, #FF5630); }
    }

    .kpi-trend {
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 13px;

      &.growth-up {
        color: var(--el-color-success, #36B37E);
      }

      &.growth-down {
        color: var(--el-color-danger, #FF5630);
      }

      .vs-label {
        color: var(--el-text-color-placeholder, #A0AEC0);
        margin-left: 4px;
      }
    }
  }
}

// 排行榜区
.ranking-section {
  margin-bottom: 16px;

  .el-col {
    margin-bottom: 16px;
  }
}

.compact-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 24px 16px;
  color: #c0c4cc;
  font-size: 13px;
}

.ranking-card {
  border-radius: var(--radius-lg);

  .card-header {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;

    .el-icon {
      color: #2D8B57;
    }
  }

  .ranking-list {
    .ranking-item {
      display: flex;
      align-items: center;
      padding: 12px 0;
      border-bottom: 1px solid #F4F6F9;

      &:last-child {
        border-bottom: none;
      }
    }

    .rank-badge {
      width: 24px;
      height: 24px;
      border-radius: var(--radius-md);
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 12px;
      font-weight: 600;
      background: #F4F6F9;
      color: var(--color-text-secondary);

      &.rank-1 {
        background: linear-gradient(135deg, #FFD700, #FFA500);
        color: #fff;
      }

      &.rank-2 {
        background: linear-gradient(135deg, #C0C0C0, #A8A8A8);
        color: #fff;
      }

      &.rank-3 {
        background: linear-gradient(135deg, #CD7F32, #B8860B);
        color: #fff;
      }
    }

    .rank-info {
      flex: 1;
      margin-left: 12px;

      .rank-name {
        font-size: 14px;
        color: var(--el-text-color-primary, #1A2332);
        font-weight: 500;
      }

      .rank-value {
        font-size: 12px;
        color: var(--color-text-secondary);
      }
    }

    .rank-growth {
      font-size: 14px;
      font-weight: 500;

      &.growth-up {
        color: var(--el-color-success, #36B37E);
      }

      &.growth-down {
        color: var(--el-color-danger, #FF5630);
      }
    }

    .region-item {
      border-radius: 6px;
      padding: 12px 8px;
      margin: 0 -8px;
      transition: background 0.2s ease;

      &:hover {
        background: var(--el-fill-color-light, #F4F6F9);

        .region-bar {
          filter: brightness(1.1);
        }
      }

      .region-name {
        width: 80px;
        font-size: 14px;
        color: var(--el-text-color-primary, #1A2332);
      }

      .region-bar-wrapper {
        flex: 1;
        height: 8px;
        background: #F4F6F9;
        border-radius: 4px;
        margin: 0 12px;
        overflow: hidden;

        .region-bar {
          height: 100%;
          background: linear-gradient(90deg, #2D8B57, #4C9AFF);
          border-radius: 4px;
          transition: width 0.6s cubic-bezier(0.4, 0, 0.2, 1), filter 0.2s ease;

          &.rank-bar-0 { background: linear-gradient(90deg, #2D8B57, #4C9AFF); }
          &.rank-bar-1 { background: linear-gradient(90deg, #36B37E, #57D9A3); }
          &.rank-bar-2 { background: linear-gradient(90deg, #FFAB00, #FFC400); }
          &.rank-bar-3 { background: linear-gradient(90deg, #A0AEC0, #CBD5E0); }
        }
      }

      .region-value {
        width: 100px;
        text-align: right;

        .value {
          font-size: 14px;
          color: var(--el-text-color-primary, #1A2332);
          font-weight: 500;
        }

        .percent {
          font-size: 12px;
          color: var(--color-text-secondary);
          margin-left: 8px;
        }
      }
    }
  }
}

// Stagger reveal animation
// Simple fade-in for KPI cards (replaces stagger-item which had timing issues with v-if/v-else)
.kpi-fade-in {
  animation: kpiFadeIn 0.5s ease-out both;
}

@keyframes kpiFadeIn {
  from { opacity: 0; transform: translateY(12px); }
  to   { opacity: 1; transform: translateY(0); }
}

// Cross-filter indicator bar
.cross-filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  margin-bottom: 12px;
  background: var(--el-color-primary-light-9, #ecf5ff);
  border-radius: 6px;
  font-size: 13px;
  color: var(--el-text-color-regular, #606266);
}

// AI insight header timestamp
.insight-header-timestamp {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  font-weight: 400;
}

// AI insight meta (timestamp + citation legend below insights)
.insight-meta {
  margin-top: 12px;
  padding-top: 10px;
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

    .citation-ref {
      cursor: pointer;
      color: var(--el-color-primary, #2D8B57);
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
  color: var(--el-color-primary, #2D8B57);
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

// 图表区
.chart-section {
  margin-bottom: 16px;

  .el-col {
    margin-bottom: 16px;
  }
}

.chart-card {
  border-radius: var(--radius-lg);

  .card-header {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;

    .el-icon {
      color: #2D8B57;
    }
  }

  .chart-container {
    height: 320px;
    width: 100%;
  }
}

// AI 洞察区
.insight-section {
  margin-bottom: 16px;
}

.insight-loading {
  padding: 8px 4px;

  .insight-loading-hint {
    margin-top: 12px;
    color: #909399;
    font-size: 13px;
    display: flex;
    align-items: center;
    gap: 6px;

    .is-loading {
      animation: rotating 2s linear infinite;
    }
  }
}

.insight-streaming {
  padding: 12px 4px;

  .is-loading {
    animation: rotating 2s linear infinite;
  }

  .streaming-text {
    margin-top: 10px;
    font-size: 14px;
    line-height: 1.6;
    color: #303133;
    white-space: pre-wrap;
    word-break: break-word;

    .cursor {
      color: #409EFF;
      animation: blink 1s step-end infinite;
      margin-left: 1px;
    }
  }
}

@keyframes blink {
  50% { opacity: 0; }
}

@keyframes rotating {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.insight-card {
  border-radius: var(--radius-lg);

  .card-header {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;

    .el-icon {
      color: #2D8B57;
    }
  }

  .insight-list {
    .insight-item {
      display: flex;
      align-items: flex-start;
      flex-wrap: wrap;
      gap: 12px;
      padding: 12px 16px;
      margin-bottom: 8px;
      border-radius: 8px;
      border-left: 4px solid #909399;
      background: #fafbfc;
      transition: background 0.2s ease;

      &:hover {
        background: #f0f2f5;
      }

      &:last-child {
        margin-bottom: 0;
      }

      &.insight-success { border-left-color: var(--el-color-success, #36B37E); background: var(--el-color-success-light-9, #f6ffed); }
      &.insight-warning { border-left-color: var(--el-color-warning, #E6A23C); background: var(--el-color-warning-light-9, #fffbe6); }
      &.insight-danger  { border-left-color: var(--el-color-danger, #FF5630); background: var(--el-color-danger-light-9, #fff2f0); }
      &.insight-info    { border-left-color: var(--el-color-primary, #2D8B57); background: var(--el-color-primary-light-9, #e6f7ff); }

      .el-tag {
        flex-shrink: 0;
      }

      .insight-content {
        font-size: 14px;
        color: var(--el-text-color-regular, #4A5568);
        line-height: 1.6;
        flex: 1;
        min-width: 200px;
        white-space: pre-line;
      }

      .insight-suggestion {
        font-size: 13px;
        color: var(--color-text-secondary);
        font-style: italic;
        width: 100%;
        padding-left: 60px;
      }
    }

    .insight-toggle {
      text-align: center;
      padding-top: 8px;

      .toggle-icon {
        transition: transform 0.3s ease;
        margin-left: 4px;

        &.expanded {
          transform: rotate(180deg);
        }
      }
    }
  }
}

// 快捷问答区
.quick-qa-card {
  border-radius: var(--radius-lg);

  .card-header {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;

    .el-icon {
      color: #2D8B57;
    }
  }

  .quick-questions {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;

    .el-button {
      transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);

      &:hover {
        transform: translateY(-2px);
        box-shadow: 0 4px 12px rgba(45, 139, 87, 0.15);
        color: var(--el-color-primary, #2D8B57);
        border-color: var(--el-color-primary, #2D8B57);
      }
    }
  }
}

// 图表卡片悬浮效果
.ranking-card,
.chart-card,
.insight-card,
.quick-qa-card {
  transition: transform 0.25s cubic-bezier(0.4, 0, 0.2, 1), box-shadow 0.25s cubic-bezier(0.4, 0, 0.2, 1);

  &:hover {
    transform: translateY(-2px);
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.1);
  }
}

// 响应式适配
@media (max-width: 1200px) {
  .charts-section .el-col[class*="md-12"] {
    margin-bottom: 16px;
  }
}

@media (max-width: 1366px) {
  .chart-container {
    height: 280px;
  }
}

@media (max-width: 768px) {
  .page-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 16px;
  }

  .kpi-card {
    .kpi-content .kpi-value {
      font-size: var(--font-size-xl);
    }
  }

  .ranking-section {
    .el-col { margin-bottom: 12px; }
  }

  .chart-container {
    height: 240px !important;
  }
}

@media (max-width: 480px) {
  .kpi-card {
    .kpi-icon {
      width: 40px;
      height: 40px;
      .el-icon { font-size: 20px; }
    }
    .kpi-content .kpi-value {
      font-size: var(--font-size-lg);
    }
  }
}

// ==================== 暗色模式 ====================

.smart-bi-dashboard[data-theme="dark"] {
  background: var(--bg-color-page);
  color: var(--text-color-primary);

  .page-header {
    h1 { color: var(--text-color-primary); }
    .subtitle { color: var(--text-color-secondary); }
  }

  :deep(.el-card) {
    background: var(--bg-color-overlay);
    border-color: var(--border-color);
    color: var(--text-color-primary);
  }

  :deep(.el-card__header) {
    border-bottom-color: var(--border-color);
  }

  .kpi-card {
    .kpi-icon {
      opacity: 0.85;
    }
    .kpi-content {
      .kpi-label { color: var(--text-color-secondary); }
      .kpi-value { color: var(--color-primary-light); }
      .kpi-trend {
        &.growth-up { color: var(--color-success); }
        &.growth-down { color: var(--color-danger); }
      }
    }
    &.revenue .kpi-icon { background: rgba(54, 179, 126, 0.2); }
    &.profit .kpi-icon { background: rgba(45, 139, 87, 0.2); }
    &.orders .kpi-icon { background: rgba(255, 171, 0, 0.2); }
    &.customers .kpi-icon { background: rgba(114, 46, 209, 0.2); }
  }

  .ranking-card {
    .card-header span { color: var(--text-color-primary); }
  }

  .ranking-row {
    border-bottom-color: var(--border-color);
    .region-name, .dept-name { color: var(--text-color-regular); }
    .region-bar-bg { background: rgba(255, 255, 255, 0.08); }
  }

  .chart-card {
    .card-header span { color: var(--text-color-primary); }
  }

  .insight-card {
    background: rgba(255, 255, 255, 0.04) !important;
    border-left-color: var(--border-color);
    .insight-title { color: var(--text-color-regular); }
    .insight-content { color: var(--text-color-secondary); }
    &.insight-success { border-left-color: var(--color-success); background: rgba(87, 217, 163, 0.08) !important; }
    &.insight-warning { border-left-color: var(--color-warning); background: rgba(255, 171, 0, 0.08) !important; }
    &.insight-danger { border-left-color: var(--color-danger); background: rgba(255, 139, 106, 0.08) !important; }
    &.insight-info { border-left-color: var(--color-primary-light); background: rgba(76, 154, 255, 0.08) !important; }
  }

  .quick-question-section {
    :deep(.el-card) { background: var(--bg-color-overlay); }
    .quick-btn { background: rgba(255, 255, 255, 0.06); color: var(--text-color-regular); border-color: var(--border-color); }
  }
}
</style>
