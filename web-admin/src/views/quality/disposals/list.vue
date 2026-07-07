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
// R12: targetType 区分报损对象 —— 'MATERIAL'(原料批次, 扣 MaterialBatch) / 'FINISHED'(成品批次, 扣 FinishedGoodsBatch)。
const disposalForm = ref({
  targetType: 'MATERIAL' as 'MATERIAL' | 'FINISHED',
  batchId: '',
  disposalType: '',
  quantity: 0,
  reason: '',
  evidenceImages: '',  // Gap-3: 证据照片 URL (逗号分隔), 独立列 evidence_images (V20261024_15)
  notes: ''
});
const actionLoading = ref(false);
const batches = ref<TableRow[]>([]);           // 原料批次 (MaterialBatch)
const fgBatches = ref<TableRow[]>([]);          // R12: 成品批次 (FinishedGoodsBatch)

// R12: 当前报损对象对应的批次下拉数据源。
const currentBatches = computed<TableRow[]>(() =>
  disposalForm.value.targetType === 'FINISHED' ? fgBatches.value : batches.value
);

// F-FP-2 防呆 + R12: 选中批次的可用量上限。
// 原料: MaterialBatch.currentQuantity/remainingQuantity/receiptQuantity。
// 成品: FinishedGoodsBatch.availableQuantity (= produced - shipped - reserved)。
const selectedBatchMax = computed<number | null>(() => {
  const b = currentBatches.value.find((x) => x.id === disposalForm.value.batchId);
  if (!b) return null;
  const avail = disposalForm.value.targetType === 'FINISHED'
    ? ((b.availableQuantity ?? b.producedQuantity) as number | undefined)
    : ((b.currentQuantity ?? b.remainingQuantity ?? b.receiptQuantity) as number | undefined);
  return typeof avail === 'number' ? avail : null;
});

// R12: 切换报损对象时清空已选批次, 避免跨数据源残留无效 batchId。
function handleTargetTypeChange() {
  disposalForm.value.batchId = '';
  disposalForm.value.quantity = 0;
}

onMounted(() => {
  loadData();
  loadBatches();
  loadFgBatches();
});

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/disposal-records`, {
      params: {
        // 后端 @RequestParam page 是 0-based; el-pagination 的 pagination.page 是 1-based → 转换 (修: 之前直传致首屏请求第2页, 列表空)
        page: Math.max(0, pagination.value.page - 1),
        size: pagination.value.size,
        type: searchForm.value.status || undefined
      }
    });
    if (response.success && response.data !== undefined) {
      // 后端 getDisposalRecords 返回扁平 data=[数组] + 同级 totalElements (非 Spring Page 信封), 兼容两种形态
      tableData.value = Array.isArray(response.data) ? response.data : (response.data.content || []);
      pagination.value.total = (response as any).totalElements ?? response.data?.totalElements ?? tableData.value.length;
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

// R12: 加载可售成品批次 (FinishedGoodsBatch) 供成品报损选择。
async function loadFgBatches() {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/sales/finished-goods`, {
      params: { status: 'AVAILABLE', size: 100 }
    });
    if (response.success && response.data) {
      fgBatches.value = response.data.content || response.data || [];
    }
  } catch (error: any) {
    console.error('加载成品批次列表失败:', error);
    if (!error?.actionHint) ElMessage.error('加载成品批次列表失败');
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
    targetType: 'MATERIAL',
    batchId: '',
    disposalType: '',
    quantity: 0,
    reason: '',
    evidenceImages: '',
    notes: ''
  };
  dialogVisible.value = true;
}

async function submitDisposal() {
  // 防呆: 字段级提示, 明确告诉用户缺哪个 (不再笼统 "请填写完整信息")
  if (!disposalForm.value.batchId) {
    ElMessage.warning('请选择批次');
    return;
  }
  if (!disposalForm.value.disposalType) {
    ElMessage.warning('请选择废弃类型');
    return;
  }
  if (!disposalForm.value.quantity) {
    ElMessage.warning('请输入废弃数量');
    return;
  }
  if (!disposalForm.value.reason) {
    ElMessage.warning('请输入废弃原因');
    return;
  }
  // F-FP-1 防呆: 报废必须留证据 (转录硬约束). 至少 1 个证据 URL。
  if (!disposalForm.value.evidenceImages.trim()) {
    ElMessage.warning('请上传/粘贴至少一张证据照片');
    return;
  }
  // F-FP-2 防呆: 报废数量不得超过批次可用量。
  if (selectedBatchMax.value != null && disposalForm.value.quantity > selectedBatchMax.value) {
    ElMessage.warning(`报废数量不能超过批次可用量 ${selectedBatchMax.value}`);
    return;
  }

  // Issue #811 fix — rename FE form fields to match backend wire contract
  // (CreateDisposalRecordRequest DTO). FE form uses {batchId, quantity, reason}
  // but backend expects {materialBatchId, disposalQuantity, disposalReason}.
  // evidenceImages goes to dedicated evidence_images column (V20261024_15).
  // R12: targetType 决定批次字段 —— 成品报损传 finishedGoodsBatchId (扣 FinishedGoodsBatch),
  // 原料报损传 materialBatchId (扣 MaterialBatch)。
  const payload: Record<string, unknown> = {
    disposalType: disposalForm.value.disposalType,
    disposalQuantity: disposalForm.value.quantity,
    disposalReason: disposalForm.value.reason,
    evidenceImages: disposalForm.value.evidenceImages.trim() || undefined,
    notes: disposalForm.value.notes.trim() || undefined
  };
  if (disposalForm.value.targetType === 'FINISHED') {
    payload.finishedGoodsBatchId = disposalForm.value.batchId;
  } else {
    payload.materialBatchId = disposalForm.value.batchId;
  }

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
  // 防呆 Rule 2: confirm 带品名/批次/数量/损失 context, 不再笼统 "确定批准?"
  const ctx = approvalContextHtml(row);
  try {
    await ElMessageBox.confirm(ctx, '审批确认 — 批准报废', {
      type: 'warning',
      dangerouslyUseHTMLString: true,
      confirmButtonText: '确认批准',
      cancelButtonText: '取消'
    });
  } catch { return; }
  actionLoading.value = true;
  try {
    // F-BUG-1/F-BUG-4: 审批人从后端 token 取, 前端无需传 approverId/approverName。
    const response = await put(`/${factoryId.value}/disposal-records/${row.id}/approve`, {});
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

// ==================== Reject (F-BUG-2 + 防呆 Rule 3) ====================
// 拒绝原因: 标准枚举 dropdown + "其他" 才填 textarea (替代纯自由文本, 便于统计)。
const rejectDialogVisible = ref(false);
const rejectRow = ref<TableRow | null>(null);
const rejectForm = ref({ reasonCode: '', detail: '' });
const REJECT_REASONS = [
  { value: '证据不足', label: '证据不足' },
  { value: '数量不符', label: '数量与实际不符' },
  { value: '可返工/可销售', label: '可返工或仍可销售, 无需报废' },
  { value: '需复核', label: '需现场复核' },
  { value: '其他', label: '其他' }
];

function approvalContextHtml(row: TableRow): string {
  const name = getBatchDisplayName(row);
  const batchNumber = getBatchNumber(row);
  const quantity = getDisposalQuantity(row);
  const batch = batchNumber !== '-' ? `批次 ${batchNumber}` : '';
  const qty = quantity !== '-' ? `数量 ${quantity}` : '';
  const type = getTypeText(row.disposalType as string);
  const loss = row.estimatedLoss != null ? `预估损失 ¥${row.estimatedLoss}` : '';
  const parts = [batch, qty, `类型 ${type}`, loss].filter(Boolean);
  return `<div style="line-height:1.8">
    <div><b>${name}</b></div>
    <div style="color:#606266">${parts.join(' · ')}</div>
    <div style="margin-top:6px;color:#e6a23c">批准后将扣减对应库存, 不可撤销。</div>
  </div>`;
}

function handleReject(row: TableRow) {
  if (actionLoading.value) return;
  rejectRow.value = row;
  rejectForm.value = { reasonCode: '', detail: '' };
  rejectDialogVisible.value = true;
}

async function submitReject() {
  if (!rejectForm.value.reasonCode) {
    ElMessage.warning('请选择拒绝原因');
    return;
  }
  // "其他" 必须补充说明
  if (rejectForm.value.reasonCode === '其他' && !rejectForm.value.detail.trim()) {
    ElMessage.warning('请补充拒绝原因说明');
    return;
  }
  const reason = rejectForm.value.reasonCode === '其他'
    ? rejectForm.value.detail.trim()
    : (rejectForm.value.detail.trim()
        ? `${rejectForm.value.reasonCode}: ${rejectForm.value.detail.trim()}`
        : rejectForm.value.reasonCode);

  actionLoading.value = true;
  try {
    // F-BUG-2: 走独立 reject 端点 (此前误走 approve → 反而批准+扣库存)。
    const response = await put(`/${factoryId.value}/disposal-records/${rejectRow.value?.id}/reject`, {
      reason
    });
    if (response.success) {
      ElMessage.success('已拒绝');
      rejectDialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(response.message || '操作失败');
    }
  } catch (e: any) {
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
    SCRAP: '报废',
    MATERIAL: '原料',
    OTHER: '其他'
  };
  return map[type?.toUpperCase()] || type;
}

function getRecordNumber(row: TableRow): string | number {
  return row.recordNumber ?? row.recordNo ?? row.id ?? '-';
}

function getBatchNumber(row: TableRow): string {
  return row.batchNumber
    || row.materialBatch?.batchNumber
    || row.finishedGoodsBatch?.batchNumber
    || row.productionBatch?.batchNumber
    || '-';
}

function getBatchDisplayName(row: TableRow): string {
  return row.materialName
    || row.materialTypeName
    || row.materialBatch?.materialName
    || row.materialBatch?.materialTypeName
    || row.materialBatch?.materialType?.name
    || row.finishedGoodsBatch?.productName
    || row.finishedGoodsBatch?.productTypeName
    || row.finishedGoodsBatch?.productType?.name
    || getBatchNumber(row)
    || '报废记录';
}

function getDisposalQuantity(row: TableRow): string | number {
  return row.quantity ?? row.disposalQuantity ?? '-';
}

function getDisposalReason(row: TableRow): string {
  return row.reason || row.disposalReason || '-';
}

function getApplicantName(row: TableRow): string {
  return row.applicantName
    || row.createdByName
    || row.createdByUser?.fullName
    || row.createdByUser?.name
    || row.createdByUser?.username
    || '-';
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
        <el-table-column label="记录编号" width="160">
          <template #default="{ row }">
            {{ getRecordNumber(row) }}
          </template>
        </el-table-column>
        <el-table-column label="批次号" width="180" show-overflow-tooltip>
          <template #default="{ row }">
            {{ getBatchNumber(row) }}
          </template>
        </el-table-column>
        <el-table-column prop="disposalType" label="废弃类型" width="120" align="center">
          <template #default="{ row }">
            {{ getTypeText(row.disposalType) }}
          </template>
        </el-table-column>
        <el-table-column label="数量" width="100" align="right">
          <template #default="{ row }">
            {{ getDisposalQuantity(row) }}
          </template>
        </el-table-column>
        <el-table-column label="废弃原因" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            {{ getDisposalReason(row) }}
          </template>
        </el-table-column>
        <el-table-column label="申请人" width="120">
          <template #default="{ row }">
            {{ getApplicantName(row) }}
          </template>
        </el-table-column>
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
        <el-descriptions-item label="记录编号">{{ getRecordNumber(viewRecord) }}</el-descriptions-item>
        <el-descriptions-item label="批次号">{{ getBatchNumber(viewRecord) }}</el-descriptions-item>
        <el-descriptions-item label="废弃类型">{{ getTypeText(viewRecord.disposalType) }}</el-descriptions-item>
        <el-descriptions-item label="数量">{{ getDisposalQuantity(viewRecord) }}</el-descriptions-item>
        <el-descriptions-item label="废弃原因">{{ getDisposalReason(viewRecord) }}</el-descriptions-item>
        <el-descriptions-item label="申请人">{{ getApplicantName(viewRecord) }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="getStatusType(viewRecord.status)" size="small">{{ getStatusText(viewRecord.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="证据图片">
          <template v-if="viewRecord.evidenceImages">
            <div style="word-break:break-all">{{ viewRecord.evidenceImages }}</div>
          </template>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="备注">
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
        <!-- R12 防呆 Rule 3: 报损对象用单选, 切换批次数据源 (原料 / 成品)。 -->
        <el-form-item label="报损对象" required>
          <el-radio-group v-model="disposalForm.targetType" @change="handleTargetTypeChange">
            <el-radio-button label="MATERIAL">原料批次</el-radio-button>
            <el-radio-button label="FINISHED">成品批次</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="批次" required>
          <el-select v-model="disposalForm.batchId" placeholder="选择批次" style="width: 100%">
            <!-- R12: 成品批次显批次号 + 产品名 + 可用量 (防呆 Rule 1 显边界); 原料批次显批次号 + 物料名。 -->
            <el-option
              v-for="item in currentBatches"
              :key="item.id"
              :label="disposalForm.targetType === 'FINISHED'
                ? `${item.batchNumber} - ${item.productName || item.productTypeName || '成品'} (可用 ${item.availableQuantity ?? item.producedQuantity ?? 0})`
                : `${item.batchNumber} - ${item.materialName || item.materialTypeName || '物料'}`"
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
          <el-input-number
            v-model="disposalForm.quantity"
            :min="0.01"
            :max="selectedBatchMax ?? undefined"
            :step="0.01"
            :precision="2"
            style="width: 100%"
          />
          <div v-if="selectedBatchMax != null" style="font-size:12px;color:#909399;margin-top:4px">
            该批次可用量 {{ selectedBatchMax }}，报废数量不可超过此值
          </div>
        </el-form-item>
        <el-form-item label="废弃原因" required>
          <el-input v-model="disposalForm.reason" type="textarea" :rows="2" placeholder="请输入废弃原因" />
        </el-form-item>
        <!-- Gap-3 + F-FP-1 防呆: 证据留存入口 (照片 URL 逗号分隔). 报废必须留证据 (转录硬约束).
             存入独立列 evidence_images (V20261024_15), 不再塞 notes 字段.
             TODO(F-FP-1): 当前为 URL 文本粘贴, 后续接入 el-upload 文件上传组件 (需先确认 OSS 上传端点). -->
        <el-form-item label="证据留存" required>
          <el-input
            v-model="disposalForm.evidenceImages"
            type="textarea"
            :rows="3"
            placeholder="粘贴照片 URL（多张用逗号分隔）&#10;例: https://cdn.example.com/photo1.jpg, https://cdn.example.com/photo2.jpg"
          />
          <div style="font-size:12px;color:#e6a23c;margin-top:4px">报废必须上传现场照片证明，便于审批和留档（必填）</div>
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

    <!-- 拒绝报废对话框 (F-BUG-2 + 防呆 Rule 3: 标准原因 dropdown) -->
    <el-dialog v-model="rejectDialogVisible" title="拒绝报废申请" width="480px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="报废记录">
          <span>{{ rejectRow ? getBatchDisplayName(rejectRow) : '-' }}
            <span v-if="rejectRow && getBatchNumber(rejectRow) !== '-'" style="color:#909399">
              ({{ getBatchNumber(rejectRow) }})
            </span>
          </span>
        </el-form-item>
        <el-form-item label="拒绝原因" required>
          <el-select v-model="rejectForm.reasonCode" placeholder="选择拒绝原因" style="width: 100%">
            <el-option v-for="r in REJECT_REASONS" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
        </el-form-item>
        <el-form-item :label="rejectForm.reasonCode === '其他' ? '说明' : '补充说明'">
          <el-input
            v-model="rejectForm.detail"
            type="textarea"
            :rows="2"
            :placeholder="rejectForm.reasonCode === '其他' ? '请填写具体拒绝原因（必填）' : '可选补充说明'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="actionLoading" @click="submitReject">确认拒绝</el-button>
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
