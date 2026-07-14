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

type OvtRow = {
  id: string;
  userId: number;
  overtimeType: 'WEEKDAY' | 'WEEKEND' | 'HOLIDAY';
  startTime: string;
  endTime: string;
  hours: number;
  compensationType: 'CASH' | 'COMPTIME';
  reason?: string;
  status: 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'CANCELLED';
  submittedAt?: string;
  rejectReason?: string;
};

const OVT_TYPES = [
  { value: 'WEEKDAY', label: '工作日加班 (1.5x 工资 / 1:1 调休)' },
  { value: 'WEEKEND', label: '周末加班 (2x 工资 / 1:2 调休)' },
  { value: 'HOLIDAY', label: '节假日加班 (3x 工资, 不可转调休)' },
];
const OVT_TYPE_LABEL: Record<string, string> = {
  WEEKDAY: '工作日加班', WEEKEND: '周末加班', HOLIDAY: '节假日加班',
};
const COMP_LABEL: Record<string, string> = { CASH: '加班工资', COMPTIME: '调休' };
const STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿', SUBMITTED: '待审批', APPROVED: '已批准',
  REJECTED: '已拒绝', CANCELLED: '已撤回',
};
const STATUS_TAG_TYPE: Record<string, '' | 'success' | 'warning' | 'danger' | 'info'> = {
  DRAFT: 'info', SUBMITTED: 'warning', APPROVED: 'success',
  REJECTED: 'danger', CANCELLED: 'info',
};

const REJECT_REASONS = ['不符合加班条件', '已有调休余额', '项目无加班需求', '时段重叠', '其他'];

const activeTab = ref<'mine' | 'pending' | 'all' | 'summary'>('mine');
const loading = ref(false);
const tableData = ref<OvtRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const summaryData = ref<Record<string, number>>({});
const summaryYearMonth = ref<string>(currentYearMonth());

// 筛选 (仅"全部"tab 支持, 后端 /hr/overtime-requests 已加 overtimeType/compensationType/status 可选参数)
const filterOvertimeType = ref('');
const filterCompensationType = ref('');
const filterStatus = ref('');
function handleFilterChange() {
  pagination.value.page = 1;
  loadData();
}
function handleFilterReset() {
  filterOvertimeType.value = '';
  filterCompensationType.value = '';
  filterStatus.value = '';
  pagination.value.page = 1;
  loadData();
}

// Create dialog
const createDialogVisible = ref(false);
const createSubmitting = ref(false);
const createForm = ref({
  overtimeType: 'WEEKDAY' as 'WEEKDAY' | 'WEEKEND' | 'HOLIDAY',
  startTime: '',
  endTime: '',
  hours: 2,
  compensationType: 'CASH' as 'CASH' | 'COMPTIME',
  reason: '',
});
// R1 monthly accumulation hint
const monthlyOvtHours = ref<number | null>(null);

// Reject dialog
const rejectDialogVisible = ref(false);
const rejectingRow = ref<OvtRow | null>(null);
const rejectForm = ref({ reasonKey: '不符合加班条件', otherDetail: '' });

function currentYearMonth(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

onMounted(() => { loadData(); });
watch(activeTab, () => {
  pagination.value.page = 1;
  if (activeTab.value === 'summary') loadSummary(); else loadData();
});
watch(() => createForm.value.startTime, () => {
  if (createDialogVisible.value) loadMonthlyHint();
});

// Validate HOLIDAY + COMPTIME combo (R3 联动)
const compOptions = computed(() => {
  if (createForm.value.overtimeType === 'HOLIDAY') {
    return [{ value: 'CASH', label: '加班工资 (节假日强制)' }];
  }
  return [
    { value: 'CASH', label: '转加班工资' },
    { value: 'COMPTIME', label: '转调休余额' },
  ];
});

watch(() => createForm.value.overtimeType, (v) => {
  if (v === 'HOLIDAY') createForm.value.compensationType = 'CASH';
});

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const endpoint = activeTab.value === 'mine'
      ? `/${factoryId.value}/hr/overtime-requests/mine`
      : activeTab.value === 'pending'
        ? `/${factoryId.value}/hr/overtime-requests/pending`
        : `/${factoryId.value}/hr/overtime-requests`;
    const res = await get(endpoint, {
      params: {
        page: pagination.value.page - 1,
        size: pagination.value.size,
        ...(activeTab.value === 'all'
          ? {
              overtimeType: filterOvertimeType.value || undefined,
              compensationType: filterCompensationType.value || undefined,
              status: filterStatus.value || undefined,
            }
          : {})
      }
    });
    if (res.success && res.data) {
      const data = res.data as { content?: OvtRow[]; totalElements?: number };
      tableData.value = data.content || [];
      pagination.value.total = data.totalElements || 0;
    } else {
      tableData.value = [];
      pagination.value.total = 0;
    }
  } catch (e) {
    console.error('加载加班申请失败:', e);
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
    const res = await get(`/${factoryId.value}/hr/overtime-requests/summary`, {
      params: { yearMonth: summaryYearMonth.value }
    });
    if (res.success && res.data) summaryData.value = res.data as Record<string, number>;
  } catch (e) {
    console.error('加载月汇总失败:', e);
  } finally {
    loading.value = false;
  }
}

// R1: 显示本月已批准加班小时数
async function loadMonthlyHint() {
  monthlyOvtHours.value = null;
  if (!factoryId.value || !createForm.value.startTime) return;
  const ym = createForm.value.startTime.slice(0, 7);
  try {
    const res = await get(`/${factoryId.value}/hr/overtime-requests/summary`, {
      params: { yearMonth: ym }
    });
    if (res.success && res.data) {
      const map = res.data as Record<string, number>;
      monthlyOvtHours.value = (map.CASH || 0) + (map.COMPTIME || 0);
    }
  } catch (e) {
    console.error('加载月加班 hint 失败:', e);
  }
}

function openCreate() {
  createForm.value = {
    overtimeType: 'WEEKDAY', startTime: '', endTime: '',
    hours: 2, compensationType: 'CASH', reason: '',
  };
  monthlyOvtHours.value = null;
  createDialogVisible.value = true;
}

async function handleCreate(submitNow: boolean) {
  if (!createForm.value.startTime || !createForm.value.endTime) {
    ElMessage.error('请选择开始/结束时间');
    return;
  }
  if (createForm.value.hours <= 0) {
    ElMessage.error('时长必须 > 0');
    return;
  }
  createSubmitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/hr/overtime-requests`, createForm.value);
    if (res.success && res.data) {
      const id = (res.data as { id: string }).id;
      if (submitNow) {
        await post(`/${factoryId.value}/hr/overtime-requests/${id}/submit`);
        ElMessage.success('已提交审批');
      } else {
        ElMessage.success('已保存草稿');
      }
      createDialogVisible.value = false;
      await loadData();
    }
  } catch (e) {
    console.error('创建失败:', e);
  } finally {
    createSubmitting.value = false;
  }
}

// R2 防呆: approve dialog 显示申请人+类型+时长+补偿
async function approveRow(row: OvtRow) {
  try {
    await ElMessageBox.confirm(
      `批准 [申请人 ${row.userId}] ${OVT_TYPE_LABEL[row.overtimeType]} ${row.hours}h, 转${COMP_LABEL[row.compensationType]}?`,
      '审批确认',
      { confirmButtonText: '批准', cancelButtonText: '取消', type: 'warning' }
    );
    const res = await post(`/${factoryId.value}/hr/overtime-requests/${row.id}/approve`);
    if (res.success) { ElMessage.success('已批准'); await loadData(); }
  } catch (e) { if (e !== 'cancel') console.error(e); }
}

function openReject(row: OvtRow) {
  rejectingRow.value = row;
  rejectForm.value = { reasonKey: '不符合加班条件', otherDetail: '' };
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
      `/${factoryId.value}/hr/overtime-requests/${rejectingRow.value.id}/reject`,
      { reason: finalReason }
    );
    if (res.success) {
      ElMessage.success('已拒绝');
      rejectDialogVisible.value = false;
      await loadData();
    }
  } catch (e) { console.error(e); }
}

async function cancelRow(row: OvtRow) {
  try {
    await ElMessageBox.confirm(
      `撤回 ${OVT_TYPE_LABEL[row.overtimeType]} ${row.hours}h?`, '撤回确认'
    );
    const res = await post(`/${factoryId.value}/hr/overtime-requests/${row.id}/cancel`);
    if (res.success) { ElMessage.success('已撤回'); await loadData(); }
  } catch (e) { if (e !== 'cancel') console.error(e); }
}
</script>

<template>
  <div class="overtime-requests-page" style="padding: 16px;">
    <el-card>
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
        <h2 style="margin: 0;">加班/调休申请</h2>
        <div>
          <el-button type="primary" :icon="Plus" @click="openCreate">新建加班</el-button>
          <el-button :icon="Refresh" @click="activeTab === 'summary' ? loadSummary() : loadData()">刷新</el-button>
        </div>
      </div>

      <el-tabs v-model="activeTab">
        <el-tab-pane label="我的申请" name="mine" />
        <el-tab-pane v-if="canWrite" label="待审批" name="pending" />
        <el-tab-pane v-if="canWrite" label="全部" name="all" />
        <el-tab-pane v-if="canWrite" label="月汇总" name="summary" />
      </el-tabs>

      <div v-if="activeTab === 'summary'">
        <el-form inline>
          <el-form-item label="月份">
            <el-input v-model="summaryYearMonth" placeholder="YYYY-MM" style="width: 140px;" @change="loadSummary" />
          </el-form-item>
        </el-form>
        <el-table :data="Object.entries(summaryData).map(([k, v]) => ({ compType: k, hours: v }))" v-loading="loading">
          <el-table-column prop="compType" label="补偿方式" width="200">
            <template #default="{ row }">{{ COMP_LABEL[row.compType] || row.compType }}</template>
          </el-table-column>
          <el-table-column prop="hours" label="总小时数" />
        </el-table>
      </div>

      <div v-else>
        <!-- 筛选 (仅"全部"tab, 类型/补偿方式/状态均为固定业务枚举) -->
        <div v-if="activeTab === 'all'" class="filter-bar">
          <el-select v-model="filterOvertimeType" placeholder="全部类型" clearable style="width: 160px" @change="handleFilterChange">
            <el-option v-for="t in OVT_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
          <el-select v-model="filterCompensationType" placeholder="全部补偿方式" clearable style="width: 150px" @change="handleFilterChange">
            <el-option v-for="(label, value) in COMP_LABEL" :key="value" :label="label" :value="value" />
          </el-select>
          <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width: 130px" @change="handleFilterChange">
            <el-option v-for="(label, value) in STATUS_LABEL" :key="value" :label="label" :value="value" />
          </el-select>
          <el-button :icon="Refresh" @click="handleFilterReset">重置</el-button>
        </div>

        <el-table :data="tableData" v-loading="loading" stripe>
          <el-table-column prop="overtimeType" label="加班类型" width="120">
            <template #default="{ row }">{{ OVT_TYPE_LABEL[row.overtimeType] }}</template>
          </el-table-column>
          <el-table-column v-if="activeTab !== 'mine'" prop="userId" label="申请人" width="100" />
          <el-table-column prop="startTime" label="开始" width="160" />
          <el-table-column prop="endTime" label="结束" width="160" />
          <el-table-column prop="hours" label="时长(h)" width="100" />
          <el-table-column prop="compensationType" label="补偿" width="100">
            <template #default="{ row }">{{ COMP_LABEL[row.compensationType] }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="STATUS_TAG_TYPE[row.status]">{{ STATUS_LABEL[row.status] }}</el-tag>
            </template>
          </el-table-column>
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

    <!-- R1+R3 防呆 新建对话框 -->
    <el-dialog v-model="createDialogVisible" title="新建加班申请" width="560px">
      <el-form :model="createForm" label-width="120px">
        <el-form-item label="加班类型" required>
          <el-select v-model="createForm.overtimeType" style="width: 100%;">
            <el-option v-for="t in OVT_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始时间" required>
          <el-date-picker v-model="createForm.startTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="选择开始时间" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="结束时间" required>
          <el-date-picker v-model="createForm.endTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="选择结束时间" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="时长 (小时)" required>
          <el-input-number v-model="createForm.hours" :min="0.5" :step="0.5" :precision="2" />
        </el-form-item>
        <el-form-item label="补偿方式" required>
          <el-select v-model="createForm.compensationType" style="width: 100%;">
            <el-option v-for="o in compOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <!-- R1 防呆: 本月加班 hint -->
        <el-alert v-if="monthlyOvtHours !== null"
          type="info"
          :title="`本月已批准加班 ${monthlyOvtHours}h`"
          :closable="false" show-icon style="margin-bottom: 16px;" />
        <el-form-item label="原因">
          <el-input v-model="createForm.reason" type="textarea" :rows="3" placeholder="简要说明加班缘由" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button :loading="createSubmitting" @click="handleCreate(false)">保存草稿</el-button>
        <el-button type="primary" :loading="createSubmitting" @click="handleCreate(true)">保存并提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rejectDialogVisible" title="拒绝申请" width="480px">
      <div v-if="rejectingRow" style="margin-bottom: 16px; color: #909399; font-size: 13px;">
        申请人 {{ rejectingRow.userId }} ·
        {{ OVT_TYPE_LABEL[rejectingRow.overtimeType] }} ·
        {{ rejectingRow.hours }}h ·
        转{{ COMP_LABEL[rejectingRow.compensationType] }}
      </div>
      <el-form :model="rejectForm" label-width="80px">
        <el-form-item label="原因" required>
          <el-select v-model="rejectForm.reasonKey" style="width: 100%;">
            <el-option v-for="r in REJECT_REASONS" :key="r" :label="r" :value="r" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="rejectForm.reasonKey === '其他'" label="补充说明" required>
          <el-input v-model="rejectForm.otherDetail" type="textarea" :rows="3" />
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
