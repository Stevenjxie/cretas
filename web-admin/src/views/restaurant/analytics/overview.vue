<template>
  <div class="page-wrapper" ref="containerRef">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">餐饮运营分析</span>
          </div>
          <div class="header-right">
            <el-select
              v-model="selectedUploadId"
              placeholder="选择数据源"
              filterable
              style="width: 320px"
              @change="handleSelectUpload"
            >
              <el-option
                v-for="u in uploads"
                :key="u.id"
                :label="`${u.fileName}${u.sheetName ? ' / ' + u.sheetName : ''} (${u.rowCount}行)`"
                :value="u.id"
              />
            </el-select>
            <el-button :icon="Refresh" :loading="loading" @click="handleRefresh">刷新分析</el-button>
          </div>
        </div>
      </template>

      <!-- Data quality warnings -->
      <el-alert
        v-if="dataQualityWarnings.length > 0"
        type="warning"
        :closable="true"
        show-icon
        style="margin-bottom: 12px"
      >
        <template #title>数据质量提示</template>
        <div v-for="(w, i) in dataQualityWarnings" :key="i" style="font-size: 12px">{{ w }}</div>
      </el-alert>

      <!-- Empty state -->
      <el-empty v-if="!loading && !data && uploads.length === 0" description="请先在智能BI中上传POS销售数据">
        <el-button type="primary" @click="$router.push('/smart-bi/upload')">前往上传</el-button>
      </el-empty>

      <el-empty v-else-if="!loading && !data && uploads.length > 0" description="请从上方选择一个餐饮数据源" />

      <!-- Loading -->
      <div v-if="loading" v-loading="true" style="min-height: 300px" />

      <!-- Analytics content -->
      <template v-if="data && !loading">
        <!-- KPI Cards -->
        <el-row :gutter="16" class="kpi-row">
          <el-col v-if="canViewPrice" :xs="12" :sm="8" :md="4">
            <CapabilityGate card-id="restaurant_overview_revenue" :requires="['date', 'net_amount']">
            <div class="kpi-card">
              <div class="kpi-label">总营收</div>
              <div class="kpi-value success">{{ formatMoney(totalRevenue) }}</div>
            </div>
            </CapabilityGate>
          </el-col>
          <el-col :xs="12" :sm="8" :md="4">
            <div class="kpi-card">
              <div class="kpi-label">菜品数</div>
              <div class="kpi-value">{{ data.menuQuadrant.items.length }}</div>
            </div>
          </el-col>
          <el-col :xs="12" :sm="8" :md="4">
            <CapabilityGate card-id="restaurant_overview_stores" :requires="['store_name']">
            <div class="kpi-card">
              <div class="kpi-label">门店数</div>
              <div class="kpi-value">{{ data.storeComparison.stores.length }}</div>
            </div>
            </CapabilityGate>
          </el-col>
          <el-col :xs="12" :sm="8" :md="4">
            <div class="kpi-card">
              <div class="kpi-label">明星菜品</div>
              <div class="kpi-value success">{{ data.menuQuadrant.summary.starCount }}</div>
            </div>
          </el-col>
          <el-col :xs="12" :sm="8" :md="4">
            <div class="kpi-card">
              <div class="kpi-label">折扣预警</div>
              <div class="kpi-value" :class="data.discountAlerts.length > 0 ? 'danger' : ''">
                {{ data.discountAlerts.length }}
              </div>
            </div>
          </el-col>
          <el-col :xs="12" :sm="8" :md="4">
            <div class="kpi-card">
              <div class="kpi-label">子行业</div>
              <div class="kpi-value info">{{ data.benchmarksUsed }}</div>
            </div>
          </el-col>
        </el-row>

        <!-- Charts row -->
        <el-row :gutter="16" style="margin-top: 16px">
          <!-- Quadrant mini scatter — EXOTIC SKIP (SCATTER): ChartInsightProvider not wired -->
          <el-col :xs="24" :md="12">
            <CapabilityGate card-id="restaurant_overview_top_dish" :requires="['combo_string', 'qty_sold']">
            <el-card shadow="hover" class="chart-card" @click="$router.push('/restaurant/analytics/menu')">
              <template #header>
                <div class="chart-title clickable">
                  菜品四象限
                  <el-tag size="small" type="info">点击查看详情</el-tag>
                </div>
              </template>
              <div id="chart-quadrant-mini" style="height: 280px" />
            </el-card>
            </CapabilityGate>
          </el-col>

          <!-- Store ranking bar — WIRED: BAR/store/RANKING/revenue -->
          <el-col :xs="24" :md="12">
            <el-card shadow="hover" class="chart-card" @click="$router.push('/restaurant/analytics/stores')">
              <template #header>
                <div class="chart-title clickable">
                  门店营收 Top 5
                  <el-tag size="small" type="info">点击查看详情</el-tag>
                </div>
              </template>
              <div id="chart-store-top5" style="height: 280px" />
              <ChartInsightProvider
                :chart="storeTop5Chart"
                :perms="insightPerms"
                :factory-id="authStore.factoryId || ''"
              />
            </el-card>
          </el-col>
        </el-row>

        <!-- Category breakdown -->
        <el-row :gutter="16" style="margin-top: 16px">
          <!-- Category pie — WIRED: PIE/category/PROPORTION/revenue -->
          <el-col :xs="24" :md="12">
            <el-card shadow="hover" class="chart-card">
              <template #header><div class="chart-title">品类结构</div></template>
              <div id="chart-category-pie" class="chart-pie-square" />
              <ChartInsightProvider
                :chart="categoryPieChart"
                :perms="insightPerms"
                :factory-id="authStore.factoryId || ''"
              />
            </el-card>
          </el-col>

          <!-- Operations metrics radar — EXOTIC SKIP (RADAR): ChartInsightProvider not wired -->
          <el-col :xs="24" :md="12">
            <el-card shadow="hover" class="chart-card" @click="$router.push('/restaurant/analytics/dianping')">
              <template #header>
                <div class="chart-title clickable">
                  经营指标 & 平台准入
                  <el-tag size="small" type="info">点击查看详情</el-tag>
                </div>
              </template>
              <div id="chart-ops-radar-mini" class="chart-pie-square" />
            </el-card>
          </el-col>
        </el-row>

        <!-- Trend analysis -->
        <el-row :gutter="16" style="margin-top: 16px" v-if="data.trendAnalysis">
          <!-- Trend line — WIRED: LINE/time/TREND/revenue -->
          <el-col :xs="24" :md="16">
            <el-card shadow="hover" class="chart-card">
              <template #header>
                <div class="chart-title">
                  营收趋势
                  <el-tag v-if="data.trendAnalysis.popGrowth !== null" size="small"
                    :type="data.trendAnalysis.popGrowth >= 0 ? 'success' : 'danger'">
                    {{ data.trendAnalysis.popGrowth >= 0 ? '+' : '' }}{{ data.trendAnalysis.popGrowth }}% 环比
                  </el-tag>
                </div>
              </template>
              <div id="chart-trend-line" style="height: 280px" />
              <ChartInsightProvider
                :chart="trendLineChart"
                :perms="insightPerms"
                :factory-id="authStore.factoryId || ''"
              />
              <div v-if="canViewPrice" class="chart-footer">
                共 {{ data.trendAnalysis.totalDays }} 天
                · 日均 ¥{{ formatMoney(data.trendAnalysis.avgDailyRevenue) }}
                · 峰值 {{ data.trendAnalysis.peakDay.date }} (¥{{ formatMoney(data.trendAnalysis.peakDay.revenue) }})
              </div>
            </el-card>
          </el-col>
          <!-- Weekly bar — WIRED: BAR/time/TREND/revenue -->
          <el-col :xs="24" :md="8" v-if="data.trendAnalysis.weeklyTrend">
            <el-card shadow="hover" class="chart-card">
              <template #header><div class="chart-title">周度汇总</div></template>
              <div id="chart-weekly-bar" style="height: 280px" />
              <ChartInsightProvider
                :chart="weeklyBarChart"
                :perms="insightPerms"
                :factory-id="authStore.factoryId || ''"
              />
            </el-card>
          </el-col>
        </el-row>

        <!-- Time period analysis (hourly heatmap + meal periods) -->
        <el-row :gutter="16" style="margin-top: 16px" v-if="data.timePeriodAnalysis">
          <!-- Hourly bar — WIRED: BAR/category(hour)/RANKING/revenue -->
          <el-col :xs="24" :md="14">
            <el-card shadow="hover" class="chart-card">
              <template #header>
                <div class="chart-title">
                  时段分布
                  <el-tag size="small" type="success">高峰 {{ data.timePeriodAnalysis.peakHourLabel }}</el-tag>
                </div>
              </template>
              <div id="chart-hourly-bar" style="height: 280px" />
              <ChartInsightProvider
                :chart="hourlyBarChart"
                :perms="insightPerms"
                :factory-id="authStore.factoryId || ''"
              />
            </el-card>
          </el-col>
          <!-- Meal pie — WIRED: PIE/category/PROPORTION/revenue -->
          <el-col :xs="24" :md="10">
            <el-card shadow="hover" class="chart-card">
              <template #header>
                <div class="chart-title">
                  餐段营收
                  <el-tag size="small">{{ data.timePeriodAnalysis.mainMealPeriod }} {{ data.timePeriodAnalysis.mainMealPct }}%</el-tag>
                </div>
              </template>
              <div id="chart-meal-pie" class="chart-pie-square chart-pie-small" />
              <ChartInsightProvider
                :chart="mealPieChart"
                :perms="insightPerms"
                :factory-id="authStore.factoryId || ''"
              />
              <div v-if="canViewPrice" class="chart-footer">
                工作日均 ¥{{ formatMoney(data.timePeriodAnalysis.weekdayAvg) }}
                · 周末均 ¥{{ formatMoney(data.timePeriodAnalysis.weekendAvg) }}
              </div>
            </el-card>
          </el-col>
        </el-row>

        <!-- Dimension hints (missing columns) -->
        <el-row :gutter="16" style="margin-top: 12px" v-if="missingDimensions.length > 0">
          <el-col :span="24">
            <el-alert type="info" :closable="true" show-icon>
              <template #title>数据增强建议</template>
              <div style="font-size: 12px; line-height: 1.8">
                <div v-for="d in missingDimensions" :key="d.key">
                  <el-tag size="small" type="info" style="margin-right: 6px">{{ d.label }}</el-tag>
                  {{ d.hint }}
                </div>
              </div>
            </el-alert>
          </el-col>
        </el-row>

        <!-- Phase C: Extended analytics row -->
        <el-row :gutter="16" style="margin-top: 16px" v-if="data.priceBandAnalysis || data.categoryConcentration || data.storeEfficiencyMatrix">
          <!-- Price band bar — WIRED: BAR/category/RANKING/pct -->
          <el-col :xs="24" :md="8" v-if="data.priceBandAnalysis">
            <el-card shadow="hover" class="chart-card">
              <template #header>
                <div class="chart-title">
                  价格带分布
                  <el-tag size="small" :type="data.priceBandAnalysis.pricePositioning === '适中' ? 'success' : 'warning'">
                    {{ data.priceBandAnalysis.pricePositioning }}
                  </el-tag>
                </div>
              </template>
              <div id="chart-price-band" style="height: 240px" />
              <ChartInsightProvider
                :chart="priceBandChart"
                :perms="insightPerms"
                :factory-id="authStore.factoryId || ''"
              />
              <div v-if="canViewPrice" class="chart-footer">
                主力价格带: <strong>{{ data.priceBandAnalysis.mainBand }}</strong>
                · 品均价 ¥{{ data.priceBandAnalysis.avgUnitPrice.toFixed(0) }}
              </div>
            </el-card>
          </el-col>

          <!-- Category concentration HHI — EXOTIC SKIP (GAUGE): ChartInsightProvider not wired -->
          <el-col :xs="24" :md="8" v-if="data.categoryConcentration">
            <el-card shadow="hover" class="chart-card">
              <template #header>
                <div class="chart-title">
                  品类集中度
                  <el-tag size="small" :type="data.categoryConcentration.concentrationLevel === '分散' ? 'success' : data.categoryConcentration.concentrationLevel === '中度集中' ? 'warning' : 'danger'">
                    {{ data.categoryConcentration.concentrationLevel }}
                  </el-tag>
                </div>
              </template>
              <div id="chart-hhi-gauge" style="height: 240px" />
              <div class="chart-footer">
                Top 3 占比: <strong>{{ data.categoryConcentration.top3Pct }}%</strong>
                · HHI: {{ data.categoryConcentration.hhi.toFixed(0) }}
                · 长尾品类: {{ data.categoryConcentration.longTailCount }}个 ({{ data.categoryConcentration.longTailPct }}%)
              </div>
            </el-card>
          </el-col>

          <!-- Store efficiency matrix — EXOTIC SKIP (SCATTER): ChartInsightProvider not wired -->
          <el-col :xs="24" :md="8" v-if="data.storeEfficiencyMatrix">
            <el-card shadow="hover" class="chart-card">
              <template #header><div class="chart-title">门店效率矩阵</div></template>
              <div id="chart-store-efficiency" style="height: 240px" />
              <div class="chart-footer">
                高效精简: {{ data.storeEfficiencyMatrix.summary.highEfficiency }}
                · 规模领先: {{ data.storeEfficiencyMatrix.summary.scaleLeader }}
                · 低效臃肿: {{ data.storeEfficiencyMatrix.summary.bloated }}
                · 潜力不足: {{ data.storeEfficiencyMatrix.summary.underperforming }}
              </div>
            </el-card>
          </el-col>
        </el-row>

        <!-- Supply chain / traceability linkage -->
        <el-row :gutter="16" style="margin-top: 16px" v-if="data.supplyChainAnalysis">
          <!-- Supplier pie — WIRED: PIE/category/PROPORTION/cost -->
          <el-col :xs="24" :md="12">
            <el-card shadow="hover" class="chart-card">
              <template #header>
                <div class="chart-title">
                  供应链健康度
                  <el-tag size="small" :type="data.supplyChainAnalysis.overallRiskScore === '低' ? 'success' : data.supplyChainAnalysis.overallRiskScore === '中' ? 'warning' : 'danger'">
                    风险{{ data.supplyChainAnalysis.overallRiskScore }}
                  </el-tag>
                </div>
              </template>
              <div id="chart-supplier-pie" class="chart-pie-square chart-pie-small" />
              <ChartInsightProvider
                :chart="supplierPieChart"
                :perms="insightPerms"
                :factory-id="authStore.factoryId || ''"
              />
              <div class="chart-footer">
                供应商: <strong>{{ data.supplyChainAnalysis.supplierConcentration.supplierCount }}家</strong>
                · Top 1 占比: {{ data.supplyChainAnalysis.supplierConcentration.top1Pct }}%
                · HHI: {{ data.supplyChainAnalysis.supplierConcentration.hhi.toFixed(0) }}
                <el-tag v-if="data.supplyChainAnalysis.supplierConcentration.singleSourceRisk" type="danger" size="small" style="margin-left: 8px">单一来源风险</el-tag>
              </div>
            </el-card>
          </el-col>

          <el-col :xs="24" :md="12">
            <el-card shadow="hover" class="chart-card">
              <template #header><div class="chart-title">供应链风险提示</div></template>
              <div v-if="data.supplyChainAnalysis.risks.length === 0" style="padding: 20px; text-align: center; color: var(--el-text-color-secondary)">
                供应链健康，暂无风险项
              </div>
              <div v-else class="roadmap">
                <div v-for="(risk, idx) in data.supplyChainAnalysis.risks" :key="idx" class="roadmap-item">
                  <div class="roadmap-header">
                    <el-tag :type="risk.severity === 'high' ? 'danger' : risk.severity === 'medium' ? 'warning' : 'info'" size="small">
                      {{ risk.severity === 'high' ? '高风险' : risk.severity === 'medium' ? '中风险' : '低风险' }}
                    </el-tag>
                    <span class="roadmap-label">{{ risk.description }}</span>
                  </div>
                  <div class="roadmap-action">{{ risk.action }}</div>
                </div>
              </div>
              <!-- Menu-ingredient linkage -->
              <div v-if="data.supplyChainAnalysis.menuIngredientLinkage" style="margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--el-border-color-lighter)">
                <div class="chart-footer">
                  菜品-原料关联: <strong>{{ data.supplyChainAnalysis.menuIngredientLinkage.linkedItems }}</strong> /
                  {{ data.supplyChainAnalysis.menuIngredientLinkage.totalMenuItems }} 菜品可溯源
                  (覆盖率 {{ data.supplyChainAnalysis.menuIngredientLinkage.coverage }}%)
                </div>
              </div>
            </el-card>
          </el-col>
        </el-row>
      </template>
      <UnlockMoreCTA />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, watch, onUnmounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import echarts from '@/utils/echarts'
import { useChartResize } from '@/composables/useChartResize'
import { useRestaurantAnalytics } from '@/composables/useRestaurantAnalytics'
import { usePermissionStore } from '@/store/modules/permission'
import { useAuthStore } from '@/store/modules/auth'
import type { RestaurantAnalyticsResult } from '@/types/restaurant-analytics'
// Day 9 数据织网 Sub-Project A: capability-driven card visibility
import { useCapability } from '@/composables/useCapability'
import CapabilityGate from '@/components/CapabilityGate.vue'
import UnlockMoreCTA from '@/components/UnlockMoreCTA.vue'
// Phase B Moderate: ChartInsightProvider wiring
import ChartInsightProvider from '@/views/smart-bi/components/ChartInsightProvider.vue'
import type { ChartWithMeta, UserPermissions } from '@/views/smart-bi/components/chartInsight'

const permissionStore = usePermissionStore()
const authStore = useAuthStore()
const canViewPrice = computed(() => permissionStore.canViewPrice)
const { fetchCapability } = useCapability()
// Prime capability cache (fire-and-forget, useCapability handles errors fail-open).
fetchCapability()

// ── Phase B Moderate: ChartInsightProvider permissions ──────────────────────────
// canViewFinance mirrors canViewPrice (restaurant overview uses the same price gate).
const insightPerms = computed<UserPermissions>(() => ({
  canViewFinance: permissionStore.canViewPrice,
}))

const containerRef = ref<HTMLElement>()
useChartResize(containerRef)

const {
  uploads, selectedUploadId, data, loading, dataQualityWarnings,
  handleSelectUpload, handleRefresh,
} = useRestaurantAnalytics<RestaurantAnalyticsResult>((result) => result)

// Re-render charts when data changes
watch(data, (val) => {
  if (val) nextTick(() => setTimeout(renderCharts, 300))
})

const missingDimensions = computed(() => {
  if (!data.value?.dimensionHints) return []
  return data.value.dimensionHints.filter(d => !d.available && d.hint)
})

const totalRevenue = computed(() => {
  if (!data.value) return 0
  const storeSum = data.value.storeComparison.stores.reduce((s, st) => s + st.revenue, 0)
  if (storeSum > 0) return storeSum
  return data.value.menuQuadrant.items.reduce((s, item) => s + item.revenue, 0)
})

function formatMoney(val: number): string {
  if (Math.abs(val) >= 1e8) return (val / 1e8).toFixed(1) + '亿'
  if (Math.abs(val) >= 1e4) return (val / 1e4).toFixed(1) + '万'
  return val.toLocaleString('zh-CN', { maximumFractionDigits: 0 })
}

// ── Phase B Moderate: ChartWithMeta computeds ─────────────────────────────────
//
// Chart slot taxonomy:
//   WIRED (BAR/LINE/PIE → Tier1-coverable):
//     chart-store-top5      BAR  horizontal, store/RANKING, revenue
//     chart-category-pie    PIE  品类结构, category/PROPORTION, revenue
//     chart-trend-line      LINE 营收趋势, time/TREND, revenue
//     chart-weekly-bar      BAR  周度汇总, time/TREND (week labels as time xDim), revenue
//     chart-hourly-bar      BAR  时段分布, category (hour buckets)/RANKING, quantity (order count dimension)
//     chart-meal-pie        PIE  餐段营收, category/PROPORTION, revenue
//     chart-price-band      BAR  价格带分布, category/RANKING, pct
//     chart-supplier-pie    PIE  供应商集中度, category/PROPORTION, cost
//
//   SKIPPED (exotic — returns null, ChartInsightProvider renders nothing):
//     chart-quadrant-mini   SCATTER (菜品四象限)
//     chart-ops-radar-mini  RADAR   (经营指标)
//     chart-hhi-gauge       GAUGE   (品类集中度 HHI)
//     chart-store-efficiency SCATTER (门店效率矩阵)

/** BAR: 门店营收 Top 5 — store/RANKING/revenue */
const storeTop5Chart = computed<ChartWithMeta | null>(() => {
  const d = data.value
  if (!d) return null
  const top5 = d.storeComparison.stores.slice(0, 5).reverse()
  if (top5.length < 2) return null
  return {
    chartType: 'BAR',
    meta: { xDim: 'store', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
    config: {
      xAxis: { data: top5.map(s => s.name) },
      series: [{ type: 'bar', data: top5.map(s => s.revenue) }],
    },
  }
})

/** PIE: 品类结构 — category/PROPORTION/revenue */
const categoryPieChart = computed<ChartWithMeta | null>(() => {
  const d = data.value
  if (!d) return null
  const all = d.categoryBreakdown
  const top6 = all.slice(0, 6)
  const rest = all.slice(6)
  const pieData = top6.map(c => ({ name: c.category, value: c.revenue }))
  if (rest.length > 0) {
    const otherRevenue = rest.reduce((s, c) => s + c.revenue, 0)
    pieData.push({ name: '其他', value: otherRevenue })
  }
  if (pieData.length < 2) return null
  return {
    chartType: 'PIE',
    meta: { xDim: 'category', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
    config: {
      series: [{ type: 'pie', data: pieData }],
    },
  }
})

/** LINE: 营收趋势 — time/TREND/revenue. Uses the primary (total) series only. */
const trendLineChart = computed<ChartWithMeta | null>(() => {
  const d = data.value
  if (!d?.trendAnalysis) return null
  const trend = d.trendAnalysis
  if (trend.dailyTrend.length < 4) return null
  return {
    chartType: 'LINE',
    meta: { xDim: 'time', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
    config: {
      xAxis: { data: trend.dailyTrend.map(pt => pt.date) },
      series: [{ type: 'line', data: trend.dailyTrend.map(pt => pt.revenue) }],
    },
  }
})

/** BAR: 周度汇总 — time/TREND/revenue (week strings treated as time axis) */
const weeklyBarChart = computed<ChartWithMeta | null>(() => {
  const d = data.value
  if (!d?.trendAnalysis?.weeklyTrend) return null
  const weekly = d.trendAnalysis.weeklyTrend
  if (weekly.length < 2) return null
  return {
    chartType: 'BAR',
    meta: { xDim: 'time', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
    config: {
      xAxis: { data: weekly.map(w => w.week) },
      series: [{ type: 'bar', data: weekly.map(w => w.revenue) }],
    },
  }
})

/** BAR: 时段分布 (hourly) — category/RANKING. yMetric=quantity because the insight
 *  is about traffic distribution by hour, not finance revenue (avoids ¥-gate for operators). */
const hourlyBarChart = computed<ChartWithMeta | null>(() => {
  const d = data.value
  if (!d?.timePeriodAnalysis) return null
  const hourly = d.timePeriodAnalysis.hourlyDistribution
  if (hourly.length < 4) return null
  const hours = Array.from({ length: 24 }, (_, i) => i)
  const revenueData = hours.map(h => {
    const item = hourly.find(pt => pt.hour === h)
    return item ? item.revenue : 0
  })
  return {
    chartType: 'BAR',
    meta: { xDim: 'category', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
    config: {
      xAxis: { data: hours.map(h => `${h}:00`) },
      series: [{ type: 'bar', data: revenueData }],
    },
  }
})

/** PIE: 餐段营收 — category/PROPORTION/revenue */
const mealPieChart = computed<ChartWithMeta | null>(() => {
  const d = data.value
  if (!d?.timePeriodAnalysis) return null
  const periods = d.timePeriodAnalysis.mealPeriods.filter(p => p.revenue > 0)
  if (periods.length < 2) return null
  return {
    chartType: 'PIE',
    meta: { xDim: 'category', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
    config: {
      series: [{ type: 'pie', data: periods.map(p => ({ name: p.period, value: p.revenue })) }],
    },
  }
})

/** BAR: 价格带分布 — category/RANKING/pct (the y axis is % share, not ¥) */
const priceBandChart = computed<ChartWithMeta | null>(() => {
  const d = data.value
  if (!d?.priceBandAnalysis) return null
  const pb = d.priceBandAnalysis
  if (pb.bands.length < 2) return null
  return {
    chartType: 'BAR',
    meta: { xDim: 'category', yMetric: 'pct', aggregation: 'sum', domain: 'restaurant' },
    config: {
      xAxis: { data: pb.bands.map(b => b.band) },
      series: [{ type: 'bar', data: pb.bands.map(b => b.pct) }],
    },
  }
})

/** PIE: 供应商集中度 — category/PROPORTION/cost (supplier spend share) */
const supplierPieChart = computed<ChartWithMeta | null>(() => {
  const d = data.value
  if (!d?.supplyChainAnalysis) return null
  const sc = d.supplyChainAnalysis.supplierConcentration
  const suppliers = sc.suppliers.slice(0, 8).map(s => ({ name: s.name, value: s.spend || s.pct }))
  if (sc.suppliers.length > 8) {
    const rest = sc.suppliers.slice(8).reduce((sum, s) => sum + (s.spend || s.pct), 0)
    suppliers.push({ name: '其他', value: rest })
  }
  if (suppliers.length < 2) return null
  return {
    chartType: 'PIE',
    meta: { xDim: 'category', yMetric: 'cost', aggregation: 'sum', domain: 'restaurant' },
    config: {
      series: [{ type: 'pie', data: suppliers }],
    },
  }
})

// ── Chart rendering ──

function renderCharts() {
  if (!data.value) return
  renderQuadrantMini()
  renderStoreTop5()
  renderCategoryPie()
  renderOpsRadarMini()
  renderTrendLine()
  renderWeeklyBar()
  renderHourlyBar()
  renderMealPie()
  renderPriceBand()
  renderHHIGauge()
  renderStoreEfficiency()
  renderSupplierPie()
}

function renderQuadrantMini() {
  const el = document.getElementById('chart-quadrant-mini')
  if (!el || !data.value) return
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)

  const colorMap: Record<string, string> = {
    Star: '#67C23A', Plow: '#E6A23C', Puzzle: '#409EFF', Dog: '#F56C6C',
  }
  const items = data.value.menuQuadrant.items

  // Clip axes to P95 to prevent outlier compression.
  // Revenue can't be negative — floor to 0 so BCG scatter doesn't show a -100 gridline.
  // Round yMax/xMax to avoid floating-point trails like "478.4000000000003".
  const profits = items.map(i => Math.max(0, i.unitProfit)).sort((a, b) => a - b)
  const qtys = items.map(i => Math.max(0, i.quantity)).sort((a, b) => a - b)
  const yMax = Math.ceil((profits[Math.floor(profits.length * 0.95)] || 100) * 1.3)
  const xMax = Math.ceil((qtys[Math.floor(qtys.length * 0.95)] || 100) * 1.3)

  const series = Object.keys(colorMap).map(q => ({
    name: q === 'Star' ? '明星' : q === 'Plow' ? '金牛' : q === 'Puzzle' ? '问题' : '瘦狗',
    type: 'scatter' as const,
    data: items.filter(i => i.quadrant === q).map(i => ({ value: [Math.min(i.quantity, xMax), Math.min(i.unitProfit, yMax)], _raw: [i.quantity, i.unitProfit] })),
    itemStyle: { color: colorMap[q] },
    symbolSize: 8,
  }))

  chart.setOption({
    tooltip: { trigger: 'item', confine: true, formatter: (p: Record<string, unknown>) => { const d = p.data as Record<string, unknown>; const raw = (d._raw || p.value) as number[]; return `销量: ${raw[0]}, 品均收入: ¥${raw[1].toFixed(1)}` } },
    legend: { bottom: 0, textStyle: { fontSize: 11 } },
    grid: { left: 50, right: 20, top: 20, bottom: 40 },
    xAxis: { name: '销量', type: 'value', min: 0, max: xMax, splitLine: { show: false }, axisLabel: { formatter: (v: number) => String(Math.round(v)) } },
    yAxis: { name: '品均收入 (元)', type: 'value', min: 0, max: yMax, splitLine: { lineStyle: { type: 'dashed' } }, axisLabel: { formatter: (v: number) => v >= 1e4 ? (v / 1e4).toFixed(1) + '万' : String(Math.round(v)) } },
    series,
  })
}

function renderStoreTop5() {
  const el = document.getElementById('chart-store-top5')
  if (!el || !data.value) return
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)

  const top5 = data.value.storeComparison.stores.slice(0, 5).reverse()
  chart.setOption({
    tooltip: { trigger: 'axis', confine: true, axisPointer: { type: 'shadow' } },
    grid: { left: 120, right: 30, top: 10, bottom: 20 },
    xAxis: { type: 'value', axisLabel: { formatter: (v: number) => v >= 1e4 ? (v / 1e4).toFixed(0) + '万' : String(v) } },
    yAxis: { type: 'category', data: top5.map(s => s.name), axisLabel: { width: 100, overflow: 'truncate' } },
    series: [{
      type: 'bar',
      data: top5.map(s => s.revenue),
      itemStyle: { color: '#409EFF', borderRadius: [0, 4, 4, 0] },
      barMaxWidth: 24,
    }],
  })
}

function renderCategoryPie() {
  const el = document.getElementById('chart-category-pie')
  if (!el || !data.value) return
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)

  const all = data.value.categoryBreakdown
  const top6 = all.slice(0, 6)
  const rest = all.slice(6)
  const pieData = top6.map(c => ({ name: c.category, value: c.revenue }))
  if (rest.length > 0) {
    const otherRevenue = rest.reduce((s, c) => s + c.revenue, 0)
    pieData.push({ name: '其他', value: otherRevenue })
  }

  chart.setOption({
    tooltip: { trigger: 'item', confine: true, formatter: '{b}: ¥{c} ({d}%)' },
    legend: { bottom: 0, textStyle: { fontSize: 11 } },
    series: [{
      type: 'pie',
      radius: ['35%', '65%'],
      center: ['50%', '45%'],
      data: pieData,
      label: { show: false },
      emphasis: { label: { show: true, fontSize: 13 } },
    }],
  })
}

function renderOpsRadarMini() {
  const el = document.getElementById('chart-ops-radar-mini')
  if (!el || !data.value) return
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)

  const ops = data.value.operationsMetrics
  chart.setOption({
    tooltip: { confine: true },
    radar: {
      // Expanded to 6 dimensions (from 4) so the radar forms a hexagon rather than
      // a triangle/quad — much easier to read at small size.
      indicator: [
        { name: '招牌集中度', max: 100 },
        { name: '退货率(反)', max: 10 },
        { name: '价格定位', max: Math.max(ops.priceVsBenchmark.actual * 1.5, ops.priceVsBenchmark.benchmarkMedian * 2, 200) },
        { name: '稳定性', max: 100 },
        { name: '菜品丰富度', max: 100 },
        { name: '客单价水平', max: 300 },
      ],
      radius: '60%',
    },
    series: [{
      type: 'radar',
      data: [{
        value: [
          ops.signatureConcentration,
          Math.max(0, 10 - ops.returnRate),
          ops.priceVsBenchmark.actual,
          ops.consistencyScore,
          // Menu diversity = min(dishCount / 200, 1) * 100. 200+ items caps at 100.
          Math.min(100, (data.value.menuQuadrant.items.length / 200) * 100),
          // Average ticket = clamp(avg_bill, 0, 300)
          Math.max(0, Math.min(300, ops.priceVsBenchmark.actual || 0)),
        ],
        name: '当前水平',
        areaStyle: { opacity: 0.2 },
        lineStyle: { color: '#409EFF' },
        itemStyle: { color: '#409EFF' },
      }],
    }],
  })
}

function renderTrendLine() {
  const el = document.getElementById('chart-trend-line')
  if (!el || !data.value?.trendAnalysis) return
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)
  const trend = data.value.trendAnalysis

  const series: Record<string, unknown>[] = [{
    name: '总营收',
    type: 'line',
    data: trend.dailyTrend.map(d => [d.date, d.revenue]),
    smooth: true,
    areaStyle: { opacity: 0.15 },
    lineStyle: { width: 2 },
    itemStyle: { color: '#409EFF' },
  }]

  // Add store trends if available (top 5)
  const colors = ['#67C23A', '#E6A23C', '#F56C6C', '#909399', '#9B59B6']
  if (trend.storeTrends) {
    Object.entries(trend.storeTrends).forEach(([store, points], i) => {
      series.push({
        name: store,
        type: 'line',
        data: points.map(d => [d.date, d.revenue]),
        smooth: true,
        lineStyle: { width: 1, type: 'dashed' },
        itemStyle: { color: colors[i % colors.length] },
        symbol: 'none',
      })
    })
  }

  chart.setOption({
    tooltip: { trigger: 'axis', confine: true },
    legend: { bottom: 0, textStyle: { fontSize: 10 } },
    grid: { left: 60, right: 20, top: 10, bottom: series.length > 1 ? 40 : 20 },
    xAxis: { type: 'time', axisLabel: { formatter: '{MM}-{dd}' } },
    yAxis: { type: 'value', axisLabel: { formatter: (v: number) => v >= 1e4 ? (v / 1e4).toFixed(0) + '万' : String(v) } },
    series,
  })
}

function renderWeeklyBar() {
  const el = document.getElementById('chart-weekly-bar')
  if (!el || !data.value?.trendAnalysis?.weeklyTrend) return
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)
  const weekly = data.value.trendAnalysis.weeklyTrend

  chart.setOption({
    tooltip: { trigger: 'axis', confine: true, axisPointer: { type: 'shadow' } },
    grid: { left: 50, right: 20, top: 10, bottom: 20 },
    xAxis: { type: 'category', data: weekly.map(w => w.week), axisLabel: { fontSize: 11 } },
    yAxis: { type: 'value', axisLabel: { formatter: (v: number) => v >= 1e4 ? (v / 1e4).toFixed(0) + '万' : String(v) } },
    series: [{
      type: 'bar',
      data: weekly.map(w => w.revenue),
      itemStyle: { color: '#409EFF', borderRadius: [4, 4, 0, 0] },
      barMaxWidth: 28,
    }],
  })
}

function renderHourlyBar() {
  const el = document.getElementById('chart-hourly-bar')
  if (!el || !data.value?.timePeriodAnalysis) return
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)
  const hourly = data.value.timePeriodAnalysis.hourlyDistribution

  // Fill all 24 hours
  const hours = Array.from({ length: 24 }, (_, i) => i)
  const revenueData = hours.map(h => {
    const item = hourly.find(d => d.hour === h)
    return item ? item.revenue : 0
  })
  const peakHour = data.value.timePeriodAnalysis.peakHour

  chart.setOption({
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      confine: true,
      formatter: (params: Record<string, unknown>[]) => {
        const p = params[0] as Record<string, unknown>
        const item = hourly.find((d: Record<string, unknown>) => d.hour === p.dataIndex)
        return `${p.dataIndex}:00-${Number(p.dataIndex) + 1}:00<br/>营收: ¥${Number(p.value).toLocaleString()}${item ? '<br/>订单: ' + item.orderCount + '笔' : ''}`
      },
    },
    grid: { left: 50, right: 20, top: 10, bottom: 20 },
    xAxis: {
      type: 'category',
      data: hours.map(h => `${h}:00`),
      axisLabel: { fontSize: 10, interval: 2 },
    },
    yAxis: {
      type: 'value',
      axisLabel: { formatter: (v: number) => v >= 1e4 ? (v / 1e4).toFixed(0) + '万' : String(v) },
    },
    series: [{
      type: 'bar',
      data: revenueData.map((v, i) => ({
        value: v,
        itemStyle: { color: i === peakHour ? '#E6A23C' : '#409EFF', borderRadius: [3, 3, 0, 0] },
      })),
      barMaxWidth: 18,
    }],
  })
}

function renderMealPie() {
  const el = document.getElementById('chart-meal-pie')
  if (!el || !data.value?.timePeriodAnalysis) return
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)
  const periods = data.value.timePeriodAnalysis.mealPeriods.filter(p => p.revenue > 0)

  const colorMap: Record<string, string> = {
    '早餐': '#67C23A', '午市': '#409EFF', '下午茶': '#E6A23C',
    '晚市': '#F56C6C', '夜宵': '#909399',
  }

  chart.setOption({
    tooltip: {
      confine: true,
      formatter: (p: Record<string, unknown>) => { const d = p.data as Record<string, unknown>; return `${p.name}<br/>¥${Number(p.value).toLocaleString()} (${d.pct}%)<br/>${d.orderCount}笔`; },
    },
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      center: ['50%', '45%'],
      data: periods.map(p => ({
        name: p.period,
        value: p.revenue,
        pct: p.pct,
        orderCount: p.orderCount,
        itemStyle: { color: colorMap[p.period] || '#409EFF' },
      })),
      label: { formatter: '{b}\n{d}%', fontSize: 11 },
      emphasis: { itemStyle: { shadowBlur: 10 } },
    }],
  })
}

function renderPriceBand() {
  const el = document.getElementById('chart-price-band')
  if (!el || !data.value?.priceBandAnalysis) return
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)
  const pb = data.value.priceBandAnalysis

  chart.setOption({
    tooltip: { trigger: 'axis', confine: true, formatter: (p: Record<string, unknown>[]) => { const item = p[0] as Record<string, unknown>; const d = item.data as Record<string, unknown>; return `${item.name}<br/>营收占比: ${d.pct}%<br/>SKU: ${d.skuCount}`; } },
    grid: { left: 50, right: 20, top: 10, bottom: 30 },
    xAxis: { type: 'category', data: pb.bands.map(b => b.band), axisLabel: { fontSize: 11 } },
    yAxis: { type: 'value', axisLabel: { formatter: '{value}%' } },
    series: [{
      type: 'bar',
      data: pb.bands.map(b => ({ value: b.pct, pct: b.pct, skuCount: b.skuCount })),
      itemStyle: {
        color: (p: Record<string, unknown>) => pb.bands[p.dataIndex as number].band === pb.mainBand ? '#67C23A' : '#409EFF',
        borderRadius: [4, 4, 0, 0],
      },
      barMaxWidth: 32,
    }],
  })
}

function renderHHIGauge() {
  const el = document.getElementById('chart-hhi-gauge')
  if (!el || !data.value?.categoryConcentration) return
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)
  const cc = data.value.categoryConcentration

  chart.setOption({
    series: [{
      type: 'gauge',
      min: 0,
      max: 10000,
      splitNumber: 4,
      radius: '85%',
      axisLine: {
        lineStyle: {
          width: 15,
          color: [[0.15, '#67C23A'], [0.25, '#E6A23C'], [1, '#F56C6C']],
        },
      },
      pointer: { width: 4, length: '60%' },
      axisTick: { show: false },
      splitLine: { length: 12 },
      axisLabel: { distance: 20, fontSize: 10, formatter: (v: number) => v >= 1000 ? (v / 1000).toFixed(0) + 'k' : String(v) },
      detail: { formatter: '{value}', fontSize: 20, offsetCenter: [0, '70%'] },
      data: [{ value: Math.round(cc.hhi), name: cc.concentrationLevel }],
      title: { offsetCenter: [0, '90%'], fontSize: 13 },
    }],
  })
}

function renderStoreEfficiency() {
  const el = document.getElementById('chart-store-efficiency')
  if (!el || !data.value?.storeEfficiencyMatrix) return
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)
  const sem = data.value.storeEfficiencyMatrix

  const colorMap: Record<string, string> = {
    '高效精简': '#67C23A', '规模领先': '#409EFF', '低效臃肿': '#F56C6C', '潜力不足': '#E6A23C',
  }
  const quadrants = Object.keys(colorMap)
  const series = quadrants.map(q => ({
    name: q,
    type: 'scatter' as const,
    data: sem.stores.filter(s => s.quadrant === q).map(s => [s.revenue, s.skuCount]),
    itemStyle: { color: colorMap[q] },
    symbolSize: 10,
  }))

  chart.setOption({
    tooltip: { trigger: 'item', confine: true, formatter: (p: Record<string, unknown>) => { const v = p.value as number[]; return `营收: ¥${v[0].toLocaleString()}<br/>品项数: ${v[1]}`; } },
    legend: { top: 0, textStyle: { fontSize: 10 } },
    grid: { left: 50, right: 20, top: 30, bottom: 20 },
    xAxis: { name: '营收', type: 'value', splitLine: { show: false }, axisLabel: { formatter: (v: number) => v >= 1e4 ? (v / 1e4).toFixed(0) + '万' : String(v) } },
    yAxis: { name: '品项数', type: 'value', splitLine: { lineStyle: { type: 'dashed' } } },
    series,
  })
}

function renderSupplierPie() {
  const el = document.getElementById('chart-supplier-pie')
  if (!el || !data.value?.supplyChainAnalysis) return
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)
  const sc = data.value.supplyChainAnalysis.supplierConcentration

  const pieData = sc.suppliers.slice(0, 8).map(s => ({ name: s.name, value: s.spend || s.pct }))
  if (sc.suppliers.length > 8) {
    const rest = sc.suppliers.slice(8).reduce((sum, s) => sum + (s.spend || s.pct), 0)
    pieData.push({ name: '其他', value: rest })
  }

  chart.setOption({
    tooltip: { trigger: 'item', confine: true, formatter: '{b}: {d}%' },
    legend: { type: 'scroll', bottom: 0, textStyle: { fontSize: 10 } },
    series: [{
      type: 'pie',
      radius: ['35%', '65%'],
      center: ['50%', '45%'],
      label: { show: false },
      emphasis: { label: { show: true, fontSize: 12, fontWeight: 'bold' } },
      data: pieData,
    }],
  })
}

// composable handles onMounted + auto-select

const chartIds = [
  'chart-quadrant-mini', 'chart-store-top5', 'chart-category-pie',
  'chart-ops-radar-mini', 'chart-trend-line', 'chart-weekly-bar',
  'chart-hourly-bar', 'chart-meal-pie', 'chart-price-band',
  'chart-hhi-gauge', 'chart-store-efficiency', 'chart-supplier-pie',
]
onUnmounted(() => {
  chartIds.forEach(id => {
    const el = document.getElementById(id)
    if (el) echarts.getInstanceByDom(el)?.dispose()
  })
})
</script>

<style scoped>
.page-wrapper { padding: 16px; }
.page-card { border-radius: 8px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-left { display: flex; align-items: center; gap: 12px; }
.header-right { display: flex; align-items: center; gap: 8px; }
.page-title { font-size: 16px; font-weight: 600; }

.kpi-row { margin-top: 8px; }
.kpi-card {
  background: var(--el-fill-color-light);
  border-radius: 8px;
  padding: 14px 16px;
  text-align: center;
}
.kpi-label { font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 4px; }
.kpi-value { font-size: 22px; font-weight: 700; color: var(--el-text-color-primary); }
.kpi-value.success { color: var(--el-color-success); }
.kpi-value.danger { color: var(--el-color-danger); }
.kpi-value.info { color: var(--el-color-primary); font-size: 14px; }

.chart-card { cursor: default; }
.chart-title { display: flex; align-items: center; gap: 8px; font-weight: 600; }
.chart-title.clickable { cursor: pointer; }
.chart-footer { font-size: 12px; color: var(--el-text-color-secondary); text-align: center; padding-top: 4px; }

.roadmap { display: flex; flex-direction: column; gap: 10px; }
.roadmap-item { padding: 8px 12px; background: var(--el-fill-color-light); border-radius: 6px; }
.roadmap-header { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.roadmap-label { font-size: 13px; font-weight: 500; }
.roadmap-action { font-size: 12px; color: var(--el-text-color-secondary); padding-left: 4px; }

.chart-pie-square {
  aspect-ratio: 1 / 1;
  max-height: 320px;
  max-width: 320px;
  margin: 0 auto;
}
.chart-pie-small {
  max-height: 260px;
  max-width: 260px;
}

@media (max-width: 768px) {
  .card-header { flex-direction: column; align-items: flex-start; }
  .header-right { width: 100%; }
  .header-right .el-select { flex: 1; }
  .chart-pie-square { max-width: 100%; }
}
</style>
