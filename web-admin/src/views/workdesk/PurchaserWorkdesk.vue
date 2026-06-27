<!--
  PurchaserWorkdesk.vue — Sprint 8 P4b 采购员 Workdesk V1

  F006 真场景: 采购员小赵每天打开 Cretas, 默认问 "下周采购什么?"
  系统聚合 5 品类预警 (库存 + 销售预测 + 供应商交期 + 价格历史) + [一键生成请购单].

  vs HJ: 采购员要点 4 菜单 (库存预警 / 销售订单 / 供应商管理 / 价格历史) + 手工算交期,
  Cretas Workdesk 1 页面 AI 输出 + 一键创建.

  架构: 复用 /ai-intents/execute POST 端点 (mirror P1/P2/P3/P4a Workdesk).
  - 进入页面自动 trigger 'PURCHASER_WEEKLY_PLAN' (默认 stock_alert)
  - 并发拉销售预测 + 价格历史
  - 一键请购 dialog: 调 requisition_create Preview → 确认 → execute (R1+R2+R4)

  防呆 (Fool-Proof rules):
  - R1 max: 请购 dialog 显物料/数量/预算金额 (历史均价) + 推荐供应商
  - R2 context: dialog 标题 "请购 — {物料名} ({物料编码})"
  - R4 idempotent: 后端 5min dedup window
  - 4 位一体: error sticky + 业务 message + UI 一致 + 含 next action
-->
<template>
  <div class="purchaser-workdesk">
    <!-- Header -->
    <div class="workdesk-header">
      <div class="header-title">
 <span class="emoji"></span>
        <span class="title-text">采购员工作台</span>
        <el-tag size="small" type="info">Sprint 8 P4b (2026-05-20)</el-tag>
      </div>
      <div class="header-actions">
        <el-button :loading="loading" :icon="Refresh" @click="triggerWeeklyPlan">
          重新加载
        </el-button>
      </div>
    </div>

    <!-- AI Chat 输入区 -->
    <el-card class="chat-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 与 AI 对话</span>
          <span class="header-hint">默认查询: "下周采购什么?" — 也可问其他</span>
        </div>
      </template>
      <div class="chat-input">
        <el-input
          v-model="userInput"
          type="textarea"
          :rows="2"
          placeholder="例如: 下周采购什么? / 价格历史 / 供应商交期"
          @keydown.enter.ctrl="sendQuery"
        />
        <el-button
          type="primary"
          :loading="loading"
          :disabled="!userInput.trim()"
          @click="sendQuery">
          发送 (Ctrl+Enter)
        </el-button>
      </div>
    </el-card>

    <!-- 加载中提示 -->
    <el-card v-if="loading" class="loading-card" shadow="never">
      <div class="loading-content">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>AI 正在聚合 5 品类预警 (低库存 + 销售预测), 预计 3-5 秒...</span>
      </div>
    </el-card>

    <!-- 错误显示 -->
    <el-alert
      v-if="errorMessage"
      :title="errorMessage"
      type="error"
      show-icon
      :closable="false"
      class="error-alert" />

    <!-- AI 输出 -->
    <el-card v-if="formattedText" class="result-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> {{ resultTitle }}</span>
          <span class="header-hint" v-if="lastQueryTime">
            {{ lastQueryTime }} 生成
          </span>
        </div>
      </template>
      <div class="formatted-output">{{ formattedText }}</div>
    </el-card>

    <!-- 低库存清单 (主表 — R1 max 灵魂: 直接显缺口 + 推荐补货) -->
    <el-card v-if="lowStockRows.length > 0" class="low-stock-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 低库存清单 ({{ lowStockRows.length }})</span>
          <span class="header-hint">已附推荐补货量, 一键创建请购单</span>
        </div>
      </template>
      <el-table
        :data="lowStockRows"
        size="small"
        style="width: 100%">
        <el-table-column prop="materialName" label="物料名" min-width="160">
          <template #default="{ row }">
            <span>{{ row.materialName }}</span>
            <el-tag v-if="row.category" size="small" type="info" class="spec-tag">
              {{ row.category }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当前库存" width="100" align="right">
          <template #default="{ row }">
            {{ formatQty(row.currentStock) }}{{ row.unit }}
          </template>
        </el-table-column>
        <el-table-column label="安全库存" width="100" align="right">
          <template #default="{ row }">
            {{ formatQty(row.safetyStock) }}{{ row.unit }}
          </template>
        </el-table-column>
        <el-table-column label="缺口" width="100" align="right">
          <template #default="{ row }">
            <el-tag type="danger" effect="plain" size="small">
              {{ formatQty(row.gap) }}{{ row.unit }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="建议补货" width="120" align="right">
          <template #default="{ row }">
            <el-tag type="warning" effect="plain" size="small">
              {{ formatQty(row.recommendedReplenishQty) }}{{ row.unit }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="预测下周用量" width="140" align="right">
          <template #default="{ row }">
            <el-text v-if="forecastByMaterial[row.materialTypeId]" size="small" type="info">
              {{ formatQty(forecastByMaterial[row.materialTypeId]) }}{{ row.unit }}
            </el-text>
            <el-text v-else size="small" type="info">—</el-text>
          </template>
        </el-table-column>
        <el-table-column label="价格趋势" width="140">
          <template #default="{ row }">
            <PriceMiniChart :data="priceTrendByMaterial[row.materialTypeId] || []" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="openRequisitionDialog(row)">
              一键请购
            </el-button>
            <el-button
              size="small"
              type="warning"
              data-testid="loop3-procurement-create-btn"
              @click="openProcurementDialog(row)">
              一键采购下单
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 销售预测侧栏 -->
    <el-card v-if="forecastRows.length > 0" class="forecast-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 7 天销售预测 ({{ forecastRows.length }})</span>
          <span class="header-hint">最近 14 天 AVG × 7 × 1.1 buffer</span>
        </div>
      </template>
      <el-table :data="forecastRows" size="small" style="width: 100%">
        <el-table-column prop="productName" label="产品" min-width="160" />
        <el-table-column label="过去 14 天" width="120" align="right">
          <template #default="{ row }">
            {{ formatQty(row.last14dTotal) }}{{ row.unit }}
          </template>
        </el-table-column>
        <el-table-column label="日均" width="100" align="right">
          <template #default="{ row }">
            {{ formatQty(row.dailyAvg) }}{{ row.unit }}
          </template>
        </el-table-column>
        <el-table-column label="预测 7 天" width="140" align="right">
          <template #default="{ row }">
            <el-tag type="primary" effect="plain" size="small">
              {{ formatQty(row.predicted7DayDemand) }}{{ row.unit }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- ===== 一键请购 Dialog (R1+R2 灵魂) ===== -->
    <el-dialog
      v-model="requisitionDialog.visible"
      :title="requisitionDialogTitle"
      width="640px"
      @close="resetRequisitionDialog">
      <el-form :model="requisitionDialog.form" label-width="120px">
        <el-form-item label="物料">
          <el-input :value="requisitionDialog.row?.materialName || ''" disabled />
        </el-form-item>
        <el-form-item label="物料编码">
          <el-input :value="requisitionDialog.row?.materialCode || ''" disabled />
        </el-form-item>
        <el-form-item label="当前库存">
          <el-text>
            {{ formatQty(requisitionDialog.row?.currentStock) }}{{ requisitionDialog.row?.unit }}
            / 安全 {{ formatQty(requisitionDialog.row?.safetyStock) }}{{ requisitionDialog.row?.unit }}
            (缺 {{ formatQty(requisitionDialog.row?.gap) }}{{ requisitionDialog.row?.unit }})
          </el-text>
        </el-form-item>
        <el-form-item label="请购数量" required>
          <el-input-number
            v-model="requisitionDialog.form.quantity"
            :min="0.01"
            :precision="2"
            :step="1"
            placeholder="输入请购数量"
            style="width: 220px" />
          <el-text size="small" class="form-hint">
            {{ requisitionDialog.row?.unit || '' }} (建议 {{ formatQty(requisitionDialog.row?.recommendedReplenishQty) }})
          </el-text>
        </el-form-item>
        <el-form-item label="期望到货日" required>
          <el-date-picker
            v-model="requisitionDialog.form.expectedDeliveryDate"
            type="date"
            placeholder="选择期望到货日"
            value-format="YYYY-MM-DD"
            :disabled-date="disablePastDates"
            style="width: 220px" />
        </el-form-item>
        <el-form-item label="建议供应商">
          <el-input
            v-model="requisitionDialog.form.supplierId"
            placeholder="(可选) 供应商 ID — 留空仅建议不绑定"
            style="width: 320px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="requisitionDialog.form.notes"
            placeholder="(可选) 例如: 低库存补货 / 客户大单备料"
            maxlength="200"
            show-word-limit />
        </el-form-item>
        <el-alert
          v-if="requisitionDialog.preview"
          :type="previewAlertType(requisitionDialog.preview)"
          :closable="false"
          show-icon
          class="preview-alert">
          <template #title>
            <span>{{ requisitionDialog.preview.message }}</span>
          </template>
          <template #default>
            <div v-if="requisitionDialog.preview.canDo">
              <p v-if="requisitionDialog.preview.estimatedBudget != null" class="preview-line">
 预算 ¥{{ formatQty(requisitionDialog.preview.estimatedBudget) }}
                (按 ¥{{ formatQty(requisitionDialog.preview.estimatedUnitPrice) }}/单位)
                <el-text size="small" type="info">— {{ requisitionDialog.preview.priceSource }}</el-text>
              </p>
              <p v-if="requisitionDialog.preview.supplierName" class="preview-line">
 建议供应商: {{ requisitionDialog.preview.supplierName }}
                (主数据交期 {{ requisitionDialog.preview.supplierDeliveryDays || '?' }} 天)
              </p>
              <p v-if="requisitionDialog.preview.approvalChainHint" class="preview-line">
 {{ requisitionDialog.preview.approvalChainHint }}
              </p>
            </div>
          </template>
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="requisitionDialog.visible = false">取消</el-button>
        <el-button
          :loading="requisitionDialog.previewing"
          :disabled="!canPreviewRequisition"
          @click="previewRequisition">
          预览
        </el-button>
        <el-button
          type="primary"
          :loading="requisitionDialog.submitting"
          :disabled="!requisitionDialog.canSubmit"
          @click="executeRequisition">
          确认提交
        </el-button>
      </template>
    </el-dialog>

    <!-- ===== Sprint 10 Loop 3 — 一键采购下单 Dialog ===== -->
    <el-dialog
      v-model="procurementDialog.visible"
      :title="procurementDialogTitle"
      width="680px"
      data-testid="loop3-procurement-dialog"
      @close="resetProcurementDialog">
      <el-form :model="procurementDialog.form" label-width="120px">
        <el-form-item label="物料 (R2 context)">
          <el-input :value="procurementDialog.row?.materialName || ''" disabled />
          <el-text size="small" class="form-hint">
            编码 {{ procurementDialog.row?.materialCode || procurementDialog.row?.materialTypeId }}
          </el-text>
        </el-form-item>
        <el-form-item label="当前库存">
          <el-text>
            {{ formatQty(procurementDialog.row?.currentStock) }}{{ procurementDialog.row?.unit }}
            / 安全 {{ formatQty(procurementDialog.row?.safetyStock) }}{{ procurementDialog.row?.unit }}
            (缺 {{ formatQty(procurementDialog.row?.gap) }}{{ procurementDialog.row?.unit }})
          </el-text>
        </el-form-item>
        <el-form-item label="推荐供应商" required>
          <el-select
            v-model="procurementDialog.form.supplierId"
            placeholder="请选择供应商 (Top 3 ranked by last-PO + price)"
            style="width: 100%"
            data-testid="loop3-supplier-select"
            @change="onSupplierChange">
            <el-option
              v-for="s in procurementDialog.suppliers"
              :key="s.supplierId"
              :label="`${s.supplierName}${s.recommendReason ? ' — ' + s.recommendReason : ''}${s.deliveryDays ? ' (交期 ' + s.deliveryDays + 'd)' : ''}`"
              :value="s.supplierId" />
          </el-select>
        </el-form-item>
        <el-form-item label="采购数量" required>
          <el-input-number
            v-model="procurementDialog.form.quantity"
            :min="0.01"
            :max="procurementDialog.maxQuantity || undefined"
            :precision="2"
            :step="1"
            placeholder="输入采购数量"
            data-testid="loop3-quantity-input"
            style="width: 220px" />
          <el-text size="small" class="form-hint">
            {{ procurementDialog.row?.unit || '' }}
            <span v-if="procurementDialog.maxQuantity">
              — 上限 {{ formatQty(procurementDialog.maxQuantity) }} (月均 × 3)
            </span>
          </el-text>
        </el-form-item>
        <el-form-item label="期望到货日">
          <el-date-picker
            v-model="procurementDialog.form.expectedDeliveryDate"
            type="date"
            placeholder="可选 — 默认下单后 7 天"
            value-format="YYYY-MM-DD"
            :disabled-date="disablePastDates"
            style="width: 220px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="procurementDialog.form.remark"
            placeholder="(可选) 例如: 低库存补货"
            maxlength="200"
            show-word-limit />
        </el-form-item>
        <el-alert
          v-if="procurementDialog.preview"
          :type="procurementPreviewAlertType(procurementDialog.preview)"
          :closable="false"
          show-icon
          class="preview-alert"
          data-testid="loop3-preview-alert">
          <template #title>
            <span>{{ procurementDialog.preview.message }}</span>
          </template>
          <template #default>
            <div v-if="procurementDialog.preview.canDo">
              <p v-if="procurementDialog.preview.estimatedBudget != null" class="preview-line">
 预算 ¥{{ formatQty(procurementDialog.preview.estimatedBudget) }}
                <el-text v-if="procurementDialog.preview.estimatedUnitPrice" size="small" type="info">
                  (¥{{ formatQty(procurementDialog.preview.estimatedUnitPrice) }}/单位 × {{ formatQty(procurementDialog.form.quantity) }})
                </el-text>
              </p>
              <p v-if="procurementDialog.preview.supplierName" class="preview-line">
 供应商: {{ procurementDialog.preview.supplierName }}
                <el-text v-if="procurementDialog.preview.supplierDeliveryDays" size="small" type="info">
                  — 主数据交期 {{ procurementDialog.preview.supplierDeliveryDays }} 天
                </el-text>
              </p>
              <p v-if="procurementDialog.preview.monthlyAvgUsage" class="preview-line">
 月均用量 {{ formatQty(procurementDialog.preview.monthlyAvgUsage) }}{{ procurementDialog.row?.unit }}
                — 上限 {{ formatQty(procurementDialog.preview.maxQuantity) }}{{ procurementDialog.row?.unit }} (× 3 月 buffer)
              </p>
              <p v-if="procurementDialog.preview.approvalChainHint" class="preview-line">
 {{ procurementDialog.preview.approvalChainHint }}
              </p>
            </div>
            <div v-else>
              <p v-if="procurementDialog.preview.nextActionUrl" class="preview-line">
                <el-link type="primary" underline="hover" @click="gotoPreviewNextAction(procurementDialog.preview.nextActionUrl)">
                  前往: {{ procurementDialog.preview.nextActionUrl }}
                </el-link>
              </p>
            </div>
          </template>
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="procurementDialog.visible = false">取消</el-button>
        <el-button
          :loading="procurementDialog.previewing"
          :disabled="!canPreviewProcurement"
          data-testid="loop3-preview-btn"
          @click="previewProcurement">
          预览
        </el-button>
        <el-button
          type="primary"
          :loading="procurementDialog.submitting"
          :disabled="!procurementDialog.canSubmit"
          data-testid="loop3-submit-btn"
          @click="executeProcurement">
          确认提交
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, reactive, h, defineComponent } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh, Loading } from '@element-plus/icons-vue';
import request from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';

interface LowStockRow {
  materialTypeId: string;
  materialName: string;
  materialCode?: string;
  category?: string;
  currentStock?: number;
  safetyStock?: number;
  unit?: string;
  gap?: number;
  recommendedReplenishQty?: number;
  preferredSupplier?: { id: string; name: string };
  displayHint?: string;
}

interface ForecastRow {
  productTypeId: string;
  productName?: string;
  unit?: string;
  last14dTotal?: number;
  dailyAvg?: number;
  predicted7DayDemand?: number;
  bufferApplied?: number;
}

interface PricePoint {
  yearMonth: string;
  weightedAvgPrice?: number;
  momChangePct?: number;
}

interface RequisitionPreview {
  status?: string;
  canDo?: boolean;
  message?: string;
  materialId?: string;
  materialName?: string;
  quantity?: number;
  expectedDeliveryDate?: string;
  supplierId?: string;
  supplierName?: string;
  supplierDeliveryDays?: number;
  estimatedUnitPrice?: number;
  estimatedBudget?: number;
  priceSource?: string;
  approvalChainHint?: string;
  nextActionUrl?: string;
}

interface ExecuteResponse {
  intentRecognized?: boolean;
  intentCode?: string;
  intentName?: string;
  status?: string;
  message?: string;
  formattedText?: string;
  resultData?: Record<string, unknown>;
}

const authStore = useAuthStore();

const userInput = ref('下周采购什么?');
const loading = ref(false);
const errorMessage = ref('');
const formattedText = ref('');
const lastQueryTime = ref('');
// Sprint 13 #304: dynamic result-card header — reflects the answered intent for user
// queries instead of always showing the auto-mount "下周采购计划" label.
const DEFAULT_RESULT_TITLE = '下周采购计划';
const resultTitle = ref(DEFAULT_RESULT_TITLE);

const lowStockRows = ref<LowStockRow[]>([]);
const forecastRows = ref<ForecastRow[]>([]);
const forecastByMaterial = ref<Record<string, number>>({});
const priceTrendByMaterial = ref<Record<string, PricePoint[]>>({});

const factoryId = computed(() => authStore.factoryId || 'F006');

// ===== Mini line chart (no chart lib dependency) =====

const PriceMiniChart = defineComponent({
  name: 'PriceMiniChart',
  props: {
    data: { type: Array as () => PricePoint[], required: true },
  },
  setup(props) {
    return () => {
      const pts = props.data;
      if (!pts || pts.length === 0) {
        return h('span', { style: 'color:#909399;font-size:12px' }, '—');
      }
      const w = 100, hgt = 24, pad = 2;
      const prices = pts.map(p => Number(p.weightedAvgPrice) || 0);
      const minV = Math.min(...prices);
      const maxV = Math.max(...prices);
      const range = maxV - minV || 1;
      const stepX = pts.length > 1 ? (w - 2 * pad) / (pts.length - 1) : 0;
      const points = prices.map((v, i) => {
        const x = pad + i * stepX;
        const y = hgt - pad - ((v - minV) / range) * (hgt - 2 * pad);
        return `${x.toFixed(1)},${y.toFixed(1)}`;
      }).join(' ');
      const last = prices[prices.length - 1];
      const first = prices[0];
      const trendColor = last > first ? '#f56c6c' : last < first ? '#67c23a' : '#909399';
      return h('svg', {
        width: w, height: hgt,
        style: 'vertical-align:middle',
      }, [
        h('polyline', {
          fill: 'none', stroke: trendColor, 'stroke-width': '1.5', points,
        }),
      ]);
    };
  },
});

async function callIntentExecute(input: string, intentCode?: string,
    parameters?: Record<string, unknown>, preview = false): Promise<ExecuteResponse> {
  const body: Record<string, unknown> = { userInput: input };
  if (intentCode) body.intentCode = intentCode;
  if (parameters) body.parameters = parameters;
  if (preview) body.preview = true;
  const res = await request.post<ExecuteResponse>(
    `/${factoryId.value}/ai-intents/execute`, body);
  return (res as { data: ExecuteResponse }).data;
}

async function triggerWeeklyPlan() {
  userInput.value = '下周采购什么?';
  await sendQuery(true);
}

async function sendQuery(autoTrigger = false) {
  if (!userInput.value.trim()) return;
  loading.value = true;
  errorMessage.value = '';
  if (autoTrigger) {
    formattedText.value = '';
    lowStockRows.value = [];
    forecastRows.value = [];
    forecastByMaterial.value = {};
    priceTrendByMaterial.value = {};
  }

  try {
    // 1. 低库存预警 (主路径)
    const intentCode = autoTrigger ? 'PURCHASER_WEEKLY_PLAN' : undefined;
    const response = await callIntentExecute(userInput.value, intentCode);
    formattedText.value = response.formattedText || response.message || '(无输出)';
    lastQueryTime.value = new Date().toLocaleTimeString('zh-CN');
    // Sprint 13 #304: title reflects the answered intent for user queries.
    resultTitle.value = autoTrigger
        ? DEFAULT_RESULT_TITLE
        : (response.intentName || '查询结果');

    const resultData = (response.resultData || {}) as Record<string, unknown>;
    extractLowStockRows(resultData);

    // 2. 自动 trigger 时, 并发拉销售预测
    if (autoTrigger) {
      await Promise.allSettled([
        loadSalesForecast(),
      ]);
      // 3. 顺次拉每个低库存物料的价格历史 (limit 6 避免太多调用)
      await loadPriceHistories();
    }
  } catch (err: unknown) {
    const msg = extractErrorMessage(err);
    errorMessage.value = `查询失败: ${msg}`;
    ElMessage({
      message: errorMessage.value,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    loading.value = false;
  }
}

function extractLowStockRows(resultData: Record<string, unknown>) {
  const dataLayer = (resultData['data'] as Record<string, unknown>) || resultData;
  const rows = dataLayer['warnings'] as LowStockRow[] | undefined;
  if (Array.isArray(rows)) {
    lowStockRows.value = rows;
  }
}

async function loadSalesForecast() {
  try {
    const response = await callIntentExecute('7 天销售预测', 'SALES_FORECAST_7DAY');
    const resultData = (response.resultData || {}) as Record<string, unknown>;
    const dataLayer = (resultData['data'] as Record<string, unknown>) || resultData;
    const rows = dataLayer['forecasts'] as ForecastRow[] | undefined;
    if (Array.isArray(rows)) {
      forecastRows.value = rows;
      // 索引: productTypeId → predicted (UI 暂不严区分 product vs material)
      const idx: Record<string, number> = {};
      rows.forEach(r => {
        if (r.productTypeId && r.predicted7DayDemand != null) {
          idx[r.productTypeId] = Number(r.predicted7DayDemand);
        }
      });
      forecastByMaterial.value = idx;
    }
  } catch (err) {
    console.warn('Sales forecast failed:', err);
  }
}

async function loadPriceHistories() {
  const top = lowStockRows.value.slice(0, 6);
  await Promise.allSettled(top.map(async row => {
    if (!row.materialTypeId) return;
    try {
      const response = await callIntentExecute(
        `${row.materialName} 价格历史`,
        'PRICE_HISTORY_QUERY',
        { materialTypeId: row.materialTypeId, monthsBack: 6 });
      const resultData = (response.resultData || {}) as Record<string, unknown>;
      const dataLayer = (resultData['data'] as Record<string, unknown>) || resultData;
      const monthly = dataLayer['monthlyPrices'] as PricePoint[] | undefined;
      if (Array.isArray(monthly)) {
        priceTrendByMaterial.value[row.materialTypeId] = monthly;
      }
    } catch (err) {
      console.warn(`Price history failed for ${row.materialTypeId}:`, err);
    }
  }));
}

function extractErrorMessage(err: unknown): string {
  if (err && typeof err === 'object') {
    const e = err as { response?: { data?: { message?: string } }; message?: string };
    return e.response?.data?.message || e.message || '未知错误';
  }
  return String(err);
}

function previewAlertType(preview: RequisitionPreview): 'warning' | 'info' | 'success' | 'error' {
  if (!preview.canDo) return 'warning';
  if (preview.status === 'PREVIEW') return 'success';
  return 'info';
}

function formatQty(v: number | undefined | null): string {
  if (v == null) return '0';
  const n = Number(v);
  if (Number.isNaN(n)) return String(v);
  return n.toString();
}

function disablePastDates(date: Date): boolean {
  return date < new Date(new Date().setHours(0, 0, 0, 0));
}

// ===== Requisition Dialog =====

const requisitionDialog = reactive<{
  visible: boolean;
  row: LowStockRow | null;
  form: {
    quantity: number | null;
    expectedDeliveryDate: string;
    supplierId: string;
    notes: string;
  };
  preview: RequisitionPreview | null;
  previewing: boolean;
  submitting: boolean;
  canSubmit: boolean;
}>({
  visible: false,
  row: null,
  form: { quantity: null, expectedDeliveryDate: '', supplierId: '', notes: '' },
  preview: null,
  previewing: false,
  submitting: false,
  canSubmit: false,
});

const requisitionDialogTitle = computed(() => {
  const r = requisitionDialog.row;
  return r
    ? `请购 — ${r.materialName} (${r.materialCode || r.materialTypeId})`
    : '一键请购';
});

const canPreviewRequisition = computed(() => {
  const f = requisitionDialog.form;
  return f.quantity != null && f.quantity > 0
      && f.expectedDeliveryDate && f.expectedDeliveryDate.length > 0;
});

function openRequisitionDialog(row: LowStockRow) {
  requisitionDialog.visible = true;
  requisitionDialog.row = row;
  // Default 数量 = recommendedReplenishQty (R1: 直接告诉采购员要采多少)
  requisitionDialog.form.quantity = row.recommendedReplenishQty || row.gap || null;
  // Default 期望到货日 = 7 天后 (常见 lead time)
  const d = new Date();
  d.setDate(d.getDate() + 7);
  requisitionDialog.form.expectedDeliveryDate = d.toISOString().slice(0, 10);
  requisitionDialog.form.supplierId = row.preferredSupplier?.id || '';
  requisitionDialog.form.notes = '低库存补货 — Workdesk 一键创建';
  requisitionDialog.preview = null;
  requisitionDialog.canSubmit = false;
}

function resetRequisitionDialog() {
  requisitionDialog.row = null;
  requisitionDialog.form.quantity = null;
  requisitionDialog.form.expectedDeliveryDate = '';
  requisitionDialog.form.supplierId = '';
  requisitionDialog.form.notes = '';
  requisitionDialog.preview = null;
  requisitionDialog.canSubmit = false;
}

async function previewRequisition() {
  if (!requisitionDialog.row || !canPreviewRequisition.value) {
    ElMessage.warning('请填写数量 + 期望到货日');
    return;
  }
  requisitionDialog.previewing = true;
  try {
    const response = await callIntentExecute('预览创建请购单', 'REQUISITION_CREATE', {
      materialId: requisitionDialog.row.materialTypeId,
      quantity: requisitionDialog.form.quantity,
      expectedDeliveryDate: requisitionDialog.form.expectedDeliveryDate,
      supplierId: requisitionDialog.form.supplierId || undefined,
      notes: requisitionDialog.form.notes,
    }, true);
    const previewData = (response.resultData || {}) as RequisitionPreview;
    requisitionDialog.preview = previewData;
    requisitionDialog.canSubmit = previewData.canDo === true
        && previewData.status === 'PREVIEW';
  } catch (err) {
    ElMessage({
      message: `预览失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    requisitionDialog.previewing = false;
  }
}

async function executeRequisition() {
  if (!requisitionDialog.canSubmit) {
    ElMessage.warning('请先点击 [预览] 验证可提交');
    return;
  }
  if (!requisitionDialog.row) return;
  requisitionDialog.submitting = true;
  try {
    const response = await callIntentExecute('创建请购单', 'REQUISITION_CREATE', {
      materialId: requisitionDialog.row.materialTypeId,
      quantity: requisitionDialog.form.quantity,
      expectedDeliveryDate: requisitionDialog.form.expectedDeliveryDate,
      supplierId: requisitionDialog.form.supplierId || undefined,
      notes: requisitionDialog.form.notes,
    });
    ElMessage.success(response.message || '请购单已创建');
    requisitionDialog.visible = false;
    // 刷新主表
    await triggerWeeklyPlan();
  } catch (err) {
    ElMessage({
      message: `提交失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    requisitionDialog.submitting = false;
  }
}

// ===== Sprint 10 Loop 3 — Procurement Order Create Dialog =====

interface SupplierOption {
  supplierId: string;
  supplierName: string;
  lastUsed?: string | null;
  avgPrice?: number | null;
  deliveryDays?: number | null;
  recommendReason?: string | null;
}

interface ProcurementPreview {
  status?: string;
  canDo?: boolean;
  message?: string;
  materialId?: string;
  materialName?: string;
  materialCode?: string;
  unit?: string;
  quantity?: number;
  expectedDeliveryDate?: string;
  supplierId?: string;
  supplierName?: string;
  supplierDeliveryDays?: number | null;
  monthlyAvgUsage?: number;
  maxQuantity?: number;
  quantityWithinMax?: boolean;
  estimatedUnitPrice?: number;
  estimatedBudget?: number;
  priceSource?: string;
  recommendedSuppliers?: SupplierOption[];
  approvalChainHint?: string;
  nextActionUrl?: string;
  existingPoId?: string;
  existingOrderNumber?: string;
}

const procurementDialog = reactive<{
  visible: boolean;
  row: LowStockRow | null;
  suppliers: SupplierOption[];
  maxQuantity: number | null;
  form: {
    supplierId: string;
    quantity: number | null;
    expectedDeliveryDate: string;
    remark: string;
    testRun: boolean;
  };
  preview: ProcurementPreview | null;
  previewing: boolean;
  submitting: boolean;
  canSubmit: boolean;
}>({
  visible: false,
  row: null,
  suppliers: [],
  maxQuantity: null,
  form: {
    supplierId: '',
    quantity: null,
    expectedDeliveryDate: '',
    remark: '',
    testRun: false,
  },
  preview: null,
  previewing: false,
  submitting: false,
  canSubmit: false,
});

const procurementDialogTitle = computed(() => {
  const r = procurementDialog.row;
  return r
    ? `采购下单 — ${r.materialName} (${r.materialCode || r.materialTypeId})`
    : '一键采购下单';
});

const canPreviewProcurement = computed(() => {
  const f = procurementDialog.form;
  return !!f.supplierId
      && f.quantity != null && f.quantity > 0;
});

async function openProcurementDialog(row: LowStockRow) {
  procurementDialog.visible = true;
  procurementDialog.row = row;
  procurementDialog.form.quantity = row.recommendedReplenishQty || row.gap || null;
  procurementDialog.form.supplierId = row.preferredSupplier?.id || '';
  procurementDialog.form.expectedDeliveryDate = '';
  procurementDialog.form.remark = '低库存补货 — Loop 3 一键采购下单';
  procurementDialog.form.testRun = false;
  procurementDialog.preview = null;
  procurementDialog.canSubmit = false;
  procurementDialog.suppliers = [];
  procurementDialog.maxQuantity = null;

  // Preload supplier recommendations + max via initial preview without submit
  await loadProcurementContext(row);
}

async function loadProcurementContext(row: LowStockRow) {
  if (!row.materialTypeId) return;
  // Use preview with default fields to extract recommendedSuppliers + maxQuantity
  // even if supplierId is empty, server returns recommended list when MATERIAL valid.
  try {
    // Use existing preferredSupplier if any, else fallback to first attempt with empty
    const initialSupplier = row.preferredSupplier?.id || '';
    if (!initialSupplier) {
      // Try fetching context with a dummy supplier — server returns recommendedSuppliers
      // in PREVIEW even when SUPPLIER_NOT_FOUND if MATERIAL valid (defensive: just continue)
    }
    const response = await callIntentExecute(
      '预览采购下单',
      'PROCUREMENT_ORDER_CREATE',
      {
        materialId: row.materialTypeId,
        supplierId: initialSupplier || '__placeholder__',
        quantity: row.recommendedReplenishQty || row.gap || 1,
      },
      true,
    );
    const previewData = (response.resultData || {}) as ProcurementPreview;
    if (Array.isArray(previewData.recommendedSuppliers)) {
      procurementDialog.suppliers = previewData.recommendedSuppliers;
    }
    if (previewData.maxQuantity != null) {
      procurementDialog.maxQuantity = Number(previewData.maxQuantity);
    }
  } catch (err) {
    // Defensive: dialog still usable even if context load fails
    console.warn('Procurement context preload failed:', err);
  }
}

function onSupplierChange() {
  // Reset preview when supplier changes
  procurementDialog.preview = null;
  procurementDialog.canSubmit = false;
}

function resetProcurementDialog() {
  procurementDialog.row = null;
  procurementDialog.suppliers = [];
  procurementDialog.maxQuantity = null;
  procurementDialog.form.supplierId = '';
  procurementDialog.form.quantity = null;
  procurementDialog.form.expectedDeliveryDate = '';
  procurementDialog.form.remark = '';
  procurementDialog.form.testRun = false;
  procurementDialog.preview = null;
  procurementDialog.canSubmit = false;
}

function procurementPreviewAlertType(preview: ProcurementPreview): 'warning' | 'info' | 'success' | 'error' {
  if (!preview.canDo) return 'warning';
  if (preview.status === 'PREVIEW') return 'success';
  return 'info';
}

function gotoPreviewNextAction(url: string) {
  if (url && url.startsWith('/')) {
    window.location.href = url;
  }
}

async function previewProcurement() {
  if (!procurementDialog.row || !canPreviewProcurement.value) {
    ElMessage.warning('请选供应商 + 填数量');
    return;
  }
  procurementDialog.previewing = true;
  try {
    const response = await callIntentExecute(
      '预览采购下单',
      'PROCUREMENT_ORDER_CREATE',
      {
        materialId: procurementDialog.row.materialTypeId,
        supplierId: procurementDialog.form.supplierId,
        quantity: procurementDialog.form.quantity,
        expectedDeliveryDate: procurementDialog.form.expectedDeliveryDate || undefined,
        remark: procurementDialog.form.remark,
      },
      true,
    );
    const previewData = (response.resultData || {}) as ProcurementPreview;
    procurementDialog.preview = previewData;
    procurementDialog.canSubmit = previewData.canDo === true
        && previewData.status === 'PREVIEW';
    if (Array.isArray(previewData.recommendedSuppliers) && procurementDialog.suppliers.length === 0) {
      procurementDialog.suppliers = previewData.recommendedSuppliers;
    }
    if (previewData.maxQuantity != null) {
      procurementDialog.maxQuantity = Number(previewData.maxQuantity);
    }
  } catch (err) {
    ElMessage({
      message: `预览失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    procurementDialog.previewing = false;
  }
}

async function executeProcurement() {
  if (!procurementDialog.canSubmit) {
    ElMessage.warning('请先点击 [预览] 验证可提交');
    return;
  }
  if (!procurementDialog.row) return;
  procurementDialog.submitting = true;
  try {
    const response = await callIntentExecute(
      '创建采购订单',
      'PROCUREMENT_ORDER_CREATE',
      {
        materialId: procurementDialog.row.materialTypeId,
        supplierId: procurementDialog.form.supplierId,
        quantity: procurementDialog.form.quantity,
        expectedDeliveryDate: procurementDialog.form.expectedDeliveryDate || undefined,
        remark: procurementDialog.form.remark,
        testRun: procurementDialog.form.testRun,
      },
    );
    const resultData = (response.resultData || {}) as Record<string, unknown>;
    const data = (resultData['data'] as Record<string, unknown>) || resultData;
    const status = (data['status'] || resultData['status']) as string | undefined;
    // R4 idempotent — backend returns DUPLICATE with existingPoId
    if (status === 'DUPLICATE') {
      const existingOrder = (data['existingOrderNumber'] || data['existingPoId']) as string;
      const url = (data['nextActionUrl'] || '/purchase/orders') as string;
      ElMessage({
        message: response.message || `已有近期采购单 ${existingOrder}`,
        type: 'warning',
        duration: 0,
        showClose: true,
      });
      // Soft navigate hint
      setTimeout(() => {
        if (url) window.location.href = url;
      }, 1500);
      procurementDialog.visible = false;
      return;
    }
    ElMessage.success(response.message || '采购单已创建');
    procurementDialog.visible = false;
    // 刷新主表
    await triggerWeeklyPlan();
  } catch (err) {
    ElMessage({
      message: `提交失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    procurementDialog.submitting = false;
  }
}

// Auto-trigger on mount
onMounted(() => {
  void triggerWeeklyPlan();
});
</script>

<style scoped>
.purchaser-workdesk {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.workdesk-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: linear-gradient(135deg, #f0f9ff, #e0f2fe);
  padding: 12px 20px;
  border-radius: 8px;
}

.header-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 18px;
  font-weight: 600;
}

.emoji {
  font-size: 24px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-hint {
  font-size: 12px;
  color: #909399;
  font-weight: normal;
}

.chat-input {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.chat-input .el-input {
  flex: 1;
}

.loading-content {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #606266;
  padding: 8px;
}

.error-alert {
  margin: 0;
}

.formatted-output {
  font-size: 14px;
  line-height: 1.8;
  color: #303133;
  white-space: pre-wrap;
}

.spec-tag {
  margin-left: 6px;
}

.form-hint {
  margin-left: 8px;
  color: #909399;
}

.preview-alert {
  margin-top: 8px;
}

.preview-line {
  margin: 6px 0;
  font-size: 13px;
}
</style>
