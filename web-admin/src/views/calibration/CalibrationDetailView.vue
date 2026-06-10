<script setup lang="ts">
/**
 * 行为校准管理 - 详情页
 * 展示校准会话详情、结果、历史记录等
 */
import { ref, onMounted, computed, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import {
  getCalibrationSession,
  updateCalibrationSessionStatus,
  executeCalibrationEvaluation,
  getCalibrationHistory,
  getToolReliabilityRanking
} from '@/api/calibration';
import type {
  CalibrationSession,
  CalibrationHistoryItem,
  ToolReliabilityItem
} from '@/types/calibration';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  ArrowLeft,
  Refresh,
  Timer,
  Check,
  Close,
  Warning,
  CircleCheck,
  TrendCharts,
  Histogram,
  Document,
  InfoFilled
} from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
// chart-insight: LINE trend → TREND; RADAR is exotic → SKIP
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue';
import { useChartInsight } from '@/composables/useChartInsight';
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

const sessionId = computed(() => route.params.id as string);

// ==================== 状态定义 ====================

const loading = ref(false);
const session = ref<CalibrationSession | null>(null);
const historyItems = ref<CalibrationHistoryItem[]>([]);
const toolReliability = ref<ToolReliabilityItem[]>([]);

// 图表
const radarChart = ref<echarts.ECharts | null>(null);
const radarContainer = ref<HTMLElement | null>(null);
const trendChart = ref<echarts.ECharts | null>(null);
const trendContainer = ref<HTMLElement | null>(null);

// chart-insight: LINE trend → TREND (RADAR = exotic radar → SKIP)
// Data source updated by updateTrendChart when real data arrives.
const trendChartData = ref<ChartWithMeta | null>(null);

const calibrationPerms = { canViewFinance: false };

const { insight: trendInsight, loading: trendInsightLoading } = useChartInsight(
  () => trendChartData.value ? { chart: trendChartData.value } : null,
  () => calibrationPerms,
  { factoryId: () => authStore.factoryId ?? '' },
);

// 自动刷新
const refreshInterval = ref<number | null>(null);

// ==================== 生命周期 ====================

onMounted(async () => {
  await loadData();
  initCharts();

  // 如果会话进行中，每30秒刷新一次
  if (session.value?.status === 'in_progress') {
    refreshInterval.value = window.setInterval(() => {
      loadData(true);
    }, 30000);
  }
});

onUnmounted(() => {
  resizeObserver?.disconnect();
  if (resizeRaf) cancelAnimationFrame(resizeRaf);
  window.removeEventListener('resize', handleResize);
  if (refreshInterval.value) {
    clearInterval(refreshInterval.value);
  }
  if (radarChart.value) {
    radarChart.value.dispose();
  }
  if (trendChart.value) {
    trendChart.value.dispose();
  }
});

// ==================== 数据加载 ====================

async function loadData(silent = false) {
  if (!sessionId.value) return;

  if (!silent) {
    loading.value = true;
  }

  try {
    const [sessionRes, historyRes, reliabilityRes] = await Promise.all([
      getCalibrationSession(sessionId.value),
      getCalibrationHistory({ sessionId: sessionId.value, page: 0, size: 20 }),
      getToolReliabilityRanking(authStore.factoryId, 10)
    ]);

    // Apr 21 2026: drop generateMock fallback. Users saw fake calibration
    // sessions and treated them as real. Prefer empty state.
    if (sessionRes.success && sessionRes.data) {
      session.value = sessionRes.data;
    } else {
      session.value = null as never;
    }

    if (historyRes.success && historyRes.data) {
      historyItems.value = historyRes.data.content || [];
    } else {
      historyItems.value = [];
    }

    if (reliabilityRes.success && reliabilityRes.data) {
      toolReliability.value = reliabilityRes.data;
    }

    // 更新图表
    updateRadarChart();
    updateTrendChart();
  } catch (error) {
    console.error('加载数据失败:', error);
    if (!silent) {
      ElMessage.error('加载数据失败');
    }
    session.value = null as never;
    historyItems.value = [];
  } finally {
    loading.value = false;
  }
}

function generateMockSession(): CalibrationSession {
  return {
    id: sessionId.value,
    sessionName: '行为校准会话 #1',
    sessionType: 'manual',
    status: 'completed',
    factoryId: authStore.factoryId,
    factoryName: authStore.factoryId || '未知工厂',
    description: '针对 ET-Agent 的行为校准，优化工具调用效率和准确性',
    targetMetrics: ['conciseness', 'successRate', 'efficiency'],
    startTime: new Date(Date.now() - 7200000).toISOString(),
    endTime: new Date(Date.now() - 3600000).toISOString(),
    duration: 3600,
    progress: 100,
    results: {
      overallScore: 87.5,
      concisenessScore: 92.3,
      successRateScore: 97.1,
      efficiencyScore: 85.6,
      improvement: 8.5,
      issues: [
        {
          type: 'efficiency',
          severity: 'medium',
          description: '部分工具调用存在冗余',
          affectedTools: ['inventory_query', 'material_batch_query'],
          suggestedAction: '优化调用链路，减少不必要的中间查询'
        },
        {
          type: 'conciseness',
          severity: 'low',
          description: '输出内容偶尔包含不必要的解释',
          suggestedAction: '精简输出模板，提供更直接的答案'
        }
      ],
      recommendations: [
        '建议每周进行一次行为校准',
        '关注效率指标的持续改进',
        '增加对异常情况的处理能力训练'
      ]
    },
    createdBy: '1',
    createdByName: '张三',
    createdAt: new Date(Date.now() - 86400000).toISOString(),
    updatedAt: new Date(Date.now() - 3600000).toISOString()
  };
}

function generateMockHistory(): CalibrationHistoryItem[] {
  return [
    {
      id: '1',
      sessionId: sessionId.value,
      sessionName: '行为校准会话 #1',
      action: '会话完成',
      details: '校准会话已完成，综合得分 87.5',
      performedBy: '1',
      performedByName: '系统',
      performedAt: new Date(Date.now() - 3600000).toISOString()
    },
    {
      id: '2',
      sessionId: sessionId.value,
      sessionName: '行为校准会话 #1',
      action: '执行评估',
      details: '开始执行行为评估...',
      performedBy: '1',
      performedByName: '张三',
      performedAt: new Date(Date.now() - 5400000).toISOString()
    },
    {
      id: '3',
      sessionId: sessionId.value,
      sessionName: '行为校准会话 #1',
      action: '会话开始',
      details: '校准会话已开始',
      performedBy: '1',
      performedByName: '张三',
      performedAt: new Date(Date.now() - 7200000).toISOString()
    },
    {
      id: '4',
      sessionId: sessionId.value,
      sessionName: '行为校准会话 #1',
      action: '会话创建',
      details: '创建了新的校准会话',
      performedBy: '1',
      performedByName: '张三',
      performedAt: new Date(Date.now() - 86400000).toISOString()
    }
  ];
}

// ==================== 图表初始化 ====================

function initCharts() {
  // 雷达图
  if (radarContainer.value) {
    radarChart.value = echarts.init(radarContainer.value, 'cretas');
    updateRadarChart();
  }

  // 趋势图
  if (trendContainer.value) {
    trendChart.value = echarts.init(trendContainer.value, 'cretas');
    updateTrendChart();
  }

  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(handleResize);
    if (radarContainer.value) resizeObserver.observe(radarContainer.value);
    if (trendContainer.value) resizeObserver.observe(trendContainer.value);
  }
  window.addEventListener('resize', handleResize);
}

let resizeObserver: ResizeObserver | null = null;
let resizeRaf = 0;
function handleResize() {
  if (resizeRaf) return;
  resizeRaf = requestAnimationFrame(() => {
    resizeRaf = 0;
    radarChart.value?.resize();
    trendChart.value?.resize();
  });
}

function updateRadarChart() {
  if (!radarChart.value || !session.value?.results) return;

  const results = session.value.results;
  const option: echarts.EChartsOption = {
    tooltip: {
      trigger: 'item',
      confine: true
    },
    radar: {
      indicator: [
        { name: '简洁性', max: 100 },
        { name: '成功率', max: 100 },
        { name: '效率', max: 100 },
        { name: '综合得分', max: 100 }
      ],
      shape: 'polygon',
      splitNumber: 5,
      axisName: {
        color: '#303133',
        fontSize: 12
      },
      splitArea: {
        areaStyle: {
          color: ['rgba(64, 158, 255, 0.05)', 'rgba(64, 158, 255, 0.1)']
        }
      }
    },
    series: [{
      type: 'radar',
      data: [{
        value: [
          results.concisenessScore,
          results.successRateScore,
          results.efficiencyScore,
          results.overallScore
        ],
        name: '当前得分',
        areaStyle: {
          color: 'rgba(64, 158, 255, 0.3)'
        },
        lineStyle: {
          color: '#409EFF',
          width: 2
        },
        itemStyle: {
          color: '#409EFF'
        }
      }]
    }]
  };

  radarChart.value.setOption(option);
}

function updateTrendChart() {
  if (!trendChart.value) return;

  // R76: 之前用 75 + Math.random() * 20 编造 7 天趋势 — 已移除。
  // CalibrationHistoryItem 是 audit log 不含 score 字段, 后端无每日评分趋势 API。
  // 在专属 trend 接口接入前, 显示空状态而不是假折线。
  trendChart.value.setOption({
    title: {
      text: '7天趋势数据待接入',
      subtext: '后端尚未提供每日评分趋势接口',
      left: 'center',
      top: 'center',
      textStyle: { color: '#909399', fontSize: 14, fontWeight: 'normal' },
      subtextStyle: { color: '#C0C4CC', fontSize: 12 }
    }
  }, true);

  // chart-insight LINE source: no real data yet (empty state).
  // trendChartData stays null → useChartInsight returns null → ChartInsight renders nothing.
  // When a real trend API is wired, set trendChartData here with xDim:'time'.
  trendChartData.value = null;
}

// ==================== 事件处理 ====================

function goBack() {
  router.push('/calibration');
}

async function handleStart() {
  if (!session.value) return;

  try {
    await ElMessageBox.confirm(
      '确认要开始此校准会话吗？',
      '开始校准',
      { type: 'info' }
    );

    loading.value = true;
    const response = await updateCalibrationSessionStatus(session.value.id, 'in_progress');
    if (response.success) {
      ElMessage.success('会话已开始');
      loadData();

      // 开始自动刷新
      if (!refreshInterval.value) {
        refreshInterval.value = window.setInterval(() => {
          loadData(true);
        }, 30000);
      }
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    if (error !== 'cancel') console.error('操作失败:', error);
  } finally {
    loading.value = false;
  }
}

async function handleEvaluate() {
  if (!session.value) return;

  try {
    await ElMessageBox.confirm(
      '确认要执行校准评估吗？这可能需要几分钟时间。',
      '执行评估',
      { type: 'warning' }
    );

    loading.value = true;
    const response = await executeCalibrationEvaluation(session.value.id);
    if (response.success) {
      ElMessage.success('评估已开始');
      loadData();
    }
  } catch (error) {
    if (error !== 'cancel') {
      console.error('评估失败:', error);
      ElMessage.error('评估失败');
    }
  } finally {
    loading.value = false;
  }
}

async function handleComplete() {
  if (!session.value) return;

  try {
    await ElMessageBox.confirm(
      '确认要完成此校准会话吗？',
      '完成校准',
      { type: 'warning' }
    );

    loading.value = true;
    const response = await updateCalibrationSessionStatus(session.value.id, 'completed');
    if (response.success) {
      ElMessage.success('会话已完成');

      // 停止自动刷新
      if (refreshInterval.value) {
        clearInterval(refreshInterval.value);
        refreshInterval.value = null;
      }

      loadData();
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    if (error !== 'cancel') console.error('操作失败:', error);
  } finally {
    loading.value = false;
  }
}

async function handleCancel() {
  if (!session.value) return;

  try {
    await ElMessageBox.confirm(
      '确认要取消此校准会话吗？',
      '取消校准',
      { type: 'warning' }
    );

    loading.value = true;
    const response = await updateCalibrationSessionStatus(session.value.id, 'cancelled');
    if (response.success) {
      ElMessage.success('会话已取消');

      // 停止自动刷新
      if (refreshInterval.value) {
        clearInterval(refreshInterval.value);
        refreshInterval.value = null;
      }

      loadData();
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    if (error !== 'cancel') console.error('操作失败:', error);
  } finally {
    loading.value = false;
  }
}

// ==================== 辅助函数 ====================

function getStatusType(status: string) {
  const map: Record<string, string> = {
    pending: 'info',
    in_progress: 'warning',
    completed: 'success',
    cancelled: '',
    failed: 'danger'
  };
  return map[status] || 'info';
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    pending: '待开始',
    in_progress: '进行中',
    completed: '已完成',
    cancelled: '已取消',
    failed: '失败'
  };
  return map[status] || status;
}

function getSessionTypeText(type: string) {
  const map: Record<string, string> = {
    manual: '手动',
    auto: '自动',
    scheduled: '定时'
  };
  return map[type] || type;
}

function formatDuration(seconds?: number) {
  if (!seconds) return '-';
  const minutes = Math.floor(seconds / 60);
  const secs = seconds % 60;
  if (minutes < 60) {
    return `${minutes}分${secs}秒`;
  }
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  return `${hours}时${mins}分`;
}

function formatDate(dateStr?: string) {
  if (!dateStr) return '-';
  return new Date(dateStr).toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  });
}

function getScoreColor(score?: number) {
  if (!score) return '#909399';
  if (score >= 90) return '#67C23A';
  if (score >= 70) return '#E6A23C';
  if (score >= 50) return '#F56C6C';
  return '#909399';
}

function getSeverityType(severity: string) {
  const map: Record<string, string> = {
    low: 'info',
    medium: 'warning',
    high: 'danger',
    critical: 'danger'
  };
  return map[severity] || 'info';
}

function getSeverityText(severity: string) {
  const map: Record<string, string> = {
    low: '低',
    medium: '中',
    high: '高',
    critical: '严重'
  };
  return map[severity] || severity;
}
</script>

<template>
  <div class="detail-page" v-loading="loading">
    <!-- 页面头部 -->
    <div class="page-header">
      <div class="header-left">
        <el-button :icon="ArrowLeft" @click="goBack">返回</el-button>
        <h1>{{ session?.sessionName || '校准会话详情' }}</h1>
        <el-tag v-if="session" :type="getStatusType(session.status)" size="large">
          {{ getStatusText(session.status) }}
        </el-tag>
      </div>
      <div class="header-right">
        <el-button :icon="Refresh" @click="loadData(false)">刷新</el-button>
        <el-button
          v-if="session?.status === 'pending'"
          type="primary"
          :icon="Check"
          @click="handleStart"
        >
          开始校准
        </el-button>
        <el-button
          v-if="session?.status === 'in_progress'"
          type="warning"
          :icon="TrendCharts"
          @click="handleEvaluate"
        >
          执行评估
        </el-button>
        <el-button
          v-if="session?.status === 'in_progress'"
          type="success"
          :icon="Check"
          @click="handleComplete"
        >
          完成
        </el-button>
        <el-button
          v-if="session?.status === 'pending' || session?.status === 'in_progress'"
          type="info"
          :icon="Close"
          @click="handleCancel"
        >
          取消
        </el-button>
      </div>
    </div>

    <!-- 基本信息 -->
    <div class="info-section" v-if="session">
      <el-card class="info-card">
        <template #header>
          <div class="card-title">
            <el-icon><Document /></el-icon>
            <span>基本信息</span>
          </div>
        </template>
        <el-descriptions :column="3" border>
          <el-descriptions-item label="会话ID">{{ session.id }}</el-descriptions-item>
          <el-descriptions-item label="会话类型">
            <el-tag size="small" type="info">{{ getSessionTypeText(session.sessionType) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="工厂">{{ session.factoryName || session.factoryId }}</el-descriptions-item>
          <el-descriptions-item label="创建人">{{ session.createdByName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDate(session.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ formatDuration(session.duration) }}</el-descriptions-item>
          <el-descriptions-item label="目标指标" :span="3">
            <el-tag
              v-for="metric in session.targetMetrics"
              :key="metric"
              size="small"
              style="margin-right: 8px"
            >
              {{ metric === 'conciseness' ? '简洁性' : metric === 'successRate' ? '成功率' : metric === 'efficiency' ? '效率' : metric }}
            </el-tag>
            <span v-if="!session.targetMetrics?.length">-</span>
          </el-descriptions-item>
          <el-descriptions-item label="描述" :span="3">
            {{ session.description || '-' }}
          </el-descriptions-item>
        </el-descriptions>
      </el-card>
    </div>

    <!-- 进度（进行中时显示） -->
    <el-card v-if="session?.status === 'in_progress'" class="progress-card">
      <template #header>
        <div class="card-title">
          <el-icon><Timer /></el-icon>
          <span>校准进度</span>
        </div>
      </template>
      <div class="progress-content">
        <el-progress
          :percentage="session.progress || 0"
          :stroke-width="20"
          :text-inside="true"
          status="primary"
        />
        <div class="progress-info">
          <span>正在执行校准评估...</span>
          <span>已运行: {{ formatDuration(session.duration) }}</span>
        </div>
      </div>
    </el-card>

    <!-- 评估结果 -->
    <div class="results-section" v-if="session?.results">
      <el-row :gutter="20">
        <!-- 得分卡片 -->
        <el-col :xs="24" :lg="8">
          <el-card class="score-card">
            <template #header>
              <div class="card-title">
                <el-icon><Histogram /></el-icon>
                <span>评估得分</span>
              </div>
            </template>
            <div class="score-overview">
              <div class="overall-score" :style="{ color: getScoreColor(session.results.overallScore) }">
                {{ session.results.overallScore.toFixed(1) }}
              </div>
              <div class="score-label">综合得分</div>
              <div
                class="improvement"
                :style="{ color: session.results.improvement >= 0 ? '#67C23A' : '#F56C6C' }"
              >
                {{ session.results.improvement >= 0 ? '+' : '' }}{{ session.results.improvement.toFixed(1) }}%
                <span class="improvement-label">较上次</span>
              </div>
            </div>
            <div class="score-details">
              <div class="score-item">
                <span class="score-name">简洁性</span>
                <el-progress
                  :percentage="session.results.concisenessScore"
                  :stroke-width="10"
                  :color="getScoreColor(session.results.concisenessScore)"
                />
              </div>
              <div class="score-item">
                <span class="score-name">成功率</span>
                <el-progress
                  :percentage="session.results.successRateScore"
                  :stroke-width="10"
                  :color="getScoreColor(session.results.successRateScore)"
                />
              </div>
              <div class="score-item">
                <span class="score-name">效率</span>
                <el-progress
                  :percentage="session.results.efficiencyScore"
                  :stroke-width="10"
                  :color="getScoreColor(session.results.efficiencyScore)"
                />
              </div>
            </div>
          </el-card>
        </el-col>

        <!-- 雷达图 — exotic RADAR type → chart-insight SKIP -->
        <el-col :xs="24" :lg="8">
          <el-card class="chart-card">
            <template #header>
              <div class="card-title">
                <el-icon><TrendCharts /></el-icon>
                <span>能力雷达</span>
              </div>
            </template>
            <div ref="radarContainer" class="chart-container"></div>
          </el-card>
        </el-col>

        <!-- 趋势图 — LINE → TREND insight; empty state until trend API is wired -->
        <el-col :xs="24" :lg="8">
          <el-card class="chart-card">
            <template #header>
              <div class="card-title">
                <el-icon><TrendCharts /></el-icon>
                <span>得分趋势</span>
              </div>
            </template>
            <div ref="trendContainer" class="chart-container"></div>
            <!-- chart-insight: null source while trend API pending → renders nothing (honest-null) -->
            <ChartInsight :insight="trendInsight" :loading="trendInsightLoading" depth="concise" />
          </el-card>
        </el-col>
      </el-row>
    </div>

    <!-- 发现的问题 -->
    <el-card v-if="session?.results?.issues?.length" class="issues-card">
      <template #header>
        <div class="card-title">
          <el-icon><Warning /></el-icon>
          <span>发现的问题 ({{ session.results.issues.length }})</span>
        </div>
      </template>
      <el-table :data="session.results.issues" stripe border empty-text="暂无问题记录">
        <el-table-column prop="type" label="类型" width="100" align="center">
          <template #default="{ row }">
            {{ row.type === 'efficiency' ? '效率' : row.type === 'conciseness' ? '简洁性' : row.type }}
          </template>
        </el-table-column>
        <el-table-column prop="severity" label="严重程度" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getSeverityType(row.severity)" size="small">
              {{ getSeverityText(row.severity) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="问题描述" min-width="200" />
        <el-table-column prop="affectedTools" label="影响工具" width="200">
          <template #default="{ row }">
            <el-tag
              v-for="tool in row.affectedTools"
              :key="tool"
              size="small"
              type="info"
              style="margin-right: 4px; margin-bottom: 4px"
            >
              {{ tool }}
            </el-tag>
            <span v-if="!row.affectedTools?.length">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="suggestedAction" label="建议措施" min-width="200">
          <template #default="{ row }">
            {{ row.suggestedAction || '-' }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 建议 -->
    <el-card v-if="session?.results?.recommendations?.length" class="recommendations-card">
      <template #header>
        <div class="card-title">
          <el-icon><CircleCheck /></el-icon>
          <span>改进建议</span>
        </div>
      </template>
      <div class="recommendations-list">
        <div
          v-for="(rec, index) in session.results.recommendations"
          :key="index"
          class="recommendation-item"
        >
          <el-icon class="rec-icon"><InfoFilled /></el-icon>
          <span>{{ rec }}</span>
        </div>
      </div>
    </el-card>

    <!-- 操作历史 -->
    <el-card class="history-card">
      <template #header>
        <div class="card-title">
          <el-icon><Timer /></el-icon>
          <span>操作历史</span>
        </div>
      </template>
      <el-timeline>
        <el-timeline-item
          v-for="item in historyItems"
          :key="item.id"
          :timestamp="formatDate(item.performedAt)"
          placement="top"
        >
          <div class="history-content">
            <div class="history-action">{{ item.action }}</div>
            <div class="history-details" v-if="item.details">{{ item.details }}</div>
            <div class="history-user" v-if="item.performedByName">
              操作人: {{ item.performedByName }}
            </div>
          </div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-if="!historyItems.length" description="暂无操作历史" />
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.detail-page {
  padding: 20px;
  background: #f5f7fa;
  min-height: 100%;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;

  .header-left {
    display: flex;
    align-items: center;
    gap: 16px;

    h1 {
      font-size: 20px;
      font-weight: 600;
      color: #303133;
      margin: 0;
    }
  }

  .header-right {
    display: flex;
    gap: 8px;
  }
}

.card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: #303133;

  .el-icon {
    color: #409EFF;
  }
}

.info-section {
  margin-bottom: 20px;
}

.info-card,
.progress-card,
.issues-card,
.recommendations-card,
.history-card {
  margin-bottom: 20px;
  border-radius: 8px;
}

.progress-card {
  .progress-content {
    .progress-info {
      display: flex;
      justify-content: space-between;
      margin-top: 12px;
      font-size: 13px;
      color: #909399;
    }
  }
}

.results-section {
  margin-bottom: 20px;

  .el-col {
    margin-bottom: 20px;
  }
}

.score-card {
  height: 100%;

  .score-overview {
    text-align: center;
    padding: 20px 0;
    border-bottom: 1px solid #f0f2f5;
    margin-bottom: 20px;

    .overall-score {
      font-size: 48px;
      font-weight: 700;
      line-height: 1.2;
    }

    .score-label {
      font-size: 14px;
      color: #909399;
      margin-top: 4px;
    }

    .improvement {
      margin-top: 12px;
      font-size: 18px;
      font-weight: 600;

      .improvement-label {
        font-size: 12px;
        font-weight: normal;
        color: #909399;
        margin-left: 4px;
      }
    }
  }

  .score-details {
    .score-item {
      margin-bottom: 16px;

      &:last-child {
        margin-bottom: 0;
      }

      .score-name {
        display: block;
        font-size: 13px;
        color: #606266;
        margin-bottom: 8px;
      }
    }
  }
}

.chart-card {
  height: 100%;

  .chart-container {
    height: 280px;
  }
}

.recommendations-list {
  .recommendation-item {
    display: flex;
    align-items: flex-start;
    gap: 12px;
    padding: 12px;
    background: #f5f7fa;
    border-radius: 8px;
    margin-bottom: 12px;

    &:last-child {
      margin-bottom: 0;
    }

    .rec-icon {
      color: #409EFF;
      font-size: 18px;
      flex-shrink: 0;
      margin-top: 2px;
    }
  }
}

.history-card {
  .history-content {
    .history-action {
      font-weight: 600;
      color: #303133;
      margin-bottom: 4px;
    }

    .history-details {
      font-size: 13px;
      color: #606266;
      margin-bottom: 4px;
    }

    .history-user {
      font-size: 12px;
      color: #909399;
    }
  }
}

// 响应式
@media (max-width: 768px) {
  .page-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 16px;

    .header-right {
      width: 100%;
      flex-wrap: wrap;
    }
  }
}
</style>
