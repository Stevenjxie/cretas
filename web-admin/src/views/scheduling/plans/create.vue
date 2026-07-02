<script setup lang="ts">
/**
 * 创建调度计划页面
 * 支持手动创建和AI自动生成
 */
import { ref, onMounted, computed } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import {
  createSchedulingPlan,
  generateSchedule,
  getProductionLines
} from '@/api/scheduling';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  ArrowLeft,
  MagicStick,
  Plus,
  Delete
} from '@element-plus/icons-vue';
import type { TableRow } from '@/types/api';

const router = useRouter();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const loading = ref(false);
const batches = ref<TableRow[]>([]);
const productionLines = ref<TableRow[]>([]);

// 表单数据
const form = ref({
  planDate: new Date().toISOString().split('T')[0],
  selectedBatchIds: [] as number[],
  autoAssignWorkers: true,
  notes: ''
});

// AI生成选项
const aiOptions = ref({
  optimizationGoal: 'balanced' as 'minimize_cost' | 'maximize_efficiency' | 'balanced',
  considerTemporaryWorkers: true,
  minCompletionProbability: 0.7
});

const useAI = ref(false);

onMounted(() => {
  loadBatches();
  loadProductionLines();
});

// 尚未开工、可被排入调度计划的批次状态。ProductionBatchStatus 枚举没有 PENDING
// (旧代码传 status=PENDING 触发 IllegalArgumentException → 400, 批次列表恒为空,
// 排程计划页面无法创建, module-verify 2026-07-03 抓出)。PLANNED 是当前值,
// PLANNING 是历史遗留同义值 (见 ProductionBatchStatus.java 注释), 两者都算"待调度"。
const SCHEDULABLE_STATUSES = new Set(['PLANNED', 'PLANNING']);

async function loadBatches() {
  if (!factoryId.value) return;

  try {
    // 加载待调度的生产批次。后端单个 status 参数不支持传多值, 这里不传 status
    // 拉全量后前端过滤 PLANNED/PLANNING, 避免漏掉历史 PLANNING 数据。
    // Bug fix (sidebar audit): 后端 @Min(1) 要求 page>=1, 之前 page:0 触发 400 "Page index must not be less than zero"
    const response = await get(`/${factoryId.value}/processing/batches`, {
      params: {
        page: 1,
        size: 200
      }
    });
    if (response.success && response.data) {
      const content = (response.data.content || []) as TableRow[];
      batches.value = content.filter((b) => SCHEDULABLE_STATUSES.has(String(b.status || '').toUpperCase()));
    }
  } catch (error) {
    console.error('加载批次失败:', error);
  }
}

async function loadProductionLines() {
  if (!factoryId.value) return;

  try {
    const response = await getProductionLines(factoryId.value, 'active');
    if (response.success && response.data) {
      productionLines.value = response.data;
    }
  } catch (error) {
    console.error('加载产线失败:', error);
  }
}

function goBack() {
  router.push('/scheduling/plans');
}

function toggleBatchSelection(batchId: number) {
  const index = form.value.selectedBatchIds.indexOf(batchId);
  if (index > -1) {
    form.value.selectedBatchIds.splice(index, 1);
  } else {
    form.value.selectedBatchIds.push(batchId);
  }
}

function isBatchSelected(batchId: number) {
  return form.value.selectedBatchIds.includes(batchId);
}

function selectAllBatches() {
  form.value.selectedBatchIds = batches.value.map(b => b.id);
}

function clearSelection() {
  form.value.selectedBatchIds = [];
}

async function handleSubmit() {
  if (form.value.selectedBatchIds.length === 0) {
    ElMessage.warning('请选择至少一个生产批次');
    return;
  }

  try {
    loading.value = true;

    let response;
    if (useAI.value) {
      // AI 生成
      response = await generateSchedule(factoryId.value!, {
        planDate: form.value.planDate,
        batchIds: form.value.selectedBatchIds,
        optimizationGoal: aiOptions.value.optimizationGoal,
        considerTemporaryWorkers: aiOptions.value.considerTemporaryWorkers,
        minCompletionProbability: aiOptions.value.minCompletionProbability
      });
    } else {
      // 手动创建
      response = await createSchedulingPlan(factoryId.value!, {
        planDate: form.value.planDate,
        batchIds: form.value.selectedBatchIds,
        autoAssignWorkers: form.value.autoAssignWorkers,
        notes: form.value.notes
      });
    }

    if (response.success && response.data) {
      ElMessage.success(useAI.value ? 'AI调度计划生成成功' : '调度计划创建成功');
      router.push(`/scheduling/plans/${response.data.id}`);
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('创建失败:', error);
  } finally {
    loading.value = false;
  }
}

function getOptimizationLabel(goal: string) {
  const map: Record<string, string> = {
    minimize_cost: '最小化成本',
    maximize_efficiency: '最大化效率',
    balanced: '平衡模式'
  };
  return map[goal] || goal;
}
</script>

<template>
  <div class="create-plan-page">
    <!-- 页面头部 -->
    <div class="page-header">
      <div class="header-left">
        <el-button :icon="ArrowLeft" @click="goBack">返回</el-button>
        <h1>创建调度计划</h1>
      </div>
    </div>

    <div class="content-wrapper">
      <!-- 基本信息 -->
      <el-card class="form-card">
        <template #header>
          <span>基本信息</span>
        </template>
        <el-form label-width="120px">
          <el-form-item label="计划日期">
            <el-date-picker
              v-model="form.planDate"
              type="date"
              placeholder="选择日期"
              value-format="YYYY-MM-DD"
              :disabled-date="(time: Date) => time < new Date(Date.now() - 86400000)"
            />
          </el-form-item>
          <el-form-item label="备注">
            <el-input
              v-model="form.notes"
              type="textarea"
              :rows="2"
              placeholder="可选备注信息"
            />
          </el-form-item>
        </el-form>
      </el-card>

      <!-- 生成方式 -->
      <el-card class="mode-card">
        <template #header>
          <span>生成方式</span>
        </template>
        <div class="mode-selection">
          <div
            class="mode-option"
            :class="{ active: !useAI }"
            @click="useAI = false"
          >
            <el-icon class="mode-icon"><Plus /></el-icon>
            <div class="mode-info">
              <div class="mode-title">手动创建</div>
              <div class="mode-desc">选择批次后系统按顺序排程</div>
            </div>
          </div>
          <div
            class="mode-option"
            :class="{ active: useAI }"
            @click="useAI = true"
          >
            <el-icon class="mode-icon ai"><MagicStick /></el-icon>
            <div class="mode-info">
              <div class="mode-title">AI 智能生成</div>
              <div class="mode-desc">AI优化排程顺序和人员分配</div>
            </div>
          </div>
        </div>

        <!-- AI 选项 -->
        <div v-if="useAI" class="ai-options">
          <el-form label-width="140px">
            <el-form-item label="优化目标">
              <el-radio-group v-model="aiOptions.optimizationGoal">
                <el-radio-button value="minimize_cost">最小化成本</el-radio-button>
                <el-radio-button value="maximize_efficiency">最大化效率</el-radio-button>
                <el-radio-button value="balanced">平衡模式</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="考虑临时工">
              <el-switch v-model="aiOptions.considerTemporaryWorkers" />
            </el-form-item>
            <el-form-item label="最低完成概率">
              <el-slider
                v-model="aiOptions.minCompletionProbability"
                :min="0.5"
                :max="0.95"
                :step="0.05"
                :format-tooltip="(val: number) => (val * 100).toFixed(0) + '%'"
              />
            </el-form-item>
          </el-form>
        </div>

        <!-- 手动选项 -->
        <div v-else class="manual-options">
          <el-form label-width="140px">
            <el-form-item label="自动分配工人">
              <el-switch v-model="form.autoAssignWorkers" />
              <span class="option-hint">开启后系统将自动分配可用工人</span>
            </el-form-item>
          </el-form>
        </div>
      </el-card>

      <!-- 选择批次 -->
      <el-card class="batch-card">
        <template #header>
          <div class="card-header">
            <span>选择生产批次</span>
            <div class="header-actions">
              <el-button type="primary" link @click="selectAllBatches">全选</el-button>
              <el-button type="danger" link @click="clearSelection">清空</el-button>
            </div>
          </div>
        </template>

        <div class="batch-list" v-loading="loading">
          <div
            v-for="batch in batches"
            :key="batch.id"
            class="batch-item"
            :class="{ selected: isBatchSelected(batch.id) }"
            @click="toggleBatchSelection(batch.id)"
          >
            <el-checkbox :model-value="isBatchSelected(batch.id)" @click.stop />
            <div class="batch-info">
              <div class="batch-number">{{ batch.batchNumber }}</div>
              <div class="batch-product">{{ batch.productTypeName }}</div>
            </div>
            <div class="batch-quantity">
              <span class="qty-value">{{ batch.plannedQuantity }}</span>
              <span class="qty-label">计划数量</span>
            </div>
          </div>
          <el-empty v-if="batches.length === 0" description="暂无待调度的生产批次" />
        </div>

        <div class="selection-summary">
          已选择 <strong>{{ form.selectedBatchIds.length }}</strong> 个批次
        </div>
      </el-card>

      <!-- 提交按钮 -->
      <div class="submit-area">
        <el-button @click="goBack">取消</el-button>
        <el-button
          type="primary"
          :loading="loading"
          :icon="useAI ? MagicStick : Plus"
          @click="handleSubmit"
        >
          {{ useAI ? 'AI 生成调度计划' : '创建调度计划' }}
        </el-button>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.create-plan-page {
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

.content-wrapper {
  max-width: 900px;
}

.form-card,
.mode-card,
.batch-card {
  margin-bottom: 20px;
}

.mode-selection {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.mode-option {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
  border: 2px solid #dcdfe6;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    border-color: #409EFF;
  }

  &.active {
    border-color: #409EFF;
    background: rgba(64, 158, 255, 0.05);
  }

  .mode-icon {
    font-size: 32px;
    color: #909399;

    &.ai {
      color: #E6A23C;
    }
  }

  .mode-info {
    .mode-title {
      font-size: 16px;
      font-weight: 600;
      color: #303133;
      margin-bottom: 4px;
    }

    .mode-desc {
      font-size: 12px;
      color: #909399;
    }
  }
}

.ai-options,
.manual-options {
  padding-top: 20px;
  border-top: 1px solid #ebeef5;
}

.option-hint {
  margin-left: 12px;
  font-size: 12px;
  color: #909399;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.batch-list {
  max-height: 400px;
  overflow-y: auto;
}

.batch-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 16px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    background: #f5f7fa;
  }

  &.selected {
    border-color: #409EFF;
    background: rgba(64, 158, 255, 0.05);
  }

  .batch-info {
    flex: 1;

    .batch-number {
      font-weight: 600;
      color: #303133;
    }

    .batch-product {
      font-size: 12px;
      color: #909399;
    }
  }

  .batch-quantity {
    text-align: right;

    .qty-value {
      font-size: 18px;
      font-weight: 600;
      color: #303133;
    }

    .qty-label {
      display: block;
      font-size: 12px;
      color: #909399;
    }
  }
}

.selection-summary {
  padding: 12px;
  text-align: center;
  color: #606266;
  border-top: 1px solid #ebeef5;
  margin-top: 16px;

  strong {
    color: #409EFF;
    font-size: 18px;
  }
}

.submit-area {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 20px;
}
</style>
