<template>
  <el-dialog
    :model-value="visible"
    title="规则求值测试"
    width="600px"
    @update:model-value="(v: boolean) => $emit('update:visible', v)"
  >
    <el-form label-position="top" size="small">
      <el-form-item label="规则">
        <el-input :model-value="ruleTitle" disabled />
      </el-form-item>
      <el-form-item label="Mock Context (JSON)">
        <el-input
          v-model="ctxJson"
          type="textarea" :rows="6"
          placeholder='{"amount": 20000, "department": "finance", "role": "FACTORY_SUPER_ADMIN"}'
        />
        <div class="hint">需 valid JSON. 评估时透传给 evaluator.</div>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="resultText"
      :type="resultType"
      :title="resultText"
      :description="resultDetail"
      show-icon
      :closable="false"
      style="margin-top: 12px"
    />

    <template #footer>
      <el-button @click="$emit('update:visible', false)">关闭</el-button>
      <el-button type="primary" :loading="testing" @click="onTest">评估</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { testRule, RULE_TYPE_LABELS, type WorkflowRuleDTO } from '@/api/workflowRule'
import type { ApiResponse } from '@/types/api'

const props = defineProps<{
  visible: boolean
  factoryId: string
  rule: WorkflowRuleDTO | null
}>()

defineEmits<{ 'update:visible': [v: boolean] }>()

const ctxJson = ref<string>('{\n  "amount": 20000,\n  "department": "finance",\n  "role": "FACTORY_SUPER_ADMIN"\n}')
const testing = ref(false)
const resultText = ref<string>('')
const resultType = ref<'success' | 'warning'>('success')
const resultDetail = ref<string>('')

const ruleTitle = computed(() => {
  if (!props.rule) return ''
  return `${RULE_TYPE_LABELS[props.rule.ruleType]} — ${props.rule.description || props.rule.id}`
})

watch(
  () => props.rule,
  () => {
    resultText.value = ''
    resultDetail.value = ''
  },
)

async function onTest() {
  if (!props.rule) return
  let ctx: Record<string, unknown>
  try {
    ctx = JSON.parse(ctxJson.value) as Record<string, unknown>
  } catch (e) {
    ElMessage.error('Mock Context JSON 解析失败')
    return
  }
  testing.value = true
  try {
    const resp = (await testRule(props.factoryId, props.rule.id, ctx)) as unknown as ApiResponse<{
      result: boolean
      expression: string
      ruleType: string
    }>
    if (resp.success && resp.data) {
 resultText.value = resp.data.result ? ' 规则命中 (true)' : ' 规则未命中 (false)'
      resultType.value = resp.data.result ? 'success' : 'warning'
      resultDetail.value = `ruleType=${resp.data.ruleType}, expression=${resp.data.expression}`
    } else {
      ElMessage.error(resp.message || '测试失败')
    }
  } catch (e) {
    ElMessage.error(`请求失败: ${(e as Error).message}`)
  } finally {
    testing.value = false
  }
}
</script>

<style scoped>
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
</style>
