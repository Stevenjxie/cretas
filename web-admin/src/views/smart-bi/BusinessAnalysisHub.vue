<script setup lang="ts">
/**
 * BusinessAnalysisHub — 经营分析 (WS4)
 *
 * 把散落的 财务看板 / 销售分析 / 趋势分析 / KPI看板 / 指标中心 合并成单一 tab 化模块。
 * 一级 el-tabs: 财务 / 销售 / 趋势 / KPI·指标 (内层再分 KPI看板 / 指标中心)。
 *
 * - ?tab= query 同步 (保书签 + 旧路径 redirect 落点, 参考 financeDashboardSection 的
 *   ?section= 同步模式)。
 * - 各子视图已在 WS1/WS4 Task 1 gold 化 + 默认全部历史 — 此 hub 仅做容器编排, 不改子视图逻辑。
 *
 * ============================================================
 * 混合式图表分析 (2026-07-12) — 与 CRM 会员分析/运营分析/平台分析 同一套
 * pattern (确定性 bullets + ChartInsights 按需 AI 解读 + 页面级 AnalysisChatPanel),
 * 但集成方式经过慎重取舍:
 *
 * FinancialDashboardPBI.vue (3311行) / SalesAnalysis.vue (2453行) /
 * analytics/trends/index.vue (926行, 含 useCapability 订阅分级门控) /
 * analytics/kpi/index.vue (1011行) / indicator-center/IndicatorCenterDashboard.vue
 * (545行) 这 5 个子视图已经各自拥有自己的日期选择器、权限/tier gate、且各自已经
 * 内嵌了一套更早的图表洞察系统 (ChartInsightProvider+chartInsight.ts 或
 * ChartInsight.vue+useChartInsight)。在单次改动里深入这 5 个大文件内部去抠出
 * "当前激活 tab 的真实图表数据" 风险很高 (5000+ 行且各自 gate 逻辑不同,
 * 容易在 additive 的名义下无意间破坏已有 tab/图表行为)。
 *
 * 所以这里选择 Hub 级别的、完全独立、只读的做法: Hub 自己直接调用 gold.ts
 * 里已经在别处验证过的、后端已按角色 RBAC 脱敏的全历史 summary 端点
 * (getFinanceSummary/getTopProducts/getTrendBundle/getKpiSummary) —— 不读取、
 * 不触碰上面 5 个子视图的内部状态或渲染逻辑，零打破其现有行为的风险。
 * 代价: 这里展示的是"全部历史"粗粒度概览，与各 tab 内部可自定义时间范围筛选
 * 后的图表数字不一定完全一致 —— 已在 UI 上诚实标注，不伪装成对应 tab 的实时口径。
 * ============================================================
 */
import { ref, computed, watch, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import FinancialDashboardPBI from './FinancialDashboardPBI.vue';
import SalesAnalysis from './SalesAnalysis.vue';
import TrendsView from '@/views/analytics/trends/index.vue';
import KpiView from '@/views/analytics/kpi/index.vue';
import IndicatorCenterDashboard from '@/views/indicator-center/IndicatorCenterDashboard.vue';
import { resolveBusinessAnalysisTab, type BusinessAnalysisTab } from './businessAnalysisTab';
import ChartInsights from './components/analysis/ChartInsights.vue';
import AnalysisChatPanel from './components/analysis/AnalysisChatPanel.vue';
import { vReveal } from '@/composables/useReveal';
import {
  financeOverviewBullets,
  financeOverviewSummary,
  salesOverviewBullets,
  salesOverviewSummary,
  trendOverviewBullets,
  trendOverviewSummary,
  kpiOverviewBullets,
  kpiOverviewSummary,
  type AnalysisChartContext,
} from './components/analysis/analysisBullets';
import {
  getFinanceSummary,
  getTopProducts,
  getTrendBundle,
  getKpiSummary,
  type FinanceSummary,
  type TopProducts,
  type TrendBundle,
  type KpiSummary,
} from '@/api/smartbi/gold';
import { getGoldDataRange } from '@/api/smartbi/dataRange';
import { resolveAllHistoryRange } from '@/views/smart-bi/analysisDefaults';

const route = useRoute();
const router = useRouter();

const activeTab = ref<BusinessAnalysisTab>(resolveBusinessAnalysisTab(route.query));
// KPI·指标 内层子 tab (kpi 看板 / 指标中心)。?sub= 同步, 默认 kpi。
const kpiSubTab = ref<string>(route.query.sub === 'indicator' ? 'indicator' : 'kpi');

// 外部 query 变化 (e.g. 旧路径 redirect 带入 ?tab=) → 同步激活 tab。
watch(
  () => route.query.tab,
  () => { activeTab.value = resolveBusinessAnalysisTab(route.query); },
);

function syncQuery(name: string) {
  // replace 而非 push — tab 切换不污染浏览器返回栈 (与 FinancialDashboardPBI section 同模式)。
  router.replace({ query: { ...route.query, tab: name } });
}

function syncKpiSubQuery(name: string) {
  router.replace({ query: { ...route.query, tab: 'kpi', sub: name } });
}

// ============================================================
// Hub 级全历史概览 (独立于 5 个子视图, 只读, 见文件头说明) — 各自 loading/error
// 彻底隔离 (一个 500 不连累另一个, fool-proof-design 跨规则铁律)。
// ============================================================
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId || '');

const financeLoading = ref(false);
const financeError = ref('');
const financeData = ref<FinanceSummary | null>(null);
async function loadFinanceOverview() {
  if (!factoryId.value) return;
  financeLoading.value = true;
  financeError.value = '';
  try {
    financeData.value = await getFinanceSummary({ factoryId: factoryId.value });
  } catch (e) {
    financeError.value = e instanceof Error ? e.message : '财务概览加载失败';
    financeData.value = null;
  } finally {
    financeLoading.value = false;
  }
}

const salesLoading = ref(false);
const salesError = ref('');
const salesData = ref<TopProducts | null>(null);
async function loadSalesOverview() {
  if (!factoryId.value) return;
  salesLoading.value = true;
  salesError.value = '';
  try {
    // getTopProducts 要求具体日期区间 (非 optional) — 探测全部历史窗, 绝不回退"近30天"。
    const dr = await getGoldDataRange(factoryId.value).catch((): null => null);
    const [startDate, endDate] = resolveAllHistoryRange(dr);
    salesData.value = await getTopProducts({ factoryId: factoryId.value, startDate, endDate, topN: 5 });
  } catch (e) {
    salesError.value = e instanceof Error ? e.message : '销售概览加载失败';
    salesData.value = null;
  } finally {
    salesLoading.value = false;
  }
}

const trendLoading = ref(false);
const trendError = ref('');
const trendData = ref<TrendBundle | null>(null);
async function loadTrendOverview(): Promise<void> {
  if (!factoryId.value) return;
  trendLoading.value = true;
  trendError.value = '';
  try {
    trendData.value = await getTrendBundle({ factoryId: factoryId.value });
  } catch (e) {
    trendError.value = e instanceof Error ? e.message : '趋势概览加载失败';
    trendData.value = null;
  } finally {
    trendLoading.value = false;
  }
}

const kpiLoading = ref(false);
const kpiError = ref('');
const kpiData = ref<KpiSummary | null>(null);
async function loadKpiOverview() {
  if (!factoryId.value) return;
  kpiLoading.value = true;
  kpiError.value = '';
  try {
    kpiData.value = await getKpiSummary({ factoryId: factoryId.value });
  } catch (e) {
    kpiError.value = e instanceof Error ? e.message : 'KPI 概览加载失败';
    kpiData.value = null;
  } finally {
    kpiLoading.value = false;
  }
}

onMounted(() => {
  void loadFinanceOverview();
  void loadSalesOverview();
  void loadTrendOverview();
  void loadKpiOverview();
});

const financeBulletsInput = computed(() => ({
  totalRevenue: financeData.value?.totalRevenue ?? null,
  billCount: financeData.value?.billCount ?? 0,
  avgBillValue: financeData.value?.avgBillValue ?? null,
  storeCount: financeData.value?.storeCount ?? 0,
  topStores: financeData.value?.topStores ?? [],
}));
const financeBullets = computed(() => financeOverviewBullets(financeBulletsInput.value));
const financeSummaryStr = computed(() => financeOverviewSummary(financeBulletsInput.value));

const salesBulletsInput = computed(() => ({ topProducts: salesData.value?.topProducts ?? [] }));
const salesBullets = computed(() => salesOverviewBullets(salesBulletsInput.value));
const salesSummaryStr = computed(() => salesOverviewSummary(salesBulletsInput.value));

const trendBulletsInput = computed(() => ({
  dailyTrend: trendData.value?.dailyTrend ?? [],
  monthlyTrend: trendData.value?.monthlyTrend ?? [],
  weekdayWeekend: trendData.value?.weekdayWeekend ?? { weekdayAvg: null, weekendAvg: null, weekdayDays: 0, weekendDays: 0 },
}));
const trendBullets = computed(() => trendOverviewBullets(trendBulletsInput.value));
const trendSummaryStr = computed(() => trendOverviewSummary(trendBulletsInput.value));

const kpiBulletsInput = computed(() => ({
  revenue: kpiData.value?.revenue ?? 0,
  billCount: kpiData.value?.billCount ?? 0,
  itemCount: kpiData.value?.itemCount ?? 0,
  customerCount: kpiData.value?.customerCount ?? 0,
  storeCount: kpiData.value?.storeCount ?? 0,
  avgBillValue: kpiData.value?.avgBillValue ?? null,
  itemsPerBill: kpiData.value?.itemsPerBill ?? null,
  avgPerCapita: kpiData.value?.avgPerCapita ?? null,
}));
const kpiBullets = computed(() => kpiOverviewBullets(kpiBulletsInput.value));
const kpiSummaryStr = computed(() => kpiOverviewSummary(kpiBulletsInput.value));

const analysisContexts = computed<AnalysisChartContext[]>(() => [
  {
    key: 'finance',
    title: '财务概览',
    dataSummary: financeSummaryStr.value,
    dataScope: financeData.value
      ? `Gold 销售汇总 ${financeData.value.startDate} 至 ${financeData.value.endDate}；仅含营收、订单、客单价和门店排行，不是利润表`
      : 'Gold 销售汇总；当前未返回有效期间',
    unavailableMetrics: ['营业成本', '毛利', '毛利率', '营业利润', '净利润'],
  },
  {
    key: 'sales',
    title: '销售概览',
    dataSummary: salesSummaryStr.value,
    dataScope: salesData.value
      ? `Gold 菜品销量与营收汇总 ${salesData.value.startMonth} 至 ${salesData.value.endMonth}`
      : 'Gold 菜品销量与营收汇总；当前未返回有效期间',
    unavailableMetrics: ['菜品成本', '菜品毛利率'],
  },
  {
    key: 'trend',
    title: '趋势概览',
    dataSummary: trendSummaryStr.value,
    dataScope: trendData.value
      ? `Gold 营收趋势 ${trendData.value.startDate ?? '未知起始日'} 至 ${trendData.value.endDate ?? '未知结束日'}`
      : 'Gold 营收趋势；当前未返回有效期间',
    unavailableMetrics: ['成本趋势', '毛利率趋势'],
  },
  {
    key: 'kpi',
    title: 'KPI概览',
    dataSummary: kpiSummaryStr.value,
    dataScope: kpiData.value
      ? `Gold 经营 KPI ${kpiData.value.startDate} 至 ${kpiData.value.endDate}`
      : 'Gold 经营 KPI；当前未返回有效期间',
    unavailableMetrics: ['成本', '毛利率', '利润'],
  },
]);

const analysisChatPanelRef = ref<InstanceType<typeof AnalysisChatPanel> | null>(null);
function focusChart(key: string) {
  analysisChatPanelRef.value?.setFocus(key);
}
</script>

<template>
  <div class="business-analysis-hub">
    <div class="bah-layout">
      <div class="bah-main">
        <!-- 财务/销售/趋势/KPI 全历史概览卡 (Hub 独立请求, 与下方各 tab 内部
             可自定义时间范围的图表相互独立 — 见 <script> 顶部说明) -->
        <div class="bah-overview-note">
          以下概览为全部历史数据的简要口径，独立于下方各标签页内的时间范围筛选，仅供 AI 分析面板参考。
        </div>
        <div class="bah-overview-grid">
          <el-card v-reveal="0" shadow="never" class="bah-overview-card">
            <template #header><div class="bah-overview-header">财务概览</div></template>
            <el-skeleton v-if="financeLoading" :rows="2" animated />
            <div v-else-if="financeError" class="bah-inline-error">{{ financeError }}</div>
            <ChartInsights
              v-else
              :bullets="financeBullets"
              chart-key="finance"
              chart-title="财务概览"
              :data-summary="financeSummaryStr"
              @focus="focusChart"
            />
          </el-card>
          <el-card v-reveal="1" shadow="never" class="bah-overview-card">
            <template #header><div class="bah-overview-header">销售概览</div></template>
            <el-skeleton v-if="salesLoading" :rows="2" animated />
            <div v-else-if="salesError" class="bah-inline-error">{{ salesError }}</div>
            <ChartInsights
              v-else
              :bullets="salesBullets"
              chart-key="sales"
              chart-title="销售概览"
              :data-summary="salesSummaryStr"
              @focus="focusChart"
            />
          </el-card>
          <el-card v-reveal="2" shadow="never" class="bah-overview-card">
            <template #header><div class="bah-overview-header">趋势概览</div></template>
            <el-skeleton v-if="trendLoading" :rows="2" animated />
            <div v-else-if="trendError" class="bah-inline-error">{{ trendError }}</div>
            <ChartInsights
              v-else
              :bullets="trendBullets"
              chart-key="trend"
              chart-title="趋势概览"
              :data-summary="trendSummaryStr"
              @focus="focusChart"
            />
          </el-card>
          <el-card v-reveal="3" shadow="never" class="bah-overview-card">
            <template #header><div class="bah-overview-header">KPI 概览</div></template>
            <el-skeleton v-if="kpiLoading" :rows="2" animated />
            <div v-else-if="kpiError" class="bah-inline-error">{{ kpiError }}</div>
            <ChartInsights
              v-else
              :bullets="kpiBullets"
              chart-key="kpi"
              chart-title="KPI 概览"
              :data-summary="kpiSummaryStr"
              @focus="focusChart"
            />
          </el-card>
        </div>

        <el-tabs v-model="activeTab" class="hub-tabs" @tab-change="syncQuery">
          <el-tab-pane label="财务" name="finance" lazy>
            <FinancialDashboardPBI />
          </el-tab-pane>
          <el-tab-pane label="销售" name="sales" lazy>
            <SalesAnalysis />
          </el-tab-pane>
          <el-tab-pane label="趋势" name="trend" lazy>
            <TrendsView />
          </el-tab-pane>
          <el-tab-pane label="KPI·指标" name="kpi" lazy>
            <el-tabs v-model="kpiSubTab" type="card" class="kpi-sub-tabs" @tab-change="syncKpiSubQuery">
              <el-tab-pane label="KPI 看板" name="kpi" lazy>
                <KpiView />
              </el-tab-pane>
              <el-tab-pane label="指标中心" name="indicator" lazy>
                <IndicatorCenterDashboard />
              </el-tab-pane>
            </el-tabs>
          </el-tab-pane>
        </el-tabs>
      </div>

      <div class="bah-side">
        <AnalysisChatPanel ref="analysisChatPanelRef" :factory-id="factoryId" :contexts="analysisContexts" page-title="经营分析" />
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.business-analysis-hub {
  padding: 16px;

  .hub-tabs {
    :deep(.el-tabs__header) {
      margin-bottom: 8px;
    }
  }

  .kpi-sub-tabs {
    margin-top: 8px;
  }
}

/* 主列(概览卡 + 原有 tabs) + 侧列(AI 分析面板) 响应式布局
   (mirrors CRM member-analysis / 运营分析 / 平台分析) */
.bah-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 380px;
  gap: 16px;
  align-items: start;
}
@media (max-width: 1280px) {
  .bah-layout { grid-template-columns: 1fr; }
}
.bah-main { min-width: 0; }
.bah-side {
  position: sticky;
  top: 16px;
}
@media (max-width: 1280px) {
  .bah-side { position: static; }
}

.bah-overview-note {
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  margin-bottom: 10px;
  line-height: 1.5;
}
.bah-overview-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
@media (max-width: 1400px) {
  .bah-overview-grid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 768px) {
  .bah-overview-grid { grid-template-columns: 1fr; }
}
.bah-overview-card {
  :deep(.el-card__header) {
    padding: 10px 14px;
  }
  :deep(.el-card__body) {
    padding: 12px 14px;
  }
}
.bah-overview-header {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary, #303133);
}
.bah-inline-error {
  color: #f56c6c;
  font-size: 12px;
  padding: 4px 0;
}
</style>
