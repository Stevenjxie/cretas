<!--
  RuleFormDialog.vue — 创建/编辑 告警规则 form dialog.

  Form fields (per spec §3 schema + §4 mockup):
    - alertType        : el-select (8 types, locked when 编辑模式)
    - ruleName         : el-input (required, unique per factory)
    - triggerConditionSpel : el-input textarea (SpEL, placeholder hints by type)
    - severity         : el-radio-group (LOW / MID / HIGH)
    - enabled          : el-switch
    - notifyChannels   : el-checkbox-group (5 channels)
    - notifyRoles      : el-input tag input (用户输入角色 code, 逗号分隔)

  Fool-proof design (per .claude/rules/fool-proof-design.md):
    - Rule 1: ruleName 输入实时校验空 + 长度
    - Rule 2: dialog title 带规则名 + 类型上下文
    - Rule 3: severity 用 radio (不让用户瞎写); alertType 用 select
    - Rule 4: 由后端检测重名 (409), 前端 catch + 不重复 close 让用户改名
-->
<template>
  <el-dialog
    :model-value="props.visible"
    :title="dialogTitle"
    width="600px"
    :close-on-click-modal="false"
    append-to-body
    @update:model-value="(v: boolean) => emit('update:visible', v)"
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="120px"
      @submit.prevent
    >
      <el-form-item label="告警类型" prop="alertType" required>
        <el-select
          v-model="form.alertType"
          placeholder="选择 8 种业务预警类型"
          :disabled="isEditing"
          style="width: 100%"
          @change="onAlertTypeChange"
        >
          <el-option
            v-for="t in ALERT_TYPES"
            :key="t"
            :label="`${ALERT_TYPE_LABELS[t]} (${t})`"
            :value="t"
          />
        </el-select>
        <div v-if="isEditing" class="hint muted">
          编辑模式下类型不可改 — 如需切类型, 请删除当前规则并创建新规则
        </div>
      </el-form-item>

      <el-form-item label="规则名称" prop="ruleName" required>
        <el-input
          v-model="form.ruleName"
          placeholder="如: 冻猪蹄低库存预警"
          maxlength="255"
          show-word-limit
          clearable
        />
      </el-form-item>

      <el-form-item label="触发条件 (SpEL)" prop="triggerConditionSpel">
        <el-input
          v-model="form.triggerConditionSpel"
          type="textarea"
          :rows="3"
          :placeholder="spelPlaceholder"
          spellcheck="false"
        />
        <div class="hint muted">
          留空 = 业务事件命中即触发. 非空 = 业务事件 + SpEL eval=true 才触发.
          表达式可引用 <code>#context.{field}</code> (字段由 alert type 决定).
        </div>
      </el-form-item>

      <el-form-item label="严重度" prop="severity" required>
        <el-radio-group v-model="form.severity">
          <el-radio-button value="LOW">低 (仅站内)</el-radio-button>
          <el-radio-button value="MID">中 (站内 + 渠道)</el-radio-button>
          <el-radio-button value="HIGH">高 (全渠道 + 升级)</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item label="启用" prop="enabled">
        <el-switch v-model="form.enabled" />
        <span class="hint muted" style="margin-left: 12px">
          禁用后, 此规则不会触发任何事件 (历史事件保留).
        </span>
      </el-form-item>

      <el-form-item label="通知渠道" prop="notifyChannels">
        <el-checkbox-group v-model="form.notifyChannels">
          <el-checkbox
            v-for="ch in NOTIFY_CHANNELS"
            :key="ch"
            :value="ch"
            border
          >
            {{ NOTIFY_CHANNEL_LABELS[ch] }}
            <span v-if="ch !== 'IN_APP'" class="muted">(Phase 3)</span>
          </el-checkbox>
        </el-checkbox-group>
        <div class="hint muted">
          Phase 2 仅站内消息 (IN_APP) 已工作, 其他渠道占位 (Phase 3 接 SDK).
        </div>
      </el-form-item>

      <el-form-item label="通知角色" prop="notifyRoles">
        <!-- Tag input — 用户输入角色 code (逗号分隔 / 回车确认). -->
        <el-input
          v-model="rolesInputBuffer"
          placeholder="输入角色 code 后按 回车 / 逗号 添加, 如: factory_super_admin"
          @keydown.enter.prevent="onRolesEnter"
          @keydown="onRolesKeyDown"
        >
          <template #append>
            <el-button :icon="Plus" @click="onRolesEnter">添加</el-button>
          </template>
        </el-input>
        <div v-if="form.notifyRoles.length > 0" class="role-tags">
          <el-tag
            v-for="r in form.notifyRoles"
            :key="r"
            type="primary"
            closable
            @close="removeRole(r)"
          >
            {{ r }}
          </el-tag>
        </div>
        <div class="hint muted">
          常用: factory_super_admin / permission_admin / procurement_manager /
          sales_manager / quality_inspector / warehouse_keeper
        </div>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="closeDialog">取消</el-button>
      <el-button type="primary" :loading="saving" @click="onSave">
        {{ isEditing ? '保存' : '创建' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch, reactive } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  createAlertRule,
  updateAlertRule,
  ALERT_TYPES,
  ALERT_TYPE_LABELS,
  ALERT_TYPE_SPEL_HINTS,
  NOTIFY_CHANNELS,
  NOTIFY_CHANNEL_LABELS,
  type AlertRule,
  type AlertType,
  type AlertSeverity,
  type NotifyChannel,
  type CreateAlertRuleRequest,
  type UpdateAlertRuleRequest,
} from '@/api/alertRuleApi'

// ==================== Props / Emits ====================

const props = defineProps<{
  visible: boolean
  factoryId: string
  /** null = 创建模式; non-null = 编辑模式 */
  editingRule: AlertRule | null
}>()

const emit = defineEmits<{
  'update:visible': [v: boolean]
  saved: [rule: AlertRule]
}>()

// ==================== Form state ====================

interface FormState {
  alertType: AlertType
  ruleName: string
  triggerConditionSpel: string
  severity: AlertSeverity
  enabled: boolean
  notifyChannels: NotifyChannel[]
  notifyRoles: string[]
}

const defaultForm = (): FormState => ({
  alertType: 'INVENTORY_LOW',
  ruleName: '',
  triggerConditionSpel: '',
  severity: 'MID',
  enabled: true,
  notifyChannels: ['IN_APP'],
  notifyRoles: [],
})

const form = reactive<FormState>(defaultForm())
const formRef = ref<FormInstance>()
const saving = ref(false)
const rolesInputBuffer = ref('')

const isEditing = computed(() => Boolean(props.editingRule))

const dialogTitle = computed(() => {
  if (!isEditing.value) return '新建告警规则'
  const r = props.editingRule!
  return `编辑规则 — ${r.ruleName} (${ALERT_TYPE_LABELS[r.alertType] || r.alertType})`
})

const spelPlaceholder = computed(() => {
  return `示例: ${ALERT_TYPE_SPEL_HINTS[form.alertType] || '#context.someField >= 100'}`
})

const rules: FormRules<FormState> = {
  alertType: [{ required: true, message: '请选择告警类型', trigger: 'change' }],
  ruleName: [
    { required: true, message: '请输入规则名称', trigger: 'blur' },
    { min: 2, max: 255, message: '长度 2 ~ 255 字符', trigger: 'blur' },
  ],
  severity: [{ required: true, message: '请选择严重度', trigger: 'change' }],
}

// ==================== Watch: 同步 props.editingRule → form ====================

watch(
  () => [props.visible, props.editingRule] as const,
  ([visible, rule]) => {
    if (!visible) return
    // Reset form before populating.
    Object.assign(form, defaultForm())
    rolesInputBuffer.value = ''
    if (rule) {
      form.alertType = rule.alertType
      form.ruleName = rule.ruleName
      form.triggerConditionSpel = rule.triggerConditionSpel || ''
      form.severity = rule.severity
      form.enabled = rule.enabled
      form.notifyChannels = [...(rule.notifyChannels || [])]
      form.notifyRoles = [...(rule.notifyRoles || [])]
    }
    // Clear validation residuals from previous open
    formRef.value?.clearValidate()
  },
  { immediate: true },
)

// ==================== Roles tag input ====================

function onRolesEnter() {
  const raw = rolesInputBuffer.value.trim()
  if (!raw) return
  // Allow comma-separated batch entry.
  const parts = raw
    .split(/[,，]/)
    .map((s) => s.trim())
    .filter(Boolean)
  for (const p of parts) {
    if (!form.notifyRoles.includes(p)) form.notifyRoles.push(p)
  }
  rolesInputBuffer.value = ''
}

function onRolesKeyDown(e: KeyboardEvent) {
  // Comma also triggers commit
  if (e.key === ',' || e.key === '，') {
    e.preventDefault()
    onRolesEnter()
  }
}

function removeRole(r: string) {
  form.notifyRoles = form.notifyRoles.filter((x) => x !== r)
}

// ==================== Actions ====================

function onAlertTypeChange() {
  // Reset SpEL hint hint by updating placeholder (computed reactively).
  // No user-set SpEL clear — they may want to keep their custom rule.
}

function closeDialog() {
  emit('update:visible', false)
}

async function onSave() {
  // Validate via FormInstance
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  // Flush any pending tag input
  if (rolesInputBuffer.value.trim()) onRolesEnter()

  saving.value = true
  try {
    if (isEditing.value && props.editingRule) {
      const payload: UpdateAlertRuleRequest = {
        ruleName: form.ruleName,
        triggerConditionSpel: form.triggerConditionSpel || null,
        severity: form.severity,
        enabled: form.enabled,
        notifyChannels: form.notifyChannels,
        notifyRoles: form.notifyRoles,
      }
      const res = await updateAlertRule(props.factoryId, props.editingRule.id, payload)
      if (res.success && res.data) {
        ElMessage.success('告警规则已更新')
        emit('saved', res.data)
      }
    } else {
      const payload: CreateAlertRuleRequest = {
        alertType: form.alertType,
        ruleName: form.ruleName,
        triggerConditionSpel: form.triggerConditionSpel || null,
        severity: form.severity,
        enabled: form.enabled,
        notifyChannels: form.notifyChannels,
        notifyRoles: form.notifyRoles,
      }
      const res = await createAlertRule(props.factoryId, payload)
      if (res.success && res.data) {
        ElMessage.success('告警规则已创建')
        emit('saved', res.data)
      }
    }
  } catch (e) {
    // request.ts already showed sticky error toast (e.g. 409 重名).
    // Don't close dialog — let user fix the issue.
    console.error('[RuleFormDialog] save failed:', e)
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.hint {
  margin-top: 4px;
  font-size: 11px;
  line-height: 1.4;
}

.hint code {
  background: var(--el-fill-color-light);
  padding: 1px 4px;
  border-radius: 2px;
  font-family: 'Consolas', 'Monaco', monospace;
}

.muted {
  color: var(--el-text-color-secondary);
}

.role-tags {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
</style>
