<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { get } from '@/api/request';
import {
  approveCustomerMaterialArrival,
  cancelCustomerMaterialArrival,
  createCustomerMaterialArrival,
  listCustomerMaterialArrivals,
  rejectCustomerMaterialArrival,
  type CustomerMaterialArrivalNotice,
  type CustomerMaterialArrivalStatus,
  type UnorderedInboundReason,
} from '@/api/customerMaterialArrival';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import type { TableRow } from '@/types/api';

type ApplicationTab = 'ALL' | 'PENDING_APPROVAL' | 'OPEN' | 'REJECTED' | 'CANCELLED';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId || '');
const canCreate = computed(() => permissionStore.canWrite('operations'));
const canApprove = computed(() => permissionStore.canWrite('warehouse'));

const loading = ref(false);
const submitting = ref(false);
const reviewing = ref(false);
const createVisible = ref(false);
const reviewVisible = ref(false);
const reviewAction = ref<'approve' | 'reject'>('approve');
const selected = ref<CustomerMaterialArrivalNotice | null>(null);
const activeTab = ref<ApplicationTab>('ALL');
const keyword = ref('');
const rows = ref<CustomerMaterialArrivalNotice[]>([]);
const customers = ref<TableRow[]>([]);
const reviewRemark = ref('');
const reviewReason = ref('');
const rejectReasonOptions = [
  { value: 'MISSING_INFORMATION', label: '资料不完整' },
  { value: 'UNVERIFIED_SOURCE', label: '来源无法核实' },
  { value: 'UNCLEAR_OWNERSHIP', label: '库存归属不清' },
  { value: 'ARRIVAL_INFORMATION_ERROR', label: '预计到达信息有误' },
  { value: 'OTHER', label: '其他' },
];

const form = reactive({
  reason: 'CUSTOMER_MATERIAL' as UnorderedInboundReason,
  customerId: '',
  expectedArrivalAt: '',
  contactName: '',
  contactPhone: '',
  remark: '',
});

const reasonText: Record<UnorderedInboundReason, string> = {
  CUSTOMER_MATERIAL: '客户来料',
  GIFT: '赠予',
  OTHER: '其他',
};
const statusMeta: Record<CustomerMaterialArrivalStatus, { text: string; type: 'primary' | 'success' | 'warning' | 'danger' | 'info' }> = {
  PENDING_APPROVAL: { text: '待审批', type: 'warning' },
  OPEN: { text: '审批通过', type: 'success' },
  PARTIALLY_RECEIVED: { text: '审批通过', type: 'success' },
  RECEIVED: { text: '审批通过', type: 'success' },
  REJECTED: { text: '审批驳回', type: 'danger' },
  CANCELLED: { text: '已撤回', type: 'info' },
};

const customerRequired = computed(() => form.reason === 'CUSTOMER_MATERIAL');
const ownershipPreview = computed(() => customerRequired.value
  ? '客户所有：只能用于所选客户'
  : '公司所有：进入本厂普通库存');
const counts = computed(() => ({
  ALL: rows.value.length,
  PENDING_APPROVAL: rows.value.filter((row) => row.status === 'PENDING_APPROVAL').length,
  OPEN: rows.value.filter((row) => ['OPEN', 'PARTIALLY_RECEIVED', 'RECEIVED'].includes(row.status)).length,
  REJECTED: rows.value.filter((row) => row.status === 'REJECTED').length,
  CANCELLED: rows.value.filter((row) => row.status === 'CANCELLED').length,
}));
const filteredRows = computed(() => {
  const normalized = keyword.value.trim().toLocaleLowerCase('zh-CN');
  return rows.value.filter((row) => {
    const tabMatch = activeTab.value === 'ALL'
      || (activeTab.value === 'OPEN'
        ? ['OPEN', 'PARTIALLY_RECEIVED', 'RECEIVED'].includes(row.status)
        : row.status === activeTab.value);
    const keywordMatch = !normalized || [
      row.noticeNumber, row.customerName, row.contactName, row.remark, row.reviewRemark,
    ].some((value) => String(value || '').toLocaleLowerCase('zh-CN').includes(normalized));
    return tabMatch && keywordMatch;
  });
});

async function load() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const response = await listCustomerMaterialArrivals(factoryId.value, false);
    rows.value = response.success && Array.isArray(response.data) ? response.data : [];
  } finally {
    loading.value = false;
  }
}

async function loadCustomers() {
  if (!factoryId.value || !canCreate.value) return;
  const response = await get(`/${factoryId.value}/customers`, {
    params: { page: 1, size: 200 },
    _silent: true,
  } as never);
  customers.value = Array.isArray(response.data?.content) ? response.data.content : [];
}

function openCreate() {
  Object.assign(form, {
    reason: 'CUSTOMER_MATERIAL', customerId: '', expectedArrivalAt: '',
    contactName: '', contactPhone: '', remark: '',
  });
  createVisible.value = true;
}

function onReasonChange() {
  if (!customerRequired.value) form.customerId = '';
}

async function submit() {
  if (customerRequired.value && !form.customerId) {
    ElMessage.warning('客户来料必须选择归属客户');
    return;
  }
  submitting.value = true;
  try {
    const response = await createCustomerMaterialArrival(factoryId.value, {
      reason: form.reason,
      customerId: form.customerId || undefined,
      expectedArrivalAt: form.expectedArrivalAt || undefined,
      contactName: form.contactName.trim() || undefined,
      contactPhone: form.contactPhone.trim() || undefined,
      remark: form.remark.trim() || undefined,
    });
    if (!response.success) throw new Error(response.message || '创建失败');
    ElMessage.success('申请已提交，审批通过前不会进入入库任务');
    createVisible.value = false;
    activeTab.value = 'PENDING_APPROVAL';
    await load();
  } finally {
    submitting.value = false;
  }
}

function openReview(row: CustomerMaterialArrivalNotice, action: 'approve' | 'reject') {
  selected.value = row;
  reviewAction.value = action;
  reviewRemark.value = '';
  reviewReason.value = '';
  reviewVisible.value = true;
}

async function submitReview() {
  if (!selected.value) return;
  if (reviewAction.value === 'reject' && !reviewReason.value) {
    ElMessage.warning('请选择驳回原因');
    return;
  }
  if (reviewAction.value === 'reject' && reviewReason.value === 'OTHER' && !reviewRemark.value.trim()) {
    ElMessage.warning('选择“其他”时请填写具体原因');
    return;
  }
  const selectedReason = rejectReasonOptions.find((option) => option.value === reviewReason.value)?.label;
  const effectiveRemark = reviewAction.value === 'reject'
    ? [selectedReason, reviewRemark.value.trim()].filter(Boolean).join('：')
    : reviewRemark.value;
  reviewing.value = true;
  try {
    const response = reviewAction.value === 'approve'
      ? await approveCustomerMaterialArrival(factoryId.value, selected.value.id, effectiveRemark)
      : await rejectCustomerMaterialArrival(factoryId.value, selected.value.id, effectiveRemark);
    if (!response.success) throw new Error(response.message || '审批失败');
    ElMessage.success(reviewAction.value === 'approve'
      ? '审批通过，已交接到入库任务与批次'
      : '申请已驳回');
    reviewVisible.value = false;
    await load();
  } finally {
    reviewing.value = false;
  }
}

async function withdraw(row: CustomerMaterialArrivalNotice) {
  try {
    await ElMessageBox.confirm(
      `确认撤回申请 ${row.noticeNumber}？`,
      '撤回无订单入库申请',
      { type: 'warning', confirmButtonText: '确认撤回', cancelButtonText: '返回' },
    );
  } catch {
    return;
  }
  const response = await cancelCustomerMaterialArrival(factoryId.value, row.id);
  if (!response.success) throw new Error(response.message || '撤回失败');
  ElMessage.success('申请已撤回');
  await load();
}

function goToTask(row: CustomerMaterialArrivalNotice) {
  void router.push({
    path: '/warehouse/materials',
    query: {
      view: 'receiving',
      sourceType: 'CUSTOMER_MATERIAL_ARRIVAL',
      arrivalNoticeId: row.id,
    },
  });
}

function ownerText(row: CustomerMaterialArrivalNotice): string {
  return row.reason === 'CUSTOMER_MATERIAL'
    ? `客户所有 · ${row.customerName || '客户待核对'}`
    : '公司所有';
}

function handoffText(row: CustomerMaterialArrivalNotice): string {
  if (row.status === 'OPEN') return '已生成待入库任务';
  if (row.status === 'PARTIALLY_RECEIVED') return '入库任务处理中';
  if (row.status === 'RECEIVED') return '入库任务已完成';
  return '审批通过后生成';
}

onMounted(() => { void Promise.allSettled([load(), loadCustomers()]); });
</script>

<template>
  <div class="application-page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">仓储管理 / 申请审批</p>
        <h2>无订单入库申请</h2>
        <p>这里只处理申请和审批。审批通过后，系统才把来源交接到“入库任务与批次”。</p>
      </div>
      <el-button v-if="canCreate" type="primary" size="large" @click="openCreate">发起申请</el-button>
    </header>

    <el-alert
      class="boundary-alert"
      type="info"
      :closable="false"
      show-icon
      title="职责边界：本页不处理实物、数量、仓库或库存批次；审批通过仅生成可追溯的仓储任务。"
    />

    <section class="application-card">
      <el-tabs v-model="activeTab" class="status-tabs">
        <el-tab-pane :label="`全部 ${counts.ALL}`" name="ALL" />
        <el-tab-pane :label="`待审批 ${counts.PENDING_APPROVAL}`" name="PENDING_APPROVAL" />
        <el-tab-pane :label="`审批通过 ${counts.OPEN}`" name="OPEN" />
        <el-tab-pane :label="`审批驳回 ${counts.REJECTED}`" name="REJECTED" />
        <el-tab-pane :label="`已撤回 ${counts.CANCELLED}`" name="CANCELLED" />
      </el-tabs>

      <div class="toolbar">
        <el-input v-model="keyword" clearable placeholder="搜索申请单号 / 客户 / 说明" />
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="filteredRows" border empty-text="当前分类暂无申请">
        <el-table-column prop="noticeNumber" label="申请单号" min-width="190" />
        <el-table-column label="入库原因" width="110">
          <template #default="{ row }">{{ reasonText[row.reason as UnorderedInboundReason] || row.reason }}</template>
        </el-table-column>
        <el-table-column label="库存归属" min-width="200">
          <template #default="{ row }">{{ ownerText(row) }}</template>
        </el-table-column>
        <el-table-column prop="expectedArrivalAt" label="预计到达" min-width="165" />
        <el-table-column label="审批状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusMeta[row.status as CustomerMaterialArrivalStatus].type">
              {{ statusMeta[row.status as CustomerMaterialArrivalStatus].text }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="任务交接" min-width="165">
          <template #default="{ row }">{{ handoffText(row) }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="申请说明" min-width="200" show-overflow-tooltip />
        <el-table-column prop="reviewRemark" label="审批意见" min-width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING_APPROVAL'">
              <el-button v-if="canApprove" type="success" link @click="openReview(row, 'approve')">通过</el-button>
              <el-button v-if="canApprove" type="danger" link @click="openReview(row, 'reject')">驳回</el-button>
              <el-button v-if="canCreate" type="info" link @click="withdraw(row)">撤回</el-button>
            </template>
            <el-button v-else-if="['OPEN', 'PARTIALLY_RECEIVED'].includes(row.status)" type="primary" link @click="goToTask(row)">
              查看关联任务
            </el-button>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="createVisible" title="发起无订单入库申请" width="min(560px, calc(100vw - 32px))" :close-on-click-modal="false">
      <el-alert type="info" :closable="false" show-icon title="提交后进入待审批，不会直接生成入库任务或增加库存。" />
      <el-form :model="form" label-width="96px" class="dialog-form">
        <el-form-item label="入库原因" required>
          <el-radio-group v-model="form.reason" @change="onReasonChange">
            <el-radio-button value="CUSTOMER_MATERIAL">客户来料</el-radio-button>
            <el-radio-button value="GIFT">赠予</el-radio-button>
            <el-radio-button value="OTHER">其他</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="customerRequired ? '归属客户' : '关联客户'" :required="customerRequired">
          <el-select v-model="form.customerId" filterable clearable style="width: 100%" :placeholder="customerRequired ? '必须选择库存归属客户' : '可选'">
            <el-option v-for="customer in customers" :key="customer.id" :label="customer.name" :value="String(customer.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="库存归属"><el-tag>{{ ownershipPreview }}</el-tag></el-form-item>
        <el-form-item label="预计到达"><el-date-picker v-model="form.expectedArrivalAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" /></el-form-item>
        <el-form-item label="联系人"><el-input v-model="form.contactName" maxlength="100" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="form.contactPhone" maxlength="50" /></el-form-item>
        <el-form-item label="申请说明"><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">提交审批</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reviewVisible" :title="reviewAction === 'approve' ? '审批通过' : '审批驳回'" width="min(520px, calc(100vw - 32px))">
      <p class="review-target">申请单：{{ selected?.noticeNumber }}</p>
      <el-select v-if="reviewAction === 'reject'" v-model="reviewReason" placeholder="选择驳回原因" class="review-reason">
        <el-option v-for="option in rejectReasonOptions" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-input
        v-if="reviewAction === 'approve' || reviewReason === 'OTHER'"
        v-model="reviewRemark"
        type="textarea"
        :rows="4"
        maxlength="1000"
        show-word-limit
        :placeholder="reviewAction === 'approve' ? '可填写审批意见' : '请填写具体驳回原因'"
      />
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button :type="reviewAction === 'approve' ? 'success' : 'danger'" :loading="reviewing" @click="submitReview">
          {{ reviewAction === 'approve' ? '确认通过并交接任务' : '确认驳回' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.application-page { padding: 20px; }
.page-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; margin-bottom: 16px; }
.page-heading h2 { margin: 2px 0 8px; color: var(--el-text-color-primary); }
.page-heading p { margin: 0; color: var(--el-text-color-secondary); }
.eyebrow { font-size: 13px; color: var(--el-color-primary) !important; }
.boundary-alert { margin-bottom: 16px; }
.application-card { padding: 18px; background: var(--el-bg-color); border: 1px solid var(--el-border-color-light); border-radius: 10px; }
.toolbar { display: flex; justify-content: flex-end; gap: 12px; margin: 4px 0 16px; }
.toolbar .el-input { width: 320px; }
.dialog-form { margin-top: 18px; }
.review-target { margin-top: 0; color: var(--el-text-color-secondary); }
.review-reason { width: 100%; margin-bottom: 12px; }
@media (max-width: 760px) {
  .page-heading { flex-direction: column; }
  .page-heading .el-button, .toolbar .el-input { width: 100%; }
  .toolbar { flex-direction: column; }
}
</style>
