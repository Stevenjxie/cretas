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

// ── types ──────────────────────────────────────────────────────────────────

interface SuggestionItem {
  materialTypeId: string;
  materialName: string;
  materialCategory: string;
  sourceProductName: string;
  requiredQuantity: number;
  unit: string;
  currentStock: number;
  netRequired: number;
  stockSufficient: boolean;
  referenceUnitPrice: number | null;
}

interface PurchaseSuggestionResponse {
  salesOrderId: string;
  salesOrderNumber: string;
  customerName: string;
  hasBom: boolean;
  items: SuggestionItem[];
}

// editable item for PO creation
interface EditableItem {
  materialTypeId: string;
  materialName: string;
  materialCategory: string;
  quantity: number;
  unit: string;
  unitPrice: number | null;
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
const orderDateStr = ref(new Date().toISOString().slice(0, 10));
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
      editableItems.value = res.data.items.map((it) => ({
        materialTypeId: it.materialTypeId,
        materialName: it.materialName,
        materialCategory: it.materialCategory,
        quantity: it.netRequired > 0 ? it.netRequired : it.requiredQuantity,
        unit: it.unit,
        unitPrice: it.referenceUnitPrice ?? null,
        remark: '',
        _requiredQuantity: it.requiredQuantity,
        _currentStock: it.currentStock,
        _netRequired: it.netRequired,
        _stockSufficient: it.stockSufficient,
      }));
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
      orderDateStr.value = new Date().toISOString().slice(0, 10);
      remark.value = '';
      loadSuggestion();
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

  submitting.value = true;
  try {
    const payload = {
      supplierId: supplierId.value || null,
      purchaseType: 'DIRECT',
      orderDate: orderDateStr.value,
      salesOrderId: props.salesOrderId,
      remark: remark.value || `基于销售订单 ${props.salesOrderNumber} 自动生成`,
      items: editableItems.value.map((it) => ({
        materialTypeId: it.materialTypeId,
        materialName: it.materialName,
        quantity: it.quantity,
        unit: it.unit,
        unitPrice: it.unitPrice ?? null,
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
    width="860px"
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
        <el-table-column label="采购数量" width="140">
          <template #default="{ row }">
            <el-input-number
              v-model="row.quantity"
              :min="0"
              :precision="2"
              size="small"
              style="width: 120px"
              controls-position="right"
            />
          </template>
        </el-table-column>
        <el-table-column label="参考单价" width="120">
          <template #default="{ row }">
            <el-input-number
              v-model="row.unitPrice"
              :min="0"
              :precision="4"
              size="small"
              style="width: 100px"
              controls-position="right"
              :placeholder="row.unitPrice == null ? '未知' : ''"
            />
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
        <el-form-item label="下单日期" style="flex: 1">
          <el-input v-model="orderDateStr" type="date" size="small" />
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
:deep(.sp-row-needed) td {
  background-color: #fff8f0 !important;
}
:deep(.sp-row-sufficient) td {
  color: #909399;
}
</style>
