<script setup lang="ts">
/**
 * RestaurantFinanceContent — Phase IIa restaurant analytics for /smart-bi/finance.
 * Backed by GET /api/mobile/{factoryId}/smart-bi/analysis/finance?analysisType=overview.
 * Replaces the RestaurantPhaseIIPlaceholder rendered before Phase IIa.
 */
import { ref, computed, onMounted, watch, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import axios from 'axios';
import { get } from '@/api/request';
import { toApiDateString } from '@/utils/dateFormat';
import { ElMessage } from 'element-plus';
import { Refresh, Calendar, Promotion } from '@element-plus/icons-vue';
import DynamicChartRenderer from './DynamicChartRenderer.vue';
import SmartBIEmptyState from './SmartBIEmptyState.vue';
import CapabilityGate from '@/components/CapabilityGate.vue';
import ChartInsightProvider from '@/views/smart-bi/components/ChartInsightProvider.vue';
import type { ChartConfig } from '@/types/smartbi';
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight';

interface FinanceKPI {
  totalRevenue: number;
  billCount: number;
  avgPerCapita: number | null;
  storeCount: number;
  coverageStart?: string;
  coverageEnd?: string;
}

interface RawChart {
  chartType: string;
  title?: string;
  xAxis?: string[];
  series?: Array<{ name?: string; data?: number[] }>;
}

interface PhaseIIbPreview {
  wastageRate: number | null;
  dataAvailability: 'WASTAGE_NOT_TRACKED' | 'AVAILABLE' | string;
}

interface FinanceResponse {
  tenantType: 'RESTAURANT';
  analysisType: 'overview';
  kpi: FinanceKPI;
  revenueChart?: RawChart;
  phaseIIbPreview?: PhaseIIbPreview;
  generatedAt?: string;
  dateRange?: { coverageWarning?: string };
}

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);

// Phase IIb (2026-05-15): cross-link to 成本运营 tab. Parent FinanceAnalysis.vue
// listens and flips analysisType. Backward-compat: emit may be unhandled
// (parent without IIb tab support); v-if in template gates the button.
const emit = defineEmits<{
  'goto-kitchen-cost': [];
}>();
// Mirror parent's phaseIIbEnabled flag so the cross-link button only shows
// when IIb is operationally enabled. Default ON.
const phaseIIbEnabled = computed(() => import.meta.env.VITE_PHASE_IIB_ENABLED !== 'false');

// Default last 365 days (mirrors parent FinanceAnalysis.vue default).
const dateRange = ref<[string, string] | null>(null);
const shortcuts = [
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
  {
    text: '最近365天',
    value: () => {
      const end = new Date();
      const start = new Date();
      start.setTime(start.getTime() - 3600 * 1000 * 24 * 365);
      return [start, end];
    },
  },
];

const loading = ref(false);
const loadError = ref('');
const data = ref<FinanceResponse | null>(null);
let abortCtl: AbortController | null = null;

const hasBills = computed(() => (data.value?.kpi.billCount ?? 0) > 0);
const coverageWarning = computed(() => data.value?.dateRange?.coverageWarning ?? '');

function adaptChart(raw: RawChart | undefined, kind: 'bar' | 'line'): ChartConfig | null {
  if (!raw || !raw.series || raw.series.length === 0) return null;
  return {
    chartType: kind,
    title: raw.title,
    xAxis: { data: raw.xAxis ?? [] },
    series: raw.series.map((s) => ({
      name: s.name ?? '',
      type: kind,
      data: (s.data ?? []) as number[],
    })),
  } as ChartConfig;
}

const revenueChartConfig = computed(() => adaptChart(data.value?.revenueChart, 'bar'));

// ---- ChartWithMeta for ChartInsightProvider ----

/** BAR 月度营收. xAxis contains month labels (time series). xDim=time. yMetric=revenue. TREND. */
const revenueChartMeta = computed<ChartWithMeta | null>(() => {
  const cfg = revenueChartConfig.value;
  if (!cfg) return null;
  const c = cfg as { xAxis?: { data?: string[] }; series?: Array<{ name?: string; type?: string; data?: number[] }> };
  return {
    chartType: 'bar',
    title: '月度营收',
    meta: { xDim: 'time', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
    config: {
      xAxis: { data: c.xAxis?.data ?? [] },
      series: (c.series ?? []).map((s) => ({ type: s.type, data: s.data ?? [] })),
    },
  };
});

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
  if (v == null) return '—';
  return `¥${moneyFmt.format(v)}`;
}

const phaseIIbHint = computed(() => {
  const p = data.value?.phaseIIbPreview;
  if (!p) return '';
  if (p.dataAvailability === 'WASTAGE_NOT_TRACKED') return '损耗数据将在 v2 中加入';
  if (p.wastageRate != null) return `当前损耗率：${(p.wastageRate * 100).toFixed(2)}%`;
  return '损耗数据准备中';
});

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
      analysisType: 'overview',
      startDate: toApiDateString(dateRange.value[0]),
      endDate: toApiDateString(dateRange.value[1]),
    };
    const response = await get<FinanceResponse>(
      `/${factoryId.value}/smart-bi/analysis/finance`,
      { params, signal: abortCtl.signal, _silent: true } as never,
    );
    if (!response.success || !response.data) {
      loadError.value = response.message || '加载财务数据失败';
      data.value = null;
      return;
    }
    const raw = response.data as unknown as Record<string, unknown>;
    const inner = (raw.data && typeof raw.data === 'object' && !Array.isArray(raw.data) && 'tenantType' in (raw.data as object))
      ? (raw.data as FinanceResponse)
      : (response.data as FinanceResponse);
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
    loadError.value = `加载财务数据失败: ${msg}`;
    ElMessage.error(loadError.value);
    data.value = null;
  } finally {
    loading.value = false;
  }
}

function refresh() {
  loadData();
}

function gotoRevenueReport() {
  router.push('/smart-bi/revenue-report');
}

onMounted(() => {
  const end = new Date();
  const start = new Date();
  start.setTime(start.getTime() - 3600 * 1000 * 24 * 365);
  dateRange.value = [toApiDateString(start), toApiDateString(end)];
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
  <div class="restaurant-finance-content">
    <div class="page-header">
      <div class="header-left">
        <h1>财务分析（餐饮 · 营收概览）</h1>
      </div>
      <div class="header-right">
        <el-button type="primary" plain @click="gotoRevenueReport">
          导出可打印的 可比同比/环比报表 →
        </el-button>
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
        <div v-if="data?.kpi.coverageStart && data?.kpi.coverageEnd" class="coverage-note">
          数据覆盖：{{ data.kpi.coverageStart }} ~ {{ data.kpi.coverageEnd }}
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

    <el-alert
      v-if="coverageWarning"
      type="warning"
      :title="coverageWarning"
      show-icon
      :closable="false"
      style="margin-bottom: 16px"
    />

    <SmartBIEmptyState
      v-if="data && !hasBills"
      type="no-data"
      title="所选区间无营收数据"
      description="该餐饮门店在所选日期范围内未产生订单。请调整日期范围或确认 POS 数据已上传。"
      :show-action="false"
    />

    <template v-else-if="data">
      <!-- KPI cards -->
      <el-row :gutter="16" class="kpi-row">
        <el-col :xs="24" :sm="12" :md="6">
          <CapabilityGate
            v-if="canViewPrice"
            card-id="restaurant_finance_revenue"
            :requires="['date', 'gross_amount']"
          >
            <el-card class="kpi-card kpi-accent-0">
              <div class="kpi-label">总营收</div>
              <div class="kpi-value">{{ fmtMoney(data.kpi.totalRevenue) }}</div>
              <div class="kpi-sub" v-if="data.kpi.coverageStart && data.kpi.coverageEnd">
                {{ data.kpi.coverageStart }} ~ {{ data.kpi.coverageEnd }}
              </div>
            </el-card>
          </CapabilityGate>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card kpi-accent-1">
            <div class="kpi-label">总单数</div>
            <div class="kpi-value">{{ fmtInt(data.kpi.billCount) }}</div>
            <div class="kpi-sub">订单总量</div>
          </el-card>
        </el-col>
        <el-col v-if="canViewPrice" :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card kpi-accent-2">
            <div class="kpi-label">均客单价</div>
            <div class="kpi-value">{{ fmtAvgPerCapita(data.kpi.avgPerCapita) }}</div>
            <div class="kpi-sub">营收 / 客流</div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6">
          <el-card class="kpi-card kpi-accent-3">
            <div class="kpi-label">在营门店</div>
            <div class="kpi-value">{{ fmtInt(data.kpi.storeCount) }}</div>
            <div class="kpi-sub">本期录入门店</div>
          </el-card>
        </el-col>
      </el-row>

      <!-- Monthly revenue chart -->
      <el-card v-if="revenueChartConfig" class="chart-card">
        <template #header>
          <span class="chart-title">{{ revenueChartConfig.title || '月度营收' }}</span>
        </template>
        <DynamicChartRenderer :config="revenueChartConfig" :height="340" />
        <ChartInsightProvider
          :chart="revenueChartMeta"
          :perms="{ canViewFinance: canViewPrice }"
          :factory-id="factoryId"
          depth="detailed"
        />
      </el-card>

      <!-- Phase IIb preview placeholder -->
      <el-card v-if="data.phaseIIbPreview" class="iib-preview-card">
        <template #header>
          <span class="chart-title">
            <el-icon style="vertical-align: -2px; margin-right: 4px"><Promotion /></el-icon>
            成本运营（Phase IIb 预览）
          </span>
        </template>
        <div class="iib-body">
          <div class="iib-headline">
            <span class="iib-label">损耗率</span>
            <span class="iib-value">
              {{ data.phaseIIbPreview.wastageRate != null
                ? `${(data.phaseIIbPreview.wastageRate * 100).toFixed(2)}%`
                : '—' }}
            </span>
          </div>
          <p class="iib-hint">{{ phaseIIbHint }}</p>
          <p class="iib-footnote">
            完整成本/采购/损耗看板（Phase IIb）依赖 accounting_import 与 fact_restaurant_wastage 上线，预计 v2 加入。
          </p>
          <!-- Phase IIb (2026-05-15): cross-link to the 成本运营 tab when IIb enabled. -->
          <el-button
            v-if="phaseIIbEnabled"
            type="primary"
            link
            class="iib-cta"
            @click="emit('goto-kitchen-cost')"
          >
            查看 成本运营详情 →
          </el-button>
        </div>
      </el-card>

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
.restaurant-finance-content {
  padding: 16px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  gap: 12px;
  flex-wrap: wrap;
}
.page-header h1 {
  margin: 0;
  font-size: 22px;
  color: #303133;
}
.header-right {
  display: flex;
  gap: 8px;
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
.coverage-note {
  font-size: 12px;
  color: #909399;
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
.iib-preview-card {
  margin-bottom: 16px;
}
.chart-title {
  font-weight: 600;
  font-size: 15px;
  color: #303133;
}
.iib-body {
  padding: 4px 0;
}
.iib-headline {
  display: flex;
  align-items: baseline;
  gap: 16px;
  margin-bottom: 8px;
}
.iib-label {
  font-size: 13px;
  color: #909399;
}
.iib-value {
  font-size: 28px;
  font-weight: 600;
  color: #303133;
}
.iib-hint {
  font-size: 13px;
  color: #606266;
  margin: 4px 0;
}
.iib-footnote {
  font-size: 12px;
  color: #909399;
  margin: 8px 0 0;
  font-style: italic;
}
.iib-cta {
  margin-top: 8px;
}
.footer-meta {
  margin-top: 16px;
  text-align: right;
  color: #909399;
  font-size: 12px;
}
</style>
