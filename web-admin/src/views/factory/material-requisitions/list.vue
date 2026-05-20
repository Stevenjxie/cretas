<script setup lang="ts">
/**
 * 工厂物料需求单管理 — V3 P0-5 (Round 2 Agent B 9/10 设计落地)
 *
 * 客户原话 (会议 3124-3252s):
 * "提交过后如果生产一个物料需求单, 根据 BOM 会生产一个物料需求单;
 *  给到仓库备料, 然后仓库把他的库存调过到工厂; 生产跑完以后...进行一个生产退料."
 *
 * 状态机:
 *   PENDING → 开始备料 → PICKING → 补填批次 → PICKING
 *     → 调拨到工厂 → TRANSFERRED → 工厂签收 → ISSUED → IN_USE
 *     → 关单 (自动算退料 = issued - consumed) → CLOSED
 *     或 → CANCELLED
 */
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh, View } from '@element-plus/icons-vue';
import { handleCatchError } from '@/utils/errorToast';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('production'));

const loading = ref(false);
const submitting = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const statusFilter = ref<string>('');

const statusMap: Record<string, { text: string; type: 'info' | 'warning' | 'success' | 'danger' | '' }> = {
  PENDING: { text: '待备料', type: 'warning' },
  PICKING: { text: '备料中', type: 'warning' },
  TRANSFERRED: { text: '已调拨', type: '' },
  ISSUED: { text: '已签收', type: 'success' },
  IN_USE: { text: '生产中', type: 'success' },
  CLOSED: { text: '已关单', type: 'info' },
  CANCELLED: { text: '已取消', type: 'danger' },
};

const createDialogVisible = ref(false);
const createForm = ref({ productionPlanId: '' });

const detailDialogVisible = ref(false);
const detailData = ref<TableRow | null>(null);

onMounted(() => { loadData(); });

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params: TableRow = {
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
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '加载物料需求单列表失败,请检查网络');
  } finally {
    loading.value = false;
  }
}

function handleStatusChange() { pagination.value.page = 1; loadData(); }
function handlePageChange(page: number) { pagination.value.page = page; loadData(); }

function openCreateDialog() {
  createForm.value = { productionPlanId: '' };
  createDialogVisible.value = true;
}

async function handleCreate() {
  if (submitting.value) return;
  if (!createForm.value.productionPlanId) return ElMessage.warning('生产计划 ID 必填');
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/material-requisitions/generate`, createForm.value);
    if (res.success) {
      const itemCount = (res.data?.items || []).length;
      ElMessage.success(`物料需求单已生成: ${res.data?.requisitionNo} (${itemCount} 行 BOM 展开)`);
      createDialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(res.message || '生成失败');
    }
  } catch {
    ElMessage.error('生成失败, 请检查生产计划 ID 或 BOM');
  } finally {
    submitting.value = false;
  }
}

async function openDetail(row: TableRow) {
  try {
    const res = await get(`/${factoryId.value}/material-requisitions/${row.id}`);
    if (res.success) {
      detailData.value = res.data;
      detailDialogVisible.value = true;
    }
  } catch (e) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', e);
  }
}

async function handleAction(row: TableRow, action: string, confirmMsg: string, successMsg: string) {
  if (submitting.value) return;
  try {
    await ElMessageBox.confirm(confirmMsg, '确认', { type: 'warning' });
    submitting.value = true;
    const res = await put(`/${factoryId.value}/material-requisitions/${row.id}/${action}`, {});
    if (res.success) {
      ElMessage.success(successMsg);
      loadData();
    } else {
      ElMessage.error(res.message || '操作失败');
    }
  } catch { /* cancelled */ }
  finally { submitting.value = false; }
}

const handleStartPicking = (row: TableRow) =>
  handleAction(row, 'start-picking', `开始备料 ${row.requisitionNo}?`, '已进入备料状态');
const handleTransfer = (row: TableRow) =>
  handleAction(row, 'transfer', `将 ${row.requisitionNo} 从物流仓调拨到工厂?`, '已调拨');
const handleReceive = (row: TableRow) =>
  handleAction(row, 'receive', `工厂签收 ${row.requisitionNo}?`, '已签收');
const handleClose = (row: TableRow) =>
  handleAction(row, 'close', `关单 ${row.requisitionNo}? 退料将按 issued - consumed 自动计算`, '已关单');

async function handleCancel(row: TableRow) {
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
    } else {
      ElMessage.error(res.message || '取消失败');
    }
  } catch { /* cancelled */ }
  finally { submitting.value = false; }
}
</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">工厂物料需求单</span>
            <el-tag type="info" size="small">共 {{ pagination.total }} 条</el-tag>
          </div>
          <div class="header-right">
            <el-select v-model="statusFilter" placeholder="按状态筛选" clearable style="width: 140px"
                       @change="handleStatusChange">
              <el-option label="待备料" value="PENDING" />
              <el-option label="备料中" value="PICKING" />
              <el-option label="已调拨" value="TRANSFERRED" />
              <el-option label="已签收" value="ISSUED" />
              <el-option label="生产中" value="IN_USE" />
              <el-option label="已关单" value="CLOSED" />
              <el-option label="已取消" value="CANCELLED" />
            </el-select>
            <el-button :icon="Refresh" @click="loadData">刷新</el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreateDialog">
              按生产计划生成
            </el-button>
          </div>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        title="客户原话: 根据生产计划 → BOM 自动生成备料单 → 总仓备料 → 调拨到工厂线边仓 → 生产 → 退料"
        description="双仓制: 原料在总仓 (WH-LOG, 持久库存), 生产时调拨到工厂线边仓 (WH-WKS, 当日清零), 关单自动算退料 = issued - consumed"
        style="margin-bottom: 16px"
      />

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
        <el-table-column label="物料行数" width="90" align="center">
          <template #default="{ row }">{{ (row.items || []).length }}</template>
        </el-table-column>
        <el-table-column prop="requiredDate" label="需料日期" width="110" />
        <el-table-column prop="requestedByName" label="申请人" width="100">
          <template #default="{ row }">{{ row.requestedByName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="160">
          <template #default="{ row }">{{ row.createdAt ? String(row.createdAt).replace('T', ' ').slice(0, 16) : '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="280" align="center" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" :icon="View" @click="openDetail(row)">详情</el-button>
            <el-button v-if="row.status === 'PENDING' && canWrite" type="primary" link size="small"
                       @click="handleStartPicking(row)">开始备料</el-button>
            <el-button v-if="row.status === 'PICKING' && canWrite" type="primary" link size="small"
                       @click="handleTransfer(row)">调拨</el-button>
            <el-button v-if="row.status === 'TRANSFERRED' && canWrite" type="success" link size="small"
                       @click="handleReceive(row)">签收</el-button>
            <el-button v-if="['ISSUED','IN_USE'].includes(row.status) && canWrite" type="warning" link size="small"
                       @click="handleClose(row)">关单</el-button>
            <el-button v-if="['PENDING','PICKING'].includes(row.status) && canWrite" type="danger" link size="small"
                       @click="handleCancel(row)">取消</el-button>
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

    <!-- ─── 生成对话框 ─── -->
    <el-dialog v-model="createDialogVisible" title="按生产计划生成物料需求单" width="520px" destroy-on-close>
      <el-form label-width="120px">
        <el-form-item label="生产计划 ID" required>
          <el-input v-model="createForm.productionPlanId" placeholder="如 PP20260407-0001 或 UUID" />
        </el-form-item>
        <el-alert type="info" :closable="false"
                  title="系统将按该计划对应产品的 BOM 自动展开"
                  description="required_qty = plannedQuantity × bomItem.actualQuantity (考虑 yieldRate)" />
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreate">生成</el-button>
      </template>
    </el-dialog>

    <!-- ─── 详情对话框 ─── -->
    <el-dialog v-model="detailDialogVisible" :title="`物料需求单详情 — ${detailData?.requisitionNo || ''}`"
               width="900px" destroy-on-close>
      <template v-if="detailData">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="单号">{{ detailData.requisitionNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusMap[String(detailData.status)]?.type || 'info'" size="small">
              {{ statusMap[String(detailData.status)]?.text || detailData.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="生产计划">{{ detailData.productionPlanId }}</el-descriptions-item>
          <el-descriptions-item label="需料日期">{{ detailData.requiredDate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="申请人">{{ detailData.requestedByName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ detailData.remarks || '-' }}</el-descriptions-item>
        </el-descriptions>

        <h4 style="margin: 16px 0 8px">物料明细 (BOM 展开)</h4>
        <el-table :data="(detailData.items as TableRow[]) || []" border size="small" max-height="360">
          <el-table-column prop="materialName" label="物料" min-width="140" show-overflow-tooltip />
          <el-table-column prop="materialCategory" label="类别" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.materialCategory === 'RAW' ? 'success' : row.materialCategory === 'PACKAGING' ? 'warning' : ''">
                {{ row.materialCategory === 'RAW' ? '原料' : row.materialCategory === 'AUXILIARY' ? '辅料' : row.materialCategory === 'PACKAGING' ? '包材' : row.materialCategory }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="requiredQty" label="需求量" width="90" align="right" />
          <el-table-column prop="pickedQty" label="已备" width="80" align="right" />
          <el-table-column prop="issuedQty" label="已发" width="80" align="right" />
          <el-table-column prop="consumedQty" label="已耗" width="80" align="right" />
          <el-table-column prop="returnedQty" label="已退" width="80" align="right" />
          <el-table-column prop="unit" label="单位" width="70" align="center" />
        </el-table>
      </template>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper { height: 100%; width: 100%; display: flex; flex-direction: column; }
.page-card { flex: 1; display: flex; flex-direction: column;
  :deep(.el-card__header) { padding: 16px 20px; border-bottom: 1px solid #ebeef5; }
  :deep(.el-card__body) { flex: 1; padding: 20px; overflow-y: auto; }
}
.card-header { display: flex; justify-content: space-between; align-items: center;
  .header-left { display: flex; align-items: center; gap: 12px;
    .page-title { font-size: 16px; font-weight: 600; color: #303133; }
  }
  .header-right { display: flex; gap: 8px; align-items: center; }
}
.pagination-wrapper { margin-top: 16px; display: flex; justify-content: flex-end; }
</style>
