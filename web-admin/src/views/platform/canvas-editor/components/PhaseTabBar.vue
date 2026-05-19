<!-- PhaseTabBar.vue — grouped horizontal tabs with separator -->
<template>
  <div class="phase-tab-bar">
    <!-- Phase A: flow & structure -->
    <div
      v-for="tab in phaseA" :key="tab.key"
      class="phase-tab" :class="{ active: activeTab === tab.key }"
      @click="activeTab = tab.key"
    >
      <span>{{ tab.icon }}</span> {{ tab.label }}
    </div>
    <div class="phase-separator" />
    <!-- Phase B: details & permissions -->
    <div
      v-for="tab in phaseB" :key="tab.key"
      class="phase-tab" :class="{ active: activeTab === tab.key }"
      @click="activeTab = tab.key"
    >
      <span>{{ tab.icon }}</span> {{ tab.label }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { useCanvasEditor } from '../composables/useCanvasEditor'

const { activeTab } = useCanvasEditor()

const phaseA = [
  { key: 'workflow', icon: '🔄', label: '状态机' },
  { key: 'approval', icon: '✅', label: '审批工作流' },
  { key: 'triggers', icon: '🔗', label: '触发链' },
  { key: 'validation', icon: '📐', label: '校验规则' },
]

// Round 4 Fix P1-10: added '定时任务' Tab for SchedulerPanel (legacy v2 config)
// Phase 3 Task 3.4: added '模块权限' Tab for factory-level L2 override
// Phase 3 Canvas-Notify (2026-05-19): added '通知模板' Tab for NotifyTemplate CRUD + Logs
// Phase 4b (2026-05-18): added '价格策略' Tab for PricingStrategyTab
// Phase 5 (2026-05-19): added 'Canvas Cron' Tab — DB-driven DynamicScheduler
// (real cron registration via Spring TaskScheduler + ShedLock, NOT v2 config).
// Legacy 'scheduler' Tab stays for v2 config-only style; sister chat will
// migrate 24 existing @Scheduled methods to the new pattern.
const phaseB = [
  { key: 'fields', icon: '📋', label: '字段配置' },
  { key: 'permissions', icon: '🛡️', label: '权限矩阵' },
  { key: 'module-permissions', icon: '🔐', label: '模块权限' },
  { key: 'tools', icon: '🔧', label: '工具/技能' },
  { key: 'scheduler', icon: '⏰', label: '定时任务 (v2)' },
  { key: 'notify', icon: '🔔', label: '通知模板' },
  { key: 'pricing', icon: '💰', label: '价格策略' },
  { key: 'cron', icon: '⚡', label: 'Canvas Cron' },
]
</script>

<style scoped>
.phase-tab-bar {
  display: flex; align-items: center; height: 40px; border-bottom: 1px solid var(--el-border-color);
  padding: 0 8px; flex-shrink: 0;
}
.phase-tab {
  padding: 8px 14px; font-size: 13px; color: var(--el-text-color-secondary);
  cursor: pointer; border-bottom: 2px solid transparent; transition: all 0.15s;
  display: flex; align-items: center; gap: 4px; white-space: nowrap;
}
.phase-tab:hover { color: var(--el-text-color-primary); }
.phase-tab.active { color: var(--el-color-primary); border-bottom-color: var(--el-color-primary); }
.phase-separator { width: 1px; height: 20px; background: var(--el-border-color); margin: 0 8px; }
</style>
