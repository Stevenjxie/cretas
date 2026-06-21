<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { formatAmount } from '@/utils/tableFormatters';
import { handleCatchError } from '@/utils/errorToast';
import { WorkflowBar } from '@/components/workflow';
import { useWorkflowStats } from '@/composables/useWorkflowStats';
import { getBucketPrimaryStatus, getBucketLabel } from '@/types/workflow';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const route = useRoute();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('finance'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

// U-NAV-1 业务流程图导航 (Sprint 2 Track G + FU Chat 3 bucket-filter)
const { stats: workflowStats, loading: workflowLoading } = useWorkflowStats(factoryId, 'finance');
function handleWorkflowNodeClick(nodeId: string) {
  const primary = getBucketPrimaryStatus('finance', nodeId);
  if (!primary) return;
  statusFilter.value = primary;
  pagination.value.page = 0;
  loadData();
  ElMessage.success(`已切到 "${getBucketLabel('finance', nodeId)}" (显示状态: ${primary}). finance 是 Invoice+Payment 复合, done 节点实为 payment.VERIFIED 但本表仅 invoice — 详情请去收款管理.`);
}
function handleWorkflowAITrigger() {
  ElMessage.info('AI 入口待 Day 7 接入');
}

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
// 2026-06-21 fix: backend /finance/invoices is 0-based (defaultValue=0); template uses
// :current-page="page+1" and @current-change sets page=p-1, so page is 0-based internally.
// Init must be 0 — else first load requests page index 1 (2nd page) and the list is empty
// whenever total<=size (pagination control hidden → unreachable).
const pagination = ref({ page: 0, size: 20, total: 0 });
const statusFilter = ref('');

const statusMap: Record<string, { text: string; type: string }> = {
  REQUESTED: { text: '待审核', type: 'warning' },
  APPROVED: { text: '已审核', type: '' },
  ISSUED: { text: '已开具', type: 'success' },
  REJECTED: { text: '已驳回', type: 'danger' },
  CANCELLED: { text: '已取消', type: 'info' },
};

onMounted(() => {
  // Bug #40: finance 点"开票审核"菜单时 URL 带 ?status=REQUESTED 自动过滤
  const qs = route.query.status;
  if (typeof qs === 'string' && statusMap[qs]) statusFilter.value = qs;
  loadData();
  loadSalesOrderOptions();
});

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params: TableRow = { page: pagination.value.page, size: pagination.value.size };
    if (statusFilter.value) params.status = statusFilter.value;
    const res = await get(`/${factoryId.value}/finance/invoices`, { params });
    if (res.success) {
      let rows = res.data.content || [];
      // Apr 20 Bug BR-11 fix: 前端补 keyword 搜索 (客户 / 发票号), 后端无 keyword 参数
      const kw = searchKeyword.value.trim();
      if (kw) {
        const lower = kw.toLowerCase();
        rows = rows.filter((r: TableRow) =>
          String(r.customerName || '').toLowerCase().includes(lower) ||
          String(r.invoiceNumber || '').toLowerCase().includes(lower) ||
          String(r.orderNumber || '').toLowerCase().includes(lower)
        );
      }
      tableData.value = rows;
      pagination.value.total = res.data.totalElements || 0;
    }
  } catch { /* axios interceptor already displayed error toast */ }
  finally { loading.value = false; }
}

// Apr 20 Bug BR-11 fix: keyword state
const searchKeyword = ref('');
function handleSearch() { pagination.value.page = 0; loadData(); }
function handleReset() { searchKeyword.value = ''; statusFilter.value = ''; handleSearch(); }

// Apr 21 2026: load invoiceable sales orders so FE can offer a dropdown
// instead of asking users to hand-copy 订单号 like SO-20260420-0001.
// 防呆 R1 (fool-proof-design 2026-05-17): include invoicedAmount so dialog
// can compute + display "可开 ¥{unbilled}" and gate input :max.
interface SalesOrderOption { id: string; orderNumber: string; customerName: string; totalAmount?: number; invoicedAmount?: number }
const salesOrderOptions = ref<SalesOrderOption[]>([]);
async function loadSalesOrderOptions() {
  if (!factoryId.value) return;
  try {
    // Note: endpoint is /sales/orders (SalesController), not /sales-orders.
    // List all then filter to invoicable statuses client-side (CONFIRMED
    // through COMPLETED; exclude DRAFT / CANCELLED).
    const res = await get<{ content: (SalesOrderOption & { status?: string })[] }>(
      `/${factoryId.value}/sales/orders`,
      { params: { page: 1, size: 200 } }
    );
    if (res.success && res.data) {
      const invoicableStatuses = new Set([
        'CONFIRMED', 'PENDING_FINANCE_REVIEW', 'FINANCE_APPROVED',
        'PROCESSING', 'PARTIAL_DELIVERED', 'COMPLETED',
      ]);
      salesOrderOptions.value = (res.data.content || []).filter(
        o => !o.status || invoicableStatuses.has(o.status)
      );
    }
  } catch { /* silent */ }
}

async function handleAction(id: string, action: 'approve' | 'reject' | 'issue') {
  const labels = { approve: '审核通过', reject: '驳回', issue: '开具发票' };
  try {
    if (action === 'reject') {
      const { value: notes } = await ElMessageBox.prompt('请输入驳回原因', '驳回', { confirmButtonText: '确定', cancelButtonText: '取消' });
      await post(`/${factoryId.value}/finance/invoices/${id}/reject`, { notes });
    } else if (action === 'issue') {
      // Bug #4 (R7 fix 2026-04-16): 后端硬规则要求 PDF 附件, 开对话框让用户选文件
      // 防呆 R2 (fool-proof-design 2026-05-17): 弹窗时携 row context 显示 invoice号 + 客户 + 申请金额
      issueTargetId.value = id;
      issueTargetRow.value = tableData.value.find(r => r.id === id) || null;
      issuePdfFile.value = null;
      issueDialogVisible.value = true;
      return;
    } else {
      await ElMessageBox.confirm(`确认${labels[action]}？`, '确认');
      await post(`/${factoryId.value}/finance/invoices/${id}/${action}`);
    }
    ElMessage.success(`${labels[action]}成功`);
    loadData();
  } catch (e) {
    if (e === 'cancel') return;
    // R26 follow-up (reviewer #16 concern #1): pre-fix this fired ElMessage.error('操作失败')
    // on top of axios interceptor's rich actionHint toast for any backend 4xx/5xx
    // (e.g. R18+R21+R23 invariant 409 with hint, or DataIntegrity unique 409 post-R25).
    // handleCatchError gates on !err.status — only fires fallback for true network errors.
    handleCatchError(e, '操作失败,请检查网络');
  }
}

// Bug #4 fix: 开具发票弹窗 + PDF 上传
const issueDialogVisible = ref(false);
const issueTargetId = ref('');
const issueTargetRow = ref<TableRow | null>(null);  // 防呆 R2: dialog context
const issuePdfFile = ref<File | null>(null);
const issuing = ref(false);

// F-INV-1 gap-fill: 开具成功后展示 OCR 结果, 与申请金额对比
interface IssueResponse {
  success: boolean;
  data?: {
    amount?: number;
    ocrInvoiceNumber?: string | null;
    ocrAmount?: number | null;
    ocrTaxAmount?: number | null;
    ocrConfidence?: number | null;
    ocrErrorMessage?: string | null;
  };
  message?: string;
}

async function submitIssue() {
  if (!issuePdfFile.value) { ElMessage.warning('请选择发票 PDF 文件'); return; }
  issuing.value = true;
  try {
    const formData = new FormData();
    formData.append('file', issuePdfFile.value);
    const res = await post<IssueResponse['data']>(
      `/${factoryId.value}/finance/invoices/${issueTargetId.value}/issue`,
      formData,
      { headers: { 'Content-Type': 'multipart/form-data' } }
    );
    ElMessage.success('开具发票成功');
    issueDialogVisible.value = false;
    surfaceOcrResult(res.data);
    loadData();
  } catch (e) {
    handleCatchError(e, '开具失败');
  } finally {
    issuing.value = false;
  }
}

function surfaceOcrResult(data?: IssueResponse['data']) {
  if (!data) return;
  // 防呆 4-位一体 (fool-proof-design 2026-05-17):
  //   a. backend message → b. UI 同文 → c. sticky (duration:0+showClose) → d. next action
  // OCR 失败 / 金额不一致 都是 financier 不能错过的关键信息, 必 sticky.
  if (data.ocrErrorMessage) {
    ElMessage({
      message: `发票已开具,但 OCR 识别失败: ${data.ocrErrorMessage} — 请打开 发票PDF 人工核对金额`,
      type: 'warning',
      duration: 0,
      showClose: true,
    });
    return;
  }
  if (data.ocrInvoiceNumber != null || data.ocrAmount != null) {
    const conf = data.ocrConfidence != null ? Math.round(data.ocrConfidence * 100) : null;
    const mismatch = data.ocrAmount != null && data.amount != null
        && Math.abs(Number(data.ocrAmount) - Number(data.amount)) > 0.01;
    if (mismatch) {
      ElMessage({
        message: `OCR 金额 ¥${data.ocrAmount} 与申请金额 ¥${data.amount} 不一致 ` +
          `(发票号 ${data.ocrInvoiceNumber || '?'}, 置信度 ${conf}%) — ` +
          `请人工核对 PDF 后决定是否驳回重开`,
        type: 'warning',
        duration: 0,         // sticky — 不能错过
        showClose: true,
      });
    } else {
      ElMessage({
        message: `OCR 识别: 发票号 ${data.ocrInvoiceNumber || '?'}, ` +
          `金额 ¥${data.ocrAmount} (置信度 ${conf}%) — 与申请金额一致`,
        type: 'success',
        duration: 5000,
      });
    }
  }
}

function isOcrMatch(row: TableRow): boolean {
  // F-INV-1 gap-fill: 金额相差 ≤ ¥0.01 视为匹配 (浮点容差)
  if (row.ocrAmount == null || row.amount == null) return false;
  return Math.abs(Number(row.ocrAmount) - Number(row.amount)) <= 0.01;
}

function handlePdfChange(file: { raw: File } | File) {
  const raw = (file as { raw: File }).raw || (file as File);
  if (!raw.name?.toLowerCase().endsWith('.pdf')) {
    ElMessage.warning('仅支持 PDF 文件');
    issuePdfFile.value = null;
    return false;
  }
  issuePdfFile.value = raw;
  return false;
}

// 开票申请弹窗
const requestDialogVisible = ref(false);
const requestForm = ref({ salesOrderId: '', amount: 0, taxAmount: 0, invoiceType: 'NORMAL', remark: '' });
const submitting = ref(false);

// 防呆 R1+R2 (fool-proof-design 2026-05-17): 选定 SO 后计算未开金额, gate input :max,
// dialog 标题 + hint 也基于 selectedSO 显示 customer/order context.
const selectedSO = computed<SalesOrderOption | null>(() =>
  salesOrderOptions.value.find(o => o.id === requestForm.value.salesOrderId) || null
);
const unbilledAmount = computed<number>(() => {
  if (!selectedSO.value) return 0;
  const total = Number(selectedSO.value.totalAmount || 0);
  const invoiced = Number(selectedSO.value.invoicedAmount || 0);
  return Math.max(0, total - invoiced);
});
const requestOverLimit = computed<boolean>(() => {
  // 价税合计 vs 未开金额 (后端 totalAmount = amount + taxAmount)
  const total = Number(requestForm.value.amount || 0) + Number(requestForm.value.taxAmount || 0);
  return total > unbilledAmount.value + 0.001;
});

async function handleRequestSubmit() {
  if (!requestForm.value.salesOrderId || !requestForm.value.amount) {
    ElMessage.warning('请填写订单ID和金额'); return;
  }
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/finance/invoices/request`, requestForm.value);
    if (res.success) {
      ElMessage.success('开票申请已提交');
      requestDialogVisible.value = false;
      loadData();
    } else { ElMessage.error(res.message || '提交失败'); }
  } catch { /* axios interceptor already displayed error toast */ }
  finally { submitting.value = false; }
}
</script>

<template>
  <div class="page-wrapper" v-loading="loading">
    <!-- U-NAV-1 业务流程图导航 (Sprint 2 Track G) -->
    <WorkflowBar
      :nodes="workflowStats?.nodes ?? []"
      :loading="workflowLoading"
      title="财务工作流"
      :ai-trigger-enabled="true"
      @node-click="handleWorkflowNodeClick"
      @ai-trigger="handleWorkflowAITrigger"
    />
    <el-card shadow="never">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span style="font-size:16px;font-weight:600">开票管理</span>
          <div style="display:flex;gap:8px">
            <!-- Apr 20 Bug BR-11 fix: 加 keyword 搜索 (客户 / 发票号 / 订单号) -->
            <el-input v-model="searchKeyword" placeholder="搜索 客户/发票号/订单号" clearable style="width:220px" @keyup.enter="handleSearch" />
            <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width:140px" @change="loadData">
              <el-option v-for="(v,k) in statusMap" :key="k" :label="v.text" :value="k" />
            </el-select>
            <el-button type="primary" @click="handleSearch">搜索</el-button>
            <el-button @click="handleReset">重置</el-button>
            <el-button v-if="canWrite && canViewPrice" type="primary" @click="requestDialogVisible = true">申请开票</el-button>
          </div>
        </div>
      </template>

      <el-table :data="tableData" border stripe>
        <el-table-column prop="invoiceNumber" label="发票编号" width="180" />
        <el-table-column prop="customerName" label="客户" min-width="130" />
        <el-table-column v-if="canViewPrice" prop="totalAmount" label="价税合计" width="130" align="right">
          <template #default="{ row }">{{ formatAmount(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column prop="invoiceType" label="类型" width="90" align="center">
          <template #default="{ row }">{{ row.invoiceType === 'SPECIAL' ? '专票' : '普票' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusMap[row.status]?.type || 'info'" size="small">{{ statusMap[row.status]?.text || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="requestedAt" label="申请时间" width="170" />
        <el-table-column prop="invoicePdfUrl" label="发票PDF" width="90" align="center">
          <template #default="{ row }">
            <a v-if="row.invoicePdfUrl" :href="row.invoicePdfUrl" target="_blank">查看</a>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <!-- F-INV-1 gap-fill: OCR 识别结果 (供财务核对) -->
        <el-table-column label="OCR" width="110" align="center">
          <template #default="{ row }">
            <template v-if="row.status !== 'ISSUED'">
              <span style="color:#bbb">-</span>
            </template>
            <el-tooltip
              v-else-if="row.ocrErrorMessage"
              :content="`OCR 失败: ${row.ocrErrorMessage}`"
              placement="top"
            >
              <el-tag type="danger" size="small">失败</el-tag>
            </el-tooltip>
            <el-tooltip
              v-else-if="row.ocrInvoiceNumber || row.ocrAmount"
              placement="top"
            >
              <template #content>
                <div>OCR 发票号: {{ row.ocrInvoiceNumber || '-' }}</div>
                <div v-if="canViewPrice">OCR 金额: ¥{{ row.ocrAmount ?? '-' }}</div>
                <div v-if="canViewPrice">OCR 税额: ¥{{ row.ocrTaxAmount ?? '-' }}</div>
                <div>置信度: {{ row.ocrConfidence != null ? Math.round(Number(row.ocrConfidence) * 100) + '%' : '-' }}</div>
              </template>
              <el-tag
                :type="isOcrMatch(row) ? 'success' : 'warning'"
                size="small"
              >
                {{ isOcrMatch(row) ? '匹配' : '待核对' }}
              </el-tag>
            </el-tooltip>
            <span v-else style="color:#bbb">未识别</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="center" v-if="canWrite">
          <template #default="{ row }">
            <el-button v-if="row.status === 'REQUESTED'" type="success" link size="small" @click="handleAction(row.id, 'approve')">审核</el-button>
            <el-button v-if="row.status === 'REQUESTED'" type="danger" link size="small" @click="handleAction(row.id, 'reject')">驳回</el-button>
            <el-button v-if="row.status === 'APPROVED'" type="primary" link size="small" @click="handleAction(row.id, 'issue')">开具</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="pagination.total > pagination.size"
        style="margin-top:16px;justify-content:flex-end"
        :current-page="pagination.page + 1"
        :page-size="pagination.size"
        :total="pagination.total"
        layout="total, prev, pager, next"
        @current-change="(p: number) => { pagination.page = p - 1; loadData(); }"
      />
    </el-card>

    <!-- 开票申请弹窗 (防呆 R1+R2 retrofit 2026-05-17) -->
    <el-dialog
      v-model="requestDialogVisible"
      :title="selectedSO
        ? `申请开票 — ${selectedSO.customerName || '客户未知'} (${selectedSO.orderNumber})`
        : '申请开票'"
      width="540px"
      destroy-on-close
    >
      <el-form label-width="90px">
        <el-form-item label="销售订单" required>
          <el-select
            v-model="requestForm.salesOrderId"
            placeholder="选择已确认的销售订单"
            filterable
            style="width:100%"
          >
            <el-option
              v-for="o in salesOrderOptions"
              :key="o.id"
              :value="o.id"
              :label="`${o.orderNumber} · ${o.customerName || '-'} · ¥${(o.totalAmount || 0).toLocaleString()}`"
            />
            <template #empty>
              <div style="padding:8px 12px;color:#909399">暂无已确认的销售订单</div>
            </template>
          </el-select>
        </el-form-item>
        <!-- 防呆 R1: 显式列出 订单总额 / 已开 / 可开 + input :max -->
        <el-alert
          v-if="canViewPrice && selectedSO"
          type="info"
          :closable="false"
          show-icon
          style="margin-bottom:12px"
        >
          <template #title>
            订单总额 <b>¥{{ Number(selectedSO.totalAmount || 0).toFixed(2) }}</b>
            · 已开 <b>¥{{ Number(selectedSO.invoicedAmount || 0).toFixed(2) }}</b>
            · <span :style="{ color: unbilledAmount > 0 ? '#67c23a' : '#f56c6c' }">可开 <b>¥{{ unbilledAmount.toFixed(2) }}</b></span>
          </template>
          <template v-if="unbilledAmount === 0" #default>
            该订单已全额开票, 无需再次申请
          </template>
        </el-alert>
        <el-form-item v-if="canViewPrice" label="不含税金额" required>
          <el-input-number
            v-model="requestForm.amount"
            :min="0"
            :max="unbilledAmount > 0 ? unbilledAmount : undefined"
            :precision="2"
            style="width:100%"
          />
        </el-form-item>
        <el-form-item v-if="canViewPrice" label="税额">
          <el-input-number
            v-model="requestForm.taxAmount"
            :min="0"
            :max="unbilledAmount > 0 ? unbilledAmount : undefined"
            :precision="2"
            style="width:100%"
          />
        </el-form-item>
        <!-- 防呆 R1: 实时显示 价税合计 vs 未开 上限 + 超限警示 -->
        <el-form-item v-if="canViewPrice && selectedSO" label="价税合计">
          <span :style="{ color: requestOverLimit ? '#f56c6c' : '#303133', fontWeight: 'bold' }">
            ¥{{ (Number(requestForm.amount || 0) + Number(requestForm.taxAmount || 0)).toFixed(2) }}
          </span>
          <span v-if="requestOverLimit" style="color:#f56c6c;margin-left:8px;font-size:12px">
            超过未开金额 ¥{{ unbilledAmount.toFixed(2) }}, 请调低
          </span>
        </el-form-item>
        <el-form-item label="发票类型">
          <el-select v-model="requestForm.invoiceType" style="width:100%">
            <el-option label="普通发票" value="NORMAL" />
            <el-option label="增值税专用发票" value="SPECIAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="requestForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="requestDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="!requestForm.salesOrderId || !canViewPrice || requestOverLimit || unbilledAmount === 0"
          @click="handleRequestSubmit"
        >提交申请</el-button>
      </template>
    </el-dialog>

    <!-- Bug #4 R7 fix: 开具发票 + PDF 上传 (防呆 R2 retrofit 2026-05-17) -->
    <el-dialog
      v-model="issueDialogVisible"
      :title="issueTargetRow
        ? `开具发票 — ${issueTargetRow.invoiceNumber} · ${issueTargetRow.customerName || '客户未知'}`
        : '开具发票'"
      width="540px"
      destroy-on-close
    >
      <!-- 防呆 R2: 显式列出 发票号 / 客户 / 申请金额 — financier 上传前先确认 -->
      <el-descriptions
        v-if="issueTargetRow"
        :column="1"
        size="small"
        border
        style="margin-bottom:12px"
      >
        <el-descriptions-item label="发票号">{{ issueTargetRow.invoiceNumber }}</el-descriptions-item>
        <el-descriptions-item label="客户">{{ issueTargetRow.customerName || '-' }}</el-descriptions-item>
        <el-descriptions-item v-if="canViewPrice" label="申请价税合计">
          ¥{{ Number(issueTargetRow.totalAmount || 0).toFixed(2) }}
          <span style="color:#909399;font-size:12px;margin-left:6px">
            (不含税 ¥{{ Number(issueTargetRow.amount || 0).toFixed(2) }} +
            税 ¥{{ Number(issueTargetRow.taxAmount || 0).toFixed(2) }})
          </span>
        </el-descriptions-item>
      </el-descriptions>
      <el-form label-width="90px">
        <el-form-item label="发票 PDF" required>
          <el-upload
            :auto-upload="false"
            :limit="1"
            accept="application/pdf,.pdf"
            :on-change="handlePdfChange"
            :on-remove="(): void => { issuePdfFile = null }"
          >
            <el-button type="primary">选择 PDF 文件</el-button>
            <template #tip>
              <div style="color:#999;font-size:12px">仅 PDF, 上传后销售可从 SO 详情下载. 系统将自动 OCR 识别金额, 与申请金额对比.</div>
            </template>
          </el-upload>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="issueDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="issuing" :disabled="!issuePdfFile" @click="submitIssue">确认开具</el-button>
      </template>
    </el-dialog>
  </div>
</template>
