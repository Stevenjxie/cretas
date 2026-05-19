<!--
  RuleTestModal.vue — Canvas-Rules Phase 4a
  Dry-run a rule against sample JSON input.
  No side effects (per RuleEngine.preview): no logs, no mutation, no workflow trigger.
  Per fool-proof Rule 2: title shows ruleCode + ruleName context.
-->
<template>
  <el-dialog
    :model-value="visible"
    :title="dialogTitle"
    width="720px"
    :close-on-click-modal="false"
    @update:model-value="$emit('update:visible', $event)"
  >
    <div v-if="rule" class="test-modal-body">
      <div class="rule-summary">
        <div><strong>规则代码:</strong> <code>{{ rule.ruleCode }}</code></div>
        <div><strong>Scope:</strong> {{ rule.scope }}</div>
        <div><strong>动作:</strong> {{ rule.actionType }}</div>
        <div v-if="rule.conditionSpel"><strong>条件:</strong> <code>{{ rule.conditionSpel }}</code></div>
      </div>

      <el-form label-width="80px" size="default" @submit.prevent>
        <el-form-item label="样本 JSON">
          <el-input
            v-model="sampleInputText"
            type="textarea"
            :rows="8"
            placeholder='{"amount": 150000, "customer": {"tier": "VIP"}}'
          />
          <div class="form-hint">
            提供模拟的 input 对象。SpEL 条件会针对此 JSON 求值。注意: <strong>不会真实修改数据 / 不会写日志 / 不会触发流程</strong>。
          </div>
        </el-form-item>
      </el-form>

      <div class="action-bar">
        <el-button type="primary" :loading="running" @click="runTest">执行测试</el-button>
        <el-button @click="reset">清空结果</el-button>
      </div>

      <!-- Result -->
      <div v-if="result" class="result-area">
        <el-alert
          :title="result.shouldReject ? '规则会拒绝该输入' : '规则通过 / 未匹配'"
          :type="result.shouldReject ? 'error' : 'success'"
          :closable="false"
          show-icon
        />
        <el-descriptions :column="2" border size="small" class="result-desc">
          <el-descriptions-item label="shouldReject">
            <el-tag :type="result.shouldReject ? 'danger' : 'success'" size="small">
              {{ result.shouldReject }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="result.rejectRuleCode" label="rejectRuleCode">
            {{ result.rejectRuleCode }}
          </el-descriptions-item>
          <el-descriptions-item v-if="result.rejectReason" label="rejectReason" :span="2">
            {{ result.rejectReason }}
          </el-descriptions-item>
          <el-descriptions-item v-if="result.rejectActionHint" label="actionHint" :span="2">
            {{ result.rejectActionHint }}
          </el-descriptions-item>
          <el-descriptions-item v-if="result.rejectSeverity" label="severity">
            {{ result.rejectSeverity }}
          </el-descriptions-item>
          <el-descriptions-item label="modifications" :span="2">
            <span v-if="!result.modifications || result.modifications.length === 0">(无)</span>
            <ul v-else class="mods-list">
              <li v-for="(m, i) in result.modifications" :key="i">
                <code>{{ m.field || '(unknown field)' }}</code>:
                <span class="old-val">{{ formatVal(m.oldValue) }}</span>
                →
                <span class="new-val">{{ formatVal(m.newValue) }}</span>
              </li>
            </ul>
          </el-descriptions-item>
          <el-descriptions-item label="executedRules" :span="2">
            <span v-if="!result.executedRules || result.executedRules.length === 0">(无)</span>
            <code v-else>{{ result.executedRules.join(', ') }}</code>
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </div>

    <template #footer>
      <el-button @click="$emit('update:visible', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  testEvaluateBusinessRule,
} from '@/api/businessRuleApi'
import type { BusinessRule, RuleEvaluationResult } from '@/api/businessRuleApi'

const props = defineProps<{
  visible: boolean
  factoryId: string
  rule: BusinessRule | null
}>()

defineEmits<{
  (e: 'update:visible', v: boolean): void
}>()

const sampleInputText = ref<string>('{}')
const result = ref<RuleEvaluationResult | null>(null)
const running = ref(false)

const dialogTitle = computed(() => {
  if (!props.rule) return '测试评估规则'
  // Per fool-proof Rule 2 (context): include ruleCode + ruleName
  const name = props.rule.ruleName ? ` ${props.rule.ruleName}` : ''
  return `测试评估 — ${props.rule.ruleCode}${name}`
})

watch(
  () => [props.visible, props.rule?.id],
  () => {
    if (props.visible) {
      sampleInputText.value = '{}'
      result.value = null
    }
  },
)

function formatVal(v: unknown): string {
  if (v === null || v === undefined) return 'null'
  if (typeof v === 'object') {
    try {
      return JSON.stringify(v)
    } catch {
      return String(v)
    }
  }
  return String(v)
}

async function runTest() {
  if (!props.rule?.id) return
  let parsed: Record<string, unknown>
  try {
    parsed = JSON.parse(sampleInputText.value || '{}')
    if (typeof parsed !== 'object' || parsed === null) {
      throw new Error('Sample input must be a JSON object')
    }
  } catch (e) {
    const msg = e instanceof Error ? e.message : 'JSON 解析失败'
    ElMessage.error(`样本 JSON 解析失败: ${msg}`)
    return
  }
  running.value = true
  try {
    const res = await testEvaluateBusinessRule(props.factoryId, props.rule.id, parsed)
    result.value = (res.data as RuleEvaluationResult) || null
  } catch (e) {
    console.error('[testEvaluateBusinessRule]', e)
  } finally {
    running.value = false
  }
}

function reset() {
  result.value = null
}
</script>

<style scoped>
.test-modal-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.rule-summary {
  background: var(--el-fill-color-light);
  padding: 8px 12px;
  border-radius: 4px;
  font-size: 13px;
  line-height: 1.7;
}
.rule-summary code {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  background: var(--el-fill-color);
  padding: 1px 4px;
  border-radius: 2px;
}
.form-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
.action-bar {
  display: flex;
  gap: 8px;
}
.result-area {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.result-desc {
  margin-top: 4px;
}
.mods-list {
  margin: 0;
  padding-left: 16px;
}
.old-val {
  color: var(--el-color-info);
  text-decoration: line-through;
}
.new-val {
  color: var(--el-color-success);
  font-weight: 500;
}
</style>
