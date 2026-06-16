<script setup lang="ts">
/**
 * 管理员专属 Dashboard
 * 适用角色: factory_super_admin
 * 特点: 全模块数据 + AI 分析入口 + 系统状态
 */
import { ref, onMounted, computed } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import type { DashboardOverview, ProductionStats, QualityStats, EquipmentStats } from '@/types/api';
import {
  TrendCharts, DataLine, Timer, Warning, Box, User, Money, Setting
} from '@element-plus/icons-vue';

const router = useRouter();
const authStore = useAuthStore();

const loading = ref(false);
const overview = ref<DashboardOverview | null>(null);
const productionStats = ref<ProductionStats | null>(null);
const qualityStats = ref<QualityStats | null>(null);
const equipmentStats = ref<EquipmentStats | null>(null);

const factoryId = computed(() => authStore.factoryId);

// UX Round 5: 新工厂 onboarding — 所有 KPI 都为 0 时显示快速开始引导
const isNewFactory = computed(() => {
  const todayOutput = overview.value?.todayOutput ?? 0;
  const completedBatches = overview.value?.completedBatches ?? 0;
  const running = equipmentStats.value?.running ?? 0;
  const activeAlerts = equipmentStats.value?.activeAlerts ?? 0;
  return !loading.value
    && todayOutput === 0
    && completedBatches === 0
    && running === 0
    && activeAlerts === 0;
});
const shouldShowOnboarding = computed(() => isNewFactory.value && !authStore.isLiushanmenFactory);

const onboardingSteps = [
 { title: '上传 Excel 数据', desc: '财务/销售/采购报表导入 AI 分析', route: '/smart-bi/analysis', icon: '' },
 { title: '配置工厂模块', desc: '按业务启用/禁用功能模块', route: '/system/features', icon: '️' },
 { title: '添加员工账号', desc: '邀请团队成员登录协作', route: '/system/users', icon: '' },
];

// 统计卡片 - 管理员看全部
const statCards = computed(() => [
  {
    title: '今日产量',
    value: overview.value?.todayOutput ?? 0,
    unit: 'kg',
    icon: TrendCharts,
    color: '#2D8B57',
    route: '/production/batches'
  },
  {
    title: '完成批次',
    value: overview.value?.completedBatches ?? 0,
    unit: '个',
    icon: DataLine,
    color: '#36B37E',
    route: '/production/batches'
  },
  {
    title: '设备运行',
    value: equipmentStats.value?.running ?? 0,
    unit: '台',
    icon: Timer,
    color: '#FFAB00',
    route: '/equipment/list'
  },
  {
    title: '设备告警',
    // R42 BUG-14 + R76: dashboard/equipment doesn't have activeAlerts field;
    // dashboard/overview returns it nested at summary.activeAlerts.
    // 类型已升级到 DashboardOverview.summary?.activeAlerts (R76), 不再需要 `as any`.
    value: overview.value?.summary?.activeAlerts
      ?? overview.value?.alerts?.active
      ?? equipmentStats.value?.activeAlerts
      ?? 0,
    unit: '条',
    icon: Warning,
    color: '#FF5630',
    route: '/equipment/alerts'
  }
]);

// 快捷操作 - 管理员专属
const quickActions = [
  { title: '生产管理', icon: TrendCharts, route: '/production/batches', color: '#2D8B57' },
  { title: '仓储管理', icon: Box, route: '/warehouse/materials', color: '#36B37E' },
  { title: '人员管理', icon: User, route: '/hr/employees', color: '#FFAB00' },
  { title: '财务报表', icon: Money, route: '/finance/reports', color: '#FF5630' },
  { title: '系统设置', icon: Setting, route: '/system/settings', color: '#6B778C' },
  { title: '车间看板', icon: DataLine, route: '/analytics/production-report', color: '#6554C0' },
  { title: '人效分析', icon: User, route: '/production-analytics/efficiency', color: '#00B8D9' },
];

onMounted(async () => {
  await loadDashboardData();
});

async function loadDashboardData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const [overviewRes, productionRes, qualityRes, equipmentRes] = await Promise.allSettled([
      get<DashboardOverview>(`/${factoryId.value}/reports/dashboard/overview`),
      get<ProductionStats>(`/${factoryId.value}/reports/dashboard/production?period=today`),
      get<QualityStats>(`/${factoryId.value}/reports/dashboard/quality`),
      get<EquipmentStats>(`/${factoryId.value}/reports/dashboard/equipment`)
    ]);

    const failed: string[] = [];

    if (overviewRes.status === 'fulfilled' && overviewRes.value.success) {
      overview.value = overviewRes.value.data;
    } else {
      failed.push('概览');
      console.error('[DashboardAdmin] overview API failed',
        overviewRes.status === 'rejected' ? overviewRes.reason : overviewRes.value);
    }
    if (productionRes.status === 'fulfilled' && productionRes.value.success) {
      productionStats.value = productionRes.value.data;
    } else {
      failed.push('生产');
      console.error('[DashboardAdmin] production API failed',
        productionRes.status === 'rejected' ? productionRes.reason : productionRes.value);
    }
    if (qualityRes.status === 'fulfilled' && qualityRes.value.success) {
      qualityStats.value = qualityRes.value.data;
    } else {
      failed.push('质量');
      console.error('[DashboardAdmin] quality API failed',
        qualityRes.status === 'rejected' ? qualityRes.reason : qualityRes.value);
    }
    if (equipmentRes.status === 'fulfilled' && equipmentRes.value.success) {
      equipmentStats.value = equipmentRes.value.data;
    } else {
      failed.push('设备');
      console.error('[DashboardAdmin] equipment API failed',
        equipmentRes.status === 'rejected' ? equipmentRes.reason : equipmentRes.value);
    }

    if (failed.length === 4) {
      ElMessage.error('Dashboard 数据全部加载失败,请检查网络或联系管理员');
    } else if (failed.length > 0) {
      ElMessage.warning(`部分数据加载失败: ${failed.join('、')}`);
    }
  } catch (error) {
    console.error('[DashboardAdmin] Failed to load dashboard data:', error);
    ElMessage.error('加载 Dashboard 失败');
  } finally {
    loading.value = false;
  }
}

function navigateTo(route: string) {
  router.push(route);
}
</script>

<template>
  <div class="dashboard-admin" v-loading="loading">
    <!-- 欢迎区 -->
    <div class="welcome-section">
      <div class="welcome-info">
        <h1>欢迎回来，{{ authStore.user?.fullName || authStore.user?.username }}</h1>
        <p>
          <el-tag type="danger" size="small">超级管理员</el-tag>
          <span class="factory-info">工厂: {{ factoryId }}</span>
        </p>
      </div>
      <div class="ai-entry">
        <el-button type="primary" @click="navigateTo('/finance/costs')">
          <el-icon><TrendCharts /></el-icon>
          AI 成本分析
        </el-button>
      </div>
    </div>

    <!-- UX Round 5: 新工厂 onboarding 引导卡 (仅当所有 KPI=0 时显示) -->
    <el-card v-if="shouldShowOnboarding" class="onboarding-card" shadow="always">
      <div class="onboarding-header">
 <span class="onboarding-icon"></span>
        <div>
          <h3>快速开始 3 步, 激活您的 AI 工厂</h3>
          <p>当前工厂暂无数据。跟着这 3 步上手, 几分钟见 AI 分析效果.</p>
        </div>
      </div>
      <div class="onboarding-steps">
        <div v-for="(step, idx) in onboardingSteps" :key="step.route" class="onboarding-step" @click="navigateTo(step.route)">
          <div class="step-num">{{ idx + 1 }}</div>
          <div class="step-icon">{{ step.icon }}</div>
          <div class="step-body">
            <div class="step-title">{{ step.title }}</div>
            <div class="step-desc">{{ step.desc }}</div>
          </div>
          <el-icon class="step-arrow">→</el-icon>
        </div>
      </div>
    </el-card>

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

    <!-- 数据概览 -->
    <el-row :gutter="20" class="overview-section">
      <el-col :xs="24" :lg="12">
        <el-card>
          <template #header>
            <span>今日生产概览</span>
          </template>
          <div class="overview-content">
            <div class="overview-item">
              <span class="label">计划产量</span>
              <span class="value">{{ overview?.plannedOutput ?? '-' }} kg</span>
            </div>
            <div class="overview-item">
              <span class="label">实际产量</span>
              <span class="value">{{ overview?.todayOutput ?? '-' }} kg</span>
            </div>
            <div class="overview-item">
              <span class="label">完成率</span>
              <span class="value" :class="{ 'text-success': (overview?.completionRate ?? 0) >= 100 }">
                {{ overview?.completionRate ?? '-' }}%
              </span>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="12">
        <el-card>
          <template #header>
            <span>质量统计</span>
          </template>
          <div class="overview-content">
            <div class="overview-item">
              <span class="label">今日检验</span>
              <span class="value">{{ qualityStats?.todayInspections ?? '-' }} 批</span>
            </div>
            <div class="overview-item">
              <span class="label">合格率</span>
              <span class="value text-success">{{ qualityStats?.passRate ?? '-' }}%</span>
            </div>
            <div class="overview-item">
              <span class="label">不合格批次</span>
              <span class="value" :class="{ 'text-danger': (qualityStats?.failedBatches ?? 0) > 0 }">
                {{ qualityStats?.failedBatches ?? '-' }} 批
              </span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style lang="scss" scoped>
@import './dashboard-shared.scss';

.dashboard-admin {
  min-height: calc(100vh - 144px);
}

.overview-section {
  .overview-content {
    .overview-item {
      border-bottom-style: solid;
    }
  }
}

/* UX Round 5: 新工厂 onboarding 卡片 */
.onboarding-card {
  margin-bottom: 20px;
  background: linear-gradient(135deg, #e8f4ff 0%, #f0f9ff 100%);
  border: 1px solid #1B65A8;

  .onboarding-header {
    display: flex;
    align-items: center;
    gap: 16px;
    margin-bottom: 16px;

    .onboarding-icon { font-size: 36px; }
    h3 { margin: 0 0 4px; color: #1B65A8; }
    p { margin: 0; color: #606266; font-size: 13px; }
  }

  .onboarding-steps {
    display: grid;
    grid-template-columns: 1fr 1fr 1fr;
    gap: 12px;

    @media (max-width: 900px) {
      grid-template-columns: 1fr;
    }
  }

  .onboarding-step {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 14px 16px;
    background: #fff;
    border-radius: 8px;
    cursor: pointer;
    transition: all 0.2s;
    border: 1px solid #e6ebf2;

    &:hover {
      transform: translateY(-2px);
      box-shadow: 0 4px 12px rgba(27, 101, 168, 0.15);
      border-color: #1B65A8;

      .step-arrow { color: #1B65A8; transform: translateX(4px); }
    }

    .step-num {
      width: 24px;
      height: 24px;
      border-radius: 50%;
      background: #1B65A8;
      color: #fff;
      font-size: 12px;
      font-weight: 600;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }
    .step-icon { font-size: 22px; }
    .step-body {
      flex: 1;
      min-width: 0;
    }
    .step-title { font-size: 14px; font-weight: 600; color: #303133; }
    .step-desc { font-size: 12px; color: #909399; margin-top: 2px; }
    .step-arrow { color: #c0c4cc; transition: all 0.2s; }
  }
}
</style>
