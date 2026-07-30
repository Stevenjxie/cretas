<script setup lang="ts">
/**
 * Dashboard 主页面
 * 根据用户角色动态加载对应的 Dashboard 组件
 *
 * 角色映射:
 * - factory_super_admin → DashboardAdmin (全功能)
 * - hr_admin → DashboardHR (人事管理)
 * - dispatcher, production_manager, workshop_supervisor → DashboardProduction (生产管理)
 * - warehouse_manager → DashboardWarehouse (仓储管理)
 * - finance_manager → DashboardFinance (财务管理)
 * - 其他角色 → DashboardDefault (通用版)
 */
import { computed, defineAsyncComponent, defineComponent, h } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { getDashboardComponent } from '@/components/dashboard';
import PendingApprovalsWidget from '@/components/dashboard/PendingApprovalsWidget.vue';
import PendingTransferConfirmWidget from '@/components/dashboard/PendingTransferConfirmWidget.vue';

const authStore = useAuthStore();

// 获取当前角色对应的 Dashboard 组件名称（含工厂类型覆盖）
const dashboardComponentName = computed(() => {
  return getDashboardComponent(authStore.currentRole, authStore.factoryType);
});

const asyncOpts = (loader: () => Promise<{ default: ReturnType<typeof defineComponent> }>) => defineAsyncComponent({
  loader,
  loadingComponent: { render: () => h('div', { style: 'text-align:center;padding:60px;color:#7A8599' }, '加载中...') },
  errorComponent: { render: () => h('div', { style: 'text-align:center;padding:60px;color:#FF5630' }, '加载失败，请刷新页面') },
  delay: 200,
  timeout: 15000,
});

// 动态组件映射
const dashboardComponents = {
  DashboardAdmin: asyncOpts(() => import('@/components/dashboard/DashboardAdmin.vue')),
  DashboardHR: asyncOpts(() => import('@/components/dashboard/DashboardHR.vue')),
  DashboardProduction: asyncOpts(() => import('@/components/dashboard/DashboardProduction.vue')),
  DashboardWarehouse: asyncOpts(() => import('@/components/dashboard/DashboardWarehouse.vue')),
  DashboardFinance: asyncOpts(() => import('@/components/dashboard/DashboardFinance.vue')),
  DashboardDefault: asyncOpts(() => import('@/components/dashboard/DashboardDefault.vue')),
  DashboardRestaurant: asyncOpts(() => import('@/components/dashboard/DashboardRestaurant.vue')),
};

// 获取当前角色对应的组件
const CurrentDashboard = computed(() => {
  const componentName = dashboardComponentName.value as keyof typeof dashboardComponents;
  return dashboardComponents[componentName] || dashboardComponents.DashboardDefault;
});
</script>

<template>
  <div class="dashboard-container">
    <!-- issue #20: 我待审 widget — Phase 1 final closure for ADR-001 AC-3.
         自动隐藏 (v-if total=0), 对无待审用户无干扰. -->
    <PendingApprovalsWidget />
    <!-- 客户 2026-07-30「审核后 库存没有过来」: 同厂调拨审批通过后还差一次「确认入库」库存才动,
         客户不知道这一步, 单子一卡就是几周。首页主动摆出来 (仅仓储角色/超管可见, 无待办自动隐藏)。 -->
    <PendingTransferConfirmWidget />
    <component :is="CurrentDashboard" />
  </div>
</template>

<style lang="scss" scoped>
.dashboard-container {
  min-height: calc(100vh - 144px);
}
</style>
