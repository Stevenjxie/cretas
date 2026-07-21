<script setup lang="ts">
/**
 * 我参与的工作流 — Sprint 6 W1-B (C-MENU-PERSONAL-VIEW deferred sub 1).
 *
 * Round 12 §D X4 finding: HJ 工作流 6 sub-menu, Sprint 5 ship 1 (my-created),
 * 本视图填补 personal view 第 2 个 — 用户查看自己发起或作为 actor 处理过的所有 workflow
 * 实例（跨 status，后端按发起人或历史处理人去重）。
 *
 * 数据源: GET /api/mobile/{factoryId}/workflow/instances/my-participated
 *   - Backend shipped in Sprint 5 (commit 9105dbc70)
 *   - Repo: ApprovalWorkflowInstanceRepository.findParticipatedBy (DISTINCT JPQL join)
 *
 * 与 my-created.vue 差异:
 *   - my-created: 按 initiator 过滤 (我起的所有, 跨 status)
 *   - my-participated: 我发起或我处理过（含他人发起、由我处理的实例）
 *
 * @since 2026-05-19 (Sprint 6 W1-B)
 */
import { ref, onMounted, computed } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';

interface WorkflowInstanceRow {
  instanceId: string;
  moduleCode: string;
  businessEntityId: string;
  businessSummary: string;
  currentNodeId: string | null;
  currentNodeLabel: string | null;
  initiatedAt: string | null;
  initiatedByUsername: string | null;
}

interface PageResp {
  content: WorkflowInstanceRow[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

const router = useRouter();
const authStore = useAuthStore();

const factoryId = computed(() => authStore.factoryId);
const instances = ref<WorkflowInstanceRow[]>([]);
const total = ref(0);
const loading = ref(false);
const currentPage = ref(1);
const pageSize = ref(20);

async function load(): Promise<void> {
  if (!factoryId.value) {
    ElMessage.warning('未登录或工厂 ID 缺失');
    return;
  }
  loading.value = true;
  try {
    const resp = await get<PageResp>(
      `/${factoryId.value}/workflow/instances/my-participated`,
      { params: { page: currentPage.value, size: pageSize.value } },
    );
    if (resp.success && resp.data) {
      instances.value = resp.data.content || [];
      total.value = resp.data.totalElements ?? 0;
    } else {
      ElMessage.error(resp.message || '加载我参与的工作流失败');
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '加载我参与的工作流失败';
    ElMessage.error(msg);
    // eslint-disable-next-line no-console
    console.error('[MyParticipatedWorkflow] load failed:', msg);
  } finally {
    loading.value = false;
  }
}

function handlePageChange(page: number): void {
  currentPage.value = page;
  load();
}

function handleSizeChange(size: number): void {
  pageSize.value = size;
  currentPage.value = 1;
  load();
}

function goToDetail(row: WorkflowInstanceRow): void {
  if (row.moduleCode === 'PURCHASE_ORDER' && row.businessEntityId) {
    router.push(`/procurement/orders/${row.businessEntityId}`);
  } else if (row.moduleCode === 'SALES_ORDER' && row.businessEntityId) {
    router.push(`/sales/orders/${row.businessEntityId}`);
  } else {
    ElMessage.info(`暂不支持跳转模块: ${row.moduleCode}`);
  }
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

function moduleLabel(moduleCode: string): string {
  const map: Record<string, string> = {
    PURCHASE_ORDER: '采购订单',
    SALES_ORDER: '销售订单',
    DISPOSAL: '废弃处置',
    TRANSFER: '调拨单',
    VOUCHER: '凭证',
    ECN: '工程变更',
  };
  return map[moduleCode] || moduleCode;
}

onMounted(() => {
  load();
});
</script>

<template>
  <div class="my-participated-workflow">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <h3>我参与的工作流</h3>
            <el-tag size="small" type="info">{{ total }} 条记录</el-tag>
          </div>
          <div class="header-right">
            <el-button type="primary" :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="instances"
        stripe
        empty-text="暂无您参与的工作流实例"
      >
        <el-table-column label="业务摘要" min-width="280">
          <template #default="{ row }">
            <span class="biz-summary">{{ row.businessSummary }}</span>
          </template>
        </el-table-column>
        <el-table-column label="模块" width="120">
          <template #default="{ row }">
            <el-tag size="small">{{ moduleLabel(row.moduleCode) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发起人" width="120">
          <template #default="{ row }">
            <span>{{ row.initiatedByUsername || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="当前节点" width="140">
          <template #default="{ row }">
            <el-tag v-if="row.currentNodeLabel" size="small" type="warning">
              {{ row.currentNodeLabel }}
            </el-tag>
            <span v-else class="text-muted">已完成</span>
          </template>
        </el-table-column>
        <el-table-column label="发起时间" width="160">
          <template #default="{ row }">{{ formatTime(row.initiatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="goToDetail(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.my-participated-workflow {
  padding: 16px;

  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;

    .header-left {
      display: flex;
      align-items: center;
      gap: 12px;

      h3 {
        margin: 0;
        font-size: 16px;
        font-weight: 600;
      }
    }
  }

  .biz-summary {
    font-weight: 500;
    color: #303133;
  }

  .text-muted {
    color: #909399;
    font-size: 13px;
  }

  .pagination-wrapper {
    margin-top: 16px;
    display: flex;
    justify-content: flex-end;
  }
}
</style>
