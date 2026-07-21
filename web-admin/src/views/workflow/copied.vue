<script setup lang="ts">
/** Personal OA "抄送我的": notify-node instances addressed to the current user or role. */
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
      `/${factoryId.value}/workflow/instances/copied`,
      { params: { page: currentPage.value, size: pageSize.value } },
    );
    if (resp.success && resp.data) {
      instances.value = resp.data.content || [];
      total.value = resp.data.totalElements ?? 0;
    } else {
      ElMessage.error(resp.message || '加载抄送我的失败');
    }
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : '加载抄送我的失败';
    ElMessage.error(message);
    console.error('[CopiedWorkflow] load failed:', message);
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
    ElMessage.info(`暂不支持跳转模块：${row.moduleCode}`);
  }
}

function formatTime(iso: string | null): string {
  if (!iso) return '-';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function moduleLabel(moduleCode: string): string {
  const labels: Record<string, string> = {
    PURCHASE_ORDER: '采购订单',
    SALES_ORDER: '销售订单',
    DISPOSAL: '废弃处置',
    TRANSFER: '调拨单',
    VOUCHER: '凭证',
    ECN: '工程变更',
  };
  return labels[moduleCode] || moduleCode;
}

onMounted(load);
</script>

<template>
  <div class="personal-workflow">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <h3>抄送我的</h3>
            <el-tag size="small" type="info">{{ total }} 条记录</el-tag>
          </div>
          <el-button type="primary" :loading="loading" @click="load">刷新</el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="instances" stripe empty-text="暂无抄送给您的审批">
        <el-table-column label="业务摘要" min-width="280">
          <template #default="{ row }"><span class="biz-summary">{{ row.businessSummary }}</span></template>
        </el-table-column>
        <el-table-column label="模块" width="120">
          <template #default="{ row }"><el-tag size="small">{{ moduleLabel(row.moduleCode) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="发起人" width="120">
          <template #default="{ row }">{{ row.initiatedByUsername || '-' }}</template>
        </el-table-column>
        <el-table-column label="当前节点" width="140">
          <template #default="{ row }">
            <el-tag v-if="row.currentNodeLabel" size="small" type="warning">{{ row.currentNodeLabel }}</el-tag>
            <span v-else class="text-muted">已完成</span>
          </template>
        </el-table-column>
        <el-table-column label="发起时间" width="160">
          <template #default="{ row }">{{ formatTime(row.initiatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }"><el-button type="primary" link @click="goToDetail(row)">查看</el-button></template>
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
.personal-workflow {
  padding: 16px;

  .card-header,
  .header-left {
    display: flex;
    align-items: center;
  }

  .card-header { justify-content: space-between; }
  .header-left { gap: 12px; }
  h3 { margin: 0; font-size: 16px; font-weight: 600; }
  .biz-summary { font-weight: 500; color: #303133; }
  .text-muted { color: #909399; font-size: 13px; }
  .pagination-wrapper { margin-top: 16px; display: flex; justify-content: flex-end; }
}
</style>