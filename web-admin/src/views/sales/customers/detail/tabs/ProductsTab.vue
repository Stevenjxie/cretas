<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: tab 10 产品 (aggregation, 前端聚合).
  Fetch orders + group items by productTypeId, count distinct, list specs.
  Capped at 500 orders to avoid memory bloat.
-->
<template>
  <div class="products-tab">
    <div class="toolbar">
      <span class="title">产品 (基于近 {{ orderCount }} 个销售单聚合)</span>
      <el-button :icon="Refresh" @click="fetchList" :loading="state === 'loading'">刷新</el-button>
    </div>

    <el-alert
      v-if="hitCap"
      type="warning"
      :title="`聚合基于近 ${SCAN_CAP} 单 — 全量请用导出/报表系统`"
      show-icon
      :closable="false"
      style="margin-bottom: 12px"
    />

    <el-skeleton v-if="state === 'loading'" :rows="5" animated />
    <el-empty v-else-if="state === 'empty'" description="该客户暂无购买产品记录" :image-size="80" />
    <div v-else-if="state === 'error'" class="error-panel">
      <el-result icon="error" :title="errorMsg">
        <template #extra>
          <el-button type="primary" @click="fetchList">重试</el-button>
        </template>
      </el-result>
    </div>

    <template v-else>
      <el-table :data="rows" border stripe size="small" :default-sort="{ prop: 'orderCount', order: 'descending' }">
        <el-table-column label="产品名" prop="productName" min-width="220" show-overflow-tooltip />
        <el-table-column label="购买订单数" prop="orderCount" sortable width="140" align="right" />
        <el-table-column label="累计数量" prop="totalQuantity" sortable width="160" align="right">
          <template #default="{ row }">{{ formatNum(row.totalQuantity) }} {{ row.unit || '' }}</template>
        </el-table-column>
        <el-table-column label="首次购买" prop="firstDate" width="120" sortable />
        <el-table-column label="最近购买" prop="lastDate" width="120" sortable />
      </el-table>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { storeToRefs } from 'pinia';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';

const SCAN_CAP = 500;

interface OrderItem {
  productTypeId?: string;
  productName?: string;
  quantity?: number;
  unit?: string;
}
interface Order {
  id: string;
  orderDate?: string;
  items?: OrderItem[];
}
interface PageResp {
  content: Order[];
  totalElements: number;
}
interface ProductRow {
  productTypeId: string;
  productName: string;
  unit: string;
  orderCount: number;
  totalQuantity: number;
  firstDate: string;
  lastDate: string;
}

const props = defineProps<{ customerId: string }>();
const authStore = useAuthStore();
const { factoryId } = storeToRefs(authStore);

const state = ref<'loading' | 'ready' | 'empty' | 'error'>('loading');
const errorMsg = ref('');
const rows = ref<ProductRow[]>([]);
const orderCount = ref(0);
const hitCap = ref(false);

function formatNum(v?: number): string {
  if (v == null) return '—';
  return Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

async function fetchList() {
  if (!factoryId.value) return;
  state.value = 'loading';
  try {
    const res = await get<PageResp>(`/${factoryId.value}/sales/orders/by-customer`, {
      params: { customerId: props.customerId, page: 1, size: SCAN_CAP },
    });
    if (!res.success || !res.data) throw new Error(res.message || '加载失败');
    const orders = res.data.content;
    orderCount.value = orders.length;
    hitCap.value = res.data.totalElements > SCAN_CAP;

    // Group items by productTypeId (or productName if id missing)
    const groups = new Map<string, ProductRow>();
    for (const o of orders) {
      const items = o.items || [];
      const seenInThisOrder = new Set<string>();
      for (const it of items) {
        const key = it.productTypeId || it.productName || '__unknown__';
        if (!groups.has(key)) {
          groups.set(key, {
            productTypeId: it.productTypeId || '',
            productName: it.productName || '—',
            unit: it.unit || '',
            orderCount: 0,
            totalQuantity: 0,
            firstDate: o.orderDate || '',
            lastDate: o.orderDate || '',
          });
        }
        const row = groups.get(key)!;
        row.totalQuantity += Number(it.quantity || 0);
        if (!seenInThisOrder.has(key)) {
          row.orderCount += 1;
          seenInThisOrder.add(key);
        }
        if (o.orderDate) {
          if (!row.firstDate || o.orderDate < row.firstDate) row.firstDate = o.orderDate;
          if (!row.lastDate || o.orderDate > row.lastDate) row.lastDate = o.orderDate;
        }
      }
    }
    rows.value = Array.from(groups.values()).sort((a, b) => b.orderCount - a.orderCount);
    state.value = rows.value.length === 0 ? 'empty' : 'ready';
  } catch (e: any) {
    const backendMsg = e?.response?.data?.message || e?.message;
    state.value = 'error';
    errorMsg.value = backendMsg || '加载失败';
    ElMessage({ message: errorMsg.value, type: 'error', duration: 0, showClose: true });
    console.error('[CustomerDetail/ProductsTab] aggregation failed:', e);
  }
}

onMounted(fetchList);
</script>

<style scoped>
.products-tab { padding: 8px 0; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar .title { font-size: 14px; color: var(--el-text-color-secondary); }
.error-panel { padding: 24px 0; }
</style>
