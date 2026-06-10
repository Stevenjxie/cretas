<template>
  <!--
    财务 PBI 看板 — Gold 财务全量视图 (follow-up fu2, Jun 2026)

    背景: PBI 看板 (FinancialDashboardPBI) 原本是 upload_id 绑定的 — 餐饮租户 (青花椒)
    没有上传财务 Excel, 进 PBI 看板子页只能看到「请选择数据源」空态 + 需手动点「生成看板」。
    餐饮租户真实经营数据在 Gold 全量层 (agg_daily / agg_monthly), 不依赖上传。

    本组件: 餐饮租户进 PBI 看板子页即自动渲染 Gold 财务全量 (免上传 / 免手动生成):
      - 经营 KPI 卡 (营收/订单/客单价/门店/天数/峰值月) — 复用 buildRestaurantKpiBoard
      - 月度营收趋势 (折线) — 复用 goldTrendBundleToViewModel
      - 门店营收排行 + 渠道占比 — 复用 RestaurantGoldGrid

    数据源全部走 gold.ts (getKpiSummary / getFinanceSummary / getTrendBundle / getChannelBreakdown),
    全量 (all-history, 省略 start/end), 金额字段对非 price-view 角色 RBAC 置 null → 显示 "—" 而非伪造。
    禁降级假数据: 加载失败显式报错。
  -->
  <section class="fpgv">
    <!-- Header note — 明示数据来自 Gold 全量层 (per task: "本厂财务数据来自 Gold 全量层") -->
    <div class="fpgv-header">
      <div class="fpgv-header-info">
        <el-icon class="fpgv-header-icon"><DataAnalysis /></el-icon>
        <div>
          <div class="fpgv-header-title">本厂财务数据来自 Gold 全量层（无需上传 / 无需手动生成）</div>
          <div class="fpgv-header-desc">
            您的经营数据已汇总到全量数据层，下方直接展示经营 KPI、月度营收趋势、门店排行与渠道占比（{{ dateLabel }}）。
            如需上传财务 Excel 走 PBI 自定义看板，可点右侧「上传财务文件」。
          </div>
        </div>
      </div>
      <div class="fpgv-header-actions">
        <el-button size="small" plain :loading="loading" @click="reload">
          <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
        </el-button>
        <el-button size="small" plain @click="$emit('switch-to-upload')">
          <el-icon style="margin-right: 4px"><Upload /></el-icon>上传财务文件
        </el-button>
      </div>
    </div>

    <!-- 禁降级假数据: 加载失败显式报错 -->
    <div v-if="loadError" class="fpgv-error">
      加载 Gold 财务数据失败: {{ loadError }}
    </div>

    <template v-else>
      <!-- 经营 KPI 卡 (复用 buildRestaurantKpiBoard 视图模型) -->
      <div v-if="kpiBoard && kpiBoard.hasData" class="fpgv-kpi-board" v-loading="loading">
        <div class="fpgv-kpi-title">
          <el-icon><DataAnalysis /></el-icon>
          <span>经营 KPI (全部历史)</span>
          <span v-if="kpiBoard.topStoreName" class="fpgv-kpi-sub">营收最高门店: {{ kpiBoard.topStoreName }}</span>
        </div>
        <div class="fpgv-kpi-cards">
          <div v-for="item in kpiBoard.items" :key="item.key" class="fpgv-kpi-card">
            <div class="fpgv-kpi-card-icon"><el-icon><component :is="kpiIconFor[item.key]" /></el-icon></div>
            <div class="fpgv-kpi-card-body">
              <div class="fpgv-kpi-card-value">{{ kpiDisplay(item) }}</div>
              <div class="fpgv-kpi-card-label">{{ item.label }}</div>
              <div v-if="item.hint" class="fpgv-kpi-card-hint">{{ item.hint }}</div>
            </div>
          </div>
        </div>
      </div>
      <div v-else-if="!loading && kpiBoard && !kpiBoard.hasData" class="fpgv-kpi-empty">
        <el-empty description="暂无经营数据 — 上传 POS 流水后这里将显示营收/订单/客单价等 KPI" :image-size="60" />
      </div>
      <el-skeleton v-else-if="loading && !kpiBoard" :rows="3" animated style="margin-bottom: 16px" />

      <!-- 月度营收趋势 (复用 goldTrendBundleToViewModel) -->
      <el-card class="fpgv-trend-card" shadow="never">
        <template #header>
          <div class="fpgv-card-header">
            <span><el-icon style="margin-right: 4px"><TrendCharts /></el-icon>营收趋势</span>
            <span v-if="trendVm" class="fpgv-card-sub">
              {{ trendVm.granularity === 'monthly' ? '按月' : '按日' }} · {{ trendVm.pointCount }} 个数据点
            </span>
          </div>
        </template>
        <el-skeleton v-if="loading && !trendVm" :rows="4" animated />
        <div v-else-if="trendVm" ref="trendChartEl" class="fpgv-trend-chart"></div>
        <el-empty v-else description="暂无营收趋势数据" :image-size="60" />
        <!-- U6: useChartInsight — Tier1 instant, Tier2 auto on null (飞轮接通) -->
        <ChartInsight :insight="financeTrendInsight" :loading="financeTrendInsightLoading" depth="detailed" />
      </el-card>

      <!-- 门店营收排行 + 渠道占比 — 复用经营驾驶舱组件 (全量 agg_daily, 不依赖上传) -->
      <RestaurantGoldGrid :factory-id="factoryId" :date-range="goldDateRange" />
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { DataAnalysis, Refresh, Upload, TrendCharts, Money, Sell, Calendar, Shop } from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { getKpiSummary, getFinanceSummary, getTrendBundle } from '@/api/smartbi/gold';
import { getGoldDataRange } from '@/api/smartbi/dataRange';
import { resolveAllHistoryRange } from '../analysisDefaults';
import { buildRestaurantKpiBoard, type RestaurantKpiBoard } from '@/views/analytics/kpi/restaurantKpiBoard';
import { goldTrendBundleToViewModel, type TrendBundleViewModel } from '@/views/analytics/trends/goldTrendBundle';
import { formatNumber } from '@/utils/format-number';
import RestaurantGoldGrid from './RestaurantGoldGrid.vue';
import type { ChartWithMeta } from './chartInsight';
import ChartInsight from './ChartInsight.vue';
import { useChartInsight } from '@/composables/useChartInsight';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId || '');
const canViewPrice = computed(() => permissionStore.canViewPrice);

const loading = ref(false);
const loadError = ref('');
const kpiBoard = ref<RestaurantKpiBoard | null>(null);
const trendVm = ref<TrendBundleViewModel | null>(null);
// RestaurantGoldGrid 需要非 null dateRange 才加载; 父探测 Gold 真实区间填充 (绝不回到近30天)。
const goldDateRange = ref<[string, string] | null>(null);

const trendChartEl = ref<HTMLElement | null>(null);
let trendChart: echarts.ECharts | null = null;
let isUnmounted = false;

defineEmits<{ (e: 'switch-to-upload'): void }>();

const dateLabel = computed(() =>
  goldDateRange.value ? `${goldDateRange.value[0]} 至 ${goldDateRange.value[1]}` : '全部历史',
);

const kpiIconFor: Record<string, unknown> = {
  revenue: Money,
  billCount: Sell,
  avgBillValue: Money,
  storeCount: Shop,
  dayCount: Calendar,
  peakMonth: TrendCharts,
};

function kpiDisplay(item: { value: number | null; text?: string | null; kind: string }): string {
  if (item.kind === 'text') return item.text || '—';
  if (item.value === null) return '—';
  if (item.kind === 'money') return '¥' + formatNumber(item.value);
  return formatNumber(item.value, 0);
}

const fmtRevenue = (v: number): string =>
  v >= 10000 ? `¥${(v / 10000).toFixed(2)}万` : `¥${v.toLocaleString('zh-CN', { maximumFractionDigits: 0 })}`;

function renderTrendChart() {
  const vm = trendVm.value;
  const el = trendChartEl.value;
  if (!vm || !el) return;
  if (!trendChart) trendChart = echarts.init(el, 'cretas');
  const granLabel = vm.granularity === 'monthly' ? '按月' : '按日';
  trendChart.setOption(
    {
      title: { text: `营收趋势 · ${granLabel} (Gold 全量)`, left: 'center', textStyle: { fontSize: 14 } },
      tooltip: {
        trigger: 'axis',
        confine: true,
        formatter: (params: Array<{ value: number; axisValue: string; marker: string }>) => {
          if (!Array.isArray(params) || params.length === 0) return '';
          const p = params[0];
          const v = typeof p.value === 'number' ? p.value : Number(p.value);
          return `${p.axisValue}<br/>${p.marker} 营收: ${fmtRevenue(v)}`;
        },
      },
      grid: { top: 50, left: 60, right: 30, bottom: 60 },
      xAxis: {
        type: 'category',
        data: vm.categories,
        axisLabel: { rotate: vm.categories.length > 12 ? 40 : 0, interval: 'auto' },
      },
      yAxis: {
        type: 'value',
        name: '营收',
        axisLabel: { formatter: (v: number) => (v >= 10000 ? `${(v / 10000).toFixed(1)}万` : String(v)) },
      },
      series: [
        {
          name: '营收',
          type: 'line',
          smooth: true,
          areaStyle: { opacity: 0.3 },
          itemStyle: { color: '#67C23A' },
          data: vm.revenue,
        },
      ],
    },
    true,
  );
}

async function probeRange() {
  if (!factoryId.value) return;
  try {
    const dr = await getGoldDataRange(factoryId.value);
    goldDateRange.value = resolveAllHistoryRange(dr);
  } catch {
    // 探测失败 → 宽回退窗 (绝不回到近30天); 子组件自行处理空数据/报错, 不伪造。
    goldDateRange.value = resolveAllHistoryRange(null);
  }
}

async function loadGold() {
  if (!factoryId.value) return;
  loading.value = true;
  loadError.value = '';
  try {
    // All-history (省略 dates). 金额字段对非 price-view 角色 RBAC 置 null。
    const [kpi, fin, bundle] = await Promise.all([
      getKpiSummary({ factoryId: factoryId.value }),
      getFinanceSummary({ factoryId: factoryId.value, topNStores: 5 }),
      getTrendBundle({ factoryId: factoryId.value }),
    ]);
    if (isUnmounted) return;
    kpiBoard.value = buildRestaurantKpiBoard(kpi, fin, bundle, canViewPrice.value);
    trendVm.value = goldTrendBundleToViewModel(bundle);
    await nextTick();
    if (!isUnmounted) renderTrendChart();
  } catch (e) {
    if (isUnmounted) return;
    // 禁降级假数据: 显式报错, 不伪造
    loadError.value = e instanceof Error ? e.message : '请求失败';
    kpiBoard.value = null;
    trendVm.value = null;
    console.error('[finance-pbi-gold] load failed:', e);
  } finally {
    if (!isUnmounted) loading.value = false;
  }
}

async function reload() {
  await Promise.all([probeRange(), loadGold()]);
}

// ==================== Chart Insight ====================
// Single LINE chart: monthly revenue trend (TREND family, domain='finance').
// trendVm.value?.revenue is the numeric array; categories are the x labels.
const financeTrendSource = (): { chart: ChartWithMeta } | null => {
  const vm = trendVm.value;
  if (!vm || vm.revenue.length < 4) return null;
  // Filter out null/undefined from revenue array (TrendBundleViewModel may have gaps)
  const values = vm.revenue.map((v) => (typeof v === 'number' && isFinite(v) ? v : 0));
  const nonZero = values.filter((v) => v !== 0);
  if (nonZero.length < 4) return null;
  return {
    chart: {
      chartType: 'LINE',
      meta: { xDim: 'time', yMetric: 'revenue', aggregation: 'sum', domain: 'finance' },
      config: {
        xAxis: { data: vm.categories },
        series: [{ type: 'line', data: values }],
      },
    },
  };
};

const financePerms = () => ({ canViewFinance: permissionStore.canViewPrice });

const { insight: financeTrendInsight, loading: financeTrendInsightLoading } = useChartInsight(
  financeTrendSource,
  financePerms,
  { factoryId: () => factoryId.value, autoTier2: true },
);

// factoryId 可能在挂载时尚未就绪 (auth 异步) → watch 重试一次。
watch(factoryId, (id, prev) => {
  if (id && id !== prev) void reload();
});

onMounted(() => {
  if (factoryId.value) void reload();
});

onBeforeUnmount(() => {
  isUnmounted = true;
  trendChart?.dispose();
  trendChart = null;
});
</script>

<style scoped>
.fpgv { padding: 4px 0; }

.fpgv-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 18px;
  margin-bottom: 16px;
  background: linear-gradient(135deg, #f8fafc 0%, #f0f5ff 100%);
  border: 1px solid #e4ebf5;
  border-radius: 10px;
}
.fpgv-header-info { display: flex; align-items: flex-start; gap: 12px; min-width: 0; }
.fpgv-header-icon { color: #409eff; font-size: 22px; flex-shrink: 0; margin-top: 2px; }
.fpgv-header-title { font-size: 14px; font-weight: 600; color: #303133; }
.fpgv-header-desc { font-size: 12px; color: #909399; margin-top: 4px; line-height: 1.6; }
.fpgv-header-actions { display: flex; gap: 8px; flex-shrink: 0; }

.fpgv-error {
  margin-bottom: 16px;
  padding: 12px 16px;
  color: #f56c6c;
  background: #fef0f0;
  border-radius: 8px;
}

.fpgv-kpi-board {
  margin-bottom: 16px;
  padding: 16px 18px;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 10px;
}
.fpgv-kpi-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 14px;
}
.fpgv-kpi-title .el-icon { color: #409eff; font-size: 18px; }
.fpgv-kpi-sub { margin-left: auto; font-size: 12px; font-weight: 400; color: #909399; }

.fpgv-kpi-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}
@media (max-width: 768px) { .fpgv-kpi-cards { grid-template-columns: repeat(2, 1fr); } }

.fpgv-kpi-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: #fafbfc;
  border-radius: 10px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}
.fpgv-kpi-card-icon {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: #ecf5ff;
  color: #409eff;
}
.fpgv-kpi-card-icon .el-icon { font-size: 20px; }
.fpgv-kpi-card-body { min-width: 0; }
.fpgv-kpi-card-value { font-size: 22px; font-weight: 700; color: #303133; line-height: 1.2; }
.fpgv-kpi-card-label { font-size: 13px; color: #606266; margin-top: 2px; }
.fpgv-kpi-card-hint { font-size: 11px; color: #a8abb2; margin-top: 2px; }

.fpgv-kpi-empty {
  margin-bottom: 16px;
  padding: 12px;
  background: #fafafa;
  border-radius: 8px;
}

.fpgv-trend-card { margin-bottom: 16px; }
.fpgv-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}
.fpgv-card-sub { font-size: 12px; font-weight: 400; color: #909399; }
.fpgv-trend-chart { width: 100%; height: 320px; }
</style>
