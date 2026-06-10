<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { get, put } from '@/api/request';
import { handleCatchError } from '@/utils/errorToast';
import { formatDateTime } from '@/utils/dateFormat';
import type { TableRow } from '@/types/api';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('production'));

// -------- 列表状态 --------
const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const statusFilter = ref('');

// -------- 审批 Dialog 状态 --------
const approvalDialogVisible = ref(false);
const approvalLoading = ref(false);
const approvalTarget = ref<TableRow | null>(null);
const approvalOpinion = ref('');
const approvalAction = ref<'approve' | 'reject'>('approve');

// -------- 拒绝原因 --------
const REJECT_REASONS = [
  '数据仍需保留',
  '流程审批未完成',
  '成品已部分出库',
  '申请信息有误',
  '其他',
] as const;
const rejectReasonSelected = ref('数据仍需保留');
const rejectReasonOther = ref('');

onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params = statusFilter.value ? `?status=${statusFilter.value}` : '';
    const res = await get<TableRow[]>(`/${factoryId.value}/reversals${params}`);
    if (res.success) {
      tableData.value = Array.isArray(res.data) ? res.data : [];
    }
  } catch (e) {
    handleCatchError(e, '加载撤回申请列表失败，请检查网络');
  } finally {
    loading.value = false;
  }
}

function getStatusType(status: string): 'warning' | 'success' | 'info' | 'danger' {
  const map: Record<string, 'warning' | 'success' | 'info' | 'danger'> = {
    PENDING: 'warning',
    APPROVED: 'info',
    DONE: 'success',
    REJECTED: 'danger',
  };
  return map[status?.toUpperCase()] || 'info';
}

function getStatusText(status: string): string {
  const map: Record<string, string> = {
    PENDING: '待审批',
    APPROVED: '已批准',
    DONE: '已撤回',
    REJECTED: '已驳回',
  };
  return map[status?.toUpperCase()] || status || '-';
}

function getReversalScopeText(scope: string): string {
  return scope === 'WHOLE_ORDER' ? '整单撤回' : scope || '-';
}

// 点击审批/拒绝 — 打开 Dialog 并设置上下文
function openApprovalDialog(row: TableRow, action: 'approve' | 'reject') {
  approvalTarget.value = row;
  approvalAction.value = action;
  approvalOpinion.value = '';
  rejectReasonSelected.value = '数据仍需保留';
  rejectReasonOther.value = '';
  approvalDialogVisible.value = true;
}

// 确认审批
async function confirmApproval() {
  if (!factoryId.value || !approvalTarget.value) return;
  const logId = approvalTarget.value.id as number;

  // 拒绝时拼理由
  let finalReason = approvalOpinion.value;
  if (approvalAction.value === 'reject') {
    const selected = rejectReasonSelected.value;
    const other = rejectReasonOther.value.trim();
    finalReason = selected === '其他' ? (other || '其他') : (other ? `${selected}: ${other}` : selected);
  }

  approvalLoading.value = true;
  try {
    if (approvalAction.value === 'approve') {
      await put(`/${factoryId.value}/reversals/${logId}/approve`, {});
      ElMessage.success('已批准撤回申请，系统正在执行撤回');
    } else {
      await put(`/${factoryId.value}/reversals/${logId}/reject`, { reason: finalReason });
      ElMessage.success('已驳回撤回申请');
    }
    approvalDialogVisible.value = false;
    loadData();
  } catch (e) {
    handleCatchError(e, approvalAction.value === 'approve' ? '审批操作失败' : '驳回操作失败');
  } finally {
    approvalLoading.value = false;
  }
}

// 跳转批次详情
function goToBatch(batchId: number) {
  router.push(`/production/batches/${batchId}`);
}
</script>

<template>
  <div class="page-container">
    <!-- 页头 -->
    <div class="page-header" style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px">
      <div>
        <h2 style="margin:0;font-size:18px;font-weight:600;color:#303133">报工撤回申请</h2>
        <p style="margin:4px 0 0;font-size:13px;color:#909399">管理生产批次的整单撤回申请与审批</p>
      </div>
      <div style="display:flex;gap:8px;align-items:center">
        <el-select
          v-model="statusFilter"
          placeholder="状态筛选"
          clearable
          style="width:140px"
          @change="loadData"
        >
          <el-option label="全部" value="" />
          <el-option label="待审批" value="PENDING" />
          <el-option label="已批准" value="APPROVED" />
          <el-option label="已撤回" value="DONE" />
          <el-option label="已驳回" value="REJECTED" />
        </el-select>
        <el-button :icon="Refresh" @click="loadData">刷新</el-button>
      </div>
    </div>

    <!-- 主列表 -->
    <el-card shadow="never" style="border-radius:10px">
      <el-table
        v-loading="loading"
        :data="tableData"
        stripe
        style="width:100%"
        empty-text="暂无撤回申请"
      >
        <!-- 批次号 -->
        <el-table-column label="批次 ID" width="100">
          <template #default="{ row }">
            <el-button
              type="primary"
              link
              @click="goToBatch(row.batchId)"
              style="font-weight:600"
            >
              #{{ row.batchId }}
            </el-button>
          </template>
        </el-table-column>

        <!-- 撤回范围 -->
        <el-table-column label="撤回范围" width="110">
          <template #default="{ row }">
            <el-tag type="info" size="small">{{ getReversalScopeText(row.reversalScope) }}</el-tag>
          </template>
        </el-table-column>

        <!-- 状态徽章 -->
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small" effect="dark">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>

        <!-- 申请原因 -->
        <el-table-column label="申请原因" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span style="color:#606266">{{ row.reason || '—' }}</span>
          </template>
        </el-table-column>

        <!-- 已回退流水数 -->
        <el-table-column label="已回退流水" width="110">
          <template #default="{ row }">
            <span v-if="row.revertedTxnIds && row.revertedTxnIds.length > 0" style="color:#67C23A;font-weight:500">
              {{ row.revertedTxnIds.length }} 笔
            </span>
            <span v-else style="color:#C0C4CC">—</span>
          </template>
        </el-table-column>

        <!-- 提交人 -->
        <el-table-column label="提交人 ID" width="100">
          <template #default="{ row }">
            <span style="color:#606266">{{ row.submittedBy || '—' }}</span>
          </template>
        </el-table-column>

        <!-- 审批人 -->
        <el-table-column label="审批人 ID" width="100">
          <template #default="{ row }">
            <span style="color:#606266">{{ row.approvedBy || '—' }}</span>
          </template>
        </el-table-column>

        <!-- 审批时间 -->
        <el-table-column label="审批时间" width="160">
          <template #default="{ row }">
            <span style="color:#909399;font-size:12px">
              {{ row.approvedAt ? formatDateTime(row.approvedAt) : '—' }}
            </span>
          </template>
        </el-table-column>

        <!-- 申请时间 -->
        <el-table-column label="申请时间" width="160">
          <template #default="{ row }">
            <span style="color:#909399;font-size:12px">
              {{ formatDateTime(row.createdAt) }}
            </span>
          </template>
        </el-table-column>

        <!-- 操作 -->
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING' && canWrite">
              <el-button
                type="success"
                size="small"
                @click="openApprovalDialog(row, 'approve')"
              >批准</el-button>
              <el-button
                type="danger"
                size="small"
                plain
                @click="openApprovalDialog(row, 'reject')"
              >驳回</el-button>
            </template>
            <el-button
              v-else
              type="primary"
              link
              size="small"
              @click="goToBatch(row.batchId)"
            >查看批次</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- ===== 审批 Dialog ===== -->
    <el-dialog
      v-model="approvalDialogVisible"
      :title="approvalAction === 'approve' ? '批准撤回申请' : '驳回撤回申请'"
      width="520px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <template v-if="approvalTarget">
        <!-- 批次上下文（fool-proof Rule 2: context 必带） -->
        <el-alert
          :title="`撤回对象: 批次 #${approvalTarget.batchId}`"
          type="info"
          :closable="false"
          show-icon
          style="margin-bottom:16px"
        >
          <template #default>
            <div style="font-size:13px;color:#606266;line-height:1.6;margin-top:4px">
              <span v-if="approvalTarget.planId">
                关联计划: <strong>{{ approvalTarget.planId }}</strong><br>
              </span>
              撤回范围: <strong>{{ getReversalScopeText(approvalTarget.reversalScope) }}</strong><br>
              <template v-if="approvalTarget.revertedTxnIds && approvalTarget.revertedTxnIds.length > 0">
                将回退流水: <strong>{{ approvalTarget.revertedTxnIds.length }} 笔</strong>（含原料回补 / WIP 作废 / 成品软删）<br>
              </template>
              申请原因: <strong>{{ approvalTarget.reason || '（未填写）' }}</strong>
            </div>
          </template>
        </el-alert>

        <!-- 拒绝时: 原因 dropdown（fool-proof Rule 3） -->
        <template v-if="approvalAction === 'reject'">
          <div style="margin-bottom:8px">
            <span style="font-size:13px;font-weight:500;color:#303133">驳回原因</span>
            <span style="color:#F56C6C;margin-left:2px">*</span>
          </div>
          <el-select
            v-model="rejectReasonSelected"
            placeholder="选择驳回原因"
            style="width:100%;margin-bottom:10px"
          >
            <el-option v-for="r in REJECT_REASONS" :key="r" :label="r" :value="r" />
          </el-select>
          <el-input
            v-if="rejectReasonSelected === '其他'"
            v-model="rejectReasonOther"
            type="textarea"
            :rows="3"
            placeholder="请补充说明驳回原因"
            maxlength="300"
            show-word-limit
            style="margin-bottom:10px"
          />
          <el-input
            v-else
            v-model="rejectReasonOther"
            type="textarea"
            :rows="2"
            :placeholder="`可选: 补充说明（已选: ${rejectReasonSelected}）`"
            maxlength="200"
            show-word-limit
          />
        </template>

        <!-- 批准时: 可选意见 -->
        <template v-else>
          <div style="margin-bottom:8px">
            <span style="font-size:13px;font-weight:500;color:#303133">审批意见（可选）</span>
          </div>
          <el-input
            v-model="approvalOpinion"
            type="textarea"
            :rows="3"
            placeholder="可填写审批意见，不填写则默认通过"
            maxlength="200"
            show-word-limit
          />
          <el-alert
            v-if="approvalAction === 'approve'"
            title="批准后将自动执行整单撤回: 原料回补、WIP 作废、成品软删、移动均价重算。此操作不可逆。"
            type="warning"
            :closable="false"
            show-icon
            style="margin-top:12px"
          />
        </template>
      </template>

      <template #footer>
        <div style="display:flex;justify-content:flex-end;gap:8px">
          <el-button @click="approvalDialogVisible = false">取消</el-button>
          <el-button
            :type="approvalAction === 'approve' ? 'success' : 'danger'"
            :loading="approvalLoading"
            @click="confirmApproval"
          >
            {{ approvalAction === 'approve' ? '确认批准' : '确认驳回' }}
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-container {
  padding: 20px;
  background: #F4F6F9;
  min-height: 100%;
}
</style>
