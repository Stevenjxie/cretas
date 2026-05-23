<!--
  QualityManagerWorkdesk.vue — Sprint 8 P3 食品安全召回 Workdesk

  F006 真场景 (Boss 演示弹药 #3 — HJ vs Cretas 杀手锏): 客户投诉吃了拉肚子.
  HJ 模式: 客户列表查订单 → 找出货记录 → 查批次 → 查 HACCP → 查供应商 → 翻 GB 2760
            → 手算影响客户 → Word 写监管报告 (~30 min, 6 屏跳来跳去).
  Cretas Workdesk: 1 屏 30 秒 + 4 button 一键执行召回闭环.

  架构: 复用 /ai-intents/execute POST 端点, 触发 FOOD_SAFETY_RECALL intent.
  Backend 路由到 food-safety-recall Skill (串 8 Tool + LLM aggregate decisions).

  防呆 4 位一体:
  - R1 max: 4 个执行按钮全先 preview 后 execute (BLOCKING modal)
  - R2 context: dialog header 含 召回事件 / 批次号 / 客户名
  - R5 dead-end: 失败 toast duration:0 + showClose, 含 actionHint
-->
<template>
  <div class="quality-manager-workdesk">
    <!-- Header -->
    <div class="workdesk-header">
      <div class="header-title">
 <span class="emoji"></span>
        <span class="title-text">质量主管工作台</span>
        <el-tag size="small" type="danger">Sprint 8 P3 (2026-05-20)</el-tag>
      </div>
      <div class="header-actions">
        <el-button type="danger" :icon="Warning" @click="openRecallStartDialog">
 启动召回
        </el-button>
        <el-button :loading="loading" :icon="Refresh" @click="triggerHaccpQuery">
          重新查询
        </el-button>
      </div>
    </div>

    <!-- AI Chat 输入区 -->
    <el-card class="chat-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 与 AI 对话</span>
          <span class="header-hint">默认: "今天 HACCP 监控全通过吗?" — 或输入其他食品安全问题</span>
        </div>
      </template>
      <div class="chat-input">
        <el-input
          v-model="userInput"
          type="textarea"
          :rows="2"
          placeholder="例如: 今天 HACCP 监控全通过吗? / B-X 批次添加剂合规吗? / 鲜湘缘 5/18 那批是哪批?"
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
        <span>AI 正在运行 food-safety-recall Skill (串 8 Tool: batch 追溯 + HACCP audit + GB 2760 + 影响客户 + 损失预估), 预计 5-15 秒...</span>
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

    <!-- AI 输出 (LLM aggregate 召回行动方案) -->
    <el-card v-if="formattedText" class="result-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 召回分析结果</span>
          <span class="header-hint" v-if="lastQueryTime">
            {{ lastQueryTime }} 生成
          </span>
        </div>
      </template>
      <div class="formatted-output" v-html="renderedFormattedText"></div>
    </el-card>

    <!-- 原料追溯卡 -->
    <el-card v-if="recallContext.batchNumber" class="trace-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 原料追溯 — {{ recallContext.batchNumber }}</span>
          <el-tag v-if="recallContext.productName" size="small" type="info">
            {{ recallContext.productName }}
          </el-tag>
        </div>
      </template>
      <div class="trace-content">
        <div class="info-row" v-if="recallContext.materialBatchCount > 0">
          <span class="label">涉及原料批次:</span>
          <span class="value">{{ recallContext.materialBatchCount }} 个</span>
        </div>
        <div class="info-row" v-if="recallContext.affectedShipmentCount > 0">
          <span class="label">影响出货笔数:</span>
          <span class="value">{{ recallContext.affectedShipmentCount }} 笔</span>
        </div>
        <div class="info-row" v-if="recallContext.affectedCustomerCount > 0">
          <span class="label">影响客户:</span>
          <span class="value">{{ recallContext.affectedCustomerCount }} 家</span>
        </div>
      </div>
    </el-card>

    <!-- HACCP audit 卡 (deviation 高亮) -->
    <el-card v-if="haccpRecords.length > 0" class="haccp-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span>️ HACCP 监控审查 ({{ haccpRecords.length }} 记录)</span>
          <el-tag v-if="haccpDeviationCount > 0" type="danger" size="small">
            {{ haccpDeviationCount }} 偏差
          </el-tag>
          <el-tag v-else type="success" size="small">全部达标</el-tag>
        </div>
      </template>
      <el-table :data="haccpRecords" size="small" style="width: 100%" :row-class-name="haccpRowClass">
        <el-table-column prop="checkpointName" label="监控点" min-width="120" />
        <el-table-column prop="recordedAt" label="时间" width="170">
          <template #default="{ row }">
            {{ formatDateTime(row.recordedAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="recordedValue" label="实测值" width="100">
          <template #default="{ row }">
            <span :class="row.isDeviation ? 'text-danger' : ''">
              {{ row.recordedValue }}{{ row.unit || '' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="阈值" width="140">
          <template #default="{ row }">
            {{ row.limitLow }} ~ {{ row.limitHigh }}{{ row.unit || '' }}
          </template>
        </el-table-column>
        <el-table-column prop="recordedBy" label="记录人" width="100" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.isDeviation ? 'danger' : 'success'" size="small">
 {{ row.isDeviation ? '️ 偏差' : ' 达标' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- GB 2760 添加剂合规卡 -->
    <el-card v-if="additiveChecks.length > 0" class="additive-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> GB 2760 添加剂合规 ({{ additiveChecks.length }} 项)</span>
          <el-tag v-if="additiveExceedCount > 0" type="danger" size="small">
            {{ additiveExceedCount }} 超限
          </el-tag>
          <el-tag v-else type="success" size="small">全部合规</el-tag>
        </div>
      </template>
      <el-table :data="additiveChecks" size="small" style="width: 100%" :row-class-name="additiveRowClass">
        <el-table-column prop="additiveName" label="添加剂" min-width="120" />
        <el-table-column prop="actualValue" label="实测" width="110">
          <template #default="{ row }">
            <span :class="row.isExceed ? 'text-danger' : ''">
              {{ row.actualValue }} {{ row.unit || 'mg/kg' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="limitValue" label="GB 2760 限量" width="140">
          <template #default="{ row }">
            {{ row.limitValue }} {{ row.unit || 'mg/kg' }}
          </template>
        </el-table-column>
        <el-table-column prop="productCategory" label="产品类别" min-width="140" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.isExceed ? 'danger' : 'success'" size="small">
 {{ row.isExceed ? ' 超限' : ' 合规' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 影响客户列表 -->
    <el-card v-if="affectedCustomers.length > 0" class="customers-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 影响客户清单 ({{ affectedCustomers.length }})</span>
          <span class="header-hint">建议立即通知, 防止次生事故</span>
        </div>
      </template>
      <el-table :data="affectedCustomers" size="small" style="width: 100%">
        <el-table-column prop="customerName" label="客户名" min-width="160" />
        <el-table-column prop="productName" label="购买产品" min-width="140" />
        <el-table-column prop="quantity" label="数量" width="100">
          <template #default="{ row }">
            {{ row.quantity }} {{ row.quantityUnit || '' }}
          </template>
        </el-table-column>
        <el-table-column prop="shipmentDate" label="出货日期" width="120" />
        <el-table-column prop="contactPhone" label="联系电话" width="140" />
      </el-table>
    </el-card>

    <!-- 召回损失预估卡 -->
    <el-card v-if="lossEstimate.totalLoss > 0" class="loss-card" shadow="hover">
      <template #header>
        <div class="card-header">
 <span> 召回损失预估</span>
        </div>
      </template>
      <div class="loss-grid">
        <div class="loss-item">
          <div class="loss-label">冻结库存价值</div>
          <div class="loss-value">¥{{ formatAmount(lossEstimate.inventoryValue) }}</div>
        </div>
        <div class="loss-item">
          <div class="loss-label">客户退货预估</div>
          <div class="loss-value">¥{{ formatAmount(lossEstimate.returnValue) }}</div>
        </div>
        <div class="loss-item">
          <div class="loss-label">行政成本</div>
          <div class="loss-value">¥{{ formatAmount(lossEstimate.adminCost) }}</div>
        </div>
        <div class="loss-item loss-total">
          <div class="loss-label">总损失预估</div>
          <div class="loss-value">¥{{ formatAmount(lossEstimate.totalLoss) }}</div>
        </div>
      </div>
    </el-card>

    <!-- 4 个一键执行按钮卡 (R1+R2+R4: preview → confirm → execute) -->
    <el-card v-if="recallContext.batchNumber || recallContext.recallEventId" class="actions-card" shadow="never">
      <template #header>
        <div class="card-header">
 <span> 召回行动 — 一键执行</span>
          <span class="header-hint">每按钮先 preview 后执行 · 全程 < 2 min</span>
        </div>
      </template>
      <div class="actions-grid">
        <el-button
          type="primary"
          size="large"
          :icon="Box"
          @click="openInventoryFreezeDialog">
 冻结库存
        </el-button>
        <el-button
          type="warning"
          size="large"
          :icon="Bell"
          @click="openCustomerNotifyDialog">
 通知客户
        </el-button>
        <el-button
          type="info"
          size="large"
          :icon="Document"
          @click="openRegulatoryReportDialog">
 生成监管文件
        </el-button>
        <el-button
          type="success"
          size="large"
          :icon="CircleCheck"
          @click="openCloseEventDialog">
 关闭事件
        </el-button>
      </div>
    </el-card>

    <!-- ===== Dialog 1: 启动召回 (R2: 输入投诉信息 → 触发 food-safety-recall Skill) ===== -->
    <el-dialog
      v-model="recallStartDialog.visible"
 title=" 启动食品安全召回 — 输入投诉信息"
      width="640px"
      @close="resetRecallStartDialog">
      <el-form :model="recallStartDialog.form" label-width="120px">
        <el-form-item label="客户名" required>
          <el-input
            v-model="recallStartDialog.form.customerName"
            placeholder="例如: 鲜湘缘餐厅"
            clearable />
        </el-form-item>
        <el-form-item label="投诉日期" required>
          <el-date-picker
            v-model="recallStartDialog.form.complaintDate"
            type="date"
            placeholder="选择投诉日期"
            value-format="YYYY-MM-DD"
            style="width: 100%" />
        </el-form-item>
        <el-form-item label="投诉描述" required>
          <el-input
            v-model="recallStartDialog.form.complaintDescription"
            type="textarea"
            :rows="3"
            placeholder="例如: 客户吃了拉肚子, 怀疑卤猪蹄变质"
            maxlength="500"
            show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="recallStartDialog.visible = false">取消</el-button>
        <el-button
          type="danger"
          :loading="recallStartDialog.analyzing"
          :disabled="!canStartRecall"
          @click="startRecallAnalysis">
          开始召回分析
        </el-button>
      </template>
    </el-dialog>

    <!-- ===== Dialog 2: 冻结库存 (R1: 预览数量 + R2: 含批次号) ===== -->
    <el-dialog
      v-model="freezeDialog.visible"
 :title="` 冻结库存 — 召回事件 #${recallContext.recallEventId || '未创建'} / 批次 ${recallContext.batchNumber || ''}`"
      width="560px"
      @close="resetFreezeDialog">
      <el-form :model="freezeDialog.form" label-width="100px">
        <el-form-item label="批次号">
          <el-input :value="recallContext.batchNumber" disabled />
        </el-form-item>
        <el-form-item label="冻结原因" required>
          <el-input
            v-model="freezeDialog.form.reason"
            type="textarea"
            :rows="2"
            placeholder="例如: 客户投诉拉肚子, 启动召回"
            maxlength="200"
            show-word-limit />
        </el-form-item>
        <el-alert
          v-if="freezeDialog.preview"
          :type="previewAlertType(freezeDialog.preview)"
          :closable="false"
          show-icon
          class="preview-alert">
          <template #title>
            <span>{{ freezeDialog.preview.message }}</span>
          </template>
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="freezeDialog.visible = false">取消</el-button>
        <el-button
          :loading="freezeDialog.previewing"
          @click="previewFreeze">
          预览
        </el-button>
        <el-button
          type="primary"
          :loading="freezeDialog.submitting"
          :disabled="!freezeDialog.canSubmit"
          @click="executeFreeze">
          确认冻结
        </el-button>
      </template>
    </el-dialog>

    <!-- ===== Dialog 3: 通知客户 (R1: 预览短信草稿 + 影响客户数) ===== -->
    <el-dialog
      v-model="notifyDialog.visible"
 :title="` 通知客户 — 召回事件 #${recallContext.recallEventId || '未创建'} / 批次 ${recallContext.batchNumber || ''}`"
      width="640px"
      @close="resetNotifyDialog">
      <el-form :model="notifyDialog.form" label-width="100px">
        <el-form-item label="批次号">
          <el-input :value="recallContext.batchNumber" disabled />
        </el-form-item>
        <el-form-item label="召回事件 ID">
          <el-input :value="recallContext.recallEventId || '(未创建)'" disabled />
        </el-form-item>
        <el-form-item label="短信模板">
          <el-input
            v-model="notifyDialog.form.message"
            type="textarea"
            :rows="3"
            placeholder="(可选) 自定义召回通知消息. 留空则自动生成"
            maxlength="500"
            show-word-limit />
        </el-form-item>
        <el-alert
          v-if="notifyDialog.preview"
          :type="previewAlertType(notifyDialog.preview)"
          :closable="false"
          show-icon
          class="preview-alert">
          <template #title>
            <span>{{ notifyDialog.preview.message }}</span>
          </template>
          <template #default v-if="notifyDialog.preview.smsDraft">
            <div class="sms-preview">
              <div class="sms-label">短信草稿:</div>
              <div class="sms-content">{{ notifyDialog.preview.smsDraft }}</div>
            </div>
          </template>
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="notifyDialog.visible = false">取消</el-button>
        <el-button
          :loading="notifyDialog.previewing"
          @click="previewNotify">
          预览
        </el-button>
        <el-button
          type="warning"
          :loading="notifyDialog.submitting"
          :disabled="!notifyDialog.canSubmit"
          @click="executeNotify">
          确认发送
        </el-button>
      </template>
    </el-dialog>

    <!-- ===== Dialog 4: 生成监管文件 (输出 markdown + 下载) ===== -->
    <el-dialog
      v-model="reportDialog.visible"
 :title="` 生成监管上报文件 — 召回事件 #${recallContext.recallEventId || '未创建'}`"
      width="720px"
      @close="resetReportDialog">
      <el-form :model="reportDialog.form" label-width="100px">
        <el-form-item label="召回事件 ID">
          <el-input :value="recallContext.recallEventId || '(未创建)'" disabled />
        </el-form-item>
        <el-alert v-if="!recallContext.recallEventId"
          type="warning" show-icon :closable="false" class="preview-alert">
          <template #title>
 <span>️ 未创建召回事件 ID. 请先在 召回 Skill 中初始化事件后再生成监管文件.</span>
          </template>
        </el-alert>
        <el-input
          v-if="reportDialog.markdownContent"
          v-model="reportDialog.markdownContent"
          type="textarea"
          :rows="14"
          readonly
          class="markdown-output" />
      </el-form>
      <template #footer>
        <el-button @click="reportDialog.visible = false">取消</el-button>
        <el-button
          type="info"
          :loading="reportDialog.submitting"
          :disabled="!recallContext.recallEventId"
          @click="executeGenerateReport">
          生成报告
        </el-button>
        <el-button
          v-if="reportDialog.markdownContent"
          type="success"
          @click="downloadReport">
 下载 Markdown
        </el-button>
      </template>
    </el-dialog>

    <!-- ===== Dialog 5: 关闭召回事件 (R2: 含事件号 + 总结) ===== -->
    <el-dialog
      v-model="closeDialog.visible"
 :title="` 关闭召回事件 #${recallContext.recallEventId || '未创建'}`"
      width="560px"
      @close="resetCloseDialog">
      <el-form :model="closeDialog.form" label-width="100px">
        <el-form-item label="召回事件 ID">
          <el-input :value="recallContext.recallEventId || '(未创建)'" disabled />
        </el-form-item>
        <el-form-item label="关闭总结" required>
          <el-input
            v-model="closeDialog.form.summary"
            type="textarea"
            :rows="3"
            placeholder="例如: 已冻结 28 斤库存, 通知 12 家客户, 监管文件已上报食药监"
            maxlength="500"
            show-word-limit />
        </el-form-item>
        <el-alert type="info" show-icon :closable="false" class="preview-alert">
          <template #title>
            <span>ℹ️ Phase 1 状态机 stub: 关闭操作记入 RecallAction 表 (CLOSE type). 未来 Phase 集成 RecallEventService 完整流程</span>
          </template>
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="closeDialog.visible = false">取消</el-button>
        <el-button
          type="success"
          :loading="closeDialog.submitting"
          :disabled="!recallContext.recallEventId || !closeDialog.form.summary.trim()"
          @click="executeCloseEvent">
          确认关闭
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, reactive } from 'vue';
import { ElMessage } from 'element-plus';
import {
  Refresh, Loading, Warning, Box, Bell, Document, CircleCheck,
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

interface HaccpRow {
  checkpointName?: string;
  recordedAt?: string;
  recordedValue?: number | string;
  limitLow?: number | string;
  limitHigh?: number | string;
  unit?: string;
  recordedBy?: string;
  isDeviation?: boolean;
}

interface AdditiveRow {
  additiveName?: string;
  actualValue?: number | string;
  limitValue?: number | string;
  unit?: string;
  productCategory?: string;
  isExceed?: boolean;
}

interface AffectedCustomerRow {
  customerName?: string;
  productName?: string;
  quantity?: number | string;
  quantityUnit?: string;
  shipmentDate?: string;
  contactPhone?: string;
}

interface LossEstimate {
  inventoryValue: number;
  returnValue: number;
  adminCost: number;
  totalLoss: number;
}

interface RecallContext {
  batchNumber: string;
  productName: string;
  recallEventId: number | string;
  materialBatchCount: number;
  affectedShipmentCount: number;
  affectedCustomerCount: number;
}

interface PreviewData {
  status?: string;
  message?: string;
  canDo?: boolean;
  smsDraft?: string;
  customerCount?: number;
}

const authStore = useAuthStore();

const userInput = ref('今天 HACCP 监控全通过吗?');
const loading = ref(false);
const errorMessage = ref('');
const formattedText = ref('');
const lastQueryTime = ref('');

const recallContext = reactive<RecallContext>({
  batchNumber: '',
  productName: '',
  recallEventId: '',
  materialBatchCount: 0,
  affectedShipmentCount: 0,
  affectedCustomerCount: 0,
});

const haccpRecords = ref<HaccpRow[]>([]);
const additiveChecks = ref<AdditiveRow[]>([]);
const affectedCustomers = ref<AffectedCustomerRow[]>([]);
const lossEstimate = reactive<LossEstimate>({
  inventoryValue: 0,
  returnValue: 0,
  adminCost: 0,
  totalLoss: 0,
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

const haccpDeviationCount = computed(() =>
  haccpRecords.value.filter(r => r.isDeviation).length);

const additiveExceedCount = computed(() =>
  additiveChecks.value.filter(r => r.isExceed).length);

function toNum(v: unknown): number {
  if (v === null || v === undefined) return 0;
  const n = typeof v === 'number' ? v : parseFloat(String(v));
  return isNaN(n) ? 0 : n;
}

function formatAmount(v: number | string | null | undefined): string {
  if (v === null || v === undefined || v === '') return '0';
  const num = typeof v === 'number' ? v : parseFloat(String(v));
  if (isNaN(num)) return '0';
  return num.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatDateTime(v: string | undefined | null): string {
  if (!v) return '-';
  try {
    return new Date(v).toLocaleString('zh-CN', {
      hour12: false,
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit',
    });
  } catch {
    return v;
  }
}

function haccpRowClass({ row }: { row: HaccpRow }): string {
  return row.isDeviation ? 'row-deviation' : '';
}

function additiveRowClass({ row }: { row: AdditiveRow }): string {
  return row.isExceed ? 'row-exceed' : '';
}

function previewAlertType(preview: PreviewData): 'warning' | 'info' | 'success' | 'error' {
  if (!preview.status) return 'info';
  if (preview.status === 'ALREADY_FROZEN' || preview.status === 'ALREADY_NOTIFIED'
      || preview.status === 'DUPLICATE') return 'warning';
  if (preview.status === 'PREVIEW') return 'success';
  if (preview.canDo === false) return 'warning';
  return 'info';
}

function extractErrorMessage(err: unknown): string {
  if (err && typeof err === 'object') {
    const e = err as { response?: { data?: { message?: string } }; message?: string };
    return e.response?.data?.message || e.message || '未知错误';
  }
  return String(err);
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

async function triggerHaccpQuery() {
  userInput.value = '今天 HACCP 监控全通过吗?';
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
    extractRecallDataFromResult(response.resultData || {});
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

function resetRecallData() {
  recallContext.batchNumber = '';
  recallContext.productName = '';
  recallContext.recallEventId = '';
  recallContext.materialBatchCount = 0;
  recallContext.affectedShipmentCount = 0;
  recallContext.affectedCustomerCount = 0;
  haccpRecords.value = [];
  additiveChecks.value = [];
  affectedCustomers.value = [];
  lossEstimate.inventoryValue = 0;
  lossEstimate.returnValue = 0;
  lossEstimate.adminCost = 0;
  lossEstimate.totalLoss = 0;
}

function extractRecallDataFromResult(resultData: Record<string, unknown>) {
  // food-safety-recall Skill 输出 multi-step result
  const steps = (resultData['steps'] as Record<string, unknown>) || resultData;

  // step 1: batch_trace_by_customer_date → batchNumber
  const traceByCustStep = steps['batch_trace_by_customer_date'] as Record<string, unknown> | undefined;
  if (traceByCustStep) {
    const inner = (traceByCustStep['data'] as Record<string, unknown>) || traceByCustStep;
    const shipments = inner['shipments'] as Array<Record<string, unknown>> | undefined;
    if (Array.isArray(shipments) && shipments.length > 0) {
      const firstShipment = shipments[0];
      recallContext.batchNumber = (firstShipment['batchNumber'] as string) || '';
      recallContext.productName = (firstShipment['productName'] as string) || '';
    }
  }

  // step 2: batch_full_trace → affectedCustomers + counts
  const fullTraceStep = steps['batch_full_trace'] as Record<string, unknown> | undefined;
  if (fullTraceStep) {
    const inner = (fullTraceStep['data'] as Record<string, unknown>) || fullTraceStep;
    if (!recallContext.batchNumber) {
      recallContext.batchNumber = (inner['batchNumber'] as string) || '';
    }
    recallContext.affectedShipmentCount = toNum(inner['affectedShipmentCount']);
    recallContext.affectedCustomerCount = toNum(inner['affectedCustomerCount']);
    const shipments = inner['affectedShipments'] as Array<Record<string, unknown>> | undefined;
    if (Array.isArray(shipments)) {
      affectedCustomers.value = shipments.map(s => ({
        customerName: (s['customerName'] as string) || (s['customerId'] as string) || '',
        productName: (s['productName'] as string) || '',
        quantity: s['quantity'] as number | string,
        quantityUnit: (s['quantityUnit'] as string) || '',
        shipmentDate: (s['shipmentDate'] as string) || '',
        contactPhone: (s['contactPhone'] as string) || '',
      }));
    }
  }

  // step 3: haccp_checkpoint_review
  const haccpStep = steps['haccp_checkpoint_review'] as Record<string, unknown> | undefined;
  if (haccpStep) {
    const inner = (haccpStep['data'] as Record<string, unknown>) || haccpStep;
    const records = inner['records'] as HaccpRow[] | undefined;
    if (Array.isArray(records)) {
      haccpRecords.value = records;
    }
  }

  // step 4: additive_compliance_check
  const additiveStep = steps['additive_compliance_check'] as Record<string, unknown> | undefined;
  if (additiveStep) {
    const inner = (additiveStep['data'] as Record<string, unknown>) || additiveStep;
    const checks = inner['checks'] as AdditiveRow[] | undefined;
    if (Array.isArray(checks)) {
      additiveChecks.value = checks;
    }
  }

  // step 6: recall_loss_estimate
  const lossStep = steps['recall_loss_estimate'] as Record<string, unknown> | undefined;
  if (lossStep) {
    const inner = (lossStep['data'] as Record<string, unknown>) || lossStep;
    lossEstimate.inventoryValue = toNum(inner['inventoryValue']);
    lossEstimate.returnValue = toNum(inner['returnValue']);
    lossEstimate.adminCost = toNum(inner['adminCost']);
    lossEstimate.totalLoss = toNum(inner['totalLoss']);
  }

  // RecallEvent ID 可能在任意 step 的 recallEventId 字段, 或 result-level
  const eventId = resultData['recallEventId']
    ?? (steps['recall_event_create'] as Record<string, unknown>)?.['recallEventId']
    ?? '';
  if (eventId) recallContext.recallEventId = eventId as number | string;
}

// ===== Dialog 1: 启动召回 =====
const recallStartDialog = reactive({
  visible: false,
  analyzing: false,
  form: {
    customerName: '',
    complaintDate: '',
    complaintDescription: '',
  },
});

const canStartRecall = computed(() =>
  recallStartDialog.form.customerName.trim() !== ''
  && recallStartDialog.form.complaintDate !== ''
  && recallStartDialog.form.complaintDescription.trim() !== '');

function openRecallStartDialog() {
  recallStartDialog.visible = true;
}

function resetRecallStartDialog() {
  recallStartDialog.form.customerName = '';
  recallStartDialog.form.complaintDate = '';
  recallStartDialog.form.complaintDescription = '';
}

async function startRecallAnalysis() {
  if (!canStartRecall.value) return;
  recallStartDialog.analyzing = true;
  resetRecallData();

  // 构造自然语言输入触发 food-safety-recall Skill (per spec §P3.5 demo).
  const naturalInput = `${recallStartDialog.form.customerName} ${recallStartDialog.form.complaintDate} ${recallStartDialog.form.complaintDescription} 启动召回`;
  userInput.value = naturalInput;

  try {
    const response = await callIntentExecute(
      naturalInput,
      'FOOD_SAFETY_RECALL',
      {
        customerName: recallStartDialog.form.customerName,
        complaintDate: recallStartDialog.form.complaintDate,
        complaintDescription: recallStartDialog.form.complaintDescription,
      },
    );
    formattedText.value = response.formattedText || response.message || '(召回分析无输出)';
    lastQueryTime.value = new Date().toLocaleTimeString('zh-CN');
    extractRecallDataFromResult(response.resultData || {});
    recallStartDialog.visible = false;
    ElMessage.success('召回分析完成, 请查看下方结果并执行后续行动');
  } catch (err: unknown) {
    const msg = extractErrorMessage(err);
    ElMessage({
      message: `召回分析失败: ${msg}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    recallStartDialog.analyzing = false;
  }
}

// ===== Dialog 2: 冻结库存 =====
const freezeDialog = reactive({
  visible: false,
  previewing: false,
  submitting: false,
  canSubmit: false,
  form: {
    reason: '',
  },
  preview: null as PreviewData | null,
});

function openInventoryFreezeDialog() {
  if (!recallContext.batchNumber) {
    ElMessage.warning('请先启动召回分析获取批次号');
    return;
  }
  freezeDialog.visible = true;
  freezeDialog.form.reason = '食品安全召回, 防止次生污染';
}

function resetFreezeDialog() {
  freezeDialog.form.reason = '';
  freezeDialog.preview = null;
  freezeDialog.canSubmit = false;
}

async function previewFreeze() {
  if (!recallContext.batchNumber || !freezeDialog.form.reason.trim()) {
    ElMessage.warning('批次号 + 冻结原因必填');
    return;
  }
  freezeDialog.previewing = true;
  try {
    const response = await callIntentExecute(
      '预览冻结库存',
      'INVENTORY_FREEZE',
      {
        batchNumber: recallContext.batchNumber,
        reason: freezeDialog.form.reason,
      },
      true,
    );
    const previewData = (response.resultData || {}) as PreviewData;
    freezeDialog.preview = previewData;
    freezeDialog.canSubmit = previewData.canDo !== false
        && previewData.status !== 'ALREADY_FROZEN';
  } catch (err) {
    ElMessage({
      message: `预览失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    freezeDialog.previewing = false;
  }
}

async function executeFreeze() {
  if (!freezeDialog.canSubmit) {
    ElMessage.warning('请先点击 [预览] 验证可提交');
    return;
  }
  freezeDialog.submitting = true;
  try {
    const response = await callIntentExecute(
      '冻结库存',
      'INVENTORY_FREEZE',
      {
        batchNumber: recallContext.batchNumber,
        reason: freezeDialog.form.reason,
      },
    );
    ElMessage.success(response.message || '已冻结批次, 此批次无法再用于生产/出货');
    freezeDialog.visible = false;
  } catch (err) {
    ElMessage({
      message: `冻结失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    freezeDialog.submitting = false;
  }
}

// ===== Dialog 3: 通知客户 =====
const notifyDialog = reactive({
  visible: false,
  previewing: false,
  submitting: false,
  canSubmit: false,
  form: {
    message: '',
  },
  preview: null as PreviewData | null,
});

function openCustomerNotifyDialog() {
  if (!recallContext.batchNumber) {
    ElMessage.warning('请先启动召回分析获取批次号');
    return;
  }
  notifyDialog.visible = true;
}

function resetNotifyDialog() {
  notifyDialog.form.message = '';
  notifyDialog.preview = null;
  notifyDialog.canSubmit = false;
}

async function previewNotify() {
  if (!recallContext.batchNumber) {
    ElMessage.warning('批次号必填');
    return;
  }
  notifyDialog.previewing = true;
  try {
    const params: Record<string, unknown> = {
      batchNumber: recallContext.batchNumber,
    };
    if (recallContext.recallEventId) {
      params.recallEventId = recallContext.recallEventId;
    }
    if (notifyDialog.form.message.trim()) {
      params.message = notifyDialog.form.message;
    }
    const response = await callIntentExecute(
      '预览通知客户',
      'CUSTOMER_NOTIFY_BATCH',
      params,
      true,
    );
    const previewData = (response.resultData || {}) as PreviewData;
    notifyDialog.preview = previewData;
    notifyDialog.canSubmit = previewData.canDo !== false
        && previewData.status !== 'ALREADY_NOTIFIED';
  } catch (err) {
    ElMessage({
      message: `预览失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    notifyDialog.previewing = false;
  }
}

async function executeNotify() {
  if (!notifyDialog.canSubmit) {
    ElMessage.warning('请先点击 [预览] 验证可提交');
    return;
  }
  notifyDialog.submitting = true;
  try {
    const params: Record<string, unknown> = {
      batchNumber: recallContext.batchNumber,
    };
    if (recallContext.recallEventId) {
      params.recallEventId = recallContext.recallEventId;
    }
    if (notifyDialog.form.message.trim()) {
      params.message = notifyDialog.form.message;
    }
    const response = await callIntentExecute(
      '通知客户',
      'CUSTOMER_NOTIFY_BATCH',
      params,
    );
    ElMessage.success(response.message || '已群发召回通知');
    notifyDialog.visible = false;
  } catch (err) {
    ElMessage({
      message: `通知失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    notifyDialog.submitting = false;
  }
}

// ===== Dialog 4: 生成监管文件 =====
const reportDialog = reactive({
  visible: false,
  submitting: false,
  markdownContent: '',
  form: {},
});

function openRegulatoryReportDialog() {
  if (!recallContext.recallEventId) {
    ElMessage.warning('请先启动召回分析创建召回事件');
    return;
  }
  reportDialog.visible = true;
  reportDialog.markdownContent = '';
}

function resetReportDialog() {
  reportDialog.markdownContent = '';
}

async function executeGenerateReport() {
  if (!recallContext.recallEventId) return;
  reportDialog.submitting = true;
  try {
    const response = await callIntentExecute(
      '生成监管上报文件',
      'REGULATORY_REPORT',
      { recallEventId: recallContext.recallEventId },
    );
    const data = (response.resultData || {}) as Record<string, unknown>;
    reportDialog.markdownContent = (data['markdown'] as string)
        || (data['content'] as string)
        || response.formattedText
        || '(无 markdown 内容)';
    ElMessage.success(response.message || '监管文件已生成');
  } catch (err) {
    ElMessage({
      message: `生成失败: ${extractErrorMessage(err)}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    reportDialog.submitting = false;
  }
}

function downloadReport() {
  if (!reportDialog.markdownContent) return;
  const blob = new Blob([reportDialog.markdownContent], { type: 'text/markdown;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `召回上报文件-${recallContext.recallEventId}-${Date.now()}.md`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

// ===== Dialog 5: 关闭召回事件 =====
const closeDialog = reactive({
  visible: false,
  submitting: false,
  form: {
    summary: '',
  },
});

function openCloseEventDialog() {
  if (!recallContext.recallEventId) {
    ElMessage.warning('请先启动召回分析创建召回事件');
    return;
  }
  closeDialog.visible = true;
}

function resetCloseDialog() {
  closeDialog.form.summary = '';
}

async function executeCloseEvent() {
  if (!recallContext.recallEventId || !closeDialog.form.summary.trim()) return;
  closeDialog.submitting = true;
  try {
    // Phase 1 stub: RecallEventService 控制器尚未 ship.
    // 通过 ai-intents/execute 触发 RECALL_EVENT_CLOSE intent (如有);
    // 兜底走 dataop 通用 update tool (如 intent 未注册).
    const response = await callIntentExecute(
      '关闭召回事件',
      'RECALL_EVENT_CLOSE',
      {
        recallEventId: recallContext.recallEventId,
        summary: closeDialog.form.summary,
        status: 'COMPLETED',
      },
    );
    ElMessage.success(response.message
        || `召回事件 #${recallContext.recallEventId} 已关闭`);
    closeDialog.visible = false;
  } catch (err) {
    // R5 dead-end: stub 失败提示前往详情页手动关闭
    const msg = extractErrorMessage(err);
    ElMessage({
      message: `关闭失败 (Phase 1 stub): ${msg}. 后续 Phase 接入 RecallEventService`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    closeDialog.submitting = false;
  }
}

// 注意: 不 auto-trigger on mount, 召回是 reactive 场景 (客户投诉触发) 不是 daily query.
// 用户手动点 "启动召回" 或输入对话框触发.
</script>

<style scoped>
.quality-manager-workdesk {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.workdesk-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: linear-gradient(135deg, #fef0f0, #fff4f4);
  padding: 12px 20px;
  border-radius: 8px;
  border-left: 4px solid #f56c6c;
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

.error-alert {
  margin: 0;
}

.formatted-output {
  font-size: 14px;
  line-height: 1.8;
  color: #303133;
  white-space: pre-wrap;
}

.trace-content {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.info-row {
  display: flex;
  justify-content: space-between;
  padding: 4px 0;
  font-size: 14px;
}

.info-row .label {
  color: #909399;
}

.info-row .value {
  color: #303133;
  font-weight: 500;
}

.text-danger {
  color: #f56c6c;
  font-weight: 600;
}

:deep(.row-deviation) {
  background-color: #fef0f0 !important;
}

:deep(.row-exceed) {
  background-color: #fef0f0 !important;
}

.loss-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
}

.loss-item {
  padding: 12px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  text-align: center;
}

.loss-item.loss-total {
  border-color: #f56c6c;
  background: linear-gradient(135deg, #fef0f0, #fff4f4);
}

.loss-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}

.loss-value {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.loss-item.loss-total .loss-value {
  color: #f56c6c;
  font-size: 22px;
}

.actions-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
}

.preview-alert {
  margin-top: 8px;
}

.sms-preview {
  margin-top: 8px;
  padding: 8px 12px;
  background: #fff;
  border: 1px dashed #dcdfe6;
  border-radius: 4px;
}

.sms-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.sms-content {
  font-size: 13px;
  color: #303133;
  line-height: 1.6;
}

.markdown-output {
  font-family: 'Courier New', monospace;
  font-size: 12px;
}
</style>
