<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { useAppStore } from '@/store/modules/app';
import AppSidebar from './AppSidebar.vue';
import AppHeader from './AppHeader.vue';
import InlineCustomerService from '@/components/support/InlineCustomerService.vue';
import ReturnBanner from './ReturnBanner.vue';

const appStore = useAppStore();

// Sprint 4 W1 C-INLINE-CS-1: 在线客服入口 URL. 后续接入实际客服系统时改 env / runtime config.
const customerServiceUrl = (import.meta.env.VITE_CUSTOMER_SERVICE_URL as string | undefined) ?? '';

// Keep SmartBIAnalysis in cache to avoid heavy unmount side effects
const keepAliveViews = ['SmartBIAnalysis'];

onMounted(() => appStore.initResponsive());

const mainStyle = computed(() => ({
  marginLeft: appStore.isMobile ? '0px' : `${appStore.currentSidebarWidth}px`,
  transition: 'margin-left 0.3s'
}));
</script>

<template>
  <div class="app-layout">
    <!-- 侧边栏 -->
    <AppSidebar />

    <!-- 主内容区 -->
    <div class="app-main" :style="mainStyle">
      <!-- 顶部栏 -->
      <AppHeader />

      <!-- FK_BLOCK 防呆导航浮条: 从关联页跳转处理完毕后引导返回 -->
      <ReturnBanner />

      <!-- 内容区 -->
      <main class="app-content">
        <router-view v-slot="{ Component }">
          <keep-alive :include="keepAliveViews" :max="5">
            <component :is="Component" :key="$route.name" />
          </keep-alive>
        </router-view>
      </main>
    </div>

    <!-- Sprint 4 W1 C-INLINE-CS-1: 在线客服浮动入口 (固定右下角) -->
    <InlineCustomerService :service-url="customerServiceUrl" />
  </div>
</template>

<style lang="scss" scoped>
.app-layout {
  min-height: 100vh;
  width: 100%;
  background-color: var(--bg-color-page, #F4F6F9);
}

.app-main {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.app-content {
  flex: 1;
  padding: var(--page-padding, 20px);
  overflow-y: auto;
  background-color: var(--bg-color-page, #F4F6F9);

  // 确保内容区域正确展示
  > * {
    min-height: 100%;
    width: 100%;
  }
}

@media (max-width: 768px) {
  .app-main {
    margin-left: 0 !important;
  }
  .app-content {
    padding: var(--page-padding, 12px);
  }
}
</style>
