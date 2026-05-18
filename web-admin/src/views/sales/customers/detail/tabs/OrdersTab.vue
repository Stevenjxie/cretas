<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: tab 7 销售单 by customer.
  Backend: GET /api/mobile/{factoryId}/sales/orders/by-customer?customerId= (Phase C C1).
  RBAC: canViewPrice mask on totalAmount / paidAmount columns per spec §7.2.
-->
<template>
  <div class="orders-tab">
    <div class="toolbar">
      <span class="title">销售单</span>
      <el-button :icon="Refresh" @click="fetchList" :loading="state === 'loading'">刷新</el-button>
    </div>

    <el-skeleton v-if="state === 'loading'" :rows="5" animated />

    <el-empty v-else-if="state === 'empty'" description="该客户暂无销售单" :image-size="80">
      <el-button type="primary" @click="goCreate">创建销售单</el-button>
    </el-empty>

    <div v-else-if="state === 'error'" class="error-panel">
      <el-result icon="error" :title="errorMsg">
        <template #extra>
          <el-button type="primary" @click="fetchList">重试</el-button>
        </template>
      </el-result>
    </div>

    <template v-else>
      <el-table :data="orders" border stripe size="small">
        <el-table-column label="订单号" prop="orderNumber" width="170" />
        <el-table-column label="下单日期" prop="orderDate" width="120" />
        <el-table-column label="状态" prop="status" width="120">
          <template #default="{ row }">
            <el-tag size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="业务员" prop="salesperson" width="100" />
        <el-table-column label="总额" align="right" width="140">
          <template #default="{ row }">
            <span v-if="canViewPrice">{{ formatMoney(row.totalAmount) }}</span>
            <span v-else class="masked">****</span>
          </template>
        </el-table-column>
        <el-table-column label="已收款" align="right" width="140">
          <template #default="{ row }">
            <span v-if="canViewPrice">{{ formatMoney(row.paidAmount) }}</span>
            <span v-else class="masked">****</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goDetail(row.id)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pagination"
        background
        layout="prev, pager, next, total"
        :total="totalElements"
        :page-size="pageSize"
        :current-page="currentPage"
        @current-change="onPageChange"
      />
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { storeToRefs } from 'pinia';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';

interface SalesOrderRow {
  id: string;
  orderNumber: string;
  orderDate: string;
  status: string;
  salesperson?: string;
  totalAmount?: number;
  paidAmount?: number;
  customerId: string;
}

interface PageResp {
  content: SalesOrderRow[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

const props = defineProps<{ customerId: string }>();

const router = useRouter();
const authStore = useAuthStore();
const { factoryId } = storeToRefs(authStore);
const permissionStore = usePermissionStore();
const { canViewPrice } = storeToRefs(permissionStore);

const state = ref<'loading' | 'ready' | 'empty' | 'error'>('loading');
const errorMsg = ref('');
const orders = ref<SalesOrderRow[]>([]);
const totalElements = ref(0);
const currentPage = ref(1);
const pageSize = 20;

function formatMoney(v: number | null | undefined): string {
  if (v == null) return '—';
  return Number(v).toLocaleString('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    minimumFractionDigits: 2,
  });
}

async function fetchList() {
  if (!factoryId.value) return;
  state.value = 'loading';
  try {
    const res = await get<PageResp>(`/${factoryId.value}/sales/orders/by-customer`, {
      params: { customerId: props.customerId, page: currentPage.value, size: pageSize },
    });
    if (!res.success || !res.data) {
      throw new Error(res.message || '加载失败');
    }
    orders.value = res.data.content;
    totalElements.value = res.data.totalElements;
    state.value = orders.value.length === 0 ? 'empty' : 'ready';
  } catch (e: any) {
    handleError(e);
  }
}

function handleError(e: any) {
  const status = e?.response?.status || e?.code;
  const backendMsg = e?.response?.data?.message || e?.message;
  if (status === 403) {
    state.value = 'error';
    errorMsg.value = '无权查看此客户的销售单';
  } else {
    state.value = 'error';
    errorMsg.value = backendMsg || '加载失败';
    ElMessage({ message: errorMsg.value, type: 'error', duration: 0, showClose: true });
  }
  console.error('[CustomerDetail/OrdersTab] fetch failed:', e);
}

function onPageChange(p: number) {
  currentPage.value = p;
  fetchList();
}

function goDetail(id: string) {
  router.push(`/sales/orders/${id}`);
}

function goCreate() {
  router.push({ path: '/sales/orders', query: { create: '1', customerId: props.customerId } });
}

onMounted(fetchList);
</script>

<style scoped>
.orders-tab {
  padding: 8px 0;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.toolbar .title {
  font-size: 14px;
  color: var(--el-text-color-secondary);
}
.pagination {
  margin-top: 16px;
  justify-content: flex-end;
  display: flex;
}
.masked {
  color: var(--el-text-color-secondary);
  font-family: monospace;
  letter-spacing: 2px;
  user-select: none;
}
.error-panel {
  padding: 24px 0;
}
</style>
