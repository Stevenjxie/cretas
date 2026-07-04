<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Search, Refresh, Download } from '@element-plus/icons-vue';
import type { TableRow } from '@/types/api';
import { TableFooter } from '@/components/list';
import { useListSummary } from '@/composables/useListSummary';
import { formatSummaryForAI } from '@/utils/aiSummaryContext';
import type { ListSummaryRequest } from '@/types/listSummary';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('hr'));

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });

// 默认查询最近30天
const getDefaultDateRange = (): [Date, Date] => {
  const end = new Date();
  const start = new Date();
  start.setDate(start.getDate() - 30);
  return [start, end];
};

const searchForm = ref({
  keyword: '',
  dateRange: getDefaultDateRange(),
  status: ''
});

// U-FOOTER-1
// KPI-vs-list drift fix (2026-07): searchForm MUST be declared BEFORE this
// computed (see sales/shipments/list.vue Issue #716 for the TDZ pitfall).
// Previously this was a static `{ filterConditions: {} }` — the footer
// "共" count was an ALWAYS all-time aggregate while loadData() always sends
// startDate/endDate (default last 30 days) to /timeclock/admin/history.
// That guaranteed a footer-vs-paginator mismatch on every page load (footer
// = all-time, list = last 30 days). Fix: mirror the same date range.
// NOTE: `searchForm.value.status` is intentionally NOT forwarded here —
// getAllEmployeesClockHistory() (TimeClockController#admin/history) has no
// `status` @RequestParam at all, so that filter is already a no-op on the
// list side; forwarding it to the footer would filter time_clock_records.status
// (an unrelated clock-in/out column, not the NORMAL/LATE/... attendance_status
// the dropdown implies) and make footer/list diverge in a NEW way. Separate
// pre-existing bug (status dropdown non-functional on this page), out of
// scope for this stat-card fix.
const summaryRequest = computed<ListSummaryRequest>(() => ({
  filterConditions: {},
  dateFrom: searchForm.value.dateRange ? formatDate(searchForm.value.dateRange[0]) : undefined,
  dateTo: searchForm.value.dateRange ? formatDate(searchForm.value.dateRange[1]) : undefined,
}));
const { summary: footerSummary, loading: footerLoading } = useListSummary('attendance', summaryRequest);

// 统计数据
const statistics = ref({
  totalRecords: 0,
  normalCount: 0,
  lateCount: 0,
  absentCount: 0
});

onMounted(() => {
  loadData();
  loadStatistics();
});

async function loadData() {
  if (!factoryId.value) return;

  // 日期范围是必填的
  if (!searchForm.value.dateRange) {
    searchForm.value.dateRange = getDefaultDateRange();
  }

  loading.value = true;
  try {
    const params: Record<string, string | number> = {
      page: pagination.value.page,
      size: pagination.value.size,
      startDate: formatDate(searchForm.value.dateRange[0]),
      endDate: formatDate(searchForm.value.dateRange[1])
    };
    if (searchForm.value.keyword) {
      params.keyword = searchForm.value.keyword;
    }
    if (searchForm.value.status) {
      params.status = searchForm.value.status;
    }

    // 使用管理员端点获取所有员工的考勤记录
    const response = await get(`/${factoryId.value}/timeclock/admin/history`, { params });
    if (response.success && response.data) {
      tableData.value = response.data.content || [];
      pagination.value.total = response.data.totalElements || 0;
    } else {
      // 没有数据时显示空表格
      tableData.value = [];
      pagination.value.total = 0;
    }
  } catch (error) {
    console.error('加载失败:', error);
    tableData.value = [];
    pagination.value.total = 0;
  } finally {
    loading.value = false;
  }
}

async function loadStatistics() {
  if (!factoryId.value) return;

  // 日期范围是必填的
  if (!searchForm.value.dateRange) {
    searchForm.value.dateRange = getDefaultDateRange();
  }

  try {
    const params = {
      startDate: formatDate(searchForm.value.dateRange[0]),
      endDate: formatDate(searchForm.value.dateRange[1])
    };

    // 使用管理员端点获取所有员工的考勤统计
    const response = await get(`/${factoryId.value}/timeclock/admin/statistics`, { params });
    if (response.success && response.data) {
      // 映射后端返回的字段
      statistics.value = {
        totalRecords: response.data.totalDays || response.data.totalRecords || 0,
        normalCount: response.data.normalDays || response.data.normalCount || 0,
        lateCount: response.data.lateDays || response.data.lateCount || 0,
        absentCount: response.data.absentDays || response.data.absentCount || 0
      };
    }
  } catch (error) {
    console.error('加载统计失败:', error);
    // 统计失败时使用默认值
    statistics.value = { totalRecords: 0, normalCount: 0, lateCount: 0, absentCount: 0 };
  }
}

function formatDate(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleRefresh() {
  searchForm.value = { keyword: '', dateRange: getDefaultDateRange(), status: '' };
  pagination.value.page = 1;
  loadData();
  loadStatistics();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function handleSizeChange(size: number) {
  pagination.value.size = size;
  pagination.value.page = 1;
  loadData();
}

function handleExport() {
  if (tableData.value.length === 0) {
    ElMessage.warning('暂无数据可导出');
    return;
  }
  const headers = ['员工姓名', '工号', '部门', '日期', '上班打卡', '下班打卡', '工时(h)', '状态', '备注'];
  const rows = tableData.value.map((row: TableRow) => [
    getEmployeeName(row),
    getEmployeeNumber(row),
    getDepartmentName(row),
    row.date || '-',
    formatTime(row.clockIn || row.checkInTime),
    formatTime(row.clockOut || row.checkOutTime),
    formatWorkHours(row.totalWorkMinutes || row.workDuration),
    getStatusText(getAttendanceStatus(row)),
    row.notes || ''
  ]);
  const csvContent = '\uFEFF' + [headers, ...rows].map(r => r.map(c => `"${String(c).replace(/"/g, '""')}"`).join(',')).join('\n');
  const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = `考勤记录_${new Date().toISOString().slice(0, 10)}.csv`;
  link.click();
  URL.revokeObjectURL(link.href);
  ElMessage.success('导出成功');
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    NORMAL: 'success',
    LATE: 'warning',
    EARLY_LEAVE: 'warning',
    LATE_AND_EARLY_LEAVE: 'danger',
    ABSENT: 'danger',
    LEAVE: 'info',
    // 打卡状态
    complete: 'success',
    WORKING: 'primary',
    ON_BREAK: 'warning',
    OFF_WORK: 'info',
    NOT_CLOCKED: 'danger'
  };
  return map[status] || 'info';
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    NORMAL: '正常',
    LATE: '迟到',
    EARLY_LEAVE: '早退',
    LATE_AND_EARLY_LEAVE: '迟到+早退',
    ABSENT: '缺勤',
    LEAVE: '请假',
    // 打卡状态
    complete: '已完成',
    WORKING: '工作中',
    ON_BREAK: '休息中',
    OFF_WORK: '已下班',
    NOT_CLOCKED: '未打卡'
  };
  return map[status] || status || '-';
}

function formatTime(time: string | null) {
  if (!time) return '-';
  // 处理完整日期时间格式 "2025-12-26T08:15:00"
  if (time.includes('T')) {
    const timePart = time.split('T')[1];
    return timePart ? timePart.substring(0, 5) : '-';
  }
  return time.substring(0, 5);
}

function formatWorkHours(minutes: number | null) {
  if (minutes === null || minutes === undefined) return '-';
  return (minutes / 60).toFixed(1);
}

// 获取员工姓名 (从 user 对象中提取)
function getEmployeeName(row: TableRow) {
  return row.user?.fullName || row.username || '-';
}

// 获取工号 (使用 username)
function getEmployeeNumber(row: TableRow) {
  return row.username || '-';
}

// 获取部门名称
function getDepartmentName(row: TableRow) {
  const dept = row.user?.department;
  if (!dept) return '-';
  // 部门名称映射
  const deptMap: Record<string, string> = {
    'management': '管理部',
    'processing': '生产部',
    'quality': '质量部',
    'warehouse': '仓储部',
    'procurement': '采购部',
    'sales': '销售部',
    'hr': '人事部',
    'finance': '财务部',
    'equipment': '设备部'
  };
  return deptMap[dept] || dept;
}

// 格式化日期
function formatDateDisplay(date: string | null) {
  if (!date) return '-';
  return date.substring(0, 10);
}

// 获取考勤状态 (优先使用 attendanceStatus，否则用 status)
function getAttendanceStatus(row: TableRow) {
  return row.attendanceStatus || row.status || '-';
}

// 考勤详情弹窗
const detailVisible = ref(false);
const detailRow = ref<TableRow>({});

function handleDetail(row: TableRow) {
  detailRow.value = row;
  detailVisible.value = true;
}
</script>

<template>
  <div class="page-wrapper">
    <!-- 统计卡片 -->
    <div class="statistics-row">
      <el-card class="stat-card" shadow="hover">
        <div class="stat-content">
          <div class="stat-value">{{ statistics.totalRecords }}</div>
          <div class="stat-label">总记录数</div>
        </div>
      </el-card>
      <el-card class="stat-card success" shadow="hover">
        <div class="stat-content">
          <div class="stat-value">{{ statistics.normalCount }}</div>
          <div class="stat-label">正常出勤</div>
        </div>
      </el-card>
      <el-card class="stat-card warning" shadow="hover">
        <div class="stat-content">
          <div class="stat-value">{{ statistics.lateCount }}</div>
          <div class="stat-label">迟到/早退</div>
        </div>
      </el-card>
      <el-card class="stat-card danger" shadow="hover">
        <div class="stat-content">
          <div class="stat-value">{{ statistics.absentCount }}</div>
          <div class="stat-label">缺勤</div>
        </div>
      </el-card>
    </div>

    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">考勤记录</span>
            <span class="data-count">共 {{ pagination.total }} 条记录</span>
          </div>
          <div class="header-right">
            <el-button :icon="Download" @click="handleExport">导出</el-button>
          </div>
        </div>
      </template>

      <div class="search-bar">
        <el-input
          v-model="searchForm.keyword"
          placeholder="搜索员工姓名/工号"
          :prefix-icon="Search"
          clearable
          style="width: 220px"
          @keyup.enter="handleSearch"
        />
        <el-date-picker
          v-model="searchForm.dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          style="width: 280px"
        />
        <el-select v-model="searchForm.status" placeholder="考勤状态" clearable style="width: 130px">
          <el-option label="正常" value="NORMAL" />
          <el-option label="迟到" value="LATE" />
          <el-option label="早退" value="EARLY_LEAVE" />
          <el-option label="缺勤" value="ABSENT" />
          <el-option label="请假" value="LEAVE" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
      </div>

      <el-table :data="tableData" v-loading="loading" empty-text="暂无数据" stripe border style="width: 100%">
        <el-table-column label="员工姓名" width="120">
          <template #default="{ row }">
            {{ getEmployeeName(row) }}
          </template>
        </el-table-column>
        <el-table-column label="工号" width="120">
          <template #default="{ row }">
            {{ getEmployeeNumber(row) }}
          </template>
        </el-table-column>
        <el-table-column label="部门" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            {{ getDepartmentName(row) }}
          </template>
        </el-table-column>
        <el-table-column label="日期" width="120">
          <template #default="{ row }">
            {{ formatDateDisplay(row.clockDate) }}
          </template>
        </el-table-column>
        <el-table-column label="上班打卡" width="100" align="center">
          <template #default="{ row }">
            {{ formatTime(row.clockInTime) }}
          </template>
        </el-table-column>
        <el-table-column label="下班打卡" width="100" align="center">
          <template #default="{ row }">
            {{ formatTime(row.clockOutTime) }}
          </template>
        </el-table-column>
        <el-table-column label="工时(h)" width="90" align="center">
          <template #default="{ row }">
            {{ formatWorkHours(row.workDurationMinutes) }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusType(getAttendanceStatus(row))" size="small">
              {{ getStatusText(getAttendanceStatus(row)) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.notes || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <TableFooter
        :stats="footerSummary?.stats ?? []"
        :loading="footerLoading"
        :show-export="false"
        @ai-analyze="() => ElMessage.info({ message: `AI 分析 (待接 SmartBI): 分析当前考勤${formatSummaryForAI(footerSummary)}`, duration: 8000, showClose: true })"
      />

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <el-dialog v-model="detailVisible" title="考勤详情" width="500px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="员工姓名">{{ getEmployeeName(detailRow) }}</el-descriptions-item>
        <el-descriptions-item label="工号">{{ getEmployeeNumber(detailRow) }}</el-descriptions-item>
        <el-descriptions-item label="部门">{{ getDepartmentName(detailRow) }}</el-descriptions-item>
        <el-descriptions-item label="日期">{{ formatDateDisplay(detailRow.clockDate) }}</el-descriptions-item>
        <el-descriptions-item label="上班打卡">{{ formatTime(detailRow.clockInTime) }}</el-descriptions-item>
        <el-descriptions-item label="下班打卡">{{ formatTime(detailRow.clockOutTime) }}</el-descriptions-item>
        <el-descriptions-item label="工时">{{ formatWorkHours(detailRow.workDurationMinutes) }} 小时</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="getStatusType(getAttendanceStatus(detailRow))" size="small">
            {{ getStatusText(getAttendanceStatus(detailRow)) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="备注">{{ detailRow.notes || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.statistics-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.stat-card {
  .stat-content {
    text-align: center;
    padding: 8px 0;
  }

  .stat-value {
    font-size: 28px;
    font-weight: 600;
    color: #409eff;
  }

  .stat-label {
    font-size: 14px;
    color: #909399;
    margin-top: 8px;
  }

  &.success .stat-value {
    color: #67c23a;
  }

  &.warning .stat-value {
    color: #e6a23c;
  }

  &.danger .stat-value {
    color: #f56c6c;
  }
}

.page-card {
  flex: 1;
  display: flex;
  flex-direction: column;

  :deep(.el-card__header) {
    padding: 16px 20px;
    border-bottom: 1px solid var(--border-color-lighter, #ebeef5);
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
      color: var(--text-color-primary, #303133);
    }

    .data-count {
      font-size: 13px;
      color: var(--text-color-secondary, #909399);
    }
  }
}

.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.el-table {
  flex: 1;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid var(--border-color-lighter, #ebeef5);
  margin-top: 16px;
}
</style>
