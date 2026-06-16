<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useAppStore } from '@/store/modules/app';
import { usePermissionStore } from '@/store/modules/permission';
import { resolveModuleRegistryItemByRoute } from '@/config/moduleRegistry';
import AppSidebar from './AppSidebar.vue';
import AppHeader from './AppHeader.vue';
import InlineCustomerService from '@/components/support/InlineCustomerService.vue';
import ReturnBanner from './ReturnBanner.vue';

const appStore = useAppStore();
const route = useRoute();
const permissionStore = usePermissionStore();

// Sprint 4 W1 C-INLINE-CS-1: 在线客服入口 URL. 后续接入实际客服系统时改 env / runtime config.
const customerServiceUrl = (import.meta.env.VITE_CUSTOMER_SERVICE_URL as string | undefined) ?? '';

// Keep SmartBIAnalysis in cache to avoid heavy unmount side effects
const keepAliveViews = ['SmartBIAnalysis'];

const routeModuleCode = computed(() =>
  (route.meta.moduleCode as string | undefined) || resolveModuleRegistryItemByRoute(route.path)?.moduleCode,
);
const isReadOnlyRoute = computed(() =>
  !!routeModuleCode.value && permissionStore.effectiveLevelFor(routeModuleCode.value) === 'read',
);

const writeActionPattern = /(新增|新建|创建|保存|提交|删除|编辑|修改|导入|上传|审批|批准|驳回|审核|作废|停用|启用|确认|生成|分配|派工|开票|收款|付款|撤回|出库|入库|add|create|save|submit|delete|edit|update|import|upload|approve|reject|enable|disable|confirm|assign|pay)/i;
const readActionPattern = /^(搜索|查询|筛选|重置|刷新|查看|详情|预览|返回|关闭|取消|导出|下载|打印|复制|展开|收起|search|query|filter|reset|refresh|view|detail|preview|back|close|cancel|export|download|print|copy)$/i;
let readOnlyObserver: MutationObserver | null = null;

function buttonText(button: HTMLElement): string {
  return [
    button.innerText,
    button.textContent,
    button.getAttribute('aria-label'),
    button.getAttribute('title'),
  ]
    .filter(Boolean)
    .join(' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function restoreReadOnlyLocks(root: HTMLElement) {
  root.querySelectorAll<HTMLElement>('[data-module-readonly-locked="true"]').forEach(button => {
    button.removeAttribute('data-module-readonly-locked');
    button.classList.remove('module-readonly-locked');
    if (button.tagName === 'BUTTON') {
      (button as HTMLButtonElement).disabled = button.getAttribute('data-module-readonly-prev-disabled') === 'true';
    }
    button.removeAttribute('data-module-readonly-prev-disabled');
    button.removeAttribute('aria-disabled');
  });
}

function shouldLockButton(button: HTMLElement): boolean {
  if (button.closest('.app-sidebar, .app-header, .el-dialog__footer')) return false;
  const text = buttonText(button);
  if (!text || readActionPattern.test(text)) return false;
  return writeActionPattern.test(text);
}

function applyReadOnlyLocks() {
  const root = document.querySelector<HTMLElement>('.app-content');
  if (!root) return;
  restoreReadOnlyLocks(root);
  if (!isReadOnlyRoute.value) return;
  root.querySelectorAll<HTMLElement>('button, [role="button"]').forEach(button => {
    if (!shouldLockButton(button)) return;
    button.setAttribute('data-module-readonly-locked', 'true');
    button.setAttribute('aria-disabled', 'true');
    button.classList.add('module-readonly-locked');
    if (button.tagName === 'BUTTON') {
      button.setAttribute('data-module-readonly-prev-disabled', String((button as HTMLButtonElement).disabled));
      (button as HTMLButtonElement).disabled = true;
    }
  });
}

async function refreshReadOnlyLocks() {
  await nextTick();
  applyReadOnlyLocks();
}

onMounted(() => {
  appStore.initResponsive();
  void refreshReadOnlyLocks();
  const root = document.querySelector<HTMLElement>('.app-content');
  if (root) {
    readOnlyObserver = new MutationObserver(() => {
      window.requestAnimationFrame(applyReadOnlyLocks);
    });
    readOnlyObserver.observe(root, { childList: true, subtree: true, characterData: true });
  }
});

onBeforeUnmount(() => {
  readOnlyObserver?.disconnect();
  readOnlyObserver = null;
});

watch(
  () => [route.fullPath, isReadOnlyRoute.value, JSON.stringify(permissionStore.moduleLevels)],
  () => { void refreshReadOnlyLocks(); },
);

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

:deep(.module-readonly-locked) {
  cursor: not-allowed !important;
  opacity: 0.55;
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
