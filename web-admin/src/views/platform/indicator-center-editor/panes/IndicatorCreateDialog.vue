<!--
  IndicatorCreateDialog — 新建指标对话框 (写入 backend POST /canvas-indicators)。
-->
<template>
  <el-dialog
    v-model="visible"
    title="新建指标"
    width="640px"
    :close-on-click-modal="false"
    @close="onClose"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
      <el-form-item label="编码" prop="code" required>
        <el-input
          v-model="form.code"
          placeholder="例: FACTORY_YIELD_RATE (大写下划线)"
          maxlength="100"
        />
      </el-form-item>
      <el-form-item label="名称" prop="name" required>
        <el-input v-model="form.name" placeholder="中文名" maxlength="200" />
      </el-form-item>
      <el-form-item label="分类" prop="category" required>
        <el-select v-model="form.category" style="width: 100%">
          <el-option
            v-for="c in INDICATOR_CATEGORIES"
            :key="c"
            :label="INDICATOR_CATEGORY_LABELS[c]"
            :value="c"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="单位">
        <el-input v-model="form.unit" placeholder="例: %, 元, 次/天" maxlength="30" />
      </el-form-item>
      <el-form-item label="计算策略">
        <el-radio-group v-model="form.computeStrategy">
          <el-radio value="REALTIME">{{ COMPUTE_STRATEGY_LABELS.REALTIME }}</el-radio>
          <el-radio value="CACHED">{{ COMPUTE_STRATEGY_LABELS.CACHED }}</el-radio>
          <el-radio value="PRECOMPUTED">{{ COMPUTE_STRATEGY_LABELS.PRECOMPUTED }}</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="缓存 TTL (秒)">
        <el-input-number v-model="form.cacheTtlSeconds" :min="0" :step="60" />
      </el-form-item>
      <el-form-item label="显示顺序">
        <el-input-number v-model="form.displayOrder" :min="0" :step="10" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="onSubmit" :loading="submitting">创建</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import {
  indicatorsApi,
  type Indicator,
  type IndicatorCategory,
  type ComputeStrategy,
  INDICATOR_CATEGORIES,
  INDICATOR_CATEGORY_LABELS,
  COMPUTE_STRATEGY_LABELS,
} from '@/api/canvasIndicators'

interface Props {
  modelValue: boolean
  factoryId: string
}
const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'created', v: Indicator): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const formRef = ref<FormInstance | null>(null)
const submitting = ref(false)

const form = ref({
  code: '',
  name: '',
  category: 'FACTORY' as IndicatorCategory,
  unit: '',
  computeStrategy: 'CACHED' as ComputeStrategy,
  cacheTtlSeconds: 3600,
  displayOrder: 0,
  description: '',
})

const rules: FormRules = {
  code: [
    { required: true, message: '请填写编码', trigger: 'blur' },
    {
      pattern: /^[A-Z_][A-Z0-9_]*$/,
      message: '编码必须大写字母 + 下划线 (例 FACTORY_YIELD_RATE)',
      trigger: 'blur',
    },
  ],
  name: [{ required: true, message: '请填写名称', trigger: 'blur' }],
  category: [{ required: true, message: '请选择分类', trigger: 'change' }],
}

function onClose() {
  formRef.value?.resetFields()
  form.value = {
    code: '',
    name: '',
    category: 'FACTORY',
    unit: '',
    computeStrategy: 'CACHED',
    cacheTtlSeconds: 3600,
    displayOrder: 0,
    description: '',
  }
}

async function onSubmit() {
  if (!formRef.value) return
  const ok = await formRef.value.validate().catch(() => false)
  if (!ok) return

  submitting.value = true
  try {
    const resp = await indicatorsApi.create(props.factoryId, {
      code: form.value.code.trim(),
      name: form.value.name.trim(),
      category: form.value.category,
      unit: form.value.unit?.trim() || undefined,
      computeStrategy: form.value.computeStrategy,
      cacheTtlSeconds: form.value.cacheTtlSeconds,
      displayOrder: form.value.displayOrder,
      description: form.value.description?.trim() || undefined,
    })
    const created = (resp.data ?? null) as Indicator | null
    if (created) {
      emit('created', created)
    }
  } catch (err) {
    // request.ts 弹错 toast 后, 这里再 console.warn 一次, 不重复 message
    console.warn('create failed', err)
  } finally {
    submitting.value = false
  }
}
</script>
