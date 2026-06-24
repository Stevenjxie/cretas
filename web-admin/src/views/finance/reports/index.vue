<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Money, TrendCharts, Coin, Download } from '@element-plus/icons-vue';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canRead = computed(() => permissionStore.canAccess('finance'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

const loading = ref(false);
const dateRange = ref<[Date, Date]>([
  new Date(new Date().setDate(new Date().getDate() - 30)),
  new Date()
]);

// 财务报表数据
const financeData = ref<{
  totalRevenue?: number;
  totalCost?: number;
  grossProfit?: number;
  profitMargin?: number | null;
  materialCost?: number;
  laborCost?: number;
  equipmentCost?: number;
  otherCost?: number;
  dailyStats?: Array<{ date: string; revenue: number; cost: number; profit: number }>;
}>({});

// 后端响应类型 (来自 GET /smart-bi/analysis/finance?analysisType=profit/cost)
interface MetricResultDTO {
  metricCode: string;
  metricName: string;
  value: number | null;
  formattedValue?: string;
  unit?: string;
}
interface ChartConfigDTO<T = Record<string, unknown>> {
  chartType?: string;
  title?: string;
  data?: T[];
}
interface ProfitAnalysisResponse {
  metrics?: MetricResultDTO[];
  trendChart?: ChartConfigDTO<{ period: string; revenue: number; cost: number; grossProfit: number; netProfit?: number; grossMargin: number | null }>;
}
interface CostAnalysisResponse {
  structureChart?: ChartConfigDTO<{ category?: string; name?: string; value: number; percentage?: number }>;
  trendChart?: ChartConfigDTO;
}

// P0-2: 卡片 format 函数统一签名 (v: number | null | undefined => string)
// 因为 profitMargin 可能为 null (后端 P0-1 Bug C 修复后，毛利率 >100% 或 <-100% 返 null)
type StatFormat = (v: number | null | undefined) => string;
const fmtCurrency: StatFormat = (v) => (v ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2 });
const fmtPercent: StatFormat = (v) => v == null ? 'N/A' : v.toFixed(1);

const statCards = computed<Array<{
  title: string;
  value: number | null | undefined;
  unit: string;
  icon: unknown;
  color: string;
  format: StatFormat;
}>>(() => [
  {
    title: '总收入',
    value: financeData.value.totalRevenue ?? 0,
    unit: '元',
    icon: Money,
    color: '#409eff',
    format: fmtCurrency
  },
  {
    title: '总成本',
    value: financeData.value.totalCost ?? 0,
    unit: '元',
    icon: Coin,
    color: '#e6a23c',
    format: fmtCurrency
  },
  {
    title: '毛利润',
    value: financeData.value.grossProfit ?? 0,
    unit: '元',
    icon: TrendCharts,
    color: '#67c23a',
    format: fmtCurrency
  },
  {
    title: '利润率',
    // profitMargin 可能为 null — null 时显示 "N/A"，单位也清空
    value: financeData.value.profitMargin,
    unit: financeData.value.profitMargin == null ? '' : '%',
    icon: TrendCharts,
    color: '#f56c6c',
    format: fmtPercent
  }
]);

// 成本分解表格
const costBreakdown = computed(() => [
  { name: '原材料成本', value: financeData.value.materialCost ?? 0, percentage: getPercentage('material') },
  { name: '人工成本', value: financeData.value.laborCost ?? 0, percentage: getPercentage('labor') },
  { name: '设备成本', value: financeData.value.equipmentCost ?? 0, percentage: getPercentage('equipment') },
  { name: '其他成本', value: financeData.value.otherCost ?? 0, percentage: getPercentage('other') }
]);

// 当 4 个分类全部为 0 时, 显示空状态而不是 4 行 0.00 / 0.0%
// (chart audit P0-2 副发现: structureChart.data 真空时 backend 返 [], FE 应区分
// "0 数据" vs "全分类都是 0" — 前者是数据真空, 后者可能是分类未配置或时间窗内无成本)
const hasCostBreakdown = computed(() =>
  costBreakdown.value.some((row) => row.value > 0),
);

function getPercentage(type: string) {
  const total = financeData.value.totalCost ?? 1;
  const value = financeData.value[`${type}Cost` as keyof typeof financeData.value] as number ?? 0;
  return total > 0 ? ((value / total) * 100).toFixed(1) : '0.0';
}

// 财务数据是否为空 (无营收/成本/明细) — 用于默认期落地自动展开判断
function isFinanceDataEmpty(): boolean {
  const d = financeData.value;
  const hasRevenue = !!d.totalRevenue;
  const hasCost = !!d.totalCost;
  const hasStats = Array.isArray(d.dailyStats) && d.dailyStats.length > 0;
  return !hasRevenue && !hasCost && !hasStats;
}

onMounted(async () => {
  await loadFinanceData();
  // 优化默认落地 (2026-06-24): 财务数据来自 SmartBI 上传, 常落在更早会计期。
  // 近 30 天若无数据, 自动展开到近 12 个月重载一次, 让用户落地即见数据 (而非空状态)。
  if (isFinanceDataEmpty()) {
    const wideStart = new Date();
    wideStart.setMonth(wideStart.getMonth() - 12);
    dateRange.value = [wideStart, new Date()];
    await loadFinanceData();
    if (!isFinanceDataEmpty()) {
      ElMessage.info('近 30 天暂无财务数据，已自动展开至近 12 个月');
    }
  }
});

async function loadFinanceData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    if (!dateRange.value) return;
    const [startDate, endDate] = dateRange.value;
    const startStr = formatDate(startDate);
    const endStr = formatDate(endDate);

    // P0-2: 并行调用 analysisType=profit + analysisType=cost
    // 后端返嵌套结构，前端需要 flat KPI 字段，所以双调用 + 字段映射
    const baseUrl = `/${factoryId.value}/smart-bi/analysis/finance?startDate=${startStr}&endDate=${endStr}`;
    const [profitRes, costRes] = await Promise.all([
      get<ProfitAnalysisResponse>(`${baseUrl}&analysisType=profit`),
      get<CostAnalysisResponse>(`${baseUrl}&analysisType=cost`)
    ]);

    if (!profitRes.success || !costRes.success) {
      ElMessage.error('加载财务数据失败');
      return;
    }

    financeData.value = mapResponses(profitRes.data, costRes.data);
  } catch (error) {
    console.error('加载财务数据失败:', error);
    ElMessage.error('加载财务数据失败，请检查网络连接');
  } finally {
    loading.value = false;
  }
}

/**
 * 将后端两个响应映射为前端 flat 字段
 * P0-2 字段映射：
 *  - totalRevenue/totalCost ← profit.trendChart.data 加和
 *  - grossProfit/profitMargin ← profit.metrics (metricCode 匹配)
 *  - materialCost/laborCost/equipmentCost/otherCost ← cost.structureChart.data (按 name 匹配)
 *  - dailyStats ← profit.trendChart.data (字段重命名 period→date, grossProfit→profit)
 */
function mapResponses(
  profitData: ProfitAnalysisResponse | undefined,
  costData: CostAnalysisResponse | undefined
): typeof financeData.value {
  const out: typeof financeData.value = {};

  // --- Profit metrics (KPI cards) ---
  const metrics = profitData?.metrics ?? [];
  const findMetric = (code: string): number | null | undefined => {
    const m = metrics.find(x => x.metricCode === code);
    return m ? m.value : undefined;
  };
  // 毛利额 (元) — backend GROSS_PROFIT
  const gp = findMetric('GROSS_PROFIT');
  out.grossProfit = gp != null ? Number(gp) : 0;
  // 毛利率 (%) — backend GROSS_MARGIN. 后端可能返 null (毛利率>100% 或 <-100% 时，P0-1 Bug C 修复)
  const gm = findMetric('GROSS_MARGIN');
  out.profitMargin = gm != null ? Number(gm) : null;

  // --- Profit trendChart → totalRevenue/totalCost/dailyStats ---
  const profitTrend = profitData?.trendChart?.data ?? [];
  let totalRevenue = 0;
  let totalCost = 0;
  const dailyStats: Array<{ date: string; revenue: number; cost: number; profit: number }> = [];
  for (const p of profitTrend) {
    const revenue = Number(p.revenue ?? 0);
    const cost = Number(p.cost ?? 0);
    const profit = Number(p.grossProfit ?? 0);
    totalRevenue += revenue;
    totalCost += cost;
    dailyStats.push({ date: p.period, revenue, cost, profit });
  }
  out.totalRevenue = totalRevenue;
  out.totalCost = totalCost;
  out.dailyStats = dailyStats;

  // --- Cost structureChart → materialCost/laborCost/equipmentCost/otherCost ---
  // 后端 PIE data items 来自 createPieDataItem(category, value, total) — 字段是 category/value/percentage
  const costSlices = costData?.structureChart?.data ?? [];
  for (const slice of costSlices) {
    const name = String(slice.category ?? slice.name ?? '');
    const value = Number(slice.value ?? 0);
    if (name.includes('原材料')) {
      out.materialCost = (out.materialCost ?? 0) + value;
    } else if (name.includes('人工')) {
      out.laborCost = (out.laborCost ?? 0) + value;
    } else if (name.includes('制造费用') || name.includes('设备')) {
      // 后端常量是 "制造费用" (overheadCost)，UI 显示为 "设备成本"，做兼容映射
      out.equipmentCost = (out.equipmentCost ?? 0) + value;
    } else {
      out.otherCost = (out.otherCost ?? 0) + value;
    }
  }
  // 默认值 fallback (如果某类没有)
  out.materialCost = out.materialCost ?? 0;
  out.laborCost = out.laborCost ?? 0;
  out.equipmentCost = out.equipmentCost ?? 0;
  out.otherCost = out.otherCost ?? 0;

  return out;
}

function formatDate(date: Date | null | undefined) {
  if (!date) return '';
  return date.toISOString().split('T')[0];
}

function handleDateChange() {
  loadFinanceData();
}

function handleExport() {
  const data = financeData.value;
  if (!data || (!data.totalRevenue && !data.totalCost)) {
    ElMessage.warning('暂无数据可导出');
    return;
  }
  const [startDate, endDate] = dateRange.value;
  const headers = ['项目', '金额(元)', '占比(%)'];
  const rows = [
    ['总收入', String(data.totalRevenue ?? 0), '-'],
    ['总成本', String(data.totalCost ?? 0), '100.0'],
    ['  原材料成本', String(data.materialCost ?? 0), getPercentage('material')],
    ['  人工成本', String(data.laborCost ?? 0), getPercentage('labor')],
    ['  设备成本', String(data.equipmentCost ?? 0), getPercentage('equipment')],
    ['  其他成本', String(data.otherCost ?? 0), getPercentage('other')],
    ['毛利润', String(data.grossProfit ?? 0), '-'],
    ['利润率(%)', data.profitMargin == null ? 'N/A' : data.profitMargin.toFixed(1), '-']
  ];
  const csvContent = '\uFEFF' + [
    [`财务报表 (${formatDate(startDate)} ~ ${formatDate(endDate)})`],
    [],
    headers,
    ...rows
  ].map(r => r.map(c => `"${String(c).replace(/"/g, '""')}"`).join(',')).join('\n');
  const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = `财务报表_${formatDate(startDate)}_${formatDate(endDate)}.csv`;
  link.click();
  URL.revokeObjectURL(link.href);
  ElMessage.success('导出成功');
}
</script>

<template>
  <div class="page-wrapper" v-loading="loading">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span class="page-title">财务分析(Excel)</span>
          <div class="header-actions">
            <el-date-picker
              v-model="dateRange"
              type="daterange"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              :shortcuts="[
                { text: '最近一周', value: () => [new Date(Date.now() - 7 * 24 * 60 * 60 * 1000), new Date()] },
                { text: '最近一月', value: () => [new Date(Date.now() - 30 * 24 * 60 * 60 * 1000), new Date()] },
                { text: '最近三月', value: () => [new Date(Date.now() - 90 * 24 * 60 * 60 * 1000), new Date()] }
              ]"
              @change="handleDateChange"
            />
            <el-button v-if="canViewPrice" type="primary" :icon="Download" @click="handleExport">
              导出报表
            </el-button>
          </div>
        </div>
      </template>

      <template v-if="canViewPrice">
      <!-- 统计卡片 -->
      <div class="stat-cards">
        <el-card v-for="card in statCards" :key="card.title" class="stat-card" shadow="hover">
          <div class="stat-content">
            <div class="stat-icon" :style="{ backgroundColor: `${card.color}20`, color: card.color }">
              <el-icon :size="24"><component :is="card.icon" /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-title">{{ card.title }}</div>
              <div class="stat-value" :style="{ color: card.color }">
                {{ card.format(card.value) }} <span class="stat-unit">{{ card.unit }}</span>
              </div>
            </div>
          </div>
        </el-card>
      </div>

      <!-- 成本分解 -->
      <div class="section">
        <h3 class="section-title">成本分解</h3>
        <el-table
          v-if="hasCostBreakdown"
          :data="costBreakdown"
          stripe
          border
          style="width: 100%"
        >
          <el-table-column prop="name" label="成本类型" width="180" />
          <el-table-column label="金额 (元)" min-width="200">
            <template #default="{ row }">
              <span class="cost-value">{{ row.value.toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="占比" width="150">
            <template #default="{ row }">
              <el-progress
                :percentage="parseFloat(row.percentage)"
                :color="row.percentage > 50 ? '#f56c6c' : row.percentage > 30 ? '#e6a23c' : '#67c23a'"
                :format="() => `${row.percentage}%`"
              />
            </template>
          </el-table-column>
        </el-table>
        <el-empty
          v-else
          description="该期无成本细分数据 — 可在「成本分析」页面查看明细或检查 Excel 上传是否包含 原材料 / 人工 / 制造费用 列"
          :image-size="100"
          class="cost-empty-state"
        />
      </div>

      <!-- 提示信息 -->
      <div class="info-section">
        <el-alert
          title="数据说明"
          type="info"
          :closable="false"
          show-icon
        >
          <template #default>
            当前显示的是所选日期范围内的财务汇总数据。详细的成本分析请前往"成本分析"页面查看。
          </template>
        </el-alert>
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

  .page-title {
    font-size: 16px;
    font-weight: 600;
    color: var(--text-color-primary, #303133);
  }

  .header-actions {
    display: flex;
    gap: 12px;
    align-items: center;
  }
}

.stat-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}

.stat-card {
  :deep(.el-card__body) {
    padding: 16px;
  }
}

.stat-content {
  display: flex;
  align-items: center;
  gap: 16px;
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.stat-info {
  flex: 1;
}

.stat-title {
  font-size: 13px;
  color: #909399;
  margin-bottom: 4px;
}

.stat-value {
  font-size: 22px;
  font-weight: 600;
}

.stat-unit {
  font-size: 12px;
  font-weight: normal;
  margin-left: 4px;
}

.section {
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

.cost-value {
  font-weight: 500;
  color: #303133;
}

.cost-empty-state {
  padding: 32px 0;
  background: #fafbfc;
  border-radius: 4px;
}

.info-section {
  margin-top: auto;
}
</style>
