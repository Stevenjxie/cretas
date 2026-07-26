<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { Document, Refresh } from '@element-plus/icons-vue';
import { get, post } from '@/api/request';
import {
  createCustomerSuppliedReceipt,
  getPendingPurchaseReceivingTasks,
  getPendingWarehouseReceivingTasks,
  getPurchaseInboundDefaultWarehouse,
  type CustomerSuppliedReceivingTask,
  type PurchaseReceivingTask,
  type PurchaseReceivingTaskItem,
  type WarehouseReceivingTask,
} from '@/api/purchaseReceive';
import {
  listWarehouses,
  WAREHOUSE_TYPE_LABELS,
  type FactoryWarehouse,
} from '@/api/factoryWarehouse';
import AttachmentList from '@/components/attachment/AttachmentList.vue';
import AttachmentDropZone from '@/components/attachment/AttachmentDropZone.vue';
import { safePrint } from '@/api/printApi';
import { displayUnit } from '@/utils/unitPricing';
import { fmtQty } from '@/utils/tableFormatters';
import {
  defaultPurchaseReceiveWarehouseId,
  purchaseReceiveWarehouseOptions,
} from '@/views/procurement/receives/purchaseReceiveWarehouse';

const props = defineProps<{ factoryId: string; canWrite: boolean }>();
const emit = defineEmits<{ refreshed: [] }>();
const route = useRoute();

interface ReceiveItemForm {
  purchaseOrderItemId?: number;
  materialTypeId: string;
  materialName: string;
  receivedQuantity: number;
  unit: string;
  materialPackagingSpecId?: string;
  packagingKey: string;
  inventoryBaseUnit: string;
  packageToBaseFactor: number;
  packagingOptions: PackagingOption[];
}

interface PackagingOption {
  key: string;
  id?: string;
  name: string;
  packageUnit: string;
  baseUnit: string;
  factor: number;
}

interface PurchaseReceiptDetail {
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
const tasks = ref<WarehouseReceivingTask[]>([]);
const dialogVisible = ref(false);
const openingTaskId = ref('');
const submitting = ref(false);
const confirming = ref(false);
const formRef = ref<FormInstance>();
const selectedTask = ref<PurchaseReceivingTask | null>(null);
const receipt = ref<PurchaseReceiptDetail | null>(null);
const warehouseOptions = ref<FactoryWarehouse[]>([]);
const attachmentRefreshKey = ref(0);
const attachmentQueue = ref({ pending: 0, failed: 0 });
const customerDialogVisible = ref(false);
const selectedCustomerTask = ref<CustomerSuppliedReceivingTask | null>(null);
const customerConfirming = ref(false);
const customerAttachmentRefreshKey = ref(0);
const customerAttachmentQueue = ref({ pending: 0, failed: 0 });
const customerIdempotencyKey = ref('');
const selectedCustomerLine = computed(() => selectedCustomerTask.value
  ? customerTaskItem(selectedCustomerTask.value)
  : null);
const customerForm = ref({
  receivedQuantity: 0,
  externalBatchNumber: '',
  productionDate: '',
  expireDate: '',
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

const exactPurchaseOrderId = computed(() => String(route.query.purchaseOrderId || '').trim());
const exactOrderNumber = computed(() => String(route.query.orderNo || route.query.orderNumber || '').trim());
const highlightedOrder = computed(() => exactOrderNumber.value || exactPurchaseOrderId.value);
const exactSalesOrderId = computed(() => String(route.query.salesOrderId || '').trim());
const exactSalesOrderNumber = computed(() => String(route.query.salesOrderNo || route.query.salesOrderNumber || '').trim());
const requestedSourceType = computed(() => {
  const sourceType = String(route.query.sourceType || '').trim().toUpperCase();
  return sourceType === 'SALES_ORDER_CUSTOMER_SUPPLIED'
    ? 'SALES_ORDER_CUSTOMER_SUPPLIED' as const
    : sourceType === 'PURCHASE'
      ? 'PURCHASE' as const
      : undefined;
});
const highlightedSource = computed(() => highlightedOrder.value || exactSalesOrderNumber.value || exactSalesOrderId.value);

function isPurchaseTask(task: WarehouseReceivingTask): task is PurchaseReceivingTask {
  return task.sourceType === 'PURCHASE';
}

function isCustomerSuppliedTask(task: WarehouseReceivingTask): task is CustomerSuppliedReceivingTask {
  return task.sourceType === 'SALES_ORDER_CUSTOMER_SUPPLIED';
}

function localDateText(): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function unitGroups(
  task: WarehouseReceivingTask,
  field: 'orderedQuantity' | 'receivedQuantity' | 'remainingReceivableQuantity',
) {
  const groups = new Map<string, number>();
  for (const item of task.items || []) {
    const unit = displayUnit(item.unit) || '未配置';
    groups.set(unit, (groups.get(unit) || 0) + Number(item[field] || 0));
  }
  return Array.from(groups.entries())
    .map(([unit, quantity]) => `${fmtQty(quantity)}${unit}`)
    .join(' + ') || '—';
}

function materialSummary(task: WarehouseReceivingTask): string {
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
    const response = exactSalesOrderId.value || exactSalesOrderNumber.value || requestedSourceType.value
      ? await getPendingWarehouseReceivingTasks(props.factoryId, {
          purchaseOrderId: exactPurchaseOrderId.value || undefined,
          orderNumber: exactPurchaseOrderId.value ? undefined : exactOrderNumber.value || undefined,
          salesOrderId: exactSalesOrderId.value || undefined,
          salesOrderNo: exactSalesOrderId.value ? undefined : exactSalesOrderNumber.value || undefined,
          sourceType: requestedSourceType.value,
        })
      : await getPendingPurchaseReceivingTasks(props.factoryId, {
          purchaseOrderId: exactPurchaseOrderId.value || undefined,
          orderNumber: exactPurchaseOrderId.value ? undefined : exactOrderNumber.value || undefined,
        });
    const rows = response.success && Array.isArray(response.data) ? response.data : [];
    tasks.value = rows.filter((task) => {
      if (requestedSourceType.value && task.sourceType !== requestedSourceType.value) return false;
      if (isCustomerSuppliedTask(task) && exactSalesOrderId.value && task.salesOrderId !== exactSalesOrderId.value) return false;
      if (isCustomerSuppliedTask(task) && !exactSalesOrderId.value && exactSalesOrderNumber.value
        && task.salesOrderNo !== exactSalesOrderNumber.value) return false;
      return true;
    });
  } finally {
    loading.value = false;
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
  const configuredDefault = defaultWarehouse.data || null;
  warehouseOptions.value = purchaseReceiveWarehouseOptions(warehouses.data || [], configuredDefault);
  if (!form.value.warehouseId) {
    form.value.warehouseId = defaultPurchaseReceiveWarehouseId(
      warehouseOptions.value,
      configuredDefault,
    );
  }
}

async function loadExistingReceipt(receiptId: string) {
  const response = await get<PurchaseReceiptDetail>(`/${props.factoryId}/warehouse/receiving/receipts/${receiptId}`);
  if (!response.success || !response.data) throw new Error('活动收货单加载失败');
  receipt.value = response.data;
}

async function openReceive(task: PurchaseReceivingTask) {
  if (openingTaskId.value) return;
  if (task.receiptConflict) {
    ElMessage.error(task.activeReceiptCount > 1
      ? `该采购单存在 ${task.activeReceiptCount} 张活动收货草稿，请先由仓储主管核对；系统不会自动取消或合并历史草稿`
      : '该历史收货草稿缺少明确的采购订单行身份，系统无法安全分配同物料多行；请由仓储主管核对');
    return;
  }
  openingTaskId.value = task.purchaseOrderId;
  try {
    selectedTask.value = task;
    receipt.value = null;
    attachmentRefreshKey.value = 0;
    attachmentQueue.value = { pending: 0, failed: 0 };
    form.value = {
      purchaseOrderId: task.purchaseOrderId,
      supplierId: task.supplierId,
      receiveDate: localDateText(),
      warehouseId: task.warehouseId || '',
      remark: '',
      items: task.items
        .filter((item) => Number(item.remainingReceivableQuantity) > 0)
        .map((item) => ({
          ...(() => {
            const orderOption: PackagingOption = {
              key: '__ORDER__',
              id: item.materialPackagingSpecId || undefined,
              name: '采购单规格',
              packageUnit: item.unit,
              baseUnit: item.inventoryBaseUnit || item.unit,
              factor: Number(item.packageToBaseFactor || 1),
            };
            const masterOptions: PackagingOption[] = (item.packagingSpecs || []).map((spec) => ({
              key: spec.id,
              id: spec.id,
              name: spec.name,
              packageUnit: spec.packageUnit,
              baseUnit: spec.baseUnit,
              factor: Number(spec.conversionFactor),
            }));
            const selected = masterOptions.find((option) => option.id === item.materialPackagingSpecId)
              || masterOptions.find((option) =>
                option.packageUnit === item.unit
                && option.baseUnit === item.inventoryBaseUnit
                && option.factor === Number(item.packageToBaseFactor || 1))
              || orderOption;
            const options = selected.key === '__ORDER__'
              ? [orderOption, ...masterOptions]
              : masterOptions;
            return {
              materialPackagingSpecId: selected.id,
              packagingKey: selected.key,
              inventoryBaseUnit: selected.baseUnit,
              packageToBaseFactor: selected.factor,
              packagingOptions: options,
              unit: selected.packageUnit,
            };
          })(),
          purchaseOrderItemId: item.purchaseOrderItemId,
          materialTypeId: item.materialTypeId,
          materialName: item.materialName,
          receivedQuantity: Number(item.remainingReceivableQuantity),
        })),
    };
    await loadWarehouses();
    if (task.activeReceiptId) await loadExistingReceipt(task.activeReceiptId);
    dialogVisible.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '收货任务加载失败，请刷新后重试');
    selectedTask.value = null;
    receipt.value = null;
  } finally {
    openingTaskId.value = '';
  }
}

function remainingLimit(row: ReceiveItemForm): number {
  const line = selectedTask.value?.items.find((item) =>
    item.purchaseOrderItemId === row.purchaseOrderItemId);
  const orderFactor = Number(line?.packageToBaseFactor || 1);
  const selectedFactor = Number(row.packageToBaseFactor || 1);
  return Number(line?.remainingReceivableQuantity || 0) * orderFactor / selectedFactor;
}

function onPackagingChange(row: ReceiveItemForm) {
  const selected = row.packagingOptions.find((option) => option.key === row.packagingKey);
  if (!selected) return;
  row.materialPackagingSpecId = selected.id;
  row.unit = selected.packageUnit;
  row.inventoryBaseUnit = selected.baseUnit;
  row.packageToBaseFactor = selected.factor;
  row.receivedQuantity = Number(remainingLimit(row).toFixed(4));
}

function baseQuantityPreview(row: ReceiveItemForm): string {
  const quantity = Number(row.receivedQuantity || 0) * Number(row.packageToBaseFactor || 1);
  return `${fmtQty(quantity)}${displayUnit(row.inventoryBaseUnit)}`;
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
    const payload = {
      ...form.value,
      items: form.value.items.map((item) => ({
        purchaseOrderItemId: item.purchaseOrderItemId,
        materialTypeId: item.materialTypeId,
        materialName: item.materialName,
        receivedQuantity: item.receivedQuantity,
        unit: item.unit,
        materialPackagingSpecId: item.materialPackagingSpecId,
      })),
    };
    const response = await post<PurchaseReceiptDetail>(`/${props.factoryId}/warehouse/receiving/receipts`, payload);
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
  confirming.value = true;
  try {
    if (attachmentQueue.value.pending > 0) {
      ElMessage.warning('附件仍在上传，请等待完成后再确认入库');
      return;
    }
    if (attachmentQueue.value.failed > 0) {
      ElMessage.error('存在上传失败的附件，请重试或删除后再确认入库');
      return;
    }
    const lines = (receipt.value.items || [])
      .map((item) => `• ${item.materialName}：${fmtQty(item.receivedQuantity)}${displayUnit(item.unit)}`)
      .join('\n');
    await ElMessageBox.confirm(
      `${lines}\n\n确认后才会生成库存批次；重复确认不会重复入库。`,
      `确认收货入库 — ${receipt.value.receiveNumber}`,
      { type: 'warning', confirmButtonText: '确认收货入库', cancelButtonText: '返回核对' },
    );
    await post(`/${props.factoryId}/warehouse/receiving/receipts/${receipt.value.id}/confirm`);
    ElMessage.success('收货入库完成，库存批次已生成');
    dialogVisible.value = false;
    await loadTasks();
    emit('refreshed');
  } finally {
    confirming.value = false;
  }
}

function customerTaskItem(task: CustomerSuppliedReceivingTask): PurchaseReceivingTaskItem | null {
  return task.items?.[0] || null;
}

async function openCustomerReceive(task: CustomerSuppliedReceivingTask) {
  if (openingTaskId.value) return;
  const line = customerTaskItem(task);
  if (!line) {
    ElMessage.error('客供料任务缺少物料明细，无法安全收货');
    return;
  }
  if (!(Number(line.remainingReceivableQuantity) > 0)) {
    ElMessage.warning('该客供料需求已无待收数量，请刷新任务列表');
    return;
  }
  openingTaskId.value = task.taskId;
  try {
    selectedCustomerTask.value = task;
    customerAttachmentRefreshKey.value = 0;
    customerAttachmentQueue.value = { pending: 0, failed: 0 };
    customerIdempotencyKey.value = `warehouse-customer-receipt-${task.taskId}-${Date.now()}`;
    customerForm.value = {
      receivedQuantity: Number(line.remainingReceivableQuantity),
      externalBatchNumber: '',
      productionDate: '',
      expireDate: '',
      originPlace: '',
      notes: '',
    };
    customerDialogVisible.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '客供料收货任务加载失败，请刷新后重试');
    selectedCustomerTask.value = null;
  } finally {
    openingTaskId.value = '';
  }
}

async function confirmCustomerReceipt() {
  const task = selectedCustomerTask.value;
  const line = task ? customerTaskItem(task) : null;
  if (!task || !line || customerConfirming.value) return;
  const quantity = Number(customerForm.value.receivedQuantity);
  if (!(quantity > 0)) return ElMessage.warning('本次实收必须大于 0');
  if (quantity > Number(line.remainingReceivableQuantity)) {
    return ElMessage.error(`本次实收不能超过待收 ${fmtQty(line.remainingReceivableQuantity)}${displayUnit(line.unit)}`);
  }
  if (customerAttachmentQueue.value.pending > 0) {
    ElMessage.warning('附件仍在上传，请等待完成后再确认入库');
    return;
  }
  if (customerAttachmentQueue.value.failed > 0) {
    ElMessage.error('存在上传失败的附件，请重试或删除后再确认入库');
    return;
  }
  customerConfirming.value = true;
  try {
    await ElMessageBox.confirm(
      `销售订单：${task.salesOrderNo}\n客户：${task.customerName}\n物料：${line.materialName}\n本次实收：${fmtQty(quantity)}${displayUnit(line.unit)}\n所有权：客户所有（仅限该客户/订单）\n\n确认后将直接生成客户所有库存批次；重复请求不会重复入库。`,
      '确认客户来料入库',
      { type: 'warning', confirmButtonText: '确认客户来料入库', cancelButtonText: '返回核对' },
    );
    const response = await createCustomerSuppliedReceipt(props.factoryId, task.taskId, {
      ...customerForm.value,
      unit: line.unit,
      idempotencyKey: customerIdempotencyKey.value,
    });
    if (!response.success) return;
    ElMessage.success('客户来料收货完成，客户所有库存批次已生成');
    customerDialogVisible.value = false;
    await loadTasks();
    emit('refreshed');
  } finally {
    customerConfirming.value = false;
  }
}

function sourceLabel(task: WarehouseReceivingTask): string {
  return isPurchaseTask(task) ? '采购入库' : '客户来料';
}

function sourceNumber(task: WarehouseReceivingTask): string {
  return isPurchaseTask(task) ? task.orderNumber : task.salesOrderNo;
}

function counterparty(task: WarehouseReceivingTask): string {
  return isPurchaseTask(task) ? (task.supplierName || task.supplierId) : task.customerName;
}

function expectedArrival(task: WarehouseReceivingTask): string {
  return isPurchaseTask(task)
    ? (task.expectedDeliveryDate || '未维护')
    : (task.expectedArrivalAt ? String(task.expectedArrivalAt).slice(0, 10) : '未维护');
}

function taskMaterialSummary(task: WarehouseReceivingTask): string {
  return materialSummary(task);
}

function plannedQuantity(task: WarehouseReceivingTask): string {
  return unitGroups(task, 'orderedQuantity');
}

function receivedQuantity(task: WarehouseReceivingTask): string {
  return unitGroups(task, 'receivedQuantity');
}

function remainingQuantity(task: WarehouseReceivingTask): string {
  return unitGroups(task, 'remainingReceivableQuantity');
}

function taskWarehouse(task: WarehouseReceivingTask): string {
  return task.warehouseName || (task.warehouseId ? '已选择' : '收货时选择');
}

function taskRowClass({ row }: { row: WarehouseReceivingTask }) {
  const focused = isPurchaseTask(row)
    ? Boolean(highlightedOrder.value
      && (row.orderNumber === highlightedOrder.value || row.purchaseOrderId === highlightedOrder.value))
    : Boolean((exactSalesOrderId.value && row.salesOrderId === exactSalesOrderId.value)
      || (exactSalesOrderNumber.value && row.salesOrderNo === exactSalesOrderNumber.value));
  return focused
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
        <p>审批完成的采购订单和客户自带原料会进入这里；打开和刷新只查询数据，不会创建收货单或库存。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadTasks">刷新待办</el-button>
    </div>

    <el-alert
      v-if="highlightedSource && tasks.length === 0 && !loading"
      type="warning"
      :closable="false"
      show-icon
      :title="`未找到 ${highlightedSource} 的可收货任务`"
      description="请确认来源订单已完成审批且仍有待收数量；本页面不会自动补写、桥接历史数据或创建库存。"
    />

    <el-table
      :data="tasks"
      v-loading="loading"
      :row-class-name="taskRowClass"
      empty-text="暂无待收货任务"
      border
      row-key="taskId"
    >
      <el-table-column label="状态" width="115" fixed="left">
        <template #default="{ row }">
          <el-tag type="danger" effect="dark">{{ row.receiptConflict ? '收货草稿冲突' : row.statusLabel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="105"><template #default="{ row }">{{ sourceLabel(row) }}</template></el-table-column>
      <el-table-column label="来源单号" min-width="175" show-overflow-tooltip>
        <template #default="{ row }">{{ sourceNumber(row) }}</template>
      </el-table-column>
      <el-table-column label="供应商 / 客户" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">{{ counterparty(row) }}</template>
      </el-table-column>
      <el-table-column label="预计到货" width="125">
        <template #default="{ row }">
          <span :class="{ overdue: isPurchaseTask(row) && isOverdue(row) }">{{ expectedArrival(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="物料 / 待收" min-width="260" show-overflow-tooltip>
        <template #default="{ row }">{{ taskMaterialSummary(row) }}</template>
      </el-table-column>
      <el-table-column label="计划数量" min-width="120">
        <template #default="{ row }">{{ plannedQuantity(row) }}</template>
      </el-table-column>
      <el-table-column label="已收数量" min-width="120">
        <template #default="{ row }">{{ receivedQuantity(row) }}</template>
      </el-table-column>
      <el-table-column label="待收数量" min-width="120">
        <template #default="{ row }"><strong>{{ remainingQuantity(row) }}</strong></template>
      </el-table-column>
      <el-table-column label="仓库" min-width="130">
        <template #default="{ row }">{{ taskWarehouse(row) }}</template>
      </el-table-column>
      <el-table-column label="责任人" width="125">
        <template #default="{ row }">{{ row.responsibleName || '仓储待确认' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canWrite && isPurchaseTask(row)" type="danger" :disabled="row.receiptConflict || Boolean(openingTaskId)"
            :loading="openingTaskId === row.purchaseOrderId" @click="openReceive(row)">
            {{ row.activeReceiptId ? '继续收货' : '收货' }}
          </el-button>
          <el-button v-else-if="canWrite" type="danger" :disabled="row.receiptConflict || Boolean(openingTaskId)"
            :loading="openingTaskId === row.taskId" @click="openCustomerReceive(row)">
            {{ row.activeReceiptId ? '继续收货' : '收货' }}
          </el-button>
          <span v-else>只读</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="`采购收货 — ${selectedTask?.orderNumber || ''}`"
      width="min(920px, calc(100vw - 32px))" :close-on-click-modal="false">
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
            <el-table-column label="实际到货包装" min-width="255">
              <template #default="{ row }">
                <el-select v-model="row.packagingKey" style="width:100%" @change="onPackagingChange(row)">
                  <el-option
                    v-for="option in row.packagingOptions"
                    :key="option.key"
                    :label="`${option.name} · 1${displayUnit(option.packageUnit)}=${fmtQty(option.factor)}${displayUnit(option.baseUnit)}`"
                    :value="option.key"
                  />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="本次实收" width="190">
              <template #default="{ row }">
                <el-input-number v-model="row.receivedQuantity" :min="0.001" :max="remainingLimit(row)"
                  :precision="3" :controls="false" style="width:120px" />
                <span class="unit-suffix">{{ displayUnit(row.unit) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="折合基本量" width="150">
              <template #default="{ row }">{{ baseQuantityPreview(row) }}</template>
            </el-table-column>
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
          <AttachmentDropZone v-if="canWrite" entity-type="PURCHASE_RECEIPT" :entity-id="receipt.id" :factory-id="factoryId"
            business-tag="RECEIVE_PHOTO" file-category="PHOTO" accept="image/*,.pdf,.xlsx,.xls"
            @uploaded="attachmentRefreshKey++"
            @queue-change="attachmentQueue = { pending: $event.pending, failed: $event.failed }" />
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
      :title="`客户来料收货 — ${selectedCustomerTask?.salesOrderNo || ''}`"
      width="min(820px, calc(100vw - 32px))"
      :close-on-click-modal="false"
    >
      <template v-if="selectedCustomerTask && selectedCustomerLine">
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="来源、客户、物料、单位和目标仓库均由销售订单锁定，仓储只能填写本次实际收货事实。"
        />
        <el-descriptions :column="2" border class="customer-task-context">
          <el-descriptions-item label="来源">客户自带原料</el-descriptions-item>
          <el-descriptions-item label="销售订单">{{ selectedCustomerTask.salesOrderNo }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ selectedCustomerTask.customerName }}</el-descriptions-item>
          <el-descriptions-item label="所有权">客户所有（仅限该客户 / 订单）</el-descriptions-item>
          <el-descriptions-item label="原料">{{ selectedCustomerLine.materialName }}</el-descriptions-item>
          <el-descriptions-item label="目标仓库">{{ taskWarehouse(selectedCustomerTask) }}</el-descriptions-item>
          <el-descriptions-item label="预计来料">{{ fmtQty(selectedCustomerLine.orderedQuantity) }}{{ displayUnit(selectedCustomerLine.unit) }}</el-descriptions-item>
          <el-descriptions-item label="已收 / 待收">
            {{ fmtQty(selectedCustomerLine.receivedQuantity) }}{{ displayUnit(selectedCustomerLine.unit) }} /
            {{ fmtQty(selectedCustomerLine.remainingReceivableQuantity) }}{{ displayUnit(selectedCustomerLine.unit) }}
          </el-descriptions-item>
        </el-descriptions>

        <el-form label-width="112px" class="receive-form">
          <el-form-item label="本次实收" required>
            <el-input-number
              v-model="customerForm.receivedQuantity"
              :min="0.0001"
              :max="Number(selectedCustomerLine.remainingReceivableQuantity)"
              :precision="4"
              :controls="false"
              style="width: 220px"
            />
            <span class="unit-suffix">{{ displayUnit(selectedCustomerLine.unit) }}</span>
            <span class="quantity-limit">最多可收 {{ fmtQty(selectedCustomerLine.remainingReceivableQuantity) }}{{ displayUnit(selectedCustomerLine.unit) }}</span>
          </el-form-item>
          <el-form-item label="客户批次号"><el-input v-model="customerForm.externalBatchNumber" maxlength="100" /></el-form-item>
          <el-row :gutter="12">
            <el-col :span="12"><el-form-item label="生产日期"><el-date-picker v-model="customerForm.productionDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="到期日期"><el-date-picker v-model="customerForm.expireDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item></el-col>
          </el-row>
          <el-form-item label="产地"><el-input v-model="customerForm.originPlace" maxlength="200" /></el-form-item>
          <el-form-item label="备注"><el-input v-model="customerForm.notes" type="textarea" :rows="2" maxlength="500" /></el-form-item>
        </el-form>

        <el-divider content-position="left">客户送货单 / 收货凭证</el-divider>
        <AttachmentList entity-type="CUSTOMER_SUPPLIED_RECEIPT" :entity-id="selectedCustomerTask.taskId" :factory-id="factoryId"
          :refresh-key="customerAttachmentRefreshKey" empty-text="尚未上传客户送货凭证" />
        <AttachmentDropZone v-if="canWrite" entity-type="CUSTOMER_SUPPLIED_RECEIPT" :entity-id="selectedCustomerTask.taskId" :factory-id="factoryId"
          business-tag="RECEIVE_PHOTO" file-category="PHOTO" accept="image/*,.pdf,.xlsx,.xls"
          @uploaded="customerAttachmentRefreshKey++"
          @queue-change="customerAttachmentQueue = { pending: $event.pending, failed: $event.failed }" />
      </template>

      <template #footer>
        <el-button @click="customerDialogVisible = false">关闭</el-button>
        <el-button type="success" :loading="customerConfirming" @click="confirmCustomerReceipt">确认客户来料入库</el-button>
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
.customer-task-context { margin-top: 12px; }
.quantity-limit { margin-left: 12px; color: #606266; font-size: 12px; }
@media (max-width: 720px) {
  .task-heading { flex-direction: column; }
  :deep(.el-dialog__body) { padding: 12px; overflow-x: auto; }
}
</style>
