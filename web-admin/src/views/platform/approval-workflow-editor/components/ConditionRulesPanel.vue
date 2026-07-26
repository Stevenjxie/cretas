<template>
  <el-drawer
    :model-value="visible"
    :title="`条件路由规则 — 节点 ${nodeLabel}`"
    direction="rtl"
    size="600px"
    :destroy-on-close="false"
    @update:model-value="(v: boolean) => $emit('update:visible', v)"
  >
    <div class="rules-toolbar">
      <el-alert
        type="info"
        :closable="false"
        title="求值顺序"
        description="先按 priority ASC 评估规则; 第一个命中 (true) 走 trueTargetNodeId, 第一个明确否决 (false + falseTargetNodeId 非空) 走 false 分支; 全部规则不明确路由再 fall through 到 edge.condition (SpEL escape hatch)."
      />
      <el-alert
        v-if="roleDirectoryError"
        class="role-directory-alert"
        type="error"
        :closable="false"
        show-icon
        title="审批角色目录加载失败"
        description="角色条件暂时只能读取历史配置，不能新增角色。关闭后重开可重试。"
      />
      <el-button type="primary" size="small" @click="addRule" style="margin-top: 12px">+ 添加规则</el-button>
    </div>

    <el-divider />

    <div v-loading="loading" class="rules-list">
      <!-- R5 (fool-proof): empty 状态给明确 next action, 不留 dead-end -->
      <el-empty
        v-if="!loading && rules.length === 0"
        description="此节点暂无 WorkflowRule. 不配置 → 走 edge.condition (raw SpEL); 想用结构化规则 → 添加规则."
      >
        <el-button type="primary" @click="addRule">添加第一条规则</el-button>
      </el-empty>
      <RuleEditor
        v-for="(r, idx) in rules"
        :key="r.id"
        :rule="r"
        :index="idx"
        :candidate-nodes="candidateNodes"
        :business-mode="businessMode"
        :role-options="roleOptions"
        @change="(updated: WorkflowRuleDTO) => onRuleChange(idx, updated)"
        @delete="onDelete(r)"
        @test="onTest(r)"
      />
    </div>

    <template #footer>
      <el-button @click="$emit('update:visible', false)">关闭</el-button>
      <el-button type="primary" :loading="saving" @click="saveAll">保存全部</el-button>
    </template>

    <RuleTestModal
      v-if="!businessMode"
      v-model:visible="testModalVisible"
      :factory-id="factoryId"
      :rule="testingRule"
    />
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createRule,
  deleteRule,
  listRulesByNode,
  updateRule,
  type WorkflowRuleDTO,
  type WorkflowRuleRequest,
  type WorkflowRuleExpression,
} from '@/api/workflowRule'
import type { ApprovalWorkflowNode } from '@/api/approvalWorkflow'
import {
  getApprovalDirectory,
  type ApprovalRoleDirectoryItem,
} from '@/api/approvalWorkflow'
import type { ApiResponse } from '@/types/api'
import {
  buildRoleOptions,
  type DirectoryOption,
} from '../lib/approvalDirectory'
import RuleEditor from './RuleEditor.vue'
import RuleTestModal from './RuleTestModal.vue'

const props = defineProps<{
  visible: boolean
  factoryId: string
  workflowId: string
  nodeId: string
  nodeLabel: string
  candidateNodes: ApprovalWorkflowNode[]
  businessMode?: boolean
}>()

const emit = defineEmits<{ 'update:visible': [v: boolean]; saved: [] }>()

const loading = ref(false)
const saving = ref(false)
const rules = ref<WorkflowRuleDTO[]>([])
const directoryRoles = ref<ApprovalRoleDirectoryItem[]>([])
const roleDirectoryError = ref('')
const testModalVisible = ref(false)
const testingRule = ref<WorkflowRuleDTO | null>(null)

watch(
  () => props.visible,
  (v) => {
    if (v) void Promise.all([loadRules(), loadRoleDirectory()])
  },
)

const selectedRoleCodes = computed(() => rules.value.flatMap((rule) => {
  if (rule.ruleType !== 'ROLE_MATCH') return []
  const expression = parseExpr(rule.expression)
  const values = 'in' in expression ? expression.in : []
  return Array.isArray(values)
    ? values.map(String)
    : []
}))
const roleOptions = computed<DirectoryOption[]>(() => (
  buildRoleOptions(directoryRoles.value, selectedRoleCodes.value)
))

async function loadRoleDirectory() {
  roleDirectoryError.value = ''
  try {
    const directory = await getApprovalDirectory(props.factoryId)
    directoryRoles.value = directory.roles
  } catch (error) {
    roleDirectoryError.value = error instanceof Error ? error.message : '角色目录加载失败'
  }
}

async function loadRules() {
  if (!props.workflowId || !props.nodeId) return
  loading.value = true
  try {
    const resp = (await listRulesByNode(
      props.factoryId,
      props.workflowId,
      props.nodeId,
    )) as unknown as ApiResponse<WorkflowRuleDTO[]>
    if (resp.success && resp.data) {
      rules.value = resp.data
    }
  } catch (e) {
    ElMessage.error(`加载规则失败: ${(e as Error).message}`)
  } finally {
    loading.value = false
  }
}

function addRule() {
  const newRule: WorkflowRuleDTO = {
    id: `pending-${Date.now()}`,
    factoryId: props.factoryId,
    workflowId: props.workflowId,
    nodeId: props.nodeId,
    ruleType: 'AMOUNT_THRESHOLD',
    expression: JSON.stringify({ field: 'amount', op: '>', value: 0 }),
    priority: rules.value.length,
    enabled: true,
  }
  rules.value.push(newRule)
}

function onRuleChange(idx: number, updated: WorkflowRuleDTO) {
  rules.value[idx] = updated
}

async function onDelete(rule: WorkflowRuleDTO) {
  try {
    await ElMessageBox.confirm(`确认删除规则 "${rule.description || rule.id}"?`, '删除规则', {
      type: 'warning',
    })
  } catch {
    return
  }
  // pending (not yet saved) — local remove
  if (rule.id.startsWith('pending-')) {
    rules.value = rules.value.filter((r) => r.id !== rule.id)
    return
  }
  try {
    await deleteRule(props.factoryId, rule.id)
    rules.value = rules.value.filter((r) => r.id !== rule.id)
    ElMessage.success('规则已删除')
  } catch (e) {
    ElMessage.error(`删除失败: ${(e as Error).message}`)
  }
}

function onTest(rule: WorkflowRuleDTO) {
  if (rule.id.startsWith('pending-')) {
    ElMessage.warning('请先保存规则再测试')
    return
  }
  testingRule.value = rule
  testModalVisible.value = true
}

async function saveAll() {
  saving.value = true
  try {
    for (const r of rules.value) {
      const payload: WorkflowRuleRequest = {
        workflowId: r.workflowId,
        nodeId: r.nodeId,
        edgeId: r.edgeId,
        ruleType: r.ruleType,
        expression: parseExpr(r.expression),
        trueTargetNodeId: r.trueTargetNodeId,
        falseTargetNodeId: r.falseTargetNodeId,
        priority: r.priority,
        enabled: r.enabled,
        description: r.description,
      }
      if (r.id.startsWith('pending-')) {
        const resp = (await createRule(props.factoryId, payload)) as unknown as ApiResponse<WorkflowRuleDTO>
        if (resp.success && resp.data) {
          r.id = resp.data.id
        }
      } else {
        await updateRule(props.factoryId, r.id, payload)
      }
    }
    ElMessage.success(`已保存 ${rules.value.length} 条规则`)
    emit('saved')
    await loadRules()
  } catch (e) {
    ElMessage.error(`保存失败: ${(e as Error).message}`)
  } finally {
    saving.value = false
  }
}

function parseExpr(raw: string | WorkflowRuleExpression): WorkflowRuleExpression {
  if (typeof raw === 'string') {
    try {
      return JSON.parse(raw) as WorkflowRuleExpression
    } catch {
      return {} as WorkflowRuleExpression
    }
  }
  return raw
}
</script>

<style scoped>
.rules-toolbar {
  margin-bottom: 12px;
}
.role-directory-alert {
  margin-top: 12px;
}
.rules-list {
  min-height: 200px;
}
</style>
