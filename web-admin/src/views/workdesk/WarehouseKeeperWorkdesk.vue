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
 <span class="emoji"></span>
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
 <span> 与 AI 对话</span>
          <span class="header-hint">默认查询: "今天要收什么货?" — 也可输入其他问题</span>
        </div>
      </template>
      <div class="chat-input">
        <el-input
          v-model="userInput"
          type="textarea"
          :rows="2"
          placeholder="例如: 今天要收什么货? / 临期物料建议 / 今天哪些要质检?"
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

    <!-- Sprint 11 Q6 Option B.6 (2026-05-24): Restaurant P&L card render
         when RESTAURANT_ECONOMICS_ANALYSIS intent returns dataAvailable=true.
         Without this card, customer only saw "部分数据不可用" message and never
         saw the ¥1,935,193 number that backend already computed. -->
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
      <div class="formatted-output">{{ formattedText }}</div>
    </el-card>

    <!-- 待收行清单 (R1 max 灵魂: 直接显已订/已收/可入) -->
    <el-card v-if="receivingRows.length > 0" class="receiving-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 待收行项目 ({{ receivingRows.length }})</span>
          <span class="header-hint">已订/已收/还可入 直接告诉你要收多少</span>
        </div>
        <div class="header-actions-inline">
          <el-button
            size="small"
            type="primary"
            :disabled="selectedRows.length === 0"
            @click="generateScanTask">
 一键扫码 ({{ selectedRows.length }})
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
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openReceiveDialog(row)">
              快速收货
            </el-button>
            <el-button
              size="small"
              type="primary"
              data-testid="confirm-receive-btn"
              @click="openConfirmReceiveDialog(row)">
              确认收货
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 临期物料处置建议侧栏 -->
    <el-card v-if="disposalRecommendations.length > 0" class="disposal-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span>️ 临期物料处置建议 ({{ disposalRecommendations.length }})</span>
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
 <span> 待质检批次 ({{ qcInspecting.length }})</span>
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

    <!-- ===== Sprint 10 Loop 2 确认收货 Dialog (R1 max + R2 context + R3 status + R4 idempotent) ===== -->
    <el-dialog
      v-model="confirmReceiveDialog.visible"
      :title="confirmReceiveDialogTitle"
      width="600px"
      data-testid="confirm-receive-dialog"
      @close="resetConfirmReceiveDialog">
      <el-form :model="confirmReceiveDialog.form" label-width="120px">
        <!-- R2: 供应商 + PO 单号 + 物料 必显 (Context) -->
        <el-form-item label="PO 单号">
          <el-input :value="confirmReceiveDialog.row?.orderNumber || ''" disabled />
        </el-form-item>
        <el-form-item label="供应商">
          <el-input :value="confirmReceiveDialog.row?.supplierName || ''" disabled />
        </el-form-item>
        <el-form-item label="物料">
          <el-input :value="confirmReceiveDialog.row?.materialName || ''" disabled />
        </el-form-item>
        <!-- R1: 已订 / 已收 / 还可入 (边界 max) -->
        <el-form-item label="已订 / 已收">
          <el-text>
            {{ formatQty(confirmReceiveDialog.row?.orderedQuantity) }}{{ confirmReceiveDialog.row?.unit }}
            / {{ formatQty(confirmReceiveDialog.row?.receivedQuantity) }}{{ confirmReceiveDialog.row?.unit }}
          </el-text>
        </el-form-item>
        <el-form-item label="还可入">
          <div class="qty-hint-block">
            <el-text size="small">
              还要收 <b>{{ formatQty(confirmReceiveDialog.row?.pendingQuantity) }}</b>
              {{ confirmReceiveDialog.row?.unit }}
              · 30% 超收上限 = {{ formatQty(confirmReceiveDialog.row?.remainingCap) }}{{ confirmReceiveDialog.row?.unit }}
            </el-text>
          </div>
        </el-form-item>
        <el-form-item label="本次实收" required>
          <el-input-number
            v-model="confirmReceiveDialog.form.receivedQty"
            :min="0"
            :max="confirmMaxAllowedReceive"
            :precision="2"
            :step="1"
            placeholder="输入本次实收数量"
            data-testid="confirm-received-qty-input"
            style="width: 220px" />
          <el-text size="small" class="form-hint">
            {{ confirmReceiveDialog.row?.unit || '' }} (上限 {{ formatQty(confirmMaxAllowedReceive) }})
          </el-text>
        </el-form-item>
        <!-- R3: 收货状态 dropdown (标准枚举, 不让仓管员自由文本) -->
        <el-form-item label="收货状态" required>
          <el-select
            v-model="confirmReceiveDialog.form.receiveStatus"
            data-testid="receive-status-select"
            style="width: 220px">
            <el-option label="PASS — 全数完好" value="PASS" />
            <el-option label="PARTIAL_LOST — 数量短少" value="PARTIAL_LOST" />
            <el-option label="DAMAGED — 包装破损" value="DAMAGED" />
            <el-option label="OTHER — 其他 (备注详述)" value="OTHER" />
          </el-select>
        </el-form-item>
        <!-- 验签 checkbox -->
        <el-form-item label="现场验签">
          <el-checkbox
            v-model="confirmReceiveDialog.form.signatureConfirmed"
            data-testid="signature-confirmed-checkbox">
            已现场签收/验签
          </el-checkbox>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="confirmReceiveDialog.form.remark"
            placeholder="(可选) 例如: 到货 OK / 部分破损 / 验签人: 张师傅"
            maxlength="200"
            show-word-limit />
        </el-form-item>
        <el-alert
          v-if="confirmReceiveDialog.preview"
          :type="previewAlertType(confirmReceiveDialog.preview)"
          :closable="false"
          show-icon
          class="preview-alert">
          <template #title>
            <span data-testid="confirm-preview-message">{{ confirmReceiveDialog.preview.message }}</span>
          </template>
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="confirmReceiveDialog.visible = false">取消</el-button>
        <el-button
          :loading="confirmReceiveDialog.previewing"
          :disabled="!canPreviewConfirm"
          data-testid="confirm-preview-btn"
          @click="previewConfirmReceive">
          预览边界
        </el-button>
        <el-button
          type="primary"
          :loading="confirmReceiveDialog.submitting"
          :disabled="!confirmReceiveDialog.canSubmit || isConfirmOverLimit"
          data-testid="confirm-submit-btn"
          @click="executeConfirmReceive">
          确认提交
        </el-button>
      </template>
    </el-dialog>

    <!-- ===== PDA 扫码任务 Dialog ===== -->
    <el-dialog
      v-model="scanTaskDialog.visible"
 title=" PDA / 手机扫码任务"
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
          <div class="qr-display-box">
            <QrCodeDisplay
              :value="scanTaskDialog.task.qrPayload"
              :size="220"
              :show-label="false"
              error-correction-level="M" />
            <el-text size="small" type="info" class="qr-payload-hint">
              用 PDA 摄像头 / 手机微信扫一扫扫描上方二维码
            </el-text>
            <details class="qr-payload-details">
              <summary>
                <el-text size="small" type="info">展开原始 URL (备用)</el-text>
              </summary>
              <pre class="qr-payload-text">{{ scanTaskDialog.task.qrPayload }}</pre>
            </details>
          </div>
          <el-text size="small">
            <strong>下一步</strong>: 扫码后任务会自动加载. 扫码完成后一键提交即转入入库流程.
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
import QrCodeDisplay from '@/components/common/QrCodeDisplay.vue';

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
  intentName?: string;
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
// Sprint 13 #304: dynamic result-card header — reflects the answered intent for user
// queries instead of always showing the auto-mount "今日待收清单" label.
const DEFAULT_RESULT_TITLE = '今日待收清单';
const resultTitle = ref(DEFAULT_RESULT_TITLE);

const receivingRows = ref<ReceivingRow[]>([]);
const disposalRecommendations = ref<DisposalRow[]>([]);
const qcInspecting = ref<QcRow[]>([]);
const selectedRows = ref<ReceivingRow[]>([]);

// Sprint 11 Q6 Option B.6 (2026-05-24): render real P&L card when
// RESTAURANT_ECONOMICS_ANALYSIS intent returns store_pnl_one_pager data.
// Without this, customer only sees the summary message ("部分数据不可用...")
// and never sees the ¥1,935,193 number that's already in the API response.
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
const restaurantPnl = ref<RestaurantPnl | null>(null);

const factoryId = computed(() => authStore.factoryId || 'F006');

async function callIntentExecute(input: string, intentCode?: string,
    parameters?: Record<string, unknown>, preview = false,
    context?: Record<string, unknown>): Promise<ExecuteResponse> {
  const body: Record<string, unknown> = { userInput: input };
  if (intentCode) body.intentCode = intentCode;
  if (parameters) body.parameters = parameters;
  if (preview) body.preview = true;
  // Sprint 11 Q6 Option B (2026-05-24): pass context so backend can disambiguate
  // period-bounded P&L / loss / cost queries from text-only "哪个菜亏钱" inputs.
  // Without context, BERT classifier (no role/factoryType signal) tends to misroute
  // ambiguous restaurant-economics phrases on warehouse-keeper Workdesk →
  // MATERIAL_TODAY_RECEIVING_QUERY. context.month gives downstream Tool the period
  // bound it needs to produce real P&L output.
  if (context) body.context = context;
  const res = await request.post<ExecuteResponse>(
    `/${factoryId.value}/ai-intents/execute`, body);
  return (res as { data: ExecuteResponse }).data;
}

/**
 * Parse month from user input. Supports:
 *   - "2025-12" / "2025/12"  → "2025-12"
 *   - "2025年12月" / "2025年12月份" → "2025-12"
 *   - "12月" / "12月份" → current-year + 12 month (e.g. "2026-12")
 *   - "本月" / "这个月" → current calendar month
 *   - "上月" / "上个月" → previous calendar month
 *   - (none of above) → undefined (let backend default)
 *
 * Returns canonical "YYYY-MM" string or undefined.
 */
function parseMonthFromInput(input: string): string | undefined {
  if (!input) return undefined;
  // Explicit YYYY-MM or YYYY/MM
  const isoMatch = input.match(/(\d{4})[-/](\d{1,2})/);
  if (isoMatch) {
    const y = isoMatch[1];
    const m = String(isoMatch[2]).padStart(2, '0');
    return `${y}-${m}`;
  }
  // YYYY年M月
  const cnYearMatch = input.match(/(\d{4})年(\d{1,2})月/);
  if (cnYearMatch) {
    const y = cnYearMatch[1];
    const m = String(cnYearMatch[2]).padStart(2, '0');
    return `${y}-${m}`;
  }
  // 本月 / 这个月
  if (input.includes('本月') || input.includes('这个月') || input.includes('当月')) {
    const now = new Date();
    const m = String(now.getMonth() + 1).padStart(2, '0');
    return `${now.getFullYear()}-${m}`;
  }
  // 上月 / 上个月 / 上一个月
  if (input.includes('上月') || input.includes('上个月') || input.includes('上一个月')) {
    const now = new Date();
    const prev = new Date(now.getFullYear(), now.getMonth() - 1, 1);
    const m = String(prev.getMonth() + 1).padStart(2, '0');
    return `${prev.getFullYear()}-${m}`;
  }
  // M月 (current year inferred). Avoid matching incidental digits — require explicit "月".
  const cnMonthMatch = input.match(/(?<![\d年])(\d{1,2})月(?:份)?/);
  if (cnMonthMatch) {
    const now = new Date();
    const m = String(cnMonthMatch[1]).padStart(2, '0');
    return `${now.getFullYear()}-${m}`;
  }
  return undefined;
}

/**
 * Heuristic: is the input asking about period-bounded P&L / cost / loss?
 * Used to decide whether to attach context.month for the free-text path.
 * Keep deliberately narrow to avoid sending month context for non-restaurant
 * questions (e.g. "今天要收什么货?" should NOT carry month context).
 */
function looksLikeRestaurantEconomicsQuery(input: string): boolean {
  if (!input) return false;
  const economicsKeywords = [
    '损溢', '损益', '亏', '利润', '毛利', '成本',
    '盈利', '赚', 'P&L', 'p&l', '经营',
  ];
  return economicsKeywords.some((k) => input.includes(k));
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
    restaurantPnl.value = null;
  }

  try {
    // 1. 今日待收 (主路径)
    const intentCode = autoTrigger ? 'MATERIAL_TODAY_RECEIVING_QUERY' : undefined;
    // Sprint 11 Q6 Option B: for free-text (non auto-trigger), if input looks like a
    // restaurant economics query (损溢/损益/亏/利润/成本/...), attach context.month so
    // backend Tools have the period bound. Default to "上月" when no explicit period
    // word is found — matches customer's most common ask "上月损溢" pattern.
    let context: Record<string, unknown> | undefined;
    if (!autoTrigger && looksLikeRestaurantEconomicsQuery(userInput.value)) {
      const month = parseMonthFromInput(userInput.value) ?? (() => {
        // default 上月
        const now = new Date();
        const prev = new Date(now.getFullYear(), now.getMonth() - 1, 1);
        return `${prev.getFullYear()}-${String(prev.getMonth() + 1).padStart(2, '0')}`;
      })();
      context = { month };
    }
    const response = await callIntentExecute(userInput.value, intentCode,
        undefined, false, context);
    formattedText.value = response.formattedText || response.message || '(无输出)';
    lastQueryTime.value = new Date().toLocaleTimeString('zh-CN');
    // Sprint 13 #304: title reflects the answered intent for user queries; auto-trigger
    // keeps the canonical 今日待收清单 label.
    resultTitle.value = autoTrigger
        ? DEFAULT_RESULT_TITLE
        : (response.intentName || '查询结果');

    const resultData = (response.resultData || {}) as Record<string, unknown>;
    extractReceivingRows(resultData);
    extractRestaurantPnl(response.intentCode, resultData);

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

/**
 * Sprint 11 Q6 Option B.6: extract restaurant P&L from RESTAURANT_ECONOMICS_ANALYSIS
 * Composite Tool response so UI can render the real headline + pnlLines.
 *
 * API shape (RestaurantEconomicsAnalysisTool → store_pnl_one_pager):
 *   resultData.summary.data.data.{headline, headlineColor, pnlLines, storeName, period, subSector}
 * When dataAvailable=false (no Phase F.1 data for month), restaurantPnl stays null
 * and we fall back to the formattedText-only render.
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

// ===== Sprint 10 Loop 2 — Confirm Receive Dialog (R3 status dropdown + R4 idempotent) =====

interface ConfirmReceivePreview extends PreviewState {
  existingId?: string;
  existingReceiveNumber?: string;
  actionHint?: string;
}

const confirmReceiveDialog = reactive<{
  visible: boolean;
  row: ReceivingRow | null;
  form: {
    receivedQty: number | null;
    receiveStatus: 'PASS' | 'PARTIAL_LOST' | 'DAMAGED' | 'OTHER';
    signatureConfirmed: boolean;
    remark: string;
  };
  preview: ConfirmReceivePreview | null;
  previewing: boolean;
  submitting: boolean;
  canSubmit: boolean;
}>({
  visible: false,
  row: null,
  form: {
    receivedQty: null,
    receiveStatus: 'PASS',
    signatureConfirmed: false,
    remark: '',
  },
  preview: null,
  previewing: false,
  submitting: false,
  canSubmit: false,
});

const confirmReceiveDialogTitle = computed(() => {
  const r = confirmReceiveDialog.row;
  return r
    ? `确认收货 — ${r.supplierName ?? ''} (${r.orderNumber})`
    : '确认收货';
});

const confirmMaxAllowedReceive = computed(() =>
  confirmReceiveDialog.row?.remainingCap ?? 0);

const isConfirmOverLimit = computed(() => {
  const qty = confirmReceiveDialog.form.receivedQty;
  if (qty == null) return false;
  return qty > (confirmReceiveDialog.row?.remainingCap ?? 0);
});

const canPreviewConfirm = computed(() => {
  const qty = confirmReceiveDialog.form.receivedQty;
  return qty != null && qty > 0
    && !!confirmReceiveDialog.form.receiveStatus;
});

function openConfirmReceiveDialog(row: ReceivingRow) {
  confirmReceiveDialog.visible = true;
  confirmReceiveDialog.row = row;
  confirmReceiveDialog.form.receivedQty = row.pendingQuantity;  // default 全收
  confirmReceiveDialog.form.receiveStatus = 'PASS';
  confirmReceiveDialog.form.signatureConfirmed = false;
  confirmReceiveDialog.form.remark = '';
  confirmReceiveDialog.preview = null;
  confirmReceiveDialog.canSubmit = false;
}

function resetConfirmReceiveDialog() {
  confirmReceiveDialog.row = null;
  confirmReceiveDialog.form.receivedQty = null;
  confirmReceiveDialog.form.receiveStatus = 'PASS';
  confirmReceiveDialog.form.signatureConfirmed = false;
  confirmReceiveDialog.form.remark = '';
  confirmReceiveDialog.preview = null;
  confirmReceiveDialog.canSubmit = false;
}

async function previewConfirmReceive() {
  if (!confirmReceiveDialog.row || confirmReceiveDialog.form.receivedQty == null) {
    ElMessage.warning('请填写实收数量');
    return;
  }
  confirmReceiveDialog.previewing = true;
  try {
    const response = await callIntentExecute('预览确认收货', 'RECEIVE_CONFIRM_CREATE', {
      poId: confirmReceiveDialog.row.poId,
      lineId: confirmReceiveDialog.row.lineId,
      receivedQty: confirmReceiveDialog.form.receivedQty,
      receiveStatus: confirmReceiveDialog.form.receiveStatus,
      signatureConfirmed: confirmReceiveDialog.form.signatureConfirmed,
      remark: confirmReceiveDialog.form.remark,
      // testRun is added by Playwright via window override (see executeConfirmReceive)
    }, true);
    const previewData = (response.resultData || {}) as ConfirmReceivePreview;
    confirmReceiveDialog.preview = previewData;
    confirmReceiveDialog.canSubmit = previewData.canDo === true
        && previewData.status === 'PREVIEW';
  } catch (err) {
    ElMessage({
      message: `预览失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    confirmReceiveDialog.previewing = false;
  }
}

async function executeConfirmReceive() {
  if (!confirmReceiveDialog.canSubmit) {
    ElMessage.warning('请先点击 [预览边界] 验证可提交');
    return;
  }
  if (!confirmReceiveDialog.row || confirmReceiveDialog.form.receivedQty == null) return;
  confirmReceiveDialog.submitting = true;
  try {
    // Playwright 通过 window.__SPRINT10_TEST_RUN__ = true 触发 testRun 标记
    const testRun = typeof window !== 'undefined'
      && (window as unknown as { __SPRINT10_TEST_RUN__?: boolean }).__SPRINT10_TEST_RUN__ === true;
    const response = await callIntentExecute('确认收货 (Sprint 10 Loop 2)', 'RECEIVE_CONFIRM_CREATE', {
      poId: confirmReceiveDialog.row.poId,
      lineId: confirmReceiveDialog.row.lineId,
      receivedQty: confirmReceiveDialog.form.receivedQty,
      receiveStatus: confirmReceiveDialog.form.receiveStatus,
      signatureConfirmed: confirmReceiveDialog.form.signatureConfirmed,
      remark: confirmReceiveDialog.form.remark,
      testRun,
    });
 ElMessage.success(response.message || ' 入库成功');
    confirmReceiveDialog.visible = false;
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
    confirmReceiveDialog.submitting = false;
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

/* Sprint 11 Q6 Option B.6 — restaurant P&L card styles */
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

.qr-display-box {
  margin: 12px 0;
  padding: 16px;
  background: #f5f7fa;
  border-radius: 4px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

.qr-payload-hint {
  margin-top: 4px;
  text-align: center;
}

.qr-payload-details {
  width: 100%;
  margin-top: 4px;
}

.qr-payload-details summary {
  cursor: pointer;
  padding: 4px 0;
  user-select: none;
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
