<!--
  RulesList.vue — Canvas-Rules Phase 4a
  Groups business rules by scope (ORDER/INVENTORY/CUSTOMER/CUSTOM), sorted by priority ASC.
  Per fool-proof Rule 2 (context required), each table column shows ruleCode + ruleName.
  Per fool-proof Rule 5 (empty state → action), empty groups show "+ 新建" CTA.
-->
<template>
  <div class="rules-list">
    <!-- Header bar -->
    <div class="header-bar">
      <el-select
        v-model="scopeFilter"
        placeholder="全部 Scope"
        clearable
        size="small"
        style="width: 160px"
        @change="load"
      >
        <el-option label="订单 (ORDER)" value="ORDER" />
        <el-option label="库存 (INVENTORY)" value="INVENTORY" />
        <el-option label="客户 (CUSTOMER)" value="CUSTOMER" />
        <el-option label="自定义 (CUSTOM)" value="CUSTOM" />
      </el-select>
      <el-button size="small" @click="load">刷新</el-button>
      <el-button type="primary" size="small" @click="openCreate">+ 新建规则</el-button>
    </div>

    <!-- Loading -->
    <div v-if="loading" v-loading="loading" class="loading-area" />

    <!-- Empty (whole list) -->
    <div v-else-if="rules.length === 0" class="empty-state">
      <el-empty description="当前工厂还没有业务规则">
        <el-button type="primary" @click="openCreate">+ 新建第一条规则</el-button>
      </el-empty>
    </div>

    <!-- Grouped by scope -->
    <template v-else>
      <div
        v-for="scope in scopesInOrder"
        :key="scope"
        class="scope-group"
      >
        <div class="scope-group-header">
          <span class="scope-title">{{ scopeLabel(scope) }} ({{ scope }})</span>
          <span class="scope-count">共 {{ groupedRules[scope].length }} 条</span>
        </div>
        <el-table
          v-if="groupedRules[scope].length > 0"
          :data="groupedRules[scope]"
          border
          size="small"
          stripe
        >
          <el-table-column label="优先级" prop="priority" width="80" align="center" />
          <el-table-column label="规则代码" prop="ruleCode" width="180">
            <template #default="{ row }">
              <span class="rule-code">{{ row.ruleCode }}</span>
            </template>
          </el-table-column>
          <el-table-column label="规则名称" prop="ruleName" min-width="160" />
          <el-table-column label="动作" width="120">
            <template #default="{ row }">
              <el-tag :type="actionTagType(row.actionType)" size="small">
                {{ actionLabel(row.actionType) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="启用" width="80" align="center">
            <template #default="{ row }">
              <el-switch
                :model-value="row.enabled"
                @change="(v: boolean) => onToggle(row, v)"
              />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="240" align="center">
            <template #default="{ row }">
              <el-button link size="small" @click="openEdit(row)">编辑</el-button>
              <el-button link size="small" type="primary" @click="openTest(row)">测试</el-button>
              <el-button link size="small" @click="emit('select-logs', row.id!)">日志</el-button>
              <el-button link size="small" type="danger" @click="onDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-else class="empty-scope">
          <el-button size="small" @click="openCreateForScope(scope)">+ 在 {{ scopeLabel(scope) }} 中新建</el-button>
        </div>
      </div>
    </template>

    <!-- Form Dialog (create/edit) -->
    <RuleFormDialog
      v-if="formVisible"
      v-model:visible="formVisible"
      :factory-id="factoryId"
      :rule="formRule"
      :mode="formMode"
      @saved="onSaved"
    />

    <!-- Test Dialog -->
    <RuleTestModal
      v-if="testVisible"
      v-model:visible="testVisible"
      :factory-id="factoryId"
      :rule="testRule"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listBusinessRules,
  toggleBusinessRule,
  deleteBusinessRule,
  SCOPE_LABELS,
  ACTION_TYPE_LABELS,
} from '@/api/businessRuleApi'
import type { BusinessRule, RuleScope, RuleActionType } from '@/api/businessRuleApi'
import RuleFormDialog from './RuleFormDialog.vue'
import RuleTestModal from './RuleTestModal.vue'

const props = defineProps<{
  factoryId: string
}>()

const emit = defineEmits<{
  (e: 'select-logs', ruleId: string): void
}>()

const rules = ref<BusinessRule[]>([])
const loading = ref(false)
const scopeFilter = ref<RuleScope | ''>('')

// Form dialog state
const formVisible = ref(false)
const formRule = ref<BusinessRule | null>(null)
const formMode = ref<'create' | 'edit'>('create')

// Test dialog state
const testVisible = ref(false)
const testRule = ref<BusinessRule | null>(null)

// Display order for scope groups
const scopesInOrder: RuleScope[] = ['ORDER', 'INVENTORY', 'CUSTOMER', 'CUSTOM']

const groupedRules = computed<Record<RuleScope, BusinessRule[]>>(() => {
  const groups: Record<RuleScope, BusinessRule[]> = {
    ORDER: [],
    INVENTORY: [],
    CUSTOMER: [],
    CUSTOM: [],
  }
  for (const r of rules.value) {
    if (groups[r.scope]) groups[r.scope].push(r)
  }
  // Sort within each scope by priority ASC (smaller = earlier)
  for (const k of scopesInOrder) {
    groups[k].sort((a, b) => (a.priority ?? 100) - (b.priority ?? 100))
  }
  return groups
})

function scopeLabel(s: RuleScope): string {
  return SCOPE_LABELS[s] ?? s
}
function actionLabel(a: RuleActionType): string {
  return ACTION_TYPE_LABELS[a] ?? a
}
function actionTagType(a: RuleActionType): 'success' | 'danger' | 'warning' | 'info' {
  switch (a) {
    case 'REJECT':
      return 'danger'
    case 'MODIFY':
      return 'warning'
    case 'TRIGGER_WORKFLOW':
      return 'success'
    case 'LOG':
    default:
      return 'info'
  }
}

async function load() {
  if (!props.factoryId) return
  loading.value = true
  try {
    const res = await listBusinessRules(
      props.factoryId,
      scopeFilter.value === '' ? undefined : (scopeFilter.value as RuleScope),
    )
    rules.value = (res.data || []) as BusinessRule[]
  } catch (e) {
    // Interceptor displays specific toast
    console.error('[listBusinessRules]', e)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  formRule.value = null
  formMode.value = 'create'
  formVisible.value = true
}

function openCreateForScope(scope: RuleScope) {
  formRule.value = {
    ruleCode: '',
    scope,
    actionType: 'LOG',
    priority: 100,
    enabled: true,
    actionConfigJson: {},
  }
  formMode.value = 'create'
  formVisible.value = true
}

function openEdit(rule: BusinessRule) {
  formRule.value = { ...rule, actionConfigJson: { ...(rule.actionConfigJson || {}) } }
  formMode.value = 'edit'
  formVisible.value = true
}

function openTest(rule: BusinessRule) {
  testRule.value = rule
  testVisible.value = true
}

async function onToggle(rule: BusinessRule, _newVal: boolean) {
  if (!rule.id) return
  try {
    await toggleBusinessRule(props.factoryId, rule.id)
    ElMessage.success(`规则 ${rule.ruleCode} 状态已切换`)
    await load()
  } catch (e) {
    console.error('[toggleBusinessRule]', e)
  }
}

async function onDelete(rule: BusinessRule) {
  if (!rule.id) return
  // Per fool-proof Rule 2 (context): show ruleCode + ruleName in confirm dialog
  try {
    await ElMessageBox.confirm(
      `确定删除规则 "${rule.ruleName || rule.ruleCode}" (${rule.ruleCode}) 吗？此操作为软删除，可通过数据库恢复。`,
      '删除规则',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger',
      },
    )
  } catch {
    return // user canceled
  }
  try {
    await deleteBusinessRule(props.factoryId, rule.id)
    ElMessage.success(`规则 ${rule.ruleCode} 已删除`)
    await load()
  } catch (e) {
    console.error('[deleteBusinessRule]', e)
  }
}

function onSaved() {
  formVisible.value = false
  load()
}

onMounted(load)
</script>

<style scoped>
.rules-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.header-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}
.loading-area {
  height: 200px;
}
.empty-state {
  padding: 40px 0;
}
.scope-group {
  border: 1px solid var(--el-border-color-light);
  border-radius: 4px;
  padding: 8px;
  background: var(--el-fill-color-blank);
}
.scope-group-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 8px 8px;
  font-weight: 500;
}
.scope-title {
  color: var(--el-text-color-primary);
}
.scope-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.rule-code {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
}
.empty-scope {
  padding: 12px;
  text-align: center;
  color: var(--el-text-color-secondary);
}
</style>
