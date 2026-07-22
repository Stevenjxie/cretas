<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { Document, Refresh } from '@element-plus/icons-vue';
import { get, post } from '@/api/request';
import {
  getPendingPurchaseReceivingTasks,
  getPendingProductionReceivingTasks,
  getPendingCustomerSuppliedReceivingTasks,
  confirmCustomerSuppliedReceipt,
  getPurchaseInboundDefaultWarehouse,
  type CustomerSuppliedReceivingTask,
  type PurchaseReceivingTask,
  type ProductionReceivingTask,
} from '@/api/purchaseReceive';
import {
  listWarehouses,
  WAREHOUSE_TYPE_LABELS,
  type FactoryWarehouse,
} from '@/api/factoryWarehouse';
import AttachmentList from '@/components/attachment/AttachmentList.vue';
import AttachmentUploadButton from '@/components/attachment/AttachmentUploadButton.vue';
import { safePrint } from '@/api/printApi';
import { displayUnit } from '@/utils/unitPricing';
import { fmtQty } from '@/utils/tableFormatters';
import { resolveReceivingRouteFilters } from './purchaseReceivingFilters';

const props = defineProps<{ factoryId: string; canWrite: boolean }>();
const emit = defineEmits<{ refreshed: [] }>();
const route = useRoute();

interface ReceiveItemForm {
  purchaseOrderItemId?: number;
  materialTypeId: string;
  materialName: string;
  receivedQuantity: number;
  unit: string;
}

interface ReceiptDetail {
  id: string;
  receiveNumber: string;
  status: 'DRAFT' | 'PENDING_QC' | 'CONFIRMED' | 'REJECTED';
  purchaseOrderId: string;
  supplierId: string;
  warehouseId?: string | null;
  receiveDate: string;
  items: ReceiveItemForm[];
}

const loading = ref(false);
const tasks = ref<PurchaseReceivingTask[]>([]);
const productionTasks = ref<ProductionReceivingTask[]>([]);
const customerSuppliedTasks = ref<CustomerSuppliedReceivingTask[]>([]);
const dialogVisible = ref(false);
const submitting = ref(false);
const confirming = ref(false);
const formRef = ref<FormInstance>();
const selectedTask = ref<PurchaseReceivingTask | null>(null);
const receipt = ref<ReceiptDetail | null>(null);
const warehouseOptions = ref<FactoryWarehouse[]>([]);
const attachmentRefreshKey = ref(0);
const productionDialogVisible = ref(false);
const productionConfirming = ref(false);
const selectedProductionTask = ref<ProductionReceivingTask | null>(null);
const productionReceiptForm = ref({ receivedQuantity: 0, note: '' });
const customerDialogVisible = ref(false);
const customerConfirming = ref(false);
const selectedCustomerTask = ref<CustomerSuppliedReceivingTask | null>(null);
const customerAttachmentRefreshKey = ref(0);
const customerReceiptForm = ref({
  idempotencyKey: '',
  receivedQuantity: 0,
  productionDate: '',
  expireDate: '',
  externalBatchNumber: '',
  originPlace: '',
  notes: '',
});
const form = ref({
  purchaseOrderId: '',
  supplierId: '',
  receiveDate: '',
  warehouseId: '',
  remark: '',
  items: [] as ReceiveItemForm[],
});

const rules: FormRules = {
  warehouseId: [{ required: true, message: '请选择实际入库仓库', trigger: 'change' }],
  receiveDate: [{ required: true, message: '请选择收货日期', trigger: 'change' }],
};

const routeFilters = computed(() => resolveReceivingRouteFilters(route.query));
const exactPurchaseOrderId = computed(() => routeFilters.value.purchaseOrderId);
const exactOrderNumber = computed(() => routeFilters.value.purchaseOrderNumber);
const exactSalesOrderId = computed(() => routeFilters.value.salesOrderId);
const exactSalesOrderNumber = computed(() => routeFilters.value.salesOrderNumber);
const highlightedOrder = computed(() => routeFilters.value.highlightNumber);

function localDateText(): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function unitGroups(task: PurchaseReceivingTask, field: 'orderedQuantity' | 'receivedQuantity' | 'remainingReceivableQuantity') {
  const groups = new Map<string, number>();
  for (const item of task.items || []) {
    const unit = displayUnit(item.unit) || '未配置';
    groups.set(unit, (groups.get(unit) || 0) + Number(item[field] || 0));
  }
  return Array.from(groups.entries()).map(([unit, quantity]) => `${fmtQty(quantity)}${unit}`).join(' + ') || '—';
}

function materialSummary(task: PurchaseReceivingTask): string {
  return (task.items || [])
    .map((item) => `${item.materialName} ${fmtQty(item.remainingReceivableQuantity)}${displayUnit(item.unit)}`)
    .join('；');
}

function isOverdue(task: PurchaseReceivingTask): boolean {
  return Boolean(task.expectedDeliveryDate && task.expectedDeliveryDate < localDateText());
}

async function loadTasks() {
  if (!props.factoryId) return;
  loading.value = true;
  try {
    const [purchaseResult, productionResult, customerResult] = await Promise.allSettled([
      getPendingPurchaseReceivingTasks(props.factoryId, {
        purchaseOrderId: exactPurchaseOrderId.value || undefined,
        orderNumber: exactPurchaseOrderId.value ? undefined : exactOrderNumber.value || undefined,
      }),
      getPendingProductionReceivingTasks(props.factoryId),
      getPendingCustomerSuppliedReceivingTasks(props.factoryId),
    ]);
    tasks.value = purchaseResult.status === 'fulfilled'
      && purchaseResult.value.success && Array.isArray(purchaseResult.value.data)
      ? purchaseResult.value.data : [];
    productionTasks.value = routeFilters.value.restrictToPurchase
      || routeFilters.value.restrictToCustomerSupplied
      ? []
      : productionResult.status === 'fulfilled'
        && productionResult.value.success && Array.isArray(productionResult.value.data)
        ? productionResult.value.data : [];
    const allCustomerTasks = customerResult.status === 'fulfilled'
      && customerResult.value.success && Array.isArray(customerResult.value.data)
      ? customerResult.value.data : [];
    customerSuppliedTasks.value = routeFilters.value.restrictToPurchase
      ? []
      : allCustomerTasks.filter((task) =>
        (!exactSalesOrderId.value || task.salesOrderId === exactSalesOrderId.value)
        && (!exactSalesOrderNumber.value || task.salesOrderNumber === exactSalesOrderNumber.value));
  } finally {
    loading.value = false;
  }
}

type UnifiedInboundRow =
  | { key: string; source: 'PURCHASE'; purchase: PurchaseReceivingTask }
  | { key: string; source: 'PRODUCTION_PLAN'; production: ProductionReceivingTask }
  | { key: string; source: 'CUSTOMER_SUPPLIED'; customer: CustomerSuppliedReceivingTask };

const unifiedRows = computed<UnifiedInboundRow[]>(() => [
  ...tasks.value.map((purchase) => ({
    key: `PURCHASE:${purchase.taskId}`,
    source: 'PURCHASE' as const,
    purchase,
  })),
  ...productionTasks.value.map((production) => ({
    key: `PRODUCTION_PLAN:${production.id}`,
    source: 'PRODUCTION_PLAN' as const,
    production,
  })),
  ...customerSuppliedTasks.value.map((customer) => ({
    key: `CUSTOMER_SUPPLIED:${customer.taskId}`,
    source: 'CUSTOMER_SUPPLIED' as const,
    customer,
  })),
]);

function rowStatusLabel(row: UnifiedInboundRow): string {
  if (row.source === 'PURCHASE' && row.purchase.receiptConflict) return '收货草稿冲突';
  if (row.source === 'PURCHASE') return row.purchase.statusLabel;
  if (row.source === 'CUSTOMER_SUPPLIED') {
    return row.customer.status === 'PARTIALLY_RECEIVED' ? '客户来料部分已收' : '客户来料待收货';
  }
  return '成品待入库';
}

function rowSourceLabel(row: UnifiedInboundRow): string {
  if (row.source === 'PURCHASE') return '采购入库';
  return row.source === 'CUSTOMER_SUPPLIED' ? '客户自带料' : '生产入库';
}

function rowSourceNumber(row: UnifiedInboundRow): string {
  if (row.source === 'PURCHASE') return row.purchase.orderNumber;
  return row.source === 'CUSTOMER_SUPPLIED' ? row.customer.salesOrderNumber : row.production.sourceNumber;
}

function rowCounterparty(row: UnifiedInboundRow): string {
  if (row.source === 'PURCHASE') return row.purchase.supplierName || row.purchase.supplierId;
  return row.source === 'CUSTOMER_SUPPLIED' ? row.customer.customerName : '生产计划';
}

function rowMaterialSummary(row: UnifiedInboundRow): string {
  if (row.source === 'PURCHASE') return materialSummary(row.purchase);
  if (row.source === 'CUSTOMER_SUPPLIED') {
    return `${row.customer.materialName} ${fmtQty(row.customer.remainingQuantity)}${displayUnit(row.customer.unit)}`;
  }
  return `${row.production.productName} ${fmtQty(row.production.reportedQuantity)}${displayUnit(row.production.unit)}`;
}

function rowPlannedQuantity(row: UnifiedInboundRow): string {
  if (row.source === 'PURCHASE') return unitGroups(row.purchase, 'orderedQuantity');
  if (row.source === 'CUSTOMER_SUPPLIED') {
    return `${fmtQty(row.customer.expectedQuantity)}${displayUnit(row.customer.unit)}`;
  }
  return `${fmtQty(row.production.plannedQuantity || 0)}${displayUnit(row.production.unit)}`;
}

function rowReceivedQuantity(row: UnifiedInboundRow): string {
  if (row.source === 'PURCHASE') return unitGroups(row.purchase, 'receivedQuantity');
  if (row.source === 'CUSTOMER_SUPPLIED') {
    return `${fmtQty(row.customer.receivedQuantity)}${displayUnit(row.customer.unit)}`;
  }
  return `${fmtQty(row.production.receivedQuantity || 0)}${displayUnit(row.production.unit)}`;
}

function rowRemainingQuantity(row: UnifiedInboundRow): string {
  if (row.source === 'PURCHASE') return unitGroups(row.purchase, 'remainingReceivableQuantity');
  if (row.source === 'CUSTOMER_SUPPLIED') {
    return `${fmtQty(row.customer.remainingQuantity)}${displayUnit(row.customer.unit)}`;
  }
  const remaining = Math.max(
    0,
    Number(row.production.reportedQuantity || 0) - Number(row.production.receivedQuantity || 0),
  );
  return `${fmtQty(remaining)}${displayUnit(row.production.unit)}`;
}

function rowWarehouse(row: UnifiedInboundRow): string {
  if (row.source === 'PURCHASE') {
    return row.purchase.warehouseName || (row.purchase.warehouseId ? '已选择' : '收货时选择');
  }
  if (row.source === 'CUSTOMER_SUPPLIED') {
    return row.customer.targetWarehouseName || row.customer.targetWarehouseCode || '订单指定仓库';
  }
  return row.production.toWarehouseName || '成品仓（按计划配置）';
}

function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `customer-receipt-${crypto.randomUUID()}`;
  }
  return `customer-receipt-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function openCustomerReceive(task: CustomerSuppliedReceivingTask) {
  selectedCustomerTask.value = task;
  customerReceiptForm.value = {
    idempotencyKey: newIdempotencyKey(),
    receivedQuantity: Number(task.remainingQuantity || 0),
    productionDate: '',
    expireDate: '',
    externalBatchNumber: '',
    originPlace: '',
    notes: '',
  };
  customerAttachmentRefreshKey.value = 0;
  customerDialogVisible.value = true;
}

async function confirmCustomerReceipt() {
  const task = selectedCustomerTask.value;
  if (!task || customerConfirming.value) return;
  const quantity = Number(customerReceiptForm.value.receivedQuantity);
  if (!(quantity > 0) || quantity > Number(task.remainingQuantity)) {
    ElMessage.error(`本次实收必须大于 0，且不能超过待收 ${fmtQty(task.remainingQuantity)}${displayUnit(task.unit)}`);
    return;
  }
  await ElMessageBox.confirm(
    `确认收货『${task.materialName}』${fmtQty(quantity)}${displayUnit(task.unit)}吗？确认后生成客户所有库存批次。`,
    `客户来料收货 · ${task.salesOrderNumber}`,
    { type: 'warning', confirmButtonText: '确认收货', cancelButtonText: '返回核对' },
  );
  customerConfirming.value = true;
  try {
    await confirmCustomerSuppliedReceipt(props.factoryId, task.taskId, {
      ...customerReceiptForm.value,
      productionDate: customerReceiptForm.value.productionDate || undefined,
      expireDate: customerReceiptForm.value.expireDate || undefined,
      externalBatchNumber: customerReceiptForm.value.externalBatchNumber || undefined,
      originPlace: customerReceiptForm.value.originPlace || undefined,
      notes: customerReceiptForm.value.notes || undefined,
    });
    ElMessage.success('客户来料收货完成，已生成客户所有库存批次');
    customerDialogVisible.value = false;
    await loadTasks();
    emit('refreshed');
  } finally {
    customerConfirming.value = false;
  }
}

function openProductionReceive(task: ProductionReceivingTask) {
  selectedProductionTask.value = task;
  productionReceiptForm.value = {
    receivedQuantity: Number(task.reportedQuantity || 0),
    note: task.note || '',
  };
  productionDialogVisible.value = true;
}

async function confirmProductionReceipt() {
  const task = selectedProductionTask.value;
  if (!task || productionConfirming.value) return;
  if (!(Number(productionReceiptForm.value.receivedQuantity) > 0)) {
    ElMessage.error('仓库实收数量必须大于 0');
    return;
  }
  await ElMessageBox.confirm(
    `确认将『${task.productName}』${fmtQty(productionReceiptForm.value.receivedQuantity)}${displayUnit(task.unit)}入库吗？`,
    `确认生产成品入库 · ${task.sourceNumber}`,
    { type: 'warning', confirmButtonText: '确认入库', cancelButtonText: '返回核对' },
  );
  productionConfirming.value = true;
  try {
    await post(`/${props.factoryId}/warehouse/transit-ledgers/${task.id}/confirm`, {
      receivedQuantity: productionReceiptForm.value.receivedQuantity,
      note: productionReceiptForm.value.note || undefined,
    });
    ElMessage.success('成品入库完成');
    productionDialogVisible.value = false;
    await loadTasks();
    emit('refreshed');
  } finally {
    productionConfirming.value = false;
  }
}

async function loadWarehouses() {
  const [warehouses, defaultWarehouse] = await Promise.all([
    listWarehouses(props.factoryId),
    getPurchaseInboundDefaultWarehouse(props.factoryId).catch((): {
      success: boolean;
      data: FactoryWarehouse | null;
    } => ({ success: false, data: null })),
  ]);
  warehouseOptions.value = (warehouses.data || []).filter((warehouse) =>
    warehouse.isActive !== false && ['RAW', 'LOGISTICS', 'OUTSOURCE', 'OTHER'].includes(warehouse.type));
  if (!form.value.warehouseId && defaultWarehouse.data?.id) {
    form.value.warehouseId = defaultWarehouse.data.id;
  }
}

async function loadExistingReceipt(receiptId: string) {
  const response = await get<ReceiptDetail>(`/${props.factoryId}/purchase/receives/${receiptId}`);
  if (response.success && response.data) receipt.value = response.data;
}

async function openReceive(task: PurchaseReceivingTask) {
  if (task.receiptConflict) {
    ElMessage.error(`该采购单存在 ${task.activeReceiptCount} 张活动收货草稿，请先由仓储主管核对；系统不会自动取消或合并历史草稿`);
    return;
  }
  selectedTask.value = task;
  receipt.value = null;
  form.value = {
    purchaseOrderId: task.purchaseOrderId,
    supplierId: task.supplierId,
    receiveDate: localDateText(),
    warehouseId: task.warehouseId || '',
    remark: '',
    items: task.items
      .filter((item) => Number(item.remainingReceivableQuantity) > 0)
      .map((item) => ({
        purchaseOrderItemId: item.purchaseOrderItemId,
        materialTypeId: item.materialTypeId,
        materialName: item.materialName,
        receivedQuantity: Number(item.remainingReceivableQuantity),
        unit: item.unit,
      })),
  };
  dialogVisible.value = true;
  await loadWarehouses();
  if (task.activeReceiptId) await loadExistingReceipt(task.activeReceiptId);
}

async function createReceipt() {
  if (!formRef.value || !await formRef.value.validate().catch(() => false)) return;
  const invalidLine = form.value.items.find((item) => !(Number(item.receivedQuantity) > 0));
  if (invalidLine) {
    ElMessage.error(`「${invalidLine.materialName}」本次实收必须大于 0`);
    return;
  }
  submitting.value = true;
  try {
    const response = await post<ReceiptDetail>(`/${props.factoryId}/purchase/receives`, form.value);
    if (response.success && response.data) {
      receipt.value = response.data;
      ElMessage.success('收货单草稿已创建；请上传供货凭证并核对后确认入库');
      await loadTasks();
    }
  } finally {
    submitting.value = false;
  }
}

async function confirmReceipt() {
  if (!receipt.value || confirming.value) return;
  const lines = (receipt.value.items || [])
    .map((item) => `• ${item.materialName}：${fmtQty(item.receivedQuantity)}${displayUnit(item.unit)}`)
    .join('\n');
  await ElMessageBox.confirm(
    `${lines}\n\n确认后才会生成库存批次；重复确认不会重复入库。`,
    `确认收货入库 — ${receipt.value.receiveNumber}`,
    { type: 'warning', confirmButtonText: '确认收货入库', cancelButtonText: '返回核对' },
  );
  confirming.value = true;
  try {
    await post(`/${props.factoryId}/purchase/receives/${receipt.value.id}/confirm`);
    ElMessage.success('收货入库完成，库存批次已生成');
    dialogVisible.value = false;
    await loadTasks();
    emit('refreshed');
  } finally {
    confirming.value = false;
  }
}

function taskRowClass({ row }: { row: UnifiedInboundRow }) {
  if (row.source === 'CUSTOMER_SUPPLIED' && (exactSalesOrderId.value || exactSalesOrderNumber.value)
      && (row.customer.salesOrderId === exactSalesOrderId.value
        || row.customer.salesOrderNumber === exactSalesOrderNumber.value)) {
    return 'pending-receive-row pending-receive-row--focused';
  }
  return row.source === 'PURCHASE' && highlightedOrder.value
    && (row.purchase.orderNumber === highlightedOrder.value || row.purchase.purchaseOrderId === highlightedOrder.value)
    ? 'pending-receive-row pending-receive-row--focused'
    : 'pending-receive-row';
}

onMounted(loadTasks);
defineExpose({ loadTasks });
</script>

<template>
  <section class="receiving-task-panel" aria-label="仓储待收货任务">
    <div class="task-heading">
      <div>
        <h3>待收货 / 待入库任务</h3>
        <p>审批完成的采购订单、客户自带料和生产报产会进入这里；打开和刷新只查询数据，不会创建收货单或库存。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadTasks">刷新待办</el-button>
    </div>

    <el-alert
      v-if="highlightedOrder && tasks.length === 0 && !loading"
      type="warning"
      :closable="false"
      show-icon
      :title="`未找到 ${highlightedOrder} 的可收货任务`"
      description="请确认采购 OA/财务审批已完成且订单尚未收齐；本页面不会自动补写或桥接历史数据。"
    />

    <el-table
      :data="unifiedRows"
      v-loading="loading"
      :row-class-name="taskRowClass"
      empty-text="暂无待收货任务"
      border
      row-key="key"
    >
      <el-table-column label="状态" width="105" fixed="left">
        <template #default="{ row }"><el-tag type="danger" effect="dark">{{ rowStatusLabel(row) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="来源" width="105"><template #default="{ row }">{{ rowSourceLabel(row) }}</template></el-table-column>
      <el-table-column label="来源单号" min-width="175" show-overflow-tooltip><template #default="{ row }">{{ rowSourceNumber(row) }}</template></el-table-column>
      <el-table-column label="供应商 / 来源" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">{{ rowCounterparty(row) }}</template>
      </el-table-column>
      <el-table-column label="预计到货" width="125">
        <template #default="{ row }">
          <span v-if="row.source === 'PURCHASE'" :class="{ overdue: isOverdue(row.purchase) }">{{ row.purchase.expectedDeliveryDate || '未维护' }}</span>
          <span v-else-if="row.source === 'CUSTOMER_SUPPLIED'">{{ row.customer.expectedArrivalAt ? String(row.customer.expectedArrivalAt).slice(0, 10) : '未维护' }}</span>
          <span v-else>{{ row.production.submittedAt ? String(row.production.submittedAt).slice(0, 10) : '已结单' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="物料 / 待收" min-width="260" show-overflow-tooltip>
        <template #default="{ row }">{{ rowMaterialSummary(row) }}</template>
      </el-table-column>
      <el-table-column label="计划数量" min-width="120"><template #default="{ row }">{{ rowPlannedQuantity(row) }}</template></el-table-column>
      <el-table-column label="已收数量" min-width="120"><template #default="{ row }">{{ rowReceivedQuantity(row) }}</template></el-table-column>
      <el-table-column label="待收数量" min-width="120"><template #default="{ row }"><strong>{{ rowRemainingQuantity(row) }}</strong></template></el-table-column>
      <el-table-column label="仓库" min-width="130">
        <template #default="{ row }">{{ rowWarehouse(row) }}</template>
      </el-table-column>
      <el-table-column label="责任人" width="125"><template #default="{ row }">{{ row.source === 'PURCHASE' ? row.purchase.responsibleName : '仓储待确认' }}</template></el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canWrite && row.source === 'PURCHASE'" type="danger"
            :disabled="row.purchase.receiptConflict" @click="openReceive(row.purchase)">
            {{ row.purchase.activeReceiptId ? '继续收货' : '收货' }}
          </el-button>
          <el-button v-else-if="canWrite && row.source === 'CUSTOMER_SUPPLIED'" type="danger" @click="openCustomerReceive(row.customer)">收货</el-button>
          <el-button v-else-if="canWrite" type="danger" @click="openProductionReceive(row.production)">确认入库</el-button>
          <span v-else>只读</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="`采购收货 — ${selectedTask?.orderNumber || ''}`" width="920px" :close-on-click-modal="false">
      <template v-if="selectedTask">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="来源">采购订单</el-descriptions-item>
          <el-descriptions-item label="采购单号">{{ selectedTask.orderNumber }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ selectedTask.supplierName || selectedTask.supplierId }}</el-descriptions-item>
          <el-descriptions-item label="预计到货">{{ selectedTask.expectedDeliveryDate || '未维护' }}</el-descriptions-item>
          <el-descriptions-item label="已收">{{ unitGroups(selectedTask, 'receivedQuantity') }}</el-descriptions-item>
          <el-descriptions-item label="待收">{{ unitGroups(selectedTask, 'remainingReceivableQuantity') }}</el-descriptions-item>
        </el-descriptions>

        <template v-if="!receipt">
          <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" class="receive-form">
            <el-form-item label="本次收货日期" prop="receiveDate">
              <el-date-picker v-model="form.receiveDate" type="date" value-format="YYYY-MM-DD" />
            </el-form-item>
            <el-form-item label="目标仓库" prop="warehouseId">
              <el-select v-model="form.warehouseId" filterable placeholder="选择实际入库仓库" style="width:100%">
                <el-option v-for="warehouse in warehouseOptions" :key="warehouse.id"
                  :label="`${warehouse.name} (${warehouse.code}) · ${WAREHOUSE_TYPE_LABELS[warehouse.type]}`" :value="warehouse.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
          </el-form>
          <el-table :data="form.items" border>
            <el-table-column prop="materialName" label="物料" min-width="210" />
            <el-table-column label="本次实收" width="190">
              <template #default="{ row }">
                <el-input-number v-model="row.receivedQuantity" :min="0.001" :precision="3" :controls="false" style="width:120px" />
                <span class="unit-suffix">{{ displayUnit(row.unit) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="单位" width="90"><template #default="{ row }">{{ displayUnit(row.unit) }}</template></el-table-column>
          </el-table>
        </template>

        <template v-else>
          <el-alert type="warning" :closable="false" show-icon
            :title="`现有收货单 ${receipt.receiveNumber} · ${receipt.status === 'DRAFT' ? '待确认' : '待质检'}`"
            description="本订单已有活动收货单，系统不会重复创建；请继续上传凭证、打印或确认入库。" />
          <el-table :data="receipt.items" border class="receipt-lines">
            <el-table-column prop="materialName" label="物料" min-width="220" />
            <el-table-column label="本次实收" width="160"><template #default="{ row }">{{ fmtQty(row.receivedQuantity) }}{{ displayUnit(row.unit) }}</template></el-table-column>
          </el-table>
          <el-divider content-position="left">供应商供货单 / 收货凭证</el-divider>
          <AttachmentList entity-type="PURCHASE_RECEIPT" :entity-id="receipt.id" :factory-id="factoryId"
            :refresh-key="attachmentRefreshKey" empty-text="尚未上传供货凭证" />
          <AttachmentUploadButton v-if="canWrite" entity-type="PURCHASE_RECEIPT" :entity-id="receipt.id" :factory-id="factoryId"
            business-tag="RECEIVE_PHOTO" file-category="PHOTO" accept="image/*,.pdf,.xlsx,.xls" button-label="拍照 / 上传供货凭证"
            @uploaded="attachmentRefreshKey++" />
        </template>
      </template>

      <template #footer>
        <el-button @click="dialogVisible = false">关闭</el-button>
        <el-button v-if="receipt" :icon="Document" @click="safePrint('purchase-receipt', factoryId, receipt.id, { fileName: `收货单_${receipt.receiveNumber}` })">打印收货单</el-button>
        <el-button v-if="!receipt" type="primary" :loading="submitting" @click="createReceipt">创建收货单草稿</el-button>
        <el-button v-else-if="receipt.status === 'DRAFT'" type="success" :loading="confirming" @click="confirmReceipt">确认收货入库</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="customerDialogVisible"
      :title="`客户来料收货 · ${selectedCustomerTask?.salesOrderNumber || ''}`"
      width="760px"
      :close-on-click-modal="false"
    >
      <template v-if="selectedCustomerTask">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="客户">{{ selectedCustomerTask.customerName }}</el-descriptions-item>
          <el-descriptions-item label="销售订单">{{ selectedCustomerTask.salesOrderNumber }}</el-descriptions-item>
          <el-descriptions-item label="物料">{{ selectedCustomerTask.materialName }}</el-descriptions-item>
          <el-descriptions-item label="待收">{{ fmtQty(selectedCustomerTask.remainingQuantity) }}{{ displayUnit(selectedCustomerTask.unit) }}</el-descriptions-item>
          <el-descriptions-item label="目标仓库">{{ selectedCustomerTask.targetWarehouseName || selectedCustomerTask.targetWarehouseCode }}</el-descriptions-item>
          <el-descriptions-item label="所有权">客户所有（仅限该客户/订单）</el-descriptions-item>
        </el-descriptions>
        <el-form label-width="115px" class="receive-form">
          <el-form-item label="本次实收" required>
            <el-input-number v-model="customerReceiptForm.receivedQuantity" :min="0.0001"
              :max="Number(selectedCustomerTask.remainingQuantity)" :precision="4" :controls="false" style="width:220px" />
            <span class="unit-suffix">{{ displayUnit(selectedCustomerTask.unit) }}</span>
          </el-form-item>
          <el-form-item label="客户批次号"><el-input v-model="customerReceiptForm.externalBatchNumber" maxlength="100" /></el-form-item>
          <el-form-item label="生产日期"><el-date-picker v-model="customerReceiptForm.productionDate" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="到期日期"><el-date-picker v-model="customerReceiptForm.expireDate" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="产地"><el-input v-model="customerReceiptForm.originPlace" maxlength="200" /></el-form-item>
          <el-form-item label="备注"><el-input v-model="customerReceiptForm.notes" type="textarea" :rows="2" maxlength="500" /></el-form-item>
        </el-form>
        <el-divider content-position="left">客户送货单 / 收货凭证（确认前必传）</el-divider>
        <AttachmentList entity-type="CUSTOMER_SUPPLIED_RECEIPT" :entity-id="selectedCustomerTask.taskId"
          :factory-id="factoryId" :refresh-key="customerAttachmentRefreshKey" empty-text="尚未上传客户送货凭证" />
        <AttachmentUploadButton v-if="canWrite" entity-type="CUSTOMER_SUPPLIED_RECEIPT"
          :entity-id="selectedCustomerTask.taskId" :factory-id="factoryId" business-tag="RECEIVE_PHOTO"
          file-category="PHOTO" accept="image/*,.pdf,.xlsx,.xls" button-label="拍照 / 上传客户送货凭证"
          @uploaded="customerAttachmentRefreshKey++" />
      </template>
      <template #footer>
        <el-button @click="customerDialogVisible = false">取消</el-button>
        <el-button type="success" :loading="customerConfirming" @click="confirmCustomerReceipt">确认客户来料收货</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="productionDialogVisible"
      :title="`生产成品入库 · ${selectedProductionTask?.sourceNumber || ''}`"
      width="620px"
      :close-on-click-modal="false"
    >
      <el-descriptions v-if="selectedProductionTask" :column="2" border>
        <el-descriptions-item label="生产计划">{{ selectedProductionTask.sourceNumber }}</el-descriptions-item>
        <el-descriptions-item label="成品">{{ selectedProductionTask.productName }}</el-descriptions-item>
        <el-descriptions-item label="生产报产">{{ fmtQty(selectedProductionTask.reportedQuantity) }}{{ displayUnit(selectedProductionTask.unit) }}</el-descriptions-item>
        <el-descriptions-item label="目标仓库">{{ selectedProductionTask.toWarehouseName || '按计划与工厂配置' }}</el-descriptions-item>
      </el-descriptions>
      <el-form label-width="110px" style="margin-top: 16px">
        <el-form-item label="仓库实收" required>
          <el-input-number
            v-model="productionReceiptForm.receivedQuantity"
            :min="0.0001"
            :precision="4"
            :controls="false"
            style="width: 220px"
          />
          <span class="unit-suffix">{{ displayUnit(selectedProductionTask?.unit) }}</span>
        </el-form-item>
        <el-form-item label="差异说明">
          <el-input v-model="productionReceiptForm.note" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="productionDialogVisible = false">取消</el-button>
        <el-button type="success" :loading="productionConfirming" @click="confirmProductionReceipt">确认成品入库</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.receiving-task-panel { margin-bottom: 18px; }
.task-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.task-heading h3 { margin: 0 0 4px; font-size: 17px; }
.task-heading p { margin: 0; color: #606266; }
:deep(.pending-receive-row > td.el-table__cell) { background: #fff1f0 !important; }
:deep(.pending-receive-row:hover > td.el-table__cell) { background: #ffe5e3 !important; }
:deep(.pending-receive-row--focused > td.el-table__cell) { box-shadow: inset 0 2px #d93025, inset 0 -2px #d93025; }
.overdue { color: #b42318; font-weight: 700; }
.receive-form { margin-top: 16px; }
.unit-suffix { margin-left: 8px; color: #606266; }
.receipt-lines { margin: 12px 0; }
</style>
