<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: tab 9 报价单 by customer.
  Backend: GET /api/mobile/{factoryId}/quotes/by-customer?customerId= (Phase D3 added).
  RBAC: canViewPrice mask on quotedPrice / quoteTotalAmount per spec §7.2.
-->
<template>
  <div class="quotes-tab">
    <div class="toolbar">
      <span class="title">报价单</span>
      <el-button :icon="Refresh" @click="fetchList" :loading="state === 'loading'">刷新</el-button>
    </div>

    <el-skeleton v-if="state === 'loading'" :rows="5" animated />

    <el-empty v-else-if="state === 'empty'" description="该客户暂无报价单" :image-size="80">
      <el-button type="primary" @click="goCreate">创建报价单</el-button>
    </el-empty>

    <div v-else-if="state === 'error'" class="error-panel">
      <el-result icon="error" :title="errorMsg">
        <template #extra>
          <el-button type="primary" @click="fetchList">重试</el-button>
        </template>
      </el-result>
    </div>

    <template v-else>
      <el-table :data="quotes" border stripe size="small">
        <el-table-column label="报价单号" prop="quoteNumber" width="170" />
        <el-table-column label="创建日期" prop="createdAt" width="170">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" prop="status" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="产品类型" prop="productTypeName" min-width="140" show-overflow-tooltip />
        <el-table-column label="报价" align="right" width="140">
          <template #default="{ row }">
            <span v-if="canViewPrice">{{ formatMoney(row.quotedPrice) }}</span>
            <span v-else class="masked">****</span>
          </template>
        </el-table-column>
        <el-table-column label="总额" align="right" width="140">
          <template #default="{ row }">
            <span v-if="canViewPrice">{{ formatMoney(row.quoteTotalAmount) }}</span>
            <span v-else class="masked">****</span>
          </template>
        </el-table-column>
        <el-table-column label="有效期" prop="validUntil" width="120" />
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

interface QuoteRow {
  id: string;
  quoteNumber?: string;
  customerId: string;
  productTypeName?: string;
  status?: string;
  quotedPrice?: number;
  quoteTotalAmount?: number;
  validUntil?: string;
  createdAt?: string;
}

interface PageResp {
  content: QuoteRow[];
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
const quotes = ref<QuoteRow[]>([]);
const totalElements = ref(0);
const currentPage = ref(1);
const pageSize = 20;

function formatMoney(v: number | null | undefined): string {
  if (v == null) return '—';
  return Number(v).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY', minimumFractionDigits: 2 });
}

function formatDate(s?: string): string {
  if (!s) return '—';
  return new Date(s).toLocaleString('zh-CN', { hour12: false });
}

function statusTagType(status?: string): 'success' | 'warning' | 'info' | 'danger' {
  if (!status) return 'info';
  if (status === 'APPROVED') return 'success';
  if (status === 'PENDING') return 'warning';
  if (status === 'REJECTED' || status === 'CANCELLED') return 'danger';
  return 'info';
}

async function fetchList() {
  if (!factoryId.value) return;
  state.value = 'loading';
  try {
    const res = await get<PageResp>(`/${factoryId.value}/quotes/by-customer`, {
      params: { customerId: props.customerId, page: currentPage.value, size: pageSize },
    });
    if (!res.success || !res.data) throw new Error(res.message || '加载失败');
    quotes.value = res.data.content;
    totalElements.value = res.data.totalElements;
    state.value = quotes.value.length === 0 ? 'empty' : 'ready';
  } catch (e: any) {
    handleError(e);
  }
}

function handleError(e: any) {
  const status = e?.response?.status || e?.code;
  const backendMsg = e?.response?.data?.message || e?.message;
  if (status === 403) {
    state.value = 'error';
    errorMsg.value = '无权查看此客户的报价单';
  } else {
    state.value = 'error';
    errorMsg.value = backendMsg || '加载失败';
    ElMessage({ message: errorMsg.value, type: 'error', duration: 0, showClose: true });
  }
  console.error('[CustomerDetail/QuotesTab] fetch failed:', e);
}

function onPageChange(p: number) {
  currentPage.value = p;
  fetchList();
}

function goCreate() {
  router.push({ path: '/sales/quotes', query: { create: '1', customerId: props.customerId } });
}

onMounted(fetchList);
</script>

<style scoped>
.quotes-tab { padding: 8px 0; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar .title { font-size: 14px; color: var(--el-text-color-secondary); }
.pagination { margin-top: 16px; justify-content: flex-end; display: flex; }
.masked { color: var(--el-text-color-secondary); font-family: monospace; letter-spacing: 2px; user-select: none; }
.error-panel { padding: 24px 0; }
</style>
