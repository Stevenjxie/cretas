<!--
  CommissionRulesPanel — Phase B Sales Target Hub sub-tab 1.

  CRUD for CommissionRule with:
    - Flat percentage OR tier ladder
    - Period MONTHLY / QUARTERLY
    - Leaderboard formula hint
    - Customer-type / sales-id targeting
-->
<template>
  <div class="commission-rules-panel">
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新增提成规则</el-button>
      <el-button @click="loadData">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column label="模式" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.tierConfig && row.tierConfig.length > 0" type="warning">阶梯</el-tag>
          <el-tag v-else type="info">单一</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="percentage" label="基础比率" width="100">
        <template #default="{ row }">
          {{ row.tierConfig && row.tierConfig.length > 0 ? '-' : `${row.percentage}%` }}
        </template>
      </el-table-column>
      <el-table-column label="阶梯" width="120">
        <template #default="{ row }">
          <span v-if="row.tierConfig && row.tierConfig.length > 0">
            {{ row.tierConfig.length }} 段
          </span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="周期" width="100">
        <template #default="{ row }">
          {{ PERIOD_LABELS[row.periodType as PeriodType] }}
        </template>
      </el-table-column>
      <el-table-column prop="salesId" label="销售员" width="100">
        <template #default="{ row }">
          {{ row.salesId == null ? '全部销售' : `#${row.salesId}` }}
        </template>
      </el-table-column>
      <el-table-column prop="customerType" label="客户类型" width="120">
        <template #default="{ row }">
          {{ row.customerType || '全部类型' }}
        </template>
      </el-table-column>
      <el-table-column prop="effectiveFrom" label="生效起" width="120" />
      <el-table-column prop="effectiveTo" label="生效止" width="120">
        <template #default="{ row }">{{ row.effectiveTo || '永久' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.active" type="success">启用</el-tag>
          <el-tag v-else type="info">停用</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button
            size="small"
            :type="row.active ? 'warning' : 'success'"
            @click="toggleActive(row)"
          >
            {{ row.active ? '停用' : '启用' }}
          </el-button>
          <el-button size="small" type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogOpen" :title="dialogTitle" width="720px">
      <el-alert
        type="info"
        :closable="false"
        title="提成模式二选一"
        description="可选择 flat percentage (单一比率) 或 tier ladder (阶梯). 两者只能填一个. 餐饮营销员阶梯按【月度累计复购业绩】跨档 (邓总方案): 当月累计每跨过一档下界, 之后该笔营收按更高档费率计提."
        style="margin-bottom: 16px"
      />
      <el-form :model="form" label-width="140px">
        <el-form-item label="提成模式">
          <el-radio-group v-model="ruleMode">
            <el-radio value="flat">单一比率 (flat)</el-radio>
            <el-radio value="tier">阶梯比率 (tier)</el-radio>
          </el-radio-group>
        </el-form-item>

        <template v-if="ruleMode === 'flat'">
          <el-form-item label="提成比率 (%)" required>
            <el-input-number v-model="form.percentage" :min="0" :max="100" :step="0.5" :precision="2" />
          </el-form-item>
        </template>

        <template v-if="ruleMode === 'tier'">
          <el-form-item label="阶梯配置" required>
            <div class="tier-list">
              <div v-for="(tier, idx) in tierList" :key="idx" class="tier-row">
                <span class="tier-idx">#{{ idx + 1 }}</span>
                <el-input-number
                  v-model="tier.minAmount"
                  :min="0"
                  :step="10000"
                  placeholder="最低金额"
                  style="width: 140px"
                />
                <span class="tier-sep">~</span>
                <el-input-number
                  v-model="tier.maxAmount"
                  :min="0"
                  :step="10000"
                  placeholder="最高金额 (空 = ∞)"
                  style="width: 160px"
                />
                <span class="tier-sep">→</span>
                <el-input-number
                  v-model="tier.rate"
                  :min="0"
                  :max="100"
                  :step="0.5"
                  :precision="2"
                  style="width: 110px"
                />
                <span>%</span>
                <el-button
                  type="danger"
                  size="small"
                  text
                  @click="removeTier(idx)"
                >
                  删除
                </el-button>
              </div>
              <el-button type="primary" size="small" @click="addTier">新增阶梯</el-button>
              <el-text v-if="tierList.length > 0" type="info" size="small" style="display:block; margin-top:8px">
                例 (营销员月度累计复购业绩跨档): 0~15万 → 3% / 15万~30万 → 5% / 30万~∞ → 8%
              </el-text>
              <!-- 防呆: 区间重叠/缺口实时校验提示 -->
              <el-alert
                v-if="tierValidationError"
                type="error"
                :closable="false"
                :title="tierValidationError"
                style="margin-top:8px"
              />
            </div>
          </el-form-item>

          <!-- 防呆 Rule 1: tier 区间预览 — 输入一个月度累计业绩, 即时算落哪一档 + 该笔提成 -->
          <el-form-item label="区间预览">
            <div class="tier-preview">
              <span style="margin-right:8px">月度累计业绩 ¥</span>
              <el-input-number
                v-model="previewRevenue"
                :min="0"
                :step="10000"
                :precision="2"
                style="width: 160px"
              />
              <el-text type="info" size="small" style="margin-left:12px">
                <template v-if="tierPreview">
                  落 <b>第 {{ tierPreview.tierIndex + 1 }} 档</b>
                  (费率 {{ tierPreview.rate }}%) → 该笔营收提成 ¥{{ tierPreview.commission }}
                </template>
                <template v-else>累计业绩低于所有档下界, 暂不计提成 (或先配最低档下界 = 0)</template>
              </el-text>
            </div>
          </el-form-item>
        </template>

        <el-divider />

        <el-form-item label="周期类型">
          <el-select v-model="form.periodType">
            <el-option label="按月 (MONTHLY)" value="MONTHLY" />
            <el-option label="按季度 (QUARTERLY)" value="QUARTERLY" />
          </el-select>
        </el-form-item>
        <el-form-item label="销售员 ID">
          <el-input-number v-model="form.salesId" :min="1" />
          <el-text type="info" size="small" style="margin-left: 8px">(留空 = 适用所有销售员)</el-text>
        </el-form-item>
        <el-form-item label="客户类型">
          <el-input v-model="form.customerType" placeholder="大客户 / VIP / 普通 (留空 = 全部)" />
        </el-form-item>
        <el-form-item label="生效起始日" required>
          <el-date-picker v-model="form.effectiveFrom" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="生效结束日">
          <el-date-picker v-model="form.effectiveTo" type="date" value-format="YYYY-MM-DD" />
          <el-text type="info" size="small" style="margin-left: 8px">(留空 = 永久)</el-text>
        </el-form-item>
        <el-form-item label="排行榜公式">
          <el-input
            v-model="form.leaderboardFormula"
            placeholder="例: SUM(amount) 或 SUM(amount) * rate"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.active" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listRules,
  createRule,
  updateRule,
  deleteRule,
  PERIOD_TYPE_LABELS,
  type CommissionRule,
  type CommissionTier,
  type PeriodType,
} from '@/api/salesTargetHub'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()
const emit = defineEmits<{ refresh: [] }>()

const PERIOD_LABELS = PERIOD_TYPE_LABELS

const loading = ref(false)
const saving = ref(false)
const rows = ref<CommissionRule[]>([])
const dialogOpen = ref(false)
const editingId = ref<string | null>(null)
const ruleMode = ref<'flat' | 'tier'>('flat')
const dialogTitle = computed(() => editingId.value == null ? '新增提成规则' : '编辑提成规则')

const form = reactive<Partial<CommissionRule>>({
  percentage: 5,
  periodType: 'MONTHLY',
  active: true,
})
const tierList = ref<CommissionTier[]>([])
const previewRevenue = ref<number>(150000)

/**
 * 阶梯区间校验 (防呆): rate 0-100 / minAmount 必填 / maxAmount > minAmount /
 * 区间不重叠 (后一档 minAmount >= 前一档 maxAmount). 与后端 validateTierConfig 同口径.
 * 返回首个错误文案, 无错返 ''.
 */
const tierValidationError = computed<string>(() => {
  if (ruleMode.value !== 'tier') return ''
  const tiers = tierList.value
  if (tiers.length === 0) return ''
  let prevMax: number | null = null
  for (let i = 0; i < tiers.length; i++) {
    const t = tiers[i]
    const min = Number(t.minAmount)
    const max = t.maxAmount == null ? null : Number(t.maxAmount)
    const rate = Number(t.rate)
    if (t.minAmount == null || Number.isNaN(min)) return `第 ${i + 1} 档缺少最低金额`
    if (Number.isNaN(rate) || rate < 0 || rate > 100) return `第 ${i + 1} 档费率必须 0-100`
    if (max != null && max <= min) return `第 ${i + 1} 档最高金额必须 > 最低金额`
    if (prevMax != null && min < prevMax) return `第 ${i + 1} 档最低金额必须 ≥ 前一档最高金额 (区间不重叠)`
    prevMax = max
  }
  return ''
})

/**
 * tier 区间预览 (防呆 Rule 1): 给定月度累计业绩, 算落哪一档 + 该笔提成.
 * 镜像后端 CommissionService.resolveTier: minAmount <= 累计 (含下界), 累计 < maxAmount (不含上界),
 * 多档命中取下界最大者; 无命中返 null.
 */
const tierPreview = computed<{ tierIndex: number; rate: number; commission: string } | null>(() => {
  if (ruleMode.value !== 'tier') return null
  if (tierValidationError.value) return null
  const cumulative = Number(previewRevenue.value) || 0
  let matchedIdx = -1
  let matchedRate = 0
  let matchedMin = -Infinity
  tierList.value.forEach((t, i) => {
    const min = Number(t.minAmount)
    const max = t.maxAmount == null ? null : Number(t.maxAmount)
    const rate = Number(t.rate)
    if (Number.isNaN(min) || Number.isNaN(rate)) return
    const aboveMin = cumulative >= min
    const belowMax = max == null || cumulative < max
    if (aboveMin && belowMax && min > matchedMin) {
      matchedIdx = i
      matchedRate = rate
      matchedMin = min
    }
  })
  if (matchedIdx < 0) return null
  const commission = (cumulative * matchedRate / 100).toFixed(2)
  return { tierIndex: matchedIdx, rate: matchedRate, commission }
})

async function loadData() {
  if (!props.factoryId) return
  loading.value = true
  try {
    const res = await listRules(props.factoryId)
    if (res.success && res.data) rows.value = res.data
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.id = undefined
  form.percentage = 5
  form.periodType = 'MONTHLY'
  form.salesId = undefined
  form.customerType = ''
  form.effectiveFrom = new Date().toISOString().slice(0, 10)
  form.effectiveTo = undefined
  form.active = true
  form.leaderboardFormula = ''
  ;(form as any).version = undefined
  tierList.value = []
  ruleMode.value = 'flat'
}

function openCreate() {
  editingId.value = null
  resetForm()
  dialogOpen.value = true
}

function openEdit(row: CommissionRule) {
  editingId.value = row.id ?? null
  resetForm()
  form.id = row.id
  form.percentage = row.percentage
  form.periodType = row.periodType
  form.salesId = row.salesId ?? undefined
  form.customerType = row.customerType ?? ''
  form.effectiveFrom = row.effectiveFrom
  form.effectiveTo = row.effectiveTo ?? undefined
  form.active = row.active
  form.leaderboardFormula = row.leaderboardFormula ?? ''
  ;(form as any).version = row.version
  if (row.tierConfig && row.tierConfig.length > 0) {
    tierList.value = row.tierConfig.map(t => ({
      minAmount: Number(t.minAmount),
      maxAmount: t.maxAmount == null ? null : Number(t.maxAmount),
      rate: Number(t.rate),
    }))
    ruleMode.value = 'tier'
  } else {
    ruleMode.value = 'flat'
  }
  dialogOpen.value = true
}

function addTier() {
  if (tierList.value.length >= 20) {
    ElMessage.warning('最多 20 个阶梯')
    return
  }
  const prevMax = tierList.value.length > 0
    ? tierList.value[tierList.value.length - 1].maxAmount
    : 0
  tierList.value.push({
    minAmount: prevMax ?? 0,
    maxAmount: null,
    rate: 5,
  })
}

function removeTier(idx: number) {
  tierList.value.splice(idx, 1)
}

async function onSave() {
  // Validation
  if (ruleMode.value === 'flat') {
    if (form.percentage == null || form.percentage < 0 || form.percentage > 100) {
      ElMessage.warning('提成比率必须 0-100')
      return
    }
  } else {
    if (tierList.value.length === 0) {
      ElMessage.warning('请至少配置一个阶梯')
      return
    }
    // 防呆: 区间重叠/缺口/rate 校验, 阻断提交
    if (tierValidationError.value) {
      ElMessage.warning(tierValidationError.value)
      return
    }
  }
  if (!form.effectiveFrom) {
    ElMessage.warning('请选择生效起始日')
    return
  }

  // 防呆 Rule (保存前确认): 阶梯模式给档位摘要让配置人复核
  if (ruleMode.value === 'tier') {
    const summary = tierList.value
      .map((t, i) => {
        const max = t.maxAmount == null ? '∞' : `¥${Number(t.maxAmount).toLocaleString()}`
        return `第${i + 1}档 ¥${Number(t.minAmount).toLocaleString()}~${max} → ${t.rate}%`
      })
      .join('\n')
    const confirmed = await ElMessageBox.confirm(
      `请确认阶梯配置 (营销员月度累计业绩跨档):\n${summary}`,
      '保存前确认',
      { type: 'info', confirmButtonText: '确认保存', cancelButtonText: '再改改', dangerouslyUseHTMLString: false },
    ).then(() => true).catch(() => false)
    if (!confirmed) return
  }

  saving.value = true
  try {
    const body: Partial<CommissionRule> = {
      effectiveFrom: form.effectiveFrom,
      effectiveTo: form.effectiveTo,
      periodType: form.periodType,
      salesId: form.salesId,
      customerType: form.customerType || null,
      active: form.active,
      leaderboardFormula: form.leaderboardFormula,
    }
    if (ruleMode.value === 'flat') {
      body.percentage = form.percentage
      body.tierConfig = null
    } else {
      body.percentage = 0
      body.tierConfig = tierList.value
    }
    if (editingId.value != null) {
      (body as any).version = (form as any).version
      await updateRule(props.factoryId, editingId.value, body)
      ElMessage.success('规则已更新')
    } else {
      await createRule(props.factoryId, body)
      ElMessage.success('规则已创建')
    }
    dialogOpen.value = false
    await loadData()
    emit('refresh')
  } catch (e: any) {
    const errorCode = e?.response?.data?.errorCode
    if (errorCode === 'VERSION_CONFLICT') {
      ElMessage.warning('规则被他人修改, 刷新后重试')
      await loadData()
      dialogOpen.value = false
    }
  } finally {
    saving.value = false
  }
}

async function toggleActive(row: CommissionRule) {
  if (!row.id) return
  try {
    await updateRule(props.factoryId, row.id, {
      version: row.version,
      active: !row.active,
    })
    ElMessage.success(row.active ? '已停用' : '已启用')
    await loadData()
    emit('refresh')
  } catch (e: any) {
    const errorCode = e?.response?.data?.errorCode
    if (errorCode === 'VERSION_CONFLICT') {
      ElMessage.warning('规则被他人修改, 刷新后重试')
      await loadData()
    }
  }
}

async function onDelete(row: CommissionRule) {
  if (!row.id) return
  await ElMessageBox.confirm(
    '确认删除此提成规则? (软删除, 不影响历史业绩)',
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  ).catch((): null => null).then(async (confirmed: unknown): Promise<void> => {
    if (!confirmed) return
    if (!row.id) return
    await deleteRule(props.factoryId, row.id)
    ElMessage.success('规则已删除')
    await loadData()
    emit('refresh')
  })
}

onMounted(loadData)
</script>

<style scoped>
.commission-rules-panel {
  padding: 8px 0;
}
.toolbar {
  margin-bottom: 12px;
  display: flex;
  gap: 8px;
}
.tier-list {
  width: 100%;
}
.tier-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.tier-idx {
  width: 30px;
  color: #909399;
}
.tier-sep {
  color: #909399;
  margin: 0 4px;
}
.tier-preview {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
}
</style>
