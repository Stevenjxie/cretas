<script setup lang="ts" generic="T extends Record<string, any>">
import { ref, watch, computed, nextTick } from 'vue';
import { Lightning } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';

/**
 * U-NEW-1 quick mode (一维快速) — minimal-field single-row inline create.
 *
 * Philosophy (per fool-proof-design R1+R2+R5):
 *   - Only the **required** fields are presented (small N: 2-4 inputs).
 *   - 防呆 R2: header shows context (entity label + scope hint).
 *   - 防呆 R5: empty-state hint guides user to first action.
 *   - Enter key submits; Esc closes (Element Plus native).
 *   - After successful submit, dialog stays open with cleared form for
 *     consecutive entry (with toast). User clicks 完成 to close.
 *
 * Parent supplies:
 *   - fields: column definitions (label, prop, type, slotName, required)
 *   - rowFactory: () => T  (called on open + after each submit)
 *   - submit: (row: T) => Promise<void>
 */
interface QuickField<T> {
  prop: keyof T;
  label: string;
  /** Default `text`; if `slotName` set, slot is used instead. */
  slotName?: string;
  required?: boolean;
  /** Placeholder shown inside the input. */
  placeholder?: string;
}

const props = withDefaults(
  defineProps<{
    modelValue: boolean;
    title?: string;
    /** Context hint shown in subtitle, e.g. "客户: 西餐厅A" or "类型: 原材料" */
    contextHint?: string;
    fields: QuickField<T>[];
    rowFactory: () => T;
    submit: (row: T) => Promise<void>;
    /** Cumulative-created-this-session counter (R1 boundary display). */
    sessionMax?: number;
  }>(),
  {
    title: '快速新建',
    contextHint: '',
    sessionMax: 50,
  }
);

const emit = defineEmits<{
  (e: 'update:modelValue', open: boolean): void;
  (e: 'submitted', row: T, total: number): void;
}>();

const formData = ref<Record<string, any>>(props.rowFactory() as Record<string, any>);
const submitting = ref(false);
const sessionCount = ref(0);
const firstInputRef = ref<HTMLInputElement | null>(null);

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      formData.value = props.rowFactory() as Record<string, any>;
      sessionCount.value = 0;
      void nextTick(() => {
        firstInputRef.value?.focus?.();
      });
    }
  },
  { immediate: true }
);

const remainingSlots = computed(() => Math.max(0, props.sessionMax - sessionCount.value));
const canSubmit = computed(() => {
  if (remainingSlots.value <= 0) return false;
  return props.fields.every((f) => {
    if (!f.required) return true;
    const v = formData.value[String(f.prop)];
    return v !== undefined && v !== null && v !== '';
  });
});

async function handleSubmit(): Promise<void> {
  if (!canSubmit.value || submitting.value) return;
  submitting.value = true;
  try {
    await props.submit(formData.value as T);
    sessionCount.value += 1;
    emit('submitted', formData.value as T, sessionCount.value);
    ElMessage.success(`已创建第 ${sessionCount.value} 条，继续录入或点击完成关闭`);
    formData.value = props.rowFactory() as Record<string, any>;
    await nextTick();
    firstInputRef.value?.focus?.();
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '创建失败';
    ElMessage.error(msg);
  } finally {
    submitting.value = false;
  }
}

function close(): void {
  if (submitting.value) return;
  emit('update:modelValue', false);
}

function onEnterSubmit(e: KeyboardEvent): void {
  // Enter while focus inside form → submit. Shift+Enter ignored to allow
  // newlines in textareas if used in custom slots.
  if (e.shiftKey) return;
  e.preventDefault();
  void handleSubmit();
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="520px"
    :show-close="!submitting"
    :close-on-click-modal="false"
    :close-on-press-escape="!submitting"
    @update:model-value="close"
  >
    <!-- R2 context header (entity / scope hint). -->
    <div class="qc-header">
      <el-icon class="qc-header-icon"><Lightning /></el-icon>
      <div class="qc-header-text">
        <div class="qc-header-title">一维快速录入</div>
        <div v-if="contextHint" class="qc-header-sub">{{ contextHint }}</div>
        <div v-else class="qc-header-sub">回车提交，连续录入；点击完成关闭</div>
      </div>
    </div>

    <!-- R5 empty-state guidance is implicit via placeholders + first-input autofocus. -->
    <el-form
      label-position="top"
      :model="formData"
      @submit.prevent
      @keydown.enter.capture="onEnterSubmit"
    >
      <el-form-item
        v-for="(field, idx) in fields"
        :key="String(field.prop)"
        :label="field.label"
        :required="field.required"
      >
        <slot
          v-if="field.slotName"
          :name="field.slotName"
          :row="formData"
          :field="field"
        />
        <el-input
          v-else
          :ref="(el: unknown) => { if (idx === 0) firstInputRef = el as HTMLInputElement; }"
          v-model="formData[String(field.prop)]"
          :placeholder="field.placeholder || `请输入${field.label}`"
          clearable
        />
      </el-form-item>
    </el-form>

    <!-- R1 boundary: cumulative count vs session max. -->
    <div class="qc-counter">
      <span>本次已录入 <strong>{{ sessionCount }}</strong> / 上限 {{ sessionMax }} 条</span>
      <span v-if="remainingSlots <= 5 && remainingSlots > 0" class="qc-counter-warn">
        剩余 {{ remainingSlots }} 条配额
      </span>
      <span v-else-if="remainingSlots === 0" class="qc-counter-stop">
        已达本次上限，请点击完成关闭
      </span>
    </div>

    <template #footer>
      <el-button :disabled="submitting" @click="close">完成 ({{ sessionCount }} 条)</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="!canSubmit"
        @click="handleSubmit"
      >
        提交并继续 (Enter)
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.qc-header {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  margin-bottom: 12px;
  padding: 10px 12px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}
.qc-header-icon {
  font-size: 20px;
  color: var(--el-color-primary);
  margin-top: 2px;
}
.qc-header-text {
  flex: 1;
}
.qc-header-title {
  font-weight: 600;
  font-size: 14px;
  color: var(--el-text-color-primary);
}
.qc-header-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.qc-counter {
  margin-top: 8px;
  padding: 6px 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: var(--el-fill-color-lighter);
  border-radius: 4px;
}
.qc-counter-warn {
  color: var(--el-color-warning);
  font-weight: 500;
}
.qc-counter-stop {
  color: var(--el-color-danger);
  font-weight: 500;
}
</style>
