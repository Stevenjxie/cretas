<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useBusinessMode } from '@/composables/useBusinessMode';
import { get, post, put, del } from '@/api/request';
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus';
import { Plus, Refresh, Search, ChatDotRound, QuestionFilled } from '@element-plus/icons-vue';
import AiEntryDrawer from '@/components/ai-entry/AiEntryDrawer.vue';
import AuditLogDrawer from '@/components/AuditLogDrawer.vue';
import { SALES_ORDER_CONFIG } from '@/components/ai-entry/types';
import { WorkflowBar } from '@/components/workflow';
import { useWorkflowStats } from '@/composables/useWorkflowStats';
import { getBucketPrimaryStatus, getBucketLabel } from '@/types/workflow';
import { formatAmount } from '@/utils/tableFormatters';
import { warehouseDisplayName } from '@/utils/warehouse';
import { RowActionMenu, ViewModeSwitcher, GridView, KanbanView, TimelinePlaceholder, CalendarPlaceholder, InlineRowIcons, RowMarkerCell } from '@/components/list';
// Sprint 6 W3-A — inline 3-chip link counter (文件 / 图片 / 合同).
import LinkChipCell from '@/components/list/LinkChipCell.vue';
import { useLinkChipCounts } from '@/composables/useLinkChipCounts';
import { CreateModeSelector, BatchCreateDialog, QuickCreateDialog, BomExpansionDialog, StartPurchaseDialog, MergePurchaseDialog } from '@/components/dialog';
// PR #872 (#860 follow-up) — 转发 / 分享链接 dialog.
import ForwardShareDialog from '@/components/dialog/ForwardShareDialog.vue';
// #1290 follow-up — 退货 dead-button fix: wire list-row "退货" action to the
// same CreateReturnOrderDialog already used by procurement/orders/list.vue +
// sales/shipments/list.vue (backend ReturnOrderController, 7 endpoints).
import CreateReturnOrderDialog from '@/components/dialog/CreateReturnOrderDialog.vue';
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

// 开始采购弹窗 state (t2b 行1867-1902 — Friday 采购负责人请求)
const startPurchaseVisible = ref(false);
const startPurchaseSoId = ref('');
const startPurchaseSoNumber = ref('');
const startPurchaseCustomer = ref('');

// 转录行3650 — 多 SO 合并采购弹窗 (勾选多张 SO → 合并成一张采购单).
const mergePurchaseVisible = ref(false);
const mergePurchaseSos = ref<{ id: string; number: string; customer?: string }[]>([]);

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const { label } = useBusinessMode();
const factoryId = computed(() => authStore.factoryId);
// Apr 24 2026 P1-10: restaurant tenants 用 POS 账单, 无传统 B2B SO → 显提示代替空表混乱
const isRestaurantTenant = computed(() => authStore.factoryType === 'RESTAURANT');
const canWrite = computed(() => permissionStore.canWrite('sales'));

const canViewPrice = computed(() => permissionStore.canViewPrice);

// T136 — 产品字典跳转权限门控: system 模块有访问权限才显示「去配置」按钮
// (fool-proof Rule 5: dead-end → next-action navigation, gated on permission)
const canAccessProducts = computed(() => permissionStore.canAccess('system'));

/**
 * T136 — 跳转到产品字典并带 _returnTo 参数，让用户配置完后可回到此页
 * @param productTypeId 可选，目前产品字典不支持直接定位到某产品，预留供未来使用
 */
function goConfigureProduct(_productTypeId?: string) {
  const returnTo = encodeURIComponent(route.fullPath);
  router.push(`/system/products?_returnTo=${returnTo}`);
}

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
    extraFees: [] as unknown[],
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
      extraFees: [] as unknown[],
      items: [] as unknown[],
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

// #1290 follow-up — 退货 dialog state. Mirrors procurement/orders/list.vue +
// sales/shipments/list.vue's wiring of the same CreateReturnOrderDialog
// component (backend ReturnOrderController). Previously the "退货" 更多-menu
// item for COMPLETED/PARTIAL_DELIVERED/SHIPPED rows had no case in
// handleRowActionClick and fell through to the generic info toast below —
// a dead button that looked real but sent no request. The real entry point
// this mirrors is sales/orders/detail.vue's "申请退货" button (openReturnDialog),
// which caps each line's return quantity at deliveredQuantity.
const returnDialogVisible = ref(false);
const returnDialogRow = ref<TableRow | null>(null);
const returnDialogItems = computed(() => {
  const row = returnDialogRow.value;
  if (!row || !Array.isArray(row.items)) return [];
  return (row.items as TableRow[]).map((it) => {
    const delivered = Number(it.deliveredQuantity) || 0;
    return {
      id: it.id,
      materialTypeId: null as string | null,
      productTypeId: it.productTypeId ? String(it.productTypeId) : null,
      itemName: String(it.productName || it.productTypeName || '-'),
      unitPrice: Number(it.unitPrice) || 0,
      // Cap at delivered qty (can't refund what was never shipped) — same
      // rule as detail.vue's openReturnDialog.
      maxQuantity: delivered,
      batchNumber: it.batchNumber ? String(it.batchNumber) : null,
    };
  });
});
function openReturnDialog(row: TableRow): void {
  if (!Array.isArray(row.items) || row.items.length === 0) {
    ElMessage.warning('订单无明细, 无法发起退货. 请打开订单详情确认.');
    return;
  }
  if (!row.customerId) {
    ElMessage.warning('该订单缺少客户信息, 无法发起退货.');
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
    case 'view-detail': goDetail(String(row.id)); break;
    case 'edit': handleEdit(row); break;
    // T131 Part 1 — 'approve' (DRAFT→CONFIRMED) 仍走 confirm; 提交财务审核走独立的链式/单次路径.
    case 'approve': handleAction(String(row.id), 'confirm'); break;
    case 'submit': case 'submit-for-review':
      void handleSubmitForReviewRow(row); break;
    case 'cancel': handleAction(String(row.id), 'cancel'); break;
    case 'print-pdf': void safePrint('sales-order', factoryId.value, String(row.id), { fileName: `销售订单_${row.orderNumber || row.id}` }); break;
    case 'copy': void handleCopyOrder(row); break;
    case 'delete': void handleDeleteOrder(row); break;
    case 'return': openReturnDialog(row); break;
    case 'convert-to-production':
      ElMessage.info(`请为订单 ${row.orderNumber || row.id} 创建生产计划`);
      void router.push({
        path: '/production/plans',
        query: { salesOrderId: String(row.id), action: 'create' },
      });
      break;
    // convert-to-purchase / edit-price were listed in rowActionsConfig with no
    // handler here (fell to the generic debug toast below — a real dead-menu
    // item, not the key-mismatch class but the same "menu promises an action
    // with no wiring" symptom). procurement/orders/list.vue has no query-param
    // prefill for a from-SO purchase flow yet, so route to the order detail
    // (same wayfinding pattern already used for edit-price/view-price-history
    // on the purchaseOrder side) rather than leave a dead click.
    case 'convert-to-purchase':
      ElMessage.info(`请在销售单 ${row.orderNumber || row.id} 详情页查看缺料情况后手动生成采购单`);
      goDetail(String(row.id));
      break;
    case 'edit-price':
      ElMessage.info(`请在销售单 ${row.orderNumber || row.id} 详情页维护行项目单价`);
      goDetail(String(row.id));
      break;
    default: ElMessage.warning(`该操作暂不支持: ${actionId}`);
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

// T131 Part 1 (F5) — per-row 提审 loading. List 不能用单一共享 ref (多行可并发操作).
const submittingIds = ref<Set<string>>(new Set());

// T131 Part 3 — 多选批量操作 (table view only).
const selectedRows = ref<TableRow[]>([]);
const batchLoading = ref(false);
// 批量提审/确认/取消/删除各自的资格状态集合 (用于 bulk-bar 按钮可见/可用).
const BATCH_SUBMIT_STATUSES = ['DRAFT', 'CONFIRMED', 'FINANCE_REJECTED'];
const BATCH_CONFIRM_STATUSES = ['DRAFT'];
const BATCH_CANCEL_STATUSES = ['DRAFT', 'CONFIRMED'];
const BATCH_DELETE_STATUSES = ['DRAFT'];
// el-table type=selection 的 :selectable 用 (写权限 + 至少能进入某个批量操作的状态).
function canSelectRow(row: TableRow): boolean {
  if (!canWrite.value) return false;
  const status = String(row.status || '');
  return BATCH_SUBMIT_STATUSES.includes(status) || BATCH_CANCEL_STATUSES.includes(status);
}
function handleSelectionChange(rows: TableRow[]) {
  selectedRows.value = rows;
}
// bulk-bar 各按钮的可用性 (有任一选中行匹配该操作资格).
const hasBatchSubmittable = computed(() =>
  selectedRows.value.some((r) => BATCH_SUBMIT_STATUSES.includes(String(r.status || '')))
);
const hasBatchConfirmable = computed(() =>
  selectedRows.value.some((r) => BATCH_CONFIRM_STATUSES.includes(String(r.status || '')))
);
const hasBatchCancellable = computed(() =>
  selectedRows.value.some((r) => BATCH_CANCEL_STATUSES.includes(String(r.status || '')))
);
const hasBatchDeletable = computed(() =>
  selectedRows.value.some((r) => BATCH_DELETE_STATUSES.includes(String(r.status || '')))
);

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
  items: [{ productTypeId: '', quantity: 0, unit: '份', unitPrice: 0, taxRate: 13 }] as OrderItem[],
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

// 2026-07-02 fix (LIUSHANMEN "同仓库多名字"): 来源仓库下拉之前是硬编码
// "总仓 (WH-LOG)" / "线边仓 (WH-WKS)" 两个选项 — 客户在仓库配置里改了 DB name
// 之后这里仍然显示旧硬编码名字。改为加载真实仓库列表, DB name 为准；
// 加载失败/为空时才 fallback 回旧的两个硬编码选项。
const warehouseList = ref<TableRow[]>([]);
async function loadWarehouses() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/factory/warehouses`, { _silent: true } as never);
    warehouseList.value = Array.isArray(res?.data) ? res.data.filter((w: TableRow) => w.isActive !== false) : [];
  } catch { warehouseList.value = []; }
}
const LEGACY_WAREHOUSE_OPTIONS = [
  { label: '总仓 (WH-LOG)', value: 'WH-LOG' },
  { label: '线边仓 (WH-WKS)', value: 'WH-WKS' },
];
const warehouseSelectOptions = computed(() => {
  if (!warehouseList.value.length) return LEGACY_WAREHOUSE_OPTIONS;
  return warehouseList.value.map((w) => ({
    label: `${warehouseDisplayName(String(w.name || ''), String(w.code || ''))} (${w.code})`,
    value: String(w.code || ''),
  }));
});

// T130 Feature C — 来源仓库 = OPERATOR memory. 记住该操作员上次选的仓库, 新行预填.
// Key 按 user.id 隔离 (不同操作员各自记忆), anon 兜底.
const warehouseMemoryKey = computed(
  () => `cretas_so_last_warehouse_${authStore.user?.id ?? 'anon'}`
);
function getRememberedWarehouse(): string {
  // 🔴 BLOCKING fix (2026-07-03, Steve #1 客户优先级 "有货发不出"): fallback 从
  // 'WH-LOG' 改为 '' (空). 空 = 让后端 G1 跨仓 FEFO 发现(在 WH-WKS 等任意有货仓里找批次);
  // 显式 'WH-LOG' = 单仓严格匹配, 会挡住 G1 本来要修的路径(成品在 WH-WKS 时批次分配对话框空).
  // 有记忆的显式选择(用户主动选过)仍然保留 — 只有"从未选过"时才 fallback 为空,不再硬编码仓库.
  try {
    return localStorage.getItem(warehouseMemoryKey.value) || '';
  } catch {
    return '';
  }
}
function rememberWarehouse(code: string) {
  // 只在用户主动选择时持久化 (template @change 调用), 不在程序赋值时触发.
  if (!code) return;
  try {
    localStorage.setItem(warehouseMemoryKey.value, code);
  } catch { /* localStorage 不可用 — 记忆是 nice-to-have, 不阻塞 */ }
}

// T130 Feature C+D — 统一空明细行工厂. 单位默认 '份' (D: 份=下单主单位),
// 来源仓库取操作员记忆 (C). addItem / ensureTrailingEmptyRow / handleEdit / openCreateDialog 全走这里.
function emptyOrderItem(): OrderItem {
  return {
    productTypeId: '',
    quantity: 0,
    unit: '份',
    unitPrice: 0,
    taxRate: 13,
    specification: '',
    boxQuantity: null,
    sourceWarehouseCode: getRememberedWarehouse(),
    priceMemoryHint: null,
    contractPriceHint: null,
  };
}

// T130 Feature A — 自动加行: 保证末尾恰有一个空行 (无 productTypeId) 供继续录入.
// 去重多余的尾部空行 (product-clear 后不累积), 若末行已填则追加一个新空行.
function ensureTrailingEmptyRow() {
  const items = form.value.items;
  // 移除中间出现的"全空"行只在末尾保留一个 — 这里仅处理末尾: 砍掉多余的尾部空行.
  while (
    items.length > 1 &&
    !items[items.length - 1].productTypeId &&
    !items[items.length - 2].productTypeId
  ) {
    items.pop();
  }
  const last = items[items.length - 1];
  if (!last || last.productTypeId) {
    items.push(emptyOrderItem());
  }
}

// T130 Feature A — 提交时只取已选产品的行, 忽略末尾空行 (不入 payload).
function getSubmittableItems(): OrderItem[] {
  return form.value.items.filter((i) => Boolean(i.productTypeId));
}

// T130 Feature B — 业务员是否被用户手动改过 (改过则不再自动预填覆盖).
const salespersonTouched = ref(false);

// T130 Feature D6 — 下单数量列的 el-input-number ref 集合, 供 Tab 跨行跳转.
const quantityRefs = ref<Array<{ focus: () => void } | null>>([]);

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
  loadGoldSummary(); loadWarehouses();
  window.addEventListener('beforeunload', handleBeforeUnload);
});
onBeforeUnmount(() => { window.removeEventListener('beforeunload', handleBeforeUnload); });

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  // T131 Part 3 — 每次刷新/翻页/筛选清空选择, 避免跨页 stale selection (mirror finance/adjustments).
  selectedRows.value = [];
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

function addItem() { form.value.items.push(emptyOrderItem()); }
function removeItem(idx: number) {
  if (form.value.items.length > 1) form.value.items.splice(idx, 1);
  // T130 Feature A — 删行后仍保证末尾一个空行可继续录入.
  ensureTrailingEmptyRow();
}

function onProductSelect(item: TableRow, productId: string) {
  const p = products.value.find((x: TableRow) => x.id === productId);
  if (p) {
    item.specification = p.specification || p.packageSpec || '';
    // T130 Feature D — 单位口径: 份=下单主单位, 盒=包装形态(F006 1份=1盒).
    // 产品字典若标 '盒' → 规范成 '份' (否则单位 el-select 没 '盒' 选项会显空白).
    const pu = String(p.unit || '份');
    item.unit = pu === '盒' ? '份' : (pu || item.unit || '份');
    if (p.unitPrice != null && (item.unitPrice == null || item.unitPrice === 0)) {
      item.unitPrice = Number(p.unitPrice);
    }
    // P1-3: spec 自动填后立即调 calcBox, 抄码品会清空 boxQuantity (内部判断).
    // T130: calcBox 现支持两分支 (份→qty/coeff, 箱→qty), 总是重算箱数 (只读列).
    calcBox(item);
  } else {
    // T130 Feature A — 用户清空产品 → 该行变回空行; 收口尾部空行, 不累积重复空行.
    ensureTrailingEmptyRow();
  }
  // Issue #793 — 客户协议价 lookup. Customer + product 都选好后查协议价, 命中即覆盖 unitPrice.
  // Priority: 协议价 (合同) > 上次成交价 > BOM 默认价. 用户仍可手动覆盖.
  fetchContractPrice(item, productId);
  // Sprint 4 W2 S-PRICE-1 (R1) — 上次成交价 hint:
  // 选完产品立即异步查记忆价, 显在 unitPrice 输入框下. 用户**预先看到**, 不是输完后再被告知偏离.
  fetchPriceMemory(item, productId);
  // T130 Feature A — 选好产品后保证末尾仍有空行可继续录入.
  if (productId) ensureTrailingEmptyRow();
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

// U-SP5: 毛利红线 check — 单价 blur 时调后端 check-margin 端点
// GrossMarginCheckResult: { belowRedline: boolean | null, warningMessage: string | null }
// belowRedline=true  → sticky 红色警告 (不卡死提交, fool-proof)
// belowRedline=null  → 未配置毛利红线, 静默
// belowRedline=false → 合格, 静默
async function checkMarginOnBlur(item: OrderItem) {
  if (!factoryId.value || !item.productTypeId || item.unitPrice == null || item.unitPrice <= 0) return;
  try {
    const res = await post<{ belowRedline: boolean | null; warningMessage: string | null }>(
      `/${factoryId.value}/sales/check-margin`,
      { productTypeId: item.productTypeId, quotedPrice: item.unitPrice },
    );
    if (res.success && res.data?.belowRedline === true && res.data?.warningMessage) {
      ElMessage({
        message: res.data.warningMessage,
        type: 'warning',
        duration: 0,
        showClose: true,
      });
    }
    // belowRedline=false or null → 静默 (未配置 or 合格)
  } catch {
    // 静默失败 — 红线 check 是 advisory, 不阻塞下单
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
  if (!p) return;
  // 未配置箱规 → 箱数 null (模板会提示去产品字典维护, fool-proof Rule 5).
  if (!p.boxConversionCoefficient || Number(p.boxConversionCoefficient) <= 0) {
    item.boxQuantity = null;
    return;
  }
  const coeff = Number(p.boxConversionCoefficient);
  const qty = Number(item.quantity || 0);
  if (qty <= 0) return;
  // T130 Feature D — 两分支口径:
  //   一级单位 (份, 或与产品单位一致) → 箱数 = 数量 / 箱规系数
  //   二级单位 (箱)                   → 箱数 = 数量本身 (用户直接按箱下单)
  const unit = String(item.unit || '');
  const pu = String(p.unit || '份');
  if (!unit || unit === pu || unit === '份') {
    item.boxQuantity = Math.round((qty / coeff) * 100) / 100;
  } else if (unit === '箱') {
    item.boxQuantity = qty;
  }
  // 其他单位 → 不动 (defensive)
}

// T130 Feature D — 产品是否未配置箱规 (模板用来显警告 + 决定箱数列是否可显数字).
function isBoxUnconfigured(item: TableRow): boolean {
  if (!item.productTypeId || isAbacaItem(item)) return false;
  const p = products.value.find((x: TableRow) => x.id === item.productTypeId);
  if (!p) return false;
  return !p.boxConversionCoefficient || Number(p.boxConversionCoefficient) <= 0;
}

// T130 Feature D — 该行规格展示 (只读): 产品字典 specification / packageSpec.
function specDisplay(item: TableRow): string {
  if (item.specification) return String(item.specification);
  const p = products.value.find((x: TableRow) => x.id === item.productTypeId);
  return String(p?.specification || p?.packageSpec || '');
}

// T130 Feature D — 单位下拉选项: 份 + (产品配了箱规才给 箱).
function unitOptions(item: TableRow): string[] {
  const p = products.value.find((x: TableRow) => x.id === item.productTypeId);
  const opts = ['份'];
  if (p && Number(p.boxConversionCoefficient) > 0) opts.push('箱');
  return opts;
}

// T130 Feature B — 客户选择 → 智能预填业务员 (frontend-only).
// 优先级: ① 上一单业务员 (DEFER, 需后端 last-order 查询) → ② 客户归属业务员 (CustomerDTO.assignedSalesUserName)
//          → ③ 当前登录用户. salespersonTouched=true (用户手动改过) 则不覆盖.
// TODO(T130 priority ①): 接后端 "该客户最近一单业务员" 端点后, 在 ② 之前插入 last-order 优先级.
function onCustomerSelect(customerId: string) {
  // Option A (Steve-confirmed): 换客户即重算业务员 → 重置 touched 后立即按优先级预填.
  // 用户之后仍可在业务员下拉手动覆盖 (那会再次置 touched=true).
  // 其他客户相关 hint (协议价/记忆价) 由各行 onProductSelect 在用户选产品时查, 这里不动.
  salespersonTouched.value = false;
  let prefill = '';
  // ② 客户归属业务员 — 但必须在 salesEmployees 选项中存在 (否则 el-select 显空白) 才用, 否则落到 ③.
  const cust = customers.value.find((c) => String(c.id) === String(customerId));
  const assigned = cust ? String((cust as TableRow).assignedSalesUserName || '') : '';
  if (assigned && salesEmployees.value.some((e) => String(e.fullName || '') === assigned)) {
    prefill = assigned;
  }
  // ③ 当前登录用户
  if (!prefill) {
    prefill = String(authStore.user?.fullName || authStore.user?.username || '');
  }
  form.value.salesperson = prefill;
}

// T130 Feature D6 — Tab 跨行跳到下一行下单数量; Shift+Tab 跳上一行; 末行 Tab → 创建按钮.
// 只把"下单数量"列纳入 Tab 链 (录入热路径). inline 实现, 不抽 composable.
const submitButtonRef = ref<{ ref?: HTMLElement } | { $el?: HTMLElement } | null>(null);
function handleQuantityTab(e: KeyboardEvent, idx: number) {
  e.preventDefault();
  if (e.shiftKey) {
    quantityRefs.value[idx - 1]?.focus();
    return;
  }
  const next = quantityRefs.value[idx + 1];
  if (next) {
    next.focus();
  } else {
    // 末行 Tab → 聚焦"创建/保存"按钮 (Steve-default).
    const btn = submitButtonRef.value as { ref?: HTMLElement; $el?: HTMLElement } | null;
    const el = btn?.ref || btn?.$el;
    el?.focus?.();
  }
}

async function handleCreate() {
  if (!form.value.customerId) return ElMessage.warning('请选择客户');
  // T130 Feature A — 只校验/提交"已选产品"的行, 忽略末尾自动空行 (不再因尾部空行报"请为所有明细选择产品").
  const selectedItems = getSubmittableItems();
  if (selectedItems.length === 0) return ElMessage.warning('请至少添加一个订单明细');
  // 数量校验: 已选产品行不允许0或负数 (有产品但数量空 → 报错, 不静默丢弃)
  if (selectedItems.some((i) => !i.quantity || Number(i.quantity) <= 0)) return ElMessage.warning('产品数量必须大于0');
  // 单位校验
  if (selectedItems.some((i) => !i.unit)) return ElMessage.warning('请填写所有明细的单位');
  // 销售单价校验
  if (selectedItems.some((i) => i.unitPrice == null || Number(i.unitPrice) < 0)) return ElMessage.warning('请填写所有明细的销售单价');
  // SKU 重复校验 (只看已选行)
  const productIds = selectedItems.map((i) => i.productTypeId).filter(Boolean);
  if (new Set(productIds).size !== productIds.length) return ElMessage.warning('同一订单不能添加重复的产品');
  try {
    // 提交体只带已选行, 末尾空行永不发后端.
    const res = await post(`/${factoryId.value}/sales/orders`, { ...form.value, items: selectedItems });
    if (res.success) { ElMessage.success('创建成功'); dialogVisible.value = false; loadData(); }
    else { ElMessage.error(res.message || '创建失败'); }
  } catch (e: unknown) {
    // 2026-05-22 hotfix (Steve P0): 409 dedup / 乐观锁 was being silently swallowed.
    // axios interceptor for vanilla 409 (no actionHint) suppresses toast on assumption
    // caller handles it — but this catch was empty. Now: surface message + reload list
    // so user knows what's happening.
    // Per fool-proof-design.md Rule 4 (写操作幂等防重复) + 4 位一体 (d): 必含 next action.
    const err = e as { status?: number; message?: string; response?: { status?: number; data?: { message?: string; actionHint?: string } } };
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
  // E-FP-2 (fool-proof Rule 3): 取消改走标准原因采集 dialog, 不用裸 confirm.
  if (action === 'cancel') {
    openCancelDialog([orderId]);
    return;
  }
  const map: Record<string, { label: string; url: string }> = {
    confirm: { label: '确认', url: `/${factoryId.value}/sales/orders/${orderId}/confirm` },
    cancel: { label: '取消', url: `/${factoryId.value}/sales/orders/${orderId}/cancel` },
    // R23-Pre3: FINANCE_REJECTED → resubmit for finance review (backend already
    // supports CONFIRMED || FINANCE_REJECTED → PENDING_FINANCE_REVIEW transition).
    resubmit: { label: '重新提交', url: `/${factoryId.value}/sales/orders/${orderId}/submit-for-review` },
    // T131 Part 1 — CONFIRMED/FINANCE_REJECTED 单次提交财务审核 (与 resubmit 同端点, 文案不同).
    'submit-for-review': { label: '提交财务审核', url: `/${factoryId.value}/sales/orders/${orderId}/submit-for-review` },
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

// ==================== T131 提交财务审核 (链式) + 多选批量 ====================
//
// 后端状态机 (不变):
//   confirm:              DRAFT → CONFIRMED
//   submit-for-review:    CONFIRMED | FINANCE_REJECTED → PENDING_FINANCE_REVIEW
// DRAFT 行"提交财务审核"需链式: 先 confirm 再 submit-for-review.
//
// 链式部分失败语义 (审计 #1 风险):
//   confirm OK + submit-for-review 失败 → 订单停在 CONFIRMED (可对"已确认"行重试提审, 可恢复).
//   必须用 'confirmed_only' 桶 / sticky toast 与彻底失败区分.

type SubmitOutcome = 'success' | 'pending_review' | 'auto_approved' | 'confirmed_only' | 'failed';

type SalesOrderActionResponse = {
  success?: boolean;
  message?: string;
  data?: {
    status?: unknown;
  };
};

function responseOrderStatus(response: unknown): string {
  const res = response as SalesOrderActionResponse;
  return typeof res.data?.status === 'string' ? res.data.status : '';
}

function outcomeMessage(orderNumber: string, outcome: SubmitOutcome): string {
  if (outcome === 'auto_approved') return `订单 ${orderNumber} 未触发审批阈值，已免审通过`;
  if (outcome === 'pending_review') return `订单 ${orderNumber} 已进入财务审核`;
  return `订单 ${orderNumber} 已提交财务审核`;
}

function orderAmount(row: TableRow): number {
  const value = Number(row.totalAmount || 0);
  return Number.isFinite(value) ? value : 0;
}

function isExternalChannelOrder(row: TableRow): boolean {
  return Boolean(String(row.externalOrderTitle || '').trim());
}

function approvalDecisionHint(row: TableRow): string {
  const amount = orderAmount(row);
  if (isExternalChannelOrder(row)) {
    return `检测到外部渠道订单；是否免审由后台审批配置决定。订单金额：${formatAmount(amount)}。`;
  }
  if (amount > 5000) {
    return `订单金额 ${formatAmount(amount)} 超过默认阈值，预计进入财务审核。`;
  }
  return `订单金额 ${formatAmount(amount)} 未超过默认阈值，预计免审通过。`;
}

/** 取后端错误信息 (api-response-handling: error.response.data.message). */
function extractErrMessage(e: unknown, fallback: string): string {
  const err = e as { response?: { data?: { message?: string } }; message?: string };
  return err?.response?.data?.message || err?.message || fallback;
}

/**
 * 提交财务审核底层 helper. 不弹 toast / 不 loadData — 由调用方 (单行/批量) 统一处理结果.
 * @returns 'success' (DRAFT 链式两步都成 或 非 DRAFT 单次成) /
 *          'confirmed_only' (DRAFT 已 confirm 但 submit 失败) /
 *          抛错 (confirm 本身失败, 由调用方 catch → 'failed').
 */
async function submitOrderForFinanceReview(
  orderId: string,
  opts: { fromDraft: boolean }
): Promise<SubmitOutcome> {
  if (opts.fromDraft) {
    // 链式: confirm → submit-for-review.
    const confirmRes = await post(`/${factoryId.value}/sales/orders/${orderId}/confirm`);
    if (!confirmRes?.success) {
      // confirm 失败 → 订单仍 DRAFT, 当彻底失败抛出.
      throw new Error(confirmRes?.message || '确认订单失败');
    }
    const confirmedStatus = responseOrderStatus(confirmRes);
    if (confirmedStatus === 'PENDING_FINANCE_REVIEW') return 'pending_review';
    if (confirmedStatus === 'FINANCE_APPROVED') return 'auto_approved';
    if (confirmedStatus && confirmedStatus !== 'CONFIRMED') return 'success';
    try {
      const submitRes = await post(`/${factoryId.value}/sales/orders/${orderId}/submit-for-review`);
      if (!submitRes?.success) {
        throw new Error(submitRes?.message || '提交审核失败');
      }
      const submittedStatus = responseOrderStatus(submitRes);
      if (submittedStatus === 'PENDING_FINANCE_REVIEW') return 'pending_review';
      if (submittedStatus === 'FINANCE_APPROVED') return 'auto_approved';
      return 'success';
    } catch (e) {
      // confirm OK 但 submit 失败 → 订单现为 CONFIRMED, 可恢复. 标 confirmed_only.
      const err = new Error(extractErrMessage(e, '提交审核失败')) as Error & { confirmedOnly?: boolean };
      err.confirmedOnly = true;
      throw err;
    }
  }
  // 非 DRAFT (CONFIRMED / FINANCE_REJECTED) — 单次.
  const res = await post(`/${factoryId.value}/sales/orders/${orderId}/submit-for-review`);
  if (!res?.success) {
    throw new Error(res?.message || '提交审核失败');
  }
  const submittedStatus = responseOrderStatus(res);
  if (submittedStatus === 'PENDING_FINANCE_REVIEW') return 'pending_review';
  if (submittedStatus === 'FINANCE_APPROVED') return 'auto_approved';
  return 'success';
}

/** 单行"提交审批判定"入口 (fool-proof Rule 1/2 带订单号、金额、阈值预判 + Rule 4 per-row loading). */
async function handleSubmitForReviewRow(row: TableRow): Promise<void> {
  const orderId = String(row.id || '');
  const orderNumber = String(row.orderNumber || row.id || '');
  const status = String(row.status || '');
  const fromDraft = status === 'DRAFT';
  if (submittingIds.value.has(orderId)) return; // 防重复点击
  // fool-proof Rule 1/2: 确认 dialog 带订单号 + 金额 + 阈值预判; 最终以后端可配置审批链为准.
  const confirmMsg = fromDraft
    ? `确认提交销售订单 ${orderNumber} 并自动判定审批？\n\n${approvalDecisionHint(row)}\n将先确认订单，再按后台审批配置自动分流：超过阈值进入财务审核，未触发阈值或满足免审配置的订单自动通过。`
    : `确认将销售订单 ${orderNumber} 重新提交审批判定？\n\n${approvalDecisionHint(row)}\n系统会按后台审批配置自动分流，最终结果以后端返回为准。`;
  try {
    await ElMessageBox.confirm(confirmMsg, '提交审批判定', {
      confirmButtonText: '提交并判定',
      cancelButtonText: '取消',
      type: 'info',
    });
  } catch {
    return; // 用户取消
  }
  submittingIds.value.add(orderId);
  try {
    const outcome = await submitOrderForFinanceReview(orderId, { fromDraft });
    ElMessage.success(outcomeMessage(orderNumber, outcome));
    await loadData();
  } catch (e) {
    const err = e as Error & { confirmedOnly?: boolean };
    if (err.confirmedOnly) {
      // 链式部分失败 — sticky toast 明示"已确认但提审失败", 引导对已确认行重试.
      ElMessage({
        message: `订单 ${orderNumber} 已确认，但提交审核失败：${err.message} — 请对「已确认」行重试提审`,
        type: 'error',
        duration: 0,
        showClose: true,
      });
    } else {
      // confirm 本身失败 (订单仍 DRAFT) — 标准错误 toast.
      ElMessage({ message: `订单 ${orderNumber} 提交失败：${err.message}`, type: 'error', duration: 0, showClose: true });
    }
    await loadData();
  } finally {
    submittingIds.value.delete(orderId);
  }
}

// ---- T131 Part 3 批量操作 (v1 前端循环 Promise.allSettled) ----
// TODO(v2): 若批量规模常 >10, 改后端批量端点减少 N 次往返.

/** 弹批量确认框, 列前 5 个订单号 + "等共 N 条". 返回 true=确认 / false=取消. */
async function confirmBatch(actionLabel: string, rows: TableRow[]): Promise<boolean> {
  const nums = rows.map((r) => String(r.orderNumber || r.id || ''));
  const preview = nums.slice(0, 5).join('、');
  const tail = nums.length > 5 ? ` 等共 ${nums.length} 条` : ` 共 ${nums.length} 条`;
  const shouldShowApprovalPreview = actionLabel.includes('审批');
  const externalChannelCount = rows.filter(isExternalChannelOrder).length;
  const pendingReviewCount = rows.filter((r) => !isExternalChannelOrder(r) && orderAmount(r) > 5000).length;
  const exemptCount = shouldShowApprovalPreview ? rows.length - pendingReviewCount : 0;
  const approvalPreview = shouldShowApprovalPreview
    ? `\n\n预计结果：${pendingReviewCount} 条进入财务审核，${exemptCount} 条可能免审或自动通过（其中外部渠道 ${externalChannelCount} 条）。最终以后端审批配置为准。`
    : '';
  try {
    await ElMessageBox.confirm(`确认对 ${preview}${tail} 执行「${actionLabel}」？${approvalPreview}`, `批量${actionLabel}`, {
      confirmButtonText: shouldShowApprovalPreview ? '提交并判定' : '执行',
      cancelButtonText: '取消',
      type: 'warning',
    });
    return true;
  } catch {
    return false;
  }
}

/** 批量提交审批判定 — three-bucket (success / confirmed_only / failed). */
async function handleBatchSubmitForReview(): Promise<void> {
  const eligible = selectedRows.value.filter((r) => BATCH_SUBMIT_STATUSES.includes(String(r.status || '')));
  if (eligible.length === 0) {
    ElMessage.warning('选中订单中没有可提交审批判定的 (需为草稿/已确认/已驳回)');
    return;
  }
  if (!(await confirmBatch('提交审批判定', eligible))) return;
  batchLoading.value = true;
  try {
    const results = await Promise.allSettled(
      eligible.map((r) =>
        submitOrderForFinanceReview(String(r.id), { fromDraft: String(r.status || '') === 'DRAFT' })
          .then((outcome) => ({ row: r, outcome }))
      )
    );
    let success = 0;
    let pendingReview = 0;
    let autoApproved = 0;
    let confirmedOnly = 0;
    let failed = 0;
    const failedList: string[] = [];
    for (let i = 0; i < results.length; i++) {
      const res = results[i];
      const orderNumber = String(eligible[i].orderNumber || eligible[i].id || '');
      if (res.status === 'fulfilled') {
        if (res.value.outcome === 'confirmed_only') confirmedOnly++;
        else if (res.value.outcome === 'pending_review') pendingReview++;
        else if (res.value.outcome === 'auto_approved') autoApproved++;
        else success++;
      } else {
        const err = res.reason as Error & { confirmedOnly?: boolean };
        if (err?.confirmedOnly) {
          confirmedOnly++;
        } else {
          failed++;
          failedList.push(`${orderNumber}: ${err?.message || '提交失败'}`);
        }
      }
    }
    let msg = `已完成提审 ${success} 条；已确认待提审 ${confirmedOnly} 条（可对「已确认」行重试）；失败 ${failed} 条。`;
    msg = `已提交 ${success} 条；进入财务审核 ${pendingReview} 条；免审通过 ${autoApproved} 条；已确认待重试 ${confirmedOnly} 条；失败 ${failed} 条。`;
    if (confirmedOnly > 0) {
      msg += '已确认订单可直接「提交财务审核」重试，无需重走确认。';
    }
    ElNotification({
      title: '批量提交财务审核结果',
      message: msg,
      type: failed > 0 ? 'warning' : 'success',
      duration: 0,
    });
    if (failedList.length > 0) {
      void ElMessageBox.alert(failedList.join('\n'), '失败明细', { confirmButtonText: '知道了' });
    }
  } finally {
    await loadData();
    selectedRows.value = [];
    batchLoading.value = false;
  }
}

/** 批量执行单步操作 (确认/取消/删除) — two-bucket (success / failed). */
async function runBatchSimple(
  actionLabel: string,
  eligibleStatuses: string[],
  doOne: (row: TableRow) => Promise<void>
): Promise<void> {
  const eligible = selectedRows.value.filter((r) => eligibleStatuses.includes(String(r.status || '')));
  if (eligible.length === 0) {
    ElMessage.warning(`选中订单中没有可${actionLabel}的`);
    return;
  }
  if (!(await confirmBatch(actionLabel, eligible))) return;
  batchLoading.value = true;
  try {
    const results = await Promise.allSettled(eligible.map((r) => doOne(r)));
    let success = 0;
    let failed = 0;
    const failedList: string[] = [];
    for (let i = 0; i < results.length; i++) {
      const res = results[i];
      const orderNumber = String(eligible[i].orderNumber || eligible[i].id || '');
      if (res.status === 'fulfilled') success++;
      else {
        failed++;
        failedList.push(`${orderNumber}: ${extractErrMessage(res.reason, actionLabel + '失败')}`);
      }
    }
    ElNotification({
      title: `批量${actionLabel}结果`,
      message: `成功 ${success} 条，失败 ${failed} 条。`,
      type: failed > 0 ? 'warning' : 'success',
      duration: 0,
    });
    if (failedList.length > 0) {
      void ElMessageBox.alert(failedList.join('\n'), '失败明细', { confirmButtonText: '知道了' });
    }
  } finally {
    await loadData();
    selectedRows.value = [];
    batchLoading.value = false;
  }
}

async function handleBatchConfirm(): Promise<void> {
  await runBatchSimple('确认', BATCH_CONFIRM_STATUSES, async (row) => {
    const res = await post(`/${factoryId.value}/sales/orders/${row.id}/confirm`);
    if (!res?.success) throw new Error(res?.message || '确认失败');
  });
}

async function handleBatchCancel(): Promise<void> {
  // E-FP-2 (fool-proof Rule 3): 批量取消先采集统一原因 (整批共用一个原因), 再逐单调用.
  const eligible = selectedRows.value.filter((r) =>
    BATCH_CANCEL_STATUSES.includes(String(r.status || ''))
  );
  if (eligible.length === 0) {
    ElMessage.warning('选中订单中没有可取消的');
    return;
  }
  openCancelDialog(eligible.map((r) => String(r.id)));
}

// ==================== E-FP-2 取消原因采集 dialog (fool-proof Rule 3) ====================
// 标准原因 dropdown + 选"其他"展 textarea. 单单/批量共用 (pendingCancelIds 区分).
const cancelDialogVisible = ref(false);
const cancelSubmitting = ref(false);
const pendingCancelIds = ref<string[]>([]);
const cancelReasonCode = ref('');
const cancelReasonOther = ref('');
const CANCEL_REASONS = ['客户撤单', '原料缺货', '质量问题', '排程冲突', '价格分歧', '其他'];

function openCancelDialog(ids: string[]) {
  pendingCancelIds.value = ids;
  cancelReasonCode.value = '';
  cancelReasonOther.value = '';
  cancelDialogVisible.value = true;
}

// 最终原因: 选"其他"用 textarea 内容, 否则用 dropdown 选项.
const resolvedCancelReason = computed<string>(() =>
  cancelReasonCode.value === '其他' ? cancelReasonOther.value.trim() : cancelReasonCode.value
);

async function submitCancel(): Promise<void> {
  if (!cancelReasonCode.value) {
    ElMessage.warning('请选择取消原因');
    return;
  }
  if (cancelReasonCode.value === '其他' && !cancelReasonOther.value.trim()) {
    ElMessage.warning('请填写"其他"原因');
    return;
  }
  const ids = pendingCancelIds.value;
  const reason = resolvedCancelReason.value;
  cancelSubmitting.value = true;
  try {
    if (ids.length === 1) {
      const res = await post(
        `/${factoryId.value}/sales/orders/${ids[0]}/cancel`,
        undefined,
        { params: { reason } }
      );
      if (res?.success) { ElMessage.success('取消成功'); }
      else { ElMessage.error(res?.message || '取消失败'); return; }
    } else {
      const results = await Promise.allSettled(
        ids.map((id) =>
          post(`/${factoryId.value}/sales/orders/${id}/cancel`, undefined, { params: { reason } })
            .then((res) => { if (!res?.success) throw new Error(res?.message || '取消失败'); })
        )
      );
      const success = results.filter((r) => r.status === 'fulfilled').length;
      const failed = results.length - success;
      ElNotification({
        title: '批量取消结果',
        message: `成功 ${success} 条，失败 ${failed} 条。`,
        type: failed > 0 ? 'warning' : 'success',
        duration: failed > 0 ? 0 : 4500,
      });
    }
    cancelDialogVisible.value = false;
    await loadData();
  } finally {
    cancelSubmitting.value = false;
  }
}

async function handleBatchDelete(): Promise<void> {
  await runBatchSimple('删除', BATCH_DELETE_STATUSES, async (row) => {
    const res = await del(`/${factoryId.value}/sales/orders/${row.id}`);
    if (!res?.success) throw new Error(res?.message || '删除失败');
  });
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
          unit: String(item.unit || '份'),
          unitPrice: Number(item.unitPrice || 0),
          // PR #173 reviewer follow-up M-4 (May 9 2026): preserve specification + boxQuantity
          // on edit. 旧 bug: handleEdit 重建 form.items 时漏了这两字段, 用户编辑现有订单后
          // 提交导致 specification/boxQuantity 被覆盖为空 (新 form 不含 → 后端把 null 写库).
          // 抄码品识别 + 箱数自动算依赖这两字段, 不能丢.
          specification: String(item.specification || ''),
          boxQuantity: item.boxQuantity != null ? Number(item.boxQuantity) : null,
          taxRate: item.taxRate != null ? Number(item.taxRate) : 13,
          // T130 Feature C / F9 fix (issue #525): handleEdit 之前漏带 sourceWarehouseCode,
          // 编辑现有订单后提交会把来源仓库覆盖为空 → 补回(保留原值,不再强制回 WH-LOG).
          // 🔴 BLOCKING fix (2026-07-03): 空 (未声明) 是合法且现在是"好"状态 (G1 跨仓 FEFO),
          // 不要把编辑时本来是空的行强改成显式 WH-LOG — 那会重新挡住 G1 修的路径.
          sourceWarehouseCode: String(item.sourceWarehouseCode || ''),
        }))
      : [emptyOrderItem()],
    contractFileUrl: (row.contractFileUrl ? String(row.contractFileUrl) : null) as string | null,
    contractFileName: (row.contractFileName ? String(row.contractFileName) : null) as string | null,
    customFields: {} as TableRow,
    version: typeof row.version === 'number' ? row.version : null,
  };
  // T130 Feature B — handleEdit 是程序赋值 form.customerId (非用户 @change), 不触发 onCustomerSelect,
  // 故业务员保留订单原值; 显式标 touched 防止后续误覆盖.
  salespersonTouched.value = true;
  // T130 Feature A — 编辑态也追加一个尾部空行, 便于继续加品. calcBox 重算已有行箱数 (只读列).
  form.value.items.forEach((it) => calcBox(it));
  ensureTrailingEmptyRow();
  dialogVisible.value = true;
}

async function handleSave() {
  if (editingOrderId.value) {
    // Update existing order
    if (!form.value.customerId) return ElMessage.warning('请选择客户');
    // T130 Feature A — 编辑提交同样剔除末尾空行, 并对已选行做与创建一致的校验.
    const selectedItems = getSubmittableItems();
    if (selectedItems.length === 0) return ElMessage.warning('请至少添加一个订单明细');
    if (selectedItems.some((i) => !i.quantity || Number(i.quantity) <= 0)) return ElMessage.warning('产品数量必须大于0');
    if (selectedItems.some((i) => !i.unit)) return ElMessage.warning('请填写所有明细的单位');
    if (selectedItems.some((i) => i.unitPrice == null || Number(i.unitPrice) < 0)) return ElMessage.warning('请填写所有明细的销售单价');
    const editProductIds = selectedItems.map((i) => i.productTypeId).filter(Boolean);
    if (new Set(editProductIds).size !== editProductIds.length) return ElMessage.warning('同一订单不能添加重复的产品');
    try {
      const res = await put(`/${factoryId.value}/sales/orders/${editingOrderId.value}`, { ...form.value, items: selectedItems });
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
  // T130 Feature B — 新建时业务员未被用户碰过, 选客户后允许自动预填.
  salespersonTouched.value = false;
  form.value = {
    customerId: '',
    requiredDeliveryDate: '',
    deliveryAddress: '',
    remark: '',
    salesperson: '',
    shippingIncluded: false,
    shippingFee: 0,
    extraFees: [] as Array<{ name: string; amount: number; remark: string }>,
    // T130 Feature C+D — 首行走 emptyOrderItem() (单位='份' + 仓库记忆 + 全字段一致).
    items: [emptyOrderItem()] as OrderItem[],
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

/** 开始采购 — 从 SO 一键弹窗带入 PO 明细 (t2b 行1867-1902). */
function handleStartPurchase(row: TableRow) {
  startPurchaseSoId.value = row.id;
  startPurchaseSoNumber.value = row.orderNumber ?? '';
  startPurchaseCustomer.value = row.customerName ?? row.customer?.name ?? '';
  startPurchaseVisible.value = true;
}

/**
 * 合并采购 — 把勾选的多张 SO 合并成一张采购单 (转录行3650 "加号逐个追加合并").
 * 跨 SO 按物料聚合需求, 库存统一扣一次. 防呆: 至少选 1 张; 单张时等价于开始采购但走合并弹窗.
 */
function handleMergePurchase() {
  if (selectedRows.value.length === 0) {
    ElMessage.warning('请先勾选至少一张销售订单');
    return;
  }
  mergePurchaseSos.value = selectedRows.value.map((r) => ({
    id: r.id,
    number: r.orderNumber ?? '',
    customer: r.customerName ?? r.customer?.name ?? '',
  }));
  mergePurchaseVisible.value = true;
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

      <!--
        T131 Part 3 — 多选批量操作栏 (仅 table 视图; 本列表有 table/kanban/grid/timeline/calendar 多模式,
        多选只在 table 模式生效). 每按钮按选中行资格 disable; batchLoading 期间全 disable.
        批量执行走前端 Promise.allSettled 循环. TODO(v2): 大批量 (>10) 改后端批量端点.
      -->
      <div v-if="viewMode === 'table' && selectedRows.length > 0" class="bulk-bar">
        <span class="bulk-bar-count">已选 <strong>{{ selectedRows.length }}</strong> 条</span>
        <el-button
          type="primary" size="small"
          :disabled="!hasBatchSubmittable || batchLoading"
          :loading="batchLoading"
          @click="handleBatchSubmitForReview"
        >批量提交/判定审批</el-button>
        <el-button
          type="success" size="small"
          :disabled="!hasBatchConfirmable || batchLoading"
          :loading="batchLoading"
          @click="handleBatchConfirm"
        >批量确认</el-button>
        <el-button
          type="warning" size="small"
          :disabled="!hasBatchCancellable || batchLoading"
          :loading="batchLoading"
          @click="handleBatchCancel"
        >批量取消</el-button>
        <el-button
          type="danger" size="small"
          :disabled="!hasBatchDeletable || batchLoading"
          :loading="batchLoading"
          @click="handleBatchDelete"
        >批量删除</el-button>
        <!-- 转录行3650 — 多 SO 合并采购 (勾选多张 → 合并成一张采购单, 跨 SO 按物料聚合). -->
        <el-button
          type="primary" size="small" plain
          :disabled="batchLoading"
          @click="handleMergePurchase"
        >合并采购 ({{ selectedRows.length }})</el-button>
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
      <el-table
        v-else
        :data="filteredTableData"
        v-loading="loading"
        empty-text="暂无数据"
        stripe
        border
        style="width: 100%"
        @selection-change="handleSelectionChange"
      >
        <!-- T131 Part 3 — 多选列 (Element Plus 在 COLUMN 上用 :selectable, 非 table 的 rowSelectable). -->
        <el-table-column type="selection" width="48" :selectable="canSelectRow" />
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
        <!-- U-SP5: 开票状态列 (NOT @PriceSensitive — 所有角色可见) -->
        <el-table-column prop="invoiceStatus" label="开票状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag
              v-if="row.invoiceStatus"
              :type="row.invoiceStatus === 'FULLY_INVOICED' ? 'success' : row.invoiceStatus === 'PARTIAL_INVOICED' ? 'warning' : 'info'"
              size="small"
            >
              {{ row.invoiceStatus === 'FULLY_INVOICED' ? '已开票' : row.invoiceStatus === 'PARTIAL_INVOICED' ? '部分开票' : '未开票' }}
            </el-tag>
            <span v-else class="price-masked">—</span>
          </template>
        </el-table-column>
        <!-- U-SP5: 提成预览 (@PriceSensitive — null→"—" when canViewPrice=false) -->
        <el-table-column
          v-if="canViewPrice"
          prop="commissionPreview"
          label="提成(预估)"
          width="110"
          align="right"
        >
          <template #default="{ row }">
            <span v-if="row.commissionPreview != null">{{ formatAmount(row.commissionPreview) }}</span>
            <span v-else class="price-masked">—</span>
          </template>
        </el-table-column>
        <el-table-column
          v-if="canViewPrice"
          prop="commissionRatePct"
          label="提成率"
          width="90"
          align="right"
        >
          <template #default="{ row }">
            <span v-if="row.commissionRatePct != null">{{ Number(row.commissionRatePct).toFixed(2) }}%</span>
            <span v-else class="price-masked">—</span>
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
        <el-table-column label="操作" width="480" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="goDetail(row.id)">详情</el-button>
            <el-button v-if="row.status === 'DRAFT' && canWrite" type="warning" link size="small" @click="handleEdit(row)">编辑</el-button>
            <!-- T131/N9 — 提交后按审批阈值自动分流. DRAFT 链式 (先确认再判定); CONFIRMED 单次. per-row loading. -->
            <el-button
              v-if="['DRAFT','CONFIRMED'].includes(row.status) && canWrite"
              type="primary"
              link
              size="small"
              :loading="submittingIds.has(row.id)"
              :disabled="submittingIds.has(row.id)"
              @click="handleSubmitForReviewRow(row)"
            >提交/判定审批</el-button>
            <!-- T131 — 「确认」保留为 DRAFT 的次要操作 (仅 DRAFT→CONFIRMED, 不送财务). -->
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
            <!-- 开始采购 — 从 SO 一键带入 PO 明细 (t2b 行1867-1902, Friday 采购负责人) -->
            <el-button
              v-if="['CONFIRMED', 'FINANCE_APPROVED', 'PROCESSING'].includes(row.status) && canWrite"
              type="warning"
              link
              size="small"
              @click="handleStartPurchase(row)"
            >开始采购</el-button>
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
        <el-form-item :label="label('customer')" required>
          <!-- T130 Feature B — 选客户后智能预填业务员 (归属业务员 → 当前用户). -->
          <el-select v-model="form.customerId" placeholder="请选择" filterable style="width: 100%" @change="onCustomerSelect">
            <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="交货日期"><el-date-picker v-model="form.requiredDeliveryDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="交货地址"><el-input v-model="form.deliveryAddress" /></el-form-item>
        <el-form-item label="业务员">
          <!-- T130 Feature B — 用户手动改业务员 → touched=true, 不再被客户切换自动覆盖. -->
          <el-select v-model="form.salesperson" placeholder="请选择业务员" filterable allow-create style="width: 100%" @change="salespersonTouched = true">
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
          <span style="width: 130px">单价 (未税)</span>
          <!-- E-FP-1 Rule2: 含税单价/含税小计派生只读，用户填未税+税率，此列自动显示 -->
          <span style="width: 130px">含税单价</span>
          <span style="width: 130px">含税小计</span>
          <span style="width: 130px">箱数</span>
          <span style="width: 90px" title="税率 (开票 G1 按此分组): 9=原料, 13=加工, 6=服务">税率(%)</span>
          <!-- T4-D1 (issue #525): 来源仓库 — F006 客户反馈 "成品会调回总仓, 总仓再安排发货".
               Customer wants to record per-line source warehouse (WH-LOG 总仓 / WH-WKS 线边仓). -->
          <span style="width: 110px">来源仓库</span>
          <span style="width: 40px">操作</span>
        </div>
        <div v-for="(item, idx) in form.items" :key="idx" class="item-row">
          <el-select v-model="item.productTypeId" placeholder="选择产品" filterable clearable style="width: 200px" @change="(v: string) => onProductSelect(item, v)">
            <el-option v-for="p in products" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
          <!-- T130 Feature D — 规格只读: 取产品字典 specification/packageSpec, 抄码品由 onProductSelect 识别. -->
          <el-input :model-value="specDisplay(item)" placeholder="规格" style="width: 120px" disabled />
          <!-- T130 Feature D6 — 下单数量纳入 Tab 链; Tab/Shift+Tab 跨行跳, 末行 Tab → 创建按钮. -->
          <el-input-number
            v-model="item.quantity"
            :min="0"
            :ref="(el: any) => { quantityRefs[idx] = el; }"
            style="width: 130px"
            @change="() => calcBox(item)"
            @keydown.tab="(e: KeyboardEvent) => handleQuantityTab(e, idx)"
          />
          <!-- T130 Feature D — 单位下拉: 份 + (产品配箱规才给 箱). NO allow-create. 默认 份. -->
          <el-select v-model="item.unit" filterable style="width: 80px" @change="() => calcBox(item)">
            <el-option v-for="u in unitOptions(item)" :key="u" :label="u" :value="u" />
          </el-select>
          <!-- Sprint 4 W2 S-PRICE-1 R1: unitPrice + 上次成交价 hint chip (一键采纳) -->
          <!-- Issue #793: 客户协议价 hint (CUSTOMER 自动覆盖, GLOBAL 仅提示) -->
          <div class="unit-price-wrap">
            <!-- U-SP5: @blur 触发毛利红线 check (advisory, 不卡死提交) -->
            <el-input-number v-model="item.unitPrice" :min="0" :precision="2" style="width: 130px" @blur="() => checkMarginOnBlur(item)" />
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
          <!-- E-FP-1 Rule2: 含税单价 = 未税单价 × (1 + 税率/100), 只读派生，不提交后端 -->
          <el-tooltip content="含税单价 = 未税单价 × (1 + 税率/100)，自动计算，不可编辑" placement="top">
            <el-input
              :model-value="item.unitPrice != null && item.taxRate != null
                ? (item.unitPrice * (1 + (item.taxRate as number) / 100)).toFixed(2)
                : '-'"
              disabled
              style="width: 130px"
            />
          </el-tooltip>
          <!-- E-FP-1 Rule2: 含税小计 = 含税单价 × 数量，只读派生，不提交后端 -->
          <el-tooltip content="含税小计 = 含税单价 × 数量，自动计算，不可编辑" placement="top">
            <el-input
              :model-value="item.unitPrice != null && item.taxRate != null && item.quantity != null
                ? (item.unitPrice * (1 + (item.taxRate as number) / 100) * (item.quantity as number)).toFixed(2)
                : '-'"
              disabled
              style="width: 130px"
            />
          </el-tooltip>
          <!-- P1-3 R2 fix: el-tag 替换 inline-styled div, 跟随 Element Plus 主题 -->
          <!-- T130 Feature D — 箱数只读 (由数量×箱规自动计算, 非第二销售单位); 抄码品/未配置箱规分别提示. -->
          <el-tag v-if="isAbacaItem(item)" type="warning" effect="light" size="default" style="width: 130px; text-align: center;">抄码品</el-tag>
          <!-- T136: 未配置箱规 → 权限门控跳转 (fool-proof Rule 5). 有 system 权限显示「去配置」按钮，无权限仅显文案 -->
          <div v-else-if="isBoxUnconfigured(item)" style="width: 130px; line-height: 1.4;">
            <el-text type="warning" size="small">未配置箱规</el-text>
            <el-button
              v-if="canAccessProducts"
              link
              type="primary"
              size="small"
              style="padding: 0; font-size: 12px;"
              @click.stop="goConfigureProduct(item.productTypeId as string | undefined)"
            >去配置</el-button>
          </div>
          <el-tooltip v-else content="由数量与箱规自动计算，非第二销售单位" placement="top">
            <el-input-number v-model="item.boxQuantity" :min="0" :precision="2" disabled style="width: 130px" placeholder="箱" />
          </el-tooltip>
          <el-select v-model="item.taxRate" placeholder="税率" style="width: 90px" size="default">
            <el-option :value="0" label="0% 免税" />
            <el-option :value="3" label="3% 小规模" />
            <el-option :value="6" label="6% 服务" />
            <el-option :value="9" label="9% 原料" />
            <el-option :value="13" label="13% 加工" />
          </el-select>
          <!-- T4-D1 (issue #525): 来源仓库 select.
               2026-07-02 fix: options come from the real warehouse list (DB name
               authoritative) instead of hardcoded 总仓/线边仓 — falls back to the
               legacy 2 options only if the list fails to load. -->
          <!-- T130 Feature C — 用户选仓库即记忆 (按操作员), 下次新行预填. -->
          <el-select v-model="item.sourceWarehouseCode" placeholder="选择" clearable style="width: 110px" size="default" @change="(v: string) => rememberWarehouse(v)">
            <el-option
              v-for="opt in warehouseSelectOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <el-button type="danger" link @click="removeItem(idx)" :disabled="form.items.length <= 1">删除</el-button>
        </div>
        <el-button style="width: 100%; margin-top: 8px" @click="addItem">+ 添加行</el-button>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <!-- T130 Feature D6 — 末行下单数量 Tab 跳到此按钮. -->
        <el-button ref="submitButtonRef" type="primary" @click="handleSave">{{ editingOrderId ? '保存' : '创建' }}</el-button>
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

    <!-- 开始采购弹窗 — 从 SO BOM 展开一键预填 PO (t2b 行1867-1902 Friday) -->
    <StartPurchaseDialog
      v-model="startPurchaseVisible"
      :factory-id="factoryId"
      :sales-order-id="startPurchaseSoId"
      :sales-order-number="startPurchaseSoNumber"
      :customer-name="startPurchaseCustomer"
      @created="(poId: string) => { ElMessage.success(`采购单已创建 — 可在采购管理查看 (ID: ${poId})`); loadData(); }"
    />

    <!-- 合并采购弹窗 — 多张 SO 合并成一张采购单 (转录行3650 "加号逐个追加合并") -->
    <MergePurchaseDialog
      v-model="mergePurchaseVisible"
      :factory-id="factoryId"
      :sales-orders="mergePurchaseSos"
      @created="(poId: string) => { ElMessage.success(`合并采购单已创建 — 可在采购管理查看 (ID: ${poId})`); loadData(); }"
    />

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

    <!-- #1290 follow-up — 退货 dialog (fixes dead "退货" 更多-menu item for
         COMPLETED/PARTIAL_DELIVERED/SHIPPED rows; wires existing backend
         ReturnOrderController, same component as procurement/orders/list.vue
         + sales/shipments/list.vue). -->
    <CreateReturnOrderDialog
      v-if="returnDialogRow"
      v-model="returnDialogVisible"
      :factory-id="factoryId || ''"
      return-type="SALES_RETURN"
      :source-order-id="String(returnDialogRow.id)"
      :source-order-number="String(returnDialogRow.orderNumber || returnDialogRow.id)"
      :counterparty-id="String(returnDialogRow.customerId || '')"
      :counterparty-name="String(returnDialogRow.customerName || returnDialogRow.customer?.name || returnDialogRow.customerId || '-')"
      :items="returnDialogItems"
      @success="handleReturnSuccess"
    />

    <!-- E-FP-2 取消原因采集 dialog (fool-proof Rule 3): 标准原因 dropdown + "其他"展 textarea -->
    <el-dialog
      v-model="cancelDialogVisible"
      :title="pendingCancelIds.length > 1 ? `批量取消 ${pendingCancelIds.length} 个销售订单` : '取消销售订单'"
      width="460px"
      destroy-on-close
    >
      <el-form label-width="90px">
        <el-form-item label="取消原因" required>
          <el-select v-model="cancelReasonCode" placeholder="请选择取消原因" style="width:100%">
            <el-option v-for="r in CANCEL_REASONS" :key="r" :label="r" :value="r" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="cancelReasonCode === '其他'" label="补充说明" required>
          <el-input
            v-model="cancelReasonOther"
            type="textarea"
            :rows="2"
            placeholder="请填写具体取消原因"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cancelDialogVisible = false">返回</el-button>
        <el-button
          type="danger"
          :loading="cancelSubmitting"
          :disabled="!cancelReasonCode || (cancelReasonCode === '其他' && !cancelReasonOther.trim())"
          @click="submitCancel"
        >确认取消</el-button>
      </template>
    </el-dialog>
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
/* T131 Part 3 — 多选批量操作栏 */
.bulk-bar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  margin-bottom: 12px; padding: 8px 12px; background: #ecf5ff; border: 1px solid #d9ecff; border-radius: 4px;
  .bulk-bar-count { font-size: 13px; color: #606266; margin-right: 4px; strong { color: #409eff; } }
}
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
