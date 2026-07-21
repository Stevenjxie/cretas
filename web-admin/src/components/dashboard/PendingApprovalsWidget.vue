<script setup lang="ts">
/**
 * 我待审 widget — issue #20 (Phase 1 final closure for ADR-001 AC-3).
 *
 * 展示当前登录用户角色的待审 RUNNING workflow 实例.
 * - finance_manager → 看含财务审批节点的 PO
 * - quality_manager → 看含质检审批节点的 PO
 * - factory_super_admin → 看全部 (兜底)
 *
 * 数据源: GET /api/mobile/{factoryId}/workflow/instances/pending
 * 点击行: PURCHASE_ORDER → 跳 /procurement/orders/{businessEntityId}
 */
import { ref, onMounted, computed } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';

interface PendingInstance {
  instanceId: string;
  moduleCode: string;
  businessEntityId: string;
  businessSummary: string;
  currentNodeId: string | null;
  currentNodeLabel: string | null;
  initiatedAt: string | null;
  initiatedByUsername: string | null;
}

interface PendingPageResp {
  content: PendingInstance[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

const router = useRouter();
const authStore = useAuthStore();

const factoryId = computed(() => authStore.factoryId);
const instances = ref<PendingInstance[]>([]);
const total = ref(0);
const loading = ref(false);
const loadError = ref('');

// 仅显示 widget 当 totalElements > 0 (避免给无待审的角色空 card)
const visible = computed(() => total.value > 0);

async function load(): Promise<void> {
  if (!factoryId.value) return;
  loading.value = true;
  loadError.value = '';
  try {
    const resp = await get<PendingPageResp>(
      `/${factoryId.value}/workflow/instances/pending`,
      { params: { page: 1, size: 20 } }
    );
    if (resp.success && resp.data) {
      instances.value = resp.data.content || [];
      total.value = resp.data.totalElements ?? instances.value.length;
    } else {
      loadError.value = resp.message || '加载待审列表失败';
    }
  } catch (e: unknown) {
    // 静默失败 — widget 不应阻断 Dashboard 渲染
    const msg = e instanceof Error ? e.message : '加载待审列表失败';
    loadError.value = msg;
    // eslint-disable-next-line no-console
    console.warn('[PendingApprovalsWidget] load failed:', msg);
  } finally {
    loading.value = false;
  }
}

function goToDetail(row: PendingInstance): void {
  // 审批动作统一在 OA 中完成；业务详情只读展示状态与轨迹。
  router.push({ path: '/workflow/pending', query: { instanceId: row.instanceId } });
}

function formatTime(iso: string | null): string {
  if (!iso) return '-';
  try {
    const d = new Date(iso);
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  } catch {
    return iso;
  }
}

onMounted(() => {
  load();
});
</script>

<template>
  <el-card v-if="visible" class="pending-approvals-widget" shadow="hover" v-loading="loading">
    <template #header>
      <div class="widget-header">
        <span class="title">
 <span class="icon"></span>
          我待审
          <el-badge :value="total" class="count-badge" type="danger" />
        </span>
        <el-button type="primary" link @click="load" :loading="loading">刷新</el-button>
      </div>
    </template>

    <el-alert
      v-if="loadError"
      :title="loadError"
      type="warning"
      show-icon
      :closable="false"
      style="margin-bottom: 12px"
    />

    <el-table
      :data="instances"
      stripe
      size="small"
      style="width: 100%"
      empty-text="暂无待审项"
      @row-click="goToDetail"
    >
      <el-table-column prop="businessSummary" label="待审项" min-width="280">
        <template #default="{ row }">
          <span class="biz-summary">{{ row.businessSummary }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="currentNodeLabel" label="审批节点" width="140">
        <template #default="{ row }">
          <el-tag size="small" type="warning">{{ row.currentNodeLabel || row.currentNodeId || '-' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="initiatedByUsername" label="发起人" width="140">
        <template #default="{ row }">{{ row.initiatedByUsername || '系统' }}</template>
      </el-table-column>
      <el-table-column prop="initiatedAt" label="发起时间" width="160">
        <template #default="{ row }">{{ formatTime(row.initiatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link @click.stop="goToDetail(row)">查看</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<style lang="scss" scoped>
.pending-approvals-widget {
  margin-bottom: 16px;

  .widget-header {
    display: flex;
    justify-content: space-between;
    align-items: center;

    .title {
      display: flex;
      align-items: center;
      gap: 8px;
      font-weight: 600;
      font-size: 16px;

      .icon {
        font-size: 18px;
      }

      .count-badge {
        margin-left: 4px;
      }
    }
  }

  :deep(.el-table) {
    cursor: pointer;
  }

  .biz-summary {
    font-weight: 500;
    color: #303133;
  }
}
</style>
