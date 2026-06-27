<script setup lang="ts">
/**
 * Sprint4-H Q-RETURN-1 — 质检退回单列表 (PC).
 *
 * 状态机操作:
 *   - DRAFT       → 编辑 / 确认 / 撤销
 *   - CONFIRMED   → 发出 (填物流单号)
 *   - SHIPPED     → 只读
 *
 * 区分 T-RTA 客户销售退货 (/sales/returns) — 此处是上游退回.
 */
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, Plus } from '@element-plus/icons-vue';
import {
  listReturns,
  createReturn,
  confirmReturn,
  shipReturn,
  cancelReturn,
  RETURN_STATUS_LABELS,
  RETURN_TARGET_LABELS,
  type QualityReturnOrder,
  type QualityReturnStatus,
  type QualityReturnTargetType,
} from '@/api/qualityReturn';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const loading = ref(false);
const rows = ref<QualityReturnOrder[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });
const filterStatus = ref<QualityReturnStatus | ''>('');
const filterTarget = ref<QualityReturnTargetType | ''>('');

const showCreateDialog = ref(false);
const showShipDialog = ref(false);
const submitting = ref(false);
const currentOrder = ref<QualityReturnOrder | null>(null);

const createForm = ref({
  qualityInspectionId: '',
  targetType: 'SUPPLIER' as QualityReturnTargetType,
  targetId: '',
  targetName: '',
  materialId: '',
  quantity: 1,
  unit: '',
  reason: '',
});

const shipForm = ref({
  shippingTrackingNo: '',
});

const STATUS_OPTIONS: QualityReturnStatus[] = ['DRAFT', 'CONFIRMED', 'SHIPPED'];
const TARGET_OPTIONS: QualityReturnTargetType[] = ['SUPPLIER', 'SUBCONTRACT'];

async function load() {
  if (!factoryId.value) {
    ElMessage.warning('未登录或缺少工厂上下文');
    return;
  }
  loading.value = true;
  try {
    const res = await listReturns(factoryId.value, {
      status: filterStatus.value || undefined,
      targetType: filterTarget.value || undefined,
      page: pagination.value.page,
      size: pagination.value.size,
    });
    if (res.success && res.data) {
      rows.value = res.data.content;
      pagination.value.total = res.data.totalElements;
    }
  } finally {
    loading.value = false;
  }
}

function statusTagType(s: QualityReturnStatus): string {
  if (s === 'SHIPPED') return 'success';
  if (s === 'CONFIRMED') return 'warning';
  return 'info';
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  load();
}

function handleFilterChange() {
  pagination.value.page = 1;
  load();
}

function openCreateDialog() {
  createForm.value = {
    qualityInspectionId: '',
    targetType: 'SUPPLIER',
    targetId: '',
    targetName: '',
    materialId: '',
    quantity: 1,
    unit: '',
    reason: '',
  };
  showCreateDialog.value = true;
}

async function handleCreateSubmit() {
  if (!createForm.value.qualityInspectionId.trim()) {
    ElMessage.warning('关联质检记录 ID 必填');
    return;
  }
  if (!createForm.value.targetId.trim()) {
    ElMessage.warning('接收方 ID 必填');
    return;
  }
  if (createForm.value.quantity <= 0) {
    ElMessage.warning('退回数量必须 > 0');
    return;
  }
  submitting.value = true;
  try {
    const res = await createReturn(factoryId.value, {
      qualityInspectionId: createForm.value.qualityInspectionId.trim(),
      targetType: createForm.value.targetType,
      targetId: createForm.value.targetId.trim(),
      targetName: createForm.value.targetName.trim() || null,
      materialId: createForm.value.materialId.trim() || null,
      quantity: createForm.value.quantity,
      unit: createForm.value.unit.trim() || null,
      reason: createForm.value.reason.trim() || null,
    });
    if (res.success) {
      ElMessage.success(`已创建退回单 ${res.data?.returnNumber || ''}`);
      showCreateDialog.value = false;
      load();
    }
  } finally {
    submitting.value = false;
  }
}

async function handleConfirm(row: QualityReturnOrder) {
  try {
    await ElMessageBox.confirm(
      `确认退回单 ${row.returnNumber} (DRAFT → CONFIRMED)?`,
      '确认退回单',
      { type: 'warning' },
    );
  } catch {
    return;
  }
  const res = await confirmReturn(factoryId.value, row.id);
  if (res.success) {
    ElMessage.success('已确认');
    load();
  }
}

function openShipDialog(row: QualityReturnOrder) {
  currentOrder.value = row;
  shipForm.value.shippingTrackingNo = '';
  showShipDialog.value = true;
}

async function handleShipSubmit() {
  if (!currentOrder.value) return;
  if (!shipForm.value.shippingTrackingNo.trim()) {
    ElMessage.warning('物流单号必填');
    return;
  }
  submitting.value = true;
  try {
    const res = await shipReturn(
      factoryId.value,
      currentOrder.value.id,
      shipForm.value.shippingTrackingNo.trim(),
    );
    if (res.success) {
      ElMessage.success('已发出');
      showShipDialog.value = false;
      load();
    }
  } finally {
    submitting.value = false;
  }
}

async function handleCancel(row: QualityReturnOrder) {
  try {
    await ElMessageBox.confirm(
      `撤销退回单草稿 ${row.returnNumber}? 操作不可恢复.`,
      '撤销草稿',
      { type: 'warning', confirmButtonText: '确认撤销', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  const res = await cancelReturn(factoryId.value, row.id);
  if (res.success) {
    ElMessage.success('已撤销');
    load();
  }
}

onMounted(load);
</script>

<template>
  <div class="return-list">
    <div class="page-header">
      <div>
        <h2 class="title">质检退回单</h2>
        <p class="subtitle">退回供应商 / 委外加工厂 — 状态机 DRAFT → CONFIRMED → SHIPPED</p>
      </div>
      <div class="actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreateDialog">新建退回单</el-button>
      </div>
    </div>

    <div class="filter-row">
      <el-select v-model="filterStatus" placeholder="状态" clearable style="width: 160px" @change="handleFilterChange">
        <el-option v-for="s in STATUS_OPTIONS" :key="s" :label="RETURN_STATUS_LABELS[s]" :value="s" />
      </el-select>
      <el-select v-model="filterTarget" placeholder="退回类型" clearable style="width: 180px" @change="handleFilterChange">
        <el-option v-for="t in TARGET_OPTIONS" :key="t" :label="RETURN_TARGET_LABELS[t]" :value="t" />
      </el-select>
    </div>

    <el-table v-loading="loading" :data="rows" stripe empty-text="暂无退回单" style="width: 100%">
      <el-table-column prop="returnNumber" label="单号" min-width="160" />
      <el-table-column label="退回类型" min-width="130">
        <template #default="{ row }">
          <el-tag :type="row.targetType === 'SUPPLIER' ? '' : 'warning'">
            {{ RETURN_TARGET_LABELS[String(row.targetType) as QualityReturnTargetType] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="targetName" label="接收方" min-width="180">
        <template #default="{ row }">{{ row.targetName || row.targetId }}</template>
      </el-table-column>
      <el-table-column prop="quantity" label="数量" min-width="100" align="right" />
      <el-table-column prop="unit" label="单位" min-width="80" />
      <el-table-column prop="reason" label="原因" min-width="160" show-overflow-tooltip />
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)">{{ RETURN_STATUS_LABELS[String(row.status) as QualityReturnStatus] || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="shippingTrackingNo" label="物流单号" min-width="140">
        <template #default="{ row }">{{ row.shippingTrackingNo || '-' }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" min-width="160" />
      <el-table-column label="操作" width="220" align="center" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'DRAFT'" link type="primary" @click="handleConfirm(row)">确认</el-button>
          <el-button v-if="row.status === 'DRAFT'" link type="danger" @click="handleCancel(row)">撤销</el-button>
          <el-button v-if="row.status === 'CONFIRMED'" link type="success" @click="openShipDialog(row)">发出</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="pagination.total > 0"
      class="pagination"
      :current-page="pagination.page"
      :page-size="pagination.size"
      :total="pagination.total"
      layout="total, prev, pager, next, jumper"
      background
      @current-change="handlePageChange"
    />

    <!-- 新建退回单 dialog -->
    <el-dialog v-model="showCreateDialog" title="新建质检退回单" width="560px">
      <el-form label-position="top">
        <el-form-item label="关联质检记录 ID" required>
          <el-input v-model="createForm.qualityInspectionId" placeholder="quality_inspections.id" />
        </el-form-item>
        <el-form-item label="退回类型" required>
          <el-radio-group v-model="createForm.targetType">
            <el-radio value="SUPPLIER">退回供应商</el-radio>
            <el-radio value="SUBCONTRACT">退回委外</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="接收方 ID" required>
          <el-input v-model="createForm.targetId" placeholder="supplier id 或 subcontract id" />
        </el-form-item>
        <el-form-item label="接收方名称 (快照)">
          <el-input v-model="createForm.targetName" placeholder="可选" />
        </el-form-item>
        <el-form-item label="物料 ID (可选)">
          <el-input v-model="createForm.materialId" placeholder="raw_material_types.id" />
        </el-form-item>
        <el-form-item label="退回数量" required>
          <el-input-number v-model="createForm.quantity" :min="0.0001" :precision="4" :step="1" style="width: 200px" />
          <el-input v-model="createForm.unit" placeholder="单位 (kg / 件 / 箱)" style="width: 160px; margin-left: 12px" />
        </el-form-item>
        <el-form-item label="退回原因">
          <el-input v-model="createForm.reason" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreateSubmit">创建草稿</el-button>
      </template>
    </el-dialog>

    <!-- 发出 dialog -->
    <el-dialog v-model="showShipDialog" title="发出退回单" width="460px">
      <el-form label-position="top">
        <el-form-item label="物流单号 / 运单号" required>
          <el-input v-model="shipForm.shippingTrackingNo" placeholder="必填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showShipDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleShipSubmit">确认发出</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.return-list {
  padding: 20px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}
.page-header .title {
  font-size: 20px;
  font-weight: 600;
  margin: 0 0 4px;
  color: #303133;
}
.page-header .subtitle {
  font-size: 13px;
  color: #909399;
  margin: 0;
}
.filter-row {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}
.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
