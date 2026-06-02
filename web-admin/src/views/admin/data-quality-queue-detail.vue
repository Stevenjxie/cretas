<!--
  数据质量队列详情页 — 餐饮 Phase A A-3 (Task 3.6).

  URL: /system/data-quality-queue/:id
  Roles: factory_super_admin | platform_admin | permission_admin
  Hidden: true (reached from list page via router-link or browser back)

  Shows:
  - el-page-header with breadcrumb back to /system/data-quality-queue
  - el-card: current item summary (first row = most recent)
  - el-table: full history for same (factoryId, entityType, rawName)

  No `as any` — all types from @/api/admin/data-quality-queue.
-->
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import {
  fetchQueueHistory,
  type HistoryItem,
} from '@/api/admin/data-quality-queue';

// ── Router ─────────────────────────────────────────────────────────────────

const route = useRoute();
const router = useRouter();

const itemId = computed<number>(() => {
  const raw = route.params.id;
  const n = parseInt(Array.isArray(raw) ? raw[0] : raw, 10);
  return isNaN(n) ? 0 : n;
});

function goBack(): void {
  router.push('/system/data-quality-queue');
}

// ── State ──────────────────────────────────────────────────────────────────

const loading = ref<boolean>(false);
const loadError = ref<string | null>(null);
const historyItems = ref<HistoryItem[]>([]);

// Match the URL ID exactly. The "DESC-ordered list" assumption fails when
// multiple rows share the same created_at (PG returns them in undefined order)
// — found in real-window verify where /detail/117 was showing row 115's data
// because items[0] happened to be the lowest-id row, not the URL's id.
const currentItem = computed<HistoryItem | null>(
  () =>
    historyItems.value.find((it) => it.id === itemId.value) ??
    historyItems.value[0] ??
    null
);

// ── Data loading ───────────────────────────────────────────────────────────

async function load(): Promise<void> {
  if (!itemId.value) {
    loadError.value = '无效的队列项 ID';
    return;
  }
  loading.value = true;
  loadError.value = null;
  try {
    const resp = await fetchQueueHistory(itemId.value);
    historyItems.value = resp.items;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    loadError.value = msg;
    ElMessage.error(`加载历史失败: ${msg}`);
  } finally {
    loading.value = false;
  }
}

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

function statusTagType(status: string): 'warning' | 'success' | 'danger' | 'info' {
  switch (status) {
    case 'PENDING': return 'warning';
    case 'CONFIRMED': return 'success';
    case 'REJECTED': return 'danger';
    case 'DEFERRED': return 'info';
    default: return 'info';
  }
}

function statusLabel(status: string): string {
  switch (status) {
    case 'PENDING': return '待处理';
    case 'CONFIRMED': return '已确认';
    case 'REJECTED': return '已拒绝';
    case 'DEFERRED': return '已推迟';
    default: return status;
  }
}

function priorityTagType(priority: string): 'danger' | 'warning' | 'info' {
  switch (priority) {
    case 'HIGH': return 'danger';
    case 'MEDIUM': return 'warning';
    default: return 'info';
  }
}

function confidencePercent(c: number | null): string {
  if (c == null) return '—';
  return `${Math.round(c * 100)}%`;
}

// P4a (Task 8): for a dish (菜名归一) item, surface the proposed member dish
// names from the row's extra JSONB (member_names) so the detail view shows what
// would be merged. The history endpoint returns extra as-is; no extra API call.
const isDishItem = computed<boolean>(
  () => currentItem.value?.entityType === 'dish'
);

const dishMemberNames = computed<string[]>(() => {
  const extra = currentItem.value?.extra;
  if (!extra || typeof extra !== 'object') return [];
  const raw = (extra as Record<string, unknown>).member_names;
  if (!Array.isArray(raw)) return [];
  return raw.map((x) => String(x));
});

const dishProposalKind = computed<string>(() => {
  const extra = currentItem.value?.extra;
  if (!extra || typeof extra !== 'object') return '';
  const raw = (extra as Record<string, unknown>).proposal_kind;
  return raw == null ? '' : String(raw);
});

// ── Lifecycle ──────────────────────────────────────────────────────────────

onMounted(load);
</script>

<template>
  <div class="dq-detail-page">

    <!-- Page header / breadcrumb -->
    <el-page-header @back="goBack" style="margin-bottom: 16px;">
      <template #content>
        <span>数据质量队列详情</span>
        <el-text type="info" size="small" style="margin-left: 8px;">
          (ID: {{ itemId }})
        </el-text>
      </template>
      <template #breadcrumb>
        <el-breadcrumb separator="/">
          <el-breadcrumb-item :to="{ path: '/system/data-quality-queue' }">
            数据质量队列
          </el-breadcrumb-item>
          <el-breadcrumb-item>详情</el-breadcrumb-item>
        </el-breadcrumb>
      </template>
    </el-page-header>

    <!-- Loading skeleton -->
    <el-skeleton v-if="loading" :rows="8" animated />

    <!-- Error state -->
    <el-alert
      v-else-if="loadError"
      type="error"
      :title="`加载失败: ${loadError}`"
      show-icon
      :closable="false"
    />

    <!-- Empty -->
    <el-empty
      v-else-if="historyItems.length === 0"
      description="未找到该队列项的历史记录"
    />

    <template v-else>

      <!-- Current item card -->
      <el-card shadow="never" style="margin-bottom: 16px;">
        <template #header>
          <div class="card-header">
            <span>当前状态</span>
            <el-tag
              v-if="currentItem"
              :type="statusTagType(currentItem.status)"
              size="small"
            >
              {{ statusLabel(currentItem.status) }}
            </el-tag>
          </div>
        </template>

        <el-descriptions
          v-if="currentItem"
          :column="2"
          border
          size="small"
        >
          <el-descriptions-item label="原始名称">
            {{ currentItem.rawName || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="实体类型">
            {{ currentItem.entityType || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="优先级">
            <el-tag :type="priorityTagType(currentItem.priority)" size="small">
              {{ currentItem.priority || '—' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="置信度">
            {{ confidencePercent(currentItem.confidence) }}
          </el-descriptions-item>
          <el-descriptions-item label="候选实体 ID">
            {{ currentItem.candidateEntityId ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="已解析至实体 ID">
            {{ currentItem.resolvedToEntityId ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="AI 决策">
            {{ currentItem.decidedByAgent || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="处理动作">
            {{ currentItem.adminAction || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="处理人">
            {{ currentItem.adminUser || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="处理时间">
            {{ formatDateTime(currentItem.adminAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ formatDateTime(currentItem.createdAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="推理说明" :span="2">
            <span class="reasoning-text">{{ currentItem.reasoning || '—' }}</span>
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <!-- P4a (Task 8): dish proposal member dishes (菜名归一 detail) -->
      <el-card v-if="isDishItem" shadow="never" style="margin-bottom: 16px;">
        <template #header>
          <span>归一成员菜名（{{ dishProposalKind || '提议' }}，共 {{ dishMemberNames.length }} 个）</span>
        </template>
        <el-empty
          v-if="dishMemberNames.length === 0"
          description="该提议无成员菜名记录"
        />
        <div v-else class="dish-member-tags">
          <el-tag
            v-for="(name, idx) in dishMemberNames"
            :key="idx"
            size="default"
            type="info"
            class="dish-member-tag"
          >{{ name }}</el-tag>
        </div>
        <p class="dish-member-note">
          字面相似不等于同菜（如 青花椒鱼 ≠ 青花椒虾）。确认归一将合并这些名字的销量/营收/评价统计。
        </p>
      </el-card>

      <!-- History table card -->
      <el-card shadow="never">
        <template #header>
          <span>历史记录（同原始名称，共 {{ historyItems.length }} 条）</span>
        </template>

        <el-table
          :data="historyItems"
          stripe
          size="default"
          class="history-table"
        >
          <el-table-column label="ID" prop="id" width="80" />

          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small">
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="优先级" width="90">
            <template #default="{ row }">
              <el-tag :type="priorityTagType(row.priority)" size="small">
                {{ row.priority || '—' }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="置信度" width="90">
            <template #default="{ row }">
              {{ confidencePercent(row.confidence) }}
            </template>
          </el-table-column>

          <el-table-column label="候选实体 ID" prop="candidateEntityId" width="110">
            <template #default="{ row }">
              {{ row.candidateEntityId ?? '—' }}
            </template>
          </el-table-column>

          <el-table-column label="已解析至" prop="resolvedToEntityId" width="100">
            <template #default="{ row }">
              {{ row.resolvedToEntityId ?? '—' }}
            </template>
          </el-table-column>

          <el-table-column label="处理动作" prop="adminAction" width="120">
            <template #default="{ row }">
              {{ row.adminAction || '—' }}
            </template>
          </el-table-column>

          <el-table-column label="处理人" prop="adminUser" width="120">
            <template #default="{ row }">
              {{ row.adminUser || '—' }}
            </template>
          </el-table-column>

          <el-table-column label="处理时间" width="160">
            <template #default="{ row }">
              {{ formatDateTime(row.adminAt) }}
            </template>
          </el-table-column>

          <el-table-column label="创建时间" width="160">
            <template #default="{ row }">
              {{ formatDateTime(row.createdAt) }}
            </template>
          </el-table-column>

          <el-table-column label="推理说明" min-width="200">
            <template #default="{ row }">
              <span class="reasoning-text">{{ row.reasoning || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

    </template>
  </div>
</template>

<style scoped>
.dq-detail-page {
  padding: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.history-table {
  width: 100%;
}

.reasoning-text {
  font-size: 13px;
  color: #606266;
  word-break: break-all;
}

/* P4a (Task 8) — dish member tags in detail */
.dish-member-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.dish-member-tag {
  margin-bottom: 4px;
}

.dish-member-note {
  margin-top: 12px;
  font-size: 12px;
  color: #e6a23c;
}
</style>
