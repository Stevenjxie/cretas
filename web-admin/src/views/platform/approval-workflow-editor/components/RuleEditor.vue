<template>
  <el-card class="rule-editor" :body-style="{ padding: '12px' }">
    <template #header>
      <div class="rule-header">
        <span class="rule-title">
          规则 #{{ index + 1 }} — {{ RULE_TYPE_LABELS[localRule.ruleType] }}
        </span>
        <div class="rule-actions">
          <el-switch
            v-model="localRule.enabled"
            size="small"
            aria-label="启用规则"
            @change="emitChange"
          />
          <el-button v-if="!businessMode" size="small" type="primary" link @click="$emit('test')">测试</el-button>
          <el-button size="small" type="danger" link @click="$emit('delete')">删除</el-button>
        </div>
      </div>
    </template>

    <el-form label-position="top" size="small" @submit.prevent>
      <el-form-item label="规则类型">
        <el-select
          v-model="localRule.ruleType"
          :disabled="businessMode && localRule.ruleType === 'SPEL_CUSTOM'"
          @change="onRuleTypeChange"
        >
          <el-option
            v-for="t in availableRuleTypes"
            :key="t"
            :value="t"
            :label="RULE_TYPE_LABELS[t]"
          />
        </el-select>
      </el-form-item>

      <!-- AMOUNT_THRESHOLD -->
      <template v-if="localRule.ruleType === 'AMOUNT_THRESHOLD'">
        <el-form-item label="字段名">
          <el-input
            :model-value="(expr as AmountThresholdExpr).field || 'amount'"
            placeholder="如: amount / total / subtotal"
            @change="(v: string | number) => updateExpr({ field: String(v) || 'amount' })"
          />
        </el-form-item>
        <el-form-item label="操作">
          <el-select
            :model-value="(expr as AmountThresholdExpr).op"
            @change="(v: AmountThresholdExpr['op']) => updateExpr({ op: v })"
          >
            <el-option v-for="op in AMOUNT_OPS" :key="op.value" :value="op.value" :label="op.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="阈值">
          <el-input-number
            :model-value="toNumber((expr as AmountThresholdExpr).value)"
            :min="0" :step="1000"
            @change="(v: number | undefined) => updateExpr({ value: v ?? 0 })"
            style="width: 100%"
          />
        </el-form-item>
      </template>

      <!-- DEPT_MATCH / ROLE_MATCH 共享 -->
      <template v-if="localRule.ruleType === 'DEPT_MATCH' || localRule.ruleType === 'ROLE_MATCH'">
        <el-form-item :label="localRule.ruleType === 'DEPT_MATCH' ? '部门列表' : '角色列表'">
          <el-select
            :model-value="(expr as InListExpr).in || []"
            multiple filterable
            placeholder="请选择…"
            style="width: 100%"
            @change="(v: string[]) => updateExpr({ in: v })"
          >
            <el-option
              v-for="opt in (localRule.ruleType === 'DEPT_MATCH' ? DEPT_OPTIONS : roleOptions)"
              :key="opt.value"
              :value="opt.value"
              :label="opt.label"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="(expr as InListExpr).field && !businessMode" label="字段名 (可选)">
          <el-input
            :model-value="(expr as InListExpr).field"
            placeholder="默认 department / role"
            @change="(v: string | number) => updateExpr({ field: String(v) })"
          />
        </el-form-item>
      </template>

      <!-- SPEL_CUSTOM -->
      <template v-if="localRule.ruleType === 'SPEL_CUSTOM' && !businessMode">
        <el-form-item label="SpEL 表达式">
          <el-input
            type="textarea" :rows="3"
            :model-value="(expr as SpelExpr).spel || ''"
            placeholder="如: #amount > 10000 && #department == 'finance'"
            @change="(v: string | number) => updateExpr({ spel: String(v) })"
          />
 <div class="hint"> 受 sandbox 保护; 拒绝 T()/Runtime/Process/Class/@/new</div>
        </el-form-item>
      </template>
      <el-alert
        v-else-if="localRule.ruleType === 'SPEL_CUSTOM'"
        type="warning"
        :closable="false"
        show-icon
        title="这是一条高级判断规则"
        description="业务配置页不会展示或修改表达式。需要调整时请由平台管理员在高级模式处理。"
      />

      <el-divider />

      <el-form-item label="命中后路由 (true target)">
        <el-select
          v-model="localRule.trueTargetNodeId"
          placeholder="选择目标节点"
          clearable
          filterable
          @change="emitChange"
        >
          <el-option
            v-for="n in candidateNodes"
            :key="n.id"
            :value="n.id"
            :label="`${n.label || n.id} (${n.type})`"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="未命中路由 (false target, 可空 = fall through)">
        <el-select
          v-model="localRule.falseTargetNodeId"
          placeholder="(可空) 继续评估下一规则或 edge"
          clearable
          filterable
          @change="emitChange"
        >
          <el-option
            v-for="n in candidateNodes"
            :key="n.id"
            :value="n.id"
            :label="`${n.label || n.id} (${n.type})`"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="优先级 (数值越小越优先)">
        <el-input-number v-model="localRule.priority" :min="0" :max="999" @change="emitChange" />
      </el-form-item>

      <el-form-item label="说明 (UI 显示)">
        <el-input
          v-model="localRule.description"
          type="textarea" :rows="2"
          placeholder="如: 金额超 10w 走总裁审批"
          @change="emitChange"
        />
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import {
  RULE_TYPE_LABELS,
  AMOUNT_OPS,
  type WorkflowRuleDTO,
  type WorkflowRuleType,
  type WorkflowRuleExpression,
  type AmountThresholdExpr,
  type InListExpr,
  type SpelExpr,
} from '@/api/workflowRule'
import type { ApprovalWorkflowNode } from '@/api/approvalWorkflow'
import type { DirectoryOption } from '../lib/approvalDirectory'

const props = defineProps<{
  rule: WorkflowRuleDTO
  index: number
  candidateNodes: ApprovalWorkflowNode[]
  businessMode?: boolean
  roleOptions: DirectoryOption[]
}>()

const emit = defineEmits<{ change: [rule: WorkflowRuleDTO]; delete: []; test: [] }>()

const RULE_TYPES: WorkflowRuleType[] = ['AMOUNT_THRESHOLD', 'DEPT_MATCH', 'ROLE_MATCH', 'SPEL_CUSTOM']
const availableRuleTypes = computed(() => (
  props.businessMode
    ? RULE_TYPES.filter((type) => type !== 'SPEL_CUSTOM')
    : RULE_TYPES
))

const DEPT_OPTIONS = [
  { value: 'finance', label: '财务部' },
  { value: 'purchasing', label: '采购部' },
  { value: 'sales', label: '销售部' },
  { value: 'production', label: '生产部' },
  { value: 'quality', label: '质量部' },
  { value: 'warehouse', label: '仓储部' },
  { value: 'hr', label: '人事部' },
]
// 拷贝一份做 reactive local edit
const localRule = reactive<WorkflowRuleDTO>({ ...props.rule })

// expression parsed object (since backend stores JSONB string)
const expr = computed<WorkflowRuleExpression>(() => {
  try {
    if (typeof localRule.expression === 'string') {
      return JSON.parse(localRule.expression) as WorkflowRuleExpression
    }
    return localRule.expression as WorkflowRuleExpression
  } catch {
    return {} as WorkflowRuleExpression
  }
})

// 父组件传入新 rule 时 sync 本地 state
watch(
  () => props.rule,
  (r) => Object.assign(localRule, r),
  { deep: true },
)

function emitChange() {
  emit('change', { ...localRule })
}

function updateExpr(patch: Partial<AmountThresholdExpr & InListExpr & SpelExpr>) {
  const current = expr.value as Record<string, unknown>
  const next = { ...current, ...patch }
  localRule.expression = JSON.stringify(next)
  emitChange()
}

function onRuleTypeChange(t: WorkflowRuleType) {
  // 切换 ruleType 时重置 expression 到 type 默认 shape
  const defaultExpr: Record<WorkflowRuleType, WorkflowRuleExpression> = {
    AMOUNT_THRESHOLD: { field: 'amount', op: '>', value: 0 } as AmountThresholdExpr,
    DEPT_MATCH: { in: [] } as InListExpr,
    ROLE_MATCH: { in: [] } as InListExpr,
    SPEL_CUSTOM: { spel: '' } as SpelExpr,
  }
  localRule.expression = JSON.stringify(defaultExpr[t])
  emitChange()
}

function toNumber(v: unknown): number {
  if (v === null || v === undefined) return 0
  if (typeof v === 'number') return v
  const n = Number(v)
  return isNaN(n) ? 0 : n
}
</script>

<style scoped>
.rule-editor {
  margin-bottom: 12px;
}
.rule-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.rule-title {
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.rule-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
</style>
