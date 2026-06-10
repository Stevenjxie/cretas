<script setup lang="ts">
/**
 * RestaurantSalesContent — Phase IIa restaurant analytics for /smart-bi/sales.
 * Backed by GET /api/mobile/{factoryId}/smart-bi/analysis/sales (restaurant branch).
 * Replaces the RestaurantPhaseIIPlaceholder rendered before Phase IIa.
 */
import { ref, computed, onMounted, watch, onUnmounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import axios from 'axios';
import { get } from '@/api/request';
import { toApiDateString } from '@/utils/dateFormat';
import { ElMessage } from 'element-plus';
import { Refresh, Calendar } from '@element-plus/icons-vue';
import DynamicChartRenderer from './DynamicChartRenderer.vue';
import SmartBIEmptyState from './SmartBIEmptyState.vue';
import CapabilityGate from '@/components/CapabilityGate.vue';
import ChartInsightProvider from '@/views/smart-bi/components/ChartInsightProvider.vue';
import { getGoldDataRange } from '@/api/smartbi/dataRange';
import { resolveAllHistoryRange } from '@/views/smart-bi/analysisDefaults';
import type { ChartConfig } from '@/types/smartbi';
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight';

interface SalesOverview {
  totalRevenue: number;
  billCount: number;
  avgPerCapita: number | null;
  storeCount: number;
  dataSource?: string;
}

interface SeriesEntry {
  name?: string;
  data?: number[];
  value?: number;
}

interface RawChart {
  chartType: string;
  title?: string;
  xAxis?: string[];
  series?: SeriesEntry[];
}

interface ProductRow {
  rank: number;
  name: string;
  revenue: number;
  qtySold: number;
}

interface ChannelRow {
  channelName: string;
  amount: number;
  billCount: number;
  share: number;
}

interface SalesResponse {
  tenantType: 'RESTAURANT';
  dateRange: {
    startDate: string;
    endDate: string;
    days: number;
    coverageWarning?: string;
  };
  overview: SalesOverview;
  revenueTrend?: RawChart;
  orderTypeSplit?: RawChart;
  mealPeriodBreakdown?: RawChart;
  productRanking?: ProductRow[];
  channelBreakdown?: ChannelRow[];
  avgPerCapitaTrend?: RawChart;
  generatedAt?: string;
}

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);

// WS4 #11: default = ALL HISTORY (gold 数据窗), 不是近30天 — 餐饮租户 (qhj 等) 真实
// 数据落在历史区间 (2025-01..2026-04), 近30天默认窗常空 → "所选区间无销售数据"。
// 镜像 SalesAnalysis.vue 工厂路径 (getGoldDataRange + resolveAllHistoryRange)。
const dateRange = ref<[string, string] | null>(null);
const shortcuts = [
  {
    text: '最近7天',
    value: () => {
      const end = new Date();
      const start = new Date();
      start.setTime(start.getTime() - 3600 * 1000 * 24 * 7);
      return [start, end];
    },
  },
  {
    text: '最近30天',
    value: () => {
      const end = new Date();
      const start = new Date();
      start.setTime(start.getTime() - 3600 * 1000 * 24 * 30);
      return [start, end];
    },
  },
  {
    text: '最近90天',
    value: () => {
      const end = new Date();
      const start = new Date();
      start.setTime(start.getTime() - 3600 * 1000 * 24 * 90);
      return [start, end];
    },
  },
];

const loading = ref(false);
const loadError = ref('');
const data = ref<SalesResponse | null>(null);
let abortCtl: AbortController | null = null;

const hasBills = computed(() => (data.value?.overview.billCount ?? 0) > 0);
const coverageWarning = computed(() => data.value?.dateRange.coverageWarning ?? '');

// ---- chart-config adapters: spec response → DynamicChartRenderer shape -----

function adaptStackedBar(raw: RawChart | undefined, stackKey: string): ChartConfig | null {
  if (!raw || !raw.series || raw.series.length === 0) return null;
  return {
    chartType: 'bar',
    title: raw.title,
    xAxis: { data: raw.xAxis ?? [] },
    series: raw.series.map((s) => ({
      name: s.name ?? '',
      type: 'bar',
      stack: stackKey,
      data: (s.data ?? []) as number[],
    })),
  } as ChartConfig;
}

function adaptLine(raw: RawChart | undefined): ChartConfig | null {
  if (!raw || !raw.series || raw.series.length === 0) return null;
  return {
    chartType: 'line',
    title: raw.title,
    xAxis: { data: raw.xAxis ?? [] },
    series: raw.series.map((s) => ({
      name: s.name ?? '',
      type: 'line',
      data: (s.data ?? []) as number[],
    })),
  } as ChartConfig;
}

function adaptValueBar(raw: RawChart | undefined): ChartConfig | null {
  // mealPeriodBreakdown: series is [{name:"午市", value:N}, {name:"晚市", value:M}].
  // Convert to single bar series with xAxis = names.
  if (!raw || !raw.series || raw.series.length === 0) return null;
  const names = raw.series.map((s) => s.name ?? '');
  const values = raw.series.map((s) => Number(s.value ?? 0));
  return {
    chartType: 'bar',
    title: raw.title,
    xAxis: { data: names },
    series: [{ name: raw.title ?? '', type: 'bar', data: values }],
  } as ChartConfig;
}

function adaptPie(raw: RawChart | undefined): ChartConfig | null {
  // orderTypeSplit: series = [{name, value}]. Map to xAxis.data + numeric data
  // so DynamicChartRenderer.buildDashboardPie picks names from xAxis.
  if (!raw || !raw.series || raw.series.length === 0) return null;
  const names = raw.series.map((s) => s.name ?? '');
  const values = raw.series.map((s) => Number(s.value ?? 0));
  return {
    chartType: 'pie',
    title: raw.title,
    xAxis: { data: names },
    series: [{ name: raw.title ?? '', type: 'pie', data: values }],
  } as ChartConfig;
}

const revenueTrendConfig = computed(() => adaptStackedBar(data.value?.revenueTrend, 'orderType'));
const orderTypeSplitConfig = computed(() => adaptPie(data.value?.orderTypeSplit));
const mealPeriodConfig = computed(() => adaptValueBar(data.value?.mealPeriodBreakdown));
const avgPerCapitaTrendConfig = computed(() => adaptLine(data.value?.avgPerCapitaTrend));
const channelBreakdownConfig = computed<ChartConfig | null>(() => {
  const rows = data.value?.channelBreakdown;
  if (!rows || rows.length === 0) return null;
  return {
    chartType: 'bar',
    title: '渠道营收分布',
    xAxis: { data: rows.map((r) => r.channelName) },
    series: [{ name: '渠道营收', type: 'bar', data: rows.map((r) => r.amount) }],
  } as ChartConfig;
});

// ---- ChartWithMeta for ChartInsightProvider ----

/** BAR stacked 日营收趋势 (堂食+外卖). xDim=time (x-axis = dates). yMetric=revenue. */
const revenueTrendMeta = computed<ChartWithMeta | null>(() => {
  const cfg = revenueTrendConfig.value;
  if (!cfg) return null;
  const c = cfg as { xAxis?: { data?: string[] }; series?: Array<{ name?: string; type?: string; data?: number[] }> };
  return {
    chartType: 'bar',
    title: '日营收趋势',
    meta: { xDim: 'time', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
    config: {
      xAxis: { data: c.xAxis?.data ?? [] },
      series: (c.series ?? []).map((s) => ({ type: s.type, data: s.data ?? [] })),
    },
  };
});

/** PIE 堂食/外卖占比. xDim=channel. yMetric=revenue. */
const orderTypeSplitMeta = computed<ChartWithMeta | null>(() => {
  const cfg = orderTypeSplitConfig.value;
  if (!cfg) return null;
  const c = cfg as { xAxis?: { data?: string[] }; series?: Array<{ name?: string; type?: string; data?: number[] }> };
  const labels = c.xAxis?.data ?? [];
  const values = c.series?.[0]?.data ?? [];
  return {
    chartType: 'pie',
    title: '堂食/外卖占比',
    meta: { xDim: 'channel', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
    config: {
      xAxis: { data: labels },
      series: [
        {
          type: 'pie',
          data: labels.map((name, i) => ({ name, value: Number(values[i] ?? 0) })),
        },
      ],
    },
  };
});

/** BAR 时段营收 (午市/晚市). xDim=category. yMetric=revenue. RANKING. */
const mealPeriodMeta = computed<ChartWithMeta | null>(() => {
  const cfg = mealPeriodConfig.value;
  if (!cfg) return null;
  const c = cfg as { xAxis?: { data?: string[] }; series?: Array<{ name?: string; type?: string; data?: number[] }> };
  return {
    chartType: 'bar',
    title: '时段营收分布',
    meta: { xDim: 'category', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
    config: {
      xAxis: { data: c.xAxis?.data ?? [] },
      series: (c.series ?? []).map((s) => ({ type: s.type, data: s.data ?? [] })),
    },
  };
});

/** LINE 客单价趋势. xDim=time. yMetric=revenue (per-capita). TREND. */
const avgPerCapitaTrendMeta = computed<ChartWithMeta | null>(() => {
  const cfg = avgPerCapitaTrendConfig.value;
  if (!cfg) return null;
  const c = cfg as { xAxis?: { data?: string[] }; series?: Array<{ name?: string; type?: string; data?: number[] }> };
  return {
    chartType: 'line',
    title: '客单价趋势',
    meta: { xDim: 'time', yMetric: 'revenue', aggregation: 'avg', domain: 'restaurant' },
    config: {
      xAxis: { data: c.xAxis?.data ?? [] },
      series: (c.series ?? []).map((s) => ({ type: s.type, data: s.data ?? [] })),
    },
  };
});

/** BAR 支付渠道营收分布. xDim=channel. yMetric=revenue. RANKING. */
const channelBreakdownMeta = computed<ChartWithMeta | null>(() => {
  const cfg = channelBreakdownConfig.value;
  if (!cfg) return null;
  const c = cfg as { xAxis?: { data?: string[] }; series?: Array<{ name?: string; type?: string; data?: number[] }> };
  return {
    chartType: 'bar',
    title: '支付渠道营收分布',
    meta: { xDim: 'channel', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
    config: {
      xAxis: { data: c.xAxis?.data ?? [] },
      series: (c.series ?? []).map((s) => ({ type: s.type, data: s.data ?? [] })),
    },
  };
});

// ---- formatting helpers ----

const moneyFmt = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const intFmt = new Intl.NumberFormat('zh-CN');

function fmtMoney(v: number | null | undefined): string {
  if (v == null) return '—';
  return `¥${moneyFmt.format(v)}`;
}

function fmtInt(v: number | null | undefined): string {
  if (v == null) return '—';
  return intFmt.format(v);
}

function fmtAvgPerCapita(v: number | null | undefined): string {
  // Spec §4.5: avgPerCapita: null when customerCount=0 → render "—" not "¥0".
  if (v == null) return '—';
  return `¥${moneyFmt.format(v)}`;
}

// ---- data load ----

async function loadData() {
  if (!factoryId.value) {
    loadError.value = '未找到工厂ID，请重新登录';
    return;
  }
  if (!dateRange.value) return;

  if (abortCtl) abortCtl.abort();
  abortCtl = new AbortController();

  loading.value = true;
  loadError.value = '';
  try {
    const params: Record<string, string> = {
      startDate: toApiDateString(dateRange.value[0]),
      endDate: toApiDateString(dateRange.value[1]),
    };
    const response = await get<SalesResponse>(
      `/${factoryId.value}/smart-bi/analysis/sales`,
      { params, signal: abortCtl.signal, _silent: true } as never,
    );
    if (!response.success || !response.data) {
      loadError.value = response.message || '加载销售数据失败';
      data.value = null;
      return;
    }
    // Defensive: backend may return Java-style envelope (double-wrap).
    const raw = response.data as unknown as Record<string, unknown>;
    const inner = (raw.data && typeof raw.data === 'object' && !Array.isArray(raw.data) && 'tenantType' in (raw.data as object))
      ? (raw.data as SalesResponse)
      : (response.data as SalesResponse);
    data.value = inner;
  } catch (error) {
    // Suppress all forms of request cancellation (rapid navigation + abort):
    //   - native fetch AbortError DOMException
    //   - axios CanceledError (isCancel) — most common via interceptors
    //   - any check on the abort controller signal
    if (axios.isCancel(error)) return;
    if (error instanceof DOMException && error.name === 'AbortError') return;
    if (abortCtl?.signal.aborted) return;
    const msg = error instanceof Error ? error.message : '未知错误';
    loadError.value = `加载销售数据失败: ${msg}`;
    ElMessage.error(loadError.value);
    data.value = null;
  } finally {
    loading.value = false;
  }
}

function refresh() {
  loadData();
}

onMounted(async () => {
  // 默认全部历史: 探 gold 数据窗 → [minDate, maxDate]; 失败回落宽窗 (绝不近30天)。
  let allHistory: [string, string];
  try {
    const probe = factoryId.value ? await getGoldDataRange(factoryId.value) : null;
    allHistory = resolveAllHistoryRange(probe);
  } catch {
    allHistory = resolveAllHistoryRange(null);
  }
  dateRange.value = [allHistory[0], allHistory[1]];
  // dateRange watcher triggers loadData(); call directly too in case the value
  // is unchanged (e.g. fallback window matches a prior state) so first paint loads.
  loadData();
});

watch(dateRange, () => loadData());

onUnmounted(() => {
  if (abortCtl) {
    abortCtl.abort();
    abortCtl = null;
  }
});
</script>

<template>
  <div class="restaurant-sales-content">
    <div class="page-header">
      <div class="header-left">
        <h1>销售分析（餐饮）</h1>
        <span v-if="data?.overview.dataSource" class="data-source-tag">
          来源：{{ data.overview.dataSource }}
        </span>
      </div>
      <div class="header-right">
        <el-button type="primary" :icon="Refresh" :loading="loading" @click="refresh">刷新</el-button>
      </div>
    </div>

    <el-card class="filter-card">
      <div class="filter-bar">
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
          />
        </div>
      </div>
    </el-card>

    <el-alert
      v-if="loadError"
      type="error"
      :title="loadError"
      show-icon
      closable
      style="margin-bottom: 16px"
      @close="loadError = ''"
    />

    <!-- Edge case §4.5: range exceeds Gold coverage → warning banner -->
    <el-alert
      v-if="coverageWarning"
      type="warning"
      :title="coverageWarning"
      show-icon
      :closable="false"
      style="margin-bottom: 16px"
    />

    <!-- Edge case §4.5: zero bills → empty state instead of empty charts -->
    <SmartBIEmptyState
      v-if="data && !hasBills"
      type="no-data"
      title="所选区间无销售数据"
      description="该门店在所选日期范围内未产生订单。请调整日期范围或确认 POS 数据已上传。"
      :show-action="false"
    />

    <template v-else-if="data">
      <!-- KPI strip -->
      <el-row :gutter="16" class="kpi-row">
        <el-col :xs="24" :sm="12" :md="6">
          <CapabilityGate
            v-if="canViewPrice"
            card-id="restaurant_sales_revenue"
            :requires="['date', 'gross_amount']"
          >
            <el-card class="kpi-card kpi-accent-0">
              <div class="kpi-label">总营收</div>
              <div class="kpi-value">{{ fmtMoney(data.overview.totalRevenue) }}</div>
              <div class="kpi-sub">覆盖 {{ data.dateRange.days }} 天</div>
            </el-card>
          </CapabilityGate>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card kpi-accent-1">
            <div class="kpi-label">总单数</div>
            <div class="kpi-value">{{ fmtInt(data.overview.billCount) }}</div>
            <div class="kpi-sub">订单总量</div>
          </el-card>
        </el-col>
        <el-col v-if="canViewPrice" :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card kpi-accent-2">
            <div class="kpi-label">均客单价</div>
            <div class="kpi-value">{{ fmtAvgPerCapita(data.overview.avgPerCapita) }}</div>
            <div class="kpi-sub">营收 / 客流</div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card kpi-accent-3">
            <div class="kpi-label">在营门店数</div>
            <div class="kpi-value">{{ fmtInt(data.overview.storeCount) }}</div>
            <div class="kpi-sub">本期录入门店</div>
          </el-card>
        </el-col>
      </el-row>

      <!-- Revenue trend (stacked BAR: 堂食 + 外卖) -->
      <el-card v-if="revenueTrendConfig" class="chart-card">
        <template #header>
          <span class="chart-title">{{ revenueTrendConfig.title || '日营收趋势' }}</span>
        </template>
        <DynamicChartRenderer :config="revenueTrendConfig" :height="340" />
        <ChartInsightProvider
          :chart="revenueTrendMeta"
          :perms="{ canViewFinance: canViewPrice }"
          :factory-id="factoryId ?? ''"
          depth="detailed"
        />
      </el-card>

      <!-- Order type split (PIE) + Meal period (BAR) side by side -->
      <el-row :gutter="16" class="chart-row">
        <el-col :xs="24" :md="12">
          <el-card v-if="orderTypeSplitConfig" class="chart-card">
            <template #header>
              <span class="chart-title">{{ orderTypeSplitConfig.title || '堂食/外卖占比' }}</span>
            </template>
            <DynamicChartRenderer :config="orderTypeSplitConfig" :height="320" />
            <ChartInsightProvider
              :chart="orderTypeSplitMeta"
              :perms="{ canViewFinance: canViewPrice }"
              :factory-id="factoryId ?? ''"
              depth="detailed"
            />
          </el-card>
        </el-col>
        <el-col :xs="24" :md="12">
          <el-card v-if="mealPeriodConfig" class="chart-card">
            <template #header>
              <span class="chart-title">{{ mealPeriodConfig.title || '时段营收分布' }}</span>
            </template>
            <DynamicChartRenderer :config="mealPeriodConfig" :height="320" />
            <ChartInsightProvider
              :chart="mealPeriodMeta"
              :perms="{ canViewFinance: canViewPrice }"
              :factory-id="factoryId ?? ''"
              depth="detailed"
            />
          </el-card>
        </el-col>
      </el-row>

      <!-- Average per capita trend (LINE) -->
      <el-card v-if="avgPerCapitaTrendConfig" class="chart-card">
        <template #header>
          <span class="chart-title">客单价趋势</span>
        </template>
        <DynamicChartRenderer :config="avgPerCapitaTrendConfig" :height="300" />
        <ChartInsightProvider
          :chart="avgPerCapitaTrendMeta"
          :perms="{ canViewFinance: canViewPrice }"
          :factory-id="factoryId ?? ''"
          depth="detailed"
        />
      </el-card>

      <!-- Product ranking table (top 20, monthly grain) -->
      <el-card v-if="data.productRanking && data.productRanking.length > 0" class="table-card">
        <template #header>
          <span class="chart-title">本月菜品排行（Top 20）</span>
          <span class="grain-hint">数据粒度：按月聚合（agg_product）</span>
        </template>
        <el-table :data="data.productRanking" stripe size="small" :max-height="540">
          <el-table-column prop="rank" label="排名" width="80" align="center" />
          <el-table-column prop="name" label="菜品名称" min-width="200">
            <template #default="{ row }">
              <span :class="{ 'deleted-dish': row.name && row.name.startsWith('(已下架') }">
                {{ row.name }}
              </span>
            </template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" prop="revenue" label="营收" width="160" align="right">
            <template #default="{ row }">{{ fmtMoney(row.revenue) }}</template>
          </el-table-column>
          <el-table-column prop="qtySold" label="销量" width="120" align="right">
            <template #default="{ row }">{{ fmtInt(row.qtySold) }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- Channel breakdown -->
      <el-row :gutter="16" class="chart-row">
        <el-col :xs="24" :md="14">
          <el-card v-if="channelBreakdownConfig" class="chart-card">
            <template #header>
              <span class="chart-title">支付渠道营收分布</span>
            </template>
            <DynamicChartRenderer :config="channelBreakdownConfig" :height="320" />
            <ChartInsightProvider
              :chart="channelBreakdownMeta"
              :perms="{ canViewFinance: canViewPrice }"
              :factory-id="factoryId ?? ''"
              depth="detailed"
            />
          </el-card>
        </el-col>
        <el-col :xs="24" :md="10">
          <el-card v-if="data.channelBreakdown && data.channelBreakdown.length > 0" class="table-card">
            <template #header>
              <span class="chart-title">渠道明细</span>
            </template>
            <el-table :data="data.channelBreakdown" stripe size="small" :max-height="320">
              <el-table-column prop="channelName" label="渠道" min-width="100" />
              <el-table-column v-if="canViewPrice" prop="amount" label="金额" width="140" align="right">
                <template #default="{ row }">{{ fmtMoney(row.amount) }}</template>
              </el-table-column>
              <el-table-column prop="billCount" label="单数" width="100" align="right">
                <template #default="{ row }">{{ fmtInt(row.billCount) }}</template>
              </el-table-column>
              <el-table-column prop="share" label="占比" width="100" align="right">
                <template #default="{ row }">{{ row.share?.toFixed(1) ?? '—' }}%</template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>
      </el-row>

      <div v-if="data.generatedAt" class="footer-meta">
        生成时间：{{ data.generatedAt }}
      </div>
    </template>

    <SmartBIEmptyState
      v-else-if="!loading"
      type="loading-failed"
      :show-action="true"
      action-text="重新加载"
      @action="refresh"
    />
  </div>
</template>

<style scoped>
.restaurant-sales-content {
  padding: 16px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.page-header h1 {
  margin: 0;
  font-size: 22px;
  color: #303133;
}
.data-source-tag {
  margin-left: 12px;
  font-size: 12px;
  color: #909399;
}
.filter-card {
  margin-bottom: 16px;
}
.filter-bar {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  align-items: center;
}
.filter-item {
  display: flex;
  align-items: center;
  gap: 8px;
}
.filter-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #606266;
}
.kpi-row {
  margin-bottom: 16px;
}
.kpi-card {
  padding: 4px 0;
}
.kpi-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 8px;
}
.kpi-value {
  font-size: 24px;
  font-weight: 600;
  color: #303133;
}
.kpi-sub {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
.kpi-accent-0 { border-left: 3px solid #5470c6; }
.kpi-accent-1 { border-left: 3px solid #91cc75; }
.kpi-accent-2 { border-left: 3px solid #fac858; }
.kpi-accent-3 { border-left: 3px solid #ee6666; }
.chart-card,
.table-card {
  margin-bottom: 16px;
}
.chart-row {
  margin-bottom: 0;
}
.chart-title {
  font-weight: 600;
  font-size: 15px;
  color: #303133;
}
.grain-hint {
  margin-left: 12px;
  font-size: 12px;
  color: #909399;
  font-weight: normal;
}
.deleted-dish {
  color: #909399;
  font-style: italic;
}
.footer-meta {
  margin-top: 16px;
  text-align: right;
  color: #909399;
  font-size: 12px;
}
</style>
