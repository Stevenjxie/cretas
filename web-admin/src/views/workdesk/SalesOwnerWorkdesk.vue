<!--
  SalesOwnerWorkdesk.vue — Sprint 8 P1 卤味老板 Workdesk V1

  F006 真场景: 张老板 (六腾门卤味店老板) 早 8am 打开 Cretas, 说一句
  "今天哪些客户该跟进?" 系统 5 秒内输出按优先级排序客户清单.

  vs HJ 模式: 客户列表 → 微信记录 → 通话记录 → 商机阶段 → 订单历史 (5 屏 5 分钟).
  Cretas Workdesk: 1 屏 30 秒.

  架构: 复用现有 /ai-intents/execute POST 端点, 触发 DAILY_CUSTOMER_FOLLOWUP intent.
  Backend 路由到 daily-customer-followup Skill (串 5 Tool + LLM aggregate).

  防呆: 4 位一体. WRITE 行动按钮调对应 Tool with Preview → 用户确认 → execute.
-->
<template>
  <div class="sales-owner-workdesk">
    <!-- Header -->
    <div class="workdesk-header">
      <div class="header-title">
        <span class="emoji"></span>
        <span class="title-text">销售老板工作台</span>
        <el-tag size="small" type="info">Sprint 8 P1 (2026-05-20)</el-tag>
      </div>
      <div class="header-actions">
        <el-button :loading="loading" :icon="Refresh" @click="triggerFollowupQuery">
          重新分析
        </el-button>
      </div>
    </div>

    <!-- AI Chat 输入区 -->
    <el-card class="chat-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>与 AI 对话</span>
          <span class="header-hint">默认查询: "今天该跟谁?" — 也可输入其他问题</span>
        </div>
      </template>
      <div class="chat-input">
        <el-input
          v-model="userInput"
          type="textarea"
          :rows="2"
          placeholder="例如: 今天该跟谁? / 哪些商机超过 21 天没推进? / 今日产能"
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
        <span>AI 正在聚合 5 个数据源 (客户优先级 + 微信跟进 + 通话 + 商机 + 收入), 预计 5-10 秒...</span>
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
          <span>今日跟进清单</span>
          <span class="header-hint" v-if="lastQueryTime">
            {{ lastQueryTime }} 生成
          </span>
        </div>
      </template>
      <div class="formatted-output" v-html="renderedFormattedText"></div>
    </el-card>

    <!-- 客户行动卡片清单 (R2: 含 customerName + 推荐行动) -->
    <el-card v-if="customers.length > 0" class="customers-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>客户优先级清单 ({{ customers.length }})</span>
        </div>
      </template>
      <div class="customers-grid">
        <div
          v-for="c in customers"
          :key="c.customerId"
          class="customer-card"
          :class="priorityClass(c.priorityScore)">
          <div class="customer-header">
            <span class="priority-emoji">{{ priorityEmoji(c.priorityScore) }}</span>
            <div class="customer-name">{{ c.customerName || '(未命名)' }}</div>
            <el-tag v-if="c.importanceDisplay" size="small" :type="importanceTagType(c.importance)">
              {{ c.importanceDisplay }}
            </el-tag>
          </div>
          <div class="customer-info">
            <div v-if="c.highestStageDisplay" class="info-row">
              <span class="label">最高商机阶段:</span>
              <span class="value">{{ c.highestStageDisplay }}</span>
            </div>
            <div class="info-row">
              <span class="label">活跃商机:</span>
              <span class="value">{{ c.activeOpportunityCount }} 个</span>
            </div>
            <div v-if="c.contactPerson" class="info-row">
              <span class="label">联系人:</span>
              <span class="value">{{ c.contactPerson }}</span>
            </div>
            <div v-if="c.contactPhone" class="info-row">
              <span class="label">电话:</span>
              <span class="value">{{ c.contactPhone }}</span>
            </div>
          </div>
          <div class="customer-actions">
            <el-button size="small" type="primary" @click="openWechatDialog(c)">
              补录微信
            </el-button>
            <el-button size="small" @click="gotoCustomerDetail(c)">
              查看详情
            </el-button>
            <el-button size="small" type="warning" @click="openOpportunityDialog(c)">
              更新商机
            </el-button>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 商机预警 -->
    <el-card v-if="staleOpportunities.length > 0" class="alerts-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>商机超 SLA 警告 ({{ staleOpportunities.length }})</span>
          <span class="header-hint">超 21 天未推进, 建议立即跟进</span>
        </div>
      </template>
      <el-table :data="staleOpportunities" size="small" style="width: 100%">
        <el-table-column prop="title" label="商机标题" min-width="180" />
        <el-table-column prop="customerName" label="客户" min-width="140" />
        <el-table-column prop="stageDisplay" label="当前阶段" width="120" />
        <el-table-column prop="stagnantDays" label="停滞天数" width="100">
          <template #default="{ row }">
            <el-tag :type="row.stagnantDays > 30 ? 'danger' : 'warning'" size="small">
              {{ row.stagnantDays }} 天
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="openOpportunityTransitionById(row)">
              推进阶段
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 微信补录 Dialog (R1 max + R2 context + R3 dropdown + R4 dedup) -->
    <el-dialog
      v-model="wechatDialog.visible"
      :title="`补录微信 — ${wechatDialog.customerName || '客户'}`"
      width="540px"
      @close="resetWechatDialog">
      <el-form :model="wechatDialog.form" label-width="100px">
        <el-form-item label="客户">
          <el-input :value="wechatDialog.customerName || '(未命名)'" disabled />
        </el-form-item>
        <el-form-item label="方向" required>
          <el-select v-model="wechatDialog.form.direction" placeholder="选择消息方向">
            <el-option label="客户来信 (INBOUND)" value="INBOUND" />
            <el-option label="我方去信 (OUTBOUND)" value="OUTBOUND" />
            <el-option label="内部备注 (INTERNAL)" value="INTERNAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="消息内容" required>
          <el-input
            v-model="wechatDialog.form.messageContent"
            type="textarea"
            :rows="4"
            placeholder="粘贴微信对话或概括关键点 (最少 1 字)"
            maxlength="2000"
            show-word-limit />
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="wechatDialog.form.contactPerson" placeholder="(可选) 客户端联系人" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="wechatDialog.form.remark" placeholder="(可选) 内部备注" />
        </el-form-item>
        <!-- Preview 结果显示 -->
        <el-alert
          v-if="wechatDialog.preview"
          :type="wechatDialog.preview.status === 'DUPLICATE' ? 'warning' : 'info'"
          :closable="false"
          show-icon
          class="preview-alert">
          <template #title>
            <span>{{ wechatDialog.preview.message }}</span>
          </template>
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="wechatDialog.visible = false">取消</el-button>
        <el-button
          :loading="wechatDialog.previewing"
          @click="previewWechatCreate">
          预览
        </el-button>
        <el-button
          type="primary"
          :loading="wechatDialog.submitting"
          :disabled="!wechatDialog.canSubmit"
          @click="executeWechatCreate">
          确认提交
        </el-button>
      </template>
    </el-dialog>

    <!-- 商机推进 Dialog (R1 当前/目标阶段 + R2 context + R3 stage dropdown) -->
    <el-dialog
      v-model="opportunityDialog.visible"
      :title="`推进商机 — ${opportunityDialog.customerName || '客户'}`"
      width="600px"
      @close="resetOpportunityDialog">
      <el-form :model="opportunityDialog.form" label-width="100px">
        <el-form-item label="客户">
          <el-input :value="opportunityDialog.customerName || '(未命名)'" disabled />
        </el-form-item>
        <el-form-item label="选择商机" required>
          <el-select
            v-model="opportunityDialog.form.id"
            placeholder="选择要推进的商机"
            @change="onOpportunitySelect">
            <el-option
              v-for="o in opportunityDialog.opportunityList"
              :key="o.id"
              :label="`${o.title} (当前: ${o.stageDisplay || o.stage})`"
              :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标阶段" required>
          <el-select v-model="opportunityDialog.form.newStage" placeholder="选择目标阶段">
            <el-option label="LEAD — 线索 (10%)" value="LEAD" />
            <el-option label="QUALIFIED — 已资格化 (30%)" value="QUALIFIED" />
            <el-option label="DEMO — 产品演示 (50%)" value="DEMO" />
            <el-option label="PROPOSAL — 方案报价 (70%)" value="PROPOSAL" />
            <el-option label="NEGOTIATE — 商务谈判 (85%)" value="NEGOTIATE" />
            <el-option label="VERBAL — 口头承诺 (95%)" value="VERBAL" />
            <el-option label="CLOSED_WON — 赢单 (100%)" value="CLOSED_WON" />
            <el-option label="CLOSED_LOST — 丢单 (0%)" value="CLOSED_LOST" />
          </el-select>
        </el-form-item>
        <el-form-item label="原因">
          <el-input
            v-model="opportunityDialog.form.reason"
            type="textarea"
            :rows="2"
            placeholder="(回退 / 跳级 / 终态重激活 时必填)" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="opportunityDialog.form.confirmSkip">
            允许跨阶段跳级 (如 LEAD → PROPOSAL)
          </el-checkbox>
        </el-form-item>
        <!-- Preview 结果 -->
        <el-alert
          v-if="opportunityDialog.preview"
          :type="previewAlertType(opportunityDialog.preview)"
          :closable="false"
          show-icon
          class="preview-alert">
          <template #title>
            <span>{{ opportunityDialog.preview.message }}</span>
          </template>
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="opportunityDialog.visible = false">取消</el-button>
        <el-button
          :loading="opportunityDialog.previewing"
          @click="previewOpportunityTransition">
          预览
        </el-button>
        <el-button
          type="primary"
          :loading="opportunityDialog.submitting"
          :disabled="!opportunityDialog.canSubmit"
          @click="executeOpportunityTransition">
          确认推进
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Refresh, Loading } from '@element-plus/icons-vue';
import request from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';

interface CustomerItem {
  customerId: string;
  customerName?: string;
  customerCode?: string;
  importance?: string;
  importanceDisplay?: string;
  contactPerson?: string;
  contactPhone?: string;
  activeOpportunityCount: number;
  highestStage?: string;
  highestStageDisplay?: string;
  priorityScore: number;
}

interface StaleOpportunity {
  id: string;
  title: string;
  customerId: string;
  customerName?: string;
  stage: string;
  stageDisplay?: string;
  stagnantDays: number;
}

interface OpportunityListItem {
  id: string;
  title: string;
  stage: string;
  stageDisplay?: string;
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

const router = useRouter();
const authStore = useAuthStore();

const userInput = ref('今天该跟谁?');
const loading = ref(false);
const errorMessage = ref('');
const formattedText = ref('');
const customers = ref<CustomerItem[]>([]);
const staleOpportunities = ref<StaleOpportunity[]>([]);
const lastQueryTime = ref('');

const factoryId = computed(() => authStore.factoryId || 'F006');

const renderedFormattedText = computed(() => {
  // Minimal markdown → HTML (line breaks + emoji preserve, escape angle brackets)
  if (!formattedText.value) return '';
  return formattedText.value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\n/g, '<br/>');
});

function priorityClass(score: number) {
  if (score >= 50) return 'priority-high';
  if (score >= 25) return 'priority-med';
  return 'priority-low';
}

function priorityEmoji(score: number) {
  if (score >= 50) return '';
  if (score >= 25) return '';
  return '';
}

function importanceTagType(imp?: string): 'danger' | 'warning' | 'info' | '' {
  switch (imp) {
    case 'VIP': return 'danger';
    case 'IMPORTANT': return 'warning';
    case 'NORMAL': return 'info';
    case 'LOW': return '';
    default: return '';
  }
}

function previewAlertType(preview: { status?: string }): 'warning' | 'info' | 'success' | 'error' {
  if (preview.status === 'DUPLICATE' || preview.status === 'PREVIEW_INVALID'
      || preview.status === 'INVALID_TRANSITION') return 'warning';
  if (preview.status === 'PREVIEW') return 'success';
  return 'info';
}

async function callIntentExecute(input: string, intentCode?: string)
    : Promise<ExecuteResponse> {
  const body: Record<string, unknown> = { userInput: input };
  if (intentCode) body.intentCode = intentCode;
  const res = await request.post<ExecuteResponse>(
    `/${factoryId.value}/ai-intents/execute`, body);
  return (res as { data: ExecuteResponse }).data;
}

async function triggerFollowupQuery() {
  userInput.value = '今天该跟谁?';
  await sendQuery(true);
}

async function sendQuery(forceFollowup = false) {
  if (!userInput.value.trim()) return;
  loading.value = true;
  errorMessage.value = '';
  formattedText.value = '';
  customers.value = [];
  staleOpportunities.value = [];

  try {
    const intentCode = forceFollowup ? 'DAILY_CUSTOMER_FOLLOWUP' : undefined;
    const response = await callIntentExecute(userInput.value, intentCode);
    formattedText.value = response.formattedText || response.message
        || '(无输出)';
    lastQueryTime.value = new Date().toLocaleTimeString('zh-CN');

    // 解析 resultData (Skill aggregate 后的多 Tool 结果)
    const resultData = response.resultData || {};
    extractCustomersFromResult(resultData);
    extractStaleOpportunitiesFromResult(resultData);
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

function extractCustomersFromResult(resultData: Record<string, unknown>) {
  // Skill aggregate 后 resultData 可能含 step 结果 dict
  const steps = (resultData['steps'] as Record<string, unknown>) || resultData;
  const customerStep = steps['customer_priority_query'] as
      Record<string, unknown> | undefined;
  if (customerStep) {
    const inner = (customerStep['data'] as Record<string, unknown>) || customerStep;
    const list = inner['customers'] as CustomerItem[] | undefined;
    if (Array.isArray(list)) {
      customers.value = list;
    }
  }
}

function extractStaleOpportunitiesFromResult(resultData: Record<string, unknown>) {
  const steps = (resultData['steps'] as Record<string, unknown>) || resultData;
  const oppStep = steps['opportunity_stage_alert'] as
      Record<string, unknown> | undefined;
  if (oppStep) {
    const inner = (oppStep['data'] as Record<string, unknown>) || oppStep;
    const list = inner['opportunities'] as StaleOpportunity[] | undefined;
    if (Array.isArray(list)) {
      staleOpportunities.value = list;
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

function gotoCustomerDetail(c: CustomerItem) {
  router.push(`/sales/customers/${c.customerId}`);
}

// ===== Wechat 补录 Dialog =====

const wechatDialog = reactive({
  visible: false,
  customerId: '',
  customerName: '',
  form: {
    direction: 'OUTBOUND',
    messageContent: '',
    contactPerson: '',
    remark: '',
  },
  preview: null as { status?: string; message?: string; canDo?: boolean } | null,
  previewing: false,
  submitting: false,
  canSubmit: false,
});

function openWechatDialog(c: CustomerItem) {
  wechatDialog.visible = true;
  wechatDialog.customerId = c.customerId;
  wechatDialog.customerName = c.customerName || '';
  resetWechatDialogForm();
}

function resetWechatDialog() {
  wechatDialog.preview = null;
  wechatDialog.canSubmit = false;
  resetWechatDialogForm();
}

function resetWechatDialogForm() {
  wechatDialog.form.direction = 'OUTBOUND';
  wechatDialog.form.messageContent = '';
  wechatDialog.form.contactPerson = '';
  wechatDialog.form.remark = '';
  wechatDialog.preview = null;
  wechatDialog.canSubmit = false;
}

async function previewWechatCreate() {
  if (!wechatDialog.form.messageContent.trim()) {
    ElMessage.warning('消息内容必填');
    return;
  }
  wechatDialog.previewing = true;
  try {
    const res = await request.post<ExecuteResponse>(
      `/${factoryId.value}/ai-intents/execute`,
      {
        userInput: '预览补录微信',
        intentCode: 'WECHAT_CREATE',
        parameters: {
          customerId: wechatDialog.customerId,
          direction: wechatDialog.form.direction,
          messageContent: wechatDialog.form.messageContent,
          contactPerson: wechatDialog.form.contactPerson,
          remark: wechatDialog.form.remark,
        },
        preview: true,
      });
    const data = (res as { data: ExecuteResponse }).data;
    const previewData = (data.resultData || {}) as
        { status?: string; message?: string; canDo?: boolean };
    wechatDialog.preview = previewData;
    wechatDialog.canSubmit = previewData.canDo !== false
        && previewData.status !== 'DUPLICATE';
  } catch (err) {
    ElMessage({
      message: `预览失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    wechatDialog.previewing = false;
  }
}

async function executeWechatCreate() {
  if (!wechatDialog.canSubmit) {
    ElMessage.warning('请先点击 [预览] 验证可提交');
    return;
  }
  wechatDialog.submitting = true;
  try {
    const res = await request.post<ExecuteResponse>(
      `/${factoryId.value}/ai-intents/execute`,
      {
        userInput: '补录微信',
        intentCode: 'WECHAT_CREATE',
        parameters: {
          customerId: wechatDialog.customerId,
          direction: wechatDialog.form.direction,
          messageContent: wechatDialog.form.messageContent,
          contactPerson: wechatDialog.form.contactPerson,
          remark: wechatDialog.form.remark,
        },
      });
    const data = (res as { data: ExecuteResponse }).data;
    ElMessage.success(data.message || '微信记录已补录');
    wechatDialog.visible = false;
  } catch (err) {
    ElMessage({
      message: `提交失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    wechatDialog.submitting = false;
  }
}

// ===== Opportunity 推进 Dialog =====

const opportunityDialog = reactive({
  visible: false,
  customerId: '',
  customerName: '',
  opportunityList: [] as OpportunityListItem[],
  form: {
    id: '',
    newStage: '',
    reason: '',
    confirmSkip: false,
  },
  preview: null as { status?: string; message?: string; canDo?: boolean } | null,
  previewing: false,
  submitting: false,
  canSubmit: false,
});

async function openOpportunityDialog(c: CustomerItem) {
  opportunityDialog.visible = true;
  opportunityDialog.customerId = c.customerId;
  opportunityDialog.customerName = c.customerName || '';
  resetOpportunityDialogForm();
  // 加载该客户的所有商机
  try {
    const res = await request.get(
      `/${factoryId.value}/sales/opportunities`,
      { params: { customerId: c.customerId, page: 0, size: 50 } });
    const data = (res as { data?: { content?: OpportunityListItem[] } }).data;
    opportunityDialog.opportunityList = data?.content || [];
  } catch {
    opportunityDialog.opportunityList = [];
  }
}

function openOpportunityTransitionById(opp: StaleOpportunity) {
  opportunityDialog.visible = true;
  opportunityDialog.customerId = opp.customerId;
  opportunityDialog.customerName = opp.customerName || '';
  opportunityDialog.opportunityList = [{
    id: opp.id,
    title: opp.title,
    stage: opp.stage,
    stageDisplay: opp.stageDisplay,
  }];
  resetOpportunityDialogForm();
  opportunityDialog.form.id = opp.id;
}

function resetOpportunityDialog() {
  opportunityDialog.preview = null;
  opportunityDialog.canSubmit = false;
  resetOpportunityDialogForm();
}

function resetOpportunityDialogForm() {
  opportunityDialog.form.id = '';
  opportunityDialog.form.newStage = '';
  opportunityDialog.form.reason = '';
  opportunityDialog.form.confirmSkip = false;
  opportunityDialog.preview = null;
  opportunityDialog.canSubmit = false;
}

function onOpportunitySelect() {
  opportunityDialog.preview = null;
  opportunityDialog.canSubmit = false;
}

async function previewOpportunityTransition() {
  if (!opportunityDialog.form.id || !opportunityDialog.form.newStage) {
    ElMessage.warning('请选择商机 + 目标阶段');
    return;
  }
  opportunityDialog.previewing = true;
  try {
    const res = await request.post<ExecuteResponse>(
      `/${factoryId.value}/ai-intents/execute`,
      {
        userInput: '预览推进商机',
        intentCode: 'OPPORTUNITY_TRANSITION',
        parameters: {
          id: opportunityDialog.form.id,
          newStage: opportunityDialog.form.newStage,
          reason: opportunityDialog.form.reason,
          confirmSkip: opportunityDialog.form.confirmSkip,
        },
        preview: true,
      });
    const data = (res as { data: ExecuteResponse }).data;
    const previewData = (data.resultData || {}) as
        { status?: string; message?: string; canDo?: boolean };
    opportunityDialog.preview = previewData;
    opportunityDialog.canSubmit = previewData.canDo !== false
        && previewData.status === 'PREVIEW';
  } catch (err) {
    ElMessage({
      message: `预览失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    opportunityDialog.previewing = false;
  }
}

async function executeOpportunityTransition() {
  if (!opportunityDialog.canSubmit) {
    ElMessage.warning('请先点击 [预览] 验证可提交');
    return;
  }
  opportunityDialog.submitting = true;
  try {
    const res = await request.post<ExecuteResponse>(
      `/${factoryId.value}/ai-intents/execute`,
      {
        userInput: '推进商机',
        intentCode: 'OPPORTUNITY_TRANSITION',
        parameters: {
          id: opportunityDialog.form.id,
          newStage: opportunityDialog.form.newStage,
          reason: opportunityDialog.form.reason,
          confirmSkip: opportunityDialog.form.confirmSkip,
        },
      });
    const data = (res as { data: ExecuteResponse }).data;
    ElMessage.success(data.message || '商机已推进');
    opportunityDialog.visible = false;
    // Refresh
    await triggerFollowupQuery();
  } catch (err) {
    ElMessage({
      message: `推进失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    opportunityDialog.submitting = false;
  }
}

// Auto-trigger on mount
onMounted(() => {
  void triggerFollowupQuery();
});
</script>

<style scoped>
.sales-owner-workdesk {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.workdesk-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: linear-gradient(135deg, #f0f9ff, #e6f4ff);
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

.customers-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}

.customer-card {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px;
  background: #fff;
  transition: box-shadow .2s;
}

.customer-card:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, .12);
}

.customer-card.priority-high {
  border-left: 4px solid #f56c6c;
}
.customer-card.priority-med {
  border-left: 4px solid #e6a23c;
}
.customer-card.priority-low {
  border-left: 4px solid #67c23a;
}

.customer-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.priority-emoji {
  font-size: 18px;
}

.customer-name {
  font-weight: 600;
  flex: 1;
  color: #303133;
}

.customer-info {
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

.customer-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.preview-alert {
  margin-top: 8px;
}
</style>
