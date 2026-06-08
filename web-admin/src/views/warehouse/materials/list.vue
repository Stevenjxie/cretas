<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put } from '@/api/request';
import { ElMessage } from 'element-plus';
import CanvasAwareWrapper from '@/components/canvas/CanvasAwareWrapper.vue';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import { Plus, Search, Refresh } from '@element-plus/icons-vue';
import { formatDateTimeCell } from '@/utils/tableFormatters';
import type { FormInstance } from 'element-plus';
import type { TableRow } from '@/types/api';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('warehouse'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const searchKeyword = ref('');

const materialTypes = ref<TableRow[]>([]);
const suppliers = ref<TableRow[]>([]);

onMounted(() => {
  loadData();
  loadMaterialTypes();
  loadSuppliers();
});

async function loadMaterialTypes() {
  if (!factoryId.value) return;
  try {
    // Bug B2 fix: use raw-material-types/active (same table the backend validates against)
    const res = await get(`/${factoryId.value}/raw-material-types/active`);
    if (res.success && res.data) materialTypes.value = Array.isArray(res.data) ? res.data : (res.data.content || []);
  } catch { /* silent */ }
}

async function loadSuppliers() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/suppliers`, { params: { size: 200 } });
    if (res.success && res.data) suppliers.value = res.data.content || res.data || [];
  } catch { /* silent */ }
}

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/material-batches`, {
      params: {
        page: pagination.value.page,
        size: pagination.value.size,
        keyword: searchKeyword.value || undefined
      }
    });
    if (response.success && response.data) {
      tableData.value = response.data.content || [];
      pagination.value.total = response.data.totalElements || 0;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载原材料批次失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleRefresh() {
  searchKeyword.value = '';
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

function getStatusType(status: string) {
  const map: Record<string, string> = {
    AVAILABLE: 'success',
    RESERVED: 'warning',
    DEPLETED: 'info',
    EXPIRED: 'danger'
  };
  return map[status?.toUpperCase()] || 'info';
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    AVAILABLE: '可用',
    RESERVED: '已预留',
    DEPLETED: '已耗尽',
    EXPIRED: '已过期'
  };
  return map[status?.toUpperCase()] || status;
}

// ==================== View Dialog ====================
const viewDialogVisible = ref(false);
const viewRecord = ref<TableRow | null>(null);

function handleView(row: TableRow) {
  viewRecord.value = row;
  viewDialogVisible.value = true;
}

// ==================== Create / Edit Dialog ====================
const formDialogVisible = ref(false);
const formDialogTitle = ref('入库登记');
const formRef = ref<FormInstance>();
const formSaving = ref(false);
const editingId = ref<string | null>(null);

const formData = reactive({
  batchNumber: '',
  materialTypeId: '',
  supplierId: '',
  receiptDate: new Date().toISOString().slice(0, 10),
  receiptQuantity: null as number | null,
  quantityUnit: 'kg',
  boxCount: null as number | null,
  totalWeight: null as number | null,
  totalValue: null as number | null,
  expireDate: '',
  notes: '',
});

// Track the selected material's canonical unit for the UI hint
const selectedMaterialUnit = ref<string | null>(null);

const formRules = {
  batchNumber: [{ required: true, message: '请输入批次号', trigger: 'blur' }],
  materialTypeId: [{ required: true, message: '请选择原料类型', trigger: 'change' }],
  supplierId: [{ required: true, message: '请选择供应商', trigger: 'change' }],
  receiptQuantity: [{ required: true, message: '请输入数量', trigger: 'blur' }],
  receiptDate: [{ required: true, message: '请选择入库日期', trigger: 'change' }],
  totalWeight: [{ required: true, message: '请输入总重量(kg)', trigger: 'blur' }],
  totalValue: [{ required: true, message: '请输入总价值(元)', trigger: 'blur' }],
};

// Bug C5: auto-calculate totalWeight and totalValue from selected material's base info
// W-02 fix (Round 7): when material has no unit price, hint the user so they don't
// stare at an empty required field wondering why auto-calc skipped it.
// T158 fix: auto-sync quantityUnit to the material's master unit when user selects/changes
// a material in add mode, preventing kg/个 mismatch (防呆 Rule 2 — unit anchored to master).
let w02HintShown = false;
function autoCalcWeightAndValue() {
  const qty = formData.receiptQuantity;
  const mat = materialTypes.value.find((m: TableRow) => m.id === formData.materialTypeId) as TableRow | undefined;

  // T158: sync unit on every material-selection change, even before qty is entered.
  // Only apply in add mode (editingId === null) to avoid clobbering a saved batch's unit on edit-open.
  if (mat) {
    selectedMaterialUnit.value = (mat.unit as string) || null;
    if (editingId.value === null && mat.unit) {
      formData.quantityUnit = String(mat.unit);
    }
  } else {
    selectedMaterialUnit.value = null;
  }

  if (!formData.materialTypeId || qty == null || qty <= 0) return;
  if (!mat) return;
  // totalWeight = quantity (unit is typically kg; use quantity directly as weight)
  formData.totalWeight = Number((qty).toFixed(3));
  // totalValue = quantity * unitPrice
  const unitPrice = Number(mat.unitPrice || mat.movingAvgPrice || 0);
  if (unitPrice > 0) {
    formData.totalValue = Number((qty * unitPrice).toFixed(2));
    w02HintShown = false;
  } else if (!w02HintShown) {
    // Show hint once per dialog session so user knows why totalValue wasn't auto-filled
    ElMessage.info({ message: `原料「${mat.name || '该原料'}」未配置单价，请手动输入总价值`, duration: 4000 });
    w02HintShown = true;
  }
}

watch(() => formData.materialTypeId, () => { autoCalcWeightAndValue(); });
watch(() => formData.receiptQuantity, () => { autoCalcWeightAndValue(); });

function handleCreate() {
  editingId.value = null;
  formDialogTitle.value = '入库登记';
  w02HintShown = false;
  selectedMaterialUnit.value = null;
  Object.assign(formData, { batchNumber: '', materialTypeId: '', supplierId: '', receiptDate: new Date().toISOString().slice(0, 10), receiptQuantity: null, quantityUnit: 'kg', boxCount: null, totalWeight: null, totalValue: null, expireDate: '', notes: '' });
  formDialogVisible.value = true;
}

function handleEdit(row: TableRow) {
  editingId.value = String(row.id || '');
  formDialogTitle.value = '编辑批次';
  w02HintShown = false;
  selectedMaterialUnit.value = null;
  Object.assign(formData, {
    batchNumber: row.batchNumber || '',
    materialTypeId: row.materialTypeId || '',
    supplierId: row.supplierId || '',
    receiptDate: row.receiptDate || row.inboundDate || new Date().toISOString().slice(0, 10),
    receiptQuantity: row.receiptQuantity ?? row.quantity ?? row.currentQuantity ?? null,
    quantityUnit: row.quantityUnit || row.unit || 'kg',
    boxCount: row.boxCount ?? null,
    totalWeight: row.totalWeight ?? null,
    totalValue: row.totalValue ?? null,
    expireDate: row.expireDate || row.expiryDate || '',
    notes: row.notes || '',
  });
  formDialogVisible.value = true;
}

async function handleFormSubmit() {
  if (!formRef.value) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;

  formSaving.value = true;
  try {
    // W-05 fix (Round 8): factoryId was being spread into the body even though
    // the URL path already carries it — redundant noise in server logs. Backend
    // reads the path variable and ignores any body factoryId.
    const payload = { ...formData };
    let response;
    if (editingId.value) {
      response = await put(`/${factoryId.value}/material-batches/${editingId.value}`, payload);
    } else {
      response = await post(`/${factoryId.value}/material-batches`, payload);
    }
    if (response.success) {
      ElMessage.success(editingId.value ? '更新成功' : '入库登记成功');
      formDialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(response.message || '操作失败');
    }
  } catch (error) {
    console.error('保存失败:', error);
    ElMessage.error('保存失败，请重试');
  } finally {
    formSaving.value = false;
  }
}
</script>

<template>
  <CanvasAwareWrapper module-code="material_batch">
  <div class="page-wrapper">
    <ConceptDisambiguationAlert
      here-name="原料 / 物料"
      here="采购入库的原材料、包材、辅料（如「冻猪蹄」「吸塑盒」）"
      other-name="系统管理 → 成品 / SKU (本厂生产)"
      other="本厂生产的成品 / SKU（如「叮咚好食光卤猪蹄 200g」）"
      other-path="/system/products"
    />
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">原料 / 物料管理 (采购入库)</span>
            <span class="data-count">共 {{ pagination.total }} 条记录</span>
          </div>
          <div class="header-right">
            <el-button v-if="canWrite" @click="router.push('/warehouse/material-types')">
              管理原料类型字典
            </el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleCreate">入库登记</el-button>
          </div>
        </div>
      </template>

      <div class="search-bar">
        <el-input
          v-model="searchKeyword"
          placeholder="搜索批次号/原料名称"
          :prefix-icon="Search"
          clearable
          style="width: 280px"
          @keyup.enter="handleSearch"
        />
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
      </div>

      <el-table :data="tableData" v-loading="loading" empty-text="暂无数据" stripe border style="width: 100%">
        <el-table-column prop="batchNumber" label="批次号" width="160" />
        <el-table-column label="原料类型" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.materialTypeName || row.materialName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="supplierName" label="供应商" min-width="150" show-overflow-tooltip />
        <el-table-column label="数量" width="100" align="right">
          <template #default="{ row }">{{ row.quantity ?? row.currentQuantity ?? row.receiptQuantity ?? '-' }}</template>
        </el-table-column>
        <!-- T159-A foldable #1: 显示批次实际称重/入库单位 (quantityUnit), 非主数据 unit
             Steve反馈: "改批次单位列表不变" — row.unit=主数据unit(箱/kg等), row.quantityUnit=实际入库单位 -->
        <el-table-column label="单位" width="80" align="center">
          <template #default="{ row }">{{ row.quantityUnit || row.unit || '-' }}</template>
        </el-table-column>
        <el-table-column label="箱数" width="90" align="right">
          <template #default="{ row }">
            <span v-if="row.boxCount != null">约 {{ row.boxCount }} 箱</span>
            <span v-else class="text-secondary">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="expiryDate" label="过期日期" width="120" />
        <el-table-column prop="createdAt" label="入库时间" width="180" :formatter="formatDateTimeCell" />
        <el-table-column label="操作" width="120" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleView(row)">查看</el-button>
            <el-button v-if="canWrite" type="primary" link size="small" @click="handleEdit(row)">编辑</el-button>
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

    <!-- View Dialog -->
    <el-dialog v-model="viewDialogVisible" title="批次详情" width="500px" destroy-on-close>
      <el-descriptions v-if="viewRecord" :column="1" border>
        <el-descriptions-item label="批次号">{{ viewRecord.batchNumber || '-' }}</el-descriptions-item>
        <el-descriptions-item label="原料类型">{{ viewRecord.materialTypeName || viewRecord.materialName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="供应商">{{ viewRecord.supplierName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="数量">{{ viewRecord.quantity ?? viewRecord.currentQuantity ?? '-' }} {{ viewRecord.unit || '' }}</el-descriptions-item>
        <el-descriptions-item label="箱数">
          <span v-if="viewRecord.boxCount != null">约 {{ viewRecord.boxCount }} 箱 <span class="text-secondary">(粗略统计, 实际库存以称重 kg 为准)</span></span>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="getStatusType(String(viewRecord.status || ''))">{{ getStatusText(String(viewRecord.status || '')) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="过期日期">{{ viewRecord.expiryDate || '-' }}</el-descriptions-item>
        <el-descriptions-item label="入库时间">{{ viewRecord.createdAt || '-' }}</el-descriptions-item>
        <el-descriptions-item label="备注">{{ viewRecord.notes || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>

    <!-- Create / Edit Dialog -->
    <el-dialog v-model="formDialogVisible" :title="formDialogTitle" width="500px" destroy-on-close>
      <el-form ref="formRef" :model="formData" :rules="formRules" label-width="80px">
        <el-form-item label="批次号" prop="batchNumber">
          <el-tooltip v-if="!!editingId" content="批次号作为追溯标识, 创建后不可修改" placement="top-start">
            <el-input v-model="formData.batchNumber" placeholder="如 MB-2026-001" :disabled="true" />
          </el-tooltip>
          <el-input v-else v-model="formData.batchNumber" placeholder="如 MB-2026-001" />
        </el-form-item>
        <el-form-item label="原料类型" prop="materialTypeId">
          <el-select v-model="formData.materialTypeId" placeholder="选择原料类型" filterable style="width: 100%">
            <el-option v-for="mt in materialTypes" :key="mt.id" :label="mt.name" :value="mt.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="供应商" prop="supplierId">
          <el-select v-model="formData.supplierId" placeholder="选择供应商" filterable style="width: 100%">
            <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量" prop="receiptQuantity">
          <el-input-number v-model="formData.receiptQuantity" :min="0" :precision="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="单位">
          <el-select v-model="formData.quantityUnit" style="width: 100%">
            <el-option label="kg" value="kg" />
            <el-option label="g" value="g" />
            <el-option label="L" value="L" />
            <el-option label="个" value="个" />
            <el-option label="箱" value="箱" />
            <!-- T158: include master unit as an option if it's not in the standard list above -->
            <el-option
              v-if="selectedMaterialUnit && !['kg','g','L','个','箱'].includes(selectedMaterialUnit)"
              :label="selectedMaterialUnit"
              :value="selectedMaterialUnit"
            />
          </el-select>
          <div v-if="selectedMaterialUnit && editingId === null" class="field-hint">
            已自动锁定为原料主数据单位「{{ selectedMaterialUnit }}」，如有特殊情况可手动修改
          </div>
        </el-form-item>
        <el-form-item label="箱数">
          <el-input-number v-model="formData.boxCount" :min="0" :precision="0" :controls="true" placeholder="可选" style="width: 100%" />
          <div class="field-hint">粗略统计用, 实际库存以称重(kg)为准</div>
        </el-form-item>
        <el-form-item label="入库日期" prop="receiptDate">
          <el-date-picker v-model="formData.receiptDate" type="date" value-format="YYYY-MM-DD" placeholder="选择入库日期" style="width: 100%" />
        </el-form-item>
        <el-form-item label="总重量(kg)" prop="totalWeight">
          <el-input-number v-model="formData.totalWeight" :min="0" :precision="3" style="width: 100%" />
        </el-form-item>
        <el-form-item v-if="canViewPrice" label="总价值(元)" prop="totalValue">
          <el-input-number v-model="formData.totalValue" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="过期日期">
          <el-date-picker v-model="formData.expireDate" type="date" value-format="YYYY-MM-DD" placeholder="选择过期日期" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="formData.notes" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="formSaving" @click="handleFormSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
  </CanvasAwareWrapper>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
}

.field-hint {
  font-size: 12px;
  color: var(--text-color-secondary, #909399);
  line-height: 1.4;
  margin-top: 4px;
}

.text-secondary {
  font-size: 12px;
  color: var(--text-color-secondary, #909399);
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
