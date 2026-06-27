<script setup lang="ts">
/**
 * 餐饮管理员 Dashboard
 * 适用: factoryType=RESTAURANT 的 factory_super_admin
 * 展示餐饮相关指标：食材库存、领料、盘点、损耗、出勤、营业额
 */
import { ref, onMounted, computed } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { getRestaurantDashboardSummary } from '@/api/restaurant';
import { ElMessage } from 'element-plus';
import {
  KnifeFork, Box, Money, User, ShoppingCart, TrendCharts, Warning, DataLine
} from '@element-plus/icons-vue';

const router = useRouter();
const authStore = useAuthStore();

const loading = ref(false);
const factoryId = computed(() => authStore.factoryId);

// 餐饮统计数据
const stats = ref({
  todayRequisitions: 0,
  pendingApprovalCount: 0,
  monthWastageCost: 0,
  latestStocktakingDate: '' as string | null,
});

// 统计卡片 — 餐饮核心指标
const statCards = computed(() => [
  {
    title: '今日领料单',
    value: stats.value.todayRequisitions,
    unit: '单',
    icon: KnifeFork,
    color: 'var(--el-color-primary)',
    route: '/restaurant/requisitions'
  },
  {
    title: '待审批',
    value: stats.value.pendingApprovalCount,
    unit: '单',
    icon: Warning,
    color: 'var(--el-color-warning)',
    // Apr 20 Bug BR-03 fix: 点击"待审批"应进入 SUBMITTED 过滤列表, 而不是全列表
    route: '/restaurant/requisitions?status=SUBMITTED'
  },
  {
    title: '本月损耗',
    value: stats.value.monthWastageCost ? `¥${stats.value.monthWastageCost.toLocaleString()}` : '0',
    unit: stats.value.monthWastageCost ? '' : '元',
    icon: DataLine,
    color: 'var(--el-color-danger)',
    route: '/restaurant/wastage'
  },
  {
    title: '最近盘点',
    value: stats.value.latestStocktakingDate || '暂无',
    unit: '',
    icon: Box,
    color: 'var(--el-color-success)',
    route: '/restaurant/stocktaking'
  }
]);

// 快捷操作 — 餐饮常用入口 (按权限过滤)
import { usePermissionStore, type ModuleName } from '@/store/modules/permission';
const permissionStore = usePermissionStore();

const allQuickActions = [
  { title: '配方管理', icon: KnifeFork, route: '/restaurant/recipes', color: '#2D8B57', module: 'restaurant' },
  { title: '领料管理', icon: Box, route: '/restaurant/requisitions', color: '#36B37E', module: 'restaurant' },
  { title: '盘点管理', icon: ShoppingCart, route: '/restaurant/stocktaking', color: '#FFAB00', module: 'restaurant' },
  { title: '损耗管理', icon: Warning, route: '/restaurant/wastage', color: '#FF5630', module: 'restaurant' },
  { title: '采购订单', icon: ShoppingCart, route: '/procurement/orders', color: '#909399', module: 'procurement' },
  { title: '财务报表', icon: Money, route: '/finance/three-statements', color: '#5ad8a6', module: 'finance' },
  { title: '智能BI', icon: TrendCharts, route: '/smart-bi/dashboard', color: '#5b8ff9', module: 'analytics' }
];
const quickActions = computed(() => allQuickActions.filter(a => permissionStore.canAccess(a.module as ModuleName)));

onMounted(async () => {
  await loadDashboardData();
});

async function loadDashboardData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const res = await getRestaurantDashboardSummary(factoryId.value);
    if (res.success && res.data) {
      stats.value.todayRequisitions = res.data.todayRequisitionCount ?? 0;
      stats.value.pendingApprovalCount = res.data.pendingApprovalCount ?? 0;
      stats.value.monthWastageCost = res.data.thisMonthWastageCost ?? 0;
      stats.value.latestStocktakingDate = res.data.latestStocktakingDate;
    }
  } catch (error) {
    console.error('Failed to load restaurant dashboard data:', error);
    ElMessage.error('加载餐饮概览失败');
  } finally {
    loading.value = false;
  }
}

function navigateTo(route: string) {
  router.push(route);
}
</script>

<template>
  <div class="dashboard-restaurant" v-loading="loading">
    <!-- 欢迎区 -->
    <div class="welcome-section">
      <div class="welcome-info">
        <h1>欢迎回来，{{ authStore.user?.fullName || authStore.user?.username }}</h1>
        <p>
          <el-tag type="success" size="small">餐饮管理员</el-tag>
          <span class="factory-info">门店: {{ factoryId }}</span>
        </p>
      </div>
      <div class="ai-entry">
        <el-button type="primary" @click="navigateTo('/smart-bi/dashboard')">
          <el-icon><TrendCharts /></el-icon>
          经营驾驶舱
        </el-button>
      </div>
    </div>

    <!-- 统计卡片 -->
    <el-row :gutter="20" class="stat-cards">
      <el-col v-for="card in statCards" :key="card.title" :xs="24" :sm="12" :md="6">
        <el-card class="stat-card" shadow="hover" @click="navigateTo(card.route)">
          <div class="stat-content">
            <div class="stat-info">
              <span class="stat-title">{{ card.title }}</span>
              <span class="stat-value" :style="{ color: card.color }">
                {{ card.value }}
                <small>{{ card.unit }}</small>
              </span>
            </div>
            <el-icon class="stat-icon" :style="{ backgroundColor: card.color + '20', color: card.color }">
              <component :is="card.icon" />
            </el-icon>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 快捷操作 -->
    <el-card class="quick-actions-card">
      <template #header>
        <span>快捷操作</span>
      </template>
      <div class="quick-actions">
        <div
          v-for="action in quickActions"
          :key="action.title"
          class="action-item"
          @click="navigateTo(action.route)"
        >
          <el-icon :size="32" :style="{ color: action.color }">
            <component :is="action.icon" />
          </el-icon>
          <span>{{ action.title }}</span>
        </div>
      </div>
    </el-card>

    <!-- 餐饮概览 -->
    <el-row :gutter="20" class="overview-section">
      <el-col :xs="24" :lg="12">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>今日运营</span>
              <el-button text type="primary" @click="navigateTo('/restaurant/requisitions')">查看详情</el-button>
            </div>
          </template>
          <div class="overview-content">
            <div class="overview-item">
              <span class="label">今日领料</span>
              <span class="value">{{ stats.todayRequisitions }} 单</span>
            </div>
            <div class="overview-item">
              <span class="label">待审批</span>
              <span class="value" :class="{ 'text-danger': stats.pendingApprovalCount > 0 }">
                {{ stats.pendingApprovalCount }} 单
              </span>
            </div>
            <div class="overview-item">
              <span class="label">最近盘点</span>
              <span class="value">{{ stats.latestStocktakingDate || '暂无记录' }}</span>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="12">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>经营指标</span>
              <el-button text type="primary" @click="navigateTo('/finance/costs')">成本分析</el-button>
            </div>
          </template>
          <div class="overview-content">
            <div class="overview-item">
              <span class="label">本月损耗金额</span>
              <span class="value" :class="{ 'text-danger': stats.monthWastageCost > 0 }">
                {{ stats.monthWastageCost ? `¥${stats.monthWastageCost.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}` : '-' }}
              </span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style lang="scss" scoped>
@use './dashboard-shared.scss';

.dashboard-restaurant {
  min-height: calc(100vh - 144px);
}

.overview-section {
  .overview-content {
    .overview-item {
      border-bottom-style: solid;
    }
  }
}
</style>
