<template>
  <el-drawer
    v-model="visible"
    :title="drawerTitle"
    direction="rtl"
    size="60%"
    :destroy-on-close="true"
    @close="onClose"
  >
    <div v-if="loading" class="loading-block">
      <el-skeleton :rows="6" animated />
    </div>

    <div v-else-if="loadError" class="error-block">
      <el-result
        icon="error"
        :title="'加载失败'"
        :sub-title="loadError"
      >
        <template #extra>
          <el-button type="primary" @click="loadDetail">重试</el-button>
        </template>
      </el-result>
    </div>

    <div v-else-if="detail" class="detail-content">
      <!-- 顶部: 基础信息 + 操作 -->
      <div class="meta-section">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="编码">
            <code class="code-text">{{ detail.code }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="分类">
            <el-tag size="small">{{ categoryText(detail.category) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="单位">
            {{ detail.unit || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="detail.isActive ? 'success' : 'info'" size="small">
              {{ detail.isActive ? '启用' : '停用' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="计算策略">
            {{ strategyText(detail.computeStrategy) }}
          </el-descriptions-item>
          <el-descriptions-item label="缓存 TTL">
            {{ detail.cacheTtlSeconds ? `${detail.cacheTtlSeconds} 秒` : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="说明" :span="2">
            {{ detail.description || '—' }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="action-bar">
          <el-button
            v-if="canRecompute"
            type="primary"
            :loading="recomputing"
            :icon="Refresh"
            @click="onRecompute"
          >
            强制重算
          </el-button>
          <el-button :icon="Refresh" @click="loadAll">刷新</el-button>
        </div>
      </div>

      <!-- 当前值 + 阈值色带 -->
      <el-card class="section-card" shadow="never">
        <template #header>
          <span class="section-title">当前指标值</span>
        </template>
        <ThresholdGauge
          :current-value="currentValue"
          :unit="detail.unit"
          :thresholds="thresholds"
          :alert-level="currentAlertLevel"
          :loading="valueLoading || thresholdsLoading"
        />
      </el-card>

      <!-- 历史快照 -->
      <el-card class="section-card" shadow="never">
        <template #header>
          <div class="section-header-flex">
            <span class="section-title">历史趋势</span>
            <el-radio-group v-model="historyView" size="small">
              <el-radio-button label="chart">图表</el-radio-button>
              <el-radio-button label="table">表格</el-radio-button>
            </el-radio-group>
          </div>
        </template>

        <div v-if="versionsLoading" class="loading-block">
          <el-skeleton :rows="3" animated />
        </div>

        <el-empty
          v-else-if="versions.length === 0"
          :image-size="60"
          description="暂无历史快照"
        />

        <div v-else>
          <!-- ECharts 折线图 (>5 条用图表, ≤5 用表格) -->
          <div
            v-show="historyView === 'chart' && versions.length > 5"
            ref="chartRef"
            class="history-chart"
          />
          <!-- U6: useChartInsight — TREND insight on historical versions -->
          <ChartInsight :insight="versionsInsight" :loading="versionsInsightLoading" depth="detailed" />

          <!-- 表格视图 (始终可切换, 数据少时默认显示) -->
          <el-table
            v-show="historyView === 'table' || versions.length <= 5"
            :data="versions"
            size="small"
            border
            stripe
            max-height="320"
          >
            <el-table-column prop="computedAt" label="计算时间" min-width="160">
              <template #default="{ row }">
                {{ formatDateTime(row.computedAt) }}
              </template>
            </el-table-column>
            <el-table-column prop="periodStart" label="期间起" min-width="100">
              <template #default="{ row }">
                {{ row.periodStart }}
              </template>
            </el-table-column>
            <el-table-column prop="periodEnd" label="期间止" min-width="100">
              <template #default="{ row }">
                {{ row.periodEnd }}
              </template>
            </el-table-column>
            <el-table-column prop="value" label="数值" min-width="100" align="right">
              <template #default="{ row }">
                <span class="version-value">{{ formatValue(row.value) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="alertLevel" label="告警" min-width="80" align="center">
              <template #default="{ row }">
                <el-tag
                  v-if="row.alertLevel"
                  :type="alertTagType(row.alertLevel)"
                  size="small"
                  effect="dark"
                >
                  {{ alertLevelText(row.alertLevel) }}
                </el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-if="versionsTotal > pageSize"
            class="version-pagination"
            small
            background
            layout="prev, pager, next, total"
            :total="versionsTotal"
            :page-size="pageSize"
            :current-page="page + 1"
            @current-change="onPageChange"
          />
        </div>
      </el-card>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
/**
 * IndicatorDetailDrawer — slide-in drawer 显示指标详情 + 阈值色带 + 历史快照.
 *
 * v-model:visible 控制开关. props.code 切换时自动 reload.
 */
import { ref, computed, watch, nextTick } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import { usePermissionStore } from '@/store/modules/permission';
import { useChartInsight } from '@/composables/useChartInsight';
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue';
import {
  getIndicatorDetail,
  getIndicatorValue,
  getIndicatorThresholds,
  listIndicatorVersions,
  recomputeIndicator,
  type IndicatorDetailResponse,
  type IndicatorValueResponse,
  type ThresholdResponse,
  type IndicatorVersionResponse,
  type AlertLevel,
} from '@/api/indicator';
import ThresholdGauge from '@/components/indicator/ThresholdGauge.vue';

interface Props {
  modelValue: boolean;
  factoryId: string;
  code: string | null;
}

const props = defineProps<Props>();
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void;
  (e: 'recomputed', code: string): void;
}>();

const permissionStore = usePermissionStore();
const canRecompute = computed(() => permissionStore.canWrite('analytics'));

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
});

const detail = ref<IndicatorDetailResponse | null>(null);
const currentValueData = ref<IndicatorValueResponse | null>(null);
const thresholds = ref<ThresholdResponse[]>([]);
const versions = ref<IndicatorVersionResponse[]>([]);
const versionsTotal = ref(0);

const loading = ref(false);
const valueLoading = ref(false);
const thresholdsLoading = ref(false);
const versionsLoading = ref(false);
const recomputing = ref(false);
const loadError = ref<string | null>(null);

const page = ref(0);
const pageSize = 10;
const historyView = ref<'chart' | 'table'>('chart');

const chartRef = ref<HTMLElement | null>(null);
let chartInst: echarts.ECharts | null = null;

const drawerTitle = computed(() => {
  if (loading.value) return '指标详情';
  return detail.value ? `${detail.value.name} - 指标详情` : '指标详情';
});

const currentValue = computed(() => {
  if (currentValueData.value?.value !== undefined && currentValueData.value?.value !== null) {
    return currentValueData.value.value;
  }
  return detail.value?.lastValue ?? null;
});

const currentAlertLevel = computed<AlertLevel>(() => {
  return currentValueData.value?.alertLevel ?? null;
});

async function loadDetail() {
  if (!props.code) return;
  loadError.value = null;
  loading.value = true;
  try {
    detail.value = await getIndicatorDetail(props.factoryId, props.code);
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

async function loadValue() {
  if (!props.code) return;
  valueLoading.value = true;
  try {
    currentValueData.value = await getIndicatorValue(props.factoryId, props.code);
  } catch (e) {
    // 静默 — 详情 detail.lastValue 兜底
    console.warn('[IndicatorDetailDrawer] value load failed:', e);
  } finally {
    valueLoading.value = false;
  }
}

async function loadThresholds() {
  if (!props.code) return;
  thresholdsLoading.value = true;
  try {
    thresholds.value = await getIndicatorThresholds(props.factoryId, props.code);
  } catch (e) {
    console.warn('[IndicatorDetailDrawer] thresholds load failed:', e);
    thresholds.value = [];
  } finally {
    thresholdsLoading.value = false;
  }
}

async function loadVersions() {
  if (!props.code) return;
  versionsLoading.value = true;
  try {
    const res = await listIndicatorVersions(props.factoryId, props.code, {
      page: page.value,
      size: pageSize,
    });
    versions.value = res.content || [];
    versionsTotal.value = res.totalElements ?? 0;
    nextTick(() => renderChart());
  } catch (e) {
    console.warn('[IndicatorDetailDrawer] versions load failed:', e);
    versions.value = [];
    versionsTotal.value = 0;
  } finally {
    versionsLoading.value = false;
  }
}

async function loadAll() {
  await Promise.all([loadDetail(), loadValue(), loadThresholds(), loadVersions()]);
}

function onPageChange(newPage: number) {
  page.value = newPage - 1;
  loadVersions();
}

async function onRecompute() {
  if (!props.code) return;
  recomputing.value = true;
  try {
    const result = await recomputeIndicator(props.factoryId, props.code);
    currentValueData.value = result;
    ElMessage.success(`重算完成: ${result.value ?? '—'}`);
    emit('recomputed', props.code);
    // 重新加载详情 + 历史 (新增了一条 version)
    await Promise.all([loadDetail(), loadVersions()]);
  } catch (e) {
    // ApiError 已被 request.ts 拦截展示 toast, 此处只 console.
    console.warn('[IndicatorDetailDrawer] recompute failed:', e);
  } finally {
    recomputing.value = false;
  }
}

function onClose() {
  // reset 状态便于下次打开
  detail.value = null;
  currentValueData.value = null;
  thresholds.value = [];
  versions.value = [];
  versionsTotal.value = 0;
  page.value = 0;
  loadError.value = null;
  if (chartInst) {
    chartInst.dispose();
    chartInst = null;
  }
}

function renderChart() {
  if (historyView.value !== 'chart' || versions.value.length <= 5) return;
  if (!chartRef.value) return;

  if (!chartInst) {
    chartInst = echarts.init(chartRef.value);
  }

  // versions 后端默认 page=0 DESC; 反转给图表展示 (旧→新)
  const sorted = [...versions.value].sort(
    (a, b) => new Date(a.computedAt).getTime() - new Date(b.computedAt).getTime()
  );
  const xData = sorted.map(v => formatDateTime(v.computedAt));
  const yData = sorted.map(v => ({
    value: v.value,
    itemStyle: {
      color: alertColor(v.alertLevel),
    },
  }));

  const option: echarts.EChartsOption = {
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 30, bottom: 60 },
    xAxis: {
      type: 'category',
      data: xData,
      axisLabel: { rotate: 30, fontSize: 10 },
    },
    yAxis: {
      type: 'value',
      name: detail.value?.unit || '',
      axisLabel: { fontSize: 10 },
    },
    series: [
      {
        name: detail.value?.name || '值',
        type: 'line',
        data: yData,
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { color: '#5470c6', width: 2 },
      },
    ],
  };

  chartInst.setOption(option);
}

// ==================== Chart Insight ====================
// LINE over time: versions (sorted asc by computedAt) → TREND family.
// Driven off the same `versions` ref used by renderChart().
const indicatorVersionsSource = () => {
  if (versions.value.length < 2) return null;
  const sorted = [...versions.value].sort(
    (a, b) => new Date(a.computedAt).getTime() - new Date(b.computedAt).getTime(),
  );
  return {
    chart: {
      chartType: 'LINE',
      meta: { xDim: 'time' as const, yMetric: 'quantity' as const, aggregation: 'avg' as const, domain: 'factory' as const },
      config: {
        xAxis: { data: sorted.map((v) => v.computedAt.slice(0, 10)) },
        series: [{ type: 'line', data: sorted.map((v) => Number(v.value)) }],
      },
    },
  };
};

const { insight: versionsInsight, loading: versionsInsightLoading } = useChartInsight(
  indicatorVersionsSource,
  () => ({ canViewFinance: false }),
  { factoryId: () => props.factoryId, autoTier2: true },
);

watch(historyView, () => {
  nextTick(() => renderChart());
});

watch(
  () => [props.modelValue, props.code],
  ([show, code]) => {
    if (show && code) {
      loadAll();
    }
  }
);

// ============ helpers ============

function categoryText(c: string): string {
  const map: Record<string, string> = {
    FACTORY: '工厂',
    RESTAURANT: '餐饮',
    QUALITY: '质量',
  };
  return map[c] || c;
}

function strategyText(s: string): string {
  const map: Record<string, string> = {
    REALTIME: '实时计算',
    CACHED: '缓存 (TTL)',
    PRECOMPUTED: '预计算',
  };
  return map[s] || s;
}

function alertLevelText(level: string): string {
  return level === 'GREEN' ? '正常' : level === 'YELLOW' ? '关注' : level === 'RED' ? '告警' : level;
}

function alertTagType(level: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (level) {
    case 'GREEN': return 'success';
    case 'YELLOW': return 'warning';
    case 'RED': return 'danger';
    default: return 'info';
  }
}

function alertColor(level: AlertLevel): string {
  switch (level) {
    case 'GREEN': return '#67c23a';
    case 'YELLOW': return '#e6a23c';
    case 'RED': return '#f56c6c';
    default: return '#909399';
  }
}

function formatValue(v: number): string {
  if (Math.abs(v) < 1000) {
    return Number.isInteger(v) ? v.toString() : v.toFixed(2);
  }
  return v.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

function formatDateTime(iso: string): string {
  try {
    return new Date(iso).toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return iso;
  }
}
</script>

<style scoped>
.loading-block,
.error-block {
  padding: 24px;
}

.detail-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 0 4px;
}

.meta-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.code-text {
  background: var(--el-fill-color);
  padding: 2px 6px;
  border-radius: 3px;
  font-family: var(--el-font-family-monospace, monospace);
}

.action-bar {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.section-card {
  border: 1px solid var(--el-border-color-lighter);
}

.section-title {
  font-size: 14px;
  font-weight: 600;
}

.section-header-flex {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.history-chart {
  width: 100%;
  height: 280px;
}

.version-value {
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}

.version-pagination {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}

.muted {
  color: var(--el-text-color-placeholder);
}
</style>
