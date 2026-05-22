<!--
  FoodSafetyHubEditor — Canvas Phase A Tab 2 食品安全中心.

  7 sub-tabs (4 wrap shipped Sprint 8 P3 Phase A entities + 3 Sprint 9 placeholders):
    1. HACCP 关键控制点 (CRUD)
    2. HACCP 监控记录 (read-only with deviation filter)
    3. 食品添加剂限量 GB 2760 (read-only system data)
    4. 召回事件 (CRUD with status workflow + inline RecallAction timeline drawer)
    5. 留样追踪 GB 31654 (placeholder — sprint9/p2a not on main)
    6. 营养标签合规 GB 28050 (placeholder — sprint9/p2b)
    7. 供应商食品资质 / 冷链 GB 14881 / SSOP (placeholder — sprint9/p2c/p2d/p2e)

  Backend: CanvasFoodSafetyController @ /api/mobile/{factoryId}/canvas-food-safety.
  Role gate: factory_super_admin / permission_admin (backend @RequireRole).

  @since 2026-05-21 (Canvas Phase A subagent #2)
-->
<template>
  <div class="food-safety-hub-editor">
    <el-card class="summary-card" shadow="never">
      <template #header>
        <div class="summary-header">
          <span>食品安全中心 — 数据概览</span>
          <el-button text type="primary" @click="loadSummary">刷新</el-button>
        </div>
      </template>
      <div class="summary-grid">
        <el-statistic
          :value="summary.haccpCheckpointsActive"
          title="启用 CCP 数"
        />
        <el-statistic
          :value="summary.haccpDeviations"
          title="累计偏离记录"
          :value-style="summary.haccpDeviations > 0 ? { color: '#f56c6c' } : {}"
        />
        <el-statistic
          :value="summary.recallsOpen"
          title="未闭环召回"
          :value-style="summary.recallsOpen > 0 ? { color: '#e6a23c' } : {}"
        />
        <el-statistic
          :value="summary.additiveLimitsTotal"
          title="国标添加剂条目"
        />
      </div>
    </el-card>

    <el-tabs v-model="subTab" type="card" class="sub-tabs">
      <el-tab-pane label="HACCP 检查点" name="haccp">
        <HaccpCheckpointsList v-if="subTab === 'haccp'" :factory-id="factoryId" />
      </el-tab-pane>

      <el-tab-pane label="HACCP 监控记录" name="monitoring">
        <HaccpMonitoringList v-if="subTab === 'monitoring'" :factory-id="factoryId" />
      </el-tab-pane>

      <el-tab-pane label="食品添加剂 GB 2760" name="additive">
        <AdditiveLimitsList v-if="subTab === 'additive'" :factory-id="factoryId" />
      </el-tab-pane>

      <el-tab-pane label="召回事件" name="recall">
        <RecallEventsList v-if="subTab === 'recall'" :factory-id="factoryId" />
      </el-tab-pane>

      <el-tab-pane label="留样追踪 (GB 31654)" name="sample">
        <PlaceholderTab
          v-if="subTab === 'sample'"
          title="留样追踪 — 敬请期待"
          subtitle="留样制度强制 48 小时, 每批次 ≥ 100g. 实体在 Sprint 9 分支已实现, 待合并至 main."
          regulation="GB 31654-2021 餐饮服务通用卫生规范 §6.1"
          branch="sprint9/p2a-sample-retention-gb31654"
        />
      </el-tab-pane>

      <el-tab-pane label="营养标签 (GB 28050)" name="nutrition">
        <PlaceholderTab
          v-if="subTab === 'nutrition'"
          title="营养标签合规 — 敬请期待"
          subtitle="预包装食品强制标注 1+4 (能量 + 4 大营养素). 实体在 Sprint 9 分支已实现, 待合并至 main."
          regulation="GB 28050-2011 预包装食品营养标签通则"
          branch="sprint9/p2b-nutrition-label-gb28050"
        />
      </el-tab-pane>

      <el-tab-pane label="供应商 / 冷链 / SSOP" name="sprint9">
        <PlaceholderTab
          v-if="subTab === 'sprint9'"
          title="供应商食品资质 / 冷链温控 / SSOP — 敬请期待"
          subtitle="3 大食品安全模块 (SC证+HACCP+营业执照 / 冷链 GB 14881 / SSOP 清洁消毒). 实体在 Sprint 9 分支已实现, 待合并至 main."
          regulation="食品安全法 + GB 14881-2013 + SSOP 八项作业程序"
          branch="sprint9/p2c, sprint9/p2d, sprint9/p2e"
        />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useAuthStore } from '@/store/modules/auth'
import { getSummary, type FoodSafetySummary } from '@/api/foodSafetyHub'
import HaccpCheckpointsList from './components/HaccpCheckpointsList.vue'
import HaccpMonitoringList from './components/HaccpMonitoringList.vue'
import AdditiveLimitsList from './components/AdditiveLimitsList.vue'
import RecallEventsList from './components/RecallEventsList.vue'
import PlaceholderTab from './components/PlaceholderTab.vue'

interface Props {
  /** Optional override; defaults to authStore.factoryId. */
  factoryId?: string
}
const props = defineProps<Props>()
const authStore = useAuthStore()
const factoryId = computed(() => props.factoryId || authStore.factoryId || '')

type SubTabKey =
  | 'haccp'
  | 'monitoring'
  | 'additive'
  | 'recall'
  | 'sample'
  | 'nutrition'
  | 'sprint9'
const subTab = ref<SubTabKey>('haccp')

const summary = ref<FoodSafetySummary>({
  haccpCheckpointsActive: 0,
  haccpDeviations: 0,
  recallsOpen: 0,
  additiveLimitsTotal: 0,
  foodSamplePending: 0,
  nutritionLabelPending: 0,
  supplierQualPending: 0,
})

async function loadSummary() {
  if (!factoryId.value) return
  try {
    const res = await getSummary(factoryId.value)
    if (res.success && res.data) {
      summary.value = res.data
    }
  } catch {
    // axios interceptor handles error toast
  }
}

onMounted(() => {
  void loadSummary()
})
</script>

<style scoped>
.food-safety-hub-editor {
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
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}
.sub-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}
</style>
