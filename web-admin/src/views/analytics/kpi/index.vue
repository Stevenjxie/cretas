<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Refresh, TrendCharts, Histogram, Timer, Check, KnifeFork, DataAnalysis } from '@element-plus/icons-vue';
import { pythonFetch } from '@/api/smartbi/common';

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

async function loadRestaurantKpi() {
  if (!factoryId.value) return;
  loading.value = true;
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

async function loadKPIData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/reports/kpi`);
    if (response.success && response.data) {
      kpiData.value = { ...kpiData.value, ...response.data };
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

    <!-- Apr 24 P1.6: restaurant-specific KPI view (Plan C Gold) -->
    <div v-if="isRestaurant" class="kpi-grid" v-loading="loading">
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
            <el-button size="small" @click="router.push('/smart-bi/restaurant-v2')">餐饮综合分析 V2 →</el-button>
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
    <div v-else class="kpi-grid" v-loading="loading">
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
