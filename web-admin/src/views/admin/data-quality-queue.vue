<!--
  数据质量队列管理页 — 餐饮 Phase A A-3 (Task 3.5).

  URL: /system/data-quality-queue
  Roles: factory_super_admin | platform_admin | permission_admin

  Features:
  - el-tabs per entity_type (8 types)
  - el-table with per-row 处理 + batch 批量 confirm
  - Single-resolve modal with 4-eye gate: submitter == currentUser && adminCount > 1 → disabled
  - Single-admin banner when adminCount === 1
  - Loading/error skeleton

  No polling. Admin manually refreshes.
  No `as any` — all types from @/api/admin/data-quality-queue.
-->
<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  listQueue,
  resolveQueue,
  rejectQueue,
  batchResolveQueue,
  getAdminCount,
  type QueueItem,
  type ListResponse,
} from '@/api/admin/data-quality-queue';
import { useAuthStore } from '@/store/modules/auth';

// ── Constants ──────────────────────────────────────────────────────────────

const ENTITY_TYPES: Array<{ label: string; value: string }> = [
  { label: '门店', value: 'store' },
  { label: '产品', value: 'product' },
  { label: '员工', value: 'staff' },
  { label: '食材', value: 'ingredient' },
  // P4a (Task 8): cross-store canonical dish merge review.
  { label: '菜名归一', value: 'dish' },
  { label: '形态识别', value: 'shape_detection' },
  { label: '跨表合并', value: 'sheet_merge' },
  { label: '周期推断', value: 'period_inference' },
  { label: '字段冲突', value: 'field_conflict' },
];

// P4a (Task 8) — standard reject reasons (防呆 Rule 3: dropdown, not free text).
// "其他" reveals an optional note textarea.
const DISH_REJECT_REASONS: Array<{ label: string; value: string }> = [
  { label: '主料不同，非同一道菜（如 青花椒鱼 ≠ 青花椒虾）', value: '主料不同，非同一道菜' },
  { label: '只是名字相似，实际不同菜', value: '名字相似但不是同一道菜' },
  { label: '规格/吃法不同，应分开统计', value: '规格或吃法不同，应分开统计' },
  { label: '建议名不准确，需重新归一', value: '建议的规范名不准确' },
  { label: '其他（填写说明）', value: '__other__' },
];

const STATUS_OPTIONS = [
  { label: '待处理', value: 'PENDING' },
  { label: '已确认', value: 'CONFIRMED' },
  { label: '已拒绝', value: 'REJECTED' },
  { label: '已推迟', value: 'DEFERRED' },
];

const PRIORITY_OPTIONS = [
  { label: '高', value: 'HIGH' },
  { label: '中', value: 'MEDIUM' },
  { label: '低', value: 'LOW' },
];

// ── Auth ───────────────────────────────────────────────────────────────────

const auth = useAuthStore();

// Current logged-in user's numeric ID (used for 4-eye gate comparison).
// Auth store exposes user.id (set at login from data.userId).
const currentUserId = computed<string>(() => {
  const u = auth.user;
  if (!u) return '';
  return String(u.id ?? '');
});

// ── State ──────────────────────────────────────────────────────────────────

// Filter state.
// Priority filter removed pending Python list endpoint support (Phase B).
const filterFactoryId = ref<string>('');
const filterStatus = ref<string>('PENDING');

// Active entity_type tab
const activeEntityType = ref<string>('store');

// List data
const loading = ref<boolean>(false);
const loadError = ref<string | null>(null);
const listData = ref<ListResponse>({ items: [], total: 0, page: 1, pageSize: 50 });

// Single-admin check
const adminCount = ref<number>(1);
const adminCountLoading = ref<boolean>(false);

const isSingleAdmin = computed<boolean>(() => adminCount.value === 1);

// Batch selection
const selectedRows = ref<QueueItem[]>([]);

// Resolve modal
const modalVisible = ref<boolean>(false);
const modalItem = ref<QueueItem | null>(null);
const modalAction = ref<'confirm' | 'create_new' | 'reject'>('confirm');
const modalEntityId = ref<string>('');
const modalReason = ref<string>('');
const modalSubmitting = ref<boolean>(false);

// P4a (Task 8) — dish-specific modal state.
// modalCanonicalName: the regularised display name for create_new.
// modalRejectReasonValue: the selected dropdown reason ('__other__' → free note).
// modalRejectNote: the optional free-text when reason === '__other__'.
const modalCanonicalName = ref<string>('');
const modalRejectReasonValue = ref<string>(DISH_REJECT_REASONS[0].value);
const modalRejectNote = ref<string>('');

// Active tab is the dish (菜名归一) review tab.
const isDishTab = computed<boolean>(() => activeEntityType.value === 'dish');
// The modal item is a dish proposal (drives dish-specific modal UI).
const isDishModal = computed<boolean>(
  () => modalItem.value?.entityType === 'dish'
);

// 4-eye gate: submitter == currentUserId AND adminCount > 1 → blocked
const isFourEyeBlocked = computed<boolean>(() => {
  if (!modalItem.value) return false;
  const submitter = modalItem.value.submitter ?? '';
  return submitter !== '' && submitter === currentUserId.value && adminCount.value > 1;
});

// Batch modal
const batchDialogVisible = ref<boolean>(false);
const batchSubmitting = ref<boolean>(false);
const batchResultVisible = ref<boolean>(false);
const batchSuccessCount = ref<number>(0);
const batchFailedItems = ref<Array<{ id: number; reason: string }>>([]);

// ── Helpers ────────────────────────────────────────────────────────────────

function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  try {
    return new Intl.DateTimeFormat('zh-CN', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
      hour12: false,
    }).format(new Date(iso));
  } catch {
    return iso;
  }
}

function statusTagType(status: QueueItem['status']): string {
  switch (status) {
    case 'PENDING': return 'warning';
    case 'CONFIRMED': return 'success';
    case 'REJECTED': return 'danger';
    case 'DEFERRED': return 'info';
    default: return 'info';
  }
}

function statusLabel(status: QueueItem['status']): string {
  switch (status) {
    case 'PENDING': return '待处理';
    case 'CONFIRMED': return '已确认';
    case 'REJECTED': return '已拒绝';
    case 'DEFERRED': return '已推迟';
    default: return status;
  }
}

function priorityTagType(priority: string): string {
  switch (priority) {
    case 'HIGH': return 'danger';
    case 'MEDIUM': return 'warning';
    case 'LOW': return 'info';
    default: return 'info';
  }
}

function confidencePercent(c: number | null): string {
  if (c == null) return '—';
  return `${Math.round(c * 100)}%`;
}

// P4a (Task 8): compact 元 / 件 formatting for the dish sales sample.
function formatRevenue(v: number | null | undefined): string {
  if (v == null) return '¥0';
  if (v >= 10000) return `¥${(v / 10000).toFixed(1)}万`;
  return `¥${Math.round(v)}`;
}

function formatQty(v: number | null | undefined): string {
  if (v == null) return '0';
  return Number.isInteger(v) ? String(v) : v.toFixed(1);
}

// ── Data loading ───────────────────────────────────────────────────────────

async function loadList(): Promise<void> {
  if (!filterFactoryId.value.trim()) {
    listData.value = { items: [], total: 0, page: 1, pageSize: 50 };
    return;
  }

  loading.value = true;
  loadError.value = null;
  try {
    const resp = await listQueue({
      factoryId: filterFactoryId.value.trim(),
      entityType: activeEntityType.value || undefined,
      status: filterStatus.value || undefined,
      page: 1,
      pageSize: 50,
    });
    listData.value = resp;
    selectedRows.value = [];
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : String(err);
  } finally {
    loading.value = false;
  }
}

async function loadAdminCount(): Promise<void> {
  const fid = filterFactoryId.value.trim();
  if (!fid) {
    adminCount.value = 1;
    return;
  }
  adminCountLoading.value = true;
  try {
    adminCount.value = await getAdminCount(fid);
  } catch {
    // Non-fatal — default to 1 (single admin mode, conservative)
    adminCount.value = 1;
  } finally {
    adminCountLoading.value = false;
  }
}

// ── Tab change ─────────────────────────────────────────────────────────────

function onTabChange(tabName: string): void {
  activeEntityType.value = tabName;
  loadList();
}

// ── Watchers ───────────────────────────────────────────────────────────────

watch(
  () => filterFactoryId.value,
  (newVal) => {
    if (newVal.trim()) {
      loadAdminCount();
      loadList();
    } else {
      adminCount.value = 1;
      listData.value = { items: [], total: 0, page: 1, pageSize: 50 };
    }
  }
);

// ── Table selection ────────────────────────────────────────────────────────

function onSelectionChange(rows: QueueItem[]): void {
  selectedRows.value = rows;
}

// ── Single resolve modal ───────────────────────────────────────────────────

function openModal(item: QueueItem): void {
  modalItem.value = item;
  modalEntityId.value = item.candidateEntityId != null ? String(item.candidateEntityId) : '';
  modalReason.value = '';
  // P4a (Task 8): dish defaults — propose create_new (most common: 8 stores'
  // variants → one new canonical), pre-fill the suggested canonical name from
  // rawName (the proposal's shortest member name). Human edits / switches action.
  if (item.entityType === 'dish') {
    modalAction.value = 'create_new';
    modalCanonicalName.value = item.rawName || '';
    modalRejectReasonValue.value = DISH_REJECT_REASONS[0].value;
    modalRejectNote.value = '';
  } else {
    modalAction.value = 'confirm';
  }
  modalVisible.value = true;
}

function closeModal(): void {
  modalVisible.value = false;
  modalItem.value = null;
}

// P4a (Task 8): dish submit — uses the regularised canonical name (create_new)
// or an existing canonical_dish_id (confirm), and a dropdown reject reason.
async function submitDishModal(): Promise<void> {
  if (!modalItem.value) return;
  const id = modalItem.value.id;

  if (modalAction.value === 'reject') {
    const sel = modalRejectReasonValue.value;
    const reason =
      sel === '__other__' ? modalRejectNote.value.trim() : sel;
    if (!reason) {
      ElMessage.warning('请选择或填写拒绝原因');
      return;
    }
    modalSubmitting.value = true;
    try {
      await rejectQueue(id, reason);
      ElMessage.success('已拒绝该归一提议');
      closeModal();
      await loadList();
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      ElMessage.error(`拒绝失败: ${msg}`);
    } finally {
      modalSubmitting.value = false;
    }
    return;
  }

  if (modalAction.value === 'create_new') {
    const name = modalCanonicalName.value.trim();
    if (!name) {
      ElMessage.warning('请填写规范菜名');
      return;
    }
    modalSubmitting.value = true;
    try {
      const resp = await resolveQueue(id, {
        action: 'create_new',
        canonicalName: name,
      });
      ElMessage.success(
        resp.singleAdminDegraded ? '已新建归一（单管理员模式）' : '已新建归一菜品'
      );
      closeModal();
      await loadList();
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      ElMessage.error(`处理失败: ${msg}`);
    } finally {
      modalSubmitting.value = false;
    }
    return;
  }

  // confirm: attach members to an EXISTING canonical_dish_id.
  const canonicalIdNum = modalEntityId.value.trim()
    ? parseInt(modalEntityId.value.trim(), 10)
    : undefined;
  if (canonicalIdNum == null || Number.isNaN(canonicalIdNum)) {
    ElMessage.warning('确认合并到既有菜品需填写 canonical_dish_id');
    return;
  }
  modalSubmitting.value = true;
  try {
    const resp = await resolveQueue(id, {
      action: 'confirm',
      resolvedToEntityId: canonicalIdNum,
    });
    ElMessage.success(
      resp.singleAdminDegraded ? '已合并（单管理员模式）' : '已合并到既有菜品'
    );
    closeModal();
    await loadList();
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    ElMessage.error(`处理失败: ${msg}`);
  } finally {
    modalSubmitting.value = false;
  }
}

async function submitModal(): Promise<void> {
  if (!modalItem.value) return;
  if (isFourEyeBlocked.value) return;

  // P4a (Task 8): dish proposals use the dish-specific submit path.
  if (isDishModal.value) {
    await submitDishModal();
    return;
  }

  const id = modalItem.value.id;

  if (modalAction.value === 'reject') {
    if (!modalReason.value.trim()) {
      ElMessage.warning('请填写拒绝原因');
      return;
    }
    modalSubmitting.value = true;
    try {
      await rejectQueue(id, modalReason.value.trim());
      ElMessage.success('已拒绝');
      closeModal();
      await loadList();
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      ElMessage.error(`拒绝失败: ${msg}`);
    } finally {
      modalSubmitting.value = false;
    }
    return;
  }

  // confirm or create_new
  const entityIdNum = modalEntityId.value.trim()
    ? parseInt(modalEntityId.value.trim(), 10)
    : undefined;

  if (modalAction.value === 'confirm' && entityIdNum == null) {
    ElMessage.warning('请填写解析到的实体 ID');
    return;
  }

  modalSubmitting.value = true;
  try {
    const resp = await resolveQueue(id, {
      action: modalAction.value,
      resolvedToEntityId: entityIdNum,
    });
    if (resp.singleAdminDegraded) {
      ElMessage.success('已确认（单管理员降级模式）');
    } else {
      ElMessage.success('已确认');
    }
    closeModal();
    await loadList();
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    ElMessage.error(`处理失败: ${msg}`);
  } finally {
    modalSubmitting.value = false;
  }
}

// ── Batch resolve ──────────────────────────────────────────────────────────

function openBatchDialog(): void {
  if (selectedRows.value.length === 0) return;
  batchDialogVisible.value = true;
}

async function submitBatch(): Promise<void> {
  if (selectedRows.value.length === 0) return;

  const ids = selectedRows.value.map((r) => r.id);
  batchSubmitting.value = true;
  try {
    const resp = await batchResolveQueue({ ids, action: 'confirm' });
    batchDialogVisible.value = false;
    batchSuccessCount.value = resp.successCount;
    batchFailedItems.value = resp.failedItems;
    batchResultVisible.value = true;
    await loadList();
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    ElMessage.error(`批量处理失败: ${msg}`);
  } finally {
    batchSubmitting.value = false;
  }
}

// ── Lifecycle ──────────────────────────────────────────────────────────────

onMounted(() => {
  // Pre-fill factoryId from auth store (factory users see their own factory)
  if (auth.factoryId) {
    filterFactoryId.value = auth.factoryId;
  }
});
</script>

<template>
  <div class="data-quality-queue-page">

    <!-- Single-admin banner -->
    <el-alert
      v-if="filterFactoryId && isSingleAdmin && !adminCountLoading"
      type="warning"
      show-icon
      :closable="false"
      style="margin-bottom: 12px;"
    >
      该工厂仅 1 名管理员，4-eye 原则降级为单管理员模式（自审核）
    </el-alert>

    <!-- Filter row card -->
    <el-card shadow="never" class="filter-card" style="margin-bottom: 12px;">
      <div class="filter-row">
        <el-form-item label="工厂 ID" style="margin-bottom: 0; margin-right: 16px;">
          <el-input
            v-model="filterFactoryId"
            placeholder="输入工厂 ID（必填）"
            clearable
            style="width: 200px;"
          />
        </el-form-item>

        <el-form-item label="状态" style="margin-bottom: 0; margin-right: 16px;">
          <el-select v-model="filterStatus" placeholder="选择状态" clearable style="width: 140px;">
            <el-option
              v-for="opt in STATUS_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>

        <!-- Priority filter omitted: Python list endpoint does not yet
             accept a priority query param (Phase B). -->

        <el-button type="primary" @click="loadList">查询</el-button>

        <!-- Batch button — disabled on the dish tab: canonical merges are
             one-at-a-time human decisions (per fool-proof Rule 2/3). -->
        <el-button
          v-if="!isDishTab"
          type="warning"
          :disabled="selectedRows.length === 0"
          style="margin-left: 16px;"
          @click="openBatchDialog"
        >
          批量 confirm ({{ selectedRows.length }})
        </el-button>
        <el-text
          v-else
          type="info"
          size="small"
          style="margin-left: 16px;"
        >
          菜名归一需逐条人工确认（不支持批量）
        </el-text>
      </div>

      <!-- Require factoryId banner -->
      <el-alert
        v-if="!filterFactoryId.trim()"
        type="info"
        :closable="false"
        title="请输入工厂 ID 查询队列"
        style="margin-top: 12px;"
      />
    </el-card>

    <!-- Main content card -->
    <el-card shadow="never" class="main-card">

      <!-- Entity type tabs -->
      <el-tabs
        v-model="activeEntityType"
        class="entity-tabs"
        @tab-change="onTabChange"
      >
        <el-tab-pane
          v-for="et in ENTITY_TYPES"
          :key="et.value"
          :label="et.label"
          :name="et.value"
        />
      </el-tabs>

      <!-- Loading skeleton (first load) -->
      <el-skeleton v-if="loading && listData.items.length === 0" :rows="6" animated />

      <!-- Error state -->
      <el-alert
        v-else-if="loadError"
        type="error"
        :title="`加载失败: ${loadError}`"
        show-icon
        :closable="false"
      />

      <!-- Empty: factoryId not entered -->
      <el-empty
        v-else-if="!filterFactoryId.trim()"
        description="请输入工厂 ID 查询队列"
      />

      <!-- Empty: no dish items — next-action guidance (fool-proof Rule 5),
           not a dead-end. -->
      <el-empty
        v-else-if="!loading && isDishTab && listData.items.length === 0"
        description="暂无待归一菜品"
      >
        <div class="empty-dish-hint">
          <p>跨店同名菜归一候选已全部处理，或尚未生成。</p>
          <p>可在服务器重跑候选生成（离线，仅 PROPOSE，绝不自动合并）：</p>
          <code class="empty-dish-cmd">
            python -m smartbi.scripts.generate_dish_canonical_candidates --factory {{ filterFactoryId || '&lt;工厂ID&gt;' }} --apply
          </code>
        </div>
      </el-empty>

      <!-- Empty: no items (non-dish) -->
      <el-empty
        v-else-if="!loading && listData.items.length === 0"
        description="暂无队列数据"
      />

      <!-- Main table -->
      <el-table
        v-else
        :data="listData.items"
        stripe
        size="default"
        v-loading="loading"
        @selection-change="onSelectionChange"
        class="queue-table"
      >
        <!-- Checkbox — hidden on dish tab (no batch) -->
        <el-table-column v-if="!isDishTab" type="selection" width="50" />

        <!-- ID -->
        <el-table-column label="ID" prop="id" width="80" />

        <!-- rawName / 建议规范名 (dish) -->
        <el-table-column
          :label="isDishTab ? '建议规范名' : '原始名称'"
          prop="rawName"
          min-width="160"
        >
          <template #default="{ row }">
            <span class="raw-name">{{ row.rawName || '—' }}</span>
          </template>
        </el-table-column>

        <!-- P4a: dish member dishes + which stores (fool-proof Rule 2) -->
        <el-table-column v-if="isDishTab" label="成员菜名（哪些门店有）" min-width="320">
          <template #default="{ row }">
            <div v-if="row.dishReview && row.dishReview.members.length" class="dish-members">
              <div
                v-for="m in row.dishReview.members"
                :key="m.productId"
                class="dish-member-row"
              >
                <span class="dish-member-name">{{ m.name }}</span>
                <span class="dish-member-stores">
                  <el-tag
                    v-for="st in m.stores"
                    :key="st"
                    size="small"
                    type="info"
                    class="dish-store-tag"
                  >{{ st }}</el-tag>
                  <span v-if="!m.stores.length" class="dish-no-store">（无门店销售记录）</span>
                </span>
                <span class="dish-member-sales">
                  销 {{ formatQty(m.qty) }} · {{ formatRevenue(m.revenue) }}
                </span>
              </div>
            </div>
            <span v-else class="dish-no-member">—</span>
          </template>
        </el-table-column>

        <!-- candidateEntityId (non-dish) -->
        <el-table-column
          v-if="!isDishTab"
          label="候选实体 ID"
          prop="candidateEntityId"
          width="110"
        >
          <template #default="{ row }">
            {{ row.candidateEntityId ?? '—' }}
          </template>
        </el-table-column>

        <!-- confidence -->
        <el-table-column label="置信度" width="90">
          <template #default="{ row }">
            {{ confidencePercent(row.confidence) }}
          </template>
        </el-table-column>

        <!-- submitter -->
        <el-table-column label="提交者" prop="submitter" width="120">
          <template #default="{ row }">
            {{ row.submitter || '—' }}
          </template>
        </el-table-column>

        <!-- status -->
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>

        <!-- priority -->
        <el-table-column label="优先级" width="90">
          <template #default="{ row }">
            <el-tag :type="priorityTagType(row.priority)" size="small">
              {{ row.priority || '—' }}
            </el-tag>
          </template>
        </el-table-column>

        <!-- createdAt -->
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">
            {{ formatDateTime(row.createdAt) }}
          </template>
        </el-table-column>

        <!-- Actions -->
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              type="primary"
              @click="openModal(row)"
            >
              处理
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- Total count -->
      <div v-if="listData.total > 0" class="total-row">
        共 {{ listData.total }} 条
      </div>
    </el-card>

    <!-- ── Single-resolve modal ─────────────────────────────────────────── -->
    <el-dialog
      v-model="modalVisible"
      :title="isDishModal ? '菜名归一 — 人工确认' : '处理队列项'"
      width="640px"
      :close-on-click-modal="false"
    >
      <!-- ===================== DISH (菜名归一) modal ===================== -->
      <template v-if="modalItem && isDishModal">
        <!-- Proposal summary -->
        <el-descriptions :column="2" border size="small" style="margin-bottom: 12px;">
          <el-descriptions-item label="建议规范名">{{ modalItem.rawName }}</el-descriptions-item>
          <el-descriptions-item label="归一类型">{{ modalItem.dishReview?.proposalKind || '—' }}</el-descriptions-item>
          <el-descriptions-item label="品类">{{ modalItem.dishReview?.category || '—' }}</el-descriptions-item>
          <el-descriptions-item label="成员数">{{ modalItem.dishReview?.memberCount ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="AI 置信度">{{ confidencePercent(modalItem.confidence) }}</el-descriptions-item>
          <el-descriptions-item label="归一 key">{{ modalItem.dishReview?.normalizedKey || '—' }}</el-descriptions-item>
        </el-descriptions>

        <!-- Caution: literal similarity ≠ same dish (fool-proof Rule 2 context) -->
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="确认前请核对：下列成员菜名是否真的是同一道菜？"
          description="字面相似不等于同菜（如 青花椒鱼 ≠ 青花椒虾）。确认后将合并销量/营收/评价统计。"
          style="margin-bottom: 12px;"
        />

        <!-- Member dishes + stores + sales (Rule 2: eyeball before confirm) -->
        <div class="dish-modal-members">
          <div
            v-for="m in (modalItem.dishReview?.members || [])"
            :key="m.productId"
            class="dish-modal-member"
          >
            <div class="dish-modal-member-name">{{ m.name }}</div>
            <div class="dish-modal-member-meta">
              <span class="dish-modal-stores">
                门店：
                <el-tag
                  v-for="st in m.stores"
                  :key="st"
                  size="small"
                  type="info"
                  class="dish-store-tag"
                >{{ st }}</el-tag>
                <span v-if="!m.stores.length" class="dish-no-store">无销售记录</span>
              </span>
              <span class="dish-modal-sales">销 {{ formatQty(m.qty) }} 件 · {{ formatRevenue(m.revenue) }}</span>
            </div>
          </div>
          <div v-if="!(modalItem.dishReview?.members || []).length" class="dish-no-member">
            该提议无成员菜品。
          </div>
        </div>

        <!-- Action dropdown (Rule 3: dropdown, not free text) -->
        <el-form-item label="处理动作" style="margin-top: 8px;">
          <el-select v-model="modalAction" style="width: 240px;">
            <el-option label="新建归一菜品（create_new）" value="create_new" />
            <el-option label="合并到既有菜品（confirm）" value="confirm" />
            <el-option label="拒绝该归一（reject）" value="reject" />
          </el-select>
        </el-form-item>

        <!-- create_new: regularised canonical name -->
        <el-form-item v-if="modalAction === 'create_new'" label="规范菜名（必填）">
          <el-input
            v-model="modalCanonicalName"
            placeholder="确认后的统一展示名，如「招牌青花椒鱼」"
            style="width: 360px;"
          />
        </el-form-item>

        <!-- confirm: attach to existing canonical_dish_id -->
        <el-form-item v-if="modalAction === 'confirm'" label="既有菜品 ID（必填）">
          <el-input
            v-model="modalEntityId"
            placeholder="要合并到的 canonical_dish_id"
            style="width: 240px;"
          />
        </el-form-item>

        <!-- reject: reason dropdown + optional note (Rule 3) -->
        <template v-if="modalAction === 'reject'">
          <el-form-item label="拒绝原因（必选）">
            <el-select v-model="modalRejectReasonValue" style="width: 420px;">
              <el-option
                v-for="opt in DISH_REJECT_REASONS"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item v-if="modalRejectReasonValue === '__other__'" label="补充说明（必填）">
            <el-input
              v-model="modalRejectNote"
              type="textarea"
              :rows="2"
              placeholder="请说明拒绝原因"
            />
          </el-form-item>
        </template>
      </template>

      <!-- ===================== GENERIC modal ===================== -->
      <template v-else-if="modalItem">
        <!-- Item summary -->
        <el-descriptions :column="1" border size="small" style="margin-bottom: 16px;">
          <el-descriptions-item label="原始名称">{{ modalItem.rawName }}</el-descriptions-item>
          <el-descriptions-item label="候选实体 ID">{{ modalItem.candidateEntityId ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="提交者">{{ modalItem.submitter || '—' }}</el-descriptions-item>
          <el-descriptions-item label="置信度">{{ confidencePercent(modalItem.confidence) }}</el-descriptions-item>
        </el-descriptions>

        <!-- 4-eye gate banner -->
        <el-alert
          v-if="isFourEyeBlocked"
          type="error"
          :closable="false"
          show-icon
          title="您是该项的提交者，无法处理 (4-eye 原则)"
          description="请联系其他管理员处理此条目"
          style="margin-bottom: 16px;"
        />

        <!-- Action radio -->
        <div style="margin-bottom: 16px;">
          <el-radio-group v-model="modalAction" :disabled="isFourEyeBlocked">
            <el-radio value="confirm">确认映射</el-radio>
            <el-radio value="create_new">创建新实体</el-radio>
            <el-radio value="reject">拒绝</el-radio>
          </el-radio-group>
        </div>

        <!-- confirm / create_new: entity ID input -->
        <div v-if="modalAction === 'confirm' || modalAction === 'create_new'">
          <el-form-item label="解析到实体 ID">
            <el-input
              v-model="modalEntityId"
              placeholder="输入数字 ID"
              :disabled="isFourEyeBlocked"
              style="width: 200px;"
            />
          </el-form-item>
        </div>

        <!-- reject: reason textarea -->
        <div v-if="modalAction === 'reject'">
          <el-form-item label="拒绝原因（必填）">
            <el-input
              v-model="modalReason"
              type="textarea"
              :rows="3"
              placeholder="请说明拒绝原因"
              :disabled="isFourEyeBlocked"
            />
          </el-form-item>
        </div>
      </template>

      <template #footer>
        <el-button @click="closeModal">取消</el-button>
        <el-button
          type="primary"
          :loading="modalSubmitting"
          :disabled="isFourEyeBlocked"
          @click="submitModal"
        >
          确认
        </el-button>
      </template>
    </el-dialog>

    <!-- ── Batch confirm dialog ──────────────────────────────────────────── -->
    <el-dialog
      v-model="batchDialogVisible"
      title="批量 confirm"
      width="400px"
    >
      <p>确认批量 confirm {{ selectedRows.length }} 条？</p>

      <template #footer>
        <el-button @click="batchDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="batchSubmitting"
          @click="submitBatch"
        >
          确认
        </el-button>
      </template>
    </el-dialog>

    <!-- ── Batch result dialog ───────────────────────────────────────────── -->
    <el-dialog
      v-model="batchResultVisible"
      title="批量处理结果"
      width="480px"
    >
      <p>成功: {{ batchSuccessCount }} 条</p>
      <div v-if="batchFailedItems.length > 0">
        <p style="color: #f56c6c;">失败 {{ batchFailedItems.length }} 条：</p>
        <ul>
          <li v-for="f in batchFailedItems" :key="f.id">
            ID {{ f.id }}: {{ f.reason }}
          </li>
        </ul>
      </div>

      <template #footer>
        <el-button type="primary" @click="batchResultVisible = false">关闭</el-button>
      </template>
    </el-dialog>

  </div>
</template>

<style scoped>
.data-quality-queue-page {
  padding: 16px;
}

.filter-card .filter-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.entity-tabs {
  margin-bottom: 12px;
}

.queue-table {
  width: 100%;
}

.raw-name {
  font-weight: 500;
}

.total-row {
  margin-top: 8px;
  font-size: 13px;
  color: #909399;
  text-align: right;
}

/* P4a (Task 8) — dish member display */
.dish-members {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.dish-member-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.dish-member-name {
  font-weight: 500;
  color: #303133;
}

.dish-store-tag {
  margin-right: 4px;
}

.dish-member-sales {
  color: #909399;
  font-size: 12px;
}

.dish-no-store,
.dish-no-member {
  color: #c0c4cc;
  font-size: 12px;
}

.empty-dish-hint {
  text-align: left;
  max-width: 560px;
  margin: 0 auto;
  color: #606266;
  font-size: 13px;
}

.empty-dish-cmd {
  display: block;
  margin-top: 8px;
  padding: 8px 10px;
  background: #f5f7fa;
  border-radius: 4px;
  font-family: monospace;
  font-size: 12px;
  word-break: break-all;
  color: #303133;
}

/* dish modal member list */
.dish-modal-members {
  max-height: 240px;
  overflow-y: auto;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 8px;
  margin-bottom: 12px;
}

.dish-modal-member {
  padding: 6px 4px;
  border-bottom: 1px dashed #ebeef5;
}

.dish-modal-member:last-child {
  border-bottom: none;
}

.dish-modal-member-name {
  font-weight: 600;
  color: #303133;
  font-size: 13px;
}

.dish-modal-member-meta {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
}

.dish-modal-sales {
  color: #909399;
}
</style>
