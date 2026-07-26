<template>
  <div class="wf-node end-node" :class="['outcome-' + outcomeClass, { selected }]">
    <Handle type="target" :position="Position.Top" />
    <div class="node-label">{{ data?.label || outcomeLabel }}</div>
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
  box-sizing: border-box;
  display: flex;
  width: 136px;
  min-height: 40px;
  align-items: center;
  justify-content: center;
  padding: 8px 18px;
  border: 1px solid #cbd6e2;
  border-radius: 999px;
  background: #fff;
  box-shadow: 0 2px 6px rgb(31 62 92 / 8%);
  color: #1a2332;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.wf-node.selected {
  border-color: #1b65a8;
  box-shadow: 0 0 0 3px rgb(27 101 168 / 16%), 0 4px 10px rgb(31 62 92 / 12%);
}
.wf-node.outcome-rejected { border-color: #e6b0b0; }
.wf-node.outcome-timeout { border-color: #e7c990; }
.wf-node.outcome-cancelled { border-color: #bfc7d1; }
.node-label {
  overflow: hidden;
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}
:deep(.vue-flow__handle) {
  width: 8px;
  height: 8px;
  border: 2px solid #fff;
  background: #2fa66a;
  box-shadow: 0 0 0 1px #8aa0b5;
}
</style>
