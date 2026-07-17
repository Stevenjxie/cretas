<script setup lang="ts">
/**
 * StartPurchaseDialog.vue — 从销售订单一键生成采购建议弹窗.
 *
 * 客户原话 (t2b 行1867-1902 [61:17-62:02]):
 * "做个弹窗…我直接点开始采购…不然新增有点麻烦，全部手写没意义"。
 *
 * 防呆 Rule 1: 弹窗打开即展示「品名/需要量/现有库存/净需求」上下文，不让用户手填。
 * 防呆 Rule 2: 标题带 SO 编号 + 客户名上下文。
 * 诚实 null: hasBom=false 时不伪造数据，显示无 BOM 提示。
 *
 * 后端路径: GET /api/mobile/{factoryId}/purchase/orders/suggestions/from-so/{salesOrderId}
 * 确认后:   POST /api/mobile/{factoryId}/purchase/orders  (CreatePurchaseOrderRequest)
 */
import { ref, watch, computed } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { get, post } from '@/api/request';
import {
  canonicalUnitCode,
  displayUnit,
  formatPriceUnit,
  mergeCanonicalUnitOptions,
  pricingAmountPreview,
  purchaseOrderPricingPayload,
  resolvePurchaseSuggestionUnits,
} from '@/utils/unitPricing';

// ── types ──────────────────────────────────────────────────────────────────

interface SuggestionItem {
  materialTypeId: string;
  materialName: string;
  materialCategory: string;
  sourceProductName: string;
  requiredQuantity: number;
  unit: string;
  quantityUnit?: string;
  currentStock: number;
  netRequired: number;
  stockSufficient: boolean;
  referenceUnitPrice: number | null;
  priceUnit?: string | null;
  referencePriceUnit?: string | null;
  lineAmount?: number | null;
  convertedPricingQuantity?: number | null;
}

interface PurchaseSuggestionResponse {
  salesOrderId: string;
  salesOrderNumber: string;
  customerName: string;
  hasBom: boolean;
  items: SuggestionItem[];
}

interface SupplierOption {
  id: string;
  name: string;
  supplierCode?: string | null;
}

interface MaterialPackagingHierarchy {
  level1Unit?: string | null;
  level1PerLevel2?: number | null;
  level2Unit?: string | null;
  level2PerLevel3?: number | null;
  level3Unit?: string | null;
}

// editable item for PO creation
interface EditableItem {
  materialTypeId: string;
  materialName: string;
  materialCategory: string;
  quantity: number;
  unit: string;
  quantityUnit: string;
  unitPrice: number | null;
  priceUnit: string;
  lineAmount: number | null;
  convertedPricingQuantity: number | null;
  specification: string;
  boxQuantity: number | null;
  remark: string;
  _requiredQuantity: number;
  _currentStock: number;
  _netRequired: number;
  _stockSufficient: boolean;
}

// ── props / emits ──────────────────────────────────────────────────────────

const props = defineProps<{
  modelValue: boolean;
  factoryId: string;
  salesOrderId: string;
  salesOrderNumber: string;
  customerName?: string;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  /** emitted after PO created, with new PO id */
  created: [poId: string];
}>();

// ── state ──────────────────────────────────────────────────────────────────

const loading = ref(false);
const submitting = ref(false);
const suggestion = ref<PurchaseSuggestionResponse | null>(null);
const editableItems = ref<EditableItem[]>([]);
const supplierId = ref('');
const suppliers = ref<SupplierOption[]>([]);
const suppliersLoading = ref(false);
const packagingByMaterial = ref<Record<string, MaterialPackagingHierarchy>>({});
const factoryToday = () =>
  new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date());
const orderDateStr = ref(factoryToday());
const expectedDeliveryDate = ref('');
const remark = ref('');

// ── computed ───────────────────────────────────────────────────────────────

const dialogTitle = computed(
  () =>
    `开始采购 — ${props.salesOrderNumber}${props.customerName ? ` (${props.customerName})` : ''}`
);

const hasNetRequired = computed(() =>
  editableItems.value.some((it) => !it._stockSufficient)
);

const categoryLabel = (cat: string) => {
  const map: Record<string, string> = {
    RAW: '原料',
    AUXILIARY: '辅料',
    PACKAGING: '包材',
  };
  return map[cat] ?? cat;
};

function amountPreview(item: EditableItem) {
  return pricingAmountPreview(item);
}

function quantityUnitOptions(item: EditableItem) {
  const packaging = packagingByMaterial.value[item.materialTypeId];
  return mergeCanonicalUnitOptions(
    packaging?.level1Unit,
    packaging?.level2Unit,
    packaging?.level3Unit,
    item.quantityUnit,
    item.unit,
  ).map((unit) => ({ value: unit, label: displayUnit(unit) }));
}

function priceUnitOptions(item: EditableItem) {
  return mergeCanonicalUnitOptions(
    quantityUnitOptions(item).map((option) => option.value),
    item.priceUnit,
  ).map((unit) => ({ value: unit, label: formatPriceUnit(unit) }));
}

function packagingSummary(item: EditableItem): string {
  const packaging = packagingByMaterial.value[item.materialTypeId];
  if (!packaging?.level1Unit || !packaging.level2Unit || !packaging.level1PerLevel2) return '';
  const level2 = `1 ${displayUnit(packaging.level2Unit)} = ${packaging.level1PerLevel2} ${displayUnit(packaging.level1Unit)}`;
  if (!packaging.level3Unit || !packaging.level2PerLevel3) return level2;
  return `${level2}；1 ${displayUnit(packaging.level3Unit)} = ${packaging.level2PerLevel3} ${displayUnit(packaging.level2Unit)}`;
}

function recalculateBoxQuantity(item: EditableItem) {
  const packaging = packagingByMaterial.value[item.materialTypeId];
  const quantity = Number(item.quantity);
  const quantityUnit = canonicalUnitCode(item.quantityUnit || item.unit);
  const level1Unit = canonicalUnitCode(packaging?.level1Unit);
  const level2Unit = canonicalUnitCode(packaging?.level2Unit);
  const level1PerLevel2 = Number(packaging?.level1PerLevel2);
  if (!Number.isFinite(quantity) || quantity <= 0 || !level2Unit) {
    item.boxQuantity = null;
  } else if (quantityUnit === level2Unit) {
    item.boxQuantity = quantity;
  } else if (quantityUnit === level1Unit && Number.isFinite(level1PerLevel2) && level1PerLevel2 > 0) {
    item.boxQuantity = Math.round((quantity / level1PerLevel2) * 10000) / 10000;
  } else {
    item.boxQuantity = null;
  }
}

function quantityUnitToLevel1Factor(item: EditableItem, unit: string): number | null {
  const packaging = packagingByMaterial.value[item.materialTypeId];
  const candidate = canonicalUnitCode(unit);
  const level1Unit = canonicalUnitCode(packaging?.level1Unit);
  const level2Unit = canonicalUnitCode(packaging?.level2Unit);
  const level3Unit = canonicalUnitCode(packaging?.level3Unit);
  const level1PerLevel2 = Number(packaging?.level1PerLevel2);
  const level2PerLevel3 = Number(packaging?.level2PerLevel3);

  if (!candidate) return null;
  if (candidate === level1Unit) return 1;
  if (candidate === level2Unit && Number.isFinite(level1PerLevel2) && level1PerLevel2 > 0) {
    return level1PerLevel2;
  }
  if (candidate === level3Unit
    && Number.isFinite(level1PerLevel2) && level1PerLevel2 > 0
    && Number.isFinite(level2PerLevel3) && level2PerLevel3 > 0) {
    return level1PerLevel2 * level2PerLevel3;
  }
  return null;
}

function clearSuggestionAmount(item: EditableItem) {
  // Backend preview values describe the original suggestion quantity only.
  item.lineAmount = null;
  item.convertedPricingQuantity = null;
}

function onQuantityChange(item: EditableItem) {
  clearSuggestionAmount(item);
  recalculateBoxQuantity(item);
}

function onQuantityUnitChange(item: EditableItem) {
  const previousUnit = canonicalUnitCode(item.unit);
  const nextUnit = canonicalUnitCode(item.quantityUnit);
  if (!nextUnit) {
    item.quantityUnit = previousUnit;
    return;
  }

  if (previousUnit && previousUnit !== nextUnit) {
    const previousFactor = quantityUnitToLevel1Factor(item, previousUnit);
    const nextFactor = quantityUnitToLevel1Factor(item, nextUnit);
    if (previousFactor == null || nextFactor == null) {
      ElMessage.warning('该物料未配置这两个单位之间的包装换算，不能直接切换单位');
      item.quantityUnit = previousUnit;
      return;
    }
    item.quantity = Math.round((Number(item.quantity) * previousFactor / nextFactor) * 10000) / 10000;
  }

  item.quantityUnit = nextUnit;
  item.unit = nextUnit;
  clearSuggestionAmount(item);
  recalculateBoxQuantity(item);
}

async function loadSupplierOptions() {
  suppliersLoading.value = true;
  try {
    const res = await get<{ content?: SupplierOption[] }>(`/${props.factoryId}/suppliers`, {
      params: { page: 1, size: 100 },
    });
    suppliers.value = res.success && Array.isArray(res.data?.content) ? res.data.content : [];
  } finally {
    suppliersLoading.value = false;
  }
}

async function ensurePackagingLoaded(materialTypeId: string) {
  if (!materialTypeId || packagingByMaterial.value[materialTypeId]) return;
  try {
    const res = await get<MaterialPackagingHierarchy | null>(
      `/${props.factoryId}/material-packaging/by-material/${materialTypeId}`,
    );
    packagingByMaterial.value[materialTypeId] = res.data || {};
  } catch {
    packagingByMaterial.value[materialTypeId] = {};
  }
}

// ── load suggestion ────────────────────────────────────────────────────────

async function loadSuggestion() {
  if (!props.factoryId || !props.salesOrderId) return;
  loading.value = true;
  suggestion.value = null;
  editableItems.value = [];
  try {
    const res = await get<PurchaseSuggestionResponse>(
      `/${props.factoryId}/purchase/orders/suggestions/from-so/${props.salesOrderId}`
    );
    if (res.success && res.data) {
      suggestion.value = res.data;
      // Pre-fill editable rows from suggestion, defaulting quantity to netRequired
      editableItems.value = res.data.items.map((it) => {
        const units = resolvePurchaseSuggestionUnits(it);
        return {
        materialTypeId: it.materialTypeId,
        materialName: it.materialName,
        materialCategory: it.materialCategory,
        quantity: it.netRequired > 0 ? it.netRequired : it.requiredQuantity,
        unit: units.quantityUnit,
        quantityUnit: units.quantityUnit,
        unitPrice: it.referenceUnitPrice ?? null,
        priceUnit: units.priceUnit,
        lineAmount: it.lineAmount ?? null,
        convertedPricingQuantity: it.convertedPricingQuantity ?? null,
        specification: '',
        boxQuantity: null,
        remark: '',
        _requiredQuantity: it.requiredQuantity,
        _currentStock: it.currentStock,
        _netRequired: it.netRequired,
        _stockSufficient: it.stockSufficient,
        };
      });
      await Promise.all(editableItems.value.map((item) => ensurePackagingLoaded(item.materialTypeId)));
      editableItems.value.forEach(recalculateBoxQuantity);
    }
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      supplierId.value = '';
      orderDateStr.value = factoryToday();
      expectedDeliveryDate.value = '';
      remark.value = '';
      void loadSupplierOptions();
      void loadSuggestion();
    }
  },
  { immediate: false }
);

// ── create PO ──────────────────────────────────────────────────────────────

async function handleConfirm() {
  if (editableItems.value.length === 0) {
    await ElMessageBox.alert(
      '没有可采购的原料行（BOM 未配置或库存充足），无需创建采购单。',
      '无需采购',
      { type: 'info', confirmButtonText: '确定' }
    );
    return;
  }
  const invalidQuantityIndex = editableItems.value.findIndex((item) => !(Number(item.quantity) > 0));
  if (invalidQuantityIndex >= 0) {
    ElMessage.warning(`第 ${invalidQuantityIndex + 1} 行采购数量必须大于 0`);
    return;
  }
  const missingQuantityUnitIndex = editableItems.value.findIndex((item) => !canonicalUnitCode(item.quantityUnit));
  if (missingQuantityUnitIndex >= 0) {
    ElMessage.warning(`第 ${missingQuantityUnitIndex + 1} 行请选择数量单位`);
    return;
  }
  const missingPriceUnitIndex = editableItems.value.findIndex(
    (item) => Number(item.unitPrice) > 0 && !canonicalUnitCode(item.priceUnit),
  );
  if (missingPriceUnitIndex >= 0) {
    ElMessage.warning(`第 ${missingPriceUnitIndex + 1} 行有参考单价，请选择计价单位`);
    return;
  }

  submitting.value = true;
  try {
    const payload = {
      supplierId: supplierId.value || null,
      purchaseType: 'DIRECT',
      orderDate: orderDateStr.value,
      expectedDeliveryDate: expectedDeliveryDate.value || null,
      salesOrderId: props.salesOrderId,
      remark: remark.value || `基于销售订单 ${props.salesOrderNumber} 自动生成`,
      items: editableItems.value.map((it) => purchaseOrderPricingPayload({
        materialTypeId: it.materialTypeId,
        materialName: it.materialName,
        quantity: it.quantity,
        unit: it.unit,
        quantityUnit: it.quantityUnit,
        unitPrice: it.unitPrice ?? null,
        priceUnit: it.priceUnit,
        specification: it.specification || null,
        boxQuantity: it.boxQuantity,
        remark: it.remark || '',
      })),
    };

    const res = await post<{ id: string }>(
      `/${props.factoryId}/purchase/orders`,
      payload
    );
    if (res.success && res.data?.id) {
      ElMessage.success('采购单创建成功');
      emit('update:modelValue', false);
      emit('created', res.data.id);
    }
  } finally {
    submitting.value = false;
  }
}

function handleClose() {
  emit('update:modelValue', false);
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="dialogTitle"
    width="96%"
    top="4vh"
    destroy-on-close
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <!-- Loading skeleton -->
    <div v-if="loading" class="sp-loading">
      <el-skeleton :rows="5" animated />
    </div>

    <!-- No BOM configured -->
    <el-empty
      v-else-if="suggestion && !suggestion.hasBom"
      description="该销售订单关联的产品尚未配置 BOM，无法自动展开原料需求。请先在「工序配置」中配置 BOM，再使用开始采购功能。"
    >
      <template #extra>
        <el-button type="primary" @click="handleClose">知道了</el-button>
      </template>
    </el-empty>

    <!-- Main content -->
    <template v-else-if="suggestion">
      <!-- Context header (防呆 Rule 2) -->
      <div class="sp-context-header">
        <el-alert
          :title="`销售订单: ${suggestion.salesOrderNumber}  |  客户: ${suggestion.customerName || '—'}  |  共 ${suggestion.items.length} 种原辅包材`"
          type="info"
          :closable="false"
          show-icon
        />
        <el-alert
          v-if="hasNetRequired"
          title="橙色行: 库存不足需采购（净需求 > 0）；绿色行: 库存充足可跳过。可手动调整数量或删除行。"
          type="warning"
          :closable="false"
          class="sp-hint"
        />
      </div>

      <!-- Editable items table -->
      <el-table
        :data="editableItems"
        stripe
        size="small"
        style="width: 100%; margin-top: 12px"
        :row-class-name="(row: { row: EditableItem }) => row.row._stockSufficient ? 'sp-row-sufficient' : 'sp-row-needed'"
      >
        <el-table-column label="分类" width="70">
          <template #default="{ row }">
            <el-tag
              :type="row.materialCategory === 'RAW' ? 'danger' : row.materialCategory === 'AUXILIARY' ? 'warning' : 'info'"
              size="small"
            >{{ categoryLabel(row.materialCategory) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="materialName" label="原料名称" min-width="140" />
        <el-table-column label="需要量" width="100" align="right">
          <template #default="{ row }">
            <span class="sp-qty">{{ row._requiredQuantity?.toFixed(2) }} {{ row.unit }}</span>
          </template>
        </el-table-column>
        <el-table-column label="现有库存" width="100" align="right">
          <template #default="{ row }">
            <span :class="row._stockSufficient ? 'sp-stock-ok' : 'sp-stock-low'">
              {{ row._currentStock?.toFixed(2) }} {{ row.unit }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="净需求" width="100" align="right">
          <template #default="{ row }">
            <span :class="row._stockSufficient ? 'sp-stock-ok' : 'sp-net-needed'">
              {{ row._netRequired?.toFixed(2) }} {{ row.unit }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="采购数量 / 单位" width="220">
          <template #default="{ row }">
            <div class="sp-inline-editor">
              <el-input-number
                v-model="row.quantity"
                :min="0"
                :precision="2"
                size="small"
                style="width: 125px"
                controls-position="right"
                @change="onQuantityChange(row)"
              />
              <el-select
                v-model="row.quantityUnit"
                filterable
                size="small"
                style="width: 82px"
                placeholder="单位"
                @change="onQuantityUnitChange(row)"
              >
                <el-option
                  v-for="option in quantityUnitOptions(row)"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="包装规格" width="190">
          <template #default="{ row }">
            <el-input v-model="row.specification" size="small" placeholder="选填，如 10kg/箱" clearable />
            <div v-if="packagingSummary(row)" class="sp-packaging-hint">{{ packagingSummary(row) }}</div>
            <div v-if="row.boxQuantity != null" class="sp-packaging-hint">折算箱数：{{ row.boxQuantity }}</div>
          </template>
        </el-table-column>
        <el-table-column label="参考单价 / 计价单位" width="200">
          <template #default="{ row }">
            <div class="sp-inline-editor">
              <el-input-number
                v-model="row.unitPrice"
                :min="0"
                :precision="4"
                size="small"
                style="width: 108px"
                controls-position="right"
                :placeholder="row.unitPrice == null ? '未知' : ''"
              />
              <el-select v-model="row.priceUnit" size="small" style="width: 82px" placeholder="计价单位">
                <el-option
                  v-for="option in priceUnitOptions(row)"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
            </div>
            <div class="sp-price-label">{{ formatPriceUnit(row.priceUnit) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="金额预览" width="150" align="right">
          <template #default="{ row }">
            <span v-if="amountPreview(row).amount != null">{{ amountPreview(row).amount?.toFixed(2) }} 元</span>
            <span v-else class="sp-pending-amount">{{ amountPreview(row).message }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70" align="center">
          <template #default="{ $index }">
            <el-button
              type="danger"
              link
              size="small"
              @click="editableItems.splice($index, 1)"
            >删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- PO meta -->
      <div class="sp-meta-row" style="margin-top: 16px; display: flex; gap: 16px; align-items: flex-start">
        <el-form-item label="供应商（选填）" style="flex: 1.4">
          <el-select
            v-model="supplierId"
            :loading="suppliersLoading"
            placeholder="可稍后在采购草稿中补填"
            filterable
            clearable
            size="small"
            style="width: 100%"
          >
            <el-option
              v-for="supplier in suppliers"
              :key="supplier.id"
              :label="supplier.supplierCode ? `${supplier.name} (${supplier.supplierCode})` : supplier.name"
              :value="supplier.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="下单日期" style="flex: 1">
          <el-input v-model="orderDateStr" type="date" size="small" />
        </el-form-item>
        <el-form-item label="期望交货" style="flex: 1">
          <el-input v-model="expectedDeliveryDate" type="date" size="small" />
        </el-form-item>
        <el-form-item label="备注" style="flex: 2">
          <el-input
            v-model="remark"
            type="textarea"
            :rows="2"
            size="small"
            :placeholder="`基于销售订单 ${salesOrderNumber} 自动生成`"
          />
        </el-form-item>
      </div>
      <el-alert
        type="info"
        :closable="false"
        style="margin-top: 8px"
        title="将先创建采购草稿；供应商可稍后补填，但提交审批前必须选择。"
      />
    </template>

    <template #footer>
      <el-button @click="handleClose">取消</el-button>
      <el-button
        v-if="suggestion && suggestion.hasBom"
        type="primary"
        :loading="submitting"
        :disabled="editableItems.length === 0"
        @click="handleConfirm"
      >
        确认创建采购单 ({{ editableItems.length }} 行)
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.sp-loading {
  padding: 20px 0;
}
.sp-context-header {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.sp-hint {
  margin-top: 4px;
}
.sp-qty {
  color: #606266;
}
.sp-stock-ok {
  color: #67c23a;
  font-weight: 500;
}
.sp-stock-low {
  color: #e6a23c;
}
.sp-net-needed {
  color: #f56c6c;
  font-weight: 600;
}
.sp-price-label {
  margin-bottom: 4px;
  color: #909399;
  font-size: 12px;
}
.sp-inline-editor {
  display: flex;
  gap: 6px;
  align-items: center;
}
.sp-packaging-hint {
  margin-top: 4px;
  color: #909399;
  font-size: 12px;
  line-height: 1.35;
}
.sp-pending-amount {
  color: #909399;
  font-size: 12px;
}
:deep(.sp-row-needed) td {
  background-color: #fff8f0 !important;
}
:deep(.sp-row-sufficient) td {
  color: #909399;
}
</style>
