<script setup lang="ts">
/**
 * 行为校准管理 - 列表页
 * 展示校准会话列表，支持创建、查看、删除等操作
 */
import { ref, onMounted, computed } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import {
  getCalibrationSessions,
  createCalibrationSession,
  updateCalibrationSessionStatus,
  deleteCalibrationSession,
  getCalibrationStatistics,
  getFactoryOptions
} from '@/api/calibration';
import type {
  CalibrationSession,
  CalibrationSessionStatus,
  CalibrationStatistics,
  FactoryOption,
  CreateCalibrationSessionRequest
} from '@/types/calibration';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  Plus,
  Search,
  Refresh,
  View,
  Delete,
  Check,
  Close,
  Timer,
  TrendCharts,
  Histogram,
  CircleCheck
} from '@element-plus/icons-vue';

const router = useRouter();
const authStore = useAuthStore();

// 判断是否为平台管理员
const isPlatformAdmin = computed(() => {
  return authStore.user?.role === 'platform_admin';
});

// 当前工厂 ID
const currentFactoryId = computed(() => {
  return selectedFactoryId.value || authStore.factoryId;
});

// ==================== 状态定义 ====================

const loading = ref(false);
const tableData = ref<CalibrationSession[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });

// 搜索表单
const searchForm = ref({
  status: '' as string,
  sessionType: '' as string,
  startDate: '',
  endDate: ''
});

// 工厂选择
const factoryOptions = ref<FactoryOption[]>([]);
const selectedFactoryId = ref<string>('');

// 统计数据
const statistics = ref<CalibrationStatistics | null>(null);

// 创建会话对话框
const createDialogVisible = ref(false);
const createFormRef = ref();
const createForm = ref<CreateCalibrationSessionRequest>({
  sessionName: '',
  sessionType: 'manual',
  description: '',
  targetMetrics: []
});
const createLoading = ref(false);

// 表单验证规则
const createFormRules = {
  sessionName: [
    { required: true, message: '请输入会话名称', trigger: 'blur' },
    { min: 2, max: 50, message: '名称长度为 2-50 个字符', trigger: 'blur' }
  ],
  sessionType: [
    { required: true, message: '请选择会话类型', trigger: 'change' }
  ]
};

// ==================== 数据加载 ====================

onMounted(async () => {
  await Promise.all([
    loadFactoryOptions(),
    loadStatistics()
  ]);
  loadData();
});

async function loadFactoryOptions() {
  if (!isPlatformAdmin.value) return;

  try {
    const response = await getFactoryOptions();
    if (response.success && response.data) {
      factoryOptions.value = response.data;
    }
  } catch (e) {
    // UX polish (2026-05-20): interceptor toast already covers 4xx/5xx; suppress
    // double toast for this background load — factory dropdown empty is a soft
    // failure (user can still operate within their default factory).
    console.error('[加载工厂选项失败]', e);
  }
}

async function loadStatistics() {
  try {
    const response = await getCalibrationStatistics(currentFactoryId.value);
    if (response.success && response.data) {
      statistics.value = response.data;
    }
  } catch (e) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', e);
  }
}

// Apr 24 2026: 后端 /sessions 端点未实现, 避免误报 toast
const featureNotImplemented = ref(false);

async function loadData() {
  loading.value = true;
  try {
    const response = await getCalibrationSessions({
      page: pagination.value.page - 1,
      size: pagination.value.size,
      status: searchForm.value.status as CalibrationSessionStatus || undefined,
      sessionType: searchForm.value.sessionType || undefined,
      startDate: searchForm.value.startDate || undefined,
      endDate: searchForm.value.endDate || undefined,
      factoryId: currentFactoryId.value
    });

    if (response.success && response.data) {
      tableData.value = response.data.content || [];
      pagination.value.total = response.data.totalElements || 0;
    }
  } catch (e) {
    // Apr 24: 404 (endpoint 未实现) 时走友好 empty state, 不弹 toast
    const err = e as { status?: number; code?: string; message?: string };
    if (err?.status === 404 || err?.code === 'NOT_FOUND' || /资源不存在|not.*found/i.test(err?.message || '')) {
      featureNotImplemented.value = true;
      tableData.value = [];
      pagination.value.total = 0;
    } else {
      console.error('[加载校准数据失败]', e);
    }
  } finally {
    loading.value = false;
  }
}

// ==================== 事件处理 ====================

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleReset() {
  searchForm.value = {
    status: '',
    sessionType: '',
    startDate: '',
    endDate: ''
  };
  handleSearch();
}

function handleFactoryChange() {
  pagination.value.page = 1;
  loadData();
  loadStatistics();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function handleSizeChange(size: number) {
  pagination.value.size = size;
  pagination.value.page = 1;
  loadData();
}

function handleView(row: CalibrationSession) {
  router.push(`/calibration/${row.id}`);
}

function handleCreate() {
  createForm.value = {
    sessionName: '',
    sessionType: 'manual',
    description: '',
    targetMetrics: []
  };
  createDialogVisible.value = true;
}

async function submitCreate() {
  if (!createFormRef.value) return;

  try {
    await createFormRef.value.validate();

    createLoading.value = true;
    const response = await createCalibrationSession({
      ...createForm.value,
      factoryId: currentFactoryId.value
    });

    if (response.success) {
      ElMessage.success('创建成功');
      createDialogVisible.value = false;
      loadData();
      loadStatistics();
    } else {
      ElMessage.error(response.message || '创建失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    if (error !== 'cancel') console.error('创建失败:', error);
  } finally {
    createLoading.value = false;
  }
}

async function handleStart(row: CalibrationSession) {
  try {
    await ElMessageBox.confirm(
      `确认要开始校准会话 "${row.sessionName}" 吗？`,
      '开始校准',
      { type: 'info' }
    );

    loading.value = true;
    const response = await updateCalibrationSessionStatus(row.id, 'in_progress');
    if (response.success) {
      ElMessage.success('会话已开始');
      loadData();
    } else {
      ElMessage.error(response.message || '操作失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    if (error !== 'cancel') console.error('操作失败:', error);
  } finally {
    loading.value = false;
  }
}

async function handleComplete(row: CalibrationSession) {
  try {
    await ElMessageBox.confirm(
      `确认要完成校准会话 "${row.sessionName}" 吗？`,
      '完成校准',
      { type: 'warning' }
    );

    loading.value = true;
    const response = await updateCalibrationSessionStatus(row.id, 'completed');
    if (response.success) {
      ElMessage.success('会话已完成');
      loadData();
      loadStatistics();
    } else {
      ElMessage.error(response.message || '操作失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    if (error !== 'cancel') console.error('操作失败:', error);
  } finally {
    loading.value = false;
  }
}

async function handleCancel(row: CalibrationSession) {
  try {
    await ElMessageBox.confirm(
      `确认要取消校准会话 "${row.sessionName}" 吗？`,
      '取消校准',
      { type: 'warning' }
    );

    loading.value = true;
    const response = await updateCalibrationSessionStatus(row.id, 'cancelled');
    if (response.success) {
      ElMessage.success('会话已取消');
      loadData();
    } else {
      ElMessage.error(response.message || '操作失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    if (error !== 'cancel') console.error('操作失败:', error);
  } finally {
    loading.value = false;
  }
}

async function handleDelete(row: CalibrationSession) {
  try {
    await ElMessageBox.confirm(
      `确认要删除校准会话 "${row.sessionName}" 吗？删除后无法恢复。`,
      '删除会话',
      { type: 'error' }
    );

    loading.value = true;
    const response = await deleteCalibrationSession(row.id);
    if (response.success) {
      ElMessage.success('删除成功');
      loadData();
      loadStatistics();
    } else {
      ElMessage.error(response.message || '删除失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    if (error !== 'cancel') console.error('删除失败:', error);
  } finally {
    loading.value = false;
  }
}

// ==================== 辅助函数 ====================

function getStatusType(status: CalibrationSessionStatus) {
  const map: Record<CalibrationSessionStatus, string> = {
    pending: 'info',
    in_progress: 'warning',
    completed: 'success',
    cancelled: '',
    failed: 'danger'
  };
  return map[status] || 'info';
}

function getStatusText(status: CalibrationSessionStatus) {
  const map: Record<CalibrationSessionStatus, string> = {
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

function getSessionTypeIcon(type: string) {
  const map: Record<string, typeof Timer> = {
    manual: View,
    auto: TrendCharts,
    scheduled: Timer
  };
  return map[type] || Timer;
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
</script>

<template>
  <div class="page-wrapper">
    <!-- 统计卡片 -->
    <div class="statistics-row" v-if="statistics">
      <el-card class="stat-card" shadow="hover">
        <div class="stat-content">
          <div class="stat-icon" style="background: linear-gradient(135deg, #409EFF, #66b1ff)">
            <el-icon><Histogram /></el-icon>
          </div>
          <div class="stat-info">
            <div class="stat-value">{{ statistics.totalSessions }}</div>
            <div class="stat-label">总会话数</div>
          </div>
        </div>
      </el-card>
      <el-card class="stat-card" shadow="hover">
        <div class="stat-content">
          <div class="stat-icon" style="background: linear-gradient(135deg, #67C23A, #85ce61)">
            <el-icon><CircleCheck /></el-icon>
          </div>
          <div class="stat-info">
            <div class="stat-value">{{ statistics.completedSessions }}</div>
            <div class="stat-label">已完成</div>
          </div>
        </div>
      </el-card>
      <el-card class="stat-card" shadow="hover">
        <div class="stat-content">
          <div class="stat-icon" style="background: linear-gradient(135deg, #E6A23C, #ebb563)">
            <el-icon><TrendCharts /></el-icon>
          </div>
          <div class="stat-info">
            <div class="stat-value">{{ statistics.averageScore?.toFixed(1) || '-' }}</div>
            <div class="stat-label">平均得分</div>
          </div>
        </div>
      </el-card>
      <el-card class="stat-card" shadow="hover">
        <div class="stat-content">
          <div class="stat-icon" style="background: linear-gradient(135deg, #F56C6C, #f78989)">
            <el-icon><Timer /></el-icon>
          </div>
          <div class="stat-info">
            <div class="stat-value" :style="{ color: statistics.averageImprovement >= 0 ? '#67C23A' : '#F56C6C' }">
              {{ statistics.averageImprovement >= 0 ? '+' : '' }}{{ statistics.averageImprovement?.toFixed(1) || '-' }}%
            </div>
            <div class="stat-label">平均改进</div>
          </div>
        </div>
      </el-card>
    </div>

    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">行为校准管理</span>
            <span class="data-count">共 {{ pagination.total }} 条记录</span>
          </div>
          <div class="header-right">
            <!-- 工厂选择器（平台管理员） -->
            <el-select
              v-if="isPlatformAdmin && factoryOptions.length > 0"
              v-model="selectedFactoryId"
              placeholder="选择工厂"
              clearable
              style="width: 180px; margin-right: 12px"
              @change="handleFactoryChange"
            >
              <el-option
                v-for="factory in factoryOptions"
                :key="factory.factoryId"
                :label="factory.name"
                :value="factory.factoryId"
              />
            </el-select>
            <el-button type="primary" :icon="Plus" @click="handleCreate">
              新建校准
            </el-button>
          </div>
        </div>
      </template>

      <!-- 搜索区域 -->
      <div class="search-bar">
        <el-select v-model="searchForm.status" placeholder="全部状态" clearable style="width: 120px">
          <el-option label="待开始" value="pending" />
          <el-option label="进行中" value="in_progress" />
          <el-option label="已完成" value="completed" />
          <el-option label="已取消" value="cancelled" />
          <el-option label="失败" value="failed" />
        </el-select>
        <el-select v-model="searchForm.sessionType" placeholder="全部类型" clearable style="width: 120px">
          <el-option label="手动" value="manual" />
          <el-option label="自动" value="auto" />
          <el-option label="定时" value="scheduled" />
        </el-select>
        <el-date-picker
          v-model="searchForm.startDate"
          type="date"
          placeholder="开始日期"
          value-format="YYYY-MM-DD"
          style="width: 150px"
        />
        <el-date-picker
          v-model="searchForm.endDate"
          type="date"
          placeholder="结束日期"
          value-format="YYYY-MM-DD"
          style="width: 150px"
        />
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleReset">重置</el-button>
      </div>

      <!-- Apr 24 2026: 后端 /sessions 未实现的 friendly empty state -->
      <el-alert v-if="featureNotImplemented" type="info" :closable="false" show-icon style="margin-bottom: 16px">
        <template #title>
          <strong>行为校准功能尚未上线</strong>
        </template>
        <template #default>
          后端 API 正在开发中。本页是前端占位, 数据接口上线后自动生效。
          如有紧急需求, 请联系平台管理员。
        </template>
      </el-alert>

      <!-- 数据表格 -->
      <el-table v-if="!featureNotImplemented" :data="tableData" v-loading="loading" empty-text="暂无数据" stripe border style="width: 100%">
        <el-table-column prop="sessionName" label="会话名称" min-width="180">
          <template #default="{ row }">
            <div class="session-name-cell">
              <el-icon :style="{ color: '#409EFF' }">
                <component :is="getSessionTypeIcon(row.sessionType)" />
              </el-icon>
              <span class="session-name">{{ row.sessionName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="sessionType" label="类型" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ getSessionTypeText(row.sessionType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="progress" label="进度" width="120" align="center">
          <template #default="{ row }">
            <el-progress
              v-if="row.status === 'in_progress'"
              :percentage="row.progress || 0"
              :stroke-width="8"
              :show-text="true"
            />
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="得分" width="100" align="center">
          <template #default="{ row }">
            <span
              v-if="row.results?.overallScore"
              :style="{ color: getScoreColor(row.results.overallScore), fontWeight: 600 }"
            >
              {{ row.results.overallScore }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="改进" width="90" align="center">
          <template #default="{ row }">
            <span
              v-if="row.results?.improvement !== undefined"
              :style="{ color: row.results.improvement >= 0 ? '#67C23A' : '#F56C6C', fontWeight: 500 }"
            >
              {{ row.results.improvement >= 0 ? '+' : '' }}{{ row.results.improvement.toFixed(1) }}%
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="duration" label="耗时" width="100" align="center">
          <template #default="{ row }">
            {{ formatDuration(row.duration) }}
          </template>
        </el-table-column>
        <el-table-column prop="createdByName" label="创建人" width="100" align="center">
          <template #default="{ row }">
            {{ row.createdByName || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="160">
          <template #default="{ row }">
            {{ formatDate(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" :icon="View" @click="handleView(row)">
              查看
            </el-button>
            <el-button
              v-if="row.status === 'pending'"
              type="success"
              link
              size="small"
              :icon="Check"
              @click="handleStart(row)"
            >
              开始
            </el-button>
            <el-button
              v-if="row.status === 'in_progress'"
              type="warning"
              link
              size="small"
              :icon="Check"
              @click="handleComplete(row)"
            >
              完成
            </el-button>
            <el-button
              v-if="row.status === 'pending' || row.status === 'in_progress'"
              type="info"
              link
              size="small"
              :icon="Close"
              @click="handleCancel(row)"
            >
              取消
            </el-button>
            <el-button
              v-if="row.status === 'completed' || row.status === 'cancelled' || row.status === 'failed'"
              type="danger"
              link
              size="small"
              :icon="Delete"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <!-- 创建会话对话框 -->
    <el-dialog
      v-model="createDialogVisible"
      title="新建校准会话"
      width="500px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createFormRules"
        label-width="100px"
      >
        <el-form-item label="会话名称" prop="sessionName">
          <el-input
            v-model="createForm.sessionName"
            placeholder="请输入会话名称"
            maxlength="50"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="会话类型" prop="sessionType">
          <el-radio-group v-model="createForm.sessionType">
            <el-radio value="manual">手动</el-radio>
            <el-radio value="auto">自动</el-radio>
            <el-radio value="scheduled">定时</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="目标指标">
          <el-checkbox-group v-model="createForm.targetMetrics">
            <el-checkbox value="conciseness">简洁性</el-checkbox>
            <el-checkbox value="successRate">成功率</el-checkbox>
            <el-checkbox value="efficiency">效率</el-checkbox>
            <el-checkbox value="accuracy">准确性</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="createForm.description"
            type="textarea"
            :rows="3"
            placeholder="请输入会话描述（可选）"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate" :loading="createLoading">
          创建
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.statistics-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;

  @media (max-width: 1200px) {
    grid-template-columns: repeat(2, 1fr);
  }

  @media (max-width: 768px) {
    grid-template-columns: 1fr;
  }
}

.stat-card {
  :deep(.el-card__body) {
    padding: 20px;
  }
}

.stat-content {
  display: flex;
  align-items: center;
  gap: 16px;

  .stat-icon {
    width: 48px;
    height: 48px;
    border-radius: 12px;
    display: flex;
    align-items: center;
    justify-content: center;

    .el-icon {
      font-size: 24px;
      color: #fff;
    }
  }

  .stat-info {
    .stat-value {
      font-size: 24px;
      font-weight: 700;
      color: #303133;
      line-height: 1.2;
    }

    .stat-label {
      font-size: 13px;
      color: #909399;
      margin-top: 4px;
    }
  }
}

.page-card {
  flex: 1;
  display: flex;
  flex-direction: column;

  :deep(.el-card__header) {
    padding: 16px 20px;
    border-bottom: 1px solid var(--border-color-lighter, #ebeef5);
  }

  :deep(.el-card__body) {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 20px;
  }
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;

  .header-left {
    display: flex;
    align-items: baseline;
    gap: 12px;

    .page-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-color-primary, #303133);
    }

    .data-count {
      font-size: 13px;
      color: var(--text-color-secondary, #909399);
    }
  }

  .header-right {
    display: flex;
    align-items: center;
  }
}

.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.el-table {
  flex: 1;
}

.session-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;

  .session-name {
    font-weight: 500;
  }
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid var(--border-color-lighter, #ebeef5);
  margin-top: 16px;
}
</style>
