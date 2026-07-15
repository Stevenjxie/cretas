<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useBusinessMode } from '@/composables/useBusinessMode';
import { get } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, Search, Plus } from '@element-plus/icons-vue';
import { formatAmount } from '@/utils/tableFormatters';
import type { TableRow } from '@/types/api';
import { RowActionMenu } from '@/components/list';
import { computeRowActions } from '@/composables/useRowActions';
import PriceHistoryDialog from '@/components/dialog/PriceHistoryDialog.vue';
import {
  createOpeningFgBatch,
  adjustFgBatch,
  voidFgBatch,
  type FgOpeningRequest,
  type FgAdjustRequest,
  type FgAdjustmentReferenceType,
} from '@/api/finishedGoods';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const { label } = useBusinessMode();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);
// T126: writes need production:read_write OR sales:read_write
const canEdit = computed(
  () => permissionStore.canWrite('production') || permissionStore.canWrite('sales')
);

function deriveStockStatus(row: TableRow): string {
  const available = Number((row as Record<string, unknown>).availableQuantity ?? (row as Record<string, unknown>).producedQuantity ?? 0);
  const total = Number((row as Record<string, unknown>).producedQuantity ?? 1);
  if (available <= 0) return 'OUT_OF_STOCK';
  if (available < total * 0.2) return 'LOW_STOCK';
  return 'IN_STOCK';
}
function rowActionsFor(row: TableRow) {
  return computeRowActions(
    'inventory',
    {
      status: deriveStockStatus(row),
      id: String(row.id || ''),
      canEdit: canEdit.value,
    },
    { canViewPrice: canViewPrice.value }
  );
}

// Issue #752 / #761 follow-up: view-detail 之前用 ElMessageBox.alert 占位,
// 现在跳独立 detail route (views/sales/finished-goods/detail.vue, router.name=SalesFinishedGoodsDetail).
//
// 2026-05-18 (#860 follow-up): view-price-history 接 GET /customer-price-history
// (跨客户的成交价历史 by product_type_id) — 展示在 PriceHistoryDialog 内.
// 成品库存视图行没有 customerId 上下文, dialog 自动 fallback 跨客户视图.

// ---------- PriceHistoryDialog state ----------
const priceHistoryDialog = ref({
  visible: false,
  productId: '',
  productLabel: '',
});

function openPriceHistory(row: TableRow) {
  const ptid = String(
    (row as TableRow & { productTypeId?: string; productType?: { id?: string } }).productTypeId
    || (row as TableRow & { productType?: { id?: string } }).productType?.id
    || ''
  );
  if (!ptid) {
    ElMessage.warning('当前行缺少产品类型 ID, 无法查询价格历史');
    return;
  }
  const lbl = String(
    row.productName
    || (row as TableRow & { productType?: { name?: string } }).productType?.name
    || ptid
  );
  priceHistoryDialog.value = {
    visible: true,
    productId: ptid,
    productLabel: lbl,
  };
}

// ---------- 期初入库 dialog ----------
const openingDialogVisible = ref(false);
const openingSubmitting = ref(false);
interface OpeningProductType {
  id: string;
  name: string;
  code?: string;
  unit?: string;
}
interface OpeningPackagingSpec {
  id: string;
  name: string;
  packageUnit: string;
  baseUnit: string;
  conversionFactor: number;
  active: boolean;
}
const productTypes = ref<OpeningProductType[]>([]);
const productTypesLoading = ref(false);
const openingPackagingSpecs = ref<OpeningPackagingSpec[]>([]);

const openingForm = ref<FgOpeningRequest>({
  productTypeId: '',
  batchNumber: '',
  producedQuantity: 0,
  unit: 'kg',
  productionDate: '',
  remark: '',
});

async function loadProductTypes() {
  if (!factoryId.value || productTypes.value.length > 0) return;
  productTypesLoading.value = true;
  try {
    const res = await get<OpeningProductType[]>(
      `/${factoryId.value}/product-types/active`
    );
    if (res.success && res.data) {
      productTypes.value = res.data;
    }
  } catch {
    // interceptor handles toast
  } finally {
    productTypesLoading.value = false;
  }
}

const openingUnitOptions = computed(() => {
  const selectedProduct = productTypes.value.find((product) => product.id === openingForm.value.productTypeId);
  return Array.from(new Set([
    selectedProduct?.unit,
    ...openingPackagingSpecs.value.map((spec) => spec.packageUnit),
  ].filter((unit): unit is string => !!unit)));
});

const openingPackagingOptions = computed(() => openingPackagingSpecs.value
  .filter((spec) => spec.active !== false && spec.packageUnit === openingForm.value.unit));

function onOpeningUnitChange() {
  const options = openingPackagingOptions.value;
  if (options.length === 1) openingForm.value.packagingSpecId = options[0].id;
  else if (!options.some((spec) => spec.id === openingForm.value.packagingSpecId)) {
    openingForm.value.packagingSpecId = undefined;
  }
}

async function onOpeningProductChange(productTypeId: string) {
  const selectedProduct = productTypes.value.find((product) => product.id === productTypeId);
  openingForm.value.unit = selectedProduct?.unit || 'kg';
  openingForm.value.packagingSpecId = undefined;
  openingPackagingSpecs.value = [];
  if (!factoryId.value || !productTypeId) return;
  try {
    const res = await get<OpeningPackagingSpec[]>(
      `/${factoryId.value}/product-types/${productTypeId}/packaging-specs`
    );
    openingPackagingSpecs.value = res.success && Array.isArray(res.data)
      ? res.data.filter((spec) => spec.active !== false)
      : [];
    onOpeningUnitChange();
  } catch {
    ElMessage.error('包装规格加载失败，请重试后再入库');
  }
}

function openOpeningDialog() {
  openingForm.value = {
    productTypeId: '',
    batchNumber: '',
    producedQuantity: 0,
    unit: 'kg',
    productionDate: '',
    remark: '',
  };
  openingPackagingSpecs.value = [];
  openingDialogVisible.value = true;
  void loadProductTypes();
}

async function submitOpening() {
  if (!factoryId.value) return;
  if (!openingForm.value.productTypeId) { ElMessage.warning('请选择产品类型'); return; }
  if (!openingForm.value.batchNumber.trim()) { ElMessage.warning('请输入批次号'); return; }
  if (!openingForm.value.producedQuantity || openingForm.value.producedQuantity <= 0) {
    ElMessage.warning('请输入有效的期初数量'); return;
  }
  if (!openingForm.value.unit.trim()) { ElMessage.warning('请输入单位'); return; }
  if (openingPackagingOptions.value.length > 1 && !openingForm.value.packagingSpecId) {
    ElMessage.warning('该产品有多个装箱规格，请选择本次入库使用的箱规'); return;
  }
  if (!openingForm.value.productionDate) { ElMessage.warning('请选择生产日期'); return; }

  openingSubmitting.value = true;
  try {
    const res = await createOpeningFgBatch(factoryId.value, {
      ...openingForm.value,
      batchNumber: openingForm.value.batchNumber.trim(),
      unit: openingForm.value.unit.trim(),
      remark: openingForm.value.remark?.trim() || undefined,
    });
    if (res.success) {
      ElMessage.success('期初入库成功');
      openingDialogVisible.value = false;
      void loadData();
    } else {
      ElMessage({ message: res.message || '期初入库失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: unknown) {
    const err = e as {
      response?: { status?: number; data?: { message?: string; actionHint?: string } };
      actionHint?: string;
    };
    const status = err?.response?.status;
    const msg = err?.response?.data?.message;
    const hint = err?.response?.data?.actionHint;

    if (status === 409) {
      // 批次号重复 — fool-proof: offer to view existing
      const displayMsg = msg || `批次号 "${openingForm.value.batchNumber}" 已存在`;
      try {
        await ElMessageBox.confirm(
          `${displayMsg}${hint ? `\n提示: ${hint}` : ''}`,
          '批次号重复',
          { confirmButtonText: '查看现有批次', cancelButtonText: '重新填写', type: 'warning' }
        );
        // Navigate to list with search for duplicate batch number
        searchKeyword.value = openingForm.value.batchNumber;
        openingDialogVisible.value = false;
        void loadData();
      } catch {
        // user chose to re-fill — keep dialog open
      }
    } else if (!err?.actionHint) {
      ElMessage({ message: msg || '期初入库失败', type: 'error', duration: 0, showClose: true });
    }
  } finally {
    openingSubmitting.value = false;
  }
}

// ---------- 调整库存 dialog ----------
const adjustDialogVisible = ref(false);
const adjustSubmitting = ref(false);
const adjustTargetRow = ref<TableRow | null>(null);

const ADJUST_REF_TYPES: Array<{ value: FgAdjustmentReferenceType; label: string }> = [
  { value: 'OPENING_CORRECTION', label: '期初修正' },
  { value: 'STOCKTAKE', label: '盘点差异' },
  { value: 'SCRAP', label: '报废损耗' },
  { value: 'OTHER', label: '其他' },
];

const adjustForm = ref<FgAdjustRequest>({
  adjustmentQuantity: 0,
  reason: '',
  referenceType: 'OPENING_CORRECTION',
});

function openAdjustDialog(row: TableRow) {
  adjustTargetRow.value = row;
  adjustForm.value = {
    adjustmentQuantity: 0,
    reason: '',
    referenceType: 'OPENING_CORRECTION',
  };
  adjustDialogVisible.value = true;
}

function adjustAvailableQty(row: TableRow): number {
  return (Number(row.producedQuantity) || 0)
    - (Number(row.shippedQuantity) || 0)
    - (Number(row.reservedQuantity) || 0);
}

async function submitAdjust() {
  if (!factoryId.value || !adjustTargetRow.value) return;
  if (adjustForm.value.adjustmentQuantity === 0) { ElMessage.warning('调整数量不能为 0'); return; }
  if (!adjustForm.value.reason.trim()) { ElMessage.warning('请填写调整原因'); return; }

  adjustSubmitting.value = true;
  try {
    const res = await adjustFgBatch(
      factoryId.value,
      String(adjustTargetRow.value.id),
      {
        ...adjustForm.value,
        reason: adjustForm.value.reason.trim(),
      }
    );
    if (res.success) {
      ElMessage.success('库存调整成功');
      adjustDialogVisible.value = false;
      void loadData();
    } else {
      ElMessage({ message: res.message || '库存调整失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: unknown) {
    const err = e as {
      response?: { status?: number; data?: { message?: string; actionHint?: string } };
      actionHint?: string;
    };
    const msg = err?.response?.data?.message;
    const hint = err?.response?.data?.actionHint;
    if (!err?.actionHint) {
      const fullMsg = msg
        ? `${msg}${hint ? `\n提示: ${hint}` : ''}`
        : '库存调整失败';
      ElMessage({ message: fullMsg, type: 'error', duration: 0, showClose: true });
    }
  } finally {
    adjustSubmitting.value = false;
  }
}

// ---------- 作废批次 ----------
async function handleVoidBatch(row: TableRow) {
  if (!factoryId.value) return;
  const batchLabel = row.batchNumber || row.id || '-';
  const productLabel = String(
    row.productName
    || (row as TableRow & { productType?: { name?: string } }).productType?.name
    || (row as TableRow & { productTypeId?: string }).productTypeId
    || '-'
  );

  try {
    // fool-proof Rule 2: context header with 批次号 + 产品名
    await ElMessageBox.confirm(
      '作废后该批次将不可恢复，且无法再出库或预留。请确认操作。',
      `作废批次 — ${batchLabel} (${productLabel})`,
      {
        confirmButtonText: '确认作废',
        cancelButtonText: '取消',
        type: 'warning',
        confirmButtonClass: 'el-button--danger',
      }
    );
  } catch {
    // user cancelled
    return;
  }

  try {
    const res = await voidFgBatch(factoryId.value, String(row.id));
    // 204 returns null data — success if no error thrown
    if (res === undefined || res === null || (res as { success?: boolean }).success !== false) {
      ElMessage.success(`批次 ${batchLabel} 已作废`);
      void loadData();
    } else {
      const msg = (res as { message?: string }).message;
      ElMessage({ message: msg || '作废失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: unknown) {
    const err = e as {
      response?: { status?: number; data?: { message?: string; actionHint?: string } };
      actionHint?: string;
    };
    const status = err?.response?.status;
    const msg = err?.response?.data?.message;
    const hint = err?.response?.data?.actionHint;

    if (status === 409) {
      // 已有出库/预留记录 — fool-proof: sticky error with backend message
      const fullMsg = msg
        ? `${msg}${hint ? `\n提示: ${hint}` : ''}`
        : `批次 ${batchLabel} 已有出库或预留记录，无法作废`;
      ElMessage({ message: fullMsg, type: 'error', duration: 0, showClose: true });
    } else if (!err?.actionHint) {
      ElMessage({ message: msg || '作废失败', type: 'error', duration: 0, showClose: true });
    }
  }
}

async function handleRowActionClick(actionId: string, row: TableRow): Promise<void> {
  const batchLabel = row.batchNumber || row.id || '-';
  switch (actionId) {
    case 'view-detail': {
      router.push({
        name: 'SalesFinishedGoodsDetail',
        params: { id: String(row.id || '') },
      });
      break;
    }
    case 'transfer': {
      try {
        await ElMessageBox.confirm(
          `批次 ${batchLabel} 的调拨工作流尚未配置. 是否前去工作流设计器配置 TRANSFER 流程?`,
          '调拨工作流未配置',
          { confirmButtonText: '去配置', cancelButtonText: '取消', type: 'info' }
        );
        router.push({ path: '/system/workflow-designer', query: { entityType: 'TRANSFER' } });
      } catch {
        // user cancel — no-op
      }
      break;
    }
    case 'view-price-history': {
      openPriceHistory(row);
      break;
    }
    case 'adjust-inventory': {
      openAdjustDialog(row);
      break;
    }
    case 'void-batch': {
      await handleVoidBatch(row);
      break;
    }
    default:
      ElMessage.warning(`该操作暂不支持: ${actionId}`);
  }
}
function openAiForRow(row: TableRow) {
  ElMessage.info(`AIChat: inventory/${row.batchNumber || row.id} — 接 AiEntryDrawer 待 Day 9`);
}

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const searchKeyword = ref('');

onMounted(() => loadData());

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params: Record<string, unknown> = {
      page: pagination.value.page,
      size: pagination.value.size,
    };
    const kw = searchKeyword.value.trim();
    if (kw) params.keyword = kw;
    const res = await get(`/${factoryId.value}/sales/finished-goods`, { params });
    if (res.success && res.data) {
      tableData.value = res.data.content || [];
      pagination.value.total = res.data.totalElements || 0;
    } else if (res.success === false) {
      ElMessage.error(res.message || '加载成品数据失败');
    }
  } catch { /* axios interceptor already displayed error toast */ }
  finally { loading.value = false; }
}

function handlePageChange(page: number) { pagination.value.page = page; loadData(); }
function handleSizeChange(size: number) { pagination.value.size = size; pagination.value.page = 1; loadData(); }
function handleSearch() { pagination.value.page = 1; loadData(); }
function handleSearchClear() { searchKeyword.value = ''; handleSearch(); }

function availableQty(row: TableRow) {
  return (Number(row.producedQuantity) || 0) - (Number(row.shippedQuantity) || 0) - (Number(row.reservedQuantity) || 0);
}

// Bug 2 fix (fool-proof-design Rule 1): 此前状态列纯按数量算 (充足/紧张/缺货), 从不读
// row.status —— 一个 DEFECTIVE (不良品/已隔离, 如退货入库批次) 的批次数量算下来会显示"充足",
// 与正常可售批次视觉上一模一样, 仓管员误以为可正常出货。后端已把它排除在 FEFO/可售查询外
// (status !== 'AVAILABLE'), 这里只是显示诚实化 —— 状态优先于数量判断。
function isDefective(row: TableRow): boolean {
  return String((row as Record<string, unknown>).status || '').toUpperCase() === 'DEFECTIVE';
}
function statusType(row: TableRow) {
  if (isDefective(row)) return 'danger';
  const avail = availableQty(row);
  if (avail <= 0) return 'danger';
  if (avail < (Number(row.producedQuantity) || 1) * 0.2) return 'warning';
  return 'success';
}
function statusLabel(row: TableRow) {
  if (isDefective(row)) return '不良品(已隔离)';
  const avail = availableQty(row);
  if (avail <= 0) return '已售罄';
  if (avail < (Number(row.producedQuantity) || 1) * 0.2) return '库存低';
  return '充足';
}
</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">{{ label('finishedGoods') }}</span>
            <span class="data-count">共 {{ pagination.total }} 批</span>
          </div>
          <div class="header-right">
            <el-input
              v-model="searchKeyword"
              placeholder="搜索批次号 / 产品名"
              clearable
              :prefix-icon="Search"
              style="width: 240px; margin-right: 12px;"
              @keyup.enter="handleSearch"
              @clear="handleSearchClear"
            />
            <el-button type="primary" @click="handleSearch">搜索</el-button>
            <el-button :icon="Refresh" @click="loadData">刷新</el-button>
            <el-button
              v-if="canEdit"
              type="success"
              :icon="Plus"
              @click="openOpeningDialog"
            >期初入库</el-button>
          </div>
        </div>
      </template>

      <!-- fool-proof: explain when 期初入库 is appropriate -->
      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 16px;"
        title="入库渠道说明"
        description="正常入库来自「生产完工确认」自动写入。期初入库仅用于系统上线初始化 / Excel 结存导入，请勿日常使用。"
      />

      <el-table :data="tableData" v-loading="loading" empty-text="暂无数据" stripe border style="width: 100%">
        <el-table-column prop="batchNumber" label="批次号" width="170" />
        <el-table-column label="产品" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.productName || row.productType?.name || row.productTypeId || '-' }}</template>
        </el-table-column>
        <el-table-column prop="producedQuantity" label="生产数量" width="110" align="right" />
        <el-table-column prop="shippedQuantity" label="已发货" width="100" align="right" />
        <el-table-column prop="reservedQuantity" label="已预留" width="100" align="right" />
        <el-table-column label="可用库存" width="110" align="right">
          <template #default="{ row }">
            <span :style="{ color: availableQty(row) <= 0 ? '#f56c6c' : '#303133', fontWeight: 600 }">
              {{ availableQty(row) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="70" align="center" />
        <el-table-column v-if="canViewPrice" prop="unitPrice" label="成本单价" width="110" align="right">
          <template #default="{ row }">{{ formatAmount(row.unitPrice) }}</template>
        </el-table-column>
        <el-table-column prop="productionDate" label="生产日期" width="120" />
        <el-table-column prop="expireDate" label="过期日期" width="120" />
        <el-table-column prop="storageLocation" label="库位" width="120" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row)" size="small">
              {{ statusLabel(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="center" fixed="right">
          <template #default="{ row }">
            <RowActionMenu
              :actions="rowActionsFor(row)"
              button-label="操作"
              @action-click="(id: string) => handleRowActionClick(id, row)"
              @ai-trigger="() => openAiForRow(row)"
            />
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination v-model:current-page="pagination.page" v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50]" :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange" @size-change="handleSizeChange" />
      </div>
    </el-card>

    <!-- 价格历史 Dialog — #860 follow-up, 接 GET /customer-price-history -->
    <PriceHistoryDialog
      v-model:visible="priceHistoryDialog.visible"
      :product-id="priceHistoryDialog.productId"
      :product-label="priceHistoryDialog.productLabel"
    />

    <!-- ===== 期初入库 Dialog ===== -->
    <el-dialog
      v-model="openingDialogVisible"
      title="期初入库"
      width="520px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom: 16px;"
        title="仅限期初导入"
        description="期初入库仅用于系统上线 / Excel 结存导入。批次号工厂内唯一，多 SKU 建议加后缀区分（如 46173-ZS / 46173-NJ / 46173-ZZB）。"
      />
      <el-form label-width="90px">
        <el-form-item label="产品" required>
          <el-select
            v-model="openingForm.productTypeId"
            placeholder="请选择产品类型"
            filterable
            clearable
            :loading="productTypesLoading"
            style="width: 100%"
            @change="onOpeningProductChange"
          >
            <el-option
              v-for="pt in productTypes"
              :key="pt.id"
              :label="pt.name"
              :value="pt.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="批次号" required>
          <el-input
            v-model="openingForm.batchNumber"
            placeholder="如 OPN-20260101-001 或 OPN-001-ZS"
            clearable
          />
        </el-form-item>
        <el-form-item label="期初数量" required>
          <el-input-number
            v-model="openingForm.producedQuantity"
            :min="0.01"
            :precision="2"
            style="width: 160px;"
          />
          <el-select
            v-model="openingForm.unit"
            placeholder="单位"
            style="width: 100px; margin-left: 8px;"
            @change="onOpeningUnitChange"
          >
            <el-option v-for="unit in openingUnitOptions" :key="unit" :label="unit" :value="unit" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="openingPackagingOptions.length > 0" label="包装规格"
          :required="openingPackagingOptions.length > 1">
          <el-select
            v-model="openingForm.packagingSpecId"
            :placeholder="openingPackagingOptions.length > 1 ? '请选择本次箱规' : '默认箱规'"
            style="width: 100%"
          >
            <el-option
              v-for="spec in openingPackagingOptions"
              :key="spec.id"
              :label="`1${spec.packageUnit}=${Number(spec.conversionFactor)}${spec.baseUnit}`"
              :value="spec.id"
            />
          </el-select>
          <div class="form-tip">同一 SKU 有多种装箱数量时，本次按哪一种入库必须明确选择</div>
        </el-form-item>
        <el-form-item label="生产日期" required>
          <el-date-picker
            v-model="openingForm.productionDate"
            type="date"
            placeholder="选择生产日期"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="openingForm.remark"
            type="textarea"
            :rows="2"
            placeholder="可选备注"
            clearable
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="openingDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="openingSubmitting" @click="submitOpening">确认入库</el-button>
      </template>
    </el-dialog>

    <!-- ===== 调整库存 Dialog ===== -->
    <el-dialog
      v-model="adjustDialogVisible"
      :title="`调整库存 — ${adjustTargetRow?.batchNumber || ''} (${adjustTargetRow?.productName || adjustTargetRow?.productTypeId || '-'})`"
      width="480px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <!-- fool-proof Rule 1: 预先显示当前库存边界 -->
      <el-descriptions v-if="adjustTargetRow" :column="3" border size="small" style="margin-bottom: 16px;">
        <el-descriptions-item label="可用库存">
          <span :style="{ fontWeight: 600, color: adjustAvailableQty(adjustTargetRow) <= 0 ? '#f56c6c' : '#303133' }">
            {{ adjustAvailableQty(adjustTargetRow) }} {{ adjustTargetRow.unit || '' }}
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="已发货">
          {{ Number(adjustTargetRow.shippedQuantity) || 0 }} {{ adjustTargetRow.unit || '' }}
        </el-descriptions-item>
        <el-descriptions-item label="已预留">
          {{ Number(adjustTargetRow.reservedQuantity) || 0 }} {{ adjustTargetRow.unit || '' }}
        </el-descriptions-item>
      </el-descriptions>

      <el-form label-width="90px">
        <el-form-item label="调整数量" required>
          <el-input-number
            v-model="adjustForm.adjustmentQuantity"
            :precision="2"
            placeholder="正数增加，负数减少"
            style="width: 180px;"
          />
          <span v-if="adjustTargetRow" style="margin-left: 8px; font-size: 12px; color: #909399;">
            调整后可用:
            <strong :style="{ color: (adjustAvailableQty(adjustTargetRow) + adjustForm.adjustmentQuantity) < 0 ? '#f56c6c' : '#303133' }">
              {{ (adjustAvailableQty(adjustTargetRow) + adjustForm.adjustmentQuantity).toFixed(2) }}
            </strong>
            {{ adjustTargetRow.unit || '' }}
          </span>
        </el-form-item>
        <el-form-item label="原因类型" required>
          <el-select v-model="adjustForm.referenceType" style="width: 100%">
            <el-option
              v-for="opt in ADJUST_REF_TYPES"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="调整原因" required>
          <el-input
            v-model="adjustForm.reason"
            type="textarea"
            :rows="2"
            placeholder="请说明调整原因"
            clearable
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="adjustSubmitting"
          :disabled="adjustForm.adjustmentQuantity === 0"
          @click="submitAdjust"
        >确认调整</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper { height: 100%; width: 100%; display: flex; flex-direction: column; }
.page-card { flex: 1; display: flex; flex-direction: column;
  :deep(.el-card__header) { padding: 16px 20px; border-bottom: 1px solid #ebeef5; }
  :deep(.el-card__body) { flex: 1; display: flex; flex-direction: column; padding: 20px; }
}
.card-header { display: flex; justify-content: space-between; align-items: center;
  .header-left { display: flex; align-items: baseline; gap: 12px;
    .page-title { font-size: 16px; font-weight: 600; color: #303133; }
    .data-count { font-size: 13px; color: #909399; }
  }
}
.pagination-wrapper { display: flex; justify-content: flex-end; padding-top: 16px; border-top: 1px solid #ebeef5; margin-top: 16px; }
</style>
