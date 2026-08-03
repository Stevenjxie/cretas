<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
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
import { getRestaurantStaffingDashboard } from '@/api/smartbi/restaurant-staffing';
import type { StaffingDashboard } from '@/types/restaurant-staffing';
import { getRestaurantRoleExperience } from '@/views/restaurant/restaurantRoleExperience';
import {
  buildRestaurantCommandMetrics,
  latestStaffingSourceUpdate,
  resolveRestaurantTransmissionState,
  type RestaurantOpsSnapshot,
} from './restaurantLiveCommand';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();

const factoryId = computed(() => authStore.factoryId);
const experience = computed(() => getRestaurantRoleExperience(permissionStore.currentRole));
const displayName = computed(() => authStore.user?.fullName || authStore.user?.username || '餐饮伙伴');

const loading = ref(false);
const loadError = ref('');
const loadedAt = ref<Date | null>(null);
const stats = ref<RestaurantOpsSnapshot | null>(null);
const staffingDashboard = ref<StaffingDashboard | null>(null);
const opsError = ref('');
const staffingError = ref('');
const nextRefreshAt = ref<Date | null>(null);
const clockNow = ref(Date.now());
const lastRefreshDurationMs = ref<number | null>(null);
const AUTO_REFRESH_MS = 60_000;
let refreshTimer: ReturnType<typeof setInterval> | null = null;
let clockTimer: ReturnType<typeof setInterval> | null = null;

const STAFFING_COMMAND_ROLES = new Set([
  'restaurant_owner',
  'restaurant_manager',
  'hr_admin',
  'factory_super_admin',
  'platform_admin',
  'permission_admin',
]);
const staffingExpected = computed(() => (
  STAFFING_COMMAND_ROLES.has(permissionStore.currentRole)
  && permissionStore.canAccess('restaurantHr')
));
const opsExpected = computed(() => permissionStore.canAccess('restaurantOps'));
const commandMetrics = computed(() => {
  const metrics = buildRestaurantCommandMetrics(
    opsExpected.value ? stats.value : null,
    staffingExpected.value ? staffingDashboard.value : null,
  );
  return opsExpected.value ? metrics : metrics.filter((item) => !['requisitions', 'pending'].includes(item.key));
});
const transmissionState = computed(() => resolveRestaurantTransmissionState({
  loading: loading.value,
  hasOps: stats.value !== null,
  hasStaffing: staffingDashboard.value !== null,
  opsExpected: opsExpected.value,
  staffingExpected: staffingExpected.value,
  hasError: Boolean(opsError.value || staffingError.value),
}));
const transmissionLabel = computed(() => ({
  idle: '等待连接',
  connecting: '正在建立数据链路',
  refreshing: '正在接收最新数据',
  live: '数据链路正常',
  partial: '部分数据链路异常',
  error: '数据链路不可用',
}[transmissionState.value]));
const nextRefreshSeconds = computed(() => {
  if (!nextRefreshAt.value) return null;
  return Math.max(0, Math.ceil((nextRefreshAt.value.getTime() - clockNow.value) / 1000));
});
const latestReservationUpdate = computed(() => latestStaffingSourceUpdate(staffingDashboard.value));
const commandLinks = computed(() => [
  {
    key: 'ops',
    label: '今日经营汇总',
    detail: !opsExpected.value ? '当前角色不读取运营数据' : opsError.value || (stats.value ? '已接收' : '等待响应'),
    state: !opsExpected.value
      ? 'restricted'
      : opsError.value ? 'error' : stats.value ? 'done' : loading.value ? 'active' : 'idle',
  },
  {
    key: 'factbook',
    label: '预订 / POS / 客流',
    detail: !staffingExpected.value
      ? '当前角色不读取排班数据'
      : staffingError.value || (staffingDashboard.value
        ? `${staffingDashboard.value.sources.length} 个预订来源 · 7/30/365 天趋势`
        : '等待预测数据'),
    state: !staffingExpected.value
      ? 'restricted'
      : staffingError.value ? 'error' : staffingDashboard.value ? 'done' : loading.value ? 'active' : 'idle',
  },
  {
    key: 'forecast',
    label: '预测 FactBook',
    detail: staffingDashboard.value
      ? `生成于 ${formatDateTime(staffingDashboard.value.generatedAt)}`
      : staffingExpected.value ? '尚未生成' : '当前角色不读取',
    state: staffingDashboard.value ? 'done' : staffingExpected.value && loading.value ? 'active' : 'idle',
  },
  {
    key: 'ai',
    label: '大模型解释',
    detail: '未调用 · 提问时只读 FactBook',
    state: 'on-demand',
  },
]);

const visibleActions = computed(() => experience.value.actions.filter(
  (action) => permissionStore.canAccess(action.module),
));
const primaryAction = computed(() => visibleActions.value.find((action) => action.emphasis === 'primary'));
const secondaryActions = computed(() => visibleActions.value.filter((action) => action !== primaryAction.value));
const aiEntryPath = computed(() => (
  experience.value.role === 'hr_admin' ? '/restaurant/staffing' : '/smart-bi/query'
));

const dataStatus = computed(() => {
  if (loading.value) return '正在读取今日经营数据';
  if (loadError.value) return '数据读取失败';
  if (!loadedAt.value) return '等待读取';
  return `更新于 ${loadedAt.value.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`;
});

function formatDateTime(value: string | Date | null | undefined): string {
  if (!value) return '—';
  const parsed = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(parsed.getTime())) return String(value);
  return parsed.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  });
}

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
  if (!factoryId.value || loading.value) return;
  const startedAt = performance.now();
  loading.value = true;
  loadError.value = '';
  opsError.value = '';
  staffingError.value = '';
  try {
    const opsRequest = opsExpected.value
      ? getRestaurantDashboardSummary(factoryId.value)
      : Promise.resolve(null);
    const staffingRequest = staffingExpected.value
      ? getRestaurantStaffingDashboard('tomorrow')
      : Promise.resolve<StaffingDashboard | null>(null);
    const [opsResult, staffingResult] = await Promise.allSettled([opsRequest, staffingRequest]);

    if (!opsExpected.value) {
      stats.value = null;
    } else if (opsResult.status === 'fulfilled' && opsResult.value?.success && opsResult.value.data) {
      stats.value = {
        todayRequisitions: opsResult.value.data.todayRequisitionCount,
        pendingApprovalCount: opsResult.value.data.pendingApprovalCount,
        monthWastageCost: opsResult.value.data.thisMonthWastageCost,
        latestStocktakingDate: opsResult.value.data.latestStocktakingDate,
      };
    } else {
      opsError.value = opsResult.status === 'rejected'
        ? (opsResult.reason instanceof Error ? opsResult.reason.message : '今日经营汇总读取失败')
        : opsResult.value?.message || '今日经营汇总暂不可用';
    }

    if (staffingExpected.value) {
      if (staffingResult.status === 'fulfilled' && staffingResult.value) {
        staffingDashboard.value = staffingResult.value;
      } else {
        staffingError.value = staffingResult.status === 'rejected'
          ? (staffingResult.reason instanceof Error ? staffingResult.reason.message : '预测 FactBook 读取失败')
          : '预测 FactBook 暂不可用';
      }
    } else {
      staffingDashboard.value = null;
    }

    loadError.value = [opsError.value, staffingError.value].filter(Boolean).join('；');
    loadedAt.value = new Date();
  } finally {
    lastRefreshDurationMs.value = Math.round(performance.now() - startedAt);
    nextRefreshAt.value = new Date(Date.now() + AUTO_REFRESH_MS);
    loading.value = false;
  }
}

function handleVisibilityChange() {
  if (document.visibilityState === 'visible') {
    const overdue = !nextRefreshAt.value || nextRefreshAt.value.getTime() <= Date.now();
    if (overdue) void loadDashboardData();
  }
}

onMounted(() => {
  void loadDashboardData();
  refreshTimer = setInterval(() => {
    if (document.visibilityState === 'visible') void loadDashboardData();
  }, AUTO_REFRESH_MS);
  clockTimer = setInterval(() => {
    clockNow.value = Date.now();
  }, 1000);
  document.addEventListener('visibilitychange', handleVisibilityChange);
});

onBeforeUnmount(() => {
  if (refreshTimer) clearInterval(refreshTimer);
  if (clockTimer) clearInterval(clockTimer);
  document.removeEventListener('visibilitychange', handleVisibilityChange);
});
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

    <section
      class="live-command"
      :class="[`live-command--${transmissionState}`, { 'is-transmitting': loading }]"
      aria-labelledby="live-command-title"
      data-testid="restaurant-live-command"
    >
      <header class="live-command__header">
        <div>
          <div class="live-command__eyebrow">
            <span class="live-command__signal" aria-hidden="true"></span>
            <span>{{ transmissionLabel }}</span>
            <span aria-hidden="true">/</span>
            <span>60 秒只读自动刷新</span>
          </div>
          <h2 id="live-command-title">餐饮 AI 实时经营指挥屏</h2>
          <p>经营汇总与预测 FactBook 独立传输；大模型仅在提问时读取事实并解释，不在此屏生成业务数字。</p>
        </div>
        <div class="live-command__tools">
          <div class="live-command__clock" aria-live="polite">
            <span>最后接收</span>
            <strong>{{ formatDateTime(loadedAt) }}</strong>
            <small v-if="lastRefreshDurationMs !== null">
              本次 {{ lastRefreshDurationMs }} ms
              <template v-if="nextRefreshSeconds !== null"> · {{ nextRefreshSeconds }} 秒后刷新</template>
            </small>
          </div>
          <el-button
            :icon="Refresh"
            :loading="loading"
            plain
            aria-label="立即刷新餐饮经营与预测数据"
            @click="loadDashboardData"
          >立即刷新</el-button>
        </div>
      </header>

      <div class="live-command__body">
        <div
          class="digital-grid"
          :class="{ 'digital-grid--staffing-only': !opsExpected }"
          v-loading="loading && commandMetrics.every((item) => item.value === '—')"
        >
          <article
            v-for="metric in commandMetrics"
            :key="metric.key"
            class="digital-metric"
            :class="'digital-metric--' + metric.tone"
            :data-testid="'restaurant-live-metric-' + metric.key"
          >
            <span class="digital-metric__label">{{ metric.label }}</span>
            <strong class="digital-metric__value">{{ metric.value }}<small>{{ metric.unit }}</small></strong>
            <span class="digital-metric__detail">{{ metric.detail }}</span>
            <span class="digital-metric__source">{{ metric.source }}</span>
          </article>
        </div>

        <aside class="transmission-rail" aria-label="实时数据传输链路">
          <div class="transmission-rail__title">
            <span>数据传输链路</span>
            <strong>{{ factoryId }}</strong>
          </div>
          <ol>
            <li
              v-for="link in commandLinks"
              :key="link.key"
              :class="'is-' + link.state"
            >
              <span class="transmission-node" aria-hidden="true"></span>
              <div>
                <strong>{{ link.label }}</strong>
                <small>{{ link.detail }}</small>
              </div>
            </li>
          </ol>
          <div class="transmission-rail__footer">
            <span>预订最新事件</span>
            <strong>{{ formatDateTime(latestReservationUpdate) }}</strong>
            <small v-if="staffingDashboard?.sources.some((source) => source.isSimulated)">
              含明确标记的模拟来源，不冒充平台实单
            </small>
            <small v-else-if="staffingDashboard">当前返回来源均标记为平台数据</small>
            <small v-else>尚未取得可显示的预订来源</small>
          </div>
        </aside>
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

    <section v-if="opsExpected" class="today-section" aria-labelledby="today-overview-title">
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
        <button type="button" class="ai-link" @click="navigateTo(aiEntryPath)">
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

.live-command {
  --command-accent: #62d5c5;
  --command-warn: #f2c572;
  position: relative;
  overflow: hidden;
  color: #f5f8fb;
  background:
    radial-gradient(circle at 12% 0%, rgba(50, 139, 155, 0.24), transparent 34%),
    linear-gradient(135deg, #0d1c2d 0%, #11283b 58%, #102233 100%);
  border: 1px solid rgba(111, 178, 192, 0.32);
  border-radius: 16px;
  box-shadow: 0 18px 40px rgba(10, 29, 45, 0.18);
}

.live-command::after {
  position: absolute;
  inset: 0;
  pointer-events: none;
  content: '';
  background-image: linear-gradient(rgba(255, 255, 255, 0.018) 1px, transparent 1px);
  background-size: 100% 4px;
}

.live-command--partial { border-color: rgba(242, 197, 114, 0.72); }
.live-command--error { border-color: rgba(245, 108, 108, 0.72); }

.live-command__header {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  padding: 22px 24px 18px;
  border-bottom: 1px solid rgba(187, 215, 224, 0.14);
}

.live-command__eyebrow {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  color: #a9becb;
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.live-command__signal {
  width: 8px;
  height: 8px;
  background: var(--command-accent);
  border-radius: 50%;
  box-shadow: 0 0 0 3px rgba(98, 213, 197, 0.12);
}

.live-command--partial .live-command__signal { background: var(--command-warn); }
.live-command--error .live-command__signal { background: #f56c6c; }
.live-command h2 { margin: 8px 0 5px; font-size: clamp(20px, 2.2vw, 28px); letter-spacing: -0.025em; }
.live-command__header p { max-width: 72ch; margin: 0; color: #a9becb; font-size: 12px; line-height: 1.65; }
.live-command__tools { display: flex; flex: none; align-items: center; gap: 14px; }
.live-command__clock { min-width: 188px; text-align: right; }
.live-command__clock span,
.live-command__clock small,
.digital-metric__label,
.digital-metric__detail,
.digital-metric__source { display: block; }
.live-command__clock span { color: #7f9bab; font-size: 10px; letter-spacing: .08em; }
.live-command__clock strong { display: block; margin: 3px 0; font-size: 13px; font-variant-numeric: tabular-nums; }
.live-command__clock small { color: #87a5b5; font-size: 10px; font-variant-numeric: tabular-nums; }
.live-command__tools :deep(.el-button) {
  color: #d9eef0;
  background: rgba(255, 255, 255, 0.04);
  border-color: rgba(154, 202, 207, 0.36);
}
.live-command__tools :deep(.el-button:hover),
.live-command__tools :deep(.el-button:focus-visible) {
  color: #fff;
  background: rgba(98, 213, 197, 0.12);
  border-color: var(--command-accent);
}

.live-command__body {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(280px, 0.34fr);
}

.digital-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-right: 1px solid rgba(187, 215, 224, 0.14);
}

.digital-metric {
  min-width: 0;
  padding: 20px 22px;
  border-right: 1px solid rgba(187, 215, 224, 0.1);
  border-bottom: 1px solid rgba(187, 215, 224, 0.1);
}
.digital-metric:nth-child(3n) { border-right: 0; }
.digital-metric:nth-last-child(-n + 3) { border-bottom: 0; }
.digital-grid--staffing-only { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.digital-grid--staffing-only .digital-metric { border-bottom: 0; }
.digital-grid--staffing-only .digital-metric:nth-child(3n) { border-right: 1px solid rgba(187, 215, 224, 0.1); }
.digital-grid--staffing-only .digital-metric:last-child { border-right: 0; }
.digital-metric__label { color: #9bb1bf; font-size: 11px; }
.digital-metric__value {
  display: block;
  margin: 7px 0 8px;
  overflow: hidden;
  color: #f7fbfc;
  font-family: 'SFMono-Regular', 'Cascadia Mono', 'Roboto Mono', ui-monospace, monospace;
  font-size: clamp(25px, 2.6vw, 38px);
  font-variant-numeric: tabular-nums slashed-zero;
  font-weight: 650;
  letter-spacing: -0.05em;
  line-height: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.digital-metric__value small { margin-left: 5px; color: #88a4b3; font-family: inherit; font-size: 10px; font-weight: 500; letter-spacing: 0; }
.digital-metric__detail { min-height: 16px; color: #a9becb; font-size: 10px; }
.digital-metric__source { margin-top: 7px; color: #5f8293; font-size: 9px; letter-spacing: .04em; }
.digital-metric--primary .digital-metric__value { color: #8fe0d6; }
.digital-metric--warning .digital-metric__value { color: var(--command-warn); }
.digital-metric--danger .digital-metric__value { color: #ff9c96; }

.transmission-rail { padding: 18px 20px; background: rgba(4, 14, 25, 0.18); }
.transmission-rail__title { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; color: #8fa9b8; font-size: 10px; letter-spacing: .08em; }
.transmission-rail__title strong { color: #d5e3e8; font-family: ui-monospace, monospace; font-size: 10px; }
.transmission-rail ol { display: grid; gap: 0; margin: 0; padding: 0; list-style: none; }
.transmission-rail li { position: relative; display: grid; grid-template-columns: 14px minmax(0, 1fr); gap: 9px; min-height: 52px; }
.transmission-rail li:not(:last-child)::after { position: absolute; top: 13px; bottom: -1px; left: 5px; width: 1px; content: ''; background: rgba(127, 166, 179, 0.28); }
.transmission-node { position: relative; z-index: 1; width: 9px; height: 9px; margin-top: 4px; background: #496777; border: 2px solid #173044; border-radius: 50%; }
.transmission-rail li.is-done .transmission-node { background: var(--command-accent); }
.transmission-rail li.is-active .transmission-node { background: #82c8ef; }
.transmission-rail li.is-error .transmission-node { background: #f56c6c; }
.transmission-rail li.is-restricted .transmission-node,
.transmission-rail li.is-on-demand .transmission-node { background: var(--command-warn); }
.transmission-rail li strong,
.transmission-rail li small { display: block; }
.transmission-rail li strong { color: #dae6ea; font-size: 11px; font-weight: 620; }
.transmission-rail li small { margin-top: 3px; overflow-wrap: anywhere; color: #7895a5; font-size: 9px; line-height: 1.4; }
.live-command.is-transmitting .transmission-rail li.is-active .transmission-node {
  animation: command-transmitting 1s ease-in-out infinite;
}
.transmission-rail__footer { padding-top: 12px; border-top: 1px solid rgba(187, 215, 224, 0.12); }
.transmission-rail__footer span,
.transmission-rail__footer strong,
.transmission-rail__footer small { display: block; }
.transmission-rail__footer span { color: #708d9c; font-size: 9px; }
.transmission-rail__footer strong { margin: 4px 0; color: #d9e7ea; font-size: 11px; font-variant-numeric: tabular-nums; }
.transmission-rail__footer small { color: #7895a5; font-size: 9px; line-height: 1.45; }

@keyframes command-transmitting {
  0%, 100% { box-shadow: 0 0 0 0 rgba(130, 200, 239, 0); }
  50% { box-shadow: 0 0 0 5px rgba(130, 200, 239, 0.18); }
}

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
  .live-command__body { grid-template-columns: 1fr; }
  .digital-grid { border-right: 0; border-bottom: 1px solid rgba(187, 215, 224, 0.14); }
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
  .live-command__header { flex-direction: column; padding: 18px; }
  .live-command__tools { width: 100%; align-items: stretch; flex-direction: column; }
  .live-command__clock { min-width: 0; text-align: left; }
  .live-command__tools :deep(.el-button) { width: 100%; }
  .digital-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .digital-grid--staffing-only { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .digital-metric { padding: 16px; }
  .digital-metric:nth-child(3n) { border-right: 1px solid rgba(187, 215, 224, 0.1); }
  .digital-metric:nth-child(2n) { border-right: 0; }
  .digital-metric:nth-last-child(-n + 3) { border-bottom: 1px solid rgba(187, 215, 224, 0.1); }
  .digital-metric:nth-last-child(-n + 2) { border-bottom: 0; }
  .digital-grid--staffing-only .digital-metric { border-bottom: 1px solid rgba(187, 215, 224, 0.1); }
  .digital-grid--staffing-only .digital-metric:nth-child(2n) { border-right: 0; }
  .digital-grid--staffing-only .digital-metric:nth-last-child(-n + 2) { border-bottom: 0; }
  .digital-metric__value { font-size: clamp(22px, 7vw, 28px); }
  .section-heading { align-items: flex-start; flex-direction: column; gap: 5px; }
  .metric-strip { grid-template-columns: 1fr; }
  .metric-item { border-right: 0; border-bottom: 1px solid var(--el-border-color-lighter, #e4e7ed); }
  .metric-item:nth-child(2) { border-right: 0; }
  .metric-item:nth-child(-n + 2) { border-bottom: 1px solid var(--el-border-color-lighter, #e4e7ed); }
  .metric-item:last-child { border-bottom: 0; }
}

@media (prefers-reduced-motion: reduce) {
  .live-command.is-transmitting .transmission-rail li.is-active .transmission-node { animation: none; }
  .metric-item,
  .action-row__copy strong,
  .ai-link { transition: none; }
}
</style>
