<script setup lang="ts">
/**
 * 销售退货管理 — T-RTA (issue #531) frontend MVP.
 *
 * F006 客户反馈 (第四次会议 line 956-1037):
 * > 可能会涉及到一个退货... 售后的话申请, 有食物的话库存入库到总仓...
 * > 无食物的话就退款, 财务审批
 *
 * Backend ReturnOrderController has existed since 2026-Q1 (covers both 采购退货
 * + 销售退货 via returnType=SALES_RETURN | PURCHASE_RETURN filter). F006 coverage
 * push (PR #517 / #527) flagged this as 真 gap because no frontend view existed.
 *
 * This view filters returnType=SALES_RETURN by default (sales menu context).
 * Downstream linkage (inventory inbound on 有食物 / 财务退款 on 无食物) is
 * handled by backend ReturnOrderService.approveReturnOrder / completeReturnOrder
 * — UI just records the request + drives state transitions.
 */
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Refresh, Search, View } from '@element-plus/icons-vue';
import { formatAmount } from '@/utils/tableFormatters';
import { formatDateTime } from '@/utils/dateFormat';
import type { TableRow } from '@/types/api';
import { RowActionMenu, TableFooter } from '@/components/list';
import { computeRowActions } from '@/composables/useRowActions';
import { safePrint } from '@/api/printApi';
import { useListSummary } from '@/composables/useListSummary';
import { formatSummaryForAI } from '@/utils/aiSummaryContext';
import type { ListSummaryRequest } from '@/types/listSummary';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);

function rowActionsFor(row: TableRow) {
  return computeRowActions(
    'returnOrder',
    { status: String(row.status || ''), id: String(row.id || '') },
    { canViewPrice: canViewPrice.value }
  );
}
function handleRowActionClick(actionId: string, row: TableRow) {
  switch (actionId) {
    case 'view-detail': router.push(`/sales/returns/${row.id}`); break;
    // Sales return 复用 sales-order 模板 (后端 RBAC 仍校验 sales:read)
    case 'print-pdf': void safePrint('sales-order', factoryId.value, String(row.id), { fileName: `销售退货单_${row.returnNumber || row.id}` }); break;
    default: ElMessage.warning(`该操作暂不支持: ${actionId}`);
  }
}
function openAiForRow(row: TableRow) {
  ElMessage.info(`AIChat: returnOrder/${row.returnNumber || row.id} — 接 AiEntryDrawer 待 Day 9`);
}

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });

const filterStatus = ref('');

// U-FOOTER-1
// KPI-vs-list drift fix (2026-07): filterStatus MUST be declared BEFORE this
// computed (TDZ — see sales/shipments/list.vue Issue #716). Previously this
// was a static `{ filterConditions: {} }` so the footer "共" always showed
// the ALL-status total while loadData() sends `params.status = filterStatus`
// to /return-orders — footer and paginator would diverge as soon as a
// status filter was applied. Fix: mirror the same status filter.
const summaryRequest = computed<ListSummaryRequest>(() => ({
  filterConditions: filterStatus.value ? { status: filterStatus.value } : {},
}));
const { summary: footerSummary, loading: footerLoading } = useListSummary('returnOrder', summaryRequest);

const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'SUBMITTED', label: '已提交' },
  { value: 'APPROVED', label: '已审批' },
  { value: 'FINANCE_APPROVED', label: '财务已审' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'PROCESSING', label: '处理中' },
  { value: 'COMPLETED', label: '已完成' },
];

function statusType(s: string) {
  switch (s) {
    case 'DRAFT': return 'info';
    case 'SUBMITTED': return 'warning';
    case 'APPROVED': return 'primary';
    case 'FINANCE_APPROVED': return 'success';
    case 'REJECTED': return 'danger';
    case 'PROCESSING': return 'warning';
    case 'COMPLETED': return 'success';
    default: return 'info';
  }
}

function statusLabel(s: string) {
  const o = STATUS_OPTIONS.find((x) => x.value === s);
  return o ? o.label : s || '-';
}

onMounted(() => loadData());

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params: TableRow = {
      returnType: 'SALES_RETURN',
      page: pagination.value.page,
      size: pagination.value.size,
    };
    if (filterStatus.value) params.status = filterStatus.value;
    const res = await get(`/${factoryId.value}/return-orders`, { params });
    if (res.success && res.data) {
      tableData.value = res.data.content || res.data.list || [];
      pagination.value.total = res.data.totalElements || res.data.total || 0;
    } else if (res.success === false) {
      ElMessage.error(res.message || '加载退货单失败');
    }
  } catch { /* interceptor */ }
  finally { loading.value = false; }
}

function handlePageChange(page: number) { pagination.value.page = page; loadData(); }
function handleSizeChange(size: number) { pagination.value.size = size; pagination.value.page = 1; loadData(); }
function handleSearch() { pagination.value.page = 1; loadData(); }
function viewDetail(row: TableRow) { router.push(`/sales/returns/${row.id}`); }
</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">销售退货</span>
            <span class="data-count">共 {{ pagination.total }} 单</span>
          </div>
          <div class="header-right">
            <el-select v-model="filterStatus" placeholder="状态筛选" clearable style="width: 160px" @change="handleSearch">
              <el-option v-for="opt in STATUS_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
            <el-button :icon="Search" type="primary" @click="handleSearch">查询</el-button>
            <el-button :icon="Refresh" @click="loadData">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert type="info" show-icon :closable="false" style="margin-bottom: 12px"
                title="如何创建退货单"
                description="进入「销售订单」详情 → 点击「申请退货」按钮选择要退货的行项目。本页只列出历史退货单。" />

      <el-table :data="tableData" v-loading="loading" empty-text="暂无退货单" stripe border style="width: 100%">
        <el-table-column prop="returnNumber" label="退货单号" width="200" />
        <el-table-column label="客户" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.counterpartyName || row.customerName || row.counterpartyId || '-' }}</template>
        </el-table-column>
        <el-table-column label="源订单" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.sourceOrderNumber || row.sourceOrderId || '-' }}</template>
        </el-table-column>
        <el-table-column prop="returnDate" label="退货日期" width="120" />
        <el-table-column v-if="canViewPrice" label="退货金额" width="130" align="right">
          <template #default="{ row }">{{ formatAmount(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="原因" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.reason || '-' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" align="center" fixed="right">
          <template #default="{ row }">
            <el-button :icon="View" link type="primary" @click="viewDetail(row)">查看</el-button>
            <RowActionMenu
              :actions="rowActionsFor(row)"
              button-label="更多"
              @action-click="(id: string) => handleRowActionClick(id, row)"
              @ai-trigger="() => openAiForRow(row)"
            />
          </template>
        </el-table-column>
      </el-table>

      <TableFooter
        :stats="footerSummary?.stats ?? []"
        :loading="footerLoading"
        :show-export="false"
        @ai-analyze="() => ElMessage.info({ message: `AI 分析 (待接 SmartBI): 分析当前退货单${formatSummaryForAI(footerSummary)}`, duration: 8000, showClose: true })"
      />

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page" v-model:page-size="pagination.size"
          :total="pagination.total" :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="handlePageChange" @size-change="handleSizeChange" />
      </div>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper { height: 100%; width: 100%; display: flex; flex-direction: column; }
.page-card { flex: 1; display: flex; flex-direction: column;
  :deep(.el-card__header) { padding: 16px 20px; border-bottom: 1px solid #ebeef5; }
  :deep(.el-card__body) { flex: 1; display: flex; flex-direction: column; padding: 20px; }
}
.card-header { display: flex; justify-content: space-between; align-items: center;
  .header-left { display: flex; align-items: baseline; gap: 12px;
    .page-title { font-size: 16px; font-weight: 600; color: #303133; }
    .data-count { font-size: 13px; color: #909399; }
  }
  .header-right { display: flex; gap: 8px; align-items: center; }
}
.pagination-wrapper { display: flex; justify-content: flex-end; padding-top: 16px; border-top: 1px solid #ebeef5; margin-top: 16px; }
</style>
