<script setup lang="ts">
/**
 * 请购单详情 — Sprint 6 W2-A.
 *
 * State machine actions wired:
 *   - DRAFT → 提交审批 (submit)
 *   - PENDING_APPROVAL → 审批通过 (approve) / 驳回 (reject with reason dropdown)
 *   - APPROVED → 转 PO (convertToPO with supplier select; idempotent backend)
 *
 * Fool-proof Rule 2 (context): all dialogs include 单号 + 行数 + 请购人.
 * Fool-proof Rule 3 (constrained inputs): reject reason dropdown + OTHER textarea.
 * Fool-proof Rule 4 (idempotent): convertToPO backend dedupes by convertedPoId.
 */
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowLeft } from '@element-plus/icons-vue';
import { get } from '@/api/request';
import {
  getRequisition,
  submitRequisition,
  approveRequisition,
  rejectRequisition,
  convertToPO,
  deleteRequisition,
  REQUISITION_STATUS_MAP,
  type PurchaseRequisition,
  type PurchaseRequisitionStatus,
} from '@/api/purchaseRequisition';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();

const factoryId = computed(() => authStore.factoryId);
const requisitionId = computed(() => route.params.id as string);
const currentUserId = computed(() => authStore.user?.id ?? null);
const canWrite = computed(() => permissionStore.canWrite('procurement'));
const isRestaurant = computed(() => authStore.factoryType === 'RESTAURANT');
const canManageOwnDraft = computed(() => {
  if (canWrite.value) return true;
  if (!isRestaurant.value || !permissionStore.canAccess('procurement')) return false;
  return ['warehouse_manager', 'restaurant_manager'].includes(authStore.currentRole);
});
const detailTitle = computed(() => (isRestaurant.value ? '报货单详情' : '请购单详情'));
const submitButtonText = computed(() => (isRestaurant.value ? '提交采购计划' : '提交审批'));
const expectedDateLabel = computed(() => (isRestaurant.value ? '希望到货' : '期望交货'));
const requesterLabel = computed(() => (isRestaurant.value ? '报货人ID' : '请购人ID'));
const requesterDeptLabel = computed(() => (isRestaurant.value ? '报货部门' : '请购部门'));
const reasonLabel = computed(() => (isRestaurant.value ? '报货原因' : '申请原因'));
const detailDividerText = computed(() => (isRestaurant.value ? '报货明细' : '请购明细'));

const loading = ref(false);
const submitting = ref(false);
const requisition = ref<PurchaseRequisition | null>(null);
const notFound = ref(false);
const notFoundMessage = ref('');

// Bug #6 (PR #96): 仅 DRAFT + requester 本人 可删 (backend 同步 enforce)
const canDelete = computed(
  () =>
    canManageOwnDraft.value &&
    requisition.value?.status === 'DRAFT' &&
    currentUserId.value !== null &&
    requisition.value?.requesterId === currentUserId.value,
);

interface Supplier {
  id: string;
  name: string;
}
const suppliers = ref<Supplier[]>([]);

// 驳回 dialog state (same constrained-dropdown as pending-approval.vue)
const rejectDialogVisible = ref(false);
const rejectReasonCode = ref<string>('');
const rejectReasonOther = ref<string>('');
const REJECT_REASONS: { code: string; label: string }[] = [
  { code: 'BUDGET_EXCEEDED', label: '预算超支' },
  { code: 'STOCK_AVAILABLE', label: '现有库存可满足, 暂不采购' },
  { code: 'SUPPLIER_ISSUE', label: '建议供应商不合格 / 替换' },
  { code: 'NEED_MORE_INFO', label: '信息不全, 请补充后再提' },
  { code: 'TIMING_ISSUE', label: '时间不合适 (排程冲突 / 节假日)' },
  { code: 'OTHER', label: '其他原因 (请说明)' },
];

// 转 PO dialog state
const convertDialogVisible = ref(false);
const convertSupplierId = ref<string>('');

async function loadRequisition() {
  if (!factoryId.value || !requisitionId.value) return;
  loading.value = true;
  try {
    const res = await getRequisition(factoryId.value, requisitionId.value);
    if (res?.success && res.data) {
      requisition.value = res.data;
      // Pre-fill convert dialog supplier from first item's suggestedSupplierId
      const firstItem = res.data.requestedItems?.[0];
      const suggested =
        firstItem &&
        typeof firstItem === 'object' &&
        'suggestedSupplierId' in firstItem
          ? String((firstItem as { suggestedSupplierId?: string }).suggestedSupplierId || '')
          : '';
      if (suggested) convertSupplierId.value = suggested;
    }
  } catch (err: unknown) {
    const e = err as { status?: number; response?: { status?: number; data?: { code?: string; message?: string } }; code?: string; message?: string };
    const status = e?.status ?? e?.response?.status;
    const code = e?.code ?? e?.response?.data?.code;
    if (status === 404 || status === 403 || code === 'NOT_FOUND' || code === 'FORBIDDEN') {
      notFound.value = true;
      notFoundMessage.value = e?.message || e?.response?.data?.message || '请购单不存在或无权访问';
    }
    // axios interceptor already showed sticky toast
  } finally {
    loading.value = false;
  }
}

async function loadSuppliers() {
  if (!factoryId.value) return;
  try {
    const res = await get<{ content?: Supplier[] }>(
      `/${factoryId.value}/suppliers`,
      { params: { page: 1, size: 200 } },
    );
    suppliers.value = res?.data?.content ?? [];
  } catch {
    // interceptor handles
  }
}

onMounted(async () => {
  await Promise.all([loadRequisition(), loadSuppliers()]);
});

const lineCount = computed(() =>
  Array.isArray(requisition.value?.requestedItems)
    ? requisition.value!.requestedItems.length
    : 0,
);

async function handleSubmit() {
  if (!requisition.value || submitting.value) return;
  const r = requisition.value;
  try {
    await ElMessageBox.confirm(
      `确认${isRestaurant.value ? '提交采购计划' : '提交审批'}: ${r.requisitionNumber} (${lineCount.value} 行明细)?\n\n` +
      `${isRestaurant.value ? '报货人' : '请购人'} #${r.requesterId}` +
      `${r.expectedDate ? ` · ${expectedDateLabel.value} ${r.expectedDate}` : ''}`,
      isRestaurant.value ? '提交采购计划' : '提交审批',
      { type: 'warning' },
    );
  } catch {
    return;
  }
  submitting.value = true;
  try {
    const res = await submitRequisition(factoryId.value, r.id);
    if (res?.success) {
      ElMessage.success(isRestaurant.value ? '已提交给采购' : '已提交审批');
      await loadRequisition();
    }
  } catch (e) {
    console.error('[提交失败]', e);
  } finally {
    submitting.value = false;
  }
}

async function handleApprove() {
  if (!requisition.value || submitting.value) return;
  const r = requisition.value;
  try {
    await ElMessageBox.confirm(
      `确认审批通过请购单 ${r.requisitionNumber}?\n\n` +
      `明细 ${lineCount.value} 行 · ${isRestaurant.value ? '报货人' : '请购人'} #${r.requesterId}`,
      '审批通过',
      { type: 'success', confirmButtonText: '通过' },
    );
  } catch {
    return;
  }
  submitting.value = true;
  try {
    const res = await approveRequisition(factoryId.value, r.id);
    if (res?.success) {
      ElMessage.success('已审批通过');
      await loadRequisition();
    }
  } catch (e) {
    console.error('[审批失败]', e);
  } finally {
    submitting.value = false;
  }
}

function openRejectDialog() {
  rejectReasonCode.value = '';
  rejectReasonOther.value = '';
  rejectDialogVisible.value = true;
}

const finalRejectReason = computed(() => {
  if (!rejectReasonCode.value) return '';
  if (rejectReasonCode.value === 'OTHER') {
    return rejectReasonOther.value.trim();
  }
  const found = REJECT_REASONS.find((r) => r.code === rejectReasonCode.value);
  return found ? found.label : '';
});
const canConfirmReject = computed(() => {
  if (!rejectReasonCode.value) return false;
  if (rejectReasonCode.value === 'OTHER') {
    return rejectReasonOther.value.trim().length >= 5;
  }
  return true;
});

async function confirmReject() {
  if (!canConfirmReject.value || !requisition.value || submitting.value) return;
  submitting.value = true;
  try {
    const res = await rejectRequisition(
      factoryId.value,
      requisition.value.id,
      finalRejectReason.value,
    );
    if (res?.success) {
      ElMessage.success('已驳回');
      rejectDialogVisible.value = false;
      await loadRequisition();
    }
  } catch (e) {
    console.error('[驳回失败]', e);
  } finally {
    submitting.value = false;
  }
}

function openConvertDialog() {
  convertDialogVisible.value = true;
}

async function confirmConvert() {
  if (!convertSupplierId.value) {
    ElMessage.warning('请选择 PO 供应商');
    return;
  }
  if (!requisition.value || submitting.value) return;
  submitting.value = true;
  try {
    const res = await convertToPO(
      factoryId.value,
      requisition.value.id,
      convertSupplierId.value,
    );
    if (res?.success && res.data) {
      ElMessage.success(`已转为采购订单 ${res.data.orderNumber}`);
      convertDialogVisible.value = false;
      await loadRequisition();
    }
  } catch (e) {
    console.error('[转PO失败]', e);
  } finally {
    submitting.value = false;
  }
}

function goPO() {
  if (requisition.value?.convertedPoId) {
    router.push(`/procurement/orders/${requisition.value.convertedPoId}`);
  }
}

async function handleDelete() {
  if (!requisition.value || submitting.value) return;
  const r = requisition.value;
  // Fool-proof Rule 2: dialog 含单号 + 行数; Rule 5: backend 仅 DRAFT + requester 本人可删
  try {
    await ElMessageBox.confirm(
      `确认删除请购单 ${r.requisitionNumber} (${lineCount.value} 行明细)?\n\n此操作不可恢复.`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
      },
    );
  } catch {
    return;
  }
  submitting.value = true;
  try {
    const res = await deleteRequisition(factoryId.value, r.id);
    if (res?.success) {
      ElMessage.success('请购单已删除');
      router.back();
    }
  } catch (e) {
    // Interceptor 已展示 sticky toast (含 backend actionHint); dedupe fallback log
    if (e !== 'cancel') console.error('[删除失败]', e);
  } finally {
    submitting.value = false;
  }
}

function goBack() {
  router.back();
}
</script>

<template>
  <div class="page-wrapper">
    <el-page-header :icon="ArrowLeft" @back="goBack">
      <template #content>
        <span class="page-header-title">{{ detailTitle }}</span>
      </template>
    </el-page-header>

    <div v-if="notFound" class="empty-state">
      <el-empty :description="notFoundMessage || '请购单不存在'">
        <el-button @click="goBack">返回</el-button>
      </el-empty>
    </div>

    <el-card v-else v-loading="loading" class="content-card" shadow="never">
      <template v-if="requisition">
        <div class="header-row">
          <div class="header-left">
            <span class="req-number">{{ requisition.requisitionNumber }}</span>
            <el-tag
              :type="REQUISITION_STATUS_MAP[requisition.status as PurchaseRequisitionStatus]?.type || 'info'"
              size="default"
            >
              {{ REQUISITION_STATUS_MAP[requisition.status as PurchaseRequisitionStatus]?.text || requisition.status }}
            </el-tag>
          </div>
          <div class="header-actions">
            <el-button
              v-if="requisition.status === 'DRAFT' && canManageOwnDraft"
              type="warning"
              :loading="submitting"
              @click="handleSubmit"
            >
              {{ submitButtonText }}
            </el-button>
            <el-button
              v-if="canDelete"
              type="danger"
              :loading="submitting"
              @click="handleDelete"
            >
              删除
            </el-button>
            <el-button
              v-if="requisition.status === 'PENDING_APPROVAL' && canWrite"
              type="success"
              :loading="submitting"
              @click="handleApprove"
            >
              审批通过
            </el-button>
            <el-button
              v-if="requisition.status === 'PENDING_APPROVAL' && canWrite"
              type="danger"
              @click="openRejectDialog"
            >
              驳回
            </el-button>
            <el-button
              v-if="requisition.status === 'APPROVED' && canWrite"
              type="primary"
              :loading="submitting"
              @click="openConvertDialog"
            >
              转采购订单
            </el-button>
            <el-button
              v-if="requisition.convertedPoId"
              type="primary"
              plain
              @click="goPO"
            >
              查看关联 PO
            </el-button>
          </div>
        </div>

        <el-descriptions :column="2" border style="margin-top: 16px">
          <el-descriptions-item :label="requesterLabel">
            {{ requisition.sourceType === 'PRODUCTION_PLAN_SHORTAGE' && requisition.requesterId === 0 ? '系统缺料计算' : `#${requisition.requesterId}` }}
          </el-descriptions-item>
          <el-descriptions-item :label="requesterDeptLabel">
            {{ requisition.requesterDeptId || '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="expectedDateLabel">
            {{ requisition.expectedDate || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ requisition.createdAt ? String(requisition.createdAt).replace('T', ' ').slice(0, 19) : '-' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="requisition.sourceType" label="需求来源" :span="2">
            <el-tag v-if="requisition.sourceType === 'PRODUCTION_PLAN_SHORTAGE'" type="danger" size="small">生产缺料</el-tag>
            <span style="margin-left: 8px">{{ requisition.sourceNo || requisition.sourceId || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="审批人">
            {{ requisition.approverId ? '#' + requisition.approverId : '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="审批时间">
            {{ requisition.approvedAt ? String(requisition.approvedAt).replace('T', ' ').slice(0, 19) : '-' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="requisition.rejectionReason" label="驳回原因" :span="2">
            <span style="color: #f56c6c">{{ requisition.rejectionReason }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="requisition.convertedPoId" label="关联PO" :span="2">
            <el-button type="primary" link @click="goPO">
              {{ requisition.convertedPoId }}
            </el-button>
            <span v-if="requisition.convertedAt" style="margin-left: 12px; color: #909399">
              {{ String(requisition.convertedAt).replace('T', ' ').slice(0, 19) }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item v-if="requisition.reason" :label="reasonLabel" :span="2">
            {{ requisition.reason }}
          </el-descriptions-item>
          <el-descriptions-item v-if="requisition.remark" label="备注" :span="2">
            {{ requisition.remark }}
          </el-descriptions-item>
        </el-descriptions>

        <el-divider>{{ detailDividerText }} ({{ lineCount }} 行)</el-divider>

        <el-table
          :data="requisition.requestedItems || []"
          stripe
          border
          empty-text="无明细"
          style="width: 100%"
        >
          <el-table-column type="index" label="#" width="60" align="center" />
          <el-table-column label="物料" min-width="180">
            <template #default="{ row }">
              {{ row.materialName || row.materialTypeId || '-' }}
            </template>
          </el-table-column>
          <el-table-column label="需求/现有/缺口" min-width="190" align="right">
            <template #default="{ row }">
              <template v-if="row.requiredQuantity != null && row.availableQuantity != null">
                {{ row.requiredQuantity }} / {{ row.availableQuantity }} /
                <strong>{{ row.shortfallQuantity ?? row.quantity }}</strong>
              </template>
              <template v-else>{{ row.quantity ?? '-' }}</template>
            </template>
          </el-table-column>
          <el-table-column label="单位" width="80" align="center">
            <template #default="{ row }">{{ row.unit || '-' }}</template>
          </el-table-column>
          <el-table-column label="建议供应商 ID" width="200">
            <template #default="{ row }">
              {{ row.suggestedSupplierId || '-' }}
            </template>
          </el-table-column>
          <el-table-column label="行备注" min-width="180">
            <template #default="{ row }">{{ row.remark || '-' }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-card>

    <!-- 驳回 dialog -->
    <el-dialog
      v-model="rejectDialogVisible"
      title="驳回请购单"
      width="520px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <template v-if="requisition">
        <div class="reject-context">
          单号: <strong>{{ requisition.requisitionNumber }}</strong> ·
          请购人: <strong>#{{ requisition.requesterId }}</strong>
        </div>
      </template>
      <el-form label-width="100px" style="margin-top: 12px">
        <el-form-item label="驳回原因" required>
          <el-select
            v-model="rejectReasonCode"
            placeholder="请选择"
            style="width: 100%"
          >
            <el-option
              v-for="r in REJECT_REASONS"
              :key="r.code"
              :label="r.label"
              :value="r.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="rejectReasonCode === 'OTHER'" label="详细说明" required>
          <el-input
            v-model="rejectReasonOther"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="请填写至少 5 字的具体原因"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDialogVisible = false">取消</el-button>
        <el-button
          type="danger"
          :disabled="!canConfirmReject"
          :loading="submitting"
          @click="confirmReject"
        >
          确认驳回
        </el-button>
      </template>
    </el-dialog>

    <!-- 转 PO dialog -->
    <el-dialog
      v-model="convertDialogVisible"
      title="转采购订单"
      width="480px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <template v-if="requisition">
        <div class="reject-context">
          单号: <strong>{{ requisition.requisitionNumber }}</strong> ·
          {{ lineCount }} 行明细
        </div>
      </template>
      <el-form label-width="120px" style="margin-top: 12px">
        <el-form-item label="PO 供应商" required>
          <el-select
            v-model="convertSupplierId"
            placeholder="请选择 (整张 PO 走同一供应商)"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="s in suppliers"
              :key="s.id"
              :label="s.name"
              :value="s.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="hint" style="margin-top: 8px">
        提示: 重复点击不会创建多张 PO (后端幂等). 如果已转过会直接返回已有 PO.
      </div>
      <template #footer>
        <el-button @click="convertDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!convertSupplierId"
          :loading="submitting"
          @click="confirmConvert"
        >
          创建 PO
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px;
}
.page-header-title {
  font-size: 15px;
  font-weight: 600;
}
.empty-state {
  display: flex;
  justify-content: center;
  align-items: center;
  flex: 1;
}
.content-card {
  flex: 1;
}
.header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
  .req-number {
    font-size: 18px;
    font-weight: 600;
    color: #303133;
  }
}
.header-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.reject-context {
  background: #f5f7fa;
  padding: 12px;
  border-radius: 4px;
  font-size: 13px;
  color: #606266;
}
.hint {
  font-size: 12px;
  color: #909399;
}
</style>
