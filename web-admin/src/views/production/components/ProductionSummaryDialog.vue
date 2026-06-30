<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getProductionSummary } from '@/api/productionPlan'
import type { ProductionSummaryDTO } from '@/api/productionPlan'
import { handleCatchError } from '@/utils/errorToast'

const props = defineProps<{
  modelValue: boolean
  factoryId: string
  planId: string
  planNumber?: string
  productName?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', val: boolean): void
}>()

const loading = ref(false)
const summary = ref<ProductionSummaryDTO | null>(null)

watch(
  () => props.modelValue,
  (open) => {
    if (open && props.planId) {
      void loadSummary()
    } else if (!open) {
      summary.value = null
    }
  },
)

async function loadSummary() {
  if (!props.factoryId || !props.planId) return
  loading.value = true
  summary.value = null
  try {
    const res = await getProductionSummary(props.factoryId, props.planId)
    if (res.success && res.data) {
      summary.value = res.data
    } else {
      ElMessage({ message: res.message || '加载生产汇总失败', type: 'error', duration: 0, showClose: true })
    }
  } catch (e) {
    handleCatchError(e, '加载生产汇总失败')
  } finally {
    loading.value = false
  }
}

function handleClose() {
  emit('update:modelValue', false)
}

// Fix 1: backend already returns value as percent (e.g. 88.89 means 88.89%) — drop the ×100
function formatYieldRate(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '—'
  return `${Number(rate).toFixed(2)} %`
}

function formatCost(cost: number | null, masked: boolean): string {
  if (masked) return '无权限'
  if (cost === null || cost === undefined) return '—'
  return `¥ ${cost.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

// Fix 7: add real item statuses ACTIVE (在制) and DEPLETED (已耗尽)
function batchStatusLabel(status: string): string {
  const map: Record<string, string> = {
    PENDING: '待处理',
    IN_PROGRESS: '进行中',
    ACTIVE: '在制',
    COMPLETED: '已完成',
    DEPLETED: '已耗尽',
    CANCELLED: '已取消',
  }
  return map[status] ?? status
}

function batchStatusType(status: string): 'success' | 'warning' | 'danger' | 'info' | '' {
  if (status === 'COMPLETED') return 'success'
  if (status === 'ACTIVE' || status === 'IN_PROGRESS') return 'warning'
  if (status === 'DEPLETED') return 'info'
  if (status === 'CANCELLED') return 'danger'
  return 'info'
}

// Fix 3: identity display prefers props (always populated from list row) over summary fields (may be null)
function resolvedPlanNumber(): string {
  return props.planNumber || summary.value?.planNumber || ''
}

function resolvedProductName(): string {
  return props.productName || summary.value?.productName || ''
}

// Fix 6: detect "no 逐道录入 data" — no batches and no raw input
function hasProcessData(): boolean {
  if (!summary.value) return false
  return (summary.value.batches?.length ?? 0) > 0 || (summary.value.totalRawInput ?? 0) > 0
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="resolvedProductName() || resolvedPlanNumber()
      ? `阅读汇总 — ${resolvedProductName()} (${resolvedPlanNumber()})`
      : '阅读汇总'"
    width="760px"
    top="4vh"
    destroy-on-close
    append-to-body
    @close="handleClose"
  >
    <div v-loading="loading" style="min-height: 120px;">
      <template v-if="!loading && summary">

        <!-- Fix 6: empty state when no 逐道录入 data (no batches + no raw input) -->
        <template v-if="!hasProcessData()">
          <el-empty description="此计划暂无逐道录入数据，无法生成阅读汇总" />
        </template>

        <!-- Normal data view -->
        <template v-else>
          <!-- 5 核心指标 -->
          <el-descriptions :column="2" border style="margin-bottom: 20px;">
            <el-descriptions-item label="计划单号" :span="1">
              <!-- Fix 3: prefer props, fall back to summary -->
              {{ resolvedPlanNumber() }}
            </el-descriptions-item>
            <el-descriptions-item label="产品名称" :span="1">
              <!-- Fix 3: prefer props, fall back to summary -->
              {{ resolvedProductName() }}
            </el-descriptions-item>
            <el-descriptions-item label="总投入原料（kg）" :span="1">
              <span style="font-size: 15px; font-weight: 600;">
                {{ summary.totalRawInput.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 3 }) }} kg
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="总产出成品（kg）" :span="1">
              <span style="font-size: 15px; font-weight: 600;">
                {{ summary.totalFinishedOutput.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 3 }) }} kg
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="剩余半成品（kg）" :span="1">
              {{ summary.remainingSemiFinished.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 3 }) }} kg
            </el-descriptions-item>
            <el-descriptions-item label="真实总出成率" :span="1">
              <span
                style="font-size: 16px; font-weight: 700;"
                :style="{
                  color: summary.realYieldRate >= 80 ? '#67c23a' : summary.realYieldRate >= 60 ? '#e6a23c' : '#f56c6c'
                }"
              >
                <!-- Fix 1: formatYieldRate no longer multiplies by 100 -->
                {{ formatYieldRate(summary.realYieldRate) }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="总成本" :span="2">
              <span
                :style="{ color: summary.priceMasked ? '#909399' : 'inherit', fontStyle: summary.priceMasked ? 'italic' : 'normal' }"
                style="font-size: 15px; font-weight: 600;"
              >
                {{ formatCost(summary.totalCost, summary.priceMasked) }}
              </span>
              <el-tag v-if="summary.priceMasked" type="info" size="small" style="margin-left: 8px;">无采购价权限</el-tag>
            </el-descriptions-item>
          </el-descriptions>

          <!-- 批次明细 -->
          <div style="margin-bottom: 8px; font-weight: 600; color: #303133;">批次明细（共 {{ summary.batches.length }} 批）</div>
          <el-table
            :data="summary.batches"
            border
            size="small"
            style="width: 100%;"
            :max-height="320"
            empty-text="暂无批次数据"
          >
            <el-table-column prop="batchNumber" label="批号" min-width="140" />
            <el-table-column label="工序" min-width="100">
              <template #default="{ row }">
                <span>{{ row.processName || `工序 ${row.processOrder}` }}</span>
              </template>
            </el-table-column>
            <el-table-column label="本批产出 (kg)" min-width="110" align="right">
              <template #default="{ row }">
                {{ row.produced.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 3 }) }}
              </template>
            </el-table-column>
            <el-table-column label="剩余 (kg)" min-width="100" align="right">
              <template #default="{ row }">
                {{ row.remaining.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 3 }) }}
              </template>
            </el-table-column>
            <el-table-column label="累计出成率" min-width="110" align="center">
              <template #default="{ row }">
                <!-- Fix 1: cumulativeYieldRate also already a percent, no ×100 -->
                {{ formatYieldRate(row.cumulativeYieldRate) }}
              </template>
            </el-table-column>
            <el-table-column label="状态" min-width="90" align="center">
              <template #default="{ row }">
                <!-- Fix 7: now handles ACTIVE / DEPLETED -->
                <el-tag :type="batchStatusType(row.status)" size="small">
                  {{ batchStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </template>

      </template>

      <el-empty v-else-if="!loading && !summary" description="暂无汇总数据" />
    </div>

    <template #footer>
      <el-button @click="handleClose">关闭</el-button>
    </template>
  </el-dialog>
</template>
