<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">损耗管理</span>
            <span class="data-count">共 {{ pagination.total }} 条</span>
          </div>
          <div class="header-right">
            <el-button v-if="canViewPrice" type="warning" plain @click="boardVisible = true">损耗责任看板</el-button>
 <el-button type="info" plain @click="handleAiAnalyze"> AI 分析</el-button>
            <el-button :icon="Download" @click="handleExport">导出</el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleCreate">新建损耗记录</el-button>
          </div>
        </div>
      </template>

      <!-- 统计卡片 -->
      <el-row :gutter="16" class="stat-row" v-if="statsLoaded">
        <el-col v-if="canViewPrice" :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">损耗总额</span>
            <span class="stat-value danger">¥{{ (statsData.totalEstimatedCost ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}</span>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">损耗记录</span>
            <span class="stat-value">{{ statsData.totalCount ?? pagination.total }} 条</span>
          </div>
        </el-col>
        <el-col v-if="canViewPrice && statsData.byType?.length" :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">最高损耗类型</span>
            <span class="stat-value warning">{{ wastageTypeText(topType.type) }} (¥{{ topType.totalCost?.toLocaleString() ?? 0 }})</span>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6" v-if="statsData.byType?.length">
          <div class="stat-item">
            <span class="stat-label">损耗类型数</span>
            <span class="stat-value">{{ statsData.byType.length }} 类</span>
          </div>
        </el-col>
      </el-row>

      <!-- Apr 24 P1 analytics strip: trend + ranking from current table rows -->
      <AnalyticsStrip
        v-if="canViewPrice"
        :rows="tableData"
        date-field="wastageDate"
        value-field="estimatedCost"
        category-field="type"
        :category-name-map="wastageTypeMap"
        trend-title="损耗金额趋势"
        ranking-title="按损耗类型排行"
        value-unit="元"
        :is-currency="true"
        :top-n="5"
      />

      <div class="search-bar" role="search" aria-label="损耗记录筛选">
        <el-date-picker v-model="filterDateRange" type="daterange" range-separator="至" start-placeholder="开始日期"
          end-placeholder="结束日期" value-format="YYYY-MM-DD" clearable style="width: 240px" @change="handleSearch" />
        <el-select v-model="filterStatus" placeholder="状态" clearable style="width: 130px" @change="handleSearch">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已提交" value="SUBMITTED" />
          <el-option label="已审批" value="APPROVED" />
          <el-option label="已驳回" value="REJECTED" />
        </el-select>
        <el-select v-model="filterType" placeholder="损耗类型" clearable style="width: 130px" @change="handleSearch">
          <el-option label="过期" value="EXPIRED" />
          <el-option label="破损" value="DAMAGED" />
          <el-option label="变质" value="SPOILED" />
          <el-option label="加工损耗" value="PROCESSING" />
          <el-option label="其他" value="OTHER" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
      </div>

      <el-table :data="tableData" row-key="id" v-loading="loading" stripe border style="width: 100%" empty-text="暂无损耗记录，点击「新建损耗记录」添加" aria-label="损耗记录列表">
        <el-table-column prop="wastageNumber" label="损耗单号" width="180" show-overflow-tooltip :formatter="emptyCell" />
        <el-table-column prop="wastageDate" label="损耗日期" width="120" :formatter="formatDateCell" />
        <el-table-column label="损耗类型" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="wastageTypeTag(row.type)" size="small">{{ wastageTypeText(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="rawMaterialTypeId" label="食材" width="160" show-overflow-tooltip class-name="hidden-sm-col">
          <template #default="{ row }">{{ materialNameMap[row.rawMaterialTypeId] || row.rawMaterialTypeId }}</template>
        </el-table-column>
        <el-table-column label="数量" width="110" align="right">
          <template #default="{ row }">{{ row.quantity }} {{ row.unit }}</template>
        </el-table-column>
        <el-table-column label="档口" width="90" align="center" class-name="hidden-sm-col">
          <template #default="{ row }">
            <el-tag v-if="row.sectionCode" size="small" effect="plain">{{ sectionLabel(row.sectionCode) }}</el-tag>
            <span v-else class="muted-cell">-</span>
          </template>
        </el-table-column>
        <el-table-column label="责任人" width="110" show-overflow-tooltip class-name="hidden-sm-col">
          <template #default="{ row }">{{ row.operatorId ? (operatorNameMap[row.operatorId] || ('用户#' + row.operatorId)) : '-' }}</template>
        </el-table-column>
        <el-table-column v-if="canViewPrice" label="估算损失" width="110" align="right">
          <template #default="{ row }">
            {{ formatAmount(row.estimatedCost) }}
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" show-overflow-tooltip :formatter="emptyCell" />
        <el-table-column label="操作" width="200" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="showDetail(row)">查看</el-button>
            <el-button v-if="canWrite && (row.status === 'DRAFT' || row.status === 'REJECTED')" type="success" link size="small" :disabled="submitting" @click="handleSubmit(row)">提交</el-button>
            <el-button v-if="canWrite && row.status === 'SUBMITTED'" type="success" link size="small" :disabled="submitting" @click="handleApprove(row)">审批</el-button>
            <el-button v-if="canWrite && row.status === 'SUBMITTED'" type="danger" link size="small" :disabled="submitting" @click="handleReject(row)">驳回</el-button>
          </template>
        </el-table-column>
      </el-table>

      <TableFooter
        :stats="footerSummary?.stats ?? []"
        :loading="footerLoading"
        :show-export="false"
        @ai-analyze="() => ElMessage.info({ message: `AI 分析 (待接 SmartBI): 分析当前损耗记录${formatSummaryForAI(footerSummary)}`, duration: 8000, showClose: true })"
      />

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="loadData"
          @size-change="() => { pagination.page = 1; loadData(); }"
        />
      </div>
    </el-card>

    <!-- 新建损耗对话框 -->
    <el-dialog v-model="dialogVisible" title="新建损耗记录" width="500px" :close-on-click-modal="false" destroy-on-close>
      <el-form ref="formRef" :model="dialogForm" :rules="formRules" label-width="100px">
        <el-form-item label="损耗类型" prop="type">
          <el-select v-model="dialogForm.type" style="width: 100%">
            <el-option label="过期报废" value="EXPIRED" />
            <el-option label="物理破损" value="DAMAGED" />
            <el-option label="变质" value="SPOILED" />
            <el-option label="加工损耗" value="PROCESSING" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="食材" prop="rawMaterialTypeId">
          <el-select v-model="dialogForm.rawMaterialTypeId" filterable placeholder="选择食材" style="width: 100%">
            <el-option v-for="mt in materialTypes" :key="mt.id" :label="mt.name" :value="mt.id" />
          </el-select>
          <UpstreamMissingHint v-if="materialTypes.length === 0" description="本店暂无食材，无法报损" target-module="warehouse" require-write action-text="去创建物料类型" contact-text="请联系仓库或管理员先创建物料类型" @action="goCreate('/warehouse/material-types')" />
        </el-form-item>
        <el-form-item label="损耗数量" prop="quantity">
          <el-input-number v-model="dialogForm.quantity" :precision="4" :step="0.5" :min="0" />
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="dialogForm.unit" placeholder="kg / L" style="width: 120px" />
        </el-form-item>
        <!-- Wave2 损耗按人/档口责任制：dropdown 选择（防呆 Rule 3 — 约束选择非自由文本） -->
        <el-form-item label="档口">
          <el-select v-model="dialogForm.sectionCode" clearable placeholder="选择档口（可选）" style="width: 100%">
            <el-option v-for="s in sectionOptions" :key="s.code" :label="s.label" :value="s.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="责任人">
          <el-select v-model="dialogForm.operatorId" filterable clearable placeholder="选择责任人（可选）" style="width: 100%">
            <el-option v-for="u in operatorOptions" :key="u.id" :label="u.label" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="canViewPrice" label="估算损失">
          <el-input-number v-model="dialogForm.estimatedCost" :precision="2" :step="10" :min="0" />
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="dialogForm.reason" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="submitting" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreateForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- Wave2 损耗责任看板 -->
    <AccountabilityBoard v-model="boardVisible" :factory-id="factoryId" />

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="损耗记录详情" size="400px">
      <el-descriptions :column="1" border v-if="detailData">
        <el-descriptions-item label="单号">{{ detailData.wastageNumber }}</el-descriptions-item>
        <el-descriptions-item label="日期">{{ formatDate(detailData.wastageDate) }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ wastageTypeText(detailData.type) }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusText(detailData.status) }}</el-descriptions-item>
        <el-descriptions-item label="食材">{{ materialNameMap[detailData.rawMaterialTypeId] || detailData.rawMaterialTypeId }}</el-descriptions-item>
        <el-descriptions-item label="数量">{{ detailData.quantity }} {{ detailData.unit }}</el-descriptions-item>
        <el-descriptions-item label="档口">{{ detailData.sectionCode ? sectionLabel(detailData.sectionCode) : '-' }}</el-descriptions-item>
        <el-descriptions-item label="责任人">{{ detailData.operatorId ? (operatorNameMap[detailData.operatorId] || ('用户#' + detailData.operatorId)) : '-' }}</el-descriptions-item>
        <el-descriptions-item v-if="canViewPrice" label="估算损失">{{ formatAmount(detailData.estimatedCost) }}</el-descriptions-item>
        <el-descriptions-item label="原因">{{ detailData.reason || '-' }}</el-descriptions-item>
        <el-descriptions-item label="备注">{{ detailData.notes || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { Plus, Search, Refresh, Download } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus';
import { useFactoryId } from '@/composables/useFactoryId';
import { usePermissionStore } from '@/store/modules/permission';
import { getWastageRecords, getWastageRecord, getWastageStatistics, createWastageRecord, submitWastage, approveWastage, rejectWastage, getRawMaterialTypes, getFactoryUsersForSelect } from '@/api/restaurant';
import AccountabilityBoard from './AccountabilityBoard.vue';
import { emptyCell, formatDateCell, formatAmount, exportTableToExcel } from '@/utils/tableFormatters';
import { formatDate } from '@/utils/dateFormat';
import type { WastageRecord } from '@/types/restaurant';
import { handleCatchError } from '@/utils/errorToast';
import AnalyticsStrip from '../components/AnalyticsStrip.vue';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';
import { TableFooter } from '@/components/list';
import { useListSummary } from '@/composables/useListSummary';
import { formatSummaryForAI } from '@/utils/aiSummaryContext';
import type { ListSummaryRequest } from '@/types/listSummary';

const wastageTypeMap: Record<string, string> = {
  EXPIRED: '过期', DAMAGED: '破损', SPOILED: '变质', PROCESSING: '加工损耗', OTHER: '其他',
};

// Wave2 损耗按人/档口责任制：档口固定枚举 (dropdown 约束选择，防呆 Rule 3)
const sectionOptions = [
  { code: 'SEAFOOD', label: '海鲜' },
  { code: 'COLD_DISH', label: '冷菜' },
  { code: 'HOT_DISH', label: '热菜' },
  { code: 'FRONT_HOUSE', label: '前厅' },
  { code: 'OTHER', label: '其他' },
];
const sectionLabelMap: Record<string, string> = Object.fromEntries(sectionOptions.map(s => [s.code, s.label]));
const sectionLabel = (code?: string | null) => (code ? (sectionLabelMap[code] || code) : '-');

const factoryId = useFactoryId();
const permissionStore = usePermissionStore();
const canWrite = computed(() => permissionStore.canWrite('restaurant'));
const canViewPrice = computed(() => permissionStore.canViewPrice);
const { goCreate } = useCreateAndReturn();

// U-FOOTER-1
const summaryRequest = computed<ListSummaryRequest>(() => ({ filterConditions: {} }));
const { summary: footerSummary, loading: footerLoading } = useListSummary('wastage', summaryRequest);

const boardVisible = ref(false);

// Wave2 责任人下拉 + 姓名映射（dropdown 选择，非自由文本）
const operatorOptions = ref<{ id: number; label: string }[]>([]);
const operatorNameMap = computed(() => Object.fromEntries(operatorOptions.value.map(u => [u.id, u.label])));
async function loadOperators() {
  if (!factoryId.value) return;
  try {
    const res = await getFactoryUsersForSelect(factoryId.value);
    if (res.success && res.data) {
      const d = res.data as { content?: { id: number; username: string; realName?: string }[] } | { id: number; username: string; realName?: string }[];
      const users = Array.isArray(d) ? d : (d.content || []);
      operatorOptions.value = users.map(u => ({ id: u.id, label: u.realName || u.username }));
    }
  } catch (e) {
    console.error('Failed to load operators:', e);
  }
}

const materialTypes = ref<{ id: string; name: string }[]>([]);
const materialNameMap = computed(() => Object.fromEntries(materialTypes.value.map(m => [m.id, m.name])));
async function loadMaterialTypes() {
  try {
    const res = await getRawMaterialTypes(factoryId.value);
    if (res.success && res.data) {
      const d = res.data as { content?: { id: string; name: string }[] } | { id: string; name: string }[];
      materialTypes.value = Array.isArray(d) ? d : d.content || [];
    }
  } catch (e) {
    console.error('Failed to load material types:', e);
    ElMessage.error('加载食材类型失败');
  }
}

const statsLoaded = ref(false);
const statsData = ref<{
  totalEstimatedCost?: number;
  totalCount?: number;
  byType?: { type: string; count: number; totalQuantity: number; totalCost: number }[];
  byMaterial?: { rawMaterialTypeId: string; unit: string; totalQuantity: number; totalCost: number }[];
}>({});
const topType = computed(() => {
  if (!statsData.value.byType?.length) return { type: '', totalCost: 0 };
  return statsData.value.byType.reduce((a, b) => (b.totalCost ?? 0) > (a.totalCost ?? 0) ? b : a);
});

async function loadStatistics() {
  try {
    const res = await getWastageStatistics(factoryId.value);
    if (res.success && res.data) {
      statsData.value = res.data;
      statsLoaded.value = true;
    }
  } catch (e) {
    console.error('Failed to load wastage statistics:', e);
    ElMessage.error('加载损耗统计失败');
  }
}

const loading = ref(false);
const submitting = ref(false);
const tableData = ref<WastageRecord[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const filterStatus = ref('');
const filterType = ref('');
const filterDateRange = ref<[string, string] | null>(null);
const dialogVisible = ref(false);
const detailVisible = ref(false);
const detailData = ref<WastageRecord | null>(null);

const emptyForm = () => ({
  type: 'EXPIRED',
  rawMaterialTypeId: '',
  quantity: 1,
  unit: 'kg',
  estimatedCost: 0,
  reason: '',
  sectionCode: undefined as string | undefined,
  operatorId: undefined as number | undefined,
});
const dialogForm = ref(emptyForm());
const formRef = ref<FormInstance>();
const formRules = {
  type: [{ required: true, message: '请选择损耗类型', trigger: 'change' }],
  rawMaterialTypeId: [{ required: true, message: '请选择食材', trigger: 'change' }],
  quantity: [{ required: true, message: '请输入损耗数量', trigger: 'blur' }],
};

const statusType = (s: string) => ({ DRAFT: 'info', SUBMITTED: 'warning', APPROVED: 'success', REJECTED: 'danger' }[s] || 'info');
const statusText = (s: string) => ({ DRAFT: '草稿', SUBMITTED: '待审批', APPROVED: '已审批', REJECTED: '已驳回' }[s] || s);
const wastageTypeTag = (t: string) => ({ EXPIRED: 'danger', DAMAGED: 'warning', SPOILED: 'danger', PROCESSING: 'info', OTHER: '' }[t] || '');
const wastageTypeText = (t: string) => ({ EXPIRED: '过期', DAMAGED: '破损', SPOILED: '变质', PROCESSING: '加工损耗', OTHER: '其他' }[t] || t);

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await getWastageRecords(factoryId.value, {
      page: pagination.value.page,
      size: pagination.value.size,
      status: filterStatus.value || undefined,
      type: filterType.value || undefined,
      startDate: filterDateRange.value?.[0] || undefined,
      endDate: filterDateRange.value?.[1] || undefined
    });
    if (res.success && res.data) {
      const d = res.data as { content?: unknown[]; totalElements?: number } | unknown[];
      const items = Array.isArray(d) ? d : (d as { content?: unknown[]; totalElements?: number }).content || [];
      tableData.value = items as WastageRecord[];
      pagination.value.total = Array.isArray(d) ? items.length : ((d as { content?: unknown[]; totalElements?: number }).totalElements ?? items.length);
    } else {
      tableData.value = [];
      pagination.value.total = 0;
    }
  } catch (e) {
    console.error('Failed to load wastage records:', e);
    ElMessage.error('加载损耗数据失败');
    tableData.value = [];
    pagination.value.total = 0;
  } finally { loading.value = false; }
}

const router = useRouter();
function handleAiAnalyze() {
  router.push({ path: '/smart-bi/query', query: { q: '最近30天损耗最多的食材和类型占比' } });
}

function handleSearch() { pagination.value.page = 1; loadData(); }
function handleRefresh() { filterStatus.value = ''; filterType.value = ''; filterDateRange.value = null; handleSearch(); }
function handleCreate() { dialogForm.value = emptyForm(); dialogVisible.value = true; }
async function showDetail(row: WastageRecord) {
  detailData.value = row;
  detailVisible.value = true;
  try {
    const res = await getWastageRecord(factoryId.value, row.id);
    if (res.success && res.data) detailData.value = res.data as WastageRecord;
  } catch { /* keep cached row data */ }
}

async function submitCreateForm() {
  if (formRef.value) {
    try { await formRef.value.validate(); } catch { return; }
  }
  submitting.value = true;
  try {
    const res = await createWastageRecord(factoryId.value, dialogForm.value);
    if (res.success) {
      ElMessage.success('损耗记录已创建');
      dialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(res.message || '创建失败');
    }
  } catch {
    ElMessage.error('创建失败，请检查网络连接');
  } finally { submitting.value = false; }
}

async function handleSubmit(row: WastageRecord) {
  if (submitting.value) return;
  try {
    await ElMessageBox.confirm('确认提交该损耗记录？', '提交', { type: 'warning' });
  } catch { return; }
  submitting.value = true;
  try {
    const res = await submitWastage(factoryId.value, row.id);
    if (res.success) {
      ElMessage.success('已提交');
      loadData();
    } else {
      ElMessage.error(res.message || '提交失败');
    }
  } catch (e) { handleCatchError(e, '提交失败，请检查网络'); }
  finally { submitting.value = false; }
}

async function handleApprove(row: WastageRecord) {
  if (submitting.value) return;
  try {
    await ElMessageBox.confirm('确认审批通过？审批后将记入损耗统计。', '审批', { type: 'warning' });
  } catch { return; }
  submitting.value = true;
  try {
    const res = await approveWastage(factoryId.value, row.id);
    if (res.success) {
      ElMessage.success('已审批');
      loadData();
    } else {
      ElMessage.error(res.message || '审批失败');
    }
  } catch (e) { handleCatchError(e, '审批失败，请检查网络'); }
  finally { submitting.value = false; }
}

async function handleReject(row: WastageRecord) {
  if (submitting.value) return;
  let reason: string;
  try {
    const { value } = await ElMessageBox.prompt('请输入驳回原因', '驳回', { inputType: 'textarea' });
    reason = value;
  } catch { return; }
  submitting.value = true;
  try {
    const res = await rejectWastage(factoryId.value, row.id, { reason });
    if (res.success) {
      ElMessage.success('已驳回');
      loadData();
    } else {
      ElMessage.error(res.message || '驳回失败');
    }
  } catch (e) { handleCatchError(e, '驳回失败，请检查网络'); }
  finally { submitting.value = false; }
}

const MAX_EXPORT = 10000;

async function handleExport() {
  let exportData: WastageRecord[] = tableData.value;
  if (pagination.value.total > pagination.value.size) {
    const exportSize = Math.min(pagination.value.total, MAX_EXPORT);
    if (pagination.value.total > MAX_EXPORT) ElMessage.warning(`数据量较大，仅导出前 ${MAX_EXPORT} 条`);
    else ElMessage.info('正在导出全部数据…');
    try {
      const res = await getWastageRecords(factoryId.value, { page: 1, size: exportSize });
      if (res.success && res.data) {
        const d = res.data as { content?: WastageRecord[] } | WastageRecord[];
        exportData = Array.isArray(d) ? d : (d as { content?: WastageRecord[] }).content || [];
      }
    } catch { /* fall back to current page */ }
  }
  await exportTableToExcel(exportData as unknown as Record<string, unknown>[], [
    { label: '损耗单号', field: 'wastageNumber' },
    { label: '损耗日期', field: 'wastageDate' },
    { label: '损耗类型', field: 'type', formatter: (val) => wastageTypeText(String(val)) },
    { label: '状态', field: 'status', formatter: (val) => statusText(String(val)) },
    { label: '食材', field: 'rawMaterialTypeId', formatter: (val) => materialNameMap.value[String(val)] || String(val) },
    { label: '数量', field: 'quantity' },
    { label: '单位', field: 'unit' },
    { label: '档口', field: 'sectionCode', formatter: (val) => val ? sectionLabel(String(val)) : '' },
    { label: '责任人', field: 'operatorId', formatter: (val) => val ? (operatorNameMap.value[Number(val)] || ('用户#' + val)) : '' },
    { label: '估算损失', field: 'estimatedCost' },
    { label: '原因', field: 'reason' },
  ], '损耗记录');
}

onMounted(() => { loadData(); loadMaterialTypes(); loadStatistics(); loadOperators(); });

// Handle full-page reload: factoryId may not be ready at mount time
watch(factoryId, (val) => { if (val) { loadData(); loadStatistics(); loadOperators(); } });
</script>

<style scoped lang="scss">
@import '../restaurant-shared.scss';
</style>
