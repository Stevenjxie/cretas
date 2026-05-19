<!--
  Canvas-Pricing Phase 4b — 价格策略 Tab 容器 (2026-05-18).

  3 sub-tabs:
    1. 策略列表 (StrategiesList)    — 按 5 种类型分组 + 启用 toggle + 编辑/删除
    2. 模拟器   (SimulatorTab)      — 输入场景, 调用 /simulate, 显示 finalPrice + warnings
    3. 应用日志 (LogsList)          — 审计 audit log, 按 SO line 过滤

  Per spec §5: 嵌入 Canvas Editor 模块为新 Tab "价格策略".
-->
<template>
  <div class="pricing-strategy-tab">
    <div class="panel-header">
      <h3>价格策略</h3>
      <div class="panel-actions">
        <el-button type="primary" size="small" @click="openCreateDialog">
          + 新建策略
        </el-button>
      </div>
    </div>

    <el-tabs v-model="activeSubTab" class="sub-tabs">
      <el-tab-pane label="策略列表" name="strategies">
        <StrategiesList
          :factory-id="factoryId"
          :strategies="strategies"
          :loading="loading"
          @edit="openEditDialog"
          @toggle="onToggleStrategy"
          @delete="onDeleteStrategy"
          @reload="loadStrategies"
        />
      </el-tab-pane>

      <el-tab-pane label="模拟计算" name="simulator">
        <SimulatorTab :factory-id="factoryId" />
      </el-tab-pane>

      <el-tab-pane label="应用日志" name="logs">
        <LogsList :factory-id="factoryId" />
      </el-tab-pane>
    </el-tabs>

    <!-- Create/Edit Form Dialog -->
    <StrategyFormDialog
      v-if="formDialogVisible"
      :visible="formDialogVisible"
      :factory-id="factoryId"
      :edit-target="editTarget"
      @close="formDialogVisible = false"
      @saved="onStrategySaved"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import StrategiesList from './components/StrategiesList.vue';
import StrategyFormDialog from './components/StrategyFormDialog.vue';
import SimulatorTab from './components/SimulatorTab.vue';
import LogsList from './components/LogsList.vue';
import {
  listStrategies,
  toggleStrategy,
  deleteStrategy,
  type PricingStrategy,
} from '@/api/pricingStrategyApi';

const props = defineProps<{
  factoryId: string;
}>();

const activeSubTab = ref<'strategies' | 'simulator' | 'logs'>('strategies');
const strategies = ref<PricingStrategy[]>([]);
const loading = ref(false);

const formDialogVisible = ref(false);
const editTarget = ref<PricingStrategy | null>(null);

async function loadStrategies() {
  if (!props.factoryId) return;
  loading.value = true;
  try {
    strategies.value = await listStrategies(props.factoryId);
  } catch (e) {
    // axios interceptor already showed error toast
    console.error('Failed to load pricing strategies:', e);
  } finally {
    loading.value = false;
  }
}

function openCreateDialog() {
  editTarget.value = null;
  formDialogVisible.value = true;
}

function openEditDialog(strategy: PricingStrategy) {
  editTarget.value = strategy;
  formDialogVisible.value = true;
}

async function onToggleStrategy(strategy: PricingStrategy) {
  try {
    const updated = await toggleStrategy(props.factoryId, strategy.id);
    ElMessage.success(updated.enabled ? '策略已启用' : '策略已禁用');
    await loadStrategies();
  } catch (e) {
    console.error('Toggle failed:', e);
  }
}

async function onDeleteStrategy(strategy: PricingStrategy) {
  try {
    await ElMessageBox.confirm(
      `确认删除策略 "${strategy.strategyName || strategy.strategyCode}"?`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    );
  } catch {
    return; // user cancelled
  }
  try {
    await deleteStrategy(props.factoryId, strategy.id);
    ElMessage.success('策略已删除');
    await loadStrategies();
  } catch (e) {
    console.error('Delete failed:', e);
  }
}

function onStrategySaved() {
  formDialogVisible.value = false;
  loadStrategies();
}

watch(() => props.factoryId, () => {
  if (props.factoryId) loadStrategies();
});

onMounted(() => {
  if (props.factoryId) loadStrategies();
});
</script>

<style scoped>
.pricing-strategy-tab {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 12px;
  gap: 12px;
}
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.panel-header h3 {
  margin: 0;
  font-size: 16px;
  color: var(--el-text-color-primary);
}
.panel-actions {
  display: flex;
  gap: 8px;
}
.sub-tabs {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.sub-tabs :deep(.el-tabs__content) {
  flex: 1;
  overflow: auto;
}
</style>
