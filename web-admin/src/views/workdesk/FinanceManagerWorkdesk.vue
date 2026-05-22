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
          <span>与 AI 对话</span>
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
          <span>月度经营摘要</span>
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
        <div class="metric-label">期间状态 ({{ periodLabel }})</div>
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
        <div class="metric-label">营业收入</div>
        <div class="metric-value">¥{{ formatAmount(totalRevenue) }}</div>
        <div class="metric-hint" v-if="grossMarginPercent !== null">
          毛利率 {{ grossMarginPercent }}%
        </div>
      </el-card>

      <el-card class="metric-card" shadow="hover">
        <div class="metric-label">净利润</div>
        <div class="metric-value" :class="netProfit >= 0 ? 'positive' : 'negative'">
          ¥{{ formatAmount(netProfit) }}
        </div>
      </el-card>

      <el-card class="metric-card" shadow="hover">
        <div class="metric-label">工资成本</div>
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
          <span>三大报表 (点击跳转)</span>
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
          <span>应收账龄警告 — 60+ 天高风险</span>
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
          <span>待付提成</span>
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
          <span>商机漏斗摘要</span>
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

// Auto-trigger on mount
onMounted(() => {
  void triggerMonthlyClose();
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
</style>
