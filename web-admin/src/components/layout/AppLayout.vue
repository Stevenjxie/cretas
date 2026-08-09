<script setup lang="ts">
import { computed, onMounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useAppStore } from '@/store/modules/app';
import { useAuthStore } from '@/store/modules/auth';
import { useWorkspaceStore, workspaceRouteKey } from '@/store/modules/workspace';
import AppSidebar from './AppSidebar.vue';
import AppHeader from './AppHeader.vue';
import WorkspaceTabs from './WorkspaceTabs.vue';
import WorkspaceReferencePane from './WorkspaceReferencePane.vue';
import InlineCustomerService from '@/components/support/InlineCustomerService.vue';
import ReturnBanner from './ReturnBanner.vue';

const appStore = useAppStore();
const authStore = useAuthStore();
const workspace = useWorkspaceStore();
const route = useRoute();

// Sprint 4 W1 C-INLINE-CS-1: 在线客服入口 URL. 后续接入实际客服系统时改 env / runtime config.
const customerServiceUrl = (import.meta.env.VITE_CUSTOMER_SERVICE_URL as string | undefined) ?? '';

onMounted(() => appStore.initResponsive());

const isReferenceFrame = computed(() => String(route.query._workspaceReference || '') === '1');

watch(
  () => authStore.factoryId,
  (factoryId) => workspace.setScope(factoryId || authStore.currentRole || 'authenticated'),
  { immediate: true },
);

watch(
  () => route.fullPath,
  () => workspace.openRoute({
    fullPath: route.fullPath,
    path: route.path,
    name: route.name,
    title: String(route.meta.title || ''),
    query: route.query,
  }),
  { immediate: true },
);

const mainStyle = computed(() => ({
  marginLeft: isReferenceFrame.value || appStore.isMobile ? '0px' : `${appStore.currentSidebarWidth}px`,
  transition: 'margin-left 0.3s'
}));

function pinDraggedReference(event: DragEvent): void {
  event.preventDefault();
  workspace.pinReference(event.dataTransfer?.getData('text/plain') || workspace.draggingKey);
}
</script>

<template>
  <div class="app-layout">
    <!-- 侧边栏 -->
    <AppSidebar v-if="!isReferenceFrame" />

    <!-- 主内容区 -->
    <div class="app-main" :style="mainStyle">
      <!-- 顶部栏 -->
      <AppHeader v-if="!isReferenceFrame" />

      <WorkspaceTabs v-if="!isReferenceFrame" />

      <!-- FK_BLOCK 防呆导航浮条: 从关联页跳转处理完毕后引导返回 -->
      <ReturnBanner v-if="!isReferenceFrame" />

      <!-- 内容区 -->
      <div class="workspace-content-shell" :class="{ 'has-reference': workspace.referenceTab && !isReferenceFrame }">
        <main class="app-content" :class="{ 'reference-frame-content': isReferenceFrame }">
          <router-view v-slot="{ Component }">
            <keep-alive :max="10">
              <component :is="Component" :key="workspaceRouteKey({ path: route.path, query: route.query })" />
            </keep-alive>
          </router-view>
        </main>
        <WorkspaceReferencePane
          v-if="workspace.referenceTab && !isReferenceFrame"
          :tab="workspace.referenceTab"
          @close="workspace.closeReference"
        />
        <div
          v-if="workspace.draggingKey && !isReferenceFrame"
          class="reference-drop-zone"
          @dragover.prevent
          @drop="pinDraggedReference"
        >
          <div><strong>拖到这里，同屏参考</strong><span>松开后右侧只读展示，左侧任务保持不变</span></div>
        </div>
      </div>
    </div>

    <!-- Sprint 4 W1 C-INLINE-CS-1: 在线客服浮动入口 (固定右下角) -->
    <InlineCustomerService v-if="!isReferenceFrame" :service-url="customerServiceUrl" />
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

.workspace-content-shell {
  flex: 1;
  min-height: 0;
  display: flex;
  position: relative;
}

.app-content {
  flex: 1;
  padding: var(--page-padding, 20px);
  overflow-y: auto;
  background-color: var(--bg-color-page, #F4F6F9);
  min-width: 0;

  // 确保内容区域正确展示
  > * {
    min-height: 100%;
    width: 100%;
  }
}

.reference-frame-content { padding: 12px; min-height: 100vh; }

.reference-drop-zone {
  position: absolute;
  z-index: 30;
  inset: 12px 12px 12px auto;
  width: min(38%, 520px);
  border: 2px dashed #1b65a8;
  border-radius: 12px;
  background: rgba(238, 247, 255, 0.96);
  display: grid;
  place-items: center;
  color: #1b65a8;
  box-shadow: 0 12px 36px rgba(27, 101, 168, 0.16);
}

.reference-drop-zone div { display: flex; flex-direction: column; align-items: center; gap: 8px; text-align: center; }
.reference-drop-zone strong { font-size: 18px; }
.reference-drop-zone span { color: #63758a; }

@media (max-width: 768px) {
  .app-main {
    margin-left: 0 !important;
  }
  .app-content {
    padding: var(--page-padding, 12px);
  }
}
</style>
