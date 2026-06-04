<!--
  餐饮营销员阶梯提成汇总 (#59 Phase 2).

  邓总方案: 营销员月度累计复购业绩跨 15万/30万/50万 档位 (费率走提成规则 tierConfig UI 配).
  本页: 选营销员 + 月份 → 当月累计业绩 / 所处档位 / 计业绩到访次数 + 逐笔提成明细 (可标记发放).

  金额字段为 @PriceSensitive, 后端按价权角色自动剥离 (无权限显 "—").
-->
<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span class="page-title">营销员提成汇总</span>
          <span class="data-count">月度累计阶梯 · 复购业绩跨档</span>
        </div>
      </template>

      <!-- 查询条件 -->
      <el-form :inline="true" class="filter-bar">
        <el-form-item label="营销员 ID">
          <el-input-number v-model="repId" :min="1" :controls="false" placeholder="用户 ID" style="width: 140px" />
        </el-form-item>
        <el-form-item label="月份">
          <el-date-picker v-model="month" type="month" value-format="YYYY-MM" placeholder="本月" style="width: 150px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="loadAll">查询</el-button>
        </el-form-item>
      </el-form>

      <!-- 月度累计汇总卡 -->
      <el-row v-if="summaryLoaded" :gutter="16" class="stat-row">
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">当月累计业绩</span>
            <span class="stat-value">{{ formatMoney(summary.cumulativeRevenue) }}</span>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">当前档位</span>
            <span class="stat-value">{{ tierLabel(summary.currentTier) }}</span>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">计业绩到访</span>
            <span class="stat-value">{{ summary.attributedVisitCount ?? 0 }} 次</span>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">查询月份</span>
            <span class="stat-value">{{ summary.month || summary.periodKey || '—' }}</span>
          </div>
        </el-col>
      </el-row>
      <el-empty
        v-else-if="queried && !summaryLoaded"
        description="该营销员当月暂无计业绩复购到访 (散客第二次复购起才计业绩, 且需先绑定营销员)"
      />

      <!-- 逐笔提成明细 -->
      <el-table v-loading="loading" :data="commissions" stripe style="margin-top: 16px">
        <el-table-column prop="createdAt" label="结算时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="本次营收" width="120">
          <template #default="{ row }">{{ formatMoney(row.visitRevenue) }}</template>
        </el-table-column>
        <el-table-column label="档位" width="90">
          <template #default="{ row }">{{ tierLabel(row.tierSnapshot) }}</template>
        </el-table-column>
        <el-table-column label="费率" width="80">
          <template #default="{ row }">{{ row.rateSnapshot == null ? '—' : `${row.rateSnapshot}%` }}</template>
        </el-table-column>
        <el-table-column label="本次提成" width="120">
          <template #default="{ row }">{{ formatMoney(row.commissionAmount) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'PENDING'"
              size="small"
              type="success"
              @click="onMarkPaid(row)"
            >
              标记发放
            </el-button>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useFactoryId } from '@/composables/useFactoryId'
import {
  listCommissions,
  getRepSummary,
  markCommissionPaid,
  COMMISSION_STATUS_LABELS,
  type RestaurantCommission,
  type RestaurantRepCommissionSummary,
  type CommissionStatus,
} from '@/api/restaurant-commission'

const STATUS_LABELS = COMMISSION_STATUS_LABELS

const factoryId = useFactoryId()
const repId = ref<number | undefined>(undefined)
const month = ref<string | undefined>(undefined)

const loading = ref(false)
const queried = ref(false)
const summaryLoaded = ref(false)
const summary = reactive<RestaurantRepCommissionSummary>({ repId: 0, attributedVisitCount: 0 })
const commissions = ref<RestaurantCommission[]>([])

function tierLabel(tier?: number | null): string {
  if (tier == null) return '单一比率'
  return `第 ${tier + 1} 档`
}

function formatMoney(v?: number | null): string {
  if (v == null) return '—'   // @PriceSensitive 剥离后为 null
  return `¥${Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function formatTime(v?: string): string {
  return v ? v.replace('T', ' ').slice(0, 16) : '—'
}

function statusTagType(s: CommissionStatus): 'warning' | 'success' | 'info' {
  if (s === 'PAID') return 'success'
  if (s === 'CANCELLED') return 'info'
  return 'warning'
}

function statusLabel(s: CommissionStatus): string {
  return STATUS_LABELS[s] ?? String(s)
}

async function loadAll() {
  if (!factoryId.value) return
  if (repId.value == null) {
    ElMessage.warning('请输入营销员 ID')
    return
  }
  loading.value = true
  queried.value = true
  try {
    const [sumRes, listRes] = await Promise.all([
      getRepSummary(factoryId.value, repId.value, month.value),
      listCommissions(factoryId.value, { repId: repId.value, page: 0, size: 100 }),
    ])
    if (sumRes.success && sumRes.data && sumRes.data.hasData !== false) {
      Object.assign(summary, sumRes.data)
      summaryLoaded.value = true
    } else {
      summaryLoaded.value = false
    }
    commissions.value = listRes.success && listRes.data ? listRes.data.content : []
  } finally {
    loading.value = false
  }
}

async function onMarkPaid(row: RestaurantCommission) {
  const confirmed = await ElMessageBox.confirm(
    `确认将这笔提成标记为已发放? (营收 ${formatMoney(row.visitRevenue)} / 提成 ${formatMoney(row.commissionAmount)})`,
    '标记发放',
    { type: 'warning', confirmButtonText: '确认发放', cancelButtonText: '取消' },
  ).then(() => true).catch(() => false)
  if (!confirmed) return
  try {
    await markCommissionPaid(factoryId.value, row.id)
    ElMessage.success('已标记发放')
    await loadAll()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '标记发放失败')
  }
}
</script>

<style scoped>
.page-wrapper {
  padding: 16px;
}
.card-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.page-title {
  font-size: 16px;
  font-weight: 600;
}
.data-count {
  color: #909399;
  font-size: 13px;
}
.filter-bar {
  margin-bottom: 12px;
}
.stat-row {
  margin-bottom: 8px;
}
.stat-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
}
.stat-label {
  color: #909399;
  font-size: 13px;
}
.stat-value {
  font-size: 20px;
  font-weight: 600;
}
</style>
