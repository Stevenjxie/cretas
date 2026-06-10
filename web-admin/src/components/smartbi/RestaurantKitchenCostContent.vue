<script setup lang="ts">
/**
 * RestaurantKitchenCostContent — Phase IIb kitchen cost & ops analytics.
 *
 * Backed by GET /api/mobile/{factoryId}/smart-bi/analysis/kitchen-cost.
 * Mounted by FinanceAnalysis.vue under the 成本运营 tab when isRestaurantTenant.
 *
 * Spec: docs/superpowers/specs/2026-05-15-restaurant-phase-iib-kitchen-cost-analytics-spec.md §5
 *
 * Sections:
 *   - KPI strip (4 cards: ratio / wastage / requisition / net variance)
 *   - Section A: 食材成本占比 + benchmark band + alert message
 *   - Section B: 食材损耗分析 (trend chart + top-10 table + by-type pie)
 *   - Section C: 领料成本趋势 (trend + by-category stacked bar)
 *   - Section D: 盘点差异报告 (top-10 table + summary)
 *
 * Empty-state hierarchy (§5.6):
 *   1. Whole-tab empty → big SmartBIEmptyState w/ CTA → /restaurant/requisitions
 *   2. Single-section empty → small in-section empty placeholder
 *   3. POS revenue missing → ratio area placeholder; requisition number still renders
 */
import { ref, computed, onMounted, watch, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import axios from 'axios';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage } from 'element-plus';
import { Refresh, Calendar, Promotion, InfoFilled } from '@element-plus/icons-vue';
import DynamicChartRenderer from './DynamicChartRenderer.vue';
import SmartBIEmptyState from './SmartBIEmptyState.vue';
import CapabilityGate from '@/components/CapabilityGate.vue';
import ChartInsightProvider from '@/views/smart-bi/components/ChartInsightProvider.vue';
import { toApiDateString } from '@/utils/dateFormat';
import {
  getKitchenCostAnalysis,
  isRestaurantKitchenCost,
  type KitchenCostData,
  type KitchenCostResponseData,
  type GroupBy,
  type AlertLevel,
} from '@/api/smartbi/kitchen-cost';
import type { ChartConfig } from '@/types/smartbi';
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight';

// ==================== Props ====================
interface Props {
  factoryId: string;
  dateRange: { startDate: string; endDate: string };
  groupBy?: GroupBy;
}
const props = withDefaults(defineProps<Props>(), {
  groupBy: 'day',
});

const emit = defineEmits<{
  /** Cross-link back to 营收概览 (Phase IIa) tab */
  'goto-overview': [];
}>();

// ==================== Stores ====================
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const canViewPrice = computed(() => permissionStore.canViewPrice);

// ==================== State ====================
const loading = ref(false);
const loadError = ref('');
const data = ref<KitchenCostData | null>(null);
const notApplicableMessage = ref('');
const localGroupBy = ref<GroupBy>(props.groupBy);
let abortCtl: AbortController | null = null;

// ==================== Computed ====================

const hasAnyData = computed(() => {
  if (!data.value) return false;
  const d = data.value;
  return (
    d.wastageAnalytics.totalWastageEvents > 0 ||
    d.requisitionTrend.totalEvents > 0 ||
    d.stocktakingVariance.stocktakingCount > 0 ||
    d.foodCostRatio.totalRequisitionCost > 0
  );
});

const hasWastage = computed(() => (data.value?.wastageAnalytics.totalWastageEvents ?? 0) > 0);
const hasRequisition = computed(() => (data.value?.requisitionTrend.totalEvents ?? 0) > 0);
const hasStocktaking = computed(() => (data.value?.stocktakingVariance.stocktakingCount ?? 0) > 0);
const hasRatio = computed(() => {
  const r = data.value?.foodCostRatio;
  return !!r && r.ratio !== null;
});
const hasRevenue = computed(() => (data.value?.foodCostRatio.totalRevenue ?? 0) > 0);
const hasRequisitionCostOnly = computed(() => {
  const r = data.value?.foodCostRatio;
  return !!r && r.totalRequisitionCost > 0;
});

// ==================== Formatters ====================

const moneyFmt = new Intl.NumberFormat('zh-CN', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});
const intFmt = new Intl.NumberFormat('zh-CN');
const qtyFmt = new Intl.NumberFormat('zh-CN', {
  minimumFractionDigits: 0,
  maximumFractionDigits: 3,
});

function fmtMoney(v: number | null | undefined): string {
  if (v === null || v === undefined) return '—';
  return `¥${moneyFmt.format(v)}`;
}
function fmtInt(v: number | null | undefined): string {
  if (v === null || v === undefined) return '—';
  return intFmt.format(v);
}
function fmtQty(v: number | null | undefined): string {
  if (v === null || v === undefined) return '—';
  return qtyFmt.format(v);
}
function fmtPercent(v: number | null | undefined): string {
  if (v === null || v === undefined) return '—';
  return `${v.toFixed(1)}%`;
}

// ==================== Alert level styling ====================

function alertBadgeType(level: AlertLevel | null | undefined): 'success' | 'warning' | 'danger' | 'info' {
  switch (level) {
    case 'GREEN':
      return 'success';
    case 'YELLOW':
      return 'warning';
    case 'RED':
      return 'danger';
    default:
      return 'info';
  }
}
function alertBadgeLabel(level: AlertLevel | null | undefined): string {
  switch (level) {
    case 'GREEN':
      return '健康';
    case 'YELLOW':
      return '警戒';
    case 'RED':
      return '危险';
    default:
      return '暂无';
  }
}

// ==================== Chart configs ====================

function makeWastageTrendChart(): ChartConfig | null {
  const trend = data.value?.wastageAnalytics.trend ?? [];
  if (trend.length === 0) return null;
  return {
    chartType: 'line',
    title: '食材损耗成本趋势',
    xAxis: { data: trend.map((p) => p.period) },
    series: [
      {
        name: '损耗成本',
        type: 'line',
        data: trend.map((p) => p.totalCost),
      },
    ],
  } as ChartConfig;
}

function makeWastageByTypePie(): ChartConfig | null {
  const byType = data.value?.wastageAnalytics.wastageByType ?? [];
  if (byType.length === 0) return null;
  // 5 type labels (verified prod 2026-05-15)
  const labelMap: Record<string, string> = {
    DAMAGED: '损坏',
    EXPIRED: '过期',
    OTHER: '其他',
    PROCESSING: '加工损耗',
    SPOILED: '变质',
  };
  return {
    chartType: 'pie',
    title: '损耗类型分布',
    series: [
      {
        name: '损耗成本',
        type: 'pie',
        data: byType.map((b) => ({
          name: labelMap[b.type] || b.type,
          value: b.totalCost,
        })),
      },
    ],
  } as unknown as ChartConfig;
}

function makeRequisitionTrendChart(): ChartConfig | null {
  const trend = data.value?.requisitionTrend.trend ?? [];
  if (trend.length === 0) return null;
  return {
    chartType: 'line',
    title: '领料成本趋势',
    xAxis: { data: trend.map((p) => p.period) },
    series: [
      {
        name: '领料成本',
        type: 'line',
        data: trend.map((p) => p.totalCost),
      },
    ],
  } as ChartConfig;
}

function makeRequisitionByCategoryChart(): ChartConfig | null {
  const byCat = data.value?.requisitionTrend.byCategory ?? [];
  if (byCat.length === 0) return null;
  return {
    chartType: 'bar',
    title: '领料成本（按食材类别）',
    xAxis: { data: byCat.map((c) => c.category) },
    series: [
      {
        name: '类别成本',
        type: 'bar',
        data: byCat.map((c) => c.totalCost),
      },
    ],
  } as ChartConfig;
}

const wastageTrendConfig = computed(() => makeWastageTrendChart());
const wastageByTypeConfig = computed(() => makeWastageByTypePie());
const requisitionTrendConfig = computed(() => makeRequisitionTrendChart());
const requisitionByCategoryConfig = computed(() => makeRequisitionByCategoryChart());

// ---- ChartWithMeta for ChartInsightProvider ----

/** LINE 食材损耗成本趋势. xDim=time. yMetric=cost. TREND. */
const wastageTrendMeta = computed<ChartWithMeta | null>(() => {
  const trend = data.value?.wastageAnalytics.trend ?? [];
  if (trend.length === 0) return null;
  return {
    chartType: 'line',
    title: '食材损耗成本趋势',
    meta: { xDim: 'time', yMetric: 'cost', aggregation: 'sum', domain: 'restaurant' },
    config: {
      xAxis: { data: trend.map((p) => p.period) },
      series: [{ type: 'line', data: trend.map((p) => p.totalCost) }],
    },
  };
});

/** PIE 损耗类型分布. xDim=category. yMetric=cost. PROPORTION. */
const wastageByTypeMeta = computed<ChartWithMeta | null>(() => {
  const byType = data.value?.wastageAnalytics.wastageByType ?? [];
  if (byType.length === 0) return null;
  const labelMap: Record<string, string> = {
    DAMAGED: '损坏',
    EXPIRED: '过期',
    OTHER: '其他',
    PROCESSING: '加工损耗',
    SPOILED: '变质',
  };
  return {
    chartType: 'pie',
    title: '损耗类型分布',
    meta: { xDim: 'category', yMetric: 'cost', aggregation: 'sum', domain: 'restaurant' },
    config: {
      xAxis: { data: byType.map((b) => labelMap[b.type] || b.type) },
      series: [
        {
          type: 'pie',
          data: byType.map((b) => ({ name: labelMap[b.type] || b.type, value: b.totalCost })),
        },
      ],
    },
  };
});

/** LINE 领料成本趋势. xDim=time. yMetric=cost. TREND. */
const requisitionTrendMeta = computed<ChartWithMeta | null>(() => {
  const trend = data.value?.requisitionTrend.trend ?? [];
  if (trend.length === 0) return null;
  return {
    chartType: 'line',
    title: '领料成本趋势',
    meta: { xDim: 'time', yMetric: 'cost', aggregation: 'sum', domain: 'restaurant' },
    config: {
      xAxis: { data: trend.map((p) => p.period) },
      series: [{ type: 'line', data: trend.map((p) => p.totalCost) }],
    },
  };
});

/** BAR 领料成本（按食材类别）. xDim=category. yMetric=cost. RANKING. */
const requisitionByCategoryMeta = computed<ChartWithMeta | null>(() => {
  const byCat = data.value?.requisitionTrend.byCategory ?? [];
  if (byCat.length === 0) return null;
  return {
    chartType: 'bar',
    title: '领料成本（按食材类别）',
    meta: { xDim: 'category', yMetric: 'cost', aggregation: 'sum', domain: 'restaurant' },
    config: {
      xAxis: { data: byCat.map((c) => c.category) },
      series: [{ type: 'bar', data: byCat.map((c) => c.totalCost) }],
    },
  };
});

// ==================== Benchmark band visualization ====================

/** ratio 0..1 → percent 0..100, clamped at 60 for the band visualization */
const ratioMarkerPercent = computed(() => {
  const r = data.value?.foodCostRatio.ratio;
  if (r === null || r === undefined) return null;
  return Math.min(Math.max(r * 100, 0), 60);
});

// ==================== Data loading ====================

async function loadData() {
  if (!props.factoryId) {
    loadError.value = '未找到工厂ID，请重新登录';
    return;
  }
  if (!props.dateRange?.startDate || !props.dateRange?.endDate) return;

  if (abortCtl) abortCtl.abort();
  abortCtl = new AbortController();

  loading.value = true;
  loadError.value = '';
  notApplicableMessage.value = '';
  try {
    const response = await getKitchenCostAnalysis({
      startDate: props.dateRange.startDate,
      endDate: props.dateRange.endDate,
      groupBy: localGroupBy.value,
    });
    if (!response.success || !response.data) {
      loadError.value = response.message || '加载厨房成本数据失败';
      data.value = null;
      return;
    }
    // Some backend wrappers nest the real payload under response.data.data
    // (mirror RestaurantFinanceContent.vue defensive unwrap pattern).
    const raw = response.data as unknown as Record<string, unknown>;
    const inner: KitchenCostResponseData =
      raw && typeof raw === 'object' && 'data' in raw && raw.data && typeof raw.data === 'object' && !Array.isArray(raw.data) && 'tenantType' in (raw.data as object)
        ? (raw.data as KitchenCostResponseData)
        : (response.data as KitchenCostResponseData);

    if (isRestaurantKitchenCost(inner)) {
      data.value = inner;
    } else {
      // Factory branch (kitchen-cost not applicable)
      notApplicableMessage.value = inner.message || '厨房成本运营分析仅适用于餐饮租户。';
      data.value = null;
    }
  } catch (error) {
    if (axios.isCancel(error)) return;
    if (error instanceof DOMException && error.name === 'AbortError') return;
    if (abortCtl?.signal.aborted) return;
    const msg = error instanceof Error ? error.message : '未知错误';
    loadError.value = `加载厨房成本数据失败: ${msg}`;
    ElMessage.error(loadError.value);
    data.value = null;
  } finally {
    loading.value = false;
  }
}

function refresh() {
  loadData();
}

function gotoRequisitions() {
  router.push('/restaurant/requisitions');
}
function gotoWastage() {
  router.push('/restaurant/wastage');
}
function gotoStocktaking() {
  router.push('/restaurant/stocktaking');
}
function gotoOverview() {
  emit('goto-overview');
}

function changeGroupBy(g: GroupBy) {
  if (localGroupBy.value === g) return;
  localGroupBy.value = g;
  loadData();
}

// ==================== Lifecycle ====================

onMounted(() => {
  loadData();
});

watch(
  () => [props.dateRange?.startDate, props.dateRange?.endDate, props.factoryId],
  () => loadData(),
);

onUnmounted(() => {
  if (abortCtl) {
    abortCtl.abort();
    abortCtl = null;
  }
});
</script>

<template>
  <div class="restaurant-kitchen-cost-content">
    <div class="page-header">
      <div class="header-left">
        <h2>成本运营（餐饮厨房）</h2>
        <span class="header-subtitle">基于领料/损耗/盘点录入的运营成本分析</span>
      </div>
      <div class="header-right">
        <el-button-group class="groupby-switch" size="small">
          <el-button
            :type="localGroupBy === 'day' ? 'primary' : ''"
            @click="changeGroupBy('day')"
          >日</el-button>
          <el-button
            :type="localGroupBy === 'week' ? 'primary' : ''"
            @click="changeGroupBy('week')"
          >周</el-button>
          <el-button
            :type="localGroupBy === 'month' ? 'primary' : ''"
            @click="changeGroupBy('month')"
          >月</el-button>
        </el-button-group>
        <el-button :icon="Refresh" :loading="loading" size="small" @click="refresh">刷新</el-button>
      </div>
    </div>

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
      v-if="notApplicableMessage"
      type="info"
      :title="notApplicableMessage"
      show-icon
      :closable="false"
      style="margin-bottom: 16px"
    />

    <!-- Empty-state Level 1: whole tab empty (§5.6.1) -->
    <SmartBIEmptyState
      v-if="data && !hasAnyData"
      type="no-data"
      title="暂无厨房运营数据"
      description="在领料管理 / 损耗记录 / 盘点管理模块录入业务数据后，此处将自动分析。"
      action-text="前往运营管理"
      :show-action="true"
      @action="gotoRequisitions"
    />

    <template v-else-if="data">
      <!-- ==================== KPI strip ==================== -->
      <el-row :gutter="16" class="kpi-row">
        <!-- KPI 1: 食材成本占比 with alert badge -->
        <el-col :xs="24" :sm="12" :md="6">
          <CapabilityGate
            v-if="canViewPrice"
            card-id="restaurant_kitchen_cost_ratio"
            :requires="['date', 'gross_amount']"
          >
            <el-card class="kpi-card kpi-accent-ratio">
              <div class="kpi-label-row">
                <span class="kpi-label">食材成本占比</span>
                <el-tag
                  v-if="data.foodCostRatio.alertLevel"
                  :type="alertBadgeType(data.foodCostRatio.alertLevel)"
                  size="small"
                >
                  {{ alertBadgeLabel(data.foodCostRatio.alertLevel) }}
                </el-tag>
              </div>
              <div class="kpi-value">{{ fmtPercent(data.foodCostRatio.ratioPercent) }}</div>
              <div class="kpi-sub">健康 &lt; 30% · 警戒 35% · 危险 &gt; 40%</div>
            </el-card>
          </CapabilityGate>
        </el-col>

        <!-- KPI 2: 总损耗成本 -->
        <el-col :xs="24" :sm="12" :md="6">
          <CapabilityGate
            v-if="canViewPrice"
            card-id="restaurant_kitchen_cost_wastage"
            :requires="['date', 'gross_amount']"
          >
            <el-card class="kpi-card kpi-accent-wastage">
              <div class="kpi-label">总损耗成本</div>
              <div class="kpi-value">{{ fmtMoney(data.wastageAnalytics.totalWastageCost) }}</div>
              <div class="kpi-sub">{{ fmtInt(data.wastageAnalytics.totalWastageEvents) }} 起损耗事件</div>
            </el-card>
          </CapabilityGate>
        </el-col>

        <!-- KPI 3: 总领料成本 -->
        <el-col :xs="24" :sm="12" :md="6">
          <CapabilityGate
            v-if="canViewPrice"
            card-id="restaurant_kitchen_cost_requisition"
            :requires="['date', 'gross_amount']"
          >
            <el-card class="kpi-card kpi-accent-requisition">
              <div class="kpi-label">总领料成本</div>
              <div class="kpi-value">{{ fmtMoney(data.requisitionTrend.totalCost) }}</div>
              <div class="kpi-sub">{{ fmtInt(data.requisitionTrend.totalEvents) }} 单领料</div>
            </el-card>
          </CapabilityGate>
        </el-col>

        <!-- KPI 4: 净盘点差异 -->
        <el-col :xs="24" :sm="12" :md="6">
          <CapabilityGate
            v-if="canViewPrice"
            card-id="restaurant_kitchen_cost_variance"
            :requires="['date', 'gross_amount']"
          >
            <el-card class="kpi-card kpi-accent-variance">
              <div class="kpi-label">净盘点差异</div>
              <div class="kpi-value">{{ fmtMoney(data.stocktakingVariance.netVarianceCost) }}</div>
              <div class="kpi-sub">{{ fmtInt(data.stocktakingVariance.stocktakingCount) }} 次盘点</div>
            </el-card>
          </CapabilityGate>
        </el-col>
      </el-row>

      <!-- ==================== Section A: 食材成本占比 ==================== -->
      <el-card class="section-card">
        <template #header>
          <span class="section-title">
            <el-icon><Promotion /></el-icon>
            食材成本占比
          </span>
        </template>
        <!-- §5.6.3: requisition cost preserved but revenue missing → placeholder for ratio area -->
        <div v-if="!hasRevenue && hasRequisitionCostOnly" class="ratio-empty-3">
          <p class="ratio-empty-message">
            {{ data.foodCostRatio.alertMessage || '暂无营收数据，无法计算占比' }}
          </p>
          <p class="ratio-fallback-line">
            当前领料成本：<strong>{{ fmtMoney(data.foodCostRatio.totalRequisitionCost) }}</strong>
          </p>
        </div>
        <div v-else-if="!hasRatio && !hasRequisitionCostOnly" class="section-empty-inline">
          <p>暂无成本数据</p>
          <p class="section-empty-sub">先在领料管理录入数据，再返回查看占比</p>
        </div>
        <div v-else class="ratio-body">
          <div class="ratio-headline">
            <span class="ratio-number">{{ fmtPercent(data.foodCostRatio.ratioPercent) }}</span>
            <el-tag
              v-if="data.foodCostRatio.alertLevel"
              :type="alertBadgeType(data.foodCostRatio.alertLevel)"
              size="default"
              effect="dark"
            >
              {{ alertBadgeLabel(data.foodCostRatio.alertLevel) }}
            </el-tag>
          </div>
          <!-- Horizontal benchmark band -->
          <div class="benchmark-band">
            <div class="band-segment band-healthy" :style="{ width: '50%' }">
              <span class="band-label">健康 &lt; 30%</span>
            </div>
            <div class="band-segment band-warning" :style="{ width: '8.33%' }">
              <span class="band-label">良好</span>
            </div>
            <div class="band-segment band-alert" :style="{ width: '8.33%' }">
              <span class="band-label">警戒</span>
            </div>
            <div class="band-segment band-critical" :style="{ width: '33.33%' }">
              <span class="band-label">危险 &gt; 40%</span>
            </div>
            <div
              v-if="ratioMarkerPercent !== null"
              class="band-marker"
              :style="{ left: `${(ratioMarkerPercent / 60) * 100}%` }"
            />
          </div>
          <p
            v-if="data.foodCostRatio.alertMessage"
            class="ratio-alert-message"
          >
            {{ data.foodCostRatio.alertMessage }}
          </p>
          <div
            v-if="data.foodCostRatio.dataCaveats && data.foodCostRatio.dataCaveats.length > 0"
            class="ratio-caveats"
          >
            <el-icon><InfoFilled /></el-icon>
            <span
              v-for="(c, idx) in data.foodCostRatio.dataCaveats"
              :key="idx"
              class="caveat-item"
            >{{ c }}</span>
          </div>
        </div>
      </el-card>

      <!-- ==================== Section B: 食材损耗分析 ==================== -->
      <el-card class="section-card">
        <template #header>
          <span class="section-title">
            <el-icon><Promotion /></el-icon>
            食材损耗分析
          </span>
        </template>
        <SmartBIEmptyState
          v-if="!hasWastage"
          type="no-data"
          title="暂无损耗数据"
          description="在损耗管理模块录入数据后，此处将显示损耗趋势与排行"
          action-text="前往损耗管理"
          :show-action="true"
          @action="gotoWastage"
        />
        <template v-else>
          <div class="wastage-charts-row">
            <div class="wastage-trend-col">
              <DynamicChartRenderer
                v-if="wastageTrendConfig"
                :config="wastageTrendConfig"
                :height="280"
              />
              <ChartInsightProvider
                :chart="wastageTrendMeta"
                :perms="{ canViewFinance: canViewPrice }"
                :factory-id="props.factoryId"
                depth="detailed"
              />
            </div>
            <div class="wastage-pie-col">
              <DynamicChartRenderer
                v-if="wastageByTypeConfig"
                :config="wastageByTypeConfig"
                :height="280"
              />
              <ChartInsightProvider
                :chart="wastageByTypeMeta"
                :perms="{ canViewFinance: canViewPrice }"
                :factory-id="props.factoryId"
                depth="detailed"
              />
            </div>
          </div>

          <el-divider />

          <div class="table-title">Top 10 高损耗食材</div>
          <el-table
            :data="data.wastageAnalytics.topWasteIngredients"
            stripe
            empty-text="暂无排行数据"
            size="small"
            style="width: 100%"
          >
            <el-table-column type="index" label="#" width="60" align="center" />
            <el-table-column prop="name" label="食材" min-width="120" />
            <el-table-column prop="category" label="类别" width="100" />
            <el-table-column
              v-if="canViewPrice"
              prop="totalCost"
              label="损耗成本"
              width="120"
              align="right"
              :formatter="(_row: unknown, _col: unknown, v: number | null) => fmtMoney(v)"
            />
            <el-table-column
              prop="quantity"
              label="损耗数量"
              width="120"
              align="right"
              :formatter="(row: { quantity: number | null; unit: string }) => `${fmtQty(row.quantity)} ${row.unit || ''}`"
            />
            <el-table-column
              prop="eventCount"
              label="次数"
              width="80"
              align="right"
              :formatter="(_row: unknown, _col: unknown, v: number) => fmtInt(v)"
            />
          </el-table>
        </template>
      </el-card>

      <!-- ==================== Section C: 领料成本趋势 ==================== -->
      <el-card class="section-card">
        <template #header>
          <span class="section-title">
            <el-icon><Promotion /></el-icon>
            领料成本趋势
          </span>
        </template>
        <SmartBIEmptyState
          v-if="!hasRequisition"
          type="no-data"
          title="暂无领料数据"
          description="在领料管理模块录入数据后，此处将显示领料趋势与类别分布"
          action-text="前往领料管理"
          :show-action="true"
          @action="gotoRequisitions"
        />
        <template v-else>
          <div class="requisition-charts-row">
            <div class="requisition-trend-col">
              <DynamicChartRenderer
                v-if="requisitionTrendConfig"
                :config="requisitionTrendConfig"
                :height="280"
              />
              <ChartInsightProvider
                :chart="requisitionTrendMeta"
                :perms="{ canViewFinance: canViewPrice }"
                :factory-id="props.factoryId"
                depth="detailed"
              />
            </div>
            <div class="requisition-cat-col">
              <DynamicChartRenderer
                v-if="requisitionByCategoryConfig"
                :config="requisitionByCategoryConfig"
                :height="280"
              />
              <ChartInsightProvider
                :chart="requisitionByCategoryMeta"
                :perms="{ canViewFinance: canViewPrice }"
                :factory-id="props.factoryId"
                depth="detailed"
              />
              <p class="category-note">
                <el-icon><InfoFilled /></el-icon>
                类别基于食材主数据，部分门店未分类项归入 "其他"。
              </p>
            </div>
          </div>
        </template>
      </el-card>

      <!-- ==================== Section D: 盘点差异报告 ==================== -->
      <el-card class="section-card">
        <template #header>
          <span class="section-title">
            <el-icon><Promotion /></el-icon>
            盘点差异报告
          </span>
        </template>
        <SmartBIEmptyState
          v-if="!hasStocktaking"
          type="no-data"
          title="暂无盘点数据"
          description="建议每月至少进行一次盘点，以掌握真实库存"
          action-text="前往盘点管理"
          :show-action="true"
          @action="gotoStocktaking"
        />
        <template v-else>
          <el-row :gutter="12" class="variance-summary-row">
            <el-col :xs="24" :sm="8">
              <div class="variance-summary-card variance-shortage">
                <div class="variance-label">总短缺金额</div>
                <div class="variance-value">{{ fmtMoney(data.stocktakingVariance.totalShortageCost) }}</div>
                <div class="variance-sub">短缺数量 {{ fmtQty(data.stocktakingVariance.totalShortageQty) }}</div>
              </div>
            </el-col>
            <el-col :xs="24" :sm="8">
              <div class="variance-summary-card variance-surplus">
                <div class="variance-label">总盈余金额</div>
                <div class="variance-value">{{ fmtMoney(data.stocktakingVariance.totalSurplusCost) }}</div>
                <div class="variance-sub">盈余数量 {{ fmtQty(data.stocktakingVariance.totalSurplusQty) }}</div>
              </div>
            </el-col>
            <el-col :xs="24" :sm="8">
              <div class="variance-summary-card variance-net">
                <div class="variance-label">净差异</div>
                <div class="variance-value">{{ fmtMoney(data.stocktakingVariance.netVarianceCost) }}</div>
                <div
                  v-if="data.stocktakingVariance.lastStocktakingDate"
                  class="variance-sub"
                >
                  上次盘点 {{ data.stocktakingVariance.lastStocktakingDate }}
                </div>
              </div>
            </el-col>
          </el-row>

          <el-divider />

          <div class="table-title">Top 10 差异食材</div>
          <el-table
            :data="data.stocktakingVariance.byIngredient"
            stripe
            empty-text="暂无差异数据"
            size="small"
            style="width: 100%"
          >
            <el-table-column type="index" label="#" width="60" align="center" />
            <el-table-column prop="name" label="食材" min-width="120" />
            <el-table-column prop="category" label="类别" width="100" />
            <el-table-column
              prop="diffQty"
              label="差异数量"
              width="120"
              align="right"
              :formatter="(_row: unknown, _col: unknown, v: number) => fmtQty(v)"
            />
            <el-table-column
              v-if="canViewPrice"
              prop="diffCost"
              label="差异金额"
              width="120"
              align="right"
              :formatter="(_row: unknown, _col: unknown, v: number) => fmtMoney(v)"
            />
          </el-table>
        </template>
      </el-card>

      <!-- Footer + cross-link back to 营收概览 -->
      <div class="footer-actions">
        <el-button link type="primary" @click="gotoOverview">← 返回 营收概览</el-button>
        <span v-if="data.generatedAt" class="footer-meta">生成时间：{{ data.generatedAt }}</span>
      </div>
    </template>

    <SmartBIEmptyState
      v-else-if="!loading && !notApplicableMessage"
      type="loading-failed"
      :show-action="true"
      action-text="重新加载"
      @action="refresh"
    />
  </div>
</template>

<style scoped>
.restaurant-kitchen-cost-content {
  padding: 4px 0 16px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  gap: 12px;
  flex-wrap: wrap;
}
.header-left h2 {
  margin: 0 0 4px 0;
  font-size: 18px;
  color: #303133;
}
.header-subtitle {
  font-size: 12px;
  color: #909399;
}
.header-right {
  display: flex;
  gap: 8px;
  align-items: center;
}
.groupby-switch {
  margin-right: 4px;
}

.kpi-row {
  margin-bottom: 16px;
}
.kpi-card {
  padding: 4px 0;
}
.kpi-label-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.kpi-label {
  font-size: 13px;
  color: #909399;
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
.kpi-accent-ratio { border-left: 3px solid #5470c6; }
.kpi-accent-wastage { border-left: 3px solid #ee6666; }
.kpi-accent-requisition { border-left: 3px solid #91cc75; }
.kpi-accent-variance { border-left: 3px solid #fac858; }

.section-card {
  margin-bottom: 16px;
}
.section-title {
  font-weight: 600;
  font-size: 15px;
  color: #303133;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

/* Section A: ratio + benchmark band */
.ratio-body {
  padding: 8px 0;
}
.ratio-headline {
  display: flex;
  align-items: baseline;
  gap: 16px;
  margin-bottom: 16px;
}
.ratio-number {
  font-size: 32px;
  font-weight: 600;
  color: #303133;
}
.benchmark-band {
  position: relative;
  display: flex;
  width: 100%;
  height: 36px;
  border-radius: 6px;
  overflow: hidden;
  margin-bottom: 16px;
  background: #f5f7fa;
}
.band-segment {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: #fff;
}
.band-healthy { background: #67c23a; }
.band-warning { background: #95d475; }
.band-alert { background: #e6a23c; }
.band-critical { background: #f56c6c; }
.band-label { white-space: nowrap; opacity: 0.95; }
.band-marker {
  position: absolute;
  top: -4px;
  width: 4px;
  height: 44px;
  background: #303133;
  border-radius: 2px;
  transition: left 0.4s ease;
  transform: translateX(-2px);
  box-shadow: 0 0 4px rgba(0, 0, 0, 0.3);
}
.ratio-alert-message {
  font-size: 13px;
  color: #606266;
  margin: 8px 0;
}
.ratio-caveats {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #909399;
  margin-top: 8px;
  flex-wrap: wrap;
}
.caveat-item:not(:last-child)::after {
  content: '；';
}

/* Section A empty-state-3 (revenue missing) */
.ratio-empty-3 {
  padding: 24px;
  text-align: center;
  background: #fafafa;
  border-radius: 6px;
}
.ratio-empty-message {
  font-size: 14px;
  color: #909399;
  margin: 0 0 8px;
}
.ratio-fallback-line {
  font-size: 13px;
  color: #606266;
  margin: 0;
}
.section-empty-inline {
  padding: 24px;
  text-align: center;
  color: #909399;
}
.section-empty-inline p {
  margin: 4px 0;
}
.section-empty-sub {
  font-size: 12px;
}

/* Section B wastage row */
.wastage-charts-row,
.requisition-charts-row {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}
.wastage-trend-col,
.requisition-trend-col {
  flex: 2 1 360px;
  min-width: 320px;
}
.wastage-pie-col,
.requisition-cat-col {
  flex: 1 1 240px;
  min-width: 240px;
}
.category-note {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.table-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin: 8px 0 12px;
}

/* Section D variance summary */
.variance-summary-row {
  margin-bottom: 8px;
}
.variance-summary-card {
  padding: 12px 16px;
  border-radius: 6px;
  background: #fafafa;
  border-left: 3px solid #dcdfe6;
}
.variance-shortage { border-left-color: #f56c6c; }
.variance-surplus { border-left-color: #67c23a; }
.variance-net { border-left-color: #5470c6; }
.variance-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 6px;
}
.variance-value {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}
.variance-sub {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.footer-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 16px;
  padding: 0 4px;
}
.footer-meta {
  color: #909399;
  font-size: 12px;
}
</style>
