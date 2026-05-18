<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: tab 17 退货 by customer.
  Backend: GET /api/mobile/{factoryId}/return-orders/by-customer?customerId= (Phase C C1).

  Spec drift #7: ReturnOrder 没有直接 customerId 字段, backend 用 counterpartyId +
  returnType=SALES_RETURN 组合过滤. 前端调用 endpoint 时传 customerId, backend 内部映射.

  RBAC: canViewPrice mask on totalAmount.
-->
<template>
  <div class="returns-tab">
    <div class="toolbar">
      <span class="title">销售退货</span>
      <el-button :icon="Refresh" @click="fetchList" :loading="state === 'loading'">刷新</el-button>
    </div>

    <el-skeleton v-if="state === 'loading'" :rows="5" animated />
    <el-empty v-else-if="state === 'empty'" description="该客户暂无销售退货记录" :image-size="80" />
    <div v-else-if="state === 'error'" class="error-panel">
      <el-result icon="error" :title="errorMsg">
        <template #extra>
          <el-button type="primary" @click="fetchList">重试</el-button>
        </template>
      </el-result>
    </div>

    <template v-else>
      <el-table :data="returns" border stripe size="small">
        <el-table-column label="退货单号" prop="returnNumber" width="170" />
        <el-table-column label="退货日期" prop="returnDate" width="120" />
        <el-table-column label="状态" prop="status" width="110">
          <template #default="{ row }">
            <el-tag size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="金额" align="right" width="140">
          <template #default="{ row }">
            <span v-if="canViewPrice">{{ formatMoney(row.totalAmount) }}</span>
            <span v-else class="masked">****</span>
          </template>
        </el-table-column>
        <el-table-column label="原因" prop="reason" min-width="160" show-overflow-tooltip />
        <el-table-column label="备注" prop="remark" min-width="140" show-overflow-tooltip />
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

interface ReturnRow {
  id: string;
  returnNumber?: string;
  returnDate?: string;
  status?: string;
  totalAmount?: number;
  reason?: string;
  remark?: string;
}

interface PageResp {
  content: ReturnRow[];
  totalElements: number;
}

const props = defineProps<{ customerId: string }>();

const authStore = useAuthStore();
const { factoryId } = storeToRefs(authStore);
const permissionStore = usePermissionStore();
const { canViewPrice } = storeToRefs(permissionStore);

const state = ref<'loading' | 'ready' | 'empty' | 'error'>('loading');
const errorMsg = ref('');
const returns = ref<ReturnRow[]>([]);
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
    const res = await get<PageResp>(`/${factoryId.value}/return-orders/by-customer`, {
      params: { customerId: props.customerId, page: currentPage.value, size: pageSize },
    });
    if (!res.success || !res.data) throw new Error(res.message || '加载失败');
    returns.value = res.data.content;
    totalElements.value = res.data.totalElements;
    state.value = returns.value.length === 0 ? 'empty' : 'ready';
  } catch (e: any) {
    const status = e?.response?.status || e?.code;
    const backendMsg = e?.response?.data?.message || e?.message;
    if (status === 403) {
      state.value = 'error';
      errorMsg.value = '无权查看此客户的退货记录';
    } else {
      state.value = 'error';
      errorMsg.value = backendMsg || '加载失败';
      ElMessage({ message: errorMsg.value, type: 'error', duration: 0, showClose: true });
    }
    console.error('[CustomerDetail/ReturnsTab] fetch failed:', e);
  }
}

function onPageChange(p: number) {
  currentPage.value = p;
  fetchList();
}

onMounted(fetchList);
</script>

<style scoped>
.returns-tab { padding: 8px 0; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar .title { font-size: 14px; color: var(--el-text-color-secondary); }
.pagination { margin-top: 16px; justify-content: flex-end; display: flex; }
.masked { color: var(--el-text-color-secondary); font-family: monospace; letter-spacing: 2px; user-select: none; }
.error-panel { padding: 24px 0; }
</style>
