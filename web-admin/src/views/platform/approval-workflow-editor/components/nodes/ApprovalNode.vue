<template>
  <div class="wf-node approval-node" :class="{ selected, 'co-sign': requiredApprovers > 1 }">
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
 <span class="node-icon"></span>
      <span class="node-title">{{ data?.label || '审批节点' }}</span>
    </div>
    <div class="node-body">
      <div v-if="approverRoleLabels.length" class="badge roles">
        {{ approverRoleLabels.join(' / ') }}
      </div>
      <div v-else-if="approverRoleCount > 0" class="badge roles">
        已选 {{ approverRoleCount }} 个审批角色
      </div>
      <div v-if="approverUserLabels.length" class="badge users">
        {{ approverUserLabels.join(' / ') }}
      </div>
      <div v-else-if="approverUserCount > 0" class="badge users">
        已指定 {{ approverUserCount }} 位审批人
      </div>
      <div v-if="departmentIds.length" class="badge depts">
 {{ departmentIds.length }} 个部门
      </div>
      <div v-if="requiredApprovers > 1" class="badge cosign">
        会签 {{ requiredApprovers }} 人
      </div>
      <div v-if="timeoutMinutes > 0" class="badge sla">
        ⏱ {{ timeoutMinutes }} 分钟
      </div>
      <div v-if="delegateUserId" class="badge delegate">
        超时转派：{{ delegateUserLabel || '已指定人员' }}
      </div>
      <div v-if="autoApprove" class="badge auto">
 自动审批
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
const autoApprove = computed(() => Boolean(props.data?.config?.autoApproveCondition))
// Phase 1 B.5
const departmentIds = computed(() => props.data?.config?.departmentIds ?? [])
const delegateUserId = computed(() => props.data?.config?.delegateUserId ?? '')
const delegateUserLabel = computed(() => props.data?.config?.delegateUserLabel ?? '')
</script>

<style scoped>
.wf-node {
  min-width: 180px; background: #fff; border-radius: 8px;
  border: 2px solid #409eff; border-left: 6px solid #409eff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.wf-node.selected { border-color: #ecf5ff; box-shadow: 0 0 0 3px #409eff, 0 2px 12px rgba(0, 0, 0, 0.2); }
.wf-node.co-sign { border-left-color: #e6a23c; }
.node-header {
  padding: 6px 12px; background: #409eff; color: #fff;
  font-size: 13px; font-weight: 600; display: flex; align-items: center; gap: 6px;
  border-radius: 5px 5px 0 0;
}
.wf-node.co-sign .node-header { background: #e6a23c; }
.node-icon { font-size: 14px; }
.node-body { padding: 6px 12px; font-size: 11px; color: #606266; display: flex; flex-direction: column; gap: 3px; }
.badge { padding: 2px 6px; border-radius: 3px; background: #f4f4f5; }
.badge.roles { color: #409eff; }
.badge.users { color: #337ecc; }
.badge.depts { color: #909399; }
.badge.cosign { color: #e6a23c; font-weight: 600; }
.badge.sla { color: #f56c6c; }
.badge.delegate { color: #909399; }
.badge.auto { color: #67c23a; }
</style>
