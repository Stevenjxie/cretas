<template>
  <!--
    运营分析 (2026-07-12) — 撤单稽核 + 区域坪效, moved OFF 经营驾驶舱(RestaurantGoldGrid)
    into its own top-level module (与 CRM 会员分析 同一批 hybrid bullet-point analysis
    pattern: 确定性 bullets + ChartInsights AI 解读 + 整页 AnalysisChatPanel)。
    数据源 gold.ts getVoidRate (撤单率 + 撤单稽核) / getZoneEfficiency (区域坪效)。
    业态门控: 路由 meta module='analytics' + roles + hideForFactoryTypes=['FACTORY']
    (见 router/modules/ops.ts), 与 CRM 完全一致的白名单, 仅餐饮/demo 租户可达。
    两次请求彻底隔离 (各自 loading/error) — 一个 500 不连累另一个。
  -->
  <div class="opa-page" data-test="ops-operations-analysis">
    <header class="opa-header">
      <div class="opa-title">
        <el-icon><Operation /></el-icon>
        <span>运营分析</span>
        <span class="opa-subtitle">{{ factoryId }} · 撤单稽核 · 区域坪效 · {{ dateLabel }}</span>
      </div>
      <el-button size="small" type="primary" plain :loading="voidLoading || zoneLoading" @click="loadAll">
        <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
      </el-button>
    </header>

    <!-- 总览条 -->
    <div class="opa-overview">
      <div class="opa-kpi">
        <span class="opa-kpi-val">{{ voidRateVal != null ? formatPct(voidRateVal) : '—' }}</span>
        <span class="opa-kpi-label">撤单率</span>
      </div>
      <div class="opa-kpi">
        <span class="opa-kpi-val">{{ voidCount.toLocaleString() }}</span>
        <span class="opa-kpi-label">撤单数</span>
      </div>
      <div class="opa-kpi">
        <span class="opa-kpi-val">{{ voidBillCount.toLocaleString() }}</span>
        <span class="opa-kpi-label">开单总数</span>
      </div>
      <div class="opa-kpi">
        <span class="opa-kpi-val">{{ formatMoney(zoneTotalRevenue) }}</span>
        <span class="opa-kpi-label">区域营收合计</span>
      </div>
      <div class="opa-kpi">
        <span class="opa-kpi-val">{{ zoneTotalItemQty.toLocaleString() }}</span>
        <span class="opa-kpi-label">区域销量合计</span>
      </div>
    </div>

    <div class="opa-layout">
      <div class="opa-main">
        <!-- 撤单稽核 -->
        <el-card class="opa-card opa-hero" shadow="never">
          <template #header>
            <div class="opa-card-header">
              <span class="opa-card-header-left"><el-icon><WarningFilled /></el-icon><span>撤单稽核</span></span>
              <el-button size="small" text @click="focusChart('void-audit')">🎯 聚焦</el-button>
            </div>
          </template>
          <el-skeleton v-if="voidLoading" :rows="6" animated />
          <div v-else-if="voidError" class="opa-inline-error">加载撤单数据失败: {{ voidError }}</div>
          <template v-else-if="voidDataAvailable && voidBreakdown.length">
            <div class="opa-void-grid">
              <div>
                <div class="opa-sub-title">撤单原因分布</div>
                <div ref="voidReasonChartRef" class="opa-chart" style="height: 260px"></div>
                <div class="opa-est-note">
                  <el-icon><InfoFilled /></el-icon>
                  <span>按操作人「主要撤单原因」加权撤单次数汇总的近似值（非逐笔撤单明细），未标注原因归入「未标注」</span>
                </div>
              </div>
              <div>
                <div class="opa-sub-title">操作人每百单撤单率</div>
                <div ref="voidStaffChartRef" class="opa-chart" style="height: 260px"></div>
              </div>
            </div>

            <!-- 撤单稽核明细表 (操作人 × 门店, 按每百单撤单率排序 — 避免冤枉授权量大的店长) -->
            <div class="opa-sub-title" style="margin-top: 16px">
              撤单稽核明细 · 操作人 Top {{ voidBreakdown.length }} (按每百单撤单率)
            </div>
            <el-table :data="voidBreakdown" size="small" stripe>
              <el-table-column prop="staffName" label="操作人" width="140" />
              <el-table-column prop="storeName" label="门店" min-width="140" show-overflow-tooltip />
              <el-table-column label="主要原因" width="130">
                <template #default="{ row }">
                  <span v-if="row.topReason">{{ row.topReason }}</span>
                  <span v-else class="opa-unlabeled">未标注</span>
                </template>
              </el-table-column>
              <el-table-column prop="voidCount" label="撤单次数" width="90" align="right" />
              <el-table-column label="每百单撤单" width="110" align="right">
                <template #default="{ row }">
                  <span v-if="row.voidsPer100Bills !== null">{{ formatPct(row.voidsPer100Bills) }}</span>
                  <span v-else class="opa-unlabeled" title="该操作人无经手订单记录，无法计算率">—</span>
                </template>
              </el-table-column>
            </el-table>
            <div class="opa-caveat-inline">{{ voidCaveat }}</div>

            <ChartInsights
              :bullets="voidBulletsComputed"
              chart-key="void-audit"
              chart-title="撤单稽核"
              :data-summary="voidSummaryStr"
              @focus="focusChart"
            />
          </template>
          <el-empty
            v-else
            :description="voidDataAvailable ? (voidNote || '订单样本不足，暂不计算撤单率') : '未上传撤单数据'"
            :image-size="60"
          />
        </el-card>

        <!-- 区域坪效 -->
        <el-card class="opa-card" shadow="never">
          <template #header>
            <div class="opa-card-header">
              <span class="opa-card-header-left"><el-icon><Grid /></el-icon><span>区域坪效</span></span>
              <el-button size="small" text @click="focusChart('zone-efficiency')">🎯 聚焦</el-button>
            </div>
          </template>
          <el-skeleton v-if="zoneLoading" :rows="6" animated />
          <div v-else-if="zoneError" class="opa-inline-error">加载区域数据失败: {{ zoneError }}</div>
          <template v-else-if="zoneDataAvailable && zones.length">
            <div class="opa-sub-title">区域营收{{ zoneHasRevenue ? '' : '（无价格权限，按销售数量展示）' }}</div>
            <div ref="zoneRevenueChartRef" class="opa-chart" style="height: 320px"></div>
            <!-- 诚实披露: (1) 营收/数量代理指标, 非真实元/平米坪效 (无场地面积字段);
                 (2) 部分 zoneName 是外卖等渠道标签, 非物理场地; (3) 数据无堂食/外卖
                 分区维度, 无法做堆叠对比 (源数据 zones 仅一维, 诚实不伪造第二维)。 -->
            <div class="opa-est-note">
              <el-icon><InfoFilled /></el-icon>
              <span>{{ zoneCaveat }}</span>
            </div>

            <ChartInsights
              :bullets="zoneBulletsComputed"
              chart-key="zone-efficiency"
              chart-title="区域坪效"
              :data-summary="zoneSummaryStr"
              @focus="focusChart"
            />
          </template>
          <el-empty
            v-else
            :description="zoneDataAvailable ? '暂无区域销售数据' : (zoneNote || '未上传区域销售数据')"
            :image-size="60"
          />
        </el-card>
      </div>

      <div class="opa-side">
        <AnalysisChatPanel ref="analysisChatPanelRef" :contexts="analysisContexts" page-title="运营分析" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Refresh, Operation, WarningFilled, Grid, InfoFilled } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import echarts from '@/utils/echarts';
import type { ECharts as EChartsInstance } from 'echarts/core';
import { getVoidRate, getZoneEfficiency, type VoidRate, type ZoneEfficiency } from '@/api/smartbi/gold';
import { getGoldDataRange } from '@/api/smartbi/dataRange';
import { resolveAllHistoryRange } from '@/views/smart-bi/analysisDefaults';
import { formatNumber } from '@/utils/format-number';
import ChartInsights from '@/views/smart-bi/components/analysis/ChartInsights.vue';
import AnalysisChatPanel from '@/views/smart-bi/components/analysis/AnalysisChatPanel.vue';
import {
  voidRateBullets,
  voidRateSummary,
  zoneEfficiencyBullets,
  zoneEfficiencySummary,
  type AnalysisChartContext,
} from '@/views/smart-bi/components/analysis/analysisBullets';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId || '');

function formatMoney(v: number | null | undefined): string {
  if (v == null) return '—';
  return '¥' + formatNumber(v);
}
// 禁降级: voidRate/revenuePct 为 null 时(未上传/RBAC 脱敏/样本不足) 诚实显示 "—",
// 绝不伪造 0%。
function formatPct(v: number | null | undefined): string {
  if (v == null) return '—';
  return v.toFixed(2) + '%';
}

// ============================================================
// 日期区间探测 (mirrors FinancePbiGoldView.vue) — getVoidRate/getZoneEfficiency
// 要求非空 startDate/endDate, 探测 Gold 真实数据窗, 绝不回退到"近30天"。
// ============================================================
const dateRange = ref<[string, string] | null>(null);
const dateLabel = computed(() => (dateRange.value ? `${dateRange.value[0]} 至 ${dateRange.value[1]}` : '全部数据'));

async function probeRange() {
  if (!factoryId.value) return;
  try {
    const dr = await getGoldDataRange(factoryId.value);
    dateRange.value = resolveAllHistoryRange(dr);
  } catch {
    // 探测失败 → 宽回退窗 (绝不回到近30天); 子请求自行处理空数据/报错, 不伪造。
    dateRange.value = resolveAllHistoryRange(null);
  }
}

// ============================================================
// 撤单稽核 (getVoidRate) — isolated fetch/loading/error, 与区域坪效彻底隔离
// (一个 500 不连累另一个卡 — fool-proof-design 跨规则铁律 + 历史教训)。
// ============================================================
const voidLoading = ref(false);
const voidError = ref('');
const voidDataAvailable = ref(false);
const voidNote = ref<string | null>(null);
const voidRateVal = ref<number | null>(null);
const voidCount = ref(0);
const voidBillCount = ref(0);
const voidBreakdown = ref<VoidRate['breakdown']>([]);
const voidCaveat = ref('');

async function loadVoid() {
  if (!factoryId.value || !dateRange.value) return;
  voidLoading.value = true;
  voidError.value = '';
  const [startDate, endDate] = dateRange.value;
  try {
    const res = await getVoidRate({ factoryId: factoryId.value, startDate, endDate, topN: 12 });
    voidDataAvailable.value = res.dataAvailable ?? false;
    voidNote.value = res.note ?? null;
    voidRateVal.value = res.voidRate ?? null;
    voidCount.value = res.voidCount ?? 0;
    voidBillCount.value = res.billCount ?? 0;
    voidBreakdown.value = res.breakdown ?? [];
    voidCaveat.value = res.caveat ?? '';
  } catch (e) {
    // 禁降级假数据: 失败显式报错, 不伪造
    voidError.value = e instanceof Error ? e.message : '撤单数据加载失败';
    voidDataAvailable.value = false;
    voidRateVal.value = null;
    voidBreakdown.value = [];
  } finally {
    voidLoading.value = false;
  }
}

// ============================================================
// 区域坪效 (getZoneEfficiency) — isolated fetch/loading/error
// ============================================================
const zoneLoading = ref(false);
const zoneError = ref('');
const zoneDataAvailable = ref(false);
const zoneNote = ref<string | null>(null);
const zoneTotalRevenue = ref<number | null>(null);
const zoneTotalItemQty = ref(0);
const zones = ref<ZoneEfficiency['zones']>([]);
const zoneCaveat = ref('');

async function loadZone() {
  if (!factoryId.value || !dateRange.value) return;
  zoneLoading.value = true;
  zoneError.value = '';
  const [startDate, endDate] = dateRange.value;
  try {
    const res = await getZoneEfficiency({ factoryId: factoryId.value, startDate, endDate, topN: 12 });
    zoneDataAvailable.value = res.dataAvailable ?? false;
    zoneNote.value = res.note ?? null;
    zoneTotalRevenue.value = res.totalRevenue ?? null;
    zoneTotalItemQty.value = res.totalItemQty ?? 0;
    zones.value = res.zones ?? [];
    zoneCaveat.value = res.caveat ?? '';
  } catch (e) {
    zoneError.value = e instanceof Error ? e.message : '区域数据加载失败';
    zoneDataAvailable.value = false;
    zones.value = [];
  } finally {
    zoneLoading.value = false;
  }
}

async function loadAll() {
  // dateRange 必须先探测好 (getVoidRate/getZoneEfficiency 的 startDate/endDate 非可选),
  // 探测完成后两个请求各自 try/catch 并行, 互不阻塞 (不用 Promise.all)。
  await probeRange();
  void loadVoid();
  void loadZone();
}

// ============================================================
// 混合式图表分析(bullets + AI 解读 + 整页 AI 分析面板)
// 2 个分析域 = 2 个 AnalysisChartContext (void-audit / zone-efficiency),
// 与 analysisBullets.ts 的 voidRateBullets/Summary + zoneEfficiencyBullets/Summary 一一对应。
// ============================================================
const voidBulletsInput = computed(() => ({
  voidRate: voidRateVal.value,
  voidCount: voidCount.value,
  billCount: voidBillCount.value,
  breakdown: voidBreakdown.value,
}));
const voidBulletsComputed = computed(() => voidRateBullets(voidBulletsInput.value));
const voidSummaryStr = computed(() => voidRateSummary(voidBulletsInput.value));

const zoneBulletsInput = computed(() => ({
  totalRevenue: zoneTotalRevenue.value,
  totalItemQty: zoneTotalItemQty.value,
  zones: zones.value,
}));
const zoneBulletsComputed = computed(() => zoneEfficiencyBullets(zoneBulletsInput.value));
const zoneSummaryStr = computed(() => zoneEfficiencySummary(zoneBulletsInput.value));

const analysisContexts = computed<AnalysisChartContext[]>(() => [
  { key: 'void-audit', title: '撤单稽核', dataSummary: voidSummaryStr.value },
  { key: 'zone-efficiency', title: '区域坪效', dataSummary: zoneSummaryStr.value },
]);

const analysisChatPanelRef = ref<InstanceType<typeof AnalysisChatPanel> | null>(null);
function focusChart(key: string) {
  analysisChatPanelRef.value?.setFocus(key);
}

// ============================================================
// ECharts — 撤单原因分布(饼) / 操作人每百单撤单率(横向柱) / 区域营收(横向柱)
// ============================================================
const voidReasonChartRef = ref<HTMLDivElement | null>(null);
const voidStaffChartRef = ref<HTMLDivElement | null>(null);
const zoneRevenueChartRef = ref<HTMLDivElement | null>(null);

let voidReasonChart: EChartsInstance | null = null;
let voidStaffChart: EChartsInstance | null = null;
let zoneRevenueChart: EChartsInstance | null = null;

function disposeChart(instance: EChartsInstance | null): null {
  if (instance && !instance.isDisposed?.()) instance.dispose();
  return null;
}

function handleChartsResize() {
  voidReasonChart?.resize();
  voidStaffChart?.resize();
  zoneRevenueChart?.resize();
}

// 撤单原因分布: 按操作人「主要撤单原因」加权撤单次数汇总的近似值 (breakdown 只给逐人
// 主要原因, 非逐笔撤单明细) — 未标注原因归入「未标注」, 真实 voidCount 数字不伪造。
const voidReasonDistribution = computed(() => {
  const counts = new Map<string, number>();
  for (const row of voidBreakdown.value) {
    if (row.voidCount <= 0) continue;
    const reason = row.topReason ?? '未标注';
    counts.set(reason, (counts.get(reason) ?? 0) + row.voidCount);
  }
  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1])
    .map(([name, value]) => ({ name, value }));
});

const voidStaffRanked = computed(() =>
  [...voidBreakdown.value]
    .filter((b) => b.voidsPer100Bills != null)
    .sort((a, b) => (b.voidsPer100Bills as number) - (a.voidsPer100Bills as number)),
);

// zone.revenue 是全体一致 RBAC 置空 (无价格权限角色) 或全体非空, 不会部分置空
// (见 gold.ts ZoneEfficiency 注释) — 用第一个 zone 判断即可决定是否降级展示数量。
const zoneHasRevenue = computed(() => zones.value.some((z) => z.revenue != null));

function renderVoidReasonChart() {
  if (!voidReasonChartRef.value || !voidReasonDistribution.value.length) return;
  voidReasonChart = disposeChart(voidReasonChart);
  voidReasonChart = echarts.init(voidReasonChartRef.value, 'cretas');
  voidReasonChart.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c}单 ({d}%)' },
    legend: { orient: 'vertical', right: 10, top: 'center', textStyle: { fontSize: 12 } },
    series: [
      {
        type: 'pie',
        radius: ['40%', '68%'],
        center: ['38%', '50%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
        label: { show: true, formatter: '{b}: {c}', fontSize: 11 },
        data: voidReasonDistribution.value,
      },
    ],
  });
}

function renderVoidStaffChart() {
  if (!voidStaffChartRef.value || !voidStaffRanked.value.length) return;
  voidStaffChart = disposeChart(voidStaffChart);
  voidStaffChart = echarts.init(voidStaffChartRef.value, 'cretas');
  const rows = voidStaffRanked.value;
  const labels = rows.map((r) => `${r.staffName}·${r.storeName}`);
  const rates = rows.map((r) => r.voidsPer100Bills);

  voidStaffChart.setOption({
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: unknown) => {
        const p = Array.isArray(params) ? params[0] : params;
        const idx = (p as { dataIndex: number }).dataIndex;
        const row = rows[idx];
        const reasonLine = row.topReason ? `<br/>主要原因: ${row.topReason}` : '';
        return `<strong>${row.staffName}（${row.storeName}）</strong><br/>每百单撤单: ${formatPct(row.voidsPer100Bills)}<br/>撤单次数: ${row.voidCount}${reasonLine}`;
      },
    },
    grid: { left: 150, right: 30, top: 10, bottom: 20, containLabel: true },
    xAxis: { type: 'value' },
    yAxis: { type: 'category', data: labels, inverse: true },
    series: [
      {
        type: 'bar',
        data: rates,
        barMaxWidth: 22,
        itemStyle: { color: '#e6a23c' },
        label: {
          show: true,
          position: 'right',
          formatter: (p: { value: number | null }) => (p.value != null ? p.value.toFixed(2) : '—'),
        },
      },
    ],
  });
}

function renderZoneRevenueChart() {
  if (!zoneRevenueChartRef.value || !zones.value.length) return;
  zoneRevenueChart = disposeChart(zoneRevenueChart);
  zoneRevenueChart = echarts.init(zoneRevenueChartRef.value, 'cretas');
  const hasRevenue = zoneHasRevenue.value;
  const rows = [...zones.value].sort((a, b) => {
    if (hasRevenue) return (b.revenue as number) - (a.revenue as number);
    return b.itemQty - a.itemQty;
  });
  const labels = rows.map((z) => z.zoneName);

  zoneRevenueChart.setOption({
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: unknown) => {
        const p = Array.isArray(params) ? params[0] : params;
        const idx = (p as { dataIndex: number }).dataIndex;
        const z = rows[idx];
        return `<strong>${z.zoneName}</strong><br/>营收: ${formatMoney(z.revenue)}<br/>数量: ${z.itemQty.toLocaleString()}件`;
      },
    },
    grid: { left: 130, right: 30, top: 10, bottom: 20, containLabel: true },
    xAxis: { type: 'value' },
    yAxis: { type: 'category', data: labels, inverse: true },
    series: [
      {
        type: 'bar',
        data: hasRevenue ? rows.map((z) => z.revenue) : rows.map((z) => z.itemQty),
        barMaxWidth: 28,
        itemStyle: { color: '#9a60b4' },
        label: {
          show: true,
          position: 'right',
          formatter: (p: { value: number }) => (hasRevenue ? formatMoney(p.value) : `${p.value}件`),
        },
      },
    ],
  });
}

watch(voidReasonDistribution, () => nextTick(renderVoidReasonChart));
watch(voidStaffRanked, () => nextTick(renderVoidStaffChart));
watch(zones, () => nextTick(renderZoneRevenueChart));

onMounted(() => {
  void loadAll();
  window.addEventListener('resize', handleChartsResize);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleChartsResize);
  voidReasonChart = disposeChart(voidReasonChart);
  voidStaffChart = disposeChart(voidStaffChart);
  zoneRevenueChart = disposeChart(zoneRevenueChart);
});
</script>

<style scoped>
.opa-page { padding: 16px; }
.opa-header { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 16px; flex-wrap: wrap; }
.opa-title { display: flex; align-items: center; gap: 8px; font-size: 18px; font-weight: 600; color: #303133; }
.opa-subtitle { font-size: 12px; font-weight: 400; color: #909399; margin-left: 4px; }

.opa-overview {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}
@media (max-width: 1200px) { .opa-overview { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 768px) { .opa-overview { grid-template-columns: repeat(2, 1fr); } }
.opa-kpi {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 14px 8px;
  background: #f7f8fa;
  border-radius: 8px;
}
.opa-kpi-val { font-size: 22px; font-weight: 700; color: #303133; }
.opa-kpi-label { font-size: 12px; color: #909399; }

/* 主列(图表卡片) + 侧列(AI 分析面板) 响应式布局 (mirrors CRM member-analysis) */
.opa-layout { display: grid; grid-template-columns: minmax(0, 1fr) 380px; gap: 16px; align-items: start; }
@media (max-width: 1280px) { .opa-layout { grid-template-columns: 1fr; } }
.opa-main { min-width: 0; }
.opa-side { position: sticky; top: 16px; }
@media (max-width: 1280px) { .opa-side { position: static; } }

.opa-card { margin-bottom: 16px; }
.opa-hero { min-height: 120px; }
.opa-card-header { display: flex; align-items: center; justify-content: space-between; gap: 6px; font-weight: 600; }
.opa-card-header-left { display: flex; align-items: center; gap: 6px; }
.opa-chart { width: 100%; }
.opa-inline-error { color: #f56c6c; font-size: 13px; padding: 8px 0; }
.opa-sub-title { font-size: 13px; font-weight: 600; color: #303133; margin-bottom: 8px; }

.opa-void-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
@media (max-width: 992px) { .opa-void-grid { grid-template-columns: 1fr; } }

.opa-unlabeled { color: #909399; }
.opa-caveat-inline { margin-top: 10px; font-size: 12px; color: #909399; line-height: 1.5; }
.opa-est-note {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 10px;
  padding: 6px 10px;
  font-size: 12px;
  line-height: 1.5;
  color: #909399;
  background: #f4f4f5;
  border-radius: 4px;
}
</style>
