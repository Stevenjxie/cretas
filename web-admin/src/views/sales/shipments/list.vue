<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Search } from '@element-plus/icons-vue';
import { formatDateTimeCell } from '@/utils/tableFormatters';
import type { TableRow } from '@/types/api';
import { RowActionMenu, TableFooter } from '@/components/list';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import CreateReturnOrderDialog from '@/components/dialog/CreateReturnOrderDialog.vue';
import { computeRowActions } from '@/composables/useRowActions';
import { safePrint } from '@/api/printApi';
import { useListSummary } from '@/composables/useListSummary';
import { formatSummaryForAI } from '@/utils/aiSummaryContext';
import type { ListSummaryRequest } from '@/types/listSummary';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const router = useRouter();
const factoryId = computed(() => authStore.factoryId);

// Issue 1 (仓管走查 2026-07): 引导到会扣减成品库存的正式发货流程 (销售订单 → 创建发货单 → 仓库确认).
function goToSalesOrders(): void {
  router.push('/sales/orders');
}
const canViewPrice = computed(() => permissionStore.canViewPrice);

function rowActionsFor(row: TableRow) {
  return computeRowActions(
    'whOutbound',
    { status: String(row.status || 'SHIPPED'), id: String(row.id || '') },
    { canViewPrice: canViewPrice.value }
  );
}
// 退货 dialog state (#860 follow-up — wires existing backend ReturnOrderController).
// Shipments 是 single-product 记录, items list 只有 1 行 (productName + quantity).
const returnDialogVisible = ref(false);
const returnDialogRow = ref<TableRow | null>(null);
const returnDialogItems = computed(() => {
  const row = returnDialogRow.value;
  if (!row) return [];
  return [
    {
      id: String(row.id),
      // Shipment doesn't carry productTypeId in the list response; we send
      // null + itemName so backend records the return line by name.
      materialTypeId: null as string | null,
      productTypeId: row.productTypeId ? String(row.productTypeId) : null,
      itemName: String(row.productName || '-'),
      unitPrice: Number(row.unitPrice) || 0,
      maxQuantity: Number(row.quantity) || 0,
      batchNumber: row.batchNumber ? String(row.batchNumber) : null,
    },
  ];
});
function openReturnDialog(row: TableRow): void {
  if (!row.customerId) {
    ElMessage.warning('该出货记录缺少客户信息, 无法发起退货.');
    return;
  }
  returnDialogRow.value = row;
  returnDialogVisible.value = true;
}
function handleReturnSuccess(): void {
  returnDialogVisible.value = false;
  returnDialogRow.value = null;
  void loadData();
}

function handleRowActionClick(actionId: string, row: TableRow) {
  switch (actionId) {
    case 'view-detail': handleView(row); break;
    // Shipments 复用 sales-order 模板 — 出货关联的销售单 PDF 是客户期望
    case 'print-pdf': void safePrint('sales-order', factoryId.value, String(row.salesOrderId || row.id), { fileName: `出货单_${row.id}` }); break;
    case 'return': openReturnDialog(row); break;
    default: ElMessage.warning(`该操作暂不支持: ${actionId}`);
  }
}
function openAiForRow(row: TableRow) {
  ElMessage.info(`AIChat: shipment/${row.id} — 接 AiEntryDrawer 待 Day 9`);
}

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const customerMap = ref<Record<string, string>>({});
const searchKeyword = ref('');
const dateRange = ref<[string, string] | null>(null);
const statusFilter = ref('');

// U-FOOTER-1
// Issue #716 fix — statusFilter MUST be declared BEFORE this computed because
// useListSummary's watch fires immediately and evaluates summaryRequest.value
// synchronously, which hits TDZ on statusFilter.value if declared after.
// E2E observed: ReferenceError "Cannot access 'b' before initialization"
// in Vue ReactiveEffect (mis-attributed to echarts in stack via chunk co-location).
// Sibling returns/list.vue uses static {} filterConditions so doesn't reproduce.
const summaryRequest = computed<ListSummaryRequest>(() => ({
  filterConditions: statusFilter.value ? { status: statusFilter.value } : {},
}));
const { summary: footerSummary, loading: footerLoading } = useListSummary('shipment', summaryRequest);

onMounted(() => {
  loadData();
  loadCustomers();
});

async function loadCustomers() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/customers`, { params: { page: 1, size: 100 } });
    if (res.success && res.data) {
      const list = res.data.content || [];
      const map: Record<string, string> = {};
      list.forEach((c: TableRow) => { if (c.id && c.name) map[String(c.id)] = String(c.name); });
      customerMap.value = map;
    } else if (res.success === false) {
      ElMessage.error(res.message || '加载客户数据失败');
    }
  } catch { /* axios interceptor already displayed error toast */ }
}

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const params: TableRow = {
      page: pagination.value.page - 1,
      size: pagination.value.size,
    };
    const kw = searchKeyword.value.trim();
    if (kw) params.keyword = kw;
    if (statusFilter.value) params.status = statusFilter.value;
    const response = await get(`/${factoryId.value}/shipments`, { params });
    if (response.success && response.data) {
      let rows = response.data.content || [];
      // Client-side date filter (Apr 21 2026) — backend supports status
      // and keyword server-side; daterange filtered locally until backend
      // accepts startDate/endDate params.
      if (dateRange.value && dateRange.value[0] && dateRange.value[1]) {
        const [from, to] = dateRange.value;
        rows = rows.filter((r: TableRow) => {
          const d = String(r.shipmentDate || r.createdAt || '').slice(0, 10);
          return d && d >= from && d <= to;
        });
      }
      tableData.value = rows;
      pagination.value.total = response.data.totalElements || 0;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载出货记录失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function handleSearch() { pagination.value.page = 1; loadData(); }
function handleSearchClear() { searchKeyword.value = ''; handleSearch(); }
function handleReset() {
  searchKeyword.value = '';
  statusFilter.value = '';
  dateRange.value = null;
  handleSearch();
}

// ==================== View ====================
const viewDialogVisible = ref(false);
const viewRecord = ref<TableRow | null>(null);

function handleView(row: TableRow) {
  viewRecord.value = row;
  viewDialogVisible.value = true;
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    PENDING: 'info',
    SHIPPED: 'warning',
    DELIVERED: 'success',
    RETURNED: 'danger',
    CANCELLED: 'danger'
  };
  return map[status?.toUpperCase()] || 'info';
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    PENDING: '待出货',
    SHIPPED: '已发货',
    DELIVERED: '已送达',
    RETURNED: '已退货',
    CANCELLED: '已取消'
  };
  return map[status?.toUpperCase()] || status;
}

</script>

<template>
  <div class="page-container">
    <!--
      仓管现场走查 (2026-07): 在此「新建出货」成功后, 成品库存 (销售 → 成品库存) 可用量不变,
      误导仓管以为已发货扣了库存。此页现只读展示遗留 ShipmentRecord 历史；真正扣减成品库存的
      正式发货走「销售订单 → 创建发货单 → 仓库确认」(FEFO 批次分配 + 扣减)。
    -->
    <ConceptDisambiguationAlert
      here-name="历史手工出货记录（只读）"
      here="旧台账已停止新增和修改，仅保留历史查询"
      other="要真正扣减成品库存的正式发货 (按批次 FEFO 分配)"
      other-name="销售订单 → 创建发货单"
      other-path="/sales/orders"
      consequence="请勿依据旧台账判断库存或继续发货"
    />
    <el-card>
      <template #header>
        <div class="card-header">
          <span>历史手工出货记录<span style="font-size:12px;color:#e6a23c;font-weight:400;margin-left:8px">(只读)</span></span>
          <div style="display: flex; align-items: center; gap: 12px; flex-wrap: wrap;">
            <el-input
              v-model="searchKeyword"
              placeholder="搜索出货单号 / 产品"
              clearable
              :prefix-icon="Search"
              style="width: 240px;"
              @keyup.enter="handleSearch"
              @clear="handleSearchClear"
            />
            <el-date-picker
              v-model="dateRange"
              type="daterange"
              range-separator="至"
              start-placeholder="出货起始"
              end-placeholder="出货结束"
              value-format="YYYY-MM-DD"
              style="width: 280px;"
              @change="handleSearch"
            />
            <!-- 同一 key-mismatch bug class: 后端 ShipmentRecordService 只 setStatus 小写
                 pending/shipped/delivered/returned/cancelled ('草稿'/DRAFT 从未真实存在),
                 findByFactoryIdAndStatus 是精确匹配 (PG = 大小写敏感) — 之前传大写值筛选
                 永远 0 结果。改传真实小写值。 -->
            <el-select v-model="statusFilter" placeholder="状态" clearable style="width: 140px" @change="handleSearch">
              <el-option label="待发货" value="pending" />
              <el-option label="已发货" value="shipped" />
              <el-option label="已签收" value="delivered" />
              <el-option label="已退货" value="returned" />
              <el-option label="已取消" value="cancelled" />
            </el-select>
            <el-button type="primary" @click="handleSearch">搜索</el-button>
            <el-button @click="handleReset">重置</el-button>
            <el-button type="primary" @click="goToSalesOrders">去销售订单创建发货单</el-button>
          </div>
        </div>
      </template>

      <el-table :data="tableData" v-loading="loading" empty-text="暂无数据" stripe border>
        <el-table-column prop="shipmentNumber" label="出货单号" width="160" />
        <el-table-column label="客户名称">
          <template #default="{ row }">{{ customerMap[row.customerId] || row.customerId || '-' }}</template>
        </el-table-column>
        <el-table-column prop="productName" label="产品" />
        <el-table-column prop="quantity" label="数量" />
        <el-table-column prop="status" label="状态">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="出货时间" width="180" :formatter="formatDateTimeCell" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="handleView(row)">查看</el-button>
            <RowActionMenu
              :actions="rowActionsFor(row)"
              button-label="更多"
              @action-click="(id: string) => handleRowActionClick(id, row)"
              @ai-trigger="() => openAiForRow(row)"
            />
          </template>
        </el-table-column>
      </el-table>

      <!-- 查看详情 -->
      <el-dialog v-model="viewDialogVisible" title="出货详情" width="500px" destroy-on-close>
        <el-descriptions v-if="viewRecord" :column="1" border>
          <el-descriptions-item label="出货单号">{{ viewRecord.shipmentNumber || '-' }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ customerMap[viewRecord.customerId] || viewRecord.customerId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="产品">{{ viewRecord.productName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="数量">{{ viewRecord.quantity || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="getStatusType(viewRecord.status)">{{ getStatusText(viewRecord.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="出货时间">{{ viewRecord.createdAt || '-' }}</el-descriptions-item>
        </el-descriptions>
      </el-dialog>

      <TableFooter
        :stats="footerSummary?.stats ?? []"
        :loading="footerLoading"
        :show-export="false"
        @ai-analyze="() => ElMessage.info({ message: `AI 分析 (待接 SmartBI): 分析当前出货${formatSummaryForAI(footerSummary, { filter: { status: statusFilter } })}`, duration: 8000, showClose: true })"
      />

      <el-pagination
        v-model:current-page="pagination.page"
        :page-size="pagination.size"
        :total="pagination.total"
        layout="total, prev, pager, next"
        @current-change="handlePageChange"
        class="pagination"
      />

      <!-- #860 follow-up — 退货 dialog. Wires existing ReturnOrderController. -->
      <CreateReturnOrderDialog
        v-if="returnDialogRow"
        v-model="returnDialogVisible"
        :factory-id="factoryId"
        return-type="SALES_RETURN"
        :source-order-id="String(returnDialogRow.id)"
        :source-order-number="String(returnDialogRow.shipmentNumber || returnDialogRow.id)"
        :counterparty-id="String(returnDialogRow.customerId || '')"
        :counterparty-name="customerMap[String(returnDialogRow.customerId || '')] || String(returnDialogRow.customerId || '-')"
        :items="returnDialogItems"
        @success="handleReturnSuccess"
      />
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.page-container {
  padding: 20px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.pagination {
  margin-top: 20px;
  justify-content: flex-end;
}
</style>
