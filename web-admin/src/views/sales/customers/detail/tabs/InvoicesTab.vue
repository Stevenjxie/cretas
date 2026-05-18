<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: tab 15 开票 by customer.
  Backend: GET /api/mobile/{factoryId}/finance/invoices/by-customer?customerId= (Phase C C1).
  RBAC: canViewPrice mask on amount / taxAmount / totalAmount per spec §7.2.
-->
<template>
  <div class="invoices-tab">
    <div class="toolbar">
      <span class="title">开票记录</span>
      <el-button :icon="Refresh" @click="fetchList" :loading="state === 'loading'">刷新</el-button>
    </div>

    <el-skeleton v-if="state === 'loading'" :rows="5" animated />
    <el-empty v-else-if="state === 'empty'" description="该客户暂无开票记录" :image-size="80" />
    <div v-else-if="state === 'error'" class="error-panel">
      <el-result icon="error" :title="errorMsg">
        <template #extra>
          <el-button type="primary" @click="fetchList">重试</el-button>
        </template>
      </el-result>
    </div>

    <template v-else>
      <el-table :data="invoices" border stripe size="small">
        <el-table-column label="发票号" prop="invoiceNumber" width="180" />
        <el-table-column label="类型" prop="invoiceType" width="110" />
        <el-table-column label="状态" prop="status" width="110">
          <template #default="{ row }">
            <el-tag size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="不含税" align="right" width="130">
          <template #default="{ row }">
            <span v-if="canViewPrice">{{ formatMoney(row.amount) }}</span>
            <span v-else class="masked">****</span>
          </template>
        </el-table-column>
        <el-table-column label="税额" align="right" width="120">
          <template #default="{ row }">
            <span v-if="canViewPrice">{{ formatMoney(row.taxAmount) }}</span>
            <span v-else class="masked">****</span>
          </template>
        </el-table-column>
        <el-table-column label="价税合计" align="right" width="140">
          <template #default="{ row }">
            <span v-if="canViewPrice">{{ formatMoney(row.totalAmount) }}</span>
            <span v-else class="masked">****</span>
          </template>
        </el-table-column>
        <el-table-column label="开票日期" prop="invoiceDate" width="120" />
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
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { storeToRefs } from 'pinia';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';

interface InvoiceRow {
  id: string;
  invoiceNumber?: string;
  invoiceType?: string;
  status?: string;
  amount?: number;
  taxAmount?: number;
  totalAmount?: number;
  invoiceDate?: string;
}

interface PageResp {
  content: InvoiceRow[];
  totalElements: number;
  totalPages: number;
}

const props = defineProps<{ customerId: string }>();

const authStore = useAuthStore();
const { factoryId } = storeToRefs(authStore);
const permissionStore = usePermissionStore();
const { canViewPrice } = storeToRefs(permissionStore);

const state = ref<'loading' | 'ready' | 'empty' | 'error'>('loading');
const errorMsg = ref('');
const invoices = ref<InvoiceRow[]>([]);
const totalElements = ref(0);
const currentPage = ref(1);
const pageSize = 20;

function formatMoney(v?: number | null): string {
  if (v == null) return '—';
  return Number(v).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY', minimumFractionDigits: 2 });
}

async function fetchList() {
  if (!factoryId.value) return;
  state.value = 'loading';
  try {
    const res = await get<PageResp>(`/${factoryId.value}/finance/invoices/by-customer`, {
      params: { customerId: props.customerId, page: currentPage.value, size: pageSize },
    });
    if (!res.success || !res.data) throw new Error(res.message || '加载失败');
    invoices.value = res.data.content;
    totalElements.value = res.data.totalElements;
    state.value = invoices.value.length === 0 ? 'empty' : 'ready';
  } catch (e: any) {
    const status = e?.response?.status || e?.code;
    const backendMsg = e?.response?.data?.message || e?.message;
    if (status === 403) {
      state.value = 'error';
      errorMsg.value = '无权查看此客户的开票记录';
    } else {
      state.value = 'error';
      errorMsg.value = backendMsg || '加载失败';
      ElMessage({ message: errorMsg.value, type: 'error', duration: 0, showClose: true });
    }
    console.error('[CustomerDetail/InvoicesTab] fetch failed:', e);
  }
}

function onPageChange(p: number) {
  currentPage.value = p;
  fetchList();
}

onMounted(fetchList);
</script>

<style scoped>
.invoices-tab { padding: 8px 0; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar .title { font-size: 14px; color: var(--el-text-color-secondary); }
.pagination { margin-top: 16px; justify-content: flex-end; display: flex; }
.masked { color: var(--el-text-color-secondary); font-family: monospace; letter-spacing: 2px; user-select: none; }
.error-panel { padding: 24px 0; }
</style>
