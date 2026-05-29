<!--
  ProductionManagerWorkdesk.vue — Sprint 10 Loop 5 生产经理 Workdesk (2026-05-21)

  F006 真场景: 生产经理张工程师早 8am 打开 Cretas, 说一句
  "今天要起产什么?" 系统 5 秒内输出按净缺量排序的产品清单 + 推荐起产量 +
  推荐生产线. 一键 confirm 即创建 PLANNED 批次, 跳转批次详情打印工单.

  vs HJ 模式: 销售订单列表 → 一个个看缺货量 → 手算扣在产 → 进生产模块新建批次 →
  填表选生产线 (5 屏 10 分钟).
  Cretas Workdesk: 1 屏 30 秒.

  架构: 复用 /ai-intents/execute POST 端点
        Path A keyword: "今天要起产什么" → PRODUCTION_DEMAND_ANALYSIS (WORKDESK)
        Path B LLM-routed: "什么订单缺货要做" → SEMANTIC/LLM 兜底 → 同 intent
        显式 intent_code = PRODUCTION_DEMAND_QUERY → Tool 直接执行

  防呆 4 位一体 (per .claude/rules/fool-proof-design.md):
        R1 max: dialog 显推荐量 + max + 净缺量, input :max= 防超
        R2 context: dialog 标题 = 起产 — {productName} ({productTypeId})
        R3 dropdown: productionLine el-select 限 4 枚举
        R4 idempotent: preview 返 DUPLICATE → confirm 跳已有 batchId

  WRITE 行动: production_batch_create Tool + Preview 路径
              请求体含 testRun (从 query string 或 button 控制, 默认 false)
-->
<template>
  <div class="production-manager-workdesk">
    <!-- Header -->
    <div class="workdesk-header">
      <div class="header-title">
 <span class="emoji"></span>
        <span class="title-text">生产经理工作台</span>
        <el-tag size="small" type="info">Sprint 10 Loop 5 (2026-05-21)</el-tag>
      </div>
      <div class="header-actions">
        <el-button :loading="loading" :icon="Refresh" @click="triggerDemandQuery">
          重新分析
        </el-button>
      </div>
    </div>

    <!-- AI Chat 输入区 -->
    <el-card class="chat-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 与 AI 对话</span>
          <span class="header-hint">默认: "今天要起产什么?" — 或输入其他问题</span>
        </div>
      </template>
      <div class="chat-input">
        <el-input
          v-model="userInput"
          type="textarea"
          :rows="2"
          placeholder="例如: 今天要起产什么? / 什么订单缺货要做? / 排产建议"
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
        <span>AI 正在聚合销售订单 + 在产批次, 计算净缺量 + 推荐起产量, 预计 3-8 秒...</span>
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

    <!-- AI 输出 (formattedText / message) -->
    <el-card v-if="formattedText" class="result-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> {{ resultTitle }}</span>
          <span class="header-hint" v-if="lastQueryTime">
            {{ lastQueryTime }} 生成
          </span>
        </div>
      </template>
      <div class="formatted-output" v-html="renderedFormattedText"></div>
    </el-card>

    <!-- 待产产品卡片清单 -->
    <el-card v-if="demands.length > 0" class="demands-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 待产产品清单 ({{ demands.length }})</span>
          <span class="header-hint">净缺量 = 订单总量 - 已发货 - 在产</span>
        </div>
      </template>
      <div class="demands-grid">
        <div
          v-for="d in demands"
          :key="d.productTypeId"
          class="demand-card"
          :class="urgencyClass(d.netShortage)">
          <div class="demand-header">
            <span class="urgency-emoji">{{ urgencyEmoji(d.netShortage) }}</span>
            <div class="demand-name">{{ d.productName || '(未命名)' }}</div>
            <el-tag size="small" type="warning">
              缺 {{ formatNum(d.netShortage) }} {{ d.unit }}
            </el-tag>
          </div>
          <div class="demand-info">
            <div class="info-row">
              <span class="label">订单总量:</span>
              <span class="value">{{ formatNum(d.totalDemand) }} {{ d.unit }}</span>
            </div>
            <div class="info-row">
              <span class="label">在产:</span>
              <span class="value">{{ formatNum(d.inProgressQuantity) }} {{ d.unit }}</span>
            </div>
            <div class="info-row">
              <span class="label">推荐起产:</span>
              <span class="value highlight">{{ formatNum(d.recommendedQuantity) }} {{ d.unit }}</span>
            </div>
            <div class="info-row">
              <span class="label">推荐产线:</span>
              <span class="value">{{ lineLabel(d.recommendedLine) }}</span>
            </div>
            <div class="info-row">
              <span class="label">涉及订单:</span>
              <span class="value">{{ d.orderCount }} 行</span>
            </div>
          </div>
          <div class="demand-actions">
            <el-button size="small" type="primary" @click="openCreateBatchDialog(d)">
 一键起产
            </el-button>
            <el-button size="small" @click="gotoOrderList(d)">
 查涉及订单
            </el-button>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 起产 Dialog (R1 max + R2 context + R3 dropdown + R4 idem) -->
    <el-dialog
      v-model="batchDialog.visible"
      :title="dialogTitle"
      width="640px"
      @close="resetBatchDialog">
      <el-form :model="batchDialog.form" label-width="120px">
        <el-form-item label="产品">
          <el-input :value="batchDialog.productLabel" disabled />
        </el-form-item>
        <el-form-item label="当前净缺量">
          <el-input :value="formatNum(batchDialog.netShortage) + ' ' + batchDialog.unit" disabled>
            <template #append>已扣已发货 + 在产</template>
          </el-input>
        </el-form-item>
        <el-form-item label="实际起产量" required>
          <el-input-number
            v-model="batchDialog.form.quantity"
            :min="0"
            :max="batchDialog.maxAllowed"
            :precision="2"
            :step="1"
            controls-position="right"
            style="width: 240px" />
          <span class="form-hint">
            最多 {{ formatNum(batchDialog.maxAllowed) }} {{ batchDialog.unit }}
            (= 净缺量)
          </span>
        </el-form-item>
        <el-form-item label="排程开工日" required>
          <el-date-picker
            v-model="batchDialog.form.scheduledDate"
            type="date"
            value-format="YYYY-MM-DD"
            :disabled-date="(d: Date) => d.getTime() < Date.now() - 86400000"
            placeholder="选择开工日 (默认明天)"
            style="width: 240px" />
        </el-form-item>
        <el-form-item label="生产线" required>
          <el-select v-model="batchDialog.form.productionLine" style="width: 240px">
            <el-option label="专线 A (DEDICATED_LINE_A)" value="DEDICATED_LINE_A" />
            <el-option label="专线 B (DEDICATED_LINE_B)" value="DEDICATED_LINE_B" />
            <el-option label="共用产线 (SHARED_LINE)" value="SHARED_LINE" />
            <el-option label="其他 (OTHER)" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="batchDialog.form.notes"
            type="textarea"
            :rows="2"
            placeholder="(可选) 排产备注"
            maxlength="500"
            show-word-limit />
        </el-form-item>
        <!-- Preview 结果 -->
        <el-alert
          v-if="batchDialog.preview"
          :type="previewAlertType(batchDialog.preview)"
          :closable="false"
          show-icon
          class="preview-alert">
          <template #title>
            <span>{{ batchDialog.preview.message }}</span>
          </template>
          <template #default>
            <div v-if="batchDialog.preview.status === 'DUPLICATE'" class="preview-actions">
              <el-button size="small" type="primary" @click="gotoExistingBatch">
                查看已有批次 {{ batchDialog.preview.existingBatchNumber }}
              </el-button>
            </div>
          </template>
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="batchDialog.visible = false">取消</el-button>
        <el-button
          :loading="batchDialog.previewing"
          @click="previewBatchCreate">
          预览
        </el-button>
        <el-button
          type="primary"
          :loading="batchDialog.submitting"
          :disabled="!batchDialog.canSubmit"
          @click="executeBatchCreate">
          确认创建
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Refresh, Loading } from '@element-plus/icons-vue';
import request from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';

interface ProductDemand {
  productTypeId: string;
  productName?: string;
  unit: string;
  totalDemand: number;
  inProgressQuantity: number;
  netShortage: number;
  recommendedQuantity: number;
  recommendedLine: string;
  orderCount: number;
  orderRefs?: Array<{
    salesOrderId: string;
    orderNumber: string;
    customerName?: string;
    pendingQuantity: number;
    requiredDeliveryDate?: string;
  }>;
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

interface PreviewResult {
  status?: string;
  canDo?: boolean;
  message?: string;
  existingBatchId?: number;
  existingBatchNumber?: string;
  maxAllowed?: number;
  netShortage?: number;
}

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();

// testRun: ?testRun=1 in URL → playwright will set this true to tag created data
const testRun = computed(() => {
  const v = route.query.testRun;
  return v === '1' || v === 'true';
});

const userInput = ref('今天要起产什么?');
const loading = ref(false);
const errorMessage = ref('');
const formattedText = ref('');
const demands = ref<ProductDemand[]>([]);
const lastQueryTime = ref('');
// Sprint 13 #304: dynamic result-card header — reflects the answered intent for user
// queries instead of always showing the auto-mount "排产建议清单" label.
const DEFAULT_RESULT_TITLE = '排产建议清单';
const resultTitle = ref(DEFAULT_RESULT_TITLE);

const factoryId = computed(() => authStore.factoryId || 'F006');

const renderedFormattedText = computed(() => {
  if (!formattedText.value) return '';
  return formattedText.value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\n/g, '<br/>');
});

function urgencyClass(net: number) {
  if (net >= 100) return 'urgency-high';
  if (net >= 20) return 'urgency-med';
  return 'urgency-low';
}

function urgencyEmoji(net: number) {
 if (net >= 100) return '';
 if (net >= 20) return '';
 return '';
}

function formatNum(v: number | undefined | null): string {
  if (v == null) return '0';
  const n = typeof v === 'number' ? v : Number(v);
  if (Number.isNaN(n)) return '0';
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

function lineLabel(code: string): string {
  switch (code) {
    case 'DEDICATED_LINE_A': return '专线 A';
    case 'DEDICATED_LINE_B': return '专线 B';
    case 'SHARED_LINE': return '共用产线';
    case 'OTHER': return '其他';
    default: return code || '(未指定)';
  }
}

function previewAlertType(preview: PreviewResult): 'warning' | 'success' | 'info' | 'error' {
  if (preview.status === 'DUPLICATE' || preview.status === 'INVALID') return 'warning';
  if (preview.status === 'PREVIEW') return 'success';
  return 'info';
}

async function callIntentExecute(input: string, intentCode?: string,
    parameters?: Record<string, unknown>, preview = false)
    : Promise<ExecuteResponse> {
  const body: Record<string, unknown> = { userInput: input };
  if (intentCode) body.intentCode = intentCode;
  if (parameters) body.parameters = parameters;
  if (preview) body.preview = true;
  const res = await request.post<ExecuteResponse>(
    `/${factoryId.value}/ai-intents/execute`, body);
  return (res as { data: ExecuteResponse }).data;
}

async function triggerDemandQuery() {
  userInput.value = '今天要起产什么?';
  await sendQuery(true);
}

async function sendQuery(forceDemand = false) {
  if (!userInput.value.trim()) return;
  loading.value = true;
  errorMessage.value = '';
  formattedText.value = '';
  demands.value = [];

  try {
    const intentCode = forceDemand ? 'PRODUCTION_DEMAND_QUERY' : undefined;
    const response = await callIntentExecute(userInput.value, intentCode);
    formattedText.value = response.formattedText || response.message
        || '(无输出)';
    lastQueryTime.value = new Date().toLocaleTimeString('zh-CN');
    // Sprint 13 #304: title reflects the answered intent for user queries.
    resultTitle.value = forceDemand
        ? DEFAULT_RESULT_TITLE
        : (response.intentName || '查询结果');

    // 解析 resultData
    extractDemandsFromResult(response.resultData || {});
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

function extractDemandsFromResult(resultData: Record<string, unknown>) {
  // Tool 直接执行: resultData.data.products
  // Skill 编排: resultData.steps.production_demand_query.data.products
  const inner = (resultData['data'] as Record<string, unknown>) || resultData;
  const list = inner['products'] as ProductDemand[] | undefined;
  if (Array.isArray(list)) {
    demands.value = list;
    return;
  }
  // Skill 兜底
  const steps = resultData['steps'] as Record<string, unknown> | undefined;
  if (steps) {
    const step = steps['production_demand_query'] as Record<string, unknown> | undefined;
    if (step) {
      const stepInner = (step['data'] as Record<string, unknown>) || step;
      const sList = stepInner['products'] as ProductDemand[] | undefined;
      if (Array.isArray(sList)) demands.value = sList;
    }
  }
}

function extractErrorMessage(err: unknown): string {
  if (err && typeof err === 'object') {
    const e = err as { response?: { data?: { message?: string } }; message?: string };
    return e.response?.data?.message || e.message || '未知错误';
  }
  return String(err);
}

function gotoOrderList(d: ProductDemand) {
  router.push(`/sales/orders?productTypeId=${encodeURIComponent(d.productTypeId)}`);
}

// ===== 起产 Dialog =====

const batchDialog = reactive({
  visible: false,
  productTypeId: '',
  productName: '',
  productLabel: '',
  unit: 'kg',
  netShortage: 0,
  maxAllowed: 0,
  salesOrderId: '' as string | undefined,
  form: {
    quantity: 0,
    scheduledDate: '',
    productionLine: 'DEDICATED_LINE_A',
    notes: '',
  },
  preview: null as PreviewResult | null,
  previewing: false,
  submitting: false,
  canSubmit: false,
});

const dialogTitle = computed(() =>
  batchDialog.productName
    ? `起产 — ${batchDialog.productName} (${batchDialog.productTypeId})`
    : `起产 — ${batchDialog.productTypeId}`
);

function tomorrow(): string {
  const t = new Date(Date.now() + 86400000);
  return t.toISOString().split('T')[0];
}

function openCreateBatchDialog(d: ProductDemand) {
  batchDialog.visible = true;
  batchDialog.productTypeId = d.productTypeId;
  batchDialog.productName = d.productName || '';
  batchDialog.productLabel = `${d.productName || '(未命名)'} (${d.productTypeId})`;
  batchDialog.unit = d.unit;
  batchDialog.netShortage = Number(d.netShortage);
  batchDialog.maxAllowed = Number(d.netShortage);
  batchDialog.salesOrderId = d.orderRefs?.[0]?.salesOrderId;
  batchDialog.form.quantity = Number(d.recommendedQuantity);
  batchDialog.form.scheduledDate = tomorrow();
  batchDialog.form.productionLine = d.recommendedLine || 'DEDICATED_LINE_A';
  batchDialog.form.notes = '';
  batchDialog.preview = null;
  batchDialog.canSubmit = false;
}

function resetBatchDialog() {
  batchDialog.productTypeId = '';
  batchDialog.productName = '';
  batchDialog.productLabel = '';
  batchDialog.netShortage = 0;
  batchDialog.maxAllowed = 0;
  batchDialog.salesOrderId = undefined;
  batchDialog.form.quantity = 0;
  batchDialog.form.scheduledDate = '';
  batchDialog.form.productionLine = 'DEDICATED_LINE_A';
  batchDialog.form.notes = '';
  batchDialog.preview = null;
  batchDialog.canSubmit = false;
}

async function previewBatchCreate() {
  if (!batchDialog.form.quantity || batchDialog.form.quantity <= 0) {
    ElMessage.warning('请输入正整数起产量');
    return;
  }
  if (batchDialog.form.quantity > batchDialog.maxAllowed) {
    ElMessage.warning(`起产量不能超过净缺量 ${batchDialog.maxAllowed}`);
    return;
  }
  if (!batchDialog.form.scheduledDate) {
    ElMessage.warning('请选择排程开工日');
    return;
  }
  batchDialog.previewing = true;
  try {
    const params: Record<string, unknown> = {
      productTypeId: batchDialog.productTypeId,
      productName: batchDialog.productName,
      quantity: batchDialog.form.quantity,
      unit: batchDialog.unit,
      scheduledDate: batchDialog.form.scheduledDate,
      productionLine: batchDialog.form.productionLine,
      notes: batchDialog.form.notes,
      testRun: testRun.value,
    };
    if (batchDialog.salesOrderId) params.salesOrderId = batchDialog.salesOrderId;
    const data = await callIntentExecute('预览创建生产批次', 'PRODUCTION_BATCH_CREATE', params, true);
    const previewData = (data.resultData || {}) as PreviewResult;
    batchDialog.preview = previewData;
    batchDialog.canSubmit = previewData.canDo !== false
        && previewData.status === 'PREVIEW';
  } catch (err) {
    ElMessage({
      message: `预览失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    batchDialog.previewing = false;
  }
}

async function executeBatchCreate() {
  if (!batchDialog.canSubmit) {
    ElMessage.warning('请先点击 [预览] 验证可提交');
    return;
  }
  batchDialog.submitting = true;
  try {
    const params: Record<string, unknown> = {
      productTypeId: batchDialog.productTypeId,
      productName: batchDialog.productName,
      quantity: batchDialog.form.quantity,
      unit: batchDialog.unit,
      scheduledDate: batchDialog.form.scheduledDate,
      productionLine: batchDialog.form.productionLine,
      notes: batchDialog.form.notes,
      testRun: testRun.value,
    };
    if (batchDialog.salesOrderId) params.salesOrderId = batchDialog.salesOrderId;
    const data = await callIntentExecute('创建生产批次', 'PRODUCTION_BATCH_CREATE', params, false);
    const result = (data.resultData || {}) as {
      status?: string; message?: string; batchId?: number; batchNumber?: string;
    };
    const msg = result.message || data.message || '生产批次已创建';
    if (result.status === 'CREATED') {
      ElMessage.success(msg);
      batchDialog.visible = false;
      // 刷新需求列表
      await triggerDemandQuery();
    } else if (result.status === 'DUPLICATE') {
      ElMessage({
        message: msg,
        type: 'warning',
        duration: 0,
        showClose: true,
      });
    } else {
      ElMessage({
        message: msg,
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  } catch (err) {
    ElMessage({
      message: `创建失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    batchDialog.submitting = false;
  }
}

function gotoExistingBatch() {
  if (batchDialog.preview?.existingBatchId) {
    router.push(`/production/batches/${batchDialog.preview.existingBatchId}`);
    batchDialog.visible = false;
  }
}

// Auto-trigger on mount
onMounted(() => {
  void triggerDemandQuery();
});
</script>

<style scoped>
.production-manager-workdesk {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.workdesk-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: linear-gradient(135deg, #fff7e6, #fff1d3);
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

.demands-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 12px;
}

.demand-card {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px;
  background: #fff;
  transition: box-shadow .2s;
}

.demand-card:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, .12);
}

.demand-card.urgency-high {
  border-left: 4px solid #f56c6c;
}
.demand-card.urgency-med {
  border-left: 4px solid #e6a23c;
}
.demand-card.urgency-low {
  border-left: 4px solid #67c23a;
}

.demand-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.urgency-emoji {
  font-size: 18px;
}

.demand-name {
  font-weight: 600;
  flex: 1;
  color: #303133;
}

.demand-info {
  font-size: 13px;
  color: #606266;
  margin-bottom: 12px;
}

.info-row {
  display: flex;
  justify-content: space-between;
  padding: 2px 0;
}

.info-row .label {
  color: #909399;
}

.info-row .value {
  color: #303133;
  font-weight: 500;
}

.info-row .value.highlight {
  color: #f56c6c;
  font-weight: 700;
}

.demand-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.form-hint {
  margin-left: 12px;
  font-size: 12px;
  color: #909399;
}

.preview-alert {
  margin-top: 8px;
}

.preview-actions {
  margin-top: 8px;
}
</style>
