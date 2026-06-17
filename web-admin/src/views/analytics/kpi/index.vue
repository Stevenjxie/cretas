<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Refresh, TrendCharts, Histogram, Timer, Check, KnifeFork, DataAnalysis, Money, Sell, Calendar, Shop } from '@element-plus/icons-vue';
import { pythonFetch } from '@/api/smartbi/common';
import { getKpiSummary, getFinanceSummary, getTrendBundle } from '@/api/smartbi/gold';
import { buildRestaurantKpiBoard, type RestaurantKpiBoard } from './restaurantKpiBoard';
import { formatNumber } from '@/utils/format-number';
import {
  fetchAchievement,
  fetchAlerts,
  type AchievementResponse,
  type AlertResponse,
} from '@/api/smartbi/restaurant-targets';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const router = useRouter();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);
// Apr 24 P1.6: restaurant tenants see ops-specific KPIs, not manufacturing ones
const isRestaurant = computed(() => authStore.factoryType === 'RESTAURANT');

const loading = ref(false);

// Restaurant KPI set (domain-appropriate)
const restaurantKpi = ref({
  wastageRate: 0,        // wastage_cost / requisition_cost — 损耗率
  shortageRate: 0,       // |shortage_qty| / requisition_qty — 盘亏率
  activeDays: 0,         // req + wastage + stock active days in window
  totalCost: 0,          // sum req cost
  wastageCost: 0,
  shortageQty: 0,
  topIngredient: '',
  // Phase 7+: gross margin (POS × food cost)
  marginRate: 0,         // POS revenue - food_cost / POS revenue
  marginRevenue: 0,
  marginProfit: 0,
});

// Jun 2026 follow-up: GOLD 经营 KPI board for restaurant tenants.
// 青花椒 (POS-only) has no 领料/损耗/盘点 data → the old ops cards showed empty
// "—". Surface the boss-relevant Gold KPIs instead (营收/订单/客单价/门店/天数/峰值月).
const goldBoard = ref<RestaurantKpiBoard | null>(null);
const goldBoardError = ref('');
// Whether the legacy 领料/损耗/盘点 ops section actually has any data — only
// then do we render it (honest: hide the empty ops card for POS-only tenants).
const hasOpsData = computed(() =>
  restaurantKpi.value.activeDays > 0 ||
  restaurantKpi.value.totalCost > 0 ||
  restaurantKpi.value.wastageCost > 0 ||
  (restaurantKpi.value.topIngredient !== '' && restaurantKpi.value.topIngredient !== '—'),
);

function kpiDisplay(item: { value: number | null; text?: string | null; kind: string }): string {
  if (item.kind === 'text') return item.text || '—';
  if (item.value === null) return '—';
  if (item.kind === 'money') return '¥' + formatNumber(item.value);
  return formatNumber(item.value, 0);
}

const goldIconFor: Record<string, unknown> = {
  revenue: Money,
  billCount: Sell,
  avgBillValue: Money,
  storeCount: Shop,
  dayCount: Calendar,
  peakMonth: TrendCharts,
};

async function loadGoldBoard() {
  if (!factoryId.value) return;
  goldBoardError.value = '';
  try {
    // All-history (omit dates). Money fields RBAC-nulled for non price-view roles.
    const [kpi, fin, bundle] = await Promise.all([
      getKpiSummary({ factoryId: factoryId.value }),
      getFinanceSummary({ factoryId: factoryId.value, topNStores: 5 }),
      getTrendBundle({ factoryId: factoryId.value }),
    ]);
    goldBoard.value = buildRestaurantKpiBoard(kpi, fin, bundle, canViewPrice.value);
  } catch (e) {
    // 禁降级假数据: 显式报错, 不伪造
    goldBoardError.value = e instanceof Error ? e.message : '加载经营 KPI 失败';
    goldBoard.value = null;
    console.error('[kpi-dashboard] gold board load failed:', e);
  }
}

// ── G2 Achievement + Alert state ─────────────────────────────────────────────
const achievementLevel = ref<'day' | 'week' | 'month'>('day');
const achievementData = ref<AchievementResponse | null>(null);
const alertData = ref<AlertResponse | null>(null);
const achievementLoading = ref(false);
// Fix 4: distinguish a READ FAILURE (500/network) from a genuine
// "not configured" empty state. Without this, a failed read leaves
// achievementData/alertData null → template renders "尚未设置营业目标" /
// "预警阈值未配置", disguising a system error as user mis-configuration
// (防呆 Rule 5 violation).
const achievementError = ref('');

const fmtDate = (d: Date) => {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
};

async function loadAchievementData() {
  if (!factoryId.value || !isRestaurant.value) return;
  achievementLoading.value = true;
  achievementError.value = '';
  try {
    const today = new Date();
    let start: string;
    const end = fmtDate(today);
    if (achievementLevel.value === 'day') {
      start = fmtDate(new Date(today.getTime() - 6 * 86400000));
    } else if (achievementLevel.value === 'week') {
      start = fmtDate(new Date(today.getTime() - 27 * 86400000));
    } else {
      start = fmtDate(new Date(today.getFullYear(), today.getMonth(), 1));
    }
    const [achRes, alertRes] = await Promise.all([
      fetchAchievement({ startDate: start, endDate: end, level: achievementLevel.value }),
      fetchAlerts({ lookbackDays: 7 }),
    ]);
    // success===false is a real backend error, NOT an empty/unconfigured state.
    if (!achRes.success || !alertRes.success) {
      throw new Error(achRes.message || alertRes.message || '达成率数据加载失败');
    }
    achievementData.value = achRes.data;
    alertData.value = alertRes.data;
  } catch (e) {
    // Fix 4: surface read failure explicitly instead of masquerading as
    // "尚未配置". Keep data null so cards render the error state (with retry),
    // and emit a non-blocking toast carrying the backend message.
    const msg = e instanceof Error ? e.message : '达成率数据加载失败';
    achievementError.value = msg;
    achievementData.value = null;
    alertData.value = null;
    console.error('[kpi-dashboard] achievement load failed:', e);
    ElMessage({ message: `达成率数据加载失败: ${msg}`, type: 'error', duration: 0, showClose: true });
  } finally {
    achievementLoading.value = false;
  }
}

// Alert timeline status → color
const statusColor = (status: string) => ({
  OK: '#67c23a',
  WARN: '#e6a23c',
  CRITICAL: '#f56c6c',
  DATA_MISSING: '#909399',
  NO_TARGET: '#c0c4cc',
}[status] ?? '#c0c4cc');

watch(achievementLevel, () => { if (isRestaurant.value) loadAchievementData(); });

async function loadRestaurantKpi() {
  if (!factoryId.value) return;
  loading.value = true;
  // Gold 经营 KPI board (primary) + legacy ops summary (shown only if it has data).
  void loadGoldBoard();
  // G2: achievement + alert (fire-and-forget; renders own card)
  void loadAchievementData();
  try {
    // WS4 #10: 默认全部历史。restaurant-ops/summary 的 days 上限 365 (后端 Query le=365),
    // 取最大窗 = 餐饮租户全部历史 (qhj 等数据落在单年内)。绝不再用 days=30 默认窗。
    const res = await pythonFetch('/api/smartbi/restaurant-ops/summary?days=365') as {
      success: boolean;
      data?: {
        totals?: Record<string, number>;
        top5_ingredients?: { name: string }[];
      };
    };
    if (res.success && res.data) {
      // pythonFetch auto-transforms snake_case → camelCase
      const t = (res.data.totals || {}) as Record<string, number>;
      const top5 = ((res.data as Record<string, unknown>).top5Ingredients || []) as { name: string }[];
      restaurantKpi.value.totalCost = t.totalReqCost || 0;
      restaurantKpi.value.wastageCost = t.totalWastageCost || 0;
      restaurantKpi.value.shortageQty = t.totalShortage || 0;
      restaurantKpi.value.activeDays = t.activeDays || 0;
      restaurantKpi.value.topIngredient = top5[0]?.name || '—';
      const reqCost = t.totalReqCost || 0;
      const reqQty = t.totalReqQty || 0;
      restaurantKpi.value.wastageRate = reqCost > 0 ? (t.totalWastageCost || 0) / reqCost : 0;
      restaurantKpi.value.shortageRate = reqQty > 0 ? (t.totalShortage || 0) / reqQty : 0;
      // Phase 7+ gross margin
      const margin = ((res.data as Record<string, unknown>).margin || {}) as Record<string, number>;
      restaurantKpi.value.marginRate = margin.avgMarginRate || 0;
      restaurantKpi.value.marginRevenue = margin.totalPosRevenue || 0;
      restaurantKpi.value.marginProfit = margin.totalGrossProfit || 0;
    }
  } catch (e) {
    console.error('[kpi-dashboard] restaurant kpi load failed:', e);
  } finally {
    loading.value = false;
  }
}

// KPI 数据
const kpiData = ref({
  production: {
    oee: 0,           // Overall Equipment Effectiveness
    yield: 0,         // 良品率
    cycleTime: 0,     // 周期时间
    throughput: 0     // 产出量
  },
  quality: {
    fpy: 0,           // First Pass Yield
    defectRate: 0,    // 缺陷率
    reworkRate: 0,    // 返工率
    scrapRate: 0      // 报废率
  },
  delivery: {
    onTimeRate: 0,    // 准时交付率
    leadTime: 0,      // 交期
    fillRate: 0       // 订单满足率
  },
  cost: {
    unitCost: 0,      // 单位成本
    materialCost: 0,  // 原料成本占比
    laborCost: 0,     // 人工成本占比
    overheadCost: 0   // 间接成本占比
  }
});

// 目标值
const targets = {
  oee: 85,
  yield: 95,
  fpy: 90,
  onTimeRate: 95,
  defectRate: 5
};

onMounted(() => {
  if (isRestaurant.value) {
    loadRestaurantKpi();
    return;
  }
  loadKPIData();
});

// Backend KpiMetricsDTO shape (/reports/kpi-metrics). All rate fields are plain
// percentages (0–100), EXCEPT oee which is a ×100-scaled percentage
// (= availability% × performance% × quality% / 10000, e.g. 7999.30 → 79.99%).
// The template renders every nested value via `× 100` (formatPercent / el-progress
// `:percentage="x * 100"`), so the mapper must convert each field to a 0–1 fraction.
interface KpiMetricsResponse {
  oee?: number;                 // ×100-scaled percentage (7999.30 = 79.99%)
  outputCompletionRate?: number;
  capacityUtilization?: number;
  avgCycleTime?: number;
  throughput?: number;
  fpy?: number;                 // plain % (0–100)
  overallQualityRate?: number;  // plain %
  scrapRate?: number;           // plain %
  reworkRate?: number;          // plain %
  unitCost?: number;
  materialCostRatio?: number;   // plain %
  laborCostRatio?: number;      // plain %
  overheadCostRatio?: number;   // plain %
  otif?: number;                // plain %
  onTimeDeliveryRate?: number;  // plain %
  inFullDeliveryRate?: number;  // plain %
  avgLeadTime?: number;
  orderFulfillmentRate?: number;// plain %
}

// plain-% (0–100) → 0–1 fraction (template multiplies back by 100 for display)
const pct = (v: number | undefined | null): number => (v ?? 0) / 100;

async function loadKPIData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const response = await get<KpiMetricsResponse>(`/${factoryId.value}/reports/kpi-metrics`);
    if (response.success && response.data) {
      const d = response.data;
      kpiData.value = {
        production: {
          // oee is ×100-scaled (7999.30 = 79.99%) → divide by 10000 to get fraction
          oee: (d.oee ?? 0) / 10000,
          yield: pct(d.overallQualityRate),
          cycleTime: d.avgCycleTime ?? 0,
          throughput: d.throughput ?? 0,
        },
        quality: {
          fpy: pct(d.fpy),
          // no direct defectRate field — first-pass failure rate is an honest proxy
          defectRate: pct(d.fpy != null ? 100 - d.fpy : 0),
          reworkRate: pct(d.reworkRate),
          scrapRate: pct(d.scrapRate),
        },
        delivery: {
          onTimeRate: pct(d.onTimeDeliveryRate ?? d.otif),
          leadTime: d.avgLeadTime ?? 0,
          fillRate: pct(d.inFullDeliveryRate ?? d.orderFulfillmentRate),
        },
        cost: {
          unitCost: d.unitCost ?? 0,
          materialCost: pct(d.materialCostRatio),
          laborCost: pct(d.laborCostRatio),
          overheadCost: pct(d.overheadCostRatio),
        },
      };
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载KPI数据失败');
    }
  } catch (error) {
    console.error('加载KPI数据失败:', error);
    ElMessage.error('加载KPI数据失败，请刷新重试');
  } finally {
    loading.value = false;
  }
}

function getProgressStatus(value: number, target: number) {
  if (target === 0) return 'exception';
  const ratio = value / target;
  if (ratio >= 1) return 'success';
  if (ratio >= 0.8) return 'warning';
  return 'exception';
}

function formatPercent(value: number) {
  return (value * 100).toFixed(1) + '%';
}
</script>

<template>
  <div class="kpi-page">
    <div class="page-header">
      <div class="header-left">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item :to="{ path: '/analytics' }">数据分析</el-breadcrumb-item>
          <el-breadcrumb-item>KPI看板</el-breadcrumb-item>
        </el-breadcrumb>
        <h1>KPI看板</h1>
      </div>
      <div class="header-right">
        <el-button type="primary" :icon="Refresh" @click="isRestaurant ? loadRestaurantKpi() : loadKPIData()">刷新数据</el-button>
      </div>
    </div>

    <!-- Jun 2026 follow-up: GOLD 经营 KPI board (营收/订单/客单价/门店/天数/峰值月).
         Primary for restaurant tenants — replaces the empty 领料/损耗/盘点 cards
         a POS-only restaurant (青花椒) doesn't have data for. -->
    <template v-if="isRestaurant">
      <div v-if="goldBoardError" class="gold-board-error">
        加载经营 KPI 失败: {{ goldBoardError }}
      </div>
      <div v-else-if="goldBoard && goldBoard.hasData" class="gold-board" v-loading="loading">
        <div class="gold-board-title">
          <el-icon><DataAnalysis /></el-icon>
          <span>经营 KPI (全部历史)</span>
          <span v-if="goldBoard.topStoreName" class="gold-board-sub">
            营收最高门店: {{ goldBoard.topStoreName }}
          </span>
        </div>
        <div class="gold-cards">
          <div v-for="item in goldBoard.items" :key="item.key" class="gold-card">
            <div class="gold-card-icon"><el-icon><component :is="goldIconFor[item.key]" /></el-icon></div>
            <div class="gold-card-body">
              <div class="gold-card-value">{{ kpiDisplay(item) }}</div>
              <div class="gold-card-label">{{ item.label }}</div>
              <div v-if="item.hint" class="gold-card-hint">{{ item.hint }}</div>
            </div>
          </div>
        </div>
        <div class="gold-board-actions">
          <el-button size="small" @click="router.push('/smart-bi/dashboard')">经营驾驶舱 →</el-button>
          <el-button size="small" @click="router.push('/smart-bi/analysis?tab=query')">AI 综合分析 →</el-button>
        </div>
      </div>
      <div v-else-if="!loading && goldBoard && !goldBoard.hasData" class="gold-board-empty">
        <el-empty description="暂无经营数据 — 上传 POS 流水后这里将显示营收/订单/客单价等 KPI">
          <el-button size="small" type="primary" @click="router.push('/smart-bi/dashboard')">前往经营驾驶舱 →</el-button>
        </el-empty>
      </div>
    </template>

    <!-- G2 (2026-06-03): 营业额达成率 KPI 卡 + 近 7 天预警 timeline -->
    <div v-if="isRestaurant" class="kpi-grid">
      <!-- 达成率 KPI 卡 -->
      <el-card class="kpi-card" v-loading="achievementLoading">
        <template #header>
          <div class="card-header">
            <el-icon><DataAnalysis /></el-icon>
            <span>营业额达成率</span>
            <el-radio-group
              v-model="achievementLevel"
              size="small"
              style="margin-left: auto;"
              @change="loadAchievementData"
            >
              <el-radio-button value="day">日</el-radio-button>
              <el-radio-button value="week">周</el-radio-button>
              <el-radio-button value="month">月</el-radio-button>
            </el-radio-group>
          </div>
        </template>

        <!-- Fix 4: read failure → explicit error state + retry (NOT 未配置) -->
        <div v-if="achievementError">
          <el-alert
            type="error"
            :closable="false"
            show-icon
            :title="`达成率数据加载失败: ${achievementError}`"
          />
          <div style="text-align: center; margin-top: 8px;">
            <el-button size="small" :icon="Refresh" @click="loadAchievementData">重试</el-button>
          </div>
        </div>
        <template v-else-if="achievementData && achievementData.points.length > 0">
          <div v-for="pt in achievementData.points.slice(-3)" :key="pt.periodKey" class="kpi-item">
            <div class="kpi-label">
              {{ pt.periodKey }}
              <span v-if="pt.dataMissing" style="color: #909399; font-size: 12px;">（数据缺失）</span>
              <!-- Fix 3: incomplete period badge — a partial week/month rate is
                   not a real low-achievement signal. -->
              <el-tag v-else-if="pt.inProgress" size="small" type="info" effect="plain" style="margin-left: 6px;">
                进行中（已过 {{ pt.daysElapsed ?? 0 }}/{{ pt.daysTotal ?? 0 }} 天）
              </el-tag>
            </div>
            <template v-if="!pt.dataMissing && pt.achievementRate !== null">
              <!-- in-progress: neutral color (do NOT alert on a partial-period rate) -->
              <el-progress
                :percentage="Math.min(100, Math.round((pt.achievementRate ?? 0) * 100))"
                :color="pt.inProgress
                  ? '#909399'
                  : ((pt.achievementRate ?? 0) >= 0.8 ? '#67c23a' : (pt.achievementRate ?? 0) >= 0.6 ? '#e6a23c' : '#f56c6c')"
              />
              <!-- Rule 2: show target + actual + rate together -->
              <div class="kpi-footer" v-if="canViewPrice">
                <span>目标 ¥{{ (pt.target ?? 0).toLocaleString() }}</span>
                <span style="margin: 0 8px;">·</span>
                <span>实际 ¥{{ (pt.actual ?? 0).toLocaleString() }}</span>
                <span style="margin: 0 8px;">·</span>
                <span>{{ ((pt.achievementRate ?? 0) * 100).toFixed(1) }}%</span>
                <span v-if="pt.inProgress" style="margin-left: 6px; color: #909399;">（周期未结束，进度仅供参考）</span>
              </div>
              <div class="kpi-footer" v-else>
                <span>达成率 {{ ((pt.achievementRate ?? 0) * 100).toFixed(1) }}%</span>
                <span v-if="pt.inProgress" style="margin-left: 6px; color: #909399;">（周期未结束，进度仅供参考）</span>
              </div>
            </template>
            <div v-else-if="pt.dataMissing" class="kpi-footer" style="color: #909399;">
              POS 数据缺失，不计入达成
            </div>
          </div>
        </template>
        <!-- Rule 5: no target → navigate to config -->
        <div v-else>
          <el-empty description="尚未设置营业目标" :image-size="80" />
          <div style="text-align: center; margin-top: 8px;">
            <el-button type="primary" size="small" @click="router.push('/restaurant/analytics/targets')">
              立即设置目标
            </el-button>
          </div>
        </div>
      </el-card>

      <!-- 近 7 天达成预警 timeline -->
      <el-card class="kpi-card">
        <template #header>
          <div class="card-header"><el-icon><Timer /></el-icon><span>近 7 天达成预警</span></div>
        </template>
        <!-- Fix 4: read failure → explicit error state + retry (NOT 未配置) -->
        <div v-if="achievementError">
          <el-alert
            type="error"
            :closable="false"
            show-icon
            :title="`预警数据加载失败: ${achievementError}`"
          />
          <div style="text-align: center; margin-top: 8px;">
            <el-button size="small" :icon="Refresh" @click="loadAchievementData">重试</el-button>
          </div>
        </div>
        <template v-else-if="alertData && alertData.configExists && alertData.timeline.length > 0">
          <el-timeline>
            <el-timeline-item
              v-for="entry in alertData.timeline"
              :key="entry.date"
              :color="statusColor(entry.status)"
              :timestamp="entry.date"
            >
              <!-- Rule 2: full context per node -->
              <span v-if="entry.status === 'DATA_MISSING'" style="color: #909399;">数据缺失</span>
              <span v-else-if="entry.status === 'NO_TARGET'" style="color: #c0c4cc;">无目标配置</span>
              <span v-else>
                {{ entry.status }} ·
                <template v-if="canViewPrice">
                  ¥{{ (entry.actual ?? 0).toLocaleString() }} / ¥{{ (entry.target ?? 0).toLocaleString() }} =
                </template>
                {{ entry.achievementRate !== null ? ((entry.achievementRate ?? 0) * 100).toFixed(1) + '%' : '—' }}
              </span>
            </el-timeline-item>
          </el-timeline>
        </template>
        <!-- Rule 5: config not set → navigate to config (only when read succeeded) -->
        <div v-else-if="alertData && !alertData.configExists">
          <el-empty description="预警阈值未配置" :image-size="80" />
          <div style="text-align: center; margin-top: 8px;">
            <el-button size="small" @click="router.push('/restaurant/analytics/targets')">配置预警阈值</el-button>
          </div>
        </div>
        <div v-else>
          <el-empty description="暂无预警数据" :image-size="80" />
        </div>
      </el-card>
    </div>

    <!-- Apr 24 P1.6: restaurant-specific KPI view (Plan C Gold).
         领料/损耗/盘点 ops cards — shown ONLY when the tenant actually has
         requisition/wastage data (honest: hidden for POS-only restaurants). -->
    <div v-if="isRestaurant && hasOpsData" class="kpi-grid" v-loading="loading">
      <el-card class="kpi-card">
        <template #header>
          <div class="card-header"><el-icon><KnifeFork /></el-icon><span>运营指标 (全部历史)</span></div>
        </template>
        <div class="kpi-item">
          <div class="kpi-label">损耗率 (金额占领料比)</div>
          <el-progress
            :percentage="Math.min(100, Math.round(restaurantKpi.wastageRate * 100))"
            :color="restaurantKpi.wastageRate > 0.05 ? '#f56c6c' : restaurantKpi.wastageRate > 0.02 ? '#e6a23c' : '#67c23a'"
          />
          <div class="kpi-footer">
            <span>当前: {{ (restaurantKpi.wastageRate * 100).toFixed(2) }}%</span>
            <span class="target">目标: &lt; 2%</span>
          </div>
        </div>
        <div class="kpi-item">
          <div class="kpi-label">盘亏率 (数量占领料比)</div>
          <el-progress
            :percentage="Math.min(100, Math.round(restaurantKpi.shortageRate * 100))"
            :color="restaurantKpi.shortageRate > 0.03 ? '#f56c6c' : '#67c23a'"
          />
          <div class="kpi-footer">
            <span>当前: {{ (restaurantKpi.shortageRate * 100).toFixed(2) }}%</span>
            <span class="target">目标: &lt; 1%</span>
          </div>
        </div>
        <div v-if="canViewPrice && restaurantKpi.marginRevenue > 0" class="kpi-item">
          <div class="kpi-label">菜品毛利率 (POS 销售 × 配方成本)</div>
          <el-progress
            :percentage="Math.round(restaurantKpi.marginRate * 100)"
            :color="restaurantKpi.marginRate >= 0.5 ? '#67c23a' : restaurantKpi.marginRate >= 0.3 ? '#e6a23c' : '#f56c6c'"
          />
          <div class="kpi-footer">
            <span>当前: {{ (restaurantKpi.marginRate * 100).toFixed(1) }}%</span>
            <span class="target">目标: ≥ 60%</span>
          </div>
        </div>
        <div class="kpi-stats">
          <div class="stat"><div class="stat-value">{{ restaurantKpi.activeDays }}</div><div class="stat-label">活动天数</div></div>
          <div v-if="canViewPrice" class="stat"><div class="stat-value">¥{{ Math.round(restaurantKpi.totalCost).toLocaleString() }}</div><div class="stat-label">领料总成本</div></div>
        </div>
      </el-card>

      <el-card class="kpi-card">
        <template #header>
          <div class="card-header"><el-icon><DataAnalysis /></el-icon><span>AI 深度分析</span></div>
        </template>
        <div style="padding:12px 0">
          <p style="color:#606266;line-height:1.8">
            本 KPI 看板基于 <b>Gold 运营层聚合</b>. 数据来自领料/损耗/盘点 3 个 Silver 事实表
            的当日汇总 (全部历史).
          </p>
          <div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:12px">
 <el-button size="small" @click="router.push('/smart-bi/query?q=最近30天损耗最多的食材和类型占比')"> 损耗分析</el-button>
 <el-button size="small" @click="router.push('/smart-bi/query?q=哪个食材盘亏最严重')"> 盘亏分析</el-button>
 <el-button size="small" @click="router.push('/smart-bi/query?q=最近30天领料趋势 top 10 食材')"> 领料趋势</el-button>
            <el-button size="small" @click="router.push('/smart-bi/analysis?tab=query')">AI 综合分析 →</el-button>
          </div>
        </div>
      </el-card>

      <el-card class="kpi-card">
        <template #header>
          <div class="card-header"><el-icon><Histogram /></el-icon><span>消耗最多食材</span></div>
        </template>
        <div style="text-align:center;padding:20px 0">
          <div style="font-size:24px;font-weight:600;color:#303133">{{ restaurantKpi.topIngredient }}</div>
          <div style="color:#909399;margin-top:8px;font-size:13px">全部历史消耗最多 (按金额)</div>
          <el-button type="text" size="small" style="margin-top:12px"
            @click="router.push('/restaurant/requisitions')">查看领料明细 →</el-button>
        </div>
      </el-card>
    </div>

    <!-- Factory tenant: manufacturing KPI (original) -->
    <div v-if="!isRestaurant" class="kpi-grid" v-loading="loading">
      <!-- 生产效率KPI -->
      <el-card class="kpi-card">
        <template #header>
          <div class="card-header">
            <el-icon><TrendCharts /></el-icon>
            <span>生产效率</span>
          </div>
        </template>

        <div class="kpi-item">
          <div class="kpi-label">设备综合效率 (OEE)</div>
          <el-progress
            :percentage="kpiData.production.oee * 100"
            :status="getProgressStatus(kpiData.production.oee * 100, targets.oee)"
            :stroke-width="12"
          />
          <div class="kpi-meta">
            <span>当前: {{ formatPercent(kpiData.production.oee) }}</span>
            <span class="target">目标: {{ targets.oee }}%</span>
          </div>
        </div>

        <div class="kpi-item">
          <div class="kpi-label">良品率</div>
          <el-progress
            :percentage="kpiData.production.yield * 100"
            :status="getProgressStatus(kpiData.production.yield * 100, targets.yield)"
            :stroke-width="12"
          />
          <div class="kpi-meta">
            <span>当前: {{ formatPercent(kpiData.production.yield) }}</span>
            <span class="target">目标: {{ targets.yield }}%</span>
          </div>
        </div>

        <div class="kpi-stats">
          <div class="stat">
            <div class="stat-value">{{ kpiData.production.throughput }}</div>
            <div class="stat-label">日产出</div>
          </div>
          <div class="stat">
            <div class="stat-value">{{ kpiData.production.cycleTime }}min</div>
            <div class="stat-label">周期时间</div>
          </div>
        </div>
      </el-card>

      <!-- 质量KPI -->
      <el-card class="kpi-card">
        <template #header>
          <div class="card-header">
            <el-icon><Check /></el-icon>
            <span>质量指标</span>
          </div>
        </template>

        <div class="kpi-item">
          <div class="kpi-label">一次合格率 (FPY)</div>
          <el-progress
            :percentage="kpiData.quality.fpy * 100"
            :status="getProgressStatus(kpiData.quality.fpy * 100, targets.fpy)"
            :stroke-width="12"
          />
          <div class="kpi-meta">
            <span>当前: {{ formatPercent(kpiData.quality.fpy) }}</span>
            <span class="target">目标: {{ targets.fpy }}%</span>
          </div>
        </div>

        <div class="kpi-stats">
          <div class="stat danger">
            <div class="stat-value">{{ formatPercent(kpiData.quality.defectRate) }}</div>
            <div class="stat-label">缺陷率</div>
          </div>
          <div class="stat warning">
            <div class="stat-value">{{ formatPercent(kpiData.quality.reworkRate) }}</div>
            <div class="stat-label">返工率</div>
          </div>
          <div class="stat">
            <div class="stat-value">{{ formatPercent(kpiData.quality.scrapRate) }}</div>
            <div class="stat-label">报废率</div>
          </div>
        </div>
      </el-card>

      <!-- 交付KPI -->
      <el-card class="kpi-card">
        <template #header>
          <div class="card-header">
            <el-icon><Timer /></el-icon>
            <span>交付指标</span>
          </div>
        </template>

        <div class="kpi-item">
          <div class="kpi-label">准时交付率</div>
          <el-progress
            :percentage="kpiData.delivery.onTimeRate * 100"
            :status="getProgressStatus(kpiData.delivery.onTimeRate * 100, targets.onTimeRate)"
            :stroke-width="12"
          />
          <div class="kpi-meta">
            <span>当前: {{ formatPercent(kpiData.delivery.onTimeRate) }}</span>
            <span class="target">目标: {{ targets.onTimeRate }}%</span>
          </div>
        </div>

        <div class="kpi-stats">
          <div class="stat">
            <div class="stat-value">{{ kpiData.delivery.leadTime }}天</div>
            <div class="stat-label">平均交期</div>
          </div>
          <div class="stat">
            <div class="stat-value">{{ formatPercent(kpiData.delivery.fillRate) }}</div>
            <div class="stat-label">订单满足率</div>
          </div>
        </div>
      </el-card>

      <!-- 成本KPI -->
      <el-card v-if="canViewPrice" class="kpi-card">
        <template #header>
          <div class="card-header">
            <el-icon><Histogram /></el-icon>
            <span>成本结构</span>
          </div>
        </template>

        <div class="cost-breakdown">
          <div class="cost-item">
            <div class="cost-label">单位成本</div>
            <div class="cost-value">¥{{ kpiData.cost.unitCost.toFixed(2) }}</div>
          </div>

          <el-divider />

          <div class="cost-pie">
            <div class="pie-item material">
              <div class="pie-bar" :style="{ width: kpiData.cost.materialCost * 100 + '%' }"></div>
              <span class="pie-label">原料 {{ formatPercent(kpiData.cost.materialCost) }}</span>
            </div>
            <div class="pie-item labor">
              <div class="pie-bar" :style="{ width: kpiData.cost.laborCost * 100 + '%' }"></div>
              <span class="pie-label">人工 {{ formatPercent(kpiData.cost.laborCost) }}</span>
            </div>
            <div class="pie-item overhead">
              <div class="pie-bar" :style="{ width: kpiData.cost.overheadCost * 100 + '%' }"></div>
              <span class="pie-label">间接 {{ formatPercent(kpiData.cost.overheadCost) }}</span>
            </div>
          </div>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.kpi-page {
  padding: 20px;
}

/* Jun 2026 follow-up: GOLD 经营 KPI board (restaurant tenants) */
.gold-board {
  margin-bottom: 16px;
  padding: 18px 20px;
  background: linear-gradient(135deg, #f8fafc 0%, #f0f5ff 100%);
  border: 1px solid #e4ebf5;
  border-radius: 12px;

  .gold-board-title {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 15px;
    font-weight: 600;
    color: #303133;
    margin-bottom: 16px;

    .el-icon { color: #409eff; font-size: 18px; }
    .gold-board-sub {
      margin-left: auto;
      font-size: 12px;
      font-weight: 400;
      color: #909399;
    }
  }
}

.gold-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}

@media (max-width: 768px) {
  .gold-cards { grid-template-columns: repeat(2, 1fr); }
}

.gold-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);

  .gold-card-icon {
    flex-shrink: 0;
    width: 40px;
    height: 40px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 10px;
    background: #ecf5ff;
    color: #409eff;
    .el-icon { font-size: 20px; }
  }

  .gold-card-body { min-width: 0; }
  .gold-card-value {
    font-size: 22px;
    font-weight: 700;
    color: #303133;
    line-height: 1.2;
  }
  .gold-card-label {
    font-size: 13px;
    color: #606266;
    margin-top: 2px;
  }
  .gold-card-hint {
    font-size: 11px;
    color: #a8abb2;
    margin-top: 2px;
  }
}

.gold-board-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 16px;
}

.gold-board-error {
  margin-bottom: 16px;
  padding: 12px 16px;
  color: #f56c6c;
  background: #fef0f0;
  border-radius: 8px;
}

.gold-board-empty {
  margin-bottom: 16px;
  padding: 12px;
  background: #fafafa;
  border-radius: 8px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 24px;

  .header-left h1 {
    margin: 12px 0 0;
    font-size: 20px;
    font-weight: 600;
  }
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.kpi-card {
  border-radius: 12px;

  .card-header {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;

    .el-icon {
      font-size: 18px;
      color: #409EFF;
    }
  }
}

.kpi-item {
  margin-bottom: 20px;

  .kpi-label {
    font-size: 13px;
    color: #606266;
    margin-bottom: 8px;
  }

  .kpi-meta {
    display: flex;
    justify-content: space-between;
    margin-top: 8px;
    font-size: 12px;
    color: #909399;

    .target {
      color: #67C23A;
    }
  }
}

.kpi-stats {
  display: flex;
  gap: 16px;
  padding-top: 16px;
  border-top: 1px solid #ebeef5;

  .stat {
    flex: 1;
    text-align: center;
    padding: 12px;
    background: #f5f7fa;
    border-radius: 8px;

    .stat-value {
      font-size: 20px;
      font-weight: 600;
      color: #303133;
    }

    .stat-label {
      font-size: 12px;
      color: #909399;
      margin-top: 4px;
    }

    &.danger .stat-value {
      color: #F56C6C;
    }

    &.warning .stat-value {
      color: #E6A23C;
    }
  }
}

.cost-breakdown {
  .cost-item {
    display: flex;
    justify-content: space-between;
    align-items: center;

    .cost-label {
      font-size: 14px;
      color: #606266;
    }

    .cost-value {
      font-size: 24px;
      font-weight: 600;
      color: #303133;
    }
  }

  .cost-pie {
    .pie-item {
      margin-bottom: 12px;

      .pie-bar {
        height: 8px;
        border-radius: 4px;
        margin-bottom: 4px;
      }

      .pie-label {
        font-size: 12px;
        color: #909399;
      }

      &.material .pie-bar {
        background: #409EFF;
      }

      &.labor .pie-bar {
        background: #67C23A;
      }

      &.overhead .pie-bar {
        background: #E6A23C;
      }
    }
  }
}

@media (max-width: 768px) {
  .kpi-grid {
    grid-template-columns: 1fr;
  }
}
</style>
