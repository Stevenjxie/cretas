<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useBusinessMode } from '@/composables/useBusinessMode';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';
import request, { get, post } from '@/api/request';
// request.patch is used by U-MARKER-1 below; default export already imported.
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Search, Refresh, ChatDotRound, Download, QuestionFilled } from '@element-plus/icons-vue';
import AiEntryDrawer from '@/components/ai-entry/AiEntryDrawer.vue';
import AuditLogDrawer from '@/components/AuditLogDrawer.vue';
import { PURCHASE_ORDER_CONFIG } from '@/components/ai-entry/types';
import { WorkflowBar } from '@/components/workflow';
import { useWorkflowStats } from '@/composables/useWorkflowStats';
import { getBucketPrimaryStatus, getBucketLabel } from '@/types/workflow';
import { formatAmount } from '@/utils/tableFormatters';
import CanvasDynamicFields from '@/components/canvas/CanvasDynamicFields.vue';
import CanvasAwareWrapper from '@/components/canvas/CanvasAwareWrapper.vue';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import type { TableRow } from '@/types/api';
import { RowActionMenu, TableFooter, ViewModeSwitcher, GridView, KanbanView, TimelinePlaceholder, CalendarPlaceholder, InlineRowIcons, RowMarkerCell } from '@/components/list';
// Sprint 6 W3-A — inline 3-chip link counter (文件 / 图片 / 合同).
import LinkChipCell from '@/components/list/LinkChipCell.vue';
import { useLinkChipCounts } from '@/composables/useLinkChipCounts';
import { CreateModeSelector, BatchCreateDialog, QuickCreateDialog, BomExpansionDialog } from '@/components/dialog';
import CreateReturnOrderDialog from '@/components/dialog/CreateReturnOrderDialog.vue';
import { copyPurchaseOrder } from '@/api/orderCopy';
// PR #878 (#860 follow-up) — 转发 / 分享链接 dialog.
import ForwardShareDialog from '@/components/dialog/ForwardShareDialog.vue';
import type { ViewMode } from '@/types/viewMode';
import type { CreateMode } from '@/types/createMode';
import type { InlineIconId } from '@/types/inlineIcons';
import type { RowMarkerColor } from '@/types/rowMarker';
import { computeRowActions } from '@/composables/useRowActions';
import { useListSummary } from '@/composables/useListSummary';
import { formatSummaryForAI } from '@/utils/aiSummaryContext';
import type { ListSummaryRequest } from '@/types/listSummary';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const { label } = useBusinessMode();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('procurement'));
const { goCreate } = useCreateAndReturn();

const canViewPrice = computed(() => permissionStore.canViewPrice);

// U-VIEW-1 (Sprint 4 Wave 2 Chat L) — view-mode switcher (5 modes).
const viewMode = ref<ViewMode>('table');

// U-NEW-1 — create-mode selector (4 modes).
// Sprint 4 W2 Chat L shipped normal+batch. P1 #58 finishes quick + bom.
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
    await Promise.all([loadSuppliers(), loadMaterials(), loadSalesOrders()]);
    batchCreateVisible.value = true;
  } else if (mode === 'quick') {
    // P1 #58: minimal fields (supplier + type) for fast consecutive entry.
    await loadSuppliers();
    quickCreateVisible.value = true;
  } else if (mode === 'bom') {
    // P1 #58: parent PO + child items expanded from material template.
    await Promise.all([loadSuppliers(), loadMaterials()]);
    bomCreateVisible.value = true;
  }
}

// P1 #58 — Quick create (一维): supplier + type + expectedDate + remark.
interface QuickPurchaseOrderRow {
  supplierId: string;
  purchaseType: string;
  expectedDate: string;
  remark: string;
}
function quickPurchaseOrderFactory(): QuickPurchaseOrderRow {
  return { supplierId: '', purchaseType: 'NORMAL', expectedDate: '', remark: '' };
}
async function submitQuickPurchaseOrder(row: QuickPurchaseOrderRow): Promise<void> {
  if (!row.supplierId) {
    throw new Error('请选择供应商');
  }
  const payload = {
    supplierId: row.supplierId,
    purchaseType: row.purchaseType || 'NORMAL',
    expectedDate: row.expectedDate || null,
    remark: row.remark || '',
    items: [] as unknown[],
  };
  const res = await post(`/${factoryId.value}/purchase/orders`, payload);
  if (!res?.success) {
    throw new Error(res?.message || '创建失败');
  }
  await loadData();
}

// P1 #58 — BOM 展开: parent PO + child items from material template.
interface BomPurchaseOrderParent {
  supplierId: string;
  purchaseType: string;
  expectedDate: string;
  remark: string;
}
interface BomPurchaseOrderChild {
  materialId: string;
  materialName: string;
  quantity: number | string;
  unit: string;
  unitPrice: number | string;
  taxRate?: number | string | null;
}
function bomPurchaseOrderParentFactory(): BomPurchaseOrderParent {
  return { supplierId: '', purchaseType: 'NORMAL', expectedDate: '', remark: '' };
}
const bomPurchaseTemplates = computed(() =>
  (materials.value || []).map((m) => ({
    id: String(m.id || ''),
    name: String(m.name || m.code || ''),
    description: m.category ? `分类 ${m.category}` : '',
  }))
);
async function expandBomPurchaseTemplate(materialId: string): Promise<BomPurchaseOrderChild[]> {
  const tpl = materials.value.find((m) => String(m.id) === materialId);
  if (!tpl) return [];
  return [
    {
      materialId: String(tpl.id || ''),
      materialName: String(tpl.name || ''),
      quantity: 1,
      unit: String((tpl as Record<string, unknown>).unit || 'kg'),
      unitPrice: Number((tpl as Record<string, unknown>).referencePrice ?? (tpl as Record<string, unknown>).price ?? 0) || 0,
      taxRate: normalizeTaxRateForPayload(tpl.taxRate),
    },
  ];
}
async function submitBomPurchaseOrder(parent: BomPurchaseOrderParent, children: BomPurchaseOrderChild[]): Promise<void> {
  if (!parent.supplierId) {
    throw new Error('请选择供应商');
  }
  if (!children.length) {
    throw new Error('请至少添加 1 项明细');
  }
  const invalidChildIndex = children.findIndex((c) => validateTaxRate(c.taxRate));
  if (invalidChildIndex >= 0) {
    throw new Error(`第 ${invalidChildIndex + 1} 行税率必须是 0-100 之间的数字`);
  }
  const payload = {
    supplierId: parent.supplierId,
    purchaseType: parent.purchaseType || 'NORMAL',
    expectedDate: parent.expectedDate || null,
    remark: parent.remark || '',
    items: children.map((c) => ({
      materialId: c.materialId,
      materialName: c.materialName,
      quantity: Number(c.quantity) || 0,
      unit: c.unit || 'kg',
      unitPrice: Number(c.unitPrice) || 0,
      taxRate: normalizeTaxRateForPayload(c.taxRate),
    })),
  };
  const res = await post(`/${factoryId.value}/purchase/orders`, payload);
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
      // U-MARKER-1 — primary entry is the marker column dot; icon is parity fallback.
      ElMessage.info(`点击行首色点选择标记 (采购单 ${row.orderNumber})`);
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
      try {
        await ElMessageBox.confirm(`确认删除采购单 ${row.orderNumber}？`, '删除确认', { type: 'warning' });
        handleRowActionClick('delete', row);
      } catch {
        // user cancelled
      }
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
    const res = await request.patch(`/${factoryId.value}/markers/purchase-order/${row.id}`, {
      color,
    });
    if (res?.data?.success) {
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

function batchPurchaseFactory(): { supplierId: string; purchaseType: string; expectedDate: string; remark: string } {
  return { supplierId: '', purchaseType: 'NORMAL', expectedDate: '', remark: '' };
}
async function submitBatchPurchaseOrders(orders: Array<{ supplierId: string; purchaseType: string; expectedDate: string; remark: string }>): Promise<void> {
  const created: string[] = [];
  for (const order of orders) {
    if (!order.supplierId) continue;
    const payload = {
      supplierId: order.supplierId,
      purchaseType: order.purchaseType || 'NORMAL',
      expectedDate: order.expectedDate || null,
      remark: order.remark || '',
      items: [] as unknown[],
    };
    const res = await post(`/${factoryId.value}/purchase/orders`, payload);
    if (res?.success) created.push(String(res.data?.orderNumber || res.data?.id || ''));
  }
  if (!created.length) {
    throw new Error('未能创建任何订单（请确认每行至少填写供应商）');
  }
  await loadData();
}

function rowActionsFor(row: TableRow) {
  return computeRowActions(
    'purchaseOrder',
    { status: String(row.status || ''), id: String(row.id || '') },
    { canViewPrice: canViewPrice.value }
  );
}
// 退货 dialog state (#860 follow-up — wires existing backend ReturnOrderController).
const returnDialogVisible = ref(false);
const returnDialogRow = ref<TableRow | null>(null);
const returnDialogItems = computed(() => {
  const row = returnDialogRow.value;
  if (!row || !Array.isArray(row.items)) return [];
  return (row.items as TableRow[]).map((it) => ({
    id: it.id,
    materialTypeId: it.materialTypeId ? String(it.materialTypeId) : null,
    productTypeId: null as string | null,
    itemName: String(it.materialName || it.itemName || '-'),
    unitPrice: Number(it.unitPrice) || 0,
    // For procurement, cap return at ordered quantity (no per-line received-qty
    // hydration on list endpoint; service-side over-return guard will reject).
    maxQuantity: Number(it.quantity) || 0,
    batchNumber: it.batchNumber ? String(it.batchNumber) : null,
  }));
});
function openReturnDialog(row: TableRow): void {
  if (!Array.isArray(row.items) || row.items.length === 0) {
    ElMessage.warning('采购单无明细, 无法发起退货. 请打开订单详情确认.');
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
    case 'edit':
      ElMessage.info(`请在采购单 ${row.orderNumber || row.id} 详情页编辑`);
      router.push({ path: `/procurement/orders/${row.id}`, query: { action: 'edit' } });
      break;
    case 'submit': handleAction(String(row.id), 'submit'); break;
    case 'approve': handleAction(String(row.id), 'approve'); break;
    case 'reject': handleAction(String(row.id), 'reject'); break;
    case 'cancel': handleAction(String(row.id), 'cancel'); break;
    case 'print-pdf': handleDownloadPdf(row); break;
    case 'copy': void handleCopyOrder(row); break;
    case 'return': openReturnDialog(row); break;
    case 'edit-price':
      ElMessage.info(`请在采购单 ${row.orderNumber || row.id} 详情页维护行项目单价`);
      router.push({ path: `/procurement/orders/${row.id}`, query: { action: 'price' } });
      break;
    case 'view-price-history':
      ElMessage.info(`请在采购单 ${row.orderNumber || row.id} 详情页查看价格上下文`);
      router.push(`/procurement/orders/${row.id}`);
      break;
    case 'undo-approval':
      ElMessage({
        message: `采购单 ${row.orderNumber || row.id} 暂不支持列表撤销审批，请进入详情页按当前状态处理`,
        type: 'warning',
        duration: 0,
        showClose: true,
      });
      break;
    default: ElMessage.info(`Action: ${actionId}`);
  }
}

// #860 follow-up (2026-05-18): 复制采购订单 → 新草稿. 后端复用供应商/品项/价格,
// 不复制审批/到货状态. 错误由 axios interceptor sticky toast 显示, 不要 try/catch 吞.
async function handleCopyOrder(row: TableRow): Promise<void> {
  const orderNumber = String(row.orderNumber || row.id || '');
  try {
    await ElMessageBox.confirm(
      `确认复制采购单 ${orderNumber} 为新草稿？复制内容包含供应商、品项和价格，不复制审批/到货状态。`,
      '复制采购单',
      { confirmButtonText: '复制', cancelButtonText: '取消', type: 'info' }
    );
  } catch {
    return; // 用户取消
  }
  const res = await copyPurchaseOrder(factoryId.value, String(row.id));
  if (res?.success && res.data) {
    ElMessage.success(`已复制为 ${res.data.orderNumber}`);
    await loadData();
  }
}
function openAiForRow(row: TableRow) {
  console.info('[RowAction AI]', { entityType: 'purchaseOrder', entityId: row.id, orderNumber: row.orderNumber });
  aiEntryVisible.value = true;
}

// U-NAV-1 业务流程图导航 (Sprint 2 Track G + FU Chat 3 bucket-filter)
const { stats: workflowStats, loading: workflowLoading } = useWorkflowStats(factoryId, 'purchase');

// Sprint 6 W3-A — inline 3-chip 链接计数 (文件 / 图片 / 合同).
const { fetchLinkChipCounts, countsFor: linkCountsFor } =
  useLinkChipCounts(factoryId, 'PURCHASE_ORDER');

function handleWorkflowNodeClick(nodeId: string) {
  const primary = getBucketPrimaryStatus('purchase', nodeId);
  if (!primary) return;
  statusFilter.value = primary;
  pagination.value.page = 1;
  loadData();
  ElMessage.success(`已切到 "${getBucketLabel('purchase', nodeId)}" (显示状态: ${primary}). bucket 含多个状态, 想看其他请打开状态下拉切换.`);
}

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const statusFilter = ref('');
const searchKeyword = ref('');
const dateRange = ref<[string, string] | null>(null);
const dialogVisible = ref(false);

// U-FOOTER-1: sticky summary stats
const summaryRequest = computed<ListSummaryRequest>(() => ({
  filterConditions: statusFilter.value ? { status: statusFilter.value } : {},
}));
const { summary: footerSummary, loading: footerLoading } = useListSummary('purchaseOrder', summaryRequest);

interface ProcurementOrderItem {
  materialTypeId: string;
  quantity: number;
  unit: string;
  unitPrice: number;
  taxRate?: number | string | null;
  specification?: string;
  boxQuantity?: number;
}

const commonTaxRateOptions = [
  { label: '0%', value: 0 },
  { label: '9%', value: 9 },
  { label: '13%', value: 13 },
];

function validateTaxRate(rate: unknown): string | null {
  if (rate == null || rate === '') return null;
  const numeric = Number(rate);
  if (!Number.isFinite(numeric) || numeric < 0 || numeric > 100) {
    return '税率必须是 0-100 之间的数字';
  }
  return null;
}

function normalizeTaxRateForPayload(rate: unknown): number | null {
  const message = validateTaxRate(rate);
  if (message) throw new Error(message);
  if (rate == null || rate === '') return null;
  return Number(rate);
}

const form = ref({
  supplierId: '',
  purchaseType: 'DIRECT',
  expectedDeliveryDate: '',
  remark: '',
  relatedSalesOrderId: '',
  contractNumber: '',       // SP6 合同号（选填，对应纸质/框架合同）
  settlementType: '',       // SP6 结算方式
  invoiceReminderDays: null as number | null,  // SP6 开票提醒天数
  items: [{ materialTypeId: '', quantity: 0, unit: 'kg', unitPrice: 0, taxRate: null }] as ProcurementOrderItem[],
  customFields: {} as TableRow,
});
const suppliers = ref<TableRow[]>([]);
const materials = ref<TableRow[]>([]);
const salesOrders = ref<TableRow[]>([]);

// 包装层级缓存: materialTypeId → { level1Unit, level2Unit, level3Unit, level1PerLevel2 }
// 选原料后调 /material-packaging/by-material/{id} 拉取, 用于单位下拉 + 自动算箱数 (P1-2)
const packagingCache = ref<Record<string, {
  level1Unit?: string;
  level2Unit?: string | null;
  level3Unit?: string | null;
  level1PerLevel2?: number | null;
}>>({});

async function ensurePackagingLoaded(materialId: string) {
  if (!materialId || packagingCache.value[materialId]) return;
  try {
    const res = await get<{
      level1Unit?: string;
      level2Unit?: string | null;
      level3Unit?: string | null;
      level1PerLevel2?: number | null;
    } | null>(
      `/${factoryId.value}/material-packaging/by-material/${materialId}`,
    );
    packagingCache.value[materialId] = res.data || {};
  } catch {
    packagingCache.value[materialId] = {};
  }
}

function getUnitOptionsForItem(item: TableRow): { value: string; label: string }[] {
  const matId = String(item.materialTypeId || '');
  const set = new Set<string>();
  // 优先该原料的 1/2/3 级单位
  const pkg = packagingCache.value[matId];
  if (pkg) {
    if (pkg.level1Unit) set.add(pkg.level1Unit);
    if (pkg.level2Unit) set.add(pkg.level2Unit);
    if (pkg.level3Unit) set.add(pkg.level3Unit);
  }
  // 兜底: 该原料的默认 unit (如果有 packaging 已经在内)
  const mat = materials.value.find((m) => m.id === matId);
  if (mat && mat.unit) set.add(String(mat.unit));
  // 当前已选的 unit (历史值兼容)
  const cur = String(item.unit || '');
  if (cur && !set.has(cur)) set.add(cur);
  return Array.from(set).map((u) => ({ value: u, label: u }));
}

function onItemMaterialChange(item: TableRow) {
  const matId = String(item.materialTypeId || '');
  if (!matId) return;
  ensurePackagingLoaded(matId).then(() => {
    // 选原料后默认带入该原料的一级单位 (除非用户已经填了)
    const mat = materials.value.find((m) => m.id === matId);
    if (mat && mat.unit && !item.unit) item.unit = String(mat.unit);
    if (mat && mat.taxRate != null && item.taxRate == null && !validateTaxRate(mat.taxRate)) {
      item.taxRate = Number(mat.taxRate);
    }
    recalcBoxQuantity(item);  // P1-2: packaging 拉到后立即算箱数
  });
}

/**
 * P1-2/3 (audio May 7 客户通话): 箱数自动算 + 抄码品识别.
 *
 * 客户原话: "規格寫是抄码那我在折算箱數的時候比如說那個規格是抄码,
 * 那個箱數自動會顯示抄码品, 然後就不顯示多少箱". 抄码 = 餐饮/食品行业
 * 称重商品 (每箱重量不一致, 如鲜牛肉/鲜鱼), 不能按箱计.
 *
 * 双轨说明: 这是 LEGACY 实现. CANVAS DynamicModulePage 等 Phase B C-6
 * 框架落地 (ReferenceSelector projectFields + SpEL evaluator), 见
 * docs/superpowers/specs/2026-05-09-canvas-c6-reactive-default-framework.md
 */
/**
 * R2 fix #3: 精确匹配 trim() === '抄码', 不用 includes.
 * 旧: includes 误报 — "抄码区限量款" / "抄码加工标识" 都会被识别为抄码品.
 * 客户原话 "規格寫是抄码" 暗示 spec 字面值就是 "抄码", 而非含 "抄码" 子串.
 * 后续如客户报漏匹配 (繁体 "抄碼" / 含空格), 改加 normalize: trim + lower + 简繁同义.
 */
function isAbacaItem(item: TableRow): boolean {
  return String(item.specification || '').trim() === '抄码';
}

function recalcBoxQuantity(item: TableRow) {
  if (isAbacaItem(item)) {
    item.boxQuantity = null;  // 抄码品: 不算箱数
    return;
  }
  const matId = String(item.materialTypeId || '');
  const qty = Number(item.quantity || 0);
  const pkg = packagingCache.value[matId];
  if (!pkg || qty <= 0) return;

  // I-1 reviewer fix: 用户选 1/2/3 级单位决定如何算箱数, 不再硬编码假设一级单位.
  // 旧逻辑: 任何 unit 都按 qty / level1PerLevel2 算 → 用户选"箱"时 5 箱被算成 0.5 箱.
  const unit = String(item.unit || '');
  if (!unit || unit === pkg.level1Unit) {
    // 一级单位 (如 kg) → 按转换系数算箱: box = qty / level1PerLevel2
    if (!pkg.level1PerLevel2 || Number(pkg.level1PerLevel2) <= 0) return;
    item.boxQuantity = Math.round((qty / Number(pkg.level1PerLevel2)) * 100) / 100;
  } else if (unit === pkg.level2Unit) {
    // 二级单位 (如 箱) → 数量本身就是箱数, 直接赋值
    item.boxQuantity = qty;
  }
  // 其他单位 (level3 柜 / 用户自定义 allow-create 单位) → 不自动算, 保留用户手动填值
}

const statusMap: Record<string, { text: string; type: string }> = {
  DRAFT: { text: '草稿', type: 'info' },
  SUBMITTED: { text: '已提交', type: 'warning' },
  APPROVED: { text: '已审批', type: '' },
  PARTIAL_RECEIVED: { text: '部分收货', type: 'warning' },
  COMPLETED: { text: '已完成', type: 'success' },
  CANCELLED: { text: '已取消', type: 'danger' },
  CLOSED: { text: '已关闭', type: 'info' },
};

// D13: Dirty form guard — warn user before leaving with unsaved changes
const isDirty = ref(false);
watch(dialogVisible, (val) => { isDirty.value = val; });
function handleBeforeUnload(e: BeforeUnloadEvent) {
  if (isDirty.value) { e.preventDefault(); e.returnValue = ''; }
}
onMounted(() => {
  loadData();
  loadSuppliers();
  loadMaterials();
  loadSalesOrders();
  window.addEventListener('beforeunload', handleBeforeUnload);
});
onBeforeUnmount(() => { window.removeEventListener('beforeunload', handleBeforeUnload); });

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const url = statusFilter.value
      ? `/${factoryId.value}/purchase/orders/by-status`
      : `/${factoryId.value}/purchase/orders`;
    const params: TableRow = { page: pagination.value.page, size: pagination.value.size };
    if (statusFilter.value) params.status = statusFilter.value;
    const response = await get(url, { params });
    if (response.success && response.data) {
      let rows = response.data.content || [];
      // Client-side keyword + date filter (Apr 21 2026): backend lacks
      // keyword param on purchase/orders; filter locally on current page.
      const kw = searchKeyword.value.trim().toLowerCase();
      if (kw) {
        rows = rows.filter((r: TableRow) =>
          String(r.orderNumber || '').toLowerCase().includes(kw) ||
          String(r.supplierName || (r.supplier as TableRow)?.name || '').toLowerCase().includes(kw)
        );
      }
      if (dateRange.value && dateRange.value[0] && dateRange.value[1]) {
        const [from, to] = dateRange.value;
        rows = rows.filter((r: TableRow) => {
          const d = String(r.orderDate || '').slice(0, 10);
          return d && d >= from && d <= to;
        });
      }
      tableData.value = rows;
      pagination.value.total = response.data.totalElements || 0;

      // Sprint 6 W3-A — fire-and-forget batch 3-chip counts (文件/图片/合同).
      // List itself is unaffected by chip request failure; chip falls back to "-".
      void fetchLinkChipCounts(rows.map((r: TableRow) => String(r.id)).filter(Boolean));
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载数据失败');
    }
  } catch (error) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', error);
  } finally {
    loading.value = false;
  }
}

async function loadSuppliers() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/suppliers`, { params: { page: 1, size: 100 } });
    if (res.success && res.data) suppliers.value = res.data.content || [];
  } catch { /* axios interceptor already displayed error toast */ }
}

async function loadMaterials() {
  if (!factoryId.value) return;
  try {
    // Bug B3 fix: use /active endpoint (unpaginated, same source as BOM and warehouse)
    const res = await get(`/${factoryId.value}/raw-material-types/active`);
    if (res.success && res.data) materials.value = Array.isArray(res.data) ? res.data : res.data.content || [];
  } catch { /* axios interceptor already displayed error toast */ }
}

async function loadSalesOrders() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/sales/orders`, { params: { page: 1, size: 100 } });
    if (res.success && res.data) salesOrders.value = res.data.content || [];
  } catch { /* optional field, ignore errors */ }
}

function addItem() {
  form.value.items.push({ materialTypeId: '', quantity: 0, unit: 'kg', unitPrice: 0, taxRate: null });
}

function removeItem(index: number) {
  if (form.value.items.length > 1) form.value.items.splice(index, 1);
}

const submitting = ref(false);

async function handleCreate() {
  if (submitting.value) return; // P-2: prevent double-submit
  if (!form.value.supplierId) return ElMessage.warning('请选择供应商');
  if (!Array.isArray(form.value.items) || form.value.items.length === 0) {
    return ElMessage.warning('请至少添加一行原料明细');
  }
  if (form.value.items.some(i => !i.materialTypeId)) return ElMessage.warning('请选择所有原料');
  const invalidQtyIndex = form.value.items.findIndex(i => !(Number(i.quantity) > 0));
  if (invalidQtyIndex >= 0) {
    return ElMessage.warning(`第 ${invalidQtyIndex + 1} 行采购数量必须大于 0`);
  }
  if (form.value.items.some(i => !i.unit)) return ElMessage.warning('请填写所有明细的单位');
  const invalidTaxRateIndex = form.value.items.findIndex((i) => validateTaxRate(i.taxRate));
  if (invalidTaxRateIndex >= 0) {
    return ElMessage.warning(`第 ${invalidTaxRateIndex + 1} 行税率必须是 0-100 之间的数字`);
  }
  submitting.value = true;
  try {
    // W-12 fix (Round 15): previously relatedSalesOrderId was stripped from payload
    // and only embedded as "[关联销售订单: SO-XXX]" in remark. Now that the backend has
    // a proper sales_order_id column + filter (V20260424_03 + PurchaseService fix),
    // pass it through as salesOrderId. Keep the remark text for backward-compat
    // readability — users who view old POs will still see the ref.
    let remark = form.value.remark || '';
    const salesOrderId = form.value.relatedSalesOrderId || null;
    if (form.value.relatedSalesOrderId) {
      const so = salesOrders.value.find((o: TableRow) => o.id === form.value.relatedSalesOrderId);
      const soRef = so ? `[关联销售订单: ${so.orderNumber}]` : '';
      remark = soRef ? (remark ? `${soRef} ${remark}` : soRef) : remark;
    }
    const { relatedSalesOrderId: _unused, items, ...formData } = form.value;
    const payload = {
      ...formData,
      items: items.map((i) => ({
        ...i,
        taxRate: normalizeTaxRateForPayload(i.taxRate),
      })),
      remark,
      salesOrderId,
      orderDate: new Date().toISOString().slice(0, 10), // backend requires @NotNull orderDate
    };
    const res = await post(`/${factoryId.value}/purchase/orders`, payload);
    if (res.success) {
      ElMessage.success('创建成功');
      dialogVisible.value = false;
      resetForm();
      loadData();
    } else {
      ElMessage.error(res.message || '创建失败');
    }
  } catch (error) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', error);
  } finally {
    submitting.value = false;
  }
}

function resetForm() {
  form.value = { supplierId: '', purchaseType: 'DIRECT', expectedDeliveryDate: '', remark: '', relatedSalesOrderId: '', contractNumber: '', settlementType: '', invoiceReminderDays: null, items: [{ materialTypeId: '', quantity: 0, unit: 'kg', unitPrice: 0, taxRate: null }], customFields: {} as TableRow };
}

// 张权 Apr 28 反馈: "基础数据已经新建了 但是采购订单 下拉没有选项"
// — 用户先打开本页, dropdown 已加载; 然后跳到基础数据页新建供应商/原料;
// 切回本页打开新建对话框时, dropdown 仍是旧 cache. 修复: 每次打开对话框
// 强制刷新 3 个 dropdown 数据源.
async function openCreateDialog() {
  resetForm();
  await Promise.all([loadSuppliers(), loadMaterials(), loadSalesOrders()]);
  dialogVisible.value = true;
}

async function handleAction(orderId: string, action: string) {
  const actionMap: Record<string, { label: string; url: string }> = {
    submit: { label: '提交', url: `/${factoryId.value}/purchase/orders/${orderId}/submit` },
    approve: { label: '审批', url: `/${factoryId.value}/purchase/orders/${orderId}/approve` },
    cancel: { label: '取消', url: `/${factoryId.value}/purchase/orders/${orderId}/cancel` },
  };
  const a = actionMap[action];
  if (!a) return;
  // 防呆 Rule2 (fool-proof-design): 确认框必带身份信息 (单号/供应商/金额),
  // 不用泛泛的"确认审批此采购订单？" —— 低技术素养用户需明确知道在操作哪一单。
  const row = tableData.value.find((r) => String(r.id) === String(orderId));
  const ctx = row
    ? `${row.orderNumber || ''}${row.supplierName ? ' · ' + row.supplierName : ''}` +
      `${row.totalAmount != null ? ' · ¥' + Number(row.totalAmount).toLocaleString('zh-CN', { minimumFractionDigits: 2 }) : ''}`
    : '';
  try {
    await ElMessageBox.confirm(
      ctx ? `确认${a.label}采购订单 ${ctx}？` : `确认${a.label}此${label('purchaseOrder')}？`,
      `${a.label}采购订单`,
      { type: 'warning', confirmButtonText: `确认${a.label}`, cancelButtonText: '取消' },
    );
    const res = await post(a.url);
    if (res.success) {
      ElMessage.success(`${a.label}成功`);
      loadData();
    } else {
      ElMessage.error(res.message || `${a.label}失败`);
    }
  } catch (error) { if (error !== 'cancel') ElMessage.error(`${a.label}失败`); }
}

function goDetail(id: string) {
  router.push(`/procurement/orders/${id}`);
}

// P0 (六扇门 May 7 transcript): 列表行内直接下载 PDF 供货单
const pdfDownloadingIds = ref<Set<string>>(new Set());
async function handleDownloadPdf(row: TableRow) {
  if (!factoryId.value || !row.id) return;
  const id = String(row.id);
  if (pdfDownloadingIds.value.has(id)) return;
  pdfDownloadingIds.value.add(id);
  try {
    const response = await request.get(
      `/${factoryId.value}/purchase/orders/${id}/pdf`,
      {
        params: { external: true },
        responseType: 'blob',
      }
    );
    const blob = new Blob([response.data], { type: 'application/pdf' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `供货单_${row.orderNumber || id}.pdf`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
    ElMessage.success('PDF 下载成功');
  } catch (e) {
    console.error('[PDF 下载失败]', e);
    ElMessage.error('PDF 下载失败,请稍后重试');
  } finally {
    pdfDownloadingIds.value.delete(id);
  }
}

function handlePageChange(page: number) { pagination.value.page = page; loadData(); }
function handleSizeChange(size: number) { pagination.value.size = size; pagination.value.page = 1; loadData(); }
function handleStatusChange() { pagination.value.page = 1; loadData(); }
function handleSearch() { pagination.value.page = 1; loadData(); }
function handleRefresh() {
  statusFilter.value = '';
  searchKeyword.value = '';
  dateRange.value = null;
  pagination.value.page = 1;
  loadData();
}

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
  // Match supplierName to supplierId
  const supplierName = String(params.supplierName || '');
  const matched = suppliers.value.find(
    (s: TableRow) => String(s.name || '').includes(supplierName) || supplierName.includes(String(s.name || ''))
  );

  form.value.supplierId = matched ? String(matched.id) : '';
  form.value.purchaseType = String(params.purchaseType || 'DIRECT');
  form.value.expectedDeliveryDate = String(params.expectedDeliveryDate || '');
  form.value.remark = String(params.remark || '');

  if (Array.isArray(params.items) && params.items.length > 0) {
    form.value.items = (params.items as TableRow[]).map((item) => {
      // Try to match materialName to materialTypeId
      const matName = String(item.materialName || '');
      const matMatch = materials.value.find(
        (m: TableRow) => String(m.name || '').includes(matName) || matName.includes(String(m.name || ''))
      );
      return {
        materialTypeId: matMatch ? String(matMatch.id) : '',
        quantity: Number(item.quantity || 0),
        unit: String(item.unit || 'kg'),
        unitPrice: Number(item.unitPrice || 0),
        taxRate: validateTaxRate(item.taxRate) ? null : normalizeTaxRateForPayload(item.taxRate),
      };
    });
  }

  dialogVisible.value = true;
}
</script>

<template>
  <CanvasAwareWrapper module-code="purchase_order">
  <div class="page-wrapper">
    <!-- U-NAV-1 业务流程图导航 (Sprint 2 Track G) -->
    <WorkflowBar
      :nodes="workflowStats?.nodes ?? []"
      :loading="workflowLoading"
      title="采购工作流"
      :ai-trigger-enabled="true"
      @node-click="handleWorkflowNodeClick"
      @ai-trigger="aiEntryVisible = true"
    />
    <ConceptDisambiguationAlert
      here-name="采购订单"
      here="我们向供应商下的订单（进货方向、应付账款）"
      other-name="销售管理 → 销售订单"
      other="客户向我们下的订单（出货方向、应收账款）"
      other-path="/sales/orders"
    />
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">{{ label('purchaseOrder') }}管理</span>
            <span class="data-count">共 {{ pagination.total }} 条记录</span>
          </div>
          <div class="header-right">
            <el-button v-if="canWrite" type="success" :icon="ChatDotRound" @click="aiEntryVisible = true">
              AI录入
            </el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreateModeSelector">
              新建{{ label('purchaseOrder') }}
            </el-button>
          </div>
        </div>
      </template>

      <div class="search-bar">
        <el-input
          v-model="searchKeyword"
          placeholder="搜索 订单号 / 供应商"
          clearable
          style="width: 220px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="下单起始"
          end-placeholder="下单结束"
          value-format="YYYY-MM-DD"
          style="width: 280px"
          @change="handleSearch"
        />
        <el-select v-model="statusFilter" placeholder="按状态筛选" clearable style="width: 160px" @change="handleStatusChange">
          <el-option v-for="(v, k) in statusMap" :key="k" :label="v.text" :value="k" />
        </el-select>
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
        <!-- U-VIEW-1 view-mode switcher (Sprint 4 Wave 2 Chat L) -->
        <div style="margin-left: auto">
          <ViewModeSwitcher v-model="viewMode" />
        </div>
      </div>

      <GridView
        v-if="viewMode === 'grid'"
        :rows="tableData"
        title-field="orderNumber"
        subtitle-field="supplierName"
        status-field="status"
        row-key="id"
      />
      <KanbanView
        v-else-if="viewMode === 'kanban'"
        :rows="tableData"
        status-field="status"
        title-field="orderNumber"
        subtitle-field="supplierName"
        :columns="Object.entries(statusMap).map(([s, v]) => ({ status: s, label: v.text }))"
        row-key="id"
      />
      <TimelinePlaceholder v-else-if="viewMode === 'timeline'" />
      <CalendarPlaceholder v-else-if="viewMode === 'calendar'" />
      <el-table v-else :data="tableData" v-loading="loading" empty-text="暂无数据" stripe border style="width: 100%">
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
        <el-table-column label="供应商" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.supplierName || row.supplier?.name || row.supplierId || '-' }}</template>
        </el-table-column>
        <el-table-column prop="purchaseType" label="类型" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.purchaseType === 'URGENT' ? 'danger' : ''">
              {{ row.purchaseType === 'DIRECT' ? '直接' : row.purchaseType === 'URGENT' ? '紧急' : '统一' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="orderDate" label="下单日期" width="120" />
        <!--
          RBAC defense-in-depth (PR #415 Option B 2026-05-12 + P3 column-hide fix):
          backend PriceFieldResponseAdvice strips totalAmount → null for roles
          lacking procurement:price:view. v-if on the column itself hides BOTH
          the header and cells for non-whitelisted roles (warehouse_manager etc).
          Without v-if on <el-table-column>, Element Plus still renders the
          column header even when every cell template returns nothing → E2E
          flagged 总金额 header visible despite null cells (misleading UX).
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
          EntityType=PURCHASE_ORDER 由 useLinkChipCounts composable 锁定.
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
                    <div><b>合同</b>: 采购合同 / 法律文件 (CONTRACT)</div>
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
        <el-table-column label="操作" width="260" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="goDetail(row.id)">详情</el-button>
            <!-- P0 (六扇门 May 7 transcript): 下载 PDF 供货单 (含 Code128 + QR 条码) -->
            <el-button type="info" link size="small" :icon="Download"
              :loading="pdfDownloadingIds.has(String(row.id))"
              @click="handleDownloadPdf(row)">对外供货单</el-button>
            <el-button v-if="row.status === 'DRAFT' && canWrite" type="warning" link size="small" @click="handleAction(row.id, 'submit')">提交</el-button>
            <el-button v-if="row.status === 'SUBMITTED' && canWrite" type="success" link size="small" @click="handleAction(row.id, 'approve')">审批</el-button>
            <el-button v-if="['DRAFT','SUBMITTED'].includes(row.status) && canWrite" type="danger" link size="small" @click="handleAction(row.id, 'cancel')">取消</el-button>
            <!-- U-ICON-1 (Sprint 4 Wave 2 Chat L) inline 7-icon hover toolbar -->
            <InlineRowIcons
              :row-actions="rowActionsFor(row)"
              entity-type="purchaseOrder"
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
        @ai-analyze="() => ElMessage.info({ message: `AI 分析 (待接 SmartBI): 分析当前采购订单${formatSummaryForAI(footerSummary, { filter: { status: statusFilter } })}`, duration: 8000, showClose: true })"
      />

      <div class="pagination-wrapper">
        <el-pagination v-model:current-page="pagination.page" v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50]" :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange" @size-change="handleSizeChange" />
      </div>
    </el-card>

    <!-- May 7 2026 用户反馈: "新建采购订单需要把页面放大,要不然很多明细的话是很小的".
         改全屏 dialog (full-screen modal),明细行有充足空间. -->
    <el-dialog v-model="dialogVisible" :title="`新建${label('purchaseOrder')}`" fullscreen destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-form-item :label="label('supplier')" required>
          <el-select v-model="form.supplierId" placeholder="请选择" filterable style="width: 100%">
            <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
          <UpstreamMissingHint
            v-if="suppliers.length === 0"
            description="本工厂暂无供应商，无法下采购单"
            target-module="procurement"
            require-write
            action-text="去创建供应商"
            contact-text="请联系采购或管理员先创建供应商"
            @action="goCreate('/procurement/suppliers')"
          />
        </el-form-item>
        <el-form-item label="采购类型">
          <el-radio-group v-model="form.purchaseType">
            <el-radio value="DIRECT">直接采购</el-radio>
            <el-radio value="HQ_UNIFIED">总部统采</el-radio>
            <el-radio value="URGENT">紧急采购</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="关联销售订单">
          <el-select v-model="form.relatedSalesOrderId" placeholder="可选 - 选择关联的销售订单" filterable clearable style="width: 100%">
            <el-option
              v-for="so in salesOrders"
              :key="so.id"
              :label="`${so.orderNumber} - ${so.customerName || so.customer?.name || ''}`"
              :value="so.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="期望交货">
          <el-date-picker v-model="form.expectedDeliveryDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="合同号">
          <el-input
            v-model="form.contractNumber"
            placeholder="选填 — 纸质合同号或框架合同编号，如 HT-2026-001"
            :maxlength="100"
            clearable
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="结算方式">
          <el-select v-model="form.settlementType" placeholder="选填 — 选择结算方式" clearable style="width: 100%">
            <el-option label="预付" value="PREPAID" />
            <el-option label="赊销先入库" value="CREDIT_FIRST" />
            <el-option label="未到票" value="NO_INVOICE" />
            <el-option label="月结" value="MONTHLY" />
            <el-option label="账期" value="CREDIT_PERIOD" />
            <el-option label="现结" value="IMMEDIATE" />
          </el-select>
        </el-form-item>
        <el-form-item label="开票提醒天数">
          <el-input-number
            v-model="form.invoiceReminderDays"
            :min="0"
            :max="365"
            placeholder="选填 — 收货后 N 天未收票则提醒"
            style="width: 200px"
          />
          <span style="margin-left: 8px; color: #909399; font-size: 13px">天（0 = 不提醒，空 = 使用工厂默认）</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
        <CanvasDynamicFields v-model="form.customFields" module-code="purchase_order" />
        <el-divider>{{ label('rawMaterial') }}明细</el-divider>
        <div class="item-row item-header">
          <!-- May 7 2026 用户反馈: 数量/单价/箱数 input-number 控件 -/+ 占两端,
               value 字段被挤压看不到数字. fullscreen dialog 1200px+ 有充足空间,
               把所有列加宽确保 3 位以上数字 + 小数点 + -/+ 控件都能完整显示. -->
          <span style="width: 220px"><span class="req-star">*</span>原料名称</span>
          <span style="width: 140px">规格</span>
          <span style="width: 140px"><span class="req-star">*</span>数量</span>
          <span style="width: 130px"><span class="req-star">*</span>单位</span>
          <span style="width: 160px">单价</span>
          <span style="width: 130px">税率</span>
          <span style="width: 140px">箱数</span>
          <span style="width: 70px">操作</span>
        </div>
        <div v-for="(item, idx) in form.items" :key="idx" class="item-row">
          <el-select
            v-model="item.materialTypeId"
            placeholder="选择原料"
            filterable
            style="width: 220px"
            @change="onItemMaterialChange(item)"
          >
            <el-option v-for="m in materials" :key="m.id" :label="m.name" :value="m.id" />
          </el-select>
          <el-input v-model="item.specification" placeholder="规格" style="width: 140px" @change="recalcBoxQuantity(item)" />
          <el-input-number v-model="item.quantity" :min="1" placeholder="数量" style="width: 140px" @change="recalcBoxQuantity(item)" />
          <!-- 单位下拉: 选定原料后显示该原料的 1/2/3 级单位; 未选时退化空选项 -->
          <!-- M-2 reviewer fix: @change 触发箱数重算, 用户切单位时同步更新 boxQuantity -->
          <el-select
            v-model="item.unit"
            placeholder="单位"
            :disabled="!item.materialTypeId"
            style="width: 130px"
            filterable
            allow-create
            default-first-option
            @change="recalcBoxQuantity(item)"
          >
            <el-option
              v-for="opt in getUnitOptionsForItem(item)"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <el-input-number v-model="item.unitPrice" :min="0" :precision="2" placeholder="单价" style="width: 160px" />
          <div style="width: 130px">
            <el-select
              v-model="item.taxRate"
              placeholder="未配置"
              clearable
              filterable
              allow-create
              default-first-option
              style="width: 100%"
            >
              <el-option
                v-for="opt in commonTaxRateOptions"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
            <div v-if="validateTaxRate(item.taxRate)" class="field-error">
              {{ validateTaxRate(item.taxRate) }}
            </div>
          </div>
          <!-- P1-2/3 R2 fix: el-tag 替换 inline-styled div, 跟随 Element Plus 主题 + 暗模式 -->
          <el-tag v-if="isAbacaItem(item)" type="warning" effect="light" size="default" style="width: 140px; text-align: center;">抄码品</el-tag>
          <el-input-number v-else v-model="item.boxQuantity" :min="0" :precision="2" placeholder="箱" style="width: 140px" />
          <el-button type="danger" link @click="removeItem(idx)" :disabled="form.items.length <= 1" style="width: 70px">删除</el-button>
        </div>
        <el-button style="width: 100%; margin-top: 8px" @click="addItem">+ 添加行</el-button>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- AI 对话录入 -->
    <AiEntryDrawer
      v-model="aiEntryVisible"
      :config="PURCHASE_ORDER_CONFIG"
      @fill-form="handleAiFill"
    />

    <!-- U-NEW-1 — create-mode selector + 4 mode dialogs.
         普通 + 二维 = Sprint 4 W2 Chat L. 一维 + BOM = P1 #58. -->
    <CreateModeSelector
      v-model="createModeSelectorVisible"
      :entity-label="label('purchaseOrder')"
      @mode-selected="handleCreateModeSelected"
    />
    <BatchCreateDialog
      v-model="batchCreateVisible"
      :title="`批量新建 ${label('purchaseOrder')}`"
      :columns="[
        { prop: 'supplierId', label: '供应商', required: true, slotName: 'supplier' },
        { prop: 'purchaseType', label: '类型', width: 130, slotName: 'type' },
        { prop: 'expectedDate', label: '期望交货日', width: 160, slotName: 'date' },
        { prop: 'remark', label: '备注' },
      ]"
      :row-factory="batchPurchaseFactory"
      :submit="submitBatchPurchaseOrders"
    >
      <template #supplier="{ row }">
        <el-select v-model="row.supplierId" filterable size="small" placeholder="选择供应商" style="width: 100%">
          <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
        </el-select>
      </template>
      <template #type="{ row }">
        <el-select v-model="row.purchaseType" size="small" style="width: 100%">
          <el-option label="统一" value="NORMAL" />
          <el-option label="直接" value="DIRECT" />
          <el-option label="紧急" value="URGENT" />
        </el-select>
      </template>
      <template #date="{ row }">
        <el-date-picker v-model="row.expectedDate" type="date" size="small" value-format="YYYY-MM-DD" style="width: 100%" />
      </template>
    </BatchCreateDialog>

    <!-- P1 #58 — Quick (一维): supplier + type + expectedDate + remark -->
    <QuickCreateDialog
      v-model="quickCreateVisible"
      :title="`快速新建 ${label('purchaseOrder')}`"
      :context-hint="`供应商范围: ${suppliers.length} 个可选 — 回车连续录入`"
      :fields="[
        { prop: 'supplierId', label: '供应商', required: true, slotName: 'supplier' },
        { prop: 'purchaseType', label: '采购类型', slotName: 'type' },
        { prop: 'expectedDate', label: '期望交货日', slotName: 'date' },
        { prop: 'remark', label: '备注', placeholder: '可选, 简短备注' },
      ]"
      :row-factory="quickPurchaseOrderFactory"
      :submit="submitQuickPurchaseOrder"
      :session-max="20"
    >
      <template #supplier="{ row }">
        <el-select v-model="row.supplierId" filterable placeholder="选择供应商" style="width: 100%">
          <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
        </el-select>
      </template>
      <template #type="{ row }">
        <el-select v-model="row.purchaseType" style="width: 100%">
          <el-option label="统一采购" value="NORMAL" />
          <el-option label="直接采购" value="DIRECT" />
          <el-option label="紧急采购" value="URGENT" />
        </el-select>
      </template>
      <template #date="{ row }">
        <el-date-picker v-model="row.expectedDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
      </template>
    </QuickCreateDialog>

    <!-- P1 #58 — BOM 展开: parent PO + child material items -->
    <BomExpansionDialog
      v-model="bomCreateVisible"
      :title="`BOM 展开新建 ${label('purchaseOrder')}`"
      :entity-label="label('purchaseOrder')"
      :templates="bomPurchaseTemplates"
      :parent-factory="bomPurchaseOrderParentFactory"
      :expand-template="expandBomPurchaseTemplate"
      :submit="submitBomPurchaseOrder"
      :child-columns="[
        { prop: 'materialName', label: '物料名称', width: 200 },
        { prop: 'quantity', label: '数量', width: 120, slotName: 'quantity' },
        { prop: 'unit', label: '单位', width: 100 },
        { prop: 'unitPrice', label: '单价', width: 140, slotName: 'price' },
        { prop: 'taxRate', label: '税率', width: 140, slotName: 'taxRate' },
      ]"
      :max-children="50"
    >
      <template #parent-fields="{ parent }">
        <el-form label-position="top">
          <el-form-item label="供应商" required>
            <el-select v-model="parent.supplierId" filterable placeholder="选择供应商" style="width: 100%">
              <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="采购类型">
            <el-select v-model="parent.purchaseType" style="width: 100%">
              <el-option label="统一采购" value="NORMAL" />
              <el-option label="直接采购" value="DIRECT" />
              <el-option label="紧急采购" value="URGENT" />
            </el-select>
          </el-form-item>
          <el-form-item label="期望交货日">
            <el-date-picker v-model="parent.expectedDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
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
      <template #taxRate="{ row }">
        <el-select
          v-model="row.taxRate"
          placeholder="未配置"
          clearable
          filterable
          allow-create
          default-first-option
          size="small"
          style="width: 100%"
        >
          <el-option
            v-for="opt in commonTaxRateOptions"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <div v-if="validateTaxRate(row.taxRate)" class="field-error">
          {{ validateTaxRate(row.taxRate) }}
        </div>
      </template>
    </BomExpansionDialog>

    <!-- PR #865: per-row operation log timeline (replaces the disabled "审计" chip). -->
    <AuditLogDrawer
      v-model:visible="auditDrawerVisible"
      entity-type="PurchaseOrder"
      entity-type-label="采购单"
      :entity-id="auditEntityId"
      :entity-label="auditEntityLabel"
    />

    <!-- PR #872 (#860 follow-up) — 转发分享链接 dialog (replaces (开发中) chip). -->
    <ForwardShareDialog
      v-model:visible="forwardDialogVisible"
      :factory-id="factoryId"
      entity-type="PurchaseOrder"
      entity-type-label="采购单"
      :entity-id="forwardEntityId"
      :entity-label="forwardEntityLabel"
    />

    <!-- PR #866 (#860 follow-up) — 退货 dialog. Wires existing ReturnOrderController. -->
    <CreateReturnOrderDialog
      v-if="returnDialogRow"
      v-model="returnDialogVisible"
      :factory-id="factoryId"
      return-type="PURCHASE_RETURN"
      :source-order-id="String(returnDialogRow.id)"
      :source-order-number="String(returnDialogRow.orderNumber || returnDialogRow.id)"
      :counterparty-id="String(returnDialogRow.supplierId || '')"
      :counterparty-name="String(returnDialogRow.supplierName || (returnDialogRow.supplier as TableRow)?.name || returnDialogRow.supplierId || '-')"
      :items="returnDialogItems"
      @success="handleReturnSuccess"
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
.item-header { font-size: 13px; font-weight: 600; color: #606266; margin-bottom: 4px;
  span { text-align: center; display: inline-block; }
}
.field-error { color: #f56c6c; font-size: 12px; line-height: 16px; margin-top: 2px; }
.req-star { color: #f56c6c; margin-right: 2px; }
</style>
