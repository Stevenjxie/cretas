<script setup lang="ts">
/**
 * SP9-M5: 人效看板 — 达成率趋势 + 工序对比图表
 *
 * 后端:
 *   GET /{factoryId}/labor-efficiency/achievement-summary?startDate=&endDate=&productTypeId=
 *
 * @PriceSensitive: 达成率 % 与工时(分钟/人次) 是工作量指标，不脱敏。
 * 成本绝对值不在本页展示。
 */
import { ref, computed, onMounted, watch, nextTick } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage } from 'element-plus';
import { Search } from '@element-plus/icons-vue';
import {
  getLaborAchievementSummary,
  getLaborStepBreakdown,
  type LaborAchievementSummaryDTO,
  type LaborStepBreakdownDTO,
  type LaborAchievementAlert,
} from '@/api/laborEfficiency';
import { get } from '@/api/request';

// Lazy-load ECharts to keep bundle small
let echarts: typeof import('echarts') | null = null;
async function loadECharts() {
  if (!echarts) {
    echarts = await import('echarts');
  }
  return echarts;
}

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

// ============================================================================
// 产品类型选项
// ============================================================================

interface ProductTypeOption {
  id: string;
  name: string;
}
const productTypes = ref<ProductTypeOption[]>([]);

async function loadProductTypes() {
  if (!factoryId.value) return;
  try {
    const res = await get<ProductTypeOption[]>(`/${factoryId.value}/product-types/active`);
    if (res.success && res.data) productTypes.value = res.data;
  } catch {
    // non-blocking
  }
}

// ============================================================================
// 查询条件
// ============================================================================

// 默认近30天 — 用 sv-SE locale 得到本地时区的 YYYY-MM-DD，避免 UTC 偏移在 UTC+8 凌晨给出昨天的日期
function defaultDateRange(): [string, string] {
  const end = new Date();
  const start = new Date();
  start.setDate(start.getDate() - 30);
  const fmt = (d: Date) => d.toLocaleDateString('sv-SE'); // YYYY-MM-DD in local timezone
  return [fmt(start), fmt(end)];
}

const [defaultStart, defaultEnd] = defaultDateRange();
const dateRange = ref<[string, string]>([defaultStart, defaultEnd]);
const selectedProductTypeId = ref<string | null>(null);

// ============================================================================
// 数据
// ============================================================================

const summaryList = ref<LaborAchievementSummaryDTO[]>([]);
const loading = ref(false);

async function loadData() {
  if (!factoryId.value) return;
  if (!dateRange.value?.[0] || !dateRange.value?.[1]) {
    ElMessage({ message: '请选择日期范围', type: 'warning', duration: 3000 });
    return;
  }
  loading.value = true;
  try {
    const res = await getLaborAchievementSummary(
      factoryId.value,
      dateRange.value[0],
      dateRange.value[1],
      selectedProductTypeId.value || null,
    );
    if (res.success && res.data) {
      summaryList.value = res.data;
      await nextTick();
      renderCharts();
    } else {
      ElMessage({ message: res.message || '加载失败', type: 'error', duration: 0, showClose: true });
    }
  } catch {
    ElMessage({ message: '网络错误，请重试', type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}

// ============================================================================
// ECharts 渲染
// ============================================================================

const achievementChartRef = ref<HTMLElement | null>(null);
const batchTrendChartRef = ref<HTMLElement | null>(null);

// Product summary rows (exclude the "全部产品" overall row for per-product chart)
const perProductRows = computed(() =>
  summaryList.value.filter(r => r.productTypeId !== null),
);

async function renderCharts() {
  const ec = await loadECharts();

  // Chart 1: Per-product achievement rate bar chart
  if (achievementChartRef.value && perProductRows.value.length > 0) {
    const chart = ec.init(achievementChartRef.value);
    const names = perProductRows.value.map(r => r.productName);
    const rates = perProductRows.value.map(r => r.achievementRatePct ?? null);
    const colors = perProductRows.value.map(r => alertColor(r.achievementAlert));

    chart.setOption({
      title: { text: '各产品达成率对比', left: 0, textStyle: { fontSize: 14, color: '#303133' } },
      tooltip: {
        trigger: 'axis',
        formatter: (params: any) => {
          const p = params[0];
          return `${p.name}<br/>达成率: ${p.value != null ? p.value.toFixed(2) + '%' : '未配置'}`;
        },
      },
      grid: { left: 40, right: 20, bottom: 40, top: 50, containLabel: true },
      xAxis: { type: 'category', data: names, axisLabel: { rotate: 30, fontSize: 12 } },
      yAxis: {
        type: 'value',
        name: '达成率 %',
        axisLabel: { formatter: '{value}%' },
        splitLine: { lineStyle: { type: 'dashed' } },
      },
      series: [
        {
          type: 'bar',
          data: rates.map((v, i) => ({
            value: v,
            itemStyle: { color: colors[i] },
          })),
          label: {
            show: true,
            position: 'top',
            formatter: (p: any) => (p.value != null ? p.value.toFixed(1) + '%' : '—'),
          },
          barMaxWidth: 60,
        },
      ],
      // Reference lines: 75% (BELOW threshold) and 150% (ABOVE threshold)
      markLine: {
        data: [{ yAxis: 75, name: '低效预警 75%' }, { yAxis: 150, name: '超时预警 150%' }],
      },
    });
  }

  // Chart 2: Batch-level trend for the first summary row (or overall row)
  const trendSource = summaryList.value.find(r => r.productTypeId === null) ?? summaryList.value[0];
  if (batchTrendChartRef.value && trendSource?.batchItems?.length) {
    const chart = ec.init(batchTrendChartRef.value);
    const items = trendSource.batchItems.slice(0, 30); // cap display at 30 batches
    const batchNums = items.map(b => b.batchNumber);
    const rates = items.map(b => b.achievementRatePct ?? null);

    chart.setOption({
      title: {
        text: `批次达成率趋势 — ${trendSource.productName}`,
        left: 0,
        textStyle: { fontSize: 14, color: '#303133' },
      },
      tooltip: {
        trigger: 'axis',
        formatter: (params: any) => {
          const p = params[0];
          return `${p.name}<br/>达成率: ${p.value != null ? p.value.toFixed(2) + '%' : '未配置'}`;
        },
      },
      grid: { left: 40, right: 20, bottom: 50, top: 50, containLabel: true },
      xAxis: {
        type: 'category',
        data: batchNums,
        axisLabel: { rotate: 45, fontSize: 10 },
      },
      yAxis: {
        type: 'value',
        name: '达成率 %',
        axisLabel: { formatter: '{value}%' },
        splitLine: { lineStyle: { type: 'dashed' } },
      },
      series: [
        {
          type: 'line',
          data: rates,
          smooth: false,
          lineStyle: { color: '#5470c6' },
          itemStyle: { color: '#5470c6' },
          symbol: 'circle',
          symbolSize: 6,
          label: {
            show: rates.length <= 10,
            formatter: (p: any) => (p.value != null ? p.value.toFixed(1) + '%' : ''),
          },
          markLine: {
            data: [
              { yAxis: 75, lineStyle: { color: '#e6a23c', type: 'dashed' } },
              { yAxis: 150, lineStyle: { color: '#f56c6c', type: 'dashed' } },
            ],
            label: { formatter: (p: any) => p.yAxis + '%' },
          },
        },
      ],
    });
  }
}

// ============================================================================
// Step-breakdown 抽屉
// ============================================================================

const stepDrawerVisible = ref(false);
const stepBreakdownLoading = ref(false);
const currentStepBreakdown = ref<LaborStepBreakdownDTO | null>(null);
const stepChartRef = ref<HTMLElement | null>(null);

async function openStepBreakdown(batchId: number, batchNumber: string) {
  if (!factoryId.value) return;
  stepDrawerVisible.value = true;
  stepBreakdownLoading.value = true;
  currentStepBreakdown.value = null;
  try {
    const res = await getLaborStepBreakdown(factoryId.value, batchId);
    if (res.success && res.data) {
      currentStepBreakdown.value = res.data;
      await nextTick();
      renderStepChart();
    } else {
      ElMessage({ message: res.message || '加载失败', type: 'error', duration: 0, showClose: true });
    }
  } catch {
    ElMessage({ message: '网络错误', type: 'error', duration: 0, showClose: true });
  } finally {
    stepBreakdownLoading.value = false;
  }
}

async function renderStepChart() {
  if (!stepChartRef.value || !currentStepBreakdown.value?.steps?.length) return;
  const ec = await loadECharts();
  const chart = ec.init(stepChartRef.value);
  const steps = currentStepBreakdown.value.steps;
  const names = steps.map(s => s.processName);
  const actual = steps.map(s => s.actualWorkMinutes ?? null);
  const planned = steps.map(s => s.plannedWorkMinutes ?? null);

  chart.setOption({
    title: { text: '逐工序工时对比', left: 0, textStyle: { fontSize: 13 } },
    legend: { right: 0, top: 0 },
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, bottom: 40, top: 50, containLabel: true },
    xAxis: { type: 'category', data: names },
    yAxis: { type: 'value', name: '工时(分钟)', axisLabel: { formatter: '{value}min' } },
    series: [
      {
        name: '实际工时',
        type: 'bar',
        data: actual,
        itemStyle: { color: '#5470c6' },
        label: { show: true, position: 'top', formatter: (p: any) => p.value != null ? p.value + 'm' : '—' },
        barMaxWidth: 40,
      },
      {
        name: '计划工时',
        type: 'bar',
        data: planned,
        itemStyle: { color: '#91cc75' },
        label: { show: true, position: 'top', formatter: (p: any) => p.value != null ? p.value + 'm' : '—' },
        barMaxWidth: 40,
      },
    ],
  });
}

// ============================================================================
// 格式化工具
// ============================================================================

function fmtMin(minutes: number | null | undefined): string {
  if (minutes == null) return '—';
  if (minutes < 60) return `${minutes} 分`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m > 0 ? `${h}h${m}m` : `${h}h`;
}

function fmtPct(v: number | null | undefined): string {
  if (v == null) return '—';
  return `${v.toFixed(2)}%`;
}

function alertTagType(alert: LaborAchievementAlert | null): string {
  if (alert === 'ABOVE_ALERT') return 'danger';
  if (alert === 'BELOW_ALERT') return 'warning';
  if (alert === 'OK') return 'success';
  return 'info';
}

function alertLabel(alert: LaborAchievementAlert | null): string {
  if (alert === 'ABOVE_ALERT') return '超时预警';
  if (alert === 'BELOW_ALERT') return '低效预警';
  if (alert === 'OK') return '正常';
  return '—';
}

function alertColor(alert: LaborAchievementAlert | null | undefined): string {
  if (alert === 'ABOVE_ALERT') return '#f56c6c';
  if (alert === 'BELOW_ALERT') return '#e6a23c';
  return '#67c23a';
}

function achievementRateClass(rate: number | null, alert: LaborAchievementAlert | null): string {
  if (rate == null) return 'text-muted';
  if (alert === 'ABOVE_ALERT') return 'text-critical';
  if (alert === 'BELOW_ALERT') return 'text-warning';
  return 'text-normal';
}

// ============================================================================
// 初始化
// ============================================================================

onMounted(async () => {
  await loadProductTypes();
  await loadData();
});
</script>

<template>
  <div class="labor-dashboard-page">
    <div class="page-header">
      <h2 class="title">人效看板</h2>
      <span class="subtitle">达成率趋势 · 工序对比</span>
    </div>

    <el-alert
      type="info"
      :closable="false"
      title="达成率 = 实际工时 / 计划工时 × 100%。计划工时取自工序配置 estimatedMinutes；未配置时显示 —。成本字段对非财务角色不可见。"
      style="margin-bottom: 16px"
    />

    <!-- 查询条件 -->
    <el-card shadow="never" class="filter-card">
      <el-form inline>
        <el-form-item label="日期范围">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            style="width: 260px"
          />
        </el-form-item>
        <el-form-item label="产品">
          <el-select
            v-model="selectedProductTypeId"
            clearable
            placeholder="全部产品"
            style="width: 200px"
          >
            <el-option
              v-for="pt in productTypes"
              :key="pt.id"
              :label="pt.name"
              :value="pt.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" :loading="loading" @click="loadData">
            查询
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 汇总卡片 -->
    <el-row :gutter="12" v-loading="loading" style="margin-bottom: 16px">
      <el-col
        v-for="row in summaryList"
        :key="row.productTypeId ?? 'overall'"
        :span="summaryList.length <= 2 ? 12 : summaryList.length <= 3 ? 8 : 6"
        :xs="24"
        :sm="12"
      >
        <el-card
          shadow="hover"
          class="summary-card"
          :class="{
            'card-critical': row.achievementAlert === 'ABOVE_ALERT',
            'card-warning': row.achievementAlert === 'BELOW_ALERT',
            'card-ok': row.achievementAlert === 'OK',
          }"
        >
          <div class="card-title">{{ row.productName }}</div>
          <div
            class="card-rate"
            :class="achievementRateClass(row.achievementRatePct, row.achievementAlert)"
          >
            {{ fmtPct(row.achievementRatePct) }}
          </div>
          <div class="card-meta">
            <span>实际: {{ fmtMin(row.totalActualWorkMinutes) }}</span>
            <span class="divider">|</span>
            <span>计划: {{ fmtMin(row.totalPlannedWorkMinutes) }}</span>
            <span class="divider">|</span>
            <span>{{ row.batchCount }} 批</span>
          </div>
          <div class="card-alert">
            <el-tag v-if="row.achievementAlert" :type="alertTagType(row.achievementAlert)" size="small">
              {{ alertLabel(row.achievementAlert) }}
            </el-tag>
            <span v-else class="text-muted" style="font-size: 12px">计划工时未配置</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ECharts: 产品达成率对比 + 批次趋势 -->
    <el-row :gutter="16" v-if="summaryList.length > 0 && !loading">
      <el-col :span="12" :xs="24">
        <el-card shadow="never" class="chart-card">
          <div ref="achievementChartRef" style="height: 300px" />
        </el-card>
      </el-col>
      <el-col :span="12" :xs="24">
        <el-card shadow="never" class="chart-card">
          <div ref="batchTrendChartRef" style="height: 300px" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 批次明细表 -->
    <el-card shadow="never" v-loading="loading" class="data-card">
      <template #header>
        <span>批次达成率明细</span>
      </template>
      <template v-for="summary in summaryList" :key="summary.productTypeId ?? 'overall'">
        <div v-if="summary.batchItems && summary.batchItems.length > 0">
          <div class="product-group-header">{{ summary.productName }}</div>
          <el-table
            :data="summary.batchItems"
            size="small"
            stripe
            style="margin-bottom: 16px"
          >
            <el-table-column prop="batchNumber" label="批次号" min-width="160" />
            <el-table-column prop="productName" label="产品" min-width="120" />
            <el-table-column label="实际工时" align="right" min-width="100">
              <template #default="{ row }">{{ fmtMin(row.actualWorkMinutes) }}</template>
            </el-table-column>
            <el-table-column label="计划工时" align="right" min-width="100">
              <template #default="{ row }">{{ fmtMin(row.plannedWorkMinutes) }}</template>
            </el-table-column>
            <el-table-column label="达成率" align="right" min-width="100">
              <template #default="{ row }">
                <span :class="achievementRateClass(row.achievementRatePct, row.achievementAlert)">
                  {{ fmtPct(row.achievementRatePct) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="状态" align="center" min-width="100">
              <template #default="{ row }">
                <el-tag v-if="row.achievementAlert" :type="alertTagType(row.achievementAlert)" size="small">
                  {{ alertLabel(row.achievementAlert) }}
                </el-tag>
                <span v-else class="text-muted">—</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>
      <el-empty
        v-if="!loading && summaryList.length === 0"
        description="暂无数据 — 请调整日期范围后重新查询"
      />
    </el-card>

    <!-- Step-breakdown 抽屉 -->
    <el-drawer
      v-model="stepDrawerVisible"
      title="逐工序人效拆分"
      size="640px"
      direction="rtl"
    >
      <div v-loading="stepBreakdownLoading">
        <template v-if="currentStepBreakdown">
          <div class="drawer-subtitle">
            批次: {{ currentStepBreakdown.batchNumber }} — {{ currentStepBreakdown.productName }}
          </div>
          <el-descriptions :column="3" size="small" border style="margin-bottom: 16px">
            <el-descriptions-item label="实际总工时">
              {{ fmtMin(currentStepBreakdown.totalActualWorkMinutes) }}
            </el-descriptions-item>
            <el-descriptions-item label="计划总工时">
              {{ fmtMin(currentStepBreakdown.totalPlannedWorkMinutes) }}
            </el-descriptions-item>
            <el-descriptions-item label="整体达成率">
              <span :class="achievementRateClass(currentStepBreakdown.overallAchievementRatePct, currentStepBreakdown.overallAchievementAlert)">
                {{ fmtPct(currentStepBreakdown.overallAchievementRatePct) }}
              </span>
            </el-descriptions-item>
          </el-descriptions>

          <!-- Step 工时对比图 -->
          <div ref="stepChartRef" style="height: 220px; margin-bottom: 16px" />

          <!-- Step 明细表 -->
          <el-table :data="currentStepBreakdown.steps" size="small" border>
            <el-table-column prop="processName" label="工序" min-width="120" />
            <el-table-column label="实际工时" align="right" min-width="90">
              <template #default="{ row }">{{ fmtMin(row.actualWorkMinutes) }}</template>
            </el-table-column>
            <el-table-column label="人次" align="right" min-width="70">
              <template #default="{ row }">{{ row.actualWorkers ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="计划工时" align="right" min-width="90">
              <template #default="{ row }">{{ fmtMin(row.plannedWorkMinutes) }}</template>
            </el-table-column>
            <el-table-column label="达成率" align="right" min-width="90">
              <template #default="{ row }">
                <span :class="achievementRateClass(row.achievementRatePct, row.achievementAlert)">
                  {{ fmtPct(row.achievementRatePct) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="状态" align="center" min-width="90">
              <template #default="{ row }">
                <el-tag v-if="row.achievementAlert" :type="alertTagType(row.achievementAlert)" size="small">
                  {{ alertLabel(row.achievementAlert) }}
                </el-tag>
                <span v-else class="text-muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="工序人工成本" align="right" min-width="120">
              <template #default="{ row }">
                {{ row.laborCost != null ? `¥${row.laborCost.toFixed(2)}` : '—' }}
              </template>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.labor-dashboard-page {
  padding: 20px;
}
.page-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 16px;
}
.page-header .title {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
  color: #303133;
}
.page-header .subtitle {
  font-size: 13px;
  color: #909399;
}
.filter-card {
  margin-bottom: 16px;
}
.chart-card {
  margin-bottom: 16px;
}
.data-card {
  margin-bottom: 16px;
}
.summary-card {
  margin-bottom: 12px;
}
.card-title {
  font-size: 14px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 8px;
}
.card-rate {
  font-size: 28px;
  font-weight: 700;
  margin-bottom: 6px;
}
.card-meta {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}
.card-meta .divider {
  margin: 0 6px;
  color: #dcdfe6;
}
.card-alert {
  min-height: 22px;
}
.product-group-header {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  padding: 4px 0 8px;
  border-bottom: 1px solid #ebeef5;
  margin-bottom: 8px;
}
.drawer-subtitle {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 12px;
}
.text-critical {
  color: #f56c6c;
  font-weight: 600;
}
.text-warning {
  color: #e6a23c;
  font-weight: 600;
}
.text-normal {
  color: #67c23a;
}
.text-muted {
  color: #c0c4cc;
}
</style>
