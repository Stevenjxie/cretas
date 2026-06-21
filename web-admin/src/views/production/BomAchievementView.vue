<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Search, Refresh } from '@element-plus/icons-vue';
import { formatDateTimeCell } from '@/utils/tableFormatters';

// ---------- auth ----------
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

// ---------- state ----------
const loading = ref(false);
const tableData = ref<BatchRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const dateRange = ref<[string, string] | null>(null);

// Per-batch expanded detail (materialConsumptions)
const expandedDetails = ref<Record<number, MaterialDetail[]>>({});
const expandLoading = ref<Record<number, boolean>>({});

// ---------- types ----------
interface BatchRow {
  id: number;
  batchNumber: string;
  productTypeName?: string;
  productName?: string;
  plannedQuantity: number;
  actualQuantity: number | null;
  status: string;
  createdAt: string;
  // computed from consumption summary
  plannedTotal?: number;
  actualTotal?: number;
  achievementRate?: number;
}

interface MaterialDetail {
  materialName: string;
  planned: number;
  actual: number;
  variance: number;
  unit?: string;
}

interface ConsumptionSummary {
  materialName?: string;
  materialTypeName?: string;
  plannedQuantity?: number;
  actualQuantity?: number;
  unit?: string;
}

// ---------- KPI ----------
const kpi = computed(() => {
  const rows = tableData.value;
  const total = rows.length;
  if (total === 0) return { total: 0, avgRate: null as number | null, overConsumptionCount: 0, minRate: null as number | null };

  // Only include rows that have a real achievementRate (not null/undefined)
  const rowsWithRate = rows.filter(r => r.achievementRate != null);
  const rates = rowsWithRate.map(r => r.achievementRate as number);

  const avg = rates.length > 0 ? rates.reduce((a, b) => a + b, 0) / rates.length : null;
  // Only count as over-consumption rows with actual data showing < 95%
  const overCount = rows.filter(r => r.achievementRate != null && r.achievementRate < 95).length;
  const minRate = rates.length > 0 ? Math.min(...rates) : null;

  return {
    total,
    avgRate: avg != null ? Math.round(avg * 10) / 10 : null,
    overConsumptionCount: overCount,
    minRate: minRate != null ? Math.round(minRate * 10) / 10 : null,
  };
});

// ---------- load ----------
onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const params: Record<string, unknown> = {
      page: pagination.value.page,
      size: pagination.value.size,
      status: 'COMPLETED',
    };
    if (dateRange.value && dateRange.value[0]) {
      params.startDate = dateRange.value[0];
      params.endDate = dateRange.value[1];
    }

    const response = await get<{
      content: BatchRow[];
      totalElements: number;
    }>(`/${factoryId.value}/processing/batches`, { params });

    if (response.success && response.data) {
      const batches = response.data.content || [];
      // Fetch consumption summaries in parallel
      const enriched = await Promise.all(batches.map(enrichBatch));
      tableData.value = enriched;
      pagination.value.total = response.data.totalElements || 0;
    }
  } catch (error: any) {
    console.error('Failed to load batches:', error);
    if (!error?.actionHint) ElMessage.error('加载批次数据失败');
  } finally {
    loading.value = false;
  }
}

async function enrichBatch(batch: BatchRow): Promise<BatchRow> {
  try {
    const res = await get<Record<string, unknown>>(
      `/${factoryId.value}/processing/material-consumptions/batch/${batch.id}/summary`
    );
    if (res.success && res.data) {
      // 后端返回 { items: [...], overallAchievementRate, totalPlannedCost, totalActualCost }
      const items = (res.data.items as Record<string, unknown>[] | undefined) || (Array.isArray(res.data) ? res.data as Record<string, unknown>[] : []);
      const planned = items.reduce((s: number, r) => s + (Number(r.plannedQty ?? r.plannedQuantity ?? 0)), 0);
      const actual = items.reduce((s: number, r) => s + (Number(r.actualQty ?? r.actualQuantity ?? 0)), 0);
      if (items.length > 0) {
        // If planned > 0 we can compute from items; otherwise use backend overallAchievementRate if present.
        // Never fall back to 100 — null means no data.
        const computedRate = planned > 0 ? Math.round((actual / planned) * 1000) / 10 : null;
        const backendRate = res.data.overallAchievementRate != null ? Number(res.data.overallAchievementRate) : null;
        return {
          ...batch,
          plannedTotal: Math.round(planned * 100) / 100,
          actualTotal: Math.round(actual * 100) / 100,
          achievementRate: computedRate ?? backendRate ?? undefined,
        };
      }
    }
  } catch {
    // Silently fall back — API may not exist yet for some batches
  }
  return {
    ...batch,
    plannedTotal: batch.plannedQuantity ?? 0,
    actualTotal: batch.actualQuantity ?? 0,
    // Never use 100 as a fake default — null means no consumption data available
    achievementRate: batch.plannedQuantity && batch.actualQuantity != null
      ? Math.round((batch.actualQuantity / batch.plannedQuantity) * 1000) / 10
      : undefined,
  };
}

// ---------- expand row ----------
async function handleExpandChange(row: BatchRow, expandedRows: BatchRow[]) {
  const isExpanded = expandedRows.some(r => r.id === row.id);
  if (!isExpanded || expandedDetails.value[row.id]) return;

  expandLoading.value[row.id] = true;
  try {
    const res = await get<Record<string, unknown>>(
      `/${factoryId.value}/processing/material-consumptions/batch/${row.id}/summary`
    );
    if (res.success && res.data) {
      // 后端返回 { items: [...] } 或直接数组
      const items = (res.data.items as Record<string, unknown>[] | undefined) || (Array.isArray(res.data) ? res.data as Record<string, unknown>[] : []);
      expandedDetails.value[row.id] = items.map((item) => ({
        materialName: (item.materialName as string) || (item.materialTypeName as string) || '-',
        planned: Number(item.plannedQty ?? item.plannedQuantity ?? 0),
        actual: Number(item.actualQty ?? item.actualQuantity ?? 0),
        variance: Math.round((Number(item.actualQty ?? item.actualQuantity ?? 0) - Number(item.plannedQty ?? item.plannedQuantity ?? 0)) * 100) / 100,
        unit: item.unit as string,
      }));
    } else {
      expandedDetails.value[row.id] = [];
    }
  } catch {
    expandedDetails.value[row.id] = [];
  } finally {
    expandLoading.value[row.id] = false;
  }
}

// ---------- helpers ----------
function getRateTagType(rate: number | undefined): string {
  if (rate == null) return 'info';
  if (rate >= 95) return 'success';
  if (rate >= 85) return 'warning';
  return 'danger';
}

function getRateColor(rate: number | undefined): string {
  if (rate == null) return '#909399';
  if (rate >= 95) return '#67C23A';
  if (rate >= 85) return '#E6A23C';
  return '#F56C6C';
}

function getStatusType(status: string): string {
  const map: Record<string, string> = {
    PLANNED: 'info', PENDING: 'info',
    IN_PROGRESS: 'warning',
    COMPLETED: 'success',
    CANCELLED: 'danger',
  };
  return map[status?.toUpperCase()] || 'info';
}

function getStatusText(status: string): string {
  const map: Record<string, string> = {
    PLANNED: '待生产', PENDING: '待生产',
    IN_PROGRESS: '生产中',
    COMPLETED: '已完成',
    CANCELLED: '已取消',
  };
  return map[status?.toUpperCase()] || status;
}

function getVarianceClass(variance: number): string {
  if (variance > 0) return 'variance-over';
  if (variance < 0) return 'variance-under';
  return '';
}

// ---------- pagination ----------
function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleReset() {
  dateRange.value = null;
  handleSearch();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function handleSizeChange(size: number) {
  pagination.value.size = size;
  pagination.value.page = 1;
  loadData();
}
</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">BOM达成率分析</span>
            <span class="data-count">共 {{ pagination.total }} 条记录</span>
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
              @change="handleSearch"
            />
          </div>
        </div>
      </template>

      <!-- KPI Row -->
      <div class="kpi-row">
        <el-card shadow="hover" class="kpi-card">
          <div class="kpi-value">{{ kpi.total }}</div>
          <div class="kpi-label">总批次数</div>
        </el-card>
        <el-card shadow="hover" class="kpi-card">
          <div class="kpi-value" :style="{ color: kpi.avgRate != null ? getRateColor(kpi.avgRate) : '#909399' }">
            {{ kpi.avgRate != null ? kpi.avgRate + '%' : '—' }}
          </div>
          <div class="kpi-label">平均达成率</div>
        </el-card>
        <el-card shadow="hover" class="kpi-card">
          <div class="kpi-value" style="color: #F56C6C">{{ kpi.overConsumptionCount }}</div>
          <div class="kpi-label">超耗批次数 (&lt;95%)</div>
        </el-card>
        <el-card shadow="hover" class="kpi-card">
          <div class="kpi-value" :style="{ color: kpi.minRate != null ? getRateColor(kpi.minRate) : '#909399' }">
            {{ kpi.minRate != null ? kpi.minRate + '%' : '—' }}
          </div>
          <div class="kpi-label">最低达成率</div>
        </el-card>
      </div>

      <!-- Search bar -->
      <div class="search-bar">
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleReset">重置</el-button>
      </div>

      <!-- Main Table -->
      <el-table
        :data="tableData"
        v-loading="loading"
        empty-text="暂无已完成的生产批次"
        stripe
        border
        style="width: 100%"
        row-key="id"
        @expand-change="handleExpandChange"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-content" v-loading="expandLoading[row.id]">
              <el-table
                v-if="expandedDetails[row.id] && expandedDetails[row.id].length > 0"
                :data="expandedDetails[row.id]"
                border
                size="small"
                style="margin: 12px 48px"
              >
                <el-table-column prop="materialName" label="原材料" min-width="150" />
                <el-table-column prop="planned" label="计划用量" width="120" align="right">
                  <template #default="{ row: r }">
                    {{ r.planned }} {{ r.unit || '' }}
                  </template>
                </el-table-column>
                <el-table-column prop="actual" label="实际用量" width="120" align="right">
                  <template #default="{ row: r }">
                    {{ r.actual }} {{ r.unit || '' }}
                  </template>
                </el-table-column>
                <el-table-column label="偏差" width="120" align="right">
                  <template #default="{ row: r }">
                    <span :class="getVarianceClass(r.variance)">
                      {{ r.variance > 0 ? '+' : '' }}{{ r.variance }} {{ r.unit || '' }}
                    </span>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-else-if="!expandLoading[row.id]" description="暂无物料消耗数据" :image-size="60" />
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="batchNumber" label="批次号" width="160" />
        <el-table-column label="产品名称" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.productTypeName || row.productName || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="plannedTotal" label="计划总量" width="110" align="right">
          <template #default="{ row }">
            {{ row.plannedTotal != null ? row.plannedTotal : '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="actualTotal" label="实际总量" width="110" align="right">
          <template #default="{ row }">
            {{ row.actualTotal != null ? row.actualTotal : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="达成率" width="120" align="center">
          <template #default="{ row }">
            <el-tag
              :type="getRateTagType(row.achievementRate)"
              size="small"
              effect="light"
            >
              {{ row.achievementRate != null ? row.achievementRate + '%' : '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180" :formatter="formatDateTimeCell" />
      </el-table>

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
}

.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
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
}

.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.el-table {
  flex: 1;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid var(--border-color-lighter, #ebeef5);
  margin-top: 16px;
}

.expand-content {
  min-height: 40px;
}

.variance-over {
  color: #F56C6C;
  font-weight: 600;
}

.variance-under {
  color: #67C23A;
  font-weight: 600;
}

@media (max-width: 768px) {
  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
