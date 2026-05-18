<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: tab 13 商品统计 (aggregation, 前端聚合).
  Group SalesOrder items by productTypeId, sum quantity + amount, sort by amount desc.
  RBAC: canViewPrice mask on salesAmount / avgPrice.
-->
<template>
  <div class="item-stats-tab">
    <div class="toolbar">
      <span class="title">商品统计 (基于近 {{ orderCount }} 个销售单)</span>
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
    <el-empty v-else-if="state === 'empty'" description="暂无商品统计数据" :image-size="80" />
    <div v-else-if="state === 'error'" class="error-panel">
      <el-result icon="error" :title="errorMsg">
        <template #extra>
          <el-button type="primary" @click="fetchList">重试</el-button>
        </template>
      </el-result>
    </div>

    <template v-else>
      <el-table :data="rows" border stripe size="small" :default-sort="{ prop: 'salesAmount', order: 'descending' }">
        <el-table-column label="商品" prop="productName" min-width="220" show-overflow-tooltip />
        <el-table-column label="数量" prop="totalQuantity" sortable width="150" align="right">
          <template #default="{ row }">{{ formatNum(row.totalQuantity) }} {{ row.unit || '' }}</template>
        </el-table-column>
        <el-table-column label="销售额" prop="salesAmount" sortable width="160" align="right">
          <template #default="{ row }">
            <span v-if="canViewPrice">{{ formatMoney(row.salesAmount) }}</span>
            <span v-else class="masked">****</span>
          </template>
        </el-table-column>
        <el-table-column label="均价" prop="avgPrice" sortable width="140" align="right">
          <template #default="{ row }">
            <span v-if="canViewPrice">{{ formatMoney(row.avgPrice) }}</span>
            <span v-else class="masked">****</span>
          </template>
        </el-table-column>
        <el-table-column label="最近订单日期" prop="lastDate" width="140" sortable />
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
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';

const SCAN_CAP = 500;

interface OrderItem {
  productTypeId?: string;
  productName?: string;
  quantity?: number;
  unit?: string;
  unitPrice?: number;
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
interface StatsRow {
  productTypeId: string;
  productName: string;
  unit: string;
  totalQuantity: number;
  salesAmount: number;
  avgPrice: number;
  lastDate: string;
}

const props = defineProps<{ customerId: string }>();
const authStore = useAuthStore();
const { factoryId } = storeToRefs(authStore);
const permissionStore = usePermissionStore();
const { canViewPrice } = storeToRefs(permissionStore);

const state = ref<'loading' | 'ready' | 'empty' | 'error'>('loading');
const errorMsg = ref('');
const rows = ref<StatsRow[]>([]);
const orderCount = ref(0);
const hitCap = ref(false);

function formatNum(v?: number): string {
  if (v == null) return '—';
  return Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}
function formatMoney(v?: number): string {
  if (v == null) return '—';
  return Number(v).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY', minimumFractionDigits: 2 });
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

    const groups = new Map<string, StatsRow>();
    for (const o of orders) {
      const items = o.items || [];
      for (const it of items) {
        const key = it.productTypeId || it.productName || '__unknown__';
        const qty = Number(it.quantity || 0);
        const price = Number(it.unitPrice || 0);
        const amount = qty * price;
        if (!groups.has(key)) {
          groups.set(key, {
            productTypeId: it.productTypeId || '',
            productName: it.productName || '—',
            unit: it.unit || '',
            totalQuantity: 0,
            salesAmount: 0,
            avgPrice: 0,
            lastDate: o.orderDate || '',
          });
        }
        const row = groups.get(key)!;
        row.totalQuantity += qty;
        row.salesAmount += amount;
        if (o.orderDate && (!row.lastDate || o.orderDate > row.lastDate)) {
          row.lastDate = o.orderDate;
        }
      }
    }
    // Compute avgPrice
    for (const r of groups.values()) {
      r.avgPrice = r.totalQuantity > 0 ? r.salesAmount / r.totalQuantity : 0;
    }
    rows.value = Array.from(groups.values()).sort((a, b) => b.salesAmount - a.salesAmount);
    state.value = rows.value.length === 0 ? 'empty' : 'ready';
  } catch (e: any) {
    const backendMsg = e?.response?.data?.message || e?.message;
    state.value = 'error';
    errorMsg.value = backendMsg || '加载失败';
    ElMessage({ message: errorMsg.value, type: 'error', duration: 0, showClose: true });
    console.error('[CustomerDetail/ItemStatsTab] aggregation failed:', e);
  }
}

onMounted(fetchList);
</script>

<style scoped>
.item-stats-tab { padding: 8px 0; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar .title { font-size: 14px; color: var(--el-text-color-secondary); }
.masked { color: var(--el-text-color-secondary); font-family: monospace; letter-spacing: 2px; user-select: none; }
.error-panel { padding: 24px 0; }
</style>
