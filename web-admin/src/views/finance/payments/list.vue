<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { QuestionFilled } from '@element-plus/icons-vue';
import { formatAmount } from '@/utils/tableFormatters';
import type { TableRow } from '@/types/api';
// Sprint 6 W3-A — inline 3-chip link counter (文件 / 图片 / 合同).
import LinkChipCell from '@/components/list/LinkChipCell.vue';
import { useLinkChipCounts } from '@/composables/useLinkChipCounts';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('finance'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

// Sprint 6 W3-A — inline 3-chip 链接计数 (文件 / 图片 / 合同).
const { fetchLinkChipCounts, countsFor: linkCountsFor } =
  useLinkChipCounts(factoryId, 'PAYMENT_VOUCHER');

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
// 2026-06-21 fix: backend /finance/payments is 0-based (defaultValue=0); template
// uses :current-page="page+1" and @current-change sets page=p-1, so page is 0-based
// internally. Init must be 0 — else first load requests page index 1 (2nd page) and the
// list is empty whenever total<=size (pagination control hidden → unreachable),
// making 收款确认 (verify) impossible → SO stuck UNPAID.
const pagination = ref({ page: 0, size: 20, total: 0 });
const statusFilter = ref('');

const statusMap: Record<string, { text: string; type: string }> = {
  PENDING: { text: '待确认', type: 'warning' },
  VERIFIED: { text: '已确认', type: 'success' },
  REJECTED: { text: '已驳回', type: 'danger' },
};

const methodMap: Record<string, string> = {
  BANK_TRANSFER: '银行转账', CASH: '现金', WECHAT: '微信', ALIPAY: '支付宝',
  CHECK: '支票', CREDIT: '信用', POS: 'POS', OTHER: '其他',
};

onMounted(() => {
  loadData();
  loadSalesOrderOptions();
});

// Apr 21 2026: load confirmed sales orders for dropdown in 录入收款 dialog
// E-FP-3 (fool-proof Rule 1): SalesOrder 实体已序列化 paidAmount (entity line 223),
// 故 dialog 可计算 + 显示 "可收余额" 并 gate input :max — 纯前端, 无需后端改动.
interface SalesOrderOption { id: string; orderNumber: string; customerName: string; totalAmount?: number; paidAmount?: number }
const salesOrderOptions = ref<SalesOrderOption[]>([]);
async function loadSalesOrderOptions() {
  if (!factoryId.value) return;
  try {
    // Note: endpoint is /sales/orders (SalesController), not /sales-orders.
    // List all then filter to payable statuses client-side (CONFIRMED through
    // COMPLETED; exclude DRAFT / CANCELLED).
    const res = await get<{ content: (SalesOrderOption & { status?: string })[] }>(
      `/${factoryId.value}/sales/orders`,
      { params: { page: 1, size: 200 } }
    );
    if (res.success && res.data) {
      const payableStatuses = new Set([
        'CONFIRMED', 'PENDING_FINANCE_REVIEW', 'FINANCE_APPROVED',
        'PROCESSING', 'PARTIAL_DELIVERED', 'COMPLETED',
      ]);
      salesOrderOptions.value = (res.data.content || []).filter(
        o => !o.status || payableStatuses.has(o.status)
      );
    }
  } catch { /* silent */ }
}

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params: TableRow = { page: pagination.value.page, size: pagination.value.size };
    if (statusFilter.value) params.status = statusFilter.value;
    const res = await get(`/${factoryId.value}/finance/payments`, { params });
    if (res.success) {
      let rows = res.data.content || [];
      // Apr 20 Bug BR-12 fix: 加前端 keyword 搜索 (客户 / 收款单号 / 发票号)
      const kw = searchKeyword.value.trim();
      if (kw) {
        const lower = kw.toLowerCase();
        rows = rows.filter((r: TableRow) =>
          String(r.customerName || '').toLowerCase().includes(lower) ||
          String(r.paymentNumber || '').toLowerCase().includes(lower) ||
          String(r.invoiceNumber || '').toLowerCase().includes(lower)
        );
      }
      tableData.value = rows;
      pagination.value.total = res.data.totalElements || 0;

      // Sprint 6 W3-A — fire-and-forget batch 3-chip counts (文件/图片/合同).
      // EntityType=PAYMENT_VOUCHER → 收款凭证附件 (银行回单 / 转账截图 / 合同).
      void fetchLinkChipCounts(rows.map((r: TableRow) => String(r.id)).filter(Boolean));
    }
  } catch { /* axios interceptor already displayed error toast */ }
  finally { loading.value = false; }
}

// Apr 20 Bug BR-12 fix: keyword state
const searchKeyword = ref('');
function handleSearch() { pagination.value.page = 0; loadData(); }
function handleReset() { searchKeyword.value = ''; statusFilter.value = ''; handleSearch(); }

async function handleVerify(id: string) {
  try {
    await ElMessageBox.confirm('确认此笔收款？', '确认');
    const res = await post(`/${factoryId.value}/finance/payments/${id}/verify`);
    if (res.success) { ElMessage.success('收款已确认'); loadData(); }
    else { ElMessage.error(res.message || '确认失败'); }
  } catch (e) { /* axios interceptor handles API errors; cancel from MessageBox is silent */ }
}

async function handleReject(id: string) {
  try {
    const { value: reason } = await ElMessageBox.prompt('请输入驳回原因', '驳回');
    const res = await post(`/${factoryId.value}/finance/payments/${id}/reject`, { reason });
    if (res.success) { ElMessage.success('已驳回'); loadData(); }
    else { ElMessage.error(res.message || '驳回失败'); }
  } catch (e) { /* axios interceptor handles API errors; cancel from MessageBox is silent */ }
}

// 录入收款弹窗
const recordDialogVisible = ref(false);
const recordForm = ref({
  salesOrderId: '', amount: 0, paymentMethod: 'BANK_TRANSFER',
  paymentDate: '', paymentReference: '', remark: ''
});
const submitting = ref(false);

// E-FP-3 (fool-proof Rule 1): 选定 SO 后计算未收金额, gate input :max + el-alert 显示
// "订单总额 / 已收 / 可收" 上下文 (仿 invoices/list.vue 开票防呆).
const selectedSO = computed<SalesOrderOption | null>(() =>
  salesOrderOptions.value.find(o => o.id === recordForm.value.salesOrderId) || null
);
const remainingAmount = computed<number>(() => {
  if (!selectedSO.value) return 0;
  const total = Number(selectedSO.value.totalAmount || 0);
  const paid = Number(selectedSO.value.paidAmount || 0);
  return Math.max(0, total - paid);
});
const recordOverLimit = computed<boolean>(() =>
  Number(recordForm.value.amount || 0) > remainingAmount.value + 0.001
);
// 选 SO → 预填收款金额为可收余额 (用户可改). 仅当当前金额为 0 时预填, 不覆盖用户已输.
function onSalesOrderSelect() {
  if (!recordForm.value.amount || recordForm.value.amount === 0) {
    recordForm.value.amount = remainingAmount.value;
  }
}

async function handleRecordSubmit() {
  if (!recordForm.value.salesOrderId || !recordForm.value.amount) {
    ElMessage.warning('请填写订单ID和收款金额'); return;
  }
  if (recordOverLimit.value) {
    ElMessage.warning(`收款金额超过可收余额 ¥${remainingAmount.value.toFixed(2)}, 请调低`); return;
  }
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/finance/payments/record`, recordForm.value);
    if (res.success) {
      ElMessage.success('收款记录已创建');
      recordDialogVisible.value = false;
      loadData();
    } else { ElMessage.error(res.message || '创建失败'); }
  } catch { /* axios interceptor already displayed error toast */ }
  finally { submitting.value = false; }
}
</script>

<template>
  <div class="page-wrapper" v-loading="loading">
    <el-card shadow="never">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span style="font-size:16px;font-weight:600">收款管理</span>
          <div style="display:flex;gap:8px">
            <!-- Apr 20 Bug BR-12 fix: 加 keyword 搜索 (客户 / 收款单号 / 发票号) -->
            <el-input v-model="searchKeyword" placeholder="搜索 客户/收款单号/发票号" clearable style="width:220px" @keyup.enter="handleSearch" />
            <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width:140px" @change="loadData">
              <el-option v-for="(v,k) in statusMap" :key="k" :label="v.text" :value="k" />
            </el-select>
            <el-button type="primary" @click="handleSearch">搜索</el-button>
            <el-button @click="handleReset">重置</el-button>
            <el-button v-if="canWrite && canViewPrice" type="primary" @click="recordDialogVisible = true">录入收款</el-button>
          </div>
        </div>
      </template>

      <el-table :data="tableData" border stripe>
        <el-table-column prop="paymentNumber" label="收款编号" width="180" />
        <el-table-column prop="customerName" label="客户" min-width="130" />
        <el-table-column v-if="canViewPrice" prop="amount" label="收款金额" width="130" align="right">
          <template #default="{ row }">{{ formatAmount(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="paymentMethod" label="方式" width="100" align="center">
          <template #default="{ row }">{{ methodMap[row.paymentMethod] || row.paymentMethod }}</template>
        </el-table-column>
        <el-table-column prop="paymentDate" label="收款日期" width="120" />
        <el-table-column prop="paymentReference" label="流水号" min-width="140" show-overflow-tooltip />
        <el-table-column prop="receiptUrl" label="凭证" width="70" align="center">
          <template #default="{ row }">
            <a v-if="row.receiptUrl" :href="row.receiptUrl" target="_blank">查看</a>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusMap[row.status]?.type || 'info'" size="small">{{ statusMap[row.status]?.text || row.status }}</el-tag>
          </template>
        </el-table-column>
        <!--
          Sprint 6 W3-A — 行内 3-chip 链接计数 (文件 / 图片 / 合同).
          凭证附件: 转账记录 (DOCUMENT) + 银行回单截图 (PHOTO) + 合同 (CONTRACT).
          数据源: POST /attachments/batch-3chip-counts (batch, 避免 N+1).
          注: 'receiptUrl' 列是单图凭证 (老 schema), 跟本列互补.
        -->
        <el-table-column label="附件" width="200" align="center">
          <template #header>
            <span style="display: inline-flex; align-items: center; gap: 4px;">
              附件
              <el-tooltip placement="top">
                <template #content>
                  <div style="line-height: 1.6;">
                    <div><b>文件</b>: 转账记录 / 凭证 (DOCUMENT / OTHER)</div>
                    <div><b>图片</b>: 银行回单截图 (PHOTO / VIDEO)</div>
                    <div><b>合同</b>: 关联销售/采购合同 (CONTRACT)</div>
                    <div style="margin-top: 4px; color: var(--text-color-secondary);">'凭证' 列是单图老 schema, 此列是新多附件</div>
                  </div>
                </template>
                <el-icon style="cursor: help; color: var(--text-color-secondary, #909399); font-size: 12px;"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <template #default="{ row }">
            <LinkChipCell :counts="linkCountsFor(row.id)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" v-if="canWrite">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PENDING'" type="success" link size="small" @click="handleVerify(row.id)">确认</el-button>
            <el-button v-if="row.status === 'PENDING'" type="danger" link size="small" @click="handleReject(row.id)">驳回</el-button>
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

    <!-- 录入收款弹窗 (E-FP-3 防呆 R1 retrofit) -->
    <el-dialog
      v-model="recordDialogVisible"
      :title="selectedSO
        ? `录入收款 — ${selectedSO.customerName || '客户未知'} (${selectedSO.orderNumber})`
        : '录入收款'"
      width="480px"
      destroy-on-close
    >
      <el-form label-width="90px">
        <el-form-item label="销售订单" required>
          <el-select
            v-model="recordForm.salesOrderId"
            placeholder="选择已确认的销售订单"
            filterable
            style="width:100%"
            @change="onSalesOrderSelect"
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
        <!-- E-FP-3 (fool-proof Rule 1): 显式列出 订单总额 / 已收 / 可收 + input :max.
             paidAmount 由 SalesOrder 实体直接序列化 (entity line 223), 纯前端实现. -->
        <el-alert
          v-if="canViewPrice && selectedSO"
          type="info"
          :closable="false"
          show-icon
          style="margin-bottom:12px"
        >
          <template #title>
            订单总额 <b>¥{{ Number(selectedSO.totalAmount || 0).toFixed(2) }}</b>
            · 已收 <b>¥{{ Number(selectedSO.paidAmount || 0).toFixed(2) }}</b>
            · <span :style="{ color: remainingAmount > 0 ? '#67c23a' : '#f56c6c' }">可收 <b>¥{{ remainingAmount.toFixed(2) }}</b></span>
          </template>
          <template v-if="remainingAmount === 0" #default>
            该订单已全额收款, 无需再次录入
          </template>
        </el-alert>
        <el-form-item v-if="canViewPrice" label="收款金额" required>
          <el-input-number
            v-model="recordForm.amount"
            :min="0"
            :max="selectedSO && remainingAmount > 0 ? remainingAmount : undefined"
            :precision="2"
            style="width:100%"
          />
          <span v-if="recordOverLimit" style="color:#f56c6c;margin-left:8px;font-size:12px">
            超过可收余额 ¥{{ remainingAmount.toFixed(2) }}, 请调低
          </span>
        </el-form-item>
        <el-form-item label="支付方式">
          <el-select v-model="recordForm.paymentMethod" style="width:100%">
            <el-option v-for="(v,k) in methodMap" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="收款日期">
          <el-date-picker v-model="recordForm.paymentDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>
        <el-form-item label="流水号">
          <el-input v-model="recordForm.paymentReference" placeholder="银行流水号/支付凭证号" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="recordForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="recordDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="!recordForm.salesOrderId || !canViewPrice || recordOverLimit || (!!selectedSO && remainingAmount === 0)"
          @click="handleRecordSubmit"
        >提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>
