<!-- CanvasBreadcrumb.vue -->
<template>
  <div class="canvas-breadcrumb" v-if="selectedModule">
    <span class="bc-module">{{ moduleIcon }} {{ moduleName }}</span>
    <span class="bc-sep">›</span>
    <span class="bc-phase">{{ phaseIcon }} {{ phaseLabel }}</span>
    <span class="bc-meta" v-if="meta">· {{ meta }}</span>
  </div>
  <div class="canvas-breadcrumb empty" v-else>
    请在左侧选择一个模块开始配置
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useCanvasEditor } from '../composables/useCanvasEditor'

const { selectedModule, activeTab } = useCanvasEditor()

const moduleIcons: Record<string, string> = {
 sales_order: '', purchase_order: '', bom: '', production_plan: '',
 production_report: '', quality_inspection: '', inventory: '',
 equipment: '️', customer: '', supplier: '',
}

const moduleNames: Record<string, string> = {
  sales_order: '销售订单', purchase_order: '采购订单', bom: 'BOM配方',
  production_plan: '生产计划', production_report: '报工', quality_inspection: '质检',
  inventory: '库存', equipment: '设备', customer: '客户', supplier: '供应商',
}

const tabLabels: Record<string, { icon: string; label: string }> = {
 workflow: { icon: '', label: '状态机' },
 approval: { icon: '', label: '审批工作流' },
 triggers: { icon: '', label: '触发链' },
 validation: { icon: '', label: '校验规则' },
 fields: { icon: '', label: '字段配置' },
 permissions: { icon: '️', label: '权限矩阵' },
 'module-permissions': { icon: '', label: '模块权限' },
 tools: { icon: '', label: '工具/技能' },
  scheduler: { icon: '⏰', label: '定时任务' },
}

const moduleIcon = computed(() => moduleIcons[selectedModule.value] || '')
const moduleName = computed(() => moduleNames[selectedModule.value] || selectedModule.value)
const phaseIcon = computed(() => tabLabels[activeTab.value]?.icon || '')
const phaseLabel = computed(() => tabLabels[activeTab.value]?.label || activeTab.value)
const meta = ref('')

defineExpose({ setMeta: (m: string) => { meta.value = m } })
</script>

<style scoped>
.canvas-breadcrumb {
  height: 32px; display: flex; align-items: center; gap: 6px;
  padding: 0 12px; font-size: 12px; border-bottom: 1px solid var(--el-border-color-lighter);
  flex-shrink: 0;
}
.canvas-breadcrumb.empty { color: var(--el-text-color-secondary); }
.bc-module { color: var(--el-color-primary); }
.bc-sep { color: var(--el-text-color-secondary); }
.bc-phase { font-weight: bold; }
.bc-meta { color: var(--el-text-color-secondary); }
</style>
