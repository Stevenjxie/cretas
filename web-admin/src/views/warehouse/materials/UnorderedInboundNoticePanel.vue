<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { get } from '@/api/request';
import {
  cancelCustomerMaterialArrival,
  createCustomerMaterialArrival,
  listCustomerMaterialArrivals,
  type CustomerMaterialArrivalNotice,
  type UnorderedInboundReason,
} from '@/api/customerMaterialArrival';
import type { TableRow } from '@/types/api';

const props = defineProps<{ factoryId: string }>();
const emit = defineEmits<{ refreshed: [] }>();
const route = useRoute();

const loading = ref(false);
const submitting = ref(false);
const dialogVisible = ref(false);
const openOnly = ref(true);
const rows = ref<CustomerMaterialArrivalNotice[]>([]);
const customers = ref<TableRow[]>([]);

const form = reactive({
  reason: 'CUSTOMER_MATERIAL' as UnorderedInboundReason,
  customerId: '',
  expectedArrivalAt: '',
  contactName: '',
  contactPhone: '',
  remark: '',
});

const reasonText: Record<UnorderedInboundReason, string> = {
  CUSTOMER_MATERIAL: '客户来料',
  GIFT: '赠予',
  OTHER: '其他',
};
const statusText: Record<string, string> = {
  OPEN: '待收货',
  PARTIALLY_RECEIVED: '部分收货',
  RECEIVED: '已完成',
  CANCELLED: '已取消',
};
const customerRequired = computed(() => form.reason === 'CUSTOMER_MATERIAL');
const ownershipPreview = computed(() => customerRequired.value
  ? '客户所有：只能用于所选客户'
  : '公司所有：进入本厂普通库存');

async function load() {
  loading.value = true;
  try {
    const response = await listCustomerMaterialArrivals(props.factoryId, openOnly.value);
    rows.value = response.success && Array.isArray(response.data) ? response.data : [];
  } finally {
    loading.value = false;
  }
}

async function loadCustomers() {
  const response = await get(`/${props.factoryId}/customers`, {
    params: { page: 1, size: 200 },
    _silent: true,
  } as never);
  customers.value = Array.isArray(response.data?.content) ? response.data.content : [];
}

function openCreate() {
  Object.assign(form, {
    reason: 'CUSTOMER_MATERIAL', customerId: '', expectedArrivalAt: '',
    contactName: '', contactPhone: '', remark: '',
  });
  dialogVisible.value = true;
}

function onReasonChange() {
  if (!customerRequired.value) form.customerId = '';
}

async function submit() {
  if (customerRequired.value && !form.customerId) {
    ElMessage.warning('客户来料必须选择归属客户');
    return;
  }
  submitting.value = true;
  try {
    const response = await createCustomerMaterialArrival(props.factoryId, {
      reason: form.reason,
      customerId: form.customerId || undefined,
      expectedArrivalAt: form.expectedArrivalAt || undefined,
      contactName: form.contactName.trim() || undefined,
      contactPhone: form.contactPhone.trim() || undefined,
      remark: form.remark.trim() || undefined,
    });
    if (!response.success) throw new Error(response.message || '创建失败');
    ElMessage.success('无订单入库申请已发给仓储，当前没有增加库存');
    dialogVisible.value = false;
    await load();
    emit('refreshed');
  } finally {
    submitting.value = false;
  }
}

async function cancel(row: CustomerMaterialArrivalNotice) {
  try {
    await ElMessageBox.confirm(
      `确认取消申请 ${row.noticeNumber}？已有收货记录的申请不能取消。`,
      '取消无订单入库申请',
      { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '返回' },
    );
  } catch {
    return;
  }
  const response = await cancelCustomerMaterialArrival(props.factoryId, row.id);
  if (!response.success) throw new Error(response.message || '取消失败');
  ElMessage.success('申请已取消');
  await load();
  emit('refreshed');
}

function ownerText(row: CustomerMaterialArrivalNotice): string {
  return row.reason === 'CUSTOMER_MATERIAL'
    ? `客户所有 · ${row.customerName || '客户待核对'}`
    : '公司所有';
}

onMounted(async () => {
  await Promise.allSettled([load(), loadCustomers()]);
  if (route.query.action === 'unordered-inbound') openCreate();
});
</script>

<template>
  <section class="unordered-inbound-panel" aria-label="无订单入库申请">
    <div class="panel-heading">
      <div>
        <h3>无订单入库申请</h3>
        <p>客户来料、赠予或其他没有采购/销售订单的到货从这里发起；仓管确认实物后才增加库存。</p>
      </div>
      <el-button class="primary-action" type="primary" @click="openCreate">发起无订单入库</el-button>
    </div>

    <div class="toolbar">
      <el-switch v-model="openOnly" active-text="只看待收货" @change="load" />
      <el-button @click="load">刷新申请</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border empty-text="暂无无订单入库申请">
      <el-table-column prop="noticeNumber" label="申请单号" min-width="180" />
      <el-table-column label="入库原因" width="120">
        <template #default="{ row }">{{ reasonText[row.reason as UnorderedInboundReason] || row.reason }}</template>
      </el-table-column>
      <el-table-column label="库存归属" min-width="210">
        <template #default="{ row }">{{ ownerText(row) }}</template>
      </el-table-column>
      <el-table-column prop="expectedArrivalAt" label="预计到达" min-width="170" />
      <el-table-column label="收货进度" width="110">
        <template #default="{ row }">{{ row.receiptCount || 0 }} 批</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }"><el-tag>{{ statusText[row.status] || row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="remark" label="说明" min-width="220" show-overflow-tooltip />
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'OPEN' && !row.receiptCount" type="danger" link @click="cancel(row)">取消</el-button>
          <span v-else>—</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="发起无订单入库" width="min(560px, calc(100vw - 32px))" :close-on-click-modal="false">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="这里只登记到货原因；物料、数量和仓库由仓管看见实物后填写，提交不会直接增加库存。"
      />
      <el-form :model="form" label-width="96px" class="create-form">
        <el-form-item label="入库原因" required>
          <el-radio-group v-model="form.reason" @change="onReasonChange">
            <el-radio-button value="CUSTOMER_MATERIAL">客户来料</el-radio-button>
            <el-radio-button value="GIFT">赠予</el-radio-button>
            <el-radio-button value="OTHER">其他</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="customerRequired ? '归属客户' : '关联客户'" :required="customerRequired">
          <el-select v-model="form.customerId" filterable clearable :placeholder="customerRequired ? '必须选择库存归属客户' : '可选，不改变公司库存归属'" style="width: 100%">
            <el-option v-for="customer in customers" :key="customer.id" :label="customer.name" :value="String(customer.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="库存归属">
          <el-tag :type="customerRequired ? 'warning' : 'success'">{{ ownershipPreview }}</el-tag>
        </el-form-item>
        <el-form-item label="预计到达">
          <el-date-picker v-model="form.expectedArrivalAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
        </el-form-item>
        <el-form-item label="联系人"><el-input v-model="form.contactName" maxlength="100" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="form.contactPhone" maxlength="50" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">发送给仓储</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.unordered-inbound-panel { margin-bottom: 18px; padding: 18px; border: 1px solid var(--el-border-color-light); border-radius: 8px; }
.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.panel-heading h3 { margin: 0 0 6px; font-size: 18px; }
.panel-heading p { margin: 0; color: var(--el-text-color-secondary); }
.primary-action { min-height: 44px; min-width: 144px; }
.toolbar { display: flex; justify-content: flex-end; align-items: center; gap: 12px; margin: 14px 0; }
.create-form { margin-top: 18px; }
@media (max-width: 760px) {
  .panel-heading { flex-direction: column; }
  .primary-action { width: 100%; }
}
</style>
