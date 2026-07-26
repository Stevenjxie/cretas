<template>
  <div class="wf-node notify-node" :class="{ selected }">
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <strong class="node-label">{{ data?.label || '审批结果通知' }}</strong>
      <span class="node-kind">通知</span>
    </div>
    <div class="node-body">
      <div
        v-if="notifyRoleLabels.length"
        class="node-roles"
        :title="notifyRoleLabels.join('、')"
      >
        {{ notifyRoleLabels.join('、') }}
      </div>
      <div v-else-if="notifyRoleCount > 0" class="node-roles">
        已选 {{ notifyRoleCount }} 个通知角色
      </div>
      <div v-else class="node-roles empty">尚未选择通知对象</div>
      <div v-if="channelLabels.length" class="node-channels">
        {{ channelLabels.join(' · ') }}
      </div>
      <div v-else class="node-channels warn">未配置通知渠道</div>
    </div>
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'

const props = defineProps<{
  data?: {
    label?: string
    nodeType?: string
    config?: {
      notifyRoles?: string[]
      notifyRoleLabels?: string[]
      notifyTemplate?: string
      channels?: string[]
    }
  }
  selected?: boolean
}>()

const notifyRoleLabels = computed(() => props.data?.config?.notifyRoleLabels ?? [])
const notifyRoleCount = computed(() => props.data?.config?.notifyRoles?.length ?? 0)
const channels = computed(() => props.data?.config?.channels ?? [])
const channelLabels = computed(() => channels.value.map((channel) => {
  if (channel === 'wechat') return '微信'
  if (channel === 'dingtalk') return '钉钉'
  if (channel === 'email') return '邮件'
  return channel
}))
</script>

<style scoped>
.wf-node {
  box-sizing: border-box;
  width: 208px;
  overflow: hidden;
  border: 1px solid #cbd6e2;
  border-left: 4px solid #527a9d;
  border-radius: 9px;
  background: #fff;
  box-shadow: 0 2px 7px rgb(31 62 92 / 8%);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.wf-node.selected {
  border-color: #527a9d;
  box-shadow: 0 0 0 3px rgb(82 122 157 / 16%), 0 4px 12px rgb(31 62 92 / 13%);
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
  color: #527a9d;
  font-size: 11px;
}
.node-body {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 7px 10px 9px;
}
.node-roles,
.node-channels {
  overflow: hidden;
  padding: 4px 7px;
  border-radius: 4px;
  background: #eff5fa;
  color: #466b8b;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.node-roles.empty {
  background: #f4f6f9;
  color: #8a95a5;
}
.node-channels {
  padding: 0;
  background: transparent;
  color: #697587;
  font-size: 10px;
}
.node-channels.warn {
  color: #b47616;
}
:deep(.vue-flow__handle) {
  width: 8px;
  height: 8px;
  border: 2px solid #fff;
  background: #527a9d;
  box-shadow: 0 0 0 1px #8aa0b5;
}
</style>
