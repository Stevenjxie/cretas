<script setup lang="ts">
/**
 * 新建请购单 dialog — Sprint 6 W2-A.
 *
 * MVP scope (per Sprint 5 PR #56 backend contract):
 *   - 单选 suggestedSupplierId (整张请购单走同一供应商; UI 限制, backend convertToPO 同假设)
 *   - 多行明细: 物料 + 数量 + 单位 + 行内 remark
 *   - 期望交货日 + 申请原因 + 备注
 *
 * Fool-proof design (per .claude/rules/fool-proof-design.md):
 *   - Rule 2 (context): 行标题显示已选物料名 + 数量 单位
 *   - Rule 3 (constrained inputs): 单位走原料带出的选项 (allow-create 兜底)
 *   - 提交按 :disabled 控制 (必填校验失败时 disable)
 */
import { ref, reactive, computed, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Plus, Delete } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import {
  createRequisition,
  type CreatePurchaseRequisitionRequest,
  type RequisitionItem,
} from '@/api/purchaseRequisition';
import type { TableRow } from '@/types/api';

interface Supplier {
  id: string;
  name: string;
}
interface Material {
  id: string;
  name: string;
  unit?: string | null;
  category?: string | null;
}

const props = defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void;
  (e: 'created'): void;
}>();

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);
const isRestaurant = computed(() => authStore.factoryType === 'RESTAURANT');
const dialogTitle = computed(() => (isRestaurant.value ? '新建报货单' : '新建请购单'));
const supplierLabelText = computed(() => (isRestaurant.value ? '建议供应商' : '建议供应商'));
const supplierPlaceholder = computed(() =>
  isRestaurant.value
    ? '可选: 厨师长不确定供应商时留空, 采购转单时再选'
    : '选择供应商 (整张请购单走同一供应商)',
);
const expectedDateLabel = computed(() => (isRestaurant.value ? '希望到货日' : '期望交货日'));
const reasonLabel = computed(() => (isRestaurant.value ? '报货原因' : '申请原因'));
const reasonPlaceholder = computed(() =>
  isRestaurant.value
    ? '可选: 明日备货 / 厨房急用 / 库存不够 / 临时加菜'
    : '可选: 简要说明请购理由 (生产急用 / 库存告缺 / 客户专单)',
);
const remarkPlaceholder = computed(() => (isRestaurant.value ? '可选: 档口、厨师长、验收备注等' : '可选: 补充说明'));
const itemDividerText = computed(() => (isRestaurant.value ? '报货明细' : '请购明细'));
const addLineText = computed(() => (isRestaurant.value ? '添加报货明细' : '添加明细行'));
const createReadyText = computed(() => (isRestaurant.value ? '创建报货草稿' : '创建草稿'));
const createdMessagePrefix = computed(() => (isRestaurant.value ? '已创建报货单' : '已创建请购单'));

const visible = computed<boolean>({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
});

interface FormItemRow {
  materialTypeId: string;
  materialName: string;
  quantity: number | null;
  unit: string;
  remark: string;
}

function newItemRow(): FormItemRow {
  return {
    materialTypeId: '',
    materialName: '',
    quantity: null,
    unit: '',
    remark: '',
  };
}

const form = reactive<{
  suggestedSupplierId: string;
  expectedDate: string;
  reason: string;
  remark: string;
  items: FormItemRow[];
}>({
  suggestedSupplierId: '',
  expectedDate: '',
  reason: '',
  remark: '',
  items: [newItemRow()],
});

const suppliers = ref<Supplier[]>([]);
const materials = ref<Material[]>([]);
const submitting = ref(false);

async function loadSuppliers() {
  if (!factoryId.value) return;
  try {
    const res = await get<{ content?: Supplier[] }>(
      `/${factoryId.value}/suppliers`,
      { params: { page: 1, size: 200 } },
    );
    suppliers.value = res?.data?.content ?? [];
  } catch {
    // request interceptor already showed sticky toast
  }
}

async function loadMaterials() {
  if (!factoryId.value) return;
  try {
    const res = await get<Material[] | { content?: Material[] }>(
      `/${factoryId.value}/raw-material-types/active`,
    );
    const data = res?.data;
    materials.value = Array.isArray(data) ? data : data?.content ?? [];
  } catch {
    // interceptor handles
  }
}

watch(visible, async (v) => {
  if (v) {
    // 重置表单
    form.suggestedSupplierId = '';
    form.expectedDate = '';
    form.reason = '';
    form.remark = '';
    form.items = [newItemRow()];
    await Promise.all([loadSuppliers(), loadMaterials()]);
  }
});

function onMaterialChange(row: FormItemRow) {
  const mat = materials.value.find((m) => m.id === row.materialTypeId);
  if (mat) {
    row.materialName = String(mat.name || '');
    if (!row.unit && mat.unit) row.unit = String(mat.unit);
  }
}

function addItem() {
  form.items.push(newItemRow());
}

function removeItem(idx: number) {
  if (form.items.length > 1) form.items.splice(idx, 1);
}

const canSubmit = computed(() => {
  if (!isRestaurant.value && !form.suggestedSupplierId) return false;
  if (!form.items.length) return false;
  return form.items.every(
    (it) =>
      it.materialTypeId &&
      it.unit &&
      typeof it.quantity === 'number' &&
      it.quantity > 0,
  );
});

async function handleCreate() {
  if (!canSubmit.value || submitting.value || !factoryId.value) return;
  submitting.value = true;
  try {
    const requestedItems: RequisitionItem[] = form.items.map((it) => ({
      materialTypeId: it.materialTypeId,
      materialName: it.materialName || null,
      quantity: Number(it.quantity),
      unit: it.unit,
      suggestedSupplierId: form.suggestedSupplierId || null,
      remark: it.remark || null,
    }));
    const payload: CreatePurchaseRequisitionRequest = {
      requestedItems,
      expectedDate: form.expectedDate || null,
      reason: form.reason || null,
      remark: form.remark || null,
    };
    const res = await createRequisition(factoryId.value, payload);
    if (res?.success) {
      const created = res.data;
      ElMessage.success(`${createdMessagePrefix.value} ${created?.requisitionNumber || ''}`);
      visible.value = false;
      emit('created');
    } else {
      ElMessage.error(res?.message || '创建失败');
    }
  } catch (e) {
    // Interceptor shows toast; log for dev
    console.error('[请购单创建失败]', e);
  } finally {
    submitting.value = false;
  }
}

// Fool-proof Rule 2: dialog 标题显示已选供应商 (一旦选了)
const supplierLabel = computed(() => {
  const s = suppliers.value.find((x) => x.id === form.suggestedSupplierId);
  return s ? s.name : '';
});

function getMaterialUnits(row: FormItemRow): string[] {
  const mat = materials.value.find((m) => m.id === row.materialTypeId);
  const set = new Set<string>();
  if (mat?.unit) set.add(String(mat.unit));
  if (row.unit) set.add(row.unit);
  return Array.from(set);
}

function _avoidUnusedWarning(_r: TableRow) {
  /* preserve TableRow import for future audit */
}
void _avoidUnusedWarning;
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="dialogTitle"
    width="900px"
    destroy-on-close
    :close-on-click-modal="false"
  >
    <el-form :model="form" label-width="110px">
      <el-form-item :label="supplierLabelText" :required="!isRestaurant">
        <el-select
          v-model="form.suggestedSupplierId"
          :placeholder="supplierPlaceholder"
          filterable
          clearable
          style="width: 100%"
        >
          <el-option
            v-for="s in suppliers"
            :key="s.id"
            :label="s.name"
            :value="s.id"
          />
        </el-select>
        <div v-if="supplierLabel" class="hint">
          已选: <strong>{{ supplierLabel }}</strong>
        </div>
      </el-form-item>
      <el-form-item :label="expectedDateLabel">
        <el-date-picker
          v-model="form.expectedDate"
          type="date"
          value-format="YYYY-MM-DD"
          style="width: 220px"
        />
      </el-form-item>
      <el-form-item :label="reasonLabel">
        <el-input
          v-model="form.reason"
          type="textarea"
          :rows="2"
          maxlength="5000"
          show-word-limit
          :placeholder="reasonPlaceholder"
        />
      </el-form-item>
      <el-form-item label="备注">
        <el-input
          v-model="form.remark"
          type="textarea"
          :rows="2"
          maxlength="5000"
          show-word-limit
          :placeholder="remarkPlaceholder"
        />
      </el-form-item>

      <el-divider>{{ itemDividerText }}</el-divider>

      <div class="item-row item-header">
        <span style="width: 240px"><span class="req-star">*</span>物料</span>
        <span style="width: 130px"><span class="req-star">*</span>数量</span>
        <span style="width: 110px"><span class="req-star">*</span>单位</span>
        <span style="flex: 1; min-width: 160px">行备注</span>
        <span style="width: 60px">操作</span>
      </div>
      <div v-for="(item, idx) in form.items" :key="idx" class="item-row">
        <el-select
          v-model="item.materialTypeId"
          placeholder="选择物料"
          filterable
          style="width: 240px"
          @change="onMaterialChange(item)"
        >
          <el-option
            v-for="m in materials"
            :key="m.id"
            :label="m.name"
            :value="m.id"
          />
        </el-select>
        <el-input-number
          v-model="item.quantity"
          :min="0.01"
          :precision="2"
          placeholder="数量"
          style="width: 130px"
          controls-position="right"
        />
        <el-select
          v-model="item.unit"
          placeholder="单位"
          :disabled="!item.materialTypeId"
          filterable
          allow-create
          default-first-option
          style="width: 110px"
        >
          <el-option
            v-for="u in getMaterialUnits(item)"
            :key="u"
            :label="u"
            :value="u"
          />
        </el-select>
        <el-input
          v-model="item.remark"
          placeholder="可选 — 备注"
          style="flex: 1; min-width: 160px"
          maxlength="200"
        />
        <el-button
          type="danger"
          link
          :icon="Delete"
          :disabled="form.items.length <= 1"
          @click="removeItem(idx)"
        />
      </div>
      <el-button
        type="primary"
        plain
        :icon="Plus"
        style="width: 100%; margin-top: 8px"
        @click="addItem"
      >
        {{ addLineText }}
      </el-button>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="!canSubmit"
        @click="handleCreate"
      >
        {{ canSubmit ? createReadyText : '请填写必填项' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style lang="scss" scoped>
.item-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.item-header {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 4px;
  span {
    display: inline-block;
  }
}
.hint {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
.req-star {
  color: var(--el-color-danger);
  margin-right: 4px;
}
</style>
