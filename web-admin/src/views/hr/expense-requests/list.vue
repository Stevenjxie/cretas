<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, Plus } from '@element-plus/icons-vue';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('hr'));
const canFinanceWrite = computed(() => permissionStore.canWrite('finance'));

type ExpenseRow = {
  id: string;
  userId: number;
  category: string;
  amount: number;
  expenseDate: string;
  reason?: string;
  status: 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'PAID';
  paymentRecordId?: string;
  submittedAt?: string;
  approvedAt?: string;
  paidAt?: string;
  rejectReason?: string;
};

const CATEGORIES = [
  { value: 'TRAVEL', label: '差旅' },
  { value: 'MEAL', label: '餐补' },
  { value: 'OFFICE', label: '办公用品' },
  { value: 'ENTERTAIN', label: '招待' },
  { value: 'TRAINING', label: '培训' },
  { value: 'OTHER', label: '其它' },
];
const CATEGORY_LABEL = Object.fromEntries(CATEGORIES.map(c => [c.value, c.label]));

const STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿', SUBMITTED: '待审批', APPROVED: '已批准',
  REJECTED: '已拒绝', CANCELLED: '已撤回', PAID: '已付款',
};
const STATUS_TAG_TYPE: Record<string, '' | 'success' | 'warning' | 'danger' | 'info'> = {
  DRAFT: 'info', SUBMITTED: 'warning', APPROVED: 'success',
  REJECTED: 'danger', CANCELLED: 'info', PAID: 'success',
};

const REJECT_REASONS = ['凭证不全', '超额超标', '不符合报销范围', '类目错误', '重复报销', '其他'];

const activeTab = ref<'mine' | 'pending' | 'all' | 'summary'>('mine');
const loading = ref(false);
const tableData = ref<ExpenseRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const summaryData = ref<Record<string, number>>({});
const summaryYearMonth = ref<string>(currentYearMonth());

// Create dialog
const createDialogVisible = ref(false);
const createSubmitting = ref(false);
const createForm = ref({
  category: 'TRAVEL', amount: 0, expenseDate: '', reason: '',
});
// R1 monthly category hint
const monthlyCatHint = ref<Record<string, number> | null>(null);

// Reject dialog
const rejectDialogVisible = ref(false);
const rejectingRow = ref<ExpenseRow | null>(null);
const rejectForm = ref({ reasonKey: '凭证不全', otherDetail: '' });
const rejectSubmitting = ref(false);

// Mark-paid dialog
const markPaidDialogVisible = ref(false);
const markPaidRow = ref<ExpenseRow | null>(null);
const markPaidForm = ref({ paymentRecordId: '' });
const markPaidSubmitting = ref(false);

function currentYearMonth(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

onMounted(() => { loadData(); });
watch(activeTab, () => {
  pagination.value.page = 1;
  if (activeTab.value === 'summary') loadSummary(); else loadData();
});
watch(() => createForm.value.expenseDate, () => {
  if (createDialogVisible.value) loadMonthlyHint();
});

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const endpoint = activeTab.value === 'mine'
      ? `/${factoryId.value}/hr/expense-requests/mine`
      : activeTab.value === 'pending'
        ? `/${factoryId.value}/hr/expense-requests/pending`
        : `/${factoryId.value}/hr/expense-requests`;
    const res = await get(endpoint, {
      params: { page: pagination.value.page - 1, size: pagination.value.size }
    });
    if (res.success && res.data) {
      const data = res.data as { content?: ExpenseRow[]; totalElements?: number };
      tableData.value = data.content || [];
      pagination.value.total = data.totalElements || 0;
    } else {
      tableData.value = [];
      pagination.value.total = 0;
    }
  } catch (e) {
    console.error('加载报销申请失败:', e);
    tableData.value = [];
    pagination.value.total = 0;
  } finally {
    loading.value = false;
  }
}

async function loadSummary() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await get(`/${factoryId.value}/hr/expense-requests/summary`, {
      params: { yearMonth: summaryYearMonth.value }
    });
    if (res.success && res.data) summaryData.value = res.data as Record<string, number>;
  } catch (e) {
    console.error('加载月汇总失败:', e);
  } finally {
    loading.value = false;
  }
}

// R1: 本月各类目已批准报销金额
async function loadMonthlyHint() {
  monthlyCatHint.value = null;
  if (!factoryId.value || !createForm.value.expenseDate) return;
  const ym = createForm.value.expenseDate.slice(0, 7);
  try {
    const res = await get(`/${factoryId.value}/hr/expense-requests/summary`, {
      params: { yearMonth: ym }
    });
    if (res.success && res.data) {
      monthlyCatHint.value = res.data as Record<string, number>;
    }
  } catch (e) {
    console.error('加载月类目 hint 失败:', e);
  }
}

const monthlyCatLine = computed(() => {
  if (!monthlyCatHint.value) return '';
  const cur = monthlyCatHint.value[createForm.value.category] || 0;
  return `本月 ${CATEGORY_LABEL[createForm.value.category]} 已审 ¥${cur}`;
});

function openCreate() {
  createForm.value = { category: 'TRAVEL', amount: 0, expenseDate: '', reason: '' };
  monthlyCatHint.value = null;
  createDialogVisible.value = true;
}

async function handleCreate(submitNow: boolean) {
  if (createSubmitting.value) return;
  if (!createForm.value.expenseDate) {
    ElMessage.error('请选择发生日期');
    return;
  }
  if (createForm.value.amount <= 0) {
    ElMessage.error('金额必须 > 0');
    return;
  }
  createSubmitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/hr/expense-requests`, createForm.value);
    if (res.success && res.data) {
      const id = (res.data as { id: string }).id;
      if (submitNow) {
        await post(`/${factoryId.value}/hr/expense-requests/${id}/submit`);
        ElMessage.success('已提交审批');
      } else {
        ElMessage.success('已保存草稿');
      }
      createDialogVisible.value = false;
      await loadData();
    }
  } catch (e) {
    console.error('创建失败:', e);
  } finally {
    createSubmitting.value = false;
  }
}

async function approveRow(row: ExpenseRow) {
  try {
    await ElMessageBox.confirm(
      `批准 [申请人 ${row.userId}] ${CATEGORY_LABEL[row.category]} ¥${row.amount} (${row.expenseDate})?\n批准后自动创建 PaymentRecord PENDING.`,
      '审批确认',
      { confirmButtonText: '批准', cancelButtonText: '取消', type: 'warning' }
    );
    const res = await post(`/${factoryId.value}/hr/expense-requests/${row.id}/approve`);
    if (res.success) { ElMessage.success('已批准, PaymentRecord 已创建'); await loadData(); }
  } catch (e) { if (e !== 'cancel') console.error(e); }
}

function openReject(row: ExpenseRow) {
  rejectingRow.value = row;
  rejectForm.value = { reasonKey: '凭证不全', otherDetail: '' };
  rejectDialogVisible.value = true;
}

async function confirmReject() {
  if (!rejectingRow.value || rejectSubmitting.value) return;
  const finalReason = rejectForm.value.reasonKey === '其他'
    ? rejectForm.value.otherDetail.trim()
    : rejectForm.value.reasonKey;
  if (!finalReason) {
    ElMessage.error('请填写"其他"补充说明');
    return;
  }
  rejectSubmitting.value = true;
  try {
    const res = await post(
      `/${factoryId.value}/hr/expense-requests/${rejectingRow.value.id}/reject`,
      { reason: finalReason }
    );
    if (res.success) {
      ElMessage.success('已拒绝');
      rejectDialogVisible.value = false;
      await loadData();
    }
  } catch (e) { console.error(e); }
  finally { rejectSubmitting.value = false; }
}

async function cancelRow(row: ExpenseRow) {
  try {
    await ElMessageBox.confirm(`撤回 ${CATEGORY_LABEL[row.category]} ¥${row.amount}?`, '撤回确认');
    const res = await post(`/${factoryId.value}/hr/expense-requests/${row.id}/cancel`);
    if (res.success) { ElMessage.success('已撤回'); await loadData(); }
  } catch (e) { if (e !== 'cancel') console.error(e); }
}

function openMarkPaid(row: ExpenseRow) {
  markPaidRow.value = row;
  markPaidForm.value = { paymentRecordId: row.paymentRecordId || '' };
  markPaidDialogVisible.value = true;
}

async function confirmMarkPaid() {
  if (!markPaidRow.value || markPaidSubmitting.value) return;
  markPaidSubmitting.value = true;
  try {
    const res = await post(
      `/${factoryId.value}/hr/expense-requests/${markPaidRow.value.id}/mark-paid`,
      markPaidForm.value.paymentRecordId ? { paymentRecordId: markPaidForm.value.paymentRecordId } : {}
    );
    if (res.success) {
      ElMessage.success('已标记付款');
      markPaidDialogVisible.value = false;
      await loadData();
    }
  } catch (e) { console.error(e); }
  finally { markPaidSubmitting.value = false; }
}
</script>

<template>
  <div class="expense-requests-page" style="padding: 16px;">
    <el-card>
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
        <h2 style="margin: 0;">报销申请</h2>
        <div>
          <el-button type="primary" :icon="Plus" @click="openCreate">新建报销</el-button>
          <el-button :icon="Refresh" @click="activeTab === 'summary' ? loadSummary() : loadData()">刷新</el-button>
        </div>
      </div>

      <el-tabs v-model="activeTab">
        <el-tab-pane label="我的申请" name="mine" />
        <el-tab-pane v-if="canWrite" label="待审批" name="pending" />
        <el-tab-pane v-if="canWrite" label="全部" name="all" />
        <el-tab-pane v-if="canWrite" label="月汇总" name="summary" />
      </el-tabs>

      <div v-if="activeTab === 'summary'">
        <el-form inline>
          <el-form-item label="月份">
            <el-input v-model="summaryYearMonth" placeholder="YYYY-MM" style="width: 140px;" @change="loadSummary" />
          </el-form-item>
        </el-form>
        <el-table :data="Object.entries(summaryData).map(([k, v]) => ({ category: k, amount: v }))" v-loading="loading">
          <el-table-column prop="category" label="类目" width="200">
            <template #default="{ row }">{{ CATEGORY_LABEL[row.category] || row.category }}</template>
          </el-table-column>
          <el-table-column prop="amount" label="总金额(¥)" />
        </el-table>
      </div>

      <div v-else>
        <el-table :data="tableData" v-loading="loading" stripe>
          <el-table-column prop="category" label="类目" width="120">
            <template #default="{ row }">{{ CATEGORY_LABEL[row.category] || row.category }}</template>
          </el-table-column>
          <el-table-column v-if="activeTab !== 'mine'" prop="userId" label="申请人" width="100" />
          <el-table-column prop="amount" label="金额(¥)" width="120" />
          <el-table-column prop="expenseDate" label="发生日期" width="120" />
          <el-table-column prop="reason" label="说明" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="STATUS_TAG_TYPE[row.status]">{{ STATUS_LABEL[row.status] }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="paymentRecordId" label="付款单号" width="160" show-overflow-tooltip />
          <el-table-column prop="submittedAt" label="提交时间" width="160" />
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <template v-if="activeTab === 'mine' && (row.status === 'DRAFT' || row.status === 'SUBMITTED')">
                <el-button size="small" type="danger" @click="cancelRow(row)">撤回</el-button>
              </template>
              <template v-if="activeTab === 'pending' && canWrite && row.status === 'SUBMITTED'">
                <el-button size="small" type="success" @click="approveRow(row)">批准</el-button>
                <el-button size="small" type="danger" @click="openReject(row)">拒绝</el-button>
              </template>
              <el-button v-if="row.status === 'APPROVED' && canFinanceWrite" size="small" type="primary"
                @click="openMarkPaid(row)">标记付款</el-button>
              <span v-if="row.status === 'REJECTED' && row.rejectReason" style="color: #f56c6c; font-size: 12px;">
                拒绝: {{ row.rejectReason }}
              </span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :total="pagination.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          style="margin-top: 16px; justify-content: flex-end;"
          @current-change="loadData"
          @size-change="loadData"
        />
      </div>
    </el-card>

    <!-- R1+R3 防呆 新建对话框 -->
    <el-dialog v-model="createDialogVisible" title="新建报销申请" width="560px">
      <el-form :model="createForm" label-width="100px">
        <el-form-item label="类目" required>
          <el-select v-model="createForm.category" style="width: 100%;">
            <el-option v-for="c in CATEGORIES" :key="c.value" :label="c.label" :value="c.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="金额 (¥)" required>
          <el-input-number v-model="createForm.amount" :min="0.01" :step="10" :precision="2" />
        </el-form-item>
        <el-form-item label="发生日期" required>
          <el-date-picker v-model="createForm.expenseDate" type="date" value-format="YYYY-MM-DD"
            placeholder="选择日期" style="width: 100%;" />
        </el-form-item>
        <el-alert v-if="monthlyCatHint" type="info" :title="monthlyCatLine"
          :closable="false" show-icon style="margin-bottom: 16px;" />
        <el-form-item label="说明">
          <el-input v-model="createForm.reason" type="textarea" :rows="3"
            placeholder="必填: 说明用途 (差旅地点 / 培训内容等)" />
        </el-form-item>
        <el-alert type="warning" :closable="false"
          title="提示: 请提交报销凭证至 OSS, 通过附件功能挂载到本次申请 (entity_type=EXPENSE_REQUEST)" />
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button :loading="createSubmitting" @click="handleCreate(false)">保存草稿</el-button>
        <el-button type="primary" :loading="createSubmitting" @click="handleCreate(true)">保存并提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rejectDialogVisible" title="拒绝报销" width="480px">
      <div v-if="rejectingRow" style="margin-bottom: 16px; color: #909399; font-size: 13px;">
        申请人 {{ rejectingRow.userId }} ·
        {{ CATEGORY_LABEL[rejectingRow.category] }} ·
        ¥{{ rejectingRow.amount }} ·
        {{ rejectingRow.expenseDate }}
      </div>
      <el-form :model="rejectForm" label-width="80px">
        <el-form-item label="原因" required>
          <el-select v-model="rejectForm.reasonKey" style="width: 100%;">
            <el-option v-for="r in REJECT_REASONS" :key="r" :label="r" :value="r" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="rejectForm.reasonKey === '其他'" label="补充说明" required>
          <el-input v-model="rejectForm.otherDetail" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="rejectSubmitting" @click="confirmReject">确认拒绝</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="markPaidDialogVisible" title="标记付款" width="480px">
      <div v-if="markPaidRow" style="margin-bottom: 16px; color: #909399; font-size: 13px;">
        申请人 {{ markPaidRow.userId }} · {{ CATEGORY_LABEL[markPaidRow.category] }} · ¥{{ markPaidRow.amount }}
      </div>
      <el-form :model="markPaidForm" label-width="120px">
        <el-form-item label="PaymentRecord ID">
          <el-input v-model="markPaidForm.paymentRecordId" placeholder="approve 自动创建, 留空 = 手工记账" />
        </el-form-item>
        <el-alert v-if="markPaidRow?.paymentRecordId" type="info"
          :title="`审批通过时已自动创建 PaymentRecord: ${markPaidRow.paymentRecordId}`"
          :closable="false" />
      </el-form>
      <template #footer>
        <el-button @click="markPaidDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="markPaidSubmitting" @click="confirmMarkPaid">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>
