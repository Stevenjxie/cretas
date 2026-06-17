<!--
  TaskFormDialog.vue — create / edit a scheduled task.

  Per fool-proof Rule 2: Dialog title always includes the entity name when editing
  ("编辑定时任务 — {taskCode}") so the user knows exactly what is being modified.
  Per fool-proof Rule 3: handler bean is a select dropdown (populated from
  /handlers endpoint) — no free-text typos. Cron preset dropdown auto-fills the
  expression input.
  Per fool-proof Rule 5: if /handlers endpoint returns empty, show a hint
  pointing to backend setup instead of leaving the dropdown empty.
-->
<template>
  <el-dialog
    :model-value="visible"
    :title="dialogTitle"
    width="640px"
    :close-on-click-modal="false"
    @close="onClose"
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="120px"
      :disabled="saving"
    >
      <el-form-item label="任务代码" prop="taskCode">
        <el-input
          v-model="form.taskCode"
          :disabled="isEdit"
          placeholder="如: INVENTORY_DAILY_REPORT"
          maxlength="100"
          show-word-limit
        />
        <div class="form-hint">唯一标识，创建后不可改</div>
      </el-form-item>

      <el-form-item label="任务名称" prop="taskName">
        <el-input
          v-model="form.taskName"
          placeholder="人类可读名称（用于列表显示）"
          maxlength="255"
          show-word-limit
        />
      </el-form-item>

      <el-form-item label="Cron 表达式" prop="cronExpression">
        <div style="display: flex; gap: 8px; width: 100%;">
          <el-input
            v-model="form.cronExpression"
            placeholder="6 字段: 秒 分 时 日 月 周"
            style="flex: 1;"
          />
          <el-select
            v-model="presetPick"
            placeholder="预设"
            style="width: 200px;"
            @change="onPresetPick"
          >
            <el-option
              v-for="p in presets"
              :key="p.value"
              :label="p.label"
              :value="p.value"
            />
          </el-select>
        </div>
        <div class="form-hint cron-preview">
          <span v-if="cronDesc">预览: {{ cronDesc }}</span>
          <span v-else class="muted">输入 cron 后显示语义预览</span>
        </div>
        <div class="form-hint">
          格式: 秒(0-59) 分(0-59) 时(0-23) 日(1-31) 月(1-12) 周(0-7 / ?) — Spring 6 字段
        </div>
      </el-form-item>

      <el-form-item label="处理器 Bean" prop="handlerBeanName">
        <el-select
          v-model="form.handlerBeanName"
          placeholder="选择实现 TaskHandler 接口的 Spring Bean"
          filterable
          :loading="loadingHandlers"
          style="width: 100%;"
        >
          <el-option
            v-for="h in availableHandlers"
            :key="h"
            :label="h"
            :value="h"
          />
        </el-select>
        <div v-if="!loadingHandlers && availableHandlers.length === 0" class="form-hint warning-hint">
          未发现任何 TaskHandler bean。请检查后端是否注册了实现 com.cretas.aims.service.cron.TaskHandler 的 @Component。
        </div>
      </el-form-item>

      <el-form-item label="工厂范围">
        <el-input
          v-model="factoryIdInput"
          placeholder="留空 = 全局任务（跨工厂）；填写 = 仅该工厂"
          maxlength="50"
        />
        <div class="form-hint">
          全局任务示例：缓存清理、AI 权重调整。工厂任务示例：月度库存报表。
        </div>
      </el-form-item>

      <el-form-item label="启用">
        <el-switch v-model="form.enabled" />
        <span class="form-hint" style="margin-left: 12px;">
          禁用后任务不会被调度，但保留配置便于以后启用。
        </span>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="onClose" :disabled="saving">取消</el-button>
      <el-button type="primary" :loading="saving" @click="onSubmit">
        {{ isEdit ? '保存' : '创建' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  createScheduledTask,
  updateScheduledTask,
  listTaskHandlers,
  type ScheduledTask,
} from '@/api/scheduledTaskApi'
import { describeCron, CRON_PRESETS } from '../cronHelp'

const props = defineProps<{
  visible: boolean
  editTask: ScheduledTask | null
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'saved', task: ScheduledTask): void
}>()

const formRef = ref<FormInstance | null>(null)
const saving = ref(false)
const loadingHandlers = ref(false)
const availableHandlers = ref<string[]>([])
const presets = CRON_PRESETS
const presetPick = ref('')

const isEdit = computed(() => !!props.editTask?.id)
const dialogTitle = computed(() =>
  isEdit.value
    ? `编辑定时任务 — ${props.editTask?.taskCode || ''}`
    : '新建定时任务',
)

const form = ref({
  taskCode: '',
  taskName: '',
  cronExpression: '0 0 9 * * ?',
  handlerBeanName: '',
  enabled: true,
})

const factoryIdInput = ref<string>('')

const cronDesc = computed(() => describeCron(form.value.cronExpression))

const rules: FormRules = {
  taskCode: [
    { required: true, message: '任务代码必填', trigger: 'blur' },
    { pattern: /^[A-Z][A-Z0-9_]*$/, message: '建议: 大写字母开头，仅含大写字母/数字/下划线', trigger: 'blur' },
  ],
  cronExpression: [
    { required: true, message: 'Cron 表达式必填', trigger: 'blur' },
    {
      validator: (_rule, value: string, cb) => {
        const parts = String(value || '').trim().split(/\s+/)
        if (parts.length < 6 || parts.length > 7) {
          cb(new Error('Cron 必须有 6 或 7 个字段（含可选 year）'))
        } else {
          cb()
        }
      },
      trigger: 'blur',
    },
  ],
  handlerBeanName: [
    { required: true, message: '处理器 Bean 必填', trigger: 'change' },
  ],
}

watch(
  () => props.editTask,
  async (t) => {
    if (t) {
      form.value = {
        taskCode: t.taskCode,
        taskName: t.taskName || '',
        cronExpression: t.cronExpression,
        handlerBeanName: t.handlerBeanName,
        enabled: t.enabled,
      }
      factoryIdInput.value = t.factoryId || ''
    } else {
      form.value = {
        taskCode: '',
        taskName: '',
        cronExpression: '0 0 9 * * ?',
        handlerBeanName: '',
        enabled: true,
      }
      factoryIdInput.value = ''
    }
    presetPick.value = ''
    // UX polish (2026-05-20): clear validation residuals from previous open.
    // Cron expression validation can leave stale red error if user closed dialog
    // after a bad cron expression error then re-opened for a new task.
    await nextTick()
    formRef.value?.clearValidate()
  },
  { immediate: true },
)

async function loadHandlers() {
  loadingHandlers.value = true
  try {
    const res = await listTaskHandlers()
    availableHandlers.value = res.data || []
  } catch (e) {
    console.error('[TaskFormDialog] loadHandlers failed', e)
  } finally {
    loadingHandlers.value = false
  }
}

function onPresetPick(value: string) {
  if (value) {
    form.value.cronExpression = value
    presetPick.value = '' // reset to allow re-picking same preset
  }
}

async function onSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload: ScheduledTask = {
      taskCode: form.value.taskCode.trim(),
      taskName: form.value.taskName.trim() || undefined,
      cronExpression: form.value.cronExpression.trim(),
      handlerBeanName: form.value.handlerBeanName,
      factoryId: factoryIdInput.value.trim() || null,
      enabled: form.value.enabled,
    }
    let saved: ScheduledTask
    if (isEdit.value && props.editTask?.id) {
      const res = await updateScheduledTask(props.editTask.id, payload)
      saved = res.data
      ElMessage.success('已保存')
    } else {
      const res = await createScheduledTask(payload)
      saved = res.data
      ElMessage.success('已创建并加载到调度器')
    }
    emit('saved', saved)
  } catch (e) {
    console.error('[TaskFormDialog] submit failed', e)
    // Interceptor already surfaced the error toast.
  } finally {
    saving.value = false
  }
}

function onClose() {
  emit('close')
}

onMounted(loadHandlers)
</script>

<style scoped>
.form-hint {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.cron-preview {
  font-size: 12px;
  color: var(--el-color-primary);
  margin-top: 4px;
}
.warning-hint {
  color: var(--el-color-warning);
}
.muted { color: var(--el-text-color-placeholder); }
</style>
