<!--
  CommissionPreviewPanel — Phase B Sales Target Hub sub-tab 2.

  Dry-run commission calculation: pick a rule + enter order amount → see computed commission.
-->
<template>
  <div class="commission-preview-panel">
    <el-card shadow="never">
      <template #header>
        <span>提成试算 — 选规则 + 输入订单金额, 立即计算应得提成</span>
      </template>

      <el-form :model="form" label-width="120px">
        <el-form-item label="提成规则">
          <el-select v-model="form.ruleId" placeholder="请选择规则" filterable>
            <el-option
              v-for="r in rules"
              :key="r.id"
              :label="formatRuleLabel(r)"
              :value="r.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="订单金额 (元)" required>
          <el-input-number
            v-model="form.orderAmount"
            :min="0"
            :step="1000"
            :precision="2"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" :disabled="!form.ruleId" @click="onPreview">
            试算
          </el-button>
          <el-button @click="form.orderAmount = 0; result = null">清空</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card v-if="result" shadow="never" class="result-card">
      <template #header>
        <span>试算结果</span>
      </template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="订单金额">
          ¥{{ Number(result.orderAmount).toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }}
        </el-descriptions-item>
        <el-descriptions-item label="提成模式">
          <el-tag :type="result.mode === 'TIER' ? 'warning' : 'info'">
            {{ result.mode === 'TIER' ? '阶梯' : '单一' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="生效比率">
          {{ result.rate ? `${result.rate}%` : '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="提成金额">
          <span class="commission-value">
            ¥{{ Number(result.commission).toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }}
          </span>
        </el-descriptions-item>
        <el-descriptions-item v-if="result.matchedTier" label="匹配阶梯" :span="2">
          {{ formatTier(result.matchedTier) }}
        </el-descriptions-item>
        <el-descriptions-item v-if="result.note" label="备注" :span="2">
          <el-text type="warning">{{ result.note }}</el-text>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  listRules,
  previewCommission,
  type CommissionRule,
  type CommissionTier,
  type CommissionPreview,
} from '@/api/salesTargetHub'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

const loading = ref(false)
const rules = ref<CommissionRule[]>([])
const result = ref<CommissionPreview | null>(null)
const form = reactive<{ ruleId?: string; orderAmount: number }>({
  orderAmount: 100000,
})

async function loadRules() {
  if (!props.factoryId) return
  const res = await listRules(props.factoryId)
  if (res.success && res.data) {
    rules.value = res.data.filter(r => r.active)
  }
}

function formatRuleLabel(r: CommissionRule): string {
  const isTier = r.tierConfig && r.tierConfig.length > 0
  const mode = isTier ? `阶梯 ${r.tierConfig!.length} 段` : `单一 ${r.percentage}%`
  const period = r.periodType === 'MONTHLY' ? '按月' : '按季度'
  const scope = r.customerType ? r.customerType : '全部客户'
  return `${mode} | ${period} | ${scope} | from ${r.effectiveFrom}`
}

function formatTier(t: CommissionTier): string {
  const min = Number(t.minAmount).toLocaleString('zh-CN')
  const max = t.maxAmount == null ? '∞' : Number(t.maxAmount).toLocaleString('zh-CN')
  return `¥${min} ~ ¥${max} → ${t.rate}%`
}

async function onPreview() {
  if (!form.ruleId) {
    ElMessage.warning('请选择规则')
    return
  }
  if (form.orderAmount == null || form.orderAmount < 0) {
    ElMessage.warning('订单金额必须 ≥ 0')
    return
  }
  loading.value = true
  try {
    const res = await previewCommission(props.factoryId, form.ruleId, form.orderAmount)
    if (res.success && res.data) {
      result.value = res.data
    }
  } finally {
    loading.value = false
  }
}

onMounted(loadRules)
</script>

<style scoped>
.commission-preview-panel {
  padding: 8px 0;
}
.result-card {
  margin-top: 16px;
}
.commission-value {
  color: #67c23a;
  font-size: 20px;
  font-weight: 600;
}
</style>
