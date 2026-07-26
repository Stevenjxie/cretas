<template>
  <div class="wf-node condition-node" :class="{ selected }">
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <strong class="node-label">{{ data?.label || '条件判断' }}</strong>
      <span class="node-kind">条件</span>
    </div>
    <div class="node-body">
      <div class="node-rule">{{ description || '按配置条件自动分流' }}</div>
      <div class="node-meta">满足规则后进入对应审批分支</div>
    </div>
    <Handle type="source" :position="Position.Bottom" id="default" />
    <Handle type="source" :position="Position.Right" id="match" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'

const props = defineProps<{
  data?: { label?: string; nodeType?: string; config?: { description?: string } }
  selected?: boolean
}>()

const description = computed(() => props.data?.config?.description ?? '')
</script>

<style scoped>
.wf-node {
  position: relative;
  box-sizing: border-box;
  width: 208px;
  overflow: hidden;
  border: 1px solid #d7a447;
  border-left: 4px solid #d88900;
  border-radius: 9px;
  background: #fff;
  box-shadow: 0 2px 7px rgb(31 62 92 / 8%);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.wf-node.selected {
  border-color: #d88900;
  box-shadow: 0 0 0 3px rgb(216 137 0 / 17%), 0 4px 12px rgb(31 62 92 / 13%);
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
  color: #8b6a2b;
  font-size: 11px;
}
.node-body {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 7px 10px 9px;
}
.node-rule {
  overflow: hidden;
  padding: 4px 7px;
  border-radius: 4px;
  background: #fff5e5;
  color: #b56d00;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  background: #d88900;
  box-shadow: 0 0 0 1px #8aa0b5;
}
</style>
