<!--
  FinanceManagerWorkdesk.vue — Sprint 8 P2 财务主管 Workdesk

  F006 真场景: 财务主管刘女士月末打开 Cretas, 问 "本月经营怎么样?"
  系统 10 秒内输出聚合的经营摘要 + 三表跳转 + 应收账龄警告.

  vs HJ 模式: 财务报表菜单 → 选三表分别看 → 切到工资 → 切到 AR → 切到商机
  (5 屏 10 分钟). Cretas Workdesk: 1 屏 30 秒.

  架构: 复用现有 /ai-intents/execute POST 端点, 触发 MONTHLY_FINANCIAL_CLOSE intent.
  Backend 路由到 monthly-financial-close Skill (串 8 Tool + LLM aggregate).

  防呆: 4 位一体. 三表链接跳 Sprint 7 T3 ship 的 /finance/three-statements.
-->
<template>
  <div class="finance-manager-workdesk">
    <!-- Header -->
    <div class="workdesk-header">
      <div class="header-title">
 <span class="emoji"></span>
        <span class="title-text">财务主管工作台</span>
        <el-tag size="small" type="info">Sprint 8 P2 (2026-05-20)</el-tag>
      </div>
      <div class="header-actions">
        <el-button :loading="loading" :icon="Refresh" @click="triggerMonthlyClose">
          重新分析
        </el-button>
      </div>
    </div>

    <!-- AI Chat 输入区 -->
    <el-card class="chat-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 与 AI 对话</span>
          <span class="header-hint">默认查询: "本月经营怎么样?" — 也可输入其他问题</span>
        </div>
      </template>
      <div class="chat-input">
        <el-input
          v-model="userInput"
          type="textarea"
          :rows="2"
          placeholder="例如: 本月经营怎么样? / 应收账龄分析 / 待付提成多少 / 5 月利润表"
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
        <span>AI 正在聚合 8 个数据源 (期间状态 + 三表 + 工资 + 漏斗 + 应付提成 + 应收账龄), 预计 5-15 秒...</span>
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
 <span> 月度经营摘要</span>
          <span class="header-hint" v-if="lastQueryTime">
            {{ lastQueryTime }} 生成
          </span>
        </div>
      </template>
      <div class="formatted-output" v-html="renderedFormattedText"></div>
    </el-card>

    <!-- 关键指标卡 (R2: 含 period status + 三大数字) -->
    <div v-if="hasKeyMetrics" class="metrics-grid">
      <el-card class="metric-card" shadow="hover">
 <div class="metric-label"> 期间状态 ({{ periodLabel }})</div>
        <div class="metric-value" :class="periodStatusClass">
          {{ periodStatusDisplay }}
        </div>
        <div class="metric-hint" v-if="periodStatus !== 'CLOSED'">
          <el-button size="small" type="primary" link @click="gotoAccountingPeriod">
            管理期间结账 →
          </el-button>
        </div>
      </el-card>

      <el-card class="metric-card" shadow="hover">
 <div class="metric-label"> 营业收入</div>
        <div class="metric-value">¥{{ formatAmount(totalRevenue) }}</div>
        <div class="metric-hint" v-if="grossMarginPercent !== null">
          毛利率 {{ grossMarginPercent }}%
        </div>
      </el-card>

      <el-card class="metric-card" shadow="hover">
 <div class="metric-label"> 净利润</div>
        <div class="metric-value" :class="netProfit >= 0 ? 'positive' : 'negative'">
          ¥{{ formatAmount(netProfit) }}
        </div>
      </el-card>

      <el-card class="metric-card" shadow="hover">
 <div class="metric-label"> 工资成本</div>
        <div class="metric-value">¥{{ formatAmount(wageTotalAmount) }}</div>
        <div class="metric-hint" v-if="wageEmployeeCount > 0">
          {{ wageEmployeeCount }} 员工
        </div>
      </el-card>
    </div>

    <!-- 三大报表跳转卡 (R5: 一键跳到 Sprint 7 T3 真页) -->
    <el-card v-if="hasKeyMetrics" class="reports-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 三大报表 (点击跳转)</span>
          <span class="header-hint">Sprint 7 T3 ship · 期末快照 + 期间累计</span>
        </div>
      </template>
      <div class="reports-grid">
        <el-button
          type="primary"
          size="large"
          plain
          @click="gotoReport('balance-sheet')">
 资产负债表
        </el-button>
        <el-button
          type="success"
          size="large"
          plain
          @click="gotoReport('income-statement')">
 利润表
        </el-button>
        <el-button
          type="warning"
          size="large"
          plain
          @click="gotoReport('cashflow')">
 现金流量表
        </el-button>
      </div>
    </el-card>

    <!-- 应收账龄警告卡 (R5: 60+ 天高风险跳到 AR-AP 页) -->
    <el-card v-if="highRiskARCount > 0" class="alert-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 应收账龄警告 — 60+ 天高风险</span>
          <el-tag type="danger" size="small">{{ highRiskARCount }} 客户</el-tag>
        </div>
      </template>
      <div class="alert-content">
        <div class="alert-value">¥{{ formatAmount(highRiskARAmount) }}</div>
        <div class="alert-hint">建议立即催收, 防止形成坏账</div>
        <el-button type="danger" plain size="small" @click="gotoArAp">
          查看应收账龄详情 →
        </el-button>
      </div>
    </el-card>

    <!-- 待付提成卡 -->
    <el-card v-if="pendingCommissionAmount > 0" class="alert-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 待付提成</span>
          <el-tag type="warning" size="small">{{ pendingCommissionCount }} 笔</el-tag>
        </div>
      </template>
      <div class="alert-content">
        <div class="alert-value">¥{{ formatAmount(pendingCommissionAmount) }}</div>
        <div class="alert-hint">下月发放 · 提前预留现金</div>
      </div>
    </el-card>

    <!-- 商机漏斗摘要 -->
    <el-card v-if="funnelActiveCount > 0" class="alert-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 商机漏斗摘要</span>
          <el-tag type="info" size="small">{{ funnelActiveCount }} 活跃</el-tag>
        </div>
      </template>
      <div class="alert-content">
        <div class="alert-value">¥{{ formatAmount(funnelExpectedValue) }} <span class="alert-sub">加权预期</span></div>
        <div class="alert-hint">总 pipeline ¥{{ formatAmount(funnelTotalValue) }} 预估</div>
        <el-button type="info" plain size="small" @click="gotoFunnel">
          查看商机漏斗 →
        </el-button>
      </div>
    </el-card>

    <!-- Sprint 10 Loop 4 — 审批闭环 panel (付款/开票/调价/采购/销售订单 通用) -->
    <el-card class="approval-card" shadow="never" data-testid="approval-loop-panel">
      <template #header>
        <div class="card-header">
 <span> 待我审批 (Loop 4 AI 闭环)</span>
          <div>
            <el-tag :type="approvalPendingCount > 0 ? 'warning' : 'info'" size="small">
              {{ approvalPendingCount }} 项待审
            </el-tag>
            <el-button
              size="small"
              type="primary"
              link
              :loading="approvalLoading"
              @click="loadPendingApprovals"
              data-testid="approval-refresh-btn">
 刷新
            </el-button>
          </div>
        </div>
      </template>

      <!-- 空 / 加载状态 (R5 dead-end nav fix) -->
      <div v-if="approvalLoading" class="approval-empty">
        <el-icon class="is-loading"><Loading /></el-icon>
        正在查询待审清单...
      </div>
      <div v-else-if="approvalError" class="approval-empty">
        <el-alert
          :title="approvalError"
          type="error"
          show-icon
          :closable="false" />
      </div>
      <div v-else-if="approvalPendingCount === 0" class="approval-empty">
 <span class="empty-icon"></span>
        <div>暂无待您审批的工作流</div>
        <div class="empty-hint">提示: AI 问 "我该批什么" / "今日待审" 可触发查询</div>
      </div>

      <!-- 待审列表 (Rule 2 context: 必带 businessSummary / nodeLabel / initiator) -->
      <el-table
        v-else
        :data="approvalPendingItems"
        size="small"
        stripe
        data-testid="approval-pending-table">
        <el-table-column prop="businessSummary" label="单据" min-width="220">
          <template #default="scope">
            <el-tag size="small" type="info">{{ scope.row.moduleCode }}</el-tag>
            <span class="business-summary" style="margin-left: 6px;">
              {{ scope.row.businessSummary }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="currentNodeLabel" label="当前节点" width="120">
          <template #default="scope">
            <el-tag type="warning" size="small">{{ scope.row.currentNodeLabel || '审批中' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="initiatedByUsername" label="发起人" width="100">
          <template #default="scope">
            {{ scope.row.initiatedByUsername || '系统' }}
          </template>
        </el-table-column>
        <el-table-column prop="initiatedAt" label="发起时间" width="140" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="scope">
            <el-button
              size="small"
              type="success"
              @click="openApprovalDialog(scope.row, 'APPROVE')"
              data-testid="approval-approve-btn">
              批准
            </el-button>
            <el-button
              size="small"
              type="danger"
              @click="openApprovalDialog(scope.row, 'REJECT')"
              data-testid="approval-reject-btn">
              拒绝
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Approval Dialog (Fool-proof R1 confirm + R2 context + R3 dropdown reason) -->
    <el-dialog
      v-model="approvalDialogVisible"
      :title="approvalDialogTitle"
      width="540px"
      :close-on-click-modal="false"
      data-testid="approval-dialog">
      <div v-if="approvalDialogTarget">
        <!-- R2: context display 单据 + 当前节点 + 发起人 -->
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="业务模块">
            <el-tag size="small" type="info">{{ approvalDialogTarget.moduleCode }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="单据">
            {{ approvalDialogTarget.businessSummary }}
          </el-descriptions-item>
          <el-descriptions-item label="当前节点">
            {{ approvalDialogTarget.currentNodeLabel || '审批中' }}
          </el-descriptions-item>
          <el-descriptions-item label="发起人">
            {{ approvalDialogTarget.initiatedByUsername || '系统' }}
          </el-descriptions-item>
          <el-descriptions-item label="发起时间">
            {{ approvalDialogTarget.initiatedAt }}
          </el-descriptions-item>
        </el-descriptions>

        <el-divider />

        <!-- R3: reason dropdown enum + "其他" → textarea -->
        <el-form
          :model="approvalForm"
          label-width="90px"
          size="small"
          style="margin-top: 8px;">
          <el-form-item label="决定">
            <el-radio-group v-model="approvalForm.action" data-testid="approval-action-radio">
 <el-radio value="APPROVE"> 批准</el-radio>
 <el-radio value="REJECT"> 拒绝</el-radio>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="原因">
            <el-select
              v-model="approvalForm.reasonOption"
              placeholder="选择审批意见模板"
              style="width: 100%;"
              data-testid="approval-reason-select">
              <el-option-group v-if="approvalForm.action === 'APPROVE'" label="批准理由">
                <el-option label="符合预算 / 流程合规" value="符合预算 / 流程合规" />
                <el-option label="紧急业务 / 优先处理" value="紧急业务 / 优先处理" />
                <el-option label="审核无异常" value="审核无异常" />
                <el-option label="其他 (自定义)" value="OTHER" />
              </el-option-group>
              <el-option-group v-else label="拒绝原因">
                <el-option label="金额超限 / 预算不足" value="金额超限 / 预算不足" />
                <el-option label="资料不完整 / 缺凭证" value="资料不完整 / 缺凭证" />
                <el-option label="供应商资质问题" value="供应商资质问题" />
                <el-option label="单据信息有误" value="单据信息有误" />
                <el-option label="其他 (自定义)" value="OTHER" />
              </el-option-group>
            </el-select>
          </el-form-item>

          <el-form-item v-if="approvalForm.reasonOption === 'OTHER'" label="备注">
            <el-input
              v-model="approvalForm.notes"
              type="textarea"
              :rows="2"
              placeholder="请补充审批理由"
              data-testid="approval-notes-textarea" />
          </el-form-item>
        </el-form>
      </div>

      <template #footer>
        <el-button @click="approvalDialogVisible = false">取消</el-button>
        <el-button
          :type="approvalForm.action === 'APPROVE' ? 'success' : 'danger'"
          :loading="approvalSubmitting"
          :disabled="!isApprovalFormValid"
          @click="submitApproval"
          data-testid="approval-submit-btn">
          {{ approvalForm.action === 'APPROVE' ? '确认批准' : '确认拒绝' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Refresh, Loading } from '@element-plus/icons-vue';
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

const router = useRouter();
const authStore = useAuthStore();

const now = new Date();
const currentYear = ref(now.getFullYear());
const currentMonth = ref(now.getMonth() + 1);

const userInput = ref('本月经营怎么样?');
const loading = ref(false);
const errorMessage = ref('');
const formattedText = ref('');
const lastQueryTime = ref('');

// Key metrics (extracted from Skill result)
const periodStatus = ref<'OPEN' | 'PENDING_CLOSE' | 'CLOSED' | ''>('');
const totalRevenue = ref(0);
const netProfit = ref(0);
const grossMarginPercent = ref<number | null>(null);
const wageTotalAmount = ref(0);
const wageEmployeeCount = ref(0);
const highRiskARCount = ref(0);
const highRiskARAmount = ref(0);
const pendingCommissionCount = ref(0);
const pendingCommissionAmount = ref(0);
const funnelActiveCount = ref(0);
const funnelTotalValue = ref(0);
const funnelExpectedValue = ref(0);

// Sprint 10 Loop 4 — approval state
interface ApprovalPendingItem {
  instanceId: string;
  moduleCode: string;
  businessEntityId: string;
  businessSummary: string;
  currentNodeId?: string;
  currentNodeLabel?: string;
  initiatedByUsername?: string;
  initiatedAt?: string;
  actionUrl?: string;
}

interface PendingQueryResultData {
  count?: number;
  returned?: number;
  page?: number;
  size?: number;
  items?: ApprovalPendingItem[];
  userRole?: string;
  moduleCodeFilter?: string | null;
  message?: string;
}

const approvalLoading = ref(false);
const approvalError = ref('');
const approvalPendingCount = ref(0);
const approvalPendingItems = ref<ApprovalPendingItem[]>([]);
const approvalDialogVisible = ref(false);
const approvalDialogTarget = ref<ApprovalPendingItem | null>(null);
const approvalSubmitting = ref(false);
const approvalForm = ref<{
  action: 'APPROVE' | 'REJECT';
  reasonOption: string;
  notes: string;
}>({
  action: 'APPROVE',
  reasonOption: '',
  notes: '',
});

const approvalDialogTitle = computed(() => {
  if (!approvalDialogTarget.value) return '审批';
  const verb = approvalForm.value.action === 'APPROVE' ? '批准' : '拒绝';
  const summary = approvalDialogTarget.value.businessSummary || approvalDialogTarget.value.businessEntityId;
  return `${verb} — ${approvalDialogTarget.value.moduleCode} (${summary})`;
});

const isApprovalFormValid = computed(() => {
  if (!approvalForm.value.action) return false;
  if (!approvalForm.value.reasonOption) return false;
  if (approvalForm.value.reasonOption === 'OTHER' && !approvalForm.value.notes.trim()) return false;
  return true;
});

const factoryId = computed(() => authStore.factoryId || 'F006');

const periodLabel = computed(() => `${currentYear.value}-${String(currentMonth.value).padStart(2, '0')}`);

const periodStatusDisplay = computed(() => {
  switch (periodStatus.value) {
    case 'OPEN': return 'OPEN — 期间正常';
    case 'PENDING_CLOSE': return 'PENDING_CLOSE — 等待审批';
    case 'CLOSED': return 'CLOSED — 已关账';
    default: return '未知';
  }
});

const periodStatusClass = computed(() => {
  switch (periodStatus.value) {
    case 'OPEN': return 'status-open';
    case 'PENDING_CLOSE': return 'status-pending';
    case 'CLOSED': return 'status-closed';
    default: return '';
  }
});

const hasKeyMetrics = computed(() => Boolean(periodStatus.value || totalRevenue.value));

const renderedFormattedText = computed(() => {
  if (!formattedText.value) return '';
  return formattedText.value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\n/g, '<br/>');
});

function formatAmount(v: number | string | null | undefined): string {
  if (v === null || v === undefined || v === '') return '0';
  const num = typeof v === 'number' ? v : parseFloat(String(v));
  if (isNaN(num)) return '0';
  // 简单千分位 + 2 decimal
  return num.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

async function callIntentExecute(input: string, intentCode?: string): Promise<ExecuteResponse> {
  const body: Record<string, unknown> = { userInput: input };
  if (intentCode) body.intentCode = intentCode;
  const res = await request.post<ExecuteResponse>(
    `/${factoryId.value}/ai-intents/execute`, body);
  return (res as { data: ExecuteResponse }).data;
}

async function triggerMonthlyClose() {
  userInput.value = '本月经营怎么样?';
  await sendQuery(true);
}

async function sendQuery(forceMonthlyClose = false) {
  if (!userInput.value.trim()) return;
  loading.value = true;
  errorMessage.value = '';
  formattedText.value = '';
  resetMetrics();

  try {
    const intentCode = forceMonthlyClose ? 'MONTHLY_FINANCIAL_CLOSE' : undefined;
    const response = await callIntentExecute(userInput.value, intentCode);
    formattedText.value = response.formattedText || response.message || '(无输出)';
    lastQueryTime.value = new Date().toLocaleTimeString('zh-CN');

    const resultData = response.resultData || {};
    extractKeyMetrics(resultData);
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

function resetMetrics() {
  periodStatus.value = '';
  totalRevenue.value = 0;
  netProfit.value = 0;
  grossMarginPercent.value = null;
  wageTotalAmount.value = 0;
  wageEmployeeCount.value = 0;
  highRiskARCount.value = 0;
  highRiskARAmount.value = 0;
  pendingCommissionCount.value = 0;
  pendingCommissionAmount.value = 0;
  funnelActiveCount.value = 0;
  funnelTotalValue.value = 0;
  funnelExpectedValue.value = 0;
}

function extractKeyMetrics(resultData: Record<string, unknown>) {
  const steps = (resultData['steps'] as Record<string, unknown>) || resultData;

  // period_status_query
  const periodStep = steps['period_status_query'] as Record<string, unknown> | undefined;
  if (periodStep) {
    const inner = (periodStep['data'] as Record<string, unknown>) || periodStep;
    const status = inner['status'];
    if (typeof status === 'string') {
      periodStatus.value = status as 'OPEN' | 'PENDING_CLOSE' | 'CLOSED';
    }
    const y = inner['year'];
    const m = inner['month'];
    if (typeof y === 'number') currentYear.value = y;
    if (typeof m === 'number') currentMonth.value = m;
  }

  // income_statement_query
  const incomeStep = steps['income_statement_query'] as Record<string, unknown> | undefined;
  if (incomeStep) {
    const inner = (incomeStep['data'] as Record<string, unknown>) || incomeStep;
    totalRevenue.value = toNum(inner['totalRevenue']);
    netProfit.value = toNum(inner['netProfit']);
    const gm = inner['grossMarginPercent'];
    grossMarginPercent.value = gm !== null && gm !== undefined ? toNum(gm) : null;
  }

  // wage_cost_summary
  const wageStep = steps['wage_cost_summary'] as Record<string, unknown> | undefined;
  if (wageStep) {
    const inner = (wageStep['data'] as Record<string, unknown>) || wageStep;
    wageTotalAmount.value = toNum(inner['totalAmount']);
    wageEmployeeCount.value = toNum(inner['employeeCount']);
  }

  // accounts_receivable_aging
  const arStep = steps['accounts_receivable_aging'] as Record<string, unknown> | undefined;
  if (arStep) {
    const inner = (arStep['data'] as Record<string, unknown>) || arStep;
    highRiskARCount.value = toNum(inner['highRiskCount']);
    highRiskARAmount.value = toNum(inner['highRiskAmount']);
  }

  // commission_pending_total
  const commStep = steps['commission_pending_total'] as Record<string, unknown> | undefined;
  if (commStep) {
    const inner = (commStep['data'] as Record<string, unknown>) || commStep;
    pendingCommissionCount.value = toNum(inner['totalCount']);
    pendingCommissionAmount.value = toNum(inner['totalAmount']);
  }

  // opportunity_funnel_stats
  const funnelStep = steps['opportunity_funnel_stats'] as Record<string, unknown> | undefined;
  if (funnelStep) {
    const inner = (funnelStep['data'] as Record<string, unknown>) || funnelStep;
    funnelActiveCount.value = toNum(inner['activeCount']);
    funnelTotalValue.value = toNum(inner['activeValue']);
    funnelExpectedValue.value = toNum(inner['activeExpected']);
  }
}

function toNum(v: unknown): number {
  if (v === null || v === undefined) return 0;
  if (typeof v === 'number') return v;
  const n = parseFloat(String(v));
  return isNaN(n) ? 0 : n;
}

function extractErrorMessage(err: unknown): string {
  if (err && typeof err === 'object') {
    const e = err as { response?: { data?: { message?: string } }; message?: string };
    return e.response?.data?.message || e.message || '未知错误';
  }
  return String(err);
}

// Navigation handlers (R5 dead-end nav fix — all routes verified in router/index.ts)
function gotoReport(type: string) {
  router.push({
    path: '/finance/three-statements',
    query: {
      type,
      year: String(currentYear.value),
      month: String(currentMonth.value),
    },
  });
}

function gotoAccountingPeriod() {
  router.push({
    path: '/finance/accounting-period',
    query: {
      year: String(currentYear.value),
      month: String(currentMonth.value),
    },
  });
}

function gotoArAp() {
  router.push({
    path: '/finance/ar-ap',
    query: { tab: 'aging', type: 'CUSTOMER' },
  });
}

function gotoFunnel() {
  router.push('/crm/opportunity/funnel');
}

// ==================== Sprint 10 Loop 4 — 审批闭环 ====================

async function loadPendingApprovals() {
  approvalLoading.value = true;
  approvalError.value = '';
  try {
    const response = await callIntentExecute('我该批什么', 'APPROVAL_PENDING_QUERY');
    const data = (response.resultData as Record<string, unknown> | undefined) || {};
    const inner = (data['data'] as PendingQueryResultData) || (data as PendingQueryResultData);
    approvalPendingCount.value = typeof inner.count === 'number' ? inner.count : 0;
    approvalPendingItems.value = Array.isArray(inner.items) ? inner.items : [];
  } catch (err: unknown) {
    const msg = extractErrorMessage(err);
    approvalError.value = `查询待审清单失败: ${msg}`;
    ElMessage({
      message: approvalError.value,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    approvalLoading.value = false;
  }
}

function openApprovalDialog(target: ApprovalPendingItem, action: 'APPROVE' | 'REJECT') {
  approvalDialogTarget.value = target;
  approvalForm.value = {
    action,
    reasonOption: '',
    notes: '',
  };
  approvalDialogVisible.value = true;
}

async function submitApproval() {
  if (!approvalDialogTarget.value) return;
  if (!isApprovalFormValid.value) {
    ElMessage({
      message: '请选择审批意见 (若选 "其他" 需填写备注)',
      type: 'warning',
      duration: 3000,
    });
    return;
  }

  approvalSubmitting.value = true;
  try {
    const notesText = approvalForm.value.reasonOption === 'OTHER'
      ? approvalForm.value.notes.trim()
      : approvalForm.value.reasonOption;

    // 直接调 ai-intents/execute 触发 APPROVAL_ACTION_EXECUTE (Tool)
    // userInput 内含 instanceId + action + notes 让 LLM 提参; 同时 context 也带 fallback.
    const actionVerb = approvalForm.value.action === 'APPROVE' ? '批准' : '拒绝';
    const userMsg = `${actionVerb}审批 instanceId=${approvalDialogTarget.value.instanceId} action=${approvalForm.value.action} notes=${notesText}`;
    const response = await callIntentExecuteWithParams(
      userMsg,
      'APPROVAL_ACTION_EXECUTE',
      {
        instanceId: approvalDialogTarget.value.instanceId,
        action: approvalForm.value.action,
        notes: notesText,
      },
    );

    // 成功: 用 sticky info toast 显示后端 message (4 位一体)
    const replyMsg = response.message
      || (response.resultData && (response.resultData as Record<string, unknown>)['message'] as string | undefined)
      || (approvalForm.value.action === 'APPROVE' ? '已批准' : '已拒绝');
    ElMessage({
      message: String(replyMsg),
      type: 'success',
      duration: 5000,
      showClose: true,
    });

    approvalDialogVisible.value = false;
    // 重新加载列表
    await loadPendingApprovals();
  } catch (err: unknown) {
    const msg = extractErrorMessage(err);
    ElMessage({
      message: `审批失败: ${msg}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    approvalSubmitting.value = false;
  }
}

/**
 * 直调指定 intent + 参数 — 用于 dialog 提交场景, bypass LLM 参数提取.
 * IntentExecuteRequest.context 透传给 Tool 的 doExecute params, skipSlotFilling=true 跳过 NEED_MORE_INFO.
 */
async function callIntentExecuteWithParams(
  input: string,
  intentCode: string,
  toolParams: Record<string, unknown>,
): Promise<ExecuteResponse> {
  const body: Record<string, unknown> = {
    userInput: input,
    intentCode,
    context: toolParams,
    skipSlotFilling: true,
  };
  const res = await request.post<ExecuteResponse>(
    `/${factoryId.value}/ai-intents/execute`, body);
  return (res as { data: ExecuteResponse }).data;
}

// Auto-trigger on mount
onMounted(() => {
  void triggerMonthlyClose();
  void loadPendingApprovals();
});
</script>

<style scoped>
.finance-manager-workdesk {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.workdesk-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: linear-gradient(135deg, #fff7ed, #fef3c7);
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

/* Key metrics grid */
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
}

.metric-card {
  text-align: center;
}

.metric-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 8px;
}

.metric-value {
  font-size: 22px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 4px;
}

.metric-value.positive {
  color: #67c23a;
}

.metric-value.negative {
  color: #f56c6c;
}

.metric-value.status-open {
  color: #67c23a;
}

.metric-value.status-pending {
  color: #e6a23c;
}

.metric-value.status-closed {
  color: #909399;
}

.metric-hint {
  font-size: 12px;
  color: #909399;
}

/* Reports grid */
.reports-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
}

.reports-grid .el-button {
  height: 60px;
  font-size: 16px;
}

/* Alert cards */
.alert-card .alert-content {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}

.alert-value {
  font-size: 24px;
  font-weight: 600;
  color: #303133;
}

.alert-sub {
  font-size: 13px;
  color: #909399;
  font-weight: normal;
}

.alert-hint {
  font-size: 13px;
  color: #606266;
}

/* Sprint 10 Loop 4 — approval panel */
.approval-card {
  border-left: 3px solid #67c23a;
}

.approval-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 24px;
  color: #909399;
  font-size: 14px;
}

.empty-icon {
  font-size: 32px;
}

.empty-hint {
  font-size: 12px;
  color: #c0c4cc;
}

.business-summary {
  font-size: 13px;
  color: #303133;
}
</style>
