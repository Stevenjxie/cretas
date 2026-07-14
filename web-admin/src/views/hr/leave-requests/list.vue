<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, Plus } from '@element-plus/icons-vue';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('hr'));

type LeaveRow = {
  id: string;
  userId: number;
  leaveType: string;
  startDate: string;
  endDate: string;
  durationHours: number;
  reason?: string;
  status: 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'PAID';
  submittedAt?: string;
  approvedAt?: string;
  rejectReason?: string;
};

type BalanceRow = {
  leaveType: string; yearMonth: string;
  earnedHours: number; usedHours: number; balanceHours: number;
};

const LEAVE_TYPES = [
  { value: 'ANNUAL', label: '年假 (跟踪余额)' },
  { value: 'SICK', label: '病假 (跟踪余额)' },
  { value: 'PERSONAL', label: '事假' },
  { value: 'COMPTIME', label: '调休 (跟踪余额)' },
  { value: 'MARRIAGE', label: '婚假' },
  { value: 'FUNERAL', label: '丧假' },
  { value: 'MATERNITY', label: '产假' },
  { value: 'PATERNITY', label: '陪产假' },
  { value: 'OTHER', label: '其它' },
];
const LEAVE_TYPE_LABEL = Object.fromEntries(LEAVE_TYPES.map(t => [t.value, t.label.split(' ')[0]]));
const BALANCE_TRACKED = ['ANNUAL', 'SICK', 'COMPTIME', 'OTHER'];

const STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿', SUBMITTED: '待审批', APPROVED: '已批准',
  REJECTED: '已拒绝', CANCELLED: '已撤回', PAID: '已付款',
};
const STATUS_TAG_TYPE: Record<string, '' | 'success' | 'warning' | 'danger' | 'info'> = {
  DRAFT: 'info', SUBMITTED: 'warning', APPROVED: 'success',
  REJECTED: 'danger', CANCELLED: 'info', PAID: 'success',
};

// R3 reject reason dropdown (R3 防呆: enum 标准原因 + 其他 fallback)
const REJECT_REASONS = ['余额不足', '业务高峰期', '时间冲突', '材料不全', '其他'];

const activeTab = ref<'mine' | 'pending' | 'all' | 'summary'>('mine');
const loading = ref(false);
const tableData = ref<LeaveRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });

// 筛选 (仅"全部"tab 支持, 后端 /hr/leave-requests 已加 leaveType/status 可选参数)
const filterLeaveType = ref('');
const filterStatus = ref('');
function handleFilterChange() {
  pagination.value.page = 1;
  loadData();
}
function handleFilterReset() {
  filterLeaveType.value = '';
  filterStatus.value = '';
  pagination.value.page = 1;
  loadData();
}
const summaryData = ref<Record<string, number>>({});
const summaryYearMonth = ref<string>(currentYearMonth());

// === Create dialog state ===
const createDialogVisible = ref(false);
const createSubmitting = ref(false);
const createForm = ref({
  leaveType: 'ANNUAL', startDate: '', endDate: '',
  durationHours: 8, reason: '',
});
// R1 balance hint
const balanceHint = ref<BalanceRow | null>(null);

// === Reject dialog state ===
const rejectDialogVisible = ref(false);
const rejectingRow = ref<LeaveRow | null>(null);
const rejectForm = ref({ reasonKey: '余额不足', otherDetail: '' });

function currentYearMonth(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

onMounted(() => { loadData(); });

watch(activeTab, () => {
  pagination.value.page = 1;
  if (activeTab.value === 'summary') loadSummary(); else loadData();
});

// Re-fetch balance when type/date changes (R1)
watch(() => [createForm.value.leaveType, createForm.value.startDate], () => {
  if (createDialogVisible.value) loadBalanceHint();
});

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const endpoint = activeTab.value === 'mine'
      ? `/${factoryId.value}/hr/leave-requests/mine`
      : activeTab.value === 'pending'
        ? `/${factoryId.value}/hr/leave-requests/pending`
        : `/${factoryId.value}/hr/leave-requests`;
    const res = await get(endpoint, {
      params: {
        page: pagination.value.page - 1,
        size: pagination.value.size,
        ...(activeTab.value === 'all'
          ? { leaveType: filterLeaveType.value || undefined, status: filterStatus.value || undefined }
          : {})
      }
    });
    if (res.success && res.data) {
      const data = res.data as { content?: LeaveRow[]; totalElements?: number };
      tableData.value = data.content || [];
      pagination.value.total = data.totalElements || 0;
    } else {
      tableData.value = [];
      pagination.value.total = 0;
    }
  } catch (e) {
    console.error('加载请假申请失败:', e);
    tableData.value = [];
    pagination.value.total = 0;
  } finally {
    loading.value = false;
  }
}

async function loadSummary() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await get(`/${factoryId.value}/hr/leave-requests/summary`, {
      params: { yearMonth: summaryYearMonth.value }
    });
    if (res.success && res.data) {
      summaryData.value = res.data as Record<string, number>;
    }
  } catch (e) {
    console.error('加载月汇总失败:', e);
  } finally {
    loading.value = false;
  }
}

// R1 防呆: 显示当前类型 + 月度余额
async function loadBalanceHint() {
  balanceHint.value = null;
  if (!factoryId.value || !createForm.value.startDate) return;
  if (!BALANCE_TRACKED.includes(createForm.value.leaveType)) return;
  const ym = createForm.value.startDate.slice(0, 7);
  try {
    const res = await get(`/${factoryId.value}/hr/leave-balances/mine`, {
      params: { yearMonth: ym }
    });
    if (res.success && res.data) {
      const rows = res.data as BalanceRow[];
      balanceHint.value = rows.find(r => r.leaveType === createForm.value.leaveType) || null;
    }
  } catch (e) {
    console.error('加载余额失败:', e);
  }
}

const balanceWarn = computed(() => {
  if (!balanceHint.value) return null;
  if (createForm.value.durationHours > balanceHint.value.balanceHours) {
    return `申请 ${createForm.value.durationHours}h 超出剩余 ${balanceHint.value.balanceHours}h`;
  }
  return null;
});

function openCreate() {
  createForm.value = { leaveType: 'ANNUAL', startDate: '', endDate: '', durationHours: 8, reason: '' };
  balanceHint.value = null;
  createDialogVisible.value = true;
}

async function handleCreate(submitNow: boolean) {
  if (!createForm.value.startDate || !createForm.value.endDate) {
    ElMessage.error('请选择开始/结束日期');
    return;
  }
  if (createForm.value.durationHours <= 0) {
    ElMessage.error('时长必须 > 0');
    return;
  }
  createSubmitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/hr/leave-requests`, createForm.value);
    if (res.success && res.data) {
      const id = (res.data as { id: string }).id;
      if (submitNow) {
        const sres = await post(`/${factoryId.value}/hr/leave-requests/${id}/submit`);
        if (sres.success) ElMessage.success('已提交审批');
      } else {
        ElMessage.success('已保存草稿');
      }
      createDialogVisible.value = false;
      await loadData();
    }
  } catch (e) {
    // 4位一体: 全局 axios interceptor 已 sticky display backend message (含 R4 dedup 409)
    console.error('创建失败:', e);
  } finally {
    createSubmitting.value = false;
  }
}

// R2 防呆: 审批 dialog 显示申请人 + 类型 + 时长
async function approveRow(row: LeaveRow) {
  try {
    await ElMessageBox.confirm(
      `批准 [申请人 ${row.userId}] ${LEAVE_TYPE_LABEL[row.leaveType]} ${row.durationHours} 小时 (${row.startDate} ~ ${row.endDate})?`,
      '审批确认',
      { confirmButtonText: '批准', cancelButtonText: '取消', type: 'warning' }
    );
    const res = await post(`/${factoryId.value}/hr/leave-requests/${row.id}/approve`);
    if (res.success) {
      ElMessage.success('已批准');
      await loadData();
    }
  } catch (e) {
    if (e !== 'cancel') console.error(e);
  }
}

// R3 防呆: 拒绝原因 dropdown + "其他"才展开 textarea
function openReject(row: LeaveRow) {
  rejectingRow.value = row;
  rejectForm.value = { reasonKey: '余额不足', otherDetail: '' };
  rejectDialogVisible.value = true;
}

async function confirmReject() {
  if (!rejectingRow.value) return;
  const finalReason = rejectForm.value.reasonKey === '其他'
    ? rejectForm.value.otherDetail.trim()
    : rejectForm.value.reasonKey;
  if (!finalReason) {
    ElMessage.error('请填写"其他"补充说明');
    return;
  }
  try {
    const res = await post(
      `/${factoryId.value}/hr/leave-requests/${rejectingRow.value.id}/reject`,
      { reason: finalReason }
    );
    if (res.success) {
      ElMessage.success('已拒绝');
      rejectDialogVisible.value = false;
      await loadData();
    }
  } catch (e) {
    console.error(e);
  }
}

async function cancelRow(row: LeaveRow) {
  try {
    await ElMessageBox.confirm(
      `撤回 ${LEAVE_TYPE_LABEL[row.leaveType]} ${row.durationHours}h (${row.startDate} ~ ${row.endDate})?`,
      '撤回确认'
    );
    const res = await post(`/${factoryId.value}/hr/leave-requests/${row.id}/cancel`);
    if (res.success) {
      ElMessage.success('已撤回');
      await loadData();
    }
  } catch (e) {
    if (e !== 'cancel') console.error(e);
  }
}
</script>

<template>
  <div class="leave-requests-page" style="padding: 16px;">
    <el-card>
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
        <h2 style="margin: 0;">请假申请</h2>
        <div>
          <el-button type="primary" :icon="Plus" @click="openCreate">新建请假</el-button>
          <el-button :icon="Refresh" @click="activeTab === 'summary' ? loadSummary() : loadData()">刷新</el-button>
        </div>
      </div>

      <el-tabs v-model="activeTab">
        <el-tab-pane label="我的申请" name="mine" />
        <el-tab-pane v-if="canWrite" label="待审批" name="pending" />
        <el-tab-pane v-if="canWrite" label="全部" name="all" />
        <el-tab-pane v-if="canWrite" label="月汇总" name="summary" />
      </el-tabs>

      <!-- 月汇总视图 -->
      <div v-if="activeTab === 'summary'">
        <el-form inline>
          <el-form-item label="月份">
            <el-input v-model="summaryYearMonth" placeholder="YYYY-MM" style="width: 140px;" @change="loadSummary" />
          </el-form-item>
        </el-form>
        <el-table :data="Object.entries(summaryData).map(([k, v]) => ({ leaveType: k, hours: v }))" v-loading="loading">
          <el-table-column prop="leaveType" label="假期类型" width="200">
            <template #default="{ row }">{{ LEAVE_TYPE_LABEL[row.leaveType] || row.leaveType }}</template>
          </el-table-column>
          <el-table-column prop="hours" label="总小时数" />
        </el-table>
      </div>

      <!-- 列表视图 -->
      <div v-else>
        <!-- 筛选 (仅"全部"tab, 类型/状态均为固定业务枚举) -->
        <div v-if="activeTab === 'all'" class="filter-bar">
          <el-select v-model="filterLeaveType" placeholder="全部类型" clearable style="width: 160px" @change="handleFilterChange">
            <el-option v-for="t in LEAVE_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
          <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width: 130px" @change="handleFilterChange">
            <el-option v-for="(label, value) in STATUS_LABEL" :key="value" :label="label" :value="value" />
          </el-select>
          <el-button :icon="Refresh" @click="handleFilterReset">重置</el-button>
        </div>

        <el-table :data="tableData" v-loading="loading" stripe>
          <el-table-column prop="leaveType" label="类型" width="100">
            <template #default="{ row }">{{ LEAVE_TYPE_LABEL[row.leaveType] || row.leaveType }}</template>
          </el-table-column>
          <el-table-column v-if="activeTab !== 'mine'" prop="userId" label="申请人" width="100" />
          <el-table-column prop="startDate" label="开始" width="120" />
          <el-table-column prop="endDate" label="结束" width="120" />
          <el-table-column prop="durationHours" label="时长(h)" width="100" />
          <el-table-column prop="reason" label="原因" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="STATUS_TAG_TYPE[row.status]">{{ STATUS_LABEL[row.status] || row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="submittedAt" label="提交时间" width="160" />
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <template v-if="activeTab === 'mine' && (row.status === 'DRAFT' || row.status === 'SUBMITTED')">
                <el-button size="small" type="danger" @click="cancelRow(row)">撤回</el-button>
              </template>
              <template v-if="activeTab === 'pending' && canWrite && row.status === 'SUBMITTED'">
                <el-button size="small" type="success" @click="approveRow(row)">批准</el-button>
                <el-button size="small" type="danger" @click="openReject(row)">拒绝</el-button>
              </template>
              <span v-if="row.status === 'REJECTED' && row.rejectReason" style="color: #f56c6c; font-size: 12px;">
                拒绝: {{ row.rejectReason }}
              </span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :total="pagination.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          style="margin-top: 16px; justify-content: flex-end;"
          @current-change="loadData"
          @size-change="loadData"
        />
      </div>
    </el-card>

    <!-- R1+R2+R3 防呆 新建对话框 -->
    <el-dialog v-model="createDialogVisible" title="新建请假申请" width="560px">
      <el-form :model="createForm" label-width="100px">
        <el-form-item label="假期类型" required>
          <el-select v-model="createForm.leaveType" style="width: 100%;">
            <el-option v-for="t in LEAVE_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始日期" required>
          <el-date-picker v-model="createForm.startDate" type="date" value-format="YYYY-MM-DD"
            placeholder="选择开始日期" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="结束日期" required>
          <el-date-picker v-model="createForm.endDate" type="date" value-format="YYYY-MM-DD"
            placeholder="选择结束日期" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="时长 (小时)" required>
          <el-input-number v-model="createForm.durationHours" :min="0.5" :step="0.5" :precision="2" />
        </el-form-item>
        <!-- R1 防呆: 余额预先显示 -->
        <el-alert v-if="balanceHint" :type="balanceWarn ? 'warning' : 'info'"
          :title="`本月 ${LEAVE_TYPE_LABEL[createForm.leaveType]} 余额: 已获 ${balanceHint.earnedHours}h, 已用 ${balanceHint.usedHours}h, 剩余 ${balanceHint.balanceHours}h`"
          :description="balanceWarn || ''" :closable="false" show-icon style="margin-bottom: 16px;" />
        <el-alert v-else-if="BALANCE_TRACKED.includes(createForm.leaveType) && createForm.startDate"
          type="info" title="本月暂无余额记录 (调休需先加班审批通过)" :closable="false" style="margin-bottom: 16px;" />
        <el-form-item label="原因">
          <el-input v-model="createForm.reason" type="textarea" :rows="3" placeholder="可选, 简要说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button :loading="createSubmitting" @click="handleCreate(false)">保存草稿</el-button>
        <el-button type="primary" :loading="createSubmitting" @click="handleCreate(true)">保存并提交</el-button>
      </template>
    </el-dialog>

    <!-- R3 防呆: 拒绝原因 dropdown -->
    <el-dialog v-model="rejectDialogVisible" title="拒绝申请" width="480px">
      <div v-if="rejectingRow" style="margin-bottom: 16px; color: #909399; font-size: 13px;">
        申请人 {{ rejectingRow.userId }} ·
        {{ LEAVE_TYPE_LABEL[rejectingRow.leaveType] }} ·
        {{ rejectingRow.durationHours }}h ·
        {{ rejectingRow.startDate }} ~ {{ rejectingRow.endDate }}
      </div>
      <el-form :model="rejectForm" label-width="80px">
        <el-form-item label="原因" required>
          <el-select v-model="rejectForm.reasonKey" style="width: 100%;">
            <el-option v-for="r in REJECT_REASONS" :key="r" :label="r" :value="r" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="rejectForm.reasonKey === '其他'" label="补充说明" required>
          <el-input v-model="rejectForm.otherDetail" type="textarea" :rows="3" placeholder="请填写具体原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDialogVisible = false">取消</el-button>
        <el-button type="danger" @click="confirmReject">确认拒绝</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-bar { display: flex; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }
</style>
