<script setup lang="ts">
/**
 * 工作流处理 (admin) — Sprint 6 W1-B (C-MENU-PERSONAL-VIEW deferred sub 2).
 *
 * Round 12 §D X4: HJ ERP 工作流 6 sub-menu 之一. 给 super-admin / platform_admin
 * 看全局视角 — 跨 module 跨 initiator 跨 role 列出 factory 下所有 RUNNING 实例.
 *
 * 用途:
 *   - 排查卡住的流程 (谁在审, 多久没动)
 *   - 跨部门协调 (找负责人)
 *   - bulk admin 操作 — W4+ 加 force-cancel / delegate (本 MVP 暂不实现)
 *
 * 数据源: GET /api/mobile/{factoryId}/workflow/instances/admin-running
 *   - Backend: WorkflowEngineService.listAllRunning(factoryId, pageable)
 *   - RBAC: @PreAuthorize("hasAnyRole('FACTORY_SUPER_ADMIN', 'PLATFORM_ADMIN')")
 *   - 非 super-admin 调用 → 403
 *
 * 与 personal view 差异:
 *   - my-created: 按 initiator 过滤
 *   - my-participated: 按 actor join history
 *   - admin-running: 无过滤, 仅 status=RUNNING (super-admin 视角)
 *
 * Deferred (Sprint 6 W4+):
 *   - bulk actions (force-cancel, delegate)
 *   - filters (module, initiator, time range)
 *   - 当前 active 节点 count 列
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
const currentRole = computed(() => authStore.currentRole);

// 前端预判 — 非 super-admin / platform_admin 直接显示提示, 不发请求
const isAdminEligible = computed(() => {
  const r = currentRole.value;
  return r === 'factory_super_admin' || r === 'platform_admin';
});

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
  if (!isAdminEligible.value) {
    ElMessage.warning('此功能仅供管理员访问 (需 factory_super_admin / platform_admin 角色)');
    return;
  }
  loading.value = true;
  try {
    const resp = await get<PageResp>(
      `/${factoryId.value}/workflow/instances/admin-running`,
      { params: { page: currentPage.value, size: pageSize.value } },
    );
    if (resp.success && resp.data) {
      instances.value = resp.data.content || [];
      total.value = resp.data.totalElements ?? 0;
    } else {
      ElMessage.error(resp.message || '加载 admin 工作流列表失败');
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '加载 admin 工作流列表失败';
    ElMessage.error(msg);
    // eslint-disable-next-line no-console
    console.error('[AdminRunningWorkflow] load failed:', msg);
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

function pendingDays(iso: string | null): number {
  if (!iso) return 0;
  try {
    const d = new Date(iso).getTime();
    const now = Date.now();
    return Math.floor((now - d) / (1000 * 60 * 60 * 24));
  } catch {
    return 0;
  }
}

onMounted(() => {
  if (isAdminEligible.value) {
    load();
  }
});
</script>

<template>
  <div class="admin-running-workflow">
    <el-card v-if="!isAdminEligible" shadow="never">
      <el-empty description="此功能仅供管理员访问">
        <template #image>
          <el-icon :size="64" color="#909399"><i-ep-warning /></el-icon>
        </template>
        <p class="permission-hint">
          需 <code>factory_super_admin</code> 或 <code>platform_admin</code> 角色.
        </p>
        <p class="permission-hint">
          如需访问全局工作流, 请联系系统管理员开通权限.
        </p>
      </el-empty>
    </el-card>

    <el-card v-else shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <h3>工作流处理 (admin 全局视图)</h3>
            <el-tag size="small" type="info">{{ total }} 条 RUNNING</el-tag>
            <el-tag size="small" type="warning">仅 super-admin 可见</el-tag>
          </div>
          <div class="header-right">
            <el-button type="primary" :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert
        title="说明"
        type="info"
        :closable="false"
        show-icon
      >
        <p>跨 module / initiator / role 展示 factory 内所有 RUNNING 工作流. 用于排查卡住流程、跨部门协调.</p>
        <p>Bulk 操作 (force-cancel / delegate) 将于 Sprint 6 W4+ 上线.</p>
      </el-alert>

      <el-table
        v-loading="loading"
        :data="instances"
        stripe
        empty-text="工厂内无正在运行的工作流"
        style="margin-top: 12px;"
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
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="发起时间" width="160">
          <template #default="{ row }">{{ formatTime(row.initiatedAt) }}</template>
        </el-table-column>
        <el-table-column label="已挂起" width="100">
          <template #default="{ row }">
            <el-tag
              :type="pendingDays(row.initiatedAt) > 3 ? 'danger' : 'info'"
              size="small"
            >
              {{ pendingDays(row.initiatedAt) }} 天
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="goToDetail(row)">查看</el-button>
            <!-- Sprint 6 W4+ 加: 强制取消 / 委派审批人 -->
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
.admin-running-workflow {
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

  .permission-hint {
    margin: 8px 0;
    color: #606266;
    font-size: 14px;

    code {
      background: #f5f7fa;
      padding: 2px 6px;
      border-radius: 3px;
      font-family: monospace;
      color: #e6a23c;
    }
  }

  .pagination-wrapper {
    margin-top: 16px;
    display: flex;
    justify-content: flex-end;
  }
}
</style>
