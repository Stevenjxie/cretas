<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { Document, Refresh } from '@element-plus/icons-vue';
import { get, post } from '@/api/request';
import {
  getPendingPurchaseReceivingTasks,
  getPurchaseInboundDefaultWarehouse,
  type PurchaseReceivingTask,
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
const dialogVisible = ref(false);
const submitting = ref(false);
const confirming = ref(false);
const formRef = ref<FormInstance>();
const selectedTask = ref<PurchaseReceivingTask | null>(null);
const receipt = ref<ReceiptDetail | null>(null);
const warehouseOptions = ref<FactoryWarehouse[]>([]);
const attachmentRefreshKey = ref(0);
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

function localDateText(): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function unitGroups(
  task: PurchaseReceivingTask,
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
    const response = await getPendingPurchaseReceivingTasks(props.factoryId, {
      purchaseOrderId: exactPurchaseOrderId.value || undefined,
      orderNumber: exactPurchaseOrderId.value ? undefined : exactOrderNumber.value || undefined,
    });
    tasks.value = response.success && Array.isArray(response.data) ? response.data : [];
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
  attachmentRefreshKey.value = 0;
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

function taskRowClass({ row }: { row: PurchaseReceivingTask }) {
  return highlightedOrder.value
    && (row.orderNumber === highlightedOrder.value || row.purchaseOrderId === highlightedOrder.value)
    ? 'pending-receive-row pending-receive-row--focused'
    : 'pending-receive-row';
}

onMounted(loadTasks);
defineExpose({ loadTasks });
</script>

<template>
  <section class="receiving-task-panel" aria-label="采购待收货任务">
    <div class="task-heading">
      <div>
        <h3>采购待收货 / 待入库任务</h3>
        <p>OA 与财务审批完成的采购订单会进入这里；打开和刷新只查询数据，不会创建收货单或库存。</p>
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
      :data="tasks"
      v-loading="loading"
      :row-class-name="taskRowClass"
      empty-text="暂无采购待收货任务"
      border
      row-key="taskId"
    >
      <el-table-column label="状态" width="115" fixed="left">
        <template #default="{ row }">
          <el-tag type="danger" effect="dark">{{ row.receiptConflict ? '收货草稿冲突' : row.statusLabel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="105">采购入库</el-table-column>
      <el-table-column prop="orderNumber" label="采购单号" min-width="175" show-overflow-tooltip />
      <el-table-column label="供应商" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">{{ row.supplierName || row.supplierId }}</template>
      </el-table-column>
      <el-table-column label="预计到货" width="125">
        <template #default="{ row }">
          <span :class="{ overdue: isOverdue(row) }">{{ row.expectedDeliveryDate || '未维护' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="物料 / 待收" min-width="260" show-overflow-tooltip>
        <template #default="{ row }">{{ materialSummary(row) }}</template>
      </el-table-column>
      <el-table-column label="采购数量" min-width="120">
        <template #default="{ row }">{{ unitGroups(row, 'orderedQuantity') }}</template>
      </el-table-column>
      <el-table-column label="已收数量" min-width="120">
        <template #default="{ row }">{{ unitGroups(row, 'receivedQuantity') }}</template>
      </el-table-column>
      <el-table-column label="待收数量" min-width="120">
        <template #default="{ row }"><strong>{{ unitGroups(row, 'remainingReceivableQuantity') }}</strong></template>
      </el-table-column>
      <el-table-column label="仓库" min-width="130">
        <template #default="{ row }">{{ row.warehouseName || (row.warehouseId ? '已选择' : '收货时选择') }}</template>
      </el-table-column>
      <el-table-column prop="responsibleName" label="责任人" width="125">
        <template #default="{ row }">{{ row.responsibleName || '仓储待确认' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canWrite" type="danger" :disabled="row.receiptConflict" @click="openReceive(row)">
            {{ row.activeReceiptId ? '继续收货' : '收货' }}
          </el-button>
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
