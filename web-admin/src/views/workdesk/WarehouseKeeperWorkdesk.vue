<!--
  WarehouseKeeperWorkdesk.vue — Sprint 8 P4a 仓管员 Workdesk V1

  F006 真场景: 仓管员张师傅早上 8 am 打开 Cretas, 默认问 "今天要收什么货?"
  系统聚合今日 + 明日 expected delivery date 的 PO + Requisition pending receiving,
  每行带 R1 max 边界 (已订 - 已收 = 剩余 + 30% 超收上限).

  vs HJ 模式: 仓管员要点 5 个菜单 (采购订单 / 请购单 / 采购入库 / 到货登记 / 扫码).
  Cretas: 1 屏 30 秒 + AI 智能输出 + 一键扫码任务.

  客户原话 (F006 张权): "做仓管的他年纪都比较大文化素质很低, 你不能太依赖他们,
  最好的方法就是你告诉他这个东西你要收多少就行了" — 本页面的核心设计原则.

  架构: 复用 /ai-intents/execute POST 端点 (mirror P1/P2/P3 Workdesk).
  - 进入页面自动 trigger 'MATERIAL_TODAY_RECEIVING_QUERY' (不让仓管员自己输入)
  - 临期建议侧栏: 触发 'MATERIAL_DISPOSAL_RECOMMENDATION'
  - 快速收货 dialog: 调用 receive_with_limit Preview → 确认 → execute
  - 一键扫码: 调用 pda_scan_task_generate → 显二维码 (文本 token MVP)

  防呆: 4 位一体. R1 max 灵魂体现在 receive dialog 全程显边界.
-->
<template>
  <div class="warehouse-keeper-workdesk">
    <!-- Header -->
    <div class="workdesk-header">
      <div class="header-title">
        <span class="emoji">🏭</span>
        <span class="title-text">仓管员工作台</span>
        <el-tag size="small" type="info">Sprint 8 P4a (2026-05-20)</el-tag>
      </div>
      <div class="header-actions">
        <el-button :loading="loading" :icon="Refresh" @click="triggerTodayQuery">
          重新加载
        </el-button>
      </div>
    </div>

    <!-- AI Chat 输入区 -->
    <el-card class="chat-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>💬 与 AI 对话</span>
          <span class="header-hint">默认查询: "今天要收什么货?" — 也可输入其他问题</span>
        </div>
      </template>
      <div class="chat-input">
        <el-input
          v-model="userInput"
          type="textarea"
          :rows="2"
          placeholder="例如: 今天要收什么货? / 临期物料建议 / 今天哪些要质检?"
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
        <span>AI 正在聚合今日待收清单 + 临期建议, 预计 3-5 秒...</span>
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
          <span>📋 今日待收清单</span>
          <span class="header-hint" v-if="lastQueryTime">
            {{ lastQueryTime }} 生成
          </span>
        </div>
      </template>
      <div class="formatted-output">{{ formattedText }}</div>
    </el-card>

    <!-- 待收行清单 (R1 max 灵魂: 直接显已订/已收/可入) -->
    <el-card v-if="receivingRows.length > 0" class="receiving-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>📦 待收行项目 ({{ receivingRows.length }})</span>
          <span class="header-hint">已订/已收/还可入 直接告诉你要收多少</span>
        </div>
        <div class="header-actions-inline">
          <el-button
            size="small"
            type="primary"
            :disabled="selectedRows.length === 0"
            @click="generateScanTask">
            📲 一键扫码 ({{ selectedRows.length }})
          </el-button>
        </div>
      </template>
      <el-table
        :data="receivingRows"
        size="small"
        style="width: 100%"
        @selection-change="onSelectionChange">
        <el-table-column type="selection" width="48" />
        <el-table-column prop="orderNumber" label="PO 单号" width="140" />
        <el-table-column prop="supplierName" label="供应商" min-width="120" />
        <el-table-column prop="materialName" label="物料名" min-width="160">
          <template #default="{ row }">
            <span>{{ row.materialName }}</span>
            <el-tag v-if="row.specification" size="small" type="info" class="spec-tag">
              {{ row.specification }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="已订" width="90" align="right">
          <template #default="{ row }">
            {{ formatQty(row.orderedQuantity) }}{{ row.unit }}
          </template>
        </el-table-column>
        <el-table-column label="已收" width="90" align="right">
          <template #default="{ row }">
            {{ formatQty(row.receivedQuantity) }}{{ row.unit }}
          </template>
        </el-table-column>
        <el-table-column label="还要收" width="120" align="right">
          <template #default="{ row }">
            <el-tag type="warning" effect="plain" size="small">
              {{ formatQty(row.pendingQuantity) }}{{ row.unit }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="超收上限" width="120" align="right">
          <template #default="{ row }">
            <el-text size="small" type="info">
              ≤ {{ formatQty(row.remainingCap) }}{{ row.unit }}
            </el-text>
          </template>
        </el-table-column>
        <el-table-column label="预计到货" width="120">
          <template #default="{ row }">
            <span v-if="row.isOverdue" class="overdue-text">
              {{ row.expectedDeliveryDate }} (已延)
            </span>
            <span v-else>{{ row.expectedDeliveryDate }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="openReceiveDialog(row)">
              快速收货
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 临期物料处置建议侧栏 -->
    <el-card v-if="disposalRecommendations.length > 0" class="disposal-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>⚠️ 临期物料处置建议 ({{ disposalRecommendations.length }})</span>
          <span class="header-hint">系统按规则给出推荐行动</span>
        </div>
      </template>
      <el-table :data="disposalRecommendations" size="small" style="width: 100%">
        <el-table-column prop="materialName" label="物料名" min-width="140" />
        <el-table-column prop="batchNumber" label="批次号" min-width="140" />
        <el-table-column label="剩余天数" width="100">
          <template #default="{ row }">
            <el-tag
              :type="row.daysRemaining != null && row.daysRemaining <= 2 ? 'danger' : 'warning'"
              size="small">
              {{ row.isExpired ? '已过期' : (row.daysRemaining + ' 天') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="100" align="right">
          <template #default="{ row }">
            {{ formatQty(row.currentQuantity) }}
          </template>
        </el-table-column>
        <el-table-column prop="recommendedActionDisplay" label="推荐行动" min-width="160" />
        <el-table-column prop="recommendedReason" label="原因" min-width="200" />
      </el-table>
    </el-card>

    <!-- 待质检批次 -->
    <el-card v-if="qcInspecting.length > 0" class="qc-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>🔬 待质检批次 ({{ qcInspecting.length }})</span>
          <span class="header-hint">通知质量主管尽快验收</span>
        </div>
      </template>
      <el-table :data="qcInspecting" size="small" style="width: 100%">
        <el-table-column prop="materialName" label="物料名" min-width="140" />
        <el-table-column prop="batchNumber" label="批次号" min-width="140" />
        <el-table-column prop="receiptDate" label="入库日期" width="120" />
        <el-table-column label="数量" width="100" align="right">
          <template #default="{ row }">
            {{ formatQty(row.receiptQuantity) }}{{ row.quantityUnit }}
          </template>
        </el-table-column>
        <el-table-column prop="daysWaiting" label="等待天数" width="100">
          <template #default="{ row }">
            <el-tag :type="row.daysWaiting > 1 ? 'warning' : 'info'" size="small">
              {{ row.daysWaiting }} 天
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="storageLocation" label="库位" min-width="120" />
      </el-table>
    </el-card>

    <!-- ===== 快速收货 Dialog (R1 max 灵魂体现) ===== -->
    <el-dialog
      v-model="receiveDialog.visible"
      :title="receiveDialogTitle"
      width="560px"
      @close="resetReceiveDialog">
      <el-form :model="receiveDialog.form" label-width="120px">
        <el-form-item label="PO 单号">
          <el-input :value="receiveDialog.row?.orderNumber || ''" disabled />
        </el-form-item>
        <el-form-item label="供应商">
          <el-input :value="receiveDialog.row?.supplierName || ''" disabled />
        </el-form-item>
        <el-form-item label="物料">
          <el-input :value="receiveDialog.row?.materialName || ''" disabled />
        </el-form-item>
        <!-- R1 max: 直接显已订/已收/剩余/超收上限 -->
        <el-form-item label="已订 / 已收">
          <el-text>
            {{ formatQty(receiveDialog.row?.orderedQuantity) }}{{ receiveDialog.row?.unit }}
            / {{ formatQty(receiveDialog.row?.receivedQuantity) }}{{ receiveDialog.row?.unit }}
          </el-text>
        </el-form-item>
        <el-form-item label="还可入">
          <div class="qty-hint-block">
            <el-text size="small">
              还要收 <b>{{ formatQty(receiveDialog.row?.pendingQuantity) }}</b>
              {{ receiveDialog.row?.unit }}
              · 30% 超收上限 = {{ formatQty(receiveDialog.row?.remainingCap) }}{{ receiveDialog.row?.unit }}
            </el-text>
          </div>
        </el-form-item>
        <el-form-item label="本次实收" required>
          <el-input-number
            v-model="receiveDialog.form.receivedQty"
            :min="0"
            :max="maxAllowedReceive"
            :precision="2"
            :step="1"
            placeholder="输入本次实收数量"
            style="width: 220px" />
          <el-text size="small" class="form-hint">
            {{ receiveDialog.row?.unit || '' }} (上限 {{ formatQty(maxAllowedReceive) }})
          </el-text>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="receiveDialog.form.remark"
            placeholder="(可选) 例如: 到货品相好 / 部分包装破损"
            maxlength="200"
            show-word-limit />
        </el-form-item>
        <el-alert
          v-if="receiveDialog.preview"
          :type="previewAlertType(receiveDialog.preview)"
          :closable="false"
          show-icon
          class="preview-alert">
          <template #title>
            <span>{{ receiveDialog.preview.message }}</span>
          </template>
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="receiveDialog.visible = false">取消</el-button>
        <el-button
          :loading="receiveDialog.previewing"
          :disabled="!canPreviewReceive"
          @click="previewReceive">
          预览边界
        </el-button>
        <el-button
          type="primary"
          :loading="receiveDialog.submitting"
          :disabled="!receiveDialog.canSubmit || isOverLimit"
          @click="executeReceive">
          确认提交
        </el-button>
      </template>
    </el-dialog>

    <!-- ===== PDA 扫码任务 Dialog ===== -->
    <el-dialog
      v-model="scanTaskDialog.visible"
      title="📲 PDA / 手机扫码任务"
      width="540px">
      <el-result
        v-if="scanTaskDialog.task"
        icon="success"
        title="扫码任务已生成">
        <template #sub-title>
          <p>共 <b>{{ scanTaskDialog.task.totalItems }}</b> 行待扫,
            Token <b>{{ scanTaskDialog.task.token }}</b></p>
          <p class="task-expire">有效期 {{ scanTaskDialog.task.ttlMin }} 分钟
            (至 {{ scanTaskDialog.task.expiresAt }})</p>
        </template>
        <template #extra>
          <div class="qr-payload-box">
            <el-text size="small" type="info">QR Payload (PDA 扫描此 URL):</el-text>
            <pre class="qr-payload-text">{{ scanTaskDialog.task.qrPayload }}</pre>
          </div>
          <el-text size="small">
            <strong>下一步</strong>: 用 PDA / 手机扫码 app 扫描上面的 URL,
            任务会自动加载. 扫码完成后一键提交即转入入库流程.
          </el-text>
        </template>
      </el-result>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh, Loading } from '@element-plus/icons-vue';
import request from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';

interface ReceivingRow {
  poId: string;
  orderNumber: string;
  supplierName?: string;
  expectedDeliveryDate?: string;
  isOverdue?: boolean;
  lineId: number;
  materialTypeId: string;
  materialName: string;
  specification?: string;
  orderedQuantity: number;
  receivedQuantity: number;
  pendingQuantity: number;
  remainingCap: number;
  overReceiveLimit: number;
  unit?: string;
  displayHint?: string;
}

interface DisposalRow {
  batchId: string;
  batchNumber?: string;
  materialName?: string;
  expireDate?: string;
  daysRemaining?: number | null;
  isExpired?: boolean;
  currentQuantity?: number;
  recommendedAction?: string;
  recommendedActionDisplay?: string;
  recommendedReason?: string;
}

interface QcRow {
  batchId: string;
  batchNumber?: string;
  materialName?: string;
  receiptDate?: string;
  receiptQuantity?: number;
  quantityUnit?: string;
  daysWaiting?: number | null;
  storageLocation?: string;
}

interface ScanTask {
  taskId: string;
  token: string;
  expiresAt: string;
  ttlMin: number;
  totalItems: number;
  qrPayload: string;
}

interface ExecuteResponse {
  intentRecognized?: boolean;
  intentCode?: string;
  status?: string;
  message?: string;
  formattedText?: string;
  resultData?: Record<string, unknown>;
}

interface PreviewState {
  status?: string;
  canDo?: boolean;
  message?: string;
  remainingCap?: number;
  pending?: number;
  warningIfOver?: string;
}

const authStore = useAuthStore();

const userInput = ref('今天要收什么货?');
const loading = ref(false);
const errorMessage = ref('');
const formattedText = ref('');
const lastQueryTime = ref('');

const receivingRows = ref<ReceivingRow[]>([]);
const disposalRecommendations = ref<DisposalRow[]>([]);
const qcInspecting = ref<QcRow[]>([]);
const selectedRows = ref<ReceivingRow[]>([]);

const factoryId = computed(() => authStore.factoryId || 'F006');

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

async function triggerTodayQuery() {
  userInput.value = '今天要收什么货?';
  await sendQuery(true);
}

async function sendQuery(autoTrigger = false) {
  if (!userInput.value.trim()) return;
  loading.value = true;
  errorMessage.value = '';
  if (autoTrigger) {
    formattedText.value = '';
    receivingRows.value = [];
    disposalRecommendations.value = [];
    qcInspecting.value = [];
  }

  try {
    // 1. 今日待收 (主路径)
    const intentCode = autoTrigger ? 'MATERIAL_TODAY_RECEIVING_QUERY' : undefined;
    const response = await callIntentExecute(userInput.value, intentCode);
    formattedText.value = response.formattedText || response.message || '(无输出)';
    lastQueryTime.value = new Date().toLocaleTimeString('zh-CN');

    const resultData = (response.resultData || {}) as Record<string, unknown>;
    extractReceivingRows(resultData);

    // 2. 自动 trigger 时, 并发拉临期建议 + 待质检
    if (autoTrigger) {
      await Promise.allSettled([
        loadDisposalRecommendations(),
        loadQcInspecting(),
      ]);
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

function extractReceivingRows(resultData: Record<string, unknown>) {
  // Tool 直接执行 → data 在 resultData.data, 或 resultData 本身
  const dataLayer = (resultData['data'] as Record<string, unknown>) || resultData;
  const rows = dataLayer['rows'] as ReceivingRow[] | undefined;
  if (Array.isArray(rows)) {
    receivingRows.value = rows;
  }
}

async function loadDisposalRecommendations() {
  try {
    const response = await callIntentExecute(
      '临期物料建议', 'MATERIAL_DISPOSAL_RECOMMENDATION');
    const resultData = (response.resultData || {}) as Record<string, unknown>;
    const dataLayer = (resultData['data'] as Record<string, unknown>) || resultData;
    const recs = dataLayer['recommendations'] as DisposalRow[] | undefined;
    if (Array.isArray(recs)) {
      disposalRecommendations.value = recs;
    }
  } catch (err) {
    console.warn('Disposal recommendations failed:', err);
  }
}

async function loadQcInspecting() {
  try {
    const response = await callIntentExecute(
      '今日待质检批次', 'RECEIVE_QUALITY_CHECK_TODAY');
    const resultData = (response.resultData || {}) as Record<string, unknown>;
    const dataLayer = (resultData['data'] as Record<string, unknown>) || resultData;
    const rows = dataLayer['inspectingBatches'] as QcRow[] | undefined;
    if (Array.isArray(rows)) {
      qcInspecting.value = rows;
    }
  } catch (err) {
    console.warn('QC inspecting failed:', err);
  }
}

function extractErrorMessage(err: unknown): string {
  if (err && typeof err === 'object') {
    const e = err as { response?: { data?: { message?: string } }; message?: string };
    return e.response?.data?.message || e.message || '未知错误';
  }
  return String(err);
}

function onSelectionChange(rows: ReceivingRow[]) {
  selectedRows.value = rows;
}

function previewAlertType(preview: PreviewState): 'warning' | 'info' | 'success' | 'error' {
  if (!preview.canDo) return 'warning';
  if (preview.status === 'PREVIEW') return 'success';
  return 'info';
}

function formatQty(v: number | undefined | null): string {
  if (v == null) return '0';
  const n = Number(v);
  if (Number.isNaN(n)) return String(v);
  // strip trailing .00 → 100 / .5 → 100.5
  return n.toString();
}

// ===== Receive Dialog =====

const receiveDialog = reactive<{
  visible: boolean;
  row: ReceivingRow | null;
  form: { receivedQty: number | null; remark: string };
  preview: PreviewState | null;
  previewing: boolean;
  submitting: boolean;
  canSubmit: boolean;
}>({
  visible: false,
  row: null,
  form: { receivedQty: null, remark: '' },
  preview: null,
  previewing: false,
  submitting: false,
  canSubmit: false,
});

const receiveDialogTitle = computed(() => {
  const r = receiveDialog.row;
  return r
    ? `快速收货 — ${r.materialName} (${r.orderNumber})`
    : '快速收货';
});

const maxAllowedReceive = computed(() => {
  return receiveDialog.row?.remainingCap ?? 0;
});

const isOverLimit = computed(() => {
  const qty = receiveDialog.form.receivedQty;
  if (qty == null) return false;
  return qty > (receiveDialog.row?.remainingCap ?? 0);
});

const canPreviewReceive = computed(() => {
  const qty = receiveDialog.form.receivedQty;
  return qty != null && qty > 0;
});

function openReceiveDialog(row: ReceivingRow) {
  receiveDialog.visible = true;
  receiveDialog.row = row;
  // 默认填 pendingQuantity (仓管员最常见场景: 全收)
  receiveDialog.form.receivedQty = row.pendingQuantity;
  receiveDialog.form.remark = '';
  receiveDialog.preview = null;
  receiveDialog.canSubmit = false;
}

function resetReceiveDialog() {
  receiveDialog.row = null;
  receiveDialog.form.receivedQty = null;
  receiveDialog.form.remark = '';
  receiveDialog.preview = null;
  receiveDialog.canSubmit = false;
}

async function previewReceive() {
  if (!receiveDialog.row || receiveDialog.form.receivedQty == null) {
    ElMessage.warning('请填写实收数量');
    return;
  }
  receiveDialog.previewing = true;
  try {
    const response = await callIntentExecute('预览快速入库', 'RECEIVE_WITH_LIMIT', {
      poId: receiveDialog.row.poId,
      lineId: receiveDialog.row.lineId,
      receivedQty: receiveDialog.form.receivedQty,
      remark: receiveDialog.form.remark,
    }, true);
    const previewData = (response.resultData || {}) as PreviewState;
    receiveDialog.preview = previewData;
    receiveDialog.canSubmit = previewData.canDo === true
        && previewData.status === 'PREVIEW';
  } catch (err) {
    ElMessage({
      message: `预览失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    receiveDialog.previewing = false;
  }
}

async function executeReceive() {
  if (!receiveDialog.canSubmit) {
    ElMessage.warning('请先点击 [预览边界] 验证可提交');
    return;
  }
  if (!receiveDialog.row || receiveDialog.form.receivedQty == null) return;
  receiveDialog.submitting = true;
  try {
    const response = await callIntentExecute('快速入库', 'RECEIVE_WITH_LIMIT', {
      poId: receiveDialog.row.poId,
      lineId: receiveDialog.row.lineId,
      receivedQty: receiveDialog.form.receivedQty,
      remark: receiveDialog.form.remark,
    });
    ElMessage.success(response.message || '草稿入库单已创建');
    receiveDialog.visible = false;
    // 刷新今日清单
    await triggerTodayQuery();
  } catch (err) {
    ElMessage({
      message: `提交失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    receiveDialog.submitting = false;
  }
}

// ===== Scan Task Dialog =====

const scanTaskDialog = reactive<{ visible: boolean; task: ScanTask | null }>({
  visible: false,
  task: null,
});

async function generateScanTask() {
  if (selectedRows.value.length === 0) {
    ElMessage.warning('请先选中要扫码的 PO 行');
    return;
  }
  // 去重 PO ID (多行同 PO)
  const poIds = [...new Set(selectedRows.value.map(r => r.poId))];
  try {
    const response = await callIntentExecute('一键扫码任务', 'PDA_SCAN_TASK_GENERATE', {
      poIds,
    });
    const data = (response.resultData || {}) as { task?: ScanTask };
    if (data.task) {
      scanTaskDialog.task = data.task;
      scanTaskDialog.visible = true;
    } else {
      ElMessage.info(response.message || '未生成任务');
    }
  } catch (err) {
    ElMessage({
      message: `生成扫码任务失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  }
}

// Auto-trigger on mount
onMounted(() => {
  void triggerTodayQuery();
});
</script>

<style scoped>
.warehouse-keeper-workdesk {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.workdesk-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: linear-gradient(135deg, #fff7e6, #fff1d8);
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

.header-actions-inline {
  display: flex;
  gap: 8px;
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

.overdue-text {
  color: #f56c6c;
  font-weight: 500;
}

.qty-hint-block {
  background: #f0f9ff;
  padding: 8px 12px;
  border-radius: 4px;
  border-left: 3px solid #409eff;
}

.form-hint {
  margin-left: 8px;
  color: #909399;
}

.preview-alert {
  margin-top: 8px;
}

.task-expire {
  color: #909399;
  font-size: 12px;
}

.qr-payload-box {
  margin: 12px 0;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 4px;
  text-align: left;
}

.qr-payload-text {
  margin: 8px 0 0;
  padding: 8px;
  background: #fff;
  border: 1px dashed #dcdfe6;
  border-radius: 4px;
  font-family: 'Courier New', monospace;
  font-size: 12px;
  word-break: break-all;
  white-space: pre-wrap;
}
</style>
