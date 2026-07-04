<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Plus, Search, Refresh, View, Check, Close, Picture, Paperclip, CircleCloseFilled } from '@element-plus/icons-vue';
import type { TableRow } from '@/types/api';
import { warehouseTypeBadge } from '@/utils/warehouse';
// Rule 5 (fool-proof-design): 报损照片证据改真实拍照/文件上传, 不再要求仓管员手打 URL 粘贴到文本框
// (低技术素养用户根本无法生成一个图片 URL). WastageReport 在后端要求创建时 photoUrls 已非空
// (WastageReportServiceImpl#validatePhotos), 此时报损单实体还不存在, 无法用需要 entityId 的
// AttachmentUploadButton/uploadAndRegister (会注册 Attachment 记录挂在一个还不存在的 entityId 上)。
// 改用底层 getUploadUrl + 直传 OSS (与 uploadAndRegister 内部一致, 跳过 register 步骤), 拿到
// fileUrl 后推入 createForm.photoUrls — 提交时序不变 (仍是 photoUrls: string[] JSON.stringify)。
import { getUploadUrl } from '@/api/attachment';

// Safe helpers — Vue template compiler does not support optional chaining (?.)
// in attribute bindings; delegate to these plain functions instead.
function wBadgeColor(type: unknown): string | undefined {
  return warehouseTypeBadge(String(type || ''))?.color;
}
function wBadgeLabel(type: unknown): string | undefined {
  return warehouseTypeBadge(String(type || ''))?.label;
}

// ────────────────────────────────────────────────────────────────────────────
// Auth / permissions
// ────────────────────────────────────────────────────────────────────────────
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('warehouse'));

// ────────────────────────────────────────────────────────────────────────────
// Tab: WAREHOUSE / FACTORY
// ────────────────────────────────────────────────────────────────────────────
const activeTab = ref<'WAREHOUSE' | 'FACTORY'>('WAREHOUSE');

// ────────────────────────────────────────────────────────────────────────────
// Status / reason maps
// ────────────────────────────────────────────────────────────────────────────
const statusMap: Record<string, { label: string; type: string }> = {
  DRAFT: { label: '草稿', type: 'info' },
  PENDING_APPROVAL: { label: '待审批', type: 'warning' },
  APPROVED: { label: '已审批', type: 'success' },
  REJECTED: { label: '已驳回', type: 'danger' },
  APPLIED: { label: '已应用', type: 'success' },
};

const reasonOptions = [
  { value: 'EXPIRED', label: '过期/变质' },
  { value: 'DAMAGED', label: '物理损坏' },
  { value: 'CONTAMINATED', label: '污染/不合格' },
  { value: 'THEFT', label: '丢失/盗失' },
  { value: 'PRODUCTION_WASTE', label: '生产损耗' },
  { value: 'OTHER', label: '其他' },
];

function reasonLabel(val: string) {
  return reasonOptions.find((o) => o.value === val)?.label || val;
}

// ────────────────────────────────────────────────────────────────────────────
// List state
// ────────────────────────────────────────────────────────────────────────────
const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });
const statusFilter = ref('');

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params: Record<string, unknown> = {
      page: pagination.value.page - 1,  // backend is 0-based; pagination.page is 1-based
      size: pagination.value.size,
      trackType: activeTab.value,
    };
    if (statusFilter.value) params.status = statusFilter.value;
    const res = await get(`/${factoryId.value}/wastage-reports`, { params });
    if (res.success && res.data) {
      const d = res.data;
      tableData.value = d.content || d || [];
      pagination.value.total = d.totalElements ?? tableData.value.length;
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '加载失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function handleTabChange() {
  pagination.value.page = 1;
  statusFilter.value = '';
  loadData();
}

function handleFilterChange() {
  pagination.value.page = 1;
  loadData();
}

// ────────────────────────────────────────────────────────────────────────────
// Warehouses dropdown
// ────────────────────────────────────────────────────────────────────────────
const warehouses = ref<TableRow[]>([]);

async function loadWarehouses() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/factory/warehouses`);
    if (res.success && Array.isArray(res.data)) warehouses.value = res.data;
  } catch { /* silent */ }
}

function warehouseName(id: string) {
  const w = warehouses.value.find((x) => String(x.id) === String(id));
  return w ? String(w.name || id) : id;
}

// ────────────────────────────────────────────────────────────────────────────
// 批次下拉 (防呆: 选批次 → 报损数量绑定该批次可用量, 与报废处置一致)。
//
// ⚠️ 批次为必选, 不是选填: 后端 CreateWastageReportRequest.materialBatchId 是
// @NotBlank, DB 列 wastage_reports.material_batch_id 也是 NOT NULL (V20261010_24)。
// 报损服务 (WastageReportServiceImpl) 全程按单一批次扣减 (applyWastageToInventory),
// 没有跨批次 FEFO 物料级扣减路径。之前 UI 写 "留空=物料级报损" 但后端从未支持过 —
// 仓管员照着提示留空批次 100% 命中 400 "批次 ID 不能为空"。
// 修复: UI 诚实化为必选, 不再宣传后端不支持的路径 (fool-proof-design.md Rule 1/2)。
// 若未来要支持真物料级报损 (跨批次 FEFO 自动扣减), 需要: ①Flyway 迁移放开
// material_batch_id NOT NULL ②前端加物料类型选择器(当前无) ③后端仿
// BatchConsumptionServiceImpl.adjustConsumption 写跨批次 FEFO 扣减 —
// 属于新功能设计, 不在本次缺陷修复范围内。
// ────────────────────────────────────────────────────────────────────────────
const batches = ref<TableRow[]>([]);

async function loadBatches() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/material-batches`, {
      params: { status: 'AVAILABLE', size: 200 },
    });
    if (res.success && res.data) batches.value = res.data.content || res.data || [];
  } catch { /* silent — 加载失败时批次下拉为空, 提交仍会被 submitCreate 的必选校验挡住 */ }
}

// 按所选仓库过滤批次 (仓库未选则显全部)
const warehouseBatches = computed<TableRow[]>(() => {
  const wid = createForm.value.warehouseId;
  if (!wid) return batches.value;
  return batches.value.filter((b) => String(b.warehouseId) === String(wid));
});

// Bug 3 fix: 列表"批次"列之前直接渲染 row.materialBatchId (裸 GUID)。
// 复用 create dialog 已加载的 batches (AVAILABLE 批次) 解析显示名 "批次号 - 物料名"。
// 注意: 已耗尽/非 AVAILABLE 批次可能不在 batches 里 (loadBatches 只拉 AVAILABLE) —
// 找不到时诚实回退显示原始 ID, 而不是静默隐藏。
function batchLabel(id: unknown): string {
  const idStr = id == null ? '' : String(id);
  if (!idStr) return '—';
  const b = batches.value.find((x) => String(x.id) === idStr);
  if (!b) return idStr;
  const name = b.materialName || b.materialTypeName || '物料';
  return `${b.batchNumber || idStr} - ${name}`;
}

// 选中批次的可用量 (currentQuantity 优先); 批次必选, 未选时 null 仅用于禁止提交前的兜底态
const selectedWastageMax = computed<number | null>(() => {
  const b = batches.value.find((x) => String(x.id) === String(createForm.value.materialBatchId));
  if (!b) return null;
  const avail = (b.currentQuantity ?? b.remainingQuantity ?? b.receiptQuantity) as number | undefined;
  return avail != null ? Number(avail) : null;
});

// ────────────────────────────────────────────────────────────────────────────
// Create wastage report dialog
// ────────────────────────────────────────────────────────────────────────────
const createDialogVisible = ref(false);
const createLoading = ref(false);
const createForm = ref({
  warehouseId: '',
  materialBatchId: '',
  rawMaterialTypeId: '',
  wastageQty: null as number | null,
  wastageReason: '',
  reasonDetail: '',
  photoUrls: [] as string[],
  notes: '',
});

// 报损照片 — 真实拍照/文件上传 (Rule 5), 替代原 URL 粘贴文本框.
const photoUploading = ref(false);

function beforePhotoUpload(file: File): boolean {
  const maxSize = 20 * 1024 * 1024;
  if (file.size > maxSize) {
    ElMessage.error('照片超出上限 20MB');
    return false;
  }
  return true;
}

async function handlePhotoUpload(opts: { file: File }): Promise<void> {
  photoUploading.value = true;
  try {
    const urlResp = await getUploadUrl(opts.file.name, opts.file.type, factoryId.value);
    const { uploadUrl, fileUrl } = urlResp.data;
    const putRes = await fetch(uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': opts.file.type },
      body: opts.file,
    });
    if (!putRes.ok) throw new Error(`上传失败 HTTP ${putRes.status}`);
    createForm.value.photoUrls.push(fileUrl);
    ElMessage.success('照片上传成功');
  } catch (e) {
    ElMessage.error(String((e as Error).message || '照片上传失败'));
  } finally {
    photoUploading.value = false;
  }
}

function removePhoto(idx: number) {
  createForm.value.photoUrls.splice(idx, 1);
}

function openCreateDialog() {
  createForm.value = {
    warehouseId: '',
    materialBatchId: '',
    rawMaterialTypeId: '',
    wastageQty: null,
    wastageReason: '',
    reasonDetail: '',
    photoUrls: [],
    notes: '',
  };
  createDialogVisible.value = true;
}

async function submitCreate() {
  const form = createForm.value;
  if (!form.warehouseId) {
    ElMessage({ message: '请选择仓库', type: 'warning', duration: 3000 });
    return;
  }
  // 批次必选 (后端 @NotBlank + DB NOT NULL, 服务不支持跨批次物料级扣减 — 见上方批次下拉注释)
  if (!form.materialBatchId) {
    ElMessage({ message: '请选择批次（报损须精确到批次，用于扣减对应库存与核算成本）', type: 'warning', duration: 0, showClose: true });
    return;
  }
  if (!form.wastageQty || form.wastageQty <= 0) {
    ElMessage({ message: '请填写报损数量', type: 'warning', duration: 3000 });
    return;
  }
  // 防呆: 报损数量不可超过所选批次可用量 (与报废处置一致)
  if (selectedWastageMax.value != null && form.wastageQty > selectedWastageMax.value) {
    ElMessage({ message: `报损数量不能超过批次可用量 ${selectedWastageMax.value}`, type: 'warning', duration: 0, showClose: true });
    return;
  }
  if (!form.wastageReason) {
    ElMessage({ message: '请选择报损原因', type: 'warning', duration: 3000 });
    return;
  }
  if (form.photoUrls.length === 0) {
    ElMessage({ message: '至少需要拍照/上传1张照片证据', type: 'warning', duration: 3000 });
    return;
  }
  createLoading.value = true;
  try {
    const payload = {
      trackType: activeTab.value,
      warehouseId: form.warehouseId,
      materialBatchId: form.materialBatchId || undefined,
      rawMaterialTypeId: form.rawMaterialTypeId || undefined,
      wastageQty: form.wastageQty,
      wastageReason: form.wastageReason,
      reasonDetail: form.wastageReason === 'OTHER' ? form.reasonDetail : undefined,
      // P0 fix: backend CreateWastageReportRequest.photoUrls is a `String` field
      // (JSON array string, e.g. '["url1","url2"]' — see WastageReport entity /
      // WastageReportServiceImpl#validatePhotos which re-parses it as JSON text).
      // Sending a raw JS array here fails Jackson deserialization with
      // "字段类型不正确，期望 String" and the create ALWAYS 400s — 报损 was 100%
      // unusable via web-admin. Must JSON.stringify before sending.
      photoUrls: JSON.stringify(form.photoUrls),
      notes: form.notes || undefined,
    };
    const res = await post(`/${factoryId.value}/wastage-reports`, payload);
    if (res.success) {
      ElMessage({ message: '报损记录已创建（草稿）', type: 'success', duration: 3000 });
      createDialogVisible.value = false;
      loadData();
    } else {
      ElMessage({ message: res.message || '创建失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '创建失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    createLoading.value = false;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Submit for approval
// ────────────────────────────────────────────────────────────────────────────
const submitLoading = ref(false);

async function submitForApproval(row: TableRow) {
  submitLoading.value = true;
  try {
    const res = await post(`/${factoryId.value}/wastage-reports/${row.id}/submit`, {});
    if (res.success) {
      ElMessage({ message: '已提交审批', type: 'success', duration: 3000 });
      loadData();
    } else {
      ElMessage({ message: res.message || '提交失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '提交失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    submitLoading.value = false;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Approve dialog
// ────────────────────────────────────────────────────────────────────────────
const approveDialogVisible = ref(false);
const approveLoading = ref(false);
const approveRow = ref<TableRow | null>(null);
const approveNote = ref('');

function openApproveDialog(row: TableRow) {
  approveRow.value = row;
  approveNote.value = '';
  approveDialogVisible.value = true;
}

async function submitApprove() {
  if (!approveRow.value) return;
  approveLoading.value = true;
  try {
    const res = await post(`/${factoryId.value}/wastage-reports/${approveRow.value.id}/approve`, { notes: approveNote.value });
    if (res.success) {
      ElMessage({ message: '审批通过', type: 'success', duration: 3000 });
      approveDialogVisible.value = false;
      loadData();
    } else {
      ElMessage({ message: res.message || '审批失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '审批失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    approveLoading.value = false;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Reject dialog
// ────────────────────────────────────────────────────────────────────────────
const rejectDialogVisible = ref(false);
const rejectLoading = ref(false);
const rejectRow = ref<TableRow | null>(null);
const rejectReasonSelect = ref('');
const rejectReasonOther = ref('');

const rejectReasonOptions = [
  { value: 'PHOTO_MISSING', label: '照片证据不足' },
  { value: 'QTY_MISMATCH', label: '数量与实物不符' },
  { value: 'REASON_INVALID', label: '报损原因不充分' },
  { value: 'WRONG_BATCH', label: '批次信息有误' },
  { value: 'OTHER', label: '其他' },
];

function openRejectDialog(row: TableRow) {
  rejectRow.value = row;
  rejectReasonSelect.value = '';
  rejectReasonOther.value = '';
  rejectDialogVisible.value = true;
}

async function submitReject() {
  if (!rejectRow.value) return;
  const reason = rejectReasonSelect.value === 'OTHER'
    ? rejectReasonOther.value.trim()
    : rejectReasonOptions.find((o) => o.value === rejectReasonSelect.value)?.label || '';
  if (!reason) {
    ElMessage({ message: '请选择驳回原因', type: 'warning', duration: 3000 });
    return;
  }
  rejectLoading.value = true;
  try {
    const res = await post(`/${factoryId.value}/wastage-reports/${rejectRow.value.id}/reject`, { reason });
    if (res.success) {
      ElMessage({ message: '已驳回', type: 'success', duration: 3000 });
      rejectDialogVisible.value = false;
      loadData();
    } else {
      ElMessage({ message: res.message || '驳回失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '驳回失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    rejectLoading.value = false;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Photo viewer dialog
// ────────────────────────────────────────────────────────────────────────────
const photoViewerVisible = ref(false);
const viewingPhotos = ref<string[]>([]);

/**
 * WastageReportDTO.photoUrls is serialized as a JSON string by the backend
 * (e.g., '["url1","url2"]'). Normalize to string[] regardless of whether the
 * value arrives as a parsed array (unlikely) or a raw JSON string.
 */
function parsePhotoUrlsFromRow(raw: unknown): string[] {
  if (Array.isArray(raw)) return (raw as unknown[]).map(String);
  if (typeof raw === 'string' && raw.trim().startsWith('[')) {
    try { return JSON.parse(raw) as string[]; } catch { /* fall through */ }
  }
  if (typeof raw === 'string' && raw.length > 0) return [raw];
  return [];
}

function openPhotoViewer(row: TableRow) {
  const urls = parsePhotoUrlsFromRow(row.photoUrls);
  if (urls.length === 0) {
    ElMessage({ message: '该记录暂无照片', type: 'info', duration: 3000 });
    return;
  }
  viewingPhotos.value = urls;
  photoViewerVisible.value = true;
}

// ────────────────────────────────────────────────────────────────────────────
// Init
// ────────────────────────────────────────────────────────────────────────────
onMounted(async () => {
  await loadWarehouses();
  await loadBatches();
  await loadData();
});
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <h2>报损管理</h2>
      <div class="header-actions">
        <el-select
          v-model="statusFilter"
          placeholder="全部状态"
          clearable
          style="width: 140px; margin-right: 12px"
          @change="handleFilterChange"
        >
          <el-option v-for="(v, k) in statusMap" :key="k" :label="v.label" :value="k" />
        </el-select>
        <el-button :icon="Refresh" @click="loadData">刷新</el-button>
        <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreateDialog">
          新建报损
        </el-button>
      </div>
    </div>

    <!-- Cost note banner -->
    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="报损出库将计入损耗成本，审批通过并应用后自动扣减库存。"
      style="margin-bottom: 16px"
    />

    <!-- Dual-track tabs -->
    <el-tabs v-model="activeTab" type="border-card" style="margin-bottom: 16px" @tab-change="handleTabChange">
      <el-tab-pane label="仓库报损 → 财务审批" name="WAREHOUSE" />
      <el-tab-pane label="生产报损 → 厂长审批" name="FACTORY" />
    </el-tabs>

    <el-card>
      <el-table v-loading="loading" :data="tableData" row-key="id" stripe>
        <el-table-column label="报损单号" prop="reportNo" min-width="150" />
        <el-table-column label="仓库" min-width="110">
          <template #default="{ row }">{{ warehouseName(String(row.warehouseId)) }}</template>
        </el-table-column>
        <el-table-column label="批次" prop="materialBatchId" min-width="180">
          <template #default="{ row }">{{ batchLabel(row.materialBatchId) }}</template>
        </el-table-column>
        <el-table-column label="报损数量" prop="wastageQty" width="100" align="right" />
        <el-table-column label="报损原因" width="120">
          <template #default="{ row }">{{ reasonLabel(String(row.wastageReason)) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="(statusMap[String(row.status)]?.type as 'info' | 'warning' | 'success' | 'danger') || 'info'" size="small">
              {{ statusMap[String(row.status)]?.label || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <!-- fool-proof-design Rule 2: 后端已批量解析 submittedByName (userId→姓名), 不再显裸 userId. -->
        <el-table-column label="提交人" width="100">
          <template #default="{ row }">{{ row.submittedByName || row.submittedBy || '—' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createdAt" min-width="150">
          <template #default="{ row }">
            {{ row.createdAt ? String(row.createdAt).replace('T', ' ').slice(0, 16) : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <!-- 查看照片 -->
            <el-button size="small" :icon="Picture" @click="openPhotoViewer(row)">照片</el-button>
            <!-- 提交审批: DRAFT -->
            <el-button
              v-if="canWrite && row.status === 'DRAFT'"
              size="small"
              type="warning"
              :loading="submitLoading"
              @click="submitForApproval(row)"
            >提交审批</el-button>
            <!-- 审批: PENDING_APPROVAL -->
            <el-button
              v-if="canWrite && row.status === 'PENDING_APPROVAL'"
              size="small"
              type="success"
              :icon="Check"
              @click="openApproveDialog(row)"
            >审批</el-button>
            <el-button
              v-if="canWrite && row.status === 'PENDING_APPROVAL'"
              size="small"
              type="danger"
              :icon="Close"
              @click="openRejectDialog(row)"
            >驳回</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div style="display: flex; justify-content: flex-end; margin-top: 16px">
        <el-pagination
          v-model:current-page="pagination.page"
          :page-size="pagination.size"
          :total="pagination.total"
          layout="total, prev, pager, next"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>

    <!-- ──────────────── Create Dialog ──────────────── -->
    <el-dialog v-model="createDialogVisible" title="新建报损记录" width="560px">
      <el-alert
        type="warning"
        show-icon
        :closable="false"
        title="报损出库计入损耗成本。至少上传1张照片证据。"
        style="margin-bottom: 16px"
      />
      <el-form label-width="110px">
        <el-form-item label="报损类型">
          <el-radio-group v-model="activeTab">
            <el-radio-button value="WAREHOUSE">仓库报损</el-radio-button>
            <el-radio-button value="FACTORY">生产报损</el-radio-button>
          </el-radio-group>
          <el-text type="info" style="font-size: 12px; margin-left: 8px">
            {{ activeTab === 'WAREHOUSE' ? '财务审批' : '厂长审批' }}
          </el-text>
        </el-form-item>
        <el-form-item label="仓库" required>
          <el-select v-model="createForm.warehouseId" placeholder="选择仓库" style="width: 100%" @change="createForm.materialBatchId = ''">
            <el-option v-for="w in warehouses" :key="String(w.id)" :label="String(w.name)" :value="String(w.id)">
              <span>{{ w.name }}</span>
              <el-tag
                v-if="wBadgeLabel(w.type)"
                size="small"
                style="margin-left: 8px; font-size: 11px"
                :color="wBadgeColor(w.type)"
                effect="plain"
              >{{ wBadgeLabel(w.type) }}</el-tag>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="批次" required>
          <el-select
            v-model="createForm.materialBatchId"
            placeholder="请选择批次（报损须精确到批次）"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="b in warehouseBatches"
              :key="String(b.id)"
              :label="`${b.batchNumber} - ${b.materialName || b.materialTypeName || '物料'} (可用 ${b.currentQuantity ?? b.remainingQuantity ?? 0})`"
              :value="String(b.id)"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="报损数量" required>
          <el-input-number
            v-model="createForm.wastageQty"
            :min="0.001"
            :max="selectedWastageMax ?? undefined"
            :precision="3"
            style="width: 200px"
            placeholder="数量"
          />
          <div v-if="selectedWastageMax != null" style="font-size:12px;color:#909399;margin-top:4px">
            该批次可用量 {{ selectedWastageMax }}，报损数量不可超过此值
          </div>
        </el-form-item>
        <el-form-item label="报损原因" required>
          <el-select v-model="createForm.wastageReason" placeholder="选择原因" style="width: 100%">
            <el-option v-for="opt in reasonOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="createForm.wastageReason === 'OTHER'" label="补充说明" required>
          <el-input v-model="createForm.reasonDetail" type="textarea" :rows="2" placeholder="请填写具体原因" />
        </el-form-item>
        <el-form-item label="照片证据" required>
          <div style="width: 100%">
            <el-upload
              :show-file-list="false"
              :auto-upload="true"
              :before-upload="beforePhotoUpload"
              :http-request="handlePhotoUpload"
              :disabled="photoUploading"
              accept="image/*"
            >
              <el-button type="primary" :icon="Paperclip" :loading="photoUploading" :disabled="photoUploading">
                拍照 / 上传照片
              </el-button>
            </el-upload>
            <div v-if="createForm.photoUrls.length" style="display:flex;flex-wrap:wrap;gap:8px;margin-top:8px">
              <div v-for="(url, i) in createForm.photoUrls" :key="url + i" style="position:relative">
                <el-image
                  :src="url"
                  fit="cover"
                  style="width:72px;height:72px;border-radius:4px;border:1px solid #dcdfe6"
                  :preview-src-list="createForm.photoUrls"
                  :initial-index="i"
                />
                <el-icon
                  style="position:absolute;top:-6px;right:-6px;background:#fff;border-radius:50%;cursor:pointer;color:#f56c6c"
                  :size="18"
                  @click="removePhoto(i)"
                ><CircleCloseFilled /></el-icon>
              </div>
            </div>
            <el-text type="info" style="font-size: 12px;display:block;margin-top:4px">已上传 {{ createForm.photoUrls.length }} 张（至少1张）</el-text>
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.notes" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="createLoading" @click="submitCreate">保存草稿</el-button>
      </template>
    </el-dialog>

    <!-- ──────────────── Approve Dialog ──────────────── -->
    <el-dialog v-model="approveDialogVisible" title="审批报损申请" width="520px">
      <template v-if="approveRow">
        <el-descriptions :column="2" border style="margin-bottom: 16px">
          <el-descriptions-item label="报损单号">{{ approveRow.reportNo }}</el-descriptions-item>
          <el-descriptions-item label="审批路径">
            <el-tag type="info" size="small">
              {{ activeTab === 'WAREHOUSE' ? '仓库报损 → 财务审批' : '生产报损 → 厂长审批' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="仓库">{{ warehouseName(String(approveRow.warehouseId)) }}</el-descriptions-item>
          <el-descriptions-item label="报损数量">{{ approveRow.wastageQty }}</el-descriptions-item>
          <el-descriptions-item label="报损原因">{{ reasonLabel(String(approveRow.wastageReason)) }}</el-descriptions-item>
          <el-descriptions-item label="提交人">{{ approveRow.submittedByName || approveRow.submittedBy || '—' }}</el-descriptions-item>
        </el-descriptions>

        <!-- Photo thumbnails: photoUrls is a JSON string from the backend DTO -->
        <div v-if="parsePhotoUrlsFromRow(approveRow.photoUrls).length > 0" style="margin-bottom: 16px">
          <div style="font-size: 13px; color: #606266; margin-bottom: 6px">照片证据：</div>
          <div style="display: flex; gap: 8px; flex-wrap: wrap">
            <el-image
              v-for="(url, i) in parsePhotoUrlsFromRow(approveRow.photoUrls)"
              :key="i"
              :src="url"
              :preview-src-list="parsePhotoUrlsFromRow(approveRow.photoUrls)"
              fit="cover"
              style="width: 80px; height: 80px; border-radius: 4px; object-fit: cover"
            />
          </div>
        </div>

        <el-alert type="warning" show-icon :closable="false" title="审批通过后，报损数量将计入损耗成本并扣减对应库存。" style="margin-bottom: 12px" />

        <el-form label-width="80px">
          <el-form-item label="审批备注">
            <el-input v-model="approveNote" type="textarea" :rows="2" placeholder="可选" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="approveDialogVisible = false">取消</el-button>
        <el-button type="success" :loading="approveLoading" @click="submitApprove">确认审批通过</el-button>
      </template>
    </el-dialog>

    <!-- ──────────────── Reject Dialog ──────────────── -->
    <el-dialog v-model="rejectDialogVisible" title="驳回报损申请" width="480px">
      <template v-if="rejectRow">
        <el-descriptions :column="2" border style="margin-bottom: 16px">
          <el-descriptions-item label="报损单号">{{ rejectRow.reportNo }}</el-descriptions-item>
          <el-descriptions-item label="仓库">{{ warehouseName(String(rejectRow.warehouseId)) }}</el-descriptions-item>
          <el-descriptions-item label="报损数量">{{ rejectRow.wastageQty }}</el-descriptions-item>
          <el-descriptions-item label="原因">{{ reasonLabel(String(rejectRow.wastageReason)) }}</el-descriptions-item>
        </el-descriptions>
        <el-form label-width="100px">
          <el-form-item label="驳回原因" required>
            <el-select v-model="rejectReasonSelect" placeholder="选择驳回原因" style="width: 100%">
              <el-option v-for="opt in rejectReasonOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="rejectReasonSelect === 'OTHER'" label="补充说明" required>
            <el-input v-model="rejectReasonOther" type="textarea" :rows="2" placeholder="请填写具体原因" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="rejectDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="rejectLoading" @click="submitReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- ──────────────── Photo Viewer Dialog ──────────────── -->
    <el-dialog v-model="photoViewerVisible" title="照片证据查看" width="640px">
      <div style="display: flex; flex-wrap: wrap; gap: 12px; justify-content: center">
        <el-image
          v-for="(url, i) in viewingPhotos"
          :key="i"
          :src="url"
          :preview-src-list="viewingPhotos"
          fit="contain"
          style="width: 180px; height: 180px; border: 1px solid #e4e7ed; border-radius: 6px"
        >
          <template #error>
            <div style="display: flex; align-items: center; justify-content: center; height: 100%; color: #c0c4cc; font-size: 12px">
              图片加载失败
            </div>
          </template>
        </el-image>
      </div>
      <template #footer>
        <el-button @click="photoViewerVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-container {
  padding: 20px;
  background: #F4F6F9;
  min-height: 100%;
}
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.page-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #1B65A8;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.el-card {
  border-radius: 10px;
}
</style>
