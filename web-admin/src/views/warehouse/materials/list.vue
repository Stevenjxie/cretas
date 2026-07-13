<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put } from '@/api/request';
import { listManufacturers, type ManufacturerRegistry } from '@/api/manufacturer';
import { ElMessage, ElMessageBox } from 'element-plus';
import CanvasAwareWrapper from '@/components/canvas/CanvasAwareWrapper.vue';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import { Plus, Search, Refresh } from '@element-plus/icons-vue';
import { formatDateTimeCell, fmtQty, formatAmount } from '@/utils/tableFormatters';
import type { FormInstance } from 'element-plus';
import type { TableRow } from '@/types/api';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('warehouse'));
const canViewPrice = computed(() => permissionStore.canViewPrice);
const { goCreate } = useCreateAndReturn();

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const searchKeyword = ref('');

// ==================== 按物料汇总 / 按批次明细 切换 ====================
// F006 六膳门客户原话痛点: 同一原料入库多次 → 一堆散批次看着重复。
// 汇总模式按 materialTypeId 把当前搜索条件下的全部批次聚合成一行/物料 (合计量 + 批次数),
// 点开可展开看具体批次。纯前端聚合 (不新增后端端点) — 复用现有分页列表接口, 汇总时改用大 size
// 一次拉全量 (而非当前页 10 条), 避免"只汇总当前页"造成误导性小计。
interface MaterialSummaryGroup {
  materialTypeId: string;
  materialName: string;
  unit: string;
  totalQuantity: number;
  totalValue: number | null;
  batchCount: number;
  batches: TableRow[];
}

const viewMode = ref<'detail' | 'summary'>('detail');
const summaryLoading = ref(false);
const summaryRows = ref<TableRow[]>([]);
const summaryTruncated = ref(false);
// 汇总一次拉取的上限 — F006 规模下批次总数远小于此; 若真的超过, summaryTruncated 会提示用户缩小搜索范围
// 而不是静默漏算 (防呆: 不返回看似完整实则不全的假汇总)。
const SUMMARY_FETCH_SIZE = 2000;

async function loadSummaryData() {
  if (!factoryId.value) return;
  summaryLoading.value = true;
  summaryTruncated.value = false;
  try {
    const response = await get(`/${factoryId.value}/material-batches`, {
      params: {
        page: 1,
        size: SUMMARY_FETCH_SIZE,
        keyword: searchKeyword.value || undefined,
      },
    });
    if (response.success && response.data) {
      summaryRows.value = response.data.content || [];
      const total = Number(response.data.totalElements) || 0;
      summaryTruncated.value = total > summaryRows.value.length;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载按物料汇总数据失败');
      summaryRows.value = [];
    }
  } catch (error) {
    console.error('加载汇总失败:', error);
    ElMessage.error('加载按物料汇总数据失败，请重试');
    summaryRows.value = [];
  } finally {
    summaryLoading.value = false;
  }
}

const groupedSummary = computed<MaterialSummaryGroup[]>(() => {
  const map = new Map<string, MaterialSummaryGroup>();
  for (const row of summaryRows.value) {
    const key = String(row.materialTypeId || row.materialName || row.materialTypeName || 'unknown');
    const qty = Number(row.currentQuantity ?? row.quantity ?? row.receiptQuantity ?? 0) || 0;
    const unit = String(row.quantityUnit || row.unit || '-');
    const name = String(row.materialName || row.materialTypeName || '未命名物料');
    let group = map.get(key);
    if (!group) {
      group = {
        materialTypeId: key,
        materialName: name,
        unit,
        totalQuantity: 0,
        totalValue: canViewPrice.value ? 0 : null,
        batchCount: 0,
        batches: [],
      };
      map.set(key, group);
    }
    group.totalQuantity += qty;
    if (group.totalValue !== null) {
      group.totalValue += Number(row.totalValue) || 0;
    }
    group.batchCount += 1;
    group.batches.push(row);
  }
  return Array.from(map.values()).sort((a, b) => a.materialName.localeCompare(b.materialName, 'zh-CN'));
});

function handleViewModeChange(mode: string | number | boolean) {
  viewMode.value = mode as 'detail' | 'summary';
  if (viewMode.value === 'summary') loadSummaryData();
}

const materialTypes = ref<TableRow[]>([]);
const suppliers = ref<TableRow[]>([]);
const manufacturers = ref<ManufacturerRegistry[]>([]);

onMounted(() => {
  loadData();
  loadMaterialTypes();
  loadSuppliers();
  loadManufacturers();
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

async function loadManufacturers() {
  if (!factoryId.value) return;
  try {
    const res = await listManufacturers(factoryId.value, true);
    if (res.success && Array.isArray(res.data)) manufacturers.value = res.data;
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
  if (viewMode.value === 'summary') {
    loadSummaryData();
  } else {
    loadData();
  }
}

function handleRefresh() {
  searchKeyword.value = '';
  pagination.value.page = 1;
  if (viewMode.value === 'summary') {
    loadSummaryData();
  } else {
    loadData();
  }
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function handleManufacturerChange(code: string) {
  const manufacturer = manufacturers.value.find((item) => item.code === code);
  if (manufacturer?.originPlace) {
    formData.originPlace = manufacturer.originPlace;
  }
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
  factoryNumber: '',
  originPlace: '',
});

// ==================== 扫码查询 ====================
const scanCode = ref('');
const scanLoading = ref(false);
const scanResult = ref<Record<string, unknown> | null>(null);
const scanResultVisible = computed({
  get: () => scanResult.value !== null,
  set: (v: boolean) => { if (!v) scanResult.value = null; },
});

async function handleScanQuery() {
  const code = scanCode.value.trim();
  if (!code) {
    ElMessage.warning('请输入或扫描条码');
    return;
  }
  scanLoading.value = true;
  scanResult.value = null;
  try {
    const res = await get(`/${factoryId.value}/labels/scan/${encodeURIComponent(code)}`);
    if (res.success && res.data) {
      scanResult.value = res.data as Record<string, unknown>;
    } else {
      ElMessage({ message: res.message || '未找到对应批次', type: 'warning', duration: 0, showClose: true });
    }
  } catch {
    ElMessage({ message: '扫码查询失败，请重试', type: 'error', duration: 0, showClose: true });
  } finally {
    scanLoading.value = false;
  }
}

// Track the selected material's canonical unit for the UI hint
const selectedMaterialUnit = ref<string | null>(null);

const formRules = {
  // 一物一码: 批次号由系统自动生成, 非手填, 故无 required 校验 (转录 6.9 行68/105 防手敲重码)
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
  Object.assign(formData, { batchNumber: '', materialTypeId: '', supplierId: '', receiptDate: new Date().toISOString().slice(0, 10), receiptQuantity: null, quantityUnit: 'kg', boxCount: null, totalWeight: null, totalValue: null, expireDate: '', notes: '', factoryNumber: '', originPlace: '' });
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
    factoryNumber: row.factoryNumber || '',
    originPlace: row.originPlace || '',
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
      if (viewMode.value === 'summary') loadSummaryData();
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

// ==================== A5: 生成标签 ====================
const labelGenerating = ref<string | null>(null); // batchId 正在生成中

async function handleGenerateLabel(row: TableRow) {
  const batchId = String(row.id || '');
  if (!batchId) { ElMessage.warning('无效批次 ID'); return; }
  try {
    await ElMessageBox.confirm(
      `确认为批次「${row.batchNumber || batchId}」生成物料标签？\n\n标签已存在则会提示先作废旧标签。`,
      '生成物料标签',
      { confirmButtonText: '生成', cancelButtonText: '取消', type: 'info' }
    );
  } catch { return; } // user cancelled

  labelGenerating.value = batchId;
  try {
    const res = await post(`/${factoryId.value}/labels/material-batch/${batchId}`, {});
    if (res.success && res.data) {
      const code = (res.data as { labelCode?: string }).labelCode || '—';
      ElMessage({ message: `标签已生成，编号：${code}`, type: 'success', duration: 5000, showClose: true });
    } else {
      ElMessage({ message: res.message || '生成失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
    ElMessage({ message: msg || '生成标签失败，请重试', type: 'error', duration: 0, showClose: true });
  } finally {
    labelGenerating.value = null;
  }
}

// ==================== 续入到已有批次 (方案A 严格匹配续入, F006 采购补货) ====================
// 把本次到货并进指定的已有批次 (沿用其单价/保质期/供应商), 而不是每次新建批次 —— 解决
// "同一原料入很多次批次散着列看着重复"。防呆: 仅可用且未过期批次可续入, 否则引导新建;
// dialog 显式展示沿用的单价/保质期, 让用户确认新到货口径一致再续入 (fool-proof Rule 1/2)。
const replenishDialogVisible = ref(false);
const replenishSaving = ref(false);
const replenishTarget = ref<TableRow | null>(null);
const replenishForm = reactive<{ addQuantity: number | null; sourceDocType: string; sourceDocId: string; note: string }>({
  addQuantity: null,
  sourceDocType: '',
  sourceDocId: '',
  note: '',
});
const replenishUnit = computed(() => {
  const r = replenishTarget.value;
  return r ? String(r.quantityUnit || r.unit || '') : '';
});

function handleReplenish(row: TableRow) {
  const status = String(row.status || '');
  // 前置防呆: 非可用批次直接引导新建 (后端也会 409, 前端提前拦更友好)
  if (status && status !== 'AVAILABLE') {
    ElMessage({
      message: `该批次状态为「${getStatusText(row.status)}」，不支持续入。请改用上方「入库登记」新建批次。`,
      type: 'warning', duration: 0, showClose: true,
    });
    return;
  }
  replenishTarget.value = row;
  replenishForm.addQuantity = null;
  replenishForm.sourceDocType = '';
  replenishForm.sourceDocId = '';
  replenishForm.note = '';
  replenishDialogVisible.value = true;
}

async function handleReplenishSubmit() {
  const row = replenishTarget.value;
  if (!row) return;
  const batchId = String(row.id || '');
  if (!batchId) { ElMessage.warning('无效批次 ID'); return; }
  if (!replenishForm.addQuantity || replenishForm.addQuantity <= 0) {
    ElMessage.warning('请填写续入数量（必须大于 0）');
    return;
  }
  replenishSaving.value = true;
  try {
    const res = await post(`/${factoryId.value}/material-batches/${batchId}/replenish`, {
      addQuantity: replenishForm.addQuantity,
      sourceDocType: replenishForm.sourceDocType || undefined,
      sourceDocId: replenishForm.sourceDocId || undefined,
      note: replenishForm.note || undefined,
    });
    if (res.success) {
      ElMessage({
        message: `续入成功，批次「${row.batchNumber || batchId}」已增加 ${replenishForm.addQuantity} ${replenishUnit.value}`,
        type: 'success', duration: 4000, showClose: true,
      });
      replenishDialogVisible.value = false;
      if (viewMode.value === 'summary') loadSummaryData(); else loadData();
    } else {
      ElMessage({ message: res.message || '续入失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
    ElMessage({ message: msg || '续入失败，请重试', type: 'error', duration: 0, showClose: true });
  } finally {
    replenishSaving.value = false;
  }
}
</script>

<template>
  <CanvasAwareWrapper module-code="material_batch">
  <div class="page-wrapper">
    <ConceptDisambiguationAlert
      here-name="原料 / 物料"
      here="采购入库的原材料、包材、辅料（如「冻猪蹄」「吸塑盒」）"
      other-name="生产管理 → 成品 / SKU (本厂生产)"
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
            <el-input
              v-model="scanCode"
              placeholder="扫码/输入条码查询"
              clearable
              style="width: 200px"
              @keyup.enter="handleScanQuery"
            />
            <el-button :loading="scanLoading" @click="handleScanQuery">扫码查询</el-button>
            <el-button v-if="canWrite" @click="router.push('/warehouse/material-types')">
              管理原料类型字典
            </el-button>
            <el-button v-if="canWrite" @click="router.push('/warehouse/manufacturers')">
              厂商登记表
            </el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleCreate">入库登记</el-button>
          </div>
        </div>
      </template>

      <div class="search-bar">
        <el-radio-group v-model="viewMode" @change="handleViewModeChange">
          <el-radio-button label="detail">按批次明细</el-radio-button>
          <el-radio-button label="summary">按物料汇总</el-radio-button>
        </el-radio-group>
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

      <!-- 按物料汇总视图 (F006 客户原话痛点: 同一原料入库多次, 看着一堆散批次) -->
      <template v-if="viewMode === 'summary'">
        <div class="summary-hint">
          <span>共 <strong>{{ groupedSummary.length }}</strong> 种原料在库</span>
          <span v-if="summaryTruncated" class="summary-warning">
            数据量较大, 当前汇总仅基于前 {{ summaryRows.length }} 条批次记录, 如有遗漏请缩小搜索范围重新汇总
          </span>
        </div>
        <el-table
          :data="groupedSummary"
          v-loading="summaryLoading"
          empty-text="暂无库存数据"
          stripe
          border
          row-key="materialTypeId"
          style="width: 100%"
        >
          <el-table-column type="expand">
            <template #default="{ row }">
              <el-table :data="row.batches" size="small" border stripe class="nested-batch-table">
                <el-table-column prop="batchNumber" label="批次号" width="160" />
                <el-table-column prop="supplierName" label="供应商" min-width="140" show-overflow-tooltip />
                <el-table-column label="数量" width="120" align="right">
                  <template #default="{ row: b }">
                    {{ b.quantity ?? b.currentQuantity ?? b.receiptQuantity ?? '-' }} {{ b.quantityUnit || b.unit || '' }}
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="100" align="center">
                  <template #default="{ row: b }">
                    <el-tag :type="getStatusType(b.status)" size="small">{{ getStatusText(b.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="expiryDate" label="过期日期" width="120" />
                <el-table-column prop="createdAt" label="入库时间" width="180" :formatter="formatDateTimeCell" />
                <el-table-column label="操作" width="240" fixed="right" align="center">
                  <template #default="{ row: b }">
                    <el-button type="primary" link size="small" @click="handleView(b)">查看</el-button>
                    <el-button v-if="canWrite" type="primary" link size="small" @click="handleEdit(b)">编辑</el-button>
                    <el-button v-if="canWrite" type="warning" link size="small" @click="handleReplenish(b)">续入</el-button>
                    <el-button
                      v-if="canWrite"
                      type="success"
                      link
                      size="small"
                      :loading="labelGenerating === String(b.id)"
                      @click="handleGenerateLabel(b)"
                    >生成标签</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </template>
          </el-table-column>
          <el-table-column label="原料名称" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.materialName }}</template>
          </el-table-column>
          <el-table-column label="合计可用量" width="150" align="right">
            <template #default="{ row }"><strong>{{ fmtQty(row.totalQuantity) }}</strong></template>
          </el-table-column>
          <el-table-column label="单位" width="90" align="center">
            <template #default="{ row }">{{ row.unit }}</template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" label="合计价值" width="140" align="right">
            <template #default="{ row }">{{ formatAmount(row.totalValue) }}</template>
          </el-table-column>
          <el-table-column label="批次数" width="120" align="right">
            <template #default="{ row }">{{ row.batchCount }} 个批次</template>
          </el-table-column>
        </el-table>
      </template>

      <el-table v-else :data="tableData" v-loading="loading" empty-text="暂无数据" stripe border style="width: 100%">
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
        <el-table-column prop="factoryNumber" label="厂号" width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.factoryNumber || '-' }}</template>
        </el-table-column>
        <el-table-column prop="originPlace" label="产地" width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.originPlace || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleView(row)">查看</el-button>
            <el-button v-if="canWrite" type="primary" link size="small" @click="handleEdit(row)">编辑</el-button>
            <!-- 续入到已有批次 (方案A 严格匹配续入, F006 采购补货) -->
            <el-button v-if="canWrite" type="warning" link size="small" @click="handleReplenish(row)">续入</el-button>
            <!-- A5: 生成物料批次标签 (SP4) -->
            <el-button
              v-if="canWrite"
              type="success"
              link
              size="small"
              :loading="labelGenerating === String(row.id)"
              @click="handleGenerateLabel(row)"
            >生成标签</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="viewMode === 'detail'" class="pagination-wrapper">
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
        <el-descriptions-item label="厂号">{{ viewRecord.factoryNumber || '-' }}</el-descriptions-item>
        <el-descriptions-item label="产地">{{ viewRecord.originPlace || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>

    <!-- 扫码查询结果 Dialog -->
    <el-dialog v-model="scanResultVisible" title="扫码查询结果" width="480px" destroy-on-close>
      <el-descriptions v-if="scanResult" :column="1" border>
        <el-descriptions-item v-for="(val, key) in scanResult" :key="String(key)" :label="String(key)">
          {{ val ?? '-' }}
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="scanResult = null">关闭</el-button>
      </template>
    </el-dialog>

    <!-- Create / Edit Dialog -->
    <el-dialog v-model="formDialogVisible" :title="formDialogTitle" width="500px" destroy-on-close>
      <el-form ref="formRef" :model="formData" :rules="formRules" label-width="80px">
        <el-form-item label="批次号" prop="batchNumber">
          <el-tooltip
            :content="editingId ? '批次号作为追溯标识, 创建后不可修改' : '批次号由系统自动生成 (一物一码), 防手敲重码, 无需填写'"
            placement="top-start"
          >
            <el-input
              v-model="formData.batchNumber"
              :placeholder="editingId ? '' : '系统自动生成'"
              :disabled="true"
            />
          </el-tooltip>
        </el-form-item>
        <el-form-item label="原料类型" prop="materialTypeId">
          <el-select v-model="formData.materialTypeId" placeholder="选择原料类型" filterable style="width: 100%">
            <el-option v-for="mt in materialTypes" :key="mt.id" :label="mt.name" :value="mt.id" />
          </el-select>
          <UpstreamMissingHint v-if="materialTypes.length === 0" description="本工厂暂无物料类型" target-module="warehouse" require-write action-text="去创建物料类型" contact-text="请联系仓库管理员先创建物料类型" @action="goCreate('/warehouse/material-types')" />
        </el-form-item>
        <el-form-item label="供应商" prop="supplierId">
          <el-select v-model="formData.supplierId" placeholder="选择供应商" filterable style="width: 100%">
            <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
          <UpstreamMissingHint v-if="suppliers.length === 0" description="本工厂暂无供应商" target-module="procurement" require-write action-text="去创建供应商" contact-text="请联系采购或管理员先创建供应商" @action="goCreate('/procurement/suppliers')" />
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
        <el-form-item label="厂号">
          <el-select
            v-model="formData.factoryNumber"
            placeholder="选择厂号"
            filterable
            clearable
            style="width: 100%"
            @change="handleManufacturerChange"
          >
            <el-option
              v-for="manufacturer in manufacturers"
              :key="manufacturer.id"
              :label="`${manufacturer.code} · ${manufacturer.name}`"
              :value="manufacturer.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="产地">
          <el-input v-model="formData.originPlace" placeholder="原料产地（如：山东寿光，可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="formSaving" @click="handleFormSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 续入到已有批次 dialog (方案A 严格匹配续入, F006 采购补货) -->
    <el-dialog
      v-model="replenishDialogVisible"
      :title="replenishTarget ? `续入到批次 — ${replenishTarget.materialTypeName || replenishTarget.materialName || ''}（${replenishTarget.batchNumber || ''}）` : '续入到已有批次'"
      width="520px"
    >
      <template v-if="replenishTarget">
        <!-- fool-proof Rule 2: 上下文必带身份信息 + Rule 1: 显式展示将沿用的单价/保质期 -->
        <el-alert type="info" :closable="false" show-icon class="replenish-hint">
          <template #title>
            本次续入将<strong>沿用该批次的单价与保质期</strong>，只累加数量。<br />
            如新到货的<strong>单价 / 保质期 / 供应商与本批次不同</strong>，请<strong>取消并改用「入库登记」新建批次</strong>，以保证成本核算与溯源正确。
          </template>
        </el-alert>
        <el-descriptions :column="2" border size="small" class="replenish-desc">
          <el-descriptions-item label="供应商">{{ replenishTarget.supplierName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="当前数量">
            {{ replenishTarget.quantity ?? replenishTarget.currentQuantity ?? replenishTarget.receiptQuantity ?? '-' }} {{ replenishUnit }}
          </el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="单价（沿用）">
            {{ replenishTarget.unitPrice != null ? formatAmount(replenishTarget.unitPrice) : '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="保质期（沿用）">{{ replenishTarget.expiryDate || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-form label-width="110px" class="replenish-form">
          <el-form-item label="续入数量" required>
            <el-input-number v-model="replenishForm.addQuantity" :min="0.01" :precision="2" :controls="false" style="width: 200px" />
            <span class="replenish-unit">{{ replenishUnit }}</span>
          </el-form-item>
          <el-form-item label="发起单类型">
            <el-input v-model="replenishForm.sourceDocType" placeholder="如：采购单（可选，记入续入流水供溯源）" />
          </el-form-item>
          <el-form-item label="发起单号">
            <el-input v-model="replenishForm.sourceDocId" placeholder="本次到货的采购单/送货单号（可选）" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="replenishForm.note" type="textarea" :rows="2" placeholder="可选" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="replenishDialogVisible = false">取消</el-button>
        <el-button type="warning" :loading="replenishSaving" @click="handleReplenishSubmit">确认续入</el-button>
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
  align-items: center;
}

.summary-hint {
  display: flex;
  gap: 16px;
  align-items: center;
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--text-color-secondary, #909399);
}

.summary-warning {
  color: var(--el-color-warning, #e6a23c);
}

.nested-batch-table {
  width: calc(100% - 48px);
  margin-left: 48px;
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
