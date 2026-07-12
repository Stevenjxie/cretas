<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Delete, Rank, Refresh, QuestionFilled } from '@element-plus/icons-vue';
import { handleCatchError } from '@/utils/errorToast';
import {
  getActiveWorkProcesses,
  getProductWorkProcesses,
  createProductWorkProcess,
  deleteProductWorkProcess,
  batchSortProductWorkProcesses,
  updateProductWorkProcess,
  updateWorkProcess,
  getProductWorkProcessRecommendation,
  type WorkProcessItem,
  type ProductWorkProcessItem,
  type RecommendedWorkProcess,
  type ProcessSheetCustomFieldDef
} from '@/api/processProduction';
import { getOperatorUsers } from '@/api/factory';
import ProductProcessWorkflowEditor from './workflow/ProductProcessWorkflowEditor.vue';
import { usePinyinFilter } from './workflow/pinyinInitials';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const route = useRoute();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('system'));

// 产品列表（从后端获取）
const products = ref<Array<{ id: string; name: string }>>([]);
const selectedProductId = ref('');
const productsLoading = ref(false);

// #2: 顶部产品选择器拼音首字母搜索 (复用 pinyinInitials.ts 的共享 usePinyinFilter composable)。
const topProductFilter = usePinyinFilter(() => products.value, (p) => [p.name]);
const handleTopProductFilter = topProductFilter.handleFilter;
const handleTopProductVisibleChange = topProductFilter.handleVisibleChange;
const filteredTopProducts = topProductFilter.filtered;

// 服务端已关联工序（source of truth from backend）
const linkedProcesses = ref<ProductWorkProcessItem[]>([]);
const linkedLoading = ref(false);

// 可选工序（全部活跃工序）
const allProcesses = ref<WorkProcessItem[]>([]);

// 操作员列表（小组长下拉数据源）
const operators = ref<Array<{ id: number; username: string; realName?: string; fullName?: string }>>([]);

// ─────────────────────────────────────────────
// C4 — 草稿 / 显式保存
//
// DraftItem wraps ProductWorkProcessItem with a stable string draftKey
// so we can distinguish server items (draftKey = String(id)) from
// locally-added items (draftKey = '__draft__' + workProcessId).
// ─────────────────────────────────────────────

interface DraftItem extends ProductWorkProcessItem {
  /** stable string key for v-for and draft tracking */
  draftKey: string;
  /** true = this item exists only in local draft, not yet on server */
  isPending: boolean;
}

// Local draft array — all edits operate here first
const draftLinked = ref<DraftItem[]>([]);

// dirty = draft differs from server state
const dirty = ref(false);

// saving in progress
const saving = ref(false);

// ─────────────────────────────────────────────
// 从产品复制工序链
// ─────────────────────────────────────────────
const copyChainDialogVisible = ref(false);
const copyChainSourceId = ref('');
const copyChainLoading = ref(false);

/** Products available as copy source (excludes current target) */
const copySourceProducts = computed(() =>
  products.value.filter(p => p.id !== selectedProductId.value)
);

function openCopyChainDialog() {
  copyChainSourceId.value = '';
  copyChainDialogVisible.value = true;
}

async function confirmCopyChain() {
  if (!copyChainSourceId.value || !selectedProductId.value || !factoryId.value) return;
  copyChainLoading.value = true;
  try {
    const { post } = await import('@/api/request');
    const res = await post<{ copiedCount: number }>(
      `/${factoryId.value}/product-work-processes/copy-chain?sourceProductId=${copyChainSourceId.value}&targetProductId=${selectedProductId.value}`,
      {}
    );
    if (res.success) {
      ElMessage.success(`已复制 ${res.data?.copiedCount ?? 0} 道工序`);
      copyChainDialogVisible.value = false;
      await loadLinkedProcesses();
    } else {
      ElMessage.error(res.message || '复制失败');
    }
  } catch (e) {
    handleCatchError(e, '复制工序链失败');
  } finally {
    copyChainLoading.value = false;
  }
}

// Pending ops to flush on save
interface PendingAdd { type: 'add'; wp: WorkProcessItem }
interface PendingRemove { type: 'remove'; serverId: number }
interface PendingSort { type: 'sort' }
/** T121: multi-assignee pending op replaces single-workerId op */
interface PendingResponsible { type: 'responsible'; serverId: number; assigneeWorkerIds: number[]; productTypeId: string; workProcessId: string }
/** Wave2: 报工粒度切换 pending op (server item only; draft-only items send reportingRequired on POST) */
interface PendingReporting { type: 'reporting'; serverId: number; reportingRequired: boolean; productTypeId: string; workProcessId: string }
/** 张权 R4: 半成品注入工序切换 pending op (server item only; draft-only items send flag on POST) */
interface PendingInjection { type: 'injection'; serverId: number; allowSemiFinishedInjection: boolean; productTypeId: string; workProcessId: string }
/** 多上游混批切换 pending op (server item only; draft-only items send flag on POST) */
interface PendingMultiSource { type: 'multiSource'; serverId: number; allowMultipleUpstreamSources: boolean; productTypeId: string; workProcessId: string }
interface PendingFinishedGoodsSource { type: 'finishedGoodsSource'; serverId: number; allowFinishedGoodsSource: boolean; productTypeId: string; workProcessId: string }
type PendingOp = PendingAdd | PendingRemove | PendingSort | PendingResponsible | PendingReporting | PendingInjection | PendingMultiSource | PendingFinishedGoodsSource;
const pendingOps = ref<PendingOp[]>([]);
const recommendationNotice = ref('');

interface AIDraftStep {
  workProcessId: string;
  processName?: string;
  processOrder?: number;
  responsibleWorkerId?: number | null;
}

/** Convert server items to DraftItems */
function toDraftItems(items: ProductWorkProcessItem[]): DraftItem[] {
  return items.map(i => ({ ...i, draftKey: String(i.id), isPending: false }));
}

/** Reset draft to server state */
function resetDraftToServer() {
  draftLinked.value = toDraftItems(linkedProcesses.value);
  dirty.value = false;
  pendingOps.value = [];
}

/** Mark dirty and push an op (dedup sort ops) */
function markDirty(op: PendingOp) {
  dirty.value = true;
  if (op.type === 'sort') {
    pendingOps.value = pendingOps.value.filter(o => o.type !== 'sort');
  }
  pendingOps.value.push(op);
}

/** Guard: ask user before switching product if there are unsaved changes */
async function guardUnsaved(): Promise<boolean> {
  if (!dirty.value) return true;
  try {
    await ElMessageBox.confirm('有未保存改动，确定放弃？', '提示', {
      confirmButtonText: '放弃改动',
      cancelButtonText: '继续编辑',
      type: 'warning',
    });
    return true;
  } catch {
    return false;
  }
}

/** Save all pending ops to backend */
async function handleSave() {
  if (!factoryId.value || !selectedProductId.value || !dirty.value) return;
  saving.value = true;
  try {
    const toRemove = pendingOps.value.filter((o): o is PendingRemove => o.type === 'remove');
    const toAdd = pendingOps.value.filter((o): o is PendingAdd => o.type === 'add');
    const toResponsible = pendingOps.value.filter((o): o is PendingResponsible => o.type === 'responsible');
    const toReporting = pendingOps.value.filter((o): o is PendingReporting => o.type === 'reporting');
    const toInjection = pendingOps.value.filter((o): o is PendingInjection => o.type === 'injection');
    const toMultiSource = pendingOps.value.filter((o): o is PendingMultiSource => o.type === 'multiSource');
    const toFinishedGoodsSource = pendingOps.value.filter((o): o is PendingFinishedGoodsSource => o.type === 'finishedGoodsSource');
    const needsSort = pendingOps.value.some(o =>
      o.type === 'sort' || o.type === 'add' || o.type === 'remove'
    );

    // 1. Removals (server items only)
    for (const op of toRemove) {
      await deleteProductWorkProcess(factoryId.value, op.serverId);
    }

    // 2. Additions (T121: send assigneeWorkerIds if set; else fall back to responsibleWorkerId)
    for (const op of toAdd) {
      const draftItem = draftLinked.value.find(d => d.workProcessId === op.wp.id && d.isPending);
      const assigneeWorkerIds = draftItem?.assigneeWorkerIds ?? null;
      const responsibleWorkerId = draftItem?.responsibleWorkerId ?? undefined;
      // Wave2: 草稿态工序若标了免报, 随 create 一起发 (省略 → 后端默认 true 逐道报)
      const reportingRequired = draftItem?.reportingRequired;
      // 张权 R4: 草稿态工序若标了半成品注入, 随 create 一起发 (省略 → 后端默认 false 普通工序)
      const allowSemiFinishedInjection = draftItem?.allowSemiFinishedInjection;
      // 多上游混批: 草稿态工序若开启混批, 随 create 一起发 (省略 → 后端默认 false 单批)
      const allowMultipleUpstreamSources = draftItem?.allowMultipleUpstreamSources;
      const allowFinishedGoodsSource = draftItem?.allowFinishedGoodsSource;
      await createProductWorkProcess(factoryId.value, {
        productTypeId: selectedProductId.value,
        workProcessId: op.wp.id,
        processOrder: 999, // corrected by sort step below
        ...(reportingRequired === false ? { reportingRequired: false } : {}),
        ...(allowSemiFinishedInjection === true ? { allowSemiFinishedInjection: true } : {}),
        ...(allowMultipleUpstreamSources === true ? { allowMultipleUpstreamSources: true } : {}),
        ...(allowFinishedGoodsSource === true ? { allowFinishedGoodsSource: true } : {}),
        ...(assigneeWorkerIds && assigneeWorkerIds.length > 0
          ? { assigneeWorkerIds }
          : responsibleWorkerId != null ? { responsibleWorkerId } : {}),
      });
    }

    // 3. Re-fetch to get fresh server IDs after add/remove
    const freshRes = await getProductWorkProcesses(factoryId.value, selectedProductId.value);
    let freshItems: ProductWorkProcessItem[] = [];
    if (freshRes.success && freshRes.data) {
      freshItems = Array.isArray(freshRes.data) ? freshRes.data : [];
    }

    // 4. Assignee changes (T121: use assigneeWorkerIds; fall back to legacy responsibleWorkerId clear)
    const freshByWpId = new Map(freshItems.map(i => [i.workProcessId, i]));
    for (const op of toResponsible) {
      const freshItem = freshByWpId.get(op.workProcessId);
      if (!freshItem) continue;
      // T121: always send assigneeWorkerIds (empty array = clear all assignees)
      await updateProductWorkProcess(factoryId.value, freshItem.id, {
        productTypeId: freshItem.productTypeId,
        workProcessId: freshItem.workProcessId,
        assigneeWorkerIds: op.assigneeWorkerIds,
      });
    }

    // 4b. Wave2 报工粒度变更 (server items): partial update reportingRequired
    for (const op of toReporting) {
      const freshItem = freshByWpId.get(op.workProcessId);
      if (!freshItem) continue;
      await updateProductWorkProcess(factoryId.value, freshItem.id, {
        productTypeId: freshItem.productTypeId,
        workProcessId: freshItem.workProcessId,
        reportingRequired: op.reportingRequired,
      });
    }

    // 4c. 张权 R4 半成品注入工序变更 (server items): partial update allowSemiFinishedInjection
    for (const op of toInjection) {
      const freshItem = freshByWpId.get(op.workProcessId);
      if (!freshItem) continue;
      await updateProductWorkProcess(factoryId.value, freshItem.id, {
        productTypeId: freshItem.productTypeId,
        workProcessId: freshItem.workProcessId,
        allowSemiFinishedInjection: op.allowSemiFinishedInjection,
      });
    }

    // 4d. 多上游混批变更 (server items): partial update allowMultipleUpstreamSources
    for (const op of toMultiSource) {
      const freshItem = freshByWpId.get(op.workProcessId);
      if (!freshItem) continue;
      await updateProductWorkProcess(factoryId.value, freshItem.id, {
        productTypeId: freshItem.productTypeId,
        workProcessId: freshItem.workProcessId,
        allowMultipleUpstreamSources: op.allowMultipleUpstreamSources,
      });
    }

    for (const op of toFinishedGoodsSource) {
      const freshItem = freshByWpId.get(op.workProcessId);
      if (!freshItem) continue;
      await updateProductWorkProcess(factoryId.value, freshItem.id, {
        productTypeId: freshItem.productTypeId,
        workProcessId: freshItem.workProcessId,
        allowFinishedGoodsSource: op.allowFinishedGoodsSource,
      });
    }

    // 5. Sort — align fresh items to draft order
    if (needsSort && freshItems.length > 0) {
      const draftOrder = draftLinked.value.map(d => d.workProcessId);
      const sortedFresh = [...freshItems].sort((a, b) => {
        const ai = draftOrder.indexOf(a.workProcessId);
        const bi = draftOrder.indexOf(b.workProcessId);
        return (ai === -1 ? 999 : ai) - (bi === -1 ? 999 : bi);
      });
      const sortItems = sortedFresh.map((item, i) => ({ id: item.id, processOrder: i + 1 }));
      await batchSortProductWorkProcesses(factoryId.value, sortItems);
    }

    ElMessage.success('保存成功');
    await loadLinkedProcesses(); // reload to sync draft
  } catch (e) {
    handleCatchError(e, '保存失败');
  } finally {
    saving.value = false;
  }
}

// ─────────────────────────────────────────────
// Data loading
// ─────────────────────────────────────────────

onMounted(async () => {
  await loadProducts();
  await loadAllProcesses();
  await loadOperators();
  if (selectedProductId.value) {
    await loadLinkedProcesses();
  }
  await applyRouteRecommendationDraft();
});

// flag: suppress watch reload when guard-rejecting a product switch
let suppressNextWatch = false;

watch(selectedProductId, async (newVal, oldVal) => {
  if (newVal === oldVal) return;
  if (suppressNextWatch) { suppressNextWatch = false; return; }
  if (newVal) {
    await loadLinkedProcesses();
  } else {
    linkedProcesses.value = [];
    resetDraftToServer();
  }
});

async function loadProducts() {
  if (!factoryId.value) return;
  productsLoading.value = true;
  try {
    const { get } = await import('@/api/request');
    const res = await get<{ content: Array<{ id: string; name: string }> }>(
      `/${factoryId.value}/product-types`, { params: { page: 1, size: 1000 } }
    );
    if (res.success && res.data?.content) {
      products.value = res.data.content;
      if (products.value.length > 0 && !selectedProductId.value) {
        const preferredProductId = routeQueryString(route.query.productTypeId);
        const preferredExists = preferredProductId && products.value.some(p => p.id === preferredProductId);
        suppressNextWatch = true;
        selectedProductId.value = preferredExists ? preferredProductId : products.value[0].id;
      }
    }
  } catch (e) {
    handleCatchError(e, '加载产品列表失败');
  } finally {
    productsLoading.value = false;
  }
}

function routeQueryString(value: unknown): string {
  if (Array.isArray(value)) {
    return typeof value[0] === 'string' ? value[0] : '';
  }
  return typeof value === 'string' ? value : '';
}

async function loadAllProcesses() {
  if (!factoryId.value) return;
  try {
    const res = await getActiveWorkProcesses(factoryId.value);
    if (res.success && res.data) {
      allProcesses.value = Array.isArray(res.data) ? res.data : [];
    }
  } catch {
    // silent
  }
}

async function loadOperators() {
  if (!factoryId.value) return;
  try {
    const res = await getOperatorUsers(factoryId.value);
    if (res.success && res.data) {
      operators.value = Array.isArray(res.data) ? res.data : [];
    }
  } catch (e) {
    ElMessage.warning('小组长列表加载失败，请刷新后重试');
  }
}

async function loadLinkedProcesses() {
  if (!factoryId.value || !selectedProductId.value) return;
  linkedLoading.value = true;
  try {
    const res = await getProductWorkProcesses(factoryId.value, selectedProductId.value);
    if (res.success && res.data) {
      linkedProcesses.value = Array.isArray(res.data) ? res.data : [];
    }
  } catch (e) {
    handleCatchError(e, '加载关联工序失败');
  } finally {
    linkedLoading.value = false;
    resetDraftToServer(); // sync draft to fresh server state
  }
}

async function applyRouteRecommendationDraft() {
  if (!factoryId.value || !selectedProductId.value) return;
  if (routeQueryString(route.query.recommend) !== '1') return;

  try {
    const res = await getProductWorkProcessRecommendation(factoryId.value, selectedProductId.value, 5);
    if (!res.success || !res.data || res.data.recommendations.length === 0) {
      ElMessage.warning(res.message || res.data?.message || '暂未生成推荐工序，请手动配置');
      return;
    }

    let addedCount = 0;
    let skippedCount = 0;
    for (const recommendation of res.data.recommendations) {
      const alreadyInDraft = draftLinked.value.some(d => d.workProcessId === recommendation.workProcessId);
      if (alreadyInDraft) {
        continue;
      }
      const item = toWorkProcessItem(recommendation);
      if (item === null) {
        skippedCount++;
        continue;
      }
      handleAdd(item);
      addedCount++;
    }

    const skippedSuffix = skippedCount > 0 ? `（${skippedCount} 道推荐工序在工序库中不存在已跳过）` : '';
    recommendationNotice.value = `${res.data.notice}：已预填 ${addedCount} 道推荐工序，请检查顺序和责任小组长后再保存。${skippedSuffix}`;
    if (addedCount > 0) {
      ElMessage.success(`已预填 ${addedCount} 道 AI 推荐工序，请核对后保存`);
    } else if (skippedCount > 0) {
      ElMessage.warning(`推荐工序在工序库中均不存在（${skippedCount} 道已跳过），请手动配置`);
    } else {
      ElMessage.info('推荐工序已在当前草稿/配置中，无需重复添加');
    }
  } catch (e) {
    handleCatchError(e, '加载推荐工序失败');
  }
}

function toWorkProcessItem(recommendation: RecommendedWorkProcess): WorkProcessItem | null {
  const existing = allProcesses.value.find(wp => wp.id === recommendation.workProcessId);
  if (existing) {
    return existing;
  }
  console.warn(
    `[推荐工序] workProcessId="${recommendation.workProcessId}" (${recommendation.processName}) 在工序库中不存在，已跳过，不会伪造假记录`
  );
  return null;
}

// ─────────────────────────────────────────────
// Computed
// ─────────────────────────────────────────────

// Available pool: all processes minus those in draft
const availableProcesses = computed(() => {
  const linkedIds = new Set(draftLinked.value.map(p => p.workProcessId));
  return allProcesses.value.filter(p => !linkedIds.has(p.id));
});

// Pool category filter (筛选可添加工序按类型: 前处理/加工/包装...)
const poolCategoryFilter = ref('');

// Distinct categories present in the available pool
const poolCategories = computed(() => {
  const set = new Set<string>();
  for (const p of availableProcesses.value) {
    if (p.processCategory) set.add(p.processCategory);
  }
  return Array.from(set);
});

// Available pool filtered by selected category
const filteredAvailableProcesses = computed(() => {
  if (!poolCategoryFilter.value) return availableProcesses.value;
  return availableProcesses.value.filter(p => p.processCategory === poolCategoryFilter.value);
});

const selectedProductName = computed(() => {
  return products.value.find(p => p.id === selectedProductId.value)?.name || '';
});

// ─────────────────────────────────────────────
// C3 — 责任人下拉搜索 filter method
// ─────────────────────────────────────────────

// Per-item operator search query (keyed by draft item index or id)
const operatorFilterQuery = ref<Record<string, string>>({});

function filterOperator(query: string, opList: typeof operators.value) {
  if (!query) return opList;
  const q = query.toLowerCase();
  return opList.filter(op => {
    const name = (op.realName || op.fullName || op.username).toLowerCase();
    return name.includes(q);
  });
}

// Filtered operators per item (C3)
function filteredOperators(itemKey: string) {
  const q = operatorFilterQuery.value[itemKey] || '';
  return filterOperator(q, operators.value);
}

/**
 * 返回操作员的显示名称：优先 realName（即 fullName），次选 username。
 */
function operatorDisplayName(op: { id: number; username: string; realName?: string; fullName?: string }) {
  return op.realName || op.fullName || op.username;
}

/**
 * T121: 返回工序行的负责人显示名称（只读模式，支持多人）。
 */
function responsibleLabel(item: ProductWorkProcessItem): string {
  // T121: prefer assigneeWorkerIds (multi-assignee); fall back to single responsibleWorkerId
  const ids = (item.assigneeWorkerIds && item.assigneeWorkerIds.length > 0)
    ? item.assigneeWorkerIds
    : (item.responsibleWorkerId ? [item.responsibleWorkerId] : []);
  if (ids.length === 0) return '';
  return ids.map(id => {
    const op = operators.value.find(o => o.id === id);
    return op ? operatorDisplayName(op) : `#${id}`;
  }).join('、');
}

// ─────────────────────────────────────────────
// C4 — Draft CRUD handlers (no immediate API calls)
// ─────────────────────────────────────────────

/** Product selector change: guard unsaved first */
async function handleProductChange(newId: string) {
  // At this point el-select has already updated selectedProductId to newId via v-model.
  // We need the old product id before the change.
  const prevProductId = draftLinked.value.length > 0
    ? draftLinked.value[0].productTypeId
    : '';

  const ok = await guardUnsaved();
  if (!ok) {
    // User chose to continue editing — restore selectedProductId to previous product.
    // Use suppressNextWatch to prevent the watch from re-loading the old product
    // (it's already loaded; draft still has current state).
    if (prevProductId && prevProductId !== newId) {
      suppressNextWatch = true;
      selectedProductId.value = prevProductId;
    }
    return;
  }
  // ok — proceed; the watch will fire and load the new product
}

/** Add a process to draft (C4 draft add) */
function handleAdd(wp: WorkProcessItem) {
  if (!selectedProductId.value) return;
  const draftItem: DraftItem = {
    // id as number is required by ProductWorkProcessItem type; use 0 as sentinel for draft-only
    id: 0 as unknown as number,
    draftKey: `__draft__${wp.id}`,
    isPending: true,
    productTypeId: selectedProductId.value,
    workProcessId: wp.id,
    processOrder: draftLinked.value.length + 1,
    processName: wp.processName,
    processCategory: wp.processCategory,
    defaultUnit: wp.unit,
    unitOverride: null,
    estimatedMinutesOverride: null,
    defaultEstimatedMinutes: null,
    responsibleWorkerId: null,
    assigneeWorkerIds: null,
  };
  draftLinked.value = [...draftLinked.value, draftItem];
  markDirty({ type: 'add', wp });
}

function normalizeAIDraftStep(value: unknown): AIDraftStep | null {
  if (!isObjectRecord(value)) {
    return null;
  }

  const workProcessId = typeof value.workProcessId === 'string' ? value.workProcessId : '';
  if (!workProcessId) {
    return null;
  }

  const responsibleWorkerId = typeof value.responsibleWorkerId === 'number'
    ? value.responsibleWorkerId
    : null;
  return {
    workProcessId,
    processName: typeof value.processName === 'string' ? value.processName : undefined,
    processOrder: typeof value.processOrder === 'number' ? value.processOrder : undefined,
    responsibleWorkerId,
  };
}

function handleApplyAIDraft(payload: Record<string, unknown>): void {
  const rawDraft = payload.draft;
  if (!Array.isArray(rawDraft)) {
    ElMessage.warning('AI 草稿格式不正确，请重新生成');
    return;
  }

  const draftSteps = rawDraft
    .map(normalizeAIDraftStep)
    .filter((row): row is AIDraftStep => row !== null)
    .sort((a, b) => (a.processOrder ?? 999) - (b.processOrder ?? 999));

  const missingNames: string[] = [];
  const processById = new Map(allProcesses.value.map(process => [process.id, process]));
  for (const step of draftSteps) {
    const wp = processById.get(step.workProcessId);
    if (!wp) {
      missingNames.push(step.processName || step.workProcessId);
      continue;
    }

    let draftItem = draftLinked.value.find(d => d.workProcessId === step.workProcessId);
    if (!draftItem) {
      handleAdd(wp);
      draftItem = draftLinked.value.find(d => d.workProcessId === step.workProcessId);
    }
    if (draftItem && step.responsibleWorkerId != null) {
      // T121: wrap single responsibleWorkerId as single-element assigneeWorkerIds list
      handleAssigneesChange(draftItem, [step.responsibleWorkerId]);
    }
  }

  if (draftSteps.length > 0) {
    const aiOrder = new Map(draftSteps.map((step, index) => [step.workProcessId, index]));
    const ordered = [...draftLinked.value]
      .sort((a, b) => {
        const ai = aiOrder.get(a.workProcessId);
        const bi = aiOrder.get(b.workProcessId);
        if (ai == null && bi == null) return a.processOrder - b.processOrder;
        if (ai == null) return 1;
        if (bi == null) return -1;
        return ai - bi;
      })
      .map((item, index) => ({ ...item, processOrder: index + 1 }));
    const orderChanged = ordered.some((item, index) => item.workProcessId !== draftLinked.value[index]?.workProcessId);
    draftLinked.value = ordered;
    if (orderChanged) {
      markDirty({ type: 'sort' });
    }
  }

  const rawMissing = payload.missingProcesses;
  if (Array.isArray(rawMissing)) {
    for (const item of rawMissing) {
      if (isObjectRecord(item)) {
        const name = item.name;
        if (typeof name === 'string') missingNames.push(name);
      }
    }
  }

  if (missingNames.length > 0) {
    ElMessage.warning(`以下工序未在本厂工序库找到，请先去工序管理新建：${[...new Set(missingNames)].join('、')}`);
  } else {
    ElMessage.success('AI 草稿已应用，请检查后点击保存');
  }
}

function isObjectRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object';
}

/** Remove a process from draft (C4 draft remove) */
function handleRemove(item: DraftItem) {
  draftLinked.value = draftLinked.value.filter(d => d.draftKey !== item.draftKey);
  draftLinked.value = draftLinked.value.map((d, i) => ({ ...d, processOrder: i + 1 }));

  if (!item.isPending) {
    // Server item — queue delete + sort
    markDirty({ type: 'remove', serverId: item.id });
    markDirty({ type: 'sort' });
  } else {
    // Draft-only item — cancel the matching add op
    pendingOps.value = pendingOps.value.filter(
      o => !(o.type === 'add' && o.wp.id === item.workProcessId)
    );
    dirty.value = pendingOps.value.length > 0;
  }
}

/**
 * T121: Multi-assignee change handler (replaces single responsibleWorkerChange).
 * @param item - the DraftItem being edited
 * @param values - array of selected worker ids (empty array = clear all)
 */
function handleAssigneesChange(item: DraftItem, values: number[]) {
  const assigneeWorkerIds = values.length === 0 ? [] : values;
  // Keep responsibleWorkerId in sync with primary (first in list)
  const responsibleWorkerId = assigneeWorkerIds.length > 0 ? assigneeWorkerIds[0] : null;
  const idx = draftLinked.value.findIndex(d => d.draftKey === item.draftKey);
  if (idx !== -1) {
    draftLinked.value[idx] = {
      ...draftLinked.value[idx],
      assigneeWorkerIds,
      responsibleWorkerId,
    };
  }
  if (!item.isPending) {
    // Server item — dedupe and queue responsible op (T121: using assigneeWorkerIds)
    pendingOps.value = pendingOps.value.filter(
      o => !(o.type === 'responsible' && o.serverId === item.id)
    );
    markDirty({
      type: 'responsible',
      serverId: item.id,
      assigneeWorkerIds,
      productTypeId: item.productTypeId,
      workProcessId: item.workProcessId,
    });
  } else {
    // Draft-only item — tracked in local draft; sent on POST in save
    if (!dirty.value) dirty.value = true;
  }
}

/**
 * Wave2 可配置报工粒度: 切换某道工序是否需报工。
 * @param item - 被编辑的 DraftItem
 * @param value - true=逐道报(默认); false=免报(spawn 跳过)
 */
function handleReportingRequiredChange(item: DraftItem, value: boolean) {
  const idx = draftLinked.value.findIndex(d => d.draftKey === item.draftKey);
  if (idx !== -1) {
    draftLinked.value[idx] = { ...draftLinked.value[idx], reportingRequired: value };
  }
  if (!item.isPending) {
    // 服务端已存在的工序 — 去重后排队 update op
    pendingOps.value = pendingOps.value.filter(
      o => !(o.type === 'reporting' && o.serverId === item.id)
    );
    markDirty({
      type: 'reporting',
      serverId: item.id,
      reportingRequired: value,
      productTypeId: item.productTypeId,
      workProcessId: item.workProcessId,
    });
  } else {
    // 草稿态工序 — 值已记在本地草稿, POST 时随 create 一起发
    if (!dirty.value) dirty.value = true;
  }
}

/**
 * 张权 R4 半成品注入工序: 切换某道工序是否可从库里选已有半成品/成品直接投料。
 * @param item - 被编辑的 DraftItem
 * @param value - true=半成品注入工序(逐道录入显 SFI/FG picker); false=普通工序
 */
function handleInjectionChange(item: DraftItem, value: boolean) {
  const idx = draftLinked.value.findIndex(d => d.draftKey === item.draftKey);
  if (idx !== -1) {
    draftLinked.value[idx] = { ...draftLinked.value[idx], allowSemiFinishedInjection: value };
  }
  if (!item.isPending) {
    // 服务端已存在的工序 — 去重后排队 update op
    pendingOps.value = pendingOps.value.filter(
      o => !(o.type === 'injection' && o.serverId === item.id)
    );
    markDirty({
      type: 'injection',
      serverId: item.id,
      allowSemiFinishedInjection: value,
      productTypeId: item.productTypeId,
      workProcessId: item.workProcessId,
    });
  } else {
    // 草稿态工序 — 值已记在本地草稿, POST 时随 create 一起发
    if (!dirty.value) dirty.value = true;
  }
}

/**
 * 多上游混批: 切换某道工序是否可在报工时添加多个上游来源批。
 * @param item - 被编辑的 DraftItem
 * @param value - true=允许混批; false=单批
 */
function handleMultipleUpstreamSourcesChange(item: DraftItem, value: boolean) {
  const idx = draftLinked.value.findIndex(d => d.draftKey === item.draftKey);
  if (idx !== -1) {
    draftLinked.value[idx] = { ...draftLinked.value[idx], allowMultipleUpstreamSources: value };
  }
  if (!item.isPending) {
    pendingOps.value = pendingOps.value.filter(
      o => !(o.type === 'multiSource' && o.serverId === item.id)
    );
    markDirty({
      type: 'multiSource',
      serverId: item.id,
      allowMultipleUpstreamSources: value,
      productTypeId: item.productTypeId,
      workProcessId: item.workProcessId,
    });
  } else {
    if (!dirty.value) dirty.value = true;
  }
}

// ─────────────────────────────────────────────
// Move up/down (fallback buttons, C1)
// ─────────────────────────────────────────────

function handleFinishedGoodsSourceChange(item: DraftItem, value: boolean) {
  const idx = draftLinked.value.findIndex(d => d.draftKey === item.draftKey);
  if (idx !== -1) {
    draftLinked.value[idx] = { ...draftLinked.value[idx], allowFinishedGoodsSource: value };
  }
  if (!item.isPending) {
    pendingOps.value = pendingOps.value.filter(
      o => !(o.type === 'finishedGoodsSource' && o.serverId === item.id)
    );
    markDirty({
      type: 'finishedGoodsSource',
      serverId: item.id,
      allowFinishedGoodsSource: value,
      productTypeId: item.productTypeId,
      workProcessId: item.workProcessId,
    });
  } else {
    if (!dirty.value) dirty.value = true;
  }
}

function handleMoveUp(index: number) {
  if (index <= 0) return;
  const items = [...draftLinked.value];
  [items[index - 1], items[index]] = [items[index], items[index - 1]];
  draftLinked.value = items.map((d, i) => ({ ...d, processOrder: i + 1 }));
  markDirty({ type: 'sort' });
}

function handleMoveDown(index: number) {
  if (index >= draftLinked.value.length - 1) return;
  const items = [...draftLinked.value];
  [items[index], items[index + 1]] = [items[index + 1], items[index]];
  draftLinked.value = items.map((d, i) => ({ ...d, processOrder: i + 1 }));
  markDirty({ type: 'sort' });
}

// ─────────────────────────────────────────────
// C1 — Native HTML5 drag-and-drop reorder
// ─────────────────────────────────────────────

const dragFromIndex = ref<number | null>(null);
const dragOverIndex = ref<number | null>(null);

function onDragStart(index: number, event: DragEvent) {
  dragFromIndex.value = index;
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', String(index));
  }
}

function onDragOver(index: number, event: DragEvent) {
  event.preventDefault();
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move';
  dragOverIndex.value = index;
}

function onDragLeave() {
  dragOverIndex.value = null;
}

function onDrop(toIndex: number, event: DragEvent) {
  event.preventDefault();
  const fromIndex = dragFromIndex.value;
  dragFromIndex.value = null;
  dragOverIndex.value = null;
  if (fromIndex === null || fromIndex === toIndex) return;
  const items = [...draftLinked.value];
  const [moved] = items.splice(fromIndex, 1);
  items.splice(toIndex, 0, moved);
  draftLinked.value = items.map((d, i) => ({ ...d, processOrder: i + 1 }));
  markDirty({ type: 'sort' });
}

function onDragEnd() {
  dragFromIndex.value = null;
  dragOverIndex.value = null;
}

// ─────────────────────────────────────────────
// Refresh (reloads from server, discards draft if confirmed)
// ─────────────────────────────────────────────

async function handleRefresh() {
  const ok = await guardUnsaved();
  if (!ok) return;
  await loadLinkedProcesses();
}

// ───── 工序成本配置 (报工自动继承; 防呆: 操作员不手填会计类别/明细) ─────
const costDialogVisible = ref(false);
const costSaving = ref(false);
const costEditId = ref<number | null>(null);
const costEditName = ref('');
// 后端 update() @Valid 要求 productTypeId + workProcessId 非空 (即便 partial update), 否则 400。
const costEditProductTypeId = ref('');
const costEditWorkProcessId = ref('');
const costForm = ref<{
  defaultCostCategory: string; auxAllocMethod: string;
  packagingTemplate: Array<{ name: string; cost: number | null }>;
  standardYieldPct: number | null;   // 段2(B) 标准出成率, UI 用百分比 (85=85%), 保存折回小数
  auxUnitPrice: number | null;       // 段2(B) 辅料单价 元/kg
  auxBasis: string;                  // 段2(B) INPUT|OUTPUT
}>({
  defaultCostCategory: '', auxAllocMethod: '', packagingTemplate: [],
  standardYieldPct: null, auxUnitPrice: null, auxBasis: '',
});

function openCostConfig(item: any) {
  costEditId.value = item.id;
  costEditName.value = item.processName || item.workProcessId;
  costEditProductTypeId.value = item.productTypeId || selectedProductId.value || '';
  costEditWorkProcessId.value = item.workProcessId || '';
  costForm.value = {
    defaultCostCategory: item.defaultCostCategory || '',
    auxAllocMethod: item.auxAllocMethod || '',
    packagingTemplate: Array.isArray(item.packagingTemplate)
      ? item.packagingTemplate.map((p: any) => ({ name: p.name, cost: Number(p.cost) }))
      : [],
    standardYieldPct: item.standardYieldRate != null ? Number(item.standardYieldRate) * 100 : null,
    auxUnitPrice: item.auxUnitPrice != null ? Number(item.auxUnitPrice) : null,
    auxBasis: item.auxBasis || '',
  };
  costDialogVisible.value = true;
}
function addPkgRow() { costForm.value.packagingTemplate.push({ name: '', cost: null }); }
function removePkgRow(i: number) { costForm.value.packagingTemplate.splice(i, 1); }

async function saveCostConfig() {
  if (costEditId.value == null) return;
  costSaving.value = true;
  try {
    const pkg = costForm.value.packagingTemplate
      .filter((r) => r.name && r.cost != null)
      .map((r) => ({ name: r.name, cost: Number(r.cost) }));
    // 仅传成本字段做 partial update (后端 null=no-change; packagingTemplate 传 [] 可清空)
    // 后端 update() @Valid 强制 productTypeId + workProcessId 非空 → 必带 (identity, update 不改值)。
    const payload: Record<string, unknown> = {
      packagingTemplate: pkg,
      productTypeId: costEditProductTypeId.value,
      workProcessId: costEditWorkProcessId.value,
    };
    if (costForm.value.defaultCostCategory) payload.defaultCostCategory = costForm.value.defaultCostCategory;
    if (costForm.value.auxAllocMethod) payload.auxAllocMethod = costForm.value.auxAllocMethod;
    // 段2(B): 标准率 UI 百分比 → 小数 (0.85); 辅料单价/基准
    if (costForm.value.standardYieldPct != null) payload.standardYieldRate = costForm.value.standardYieldPct / 100;
    if (costForm.value.auxUnitPrice != null) payload.auxUnitPrice = costForm.value.auxUnitPrice;
    if (costForm.value.auxBasis) payload.auxBasis = costForm.value.auxBasis;
    await updateProductWorkProcess(factoryId.value, costEditId.value, payload as Partial<ProductWorkProcessItem>);
    ElMessage.success('成本配置已保存 (报工将自动继承)');
    costDialogVisible.value = false;
    await loadLinkedProcesses();
  } catch (e) {
    handleCatchError(e, '保存成本配置失败');
  } finally {
    costSaving.value = false;
  }
}

// ───── G2 工序自定义字段 (config-driven 逐工序电子表格自定义列, 如 波美度/添加剂量/备注) ─────
// 注意: 编辑的是 WorkProcess.customFieldSchema (共享工序目录字段, 见 WorkProcess.java), 不是
// 本页其它开关那样的 ProductWorkProcess 链接行字段 —— 因此不走 pendingOps/草稿-保存 流程,
// 而是直接调 updateWorkProcess(workProcessId, ...) 立即持久化 (与 handleSave 的批量提交解耦)。
// 影响范围: 改动会作用于该 workProcessId 在**所有**产品上的自定义字段列 (工序是跨产品共享的目录项)。
const customFieldDialogVisible = ref(false);
const customFieldSaving = ref(false);
const customFieldEditWorkProcessId = ref('');
const customFieldEditName = ref('');
// WorkProcess 更新 DTO 上 processName @NotBlank (必填), 保存自定义字段的 PUT /work-processes/{id}
// 必须带上该工序已有的 processName, 否则后端 400 "工序名称不能为空" —— 自定义字段根本存不上。
// processCategory/unit 仅 @Size (可空), 但一并回传已有值 (partial update no-op 覆盖), 与后端
// update() 语义对齐, 也防将来新增必填字段再次踩坑。均取自 item 的 join 只读字段 (server 行必有)。
const customFieldEditProcessName = ref('');
const customFieldEditProcessCategory = ref<string | undefined>(undefined);
const customFieldEditUnit = ref<string | undefined>(undefined);
const customFieldForm = ref<ProcessSheetCustomFieldDef[]>([]);

function openCustomFieldConfig(item: any) {
  customFieldEditWorkProcessId.value = item.workProcessId || '';
  customFieldEditName.value = item.processName || item.workProcessId;
  // 捕获该工序已有的必填/身份字段 (随 PUT 一并回传, 满足 WorkProcessDTO @NotBlank processName)
  customFieldEditProcessName.value = item.processName || '';
  customFieldEditProcessCategory.value = item.processCategory ?? undefined;
  customFieldEditUnit.value = item.defaultUnit ?? undefined;
  customFieldForm.value = Array.isArray(item.customFieldSchema)
    ? item.customFieldSchema.map((f: any) => ({
        key: f.key ?? '',
        label: f.label ?? '',
        type: (f.type === 'text' || f.type === 'date') ? f.type : 'number',
        enabled: f.enabled !== false,
      }))
    : [];
  customFieldDialogVisible.value = true;
}
function addCustomFieldRow() {
  customFieldForm.value.push({ key: '', label: '', type: 'number', enabled: true });
}
function removeCustomFieldRow(i: number) {
  customFieldForm.value.splice(i, 1);
}

async function saveCustomFieldConfig() {
  if (!customFieldEditWorkProcessId.value) return;
  // 防呆: key/label 必填 + key 唯一 才允许保存 —— 否则存进 schema 会产生前端无法渲染
  // (缺 label) 或后端校验白名单里出现两个同 key (歧义) 的坏配置。
  const rows = customFieldForm.value;
  if (rows.some((r) => !r.key.trim() || !r.label.trim())) {
    ElMessage.error('每个自定义字段必须填写 key 和显示名称');
    return;
  }
  const keys = rows.map((r) => r.key.trim());
  if (new Set(keys).size !== keys.length) {
    ElMessage.error('自定义字段 key 不能重复');
    return;
  }
  // WorkProcess 更新 DTO processName @NotBlank — 必须回传已有工序名, 否则后端 400。
  // 防呆: 拿不到已有工序名 (理论上不会, server 行都有 join processName) 时明确报错而非发坏请求。
  const existingProcessName = customFieldEditProcessName.value.trim();
  if (!existingProcessName) {
    ElMessage.error('无法获取该工序名称，请刷新页面后重试');
    return;
  }
  customFieldSaving.value = true;
  try {
    // 随 customFieldSchema 一并回传该工序已有的 processName (必填) + processCategory/unit (可空,
    // partial update 语义下等于 no-op 覆盖), 满足 WorkProcessDTO @Valid 校验, 不改动其它字段。
    const payload: Partial<WorkProcessItem> & { customFieldSchema: ProcessSheetCustomFieldDef[] } = {
      processName: existingProcessName,
      customFieldSchema: rows.map((r) => ({
        key: r.key.trim(), label: r.label.trim(), type: r.type, enabled: r.enabled,
      })),
    };
    if (customFieldEditProcessCategory.value != null) payload.processCategory = customFieldEditProcessCategory.value;
    if (customFieldEditUnit.value != null) payload.unit = customFieldEditUnit.value;
    await updateWorkProcess(factoryId.value, customFieldEditWorkProcessId.value, payload);
    ElMessage.success('自定义字段已保存（逐工序录入将显示新增列）');
    customFieldDialogVisible.value = false;
    await loadLinkedProcesses(); // 重新拉取 join 后的 customFieldSchema
  } catch (e) {
    handleCatchError(e, '保存自定义字段失败');
  } finally {
    customFieldSaving.value = false;
  }
}
</script>

<template>
  <div class="page-container">
    <el-card>
      <div class="toolbar">
        <div class="toolbar-left">
          <h2 style="margin: 0">产品-工序配置</h2>
          <el-tag type="info">{{ factoryId }}</el-tag>
          <el-select
            v-model="selectedProductId"
            placeholder="选择要配置的产品（支持拼音首字母搜索）"
            filterable
            style="width: 320px"
            :loading="productsLoading"
            :filter-method="handleTopProductFilter"
            @visible-change="handleTopProductVisibleChange"
            @change="handleProductChange"
          >
            <el-option v-for="p in filteredTopProducts" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </div>
        <div class="toolbar-right">
          <!-- C4 status indicator + save button -->
          <template v-if="selectedProductId && canWrite">
            <span v-if="dirty" class="dirty-indicator">● 有未保存改动</span>
            <span v-else class="clean-indicator">✓ 已保存</span>
            <el-button
              type="primary"
              :disabled="!dirty || saving"
              :loading="saving"
              @click="handleSave"
              style="margin-left: 8px"
            >
              保存兼容列表
            </el-button>
          </template>
          <el-button
            v-if="selectedProductId && canWrite"
            :disabled="copyChainLoading"
            @click="openCopyChainDialog"
          >从产品复制工序链</el-button>
          <el-button :icon="Refresh" @click="handleRefresh" />
        </div>
      </div>
    </el-card>

    <ProductProcessWorkflowEditor
      class="workflow-section"
      :factory-id="factoryId || ''"
      :product-type-id="selectedProductId"
      :product-name="selectedProductName"
      :can-write="canWrite"
    />

    <el-collapse class="legacy-compatibility">
      <el-collapse-item name="legacy">
        <template #title>
          <div class="legacy-title">
            <span>兼容列表 · 现有报工运行时仍按这里的线性工序链执行</span>
            <el-tag size="small" type="info">核对 / 兼容维护</el-tag>
          </div>
        </template>
    <div class="content-layout">
      <!-- 左：产品选择 (fixed 280px) -->
      <el-card class="product-panel">
        <template #header>
          <span style="font-weight: 600">选择产品</span>
        </template>
        <el-select
          v-model="selectedProductId"
          placeholder="选择产品"
          filterable
          style="width: 100%; margin-bottom: 12px"
          :loading="productsLoading"
          @change="handleProductChange"
        >
          <el-option v-for="p in products" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>

        <div v-if="selectedProductId" class="product-info">
          <el-tag>{{ selectedProductName }}</el-tag>
          <el-text type="info" size="small" style="margin-top: 8px; display: block">
            已配 {{ draftLinked.length }} 道工序
          </el-text>
        </div>

      </el-card>

      <!-- 右：C2 — 左右两栏布局 (工序流程 | 可添加工序池) -->
      <el-card class="process-panel" v-loading="linkedLoading">
        <template #header>
          <div class="card-header">
            <div class="panel-titles">
              <span class="panel-title-left">工序流程（按顺序执行）</span>
              <span class="panel-title-right" v-if="selectedProductId && canWrite">可添加的工序</span>
            </div>
          </div>
        </template>

        <div v-if="!selectedProductId" class="no-product-hint">
          <el-empty description="请在左侧选择产品" :image-size="80" />
        </div>

        <template v-else>
          <el-alert
            v-if="recommendationNotice"
            :title="recommendationNotice"
            type="warning"
            show-icon
            :closable="false"
            style="margin-bottom: 12px"
          />
        </template>

        <div v-if="selectedProductId" class="two-column-layout">
          <!-- ───── LEFT column: 工序链 (C1 drag + C4 draft) ───── -->
          <div class="chain-column">
            <div v-if="draftLinked.length === 0" class="empty-state">
              <el-empty description="暂无工序，请从右侧添加" :image-size="60" />
            </div>

            <div
              v-for="(item, index) in draftLinked"
              :key="item.draftKey"
              class="linked-item"
              :class="{
                'is-draft': item.isPending,
                'drag-over': dragOverIndex === index,
              }"
              :draggable="canWrite"
              @dragstart="onDragStart(index, $event)"
              @dragover="onDragOver(index, $event)"
              @dragleave="onDragLeave"
              @drop="onDrop(index, $event)"
              @dragend="onDragEnd"
            >
              <!-- Drag handle hint -->
              <div class="drag-handle" v-if="canWrite" title="拖拽排序">⠿</div>

              <div class="step-number">{{ index + 1 }}</div>
              <div class="step-info">
                <div class="step-name">
                  {{ item.processName || item.workProcessId }}
                  <el-tag v-if="item.isPending" size="small" type="warning" style="margin-left: 6px">待保存</el-tag>
                </div>
                <div class="step-meta">
                  <el-tag v-if="item.processCategory" size="small" type="info">{{ item.processCategory }}</el-tag>
                  <span class="step-unit">单位: {{ item.unitOverride || item.defaultUnit || '-' }}</span>
                  <!-- Wave2 可配置报工粒度: 是否需报工 (默认勾选=逐道报; 取消=该工序免报) -->
                  <span class="step-reporting" v-if="canWrite">
                    <el-switch
                      :model-value="item.reportingRequired !== false"
                      size="small"
                      inline-prompt
                      active-text="报工"
                      inactive-text="免报"
                      @change="(val: boolean) => handleReportingRequiredChange(item, val)"
                    />
                    <el-tooltip
                      content="开启=该工序需报工(逐道); 关闭=免报, 仅保留工序配置不生成报工任务 (适合中间工序, 只在领料/产出两点报工)"
                      placement="top"
                    >
                      <el-icon class="reporting-hint"><QuestionFilled /></el-icon>
                    </el-tooltip>
                  </span>
                  <el-tag v-else-if="item.reportingRequired === false" size="small" type="warning">免报</el-tag>
                  <!-- 张权 R4 半成品注入工序: 开启后该工序逐道录入可从库里选已有半成品/成品直接投料 -->
                  <span class="step-injection" v-if="canWrite">
                    <el-switch
                      :model-value="item.allowSemiFinishedInjection === true"
                      size="small"
                      inline-prompt
                      active-text="半成品注入"
                      inactive-text="普通工序"
                      @change="(val: boolean) => handleInjectionChange(item, val)"
                    />
                    <el-tooltip placement="top">
                      <template #content>
                        开启后, 此工序录入时可从仓库选已有的半成品/成品直接作为投料来源<br />
                        (跳过前面的工序, 从这一道接着生产)。<br />
                        用途: 生产第二个产品时不用重配前段工序, 直接从中段用已有半成品/成品接上。
                      </template>
                      <el-icon class="reporting-hint"><QuestionFilled /></el-icon>
                    </el-tooltip>
                  </span>
                  <el-tag v-else-if="item.allowSemiFinishedInjection === true" size="small" type="success">半成品注入</el-tag>
                  <!-- 多上游混批: 开启后本道报工可添加多个上游来源批, 关闭后只允许单一上游批次 -->
                  <span class="step-injection" v-if="canWrite">
                    <el-switch
                      :model-value="item.allowMultipleUpstreamSources === true"
                      size="small"
                      inline-prompt
                      active-text="混批"
                      inactive-text="单批"
                      @change="(val: boolean) => handleMultipleUpstreamSourcesChange(item, val)"
                    />
                    <el-tooltip placement="top">
                      <template #content>
                        开启后, 此工序报工时可选择多个上游批次并分别填写投料量。<br />
                        关闭后, 此工序只走单一上游批次, 适合不需要混批追溯的简单工序。
                      </template>
                      <el-icon class="reporting-hint"><QuestionFilled /></el-icon>
                    </el-tooltip>
                  </span>
                  <!-- 5988 成品源: 开启后报工来源可额外选择同产品族成品库存 -->
                  <span class="step-injection" v-if="canWrite">
                    <el-switch
                      :model-value="item.allowFinishedGoodsSource === true"
                      size="small"
                      inline-prompt
                      active-text="成品源"
                      inactive-text="禁成品"
                      @change="(val: boolean) => handleFinishedGoodsSourceChange(item, val)"
                    />
                    <el-tooltip
                      content="开启后，报工来源批次可额外选择同产品族成品库存；默认关闭，避免把成品仓库存混入普通生产来源。"
                      placement="top"
                    >
                      <el-icon class="reporting-hint"><QuestionFilled /></el-icon>
                    </el-tooltip>
                  </span>
                  <template v-else>
                    <el-tag v-if="item.allowMultipleUpstreamSources === true" size="small" type="success">混批</el-tag>
                    <el-tag v-if="item.allowFinishedGoodsSource === true" size="small" type="warning">成品源</el-tag>
                  </template>
                  <!-- G2 自定义字段: 已配置且至少 1 个启用项时显示提示 (Rule 1: 预先显示边界); 与读写模式无关, 始终展示汇总 -->
                  <el-tag
                    v-if="Array.isArray(item.customFieldSchema) && item.customFieldSchema.some((f: any) => f.enabled !== false)"
                    size="small" type="success">
                    自定义字段×{{ item.customFieldSchema.filter((f: any) => f.enabled !== false).length }}
                  </el-tag>
                </div>

                <!-- C3 / T121 — 多人负责多选下拉 (filterable, multiple) -->
                <div class="step-leader" v-if="canWrite">
                  <el-select
                    :model-value="item.assigneeWorkerIds ?? (item.responsibleWorkerId ? [item.responsibleWorkerId] : [])"
                    multiple
                    collapse-tags
                    collapse-tags-tooltip
                    placeholder="设置负责人（可多选）"
                    clearable
                    filterable
                    :filter-method="(q: string) => { operatorFilterQuery[item.draftKey] = q }"
                    size="small"
                    style="width: 200px"
                    @change="(vals: number[]) => handleAssigneesChange(item, vals)"
                  >
                    <el-option
                      v-for="op in filteredOperators(item.draftKey)"
                      :key="op.id"
                      :label="operatorDisplayName(op)"
                      :value="op.id"
                    />
                  </el-select>
                </div>
                <div class="step-leader-readonly" v-else-if="item.responsibleWorkerId || (item.assigneeWorkerIds && item.assigneeWorkerIds.length > 0)">
                  <el-text type="info" size="small">
                    负责人: {{ responsibleLabel(item) }}
                  </el-text>
                </div>
              </div>

              <div class="step-actions" v-if="canWrite">
                <el-button text size="small" v-if="!item.isPending" @click="openCostConfig(item)" title="成本配置 (报工自动继承)">成本</el-button>
                <el-button text size="small" v-if="!item.isPending" @click="openCustomFieldConfig(item)" title="自定义字段 (波美度/添加剂量/备注等, 逐工序电子表格自动显示)">字段</el-button>
                <el-button text size="small" :disabled="index === 0" @click="handleMoveUp(index)" title="上移">
                  <el-icon><Rank /></el-icon>
                </el-button>
                <el-button text size="small" :disabled="index === draftLinked.length - 1" @click="handleMoveDown(index)" title="下移">
                  <el-icon><Rank /></el-icon>
                </el-button>
                <el-button type="danger" text size="small" @click="handleRemove(item)" title="移除">
                  <el-icon><Delete /></el-icon>
                </el-button>
              </div>
            </div>
          </div>

          <!-- ───── C2 Separator ───── -->
          <div class="column-divider" v-if="canWrite"></div>

          <!-- ───── RIGHT column: 可添加工序池 ───── -->
          <div class="pool-column" v-if="canWrite">
            <div class="pool-filter">
              <el-select
                v-model="poolCategoryFilter"
                placeholder="全部类型"
                clearable
                size="small"
                style="width: 100%"
              >
                <el-option label="全部类型" value="" />
                <el-option v-for="cat in poolCategories" :key="cat" :label="cat" :value="cat" />
              </el-select>
            </div>
            <div v-if="filteredAvailableProcesses.length === 0" class="empty-hint">
              <el-text type="info" size="small">
                {{ availableProcesses.length === 0 ? '所有工序已关联，或尚未创建工序' : '该类型下暂无可添加工序' }}
              </el-text>
            </div>
            <div v-for="wp in filteredAvailableProcesses" :key="wp.id" class="available-item">
              <div class="available-info">
                <span class="available-name">{{ wp.processName }}</span>
                <el-tag v-if="wp.processCategory" size="small" type="info">{{ wp.processCategory }}</el-tag>
                <span class="available-unit">{{ wp.unit }}</span>
              </div>
              <el-button type="primary" text size="small" :icon="Plus" @click="handleAdd(wp)">添加</el-button>
            </div>
          </div>
        </div>
      </el-card>
    </div>
      </el-collapse-item>
    </el-collapse>

    <!-- 工序成本配置 dialog (报工自动继承) -->
    <el-dialog v-model="costDialogVisible" :title="`工序成本配置 — ${costEditName}`" width="540px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px"
        title="报工时自动继承这些默认值, 操作员无需手填 (防呆); 报工显式传值时以报工为准。" />
      <el-form label-width="120px">
        <el-form-item label="默认成本类别">
          <el-select v-model="costForm.defaultCostCategory" clearable placeholder="不设置 (按工序顺序启发式)" style="width: 100%">
            <el-option label="原料 RAW_MATERIAL" value="RAW_MATERIAL" />
            <el-option label="调料 SEASONING" value="SEASONING" />
            <el-option label="辅料 AUXILIARY" value="AUXILIARY" />
            <el-option label="包装 PACKAGING" value="PACKAGING" />
            <el-option label="其他 OTHER" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="辅料分摊方式">
          <el-select v-model="costForm.auxAllocMethod" clearable placeholder="不设置" style="width: 100%">
            <el-option label="按产出量 BY_OUTPUT" value="BY_OUTPUT" />
            <el-option label="固定比例 FIXED_RATIO" value="FIXED_RATIO" />
          </el-select>
        </el-form-item>
        <el-divider content-position="left" style="margin: 4px 0 12px">辅料标准单价对账 (抓多投/误差)</el-divider>
        <el-form-item label="标准出成率">
          <el-input-number v-model="costForm.standardYieldPct" :min="0" :max="300" :precision="2"
            controls-position="right" placeholder="如 85 = 85%" style="width: 200px" />
          <span style="margin-left: 8px; color: #909399; font-size: 12px">% (配方率, 投料对账基准; 保水工序可 &gt;100)</span>
        </el-form-item>
        <el-form-item label="辅料单价">
          <el-input-number v-model="costForm.auxUnitPrice" :min="0" :precision="4"
            controls-position="right" placeholder="元/kg" style="width: 200px" />
          <span style="margin-left: 8px; color: #909399; font-size: 12px">元/kg (离线按配方算好; 不填按 0 计)</span>
        </el-form-item>
        <el-form-item label="单价基准">
          <el-select v-model="costForm.auxBasis" clearable placeholder="不设置 (默认按投入侧)" style="width: 200px">
            <el-option label="投入侧 INPUT" value="INPUT" />
            <el-option label="产出侧 OUTPUT" value="OUTPUT" />
          </el-select>
          <span style="margin-left: 8px; color: #909399; font-size: 12px">元/kg 乘投入还是产出侧 kg (保水工序须选)</span>
        </el-form-item>
        <el-form-item label="包装明细模板">
          <div style="width: 100%">
            <div v-for="(row, i) in costForm.packagingTemplate" :key="i" style="display:flex; gap:8px; margin-bottom:6px">
              <el-input v-model="row.name" placeholder="包材名 (膜/气体/标签/其他)" style="flex:1" />
              <el-input-number v-model="row.cost" :min="0" :precision="2" controls-position="right" style="width:140px" />
              <el-button text type="danger" @click="removePkgRow(i)">删</el-button>
            </div>
            <el-button text type="primary" @click="addPkgRow">+ 添加包材项</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="costDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="costSaving" @click="saveCostConfig">保存</el-button>
      </template>
    </el-dialog>

    <!-- G2 工序自定义字段 dialog (config-driven 逐工序电子表格自定义列) -->
    <el-dialog v-model="customFieldDialogVisible" :title="`自定义字段 — ${customFieldEditName}`" width="620px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px"
        title="为该工序增加自定义录入列 (如波美度/添加剂量/备注)。逐工序电子表格录入时会显示对应输入框; 停用后列隐藏, 已录历史值不丢失。工序为跨产品共享目录, 改动对所有使用该工序的产品生效。" />
      <div v-for="(row, i) in customFieldForm" :key="i" style="display:flex; gap:8px; margin-bottom:8px; align-items:center">
        <el-input v-model="row.key" placeholder="字段 key (英文, 如 baume)" style="width:170px" />
        <el-input v-model="row.label" placeholder="显示名称 (如 波美度)" style="width:150px" />
        <el-select v-model="row.type" style="width:100px">
          <el-option label="数字" value="number" />
          <el-option label="文本" value="text" />
          <el-option label="日期" value="date" />
        </el-select>
        <el-switch v-model="row.enabled" inline-prompt active-text="启用" inactive-text="停用" />
        <el-button text type="danger" @click="removeCustomFieldRow(i)">删</el-button>
      </div>
      <el-button text type="primary" @click="addCustomFieldRow">+ 添加字段</el-button>
      <template #footer>
        <el-button @click="customFieldDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="customFieldSaving" @click="saveCustomFieldConfig">保存</el-button>
      </template>
    </el-dialog>

    <!-- 从产品复制工序链 dialog -->
    <el-dialog
      v-model="copyChainDialogVisible"
      title="从产品复制工序链"
      width="420px"
      :close-on-click-modal="false"
    >
      <el-form label-width="90px">
        <el-form-item label="目标产品">
          <el-tag>{{ selectedProductName }}</el-tag>
        </el-form-item>
        <el-form-item label="复制来源">
          <el-select
            v-model="copyChainSourceId"
            placeholder="选择源产品"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="p in copySourceProducts"
              :key="p.id"
              :label="p.name"
              :value="p.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        style="margin-top: 4px"
        title="目标产品工序链须为空才可复制。若已有工序，后端将提示请先清空目标工序链。"
      />
      <template #footer>
        <el-button @click="copyChainDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!copyChainSourceId"
          :loading="copyChainLoading"
          @click="confirmCopyChain"
        >确认复制</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-container { padding: 20px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; }
.toolbar-left { display: flex; align-items: center; gap: 12px; }
.toolbar-right { display: flex; align-items: center; gap: 8px; }
.workflow-section { margin-top: 12px; }
.legacy-compatibility { margin-top: 12px; border: 1px solid #edf2f7; border-radius: 10px; background: #fff; }
.legacy-compatibility :deep(.el-collapse-item__header) { padding: 0 16px; border-radius: 10px; }
.legacy-compatibility :deep(.el-collapse-item__content) { padding: 0 16px 16px; }
.legacy-title { display: flex; align-items: center; gap: 8px; color: #475467; font-weight: 600; }

/* C4 status indicators */
.dirty-indicator { font-size: 13px; color: #e6a23c; font-weight: 500; }
.clean-indicator { font-size: 13px; color: #67c23a; font-weight: 500; }

.content-layout {
  display: flex;
  gap: 16px;
  margin-top: 16px;
}
.product-panel { width: 340px; flex-shrink: 0; }
.process-panel { flex: 1; min-width: 0; }

.card-header { display: flex; justify-content: space-between; align-items: center; }
.product-info { padding: 8px 0; }

/* C2 — two-column layout inside the right card */
.panel-titles {
  display: flex;
  gap: 0;
  width: 100%;
}
.panel-title-left {
  flex: 1;
  font-weight: 600;
  font-size: 14px;
}
.panel-title-right {
  width: 320px;
  flex-shrink: 0;
  font-weight: 600;
  font-size: 14px;
  color: #606266;
  padding-left: 16px;
  border-left: 1px solid #e4e7ed;
  box-sizing: border-box;
}

.no-product-hint { padding: 20px 0; }

.two-column-layout {
  display: flex;
  gap: 0;
  min-height: 300px;
}

/* Left chain column */
.chain-column {
  flex: 1;
  min-width: 0;
  padding-right: 16px;
  overflow-y: auto;
  max-height: 70vh;
}

/* Separator */
.column-divider {
  width: 1px;
  background: #e4e7ed;
  flex-shrink: 0;
  margin: 0 4px;
}

/* Right pool column */
.pool-column {
  width: 320px;
  flex-shrink: 0;
  padding-left: 16px;
  overflow-y: auto;
  max-height: 70vh;
}

/* Pool category filter (sticky on top while scrolling) */
.pool-filter {
  position: sticky;
  top: 0;
  z-index: 1;
  background: #fff;
  padding-bottom: 8px;
  margin-bottom: 4px;
}

/* Linked item row (C1 drag-enabled) */
.linked-item {
  display: flex;
  align-items: center;
  padding: 10px 8px;
  margin-bottom: 8px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  background: #fafafa;
  transition: background 0.15s, border-color 0.15s;
  cursor: grab;
  user-select: none;
}
.linked-item:active { cursor: grabbing; }
.linked-item:hover { background: #f0f9ff; border-color: #409eff; }
.linked-item.is-draft { border-style: dashed; border-color: #e6a23c; background: #fffbe6; }
.linked-item.drag-over { border-color: #409eff; background: #ecf5ff; border-style: solid; }

/* Drag handle */
.drag-handle {
  font-size: 18px;
  color: #c0c4cc;
  cursor: grab;
  padding: 0 4px 0 0;
  flex-shrink: 0;
  line-height: 1;
}
.drag-handle:hover { color: #909399; }

.step-number {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #409eff;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 13px;
  flex-shrink: 0;
  margin-right: 10px;
}
.step-info { flex: 1; min-width: 0; }
.step-name { font-weight: 600; font-size: 14px; color: #333; display: flex; align-items: center; flex-wrap: wrap; gap: 4px; }
.step-meta { display: flex; align-items: center; gap: 8px; margin-top: 4px; flex-wrap: wrap; }
.step-unit { font-size: 12px; color: #909399; }
.step-actions { display: flex; gap: 2px; flex-shrink: 0; margin-left: 4px; }

.step-leader { margin-top: 6px; }
.step-leader-readonly { margin-top: 4px; }

/* Pool (right column) */
.available-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 4px;
  border-bottom: 1px solid #f0f0f0;
}
.available-item:last-child { border-bottom: none; }
.available-info { display: flex; align-items: center; gap: 6px; flex: 1; min-width: 0; }
.available-name { font-size: 13px; color: #333; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.available-unit { font-size: 12px; color: #909399; flex-shrink: 0; }

.empty-state { padding: 20px 0; }
.empty-hint { padding: 12px; text-align: center; }
</style>
