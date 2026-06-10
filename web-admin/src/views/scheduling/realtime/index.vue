<script setup lang="ts">
/**
 * 实时监控大屏
 * 显示当前生产状态、进度、概率、告警
 */
import { ref, onMounted, computed, onUnmounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import {
  getSchedulingDashboard,
  getSchedulingPlans,
  getRealtimeMonitor,
  SchedulingDashboard,
  SchedulingPlan
} from '@/api/scheduling';
import { ElMessage } from 'element-plus';
import { Refresh, Warning, User, Timer, TrendCharts } from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
// chart-insight: BAR (progress → RANKING) + PIE (probability → PROPORTION)
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue';
import { useChartInsight } from '@/composables/useChartInsight';
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const loading = ref(false);
const dashboard = ref<SchedulingDashboard | null>(null);
const todayPlans = ref<SchedulingPlan[]>([]);
const selectedPlanId = ref<string | null>(null);
const refreshInterval = ref<number | null>(null);
const lastUpdated = ref<Date | null>(null);

// 图表
const progressChart = ref<echarts.ECharts | null>(null);
const probabilityChart = ref<echarts.ECharts | null>(null);
const progressContainer = ref<HTMLElement | null>(null);
const probabilityContainer = ref<HTMLElement | null>(null);

// chart-insight reactive sources (updated whenever chart data changes)
const progressChartData = ref<ChartWithMeta | null>(null);   // BAR → RANKING
const probabilityChartData = ref<ChartWithMeta | null>(null); // PIE → PROPORTION

// perms: scheduling realtime has no finance metrics (quantity/pct domain)
const schedulePerms = { canViewFinance: false };

const { insight: progressInsight, loading: progressInsightLoading } = useChartInsight(
  () => progressChartData.value ? { chart: progressChartData.value } : null,
  () => schedulePerms,
  { factoryId: () => factoryId.value ?? '' },
);

const { insight: probabilityInsight, loading: probabilityInsightLoading } = useChartInsight(
  () => probabilityChartData.value ? { chart: probabilityChartData.value } : null,
  () => schedulePerms,
  { factoryId: () => factoryId.value ?? '' },
);

onMounted(async () => {
  await loadPlans();
  initCharts();
  // 每15秒刷新
  refreshInterval.value = window.setInterval(() => {
    loadData(true);
  }, 15000);
});

let resizeRaf = 0;
function handleResize() {
  if (resizeRaf) return;
  resizeRaf = requestAnimationFrame(() => {
    resizeRaf = 0;
    progressChart.value?.resize();
    probabilityChart.value?.resize();
  });
}

let resizeObserver: ResizeObserver | null = null;

onUnmounted(() => {
  resizeObserver?.disconnect();
  if (resizeRaf) cancelAnimationFrame(resizeRaf);
  if (refreshInterval.value) {
    clearInterval(refreshInterval.value);
  }
  window.removeEventListener('resize', handleResize);
  progressChart.value?.dispose();
  probabilityChart.value?.dispose();
});

async function loadPlans() {
  if (!factoryId.value) return;

  try {
    const today = new Date().toISOString().split('T')[0];
    const response = await getSchedulingPlans(factoryId.value, {
      startDate: today,
      endDate: today,
      page: 1,
      size: 10
    });

    if (response.success && response.data) {
      todayPlans.value = response.data.content || [];
      // 默认选择第一个进行中的计划
      const inProgressPlan = todayPlans.value.find(p => p.status === 'in_progress' || p.status === 'confirmed');
      if (inProgressPlan) {
        selectedPlanId.value = inProgressPlan.id;
        await loadData();
      }
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载计划失败');
    }
  } catch (error: any) {
    console.error('加载计划失败:', error);
    if (!error?.actionHint) ElMessage.error('加载计划列表失败');
  }
}

async function loadData(silent = false) {
  if (!factoryId.value) return;

  if (!silent) {
    loading.value = true;
  }

  try {
    let response;
    if (selectedPlanId.value) {
      response = await getRealtimeMonitor(factoryId.value, selectedPlanId.value);
    } else {
      response = await getSchedulingDashboard(factoryId.value);
    }

    if (response.success && response.data) {
      dashboard.value = response.data;
      updateCharts();
    }
    lastUpdated.value = new Date();
  } catch (error: any) {
    console.error('加载数据失败:', error);
    if (!silent) {
      if (!error?.actionHint) ElMessage.error('加载数据失败');
    }
  } finally {
    loading.value = false;
  }
}

function initCharts() {
  if (progressContainer.value) {
    progressChart.value = echarts.init(progressContainer.value, 'cretas');
  }
  if (probabilityContainer.value) {
    probabilityChart.value = echarts.init(probabilityContainer.value, 'cretas');
  }

  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(handleResize);
    if (progressContainer.value) resizeObserver.observe(progressContainer.value);
    if (probabilityContainer.value) resizeObserver.observe(probabilityContainer.value);
  }
  window.addEventListener('resize', handleResize);
}

function updateCharts() {
  updateProgressChart();
  updateProbabilityChart();
}

function updateProgressChart() {
  if (!progressChart.value || !dashboard.value?.todaySchedules) return;

  const schedules = dashboard.value.todaySchedules;
  const data = schedules.map(s => ({
    name: s.batchNumber,
    value: s.completionRate || 0,
    itemStyle: {
      color: s.status === 'completed' ? '#67C23A' :
             s.status === 'in_progress' ? '#E6A23C' :
             s.status === 'delayed' ? '#F56C6C' : '#409EFF'
    }
  }));

  const option: echarts.EChartsOption = {
    title: {
      text: '排程进度',
      left: 'center',
      textStyle: { fontSize: 14 }
    },
    tooltip: {
      trigger: 'axis',
      confine: true,
      axisPointer: { type: 'shadow' }
    },
    grid: {
      left: 100,
      right: 40,
      top: 40,
      bottom: 20
    },
    xAxis: {
      type: 'value',
      max: 100,
      axisLabel: { formatter: '{value}%' }
    },
    yAxis: {
      type: 'category',
      data: data.map(d => d.name),
      axisLabel: { width: 80, overflow: 'truncate' }
    },
    series: [{
      type: 'bar',
      data: data,
      barWidth: 20,
      label: {
        show: true,
        position: 'right',
        formatter: '{c}%'
      }
    }]
  };

  progressChart.value.setOption(option);

  // Update insight source: BAR (horizontal progress) → xDim:category (batch names), yMetric:pct
  progressChartData.value = {
    chartType: 'bar',
    title: '排程进度',
    meta: { xDim: 'category', yMetric: 'pct', aggregation: 'sum', domain: 'factory' },
    config: {
      xAxis: { data: data.map(d => d.name) },
      series: [{ type: 'bar', data: data.map(d => d.value) }],
    },
  };
}

function updateProbabilityChart() {
  if (!probabilityChart.value || !dashboard.value?.todaySchedules) return;

  const schedules = dashboard.value.todaySchedules;
  const data = schedules.map(s => ({
    value: (s.predictedCompletionProb || 0) * 100,
    name: s.batchNumber,
    itemStyle: {
      color: (s.predictedCompletionProb || 0) >= 0.9 ? '#67C23A' :
             (s.predictedCompletionProb || 0) >= 0.7 ? '#E6A23C' :
             (s.predictedCompletionProb || 0) >= 0.5 ? '#F56C6C' : '#909399'
    }
  }));

  const option: echarts.EChartsOption = {
    title: {
      text: '完成概率分布',
      left: 'center',
      textStyle: { fontSize: 14 }
    },
    tooltip: {
      trigger: 'item',
      confine: true,
      formatter: '{b}: {c}%'
    },
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      avoidLabelOverlap: false,
      label: {
        show: true,
        formatter: '{b}\n{c}%'
      },
      data: data
    }]
  };

  probabilityChart.value.setOption(option);

  // Update insight source: PIE → PROPORTION family
  probabilityChartData.value = {
    chartType: 'pie',
    title: '完成概率分布',
    meta: { xDim: 'category', yMetric: 'pct', aggregation: 'avg', domain: 'factory' },
    config: {
      series: [{
        type: 'pie',
        data: data.map(d => ({ name: d.name, value: d.value })),
      }],
    },
  };
}

function handlePlanChange() {
  loadData();
}

function getStatusColor(status: string) {
  const map: Record<string, string> = {
    pending: '#909399',
    in_progress: '#E6A23C',
    completed: '#67C23A',
    delayed: '#F56C6C'
  };
  return map[status] || '#909399';
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    pending: '待开始',
    in_progress: '进行中',
    completed: '已完成',
    delayed: '已延期'
  };
  return map[status] || status;
}

function formatTime(time: string) {
  if (!time) return '-';
  return new Date(time).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
}

function formatProbability(prob: number) {
  if (prob === null || prob === undefined) return '-';
  return (prob * 100).toFixed(0) + '%';
}

function getProbabilityColor(prob: number) {
  if (prob >= 0.9) return '#67C23A';
  if (prob >= 0.7) return '#E6A23C';
  if (prob >= 0.5) return '#F56C6C';
  return '#909399';
}
</script>

<template>
  <div class="realtime-monitor" v-loading="loading">
    <!-- 顶部控制栏 -->
    <div class="control-bar">
      <div class="bar-left">
        <h1>实时监控</h1>
        <el-select
          v-model="selectedPlanId"
          placeholder="选择调度计划"
          style="width: 200px"
          @change="handlePlanChange"
        >
          <el-option
            v-for="plan in todayPlans"
            :key="plan.id"
            :label="`${plan.planDate} (${plan.status})`"
            :value="plan.id"
          />
        </el-select>
      </div>
      <div class="bar-right">
        <span v-if="lastUpdated" class="last-updated">
          更新于 {{ formatTime(lastUpdated.toISOString()) }}
        </span>
        <el-button :icon="Refresh" circle @click="loadData(false)" />
      </div>
    </div>

    <!-- 统计卡片 -->
    <div class="stats-row" v-if="dashboard">
      <el-card class="stat-card">
        <div class="stat-content">
          <el-icon class="stat-icon" style="color: var(--el-color-primary)"><Timer /></el-icon>
          <div class="stat-info">
            <div class="stat-value">{{ dashboard.todaySchedules?.length || 0 }}</div>
            <div class="stat-label">今日排程</div>
          </div>
        </div>
      </el-card>
      <el-card class="stat-card">
        <div class="stat-content">
          <el-icon class="stat-icon" style="color: var(--el-color-success)"><User /></el-icon>
          <div class="stat-info">
            <div class="stat-value">{{ dashboard.workerStats?.checkedIn || 0 }}</div>
            <div class="stat-label">在岗工人</div>
          </div>
        </div>
      </el-card>
      <el-card class="stat-card">
        <div class="stat-content">
          <el-icon class="stat-icon" style="color: var(--el-color-warning)"><TrendCharts /></el-icon>
          <div class="stat-info">
            <div class="stat-value">{{ ((dashboard.lineStats?.utilizationRate || 0) * 100).toFixed(0) }}%</div>
            <div class="stat-label">产线利用率</div>
          </div>
        </div>
      </el-card>
      <el-card class="stat-card" :class="{ warning: dashboard.alerts?.length > 0 }">
        <div class="stat-content">
          <el-icon class="stat-icon" :style="{ color: dashboard.alerts?.length > 0 ? 'var(--el-color-danger)' : 'var(--el-color-info)' }">
            <Warning />
          </el-icon>
          <div class="stat-info">
            <div class="stat-value">{{ dashboard.alerts?.length || 0 }}</div>
            <div class="stat-label">活跃告警</div>
          </div>
        </div>
      </el-card>
    </div>

    <!-- 图表区域 -->
    <div class="charts-row">
      <el-card class="chart-card">
        <div ref="progressContainer" class="chart-container"></div>
        <!-- BAR progress → RANKING insight (canViewFinance:false, no finance metrics) -->
        <ChartInsight :insight="progressInsight" :loading="progressInsightLoading" depth="concise" />
      </el-card>
      <el-card class="chart-card">
        <div ref="probabilityContainer" class="chart-container"></div>
        <!-- PIE probability → PROPORTION insight -->
        <ChartInsight :insight="probabilityInsight" :loading="probabilityInsightLoading" depth="concise" />
      </el-card>
    </div>

    <!-- 排程详情 -->
    <el-card class="schedule-detail-card">
      <template #header>
        <span>排程详情</span>
      </template>
      <div class="schedule-grid" v-if="dashboard?.todaySchedules?.length">
        <div
          v-for="schedule in dashboard.todaySchedules"
          :key="schedule.id"
          class="schedule-tile"
          :style="{ borderLeftColor: getStatusColor(schedule.status) }"
        >
          <div class="tile-header">
            <span class="batch-number">{{ schedule.batchNumber }}</span>
            <el-tag :color="getStatusColor(schedule.status)" size="small" effect="dark">
              {{ getStatusText(schedule.status) }}
            </el-tag>
          </div>
          <div class="tile-line">{{ schedule.productionLineName }}</div>
          <div class="tile-time">
            {{ formatTime(schedule.plannedStartTime) }} - {{ formatTime(schedule.plannedEndTime) }}
          </div>
          <div class="tile-progress">
            <el-progress
              :percentage="schedule.completionRate || 0"
              :stroke-width="8"
              :show-text="false"
            />
            <span class="progress-text">{{ schedule.completedQuantity || 0 }}/{{ schedule.targetQuantity }}</span>
          </div>
          <div class="tile-footer">
            <div class="probability" :style="{ color: getProbabilityColor(schedule.predictedCompletionProb) }">
              {{ formatProbability(schedule.predictedCompletionProb) }}
            </div>
            <div class="workers">
              <el-icon><User /></el-icon>
              {{ schedule.assignedWorkerCount || 0 }}
            </div>
          </div>
        </div>
      </div>
      <el-empty v-else description="暂无排程数据" />
    </el-card>

    <!-- 告警列表 -->
    <el-card class="alerts-card" v-if="dashboard?.alerts?.length">
      <template #header>
        <div class="card-header">
          <span>活跃告警</span>
          <el-tag type="danger">{{ dashboard.alerts.length }}</el-tag>
        </div>
      </template>
      <div class="alert-list">
        <div
          v-for="alert in dashboard.alerts"
          :key="alert.id"
          class="alert-item"
          :class="alert.severity"
        >
          <el-icon class="alert-icon"><Warning /></el-icon>
          <div class="alert-content">
            <div class="alert-message">{{ alert.message }}</div>
            <div class="alert-action" v-if="alert.suggestedAction">{{ alert.suggestedAction }}</div>
          </div>
          <div class="alert-time">{{ formatTime(alert.createdAt) }}</div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.realtime-monitor {
  padding: 20px;
  background: #1a1a2e;
  min-height: 100vh;
  color: #fff;
}

.control-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;

  .bar-left {
    display: flex;
    align-items: center;
    gap: 16px;

    h1 {
      font-size: 24px;
      font-weight: 600;
      margin: 0;
    }
  }

  .bar-right {
    display: flex;
    align-items: center;
    gap: 16px;

    .last-updated {
      font-size: 12px;
      color: #909399;
    }
  }
}

.stats-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
  margin-bottom: 20px;
}

.stat-card {
  background: #16213e;
  border: none;

  &.warning {
    animation: pulse 2s infinite;
  }

  :deep(.el-card__body) {
    padding: 20px;
  }
}

.stat-content {
  display: flex;
  align-items: center;
  gap: 16px;

  .stat-icon {
    font-size: 40px;
  }

  .stat-info {
    .stat-value {
      font-size: 32px;
      font-weight: 700;
      color: #fff;
    }

    .stat-label {
      font-size: 12px;
      color: #909399;
    }
  }
}

@keyframes pulse {
  0%, 100% {
    box-shadow: 0 0 0 0 rgba(245, 108, 108, 0.4);
  }
  50% {
    box-shadow: 0 0 0 10px rgba(245, 108, 108, 0);
  }
}

.charts-row {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 20px;
  margin-bottom: 20px;
}

.chart-card {
  background: #16213e;
  border: none;

  :deep(.el-card__body) {
    padding: 16px;
  }
}

.chart-container {
  height: 300px;
}

.schedule-detail-card {
  background: #16213e;
  border: none;
  margin-bottom: 20px;

  :deep(.el-card__header) {
    color: #fff;
    border-bottom: 1px solid #3a3f5c;
  }
}

.schedule-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.schedule-tile {
  background: #1f2940;
  border-radius: 8px;
  padding: 16px;
  border-left: 4px solid;

  .tile-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 8px;

    .batch-number {
      font-weight: 600;
      font-size: 16px;
    }
  }

  .tile-line {
    font-size: 14px;
    color: #909399;
    margin-bottom: 4px;
  }

  .tile-time {
    font-size: 12px;
    color: #606266;
    margin-bottom: 12px;
  }

  .tile-progress {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 12px;

    .el-progress {
      flex: 1;
    }

    .progress-text {
      font-size: 12px;
      color: #909399;
    }
  }

  .tile-footer {
    display: flex;
    justify-content: space-between;
    align-items: center;

    .probability {
      font-size: 20px;
      font-weight: 700;
    }

    .workers {
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 14px;
      color: #909399;
    }
  }
}

.alerts-card {
  background: #16213e;
  border: none;

  :deep(.el-card__header) {
    color: #fff;
    border-bottom: 1px solid #3a3f5c;

    .card-header {
      display: flex;
      align-items: center;
      gap: 8px;
    }
  }
}

.alert-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.alert-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px;
  background: #1f2940;
  border-radius: 8px;
  border-left: 4px solid;

  &.critical {
    border-left-color: #F56C6C;
  }

  &.warning {
    border-left-color: #E6A23C;
  }

  &.info {
    border-left-color: #409EFF;
  }

  .alert-icon {
    font-size: 20px;
    color: #F56C6C;
  }

  .alert-content {
    flex: 1;

    .alert-message {
      font-size: 14px;
      color: #fff;
      margin-bottom: 4px;
    }

    .alert-action {
      font-size: 12px;
      color: #E6A23C;
    }
  }

  .alert-time {
    font-size: 12px;
    color: #606266;
  }
}
</style>
