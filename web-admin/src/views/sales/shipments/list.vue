<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage, type FormInstance } from 'element-plus';
import { Plus, Search } from '@element-plus/icons-vue';
import { formatDateTimeCell } from '@/utils/tableFormatters';
import type { TableRow } from '@/types/api';
import { RowActionMenu, TableFooter } from '@/components/list';
import CreateReturnOrderDialog from '@/components/dialog/CreateReturnOrderDialog.vue';
import { computeRowActions } from '@/composables/useRowActions';
import { safePrint } from '@/api/printApi';
import { useListSummary } from '@/composables/useListSummary';
import { formatSummaryForAI } from '@/utils/aiSummaryContext';
import type { ListSummaryRequest } from '@/types/listSummary';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('sales'));
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
    default: ElMessage.info(`Action: ${actionId}`);
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
    CANCELLED: 'danger'
  };
  return map[status?.toUpperCase()] || 'info';
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    PENDING: '待出货',
    SHIPPED: '已发货',
    DELIVERED: '已送达',
    CANCELLED: '已取消'
  };
  return map[status?.toUpperCase()] || status;
}

// ==================== Create ====================
const createDialogVisible = ref(false);
const submitting = ref(false);
const formRef = ref<FormInstance>();
const customerOptions = ref<Array<{ id: string; name: string }>>([]);
const emptyForm = () => ({
  customerId: '',
  productName: '',
  quantity: 1,
  unit: 'kg',
  unitPrice: 0,
  shipmentDate: new Date().toISOString().slice(0, 10),
  shippingAddress: '',
  trackingNumber: '',
  notes: ''
});
const createForm = ref(emptyForm());
const createFormRules = {
  customerId: [{ required: true, message: '请选择客户', trigger: 'change' }],
  productName: [{ required: true, message: '请输入产品名称', trigger: 'blur' }],
  quantity: [{ required: true, message: '请输入数量', trigger: 'blur' }],
  unit: [{ required: true, message: '请输入单位', trigger: 'blur' }],
  shipmentDate: [{ required: true, message: '请选择出货日期', trigger: 'change' }]
};

async function loadCustomerOptions() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/customers`, { params: { page: 1, size: 200 } });
    if (res.success && res.data) {
      const list = (res.data.content || []) as Array<TableRow>;
      customerOptions.value = list
        .filter(c => c.id && c.name)
        .map(c => ({ id: String(c.id), name: String(c.name) }));
    }
  } catch {
    // fall back to empty select
  }
}

function handleCreate() {
  createForm.value = emptyForm();
  createDialogVisible.value = true;
  if (customerOptions.value.length === 0) loadCustomerOptions();
}

async function submitCreateForm() {
  if (formRef.value) {
    try { await formRef.value.validate(); } catch { return; }
  }
  submitting.value = true;
  try {
    const payload: TableRow = { ...createForm.value };
    if (!payload.unitPrice) delete payload.unitPrice;
    const res = await post(`/${factoryId.value}/shipments`, payload);
    if (res.success) {
      ElMessage.success('出货记录已创建');
      createDialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(res.message || '创建失败');
    }
  } catch (e) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('Create shipment failed:', e);
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>出货记录管理</span>
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
            <el-select v-model="statusFilter" placeholder="状态" clearable style="width: 140px" @change="handleSearch">
              <el-option label="草稿" value="DRAFT" />
              <el-option label="待发货" value="PENDING" />
              <el-option label="已发货" value="SHIPPED" />
              <el-option label="已签收" value="DELIVERED" />
              <el-option label="已取消" value="CANCELLED" />
            </el-select>
            <el-button type="primary" @click="handleSearch">搜索</el-button>
            <el-button @click="handleReset">重置</el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleCreate">新建出货</el-button>
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

      <!-- 新建出货 -->
      <el-dialog v-model="createDialogVisible" title="新建出货" width="560px" :close-on-click-modal="false" destroy-on-close>
        <el-form ref="formRef" :model="createForm" :rules="createFormRules" label-width="100px">
          <el-form-item label="客户" prop="customerId">
            <el-select v-model="createForm.customerId" filterable placeholder="选择客户" style="width: 100%">
              <el-option v-for="c in customerOptions" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="产品名称" prop="productName">
            <el-input v-model="createForm.productName" placeholder="请输入产品名称" />
          </el-form-item>
          <el-form-item label="数量" prop="quantity">
            <el-input-number v-model="createForm.quantity" :min="0.01" :precision="2" :step="1" />
          </el-form-item>
          <el-form-item label="单位" prop="unit">
            <el-input v-model="createForm.unit" placeholder="kg / 件 / 箱" style="width: 140px" />
          </el-form-item>
          <el-form-item v-if="canViewPrice" label="单价">
            <el-input-number v-model="createForm.unitPrice" :min="0" :precision="2" :step="1" />
          </el-form-item>
          <el-form-item label="出货日期" prop="shipmentDate">
            <el-date-picker v-model="createForm.shipmentDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width: 200px" />
          </el-form-item>
          <el-form-item label="发货地址">
            <el-input v-model="createForm.shippingAddress" placeholder="请输入发货地址" />
          </el-form-item>
          <el-form-item label="物流单号">
            <el-input v-model="createForm.trackingNumber" placeholder="可选" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="createForm.notes" type="textarea" :rows="2" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button :disabled="submitting" @click="createDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="submitCreateForm">确定</el-button>
        </template>
      </el-dialog>

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
