<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Delete, Rank, Refresh } from '@element-plus/icons-vue';
import { handleCatchError } from '@/utils/errorToast';
import {
  getActiveWorkProcesses,
  getProductWorkProcesses,
  createProductWorkProcess,
  deleteProductWorkProcess,
  batchSortProductWorkProcesses,
  updateProductWorkProcess,
  getProductWorkProcessRecommendation,
  type WorkProcessItem,
  type ProductWorkProcessItem,
  type RecommendedWorkProcess
} from '@/api/processProduction';
import { getOperatorUsers } from '@/api/factory';
import WorkProcessAIChatPanel from '@/views/system/components/WorkProcessAIChatPanel.vue';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const route = useRoute();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('system'));

// 产品列表（从后端获取）
const products = ref<Array<{ id: string; name: string }>>([]);
const selectedProductId = ref('');
const productsLoading = ref(false);

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

// Pending ops to flush on save
interface PendingAdd { type: 'add'; wp: WorkProcessItem }
interface PendingRemove { type: 'remove'; serverId: number }
interface PendingSort { type: 'sort' }
interface PendingResponsible { type: 'responsible'; serverId: number; workerId: number | null; productTypeId: string; workProcessId: string }
type PendingOp = PendingAdd | PendingRemove | PendingSort | PendingResponsible;
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
    const needsSort = pendingOps.value.some(o =>
      o.type === 'sort' || o.type === 'add' || o.type === 'remove'
    );

    // 1. Removals (server items only)
    for (const op of toRemove) {
      await deleteProductWorkProcess(factoryId.value, op.serverId);
    }

    // 2. Additions (include responsibleWorkerId if user set it on draft item)
    for (const op of toAdd) {
      const draftItem = draftLinked.value.find(d => d.workProcessId === op.wp.id && d.isPending);
      const responsibleWorkerId = draftItem?.responsibleWorkerId ?? undefined;
      await createProductWorkProcess(factoryId.value, {
        productTypeId: selectedProductId.value,
        workProcessId: op.wp.id,
        processOrder: 999, // corrected by sort step below
        ...(responsibleWorkerId != null ? { responsibleWorkerId } : {}),
      });
    }

    // 3. Re-fetch to get fresh server IDs after add/remove
    const freshRes = await getProductWorkProcesses(factoryId.value, selectedProductId.value);
    let freshItems: ProductWorkProcessItem[] = [];
    if (freshRes.success && freshRes.data) {
      freshItems = Array.isArray(freshRes.data) ? freshRes.data : [];
    }

    // 4. Responsible worker changes (use fresh server IDs)
    const freshByWpId = new Map(freshItems.map(i => [i.workProcessId, i]));
    for (const op of toResponsible) {
      const freshItem = freshByWpId.get(op.workProcessId);
      if (!freshItem) continue;
      const workerId = op.workerId == null ? -1 : op.workerId;
      await updateProductWorkProcess(factoryId.value, freshItem.id, {
        productTypeId: freshItem.productTypeId,
        workProcessId: freshItem.workProcessId,
        responsibleWorkerId: workerId,
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
 * 返回工序行的责任小组长显示名称（只读模式）。
 */
function responsibleLabel(item: ProductWorkProcessItem): string {
  if (!item.responsibleWorkerId) return '';
  const op = operators.value.find(o => o.id === item.responsibleWorkerId);
  return op ? operatorDisplayName(op) : `#${item.responsibleWorkerId}`;
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
      handleResponsibleWorkerChange(draftItem, step.responsibleWorkerId);
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

/** Responsible worker change → draft only (C4) */
function handleResponsibleWorkerChange(item: DraftItem, value: number | null | undefined) {
  const workerId = value == null ? null : value;
  const idx = draftLinked.value.findIndex(d => d.draftKey === item.draftKey);
  if (idx !== -1) {
    draftLinked.value[idx] = { ...draftLinked.value[idx], responsibleWorkerId: workerId };
  }
  if (!item.isPending) {
    // Server item — dedupe and queue responsible op
    pendingOps.value = pendingOps.value.filter(
      o => !(o.type === 'responsible' && o.serverId === item.id)
    );
    markDirty({
      type: 'responsible',
      serverId: item.id,
      workerId,
      productTypeId: item.productTypeId,
      workProcessId: item.workProcessId,
    });
  } else {
    // Draft-only item — responsible is tracked in local state; will be set on POST in save
    if (!dirty.value) dirty.value = true;
  }
}

// ─────────────────────────────────────────────
// Move up/down (fallback buttons, C1)
// ─────────────────────────────────────────────

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
</script>

<template>
  <div class="page-container">
    <el-card>
      <div class="toolbar">
        <div class="toolbar-left">
          <h2 style="margin: 0">产品-工序配置</h2>
          <el-tag type="info">{{ factoryId }}</el-tag>
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
              保存
            </el-button>
          </template>
          <el-button :icon="Refresh" @click="handleRefresh" />
        </div>
      </div>
    </el-card>

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

        <WorkProcessAIChatPanel
          v-if="factoryId && canWrite"
          :factory-id="factoryId"
          :product-type-id="selectedProductId"
          :endpoint="`/${factoryId}/config/v2/ai/chat`"
          module-code="product_work_process_config"
          :disabled="!selectedProductId"
          @apply-draft="handleApplyAIDraft"
        />
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
                </div>

                <!-- C3 — 责任人下拉加搜索 (filterable) -->
                <div class="step-leader" v-if="canWrite">
                  <el-select
                    :model-value="item.responsibleWorkerId ?? undefined"
                    placeholder="默认责任小组长"
                    clearable
                    filterable
                    :filter-method="(q: string) => { operatorFilterQuery[item.draftKey] = q }"
                    size="small"
                    style="width: 170px"
                    @change="(val: number | undefined) => handleResponsibleWorkerChange(item, val)"
                  >
                    <el-option
                      v-for="op in filteredOperators(item.draftKey)"
                      :key="op.id"
                      :label="operatorDisplayName(op)"
                      :value="op.id"
                    />
                  </el-select>
                </div>
                <div class="step-leader-readonly" v-else-if="item.responsibleWorkerId">
                  <el-text type="info" size="small">
                    责任小组长: {{ responsibleLabel(item) }}
                  </el-text>
                </div>
              </div>

              <div class="step-actions" v-if="canWrite">
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
  </div>
</template>

<style scoped>
.page-container { padding: 20px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; }
.toolbar-left { display: flex; align-items: center; gap: 12px; }
.toolbar-right { display: flex; align-items: center; gap: 8px; }

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
