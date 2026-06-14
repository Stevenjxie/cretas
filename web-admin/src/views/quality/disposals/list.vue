<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Search, Refresh, Check, Close } from '@element-plus/icons-vue';
import { formatDateTimeCell } from '@/utils/tableFormatters';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('quality'));

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const searchForm = ref({
  keyword: '',
  status: ''
});

// 新建废弃对话框
const dialogVisible = ref(false);
const dialogLoading = ref(false);
const disposalForm = ref({
  batchId: '',
  disposalType: '',
  quantity: 0,
  reason: '',
  evidenceUrls: '',   // Gap-3: 证据照片 URL (逗号分隔); 随 notes 一起提交
  notes: ''
});
const actionLoading = ref(false);
const batches = ref<TableRow[]>([]);

onMounted(() => {
  loadData();
  loadBatches();
});

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/disposal-records`, {
      params: {
        page: pagination.value.page,
        size: pagination.value.size,
        keyword: searchForm.value.keyword || undefined,
        status: searchForm.value.status || undefined
      }
    });
    if (response.success && response.data) {
      tableData.value = response.data.content || [];
      pagination.value.total = response.data.totalElements || 0;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载数据失败');
    }
  } catch (error: any) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

async function loadBatches() {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/material-batches`, {
      params: { status: 'AVAILABLE', size: 100 }
    });
    if (response.success && response.data) {
      batches.value = response.data.content || response.data || [];
    }
  } catch (error: any) {
    console.error('加载批次列表失败:', error);
    if (!error?.actionHint) ElMessage.error('加载批次列表失败');
  }
}

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleRefresh() {
  searchForm.value = { keyword: '', status: '' };
  pagination.value.page = 1;
  loadData();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function handleSizeChange(size: number) {
  pagination.value.size = size;
  pagination.value.page = 1;
  loadData();
}

function handleCreate() {
  disposalForm.value = {
    batchId: '',
    disposalType: '',
    quantity: 0,
    reason: '',
    evidenceUrls: '',
    notes: ''
  };
  dialogVisible.value = true;
}

async function submitDisposal() {
  if (!disposalForm.value.batchId || !disposalForm.value.disposalType || !disposalForm.value.quantity || !disposalForm.value.reason) {
    ElMessage.warning('请填写完整信息');
    return;
  }

  // Issue #811 fix — rename FE form fields to match backend wire contract
  // (CreateDisposalRecordRequest DTO). FE form uses {batchId, quantity, reason}
  // but backend expects {materialBatchId, disposalQuantity, disposalReason}.
  // The batches dropdown is populated from /material-batches, so batchId is a
  // materialBatchId on the wire.
  //
  // Gap-3 防呆: evidenceUrls 和 notes 合并进 notes 字段 (DisposalRecord.notes = TEXT,
  // 无独立 evidenceImages 列; Flyway V20261024_15 预留但此处不需要 schema 变更).
  const evidencePart = disposalForm.value.evidenceUrls.trim();
  const notesPart = disposalForm.value.notes.trim();
  const mergedNotes = [
    evidencePart ? `[证据] ${evidencePart}` : '',
    notesPart
  ].filter(Boolean).join('\n');
  const payload = {
    materialBatchId: disposalForm.value.batchId,
    disposalType: disposalForm.value.disposalType,
    disposalQuantity: disposalForm.value.quantity,
    disposalReason: disposalForm.value.reason,
    notes: mergedNotes || undefined
  };

  dialogLoading.value = true;
  try {
    const response = await post(`/${factoryId.value}/disposal-records`, payload);
    if (response.success) {
      ElMessage.success('创建成功，等待审批');
      dialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(response.message || '创建失败');
    }
  } catch (error: any) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', error);
  } finally {
    dialogLoading.value = false;
  }
}

async function handleApprove(row: TableRow) {
  if (actionLoading.value) return;
  try {
    await ElMessageBox.confirm('确定批准此废弃申请?', '审批确认', { type: 'warning' });
  } catch { return; }
  actionLoading.value = true;
  try {
    const response = await put(`/${factoryId.value}/disposal-records/${row.id}/approve`, {
      approved: true
    });
    if (response.success) {
      ElMessage.success('已批准');
      loadData();
    } else {
      ElMessage.error(response.message || '操作失败');
    }
  } catch (e: any) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[操作失败]', e);
  } finally {
    actionLoading.value = false;
  }
}

async function handleReject(row: TableRow) {
  if (actionLoading.value) return;
  let reason: string;
  try {
    const { value } = await ElMessageBox.prompt('请输入拒绝原因', '拒绝申请', {
      inputPattern: /.+/,
      inputErrorMessage: '请输入拒绝原因'
    });
    reason = value;
  } catch { return; }
  actionLoading.value = true;
  try {
    const response = await put(`/${factoryId.value}/disposal-records/${row.id}/approve`, {
      approved: false,
      rejectReason: reason
    });
    if (response.success) {
      ElMessage.success('已拒绝');
      loadData();
    } else {
      ElMessage.error(response.message || '操作失败');
    }
  } catch (e: any) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[操作失败]', e);
  } finally {
    actionLoading.value = false;
  }
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    PENDING: 'warning',
    APPROVED: 'success',
    REJECTED: 'danger',
    COMPLETED: 'info'
  };
  return map[status?.toUpperCase()] || 'info';
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    PENDING: '待审批',
    APPROVED: '已批准',
    REJECTED: '已拒绝',
    COMPLETED: '已完成'
  };
  return map[status?.toUpperCase()] || status;
}

// ==================== View ====================
const viewDialogVisible = ref(false);
const viewRecord = ref<TableRow | null>(null);

function handleView(row: TableRow) {
  viewRecord.value = row;
  viewDialogVisible.value = true;
}

function getTypeText(type: string) {
  const map: Record<string, string> = {
    EXPIRED: '过期',
    DAMAGED: '损坏',
    QUALITY_ISSUE: '质量问题',
    OTHER: '其他'
  };
  return map[type?.toUpperCase()] || type;
}
</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">废弃处理</span>
            <span class="data-count">共 {{ pagination.total }} 条记录</span>
          </div>
          <div class="header-right">
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleCreate">
              新建申请
            </el-button>
          </div>
        </div>
      </template>

      <div class="search-bar">
        <el-input
          v-model="searchForm.keyword"
          placeholder="搜索记录编号/批次号"
          :prefix-icon="Search"
          clearable
          style="width: 280px"
          @keyup.enter="handleSearch"
        />
        <el-select v-model="searchForm.status" placeholder="全部状态" clearable style="width: 150px">
          <el-option label="待审批" value="PENDING" />
          <el-option label="已批准" value="APPROVED" />
          <el-option label="已拒绝" value="REJECTED" />
          <el-option label="已完成" value="COMPLETED" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
      </div>

      <el-table :data="tableData" v-loading="loading" empty-text="暂无数据" stripe border style="width: 100%">
        <el-table-column prop="recordNumber" label="记录编号" width="160" />
        <el-table-column prop="batchNumber" label="批次号" width="160" />
        <el-table-column prop="disposalType" label="废弃类型" width="120" align="center">
          <template #default="{ row }">
            {{ getTypeText(row.disposalType) }}
          </template>
        </el-table-column>
        <el-table-column prop="quantity" label="数量" width="100" align="right" />
        <el-table-column prop="reason" label="废弃原因" min-width="200" show-overflow-tooltip />
        <el-table-column prop="applicantName" label="申请人" width="100" />
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="申请时间" width="180" :formatter="formatDateTimeCell" />
        <el-table-column label="操作" width="180" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleView(row)">查看</el-button>
            <el-button
              v-if="canWrite && row.status === 'PENDING'"
              type="success"
              link
              size="small"
              :icon="Check"
              :disabled="actionLoading"
              @click="handleApprove(row)"
            >批准</el-button>
            <el-button
              v-if="canWrite && row.status === 'PENDING'"
              type="danger"
              link
              size="small"
              :icon="Close"
              :disabled="actionLoading"
              @click="handleReject(row)"
            >拒绝</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <!-- 查看详情 -->
    <el-dialog v-model="viewDialogVisible" title="废弃处理详情" width="500px" destroy-on-close>
      <el-descriptions v-if="viewRecord" :column="1" border>
        <el-descriptions-item label="记录编号">{{ viewRecord.recordNumber || '-' }}</el-descriptions-item>
        <el-descriptions-item label="批次号">{{ viewRecord.batchNumber || '-' }}</el-descriptions-item>
        <el-descriptions-item label="废弃类型">{{ getTypeText(viewRecord.disposalType) }}</el-descriptions-item>
        <el-descriptions-item label="数量">{{ viewRecord.quantity }}</el-descriptions-item>
        <el-descriptions-item label="废弃原因">{{ viewRecord.reason || '-' }}</el-descriptions-item>
        <el-descriptions-item label="申请人">{{ viewRecord.applicantName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="getStatusType(viewRecord.status)" size="small">{{ getStatusText(viewRecord.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="证据/备注">
          <template v-if="viewRecord.notes">
            <div style="white-space:pre-wrap;word-break:break-all">{{ viewRecord.notes }}</div>
          </template>
          <span v-else>-</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>

    <!-- 新建废弃申请对话框 -->
    <el-dialog v-model="dialogVisible" title="新建废弃申请" width="500px">
      <el-form :model="disposalForm" label-width="100px">
        <el-form-item label="批次" required>
          <el-select v-model="disposalForm.batchId" placeholder="选择批次" style="width: 100%">
            <el-option
              v-for="item in batches"
              :key="item.id"
              :label="`${item.batchNumber} - ${item.materialTypeName}`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="废弃类型" required>
          <el-select v-model="disposalForm.disposalType" placeholder="选择类型" style="width: 100%">
            <el-option label="过期" value="EXPIRED" />
            <el-option label="损坏" value="DAMAGED" />
            <el-option label="质量问题" value="QUALITY_ISSUE" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量" required>
          <el-input-number v-model="disposalForm.quantity" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="废弃原因" required>
          <el-input v-model="disposalForm.reason" type="textarea" :rows="2" placeholder="请输入废弃原因" />
        </el-form-item>
        <!-- Gap-3 防呆: 证据留存入口 (照片 URL 逗号分隔). 防呆 Rule 2 — 写操作必留证据. -->
        <el-form-item label="证据留存">
          <el-input
            v-model="disposalForm.evidenceUrls"
            type="textarea"
            :rows="3"
            placeholder="粘贴照片 URL（多张用逗号分隔）&#10;例: https://cdn.example.com/photo1.jpg, https://cdn.example.com/photo2.jpg"
          />
          <div style="font-size:12px;color:#909399;margin-top:4px">建议上传现场照片证明废弃情况，便于审批和留档</div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="disposalForm.notes" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="dialogLoading" @click="submitDisposal">提交申请</el-button>
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
    border-bottom: 1px solid var(--border-color-lighter, #ebeef5);
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
      color: var(--text-color-primary, #303133);
    }

    .data-count {
      font-size: 13px;
      color: var(--text-color-secondary, #909399);
    }
  }
}

.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.el-table {
  flex: 1;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid var(--border-color-lighter, #ebeef5);
  margin-top: 16px;
}
</style>
