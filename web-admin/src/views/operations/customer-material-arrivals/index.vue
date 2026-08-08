<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import {
  cancelCustomerMaterialArrival,
  createCustomerMaterialArrival,
  listCustomerMaterialArrivals,
  type CustomerMaterialArrivalNotice,
} from '@/api/customerMaterialArrival';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const factoryId = computed(() => String(authStore.factoryId || ''));
const loading = ref(false);
const submitting = ref(false);
const dialogVisible = ref(false);
const openOnly = ref(true);
const rows = ref<CustomerMaterialArrivalNotice[]>([]);
const customers = ref<TableRow[]>([]);

const form = reactive({
  customerId: '',
  expectedArrivalAt: '',
  contactName: '',
  contactPhone: '',
  remark: '',
});

const statusText: Record<string, string> = {
  OPEN: '待收货',
  PARTIALLY_RECEIVED: '部分收货',
  RECEIVED: '已完成',
  CANCELLED: '已取消',
};

async function load() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const response = await listCustomerMaterialArrivals(factoryId.value, openOnly.value);
    rows.value = response.success && Array.isArray(response.data) ? response.data : [];
  } finally {
    loading.value = false;
  }
}

async function loadCustomers() {
  if (!factoryId.value) return;
  const response = await get(`/${factoryId.value}/customers`, {
    params: { page: 1, size: 200 },
    _silent: true,
  } as never);
  customers.value = Array.isArray(response.data?.content) ? response.data.content : [];
}

function openCreate() {
  Object.assign(form, {
    customerId: '', expectedArrivalAt: '', contactName: '', contactPhone: '', remark: '',
  });
  dialogVisible.value = true;
}

async function submit() {
  if (!form.customerId) return ElMessage.warning('请选择归属客户');
  submitting.value = true;
  try {
    const response = await createCustomerMaterialArrival(factoryId.value, {
      customerId: form.customerId,
      expectedArrivalAt: form.expectedArrivalAt || undefined,
      contactName: form.contactName.trim() || undefined,
      contactPhone: form.contactPhone.trim() || undefined,
      remark: form.remark.trim() || undefined,
    });
    if (!response.success) throw new Error(response.message || '创建失败');
    ElMessage.success('来料预告已发给仓储');
    dialogVisible.value = false;
    await load();
  } finally {
    submitting.value = false;
  }
}

async function cancel(row: CustomerMaterialArrivalNotice) {
  await ElMessageBox.confirm(
    `确认取消预告 ${row.noticeNumber}？已有收货记录的预告不能取消。`,
    '取消来料预告',
    { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '返回' },
  );
  const response = await cancelCustomerMaterialArrival(factoryId.value, row.id);
  if (!response.success) throw new Error(response.message || '取消失败');
  ElMessage.success('已取消');
  await load();
}

onMounted(() => Promise.allSettled([load(), loadCustomers()]));
</script>

<template>
  <div class="arrival-page">
    <el-card shadow="never">
      <template #header>
        <div class="page-head">
          <div>
            <h2>客户来料预告</h2>
            <p>这里只通知仓储“哪个客户会来料”，不填物料和数量，也不会直接增加库存。</p>
          </div>
          <el-button type="primary" @click="openCreate">新建预告</el-button>
        </div>
      </template>

      <div class="toolbar">
        <el-switch v-model="openOnly" active-text="只看待收货" @change="load" />
        <el-button @click="load">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" border empty-text="暂无来料预告">
        <el-table-column prop="noticeNumber" label="预告单号" min-width="180" />
        <el-table-column prop="customerName" label="归属客户" min-width="180" />
        <el-table-column prop="expectedArrivalAt" label="预计到达" min-width="170" />
        <el-table-column label="收货进度" width="130">
          <template #default="{ row }">{{ row.receiptCount || 0 }} 批</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
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
    </el-card>

    <el-dialog v-model="dialogVisible" title="新建客户来料预告" width="520px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="不用猜物料和数量；仓管看到实物后再按实际收货。"
      />
      <el-form :model="form" label-width="92px" class="create-form">
        <el-form-item label="归属客户" required>
          <el-select v-model="form.customerId" filterable placeholder="请选择客户" style="width: 100%">
            <el-option v-for="customer in customers" :key="customer.id" :label="customer.name" :value="String(customer.id)" />
          </el-select>
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
  </div>
</template>

<style scoped>
.arrival-page { padding: 20px; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.page-head h2 { margin: 0 0 6px; font-size: 20px; }
.page-head p { margin: 0; color: var(--el-text-color-secondary); }
.toolbar { display: flex; justify-content: flex-end; align-items: center; gap: 12px; margin-bottom: 14px; }
.create-form { margin-top: 18px; }
</style>
