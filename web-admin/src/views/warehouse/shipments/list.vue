<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Search, Refresh, Check } from '@element-plus/icons-vue';
import { formatDateTime } from '@/utils/dateFormat';
import { handleCatchError } from '@/utils/errorToast';
import { emptyCell } from '@/utils/tableFormatters';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('warehouse'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

// Issue #740 (六扇门 May10): Tab 切换 — 默认进入新的"待销售发货单确认"queue.
//   legacy:  老 /shipments endpoint，只读展示存量记录
//   sales:   新 /warehouse/deliveries/pending (销售推过来的发货单待 confirm)
const activeTab = ref<'pending-sales' | 'legacy'>('pending-sales');

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const searchForm = ref({
  keyword: '',
  status: ''
});

// Issue #740: 销售推过来的待确认发货单 state.
const pendingLoading = ref(false);
const pendingData = ref<TableRow[]>([]);
const pendingPagination = ref({ page: 1, size: 10, total: 0 });

// confirm 弹窗 state
const confirmDialogVisible = ref(false);
const confirmDialogLoading = ref(false);
const confirmingDelivery = ref<TableRow | null>(null);
const confirmItems = ref<Array<{ id: string; productName: string; plannedQty: number; actualQty: number; unit: string }>>([]);

const customers = ref<TableRow[]>([]);
const customerMap = computed(() => {
  const map: Record<string, string> = {};
  customers.value.forEach((c) => { if (c.id && c.name) map[String(c.id)] = String(c.name); });
  return map;
});

onMounted(() => {
  // Issue #740: 默认加载销售待确认 queue
  loadPendingSalesDeliveries();
  loadData();
  loadCustomers();
});

// ==================== Issue #740: 销售发货单待 confirm queue ====================

async function loadPendingSalesDeliveries() {
  if (!factoryId.value) return;
  pendingLoading.value = true;
  try {
    const response = await get(`/${factoryId.value}/warehouse/deliveries/pending`, {
      params: {
        page: pendingPagination.value.page,
        size: pendingPagination.value.size,
      }
    });
    if (response.success && response.data) {
      pendingData.value = response.data.content || [];
      pendingPagination.value.total = response.data.totalElements || 0;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载待确认发货单失败');
    }
  } catch (error) {
    console.error('加载待确认发货单失败:', error);
  } finally {
    pendingLoading.value = false;
  }
}

function handlePendingPageChange(page: number) {
  pendingPagination.value.page = page;
  loadPendingSalesDeliveries();
}

function handlePendingSizeChange(size: number) {
  pendingPagination.value.size = size;
  pendingPagination.value.page = 1;
  loadPendingSalesDeliveries();
}

async function openConfirmDialog(row: TableRow) {
  confirmingDelivery.value = row;
  // 拉取明细
  try {
    const res = await get(`/${factoryId.value}/sales/deliveries/${row.id}`);
    if (!res.success || !res.data) {
      ElMessage.error('加载发货单明细失败');
      return;
    }
    const items = Array.isArray(res.data.items) ? res.data.items : [];
    confirmItems.value = items.map((it: TableRow) => ({
      id: String(it.id || ''),
      productName: String(it.productName || `产品-${it.productTypeId || ''}`),
      plannedQty: Number(it.deliveredQuantity || 0),
      actualQty: Number(it.deliveredQuantity || 0),  // 默认实发 = 计划
      unit: String(it.unit || 'kg'),
    }));
    confirmDialogVisible.value = true;
  } catch (e) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    console.error('加载发货单明细失败:', e);
    handleCatchError(e, '加载发货单明细失败');
  }
}

async function submitConfirmDelivery() {
  if (!confirmingDelivery.value) return;
  // 校验 actualQty
  for (const it of confirmItems.value) {
    if (it.actualQty == null || it.actualQty < 0) {
      ElMessage.warning(`${it.productName} 实际发货数量不能为负`);
      return;
    }
  }
  const actualQuantities: Record<string, number> = {};
  for (const it of confirmItems.value) {
    if (it.id) actualQuantities[it.id] = it.actualQty;
  }
  confirmDialogLoading.value = true;
  try {
    const res = await post(
      `/${factoryId.value}/warehouse/deliveries/${confirmingDelivery.value.id}/confirm`,
      { actualQuantities }
    );
    if (res.success) {
      ElMessage.success('发货确认成功, 库存已扣减');
      confirmDialogVisible.value = false;
      confirmingDelivery.value = null;
      loadPendingSalesDeliveries();
    } else {
      ElMessage.error(res.message || '发货确认失败');
    }
  } catch (e) {
    // interceptor 已显示具体错误 (e.g. 批次未分配 409)
    console.error('发货确认失败:', e);
  } finally {
    confirmDialogLoading.value = false;
  }
}

function getDeliveryStatusType(status: string) {
  const map: Record<string, string> = {
    DRAFT: 'info',
    PENDING_WAREHOUSE_CONFIRM: 'warning',
    PICKED: '',
    SHIPPED: 'warning',
    DELIVERED: 'success',
    RETURNED: 'danger',
  };
  return map[status?.toUpperCase()] || 'info';
}

function getDeliveryStatusText(status: string) {
  const map: Record<string, string> = {
    DRAFT: '草稿',
    PENDING_WAREHOUSE_CONFIRM: '待仓库确认',
    PICKED: '已拣货',
    SHIPPED: '已发货',
    DELIVERED: '已签收',
    RETURNED: '已退回',
  };
  return map[status?.toUpperCase()] || status || '-';
}

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/shipments`, {
      params: {
        page: pagination.value.page - 1,
        size: pagination.value.size,
        keyword: searchForm.value.keyword || undefined,
        status: searchForm.value.status || undefined
      }
    });
    if (response.success && response.data) {
      tableData.value = response.data.content || [];
      pagination.value.total = response.data.totalElements || 0;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载数据失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

async function loadCustomers() {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/customers`);
    if (response.success && response.data) {
      customers.value = response.data.content || response.data || [];
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载客户列表失败');
    }
  } catch (error) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    console.error('加载客户列表失败:', error);
    handleCatchError(error, '加载客户列表失败');
  }
}

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleRefresh() {
  searchForm.value = { keyword: '', status: '' };
  pagination.value.page = 1;
  loadData();
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

// 详情抽屉
const detailVisible = ref(false);
const detailRow = ref<TableRow | null>(null);

function showDetail(row: TableRow) {
  detailRow.value = row;
  detailVisible.value = true;
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    PENDING: 'info',
    SHIPPED: 'warning',
    DELIVERED: 'success',
    CANCELLED: 'danger'
  };
  return map[status?.toUpperCase()] || 'info';
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    PENDING: '待发货',
    SHIPPED: '运输中',
    DELIVERED: '已送达',
    CANCELLED: '已取消'
  };
  return map[status?.toUpperCase()] || status || '-';
}
</script>

<template>
  <div class="page-wrapper">
    <ConceptDisambiguationAlert
      here-name="出货管理"
      here="把成品发给客户的物流操作（车牌号、司机、批次分配）"
      other-name="销售管理 → 销售订单"
      other="客户下的销售订单（金额、收款）。出货是销售订单的物流执行环节"
      other-path="/sales/orders"
    />
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">出货管理</span>
            <span v-if="activeTab === 'legacy'" class="data-count">共 {{ pagination.total }} 条记录</span>
            <span v-else class="data-count">共 {{ pendingPagination.total }} 条待确认</span>
          </div>
        </div>
      </template>

      <!--
        Issue #740 (六扇门 May10): 仓库出货管理重构为两 tab.
          tab 1 (default):  销售推过来的待确认发货单 → 填实发数量 + 扣库存
          tab 2 (legacy):   老 /shipments endpoint 历史数据，只读
      -->
      <el-tabs v-model="activeTab" style="margin-bottom: 12px;">
        <el-tab-pane name="pending-sales">
          <template #label>
            <span style="display: inline-flex; align-items: center; gap: 6px;">
              销售发货单待确认
              <el-badge v-if="pendingPagination.total > 0" :value="pendingPagination.total" :max="99" />
            </span>
          </template>

          <el-alert
            type="info"
            :closable="false"
            show-icon
            style="margin-bottom: 12px;"
          >
            <template #title>
              <span style="font-size: 13px;">
                销售员创建的发货单 (任务单) 列在这里. 仓库确认实际发货数量后, 系统扣减成品库存并生成送货单.
                (六扇门 May10 客户决定)
              </span>
            </template>
          </el-alert>

          <el-table :data="pendingData" v-loading="pendingLoading" empty-text="暂无待确认发货单" stripe border style="width: 100%">
            <el-table-column prop="deliveryNumber" label="发货单号" width="180" :formatter="emptyCell" />
            <el-table-column label="客户" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ row.customer?.name || row.customerName || row.customerId || '-' }}</template>
            </el-table-column>
            <el-table-column prop="deliveryDate" label="计划发货日期" width="130" :formatter="emptyCell" />
            <el-table-column prop="logisticsCompany" label="物流公司" width="130" :formatter="emptyCell" />
            <el-table-column label="销售订单" width="170" show-overflow-tooltip>
              <template #default="{ row }">{{ row.orderNumber || row.salesOrder?.orderNumber || row.salesOrderId || '-' }}</template>
            </el-table-column>
            <el-table-column v-if="canViewPrice" prop="totalAmount" label="金额" width="120" align="right">
              <template #default="{ row }">{{ row.totalAmount ? `¥${row.totalAmount}` : '-' }}</template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="120" align="center">
              <template #default="{ row }">
                <el-tag :type="getDeliveryStatusType(row.status)" size="small">
                  {{ getDeliveryStatusText(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="销售创建时间" width="180">
              <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="180" fixed="right" align="center">
              <template #default="{ row }">
                <el-button
                  v-if="canWrite"
                  type="primary"
                  size="small"
                  :icon="Check"
                  @click="openConfirmDialog(row)"
                >确认并发货</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="pagination-wrapper">
            <el-pagination
              v-model:current-page="pendingPagination.page"
              v-model:page-size="pendingPagination.size"
              :page-sizes="[10, 20, 50, 100]"
              :total="pendingPagination.total"
              layout="total, sizes, prev, pager, next, jumper"
              @current-change="handlePendingPageChange"
              @size-change="handlePendingSizeChange"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane name="legacy" label="历史出货记录 (只读)">

      <el-alert
        title="旧出货台账已冻结，仅供历史查询；新发货请从销售订单创建发货单，并在左侧待确认页完成仓库确认。"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      />

      <div class="search-bar">
        <el-input
          v-model="searchForm.keyword"
          placeholder="搜索出货单号/客户名称"
          :prefix-icon="Search"
          clearable
          style="width: 280px"
          @keyup.enter="handleSearch"
        />
        <el-select v-model="searchForm.status" placeholder="全部状态" clearable style="width: 150px">
          <el-option label="待发货" value="pending" />
          <el-option label="运输中" value="shipped" />
          <el-option label="已送达" value="delivered" />
          <el-option label="已退货" value="returned" />
          <el-option label="已取消" value="cancelled" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
      </div>

      <el-table :data="tableData" v-loading="loading" empty-text="暂无数据" stripe border style="width: 100%">
        <el-table-column prop="shipmentNumber" label="出货单号" width="160" :formatter="emptyCell" />
        <el-table-column label="客户名称" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.customerName || customerMap[row.customerId] || row.customerId || '-' }}</template>
        </el-table-column>
        <el-table-column label="产品" width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.productName || row.productBatchNumber || '-' }}</template>
        </el-table-column>
        <el-table-column prop="quantity" label="数量" width="100" align="right" :formatter="emptyCell" />
        <el-table-column label="物流/车牌" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.logisticsCompany || row.vehicleNumber || row.trackingNumber || '-' }}</template>
        </el-table-column>
        <el-table-column v-if="canViewPrice" label="单价/金额" width="120" align="right">
          <template #default="{ row }">{{ row.totalAmount ? `¥${row.totalAmount}` : row.unitPrice ? `¥${row.unitPrice}/${row.unit || ''}` : '-' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="showDetail(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

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
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!--
      Issue #740: 仓库 confirm 弹窗 — 填实发数量, 确认后扣库存 + 转 SHIPPED.
      客户原话: "比如说我可能计划发发一版, 那但是可能车或者是没装下什么的, 各种各样的因素,
                然后他可能就发了八十, 然后他确认以后呢, 他就可以直接点打印"
    -->
    <el-dialog v-model="confirmDialogVisible" title="确认发货 (填实际数量)" width="600px" :close-on-click-modal="false">
      <template v-if="confirmingDelivery">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 12px;">
          <el-descriptions-item label="发货单号">{{ confirmingDelivery.deliveryNumber }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ confirmingDelivery.customer?.name || confirmingDelivery.customerName || confirmingDelivery.customerId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="销售订单">{{ confirmingDelivery.orderNumber || confirmingDelivery.salesOrder?.orderNumber || confirmingDelivery.salesOrderId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="计划日期">{{ confirmingDelivery.deliveryDate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="物流公司">{{ confirmingDelivery.logisticsCompany || '-' }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom: 12px;"
        >
          <template #title>
            <span style="font-size: 13px;">
              请填写每行实际发货数量 (默认 = 计划数量). 确认后系统将扣减成品库存.
              如修改数量, 需先到"销售订单 → 发货记录"重新分配批次保证总量匹配.
            </span>
          </template>
        </el-alert>

        <el-table :data="confirmItems" border size="small">
          <el-table-column prop="productName" label="产品" min-width="160" />
          <el-table-column label="计划数量" width="120" align="right">
            <template #default="{ row }">{{ row.plannedQty }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="实际数量" width="180" align="center">
            <template #default="{ row }">
              <el-input-number
                v-model="row.actualQty"
                :min="0"
                :max="row.plannedQty"
                :precision="2"
                size="small"
                style="width: 130px;"
              />
              <span style="margin-left: 4px;">{{ row.unit }}</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="confirmDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="confirmDialogLoading" @click="submitConfirmDelivery">
          确认并扣库存
        </el-button>
      </template>
    </el-dialog>

    <!-- 出货详情抽屉 -->
    <el-drawer v-model="detailVisible" title="出货详情" size="420px">
      <template v-if="detailRow">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="出货单号">{{ detailRow.shipmentNumber || '-' }}</el-descriptions-item>
          <el-descriptions-item label="客户名称">{{ detailRow.customerName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="产品批次">{{ detailRow.productBatchNumber || '-' }}</el-descriptions-item>
          <el-descriptions-item label="数量">{{ detailRow.quantity ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="getStatusType(detailRow.status)" size="small">
              {{ getStatusText(detailRow.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="车牌号">{{ detailRow.vehicleNumber || '-' }}</el-descriptions-item>
          <el-descriptions-item label="司机">{{ detailRow.driverName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="司机电话">{{ detailRow.driverPhone || '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDateTime(detailRow.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="备注">{{ detailRow.notes || '-' }}</el-descriptions-item>
        </el-descriptions>
      </template>
    </el-drawer>

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
</style>
