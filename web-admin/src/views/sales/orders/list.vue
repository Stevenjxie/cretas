<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useBusinessMode } from '@/composables/useBusinessMode';
import { get, post, put, del } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh, Search, ChatDotRound, QuestionFilled } from '@element-plus/icons-vue';
import AiEntryDrawer from '@/components/ai-entry/AiEntryDrawer.vue';
import AuditLogDrawer from '@/components/AuditLogDrawer.vue';
import { SALES_ORDER_CONFIG } from '@/components/ai-entry/types';
import { WorkflowBar } from '@/components/workflow';
import { useWorkflowStats } from '@/composables/useWorkflowStats';
import { getBucketPrimaryStatus, getBucketLabel } from '@/types/workflow';
import { formatAmount } from '@/utils/tableFormatters';
import { RowActionMenu, ViewModeSwitcher, GridView, KanbanView, TimelinePlaceholder, CalendarPlaceholder, InlineRowIcons, RowMarkerCell } from '@/components/list';
// Sprint 6 W3-A — inline 3-chip link counter (文件 / 图片 / 合同).
import LinkChipCell from '@/components/list/LinkChipCell.vue';
import { useLinkChipCounts } from '@/composables/useLinkChipCounts';
import { CreateModeSelector, BatchCreateDialog, QuickCreateDialog, BomExpansionDialog } from '@/components/dialog';
// PR #872 (#860 follow-up) — 转发 / 分享链接 dialog.
import ForwardShareDialog from '@/components/dialog/ForwardShareDialog.vue';
import request from '@/api/request';
import type { ViewMode } from '@/types/viewMode';
import type { CreateMode } from '@/types/createMode';
import type { InlineIconId } from '@/types/inlineIcons';
import type { RowMarkerColor } from '@/types/rowMarker';
import { computeRowActions } from '@/composables/useRowActions';
import { safePrint } from '@/api/printApi';
import TaxGroupInvoiceDialog from './components/TaxGroupInvoiceDialog.vue';
import CanvasDynamicFields from '@/components/canvas/CanvasDynamicFields.vue';
import CanvasAwareWrapper from '@/components/canvas/CanvasAwareWrapper.vue';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import { getFinanceSummary, type FinanceSummary } from '@/api/smartbi/gold';
import { copySalesOrder } from '@/api/orderCopy';
import type { TableRow } from '@/types/api';
import { TableFooter } from '@/components/list';
import { useListSummary } from '@/composables/useListSummary';
import { formatSummaryForAI } from '@/utils/aiSummaryContext';
import type { ListSummaryRequest } from '@/types/listSummary';

// G1: 税率分组开票对话框 (客户原话 2645-2660s)
// Sprint 4 W2 S-INVOICE-CLIENT-1: defaultInvoiceType 字段从 SO 行带过来 (后端在 SO 创建时已 prefill 自 customer)
const taxGroupInvoiceVisible = ref(false);
const taxGroupInvoiceOrder = ref<{
  id: string;
  orderNumber: string;
  customerName: string;
  totalAmount: number | string;
  defaultInvoiceType: string;
}>({
  id: '', orderNumber: '', customerName: '', totalAmount: 0, defaultInvoiceType: '',
});
function openTaxGroupInvoice(row: TableRow) {
  taxGroupInvoiceOrder.value = {
    id: String(row.id || ''),
    orderNumber: String(row.orderNumber || ''),
    customerName: String(row.customerName || ''),
    totalAmount: (row.totalAmount as number | string) ?? 0,
    defaultInvoiceType: String(row.defaultInvoiceType || ''),
  };
  taxGroupInvoiceVisible.value = true;
}

// Quick action dialogs
const deliveryDialogVisible = ref(false);
const deliveryForm = ref<Record<string, any>>({ orderId: '', customerId: '', deliveryDate: '', items: [], notes: '' });

const invoiceDialogVisible = ref(false);
const invoiceForm = ref({ orderId: '', counterpartyId: '', amount: 0, notes: '' });

const paymentDialogVisible = ref(false);
const paymentForm = ref({ orderId: '', counterpartyId: '', amount: 0, paymentMethod: 'BANK_TRANSFER', notes: '' });

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const { label } = useBusinessMode();
const factoryId = computed(() => authStore.factoryId);
// Apr 24 2026 P1-10: restaurant tenants 用 POS 账单, 无传统 B2B SO → 显提示代替空表混乱
const isRestaurantTenant = computed(() => authStore.factoryType === 'RESTAURANT');
const canWrite = computed(() => permissionStore.canWrite('sales'));

const canViewPrice = computed(() => permissionStore.canViewPrice);

// U-VIEW-1 (Sprint 4 Wave 2 Chat L) — view-mode switcher (5 modes).
// Persistence handled by ViewModeSwitcher via route.name + localStorage.
const viewMode = ref<ViewMode>('table');
const kanbanColumns = computed(() =>
  Object.entries(statusMap).map(([status, v]: [string, { text: string }]) => ({ status, label: v.text }))
);

// U-NEW-1 — create-mode selector. Pre-dialog presenting normal/quick/batch/bom modes.
// Sprint 4 W2 Chat L shipped: normal + batch. P1 #58 (this session): quick + bom finished.
const createModeSelectorVisible = ref(false);
const batchCreateVisible = ref(false);
const quickCreateVisible = ref(false);
const bomCreateVisible = ref(false);
function openCreateModeSelector(): void {
  createModeSelectorVisible.value = true;
}
async function handleCreateModeSelected(mode: CreateMode): Promise<void> {
  if (mode === 'normal') {
    await openCreateDialog();
  } else if (mode === 'batch') {
    // Ensure dropdowns are warm before showing batch dialog.
    await Promise.all([loadCustomers(), loadProducts(), loadSalesEmployees()]);
    batchCreateVisible.value = true;
  } else if (mode === 'quick') {
    // P1 #58: minimal-field consecutive entry (customer + delivery date only)
    await loadCustomers();
    quickCreateVisible.value = true;
  } else if (mode === 'bom') {
    // P1 #58: parent SO + nested child lines expanded from a product template.
    await Promise.all([loadCustomers(), loadProducts()]);
    bomCreateVisible.value = true;
  }
}

// P1 #58 — Quick create (一维): single-row minimal-fields with consecutive entry.
interface QuickSalesOrderRow {
  customerId: string;
  requiredDeliveryDate: string;
  remark: string;
}
function quickSalesOrderFactory(): QuickSalesOrderRow {
  return { customerId: '', requiredDeliveryDate: '', remark: '' };
}
async function submitQuickSalesOrder(_row: QuickSalesOrderRow): Promise<void> {
  // P0 hotfix 2026-05-22: 销售订单后端要求至少 1 项明细 (SalesOrderService.createOrder
  // 校验 items.isEmpty → 400 "订单行项目不能为空"). Quick 模式 (P1 #58) 设计为 header-only
  // 录入, 跟 SO 强制 items 契约冲突 → 用户提交即报错. 改 fool-proof Rule 1 预先告知 +
  // 引导切到 BOM/标准模式 (它们有 items 编辑 UI).
  // Per fool-proof-design.md Rule 5 — 不让用户卡死, 给 next action.
  throw new Error('销售订单需要至少 1 项明细 — 快速模式不支持 header-only 创建。请改用 "BOM 展开" 或 "标准新建" 模式来填写明细行。');
}

// P1 #58 — BOM expansion (BOM 展开): parent SO + child items from selected product template.
interface BomSalesOrderParent {
  customerId: string;
  requiredDeliveryDate: string;
  remark: string;
}
interface BomSalesOrderChild {
  productId: string;
  productName: string;
  quantity: number | string;
  unit: string;
  unitPrice: number | string;
}
function bomSalesOrderParentFactory(): BomSalesOrderParent {
  return { customerId: '', requiredDeliveryDate: '', remark: '' };
}
// Each "BOM template" for sales = a product configuration with default qty/unit/price.
const bomSalesTemplates = computed(() =>
  (products.value || []).map((p) => ({
    id: String(p.id || ''),
    name: String(p.name || p.code || ''),
    description: (p.code ? `编码 ${p.code}` : ''),
  }))
);
async function expandBomSalesTemplate(productId: string): Promise<BomSalesOrderChild[]> {
  const tpl = products.value.find((p) => String(p.id) === productId);
  if (!tpl) return [];
  // Sales SO BOM = single product line by default. Caller can manually
  // add more rows via the dialog's "手动添加行" button.
  return [
    {
      productId: String(tpl.id || ''),
      productName: String(tpl.name || ''),
      quantity: 1,
      unit: String((tpl as Record<string, unknown>).unit || 'kg'),
      unitPrice: Number((tpl as Record<string, unknown>).price ?? 0) || 0,
    },
  ];
}
async function submitBomSalesOrder(parent: BomSalesOrderParent, children: BomSalesOrderChild[]): Promise<void> {
  if (!parent.customerId) {
    throw new Error('请选择客户');
  }
  if (!children.length) {
    throw new Error('请至少添加 1 项明细');
  }
  const payload = {
    customerId: parent.customerId,
    salesperson: '',
    requiredDeliveryDate: parent.requiredDeliveryDate || null,
    remark: parent.remark || '',
    shippingIncluded: false,
    shippingFee: 0,
    extraFees: [],
    items: children.map((c) => ({
      productId: c.productId,
      productName: c.productName,
      quantity: Number(c.quantity) || 0,
      unit: c.unit || 'kg',
      unitPrice: Number(c.unitPrice) || 0,
    })),
    customFields: {},
  };
  const res = await post(`/${factoryId.value}/sales/orders`, payload);
  if (!res?.success) {
    throw new Error(res?.message || '提交失败');
  }
  await loadData();
}
// U-ICON-1 (Sprint 4 Wave 2 Chat L) — inline 7-icon hover toolbar handler.
async function handleInlineIconClick(id: InlineIconId, row: TableRow): Promise<void> {
  switch (id) {
    case 'copy':
      handleRowActionClick('copy', row);
      break;
    case 'mark':
      // U-MARKER-1 (Sprint 4 Wave 2 Chat L) — open the standalone marker cell.
      // The marker dot in the "标记" column is the primary entry; the icon here
      // is a fallback bringing visual parity with the 7-icon palette per brief.
      ElMessage.info(`点击行末色点选择标记 (订单 ${row.orderNumber})`);
      break;
    case 'lock':
      handleRowActionClick('lock', row);
      break;
    case 'forward':
      // PR #872 (#860 follow-up): generate share link + clipboard URL.
      forwardEntityId.value = String(row.id || '');
      forwardEntityLabel.value = String(row.orderNumber || row.id || '');
      forwardDialogVisible.value = true;
      break;
    case 'delete':
      // T129: single confirm lives inside handleDeleteOrder — no double-confirm here
      await handleDeleteOrder(row);
      break;
    case 'audit':
      // PR #861: open AuditLogDrawer scoped to this row. Backend
      // OperationLogController filters by entityType + entityId.
      auditEntityId.value = String(row.id || '');
      auditEntityLabel.value = String(row.orderNumber || row.id || '');
      auditDrawerVisible.value = true;
      break;
  }
}

// U-MARKER-1 (Sprint 4 Wave 2 Chat L) — PATCH marker color to backend.
async function handleMarkerSelect(row: TableRow, color: RowMarkerColor | null): Promise<void> {
  try {
    const res = await request.patch(`/${factoryId.value}/markers/sales-order/${row.id}`, {
      color,
    });
    if (res?.data?.success) {
      // Optimistic local update so the dot reflects new state without refetch.
      (row as TableRow & { markerColor?: string | null }).markerColor = color;
      ElMessage.success(color ? `已标记为 ${color}` : '已清除标记');
    } else {
      throw new Error(res?.data?.message || '标记失败');
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '标记请求失败';
    ElMessage.error(msg);
  }
}

function batchOrderFactory(): { customerId: string; salesperson: string; requiredDeliveryDate: string; remark: string } {
  return { customerId: '', salesperson: '', requiredDeliveryDate: '', remark: '' };
}
async function submitBatchOrders(orders: Array<{ customerId: string; salesperson: string; requiredDeliveryDate: string; remark: string }>): Promise<void> {
  const created: string[] = [];
  for (const order of orders) {
    if (!order.customerId) continue;
    const payload = {
      customerId: order.customerId,
      salesperson: order.salesperson || '',
      requiredDeliveryDate: order.requiredDeliveryDate || null,
      remark: order.remark || '',
      shippingIncluded: false,
      shippingFee: 0,
      extraFees: [],
      items: [],
      customFields: {},
    };
    const res = await post(`/${factoryId.value}/sales/orders`, payload);
    if (res?.success) created.push(String(res.data?.orderNumber || res.data?.id || ''));
  }
  if (!created.length) {
    throw new Error('未能创建任何订单（请确认每行至少填写客户）');
  }
  await loadData();
}

/** UX-A2: secondary-action dropdown ("操作 ▾") shown last in row toolbar. */
function rowActionsFor(row: TableRow) {
  return computeRowActions(
    'salesOrder',
    { status: String(row.status || ''), id: String(row.id || '') },
    { canViewPrice: canViewPrice.value }
  );
}
function handleRowActionClick(actionId: string, row: TableRow) {
  switch (actionId) {
    case 'view-detail': goDetail(String(row.id)); break;
    case 'edit': handleEdit(row); break;
    case 'submit': case 'approve': handleAction(String(row.id), 'confirm'); break;
    case 'cancel': handleAction(String(row.id), 'cancel'); break;
    case 'print-pdf': void safePrint('sales-order', factoryId.value, String(row.id), { fileName: `销售订单_${row.orderNumber || row.id}` }); break;
    case 'copy': void handleCopyOrder(row); break;
    case 'delete': void handleDeleteOrder(row); break;
    case 'convert-to-production': ElMessage.info('转生产任务 (待 Track E N31 集成)'); break;
    default: ElMessage.info(`Action: ${actionId}`);
  }
}

// #860 follow-up (2026-05-18): 复制销售订单 → 新草稿. 后端复用客户/品项/价格,
// 不复制审批/发货/收款状态. 错误由 axios interceptor sticky toast 显示.
async function handleCopyOrder(row: TableRow): Promise<void> {
  const orderNumber = String(row.orderNumber || row.id || '');
  try {
    await ElMessageBox.confirm(
      `确认复制销售单 ${orderNumber} 为新草稿？复制内容包含客户、品项和价格，不复制审批/发货/收款状态。`,
      '复制销售单',
      { confirmButtonText: '复制', cancelButtonText: '取消', type: 'info' }
    );
  } catch {
    return; // 用户取消
  }
  const res = await copySalesOrder(factoryId.value, String(row.id));
  if (res?.success && res.data) {
    ElMessage.success(`已复制为 ${res.data.orderNumber}`);
    await loadData();
  }
}
// T129 Part 1 — 删除草稿销售订单 (软删除, 仅 DRAFT).
// Fool-proof Rule 2: confirm dialog carries 订单号 context.
// Fool-proof Rule 4: idempotent — backend 409 if non-DRAFT, handled as sticky error.
async function handleDeleteOrder(row: TableRow): Promise<void> {
  const orderNumber = String(row.orderNumber || row.id || '');
  try {
    await ElMessageBox.confirm(
      `确认删除草稿销售单 ${orderNumber}？此操作不可恢复。`,
      '删除草稿',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    );
  } catch {
    return; // 用户取消
  }
  try {
    const res = await del(`/${factoryId.value}/sales/orders/${row.id}`);
    if (res?.success) {
      ElMessage.success(`已删除草稿 ${orderNumber}`);
      await loadData();
    } else {
      ElMessage({ message: res?.message || '删除失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message || '删除失败';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  }
}
function openAiForRow(row: TableRow) {
  // Day 9: open existing AiEntryDrawer with row context. Drawer's seed-message
  // hook is owned by useAiChat — we surface the row id via console for now;
  // full availableActions surfacing waits on Track A AIChat schema.
  console.info('[RowAction AI]', { entityType: 'salesOrder', entityId: row.id, orderNumber: row.orderNumber });
  aiEntryVisible.value = true;
}

// U-NAV-1 业务流程图导航 (Sprint 2 Track G + FU Chat 3 bucket-filter)
const { stats: workflowStats, loading: workflowLoading } = useWorkflowStats(factoryId, 'sales');

// Sprint 6 W3-A — inline 3-chip 链接计数 (文件 / 图片 / 合同).
const { fetchLinkChipCounts, countsFor: linkCountsFor } =
  useLinkChipCounts(factoryId, 'SALES_ORDER');
function handleWorkflowNodeClick(nodeId: string) {
  const primary = getBucketPrimaryStatus('sales', nodeId);
  if (!primary) return;
  statusFilter.value = primary;
  pagination.value.page = 1;
  loadData();
  ElMessage.success(`已切到 "${getBucketLabel('sales', nodeId)}" (显示状态: ${primary}). bucket 含多个状态, 想看其他请打开状态下拉切换.`);
}

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const statusFilter = ref('');

// U-FOOTER-1: sticky summary stats
const summaryRequest = computed<ListSummaryRequest>(() => ({
  filterConditions: statusFilter.value ? { status: statusFilter.value } : {},
}));
const { summary: footerSummary, loading: footerLoading } = useListSummary('salesOrder', summaryRequest);

// Apr 20 Bug BR-07 fix: 客户报告"未见检索功能", 补 keyword 搜索 (订单号/客户名)
const searchKeyword = ref('');
const dialogVisible = ref(false);

// P1-6 智能筛选 tab (v1 金矿截图 49m38s 6 tab)
const activeViewTab = ref<'all' | 'unshipped' | 'partialShipped' | 'unpaid' | 'partialPaid' | 'completed'>('all');
const viewTabs = [
  { key: 'all', label: '全部订单' },
  { key: 'unshipped', label: '未出库订单' },
  { key: 'partialShipped', label: '部分出库订单' },
  { key: 'unpaid', label: '未收款订单' },
  { key: 'partialPaid', label: '部分收款订单' },
  { key: 'completed', label: '已完成订单' },
] as const;

// Client-side filter based on activeViewTab
const filteredTableData = computed(() => {
  const rows = tableData.value;
  if (activeViewTab.value === 'all') return rows;
  return rows.filter((row) => {
    const total = Number(row.totalAmount || 0);
    const shipped = Number(row.actualShippedAmount || 0);
    const paid = Number(row.paidAmount || 0);
    const status = String(row.status || '');
    switch (activeViewTab.value) {
      case 'unshipped':
        return shipped <= 0 && status !== 'CANCELLED' && status !== 'COMPLETED';
      case 'partialShipped':
        return shipped > 0 && shipped < total && status !== 'CANCELLED';
      case 'unpaid':
        return paid <= 0 && status !== 'CANCELLED';
      case 'partialPaid':
        return paid > 0 && paid < total && status !== 'CANCELLED';
      case 'completed':
        return status === 'COMPLETED';
      default:
        return true;
    }
  });
});

function tabCount(key: string): number {
  if (key === 'all') return tableData.value.length;
  const rows = tableData.value;
  return rows.filter((row) => {
    const total = Number(row.totalAmount || 0);
    const shipped = Number(row.actualShippedAmount || 0);
    const paid = Number(row.paidAmount || 0);
    const status = String(row.status || '');
    switch (key) {
      case 'unshipped': return shipped <= 0 && status !== 'CANCELLED' && status !== 'COMPLETED';
      case 'partialShipped': return shipped > 0 && shipped < total && status !== 'CANCELLED';
      case 'unpaid': return paid <= 0 && status !== 'CANCELLED';
      case 'partialPaid': return paid > 0 && paid < total && status !== 'CANCELLED';
      case 'completed': return status === 'COMPLETED';
      default: return false;
    }
  }).length;
}

interface OrderItem {
  productTypeId: string;
  quantity: number;
  unit: string;
  unitPrice: number;
  taxRate: number;
  specification?: string;
  boxQuantity?: number | null;
  // T4-D1 (issue #525): source warehouse code per line. Persists to sales_order_items.source_warehouse_code.
  // Optional/empty for legacy rows + drafts. UI label via utils/warehouse.ts:warehouseDisplayLabel.
  sourceWarehouseCode?: string;
  // Sprint 4 W2 S-PRICE-1 (R1) — 上次成交价 hint, 不入库 (仅 UI). 由 onProductSelect 异步填.
  priceMemoryHint?: { unitPrice: number; sourceOrderNumber: string; orderDate: string } | null;
  // Issue #793 — 客户协议价 hint, 不入库 (仅 UI). 由 onProductSelect 异步填.
  // source: 'CUSTOMER' (客户专属, 会自动覆盖 unitPrice) | 'GLOBAL' (全局价, 仅提示)
  contractPriceHint?: { price: number; source: string; priceListName: string } | null;
}

const form = ref({
  customerId: '',
  requiredDeliveryDate: '',
  deliveryAddress: '',
  remark: '',
  salesperson: '',
  shippingIncluded: false,
  shippingFee: 0,
  extraFees: [] as Array<{ name: string; amount: number; remark: string }>,
  items: [{ productTypeId: '', quantity: 0, unit: 'kg', unitPrice: 0, taxRate: 13 }] as OrderItem[],
  contractFileUrl: '' as string | null,
  contractFileName: '' as string | null,
  customFields: {} as TableRow,
  version: null as number | null,  // optimistic lock — server returns 409 on stale
});

// P1-7 合同附件上传 (v1 §2.4.3, 2257s)
async function handleContractUpload(options: { file: File }) {
  if (!factoryId.value) return;
  const fd = new FormData();
  fd.append('file', options.file);
  try {
    const token = localStorage.getItem('cretas_access_token') || '';
    const res = await fetch(`/api/mobile/${factoryId.value}/upload/contract`, {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: fd,
    });
    const json = await res.json();
    if (json.success && json.data) {
      form.value.contractFileUrl = json.data.url;
      form.value.contractFileName = json.data.fileName || options.file.name;
      ElMessage.success(`合同上传成功: ${options.file.name}`);
    } else {
      ElMessage.error(json.message || '合同上传失败');
    }
  } catch { /* axios interceptor already displayed error toast */ }
}

function clearContract() {
  form.value.contractFileUrl = '';
  form.value.contractFileName = '';
}
const customers = ref<TableRow[]>([]);
const products = ref<TableRow[]>([]);
const salesEmployees = ref<TableRow[]>([]);

const statusMap: Record<string, { text: string; type: string }> = {
  DRAFT: { text: '草稿', type: 'info' },
  CONFIRMED: { text: '已确认', type: '' },
  PENDING_FINANCE_REVIEW: { text: '待财务审核', type: 'warning' },
  FINANCE_APPROVED: { text: '财务已批准', type: 'success' },
  FINANCE_REJECTED: { text: '财务已驳回', type: 'danger' },
  PROCESSING: { text: '处理中', type: 'warning' },
  PARTIAL_DELIVERED: { text: '部分发货', type: 'warning' },
  COMPLETED: { text: '已完成', type: 'success' },
  CANCELLED: { text: '已取消', type: 'danger' },
};

// D13: Dirty form guard — warn user before leaving with unsaved changes
const isDirty = ref(false);
watch(dialogVisible, (val) => { isDirty.value = val; });
function handleBeforeUnload(e: BeforeUnloadEvent) {
  if (isDirty.value) { e.preventDefault(); e.returnValue = ''; }
}
// v1.2 Week 9: POS summary card — hides auto when Silver has no data (manufacturing tenants).
// Tries YTD first, falls back to last calendar year when YTD is empty so restaurant tenants
// whose POS feed is seeded with historical data still see a non-empty demo.
const goldSummary = ref<FinanceSummary | null>(null);
const goldSummaryRangeLabel = ref<string>('');
async function loadGoldSummary() {
  if (!factoryId.value) return;
  const year = new Date().getFullYear();
  const today = new Date().toISOString().slice(0, 10);
  const ranges: Array<{ start: string; end: string; label: string }> = [
    { start: `${year}-01-01`, end: today, label: `${year} YTD` },
    { start: `${year - 1}-01-01`, end: `${year - 1}-12-31`, label: `${year - 1} 全年` },
  ];
  for (const rng of ranges) {
    try {
      const r = await getFinanceSummary({
        factoryId: factoryId.value, startDate: rng.start, endDate: rng.end, topNStores: 3,
      });
      if (r && r.billCount > 0) {
        goldSummary.value = r;
        goldSummaryRangeLabel.value = rng.label;
        return;
      }
    } catch { /* try next range */ }
  }
  goldSummary.value = null;
}

onMounted(() => {
  loadData(); loadCustomers(); loadProducts(); loadSalesEmployees();
  loadGoldSummary();
  window.addEventListener('beforeunload', handleBeforeUnload);
});
onBeforeUnmount(() => { window.removeEventListener('beforeunload', handleBeforeUnload); });

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const url = statusFilter.value
      ? `/${factoryId.value}/sales/orders/by-status`
      : `/${factoryId.value}/sales/orders`;
    // P1-6 smart tabs do client-side filter → load larger batch
    const effectiveSize = activeViewTab.value === 'all' ? pagination.value.size : 200;
    const params: TableRow = { page: pagination.value.page, size: effectiveSize };
    if (statusFilter.value) params.status = statusFilter.value;
    const res = await get(url, { params });
    if (res.success && res.data) {
      let rows = res.data.content || [];
      // Apr 20 Bug BR-07 fix: 补 keyword 搜索 (订单号 / 客户名)
      const kw = searchKeyword.value.trim();
      if (kw) {
        const lower = kw.toLowerCase();
        rows = rows.filter((r: TableRow) =>
          String(r.orderNumber || '').toLowerCase().includes(lower) ||
          String(r.customerName || '').toLowerCase().includes(lower)
        );
      }
      tableData.value = rows;
      pagination.value.total = res.data.totalElements || 0;

      // Sprint 6 W3-A — fire-and-forget batch 3-chip counts (文件/图片/合同).
      // List itself is unaffected by chip request failure; chip falls back to "-".
      void fetchLinkChipCounts(rows.map((r: TableRow) => String(r.id)).filter(Boolean));
    } else if (res.success === false) {
      ElMessage.error(res.message || '加载订单失败');
    }
  } catch { /* axios interceptor already displayed error toast */ }
  finally { loading.value = false; }
}

function handleTabChange() {
  // Tab 切换时 reload (后端返回 top 200 以便 client-side filter)
  pagination.value.page = 1;
  loadData();
}

async function loadCustomers() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/customers`, { params: { page: 1, size: 100 }, _silent: true } as never);
    if (res.success && res.data) customers.value = res.data.content || [];
  } catch { /* dropdown optional — fail silently for roles without customer read permission */ }
}

async function loadProducts() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/product-types/active`, { _silent: true } as never);
    if (res.success && res.data) products.value = Array.isArray(res.data) ? res.data : res.data.content || [];
  } catch { /* dropdown optional — fail silently for roles without product read permission */ }
}

const salesRoles = ['sales_manager', 'factory_super_admin'];

async function loadSalesEmployees() {
  if (!factoryId.value) return;
  // Apr 24 2026: 无 hr 读权限的角色 (如 warehouse_manager) 不必调 /users 下拉.
  // 之前 _silent:true + try/catch 已经吞错了, 但 console 仍有 403 log 噪音.
  // 前置 canAccess 检查减少无效请求.
  if (!permissionStore.canAccess('hr')) return;
  try {
    const res = await get(`/${factoryId.value}/users`, { params: { page: 1, size: 200 }, _silent: true } as never);
    if (res.success && res.data) {
      const allUsers = res.data.content || [];
      salesEmployees.value = allUsers.filter(
        (u: TableRow) => salesRoles.includes(String(u.roleCode || u.role || ''))
          || String(u.departmentName || u.department || '').includes('销售')
      );
      // If no employees matched the filter, show all active employees as fallback
      if (salesEmployees.value.length === 0) {
        salesEmployees.value = allUsers.filter((u: TableRow) => u.isActive !== false);
      }
    }
  } catch { /* silently fail — user can still type manually */ }
}

function addItem() { form.value.items.push({ productTypeId: '', quantity: 0, unit: 'kg', unitPrice: 0, specification: '', boxQuantity: null, taxRate: 13, sourceWarehouseCode: '' }); }
function removeItem(idx: number) { if (form.value.items.length > 1) form.value.items.splice(idx, 1); }

function onProductSelect(item: TableRow, productId: string) {
  const p = products.value.find((x: TableRow) => x.id === productId);
  if (p) {
    item.specification = p.specification || p.packageSpec || '';
    item.unit = p.unit || item.unit || 'kg';
    if (p.unitPrice != null && (item.unitPrice == null || item.unitPrice === 0)) {
      item.unitPrice = Number(p.unitPrice);
    }
    // P1-3: spec 自动填后立即调 calcBox, 抄码品会清空 boxQuantity (内部判断)
    if (item.quantity) calcBox(item);
  }
  // Issue #793 — 客户协议价 lookup. Customer + product 都选好后查协议价, 命中即覆盖 unitPrice.
  // Priority: 协议价 (合同) > 上次成交价 > BOM 默认价. 用户仍可手动覆盖.
  fetchContractPrice(item, productId);
  // Sprint 4 W2 S-PRICE-1 (R1) — 上次成交价 hint:
  // 选完产品立即异步查记忆价, 显在 unitPrice 输入框下. 用户**预先看到**, 不是输完后再被告知偏离.
  fetchPriceMemory(item, productId);
}

/**
 * Issue #793: 查询客户协议价 (合同价).
 * - 命中客户专属价格 → 自动覆盖 unitPrice + 显示 "协议价 (来自 X)" 提示
 * - 命中全局价格 → 设置 hint 但不强制覆盖 (因 BOM/product.unitPrice 已带入)
 * - 都不命中 → 清空 hint
 *
 * 用户可在 unitPrice 输入框手动修改, 协议价提示不阻塞.
 */
async function fetchContractPrice(item: TableRow, productTypeId: string) {
  if (!form.value.customerId || !productTypeId || !factoryId.value) {
    item.contractPriceHint = null;
    return;
  }
  try {
    const res = await get<{ found: boolean; price: number | null; source: string | null; priceListName: string | null }>(
      `/${factoryId.value}/price-lists/lookup`,
      { params: { customerId: form.value.customerId, productTypeId } },
    );
    if (res.success && res.data?.found && res.data.price != null) {
      item.contractPriceHint = {
        price: Number(res.data.price),
        source: String(res.data.source || ''),
        priceListName: String(res.data.priceListName || ''),
      };
      // Customer-specific 协议价命中时, 主动覆盖 unitPrice (用户仍可手动改).
      // 全局价仅作提示, 不覆盖 BOM/product 默认价 (那个已在 onProductSelect 前段填好).
      if (res.data.source === 'CUSTOMER') {
        item.unitPrice = Number(res.data.price);
      }
    } else {
      item.contractPriceHint = null;
    }
  } catch {
    // 静默失败 — hint 是 UX nice-to-have, 不阻塞下单. 后端会在 createOrder 时仍 auto-apply.
    item.contractPriceHint = null;
  }
}

async function fetchPriceMemory(item: TableRow, productTypeId: string) {
  if (!form.value.customerId || !productTypeId || !factoryId.value) {
    item.priceMemoryHint = null;
    return;
  }
  try {
    const res = await get<{ unitPrice: number; sourceOrderNumber: string; orderDate: string } | null>(
      `/${factoryId.value}/customers/${form.value.customerId}/price-memory`,
      { params: { productTypeId } },
    );
    item.priceMemoryHint = (res.success && res.data) ? res.data : null;
  } catch {
    // 静默失败 — hint 是 UX nice-to-have, 不阻塞下单
    item.priceMemoryHint = null;
  }
}

// Sprint 4 W2 S-PRICE-1 (R1) — "采用上次成交价" 一键按钮 handler
function applyPriceMemory(item: TableRow) {
  if (item.priceMemoryHint?.unitPrice != null) {
    item.unitPrice = Number(item.priceMemoryHint.unitPrice);
  }
}

/**
 * P1-3 (audio May 7 客户通话): 抄码品识别.
 * 抄码 = 餐饮/食品行业称重商品 (每箱重量不一致, 不能按箱计).
 * 双轨说明: LEGACY 实现; CANVAS 等 Phase B C-6 框架落地, 见
 * docs/superpowers/specs/2026-05-09-canvas-c6-reactive-default-framework.md
 */
/**
 * R2 fix #3: 精确匹配 trim() === '抄码', 不用 includes (避免误报).
 * 与 procurement/orders/list.vue isAbacaItem 同模式 (M-1 follow-up 抽 composable).
 */
function isAbacaItem(item: TableRow): boolean {
  return String(item.specification || '').trim() === '抄码';
}

function calcBox(item: TableRow) {
  if (isAbacaItem(item)) {
    item.boxQuantity = null;  // 抄码品: 不算箱数
    return;
  }
  const p = products.value.find((x: TableRow) => x.id === item.productTypeId);
  if (p?.boxConversionCoefficient && Number(p.boxConversionCoefficient) > 0 && Number(item.quantity) > 0) {
    item.boxQuantity = Math.round((Number(item.quantity) / Number(p.boxConversionCoefficient)) * 100) / 100;
  }
}

async function handleCreate() {
  if (!form.value.customerId) return ElMessage.warning('请选择客户');
  if (!form.value.items || form.value.items.length === 0) return ElMessage.warning('请至少添加一个订单明细');
  // Apr 18 2026 bug #54: 用户报告"信息填写完整 无法提交 显示不为空" — 实际是前端
  // 缺 productTypeId 空值校验, 漏到后端才 reject, 用户看文案困惑。
  if (form.value.items.some((i: TableRow) => !i.productTypeId)) return ElMessage.warning('请为所有明细选择产品');
  // 数量校验: 不允许0或负数
  if (form.value.items.some((i: TableRow) => !i.quantity || Number(i.quantity) <= 0)) return ElMessage.warning('产品数量必须大于0');
  // 单位校验
  if (form.value.items.some((i: TableRow) => !i.unit)) return ElMessage.warning('请填写所有明细的单位');
  // 销售单价校验
  if (form.value.items.some((i: TableRow) => i.unitPrice == null || Number(i.unitPrice) < 0)) return ElMessage.warning('请填写所有明细的销售单价');
  // SKU 重复校验
  const productIds = form.value.items.map((i: TableRow) => i.productTypeId).filter(Boolean);
  if (new Set(productIds).size !== productIds.length) return ElMessage.warning('同一订单不能添加重复的产品');
  try {
    const res = await post(`/${factoryId.value}/sales/orders`, form.value);
    if (res.success) { ElMessage.success('创建成功'); dialogVisible.value = false; loadData(); }
    else { ElMessage.error(res.message || '创建失败'); }
  } catch (e: unknown) {
    // 2026-05-22 hotfix (Steve P0): 409 dedup / 乐观锁 was being silently swallowed.
    // axios interceptor for vanilla 409 (no actionHint) suppresses toast on assumption
    // caller handles it — but this catch was empty. Now: surface message + reload list
    // so user knows what's happening.
    // Per fool-proof-design.md Rule 4 (写操作幂等防重复) + 4 位一体 (d): 必含 next action.
    const err = e as { status?: number; message?: string; response?: { data?: { message?: string; actionHint?: string } } };
    const status = err.status || err.response?.status;
    if (status === 409) {
      const msg = err.message || err.response?.data?.message || '同客户/同日期/同明细已有订单 (5 分钟防重) — 请刷新列表查看, 或修改任意字段重试';
      ElMessage({
        message: msg,
        type: 'warning',
        duration: 0,
        showClose: true,
      });
      loadData();
    }
    // 其他 status — axios interceptor already displayed (per existing behavior)
  }
}

async function handleAction(orderId: string, action: string) {
  const map: Record<string, { label: string; url: string }> = {
    confirm: { label: '确认', url: `/${factoryId.value}/sales/orders/${orderId}/confirm` },
    cancel: { label: '取消', url: `/${factoryId.value}/sales/orders/${orderId}/cancel` },
    // R23-Pre3: FINANCE_REJECTED → resubmit for finance review (backend already
    // supports CONFIRMED || FINANCE_REJECTED → PENDING_FINANCE_REVIEW transition).
    resubmit: { label: '重新提交', url: `/${factoryId.value}/sales/orders/${orderId}/submit-for-review` },
  };
  const a = map[action];
  if (!a) return;
  try {
    await ElMessageBox.confirm(`确认${a.label}此${label('salesOrder')}？`, '操作确认');
    const res = await post(a.url);
    if (res.success) { ElMessage.success(`${a.label}成功`); loadData(); }
    else { ElMessage.error(res.message || `${a.label}失败`); }
  } catch (error) { if (error !== 'cancel') ElMessage.error(`${a.label}失败`); }
}

const editingOrderId = ref<string | null>(null);

function handleEdit(row: TableRow) {
  editingOrderId.value = String(row.id);
  form.value = {
    customerId: String(row.customerId || row.customer?.id || ''),
    requiredDeliveryDate: String(row.requiredDeliveryDate || ''),
    deliveryAddress: String(row.deliveryAddress || ''),
    remark: String(row.remark || ''),
    salesperson: String(row.salesperson || ''),
    shippingIncluded: !!row.shippingIncluded,
    shippingFee: Number(row.shippingFee || 0),
    extraFees: Array.isArray(row.extraFees)
      ? (row.extraFees as Array<TableRow>).map((f) => ({
          name: String(f.name || ''),
          amount: Number(f.amount || 0),
          remark: String(f.remark || ''),
        }))
      : [],
    items: Array.isArray(row.items) && row.items.length > 0
      ? row.items.map((item: TableRow) => ({
          productTypeId: String(item.productTypeId || item.productType?.id || ''),
          quantity: Number(item.quantity || 0),
          unit: String(item.unit || 'kg'),
          unitPrice: Number(item.unitPrice || 0),
          // PR #173 reviewer follow-up M-4 (May 9 2026): preserve specification + boxQuantity
          // on edit. 旧 bug: handleEdit 重建 form.items 时漏了这两字段, 用户编辑现有订单后
          // 提交导致 specification/boxQuantity 被覆盖为空 (新 form 不含 → 后端把 null 写库).
          // 抄码品识别 + 箱数自动算依赖这两字段, 不能丢.
          specification: String(item.specification || ''),
          boxQuantity: item.boxQuantity != null ? Number(item.boxQuantity) : null,
          taxRate: item.taxRate != null ? Number(item.taxRate) : 13,
        }))
      : [{ productTypeId: '', quantity: 0, unit: 'kg', unitPrice: 0, specification: '', boxQuantity: null, taxRate: 13 }],
    contractFileUrl: (row.contractFileUrl ? String(row.contractFileUrl) : null) as string | null,
    contractFileName: (row.contractFileName ? String(row.contractFileName) : null) as string | null,
    customFields: {} as TableRow,
    version: typeof row.version === 'number' ? row.version : null,
  };
  dialogVisible.value = true;
}

async function handleSave() {
  if (editingOrderId.value) {
    // Update existing order
    if (!form.value.customerId) return ElMessage.warning('请选择客户');
    try {
      const res = await put(`/${factoryId.value}/sales/orders/${editingOrderId.value}`, form.value);
      if (res.success) { ElMessage.success('保存成功'); dialogVisible.value = false; editingOrderId.value = null; loadData(); }
      else { ElMessage.error(res.message || '保存失败'); }
    } catch (err) {
      // R24 P2 follow-up: only optimistic-lock dialog when no actionHint (vanilla 409).
      // Business 409s from R18/R21/R23 invariants carry actionHint — interceptor already
      // toasts the rich message; firing the wrong "并发编辑冲突" dialog on top would confuse.
      const e = err as { status?: number; actionHint?: string | null };
      if (e?.status === 409 && !e.actionHint) {
        try {
          await ElMessageBox.confirm(
            '此订单已被其他用户修改。点击"确定"将刷新列表并放弃当前编辑。',
            '并发编辑冲突',
            { type: 'warning', confirmButtonText: '刷新列表', cancelButtonText: '取消' }
          );
          dialogVisible.value = false; editingOrderId.value = null; loadData();
        } catch { /* user cancelled */ }
      }
      /* axios interceptor already displayed error toast for non-409 */
    }
  } else {
    await handleCreate();
  }
}

async function openCreateDialog() {
  editingOrderId.value = null;
  form.value = {
    customerId: '',
    requiredDeliveryDate: '',
    deliveryAddress: '',
    remark: '',
    salesperson: '',
    shippingIncluded: false,
    shippingFee: 0,
    extraFees: [] as Array<{ name: string; amount: number; remark: string }>,
    items: [{ productTypeId: '', quantity: 0, unit: 'kg', unitPrice: 0, taxRate: 13 }] as OrderItem[],
    customFields: {} as TableRow,
    contractFileUrl: null,
    contractFileName: null,
    version: null,
  };
  // 张权 Apr 28 反馈: 新建对话框 dropdown 显示 onMounted 时的旧 cache.
  // 强制刷新让用户刚建的客户/产品立即可选.
  await Promise.all([loadCustomers(), loadProducts(), loadSalesEmployees()]);
  dialogVisible.value = true;
}

function addExtraFee() {
  form.value.extraFees.push({ name: '', amount: 0, remark: '' });
}
function removeExtraFee(idx: number) {
  form.value.extraFees.splice(idx, 1);
}

function goDetail(id: string) { router.push(`/sales/orders/${id}`); }
function handlePageChange(page: number) { pagination.value.page = page; loadData(); }
function handleSizeChange(size: number) { pagination.value.size = size; pagination.value.page = 1; loadData(); }
function handleStatusChange() { pagination.value.page = 1; loadData(); }
function handleRefresh() { statusFilter.value = ''; searchKeyword.value = ''; pagination.value.page = 1; loadData(); }

// ==================== AI Entry ====================
const aiEntryVisible = ref(false);

// ==================== Audit Log Drawer (PR #861) ====================
// State for AuditLogDrawer — scoped to the row clicked via 审计 inline chip.
const auditDrawerVisible = ref(false);
const auditEntityId = ref('');
const auditEntityLabel = ref('');

// ==================== Forward Share Dialog (PR #872) ====================
// State for ForwardShareDialog — scoped to the row clicked via 转发 inline chip.
const forwardDialogVisible = ref(false);
const forwardEntityId = ref('');
const forwardEntityLabel = ref('');

function handleAiFill(params: TableRow) {
  // Match customerName to customerId
  const customerName = String(params.customerName || '');
  const matched = customers.value.find(
    (c: TableRow) => String(c.name || '').includes(customerName) || customerName.includes(String(c.name || ''))
  );

  form.value.customerId = matched ? String(matched.id) : '';
  form.value.requiredDeliveryDate = String(params.requiredDeliveryDate || '');
  form.value.deliveryAddress = String(params.deliveryAddress || '');
  form.value.remark = String(params.remark || '');

  if (Array.isArray(params.items) && params.items.length > 0) {
    form.value.items = (params.items as TableRow[]).map((item) => {
      const prodName = String(item.productName || '');
      const prodMatch = products.value.find(
        (p: TableRow) => String(p.name || '').includes(prodName) || prodName.includes(String(p.name || ''))
      );
      return {
        productTypeId: prodMatch ? String(prodMatch.id) : '',
        quantity: Number(item.quantity || 0),
        unit: String(item.unit || 'kg'),
        unitPrice: Number(item.unitPrice || 0),
        taxRate: item.taxRate != null ? Number(item.taxRate) : 13,
      };
    });
  }

  dialogVisible.value = true;
}

async function handleQuickDelivery(row: TableRow) {
  const today = new Date().toISOString().slice(0, 10);
  // Build items from order items (each with productTypeId, deliveredQuantity, unit)
  let items: TableRow[] = [];
  if (row.items && row.items.length > 0) {
    items = row.items
      .filter((item: TableRow) => item.productTypeId || item.productType?.id)
      .map((item: TableRow) => ({
        productTypeId: item.productTypeId || item.productType?.id,
        productName: item.productName || item.productType?.name,
        deliveredQuantity: item.quantity || 0,
        unit: item.unit || 'kg',
        unitPrice: Number(item.unitPrice || 0),
      }));
  }
  if (items.length === 0) {
    return ElMessage.warning('订单缺少产品信息，无法快速出库');
  }
  deliveryForm.value = {
    salesOrderId: row.id,
    customerId: row.customerId || row.customer?.id || '',
    deliveryDate: today,
    deliveryAddress: row.deliveryAddress || row.customer?.shippingAddress || '',
    logisticsCompany: row.logisticsCompany || '',
    items,
    // Issue #740: 销售只创建发货单 (任务单), 仓库 confirm 时再扣库存
    remark: `销售订单 ${row.orderNumber || ''} 发货单`,
  };
  deliveryDialogVisible.value = true;
}

async function submitQuickDelivery() {
  if (!deliveryForm.value.customerId) return ElMessage.warning('缺少客户信息');
  if (!deliveryForm.value.deliveryDate) return ElMessage.warning('请选择发货日期');
  try {
    const res = await post(`/${factoryId.value}/sales/deliveries`, deliveryForm.value);
    if (res.success) {
      // Issue #740: 文案改为"发货单已创建"明示后续等仓库 confirm
      ElMessage.success('发货单已创建, 等待仓库确认实发数量');
      deliveryDialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(res.message || '发货单创建失败');
    }
  } catch { /* axios interceptor already displays specific error toast (including #739 idempotency 409) */ }
}

async function handleQuickInvoice(row: TableRow) {
  invoiceForm.value = {
    orderId: row.id,
    counterpartyId: row.customerId || row.customer?.id || '',
    amount: row.totalAmount || 0,
    notes: `销售订单 ${row.orderNumber || ''} 开票`,
  };
  invoiceDialogVisible.value = true;
}

async function submitQuickInvoice() {
  if (!invoiceForm.value.counterpartyId) return ElMessage.warning('缺少客户信息');
  try {
    const res = await post(`/${factoryId.value}/finance/receivable`, invoiceForm.value);
    if (res.success) {
      ElMessage.success('开票成功');
      invoiceDialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(res.message || '开票失败');
    }
  } catch { /* axios interceptor already displayed error toast */ }
}

async function handleQuickPayment(row: TableRow) {
  // Issue #317 fix: include orderId so AR_PAYMENT persists salesOrderId. Was
  // orphan-receipt: SO.paidAmount stayed null + SO 收款记录 tab '暂无数据'.
  paymentForm.value = {
    orderId: row.id,
    counterpartyId: row.customerId || row.customer?.id || '',
    amount: row.totalAmount || 0,
    paymentMethod: 'BANK_TRANSFER',
    notes: `销售订单 ${row.orderNumber || ''} 收款`,
  };
  paymentDialogVisible.value = true;
}

async function submitQuickPayment() {
  if (!paymentForm.value.counterpartyId) return ElMessage.warning('缺少客户信息');
  try {
    const res = await post(`/${factoryId.value}/finance/receivable/payment`, paymentForm.value);
    if (res.success) {
      ElMessage.success('收款成功');
      paymentDialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(res.message || '收款失败');
    }
  } catch { /* axios interceptor already displayed error toast */ }
}
</script>

<template>
  <!-- v1.2 Week 9: POS 交易概览 (restaurant tenants). Placed OUTSIDE CanvasAwareWrapper
       so it renders in both LEGACY and DYNAMIC/CANVAS rendering modes. Auto-hides
       when Silver empty (manufacturing tenants). -->
  <el-card
    v-show="goldSummary"
    class="gold-pos-summary"
    style="margin: 12px; border-top: 3px solid #67C23A;"
    shadow="never"
  >
    <template #header>
      <div style="display: flex; align-items: center; gap: 8px; font-weight: 600;">
 <span> POS 交易概览</span>
        <el-tag size="small" type="success">Gold · finance_summary</el-tag>
        <span v-if="goldSummaryRangeLabel" style="color: #909399; font-size: 12px; margin-left: auto;">
          {{ goldSummaryRangeLabel }}
        </span>
      </div>
    </template>
    <el-row v-if="goldSummary" :gutter="16">
      <el-col :span="6">
        <el-statistic :value="goldSummary.totalRevenue" title="总营收" :precision="2" prefix="¥" />
      </el-col>
      <el-col :span="6">
        <el-statistic :value="goldSummary.billCount" title="POS 账单数" />
      </el-col>
      <el-col :span="6">
        <el-statistic :value="goldSummary.avgBillValue ?? 0" title="客单价" :precision="2" prefix="¥" />
      </el-col>
      <el-col :span="6">
        <el-statistic :value="goldSummary.storeCount" title="门店数" />
      </el-col>
    </el-row>
  </el-card>

  <!-- P1-10: restaurant tenants 无 B2B SO,下面空表只是功能残留 -->
  <el-alert
    v-if="isRestaurantTenant"
    type="info"
    :closable="false"
    show-icon
    style="margin: 0 12px 12px 12px;"
    title="餐饮门店的 POS 账单已在上方「POS 交易概览」汇总。销售订单 (B2B) 本页下方通常为空,如有企业订餐/团购/外送合同才需新建。"
  />

  <CanvasAwareWrapper module-code="sales_order">
  <div class="page-wrapper">
    <!-- U-NAV-1 业务流程图导航 (Sprint 2 Track G) -->
    <WorkflowBar
      :nodes="workflowStats?.nodes ?? []"
      :loading="workflowLoading"
      title="销售工作流"
      :ai-trigger-enabled="true"
      @node-click="handleWorkflowNodeClick"
      @ai-trigger="aiEntryVisible = true"
    />
    <ConceptDisambiguationAlert
      here-name="销售订单"
      here="客户向我们下的订单（出货方向、应收账款）"
      other-name="采购管理 → 采购订单"
      other="我们向供应商下的订单（进货方向、应付账款）"
      other-path="/procurement/orders"
    />
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">{{ label('salesOrder') }}管理</span>
            <span class="data-count">共 {{ pagination.total }} 条记录</span>
          </div>
          <div class="header-right">
            <el-button v-if="canWrite" type="success" :icon="ChatDotRound" @click="aiEntryVisible = true">
              AI录入
            </el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreateModeSelector">新建{{ label('salesOrder') }}</el-button>
          </div>
        </div>
      </template>

      <!-- P1-6 智能筛选 tab (v1 金矿截图 49m38s) -->
      <el-radio-group v-model="activeViewTab" size="default" @change="handleTabChange" style="margin-bottom: 12px">
        <el-radio-button v-for="tab in viewTabs" :key="tab.key" :value="tab.key">
          {{ tab.label }} <span v-if="tabCount(tab.key) > 0" class="tab-count">{{ tabCount(tab.key) }}</span>
        </el-radio-button>
      </el-radio-group>

      <div class="search-bar">
        <!-- Apr 20 Bug BR-07 fix: 加 keyword 搜索 (订单号/客户名) -->
        <el-input v-model="searchKeyword" placeholder="搜索 订单号/客户" clearable style="width: 240px" @keyup.enter="loadData" />
        <el-select v-model="statusFilter" placeholder="按状态筛选" clearable style="width: 160px" @change="handleStatusChange">
          <el-option v-for="(v, k) in statusMap" :key="k" :label="v.text" :value="k" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="loadData">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
        <!-- U-VIEW-1 view-mode switcher (Sprint 4 Wave 2 Chat L) -->
        <div style="margin-left: auto">
          <ViewModeSwitcher v-model="viewMode" />
        </div>
      </div>

      <GridView
        v-if="viewMode === 'grid'"
        :rows="filteredTableData"
        title-field="orderNumber"
        subtitle-field="customerName"
        status-field="status"
        row-key="id"
      />
      <KanbanView
        v-else-if="viewMode === 'kanban'"
        :rows="filteredTableData"
        status-field="status"
        title-field="orderNumber"
        subtitle-field="customerName"
        :columns="kanbanColumns"
        row-key="id"
      />
      <TimelinePlaceholder v-else-if="viewMode === 'timeline'" />
      <CalendarPlaceholder v-else-if="viewMode === 'calendar'" />
      <el-table v-else :data="filteredTableData" v-loading="loading" empty-text="暂无数据" stripe border style="width: 100%">
        <!-- U-MARKER-1 row marker column (Sprint 4 Wave 2 Chat L) -->
        <el-table-column label="" width="36" align="center">
          <template #default="{ row }">
            <RowMarkerCell
              :value="row.markerColor"
              :readonly="!canWrite"
              @select="(c) => handleMarkerSelect(row, c)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="orderNumber" label="订单编号" width="170" />
        <el-table-column label="客户" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.customerName || row.customer?.name || row.customerId || '-' }}</template>
        </el-table-column>
        <el-table-column prop="salesperson" label="业务员" width="100" show-overflow-tooltip />
        <el-table-column prop="orderDate" label="下单日期" width="120" />
        <!--
          RBAC defense-in-depth (PR #415 Option B 2026-05-12 + P3 column-hide fix):
          backend PriceFieldResponseAdvice strips totalAmount / discountAmount /
          taxAmount / shippingFee to null for roles lacking procurement:price:view.
          v-if on the column itself hides BOTH the header and cells for
          non-whitelisted roles (warehouse_manager etc). Without v-if on
          <el-table-column>, Element Plus still renders 总金额 / 运费 / 折扣
          headers even when all cells are null/blank — E2E flagged misleading UX.
        -->
        <el-table-column
          v-if="canViewPrice"
          prop="totalAmount"
          label="总金额"
          width="130"
          align="right"
        >
          <template #default="{ row }">
            <span v-if="row.totalAmount != null">{{ formatAmount(row.totalAmount) }}</span>
            <span v-else class="price-masked">—</span>
          </template>
        </el-table-column>
        <el-table-column
          v-if="canViewPrice"
          prop="shippingFee"
          label="运费"
          width="100"
          align="right"
        >
          <template #default="{ row }">{{ row.shippingFee ? formatAmount(row.shippingFee) : '-' }}</template>
        </el-table-column>
        <el-table-column
          v-if="canViewPrice"
          prop="discountAmount"
          label="折扣"
          width="100"
          align="right"
        >
          <template #default="{ row }">
            <span v-if="row.discountAmount != null && row.discountAmount">{{ formatAmount(row.discountAmount) }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <!--
          Sprint3-G S-LOCK-1: 行内 锁/备/缺 3 chip. 销售员看销售单不用切去库存页.
          数据源: SalesOrder @Transient getTotalLockedQty/getTotalReservedQty/getTotalShortageQty
          (聚合 items[]). NOT @PriceSensitive — inventory 数据非价格 (跟 canViewPrice 解耦,
          所有角色可见).
          chip 垂直堆叠: 缺料 > 0 红色高亮, 一眼识别要不要催生产.
 Issue #746: header 加 tooltip 解释含义 (客户手测 img 34 反馈 chip 含义不清).
        -->
        <el-table-column label="锁/备/缺" width="130" align="center">
          <template #header>
            <span style="display: inline-flex; align-items: center; gap: 4px;">
              锁/备/缺
              <el-tooltip placement="top">
                <template #content>
                  <div style="line-height: 1.6;">
                    <div><b>锁</b> = 已锁定库存 (本订单已占用, 不可被其他订单分配)</div>
                    <div><b>备</b> = 已预留 (已分配批次, 等待出货确认)</div>
                    <div><b>缺</b> = 缺料数量 (库存不足, 需催生产或紧急采购)</div>
                  </div>
                </template>
                <el-icon style="cursor: help; color: var(--text-color-secondary, #909399); font-size: 12px;"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <template #default="{ row }">
            <div class="lock-reserve-shortage">
              <el-tooltip content="锁=已锁定库存 (本订单已占用)" placement="left">
                <div class="chip chip-lock">锁:{{ Number(row.lockedQty || 0) }}</div>
              </el-tooltip>
              <el-tooltip content="备=已预留 (已分配批次, 等待出货)" placement="left">
                <div class="chip chip-reserve">备:{{ Number(row.reservedQty || 0) }}</div>
              </el-tooltip>
              <el-tooltip :content="Number(row.shortageQty || 0) > 0 ? '缺=缺料数量, 需催生产或紧急采购' : '缺=0 无缺料'" placement="left">
                <div class="chip" :class="Number(row.shortageQty || 0) > 0 ? 'chip-shortage' : 'chip-zero'">
                  缺:{{ Number(row.shortageQty || 0) }}
                </div>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="(statusMap[row.status]?.type) || 'info'" size="small">
              {{ statusMap[row.status]?.text || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <!--
          Sprint 6 W3-A — 行内 3-chip 链接计数 (文件 / 图片 / 合同).
          数据源: POST /attachments/batch-3chip-counts (batch, 避免 N+1).
          替代 Sprint 5 PR #58 unified `链:N` chip per HJ baseline (Round 12 §B.6 X2).
          EntityType=SALES_ORDER 由 useLinkChipCounts composable 锁定.
        -->
        <el-table-column label="附件" width="200" align="center">
          <template #header>
            <span style="display: inline-flex; align-items: center; gap: 4px;">
              附件
              <el-tooltip placement="top">
                <template #content>
                  <div style="line-height: 1.6;">
                    <div><b>文件</b>: 通用文档 (PDF / Word / Excel / OTHER)</div>
                    <div><b>图片</b>: 照片 / 视频 (PHOTO / VIDEO)</div>
                    <div><b>合同</b>: 销售合同 / 法律文件 (CONTRACT)</div>
                    <div style="margin-top: 4px; color: var(--text-color-secondary);">点详情查看附件清单</div>
                  </div>
                </template>
                <el-icon style="cursor: help; color: var(--text-color-secondary, #909399); font-size: 12px;"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <template #default="{ row }">
            <LinkChipCell :counts="linkCountsFor(row.id)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="320" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="goDetail(row.id)">详情</el-button>
            <el-button v-if="row.status === 'DRAFT' && canWrite" type="warning" link size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button v-if="row.status === 'DRAFT' && canWrite" type="success" link size="small" @click="handleAction(row.id, 'confirm')">确认</el-button>
            <el-button v-if="['DRAFT','CONFIRMED'].includes(row.status) && canWrite" type="danger" link size="small" @click="handleAction(row.id, 'cancel')">取消</el-button>
            <el-button v-if="row.status === 'FINANCE_REJECTED' && canWrite" type="success" link size="small" @click="handleAction(row.id, 'resubmit')">重新提交</el-button>
            <!--
              Issue #740 (六扇门 May10 会议): 销售只创建发货单 (DRAFT/PENDING_WAREHOUSE_CONFIRM,
              不扣库存); 仓库角色去 仓储管理 → 出货管理 确认实发数量并扣库存.
              按钮文案从 "出库" 改为 "创建发货单" 准确反映行为.
            -->
            <el-button
              v-if="(row.status === 'CONFIRMED' || row.status === 'PROCESSING') && canWrite"
              type="warning"
              link
              size="small"
              @click="handleQuickDelivery(row)"
            >创建发货单</el-button>
            <el-button
              v-if="(row.status === 'CONFIRMED' || row.status === 'PROCESSING' || row.status === 'SHIPPED') && canWrite"
              type="success"
              link
              size="small"
              @click="handleQuickInvoice(row)"
            >开票</el-button>
            <el-button
              v-if="(row.status === 'CONFIRMED' || row.status === 'PROCESSING' || row.status === 'SHIPPED' || row.status === 'COMPLETED') && canWrite"
              type="success"
              link
              size="small"
              @click="openTaxGroupInvoice(row)"
            >税率分组开票</el-button>
            <el-button
              v-if="(row.status === 'CONFIRMED' || row.status === 'PROCESSING' || row.status === 'SHIPPED') && canWrite"
              type="primary"
              link
              size="small"
              @click="handleQuickPayment(row)"
            >收款</el-button>
            <!-- U-ICON-1 (Sprint 4 Wave 2 Chat L) inline 7-icon hover toolbar -->
            <InlineRowIcons
              :row-actions="rowActionsFor(row)"
              entity-type="salesOrder"
              class="row-inline-icons"
              @icon-click="(id: InlineIconId) => handleInlineIconClick(id, row)"
            />
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
        @ai-analyze="() => ElMessage.info({ message: `AI 分析 (待接 SmartBI): 分析当前销售订单${formatSummaryForAI(footerSummary, { filter: { status: statusFilter } })}`, duration: 8000, showClose: true })"
      />

      <div class="pagination-wrapper">
        <el-pagination v-model:current-page="pagination.page" v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50]" :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange" @size-change="handleSizeChange" />
      </div>
    </el-card>

    <!--
      Issue #740: 销售创建发货单对话框 (不扣库存, 等仓库 confirm).
      标题改为"创建发货单" + 提示文案让销售员明白此步只是创建任务单.
    -->
    <el-dialog v-model="deliveryDialogVisible" title="创建发货单" width="540px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px;"
      >
        <template #title>
          <span style="font-size: 13px;">销售创建发货单 (任务单, 不扣库存), 仓库收到后填写实际发货数量并扣库存.</span>
        </template>
      </el-alert>
      <el-form :model="deliveryForm" label-width="100px">
        <el-form-item label="发货日期" required>
          <el-date-picker v-model="deliveryForm.deliveryDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="发货地址">
          <el-input v-model="deliveryForm.deliveryAddress" placeholder="收货地址 (默认取销售订单)" />
        </el-form-item>
        <el-form-item label="物流公司">
          <el-input v-model="deliveryForm.logisticsCompany" placeholder="物流公司 (可选, 仓库 confirm 时也可补填)" />
        </el-form-item>
        <el-form-item label="计划发货数量">
          <div v-for="(item, idx) in deliveryForm.items" :key="idx" style="margin-bottom: 4px">
            {{ Number(idx) + 1 }}. {{ item.productName || '产品' }} —
            <el-input-number v-model="item.deliveredQuantity" :min="1" size="small" style="width: 120px" /> {{ item.unit }}
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="deliveryForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deliveryDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitQuickDelivery">创建发货单</el-button>
      </template>
    </el-dialog>

    <!-- 开票对话框 -->
    <el-dialog v-model="invoiceDialogVisible" title="快速开票" width="400px">
      <el-form :model="invoiceForm" label-width="80px">
        <el-form-item label="开票金额">
          <el-input-number v-model="invoiceForm.amount" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="invoiceForm.notes" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="invoiceDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitQuickInvoice">确认开票</el-button>
      </template>
    </el-dialog>

    <!-- 收款对话框 -->
    <el-dialog v-model="paymentDialogVisible" title="快速收款" width="400px">
      <el-form :model="paymentForm" label-width="80px">
        <el-form-item label="收款金额">
          <el-input-number v-model="paymentForm.amount" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="收款方式">
          <el-select v-model="paymentForm.paymentMethod" style="width: 100%">
            <el-option label="银行转账" value="BANK_TRANSFER" />
            <el-option label="现金" value="CASH" />
            <el-option label="支票" value="CHECK" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="paymentForm.notes" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="paymentDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitQuickPayment">确认收款</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" :title="editingOrderId ? `编辑${label('salesOrder')}` : `新建${label('salesOrder')}`" width="80%" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-form-item :label="label('customer')">
          <el-select v-model="form.customerId" placeholder="请选择" filterable style="width: 100%">
            <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="交货日期"><el-date-picker v-model="form.requiredDeliveryDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="交货地址"><el-input v-model="form.deliveryAddress" /></el-form-item>
        <el-form-item label="业务员">
          <el-select v-model="form.salesperson" placeholder="请选择业务员" filterable allow-create style="width: 100%">
            <el-option v-for="emp in salesEmployees" :key="emp.id" :label="emp.fullName" :value="emp.fullName" />
          </el-select>
        </el-form-item>
        <el-form-item label="含运费">
          <el-switch v-model="form.shippingIncluded" />
          <el-input-number v-if="form.shippingIncluded" v-model="form.shippingFee" :min="0" :precision="2" placeholder="运费金额" style="width: 180px; margin-left: 12px" />
        </el-form-item>
        <el-form-item label="其他费用">
          <div style="width: 100%">
            <div v-for="(fee, idx) in form.extraFees" :key="idx" style="display: flex; gap: 8px; margin-bottom: 6px">
              <el-input v-model="fee.name" placeholder="费用名称 (如装卸费)" style="width: 200px" />
              <el-input-number v-model="fee.amount" :min="0" :precision="2" placeholder="金额" style="width: 140px" />
              <el-input v-model="fee.remark" placeholder="备注" style="flex: 1" />
              <el-button type="danger" link @click="removeExtraFee(idx)">删除</el-button>
            </div>
            <el-button size="small" @click="addExtraFee">+ 添加费用项</el-button>
          </div>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="预订合同">
          <!-- P1-7 合同附件上传 (v1 §2.4.3, 客户 2257s) -->
          <div v-if="form.contractFileUrl" style="display:flex;gap:8px;align-items:center">
            <el-tag type="success" size="small">已上传: {{ form.contractFileName }}</el-tag>
            <el-button size="small" type="danger" link @click="clearContract">移除</el-button>
          </div>
          <el-upload
            v-else
            :auto-upload="true"
            :http-request="handleContractUpload as any"
            :limit="1"
            :show-file-list="false"
            accept=".pdf,.jpg,.jpeg,.png,.doc,.docx"
          >
            <el-button size="small">上传合同 (PDF/图片/Word, ≤20MB)</el-button>
          </el-upload>
        </el-form-item>
        <CanvasDynamicFields v-model="form.customFields" module-code="sales_order" />
        <el-divider>{{ label('product') }}明细</el-divider>
        <div class="item-row item-header">
          <span style="width: 200px">品名</span>
          <span style="width: 120px">规格</span>
          <span style="width: 130px">下单数量</span>
          <span style="width: 80px">单位</span>
          <span style="width: 130px">单价</span>
          <span style="width: 130px">箱数</span>
          <span style="width: 90px" title="税率 (开票 G1 按此分组): 9=原料, 13=加工, 6=服务">税率(%)</span>
          <!-- T4-D1 (issue #525): 来源仓库 — F006 客户反馈 "成品会调回总仓, 总仓再安排发货".
               Customer wants to record per-line source warehouse (WH-LOG 总仓 / WH-WKS 线边仓). -->
          <span style="width: 110px">来源仓库</span>
          <span style="width: 40px">操作</span>
        </div>
        <div v-for="(item, idx) in form.items" :key="idx" class="item-row">
          <el-select v-model="item.productTypeId" placeholder="选择产品" filterable style="width: 200px" @change="(v: string) => onProductSelect(item, v)">
            <el-option v-for="p in products" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
          <el-input v-model="item.specification" placeholder="规格" style="width: 120px" @change="calcBox(item)" />
          <el-input-number v-model="item.quantity" :min="1" style="width: 130px" @change="() => calcBox(item)" />
          <el-input v-model="item.unit" style="width: 80px" />
          <!-- Sprint 4 W2 S-PRICE-1 R1: unitPrice + 上次成交价 hint chip (一键采纳) -->
          <!-- Issue #793: 客户协议价 hint (CUSTOMER 自动覆盖, GLOBAL 仅提示) -->
          <div class="unit-price-wrap">
            <el-input-number v-model="item.unitPrice" :min="0" :precision="2" style="width: 130px" />
            <el-tooltip
              v-if="item.contractPriceHint"
              :content="`${item.contractPriceHint.source === 'CUSTOMER' ? '协议价' : '全局价'} ¥${item.contractPriceHint.price} · ${item.contractPriceHint.priceListName}${item.contractPriceHint.source === 'CUSTOMER' ? ' · 已自动应用' : ' · 点击采用'}`"
              placement="top"
            >
              <el-tag
                :type="item.contractPriceHint.source === 'CUSTOMER' ? 'primary' : 'info'"
                effect="plain"
                size="small"
                class="contract-price-chip"
                @click="item.unitPrice = Number(item.contractPriceHint.price)"
              >
                {{ item.contractPriceHint.source === 'CUSTOMER' ? '协议' : '统一' }} ¥{{ item.contractPriceHint.price }}
              </el-tag>
            </el-tooltip>
            <el-tooltip
              v-if="item.priceMemoryHint"
              :content="`上次成交 ¥${item.priceMemoryHint.unitPrice} · ${item.priceMemoryHint.orderDate} · ${item.priceMemoryHint.sourceOrderNumber} · 点击采用`"
              placement="top"
            >
              <el-tag
                type="success"
                effect="plain"
                size="small"
                class="price-memory-chip"
                @click="applyPriceMemory(item)"
              >
                ↻ ¥{{ item.priceMemoryHint.unitPrice }}
              </el-tag>
            </el-tooltip>
          </div>
          <!-- P1-3 R2 fix: el-tag 替换 inline-styled div, 跟随 Element Plus 主题 -->
          <!-- 2026-05-22 hotfix: 箱数 80→130px — Steve 客户报 -/+ 按钮挤掉数字看不见 -->
          <el-tag v-if="isAbacaItem(item)" type="warning" effect="light" size="default" style="width: 130px; text-align: center;">抄码品</el-tag>
          <el-input-number v-else v-model="item.boxQuantity" :min="0" :precision="2" style="width: 130px" placeholder="箱" />
          <el-select v-model="item.taxRate" placeholder="税率" style="width: 90px" size="default">
            <el-option :value="0" label="0% 免税" />
            <el-option :value="3" label="3% 小规模" />
            <el-option :value="6" label="6% 服务" />
            <el-option :value="9" label="9% 原料" />
            <el-option :value="13" label="13% 加工" />
          </el-select>
          <!-- T4-D1 (issue #525): 来源仓库 select — labels 总仓 / 线边仓 per utils/warehouse.ts. -->
          <el-select v-model="item.sourceWarehouseCode" placeholder="选择" clearable style="width: 110px" size="default">
            <el-option label="总仓 (WH-LOG)" value="WH-LOG" />
            <el-option label="线边仓 (WH-WKS)" value="WH-WKS" />
          </el-select>
          <el-button type="danger" link @click="removeItem(idx)" :disabled="form.items.length <= 1">删除</el-button>
        </div>
        <el-button style="width: 100%; margin-top: 8px" @click="addItem">+ 添加行</el-button>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">{{ editingOrderId ? '保存' : '创建' }}</el-button>
      </template>
    </el-dialog>

    <!-- AI 对话录入 -->
    <AiEntryDrawer
      v-model="aiEntryVisible"
      :config="SALES_ORDER_CONFIG"
      @fill-form="handleAiFill"
    />

    <!-- G1: 税率分组开票对话框 (Sprint 4 W2 S-INVOICE-CLIENT-1: 传入 default-invoice-type 用于 R3 dropdown prefill) -->
    <TaxGroupInvoiceDialog
      v-model="taxGroupInvoiceVisible"
      :factory-id="factoryId || ''"
      :sales-order-id="taxGroupInvoiceOrder.id"
      :order-number="taxGroupInvoiceOrder.orderNumber"
      :customer-name="taxGroupInvoiceOrder.customerName"
      :order-total-amount="taxGroupInvoiceOrder.totalAmount"
      :default-invoice-type="taxGroupInvoiceOrder.defaultInvoiceType"
      @success="loadData"
    />

    <!-- U-NEW-1 — create-mode selector + 4 mode dialogs (普通/一维/二维/BOM).
         普通 = openCreateDialog (existing). 二维 = BatchCreateDialog (existing).
         一维 + BOM finished in P1 #58. -->
    <CreateModeSelector
      v-model="createModeSelectorVisible"
      :entity-label="label('salesOrder')"
      @mode-selected="handleCreateModeSelected"
    />
    <BatchCreateDialog
      v-model="batchCreateVisible"
      :title="`批量新建 ${label('salesOrder')}`"
      :columns="[
        { prop: 'customerId', label: '客户 ID', required: true, slotName: 'customer' },
        { prop: 'salesperson', label: '业务员', width: 140 },
        { prop: 'requiredDeliveryDate', label: '期望交货日', width: 160, slotName: 'date' },
        { prop: 'remark', label: '备注' },
      ]"
      :row-factory="batchOrderFactory"
      :submit="submitBatchOrders"
    >
      <template #customer="{ row }">
        <el-select v-model="row.customerId" filterable size="small" placeholder="选择客户" style="width: 100%">
          <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
      </template>
      <template #date="{ row }">
        <el-date-picker v-model="row.requiredDeliveryDate" type="date" size="small" value-format="YYYY-MM-DD" style="width: 100%" />
      </template>
    </BatchCreateDialog>

    <!-- P1 #58 — Quick (一维) single-row consecutive entry -->
    <QuickCreateDialog
      v-model="quickCreateVisible"
      :title="`快速新建 ${label('salesOrder')}`"
      :context-hint="`客户范围: ${customers.length} 个可选 — 回车连续录入`"
      :fields="[
        { prop: 'customerId', label: '客户', required: true, slotName: 'customer' },
        { prop: 'requiredDeliveryDate', label: '期望交货日', slotName: 'date' },
        { prop: 'remark', label: '备注', placeholder: '可选, 简短备注' },
      ]"
      :row-factory="quickSalesOrderFactory"
      :submit="submitQuickSalesOrder"
      :session-max="20"
    >
      <template #customer="{ row }">
        <el-select v-model="row.customerId" filterable placeholder="选择客户" style="width: 100%">
          <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
      </template>
      <template #date="{ row }">
        <el-date-picker v-model="row.requiredDeliveryDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
      </template>
    </QuickCreateDialog>

    <!-- P1 #58 — BOM 展开: parent SO + child items from product template -->
    <BomExpansionDialog
      v-model="bomCreateVisible"
      :title="`BOM 展开新建 ${label('salesOrder')}`"
      :entity-label="label('salesOrder')"
      :templates="bomSalesTemplates"
      :parent-factory="bomSalesOrderParentFactory"
      :expand-template="expandBomSalesTemplate"
      :submit="submitBomSalesOrder"
      :child-columns="[
        { prop: 'productName', label: '商品名称', width: 200 },
        { prop: 'quantity', label: '数量', width: 120, slotName: 'quantity' },
        { prop: 'unit', label: '单位', width: 100 },
        { prop: 'unitPrice', label: '单价', width: 140, slotName: 'price' },
      ]"
      :max-children="50"
    >
      <template #parent-fields="{ parent }">
        <el-form label-position="top">
          <el-form-item label="客户" required>
            <el-select v-model="parent.customerId" filterable placeholder="选择客户" style="width: 100%">
              <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="期望交货日">
            <el-date-picker v-model="parent.requiredDeliveryDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="parent.remark" type="textarea" :rows="2" placeholder="可选" />
          </el-form-item>
        </el-form>
      </template>
      <template #quantity="{ row }">
        <el-input-number v-model="row.quantity" :min="0" :step="0.5" size="small" style="width: 100%" />
      </template>
      <template #price="{ row }">
        <el-input-number v-model="row.unitPrice" :min="0" :step="0.01" :precision="2" size="small" style="width: 100%" />
      </template>
    </BomExpansionDialog>

    <!-- PR #861: per-row operation log timeline (replaces the disabled "审计" chip). -->
    <AuditLogDrawer
      v-model:visible="auditDrawerVisible"
      entity-type="SalesOrder"
      entity-type-label="销售订单"
      :entity-id="auditEntityId"
      :entity-label="auditEntityLabel"
    />

    <!-- PR #872 (#860 follow-up) — 转发分享链接 dialog (replaces (开发中) chip). -->
    <ForwardShareDialog
      v-model:visible="forwardDialogVisible"
      :factory-id="factoryId"
      entity-type="SalesOrder"
      entity-type-label="销售订单"
      :entity-id="forwardEntityId"
      :entity-label="forwardEntityLabel"
    />
  </div>
  </CanvasAwareWrapper>
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
}
.search-bar { display: flex; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.pagination-wrapper { display: flex; justify-content: flex-end; padding-top: 16px; border-top: 1px solid #ebeef5; margin-top: 16px; }
.item-row { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
/* Sprint 4 W2 S-PRICE-1 R1: unitPrice + 上次成交价 chip 垂直堆 */
.unit-price-wrap { display: flex; flex-direction: column; gap: 2px; align-items: stretch; }
.price-memory-chip { cursor: pointer; font-size: 11px; line-height: 1.2; padding: 1px 4px; }
.price-memory-chip:hover { opacity: 0.85; }
/* Issue #793: 客户协议价 hint chip */
.contract-price-chip { cursor: pointer; font-size: 11px; line-height: 1.2; padding: 1px 4px; }
.contract-price-chip:hover { opacity: 0.85; }
.item-header { font-size: 13px; font-weight: 600; color: #606266; margin-bottom: 4px;
  span { text-align: center; display: inline-block; }
}
/* Sprint3-G S-LOCK-1: 行内 锁/备/缺 3 chip 垂直堆 */
.lock-reserve-shortage { display: flex; flex-direction: column; gap: 2px; align-items: stretch; }
.chip { font-size: 11px; padding: 1px 6px; border-radius: 3px; text-align: center; line-height: 1.4; }
.chip-lock { background: #f0f4ff; color: #2c5aa0; border: 1px solid #cfd8e8; }
.chip-reserve { background: #f0f9eb; color: #67c23a; border: 1px solid #c2e7b0; }
.chip-zero { background: #f4f4f5; color: #909399; border: 1px solid #e9e9eb; }
/* 缺料 > 0 红色高亮 — 销售员触发催生产 / 紧急采购的视觉信号 */
.chip-shortage { background: #fef0f0; color: #f56c6c; border: 1px solid #fbc4c4; font-weight: 600; }
</style>
