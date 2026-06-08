<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Search, Refresh, VideoPlay, VideoPause, CircleCheck, CircleClose, Download, Upload, ChatDotRound } from '@element-plus/icons-vue';
import { formatDateTimeCell } from '@/utils/tableFormatters';
import { handleCatchError } from '@/utils/errorToast';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import {
  downloadImportTemplate,
  importProductionPlans,
  exportProductionPlans,
  getSupervisors,
} from '@/api/productionPlan';
import { getProductWorkProcesses } from '@/api/processProduction';
import type { ProductWorkProcessItem } from '@/api/processProduction';
import { copyProductionPlan } from '@/api/orderCopy';
import CanvasDynamicFields from '@/components/canvas/CanvasDynamicFields.vue';
import CanvasAwareWrapper from '@/components/canvas/CanvasAwareWrapper.vue';
import AiEntryDrawer from '@/components/ai-entry/AiEntryDrawer.vue';
import { PRODUCTION_PLAN_CONFIG } from '@/components/ai-entry/types';
import { WorkflowBar } from '@/components/workflow';
import { useWorkflowStats } from '@/composables/useWorkflowStats';
import { getBucketPrimaryStatus, getBucketLabel } from '@/types/workflow';
import type { TableRow } from '@/types/api';
import { RowActionMenu, TableFooter } from '@/components/list';
import { computeRowActions } from '@/composables/useRowActions';
import { useListSummary } from '@/composables/useListSummary';
import { formatSummaryForAI } from '@/utils/aiSummaryContext';
import type { ListSummaryRequest } from '@/types/listSummary';
import { safePrint } from '@/api/printApi';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('production'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

function rowActionsFor(row: TableRow) {
  // #751: 删除 dropdown 中的 'view-detail' (页面已有独立"查看" button, 避免 3 button 跳同页)
  const all = computeRowActions(
    'productionPlan',
    { status: String(row.status || ''), id: String(row.id || '') },
    { canViewPrice: canViewPrice.value }
  );
  return all.filter((a) => a.id !== 'view-detail');
}
function handleRowActionClick(actionId: string, row: TableRow) {
  switch (actionId) {
    case 'view-detail': handleViewPlan(row); break;
    case 'cancel': handleCancel(row); break;
    case 'print-pdf': void safePrint('production-task', factoryId.value, String(row.id), { fileName: `生产计划_${row.planNumber || row.id}` }); break;
    case 'copy': void handleCopyPlan(row); break;
    case 'lock':
      // #747: 锁定动作目前后端 API 未实装, 给出明确提示而非静默 info
      ElMessageBox.alert(
        '锁定后该生产计划将不再允许修改数量/日期，进入排产保护阶段（避免生产中误改）。\n\n后端 API 正在对接中，暂时不可用。',
        '锁定生产计划',
        { confirmButtonText: '我知道了' }
      ).catch(() => { /* dismiss */ });
      break;
    default: ElMessage.info(`Action: ${actionId}`);
  }
}
function openAiForRow(row: TableRow) {
  console.info('[RowAction AI]', { entityType: 'productionPlan', entityId: row.id, planNumber: row.planNumber });
  aiEntryVisible.value = true;
}

// U-NAV-1 业务流程图导航 (Sprint 2 Track G + FU Chat 3 bucket-filter)
const { stats: workflowStats, loading: workflowLoading } = useWorkflowStats(factoryId, 'production');
function handleWorkflowNodeClick(nodeId: string) {
  const primary = getBucketPrimaryStatus('production', nodeId);
  if (!primary) return;
  searchForm.value.status = primary;
  pagination.value.page = 1;
  loadData();
  ElMessage.success(`已切到 "${getBucketLabel('production', nodeId)}" (显示状态: ${primary}). bucket 含多个状态, 想看其他请打开状态下拉切换.`);
}

const loading = ref(false);
const actionLoading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const searchForm = ref({
  keyword: '',
  status: ''
});

// U-FOOTER-1
const summaryRequest = computed<ListSummaryRequest>(() => ({
  filterConditions: searchForm.value.status ? { status: searchForm.value.status } : {},
}));
const { summary: footerSummary, loading: footerLoading } = useListSummary('productionPlan', summaryRequest);

// 新建计划对话框
const dialogVisible = ref(false);
const dialogLoading = ref(false);
const planForm = ref({
  productTypeId: '',
  plannedQuantity: 0,
  plannedDate: '',
  notes: '',
  estimatedWorkers: undefined as number | undefined,
  assignedSupervisorId: '' as string | undefined,
  sourceCustomerName: '',
  processName: '',
  batchDate: '',
  // T135 ITEM #1: 默认「销售订单」(CUSTOMER_ORDER); handleCreate() 重置时也遵循此默认
  sourceType: 'CUSTOMER_ORDER' as 'MANUAL' | 'CUSTOMER_ORDER' | 'AI_FORECAST' | 'SAFETY_STOCK',
  sourceOrderId: '' as string | undefined,
  sourceOrderItemId: '' as string | undefined,
  customFields: {} as TableRow,
});
const productTypes = ref<TableRow[]>([]);
const bomProcesses = ref<string[]>([]);
// A3: full work-process objects for read-only display (ordered by processOrder)
const productWorkProcessList = ref<TableRow[]>([]);
const customers = ref<TableRow[]>([]);

// A5: today helper
function todayStr(): string {
  return new Date().toISOString().slice(0, 10);
}

// T135: tomorrow helper (计划生产日默认 = 今天 + 1)
function tomorrowStr(): string {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  return d.toISOString().slice(0, 10);
}
const selectableSalesOrders = ref<TableRow[]>([]);
const salesOrdersLoading = ref(false);

async function loadSelectableSalesOrders() {
  if (!factoryId.value) return;
  salesOrdersLoading.value = true;
  try {
    const res = await get(`/${factoryId.value}/production-plans/sales-orders/selectable`);
    if (res.success && Array.isArray(res.data)) {
      selectableSalesOrders.value = res.data;
    } else if (res.success === false) {
      ElMessage.error(res.message || '加载销售订单失败');
    }
  } catch (e) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '加载销售订单失败');
  } finally {
    salesOrdersLoading.value = false;
  }
}

function handleSourceTypeChange(val: string) {
  if (val === 'CUSTOMER_ORDER') {
    if (selectableSalesOrders.value.length === 0) loadSelectableSalesOrders();
  } else {
    planForm.value.sourceOrderId = '';
    planForm.value.sourceOrderItemId = '';
  }
}

// P0-12: 当前选中订单的可选产品行
const selectedOrderItems = computed<TableRow[]>(() => {
  const oid = planForm.value.sourceOrderId;
  if (!oid) return [];
  const so = selectableSalesOrders.value.find((o) => String(o.id) === String(oid));
  return so && Array.isArray(so.items) ? (so.items as TableRow[]) : [];
});

function handleSalesOrderSelect(orderId: string) {
  const so = selectableSalesOrders.value.find((o) => String(o.id) === String(orderId));
  // 切换订单时清空已选行
  planForm.value.sourceOrderItemId = '';
  planForm.value.productTypeId = '';
  productWorkProcessList.value = [];
  if (so) {
    planForm.value.sourceCustomerName = String(so.customerName || '');
    // A4: 若订单只有一个产品行，自动选中
    const items = Array.isArray(so.items) ? (so.items as TableRow[]) : [];
    if (items.length === 1) {
      planForm.value.sourceOrderItemId = String(items[0].id);
      handleSalesOrderItemSelect(String(items[0].id));
    }
  }
}

// P0-12: 选中销售订单行后,自动回填产品/客户
function handleSalesOrderItemSelect(itemId: string) {
  const item = selectedOrderItems.value.find((it) => String(it.id) === String(itemId));
  if (!item) return;
  if (item.productTypeId) {
    planForm.value.productTypeId = String(item.productTypeId);
    handleProductChange(planForm.value.productTypeId);
  }
  // 客户名已在选订单时回填,这里再补一次以防订单未选时直接选行
  const so = selectableSalesOrders.value.find((o) => String(o.id) === String(planForm.value.sourceOrderId));
  if (so && so.customerName) {
    planForm.value.sourceCustomerName = String(so.customerName);
  }
}

// Import/Export & reference data
const supervisors = ref<TableRow[]>([]);

// AI Entry Drawer
const aiEntryVisible = ref(false);

onMounted(() => {
  loadData();
  loadProductTypes();
  loadReferenceData();
  loadCustomers();
});

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/production-plans`, {
      params: {
        page: pagination.value.page,
        size: pagination.value.size,
        keyword: searchForm.value.keyword || undefined,
        status: searchForm.value.status || undefined
      }
    });
    if (response.success && response.data) {
      tableData.value = response.data.content || [];
      pagination.value.total = response.data.totalElements || 0;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载生产计划失败');
    }
  } catch (error: any) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

async function loadProductTypes() {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/product-types`);
    if (response.success && response.data) {
      productTypes.value = response.data.content || response.data || [];
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载产品类型失败');
    }
  } catch (error: any) {
    console.error('加载产品类型失败:', error);
    if (!error?.actionHint) ElMessage.error('加载产品类型失败');
  }
}

async function loadCustomers() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/customers`, { params: { size: 200 } });
    if (res.success && res.data) {
      customers.value = Array.isArray(res.data) ? res.data : res.data.content || [];
    }
  } catch { /* optional, ignore */ }
}

async function loadBomProcesses(productTypeId: string) {
  if (!factoryId.value || !productTypeId) {
    bomProcesses.value = [];
    productWorkProcessList.value = [];
    return;
  }
  try {
    // B1 fix (2026-05-10): 工序下拉应读"产品工序配置"(ProductWorkProcess),
    // 不是 LaborCostConfig (人工成本). 后端按 processOrder asc 返回.
    // Ref: docs/qa-audits/2026-05-10-customer-meeting-9bug-audit.md §B1
    // A3: also populate productWorkProcessList for read-only display.
    const res = await getProductWorkProcesses(factoryId.value, productTypeId);
    if (res.success && res.data && Array.isArray(res.data)) {
      productWorkProcessList.value = res.data as TableRow[];
      const names = res.data.map((item: TableRow) => String(item.processName || '')).filter(Boolean);
      bomProcesses.value = [...new Set(names)];
      // T135 ITEM #4 (BLOCKING): wire all process names into planForm.processName so the
      // CUSTOMER_ORDER backend validation (processName required) passes.
      // Backend checks: request.getProcessName() != null && !isBlank().
      planForm.value.processName = names.join('、');
    } else {
      bomProcesses.value = [];
      productWorkProcessList.value = [];
      planForm.value.processName = '';
    }
  } catch {
    bomProcesses.value = [];
    productWorkProcessList.value = [];
    planForm.value.processName = '';
  }
}

function handleProductChange(productTypeId: string) {
  if (!productTypeId) return;
  const product = productTypes.value.find((p: TableRow) => p.id === productTypeId);
  if (product) {
    // Auto-fill customer name from product's relatedCustomer or customerId
    if (product.relatedCustomer) {
      planForm.value.sourceCustomerName = String(product.relatedCustomer);
    } else if (product.customerId) {
      const customer = customers.value.find((c: TableRow) => c.id === product.customerId);
      if (customer) {
        planForm.value.sourceCustomerName = String(customer.name || customer.companyName || '');
      }
    }
  }
  // Load BOM processes for the selected product
  loadBomProcesses(productTypeId);
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

function handleCreate() {
  const today = todayStr();
  planForm.value = {
    productTypeId: '',
    plannedQuantity: 0,
    // T135 ITEM #3: 计划生产日默认 = 今天 + 1 天
    plannedDate: tomorrowStr(),
    notes: '',
    estimatedWorkers: undefined,
    assignedSupervisorId: '',
    sourceCustomerName: '',
    processName: '',
    batchDate: today,
    // T135 ITEM #1: 来源类型默认「销售订单」
    sourceType: 'CUSTOMER_ORDER',
    sourceOrderId: '',
    sourceOrderItemId: '',
    customFields: {} as TableRow,
  };
  productWorkProcessList.value = [];
  // T135 ITEM #1: 默认 CUSTOMER_ORDER — 预加载可选销售订单列表
  if (selectableSalesOrders.value.length === 0) loadSelectableSalesOrders();
  dialogVisible.value = true;
}

async function submitPlan() {
  if (!planForm.value.productTypeId || !planForm.value.plannedQuantity || !planForm.value.plannedDate) {
    ElMessage.warning('请填写完整信息');
    return;
  }
  if (planForm.value.sourceType === 'CUSTOMER_ORDER' && !planForm.value.sourceOrderItemId) {
    ElMessage.warning('选择"销售订单"来源时必须选择关联的销售订单产品行');
    return;
  }

  if (!factoryId.value) return;
  dialogLoading.value = true;
  try {
    const response = await post(`/${factoryId.value}/production-plans`, planForm.value);
    if (response.success) {
      ElMessage.success('创建成功');
      dialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(response.message || '创建失败');
    }
  } catch (error: any) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', error);
  } finally {
    dialogLoading.value = false;
  }
}

async function handleStart(row: TableRow) {
  if (actionLoading.value) return;
  try {
    await ElMessageBox.confirm('确定开始此生产计划?', '提示', { type: 'warning' });
    actionLoading.value = true;
    const response = await post(`/${factoryId.value}/production-plans/${row.id}/start`);
    if (response.success) {
      ElMessage.success('已开始生产');
      loadData();
    } else {
      ElMessage.error(response.message || '操作失败');
    }
  } catch (error: any) {
    // Interceptor already shows specific sticky toast for ApiError (request.ts).
    // Retained catch to prevent uncaught; log for debug.
    if (error !== 'cancel') console.error('[提交失败]', error);
  } finally {
    actionLoading.value = false;
  }
}

// ==================== 完成生产 dialog (#742) ====================
// 替代纯 prompt - 显示品名/计划数量,并 enforce 实际产量 ≤ 计划数量上限
const completeDialogVisible = ref(false);
const completeRow = ref<TableRow | null>(null);
const completeForm = ref({ actualQuantity: 0 });
const completeProductName = computed(() => {
  const r = completeRow.value;
  if (!r) return '';
  return String(r.productTypeName || r.productName || r.productTypeId || '');
});
const completePlannedQuantity = computed(() => {
  const r = completeRow.value;
  if (!r) return 0;
  return Number(r.plannedQuantity || 0);
});

function handleComplete(row: TableRow) {
  if (actionLoading.value) return;
  completeRow.value = row;
  // 默认填充计划数量, 便于一键提交; 用户可改
  completeForm.value = { actualQuantity: Number(row.plannedQuantity || 0) };
  completeDialogVisible.value = true;
}

async function submitComplete() {
  if (!completeRow.value) return;
  const planned = completePlannedQuantity.value;
  const actual = Number(completeForm.value.actualQuantity || 0);
  if (!actual || actual <= 0) {
    ElMessage.warning('请输入有效的实际产量');
    return;
  }
  if (planned > 0 && actual > planned) {
    ElMessage.warning(`实际产量不能超过计划数量 ${planned}`);
    return;
  }
  actionLoading.value = true;
  try {
    const response = await post(`/${factoryId.value}/production-plans/${completeRow.value.id}/complete`, {
      actualQuantity: actual
    });
    if (response.success) {
      ElMessage.success('生产已完成');
      completeDialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(response.message || '操作失败');
    }
  } catch (error: any) {
    if (error !== 'cancel') console.error('[提交失败]', error);
  } finally {
    actionLoading.value = false;
  }
}

// ==================== 取消原因 dialog (#743) ====================
// 快捷下拉 + 自定义补充, 替代纯 textarea
const CANCEL_REASON_OPTIONS = [
  { value: '客户撤单', label: '客户撤单' },
  { value: '原料缺货', label: '原料缺货' },
  { value: '质量问题', label: '质量问题' },
  { value: '排程冲突', label: '排程冲突' },
  { value: '其他', label: '其他（请补充说明）' },
];
const cancelDialogVisible = ref(false);
const cancelRow = ref<TableRow | null>(null);
const cancelForm = ref({ reasonOption: '', otherReason: '' });
const cancelProductName = computed(() => {
  const r = cancelRow.value;
  if (!r) return '';
  return String(r.productTypeName || r.productName || r.productTypeId || '');
});

function handleCancel(row: TableRow) {
  if (actionLoading.value) return;
  cancelRow.value = row;
  cancelForm.value = { reasonOption: '', otherReason: '' };
  cancelDialogVisible.value = true;
}

async function submitCancel() {
  if (!cancelRow.value) return;
  const opt = cancelForm.value.reasonOption;
  if (!opt) {
    ElMessage.warning('请选择取消原因');
    return;
  }
  let reason = opt;
  if (opt === '其他') {
    const other = (cancelForm.value.otherReason || '').trim();
    if (!other) {
      ElMessage.warning('请补充取消原因');
      return;
    }
    reason = `其他: ${other}`;
  }
  actionLoading.value = true;
  try {
    const response = await post(`/${factoryId.value}/production-plans/${cancelRow.value.id}/cancel?reason=${encodeURIComponent(reason)}`);
    if (response.success) {
      ElMessage.success('计划已取消');
      cancelDialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(response.message || '操作失败');
    }
  } catch (error: any) {
    if (error !== 'cancel') console.error('[提交失败]', error);
  } finally {
    actionLoading.value = false;
  }
}

// #860 follow-up (2026-05-18): 复制生产计划 → 新草稿 (status=PENDING).
// 后端复用产品/数量/日期/成本预估, 不复制实际值/审批/锁定状态.
// 错误由 axios interceptor sticky toast 显示, 不要 try/catch 吞.
async function handleCopyPlan(row: TableRow): Promise<void> {
  const planNumber = String(row.planNumber || row.id || '');
  try {
    await ElMessageBox.confirm(
      `确认复制生产计划 ${planNumber} 为新草稿？复制内容包含产品、数量、日期和成本预估，不复制实际值/审批/锁定状态。`,
      '复制生产计划',
      { confirmButtonText: '复制', cancelButtonText: '取消', type: 'info' }
    );
  } catch {
    return; // 用户取消
  }
  const res = await copyProductionPlan(factoryId.value, String(row.id));
  if (res?.success && res.data) {
    ElMessage.success(`已复制为 ${res.data.planNumber}`);
    await loadData();
  }
}

async function handleCreateBatch(row: TableRow) {
  if (actionLoading.value) return;
  try {
    // #748: 加流程决策提示 (基于 May10 六扇门会议确认)
    await ElMessageBox.confirm(
      `确定将计划 "${row.planNumber}" 转为生产批次？\n\n` +
      `转换后将自动创建批次并开始生产流程。\n\n` +
 `️ 流程提示：\n` +
      `• 如果仓库尚未收到所需原料 → 请先点 "生成调拨单"，等仓库审批/出库后再转批次。\n` +
      `• 如果原料已就位 → 直接转批次即可。\n` +
      `• 转批次 = 开始生产；之后在 APP 报工审批，或在 PC 端"完成"录入实际产量。`,
      '转为批次',
      { type: 'warning', confirmButtonText: '确认转换', cancelButtonText: '取消' }
    );
    actionLoading.value = true;
    const response = await post(`/${factoryId.value}/production-plans/${row.id}/create-batch`);
    if (response.success) {
      const batch = response.data;
      ElMessage.success(`批次创建成功！批次号: ${batch?.batchNumber || ''}`);
      loadData();
    } else {
      ElMessage.error(response.message || '转换失败');
    }
  } catch (error: any) {
    // Interceptor already shows specific sticky toast for ApiError (request.ts).
    // Retained catch to prevent uncaught; log for debug.
    if (error !== 'cancel') console.error('[提交失败]', error);
  } finally {
    actionLoading.value = false;
  }
}

async function handleGenerateTransfer(row: TableRow) {
  if (actionLoading.value) return;
  try {
    await ElMessageBox.confirm(
      `确定为计划 "${row.planNumber}" 生成调拨单？\n\n将根据 BOM 配方自动计算所需原辅料及包材，生成调拨申请发送给仓库。`,
      '生成调拨单',
      { type: 'info', confirmButtonText: '生成', cancelButtonText: '取消' }
    );
    actionLoading.value = true;
    const response = await post(`/${factoryId.value}/production-plans/${row.id}/generate-transfer`);
    if (response.success) {
      const count = response.data?.items?.length || 0;
      ElMessage.success(`调拨单已生成，共 ${count} 项物料，等待仓库审批`);
      loadData();
    } else {
      const msg = response.message || '生成失败';
      // If the error is about missing BOM, offer to navigate to BOM config
      if (msg.includes('BOM') || msg.includes('bom') || msg.includes('配方')) {
        ElMessageBox.confirm(
          `${msg}\n\n是否前往配置该产品的BOM配方？`,
          '缺少BOM配置',
          { type: 'warning', confirmButtonText: '去配置BOM', cancelButtonText: '取消' }
        ).then(() => {
          router.push('/production/bom');
        }).catch(() => { /* user cancelled */ });
      } else {
        ElMessage.error(msg);
      }
    }
  } catch (error: any) {
    // Interceptor already shows specific sticky toast for ApiError (request.ts).
    // Retained catch to prevent uncaught; log for debug.
    if (error !== 'cancel') console.error('[提交失败]', error);
  } finally {
    actionLoading.value = false;
  }
}

function isPendingStatus(status: string) {
  return status === 'PLANNED' || status === 'PENDING';
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    PLANNED: 'info',
    PENDING: 'info',
    PREPARED: 'info',  // M-PREP-1: 草稿态
    IN_PROGRESS: 'warning',
    COMPLETED: 'success',
    CANCELLED: 'danger',
    PAUSED: 'warning'
  };
  return map[status?.toUpperCase()] || 'info';
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    PLANNED: '待执行',
    PENDING: '待执行',
    PREPARED: '草稿',  // M-PREP-1: 草稿态
    IN_PROGRESS: '进行中',
    COMPLETED: '已完成',
    CANCELLED: '已取消',
    PAUSED: '暂停'
  };
  return map[status?.toUpperCase()] || status;
}

// ==================== View Plan ====================
const viewDialogVisible = ref(false);
const viewPlan = ref<TableRow | null>(null);

const viewProcessList = ref<ProductWorkProcessItem[]>([]);
const viewProcessLoading = ref(false);

async function handleViewPlan(row: TableRow) {
  viewPlan.value = row;
  viewDialogVisible.value = true;
  // Fetch 工序+负责人 for the plan's product type
  const ptId = row.productTypeId as string | undefined;
  if (ptId && factoryId.value) {
    viewProcessLoading.value = true;
    viewProcessList.value = [];
    try {
      const res = await getProductWorkProcesses(factoryId.value, ptId);
      if (res.success && Array.isArray(res.data)) {
        viewProcessList.value = res.data as ProductWorkProcessItem[];
      }
    } catch (e) {
      console.warn('[计划详情] 加载工序失败', e);
    } finally {
      viewProcessLoading.value = false;
    }
  } else {
    viewProcessList.value = [];
  }
}

// ==================== Reference Data ====================

async function loadReferenceData() {
  if (!factoryId.value) return;
  try {
    const supsRes = await getSupervisors(factoryId.value);
    if (supsRes?.data) {
      supervisors.value = Array.isArray(supsRes.data) ? supsRes.data : (supsRes.data as TableRow).content || [];
    } else if (supsRes && !supsRes.success) {
      ElMessage.error(supsRes.message || '加载主管数据失败');
    }
  } catch (e: any) {
    console.warn('Failed to load reference data:', e);
    if (!e?.actionHint) ElMessage.error('加载参考数据失败');
  }
}

// ==================== Import / Export ====================

async function handleDownloadTemplate() {
  if (!factoryId.value) return;
  try {
    const response = await downloadImportTemplate(factoryId.value);
    const blob = new Blob([response.data], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'production-plan-template.xlsx';
    a.click();
    URL.revokeObjectURL(url);
    ElMessage.success('模板下载成功');
  } catch (e: any) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', e);
  }
}

async function handleImportFile(uploadFile: { raw?: File }) {
  if (!uploadFile?.raw) return;

  const file = uploadFile.raw;
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning('文件大小不能超过10MB');
    return;
  }

  try {
    if (!factoryId.value) return;
    const formData = new FormData();
    formData.append('file', file);

    const res = await importProductionPlans(factoryId.value, formData);
    if (res?.data) {
      const r = res.data;
      const failureInfo = r.failureDetails?.length
        ? '\n\n失败详情:\n' + r.failureDetails.map((f) => `第${f.rowNumber}行: ${f.reason}`).join('\n')
        : '';
      ElMessageBox.alert(
        `总计: ${r.totalCount} 条\n成功: ${r.successCount} 条\n失败: ${r.failureCount} 条` + failureInfo,
        '导入结果',
        { confirmButtonText: '确定', callback: () => loadData() }
      );
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '请检查文件格式';
    if (!(e as { actionHint?: unknown })?.actionHint) ElMessage.error('导入失败: ' + msg);
  }
}

async function handleExport() {
  if (!factoryId.value) return;
  try {
    const params: Record<string, string> = {};
    if (searchForm.value.keyword) params.keyword = searchForm.value.keyword;
    if (searchForm.value.status) params.status = searchForm.value.status;

    const response = await exportProductionPlans(factoryId.value, params);
    const blob = new Blob([response.data], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `生产计划_${new Date().toISOString().slice(0, 10)}.xlsx`;
    a.click();
    URL.revokeObjectURL(url);
    ElMessage.success('导出成功');
  } catch (e: any) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', e);
  }
}

// ==================== A2: Dialog dirty-check close ====================

function isPlanFormDirty(): boolean {
  const f = planForm.value;
  return !!(
    f.productTypeId ||
    (f.plannedQuantity && f.plannedQuantity !== 0) ||
    f.notes ||
    f.sourceCustomerName ||
    f.processName ||
    f.sourceOrderId ||
    f.sourceOrderItemId
  );
}

async function handleDialogClose() {
  if (isPlanFormDirty()) {
    try {
      await ElMessageBox.confirm('有未保存内容，确定关闭？', '提示', {
        confirmButtonText: '确定关闭',
        cancelButtonText: '继续编辑',
        type: 'warning',
      });
    } catch {
      // 用户取消 → 不关闭
      return;
    }
  }
  dialogVisible.value = false;
}

// ==================== AI Entry ====================

function handleAiFill(params: TableRow) {
  // Match productTypeName to productTypeId
  const name = String(params.productTypeName || '');
  const matched = productTypes.value.find(
    (pt: TableRow) => String(pt.name || '').includes(name) || name.includes(String(pt.name || ''))
  );

  const today = todayStr();
  planForm.value = {
    productTypeId: matched ? String(matched.id) : '',
    plannedQuantity: Number(params.plannedQuantity || 0),
    plannedDate: String(params.plannedDate || today),
    notes: String(params.notes || ''),
    estimatedWorkers: undefined,
    assignedSupervisorId: '',
    sourceCustomerName: String(params.sourceCustomerName || ''),
    processName: String(params.processName || ''),
    batchDate: String(params.batchDate || today),
    sourceType: 'MANUAL',
    sourceOrderId: '',
    sourceOrderItemId: '',
    customFields: {} as TableRow,
  };
  dialogVisible.value = true;
}
</script>

<template>
  <CanvasAwareWrapper module-code="production_plan">
  <div class="page-wrapper">
    <!-- U-NAV-1 业务流程图导航 (Sprint 2 Track G) -->
    <WorkflowBar
      :nodes="workflowStats?.nodes ?? []"
      :loading="workflowLoading"
      title="生产工作流"
      :ai-trigger-enabled="true"
      @node-click="handleWorkflowNodeClick"
      @ai-trigger="aiEntryVisible = true"
    />
    <ConceptDisambiguationAlert
      here-name="生产计划"
      here="未来要做什么的「计划」（PENDING / 待开工状态，可调整数量、日期）"
      other-name="生产管理 → 生产批次"
      other="已开工的实际「批次」（IN_PROGRESS / COMPLETED，记录实际产量、消耗）"
      other-path="/production/batches"
      consequence="计划批准后才会转为批次"
    />
    <!-- #747 + #748: 生产/锁定/调拨 业务流程引导 banner (基于 May10 六扇门会议) -->
    <el-alert
      title="生产计划操作指引"
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 12px"
    >
      <template #default>
        <div style="font-size: 13px; line-height: 1.7;">
          <strong>计划确认后，根据 BOM 配方和库存情况选择以下路径之一：</strong>
          <ul style="margin: 4px 0 4px 18px; padding: 0;">
            <li><strong>生成调拨单</strong>：根据 BOM 自动计算所需原辅料/包材，发申请给仓库审批。库存不足或需要从其他仓库调料时使用。</li>
            <li><strong>转为批次</strong>：直接将计划转为生产批次并开启生产（前提：仓库已收到所需原料）。</li>
            <li><strong>开始</strong>：手动开启生产，与"转为批次"语义相近（建议系统会先校验库存是否足够）。</li>
          </ul>
          <strong>完成后</strong>：可通过 APP「报工审批」逐工序上报，或在 PC 端「完成生产」录入实际产量结束计划。
          <span style="color: var(--text-color-secondary, #909399);">
            进行中的计划支持"锁定"——锁定后该计划不再允许修改数量/日期，避免在生产过程中被误改。
          </span>
        </div>
      </template>
    </el-alert>
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">生产计划管理</span>
            <span class="data-count">共 {{ pagination.total }} 条记录</span>
          </div>
          <div class="header-right">
            <el-button type="success" :icon="Download" @click="handleDownloadTemplate">
              下载模板
            </el-button>
            <el-upload
              :auto-upload="false"
              :show-file-list="false"
              accept=".xlsx,.xls"
              :on-change="handleImportFile"
              style="display: inline-block; margin-left: 8px;"
            >
              <el-button type="warning" :icon="Upload">
                导入Excel
              </el-button>
            </el-upload>
            <el-button type="info" :icon="Download" @click="handleExport" style="margin-left: 8px;">
              导出Excel
            </el-button>
            <el-button v-if="canWrite" type="success" :icon="ChatDotRound" @click="aiEntryVisible = true" style="margin-left: 8px;">
              AI对话创建
            </el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleCreate" style="margin-left: 8px;">
              新建计划
            </el-button>
          </div>
        </div>
      </template>

      <div class="search-bar">
        <el-input
          v-model="searchForm.keyword"
          placeholder="搜索计划编号/产品名称"
          :prefix-icon="Search"
          clearable
          style="width: 280px"
          @keyup.enter="handleSearch"
        />
        <el-select v-model="searchForm.status" placeholder="全部状态" clearable style="width: 150px">
          <el-option label="草稿" value="PREPARED" />
          <el-option label="待执行" value="PLANNED" />
          <el-option label="待执行 (PENDING)" value="PENDING" />
          <el-option label="进行中" value="IN_PROGRESS" />
          <el-option label="暂停" value="PAUSED" />
          <el-option label="已完成" value="COMPLETED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
      </div>

      <el-table :data="tableData" v-loading="loading" empty-text="暂无数据" stripe border style="width: 100%">
        <el-table-column prop="planNumber" label="计划编号" width="160" />
        <el-table-column label="产品类型" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.productTypeName || row.productName || row.productTypeId || '-' }}</template>
        </el-table-column>
        <el-table-column prop="sourceCustomerName" label="客户" min-width="120" show-overflow-tooltip />
        <el-table-column prop="processName" label="工序" width="120" show-overflow-tooltip />
        <el-table-column prop="batchDate" label="批次日期" width="120" />
        <el-table-column prop="plannedQuantity" label="计划数量" width="100" align="right" />
        <el-table-column prop="actualQuantity" label="实际数量" width="100" align="right" />
        <el-table-column prop="plannedDate" label="计划日期" width="120" />
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="estimatedWorkers" label="预计工人" width="90" align="center" />
        <el-table-column prop="assignedSupervisorName" label="指派主管" width="100" show-overflow-tooltip />
        <el-table-column prop="sourceType" label="来源" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.sourceType === 'EXCEL_IMPORT'" type="warning" size="small">Excel导入</el-tag>
            <el-tag v-else-if="row.sourceType === 'AI_CHAT'" type="success" size="small">AI创建</el-tag>
            <el-tag v-else-if="row.sourceType === 'SAFETY_STOCK'" type="info" size="small">存货生产</el-tag>
            <el-tag v-else-if="row.sourceType === 'CUSTOMER_ORDER'" type="primary" size="small">销售订单</el-tag>
            <el-tag v-else-if="row.sourceType === 'AI_FORECAST'" type="success" size="small">AI预测</el-tag>
            <el-tag v-else size="small">手动</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180" :formatter="formatDateTimeCell" />
        <el-table-column label="操作" width="280" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleViewPlan(row)">查看</el-button>
            <el-button
              v-if="canWrite && isPendingStatus(row.status)"
              type="warning"
              link
              size="small"
              :disabled="actionLoading"
              @click="handleCreateBatch(row)"
            >转为批次</el-button>
            <el-button
              v-if="canWrite && isPendingStatus(row.status)"
              type="warning"
              link
              size="small"
              :disabled="actionLoading"
              @click="handleGenerateTransfer(row)"
            >生成调拨单</el-button>
            <el-button
              v-if="canWrite && isPendingStatus(row.status)"
              type="success"
              link
              size="small"
              :icon="VideoPlay"
              :disabled="actionLoading"
              @click="handleStart(row)"
            >开始</el-button>
            <el-button
              v-if="canWrite && row.status === 'IN_PROGRESS'"
              type="primary"
              link
              size="small"
              :icon="CircleCheck"
              :disabled="actionLoading"
              @click="handleComplete(row)"
            >完成</el-button>
            <el-button
              v-if="canWrite && (isPendingStatus(row.status) || row.status === 'IN_PROGRESS')"
              type="danger"
              link
              size="small"
              :icon="CircleClose"
              :disabled="actionLoading"
              @click="handleCancel(row)"
            >取消</el-button>
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
        @ai-analyze="() => ElMessage.info({ message: `AI 分析 (待接 SmartBI): 分析当前生产计划${formatSummaryForAI(footerSummary, { filter: { status: searchForm.status } })}`, duration: 8000, showClose: true })"
      />

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
    </el-card>

    <!-- 查看计划详情 -->
    <el-dialog v-model="viewDialogVisible" title="计划详情" width="680px" destroy-on-close>
      <div v-if="viewPlan" class="plan-detail">
        <!-- 基本信息 -->
        <div class="detail-section">
          <div class="detail-section-title">基本信息</div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="计划编号">{{ viewPlan.planNumber }}</el-descriptions-item>
            <el-descriptions-item label="产品类型">{{ viewPlan.productTypeName || viewPlan.productName || viewPlan.productTypeId || '-' }}</el-descriptions-item>
            <el-descriptions-item label="客户">{{ viewPlan.sourceCustomerName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="指派主管">{{ viewPlan.assignedSupervisorName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="计划数量">{{ viewPlan.plannedQuantity }}</el-descriptions-item>
            <el-descriptions-item label="实际数量">{{ viewPlan.actualQuantity || '-' }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="getStatusType(viewPlan.status as string)" size="small">{{ getStatusText(viewPlan.status as string) }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="来源">
              <el-tag v-if="viewPlan.sourceType === 'EXCEL_IMPORT'" type="warning" size="small">Excel导入</el-tag>
              <el-tag v-else-if="viewPlan.sourceType === 'AI_CHAT'" type="success" size="small">AI创建</el-tag>
              <el-tag v-else-if="viewPlan.sourceType === 'SAFETY_STOCK'" type="info" size="small">存货生产</el-tag>
              <el-tag v-else-if="viewPlan.sourceType === 'CUSTOMER_ORDER'" type="primary" size="small">销售订单</el-tag>
              <el-tag v-else-if="viewPlan.sourceType === 'AI_FORECAST'" type="success" size="small">AI预测</el-tag>
              <el-tag v-else size="small">手动</el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </div>

        <!-- 时间 -->
        <div class="detail-section">
          <div class="detail-section-title">时间</div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="计划日期">{{ viewPlan.plannedDate || '-' }}</el-descriptions-item>
            <el-descriptions-item label="批次日期">{{ viewPlan.batchDate || '-' }}</el-descriptions-item>
          </el-descriptions>
        </div>

        <!-- 工序 + 负责人 -->
        <div class="detail-section">
          <div class="detail-section-title">
            工序
            <span v-if="viewProcessLoading" class="detail-section-hint">加载中…</span>
            <span v-else-if="viewProcessList.length > 0" class="detail-section-hint">{{ viewProcessList.length }} 道</span>
          </div>
          <div v-if="viewProcessLoading" class="process-list-loading">
            <el-icon class="is-loading"><Refresh /></el-icon>
            <span>加载工序配置…</span>
          </div>
          <ol v-else-if="viewProcessList.length > 0" class="process-list">
            <li v-for="(wp, idx) in viewProcessList" :key="wp.id" class="process-item">
              <span class="process-order">{{ idx + 1 }}.</span>
              <span class="process-name">{{ wp.processName }}</span>
              <span class="process-assignee">
                <el-tag v-if="wp.responsibleWorkerName" type="primary" size="small" effect="plain">{{ wp.responsibleWorkerName }}</el-tag>
                <span v-else class="process-unassigned">未指派</span>
              </span>
            </li>
          </ol>
          <div v-else class="process-list-empty">
            {{ viewPlan.processName || '暂无工序配置' }}
          </div>
        </div>

        <!-- 备注 -->
        <div v-if="viewPlan.notes" class="detail-section">
          <div class="detail-section-title">备注</div>
          <div class="detail-notes">{{ viewPlan.notes }}</div>
        </div>
      </div>
    </el-dialog>

    <!-- 新建计划对话框 -->
    <el-dialog
      v-model="dialogVisible"
      title="新建生产计划"
      width="500px"
      :close-on-click-modal="false"
      :before-close="handleDialogClose"
    >
      <el-form :model="planForm" label-width="100px">
        <el-form-item label="来源类型" required>
          <el-radio-group v-model="planForm.sourceType" @change="handleSourceTypeChange">
            <el-radio label="MANUAL">手动</el-radio>
            <el-radio label="SAFETY_STOCK">存货生产</el-radio>
            <el-radio label="CUSTOMER_ORDER">销售订单</el-radio>
            <el-radio label="AI_FORECAST">AI预测</el-radio>
          </el-radio-group>
          <div
            v-if="planForm.sourceType === 'SAFETY_STOCK'"
            style="font-size: 12px; color: var(--text-color-secondary, #909399); margin-top: 4px;"
          >
            存货生产将走完整报工流程，完工后自动入库成品批次，无需关联销售订单
          </div>
        </el-form-item>
        <el-form-item v-if="planForm.sourceType === 'CUSTOMER_ORDER'" label="销售订单" required>
          <el-select
            v-model="planForm.sourceOrderId"
            placeholder="选择关联的销售订单"
            filterable
            :loading="salesOrdersLoading"
            style="width: 100%"
            @change="handleSalesOrderSelect"
          >
            <el-option
              v-for="so in selectableSalesOrders"
              :key="String(so.id)"
              :label="canViewPrice ? `${so.orderNo} | ${so.customerName || ''} | ¥${so.totalAmount || 0} | ${so.statusLabel || ''}` : `${so.orderNo} | ${so.customerName || ''} | ${so.statusLabel || ''}`"
              :value="String(so.id)"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          v-if="planForm.sourceType === 'CUSTOMER_ORDER' && planForm.sourceOrderId"
          label="产品行"
          required
        >
          <el-select
            v-model="planForm.sourceOrderItemId"
            placeholder="选择关联的销售订单产品行"
            filterable
            style="width: 100%"
            @change="handleSalesOrderItemSelect"
          >
            <el-option
              v-for="it in selectedOrderItems"
              :key="String(it.id)"
              :label="`${it.productName || ''}${it.specification ? ' | ' + it.specification : ''} | 数量 ${it.quantity || 0} | 待发 ${it.remainingQty || 0}`"
              :value="String(it.id)"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="产品类型" required>
          <!-- A4: 来源=销售订单时锁定产品类型，不允许自由选择 -->
          <el-select
            v-model="planForm.productTypeId"
            placeholder="选择产品类型"
            filterable
            :disabled="planForm.sourceType === 'CUSTOMER_ORDER'"
            style="width: 100%"
            @change="handleProductChange"
          >
            <el-option
              v-for="item in productTypes"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
          <div
            v-if="planForm.sourceType === 'CUSTOMER_ORDER'"
            style="font-size: 12px; color: var(--text-color-secondary, #909399); margin-top: 4px;"
          >
            来源为销售订单时，产品类型由所选订单行自动确定，不可手动更改
          </div>
        </el-form-item>
        <el-form-item label="客户名称">
          <el-input v-model="planForm.sourceCustomerName" placeholder="选择产品后自动填充，也可手动输入" />
        </el-form-item>
        <!-- A3: 工序改为只读展示，不允许编辑；工序由产品工序配置决定 -->
        <el-form-item label="工序">
          <template v-if="!planForm.productTypeId">
            <span style="color: var(--text-color-secondary, #909399); font-size: 13px;">请先选择产品类型</span>
          </template>
          <template v-else-if="productWorkProcessList.length === 0">
            <span style="color: var(--el-color-warning, #e6a23c); font-size: 13px;">
              该产品未配置工序，请先到
              <el-link type="primary" @click="router.push('/system/product-processes')" style="font-size: 13px; vertical-align: baseline;">产品工序配置</el-link>
              中配置
            </span>
          </template>
          <template v-else>
            <div style="width: 100%;">
              <el-tag
                v-for="(wp, idx) in productWorkProcessList"
                :key="wp.id"
                size="small"
                style="margin-right: 6px; margin-bottom: 4px;"
              >{{ idx + 1 }}. {{ wp.processName }}<template v-if="wp.responsibleWorkerName"> ({{ wp.responsibleWorkerName }})</template></el-tag>
            </div>
          </template>
          <!-- processName is kept in sync via loadBomProcesses (T135 ITEM #4) -->
        </el-form-item>
        <!-- A5/E3: batchDate 已被后端消费 (ProductionProgressDashboard 按批次日期过滤)，保留；加说明区分两个日期 -->
        <el-form-item label="批次日期">
          <el-date-picker
            v-model="planForm.batchDate"
            type="date"
            placeholder="实际转批次日期（默认今日）"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
          <div style="font-size: 12px; color: var(--text-color-secondary, #909399); margin-top: 2px;">
            批次日期 = 实际开工/转批次日；计划日期 = 预期完成生产日
          </div>
        </el-form-item>
        <el-form-item label="计划数量" required>
          <el-input-number v-model="planForm.plannedQuantity" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="计划生产日" required>
          <el-date-picker
            v-model="planForm.plannedDate"
            type="date"
            placeholder="预期完成生产日期（默认今日）"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="planForm.notes" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="预计工人数">
          <el-input-number v-model="planForm.estimatedWorkers" :min="1" :max="100" placeholder="可选" style="width: 100%" />
        </el-form-item>
        <!-- E2: 指派主管为可选字段，后端 DTO 无 @NotNull，前端亦无 required 规则 -->
        <el-form-item label="指派主管(可选)">
          <el-select v-model="planForm.assignedSupervisorId" clearable placeholder="可不填，稍后再指派" style="width: 100%">
            <el-option v-for="sup in supervisors" :key="sup.id" :label="sup.fullName || sup.username" :value="sup.id" />
          </el-select>
        </el-form-item>
        <CanvasDynamicFields v-model="planForm.customFields" module-code="production_plan" />
      </el-form>
      <template #footer>
        <el-button @click="handleDialogClose">取消</el-button>
        <el-button type="primary" :loading="dialogLoading" @click="submitPlan">确定</el-button>
      </template>
    </el-dialog>

    <!-- #742 完成生产 dialog -->
    <el-dialog
      v-model="completeDialogVisible"
      :title="completeProductName ? `完成生产 — ${completeProductName}` : '完成生产'"
      width="460px"
      destroy-on-close
      append-to-body
    >
      <el-form label-width="100px">
        <el-form-item label="品名">
          <span>{{ completeProductName || '-' }}</span>
        </el-form-item>
        <el-form-item label="计划数量">
          <span>{{ completePlannedQuantity }}</span>
        </el-form-item>
        <el-form-item label="实际产量" required>
          <el-input-number
            v-model="completeForm.actualQuantity"
            :min="0"
            :max="completePlannedQuantity > 0 ? completePlannedQuantity : undefined"
            :precision="2"
            style="width: 100%"
          />
          <div style="font-size: 12px; color: var(--text-color-secondary, #909399); margin-top: 4px;">
            实际产量 ≤ {{ completePlannedQuantity }}（不能超过计划数量）
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="completeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading" @click="submitComplete">确定完成</el-button>
      </template>
    </el-dialog>

    <!-- #743 取消原因 dialog (快捷下拉 + 品名) -->
    <el-dialog
      v-model="cancelDialogVisible"
      :title="cancelProductName ? `取消计划 — ${cancelProductName}` : '取消计划'"
      width="460px"
      destroy-on-close
      append-to-body
    >
      <el-form label-width="100px">
        <el-form-item label="品名">
          <span>{{ cancelProductName || '-' }}</span>
        </el-form-item>
        <el-form-item label="取消原因" required>
          <el-select
            v-model="cancelForm.reasonOption"
            placeholder="请选择取消原因"
            style="width: 100%"
          >
            <el-option
              v-for="opt in CANCEL_REASON_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="cancelForm.reasonOption === '其他'" label="原因补充" required>
          <el-input
            v-model="cancelForm.otherReason"
            type="textarea"
            :rows="3"
            placeholder="请说明具体取消原因"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cancelDialogVisible = false">关闭</el-button>
        <el-button type="danger" :loading="actionLoading" @click="submitCancel">确认取消计划</el-button>
      </template>
    </el-dialog>

    <!-- AI 对话创建 -->
    <AiEntryDrawer
      v-model="aiEntryVisible"
      :config="PRODUCTION_PLAN_CONFIG"
      @fill-form="handleAiFill"
    />
  </div>
  </CanvasAwareWrapper>
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
  gap: 8px;

  .header-right {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 0;
  }

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

// ==================== Plan Detail Dialog ====================
.plan-detail {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.detail-section {
  .detail-section-title {
    font-size: 13px;
    font-weight: 600;
    color: var(--text-color-regular, #606266);
    margin-bottom: 8px;
    display: flex;
    align-items: center;
    gap: 8px;

    .detail-section-hint {
      font-size: 12px;
      font-weight: 400;
      color: var(--text-color-secondary, #909399);
    }
  }
}

.process-list-loading {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 12px 0;
  font-size: 13px;
  color: var(--text-color-secondary, #909399);
}

.process-list {
  margin: 0;
  padding: 0;
  list-style: none;
  border: 1px solid var(--border-color-lighter, #ebeef5);
  border-radius: 4px;
  overflow: hidden;

  .process-item {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 12px;
    font-size: 13px;
    border-bottom: 1px solid var(--border-color-extra-light, #f2f6fc);

    &:last-child {
      border-bottom: none;
    }

    &:nth-child(even) {
      background: var(--fill-color-light, #f5f7fa);
    }

    .process-order {
      color: var(--text-color-secondary, #909399);
      min-width: 20px;
      flex-shrink: 0;
    }

    .process-name {
      flex: 1;
      color: var(--text-color-primary, #303133);
    }

    .process-assignee {
      flex-shrink: 0;
    }

    .process-unassigned {
      font-size: 12px;
      color: var(--text-color-placeholder, #c0c4cc);
    }
  }
}

.process-list-empty {
  font-size: 13px;
  color: var(--text-color-secondary, #909399);
  padding: 8px 0;
}

.detail-notes {
  font-size: 13px;
  color: var(--text-color-regular, #606266);
  line-height: 1.6;
  padding: 8px 12px;
  background: var(--fill-color-light, #f5f7fa);
  border-radius: 4px;
}

</style>
