<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: tab 19 价格记忆.

  Chat B S-PRICE-1 (CustomerPriceMemory entity + controller) 尚未 ship.
  本组件做 fallback placeholder; Chat B ship 后:
    1) 添加 web-admin/src/api/customerPriceMemory.ts client
    2) 把本组件改为 list 表格 (类似 OrdersTab) + canViewPrice mask
    3) Phase D8 follow-up 可单独 5-LOC PR

  防呆 R5: next-action → 报价单 tab (替代方案).
-->
<template>
  <div class="price-memory-tab">
    <el-empty :image-size="120">
      <template #description>
        <p class="title">「价格记忆」功能即将上线</p>
        <p class="hint">
          客户级价格记忆需 Chat B (S-PRICE-1 工单) backend ship 后开放.
          当前请查「报价单」tab 看该客户的历史报价.
        </p>
      </template>
      <el-button type="primary" @click="goQuotes">去查看报价单</el-button>
    </el-empty>
  </div>
</template>

<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router';

defineProps<{
  customerId: string;
}>();

const route = useRoute();
const router = useRouter();

function goQuotes() {
  router.replace({
    name: 'SalesCustomerDetail',
    params: { id: route.params.id },
    query: { ...route.query, tab: 'quotes' },
  });
}
</script>

<style scoped>
.price-memory-tab {
  padding: 48px 16px;
}
.title {
  font-size: 16px;
  margin-bottom: 8px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin: 4px 0;
  max-width: 480px;
}
</style>
