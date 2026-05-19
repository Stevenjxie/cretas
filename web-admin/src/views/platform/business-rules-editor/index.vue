<!--
  business-rules-editor/index.vue
  Canvas-Rules Phase 4a — Tab container hosting:
    - RulesList: CRUD + toggle of business rules (grouped by scope)
    - ExecutionLogsList: paged history of rule executions

  Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §4
  Mounted from canvas-editor as activeTab='business-rules'.
-->
<template>
  <div class="business-rules-editor">
    <el-tabs v-model="activeSubTab" type="border-card" class="brules-tabs">
      <el-tab-pane label="规则列表" name="rules">
        <RulesList :factory-id="factoryId" @select-logs="onSelectLogs" />
      </el-tab-pane>
      <el-tab-pane label="执行日志" name="execution-logs">
        <ExecutionLogsList :factory-id="factoryId" :default-rule-id="selectedLogRuleId" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import RulesList from './components/RulesList.vue'
import ExecutionLogsList from './components/ExecutionLogsList.vue'

defineProps<{
  factoryId: string
}>()

const activeSubTab = ref<'rules' | 'execution-logs'>('rules')
const selectedLogRuleId = ref<string>('')

function onSelectLogs(ruleId: string) {
  selectedLogRuleId.value = ruleId
  activeSubTab.value = 'execution-logs'
}
</script>

<style scoped>
.business-rules-editor {
  height: 100%;
  padding: 12px;
  box-sizing: border-box;
  overflow: auto;
}
.brules-tabs {
  height: 100%;
}
</style>
