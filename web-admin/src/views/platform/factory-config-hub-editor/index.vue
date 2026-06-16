<!--
  FactoryConfigHubEditor — Canvas Phase B Tab 1 工厂配置中心.

  6 sub-tabs wrapping existing factory-scoped config entities:
    1. 排班配置  (FactorySchedulingConfig) — 权重 / 临时工因子 / 自适应学习
    2. 临时工记录 (FactoryTempWorker)       — list + 创建 + 状态更新
    3. 五险一金   (HrInsuranceConfig)        — 历史版本 + 新增 ACTIVE
    4. 工资模式   (WagePolicy)               — PIECE_RATE / HOURLY / MIXED
    5. 编码规则   (EncodingRule)             — 业务单据编号规则
    6. 工厂总设置 (FactorySettings)          — AI / 通知 / 工时 / 货币

  Backend: CanvasFactoryConfigController @ /api/mobile/{factoryId}/canvas-factory-config
  Role gate: factory_super_admin / permission_admin (backend @RequireRole).

  AUD-4: every PUT submits version field. 409 VERSION_CONFLICT → toast sticky.

  @since 2026-05-22 (Canvas Phase B)
-->
<template>
  <div class="factory-config-hub-editor">
    <el-card class="summary-card" shadow="never">
      <template #header>
        <div class="summary-header">
          <span>工厂配置中心 — 数据概览</span>
          <el-button text type="primary" @click="loadOverview">刷新</el-button>
        </div>
      </template>
      <div class="summary-grid">
        <el-statistic
          :value="overview.schedulingConfigured ? 1 : 0"
          title="排班配置"
          :value-style="overview.schedulingConfigured ? { color: '#67c23a' } : { color: '#909399' }"
        />
        <el-statistic :value="overview.tempWorkerCount" title="临时工数" />
        <el-statistic
          :value="overview.insuranceActive ? 1 : 0"
          title="五险一金启用"
          :value-style="overview.insuranceActive ? { color: '#67c23a' } : { color: '#909399' }"
        />
        <el-statistic :value="overview.wagePolicyCount" title="工资策略数" />
        <el-statistic :value="overview.encodingRuleCount" title="编码规则数" />
        <el-statistic
          :value="overview.factorySettingsConfigured ? 1 : 0"
          title="总设置已配"
          :value-style="overview.factorySettingsConfigured ? { color: '#67c23a' } : { color: '#909399' }"
        />
      </div>
    </el-card>

    <el-tabs v-model="subTab" type="card" class="sub-tabs">
      <el-tab-pane label="排班配置" name="scheduling">
        <SchedulingPanel v-if="subTab === 'scheduling'" :factory-id="factoryId" />
      </el-tab-pane>
      <el-tab-pane label="临时工记录" name="temp-workers">
        <ContractWorkersPanel v-if="subTab === 'temp-workers'" :factory-id="factoryId" />
      </el-tab-pane>
      <el-tab-pane label="五险一金" name="insurance">
        <InsurancePanel v-if="subTab === 'insurance'" :factory-id="factoryId" />
      </el-tab-pane>
      <el-tab-pane label="工资模式" name="wage">
        <WagePoliciesPanel v-if="subTab === 'wage'" :factory-id="factoryId" />
      </el-tab-pane>
      <el-tab-pane label="编码规则" name="encoding">
        <EncodingRulesPanel v-if="subTab === 'encoding'" :factory-id="factoryId" />
      </el-tab-pane>
      <el-tab-pane label="工厂总设置" name="settings">
        <FactorySettingsPanel v-if="subTab === 'settings'" :factory-id="factoryId" />
      </el-tab-pane>
      <el-tab-pane label="仓库管理" name="warehouses">
        <WarehousePanel v-if="subTab === 'warehouses'" :factory-id="factoryId" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useAuthStore } from '@/store/modules/auth'
import {
  getOverview,
  type FactoryConfigOverview,
} from '@/api/factoryConfigHub'
import SchedulingPanel from './components/SchedulingPanel.vue'
import ContractWorkersPanel from './components/ContractWorkersPanel.vue'
import InsurancePanel from './components/InsurancePanel.vue'
import WagePoliciesPanel from './components/WagePoliciesPanel.vue'
import EncodingRulesPanel from './components/EncodingRulesPanel.vue'
import FactorySettingsPanel from './components/FactorySettingsPanel.vue'
import WarehousePanel from './components/WarehousePanel.vue'

interface Props {
  factoryId?: string
}
const props = defineProps<Props>()
const authStore = useAuthStore()
const factoryId = computed(() => props.factoryId || authStore.factoryId || '')

type SubTabKey =
  | 'scheduling'
  | 'temp-workers'
  | 'insurance'
  | 'wage'
  | 'encoding'
  | 'settings'
  | 'warehouses'
const subTab = ref<SubTabKey>('scheduling')

const overview = ref<FactoryConfigOverview>({
  schedulingConfigured: false,
  tempWorkerCount: 0,
  insuranceActive: false,
  wagePolicyCount: 0,
  encodingRuleCount: 0,
  factorySettingsConfigured: false,
})

async function loadOverview() {
  if (!factoryId.value) return
  try {
    const res = await getOverview(factoryId.value)
    if (res.success && res.data) {
      overview.value = res.data
    }
  } catch {
    // axios interceptor handles error toast
  }
}

onMounted(() => {
  void loadOverview()
})
</script>

<style scoped>
.factory-config-hub-editor {
  padding: 12px 16px;
  height: 100%;
  overflow: auto;
}
.summary-card {
  margin-bottom: 12px;
}
.summary-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.summary-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 16px;
}
.sub-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}
</style>
