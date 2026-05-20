<script setup lang="ts">
/**
 * 待审批请购单 — Sprint 6 W2-A.
 *
 * 列表 filter by status = PENDING_APPROVAL.
 * 给采购经理用 — 一键审批通过 / 驳回 (with reason dropdown).
 *
 * RBAC: 审批/驳回需 procurement:read_write (后端 @RequirePermission 强校验).
 *
 * Fool-proof Rule 3 (constrained inputs): 驳回原因走 dropdown + 其他时显 textarea
 * (不是纯 textarea 让用户瞎写无统计价值).
 */
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import {
  listRequisitions,
  approveRequisition,
  rejectRequisition,
  REQUISITION_STATUS_MAP,
  type PurchaseRequisition,
  type PurchaseRequisitionStatus,
} from '@/api/purchaseRequisition';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();

const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('procurement'));

const tableData = ref<PurchaseRequisition[]>([]);
const loading = ref(false);
const pagination = ref({ page: 1, size: 20, total: 0 });

// 驳回 dialog state (Fool-proof Rule 3 — constrained dropdown + 'other' textarea)
const rejectDialogVisible = ref(false);
const rejectingRow = ref<PurchaseRequisition | null>(null);
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

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await listRequisitions(factoryId.value, {
      status: 'PENDING_APPROVAL',
      page: pagination.value.page,
      size: pagination.value.size,
    });
    if (res?.success && res.data) {
      tableData.value = res.data.content ?? [];
      pagination.value.total = res.data.totalElements ?? 0;
    }
  } catch (e) {
    console.error('[待审批列表加载失败]', e);
  } finally {
    loading.value = false;
  }
}

onMounted(loadData);

function handlePageChange(p: number) {
  pagination.value.page = p;
  loadData();
}
function handleSizeChange(s: number) {
  pagination.value.size = s;
  pagination.value.page = 1;
  loadData();
}
function handleRefresh() {
  pagination.value.page = 1;
  loadData();
}
function goDetail(id: string) {
  router.push(`/procurement/requisitions/${id}`);
}

async function handleApprove(row: PurchaseRequisition) {
  const lines = Array.isArray(row.requestedItems) ? row.requestedItems.length : 0;
  // Fool-proof Rule 2 (context): 单号 + 行数 + 请购人 ID
  try {
    await ElMessageBox.confirm(
      `确认审批通过请购单 ${row.requisitionNumber}?\n\n` +
      `明细 ${lines} 行 · 请购人 #${row.requesterId}` +
      `${row.expectedDate ? ' · 期望交货 ' + row.expectedDate : ''}`,
      '审批通过',
      { type: 'success', confirmButtonText: '通过' },
    );
  } catch {
    return;
  }
  if (!factoryId.value) return;
  try {
    const res = await approveRequisition(factoryId.value, row.id);
    if (res?.success) {
      ElMessage.success(`已审批通过 ${row.requisitionNumber}`);
      await loadData();
    }
  } catch (e) {
    console.error('[审批失败]', e);
  }
}

function openRejectDialog(row: PurchaseRequisition) {
  rejectingRow.value = row;
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
  if (!canConfirmReject.value) return;
  if (!rejectingRow.value || !factoryId.value) return;
  const reason = finalRejectReason.value;
  try {
    const res = await rejectRequisition(
      factoryId.value,
      rejectingRow.value.id,
      reason,
    );
    if (res?.success) {
      ElMessage.success(`已驳回 ${rejectingRow.value.requisitionNumber}`);
      rejectDialogVisible.value = false;
      rejectingRow.value = null;
      await loadData();
    }
  } catch (e) {
    console.error('[驳回失败]', e);
  }
}
</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">待审批请购单</span>
            <span class="data-count">
              共 {{ pagination.total }} 条待审
              <span v-if="pagination.total > 0" class="urgent">· 待处理</span>
            </span>
          </div>
          <div class="header-right">
            <el-button :icon="Refresh" @click="handleRefresh">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert
        v-if="!canWrite"
        title="您没有审批权限"
        description="只有 procurement:read_write 角色 (采购经理) 可以审批 / 驳回. 请联系管理员开通权限."
        type="warning"
        :closable="false"
        style="margin-bottom: 16px"
      />

      <el-table
        v-loading="loading"
        :data="tableData"
        empty-text="当前无待审请购单"
        stripe
        border
        style="width: 100%"
      >
        <el-table-column prop="requisitionNumber" label="单号" width="200" />
        <el-table-column prop="requesterId" label="请购人" width="100" align="center">
          <template #default="{ row }">#{{ row.requesterId }}</template>
        </el-table-column>
        <el-table-column label="明细" min-width="240">
          <template #default="{ row }">
            <span v-if="!row.requestedItems || row.requestedItems.length === 0">
              -
            </span>
            <span v-else>
              {{ row.requestedItems.length }} 项 ·
              <span class="muted">
                {{ row.requestedItems
                  .slice(0, 2)
                  .map((it: { materialName?: string }) => it.materialName || '-')
                  .join(', ') }}{{ row.requestedItems.length > 2 ? ' …' : '' }}
              </span>
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="expectedDate" label="期望交货" width="120" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag
              :type="REQUISITION_STATUS_MAP[row.status as PurchaseRequisitionStatus]?.type || 'info'"
              size="small"
            >
              {{ REQUISITION_STATUS_MAP[row.status as PurchaseRequisitionStatus]?.text || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="提交时间" width="170">
          <template #default="{ row }">
            {{ row.createdAt ? String(row.createdAt).replace('T', ' ').slice(0, 16) : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="goDetail(row.id)">
              详情
            </el-button>
            <el-button
              v-if="canWrite"
              type="success"
              link
              size="small"
              @click="handleApprove(row)"
            >
              通过
            </el-button>
            <el-button
              v-if="canWrite"
              type="danger"
              link
              size="small"
              @click="openRejectDialog(row)"
            >
              驳回
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <!-- 驳回 dialog — Fool-proof Rule 3: dropdown + 'other' textarea -->
    <el-dialog
      v-model="rejectDialogVisible"
      title="驳回请购单"
      width="520px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <template v-if="rejectingRow">
        <div class="reject-context">
          单号: <strong>{{ rejectingRow.requisitionNumber }}</strong> ·
          请购人: <strong>#{{ rejectingRow.requesterId }}</strong>
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
        <el-form-item
          v-if="rejectReasonCode === 'OTHER'"
          label="详细说明"
          required
        >
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
          @click="confirmReject"
        >
          确认驳回
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
}
.page-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  :deep(.el-card__header) {
    padding: 16px 20px;
    border-bottom: 1px solid #ebeef5;
  }
  :deep(.el-card__body) {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 20px;
  }
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  .header-left {
    display: flex;
    align-items: baseline;
    gap: 12px;
    .page-title {
      font-size: 16px;
      font-weight: 600;
      color: #303133;
    }
    .data-count {
      font-size: 13px;
      color: #909399;
    }
    .urgent {
      color: #e6a23c;
      font-weight: 600;
    }
  }
}
.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid #ebeef5;
  margin-top: 16px;
}
.muted {
  color: #909399;
}
.reject-context {
  background: #f5f7fa;
  padding: 12px;
  border-radius: 4px;
  font-size: 13px;
  color: #606266;
}
</style>
