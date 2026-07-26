<template>
  <div class="wf-node notify-node" :class="{ selected }">
    <Handle type="target" :position="Position.Top" />
    <div class="node-content">
 <div class="node-icon"></div>
      <div class="node-label">{{ data?.label || '通知' }}</div>
      <div v-if="notifyRoleLabels.length" class="node-roles">
        → {{ notifyRoleLabels.join('、') }}
      </div>
      <div v-else-if="notifyRoleCount > 0" class="node-roles">
        → 已选 {{ notifyRoleCount }} 个通知角色
      </div>
      <div v-if="channels.length" class="node-channels">
 <span v-if="channels.includes('wechat')" title="微信"></span>
 <span v-if="channels.includes('dingtalk')" title="钉钉"></span>
 <span v-if="channels.includes('email')" title="邮件"></span>
      </div>
 <div v-else class="node-channels warn" title="未配置通知渠道"></div>
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
</script>

<style scoped>
.wf-node {
  min-width: 150px; padding: 8px 12px; background: #fff;
  border: 2px dashed #909399; border-radius: 8px;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.05);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.wf-node.selected { box-shadow: 0 0 0 3px #909399, 0 2px 12px rgba(0, 0, 0, 0.2); }
.node-content { display: flex; flex-direction: column; align-items: center; }
.node-icon { font-size: 20px; color: #909399; line-height: 1; }
.node-label { font-size: 13px; font-weight: 600; color: #303133; margin-top: 2px; }
.node-roles { font-size: 10px; color: #909399; margin-top: 2px; max-width: 130px; text-align: center; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.node-channels { font-size: 14px; margin-top: 4px; display: flex; gap: 4px; justify-content: center; }
.node-channels.warn { color: #e6a23c; font-size: 12px; }
</style>
