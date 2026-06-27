<script setup lang="ts">
/**
 * Apr 24 2026 Plan C Phase 7+: dedicated gross margin analysis page.
 *
 * Reads fact_pos_item × agg_restaurant_product_cost (via name→source_pk lookup).
 * Computes per-dish revenue, food_cost, gross_profit, margin_rate.
 * Table sortable, date-range filterable, CSV exportable.
 */
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage } from 'element-plus';
import { Refresh, Download, DataAnalysis, TrendCharts, Money } from '@element-plus/icons-vue';
import { pythonFetch } from '@/api/smartbi/common';
import echarts from '@/utils/echarts';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);

const loading = ref(false);
const daysWindow = ref(30);

interface DishMargin {
  name: string;
  qty: number;
  revenue: number;
  foodCostUnit: number;
  totalCost: number;
  grossProfit: number;
  marginRate: number;
  bills: number;
  hasCost: boolean;
}

const totals = ref({ revenue: 0, profit: 0, rate: 0, dishCount: 0, withCost: 0, coverageRev: 0, profitWithEstimated: 0, rateWithEstimated: 0, industryDefault: 0.32 });
const showEstimated = ref(false);  // 加速 E: toggle 估算/精确版显示

// 默认营收覆盖目标 90% — 大多数商家录 top N 高营收菜品即可达成
// 未来可做成系统设置让客户按行业调整 (快餐 95%, 精品餐厅 85%)
const coverageTarget = 0.90;
const coverageColor = computed(() => {
  if (totals.value.coverageRev >= coverageTarget) return '#67c23a';  // 绿
  if (totals.value.coverageRev >= coverageTarget * 0.8) return '#e6a23c';  // 橙 (近目标)
  return '#909399';  // 灰 (起步阶段)
});
// 粗略估算还需录多少道菜 — 按已录菜覆盖的营收比例外推
const suggestedTopN = computed(() => {
  if (totals.value.withCost === 0 || totals.value.coverageRev === 0) return 30;
  const currentRate = totals.value.coverageRev / totals.value.withCost;  // 每道菜平均贡献营收比
  const need = Math.ceil((coverageTarget - totals.value.coverageRev) / currentRate);
  return Math.max(5, need);
});
const dishes = ref<DishMargin[]>([]);
const syncing = ref(false);

async function syncEtl() {
  syncing.value = true;
  try {
    const res = await pythonFetch('/api/smartbi/restaurant-ops/etl', { method: 'POST' }) as {
      success: boolean;
      data?: {
        dimIngredientUpserted: number;
        factRecipeUpserted: number;
        aggProductCostUpserted: number;
        errors: string[];
      };
      message?: string;
    };
    if (res.success && res.data) {
      const { dimIngredientUpserted = 0, factRecipeUpserted = 0, aggProductCostUpserted = 0 } = res.data;
      ElMessage.success(
        `同步完成: 食材 ${dimIngredientUpserted} / 配方 ${factRecipeUpserted} / 菜品成本 ${aggProductCostUpserted}`
      );
      // Signal sibling pages (menu-board BCG margin mode, store-comparison 毛利率列)
      // to invalidate their cached marginMap next time they render.
      try { sessionStorage.setItem('restaurantOps.marginDirty', String(Date.now())); } catch { /* ignore */ }
      await loadData();
    } else {
      ElMessage.error(res.message || '同步失败 — 请检查 Python 服务日志');
    }
  } catch (e) {
    console.error('[etl-sync] failed:', e);
    ElMessage.error('同步失败,请稍后重试');
  } finally {
    syncing.value = false;
  }
}

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    // Reuse /restaurant-ops/top-ingredients-style query via a chat-route trick:
    // build a scratch endpoint call. For now, pull from summary + call gross_margin
    // via chat stream once to hydrate. Simpler: call top-ingredients with a
    // synthetic kpi_kind, OR use the summary's margin block for totals and
    // derive per-dish from a dedicated /gross-margin API — let me add one.
    const res = await pythonFetch(`/api/smartbi/restaurant-ops/gross-margin?days=${daysWindow.value}`) as {
      success: boolean;
      data?: {
        totalRevenue: number;
        totalRevenueWithCost?: number;
        totalProfit: number;
        avgRate: number;
        // 加速 E: 估算毛利合并 (无配方菜按行业默认成本率)
        totalProfitWithEstimated?: number;
        avgRateWithEstimated?: number;
        industryDefaultCostRatio?: number;
        coverage?: { dishCount: number; totalDishCount: number; revenueRatio: number };
        dishes: Array<{
          name: string; qty: number; revenue: number; foodCostUnit: number;
          totalCost: number; grossProfit: number; marginRate: number; bills: number;
          hasCost: boolean; isEstimated?: boolean;
        }>;
      };
    };
    if (res.success && res.data) {
      totals.value.revenue = res.data.totalRevenue || 0;
      totals.value.profit = res.data.totalProfit || 0;
      totals.value.rate = res.data.avgRate || 0;
      totals.value.profitWithEstimated = res.data.totalProfitWithEstimated ?? res.data.totalProfit ?? 0;
      totals.value.rateWithEstimated = res.data.avgRateWithEstimated ?? res.data.avgRate ?? 0;
      totals.value.industryDefault = res.data.industryDefaultCostRatio ?? 0.32;
      dishes.value = res.data.dishes || [];
      totals.value.dishCount = res.data.coverage?.totalDishCount ?? dishes.value.length;
      totals.value.withCost = res.data.coverage?.dishCount ?? dishes.value.filter(d => d.hasCost).length;
      totals.value.coverageRev = res.data.coverage?.revenueRatio ?? 0;
    } else {
      ElMessage.warning('毛利数据加载为空 — 请检查是否已有 POS 销售 + 配方数据');
    }
  } catch (e) {
    console.error('[gross-margin] load failed:', e);
    ElMessage.error('毛利数据加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  loadData();
  setTimeout(renderCharts, 500);
});

function renderCharts() {
  const el = document.getElementById('chart-margin-scatter');
  if (!el || dishes.value.length === 0) return;
  const chart = echarts.getInstanceByDom(el) || echarts.init(el);
  const pts = dishes.value.filter(d => d.hasCost).map(d => ({
    value: [d.revenue, d.marginRate * 100],
    name: d.name,
  }));
  chart.setOption({
    tooltip: { trigger: 'item', confine: true, formatter: (p: Record<string, unknown>) => {
      const d = p.data as { name: string; value: number[] };
      return `<b>${d.name}</b><br/>营收: ¥${d.value[0].toFixed(2)}<br/>毛利率: ${d.value[1].toFixed(1)}%`;
    }},
    grid: { left: 60, right: 30, top: 20, bottom: 40 },
    xAxis: { name: '营收 (¥)', type: 'value', min: 0, axisLabel: { formatter: (v: number) => v >= 10000 ? (v / 10000).toFixed(1) + '万' : String(Math.round(v)) } },
    yAxis: { name: '毛利率 %', type: 'value', min: 0, max: 100, axisLabel: { formatter: '{value}%' } },
    series: [{
      type: 'scatter', data: pts, symbolSize: 14,
      itemStyle: { color: '#67c23a', opacity: 0.75 },
      label: { show: true, position: 'top', formatter: (p: Record<string, unknown>) => (p.data as { name: string }).name, fontSize: 11 },
    }],
  });
}

function handleAiAnalyze() {
  router.push({ path: '/smart-bi/query', query: { q: '哪道菜毛利最高 top 10, 分析哪些菜应该主推' } });
}

function exportCsv() {
  const header = '菜品,销量,营收,食材单位成本,总成本,毛利,毛利率,账单数,是否有配方';
  const rows = dishes.value.map(d =>
    [d.name, d.qty.toFixed(2), d.revenue.toFixed(2), d.foodCostUnit.toFixed(4),
     d.totalCost.toFixed(2), d.grossProfit.toFixed(2), (d.marginRate * 100).toFixed(1) + '%',
     d.bills, d.hasCost ? '是' : '否'].join(',')
  ).join('\n');
  const blob = new Blob(['﻿' + header + '\n' + rows], { type: 'text/csv;charset=utf-8;' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `毛利分析-${new Date().toISOString().slice(0, 10)}.csv`;
  a.click();
  URL.revokeObjectURL(a.href);
}

function marginRateTag(rate: number) {
  if (rate >= 0.6) return { label: '优秀', type: 'success' };
  if (rate >= 0.4) return { label: '良好', type: '' };
  if (rate >= 0.2) return { label: '偏低', type: 'warning' };
  return { label: '危险', type: 'danger' };
}
</script>

<template>
  <div class="margin-page">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">菜品毛利分析</span>
            <span class="data-count">{{ totals.dishCount }} 种菜品 ({{ totals.withCost }} 有配方)</span>
          </div>
          <div class="header-right">
            <el-select v-model="daysWindow" style="width:120px" @change="loadData">
              <el-option label="近 7 天" :value="7" />
              <el-option label="近 30 天" :value="30" />
              <el-option label="近 90 天" :value="90" />
              <el-option label="近 180 天" :value="180" />
              <el-option label="近 365 天" :value="365" />
            </el-select>
            <el-tooltip content="刚录完配方? 点此立即同步 Gold 毛利表 (无需等 1 小时 cron)" placement="top">
 <el-button type="success" plain :loading="syncing" @click="syncEtl"> 立即同步</el-button>
            </el-tooltip>
 <el-button type="info" plain @click="handleAiAnalyze"> AI 分析</el-button>
            <el-button :icon="Download" @click="exportCsv">导出 CSV</el-button>
            <el-button :icon="Refresh" @click="loadData">刷新</el-button>
          </div>
        </div>
      </template>

      <template v-if="canViewPrice">
      <!-- 加速 E: 估算/精确切换 toggle -->
      <div v-if="totals.dishCount > 0 && totals.withCost < totals.dishCount" class="estimate-toggle" style="margin-bottom: 12px; display: flex; align-items: center; gap: 12px; padding: 10px 14px; background: linear-gradient(135deg, #fff8e6, #ffeecc); border-radius: 6px; border: 1px solid #f0d8a0;">
 <span style="font-size: 13px; font-weight: 600"> 显示模式:</span>
        <el-radio-group v-model="showEstimated" size="small">
          <el-radio-button :value="false">仅精确 (有配方菜)</el-radio-button>
          <el-radio-button :value="true">含估算 (按行业默认 {{ (totals.industryDefault * 100).toFixed(0) }}% 成本率)</el-radio-button>
        </el-radio-group>
        <span style="font-size: 12px; color: #909399; flex: 1; text-align: right">
          精确版 {{ totals.withCost }} 道菜 ¥{{ totals.profit.toLocaleString('zh-CN', { maximumFractionDigits: 0 }) }} 毛利
          <span v-if="showEstimated"> · 含估算共 {{ totals.dishCount }} 道菜 ¥{{ totals.profitWithEstimated.toLocaleString('zh-CN', { maximumFractionDigits: 0 }) }}</span>
        </span>
      </div>

      <!-- 营收覆盖进度条 — 默认目标 90%, 引导用户意识到这是可操作的 KPI -->
      <el-card v-if="totals.dishCount > 0" class="coverage-card" shadow="never" style="margin-bottom: 12px">
        <div class="coverage-row">
          <div class="coverage-label">
            <span class="cov-title">营收覆盖率</span>
            <span class="cov-help">有配方菜 × POS 营收 ÷ 总营收</span>
          </div>
          <div class="coverage-progress">
            <el-progress
              :percentage="Math.min(100, Math.round(totals.coverageRev * 1000) / 10)"
              :stroke-width="14"
              :color="coverageColor"
              :format="() => `${(totals.coverageRev * 100).toFixed(1)}%`"
            />
          </div>
          <div class="coverage-target">
            <span>目标 {{ (coverageTarget * 100).toFixed(0) }}%</span>
 <span v-if="totals.coverageRev >= coverageTarget" class="cov-ok"> 已达标</span>
            <span v-else class="cov-gap">还差 {{ ((coverageTarget - totals.coverageRev) * 100).toFixed(1) }}%</span>
          </div>
        </div>
        <div v-if="totals.coverageRev < coverageTarget" class="coverage-hint">
 按 POS 营收从高到低录配方, 建议优先录营收 top {{ suggestedTopN }} 的未录菜品, 即可达 {{ (coverageTarget * 100).toFixed(0) }}% 目标. <el-link type="primary" underline="never" href="/restaurant/recipes">去配方管理</el-link>
        </div>
      </el-card>

      <!-- KPI 4 cards -->
      <el-row :gutter="16" class="stat-row" v-loading="loading">
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">总营收</span>
            <span class="stat-value">¥{{ totals.revenue.toLocaleString('zh-CN', { maximumFractionDigits: 0 }) }}</span>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">总毛利 <span class="stat-sub">{{ showEstimated ? '(精确+估算)' : '(仅精确)' }}</span></span>
            <span class="stat-value success">¥{{ (showEstimated ? totals.profitWithEstimated : totals.profit).toLocaleString('zh-CN', { maximumFractionDigits: 0 }) }}</span>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">平均毛利率 <span class="stat-sub">{{ showEstimated ? '(精确+估算)' : '(仅精确)' }}</span></span>
            <span class="stat-value" :class="{ success: (showEstimated ? totals.rateWithEstimated : totals.rate) >= 0.5, warning: (showEstimated ? totals.rateWithEstimated : totals.rate) < 0.3 && (showEstimated ? totals.rateWithEstimated : totals.rate) > 0 }">
              {{ ((showEstimated ? totals.rateWithEstimated : totals.rate) * 100).toFixed(1) }}%
            </span>
            <span v-if="totals.dishCount > 0" class="stat-footnote">
              <span v-if="!showEstimated">覆盖 {{ totals.withCost }}/{{ totals.dishCount }} 菜品 · {{ (totals.coverageRev * 100).toFixed(1) }}% 营收</span>
              <span v-else>{{ totals.dishCount }} 道菜 ({{ totals.withCost }} 精确, {{ totals.dishCount - totals.withCost }} 估算)</span>
            </span>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-item">
            <span class="stat-label">最赚菜品</span>
            <span class="stat-value">
              {{ dishes.length > 0 ? dishes.slice().sort((a,b) => b.grossProfit - a.grossProfit)[0].name : '—' }}
            </span>
          </div>
        </el-col>
      </el-row>

      <!-- Scatter: 营收 vs 毛利率 -->
      <div class="chart-section" v-if="dishes.length > 0">
        <div class="section-title">营收 × 毛利率 矩阵</div>
        <div id="chart-margin-scatter" style="height:320px" />
        <div class="chart-hint">
          右上区 (高营收 + 高毛利率) = 招牌赚钱菜, 重点推广; 左下区 (低营收 + 低毛利率) = 考虑下架或调整配方.
        </div>
      </div>

      <!-- Dish table -->
      <el-table :data="dishes" stripe border style="width:100%" empty-text="暂无毛利数据" :default-sort="{ prop: 'grossProfit', order: 'descending' }" v-loading="loading">
        <el-table-column prop="name" label="菜品" min-width="140" show-overflow-tooltip />
        <el-table-column prop="qty" label="销量" width="100" align="right" sortable>
          <template #default="{ row }">{{ row.qty.toFixed(2) }}</template>
        </el-table-column>
        <el-table-column prop="revenue" label="营收" width="120" align="right" sortable>
          <template #default="{ row }">¥{{ row.revenue.toFixed(2) }}</template>
        </el-table-column>
        <el-table-column prop="totalCost" label="食材成本" width="120" align="right" sortable>
          <template #default="{ row }">
            <span v-if="row.hasCost">¥{{ row.totalCost.toFixed(2) }}</span>
            <el-tag v-else type="info" size="small">无配方</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="grossProfit" label="毛利" width="120" align="right" sortable>
          <template #default="{ row }">
            <span :class="{ success: row.grossProfit > 0 && row.hasCost }">¥{{ row.grossProfit.toFixed(2) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="marginRate" label="毛利率" width="120" align="center" sortable>
          <template #default="{ row }">
            <span v-if="!row.hasCost">—</span>
            <el-tag v-else :type="(marginRateTag(row.marginRate) as any).type" size="small">
              {{ (row.marginRate * 100).toFixed(1) }}% {{ marginRateTag(row.marginRate).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="bills" label="账单数" width="80" align="center" />
      </el-table>

      <el-alert type="info" :closable="false" show-icon style="margin-top:20px">
        <template #title>数据说明</template>
        <div style="font-size:13px;line-height:1.8">
          <b>数据源</b>: POS 账单 (fact_pos_item) × 配方 BOM (agg_restaurant_product_cost) 跨模块 join.<br/>
          <b>计算公式</b>: 毛利 = 营收 - (销量 × 食材单位成本); 毛利率 = 毛利 / 营收.<br/>
          <b>"无配方" 的菜品</b>: 请到
          <el-button type="text" @click="router.push('/restaurant/recipes')">配方管理</el-button>
          录入, ETL 下一轮 (每小时) 自动重算.
        </div>
      </el-alert>
      </template>
      <el-empty v-else description="您没有查看价格/财务数据的权限" />
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.margin-page {
  padding: 16px;
  .page-card {
    :deep(.el-card__header) { padding: 14px 20px; border-bottom: 1px solid #ebeef5; }
  }
  .card-header {
    display: flex; justify-content: space-between; align-items: center;
    .header-left { display: flex; align-items: baseline; gap: 12px;
      .page-title { font-size: 16px; font-weight: 600; color: #303133; }
      .data-count { font-size: 13px; color: #909399; }
    }
    .header-right { display: flex; gap: 8px; align-items: center; }
  }
  .stat-row { margin: 10px 0 20px; }
  .stat-item {
    background: #fafafa; padding: 14px 16px; border-radius: 6px;
    display: flex; flex-direction: column; gap: 6px;
    .stat-label { font-size: 13px; color: #909399;
      .stat-sub { font-size: 11px; color: #c0c4cc; margin-left: 3px; }
    }
    .stat-value { font-size: 22px; font-weight: 600; color: #303133;
      &.success { color: #67c23a; }
      &.warning { color: #e6a23c; }
    }
    .stat-footnote { font-size: 11px; color: #909399; margin-top: 2px; }
  }
}
.coverage-card {
  border-radius: 6px;
  border: 1px solid #ebeef5;
  background: #fafbfc;
  :deep(.el-card__body) { padding: 14px 18px; }
  .coverage-row {
    display: flex; align-items: center; gap: 16px; flex-wrap: wrap;
  }
  .coverage-label {
    min-width: 140px;
    .cov-title { font-weight: 600; color: #303133; font-size: 14px; display: block; }
    .cov-help { font-size: 11px; color: #909399; }
  }
  .coverage-progress {
    flex: 1; min-width: 240px;
  }
  .coverage-target {
    font-size: 13px; color: #606266; display: flex; gap: 10px; align-items: center;
    .cov-ok { color: #67c23a; font-weight: 600; }
    .cov-gap { color: #e6a23c; font-weight: 600; }
  }
  .coverage-hint {
    margin-top: 10px; padding-top: 10px; border-top: 1px dashed #e4e7ed;
    font-size: 12px; color: #606266;
  }
}
.chart-section {
  padding: 12px 0 18px;
  .section-title { font-size: 14px; font-weight: 600; color: #303133; margin-bottom: 10px; }
  .chart-hint { font-size: 12px; color: #909399; margin-top: 8px; padding: 0 8px; }
}
.success { color: #67c23a; }
</style>
