<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">盘点管理</span>
            <span class="data-count">共 {{ pagination.total }} 条</span>
          </div>
          <div class="header-right">
 <el-button type="info" plain @click="handleAiAnalyze"> AI 分析</el-button>
            <el-button :icon="Download" @click="handleExport">导出</el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleCreate">新建盘点</el-button>
          </div>
        </div>
      </template>

      <!-- 统计卡片 -->
      <el-row :gutter="16" class="stat-row" v-if="statsLoaded">
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">盘点总数</span>
            <span class="stat-value">{{ statsData.totalCount ?? pagination.total }} 条</span>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">盘盈次数</span>
            <span class="stat-value" style="color: var(--el-color-success)">{{ statsData.surplusCount ?? 0 }}</span>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">盘亏次数</span>
            <span class="stat-value danger">{{ statsData.shortageCount ?? 0 }}</span>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">最近盘点</span>
            <span class="stat-value">{{ statsData.latestDate || '暂无' }}</span>
          </div>
        </el-col>
      </el-row>

      <!-- Apr 24 P1 analytics strip: trend of差异 + ranking by food -->
      <AnalyticsStrip
        :rows="tableData"
        date-field="stocktakingDate"
        value-field="differenceQuantity"
        category-field="rawMaterialTypeId"
        :category-name-map="materialNameMap"
        trend-title="盘点差异趋势"
        ranking-title="食材盘亏/盈 Top 10"
        value-unit="kg"
        :top-n="10"
      />

      <div class="search-bar" role="search" aria-label="盘点记录筛选">
        <el-date-picker v-model="filterDateRange" type="daterange" range-separator="至" start-placeholder="开始日期"
          end-placeholder="结束日期" value-format="YYYY-MM-DD" clearable style="width: 240px" @change="handleSearch" />
        <el-select v-model="filterStatus" placeholder="状态" clearable style="width: 130px" @change="handleSearch">
          <el-option label="盘点中" value="IN_PROGRESS" />
          <el-option label="已完成" value="COMPLETED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
      </div>

      <el-table :data="tableData" row-key="id" v-loading="loading" stripe border style="width: 100%" empty-text="暂无盘点记录，点击「新建盘点」开始" aria-label="盘点记录列表">
        <el-table-column prop="stocktakingNumber" label="盘点单号" width="180" show-overflow-tooltip :formatter="emptyCell" />
        <el-table-column prop="stocktakingDate" label="盘点日期" width="120" :formatter="formatDateCell" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="stkStatusType(row.status)" size="small">{{ stkStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="rawMaterialTypeId" label="食材" width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ materialNameMap[row.rawMaterialTypeId] || row.rawMaterialTypeId }}</template>
        </el-table-column>
        <el-table-column label="账面库存" width="110" align="right">
          <template #default="{ row }">{{ row.systemQuantity }} {{ row.unit }}</template>
        </el-table-column>
        <el-table-column label="实盘数量" width="110" align="right">
          <template #default="{ row }">
            {{ row.actualQuantity != null ? `${row.actualQuantity} ${row.unit}` : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="差异" width="110" align="right">
          <template #default="{ row }">
            <span v-if="row.differenceQuantity != null" :style="{ color: diffColor(row.differenceType) }">
              {{ row.differenceQuantity > 0 ? '+' : '' }}{{ row.differenceQuantity }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="差异类型" width="100" align="center" class-name="hidden-sm-col">
          <template #default="{ row }">
            <el-tag v-if="row.differenceType" :type="diffTagType(row.differenceType)" size="small">
              {{ diffText(row.differenceType) }}
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="adjustmentReason" label="差异原因" show-overflow-tooltip :formatter="emptyCell" />
        <el-table-column label="操作" width="180" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="showDetail(row)">查看</el-button>
            <el-button v-if="canWrite && row.status === 'IN_PROGRESS'" type="success" link size="small" @click="handleComplete(row)">完成盘点</el-button>
            <el-button v-if="canWrite && row.status === 'IN_PROGRESS'" type="danger" link size="small" @click="handleCancel(row)">取消</el-button>
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

    <!-- 新建盘点对话框 -->
    <el-dialog v-model="dialogVisible" title="新建盘点" width="500px" :close-on-click-modal="false" destroy-on-close>
      <el-form ref="createFormRef" :model="dialogForm" :rules="createFormRules" label-width="100px">
        <el-form-item label="食材" prop="rawMaterialTypeId">
          <el-select v-model="dialogForm.rawMaterialTypeId" filterable placeholder="选择食材" style="width: 100%">
            <el-option v-for="mt in materialTypes" :key="mt.id" :label="mt.name" :value="mt.id" />
          </el-select>
          <UpstreamMissingHint v-if="materialTypes.length === 0" description="本店暂无食材，无法盘点" target-module="warehouse" require-write action-text="去创建物料类型" contact-text="请联系仓库或管理员先创建物料类型" @action="goCreate('/warehouse/material-types')" />
        </el-form-item>
        <el-form-item label="盘点日期" prop="stocktakingDate">
          <el-date-picker v-model="dialogForm.stocktakingDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width: 200px" />
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

    <!-- 完成盘点对话框 -->
    <el-dialog v-model="completeDialogVisible" title="完成盘点" width="500px" :close-on-click-modal="false" destroy-on-close>
      <el-form ref="completeFormRef" :model="completeForm" :rules="completeFormRules" label-width="100px">
        <el-form-item label="账面库存">
          <span>{{ completeForm.systemQuantity }} {{ completeForm.unit }}</span>
        </el-form-item>
        <el-form-item label="实盘数量" prop="actualQuantity">
          <el-input-number v-model="completeForm.actualQuantity" :precision="4" :step="0.5" :min="0" />
        </el-form-item>
        <el-form-item label="差异原因">
          <el-input v-model="completeForm.adjustmentReason" type="textarea" :rows="2" placeholder="如有差异请说明原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="submitting" @click="completeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitComplete">确认完成</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="盘点详情" size="400px">
      <el-descriptions :column="1" border v-if="detailData">
        <el-descriptions-item label="单号">{{ detailData.stocktakingNumber }}</el-descriptions-item>
        <el-descriptions-item label="日期">{{ formatDate(detailData.stocktakingDate) }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ stkStatusText(detailData.status) }}</el-descriptions-item>
        <el-descriptions-item label="食材">{{ materialNameMap[detailData.rawMaterialTypeId] || detailData.rawMaterialTypeId }}</el-descriptions-item>
        <el-descriptions-item label="账面库存">{{ detailData.systemQuantity }} {{ detailData.unit }}</el-descriptions-item>
        <el-descriptions-item label="实盘数量">{{ detailData.actualQuantity ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="差异">{{ detailData.differenceQuantity ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="差异类型">{{ diffText(detailData.differenceType) || '-' }}</el-descriptions-item>
        <el-descriptions-item v-if="canViewPrice" label="差异金额">{{ detailData.differenceAmount != null ? `¥${detailData.differenceAmount}` : '-' }}</el-descriptions-item>
        <el-descriptions-item label="差异原因">{{ detailData.adjustmentReason || '-' }}</el-descriptions-item>
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
import { getStocktakingRecords, getStocktakingRecord, getStocktakingSummary, createStocktakingRecord, completeStocktaking, cancelStocktaking, getRawMaterialTypes } from '@/api/restaurant';
import { emptyCell, formatDateCell, exportTableToExcel } from '@/utils/tableFormatters';
import { formatDate } from '@/utils/dateFormat';
import type { StocktakingRecord } from '@/types/restaurant';
import AnalyticsStrip from '../components/AnalyticsStrip.vue';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';

const factoryId = useFactoryId();
const permissionStore = usePermissionStore();
const canWrite = computed(() => permissionStore.canWrite('restaurant'));
const canViewPrice = computed(() => permissionStore.canViewPrice);
const { goCreate } = useCreateAndReturn();

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
const statsData = ref<{ totalCount?: number; surplusCount?: number; shortageCount?: number; latestDate?: string }>({});

async function loadStatistics() {
  try {
    const res = await getStocktakingSummary(factoryId.value);
    if (res.success && res.data) {
      statsData.value = {
        totalCount: res.data.totalCount ?? res.data.total,
        surplusCount: res.data.surplusCount ?? 0,
        shortageCount: res.data.shortageCount ?? 0,
        latestDate: res.data.latestStocktakingDate ?? res.data.latestDate,
      };
      statsLoaded.value = true;
    }
  } catch (e) {
    console.error('Failed to load stocktaking summary:', e);
    ElMessage.error('加载盘点统计失败');
  }
}

const loading = ref(false);
const submitting = ref(false);
const tableData = ref<StocktakingRecord[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const filterStatus = ref('');
const filterDateRange = ref<[string, string] | null>(null);
const dialogVisible = ref(false);
const completeDialogVisible = ref(false);
const detailVisible = ref(false);
const detailData = ref<StocktakingRecord | null>(null);

// 后端 StocktakingRecord 必填字段: rawMaterialTypeId, stocktakingDate（@NotNull）
// 若前端不传 stocktakingDate, JPA @Valid 会直接返回 400, 而不会进入 controller 的"默认为今天"分支
const today = () => new Date().toISOString().slice(0, 10);
const dialogForm = ref({ rawMaterialTypeId: '', stocktakingDate: today(), unit: 'kg', notes: '' });
const createFormRef = ref<FormInstance>();
const createFormRules = {
  rawMaterialTypeId: [{ required: true, message: '请选择食材', trigger: 'change' }],
  stocktakingDate: [{ required: true, message: '请选择盘点日期', trigger: 'change' }],
};
const completeForm = ref({ id: '', systemQuantity: 0, unit: '', actualQuantity: 0, adjustmentReason: '' });
const completeFormRef = ref<FormInstance>();
const completeFormRules = {
  actualQuantity: [{ required: true, message: '请输入实盘数量', trigger: 'blur' }],
};

const stkStatusType = (s: string) => ({ IN_PROGRESS: 'warning', COMPLETED: 'success', CANCELLED: 'info' }[s] || 'info');
const stkStatusText = (s: string) => ({ IN_PROGRESS: '盘点中', COMPLETED: '已完成', CANCELLED: '已取消' }[s] || s);
const diffColor = (t: string) => ({ SURPLUS: '#67C23A', SHORTAGE: '#F56C6C', MATCH: '#909399' }[t] || '#909399');
const diffTagType = (t: string) => ({ SURPLUS: 'success', SHORTAGE: 'danger', MATCH: 'info' }[t] || 'info');
const diffText = (t: string) => ({ SURPLUS: '盘盈', SHORTAGE: '盘亏', MATCH: '一致' }[t] || '');

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await getStocktakingRecords(factoryId.value, {
      page: pagination.value.page,
      size: pagination.value.size,
      status: filterStatus.value || undefined,
      startDate: filterDateRange.value?.[0] || undefined,
      endDate: filterDateRange.value?.[1] || undefined
    });
    if (res.success && res.data) {
      const d = res.data as { content?: unknown[]; totalElements?: number } | unknown[];
      const items = Array.isArray(d) ? d : (d as { content?: unknown[]; totalElements?: number }).content || [];
      tableData.value = items as StocktakingRecord[];
      pagination.value.total = Array.isArray(d) ? items.length : ((d as { content?: unknown[]; totalElements?: number }).totalElements ?? items.length);
    } else {
      tableData.value = [];
      pagination.value.total = 0;
    }
  } catch (e) {
    console.error('Failed to load stocktaking records:', e);
    ElMessage.error('加载盘点数据失败');
    tableData.value = [];
    pagination.value.total = 0;
  } finally { loading.value = false; }
}

const router = useRouter();
function handleAiAnalyze() {
  router.push({ path: '/smart-bi/query', query: { q: '最近30天盘亏最严重的食材 top 10' } });
}

function handleSearch() { pagination.value.page = 1; loadData(); }
function handleRefresh() { filterStatus.value = ''; filterDateRange.value = null; handleSearch(); }
function handleCreate() {
  dialogForm.value = { rawMaterialTypeId: '', stocktakingDate: today(), unit: 'kg', notes: '' };
  dialogVisible.value = true;
}
async function showDetail(row: StocktakingRecord) {
  detailData.value = row;
  detailVisible.value = true;
  try {
    const res = await getStocktakingRecord(factoryId.value, row.id);
    if (res.success && res.data) detailData.value = res.data as StocktakingRecord;
  } catch { /* keep cached row data */ }
}

async function submitCreateForm() {
  if (createFormRef.value) {
    try { await createFormRef.value.validate(); } catch { return; }
  }
  submitting.value = true;
  try {
    const res = await createStocktakingRecord(factoryId.value, dialogForm.value);
    if (res.success) {
      ElMessage.success('盘点单已创建');
      dialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(res.message || '创建失败');
    }
  } catch (e) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('Create stocktaking failed:', e);
  } finally { submitting.value = false; }
}

function handleComplete(row: StocktakingRecord) {
  completeForm.value = { id: row.id, systemQuantity: row.systemQuantity, unit: row.unit, actualQuantity: row.systemQuantity, adjustmentReason: '' };
  completeDialogVisible.value = true;
}

async function submitComplete() {
  if (completeFormRef.value) {
    try { await completeFormRef.value.validate(); } catch { return; }
  }
  submitting.value = true;
  try {
    const res = await completeStocktaking(factoryId.value, completeForm.value.id, {
      actualQuantity: completeForm.value.actualQuantity,
      adjustmentReason: completeForm.value.adjustmentReason || undefined
    });
    if (res.success) {
      ElMessage.success('盘点已完成');
      completeDialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(res.message || '完成盘点失败');
    }
  } catch (e) {
    console.error('Complete stocktaking failed:', e);
    ElMessage.error('完成盘点失败');
  } finally { submitting.value = false; }
}

async function handleCancel(row: StocktakingRecord) {
  try {
    await ElMessageBox.confirm('确认取消该盘点？', '提示', { type: 'warning' });
    const res = await cancelStocktaking(factoryId.value, row.id);
    if (res.success) {
      ElMessage.success('已取消');
      loadData();
    } else {
      ElMessage.error(res.message || '取消失败');
    }
  } catch (e) {
    if (e !== 'cancel') {
      console.error('Cancel stocktaking failed:', e);
      ElMessage.error('取消失败');
    }
  }
}

const MAX_EXPORT = 10000;

async function handleExport() {
  let exportData: StocktakingRecord[] = tableData.value;
  if (pagination.value.total > pagination.value.size) {
    const exportSize = Math.min(pagination.value.total, MAX_EXPORT);
    if (pagination.value.total > MAX_EXPORT) ElMessage.warning(`数据量较大，仅导出前 ${MAX_EXPORT} 条`);
    else ElMessage.info('正在导出全部数据…');
    try {
      const res = await getStocktakingRecords(factoryId.value, { page: 1, size: exportSize });
      if (res.success && res.data) {
        const d = res.data as { content?: StocktakingRecord[] } | StocktakingRecord[];
        exportData = Array.isArray(d) ? d : (d as { content?: StocktakingRecord[] }).content || [];
      }
    } catch { /* fall back to current page */ }
  }
  await exportTableToExcel(exportData as unknown as Record<string, unknown>[], [
    { label: '盘点单号', field: 'stocktakingNumber' },
    { label: '盘点日期', field: 'stocktakingDate' },
    { label: '状态', field: 'status', formatter: (val) => stkStatusText(String(val)) },
    { label: '食材', field: 'rawMaterialTypeId', formatter: (val) => materialNameMap.value[String(val)] || String(val) },
    { label: '账面库存', field: 'systemQuantity' },
    { label: '实盘数量', field: 'actualQuantity' },
    { label: '差异', field: 'differenceQuantity' },
    { label: '差异类型', field: 'differenceType', formatter: (val) => diffText(String(val)) || '-' },
    { label: '差异原因', field: 'adjustmentReason' },
  ], '盘点记录');
}

onMounted(() => { loadData(); loadMaterialTypes(); loadStatistics(); });

// Handle full-page reload: factoryId may not be ready at mount time
watch(factoryId, (val) => { if (val) { loadData(); loadStatistics(); } });
</script>

<style scoped lang="scss">
@import '../restaurant-shared.scss';
</style>
