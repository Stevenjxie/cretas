<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Refresh, WarningFilled } from '@element-plus/icons-vue';
import { formatAmount } from '@/utils/tableFormatters';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const router = useRouter();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);
const canWriteProcurement = computed(() => permissionStore.canWrite('procurement'));

// 🔴 fool-proof-design Rule 5 fix (2026-07-05): 应付账款是只读交易台账，之前没有任何按钮
// 能跳到付款申请（PaymentRequestServiceImpl.markPaidPurchase 三写原子已在生产生效，但从这里
// 走不到）。跳转带上供应商 + 关联采购单（若该行有），落地页自动预填打开新建弹窗。
function goRequestPayment(row: TableRow) {
  router.push({
    path: '/procurement/payment-requests',
    query: {
      open: 'create',
      supplierId: row.counterpartyId,
      poId: row.purchaseOrderId || undefined
    }
  });
}

const activeTab = ref('overview');
const loading = ref(false);

// 概览数据
const overview = ref<TableRow | null>(null);
// 交易记录
const transactions = ref<TableRow[]>([]);
const txPagination = ref({ page: 1, size: 10, total: 0 });
const txTypeFilter = ref('');
// 账龄
const agingData = ref<TableRow[]>([]);
const agingType = ref('CUSTOMER');

const txTypeMap: Record<string, { text: string; type: string }> = {
  AR_INVOICE: { text: '应收挂账', type: 'warning' },
  AR_PAYMENT: { text: '客户回款', type: 'success' },
  AR_ADJUSTMENT: { text: '应收调整', type: 'info' },
  AP_INVOICE: { text: '应付挂账', type: 'danger' },
  AP_PAYMENT: { text: '供应商付款', type: 'success' },
  AP_ADJUSTMENT: { text: '应付调整', type: 'info' },
};

const paymentMethodMap: Record<string, string> = {
  CASH: '现金', BANK_TRANSFER: '银行转账', WECHAT: '微信', ALIPAY: '支付宝',
  CHECK: '支票', CREDIT: '赊账', POS: 'POS', OTHER: '其他',
};

// 🟡 F006 采购 audit fix (Bug 3): AR/AP_ADJUSTMENT 行在 approval_status=PENDING 时
// balanceAfter 有意保持"提交前快照"不变 (dual-control, 见 ArApServiceImpl.recordAdjustment
// 注释) — 审批通过后才会被 approveAdjustment() 重算. 但本页此前完全不显示 approvalStatus,
// 用户看到"余额没变"会误以为是 bug. 加状态标签让"待审批"一目了然 (fool-proof-design Rule 2:
// 上下文必带身份信息). 非调整类型交易 approval_status 恒为 APPROVED (自动过账), 不需要显眼展示.
const approvalStatusMap: Record<string, { text: string; type: string }> = {
  PENDING: { text: '待审批', type: 'warning' },
  APPROVED: { text: '已审批', type: 'success' },
  REJECTED: { text: '已驳回', type: 'danger' },
};
function isAdjustmentRow(row: TableRow): boolean {
  return row.transactionType === 'AR_ADJUSTMENT' || row.transactionType === 'AP_ADJUSTMENT';
}

onMounted(() => {
  loadOverview();
});

watch(activeTab, (tab) => {
  if (tab === 'receivable') {
    txTypeFilter.value = 'CUSTOMER';
    txPagination.value.page = 1;
    loadTransactions();
  } else if (tab === 'payable') {
    txTypeFilter.value = 'SUPPLIER';
    txPagination.value.page = 1;
    loadTransactions();
  } else if (tab === 'aging') {
    loadAging();
  }
}, { immediate: false });

async function loadOverview() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/finance/overview`);
    if (res.success) overview.value = res.data;
    else if (res.success === false) ElMessage.error(res.message || '加载财务概览失败');
  } catch (error) {
    console.error('加载财务概览失败:', error);
    ElMessage.error('加载财务概览失败');
  }
}

async function loadTransactions() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params: TableRow = { page: txPagination.value.page, size: txPagination.value.size };
    if (txTypeFilter.value) params.counterpartyType = txTypeFilter.value;
    const res = await get(`/${factoryId.value}/finance/transactions`, { params });
    if (res.success && res.data) {
      transactions.value = res.data.content || [];
      txPagination.value.total = res.data.totalElements || 0;
    } else if (res.success === false) {
      ElMessage.error(res.message || '加载交易记录失败');
    }
  } catch { /* axios interceptor already displayed error toast */ }
  finally { loading.value = false; }
}

async function loadAging() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/finance/aging`, { params: { counterpartyType: agingType.value } });
    if (res.success) agingData.value = Array.isArray(res.data) ? res.data : [];
    else if (res.success === false) ElMessage.error(res.message || '加载账龄数据失败');
  } catch (error) {
    console.error('加载账龄数据失败:', error);
    ElMessage.error('加载账龄数据失败');
  }
}

function handleTxPageChange(page: number) { txPagination.value.page = page; loadTransactions(); }
function handleTxSizeChange(size: number) { txPagination.value.size = size; txPagination.value.page = 1; loadTransactions(); }
function handleTxTypeChange() { txPagination.value.page = 1; loadTransactions(); }
function handleAgingTypeChange() { loadAging(); }
</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span class="page-title">应收应付管理</span>
          <el-button :icon="Refresh" @click="loadOverview(); loadTransactions(); loadAging();">刷新</el-button>
        </div>
      </template>

      <el-tabs v-if="canViewPrice" v-model="activeTab">
        <!-- 概览 Tab -->
        <el-tab-pane label="财务概览" name="overview">
          <div class="stat-cards" v-if="overview">
            <el-card class="stat-card ar" shadow="hover">
              <div class="stat-label">应收总额</div>
              <div class="stat-value">{{ formatAmount(overview.totalReceivable) }}</div>
              <!-- 🟡 F006 采购 audit fix (Bug 2): 后端按交易对手(客户/供应商)聚合欠款笔数, 非单据/发票行数
                   (ar_ap_transactions 是流水式台账, 无法干净拆出"未结发票行"概念). 文案改"家"避免误导. -->
              <div class="stat-sub">{{ overview.receivableCount || 0 }} 家客户未结</div>
            </el-card>
            <el-card class="stat-card ap" shadow="hover">
              <div class="stat-label">应付总额</div>
              <div class="stat-value">{{ formatAmount(overview.totalPayable) }}</div>
              <div class="stat-sub">{{ overview.payableCount || 0 }} 家供应商未结</div>
            </el-card>
            <el-card class="stat-card net" shadow="hover">
              <div class="stat-label">净额 (应收-应付)</div>
              <div class="stat-value">{{ formatAmount((overview.totalReceivable || 0) - (overview.totalPayable || 0)) }}</div>
              <div class="stat-sub">{{ (overview.totalReceivable || 0) > (overview.totalPayable || 0) ? '净应收' : '净应付' }}</div>
            </el-card>
            <el-card class="stat-card overdue" shadow="hover">
              <div class="stat-label">逾期金额</div>
              <div class="stat-value">{{ formatAmount(overview.overdueAmount) }}</div>
              <div class="stat-sub">{{ overview.overdueCount || 0 }} 笔逾期</div>
            </el-card>
          </div>
          <el-empty v-else description="暂无数据" />
        </el-tab-pane>

        <!-- 应收 Tab -->
        <el-tab-pane label="应收账款" name="receivable">
          <div class="tab-toolbar">
            <el-button :icon="Refresh" @click="txTypeFilter = 'CUSTOMER'; loadTransactions()">刷新</el-button>
          </div>
          <el-table empty-text="暂无数据" :data="transactions" border stripe v-loading="loading">
            <el-table-column prop="transactionNumber" label="交易编号" width="150" />
            <el-table-column prop="counterpartyName" label="客户" min-width="140" show-overflow-tooltip />
            <el-table-column prop="transactionType" label="类型" width="120" align="center">
              <template #default="{ row }">
                <el-tag :type="(txTypeMap[row.transactionType]?.type) || 'info'" size="small">
                  {{ txTypeMap[row.transactionType]?.text || row.transactionType }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="amount" label="金额" width="130" align="right">
              <template #default="{ row }">{{ formatAmount(row.amount) }}</template>
            </el-table-column>
            <el-table-column prop="balanceAfter" label="余额" width="130" align="right">
              <template #default="{ row }">
                {{ formatAmount(row.balanceAfter) }}
                <el-tooltip v-if="isAdjustmentRow(row) && row.approvalStatus === 'PENDING'"
                  content="调整待审批, 余额为提交前快照; 审批通过后才会更新" placement="top">
                  <el-icon style="color:#E6A23C;margin-left:4px;vertical-align:middle"><WarningFilled /></el-icon>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column v-if="transactions.some((r) => isAdjustmentRow(r))" label="审批状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag v-if="isAdjustmentRow(row)" :type="approvalStatusMap[row.approvalStatus]?.type || 'info'" size="small">
                  {{ approvalStatusMap[row.approvalStatus]?.text || row.approvalStatus }}
                </el-tag>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column prop="paymentMethod" label="支付方式" width="110" align="center">
              <template #default="{ row }">{{ row.paymentMethod ? paymentMethodMap[row.paymentMethod] || row.paymentMethod : '-' }}</template>
            </el-table-column>
            <el-table-column prop="transactionDate" label="日期" width="120" />
            <el-table-column prop="dueDate" label="到期日" width="120" />
            <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>

        <!-- 应付 Tab -->
        <el-tab-pane label="应付账款" name="payable">
          <el-table :data="transactions" border stripe v-loading="loading">
            <el-table-column prop="transactionNumber" label="交易编号" width="150" />
            <el-table-column prop="counterpartyName" label="供应商" min-width="140" show-overflow-tooltip />
            <el-table-column prop="transactionType" label="类型" width="120" align="center">
              <template #default="{ row }">
                <el-tag :type="(txTypeMap[row.transactionType]?.type) || 'info'" size="small">
                  {{ txTypeMap[row.transactionType]?.text || row.transactionType }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="amount" label="金额" width="130" align="right">
              <template #default="{ row }">{{ formatAmount(row.amount) }}</template>
            </el-table-column>
            <el-table-column prop="balanceAfter" label="余额" width="130" align="right">
              <template #default="{ row }">
                {{ formatAmount(row.balanceAfter) }}
                <el-tooltip v-if="isAdjustmentRow(row) && row.approvalStatus === 'PENDING'"
                  content="调整待审批, 余额为提交前快照; 审批通过后才会更新" placement="top">
                  <el-icon style="color:#E6A23C;margin-left:4px;vertical-align:middle"><WarningFilled /></el-icon>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column v-if="transactions.some((r) => isAdjustmentRow(r))" label="审批状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag v-if="isAdjustmentRow(row)" :type="approvalStatusMap[row.approvalStatus]?.type || 'info'" size="small">
                  {{ approvalStatusMap[row.approvalStatus]?.text || row.approvalStatus }}
                </el-tag>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column prop="paymentMethod" label="支付方式" width="110" align="center">
              <template #default="{ row }">{{ row.paymentMethod ? paymentMethodMap[row.paymentMethod] || row.paymentMethod : '-' }}</template>
            </el-table-column>
            <el-table-column prop="transactionDate" label="日期" width="120" />
            <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
            <!-- 🔴 fool-proof-design Rule 5 fix: 之前应付账款是纯只读台账，供应商欠款只能看
                 不能付 — 加"去申请付款"跳到 SP6 付款申请流程（同一供应商预填，进入现有
                 create→submit→finance-approve→mark-paid 状态机，不重复造轮子）。 -->
            <el-table-column label="操作" width="120" align="center" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="canWriteProcurement && row.counterpartyId"
                  type="primary"
                  link
                  size="small"
                  @click="goRequestPayment(row)"
                >去申请付款</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 账龄 Tab -->
        <el-tab-pane label="账龄分析" name="aging">
          <div class="tab-toolbar">
            <el-radio-group v-model="agingType" @change="handleAgingTypeChange">
              <el-radio-button value="CUSTOMER">应收账龄</el-radio-button>
              <el-radio-button value="SUPPLIER">应付账龄</el-radio-button>
            </el-radio-group>
          </div>
          <el-table :data="agingData" border stripe>
            <el-table-column prop="counterpartyName" :label="agingType === 'CUSTOMER' ? '客户' : '供应商'" min-width="160" />
            <el-table-column prop="totalBalance" label="总余额" width="130" align="right">
              <template #default="{ row }">{{ formatAmount(row.totalBalance) }}</template>
            </el-table-column>
            <el-table-column prop="current" label="未到期" width="110" align="right">
              <template #default="{ row }">{{ formatAmount(row.current) }}</template>
            </el-table-column>
            <el-table-column prop="days1to30" label="1-30天" width="110" align="right">
              <template #default="{ row }">{{ formatAmount(row.days1to30) }}</template>
            </el-table-column>
            <el-table-column prop="days31to60" label="31-60天" width="110" align="right">
              <template #default="{ row }">{{ formatAmount(row.days31to60) }}</template>
            </el-table-column>
            <el-table-column prop="days61to90" label="61-90天" width="110" align="right">
              <template #default="{ row }">{{ formatAmount(row.days61to90) }}</template>
            </el-table-column>
            <el-table-column prop="days91to180" label="91-180天" width="110" align="right">
              <template #default="{ row }">{{ formatAmount(row.days91to180) }}</template>
            </el-table-column>
            <el-table-column prop="over180" label=">180天" width="110" align="right">
              <template #default="{ row }">
                <span :style="{ color: row.over180 > 0 ? '#f56c6c' : '' }">{{ formatAmount(row.over180) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>

      <el-empty v-else description="您没有查看价格/财务数据的权限" />

      <!-- 交易记录分页 -->
      <div v-if="canViewPrice && (activeTab === 'receivable' || activeTab === 'payable')" class="pagination-wrapper">
        <el-pagination v-model:current-page="txPagination.page" v-model:page-size="txPagination.size"
          :page-sizes="[10, 20, 50]" :total="txPagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handleTxPageChange" @size-change="handleTxSizeChange" />
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
  .page-title { font-size: 16px; font-weight: 600; color: #303133; }
}
.stat-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}
.stat-card {
  text-align: center;
  padding: 20px;
  border-radius: 8px;
  .stat-label { font-size: 13px; color: #909399; margin-bottom: 8px; }
  .stat-value { font-size: 24px; font-weight: 700; margin-bottom: 4px; }
  .stat-sub { font-size: 12px; color: #b1b3b8; }
  &.ar .stat-value { color: #e6a23c; }
  &.ap .stat-value { color: #f56c6c; }
  &.net .stat-value { color: #409eff; }
  &.overdue .stat-value { color: #f56c6c; }
}
.tab-toolbar { display: flex; gap: 12px; margin-bottom: 16px; }
.pagination-wrapper { display: flex; justify-content: flex-end; padding-top: 16px; border-top: 1px solid #ebeef5; margin-top: 16px; }
</style>
