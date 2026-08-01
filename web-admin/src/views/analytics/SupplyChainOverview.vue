<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { formatNumber } from '@/utils/format-number';
import { formatDateTimeCell } from '@/utils/tableFormatters';
import echarts from '@/utils/echarts';
import type { ECharts } from 'echarts/core';

// ---------- auth ----------
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);

// ---------- state ----------
const loading = ref(false);
const activeTab = ref('purchase');
const dateRange = ref<[string, string] | null>(null);

// Sankey chart
const sankeyChartRef = ref<HTMLElement | null>(null);
let chartInstance: ECharts | null = null;

// ---------- summary data ----------
const summary = ref({
  purchaseTotal: 0,
  receivedQuantity: 0,
  consumedQuantity: 0,
  productionBatches: 0,
  finishedGoods: 0,
  salesTotal: 0,
});

// ---------- detail tab data ----------
const purchaseOrders = ref<PurchaseOrder[]>([]);
const materialBatches = ref<MaterialBatch[]>([]);
const productionBatchList = ref<ProductionBatch[]>([]);
const salesOrders = ref<SalesOrder[]>([]);

// ---------- types ----------
interface PurchaseOrder {
  id: string;
  orderNumber: string;
  supplierName: string;
  totalAmount: number;
  status: string;
  createdAt: string;
}

interface MaterialBatch {
  id: string;
  batchNumber: string;
  materialTypeName: string;
  quantity: number;
  unit: string;
  status: string;
  receivedDate: string;
}

interface ProductionBatch {
  id: string;
  batchNumber: string;
  productTypeName: string;
  plannedQuantity: number;
  actualQuantity: number | null;
  status: string;
  createdAt: string;
}

interface SalesOrder {
  id: string;
  orderNumber: string;
  customerName: string;
  totalAmount: number;
  status: string;
  createdAt: string;
}

// ---------- load data ----------
onMounted(() => {
  loadAllData();
});

onUnmounted(() => {
  if (chartInstance) {
    chartInstance.dispose();
    chartInstance = null;
  }
});

async function loadAllData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params: Record<string, unknown> = { page: 1, size: 20 };
    if (dateRange.value && dateRange.value[0]) {
      params.startDate = dateRange.value[0];
      params.endDate = dateRange.value[1];
    }

    // Suppress global error toast — RESTAURANT factories don't have purchase-orders
    // or processing/batches, so those endpoints return 500. We handle errors locally
    // via allSettled + status === 'fulfilled' checks below.
    const silentCfg = { params: { ...params, size: 10 }, _silent: true };

    const [purchaseRes, materialRes, batchRes, salesRes] = await Promise.allSettled([
      get<{ content: PurchaseOrder[]; totalElements: number }>(
        `/${factoryId.value}/purchase/orders`, silentCfg
      ),
      get<{ content: MaterialBatch[]; totalElements: number }>(
        `/${factoryId.value}/material-batches`, silentCfg
      ),
      get<{ content: ProductionBatch[]; totalElements: number }>(
        `/${factoryId.value}/processing/batches`, silentCfg
      ),
      get<{ content: SalesOrder[]; totalElements: number }>(
        `/${factoryId.value}/sales/orders`, silentCfg
      ),
    ]);

    // Purchase
    if (purchaseRes.status === 'fulfilled' && purchaseRes.value.success && purchaseRes.value.data) {
      const data = purchaseRes.value.data;
      purchaseOrders.value = data.content || [];
      summary.value.purchaseTotal = purchaseOrders.value.reduce(
        (sum, o) => sum + (o.totalAmount || 0), 0
      );
    }

    // Material receipts
    if (materialRes.status === 'fulfilled' && materialRes.value.success && materialRes.value.data) {
      const data = materialRes.value.data;
      materialBatches.value = data.content || [];
      summary.value.receivedQuantity = data.totalElements || materialBatches.value.length;
    }

    // Production
    if (batchRes.status === 'fulfilled' && batchRes.value.success && batchRes.value.data) {
      const data = batchRes.value.data;
      productionBatchList.value = data.content || [];
      summary.value.productionBatches = data.totalElements || productionBatchList.value.length;
      // Estimate consumed from planned, finished from actual
      summary.value.consumedQuantity = productionBatchList.value.reduce(
        (sum, b) => sum + (b.plannedQuantity || 0), 0
      );
      summary.value.finishedGoods = productionBatchList.value.reduce(
        (sum, b) => sum + (b.actualQuantity || 0), 0
      );
    }

    // Sales
    if (salesRes.status === 'fulfilled' && salesRes.value.success && salesRes.value.data) {
      const data = salesRes.value.data;
      salesOrders.value = data.content || [];
      summary.value.salesTotal = salesOrders.value.reduce(
        (sum, o) => sum + (o.totalAmount || 0), 0
      );
    }

    // Render chart after data ready
    await nextTick();
    renderSankeyChart();
  } catch (error) {
    console.error('加载进销存数据失败:', error);
    ElMessage.error('加载数据失败，请稍后重试');
  } finally {
    loading.value = false;
  }
}

// ---------- Sankey chart ----------
function renderSankeyChart() {
  if (!sankeyChartRef.value) return;

  if (chartInstance) {
    chartInstance.dispose();
  }
  chartInstance = echarts.init(sankeyChartRef.value, 'cretas');

  // Use summary values for links; ensure no zero values to keep chart visible
  const purchaseVal = summary.value.purchaseTotal || 1;
  const receivedVal = summary.value.receivedQuantity || 1;
  const consumedVal = summary.value.consumedQuantity || 1;
  const batchVal = summary.value.productionBatches || 1;
  const finishedVal = summary.value.finishedGoods || 1;
  const salesVal = summary.value.salesTotal || 1;

  // Normalize values for Sankey so they are visually comparable
  const maxVal = Math.max(purchaseVal, receivedVal, consumedVal, batchVal, finishedVal, salesVal);
  const normalize = (v: number) => Math.max(Math.round((v / maxVal) * 100), 5);

  const option = {
    tooltip: {
      trigger: 'item' as const,
      triggerOn: 'mousemove' as const,
    },
    series: [{
      type: 'sankey' as const,
      layout: 'none',
      left: 40,
      right: 40,
      top: 20,
      bottom: 20,
      nodeWidth: 20,
      nodeGap: 16,
      emphasis: {
        focus: 'adjacency' as const,
      },
      data: [
        { name: '采购', itemStyle: { color: '#409EFF' } },
        { name: '入库', itemStyle: { color: '#67C23A' } },
        { name: '领用', itemStyle: { color: '#E6A23C' } },
        { name: '生产', itemStyle: { color: '#F56C6C' } },
        { name: '成品', itemStyle: { color: '#9B59B6' } },
        { name: '出库', itemStyle: { color: '#2D8B57' } },
      ],
      links: [
        { source: '采购', target: '入库', value: normalize(purchaseVal) },
        { source: '入库', target: '领用', value: normalize(receivedVal) },
        { source: '领用', target: '生产', value: normalize(consumedVal) },
        { source: '生产', target: '成品', value: normalize(finishedVal) },
        { source: '成品', target: '出库', value: normalize(salesVal) },
      ],
      label: {
        position: 'top' as const,
        fontSize: 13,
        fontWeight: 600,
        color: '#303133',
      },
      lineStyle: {
        color: 'gradient' as const,
        curveness: 0.5,
        opacity: 0.4,
      },
    }],
  };

  chartInstance.setOption(option);
}

// Resize chart on window resize
function handleResize() {
  chartInstance?.resize();
}

onMounted(() => {
  window.addEventListener('resize', handleResize);
});

onUnmounted(() => {
  window.removeEventListener('resize', handleResize);
});

// ---------- helpers ----------
function handleDateChange() {
  loadAllData();
}

function handleRefresh() {
  dateRange.value = null;
  loadAllData();
}

function getStatusType(status: string): string {
  const map: Record<string, string> = {
    PLANNED: 'info', PENDING: 'info', DRAFT: 'info',
    IN_PROGRESS: 'warning', PROCESSING: 'warning', APPROVED: 'warning',
    PARTIAL_RECEIVED: 'warning', PARTIAL_DELIVERED: 'warning',
    FINANCE_APPROVED: 'warning', PENDING_FINANCE_REVIEW: 'info',
    // 批次状态 (material batches in 入库记录 tab)
    AVAILABLE: 'success', RESERVED: 'warning', DEPLETED: 'info',
    EXPIRED: 'danger', QUARANTINE: 'danger',
    COMPLETED: 'success', DELIVERED: 'success', RECEIVED: 'success',
    CANCELLED: 'danger', REJECTED: 'danger',
  };
  return map[status?.toUpperCase()] || 'info';
}

function getStatusText(status: string): string {
  // chart audit P2-1: backend may emit raw enums for partial-flow states
  // (PARTIAL_RECEIVED / FINANCE_APPROVED / etc.). 还有批次状态 (AVAILABLE
  // / RESERVED / 等), 真窗 verify 揭示后补.
  const map: Record<string, string> = {
    PLANNED: '待处理', PENDING: '待处理', DRAFT: '草稿',
    IN_PROGRESS: '进行中', PROCESSING: '处理中', APPROVED: '已审批',
    PARTIAL_RECEIVED: '部分到货', PARTIAL_DELIVERED: '部分发货',
    FINANCE_APPROVED: '财务已审', PENDING_FINANCE_REVIEW: '财务审核中',
    // 批次状态翻译 (入库记录 tab)
    AVAILABLE: '可用', RESERVED: '已预留', DEPLETED: '已用尽',
    EXPIRED: '已过期', QUARANTINE: '隔离中',
    COMPLETED: '已完成', DELIVERED: '已交付', RECEIVED: '已收货',
    CANCELLED: '已取消', REJECTED: '已拒绝',
  };
  return map[status?.toUpperCase()] || status || '-';
}
</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">进销存闭环总览</span>
            <span class="subtitle">采购 → 入库 → 领用 → 生产 → 成品 → 出库 全流程追踪</span>
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
              @change="handleDateChange"
            />
            <el-button :icon="Refresh" @click="handleRefresh">刷新</el-button>
          </div>
        </div>
      </template>

      <!-- Sankey Flow Diagram -->
      <div class="sankey-section" v-loading="loading">
        <h3 class="section-title">供应链流向</h3>
        <div ref="sankeyChartRef" class="sankey-chart" />
      </div>

      <!-- Summary Cards -->
      <el-row :gutter="16" class="summary-cards">
        <el-col v-if="canViewPrice" :xs="12" :sm="8" :md="4">
          <el-card class="stat-card purchase" shadow="hover">
            <div class="stat-value">{{ formatNumber(summary.purchaseTotal) }}</div>
            <div class="stat-label">采购总额</div>
          </el-card>
        </el-col>
        <el-col :xs="12" :sm="8" :md="4">
          <el-card class="stat-card receive" shadow="hover">
            <div class="stat-value">{{ formatNumber(summary.receivedQuantity, 0) }}</div>
            <div class="stat-label">入库批次</div>
          </el-card>
        </el-col>
        <el-col :xs="12" :sm="8" :md="4">
          <el-card class="stat-card consume" shadow="hover">
            <!--
              🔴 2026-08-02: 「领用数量」「成品数量」是**重量**不是计数
              (见上方 loadData: consumedQuantity = Σ plannedQuantity,
               finishedGoods = Σ actualQuantity, 都是批次的数量字段)。
              此前和「入库批次」「生产批次」一样用 decimals=0, 于是 127.5kg 显示成 128 ——
              这不是"约等于", 是把 0.5kg 凭空吃掉了。
              ⛔ 计数类的两张卡(入库批次/生产批次)仍保持 0 位小数 —— 那两个本来就是整数个。
            -->
            <div class="stat-value">{{ formatNumber(summary.consumedQuantity, 2) }}<span class="stat-unit">kg</span></div>
            <div class="stat-label">领用数量</div>
          </el-card>
        </el-col>
        <el-col :xs="12" :sm="8" :md="4">
          <el-card class="stat-card production" shadow="hover">
            <div class="stat-value">{{ formatNumber(summary.productionBatches, 0) }}</div>
            <div class="stat-label">生产批次</div>
          </el-card>
        </el-col>
        <el-col :xs="12" :sm="8" :md="4">
          <el-card class="stat-card finished" shadow="hover">
            <div class="stat-value">{{ formatNumber(summary.finishedGoods, 2) }}<span class="stat-unit">kg</span></div>
            <div class="stat-label">成品数量</div>
          </el-card>
        </el-col>
        <el-col v-if="canViewPrice" :xs="12" :sm="8" :md="4">
          <el-card class="stat-card sales" shadow="hover">
            <div class="stat-value">{{ formatNumber(summary.salesTotal) }}</div>
            <div class="stat-label">出库/销售额</div>
          </el-card>
        </el-col>
      </el-row>

      <!-- Detail Tabs -->
      <div class="detail-section">
        <el-tabs v-model="activeTab">
          <!-- 采购 -->
          <el-tab-pane label="采购订单" name="purchase">
            <el-table
              :data="purchaseOrders"
              v-loading="loading"
              empty-text="暂无采购数据"
              stripe
              border
              style="width: 100%"
            >
              <el-table-column prop="orderNumber" label="订单号" min-width="150" show-overflow-tooltip />
              <el-table-column prop="supplierName" label="供应商" min-width="150" show-overflow-tooltip />
              <el-table-column v-if="canViewPrice" prop="totalAmount" label="总金额" width="120" align="right">
                <template #default="{ row }">
                  {{ formatNumber(row.totalAmount) }}
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
          </el-tab-pane>

          <!-- 入库 -->
          <el-tab-pane label="入库记录" name="receive">
            <el-table
              :data="materialBatches"
              v-loading="loading"
              empty-text="暂无入库数据"
              stripe
              border
              style="width: 100%"
            >
              <el-table-column prop="batchNumber" label="批次号" min-width="150" show-overflow-tooltip />
              <el-table-column prop="materialTypeName" label="物料名称" min-width="150" show-overflow-tooltip />
              <el-table-column prop="quantity" label="数量" width="120" align="right">
                <template #default="{ row }">
                  {{ formatNumber(row.quantity, 1) }} {{ row.unit || '' }}
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="getStatusType(row.status)" size="small">
                    {{ getStatusText(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="receivedDate" label="入库时间" width="180" :formatter="formatDateTimeCell" />
            </el-table>
          </el-tab-pane>

          <!-- 生产 -->
          <el-tab-pane label="生产批次" name="production">
            <el-table
              :data="productionBatchList"
              v-loading="loading"
              empty-text="暂无生产数据"
              stripe
              border
              style="width: 100%"
            >
              <el-table-column prop="batchNumber" label="批次号" min-width="150" show-overflow-tooltip />
              <el-table-column label="产品名称" min-width="150" show-overflow-tooltip>
                <template #default="{ row }">{{ row.productTypeName || row.productName || row.productTypeId || '-' }}</template>
              </el-table-column>
              <el-table-column prop="plannedQuantity" label="计划数量" width="110" align="right" />
              <el-table-column prop="actualQuantity" label="实际数量" width="110" align="right">
                <template #default="{ row }">
                  {{ row.actualQuantity ?? '-' }}
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
          </el-tab-pane>

          <!-- 出库/销售 -->
          <el-tab-pane label="出库/销售" name="sales">
            <el-table
              :data="salesOrders"
              v-loading="loading"
              empty-text="暂无销售数据"
              stripe
              border
              style="width: 100%"
            >
              <el-table-column prop="orderNumber" label="订单号" min-width="150" show-overflow-tooltip />
              <el-table-column prop="customerName" label="客户名称" min-width="150" show-overflow-tooltip />
              <el-table-column v-if="canViewPrice" prop="totalAmount" label="总金额" width="120" align="right">
                <template #default="{ row }">
                  {{ formatNumber(row.totalAmount) }}
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
          </el-tab-pane>
        </el-tabs>
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

    .subtitle {
      font-size: 13px;
      color: var(--text-color-secondary, #909399);
    }
  }

  .header-right {
    display: flex;
    align-items: center;
    gap: 12px;
  }
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin: 0 0 12px;
}

.sankey-section {
  margin-bottom: 24px;
}

.sankey-chart {
  width: 100%;
  height: 260px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  background: #fafbfc;
}

.summary-cards {
  margin-bottom: 24px;
}

.stat-card {
  text-align: center;
  margin-bottom: 12px;
  border-radius: 10px;
  transition: all 0.2s ease;

  &:hover {
    transform: translateY(-3px);
  }

  :deep(.el-card__body) {
    padding: 16px 12px;
  }

  .stat-value {
    font-size: 24px;
    font-weight: 700;
    line-height: 1.2;
    font-variant-numeric: tabular-nums;
  }

  /* 重量类卡片的单位后缀 —— 比数字小一号, 不抢读数 */
  .stat-unit {
    font-size: 13px;
    font-weight: 500;
    margin-left: 2px;
    opacity: 0.75;
  }

  .stat-label {
    font-size: 12px;
    color: #909399;
    margin-top: 6px;
  }

  &.purchase {
    border-top: 3px solid #409EFF;
    .stat-value { color: #409EFF; }
  }

  &.receive {
    border-top: 3px solid #67C23A;
    .stat-value { color: #67C23A; }
  }

  &.consume {
    border-top: 3px solid #E6A23C;
    .stat-value { color: #E6A23C; }
  }

  &.production {
    border-top: 3px solid #F56C6C;
    .stat-value { color: #F56C6C; }
  }

  &.finished {
    border-top: 3px solid #9B59B6;
    .stat-value { color: #9B59B6; }
  }

  &.sales {
    border-top: 3px solid #2D8B57;
    .stat-value { color: #2D8B57; }
  }
}

.detail-section {
  flex: 1;
}

@media (max-width: 768px) {
  .card-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .sankey-chart {
    height: 200px;
  }
}
</style>
