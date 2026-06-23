<script setup lang="ts">
/**
 * MergePurchaseDialog.vue — 多销售订单合并生成一张采购单的弹窗.
 *
 * 转录行3650: 多个销售订单用"加号"逐个追加合并成一张采购单 —
 * "我可能要把好几个订单的料合在一起一次买"。
 *
 * 与单 SO 的 StartPurchaseDialog 区别:
 * - 跨多张 SO 按物料聚合需求 (SO1 需牛腱50 + SO2 需牛腱30 → 合并 80)，库存统一扣一次。
 * - 顶部显示参与合并的 SO 号列表 + 客户列表 (可能多客户)。
 * - 每行标注「合并自哪几张 SO」(sourceSalesOrderNumbers)。
 * - 逐 SO 摘要诚实暴露「某张 SO 无 BOM 未贡献料」，不静默。
 *
 * 防呆 Rule 1: 每行展示「需要量/现有库存/净需求」，采购数量默认 = 净需求，不让用户手填。
 * 防呆 Rule 2: 顶部带 SO 列表 + 客户上下文。
 * 防呆 Rule 5: 全部无 BOM 时给明确空状态 + 引导，不卡死。
 *
 * 后端: POST /api/mobile/{factoryId}/purchase/orders/suggestions/from-so-multi
 *       body: { salesOrderIds: [...] }
 * 确认: POST /api/mobile/{factoryId}/purchase/orders (CreatePurchaseOrderRequest)
 */
import { ref, watch, computed } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { post } from '@/api/request';

// ── types ──────────────────────────────────────────────────────────────────

interface MultiSuggestionItem {
  materialTypeId: string;
  materialName: string;
  materialCategory: string;
  sourceProductName: string;
  sourceSalesOrderNumbers: string[];
  requiredQuantity: number;
  unit: string;
  currentStock: number;
  netRequired: number;
  stockSufficient: boolean;
  referenceUnitPrice: number | null;
}

interface SoSummary {
  salesOrderId: string;
  salesOrderNumber: string;
  customerName: string;
  hasBom: boolean;
}

interface MultiSuggestionResponse {
  salesOrderIds: string[];
  salesOrderNumbers: string[];
  customerNames: string[];
  hasBom: boolean;
  items: MultiSuggestionItem[];
  soSummaries: SoSummary[];
}

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
  _sourceSalesOrderNumbers: string[];
}

/** 入参: 用户勾选/追加的 SO 列表 (id + number + customer). */
interface SelectedSo {
  id: string;
  number: string;
  customer?: string;
}

// ── props / emits ──────────────────────────────────────────────────────────

const props = defineProps<{
  modelValue: boolean;
  factoryId: string;
  /** 勾选/追加的 SO 列表 (≥1) */
  salesOrders: SelectedSo[];
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  created: [poId: string];
}>();

// ── state ──────────────────────────────────────────────────────────────────

const loading = ref(false);
const submitting = ref(false);
const suggestion = ref<MultiSuggestionResponse | null>(null);
const editableItems = ref<EditableItem[]>([]);
const supplierId = ref('');
const orderDateStr = ref(new Date().toISOString().slice(0, 10));
const remark = ref('');

// ── computed ───────────────────────────────────────────────────────────────

const dialogTitle = computed(() => `合并采购 — 共 ${props.salesOrders.length} 张销售订单`);

const hasNetRequired = computed(() =>
  editableItems.value.some((it) => !it._stockSufficient)
);

/** 逐 SO 摘要里无 BOM 的那些 (诚实提示). */
const noBomSummaries = computed(
  () => suggestion.value?.soSummaries.filter((s) => !s.hasBom) ?? []
);

const categoryLabel = (cat: string) => {
  const map: Record<string, string> = {
    RAW: '原料',
    AUXILIARY: '辅料',
    PACKAGING: '包材',
  };
  return map[cat] ?? cat;
};

// ── load merged suggestion ───────────────────────────────────────────────────

async function loadSuggestion() {
  if (!props.factoryId || props.salesOrders.length === 0) return;
  loading.value = true;
  suggestion.value = null;
  editableItems.value = [];
  try {
    const res = await post<MultiSuggestionResponse>(
      `/${props.factoryId}/purchase/orders/suggestions/from-so-multi`,
      { salesOrderIds: props.salesOrders.map((s) => s.id) }
    );
    if (res.success && res.data) {
      suggestion.value = res.data;
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
        _sourceSalesOrderNumbers: it.sourceSalesOrderNumbers ?? [],
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
      '没有可采购的原料行（所选订单均未配置 BOM 或库存充足），无需创建采购单。',
      '无需采购',
      { type: 'info', confirmButtonText: '确定' }
    );
    return;
  }

  submitting.value = true;
  try {
    const soNumbers = suggestion.value?.salesOrderNumbers ?? props.salesOrders.map((s) => s.number);
    // 关联首张 SO (后端 PO.salesOrderId 单值); 全部 SO 号写进备注供溯源.
    const primarySoId = suggestion.value?.salesOrderIds[0] ?? props.salesOrders[0]?.id ?? null;
    const payload = {
      supplierId: supplierId.value || null,
      purchaseType: 'DIRECT',
      orderDate: orderDateStr.value,
      salesOrderId: primarySoId,
      remark:
        remark.value || `合并销售订单 ${soNumbers.join(' / ')} 自动生成`,
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
      ElMessage.success('合并采购单创建成功');
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
    width="920px"
    destroy-on-close
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <div v-if="loading" class="mp-loading">
      <el-skeleton :rows="5" animated />
    </div>

    <!-- 全部无 BOM (防呆 Rule 5) -->
    <el-empty
      v-else-if="suggestion && !suggestion.hasBom"
      description="所选销售订单关联的产品均未配置 BOM，无法自动展开原料需求。请先在「工序配置」中配置 BOM，再使用合并采购功能。"
    >
      <template #extra>
        <el-button type="primary" @click="handleClose">知道了</el-button>
      </template>
    </el-empty>

    <template v-else-if="suggestion">
      <!-- Context header (防呆 Rule 2) -->
      <div class="mp-context-header">
        <el-alert
          :title="`合并 ${suggestion.salesOrderNumbers.length} 张订单: ${suggestion.salesOrderNumbers.join(' / ')}`"
          type="info"
          :closable="false"
          show-icon
        />
        <el-alert
          :title="`客户: ${suggestion.customerNames.length ? suggestion.customerNames.join('、') : '—'}  |  合并后共 ${suggestion.items.length} 种原辅包材`"
          type="info"
          :closable="false"
        />
        <!-- 诚实暴露: 某些 SO 无 BOM 未贡献料 -->
        <el-alert
          v-if="noBomSummaries.length"
          :title="`注意: ${noBomSummaries.map((s) => s.salesOrderNumber).join('、')} 未配置 BOM，未贡献原料需求。`"
          type="warning"
          :closable="false"
        />
        <el-alert
          v-if="hasNetRequired"
          title="橙色行: 库存不足需采购（净需求 > 0）；绿色行: 库存充足可跳过。库存已跨订单统一扣减一次。可手动调整数量或删除行。"
          type="warning"
          :closable="false"
          class="mp-hint"
        />
      </div>

      <!-- Merged editable items table -->
      <el-table
        :data="editableItems"
        stripe
        size="small"
        style="width: 100%; margin-top: 12px"
        :row-class-name="(row: { row: EditableItem }) => row.row._stockSufficient ? 'mp-row-sufficient' : 'mp-row-needed'"
      >
        <el-table-column label="分类" width="66">
          <template #default="{ row }">
            <el-tag
              :type="row.materialCategory === 'RAW' ? 'danger' : row.materialCategory === 'AUXILIARY' ? 'warning' : 'info'"
              size="small"
            >{{ categoryLabel(row.materialCategory) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="materialName" label="原料名称" min-width="130" />
        <el-table-column label="合并自" min-width="150">
          <template #default="{ row }">
            <el-tag
              v-for="soNo in row._sourceSalesOrderNumbers"
              :key="soNo"
              size="small"
              type="info"
              effect="plain"
              style="margin: 1px 2px"
            >{{ soNo }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="需要量" width="96" align="right">
          <template #default="{ row }">
            <span class="mp-qty">{{ row._requiredQuantity?.toFixed(2) }} {{ row.unit }}</span>
          </template>
        </el-table-column>
        <el-table-column label="现有库存" width="96" align="right">
          <template #default="{ row }">
            <span :class="row._stockSufficient ? 'mp-stock-ok' : 'mp-stock-low'">
              {{ row._currentStock?.toFixed(2) }} {{ row.unit }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="净需求" width="96" align="right">
          <template #default="{ row }">
            <span :class="row._stockSufficient ? 'mp-stock-ok' : 'mp-net-needed'">
              {{ row._netRequired?.toFixed(2) }} {{ row.unit }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="采购数量" width="136">
          <template #default="{ row }">
            <el-input-number
              v-model="row.quantity"
              :min="0"
              :precision="2"
              size="small"
              style="width: 116px"
              controls-position="right"
            />
          </template>
        </el-table-column>
        <el-table-column label="参考单价" width="112">
          <template #default="{ row }">
            <el-input-number
              v-model="row.unitPrice"
              :min="0"
              :precision="4"
              size="small"
              style="width: 96px"
              controls-position="right"
              :placeholder="row.unitPrice == null ? '未知' : ''"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="62" align="center">
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

      <div class="mp-meta-row" style="margin-top: 16px; display: flex; gap: 16px; align-items: flex-start">
        <el-form-item label="下单日期" style="flex: 1">
          <el-input v-model="orderDateStr" type="date" size="small" />
        </el-form-item>
        <el-form-item label="备注" style="flex: 2">
          <el-input
            v-model="remark"
            type="textarea"
            :rows="2"
            size="small"
            :placeholder="`合并销售订单 ${suggestion.salesOrderNumbers.join(' / ')} 自动生成`"
          />
        </el-form-item>
      </div>
      <el-alert
        type="info"
        :closable="false"
        style="margin-top: 8px"
        title="供应商可在采购单创建后补填。合并单关联首张销售订单，全部订单号记入备注供溯源。"
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
        确认创建合并采购单 ({{ editableItems.length }} 行)
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.mp-loading {
  padding: 20px 0;
}
.mp-context-header {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.mp-hint {
  margin-top: 4px;
}
.mp-qty {
  color: #606266;
}
.mp-stock-ok {
  color: #67c23a;
  font-weight: 500;
}
.mp-stock-low {
  color: #e6a23c;
}
.mp-net-needed {
  color: #f56c6c;
  font-weight: 600;
}
:deep(.mp-row-needed) td {
  background-color: #fff8f0 !important;
}
:deep(.mp-row-sufficient) td {
  color: #909399;
}
</style>
