<!-- PhaseTabBar.vue — grouped horizontal tabs with separator -->
<template>
  <div class="phase-tab-bar">
    <!-- Phase A: flow & structure -->
    <div
      v-for="tab in phaseA" :key="tab.key"
      class="phase-tab" :class="{ active: activeTab === tab.key }"
      @click="activeTab = tab.key"
    >
      <span v-if="tab.icon">{{ tab.icon }}</span>{{ tab.label }}
    </div>
    <div class="phase-separator" />
    <!-- Phase B: details & permissions -->
    <div
      v-for="tab in phaseB" :key="tab.key"
      class="phase-tab" :class="{ active: activeTab === tab.key }"
      @click="activeTab = tab.key"
    >
      <span v-if="tab.icon">{{ tab.icon }}</span>{{ tab.label }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { useCanvasEditor } from '../composables/useCanvasEditor'

const { activeTab } = useCanvasEditor()

// 2026-05-21: 清除全部 Tab emoji (per feedback_no_emoji_in_b_end_ui.md HARD —
// B 端用户对 emoji 厌恶度高, 用纯文本 label 更清晰). icon 字段保留为空串,
// template `v-if="tab.icon"` 自动跳过渲染.
const phaseA = [
  { key: 'workflow', icon: '', label: '状态机' },
  { key: 'approval', icon: '', label: '审批工作流' },
  { key: 'triggers', icon: '', label: '触发链' },
  { key: 'validation', icon: '', label: '校验规则' },
]

// Round 4 Fix P1-10: added '定时任务' Tab for SchedulerPanel (legacy v2 config)
// Phase 3 Task 3.4: added '模块权限' Tab for factory-level L2 override
// Phase 2 Canvas-Alerts (2026-05-19): added '预警规则' Tab for AlertRulesEditor (🚨 to differ from notify 🔔)
// Phase 3 Canvas-Notify (2026-05-19): added '通知模板' Tab for NotifyTemplate CRUD + Logs
// Phase 4a (2026-05-18): added '业务规则' Tab for Canvas-Rules business rule engine.
// Phase 4b (2026-05-18): added '价格策略' Tab for PricingStrategyTab
// Phase 5 (2026-05-19): added 'Canvas Cron' Tab — DB-driven DynamicScheduler
// (real cron registration via Spring TaskScheduler + ShedLock, NOT v2 config).
// Legacy 'scheduler' Tab stays for v2 config-only style; sister chat will
// migrate 24 existing @Scheduled methods to the new pattern.
// Phase A P0 (2026-05-21, post 六扇门 4 次会议 audit): 3 new hubs pre-registered
// so 3 parallel subagents can each fill their slot without rebasing central files.
// Each Tab maps to a stub component below — subagents replace the stub with the
// real editor in their own worktree-isolated branch.
const phaseB = [
  { key: 'fields', icon: '', label: '字段配置' },
  { key: 'permissions', icon: '', label: '权限矩阵' },
  { key: 'module-permissions', icon: '', label: '模块权限' },
  { key: 'user-module-access', icon: '', label: '账号模块权限' },
  { key: 'tools', icon: '', label: '工具/技能' },
  { key: 'scheduler', icon: '', label: '定时任务 (v2)' },
  { key: 'alerts', icon: '', label: '预警规则' },
  { key: 'notify', icon: '', label: '通知模板' },
  { key: 'business-rules', icon: '', label: '业务规则' },
  { key: 'pricing', icon: '', label: '价格策略' },
  { key: 'cron', icon: '', label: 'Canvas Cron' },
  // Phase A P0 新增 — Tab placeholder (subagent 填充内容)
  { key: 'thresholds', icon: '', label: '阈值参数' },
  { key: 'food-safety', icon: '', label: '食品安全' },
  { key: 'indicators', icon: '', label: '指标中心' },
  // Phase B P1 (2026-05-22) — 客户面差异化卖点
  { key: 'factory-config', icon: '', label: '工厂配置' },
  { key: 'sales-target', icon: '', label: '销售目标' },
  // Phase C P2 — 内部防呆 dropdown 集中
  { key: 'enum-dictionary', icon: '', label: '枚举字典' },
  // P3 半-Canvas-ed (entity 已存, 单 Tab 即可)
  { key: 'supplier-admission', icon: '', label: '供应商准入' },
  { key: 'encoding-rule', icon: '', label: '编码规则' },
  { key: 'hr-insurance', icon: '', label: '五险一金' },
  { key: 'factory-scheduling', icon: '', label: '排班配置' },
  { key: 'purchase-order-approval', icon: '', label: '采购审批' },
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
