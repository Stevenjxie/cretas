<script setup lang="ts">
import { computed } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useAppStore } from '@/store/modules/app';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessageBox, ElMessage } from 'element-plus';
import { Fold, Expand, User, SwitchButton, Refresh, Moon, Sunny } from '@element-plus/icons-vue';
import ReminderBell from '@/components/notifications/ReminderBell.vue';
import AlertNotificationBell from '@/components/notifications/AlertNotificationBell.vue';

const router = useRouter();
const route = useRoute();
const appStore = useAppStore();
const authStore = useAuthStore();

// 面包屑
const breadcrumbs = computed(() => {
  const matched = route.matched.filter(r => r.meta.title);
  return matched.map(r => ({
    path: r.path,
    title: r.meta.title as string
  }));
});

// 用户信息
const userInfo = computed(() => ({
  name: authStore.user?.fullName || authStore.user?.username || '用户',
  role: authStore.roleMetadata?.displayName || '未知角色',
  factoryId: authStore.factoryId
}));

// 切换侧边栏（桌面折叠 / 移动端打开抽屉）
function toggleSidebar() {
  if (appStore.isMobile) {
    appStore.openMobileMenu();
  } else {
    appStore.toggleSidebar();
  }
}

// 刷新页面
function handleRefresh() {
  router.go(0);
}

// 退出登录
async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    });

    await authStore.logout();
    ElMessage.success('已退出登录');
    router.push('/login');
  } catch (e) {
    // 用户取消
  }
}
</script>

<template>
  <header class="app-header">
    <div class="header-left">
      <!-- 折叠/汉堡按钮 -->
      <el-icon class="collapse-btn" @click="toggleSidebar">
        <Fold v-if="!appStore.sidebarCollapsed && !appStore.isMobile" />
        <Expand v-else />
      </el-icon>

      <!-- 面包屑（移动端隐藏） -->
      <el-breadcrumb v-if="!appStore.isMobile" separator="/">
        <el-breadcrumb-item
          v-for="item in breadcrumbs"
          :key="item.path"
          :to="item.path"
        >
          {{ item.title }}
        </el-breadcrumb-item>
      </el-breadcrumb>
    </div>

    <div class="header-right">
      <!-- 暗色模式切换 -->
      <el-tooltip :content="appStore.theme === 'light' ? '暗色模式' : '亮色模式'" placement="bottom">
        <el-icon class="header-action" @click="appStore.toggleTheme()">
          <Moon v-if="appStore.theme === 'light'" />
          <Sunny v-else />
        </el-icon>
      </el-tooltip>

      <!-- 2026-07-11: 站内通知 bell (含餐饮经营体检预警/反回扣推送) -->
      <AlertNotificationBell />

      <!-- Sprint 4 W2 S-REMIND-1: 我的提醒 bell badge -->
      <ReminderBell />

      <!-- 刷新按钮 -->
      <el-tooltip content="刷新页面" placement="bottom">
        <el-icon class="header-action" @click="handleRefresh">
          <Refresh />
        </el-icon>
      </el-tooltip>

      <!-- 用户下拉菜单 -->
      <el-dropdown trigger="click">
        <div class="user-info">
          <el-avatar :size="32" :icon="User" />
          <div class="user-detail">
            <span class="user-name">{{ userInfo.name }}</span>
            <span class="user-role">{{ userInfo.role }}</span>
          </div>
        </div>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-if="userInfo.factoryId">
              <el-icon><Box /></el-icon>
              工厂: {{ userInfo.factoryId }}
            </el-dropdown-item>
            <el-dropdown-item divided @click="handleLogout">
              <el-icon><SwitchButton /></el-icon>
              退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<style lang="scss" scoped>
.app-header {
  height: 64px;
  background-color: var(--color-bg-card, rgba(255, 255, 255, 0.85));
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--border-color-light, #EDF2F7);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  position: sticky;
  top: 0;
  z-index: 99;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;

  .collapse-btn {
    font-size: 20px;
    cursor: pointer;
    color: var(--color-text-secondary, #7A8599);
    transition: color 0.2s;
    min-width: 44px;
    min-height: 44px;
    display: flex;
    align-items: center;
    justify-content: center;

    &:hover {
      color: var(--color-primary, #1B65A8);
    }
  }
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;

  .header-action {
    font-size: 18px;
    cursor: pointer;
    color: var(--color-text-secondary, #7A8599);
    padding: 8px;
    border-radius: var(--radius-sm, 6px);
    transition: all 0.2s;

    // 修复: flex 布局下 el-icon 的 svg 只继承到 height:1em, width 塌成 ~2px → 图标不可见 (但可点/hover)。
    //   显式锁定 1em×1em 方形, 保证铃铛/刷新/主题图标真实可见。
    :deep(svg) {
      width: 1em;
      height: 1em;
      flex-shrink: 0;
    }

    &:hover {
      background-color: var(--color-bg-hover, #EDF2F7);
      color: var(--color-primary, #1B65A8);
    }
  }
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: var(--radius-sm, 6px);
  transition: background-color 0.2s;

  &:hover {
    background-color: var(--color-bg-hover, #EDF2F7);
  }

  :deep(.el-avatar) {
    background: linear-gradient(135deg, #1B65A8, #2B7EC1);
    color: #fff;
  }

  .user-detail {
    display: flex;
    flex-direction: column;
    line-height: 1.3;

    .user-name {
      font-size: 14px;
      color: var(--color-text-primary, #1A2332);
    }

    .user-role {
      font-size: 12px;
      color: var(--color-text-secondary, #7A8599);
    }
  }
}

@media (max-width: 768px) {
  .app-header {
    height: var(--header-height, 56px);
    padding: 0 12px;
  }
  .user-detail {
    display: none !important;
  }
}
</style>
