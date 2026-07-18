<script setup lang="ts">
import { ref, computed, onMounted, nextTick, watch } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put } from '@/api/request';
import { ElMessage, ElMessageBox, ElSelect } from 'element-plus';
import { Plus, Search, Refresh, VideoPlay, VideoPause, CircleCheck, CircleClose, Download, Upload, ChatDotRound, Printer, Warning } from '@element-plus/icons-vue';
import { formatDateTimeCell } from '@/utils/tableFormatters';
import { handleCatchError } from '@/utils/errorToast';
import { confirmDiscardIfDirty } from '@/utils/confirmDiscardChanges';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import {
  downloadImportTemplate,
  importProductionPlans,
  exportProductionPlans,
  getSupervisors,
  listAvailableWip,
  createSecondaryPlan,
  getReportModeDefault,
  getMaterialAdvisory,
  getProductionSettlement,
  confirmProductionWarehouseReceipt,
  clearProductionTransitLedger,
  interimSettle,
  requestReverseInterimSettle,
  approveReversalRequest,
  rejectReversalRequest,
  listReversalRequests,
  type InterimSettleReversalRequest,
  stopProduction,
  resolveWorkflowByOutputs,
  getProductionDocumentTrace,
} from '@/api/productionPlan';
import type {
  ProductionPlanMaterialAdvisory,
  ProductionSettlementStatus,
  WipInventoryItem,
  WorkflowResolutionCandidate,
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
import { safePrint, printWorkOrderMulti } from '@/api/printApi';
import {
  getPlanStatusText,
  getPlanStatusType,
  planRowClassNameByStatus,
  planStatusClass,
} from './statusVisuals';
import {
  resolvePlanWorkflowCandidates,
  workflowCandidateBindingProductTypeId,
  workflowCandidateExtraOutputs,
  workflowCandidateOutputIds,
  workflowCandidateProcessSummary,
  workflowCandidateTopologyLabel,
  type PlanWorkflowResolutionMode,
} from './productionPlanWorkflowResolution';
import WorkflowRoutePreview from './components/WorkflowRoutePreview.vue';
import ProcessSheet from '../components/processSheet/ProcessSheet.vue';
import ProductionSummaryDialog from '../components/ProductionSummaryDialog.vue';
import {
  documentTraceTarget,
  traceDocumentLabel,
} from './documentTrace';
import type { ProductionDocumentTrace, ProductionTraceDocument } from '@/types/productionDocumentTrace';
import { productionPlanAiGuard, findUniqueProductByName } from '@/utils/aiEntryGuards';
import { canonicalUnitCode } from '@/utils/unitPricing';
import { enumLabel } from '@/utils/enumDisplay';

const router = useRouter();
const route = useRoute();
const { goCreate } = useCreateAndReturn();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('production'));
const canConfirmReceiptWrite = computed(() =>
  permissionStore.canWrite('warehouse')
  || permissionStore.canWrite('production')
  || permissionStore.canWrite('scheduling')
);
const canViewPrice = computed(() => permissionStore.canViewPrice);

function rowActionsFor(row: TableRow) {
  // #751: 删除 dropdown 中的 'view-detail' (页面已有独立"查看" button, 避免 3 button 跳同页)
  // fool-proof-design Rule 5: 'lock' 后端 API 未实装 — 灰显 + hover 原因, 不再让用户
  // 点了才弹 toast (#747 与 production/batches 的 'lock' 同款处理)。
  const all = computeRowActions(
    'productionPlan',
    { status: String(row.status || ''), id: String(row.id || '') },
    {
      canViewPrice: canViewPrice.value,
      forceDisabled: {
        lock: '锁定功能后端 API 对接中，暂不可用',
      },
    }
  );
  const filtered = all.filter((a) => a.id !== 'view-detail');
  // 6.12 N1b: PC 主流程砍掉"待生产→点开工"中间态。
  // PENDING 计划直接在未完成列表核对结单；需要逐道报工时使用行内 APP 报工按钮。
  return filtered;
}
function handleRowActionClick(actionId: string, row: TableRow) {
  switch (actionId) {
    case 'view-detail': handleViewPlan(row); break;
    case 'edit': void handleEditPlan(row); break;
    case 'cancel': handleCancel(row); break;
    case 'print-pdf': void safePrint('production-task', factoryId.value, String(row.id), { fileName: `生产计划_${row.planNumber || row.id}` }); break;
    case 'copy': void handleCopyPlan(row); break;
    // PENDING_APPROVAL (ProductionPlanStatus) 是"申请撤回/取消"进入的状态, 由
    // PRODUCTION_REVERSAL 审批流驱动 — 真正的审批入口是下方"撤销小结审批"列表
    // (openReversalApproval), 不是逐行单独审批。之前这两个 action 列在菜单里但
    // switch 没有对应 case, 点了直接落到 debug toast — 同一类"菜单列了但没接线"
    // 的死菜单症状。改为打开真正的审批入口。
    case 'approve':
    case 'reject':
      ElMessage.info(`计划 ${row.planNumber || row.id} 的撤销/取消申请请在下方「撤销小结审批」列表中处理`);
      void openReversalApproval();
      break;
    case 'lock':
      // 'lock' 已在 rowActionsFor() 用 forceDisabled 灰显; 保留仅作防御 (禁用项
      // el-dropdown 不会派发 command)。
      ElMessageBox.alert(
        '锁定后该生产计划将不再允许修改数量/日期，进入排产保护阶段（避免生产中误改）。\n\n后端 API 正在对接中，暂时不可用。',
        '锁定生产计划',
        { confirmButtonText: '我知道了' }
      ).catch(() => { /* dismiss */ });
      break;
    default: ElMessage.warning(`该操作暂不支持: ${actionId}`);
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
const materialAdvisoryMap = ref<Record<string, ProductionPlanMaterialAdvisory>>({});
const settlementStatusMap = ref<Record<string, ProductionSettlementStatus>>({});
// #726 SP12: 批量合并打印工单 — 多选状态
const selectedPlans = ref<TableRow[]>([]);
const documentTraceVisible = ref(false);
const documentTraceLoading = ref(false);
const documentTrace = ref<ProductionDocumentTrace | null>(null);

async function openDocumentTrace(row: TableRow) {
  if (!factoryId.value) return;
  documentTraceVisible.value = true;
  documentTraceLoading.value = true;
  documentTrace.value = null;
  try {
    const response = await getProductionDocumentTrace(factoryId.value, String(row.id));
    if (!response.success || !response.data) {
      throw new Error(response.message || '加载单据追踪失败');
    }
    documentTrace.value = response.data;
  } catch (error) {
    handleCatchError(error, '加载单据追踪失败');
  } finally {
    documentTraceLoading.value = false;
  }
}

function openTraceDocument(document: ProductionTraceDocument) {
  const target = documentTraceTarget(document);
  if (!target) {
    ElMessage.info(`${traceDocumentLabel(document.documentType)}已记录在当前计划中，无独立详情页`);
    return;
  }
  documentTraceVisible.value = false;
  void router.push(target);
}

function traceDirectionLabel(direction?: string) {
  if (direction === 'UPSTREAM') return '上游来源';
  if (direction === 'EXECUTION') return '生产执行';
  if (direction === 'DOWNSTREAM') return '结算与出库';
  return '关联单据';
}
function handleSelectionChange(rows: TableRow[]) {
  selectedPlans.value = rows;
}
async function handleMultiPrint() {
  const ids = selectedPlans.value.map((r) => String(r.id));
  await printWorkOrderMulti(factoryId.value, ids);
}
const pagination = ref({ page: 1, size: 10, total: 0 });
const searchForm = ref({
  keyword: '',
  status: 'UNFINISHED'
});
const STATUS_FILTER_LABELS: Record<string, string> = {
  UNFINISHED: '未完成', PREPARED: '草稿', PLANNED: '待执行', PENDING: '待执行 (PENDING)',
  IN_PROGRESS: '进行中', PAUSED: '暂停', COMPLETED: '已结单', CANCELLED: '已取消',
};
// Rule 1 (fool-proof-design): 按编号/关键词搜索在受限状态筛选下 0 结果时, 提示切到"全部"重试,
// 而不是让用户误以为查不到这个计划 (#3 blocking-bug 场景: 按已知计划号搜, 状态默认"未完成")。
const emptyText = computed(() => {
  if (loading.value || tableData.value.length > 0) return '暂无数据';
  const status = searchForm.value.status;
  if (searchForm.value.keyword && status) {
    const label = STATUS_FILTER_LABELS[status] || status;
    return `当前筛选[${label}]无结果，试试切换状态筛选为「全部」`;
  }
  return '暂无数据';
});

// U-FOOTER-1
const summaryRequest = computed<ListSummaryRequest>(() => ({
  filterConditions: searchForm.value.status ? { status: searchForm.value.status } : {},
}));
const { summary: footerSummary, loading: footerLoading } = useListSummary('productionPlan', summaryRequest);

// ========== SP2 独立再加工/返工计划对话框 ==========
const secondaryDialogVisible = ref(false);
const secondaryDialogLoading = ref(false);
const wipListLoading = ref(false);
const wipList = ref<WipInventoryItem[]>([]);
const secondaryForm = ref<{
  wipId: number | null;
  quantity: number;
  productTypeId: string;
  plannedDate: string;
}>({
  wipId: null,
  quantity: 0,
  productTypeId: '',
  plannedDate: '',
});
const selectedWip = computed(() =>
  wipList.value.find((w) => w.id === secondaryForm.value.wipId) ?? null
);

async function handleCreateSecondary() {
  if (!factoryId.value) return;
  wipListLoading.value = true;
  secondaryForm.value = { wipId: null, quantity: 0, productTypeId: '', plannedDate: tomorrowStr() };
  secondaryDialogVisible.value = true;
  try {
    const res = await listAvailableWip(factoryId.value);
    if (res.success && Array.isArray(res.data)) {
      wipList.value = res.data;
    } else {
      ElMessage({ message: res.message || '加载半成品库存失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    handleCatchError(e, '加载半成品库存失败');
  } finally {
    wipListLoading.value = false;
  }
}

function handleWipSelect(wipId: number) {
  const wip = wipList.value.find((w) => w.id === wipId);
  if (wip) {
    // 防呆 Rule 1: 预填最大可用量
    secondaryForm.value.quantity = Number(wip.availableQuantity) || 0;
    // 防呆 Rule 2: 自动填充目标产品 (来源产品与目标产品通常相同)
    if (wip.productTypeId) {
      secondaryForm.value.productTypeId = wip.productTypeId;
    }
  }
}

async function submitSecondaryPlan() {
  if (!factoryId.value) return;
  const form = secondaryForm.value;
  if (!form.wipId) {
    ElMessage.warning('请选择源半成品 WIP');
    return;
  }
  if (form.quantity <= 0) {
    ElMessage.warning('计划加工数量必须大于 0');
    return;
  }
  if (!form.productTypeId) {
    ElMessage.warning('请选择目标产品类型');
    return;
  }
  const wip = selectedWip.value;
  if (wip && form.quantity > Number(wip.availableQuantity)) {
    ElMessage({
      message: `超出可用量: 需要 ${form.quantity}，可用 ${wip.availableQuantity}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
    return;
  }
  secondaryDialogLoading.value = true;
  try {
    const res = await createSecondaryPlan(factoryId.value, {
      wipId: form.wipId,
      quantity: form.quantity,
      productTypeId: form.productTypeId,
      plannedDate: form.plannedDate || undefined,
    });
    if (res.success) {
      ElMessage.success('独立再加工/返工计划创建成功');
      secondaryDialogVisible.value = false;
      loadData();
    } else {
      ElMessage({ message: res.message || '创建失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    handleCatchError(e, '创建独立再加工/返工计划失败');
  } finally {
    secondaryDialogLoading.value = false;
  }
}
// ========== /SP2 ==========

// 新建计划对话框
const dialogVisible = ref(false);
const dialogLoading = ref(false);
const planForm = ref({
  productTypeId: '',
  plannedQuantity: 0,
  aiRequestedUnit: '',
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
  // 以销定产多产品 (2026-06-24): 选 SO 默认带出全部产品行, 多选可取消; 每行各建一张计划。
  sourceOrderItemIds: [] as string[],
  // SP5 多SO合并工单: 追加的额外销售订单ID列表 (不含 primarySourceOrderId, 提交前合并)
  extraSourceOrderIds: [] as string[],
  customFields: {} as TableRow,
  // Wave2 六扇门: 免工序报工开关 (null→后端默认 true, 新建默认 true = 两点报工)
  skipProcessReporting: true as boolean | null,
  // 非 CUSTOMER_ORDER 来源：按所选成品集合解析一个完整 Workflow。
  targetFinishedGoodIds: [] as string[],
  resolvedCandidates: [] as WorkflowResolutionCandidate[],
  selectedCandidateWorkflowId: null as number | null,
  resolutionMode: '' as '' | PlanWorkflowResolutionMode,
});
const productTypes = ref<TableRow[]>([]);
// raw-centric 多SKU: 「生产成品」多选下拉只列成品/半成品, 过滤掉原料/包材/调味品。
// null/空 category 视为遗留产品, 保留可选 (不因缺 category 而被误伤隐藏)。
const RAW_WORKFLOW_EXCLUDED_CATEGORIES = new Set(['RAW_MATERIAL', 'PACKAGING', 'SEASONING']);
const finishedGoodProductTypes = computed(() =>
  productTypes.value.filter((p: TableRow) => {
    const cat = p.productCategory;
    if (cat === null || cat === undefined || cat === '') return true;
    return !RAW_WORKFLOW_EXCLUDED_CATEGORIES.has(String(cat));
  })
);
const bomProcesses = ref<string[]>([]);
// A3: full work-process objects for read-only display (ordered by processOrder)
const productWorkProcessList = ref<TableRow[]>([]);
// Wave2: 当前产品是否已配置工序 (0工序→强制两点, 开关 disabled 锁定勾选)
const hasProcesses = computed(() => productWorkProcessList.value.length > 0);
// raw-centric 多SKU (2026-07-13): 产品由「产品工序 Workflow」驱动 (有 enabled activation) →
// 逐道报工经 workflow materialize, 没有 legacy PWP 也不能强制两点 (否则 spawnTasks 走两点绕过 materialize → 逐道抽屉空)。
const hasActiveWorkflow = ref(false);
// 报工模式开关锁死条件:
//   - workflow 驱动 → 锁在「逐道报工」(skip=false), 防误切两点绕过 materialize
//   - 已选产品且 0 legacy 工序且无 workflow → 锁在「免工序」(强制两点)
//   - 其余(未选产品 / 有 legacy 工序)可自由切换 (防呆 Rule 5: 不制造 dead-end)
const reportModeLocked = computed(() =>
  !!planForm.value.productTypeId && (hasActiveWorkflow.value || !hasProcesses.value));
const customers = ref<TableRow[]>([]);

const resolvedWorkflowCandidate = computed<WorkflowResolutionCandidate | undefined>(() =>
  planForm.value.resolvedCandidates.find(
    (candidate) => candidate.workflowId === planForm.value.selectedCandidateWorkflowId
  )
);
const hasResolvedWorkflowCandidate = computed(() => !!resolvedWorkflowCandidate.value);

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
let _selectableSalesOrdersInflight: Promise<void> | null = null;

async function loadSelectableSalesOrders() {
  if (!factoryId.value) return;
  if (_selectableSalesOrdersInflight) return _selectableSalesOrdersInflight;
  _selectableSalesOrdersInflight = (async () => {
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
      _selectableSalesOrdersInflight = null;
    }
  })();
  return _selectableSalesOrdersInflight;
}

function handleSourceTypeChange(val: string) {
  sourceOrderIdError.value = false;
  if (val === 'CUSTOMER_ORDER') {
    if (selectableSalesOrders.value.length === 0) loadSelectableSalesOrders();
  } else {
    planForm.value.sourceOrderId = '';
    planForm.value.sourceOrderItemId = '';
  }
}

// 「来源销售订单」校验失败态 — 防呆: 空字段红框锚定 + 自动聚焦, 不只弹个 toast 让用户自己找。
const sourceOrderIdError = ref(false);
const sourceOrderSelectRef = ref<InstanceType<typeof ElSelect> | null>(null);

// P0-12: 当前选中订单的可选产品行
const selectedOrderItems = computed<TableRow[]>(() => {
  const oid = planForm.value.sourceOrderId;
  if (!oid) return [];
  const so = selectableSalesOrders.value.find((o) => String(o.id) === String(oid));
  return so && Array.isArray(so.items) ? (so.items as TableRow[]) : [];
});

function handleSalesOrderSelect(orderId: string) {
  sourceOrderIdError.value = false;
  const so = selectableSalesOrders.value.find((o) => String(o.id) === String(orderId));
  // 切换订单时清空已选行
  planForm.value.sourceOrderItemId = '';
  planForm.value.sourceOrderItemIds = [];
  planForm.value.productTypeId = '';
  productWorkProcessList.value = [];
  if (so) {
    planForm.value.sourceCustomerName = String(so.customerName || '');
    // 以销定产 (2026-06-24): 默认带出全部产品行 (多选), 用户可取消不需要的。每行各建一张计划。
    const items = Array.isArray(so.items) ? (so.items as TableRow[]) : [];
    planForm.value.sourceOrderItemIds = items.map((it) => String(it.id));
    // 兼容单产品: 仅 1 行时也回填单字段 (产品类型/客户), 供 UI 摘要展示
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

// Fable 审计修复 (问题1 — 多租户安全): 工厂级免工序报工默认值。
// F006=true (新建默认勾选两点); 其他工厂=false (默认不勾, 逐道)。取数失败兜底 false (安全=逐道)。
const skipReportingFactoryDefault = ref(false);
async function loadReportModeDefault() {
  if (!factoryId.value) return;
  try {
    const res = await getReportModeDefault(factoryId.value);
    skipReportingFactoryDefault.value = res.data === true;
  } catch {
    skipReportingFactoryDefault.value = false;
  }
}

onMounted(() => {
  loadData();
  loadProductTypes();
  loadReferenceData();
  loadCustomers();
  loadReportModeDefault();
  void maybeReopenFromQuery();
  void maybeOpenProcessEntryFromQuery();
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
      void loadMaterialAdvisories(tableData.value);
      void loadSettlementStatuses(tableData.value);
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

async function loadMaterialAdvisories(rows: TableRow[]) {
  if (!factoryId.value) return;
  const unfinishedRows = rows.filter((row) =>
    ['PENDING', 'IN_PROGRESS'].includes(String(row.status || '').toUpperCase())
  );
  if (unfinishedRows.length === 0) {
    materialAdvisoryMap.value = {};
    return;
  }
  const entries = await Promise.allSettled(
    unfinishedRows.map(async (row) => {
      const planId = String(row.id);
      const res = await getMaterialAdvisory(factoryId.value, planId);
      if (!res.success || !res.data) {
        throw new Error(res.message || '加载原料预警失败');
      }
      return [planId, res.data] as const;
    })
  );
  const next: Record<string, ProductionPlanMaterialAdvisory> = {};
  let failed = false;
  entries.forEach((entry) => {
    if (entry.status === 'fulfilled') {
      next[entry.value[0]] = entry.value[1];
    } else {
      failed = true;
    }
  });
  materialAdvisoryMap.value = next;
  if (failed) {
    ElMessage({ message: '部分生产计划原料预警加载失败，请刷新后重试', type: 'error', duration: 0, showClose: true });
  }
}

async function loadSettlementStatuses(rows: TableRow[]) {
  if (!factoryId.value) return;
  const settledRows = rows.filter((row) =>
    String(row.status || '').toUpperCase() === 'COMPLETED'
  );
  if (settledRows.length === 0) {
    settlementStatusMap.value = {};
    return;
  }
  const entries = await Promise.allSettled(
    settledRows.map(async (row) => {
      const planId = String(row.id);
      const res = await getProductionSettlement(factoryId.value, planId);
      if (!res.success || !res.data) {
        throw new Error(res.message || '加载结单状态失败');
      }
      return [planId, res.data] as const;
    })
  );
  const next: Record<string, ProductionSettlementStatus> = {};
  let failed = false;
  entries.forEach((entry) => {
    if (entry.status === 'fulfilled') {
      next[entry.value[0]] = entry.value[1];
    } else {
      failed = true;
    }
  });
  settlementStatusMap.value = next;
  if (failed) {
    ElMessage({ message: '部分已结单计划入库状态加载失败，请刷新后重试', type: 'error', duration: 0, showClose: true });
  }
}

async function loadProductTypes() {
  if (!factoryId.value) return;
  try {
    // 用 /active 端点取全部启用产品(非分页数组), 避免默认分页 size=20 截断
    // (F006 176 产品曾只加载前 20 个, 客户端 filterable 下拉搜不到老产品 e.g. 猪舌门腔)
    const response = await get(`/${factoryId.value}/product-types/active`);
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
  hasActiveWorkflow.value = false;
  if (!factoryId.value || !productTypeId) {
    bomProcesses.value = [];
    productWorkProcessList.value = [];
    return;
  }
  // raw-centric 多SKU (2026-07-13): 先探产品是否由 Workflow 驱动 (有 enabled activation)。
  // workflow 产品逐道报工经 materialize, 即便没有 legacy PWP 也必须 skip=false, 否则
  // spawnTasks 走两点报工绕过 materializeIfActive → 逐道抽屉空。诚实降级: 探测失败视为无 workflow。
  try {
    const act = await get<{ enabled?: boolean } | null>(
      `/${factoryId.value}/product-process-workflows/${productTypeId}/activation`);
    hasActiveWorkflow.value = !!(act.success && act.data && act.data.enabled === true);
  } catch {
    hasActiveWorkflow.value = false;
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
      planForm.value.processName = names.length > 0
        ? names.join('、')
        : (hasActiveWorkflow.value ? 'Workflow 逐道报工' : '两点报工');
    } else {
      bomProcesses.value = [];
      productWorkProcessList.value = [];
      planForm.value.processName = hasActiveWorkflow.value ? 'Workflow 逐道报工' : '';
    }
  } catch {
    bomProcesses.value = [];
    productWorkProcessList.value = [];
    planForm.value.processName = hasActiveWorkflow.value ? 'Workflow 逐道报工' : '';
  }
  // 最终报工模式: workflow 驱动 → 逐道 (skip=false, 让 materialize 生效);
  // 否则 0 legacy 工序 → 强制两点 (skip=true); 有 legacy 工序 → 保留当前选择。
  if (hasActiveWorkflow.value) {
    planForm.value.skipProcessReporting = false;
    // 存货生产 + workflow: 数量字段刚显现, 给个正默认值 (el-input-number min=1), 供转批次。
    if (!planForm.value.plannedQuantity || planForm.value.plannedQuantity < 1) {
      planForm.value.plannedQuantity = 1;
    }
  } else if (productWorkProcessList.value.length === 0) {
    planForm.value.skipProcessReporting = true;
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

// ========== raw-centric 多SKU (2026-07-13): 多选「生产成品」→ 解析共用 raw workflow ==========
// 非 CUSTOMER_ORDER 来源专用 (设计文档 §13)。
const resolvingWorkflow = ref(false);
const workflowCandidateDialogVisible = ref(false);
const confirmingWorkflowCandidate = ref(false);
const pendingCandidateWorkflowId = ref<number | null>(null);
let preferredWorkflowSelection: {
  workflowId: number;
  definitionVersion: number;
  targetKey: string;
} | null = null;
let workflowResolveDebounceTimer: ReturnType<typeof setTimeout> | null = null;
// 防竞态: 快速切换选中集时, 老的 in-flight 请求可能比新请求晚回, 用单调递增代
// (generation) 丢弃过期响应, 避免旧结果覆盖新选择的解析结果。
let workflowResolveGeneration = 0;

function resetWorkflowResolutionState() {
  workflowResolveGeneration += 1;
  planForm.value.resolutionMode = '';
  planForm.value.resolvedCandidates = [];
  planForm.value.selectedCandidateWorkflowId = null;
  workflowCandidateDialogVisible.value = false;
  pendingCandidateWorkflowId.value = null;
}

async function applyResolvedWorkflowCandidate(candidate: WorkflowResolutionCandidate) {
  const bindingProductTypeId = workflowCandidateBindingProductTypeId(
    candidate,
    planForm.value.targetFinishedGoodIds,
  );
  if (!bindingProductTypeId) {
    planForm.value.productTypeId = '';
    hasActiveWorkflow.value = false;
    productWorkProcessList.value = [];
    ElMessage.error('该 Workflow 缺少生产计划绑定信息，请联系管理员重新发布');
    return;
  }
  planForm.value.productTypeId = bindingProductTypeId;
  await loadBomProcesses(bindingProductTypeId);
}

function openWorkflowCandidateDialog() {
  const candidates = planForm.value.resolvedCandidates;
  if (candidates.length === 0) return;
  pendingCandidateWorkflowId.value = planForm.value.selectedCandidateWorkflowId
    ?? (candidates.length === 1 ? candidates[0].workflowId : null);
  workflowCandidateDialogVisible.value = true;
}

function candidateExtraOutputNames(candidate: WorkflowResolutionCandidate): string[] {
  const extraIds = new Set(workflowCandidateExtraOutputs(
    candidate,
    planForm.value.targetFinishedGoodIds,
  ));
  return (candidate.terminalOutputs || [])
    .filter((output) => extraIds.has(output.productTypeId))
    .map((output) => output.productName || output.productTypeId);
}

async function confirmWorkflowCandidateSelection() {
  const candidate = planForm.value.resolvedCandidates.find(
    (item) => item.workflowId === pendingCandidateWorkflowId.value,
  );
  if (!candidate) {
    ElMessage.warning('请选择一条生产工序路线');
    return;
  }
  const completeOutputs = workflowCandidateOutputIds(candidate);
  const extraOutputs = workflowCandidateExtraOutputs(
    candidate,
    planForm.value.targetFinishedGoodIds,
  );
  if (extraOutputs.length > 0) {
    preferredWorkflowSelection = {
      workflowId: candidate.workflowId,
      definitionVersion: candidate.definitionVersion,
      targetKey: completeOutputs.join(','),
    };
    workflowCandidateDialogVisible.value = false;
    planForm.value.targetFinishedGoodIds = completeOutputs;
    ElMessage.success('已把该 Workflow 的额外联产成品加入本计划');
    return;
  }
  confirmingWorkflowCandidate.value = true;
  try {
    planForm.value.selectedCandidateWorkflowId = candidate.workflowId;
    await applyResolvedWorkflowCandidate(candidate);
    workflowCandidateDialogVisible.value = false;
  } finally {
    confirmingWorkflowCandidate.value = false;
  }
}

function goToWorkflowConfig(finishedGoodIds: string[]) {
  const productTypeId = finishedGoodIds.length === 1 ? finishedGoodIds[0] : '';
  router.push(productTypeId
    ? `/system/product-processes?productTypeId=${encodeURIComponent(productTypeId)}`
    : '/system/product-processes');
}

async function resolveTargetFinishedGoods(ids: string[]): Promise<void> {
  if (!factoryId.value || ids.length === 0) return;
  const myGeneration = ++workflowResolveGeneration;
  resolvingWorkflow.value = true;
  try {
    const res = await resolveWorkflowByOutputs(factoryId.value, ids);
    if (myGeneration !== workflowResolveGeneration) return; // 已被更新的选择取代, 丢弃过期响应
    if (!res.success || !res.data) {
      handleCatchError(new Error(res.message || '解析工序图失败'), '解析工序图失败');
      return;
    }
    const resolution = resolvePlanWorkflowCandidates(ids, res.data.candidates || []);
    planForm.value.resolutionMode = resolution.mode;
    planForm.value.resolvedCandidates = resolution.candidates;
    const preferred = preferredWorkflowSelection?.targetKey === ids.join(',')
      ? resolution.candidates.find((candidate) =>
          candidate.workflowId === preferredWorkflowSelection?.workflowId
          && candidate.definitionVersion === preferredWorkflowSelection?.definitionVersion)
      : undefined;
    preferredWorkflowSelection = null;
    if (preferred) {
      planForm.value.selectedCandidateWorkflowId = preferred.workflowId;
      await applyResolvedWorkflowCandidate(preferred);
    } else if (resolution.candidates.length === 1 && resolution.candidates[0].exactMatch) {
      const candidate = resolution.candidates[0];
      planForm.value.selectedCandidateWorkflowId = candidate.workflowId;
      await applyResolvedWorkflowCandidate(candidate);
    } else if (resolution.candidates.length > 0) {
      // 同层歧义，或唯一候选包含额外联产成品：必须弹窗显式确认。
      planForm.value.selectedCandidateWorkflowId = null;
      planForm.value.productTypeId = '';
      hasActiveWorkflow.value = false;
      productWorkProcessList.value = [];
      pendingCandidateWorkflowId.value = resolution.candidates.length === 1
        ? resolution.candidates[0].workflowId : null;
      workflowCandidateDialogVisible.value = true;
    } else {
      // 无候选或数据异常出现多个激活候选时，必须显式处理，不能退回 legacy 产品路径。
      planForm.value.selectedCandidateWorkflowId = null;
      planForm.value.productTypeId = '';
      hasActiveWorkflow.value = false;
      productWorkProcessList.value = [];
    }
  } catch (e) {
    if (myGeneration === workflowResolveGeneration) {
      preferredWorkflowSelection = null;
      handleCatchError(e, '解析工序图失败');
    }
  } finally {
    if (myGeneration === workflowResolveGeneration) resolvingWorkflow.value = false;
  }
}

watch(
  () => planForm.value.targetFinishedGoodIds.join(','),
  () => {
    if (workflowResolveDebounceTimer) clearTimeout(workflowResolveDebounceTimer);
    resetWorkflowResolutionState();
    const ids = planForm.value.targetFinishedGoodIds.slice();
    if (ids.length === 0) {
      planForm.value.productTypeId = '';
      hasActiveWorkflow.value = false;
      productWorkProcessList.value = [];
      return;
    }
    workflowResolveDebounceTimer = setTimeout(() => {
      void resolveTargetFinishedGoods(ids);
    }, 300);
  }
);

const nonSoSubmitBlocked = computed(() => {
  if (planForm.value.sourceType === 'CUSTOMER_ORDER') return false;
  const ids = planForm.value.targetFinishedGoodIds;
  if (ids.length === 0) return false;
  if (resolvingWorkflow.value || planForm.value.resolutionMode === '') return true;
  if (planForm.value.resolutionMode === 'NONE') return true;
  return planForm.value.selectedCandidateWorkflowId == null;
});
// ========== /raw-centric 多SKU ==========

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleRefresh() {
  searchForm.value = { keyword: '', status: 'UNFINISHED' };
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
    aiRequestedUnit: '',
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
    sourceOrderItemIds: [],
    // SP5: 重置追加SO列表
    extraSourceOrderIds: [],
    customFields: {} as TableRow,
    // Fable 审计修复 (问题1 — 多租户安全): 默认值取工厂配置 (F006=true 两点 / 其他=false 逐道),
    // 不再全系统硬编码 true。产品加载后若 0 工序仍自动锁定为 true (loadBomProcesses)。
    skipProcessReporting: skipReportingFactoryDefault.value,
    // raw-centric 多SKU (2026-07-13): 重置多选成品 + 工序图解析状态。
    targetFinishedGoodIds: [],
    resolvedCandidates: [],
    selectedCandidateWorkflowId: null,
    resolutionMode: '',
  };
  productWorkProcessList.value = [];
  hasActiveWorkflow.value = false;
  resolvingWorkflow.value = false;
  workflowCandidateDialogVisible.value = false;
  pendingCandidateWorkflowId.value = null;
  preferredWorkflowSelection = null;
  workflowResolveGeneration += 1;
  // T135 ITEM #1: 默认 CUSTOMER_ORDER — 预加载可选销售订单列表
  if (selectableSalesOrders.value.length === 0) loadSelectableSalesOrders();
  dialogVisible.value = true;
}

function goAddOrderItems(soId: string) {
  if (!soId) return;
  goCreate(`/sales/orders/${soId}?editItems=1`, {
    reopen: `/production/plans?reopenPlan=1&planSO=${soId}`,
  });
}

async function maybeReopenFromQuery() {
  const q = route.query;
  const soId = (q.reopenPlan === '1' && typeof q.planSO === 'string' && q.planSO)
    || (q.action === 'create' && typeof q.salesOrderId === 'string' && q.salesOrderId)
    || '';
  if (!soId) return;
  // handleCreate is synchronous and fires loadSelectableSalesOrders without await.
  // We must await the SO list ourselves before calling handleSalesOrderSelect.
  handleCreate();
  await loadSelectableSalesOrders();
  planForm.value.sourceType = 'CUSTOMER_ORDER';
  planForm.value.sourceOrderId = String(soId);
  handleSalesOrderSelect(String(soId));
  const { reopenPlan: _a, planSO: _b, salesOrderId: _c, action: _d, ...rest } = q as Record<string, unknown>;
  router.replace({ query: rest as any });
}

async function submitPlan() {
  if (!planForm.value.plannedDate) {
    ElMessage.warning('请选择计划生产日');
    return;
  }
  if (!factoryId.value) return;

  // 以销定产 (2026-06-24): 来源=销售订单 → 按选中的产品行批量建计划 (每行一张, 产品/数量取自 SO 行)。
  const isSoDriven = planForm.value.sourceType === 'CUSTOMER_ORDER';
  if (isSoDriven) {
    if (!planForm.value.sourceOrderId) {
      // 防呆: 红框锚定 + 自动聚焦空字段 + sticky 可关闭错误提示 (不是 3 秒自动消失的 toast)。
      sourceOrderIdError.value = true;
      void nextTick(() => sourceOrderSelectRef.value?.focus());
      ElMessage({ message: '请选择来源销售订单', type: 'error', duration: 0, showClose: true });
      return;
    }
    if (!planForm.value.sourceOrderItemIds || planForm.value.sourceOrderItemIds.length === 0) {
      ElMessage.warning('请至少保留一个产品行 (在「产品行」多选中至少选 1 项)');
      return;
    }
    dialogLoading.value = true;
    try {
      const payload = {
        sourceOrderId: planForm.value.sourceOrderId,
        itemIds: planForm.value.sourceOrderItemIds,
        plannedDate: planForm.value.plannedDate,
        estimatedWorkers: planForm.value.estimatedWorkers,
        assignedSupervisorId: planForm.value.assignedSupervisorId || undefined,
        notes: planForm.value.notes || undefined,
        skipProcessReporting: planForm.value.skipProcessReporting,
      };
      const response = await post(`/${factoryId.value}/production-plans/batch-from-so`, payload);
      if (response.success) {
        const n = Array.isArray(response.data) ? response.data.length : planForm.value.sourceOrderItemIds.length;
        ElMessage.success(`已生成 ${n} 张生产计划`);
        dialogVisible.value = false;
        loadData();
      } else {
        ElMessage.error(response.message || '创建失败');
      }
    } catch (error: any) {
      console.error('[以销定产批量建计划失败]', error);
    } finally {
      dialogLoading.value = false;
    }
    return;
  }

  // 非 SO 来源：单选只认单产出 Workflow，多选只认共同多产出 Workflow。
  if (planForm.value.targetFinishedGoodIds.length === 0) {
    ElMessage.warning('请选择生产成品');
    return;
  }
  if (nonSoSubmitBlocked.value) {
    if (planForm.value.resolutionMode === 'NONE') {
      ElMessage.warning(planForm.value.targetFinishedGoodIds.length === 1
        ? '该产品没有单产出 Workflow，请前往创建单产出 Workflow'
        : '未找到共享的工序 Workflow，请分开创建生产计划');
    } else {
      ElMessage.warning('检测到多个可用 Workflow，请先选择本计划使用的版本');
    }
    return;
  }
  if (!planForm.value.productTypeId || !resolvedWorkflowCandidate.value) {
    ElMessage.warning('请选择生产计划使用的 Workflow');
    return;
  }
  // raw-centric 多SKU: 存货生产常规无需数量, 但 workflow 驱动产品必须 (转批次 create-batch 要求 >0)。
  const needQty = planForm.value.sourceType !== 'SAFETY_STOCK'
    || (planForm.value.sourceType === 'SAFETY_STOCK' && hasActiveWorkflow.value);
  if (!planForm.value.plannedQuantity && needQty) {
    ElMessage.warning(hasActiveWorkflow.value && planForm.value.sourceType === 'SAFETY_STOCK'
      ? '本产品由 Workflow 驱动，请输入计划投料数量'
      : '请输入计划数量');
    return;
  }
  dialogLoading.value = true;
  try {
    const {
      resolvedCandidates: _resolvedCandidates,
      selectedCandidateWorkflowId: _selectedCandidateWorkflowId,
      resolutionMode,
      targetFinishedGoodIds,
      aiRequestedUnit: _aiRequestedUnit,
      ...rest
    } = planForm.value;
    const payload: Record<string, unknown> = { ...rest };
    if (resolutionMode === 'SINGLE_OUTPUT' || resolutionMode === 'SHARED_MULTI_OUTPUT') {
      payload.targetFinishedGoodIds = targetFinishedGoodIds;
      payload.selectedWorkflowId = resolvedWorkflowCandidate.value?.workflowId;
      payload.selectedWorkflowVersion = resolvedWorkflowCandidate.value?.definitionVersion;
    }
    const response = await post(`/${factoryId.value}/production-plans`, payload);
    if (response.success) {
      ElMessage.success('创建成功');
      dialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(response.message || '创建失败');
    }
  } catch (error: unknown) {
    // 409 WORKFLOW_RESOLUTION_NOT_COVERED: 双保险 — interceptor 已弹 sticky toast,
    // 这里额外给「去产品工序配置」跳转按钮 (Rule 5: 不留死胡同)。
    const err = error as { status?: number; code?: string; message?: string };
    if (err?.status === 409 && err?.code === 'WORKFLOW_SELECTED_VERSION_CHANGED') {
      resetWorkflowResolutionState();
      ElMessageBox.alert(
        err.message || '所选 Workflow 已被切换或失效，请重新选择',
        '工序版本已变化',
        { confirmButtonText: '重新解析', type: 'warning' },
      ).then(() => {
        void resolveTargetFinishedGoods(planForm.value.targetFinishedGoodIds.slice());
      }).catch(() => { /* 用户关闭，保持提交阻塞 */ });
    } else if (err?.status === 409 && err?.code === 'WORKFLOW_RESOLUTION_NOT_COVERED') {
      ElMessageBox.confirm(
        err.message || (planForm.value.targetFinishedGoodIds.length === 1
          ? '该产品没有单产出 Workflow，请前往创建单产出 Workflow'
          : '未找到共享的工序 Workflow，请分开创建生产计划'),
        '工序图未覆盖',
        { confirmButtonText: '去产品工序配置', cancelButtonText: '取消', type: 'warning' }
      ).then(() => {
        goToWorkflowConfig(planForm.value.targetFinishedGoodIds);
      }).catch(() => { /* 用户取消, 静默 */ });
    } else {
      // Interceptor shows specific toast; dedupe fallback
      console.error('[失败]', error);
    }
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

// ==================== 逐工序电子表格抽屉 (SP-F) ====================
const entryDrawerVisible = ref(false);
const entryRow = ref<any>(null);
// 防呆: 关抽屉前若有未保存草稿行 (ProcessDataTable 聚合上报) 二次确认, 见 handleEntryDrawerBeforeClose
const processSheetRef = ref<{ hasUnsavedRows: boolean } | null>(null);

function isStepwise(row: any): boolean {
  return row.skipProcessReporting === false;
}

async function openProcessEntry(row: any) {
  // raw-centric 多SKU (2026-07-13): workflow 驱动的计划逐道报工需先 materialize 批次
  // (逐道抽屉只读 workflow-config, 无批次 → 返空 → 落回 legacy archetype tab)。
  // 存货生产(SAFETY_STOCK)无「转批次」按钮, 手动/预测虽有但用户也可能直接点逐道录入 →
  // 这里对「PENDING + 产品有 active workflow」的计划自动 create-batch, 使逐道抽屉直接出全工序。
  // 非 workflow 或已转批次(IN_PROGRESS)计划不受影响 (跳过, 保持原行为)。
  try {
    const isPending = String(row?.status || '').toUpperCase() === 'PENDING';
    if (row?.id && row?.productTypeId && isPending && factoryId.value) {
      const act = await get<{ enabled?: boolean } | null>(
        `/${factoryId.value}/product-process-workflows/${row.productTypeId}/activation`);
      if (act.success && act.data && act.data.enabled === true) {
        const br = await post(`/${factoryId.value}/production-plans/${row.id}/create-batch`);
        if (br.success) {
          row.status = 'IN_PROGRESS';
          loadData();
        }
      }
    }
  } catch (error) {
    // 无法确认/物化 Workflow 时不能打开 legacy 逐道录入，否则同一计划会混用两套工序单位。
    console.error('[production-plan] Workflow 批次物化失败，阻止逐道录入', error);
    ElMessage.error('Workflow 批次尚未准备完成，暂不能逐道录入。请修复提示问题后重试。');
    return;
  }
  entryRow.value = row;
  entryDrawerVisible.value = true;
}

function onEntrySubmitted() { loadData(); }

// Bug2 fix (fool-proof-design.md Rule 5 no-dead-end): 生产批次详情页 (batches/detail.vue)
// 之前是死胡同 — 没有任何入口能跳到这里的「逐道录入」抽屉。它现在带
// `?openProcessEntryPlan=<planId>` 跳过来, 这里直接按 id 查计划 (不依赖 tableData 分页,
// 目标计划可能不在当前页) 并自动打开抽屉, 完了清掉 query 防止刷新重复弹。
async function maybeOpenProcessEntryFromQuery() {
  const planId = route.query.openProcessEntryPlan;
  if (!planId || typeof planId !== 'string' || !factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/production-plans/${planId}`);
    if (res.success && res.data) {
      openProcessEntry(res.data);
    } else {
      ElMessage.error('未找到对应生产计划，请在下方列表手动查找并点击"逐道录入"');
    }
  } catch {
    // Interceptor already shows sticky toast for ApiError.
  } finally {
    const { openProcessEntryPlan: _drop, ...rest } = route.query;
    router.replace({ query: rest });
  }
}

async function handleEntryDrawerBeforeClose(done: () => void) {
  const dirty = processSheetRef.value?.hasUnsavedRows === true;
  if (await confirmDiscardIfDirty(dirty)) done();
}

// ==================== 核对结单 dialog (#742 / 6.12 revised) ====================
interface SettlementRawConsumptionForm {
  materialBatchId: string;
  productTypeId?: string | null;
  materialTypeId?: string | null;
  batchNumber?: string | null;
  unit?: string | null;
  warehouseId?: string | null;
  quantity: number;
  note: string;
  source?: 'PROCESS_REPORT' | 'MANUAL';
}

interface SettlementWipConsumptionForm {
  semiFinishedInventoryId: number | null;
  quantity: number;
  note: string;
}

interface SettlementCompleteForm {
  actualQuantity: number;
  semiFinishedOutputQuantity: number;
  rawMaterialConsumptions: SettlementRawConsumptionForm[];
  semiFinishedConsumptions: SettlementWipConsumptionForm[];
  workerCount: number;
  workMinutes: number;
  laborSegments: Array<{
    workerId?: number | null;
    workerName?: string | null;
    workType?: string | null;
    minutes: number;
    headcount: number;
    note?: string | null;
  }>;
  laborDeferredReason: string;
  varianceReason: string;
  otherVarianceReason: string;
}

interface MaterialBatchOption {
  id: string;
  materialTypeId?: string | null;
  materialName?: string | null;
  materialTypeName?: string | null;
  batchNumber?: string | null;
  currentQuantity?: number | string | null;
  quantity?: number | string | null;
  quantityUnit?: string | null;
  unit?: string | null;
  warehouseId?: string | null;
}

interface FactoryWarehouseOption {
  id: string;
  code?: string | null;
  type?: string | null;
  name?: string | null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object';
}

function isMaterialBatchOption(value: unknown): value is MaterialBatchOption {
  return isRecord(value) && typeof value.id === 'string';
}

function extractMaterialBatchRows(payload: unknown): MaterialBatchOption[] {
  if (Array.isArray(payload)) {
    return payload.filter(isMaterialBatchOption);
  }
  if (isRecord(payload) && Array.isArray(payload.content)) {
    return payload.content.filter(isMaterialBatchOption);
  }
  return [];
}

const SETTLEMENT_VARIANCE_REASON_OPTIONS = [
  { value: '现场称重差异', label: '现场称重差异' },
  { value: '原料状态差异', label: '原料状态差异' },
  { value: '临时调整产量', label: '临时调整产量' },
  { value: '客户/销售变更', label: '客户/销售变更' },
  { value: '其他', label: '其他（请补充说明）' },
];
const completeDialogVisible = ref(false);
const completeRow = ref<TableRow | null>(null);
// 幂等 key 在打开弹框时一次性生成，重试提交复用同一 key (防呆 Rule 4)
const settlementIdempotencyKey = ref('');

// ===== 阅读汇总 dialog =====
const summaryDialogVisible = ref(false);
const summaryPlanId = ref('');
const summaryPlanNumber = ref('');
const summaryProductName = ref('');
function handleOpenSummary(row: TableRow) {
  summaryPlanId.value = String(row.id || '');
  summaryPlanNumber.value = String(row.planNumber || '');
  summaryProductName.value = String(row.productName || row.productTypeName || '');
  summaryDialogVisible.value = true;
}
// ===== /阅读汇总 =====

// ===== Phase 2A: 核对结单自动预填 (报工→核算自动化) =====
type SettlementIssueSeverity = 'BLOCKER' | 'INFO';
interface SettlementPrefillIssue {
  code: string;
  message: string;
  field?: string | null;
  severity?: SettlementIssueSeverity;
}
interface SettlementPrefillResponse {
  prefill: {
    actualFinishedQuantity?: number | null;
    actualSemiFinishedQuantity?: number | null;
    quantityVarianceReason?: string | null;
    rawMaterialConsumptions?: Array<{
      materialBatchId?: string | null;
      productTypeId?: string | null;
      materialTypeId?: string | null;
      batchNumber?: string | null;
      unit?: string | null;
      warehouseId?: string | null;
      quantity?: number | null;
      note?: string | null;
    }> | null;
    laborSegments?: Array<{
      workerId?: number | null;
      workerName?: string | null;
      workType?: string | null;
      minutes?: number | null;
      headcount?: number | null;
      note?: string | null;
    }> | null;
    laborDeferredReason?: string | null;
    terminalOutputs?: Array<{
      productTypeId?: string | null;
      batchNumber?: string | null;
      quantity?: number | null;
      unit?: string | null;
    }> | null;
  } | null;
  audit: { clean: boolean; issues: SettlementPrefillIssue[] } | null;
}
const settlementPrefillLoading = ref(false);
const settlementPrefillIssues = ref<SettlementPrefillIssue[]>([]);
const settlementPrefillApplied = ref(false);
// 后端审计 clean (只看 BLOCKER); 未带入数据时 false
const settlementPrefillCleanFromServer = ref(false);
const settlementTerminalOutputs = ref<Array<{
  productTypeId: string;
  batchNumber: string;
  quantity: number;
  unit: string;
}>>([]);
// 阻塞级问题 (令一键确认禁用 + 顶部 warning)
const settlementBlockerIssues = computed(
  () => settlementPrefillIssues.value.filter((i) => (i.severity ?? 'BLOCKER') === 'BLOCKER'),
);
// 提示级问题 (仅展示, 不阻塞)
const settlementInfoIssues = computed(
  () => settlementPrefillIssues.value.filter((i) => i.severity === 'INFO'),
);
// 后端审计 clean=true 即可走确认式结单；正常路径不再重复校验或回传客户端事实副本。
// 缺失工时、批次或产量会由后端审计作为 BLOCKER 返回，再进入下方异常补录路径。
const settlementPrefillClean = computed(
  () => settlementPrefillApplied.value
    && settlementPrefillCleanFromServer.value,
);
// 某字段是否被预填审计标记为待人工补全 (BLOCKER 级才高亮)
function settlementFieldHasIssue(field: string): boolean {
  return settlementBlockerIssues.value.some((i) => i.field === field);
}
const materialBatchListLoading = ref(false);
const materialBatchOptions = ref<MaterialBatchOption[]>([]);
const rawWarehouseId = ref('');

// ===== 防呆 Rule 1: 原料领用下拉 BOM 预过滤 (#结单原料越权 fix) =====
// 后端结单提交守卫 (ensureMaterialBatchAllowedForSettlement) 会在提交后才 409 拒绝不属于
// 产品当前 BOM 的原料批次。这里提前拿到同一份判定结果，把下拉预先收窄到 BOM 允许的批次，
// 用户选不到 BOM 外的批次，而不是提交后才被告知选错了。后端 409 守卫原样保留作兜底 (defense in depth)。
interface SettlementBomEligibilityResponse {
  restricted: boolean;
  bomFound: boolean;
  materialTypeIds: string[];
}
// null = 尚未加载/加载失败 → 视为不限制 (回退显示全部批次, 后端 409 仍兜底校验)
const settlementBomEligibility = ref<SettlementBomEligibilityResponse | null>(null);
const bomFilteredMaterialBatchOptions = computed(() => {
  const eligibility = settlementBomEligibility.value;
  if (!eligibility || !eligibility.restricted) {
    return materialBatchOptions.value;
  }
  if (!eligibility.bomFound || eligibility.materialTypeIds.length === 0) {
    return [];
  }
  const allowed = new Set(eligibility.materialTypeIds.map(String));
  return materialBatchOptions.value.filter(
    (batch) => !!batch.materialTypeId && allowed.has(String(batch.materialTypeId)),
  );
});
// Rule 5 (dead-end → next action): 产品无生效 BOM / BOM 无原料明细时，说明原因 + 引导去配置 BOM
const bomFilterBlockedMessage = computed(() => {
  const eligibility = settlementBomEligibility.value;
  if (!eligibility || !eligibility.restricted) return '';
  if (!eligibility.bomFound) {
    return '该产品当前没有生效 BOM，不能核对原料领用；请先配置该产品的 BOM 配方。';
  }
  if (eligibility.materialTypeIds.length === 0) {
    return '该产品当前 BOM 没有原料明细，不能核对原料领用；请先维护 BOM 原料明细。';
  }
  return '';
});
function goConfigBomFromSettlement() {
  router.push('/production/bom');
}
const completeForm = ref<SettlementCompleteForm>({
  actualQuantity: 0,
  semiFinishedOutputQuantity: 0,
  rawMaterialConsumptions: [],
  semiFinishedConsumptions: [],
  workerCount: 0,
  workMinutes: 0,
  laborSegments: [],
  laborDeferredReason: '',
  varianceReason: '',
  otherVarianceReason: '',
});
const completeProductName = computed(() => {
  const r = completeRow.value;
  if (!r) return '';
  return String(r.productTypeName || r.productName || r.productTypeId || '');
});
const completePlanNumber = computed(() => {
  const r = completeRow.value;
  if (!r) return '';
  return String(r.planNumber || r.id || '');
});
const completeAdvisory = computed(() => {
  const r = completeRow.value;
  if (!r) return null;
  return getPlanAdvisory(r) || null;
});
const completePlannedQuantity = computed(() => {
  const r = completeRow.value;
  if (!r) return 0;
  return Number(r.plannedQuantity || 0);
});
const completePlannedUnit = computed(() => {
  const r = completeRow.value;
  return r?.plannedUnit ? String(r.plannedUnit) : null;
});
const completeActualQuantity = computed(() => Number(completeForm.value.actualQuantity || 0));
const completeSemiFinishedOutputQuantity = computed(() => Number(completeForm.value.semiFinishedOutputQuantity || 0));
const completeIsOverPlan = computed(() => {
  const planned = completePlannedQuantity.value;
  return planned > 0 && completeActualQuantity.value > planned;
});
const completeVarianceReasonReady = computed(() => {
  if (!completeIsOverPlan.value) return true;
  const reason = completeForm.value.varianceReason;
  if (!reason) return false;
  if (reason !== '其他') return true;
  return Boolean(completeForm.value.otherVarianceReason.trim());
});

function selectedMaterialBatch(batchId: string): MaterialBatchOption | null {
  return materialBatchOptions.value.find((b) => b.id === batchId) ?? null;
}

function selectedWipForSettlement(wipId: number | null): WipInventoryItem | null {
  if (wipId == null) return null;
  return wipList.value.find((w) => w.id === wipId) ?? null;
}

function materialBatchAvailable(batch: MaterialBatchOption | null): number {
  if (!batch) return 0;
  return Number(batch.currentQuantity ?? batch.quantity ?? 0) || 0;
}

function materialBatchUnit(batch: MaterialBatchOption | null): string {
  return String(batch?.quantityUnit || batch?.unit || '（单位未配置）');
}

function materialBatchLabel(batch: MaterialBatchOption): string {
  const name = batch.materialName || batch.materialTypeName || batch.materialTypeId || '原料';
  return `${name} | ${batch.batchNumber || batch.id} | 可用 ${materialBatchAvailable(batch)}${materialBatchUnit(batch)}`;
}

function rawLineDisabledReason(line: SettlementRawConsumptionForm, index: number): string {
  if (!line.materialBatchId) return `第 ${index + 1} 行原料领用必须选择批次`;
  if (!line.quantity || line.quantity <= 0) return `第 ${index + 1} 行原料领用数量必须大于 0`;
  // 逐道报工行已经由后端按计划、BOM、仓库和当前库存审计，不依赖“当前可选批次”下拉。
  // 报工后批次通常已从物流仓转入生产仓，不能因为它不在物流仓下拉里就丢弃真实领用。
  if (line.source === 'PROCESS_REPORT') return '';
  const batch = selectedMaterialBatch(line.materialBatchId);
  if (!batch) return `第 ${index + 1} 行原料批次不存在或未加载`;
  const available = materialBatchAvailable(batch);
  if (line.quantity > available) return `第 ${index + 1} 行原料领用超出可用量 ${available}${materialBatchUnit(batch)}`;
  return '';
}

function wipLineDisabledReason(line: SettlementWipConsumptionForm, index: number): string {
  if (line.semiFinishedInventoryId == null) return `第 ${index + 1} 行半成品领用必须选择 WIP`;
  const wip = selectedWipForSettlement(line.semiFinishedInventoryId);
  if (!wip) return `第 ${index + 1} 行半成品库存不存在或未加载`;
  if (!line.quantity || line.quantity <= 0) return `第 ${index + 1} 行半成品领用数量必须大于 0`;
  const available = Number(wip.availableQuantity || 0);
  if (line.quantity > available) return `第 ${index + 1} 行半成品领用超出可用量 ${available}${wip.unit || ''}`;
  return '';
}

const firstRawLineError = computed(() => {
  for (let i = 0; i < completeForm.value.rawMaterialConsumptions.length; i += 1) {
    const error = rawLineDisabledReason(completeForm.value.rawMaterialConsumptions[i], i);
    if (error) return error;
  }
  return '';
});
const firstWipLineError = computed(() => {
  for (let i = 0; i < completeForm.value.semiFinishedConsumptions.length; i += 1) {
    const error = wipLineDisabledReason(completeForm.value.semiFinishedConsumptions[i], i);
    if (error) return error;
  }
  return '';
});
const completeSubmitDisabledReason = computed(() => {
  if (completeActualQuantity.value + completeSemiFinishedOutputQuantity.value <= 0) return '请填写有效的成品或半成品实际产量';
  if (!completeVarianceReasonReady.value) return '实际产量超过计划时必须选择差异原因';
  const hasMaterialLines = completeForm.value.rawMaterialConsumptions.length > 0
    || completeForm.value.semiFinishedConsumptions.length > 0;
  if (!hasMaterialLines) return '请至少录入一条原料/半成品实际领用明细';
  if (firstRawLineError.value) return firstRawLineError.value;
  if (firstWipLineError.value) return firstWipLineError.value;
  if (completeForm.value.laborSegments.length === 0 && !completeForm.value.laborDeferredReason) {
    if (!completeForm.value.workerCount || completeForm.value.workerCount <= 0) return '请录入实际人数';
    if (!completeForm.value.workMinutes || completeForm.value.workMinutes <= 0) return '请录入实际工时分钟';
  }
  return '';
});
const completeCanSubmit = computed(
  () => settlementPrefillClean.value || completeSubmitDisabledReason.value === '',
);

function buildSettlementIdempotencyKey(row: TableRow): string {
  // 生成并保存到 settlementIdempotencyKey，确保重试时复用同一 key (防呆 Rule 4)
  const planId = String(row.id || row.planNumber || 'unknown');
  const key = `web-settle-${planId}-${Date.now()}`;
  settlementIdempotencyKey.value = key;
  return key;
}

function addRawConsumptionLine() {
  completeForm.value.rawMaterialConsumptions.push({
    materialBatchId: '',
    quantity: 0,
    note: '',
    source: 'MANUAL',
  });
}

function removeRawConsumptionLine(index: number) {
  completeForm.value.rawMaterialConsumptions.splice(index, 1);
}

function addWipConsumptionLine() {
  completeForm.value.semiFinishedConsumptions.push({
    semiFinishedInventoryId: null,
    quantity: 0,
    note: '',
  });
}

function removeWipConsumptionLine(index: number) {
  completeForm.value.semiFinishedConsumptions.splice(index, 1);
}

function isFactoryWarehouseOption(value: unknown): value is FactoryWarehouseOption {
  return isRecord(value) && typeof value.id === 'string';
}

async function ensureRawWarehouseId(): Promise<string | null> {
  if (rawWarehouseId.value) return rawWarehouseId.value;
  if (!factoryId.value) return null;
  const res = await get<unknown>(`/${factoryId.value}/factory/warehouses`);
  if (!res.success || !Array.isArray(res.data)) {
    ElMessage({ message: res.message || '原料仓列表加载失败，请刷新后重试', type: 'error', duration: 0, showClose: true });
    return null;
  }
  const warehouses = res.data.filter(isFactoryWarehouseOption);
  const raw = warehouses.find((w) => w.code === 'WH-LOG')
    ?? warehouses.find((w) => w.type === 'RAW' || w.type === 'LOGISTICS');
  if (!raw) {
    ElMessage({ message: '未找到原料仓/物流仓，不能核对生产原料领用', type: 'error', duration: 0, showClose: true });
    return null;
  }
  rawWarehouseId.value = raw.id;
  return raw.id;
}

async function loadSettlementInventoryOptions(row: TableRow) {
  if (!factoryId.value) return;
  materialBatchListLoading.value = true;
  wipListLoading.value = true;
  settlementBomEligibility.value = null;
  const planId = row.id ? String(row.id) : '';
  try {
    const warehouseId = await ensureRawWarehouseId();
    if (!warehouseId) {
      materialBatchOptions.value = [];
      wipList.value = [];
      return;
    }
    const bomEligibilityPromise = planId
      ? get<SettlementBomEligibilityResponse>(
          `/${factoryId.value}/production-plans/${planId}/settlement-bom-eligibility`,
        )
      : Promise.resolve(null);
    const [materialRes, wipRes, bomRes] = await Promise.allSettled([
      get<unknown>(`/${factoryId.value}/material-batches/status/AVAILABLE`, {
        params: {
          warehouseId,
          size: 200,
        },
      }),
      listAvailableWip(factoryId.value),
      bomEligibilityPromise,
    ]);
    if (materialRes.status === 'fulfilled' && materialRes.value.success) {
      materialBatchOptions.value = extractMaterialBatchRows(materialRes.value.data)
        .filter((batch) => materialBatchAvailable(batch) > 0);
    } else {
      materialBatchOptions.value = [];
      ElMessage({ message: '原料可用批次加载失败，请刷新后重试', type: 'error', duration: 0, showClose: true });
    }
    if (wipRes.status === 'fulfilled' && wipRes.value.success && Array.isArray(wipRes.value.data)) {
      wipList.value = wipRes.value.data.filter((wip) => Number(wip.availableQuantity || 0) > 0);
    } else {
      wipList.value = [];
      ElMessage({ message: '半成品可用库存加载失败，请刷新后重试', type: 'error', duration: 0, showClose: true });
    }
    // 防呆 Rule 1: BOM 预过滤加载失败时不阻塞结单 —— 回退为不限制显示全部批次，
    // 后端结单提交守卫仍会 409 兜底校验 BOM (defense in depth)，只是失去"预先显示边界"的效果。
    if (bomRes.status === 'fulfilled' && bomRes.value && bomRes.value.success && bomRes.value.data) {
      settlementBomEligibility.value = bomRes.value.data;
    } else {
      settlementBomEligibility.value = null;
      if (planId) {
        ElMessage({
          message: '原料 BOM 预过滤加载失败，暂显示全部批次；提交时仍会校验 BOM，请核对无误后再提交。',
          type: 'warning',
          duration: 0,
          showClose: true,
        });
      }
    }
  } finally {
    materialBatchListLoading.value = false;
    wipListLoading.value = false;
  }
}

function buildRawConsumptionPayload() {
  return completeForm.value.rawMaterialConsumptions.map((line) => {
    const batch = selectedMaterialBatch(line.materialBatchId);
    return {
      materialBatchId: line.materialBatchId,
      productTypeId: line.productTypeId || null,
      materialTypeId: line.materialTypeId || batch?.materialTypeId || null,
      batchNumber: line.batchNumber || batch?.batchNumber || null,
      quantity: line.quantity,
      unit: line.unit || materialBatchUnit(batch),
      warehouseId: line.warehouseId || batch?.warehouseId || null,
      note: line.note || null,
    };
  });
}

function buildWipConsumptionPayload() {
  return completeForm.value.semiFinishedConsumptions.map((line) => {
    const wip = selectedWipForSettlement(line.semiFinishedInventoryId);
    return {
      semiFinishedInventoryId: line.semiFinishedInventoryId,
      batchNumber: wip?.intermediateBatchNo || null,
      quantity: line.quantity,
      unit: wip?.unit || null,
      note: line.note || null,
    };
  });
}

/**
 * Phase 2A: 从逐道报工 derive 出结单汇总 + 审计, 灌入 completeForm。
 * 报工原料行由后端按计划/BOM/仓库/库存审计后原样带入，不再依赖当前物流仓下拉。
 * 批次报工后已进入生产仓是正常料流，不能把“不在物流仓下拉”误判为批次不存在。
 */
async function applySettlementPrefill(row: TableRow) {
  settlementPrefillApplied.value = false;
  settlementPrefillIssues.value = [];
  settlementPrefillCleanFromServer.value = false;
  settlementTerminalOutputs.value = [];
  if (!factoryId.value) return;
  settlementPrefillLoading.value = true;
  try {
    const res = await get<SettlementPrefillResponse>(
      `/${factoryId.value}/production-plans/${row.id}/settlement-prefill`,
    );
    if (!res.success || !res.data) {
      // 预填失败不阻塞结单 — 退回手填, 给出提示 (BLOCKER, 让人确认手填)
      settlementPrefillIssues.value = [{
        code: 'PREFILL_LOAD_FAILED',
        message: res.message || '自动预填加载失败，请手工录入结单数据。',
        severity: 'BLOCKER',
      }];
      return;
    }
    const issues = res.data.audit?.issues ?? [];
    const prefill = res.data.prefill;
    const carriedIssues: SettlementPrefillIssue[] = [...issues];
    let serverClean = res.data.audit?.clean ?? false;

    if (prefill) {
      settlementTerminalOutputs.value = (prefill.terminalOutputs ?? [])
        .map((output) => ({
          productTypeId: String(output.productTypeId || ''),
          batchNumber: String(output.batchNumber || ''),
          quantity: Number(output.quantity || 0),
          unit: String(output.unit || ''),
        }))
        .filter((output) => output.quantity > 0);
      // 实际产量 (derive 不出时留空, 用户手填)
      if (prefill.actualFinishedQuantity != null) {
        completeForm.value.actualQuantity = Number(prefill.actualFinishedQuantity);
      }
      if (prefill.actualSemiFinishedQuantity != null) {
        completeForm.value.semiFinishedOutputQuantity = Number(prefill.actualSemiFinishedQuantity);
      }
      // 差异原因 (自动"无显著差异"或留空)
      if (prefill.quantityVarianceReason) {
        const matched = SETTLEMENT_VARIANCE_REASON_OPTIONS.some(
          (o) => o.value === prefill.quantityVarianceReason,
        );
        if (matched) {
          completeForm.value.varianceReason = prefill.quantityVarianceReason;
        } else {
          completeForm.value.varianceReason = '其他';
          completeForm.value.otherVarianceReason = prefill.quantityVarianceReason;
        }
      }
      // 原料领用: 后端审计后的逐道报工是计划结算事实，完整保留批次与单位元数据。
      const rawLines = prefill.rawMaterialConsumptions ?? [];
      completeForm.value.rawMaterialConsumptions = [];
      for (const line of rawLines) {
        const batchId = line.materialBatchId ? String(line.materialBatchId) : '';
        const qty = Number(line.quantity || 0);
        if (!batchId || qty <= 0) continue;
        completeForm.value.rawMaterialConsumptions.push({
          materialBatchId: batchId,
          productTypeId: line.productTypeId ? String(line.productTypeId) : null,
          materialTypeId: line.materialTypeId ? String(line.materialTypeId) : null,
          batchNumber: line.batchNumber ? String(line.batchNumber) : null,
          unit: line.unit ? String(line.unit) : null,
          warehouseId: line.warehouseId ? String(line.warehouseId) : null,
          quantity: qty,
          note: line.note || '自动带入自逐道报工',
          source: 'PROCESS_REPORT',
        });
      }
      // 人效: 保留逐道工时段用于真实结算，同时显示汇总，不再伪造成一条“PC文员汇总”。
      const segs = prefill.laborSegments ?? [];
      completeForm.value.laborSegments = segs
        .map((s) => ({
          workerId: s.workerId ?? null,
          workerName: s.workerName ?? null,
          workType: s.workType ?? null,
          minutes: Number(s.minutes || 0),
          headcount: Number(s.headcount || 1),
          note: s.note ?? null,
        }))
        .filter((s) => s.minutes > 0 && s.headcount > 0);
      completeForm.value.laborDeferredReason = prefill.laborDeferredReason || '';
      if (segs.length > 0) {
        let totalMinutes = 0;
        let maxHeadcount = 0;
        for (const s of segs) {
          totalMinutes += Number(s.minutes || 0);
          maxHeadcount = Math.max(maxHeadcount, Number(s.headcount || 0));
        }
        if (totalMinutes > 0) completeForm.value.workMinutes = totalMinutes;
        if (maxHeadcount > 0) completeForm.value.workerCount = maxHeadcount;
      }
    }
    settlementPrefillIssues.value = carriedIssues;
    settlementPrefillCleanFromServer.value = serverClean;
    settlementPrefillApplied.value = true;
  } catch (error: unknown) {
    settlementPrefillIssues.value = [{
      code: 'PREFILL_ERROR',
      message: '自动预填加载异常，请手工录入结单数据。',
      severity: 'BLOCKER',
    }];
    console.error('[结单预填失败]', error);
  } finally {
    settlementPrefillLoading.value = false;
  }
}

async function handleComplete(row: TableRow) {
  if (actionLoading.value) return;
  completeRow.value = row;
  // 幂等 key 在打开弹框时生成一次，submitComplete 直接复用，重试不会产生新 key (防呆 Rule 4)
  buildSettlementIdempotencyKey(row);
  // 默认填充计划数量, 便于一键提交; 用户可改
  completeForm.value = {
    actualQuantity: Number(row.plannedQuantity || 0),
    semiFinishedOutputQuantity: 0,
    rawMaterialConsumptions: [],
    semiFinishedConsumptions: [],
    workerCount: Number(row.estimatedWorkers || 0),
    workMinutes: 0,
    laborSegments: [],
    laborDeferredReason: '',
    varianceReason: '',
    otherVarianceReason: '',
  };
  settlementPrefillApplied.value = false;
  settlementPrefillIssues.value = [];
  settlementPrefillCleanFromServer.value = false;
  settlementTerminalOutputs.value = [];
  completeDialogVisible.value = true;
  // 正常结单只拉取逐道报工汇总；仅存在阻塞异常时，才加载可选批次/WIP供人工补录。
  await applySettlementPrefill(row);
  if (settlementBlockerIssues.value.length > 0) {
    await loadSettlementInventoryOptions(row);
  }
  // 防呆: 预填完成后才快照 (自动带入的数据不算"用户改动"), 之后用户手改才计入 dirty
  completeFormSnapshot.value = JSON.stringify(completeForm.value);
}

// 防呆: 核对结单 dialog 关闭前若表单已被用户改动 (相对预填完成时的快照) 二次确认
const completeFormSnapshot = ref('');
function completeFormIsDirty(): boolean {
  return JSON.stringify(completeForm.value) !== completeFormSnapshot.value;
}
async function requestCloseCompleteDialog() {
  if (await confirmDiscardIfDirty(completeFormIsDirty())) {
    completeDialogVisible.value = false;
  }
}
async function handleCompleteDialogBeforeClose(done: () => void) {
  if (await confirmDiscardIfDirty(completeFormIsDirty())) done();
}

async function submitComplete() {
  if (!completeRow.value) return;
  if (!completeCanSubmit.value) {
    ElMessage({
      message: completeSubmitDisabledReason.value || '请先完成结单核对项',
      type: 'error',
      duration: 0,
      showClose: true,
    });
    return;
  }
  actionLoading.value = true;
  try {
    const payload = settlementPrefillClean.value
      ? {
          idempotencyKey: settlementIdempotencyKey.value,
          confirm: true,
        }
      : {
          // Legacy-compatible exception override path. The normal path above never
          // echoes server facts back; only a human-resolved blocker uses these fields.
          idempotencyKey: settlementIdempotencyKey.value,
          confirm: false,
          actualFinishedQuantity: completeActualQuantity.value,
          actualSemiFinishedQuantity: Number(completeForm.value.semiFinishedOutputQuantity || 0),
          quantityUnit: completeRow.value.unit || completeRow.value.quantityUnit || null,
          quantityVarianceReason: completeForm.value.varianceReason === '其他'
            ? completeForm.value.otherVarianceReason.trim()
            : completeForm.value.varianceReason,
          rawMaterialConsumptions: buildRawConsumptionPayload(),
          semiFinishedConsumptions: buildWipConsumptionPayload(),
          auxiliaryConsumptions: [] as Array<Record<string, unknown>>,
          laborDeferredReason: completeForm.value.laborDeferredReason || null,
          laborSegments: completeForm.value.laborSegments.length > 0
            ? completeForm.value.laborSegments
            : completeForm.value.laborDeferredReason
              ? []
              : [{
                  workerName: 'PC文员补录',
                  workType: '生产结单异常补录',
                  minutes: Number(completeForm.value.workMinutes || 0),
                  headcount: Number(completeForm.value.workerCount || 1),
                }],
        };
    const response = await post(
      `/${factoryId.value}/production-plans/${completeRow.value.id}/settle`,
      payload,
    );
    if (response.success) {
      ElMessage.success(response.message || '生产结单已提交，下一步请仓库确认入库');
      completeDialogVisible.value = false;
      searchForm.value.status = 'COMPLETED';
      loadData();
    } else {
      ElMessage({ message: response.message || '结单提交失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (error: unknown) {
    if (error !== 'cancel') console.error('[提交失败]', error);
    handleCatchError(error, '结单提交失败，请检查网络');
  } finally {
    actionLoading.value = false;
  }
}

// ==================== 仓库确认入库 dialog (6.12 中转仓) ====================
const RECEIPT_VARIANCE_REASON_OPTIONS = [
  { value: '仓库实收短少', label: '仓库实收短少' },
  { value: '生产少交', label: '生产少交' },
  { value: '称重误差', label: '称重误差' },
  { value: '破损/返工待处理', label: '破损/返工待处理' },
  { value: '其他', label: '其他（请补充说明）' },
];
const RECEIPT_RESPONSIBILITY_OPTIONS = [
  { value: 'PRODUCTION', label: '生产侧处理' },
  { value: 'WAREHOUSE', label: '仓库侧处理' },
  { value: 'WEIGHING_ERROR', label: '称重误差' },
];
const CLEARING_REASON_OPTIONS = [
  { value: '生产侧已处理', label: '生产侧已补产/承担差异' },
  { value: '仓库侧已处理', label: '仓库侧已盘点/承担差异' },
  { value: '称重误差已复核', label: '称重误差已复核' },
  { value: '财务已确认差异', label: '财务已确认差异' },
  { value: '其他', label: '其他（请补充说明）' },
];
const receiptDialogVisible = ref(false);
const receiptLoading = ref(false);
const receiptRow = ref<TableRow | null>(null);
// 幂等 key 在打开弹框时一次性生成，重试提交复用同一 key (防呆 Rule 4)
const receiptIdempotencyKey = ref('');
const receiptSettlement = ref<ProductionSettlementStatus | null>(null);
const receiptForm = ref({
  receivedQuantity: 0,
  quantityUnit: '',
  varianceReason: '',
  otherVarianceReason: '',
  responsibilitySide: '',
  varianceNote: '',
});
const clearingDialogVisible = ref(false);
const clearingLoading = ref(false);
const clearingRow = ref<TableRow | null>(null);
const clearingSettlement = ref<ProductionSettlementStatus | null>(null);
const clearingForm = ref({
  clearingReason: '',
  otherClearingReason: '',
  clearingNote: '',
});

function getSettlementStatus(row: TableRow): ProductionSettlementStatus | null {
  return settlementStatusMap.value[String(row.id)] ?? null;
}

function postingStatusText(status?: string | null): string {
  const map: Record<string, string> = {
    PENDING_WAREHOUSE_RECEIPT: '待仓库确认',
    PENDING_CLEARING: '中转挂账',
    POSTED_WITH_TOLERANCE: '已入库(容差)',
    POSTED: '已入库',
    PENDING_POSTING: '待过账',
  };
  return map[String(status || '')] || String(status || '未结单');
}

function postingStatusType(status?: string | null): string {
  const map: Record<string, string> = {
    PENDING_WAREHOUSE_RECEIPT: 'warning',
    PENDING_CLEARING: 'danger',
    POSTED_WITH_TOLERANCE: 'success',
    POSTED: 'success',
    PENDING_POSTING: 'info',
  };
  return map[String(status || '')] || 'info';
}

function canConfirmReceipt(row: TableRow): boolean {
  const settlement = getSettlementStatus(row);
  return canConfirmReceiptWrite.value
    && String(row.status || '').toUpperCase() === 'COMPLETED'
    && settlement?.postingStatus === 'PENDING_WAREHOUSE_RECEIPT';
}

function canClearTransit(row: TableRow): boolean {
  const settlement = getSettlementStatus(row);
  return canConfirmReceiptWrite.value
    && String(row.status || '').toUpperCase() === 'COMPLETED'
    && settlement?.postingStatus === 'PENDING_CLEARING';
}

const receiptProductName = computed(() => {
  const r = receiptRow.value;
  if (!r) return '';
  return String(r.productTypeName || r.productName || r.productTypeId || '');
});
const receiptPlanNumber = computed(() => {
  const r = receiptRow.value;
  if (!r) return '';
  return String(r.planNumber || r.id || '');
});
const receiptReportedQuantity = computed(() =>
  Number(receiptSettlement.value?.actualFinishedQuantity || 0)
);
const receiptReceivedQuantity = computed(() =>
  Number(receiptForm.value.receivedQuantity || 0)
);
const receiptUnit = computed(() =>
  receiptForm.value.quantityUnit || receiptSettlement.value?.quantityUnit || '件'
);
const receiptTolerance = computed(() => {
  const unit = receiptUnit.value.toLowerCase();
  if (unit === 'kg' || unit === '公斤' || unit === '千克') return 10;
  if (unit === 'g' || unit === '克') return 10000;
  return 0;
});
const receiptVarianceQuantity = computed(() =>
  receiptReportedQuantity.value - receiptReceivedQuantity.value
);
const receiptNeedsReason = computed(() => {
  const variance = Math.abs(receiptVarianceQuantity.value);
  return variance > 0 && variance > receiptTolerance.value;
});
const receiptSubmitDisabledReason = computed(() => {
  if (!receiptSettlement.value) return '请先加载生产结单状态';
  if (!receiptReceivedQuantity.value || receiptReceivedQuantity.value <= 0) return '请输入仓库实际收到数量';
  if (receiptReceivedQuantity.value > receiptReportedQuantity.value) return '仓库实收不能超过生产报产，请先让生产修正结单';
  if (receiptNeedsReason.value && !receiptForm.value.varianceReason) return '超出容差的差异必须选择原因';
  if (receiptNeedsReason.value && !receiptForm.value.responsibilitySide) return '超出容差的差异必须选择生产侧、仓库侧或称重误差';
  if (receiptNeedsReason.value && receiptForm.value.responsibilitySide === 'PENDING') return '责任侧不能为待核对，请先明确归属';
  if (receiptNeedsReason.value && receiptForm.value.varianceReason === '其他' && !receiptForm.value.otherVarianceReason.trim()) {
    return '请选择“其他”时必须补充说明';
  }
  return '';
});
const receiptCanSubmit = computed(() => receiptSubmitDisabledReason.value === '');
const clearingProductName = computed(() => {
  const r = clearingRow.value;
  if (!r) return '';
  return String(r.productTypeName || r.productName || r.productTypeId || '');
});
const clearingPlanNumber = computed(() => {
  const r = clearingRow.value;
  if (!r) return '';
  return String(r.planNumber || r.id || '');
});
const clearingDisabledReason = computed(() => {
  if (!clearingSettlement.value) return '请先加载中转挂账状态';
  if (!clearingForm.value.clearingReason) return '请选择清账原因';
  if (clearingForm.value.clearingReason === '其他' && !clearingForm.value.otherClearingReason.trim()) {
    return '选择“其他”时必须补充清账原因';
  }
  return '';
});
const clearingCanSubmit = computed(() => clearingDisabledReason.value === '');

function buildReceiptIdempotencyKey(row: TableRow): string {
  // 生成并保存到 receiptIdempotencyKey，确保重试时复用同一 key (防呆 Rule 4)
  const planId = String(row.id || row.planNumber || 'unknown');
  const key = `web-receipt-${planId}-${Date.now()}`;
  receiptIdempotencyKey.value = key;
  return key;
}

async function handleWarehouseReceipt(row: TableRow) {
  if (!factoryId.value || actionLoading.value) return;
  receiptRow.value = row;
  // 幂等 key 在打开弹框时生成一次，submitWarehouseReceipt 直接复用，重试不会产生新 key (防呆 Rule 4)
  buildReceiptIdempotencyKey(row);
  receiptLoading.value = true;
  receiptDialogVisible.value = true;
  try {
    const res = await getProductionSettlement(factoryId.value, String(row.id));
    if (!res.success || !res.data) {
      ElMessage({ message: res.message || '加载结单状态失败', type: 'error', duration: 0, showClose: true });
      receiptDialogVisible.value = false;
      return;
    }
    receiptSettlement.value = res.data;
    receiptForm.value = {
      receivedQuantity: Number(res.data.actualFinishedQuantity || 0),
      quantityUnit: String(row.unit || row.quantityUnit || ''),
      varianceReason: '',
      otherVarianceReason: '',
      responsibilitySide: '',
      varianceNote: '',
    };
  } catch (error) {
    handleCatchError(error, '加载结单状态失败');
    receiptDialogVisible.value = false;
  } finally {
    receiptLoading.value = false;
  }
}

async function submitWarehouseReceipt() {
  if (!factoryId.value || !receiptRow.value || !receiptSettlement.value) return;
  if (!receiptCanSubmit.value) {
    ElMessage({ message: receiptSubmitDisabledReason.value, type: 'error', duration: 0, showClose: true });
    return;
  }
  actionLoading.value = true;
  try {
    const varianceReason = receiptForm.value.varianceReason === '其他'
      ? receiptForm.value.otherVarianceReason.trim()
      : receiptForm.value.varianceReason;
    const res = await confirmProductionWarehouseReceipt(factoryId.value, String(receiptRow.value.id), {
      idempotencyKey: receiptIdempotencyKey.value,
      receivedQuantity: receiptReceivedQuantity.value,
      quantityUnit: receiptUnit.value,
      varianceReason: varianceReason || null,
      responsibilitySide: receiptNeedsReason.value ? receiptForm.value.responsibilitySide : null,
      varianceNote: receiptForm.value.varianceNote || null,
    });
    if (res.success) {
      ElMessage.success(res.data?.message || res.message || '仓库已确认入库');
      receiptDialogVisible.value = false;
      await loadData();
    } else {
      ElMessage({ message: res.message || '仓库确认失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (error) {
    handleCatchError(error, '仓库确认失败');
  } finally {
    actionLoading.value = false;
  }
}

async function handleTransitClearing(row: TableRow) {
  if (!factoryId.value || actionLoading.value) return;
  clearingRow.value = row;
  clearingSettlement.value = getSettlementStatus(row);
  clearingForm.value = {
    clearingReason: '',
    otherClearingReason: '',
    clearingNote: '',
  };
  clearingLoading.value = true;
  clearingDialogVisible.value = true;
  try {
    const res = await getProductionSettlement(factoryId.value, String(row.id));
    if (!res.success || !res.data) {
      ElMessage({ message: res.message || '加载中转挂账状态失败', type: 'error', duration: 0, showClose: true });
      clearingDialogVisible.value = false;
      return;
    }
    clearingSettlement.value = res.data;
    if (res.data.postingStatus !== 'PENDING_CLEARING') {
      ElMessage({
        message: `当前状态为 ${postingStatusText(res.data.postingStatus)}，没有待清账的中转挂账`,
        type: 'warning',
        duration: 0,
        showClose: true,
      });
      clearingDialogVisible.value = false;
    }
  } catch (error) {
    handleCatchError(error, '加载中转挂账状态失败');
    clearingDialogVisible.value = false;
  } finally {
    clearingLoading.value = false;
  }
}

async function submitTransitClearing() {
  if (!factoryId.value || !clearingRow.value || !clearingSettlement.value) return;
  if (!clearingCanSubmit.value) {
    ElMessage({ message: clearingDisabledReason.value, type: 'error', duration: 0, showClose: true });
    return;
  }
  actionLoading.value = true;
  try {
    const clearingReason = clearingForm.value.clearingReason === '其他'
      ? clearingForm.value.otherClearingReason.trim()
      : clearingForm.value.clearingReason;
    const res = await clearProductionTransitLedger(factoryId.value, String(clearingRow.value.id), {
      clearingReason,
      clearingNote: clearingForm.value.clearingNote || null,
    });
    if (res.success) {
      ElMessage.success(res.data?.message || res.message || '中转挂账已清账');
      const rowId = String(clearingRow.value.id);
      if (settlementStatusMap.value[rowId]) {
        settlementStatusMap.value[rowId] = {
          ...settlementStatusMap.value[rowId],
          postingStatus: res.data?.postingStatus || 'POSTED',
          postingMessage: res.data?.message || res.message || '中转挂账已清账',
        };
      }
      clearingDialogVisible.value = false;
      await loadData();
    } else {
      ElMessage({ message: res.message || '中转挂账清账失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (error) {
    handleCatchError(error, '中转挂账清账失败');
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
// 防呆 Rule 2: 取消 dialog 标题需同时带品名+单号 (与编辑 dialog 一致), 避免多个同品名计划混淆
const cancelPlanNumber = computed(() => {
  const r = cancelRow.value;
  if (!r) return '';
  return String(r.planNumber || r.id || '');
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

// ==================== 编辑生产计划 dialog (更多→编辑 dead-stub 修复) ====================
// 之前「更多→编辑」只弹一个 debug toast (`Action: edit`), 不开任何表单 — 厂长创建计划后
// 无法改计划日期/数量, 是一个完全没接线的死桩。这里用独立的轻量 dialog 而非复用复杂的
// 「新建计划」dialog (那个 dialog 的以销定产多产品批量建单逻辑跟「编辑单张已存在的计划」
// 语义冲突 — 编辑时改 SO/产品行选择不应该走 batch-from-so 去新建计划), 只暴露真正
// 编辑后仍讲得通的字段: 计划日期(核心需求)/计划数量/预计完成日期/预计工人数/指派主管/备注。
//
// 可编辑状态: 只有 PENDING / PREPARED — 与后端 ProductionPlanServiceImpl#updateProductionPlan
// 的状态守卫严格一致(该方法对其他任何状态一律 409)。注意: PAUSED 语义上是"曾经 IN_PROGRESS
// 后暂停", 不是"尚未开始", 编辑数量/日期跟"生产已开始不可编辑"的防呆意图冲突, 因此不放行
// (即使某些早期草案把 PAUSED 也算作可编辑 — 那是对 PAUSED 语义的误判)。
const EDITABLE_PLAN_STATUSES = new Set(['PENDING', 'PREPARED']);

function blockedEditMessage(status: string, isLocked?: boolean, lockReason?: string): string {
  if (isLocked) {
    return `计划已锁定${lockReason ? `（${lockReason}）` : ''}，请先在「更多」中解锁后再编辑`;
  }
  switch (status) {
    case 'IN_PROGRESS':
    case 'PAUSED':
      return '生产已开始的计划不可编辑，如需调整数量/日期请先「暂停/停止生产」或联系车间主管';
    case 'COMPLETED':
      return '已完成的计划不可编辑';
    case 'CANCELLED':
      return '已取消的计划不可编辑';
    case 'PENDING_APPROVAL':
      return '该计划正在审批流程中，暂不可编辑';
    default:
      return `当前状态 (${status}) 不可编辑，请刷新列表查看最新状态`;
  }
}

const editDialogVisible = ref(false);
const editDialogLoading = ref(false);
const editingPlanId = ref('');
// 编辑弹窗标题用的上下文 (防呆 Rule 2: 必须显示品名+单号, 不能是空表单)
const editingPlanNumber = ref('');
const editingProductName = ref('');
const editForm = ref({
  plannedDate: '',
  plannedQuantity: 0,
  expectedCompletionDate: '',
  estimatedWorkers: undefined as number | undefined,
  assignedSupervisorId: '' as string | number | undefined,
  notes: '',
});
// 编辑提交时用完整详情 hydrate PUT payload (而不是只传编辑过的几个字段) —
// CreateProductionPlanRequest 里若干字段有 Java 默认值 (sourceType=MANUAL /
// planType=FROM_INVENTORY / priority=5 / isMixedBatch=false ...), 编辑请求如果漏传
// 这些字段, 反序列化会落到默认值, updateEntity 的 null-guard 挡不住"非 null 但是默认值"
// 的情况 —— 会把已有计划的来源类型/计划类型/优先级悄悄冲掉。所以先拉完整详情原样回填,
// 再只覆盖真正编辑过的字段。
let editingPlanDetail: TableRow | null = null;

async function handleEditPlan(row: TableRow) {
  const status = String(row.status || '');
  const rowLocked = Boolean(row.isLocked);
  // Rule 1 (防呆): 先用列表已有的行数据快速判断, 不等网络往返就能挡掉明显不可编辑的行。
  if (!EDITABLE_PLAN_STATUSES.has(status) || rowLocked) {
    ElMessageBox.alert(blockedEditMessage(status, rowLocked, row.lockReason ? String(row.lockReason) : undefined), '不可编辑', {
      confirmButtonText: '我知道了',
    }).catch(() => { /* dismiss */ });
    return;
  }

  editDialogLoading.value = true;
  editingPlanId.value = String(row.id);
  try {
    const response = await get(`/${factoryId.value}/production-plans/${row.id}`);
    if (!response.success || !response.data) {
      ElMessage({ message: response.message || '加载生产计划详情失败', type: 'error', duration: 0, showClose: true });
      return;
    }
    const plan = response.data as TableRow;
    // 防御性二次校验: 列表行数据可能已过期 (并发操作/另一个 tab 锁定/开工), 以刚拉取的
    // 详情为准, 而不是只信列表缓存 (Rule 1: 提交前也要挡, 不只是点击那一刻)。
    const freshStatus = String(plan.status || '');
    const freshLocked = Boolean(plan.isLocked);
    if (!EDITABLE_PLAN_STATUSES.has(freshStatus) || freshLocked) {
      ElMessageBox.alert(
        blockedEditMessage(freshStatus, freshLocked, plan.lockReason ? String(plan.lockReason) : undefined),
        '不可编辑',
        { confirmButtonText: '我知道了' }
      ).catch(() => { /* dismiss */ });
      return;
    }

    editingPlanDetail = plan;
    editingPlanNumber.value = String(plan.planNumber || plan.id || '');
    editingProductName.value = String(plan.productTypeName || plan.productName || plan.productTypeId || '');
    editForm.value = {
      plannedDate: String(plan.plannedDate || ''),
      plannedQuantity: Number(plan.plannedQuantity) || 0,
      expectedCompletionDate: plan.expectedCompletionDate ? String(plan.expectedCompletionDate) : '',
      estimatedWorkers: plan.estimatedWorkers !== null && plan.estimatedWorkers !== undefined ? Number(plan.estimatedWorkers) : undefined,
      assignedSupervisorId: plan.assignedSupervisorId !== null && plan.assignedSupervisorId !== undefined ? plan.assignedSupervisorId : '',
      notes: String(plan.notes || ''),
    };
    editDialogVisible.value = true;
  } catch (e) {
    handleCatchError(e, '加载生产计划详情失败');
  } finally {
    editDialogLoading.value = false;
  }
}

async function submitEditPlan() {
  if (!editingPlanDetail || !factoryId.value) return;
  if (!editForm.value.plannedDate) {
    ElMessage.warning('请选择计划生产日');
    return;
  }
  if (!editForm.value.plannedQuantity && editingPlanDetail.sourceType !== 'SAFETY_STOCK') {
    ElMessage.warning('请输入计划数量');
    return;
  }
  editDialogLoading.value = true;
  try {
    // Hydrate 完整字段(避免 Java DTO 默认值冲掉未编辑的 sourceType/planType/priority 等),
    // 再用表单编辑过的字段覆盖。
    const src = editingPlanDetail;
    const payload = {
      productTypeId: src.productTypeId,
      plannedQuantity: editForm.value.plannedQuantity,
      plannedDate: editForm.value.plannedDate,
      expectedCompletionDate: editForm.value.expectedCompletionDate || undefined,
      customerOrderNumber: src.customerOrderNumber || undefined,
      priority: src.priority,
      estimatedMaterialCost: src.estimatedMaterialCost,
      estimatedLaborCost: src.estimatedLaborCost,
      estimatedEquipmentCost: src.estimatedEquipmentCost,
      estimatedOtherCost: src.estimatedOtherCost,
      notes: editForm.value.notes || undefined,
      planType: src.planType,
      estimatedWorkers: editForm.value.estimatedWorkers,
      assignedSupervisorId: editForm.value.assignedSupervisorId || undefined,
      sourceType: src.sourceType,
      sourceOrderId: src.sourceOrderId || undefined,
      sourceOrderIds: Array.isArray(src.sourceOrderIds) ? src.sourceOrderIds : undefined,
      sourceOrderItemId: src.sourceOrderItemId || undefined,
      sourceCustomerName: src.sourceCustomerName || undefined,
      processName: src.processName || undefined,
      batchDate: src.batchDate || undefined,
      aiConfidence: src.aiConfidence,
      forecastReason: src.forecastReason || undefined,
      isMixedBatch: src.isMixedBatch,
      mixedBatchType: src.mixedBatchType || undefined,
      relatedOrders: Array.isArray(src.relatedOrders) ? src.relatedOrders : undefined,
      skipProcessReporting: src.skipProcessReporting,
    };
    const response = await put(`/${factoryId.value}/production-plans/${editingPlanId.value}`, payload);
    if (response.success) {
      ElMessage.success('生产计划已更新');
      editDialogVisible.value = false;
      loadData();
    } else {
      ElMessage({ message: response.message || '更新失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    handleCatchError(e, '更新生产计划失败');
  } finally {
    editDialogLoading.value = false;
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

// ==================== Phase 2: BY_STOCK 小结 / 停产 ====================
// 逐行 loading state，防止双击（防呆 Rule 4）
const interimSettleLoadingId = ref<string | null>(null);
const reverseInterimSettleLoadingId = ref<string | null>(null);
const stopProductionLoadingId = ref<string | null>(null);

async function handleInterimSettle(row: TableRow) {
  const planId = String(row.id);
  if (interimSettleLoadingId.value) return;
  // 防呆 Rule 1/2: 小结是真实入库+扣料操作 (非只读), 之前一键无确认就执行 — 加轻量 confirm
  // 带品名+单号+大白话后果说明, 避免误触
  const productLabel = String(row.productTypeName || row.productName || row.productTypeId || '');
  const planNumberLabel = String(row.planNumber || planId);
  const planLabel = productLabel ? `${productLabel} (${planNumberLabel})` : planNumberLabel;
  try {
    await ElMessageBox.confirm(
      `小结「${planLabel}」将按当前已录入数量扣减已投入原料并产出半成品/成品入库，计划继续挂起。确认执行？`,
      '确认小结',
      { type: 'warning', confirmButtonText: '确认小结', cancelButtonText: '取消' }
    );
  } catch {
    return; // 用户取消
  }
  if (interimSettleLoadingId.value) return;
  interimSettleLoadingId.value = planId;
  try {
    const res = await interimSettle(factoryId.value, planId);
    if (res.success) {
      ElMessage.success(res.data?.message as string || res.message || '已小结，计划继续挂起');
      loadData();
    } else {
      ElMessage({ message: res.message || '小结失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: unknown) {
    const errMsg = (e as any)?.response?.data?.message || '小结失败';
    ElMessage({ message: errMsg, type: 'error', duration: 0, showClose: true });
  } finally {
    interimSettleLoadingId.value = null;
  }
}

/**
 * 撤销小结-申请 (最近一次): 治理 = 申请→审批→执行。此处仅创建撤销申请 (待审批, 零库存副作用)。
 * 防呆 4-位一体: prompt 强制填 reason (带计划名/单号 context) + 后端 message 原样 sticky (含超时/下游 blocking 引用)。
 */
async function handleReverseInterimSettle(row: TableRow) {
  const planId = String(row.id);
  // 防呆 Rule 2 (UX 修复): 之前 planLabel 落回 row.planNumber, 再与后面拼接的 (${row.planNumber})
  // 撞在一起 → 弹窗标题/正文变成「PLAN-XXX」(PLAN-XXX) 计划号显示两次, 没有品名。改用与
  // handleInterimSettle (确认小结) 同款拼法: 品名 (计划号)，没有品名时才退化为纯计划号。
  const productLabel = String(row.productTypeName || row.productName || row.productTypeId || '');
  const planNumberLabel = String(row.planNumber || planId);
  const planLabel = productLabel ? `${productLabel} (${planNumberLabel})` : planNumberLabel;
  let reason = '';
  try {
    const { value } = await ElMessageBox.prompt(
      `申请撤销「${planLabel}」的最近一次小结。撤销需审批，通过后才逆转入库并还回消耗（误结的逐道行恢复可编辑）；仅限小结后24小时内。请填写撤销原因：`,
      '撤销小结-申请',
      {
        type: 'warning',
        confirmButtonText: '提交申请',
        cancelButtonText: '取消',
        inputPlaceholder: '如：产量录错 / 批次选错',
        inputValidator: (v: string) => (v && v.trim() ? true : '请填写撤销原因'),
      }
    );
    reason = (value || '').trim();
  } catch {
    return; // 用户取消
  }
  if (reverseInterimSettleLoadingId.value) return;
  reverseInterimSettleLoadingId.value = planId;
  try {
    const res = await requestReverseInterimSettle(factoryId.value, planId, reason);
    if (res.success) {
      ElMessage.success(res.message || '已提交撤销申请，待审批');
      loadData();
    } else {
      // sticky + 后端原样 message (含超时 WINDOW_EXPIRED / 下游已消耗 blocking 引用)
      ElMessage({ message: res.message || '撤销申请失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: unknown) {
    const errMsg = (e as any)?.response?.data?.message || '撤销申请失败';
    ElMessage({ message: errMsg, type: 'error', duration: 0, showClose: true });
  } finally {
    reverseInterimSettleLoadingId.value = null;
  }
}

// ==================== 撤销小结审批 (STOCKTAKE_APPROVAL_ROLES) ====================
const reversalApprovalVisible = ref(false);
const reversalRequests = ref<InterimSettleReversalRequest[]>([]);
const reversalApprovalLoading = ref(false);
const reversalActingId = ref<string | null>(null);

async function openReversalApproval() {
  reversalApprovalVisible.value = true;
  await loadReversalRequests();
}

async function loadReversalRequests() {
  reversalApprovalLoading.value = true;
  try {
    const res = await listReversalRequests(factoryId.value, { status: 'PENDING_APPROVAL', size: 50 });
    reversalRequests.value = res.success && res.data ? (res.data.content || []) : [];
  } catch {
    reversalRequests.value = [];
  } finally {
    reversalApprovalLoading.value = false;
  }
}

async function handleApproveReversal(reqRow: InterimSettleReversalRequest) {
  if (reversalActingId.value) return;
  reversalActingId.value = reqRow.id;
  try {
    const res = await approveReversalRequest(factoryId.value, reqRow.id);
    if (res.success) {
      ElMessage.success(res.message || '撤销申请已审批，小结已撤销');
      await loadReversalRequests();
      loadData();
    } else {
      ElMessage({ message: res.message || '审批失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: unknown) {
    // 执行侧下游已消耗 / 超时 → sticky 显示后端 blocking message
    const errMsg = (e as any)?.response?.data?.message || '审批失败';
    ElMessage({ message: errMsg, type: 'error', duration: 0, showClose: true });
  } finally {
    reversalActingId.value = null;
  }
}

async function handleRejectReversal(reqRow: InterimSettleReversalRequest) {
  let reason = '';
  try {
    const { value } = await ElMessageBox.prompt('请填写驳回原因：', '驳回撤销申请', {
      confirmButtonText: '驳回', cancelButtonText: '取消',
      inputValidator: (v: string) => (v && v.trim() ? true : '请填写驳回原因'),
    });
    reason = (value || '').trim();
  } catch {
    return;
  }
  if (reversalActingId.value) return;
  reversalActingId.value = reqRow.id;
  try {
    const res = await rejectReversalRequest(factoryId.value, reqRow.id, reason);
    if (res.success) {
      ElMessage.success(res.message || '撤销申请已驳回');
      await loadReversalRequests();
    } else {
      ElMessage({ message: res.message || '驳回失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: unknown) {
    const errMsg = (e as any)?.response?.data?.message || '驳回失败';
    ElMessage({ message: errMsg, type: 'error', duration: 0, showClose: true });
  } finally {
    reversalActingId.value = null;
  }
}

async function handleStopProduction(row: TableRow) {
  const planId = String(row.id);
  // 防呆 Rule 2: confirm 必须带品名, 不能只靠计划单号 (仓管员按品名认物料, 单号不好记)
  const productLabel = String(row.productTypeName || row.productName || row.productTypeId || '');
  const planNumberLabel = String(row.planNumber || planId);
  const planLabel = productLabel ? `${productLabel} (${planNumberLabel})` : planNumberLabel;
  try {
    await ElMessageBox.confirm(
      `停产「${planLabel}」?停产后计划关闭，不可再小结。`,
      '确认停产',
      { type: 'warning', confirmButtonText: '停产', cancelButtonText: '取消' }
    );
  } catch {
    return; // 用户取消
  }
  if (stopProductionLoadingId.value) return;
  stopProductionLoadingId.value = planId;
  try {
    const res = await stopProduction(factoryId.value, planId);
    if (res.success) {
      ElMessage.success(res.data?.message as string || res.message || '已停产，计划已关闭');
      loadData();
    } else {
      ElMessage({ message: res.message || '停产失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: unknown) {
    const errMsg = (e as any)?.response?.data?.message || '停产失败';
    ElMessage({ message: errMsg, type: 'error', duration: 0, showClose: true });
  } finally {
    stopProductionLoadingId.value = null;
  }
}
// ==================== /Phase 2 BY_STOCK ====================

async function handleCreateBatch(row: TableRow) {
  if (actionLoading.value) return;
  try {
    // #748: 加流程决策提示 (基于 May10 六扇门会议确认)
    await ElMessageBox.confirm(
      `确定将计划 "${row.planNumber}" 转为生产批次？\n\n` +
      `这是 APP 逐道报工的可选分支；PC 文员主流程可以直接点"核对结单"，不需要先转批次。\n\n` +
      `流程提示：\n` +
      `• 原料不足只做预警，不阻断转批次或结单。\n` +
      `• 需要仓库备料时可先生成调拨单，但不是开工前置条件。\n` +
      `• 转批次后会自动创建批次和工序任务，现场可在 APP 报工；最终仍由文员核对实际产量、领用和工时后结单。`,
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

function isUnfinishedStatus(status: string) {
  return ['PENDING', 'IN_PROGRESS'].includes(String(status || '').toUpperCase());
}

function getPlanAdvisory(row: TableRow) {
  return materialAdvisoryMap.value[String(row.id || '')];
}

function getPlanAdvisorySummary(row: TableRow) {
  const advisory = getPlanAdvisory(row);
  if (!advisory) return '';
  if (!advisory.hasWarning) return '原料库存参考: 暂无缺料预警';
  return advisory.message;
}

function advisoryNeedsUnitConfig(advisory: ProductionPlanMaterialAdvisory | null | undefined): boolean {
  if (!advisory?.hasWarning) return false;
  const haystack = [
    advisory.message,
    ...(advisory.warnings || []).map((item) => item.message),
  ].join('\n');
  return /单位.*无法换算|无法换算.*单位|核对单位配置/.test(haystack);
}

function goMaterialUnitConfig(advisory: ProductionPlanMaterialAdvisory | null | undefined) {
  const firstUnitWarning = (advisory?.warnings || []).find((item) => /单位|换算/.test(item.message))
    || advisory?.warnings?.[0];
  router.push({
    path: '/warehouse/material-types',
    query: {
      _returnTo: route.fullPath,
      ...(firstUnitWarning?.materialName ? { keyword: firstUnitWarning.materialName } : {}),
    },
  });
}

// T138 方案A: 开工/开始/生成调拨单 的可操作 gate.
// 后端 startProduction / createBatchFromPlan 严格只接受 PENDING (PLANNED → 409),
// 所以这三个动作只在 PENDING 时展示, 避免对 PLANNED 计划点了直接报错.
// (isPendingStatus 保留给取消按钮的展示范围, 不收窄.)
function isStartable(status: string) {
  return status === 'PENDING';
}

function canPrintPlanDocuments(status: string) {
  return ['PENDING', 'CONFIRMED', 'APPROVED', 'IN_PROGRESS', 'COMPLETED'].includes(String(status || '').toUpperCase());
}

function nextStepText(row: TableRow) {
  const status = String(row.status || '').toUpperCase();
  if (status === 'COMPLETED') {
    const settlement = getSettlementStatus(row);
    if (!settlement) return '加载入库状态';
    if (settlement.postingStatus === 'PENDING_WAREHOUSE_RECEIPT') return '仓库确认入库';
    if (settlement.postingStatus === 'PENDING_CLEARING') return '中转挂账清账';
    if (settlement.postingStatus === 'POSTED_WITH_TOLERANCE') return '已入库(容差)';
    if (settlement.postingStatus === 'POSTED') return '成品库存已入库';
    return postingStatusText(settlement.postingStatus);
  }
  if (status === 'IN_PROGRESS') return '继续 APP 报工或核对结单';
  if (status === 'PENDING') {
    return row.skipProcessReporting === false ? '核对结单；需要逐道报工时下发 APP' : '核对结单；缺料只做预警';
  }
  if (status === 'PLANNED' || status === 'PREPARED') return '确认后进入未完成';
  if (status === 'CANCELLED') return '已取消';
  return '查看详情';
}

function nextStepTagType(row: TableRow) {
  const status = String(row.status || '').toUpperCase();
  if (status === 'COMPLETED') return postingStatusType(getSettlementStatus(row)?.postingStatus);
  if (status === 'IN_PROGRESS') return 'warning';
  if (status === 'PENDING') return row.skipProcessReporting === false ? 'primary' : 'info';
  if (status === 'CANCELLED') return 'danger';
  return 'info';
}

function planRowClassName({ row }: { row: TableRow }): string {
  return planRowClassNameByStatus(String(row.status || ''));
}

function getStatusType(status: string) {
  return getPlanStatusType(status);
}

function getStatusText(status: string) {
  return getPlanStatusText(status);
}

// 防呆 Rule 1: SAFETY_STOCK(存货生产)等无「计划数量」字段的计划落地为 0/null，
// 若原样显示裸 "0" 紧邻黄色「未完成」状态标签，操作员会误读成异常/漏填。
// 明确区分"没有这个字段"(—) 与"真实数量为 0"(理论不会出现于 UI，但同样降级显示避免误读)。
function formatPlannedQuantity(v: number | null | undefined, unit?: string | null): string {
  if (v == null || v === 0) return '—';
  return unit ? `${v} ${unit}` : `${v}（单位未配置）`;
}

function formatPlanDisplayQuantity(row: TableRow | null | undefined): string {
  if (!row) return '—';
  const sourceQuantity = row.sourceDisplayQuantity as number | null | undefined;
  const sourceUnit = row.sourceDisplayUnit as string | null | undefined;
  if (sourceQuantity != null && sourceQuantity !== 0 && sourceUnit) {
    return formatPlannedQuantity(sourceQuantity, sourceUnit);
  }
  return formatPlannedQuantity(
    row.plannedQuantity as number | null | undefined,
    row.plannedUnit as string | null | undefined,
  );
}

// raw-centric 多SKU (2026-07-13): 计划的 targetFinishedGoodIds → 产品名 (列表/详情共用)。
// 查不到 (产品已删除) 时兜底显示 id + 「已删除」提示, 不静默丢字段。
function targetFinishedGoodNames(ids: unknown): string[] {
  if (!Array.isArray(ids)) return [];
  return ids.map((id) => {
    const pt = productTypes.value.find((p: TableRow) => String(p.id) === String(id));
    return pt ? String(pt.name) : `${id}（已删除）`;
  });
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
    f.sourceOrderItemId ||
    (f.targetFinishedGoodIds && f.targetFinishedGoodIds.length > 0)
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
  workflowCandidateDialogVisible.value = false;
  preferredWorkflowSelection = null;
  dialogVisible.value = false;
}

// ==================== AI Entry ====================

function handleAiFill(params: TableRow) {
  const name = String(params.productTypeName || '');
  const matched = findUniqueProductByName(name, productTypes.value);

  const today = todayStr();
  planForm.value = {
    productTypeId: matched ? String(matched.id) : '',
    plannedQuantity: Number(params.plannedQuantity || 0),
    aiRequestedUnit: canonicalUnitCode(params.quantityUnit || params.plannedUnit || params.unit),
    plannedDate: String(params.plannedDate || today),
    notes: String(params.notes || ''),
    estimatedWorkers: undefined,
    assignedSupervisorId: '',
    sourceCustomerName: String(params.sourceCustomerName || ''),
    processName: String(params.processName || ''),
    batchDate: String(params.batchDate || today),
    sourceType: 'MANUAL' as 'MANUAL' | 'CUSTOMER_ORDER' | 'AI_FORECAST' | 'SAFETY_STOCK',
    sourceOrderId: '',
    sourceOrderItemId: '',
    sourceOrderItemIds: [] as string[],
    extraSourceOrderIds: [] as string[],
    customFields: {} as TableRow,
    skipProcessReporting: true as boolean | null,
    // raw-centric 多SKU (2026-07-13): AI 填单命中的单产品同步进多选成品字段,
    // 保持「生产成品」下拉与 productTypeId 一致 (触发 watch → 解析工序图)。
    targetFinishedGoodIds: matched ? [String(matched.id)] : [],
    resolvedCandidates: [],
    selectedCandidateWorkflowId: null,
    resolutionMode: '',
  };
  dialogVisible.value = true;
}

function guardProductionPlanAi(params: Record<string, unknown>) {
  return productionPlanAiGuard(params, productTypes.value);
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
      here="未完成生产任务（PENDING / IN_PROGRESS，文员核对实际产量、领用和工时后结单）"
      other-name="生产管理 → 生产批次"
      other="已开工的实际「批次」（IN_PROGRESS / COMPLETED，记录实际产量、消耗）"
      other-path="/production/batches"
      consequence="需要 APP 逐道报工时再转批次；PC 文员在未完成列表核对结单"
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
          <strong>计划确认后，先进入未完成列表；原料库存不足只做预警，不阻断开工或结单：</strong>
          <ul style="margin: 4px 0 4px 18px; padding: 0;">
            <li><strong>生成调拨单</strong>：根据 BOM 自动计算所需原辅料/包材，发申请给仓库审批。库存不足或需要从其他仓库调料时使用。<em>仅适用于有明确计划量的计划（销售订单/手工/预测）；存货生产不预排数量，请直接用「逐道录入/小结」备料。</em></li>
            <li><strong>核对结单</strong>：PC 文员逐单核对实际产量、原料/半成品领用和工时；缺料信息会在列表和弹窗里作为参考值显示。</li>
            <li><strong>APP 报工 / 转批次</strong>：需要 APP 逐道报工时使用，系统会自动建批次 + 工序任务；原料不足只提示缺口，不阻断转批次。</li>
            <li><strong>PC 结单</strong>：不需要逐道报工的计划，也必须由文员在「核对结单」里录入实际产量、实际领用和人效后，才算完成。</li>
            <li><strong>补录时效</strong>：今天/昨天可补，前天为极限，大前天及更早禁止补录；超出窗口请走审批或联系管理员处理。</li>
          </ul>
          <strong>下一步</strong>：现场用 APP 逐工序上报；文员用「核对结单」把实际数量、领用和工时闭环。
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
            <!-- #726 SP12: 合并打印公单 — 需先勾选计划, 多选 → 调 production-work-order-multi 端点 -->
            <el-button
              type="info"
              :icon="Printer"
              :disabled="selectedPlans.length === 0"
              :title="selectedPlans.length === 0 ? '请先勾选要打印的计划' : `合并打印 ${selectedPlans.length} 份工单`"
              @click="handleMultiPrint"
              style="margin-left: 8px;"
            >合并打印公单{{ selectedPlans.length > 0 ? ` (${selectedPlans.length})` : '' }}</el-button>
            <el-button v-if="canWrite" type="success" :icon="ChatDotRound" @click="aiEntryVisible = true" style="margin-left: 8px;">
              AI对话创建
            </el-button>
            <el-button v-if="canWrite" type="warning" :icon="Plus" @click="handleCreateSecondary" style="margin-left: 8px;">
              独立再加工/返工
            </el-button>
            <el-button v-if="canWrite" type="warning" plain @click="openReversalApproval" style="margin-left: 8px;"
              title="审批待处理的撤销小结申请（财务经理/工厂超管）">
              撤销审批
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
          <!-- Rule 1 (fool-proof-design): "全部" 必须是显式可选项, 不能只靠 clearable 的隐藏 x 图标才能回到不限状态
               —— 否则搜索一个已完成/已取消的计划号, 默认"未完成"筛选下静默 0 结果, 用户以为查不到 -->
          <el-option label="全部" value="" />
          <el-option label="未完成" value="UNFINISHED" />
          <el-option label="草稿" value="PREPARED" />
          <el-option label="待执行" value="PLANNED" />
          <el-option label="待执行 (PENDING)" value="PENDING" />
          <el-option label="进行中" value="IN_PROGRESS" />
          <el-option label="暂停" value="PAUSED" />
          <el-option label="已结单" value="COMPLETED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
      </div>

      <!-- #726 SP12: @selection-change 驱动合并打印 -->
      <el-table
        :data="tableData"
        v-loading="loading"
        :empty-text="emptyText"
        stripe
        border
        :scrollbar-always-on="true"
        class="wide-table"
        style="width: 100%"
        :row-class-name="planRowClassName"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="45" />
        <el-table-column prop="planNumber" label="计划编号" width="160" />
        <el-table-column label="产品类型" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.productTypeName || row.productName || row.productTypeId || '-' }}</span>
            <!-- raw-centric 多SKU: 多选成品建的计划, 后缀显示各终端成品 chips -->
            <template v-if="Array.isArray(row.targetFinishedGoodIds) && row.targetFinishedGoodIds.length > 0">
              <el-tag
                v-for="(name, idx) in targetFinishedGoodNames(row.targetFinishedGoodIds)"
                :key="idx"
                size="small"
                style="margin-left: 4px;"
              >→ {{ name }}</el-tag>
            </template>
          </template>
        </el-table-column>
        <el-table-column prop="sourceCustomerName" label="客户" min-width="120" show-overflow-tooltip />
        <el-table-column prop="processName" label="工序" width="120" show-overflow-tooltip />
        <el-table-column prop="batchDate" label="批次日期" width="120" />
        <el-table-column label="计划成品" width="110" align="right">
          <template #default="{ row }">{{ formatPlanDisplayQuantity(row) }}</template>
        </el-table-column>
        <el-table-column prop="actualQuantity" label="实际数量" width="100" align="right" />
        <el-table-column prop="plannedDate" label="计划日期" width="120" />
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag
              :type="getStatusType(row.status)"
              size="large"
              effect="dark"
              class="plan-status-tag"
              :class="planStatusClass(row.status)"
            >
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="入库状态" width="125" align="center">
          <template #default="{ row }">
            <el-tag
              v-if="getSettlementStatus(row)"
              :type="postingStatusType(getSettlementStatus(row)?.postingStatus)"
              size="small"
              effect="plain"
            >
              {{ postingStatusText(getSettlementStatus(row)?.postingStatus) }}
            </el-tag>
            <el-tag v-else-if="String(row.status || '').toUpperCase() === 'COMPLETED'" type="info" size="small">
              结单加载中
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="原料参考" width="130" align="center">
          <template #default="{ row }">
            <el-tooltip
              v-if="getPlanAdvisory(row)?.hasWarning"
              :content="getPlanAdvisorySummary(row)"
              placement="top"
            >
              <el-tag type="danger" size="small" :icon="Warning">缺料预警</el-tag>
            </el-tooltip>
            <el-tooltip
              v-else-if="getPlanAdvisory(row)"
              :content="getPlanAdvisorySummary(row)"
              placement="top"
            >
              <el-tag type="success" size="small">库存参考</el-tag>
            </el-tooltip>
            <el-tag v-else-if="isUnfinishedStatus(row.status)" type="info" size="small">加载中</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="下一步" min-width="170">
          <template #default="{ row }">
            <div class="next-step-cell">
              <el-tag :type="nextStepTagType(row)" size="small" effect="plain">
                {{ nextStepText(row) }}
              </el-tag>
              <div v-if="row.skipProcessReporting === false" class="next-step-hint">
                工序任务将下发至 APP
              </div>
              <div v-else-if="isUnfinishedStatus(row.status)" class="next-step-hint">
                文员核对实际领用后结单
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="estimatedWorkers" label="预计工人" width="90" align="center" />
        <el-table-column prop="assignedSupervisorName" label="指派主管" width="100" show-overflow-tooltip />
        <el-table-column prop="sourceType" label="来源" width="90" align="center">
          <template #default="{ row }">
            <!-- SP2: planNumber 前缀 SEC- 标识独立再加工/返工 (planSourceType 字段未暴露在 DTO) -->
            <el-tag v-if="String(row.planNumber || '').startsWith('SEC-')" type="warning" size="small">独立再加工</el-tag>
            <el-tag v-else-if="row.sourceType === 'EXCEL_IMPORT'" type="warning" size="small">Excel导入</el-tag>
            <el-tag v-else-if="row.sourceType === 'AI_CHAT'" type="success" size="small">AI创建</el-tag>
            <el-tag v-else-if="row.sourceType === 'SAFETY_STOCK'" type="info" size="small">存货生产</el-tag>
            <el-tag v-else-if="row.sourceType === 'CUSTOMER_ORDER'" type="primary" size="small">销售订单</el-tag>
            <el-tag v-else-if="row.sourceType === 'AI_FORECAST'" type="success" size="small">AI预测</el-tag>
            <el-tag v-else size="small">手动</el-tag>
          </template>
        </el-table-column>
        <!-- Wave2: 报工模式 badge -->
        <el-table-column label="报工模式" width="100" align="center">
          <template #default="{ row }">
            <el-tag
              v-if="row.skipProcessReporting === false"
              type="warning"
              size="small"
              title="逐道工序报工"
            >逐道</el-tag>
            <el-tag
              v-else
              type="info"
              size="small"
              title="只报领料+产出两点（免工序报工）"
            >免工序</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180" :formatter="formatDateTimeCell" />
        <el-table-column label="操作" width="340" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleViewPlan(row)">查看</el-button>
            <el-button type="primary" link size="small" @click="openDocumentTrace(row)">单据追踪</el-button>
            <!-- 非存货生产结单 (原逻辑不变) -->
            <el-button
              v-if="canWrite && isUnfinishedStatus(row.status) && row.sourceType !== 'SAFETY_STOCK'"
              type="primary"
              size="small"
              :icon="CircleCheck"
              :disabled="actionLoading"
              title="PC 文员核对实际产量、领用和工时后结单"
              @click="handleComplete(row)"
            >核对结单</el-button>
            <!-- 存货生产 (SAFETY_STOCK) 小结 (替换结单, 计划继续挂起) -->
            <el-button
              v-if="canWrite && isUnfinishedStatus(row.status) && row.sourceType === 'SAFETY_STOCK'"
              type="primary"
              size="small"
              :icon="CircleCheck"
              :disabled="interimSettleLoadingId === String(row.id)"
              :loading="interimSettleLoadingId === String(row.id)"
              title="增量入库成品并扣料，计划继续开放，可多次小结"
              @click="handleInterimSettle(row)"
            >小结</el-button>
            <!-- 存货生产 (SAFETY_STOCK) 撤销小结-申请 (申请→审批→执行治理; 此处仅提交申请, 零库存副作用) -->
            <el-button
              v-if="canWrite && isUnfinishedStatus(row.status) && row.sourceType === 'SAFETY_STOCK'"
              type="warning"
              size="small"
              link
              :disabled="reverseInterimSettleLoadingId === String(row.id)"
              :loading="reverseInterimSettleLoadingId === String(row.id)"
              title="申请撤销最近一次小结（需审批, 通过后才逆转入库+还回消耗）；仅限小结后24小时内，需填原因"
              @click="handleReverseInterimSettle(row)"
            >申请撤销小结</el-button>
            <!-- 存货生产 (SAFETY_STOCK) 停产 (关闭计划) -->
            <el-button
              v-if="canWrite && isUnfinishedStatus(row.status) && row.sourceType === 'SAFETY_STOCK'"
              type="danger"
              size="small"
              link
              :disabled="stopProductionLoadingId === String(row.id)"
              :loading="stopProductionLoadingId === String(row.id)"
              title="停产后计划关闭，不可再小结"
              @click="handleStopProduction(row)"
            >停产</el-button>
            <el-button
              v-if="canWrite && isUnfinishedStatus(row.status)"
              type="success"
              link
              size="small"
              title="文员逐道工序录入（批次链/混锅来源/投入产出）"
              @click="openProcessEntry(row)"
            >逐道录入</el-button>
            <el-button
              v-if="canConfirmReceipt(row)"
              type="success"
              size="small"
              :icon="CircleCheck"
              :disabled="actionLoading"
              title="仓库确认实际收到数量后，成品才正式入库；差异进入中转挂账"
              @click="handleWarehouseReceipt(row)"
            >确认入库</el-button>
            <el-button
              v-if="canClearTransit(row)"
              type="danger"
              size="small"
              :icon="Warning"
              :disabled="actionLoading"
              title="处理差异并关闭生产中转挂账"
              @click="handleTransitClearing(row)"
            >清账</el-button>
            <!-- 6.12 复核: PC 主路径是未完成列表直接完成；转批次保留给 APP 逐道报工。 -->
            <el-button
              v-if="canWrite && isStartable(row.status)"
              type="success"
              link
              size="small"
              :icon="VideoPlay"
              :disabled="actionLoading"
              title="转为生产批次并开工(建批次+工序任务, 用于 APP 逐道报工)"
              @click="handleCreateBatch(row)"
            >APP报工</el-button>
            <!-- 生成调拨单: 需要明确计划量 (BOM × 计划量 展开). 存货生产(SAFETY_STOCK)
                 计划量=0 (产量在逐道录入/小结时才定), 展开全 0 会落地死调拨单 → 对其隐藏. -->
            <el-button
              v-if="canWrite && isStartable(row.status) && row.sourceType !== 'SAFETY_STOCK'"
              type="warning"
              link
              size="small"
              :disabled="actionLoading"
              @click="handleGenerateTransfer(row)"
            >生成调拨单</el-button>
            <el-button
              v-if="canWrite && (isPendingStatus(row.status) || row.status === 'IN_PROGRESS')"
              type="danger"
              link
              size="small"
              :icon="CircleClose"
              :disabled="actionLoading"
              @click="handleCancel(row)"
            >取消</el-button>
            <!-- SP12: 打印生产工单 (PrintController /print/production-work-order/{planId}) -->
            <el-button
              v-if="canPrintPlanDocuments(row.status)"
              type="info"
              link
              size="small"
              @click="safePrint('production-work-order', factoryId, String(row.id), { fileName: `生产工单_${row.planNumber || row.id}` })"
            >打印工单</el-button>
            <!-- SP12: 打印汇总领料单 (PrintController /print/consolidated-material-requisition/{planId}) -->
            <el-button
              v-if="canPrintPlanDocuments(row.status)"
              type="info"
              link
              size="small"
              @click="safePrint('consolidated-material-requisition', factoryId, String(row.id), { fileName: `汇总领料单_${row.planNumber || row.id}` })"
            >领料单</el-button>
            <!-- 六扇门: 打印配料单 (按锅配料, PrintController /print/batching-sheet/{planId}) -->
            <el-button
              v-if="canPrintPlanDocuments(row.status)"
              type="info"
              link
              size="small"
              @click="safePrint('batching-sheet', factoryId, String(row.id), { fileName: `配料单_${row.planNumber || row.id}` })"
            >配料单</el-button>
            <el-button
              v-if="String(row.status || '').toUpperCase() === 'COMPLETED'"
              type="primary"
              link
              size="small"
              title="查看该批次的出成率与成本核算"
              @click="router.push({ path: '/production-analytics/yield-cost', query: { orderId: row.sourceOrderId } })"
            >看成本核算</el-button>
            <el-button
              type="info"
              link
              size="small"
              title="查看该生产计划的汇总：总投入原料 / 产出成品 / 真实出成率 / 总成本"
              @click="handleOpenSummary(row)"
            >阅读汇总</el-button>
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

    <el-drawer v-model="documentTraceVisible" title="生产计划单据追踪" size="620px">
      <div v-loading="documentTraceLoading" class="document-trace">
        <template v-if="documentTrace">
          <el-descriptions :column="2" border style="margin-bottom: 16px">
            <el-descriptions-item label="生产计划">{{ documentTrace.planNumber }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ documentTrace.planStatus || '-' }}</el-descriptions-item>
          </el-descriptions>
          <el-alert
            v-for="missing in documentTrace.missingLinks"
            :key="missing"
            :title="missing"
            type="warning"
            :closable="false"
            show-icon
            style="margin-bottom: 10px"
          />
          <el-empty v-if="documentTrace.documents.length === 0" description="当前计划暂无已关联单据" />
          <el-timeline v-else>
            <el-timeline-item
              v-for="document in documentTrace.documents"
              :key="`${document.documentType}-${document.documentId}`"
              :timestamp="document.occurredAt || undefined"
              placement="top"
            >
              <el-card shadow="never" class="trace-document-card">
                <div class="trace-document-header">
                  <div>
                    <el-tag size="small" effect="plain">{{ traceDirectionLabel(document.direction) }}</el-tag>
                    <strong>{{ traceDocumentLabel(document.documentType) }}</strong>
                  </div>
                  <el-button type="primary" link @click="openTraceDocument(document)">前往单据</el-button>
                </div>
                <div class="trace-document-number">{{ document.documentNumber || document.documentId }}</div>
                <div class="trace-document-meta">
                  <span>{{ document.relation || '-' }}</span>
                  <el-tag v-if="document.status" size="small" type="info">{{ enumLabel(document.status) }}</el-tag>
                </div>
              </el-card>
            </el-timeline-item>
          </el-timeline>
        </template>
      </div>
    </el-drawer>

    <!-- 查看计划详情 -->
    <el-dialog v-model="viewDialogVisible" title="计划详情" width="680px" destroy-on-close>
      <div v-if="viewPlan" class="plan-detail">
        <!-- 基本信息 -->
        <div class="detail-section">
          <div class="detail-section-title">基本信息</div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="计划编号">{{ viewPlan.planNumber }}</el-descriptions-item>
            <el-descriptions-item label="产品类型">
              {{ viewPlan.productTypeName || viewPlan.productName || viewPlan.productTypeId || '-' }}
              <template v-if="Array.isArray(viewPlan?.targetFinishedGoodIds) && viewPlan.targetFinishedGoodIds.length > 0">
                <el-tag
                  v-for="(name, idx) in targetFinishedGoodNames(viewPlan.targetFinishedGoodIds)"
                  :key="idx"
                  size="small"
                  style="margin-left: 4px;"
                >→ {{ name }}</el-tag>
              </template>
            </el-descriptions-item>
            <el-descriptions-item label="客户">{{ viewPlan.sourceCustomerName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="指派主管">{{ viewPlan.assignedSupervisorName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="计划成品">{{ formatPlanDisplayQuantity(viewPlan) }}</el-descriptions-item>
            <el-descriptions-item label="实际数量">{{ viewPlan.actualQuantity || '-' }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="getStatusType(viewPlan.status as string)" size="small">{{ getStatusText(viewPlan.status as string) }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="来源">
              <!-- SP2 -->
              <el-tag v-if="String(viewPlan.planNumber || '').startsWith('SEC-')" type="warning" size="small">独立再加工</el-tag>
              <el-tag v-else-if="viewPlan.sourceType === 'EXCEL_IMPORT'" type="warning" size="small">Excel导入</el-tag>
              <el-tag v-else-if="viewPlan.sourceType === 'AI_CHAT'" type="success" size="small">AI创建</el-tag>
              <el-tag v-else-if="viewPlan.sourceType === 'SAFETY_STOCK'" type="info" size="small">存货生产</el-tag>
              <el-tag v-else-if="viewPlan.sourceType === 'CUSTOMER_ORDER'" type="primary" size="small">销售订单</el-tag>
              <el-tag v-else-if="viewPlan.sourceType === 'AI_FORECAST'" type="success" size="small">AI预测</el-tag>
              <el-tag v-else size="small">手动</el-tag>
            </el-descriptions-item>
            <!-- SP5 多SO合并: 显示合并的全部销售订单ID -->
            <el-descriptions-item
              v-if="Array.isArray((viewPlan as any).sourceOrderIds) && (viewPlan as any).sourceOrderIds.length > 1"
              label="合并订单"
              :span="2"
            >
              <div style="display: flex; flex-wrap: wrap; gap: 4px;">
                <el-tag
                  v-for="soId in (viewPlan as any).sourceOrderIds"
                  :key="soId"
                  size="small"
                  type="warning"
                >{{ soId }}</el-tag>
              </div>
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
            存货生产可多次小结（增量入库半成品/成品+实时扣料），计划保持开放，做完手动停产关闭；无需关联销售订单
          </div>
        </el-form-item>
        <el-form-item
          v-if="planForm.sourceType === 'CUSTOMER_ORDER'"
          label="销售订单"
          required
          :class="{ 'field-error': sourceOrderIdError }"
        >
          <el-select
            ref="sourceOrderSelectRef"
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
          <div v-if="sourceOrderIdError" class="field-error-hint">请选择来源销售订单</div>
        </el-form-item>
        <!-- SP5 多SO合并: 追加额外销售订单 -->
        <el-form-item
          v-if="planForm.sourceType === 'CUSTOMER_ORDER' && planForm.sourceOrderId"
          label="合并订单"
        >
          <div style="width: 100%">
            <div v-if="planForm.extraSourceOrderIds.length > 0" style="margin-bottom: 6px; display: flex; flex-wrap: wrap; gap: 4px;">
              <el-tag
                v-for="(eid, idx) in planForm.extraSourceOrderIds"
                :key="eid"
                closable
                size="small"
                type="warning"
                @close="planForm.extraSourceOrderIds.splice(idx, 1)"
              >
                {{ selectableSalesOrders.find((o) => String(o.id) === eid)?.orderNo || eid }}
              </el-tag>
            </div>
            <el-select
              placeholder="+ 追加合并销售订单"
              filterable
              :loading="salesOrdersLoading"
              style="width: 100%"
              @change="(id: string) => {
                if (id && id !== planForm.sourceOrderId && !planForm.extraSourceOrderIds.includes(id)) {
                  planForm.extraSourceOrderIds.push(id);
                }
              }"
            >
              <el-option
                v-for="so in selectableSalesOrders.filter(
                  (o) => String(o.id) !== planForm.sourceOrderId && !planForm.extraSourceOrderIds.includes(String(o.id))
                )"
                :key="String(so.id)"
                :label="canViewPrice ? `${so.orderNo} | ${so.customerName || ''} | ¥${so.totalAmount || 0} | ${so.statusLabel || ''}` : `${so.orderNo} | ${so.customerName || ''} | ${so.statusLabel || ''}`"
                :value="String(so.id)"
              />
            </el-select>
            <div style="font-size: 12px; color: var(--text-color-secondary, #909399); margin-top: 4px;">
              可追加多个销售订单合并为一张生产工单。产品类型由主订单行确定，追加订单需已财务审核。
            </div>
          </div>
        </el-form-item>
        <el-form-item
          v-if="planForm.sourceType === 'CUSTOMER_ORDER' && planForm.sourceOrderId"
          label="产品行"
          required
        >
          <!-- 以销定产 (2026-06-24): 多选, 选 SO 默认带出全部产品行, 取消不需要的; 每保留行各建一张计划 (产品/数量取自该行) -->
          <el-select
            v-model="planForm.sourceOrderItemIds"
            multiple
            placeholder="默认已带出全部产品行, 可取消不需要的 (每行各建一张计划)"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="it in selectedOrderItems"
              :key="String(it.id)"
              :label="`${it.productName || ''}${it.specification ? ' | ' + it.specification : ''} | 数量 ${it.quantity || 0} | 待发 ${it.remainingQty || 0}`"
              :value="String(it.id)"
            />
          </el-select>
          <div v-if="planForm.sourceOrderItemIds.length > 1" style="font-size:12px;color:#909399;margin-top:4px">
            已选 {{ planForm.sourceOrderItemIds.length }} 个产品, 将各建一张生产计划 (产品类型/数量自动取自销售订单行)
          </div>
          <UpstreamMissingHint
            v-if="planForm.sourceType === 'CUSTOMER_ORDER' && planForm.sourceOrderId && selectedOrderItems.length === 0"
            description="该订单暂无产品行，无法据此排产"
            target-module="sales"
            :require-write="true"
            action-text="去销售订单添加产品行"
            contact-text="请联系销售或管理员为该订单补充产品行后再排产"
            @action="goAddOrderItems(planForm.sourceOrderId || '')"
          />
        </el-form-item>
        <!-- 以销定产 (2026-06-24): 来源=销售订单时, 产品/数量按所选产品行各自取, 不再手选单产品 → 隐藏 -->
        <!-- 单选只接受单产出 Workflow；多选只接受同时覆盖全部成品的多产出 Workflow。 -->
        <el-form-item v-if="planForm.sourceType !== 'CUSTOMER_ORDER'" label="生产成品" required>
          <el-select
            v-model="planForm.targetFinishedGoodIds"
            multiple
            filterable
            placeholder="选择本次生产的成品 (可多选)"
            style="width: 100%"
          >
            <el-option
              v-for="item in finishedGoodProductTypes"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
          <div v-if="resolvingWorkflow" style="font-size: 12px; color: var(--text-color-secondary, #909399); margin-top: 4px;">
            正在解析工序图…
          </div>
          <template v-else-if="planForm.targetFinishedGoodIds.length > 0">
            <div
              v-if="hasResolvedWorkflowCandidate && resolvedWorkflowCandidate"
              class="selected-workflow-route"
            >
              <div class="selected-workflow-route__head">
                <div>
                  <span class="selected-workflow-route__eyebrow">本计划已固定工序路线</span>
                  <strong>{{ workflowCandidateProcessSummary(resolvedWorkflowCandidate) }}</strong>
                </div>
                <el-popover
                  trigger="hover"
                  placement="right-start"
                  :width="780"
                  :show-after="120"
                  :hide-after="120"
                >
                  <WorkflowRoutePreview
                    :nodes="resolvedWorkflowCandidate.previewNodes"
                    :edges="resolvedWorkflowCandidate.previewEdges"
                  />
                  <template #reference>
                    <el-button size="small" plain @click.stop>悬浮查看 Cell 图</el-button>
                  </template>
                </el-popover>
              </div>
              <div class="selected-workflow-route__meta">
                <el-tag type="success" size="small">{{ workflowCandidateTopologyLabel(resolvedWorkflowCandidate) }}</el-tag>
                <span>版本 v{{ resolvedWorkflowCandidate.definitionVersion }}</span>
              </div>
              <div class="selected-workflow-route__outputs">
                <el-tag
                  v-for="t in (resolvedWorkflowCandidate.terminalOutputs || [])"
                  :key="t.productTypeId"
                  size="small"
                >→ {{ t.productName }}</el-tag>
              </div>
            </div>
            <el-alert
              v-else-if="planForm.resolvedCandidates.length > 0"
              type="warning"
              show-icon
              :closable="false"
              class="workflow-route-decision-alert"
              :title="planForm.resolvedCandidates.length > 1
                ? '多个 Workflow 处于同一匹配层级，需要选择实际生产路线'
                : '该 Workflow 会同时产出其它成品，需要确认完整产出集合'"
            >
              <el-button
                type="warning"
                plain
                size="small"
                @click="openWorkflowCandidateDialog"
              >
                选择生产工序路线
              </el-button>
            </el-alert>
            <el-alert
              v-else-if="planForm.resolutionMode === 'NONE' && planForm.targetFinishedGoodIds.length === 1"
              type="error"
              show-icon
              :closable="false"
              style="margin-top: 8px;"
            >
              <template #title>该产品没有单产出 Workflow，请前往创建单产出 Workflow</template>
              <div style="display: flex; gap: 8px; margin-top: 8px;">
                <el-button
                  size="small"
                  type="primary"
                  @click="goToWorkflowConfig(planForm.targetFinishedGoodIds)"
                >去 Workflow 配置</el-button>
              </div>
            </el-alert>
            <el-alert
              v-else-if="planForm.resolutionMode === 'NONE' && planForm.targetFinishedGoodIds.length > 1"
              type="error"
              show-icon
              :closable="false"
              title="未找到共享的工序 Workflow，请分开创建生产计划"
              style="margin-top: 8px;"
            />
          </template>
        </el-form-item>
        <el-form-item label="客户名称">
          <el-input v-model="planForm.sourceCustomerName" placeholder="选择产品后自动填充，也可手动输入" />
        </el-form-item>
        <!-- Wave2 六扇门: 免工序报工开关 (Rule 1 防呆: 产品无工序时 disabled 锁定) -->
        <el-form-item label="报工模式">
          <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap;">
            <el-switch
              v-model="planForm.skipProcessReporting"
              :disabled="reportModeLocked"
              active-text="免工序报工"
              inactive-text="逐道报工"
              :active-value="true"
              :inactive-value="false"
              style="--el-switch-on-color: var(--el-color-info, #909399);"
            />
            <span style="font-size: 12px; color: var(--text-color-secondary, #909399);">
              <template v-if="planForm.skipProcessReporting">
                操作员只报「领料入」+「产出」两个节点
              </template>
              <template v-else>
                操作员逐道工序报工
              </template>
              <template v-if="hasActiveWorkflow">
                （本产品由工序 Workflow 驱动，逐道报工按图逐工序执行）
              </template>
              <template v-else-if="!hasProcesses && planForm.productTypeId">
                （产品未配置工序，只能走免工序报工）
              </template>
            </span>
          </div>
        </el-form-item>
        <!-- A3: 工序只读展示；免工序报工时隐藏，产品无工序时改提示 -->
        <el-form-item v-if="!planForm.skipProcessReporting" label="工序">
          <template v-if="!planForm.productTypeId">
            <span style="color: var(--text-color-secondary, #909399); font-size: 13px;">请先选择产品类型</span>
          </template>
          <template v-else-if="hasActiveWorkflow">
            <span style="color: var(--el-color-success, #67c23a); font-size: 13px;">
              本产品由「产品工序 Workflow」驱动，逐道报工按图逐工序执行（含分支/多产出）。转批次后在逐道录入抽屉逐道保存。
            </span>
          </template>
          <template v-else-if="productWorkProcessList.length === 0">
            <span style="color: var(--el-color-warning, #e6a23c); font-size: 13px;">
              该产品未配置工序，后端将自动走两点报工。如需逐道，请先到
              <el-link type="primary" underline="hover" @click="router.push('/system/product-processes')" style="font-size: 13px; vertical-align: baseline;">产品工序配置</el-link>
              中配置工序。
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
        <!-- Wave2: 免工序报工模式下的工序提示（产品已配工序但选了免工序） -->
        <el-form-item v-else-if="hasProcesses && planForm.productTypeId" label="工序">
          <span style="color: var(--text-color-secondary, #909399); font-size: 13px;">
            已跳过工序（共 {{ productWorkProcessList.length }} 道），操作员仅需报领料+产出
          </span>
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
        <!-- 以销定产: 来源=销售订单时数量按各产品行取, 不手填 → 隐藏 -->
        <!-- 存货生产(SAFETY_STOCK): 无计划数量, 按实际小结累计 → 同样隐藏 -->
        <el-form-item
          v-if="planForm.sourceType !== 'CUSTOMER_ORDER' && planForm.sourceType !== 'SAFETY_STOCK'"
          :label="hasResolvedWorkflowCandidate
            ? `${planForm.resolutionMode === 'SHARED_MULTI_OUTPUT' ? 'Workflow 计划基准数量' : '计划成品数量'}（${resolvedWorkflowCandidate?.plannedUnit || '单位未配置'}）`
            : '计划成品数量'"
          required
        >
          <el-input-number v-model="planForm.plannedQuantity" :min="1" style="width: 100%" />
          <div v-if="planForm.aiRequestedUnit" class="ai-unit-preserved">
            AI 原始数量单位：{{ planForm.aiRequestedUnit }}；保存口径：{{ resolvedWorkflowCandidate?.plannedUnit || '等待 Workflow 解析' }}
          </div>
          <div style="font-size: 12px; color: var(--text-color-secondary, #909399); margin-top: 2px;">
            <template v-if="hasResolvedWorkflowCandidate && planForm.resolutionMode === 'SHARED_MULTI_OUTPUT'">
              计划绑定整张共同 Workflow；逐道报工按图处理全部投入、半成品和产出，不裁剪其它分支。
            </template>
            <template v-else-if="hasResolvedWorkflowCandidate">
              按 Workflow 终端成品输出端口单位填写；各工序继续按自己的端口单位报工。
            </template>
            <template v-else>
              按产品成品单位填写；首道投料数量以逐工序报工和配方出成率为准
            </template>
          </div>
        </el-form-item>
        <!-- raw-centric 多SKU (2026-07-13): 存货生产 + 产品由 Workflow 驱动 (自有图或所选成品命中
             raw-owned 候选) → 需设投料数量, 供转批次 (create-batch 要求 >0, 否则 400 数量必须大于0)。
             非 workflow 的存货生产仍按实际小结累计不填数量 (不回归)。 -->
        <el-form-item
          v-if="planForm.sourceType === 'SAFETY_STOCK' && (hasActiveWorkflow || hasResolvedWorkflowCandidate)"
          :label="`${planForm.resolutionMode === 'SHARED_MULTI_OUTPUT' ? 'Workflow 计划基准数量' : '计划成品数量'}（${resolvedWorkflowCandidate?.plannedUnit || '单位未配置'}）`"
          required
        >
          <el-input-number v-model="planForm.plannedQuantity" :min="1" style="width: 100%" />
          <div style="font-size: 12px; color: var(--text-color-secondary, #909399); margin-top: 2px;">
            <template v-if="planForm.resolutionMode === 'SHARED_MULTI_OUTPUT'">
              该计划使用共同的多产出 Workflow；逐道报工时按完整工序图记录全部分支。
            </template>
            <template v-else>
              填 Workflow 终端成品输出端口的计划数量；逐道报工仍使用各工序端口单位。
            </template>
          </div>
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
        <el-button type="primary" :loading="dialogLoading" :disabled="nonSoSubmitBlocked" @click="submitPlan">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="workflowCandidateDialogVisible"
      title="选择本计划使用的生产工序路线"
      width="880px"
      top="7vh"
      append-to-body
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-alert
        type="info"
        show-icon
        :closable="false"
        class="workflow-candidate-dialog__hint"
        title="系统只展示当前最高优先层的候选。Workflow 名称不作为判断依据，请核对中间工序、投入和完整产出。"
      />
      <el-radio-group v-model="pendingCandidateWorkflowId" class="workflow-candidate-list">
        <div
          v-for="candidate in planForm.resolvedCandidates"
          :key="candidate.workflowId"
          :class="[
            'workflow-candidate-card',
            { 'is-selected': pendingCandidateWorkflowId === candidate.workflowId },
          ]"
          @click="pendingCandidateWorkflowId = candidate.workflowId"
        >
          <div class="workflow-candidate-card__top">
            <el-radio :label="candidate.workflowId">
              <span class="workflow-candidate-card__process">
                {{ workflowCandidateProcessSummary(candidate) }}
              </span>
            </el-radio>
            <el-popover
              trigger="hover"
              placement="right-start"
              :width="780"
              :show-after="120"
              :hide-after="120"
            >
              <WorkflowRoutePreview
                :nodes="candidate.previewNodes"
                :edges="candidate.previewEdges"
              />
              <template #reference>
                <el-button
                  class="workflow-preview-trigger"
                  type="primary"
                  plain
                  size="small"
                  @click.stop
                >
                  悬浮查看 Cell 连线
                </el-button>
              </template>
            </el-popover>
          </div>

          <div class="workflow-candidate-card__meta">
            <el-tag size="small" type="info">{{ workflowCandidateTopologyLabel(candidate) }}</el-tag>
            <span>版本 v{{ candidate.definitionVersion }}</span>
            <span v-if="candidate.ownerProductName">归属 {{ candidate.ownerProductName }}</span>
          </div>

          <div class="workflow-candidate-card__outputs">
            <span class="workflow-candidate-card__label">完整产出</span>
            <el-tag
              v-for="output in (candidate.terminalOutputs || [])"
              :key="output.productTypeId"
              size="small"
              :type="workflowCandidateExtraOutputs(candidate, planForm.targetFinishedGoodIds).includes(output.productTypeId)
                ? 'warning' : 'success'"
            >
              {{ output.productName || output.productTypeId }}
            </el-tag>
          </div>

          <el-alert
            v-if="candidateExtraOutputNames(candidate).length > 0"
            type="warning"
            :closable="false"
            :title="`确认后会把额外联产成品加入本计划：${candidateExtraOutputNames(candidate).join('、')}`"
          />
        </div>
      </el-radio-group>
      <template #footer>
        <el-button @click="workflowCandidateDialogVisible = false">暂不选择</el-button>
        <el-button
          type="primary"
          :loading="confirmingWorkflowCandidate"
          :disabled="pendingCandidateWorkflowId == null"
          @click="confirmWorkflowCandidateSelection"
        >
          确认并固定该路线
        </el-button>
      </template>
    </el-dialog>

    <!-- #742 / 6.12 核对结单 dialog -->
    <el-dialog
      v-model="completeDialogVisible"
      :title="completeProductName ? `核对结单 — ${completeProductName}` : '核对结单'"
      width="760px"
      top="4vh"
      class="settlement-dialog"
      destroy-on-close
      append-to-body
      :before-close="handleCompleteDialogBeforeClose"
    >
      <el-form label-width="118px" class="settlement-form">
        <div v-if="completeAdvisory?.hasWarning" class="settlement-advisory-alert">
          <el-alert
            :title="completeAdvisory.message"
            type="warning"
            show-icon
            :closable="false"
          />
          <el-button
            v-if="advisoryNeedsUnitConfig(completeAdvisory)"
            type="warning"
            plain
            size="small"
            @click="goMaterialUnitConfig(completeAdvisory)"
          >
            去核对单位配置
          </el-button>
        </div>
        <!-- Phase 2A: 报工自动预填审计 -->
        <el-alert
          v-if="settlementPrefillLoading"
          title="正在按逐道报工自动带入结单数据…"
          type="info"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        />
        <el-alert
          v-else-if="settlementPrefillClean"
          title="审计通过，已自动带入逐道报工数据，请核对后一键确认提交。"
          type="success"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        />
        <el-alert
          v-else-if="settlementBlockerIssues.length > 0"
          type="warning"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        >
          <template #title>
            <div style="font-weight: 600; margin-bottom: 4px;">
              部分数据需人工核对补全（共 {{ settlementBlockerIssues.length }} 项），补全后才能提交：
            </div>
            <ul style="margin: 0; padding-left: 18px;">
              <li v-for="(it, idx) in settlementBlockerIssues" :key="idx" style="line-height: 1.6;">
                {{ it.message }}
              </li>
            </ul>
          </template>
        </el-alert>
        <!-- INFO 级提示 (不阻塞一键确认; 如跨计划半成品确认) -->
        <el-alert
          v-if="settlementPrefillApplied && settlementInfoIssues.length > 0"
          type="info"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        >
          <template #title>
            <ul style="margin: 0; padding-left: 18px;">
              <li v-for="(it, idx) in settlementInfoIssues" :key="idx" style="line-height: 1.6;">
                {{ it.message }}
              </li>
            </ul>
          </template>
        </el-alert>
        <div class="settlement-context">
          <div>
            <div class="settlement-context-label">计划单号</div>
            <div class="settlement-context-value">{{ completePlanNumber || '-' }}</div>
          </div>
          <div>
            <div class="settlement-context-label">品名</div>
            <div class="settlement-context-value">{{ completeProductName || '-' }}</div>
          </div>
          <div>
            <div class="settlement-context-label">计划数量</div>
            <div class="settlement-context-value">{{ formatPlannedQuantity(completePlannedQuantity, completePlannedUnit) }}</div>
          </div>
        </div>
        <template v-if="settlementPrefillClean">
          <el-divider content-position="left">逐道报工汇总</el-divider>
          <div class="settlement-reconciliation-grid">
            <div class="settlement-reconciliation-card">
              <span>实际成品产出</span>
              <strong>{{ completeActualQuantity }} {{ completePlannedUnit || '' }}</strong>
            </div>
            <div class="settlement-reconciliation-card">
              <span>原料批次</span>
              <strong>{{ completeForm.rawMaterialConsumptions.length }} 个</strong>
            </div>
            <div class="settlement-reconciliation-card">
              <span>逐道工时</span>
              <strong>{{ completeForm.workMinutes }} 分钟</strong>
            </div>
          </div>
          <div v-if="settlementTerminalOutputs.length > 0" class="settlement-reconciliation-section">
            <div class="settlement-reconciliation-title">终端产出（按 SKU / 批次 / 单位）</div>
            <div
              v-for="(output, index) in settlementTerminalOutputs"
              :key="`terminal-${output.productTypeId}-${output.batchNumber}-${output.unit}-${index}`"
              class="settlement-reconciliation-row"
            >
              <span>{{ output.productTypeId || '未识别 SKU' }} · {{ output.batchNumber || '未识别批次' }}</span>
              <strong>{{ output.quantity }} {{ output.unit }}</strong>
            </div>
          </div>
          <div class="settlement-reconciliation-section">
            <div class="settlement-reconciliation-title">实际原料领用</div>
            <div
              v-for="(line, index) in completeForm.rawMaterialConsumptions"
              :key="`recorded-raw-${line.materialBatchId}-${index}`"
              class="settlement-reconciliation-row"
            >
              <span>{{ line.batchNumber || line.materialBatchId }}</span>
              <strong>{{ line.quantity }} {{ line.unit || '' }}</strong>
            </div>
          </div>
          <div class="settlement-reconciliation-section">
            <div class="settlement-reconciliation-title">人员与工时</div>
            <div
              v-for="(segment, index) in completeForm.laborSegments"
              :key="`recorded-labor-${index}`"
              class="settlement-reconciliation-row"
            >
              <span>{{ segment.workType || `第 ${index + 1} 道工序` }}</span>
              <strong>{{ segment.headcount }} 人 × {{ segment.minutes }} 分钟</strong>
            </div>
          </div>
          <el-alert
            title="以上数据来自逐道报工，确认后系统将统一结算领用、产出和工时，并结束当前生产计划。"
            type="info"
            show-icon
            :closable="false"
            style="margin: 12px 0"
          />
        </template>
        <template v-else>
        <el-divider content-position="left">产出核对</el-divider>
        <el-form-item label="实际产量" required>
          <el-input-number
            v-model="completeForm.actualQuantity"
            :min="0"
            :precision="2"
            style="width: 100%"
          />
          <!-- 防呆 Rule 1: 有计划数量时软性参考提示; 存货生产等无计划数量 (=0/null) 跳过, 不误导 -->
          <div v-if="completePlannedQuantity > 0" class="settlement-help">
            参考: 计划 {{ formatPlannedQuantity(completePlannedQuantity, completePlannedUnit) }}。计划数量只是参考，超出时系统预警并要求原因，不硬拦。
          </div>
        </el-form-item>
        <el-alert
          v-if="completeIsOverPlan"
          title="实际产量超过计划数量，请选择差异原因。"
          type="warning"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        />
        <el-form-item v-if="completeIsOverPlan" label="差异原因" required>
          <el-select
            v-model="completeForm.varianceReason"
            placeholder="请选择差异原因"
            style="width: 100%"
          >
            <el-option
              v-for="opt in SETTLEMENT_VARIANCE_REASON_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="completeIsOverPlan && completeForm.varianceReason === '其他'" label="原因补充" required>
          <el-input
            v-model="completeForm.otherVarianceReason"
            type="textarea"
            :rows="2"
            placeholder="请说明实际产量超过计划的现场原因"
          />
        </el-form-item>
        <el-form-item label="半成品产量">
          <el-input-number
            v-model="completeForm.semiFinishedOutputQuantity"
            :min="0"
            :precision="2"
            style="width: 100%"
          />
          <div class="settlement-help">
            同一生产计划可以同时产成品和半成品；提交结单会按实际领用扣减原料/半成品，成品需仓库确认实收后才入库。
          </div>
        </el-form-item>
        <el-alert
          type="warning"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        >
          <template #title>
            <div class="settlement-loss-guide">
              <strong>生产报损/损耗留证：</strong>
              现场发生损耗时，不要直接改领用数量抵消。请到
              <el-link
                type="primary"
                underline="hover"
                @click="router.push({ path: '/warehouse/wastage-reports', query: { reason: 'PRODUCTION_WASTE', source: 'production-settlement' } })"
              >报损登记</el-link>
              报损原因选择“生产损耗”，拍照或附件留证；审批后再按缺料结果补调拨。
            </div>
          </template>
        </el-alert>

        <el-divider content-position="left">实际领用核对</el-divider>
        <div v-if="completeAdvisory?.hasWarning" class="settlement-advisory-alert">
          <el-alert
            :title="completeAdvisory.message"
            type="error"
            show-icon
            :closable="false"
          />
          <el-button
            v-if="advisoryNeedsUnitConfig(completeAdvisory)"
            type="danger"
            plain
            size="small"
            @click="goMaterialUnitConfig(completeAdvisory)"
          >
            去核对单位配置
          </el-button>
        </div>
        <el-alert
          v-else-if="completeAdvisory"
          :title="completeAdvisory.message"
          type="success"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        />
        <div class="settlement-line-header">
          <span>
            原料/辅料实际领用
            <el-tag
              v-if="settlementFieldHasIssue('rawMaterialConsumptions')"
              type="warning"
              size="small"
              effect="light"
              style="margin-left: 6px"
            >需人工补全</el-tag>
          </span>
          <el-button size="small" :icon="Plus" @click="addRawConsumptionLine">增加原料行</el-button>
        </div>
        <div
          v-if="bomFilterBlockedMessage && !materialBatchListLoading"
          class="settlement-empty"
        >
          {{ bomFilterBlockedMessage }}
          <el-button size="small" type="primary" link @click="goConfigBomFromSettlement">去配置 BOM</el-button>
        </div>
        <div
          v-else-if="bomFilteredMaterialBatchOptions.length === 0 && !materialBatchListLoading"
          class="settlement-empty"
        >
          暂无可用原料批次；不能伪造领用，请先完成仓库入库或选择正确产品/BOM。
        </div>
        <div
          v-for="(line, index) in completeForm.rawMaterialConsumptions"
          :key="`raw-${index}`"
          class="settlement-consumption-row"
        >
          <el-select
            v-model="line.materialBatchId"
            filterable
            placeholder="选择原料批次 (已按产品 BOM 预过滤)"
            :loading="materialBatchListLoading"
            class="consumption-select"
          >
            <el-option
              v-for="batch in bomFilteredMaterialBatchOptions"
              :key="batch.id"
              :label="materialBatchLabel(batch)"
              :value="batch.id"
            />
          </el-select>
          <el-input-number
            v-model="line.quantity"
            :min="0"
            :max="materialBatchAvailable(selectedMaterialBatch(line.materialBatchId)) || undefined"
            :precision="2"
            class="consumption-qty"
          />
          <el-input
            v-model="line.note"
            placeholder="备注/称重单号"
            class="consumption-note"
          />
          <el-button text type="danger" @click="removeRawConsumptionLine(index)">删除</el-button>
        </div>
        <div class="settlement-line-header">
          <span>半成品实际领用</span>
          <el-button size="small" :icon="Plus" @click="addWipConsumptionLine">增加半成品行</el-button>
        </div>
        <div class="settlement-help">
          选择半成品后会持续显示当前可用量；本行最多领用当前可用数量，超出可用量会被拦截。
        </div>
        <div v-if="wipList.length === 0 && !wipListLoading" class="settlement-empty">
          暂无可用半成品库存；如果本计划无需半成品领用，可以不新增半成品行。
        </div>
        <div
          v-for="(line, index) in completeForm.semiFinishedConsumptions"
          :key="`wip-${index}`"
          class="settlement-consumption-row"
        >
          <el-select
            v-model="line.semiFinishedInventoryId"
            filterable
            placeholder="选择半成品 WIP"
            :loading="wipListLoading"
            class="consumption-select"
          >
            <el-option
              v-for="wip in wipList"
              :key="wip.id"
              :label="`${wip.intermediateBatchNo} | 可用 ${wip.availableQuantity}${wip.unit || ''}`"
              :value="wip.id"
            />
          </el-select>
          <el-input-number
            v-model="line.quantity"
            :min="0"
            :max="selectedWipForSettlement(line.semiFinishedInventoryId) ? Number(selectedWipForSettlement(line.semiFinishedInventoryId)?.availableQuantity || 0) : undefined"
            :precision="2"
            class="consumption-qty"
          />
          <el-input
            v-model="line.note"
            placeholder="备注/交接单号"
            class="consumption-note"
          />
          <el-button text type="danger" @click="removeWipConsumptionLine(index)">删除</el-button>
          <div
            v-if="selectedWipForSettlement(line.semiFinishedInventoryId)"
            class="settlement-wip-boundary"
          >
            当前可用
            <strong>
              {{ selectedWipForSettlement(line.semiFinishedInventoryId)?.availableQuantity }}
              {{ selectedWipForSettlement(line.semiFinishedInventoryId)?.unit || '' }}
            </strong>
            ，本行最多领用
            <strong>
              {{ selectedWipForSettlement(line.semiFinishedInventoryId)?.availableQuantity }}
              {{ selectedWipForSettlement(line.semiFinishedInventoryId)?.unit || '' }}
            </strong>
            ；超出可用量会被拦截。
          </div>
        </div>

        <el-divider content-position="left">
          工时/人效最小字段
          <el-tag
            v-if="settlementFieldHasIssue('laborSegments')"
            type="warning"
            size="small"
            effect="light"
            style="margin-left: 6px"
          >需人工补全</el-tag>
        </el-divider>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="人数">
              <el-input-number
                v-model="completeForm.workerCount"
                :min="0"
                :precision="0"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="工时分钟">
              <el-input-number
                v-model="completeForm.workMinutes"
                :min="0"
                :precision="0"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>
        <div class="settlement-help" style="margin-bottom: 12px;">
          仅在上方出现异常时才需要补录或调整；正常计划会直接展示逐道报工汇总。
        </div>
        </template>
        <el-alert
          v-if="!settlementPrefillClean && completeSubmitDisabledReason"
          :title="completeSubmitDisabledReason"
          type="warning"
          show-icon
          :closable="false"
          style="margin-bottom: 4px"
        />
      </el-form>
      <template #footer>
        <el-button @click="requestCloseCompleteDialog">取消</el-button>
        <el-button
          type="primary"
          :loading="actionLoading"
          :disabled="!completeCanSubmit"
          @click="submitComplete"
        >确认结单并结束计划</el-button>
      </template>
    </el-dialog>

    <!-- 6.12: 生产报产与仓库实收必须双方核对；差异进入中转挂账 -->
    <el-dialog
      v-model="receiptDialogVisible"
      :title="receiptProductName ? `仓库确认入库 — ${receiptProductName}` : '仓库确认入库'"
      width="720px"
      top="6vh"
      class="settlement-dialog"
      destroy-on-close
      append-to-body
    >
      <el-form v-loading="receiptLoading" label-width="124px" class="settlement-form">
        <div class="settlement-context">
          <div>
            <div class="settlement-context-label">计划单号</div>
            <div class="settlement-context-value">{{ receiptPlanNumber || '-' }}</div>
          </div>
          <div>
            <div class="settlement-context-label">生产报产</div>
            <div class="settlement-context-value">
              {{ receiptReportedQuantity }} {{ receiptUnit }}
            </div>
          </div>
          <div>
            <div class="settlement-context-label">容差</div>
            <div class="settlement-context-value">
              {{ receiptTolerance }} {{ receiptUnit }}
            </div>
          </div>
        </div>

        <el-alert
          title="生产结单后不直接入成品库存；仓库确认实收后才入库。实收短少超过容差会生成中转挂账，待责任归属清账。"
          type="warning"
          show-icon
          :closable="false"
          style="margin: 12px 0"
        />

        <el-form-item label="仓库实收" required>
          <el-input-number
            v-model="receiptForm.receivedQuantity"
            :min="0"
            :max="receiptReportedQuantity"
            :precision="2"
            style="width: 100%"
          />
          <div class="settlement-help">
            上限为生产报产 {{ receiptReportedQuantity }} {{ receiptUnit }}，超过上限需先退回生产修正结单。
          </div>
        </el-form-item>

        <div class="receipt-diff-panel">
          <div>
            <span>生产报产</span>
            <strong>{{ receiptReportedQuantity }} {{ receiptUnit }}</strong>
          </div>
          <div>
            <span>仓库实收</span>
            <strong>{{ receiptReceivedQuantity }} {{ receiptUnit }}</strong>
          </div>
          <div :class="{ danger: receiptVarianceQuantity > receiptTolerance }">
            <span>待核差异</span>
            <strong>{{ receiptVarianceQuantity }} {{ receiptUnit }}</strong>
          </div>
        </div>

        <el-alert
          v-if="receiptReceivedQuantity > receiptReportedQuantity"
          title="仓库实收不能超过生产报产，系统已禁止提交。"
          type="error"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        />
        <el-alert
          v-else-if="receiptNeedsReason"
          title="差异超过容差，本次确认会生成中转挂账；请选择原因和责任侧。"
          type="error"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        />
        <el-alert
          v-else-if="receiptVarianceQuantity > 0"
          title="差异在称重容差内，系统会记录为容差入库，不生成中转挂账。"
          type="info"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        />

        <el-form-item v-if="receiptNeedsReason" label="差异原因" required>
          <el-select v-model="receiptForm.varianceReason" placeholder="请选择差异原因" style="width: 100%">
            <el-option
              v-for="opt in RECEIPT_VARIANCE_REASON_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="receiptNeedsReason && receiptForm.varianceReason === '其他'" label="原因补充" required>
          <el-input
            v-model="receiptForm.otherVarianceReason"
            type="textarea"
            :rows="2"
            placeholder="请说明差异现场原因"
          />
        </el-form-item>
        <el-form-item v-if="receiptNeedsReason" label="责任侧" required>
          <el-select v-model="receiptForm.responsibilitySide" placeholder="请选择责任侧" style="width: 100%">
            <el-option
              v-for="opt in RECEIPT_RESPONSIBILITY_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="receiptForm.varianceNote"
            type="textarea"
            :rows="2"
            placeholder="可记录称重单号、交接人、现场说明"
          />
        </el-form-item>
        <el-alert
          v-if="receiptSubmitDisabledReason"
          :title="receiptSubmitDisabledReason"
          type="warning"
          show-icon
          :closable="false"
          style="margin-bottom: 4px"
        />
      </el-form>
      <template #footer>
        <el-button @click="receiptDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="actionLoading"
          :disabled="!receiptCanSubmit"
          @click="submitWarehouseReceipt"
        >确认入库</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="clearingDialogVisible"
      :title="clearingProductName ? `中转挂账清账 — ${clearingProductName}` : '中转挂账清账'"
      width="560px"
      class="settlement-dialog"
      destroy-on-close
      append-to-body
    >
      <el-form v-loading="clearingLoading" label-width="112px" class="settlement-form">
        <div class="settlement-context">
          <div>
            <div class="settlement-context-label">计划单号</div>
            <div class="settlement-context-value">{{ clearingPlanNumber || '-' }}</div>
          </div>
          <div>
            <div class="settlement-context-label">当前状态</div>
            <div class="settlement-context-value">
              {{ postingStatusText(clearingSettlement?.postingStatus) }}
            </div>
          </div>
          <div>
            <div class="settlement-context-label">挂账说明</div>
            <div class="settlement-context-value">
              {{ clearingSettlement?.postingMessage || '-' }}
            </div>
          </div>
        </div>

        <el-alert
          title="清账只表示生产与仓库已处理并确认差异归属；未清账前该结单仍停留在中转挂账。"
          type="warning"
          show-icon
          :closable="false"
          style="margin: 12px 0"
        />

        <el-form-item label="清账原因" required>
          <el-select
            v-model="clearingForm.clearingReason"
            placeholder="请选择清账原因"
            style="width: 100%"
          >
            <el-option
              v-for="opt in CLEARING_REASON_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="clearingForm.clearingReason === '其他'" label="原因补充" required>
          <el-input
            v-model="clearingForm.otherClearingReason"
            type="textarea"
            :rows="2"
            placeholder="请说明生产/仓库/财务如何处理该差异"
          />
        </el-form-item>
        <el-form-item label="清账说明">
          <el-input
            v-model="clearingForm.clearingNote"
            type="textarea"
            :rows="3"
            placeholder="可填写交接人、盘点单号、财务确认记录等"
          />
        </el-form-item>
        <el-alert
          v-if="clearingDisabledReason"
          :title="clearingDisabledReason"
          type="warning"
          show-icon
          :closable="false"
        />
      </el-form>
      <template #footer>
        <el-button @click="clearingDialogVisible = false">取消</el-button>
        <el-button
          type="danger"
          :loading="actionLoading"
          :disabled="!clearingCanSubmit"
          @click="submitTransitClearing"
        >确认清账</el-button>
      </template>
    </el-dialog>

    <!-- #743 取消原因 dialog (快捷下拉 + 品名) -->
    <el-dialog
      v-model="cancelDialogVisible"
      :title="cancelProductName ? `取消计划 — ${cancelProductName} (${cancelPlanNumber})` : '取消计划'"
      width="460px"
      destroy-on-close
      append-to-body
    >
      <el-form label-width="100px">
        <el-form-item label="品名">
          <span>{{ cancelProductName || '-' }}</span>
        </el-form-item>
        <el-form-item label="计划单号">
          <span>{{ cancelPlanNumber || '-' }}</span>
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

    <!-- 编辑生产计划 dialog (更多→编辑 dead-stub 修复; 防呆 Rule 2: 标题带品名+单号) -->
    <el-dialog
      v-model="editDialogVisible"
      :title="editingProductName ? `编辑生产计划 — ${editingProductName} (${editingPlanNumber})` : '编辑生产计划'"
      width="520px"
      destroy-on-close
      append-to-body
    >
      <el-form v-loading="editDialogLoading" :model="editForm" label-width="120px">
        <el-form-item label="产品">
          <span>{{ editingProductName || '-' }}</span>
        </el-form-item>
        <el-form-item label="单号">
          <span>{{ editingPlanNumber || '-' }}</span>
        </el-form-item>
        <el-form-item label="计划生产日" required>
          <el-date-picker
            v-model="editForm.plannedDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择计划生产日"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="预计完成日期">
          <el-date-picker
            v-model="editForm.expectedCompletionDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="默认为计划生产日+1天"
            style="width: 100%"
            clearable
          />
        </el-form-item>
        <el-form-item label="计划数量" required>
          <el-input-number v-model="editForm.plannedQuantity" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="预计工人数">
          <el-input-number v-model="editForm.estimatedWorkers" :min="1" :max="500" style="width: 100%" placeholder="可不填" />
        </el-form-item>
        <el-form-item label="指派车间主管">
          <el-select v-model="editForm.assignedSupervisorId" clearable placeholder="可不填，稍后再指派" style="width: 100%">
            <el-option v-for="sup in supervisors" :key="sup.id" :label="sup.fullName || sup.username" :value="sup.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editForm.notes" type="textarea" :rows="2" placeholder="可不填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="editDialogLoading" @click="submitEditPlan">保存</el-button>
      </template>
    </el-dialog>

    <!-- SP2 独立再加工/返工计划创建 dialog -->
    <el-dialog
      v-model="secondaryDialogVisible"
      title="创建独立再加工/返工计划"
      width="520px"
      :close-on-click-modal="false"
      destroy-on-close
      append-to-body
    >
      <el-form :model="secondaryForm" label-width="110px" v-loading="wipListLoading">
        <!-- 防呆 Rule 1: 源 WIP 可用量前置显示，不等提交才报错 -->
        <el-form-item label="源半成品 WIP" required>
          <el-select
            v-model="secondaryForm.wipId"
            placeholder="选择可用半成品库存"
            filterable
            style="width: 100%"
            :loading="wipListLoading"
            @change="handleWipSelect"
          >
            <el-option
              v-for="wip in wipList"
              :key="wip.id"
              :value="wip.id"
              :label="`${wip.intermediateBatchNo} | 可用 ${wip.availableQuantity}${wip.unit || ''}`"
            >
              <span style="float: left; font-weight: 500;">{{ wip.intermediateBatchNo }}</span>
              <span style="float: right; font-size: 12px; color: #909399; margin-left: 12px;">
                可用 {{ wip.availableQuantity }}{{ wip.unit || '' }}
              </span>
            </el-option>
          </el-select>
          <!-- 防呆 Rule 1: 实时显示所选 WIP 可用量 -->
          <div v-if="selectedWip" style="margin-top: 6px; font-size: 12px; color: var(--el-color-success);">
            可用余量: <strong>{{ selectedWip.availableQuantity }} {{ selectedWip.unit || '' }}</strong>
            <template v-if="selectedWip.unitCost != null">
              ｜ 单位成本: ¥{{ Number(selectedWip.unitCost).toFixed(4) }}
            </template>
          </div>
          <div v-if="wipList.length === 0 && !wipListLoading" style="margin-top: 6px; font-size: 12px; color: var(--el-color-warning);">
            暂无可用半成品库存。请先完成报工产出 WIP，再创建独立再加工/返工计划。
          </div>
        </el-form-item>

        <el-form-item label="计划加工数量" required>
          <!-- 防呆 Rule 1: :max 限制不超过可用量 -->
          <el-input-number
            v-model="secondaryForm.quantity"
            :min="0.01"
            :max="selectedWip ? Number(selectedWip.availableQuantity) : undefined"
            :precision="2"
            style="width: 100%"
          />
          <div v-if="selectedWip" style="font-size: 12px; color: var(--text-color-secondary, #909399); margin-top: 4px;">
            最大可用 {{ selectedWip.availableQuantity }} {{ selectedWip.unit || '' }}（超出将被后端拒绝）
          </div>
        </el-form-item>

        <el-form-item label="目标产品类型" required>
          <el-select
            v-model="secondaryForm.productTypeId"
            placeholder="选择目标成品类型"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="item in productTypes"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
          <!-- 防呆 Rule 2: 来源上下文 -->
          <div v-if="selectedWip && secondaryForm.productTypeId" style="margin-top: 4px; font-size: 12px; color: var(--text-color-secondary, #909399);">
            源 WIP 批次: {{ selectedWip.intermediateBatchNo }}
          </div>
        </el-form-item>

        <el-form-item label="计划日期">
          <el-date-picker
            v-model="secondaryForm.plannedDate"
            type="date"
            placeholder="预期完成日期（默认明日）"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="secondaryDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="secondaryDialogLoading" @click="submitSecondaryPlan">创建独立再加工/返工计划</el-button>
      </template>
    </el-dialog>

    <!-- AI 对话创建 -->
    <AiEntryDrawer
      v-model="aiEntryVisible"
      :config="PRODUCTION_PLAN_CONFIG"
      :confirm-guard="guardProductionPlanAi"
      @fill-form="handleAiFill"
    />

    <!-- SP-F: 逐工序电子表格抽屉 -->
    <el-drawer
      v-model="entryDrawerVisible"
      :title="`逐工序录入 — ${entryRow?.productTypeName || entryRow?.productName || entryRow?.productTypeId || ''}`"
      size="80%"
      :close-on-click-modal="false"
      :destroy-on-close="false"
      :before-close="handleEntryDrawerBeforeClose"
    >
      <ProcessSheet
        v-if="entryRow"
        ref="processSheetRef"
        :factory-id="String(factoryId)"
        :plan-id="String(entryRow?.id || '')"
        :product-type-id="String(entryRow?.productTypeId || '')"
        :product-name="entryRow?.productTypeName || entryRow?.productName"
        :planned-quantity="Number(entryRow?.plannedQuantity || 0)"
        :planned-unit="entryRow?.plannedUnit || null"
        @submitted="onEntrySubmitted"
      />
    </el-drawer>

    <!-- 阅读汇总弹窗 -->
    <ProductionSummaryDialog
      v-model="summaryDialogVisible"
      :factory-id="String(factoryId)"
      :plan-id="summaryPlanId"
      :plan-number="summaryPlanNumber"
      :product-name="summaryProductName"
    />

    <!-- 撤销小结审批 (STOCKTAKE_APPROVAL_ROLES: 财务经理/工厂超管) -->
    <el-dialog v-model="reversalApprovalVisible" title="撤销小结审批" width="760px" destroy-on-close>
      <div style="margin-bottom:8px;color:#909399;font-size:13px;">
        待审批的撤销小结申请。审批通过将逆转该次小结的入库并还回被扣消耗；若相关半成品/成品已被下游领用/发货，执行时会拒绝（不绕过）。
      </div>
      <el-table :data="reversalRequests" v-loading="reversalApprovalLoading" size="small" border>
        <el-table-column prop="productionPlanId" label="计划" min-width="120" show-overflow-tooltip />
        <el-table-column prop="sessionSeq" label="小结次序" width="90" align="center" />
        <el-table-column prop="reason" label="撤销原因" min-width="150" show-overflow-tooltip />
        <el-table-column prop="requestedAt" label="申请时间" width="160" />
        <el-table-column label="操作" width="150" align="center">
          <template #default="{ row }">
            <el-button type="success" size="small" link
              :disabled="reversalActingId === row.id" :loading="reversalActingId === row.id"
              @click="handleApproveReversal(row)">审批通过</el-button>
            <el-button type="danger" size="small" link
              :disabled="reversalActingId === row.id"
              @click="handleRejectReversal(row)">驳回</el-button>
          </template>
        </el-table-column>
        <template #empty>暂无待审批的撤销申请</template>
      </el-table>
    </el-dialog>
  </div>
  </CanvasAwareWrapper>
</template>

<style lang="scss" scoped>
.wide-table :deep(.el-scrollbar__bar.is-horizontal) { opacity: 1; }
.ai-unit-preserved { margin-top: 4px; color: var(--el-text-color-secondary); font-size: 12px; }
// 校验失败字段红框锚定 (fool-proof-design Rule: 预先显示边界, 不只弹 toast 让用户自己找字段)。
.field-error {
  :deep(.el-select__wrapper) {
    box-shadow: 0 0 0 1px var(--el-color-danger, #f56c6c) inset !important;
  }
}

.field-error-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-color-danger, #f56c6c);
}

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

:deep(.el-table__body tr.plan-row-pending > td.el-table__cell) {
  background: #fff4cf !important;
}

:deep(.el-table__body tr.plan-row-in-progress > td.el-table__cell) {
  background: #e8f3ff !important;
}

:deep(.el-table__body tr.plan-row-completed > td.el-table__cell) {
  background: #e8f8df !important;
}

:deep(.el-table__body tr.plan-row-cancelled > td.el-table__cell) {
  background: #f5f7fa !important;
  color: var(--text-color-secondary, #909399);
}

:deep(.el-table__body tr.plan-row-exception > td.el-table__cell) {
  background: #fde8e8 !important;
}

:deep(.el-table__body tr.plan-row-pending:hover > td.el-table__cell) {
  background: #ffe9a8 !important;
}

:deep(.el-table__body tr.plan-row-in-progress:hover > td.el-table__cell) {
  background: #d8ebff !important;
}

:deep(.el-table__body tr.plan-row-completed:hover > td.el-table__cell) {
  background: #dcf3d0 !important;
}

:deep(.el-table__body tr.plan-row-exception:hover > td.el-table__cell) {
  background: #fad1d1 !important;
}

.plan-status-tag {
  min-width: 76px;
  height: 30px;
  justify-content: center;
  border-width: 0;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0;
}

.plan-status-pending {
  --el-tag-bg-color: #e6a23c;
  --el-tag-text-color: #ffffff;
}

.plan-status-in-progress {
  --el-tag-bg-color: #409eff;
  --el-tag-text-color: #ffffff;
}

.plan-status-completed {
  --el-tag-bg-color: #67c23a;
  --el-tag-text-color: #ffffff;
}

.plan-status-cancelled {
  --el-tag-bg-color: #909399;
  --el-tag-text-color: #ffffff;
}

.plan-status-exception {
  --el-tag-bg-color: #f56c6c;
  --el-tag-text-color: #ffffff;
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

.next-step-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
}

.next-step-hint {
  font-size: 12px;
  color: var(--text-color-secondary, #909399);
  line-height: 1.3;
}

.settlement-form {
  .el-divider {
    margin: 16px 0 14px;
  }
}

.settlement-dialog {
  display: flex;
  max-height: 92vh;
  flex-direction: column;

  .el-dialog__body {
    overflow-y: auto;
    padding-bottom: 12px;
  }

  .el-dialog__footer {
    border-top: 1px solid var(--border-color-lighter, #ebeef5);
    padding-top: 12px;
  }
}

.settlement-context {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 4px;
}

.settlement-context > div {
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--border-color-lighter, #ebeef5);
  border-radius: 8px;
  background: #f8fafc;
}

.settlement-context-label {
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--text-color-secondary, #909399);
}

.settlement-context-value {
  overflow: hidden;
  color: var(--text-color-primary, #303133);
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settlement-reconciliation-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.settlement-reconciliation-card,
.settlement-reconciliation-section {
  border: 1px solid var(--border-color-lighter, #ebeef5);
  border-radius: 8px;
  background: #f8fafc;
}

.settlement-reconciliation-card {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 12px;

  span {
    color: var(--text-color-secondary, #909399);
    font-size: 12px;
  }

  strong {
    color: var(--text-color-primary, #303133);
    font-size: 16px;
  }
}

.settlement-reconciliation-section {
  margin-top: 10px;
  overflow: hidden;
}

.settlement-reconciliation-title {
  padding: 9px 12px;
  border-bottom: 1px solid var(--border-color-lighter, #ebeef5);
  color: var(--text-color-primary, #303133);
  font-size: 13px;
  font-weight: 600;
}

.settlement-reconciliation-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 9px 12px;
  border-bottom: 1px solid var(--border-color-extra-light, #f2f6fc);
  color: var(--text-color-regular, #606266);
  font-size: 13px;

  &:last-child {
    border-bottom: 0;
  }

  strong {
    color: var(--text-color-primary, #303133);
    white-space: nowrap;
  }
}

.settlement-help {
  margin-top: 4px;
  color: var(--text-color-secondary, #909399);
  font-size: 12px;
  line-height: 1.5;
}

.settlement-loss-guide {
  color: var(--text-color-regular, #606266);
  font-size: 13px;
  line-height: 1.6;
}

.settlement-advisory-alert {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 12px;

  .el-alert {
    flex: 1;
  }

  .el-button {
    flex: 0 0 auto;
  }
}

.settlement-line-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 10px 0 8px;
  color: var(--text-color-primary, #303133);
  font-size: 14px;
  font-weight: 600;
}

.settlement-empty {
  margin-bottom: 10px;
  padding: 10px 12px;
  border: 1px dashed var(--border-color, #dcdfe6);
  border-radius: 6px;
  color: var(--text-color-secondary, #909399);
  font-size: 13px;
  line-height: 1.5;
}

.settlement-consumption-row {
  display: grid;
  grid-template-columns: minmax(220px, 1.2fr) minmax(120px, 0.5fr) minmax(150px, 0.8fr) auto;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.settlement-wip-boundary {
  grid-column: 1 / -1;
  margin-top: -2px;
  color: var(--text-color-secondary, #909399);
  font-size: 12px;
  line-height: 1.5;
}

.settlement-wip-boundary strong {
  color: var(--text-color-primary, #303133);
  font-weight: 600;
}

.consumption-select,
.consumption-qty,
.consumption-note {
  width: 100%;
}

.receipt-diff-panel {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin: 0 0 12px 124px;
}

.receipt-diff-panel > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid var(--border-color-lighter, #ebeef5);
  border-radius: 8px;
  background: #ffffff;
}

.receipt-diff-panel span {
  color: var(--text-color-secondary, #909399);
  font-size: 12px;
}

.receipt-diff-panel strong {
  overflow: hidden;
  color: var(--text-color-primary, #303133);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.receipt-diff-panel .danger {
  border-color: var(--color-danger-light-5, #fab6b6);
  background: var(--color-danger-light-9, #fef0f0);

  strong {
    color: var(--color-danger, #f56c6c);
  }
}

.document-trace {
  min-height: 180px;
}

.trace-document-card :deep(.el-card__body) {
  padding: 12px 14px;
}

.trace-document-header,
.trace-document-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.trace-document-header > div {
  display: flex;
  align-items: center;
  gap: 8px;
}

.trace-document-number {
  margin: 10px 0 6px;
  color: var(--text-color-primary, #303133);
  font-size: 15px;
  font-weight: 600;
}

.trace-document-meta {
  color: var(--text-color-secondary, #909399);
  font-size: 12px;
}

.selected-workflow-route {
  width: 100%;
  margin-top: 8px;
  padding: 12px;
  border: 1px solid #b9ddc6;
  border-radius: 10px;
  background: #f5fbf7;
}

.selected-workflow-route__head,
.workflow-candidate-card__top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.selected-workflow-route__head > div:first-child {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.selected-workflow-route__eyebrow {
  color: #548066;
  font-size: 11px;
}

.selected-workflow-route__head strong,
.workflow-candidate-card__process {
  color: #1d2c3d;
  font-size: 14px;
  line-height: 1.45;
}

.selected-workflow-route__meta,
.selected-workflow-route__outputs,
.workflow-candidate-card__meta,
.workflow-candidate-card__outputs {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.selected-workflow-route__meta {
  margin-top: 9px;
  color: #667085;
  font-size: 12px;
}

.selected-workflow-route__outputs {
  margin-top: 8px;
}

.workflow-route-decision-alert {
  width: 100%;
  margin-top: 8px;
}

.workflow-route-decision-alert :deep(.el-alert__content) {
  width: 100%;
}

.workflow-route-decision-alert .el-button {
  margin-top: 9px;
}

.workflow-candidate-dialog__hint {
  margin-bottom: 14px;
}

.workflow-candidate-list {
  display: flex;
  width: 100%;
  max-height: 58vh;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
  padding: 2px 4px 4px 2px;
}

.workflow-candidate-card {
  padding: 14px 16px;
  border: 1px solid #d9e1ea;
  border-radius: 12px;
  background: #fff;
  cursor: pointer;
  transition: border-color 150ms ease, box-shadow 150ms ease, background 150ms ease;
}

.workflow-candidate-card:hover {
  border-color: #91b9e9;
  box-shadow: 0 6px 18px rgba(47, 104, 170, 0.09);
}

.workflow-candidate-card.is-selected {
  border-color: var(--el-color-primary, #409eff);
  background: #f5f9ff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.1);
}

.workflow-candidate-card__top :deep(.el-radio) {
  min-width: 0;
  height: auto;
  flex: 1;
  align-items: flex-start;
  white-space: normal;
}

.workflow-candidate-card__top :deep(.el-radio__label) {
  min-width: 0;
  padding-right: 8px;
  white-space: normal;
}

.workflow-candidate-card__meta {
  margin: 9px 0;
  color: #7a8695;
  font-size: 12px;
}

.workflow-candidate-card__outputs {
  padding-top: 9px;
  border-top: 1px solid #edf0f4;
}

.workflow-candidate-card__label {
  margin-right: 2px;
  color: #667085;
  font-size: 12px;
  font-weight: 600;
}

.workflow-candidate-card :deep(.el-alert) {
  margin-top: 10px;
}

@media (max-width: 760px) {
  .settlement-consumption-row,
  .receipt-diff-panel,
  .settlement-reconciliation-grid {
    grid-template-columns: 1fr;
  }

  .receipt-diff-panel {
    margin-left: 0;
  }

  .selected-workflow-route__head,
  .workflow-candidate-card__top {
    flex-direction: column;
  }
}

</style>
