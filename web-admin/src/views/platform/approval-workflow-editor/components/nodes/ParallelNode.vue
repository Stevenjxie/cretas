<template>
  <div class="wf-node parallel-node" :class="{ selected }">
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <strong class="node-label">{{ data?.label || '并行审批' }}</strong>
      <span class="node-kind">并行</span>
    </div>
    <div class="node-body">
      <div class="node-rule">同时启动多个审批分支</div>
      <div class="node-meta">各分支互不阻塞，后续可通过汇聚节点收拢</div>
    </div>
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { Handle, Position } from '@vue-flow/core'

defineProps<{
  data?: { label?: string; nodeType?: string; config?: { description?: string } }
  selected?: boolean
}>()
</script>

<style scoped>
.wf-node {
  box-sizing: border-box;
  width: 208px;
  overflow: hidden;
  border: 1px solid #cfc4eb;
  border-left: 4px solid #7654c4;
  border-radius: 9px;
  background: #fff;
  box-shadow: 0 2px 7px rgb(31 62 92 / 8%);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.wf-node.selected {
  border-color: #7654c4;
  box-shadow: 0 0 0 3px rgb(118 84 196 / 16%), 0 4px 12px rgb(31 62 92 / 13%);
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
  color: #7654c4;
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
  background: #f3effd;
  color: #6647ac;
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
  background: #7654c4;
  box-shadow: 0 0 0 1px #8aa0b5;
}
</style>
