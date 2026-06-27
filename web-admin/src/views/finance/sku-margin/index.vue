<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import type { ECharts } from 'echarts/core';
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight';
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue';
import { useChartInsight } from '@/composables/useChartInsight';

// ---------- auth ----------
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);

// ---------- state ----------
const loading = ref(false);
const tableData = ref<SkuMarginRow[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });
const dateRange = ref<[string, string] | null>(null);
const sortField = ref('marginRate');
const sortOrder = ref<'ascending' | 'descending'>('descending');
// R76: 当 batch 数据存在但成本/售价 API 未接入时, 显示通知 banner 而不是用 Math.random() 编造假数据
const noCostDataNotice = ref({ show: false, batchProductCount: 0 });

// Chart
const chartRef = ref<HTMLDivElement | null>(null);
let chartInstance: ECharts | null = null;

// ---------- types ----------
interface SkuMarginRow {
  productName: string;
  quantity: number;
  materialCost: number;
  laborCost: number;
  totalCost: number;
  unitCost: number;
  sellingPrice: number;
  marginRate: number;
}

// ---------- KPI ----------
const kpi = computed(() => {
  const rows = tableData.value;
  const total = rows.length;
  if (total === 0) {
    return {
      avgMargin: 0,
      bestSku: { name: '-', rate: 0 },
      worstSku: { name: '-', rate: 0 },
      skuCount: 0,
    };
  }

  const rates = rows.map(r => r.marginRate);
  const avg = rates.reduce((a, b) => a + b, 0) / rates.length;

  const sorted = [...rows].sort((a, b) => b.marginRate - a.marginRate);
  const best = sorted[0];
  const worst = sorted[sorted.length - 1];

  return {
    avgMargin: Math.round(avg * 10) / 10,
    bestSku: { name: best.productName, rate: Math.round(best.marginRate * 10) / 10 },
    worstSku: { name: worst.productName, rate: Math.round(worst.marginRate * 10) / 10 },
    skuCount: total,
  };
});

function getMarginColor(rate: number): string {
  if (rate >= 30) return '#67C23A';
  if (rate >= 15) return '#E6A23C';
  return '#F56C6C';
}

// ---------- chart ----------
function initChart() {
  if (!chartRef.value) return;

  if (chartInstance && !chartInstance.isDisposed?.()) {
    chartInstance.dispose();
  }

  chartInstance = echarts.init(chartRef.value, 'cretas');

  const top10 = [...tableData.value]
    .sort((a, b) => b.marginRate - a.marginRate)
    .slice(0, 10)
    .reverse(); // reverse so highest is at top of horizontal bar

  if (top10.length === 0) {
    chartInstance.setOption({
      title: { text: '暂无数据', left: 'center', top: 'center', textStyle: { color: '#909399', fontSize: 14 } },
    });
    return;
  }

  const names = top10.map(r => r.productName);
  const rates = top10.map(r => Math.round(r.marginRate * 10) / 10);

  chartInstance.setOption({
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: { name: string; value: number }[]) => {
        if (!Array.isArray(params) || params.length === 0) return '';
        const p = params[0];
        return `${p.name}<br/>毛利率: ${p.value}%`;
      },
    },
    grid: { left: 140, right: 40, top: 10, bottom: 30 },
    xAxis: {
      type: 'value',
      axisLabel: { formatter: '{value}%' },
    },
    yAxis: {
      type: 'category',
      data: names,
      axisLabel: {
        width: 120,
        overflow: 'truncate',
        fontSize: 12,
      },
    },
    series: [
      {
        type: 'bar',
        data: rates.map(r => ({
          value: r,
          itemStyle: {
            color: getMarginColor(r),
            borderRadius: [0, 4, 4, 0],
          },
        })),
        barMaxWidth: 28,
        label: {
          show: true,
          position: 'right',
          formatter: '{c}%',
          fontSize: 11,
        },
      },
    ],
  });
}

function handleResize() {
  chartInstance?.resize();
}

onMounted(() => {
  loadData();
  window.addEventListener('resize', handleResize);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize);
  if (chartInstance && !chartInstance.isDisposed?.()) {
    chartInstance.dispose();
  }
  chartInstance = null;
});

// Re-draw chart when data changes
watch(tableData, async () => {
  await nextTick();
  initChart();
});

// ---------- data loading ----------
async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  noCostDataNotice.value = { show: false, batchProductCount: 0 };
  try {
    // #592 Finding 1: AI intent path can't plumb date/customer filters.
    // If user has any filter set, skip AI intent and go straight to fallback
    // (which honors startDate/endDate). Prevents silently-ignored filters.
    const hasFilters = !!(dateRange.value && dateRange.value[0]);
    if (hasFilters) {
      await loadFromBatches();
      return;
    }

    // Try the AI intent API for aggregated SKU margin data
    const intentRes = await post<{
      status?: string;
      data?: Record<string, unknown>;
      result?: Record<string, unknown>;
    }>(`/${factoryId.value}/ai-intents/execute`, {
      userInput: '查询SKU毛利率排名',
    });

    // #592 Finding 3: surface NEED_CLARIFICATION instead of silently falling
    // through to fallback. User sees an explicit hint so they know the AI
    // intent path is uncertain; behavior still falls through to batches data.
    if (intentRes.success && intentRes.data?.status === 'NEED_CLARIFICATION') {
      ElMessage.info('AI 意图识别置信度偏低, 已切换到批次聚合视图');
      await loadFromBatches();
      return;
    }

    if (intentRes.success && intentRes.data) {
      const payload = intentRes.data.data || intentRes.data.result || intentRes.data;
      if (payload && typeof payload === 'object') {
        const items = extractSkuData(payload);
        if (items.length > 0) {
          tableData.value = items;
          pagination.value.total = items.length;
          return;
        }
      }
    }

    // Fallback: load from processing batches and build margin data
    await loadFromBatches();
  } catch (error) {
    console.error('Failed to load SKU margin data:', error);
    // Fallback to batch-based loading; if that also fails, show empty
    // state rather than fake demo dishes which users would mistake for
    // their own data (Apr 21 2026).
    try {
      await loadFromBatches();
    } catch {
      tableData.value = [];
      pagination.value.total = 0;
    }
  } finally {
    loading.value = false;
  }
}

function extractSkuData(payload: Record<string, unknown>): SkuMarginRow[] {
  // Try to extract data from various response shapes
  const candidates = [payload.items, payload.records, payload.content, payload.list, payload.skuList];
  for (const candidate of candidates) {
    if (Array.isArray(candidate) && candidate.length > 0) {
      return candidate.map((item: Record<string, unknown>) => ({
        productName: String(item.productName || item.name || item.skuName || '-'),
        quantity: Number(item.quantity || item.count || 0),
        materialCost: Number(item.materialCost || 0),
        laborCost: Number(item.laborCost || 0),
        totalCost: Number(item.totalCost || item.cost || 0),
        unitCost: Number(item.unitCost || 0),
        sellingPrice: Number(item.sellingPrice || item.price || 0),
        marginRate: Number(item.marginRate || item.margin || item.grossMargin || 0),
      }));
    }
  }
  return [];
}

async function loadFromBatches() {
  if (!factoryId.value) return;

  const params: Record<string, unknown> = {
    page: 1,
    size: 200,
    status: 'COMPLETED',
  };
  if (dateRange.value && dateRange.value[0]) {
    params.startDate = dateRange.value[0];
    params.endDate = dateRange.value[1];
  }

  const response = await get<{
    content: Array<{
      id: number;
      productTypeName?: string;
      productName?: string;
      actualQuantity?: number;
      plannedQuantity?: number;
    }>;
    totalElements: number;
  }>(`/${factoryId.value}/processing/batches`, { params });

  if (response.success && response.data) {
    const batches = response.data.content || [];
    if (batches.length === 0) {
      // New factory / no batches: show empty state, not fake demo dishes
      tableData.value = [];
      pagination.value.total = 0;
      return;
    }

    // Aggregate by product name (real production data)
    const productMap = new Map<string, { quantity: number; batchCount: number }>();
    for (const batch of batches) {
      const name = batch.productTypeName || batch.productName || '未知产品';
      const existing = productMap.get(name) || { quantity: 0, batchCount: 0 };
      existing.quantity += batch.actualQuantity || batch.plannedQuantity || 0;
      existing.batchCount += 1;
      productMap.set(name, existing);
    }

    // R76: 之前此处用 Math.random() 编造材料成本 / 人工成本 / 售价 / 毛利率 — 已移除。
    // 真实 SKU 毛利率 API (AI intent 'sku-margin-ranking' 或专属端点) 接入前,
    // 不展示编造的成本数据, 改为显示通知 banner 告知用户数据限制。
    // 主路径 (AI intent at line 173-191) 若返回真实数据会优先用,
    // 走到 fallback 只能说"找到 N 个产品但成本数据待接入"。
    tableData.value = [];
    pagination.value.total = 0;
    noCostDataNotice.value = { show: true, batchProductCount: productMap.size };
  } else {
    tableData.value = [];
    pagination.value.total = 0;
  }
}

// ---------- helpers ----------
function formatMoney(val: number | null | undefined): string {
  if (val == null) return '¥0.00';
  const n = Number(val);
  return isNaN(n) ? '¥0.00' : '¥' + n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function getMarginTagType(rate: number): string {
  if (rate >= 30) return 'success';
  if (rate >= 15) return 'warning';
  return 'danger';
}

// ---------- pagination & sorting ----------
const paginatedData = computed(() => {
  let sorted = [...tableData.value];

  // Apply sort
  if (sortField.value) {
    sorted.sort((a, b) => {
      const aVal = a[sortField.value as keyof SkuMarginRow] ?? 0;
      const bVal = b[sortField.value as keyof SkuMarginRow] ?? 0;
      const cmp = Number(aVal) - Number(bVal);
      return sortOrder.value === 'descending' ? -cmp : cmp;
    });
  }

  // Apply pagination
  const start = (pagination.value.page - 1) * pagination.value.size;
  return sorted.slice(start, start + pagination.value.size);
});

function handleSortChange({ prop, order }: { prop: string; order: string | null }) {
  sortField.value = prop || 'marginRate';
  sortOrder.value = (order as 'ascending' | 'descending') || 'descending';
}

function handlePageChange(page: number) {
  pagination.value.page = page;
}

function handleSizeChange(size: number) {
  pagination.value.size = size;
  pagination.value.page = 1;
}

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleRefresh() {
  dateRange.value = null;
  pagination.value.page = 1;
  loadData();
}

// ==================== Chart Insight ====================
// Horizontal BAR chart: top-10 SKUs ranked by margin rate (RANKING family, domain='finance').
// Source getter mirrors what initChart() renders — top 10 sorted desc, displayed asc.
// We pass them desc (highest first) so RANKING engine correctly identifies top/bottom.
const skuMarginSource = (): { chart: ChartWithMeta } | null => {
  const top10 = [...tableData.value]
    .sort((a, b) => b.marginRate - a.marginRate)
    .slice(0, 10);
  if (top10.length < 2) return null;
  return {
    chart: {
      chartType: 'BAR',
      meta: { xDim: 'category', yMetric: 'margin', aggregation: 'sum', domain: 'finance' },
      config: {
        xAxis: { data: top10.map((r) => r.productName) },
        series: [{ type: 'bar', data: top10.map((r) => r.marginRate) }],
      },
    },
  };
};

const { insight: skuMarginInsight, loading: skuMarginInsightLoading } = useChartInsight(
  skuMarginSource,
  () => ({ canViewFinance: canViewPrice.value }),
  { factoryId: () => factoryId.value ?? '', autoTier2: true },
);
</script>

<template>
  <div class="page-wrapper" v-loading="loading">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">SKU毛利率分析</span>
            <span class="data-count">共 {{ pagination.total }} 个SKU</span>
          </div>
          <div class="header-right">
            <el-date-picker
              v-model="dateRange"
              type="daterange"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              value-format="YYYY-MM-DD"
              style="width: 280px"
              :shortcuts="[
                { text: '最近一周', value: () => { const e = new Date(); const s = new Date(); s.setDate(s.getDate() - 7); return [s, e]; } },
                { text: '最近一月', value: () => { const e = new Date(); const s = new Date(); s.setMonth(s.getMonth() - 1); return [s, e]; } },
                { text: '最近三月', value: () => { const e = new Date(); const s = new Date(); s.setMonth(s.getMonth() - 3); return [s, e]; } },
              ]"
              @change="handleSearch"
            />
            <el-button :icon="Refresh" @click="handleRefresh">刷新</el-button>
          </div>
        </div>
      </template>

      <template v-if="canViewPrice">
      <!-- R76: 提示成本/售价数据未接入 — 之前用 Math.random 编造, 已移除 -->
      <el-alert
        v-if="noCostDataNotice.show"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom: 16px;"
      >
        <template #title>
          数据待接入: 检测到 {{ noCostDataNotice.batchProductCount }} 个生产产品,
          但 SKU 成本/售价 API 尚未接入
        </template>
        <template #default>
          <div style="color: #606266; font-size: 13px; line-height: 1.6;">
            为避免误导,毛利率明细暂不展示估算数据。后端 SKU 毛利率聚合接口接入后将自动展示真实数据。
            如需手工查看, 请前往
            <el-link type="primary" underline="hover" @click="$router.push('/finance/costs')">财务成本</el-link>
            或
            <el-link type="primary" underline="hover" @click="$router.push('/smart-bi/finance')">SmartBI 财务分析</el-link>。
          </div>
        </template>
      </el-alert>

      <!-- KPI Row (隐藏 when 成本/售价数据未接入,避免 0% 红色误导) -->
      <div v-if="!noCostDataNotice.show" class="kpi-row">
        <el-card shadow="hover" class="kpi-card">
          <div class="kpi-value" :style="{ color: getMarginColor(kpi.avgMargin) }">
            {{ kpi.avgMargin }}%
          </div>
          <div class="kpi-label">平均毛利率</div>
        </el-card>
        <el-card shadow="hover" class="kpi-card best">
          <div class="kpi-value" style="color: #67C23A">
            {{ kpi.bestSku.rate }}%
          </div>
          <div class="kpi-label">最高毛利SKU</div>
          <div class="kpi-sub">{{ kpi.bestSku.name }}</div>
        </el-card>
        <el-card shadow="hover" class="kpi-card worst">
          <div class="kpi-value" style="color: #F56C6C">
            {{ kpi.worstSku.rate }}%
          </div>
          <div class="kpi-label">最低毛利SKU</div>
          <div class="kpi-sub">{{ kpi.worstSku.name }}</div>
        </el-card>
        <el-card shadow="hover" class="kpi-card">
          <div class="kpi-value" style="color: #409EFF">
            {{ kpi.skuCount }}
          </div>
          <div class="kpi-label">SKU数量</div>
        </el-card>
      </div>

      <!-- Bar Chart: Top 10 SKUs by Margin (隐藏 when 数据未接入) -->
      <div v-if="!noCostDataNotice.show" class="chart-section">
        <h3 class="section-title">Top 10 SKU 毛利率排名</h3>
        <div ref="chartRef" class="chart-container"></div>
        <!-- U6: useChartInsight — Tier1 instant, Tier2 auto on null (飞轮接通) -->
        <ChartInsight :insight="skuMarginInsight" :loading="skuMarginInsightLoading" depth="detailed" />
      </div>

      <!-- Main Table (隐藏 when 数据未接入) -->
      <div v-if="!noCostDataNotice.show" class="table-section">
        <h3 class="section-title">SKU 毛利率明细</h3>
        <el-table
          :data="paginatedData"
          stripe
          border
          style="width: 100%"
          :default-sort="{ prop: 'marginRate', order: 'descending' }"
          @sort-change="handleSortChange"
          empty-text="暂无SKU毛利率数据"
        >
          <el-table-column prop="productName" label="产品名称" min-width="150" show-overflow-tooltip />
          <el-table-column prop="quantity" label="产量" width="100" align="right" sortable="custom">
            <template #default="{ row }">
              {{ row.quantity.toLocaleString() }}
            </template>
          </el-table-column>
          <el-table-column prop="materialCost" label="物料成本" width="130" align="right" sortable="custom">
            <template #default="{ row }">
              {{ formatMoney(row.materialCost) }}
            </template>
          </el-table-column>
          <el-table-column prop="laborCost" label="人工成本" width="120" align="right" sortable="custom">
            <template #default="{ row }">
              {{ formatMoney(row.laborCost) }}
            </template>
          </el-table-column>
          <el-table-column prop="totalCost" label="总成本" width="130" align="right" sortable="custom">
            <template #default="{ row }">
              {{ formatMoney(row.totalCost) }}
            </template>
          </el-table-column>
          <el-table-column prop="unitCost" label="单位成本" width="110" align="right" sortable="custom">
            <template #default="{ row }">
              {{ formatMoney(row.unitCost) }}
            </template>
          </el-table-column>
          <el-table-column prop="sellingPrice" label="售价" width="100" align="right" sortable="custom">
            <template #default="{ row }">
              {{ formatMoney(row.sellingPrice) }}
            </template>
          </el-table-column>
          <el-table-column prop="marginRate" label="毛利率" width="120" align="center" sortable="custom">
            <template #default="{ row }">
              <el-tag
                :type="getMarginTagType(row.marginRate)"
                size="small"
                effect="light"
              >
                {{ row.marginRate }}%
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- Pagination -->
      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
      </template>
      <el-empty v-else description="您没有查看价格/财务数据的权限" />
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
}

.page-card {
  flex: 1;
  display: flex;
  flex-direction: column;

  :deep(.el-card__header) {
    padding: 16px 20px;
    border-bottom: 1px solid var(--border-color-lighter, #ebeef5);
  }

  :deep(.el-card__body) {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 20px;
    overflow: auto;
  }
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;

  .header-left {
    display: flex;
    align-items: baseline;
    gap: 12px;

    .page-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-color-primary, #303133);
    }

    .data-count {
      font-size: 13px;
      color: var(--text-color-secondary, #909399);
    }
  }

  .header-right {
    display: flex;
    gap: 12px;
    align-items: center;
  }
}

.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.kpi-card {
  text-align: center;

  :deep(.el-card__body) {
    padding: 16px;
    display: flex;
    flex-direction: column;
    align-items: center;
  }

  .kpi-value {
    font-size: 28px;
    font-weight: 700;
    line-height: 1.2;
    margin-bottom: 4px;
  }

  .kpi-label {
    font-size: 13px;
    color: var(--text-color-secondary, #909399);
  }

  .kpi-sub {
    font-size: 12px;
    color: var(--text-color-secondary, #909399);
    margin-top: 4px;
    max-width: 160px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.chart-section {
  margin-bottom: 24px;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 16px;
  padding-left: 10px;
  border-left: 3px solid #409eff;
}

.chart-container {
  width: 100%;
  height: 360px;
}

.table-section {
  flex: 1;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid var(--border-color-lighter, #ebeef5);
  margin-top: 16px;
}

@media (max-width: 1200px) {
  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .kpi-row {
    grid-template-columns: 1fr;
  }

  .chart-container {
    height: 280px;
  }
}
</style>
