<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Check, Close, Refresh } from '@element-plus/icons-vue';
import { handleCatchError } from '@/utils/errorToast';
import {
  getPendingApprovals, approveReport, rejectReport, batchApproveReports,
  type ApprovalItem
} from '@/api/processProduction';
import OpinionInputDialog from '@/components/approval/OpinionInputDialog.vue';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('production'));

const loading = ref(false);
const tableData = ref<ApprovalItem[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });
const selectedIds = ref<number[]>([]);

// P2-6: Auto-refresh every 30 seconds
let refreshTimer: ReturnType<typeof setInterval> | null = null;
onMounted(() => {
  loadData();
  refreshTimer = setInterval(loadData, 30000);
});
onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer);
});

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const response = await getPendingApprovals(factoryId.value, {
      page: pagination.value.page,
      size: pagination.value.size
    });
    if (response.success && response.data) {
      tableData.value = (response.data.content || []) as ApprovalItem[];
      pagination.value.total = response.data.totalElements || 0;
    }
  } catch (e) {
    // UX polish (2026-05-20): interceptor toast already covers 4xx/5xx.
    handleCatchError(e, '加载待审批列表失败,请检查网络');
  } finally {
    loading.value = false;
  }
}

async function handleApprove(row: ApprovalItem) {
  if (!factoryId.value) return;
  try {
    await approveReport(factoryId.value, row.id);
    ElMessage.success('已通过');
    loadData();
  } catch (e) {
    // R26 P1 (qa-prompt v2.4 Rule 7+8 real-window finding): pre-fix this catch
    // unconditionally fired ElMessage.error('审批失败') on top of axios interceptor's
    // rich actionHint toast → user saw BOTH "审批失败" + "请刷新报工列表查看最新审批状态".
    // Per R24 follow-up pattern: skip fallback when interceptor handled (rich error
    // identifiable by err.actionHint OR err.status === 409 — both routes axios already toasted).
    const err = e as { status?: number; actionHint?: string | null } | undefined;
    if (!err || (err.status !== 409 && !err.actionHint)) {
      ElMessage.error('审批失败');
    }
    // R26 follow-up (reviewer #16 concern #4): only reload on 409 (already-processed
    // race — backend state diverged from UI cache). On 502/network error, loadData()
    // would just retry-and-fail too, doubling failed-request rate during outage.
    if (err?.status === 409) loadData();
  }
}

// C-OPINION-1: 弹框 state + pending row reference (替代 ElMessageBox.prompt)
const rejectDialogVisible = ref(false);
const rejectTargetRow = ref<ApprovalItem | null>(null);

function handleReject(row: ApprovalItem) {
  if (!factoryId.value) return;
  rejectTargetRow.value = row;
  rejectDialogVisible.value = true;
}

async function handleRejectConfirm(reason: string) {
  const row = rejectTargetRow.value;
  if (!factoryId.value || !row) return;
  try {
    await rejectReport(factoryId.value, row.id, reason);
    ElMessage.success('已驳回');
    loadData();
  } catch (e) {
    // Interceptor shows specific toast; dedupe fallback
    if (e !== 'cancel') console.error('[操作失败]', e);
  } finally {
    rejectTargetRow.value = null;
  }
}

async function handleBatchApprove() {
  if (!factoryId.value || selectedIds.value.length === 0) return;
  try {
    await ElMessageBox.confirm(
      `确定批量通过 ${selectedIds.value.length} 条报工记录？`,
      '批量审批',
      { type: 'warning' }
    );
    await batchApproveReports(factoryId.value, selectedIds.value);
    ElMessage.success(`已批量通过 ${selectedIds.value.length} 条`);
    selectedIds.value = [];
    loadData();
  } catch (e) {
    if (e === 'cancel') return;
    // R26 P1: skip fallback when interceptor handled rich error (same gate as handleApprove).
    const err = e as { status?: number; actionHint?: string | null } | undefined;
    if (!err || (err.status !== 409 && !err.actionHint)) {
      ElMessage.error('批量审批失败');
    }
    // R26 follow-up (reviewer #16 concern #4): only reload on 409 race.
    if (err?.status === 409) loadData();
  }
}

function handleSelectionChange(rows: ApprovalItem[]) {
  selectedIds.value = rows.map(r => r.id);
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function formatDate(dateStr: string | null) {
  if (!dateStr) return '-';
  return dateStr.substring(0, 10);
}
</script>

<template>
  <div class="page-container">
    <el-card>
      <div class="toolbar">
        <div class="toolbar-left">
          <h2 style="margin: 0">报工审批</h2>
          <el-tag type="warning">待审批 {{ pagination.total }}</el-tag>
        </div>
        <div class="toolbar-right">
          <el-button :icon="Refresh" @click="loadData" />
          <el-button
            v-if="canWrite && selectedIds.length > 0"
            type="success"
            :icon="Check"
            @click="handleBatchApprove"
          >
            批量通过 ({{ selectedIds.length }})
          </el-button>
        </div>
      </div>
    </el-card>

    <el-card style="margin-top: 16px">
      <el-table
        :data="tableData"
        v-loading="loading"
        stripe
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="50" />
        <el-table-column prop="reporterName" label="报工人" width="100" />
        <el-table-column prop="reportDate" label="报工日期" width="110">
          <template #default="{ row }">{{ formatDate(row.reportDate) }}</template>
        </el-table-column>
        <el-table-column prop="processCategory" label="工序" min-width="100" />
        <el-table-column prop="outputQuantity" label="数量" width="100">
          <template #default="{ row }">
            <span :class="{ 'text-warning': row.isSupplemental }">
              {{ row.outputQuantity }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="isSupplemental" label="类型" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.isSupplemental" type="warning" size="small">补报</el-tag>
            <el-tag v-else type="info" size="small">正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="processTaskId" label="任务ID" width="120" show-overflow-tooltip />
        <el-table-column label="操作" width="160" fixed="right" v-if="canWrite">
          <template #default="{ row }">
            <el-button type="success" text size="small" :icon="Check" @click="handleApprove(row)">
              通过
            </el-button>
            <el-button type="danger" text size="small" :icon="Close" @click="handleReject(row)">
              驳回
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="pagination.total > 0"
        style="margin-top: 16px; justify-content: flex-end"
        :current-page="pagination.page"
        :page-size="pagination.size"
        :total="pagination.total"
        layout="total, prev, pager, next"
        @current-change="handlePageChange"
      />
    </el-card>

    <!-- C-OPINION-1: 驳回意见弹框 (R3 必选 dropdown + R2 context line) -->
    <OpinionInputDialog
      v-if="factoryId"
      v-model:visible="rejectDialogVisible"
      title="驳回报工"
      :context-line="
        rejectTargetRow
          ? `${rejectTargetRow.reporterName ?? '?'} - ${rejectTargetRow.processCategory ?? '?'} (${formatDate(rejectTargetRow.reportDate)})`
          : ''
      "
      :factory-id="factoryId"
      decision-type="CUSTOM"
      other-placeholder="请输入驳回原因"
      confirm-text="驳回"
      @confirm="handleRejectConfirm"
    />
  </div>
</template>

<style scoped>
.page-container { padding: 20px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; }
.toolbar-left { display: flex; align-items: center; gap: 12px; }
.toolbar-right { display: flex; gap: 8px; }
.text-warning { color: #e6a23c; font-weight: 600; }
</style>
