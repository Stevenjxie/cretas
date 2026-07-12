<script setup lang="ts">
/**
 * 站内通知 bell — 2026-07-11 (餐饮经营体检预警推送: 站内通知 + 短信 goal 的
 * "站内通知" 落地面). 展示当前用户 (老板/管理员/任何登录用户) 的通知,
 * 数据源含 AlertEventNotificationListener 为 RESTAURANT_HEALTH_CHECK 等
 * AlertEvent 创建的 Notification 行, 以及系统其它模块的通知.
 *
 * 与 ReminderBell.vue (提醒, 独立 /sales/reminders 概念) 是不同数据源, 并列显示.
 */
import { ref, onMounted, onBeforeUnmount, computed } from 'vue';
import { useRouter } from 'vue-router';
import { Bell, WarningFilled, InfoFilled, CircleCheckFilled } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import {
  getUnreadCount,
  getRecentNotifications,
  markNotificationRead,
  markAllNotificationsReadForUser,
  type AppNotification,
} from '@/api/notification';

const authStore = useAuthStore();
const router = useRouter();

const unreadCount = ref(0);
const recent = ref<AppNotification[]>([]);
const loading = ref(false);
let timer: ReturnType<typeof setInterval> | null = null;

const factoryId = computed(() => authStore.factoryId);
const userId = computed(() => authStore.user?.id);

async function refreshBadge(): Promise<void> {
  if (!factoryId.value) return;
  try {
    const res = await getUnreadCount(factoryId.value, userId.value);
    unreadCount.value = res?.data?.count ?? 0;
  } catch {
    // 静默 — bell badge 失败不打扰用户 (同 ReminderBell 约定)
  }
}

async function loadRecent(): Promise<void> {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await getRecentNotifications(factoryId.value);
    recent.value = res?.data ?? [];
  } catch {
    recent.value = [];
  } finally {
    loading.value = false;
  }
}

function onPopoverShow(): void {
  loadRecent();
}

async function handleItemClick(n: AppNotification): Promise<void> {
  if (!factoryId.value) return;
  if (!n.isRead) {
    try {
      await markNotificationRead(factoryId.value, n.id);
      n.isRead = true;
      unreadCount.value = Math.max(0, unreadCount.value - 1);
    } catch {
      // 静默 — mark-read 失败不阻塞用户查看跳转
    }
  }
  if (n.actionUrl) {
    router.push(n.actionUrl);
  }
}

async function handleMarkAllRead(): Promise<void> {
  if (!factoryId.value || userId.value == null) return;
  try {
    await markAllNotificationsReadForUser(factoryId.value, userId.value);
    recent.value = recent.value.map((n) => ({ ...n, isRead: true }));
    unreadCount.value = 0;
  } catch {
    // 静默
  }
}

function typeIcon(type: string) {
  if (type === 'ALERT' || type === 'WARNING') return WarningFilled;
  if (type === 'SUCCESS') return CircleCheckFilled;
  return InfoFilled;
}

function typeColor(type: string): string {
  if (type === 'ALERT') return '#f56c6c';
  if (type === 'WARNING') return '#e6a23c';
  if (type === 'SUCCESS') return '#67c23a';
  return '#909399';
}

function formatTime(iso: string | null): string {
  if (!iso) return '';
  return iso.replace('T', ' ').slice(0, 16);
}

onMounted(() => {
  refreshBadge();
  timer = setInterval(refreshBadge, 60_000);
});

onBeforeUnmount(() => {
  if (timer) clearInterval(timer);
});
</script>

<template>
  <el-popover placement="bottom-end" width="360" trigger="click" @show="onPopoverShow">
    <template #reference>
      <el-tooltip content="站内通知" placement="bottom">
        <el-badge :value="unreadCount" :hidden="unreadCount === 0" :max="99" class="notif-bell">
          <el-icon class="header-action">
            <Bell />
          </el-icon>
        </el-badge>
      </el-tooltip>
    </template>

    <div class="notif-panel">
      <div class="notif-header">
        <span>站内通知</span>
        <el-button link type="primary" size="small" :disabled="unreadCount === 0" @click="handleMarkAllRead">
          全部已读
        </el-button>
      </div>
      <el-scrollbar max-height="360px">
        <template v-if="loading">
          <div class="notif-empty">加载中...</div>
        </template>
        <template v-else-if="recent.length === 0">
          <div class="notif-empty">暂无通知</div>
        </template>
        <template v-else>
          <div
            v-for="n in recent"
            :key="n.id"
            class="notif-item"
            :class="{ unread: !n.isRead }"
            @click="handleItemClick(n)"
          >
            <el-icon :size="16" :color="typeColor(n.type)" class="notif-icon">
              <component :is="typeIcon(n.type)" />
            </el-icon>
            <div class="notif-body">
              <div class="notif-title">{{ n.title }}</div>
              <div class="notif-content">{{ n.content }}</div>
              <div class="notif-time">{{ formatTime(n.createdAt) }}</div>
            </div>
          </div>
        </template>
      </el-scrollbar>
    </div>
  </el-popover>
</template>

<style lang="scss" scoped>
.notif-bell {
  :deep(.el-badge__content) {
    background: #f56c6c;
    border: 0;
  }
}

.header-action {
  font-size: 18px;
  cursor: pointer;
  color: var(--color-text-secondary, #7A8599);
  padding: 8px;
  border-radius: var(--radius-sm, 6px);
  transition: all 0.2s;
  // 修复: flex 布局下 el-icon 的 svg 只继承 height:1em, width 塌成 ~2px → 图标不可见 (同 AppHeader/ReminderBell)。
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

.notif-panel {
  display: flex;
  flex-direction: column;
}

.notif-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  font-size: 14px;
  padding-bottom: 8px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--border-color-light, #EDF2F7);
}

.notif-empty {
  text-align: center;
  color: var(--color-text-secondary, #7A8599);
  padding: 24px 0;
  font-size: 13px;
}

.notif-item {
  display: flex;
  gap: 8px;
  padding: 8px 4px;
  cursor: pointer;
  border-radius: 6px;
  transition: background-color 0.15s;

  &:hover {
    background-color: var(--color-bg-hover, #EDF2F7);
  }

  &.unread {
    background-color: rgba(245, 108, 108, 0.06);
  }
}

.notif-icon {
  flex-shrink: 0;
  margin-top: 2px;
}

.notif-body {
  min-width: 0;
  flex: 1;
}

.notif-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-primary, #1A2332);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.notif-content {
  font-size: 12px;
  color: var(--color-text-secondary, #7A8599);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.notif-time {
  font-size: 11px;
  color: var(--color-text-tertiary, #B0B8C4);
  margin-top: 2px;
}
</style>
