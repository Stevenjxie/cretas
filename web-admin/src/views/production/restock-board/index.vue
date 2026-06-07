<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getRestockHorizon, type RestockHorizonProductRow, type RestockHorizonDayCell } from '@/api/restockBoard'
import { post } from '@/api/request'
import { useAuthStore } from '@/store/modules/auth'

const authStore = useAuthStore()
const route = useRoute()
const factoryId = computed(() => authStore.factoryId)

const loading = ref(false)
const DEFAULT_START_DATE = '2026-05-31'
const DEFAULT_END_DATE = '2026-06-04'

function queryDate(name: 'startDate' | 'endDate', fallback: string) {
  const value = route.query[name]
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : fallback
}

const startDate = ref(queryDate('startDate', DEFAULT_START_DATE))
const endDate = ref(queryDate('endDate', DEFAULT_END_DATE))
const dates = ref<string[]>([])
const rows = ref<RestockHorizonProductRow[]>([])
const summary = ref({ totalProducts: 0, shortfallProducts: 0, fullyCoveredProducts: 0, days: 0 })

async function load() {
  if (!factoryId.value) return
  loading.value = true
  try {
    const res = await getRestockHorizon(factoryId.value, startDate.value, endDate.value)
    if (res.success && res.data) {
      dates.value = res.data.dates
      rows.value = res.data.rows
      summary.value = res.data.summary
    } else if (res.success === false) {
      ElMessage({ message: res.message || '加载失败', type: 'error', duration: 0, showClose: true })
    }
  } catch {
    // request interceptor shows the business error
  } finally {
    loading.value = false
  }
}

function fmt(value: number | null | undefined, digits = 2) {
  if (value === null || value === undefined) return '-'
  if (!Number.isFinite(value)) return '-'
  return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: digits })
}

function dayCell(row: RestockHorizonProductRow, date: string): RestockHorizonDayCell | undefined {
  return row.days.find(item => item.deliveryDate === date)
}

function statusType(row: RestockHorizonProductRow) {
  return row.endingShortfallQty > 0 ? 'warning' : 'success'
}

function hasWipEstimate(row: RestockHorizonProductRow) {
  return row.wipEstimatedQty !== null && row.wipEstimatedQty !== undefined && row.wipEstimatedQty > 0
}

async function createPlan(row: RestockHorizonProductRow) {
  if (!row.endingShortfallQty || row.endingShortfallQty <= 0 || !factoryId.value) return
  const today = new Date().toISOString().slice(0, 10)
  try {
    await ElMessageBox.confirm(
      `产品: ${row.productName}\n建议补产: ${fmt(row.endingShortfallQty)} ${row.unit}\n覆盖区间: ${startDate.value} 至 ${endDate.value}\n计划生产日: ${today}`,
      '缺口转生产计划',
      { confirmButtonText: '生成草稿', cancelButtonText: '取消', type: 'warning' },
    )
    const res = await post(`/${factoryId.value}/production-plans`, {
      sourceType: 'SAFETY_STOCK',
      productTypeId: row.productTypeId,
      plannedQuantity: row.endingShortfallQty,
      plannedDate: today,
      notes: `来自 ${startDate.value} 至 ${endDate.value} 备货看板缺口`,
    })
    if (res.success) {
      ElMessage.success('生产计划草稿已生成')
      await load()
    } else {
      ElMessage({ message: res.message || '生成失败', type: 'error', duration: 0, showClose: true })
    }
  } catch {
    // user cancelled
  }
}

onMounted(load)
</script>

<template>
  <div class="restock-page">
    <div class="toolbar">
      <el-space wrap>
        <span class="label">日期</span>
        <el-date-picker v-model="startDate" type="date" value-format="YYYY-MM-DD" />
        <span class="label">至</span>
        <el-date-picker v-model="endDate" type="date" value-format="YYYY-MM-DD" />
        <el-button type="primary" @click="load">查询</el-button>
      </el-space>
      <el-space wrap>
        <el-tag>产品 {{ summary.totalProducts }}</el-tag>
        <el-tag type="success">覆盖 {{ summary.fullyCoveredProducts }}</el-tag>
        <el-tag type="warning">缺口 {{ summary.shortfallProducts }}</el-tag>
        <el-tag type="info">{{ summary.days }} 天</el-tag>
      </el-space>
    </div>

    <el-table
      :data="rows"
      v-loading="loading"
      border
      stripe
      row-key="productTypeId"
      empty-text="所选日期没有订单需求"
      class="horizon-table"
    >
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="expand-grid">
            <div>
              <div class="muted">原料</div>
              <div>{{ row.rawMaterialName || '-' }}: {{ fmt(row.rawAvailableQty) }} {{ row.rawUnit || '' }}</div>
              <div>原料可产: {{ fmt(row.rawEstimatedFgQty) }} {{ row.unit }}</div>
            </div>
            <div>
              <div class="muted">半成品</div>
              <div>结存: {{ fmt(row.wipAvailableQty) }} kg</div>
              <div>折成品: {{ fmt(row.wipEstimatedQty) }} {{ row.unit }}</div>
            </div>
            <div>
              <div class="muted">出成率</div>
              <div>原料->半成品: {{ fmt(row.rawToWipYield, 4) }}</div>
              <div>半成品->成品: {{ fmt(row.wipToFgYield, 4) }}</div>
              <div>原料->成品: {{ fmt(row.rawToFgYield, 4) }}</div>
            </div>
            <div v-if="row.conversionWarning" class="warning-text">{{ row.conversionWarning }}</div>
          </div>
        </template>
      </el-table-column>

      <el-table-column prop="productName" label="产品" min-width="160" fixed />
      <el-table-column label="总需求" width="100">
        <template #default="{ row }">{{ fmt(row.totalDemandQty) }}</template>
      </el-table-column>
      <el-table-column width="128">
        <template #header>
          <div class="column-header">
            <span>可扣减覆盖</span>
            <span class="header-note">不含原料估算</span>
          </div>
        </template>
        <template #default="{ row }">
          <span class="cover-qty">{{ fmt(row.startingCoverQty) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="成品" width="90">
        <template #default="{ row }">{{ fmt(row.fgAvailableQty) }}</template>
      </el-table-column>
      <el-table-column label="半成品折算" width="110">
        <template #default="{ row }">
          <div class="inline-value">
            <span>{{ fmt(row.wipEstimatedQty) }}</span>
            <el-tag v-if="hasWipEstimate(row)" size="small" type="warning" effect="plain">估</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column width="112">
        <template #header>
          <div class="column-header">
            <span>原料估算</span>
            <span class="header-note">参考，不扣减</span>
          </div>
        </template>
        <template #default="{ row }">
          <el-tooltip content="需投料生产，非现货，不参与缺口扣减" placement="top">
            <el-tag
              v-if="row.rawEstimatedFgQty !== null && row.rawEstimatedFgQty !== undefined"
              type="info"
              effect="plain"
              class="raw-tag"
            >
              {{ fmt(row.rawEstimatedFgQty) }}
            </el-tag>
            <span v-else class="muted">未配置</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="已排产" width="90">
        <template #default="{ row }">{{ fmt(row.scheduledQty) }}</template>
      </el-table-column>

      <el-table-column v-for="date in dates" :key="date" :label="date.slice(5)" min-width="150">
        <template #default="{ row }">
          <div v-if="dayCell(row, date)" class="day-cell">
            <div>需 {{ fmt(dayCell(row, date)?.demandQty) }}</div>
            <div>余 {{ fmt(dayCell(row, date)?.availableAfterDemandQty) }}</div>
            <div v-if="(dayCell(row, date)?.shortfallQty || 0) > 0" class="shortfall">
              缺 {{ fmt(dayCell(row, date)?.shortfallQty) }}
            </div>
            <div v-else-if="dayCell(row, date)?.status === 'UNIT_INCONSISTENT'" class="warning-text">
              单位待核
            </div>
          </div>
          <span v-else>-</span>
        </template>
      </el-table-column>

      <el-table-column label="期末" width="110" fixed="right">
        <template #default="{ row }">
          <el-tag :type="statusType(row)">
            {{ row.endingShortfallQty > 0 ? `缺 ${fmt(row.endingShortfallQty)}` : `余 ${fmt(row.endingAvailableQty)}` }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.endingShortfallQty > 0" text type="primary" @click="createPlan(row)">
            建计划
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.restock-page {
  padding: 12px;
}

.toolbar {
  align-items: center;
  border-bottom: 1px solid #e5e7eb;
  display: flex;
  justify-content: space-between;
  margin-bottom: 12px;
  padding-bottom: 12px;
}

.label,
.muted {
  color: #606266;
  font-size: 13px;
}

.horizon-table {
  width: 100%;
}

.day-cell {
  font-size: 12px;
  line-height: 1.6;
}

.shortfall,
.warning-text {
  color: #e6a23c;
}

.expand-grid {
  display: grid;
  gap: 14px;
  grid-template-columns: repeat(4, minmax(180px, 1fr));
  padding: 8px 32px;
}

.column-header {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.2;
}

.header-note {
  color: #909399;
  font-size: 11px;
  font-weight: normal;
}

.cover-qty {
  font-weight: 600;
}

.inline-value {
  align-items: center;
  display: flex;
  gap: 4px;
}

.raw-tag {
  border-style: dashed;
}

@media (max-width: 900px) {
  .toolbar {
    align-items: flex-start;
    flex-direction: column;
    gap: 10px;
  }

  .expand-grid {
    grid-template-columns: 1fr;
  }
}
</style>
