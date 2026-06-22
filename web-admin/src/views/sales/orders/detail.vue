<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useBusinessMode } from '@/composables/useBusinessMode';
import { get, post, put } from '@/api/request';
import OrderItemsEditor, { type OrderItemRow } from './components/OrderItemsEditor.vue';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowLeft } from '@element-plus/icons-vue';
import { formatAmount } from '@/utils/tableFormatters';
import { handleCatchError } from '@/utils/errorToast';
// T4-D1 (issue #525): F006 customer asked for 来源仓库 (总仓/线边仓) per line item.
import { warehouseDisplayLabel } from '@/utils/warehouse';
import type { TableRow } from '@/types/api';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const { label } = useBusinessMode();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('sales'));
const canViewPrice = computed(() => permissionStore.canViewPrice);
// Issue #740 (六扇门 May10): 仓库角色才能 confirm 发货 (扣库存). 销售只看不动.
const canWarehouseConfirm = computed(() => permissionStore.canWrite('warehouse'));
const orderId = computed(() => route.params.id as string);

const loading = ref(false);
const submitting = ref(false);
const notFound = ref(false);
const order = ref<TableRow | null>(null);
const deliveries = ref<TableRow[]>([]);
const invoices = ref<TableRow[]>([]);
const payments = ref<TableRow[]>([]);
const purchaseOrders = ref<TableRow[]>([]);
const activeTab = ref('detail');

const deliveryDialogVisible = ref(false);
const deliveryForm = ref<{ deliveryAddress: string; logisticsCompany: string; items: TableRow[] }>({
  deliveryAddress: '', logisticsCompany: '', items: [],
});

// T-RTA (issue #531): F006 客户反馈 第四次会议 956-1037 — 申请退货 dialog.
// Builds CreateReturnOrderRequest with returnType=SALES_RETURN + counterpartyId=customerId
// + sourceOrderId=orderId. Items default from order.items with checked=true, qty=delivered.
const returnDialogVisible = ref(false);
const returnSubmitting = ref(false);
type ReturnDialogItem = {
  productTypeId: string;
  itemName: string;
  unitPrice?: number;
  maxQuantity: number; // delivered (cap for refund)
  selected: boolean;
  quantity: number;
  reason: string;
};
// T-RTA business logic (issue #571): withGoods toggle drives backend branching.
//   true  = 有食物退货 → 库存入库总仓 + 不良品 (Phase C TODO) → AR冲减 at completion
//   false = 无食物退货 → 直接退款, 无库存动作 → AR冲减 immediate on approve
const returnForm = ref<{ returnDate: string; reason: string; remark: string; withGoods: boolean; items: ReturnDialogItem[] }>({
  returnDate: new Date().toISOString().slice(0, 10),
  reason: '',
  remark: '',
  withGoods: true, // default 实物退货 (customer's primary case per 第四次:956-1037)
  items: [],
});

// 批次分配对话框 (P0-13 强制批次追溯 — R16 深度测试后补完)
const batchAllocDialogVisible = ref(false);
const batchAllocLoading = ref(false);
type AllocRow = { finishedGoodsBatchId: string; batchNumber: string; productionDate: string; availableQuantity: number; allocatedQty: number };
type AllocItem = { deliveryItemId: string; productName: string; deliveredQuantity: number; allocations: AllocRow[] };
const batchAllocForm = ref<{ deliveryId: string; deliveryNumber: string; items: AllocItem[] }>({
  deliveryId: '', deliveryNumber: '', items: [],
});

// 开票申请对话框
const invoiceDialogVisible = ref(false);
const invoiceForm = ref<{ invoiceType: string; remark: string }>({ invoiceType: 'NORMAL', remark: '' });

// 上传发票 PDF 对话框 (V3 P0-3c)
const issueDialogVisible = ref(false);
const issueTargetInvoiceId = ref<string>('');
const issuePdfFile = ref<File | null>(null);

// 收款登记对话框
const paymentDialogVisible = ref(false);
const paymentForm = ref<{ amount: number; paymentMethod: string; paymentDate: string; paymentReference: string; remark: string; receiptUrl: string }>({
  amount: 0, paymentMethod: 'BANK_TRANSFER', paymentDate: '', paymentReference: '', remark: '', receiptUrl: '',
});
const receiptFile = ref<File | null>(null);
const receiptUploading = ref(false);

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

function orderAmountHint(row: TableRow | null): string {
  const amount = Number(row?.totalAmount || 0);
  return formatAmount(Number.isFinite(amount) ? amount : 0);
}

function isExternalChannelOrder(row: TableRow | null): boolean {
  return Boolean(String(row?.externalOrderTitle || '').trim());
}

function approvalDecisionHint(row: TableRow | null): string {
  const amount = Number(row?.totalAmount || 0);
  const safeAmount = Number.isFinite(amount) ? amount : 0;
  if (isExternalChannelOrder(row)) {
    return `检测到外部渠道订单；是否免审由后台审批配置决定。订单金额：${formatAmount(safeAmount)}。`;
  }
  if (safeAmount > 5000) {
    return `订单金额 ${formatAmount(safeAmount)} 超过默认阈值，预计进入财务审核。`;
  }
  return `订单金额 ${formatAmount(safeAmount)} 未超过默认阈值，预计免审通过。`;
}

function actionSuccessMessage(action: string, labelText: string, response: unknown): string {
  const status = responseOrderStatus(response);
  if ((action === 'confirm' || action === 'submit-for-review') && status === 'FINANCE_APPROVED') {
    return `${labelText}成功，未触发审批阈值，已免审通过`;
  }
  if ((action === 'confirm' || action === 'submit-for-review') && status === 'PENDING_FINANCE_REVIEW') {
    return `${labelText}成功，已进入财务审核`;
  }
  return `${labelText}成功`;
}

function actionConfirmMessage(action: string, labelText: string): string {
  const orderNumber = String(order.value?.orderNumber || orderId.value);
  if (action === 'confirm') {
    return `确认销售订单 ${orderNumber} 并自动判定审批？\n\n${approvalDecisionHint(order.value)}\n系统会先确认订单，再按后台审批配置自动分流：超过阈值进入财务审核，未触发阈值或满足免审配置的订单自动通过。`;
  }
  if (action === 'submit-for-review') {
    return `确认将销售订单 ${orderNumber} 提交审批判定？\n\n${approvalDecisionHint(order.value)}\n系统会按后台审批配置自动分流，最终结果以后端返回为准。`;
  }
  return `确认${labelText}销售订单 ${orderNumber}？订单金额：${orderAmountHint(order.value)}`;
}

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

const delStatusMap: Record<string, { text: string; type: string }> = {
  DRAFT: { text: '草稿', type: 'info' },
  // Issue #740: 销售创建后等仓库 confirm 的中间态
  PENDING_WAREHOUSE_CONFIRM: { text: '待仓库确认', type: 'warning' },
  PICKED: { text: '已拣货', type: '' },
  SHIPPED: { text: '已发货', type: 'warning' },
  DELIVERED: { text: '已签收', type: 'success' },
  RETURNED: { text: '已退回', type: 'danger' },
};

const invoiceStatusMap: Record<string, { text: string; type: string }> = {
  REQUESTED: { text: '待审核', type: 'warning' },
  APPROVED: { text: '已审核', type: '' },
  REJECTED: { text: '已驳回', type: 'danger' },
  ISSUED: { text: '已开票', type: 'success' },
  CANCELLED: { text: '已取消', type: 'info' },
};

const paymentStatusMap: Record<string, { text: string; type: string }> = {
  PENDING: { text: '待确认', type: 'warning' },
  VERIFIED: { text: '已确认', type: 'success' },
  REJECTED: { text: '已驳回', type: 'danger' },
};

// V3 P0-9 / v1 §2.4.4 — 订单级状态 (区别于 record-level paymentStatusMap/invoiceStatusMap)
const orderPaymentStatusMap: Record<string, { text: string; type: string }> = {
  UNPAID: { text: '待收款', type: 'info' },
  PARTIAL: { text: '部分收款', type: 'warning' },
  PAID: { text: '已收款', type: 'success' },
};
const orderInvoiceStatusMap: Record<string, { text: string; type: string }> = {
  NOT_INVOICED: { text: '待开票', type: 'info' },
  PARTIAL_INVOICED: { text: '部分开票', type: 'warning' },
  FULLY_INVOICED: { text: '已开票', type: 'success' },
};
const orderTransportStatusMap: Record<string, { text: string; type: string }> = {
  PLANNING: { text: '待出厂', type: 'info' },
  IN_PRODUCTION: { text: '生产中', type: 'warning' },
  IN_TRANSIT: { text: '运输中', type: 'warning' },
  DELIVERED: { text: '已发货', type: 'success' },
};

const orderFormulas = ref<TableRow>({});

// ─── Task 4: DRAFT 编辑产品行 ───
const { goCreate } = useCreateAndReturn();

const editItemsVisible = ref(false);
const editItemsRows = ref<OrderItemRow[]>([]);
const editItemsSaving = ref(false);
const products = ref<Array<{ id: string; name: string; unit?: string }>>([]);

const isDraft = computed(() => String((order.value as any)?.status || '').toUpperCase() === 'DRAFT');

async function loadProductsForEdit() {
  if (!factoryId.value) return;
  try {
    // Mirror list.vue loadProducts — same endpoint / response shape.
    const res = await get(`/${factoryId.value}/product-types/active`, { _silent: true } as never);
    const list = Array.isArray(res?.data) ? res.data : (res?.data?.content ?? []) as any[];
    products.value = list.map((p: any) => ({
      id: String(p.id),
      name: String(p.name ?? p.productName ?? ''),
      unit: p.unit,
    }));
  } catch { products.value = []; }
}

function openEditItems() {
  const existing = Array.isArray((order.value as any)?.items) ? (order.value as any).items : [];
  editItemsRows.value = existing.length
    ? existing.map((it: any) => ({
        productTypeId: String(it.productTypeId ?? ''),
        productName: it.productName,
        quantity: Number(it.quantity ?? 0),
        unit: it.unit ?? '份',
        unitPrice: Number(it.unitPrice ?? 0),
        taxRate: Number(it.taxRate ?? 13),
        specification: it.specification ?? '',
      }))
    : [{ productTypeId: '', quantity: 0, unit: '份', unitPrice: 0, taxRate: 13 }];
  editItemsVisible.value = true;
}

function goCreateProductType() {
  goCreate('/system/products');
}

async function saveEditItems() {
  if (!factoryId.value || !orderId.value) return;
  const items = editItemsRows.value.filter((r) => r.productTypeId);
  if (items.length === 0) { ElMessage.warning('请至少添加一个产品行'); return; }
  editItemsSaving.value = true;
  try {
    const res = await put(`/${factoryId.value}/sales/orders/${orderId.value}`, { items });
    if ((res as any)?.success !== false) {
      ElMessage.success('产品行已保存');
      editItemsVisible.value = false;
      await loadOrder();
    }
  } finally { editItemsSaving.value = false; }
}

onMounted(async () => {
  await loadOrder();
  loadDeliveries();
  loadInvoices();
  loadPayments();
  loadPurchaseOrders();
  loadFormulas();
  await loadProductsForEdit();
  if (route.query.editItems === '1' && isDraft.value) openEditItems();
});

async function loadOrder() {
  if (!factoryId.value || !orderId.value) return;
  loading.value = true;
  notFound.value = false;
  try {
    const res = await get(`/${factoryId.value}/sales/orders/${orderId.value}`);
    if (res.success && res.data) {
      order.value = res.data;
    } else {
      notFound.value = true;
      order.value = null;
    }
  } catch (e: unknown) {
    // R18-ext #283 fix: any load failure on SO detail means the order is
    // unreachable for this user — most likely 404/not-found, possibly 403.
    // Either way, render empty state rather than a blank page. Interceptor
    // already showed the server's message; don't stack a duplicate toast.
    notFound.value = true;
    order.value = null;
  }
  finally { loading.value = false; }
}

async function loadFormulas() {
  if (!factoryId.value || !orderId.value) return;
  try {
    const res = await get(`/${factoryId.value}/sales/orders/${orderId.value}/formulas`);
    if (res.success && res.data) orderFormulas.value = res.data;
  } catch { /* formula module may not be configured for this factory */ }
}

const taxGroupData = computed(() => {
  const raw = orderFormulas.value?.tax_group_sum;
  if (!Array.isArray(raw) || raw.length === 0) return null;
  return raw.map((row: TableRow) => ({
    taxRate: row.tax_rate != null ? Number(row.tax_rate) : 0,
    amount: row.agg_value != null ? Number(row.agg_value) : 0,
  })).sort((a: { taxRate: number }, b: { taxRate: number }) => a.taxRate - b.taxRate);
});

function taxIncludedUnitPrice(row: TableRow): number | null {
  if (row.unitPrice == null) return null;
  const unitPrice = Number(row.unitPrice);
  const taxRate = Number(row.taxRate || 0);
  return unitPrice * (1 + taxRate / 100);
}

function formatTaxIncludedUnitPrice(row: TableRow): string {
  const amount = taxIncludedUnitPrice(row);
  return amount == null ? '—' : formatAmount(amount);
}

async function loadDeliveries() {
  if (!factoryId.value || !orderId.value) return;
  try {
    const res = await get(`/${factoryId.value}/sales/deliveries/by-order/${orderId.value}`);
    if (res.success) deliveries.value = Array.isArray(res.data) ? res.data : [];
  } catch { /* ignore */ }
}

async function loadInvoices() {
  if (!factoryId.value || !orderId.value) return;
  try {
    const res = await get(`/${factoryId.value}/finance/invoices/by-sales-order/${orderId.value}`);
    if (res.success) invoices.value = Array.isArray(res.data) ? res.data : [];
  } catch { /* ignore — invoice module may not be initialised */ }
}

async function loadPayments() {
  if (!factoryId.value || !orderId.value) return;
  try {
    const res = await get(`/${factoryId.value}/finance/payments/by-sales-order/${orderId.value}`);
    if (res.success) payments.value = Array.isArray(res.data) ? res.data : [];
  } catch { /* ignore */ }
}

async function loadPurchaseOrders() {
  if (!factoryId.value || !orderId.value) return;
  try {
    // 关联采购订单 — 通过销售订单号查询
    const res = await get(`/${factoryId.value}/purchase/orders`, {
      params: { salesOrderId: orderId.value, page: 1, size: 50 },
    });
    if (res.success && res.data) {
      const list = Array.isArray(res.data) ? res.data : (res.data.content || res.data.records || []);
      purchaseOrders.value = list.filter((po: TableRow) => po.salesOrderId === orderId.value);
    }
  } catch { /* ignore — 后端可能尚未实现按销售订单查询采购单 */ }
}

async function handleAction(action: string) {
  if (submitting.value) return;
  const map: Record<string, { label: string; url: string }> = {
    confirm: { label: '确认并判定审批', url: `/${factoryId.value}/sales/orders/${orderId.value}/confirm` },
    cancel: { label: '取消订单', url: `/${factoryId.value}/sales/orders/${orderId.value}/cancel` },
    'submit-for-review': { label: '提交审批判定', url: `/${factoryId.value}/sales/orders/${orderId.value}/submit-for-review` },
  };
  const a = map[action];
  if (!a) return;
  try {
    await ElMessageBox.confirm(actionConfirmMessage(action, a.label), '操作确认', {
      confirmButtonText: action === 'cancel' ? '确认取消' : '提交并判定',
      cancelButtonText: '返回',
      type: action === 'cancel' ? 'warning' : 'info',
    });
  } catch { return; }
  submitting.value = true;
  try {
    const res = await post(a.url);
    if (res.success) { ElMessage.success(actionSuccessMessage(action, a.label, res)); loadOrder(); }
    else { ElMessage.error(res.message || `${a.label}失败，请重试`); }
  } catch (e) { handleCatchError(e, `${a.label}失败，请检查网络`); }
  finally { submitting.value = false; }
}

// Apr 18 2026 bug #51: 用户报告"开始生产"按钮报错"请求资源不存在"其实按钮前端根本没实现。
// SO 财务审核通过后的下一步是创建生产计划(SO → ProductionPlan 关联), 此按钮
// 跳到生产计划页并带 salesOrderId 提示, 用户在那边新建 plan。不直接改 SO 状态(那
// 是 plan 开始生产后的联动), 只承担"导航 + 提示"职责, 避免前端伪装后端未实现的能力。
async function handleStartProduction() {
  if (!order.value) return;
  ElMessage.info(`请为订单 ${order.value.orderNumber || orderId.value} 创建生产计划`);
  await router.push({
    path: '/production/plans',
    query: { salesOrderId: String(orderId.value), action: 'create' },
  });
}

// 六扇门 V1 §2.2 (audit fix 2026-04-26 #6): finance review dialog with cost breakdown
// Pre-fix: only ElMessageBox.prompt with notes — no place to record cost.
// Post-fix: rich dialog showing 订单总额 / 预估成本 (input) / 预估利润 (auto-calc) / 备注.
const financeReviewVisible = ref(false);
const financeReviewForm = ref<{
  notes: string;
  estimatedCost: number | null;
  isApprove: boolean;
}>({ notes: '', estimatedCost: null, isApprove: true });
const financeReviewProfit = computed(() => {
  const total = Number(order.value?.totalAmount || 0);
  const cost = financeReviewForm.value.estimatedCost;
  if (cost == null) return null;
  return total - Number(cost);
});

// P2-3 R2 fix: 字段隐藏期间, 始终设 null 避免无意中持久化历史值.
// 旧逻辑预填上次拒批的 estimatedCost → 此 PR 隐藏 input 后用户看不到也清不了 →
// 重审通过时静默把旧值再次提交 → 违反"禁止降级处理/假数据"原则.
// 重启用此字段时改回 `order.value?.estimatedCost ? Number(...) : null`.
const ESTIMATED_COST_ENABLED = false;

function openFinanceReview(action: 'approve' | 'reject') {
  const isApprove = action === 'approve';
  financeReviewForm.value = {
    notes: '',
    estimatedCost: ESTIMATED_COST_ENABLED && order.value?.estimatedCost
      ? Number(order.value.estimatedCost)
      : null,
    isApprove,
  };
  financeReviewVisible.value = true;
}

async function submitFinanceReview() {
  if (submitting.value) return;
  const { isApprove, notes, estimatedCost } = financeReviewForm.value;
  const labelText = isApprove ? '审核通过' : '审核驳回';
  if (!isApprove && !notes?.trim()) {
    return ElMessage.warning('请填写驳回原因');
  }
  submitting.value = true;
  try {
    const url = `/${factoryId.value}/sales/orders/${orderId.value}/${isApprove ? 'finance-approve' : 'finance-reject'}`;
    const body: TableRow = { notes: notes || '' };
    // P2-3 R2 fix: 双重防御 — 即使 form 状态被脏化 (e.g. 直接 devtools 改),
    // ESTIMATED_COST_ENABLED=false 时也不发送, 防止静默降级.
    if (ESTIMATED_COST_ENABLED && isApprove && estimatedCost != null) body.estimatedCost = estimatedCost;
    const res = await post(url, body);
    if (res.success) {
      ElMessage.success(`${labelText}成功`);
      financeReviewVisible.value = false;
      loadOrder();
    } else { ElMessage.error(res.message || `${labelText}失败`); }
  } catch (e) {
    handleCatchError(e, `${labelText}失败,请检查网络`);
  } finally {
    submitting.value = false;
  }
}

// Backward-compat for any external caller (no longer used in template)
async function handleFinanceAction(action: 'approve' | 'reject') {
  openFinanceReview(action);
}

function openDeliveryDialog() {
  if (!order.value?.items?.length) return;
  deliveryForm.value = {
    deliveryAddress: order.value.deliveryAddress || '',
    logisticsCompany: '',
    items: (order.value.items as TableRow[]).map((it) => ({
      productTypeId: it.productTypeId,
      productName: it.productName,
      deliveredQuantity: it.quantity - (it.deliveredQuantity || 0),
      unit: it.unit,
      unitPrice: it.unitPrice,
      // T4-D5 (issue #553): propagate per-line source warehouse code from the
      // sales order item. Backend SalesServiceImpl.createDeliveryRecord stores
      // it on SalesDeliveryItem; allocation logic doesn't filter yet but the
      // hint is preserved for reporting + future intelligent picking.
      sourceWarehouseCode: it.sourceWarehouseCode || '',
    })),
  };
  deliveryDialogVisible.value = true;
}

// T-RTA (issue #531): F006 客户反馈 — open return-order dialog from sales order detail.
function openReturnDialog() {
  if (!order.value?.items?.length) {
    ElMessage.warning('订单无明细, 无法发起退货');
    return;
  }
  returnForm.value = {
    returnDate: new Date().toISOString().slice(0, 10),
    reason: '',
    remark: '',
    withGoods: true, // default 实物退货
    items: (order.value.items as TableRow[]).map((it) => {
      const delivered = Number(it.deliveredQuantity) || 0;
      return {
        productTypeId: String(it.productTypeId || ''),
        itemName: String(it.productName || it.productTypeName || '-'),
        unitPrice: Number(it.unitPrice) || 0,
        maxQuantity: delivered, // can't refund more than delivered
        selected: delivered > 0,
        quantity: delivered,
        reason: '',
      };
    }),
  };
  returnDialogVisible.value = true;
}

async function handleCreateReturn() {
  const selectedItems = returnForm.value.items.filter((i) => i.selected && i.quantity > 0);
  if (selectedItems.length === 0) {
    ElMessage.warning('请至少选择一行并填写退货数量');
    return;
  }
  if (!returnForm.value.reason.trim()) {
    ElMessage.warning('请填写退货原因');
    return;
  }
  // Quantity caps: each row's quantity should not exceed maxQuantity (delivered).
  for (const it of selectedItems) {
    if (it.maxQuantity > 0 && it.quantity > it.maxQuantity) {
      ElMessage.warning(`${it.itemName} 退货数量超过已发货数量 (${it.maxQuantity})`);
      return;
    }
  }
  returnSubmitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/return-orders`, {
      returnType: 'SALES_RETURN',
      counterpartyId: order.value?.customerId || '',
      sourceOrderId: orderId.value,
      returnDate: returnForm.value.returnDate,
      reason: returnForm.value.reason,
      remark: returnForm.value.remark,
      // T-RTA business logic (#571): explicit branch — backend uses this to defer
      // AR/AP冲减 to completion path for 有食物 (waits for physical receipt).
      withGoods: returnForm.value.withGoods,
      items: selectedItems.map((it) => ({
        productTypeId: it.productTypeId,
        itemName: it.itemName,
        quantity: it.quantity,
        unitPrice: it.unitPrice,
        reason: it.reason,
      })),
    });
    if (res.success) {
      ElMessage.success('退货单已创建 (DRAFT 草稿). 在销售退货页继续提交审批.');
      returnDialogVisible.value = false;
    }
  } catch { /* interceptor */ }
  finally { returnSubmitting.value = false; }
}

async function handleCreateDelivery() {
  if (submitting.value) return;
  const filteredItems = deliveryForm.value.items.filter(i => i.deliveredQuantity > 0);
  if (filteredItems.length === 0) return ElMessage.warning('请至少填写一个发货数量');
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/sales/deliveries`, {
      salesOrderId: orderId.value,
      customerId: order.value?.customerId || '',
      deliveryDate: new Date().toISOString().slice(0, 10),
      deliveryAddress: deliveryForm.value.deliveryAddress,
      logisticsCompany: deliveryForm.value.logisticsCompany,
      items: filteredItems,
    });
    if (res.success) {
      ElMessage.success('发货单创建成功');
      deliveryDialogVisible.value = false;
      loadOrder(); loadDeliveries();
    } else { ElMessage.error(res.message || '创建失败，请重试'); }
  } catch (e) { handleCatchError(e, '创建失败，请检查网络'); }
  finally { submitting.value = false; }
}

async function handleShip(deliveryId: string) {
  if (submitting.value) return;
  try {
    await ElMessageBox.confirm('确认发货？将扣减成品库存', '确认');
  } catch { return; }
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/sales/deliveries/${deliveryId}/ship`);
    if (res.success) { ElMessage.success('发货成功'); loadDeliveries(); loadOrder(); }
    else { ElMessage.error(res.message || '发货失败，请重试'); }
  } catch (e) { handleCatchError(e, '发货失败，请检查网络'); }
  finally { submitting.value = false; }
}

async function handleDelivered(deliveryId: string) {
  if (submitting.value) return;
  try {
    await ElMessageBox.confirm('确认客户已签收？', '确认');
  } catch { return; }
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/sales/deliveries/${deliveryId}/delivered`);
    if (res.success) { ElMessage.success('签收确认成功'); loadDeliveries(); loadOrder(); }
    else { ElMessage.error(res.message || '签收确认失败，请重试'); }
  } catch (e) { handleCatchError(e, '签收确认失败，请检查网络'); }
  finally { submitting.value = false; }
}

// 批次分配 (P0-13) — /ship endpoint 校验总分配量必须等于发货量, 否则返回
// "未完成批次分配". 此对话框调用 GET /recommend-fifo 预填 FIFO 推荐, 用户可调整.
async function openBatchAllocDialog(deliveryId: string, deliveryNumber: string) {
  batchAllocLoading.value = true;
  try {
    const detailRes = await get<TableRow>(`/${factoryId.value}/sales/deliveries/${deliveryId}`);
    if (!detailRes.success || !detailRes.data) { ElMessage.error('加载发货单明细失败'); return; }
    const rawItems = (detailRes.data.items as TableRow[]) || [];
    if (!rawItems.length) { ElMessage.warning('发货单无明细'); return; }

    const items: AllocItem[] = [];
    for (const it of rawItems) {
      const productTypeId = (it.productTypeId || (it.productType as TableRow)?.id) as string;
      const deliveredQuantity = Number(it.deliveredQuantity || 0);
      const productName = (it.productName as string) || ((it.productType as TableRow)?.name as string) || '未命名产品';
      const deliveryItemId = it.id as string;
      // R6 #7: 传该行的 sourceWarehouseCode 给 recommend-fifo, 使预填的 FEFO 推荐与
      // 后端 commit/deduct 用同一仓库 (后端 recommendFifo 已 honor 该参数; 不传则 default WH-LOG,
      // 与按行仓库发货的批次会对不上)。空值省略, 走后端默认。
      const sourceWarehouseCode = (it.sourceWarehouseCode as string) || '';

      let allocations: AllocRow[] = [];
      if (productTypeId && deliveredQuantity > 0) {
        const swcParam = sourceWarehouseCode
          ? `&sourceWarehouseCode=${encodeURIComponent(sourceWarehouseCode)}`
          : '';
        const recRes = await get<Array<TableRow>>(
          `/${factoryId.value}/sales-deliveries/items/${deliveryItemId}/batch-allocations/recommend-fifo?productTypeId=${productTypeId}&requiredQty=${deliveredQuantity}${swcParam}`
        );
        if (recRes.success && Array.isArray(recRes.data)) {
          allocations = recRes.data.map(r => ({
            finishedGoodsBatchId: String(r.batchId),
            batchNumber: String(r.batchNumber || ''),
            productionDate: String(r.productionDate || ''),
            availableQuantity: Number(r.availableQuantity || 0),
            allocatedQty: Number(r.recommendedQuantity || 0),
          }));
        }
      }
      items.push({ deliveryItemId, productName, deliveredQuantity, allocations });
    }
    batchAllocForm.value = { deliveryId, deliveryNumber, items };
    batchAllocDialogVisible.value = true;
  } catch (e) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '加载失败，请检查网络');
  } finally {
    batchAllocLoading.value = false;
  }
}

function sumAllocated(item: AllocItem): number {
  return item.allocations.reduce((s, a) => s + Number(a.allocatedQty || 0), 0);
}

async function handleBatchAllocate() {
  if (submitting.value) return;
  // Zero-quantity items don't need allocation (skip them from validation + submission).
  const activeItems = batchAllocForm.value.items.filter(it => it.deliveredQuantity > 0);
  if (activeItems.length === 0) {
    return ElMessage.warning('无需分配 (所有发货行数量为 0)');
  }
  // Validation: per-item total must equal deliveredQuantity (backend enforces)
  for (const item of activeItems) {
    if (item.allocations.length === 0) {
      return ElMessage.warning(`${item.productName}: 没有可用成品批次, 请先生产`);
    }
    const total = sumAllocated(item);
    if (Math.abs(total - item.deliveredQuantity) > 0.001) {
      return ElMessage.warning(`${item.productName}: 分配合计 ${total} 必须等于发货量 ${item.deliveredQuantity}`);
    }
    // Duplicate batch guard — backend 先清空再写入 would accept dupes silently.
    const uniqueIds = new Set(item.allocations.map(a => a.finishedGoodsBatchId));
    if (uniqueIds.size !== item.allocations.length) {
      return ElMessage.warning(`${item.productName}: 同一批次不能重复分配`);
    }
  }
  submitting.value = true;
  let success = 0, failed = 0;
  const errors: string[] = [];
  // Per-item try/catch — the axios interceptor rejects on success:false, so a
  // single outer try would abort the loop after the first failure and leave the
  // remaining items un-attempted (bug caught in code review).
  for (const item of activeItems) {
    try {
      const allocations = item.allocations
        .filter(a => Number(a.allocatedQty) > 0)
        .map(a => ({ finishedGoodsBatchId: a.finishedGoodsBatchId, allocatedQty: Number(a.allocatedQty) }));
      const res = await post(
        `/${factoryId.value}/sales-deliveries/items/${item.deliveryItemId}/batch-allocations`,
        { allocations },
      );
      if (res.success) success++;
      else { failed++; errors.push(`${item.productName}: ${res.message || '失败'}`); }
    } catch (e: unknown) {
      failed++;
      const msg = (e && typeof e === 'object' && 'message' in e)
        ? String((e as { message: unknown }).message)
        : '网络错误';
      errors.push(`${item.productName}: ${msg}`);
    }
  }
  submitting.value = false;
  if (failed === 0) {
    ElMessage.success(`批次分配成功 (${success} 项)`);
    batchAllocDialogVisible.value = false;
    loadDeliveries();
  } else {
    // Reload regardless — partial successes persisted on the server, user should
    // see current state before deciding whether to retry failed items.
    loadDeliveries();
    ElMessage.error(`${success} 成功 / ${failed} 失败:\n${errors.join('\n')}`);
  }
}

// ──────────────────────────────────────────────
// 开票申请 (V3 P0-3 / G1 — 税率分组)
// ──────────────────────────────────────────────

function openInvoiceDialog() {
  invoiceForm.value = { invoiceType: 'NORMAL', remark: '' };
  invoiceDialogVisible.value = true;
}

async function handleCreateInvoice() {
  if (submitting.value) return;
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/finance/invoices/request-from-order`, {
      salesOrderId: orderId.value,
      invoiceType: invoiceForm.value.invoiceType,
      remark: invoiceForm.value.remark,
    });
    if (res.success) {
      ElMessage.success(`开票申请已提交 (${res.data?.taxBreakdown?.length || 0} 个税率组)`);
      invoiceDialogVisible.value = false;
      loadInvoices();
      loadOrder();
    } else { ElMessage.error(res.message || '开票申请创建失败'); }
  } catch (e: unknown) {
    // Bug #8 fix (R21 2026-04-16): axios response interceptor 对所有 4xx/5xx 已显示 toast,
    // 这里 catch 不再叠加 "请检查网络" (否则用户看到两个 toast: 业务消息 + 误导网络提示)
    // 仅在 ApiError.status 未设 (纯网络错, 如无响应/CORS) 时才兜底
    const err = e as { status?: number };
    if (!err?.status) {
      // 真网络错误 — interceptor 来不及显示 (e.g. 断网)
      ElMessage.error('开票申请创建失败，请检查网络');
    }
    // 否则 interceptor 已显示后端 message, 此处不再 toast
  }
  finally { submitting.value = false; }
}

async function handleApproveInvoice(invoiceId: string) {
  if (submitting.value) return;
  try {
    const { value: notes } = await ElMessageBox.prompt('审核备注 (选填)', '审核通过开票申请', {
      confirmButtonText: '通过', cancelButtonText: '取消', inputPlaceholder: '（选填）',
    });
    submitting.value = true;
    const res = await post(`/${factoryId.value}/finance/invoices/${invoiceId}/approve`, { notes: notes || '' });
    if (res.success) { ElMessage.success('已审核通过'); loadInvoices(); }
    else ElMessage.error(res.message || '审核失败');
  } catch { /* cancelled */ }
  finally { submitting.value = false; }
}

async function handleRejectInvoice(invoiceId: string) {
  if (submitting.value) return;
  try {
    const { value: notes } = await ElMessageBox.prompt('请填写驳回原因', '驳回开票申请', {
      confirmButtonText: '驳回', cancelButtonText: '取消', inputValidator: (v) => !!v || '驳回原因必填',
    });
    submitting.value = true;
    const res = await post(`/${factoryId.value}/finance/invoices/${invoiceId}/reject`, { notes });
    if (res.success) { ElMessage.success('已驳回'); loadInvoices(); }
    else ElMessage.error(res.message || '驳回失败');
  } catch { /* cancelled */ }
  finally { submitting.value = false; }
}

// V3 P0-3c — 开具发票 (上传 PDF 闭环)
function openIssueDialog(invoiceId: string) {
  issueTargetInvoiceId.value = invoiceId;
  issuePdfFile.value = null;
  issueDialogVisible.value = true;
}

function handleFileChange(file: { raw: File }) {
  issuePdfFile.value = file.raw;
}

async function handleIssueInvoice() {
  if (submitting.value) return;
  if (!issuePdfFile.value) {
    return ElMessage.warning('请先选择发票 PDF 文件 (客户要求: 销售从订单页下载发票必须有附件)');
  }
  if (!issuePdfFile.value.name.toLowerCase().endsWith('.pdf')) {
    return ElMessage.warning('只支持 PDF 文件');
  }
  submitting.value = true;
  try {
    const formData = new FormData();
    formData.append('file', issuePdfFile.value);
    // 用原生 fetch 调 multipart endpoint, 走 vite proxy 不需要全 URL
    const url = `/api/mobile/${factoryId.value}/finance/invoices/${issueTargetInvoiceId.value}/issue`;
    const token = localStorage.getItem('cretas_access_token') || '';
    const resp = await fetch(url, {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: formData,
    });
    const data = await resp.json();
    if (data.success) {
      ElMessage.success('发票已开具, 销售可在订单页下载');
      issueDialogVisible.value = false;
      loadInvoices();
      loadOrder();
    } else {
      ElMessage.error(data.message || '开具失败');
    }
  } catch {
    ElMessage.error('开具失败, 请检查网络');
  } finally {
    submitting.value = false;
  }
}

// ──────────────────────────────────────────────
// 收款登记
// ──────────────────────────────────────────────

function openPaymentDialog() {
  const remaining = computedRemainingAmount();
  paymentForm.value = {
    amount: remaining > 0 ? remaining : 0,
    paymentMethod: 'BANK_TRANSFER',
    paymentDate: new Date().toISOString().slice(0, 10),
    paymentReference: '',
    remark: '',
    receiptUrl: '',
  };
  receiptFile.value = null;
  paymentDialogVisible.value = true;
}

function computedRemainingAmount() {
  if (!order.value) return 0;
  const total = Number(order.value.totalAmount || 0);
  const paid = Number(order.value.paidAmount || 0);
  return Math.max(0, total - paid);
}

// V3 P0-11 补强 — 审批进度时间线 (Verification Round 2 / Agent A 截图 3 硬伤)
// 客户截图底部固定显示 "张权 提交申请 → 刘会林 审批人(已同意)" timeline
// 我们从现有 SalesOrder 字段直接渲染, 不加新字段
const approvalTimeline = computed<Array<{
  type: 'success' | 'warning' | 'danger' | 'primary' | 'info';
  title: string;
  user: string;
  time: string;
  notes?: string;
}>>(() => {
  if (!order.value) return [];
  const o = order.value as TableRow;
  const nodes: Array<{ type: 'success' | 'warning' | 'danger' | 'primary' | 'info'; title: string; user: string; time: string; notes?: string }> = [];

  // 节点 1: 创建
  if (o.createdAt) {
    nodes.push({
      type: 'primary',
      title: '订单创建',
      user: String(o.salesperson || o.createdByName || `用户#${o.createdBy || '?'}`),
      time: String(o.createdAt),
    });
  }

  // 节点 2: 确认
  if (o.confirmedAt) {
    nodes.push({
      type: 'primary',
      title: '订单确认',
      user: String(o.confirmedByName || o.salesperson || '业务员'),
      time: String(o.confirmedAt),
    });
  }

  // 节点 3: 提交财务审核 (从 status 推断, 无独立时间字段)
  if (o.status === 'PENDING_FINANCE_REVIEW') {
    nodes.push({
      type: 'warning',
      title: '已提交财务审核',
      user: '系统',
      time: String(o.updatedAt || ''),
    });
  }

  // 节点 4: 财务审核通过 / 驳回
  if (o.financeReviewedAt) {
    const isApproved = ['FINANCE_APPROVED', 'PROCESSING', 'PARTIAL_DELIVERED', 'COMPLETED'].includes(String(o.status));
    nodes.push({
      type: isApproved ? 'success' : 'danger',
      title: isApproved ? '财务审核通过' : '财务审核驳回',
      user: String(o.financeReviewedByName || `财务#${o.financeReviewedBy || '?'}`),
      time: String(o.financeReviewedAt),
      notes: o.financeReviewNotes ? String(o.financeReviewNotes) : undefined,
    });
  }

  // 节点 5: 发货 (有 deliveries 记录就显示)
  if (deliveries.value.length > 0) {
    const latestDelivery = deliveries.value[0];
    nodes.push({
      type: ['DELIVERED'].includes(String(latestDelivery.status)) ? 'success' : 'warning',
      title: deliveries.value.length === 1 ? '已发货' : `已发货 (${deliveries.value.length} 单)`,
      user: '仓库',
      time: String(latestDelivery.shippedAt || latestDelivery.createdAt || latestDelivery.deliveryDate || ''),
    });
  }

  // Bug #29 fix (Apr 18 2026): 补齐开票 3 段事件 — 申请 / 审核 / 开具
  if (invoices.value.length > 0) {
    const sortedByRequest = [...invoices.value].sort((a, b) =>
      String(a.requestedAt || a.createdAt || '').localeCompare(String(b.requestedAt || b.createdAt || ''))
    );
    const earliestRequested = sortedByRequest[0].requestedAt || sortedByRequest[0].createdAt;
    if (earliestRequested) {
      nodes.push({
        type: 'primary',
        title: invoices.value.length === 1 ? '开票申请' : `开票申请 (${invoices.value.length} 条)`,
        user: '业务员',
        time: String(earliestRequested),
      });
    }

    const reviewed = invoices.value.filter(inv => inv.reviewedAt);
    if (reviewed.length > 0) {
      const latestReview = reviewed.sort((a, b) =>
        String(b.reviewedAt).localeCompare(String(a.reviewedAt))
      )[0];
      const isApproved = ['APPROVED', 'ISSUED'].includes(String(latestReview.status));
      nodes.push({
        type: isApproved ? 'success' : 'danger',
        title: reviewed.length === invoices.value.length
          ? (isApproved ? '发票审核通过' : '发票审核驳回')
          : `发票审核中 (${reviewed.length}/${invoices.value.length})`,
        user: String(latestReview.reviewedByName || `财务#${latestReview.reviewedBy || '?'}`),
        time: String(latestReview.reviewedAt),
        notes: latestReview.reviewNotes ? String(latestReview.reviewNotes) : undefined,
      });
    }

    const issued = invoices.value.filter(inv => inv.issuedAt);
    if (issued.length > 0) {
      const latestIssued = issued.sort((a, b) =>
        String(b.issuedAt).localeCompare(String(a.issuedAt))
      )[0];
      nodes.push({
        type: 'success',
        title: issued.length === invoices.value.length
          ? '发票已开具'
          : `发票开具中 (${issued.length}/${invoices.value.length})`,
        user: '财务',
        time: String(latestIssued.issuedAt),
      });
    }
  }

  // 节点 6: 收款 (有 payments 记录就显示)
  if (payments.value.length > 0) {
    const latestPayment = payments.value[0];
    nodes.push({
      type: ['VERIFIED'].includes(String(latestPayment.status)) ? 'success' : 'warning',
      title: payments.value.length === 1 ? '已收款' : `已收款 (${payments.value.length} 笔)`,
      user: '财务',
      time: String(latestPayment.paymentDate || latestPayment.createdAt || ''),
    });
  }

  // 节点 7: 取消 (终态)
  if (o.status === 'CANCELLED') {
    nodes.push({
      type: 'danger',
      title: '订单已取消',
      user: '系统',
      time: String(o.updatedAt || ''),
    });
  }

  // 节点 8: 完成 (终态)
  if (o.status === 'COMPLETED') {
    nodes.push({
      type: 'success',
      title: '订单完成',
      user: '系统',
      time: String(o.updatedAt || ''),
    });
  }

  return nodes;
});

async function handleReceiptChange(file: { raw: File }) {
  receiptFile.value = file.raw;
  // 立即上传, 拿到 URL 存入 form
  receiptUploading.value = true;
  try {
    const formData = new FormData();
    formData.append('file', file.raw);
    const token = localStorage.getItem('cretas_access_token') || '';
    const resp = await fetch(`/api/mobile/${factoryId.value}/upload/receipt`, {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: formData,
    });
    const json = await resp.json();
    if (json.success && json.data?.url) {
      paymentForm.value.receiptUrl = json.data.url;
      ElMessage.success('凭证已上传');
    } else {
      ElMessage.error(json.message || '凭证上传失败');
    }
  } catch {
    ElMessage.error('凭证上传失败');
  } finally {
    receiptUploading.value = false;
  }
}

async function handleCreatePayment() {
  if (submitting.value) return;
  if (!paymentForm.value.amount || paymentForm.value.amount <= 0) {
    return ElMessage.warning('收款金额必须 > 0');
  }
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/finance/payments/record`, {
      salesOrderId: orderId.value,
      amount: paymentForm.value.amount,
      paymentMethod: paymentForm.value.paymentMethod,
      paymentDate: paymentForm.value.paymentDate || null,
      paymentReference: paymentForm.value.paymentReference,
      remark: paymentForm.value.remark,
      receiptUrl: paymentForm.value.receiptUrl || null,
    });
    if (res.success) {
      ElMessage.success('收款记录已创建');
      paymentDialogVisible.value = false;
      loadPayments();
      loadOrder();
    } else { ElMessage.error(res.message || '创建失败'); }
  } catch (e) { handleCatchError(e, '创建失败，请检查网络'); }
  finally { submitting.value = false; }
}

// T-INV (issue: F006 客户反馈 — "一键收款"): one-click full-amount payment record.
// Customer's transcript wording was "一键收" — the 登记收款 dialog already auto-fills
// remaining amount, so a true "一键" UX is dialog-less: confirm modal → POST.
// Skips dialog form entirely; uses 默认 BANK_TRANSFER + today's date + no receipt.
async function handleQuickPayFull() {
  if (submitting.value) return;
  const remaining = computedRemainingAmount();
  if (remaining <= 0) {
    return ElMessage.info('订单已全额收款, 无需再收');
  }
  try {
    // formatAmount already prepends ¥ — do NOT add another (reviewer Important #1).
    // paymentDate: omit → backend defaults to server-local today (avoids UTC-midnight
    // drift where Asia/Shanghai 23:30 click would land on tomorrow's UTC date).
    await ElMessageBox.confirm(
      `应收余额 ${formatAmount(remaining)}, 收款方式默认 银行转账, 系统按今日记录. 一键登记?`,
      '一键收款确认',
      { type: 'warning' }
    );
  } catch { return; }
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/finance/payments/record`, {
      salesOrderId: orderId.value,
      amount: remaining,
      paymentMethod: 'BANK_TRANSFER',
      paymentDate: null, // let backend default to server-today (reviewer M2: avoid UTC drift)
      paymentReference: null, // no reference recorded for quick-pay path (reviewer M4)
      remark: '一键收款 (全额, 默认方式)',
      receiptUrl: null,
    });
    if (res.success) {
      ElMessage.success('一键收款已登记');
      loadPayments();
      loadOrder();
    } else { ElMessage.error(res.message || '创建失败'); }
  } catch (e) { handleCatchError(e, '一键收款失败, 请检查网络'); }
  finally { submitting.value = false; }
}
</script>

<template>
  <div class="page-wrapper" v-loading="loading">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <el-button :icon="ArrowLeft" @click="router.push('/sales/orders')">返回</el-button>
            <span class="page-title">{{ label('salesOrder') }}详情</span>
            <el-tag v-if="order" :type="(statusMap[order.status]?.type) || 'info'" size="large">
              {{ statusMap[order.status]?.text || order.status }}
            </el-tag>
            <el-tag v-if="order" :type="orderPaymentStatusMap[order.paymentStatus]?.type || 'info'" size="small" style="margin-left: 8px">
              收款: {{ orderPaymentStatusMap[order.paymentStatus]?.text || '待收款' }}
            </el-tag>
            <el-tag v-if="order" :type="orderInvoiceStatusMap[order.invoiceStatus]?.type || 'info'" size="small" style="margin-left: 4px">
              开票: {{ orderInvoiceStatusMap[order.invoiceStatus]?.text || '待开票' }}
            </el-tag>
            <el-tag v-if="order" :type="orderTransportStatusMap[order.transportPlanStatus]?.type || 'info'" size="small" style="margin-left: 4px">
              运输: {{ orderTransportStatusMap[order.transportPlanStatus]?.text || '待出厂' }}
            </el-tag>
          </div>
          <div class="header-right" v-if="order && canWrite">
            <el-button v-if="order.status === 'DRAFT'" type="success" :loading="submitting" @click="handleAction('confirm')">确认并判定审批</el-button>
            <el-button v-if="order.status === 'CONFIRMED'" type="warning" :loading="submitting" @click="handleAction('submit-for-review')">提交/判定审批</el-button>
            <el-button v-if="order.status === 'PENDING_FINANCE_REVIEW'" type="success" :loading="submitting" @click="openFinanceReview('approve')">审核通过</el-button>
            <el-button v-if="order.status === 'PENDING_FINANCE_REVIEW'" type="danger" :loading="submitting" @click="openFinanceReview('reject')">审核驳回</el-button>
            <el-button v-if="order.status === 'FINANCE_APPROVED'" type="primary" :loading="submitting" @click="handleStartProduction">开始生产</el-button>
            <el-button v-if="['CONFIRMED','FINANCE_APPROVED','PROCESSING','PARTIAL_DELIVERED'].includes(order.status)" type="primary" :loading="submitting" @click="openDeliveryDialog">{{ label('delivery') }}</el-button>
            <!-- T-RTA (issue #531): F006 客户反馈 第四次会议 956-1037 — 申请退货 入口.
                 Opens dialog that builds CreateReturnOrderRequest with returnType=SALES_RETURN. -->
            <el-button v-if="['PARTIAL_DELIVERED','DELIVERED','COMPLETED'].includes(order.status)"
                       type="warning" :loading="submitting" @click="openReturnDialog">申请退货</el-button>
            <el-button v-if="['DRAFT','CONFIRMED'].includes(order.status)" type="danger" :disabled="submitting" @click="handleAction('cancel')">取消</el-button>
            <!-- Sprint 4 Wave 2 S-PROFIT-DETAIL-1: per-product profit drill-down.
                 Only meaningful for non-DRAFT orders with line items + visible to price-cleared roles. -->
            <el-button
              v-if="canViewPrice && order.status !== 'DRAFT'"
              type="info"
              plain
              @click="router.push(`/sales/orders/${orderId}/profit`)"
            >产品级利润分析</el-button>
          </div>
        </div>
      </template>

      <!-- R18-ext #283: explicit empty state for 404 / not-found -->
      <el-empty
        v-if="notFound"
        :description="`订单 ${orderId} 不存在或已被删除`"
      >
        <el-button type="primary" @click="router.push('/sales/orders')">返回订单列表</el-button>
        <el-button @click="router.back()">返回上页</el-button>
      </el-empty>

      <template v-if="order && !notFound">
        <!-- 订单头部摘要 (4 状态联动) -->
        <el-descriptions :column="4" border>
          <el-descriptions-item label="订单编号">{{ order.orderNumber }}</el-descriptions-item>
          <el-descriptions-item :label="label('customer')">{{ order.customerName || order.customer?.name || order.customerId }}</el-descriptions-item>
          <el-descriptions-item label="下单日期">{{ order.orderDate }}</el-descriptions-item>
          <el-descriptions-item label="业务员">{{ order.salesperson || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="未税总额">{{ formatAmount(order.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="已发货金额">{{ order.actualShippedAmount ? formatAmount(order.actualShippedAmount) : '0.00' }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="已开票">{{ order.invoicedAmount ? formatAmount(order.invoicedAmount) : '0.00' }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="已收款">{{ order.paidAmount ? formatAmount(order.paidAmount) : '0.00' }}</el-descriptions-item>
        </el-descriptions>

        <!-- 4 tab 业务中心 (V3 P0-11 — 金矿截图 49m17s) -->
        <el-tabs v-model="activeTab" class="business-tabs">

          <!-- ─── Tab 1: 订单详情 ─── -->
          <el-tab-pane label="订单详情" name="detail">
            <el-descriptions :column="3" border style="margin-top: 8px">
              <el-descriptions-item label="交货日期">{{ order.requiredDeliveryDate || '-' }}</el-descriptions-item>
              <el-descriptions-item label="下单箱数">{{ order.boxQuantity || '-' }}</el-descriptions-item>
              <el-descriptions-item v-if="canViewPrice" label="折扣">{{ order.discountAmount ? formatAmount(order.discountAmount) : '-' }}</el-descriptions-item>
              <el-descriptions-item label="含运费">{{ order.shippingIncluded ? '是' : '否' }}</el-descriptions-item>
              <el-descriptions-item v-if="canViewPrice" label="运费">{{ order.shippingFee ? formatAmount(order.shippingFee) : '-' }}</el-descriptions-item>
              <el-descriptions-item v-if="canViewPrice" label="预估利润">{{ order.estimatedProfit ? formatAmount(order.estimatedProfit) : '-' }}</el-descriptions-item>
              <el-descriptions-item label="交货地址" :span="3">{{ order.deliveryAddress || '-' }}</el-descriptions-item>
              <el-descriptions-item label="备注" :span="3">{{ order.remark || '-' }}</el-descriptions-item>
            </el-descriptions>

            <h3 style="margin: 20px 0 12px">{{ label('product') }}明细</h3>
            <!-- Task 4: DRAFT 草稿可编辑产品行 -->
            <div v-if="isDraft && canWrite" style="margin-bottom: 8px;">
              <el-button type="primary" size="small" @click="openEditItems">编辑产品行</el-button>
            </div>
            <el-alert
              v-else-if="!isDraft && !(order.items && order.items.length)"
              type="info"
              :closable="false"
              title="该订单非草稿状态且无产品行，请联系销售在草稿阶段补充。"
            />
            <!-- T3-13 fix (issue #524): widen 规格 + 产品 columns + scroll-x fallback to prevent 成品详情规格列盖住. -->
            <el-table :data="order.items || []" border stripe style="width: 100%" :scrollbar-always-on="true">
              <el-table-column prop="productName" :label="label('product')" min-width="180" show-overflow-tooltip />
              <el-table-column prop="specification" label="规格" min-width="180" show-overflow-tooltip>
                <template #default="{ row }">{{ row.specification || '-' }}</template>
              </el-table-column>
              <el-table-column prop="quantity" label="订单数量" width="110" align="right" />
              <el-table-column prop="unit" label="单位" width="80" align="center" />
              <el-table-column prop="boxQuantity" label="箱数" width="80" align="right">
                <template #default="{ row }">{{ row.boxQuantity || '-' }}</template>
              </el-table-column>
              <el-table-column v-if="canViewPrice" prop="unitPrice" label="未税单价" width="120" align="right">
                <template #default="{ row }">{{ formatAmount(row.unitPrice) }}</template>
              </el-table-column>
              <el-table-column v-if="canViewPrice" label="含税单价" width="120" align="right">
                <template #default="{ row }">
                  {{ formatTaxIncludedUnitPrice(row) }}
                </template>
              </el-table-column>
              <el-table-column prop="taxRate" label="税率" width="80" align="center">
                <template #default="{ row }">
                  <el-tag v-if="row.taxRate != null" size="small"
                          :type="Number(row.taxRate) === 9 ? 'success' : (Number(row.taxRate) === 13 ? 'warning' : 'info')">
                    {{ row.taxRate }}%
                  </el-tag>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <el-table-column label="已发货" width="100" align="right">
                <template #default="{ row }">{{ row.deliveredQuantity || 0 }}</template>
              </el-table-column>
              <!-- T4-D1 (issue #525): 来源仓库 — F006 customer wants to see 总仓/线边仓 label per line.
                   Backend: sales_order_items.source_warehouse_code (V20260514_01 migration).
                   Label mapping via utils/warehouse.ts: WH-LOG → 总仓, WH-WKS → 线边仓. -->
              <el-table-column label="来源仓库" width="120">
                <template #default="{ row }">{{ warehouseDisplayLabel(row.sourceWarehouseCode) }}</template>
              </el-table-column>
              <!-- E-4 (六扇门需求矩阵 / PR #737/#749): 同时显示未税净价 + 含税价双值.
                   后端 SalesOrderItem.getLineAmount()       = 未税小计 (折后未税)
                   后端 SalesOrderItem.getLineAmountWithTax() = 含税小计 = 未税 × (1 + taxRate/100)
                   两者均为 @PriceSensitive — 无价格权限时后端返回 null → 显示 "—". -->
              <el-table-column v-if="canViewPrice" label="未税小计" width="130" align="right">
                <template #default="{ row }">
                  <span :title="'未含税金额 (折后)'">
                    {{ row.lineAmount != null ? formatAmount(row.lineAmount) : (row.unitPrice != null ? formatAmount(row.quantity * row.unitPrice) : '—') }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column v-if="canViewPrice" label="含税小计" width="140" align="right">
                <template #default="{ row }">
                  <template v-if="row.lineAmountWithTax != null">
                    <span style="font-weight: 600; color: #303133">{{ formatAmount(row.lineAmountWithTax) }}</span>
                    <div v-if="row.taxRate && Number(row.taxRate) > 0" style="font-size: 11px; color: #909399; margin-top: 2px">
                      税额 {{ formatAmount(row.lineAmountWithTax - (row.lineAmount ?? row.quantity * row.unitPrice)) }}
                    </div>
                  </template>
                  <span v-else-if="row.unitPrice != null">
                    <!-- 无 taxRate 时未税=含税 -->
                    {{ formatAmount(row.quantity * row.unitPrice) }}
                  </span>
                  <span v-else>—</span>
                </template>
              </el-table-column>
            </el-table>

            <!-- E-4 含税/未税汇总条 (仅在有税率数据时展示) -->
            <div v-if="canViewPrice && order.items && order.items.length > 0" class="tax-summary-bar">
              <template v-if="order.items.some((item: TableRow) => item.taxRate != null && Number(item.taxRate) > 0)">
                <span class="tax-summary-item">
                  <span class="tax-summary-label">未税总额</span>
                  <span class="tax-summary-value">
                    {{ formatAmount(order.items.reduce((s: number, item: TableRow) => s + (item.lineAmount ?? (item.unitPrice != null ? item.quantity * item.unitPrice : 0)), 0)) }}
                  </span>
                </span>
                <span class="tax-summary-sep">+</span>
                <span class="tax-summary-item">
                  <span class="tax-summary-label">税额合计</span>
                  <span class="tax-summary-value tax-value">
                    {{ formatAmount(order.items.reduce((s: number, item: TableRow) => {
                      const exTax = item.lineAmount ?? (item.unitPrice != null ? item.quantity * item.unitPrice : 0);
                      const withTax = item.lineAmountWithTax ?? exTax;
                      return s + (withTax - exTax);
                    }, 0)) }}
                  </span>
                </span>
                <span class="tax-summary-sep">=</span>
                <span class="tax-summary-item">
                  <span class="tax-summary-label">含税总额</span>
                  <span class="tax-summary-value tax-total">
                    {{ formatAmount(order.items.reduce((s: number, item: TableRow) => s + (item.lineAmountWithTax ?? (item.lineAmount ?? (item.unitPrice != null ? item.quantity * item.unitPrice : 0))), 0)) }}
                  </span>
                </span>
              </template>
            </div>

            <!-- ─── R14: 税率分组汇总 (Canvas FormulaEngine 驱动) ─── -->
            <div v-if="canViewPrice && taxGroupData" class="tax-group-section">
              <h3 style="margin: 20px 0 12px">税率分组汇总</h3>
              <el-table :data="taxGroupData" border stripe size="small" style="max-width: 400px">
                <el-table-column label="税率" width="120" align="center">
                  <template #default="{ row }">
                    <el-tag :type="row.taxRate === 9 ? 'success' : (row.taxRate === 13 ? 'warning' : 'info')" size="small">
                      {{ row.taxRate }}%
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="金额小计" width="160" align="right">
                  <template #default="{ row }">{{ formatAmount(row.amount) }}</template>
                </el-table-column>
              </el-table>
            </div>

            <!-- ─── 审批进度时间线 (V3 P0-11 补强 — 客户金矿截图 49m17s 底部 timeline) ─── -->
            <div class="approval-timeline-section">
              <h3>审批进度</h3>
              <el-empty v-if="approvalTimeline.length === 0" description="暂无审批记录" :image-size="60" />
              <el-timeline v-else>
                <el-timeline-item
                  v-for="(node, idx) in approvalTimeline"
                  :key="idx"
                  :type="node.type"
                  :timestamp="node.time"
                  placement="top"
                  size="large"
                >
                  <el-card shadow="hover" class="timeline-card">
                    <div class="timeline-title">{{ node.title }}</div>
                    <div class="timeline-meta">
                      <el-icon><i class="el-icon-user" /></el-icon>
                      <span>{{ node.user }}</span>
                    </div>
                    <div v-if="node.notes" class="timeline-notes">备注: {{ node.notes }}</div>
                  </el-card>
                </el-timeline-item>
              </el-timeline>
            </div>
          </el-tab-pane>

          <!-- ─── Tab 2: 开票申请 (V3 P0-3 / G1) ─── -->
          <el-tab-pane name="invoice">
            <template #label>
              开票申请
              <el-badge v-if="invoices.length" :value="invoices.length" :max="99" class="tab-badge" />
            </template>

            <div class="tab-toolbar">
              <el-button v-if="canWrite" type="primary" @click="openInvoiceDialog">
                + 一键开票申请 (按税率分组)
              </el-button>
              <span class="tab-hint">客户原话: 一笔订单可同时含 9% 原料 + 13% 加工费, 按税率分组拆分</span>
            </div>

            <el-table :data="invoices" border stripe style="margin-top: 12px">
              <el-table-column prop="invoiceNumber" label="发票编号" width="180" />
              <el-table-column label="状态" width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="invoiceStatusMap[row.status]?.type || 'info'" size="small">
                    {{ invoiceStatusMap[row.status]?.text || row.status }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column v-if="canViewPrice" prop="amount" label="不含税" width="120" align="right">
                <template #default="{ row }">{{ formatAmount(row.amount) }}</template>
              </el-table-column>
              <el-table-column v-if="canViewPrice" prop="taxAmount" label="税额" width="120" align="right">
                <template #default="{ row }">{{ formatAmount(row.taxAmount) }}</template>
              </el-table-column>
              <el-table-column v-if="canViewPrice" prop="totalAmount" label="价税合计" width="130" align="right">
                <template #default="{ row }">{{ formatAmount(row.totalAmount) }}</template>
              </el-table-column>
              <el-table-column v-if="canViewPrice" label="税率分组" min-width="240">
                <template #default="{ row }">
                  <div v-if="row.taxBreakdown && row.taxBreakdown.length" class="tax-breakdown">
                    <el-tag
                      v-for="(group, idx) in row.taxBreakdown"
                      :key="idx"
                      size="small"
                      :type="Number(group.taxRate) === 9 ? 'success' : (Number(group.taxRate) === 13 ? 'warning' : 'info')"
                      style="margin-right: 6px"
                    >
                      {{ group.taxRate }}% × {{ group.lineCount }} 行 = {{ formatAmount(group.taxableAmount) }} (税 {{ formatAmount(group.taxAmount) }})
                    </el-tag>
                  </div>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <el-table-column prop="requestedAt" label="申请时间" width="160" />
              <el-table-column label="操作" width="200" align="center" fixed="right">
                <template #default="{ row }">
                  <el-button v-if="row.status === 'REQUESTED' && canWrite" type="success" link size="small" @click="handleApproveInvoice(row.id)">通过</el-button>
                  <el-button v-if="row.status === 'REQUESTED' && canWrite" type="danger" link size="small" @click="handleRejectInvoice(row.id)">驳回</el-button>
                  <el-button v-if="row.status === 'APPROVED' && canWrite" type="primary" link size="small" @click="openIssueDialog(row.id)">上传发票</el-button>
                  <el-link v-if="row.invoicePdfUrl" :href="row.invoicePdfUrl" target="_blank" type="primary" :download="row.invoiceFileName || ''">
                    下载{{ row.invoiceFileName ? ` (${row.invoiceFileName})` : '' }}
                  </el-link>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <!-- ─── Tab 3: 销售出库 ─── -->
          <el-tab-pane name="delivery">
            <template #label>
              {{ label('delivery') }}记录
              <el-badge v-if="deliveries.length" :value="deliveries.length" :max="99" class="tab-badge" />
            </template>

            <div class="tab-toolbar">
              <el-button v-if="['CONFIRMED','FINANCE_APPROVED','PROCESSING','PARTIAL_DELIVERED'].includes(order.status) && canWrite"
                         type="primary" @click="openDeliveryDialog">
                + 新建{{ label('delivery') }}单
              </el-button>
            </div>

            <el-table :data="deliveries" border stripe style="margin-top: 12px">
              <el-table-column prop="deliveryNumber" label="发货单号" width="170" />
              <el-table-column prop="deliveryDate" label="发货日期" width="120" />
              <el-table-column prop="logisticsCompany" label="物流公司" width="120" />
              <el-table-column prop="trackingNumber" label="运单号" width="150" />
              <el-table-column prop="status" label="状态" width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="(delStatusMap[row.status]?.type) || 'info'" size="small">
                    {{ delStatusMap[row.status]?.text || row.status }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column v-if="canViewPrice" prop="totalAmount" label="金额" width="130" align="right">
                <template #default="{ row }">{{ formatAmount(row.totalAmount) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="260" align="center">
                <template #default="{ row }">
                  <!-- 分配批次: 销售/仓库均可 (planning 阶段) -->
                  <el-button v-if="['DRAFT','PENDING_WAREHOUSE_CONFIRM','PICKED'].includes(row.status) && canWrite" type="primary" link size="small" :disabled="submitting || batchAllocLoading" :loading="batchAllocLoading" @click="openBatchAllocDialog(row.id, row.deliveryNumber)">分配批次</el-button>
                  <!--
                    Issue #740: 发货确认 (扣库存) 需要 warehouse:read_write 权限.
                    销售看到此按钮 disable + tooltip "请前往 仓储管理 → 出货管理 由仓库确认".
                  -->
                  <el-tooltip
                    v-if="['DRAFT','PENDING_WAREHOUSE_CONFIRM','PICKED'].includes(row.status) && !canWarehouseConfirm"
                    content="发货确认 (扣库存) 由仓库角色操作, 请前往: 仓储管理 → 出货管理"
                    placement="top"
                  >
                    <el-button type="warning" link size="small" disabled>发货</el-button>
                  </el-tooltip>
                  <el-button v-if="['DRAFT','PENDING_WAREHOUSE_CONFIRM','PICKED'].includes(row.status) && canWarehouseConfirm" type="warning" link size="small" :disabled="submitting" @click="handleShip(row.id)">发货</el-button>
                  <el-button v-if="row.status === 'SHIPPED' && canWrite" type="success" link size="small" :disabled="submitting" @click="handleDelivered(row.id)">签收</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <!-- ─── Tab 4: 收款记录 ─── -->
          <el-tab-pane name="payment">
            <template #label>
              收款记录
              <el-badge v-if="payments.length" :value="payments.length" :max="99" class="tab-badge" />
            </template>

            <div class="tab-toolbar">
              <el-button v-if="canWrite" type="primary" @click="openPaymentDialog">
                + 登记收款
              </el-button>
              <!-- T-INV (F006 客户反馈): 一键收款 shortcut — only shown when remaining > 0.
                   Auto-fills remaining amount + BANK_TRANSFER + today; bypasses dialog form. -->
              <el-button v-if="canWrite && computedRemainingAmount() > 0" type="success"
                         :loading="submitting" @click="handleQuickPayFull">一键收款 (全额)</el-button>
              <span v-if="canViewPrice" class="tab-hint">订单总额 {{ formatAmount(order.totalAmount) }} / 已收 {{ formatAmount(order.paidAmount || 0) }} / 待收 {{ formatAmount(computedRemainingAmount()) }}</span>
            </div>

            <el-table :data="payments" border stripe style="margin-top: 12px">
              <el-table-column prop="paymentNumber" label="收款单号" width="180" />
              <el-table-column prop="paymentDate" label="收款日期" width="120" />
              <el-table-column v-if="canViewPrice" prop="amount" label="收款金额" width="130" align="right">
                <template #default="{ row }">{{ formatAmount(row.amount) }}</template>
              </el-table-column>
              <el-table-column prop="paymentMethod" label="收款方式" width="120" align="center">
                <template #default="{ row }">
                  {{ ({ BANK_TRANSFER: '银行转账', CASH: '现金', CHECK: '支票', WECHAT: '微信', ALIPAY: '支付宝', OTHER: '其他' } as Record<string, string>)[row.paymentMethod] || row.paymentMethod }}
                </template>
              </el-table-column>
              <el-table-column prop="paymentReference" label="参考号/凭证" width="180" />
              <el-table-column label="状态" width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="paymentStatusMap[row.status]?.type || 'info'" size="small">
                    {{ paymentStatusMap[row.status]?.text || row.status }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>

          <!-- ─── Tab 5: 关联采购订单 ─── -->
          <el-tab-pane name="purchase">
            <template #label>
              关联采购
              <el-badge v-if="purchaseOrders.length" :value="purchaseOrders.length" :max="99" class="tab-badge" />
            </template>

            <div class="tab-hint" style="padding: 12px 0">
              客户原话: 主原料 (贵重料) 必须按销售订单做定点追踪, 防止多采浪费
            </div>

            <el-table :data="purchaseOrders" border stripe>
              <el-table-column prop="orderNumber" label="采购单号" width="180" />
              <el-table-column prop="supplierName" label="供应商" min-width="150" />
              <el-table-column prop="orderDate" label="下单日期" width="120" />
              <el-table-column prop="status" label="状态" width="100" align="center">
                <template #default="{ row }">
                  <el-tag size="small">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column v-if="canViewPrice" prop="totalAmount" label="金额" width="130" align="right">
                <template #default="{ row }">{{ formatAmount(row.totalAmount) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="100" align="center">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="router.push(`/procurement/orders/${row.id}`)">查看</el-button>
                </template>
              </el-table-column>
              <template #empty>
                <el-empty description="暂无关联采购订单" :image-size="80" />
              </template>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-card>

    <!-- ─── 创建发货单对话框 ─── -->
    <el-dialog v-model="deliveryDialogVisible" :title="`创建${label('delivery')}单`" width="640px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="发货地址"><el-input v-model="deliveryForm.deliveryAddress" /></el-form-item>
        <el-form-item label="物流公司"><el-input v-model="deliveryForm.logisticsCompany" placeholder="如：顺丰冷链" /></el-form-item>
      </el-form>
      <el-table :data="deliveryForm.items" border style="margin-top: 12px">
        <el-table-column prop="productName" :label="label('product')" width="150" />
        <el-table-column label="发货数量" width="160">
          <template #default="{ row }">
            <el-input-number v-model="row.deliveredQuantity" :min="0" size="small" style="width: 130px" />
          </template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="80" align="center" />
        <el-table-column v-if="canViewPrice" prop="unitPrice" label="单价" width="120" align="right">
          <template #default="{ row }">{{ formatAmount(row.unitPrice) }}</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="deliveryDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreateDelivery">创建发货单</el-button>
      </template>
    </el-dialog>

    <!-- ─── T-RTA (issues #531 + #571 business logic): 申请退货 对话框 ─── -->
    <el-dialog v-model="returnDialogVisible" title="申请退货" width="780px" destroy-on-close>
      <el-alert type="info" show-icon :closable="false" style="margin-bottom: 12px"
                title="退货流程说明"
                description="提交后退货单为 DRAFT 草稿. 客户服务 → 销售退货 菜单里点 提交审批, 财务/管理员审批后才会触发库存或退款动作. 退货数量不能超过已发货数量." />
      <el-form label-width="100px">
        <!-- T-RTA business logic (#571): 有食物 / 无食物 分支决定后端 AR冲减 + 库存 时机. -->
        <el-form-item label="退货类型" required>
          <el-radio-group v-model="returnForm.withGoods">
            <el-radio :value="true">有食物退货 (库存入库总仓 → 完成时 AR 冲减)</el-radio>
            <el-radio :value="false">无食物退款 (审批立即 AR 冲减, 无库存动作)</el-radio>
          </el-radio-group>
          <div style="color: #909399; font-size: 12px; margin-top: 4px;">
            <span v-if="returnForm.withGoods">实物退货: 仓库收到货 → 总仓入库 (不良品状态) → 标记完成时触发 AR/AP 冲减.</span>
            <span v-else>无实物: 审批通过即触发 AR/AP 冲减, 不产生库存动作.</span>
          </div>
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="退货日期" required>
              <el-date-picker v-model="returnForm.returnDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="退货原因" required>
              <el-input v-model="returnForm.reason" placeholder="如: 客户验收不合格 / 包装破损 / 滞销退仓" maxlength="500" show-word-limit />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注">
          <el-input v-model="returnForm.remark" type="textarea" :rows="2" maxlength="1000" show-word-limit />
        </el-form-item>
        <el-divider content-position="left">退货明细</el-divider>
        <el-table :data="returnForm.items" border size="small">
          <el-table-column width="50" align="center">
            <template #default="{ row }"><el-checkbox v-model="row.selected" /></template>
          </el-table-column>
          <el-table-column label="品名" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.itemName }}</template>
          </el-table-column>
          <el-table-column label="已发货" width="100" align="right">
            <template #default="{ row }">{{ row.maxQuantity }}</template>
          </el-table-column>
          <el-table-column label="退货数量" width="140">
            <template #default="{ row }">
              <el-input-number v-model="row.quantity" :min="0" :max="row.maxQuantity || undefined" :precision="3"
                               :controls="false" size="small" style="width: 100%" :disabled="!row.selected" />
            </template>
          </el-table-column>
          <el-table-column label="行原因" min-width="180">
            <template #default="{ row }">
              <el-input v-model="row.reason" size="small" placeholder="(可选)" maxlength="500" :disabled="!row.selected" />
            </template>
          </el-table-column>
        </el-table>
      </el-form>
      <template #footer>
        <el-button @click="returnDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="returnSubmitting" @click="handleCreateReturn">提交退货申请</el-button>
      </template>
    </el-dialog>

    <!-- ─── 批次分配对话框 (P0-13 强制批次追溯) ─── -->
    <el-dialog v-model="batchAllocDialogVisible" title="成品批次分配" width="880px" destroy-on-close>
      <el-alert
        type="info"
        :closable="false"
        title="系统已按 FIFO (生产日期升序) 预填推荐批次"
        description="每行 '分配合计' 必须等于发货数量才能提交。可手动调整分配数量或选择其他批次。"
        style="margin-bottom: 12px"
      />
      <div v-if="batchAllocForm.deliveryNumber" style="margin-bottom: 8px; color: #606266;">
        发货单: <strong>{{ batchAllocForm.deliveryNumber }}</strong>
      </div>
      <div v-for="(item, idx) in batchAllocForm.items" :key="idx" style="margin-bottom: 20px; padding: 10px; border: 1px solid #ebeef5; border-radius: 4px;">
        <div style="display: flex; justify-content: space-between; margin-bottom: 10px;">
          <span style="font-weight: 500;">{{ item.productName }}</span>
          <span>
            发货数量: <strong>{{ item.deliveredQuantity }}</strong>
            <span style="margin-left: 16px;" :style="{ color: Math.abs(sumAllocated(item) - item.deliveredQuantity) < 0.001 ? '#67c23a' : '#f56c6c' }">
              分配合计: <strong>{{ sumAllocated(item) }}</strong>
            </span>
          </span>
        </div>
        <el-table v-if="item.allocations.length > 0" :data="item.allocations" border size="small">
          <el-table-column prop="batchNumber" label="批次号" width="200" />
          <el-table-column prop="productionDate" label="生产日期" width="120" />
          <el-table-column label="可用数量" width="110" align="right">
            <template #default="{ row }">{{ row.availableQuantity }}</template>
          </el-table-column>
          <el-table-column label="分配数量" width="180" align="center">
            <template #default="{ row }">
              <el-input-number v-model="row.allocatedQty" :min="0" :max="row.availableQuantity" :precision="2" :step="1" size="small" style="width: 150px" />
            </template>
          </el-table-column>
        </el-table>
        <el-alert
          v-else
          type="warning"
          :closable="false"
          title="没有可用成品批次"
          description="请先完成此产品的生产入库, 或联系仓管检查库存状态。"
        />
      </div>
      <template #footer>
        <el-button @click="batchAllocDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleBatchAllocate">确认分配</el-button>
      </template>
    </el-dialog>

    <!-- ─── 一键开票对话框 ─── -->
    <el-dialog v-model="invoiceDialogVisible" title="一键开票申请 (按税率自动分组)" width="520px" destroy-on-close>
      <el-alert
        type="info"
        :closable="false"
        title="系统将自动按销售订单明细的税率分组聚合"
        description="若订单含 9% 原料 + 13% 加工费, 会生成两组明细供财务审批。"
        style="margin-bottom: 16px"
      />
      <el-form label-width="90px">
        <el-form-item label="发票类型">
          <el-radio-group v-model="invoiceForm.invoiceType">
            <el-radio value="NORMAL">普通发票</el-radio>
            <el-radio value="SPECIAL">增值税专票</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="invoiceForm.remark" type="textarea" :rows="2" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="invoiceDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreateInvoice">提交开票申请</el-button>
      </template>
    </el-dialog>

    <!-- ─── 上传发票 PDF 对话框 (V3 P0-3c — 4 步开票闭环最后一环) ─── -->
    <el-dialog v-model="issueDialogVisible" title="开具发票 — 上传 PDF 附件" width="520px" destroy-on-close>
      <el-alert
        type="warning"
        :closable="false"
        title="客户原话: 销售从订单页直接下载发票"
        description="财务开具的发票 PDF 必须以附件形式上传到本申请, 否则无法完成开票闭环。"
        style="margin-bottom: 16px"
      />
      <el-upload
        :auto-upload="false"
        :limit="1"
        accept=".pdf,application/pdf"
        :on-change="handleFileChange"
        :on-remove="() => { issuePdfFile = null; }"
        drag
      >
        <el-icon class="el-icon--upload"><i class="el-icon-upload" /></el-icon>
        <div class="el-upload__text">将 PDF 文件拖到此处, 或<em>点击选择</em></div>
        <template #tip>
          <div class="el-upload__tip">仅支持 .pdf 格式, 单文件 ≤ 10MB</div>
        </template>
      </el-upload>
      <template #footer>
        <el-button @click="issueDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!issuePdfFile" @click="handleIssueInvoice">确认开具</el-button>
      </template>
    </el-dialog>

    <!-- ─── 登记收款对话框 ─── -->
    <el-dialog v-model="paymentDialogVisible" title="登记收款" width="520px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item v-if="canViewPrice" label="收款金额" required>
          <el-input-number v-model="paymentForm.amount" :min="0" :precision="2" style="width: 200px" />
          <span style="margin-left: 12px; color: #909399">待收 {{ formatAmount(computedRemainingAmount()) }}</span>
        </el-form-item>
        <el-form-item label="收款方式">
          <el-select v-model="paymentForm.paymentMethod" style="width: 200px">
            <el-option label="银行转账" value="BANK_TRANSFER" />
            <el-option label="现金" value="CASH" />
            <el-option label="支票" value="CHECK" />
            <el-option label="微信" value="WECHAT" />
            <el-option label="支付宝" value="ALIPAY" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="收款日期">
          <el-date-picker v-model="paymentForm.paymentDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="参考号/凭证">
          <el-input v-model="paymentForm.paymentReference" placeholder="银行流水号 / 凭证号" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="paymentForm.remark" type="textarea" :rows="2" placeholder="如: 定金 / 尾款" />
        </el-form-item>
        <el-form-item label="回款凭证">
          <el-upload
            :auto-upload="false"
            :limit="1"
            accept=".pdf,image/jpeg,image/png,.jpg,.png"
            :on-change="handleReceiptChange"
            :on-remove="() => { receiptFile = null; paymentForm.receiptUrl = ''; }"
          >
            <el-button size="small" :loading="receiptUploading">选择凭证</el-button>
            <template #tip>
              <div class="el-upload__tip">PDF/JPG/PNG, ≤10MB (可选)</div>
            </template>
          </el-upload>
          <span v-if="paymentForm.receiptUrl" style="font-size:12px;color:#67c23a;margin-left:8px">已上传</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="paymentDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreatePayment">登记收款</el-button>
      </template>
    </el-dialog>

    <!-- 六扇门 V1 §2.2 (audit fix 2026-04-26 #6): finance review dialog with cost breakdown -->
    <el-dialog
      v-model="financeReviewVisible"
      :title="financeReviewForm.isApprove ? '财务审核通过' : '财务审核驳回'"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form label-width="110px">
        <el-form-item label="订单号">
          <span style="font-family:monospace">{{ order?.orderNumber }}</span>
        </el-form-item>
        <el-form-item label="客户">
          <span>{{ order?.customerName || order?.customerId }}</span>
        </el-form-item>
        <el-form-item v-if="canViewPrice" label="订单总额">
          <span style="font-weight:600;color:#67C23A">{{ formatAmount(Number(order?.totalAmount || 0)) }}</span>
        </el-form-item>
        <!--
          P2-3 (audio May 7 客户通话): 客户要求暂时隐藏 "预估成本" 字段.
          原话: "这个建议暂时先去掉, 容易产生那个冲突的, 财务那边肯定会比较跳的".
          客户后期 (V2) 计划自动从 BOM 推导, 届时再启用此字段.

          双轨说明:
          - LEGACY (本文件): v-if="false" 暂时隐藏
          - CANVAS DynamicModulePage: estimatedCost 字段未在 sales_order
            field_schema (V20260409_02) 中暴露, 已经不显示, 无需 schema migration

          重新启用方式: 改 v-if="false" 为 v-if="financeReviewForm.isApprove"
        -->
        <el-form-item v-if="false" label="预估成本 (元)">
          <el-input-number
            v-model="financeReviewForm.estimatedCost"
            :min="0" :precision="2"
            placeholder="录入 BOM 材料成本 + 工时/制造费"
            style="width:100%"
            controls-position="right"
          />
          <div style="color:#909399;font-size:12px;margin-top:4px">
            提示: V1.5 手动录入,V2 将自动从 BOM 推导
          </div>
        </el-form-item>
        <!-- 预估利润依赖 estimatedCost, estimatedCost 未填则 financeReviewProfit=null, 此 form-item 自动隐藏 (无需独立改动) -->
        <el-form-item v-if="canViewPrice && financeReviewForm.isApprove && financeReviewProfit !== null" label="预估利润">
          <span :style="{ fontWeight: 600, color: financeReviewProfit >= 0 ? '#67C23A' : '#F56C6C' }">
            {{ formatAmount(financeReviewProfit) }}
            <span v-if="Number(order?.totalAmount || 0) > 0" style="color:#909399;font-size:12px;margin-left:8px">
              (毛利率 {{ ((financeReviewProfit / Number(order?.totalAmount || 1)) * 100).toFixed(1) }}%)
            </span>
          </span>
        </el-form-item>
        <el-form-item :label="financeReviewForm.isApprove ? '审核备注' : '驳回原因'">
          <el-input
            v-model="financeReviewForm.notes"
            type="textarea" :rows="3"
            :placeholder="financeReviewForm.isApprove ? '(选填) 财务审核意见' : '请说明驳回原因'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="financeReviewVisible = false">取消</el-button>
        <el-button
          :type="financeReviewForm.isApprove ? 'success' : 'danger'"
          :loading="submitting"
          @click="submitFinanceReview"
        >
          {{ financeReviewForm.isApprove ? '确认审核通过' : '确认驳回' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ─── Task 4: 编辑产品行对话框 ─── -->
    <el-dialog
      v-model="editItemsVisible"
      :title="`编辑产品行 — ${order?.orderNumber || ''}`"
      width="820px"
      destroy-on-close
    >
      <OrderItemsEditor v-model:items="editItemsRows" :products="products">
        <template #empty-products>
          <UpstreamMissingHint
            description="本工厂暂无产品类型，无法添加产品行"
            target-module="system"
            action-text="去创建产品类型"
            contact-text="请联系管理员先创建产品类型"
            @action="goCreateProductType"
          />
        </template>
      </OrderItemsEditor>
      <template #footer>
        <el-button @click="editItemsVisible = false">取消</el-button>
        <el-button type="primary" :loading="editItemsSaving" @click="saveEditItems">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper { height: 100%; width: 100%; display: flex; flex-direction: column; }
.page-card { flex: 1; display: flex; flex-direction: column;
  :deep(.el-card__header) { padding: 16px 20px; border-bottom: 1px solid #ebeef5; }
  :deep(.el-card__body) { flex: 1; padding: 20px; overflow-y: auto; }
}
.card-header { display: flex; justify-content: space-between; align-items: center;
  .header-left { display: flex; align-items: center; gap: 12px;
    .page-title { font-size: 16px; font-weight: 600; color: #303133; }
  }
  .header-right { display: flex; gap: 8px; }
}
.business-tabs { margin-top: 20px; }
.tab-toolbar {
  display: flex; align-items: center; gap: 16px; padding: 4px 0 12px;
  .tab-hint { color: #909399; font-size: 12px; }
}
.tab-badge { :deep(.el-badge__content) { transform: translateY(-2px) translateX(8px); } }
.tax-breakdown { display: flex; flex-wrap: wrap; gap: 4px; }
// E-4 含税/未税汇总条
.tax-summary-bar {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  margin-top: 8px; padding: 10px 16px;
  background: #f5f7fa; border-radius: 6px;
  font-size: 13px;
  .tax-summary-item { display: flex; flex-direction: column; align-items: flex-end; }
  .tax-summary-label { font-size: 11px; color: #909399; margin-bottom: 2px; }
  .tax-summary-value { font-weight: 600; color: #303133; }
  .tax-value { color: #e6a23c; }
  .tax-total { color: #409eff; font-size: 15px; }
  .tax-summary-sep { color: #c0c4cc; font-size: 16px; font-weight: 300; align-self: center; }
}

// V3 P0-11 补强 — 审批 timeline
.approval-timeline-section {
  margin-top: 28px;
  padding-top: 16px;
  border-top: 1px dashed #ebeef5;

  h3 { margin: 0 0 16px; font-size: 15px; color: #303133; }

  .timeline-card { padding: 8px 12px;
    .timeline-title { font-weight: 600; color: #303133; font-size: 14px; margin-bottom: 4px; }
    .timeline-meta { color: #606266; font-size: 12px; display: flex; align-items: center; gap: 4px; }
    .timeline-notes { margin-top: 6px; padding: 6px 8px; background: #fafafa; border-radius: 4px;
      color: #909399; font-size: 12px; }
  }
}
</style>
