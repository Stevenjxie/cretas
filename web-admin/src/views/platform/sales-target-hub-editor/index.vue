<!--
  SalesTargetHubEditor — Canvas Phase B Tab 2 销售目标中心.

  Wraps Sprint 7 T5 CommissionRule + adds tier ladder + period config + leaderboard formula.

  Backend: CanvasSalesTargetController @ /api/mobile/{factoryId}/canvas-sales-target
  Role gate: factory_super_admin / permission_admin (backend @RequireRole).

  AUD-4: PUT submits version field. 409 VERSION_CONFLICT → toast sticky.

  @since 2026-05-22 (Canvas Phase B)
-->
<template>
  <div class="sales-target-hub-editor">
    <el-card class="summary-card" shadow="never">
      <template #header>
        <div class="summary-header">
          <span>销售目标中心 — 提成规则统计</span>
          <el-button text type="primary" @click="loadOverview">刷新</el-button>
        </div>
      </template>
      <div class="summary-grid">
        <el-statistic :value="overview.totalRules" title="规则总数" />
        <el-statistic
          :value="overview.activeRules"
          title="启用规则"
          :value-style="overview.activeRules > 0 ? { color: '#67c23a' } : {}"
        />
        <el-statistic
          :value="overview.tieredRules"
          title="阶梯规则"
          :value-style="overview.tieredRules > 0 ? { color: '#409eff' } : {}"
        />
        <el-statistic :value="overview.monthlyRules" title="按月规则" />
        <el-statistic :value="overview.quarterlyRules" title="按季度规则" />
      </div>
    </el-card>

    <el-tabs v-model="subTab" type="card" class="sub-tabs">
      <el-tab-pane label="规则列表 / 编辑" name="rules">
        <CommissionRulesPanel v-if="subTab === 'rules'" :factory-id="factoryId" @refresh="loadOverview" />
      </el-tab-pane>
      <el-tab-pane label="提成试算" name="preview">
        <CommissionPreviewPanel v-if="subTab === 'preview'" :factory-id="factoryId" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useAuthStore } from '@/store/modules/auth'
import { getOverview, type SalesTargetOverview } from '@/api/salesTargetHub'
import CommissionRulesPanel from './components/CommissionRulesPanel.vue'
import CommissionPreviewPanel from './components/CommissionPreviewPanel.vue'

interface Props {
  factoryId?: string
}
const props = defineProps<Props>()
const authStore = useAuthStore()
const factoryId = computed(() => props.factoryId || authStore.factoryId || '')

type SubTabKey = 'rules' | 'preview'
const subTab = ref<SubTabKey>('rules')

const overview = ref<SalesTargetOverview>({
  totalRules: 0,
  activeRules: 0,
  tieredRules: 0,
  monthlyRules: 0,
  quarterlyRules: 0,
})

async function loadOverview() {
  if (!factoryId.value) return
  try {
    const res = await getOverview(factoryId.value)
    if (res.success && res.data) {
      overview.value = res.data
    }
  } catch {
    // axios interceptor handles toast
  }
}

onMounted(() => {
  void loadOverview()
})
</script>

<style scoped>
.sales-target-hub-editor {
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
  grid-template-columns: repeat(5, 1fr);
  gap: 16px;
}
.sub-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}
</style>
