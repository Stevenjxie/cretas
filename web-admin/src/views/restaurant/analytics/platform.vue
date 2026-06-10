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
</script>

<template>
  <div class="restaurant-platform" ref="containerRef">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">平台口碑分析</span>
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
              <div class="score-value">{{ fmtScore(c.value) }}</div>
              <div class="score-unit">/ 5 分</div>
            </div>
          </el-col>
        </el-row>

        <el-row :gutter="16" class="summary-row" v-if="vm.summary">
          <el-col :xs="8" :sm="6"><div class="mini-card"><span>门店数</span><b>{{ vm.summary.storeCount }}</b></div></el-col>
          <el-col :xs="8" :sm="6"><div class="mini-card"><span>城市数</span><b>{{ vm.summary.cityCount }}</b></div></el-col>
          <el-col :xs="8" :sm="6"><div class="mini-card"><span>VIP 评价</span><b>{{ vm.summary.vipCount.toLocaleString() }}</b></div></el-col>
          <el-col :xs="8" :sm="6"><div class="mini-card warn"><span>低星(≤3)</span><b>{{ vm.summary.lowStarCount.toLocaleString() }}</b></div></el-col>
        </el-row>

        <!-- Platform comparison -->
        <el-card shadow="hover" class="section-card" v-if="vm.platforms.length">
          <template #header><div class="section-title"><el-icon><ChatLineSquare /></el-icon> 平台评价量对比 (柱顶为平均星级)</div></template>
          <div id="chart-platform-compare" style="height: 280px" />
          <!-- U6: useChartInsight — Tier1 instant, Tier2 auto on null (飞轮接通) -->
          <ChartInsight :insight="platformCompareInsight" :loading="platformCompareInsightLoading" depth="detailed" />
        </el-card>

        <!-- Top good tags -->
        <el-card shadow="hover" class="section-card" v-if="vm.goodTags.length">
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
        <el-card shadow="hover" class="section-card" v-if="vm.storeRanking.length">
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
        </el-card>

        <!-- Review trend -->
        <el-card shadow="hover" class="section-card" v-if="vm.trend.length">
          <template #header><div class="section-title">评价趋势 (按月)</div></template>
          <div id="chart-review-trend" style="height: 300px" />
          <!-- U6: useChartInsight — Tier1 instant, Tier2 auto on null (飞轮接通) -->
          <ChartInsight :insight="reviewTrendInsight" :loading="reviewTrendInsightLoading" depth="detailed" />
        </el-card>
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.restaurant-platform { padding: 16px; }
.page-card { border-radius: 8px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-left { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.page-title { font-size: 16px; font-weight: 600; }

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

.tag-cloud { display: flex; flex-wrap: wrap; gap: 10px; }
.good-tag { font-size: 13px; padding: 6px 12px; }
.good-tag b { margin-left: 4px; }

.low-star { color: var(--el-color-warning); font-weight: 600; }

.platform-actions { text-align: center; margin-top: 12px; }
.platform-hint { color: #909399; font-size: 13px; margin-top: 12px; max-width: 480px; }
</style>
