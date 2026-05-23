<template>
  <div class="wf-node end-node" :class="['outcome-' + outcomeClass, { selected }]">
    <Handle type="target" :position="Position.Top" />
    <div class="node-icon">{{ outcomeIcon }}</div>
    <div class="node-label">{{ data?.label || outcomeLabel }}</div>
    <div class="outcome-tag">{{ outcomeLabel }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'

const props = defineProps<{
  data?: { label?: string; nodeType?: string; config?: { outcome?: string } }
  selected?: boolean
}>()

const outcome = computed(() => String(props.data?.config?.outcome ?? 'APPROVED'))
const outcomeClass = computed(() => outcome.value.toLowerCase())
const outcomeIcon = computed(() => {
  switch (outcome.value) {
 case 'REJECTED': return ''
    case 'TIMEOUT': return '⏱'
    case 'CANCELLED': return '⊘'
 default: return ''
  }
})
const outcomeLabel = computed(() => {
  switch (outcome.value) {
    case 'REJECTED': return '拒绝'
    case 'TIMEOUT': return '超时'
    case 'CANCELLED': return '取消'
    default: return '通过'
  }
})
</script>

<style scoped>
.wf-node {
  width: 100px; padding: 10px 6px; border-radius: 6px;
  display: flex; flex-direction: column; align-items: center;
  color: #fff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  border: 3px solid transparent;
  transition: all 0.15s;
}
.wf-node.selected { border-color: #fff; }
.wf-node.outcome-approved { background: #67c23a; box-shadow: 0 2px 8px rgba(103, 194, 58, 0.4); }
.wf-node.outcome-approved.selected { box-shadow: 0 0 0 3px #67c23a, 0 2px 12px rgba(0, 0, 0, 0.2); }
.wf-node.outcome-rejected { background: #f56c6c; box-shadow: 0 2px 8px rgba(245, 108, 108, 0.4); }
.wf-node.outcome-rejected.selected { box-shadow: 0 0 0 3px #f56c6c, 0 2px 12px rgba(0, 0, 0, 0.2); }
.wf-node.outcome-timeout { background: #e6a23c; box-shadow: 0 2px 8px rgba(230, 162, 60, 0.4); }
.wf-node.outcome-cancelled { background: #909399; box-shadow: 0 2px 8px rgba(144, 147, 153, 0.4); }
.node-icon { font-size: 18px; line-height: 1; }
.node-label { font-size: 12px; font-weight: 600; margin-top: 2px; }
.outcome-tag { font-size: 10px; opacity: 0.85; font-family: monospace; margin-top: 1px; }
</style>
