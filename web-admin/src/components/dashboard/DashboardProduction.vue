<script setup lang="ts">
/**
 * 生产管理 Dashboard
 * 适用角色: production_manager, workshop_supervisor
 * 特点: 生产数据为主、批次管理、计划执行
 */
import { ref, onMounted, computed } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import type { DashboardOverview, ProductionStats } from '@/types/api';
import { TrendCharts, DataLine, Timer, Calendar, Document } from '@element-plus/icons-vue';

const router = useRouter();
const authStore = useAuthStore();

const loading = ref(false);
const overview = ref<DashboardOverview | null>(null);
const productionStats = ref<ProductionStats | null>(null);

const factoryId = computed(() => authStore.factoryId);

// 统计卡片
const statCards = computed(() => [
  {
    title: '今日产量',
    value: overview.value?.todayOutput ?? 0,
    unit: 'kg',
    icon: TrendCharts,
    color: '#409eff',
    route: '/production/batches'
  },
  {
    title: '完成批次',
    value: overview.value?.completedBatches ?? 0,
    unit: '个',
    icon: DataLine,
    color: '#67c23a',
    route: '/production/batches'
  },
  {
    title: '进行中批次',
    value: productionStats.value?.inProgressBatches ?? 0,
    unit: '个',
    icon: Timer,
    color: '#e6a23c',
    route: '/production/batches'
  },
  {
    title: '完成率',
    value: overview.value?.completionRate ?? 0,
    unit: '%',
    icon: Document,
    color: '#909399',
    route: '/production/plans'
  }
]);

// 快捷操作
// T125: 转换率配置入口已移除 (后端 API/fallback 仍保留, 维护路径: BOM成本管理 → 转换率 tab)
const quickActions = [
  { title: '生产批次', icon: DataLine, route: '/production/batches', color: '#409eff' },
  { title: '生产计划', icon: Calendar, route: '/production/plans', color: '#67c23a' }
];

onMounted(async () => {
  await loadProductionData();
});

async function loadProductionData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const [overviewRes, productionRes] = await Promise.allSettled([
      get<DashboardOverview>(`/${factoryId.value}/reports/dashboard/overview`),
      get<ProductionStats>(`/${factoryId.value}/reports/dashboard/production?period=today`)
    ]);

    const failed: string[] = [];

    if (overviewRes.status === 'fulfilled' && overviewRes.value.success) {
      overview.value = overviewRes.value.data;
    } else {
      failed.push('概览');
      console.error('[DashboardProduction] overview API failed',
        overviewRes.status === 'rejected' ? overviewRes.reason : overviewRes.value);
    }
    if (productionRes.status === 'fulfilled' && productionRes.value.success) {
      productionStats.value = productionRes.value.data;
    } else {
      failed.push('生产统计');
      console.error('[DashboardProduction] production API failed',
        productionRes.status === 'rejected' ? productionRes.reason : productionRes.value);
    }

    if (failed.length === 2) {
      ElMessage.error('Dashboard 数据全部加载失败');
    } else if (failed.length > 0) {
      ElMessage.warning(`部分数据加载失败: ${failed.join('、')}`);
    }
  } catch (error) {
    console.error('[DashboardProduction] Failed to load production data:', error);
    ElMessage.error('加载生产 Dashboard 失败');
  } finally {
    loading.value = false;
  }
}

function navigateTo(route: string) {
  router.push(route);
}
</script>

<template>
  <div class="dashboard-production" v-loading="loading">
    <!-- 欢迎区 -->
    <div class="welcome-section">
      <div class="welcome-info">
        <h1>欢迎回来，{{ authStore.user?.fullName || authStore.user?.username }}</h1>
        <p>
          <el-tag type="success" size="small">{{ authStore.roleMetadata?.displayName }}</el-tag>
          <span class="factory-info">工厂: {{ factoryId }}</span>
        </p>
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
        <span>生产管理</span>
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

    <!-- 生产概览 -->
    <el-row :gutter="20">
      <el-col :xs="24" :lg="12">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>今日生产目标</span>
              <el-button type="primary" link @click="navigateTo('/production/plans')">
                查看计划
              </el-button>
            </div>
          </template>
          <div class="target-progress">
            <div class="progress-info">
              <span>计划: {{ overview?.plannedOutput ?? 0 }} kg</span>
              <span>实际: {{ overview?.todayOutput ?? 0 }} kg</span>
            </div>
            <el-progress
              :percentage="overview?.completionRate ?? 0"
              :color="(overview?.completionRate ?? 0) >= 100 ? '#67c23a' : '#409eff'"
              :stroke-width="20"
            />
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="12">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>批次状态分布</span>
              <el-button type="primary" link @click="navigateTo('/production/batches')">
                查看批次
              </el-button>
            </div>
          </template>
          <div class="batch-status">
            <div class="status-item">
              <el-tag type="success">已完成</el-tag>
              <span class="count">{{ overview?.completedBatches ?? 0 }}</span>
            </div>
            <div class="status-item">
              <el-tag type="warning">进行中</el-tag>
              <span class="count">{{ productionStats?.inProgressBatches ?? 0 }}</span>
            </div>
            <div class="status-item">
              <el-tag type="info">待开始</el-tag>
              <span class="count">{{ productionStats?.pendingBatches ?? 0 }}</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style lang="scss" scoped>
.dashboard-production {
  min-height: calc(100vh - 144px);
}

.welcome-section {
  margin-bottom: 24px;

  h1 {
    font-size: 24px;
    color: #333;
    margin: 0 0 8px;
  }

  p {
    display: flex;
    align-items: center;
    gap: 12px;
    margin: 0;
    color: #999;
    font-size: 14px;
  }
}

.stat-cards {
  margin-bottom: 24px;

  .stat-card {
    margin-bottom: 20px;
    cursor: pointer;
    transition: transform 0.2s;

    &:hover {
      transform: translateY(-2px);
    }
  }

  .stat-content {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .stat-info {
    display: flex;
    flex-direction: column;
  }

  .stat-title {
    font-size: 14px;
    color: #999;
    margin-bottom: 8px;
  }

  .stat-value {
    font-size: 28px;
    font-weight: 600;

    small {
      font-size: 14px;
      font-weight: 400;
      margin-left: 4px;
    }
  }

  .stat-icon {
    width: 48px;
    height: 48px;
    border-radius: 8px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 24px;
  }
}

.quick-actions-card {
  margin-bottom: 24px;

  .quick-actions {
    display: flex;
    gap: 24px;
    flex-wrap: wrap;
  }

  .action-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
    padding: 16px 24px;
    border-radius: 8px;
    cursor: pointer;
    transition: background-color 0.2s;

    &:hover {
      background-color: #f5f7fa;
    }

    span {
      font-size: 14px;
      color: #606266;
    }
  }
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.target-progress {
  padding: 20px 0;

  .progress-info {
    display: flex;
    justify-content: space-between;
    margin-bottom: 16px;
    font-size: 14px;
    color: #606266;
  }
}

.batch-status {
  display: flex;
  justify-content: space-around;
  padding: 20px 0;

  .status-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;

    .count {
      font-size: 24px;
      font-weight: 600;
      color: #303133;
    }
  }
}

.el-card {
  margin-bottom: 20px;
}
</style>
