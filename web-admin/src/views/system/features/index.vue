<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  getFeatureConfigs, toggleModule, updateModuleConfig, initDefaultConfigs
} from '@/api/factory';
import type { FeatureConfig, ModuleConfigDetail } from '@/types/factory';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('system'));

const loading = ref(false);
const configs = ref<FeatureConfig[]>([]);
const drawerVisible = ref(false);
const saving = ref(false);
const editingModule = ref<FeatureConfig | null>(null);
const editConfig = ref<ModuleConfigDetail>({});

// Module metadata for display
const moduleIcons: Record<string, string> = {
  production: 'Operation',
  warehouse: 'Box',
  quality: 'Checked',
  procurement: 'ShoppingCart',
  sales: 'Goods',
  hr: 'User',
  equipment: 'Monitor',
  finance: 'Money',
  scheduling: 'Calendar',
  smartbi: 'TrendCharts'
};

const moduleDescriptions: Record<string, string> = {
  production: '生产批次、生产计划、BOM管理',
  warehouse: '原材料管理、出货管理、库存盘点',
  quality: '质检记录、废弃处理',
  procurement: '供应商管理、采购订单',
  sales: '客户管理、销售分析',
  hr: '员工档案、考勤、部门',
  equipment: '设备列表、维护记录、告警',
  finance: '成本分析、财务报表',
  scheduling: '调度中心、计划管理、人员分配',
  smartbi: '智能BI分析、数据可视化'
};

// Available screens per module — IDs must match RN `isScreenEnabled()` calls exactly
const moduleScreens: Record<string, Array<{ id: string; label: string }>> = {
  production: [
    { id: 'BatchManagement', label: '批次管理 (车间主管Tab)' },
    { id: 'ScheduleManagement', label: '排程管理 (车间主管首页)' },
    { id: 'EquipmentMonitoring', label: '设备监控 (车间主管Tab)' },
    { id: 'WorkerManagement', label: '人员管理 (车间主管Tab)' },
    { id: 'CostAnalysisDashboard', label: '成本分析仪表盘' },
    { id: 'CostComparison', label: '成本对比' }
  ],
  warehouse: [
    { id: 'InboundManagement', label: '入库管理 (仓储Tab)' },
    { id: 'OutboundManagement', label: '出库管理 (仓储Tab)' },
    { id: 'InventoryCheck', label: '库存盘点' },
    { id: 'AlertHandling', label: '告警处理' },
    { id: 'TempMonitoring', label: '温控监测' }
  ],
  quality: [
    { id: 'QualityInspection', label: '质检任务 (质检Tab)' },
    { id: 'QualityAnalysis', label: '质量分析 (质检Tab)' }
  ],
  procurement: [],
  sales: [],
  hr: [
    { id: 'AttendanceManagement', label: '考勤管理 (HR Tab)' },
    { id: 'WhitelistManagement', label: '入职白名单 (HR Tab)' },
    { id: 'NewHireTracking', label: '新员工追踪 (HR首页)' }
  ],
  equipment: [
    { id: 'EquipmentMonitoring', label: '设备监控' }
  ],
  finance: [
    { id: 'CostAnalysisDashboard', label: '成本分析仪表盘' }
  ],
  scheduling: [
    { id: 'ProductionPlanning', label: '生产计划 (调度Tab)' },
    { id: 'AISchedule', label: 'AI智能调度 (调度Tab+首页)' },
    { id: 'PersonnelManagement', label: '人员管理 (调度Tab)' },
    { id: 'AICompletionProb', label: 'AI完工概率 (调度首页)' },
    { id: 'WorkshopStatus', label: '车间状态 (调度首页)' },
    { id: 'PersonnelTransfer', label: '人员调配 (调度首页)' },
    { id: 'AIWorkerOptimize', label: 'AI人员优化 (调度首页)' }
  ],
  smartbi: [
    { id: 'SmartBI', label: '智能BI (工厂管理/调度Tab)' },
    { id: 'DataAnalysis', label: '数据分析 (SmartBI首页)' },
    { id: 'ExcelUpload', label: 'Excel上传 (SmartBI首页)' },
    { id: 'NLQuery', label: 'AI自然语言查询 (SmartBI首页)' },
    { id: 'AIAnalysis', label: 'AI分析 (工厂管理Tab)' },
    { id: 'Reports', label: '报表 (工厂管理Tab)' }
  ]
};

// Available quick actions per module — IDs must match RN getEnabledQuickActions() checks
const moduleQuickActions: Record<string, Array<{ id: string; label: string }>> = {
  hr: [
    { id: 'new-hires', label: '新员工入职' }
  ]
};

// Report types controlled by isReportEnabled()
const moduleReportTypes: Record<string, Array<{ id: string; label: string }>> = {
  production: [
    { id: 'production', label: '生产报表' }
  ],
  quality: [
    { id: 'quality', label: '质量报表' }
  ],
  hr: [
    { id: 'efficiency', label: '人效报表' },
    { id: 'personnel', label: '人事报表' }
  ],
  finance: [
    { id: 'cost', label: '成本报表' }
  ]
};

onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const response = await getFeatureConfigs(factoryId.value);
    if (response.success && response.data) {
      configs.value = response.data;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载功能配置失败');
    }
  } catch (error) {
    console.error('加载功能配置失败:', error);
    ElMessage.error('加载功能配置失败');
  } finally {
    loading.value = false;
  }
}

async function handleInitDefaults() {
  if (!factoryId.value) return;
  try {
    await ElMessageBox.confirm(
      '将为当前工厂初始化所有默认功能模块配置，已有配置不会被覆盖。',
      '初始化默认配置',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'info' }
    );
    loading.value = true;
    const response = await initDefaultConfigs(factoryId.value);
    if (response.success && response.data) {
      configs.value = response.data;
      ElMessage.success('默认配置初始化成功');
    } else if (response.success === false) {
      ElMessage.error(response.message || '初始化失败');
    }
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('初始化失败');
    }
  } finally {
    loading.value = false;
  }
}

async function handleToggle(config: FeatureConfig) {
  if (!factoryId.value || !canWrite.value) return;
  try {
    const response = await toggleModule(factoryId.value, config.moduleId, config.enabled);
    if (response.success) {
      ElMessage.success(`${config.moduleName} 已${config.enabled ? '启用' : '禁用'}`);
    } else {
      config.enabled = !config.enabled;
      ElMessage.error(response.message || '操作失败');
    }
  } catch (error) {
    // Revert on failure
    config.enabled = !config.enabled;
    // Interceptor already shows specific toast for ApiError
  }
}

function openConfigDrawer(config: FeatureConfig) {
  editingModule.value = config;
  editConfig.value = { ...config.config };
  // Ensure arrays exist
  if (!editConfig.value.disabledScreens) editConfig.value.disabledScreens = [];
  if (!editConfig.value.quickActions) editConfig.value.quickActions = [];
  if (!editConfig.value.analysisDimensions) editConfig.value.analysisDimensions = [];
  if (!editConfig.value.disabledReports) editConfig.value.disabledReports = [];
  drawerVisible.value = true;
}

async function saveConfig() {
  if (!factoryId.value || !editingModule.value) return;
  saving.value = true;
  try {
    const response = await updateModuleConfig(
      factoryId.value,
      editingModule.value.moduleId,
      editConfig.value
    );
    if (response.success) {
      // Update local state
      const idx = configs.value.findIndex(c => c.moduleId === editingModule.value!.moduleId);
      if (idx !== -1) {
        configs.value[idx].config = { ...editConfig.value };
      }
      ElMessage.success('配置已保存');
      drawerVisible.value = false;
    } else {
      ElMessage.error(response.message || '保存失败');
    }
  } catch (error) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', error);
  } finally {
    saving.value = false;
  }
}

function getModuleColor(moduleId: string): string {
  const colors: Record<string, string> = {
    production: '#409EFF',
    warehouse: '#67C23A',
    quality: '#E6A23C',
    procurement: '#F56C6C',
    sales: '#909399',
    hr: '#b37feb',
    equipment: '#36cfc9',
    finance: '#ff85c0',
    scheduling: '#ffc53d',
    smartbi: '#597ef7'
  };
  return colors[moduleId] || '#409EFF';
}
</script>

<template>
  <div class="page-container">
    <!-- Header -->
    <el-card class="header-card">
      <div class="header-row">
        <div>
          <h2 class="page-title">功能模块配置</h2>
          <p class="page-desc">管理工厂功能模块的启用状态和详细配置。RN App 登录后将自动加载最新配置。</p>
        </div>
        <div class="header-actions">
          <el-tag type="info" size="large">工厂: {{ factoryId }}</el-tag>
          <el-button
            v-if="canWrite && configs.length === 0"
            type="primary"
            @click="handleInitDefaults"
            :loading="loading"
          >
            初始化默认配置
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- Module Cards Grid -->
    <div v-loading="loading" class="module-grid">
      <el-card
        v-for="config in configs"
        :key="config.moduleId"
        class="module-card"
        :class="{ 'is-disabled': !config.enabled }"
        shadow="hover"
      >
        <div class="module-header">
          <div class="module-info">
            <div
              class="module-icon"
              :style="{ backgroundColor: config.enabled ? getModuleColor(config.moduleId) : '#dcdfe6' }"
            >
              {{ config.moduleName.charAt(0) }}
            </div>
            <div>
              <h3 class="module-name">{{ config.moduleName }}</h3>
              <p class="module-desc">{{ moduleDescriptions[config.moduleId] || '' }}</p>
            </div>
          </div>
          <el-switch
            v-model="config.enabled"
            :disabled="!canWrite"
            @change="handleToggle(config)"
            active-text="启用"
            inactive-text="禁用"
          />
        </div>

        <el-divider />

        <div class="module-stats">
          <div class="stat-item">
            <span class="stat-label">禁用屏幕</span>
            <el-tag size="small" :type="(config.config?.disabledScreens?.length || 0) > 0 ? 'warning' : 'success'">
              {{ config.config?.disabledScreens?.length || 0 }}
            </el-tag>
          </div>
          <div class="stat-item">
            <span class="stat-label">快捷操作</span>
            <el-tag size="small" type="info">
              {{ config.config?.quickActions?.length || 0 }}
            </el-tag>
          </div>
        </div>

        <el-button
          v-if="canWrite"
          type="primary"
          text
          class="config-btn"
          @click="openConfigDrawer(config)"
        >
          配置详情 &rarr;
        </el-button>
      </el-card>

      <!-- Empty state -->
      <el-empty
        v-if="!loading && configs.length === 0"
        description="暂无功能配置，请点击「初始化默认配置」"
        class="empty-state"
      />
    </div>

    <!-- Config Drawer -->
    <el-drawer
      v-model="drawerVisible"
      :title="`${editingModule?.moduleName || ''} — 详细配置`"
      size="480px"
      direction="rtl"
    >
      <div v-if="editingModule" class="drawer-content">
        <!-- Disabled Screens -->
        <div
          v-if="moduleScreens[editingModule.moduleId]"
          class="config-section"
        >
          <h4>禁用的屏幕</h4>
          <p class="section-hint">勾选的屏幕将在 RN App 中隐藏</p>
          <el-checkbox-group v-model="editConfig.disabledScreens">
            <el-checkbox
              v-for="screen in moduleScreens[editingModule.moduleId]"
              :key="screen.id"
              :label="screen.id"
              :value="screen.id"
            >
              {{ screen.label }}
            </el-checkbox>
          </el-checkbox-group>
        </div>

        <!-- Quick Actions -->
        <div
          v-if="moduleQuickActions[editingModule.moduleId]?.length"
          class="config-section"
        >
          <h4>快捷操作</h4>
          <p class="section-hint">选中的操作将出现在 RN App 首页快捷入口</p>
          <el-checkbox-group v-model="editConfig.quickActions">
            <el-checkbox
              v-for="action in moduleQuickActions[editingModule.moduleId]"
              :key="action.id"
              :label="action.id"
              :value="action.id"
            >
              {{ action.label }}
            </el-checkbox>
          </el-checkbox-group>
        </div>

        <!-- Report Types (disabledReports) -->
        <div
          v-if="moduleReportTypes[editingModule.moduleId]?.length"
          class="config-section"
        >
          <h4>禁用的报表类型</h4>
          <p class="section-hint">勾选的报表将在 RN App 报表中心隐藏</p>
          <el-checkbox-group v-model="editConfig.disabledReports">
            <el-checkbox
              v-for="report in moduleReportTypes[editingModule.moduleId]"
              :key="report.id"
              :label="report.id"
              :value="report.id"
            >
              {{ report.label }}
            </el-checkbox>
          </el-checkbox-group>
        </div>

        <!-- Production Mode (only for 'production' module) -->
        <div
          v-if="editingModule.moduleId === 'production'"
          class="config-section"
        >
          <h4>生产模式</h4>
          <p class="section-hint">选择工厂的生产组织模式，影响签到/报工/任务的绑定维度</p>
          <el-radio-group v-model="editConfig.mode" class="mode-radio-group">
            <el-radio value="BATCH">
              <div class="mode-option">
                <strong>批次模式 (BATCH)</strong>
                <span class="mode-desc">默认 — 按批次组织签到和报工</span>
              </div>
            </el-radio>
            <el-radio value="PROCESS">
              <div class="mode-option">
                <strong>工序模式 (PROCESS)</strong>
                <span class="mode-desc">按工序任务组织，支持累计报工和审批</span>
              </div>
            </el-radio>
            <el-radio value="ORDER" disabled>
              <div class="mode-option">
                <strong>订单模式 (ORDER)</strong>
                <span class="mode-desc">按客户订单排产（暂未开放）</span>
              </div>
            </el-radio>
          </el-radio-group>

          <template v-if="editConfig.mode === 'PROCESS'">
            <el-divider />
            <h4>工序模式配置</h4>
            <el-form label-width="120px" size="small">
              <el-form-item label="累计报工">
                <el-switch v-model="editConfig.cumulativeReporting" />
              </el-form-item>
              <el-form-item label="完成规则">
                <el-select v-model="editConfig.completionRule" style="width: 100%">
                  <el-option value="manual_with_prompt" label="达标后手动确认" />
                  <el-option value="auto" label="自动完成" />
                  <el-option value="manual" label="手动完成" />
                </el-select>
              </el-form-item>
              <el-form-item label="报工间隔">
                <el-select v-model="editConfig.reportingInterval" style="width: 100%">
                  <el-option value="hourly" label="每小时" />
                  <el-option value="shift" label="每班次" />
                  <el-option value="daily" label="每日" />
                </el-select>
              </el-form-item>
            </el-form>
          </template>
        </div>

        <!-- Priority -->
        <div class="config-section">
          <h4>模块优先级</h4>
          <p class="section-hint">数值越大，在 RN App 中排序越靠前（1-10）</p>
          <el-slider
            v-model="editConfig.priority"
            :min="1"
            :max="10"
            :step="1"
            show-stops
            :marks="{ 1: '1', 5: '5', 10: '10' }"
          />
        </div>

        <!-- Save -->
        <div class="drawer-footer">
          <el-button @click="drawerVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="saveConfig">保存配置</el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style lang="scss" scoped>
.page-container {
  padding: 20px;
}

.header-card {
  margin-bottom: 20px;
}

.header-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.page-title {
  margin: 0 0 4px 0;
  font-size: 20px;
}

.page-desc {
  margin: 0;
  color: #909399;
  font-size: 14px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.module-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 16px;
  min-height: 200px;
}

.module-card {
  transition: opacity 0.3s;

  &.is-disabled {
    opacity: 0.6;
  }
}

.module-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.module-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.module-icon {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  flex-shrink: 0;
}

.module-name {
  margin: 0;
  font-size: 16px;
}

.module-desc {
  margin: 4px 0 0 0;
  font-size: 12px;
  color: #909399;
}

.module-stats {
  display: flex;
  gap: 20px;
}

.stat-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.stat-label {
  font-size: 13px;
  color: #606266;
}

.config-btn {
  margin-top: 12px;
  padding: 0;
}

.empty-state {
  grid-column: 1 / -1;
}

.drawer-content {
  padding: 0 4px;
}

.config-section {
  margin-bottom: 28px;

  h4 {
    margin: 0 0 4px 0;
    font-size: 15px;
  }
}

.section-hint {
  margin: 0 0 12px 0;
  font-size: 12px;
  color: #909399;
}

.el-checkbox {
  display: block;
  margin-bottom: 8px;
}

.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 20px;
  border-top: 1px solid #ebeef5;
}

.mode-radio-group {
  display: flex;
  flex-direction: column;
  gap: 12px;

  .el-radio {
    height: auto;
    margin-right: 0;
  }
}

.mode-option {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.mode-desc {
  font-size: 12px;
  color: #909399;
  font-weight: normal;
}
</style>
