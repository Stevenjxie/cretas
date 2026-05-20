<script setup lang="ts">
/**
 * 全部请购单 — Sprint 6 W2-A.
 *
 * 列表无 requesterId filter, 显示工厂全部请购单 (any user).
 * 用于全局视图 + 审计 + 跨人查询. Status filter 可选.
 *
 * Sprint 6 W4 enhancements (deferred):
 *   - requester 名字解析 (当前显示 #ID)
 *   - 关联 PO 链接 (convertedPoId → 跳 detail)
 *   - 导出 / 批量审批
 */
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh } from '@element-plus/icons-vue';
import {
  listRequisitions,
  deleteRequisition,
  REQUISITION_STATUS_MAP,
  type PurchaseRequisition,
  type PurchaseRequisitionStatus,
} from '@/api/purchaseRequisition';
import CreateRequisitionDialog from './CreateRequisitionDialog.vue';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();

const factoryId = computed(() => authStore.factoryId);
const currentUserId = computed(() => authStore.user?.id ?? null);
const canWrite = computed(() => permissionStore.canWrite('procurement'));

const tableData = ref<PurchaseRequisition[]>([]);
const loading = ref(false);
const statusFilter = ref<PurchaseRequisitionStatus | ''>('');
const requesterIdFilter = ref<number | null>(null);
const pagination = ref({ page: 1, size: 20, total: 0 });
const createDialogVisible = ref(false);

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await listRequisitions(factoryId.value, {
      status: statusFilter.value || undefined,
      requesterId: requesterIdFilter.value ?? undefined,
      page: pagination.value.page,
      size: pagination.value.size,
    });
    if (res?.success && res.data) {
      tableData.value = res.data.content ?? [];
      pagination.value.total = res.data.totalElements ?? 0;
    }
  } catch (e) {
    console.error('[全部请购单加载失败]', e);
  } finally {
    loading.value = false;
  }
}

onMounted(loadData);

function handlePageChange(p: number) {
  pagination.value.page = p;
  loadData();
}
function handleSizeChange(s: number) {
  pagination.value.size = s;
  pagination.value.page = 1;
  loadData();
}
function handleFilterChange() {
  pagination.value.page = 1;
  loadData();
}
function handleRefresh() {
  statusFilter.value = '';
  requesterIdFilter.value = null;
  pagination.value.page = 1;
  loadData();
}
function goDetail(id: string) {
  router.push(`/procurement/requisitions/${id}`);
}
function goPO(poId: string) {
  router.push(`/procurement/orders/${poId}`);
}

function onCreated() {
  ElMessage.success('请购单已创建, 状态为草稿. 请在"我的请购"或本页编辑后提交审批.');
  pagination.value.page = 1;
  loadData();
}

async function handleDelete(row: PurchaseRequisition) {
  // Fool-proof Rule 2: dialog 含单号 + 行数; Rule 5: backend 仅 DRAFT + requester 本人可删
  const lines = Array.isArray(row.requestedItems) ? row.requestedItems.length : 0;
  try {
    await ElMessageBox.confirm(
      `确认删除请购单 ${row.requisitionNumber} (${lines} 行明细)?\n\n此操作不可恢复.`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
      },
    );
  } catch {
    return;
  }
  if (!factoryId.value) return;
  try {
    const res = await deleteRequisition(factoryId.value, row.id);
    if (res?.success) {
      ElMessage.success('请购单已删除');
      await loadData();
    }
  } catch (e) {
    // Interceptor 已展示 sticky toast (含 backend actionHint); dedupe fallback log
    if (e !== 'cancel') console.error('[删除失败]', e);
  }
}
</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">全部请购单</span>
            <span class="data-count">共 {{ pagination.total }} 条</span>
          </div>
          <div class="header-right">
            <el-button
              v-if="canWrite"
              type="primary"
              :icon="Plus"
              @click="createDialogVisible = true"
            >
              新建请购单
            </el-button>
          </div>
        </div>
      </template>

      <div class="search-bar">
        <el-select
          v-model="statusFilter"
          placeholder="按状态筛选"
          clearable
          style="width: 160px"
          @change="handleFilterChange"
        >
          <el-option
            v-for="(v, k) in REQUISITION_STATUS_MAP"
            :key="k"
            :label="v.text"
            :value="k"
          />
        </el-select>
        <el-input-number
          v-model="requesterIdFilter"
          placeholder="按请购人 ID"
          :min="1"
          :controls="false"
          style="width: 160px"
          @change="handleFilterChange"
        />
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="tableData"
        empty-text="暂无请购单"
        stripe
        border
        style="width: 100%"
      >
        <el-table-column prop="requisitionNumber" label="单号" width="200" />
        <el-table-column prop="requesterId" label="请购人" width="100" align="center">
          <template #default="{ row }">#{{ row.requesterId }}</template>
        </el-table-column>
        <el-table-column label="明细" min-width="220">
          <template #default="{ row }">
            <span v-if="!row.requestedItems || row.requestedItems.length === 0">
              -
            </span>
            <span v-else>
              {{ row.requestedItems.length }} 项 ·
              <span class="muted">
                {{ row.requestedItems
                  .slice(0, 2)
                  .map((it: { materialName?: string }) => it.materialName || '-')
                  .join(', ') }}{{ row.requestedItems.length > 2 ? ' …' : '' }}
              </span>
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="expectedDate" label="期望交货" width="120" />
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag
              :type="REQUISITION_STATUS_MAP[row.status as PurchaseRequisitionStatus]?.type || 'info'"
              size="small"
            >
              {{ REQUISITION_STATUS_MAP[row.status as PurchaseRequisitionStatus]?.text || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="关联PO" width="130">
          <template #default="{ row }">
            <el-button
              v-if="row.convertedPoId"
              type="primary"
              link
              size="small"
              @click="goPO(row.convertedPoId)"
            >
              查看PO
            </el-button>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170">
          <template #default="{ row }">
            {{ row.createdAt ? String(row.createdAt).replace('T', ' ').slice(0, 16) : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="goDetail(row.id)">
              详情
            </el-button>
            <el-button
              v-if="row.status === 'DRAFT' && canWrite && currentUserId !== null && row.requesterId === currentUserId"
              type="danger"
              link
              size="small"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <CreateRequisitionDialog
      v-model="createDialogVisible"
      @created="onCreated"
    />
  </div>
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
    border-bottom: 1px solid #ebeef5;
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
  .header-left {
    display: flex;
    align-items: baseline;
    gap: 12px;
    .page-title {
      font-size: 16px;
      font-weight: 600;
      color: #303133;
    }
    .data-count {
      font-size: 13px;
      color: #909399;
    }
  }
}
.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid #ebeef5;
  margin-top: 16px;
}
.muted {
  color: #909399;
}
</style>
