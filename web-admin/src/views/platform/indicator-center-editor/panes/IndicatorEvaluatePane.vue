<!--
  IndicatorEvaluatePane — Tab 4: 公式测试 (SpEL dry-run)。

  让管理员在保存前用样本数据测试 SpEL 公式, 验证语法 + 安全性。
  Backend 用 SandboxedSpelEvaluator (拒绝 T(java.lang.Runtime) 等 RCE)。
-->
<template>
  <div class="pane">
    <el-alert
      type="info"
      :closable="false"
      title="公式 dry-run (SpEL Sandbox)"
      description="用样本数据测试公式, 不落库。禁止 T(...) / new / getClass 等危险表达式。"
      show-icon
      style="margin-bottom: 12px"
    />

    <el-form :model="form" label-width="100px">
      <el-form-item label="公式 (SpEL)">
        <el-input
          v-model="form.formula"
          type="textarea"
          :rows="3"
          placeholder="例: #yield * 100 / #total"
          maxlength="2000"
          show-word-limit
        />
      </el-form-item>
      <el-form-item label="样本变量">
        <div class="variables-editor">
          <div v-for="(v, idx) in form.variables" :key="idx" class="variable-row">
            <el-input v-model="v.name" placeholder="变量名 (例 yield)" style="width: 180px" />
            <span class="eq">=</span>
            <el-input v-model="v.value" placeholder="值 (数字或字符串)" style="flex: 1" />
            <el-button :icon="Delete" link type="danger" @click="removeVar(idx)" />
          </div>
          <el-button :icon="Plus" link type="primary" @click="addVar">添加变量</el-button>
        </div>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :icon="Right" :loading="loading" @click="onEvaluate">
          求值
        </el-button>
        <el-button @click="onClear">清空</el-button>
        <el-button @click="onLoadSample" link type="primary">加载示例</el-button>
      </el-form-item>
    </el-form>

    <el-card v-if="result" shadow="never" class="result-card">
      <template #header>
        <div class="result-header">
          <span>求值结果</span>
          <el-tag :type="result.status === 'OK' ? 'success' : 'danger'" effect="dark">
            {{ result.status === 'OK' ? '成功' : '失败' }}
          </el-tag>
        </div>
      </template>
      <div v-if="result.status === 'OK'">
        <p>
          <strong>结果:</strong>
          <code class="result-value">{{ formatResult(result.result) }}</code>
          <el-tag size="small" style="margin-left: 8px">{{ result.resultType }}</el-tag>
        </p>
      </div>
      <div v-else>
        <el-alert type="error" :closable="false" :title="result.error || '未知错误'" show-icon />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Plus, Right } from '@element-plus/icons-vue'
import {
  indicatorsApi,
  type IndicatorDetail,
  type TestEvaluateResult,
} from '@/api/canvasIndicators'

interface Props {
  factoryId: string
  detail: IndicatorDetail
}
const props = defineProps<Props>()

interface VarRow {
  name: string
  value: string
}

const form = ref<{ formula: string; variables: VarRow[] }>({
  formula: '',
  variables: [
    { name: 'yield', value: '950' },
    { name: 'total', value: '1000' },
  ],
})
const result = ref<TestEvaluateResult | null>(null)
const loading = ref(false)

function addVar() {
  form.value.variables.push({ name: '', value: '' })
}

function removeVar(idx: number) {
  form.value.variables.splice(idx, 1)
}

function onClear() {
  form.value.formula = ''
  form.value.variables = []
  result.value = null
}

function onLoadSample() {
  form.value.formula = '#yield * 100.0 / #total'
  form.value.variables = [
    { name: 'yield', value: '950' },
    { name: 'total', value: '1000' },
  ]
}

async function onEvaluate() {
  if (!form.value.formula?.trim()) {
    ElMessage.warning('请填写公式')
    return
  }
  // Convert string values to number when possible
  const vars: Record<string, unknown> = {}
  for (const v of form.value.variables) {
    if (!v.name?.trim()) continue
    const name = v.name.trim()
    const raw = v.value
    const num = Number(raw)
    vars[name] = !isNaN(num) && raw.trim() !== '' ? num : raw
  }
  loading.value = true
  try {
    const resp = await indicatorsApi.testEvaluate(
      props.factoryId,
      props.detail.id,
      form.value.formula.trim(),
      vars,
    )
    result.value = (resp.data ?? null) as TestEvaluateResult | null
  } catch (err: unknown) {
    // request.ts 弹错 toast 后, 这里也要给个 result 卡片显示
    const msg = err instanceof Error ? err.message : '求值失败'
    result.value = {
      formula: form.value.formula.trim(),
      variables: vars,
      status: 'FAILED',
      error: msg,
    }
  } finally {
    loading.value = false
  }
}

function formatResult(r: unknown): string {
  if (r === null || r === undefined) return 'null'
  if (typeof r === 'object') {
    try {
      return JSON.stringify(r)
    } catch {
      return String(r)
    }
  }
  return String(r)
}
</script>

<style scoped>
.pane {
  padding: 8px 0;
}

.variables-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.variable-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.eq {
  color: var(--el-text-color-secondary);
  font-weight: 500;
}

.result-card {
  margin-top: 16px;
}

.result-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.result-value {
  font-family: 'Consolas', monospace;
  font-size: 14px;
  background: var(--el-fill-color);
  padding: 4px 8px;
  border-radius: 3px;
  color: var(--el-color-primary);
}
</style>
