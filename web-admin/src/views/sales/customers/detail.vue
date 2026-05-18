<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: 客户档案 360° detail view.

  21 tabs:
    - 12 真做 (lazy via defineAsyncComponent, wired in Phase D)
    - 1 integration: priceMemory (Chat B S-PRICE-1, internal fallback to PlaceholderTab)
    - 8 defer placeholder (R5 next-action button per fool-proof-design.md)

  Route: /sales/customers/:id?tab=N (flat, browser back to list, URL bookmarkable)
  Lazy load: defineAsyncComponent + KeepAlive (切回不重 fetch; per-tab refresh button manual)
  RBAC mask: canViewPrice (permission store) — applied per含价格 tab
  防呆: R2 (CustomerHeader sticky context) + R5 (8 defer + 1 integration next-action)
-->
<template>
  <div class="customer-detail">
    <CustomerHeader
      :customer="customer"
      :loading="customerLoading"
      :assigned-sales-user-name="assignedSalesUserName"
    />

    <el-tabs
      v-model="activeTab"
      class="business-tabs"
      @tab-change="onTabChange"
    >
      <el-tab-pane
        v-for="t in TAB_DEFS"
        :key="t.key"
        :name="t.key"
        :label="t.label"
      >
        <KeepAlive>
          <component
            v-if="t.component"
            :is="t.component"
            :customer-id="customerId"
            :customer="customer"
            @changed="refreshCustomer"
          />
          <PlaceholderTab
            v-else
            :tab-name="t.label"
            :status="t.status || '功能开发中'"
            :workaround-hint="t.workaround"
            :action-text="t.actionText"
            :action-tab-key="t.actionTabKey"
          />
        </KeepAlive>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch, defineAsyncComponent, type Component } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { storeToRefs } from 'pinia';
import { getCustomer, type Customer } from '@/api/customer';
import CustomerHeader from './detail/CustomerHeader.vue';
import PlaceholderTab from './detail/tabs/PlaceholderTab.vue';

interface TabDef {
  key: string;
  label: string;
  component?: Component;
  // R5 fields (defer/integration tabs)
  status?: string;
  workaround?: string;
  actionText?: string;
  actionTabKey?: string;
}

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const { factoryId } = storeToRefs(authStore);

const customerId = computed(() => route.params.id as string);
const activeTab = ref<string>((route.query.tab as string) || 'tracking');

const customer = ref<Customer | null>(null);
const customerLoading = ref(false);
// 业务员姓名占位 — Phase D9 wire to user lookup or backend join
const assignedSalesUserName = ref<string>('');

// 21 tab matrix (spec §3) — Phase B wires 12 real tab components as null (lazy in Phase D).
// 8 defer + 1 integration (priceMemory) have R5 next-action to tracking/quotes/returns.
const TAB_DEFS: TabDef[] = [
  // 1 — real
  { key: 'tracking', label: '跟踪记录',
    component: defineAsyncComponent(() => import('./detail/tabs/TrackingTab.vue')) },
  // 2-6 — defer (R5 → tracking)
  { key: 'wechat', label: '微信记录',
    status: '暂未对接企微 API', workaround: '当前请用「跟踪记录」tab 手工补录',
    actionText: '去跟踪记录', actionTabKey: 'tracking' },
  { key: 'call', label: '通话记录',
    status: '暂未对接呼叫中心', workaround: '当前请用「跟踪记录」tab 手工补录',
    actionText: '去跟踪记录', actionTabKey: 'tracking' },
  { key: 'sms', label: '短信记录',
    status: 'Sprint 5+ 上线', workaround: '当前请用「跟踪记录」tab 手工补录',
    actionText: '去跟踪记录', actionTabKey: 'tracking' },
  { key: 'audio', label: '谈话录音',
    status: 'Sprint 6+ 上线', workaround: '当前请用「跟踪记录」tab 手工补录',
    actionText: '去跟踪记录', actionTabKey: 'tracking' },
  { key: 'email', label: '邮件列表',
    status: 'Sprint 5+ 上线', workaround: '当前请用「跟踪记录」tab 手工补录',
    actionText: '去跟踪记录', actionTabKey: 'tracking' },
  // 7-10 — real
  { key: 'orders', label: '销售单',
    component: defineAsyncComponent(() => import('./detail/tabs/OrdersTab.vue')) },
  { key: 'samples', label: '样品单',
    component: defineAsyncComponent(() => import('./detail/tabs/SamplesTab.vue')) },
  { key: 'quotes', label: '报价单',
    component: defineAsyncComponent(() => import('./detail/tabs/QuotesTab.vue')) },
  { key: 'products', label: '产品',
    component: defineAsyncComponent(() => import('./detail/tabs/ProductsTab.vue')) },
  // 11-12 — defer (R5 → tracking, CRM modules pending)
  { key: 'campaign', label: '活动管理',
    status: 'Sprint 5+ (CRM 模块) 上线', workaround: '当前请在「跟踪记录」记录活动',
    actionText: '去跟踪记录', actionTabKey: 'tracking' },
  { key: 'opportunity', label: '商机管理',
    status: 'Sprint 5+ 上线', workaround: '当前请在「跟踪记录」记录商机',
    actionText: '去跟踪记录', actionTabKey: 'tracking' },
  // 13-14 — real
  { key: 'itemStats', label: '商品统计',
    component: defineAsyncComponent(() => import('./detail/tabs/ItemStatsTab.vue')) },
  { key: 'shipAddr', label: '收件地址',
    component: defineAsyncComponent(() => import('./detail/tabs/ShippingAddressTab.vue')) },
  // 15-17 — real
  { key: 'invoices', label: '开票',
    component: defineAsyncComponent(() => import('./detail/tabs/InvoicesTab.vue')) },
  { key: 'payments', label: '收款',
    component: defineAsyncComponent(() => import('./detail/tabs/PaymentsTab.vue')) },
  { key: 'returns', label: '退货',
    component: defineAsyncComponent(() => import('./detail/tabs/ReturnsTab.vue')) },
  // 18 — defer (R5 → returns)
  { key: 'aftersales', label: '售后',
    status: 'Sprint 6+ 上线', workaround: '当前请用「退货」tab 处理售后退货',
    actionText: '去退货', actionTabKey: 'returns' },
  // 19 — integration fallback (Chat B S-PRICE-1 pending; internal placeholder)
  { key: 'priceMemory', label: '价格记忆',
    component: defineAsyncComponent(() => import('./detail/tabs/PriceMemoryTab.vue')) },
  // 20-21 — real (Phase D7, D5)
  { key: 'salesUserHist', label: '业务员变更',
    component: defineAsyncComponent(() => import('./detail/tabs/SalesUserHistoryTab.vue')) },
  { key: 'attachments', label: '文件附件',
    component: defineAsyncComponent(() => import('./detail/tabs/AttachmentsTab.vue')) },
];

function onTabChange(key: string | number) {
  router.replace({
    query: { ...route.query, tab: String(key) },
  });
}

// React to URL query change (back/forward button)
watch(
  () => route.query.tab,
  (newKey) => {
    if (newKey && typeof newKey === 'string' && newKey !== activeTab.value) {
      activeTab.value = newKey;
    }
  },
);

async function refreshCustomer() {
  if (!factoryId.value || !customerId.value) return;
  try {
    customer.value = await getCustomer(factoryId.value, customerId.value);
  } catch (e) {
    console.warn('[CustomerDetail] refresh failed:', e);
  }
}

onMounted(async () => {
  if (!customerId.value) {
    ElMessage.error('客户 ID 缺失');
    router.replace('/sales/customers');
    return;
  }
  if (!factoryId.value) {
    ElMessage.error('未登录或工厂信息缺失');
    return;
  }
  customerLoading.value = true;
  try {
    customer.value = await getCustomer(factoryId.value, customerId.value);
    // assignedSalesUserName resolved in Phase D9 via /users/{id} lookup or backend join
  } catch (e: any) {
    const status = e?.code || e?.response?.status;
    const msg = e?.message || e?.response?.data?.message || '加载失败';
    if (status === 404) {
      ElMessage.error('客户不存在');
      router.replace('/sales/customers');
    } else if (status === 403) {
      ElMessage({
        message: '无权访问此客户',
        type: 'error',
        duration: 0,
        showClose: true,
      });
      router.replace('/sales/customers');
    } else {
      // 4 位一体: backend message + sticky
      ElMessage({
        message: msg,
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
    console.error('[CustomerDetail] getCustomer failed:', e);
  } finally {
    customerLoading.value = false;
  }
});
</script>

<style scoped>
.customer-detail {
  padding: 16px;
}
.business-tabs {
  background: var(--el-bg-color);
  padding: 16px;
  border-radius: 4px;
}
</style>
