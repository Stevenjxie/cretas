<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import {
  ArrowRight,
  Box,
  ChatDotRound,
  Checked,
  Clock,
  DataLine,
  Refresh,
  Tickets,
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { getRestaurantDashboardSummary } from '@/api/restaurant';
import { getRestaurantRoleExperience } from '@/views/restaurant/restaurantRoleExperience';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();

const factoryId = computed(() => authStore.factoryId);
const experience = computed(() => getRestaurantRoleExperience(permissionStore.currentRole));
const displayName = computed(() => authStore.user?.fullName || authStore.user?.username || '餐饮伙伴');

const loading = ref(false);
const loadError = ref('');
const loadedAt = ref<Date | null>(null);
const stats = ref<{
  todayRequisitions: number;
  pendingApprovalCount: number;
  monthWastageCost: number;
  latestStocktakingDate: string | null;
} | null>(null);

const visibleActions = computed(() => experience.value.actions.filter(
  (action) => permissionStore.canAccess(action.module),
));
const primaryAction = computed(() => visibleActions.value.find((action) => action.emphasis === 'primary'));
const secondaryActions = computed(() => visibleActions.value.filter((action) => action !== primaryAction.value));

const dataStatus = computed(() => {
  if (loading.value) return '正在读取今日经营数据';
  if (loadError.value) return '数据读取失败';
  if (!loadedAt.value) return '等待读取';
  return `更新于 ${loadedAt.value.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`;
});

const statCards = computed(() => [
  {
    label: '今日领料单',
    value: stats.value ? stats.value.todayRequisitions.toLocaleString('zh-CN') : '—',
    unit: '单',
    detail: '查看今天已发起的后厨领料',
    path: '/restaurant/requisitions',
    icon: Tickets,
    tone: 'primary',
  },
  {
    label: '待审批',
    value: stats.value ? stats.value.pendingApprovalCount.toLocaleString('zh-CN') : '—',
    unit: '单',
    detail: stats.value?.pendingApprovalCount ? '有事项等待处理' : '当前没有待审批事项',
    path: '/restaurant/requisitions?status=SUBMITTED',
    icon: Checked,
    tone: stats.value?.pendingApprovalCount ? 'warning' : 'neutral',
  },
  {
    label: '本月损耗金额',
    value: !permissionStore.canViewPrice
      ? '—'
      : stats.value
        ? `¥${stats.value.monthWastageCost.toLocaleString('zh-CN', { maximumFractionDigits: 2 })}`
        : '—',
    unit: '',
    detail: permissionStore.canViewPrice ? '进入损耗记录核对明细' : '当前角色无价格查看权限',
    path: '/restaurant/wastage',
    icon: DataLine,
    tone: stats.value?.monthWastageCost && permissionStore.canViewPrice ? 'danger' : 'neutral',
  },
  {
    label: '最近盘点',
    value: stats.value?.latestStocktakingDate || '暂无记录',
    unit: '',
    detail: stats.value?.latestStocktakingDate ? '打开盘点记录查看差异' : '尚未形成可用盘点记录',
    path: '/restaurant/stocktaking',
    icon: Box,
    tone: 'neutral',
  },
]);

function navigateTo(path: string) {
  router.push(path);
}

async function loadDashboardData() {
  if (!factoryId.value) return;
  loading.value = true;
  loadError.value = '';
  try {
    const res = await getRestaurantDashboardSummary(factoryId.value);
    if (!res.success || !res.data) {
      loadError.value = res.message || '餐饮概览暂时不可用';
      return;
    }
    stats.value = {
      todayRequisitions: res.data.todayRequisitionCount ?? 0,
      pendingApprovalCount: res.data.pendingApprovalCount ?? 0,
      monthWastageCost: res.data.thisMonthWastageCost ?? 0,
      latestStocktakingDate: res.data.latestStocktakingDate ?? null,
    };
    loadedAt.value = new Date();
  } catch (error) {
    console.error('[restaurant-dashboard] load failed:', error);
    loadError.value = '餐饮概览加载失败，请重试';
  } finally {
    loading.value = false;
  }
}

onMounted(loadDashboardData);
</script>

<template>
  <main class="restaurant-home" aria-labelledby="restaurant-home-title">
    <section class="role-hero">
      <div class="role-hero__copy">
        <div class="role-hero__eyebrow">
          <span>{{ experience.roleLabel }}</span>
          <span aria-hidden="true">·</span>
          <span>{{ factoryId }}</span>
        </div>
        <h1 id="restaurant-home-title">{{ experience.workspaceLabel }}</h1>
        <p class="role-hero__headline">{{ experience.headline }}</p>
        <p class="role-hero__summary">{{ experience.summary }}</p>
      </div>

      <div class="role-hero__aside">
        <div class="role-hero__user">{{ displayName }}，欢迎回来</div>
        <div class="role-hero__status" :class="{ 'is-error': loadError }">
          <el-icon><Clock /></el-icon>
          {{ dataStatus }}
        </div>
        <el-button
          v-if="primaryAction"
          type="primary"
          size="large"
          @click="navigateTo(primaryAction.path)"
        >
          {{ primaryAction.title }}
          <el-icon class="el-icon--right"><ArrowRight /></el-icon>
        </el-button>
      </div>
    </section>

    <el-alert
      v-if="loadError"
      class="load-alert"
      type="error"
      :title="loadError"
      :closable="false"
      show-icon
    >
      <template #default>
        <el-button link type="primary" :icon="Refresh" @click="loadDashboardData">重新读取</el-button>
      </template>
    </el-alert>

    <section class="today-section" aria-labelledby="today-overview-title">
      <div class="section-heading">
        <div>
          <span class="section-kicker">TODAY</span>
          <h2 id="today-overview-title">今日经营概览</h2>
        </div>
        <span class="section-note">数值来自业务系统；“—”表示无权限或未取得数据</span>
      </div>

      <div class="metric-strip" v-loading="loading">
        <button
          v-for="card in statCards"
          :key="card.label"
          type="button"
          class="metric-item"
          :class="`metric-item--${card.tone}`"
          :aria-label="`${card.label}，${card.value}${card.unit}，${card.detail}`"
          @click="navigateTo(card.path)"
        >
          <span class="metric-item__icon"><el-icon><component :is="card.icon" /></el-icon></span>
          <span class="metric-item__content">
            <span class="metric-item__label">{{ card.label }}</span>
            <span class="metric-item__value">{{ card.value }}<small v-if="card.unit">{{ card.unit }}</small></span>
            <span class="metric-item__detail">{{ card.detail }}</span>
          </span>
          <el-icon class="metric-item__arrow"><ArrowRight /></el-icon>
        </button>
      </div>
    </section>

    <section class="workspace-grid">
      <div class="action-panel">
        <div class="section-heading section-heading--compact">
          <div>
            <span class="section-kicker">NEXT</span>
            <h2>接下来做什么</h2>
          </div>
          <span class="section-note">已按你的角色和权限排序</span>
        </div>

        <div class="action-list">
          <button
            v-for="(action, index) in secondaryActions"
            :key="action.path"
            type="button"
            class="action-row"
            @click="navigateTo(action.path)"
          >
            <span class="action-row__index">{{ String(index + 1).padStart(2, '0') }}</span>
            <span class="action-row__copy">
              <strong>{{ action.title }}</strong>
              <small>{{ action.description }}</small>
            </span>
            <el-icon><ArrowRight /></el-icon>
          </button>
        </div>
      </div>

      <aside class="role-brief" aria-labelledby="role-brief-title">
        <div class="role-brief__head">
          <div>
            <span class="section-kicker">ROLE</span>
            <h2 id="role-brief-title">你的职责边界</h2>
          </div>
          <span class="role-brief__badge">{{ experience.roleLabel }}</span>
        </div>
        <ul>
          <li v-for="item in experience.responsibilities" :key="item">{{ item }}</li>
        </ul>
        <div class="role-brief__handoff">
          <strong>交接原则</strong>
          <p>{{ experience.handoff }}</p>
        </div>
        <button type="button" class="ai-link" @click="navigateTo('/smart-bi/query')">
          <span class="ai-link__icon"><el-icon><ChatDotRound /></el-icon></span>
          <span>
            <strong>{{ experience.ai.title }}</strong>
            <small>{{ experience.ai.description }}</small>
          </span>
          <el-icon><ArrowRight /></el-icon>
        </button>
      </aside>
    </section>
  </main>
</template>

<style lang="scss" scoped>
.restaurant-home {
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-height: calc(100vh - 112px);
  padding: 24px;
  color: var(--color-text-primary, #1a2332);
}

.role-hero {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 32px;
  padding: 28px 32px;
  overflow: hidden;
  background: var(--el-bg-color, #fff);
  border: 1px solid var(--el-border-color-lighter, #e4e7ed);
  border-radius: var(--radius-lg, 14px);
  box-shadow: 0 10px 30px rgba(12, 25, 41, 0.06);

  &::before {
    content: '';
    position: absolute;
    inset: 0 auto 0 0;
    width: 4px;
    background: var(--color-primary, #1b65a8);
  }
}

.role-hero__eyebrow,
.section-kicker {
  color: var(--color-primary, #1b65a8);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.role-hero__eyebrow { display: flex; align-items: center; gap: 8px; }
.role-hero h1 { margin: 8px 0 6px; font-size: clamp(26px, 3vw, 36px); line-height: 1.15; letter-spacing: -0.03em; }
.role-hero__headline { margin: 0; font-size: 17px; font-weight: 600; }
.role-hero__summary { max-width: 62ch; margin: 8px 0 0; color: var(--el-text-color-regular, #606266); font-size: 14px; line-height: 1.7; }
.role-hero__aside { display: flex; min-width: 220px; flex-direction: column; align-items: flex-end; justify-content: center; gap: 10px; }
.role-hero__user { font-weight: 600; }
.role-hero__status { display: inline-flex; align-items: center; gap: 6px; color: var(--el-text-color-secondary, #909399); font-size: 12px; }
.role-hero__status.is-error { color: var(--el-color-danger); }
.load-alert { border-radius: 10px; }

.today-section,
.action-panel,
.role-brief {
  background: var(--el-bg-color, #fff);
  border: 1px solid var(--el-border-color-lighter, #e4e7ed);
  border-radius: 12px;
}

.today-section { padding: 20px; }
.section-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.section-heading h2,
.role-brief h2 { margin: 3px 0 0; font-size: 17px; line-height: 1.3; }
.section-heading--compact { padding: 20px 20px 0; }
.section-note { color: var(--el-text-color-secondary, #909399); font-size: 12px; }

.metric-strip { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border: 1px solid var(--el-border-color-lighter, #e4e7ed); border-radius: 10px; overflow: hidden; }
.metric-item {
  position: relative;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 12px;
  min-width: 0;
  padding: 16px;
  text-align: left;
  color: inherit;
  background: transparent;
  border: 0;
  border-right: 1px solid var(--el-border-color-lighter, #e4e7ed);
  cursor: pointer;
  transition: background-color 160ms ease, box-shadow 160ms ease;
}
.metric-item:last-child { border-right: 0; }
.metric-item:hover { background: var(--el-fill-color-light, #f5f7fa); }
.metric-item:focus-visible,
.action-row:focus-visible,
.ai-link:focus-visible { outline: 2px solid var(--color-primary, #1b65a8); outline-offset: -2px; }
.metric-item__icon { display: grid; width: 32px; height: 32px; place-items: center; color: var(--color-primary, #1b65a8); background: var(--el-color-primary-light-9, #ecf5ff); border-radius: 8px; }
.metric-item--warning .metric-item__icon { color: var(--el-color-warning); background: var(--el-color-warning-light-9); }
.metric-item--danger .metric-item__icon { color: var(--el-color-danger); background: var(--el-color-danger-light-9); }
.metric-item__content { display: flex; min-width: 0; flex-direction: column; }
.metric-item__label { color: var(--el-text-color-secondary, #909399); font-size: 12px; }
.metric-item__value { margin-top: 4px; overflow: hidden; font-size: 22px; font-weight: 680; font-variant-numeric: tabular-nums; letter-spacing: -0.025em; text-overflow: ellipsis; white-space: nowrap; }
.metric-item__value small { margin-left: 4px; font-size: 12px; font-weight: 500; }
.metric-item__detail { margin-top: 5px; overflow: hidden; color: var(--el-text-color-secondary, #909399); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.metric-item__arrow { align-self: center; color: var(--el-text-color-placeholder, #c0c4cc); }

.workspace-grid { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(320px, 0.75fr); gap: 20px; }
.action-list { padding: 4px 20px 16px; }
.action-row { display: grid; width: 100%; grid-template-columns: 36px minmax(0, 1fr) auto; align-items: center; gap: 12px; padding: 14px 4px; text-align: left; color: inherit; background: transparent; border: 0; border-bottom: 1px solid var(--el-border-color-lighter, #e4e7ed); cursor: pointer; }
.action-row:last-child { border-bottom: 0; }
.action-row:hover strong { color: var(--color-primary, #1b65a8); }
.action-row__index { color: var(--el-text-color-placeholder, #c0c4cc); font-size: 12px; font-variant-numeric: tabular-nums; }
.action-row__copy { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.action-row__copy strong { font-size: 14px; transition: color 160ms ease; }
.action-row__copy small { color: var(--el-text-color-secondary, #909399); font-size: 12px; }

.role-brief { padding: 20px; }
.role-brief__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.role-brief__badge { padding: 5px 9px; color: var(--color-primary, #1b65a8); font-size: 11px; font-weight: 600; background: var(--el-color-primary-light-9, #ecf5ff); border-radius: 999px; }
.role-brief ul { display: grid; gap: 8px; margin: 18px 0; padding: 0; list-style: none; }
.role-brief li { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.role-brief li::before { content: ''; width: 6px; height: 6px; background: var(--color-primary, #1b65a8); border-radius: 50%; }
.role-brief__handoff { padding: 12px 14px; background: var(--el-fill-color-light, #f5f7fa); border-radius: 8px; }
.role-brief__handoff strong { font-size: 12px; }
.role-brief__handoff p { margin: 4px 0 0; color: var(--el-text-color-regular, #606266); font-size: 12px; line-height: 1.6; }
.ai-link { display: grid; width: 100%; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 10px; margin-top: 14px; padding: 12px; text-align: left; color: inherit; background: transparent; border: 1px solid var(--el-border-color-lighter, #e4e7ed); border-radius: 9px; cursor: pointer; transition: border-color 160ms ease, background-color 160ms ease; }
.ai-link:hover { background: var(--el-fill-color-light, #f5f7fa); border-color: var(--el-color-primary-light-5); }
.ai-link__icon { display: grid; width: 34px; height: 34px; place-items: center; color: #fff; background: var(--color-primary, #1b65a8); border-radius: 9px; }
.ai-link span:nth-child(2) { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.ai-link strong { font-size: 13px; }
.ai-link small { color: var(--el-text-color-secondary, #909399); font-size: 11px; line-height: 1.5; }

@media (max-width: 1100px) {
  .metric-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .metric-item:nth-child(2) { border-right: 0; }
  .metric-item:nth-child(-n + 2) { border-bottom: 1px solid var(--el-border-color-lighter, #e4e7ed); }
  .workspace-grid { grid-template-columns: 1fr; }
}

@media (max-width: 768px) {
  .restaurant-home { gap: 14px; padding: 12px; }
  .role-hero { grid-template-columns: 1fr; gap: 18px; padding: 22px 20px; }
  .role-hero__aside { min-width: 0; align-items: stretch; }
  .role-hero__aside .el-button { width: 100%; }
  .section-heading { align-items: flex-start; flex-direction: column; gap: 5px; }
  .metric-strip { grid-template-columns: 1fr; }
  .metric-item { border-right: 0; border-bottom: 1px solid var(--el-border-color-lighter, #e4e7ed); }
  .metric-item:nth-child(2) { border-right: 0; }
  .metric-item:nth-child(-n + 2) { border-bottom: 1px solid var(--el-border-color-lighter, #e4e7ed); }
  .metric-item:last-child { border-bottom: 0; }
}

@media (prefers-reduced-motion: reduce) {
  .metric-item,
  .action-row__copy strong,
  .ai-link { transition: none; }
}
</style>
