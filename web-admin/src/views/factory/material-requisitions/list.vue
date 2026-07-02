<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus';
import { Plus, Refresh, View, Tickets } from '@element-plus/icons-vue';
import { get, post, put } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { handleCatchError } from '@/utils/errorToast';

interface RequisitionItem {
  id: string;
  materialTypeId?: string | null;
  materialName?: string | null;
  materialCategory?: string | null;
  requiredQty?: number | string | null;
  pickedQty?: number | string | null;
  issuedQty?: number | string | null;
  consumedQty?: number | string | null;
  wastageQty?: number | string | null;
  returnedQty?: number | string | null;
  unit?: string | null;
}

interface Requisition {
  id: string;
  requisitionNo: string;
  status: string;
  productionPlanId?: string | null;
  requiredDate?: string | null;
  requestedByName?: string | null;
  createdAt?: string | null;
  remarks?: string | null;
  items?: RequisitionItem[];
}

interface ClosePreviewRow extends RequisitionItem {
  wastageInput: number;
  previewReturnQty: number;
}

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('production'));

const loading = ref(false);
const submitting = ref(false);
const tableData = ref<Requisition[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const statusFilter = ref('');

const createDialogVisible = ref(false);
const createForm = ref({ productionPlanId: '' });

const detailDialogVisible = ref(false);
const detailData = ref<Requisition | null>(null);

const closeDialogVisible = ref(false);
const closeTarget = ref<Requisition | null>(null);
const closeRows = ref<ClosePreviewRow[]>([]);

interface ConfirmPickRow extends RequisitionItem {
  pickedInput: number;
}
const confirmDialogVisible = ref(false);
const confirmTarget = ref<Requisition | null>(null);
const confirmRows = ref<ConfirmPickRow[]>([]);

const statusMap: Record<string, { text: string; type: 'info' | 'warning' | 'success' | 'danger' | '' }> = {
  PENDING: { text: '待备料', type: 'warning' },
  PICKING: { text: '备料中', type: 'warning' },
  TRANSFERRED: { text: '已调拨', type: '' },
  ISSUED: { text: '已签收', type: 'success' },
  IN_USE: { text: '生产中', type: 'success' },
  CLOSED: { text: '已关单', type: 'info' },
  CANCELLED: { text: '已取消', type: 'danger' },
};

onMounted(loadData);

function toNumber(value: number | string | null | undefined): number {
  if (value === null || value === undefined || value === '') return 0;
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

function qty(value: number | string | null | undefined): string {
  return toNumber(value).toFixed(3).replace(/\.?0+$/, '');
}

function calcReturn(row: ClosePreviewRow): number {
  return toNumber(row.issuedQty) - toNumber(row.consumedQty) - toNumber(row.wastageInput);
}

function recalcRow(row: ClosePreviewRow) {
  row.previewReturnQty = calcReturn(row);
}

function formatDateTime(value?: string | null): string {
  return value ? String(value).replace('T', ' ').slice(0, 16) : '-';
}

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params: Record<string, unknown> = {
      page: pagination.value.page - 1,
      size: pagination.value.size,
    };
    if (statusFilter.value) params.status = statusFilter.value;
    const res = await get(`/${factoryId.value}/material-requisitions`, { params });
    if (res.success) {
      const data = res.data || {};
      tableData.value = data.content || [];
      pagination.value.total = data.totalElements || 0;
    }
  } catch (e) {
    handleCatchError(e, '加载物料需求单失败');
  } finally {
    loading.value = false;
  }
}

function handleStatusChange() {
  pagination.value.page = 1;
  loadData();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function openCreateDialog() {
  createForm.value = { productionPlanId: '' };
  createDialogVisible.value = true;
}

async function handleCreate() {
  if (submitting.value) return;
  if (!createForm.value.productionPlanId.trim()) {
    ElMessage.warning('生产计划 ID 必填');
    return;
  }
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/material-requisitions/generate`, createForm.value);
    if (res.success) {
      createDialogVisible.value = false;
      ElMessage.success('物料需求单已生成');
      loadData();
    }
  } finally {
    submitting.value = false;
  }
}

async function fetchDetail(row: Requisition): Promise<Requisition | null> {
  const res = await get(`/${factoryId.value}/material-requisitions/${row.id}`);
  return res.success ? res.data as Requisition : null;
}

async function openDetail(row: Requisition) {
  try {
    const data = await fetchDetail(row);
    if (data) {
      detailData.value = data;
      detailDialogVisible.value = true;
    }
  } catch (e) {
    handleCatchError(e, '加载物料需求单详情失败');
  }
}

async function handleAction(row: Requisition, action: string, confirmMsg: string, successMsg: string) {
  if (submitting.value) return;
  try {
    await ElMessageBox.confirm(confirmMsg, '确认', { type: 'warning' });
    submitting.value = true;
    const res = await put(`/${factoryId.value}/material-requisitions/${row.id}/${action}`, {});
    if (res.success) {
      ElMessage.success(successMsg);
      loadData();
    }
  } catch {
    // user cancelled or interceptor displayed backend error
  } finally {
    submitting.value = false;
  }
}

const handleStartPicking = (row: Requisition) =>
  handleAction(row, 'start-picking', `开始备料 ${row.requisitionNo}?`, '已进入备料状态');
const handleTransfer = (row: Requisition) =>
  handleAction(row, 'transfer', `调拨 ${row.requisitionNo} 到生产仓?`, '已调拨到生产仓');
const handleReceive = (row: Requisition) =>
  handleAction(row, 'receive', `签收 ${row.requisitionNo}?`, '已签收');

// 仓管确认领料 (confirm-picking): 逐物料录入实际拣货数量。防呆: 预填需求量 = "要收多少",
// 仓管只需核对确认 (Rule 1 预先显示边界 + Rule 2 上下文带单号/品名)。
async function openConfirmDialog(row: Requisition) {
  try {
    const data = await fetchDetail(row);
    if (!data) return;
    confirmTarget.value = data;
    confirmRows.value = (data.items || []).map((item) => {
      const picked = toNumber(item.pickedQty);
      return {
        ...item,
        // 预填: 已录过用已录值, 否则默认 = 需求量 (仓管默认按需领, 只需核对)
        pickedInput: picked > 0 ? picked : toNumber(item.requiredQty),
      };
    });
    confirmDialogVisible.value = true;
  } catch (e) {
    handleCatchError(e, '加载领料明细失败');
  }
}

async function submitConfirm() {
  if (!confirmTarget.value || submitting.value) return;
  const invalid = confirmRows.value.filter((row) => row.pickedInput < 0);
  if (invalid.length > 0) {
    ElMessage.warning('拣货数量不能为负数');
    return;
  }
  try {
    submitting.value = true;
    const res = await put(`/${factoryId.value}/material-requisitions/${confirmTarget.value.id}/confirm-picking`, {
      items: confirmRows.value.map((row) => ({ itemId: row.id, pickedQty: row.pickedInput ?? 0 })),
    });
    if (res.success) {
      ElMessage.success('已确认领料，可调拨到生产仓');
      confirmDialogVisible.value = false;
      loadData();
    }
  } catch {
    // interceptor displayed backend error
  } finally {
    submitting.value = false;
  }
}

async function openCloseDialog(row: Requisition) {
  try {
    const data = await fetchDetail(row);
    if (!data) return;
    closeTarget.value = data;
    closeRows.value = (data.items || []).map((item) => {
      const wastageInput = toNumber(item.wastageQty);
      return {
        ...item,
        wastageInput,
        previewReturnQty: toNumber(item.issuedQty) - toNumber(item.consumedQty) - wastageInput,
      };
    });
    closeDialogVisible.value = true;
  } catch (e) {
    handleCatchError(e, '加载退料预览失败');
  }
}

async function submitClose() {
  if (!closeTarget.value || submitting.value) return;
  const invalidRows = closeRows.value.filter((row) => row.previewReturnQty < 0);
  if (invalidRows.length > 0) {
    ElNotification({
      title: '退料数据异常',
      message: `${invalidRows.map((r) => r.materialName || r.materialTypeId || r.id).join('、')} 的实用量+损耗大于发出量。请修正损耗后再关单。`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
    return;
  }
  try {
    await ElMessageBox.confirm(`确认关单 ${closeTarget.value.requisitionNo} 并执行退料回库?`, '确认关单', { type: 'warning' });
    submitting.value = true;
    const res = await put(`/${factoryId.value}/material-requisitions/${closeTarget.value.id}/close`, {
      items: closeRows.value.map((row) => ({ itemId: row.id, wastageQty: row.wastageInput || '0' })),
    });
    if (res.success) {
      ElMessage.success('已关单并执行退料回库');
      closeDialogVisible.value = false;
      loadData();
    }
  } catch {
    // user cancelled or interceptor displayed backend error
  } finally {
    submitting.value = false;
  }
}

async function handleCancel(row: Requisition) {
  if (submitting.value) return;
  try {
    const { value: reason } = await ElMessageBox.prompt('填写取消原因', '取消物料需求单', {
      confirmButtonText: '确认取消',
      cancelButtonText: '返回',
      inputValidator: (v) => !!v || '取消原因必填',
    });
    submitting.value = true;
    const res = await put(`/${factoryId.value}/material-requisitions/${row.id}/cancel`, { reason });
    if (res.success) {
      ElMessage.success('已取消');
      loadData();
    }
  } catch {
    // user cancelled
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>物料需求单</h2>
        <span class="page-subtitle">领料、调拨、签收、关单退料</span>
      </div>
      <div class="header-actions">
        <el-select v-model="statusFilter" placeholder="状态" clearable class="status-filter" @change="handleStatusChange">
          <el-option label="待备料" value="PENDING" />
          <el-option label="备料中" value="PICKING" />
          <el-option label="已调拨" value="TRANSFERRED" />
          <el-option label="已签收" value="ISSUED" />
          <el-option label="生产中" value="IN_USE" />
          <el-option label="已关单" value="CLOSED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-button :icon="Tickets" @click="router.push('/production/material-returns')">退料记录</el-button>
        <el-button :icon="Refresh" @click="loadData">刷新</el-button>
        <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreateDialog">按计划生成</el-button>
      </div>
    </div>

    <el-card shadow="never" class="content-card">
      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column prop="requisitionNo" label="单号" min-width="150" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusMap[row.status]?.type || 'info'" size="small">
              {{ statusMap[row.status]?.text || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="productionPlanId" label="生产计划" min-width="150" show-overflow-tooltip />
        <el-table-column label="物料行" width="90" align="center">
          <template #default="{ row }">{{ (row.items || []).length }}</template>
        </el-table-column>
        <el-table-column prop="requiredDate" label="需料日期" width="120" />
        <el-table-column prop="requestedByName" label="申请人" width="110">
          <template #default="{ row }">{{ row.requestedByName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="300" align="center" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" :icon="View" @click="openDetail(row)">详情</el-button>
            <el-button v-if="row.status === 'PENDING' && canWrite" type="primary" link size="small" @click="handleStartPicking(row)">备料</el-button>
            <el-button v-if="row.status === 'PICKING' && canWrite" type="primary" link size="small" @click="openConfirmDialog(row)">确认领料</el-button>
            <el-button v-if="row.status === 'PICKING' && canWrite" type="primary" link size="small" @click="handleTransfer(row)">调拨</el-button>
            <el-button v-if="row.status === 'TRANSFERRED' && canWrite" type="success" link size="small" @click="handleReceive(row)">签收</el-button>
            <el-button v-if="['ISSUED','IN_USE'].includes(row.status) && canWrite" type="warning" link size="small" @click="openCloseDialog(row)">关单</el-button>
            <el-button v-if="['PENDING','PICKING'].includes(row.status) && canWrite" type="danger" link size="small" @click="handleCancel(row)">取消</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          :page-size="pagination.size"
          :total="pagination.total"
          layout="total, prev, pager, next"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>

    <el-dialog v-model="createDialogVisible" title="按生产计划生成物料需求单" width="520px" destroy-on-close>
      <el-form label-width="120px">
        <el-form-item label="生产计划 ID" required>
          <el-input v-model="createForm.productionPlanId" placeholder="生产计划 ID" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreate">生成</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="detailDialogVisible" :title="`物料需求单 ${detailData?.requisitionNo || ''}`" width="900px" destroy-on-close>
      <template v-if="detailData">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="单号">{{ detailData.requisitionNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusMap[String(detailData.status)]?.type || 'info'" size="small">
              {{ statusMap[String(detailData.status)]?.text || detailData.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="生产计划">{{ detailData.productionPlanId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="需料日期">{{ detailData.requiredDate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ detailData.remarks || '-' }}</el-descriptions-item>
        </el-descriptions>

        <el-table :data="detailData.items || []" border size="small" class="detail-table" max-height="360">
          <el-table-column prop="materialName" label="物料" min-width="150" show-overflow-tooltip />
          <el-table-column prop="materialCategory" label="类别" width="90" align="center" />
          <el-table-column prop="requiredQty" label="需求" width="90" align="right" />
          <el-table-column prop="issuedQty" label="发出" width="90" align="right" />
          <el-table-column prop="consumedQty" label="实用" width="90" align="right" />
          <el-table-column prop="wastageQty" label="损耗" width="90" align="right" />
          <el-table-column prop="returnedQty" label="退回" width="90" align="right" />
          <el-table-column prop="unit" label="单位" width="70" align="center" />
        </el-table>
      </template>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="confirmDialogVisible" :title="`确认领料 ${confirmTarget?.requisitionNo || ''}`" width="820px" destroy-on-close>
      <el-alert
        title="仓管确认实际拣货数量。默认已按需求量预填，核对无误直接确认；确认后可调拨到生产仓。"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      />
      <el-descriptions v-if="confirmTarget" :column="2" border size="small" style="margin-bottom: 12px">
        <el-descriptions-item label="单号">{{ confirmTarget.requisitionNo }}</el-descriptions-item>
        <el-descriptions-item label="生产计划">{{ confirmTarget.productionPlanId || '-' }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="confirmRows" border size="small">
        <el-table-column prop="materialName" label="物料" min-width="180" show-overflow-tooltip />
        <el-table-column label="需求量" width="110" align="right">
          <template #default="{ row }">{{ qty(row.requiredQty) }}</template>
        </el-table-column>
        <el-table-column label="实际拣货" width="180" align="right">
          <template #default="{ row }">
            <el-input-number
              v-model="row.pickedInput"
              :min="0"
              :precision="3"
              :step="0.001"
              controls-position="right"
              class="qty-input"
            />
          </template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="80" align="center" />
      </el-table>
      <template #footer>
        <el-button @click="confirmDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitConfirm">确认领料</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="closeDialogVisible" :title="`关单退料 ${closeTarget?.requisitionNo || ''}`" width="980px" destroy-on-close>
      <el-table :data="closeRows" border size="small">
        <el-table-column prop="materialName" label="物料" min-width="170" show-overflow-tooltip />
        <el-table-column label="发出" width="100" align="right">
          <template #default="{ row }">{{ qty(row.issuedQty) }}</template>
        </el-table-column>
        <el-table-column label="实用" width="100" align="right">
          <template #default="{ row }">{{ qty(row.consumedQty) }}</template>
        </el-table-column>
        <el-table-column label="损耗" width="160" align="right">
          <template #default="{ row }">
            <el-input-number
              v-model="row.wastageInput"
              :min="0"
              :precision="3"
              :step="0.001"
              controls-position="right"
              class="qty-input"
              @change="recalcRow(row)"
            />
          </template>
        </el-table-column>
        <el-table-column label="退回" width="110" align="right">
          <template #default="{ row }">
            <span :class="{ danger: row.previewReturnQty < 0 }">{{ qty(row.previewReturnQty) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="80" align="center" />
      </el-table>
      <template #footer>
        <el-button @click="closeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitClose">确认关单</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.page-container {
  padding: 20px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;

  h2 {
    margin: 0;
    color: #1a2332;
    font-size: 20px;
    font-weight: 600;
  }
}

.page-subtitle {
  display: block;
  margin-top: 4px;
  color: #7a8599;
  font-size: 13px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-filter {
  width: 140px;
}

.content-card {
  border-radius: 10px;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.detail-table {
  margin-top: 16px;
}

.qty-input {
  width: 132px;
}

.danger {
  color: #f56c6c;
  font-weight: 600;
}
</style>
