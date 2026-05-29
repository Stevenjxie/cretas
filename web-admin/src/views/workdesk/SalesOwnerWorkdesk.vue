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
 <span> 与 AI 对话</span>
          <span class="header-hint">默认查询: "今天该跟谁?" — 也可输入其他问题</span>
        </div>
      </template>
      <div class="chat-input">
        <el-input
          v-model="userInput"
          type="textarea"
          :rows="2"
          placeholder="例如: 今天该跟谁? / 哪些商机超过 21 天没推进? / 今日产能"
          @keydown.enter.ctrl="sendQuery()"
        />
        <el-button
          type="primary"
          :loading="loading"
          :disabled="!userInput.trim()"
          @click="sendQuery()">
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

    <!-- Sprint 11 BI 4-B band-aid Workdesk fix (PR #243 audit Dim 1 — P0.5):
         Old: 4 mirror IndicatorCards (AVG_TICKET_PRICE 等 V_23_11 mirror) + 撒谎 header "F006 真数据"
         Fix: B2BRealDataSection 真接 sales_orders 算 ¥1.22M avg + 大字 banner "客户演示模式 · Sprint 12 接 backend"
         Sprint 12 backend rewrite 接 sister AI 工厂 chat, 见 docs/sprint-12-backlog/indicator-service-rewrite.md -->
    <B2BRealDataSection :factory-id="factoryId" />

    <!-- Sprint 13 #304: Restaurant P&L card render when RESTAURANT_ECONOMICS_ANALYSIS
         intent returns dataAvailable=true. Mirrors WarehouseKeeperWorkdesk Q6 Option B.6
         pattern so the user's economics query (e.g. "哪个菜亏钱") produces an INDEPENDENT
         structured card (separate from the auto-mount 今日跟进清单), and the real ¥ P&L
         numbers render once backend RESTAURANT_ECONOMICS_ANALYSIS data wiring (S13-001)
         lands. When dataAvailable=false the card stays hidden and formattedText carries
         the honest "部分数据不可用" degradation message. -->
    <el-card v-if="restaurantPnl" class="restaurant-pnl-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>餐厅经营分析 — {{ restaurantPnl.storeName }}{{ restaurantPnl.period ? ' (' + restaurantPnl.period + ')' : '' }}</span>
          <span class="header-hint" v-if="lastQueryTime">
            {{ lastQueryTime }} 生成
          </span>
        </div>
      </template>
      <div class="pnl-headline" :class="'pnl-headline-' + restaurantPnl.headlineColor">
        {{ restaurantPnl.headline }}
      </div>
      <el-table :data="restaurantPnl.pnlLines" stripe size="small" class="pnl-table">
        <el-table-column prop="label" label="项目" min-width="120" />
        <el-table-column label="金额 ¥" min-width="140" align="right">
          <template #default="{ row }">
            {{ formatPnlAmount(row.amount) }}
          </template>
        </el-table-column>
        <el-table-column label="占营收 %" min-width="100" align="right">
          <template #default="{ row }">
            {{ row.pctOfRevenue != null ? (row.pctOfRevenue * 100).toFixed(2) + '%' : '—' }}
          </template>
        </el-table-column>
        <el-table-column prop="statusEmoji" label="状态" width="80" align="center">
          <template #default="{ row }">{{ row.statusEmoji || '' }}</template>
        </el-table-column>
        <el-table-column prop="note" label="备注" min-width="160">
          <template #default="{ row }">
            <span v-if="row.note">{{ row.note }}</span>
            <span v-else class="cell-empty">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

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
      <div class="formatted-output" v-html="renderedFormattedText"></div>
    </el-card>

    <!-- 客户行动卡片清单 (R2: 含 customerName + 推荐行动) -->
    <el-card v-if="customers.length > 0" class="customers-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 客户优先级清单 ({{ customers.length }})</span>
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
 <span>️ 商机超 SLA 警告 ({{ staleOpportunities.length }})</span>
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

    <!-- Sprint 10 Loop 1 — 今日待发清单 (AI 闭环发货入口) -->
    <el-card class="shipment-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 今日待发清单 ({{ pendingShipments.length }})</span>
          <div>
            <el-button
              size="small"
              :loading="shipmentLoading"
              :icon="Refresh"
              data-testid="sprint10-refresh-pending-btn"
              @click="loadTodayPendingShipments">
              刷新待发
            </el-button>
            <span class="header-hint" style="margin-left: 8px;">Sprint 10 Loop 1 — AI 闭环发货</span>
          </div>
        </div>
      </template>
      <div v-if="!shipmentLoading && pendingShipments.length === 0" class="empty-shipment">
        <el-empty description="今日暂无待发销售订单" :image-size="60" />
      </div>
      <el-table
        v-if="pendingShipments.length > 0"
        :data="pendingShipments"
        size="small"
        data-testid="sprint10-pending-shipments-table"
        style="width: 100%">
        <el-table-column prop="orderNumber" label="订单号" min-width="170" />
        <el-table-column prop="customerName" label="客户" min-width="140">
          <template #default="{ row }">
            {{ row.customerName || '(未命名)' }}
          </template>
        </el-table-column>
        <el-table-column prop="statusDisplay" label="状态" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row.status)">
              {{ row.statusDisplay }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="requiredDeliveryDate" label="要求交货" width="120">
          <template #default="{ row }">
            <span v-if="row.requiredDeliveryDate">{{ row.requiredDeliveryDate }}</span>
            <el-tag v-else size="small" type="info">未指定</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="itemCount" label="待发行数" width="100" align="center" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              type="primary"
              data-testid="sprint10-confirm-shipment-btn"
              @click="openShipmentDialog(row)">
              一键确认发货
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Sprint 10 Loop 1 — 确认发货 Dialog (R1 max + R2 context + R4 idempotent) -->
    <el-dialog
      v-model="shipmentDialog.visible"
      :title="`确认发货 — ${shipmentDialog.customerName || '客户'} (${shipmentDialog.orderNumber || ''})`"
      width="720px"
      data-testid="sprint10-shipment-dialog"
      @close="resetShipmentDialog">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="dialog-hint">
        <template #title>
          R1 提示: 每行最多发剩余可发数量, 超额 disable 提交.
        </template>
      </el-alert>
      <el-table
        :data="shipmentDialog.itemForms"
        size="small"
        style="margin-top: 12px; width: 100%">
        <el-table-column prop="productName" label="品名" min-width="180">
          <template #default="{ row }">
            {{ row.productName || row.productTypeId }}
          </template>
        </el-table-column>
        <el-table-column prop="orderedQuantity" label="订单量" width="80" align="right" />
        <el-table-column prop="deliveredQuantity" label="已发" width="80" align="right" />
        <el-table-column label="剩余可发" width="110" align="right">
          <template #default="{ row }">
            <span style="color: #67c23a; font-weight: 600;">{{ row.pendingQuantity }}</span>
            {{ row.unit }}
          </template>
        </el-table-column>
        <el-table-column label="本次发货量" width="200">
          <template #default="{ row }">
            <el-input-number
              v-model="row.actualQty"
              :min="0"
              :max="Number(row.pendingQuantity)"
              :step="1"
              :precision="2"
              size="small"
              controls-position="right"
              :data-testid="`sprint10-actualqty-input-${row.salesOrderItemId}`"
              style="width: 140px" />
          </template>
        </el-table-column>
      </el-table>
      <el-form label-width="100px" style="margin-top: 16px;">
        <el-form-item label="发货日期">
          <el-date-picker
            v-model="shipmentDialog.deliveryDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="默认今天"
            data-testid="sprint10-delivery-date-picker" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="shipmentDialog.remark"
            placeholder="(可选) 司机/物流单号等"
            maxlength="200"
            show-word-limit />
        </el-form-item>
      </el-form>
      <el-alert
        v-if="shipmentDialog.feedback"
        :type="shipmentFeedbackType(shipmentDialog.feedback)"
        :closable="false"
        show-icon
        class="dialog-hint"
        style="margin-top: 12px;"
        data-testid="sprint10-shipment-feedback">
        <template #title>{{ shipmentDialog.feedback.message }}</template>
        <template v-if="shipmentDialog.feedback.actionHint" #default>
          <div style="margin-top: 6px; color: #606266;">
            提示: {{ shipmentDialog.feedback.actionHint }}
          </div>
        </template>
      </el-alert>
      <template #footer>
        <el-button @click="shipmentDialog.visible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="shipmentDialog.submitting"
          :disabled="!shipmentCanSubmit"
          data-testid="sprint10-shipment-submit-btn"
          @click="submitShipment">
          确认提交发货
        </el-button>
      </template>
    </el-dialog>

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
import B2BRealDataSection from '@/views/indicator-center/B2BRealDataSection.vue';

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

// Sprint 13 #304: restaurant P&L shape (mirror WarehouseKeeperWorkdesk Q6 Option B.6)
interface PnlLine {
  label: string;
  amount: number;
  pctOfRevenue: number | null;
  statusEmoji: string | null;
  note: string | null;
}
interface RestaurantPnl {
  headline: string;
  headlineColor: string;
  pnlLines: PnlLine[];
  storeName: string;
  period: string;
  subSector: string;
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
// Sprint 13 #304: result-card header reflects the actual answered intent instead of a
// hardcoded "今日跟进清单" (which mislabels e.g. a 餐厅经营分析 answer). Default keeps the
// auto-mount title; user queries show the recognized intent's display name.
const DEFAULT_RESULT_TITLE = '今日跟进清单';
const resultTitle = ref(DEFAULT_RESULT_TITLE);
const restaurantPnl = ref<RestaurantPnl | null>(null);

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

async function callIntentExecute(input: string, intentCode?: string,
    context?: Record<string, unknown>): Promise<ExecuteResponse> {
  const body: Record<string, unknown> = { userInput: input };
  if (intentCode) body.intentCode = intentCode;
  // Sprint 11 Q6 Option B (2026-05-24): pass context so backend Tools can disambiguate
  // period-bounded P&L / loss / cost queries. SalesOwnerWorkdesk is the canonical
  // entry point for the 12-cell customer-journey audit (PR #235) — without context
  // backend BERT classifier misroutes ambiguous restaurant-economics phrases.
  if (context) body.context = context;
  const res = await request.post<ExecuteResponse>(
    `/${factoryId.value}/ai-intents/execute`, body);
  return (res as { data: ExecuteResponse }).data;
}

/**
 * Parse month from user input. Returns canonical "YYYY-MM" or undefined.
 * Mirror logic in WarehouseKeeperWorkdesk.vue (kept inline rather than extracted
 * to a shared helper to keep this PR diff narrow per Q6 Option B scope).
 */
function parseMonthFromInput(input: string): string | undefined {
  if (!input) return undefined;
  const isoMatch = input.match(/(\d{4})[-/](\d{1,2})/);
  if (isoMatch) return `${isoMatch[1]}-${String(isoMatch[2]).padStart(2, '0')}`;
  const cnYearMatch = input.match(/(\d{4})年(\d{1,2})月/);
  if (cnYearMatch) return `${cnYearMatch[1]}-${String(cnYearMatch[2]).padStart(2, '0')}`;
  if (input.includes('本月') || input.includes('这个月') || input.includes('当月')) {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  }
  if (input.includes('上月') || input.includes('上个月') || input.includes('上一个月')) {
    const now = new Date();
    const prev = new Date(now.getFullYear(), now.getMonth() - 1, 1);
    return `${prev.getFullYear()}-${String(prev.getMonth() + 1).padStart(2, '0')}`;
  }
  const cnMonthMatch = input.match(/(?<![\d年])(\d{1,2})月(?:份)?/);
  if (cnMonthMatch) {
    const now = new Date();
    return `${now.getFullYear()}-${String(cnMonthMatch[1]).padStart(2, '0')}`;
  }
  return undefined;
}

function looksLikeRestaurantEconomicsQuery(input: string): boolean {
  if (!input) return false;
  const economicsKeywords = [
    '损溢', '损益', '亏', '利润', '毛利', '成本',
    '盈利', '赚', 'P&L', 'p&l', '经营',
  ];
  return economicsKeywords.some((k) => input.includes(k));
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
  restaurantPnl.value = null;

  try {
    const intentCode = forceFollowup ? 'DAILY_CUSTOMER_FOLLOWUP' : undefined;
    // Sprint 11 Q6 Option B: attach context.month for restaurant-economics queries
    // (default "上月" per customer's most common ask).
    let context: Record<string, unknown> | undefined;
    if (!forceFollowup && looksLikeRestaurantEconomicsQuery(userInput.value)) {
      const month = parseMonthFromInput(userInput.value) ?? (() => {
        const now = new Date();
        const prev = new Date(now.getFullYear(), now.getMonth() - 1, 1);
        return `${prev.getFullYear()}-${String(prev.getMonth() + 1).padStart(2, '0')}`;
      })();
      context = { month };
    }
    const response = await callIntentExecute(userInput.value, intentCode, context);
    formattedText.value = response.formattedText || response.message
        || '(无输出)';
    lastQueryTime.value = new Date().toLocaleTimeString('zh-CN');
    // Sprint 13 #304: title reflects the answered intent for user queries; auto-mount
    // (forceFollowup) keeps the canonical 今日跟进清单 label.
    resultTitle.value = forceFollowup
        ? DEFAULT_RESULT_TITLE
        : (response.intentName || '查询结果');

    // 解析 resultData (Skill aggregate 后的多 Tool 结果)
    const resultData = response.resultData || {};
    extractCustomersFromResult(resultData);
    extractStaleOpportunitiesFromResult(resultData);
    extractRestaurantPnl(response.intentCode, resultData);
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

/**
 * Sprint 13 #304: extract restaurant P&L from RESTAURANT_ECONOMICS_ANALYSIS Composite
 * Tool response so the user's economics query renders an independent structured card.
 * Mirrors WarehouseKeeperWorkdesk.extractRestaurantPnl — same backend shape:
 *   resultData.summary.data.data.{headline, headlineColor, pnlLines, storeName, period}
 * When dataAvailable=false (no data for month — current S13-001 backend gap), restaurantPnl
 * stays null and the formattedText-only render carries the honest degradation message.
 */
function extractRestaurantPnl(intentCode: string | undefined,
    resultData: Record<string, unknown>) {
  restaurantPnl.value = null;
  if (intentCode !== 'RESTAURANT_ECONOMICS_ANALYSIS') return;
  const summary = resultData['summary'] as Record<string, unknown> | undefined;
  if (!summary || summary['dataAvailable'] !== true) return;
  const outer = summary['data'] as Record<string, unknown> | undefined;
  const inner = outer?.['data'] as Record<string, unknown> | undefined;
  if (!inner || typeof inner['headline'] !== 'string') return;
  const lines = inner['pnlLines'];
  if (!Array.isArray(lines)) return;
  restaurantPnl.value = {
    headline: inner['headline'] as string,
    headlineColor: (inner['headlineColor'] as string) || 'gray',
    pnlLines: lines as PnlLine[],
    storeName: (inner['storeName'] as string) || '本店',
    period: (inner['period'] as string) || '',
    subSector: (inner['subSector'] as string) || '',
  };
}

function formatPnlAmount(amount: number | null | undefined): string {
  if (amount == null) return '—';
  return new Intl.NumberFormat('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
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

// ===== Sprint 10 Loop 1 — 发货闭环 =====

interface PendingShipmentItem {
  salesOrderItemId: number;
  productTypeId: string;
  productName?: string;
  unit: string;
  orderedQuantity: number;
  deliveredQuantity: number;
  pendingQuantity: number;
}

interface PendingShipmentOrder {
  salesOrderId: string;
  orderNumber: string;
  customerId: string;
  customerName?: string;
  requiredDeliveryDate?: string;
  status: string;
  statusDisplay: string;
  itemCount: number;
  items: PendingShipmentItem[];
}

interface ShipmentItemForm extends PendingShipmentItem {
  actualQty: number;
}

interface ShipmentFeedback {
  status?: string;
  message: string;
  actionHint?: string;
  deliveryNumber?: string;
  printPath?: string;
}

const pendingShipments = ref<PendingShipmentOrder[]>([]);
const shipmentLoading = ref(false);

const shipmentDialog = reactive({
  visible: false,
  salesOrderId: '',
  orderNumber: '',
  customerName: '',
  itemForms: [] as ShipmentItemForm[],
  deliveryDate: '',
  remark: '',
  totalPending: 0,
  submitting: false,
  feedback: null as ShipmentFeedback | null,
});

const shipmentCanSubmit = computed(() => {
  if (shipmentDialog.itemForms.length === 0) return false;
  let hasPositive = false;
  for (const it of shipmentDialog.itemForms) {
    if (it.actualQty == null || isNaN(Number(it.actualQty))) return false;
    if (Number(it.actualQty) < 0) return false;
    if (Number(it.actualQty) > Number(it.pendingQuantity)) return false;
    if (Number(it.actualQty) > 0) hasPositive = true;
  }
  return hasPositive;
});

function statusTagType(status?: string): 'success' | 'warning' | 'info' | 'danger' | '' {
  if (!status) return '';
  if (status === 'FINANCE_APPROVED') return 'success';
  if (status === 'CONFIRMED') return 'info';
  if (status === 'PROCESSING') return 'warning';
  if (status === 'PARTIAL_DELIVERED') return 'warning';
  return '';
}

function shipmentFeedbackType(f: ShipmentFeedback): 'success' | 'warning' | 'error' | 'info' {
  if (f.status === 'CREATED') return 'success';
  if (f.status === 'IDEMPOTENT_HIT') return 'warning';
  if (f.status === 'ERROR') return 'error';
  return 'info';
}

async function loadTodayPendingShipments() {
  shipmentLoading.value = true;
  try {
    const res = await request.post<ExecuteResponse>(
      `/${factoryId.value}/ai-intents/execute`,
      {
        userInput: '今日 SO 待发',
        intentCode: 'SPRINT10_SHIPMENT_PENDING_TODAY',
        parameters: {},
      });
    const data = (res as { data: ExecuteResponse }).data;
    const resultData = (data.resultData || {}) as Record<string, unknown>;
    const orders = (resultData['orders'] as PendingShipmentOrder[] | undefined) || [];
    pendingShipments.value = Array.isArray(orders) ? orders : [];
  } catch (err) {
    pendingShipments.value = [];
    ElMessage({
      message: `今日待发查询失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    shipmentLoading.value = false;
  }
}

function openShipmentDialog(order: PendingShipmentOrder) {
  shipmentDialog.visible = true;
  shipmentDialog.salesOrderId = order.salesOrderId;
  shipmentDialog.orderNumber = order.orderNumber;
  shipmentDialog.customerName = order.customerName || '';
  shipmentDialog.deliveryDate = '';
  shipmentDialog.remark = '';
  shipmentDialog.feedback = null;
  shipmentDialog.itemForms = order.items.map((it) => ({
    ...it,
    actualQty: Number(it.pendingQuantity), // R1 default = max (一键)
  }));
  shipmentDialog.totalPending = order.items.reduce(
      (s, it) => s + Number(it.pendingQuantity || 0), 0);
}

function resetShipmentDialog() {
  shipmentDialog.salesOrderId = '';
  shipmentDialog.orderNumber = '';
  shipmentDialog.customerName = '';
  shipmentDialog.itemForms = [];
  shipmentDialog.deliveryDate = '';
  shipmentDialog.remark = '';
  shipmentDialog.feedback = null;
  shipmentDialog.totalPending = 0;
}

async function submitShipment() {
  if (!shipmentCanSubmit.value) {
    ElMessage.warning('请检查发货数量 (≥1 行 > 0 且不超过剩余可发)');
    return;
  }
  shipmentDialog.submitting = true;
  shipmentDialog.feedback = null;

  // 仅传 actualQty > 0 的行
  const itemsPayload = shipmentDialog.itemForms
      .filter((it) => Number(it.actualQty) > 0)
      .map((it) => ({
        salesOrderItemId: it.salesOrderItemId,
        actualQty: Number(it.actualQty),
      }));

  try {
    const res = await request.post<ExecuteResponse>(
      `/${factoryId.value}/ai-intents/execute`,
      {
        userInput: '确认发货',
        intentCode: 'SHIPMENT_CONFIRM_CREATE',
        parameters: {
          salesOrderId: shipmentDialog.salesOrderId,
          items: itemsPayload,
          deliveryDate: shipmentDialog.deliveryDate || undefined,
          remark: shipmentDialog.remark || undefined,
          testRun: false,
        },
      });
    const data = (res as { data: ExecuteResponse }).data;
    const resultData = (data.resultData || {}) as Record<string, unknown>;
    const status = String(resultData['status'] || '');
    const msg = String(resultData['message'] || data.message || '操作完成');
    const actionHint = (resultData['actionHint'] as string | undefined) || '';
    const printPath = (resultData['printPath'] as string | undefined) || '';
    const deliveryNumber = (resultData['deliveryNumber'] as string | undefined) || '';

    shipmentDialog.feedback = {
      status,
      message: msg,
      actionHint,
      deliveryNumber,
      printPath,
    };

    if (status === 'CREATED') {
      ElMessage({
        message: msg,
        type: 'success',
        duration: 5000,
        showClose: true,
      });
      // Refresh pending list
      await loadTodayPendingShipments();
      // Keep dialog open for user to see feedback + print link (R5 dead-end avoid)
    } else if (status === 'IDEMPOTENT_HIT') {
      ElMessage({
        message: msg,
        type: 'warning',
        duration: 0,
        showClose: true,
      });
    }
  } catch (err) {
    const msg = extractErrorMessage(err);
    shipmentDialog.feedback = { status: 'ERROR', message: msg };
    ElMessage({
      message: `发货失败: ${msg}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    shipmentDialog.submitting = false;
  }
}

// Auto-trigger on mount
onMounted(() => {
  void triggerFollowupQuery();
  void loadTodayPendingShipments();
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

/* Sprint 13 #304 — restaurant P&L card styles (mirror WarehouseKeeperWorkdesk) */
.restaurant-pnl-card {
  margin-bottom: 16px;
  border: 1px solid #e0e6ed;
}
.pnl-headline {
  font-size: 20px;
  font-weight: 600;
  padding: 12px 8px;
  margin-bottom: 12px;
  border-radius: 4px;
}
.pnl-headline-green {
  background-color: #f0f9eb;
  color: #67c23a;
}
.pnl-headline-red {
  background-color: #fef0f0;
  color: #f56c6c;
}
.pnl-headline-yellow {
  background-color: #fdf6ec;
  color: #e6a23c;
}
.pnl-headline-gray {
  background-color: #f4f4f5;
  color: #909399;
}
.pnl-table {
  margin-top: 8px;
}
.cell-empty {
  color: #c0c4cc;
}

.customers-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}

/* Sprint 11 D7 — Indicators grid (4 BI Tool cards) */
.indicators-card {
  margin-top: 12px;
}

.indicators-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
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
