<script setup lang="ts">
/**
 * G2 餐饮目标拆分 + 达成率预警 — TargetHierarchyEditor
 *
 * Rule 1: 实时显示月均预览; data_missing 不显 0%
 * Rule 2: 标题带 factory / year / kpiKind 上下文
 * Rule 3: 调整原因 el-select; 选"其他"才显 textarea
 * Rule 4: POST 幂等 upsert; saving=true 防双击
 * Rule 5: 保存成功后自动跳 /analytics/kpi
 */
import { ref, computed, onMounted, nextTick } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue';
import { useChartInsight } from '@/composables/useChartInsight';
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight';
import { ElMessage, ElMessageBox } from 'element-plus';
import echarts from '@/utils/echarts';
import {
  upsertTarget,
  decomposeWeeks,
  fetchForecast,
  fetchPaceAlerts,
  fetchMarginForecast,
  fetchMarginPaceAlert,
  getMarginRate,
  setMarginRate,
  type TargetUpsertRequest,
  type ForecastResponse,
  type PaceAlertResponse,
  type DecomposeWeeksResponse,
  type MarginForecastResponse,
  type MarginPaceAlertResponse,
} from '@/api/smartbi/restaurant-targets';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const canViewPrice = computed(() => permissionStore.canViewPrice);
const factoryId = computed(() => authStore.factoryId ?? '');

// ── #58 Phase 1 state ───────────────────────────────────────────────────────
const activeTab = ref<'targets' | 'cascade' | 'forecast'>('targets');

// 周/日拆分
const decomposeMonth = ref(
  `${new Date().getFullYear()}-${String(new Date().getMonth() + 1).padStart(2, '0')}`,
);
const cascadeToDays = ref(false);
const decomposing = ref(false);
const decomposeResult = ref<DecomposeWeeksResponse | null>(null);

// 滚动预测 + pace 预警
const forecastHorizon = ref(30);
const forecastLoading = ref(false);
const forecastData = ref<ForecastResponse | null>(null);
const paceLoading = ref(false);
const pacePeriod = ref<'month' | 'week' | 'day'>('month');
const paceAlert = ref<PaceAlertResponse | null>(null);

// #58 P2 毛利 (margin) state
const marginForecast = ref<MarginForecastResponse | null>(null);
const marginPace = ref<MarginPaceAlertResponse | null>(null);
const marginRate = ref<number>(0.55);
const marginRateSaving = ref(false);

const PACE_LEVEL_META: Record<string, { text: string; color: string; tag: string }> = {
  OK: { text: '进度正常', color: '#67c23a', tag: 'success' },
  WARN: { text: '略微落后', color: '#e6a23c', tag: 'warning' },
  CRIT: { text: '明显落后', color: '#f56c6c', tag: 'danger' },
  NO_TARGET: { text: '目标未设置', color: '#909399', tag: 'info' },
  COST_DATA_INSUFFICIENT: { text: '成本数据不足', color: '#909399', tag: 'info' },
};

const marginPaceMeta = computed(() => {
  const lvl = marginPace.value?.alertLevel ?? 'NO_TARGET';
  return PACE_LEVEL_META[lvl] ?? PACE_LEVEL_META.NO_TARGET;
});

const paceMeta = computed(() => {
  const lvl = paceAlert.value?.alertLevel ?? 'NO_TARGET';
  return PACE_LEVEL_META[lvl] ?? PACE_LEVEL_META.NO_TARGET;
});

function fmtMoney(v: number | null | undefined): string {
  if (v == null) return '—';
  return `¥${Math.round(v).toLocaleString()}`;
}

async function runDecompose() {
  if (!factoryId.value) return;
  decomposing.value = true;
  try {
    const res = await decomposeWeeks({
      periodKey: decomposeMonth.value,
      kpiKind: kpiKind.value,
      cascadeToDays: cascadeToDays.value,
    });
    decomposeResult.value = res.data;
    if (res.data.weeks.length === 0) {
      ElMessage({
        message: res.data.message || '该月目标未设置，无法拆分',
        type: 'warning', duration: 0, showClose: true,
      });
    } else {
      ElMessage({ message: `已拆分为 ${res.data.weeks.length} 个周目标`, type: 'success' });
    }
  } catch (e: unknown) {
    const msg = (e instanceof Error ? e.message : String(e)) || '拆分失败';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    decomposing.value = false;
  }
}

async function loadForecast() {
  if (!factoryId.value) return;
  forecastLoading.value = true;
  try {
    const res = await fetchForecast({ horizonDays: forecastHorizon.value });
    forecastData.value = res.data;
    await nextTick();
    renderForecastChart();
  } catch (e: unknown) {
    const msg = (e instanceof Error ? e.message : String(e)) || '预测失败';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    forecastLoading.value = false;
  }
  // margin forecast overlays the same chart (separate request so revenue is never blocked)
  void loadMarginForecast();
}

async function loadPaceAlert() {
  if (!factoryId.value) return;
  paceLoading.value = true;
  try {
    const res = await fetchPaceAlerts({ periodType: pacePeriod.value, kpiKind: kpiKind.value });
    paceAlert.value = res.data;
  } catch (e: unknown) {
    const msg = (e instanceof Error ? e.message : String(e)) || '查询进度失败';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    paceLoading.value = false;
  }
  // margin pace shares the period selector
  void loadMarginPace();
}

// #58 P2 — 毛利预测 + 毛利 pace 预警
async function loadMarginForecast() {
  if (!factoryId.value) return;
  try {
    const res = await fetchMarginForecast({ horizonDays: forecastHorizon.value });
    marginForecast.value = res.data;
    await nextTick();
    renderForecastChart();
  } catch (e: unknown) {
    // margin failure must not break the revenue forecast; surface softly
    const msg = (e instanceof Error ? e.message : String(e)) || '毛利预测失败';
    ElMessage({ message: msg, type: 'warning', duration: 0, showClose: true });
  }
}

async function loadMarginPace() {
  if (!factoryId.value) return;
  try {
    const res = await fetchMarginPaceAlert({ periodType: pacePeriod.value });
    marginPace.value = res.data;
  } catch (e: unknown) {
    const msg = (e instanceof Error ? e.message : String(e)) || '查询毛利进度失败';
    ElMessage({ message: msg, type: 'warning', duration: 0, showClose: true });
  }
}

async function loadMarginRate() {
  if (!factoryId.value) return;
  try {
    const res = await getMarginRate();
    marginRate.value = res.data.targetMarginRate;
  } catch {
    marginRate.value = 0.55; // honest default; never blocks the page
  }
}

async function saveMarginRate() {
  if (!factoryId.value || !canViewPrice.value) return;
  marginRateSaving.value = true;
  try {
    const res = await setMarginRate(marginRate.value, null);
    marginRate.value = res.data.targetMarginRate;
    ElMessage({ message: '目标毛利率已更新', type: 'success' });
    void loadMarginPace(); // recompute pace against the new target
  } catch (e: unknown) {
    const msg = (e instanceof Error ? e.message : String(e)) || '保存失败';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    marginRateSaving.value = false;
  }
}

const marginInsufficient = computed(
  () => marginForecast.value != null && marginForecast.value.costDataSufficient === false,
);

function renderForecastChart() {
  const el = document.getElementById('chart-forecast');
  const data = forecastData.value;
  if (!el || !data || data.points.length === 0) return;
  const chart = echarts.getInstanceByDom(el) || echarts.init(el);
  const dates = data.points.map(p => p.date);
  // forecast/lower/upper may be null (RBAC-stripped); guard with ?? null.
  const fc = data.points.map(p => p.forecastAmount);
  const lo = data.points.map(p => p.lowerBound);
  // upper is rendered as a stacked band on top of lower (upper - lower).
  const band = data.points.map(p =>
    p.upperBound != null && p.lowerBound != null ? p.upperBound - p.lowerBound : null,
  );
  // #58 P2 — overlay 预测毛利 line when cost data is sufficient (align by date).
  const mf = marginForecast.value;
  const marginByDate = new Map<string, number | null>();
  const showMargin = mf != null && mf.costDataSufficient && mf.points.length > 0;
  if (showMargin) {
    for (const p of mf!.points) marginByDate.set(p.date, p.forecastAmount);
  }
  const marginLine = showMargin ? dates.map(d => marginByDate.get(d) ?? null) : [];

  const legendData = showMargin ? ['预测营收', '预测毛利', '80% 区间'] : ['预测营收', '80% 区间'];
  const series: Record<string, unknown>[] = [
    // CI band: invisible lower baseline + translucent stacked band
    { name: '_lower', type: 'line', data: lo, stack: 'ci', lineStyle: { opacity: 0 }, areaStyle: { opacity: 0 }, symbol: 'none', silent: true },
    { name: '80% 区间', type: 'line', data: band, stack: 'ci', lineStyle: { opacity: 0 }, areaStyle: { color: 'rgba(64,158,255,0.18)' }, symbol: 'none' },
    { name: '预测营收', type: 'line', data: fc, smooth: true, lineStyle: { width: 2, color: '#409eff' }, itemStyle: { color: '#409eff' }, symbol: 'circle', symbolSize: 5 },
  ];
  if (showMargin) {
    series.push({
      name: '预测毛利', type: 'line', data: marginLine, smooth: true,
      lineStyle: { width: 2, color: '#67c23a' }, itemStyle: { color: '#67c23a' },
      symbol: 'circle', symbolSize: 5,
    });
  }
  chart.setOption({
    tooltip: { trigger: 'axis', confine: true },
    legend: { data: legendData, top: 0 },
    grid: { left: 60, right: 30, top: 36, bottom: 50 },
    xAxis: { type: 'category', data: dates, axisLabel: { rotate: 40, interval: Math.ceil(dates.length / 12) } },
    yAxis: {
      type: 'value', name: '金额 (¥)',
      axisLabel: { formatter: (v: number) => (v >= 10000 ? (v / 10000).toFixed(1) + '万' : String(Math.round(v))) },
    },
    series,
  }, true);
}

// ── State ─────────────────────────────────────────────────────────────────────
const selectedYear = ref(new Date().getFullYear());
const kpiKind = ref<'revenue' | 'bill_count'>('revenue');
const yearTargetValue = ref<number | null>(null);
const monthlyTargets = ref<Record<string, number | null>>(
  Object.fromEntries(
    Array.from({ length: 12 }, (_, i): [string, number | null] => [
      `${new Date().getFullYear()}-${String(i + 1).padStart(2, '0')}`,
      null,
    ]),
  ),
);
const selectedReason = ref<string>('');
const reasonDetail = ref<string>('');
const saving = ref(false);
const loading = ref(false);

const REASON_OPTIONS = ['季节性', '促销活动', '市场变化', '节假日', '其他'];

// Rule 1: 月均提示
const monthlyAvgHint = computed(() => {
  if (yearTargetValue.value && yearTargetValue.value > 0) {
    return Math.round(yearTargetValue.value / 12).toLocaleString();
  }
  return null;
});

// Rule 3: 仅"其他"显 textarea
const showReasonDetail = computed(() => selectedReason.value === '其他');

// Rule 2: 页面标题上下文
const pageTitle = computed(() => {
  const kpiLabel = kpiKind.value === 'revenue' ? '营业额' : '单量';
  return `设置目标 — ${factoryId.value} / ${selectedYear.value} / ${kpiLabel}`;
});

// ── Save single target entry ──────────────────────────────────────────────────
async function saveSingleTarget(level: string, periodKey: string, targetValue: number) {
  if (!factoryId.value) return;
  const reason = selectedReason.value === '其他' ? reasonDetail.value : selectedReason.value;
  const req: TargetUpsertRequest = {
    kpiKind: kpiKind.value,
    level,
    periodKey,
    targetValue,
    storeId: null,
    reason: reason || null,
  };
  await upsertTarget(req);
}

async function saveYearTarget() {
  if (!yearTargetValue.value || yearTargetValue.value <= 0) {
    ElMessage({ message: '目标值必须大于 0', type: 'error', duration: 0, showClose: true });
    return;
  }
  saving.value = true;
  try {
    await saveSingleTarget('year', String(selectedYear.value), yearTargetValue.value);
    ElMessage({ message: `年度目标已保存（${selectedYear.value}）`, type: 'success' });
  } catch (e: unknown) {
    const msg = (e instanceof Error ? e.message : String(e)) || '保存失败';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    saving.value = false;
  }
}

async function saveAllMonthly() {
  const entries = Object.entries(monthlyTargets.value).filter(([, v]) => v && v > 0);
  if (entries.length === 0) {
    ElMessage({ message: '请至少填写一个月度目标', type: 'warning', duration: 3000 });
    return;
  }
  saving.value = true;
  try {
    for (const [periodKey, value] of entries) {
      await saveSingleTarget('month', periodKey, value as number);
    }
    ElMessage({ message: `已保存 ${entries.length} 个月度目标`, type: 'success' });
    // Rule 5: 保存成功跳 KPI 看板
    setTimeout(() => router.push('/analytics/kpi'), 1200);
  } catch (e: unknown) {
    const msg = (e instanceof Error ? e.message : String(e)) || '保存失败';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    saving.value = false;
  }
}

async function applyYearlyAverage() {
  if (!yearTargetValue.value || yearTargetValue.value <= 0) {
    ElMessage({ message: '请先填写年度目标', type: 'warning', duration: 3000 });
    return;
  }
  await ElMessageBox.confirm(
    `将用年度目标 ¥${yearTargetValue.value.toLocaleString()} 的 1/12 填充所有月度格（¥${monthlyAvgHint.value}/月），是否继续？`,
    '月度均分确认',
    { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' },
  );
  const avg = Math.round(yearTargetValue.value / 12);
  const year = selectedYear.value;
  for (let m = 1; m <= 12; m++) {
    const key = `${year}-${String(m).padStart(2, '0')}`;
    monthlyTargets.value[key] = avg;
  }
}

onMounted(() => {
  loading.value = false;
  // Future: load existing targets via hierarchy_rollup endpoint
});

// Lazy-load forecast + pace when the user opens that tab (avoid eager calls
// when they only want to set targets).
function onTabChange() {
  if (activeTab.value === 'forecast') {
    if (!forecastData.value) loadForecast();
    if (!paceAlert.value) loadPaceAlert();
    void loadMarginRate();
  }
}

// ── Chart Insight: forecast LINE chart ──────────────────────────────────────
// Single LINE chart: rolling revenue forecast + optional margin overlay.
// xDim:'time' (dates), yMetric:'revenue', domain:'restaurant', TREND family.
// Source builds ChartWithMeta from forecastData when the forecast tab is active
// and data is available.  CI band series (_lower / 80%区间) are infrastructure;
// only the 预测营收 series values are fed to the insight engine.
const forecastChartSource = (): { chart: ChartWithMeta } | null => {
  const data = forecastData.value;
  if (!data || data.points.length < 4) return null;
  return {
    chart: {
      chartType: 'LINE',
      title: '滚动营收预测',
      meta: { xDim: 'time', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
      config: {
        xAxis: { data: data.points.map(p => p.date) },
        series: [{ type: 'line', data: data.points.map(p => p.forecastAmount) }],
      },
    },
  };
};

const forecastChartPerms = () => ({ canViewFinance: canViewPrice.value === true });

const { insight: forecastInsight, loading: forecastInsightLoading } = useChartInsight(
  forecastChartSource,
  forecastChartPerms,
  { factoryId: () => factoryId.value, autoTier2: true },
);

defineExpose({
  saving, yearTargetValue, selectedReason, monthlyTargets,
  activeTab, decomposeResult, forecastData, paceAlert,
  marginForecast, marginPace, marginRate, canViewPrice,
  runDecompose, loadForecast, loadPaceAlert, loadMarginForecast, loadMarginPace, saveMarginRate,
});
</script>

<template>
  <div class="target-hierarchy-editor" v-loading="loading">
    <!-- Rule 2: context in header -->
    <div class="page-header">
      <h2>{{ pageTitle }}</h2>
    </div>

    <el-tabs v-model="activeTab" class="p1-tabs" @tab-change="onTabChange">
      <el-tab-pane label="目标设置（年/月）" name="targets" />
      <el-tab-pane label="周/日拆分" name="cascade" />
      <el-tab-pane label="预测 & 实时预警" name="forecast" />
    </el-tabs>

    <!-- ════ TAB 1: 年/月 目标设置 (G2, unchanged) ════ -->
    <template v-if="activeTab === 'targets'">
    <el-card class="section-card">
      <template #header>
        <div class="card-header">年度目标设置</div>
      </template>

      <div class="year-row">
        <el-date-picker
          v-model="selectedYear"
          type="year"
          format="YYYY"
          value-format="YYYY"
          placeholder="选择年份"
          style="width: 140px; margin-right: 16px;"
        />
        <el-tabs v-model="kpiKind" style="margin-bottom: 0;">
          <el-tab-pane label="营业额" name="revenue" />
          <el-tab-pane label="单量" name="bill_count" />
        </el-tabs>
      </div>

      <!-- Rule 1: 年度输入 + 月均提示 -->
      <div class="year-target-row">
        <label class="target-label">年度目标（{{ kpiKind === 'revenue' ? '元' : '单' }}）</label>
        <el-input-number
          v-model="yearTargetValue"
          class="year-target-input"
          :min="1"
          :step="10000"
          style="width: 200px;"
          placeholder="输入年度目标"
        />
        <span v-if="monthlyAvgHint" class="avg-hint">
          月均 ≈ ¥{{ monthlyAvgHint }}
        </span>
      </div>

      <div class="action-row">
        <el-button type="primary" :loading="saving" :disabled="saving" @click="saveYearTarget">
          保存年度目标
        </el-button>
        <el-button :disabled="!yearTargetValue" @click="applyYearlyAverage">
          按年度均分到月度
        </el-button>
      </div>
    </el-card>

    <!-- Monthly targets grid -->
    <el-card class="section-card">
      <template #header>
        <div class="card-header">月度目标（{{ selectedYear }}）</div>
      </template>

      <el-row :gutter="12">
        <el-col :span="4" v-for="m in 12" :key="m" class="month-col">
          <div class="month-label">{{ m }}月</div>
          <el-input-number
            v-model="monthlyTargets[`${selectedYear}-${String(m).padStart(2, '0')}`]"
            :min="1"
            size="small"
            style="width: 100%;"
            :placeholder="`${m}月目标`"
          />
        </el-col>
      </el-row>

      <!-- Rule 3: 原因 dropdown -->
      <div class="reason-row" style="margin-top: 16px;">
        <label class="target-label">调整原因</label>
        <el-select
          v-model="selectedReason"
          class="reason-select"
          placeholder="选择原因（可选）"
          style="width: 180px;"
        >
          <el-option v-for="opt in REASON_OPTIONS" :key="opt" :label="opt" :value="opt" />
        </el-select>
        <el-input
          v-if="showReasonDetail"
          v-model="reasonDetail"
          class="reason-detail-input"
          placeholder="请补充原因"
          style="width: 240px; margin-left: 8px;"
        />
      </div>

      <div class="action-row">
        <!-- Rule 4: saving=true → disabled -->
        <el-button
          type="primary"
          :loading="saving"
          :disabled="saving"
          class="save-btn"
          @click="saveAllMonthly"
        >
          保存所有月度目标
        </el-button>
        <el-button @click="router.push('/analytics/kpi')">返回 KPI 看板</el-button>
      </div>
    </el-card>
    </template>

    <!-- ════ TAB 2: 周/日拆分 ════ -->
    <template v-if="activeTab === 'cascade'">
    <el-card class="section-card">
      <template #header>
        <div class="card-header">月度目标 → 周/日 自动拆分</div>
      </template>
      <p class="hint-text">
        将已设置的<b>月度目标</b>按历史营收占比自动拆分到各周（无历史则按天数均分），
        子级之和严格等于月度目标。勾选"同时拆到每日"可继续按星期权重拆分到天。
      </p>
      <div class="cascade-row">
        <label class="target-label">选择月份</label>
        <el-date-picker
          v-model="decomposeMonth"
          type="month"
          format="YYYY-MM"
          value-format="YYYY-MM"
          placeholder="选择月份"
          style="width: 160px;"
        />
        <el-checkbox v-model="cascadeToDays" style="margin-left: 16px;">
          同时拆到每日
        </el-checkbox>
        <el-button
          type="primary"
          :loading="decomposing"
          :disabled="decomposing"
          style="margin-left: 16px;"
          @click="runDecompose"
        >
          拆分
        </el-button>
      </div>

      <div v-if="decomposeResult && decomposeResult.weeks.length" class="cascade-result">
        <el-alert
          :title="`月度目标 ${fmtMoney(decomposeResult.monthTarget)} 已拆分（依据：${decomposeResult.basis === 'historical_week_share' ? '历史营收占比' : '按天数均分'}）`"
          type="success"
          :closable="false"
          style="margin-bottom: 12px;"
        />
        <el-table :data="decomposeResult.weeks" size="small" border>
          <el-table-column prop="periodKey" label="周" width="160" />
          <el-table-column label="周目标">
            <template #default="{ row }">{{ fmtMoney(row.targetValue) }}</template>
          </el-table-column>
        </el-table>
        <div v-if="decomposeResult.dayCascade" class="day-cascade-note">
          <el-tag type="info" size="small">
            已同时拆分到日（共 {{ decomposeResult.dayCascade.length }} 周 ×7 天）
          </el-tag>
        </div>
      </div>
      <el-empty
        v-else-if="decomposeResult"
        :description="decomposeResult.message || '该月暂无目标可拆分'"
      >
        <el-button type="primary" @click="activeTab = 'targets'">前往设置月度目标</el-button>
      </el-empty>
    </el-card>
    </template>

    <!-- ════ TAB 3: 滚动预测 + 实时 pace 预警 ════ -->
    <template v-if="activeTab === 'forecast'">
    <!-- pace 预警卡片 -->
    <el-card class="section-card">
      <template #header>
        <div class="card-header">
          实时进度预警
          <el-radio-group v-model="pacePeriod" size="small" style="margin-left: 16px;" @change="loadPaceAlert">
            <el-radio-button value="month">本月</el-radio-button>
            <el-radio-button value="week">本周</el-radio-button>
            <el-radio-button value="day">今日</el-radio-button>
          </el-radio-group>
          <el-button size="small" :loading="paceLoading" style="margin-left: 12px;" @click="loadPaceAlert">
            刷新
          </el-button>
        </div>
      </template>
      <div v-if="paceAlert && paceAlert.alertLevel !== 'NO_TARGET'" v-loading="paceLoading" class="pace-card">
        <div class="pace-level" :style="{ color: paceMeta.color }">
          <el-tag :type="paceMeta.tag" effect="dark" size="large">{{ paceMeta.text }}</el-tag>
          <span class="pace-period">{{ paceAlert.periodKey }}</span>
        </div>
        <div class="pace-gauge">
          <div class="gauge-row">
            <span class="gauge-label">目标完成</span>
            <el-progress
              :percentage="Math.min(100, Math.round((paceAlert.completionPct ?? 0) * 100))"
              :color="paceMeta.color"
              :stroke-width="16"
            />
          </div>
          <div class="gauge-row">
            <span class="gauge-label">时间已过</span>
            <el-progress
              :percentage="Math.round((paceAlert.elapsedPct ?? 0) * 100)"
              color="#909399"
              :stroke-width="16"
            />
          </div>
        </div>
        <div class="pace-detail">
          <span>目标 {{ fmtMoney(paceAlert.targetAmount) }}</span>
          <span>已完成 {{ fmtMoney(paceAlert.actualAmount) }}</span>
          <span v-if="paceAlert.dataMissing" class="data-missing">（POS 数据缺失，仅供参考）</span>
        </div>

        <!-- #58 P2 — 毛利进度 (margin = 营收 − 食材成本) -->
        <el-divider style="margin: 16px 0 12px;">毛利进度</el-divider>
        <template v-if="marginPace && marginPace.costDataSufficient && marginPace.alertLevel !== 'NO_TARGET'">
          <div class="pace-level" :style="{ color: marginPaceMeta.color }">
            <el-tag :type="marginPaceMeta.tag" effect="plain" size="default">毛利 {{ marginPaceMeta.text }}</el-tag>
            <span class="pace-period">目标毛利率 {{ Math.round((marginPace.marginRate ?? 0.55) * 100) }}%</span>
          </div>
          <div class="pace-gauge">
            <div class="gauge-row">
              <span class="gauge-label">毛利完成</span>
              <el-progress
                :percentage="Math.min(100, Math.round((marginPace.completionPct ?? 0) * 100))"
                :color="marginPaceMeta.color"
                :stroke-width="16"
              />
            </div>
          </div>
          <div v-if="canViewPrice" class="pace-detail">
            <span>目标毛利 {{ fmtMoney(marginPace.targetMargin) }}</span>
            <span>已实现毛利 {{ fmtMoney(marginPace.marginAmount) }}</span>
            <span class="anchor-note">食材成本 {{ fmtMoney(marginPace.cogsAmount) }}</span>
          </div>
        </template>
        <!-- 防呆 Rule 5: 成本数据不足 → 给出下一步动作 (跳配方/菜名匹配) -->
        <el-alert
          v-else-if="marginPace && marginPace.costDataSufficient === false"
          type="warning" :closable="false" show-icon style="margin-top: 4px;"
        >
          <template #title>
            {{ marginPace.message || '成本数据不足，无法计算毛利进度' }}
          </template>
          <div class="margin-actions">
            <el-button size="small" type="primary" link @click="router.push('/restaurant/recipes')">去配方管理补单价</el-button>
            <el-button size="small" type="primary" link @click="router.push('/restaurant/admin/name-resolution')">去菜品名称匹配</el-button>
          </div>
        </el-alert>
        <span v-else class="anchor-note">毛利进度需先设置营收目标。</span>

        <!-- 目标毛利率设置 (仅有价权角色可改) -->
        <div v-if="canViewPrice" class="margin-rate-row">
          <span class="gauge-label">目标毛利率</span>
          <el-input-number
            v-model="marginRate" :min="0" :max="1" :step="0.01" :precision="2"
            size="small" controls-position="right" style="width: 120px;"
          />
          <el-button size="small" :loading="marginRateSaving" @click="saveMarginRate">保存</el-button>
          <span class="anchor-note">目标毛利 = 营收目标 × 该比率</span>
        </div>
      </div>
      <el-empty
        v-else
        :description="paceAlert?.message || '当前周期目标未设置'"
      >
        <el-button type="primary" @click="activeTab = 'targets'">前往设置目标</el-button>
      </el-empty>
    </el-card>

    <!-- 滚动预测图 -->
    <el-card class="section-card">
      <template #header>
        <div class="card-header">
          滚动营收 / 毛利预测（基于历史趋势，含 80% 置信区间）
          <el-select v-model="forecastHorizon" size="small" style="width: 110px; margin-left: 16px;" @change="loadForecast">
            <el-option :value="7" label="未来 7 天" />
            <el-option :value="14" label="未来 14 天" />
            <el-option :value="30" label="未来 30 天" />
            <el-option :value="60" label="未来 60 天" />
          </el-select>
          <el-button size="small" :loading="forecastLoading" style="margin-left: 12px;" @click="loadForecast">
            刷新预测
          </el-button>
        </div>
      </template>
      <div v-loading="forecastLoading">
        <div v-if="forecastData && forecastData.points.length" class="forecast-meta">
          <el-tag size="small" :type="forecastData.modelType === 'linear_trend' ? 'success' : 'warning'">
            {{ forecastData.modelType === 'linear_trend' ? '线性趋势模型' : '近期均值（数据较少）' }}
          </el-tag>
          <el-tag
            v-if="marginForecast && marginForecast.costDataSufficient"
            size="small" type="success" effect="plain"
          >含预测毛利</el-tag>
          <span class="anchor-note">锚定日期 {{ forecastData.anchorDate }} · 拟合窗口 {{ forecastData.windowDays }} 天</span>
        </div>
        <div id="chart-forecast" class="forecast-chart"></div>
        <!-- Chart Insight: LINE forecast TREND (restaurant, xDim:time, yMetric:revenue) -->
        <ChartInsight :insight="forecastInsight" :loading="forecastInsightLoading" depth="detailed" />
        <!-- 防呆 Rule 2 + Rule 5: 毛利成本数据不足 → 显数字 + 下一步动作 -->
        <el-alert
          v-if="marginInsufficient"
          type="info" :closable="false" show-icon style="margin-top: 8px;"
        >
          <template #title>毛利预测：{{ marginForecast?.message || '成本数据不足' }}</template>
          <div v-if="marginForecast?.coverage" class="anchor-note" style="margin-top: 4px;">
            已配价 {{ marginForecast.coverage.pricedDishCount }} 道菜 ·
            覆盖 {{ Math.round((marginForecast.coverage.revenueCoverage ?? 0) * 100) }}% 营收
            （需 ≥3 道菜且 ≥50% 营收覆盖才计算毛利）
          </div>
          <div class="margin-actions">
            <el-button size="small" type="primary" link @click="router.push('/restaurant/recipes')">去配方管理补单价</el-button>
            <el-button size="small" type="primary" link @click="router.push('/restaurant/admin/name-resolution')">去菜品名称匹配</el-button>
          </div>
        </el-alert>
        <el-empty
          v-if="forecastData && forecastData.points.length === 0"
          :description="forecastData.message || '暂无足够历史数据生成预测'"
        />
      </div>
    </el-card>
    </template>
  </div>
</template>

<style scoped>
.target-hierarchy-editor { padding: 20px; }
.page-header { margin-bottom: 20px; }
.page-header h2 { font-size: 18px; font-weight: 600; color: #303133; }
.section-card { margin-bottom: 16px; }
.card-header { font-weight: 600; }
.year-row { display: flex; align-items: center; margin-bottom: 16px; }
.year-target-row { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.target-label { font-size: 14px; color: #606266; min-width: 120px; }
.avg-hint { color: #909399; font-size: 13px; }
.action-row { margin-top: 16px; display: flex; gap: 8px; }
.month-col { margin-bottom: 12px; }
.month-label { font-size: 13px; color: #606266; margin-bottom: 4px; }
.reason-row { display: flex; align-items: center; gap: 8px; }
.p1-tabs { margin-bottom: 8px; }
.hint-text { color: #909399; font-size: 13px; line-height: 1.6; margin: 0 0 16px; }
.cascade-row { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; }
.cascade-result { margin-top: 8px; }
.day-cascade-note { margin-top: 10px; }
.pace-card { padding: 8px 0; }
.pace-level { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.pace-period { font-size: 14px; color: #606266; }
.pace-gauge { max-width: 520px; }
.gauge-row { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
.gauge-label { min-width: 72px; font-size: 13px; color: #606266; }
.gauge-row :deep(.el-progress) { flex: 1; }
.pace-detail { margin-top: 12px; display: flex; gap: 24px; font-size: 14px; color: #303133; }
.data-missing { color: #e6a23c; font-size: 13px; }
.forecast-meta { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.anchor-note { color: #909399; font-size: 13px; }
.forecast-chart { width: 100%; height: 340px; }
.margin-rate-row { display: flex; align-items: center; gap: 10px; margin-top: 16px; }
.margin-actions { margin-top: 6px; display: flex; gap: 12px; }
</style>
