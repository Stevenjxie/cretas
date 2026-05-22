<script setup lang="ts">
import { ref, computed, onMounted, nextTick, onBeforeUnmount, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import echarts from '@/utils/echarts'
import { pythonFetch } from '@/api/smartbi/common'

// === Types mirror backend response shapes (camelCase after pythonFetch transform) ===
interface StatsSummary {
  total: number
  thumbsUp: number
  thumbsDown: number
  feedbackTotal: number
  avgTotalMs: number | null
  distinctFactories: number
}
interface StatsResponse {
  days: number
  summary: StatsSummary
  dailyCounts: Array<{ day: string; n: number }>
}
interface RecentRow {
  id: number
  query: string
  factoryId: string | null
  uploadId: number | null
  answerPreview: string
  userFeedback: 1 | -1 | null
  feedbackComment?: string | null
  totalWallMs: number
  createdAt: string
}
interface RecentResponse {
  count: number
  rows: RecentRow[]
}
interface SimilarityRow extends RecentRow {
  similarity: number
}
interface SimilarityResponse {
  query: string
  count: number
  rows: SimilarityRow[]
}

// === State ===
const daysFilter = ref<1 | 7 | 30>(7)
const activeTab = ref<'recent' | 'issues' | 'similarity'>('recent')
const loading = ref(false)

const stats = ref<StatsSummary>({
  total: 0,
  thumbsUp: 0,
  thumbsDown: 0,
  feedbackTotal: 0,
  avgTotalMs: null,
  distinctFactories: 0,
})
const dailyCounts = ref<Array<{ day: string; n: number }>>([])
const recentRows = ref<RecentRow[]>([])

// Similarity search
const simQuery = ref('')
const simLoading = ref(false)
const simRows = ref<SimilarityRow[]>([])

// Detail drawer
const detailVisible = ref(false)
const detailRow = ref<RecentRow | null>(null)

// Chart ref + instance
const trendChartEl = ref<HTMLDivElement | null>(null)
let trendChart: echarts.ECharts | null = null

// === Derived ===
const thumbsUpPct = computed(() =>
  stats.value.total > 0 ? Math.round((stats.value.thumbsUp / stats.value.total) * 100) : 0,
)
const thumbsDownPct = computed(() =>
  stats.value.total > 0 ? Math.round((stats.value.thumbsDown / stats.value.total) * 100) : 0,
)
const noFeedback = computed(() =>
  Math.max(0, stats.value.total - stats.value.feedbackTotal),
)
const avgSeconds = computed(() =>
  stats.value.avgTotalMs != null ? (stats.value.avgTotalMs / 1000).toFixed(1) : '—',
)

const issuesRows = computed(() =>
  recentRows.value.filter(r => r.userFeedback === -1),
)

// === API ===
async function fetchStats() {
  try {
    const result = (await pythonFetch(
      `/api/smartbi/admin/fallback-log/stats?days=${daysFilter.value}`,
    )) as StatsResponse
    stats.value = result.summary
    dailyCounts.value = result.dailyCounts
    await nextTick()
    renderTrendChart()
  } catch (e) {
    ElMessage.warning('加载统计失败: ' + (e instanceof Error ? e.message : String(e)))
  }
}

async function fetchRecent() {
  try {
    const result = (await pythonFetch(
      '/api/smartbi/admin/fallback-log/recent?limit=100',
    )) as RecentResponse
    recentRows.value = result.rows
  } catch (e) {
    ElMessage.warning('加载最近查询失败: ' + (e instanceof Error ? e.message : String(e)))
  }
}

async function fetchSimilarity() {
  const q = simQuery.value.trim()
  if (q.length < 2) {
    ElMessage.info('请输入至少 2 个字')
    return
  }
  simLoading.value = true
  try {
    const result = (await pythonFetch(
      `/api/smartbi/admin/fallback-log/by-similarity?query=${encodeURIComponent(q)}&limit=10`,
    )) as SimilarityResponse
    simRows.value = result.rows
  } catch (e) {
    ElMessage.warning('相似搜索失败: ' + (e instanceof Error ? e.message : String(e)))
    simRows.value = []
  } finally {
    simLoading.value = false
  }
}

async function refresh() {
  loading.value = true
  try {
    await Promise.all([fetchStats(), fetchRecent()])
  } finally {
    loading.value = false
  }
}

// === Chart ===
function renderTrendChart() {
  if (!trendChartEl.value) return
  if (!trendChart) {
    trendChart = echarts.init(trendChartEl.value)
    window.addEventListener('resize', handleResize)
  }
  trendChart.setOption({
    title: {
      text: `最近 ${daysFilter.value} 天 AI 追问趋势`,
      left: 'center',
      textStyle: { fontSize: 14 },
    },
    tooltip: { trigger: 'axis' },
    grid: { left: '5%', right: '5%', top: 50, bottom: 40 },
    xAxis: {
      type: 'category',
      data: dailyCounts.value.map(d => d.day.slice(5, 10)),
      axisLabel: { fontSize: 11 },
    },
    yAxis: { type: 'value', name: '查询数', nameTextStyle: { fontSize: 11 } },
    series: [
      {
        type: 'line',
        data: dailyCounts.value.map(d => d.n),
        smooth: true,
        itemStyle: { color: '#2D8B57' },
        areaStyle: { opacity: 0.1 },
      },
    ],
  })
}

function handleResize() {
  trendChart?.resize()
}

function formatTime(iso: string): string {
  try {
    const d = new Date(iso)
    const pad = (n: number) => n.toString().padStart(2, '0')
    return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  } catch {
    return iso.slice(0, 16)
  }
}

function renderMarkdown(text: string): string {
  if (!text) return ''
  try {
    return DOMPurify.sanitize(marked(text) as string)
  } catch {
    return text
  }
}

function showDetail(row: RecentRow) {
  detailRow.value = row
  detailVisible.value = true
}

// === Lifecycle ===
onMounted(async () => {
  await refresh()
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (trendChart) {
    trendChart.dispose()
    trendChart = null
  }
})

// Re-fetch on days filter change
watch(daysFilter, () => {
  refresh()
})
// Resize chart when returning to a tab that might have been hidden
watch(activeTab, async () => {
  await nextTick()
  trendChart?.resize()
})
</script>

<template>
  <div class="fallback-log-admin">
    <div class="page-header">
      <h1>AI 追问日志</h1>
      <div class="header-controls">
        <el-select v-model="daysFilter" size="small" style="width: 100px">
          <el-option label="今天" :value="1" />
          <el-option label="7 天" :value="7" />
          <el-option label="30 天" :value="30" />
        </el-select>
        <el-button :icon="Refresh" size="small" :loading="loading" @click="refresh">
          刷新
        </el-button>
      </div>
    </div>

    <p class="page-desc">
      记录每次 AI 问答走 LLM 兜底 (没命中预计算模板) 的查询. 用于 Phase 2 聚类 + Phase 3 RAG 的数据源.
    </p>

    <div class="stats-row">
      <div class="stat-card">
        <div class="stat-label">总查询</div>
        <div class="stat-value">{{ stats.total }}</div>
        <div class="stat-sub">条</div>
      </div>
      <div class="stat-card success">
        <div class="stat-label">有用</div>
        <div class="stat-value">{{ stats.thumbsUp }}</div>
        <div class="stat-sub">{{ thumbsUpPct }}%</div>
      </div>
      <div class="stat-card danger">
        <div class="stat-label">待改进</div>
        <div class="stat-value">{{ stats.thumbsDown }}</div>
        <div class="stat-sub">{{ thumbsDownPct }}%</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">待反馈</div>
        <div class="stat-value">{{ noFeedback }}</div>
        <div class="stat-sub">条</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">平均耗时</div>
        <div class="stat-value">{{ avgSeconds }}</div>
        <div class="stat-sub">秒 / LLM 调用</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">覆盖工厂</div>
        <div class="stat-value">{{ stats.distinctFactories }}</div>
        <div class="stat-sub">家</div>
      </div>
    </div>

    <div class="trend-chart-wrap">
      <div ref="trendChartEl" class="trend-chart" />
    </div>

    <el-tabs v-model="activeTab" class="content-tabs">
      <el-tab-pane label="最近查询" name="recent">
        <el-table :data="recentRows" stripe border size="small" max-height="500">
          <el-table-column prop="id" label="#" width="60" />
          <el-table-column label="查询" min-width="240">
            <template #default="{ row }">
              <el-tooltip :content="row.query" placement="top">
                <div class="query-cell">{{ row.query }}</div>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column prop="factoryId" label="工厂" width="130" />
          <el-table-column label="反馈" width="90" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.userFeedback === 1" type="success" size="small"></el-tag>
              <el-tag v-else-if="row.userFeedback === -1" type="danger" size="small"></el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="90" align="right">
            <template #default="{ row }">
              {{ (row.totalWallMs / 1000).toFixed(1) }}s
            </template>
          </el-table-column>
          <el-table-column label="时间" width="130">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="showDetail(row)">
                详情
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane name="issues">
        <template #label>
          <span>
            待改进
            <el-badge v-if="issuesRows.length > 0" :value="issuesRows.length" :max="99" />
          </span>
        </template>
        <div v-if="issuesRows.length === 0" class="empty-state">
          <p>暂无差评记录, 答案质量良好。</p>
        </div>
        <el-table v-else :data="issuesRows" stripe border size="small" max-height="500">
          <el-table-column prop="id" label="#" width="60" />
          <el-table-column prop="query" label="查询" min-width="200" show-overflow-tooltip />
          <el-table-column
            prop="feedbackComment"
            label="用户反馈原因"
            min-width="200"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span v-if="row.feedbackComment" class="feedback-comment">
                {{ row.feedbackComment }}
              </span>
              <span v-else class="muted">(未填)</span>
            </template>
          </el-table-column>
          <el-table-column prop="factoryId" label="工厂" width="130" />
          <el-table-column label="时间" width="130">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="showDetail(row)">
                详情
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="相似度探索 (Phase 3 预览)" name="similarity">
        <div class="similarity-search">
          <el-input
            v-model="simQuery"
            placeholder="输入一个问题, 看历史上有没有类似追问"
            size="default"
            clearable
            @keyup.enter="fetchSimilarity"
          >
            <template #append>
              <el-button :icon="Search" :loading="simLoading" @click="fetchSimilarity">
                搜索
              </el-button>
            </template>
          </el-input>
          <p class="similarity-hint">
            基于 DashScope text-embedding-v3 (768 dim) + pgvector HNSW 余弦检索.
            这是 Phase 3 RAG 的数据通路 — 相似度高说明用户可能问过语义相同的问题, 应优先建模板.
          </p>
        </div>
        <el-table
          v-if="simRows.length > 0"
          :data="simRows"
          stripe
          border
          size="small"
          max-height="500"
        >
          <el-table-column label="#" width="60" type="index" />
          <el-table-column prop="query" label="历史查询" min-width="240" show-overflow-tooltip />
          <el-table-column label="相似度" width="140" align="center">
            <template #default="{ row }">
              <div class="sim-bar">
                <div
                  class="sim-bar-fill"
                  :style="{ width: (row.similarity * 100).toFixed(0) + '%' }"
                />
                <span class="sim-value">{{ row.similarity.toFixed(3) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="反馈" width="80" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.userFeedback === 1" type="success" size="small"></el-tag>
              <el-tag v-else-if="row.userFeedback === -1" type="danger" size="small"></el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="factoryId" label="工厂" width="130" />
          <el-table-column label="时间" width="130">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="showDetail(row)">
                详情
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-else-if="!simLoading && simQuery" class="empty-state">
          <p>未找到相似历史记录 (或未搜索过)</p>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="detailVisible" size="50%" :with-header="false">
      <div v-if="detailRow" class="drawer-content">
        <h3>查询 #{{ detailRow.id }}</h3>
        <div class="detail-meta">
          <span>{{ formatTime(detailRow.createdAt) }}</span>
          <span>工厂 {{ detailRow.factoryId }}</span>
          <span>耗时 {{ (detailRow.totalWallMs / 1000).toFixed(1) }}s</span>
          <el-tag v-if="detailRow.userFeedback === 1" type="success" size="small"></el-tag>
          <el-tag v-else-if="detailRow.userFeedback === -1" type="danger" size="small"></el-tag>
        </div>
        <h4 class="detail-h4">用户问题</h4>
        <div class="detail-query">{{ detailRow.query }}</div>
        <h4 class="detail-h4">AI 答案 (预览, 前 300 字)</h4>
        <div
          class="detail-answer markdown-body"
          v-html="renderMarkdown(detailRow.answerPreview)"
        />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.fallback-log-admin {
  padding: 20px;
  max-width: 1400px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;

  h1 {
    margin: 0;
    font-size: 20px;
  }
  .header-controls {
    display: flex;
    gap: 8px;
  }
}

.page-desc {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin: 0 0 16px 0;
}

.stats-row {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.stat-card {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 2px;

  .stat-label {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
  .stat-value {
    font-size: 24px;
    font-weight: 600;
    line-height: 1.1;
  }
  .stat-sub {
    font-size: 11px;
    color: var(--el-text-color-secondary);
  }

  &.success .stat-value {
    color: #67c23a;
  }
  &.danger .stat-value {
    color: #f56c6c;
  }
}

.trend-chart-wrap {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 12px;
  margin-bottom: 16px;
}

.trend-chart {
  width: 100%;
  height: 220px;
}

.content-tabs {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 0 16px 12px;
}

.query-cell {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 500px;
}

.muted {
  color: var(--el-text-color-placeholder);
}

.feedback-comment {
  color: #f56c6c;
}

.empty-state {
  padding: 40px;
  text-align: center;
  color: var(--el-text-color-secondary);
}

.similarity-search {
  margin-bottom: 16px;

  .similarity-hint {
    color: var(--el-text-color-secondary);
    font-size: 12px;
    margin: 8px 0 0 0;
  }
}

.sim-bar {
  position: relative;
  height: 18px;
  background: var(--el-fill-color-lighter);
  border-radius: 3px;
  overflow: hidden;

  .sim-bar-fill {
    height: 100%;
    background: linear-gradient(to right, #409eff, #67c23a);
    transition: width 0.3s;
  }
  .sim-value {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 11px;
    font-weight: 600;
    color: #000;
  }
}

.drawer-content {
  padding: 20px;

  h3 {
    margin: 0 0 12px;
  }
  .detail-meta {
    display: flex;
    gap: 12px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
    margin-bottom: 20px;
  }
  .detail-h4 {
    margin: 20px 0 8px;
    font-size: 14px;
    color: var(--el-text-color-regular);
  }
  .detail-query {
    background: var(--el-fill-color-lighter);
    padding: 12px;
    border-radius: 4px;
    font-size: 14px;
  }
  .detail-answer {
    font-size: 13px;
    line-height: 1.6;
  }
  :deep(.markdown-body) {
    h1,
    h2,
    h3 {
      margin: 12px 0 8px;
    }
    ul,
    ol {
      padding-left: 20px;
    }
  }
}

@media (max-width: 1200px) {
  .stats-row {
    grid-template-columns: repeat(3, 1fr);
  }
}
@media (max-width: 800px) {
  .stats-row {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
