<template>
  <div class="page-wrapper" ref="containerRef">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <el-button text :icon="ArrowLeft" @click="$router.push('/restaurant/analytics')">返回</el-button>
            <span class="page-title">门店对比分析</span>
            <el-tag v-if="stores.length" size="small">{{ stores.length }} 家门店</el-tag>
          </div>
          <div class="header-right">
 <el-button v-if="canViewPrice" type="info" plain @click="$router.push('/restaurant/analytics/gross-margin')"> 菜品毛利分析 →</el-button>
          </div>
        </div>
      </template>

      <div v-if="loading" v-loading="true" style="min-height: 400px" />
      <el-empty v-else-if="!canViewPrice" description="您没有查看价格/财务数据的权限" />
      <el-empty v-else-if="stores.length === 0" description="暂无门店销售数据（全部历史）。上传POS销售明细后将自动展示门店对比。" />

      <template v-if="canViewPrice && stores.length > 0 && !loading">
        <!-- Bar chart -->
        <el-card shadow="hover">
          <template #header><div class="chart-title">门店营收排名</div></template>
          <div id="chart-store-bar" style="height: 400px" />
          <!-- U6: useChartInsight — Tier1 instant, Tier2 auto on null (飞轮接通) -->
          <ChartInsight :insight="storeComparisonInsight" :loading="storeComparisonInsightLoading" depth="detailed" />
        </el-card>

        <!-- Table -->
        <el-card shadow="hover" style="margin-top: 16px">
          <template #header><div class="chart-title">门店明细</div></template>
          <el-table :data="stores" stripe border style="width: 100%" max-height="600" :default-sort="{ prop: 'revenue', order: 'descending' }">
            <el-table-column type="index" label="#" width="50" />
            <el-table-column prop="name" label="门店" min-width="160" show-overflow-tooltip />
            <el-table-column prop="revenue" label="营收(元)" width="130" align="right" sortable>
              <template #default="{ row }">
                <span :class="{ 'weak-store': isWeakStore(row.name) }">{{ formatAmount(row.revenue) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="orderCount" label="品项数" width="100" align="right" sortable />
            <el-table-column prop="avgTicket" label="品均收入" width="110" align="right" sortable>
              <template #default="{ row }">{{ formatAmount(row.avgTicket) }}</template>
            </el-table-column>
            <el-table-column prop="discountPct" label="折扣率" width="100" align="right" sortable>
              <template #default="{ row }">
                <span :class="{ 'high-discount': row.discountPct > 20 }">{{ row.discountPct.toFixed(1) }}%</span>
              </template>
            </el-table-column>
            <el-table-column label="毛利率" width="110" align="center" sortable
                             :sort-method="(a: Record<string, unknown>, b: Record<string, unknown>) => marginSortValue((a as {name:string}).name) - marginSortValue((b as {name:string}).name)">
              <template #default="{ row }">
                <el-tooltip v-if="storeMarginMap[row.name] && storeMarginMap[row.name].dishesWithCost === 0"
                            content="此门店菜品暂无配方成本数据,无法计算真实毛利率。请先在 数据→餐饮运营→配方管理 录入配方。"
                            placement="top">
                  <span style="color:#c0c4cc;cursor:help">— <span style="font-size:11px">无成本</span></span>
                </el-tooltip>
                <el-tag v-else-if="storeMarginMap[row.name]"
                        :type="storeMarginMap[row.name].marginRate >= 0.6 ? 'success'
                             : storeMarginMap[row.name].marginRate >= 0.4 ? ''
                             : 'warning'"
                        size="small">
                  {{ (storeMarginMap[row.name].marginRate * 100).toFixed(1) }}%
                </el-tag>
                <span v-else style="color:#c0c4cc">—</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag v-if="isWeakStore(row.name)" type="danger" size="small">异常</el-tag>
                <el-tag v-else-if="row.discountPct > 20" type="warning" size="small">折扣高</el-tag>
                <el-tag v-else type="success" size="small">正常</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <!-- Weak stores alert -->
        <el-alert
          v-if="weakStores.length > 0"
          style="margin-top: 16px"
          type="warning"
          :closable="false"
          show-icon
        >
          <template #title>
            <b>{{ weakStores.length }} 家门店营收低于中位数50%</b>
          </template>
          <div>{{ weakStores.join('、') }}</div>
        </el-alert>
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, watch, onUnmounted, onMounted } from 'vue'
import { ArrowLeft } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import echarts from '@/utils/echarts'
import { usePermissionStore } from '@/store/modules/permission'
import { useAuthStore } from '@/store/modules/auth'
import { useChartResize } from '@/composables/useChartResize'
import { formatAmount } from '@/utils/tableFormatters'
import { pythonFetch, getFactoryId } from '@/api/smartbi/common'
import type { StoreComparisonData } from '@/types/restaurant-analytics'
import { goldStoreComparisonToData, type GoldStoreComparisonPayload } from './storeComparisonGold'
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight'
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue'
import { useChartInsight } from '@/composables/useChartInsight'

const permissionStore = usePermissionStore()
const authStore = useAuthStore()
const canViewPrice = computed(() => permissionStore.canViewPrice)

const containerRef = ref<HTMLElement>()
useChartResize(containerRef)

// WS3 #2: 门店对比走 gold /restaurant-ops/store-comparison (默认全部历史),
// 不再依赖手选 CSV 数据源。毛利率列仍走 /store-margin (storeMarginMap)。
const storeData = ref<StoreComparisonData | null>(null)
const loading = ref(false)

async function loadStoreComparison() {
  loading.value = true
  try {
    const factoryId = getFactoryId()
    // 默认不传日期 = 全部历史 (WS1 store-comparison 端点省略 → 开区间)。
    const res = await pythonFetch(
      `/api/smartbi/restaurant-ops/store-comparison?factory_id=${factoryId}`,
      { timeoutMs: 60000 },
    ) as { success?: boolean; message?: string; data?: GoldStoreComparisonPayload }
    if (res && res.success === false) {
      ElMessage.error(res.message || '门店对比数据加载失败')
      storeData.value = null
      return
    }
    storeData.value = goldStoreComparisonToData(res?.data)
  } catch (e) {
    console.error('[store-compare] comparison load failed:', e)
    ElMessage.error('门店对比数据加载失败，请稍后重试')
    storeData.value = null
  } finally {
    loading.value = false
  }
}

const stores = computed(() => storeData.value?.stores ?? [])
const weakStores = computed(() => storeData.value?.weakStores ?? [])

// Apr 24 Plan C Phase 7++: per-store margin from Gold
interface StoreMarginRow { storeId: number; name: string; revenue: number; grossProfit: number; marginRate: number; bills: number; dishesWithCost: number; totalDishes: number }
const storeMarginMap = ref<Record<string, StoreMarginRow>>({})

async function loadStoreMargin() {
  // P1-6: days=365 to align with other Gold pages (gross-margin.vue, menu-board.vue)
  // so historic POS data (e.g. qhj 2025) matches on first load. Using 30 days would
  // return empty for any merchant whose POS is not recent, causing the 毛利率 column
  // to silently show "—" for all rows.
  try {
    const res = await pythonFetch('/api/smartbi/restaurant-ops/store-margin?days=365') as {
      success: boolean
      data?: { stores: StoreMarginRow[] }
    }
    if (res.success && res.data) {
      const map: Record<string, StoreMarginRow> = {}
      for (const s of res.data.stores || []) map[s.name] = s
      storeMarginMap.value = map
    }
  } catch (e) {
    console.warn('[store-compare] margin load failed:', e)
  }
}

// Re-render chart when data changes
watch(storeData, (val) => {
  if (val) {
    nextTick(() => setTimeout(renderChart, 300))
    loadStoreMargin()  // Load margin in parallel (separate API)
  }
})

function isWeakStore(name: string): boolean {
  return weakStores.value.includes(name)
}

// Treat "no cost data" stores as -1 so sort-desc parks them at the bottom
// (not at the top with the fake 100% margin).
function marginSortValue(name: string): number {
  const m = storeMarginMap.value[name]
  if (!m) return -1
  if (m.dishesWithCost === 0) return -1
  return m.marginRate
}

function renderChart() {
  const el = document.getElementById('chart-store-bar')
  if (!el || stores.value.length === 0) return
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)

  const sorted = [...stores.value].sort((a, b) => a.revenue - b.revenue)
  const displayStores = sorted.length > 20 ? sorted.slice(-20) : sorted

  chart.setOption({
    tooltip: {
      trigger: 'axis',
      confine: true,
      axisPointer: { type: 'shadow' },
      formatter: (params: Record<string, unknown>[]) => {
        const p = params[0] as Record<string, unknown>
        const store = stores.value.find(s => s.name === p.name)
        if (!store) return p.name
        return `<b>${store.name}</b><br/>营收: ${formatAmount(store.revenue)}<br/>品均收入: ${formatAmount(store.avgTicket)}<br/>折扣率: ${store.discountPct.toFixed(1)}%`
      },
    },
    grid: { left: 140, right: 30, top: 10, bottom: 20 },
    xAxis: {
      type: 'value',
      axisLabel: { formatter: (v: number) => v >= 1e4 ? (v / 1e4).toFixed(0) + '万' : String(v) },
    },
    yAxis: {
      type: 'category',
      data: displayStores.map(s => s.name),
      axisLabel: { width: 120, overflow: 'truncate' },
    },
    series: [{
      type: 'bar',
      data: displayStores.map(s => ({
        value: s.revenue,
        itemStyle: {
          color: weakStores.value.includes(s.name) ? '#F56C6C'
            : s.discountPct > 20 ? '#E6A23C' : '#409EFF',
          borderRadius: [0, 4, 4, 0],
        },
      })),
      barMaxWidth: 20,
    }],
  })
}

// ==================== Chart Insight ====================
// Single BAR chart: stores ranked by revenue (RANKING family, domain='restaurant').
// renderChart() sorts stores asc and slices to max 20, but for insight purposes
// we use all stores (the insight engine takes the full dataset and ranks internally).
const storeComparisonSource = (): { chart: ChartWithMeta } | null => {
  if (stores.value.length < 2) return null;
  const sorted = [...stores.value].sort((a, b) => b.revenue - a.revenue);
  return {
    chart: {
      chartType: 'BAR',
      meta: { xDim: 'store', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
      config: {
        xAxis: { data: sorted.map((s) => s.name) },
        series: [{ type: 'bar', data: sorted.map((s) => s.revenue) }],
      },
    },
  };
};

const storeComparisonPerms = () => ({ canViewFinance: permissionStore.canWrite('finance') });

const { insight: storeComparisonInsight, loading: storeComparisonInsightLoading } = useChartInsight(
  storeComparisonSource,
  storeComparisonPerms,
  { factoryId: () => authStore.factoryId ?? '', autoTier2: true },
);

onMounted(() => {
  // Only fetch when the user can view price/financial data — otherwise the page
  // shows the no-permission empty state and we skip the (would-be-stripped) call.
  if (canViewPrice.value) loadStoreComparison()
})

onUnmounted(() => {
  const el = document.getElementById('chart-store-bar')
  if (el) echarts.getInstanceByDom(el)?.dispose()
})
</script>

<style scoped>
.page-wrapper { padding: 16px; }
.page-card { border-radius: 8px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-left { display: flex; align-items: center; gap: 8px; }
.header-right { display: flex; gap: 8px; }
.page-title { font-size: 16px; font-weight: 600; }
.chart-title { font-weight: 600; }
.weak-store { color: var(--el-color-danger); font-weight: 600; }
.high-discount { color: var(--el-color-warning); font-weight: 600; }
</style>
