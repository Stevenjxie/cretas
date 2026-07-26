<template>
  <div class="wf-node approval-node" :class="{ selected, 'co-sign': requiredApprovers > 1 }">
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <strong class="node-title">{{ data?.label || '审批节点' }}</strong>
      <span class="node-kind">审批</span>
    </div>
    <div class="node-body">
      <div
        v-if="approverRoleLabels.length"
        class="info-row roles"
        :title="approverRoleLabels.join(' / ')"
      >
        {{ approverRoleLabels.join(' / ') }}
      </div>
      <div v-else-if="approverRoleCount > 0" class="info-row roles">
        已选 {{ approverRoleCount }} 个审批角色
      </div>
      <div
        v-if="approverUserLabels.length"
        class="info-row users"
        :title="approverUserLabels.join(' / ')"
      >
        {{ approverUserLabels.join(' / ') }}
      </div>
      <div v-else-if="approverUserCount > 0" class="info-row users">
        已指定 {{ approverUserCount }} 位审批人
      </div>
      <div v-if="approverRoleCount === 0 && approverUserCount === 0" class="info-row empty">
        尚未选择审批角色或审批人
      </div>
      <div class="node-meta">
        <span>{{ requiredApprovers }} 人审批</span>
        <span v-if="timeoutLabel">· {{ timeoutLabel }}</span>
        <span v-if="departmentIds.length">· 限定 {{ departmentIds.length }} 个部门</span>
      </div>
      <div v-if="delegateUserId || autoApprove" class="node-extra">
        <span v-if="delegateUserId">超时转派：{{ delegateUserLabel || '已指定人员' }}</span>
        <span v-if="autoApprove">符合条件时自动审批</span>
      </div>
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
      approverRoles?: string[]
      approverUserIds?: string[]
      approverRoleLabels?: string[]
      approverUserLabels?: string[]
      requiredApprovers?: number
      timeoutMinutes?: number
      autoApproveCondition?: string
      autoRejectCondition?: string
      // Phase 1 B.5 additions
      departmentIds?: number[]
      delegateUserId?: string
      delegateUserLabel?: string
    }
  }
  selected?: boolean
}>()

const approverRoleLabels = computed(() => props.data?.config?.approverRoleLabels ?? [])
const approverUserLabels = computed(() => props.data?.config?.approverUserLabels ?? [])
const approverRoleCount = computed(() => props.data?.config?.approverRoles?.length ?? 0)
const approverUserCount = computed(() => props.data?.config?.approverUserIds?.length ?? 0)
const requiredApprovers = computed(() => Number(props.data?.config?.requiredApprovers ?? 1))
const timeoutMinutes = computed(() => Number(props.data?.config?.timeoutMinutes ?? 0))
const timeoutLabel = computed(() => {
  if (timeoutMinutes.value <= 0) return ''
  if (timeoutMinutes.value % 1440 === 0) return `${timeoutMinutes.value / 1440} 天`
  if (timeoutMinutes.value % 60 === 0) return `${timeoutMinutes.value / 60} 小时`
  return `${timeoutMinutes.value} 分钟`
})
const autoApprove = computed(() => Boolean(props.data?.config?.autoApproveCondition))
// Phase 1 B.5
const departmentIds = computed(() => props.data?.config?.departmentIds ?? [])
const delegateUserId = computed(() => props.data?.config?.delegateUserId ?? '')
const delegateUserLabel = computed(() => props.data?.config?.delegateUserLabel ?? '')
</script>

<style scoped>
.wf-node {
  box-sizing: border-box;
  width: 208px;
  overflow: hidden;
  border: 1px solid #cbd6e2;
  border-left: 4px solid #1b65a8;
  border-radius: 9px;
  background: #fff;
  box-shadow: 0 2px 7px rgb(31 62 92 / 8%);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.wf-node.selected {
  border-color: #d88900;
  border-left-color: #d88900;
  box-shadow: 0 0 0 3px rgb(216 137 0 / 17%), 0 4px 12px rgb(31 62 92 / 13%);
}
.wf-node.co-sign { border-left-color: #e6a23c; }
.node-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 9px 10px 7px;
  border-bottom: 1px solid #edf2f7;
}
.node-title {
  overflow: hidden;
  color: #1a2332;
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.node-kind {
  flex: 0 0 auto;
  color: #7a8599;
  font-size: 11px;
}
.node-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 7px 10px 9px;
  color: #5f6b7a;
  font-size: 11px;
}
.info-row {
  overflow: hidden;
  padding: 3px 6px;
  border-radius: 4px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.info-row.roles {
  background: #edf6ff;
  color: #1b65a8;
}
.info-row.users {
  background: #edf8f1;
  color: #23764a;
}
.info-row.empty {
  background: #f4f6f9;
  color: #8a95a5;
}
.node-meta {
  overflow: hidden;
  color: #697587;
  font-size: 10px;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.node-extra {
  display: flex;
  flex-direction: column;
  gap: 2px;
  color: #8a6a30;
  font-size: 10px;
}
:deep(.vue-flow__handle) {
  width: 8px;
  height: 8px;
  border: 2px solid #fff;
  background: #1b65a8;
  box-shadow: 0 0 0 1px #8aa0b5;
}
</style>
