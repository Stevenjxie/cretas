<script setup lang="ts">
/**
 * 人员分配页面
 * 管理工人分配到排程，支持AI优化
 */
import { ref, onMounted, computed } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import {
  getSchedulingPlans,
  getSchedulingPlan,
  assignWorkers,
  removeWorkerAssignment,
  optimizeWorkers,
  workerCheckIn,
  workerCheckOut,
  isDemoSchedulingFactory,
  SchedulingPlan,
  LineSchedule,
  WorkerAssignment
} from '@/api/scheduling';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  User,
  Plus,
  Delete,
  MagicStick,
  Clock,
  Check,
  Close
} from '@element-plus/icons-vue';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const loading = ref(false);
const plans = ref<SchedulingPlan[]>([]);
const selectedPlanId = ref<string | null>(null);
const selectedPlan = ref<SchedulingPlan | null>(null);
const availableWorkers = ref<TableRow[]>([]);

// 分配对话框
const assignDialogVisible = ref(false);
const selectedScheduleId = ref<string | null>(null);
const selectedWorkerIds = ref<number[]>([]);

// AI优化对话框
const optimizeDialogVisible = ref(false);
const optimizeOptions = ref({
  objective: 'balanced' as 'minimize_cost' | 'maximize_efficiency' | 'balanced',
  maxTemporaryRatio: 0.3,
  minSkillMatch: 0.6
});

onMounted(() => {
  loadPlans();
  loadWorkers();
});

async function loadPlans() {
  if (!factoryId.value) return;

  try {
    const response = await getSchedulingPlans(factoryId.value, {
      status: 'confirmed',
      // page is 0-based per backend (mirrors scheduling/plans/list.vue's
      // `page - 1`); literal `page: 1` was silently requesting page 2 and
      // returning empty results whenever all confirmed plans fit on page 1.
      page: 0,
      size: 50
    });
    if (response.success && response.data) {
      plans.value = response.data.content || [];
      if (!selectedPlanId.value && plans.value.length > 0) {
        selectedPlanId.value = plans.value[0].id;
        await handlePlanChange();
      }
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载计划失败');
    }
  } catch (error: any) {
    console.error('加载计划失败:', error);
    if (!error?.actionHint) ElMessage.error('加载计划列表失败');
  }
}

async function loadWorkers() {
  if (!factoryId.value) return;

  if (isDemoSchedulingFactory(factoryId.value)) {
    availableWorkers.value = [
      { id: 3101, name: '赵明', position: '卤制主操' },
      { id: 3102, name: '李蓉', position: '包装线长' },
      { id: 3103, name: '王磊', position: '包装操作' },
      { id: 3104, name: '周敏', position: '质检员' },
      { id: 4101, name: '顺达人力 / 陈师傅', position: '临时包装' },
    ];
    return;
  }

  try {
    // 加载可用工人列表
    const response = await get(`/${factoryId.value}/users`, {
      params: {
        role: 'operator',
        page: 1,
        size: 200
      }
    });
    if (response.success && response.data) {
      availableWorkers.value = response.data.content || [];
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载工人列表失败');
    }
  } catch (error: any) {
    console.error('加载工人失败:', error);
    if (!error?.actionHint) ElMessage.error('加载工人列表失败');
  }
}

async function handlePlanChange() {
  if (!selectedPlanId.value || !factoryId.value) return;

  loading.value = true;
  try {
    const response = await getSchedulingPlan(factoryId.value, selectedPlanId.value);
    if (response.success && response.data) {
      selectedPlan.value = response.data;
    }
  } catch (error: any) {
    console.error('加载计划详情失败:', error);
    if (!error?.actionHint) ElMessage.error('加载计划详情失败');
  } finally {
    loading.value = false;
  }
}

function openAssignDialog(scheduleId: string) {
  selectedScheduleId.value = scheduleId;
  selectedWorkerIds.value = [];
  assignDialogVisible.value = true;
}

async function handleAssign() {
  if (!selectedScheduleId.value || !factoryId.value) return;

  if (selectedWorkerIds.value.length === 0) {
    ElMessage.warning('请选择至少一个工人');
    return;
  }

  try {
    loading.value = true;
    const response = await assignWorkers(factoryId.value, {
      scheduleId: selectedScheduleId.value,
      userIds: selectedWorkerIds.value
    });

    if (response.success) {
      ElMessage.success('分配成功');
      assignDialogVisible.value = false;
      handlePlanChange();
    } else {
      ElMessage.error(response.message || '分配失败');
    }
  } catch (error: any) {
    console.error('分配失败:', error);
    if (!error?.actionHint) ElMessage.error('分配失败');
  } finally {
    loading.value = false;
  }
}

async function handleRemoveAssignment(assignment: WorkerAssignment) {
  try {
    await ElMessageBox.confirm(
      `确认移除 "${assignment.userName}" 的分配吗？`,
      '移除分配',
      { type: 'warning' }
    );

    loading.value = true;
    const response = await removeWorkerAssignment(factoryId.value!, assignment.id);
    if (response.success) {
      ElMessage.success('已移除');
      handlePlanChange();
    } else {
      ElMessage.error(response.message || '移除失败');
    }
  } catch (error: any) {
    if (error !== 'cancel') {
      console.error('移除失败:', error);
      if (!error?.actionHint) ElMessage.error('移除失败');
    }
  } finally {
    loading.value = false;
  }
}

async function handleCheckIn(assignment: WorkerAssignment) {
  try {
    loading.value = true;
    const response = await workerCheckIn(factoryId.value!, assignment.id);
    if (response.success) {
      ElMessage.success('签到成功');
      handlePlanChange();
    } else {
      ElMessage.error(response.message || '签到失败');
    }
  } catch (error: any) {
    console.error('签到失败:', error);
    if (!error?.actionHint) ElMessage.error('签到失败');
  } finally {
    loading.value = false;
  }
}

async function handleCheckOut(assignment: WorkerAssignment) {
  try {
    const { value: score } = await ElMessageBox.prompt(
      '请输入绩效评分 (1-100)',
      '签退',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        inputValue: '80',
        inputPattern: /^([1-9]|[1-9][0-9]|100)$/,
        inputErrorMessage: '请输入1-100之间的数字'
      }
    );

    loading.value = true;
    const response = await workerCheckOut(factoryId.value!, assignment.id, Number(score));
    if (response.success) {
      ElMessage.success('签退成功');
      handlePlanChange();
    } else {
      ElMessage.error(response.message || '签退失败');
    }
  } catch (error: any) {
    if (error !== 'cancel') {
      console.error('签退失败:', error);
      if (!error?.actionHint) ElMessage.error('签退失败');
    }
  } finally {
    loading.value = false;
  }
}

function openOptimizeDialog() {
  optimizeDialogVisible.value = true;
}

async function handleOptimize() {
  if (!selectedPlan.value || !factoryId.value) return;

  const scheduleIds = selectedPlan.value.lineSchedules
    .filter(s => s.status === 'pending' || s.status === 'in_progress')
    .map(s => s.id);

  if (scheduleIds.length === 0) {
    ElMessage.warning('没有可优化的排程');
    return;
  }

  try {
    loading.value = true;
    const response = await optimizeWorkers(factoryId.value, {
      scheduleIds,
      objective: optimizeOptions.value.objective,
      maxTemporaryRatio: optimizeOptions.value.maxTemporaryRatio,
      minSkillMatch: optimizeOptions.value.minSkillMatch
    });

    if (response.success) {
      ElMessage.success('AI优化完成');
      optimizeDialogVisible.value = false;
      handlePlanChange();
    } else {
      ElMessage.error(response.message || '优化失败');
    }
  } catch (error: any) {
    console.error('优化失败:', error);
    if (!error?.actionHint) ElMessage.error('优化失败');
  } finally {
    loading.value = false;
  }
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    assigned: 'info',
    checked_in: 'success',
    checked_out: 'default'
  };
  return map[status] || 'info';
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    assigned: '已分配',
    checked_in: '已签到',
    checked_out: '已签退'
  };
  return map[status] || status;
}
</script>

<template>
  <div class="workers-page" v-loading="loading">
    <!-- 页面头部 -->
    <div class="page-header">
      <div class="header-left">
        <h1>人员分配</h1>
        <el-select
          v-model="selectedPlanId"
          placeholder="选择调度计划"
          style="width: 250px"
          @change="handlePlanChange"
        >
          <el-option
            v-for="plan in plans"
            :key="plan.id"
            :label="`${plan.planDate} - ${plan.totalBatches}批次`"
            :value="plan.id"
          />
        </el-select>
      </div>
      <div class="header-right">
        <el-button
          type="primary"
          :icon="MagicStick"
          :disabled="!selectedPlan"
          @click="openOptimizeDialog"
        >
          AI 优化分配
        </el-button>
      </div>
    </div>

    <!-- 排程工人分配 -->
    <div class="schedules-container" v-if="selectedPlan">
      <el-card
        v-for="schedule in selectedPlan.lineSchedules"
        :key="schedule.id"
        class="schedule-card"
      >
        <template #header>
          <div class="card-header">
            <div class="header-info">
              <span class="schedule-batch">{{ schedule.batchNumber }}</span>
              <span class="schedule-line">{{ schedule.productionLineName }}</span>
            </div>
            <el-button
              type="primary"
              size="small"
              :icon="Plus"
              @click="openAssignDialog(schedule.id)"
            >
              添加工人
            </el-button>
          </div>
        </template>

        <div class="workers-list" v-if="schedule.workerAssignments?.length">
          <div
            v-for="assignment in schedule.workerAssignments"
            :key="assignment.id"
            class="worker-item"
          >
            <div class="worker-avatar">
              <el-icon><User /></el-icon>
            </div>
            <div class="worker-info">
              <div class="worker-name">
                {{ assignment.userName }}
                <el-tag v-if="assignment.isTemporary" type="warning" size="small">临时工</el-tag>
              </div>
              <div class="worker-meta">
                <span>技能等级: {{ assignment.skillLevel }}</span>
                <span v-if="assignment.workedHours">工时: {{ assignment.workedHours.toFixed(1) }}h</span>
              </div>
            </div>
            <div class="worker-status">
              <el-tag :type="getStatusType(assignment.status)" size="small">
                {{ getStatusText(assignment.status) }}
              </el-tag>
            </div>
            <div class="worker-actions">
              <template v-if="assignment.status === 'assigned'">
                <el-button type="success" size="small" circle :icon="Clock" @click="handleCheckIn(assignment)" title="签到" />
                <el-button type="danger" size="small" circle :icon="Delete" @click="handleRemoveAssignment(assignment)" title="移除" />
              </template>
              <template v-else-if="assignment.status === 'checked_in'">
                <el-button type="warning" size="small" circle :icon="Check" @click="handleCheckOut(assignment)" title="签退" />
              </template>
              <template v-else>
                <span class="performance-score" v-if="assignment.performanceScore">
                  绩效: {{ assignment.performanceScore }}
                </span>
              </template>
            </div>
          </div>
        </div>
        <el-empty v-else description="暂无分配工人" :image-size="60" />
      </el-card>
    </div>

    <el-empty v-else description="请选择调度计划" />

    <!-- 分配工人对话框 -->
    <el-dialog v-model="assignDialogVisible" title="分配工人" width="600px">
      <div class="worker-select-list">
        <el-checkbox-group v-model="selectedWorkerIds">
          <div
            v-for="worker in availableWorkers"
            :key="worker.id"
            class="worker-select-item"
          >
            <el-checkbox :value="worker.id">
              <div class="worker-select-info">
                <span class="worker-name">{{ worker.name }}</span>
                <span class="worker-role">{{ worker.position || '操作员' }}</span>
              </div>
            </el-checkbox>
          </div>
        </el-checkbox-group>
      </div>
      <template #footer>
        <el-button @click="assignDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleAssign" :loading="loading">确定分配</el-button>
      </template>
    </el-dialog>

    <!-- AI优化对话框 -->
    <el-dialog v-model="optimizeDialogVisible" title="AI 优化人员分配" width="500px">
      <el-form label-width="120px">
        <el-form-item label="优化目标">
          <el-radio-group v-model="optimizeOptions.objective">
            <el-radio value="minimize_cost">最小化成本</el-radio>
            <el-radio value="maximize_efficiency">最大化效率</el-radio>
            <el-radio value="balanced">平衡模式</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="临时工比例上限">
          <el-slider
            v-model="optimizeOptions.maxTemporaryRatio"
            :min="0"
            :max="0.5"
            :step="0.05"
            :format-tooltip="(val: number) => (val * 100).toFixed(0) + '%'"
          />
        </el-form-item>
        <el-form-item label="最低技能匹配度">
          <el-slider
            v-model="optimizeOptions.minSkillMatch"
            :min="0.4"
            :max="1"
            :step="0.05"
            :format-tooltip="(val: number) => (val * 100).toFixed(0) + '%'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="optimizeDialogVisible = false">取消</el-button>
        <el-button type="primary" :icon="MagicStick" @click="handleOptimize" :loading="loading">
          开始优化
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.workers-page {
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
}

.schedules-container {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(400px, 1fr));
  gap: 20px;
}

.schedule-card {
  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;

    .header-info {
      display: flex;
      flex-direction: column;
      gap: 4px;

      .schedule-batch {
        font-weight: 600;
        color: #303133;
      }

      .schedule-line {
        font-size: 12px;
        color: #909399;
      }
    }
  }
}

.workers-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.worker-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 8px;

  .worker-avatar {
    width: 40px;
    height: 40px;
    border-radius: 50%;
    background: #409EFF;
    display: flex;
    align-items: center;
    justify-content: center;
    color: white;
    font-size: 20px;
  }

  .worker-info {
    flex: 1;

    .worker-name {
      font-weight: 600;
      color: #303133;
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .worker-meta {
      font-size: 12px;
      color: #909399;
      display: flex;
      gap: 12px;
    }
  }

  .worker-actions {
    display: flex;
    gap: 8px;
    align-items: center;

    .performance-score {
      font-size: 12px;
      color: #67C23A;
      font-weight: 600;
    }
  }
}

.worker-select-list {
  max-height: 400px;
  overflow-y: auto;
}

.worker-select-item {
  padding: 8px 0;
  border-bottom: 1px solid #ebeef5;

  &:last-child {
    border-bottom: none;
  }

  .worker-select-info {
    display: flex;
    align-items: center;
    gap: 12px;

    .worker-name {
      font-weight: 600;
    }

    .worker-role {
      font-size: 12px;
      color: #909399;
    }
  }
}
</style>
