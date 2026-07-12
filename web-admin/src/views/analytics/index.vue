<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { TrendCharts, DataAnalysis, Histogram, PieChart, Timer, Sunny, KnifeFork, Money, Goods, User, Box } from '@element-plus/icons-vue';
import { formatNumber } from '@/utils/format-number';
import { pythonFetch } from '@/api/smartbi/common';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);
// Apr 24 Plan C+: restaurant tenants get Gold ops dashboard instead of manufacturing cards
const isRestaurant = computed(() => authStore.factoryType === 'RESTAURANT');

const loading = ref(false);

// Restaurant-specific Gold ops summary data
const restaurantOps = ref({
  loading: false,
  requisitionCount: 0,
  requisitionQty: 0,
  requisitionCost: 0,
  wastageCount: 0,
  wastageCost: 0,
  stocktakingCount: 0,
  shortage: 0,
  activeDays: 0,
  top5Ingredients: [] as { name: string; category: string; cost: number }[],
  // Apr 24 Phase 7+: gross margin totals (POS × food_cost cross-module)
  marginRevenue: 0,
  marginProfit: 0,
  marginRate: 0,
  dishesWithCost: 0,
  totalDishes: 0,
});

async function loadRestaurantOps() {
  if (!factoryId.value) return;
  // 餐饮运营统计仅 RESTAURANT/CENTRAL_KITCHEN 租户有. 其他租户 agg 表空/不存在 → 500/404.
  // (authStore 已在 outer scope 定义, 复用)
  if (authStore.factoryType !== 'RESTAURANT' && authStore.factoryType !== 'CENTRAL_KITCHEN') {
    return;
  }
  restaurantOps.value.loading = true;
  try {
    const res = await pythonFetch('/api/smartbi/restaurant-ops/summary?days=30') as {
      success: boolean;
      data?: {
        totals?: {
          total_requisitions?: number;
          total_req_qty?: number;
          total_req_cost?: number;
          total_wastage?: number;
          total_wastage_cost?: number;
          total_stocktaking?: number;
          total_shortage?: number;
          active_days?: number;
        };
        top5_ingredients?: { name: string; category: string; cost: number }[];
      };
    };
    if (res.success && res.data) {
      // pythonFetch auto-transforms snake_case → camelCase, so read camelCase keys here
      const t = (res.data.totals || {}) as Record<string, number>;
      restaurantOps.value.requisitionCount = t.totalRequisitions || 0;
      restaurantOps.value.requisitionQty = t.totalReqQty || 0;
      restaurantOps.value.requisitionCost = t.totalReqCost || 0;
      restaurantOps.value.wastageCount = t.totalWastage || 0;
      restaurantOps.value.wastageCost = t.totalWastageCost || 0;
      restaurantOps.value.stocktakingCount = t.totalStocktaking || 0;
      restaurantOps.value.shortage = t.totalShortage || 0;
      restaurantOps.value.activeDays = t.activeDays || 0;
      // top5_ingredients → top5Ingredients by transformKeys
      restaurantOps.value.top5Ingredients = (res.data as Record<string, unknown>).top5Ingredients as { name: string; category: string; cost: number }[] || [];
      // margin totals (Phase 7+)
      const margin = ((res.data as Record<string, unknown>).margin || {}) as Record<string, number>;
      restaurantOps.value.marginRevenue = margin.totalPosRevenue || 0;
      restaurantOps.value.marginProfit = margin.totalGrossProfit || 0;
      restaurantOps.value.marginRate = margin.avgMarginRate || 0;
      restaurantOps.value.dishesWithCost = margin.dishCountWithCost || 0;
      restaurantOps.value.totalDishes = margin.totalDishCount || 0;
    }
  } catch (e) {
    console.error('[analytics/overview] restaurant ops load failed:', e);
  } finally {
    restaurantOps.value.loading = false;
  }
}

// 概览数据
const overviewData = ref({
  production: { todayOutput: 0, completedBatches: 0, inProgressBatches: 0, efficiency: 0 },
  quality: { totalInspections: 0, passRate: 0, pendingCount: 0 },
  warehouse: { totalMaterialBatches: 0, lowStockCount: 0, totalQuantity: 0 },
  equipment: { totalEquipments: 0, alertCount: 0, maintenanceCount: 0 },
  sales: { totalShipments: 0, pendingShipments: 0 },
  finance: { todayCost: 0, averageCost: 0 }
});

// 趋势数据
const trendData = ref({
  productionTrend: [] as { date: string; value: number }[],
  qualityTrend: [] as { date: string; value: number }[],
  costTrend: [] as { date: string; value: number }[]
});

onMounted(() => {
  if (isRestaurant.value) {
    // Skip the manufacturing dashboards entirely — they'd all return zeros
    // for a restaurant tenant and surface useless warnings. Load Gold ops.
    loadRestaurantOps();
  } else {
    loadOverviewData();
    loadTrendData();
  }
});

async function loadOverviewData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    // 加载各模块概览
    const [prodRes, qualRes, warehouseRes, equipRes] = await Promise.allSettled([
      get(`/${factoryId.value}/reports/dashboard/production`, { params: { period: 'today' } }),
      get(`/${factoryId.value}/reports/dashboard/quality`),
      get(`/${factoryId.value}/reports/dashboard/overview`),
      get(`/${factoryId.value}/reports/dashboard/equipment`)
    ]);

    const failed: string[] = [];
    if (prodRes.status === 'fulfilled' && prodRes.value.success) {
      overviewData.value.production = {
        ...overviewData.value.production,
        ...prodRes.value.data
      };
    } else { failed.push('生产'); }
    if (qualRes.status === 'fulfilled' && qualRes.value.success) {
      overviewData.value.quality = {
        ...overviewData.value.quality,
        ...qualRes.value.data
      };
    } else { failed.push('质量'); }
    if (warehouseRes.status === 'fulfilled' && warehouseRes.value.success) {
      const data = warehouseRes.value.data || {};
      overviewData.value.warehouse.totalMaterialBatches = data.materialBatches || 0;
      overviewData.value.warehouse.lowStockCount = data.lowStockMaterials || 0;
    } else { failed.push('仓储'); }
    if (equipRes.status === 'fulfilled' && equipRes.value.success) {
      overviewData.value.equipment = {
        ...overviewData.value.equipment,
        ...equipRes.value.data
      };
    } else { failed.push('设备'); }
    // R18-ext #282 fix: when some/all endpoints fail, surface the failure
    // instead of silently showing zeros (prev behavior made zeros look like
    // real data).
    if (failed.length > 0) {
      ElMessage.warning(`概览数据部分加载失败: ${failed.join('、')}. 相关指标可能为占位 0, 请刷新重试`);
    }
  } catch (error) {
    console.error('加载概览数据失败:', error);
    ElMessage.error('加载概览数据失败，请稍后重试');
  } finally {
    loading.value = false;
  }
}

async function loadTrendData() {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/reports/dashboard/trends`, {
      params: { period: 'week' }
    });
    if (response.success && response.data) {
      trendData.value = response.data;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载趋势数据失败');
    }
  } catch (error) {
    console.error('加载趋势数据失败:', error);
    ElMessage.error('加载趋势数据失败，请稍后重试');
  }
}

// formatNumber imported from @/utils/format-number

// R18-ext #284 fix: backend passRate is already a percentage value
// (e.g. 68.33 for 68.33%), not a decimal (0.6833). Multiplying by 100
// produced "6833.0%" in the UI.
function formatPercent(num: number): string {
  if (num == null || isNaN(num)) return '0.0%';
  return Number(num).toFixed(1) + '%';
}
</script>

<template>
  <div class="analytics-page">
    <div class="page-header">
      <h1>
        <el-icon><DataAnalysis /></el-icon>
        数据分析中心
      </h1>
      <p class="subtitle">{{ isRestaurant ? '餐饮日常运营数据概览 (Gold)' : '全局数据概览与趋势分析' }}</p>
    </div>

    <!-- Restaurant tenant: Gold ops dashboard (Plan C Phase 1-5) -->
    <template v-if="isRestaurant">
      <el-row :gutter="16" class="overview-cards" v-loading="restaurantOps.loading">
        <el-col :xs="24" :sm="12" :md="8" :lg="4">
          <el-card class="stat-card production" shadow="hover">
            <div class="card-header"><el-icon class="module-icon"><KnifeFork /></el-icon><span>领料</span></div>
            <div class="stat-value">{{ formatNumber(restaurantOps.requisitionCount) }}</div>
            <div class="stat-label">近30天领料单</div>
            <div class="stat-footer"><span>{{ formatNumber(restaurantOps.requisitionQty, 1) }} 单位</span></div>
          </el-card>
        </el-col>
        <el-col v-if="canViewPrice" :xs="24" :sm="12" :md="8" :lg="4">
          <el-card class="stat-card quality" shadow="hover">
            <div class="card-header"><el-icon class="module-icon"><Sunny /></el-icon><span>损耗</span></div>
            <div class="stat-value">¥{{ formatNumber(restaurantOps.wastageCost, 0) }}</div>
            <div class="stat-label">近30天损耗金额</div>
            <div class="stat-footer"><span>{{ restaurantOps.wastageCount }} 次记录</span></div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="8" :lg="4">
          <el-card class="stat-card warehouse" shadow="hover">
            <div class="card-header"><el-icon class="module-icon"><Box /></el-icon><span>盘点</span></div>
            <div class="stat-value">{{ formatNumber(restaurantOps.shortage, 1) }}</div>
            <div class="stat-label">近30天盘亏量</div>
            <div class="stat-footer"><span>{{ restaurantOps.stocktakingCount }} 次盘点</span></div>
          </el-card>
        </el-col>
        <el-col v-if="canViewPrice" :xs="24" :sm="12" :md="8" :lg="4">
          <el-card class="stat-card sales" shadow="hover">
            <div class="card-header"><el-icon class="module-icon"><Money /></el-icon><span>食材成本</span></div>
            <div class="stat-value">¥{{ formatNumber(restaurantOps.requisitionCost, 0) }}</div>
            <div class="stat-label">近30天估算</div>
            <div class="stat-footer"><span>{{ restaurantOps.activeDays }} 天有活动</span></div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="8" :lg="4">
          <el-card class="stat-card finance" shadow="hover">
            <div class="card-header"><el-icon class="module-icon"><Histogram /></el-icon><span>Top 食材</span></div>
            <div class="stat-value" style="font-size:18px">{{ restaurantOps.top5Ingredients[0]?.name || '—' }}</div>
            <div class="stat-label">消耗最多</div>
            <div class="stat-footer"><span>¥{{ formatNumber(restaurantOps.top5Ingredients[0]?.cost || 0, 0) }}</span></div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="8" :lg="4">
          <el-card class="stat-card equipment" shadow="hover" @click="$router.push('/smart-bi/query?q=最近30天领料最多的食材和损耗情况')">
            <div class="card-header"><el-icon class="module-icon"><DataAnalysis /></el-icon><span>AI 深度分析</span></div>
 <div class="stat-value" style="font-size:18px;cursor:pointer"> 点击</div>
            <div class="stat-label">问 AI 领料/损耗/盘点</div>
            <div class="stat-footer"><span style="color:#409eff">基于 Gold 数据秒回</span></div>
          </el-card>
        </el-col>
      </el-row>

      <!-- Gross margin row (Phase 7+: POS × food_cost) -->
      <el-row v-if="restaurantOps.marginRevenue > 0" :gutter="16" class="overview-cards" style="margin-top:12px">
        <el-col v-if="canViewPrice" :xs="24" :sm="12" :md="8" :lg="4">
          <el-card class="stat-card sales" shadow="hover">
            <div class="card-header"><el-icon class="module-icon"><Money /></el-icon><span>POS 营收</span></div>
            <div class="stat-value">¥{{ formatNumber(restaurantOps.marginRevenue, 0) }}</div>
            <div class="stat-label">近30天</div>
            <div class="stat-footer"><span>{{ restaurantOps.totalDishes }} 种菜品</span></div>
          </el-card>
        </el-col>
        <el-col v-if="canViewPrice" :xs="24" :sm="12" :md="8" :lg="4">
          <el-card class="stat-card production" shadow="hover">
            <div class="card-header"><el-icon class="module-icon"><TrendCharts /></el-icon><span>毛利</span></div>
            <div class="stat-value success">¥{{ formatNumber(restaurantOps.marginProfit, 0) }}</div>
            <div class="stat-label">近30天</div>
            <div class="stat-footer"><span>毛利率 {{ (restaurantOps.marginRate * 100).toFixed(1) }}%</span></div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="8" :lg="4">
          <el-card class="stat-card warehouse" shadow="hover">
            <div class="card-header"><el-icon class="module-icon"><PieChart /></el-icon><span>毛利率</span></div>
            <div class="stat-value" :class="{ 'success': restaurantOps.marginRate >= 0.5, 'warning': restaurantOps.marginRate < 0.3 }">
              {{ (restaurantOps.marginRate * 100).toFixed(1) }}%
            </div>
            <div class="stat-label">平均</div>
            <div class="stat-footer"><span>{{ restaurantOps.dishesWithCost }}/{{ restaurantOps.totalDishes }} 菜有配方</span></div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="16" :lg="12">
          <el-card class="stat-card finance" shadow="hover" @click="$router.push('/restaurant/analytics/gross-margin')">
            <div class="card-header" style="display:flex;align-items:center;gap:8px">
              <el-icon><DataAnalysis /></el-icon><span>毛利深度分析</span>
              <span style="margin-left:auto;font-size:12px;color:#409eff;cursor:pointer">查看专属页面 →</span>
            </div>
            <div style="font-size:13px;color:#606266;line-height:1.8;padding:6px 0">
              跨模块分析: POS 销售 × 配方成本 = 每道菜毛利. 可按时段筛选, 按毛利率排序识别"赚钱菜" vs"负担菜".
            </div>
            <div style="display:flex;gap:8px">
              <el-button size="small" type="primary" @click.stop="$router.push('/restaurant/analytics/gross-margin')">查看毛利排行</el-button>
 <el-button size="small" @click.stop="$router.push('/smart-bi/query?q=哪道菜毛利最高 top 10')"> AI 分析</el-button>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <!-- Top 5 ingredients list -->
      <el-card class="top5-card" shadow="never" v-if="restaurantOps.top5Ingredients.length > 0" style="margin-top:16px">
        <template #header>
          <span style="font-size:14px;font-weight:600">食材消耗 Top 5 (近30天)</span>
        </template>
        <el-table :data="restaurantOps.top5Ingredients" size="small" :show-header="true">
          <el-table-column type="index" width="60" label="排名" />
          <el-table-column prop="name" label="食材" />
          <el-table-column prop="category" label="类别" />
          <el-table-column v-if="canViewPrice" label="估算成本">
            <template #default="{ row }">¥{{ formatNumber(row.cost, 2) }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-alert type="info" :closable="false" style="margin-top:20px" show-icon>
        <template #title>关于本页面</template>
        <div style="font-size:13px">
          数据来自 <b>Gold 运营层聚合</b> (agg_restaurant_daily_*). 每次打开自动加载近 30 天.
 如需自定义问答, 点顶部 " AI 深度分析" 卡片或侧边栏 "AI 问答".
          生产/质量/仓储等制造业模块对餐饮租户已隐藏 — 本页只显示餐饮日常指标.
        </div>
      </el-alert>
    </template>

    <!-- Factory tenant: manufacturing overview (original flow) -->
    <el-row v-else :gutter="16" class="overview-cards" v-loading="loading">
      <!-- 生产模块 -->
      <el-col :xs="24" :sm="12" :md="8" :lg="4">
        <el-card class="stat-card production" shadow="hover">
          <div class="card-header">
            <el-icon class="module-icon"><TrendCharts /></el-icon>
            <span>生产</span>
          </div>
          <div class="stat-value">{{ formatNumber(overviewData.production.todayOutput) }}</div>
          <div class="stat-label">今日产量</div>
          <div class="stat-footer">
            <span>完成批次: {{ overviewData.production.completedBatches }}</span>
          </div>
        </el-card>
      </el-col>

      <!-- 质量模块 -->
      <el-col :xs="24" :sm="12" :md="8" :lg="4">
        <el-card class="stat-card quality" shadow="hover">
          <div class="card-header">
            <el-icon class="module-icon"><Sunny /></el-icon>
            <span>质量</span>
          </div>
          <div class="stat-value">{{ formatPercent(overviewData.quality.passRate) }}</div>
          <div class="stat-label">合格率</div>
          <div class="stat-footer">
            <span>待检: {{ overviewData.quality.pendingCount }}</span>
          </div>
        </el-card>
      </el-col>

      <!-- 仓储模块 -->
      <el-col :xs="24" :sm="12" :md="8" :lg="4">
        <el-card class="stat-card warehouse" shadow="hover">
          <div class="card-header">
            <el-icon class="module-icon"><Histogram /></el-icon>
            <span>仓储</span>
          </div>
          <div class="stat-value">{{ formatNumber(overviewData.warehouse.totalMaterialBatches) }}</div>
          <div class="stat-label">原料批次</div>
          <div class="stat-footer">
            <span :class="{ 'text-warning': overviewData.warehouse.lowStockCount > 0 }">
              低库存: {{ overviewData.warehouse.lowStockCount }}
            </span>
          </div>
        </el-card>
      </el-col>

      <!-- 设备模块 -->
      <el-col :xs="24" :sm="12" :md="8" :lg="4">
        <el-card class="stat-card equipment" shadow="hover">
          <div class="card-header">
            <el-icon class="module-icon"><Timer /></el-icon>
            <span>设备</span>
          </div>
          <div class="stat-value">{{ overviewData.equipment.totalEquipments }}</div>
          <div class="stat-label">设备总数</div>
          <div class="stat-footer">
            <span :class="{ 'text-danger': overviewData.equipment.alertCount > 0 }">
              告警: {{ overviewData.equipment.alertCount }}
            </span>
          </div>
        </el-card>
      </el-col>

      <!-- 销售模块 -->
      <el-col :xs="24" :sm="12" :md="8" :lg="4">
        <el-card class="stat-card sales" shadow="hover">
          <div class="card-header">
            <el-icon class="module-icon"><PieChart /></el-icon>
            <span>销售</span>
          </div>
          <div class="stat-value">{{ formatNumber(overviewData.sales.totalShipments) }}</div>
          <div class="stat-label">出货记录</div>
          <div class="stat-footer">
            <span>待发货: {{ overviewData.sales.pendingShipments }}</span>
          </div>
        </el-card>
      </el-col>

      <!-- 财务模块 -->
      <el-col :xs="24" :sm="12" :md="8" :lg="4">
        <el-card class="stat-card finance" shadow="hover">
          <div class="card-header">
            <el-icon class="module-icon"><DataAnalysis /></el-icon>
            <span>成本</span>
          </div>
          <div class="stat-value">{{ formatNumber(overviewData.finance.todayCost) }}</div>
          <div class="stat-label">今日成本</div>
          <div class="stat-footer">
            <span>平均: {{ formatNumber(overviewData.finance.averageCost) }}</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 快捷导航 -->
    <el-row :gutter="16" class="quick-nav">
      <el-col :span="8">
        <router-link to="/analytics/trends" class="nav-card">
          <el-card shadow="hover">
            <el-icon size="32"><TrendCharts /></el-icon>
            <h3>趋势分析</h3>
            <p>查看各模块历史趋势和数据变化</p>
          </el-card>
        </router-link>
      </el-col>
      <el-col :span="8">
        <router-link to="/analytics/kpi" class="nav-card">
          <el-card shadow="hover">
            <el-icon size="32"><Histogram /></el-icon>
            <h3>KPI看板</h3>
            <p>关键绩效指标实时监控</p>
          </el-card>
        </router-link>
      </el-col>
    </el-row>
  </div>
</template>

<style lang="scss" scoped>
.analytics-page {
  padding: 20px;
}

.page-header {
  margin-bottom: 24px;

  h1 {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 24px;
    font-weight: 600;
    color: #303133;
    margin: 0 0 8px;
  }

  .subtitle {
    color: #909399;
    font-size: 14px;
    margin: 0;
  }
}

.overview-cards {
  margin-bottom: 24px;
}

.stat-card {
  text-align: center;
  margin-bottom: 16px;
  border-radius: 12px;
  transition: all var(--transition-fast, 0.2s ease);

  &:hover {
    transform: translateY(-4px);
  }

  .card-header {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    margin-bottom: 12px;
    color: #606266;
    font-size: 14px;
  }

  .module-icon {
    font-size: 18px;
  }

  .stat-value {
    font-size: 28px;
    font-weight: 700;
    color: #303133;
    line-height: 1.2;
    font-variant-numeric: tabular-nums;
  }

  .stat-label {
    font-size: 12px;
    color: #909399;
    margin-top: 4px;
  }

  .stat-footer {
    margin-top: 12px;
    padding-top: 12px;
    border-top: 1px solid #ebeef5;
    font-size: 12px;
    color: #606266;
  }

  &.production {
    border-top: 3px solid #409EFF;
    .stat-value { color: #409EFF; }
  }

  &.quality {
    border-top: 3px solid #67C23A;
    .stat-value { color: #67C23A; }
  }

  &.warehouse {
    border-top: 3px solid #E6A23C;
    .stat-value { color: #E6A23C; }
  }

  &.equipment {
    border-top: 3px solid #909399;
    .stat-value { color: #909399; }
  }

  &.sales {
    border-top: 3px solid #9B59B6;
    .stat-value { color: #9B59B6; }
  }

  &.finance {
    border-top: 3px solid #E74C3C;
    .stat-value { color: #E74C3C; }
  }
}

.text-warning {
  color: #E6A23C !important;
}

.text-danger {
  color: #F56C6C !important;
}

.quick-nav {
  margin-top: 24px;

  .nav-card {
    text-decoration: none;
    display: block;

    .el-card {
      text-align: center;
      padding: 24px;
      cursor: pointer;
      transition: all var(--transition-base, 0.3s ease);
      border-radius: 12px;

      &:hover {
        transform: translateY(-4px);
        box-shadow: var(--shadow-xl);
      }

      .el-icon {
        color: #409EFF;
        margin-bottom: 12px;
      }

      h3 {
        margin: 0 0 8px;
        font-size: 16px;
        color: #303133;
      }

      p {
        margin: 0;
        font-size: 13px;
        color: #909399;
      }
    }
  }
}
</style>
