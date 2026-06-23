<script setup lang="ts">
/**
 * 采购退货管理 — D-BUG3 frontend.
 *
 * 客户红线 (catalog 行2399-2413): "退货跟钱有关, 必须先财务审批"。
 *
 * 退货单状态机 (ReturnOrderStatus, 六扇门 Tier0 #16 财审门):
 *   DRAFT → SUBMITTED → APPROVED → FINANCE_APPROVED → COMPLETED
 *   退货跟资金相关, 业务审批 (APPROVED) 后必须先财务审批 (FINANCE_APPROVED, 仅 finance:read_write)
 *   才能交仓管完成 / 出货。
 *
 * 采购退货单来源:
 *   - 采购入库异常 (procurement/exceptions/list.vue) 选 "退货(拒收,退回供应商)" = RETURN_OVER 决策
 *     → 后端 PurchaseExceptionServiceImpl.decideException 自动创建 PURCHASE_RETURN ReturnOrder (DRAFT)。
 *   - 本页负责后续 提交 → 业务审批 → 财务审批 → 完成 的状态推进。
 *
 * 复用 returnOrder.ts API client (后端 ReturnOrderController, returnType=PURCHASE_RETURN 过滤),
 * 不重建退货实体。销售退货另有 sales/returns/list.vue (returnType=SALES_RETURN)。
 */
import { ref, computed, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, Search } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import {
  listReturnOrders,
  submitReturnOrder,
  approveReturnOrder,
  financeApproveReturnOrder,
  financeRejectReturnOrder,
  rejectReturnOrder,
  completeReturnOrder,
  type ReturnOrder,
  type ReturnOrderStatus,
} from '@/api/returnOrder';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);
// 采购业务审批: procurement 写权限。财务审批: finance 写权限 (后端 finance:read_write 把关)。
const canProcurementWrite = computed(() => permissionStore.canWrite('procurement'));
const canFinanceWrite = computed(() => permissionStore.canWrite('finance'));

const loading = ref(false);
const submitting = ref(false);
const tableData = ref<ReturnOrder[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const filterStatus = ref<ReturnOrderStatus | ''>('');

const STATUS_OPTIONS: Array<{ value: ReturnOrderStatus | ''; label: string }> = [
  { value: '', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'SUBMITTED', label: '已提交' },
  { value: 'APPROVED', label: '已审批 (待财务)' },
  { value: 'FINANCE_APPROVED', label: '财务已审' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'COMPLETED', label: '已完成' },
];

function statusType(s: string) {
  switch (s) {
    case 'DRAFT': return 'info';
    case 'SUBMITTED': return 'warning';
    case 'APPROVED': return 'primary';
    case 'FINANCE_APPROVED': return 'success';
    case 'REJECTED': return 'danger';
    case 'COMPLETED': return 'success';
    default: return 'info';
  }
}

function statusLabel(s: string) {
  const o = STATUS_OPTIONS.find((x) => x.value === s);
  return o ? o.label : s || '-';
}

function formatAmount(v: number | null | undefined): string {
  if (v == null) return '—';
  return `¥${Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function formatDateTime(dt: string | null | undefined): string {
  if (!dt) return '—';
  return new Date(dt).toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  });
}

onMounted(() => loadData());

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const page = await listReturnOrders(factoryId.value, {
      returnType: 'PURCHASE_RETURN',
      status: filterStatus.value || undefined,
      page: pagination.value.page,
      size: pagination.value.size,
    });
    tableData.value = page.content;
    pagination.value.total = page.totalElements;
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      || (err as Error)?.message || '加载采购退货单失败';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}

function handlePageChange(p: number) { pagination.value.page = p; loadData(); }
function handleSizeChange(s: number) { pagination.value.size = s; pagination.value.page = 1; loadData(); }
function handleSearch() { pagination.value.page = 1; loadData(); }

// ─── 状态推进操作 (Fool-proof Rule 2: 确认框带单号 + context) ──────────────

async function runAction(
  row: ReturnOrder,
  actionLabel: string,
  confirmText: string,
  fn: (factoryId: string, id: string) => Promise<ReturnOrder>,
) {
  try {
    await ElMessageBox.confirm(confirmText, `${actionLabel} — ${row.returnNumber}`, {
      confirmButtonText: actionLabel,
      cancelButtonText: '取消',
      type: 'warning',
    });
  } catch {
    return; // 用户取消
  }
  submitting.value = true;
  try {
    await fn(factoryId.value, row.id);
    ElMessage({ message: `${actionLabel}成功`, type: 'success', duration: 3000 });
    loadData();
  } catch (err: unknown) {
    // 4-in-1 sticky error: 原样显示后端 message (含财审门 hint)
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      || (err as Error)?.message || `${actionLabel}失败`;
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    submitting.value = false;
  }
}

function doSubmit(row: ReturnOrder) {
  runAction(row, '提交', `确认提交采购退货单「${row.returnNumber}」进入审批流程？`, submitReturnOrder);
}
function doApprove(row: ReturnOrder) {
  runAction(row, '业务审批', `确认通过采购退货单「${row.returnNumber}」的业务审批？审批后还需财务审批方可完成。`, approveReturnOrder);
}
function doFinanceApprove(row: ReturnOrder) {
  runAction(
    row, '财务审批',
    `退货金额 ${formatAmount(row.totalAmount)}。退货跟资金相关，确认财务审批通过采购退货单「${row.returnNumber}」？`,
    financeApproveReturnOrder,
  );
}
function doFinanceReject(row: ReturnOrder) {
  runAction(row, '财务驳回', `确认财务驳回采购退货单「${row.returnNumber}」？`, financeRejectReturnOrder);
}
function doReject(row: ReturnOrder) {
  runAction(row, '驳回', `确认驳回采购退货单「${row.returnNumber}」？`, rejectReturnOrder);
}
function doComplete(row: ReturnOrder) {
  runAction(row, '完成', `确认完成采购退货单「${row.returnNumber}」并交仓管出货？`, completeReturnOrder);
}
</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">采购退货</span>
            <span class="data-count">共 {{ pagination.total }} 单</span>
          </div>
          <div class="header-right">
            <el-select v-model="filterStatus" placeholder="状态筛选" clearable style="width: 180px" @change="handleSearch">
              <el-option v-for="opt in STATUS_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
            <el-button :icon="Search" type="primary" @click="handleSearch">查询</el-button>
            <el-button :icon="Refresh" @click="loadData">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert
        type="info" show-icon :closable="false" style="margin-bottom: 12px"
        title="采购退货流程"
        description="采购退货单来自「采购管理 → 入库异常」选「退货(拒收,退回供应商)」自动生成。退货跟资金相关，需走 提交 → 业务审批 → 财务审批 → 完成，财务审批通过后才能交仓管出货。"
      />

      <el-table :data="tableData" v-loading="loading" empty-text="暂无采购退货单" stripe border style="width: 100%">
        <el-table-column prop="returnNumber" label="退货单号" width="200" />
        <el-table-column label="供应商" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.counterpartyId || '-' }}</template>
        </el-table-column>
        <el-table-column label="源采购单" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.sourceOrderId || '-' }}</template>
        </el-table-column>
        <el-table-column prop="returnDate" label="退货日期" width="120" />
        <el-table-column v-if="canViewPrice" label="退货金额" width="130" align="right">
          <template #default="{ row }">{{ formatAmount(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="140" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="退货原因" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.reason || '-' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" align="center" fixed="right">
          <template #default="{ row }">
            <!-- DRAFT → 提交 / 驳回(撤销) (采购写权限) -->
            <template v-if="row.status === 'DRAFT' && canProcurementWrite">
              <el-button link type="primary" size="small" :loading="submitting" @click="doSubmit(row)">提交</el-button>
            </template>
            <!-- SUBMITTED → 业务审批 / 驳回 (采购写权限) -->
            <template v-else-if="row.status === 'SUBMITTED' && canProcurementWrite">
              <el-button link type="primary" size="small" :loading="submitting" @click="doApprove(row)">业务审批</el-button>
              <el-button link type="danger" size="small" :loading="submitting" @click="doReject(row)">驳回</el-button>
            </template>
            <!-- APPROVED → 财务审批 / 财务驳回 (财务写权限; 客户红线: 退货必须先财务审批) -->
            <template v-else-if="row.status === 'APPROVED'">
              <template v-if="canFinanceWrite">
                <el-button link type="success" size="small" :loading="submitting" @click="doFinanceApprove(row)">财务审批</el-button>
                <el-button link type="danger" size="small" :loading="submitting" @click="doFinanceReject(row)">财务驳回</el-button>
              </template>
              <el-tooltip v-else content="退货跟资金相关，需财务角色 (finance) 审批" placement="top">
                <span class="text-muted">待财务审批</span>
              </el-tooltip>
            </template>
            <!-- FINANCE_APPROVED → 完成 (采购写权限) -->
            <template v-else-if="row.status === 'FINANCE_APPROVED' && canProcurementWrite">
              <el-button link type="primary" size="small" :loading="submitting" @click="doComplete(row)">完成出货</el-button>
            </template>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page" v-model:page-size="pagination.size"
          :total="pagination.total" :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="handlePageChange" @size-change="handleSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper { height: 100%; width: 100%; display: flex; flex-direction: column; }
.page-card { flex: 1; display: flex; flex-direction: column;
  :deep(.el-card__header) { padding: 16px 20px; border-bottom: 1px solid #ebeef5; }
  :deep(.el-card__body) { flex: 1; display: flex; flex-direction: column; padding: 20px; }
}
.card-header { display: flex; justify-content: space-between; align-items: center;
  .header-left { display: flex; align-items: baseline; gap: 12px;
    .page-title { font-size: 16px; font-weight: 600; color: #303133; }
    .data-count { font-size: 13px; color: #909399; }
  }
  .header-right { display: flex; gap: 8px; align-items: center; }
}
.pagination-wrapper { display: flex; justify-content: flex-end; padding-top: 16px; border-top: 1px solid #ebeef5; margin-top: 16px; }
.text-muted { color: #C0C4CC; }
</style>
