<template>
  <div class="entity-tree">
    <div class="tree-title">实体字段 <span class="entity-tag">{{ entityType }}</span></div>
    <div class="hint">
      拖到画布即创建字段绑定
      <span v-if="loadingSchema" class="loading-hint">（加载中…）</span>
      <span v-else-if="dynamicSchema" class="live-hint">（实时）</span>
    </div>
    <el-tree
      :data="treeData"
      :props="{ children: 'children', label: 'label' }"
      node-key="path"
      :draggable="true"
      :allow-drop="() => false"
      @node-drag-start="onDragStart"
    >
      <template #default="{ node, data }">
        <div class="node-row" :draggable="true" @dragstart="onItemDragStart(data, $event)">
          <span class="node-label">{{ node.label }}</span>
          <span v-if="data.type" class="node-type">{{ data.type }}</span>
        </div>
      </template>
    </el-tree>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { PrintEntityType } from '../utils/printSchemaTypes'

interface FieldNode {
  label: string
  path: string             // dotted path used to build {{path}}
  type?: string            // 'string' | 'number' | 'array' | 'date'
  children?: FieldNode[]
}

const props = defineProps<{
  entityType: PrintEntityType | ''
  factoryId?: string       // passed from parent editor; needed for backend fetch
}>()

/**
 * SP12 stub builder → backend-ready replacement (2026-06-11, P1 #41):
 *
 * Dynamic schema loaded from backend when available.
 * When `GET /api/mobile/{factoryId}/entity-metadata/{entityType}` is implemented,
 * `dynamicSchema` is populated and `FIELD_SCHEMA_FALLBACK` is bypassed.
 * Until then the hardcoded fallback below ensures the editor remains usable.
 *
 * TODO(SP12 backend): implement EntityMetadataController returning FieldNode[] per PrintEntityType.
 */
const dynamicSchema = ref<FieldNode[] | null>(null)
const loadingSchema = ref(false)

async function fetchFromBackend(entityType: string, factoryId: string) {
  if (!entityType || !factoryId) return
  loadingSchema.value = true
  try {
    const token = localStorage.getItem('access_token') || ''
    const res = await fetch(
      `/api/mobile/${factoryId}/entity-metadata/${entityType}`,
      { headers: { Authorization: `Bearer ${token}` } }
    )
    if (res.ok) {
      const json = await res.json()
      // backend should return { success: true, data: FieldNode[] }
      const nodes: FieldNode[] = json?.data ?? json
      if (Array.isArray(nodes) && nodes.length > 0) {
        dynamicSchema.value = nodes
      }
    }
    // 404 / 501 = endpoint not implemented yet → fall through to FIELD_SCHEMA_FALLBACK
  } catch {
    // network error or JSON parse error → fall through to fallback
  } finally {
    loadingSchema.value = false
  }
}

watch(
  () => [props.entityType, props.factoryId] as [string, string | undefined],
  ([et, fid]) => {
    dynamicSchema.value = null
    if (et && fid) fetchFromBackend(et, fid)
  },
  { immediate: true }
)

/**
 * Fallback field schema (Sprint 6 hardcoded stub, covers ~80% of built-in entityTypes).
 * Used when the backend metadata endpoint is unavailable.
 */
const FIELD_SCHEMA_FALLBACK: Record<string, FieldNode[]> = {
  PRINT_SALES_ORDER: [
    { label: '工厂名', path: 'factoryName', type: 'string' },
    { label: '订单号', path: 'order.orderNumber', type: 'string' },
    { label: '订单日期', path: 'order.orderDate', type: 'date' },
    { label: '客户名', path: 'order.customerName', type: 'string' },
    { label: '销售员', path: 'order.salesperson', type: 'string' },
    { label: '总金额', path: 'order.totalAmount', type: 'currency' },
    { label: '备注', path: 'order.remark', type: 'string' },
    {
      label: '明细行', path: 'order.items', type: 'array',
      children: [
        { label: '物料名', path: 'item.materialName', type: 'string' },
        { label: '数量', path: 'item.quantity', type: 'number' },
        { label: '单位', path: 'item.unit', type: 'string' },
        { label: '单价', path: 'item.unitPrice', type: 'currency' },
        { label: '小计', path: 'item.subtotal', type: 'currency' },
      ],
    },
  ],
  PRINT_PURCHASE_ORDER: [
    { label: '工厂名', path: 'factoryName', type: 'string' },
    { label: '采购单号', path: 'order.orderNumber', type: 'string' },
    { label: '采购日期', path: 'order.orderDate', type: 'date' },
    { label: '供应商', path: 'order.supplierName', type: 'string' },
    { label: '期望到货', path: 'order.expectedDeliveryDate', type: 'date' },
    { label: '总金额', path: 'order.totalAmount', type: 'currency' },
    { label: '二维码 payload', path: 'order.qrPayload', type: 'string' },
    {
      label: '明细行', path: 'order.items', type: 'array',
      children: [
        { label: '物料名', path: 'item.materialName', type: 'string' },
        { label: '规格', path: 'item.spec', type: 'string' },
        { label: '数量', path: 'item.quantity', type: 'number' },
        { label: '单位', path: 'item.unit', type: 'string' },
        { label: '单价', path: 'item.unitPrice', type: 'currency' },
      ],
    },
  ],
  PRINT_QUOTATION: [
    { label: '报价编号', path: 'quotation.quotationNumber', type: 'string' },
    { label: '报价日期', path: 'quotation.quotationDate', type: 'date' },
    { label: '客户', path: 'quotation.customerName', type: 'string' },
    { label: '有效期', path: 'quotation.validUntil', type: 'date' },
    { label: '销售员', path: 'quotation.salesperson', type: 'string' },
    { label: '总金额', path: 'quotation.totalAmount', type: 'currency' },
    { label: '明细行', path: 'quotation.items', type: 'array' },
  ],
  PRINT_PRODUCTION_TASK: [
    { label: '任务号', path: 'task.taskNumber', type: 'string' },
    { label: '产品', path: 'task.productName', type: 'string' },
    { label: '计划数量', path: 'task.plannedQuantity', type: 'number' },
    { label: '单位', path: 'task.unit', type: 'string' },
    { label: '开始日期', path: 'task.startDate', type: 'date' },
    { label: '结束日期', path: 'task.endDate', type: 'date' },
    { label: '车间', path: 'task.workshopName', type: 'string' },
    { label: '负责人', path: 'task.supervisor', type: 'string' },
    { label: '工序', path: 'task.processes', type: 'array' },
  ],
  PRINT_MATERIAL_REQUISITION: [
    { label: '领料单号', path: 'requisition.requisitionNumber', type: 'string' },
    { label: '产品名', path: 'requisition.productName', type: 'string' },
    { label: '车间', path: 'requisition.workshop', type: 'string' },
    { label: '领料人', path: 'requisition.requester', type: 'string' },
    { label: '申请日期', path: 'requisition.requestDate', type: 'date' },
    { label: '明细行', path: 'requisition.items', type: 'array' },
  ],
  PRINT_WEIGHING_SLIP: [
    { label: '称重单号', path: 'slip.slipNumber', type: 'string' },
    { label: '产品名', path: 'slip.productName', type: 'string' },
    { label: '客户/供应商', path: 'slip.partnerName', type: 'string' },
    { label: '过磅日期', path: 'slip.weighDate', type: 'date' },
    { label: '操作员', path: 'slip.operator', type: 'string' },
    { label: '总毛重', path: 'slip.grossWeight', type: 'qty' },
    { label: '总皮重', path: 'slip.tareWeight', type: 'qty' },
    { label: '总净重', path: 'slip.netWeight', type: 'qty' },
    {
      label: '过磅明细', path: 'slip.items', type: 'array',
      children: [
        { label: '箱号', path: 'item.boxNo', type: 'string' },
        { label: '毛重', path: 'item.grossWeight', type: 'qty' },
        { label: '皮重', path: 'item.tareWeight', type: 'qty' },
        { label: '净重', path: 'item.netWeight', type: 'qty' },
      ],
    },
  ],
}

const treeData = computed<FieldNode[]>(() => {
  if (!props.entityType) return []
  // Prefer backend-fetched schema when available; fall back to hardcoded stub
  return dynamicSchema.value ?? FIELD_SCHEMA_FALLBACK[props.entityType] ?? []
})

function onItemDragStart(node: FieldNode, e: DragEvent) {
  if (!e.dataTransfer) return
  e.dataTransfer.setData('application/x-entity-field', node.path)
  e.dataTransfer.effectAllowed = 'copy'
}

// el-tree's built-in drag — block it; we manage drag via native drag on row.
function onDragStart() { /* noop */ }
</script>

<style scoped>
.entity-tree {
  padding: 12px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
.tree-title {
  font-size: 13px;
  font-weight: 600;
  color: #374151;
  margin-bottom: 4px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.entity-tag {
  font-size: 10px;
  background: #eff6ff;
  color: #2563eb;
  padding: 2px 6px;
  border-radius: 4px;
  font-weight: 500;
}
.hint {
  font-size: 11px;
  color: #9ca3af;
  margin-bottom: 8px;
}
.node-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  cursor: grab;
}
.node-row:active {
  cursor: grabbing;
}
.node-label {
  font-size: 12px;
  color: #374151;
}
.node-type {
  font-size: 10px;
  color: #9ca3af;
  background: #f3f4f6;
  padding: 1px 6px;
  border-radius: 3px;
}
.loading-hint {
  font-size: 10px;
  color: #3b82f6;
  margin-left: 4px;
}
.live-hint {
  font-size: 10px;
  color: #10b981;
  margin-left: 4px;
}
</style>
