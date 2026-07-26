<template>
  <div class="wf-node start-node" :class="{ selected }">
    <div class="node-label">{{ data?.label || '开始' }}</div>
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { Handle, Position } from '@vue-flow/core'

defineProps<{
  data?: { label?: string; nodeType?: string; config?: Record<string, unknown> }
  selected?: boolean
}>()
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
