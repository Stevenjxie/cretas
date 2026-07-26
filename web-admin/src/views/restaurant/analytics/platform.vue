<script setup lang="ts">
import { ref, computed, nextTick, watch, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { DataLine, ChatLineSquare, Upload } from '@element-plus/icons-vue'
import echarts from '@/utils/echarts'
import { useChartResize } from '@/composables/useChartResize'
import { useGoldAnalytics } from '@/composables/useGoldAnalytics'
import { getFactoryId } from '@/api/smartbi/common'
import { buildPlatformReviewVM } from './platformReviewGold'
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight'
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue'
import { useChartInsight } from '@/composables/useChartInsight'
import ChartInsights from '@/views/smart-bi/components/analysis/ChartInsights.vue'
import AnalysisChatPanel from '@/views/smart-bi/components/analysis/AnalysisChatPanel.vue'
import { useCountUp } from '@/composables/useCountUp'
import { vReveal } from '@/composables/useReveal'
import {
  platformOverviewBullets,
  platformOverviewSummary,
  platformCompareBullets,
  platformCompareSummary,
  storeRankingBullets,
  storeRankingSummary,
  reviewTrendBullets,
  reviewTrendSummary,
  type AnalysisChartContext,
} from '@/views/smart-bi/components/analysis/analysisBullets'

const router = useRouter()
function goUpload() { router.push('/smart-bi/upload') }

const containerRef = ref<HTMLElement>()
useChartResize(containerRef)

// WS3 #3: 平台口碑默认展示已有 gold 点评数据 (大众点评/美团导出, qhj ~19845 条),
// 不再 dead-end 在"未接入"空态。无数据 (非餐饮租户) 才回退上传引导。
const REVIEW_ENDPOINTS = [
  'review-summary',
  'review-platform',
  'review-good-tags',
  'review-store-ranking',
  'review-trend',
]

let factoryId = ''
try { factoryId = getFactoryId() } catch { factoryId = '' }

const { data, loading, error } = useGoldAnalytics({
  endpoints: REVIEW_ENDPOINTS,
  factoryId,
  autoLoad: !!factoryId,
})

const vm = computed(() => buildPlatformReviewVM(data.value))

function fmtScore(v: number | null): string {
  return v === null || v === undefined ? '—' : v.toFixed(2)
}

// ============================================================
// KPI count-up (GSAP, once on first load — not on 刷新 refetch) — 2026-07-12
// Gated on `loading` (useGoldAnalytics) so the once-only animation fires on
// the first true→false edge, not on the pre-fetch placeholder values.
// scoreCards is a variable-length list (dimensionScores 优先, 4-item 星级/
// 服务/环境/口味 fallback) — look up the 4 known dimensions by name for a
// stable per-metric tween; any other/未知 dimension name falls back to a
// plain (non-animated) fmtScore render via scoreDisplay() below.
// ============================================================
function scoreByName(name: string): number | null {
  return vm.value.scoreCards.find((c) => c.name === name)?.value ?? null
}
const starScoreVal = computed(() => scoreByName('星级'))
const serviceScoreVal = computed(() => scoreByName('服务'))
const envScoreVal = computed(() => scoreByName('环境'))
const tasteScoreVal = computed(() => scoreByName('口味'))

const { display: starScoreDisplay } = useCountUp(starScoreVal, {
  loading,
  decimals: 2,
  formatter: (v) => fmtScore(v),
})
const { display: serviceScoreDisplay } = useCountUp(serviceScoreVal, {
  loading,
  decimals: 2,
  formatter: (v) => fmtScore(v),
})
const { display: envScoreDisplay } = useCountUp(envScoreVal, {
  loading,
  decimals: 2,
  formatter: (v) => fmtScore(v),
})
const { display: tasteScoreDisplay } = useCountUp(tasteScoreVal, {
  loading,
  decimals: 2,
  formatter: (v) => fmtScore(v),
})

function scoreDisplay(name: string): string {
  switch (name) {
    case '星级': return starScoreDisplay.value
    case '服务': return serviceScoreDisplay.value
    case '环境': return envScoreDisplay.value
    case '口味': return tasteScoreDisplay.value
    default: return fmtScore(scoreByName(name))
  }
}

const storeCountVal = computed(() => vm.value.summary?.storeCount ?? null)
const cityCountVal = computed(() => vm.value.summary?.cityCount ?? null)
const vipCountVal = computed(() => vm.value.summary?.vipCount ?? null)
const lowStarCountVal = computed(() => vm.value.summary?.lowStarCount ?? null)

const { display: storeCountDisplay } = useCountUp(storeCountVal, {
  loading,
  formatter: (v) => Math.round(v).toLocaleString(),
})
const { display: cityCountDisplay } = useCountUp(cityCountVal, {
  loading,
  formatter: (v) => Math.round(v).toLocaleString(),
})
const { display: vipCountDisplay } = useCountUp(vipCountVal, {
  loading,
  formatter: (v) => Math.round(v).toLocaleString(),
})
const { display: lowStarCountDisplay } = useCountUp(lowStarCountVal, {
  loading,
  formatter: (v) => Math.round(v).toLocaleString(),
})

// ── Platform comparison bar (评价量) + ── trend line (评价量 + 平均星级) ──
function renderPlatformChart() {
  const el = document.getElementById('chart-platform-compare')
  if (!el) return
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)
  const platforms = vm.value.platforms
  if (platforms.length === 0) { chart.clear(); return }
  chart.setOption({
    tooltip: {
      trigger: 'axis', confine: true, axisPointer: { type: 'shadow' },
      formatter: (params: Record<string, unknown>[]) => {
        const p = platforms[(params[0] as { dataIndex: number }).dataIndex]
        return `<b>${p.platform}</b><br/>评价量: ${p.reviewCount.toLocaleString()}<br/>平均星级: ${fmtScore(p.avgStar)}`
      },
    },
    grid: { left: 60, right: 30, top: 20, bottom: 30 },
    xAxis: { type: 'category', data: platforms.map((p) => p.platform) },
    yAxis: { type: 'value', name: '评价量' },
    series: [{
      type: 'bar', barMaxWidth: 48,
      data: platforms.map((p) => ({
        value: p.reviewCount,
        itemStyle: { color: '#409EFF', borderRadius: [4, 4, 0, 0] },
      })),
      label: {
        show: true, position: 'top',
        formatter: (o: { dataIndex: number }) => `${fmtScore(platforms[o.dataIndex].avgStar)}分`,
      },
    }],
  })
}

function renderTrendChart() {
  const el = document.getElementById('chart-review-trend')
  if (!el) return
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)
  const trend = vm.value.trend
  if (trend.length === 0) { chart.clear(); return }
  chart.setOption({
    tooltip: { trigger: 'axis', confine: true },
    legend: { data: ['评价量', '平均星级'], bottom: 0 },
    grid: { left: 50, right: 50, top: 20, bottom: 40 },
    xAxis: { type: 'category', data: trend.map((m) => m.month), boundaryGap: false },
    yAxis: [
      { type: 'value', name: '评价量', position: 'left' },
      { type: 'value', name: '平均星级', position: 'right', min: 0, max: 5 },
    ],
    series: [
      {
        name: '评价量', type: 'bar', yAxisIndex: 0,
        data: trend.map((m) => m.reviewCount),
        itemStyle: { color: '#DCEBFB' }, barMaxWidth: 28,
      },
      {
        name: '平均星级', type: 'line', yAxisIndex: 1, smooth: true,
        data: trend.map((m) => m.avgStar),
        itemStyle: { color: '#E6A23C' }, lineStyle: { width: 2 },
      },
    ],
  })
}

watch(vm, (val) => {
  if (!val.isEmpty) {
    nextTick(() => setTimeout(() => { renderPlatformChart(); renderTrendChart() }, 200))
  }
})

onUnmounted(() => {
  for (const id of ['chart-platform-compare', 'chart-review-trend']) {
    const el = document.getElementById(id)
    if (el) echarts.getInstanceByDom(el)?.dispose()
  }
})

// ==================== Chart Insight ====================
// Chart 1: platform comparison BAR (评价量 by platform, RANKING family, domain='restaurant').
const platformCompareSource = (): { chart: ChartWithMeta } | null => {
  const platforms = vm.value.platforms
  if (platforms.length < 2) return null
  return {
    chart: {
      chartType: 'BAR',
      meta: { xDim: 'channel', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
      config: {
        xAxis: { data: platforms.map((p) => p.platform) },
        series: [{ type: 'bar', data: platforms.map((p) => p.reviewCount) }],
      },
    },
  }
}

// Chart 2: review trend BAR+LINE (月度评价趋势, RANKING family with channel dim, domain='restaurant').
const reviewTrendSource = (): { chart: ChartWithMeta } | null => {
  const trend = vm.value.trend
  if (trend.length < 2) return null
  return {
    chart: {
      chartType: 'LINE',
      meta: { xDim: 'time', yMetric: 'quantity', aggregation: 'sum', domain: 'restaurant' },
      config: {
        xAxis: { data: trend.map((m) => m.month) },
        series: [{ type: 'line', data: trend.map((m) => m.reviewCount) }],
      },
    },
  }
}

// platform.vue has no finance gate — reviews are public within the tenant.
const platformPerms = () => ({ canViewFinance: false })

const { insight: platformCompareInsight, loading: platformCompareInsightLoading } = useChartInsight(
  platformCompareSource,
  platformPerms,
  { factoryId: () => factoryId, autoTier2: true },
)

const { insight: reviewTrendInsight, loading: reviewTrendInsightLoading } = useChartInsight(
  reviewTrendSource,
  platformPerms,
  { factoryId: () => factoryId, autoTier2: true },
)

// ============================================================
// 混合式图表分析(bullets + AI 解读 + 整页 AI 分析面板) — 2026-07-12
// 与 CRM 会员分析 / 运营分析同一套 pattern: 确定性 bullets (analysisBullets.ts,
// 0 LLM) + ChartInsights.vue 承载的按需 AI 解读 + 页面级 AnalysisChatPanel。
// 这套是全新的, 与上面已有的 ChartInsight(单数)/useChartInsight 旧系统并存,
// 纯新增, 不影响旧系统的 Tier1/Tier2 行为。
// ============================================================
const platformOverviewBulletsInput = computed(() => ({
  totalReviews: vm.value.totalReviews,
  storeCount: vm.value.summary?.storeCount ?? 0,
  cityCount: vm.value.summary?.cityCount ?? 0,
  lowStarCount: vm.value.summary?.lowStarCount ?? 0,
  scoreCards: vm.value.scoreCards,
  goodTags: vm.value.goodTags,
}))
const platformOverviewBulletsComputed = computed(() => platformOverviewBullets(platformOverviewBulletsInput.value))
const platformOverviewSummaryStr = computed(() => platformOverviewSummary(platformOverviewBulletsInput.value))

const platformCompareBulletsComputed = computed(() => platformCompareBullets(vm.value.platforms))
const platformCompareSummaryStr = computed(() => platformCompareSummary(vm.value.platforms))

const storeRankingBulletsComputed = computed(() => storeRankingBullets(vm.value.storeRanking))
const storeRankingSummaryStr = computed(() => storeRankingSummary(vm.value.storeRanking))

const reviewTrendBulletsComputed = computed(() => reviewTrendBullets(vm.value.trend))
const reviewTrendSummaryStr = computed(() => reviewTrendSummary(vm.value.trend))

const analysisContexts = computed<AnalysisChartContext[]>(() => [
  { key: 'platform-overview', title: '口碑总览', dataSummary: platformOverviewSummaryStr.value },
  { key: 'platform-compare', title: '平台评价量对比', dataSummary: platformCompareSummaryStr.value },
  { key: 'store-ranking', title: '门店口碑排名', dataSummary: storeRankingSummaryStr.value },
  { key: 'review-trend', title: '评价趋势', dataSummary: reviewTrendSummaryStr.value },
])

const analysisChatPanelRef = ref<InstanceType<typeof AnalysisChatPanel> | null>(null)
function focusChart(key: string) {
  analysisChatPanelRef.value?.setFocus(key)
}
</script>

<template>
  <div class="restaurant-platform" ref="containerRef">
    <div class="platform-layout">
    <div class="platform-main">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">平台分析（美团 / 点评 / 抖音）</span>
            <el-tag v-if="!vm.isEmpty" size="small" type="info">
              来源: 大众点评 / 美团 导出，共 {{ vm.totalReviews.toLocaleString() }} 条点评
            </el-tag>
          </div>
          <div class="header-right">
            <el-button :icon="Upload" plain @click="goUpload">上传最新点评导出 (补充更新)</el-button>
          </div>
        </div>
      </template>

      <div v-if="loading" v-loading="true" style="min-height: 400px" />

      <!-- Honest empty: no review data for this tenant (non-qhj) → upload guide -->
      <el-empty
        v-else-if="vm.isEmpty"
        :description="error || '暂无平台点评数据'"
      >
        <template #image><el-icon :size="64"><DataLine /></el-icon></template>
        <div class="platform-actions">
          <el-button type="primary" :icon="Upload" @click="goUpload">上传点评导出文件分析</el-button>
          <p class="platform-hint">
            导出大众点评 / 美团后台的评分、评论数据为 Excel，上传后自动生成口碑分析。
          </p>
        </div>
      </el-empty>

      <template v-else>
        <!-- Score overview cards (5-point scale) -->
        <el-row :gutter="16" class="score-row">
          <el-col :xs="12" :sm="6" v-for="c in vm.scoreCards" :key="c.name">
            <div class="score-card">
              <div class="score-name">平均{{ c.name }}分</div>
              <div class="score-value">{{ scoreDisplay(c.name) }}</div>
              <div class="score-unit">/ 5 分</div>
            </div>
          </el-col>
        </el-row>

        <el-row :gutter="16" class="summary-row" v-if="vm.summary">
          <el-col :xs="8" :sm="6"><div class="mini-card"><span>门店数</span><b>{{ storeCountDisplay }}</b></div></el-col>
          <el-col :xs="8" :sm="6"><div class="mini-card"><span>城市数</span><b>{{ cityCountDisplay }}</b></div></el-col>
          <el-col :xs="8" :sm="6"><div class="mini-card"><span>VIP 评价</span><b>{{ vipCountDisplay }}</b></div></el-col>
          <el-col :xs="8" :sm="6"><div class="mini-card warn"><span>低星(≤3)</span><b>{{ lowStarCountDisplay }}</b></div></el-col>
        </el-row>

        <!-- 口碑总览: 确定性 bullets + 按需 AI 解读 (新增, 与上面卡片同批数据) -->
        <ChartInsights
          :bullets="platformOverviewBulletsComputed"
          chart-key="platform-overview"
          chart-title="口碑总览"
          :data-summary="platformOverviewSummaryStr"
          @focus="focusChart"
        />

        <el-card v-reveal="0" shadow="hover" class="section-card benchmark-section" v-if="vm.benchmarkCards.length">
          <template #header>
            <div class="section-title">多维对标分析：不只看店内，还看平台、连锁、产品、商圈和成本</div>
          </template>
          <div class="benchmark-grid">
            <div
              v-for="card in vm.benchmarkCards"
              :key="card.title"
              class="benchmark-card"
              :class="{ 'benchmark-card--pending': card.status === 'needs-data' }"
            >
              <div class="benchmark-card__top">
                <strong>{{ card.title }}</strong>
                <el-tag size="small" :type="card.status === 'ready' ? 'success' : 'warning'" effect="plain">
                  {{ card.status === 'ready' ? '已接入' : '待接入' }}
                </el-tag>
              </div>
              <div class="benchmark-card__scope">{{ card.scope }}</div>
              <p class="benchmark-card__headline">{{ card.headline }}</p>
              <p class="benchmark-card__detail">{{ card.detail }}</p>
              <p class="benchmark-card__action">{{ card.action }}</p>
            </div>
          </div>
          <div class="benchmark-source-note">
            当前已接入项来自租户内真实评价聚合和大众点评/美团导出；商圈、同菜系、采购成本等外部基准需要通过商家后台、开放平台、授权导出或合规第三方数据接入后再给真实排名。
          </div>
        </el-card>

        <!-- Platform comparison -->
        <el-card v-reveal="1" shadow="hover" class="section-card" v-if="vm.platforms.length">
          <template #header><div class="section-title"><el-icon><ChatLineSquare /></el-icon> 平台评价量对比 (柱顶为平均星级)</div></template>
          <div id="chart-platform-compare" style="height: 280px" />
          <!-- U6: useChartInsight — Tier1 instant, Tier2 auto on null (飞轮接通) -->
          <ChartInsight :insight="platformCompareInsight" :loading="platformCompareInsightLoading" depth="detailed" />
          <ChartInsights
            :bullets="platformCompareBulletsComputed"
            chart-key="platform-compare"
            chart-title="平台评价量对比"
            :data-summary="platformCompareSummaryStr"
            @focus="focusChart"
          />
        </el-card>

        <!-- Top good tags -->
        <el-card v-reveal="2" shadow="hover" class="section-card" v-if="vm.goodTags.length">
          <template #header><div class="section-title">高频好评词 (好评 ≥4.5 星的口味/品质标签，非菜名)</div></template>
          <div class="tag-cloud">
            <el-tag
              v-for="t in vm.goodTags"
              :key="t.tag"
              type="success"
              effect="light"
              class="good-tag"
            >{{ t.tag }} <b>{{ t.count.toLocaleString() }}</b></el-tag>
          </div>
        </el-card>

        <!-- Store reputation ranking -->
        <el-card v-reveal="3" shadow="hover" class="section-card" v-if="vm.storeRanking.length">
          <template #header><div class="section-title">门店口碑排名</div></template>
          <el-table :data="vm.storeRanking" stripe border style="width: 100%" max-height="420"
                    :default-sort="{ prop: 'avgStar', order: 'descending' }">
            <el-table-column type="index" label="#" width="50" />
            <el-table-column prop="store" label="门店" min-width="160" show-overflow-tooltip />
            <el-table-column prop="reviewCount" label="评价量" width="100" align="right" sortable />
            <el-table-column prop="avgStar" label="星级分" width="100" align="right" sortable>
              <template #default="{ row }">{{ fmtScore(row.avgStar) }}</template>
            </el-table-column>
            <el-table-column prop="avgService" label="服务分" width="100" align="right" sortable>
              <template #default="{ row }">{{ fmtScore(row.avgService) }}</template>
            </el-table-column>
            <el-table-column prop="avgEnv" label="环境分" width="100" align="right" sortable>
              <template #default="{ row }">{{ fmtScore(row.avgEnv) }}</template>
            </el-table-column>
            <el-table-column prop="lowStarCount" label="低星数" width="100" align="right" sortable>
              <template #default="{ row }">
                <span :class="{ 'low-star': row.lowStarCount > 0 }">{{ row.lowStarCount }}</span>
              </template>
            </el-table-column>
          </el-table>
          <ChartInsights
            :bullets="storeRankingBulletsComputed"
            chart-key="store-ranking"
            chart-title="门店口碑排名"
            :data-summary="storeRankingSummaryStr"
            @focus="focusChart"
          />
        </el-card>

        <!-- Review trend -->
        <el-card v-reveal="4" shadow="hover" class="section-card" v-if="vm.trend.length">
          <template #header><div class="section-title">评价趋势 (按月)</div></template>
          <div id="chart-review-trend" style="height: 300px" />
          <!-- U6: useChartInsight — Tier1 instant, Tier2 auto on null (飞轮接通) -->
          <ChartInsight :insight="reviewTrendInsight" :loading="reviewTrendInsightLoading" depth="detailed" />
          <ChartInsights
            :bullets="reviewTrendBulletsComputed"
            chart-key="review-trend"
            chart-title="评价趋势"
            :data-summary="reviewTrendSummaryStr"
            @focus="focusChart"
          />
        </el-card>
      </template>
    </el-card>
    </div>

    <div class="platform-side">
      <AnalysisChatPanel ref="analysisChatPanelRef" :factory-id="factoryId" :contexts="analysisContexts" page-title="平台分析" />
    </div>
    </div>
  </div>
</template>

<style scoped>
.restaurant-platform { padding: 16px; }
.page-card { border-radius: 8px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-left { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.page-title { font-size: 16px; font-weight: 600; }

/* 主列(原有内容) + 侧列(AI 分析面板) 响应式布局 (mirrors CRM member-analysis / 运营分析) */
.platform-layout { display: grid; grid-template-columns: minmax(0, 1fr) 380px; gap: 16px; align-items: start; }
@media (max-width: 1280px) { .platform-layout { grid-template-columns: 1fr; } }
.platform-main { min-width: 0; }
.platform-side { position: sticky; top: 16px; }
@media (max-width: 1280px) { .platform-side { position: static; } }

.score-row { margin-top: 4px; }
.score-card {
  background: var(--el-fill-color-light);
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 12px;
  text-align: center;
}
.score-name { font-size: 13px; color: var(--el-text-color-secondary); }
.score-value { font-size: 28px; font-weight: 700; color: #409EFF; margin: 4px 0; }
.score-unit { font-size: 12px; color: var(--el-text-color-secondary); }

.summary-row { margin-bottom: 4px; }
.mini-card {
  display: flex; justify-content: space-between; align-items: center;
  background: var(--el-fill-color-lighter);
  border-radius: 6px; padding: 8px 12px; margin-bottom: 12px;
}
.mini-card span { font-size: 12px; color: var(--el-text-color-secondary); }
.mini-card b { font-size: 18px; }
.mini-card.warn b { color: var(--el-color-warning); }

.section-card { margin-top: 16px; }
.section-title { font-weight: 600; display: flex; align-items: center; gap: 6px; }

.benchmark-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 12px;
}
.benchmark-card {
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 12px;
  background: var(--el-fill-color-blank);
}
.benchmark-card--pending {
  background: var(--el-fill-color-lighter);
}
.benchmark-card__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.benchmark-card__scope {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.benchmark-card__headline {
  margin: 10px 0 6px;
  font-weight: 600;
  line-height: 1.45;
  color: var(--el-text-color-primary);
}
.benchmark-card__detail,
.benchmark-card__action {
  margin: 6px 0 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--el-text-color-regular);
}
.benchmark-card__action {
  color: var(--el-color-primary);
}
.benchmark-source-note {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--el-border-color);
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.5;
}

.tag-cloud { display: flex; flex-wrap: wrap; gap: 10px; }
.good-tag { font-size: 13px; padding: 6px 12px; }
.good-tag b { margin-left: 4px; }

.low-star { color: var(--el-color-warning); font-weight: 600; }

.platform-actions { text-align: center; margin-top: 12px; }
.platform-hint { color: #909399; font-size: 13px; margin-top: 12px; max-width: 480px; }
</style>
