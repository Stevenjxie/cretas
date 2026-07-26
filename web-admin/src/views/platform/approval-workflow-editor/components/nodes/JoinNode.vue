<template>
  <div class="wf-node join-node" :class="{ selected }">
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <strong class="node-label">{{ data?.label || '分支汇聚' }}</strong>
      <span class="node-kind">汇聚</span>
    </div>
    <div class="node-body">
      <div class="node-rule">{{ modeLabel }}</div>
      <div class="node-meta">等待上游审批分支达到汇聚条件</div>
    </div>
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'

const props = defineProps<{
  data?: { label?: string; nodeType?: string; config?: { mode?: string; n?: number } }
  selected?: boolean
}>()

const modeLabel = computed(() => {
  const m = props.data?.config?.mode ?? 'ALL'
  const n = props.data?.config?.n
  if (m === 'ALL') return '全部分支完成后继续'
  if (m === 'ANY') return '任一分支完成后继续'
  if (m === 'N_OF_M' && typeof n === 'number') return `任意 ${n} 个分支完成后继续`
  return '按配置的分支规则继续'
})
</script>

<style scoped>
.wf-node {
  box-sizing: border-box;
  width: 208px;
  overflow: hidden;
  border: 1px solid #cbd6e2;
  border-left: 4px solid #60758a;
  border-radius: 9px;
  background: #fff;
  box-shadow: 0 2px 7px rgb(31 62 92 / 8%);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.wf-node.selected {
  border-color: #60758a;
  box-shadow: 0 0 0 3px rgb(96 117 138 / 16%), 0 4px 12px rgb(31 62 92 / 13%);
}
.node-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 9px 10px 7px;
  border-bottom: 1px solid #edf2f7;
}
.node-label {
  overflow: hidden;
  color: #1a2332;
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.node-kind {
  flex: 0 0 auto;
  color: #60758a;
  font-size: 11px;
}
.node-body {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 7px 10px 9px;
}
.node-rule {
  padding: 4px 7px;
  border-radius: 4px;
  background: #f0f3f6;
  color: #4f6275;
  font-size: 11px;
}
.node-meta {
  color: #7a8599;
  font-size: 10px;
  line-height: 1.35;
}
:deep(.vue-flow__handle) {
  width: 8px;
  height: 8px;
  border: 2px solid #fff;
  background: #60758a;
  box-shadow: 0 0 0 1px #8aa0b5;
}
</style>
