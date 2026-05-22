<!-- OnboardingStep3Workflows.vue -->
<template>
  <div>
    <h3>流程总览确认</h3>
    <p class="step-desc">模板已为每个模块预设工作流。确认无误后进入下一步。</p>
    <div class="workflow-grid">
      <div v-for="mod in modules" :key="mod" class="workflow-card">
        <div class="wf-header">
          <span class="wf-name">{{ moduleNames[mod] || mod }}</span>
          <el-tag size="small" type="success">就绪</el-tag>
        </div>
        <div class="wf-preview">
          <span v-for="(state, i) in getStates(mod)" :key="i" class="wf-state">
            {{ state }}<span v-if="i < getStates(mod).length - 1"> → </span>
          </span>
        </div>
        <el-button link size="small" type="primary">编辑流程</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{ factoryId: string; modules: string[] }>()

const moduleNames: Record<string, string> = {
  sales_order: '销售订单', purchase_order: '采购订单', bom: 'BOM配方',
  production_plan: '生产计划', production_report: '报工', quality_inspection: '质检',
  inventory: '库存', equipment: '设备', customer: '客户', supplier: '供应商',
}

function getStates(mod: string): string[] {
  const defaults: Record<string, string[]> = {
    sales_order: ['草稿', '确认', '财务审核', '发货', '完成'],
    purchase_order: ['草稿', '提交', '审批', '到货', '完成'],
    production_plan: ['排产', '物料齐套', '生产中', '完成'],
  }
  return defaults[mod] || ['就绪']
}
</script>

<style scoped>
.step-desc { color: var(--el-text-color-secondary); font-size: 13px; margin-bottom: 16px; }
.workflow-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.workflow-card { border: 1px solid var(--el-border-color); border-radius: 8px; padding: 12px; }
.wf-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.wf-name { font-weight: bold; }
.wf-preview { font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 8px; }
.wf-state { white-space: nowrap; }
</style>
