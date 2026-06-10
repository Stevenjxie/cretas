<template>
  <div class="page-wrapper" ref="containerRef">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <el-button v-if="!embedded" text :icon="ArrowLeft" @click="$router.push('/restaurant/analytics')">返回</el-button>
            <span class="page-title">菜品四象限分析</span>
            <el-tag v-if="data" size="small">{{ data.items.length }} 个菜品</el-tag>
          </div>
          <div class="header-right">
            <el-radio-group v-model="mode" size="small" @change="onModeChange">
              <el-radio-button value="revenue">按品均收入</el-radio-button>
              <el-radio-button value="margin">按毛利率 (BCG 真实版)</el-radio-button>
            </el-radio-group>
            <!-- Margin threshold: classic BCG 50%, thinner restaurants 30-40%, premium 60%+ -->
            <div v-if="mode === 'margin'" class="margin-threshold-box">
              <span class="threshold-label">毛利分界:</span>
              <el-slider
                v-model="marginThreshold"
                :min="0.2" :max="0.8" :step="0.05"
                :format-tooltip="(v: number) => (v * 100).toFixed(0) + '%'"
                style="width: 120px"
                @change="onModeChange"
              />
              <span class="threshold-value">{{ (marginThreshold * 100).toFixed(0) }}%</span>
            </div>
          </div>
        </div>
      </template>

      <div v-if="loading" v-loading="true" style="min-height: 400px" />

      <el-empty v-else-if="!data" description="暂无菜品销售数据（全部历史）。上传POS销售明细后将自动展示四象限分析。" />

      <template v-if="data && !loading">
        <!-- Summary cards -->
        <el-row :gutter="16" class="summary-row">
          <el-col :xs="12" :sm="6" v-for="q in quadrants" :key="q.key">
            <div class="quadrant-card" :style="{ borderLeftColor: q.color }">
              <div class="q-name">{{ q.label }}</div>
              <div class="q-count">{{ q.count }} 个</div>
              <div class="q-desc">{{ q.desc }}</div>
            </div>
          </el-col>
        </el-row>

        <!-- Scatter chart — WS4: gold /restaurant-ops/menu-quadrant 已提供
             {name,qty,revenue,unitProfit,quadrant}, 散点只需这些 quadrant items,
             不需 combo_string。data 非空即 gold-mode → :force 跳过上传能力门控
             (镜像 WS2 Dashboard.vue 的 :force="isGoldMode")。
             chart-insight: SCATTER (BCG quadrant) is an exotic type → SKIP.
             The spec-mentioned LINE does not exist as a standalone chart element
             in this view (the only 'line' series is a markLine median overlay
             inside the scatter; no separate ECharts LINE instance is rendered). -->
        <CapabilityGate card-id="menu_top_dishes" :requires="['combo_string']" :force="isGoldMode">
        <el-card shadow="hover" style="margin-top: 16px">
          <div id="chart-quadrant-full" style="height: 480px" />
        </el-card>
        </CapabilityGate>

        <!-- Filter + table — 同上, 表格列 (菜品名/象限/营收/销量/品均收入) 全来自
             gold quadrant items, 无套餐拆分逻辑, gold-mode 下 :force 出数。 -->
        <CapabilityGate card-id="menu_dish_revenue" :requires="['combo_string', 'net_amount']" :force="isGoldMode">
        <el-card shadow="hover" style="margin-top: 16px">
          <div class="filter-bar">
            <el-radio-group v-model="filterQuadrant">
              <el-radio-button value="">全部</el-radio-button>
              <el-radio-button value="Star">明星</el-radio-button>
              <el-radio-button value="Plow">金牛</el-radio-button>
              <el-radio-button value="Puzzle">问题</el-radio-button>
              <el-radio-button value="Dog">瘦狗</el-radio-button>
            </el-radio-group>
            <el-input v-model="searchKeyword" placeholder="搜索菜品名" clearable style="width: 200px" />
          </div>

          <el-table :data="paginatedItems" stripe border style="width: 100%; margin-top: 12px" max-height="500">
            <el-table-column prop="name" label="菜品名称" min-width="160" show-overflow-tooltip />
            <el-table-column prop="quadrant" label="象限" width="80" align="center">
              <template #default="{ row }">
                <el-tag :type="tagType(row.quadrant)" size="small">{{ quadrantLabel(row.quadrant) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column v-if="canViewPrice" prop="revenue" label="营收(元)" width="120" align="right" sortable>
              <template #default="{ row }">{{ row.revenue.toLocaleString() }}</template>
            </el-table-column>
            <el-table-column prop="quantity" label="销量" width="90" align="right" sortable />
            <el-table-column v-if="canViewPrice" prop="unitProfit" label="品均收入" width="110" align="right" sortable>
              <template #default="{ row }">¥{{ row.unitProfit.toFixed(1) }}</template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-if="filteredItems.length > pageSize"
            v-model:current-page="currentPage"
            v-model:page-size="pageSize"
            :page-sizes="[50, 100, 200]"
            :total="filteredItems.length"
            layout="total, sizes, prev, pager, next"
            style="margin-top: 12px; justify-content: flex-end"
          />
        </el-card>
        </CapabilityGate>
      </template>
      <UnlockMoreCTA />
    </el-card>

    <!-- Item detail drawer -->
    <el-drawer v-model="drawerVisible" :title="selectedItem?.name || ''" size="360px">
      <template v-if="selectedItem">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="象限">
            <el-tag :type="tagType(selectedItem.quadrant)">{{ quadrantLabel(selectedItem.quadrant) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="营收">¥{{ selectedItem.revenue.toLocaleString() }}</el-descriptions-item>
          <el-descriptions-item label="销量">{{ selectedItem.quantity }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="品均收入">¥{{ selectedItem.unitProfit.toFixed(2) }}</el-descriptions-item>
        </el-descriptions>
        <div class="suggestion-box" style="margin-top: 16px">
          <h4>运营建议</h4>
          <p>{{ getSuggestion(selectedItem.quadrant) }}</p>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, watch, onUnmounted, onMounted } from 'vue'
import { ArrowLeft } from '@element-plus/icons-vue'
import echarts from '@/utils/echarts'
import { useChartResize } from '@/composables/useChartResize'
import { usePermissionStore } from '@/store/modules/permission'
import type { MenuQuadrantData, MenuQuadrantItem } from '@/types/restaurant-analytics'
import { pythonFetch, getFactoryId } from '@/api/smartbi/common'
import { goldQuadrantToData, type GoldQuadrantPayload } from './menuQuadrantGold'
import { ElMessage } from 'element-plus'
// Day 9 数据织网 Sub-Project A: capability-driven card visibility
import { useCapability } from '@/composables/useCapability'
import CapabilityGate from '@/components/CapabilityGate.vue'
import UnlockMoreCTA from '@/components/UnlockMoreCTA.vue'

// embedded: 当被 dishes.vue 双tab 嵌入时为 true → 隐藏独立页才需要的「返回」按钮 (IA v2)
defineProps<{ embedded?: boolean }>()

const permissionStore = usePermissionStore()
const canViewPrice = computed(() => permissionStore.canViewPrice)
const { fetchCapability } = useCapability()
// Prime capability cache (fire-and-forget, useCapability handles errors fail-open).
fetchCapability()

// Apr 24 Plan C Phase 7+: mode toggle between legacy (品均收入) vs BCG-proper (毛利率)
const mode = ref<'revenue' | 'margin'>('revenue')
// Map: dish name → marginRate (0-1), qty, grossProfit. Filled when mode=margin.
const marginMap = ref<Record<string, { rate: number; qty: number; grossProfit: number; revenue: number }>>({})
const marginLoading = ref(false)
// Adjustable threshold: the cutoff between "high margin" and "low margin"
// for BCG quadrant classification. Default 50% is classic BCG; restaurants
// with thinner margins may want 30-40%; premium venues 60%+.
const marginThreshold = ref(0.5)

async function loadMarginData() {
  marginLoading.value = true
  try {
    // days=365 covers legacy POS uploads (e.g. qhj 2025 full year).
    // When data becomes more recent, FE can expose this as a user-selectable range.
    const res = await pythonFetch('/api/smartbi/restaurant-ops/gross-margin?days=365') as {
      success: boolean
      data?: { dishes: Array<{ name: string; qty: number; revenue: number; marginRate: number; grossProfit: number; hasCost: boolean }> }
    }
    if (res.success && res.data) {
      marginMap.value = {}
      for (const d of res.data.dishes || []) {
        if (d.hasCost) marginMap.value[d.name] = { rate: d.marginRate, qty: d.qty, grossProfit: d.grossProfit, revenue: d.revenue }
      }
    }
  } catch (e) {
    console.warn('[menu-board] margin load failed:', e)
  } finally {
    marginLoading.value = false
  }
}

function onModeChange() {
  // Check if gross-margin page just triggered an ETL sync — if so invalidate cache
  // so BCG margin mode reflects the fresh data without a full page reload.
  let dirty = false
  try {
    const last = sessionStorage.getItem('restaurantOps.marginDirty')
    const seen = sessionStorage.getItem('restaurantOps.marginSeenAt')
    if (last && last !== seen) {
      dirty = true
      sessionStorage.setItem('restaurantOps.marginSeenAt', last)
    }
  } catch { /* ignore */ }
  const needsLoad = mode.value === 'margin' && (dirty || Object.keys(marginMap.value).length === 0)
  if (needsLoad) {
    if (dirty) marginMap.value = {}
    loadMarginData().then(() => nextTick(() => renderChart()))
  } else {
    nextTick(() => renderChart())
  }
}

const containerRef = ref<HTMLElement>()
useChartResize(containerRef)

// WS3 #1: 收入模式四象限走 gold /restaurant-ops/menu-quadrant (默认全部历史),
// 不再依赖手选 CSV 数据源。marginMap (毛利模式) 仍走 /gross-margin。
const data = ref<MenuQuadrantData | null>(null)
const loading = ref(false)

// WS4 (2026-06-03): gold-mode 检测 — 当 gold /restaurant-ops/menu-quadrant 返回
// quadrant items (summary 卡已证明有数据) 时, 散点图 + 明细表直接出数, 不经
// <CapabilityGate> 的上传能力 (combo_string / net_amount) 门控。gold 聚合自身
// 已产出 {name,qty,revenue,unitProfit,quadrant}, 渲染散点/表格无需原始 combo_string
// 列。镜像 WS2 Dashboard.vue 的 :force="isGoldMode" 模式。data 为空 (gold 无数据
// 或工厂租户走别的路径) → false, 保持原门控行为不变。
const isGoldMode = computed(() => data.value !== null && data.value.items.length > 0)

async function loadQuadrantData() {
  loading.value = true
  try {
    const factoryId = getFactoryId()
    // 默认不传日期 = 全部历史 (WS1 menu-quadrant 端点已支持省略 → 开区间)。
    const res = await pythonFetch(
      `/api/smartbi/restaurant-ops/menu-quadrant?factory_id=${factoryId}`,
      { timeoutMs: 60000 },
    ) as { success?: boolean; message?: string; data?: GoldQuadrantPayload }
    if (res && res.success === false) {
      ElMessage.error(res.message || '菜品四象限数据加载失败')
      data.value = null
      return
    }
    data.value = goldQuadrantToData(res?.data)
  } catch (e) {
    console.error('[menu-board] quadrant load failed:', e)
    ElMessage.error('菜品四象限数据加载失败，请稍后重试')
    data.value = null
  } finally {
    loading.value = false
  }
}

const filterQuadrant = ref('')
const searchKeyword = ref('')
const drawerVisible = ref(false)
const selectedItem = ref<MenuQuadrantItem | null>(null)

// Re-render chart when data changes
watch(data, (val) => {
  if (val) nextTick(() => setTimeout(renderChart, 300))
})

onMounted(() => {
  loadQuadrantData()
})

const quadrants = computed(() => {
  if (!data.value) return []
  const isM = mode.value === 'margin'
  if (!isM) {
    const s = data.value.summary
    return [
      { key: 'Star', label: '明星菜', count: s.starCount, color: '#67C23A', desc: '高销量 + 高收入' },
      { key: 'Plow', label: '金牛菜', count: s.plowCount, color: '#E6A23C', desc: '高销量 + 低收入' },
      { key: 'Puzzle', label: '问题菜', count: s.puzzleCount, color: '#409EFF', desc: '低销量 + 高收入' },
      { key: 'Dog', label: '瘦狗菜', count: s.dogCount, color: '#F56C6C', desc: '低销量 + 低收入' },
    ]
  }
  // Margin mode: re-count via marginMap using adjustable threshold.
  // Note: xlsx-parsed item.name is often an order-combo string containing
  // multiple dish names joined with '+'. We match by substring against the
  // seeded dish names (longest-first so "招牌青花椒味(2-3人份)" wins over
  // "招牌青花椒味" prefix).
  const qm = data.value.qtyMedian
  const mt = marginThreshold.value
  const counts = { Star: 0, Plow: 0, Puzzle: 0, Dog: 0 }
  const dishRates = Object.entries(marginMap.value)
    .map(([name, info]) => ({ name, rate: info.rate }))
    .sort((a, b) => b.name.length - a.name.length)
  const rateOf = (itemName: string): number => {
    const direct = marginMap.value[itemName]
    if (direct) return direct.rate
    for (const { name, rate } of dishRates) if (itemName.includes(name)) return rate
    return 0
  }
  for (const i of data.value.items) {
    const rate = rateOf(i.name)
    const highQty = i.quantity >= qm
    const highMargin = rate >= mt
    const k = highQty && highMargin ? 'Star'
            : highQty && !highMargin ? 'Plow'
            : !highQty && highMargin ? 'Puzzle' : 'Dog'
    counts[k as 'Star'|'Plow'|'Puzzle'|'Dog']++
  }
  return [
    { key: 'Star',   label: '明星菜', count: counts.Star,   color: '#67C23A', desc: '高销量 + 高毛利 (核心赚钱菜)' },
    { key: 'Plow',   label: '金牛菜', count: counts.Plow,   color: '#E6A23C', desc: '高销量 + 低毛利 (引流菜,可优化成本)' },
    { key: 'Puzzle', label: '问题菜', count: counts.Puzzle, color: '#409EFF', desc: '低销量 + 高毛利 (待推广的赚钱菜)' },
    { key: 'Dog',   label: '瘦狗菜', count: counts.Dog,   color: '#F56C6C', desc: '低销量 + 低毛利 (考虑下架)' },
  ]
})

const filteredItems = computed(() => {
  if (!data.value) return []
  let items = data.value.items
  if (filterQuadrant.value) items = items.filter(i => i.quadrant === filterQuadrant.value)
  if (searchKeyword.value) {
    const kw = searchKeyword.value.toLowerCase()
    items = items.filter(i => i.name.toLowerCase().includes(kw))
  }
  return items
})

function tagType(q: string): '' | 'success' | 'warning' | 'info' | 'danger' {
  const map: Record<string, '' | 'success' | 'warning' | 'info' | 'danger'> = { Star: 'success', Plow: 'warning', Puzzle: 'info', Dog: 'danger' }
  return map[q] || ''
}

function quadrantLabel(q: string): string {
  const map: Record<string, string> = { Star: '明星', Plow: '金牛', Puzzle: '问题', Dog: '瘦狗' }
  return map[q] || q
}

function getSuggestion(q: string): string {
  const map: Record<string, string> = {
    Star: '明星菜品是核心收入来源，保持品质稳定，适当提价测试弹性，作为招牌菜重点推广。',
    Plow: '金牛菜品销量高但品均收入低，考虑优化食材成本、调整份量或组合套餐提升单品收入。',
    Puzzle: '问题菜品品均收入高但销量低，加大推广力度（推荐位/服务员推荐），或调整价格刺激需求。',
    Dog: '瘦狗菜品销量和品均收入均低，考虑优化口味/呈现，或逐步淘汰替换为新品。',
  }
  return map[q] || ''
}

// Pagination for table
const currentPage = ref(1)
const pageSize = ref(50)

const paginatedItems = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredItems.value.slice(start, start + pageSize.value)
})

function renderChart() {
  const el = document.getElementById('chart-quadrant-full')
  if (!el || !data.value) return
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)

  const colorMap: Record<string, string> = {
    Star: '#67C23A', Plow: '#E6A23C', Puzzle: '#409EFF', Dog: '#F56C6C',
  }
  const qm = data.value.qtyMedian
  const pm = data.value.profitMedian
  const isMarginMode = mode.value === 'margin'

  // Clip axes to P95 to prevent outlier compression.
  // min=0 on both axes — sales count can't be negative; unitProfit is a revenue
  // proxy that also shouldn't go below zero in a BCG quadrant.
  // Ceil maxes so labels don't show JS float trails like "478.4000000000003".
  const allProfits = data.value.items.map(i => Math.max(0, i.unitProfit)).sort((a, b) => a - b)
  const allQtys = data.value.items.map(i => Math.max(0, i.quantity)).sort((a, b) => a - b)
  const p95Profit = allProfits[Math.floor(allProfits.length * 0.95)] || 100
  const p95Qty = allQtys[Math.floor(allQtys.length * 0.95)] || 100
  const yMaxLegacy = Math.ceil(Math.max(p95Profit * 1.3, pm * 3))
  const xMax = Math.ceil(Math.max(p95Qty * 1.3, qm * 3))
  // Margin mode: Y axis = rate 0-100%, threshold from adjustable marginThreshold
  const yMax = isMarginMode ? 100 : yMaxLegacy
  const yMedian = isMarginMode ? marginThreshold.value * 100 : pm

  // Re-classify quadrant for margin mode based on (quantity vs qm, margin% vs threshold)
  const pointsByQuadrant: Record<string, Array<{ value: number[]; name: string; _raw: number[]; _hasMargin?: boolean }>> = {
    Star: [], Plow: [], Puzzle: [], Dog: [],
  }
  const outliersList: string[] = []
  // Longest-first list of seeded dish names with margin rate — for substring
  // match against xlsx order-combo item names (e.g. '招牌青花椒味(单人份)+米饭+打包盒').
  const dishRatesForChart = Object.entries(marginMap.value)
    .map(([name, info]) => ({ name, rate: info.rate }))
    .sort((a, b) => b.name.length - a.name.length)

  for (const i of data.value.items) {
    let yVal: number
    let quadrant: string
    let hasMargin = true
    if (isMarginMode) {
      // Same longest-first substring matcher as quadrants computed above —
      // supports order-combo item names from xlsx containing multiple dishes.
      const direct = marginMap.value[i.name]
      let matchedRate = 0
      if (direct) {
        matchedRate = direct.rate
      } else {
        for (const { name, rate } of dishRatesForChart) if (i.name.includes(name)) { matchedRate = rate; break }
      }
      if (matchedRate > 0) {
        yVal = matchedRate * 100
      } else {
        hasMargin = false
        yVal = 0  // place at bottom so user sees "no data" cluster
      }
      const highQty = i.quantity >= qm
      const highMargin = yVal >= marginThreshold.value * 100
      quadrant = highQty && highMargin ? 'Star'
               : highQty && !highMargin ? 'Plow'
               : !highQty && highMargin ? 'Puzzle' : 'Dog'
    } else {
      yVal = i.unitProfit
      quadrant = i.quadrant
    }
    if (i.quantity > xMax || yVal > yMax) outliersList.push(i.name)
    pointsByQuadrant[quadrant].push({
      value: [Math.min(i.quantity, xMax), Math.min(yVal, yMax)],
      name: i.name,
      _raw: [i.quantity, yVal],
      _hasMargin: hasMargin,
    })
  }
  const outlierCount = outliersList.length

  const series = Object.entries(colorMap).map(([q, color]) => ({
    name: quadrantLabel(q),
    type: 'scatter' as const,
    data: pointsByQuadrant[q],
    itemStyle: { color },
    symbolSize: 10,
  }))

  chart.setOption({
    tooltip: {
      trigger: 'item',
      confine: true,
      formatter: (p: Record<string, unknown>) => {
        const d = p.data as Record<string, unknown>
        const raw = (d._raw || p.value) as number[]
        const hasMargin = d._hasMargin !== false
        if (isMarginMode) {
          if (!hasMargin) return `<b>${p.name}</b><br/>销量: ${raw[0]}<br/>毛利率: 无配方数据`
          return `<b>${p.name}</b><br/>销量: ${raw[0]}<br/>毛利率: ${raw[1].toFixed(1)}%`
        }
        return `<b>${p.name}</b><br/>销量: ${raw[0]}<br/>品均收入: ¥${raw[1].toFixed(1)}`
      },
    },
    legend: { bottom: 0, textStyle: { fontSize: 12 } },
    title: outlierCount > 0 ? {
      show: true,
      text: `${outlierCount} 个超范围点已截断`,
      right: 10, top: 5,
      textStyle: { fontSize: 11, color: '#999', fontWeight: 'normal' },
    } : { show: false },
    grid: { left: 60, right: 30, top: 30, bottom: 50 },
    xAxis: {
      name: '销量',
      type: 'value',
      min: 0,
      max: xMax,
      splitLine: { show: false },
      axisLabel: { formatter: (v: number) => String(Math.round(v)) },
    },
    yAxis: {
      name: isMarginMode ? '毛利率 (%)' : '品均收入 (元)',
      type: 'value',
      min: 0,
      max: yMax,
      splitLine: { lineStyle: { type: 'dashed' } },
      axisLabel: { formatter: (v: number) =>
        isMarginMode ? v + '%' : (v >= 1e4 ? (v / 1e4).toFixed(1) + '万' : String(Math.round(v))) },
    },
    series: [
      ...series,
      // Median lines — margin mode uses 50% threshold
      {
        type: 'line',
        markLine: {
          silent: true,
          lineStyle: { type: 'dashed', color: '#999' },
          data: [
            { xAxis: qm, label: { formatter: `销量中位数: ${qm.toFixed(0)}` } },
            { yAxis: yMedian, label: { formatter: isMarginMode
                ? `毛利率分界: ${(marginThreshold.value * 100).toFixed(0)}%`
                : `品均收入中位数: ¥${pm.toFixed(1)}` } },
          ],
        },
        data: [],
      },
    ],
  })

  chart.off('click')
  chart.on('click', (params: Record<string, unknown>) => {
    if (params.componentType === 'series' && params.name) {
      selectedItem.value = data.value!.items.find(i => i.name === params.name) || null
      if (selectedItem.value) drawerVisible.value = true
    }
  })
}

// Reset pagination when filter changes
watch([filterQuadrant, searchKeyword], () => { currentPage.value = 1 })

onUnmounted(() => {
  const el = document.getElementById('chart-quadrant-full')
  if (el) echarts.getInstanceByDom(el)?.dispose()
})
</script>

<style scoped>
.page-wrapper { padding: 16px; }
.page-card { border-radius: 8px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-left { display: flex; align-items: center; gap: 8px; }
.header-right { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.margin-threshold-box { display: flex; align-items: center; gap: 8px; padding: 0 10px; border-left: 1px solid #e4e7ed; }
.threshold-label { font-size: 12px; color: #606266; white-space: nowrap; }
.threshold-value { font-size: 12px; font-weight: 600; color: #409eff; min-width: 32px; }
.page-title { font-size: 16px; font-weight: 600; }

.summary-row { margin-top: 8px; }
.quadrant-card {
  background: var(--el-fill-color-light);
  border-radius: 8px;
  border-left: 4px solid;
  padding: 12px 16px;
  margin-bottom: 8px;
}
.q-name { font-weight: 600; font-size: 14px; }
.q-count { font-size: 22px; font-weight: 700; margin: 4px 0; }
.q-desc { font-size: 12px; color: var(--el-text-color-secondary); }

.filter-bar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }

.suggestion-box {
  background: var(--el-fill-color-light);
  border-radius: 8px;
  padding: 12px 16px;
}
.suggestion-box h4 { margin: 0 0 8px; font-size: 14px; }
.suggestion-box p { margin: 0; font-size: 13px; line-height: 1.6; color: var(--el-text-color-regular); }
</style>
