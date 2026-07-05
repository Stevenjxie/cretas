<!--
  QualityChiefWorkdesk.vue — Sprint 8 P4c 质检组长 Workdesk (批次放行决策)

  F006 真场景 (Boss 演示弹药 #5): 质量主管李工程师早上打开 Cretas, 说
  "这批卤猪蹄能放行吗?" → AI 综合 质检 + HACCP + 添加剂合规 + 客户标准
 → 4 项 icon / + [一键放行] / [退货]

  vs HJ: 质量主管要点 4 菜单 (质检记录 / HACCP / 添加剂 / 客户标准) + 手工综合
  Cretas: 1 页面 AI 综合判断 + 一键决策

  架构: 复用 /ai-intents/execute POST 端点, 触发 RELEASE_DECISION intent (WRITE + Preview).
  AI 串 5 Tool: quality_check_summary + haccp_status_query + additive_compliance_check_quality +
  customer_quality_standard + release_decision

  防呆 4 位一体:
  - R1 max: 决策按钮 preview → BLOCKING modal → 用户确认 → execute
  - R2 context: dialog header 含批次号 + 物料名 + 决策类型
  - R3 dropdown: 退货必填 reason (textarea)
  - R5 dead-end: 失败 toast duration:0 + showClose + actionHint
-->
<template>
  <div class="quality-chief-workdesk">
    <!-- Header -->
    <div class="workdesk-header">
      <div class="header-title">
 <span class="emoji"></span>
        <span class="title-text">质检组长工作台</span>
        <el-tag size="small" type="primary">Sprint 8 P4c (2026-05-20)</el-tag>
      </div>
      <div class="header-actions">
        <el-button :loading="loading" :icon="Refresh" @click="triggerPendingQuery">
          重新查询
        </el-button>
      </div>
    </div>

    <!-- AI 输入区 -->
    <el-card class="chat-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 与 AI 对话 — 批次放行综合决策</span>
          <span class="header-hint">默认查待放行批次, 或输入 "卤猪蹄 B-X 能放行吗?"</span>
        </div>
      </template>
      <div class="chat-input">
        <el-input
          v-model="userInput"
          type="textarea"
          :rows="2"
          placeholder="例如: 今天哪些批次待放行? / B-20260520-A01 能放行吗? / 卤猪蹄批次质检结果"
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

    <!-- Loading -->
    <el-card v-if="loading" class="loading-card" shadow="never">
      <div class="loading-content">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>AI 正在综合 4 项 audit (质检 + HACCP + 添加剂 + 客户标准), 预计 3-5 秒...</span>
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

    <!-- AI 输出 (LLM 综合建议) -->
    <el-card v-if="formattedText" class="result-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> {{ resultTitle }}</span>
          <span class="header-hint" v-if="lastQueryTime">{{ lastQueryTime }} 生成</span>
        </div>
      </template>
      <div class="formatted-output" v-html="renderedFormattedText"></div>
    </el-card>

    <!-- 待放行批次卡片列表 -->
    <el-card v-if="pendingBatches.length > 0" class="batches-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 待放行批次 ({{ pendingBatches.length }} 条)</span>
          <span class="header-hint">每批显综合 audit + 一键放行/退货</span>
        </div>
      </template>
      <div class="batches-list">
        <div v-for="batch in pendingBatches" :key="batch.batchNumber" class="batch-card">
          <div class="batch-header">
            <div class="batch-title">
              <span class="batch-num">{{ batch.batchNumber }}</span>
              <span class="batch-material">{{ batch.materialName || '物料' }}</span>
              <el-tag size="small" :type="batch.statusTag">{{ batch.statusText }}</el-tag>
            </div>
            <div class="batch-audit">
              <span class="audit-item" :class="batch.qualityCheck ? 'ok' : 'fail'">
 {{ batch.qualityCheck ? '' : '' }} 质检
              </span>
              <span class="audit-item" :class="batch.haccp ? 'ok' : 'fail'">
 {{ batch.haccp ? '' : '' }} HACCP
              </span>
              <span class="audit-item" :class="batch.additive ? 'ok' : 'fail'">
 {{ batch.additive ? '' : '' }} 添加剂
              </span>
              <span class="audit-item" :class="batch.customerStandard ? 'ok' : 'fail'">
 {{ batch.customerStandard ? '' : '' }} 客户标准
              </span>
            </div>
          </div>
          <div class="batch-info" v-if="batch.detail">
            <span class="detail-text">{{ batch.detail }}</span>
          </div>
          <div class="batch-actions">
            <el-button
              type="success"
              size="default"
              :icon="CircleCheck"
              :disabled="!batch.canRelease"
              @click="openReleaseDialog(batch, 'RELEASED')">
 一键放行
            </el-button>
            <el-button
              type="danger"
              size="default"
              :icon="CircleClose"
              @click="openReleaseDialog(batch, 'REJECTED')">
 退货
            </el-button>
            <el-button
              size="default"
              :icon="View"
              @click="queryBatchDetail(batch.batchNumber)">
              详情
            </el-button>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 详细 audit 区 (单批次查询时) -->
    <el-card v-if="currentBatchDetail.show" class="detail-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 批次详细 audit — {{ currentBatchDetail.batchNumber }}</span>
          <el-tag v-if="currentBatchDetail.materialName" size="small" type="info">
            {{ currentBatchDetail.materialName }}
          </el-tag>
        </div>
      </template>
      <el-row :gutter="12">
        <el-col :span="6">
          <div class="metric-box" :class="qualityScoreClass">
            <div class="metric-label">质检合格率</div>
            <div class="metric-value">{{ currentBatchDetail.qualityScore }}</div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="metric-box" :class="haccpClass">
            <div class="metric-label">HACCP 监控</div>
            <div class="metric-value">{{ currentBatchDetail.haccpStatus }}</div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="metric-box" :class="additiveClass">
            <div class="metric-label">添加剂合规</div>
            <div class="metric-value">{{ currentBatchDetail.additiveStatus }}</div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="metric-box" :class="customerClass">
            <div class="metric-label">客户标准</div>
            <div class="metric-value">{{ currentBatchDetail.customerStatus }}</div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <!-- ===== 放行/退货决策 Dialog (R1+R2+R3+R4: preview → confirm → execute) ===== -->
    <el-dialog
      v-model="releaseDialog.visible"
      :title="releaseDialogTitle"
      width="640px"
      @close="resetReleaseDialog">
      <el-form :model="releaseDialog.form" label-width="100px">
        <el-form-item label="批次号">
          <el-input :value="releaseDialog.batchNumber" disabled />
        </el-form-item>
        <el-form-item label="物料名">
          <el-input :value="releaseDialog.materialName" disabled />
        </el-form-item>
        <el-form-item label="决策">
          <el-tag size="large" :type="releaseDialog.decisionTag">
            {{ releaseDialog.decisionLabel }}
          </el-tag>
        </el-form-item>
        <el-form-item
          :label="releaseDialog.decision === 'REJECTED' ? '退货理由 *' : '备注'"
          :required="releaseDialog.decision === 'REJECTED'">
          <el-input
            v-model="releaseDialog.form.notes"
            type="textarea"
            :rows="2"
            :placeholder="releaseDialog.decision === 'REJECTED'
              ? '退货必填: 例如 亚硝酸盐超限 / 微生物超标 / 客户标准不符'
              : '可选: 例如 全部 audit 通过, 放行'"
            maxlength="500"
            show-word-limit />
        </el-form-item>
        <!-- Preview 显示区 -->
        <el-alert
          v-if="releaseDialog.preview"
          :type="previewAlertType(releaseDialog.preview)"
          :closable="false"
          show-icon
          class="preview-alert">
          <template #title>
            <span>{{ releaseDialog.preview.message }}</span>
          </template>
          <template #default v-if="releaseDialog.preview.canDo">
            <div class="preview-detail">
              <div>当前 status: <strong>{{ releaseDialog.preview.currentStatus }}</strong></div>
              <div>决策后 status: <strong>{{ releaseDialog.preview.newStatus }}</strong></div>
              <div v-if="releaseDialog.preview.currentQuantity">
                数量: {{ releaseDialog.preview.currentQuantity }} {{ releaseDialog.preview.quantityUnit }}
              </div>
            </div>
          </template>
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="releaseDialog.visible = false">取消</el-button>
        <el-button
          :loading="releaseDialog.previewing"
          @click="previewRelease">
          预览
        </el-button>
        <el-button
          :type="releaseDialog.decision === 'RELEASED' ? 'success' : 'danger'"
          :loading="releaseDialog.submitting"
          :disabled="!releaseDialog.canSubmit"
          @click="executeRelease">
          {{ releaseDialog.decision === 'RELEASED' ? '确认放行' : '确认退货' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, reactive, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import {
  Refresh, Loading, CircleCheck, CircleClose, View,
} from '@element-plus/icons-vue';
import request from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';

interface ExecuteResponse {
  intentRecognized?: boolean;
  intentCode?: string;
  intentName?: string;
  status?: string;
  message?: string;
  formattedText?: string;
  resultData?: Record<string, unknown>;
}

interface PendingBatch {
  batchNumber: string;
  materialName?: string;
  statusText: string;
  statusTag: 'success' | 'warning' | 'danger' | 'info';
  qualityCheck: boolean;
  haccp: boolean;
  additive: boolean;
  customerStandard: boolean;
  canRelease: boolean;
  detail?: string;
}

interface PreviewData {
  status?: string;
  message?: string;
  canDo?: boolean;
  currentStatus?: string;
  newStatus?: string;
  currentQuantity?: number | string;
  quantityUnit?: string;
}

interface BatchDetail {
  show: boolean;
  batchNumber: string;
  materialName?: string;
  qualityScore: string;
  haccpStatus: string;
  additiveStatus: string;
  customerStatus: string;
}

const authStore = useAuthStore();

const userInput = ref('今天哪些批次待放行?');
const loading = ref(false);
const errorMessage = ref('');
const formattedText = ref('');
const lastQueryTime = ref('');
// Sprint 13 #304: dynamic result-card header — reflects the answered intent for user
// queries instead of always showing the auto-mount "AI 综合建议" label.
const DEFAULT_RESULT_TITLE = 'AI 综合建议';
const resultTitle = ref(DEFAULT_RESULT_TITLE);

const pendingBatches = ref<PendingBatch[]>([]);

const currentBatchDetail = reactive<BatchDetail>({
  show: false,
  batchNumber: '',
  materialName: '',
  qualityScore: '-',
  haccpStatus: '-',
  additiveStatus: '-',
  customerStatus: '-',
});

const factoryId = computed(() => authStore.factoryId || 'F006');

const renderedFormattedText = computed(() => {
  if (!formattedText.value) return '';
  return formattedText.value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\n/g, '<br/>');
});

const qualityScoreClass = computed(() =>
  currentBatchDetail.qualityScore === '-' || currentBatchDetail.qualityScore.includes('FAIL')
    ? 'metric-fail' : 'metric-ok');
const haccpClass = computed(() =>
  currentBatchDetail.haccpStatus.includes('偏离') || currentBatchDetail.haccpStatus.includes('-')
    ? 'metric-fail' : 'metric-ok');
const additiveClass = computed(() =>
  currentBatchDetail.additiveStatus.includes('超限') || currentBatchDetail.additiveStatus.includes('-')
    ? 'metric-fail' : 'metric-ok');
const customerClass = computed(() =>
  currentBatchDetail.customerStatus === '-' ? 'metric-warning' : 'metric-ok');

function extractErrorMessage(err: unknown): string {
  if (err && typeof err === 'object') {
    const e = err as { response?: { data?: { message?: string } }; message?: string };
    return e.response?.data?.message || e.message || '未知错误';
  }
  return String(err);
}

function previewAlertType(preview: PreviewData): 'warning' | 'info' | 'success' | 'error' {
  if (!preview.status) return 'info';
  if (preview.status === 'ALREADY_RELEASED' || preview.status === 'ALREADY_REJECTED') return 'warning';
  if (preview.status === 'PREVIEW') return 'success';
  if (preview.canDo === false) return 'warning';
  return 'info';
}

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

async function triggerPendingQuery() {
  userInput.value = '今天哪些批次待放行?';
  await sendQuery();
}

async function sendQuery() {
  if (!userInput.value.trim()) return;
  loading.value = true;
  errorMessage.value = '';
  formattedText.value = '';

  try {
    const response = await callIntentExecute(userInput.value);
    formattedText.value = response.formattedText || response.message || '(无输出)';
    lastQueryTime.value = new Date().toLocaleTimeString('zh-CN');
    // Sprint 13 #304: title reflects the answered intent. triggerPendingQuery resets
    // userInput to the default phrase before calling, so the default ask still maps to
    // the canonical 待放行 query and shows the default title via the intentName fallback.
    resultTitle.value = userInput.value === '今天哪些批次待放行?'
        ? DEFAULT_RESULT_TITLE
        : (response.intentName || DEFAULT_RESULT_TITLE);
    extractBatchesFromResult(response.resultData || {});
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

function extractBatchesFromResult(resultData: Record<string, unknown>) {
  // 兼容多种 result 格式: result.data.pendingBatches / result.batches / 直接返 list
  const data = (resultData['data'] as Record<string, unknown>) || resultData;
  const list = (data['pendingBatches'] as Array<Record<string, unknown>>)
    || (data['batches'] as Array<Record<string, unknown>>)
    || [];

  if (Array.isArray(list) && list.length > 0) {
    pendingBatches.value = list.map(item => mapToBatch(item));
  } else {
    pendingBatches.value = [];
  }
}

function mapToBatch(item: Record<string, unknown>): PendingBatch {
  // 兼容字段命名
  const overallStatus = (item['overallStatus'] as string) || '';
  const qualityOk = overallStatus === 'PASSED' || (item['qualityCheck'] as boolean) === true;
  const haccpOk = (item['haccpPassed'] as boolean) === true
    || (item['haccp'] as boolean) === true;
  const additiveOk = (item['additiveCompliant'] as boolean) === true
    || (item['additive'] as boolean) === true;
  const customerOk = (item['customerStandardMet'] as boolean) === true
    || (item['customerStandard'] as boolean) !== false;

  const canRelease = qualityOk && haccpOk && additiveOk && customerOk;

  return {
    batchNumber: (item['batchNumber'] as string) || (item['batchId'] as string) || '',
    materialName: (item['materialName'] as string) || '',
    statusText: (item['currentStatusDisplay'] as string) || (item['status'] as string) || '待放行',
    statusTag: canRelease ? 'success' : (qualityOk ? 'warning' : 'danger'),
    qualityCheck: qualityOk,
    haccp: haccpOk,
    additive: additiveOk,
    customerStandard: customerOk,
    canRelease,
    detail: (item['detail'] as string) || (item['message'] as string),
  };
}

async function queryBatchDetail(batchNumber: string) {
  loading.value = true;
  try {
    // 调 quality_check_summary Tool
    const response = await callIntentExecute(
      `批次 ${batchNumber} 的质检`,
      'QUALITY_CHECK_SUMMARY',
      { batchNumber },
    );
    const data = (response.resultData?.['data'] as Record<string, unknown>)
      || response.resultData || {};

    currentBatchDetail.show = true;
    currentBatchDetail.batchNumber = batchNumber;
    currentBatchDetail.materialName = (data['materialName'] as string) || '';

    const overallStatus = (data['overallStatus'] as string) || 'UNKNOWN';
    const inspectionCount = data['inspectionCount'] as number;
    currentBatchDetail.qualityScore = inspectionCount === 0
      ? '无质检记录'
      : `${inspectionCount} 次 ${overallStatus}`;

    // 并行调其他 3 Tool 凑齐 4 维度
    try {
      const [haccpRes, additiveRes, custRes] = await Promise.allSettled([
        callIntentExecute(`HACCP ${batchNumber}`, 'HACCP_STATUS_QUERY', { batchNumber }),
        callIntentExecute(`添加剂 ${batchNumber}`, 'ADDITIVE_COMPLIANCE_QUALITY', { batchNumber }),
        callIntentExecute('客户标准', 'CUSTOMER_QUALITY_STANDARD', { customerId: 'placeholder' }),
      ]);
      if (haccpRes.status === 'fulfilled') {
        const hd = (haccpRes.value.resultData?.['data'] as Record<string, unknown>) || {};
        const passed = hd['passed'] as boolean;
        const total = hd['totalRecords'] as number;
        const dev = hd['deviationCount'] as number;
        currentBatchDetail.haccpStatus = total === 0 ? '无监控记录'
 : passed ? ` ${total} 项通过` : ` ${dev}/${total} 偏离`;
      }
      if (additiveRes.status === 'fulfilled') {
        const ad = (additiveRes.value.resultData?.['data'] as Record<string, unknown>) || {};
        const compliant = ad['compliant'] as boolean | null;
        const violations = ad['violationCount'] as number;
        currentBatchDetail.additiveStatus = compliant === null ? '无 BOM 数据'
 : compliant ? ' 全部合规' : ` ${violations} 项超限`;
      }
      if (custRes.status === 'fulfilled') {
        const cd = (custRes.value.resultData?.['data'] as Record<string, unknown>) || {};
        currentBatchDetail.customerStatus = (cd['source'] as string) === 'FACTORY_DEFAULT'
          ? '工厂默认' : '客户特定';
      }
    } catch (innerErr) {
      console.warn('详细 audit 部分失败', innerErr);
    }

    ElMessage.success('详细 audit 已加载');
  } catch (err) {
    ElMessage({
      message: `查询失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    loading.value = false;
  }
}

// ===== 放行/退货 Dialog =====
const releaseDialog = reactive({
  visible: false,
  previewing: false,
  submitting: false,
  canSubmit: false,
  batchNumber: '',
  materialName: '',
  decision: 'RELEASED' as 'RELEASED' | 'REJECTED',
  decisionLabel: '',
  decisionTag: 'success' as 'success' | 'danger',
  form: {
    notes: '',
  },
  preview: null as PreviewData | null,
});

const releaseDialogTitle = computed(() => {
 const action = releaseDialog.decision === 'RELEASED' ? ' 放行批次' : ' 退货批次';
  return `${action} — ${releaseDialog.batchNumber} (${releaseDialog.materialName || '物料'})`;
});

function openReleaseDialog(batch: PendingBatch, decision: 'RELEASED' | 'REJECTED') {
  releaseDialog.batchNumber = batch.batchNumber;
  releaseDialog.materialName = batch.materialName || '';
  releaseDialog.decision = decision;
  releaseDialog.decisionLabel = decision === 'RELEASED' ? '放行 (INSPECTING → AVAILABLE)'
    : '退货 (INSPECTING → DEFECTIVE)';
  releaseDialog.decisionTag = decision === 'RELEASED' ? 'success' : 'danger';
  releaseDialog.form.notes = decision === 'RELEASED'
    ? '综合 audit 通过, 放行' : '';
  releaseDialog.preview = null;
  releaseDialog.canSubmit = false;
  releaseDialog.visible = true;
}

function resetReleaseDialog() {
  releaseDialog.form.notes = '';
  releaseDialog.preview = null;
  releaseDialog.canSubmit = false;
}

async function previewRelease() {
  if (!releaseDialog.batchNumber) {
    ElMessage.warning('批次号必填');
    return;
  }
  if (releaseDialog.decision === 'REJECTED' && !releaseDialog.form.notes.trim()) {
    ElMessage.warning('退货必填理由');
    return;
  }
  releaseDialog.previewing = true;
  try {
    const response = await callIntentExecute(
      releaseDialog.decision === 'RELEASED' ? '预览放行' : '预览退货',
      'RELEASE_DECISION',
      {
        batchNumber: releaseDialog.batchNumber,
        decision: releaseDialog.decision,
        notes: releaseDialog.form.notes,
      },
      true,
    );
    const previewData = (response.resultData || {}) as PreviewData;
    releaseDialog.preview = previewData;
    releaseDialog.canSubmit = previewData.canDo !== false
      && previewData.status !== 'ALREADY_RELEASED'
      && previewData.status !== 'ALREADY_REJECTED'
      && previewData.status !== 'INVALID_DECISION'
      && previewData.status !== 'MISSING_REJECT_REASON';
  } catch (err) {
    ElMessage({
      message: `预览失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    releaseDialog.previewing = false;
  }
}

async function executeRelease() {
  if (!releaseDialog.canSubmit) {
    ElMessage.warning('请先点击 [预览] 验证可提交');
    return;
  }
  releaseDialog.submitting = true;
  try {
    const response = await callIntentExecute(
      releaseDialog.decision === 'RELEASED' ? '放行批次' : '退货批次',
      'RELEASE_DECISION',
      {
        batchNumber: releaseDialog.batchNumber,
        decision: releaseDialog.decision,
        notes: releaseDialog.form.notes,
      },
    );
    ElMessage.success(response.message
      || `批次 ${releaseDialog.batchNumber} ${releaseDialog.decisionLabel} 完成`);
    releaseDialog.visible = false;
    // 重新查询
    await sendQuery();
  } catch (err) {
    ElMessage({
      message: `决策失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    releaseDialog.submitting = false;
  }
}

// auto-trigger on mount
onMounted(() => {
  triggerPendingQuery();
});
</script>

<style scoped>
.quality-chief-workdesk {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.workdesk-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: linear-gradient(135deg, #ecf5ff, #f4f8ff);
  padding: 12px 20px;
  border-radius: 8px;
  border-left: 4px solid #409eff;
}

.header-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 18px;
  font-weight: 600;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.emoji {
  font-size: 24px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
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

.formatted-output {
  font-size: 14px;
  line-height: 1.8;
  color: #303133;
  white-space: pre-wrap;
}

.batches-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.batch-card {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px 16px;
  background: #fff;
}

.batch-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.batch-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.batch-num {
  font-weight: 600;
  color: #303133;
}

.batch-material {
  color: #606266;
  font-size: 14px;
}

.batch-audit {
  display: flex;
  gap: 12px;
  font-size: 14px;
}

.audit-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.audit-item.ok {
  color: #67c23a;
}

.audit-item.fail {
  color: #f56c6c;
}

.batch-info {
  margin: 8px 0;
  padding: 8px 12px;
  background: #fafafa;
  border-radius: 4px;
  font-size: 13px;
  color: #606266;
}

.batch-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.metric-box {
  padding: 12px;
  border-radius: 6px;
  text-align: center;
}

.metric-ok {
  background: linear-gradient(135deg, #f0f9eb, #f4fbef);
  border: 1px solid #c2e7b0;
}

.metric-warning {
  background: linear-gradient(135deg, #fdf6ec, #fef9f0);
  border: 1px solid #f5dab1;
}

.metric-fail {
  background: linear-gradient(135deg, #fef0f0, #fff4f4);
  border: 1px solid #fbc4c4;
}

.metric-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}

.metric-value {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.preview-alert {
  margin-top: 8px;
}

.preview-detail {
  margin-top: 8px;
  padding: 8px;
  background: rgba(255, 255, 255, 0.5);
  border-radius: 4px;
  font-size: 13px;
}

.preview-detail > div {
  margin: 2px 0;
}
</style>
