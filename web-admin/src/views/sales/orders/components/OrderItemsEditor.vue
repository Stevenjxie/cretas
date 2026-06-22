<!-- web-admin/src/views/sales/orders/components/OrderItemsEditor.vue -->
<script setup lang="ts">
/**
 * OrderItemsEditor — 销售订单产品行受控编辑器（新建弹窗 + 详情页 DRAFT 编辑共用）。
 * 字段对齐后端 SalesOrderItemDTO。选产品时 emit product-change(index) 供父级跑价格逻辑。
 */
import { computed } from 'vue';

export interface OrderItemRow {
  productTypeId: string;
  productName?: string;
  quantity?: number;
  unit?: string;
  unitPrice?: number;
  taxRate?: number;
  specification?: string;
  [k: string]: unknown;
}
interface ProductOption { id: string; name: string; unit?: string; [k: string]: unknown; }

const props = defineProps<{ items: OrderItemRow[]; products: ProductOption[] }>();
const emit = defineEmits<{
  (e: 'update:items', v: OrderItemRow[]): void;
  (e: 'product-change', index: number): void;
}>();

const rows = computed(() => props.items);
function emitRows(next: OrderItemRow[]) { emit('update:items', next); }

function emptyRow(): OrderItemRow { return { productTypeId: '', quantity: 0, unit: '份', unitPrice: 0, taxRate: 13 }; }
function addRow() { emitRows([...rows.value, emptyRow()]); }
function removeRow(idx: number) {
  const next = rows.value.slice();
  next.splice(idx, 1);
  emitRows(next.length ? next : [emptyRow()]);
}
function onProductChange(idx: number, productTypeId: string) {
  const next = rows.value.map((r, i) => (i === idx ? { ...r } : r));
  next[idx].productTypeId = productTypeId;
  const p = props.products.find((x) => x.id === productTypeId);
  if (p) { next[idx].productName = p.name; if (!next[idx].unit) next[idx].unit = p.unit ?? '份'; }
  emitRows(next);
  emit('product-change', idx);
}
function patch(idx: number, key: keyof OrderItemRow, val: unknown) {
  const next = rows.value.map((r, i) => (i === idx ? { ...r, [key]: val } : r));
  emitRows(next);
}

const hasProducts = computed(() => props.products.length > 0);
</script>

<template>
  <div class="order-items-editor">
    <slot v-if="!hasProducts" name="empty-products" />
    <el-table v-else :data="rows" border size="small">
      <el-table-column label="产品" min-width="160">
        <template #default="{ row, $index }">
          <el-select v-if="row" :model-value="row.productTypeId" placeholder="选择产品"
            @update:model-value="(v: string) => onProductChange($index, v)">
            <el-option v-for="p in products" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="数量" width="120">
        <template #default="{ row, $index }">
          <el-input-number v-if="row" :model-value="row.quantity" :min="0" controls-position="right"
            @update:model-value="(v: number) => patch($index, 'quantity', v)" />
        </template>
      </el-table-column>
      <el-table-column label="单位" width="90">
        <template #default="{ row, $index }">
          <el-input v-if="row" :model-value="row.unit" @update:model-value="(v: string) => patch($index, 'unit', v)" />
        </template>
      </el-table-column>
      <el-table-column label="单价(未税)" width="120">
        <template #default="{ row, $index }">
          <el-input-number v-if="row" :model-value="row.unitPrice" :min="0" :precision="2" controls-position="right"
            @update:model-value="(v: number) => patch($index, 'unitPrice', v)" />
        </template>
      </el-table-column>
      <el-table-column label="税率%" width="100">
        <template #default="{ row, $index }">
          <el-input-number v-if="row" :model-value="row.taxRate" :min="0" :max="100" controls-position="right"
            @update:model-value="(v: number) => patch($index, 'taxRate', v)" />
        </template>
      </el-table-column>
      <el-table-column label="规格" min-width="120">
        <template #default="{ row, $index }">
          <el-input v-if="row" :model-value="row.specification" @update:model-value="(v: string) => patch($index, 'specification', v)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{ $index }">
          <el-button v-if="$index != null" type="danger" link size="small" @click="removeRow($index)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-button v-if="hasProducts" size="small" style="margin-top: 8px;" @click="addRow">+ 添加一行</el-button>
  </div>
</template>
