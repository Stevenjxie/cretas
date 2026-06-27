<template>
  <CanvasAwareWrapper module-code="restaurant">
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">领料管理</span>
            <span class="data-count">共 {{ pagination.total }} 条</span>
          </div>
          <div class="header-right">
 <el-button type="info" plain @click="handleAiAnalyze"> AI 分析</el-button>
            <el-button :icon="Download" @click="handleExport">导出</el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleCreate">新建领料单</el-button>
          </div>
        </div>
      </template>

      <!-- 统计卡片 -->
      <el-row :gutter="16" class="stat-row" v-if="statsLoaded">
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">领料总数</span>
            <span class="stat-value">{{ statsData.todayCount ?? 0 }} 单</span>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">待审批</span>
            <span class="stat-value" :class="{ warning: (statsData.pendingCount ?? 0) > 0 }">{{ statsData.pendingCount ?? 0 }} 单</span>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">已审批</span>
            <span class="stat-value" style="color: var(--el-color-success)">{{ statsData.todayTotalQuantity ?? 0 }} 单</span>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">总记录数</span>
            <span class="stat-value">{{ pagination.total }}</span>
          </div>
        </el-col>
      </el-row>

      <!-- Apr 24 P1 analytics strip: trend + ranking from current table rows -->
      <AnalyticsStrip
        :rows="tableData"
        date-field="requisitionDate"
        value-field="requestedQuantity"
        category-field="rawMaterialTypeId"
        :category-name-map="materialNameMap"
        trend-title="领料数量趋势"
        ranking-title="食材领料量 Top 10"
        value-unit="kg"
        :top-n="10"
      />

      <div class="search-bar" role="search" aria-label="领料记录筛选">
        <el-date-picker v-model="filterDateRange" type="daterange" range-separator="至" start-placeholder="开始日期"
          end-placeholder="结束日期" value-format="YYYY-MM-DD" clearable style="width: 240px" @change="handleSearch" />
        <el-select v-model="filterStatus" placeholder="状态" clearable style="width: 130px" @change="handleSearch">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已提交" value="SUBMITTED" />
          <el-option label="已审批" value="APPROVED" />
          <el-option label="已驳回" value="REJECTED" />
        </el-select>
        <el-select v-model="filterType" placeholder="类型" clearable style="width: 130px" @change="handleSearch">
          <el-option label="按菜品BOM" value="PRODUCTION" />
          <el-option label="手动领料" value="MANUAL" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
      </div>

      <el-table :data="tableData" row-key="id" v-loading="loading" stripe border style="width: 100%" empty-text="暂无领料记录，点击「新建领料单」创建" aria-label="领料记录列表">
        <el-table-column prop="requisitionNumber" label="领料单号" width="180" show-overflow-tooltip :formatter="emptyCell" />
        <el-table-column prop="requisitionDate" label="日期" width="120" :formatter="formatDateCell" />
        <el-table-column label="类型" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.type === 'PRODUCTION' ? '' : 'warning'" size="small">
              {{ row.type === 'PRODUCTION' ? '按BOM' : '手动' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="productTypeId" label="菜品" width="140" show-overflow-tooltip class-name="hidden-sm-col">
          <template #default="{ row }">{{ productNameMap[row.productTypeId] || row.productTypeId || '-' }}</template>
        </el-table-column>
        <el-table-column label="申请量" width="120" align="right">
          <template #default="{ row }">
            {{ row.requestedQuantity }} {{ row.unit }}
          </template>
        </el-table-column>
        <el-table-column label="实发量" width="120" align="right" class-name="hidden-sm-col">
          <template #default="{ row }">
            {{ row.actualQuantity != null ? `${row.actualQuantity} ${row.unit}` : '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="notes" label="备注" show-overflow-tooltip :formatter="emptyCell" />
        <el-table-column label="操作" width="200" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="showDetail(row)">查看</el-button>
            <el-button v-if="canWrite && row.status === 'DRAFT'" type="success" link size="small" :disabled="submitting" @click="handleSubmit(row)">提交</el-button>
            <el-button v-if="canWrite && row.status === 'SUBMITTED'" type="success" link size="small" :disabled="submitting" @click="handleApprove(row)">审批</el-button>
            <el-button v-if="canWrite && row.status === 'SUBMITTED'" type="danger" link size="small" :disabled="submitting" @click="handleReject(row)">驳回</el-button>
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
          @current-change="loadData"
          @size-change="() => { pagination.page = 1; loadData(); }"
        />
      </div>
    </el-card>

    <!-- 新建领料对话框 -->
    <el-dialog v-model="dialogVisible" title="新建领料单" width="500px" :close-on-click-modal="false" destroy-on-close>
      <el-form ref="formRef" :model="dialogForm" :rules="formRules" label-width="100px">
        <el-form-item label="类型" prop="type">
          <el-radio-group v-model="dialogForm.type">
            <el-radio value="PRODUCTION">按菜品BOM</el-radio>
            <el-radio value="MANUAL">手动领料</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="dialogForm.type === 'PRODUCTION'" label="菜品" prop="productTypeId">
          <el-select v-model="dialogForm.productTypeId" filterable placeholder="选择菜品" style="width: 100%">
            <el-option v-for="pt in productTypes" :key="pt.id" :label="pt.name" :value="pt.id" />
          </el-select>
          <UpstreamMissingHint v-if="productTypes.length === 0" description="本店暂无菜品，无法领料" target-module="system" require-write action-text="去创建菜品" contact-text="请联系管理员先创建菜品" @action="goCreate('/system/products')" />
        </el-form-item>
        <el-form-item v-if="dialogForm.type === 'PRODUCTION'" label="制作份数">
          <el-input-number v-model="dialogForm.dishQuantity" :min="1" />
        </el-form-item>
        <!-- BOM计算结果提示 -->
        <el-alert v-if="dialogForm.type === 'PRODUCTION' && bomItems.length > 1" type="info" :closable="false" style="margin-bottom: 12px">
          <template #title>
            配方含 {{ bomItems.length }} 种食材，已自动选择主料。可切换食材：
          </template>
        </el-alert>
        <el-form-item label="食材" prop="rawMaterialTypeId" v-loading="bomLoading">
          <!-- PRODUCTION模式 + 有BOM数据: 从BOM食材中选择 -->
          <el-select v-if="dialogForm.type === 'PRODUCTION' && bomItems.length > 0"
            v-model="dialogForm.rawMaterialTypeId" placeholder="选择食材" style="width: 100%"
            @change="onBomMaterialChange">
            <el-option v-for="item in bomItems" :key="item.rawMaterialTypeId"
              :label="(materialNameMap[item.rawMaterialTypeId] || item.rawMaterialTypeId) + (item.isMainIngredient ? ' (主料)' : '')"
              :value="item.rawMaterialTypeId" />
          </el-select>
          <!-- 手动模式或无BOM: 全部食材 -->
          <el-select v-else v-model="dialogForm.rawMaterialTypeId" filterable placeholder="选择食材" style="width: 100%">
            <el-option v-for="mt in materialTypes" :key="mt.id" :label="mt.name" :value="mt.id" />
          </el-select>
          <UpstreamMissingHint v-if="materialTypes.length === 0" description="本店暂无食材，无法领料" target-module="warehouse" require-write action-text="去创建物料类型" contact-text="请联系仓库或管理员先创建物料类型" @action="goCreate('/warehouse/material-types')" />
        </el-form-item>
        <el-form-item label="申请数量" prop="requestedQuantity">
          <el-input-number v-model="dialogForm.requestedQuantity" :precision="4" :step="0.5" :min="0" />
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="dialogForm.unit" placeholder="kg / L" style="width: 120px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="dialogForm.notes" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="submitting" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreateForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="领料单详情" size="400px">
      <el-descriptions :column="1" border v-if="detailData">
        <el-descriptions-item label="单号">{{ detailData.requisitionNumber }}</el-descriptions-item>
        <el-descriptions-item label="日期">{{ formatDate(detailData.requisitionDate) }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ detailData.type === 'PRODUCTION' ? '按BOM' : '手动' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusText(detailData.status) }}</el-descriptions-item>
        <el-descriptions-item label="菜品">{{ productNameMap[detailData.productTypeId] || detailData.productTypeId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="份数">{{ detailData.dishQuantity || '-' }}</el-descriptions-item>
        <el-descriptions-item label="食材">{{ materialNameMap[detailData.rawMaterialTypeId] || detailData.rawMaterialTypeId }}</el-descriptions-item>
        <el-descriptions-item label="申请量">{{ detailData.requestedQuantity }} {{ detailData.unit }}</el-descriptions-item>
        <el-descriptions-item label="实发量">{{ detailData.actualQuantity ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="备注">{{ detailData.notes || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-drawer>
  </div>
  </CanvasAwareWrapper>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, nextTick } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Plus, Search, Refresh, Download } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus';
import { useFactoryId } from '@/composables/useFactoryId';
import { usePermissionStore } from '@/store/modules/permission';
import { getRequisitions, getRequisition, getRequisitionStatistics, calculateRecipeIngredients, createRequisition, submitRequisition, approveRequisition, rejectRequisition, getProductTypesActive, getRawMaterialTypes } from '@/api/restaurant';
import { emptyCell, formatDateCell, exportTableToExcel } from '@/utils/tableFormatters';
import { formatDate } from '@/utils/dateFormat';
import type { RequisitionItem } from '@/types/restaurant';
import CanvasAwareWrapper from '@/components/canvas/CanvasAwareWrapper.vue';
import { handleCatchError } from '@/utils/errorToast';
import AnalyticsStrip from '../components/AnalyticsStrip.vue';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';

const factoryId = useFactoryId();
const permissionStore = usePermissionStore();
const canWrite = computed(() => permissionStore.canWrite('restaurant'));
const { goCreate } = useCreateAndReturn();

const productTypes = ref<{ id: string; name: string }[]>([]);
const materialTypes = ref<{ id: string; name: string }[]>([]);
const productNameMap = computed(() => Object.fromEntries(productTypes.value.map(p => [p.id, p.name])));
const materialNameMap = computed(() => Object.fromEntries(materialTypes.value.map(m => [m.id, m.name])));
async function loadSelectOptions() {
  try {
    const [ptRes, mtRes] = await Promise.all([
      getProductTypesActive(factoryId.value),
      getRawMaterialTypes(factoryId.value)
    ]);
    if (ptRes.success && ptRes.data) {
      productTypes.value = Array.isArray(ptRes.data) ? ptRes.data : [];
    }
    if (mtRes.success && mtRes.data) {
      const d = mtRes.data as { content?: { id: string; name: string }[] } | { id: string; name: string }[];
      materialTypes.value = Array.isArray(d) ? d : d.content || [];
    }
  } catch (e) {
    console.error('Failed to load select options:', e);
    ElMessage.error('加载选项数据失败');
  }
}

const statsLoaded = ref(false);
const statsData = ref<{ todayCount?: number; pendingCount?: number; todayTotalQuantity?: number; productionCount?: number; manualCount?: number }>({});

async function loadStatistics() {
  try {
    const res = await getRequisitionStatistics(factoryId.value);
    if (res.success && res.data) {
      statsData.value = {
        todayCount: res.data.totalRequisitions ?? 0,
        pendingCount: res.data.pendingApproval ?? 0,
        todayTotalQuantity: res.data.approved ?? 0,
        productionCount: 0,
        manualCount: 0,
      };
      statsLoaded.value = true;
    }
  } catch (e) {
    console.error('Failed to load requisition statistics:', e);
    ElMessage.error('加载领料统计失败');
  }
}

const loading = ref(false);
const submitting = ref(false);
const tableData = ref<RequisitionItem[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const filterDateRange = ref<[string, string] | null>(null);
const filterStatus = ref('');
const filterType = ref('');
const dialogVisible = ref(false);
const detailVisible = ref(false);
const detailData = ref<RequisitionItem | null>(null);

const emptyForm = () => ({
  type: 'MANUAL' as string,
  productTypeId: '',
  dishQuantity: 1,
  rawMaterialTypeId: '',
  requestedQuantity: 1,
  unit: 'kg',
  notes: ''
});
const dialogForm = ref(emptyForm());
const formRef = ref<FormInstance>();
const formRules = computed(() => ({
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
  rawMaterialTypeId: [{ required: true, message: '请选择食材', trigger: 'change' }],
  requestedQuantity: [{ required: true, message: '请输入申请数量', trigger: 'blur' }],
  ...(dialogForm.value.type === 'PRODUCTION' ? {
    productTypeId: [{ required: true, message: '请输入菜品ID', trigger: 'blur' }],
  } : {}),
}));

// BOM auto-fill: when PRODUCTION + dish selected, calculate ingredients
const bomLoading = ref(false);
const bomItems = ref<{ rawMaterialTypeId: string; unit: string; totalStandardQuantity: number; isMainIngredient: boolean }[]>([]);

let bomDebounceTimer: ReturnType<typeof setTimeout> | null = null;
watch(
  () => [dialogForm.value.type, dialogForm.value.productTypeId, dialogForm.value.dishQuantity] as const,
  ([type, productId, qty]) => {
    if (bomDebounceTimer) clearTimeout(bomDebounceTimer);
    bomDebounceTimer = setTimeout(() => fetchBom(type, productId, qty), 300);
  },
  { deep: false }
);

async function fetchBom(type: string, productId: string, qty: number) {
  if (type !== 'PRODUCTION' || !productId) {
    bomItems.value = [];
    return;
  }
  bomLoading.value = true;
  try {
    const res = await calculateRecipeIngredients(factoryId.value, productId, qty || 1);
    if (res.success && Array.isArray(res.data) && res.data.length > 0) {
      bomItems.value = res.data;
      const main = res.data.find((i) => i.isMainIngredient) || res.data[0];
      dialogForm.value.rawMaterialTypeId = main.rawMaterialTypeId;
      dialogForm.value.requestedQuantity = main.totalStandardQuantity ?? main.totalActualQuantity ?? 1;
      dialogForm.value.unit = main.unit || 'kg';
    } else {
      bomItems.value = [];
    }
  } catch {
    bomItems.value = [];
  } finally {
    bomLoading.value = false;
  }
}

function onBomMaterialChange(rawMaterialTypeId: string) {
  const item = bomItems.value.find(i => i.rawMaterialTypeId === rawMaterialTypeId);
  if (item) {
    dialogForm.value.requestedQuantity = item.totalStandardQuantity ?? 1;
    dialogForm.value.unit = item.unit || 'kg';
  }
}

const statusType = (s: string) => ({ DRAFT: 'info', SUBMITTED: 'warning', APPROVED: 'success', REJECTED: 'danger' }[s] || 'info');
const statusText = (s: string) => ({ DRAFT: '草稿', SUBMITTED: '待审批', APPROVED: '已审批', REJECTED: '已驳回' }[s] || s);

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await getRequisitions(factoryId.value, {
      page: pagination.value.page,
      size: pagination.value.size,
      startDate: filterDateRange.value?.[0] || undefined,
      endDate: filterDateRange.value?.[1] || undefined,
      status: filterStatus.value || undefined,
      type: filterType.value || undefined
    });
    if (res.success && res.data) {
      const d = res.data as { content?: unknown[]; totalElements?: number } | unknown[];
      const items = Array.isArray(d) ? d : (d as { content?: unknown[]; totalElements?: number }).content || [];
      tableData.value = items as RequisitionItem[];
      pagination.value.total = Array.isArray(d) ? items.length : ((d as { content?: unknown[]; totalElements?: number }).totalElements ?? items.length);
    } else {
      tableData.value = [];
      pagination.value.total = 0;
    }
  } catch (e) {
    console.error('Failed to load requisitions:', e);
    ElMessage.error('加载领料数据失败');
    tableData.value = [];
    pagination.value.total = 0;
  } finally {
    loading.value = false;
  }
}

function handleAiAnalyze() {
  // Plan C Phase 5: jump to AI Query with pre-filled prompt that the Gold ops router picks up
  router.push({ path: '/smart-bi/query', query: { q: '最近30天领料趋势, top 10 食材领用量和异常' } });
}

function handleSearch() { pagination.value.page = 1; loadData(); }
function handleRefresh() { filterDateRange.value = null; filterStatus.value = ''; filterType.value = ''; handleSearch(); }
function handleCreate() { dialogForm.value = emptyForm(); dialogVisible.value = true; }
async function showDetail(row: RequisitionItem) {
  detailData.value = row;
  detailVisible.value = true;
  try {
    const res = await getRequisition(factoryId.value, row.id);
    if (res.success && res.data) detailData.value = res.data as RequisitionItem;
  } catch { /* keep cached row data */ }
}

async function submitCreateForm() {
  if (formRef.value) {
    try { await formRef.value.validate(); } catch { return; }
  }
  submitting.value = true;
  try {
    const res = await createRequisition(factoryId.value, dialogForm.value);
    if (res.success) {
      ElMessage.success('领料单已创建');
      dialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(res.message || '创建失败');
    }
  } catch {
    ElMessage.error('创建失败，请检查网络连接');
  } finally { submitting.value = false; }
}

async function handleSubmit(row: RequisitionItem) {
  if (submitting.value) return;
  try {
    await ElMessageBox.confirm('确认提交该领料单？', '提交', { type: 'warning' });
  } catch { return; }
  submitting.value = true;
  try {
    const res = await submitRequisition(factoryId.value, row.id);
    if (res.success) {
      ElMessage.success('已提交');
      loadData();
    } else {
      ElMessage.error(res.message || '提交失败');
    }
  } catch (e) { handleCatchError(e, '提交失败，请检查网络'); }
  finally { submitting.value = false; }
}

async function handleApprove(row: RequisitionItem) {
  if (submitting.value) return;
  let actualQuantity: number;
  try {
    const { value } = await ElMessageBox.prompt(
      `申请量: ${row.requestedQuantity} ${row.unit}，请输入实发量`,
      '审批领料',
      { inputValue: String(row.requestedQuantity), inputType: 'text', type: 'warning' }
    );
    actualQuantity = Number(value);
    if (isNaN(actualQuantity) || actualQuantity < 0) {
      ElMessage.warning('请输入有效的实发数量');
      return;
    }
  } catch { return; }
  submitting.value = true;
  try {
    const res = await approveRequisition(factoryId.value, row.id, { actualQuantity });
    if (res.success) {
      ElMessage.success('已审批');
      loadData();
    } else {
      ElMessage.error(res.message || '审批失败');
    }
  } catch (e) { handleCatchError(e, '审批失败，请检查网络'); }
  finally { submitting.value = false; }
}

async function handleReject(row: RequisitionItem) {
  if (submitting.value) return;
  let reason: string;
  try {
    const { value } = await ElMessageBox.prompt('请输入驳回原因', '驳回', { inputType: 'textarea' });
    reason = value;
  } catch { return; }
  submitting.value = true;
  try {
    const res = await rejectRequisition(factoryId.value, row.id, { reason });
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
  let exportData: RequisitionItem[] = tableData.value;
  if (pagination.value.total > pagination.value.size) {
    const exportSize = Math.min(pagination.value.total, MAX_EXPORT);
    if (pagination.value.total > MAX_EXPORT) ElMessage.warning(`数据量较大，仅导出前 ${MAX_EXPORT} 条`);
    else ElMessage.info('正在导出全部数据…');
    try {
      const res = await getRequisitions(factoryId.value, { page: 1, size: exportSize });
      if (res.success && res.data) {
        const d = res.data as { content?: RequisitionItem[] } | RequisitionItem[];
        exportData = Array.isArray(d) ? d : (d as { content?: RequisitionItem[] }).content || [];
      }
    } catch { /* fall back to current page */ }
  }
  await exportTableToExcel(exportData as unknown as Record<string, unknown>[], [
    { label: '领料单号', field: 'requisitionNumber' },
    { label: '日期', field: 'requisitionDate' },
    { label: '类型', field: 'type', formatter: (val) => val === 'PRODUCTION' ? '按BOM' : '手动' },
    { label: '状态', field: 'status', formatter: (val) => statusText(String(val)) },
    { label: '菜品', field: 'productTypeId', formatter: (val) => productNameMap.value[String(val)] || String(val) || '-' },
    { label: '申请量', field: 'requestedQuantity' },
    { label: '实发量', field: 'actualQuantity' },
    { label: '单位', field: 'unit' },
    { label: '备注', field: 'notes' },
  ], '领料记录');
}

// Apr 20 Bug BR-03 fix: DashboardRestaurant 的"待审批"卡片现传 ?status=SUBMITTED
// 进来, 这里读 route query 预设 filterStatus, 让用户看到筛选后列表而不是全列表.
const route = useRoute();
const router = useRouter();
onMounted(() => {
  const qs = route.query.status;
  if (typeof qs === 'string' && ['DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED'].includes(qs)) {
    filterStatus.value = qs;
  }
  loadData();
  loadSelectOptions();
  loadStatistics();
});

// Handle full-page reload: factoryId may not be ready at mount time
watch(factoryId, (val) => { if (val) { loadData(); loadStatistics(); } });
</script>

<style scoped lang="scss">
@use '../restaurant-shared.scss';
</style>
