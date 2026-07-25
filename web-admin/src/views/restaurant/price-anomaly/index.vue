<template>
  <CanvasAwareWrapper module-code="restaurant">
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">价格异常预警</span>
            <span class="data-count">检出 {{ anomalies.length }} 条</span>
          </div>
          <div class="header-right">
            <span class="cfg-label">基线</span>
            <el-tag size="small" effect="plain">近 90 天移动均价</el-tag>
            <span class="cfg-label">异常容限</span>
            <el-input-number v-model="epsilonPct" :min="0" :max="100" :step="1" size="small" style="width: 120px" @change="loadAnomalies">
              <template #suffix>%</template>
            </el-input-number>
            <el-button :icon="Refresh" @click="loadAnomalies">刷新</el-button>
          </div>
        </div>
      </template>

      <!-- 威慑说明 (邓总护城河): 报警→要求解释→连续累计标高风险 -->
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="价格异常威慑"
        description="系统对同类物料相邻采购单价做异常检测。对每条异常请要求供应商录入解释 (不自动处罚)；连续同向异常累计三次，该供应商自动标记为「高风险」。"
        style="margin-bottom: 16px"
      />

      <!-- 采购单价趋势曲线: 把异常点放回时间序列, 涨跌一眼看得见 -->
      <div v-if="anomalies.length" class="trend-section">
        <div class="trend-head">
          <span class="trend-title">采购单价趋势</span>
          <el-select
            v-model="trendIngredient"
            size="small"
            style="width: 200px"
            placeholder="选择食材"
            @change="loadTrend"
          >
            <el-option v-for="ing in trendIngredients" :key="ing.value" :label="ing.label" :value="ing.value" />
          </el-select>
        </div>
        <div ref="trendChartEl" v-loading="trendLoading" class="trend-chart"></div>
        <p class="trend-hint muted">红点为本次检出的异常点 · 虚线为近 90 天移动均价基线</p>
      </div>

      <el-table
        :data="anomalies"
        v-loading="loading"
        stripe
        style="width: 100%"
        highlight-current-row
        @row-click="onRowClick"
      >
        <el-table-column label="风险" width="90" fixed="left">
          <template #default="{ row }">
            <el-tag :type="row.riskLevel === 'HIGH' ? 'danger' : 'warning'" size="small" effect="dark">
              {{ row.riskLevel === 'HIGH' ? '高风险' : '关注' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ingredientName" label="物料" min-width="120">
          <template #default="{ row }">{{ ingredientDisplayName(row) }}</template>
        </el-table-column>
        <el-table-column prop="supplierName" label="供应商" min-width="120">
          <template #default="{ row }">{{ row.supplierName || '—' }}</template>
        </el-table-column>
        <el-table-column label="趋势均价" width="110" align="right">
          <template #default="{ row }">{{ fmtPrice(row.trailingAvg) }}</template>
        </el-table-column>
        <el-table-column label="上次价" width="100" align="right">
          <template #default="{ row }">{{ fmtPrice(row.oldPrice) }}</template>
        </el-table-column>
        <el-table-column label="本次价" width="100" align="right">
          <template #default="{ row }">
            <span :class="row.direction === 'UP' ? 'price-up' : 'price-down'">{{ fmtPrice(row.newPrice) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="偏离" width="110" align="right">
          <template #default="{ row }">
            <span :class="row.direction === 'UP' ? 'price-up' : 'price-down'">
              {{ row.direction === 'UP' ? '▲' : '▼' }} {{ fmtPct(row.deltaPct) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="连续异常" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.consecutiveAnomalyCount >= 3 ? 'danger' : 'info'" size="small">
              第 {{ row.consecutiveAnomalyCount }} 次
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="anomalyDeliveryDate" label="送货日" width="120" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canWrite" link type="primary" @click="openExplain(row)">录入解释</el-button>
            <span v-else class="muted">仅查看</span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <p>未检出价格异常</p>
            <p class="muted">
              价格异常依赖供应商进价历史。请先在
              <el-link type="primary" underline="hover" @click="goSupplierDelivery">供应商进货录入</el-link>
              录入/确认送货单，积累进价后再检测。
            </p>
          </div>
        </template>
      </el-table>

      <!-- 已确认记录 (留痕) -->
      <div class="acks-section" v-if="acks.length">
        <div class="acks-title">已确认/已解释 ({{ acks.length }})</div>
        <el-table :data="acks" size="small" stripe style="width: 100%">
          <el-table-column label="物料" min-width="100">
            <template #default="{ row }">{{ ingredientDisplayName(row) }}</template>
          </el-table-column>
          <el-table-column prop="supplierName" label="供应商" min-width="100">
            <template #default="{ row }">{{ row.supplierName || '—' }}</template>
          </el-table-column>
          <el-table-column label="本次价" width="90" align="right">
            <template #default="{ row }">{{ fmtPrice(row.newPrice) }}</template>
          </el-table-column>
          <el-table-column label="解释" min-width="140">
            <template #default="{ row }">
              <el-tag size="small">{{ reasonLabel(row.reasonCode) }}</el-tag>
              <span v-if="row.reasonNote" class="ack-note">{{ row.reasonNote }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="acknowledgedBy" label="记录人" width="120" />
          <el-table-column prop="anomalyDeliveryDate" label="送货日" width="110" />
        </el-table>
      </div>
    </el-card>

    <!-- Rule 1 边界预显 + Rule 2 上下文 + Rule 3 原因 dropdown -->
    <el-dialog
      v-model="explainVisible"
      :title="explainTitle"
      width="480px"
      @closed="resetExplainForm"
    >
      <div v-if="current" class="explain-ctx">
        <div class="ctx-row">
          <span class="ctx-k">趋势均价</span><span class="ctx-v">{{ fmtPrice(current.trailingAvg) }}</span>
          <span class="ctx-k">本次价</span>
          <span class="ctx-v" :class="current.direction === 'UP' ? 'price-up' : 'price-down'">
            {{ fmtPrice(current.newPrice) }} ({{ current.direction === 'UP' ? '▲' : '▼' }} {{ fmtPct(current.deltaPct) }})
          </span>
        </div>
        <el-alert
          v-if="current.riskLevel === 'HIGH'"
          type="error"
          :closable="false"
          show-icon
          title="该供应商已连续异常 3 次，已标记为高风险"
          style="margin: 8px 0"
        />
      </div>

      <el-form :model="explainForm" label-width="92px">
        <el-form-item label="异常原因" required>
          <el-select v-model="explainForm.reasonCode" placeholder="请选择原因" style="width: 100%">
            <el-option v-for="opt in REASON_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item
          v-if="explainForm.reasonCode === 'OTHER'"
          label="补充说明"
          required
        >
          <el-input
            v-model="explainForm.reasonNote"
            type="textarea"
            :rows="3"
            placeholder="选择「其他」时请补充具体原因"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="explainVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!explainValid" @click="submitExplain">
          确认记录
        </el-button>
      </template>
    </el-dialog>
  </div>
  </CanvasAwareWrapper>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import { usePermissionStore } from '@/store/modules/permission';
import { getErrorMessage } from '@/utils/errorToast';
import CanvasAwareWrapper from '@/components/canvas/CanvasAwareWrapper.vue';
import {
  ingredientDisplayName,
  normalizeIngredientOption,
  normalizedIngredientValue,
} from './ingredientOption';
import {
  detectPriceAnomalies,
  ackPriceAnomaly,
  listPriceAnomalyAcks,
  getSupplierPriceTrend,
  REASON_OPTIONS,
  type PriceAnomaly,
  type PriceAnomalyAck,
  type ReasonCode,
} from '@/api/smartbi/priceAnomaly';

const router = useRouter();
const permissionStore = usePermissionStore();
const canWrite = computed(() => permissionStore.canWrite('restaurant'));

const loading = ref(false);
const submitting = ref(false);
const anomalies = ref<PriceAnomaly[]>([]);
const acks = ref<PriceAnomalyAck[]>([]);
const epsilonPct = ref(5);
const BASELINE_WINDOW_DAYS = 90;

// ---- 采购单价趋势曲线 ----
const trendChartEl = ref<HTMLDivElement | null>(null);
const trendLoading = ref(false);
const trendIngredient = ref(''); // 存 normalizedName (endpoint 按 normalized 匹配)
let trendChart: echarts.ECharts | null = null;

// 唯一食材下拉 (按 normalizedName 去重, label 显友好名)
const trendIngredients = computed(() => {
  const seen = new Map<string, string>();
  for (const a of anomalies.value) {
    const option = normalizeIngredientOption(a);
    if (option && !seen.has(option.value)) {
      seen.set(option.value, option.label);
    }
  }
  return Array.from(seen, ([value, label]) => ({ value, label }));
});

async function loadTrend() {
  if (!trendIngredient.value) return;
  trendLoading.value = true;
  try {
    const points = await getSupplierPriceTrend(trendIngredient.value, BASELINE_WINDOW_DAYS);
    await nextTick();
    renderTrend(points);
  } catch (e) {
    ElMessage({ message: getErrorMessage(e, '进价趋势加载失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    trendLoading.value = false;
  }
}

function renderTrend(points: import('@/api/smartbi/priceAnomaly').SupplierPricePoint[]) {
  if (!trendChartEl.value) return;
  if (!trendChart) trendChart = echarts.init(trendChartEl.value);

  const valid = points.filter((p) => p.deliveryDate != null && p.unitPrice != null);
  const dates = valid.map((p) => p.deliveryDate as string);
  const prices = valid.map((p) => p.unitPrice as number);

  // 本食材当前异常 (标红点 + 基线)
  const anomaly = anomalies.value.find(
    (a) => normalizedIngredientValue(a) === trendIngredient.value,
  );
  const markData =
    anomaly && anomaly.anomalyDeliveryDate && anomaly.newPrice != null
      ? [{ name: '异常点', coord: [anomaly.anomalyDeliveryDate, anomaly.newPrice], value: anomaly.newPrice }]
      : [];
  const baseline = anomaly && anomaly.trailingAvg != null ? anomaly.trailingAvg : null;

  trendChart.setOption(
    {
      grid: { left: 48, right: 24, top: 24, bottom: 32 },
      tooltip: {
        trigger: 'axis',
        valueFormatter: (v: number) => (v == null ? '—' : `¥${v}`),
      },
      xAxis: { type: 'category', data: dates, boundaryGap: false },
      yAxis: { type: 'value', scale: true, axisLabel: { formatter: '¥{value}' } },
      series: [
        {
          name: '采购单价',
          type: 'line',
          smooth: true,
          showSymbol: true,
          symbolSize: 6,
          data: prices,
          lineStyle: { width: 2 },
          areaStyle: { opacity: 0.08 },
          markPoint: {
            symbol: 'pin',
            symbolSize: 46,
            itemStyle: { color: '#F56C6C' },
            label: { color: '#fff', fontSize: 11, formatter: '异常' },
            data: markData,
          },
          markLine: baseline
            ? {
                silent: true,
                symbol: 'none',
                lineStyle: { type: 'dashed', color: '#E6A23C' },
                label: { formatter: `均价 ¥${baseline}`, position: 'insideEndTop', fontSize: 11 },
                data: [{ yAxis: baseline }],
              }
            : undefined,
        },
      ],
    },
    true,
  );
  trendChart.resize();
}

function onRowClick(row: PriceAnomaly) {
  const value = normalizedIngredientValue(row);
  if (value && value !== trendIngredient.value) {
    trendIngredient.value = value;
    loadTrend();
  }
}

function handleTrendResize() {
  trendChart?.resize();
}

const explainVisible = ref(false);
const current = ref<PriceAnomaly | null>(null);
const explainForm = reactive<{ reasonCode: ReasonCode | ''; reasonNote: string }>({
  reasonCode: '',
  reasonNote: '',
});

const explainTitle = computed(() => {
  if (!current.value) return '录入解释';
  const name = ingredientDisplayName(current.value);
  const sup = current.value.supplierName ? ` · ${current.value.supplierName}` : '';
  return `录入解释 — ${name}${sup}`;
});

// Rule 3: OTHER requires a non-blank note → submit disabled until valid.
const explainValid = computed(() => {
  if (!explainForm.reasonCode) return false;
  if (explainForm.reasonCode === 'OTHER' && !explainForm.reasonNote.trim()) return false;
  return true;
});

function fmtPrice(v: number | null | undefined): string {
  // null = RBAC-stripped for non price-view roles → show 锁定占位 not 0 (missing≠zero)
  return v == null ? '—' : `¥${v}`;
}
function fmtPct(v: number | null | undefined): string {
  return v == null ? '—' : `${Math.abs(v).toFixed(2)}%`;
}
function reasonLabel(code: ReasonCode): string {
  return REASON_OPTIONS.find((o) => o.value === code)?.label || code;
}

async function loadAnomalies() {
  loading.value = true;
  try {
    const [det, ackList] = await Promise.all([
      detectPriceAnomalies({
        epsilonPct: epsilonPct.value,
        baselineMode: 'days',
        windowDays: BASELINE_WINDOW_DAYS,
      }),
      listPriceAnomalyAcks(),
    ]);
    anomalies.value = det;
    acks.value = ackList;
    // 默认选第一条异常的食材, 画出它的进价趋势曲线
    if (det.length) {
      const first = normalizedIngredientValue(det[0]);
      if (first && !trendIngredients.value.some((o) => o.value === trendIngredient.value)) {
        trendIngredient.value = first;
      }
      if (trendIngredient.value) await loadTrend();
    }
  } catch (e) {
    // pythonFetch errors carry the backend message (no axios interceptor here),
    // so surface it directly + sticky for流程依赖错误 (fool-proof 4位一体).
    ElMessage({ message: getErrorMessage(e, '价格异常检测失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}

function openExplain(row: PriceAnomaly) {
  current.value = row;
  explainForm.reasonCode = '';
  explainForm.reasonNote = '';
  explainVisible.value = true;
}

function resetExplainForm() {
  current.value = null;
  explainForm.reasonCode = '';
  explainForm.reasonNote = '';
}

async function submitExplain() {
  if (!current.value || !explainForm.reasonCode) return;
  if (current.value.anomalyDeliveryDate == null || current.value.newPrice == null) {
    ElMessage({ message: '缺少异常关键信息 (送货日/价格), 无法记录', type: 'error', duration: 0, showClose: true });
    return;
  }
  submitting.value = true;
  try {
    const ingredient = normalizeIngredientOption(current.value);
    if (!ingredient) {
      ElMessage({ message: '缺少有效物料名称，无法记录', type: 'error', duration: 0, showClose: true });
      return;
    }
    await ackPriceAnomaly({
      normalizedName: ingredient.value,
      ingredientName: ingredient.label,
      supplierId: current.value.supplierId,
      supplierName: current.value.supplierName,
      anomalyDeliveryDate: current.value.anomalyDeliveryDate,
      oldPrice: current.value.oldPrice,
      newPrice: current.value.newPrice,
      deltaPct: current.value.deltaPct,
      reasonCode: explainForm.reasonCode,
      reasonNote: explainForm.reasonNote.trim() || null,
    });
    ElMessage.success('解释已记录, 该异常已确认');
    explainVisible.value = false;
    await loadAnomalies();
  } catch (e) {
    ElMessage({ message: getErrorMessage(e, '解释录入失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    submitting.value = false;
  }
}

// Rule 5: dead-end → 跳转到进货录入积累数据
function goSupplierDelivery() {
  router.push({ name: 'SupplierDeliveryNoteList' });
}

onMounted(() => {
  loadAnomalies();
  window.addEventListener('resize', handleTrendResize);
});
onBeforeUnmount(() => {
  window.removeEventListener('resize', handleTrendResize);
  trendChart?.dispose();
  trendChart = null;
});
</script>

<style scoped lang="scss">
@use '../restaurant-shared.scss';
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.header-left {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cfg-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.page-title {
  font-size: 16px;
  font-weight: 600;
}
.data-count {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.price-up {
  color: var(--el-color-danger);
  font-weight: 600;
}
.price-down {
  color: var(--el-color-success);
  font-weight: 600;
}
.empty-state {
  padding: 24px 0;
  text-align: center;
}
.trend-section {
  margin-bottom: 16px;
  padding: 12px 16px 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-bg-color);
}
.trend-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.trend-title {
  font-size: 14px;
  font-weight: 600;
}
.trend-chart {
  width: 100%;
  height: 260px;
}
.trend-hint {
  margin: 4px 0 0;
  font-size: 12px;
}
.acks-section {
  margin-top: 24px;
}
.acks-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 8px;
}
.ack-note {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.explain-ctx {
  margin-bottom: 12px;
}
.ctx-row {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 12px;
}
.ctx-k {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.ctx-v {
  font-size: 14px;
  font-weight: 600;
}
</style>
