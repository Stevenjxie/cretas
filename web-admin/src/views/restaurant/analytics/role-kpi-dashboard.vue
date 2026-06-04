<template>
  <div class="page-wrapper" ref="containerRef">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">经营看板</span>
            <!-- 防呆 Rule 2: header 三元素 店名 · 期间 · 角色 -->
            <el-tag size="small" type="info" effect="plain">{{ storeLabel }}</el-tag>
            <el-tag size="small" type="info" effect="plain">{{ periodLabel }}</el-tag>
            <el-tag size="small" type="info" effect="plain">{{ roleLabel }}</el-tag>
            <el-tag v-if="overallHealth" :type="badgeType(overallHealth)" size="small" effect="dark">
              整体经营健康度: {{ healthText(overallHealth) }}
            </el-tag>
          </div>
          <div class="header-right">
            <el-button text :icon="Refresh" @click="load" :loading="loading">刷新</el-button>
          </div>
        </div>
      </template>

      <div v-if="loading" v-loading="true" style="min-height: 360px" />

      <!-- 诚实空态 -->
      <el-empty
        v-else-if="isEmpty"
        :description="emptyMessage || '暂无经营数据。上传 POS 销售/财务数据后将自动汇总店长 6 项指标。'"
      >
        <el-button type="primary" @click="$router.push('/restaurant/analytics/targets')">去配置目标</el-button>
      </el-empty>

      <!-- 6 KPI cards grid -->
      <div v-else class="kpi-grid">
        <el-card
          v-for="k in kpis"
          :key="k.key"
          class="kpi-card"
          :class="'status-' + (k.status || 'GOOD').toLowerCase()"
          shadow="hover"
        >
          <div class="kpi-head">
            <span class="kpi-label">{{ k.label }}</span>
            <el-tag :type="badgeType(k.status)" size="small" effect="dark">{{ statusText(k.status) }}</el-tag>
          </div>

          <div class="kpi-value">
            <!-- masked money → "—" (compute already nulled rawValue for non price-view) -->
            <span v-if="isMasked(k)" class="masked">—</span>
            <span v-else>{{ k.value }}</span>
            <span v-if="k.unit && k.unit !== '%' && !isMasked(k)" class="kpi-unit">&nbsp;</span>
          </div>

          <!-- 目标完成率 progress bar -->
          <div v-if="k.key === 'target_completion' && k.rawValue != null" class="kpi-progress">
            <el-progress
              :percentage="progressPct(k.rawValue)"
              :status="progressStatus(k.status)"
              :stroke-width="10"
            />
          </div>

          <!-- 防呆 Rule 5: 目标未配置 → 去配置目标 -->
          <div v-if="k.key === 'target_completion' && k.needsConfig" class="kpi-action">
            <span class="kpi-note">尚未配置经营目标</span>
            <el-button size="small" type="primary" plain @click="$router.push('/restaurant/analytics/targets')">
              去配置目标
            </el-button>
          </div>
          <div v-else-if="k.key === 'target_completion' && k.configExists === false" class="kpi-note">
            预警阈值未配置，使用默认 (达成 ≥90% 良好 / &lt;70% 严重)。
          </div>

          <!-- 毛利率 数据不足 → 去录入配方 -->
          <div v-if="k.key === 'gross_margin' && k.status === 'INSUFFICIENT'" class="kpi-action">
            <span class="kpi-note">暂无配方成本数据，无法计算真实毛利率</span>
            <el-button size="small" type="primary" plain @click="$router.push('/restaurant/recipes')">
              去录入配方
            </el-button>
          </div>
          <div v-else-if="k.key === 'gross_margin' && k.detail" class="kpi-note">
            {{ marginNote(k) }}
          </div>

          <!-- 食材成本率 口径说明 -->
          <div v-if="k.key === 'food_cost_rate' && k.status !== 'INSUFFICIENT'" class="kpi-note">
            领料成本 ÷ 营收 (Layer1, 与配方理论 COGS 口径不同)
          </div>

          <!-- 日营收 detail -->
          <div v-if="k.key === 'daily_revenue' && !isMasked(k) && k.detail" class="kpi-note">
            区间 {{ (k.detail.dayCount ?? '—') }} 天日均
          </div>
        </el-card>
      </div>

      <p v-if="!isEmpty && !canViewPrice" class="rbac-hint">
        ⓘ 当前角色无价格查看权限，营收/客单价等金额已隐藏 (显示 "—")；比率与计数正常展示。
      </p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { usePermissionStore } from '@/store/modules/permission'
import { pythonFetch, getFactoryId } from '@/api/smartbi/common'

interface KpiCard {
  key: string
  label: string
  unit?: string
  value: string
  rawValue: number | null
  status: string            // GOOD / WARNING / CRITICAL / INSUFFICIENT / NO_TARGET
  money?: boolean
  needsConfig?: boolean
  configExists?: boolean
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  detail?: Record<string, any>
}
interface DashboardPayload {
  storeName?: string | null
  startDate?: string | null
  endDate?: string | null
  kpis?: KpiCard[]
  overallHealth?: string
  empty?: boolean
  message?: string
}

const permissionStore = usePermissionStore()
const canViewPrice = computed(() => permissionStore.canViewPrice)
const roleLabel = computed(() => {
  const r = permissionStore.currentRole || '—'
  return `角色: ${r}`
})

const containerRef = ref<HTMLElement>()
const loading = ref(false)
const payload = ref<DashboardPayload | null>(null)

const kpis = computed<KpiCard[]>(() => payload.value?.kpis ?? [])
const overallHealth = computed(() => payload.value?.overallHealth ?? '')
const isEmpty = computed(() => !payload.value || payload.value.empty === true || kpis.value.length === 0)
const emptyMessage = computed(() => payload.value?.message ?? '')

const storeLabel = computed(() => {
  const n = payload.value?.storeName
  return n ? `门店: ${n}` : '门店: 本店 (单店直营)'
})
const periodLabel = computed(() => {
  const s = payload.value?.startDate
  const e = payload.value?.endDate
  if (!s && !e) return '期间: 全部历史'
  return `期间: ${s ?? '起始'} ~ ${e ?? '至今'}`
})

async function load() {
  loading.value = true
  try {
    const factoryId = getFactoryId()
    // 默认不传日期 = 全部历史 (后端 _parse_range 开区间)。
    const res = await pythonFetch(
      `/api/smartbi/gold/store-kpi-dashboard?factory_id=${factoryId}`,
      { timeoutMs: 60000 },
    ) as DashboardPayload & { success?: boolean; message?: string }
    if (res && (res as { success?: boolean }).success === false) {
      ElMessage.error(res.message || '经营 KPI 看板加载失败')
      payload.value = null
      return
    }
    payload.value = res
  } catch (e) {
    console.error('[role-kpi] load failed:', e)
    ElMessage.error('经营 KPI 看板加载失败，请稍后重试')
    payload.value = null
  } finally {
    loading.value = false
  }
}

// money card whose rawValue was nulled server-side (non price-view role).
function isMasked(k: KpiCard): boolean {
  return !!k.money && k.rawValue === null
}

function badgeType(status?: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 'GOOD': return 'success'
    case 'WARNING': return 'warning'
    case 'CRITICAL': return 'danger'
    default: return 'info'   // INSUFFICIENT / NO_TARGET
  }
}
function statusText(status?: string): string {
  switch (status) {
    case 'GOOD': return '良好'
    case 'WARNING': return '预警'
    case 'CRITICAL': return '严重'
    case 'INSUFFICIENT': return '数据不足'
    case 'NO_TARGET': return '未设目标'
    default: return status || ''
  }
}
function healthText(h: string): string {
  switch (h) {
    case 'GOOD': return '良好'
    case 'WARNING': return '需关注'
    case 'CRITICAL': return '严重'
    default: return h
  }
}
function progressPct(rate: number): number {
  // rate is 0..1+ (实际/目标). cap progress bar at 100 visually.
  return Math.min(Math.round(rate * 100), 100)
}
function progressStatus(status?: string): '' | 'success' | 'warning' | 'exception' {
  switch (status) {
    case 'GOOD': return 'success'
    case 'WARNING': return 'warning'
    case 'CRITICAL': return 'exception'
    default: return ''
  }
}
function marginNote(k: KpiCard): string {
  const d = k.detail || {}
  if (d.dishesWithCost != null && d.totalDishes != null) {
    return `基于 ${d.dishesWithCost}/${d.totalDishes} 个有配方成本菜品 (±15% Layer1 精度)`
  }
  return ''
}

onMounted(load)
</script>

<style scoped>
.page-wrapper { padding: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-left { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.page-title { font-size: 18px; font-weight: 600; }
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
}
.kpi-card { border-left: 4px solid var(--el-color-info); }
.kpi-card.status-good { border-left-color: var(--el-color-success); }
.kpi-card.status-warning { border-left-color: var(--el-color-warning); }
.kpi-card.status-critical { border-left-color: var(--el-color-danger); }
.kpi-card.status-insufficient,
.kpi-card.status-no_target { border-left-color: var(--el-color-info); }
.kpi-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.kpi-label { font-size: 14px; color: var(--el-text-color-regular); }
.kpi-value { font-size: 26px; font-weight: 700; color: var(--el-text-color-primary); }
.kpi-value .masked { color: var(--el-text-color-disabled); }
.kpi-unit { font-size: 14px; }
.kpi-progress { margin-top: 12px; }
.kpi-action { margin-top: 10px; display: flex; flex-direction: column; gap: 6px; align-items: flex-start; }
.kpi-note { margin-top: 8px; font-size: 12px; color: var(--el-text-color-secondary); }
.rbac-hint { margin-top: 16px; font-size: 12px; color: var(--el-text-color-secondary); }
</style>
