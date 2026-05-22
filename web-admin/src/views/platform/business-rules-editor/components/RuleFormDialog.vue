<!--
  RuleFormDialog.vue — Canvas-Rules Phase 4a
  Create/edit dialog for BusinessRule.
  Per fool-proof Rule 2 (context): dialog header shows scope + ruleCode.
  Per fool-proof Rule 3 (constrained inputs): actionType is radio with helper templates
  for actionConfigJson based on selection.
-->
<template>
  <el-dialog
    :model-value="visible"
    :title="dialogTitle"
    width="720px"
    :close-on-click-modal="false"
    @update:model-value="$emit('update:visible', $event)"
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="formRules"
      label-width="120px"
      label-position="right"
      size="default"
    >
      <el-form-item label="规则代码" prop="ruleCode" required>
        <el-input
          v-model="form.ruleCode"
          placeholder="po_blacklist (unique within factory)"
          :disabled="mode === 'edit'"
          maxlength="100"
        />
      </el-form-item>

      <el-form-item label="规则名称" prop="ruleName">
        <el-input
          v-model="form.ruleName"
          placeholder="例: 黑名单供应商拒单"
          maxlength="255"
        />
      </el-form-item>

      <el-form-item label="Scope" prop="scope" required>
        <el-select v-model="form.scope" placeholder="选择规则作用域">
          <el-option label="订单 (ORDER) — 采购/销售下单" value="ORDER" />
          <el-option label="库存 (INVENTORY) — 库存变动" value="INVENTORY" />
          <el-option label="客户 (CUSTOMER) — 客户更新/升级" value="CUSTOMER" />
          <el-option label="自定义 (CUSTOM) — 其他场景" value="CUSTOM" />
        </el-select>
      </el-form-item>

      <el-form-item label="条件 (SpEL)" prop="conditionSpel">
        <el-input
          v-model="form.conditionSpel"
          type="textarea"
          :rows="3"
          :placeholder="conditionSpelPlaceholder"
        />
        <div class="form-hint">
          <strong>SpEL 变量</strong>:
          <code>#input</code> (通用, 任何 scope 均可用)
          <span v-if="form.scope === 'ORDER'">+ <code>#order</code> (订单别名, 同 #input)</span>
          <span v-else-if="form.scope === 'INVENTORY'">+ <code>#inventory</code> (库存别名, 同 #input)</span>
          <span v-else-if="form.scope === 'CUSTOMER'">+ <code>#customer</code> (客户别名, 同 #input)</span>
          <span v-else>(CUSTOM scope 只能用 #input)</span>.
          <br />
          <strong>示例</strong>:
          <code v-if="form.scope === 'ORDER'">#order.amount &gt; 100000</code>
          <code v-else-if="form.scope === 'INVENTORY'">#inventory.quantity &lt; 10</code>
          <code v-else-if="form.scope === 'CUSTOMER'">#customer.tier == 'VIP'</code>
          <code v-else>#input.amount &gt; 100</code>,
          <code>#input['items'][0]['unitPrice'] &gt; 100</code>.
          空条件 = 总是 true (匹配所有 input)。
        </div>
      </el-form-item>

      <el-form-item label="动作类型" prop="actionType" required>
        <el-radio-group v-model="form.actionType" @change="onActionTypeChange">
          <el-radio-button value="LOG">LOG 记录日志</el-radio-button>
          <el-radio-button value="REJECT">REJECT 拒绝</el-radio-button>
          <el-radio-button value="MODIFY">MODIFY 修改字段</el-radio-button>
          <el-radio-button value="TRIGGER_WORKFLOW">TRIGGER_WORKFLOW 触发流程</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item label="动作配置 (JSON)" prop="actionConfigJsonText">
        <el-input
          v-model="actionConfigJsonText"
          type="textarea"
          :rows="6"
          :placeholder="actionConfigPlaceholder"
        />
        <div class="form-hint">
          <strong>示例配置</strong>:
          <pre class="json-example">{{ actionConfigExample }}</pre>
          <span v-if="form.actionType === 'TRIGGER_WORKFLOW'" class="warning-text">
            TRIGGER_WORKFLOW 依赖 Phase 1 Canvas-Workflow 合并后才能实际触发。当前可保存规则，执行时会失败。
          </span>
        </div>
      </el-form-item>

      <el-form-item label="优先级" prop="priority">
        <el-input-number
          v-model="form.priority"
          :min="1"
          :max="9999"
          controls-position="right"
        />
        <span class="form-hint-inline">越小越早执行 (default 100)。REJECT 会短路后续规则。</span>
      </el-form-item>

      <el-form-item label="启用" prop="enabled">
        <el-switch v-model="form.enabled" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="$emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch, computed, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createBusinessRule,
  updateBusinessRule,
} from '@/api/businessRuleApi'
import type {
  BusinessRule,
  RuleActionType,
  RuleScope,
} from '@/api/businessRuleApi'

const props = defineProps<{
  visible: boolean
  factoryId: string
  rule: BusinessRule | null
  mode: 'create' | 'edit'
}>()

const emit = defineEmits<{
  (e: 'update:visible', v: boolean): void
  (e: 'saved'): void
}>()

interface FormState {
  ruleCode: string
  ruleName: string
  scope: RuleScope
  conditionSpel: string
  actionType: RuleActionType
  priority: number
  enabled: boolean
}

function emptyForm(): FormState {
  return {
    ruleCode: '',
    ruleName: '',
    scope: 'ORDER',
    conditionSpel: '',
    actionType: 'LOG',
    priority: 100,
    enabled: true,
  }
}

const form = ref<FormState>(emptyForm())
const actionConfigJsonText = ref<string>('{}')
const saving = ref(false)
const formRef = ref<FormInstance>()

const dialogTitle = computed(() => {
  if (props.mode === 'create') return '新建业务规则'
  return `编辑规则 — ${form.value.ruleCode || ''}`
})

// Sync incoming prop -> form
// B-BR3 UX polish (2026-05-20): clear validation residuals from previous open
// (e.g. user closed dialog after a 409 dup-ruleCode validation error — re-opening
// shouldn't show stale red errors before user has even typed anything). Uses
// nextTick because formRef binds AFTER the watch runs on dialog mount.
watch(
  () => [props.visible, props.rule],
  async () => {
    if (!props.visible) return
    if (props.rule) {
      form.value = {
        ruleCode: props.rule.ruleCode ?? '',
        ruleName: props.rule.ruleName ?? '',
        scope: props.rule.scope ?? 'ORDER',
        conditionSpel: props.rule.conditionSpel ?? '',
        actionType: props.rule.actionType ?? 'LOG',
        priority: props.rule.priority ?? 100,
        enabled: props.rule.enabled ?? true,
      }
      const cfg = props.rule.actionConfigJson ?? {}
      try {
        actionConfigJsonText.value = JSON.stringify(cfg, null, 2)
      } catch {
        actionConfigJsonText.value = '{}'
      }
    } else {
      form.value = emptyForm()
      actionConfigJsonText.value = JSON.stringify(actionConfigDefault('LOG'), null, 2)
    }
    // Wait for DOM update so formRef is bound, then clear validation residuals.
    await nextTick()
    formRef.value?.clearValidate()
  },
  { immediate: true },
)

// Default action config payload by actionType (per spec §2.2)
function actionConfigDefault(t: RuleActionType): Record<string, unknown> {
  switch (t) {
    case 'LOG':
      return { level: 'INFO', message: '规则匹配' }
    case 'REJECT':
      return {
        reason: '业务规则禁止此操作',
        actionHint: '请检查输入或联系管理员',
        severity: 'WARNING',
      }
    case 'MODIFY':
      return { field: 'discount', value: 0.05 }
    case 'TRIGGER_WORKFLOW':
      return { workflowCode: 'TRANSFER_REQUEST', ctx: {} }
    default:
      return {}
  }
}

const actionConfigExample = computed(() => {
  return JSON.stringify(actionConfigDefault(form.value.actionType), null, 2)
})

// B-BR1 fix: scope-aware placeholder so the example uses the alias that actually resolves.
const conditionSpelPlaceholder = computed(() => {
  switch (form.value.scope) {
    case 'ORDER':
      return "例: #order.amount > 100000 (#order 是 #input 的别名, 任选其一)"
    case 'INVENTORY':
      return "例: #inventory.quantity < 10"
    case 'CUSTOMER':
      return "例: #customer.tier == 'VIP'"
    case 'CUSTOM':
    default:
      return "例: #input.amount > 100 (CUSTOM scope 仅 #input 可用)"
  }
})

const actionConfigPlaceholder = computed(() => {
  switch (form.value.actionType) {
    case 'LOG':
      return '{"level":"INFO","message":"高额订单 #{ruleCode}"}'
    case 'REJECT':
      return '{"reason":"...","actionHint":"...","severity":"WARNING|BLOCKING"}'
    case 'MODIFY':
      return '{"field":"discount","value":0.05}  或  {"field":"...","valueSpel":"..."}'
    case 'TRIGGER_WORKFLOW':
      return '{"workflowCode":"TRANSFER_REQUEST","ctx":{...}}'
    default:
      return '{}'
  }
})

function onActionTypeChange(v: string | number | boolean | undefined) {
  if (typeof v !== 'string') return
  // Replace the JSON template when switching actionType, but only if user hasn't edited it (best-effort)
  actionConfigJsonText.value = JSON.stringify(actionConfigDefault(v as RuleActionType), null, 2)
}

const formRules = computed<FormRules>(() => ({
  ruleCode: [
    { required: true, message: '请填写规则代码', trigger: 'blur' },
    {
      pattern: /^[a-z0-9_]{1,100}$/,
      message: '规则代码: lowercase + digits + underscore, 最长 100',
      trigger: 'blur',
    },
  ],
  scope: [{ required: true, message: '请选择 Scope', trigger: 'change' }],
  actionType: [{ required: true, message: '请选择动作类型', trigger: 'change' }],
}))

async function onSave() {
  if (!formRef.value) return
  // Validate form
  const ok = await formRef.value.validate().catch(() => false)
  if (!ok) return
  // Validate JSON
  let parsedConfig: Record<string, unknown>
  try {
    parsedConfig = JSON.parse(actionConfigJsonText.value || '{}')
    if (typeof parsedConfig !== 'object' || parsedConfig === null) {
      throw new Error('actionConfigJson 必须是 JSON 对象')
    }
  } catch (e) {
    const msg = e instanceof Error ? e.message : 'JSON 格式错误'
    ElMessage.error(`actionConfigJson JSON 解析失败: ${msg}`)
    return
  }

  saving.value = true
  try {
    const body: Partial<BusinessRule> = {
      ruleCode: form.value.ruleCode,
      ruleName: form.value.ruleName,
      scope: form.value.scope,
      conditionSpel: form.value.conditionSpel,
      actionType: form.value.actionType,
      actionConfigJson: parsedConfig,
      priority: form.value.priority,
      enabled: form.value.enabled,
    }
    if (props.mode === 'create') {
      await createBusinessRule(props.factoryId, body)
      ElMessage.success('规则已创建')
    } else if (props.rule?.id) {
      await updateBusinessRule(props.factoryId, props.rule.id, body)
      ElMessage.success('规则已更新')
    }
    emit('saved')
  } catch (e) {
    // Interceptor toasts; just log
    console.error('[save rule]', e)
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.form-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
  line-height: 1.6;
}
.form-hint code {
  background: var(--el-fill-color);
  padding: 1px 4px;
  border-radius: 3px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 11px;
}
.json-example {
  background: var(--el-fill-color-light);
  padding: 6px 8px;
  border-radius: 3px;
  font-size: 11px;
  margin: 4px 0;
  max-width: 100%;
  overflow-x: auto;
}
.warning-text {
  color: var(--el-color-warning);
  display: block;
  margin-top: 4px;
}
.form-hint-inline {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
