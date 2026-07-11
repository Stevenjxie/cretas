<script setup lang="ts">
/**
 * SmartBI 餐饮 V2 Dashboard — 餐饮综合分析
 *
 * 渲染 RestaurantAnalyzerV2 的 5 sections:
 *   1. Executive Summary (摘要 + 关键建议)
 *   2. 财务指标卡片 (food/labor/cost_rigidity)
 *   3. 诊断列表 (DiagnosticsEngine 输出)
 *   4. 对标预警列表 (BenchmarkAlertEngine 输出, 含年度影响)
 *   5. 渠道毛利率表格 (ChannelMarginCalculator, 含 cogs_source 透明度)
 *   6. 命名归一统计 (MenuNormalizer)
 *
 * 数据流:
 *   用户选择 upload → POST /restaurant-analytics-v2/{id} → V2.analyze() → 渲染
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import echarts from '@/utils/echarts';
import type { ECharts as EChartsInstance } from 'echarts/core';
import {
  Refresh,
  TrendCharts,
  WarningFilled,
  CircleCheckFilled,
  InfoFilled,
  Money,
  DataAnalysis,
  ChatDotRound,
} from '@element-plus/icons-vue';
import RestaurantChatPanel from './components/chat/RestaurantChatPanel.vue';
import { getUploadHistory, type UploadHistoryItem } from '@/api/smartbi/upload';
import { getFinanceSummary, type FinanceSummary } from '@/api/smartbi/gold';
import {
  computeRestaurantAnalyticsV2,
  getRestaurantAnalyticsV2,
  getReviewStats,
  type V2UnifiedReport,
  type V2AnalyzePayload,
  type FinancialData,
  type Diagnosis,
  type BenchmarkAlert,
  type ChannelMarginRow,
  // Week 4 types
  type StorePnlOnePager,
  type DiningHeatmap,
  type StoredValueDependency,
  type LongTailSku,
  type ReviewAnalysis,
  type BomLayerStatus,
  type TemporalComparison,
  type MemberRfm,
  type CalibrationHistoryReport,
  // W6.4 types
  type MultiStoreComparison,
  type AdvancedTrafficPersona,
} from '@/api/smartbi/restaurant-v2';
import BomIngestDialog from './BomIngestDialog.vue';
import TemplateGrid from './components/TemplateGrid.vue';
import ChartInsightProvider from './components/ChartInsightProvider.vue';
import type { ChartWithMeta, UserPermissions } from './components/chartInsight';
import AdvancedTrafficPersonaCard from './components/chat/cards/AdvancedTrafficPersonaCard.vue';
import { resolveAdvancedTrafficPersona } from './advancedTrafficPersonaDemo';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);

// ── ChartInsightProvider perms (Phase B Moderate) ──────────────
// canViewPrice gates finance absolute ¥ display in insight provider
const insightPerms = computed<UserPermissions>(() => ({
  canViewFinance: canViewPrice.value,
}));

/**
 * RFM Pie chart — ChartWithMeta for ChartInsightProvider.
 * Slot: rfmPieChartRef (PIE, member segment distribution).
 * meta: xDim 'category' (segment names), yMetric 'count', domain 'restaurant'.
 * Data sourced from memberRfm.value.segmentCounts after renderRfmPieChart() builds pieData.
 *
 * SKIP: HEATMAP (heatmapChartRef) — exotic, not Tier1-coverable.
 */
const rfmPieChartWithMeta = computed<ChartWithMeta | null>(() => {
  const rfm = memberRfm.value;
  if (!rfm?.analyzedMembers) return null;
  const segCounts = rfm.segmentCounts;
  const pieData = Object.entries(segCounts)
    .filter(([, count]) => count > 0)
    .map(([seg, count]) => ({ name: seg, value: count }));
  if (pieData.length < 2) return null;
  return {
    chartType: 'pie',
    title: '会员客群分布',
    meta: {
      xDim: 'category',
      yMetric: 'count',
      aggregation: 'sum',
      domain: 'restaurant',
    },
    config: {
      series: [{ type: 'pie', data: pieData }],
    },
  };
});

/**
 * Calibration line chart — ChartWithMeta for ChartInsightProvider.
 * Slot: calibrationChartRef (LINE×2: 校准因子 + 食材率).
 * chartType 'comparison' (2 comparable series over time).
 * meta: xDim 'time' (period labels), yMetric 'pct', domain 'restaurant'.
 * Data sourced from calibrationHistory.value.factorTrend.
 */
const calibrationChartWithMeta = computed<ChartWithMeta | null>(() => {
  const cal = calibrationHistory.value;
  if (!cal?.factorTrend?.length || cal.factorTrend.length < 2) return null;
  const trend = cal.factorTrend;
  const periods = trend.map((t) => t.period);
  const factors = trend.map((t) => t.factor);
  const ratios = trend.map((t) => t.ratio);
  return {
    chartType: 'comparison',
    title: '月度校准历史对比',
    meta: {
      xDim: 'time',
      yMetric: 'pct',
      aggregation: 'avg',
      domain: 'restaurant',
    },
    config: {
      xAxis: { data: periods },
      series: [
        { type: 'line', name: '校准因子', data: factors },
        { type: 'line', name: '食材率', data: ratios },
      ],
    },
  };
});

// ── State ──────────────────────────────────────────
const uploads = ref<UploadHistoryItem[]>([]);
const selectedUploadId = ref<number | null>(null);
const loading = ref(false);
const report = ref<V2UnifiedReport | null>(null);
const lastError = ref<string>('');
const errorActionHint = ref<string>('');
const performanceInfo = ref<{ totalSeconds: number; posRows: number } | null>(null);

// Financial data (可选, 用户填)
const showFinancialForm = ref(false);
const financialCurrent = ref({
  revenue: 0,
  food_cost: 0,
  labor_cost: 0,
  rent: 0,
});
const financialPrevious = ref({
  revenue: 0,
  food_cost: 0,
  labor_cost: 0,
  rent: 0,
});
// P1-4 fix: subSector default was hardcoded '火锅', placeholder "青花椒大丸百货店" —
// both assumed 青花椒 火锅店. Make default generic '中餐' so 西餐/日料/快餐 etc.
// don't see wrong industry. Placeholder now computed from factoryName (if present).
const subSector = ref<string>('中餐');
const storeName = ref<string>('');
// Computed dynamic placeholder based on merchant identity
const storePlaceholder = computed(() => {
  // Try window auth payload (set via login)
  const factoryName = (authStore as unknown as { factoryName?: string }).factoryName
    || (window as unknown as { __factoryName?: string }).__factoryName
    || '';
  if (factoryName) {
    const clean = factoryName.replace(/(有限公司|股份|集团|连锁|餐饮|管理)/g, '').trim();
    return clean ? `例: ${clean}总店` : '例: 总店';
  }
  return '例: 总店';
});
// Apr 24 2026 P1-13: period 默认当前月 YYYY-MM, 不硬编码 2026-02 (历史写死值)
const _thisMonth = (() => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
})();
const period = ref<string>(_thisMonth);

// P5 Task 5.3: chat drawer state
const chatDrawerVisible = ref(false);

// 演示数据快捷填写 (火锅店样例 P&L)
function fillDengHuoguoDemo() {
  financialCurrent.value = {
    revenue: 731047.52,
    food_cost: 335212.75,
    labor_cost: 237660.0,
    rent: 57328.0,
  };
  financialPrevious.value = {
    revenue: 1390503.28,
    food_cost: 578603.27,
    labor_cost: 323805.0,
    rent: 57324.0,
  };
  subSector.value = '火锅';
  storeName.value = '示例店·火锅样例';
  period.value = '2026-02';
  ElMessage.success('已填入火锅样例 P&L 演示数据');
}

// ── Lifecycle ──────────────────────────────────────
onMounted(async () => {
  await loadUploads();
  loadGoldKpi();
  loadMarginKpi();
});

async function loadUploads() {
  try {
    // Note: factoryId-scoped via auth store; pass no positional factoryId here.
    void (factoryId.value || '');  // #840: no F001 fallback
    const response = await getUploadHistory();
    uploads.value = response.data || [];
    if (uploads.value.length > 0) {
      selectedUploadId.value = uploads.value[0].id;
    }
  } catch (e: any) {
    ElMessage.error(`加载 upload 列表失败: ${e.message}`);
  }
}

// ── Core: 触发 V2 分析 ─────────────────────────────
async function runAnalysis(force: boolean = false) {
  if (!selectedUploadId.value) {
    ElMessage.warning('请先选择一个上传的 Excel');
    return;
  }

  loading.value = true;
  lastError.value = '';
  report.value = null;
  performanceInfo.value = null;

  try {
    const payload: V2AnalyzePayload = {
      sub_sector: subSector.value,
      store_name: storeName.value || undefined,
      period: period.value,
    };

    // 只有当 current revenue 有值时才带财务数据
    if (financialCurrent.value.revenue > 0) {
      const financial: FinancialData = {
        current: { ...financialCurrent.value },
        monthly_revenue: financialCurrent.value.revenue,
      };
      if (financialPrevious.value.revenue > 0) {
        financial.previous = { ...financialPrevious.value };
      }
      payload.financial_data = financial;
    }

    const response = await computeRestaurantAnalyticsV2(
      selectedUploadId.value,
      payload,
      force
    );

    if (response.success && response.data) {
      report.value = response.data;
      if (response.performance) {
        performanceInfo.value = {
          totalSeconds: response.performance.totalSeconds,
          posRows: response.performance.posRows,
        };
      }
      if (response.cached) {
        ElMessage.info('已使用缓存结果, 点"重新计算"强制更新');
      } else {
        ElMessage.success(
          `V2 分析完成 (${response.performance?.posRows || 0} 行, ${response.performance?.totalSeconds || 0}s)`
        );
      }
    } else {
      lastError.value = response.message || '分析失败';
      showAnalysisError(lastError.value);
    }
  } catch (e) {
    // Apr 24 UX Rule 8: extract useful error message without losing info
    const msg = e instanceof Error ? e.message : String(e);
    lastError.value = msg;
    showAnalysisError(msg);
  } finally {
    loading.value = false;
  }
}

// Apr 24 UX: sticky error toast with specific actionHint by error type
// (Rule 8 四位一体: message 具体 / sticky / next action hint)
function showAnalysisError(rawMsg: string) {
  let actionHint = '';
  if (rawMsg.includes('timeout') || rawMsg.includes('超时') || rawMsg.includes('Timeout')) {
    actionHint = 'Python 服务响应超时。数据量大时可能需 30-60s,请稍后重试,或降低分析范围。';
  } else if (rawMsg.includes('500') || rawMsg.includes('Internal Server')) {
    actionHint = 'Python 服务内部错误。请联系管理员检查 python-test.log。';
  } else if (rawMsg.includes('不存在') || rawMsg.includes('404')) {
    actionHint = '上传数据可能已被删除,请重新选择其他上传。';
  } else if (rawMsg.includes('未启用') || rawMsg.includes('模块')) {
    actionHint = '您的工厂未启用餐饮 V2 分析模块,请联系管理员在 Canvas 配置中开启。';
  }
  errorActionHint.value = actionHint;
  ElMessage({
    message: actionHint ? `${rawMsg}\n${actionHint}` : rawMsg,
    type: 'error',
    duration: 0,      // Sticky — user must manually close
    showClose: true,
    dangerouslyUseHTMLString: false,
    customClass: 'rv2-error-toast',
  });
}

// ── Gold KPI strip (v1.1 cutover) ──────────────────
// 独立于 V2 compute 的实时 KPI, 读 /gold/finance-summary (agg_daily). 页面打开即可见.
function _todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}
function _monthStartIso(offsetMonths = 0): string {
  const d = new Date();
  d.setMonth(d.getMonth() + offsetMonths, 1);
  return d.toISOString().slice(0, 10);
}
const goldKpiRange = ref<[string, string]>([_monthStartIso(), _todayIso()]);
const goldKpiShortcuts = [
  { text: '本月', value: (): [Date, Date] => [new Date(new Date().setDate(1)), new Date()] },
  { text: '近30天', value: (): [Date, Date] => {
    const e = new Date(); const s = new Date(); s.setDate(s.getDate() - 29);
    return [s, e];
  }},
  { text: '近90天', value: (): [Date, Date] => {
    const e = new Date(); const s = new Date(); s.setDate(s.getDate() - 89);
    return [s, e];
  }},
  { text: '2025全年', value: (): [Date, Date] => [new Date('2025-01-01'), new Date('2025-12-31')] },
  { text: '2024全年', value: (): [Date, Date] => [new Date('2024-01-01'), new Date('2024-12-31')] },
];
const goldKpi = ref<FinanceSummary | null>(null);
const goldKpiLoading = ref(false);
const goldKpiError = ref<string>('');
const goldKpiFallbackLabel = ref<string>('');

// Apr 24 Plan C Phase 7+: margin KPI (restaurant ops gold)
const marginKpi = ref<{ rate: number; profit: number; dishes: Array<{name: string, grossProfit: number, marginRate: number}> }>({
  rate: 0, profit: 0, dishes: [],
});
async function loadMarginKpi() {
  if (!factoryId.value) return;
  try {
    const { pythonFetch } = await import('@/api/smartbi/common');
    const res = await pythonFetch('/api/smartbi/restaurant-ops/gross-margin?days=30') as {
      success: boolean;
      data?: { avgRate: number; totalProfit: number; dishes: Array<{name: string; grossProfit: number; marginRate: number; hasCost: boolean}> };
    };
    if (res.success && res.data) {
      marginKpi.value.rate = res.data.avgRate || 0;
      marginKpi.value.profit = res.data.totalProfit || 0;
      marginKpi.value.dishes = (res.data.dishes || [])
        .filter(d => d.hasCost)
        .sort((a, b) => b.grossProfit - a.grossProfit)
        .slice(0, 3);
    }
  } catch (e) {
    console.warn('[margin-kpi] load failed:', e);
  }
}

async function loadGoldKpi() {
  if (!factoryId.value) return;
  const [s, e] = goldKpiRange.value;
  if (!s || !e) return;
  goldKpiLoading.value = true;
  goldKpiError.value = '';
  try {
    const primary = await getFinanceSummary({
      factoryId: factoryId.value,
      startDate: s,
      endDate: e,
      topNStores: 5,
    });
    if (primary && primary.billCount > 0) {
      goldKpi.value = primary;
      goldKpiFallbackLabel.value = '';
    } else {
      // Fallback chain: 近 90 天 → 上一年全年 → 再前一年, 避免用户看到空 KPI.
      const y = new Date().getFullYear();
      const iso = (d: Date): string => d.toISOString().slice(0, 10);
      const chain: Array<[string, string, string]> = [
        (() => { const e2 = new Date(); const s2 = new Date(); s2.setDate(s2.getDate() - 89); return [iso(s2), iso(e2), '近90天'] as [string, string, string]; })(),
        [`${y - 1}-01-01`, `${y - 1}-12-31`, `${y - 1}全年`],
        [`${y - 2}-01-01`, `${y - 2}-12-31`, `${y - 2}全年`],
      ];
      for (const [cs, ce, label] of chain) {
        const fb = await getFinanceSummary({ factoryId: factoryId.value, startDate: cs, endDate: ce, topNStores: 5 });
        if (fb && fb.billCount > 0) {
          goldKpi.value = fb;
          goldKpiFallbackLabel.value = label;
          break;
        }
      }
      if (!goldKpi.value || goldKpi.value.billCount === 0) {
        goldKpi.value = primary;  // keep zero-state for display
        goldKpiFallbackLabel.value = '';
      }
    }
  } catch (err: unknown) {
    goldKpiError.value = err instanceof Error ? err.message : String(err);
    goldKpi.value = null;
    goldKpiFallbackLabel.value = '';
  } finally {
    goldKpiLoading.value = false;
  }
}

watch(goldKpiRange, loadGoldKpi);
watch(factoryId, (v) => { if (v) loadGoldKpi(); });

// ── Computed helpers ───────────────────────────────

const executiveSummary = computed(() => report.value?.executiveSummary || []);
const financialMetrics = computed(() => report.value?.sections?.financialMetrics);
const diagnostics = computed<Diagnosis[]>(
  () => report.value?.sections?.diagnostics || []
);
const benchmarkAlerts = computed<BenchmarkAlert[]>(
  () => report.value?.sections?.benchmarkAlerts || []
);
const channelMargin = computed(() => report.value?.sections?.channelMargin);
const menuNormalization = computed(() => report.value?.sections?.menuNormalization);

// Week 4 sections
const storePnlOnePager = computed<StorePnlOnePager | undefined>(
  () => report.value?.sections?.storePnlOnePager
);
const diningHeatmap = computed<DiningHeatmap | undefined>(
  () => report.value?.sections?.diningHeatmap
);
const storedValueDependency = computed<StoredValueDependency | undefined>(
  () => report.value?.sections?.storedValueDependency
);
const longTailSku = computed<LongTailSku | undefined>(
  () => report.value?.sections?.longTailSku
);
const reviewAnalysis = computed<ReviewAnalysis | undefined>(
  () => report.value?.sections?.reviewAnalysis
);
const calibrationHistory = computed<CalibrationHistoryReport | undefined>(
  () => report.value?.sections?.calibrationHistory
);
const bomLayerStatus = computed<BomLayerStatus | undefined>(
  () => report.value?.sections?.bomLayerStatus
);
const temporalComparison = computed<TemporalComparison | undefined>(
  () => report.value?.sections?.temporalComparison
);
const memberRfm = computed<MemberRfm | undefined>(
  () => report.value?.sections?.memberRfm
);
const multiStoreComparison = computed<MultiStoreComparison | undefined>(
  () => report.value?.sections?.multiStoreComparison
);
const advancedTrafficPersona = computed<AdvancedTrafficPersona>(
  () => resolveAdvancedTrafficPersona(report.value)
);

// W6 — Review collection stats (separate API call)
const reviewStats = ref<{ totalReviews: number; storeCount: number; lastUpload?: string } | null>(null);

async function loadReviewStats() {
  try {
    const resp = await getReviewStats(factoryId.value || '') as {
      success?: boolean;
      data?: { totalReviews: number; storeCount: number; lastUpload?: string };
    };
    if (resp?.success && resp.data) {
      reviewStats.value = resp.data;
    }
  } catch {
    // Silently fail — review stats are non-critical
  }
}

function segmentTagType(segment: string): string {
  if (segment === 'Champions') return 'success';
  if (segment === 'Loyal') return 'success';
  if (segment === 'New') return 'info';
  if (segment === 'Potential') return 'info';
  if (segment === 'At Risk') return 'warning';
  if (segment === 'Hibernating') return 'warning';
  if (segment === 'Lost') return 'danger';
  return '';
}

// ── ECharts refs & lifecycle ──────────────────────────────
const heatmapChartRef = ref<HTMLDivElement | null>(null);
const rfmPieChartRef = ref<HTMLDivElement | null>(null);
const calibrationChartRef = ref<HTMLDivElement | null>(null);

let heatmapChart: EChartsInstance | null = null;
let rfmPieChart: EChartsInstance | null = null;
let calibrationChart: EChartsInstance | null = null;

function disposeChart(instance: EChartsInstance | null): null {
  if (instance && !instance.isDisposed?.()) instance.dispose();
  return null;
}

function handleChartsResize() {
  heatmapChart?.resize();
  rfmPieChart?.resize();
  calibrationChart?.resize();
}

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleChartsResize);
  heatmapChart = disposeChart(heatmapChart);
  rfmPieChart = disposeChart(rfmPieChart);
  calibrationChart = disposeChart(calibrationChart);
});

// --- Chart 1: Dining Heatmap (7x24 grid) ---
const DAY_LABELS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
const HOUR_LABELS = Array.from({ length: 24 }, (_, i) => `${i}时`);

function renderHeatmapChart() {
  if (!heatmapChartRef.value || !diningHeatmap.value?.cells?.length) return;
  heatmapChart = disposeChart(heatmapChart);
  heatmapChart = echarts.init(heatmapChartRef.value, 'cretas');

  const cells = diningHeatmap.value.cells;
  // Build [hour, dayIdx, revenue] tuples
  const data = cells.map((c) => [c.hour, c.dayOfWeek, c.revenue]);
  const revenues = cells.map((c) => c.revenue);
  const maxRev = Math.max(...revenues, 1);

  heatmapChart.setOption({
    tooltip: {
      position: 'top',
      formatter: (p: any) => {
        const [hour, day, rev] = p.data;
        return `${DAY_LABELS[day]} ${hour}:00<br/>营收: <strong>¥${Number(rev).toLocaleString()}</strong>`;
      },
    },
    grid: { top: 10, right: 80, bottom: 40, left: 60 },
    xAxis: {
      type: 'category',
      data: HOUR_LABELS,
      splitArea: { show: true },
      axisLabel: { fontSize: 10 },
    },
    yAxis: {
      type: 'category',
      data: DAY_LABELS,
      splitArea: { show: true },
    },
    visualMap: {
      min: 0,
      max: maxRev,
      calculable: true,
      orient: 'vertical',
      right: 0,
      top: 'center',
      inRange: { color: ['#f0f9eb', '#b3e19d', '#67c23a', '#e6a23c', '#f56c6c'] },
      formatter: (v: number) => `¥${Math.round(v).toLocaleString()}`,
    },
    series: [{
      type: 'heatmap',
      data,
      label: { show: false },
      emphasis: {
        itemStyle: { shadowBlur: 10, shadowColor: 'rgba(0,0,0,0.3)' },
      },
    }],
  });
}

// --- Chart 2: Member RFM Pie (segment distribution) ---
function renderRfmPieChart() {
  if (!rfmPieChartRef.value || !memberRfm.value?.analyzedMembers) return;
  rfmPieChart = disposeChart(rfmPieChart);
  rfmPieChart = echarts.init(rfmPieChartRef.value, 'cretas');

  const segCounts = memberRfm.value.segmentCounts;
  const segRevenue = memberRfm.value.segmentRevenue;
  const segColors: Record<string, string> = {
    Champions: '#67c23a', Loyal: '#409eff', Potential: '#36b37e',
    New: '#4C9AFF', 'At Risk': '#e6a23c', Hibernating: '#909399', Lost: '#f56c6c',
  };

  const pieData = Object.entries(segCounts)
    .filter(([, count]) => count > 0)
    .map(([seg, count]) => ({
      name: seg,
      value: count,
      revenue: segRevenue[seg] || 0,
      itemStyle: { color: segColors[seg] || '#6B778C' },
    }));

  rfmPieChart.setOption({
    tooltip: {
      trigger: 'item',
      formatter: (p: any) => {
        const { name, value, data: d, percent } = p;
        return `<strong>${name}</strong><br/>
                人数: ${value} (${percent}%)<br/>
                贡献营收: ¥${Number(d.revenue).toLocaleString()}`;
      },
    },
    legend: {
      orient: 'vertical',
      right: 10,
      top: 'center',
      textStyle: { fontSize: 12 },
    },
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      center: ['40%', '50%'],
      avoidLabelOverlap: true,
      itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
      label: {
        show: true,
        formatter: '{b}: {c}',
        fontSize: 11,
      },
      emphasis: { label: { show: true, fontSize: 14, fontWeight: 'bold' } },
      data: pieData,
    }],
  });
}

// --- Chart 3: Calibration Factor Trend Line ---
function renderCalibrationChart() {
  if (!calibrationChartRef.value || !calibrationHistory.value?.factorTrend?.length) return;
  calibrationChart = disposeChart(calibrationChart);
  calibrationChart = echarts.init(calibrationChartRef.value, 'cretas');

  const trend = calibrationHistory.value.factorTrend;
  const periods = trend.map((t) => t.period);
  const factors = trend.map((t) => t.factor);
  const ratios = trend.map((t) => t.ratio);

  calibrationChart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params: any) => {
        if (!Array.isArray(params)) return '';
        const period = params[0]?.axisValue || '';
        let html = `<strong>${period}</strong><br/>`;
        params.forEach((p: any) => {
          html += `${p.marker} ${p.seriesName}: <strong>${Number(p.value).toFixed(3)}</strong><br/>`;
        });
        return html;
      },
    },
    legend: { bottom: 0, textStyle: { fontSize: 12 } },
    grid: { top: 20, right: 20, bottom: 40, left: 60 },
    xAxis: {
      type: 'category',
      data: periods,
      boundaryGap: false,
      axisLabel: { fontSize: 11 },
    },
    yAxis: {
      type: 'value',
      axisLabel: { fontSize: 11, formatter: '{value}' },
      splitLine: { lineStyle: { type: 'dashed' } },
    },
    series: [
      {
        name: '校准因子 (vs 基准)',
        type: 'line',
        data: factors,
        smooth: true,
        symbol: 'circle',
        symbolSize: 8,
        lineStyle: { width: 3, color: '#409eff' },
        itemStyle: { color: '#409eff' },
        areaStyle: { color: 'rgba(64,158,255,0.08)' },
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { type: 'dashed', color: '#f56c6c', width: 2 },
          data: [{ yAxis: 1.0, label: { formatter: '基准 1.0', position: 'end', fontSize: 11 } }],
        },
      },
      {
        name: '食材率',
        type: 'line',
        data: ratios,
        smooth: true,
        symbol: 'diamond',
        symbolSize: 6,
        lineStyle: { width: 2, color: '#e6a23c', type: 'dashed' },
        itemStyle: { color: '#e6a23c' },
      },
    ],
  });
}

// Watch report changes to re-render charts + load auxiliary data
watch(
  () => report.value,
  async () => {
    if (!report.value) return;
    await nextTick();
    renderHeatmapChart();
    renderRfmPieChart();
    renderCalibrationChart();
    // Attach global resize listener (idempotent via remove-then-add)
    window.removeEventListener('resize', handleChartsResize);
    window.addEventListener('resize', handleChartsResize);
    // Load review collection stats when report is ready
    loadReviewStats();
  },
  { flush: 'post' }
);

function modeLabel(mode?: string): string {
 if (mode === 'yoy') return ' 同比 (YoY)';
 if (mode === 'qoq') return ' 环比 (QoQ)';
 if (mode === 'mom') return ' 月环比 (MoM)';
  return '—';
}

function trendIcon(trend?: string): string {
 if (trend === 'up') return '';
 if (trend === 'down') return '';
 return '️';
}

// W5.2 — BOM 数据录入 dialog state
const bomIngestDialogVisible = ref(false);

function openBomIngest() {
  bomIngestDialogVisible.value = true;
}

function onBomDataChanged() {
  // When user uploads new SKU/monthly data, offer to refresh analysis
  ElMessage.info('BOM 数据已更新, 建议点"强制重算"刷新分析');
}

function headlineColorToTag(color?: string): string {
  if (color === 'red') return 'danger';
  if (color === 'yellow') return 'warning';
  return 'success';
}

function trendDirectionLabel(direction?: string): string {
 if (direction === 'sharp_decline') return ' 急剧下滑';
 if (direction === 'declining') return ' 缓慢下滑';
 if (direction === 'rising') return ' 上升';
 if (direction === 'stable') return ' 稳定';
  return '—';
}

function severityTagType(severity: string): string {
  if (severity === 'critical' || severity === 'red') return 'danger';
  if (severity === 'warning' || severity === 'yellow') return 'warning';
  return 'info';
}

function formatPercent(v?: number): string {
  if (v === null || v === undefined) return '—';
  if (Math.abs(v) <= 1) return `${(v * 100).toFixed(2)}%`;
  return `${v.toFixed(2)}%`;
}

function formatCurrency(v?: number): string {
  if (v === null || v === undefined) return '—';
  return `¥${v.toLocaleString('zh-CN', { maximumFractionDigits: 0 })}`;
}
</script>

<template>
  <div class="restaurant-v2-dashboard">
    <!-- Header -->
    <el-card class="header-card" shadow="hover">
      <template #header>
        <div class="header-title">
          <el-icon><DataAnalysis /></el-icon>
          <span>餐饮综合分析</span>
        </div>
      </template>

      <div class="header-controls">
        <el-form :inline="true" label-width="90px">
          <el-form-item label="选择数据">
            <el-select
              v-model="selectedUploadId"
              placeholder="选择上传的 Excel"
              style="width: 320px"
            >
              <el-option
                v-for="u in uploads"
                :key="u.id"
                :label="`${u.fileName || u.originalFileName || 'upload'} (${u.id})`"
                :value="u.id"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="子行业">
            <el-select v-model="subSector" style="width: 140px">
              <el-option label="火锅" value="火锅" />
              <el-option label="鱼类餐饮" value="鱼类餐饮" />
              <el-option label="快餐" value="快餐" />
              <el-option label="烧烤" value="烧烤" />
              <el-option label="西餐" value="西餐" />
              <el-option label="日料" value="日料" />
              <el-option label="牛肉面" value="牛肉面" />
              <el-option label="中式海鲜" value="中式海鲜" />
              <el-option label="咖啡" value="咖啡" />
              <el-option label="奶茶" value="奶茶" />
              <el-option label="餐饮连锁" value="餐饮连锁" />
            </el-select>
          </el-form-item>

          <el-form-item label="门店">
            <el-input
              v-model="storeName"
              :placeholder="storePlaceholder"
              style="width: 200px"
            />
          </el-form-item>

          <el-form-item label="期间">
            <el-input v-model="period" placeholder="2026-02" style="width: 110px" />
          </el-form-item>

          <el-form-item>
            <el-button
              type="primary"
              :loading="loading"
              @click="runAnalysis(false)"
              :disabled="!selectedUploadId"
            >
              <el-icon><TrendCharts /></el-icon> 跑 V2 分析
            </el-button>
            <el-button
              :loading="loading"
              @click="runAnalysis(true)"
              :disabled="!selectedUploadId"
            >
              <el-icon><Refresh /></el-icon> 强制重算
            </el-button>
            <el-button v-if="canViewPrice" @click="showFinancialForm = !showFinancialForm">
              {{ showFinancialForm ? '收起财务' : '填财务数据' }}
            </el-button>
            <el-button type="success" :icon="Money" @click="openBomIngest">
              BOM 数据录入
            </el-button>
            <el-button type="primary" :icon="ChatDotRound" @click="chatDrawerVisible = true">
              聊天问答
            </el-button>
          </el-form-item>
        </el-form>

        <!-- Financial data form (optional) -->
        <el-collapse-transition>
          <div v-if="showFinancialForm && canViewPrice" class="financial-form">
            <el-alert type="info" :closable="false" style="margin-bottom: 12px">
              <template #default>
                填财务数据后, 系统能跑成本弹性指数 + 对标预警 + 诊断. 不填则只做渠道毛利率 + 命名归一.
                <el-button link type="primary" @click="fillDengHuoguoDemo">
                  一键填入火锅样例演示数据
                </el-button>
              </template>
            </el-alert>

            <el-row :gutter="16">
              <el-col :span="12">
                <h4>当期财务 (Current)</h4>
                <el-form label-width="90px" size="small">
                  <el-form-item label="营业收入">
                    <el-input-number
                      v-model="financialCurrent.revenue"
                      :min="0"
                      :controls="false"
                      style="width: 180px"
                    />
                  </el-form-item>
                  <el-form-item label="食材成本">
                    <el-input-number
                      v-model="financialCurrent.food_cost"
                      :min="0"
                      :controls="false"
                      style="width: 180px"
                    />
                  </el-form-item>
                  <el-form-item label="人力成本">
                    <el-input-number
                      v-model="financialCurrent.labor_cost"
                      :min="0"
                      :controls="false"
                      style="width: 180px"
                    />
                  </el-form-item>
                  <el-form-item label="房租">
                    <el-input-number
                      v-model="financialCurrent.rent"
                      :min="0"
                      :controls="false"
                      style="width: 180px"
                    />
                  </el-form-item>
                </el-form>
              </el-col>
              <el-col :span="12">
                <h4>上期财务 (Previous) — 用于 cost_rigidity</h4>
                <el-form label-width="90px" size="small">
                  <el-form-item label="营业收入">
                    <el-input-number
                      v-model="financialPrevious.revenue"
                      :min="0"
                      :controls="false"
                      style="width: 180px"
                    />
                  </el-form-item>
                  <el-form-item label="食材成本">
                    <el-input-number
                      v-model="financialPrevious.food_cost"
                      :min="0"
                      :controls="false"
                      style="width: 180px"
                    />
                  </el-form-item>
                  <el-form-item label="人力成本">
                    <el-input-number
                      v-model="financialPrevious.labor_cost"
                      :min="0"
                      :controls="false"
                      style="width: 180px"
                    />
                  </el-form-item>
                  <el-form-item label="房租">
                    <el-input-number
                      v-model="financialPrevious.rent"
                      :min="0"
                      :controls="false"
                      style="width: 180px"
                    />
                  </el-form-item>
                </el-form>
              </el-col>
            </el-row>
          </div>
        </el-collapse-transition>
      </div>
    </el-card>

    <!-- Gold KPI Strip (v1.1 cutover, 独立于 V2 compute 的实时数据) -->
    <el-card class="gold-kpi-card" shadow="hover" v-loading="goldKpiLoading">
      <template #header>
        <div class="gold-kpi-header">
          <div class="gold-kpi-title">
            <el-icon color="#67C23A"><TrendCharts /></el-icon>
            <span>实时 KPI 看板</span>
            <el-tag size="small" type="success">Gold · agg_daily</el-tag>
            <el-tag v-if="goldKpiFallbackLabel" size="small" type="warning" effect="plain">
              所选区间无数据,已显示 {{ goldKpiFallbackLabel }}
            </el-tag>
          </div>
          <el-date-picker
            v-model="goldKpiRange"
            type="daterange"
            range-separator="→"
            start-placeholder="开始"
            end-placeholder="结束"
            value-format="YYYY-MM-DD"
            :shortcuts="goldKpiShortcuts"
            size="small"
            style="width: 300px"
          />
        </div>
      </template>

      <div v-if="goldKpiError" class="gold-kpi-error">
        Gold 查询失败: {{ goldKpiError }}
      </div>
      <div v-else-if="goldKpi && goldKpi.billCount === 0" class="gold-kpi-empty">
        所选区间无 POS 数据 (请上传 Excel 或调整区间)
      </div>
      <el-row v-else-if="goldKpi" :gutter="16" class="gold-kpi-metrics">
        <el-col :span="marginKpi.rate > 0 ? 4 : 6">
          <el-statistic :value="goldKpi.totalRevenue" title="总营收" :precision="2" prefix="¥" />
          <div class="gold-kpi-sub">{{ goldKpi.dayCount }} 天</div>
        </el-col>
        <el-col :span="marginKpi.rate > 0 ? 4 : 6">
          <el-statistic :value="goldKpi.billCount" title="订单数" :precision="0" />
        </el-col>
        <el-col :span="marginKpi.rate > 0 ? 4 : 6">
          <el-statistic :value="goldKpi.avgBillValue ?? 0" title="客单价" :precision="2" prefix="¥" />
        </el-col>
        <el-col :span="marginKpi.rate > 0 ? 4 : 6">
          <el-statistic :value="goldKpi.storeCount" title="门店数" :precision="0" />
        </el-col>
        <!-- Phase 7+: margin KPIs if POS × recipe data exists -->
        <el-col v-if="marginKpi.rate > 0" :span="4">
          <el-statistic :value="marginKpi.rate * 100" title="平均毛利率" :precision="1" suffix="%" />
          <div class="gold-kpi-sub" style="color: #67c23a">基于 Gold</div>
        </el-col>
        <el-col v-if="marginKpi.rate > 0" :span="4">
          <el-statistic :value="marginKpi.profit" title="总毛利" :precision="2" prefix="¥" />
          <div class="gold-kpi-sub">
            <el-button size="small" type="text" @click="$router.push('/restaurant/analytics/gross-margin')">
              详细 →
            </el-button>
          </div>
        </el-col>
      </el-row>
      <div v-else class="gold-kpi-empty">正在加载...</div>

      <div v-if="goldKpi && goldKpi.topStores.length > 0" class="gold-kpi-stores">
        <div class="gold-kpi-stores-title">门店 Top 5 (按营收)</div>
        <el-table :data="goldKpi.topStores" size="small" stripe>
          <el-table-column prop="storeName" label="门店" />
          <el-table-column prop="revenue" label="营收" width="160" align="right">
            <template #default="{ row }">{{ formatCurrency(row.revenue) }}</template>
          </el-table-column>
          <el-table-column prop="billCount" label="订单数" width="120" align="right">
            <template #default="{ row }">{{ row.billCount.toLocaleString() }}</template>
          </el-table-column>
        </el-table>
      </div>

      <!-- Phase 7+: Top 3 毛利菜品 — encouraging "promote these" insight -->
      <div v-if="marginKpi.dishes.length > 0" class="gold-kpi-stores" style="margin-top: 14px">
        <div class="gold-kpi-stores-title" style="display:flex;align-items:center;gap:8px">
          <span>菜品 Top 3 (按毛利)</span>
          <el-tag size="small" type="success">跨模块: POS × 配方</el-tag>
        </div>
        <el-table :data="marginKpi.dishes" size="small" stripe>
          <el-table-column prop="name" label="菜品" />
          <el-table-column prop="grossProfit" label="毛利" width="140" align="right">
            <template #default="{ row }">¥{{ row.grossProfit.toFixed(2) }}</template>
          </el-table-column>
          <el-table-column prop="marginRate" label="毛利率" width="120" align="center">
            <template #default="{ row }">
              <el-tag :type="row.marginRate >= 0.6 ? 'success' : row.marginRate >= 0.4 ? '' : 'warning'" size="small">
                {{ (row.marginRate * 100).toFixed(1) }}%
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>

    <!-- Error — Apr 24 UX: adds retry button + specific action hint -->
    <el-alert
      v-if="lastError"
      type="error"
      :closable="true"
      :title="lastError"
      show-icon
      style="margin-top: 16px"
      @close="lastError = ''"
    >
      <template #default>
        <div style="margin-top: 8px; display: flex; gap: 8px; align-items: center;">
          <el-button size="small" type="primary" :loading="loading" @click="runAnalysis(true)">
            重新分析
          </el-button>
          <span v-if="errorActionHint" style="color: #909399; font-size: 13px;">
 {{ errorActionHint }}
          </span>
        </div>
      </template>
    </el-alert>

    <!-- Report sections -->
    <template v-if="report">
      <!-- Executive Summary -->
      <el-card class="section-card" shadow="hover">
        <template #header>
          <div class="section-title">
            <el-icon color="#409EFF"><InfoFilled /></el-icon>
            <span>执行摘要 (Executive Summary)</span>
            <el-tag v-if="performanceInfo" size="small" type="info">
              {{ performanceInfo.posRows.toLocaleString() }} 行, {{ performanceInfo.totalSeconds }}s
            </el-tag>
          </div>
        </template>
        <ul class="summary-list">
          <li v-for="(line, idx) in executiveSummary" :key="idx" class="summary-item">
            {{ line }}
          </li>
          <li v-if="executiveSummary.length === 0" class="summary-empty">
            无告警 (数据可能不足或健康状态)
          </li>
        </ul>
      </el-card>

      <!-- Financial Metrics 卡片 -->
      <el-card v-if="advancedTrafficPersona" class="section-card advanced-traffic-section" shadow="hover">
        <template #header>
          <div class="section-title">
            <el-icon color="#409EFF"><DataAnalysis /></el-icon>
            <span>高级客流画像分析</span>
            <el-tag size="small" type="warning">需额外开通</el-tag>
          </div>
        </template>
        <AdvancedTrafficPersonaCard :data="advancedTrafficPersona" />
      </el-card>

      <el-card v-if="financialMetrics" class="section-card" shadow="hover">
        <template #header>
          <div class="section-title">
            <el-icon color="#F56C6C"><Money /></el-icon>
            <span>财务指标 (Financial Metrics)</span>
          </div>
        </template>

        <el-row :gutter="16" class="metrics-row">
          <el-col :span="6">
            <div class="metric-card">
              <div class="metric-label">营业收入</div>
              <div class="metric-value">{{ formatCurrency(financialMetrics.revenue) }}</div>
              <div v-if="financialMetrics.revenueChangePct !== null && financialMetrics.revenueChangePct !== undefined" class="metric-delta">
                环比 {{ formatPercent(financialMetrics.revenueChangePct) }}
              </div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="metric-card">
              <div class="metric-label">食材成本率</div>
              <div class="metric-value">{{ formatPercent(financialMetrics.foodCostRatio) }}</div>
              <div v-if="financialMetrics.foodCostChangePct !== null && financialMetrics.foodCostChangePct !== undefined" class="metric-delta">
                环比 {{ formatPercent(financialMetrics.foodCostChangePct) }}
              </div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="metric-card">
              <div class="metric-label">人力成本率</div>
              <div class="metric-value">{{ formatPercent(financialMetrics.laborCostRatio) }}</div>
              <div v-if="financialMetrics.laborCostChangePct !== null && financialMetrics.laborCostChangePct !== undefined" class="metric-delta">
                环比 {{ formatPercent(financialMetrics.laborCostChangePct) }}
              </div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="metric-card" :class="{ 'metric-critical': (financialMetrics.costRigidity ?? 1) < 0.5 }">
              <div class="metric-label">成本弹性指数</div>
              <div class="metric-value">
                {{ financialMetrics.costRigidity !== null && financialMetrics.costRigidity !== undefined
                  ? financialMetrics.costRigidity.toFixed(3)
                  : '—' }}
              </div>
              <div class="metric-delta">健康 ≥ 0.85</div>
            </div>
          </el-col>
        </el-row>
      </el-card>

      <!-- Diagnostics -->
      <el-card v-if="diagnostics.length > 0" class="section-card" shadow="hover">
        <template #header>
          <div class="section-title">
            <el-icon color="#E6A23C"><WarningFilled /></el-icon>
            <span>诊断引擎 (Diagnostics) — {{ diagnostics.length }} 项</span>
          </div>
        </template>
        <el-collapse>
          <el-collapse-item
            v-for="(d, idx) in diagnostics"
            :key="idx"
            :name="idx"
          >
            <template #title>
              <el-tag :type="severityTagType(d.severity)" size="small">
                {{ d.severity.toUpperCase() }}
              </el-tag>
              <span class="diag-name">{{ d.metricNameZh }}</span>
              <span class="diag-value">实测 {{ d.actualValue }}</span>
              <span class="diag-status">{{ d.status }}</span>
            </template>
            <div class="diag-body">
              <p>{{ d.descriptionZh }}</p>
              <div v-if="d.suggestionZh && d.suggestionZh.length > 0">
                <h5>建议:</h5>
                <ul>
                  <li v-for="(s, si) in d.suggestionZh" :key="si">{{ s }}</li>
                </ul>
              </div>
              <div v-if="d.subSectorNotes && d.subSectorNotes.length > 0">
                <h5>子行业提示:</h5>
                <ul>
                  <li v-for="(n, ni) in d.subSectorNotes" :key="ni">{{ n }}</li>
                </ul>
              </div>
              <div v-if="d.playbookId" class="diag-playbook">
                Playbook: <code>{{ d.playbookId }}</code>
              </div>
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-card>

      <!-- Benchmark Alerts -->
      <el-card v-if="benchmarkAlerts.length > 0" class="section-card" shadow="hover">
        <template #header>
          <div class="section-title">
            <el-icon color="#F56C6C"><WarningFilled /></el-icon>
            <span>对标预警 (Benchmark Alerts) — {{ benchmarkAlerts.length }} 项</span>
          </div>
        </template>
        <div v-for="(a, idx) in benchmarkAlerts" :key="idx" class="alert-item">
          <el-tag :type="severityTagType(a.severity)" size="small">
 {{ a.severity === 'red' ? ' 严重' : a.severity === 'yellow' ? ' 警戒' : 'ℹ️ 信息' }}
          </el-tag>
          <div class="alert-content">
            <div class="alert-message">{{ a.messageZh }}</div>
            <div v-if="a.estimatedYearlyImpact" class="alert-impact">
 估算年度影响: <strong>{{ formatCurrency(Math.abs(a.estimatedYearlyImpact)) }}</strong>
            </div>
            <div v-if="a.actionHint" class="alert-action">建议: {{ a.actionHint }}</div>
          </div>
        </div>
      </el-card>

      <!-- Channel Margin Table -->
      <el-card v-if="channelMargin && channelMargin.rows.length > 0" class="section-card" shadow="hover">
        <template #header>
          <div class="section-title">
            <el-icon color="#67C23A"><TrendCharts /></el-icon>
            <span>渠道毛利率 (Channel Margin) — 整体 {{ formatPercent(channelMargin.overallGrossMarginPct) }}</span>
          </div>
        </template>

        <el-table :data="channelMargin.rows" stripe>
          <el-table-column prop="channel" label="渠道" width="130" />
          <el-table-column prop="revenue" label="营收" width="130">
            <template #default="{ row }">{{ formatCurrency(row.revenue) }}</template>
          </el-table-column>
          <el-table-column prop="orderCount" label="订单数" width="90" />
          <el-table-column prop="commissionRate" label="抽佣率" width="90">
            <template #default="{ row }">{{ formatPercent(row.commissionRate) }}</template>
          </el-table-column>
          <el-table-column prop="commissionAmount" label="抽佣金额" width="120">
            <template #default="{ row }">{{ formatCurrency(row.commissionAmount) }}</template>
          </el-table-column>
          <el-table-column prop="cogs" label="COGS" width="130">
            <template #default="{ row }">{{ formatCurrency(row.cogs) }}</template>
          </el-table-column>
          <el-table-column prop="cogsSource" label="COGS 来源" width="140">
            <template #default="{ row }">
              <el-tag size="small" :type="row.cogsSource === 'manual_override' ? 'success' : 'info'">
                {{ row.cogsSource }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="grossProfit" label="毛利" width="130">
            <template #default="{ row }">{{ formatCurrency(row.grossProfit) }}</template>
          </el-table-column>
          <el-table-column prop="grossMarginPct" label="毛利率" width="100">
            <template #default="{ row }">
              <strong :class="{ 'text-danger': row.grossMarginPct < 0.15 }">
                {{ formatPercent(row.grossMarginPct) }}
              </strong>
            </template>
          </el-table-column>
        </el-table>

        <el-alert
          v-for="(advice, idx) in channelMargin.adviceZh"
          :key="idx"
          :title="advice"
          type="info"
          :closable="false"
          style="margin-top: 8px"
        />
      </el-card>

      <!-- ═══ Week 4 新增 sections ═══════════════════════ -->

      <!-- Week 4.1: Store P&L One Pager (销售杀手级单页) -->
      <el-card v-if="storePnlOnePager" class="section-card pnl-card" shadow="hover">
        <template #header>
          <div class="section-title">
            <el-icon color="#F56C6C"><Money /></el-icon>
            <span>单店 P&L 一页纸 (Store P&L One Pager)</span>
            <el-tag size="small" type="info">Week 4.1</el-tag>
          </div>
        </template>
        <div class="pnl-headline" :class="`headline-${storePnlOnePager.headlineColor}`">
          {{ storePnlOnePager.emoji }} {{ storePnlOnePager.headline }}
        </div>
        <div v-if="storePnlOnePager.topInsights && storePnlOnePager.topInsights.length > 0" class="pnl-insights">
          <h5>关键洞察:</h5>
          <ul>
            <li v-for="(ins, idx) in storePnlOnePager.topInsights" :key="idx">{{ ins }}</li>
          </ul>
        </div>
        <div v-if="storePnlOnePager.topRecommendations && storePnlOnePager.topRecommendations.length > 0" class="pnl-recommendations">
          <h5>立即执行:</h5>
          <ul>
            <li v-for="(rec, idx) in storePnlOnePager.topRecommendations" :key="idx">{{ rec }}</li>
          </ul>
        </div>
      </el-card>

      <!-- Week 4.2: Dining Heatmap (营业时段热力图) -->
      <el-card v-if="diningHeatmap && diningHeatmap.cells.length > 0" class="section-card" shadow="hover">
        <template #header>
          <div class="section-title">
            <el-icon color="#E6A23C"><TrendCharts /></el-icon>
            <span>营业时段热力图 (Dining Heatmap)</span>
            <el-tag size="small" type="info">Week 4.2</el-tag>
          </div>
        </template>

        <el-row :gutter="16" style="margin-bottom: 12px">
          <el-col :span="6">
            <el-statistic :value="diningHeatmap.totalRevenue" title="总营收" :precision="0" prefix="¥" />
          </el-col>
          <el-col :span="6">
            <el-statistic :value="diningHeatmap.totalOrders" title="总订单" />
          </el-col>
          <el-col :span="6">
            <el-statistic :value="diningHeatmap.avgHourlyRevenue" title="小时均收" :precision="0" prefix="¥" />
          </el-col>
          <el-col :span="6">
            <el-statistic :value="diningHeatmap.peakOffPeakRatio" title="峰谷比" :precision="1" suffix="x" />
          </el-col>
        </el-row>

        <h5>餐段营收占比:</h5>
        <el-table :data="diningHeatmap.mealPeriods" size="small" stripe>
          <el-table-column prop="period" label="餐段" width="100" />
          <el-table-column prop="hourRange" label="时段">
            <template #default="{ row }">{{ row.hourRange[0] }}:00 - {{ row.hourRange[1] % 24 }}:00</template>
          </el-table-column>
          <el-table-column prop="revenue" label="营收" width="130">
            <template #default="{ row }">{{ formatCurrency(row.revenue) }}</template>
          </el-table-column>
          <el-table-column prop="orderCount" label="订单数" width="100" />
          <el-table-column prop="revenuePct" label="占比" width="100">
            <template #default="{ row }">{{ formatPercent(row.revenuePct) }}</template>
          </el-table-column>
          <el-table-column prop="avgTicket" label="客单" width="100">
            <template #default="{ row }">{{ formatCurrency(row.avgTicket) }}</template>
          </el-table-column>
        </el-table>

        <!-- ECharts Heatmap (7x24) -->
        <div
          ref="heatmapChartRef"
          style="width: 100%; height: 300px; margin-top: 16px"
        />

        <el-row :gutter="16" style="margin-top: 16px">
          <el-col :span="12">
 <h5> TOP 5 高峰时段</h5>
            <ul class="period-list">
              <li v-for="(p, idx) in diningHeatmap.topPeakHours" :key="'peak-' + idx">
                {{ p.emoji }} {{ p.dayLabel }} {{ p.hour }}:00 —
                <strong>{{ formatCurrency(p.revenue) }}</strong>
                ({{ p.orderCount }} 单)
              </li>
            </ul>
          </el-col>
          <el-col :span="12">
 <h5> TOP 5 低谷时段</h5>
            <ul class="period-list">
              <li v-for="(p, idx) in diningHeatmap.bottomOffPeakHours" :key="'off-' + idx">
                {{ p.emoji }} {{ p.dayLabel }} {{ p.hour }}:00 —
                {{ formatCurrency(p.revenue) }}
                ({{ p.orderCount }} 单)
              </li>
            </ul>
          </el-col>
        </el-row>

        <el-alert
          v-for="(ins, idx) in diningHeatmap.insights"
          :key="'ins-' + idx"
          :title="ins"
          type="info"
          :closable="false"
          style="margin-top: 6px"
        />
      </el-card>

      <!-- Week 4.3a: Stored Value Dependency -->
      <el-card v-if="storedValueDependency" class="section-card" shadow="hover">
        <template #header>
          <div class="section-title">
            <el-icon color="#F56C6C"><WarningFilled /></el-icon>
            <span>充卡依赖度 (Stored Value Dependency)</span>
            <el-tag :type="severityTagType(storedValueDependency.severity)" size="small">
              {{ storedValueDependency.severity.toUpperCase() }}
            </el-tag>
            <el-tag size="small" type="info">Week 4.3</el-tag>
          </div>
        </template>
        <div class="sv-message">{{ storedValueDependency.messageZh }}</div>
        <el-row :gutter="16" style="margin-top: 12px">
          <el-col :span="8">
            <el-statistic :value="storedValueDependency.storedValueGiveaway" title="充卡赠送" :precision="0" prefix="¥" />
          </el-col>
          <el-col :span="8">
            <el-statistic :value="storedValueDependency.dependencyPct * 100" title="占营收比例" :precision="2" suffix="%" />
          </el-col>
          <el-col :span="8">
            <el-statistic
              v-if="storedValueDependency.chargeToRevenueRatio !== null && storedValueDependency.chargeToRevenueRatio !== undefined"
              :value="(storedValueDependency.chargeToRevenueRatio || 0) * 100"
              title="新充占营收"
              :precision="1"
              suffix="%"
            />
          </el-col>
        </el-row>
        <ul v-if="storedValueDependency.warnings.length > 0" class="sv-warnings">
 <li v-for="(w, idx) in storedValueDependency.warnings" :key="idx">️ {{ w }}</li>
        </ul>
        <ul v-if="storedValueDependency.recommendations.length > 0" class="sv-recs">
 <li v-for="(r, idx) in storedValueDependency.recommendations" :key="idx"> {{ r }}</li>
        </ul>
      </el-card>

      <!-- Week 4.3b: Long Tail SKU -->
      <el-card v-if="longTailSku && longTailSku.totalSkuCount > 0" class="section-card" shadow="hover">
        <template #header>
          <div class="section-title">
            <el-icon color="#909399"><InfoFilled /></el-icon>
            <span>长尾 SKU 识别 — 建议下架 {{ longTailSku.recommendedDelistCount }} 个</span>
            <el-tag size="small" type="info">Week 4.3</el-tag>
          </div>
        </template>
        <el-row :gutter="16">
          <el-col :span="6">
            <el-statistic :value="longTailSku.totalSkuCount" title="总 SKU 数" />
          </el-col>
          <el-col :span="6">
            <el-statistic :value="longTailSku.top20PctSkusContribute * 100" title="TOP 20% 贡献" :precision="1" suffix="%" />
          </el-col>
          <el-col :span="6">
            <el-statistic :value="longTailSku.recommendedDelistCount" title="建议下架" />
          </el-col>
          <el-col :span="6">
            <el-statistic :value="longTailSku.estimatedCostSaving" title="年省成本" :precision="0" prefix="¥" />
          </el-col>
        </el-row>

        <el-table
          v-if="longTailSku.lowEfficiencySkus.length > 0"
          :data="longTailSku.lowEfficiencySkus.slice(0, 15)"
          size="small"
          stripe
          style="margin-top: 12px"
        >
          <el-table-column prop="name" label="SKU" />
          <el-table-column prop="quantity" label="销量" width="90" />
          <el-table-column prop="revenue" label="营收" width="120">
            <template #default="{ row }">{{ formatCurrency(row.revenue) }}</template>
          </el-table-column>
          <el-table-column prop="score" label="综合分" width="100">
            <template #default="{ row }">{{ (row.score * 100).toFixed(1) }}%</template>
          </el-table-column>
          <el-table-column prop="recommendation" label="建议" width="140">
            <template #default="{ row }">
              <el-tag :type="row.recommendation.includes('下架') ? 'danger' : 'warning'" size="small">
                {{ row.recommendation }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" />
        </el-table>

        <el-alert
          v-for="(rec, idx) in longTailSku.recommendations"
          :key="'lt-rec-' + idx"
          :title="rec"
          type="info"
          :closable="false"
          style="margin-top: 6px"
        />
      </el-card>

      <!-- Week 4.5: Review Analysis -->
      <el-card v-if="reviewAnalysis && reviewAnalysis.totalReviews > 0" class="section-card" shadow="hover">
        <template #header>
          <div class="section-title">
            <el-icon color="#67C23A"><CircleCheckFilled /></el-icon>
            <span>
              大众点评分析 — {{ reviewAnalysis.totalReviews }} 评论, 平均 {{ reviewAnalysis.avgRating.toFixed(2) }} 星
            </span>
            <el-tag size="small" type="info">Week 4.5</el-tag>
          </div>
        </template>

        <div v-if="reviewAnalysis.ratingTrend" class="review-trend">
          <strong>趋势: </strong>{{ trendDirectionLabel(reviewAnalysis.ratingTrend.direction) }}
          <span class="trend-delta">
            ({{ reviewAnalysis.ratingTrend.earliestAvg.toFixed(2) }} →
            {{ reviewAnalysis.ratingTrend.latestAvg.toFixed(2) }},
            delta {{ reviewAnalysis.ratingTrend.totalDelta > 0 ? '+' : '' }}{{ reviewAnalysis.ratingTrend.totalDelta.toFixed(2) }})
          </span>
        </div>

        <el-row :gutter="16" style="margin-top: 12px">
          <el-col :span="12">
 <h5> TOP 客户口中的招牌 (真正的好菜)</h5>
            <ul class="dish-list">
              <li v-for="d in reviewAnalysis.topPraisedDishes" :key="'p-' + d.dishName">
                <strong>{{ d.dishName }}</strong> —
                {{ d.mentionCount }} 次提及,
                {{ (d.positiveRate * 100).toFixed(0) }}% 好评
              </li>
            </ul>
          </el-col>
          <el-col :span="12">
 <h5> 差评集中 (需检查/下架)</h5>
            <ul class="dish-list">
              <li v-for="d in reviewAnalysis.topComplainedDishes" :key="'c-' + d.dishName">
                <strong>{{ d.dishName }}</strong> —
                差评 {{ d.negativeCount }} 次,
                好评率仅 {{ (d.positiveRate * 100).toFixed(0) }}%
              </li>
            </ul>
          </el-col>
        </el-row>

        <div v-if="reviewAnalysis.hiddenGems && reviewAnalysis.hiddenGems.length > 0">
 <h5> 潜力菜 (低曝光高好评)</h5>
          <ul class="dish-list">
            <li v-for="d in reviewAnalysis.hiddenGems" :key="'g-' + d.dishName">
              <strong>{{ d.dishName }}</strong> —
              {{ d.mentionCount }} 次提及,
              {{ (d.positiveRate * 100).toFixed(0) }}% 好评
            </li>
          </ul>
        </div>

        <el-alert
          v-for="(alert, idx) in reviewAnalysis.riskAlerts"
          :key="'r-alert-' + idx"
          :title="alert"
          type="warning"
          :closable="false"
          style="margin-top: 6px"
        />
      </el-card>

      <!-- W5.4: Member RFM Analysis (会员 RFM 分析) -->
      <el-card v-if="memberRfm && memberRfm.analyzedMembers > 0" class="section-card" shadow="hover">
        <template #header>
          <div class="section-title">
            <el-icon color="#67C23A"><DataAnalysis /></el-icon>
            <span>会员 RFM 分析 — {{ memberRfm.analyzedMembers }} 会员</span>
            <el-tag size="small" type="info">W5.4</el-tag>
          </div>
        </template>

        <el-row :gutter="16" style="margin-bottom: 12px">
          <el-col :span="6">
            <el-statistic :value="memberRfm.avgRecency" title="平均 Recency (天)" :precision="0" />
          </el-col>
          <el-col :span="6">
            <el-statistic :value="memberRfm.avgFrequency" title="平均 Frequency" :precision="1" />
          </el-col>
          <el-col :span="6">
            <el-statistic :value="memberRfm.avgMonetary" title="平均 Monetary" :precision="0" prefix="¥" />
          </el-col>
          <el-col :span="6">
            <el-statistic :value="memberRfm.analyzedMembers" title="参与分析会员" />
          </el-col>
        </el-row>

        <h5>客群分布</h5>
        <el-table :data="Object.entries(memberRfm.segmentCounts).map(([segment, count]) => ({
          segment,
          count,
          revenue: memberRfm.segmentRevenue[segment] || 0,
          pct: (count / memberRfm.analyzedMembers * 100).toFixed(1),
        }))" size="small" stripe>
          <el-table-column label="客群" width="140">
            <template #default="{ row }">
              <el-tag :type="segmentTagType(row.segment)" size="small">
                {{ row.segment }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="count" label="人数" width="100" />
          <el-table-column prop="pct" label="占比" width="100">
            <template #default="{ row }">{{ row.pct }}%</template>
          </el-table-column>
          <el-table-column prop="revenue" label="贡献营收" width="160">
            <template #default="{ row }">{{ formatCurrency(row.revenue) }}</template>
          </el-table-column>
        </el-table>

        <!-- ECharts RFM Pie Chart -->
        <div
          ref="rfmPieChartRef"
          style="width: 100%; height: 300px; margin-top: 12px"
        />
        <!-- Phase B Moderate: PIE → PROPORTION insight (segment distribution) -->
        <ChartInsightProvider
          v-if="rfmPieChartWithMeta"
          :chart="rfmPieChartWithMeta"
          :perms="insightPerms"
          :factory-id="factoryId || ''"
          depth="detailed"
        />

        <el-row :gutter="16" style="margin-top: 16px">
          <el-col :span="12">
 <h5> TOP 5 Champions (最佳客户)</h5>
            <ul class="member-list">
              <li v-for="m in memberRfm.topChampions.slice(0, 5)" :key="'ch-' + m.memberId">
                <strong>{{ m.memberId }}</strong> —
                消费 {{ m.frequency }} 次,
                {{ formatCurrency(m.monetary) }},
                {{ m.recencyDays }} 天前
              </li>
            </ul>
          </el-col>
          <el-col :span="12">
 <h5>️ TOP 5 At Risk (召回候选)</h5>
            <ul class="member-list">
              <li v-for="m in memberRfm.atRiskMembers.slice(0, 5)" :key="'ar-' + m.memberId">
                <strong>{{ m.memberId }}</strong> —
                曾消费 {{ formatCurrency(m.monetary) }},
                已 {{ m.recencyDays }} 天未来
              </li>
            </ul>
          </el-col>
        </el-row>

        <el-alert
          v-for="(ins, idx) in memberRfm.insights"
          :key="'rfm-ins-' + idx"
          :title="ins"
          type="info"
          :closable="false"
          style="margin-top: 6px"
        />
      </el-card>

      <!-- W5.6: Temporal Comparison (同店同比) -->
      <el-card
        v-if="temporalComparison && temporalComparison.mode !== 'insufficient'"
        class="section-card"
        shadow="hover"
      >
        <template #header>
          <div class="section-title">
            <el-icon color="#409EFF"><TrendCharts /></el-icon>
            <span>
              同店同比 (Temporal Comparison) — {{ modeLabel(temporalComparison.mode) }}
            </span>
            <el-tag size="small" type="info">W5.6</el-tag>
          </div>
        </template>

        <div class="temporal-message">
          {{ temporalComparison.messageZh }}
        </div>

        <el-row :gutter="16" style="margin-top: 12px">
          <el-col :span="8">
            <el-statistic :value="temporalComparison.monthsAvailable" title="可用月数" />
          </el-col>
          <el-col :span="8">
            <div class="metric-card">
              <div class="metric-label">当期</div>
              <div class="metric-value">{{ temporalComparison.currentPeriod }}</div>
            </div>
          </el-col>
          <el-col :span="8">
            <div class="metric-card">
              <div class="metric-label">对比期</div>
              <div class="metric-value">{{ temporalComparison.comparePeriod }}</div>
            </div>
          </el-col>
        </el-row>

        <el-table
          :data="temporalComparison.deltas.slice(0, 15)"
          size="small"
          stripe
          style="margin-top: 12px"
        >
          <el-table-column prop="groupName" label="门店" />
          <el-table-column prop="currentValue" label="当期" width="130">
            <template #default="{ row }">{{ formatCurrency(row.currentValue) }}</template>
          </el-table-column>
          <el-table-column prop="compareValue" label="对比期" width="130">
            <template #default="{ row }">{{ formatCurrency(row.compareValue) }}</template>
          </el-table-column>
          <el-table-column prop="deltaPct" label="变化" width="130">
            <template #default="{ row }">
              <strong
                :class="{
                  'text-success': row.trend === 'up',
                  'text-danger': row.trend === 'down',
                }"
              >
                {{ trendIcon(row.trend) }}
                {{ row.deltaPct > 0 ? '+' : '' }}{{ (row.deltaPct * 100).toFixed(1) }}%
              </strong>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- W6.4: Multi-Store Comparison (多门店对比) -->
      <el-card v-if="multiStoreComparison" class="section-card" shadow="hover">
        <template #header>
          <div class="section-title">
            <el-icon color="#409EFF"><DataAnalysis /></el-icon>
            <span>多门店对比 (Multi-Store Comparison) — {{ multiStoreComparison.storeCount }} 门店</span>
            <el-tag size="small" type="info">W6.4</el-tag>
          </div>
        </template>

        <el-row :gutter="16" style="margin-bottom: 12px">
          <el-col :span="8">
            <el-statistic :value="multiStoreComparison.storeCount" title="门店数量" />
          </el-col>
          <el-col :span="8">
            <el-statistic :value="multiStoreComparison.revenueSpreadRatio" title="营收离散系数" :precision="2" />
          </el-col>
          <el-col :span="8">
            <el-statistic :value="multiStoreComparison.anomalies.length" title="异常项" />
          </el-col>
        </el-row>

        <!-- Top / Bottom store highlight -->
        <el-row :gutter="16" style="margin-bottom: 12px">
          <el-col :span="12">
            <div class="metric-card store-highlight-top">
              <div class="metric-label">TOP 门店</div>
              <div class="metric-value">{{ multiStoreComparison.topStore.storeName }}</div>
              <div class="metric-delta">
                {{ formatCurrency(multiStoreComparison.topStore.totalRevenue) }} /
                客单 {{ formatCurrency(multiStoreComparison.topStore.avgTicket) }}
              </div>
            </div>
          </el-col>
          <el-col :span="12">
            <div class="metric-card store-highlight-bottom">
              <div class="metric-label">末位门店</div>
              <div class="metric-value">{{ multiStoreComparison.bottomStore.storeName }}</div>
              <div class="metric-delta">
                {{ formatCurrency(multiStoreComparison.bottomStore.totalRevenue) }} /
                客单 {{ formatCurrency(multiStoreComparison.bottomStore.avgTicket) }}
              </div>
            </div>
          </el-col>
        </el-row>

        <!-- Rankings table -->
        <el-table
          :data="multiStoreComparison.storeRankings"
          size="small"
          stripe
          style="margin-bottom: 12px"
        >
          <el-table-column prop="rank" label="#" width="60" />
          <el-table-column prop="storeName" label="门店" />
          <el-table-column prop="totalRevenue" label="营收" width="130">
            <template #default="{ row }">{{ formatCurrency(row.totalRevenue) }}</template>
          </el-table-column>
          <el-table-column prop="orderCount" label="订单" width="90" />
          <el-table-column prop="avgTicket" label="客单" width="100">
            <template #default="{ row }">{{ formatCurrency(row.avgTicket) }}</template>
          </el-table-column>
          <el-table-column prop="uniqueSkuCount" label="SKU 数" width="90" />
          <el-table-column prop="vsAvgRevenuePct" label="vs 均值" width="120">
            <template #default="{ row }">
              <strong :class="{ 'text-success': row.vsAvgRevenuePct > 0, 'text-danger': row.vsAvgRevenuePct < 0 }">
                {{ row.vsAvgRevenuePct > 0 ? '+' : '' }}{{ (row.vsAvgRevenuePct * 100).toFixed(1) }}%
              </strong>
            </template>
          </el-table-column>
        </el-table>

        <!-- Anomalies -->
        <template v-if="multiStoreComparison.anomalies.length > 0">
          <h5 style="margin: 0 0 8px; color: #303133;">异常检测</h5>
          <div
            v-for="(a, idx) in multiStoreComparison.anomalies"
            :key="'msa-' + idx"
            style="margin-bottom: 6px;"
          >
            <el-tag :type="a.severity === 'critical' ? 'danger' : 'warning'" size="small">
              {{ a.severity }}
            </el-tag>
            <span style="margin-left: 8px; color: #606266;">
              {{ a.storeName }} — {{ a.metric }}:
              {{ a.value.toFixed(1) }} vs 链均 {{ a.chainAvg.toFixed(1) }}
              ({{ a.deltaPct > 0 ? '+' : '' }}{{ (a.deltaPct * 100).toFixed(0) }}%)
            </span>
          </div>
        </template>

        <!-- Insights + Recommendations -->
        <el-alert
          v-for="(ins, idx) in multiStoreComparison.insights"
          :key="'ms-ins-' + idx"
          :title="ins"
          type="info"
          :closable="false"
          show-icon
          style="margin-top: 6px"
        />
        <el-alert
          v-for="(rec, idx) in multiStoreComparison.recommendations"
          :key="'ms-rec-' + idx"
          :title="rec"
          type="warning"
          :closable="false"
          show-icon
          style="margin-top: 6px"
        />
      </el-card>

      <!-- W5.7: Calibration History (Layer A 月度校准报告) -->
      <el-card v-if="calibrationHistory" class="section-card" shadow="hover">
        <template #header>
          <div class="section-title">
            <el-icon color="#409EFF"><TrendCharts /></el-icon>
            <span>月度校准历史 (Calibration History)</span>
            <el-tag size="small" type="info">W5.7</el-tag>
          </div>
        </template>
        <!-- Summary -->
        <p style="margin: 0 0 12px; color: #606266;">{{ calibrationHistory.summary }}</p>
        <!-- Factor Trend Table -->
        <h4 style="margin: 0 0 8px; color: #303133;">食材成本率走势</h4>
        <el-table :data="calibrationHistory.periods" border size="small" style="margin-bottom: 16px;">
          <el-table-column prop="period" label="月份" width="110" />
          <el-table-column label="采购额" width="130">
            <template #default="{ row }">{{ formatCurrency(row.totalPurchase) }}</template>
          </el-table-column>
          <el-table-column label="营收" width="130">
            <template #default="{ row }">{{ formatCurrency(row.totalRevenue) }}</template>
          </el-table-column>
          <el-table-column label="食材率" width="100">
            <template #default="{ row }">
              <strong>{{ (row.overallRatio * 100).toFixed(1) }}%</strong>
            </template>
          </el-table-column>
          <el-table-column label="vs 基准" width="100">
            <template #default="{ row }">
              <el-tag
                :type="row.factorVsBaseline > 1.1 ? 'danger' : row.factorVsBaseline < 0.9 ? 'success' : 'info'"
                size="small"
              >
                {{ row.factorVsBaseline.toFixed(2) }}x
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <!-- ECharts Calibration Trend Line -->
        <div
          v-if="calibrationHistory.factorTrend && calibrationHistory.factorTrend.length > 0"
          ref="calibrationChartRef"
          style="width: 100%; height: 300px; margin-bottom: 16px"
        />
        <!-- Phase B Moderate: LINE×2 → COMPARISON insight (校准因子 vs 食材率) -->
        <!-- SKIP: HEATMAP (heatmapChartRef) — exotic 7×24 grid, not Tier1-coverable -->
        <ChartInsightProvider
          v-if="calibrationChartWithMeta"
          :chart="calibrationChartWithMeta"
          :perms="insightPerms"
          :factory-id="factoryId || ''"
          depth="detailed"
        />

        <!-- Category Volatility (Top 5) -->
        <template v-if="calibrationHistory.categoryVolatility.length > 0">
          <h4 style="margin: 0 0 8px; color: #303133;">类目波动 (Top 5)</h4>
          <el-table :data="calibrationHistory.categoryVolatility.slice(0, 5)" border size="small" style="margin-bottom: 16px;">
            <el-table-column prop="category" label="类目" width="120" />
            <el-table-column label="均值" width="100">
              <template #default="{ row }">{{ (row.meanRatio * 100).toFixed(2) }}%</template>
            </el-table-column>
            <el-table-column label="CV%" width="100">
              <template #default="{ row }">
                <el-tag
                  :type="row.cvPct > 0.3 ? 'danger' : row.cvPct > 0.2 ? 'warning' : 'info'"
                  size="small"
                >
                  {{ (row.cvPct * 100).toFixed(0) }}%
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="peakPeriod" label="峰值月" width="100" />
            <el-table-column prop="troughPeriod" label="谷值月" width="100" />
          </el-table>
        </template>
        <!-- Anomalies -->
        <template v-if="calibrationHistory.anomalies.length > 0">
          <h4 style="margin: 0 0 8px; color: #303133;">异常告警</h4>
          <div v-for="(anomaly, idx) in calibrationHistory.anomalies.slice(0, 5)" :key="idx" style="margin-bottom: 6px;">
            <el-tag :type="anomaly.severity === 'critical' ? 'danger' : 'warning'" size="small">
              {{ anomaly.severity }}
            </el-tag>
            <span style="margin-left: 8px; color: #606266;">
              {{ anomaly.period }}
              <template v-if="anomaly.category"> / {{ anomaly.category }}</template>
              : 实际 {{ (anomaly.actualRatio * 100).toFixed(1) }}% vs 预期 {{ (anomaly.expectedRatio * 100).toFixed(1) }}%
              ({{ anomaly.deltaPct > 0 ? '+' : '' }}{{ (anomaly.deltaPct * 100).toFixed(0) }}%)
            </span>
          </div>
        </template>
        <!-- Insights + Recommendations -->
        <template v-if="calibrationHistory.insights.length > 0">
          <el-alert
            v-for="(insight, idx) in calibrationHistory.insights"
            :key="'ci-' + idx"
            :title="insight"
            type="info"
            :closable="false"
            show-icon
            style="margin-top: 8px;"
          />
        </template>
        <template v-if="calibrationHistory.recommendations.length > 0">
          <el-alert
            v-for="(rec, idx) in calibrationHistory.recommendations"
            :key="'cr-' + idx"
            :title="rec"
            type="warning"
            :closable="false"
            show-icon
            style="margin-top: 8px;"
          />
        </template>
      </el-card>

      <!-- Week 4.4: BOM Layer Status -->
      <el-card v-if="bomLayerStatus" class="section-card" shadow="hover">
        <template #header>
          <div class="section-title">
            <el-icon color="#409EFF"><DataAnalysis /></el-icon>
            <span>BOM 精度层级 (BOM Layer Status)</span>
            <el-tag size="small" type="info">Week 4.4</el-tag>
          </div>
        </template>
        <el-row :gutter="16">
          <el-col :span="6">
            <div class="metric-card">
              <div class="metric-label">当前层级</div>
              <div class="metric-value">{{ bomLayerStatus.currentLayer }}</div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="metric-card">
              <div class="metric-label">当前精度</div>
              <div class="metric-value">±{{ bomLayerStatus.currentAccuracyPp }}%</div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="metric-card">
              <div class="metric-label">Layer 2 SKU 表单</div>
              <div class="metric-value">{{ bomLayerStatus.layer2SkuCount }}</div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="metric-card">
              <div class="metric-label">Layer 3 月采购数据</div>
              <div class="metric-value">{{ bomLayerStatus.layer3PeriodCount }}</div>
            </div>
          </el-col>
        </el-row>
        <el-alert
          :title="bomLayerStatus.upgradeHint"
          type="info"
          :closable="false"
          style="margin-top: 12px"
        />
      </el-card>

      <!-- W6: Review Collection Status (评论数据概览) -->
      <el-card v-if="reviewStats" class="section-card" shadow="hover">
        <template #header>
          <div class="section-title">
            <el-icon color="#67C23A"><CircleCheckFilled /></el-icon>
            <span>评论数据管理 (Review Collection)</span>
            <el-tag size="small" type="info">W6</el-tag>
          </div>
        </template>
        <el-row :gutter="16">
          <el-col :span="8">
            <el-statistic :value="reviewStats.totalReviews" title="评论总数" />
          </el-col>
          <el-col :span="8">
            <el-statistic :value="reviewStats.storeCount" title="已注册门店" />
          </el-col>
          <el-col :span="8">
            <div class="metric-card">
              <div class="metric-label">最近上传</div>
              <div class="metric-value" style="font-size: 14px;">
                {{ reviewStats.lastUpload || '暂无' }}
              </div>
            </div>
          </el-col>
        </el-row>
        <el-alert
          title="评论分析结果会在 V2 分析中自动包含 (Week 4.5 Review Analysis)。如需管理评论源或上传评论, 请使用 API 接口。"
          type="info"
          :closable="false"
          style="margin-top: 12px"
        />
      </el-card>

      <!-- ═══ Week 2-3 existing sections ═══════════════ -->

      <!-- Menu Normalization -->
      <el-card v-if="menuNormalization" class="section-card" shadow="hover">
        <template #header>
          <div class="section-title">
            <el-icon color="#909399"><CircleCheckFilled /></el-icon>
            <span>命名归一 (Menu Normalization)</span>
          </div>
        </template>
        <el-row :gutter="16">
          <el-col :span="8">
            <el-statistic :value="menuNormalization.originalUniqueCount" title="原始 SKU 数" />
          </el-col>
          <el-col :span="8">
            <el-statistic :value="menuNormalization.normalizedUniqueCount" title="归一后 SKU 数" />
          </el-col>
          <el-col :span="8">
            <el-statistic
              :value="menuNormalization.reductionPct"
              title="减少百分比 (%)"
              :precision="2"
            />
          </el-col>
        </el-row>
        <p class="menu-note">{{ menuNormalization.note }}</p>
      </el-card>
    </template>

    <!-- Empty state (P2-16: shrink 图 from default ~200px to 80px, 压缩占屏) -->
    <el-empty
      v-else-if="!loading"
      :image-size="80"
      description="选择上传数据 → 点「跑 V2 分析」 → 查看餐饮综合分析 (诊断 / 预警 / 渠道毛利 / 对标 / BOM / 评论等 15+ section)"
      style="margin-top: 16px; padding: 16px;"
    />

    <!-- W5.2 — BOM Ingest Dialog -->
    <BomIngestDialog
      v-model="bomIngestDialogVisible"
      :factory-id="factoryId || ''"
      @data-changed="onBomDataChanged"
    />

    <!-- P5 Task 5.3: chat drawer -->
    <el-drawer
      v-model="chatDrawerVisible"
      direction="rtl"
      size="480px"
      :with-header="false"
      :append-to-body="true"
      :destroy-on-close="false"
    >
      <!-- The chat panel answers over whole-store data (backend derives the
           tenant from JWT); it deliberately ignores subSector / the selected
           upload Excel, so those are not bound here (F2: synthesis-only). -->
      <RestaurantChatPanel :factory-id="factoryId || ''" />
    </el-drawer>

    <!-- Week 6 Template Surfacing: show analysis results for this page -->
    <TemplateGrid page-key="restaurantv2" :factory-id="factoryId || ''" />
  </div>
</template>

<style scoped>
.restaurant-v2-dashboard {
  padding: 16px;
}

.header-card {
  margin-bottom: 16px;
}

.gold-kpi-card {
  margin-bottom: 16px;
  border-top: 3px solid #67C23A;
}
.gold-kpi-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.gold-kpi-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 15px;
}
.gold-kpi-metrics {
  padding: 4px 0;
}
.gold-kpi-sub {
  color: #909399;
  font-size: 12px;
  margin-top: 4px;
}
.gold-kpi-empty, .gold-kpi-error {
  color: #909399;
  text-align: center;
  padding: 24px 0;
}
.gold-kpi-error { color: #F56C6C; }
.gold-kpi-stores {
  margin-top: 16px;
}
.gold-kpi-stores-title {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 8px;
  color: #606266;
}

.header-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 16px;
}

.header-controls {
  padding: 8px 0;
}

.financial-form {
  margin-top: 16px;
  padding: 16px;
  background: #f5f7fa;
  border-radius: 4px;
}

.financial-form h4 {
  margin: 0 0 12px;
  color: #303133;
}

.section-card {
  margin-bottom: 16px;
}

.advanced-traffic-section {
  border-top: 3px solid #409eff;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}

.summary-list {
  margin: 0;
  padding-left: 20px;
}

.summary-item {
  margin-bottom: 8px;
  line-height: 1.6;
  color: #606266;
}

.summary-empty {
  color: #909399;
  font-style: italic;
}

.metrics-row {
  margin-top: 8px;
}

.metric-card {
  padding: 16px;
  background: #f5f7fa;
  border-radius: 4px;
  text-align: center;
  border-left: 3px solid #409eff;
}

.metric-card.metric-critical {
  border-left-color: #f56c6c;
  background: #fef0f0;
}

.metric-label {
  color: #909399;
  font-size: 13px;
  margin-bottom: 6px;
}

.metric-value {
  font-size: 22px;
  font-weight: 600;
  color: #303133;
}

.metric-delta {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.diag-name {
  margin: 0 12px;
  font-weight: 600;
}

.diag-value,
.diag-status {
  margin-left: 12px;
  color: #909399;
  font-size: 13px;
}

.diag-body {
  padding: 8px 16px;
  color: #606266;
  line-height: 1.7;
}

.diag-body h5 {
  margin: 8px 0 4px;
  color: #303133;
}

.diag-body ul {
  margin: 0;
  padding-left: 20px;
}

.diag-playbook {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}

.alert-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px;
  border-bottom: 1px solid #ebeef5;
}

.alert-item:last-child {
  border-bottom: none;
}

.alert-content {
  flex: 1;
}

.alert-message {
  color: #303133;
  line-height: 1.6;
}

.alert-impact {
  color: #f56c6c;
  font-size: 13px;
  margin-top: 4px;
}

.alert-action {
  color: #909399;
  font-size: 13px;
  margin-top: 4px;
}

.text-danger {
  color: #f56c6c;
}

.menu-note {
  margin-top: 16px;
  color: #909399;
  font-size: 13px;
  font-style: italic;
}

/* Week 4 specific styles */
.pnl-card {
  border-left: 4px solid #F56C6C;
}

.pnl-headline {
  font-size: 20px;
  font-weight: 700;
  padding: 16px;
  border-radius: 4px;
  text-align: center;
  margin-bottom: 12px;
}

.headline-red {
  background: #fef0f0;
  color: #f56c6c;
  border: 1px solid #fbc4c4;
}

.headline-yellow {
  background: #fdf6ec;
  color: #e6a23c;
  border: 1px solid #f5dab1;
}

.headline-green {
  background: #f0f9eb;
  color: #67c23a;
  border: 1px solid #c2e7b0;
}

.pnl-insights,
.pnl-recommendations {
  margin-top: 12px;
}

.pnl-insights h5,
.pnl-recommendations h5 {
  margin: 0 0 4px;
  color: #303133;
}

.pnl-insights ul,
.pnl-recommendations ul {
  margin: 0;
  padding-left: 20px;
  color: #606266;
  line-height: 1.7;
}

.period-list {
  margin: 0;
  padding-left: 20px;
  color: #606266;
  line-height: 1.8;
}

.sv-message {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  padding: 8px 12px;
  background: #f5f7fa;
  border-radius: 4px;
}

.sv-warnings,
.sv-recs {
  margin-top: 12px;
  padding-left: 20px;
  line-height: 1.7;
  color: #606266;
}

.review-trend {
  font-size: 14px;
  padding: 8px 12px;
  background: #f5f7fa;
  border-radius: 4px;
  color: #303133;
}

.trend-delta {
  color: #909399;
  font-size: 12px;
  margin-left: 8px;
}

.dish-list {
  margin: 0;
  padding-left: 20px;
  line-height: 1.8;
  color: #606266;
}

/* W5.4 RFM member list */
.member-list {
  margin: 0;
  padding-left: 20px;
  line-height: 1.8;
  color: #606266;
  font-size: 13px;
}

/* W5.6 temporal comparison */
.temporal-message {
  font-size: 14px;
  padding: 10px 14px;
  background: #ecf5ff;
  border-left: 4px solid #409eff;
  color: #303133;
}

.text-success {
  color: #67c23a;
}

/* W6.4 Multi-Store Comparison */
.store-highlight-top {
  border-left-color: #67c23a;
  background: #f0f9eb;
}

.store-highlight-bottom {
  border-left-color: #f56c6c;
  background: #fef0f0;
}
</style>
