<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import { getRestockHorizon, type RestockHorizonProductRow, type RestockHorizonDayCell } from '@/api/restockBoard'
import { post } from '@/api/request'
import { useAuthStore } from '@/store/modules/auth'
import { usePermissionStore } from '@/store/modules/permission'

const authStore = useAuthStore()
const permissionStore = usePermissionStore()
const router = useRouter()
const route = useRoute()
const factoryId = computed(() => authStore.factoryId)

// T136 — 产品字典跳转权限门控 (fool-proof Rule 5: dead-end → next-action navigation)
const canAccessProducts = computed(() => permissionStore.canAccess('system'))

function goConfigureProduct() {
  const returnTo = encodeURIComponent(route.fullPath)
  router.push(`/system/products?_returnTo=${returnTo}`)
}

const loading = ref(false)

// 动态默认日期: 今天 → 今天+4天 (避免硬编码日期过期后看板默认到过去)
function isoDate(d: Date) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}
const today = new Date()
const DEFAULT_START_DATE = isoDate(today)
const DEFAULT_END_DATE = isoDate(new Date(today.getTime() + 4 * 24 * 60 * 60 * 1000))

function queryDate(name: 'startDate' | 'endDate', fallback: string) {
  const value = route.query[name]
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : fallback
}

const startDate = ref(queryDate('startDate', DEFAULT_START_DATE))
const endDate = ref(queryDate('endDate', DEFAULT_END_DATE))
const dates = ref<string[]>([])
const rows = ref<RestockHorizonProductRow[]>([])
const summary = ref({ totalProducts: 0, shortfallProducts: 0, fullyCoveredProducts: 0, days: 0 })

/**
 * T134: "计划覆盖充足但 WH-LOG 不足" banner 条件。
 * 当任意产品满足以下条件时显示 warning banner：
 *   - endingShortfallQty == 0（区间缺口为零，计划已覆盖）
 *   - fgShippableQty < totalDemandQty（WH-LOG 现货不足以满足需求，无法立即发货）
 */
const hasCoveredButNotShippable = computed(() =>
  rows.value.some(
    (r) =>
      r.endingShortfallQty === 0 &&
      r.fgShippableQty < r.totalDemandQty,
  ),
)

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

    <!-- T134 banner: 计划覆盖充足但 WH-LOG 物流仓现货不足 -->
    <el-alert
      v-if="hasCoveredButNotShippable"
      type="warning"
      :closable="true"
      show-icon
      class="wh-log-banner"
    >
      <template #title>
        部分产品计划覆盖充足，但 WH-LOG（物流仓）成品不足，尚不可立即发货 —— 需等生产完工 / 反向调拨到物流仓
      </template>
      <template #default>
        查看各行「可发(WH-LOG)」列了解物流仓现货，或检查生产仓是否有待调拨批次。
      </template>
    </el-alert>

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
              <div>
                <span class="term">
                  原料可产
                  <el-tooltip content="原料库存 × 原料→成品转换率的估算可产盒数；原料需投料生产，非现货。" placement="top">
                    <el-icon class="head-help"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </span>
                : {{ fmt(row.rawEstimatedFgQty) }} {{ row.unit }}
              </div>
            </div>
            <div>
              <div class="muted">半成品</div>
              <div>结存: {{ fmt(row.wipAvailableQty) }} kg</div>
              <div>
                <span class="term">
                  折成品
                  <el-tooltip content="半成品库存(kg) × 出成率 × 1000 ÷ 克重 = 估算可产成品盒数。" placement="top">
                    <el-icon class="head-help"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </span>
                : {{ fmt(row.wipEstimatedQty) }} {{ row.unit }}
              </div>
            </div>
            <div>
              <div class="muted">出成率</div>
              <div>
                <span class="term">
                  原料->半成品
                  <el-tooltip content="原料→成品转换率 ÷ 半成品→成品出成率，推算的理论比例，仅参考。" placement="top">
                    <el-icon class="head-help"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </span>
                : {{ fmt(row.rawToWipYield, 4) }}
              </div>
              <div>
                <span class="term">
                  半成品->成品
                  <el-tooltip content="半成品加工为成品的出成率，产品字典配置；未配置按 1:1 估算。" placement="top">
                    <el-icon class="head-help"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </span>
                : {{ fmt(row.wipToFgYield, 4) }}
              </div>
              <div>
                <span class="term">
                  原料->成品
                  <el-tooltip content="每单位原料可产成品（盒）的转换率，在原料转换率配置维护。" placement="top">
                    <el-icon class="head-help"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </span>
                : {{ fmt(row.rawToFgYield, 4) }}
              </div>
            </div>
            <!-- T136: 出成率未配置等告警 + 权限门控「去配置」跳转 (fool-proof Rule 5) -->
            <div v-if="row.conversionWarning" class="warning-icon-line">
              <el-tooltip :content="row.conversionWarning" placement="top">
                <span class="warning-text">⚠️ 配置提示</span>
              </el-tooltip>
              <el-button
                v-if="canAccessProducts"
                link
                type="primary"
                size="small"
                style="padding: 0 0 0 4px; font-size: 12px; vertical-align: middle;"
                @click="goConfigureProduct()"
              >去配置</el-button>
            </div>
          </div>
        </template>
      </el-table-column>

      <el-table-column prop="productName" label="产品" min-width="160" fixed />
      <el-table-column width="100">
        <template #header>
          <span>总需求 (盒)</span>
          <el-tooltip placement="top">
            <template #content>区间内所有交货日订单需求合计（盒）。<br />仅含已确认/待审/已审/处理中订单；草稿和取消不计。</template>
            <el-icon class="head-help"><QuestionFilled /></el-icon>
          </el-tooltip>
        </template>
        <template #default="{ row }">{{ fmt(row.totalDemandQty) }}</template>
      </el-table-column>
      <el-table-column width="128">
        <template #header>
          <span>可用总量 (盒)</span>
          <el-tooltip placement="top">
            <template #content>可保障发货的总量 = 成品库存 + 半成品折算 + 已排产（未开工）。<br />不含原料估算（原料需生产才变成货）。这是判断缺口的基准。</template>
            <el-icon class="head-help"><QuestionFilled /></el-icon>
          </el-tooltip>
        </template>
        <template #default="{ row }">
          <span class="cover-qty">{{ fmt(row.startingCoverQty) }}</span>
        </template>
      </el-table-column>
      <el-table-column width="90">
        <template #header>
          <span>成品 (盒)</span>
          <el-tooltip placement="top">
            <template #content>当前库存中状态为"可用"的成品（盒）。<br />仅含以"盒"为单位入库的批次。</template>
            <el-icon class="head-help"><QuestionFilled /></el-icon>
          </el-tooltip>
        </template>
        <template #default="{ row }">{{ fmt(row.fgAvailableQty) }}</template>
      </el-table-column>
      <el-table-column width="120">
        <template #header>
          <span>可发(WH-LOG)</span>
          <el-tooltip placement="top">
            <template #content>WH-LOG 物流仓现货（盒），只有此仓成品能直接发货。<br />生产仓（WH-WKS）成品需先调拨到物流仓才可发货。<br />此值 &lt; 总需求时即使区间覆盖充足也无法立即发货。</template>
            <el-icon class="head-help"><QuestionFilled /></el-icon>
          </el-tooltip>
        </template>
        <template #default="{ row }">
          <span :class="row.fgShippableQty < row.totalDemandQty && row.endingShortfallQty === 0 ? 'shippable-warning' : ''">
            {{ fmt(row.fgShippableQty) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column width="120">
        <template #header>
          <span>半成品（折盒）</span>
          <el-tooltip placement="top">
            <template #content>半成品 kg × 出成率 × 1000 ÷ 克重 = 估算可产成品盒数。<br />出成率未配置时按 1:1（偏乐观）。"估"标表示非精确值。</template>
            <el-icon class="head-help"><QuestionFilled /></el-icon>
          </el-tooltip>
        </template>
        <template #default="{ row }">
          <div class="inline-value">
            <span>{{ fmt(row.wipEstimatedQty) }}</span>
            <el-tag v-if="hasWipEstimate(row)" size="small" type="warning" effect="plain">估</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column width="124">
        <template #header>
          <span>原料可产（参考）</span>
          <el-tooltip placement="top">
            <template #content>原料库存 × 原料→成品转换率的估算值（盒）。<br />仅供参考，不计入缺口 — 原料需安排生产，非现货。</template>
            <el-icon class="head-help"><QuestionFilled /></el-icon>
          </el-tooltip>
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
      <el-table-column width="90">
        <template #header>
          <span>已排产 (盒)</span>
          <el-tooltip placement="top">
            <template #content>已提交但尚未开工的生产计划总量（盒）= 状态"待计划/待确认"的计划。<br />已开工/完成的不重复计入。</template>
            <el-icon class="head-help"><QuestionFilled /></el-icon>
          </el-tooltip>
        </template>
        <template #default="{ row }">{{ fmt(row.scheduledQty) }}</template>
      </el-table-column>

      <el-table-column
        v-for="(date, index) in dates"
        :key="date"
        :label="date.slice(5)"
        min-width="150"
        :class-name="index === 0 ? 'day-matrix-first' : ''"
        :header-align="'center'"
      >
        <template #header>
          <span>{{ date.slice(5) }}</span>
          <el-tooltip placement="top">
            <template #content>需：当日订单需求。<br />余：满足需求后剩余（滚入次日）。<br />缺：当日缺口（超出剩余部分）。<br />各日依次扣减，非独立计算。</template>
            <el-icon class="head-help"><QuestionFilled /></el-icon>
          </el-tooltip>
        </template>
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

      <el-table-column label="缺口" width="90" fixed="right">
        <template #header>
          <span>缺口 (盒)</span>
          <el-tooltip content="区间内累计无法满足的需求（盒）。>0 需补产，点「建计划」。" placement="top">
            <el-icon class="head-help"><QuestionFilled /></el-icon>
          </el-tooltip>
        </template>
        <template #default="{ row }">
          <span v-if="row.endingShortfallQty > 0" class="shortfall-strong">{{ fmt(row.endingShortfallQty) }}</span>
          <span v-else class="covered-dash">-</span>
        </template>
      </el-table-column>

      <el-table-column label="期末" width="120" fixed="right">
        <template #header>
          <span>期末</span>
          <el-tooltip placement="top">
            <template #content>绿色"余 X"：全部交货日已覆盖，还剩 X 盒。<br />橙色"缺 X"：区间内累计缺口 X 盒，点「建计划」补产。</template>
            <el-icon class="head-help"><QuestionFilled /></el-icon>
          </el-tooltip>
        </template>
        <template #default="{ row }">
          <el-tag :type="statusType(row)">
            {{ row.endingShortfallQty > 0 ? `缺 ${fmt(row.endingShortfallQty)}` : `余 ${fmt(row.endingAvailableQty)}` }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.endingShortfallQty > 0"
            size="small"
            type="warning"
            @click="createPlan(row)"
          >
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

.shortfall-strong {
  color: #f56c6c;
  font-weight: 600;
}

.covered-dash {
  color: #67c23a;
}

.head-help {
  cursor: help;
  margin-left: 4px;
  vertical-align: middle;
}

.term {
  align-items: center;
  display: inline-flex;
}

.warning-icon-line {
  align-self: center;
}

/* 日期矩阵第一列加左分隔, 与汇总列视觉区分 */
.horizon-table :deep(.day-matrix-first) {
  border-left: 2px solid #dcdfe6;
  background-color: #fafafa;
}

.expand-grid {
  display: grid;
  gap: 14px;
  grid-template-columns: repeat(4, minmax(180px, 1fr));
  padding: 8px 32px;
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

.wh-log-banner {
  margin-bottom: 10px;
}

.shippable-warning {
  color: #e6a23c;
  font-weight: 600;
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
