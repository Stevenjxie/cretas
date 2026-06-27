<!--
  TemplateFormDialog — Phase 3 Canvas-Notify, edit / create / test-send.

  fool-proof:
    Rule 1: preview area shows rendered title/body BEFORE test-send (no after-fact error).
    Rule 2: dialog title 含 templateCode + 模式.
    Rule 3: channels 多选 checkbox (避免自由文本).
    Rule 5: 测试发送 缺模板时引导用户先创建.

  @since 2026-05-19
-->
<template>
  <el-dialog
    v-model="dialogVisible"
    :title="dialogTitle"
    width="720px"
    :close-on-click-modal="false"
    @close="onClose"
  >
    <el-form
      v-if="mode !== 'test-send'"
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="120px"
      label-position="right"
    >
      <el-form-item label="编码" prop="templateCode">
        <el-input
          v-model="form.templateCode"
          placeholder="如 PO_APPROVAL_PENDING (字母 + 数字 + 下划线)"
          :disabled="mode === 'edit'"
          maxlength="100"
          show-word-limit
        />
        <div class="hint">业务 key, 创建后不可修改; 工作流 notify 节点通过此 code 绑定模板.</div>
      </el-form-item>

      <el-form-item label="标题" prop="title">
        <el-input
          v-model="form.title"
          placeholder="如 您有 {{count}} 笔待审采购单"
          maxlength="255"
          show-word-limit
        />
        <div class="hint">支持 {{ placeholderSyntax }} 占位符.</div>
      </el-form-item>

      <el-form-item label="正文" prop="bodyTemplate">
        <el-input
          v-model="form.bodyTemplate"
          type="textarea"
          :rows="5"
          placeholder="如 请审核 {{poNumber}}, 金额 {{amount}} 元, 发起人 {{initiatorName}}"
        />
        <div class="hint">支持 {{ placeholderSyntax }} 占位符. 变量未提供时发送会报错 (防止残缺通知).</div>
      </el-form-item>

      <el-form-item label="渠道" prop="channels">
        <el-checkbox-group v-model="form.channels">
          <el-checkbox v-for="ch in ALL_CHANNELS" :key="ch" :label="ch" :value="ch">
            {{ NotifyChannelLabels[ch] }}
          </el-checkbox>
        </el-checkbox-group>
        <div class="hint">至少选 1 个; 工作流 notify 节点会调度全部勾选渠道.</div>
      </el-form-item>

      <el-form-item label="变量 schema">
        <el-input
          v-model="variablesSchemaJsonText"
          type="textarea"
          :rows="3"
          placeholder='{"amount": "number", "poNumber": "string"}'
        />
        <div class="hint">
          JSON 对象描述每个变量的类型. 例: {{ schemaExample }}.
          Auto-detect 占位符: {{ detectedVars.join(', ') || '(无)' }}
        </div>
      </el-form-item>
    </el-form>

    <!-- Test-send section -->
    <div v-if="mode === 'test-send' || (mode === 'edit' && form.id)" class="test-send-section">
      <el-divider v-if="mode === 'edit'" content-position="left">测试发送</el-divider>
      <el-form label-width="120px" label-position="right">
        <el-form-item label="收件用户 ID">
          <el-input
            v-model="testSendRecipientsText"
            placeholder="逗号分隔, 如 101,102 (空则用 -1 dry-run)"
          />
        </el-form-item>
        <el-form-item label="参数 (JSON)">
          <el-input
            v-model="testSendParamsText"
            type="textarea"
            :rows="3"
            placeholder='{"count": 3, "poNumber": "PO-001", "amount": 1000}'
          />
          <div class="hint">必须覆盖模板中所有 {{ placeholderSyntax }} 占位符.</div>
        </el-form-item>

        <!-- Rule 1 preview: 边界预显, 用户提交前看到 -->
        <div v-if="previewTitle || previewBody" class="preview-box">
          <div class="preview-label">预览渲染结果:</div>
          <div class="preview-content">
            <div v-if="previewTitle" class="preview-line"><b>标题:</b> {{ previewTitle }}</div>
            <div v-if="previewBody" class="preview-line"><b>正文:</b> {{ previewBody }}</div>
          </div>
        </div>

        <el-form-item>
          <el-button type="warning" :loading="testSending" @click="handleTestSend">
            测试发送
          </el-button>
        </el-form-item>
      </el-form>

      <div v-if="lastTestSendResults.length > 0" class="test-result-box">
        <div class="preview-label">发送结果:</div>
        <el-table :data="lastTestSendResults" size="small" stripe>
          <el-table-column prop="channel" label="渠道" width="140">
            <template #default="{ row }">{{ NotifyChannelLabels[String(row.channel) as NotifyChannel] || row.channel }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 'SENT' ? 'success' : 'danger'" size="small">
                {{ row.status === 'SENT' ? '已发送' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="errorMsg" label="错误信息" min-width="280" show-overflow-tooltip />
        </el-table>
      </div>
    </div>

    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button v-if="mode !== 'test-send'" type="primary" :loading="saving" @click="handleSave">
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  createTemplate,
  updateTemplate,
  testSend,
  type NotifyTemplate,
  type NotifyChannel,
  type TestSendChannelResult,
  ALL_NOTIFY_CHANNELS,
  NotifyChannelLabels,
} from '@/api/notifyTemplateApi'

interface Props {
  open: boolean
  factoryId: string
  template: NotifyTemplate | null
  mode: 'create' | 'edit' | 'test-send'
}
const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'saved'): void
}>()

const ALL_CHANNELS = ALL_NOTIFY_CHANNELS

// Hint constants — avoid Vue interpolation conflict with double-brace placeholders.
const placeholderSyntax = '{{var}}'
const schemaExample = '{"amount":"number","approverName":"string"}'

const dialogVisible = computed({
  get: () => props.open,
  set: (v) => emit('update:open', v),
})

const dialogTitle = computed(() => {
  if (props.mode === 'test-send') {
    return `测试发送 — ${props.template?.templateCode || ''}`
  }
  if (props.mode === 'edit') {
    return `编辑通知模板 — ${props.template?.templateCode || ''}`
  }
  return '新建通知模板'
})

// ==================== Form state ====================

const formRef = ref<FormInstance | null>(null)
const form = ref<NotifyTemplate>({
  templateCode: '',
  title: '',
  bodyTemplate: '',
  channels: [],
  variablesSchemaJson: {},
})
const variablesSchemaJsonText = ref('{}')
const saving = ref(false)

const rules: FormRules = {
  templateCode: [
    { required: true, message: 'templateCode 必填', trigger: 'blur' },
    {
      pattern: /^[A-Z][A-Z0-9_]*$/,
      message: '建议大写字母 + 数字 + 下划线 (如 PO_APPROVAL_PENDING)',
      trigger: 'blur',
    },
  ],
  title: [{ required: true, message: '标题必填', trigger: 'blur' }],
  channels: [
    {
      validator: (_rule: unknown, value: NotifyChannel[], cb: (err?: Error) => void) => {
        if (!value || value.length === 0) {
          cb(new Error('至少选 1 个渠道'))
        } else {
          cb()
        }
      },
      trigger: 'change',
    },
  ],
}

// Auto-detect {{var}} 占位符 from title + body.
const detectedVars = computed(() => {
  const pattern = /\{\{\s*(\w+)\s*\}\}/g
  const result = new Set<string>()
  const haystacks = [form.value.title || '', form.value.bodyTemplate || '']
  for (const text of haystacks) {
    let m
    while ((m = pattern.exec(text)) !== null) {
      result.add(m[1])
    }
  }
  return Array.from(result)
})

// ==================== Test-send state ====================

const testSending = ref(false)
const testSendRecipientsText = ref('')
const testSendParamsText = ref('{}')
const lastTestSendResults = ref<TestSendChannelResult[]>([])
const previewTitle = ref('')
const previewBody = ref('')

// Live local preview (best-effort, not authoritative — server is source of truth).
watch([testSendParamsText, () => form.value.title, () => form.value.bodyTemplate], () => {
  previewTitle.value = ''
  previewBody.value = ''
  try {
    const params = JSON.parse(testSendParamsText.value || '{}')
    previewTitle.value = renderLocal(form.value.title || '', params)
    previewBody.value = renderLocal(form.value.bodyTemplate || '', params)
  } catch {
    // Invalid JSON, suppress preview.
  }
})

function renderLocal(text: string, params: Record<string, unknown>): string {
  return text.replace(/\{\{\s*(\w+)\s*\}\}/g, (_match, varName) => {
    if (varName in params) {
      const v = params[varName]
      return v == null ? '' : String(v)
    }
    return `{{${varName}}}` // 保留未替换占位符让用户看到缺什么.
  })
}

// ==================== Sync form with prop changes ====================

watch(
  () => [props.open, props.template, props.mode],
  async () => {
    if (!props.open) return
    if (props.template) {
      form.value = {
        id: props.template.id,
        factoryId: props.template.factoryId,
        templateCode: props.template.templateCode || '',
        title: props.template.title || '',
        bodyTemplate: props.template.bodyTemplate || '',
        channels: [...(props.template.channels || [])],
        variablesSchemaJson: { ...(props.template.variablesSchemaJson || {}) },
      }
      variablesSchemaJsonText.value = JSON.stringify(
        props.template.variablesSchemaJson || {},
        null,
        2,
      )
    } else {
      // Create mode reset.
      form.value = { templateCode: '', title: '', bodyTemplate: '', channels: [], variablesSchemaJson: {} }
      variablesSchemaJsonText.value = '{}'
    }
    lastTestSendResults.value = []
    testSendRecipientsText.value = ''
    testSendParamsText.value = '{}'
    previewTitle.value = ''
    previewBody.value = ''
    // UX polish (2026-05-20): clear validation residuals from previous open.
    // Without this, a user who hit a 409 (dup templateCode) on first attempt,
    // closed the dialog, then re-opened it for a fresh create, would see
    // stale red error markers before typing anything.
    await nextTick()
    formRef.value?.clearValidate()
  },
  { immediate: true },
)

// ==================== Actions ====================

async function handleSave() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  // Parse variablesSchemaJson.
  let variablesSchemaJson: Record<string, string> = {}
  try {
    variablesSchemaJson = JSON.parse(variablesSchemaJsonText.value || '{}')
    if (typeof variablesSchemaJson !== 'object' || Array.isArray(variablesSchemaJson)) {
      throw new Error('必须是 JSON object')
    }
  } catch (e) {
    ElMessage.error(`变量 schema JSON 解析失败: ${(e as Error).message}`)
    return
  }

  const payload: NotifyTemplate = {
    ...form.value,
    variablesSchemaJson,
  }

  saving.value = true
  try {
    if (props.mode === 'create') {
      const res = await createTemplate(props.factoryId, payload)
      if (res.success) {
        ElMessage.success('通知模板创建成功')
        emit('saved')
      }
    } else {
      const res = await updateTemplate(props.factoryId, payload.id!, payload)
      if (res.success) {
        ElMessage.success('通知模板更新成功')
        emit('saved')
      }
    }
  } catch {
    // Error toast handled by axios interceptor (4 位一体).
  } finally {
    saving.value = false
  }
}

async function handleTestSend() {
  // Parse recipientUserIds.
  const recipientUserIds: number[] = []
  const trimmed = testSendRecipientsText.value.trim()
  if (trimmed) {
    for (const part of trimmed.split(',')) {
      const num = Number(part.trim())
      if (Number.isFinite(num)) {
        recipientUserIds.push(num)
      }
    }
  }

  // Parse params.
  let params: Record<string, unknown> = {}
  try {
    params = JSON.parse(testSendParamsText.value || '{}')
  } catch (e) {
    ElMessage.error(`参数 JSON 解析失败: ${(e as Error).message}`)
    return
  }

  if (!form.value.templateCode) {
    ElMessage.error('请先保存模板再测试发送')
    return
  }

  testSending.value = true
  try {
    const res = await testSend(props.factoryId, {
      templateCode: form.value.templateCode,
      channels: form.value.channels,
      recipientUserIds,
      params,
    })
    if (res.success && res.data) {
      lastTestSendResults.value = res.data.results
      previewTitle.value = res.data.renderedTitle || ''
      previewBody.value = res.data.renderedBody || ''
      const okCount = res.data.results.filter((r) => r.status === 'SENT').length
      const totalCount = res.data.results.length
      if (okCount === totalCount) {
        ElMessage.success(`测试发送完成 — ${okCount}/${totalCount} 渠道成功`)
      } else if (okCount > 0) {
        ElMessage.warning(`部分成功 — ${okCount}/${totalCount} 渠道成功 (其余见下方表格)`)
      } else {
        ElMessage.error(`全部失败 — 见下方错误信息`)
      }
    }
  } catch {
    // Error toast handled by interceptor.
  } finally {
    testSending.value = false
  }
}

function onClose() {
  emit('update:open', false)
}
</script>

<style scoped>
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
  margin-top: 2px;
}

.test-send-section {
  margin-top: 12px;
}

.preview-box,
.test-result-box {
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  padding: 12px;
  margin: 8px 0;
}

.preview-label {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
  color: var(--el-text-color-primary);
}

.preview-content {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.preview-line {
  margin: 4px 0;
}
</style>
