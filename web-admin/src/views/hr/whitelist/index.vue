<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put, del } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Search, Refresh, Edit, Delete } from '@element-plus/icons-vue';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('hr'));

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const searchKeyword = ref('');

// 对话框
const dialogVisible = ref(false);
const dialogLoading = ref(false);
const isEdit = ref(false);
const whitelistForm = ref({
  id: '',
  phoneNumber: '',
  name: '',
  role: '',
  departmentId: '',
  expirationDate: '',
  notes: ''
});
const departments = ref<TableRow[]>([]);

// 统计数据
const statistics = ref({
  total: 0,
  opened: 0,
  expired: 0
});

const roleOptions = [
  { value: 'factory_super_admin', label: '工厂总监' },
  { value: 'hr_admin', label: 'HR管理员' },
  { value: 'dispatcher', label: '调度员' },
  { value: 'quality_manager', label: '质量经理' },
  { value: 'quality_inspector', label: '质检员' },
  { value: 'workshop_supervisor', label: '车间主任' },
  { value: 'yield_operator', label: '报工员' },
  { value: 'operator', label: '操作员' },
  { value: 'warehouse_manager', label: '仓储主管' },
  { value: 'warehouse_worker', label: '仓库员' },
  { value: 'viewer', label: '查看者' }
];

onMounted(() => {
  loadData();
  loadDepartments();
  loadStatistics();
});

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/whitelist`, {
      params: {
        page: pagination.value.page,
        size: pagination.value.size,
        keyword: searchKeyword.value || undefined
      }
    });
    if (response.success && response.data) {
      tableData.value = response.data.content || [];
      pagination.value.total = response.data.totalElements || 0;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载数据失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

async function loadDepartments() {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/departments`);
    if (response.success && response.data) {
      departments.value = response.data.content || response.data || [];
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载部门列表失败');
    }
  } catch (error) {
    console.error('加载部门列表失败:', error);
    ElMessage.error('加载部门列表失败');
  }
}

async function loadStatistics() {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/whitelist/stats`);
    if (response.success && response.data) {
      statistics.value = {
        total: response.data.totalCount || 0,
        opened: response.data.activeUsersCount || 0,
        expired: response.data.expiredCount || 0
      };
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载统计失败');
    }
  } catch (error) {
    console.error('加载统计失败:', error);
    ElMessage.error('加载统计失败');
  }
}

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleRefresh() {
  searchKeyword.value = '';
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

function handleCreate() {
  isEdit.value = false;
  whitelistForm.value = {
    id: '',
    phoneNumber: '',
    name: '',
    role: '',
    departmentId: '',
    expirationDate: '',
    notes: ''
  };
  dialogVisible.value = true;
}

function handleEdit(row: TableRow) {
  isEdit.value = true;
  whitelistForm.value = {
    id: row.id,
    phoneNumber: row.phoneNumber,
    name: row.name,
    role: row.role,
    departmentId: row.department || '',
    expirationDate: row.expiresAt ? row.expiresAt.substring(0, 10) : '',
    notes: row.notes || ''
  };
  dialogVisible.value = true;
}

async function submitForm() {
  if (!whitelistForm.value.phoneNumber || !whitelistForm.value.name || !whitelistForm.value.role) {
    ElMessage.warning('请填写完整信息');
    return;
  }

  // 手机号格式验证
  if (!/^1[3-9]\d{9}$/.test(whitelistForm.value.phoneNumber)) {
    ElMessage.warning('请输入正确的手机号');
    return;
  }

  dialogLoading.value = true;
  try {
    let response;
    if (isEdit.value) {
      response = await put(`/${factoryId.value}/whitelist/${whitelistForm.value.id}`, {
        name: whitelistForm.value.name,
        role: whitelistForm.value.role,
        department: whitelistForm.value.departmentId || undefined,
        expiresAt: whitelistForm.value.expirationDate
          ? `${whitelistForm.value.expirationDate} 23:59:59`
          : undefined,
        notes: whitelistForm.value.notes
      });
    } else {
      response = await post(`/${factoryId.value}/whitelist`, whitelistForm.value);
    }
    const batchSucceeded = isEdit.value || (response.data?.successCount ?? 0) > 0;
    if (response.success && batchSucceeded) {
      ElMessage.success(isEdit.value ? '邀请更新成功' : '账号邀请已创建');
      dialogVisible.value = false;
      loadData();
      loadStatistics();
    } else {
      const failure = response.data?.failedEntries?.[0]?.reason;
      ElMessage.error(failure || response.message || '操作失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast; debug-only log.
    console.error('[提交失败]', error);
  } finally {
    dialogLoading.value = false;
  }
}

async function handleDelete(row: TableRow) {
  try {
    await ElMessageBox.confirm('确定删除此白名单记录?', '提示', { type: 'warning' });
    const response = await del(`/${factoryId.value}/whitelist/${row.id}`);
    if (response.success) {
      ElMessage.success('删除成功');
      loadData();
      loadStatistics();
    } else {
      ElMessage.error(response.message || '删除失败');
    }
  } catch (error) {
    // Interceptor shows specific toast; dedupe fallback
    if (error !== 'cancel') console.error('[失败]', error);
  }
}

function getStatusType(row: TableRow) {
  if (row.accountCreated && row.accountActive) return 'success';
  if (row.accountCreated) return 'warning';
  if (row.status === 'EXPIRED') return 'danger';
  if (row.status === 'DISABLED') return 'info';
  return 'primary';
}

function getStatusText(row: TableRow) {
  if (row.accountCreated && row.accountActive) return '已开户';
  if (row.accountCreated) return '待激活';
  if (row.status === 'EXPIRED') return '已过期';
  if (row.status === 'DISABLED') return '已禁用';
  return '邀请待领取';
}

function getRoleText(role: string) {
  return roleOptions.find(option => option.value === role)?.label || role || '历史白名单';
}
</script>

<template>
  <div class="page-wrapper">
    <!-- 统计卡片 -->
    <div class="statistics-row">
      <el-card class="stat-card" shadow="hover">
        <div class="stat-content">
          <div class="stat-value">{{ statistics.total }}</div>
          <div class="stat-label">总数</div>
        </div>
      </el-card>
      <el-card class="stat-card success" shadow="hover">
        <div class="stat-content">
          <div class="stat-value">{{ statistics.opened }}</div>
          <div class="stat-label">已开户</div>
        </div>
      </el-card>
      <el-card class="stat-card danger" shadow="hover">
        <div class="stat-content">
          <div class="stat-value">{{ statistics.expired }}</div>
          <div class="stat-label">已过期</div>
        </div>
      </el-card>
    </div>

    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">账号邀请（白名单）</span>
            <span class="data-count">共 {{ pagination.total }} 条记录</span>
          </div>
          <div class="header-right">
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleCreate">
              创建账号邀请
            </el-button>
          </div>
        </div>
      </template>

      <div class="invitation-guide">
        <div class="guide-step"><strong>1</strong><span>管理员填写手机号、姓名和角色</span></div>
        <div class="guide-arrow">→</div>
        <div class="guide-step"><strong>2</strong><span>员工在手机端用该手机号注册并设置密码</span></div>
        <div class="guide-arrow">→</div>
        <div class="guide-step"><strong>3</strong><span>账号自动归属当前工厂并获得预设角色</span></div>
      </div>

      <div class="search-bar">
        <el-input
          v-model="searchKeyword"
          placeholder="搜索手机号/姓名"
          :prefix-icon="Search"
          clearable
          style="width: 280px"
          @keyup.enter="handleSearch"
        />
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
      </div>

      <el-table :data="tableData" v-loading="loading" empty-text="暂无数据" stripe border style="width: 100%">
        <el-table-column prop="phoneNumber" label="手机号" width="140" />
        <el-table-column prop="name" label="姓名" width="120" />
        <el-table-column prop="role" label="角色" width="150">
          <template #default="{ row }">
            {{ getRoleText(row.role) }}
          </template>
        </el-table-column>
        <el-table-column prop="department" label="部门" min-width="150" show-overflow-tooltip />
        <el-table-column label="过期时间" width="120">
          <template #default="{ row }">
            {{ row.expiresAt ? row.expiresAt.substring(0, 10) : '长期有效' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row)" size="small">
              {{ getStatusText(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="添加时间" width="180">
          <template #default="{ row }">
            {{ row.createdAt ? row.createdAt.replace('T', ' ').substring(0, 19) : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right" align="center">
          <template #default="{ row }">
            <el-button
              v-if="canWrite && !row.accountCreated"
              type="primary"
              link
              size="small"
              :icon="Edit"
              @click="handleEdit(row)"
            >编辑</el-button>
            <el-button
              v-if="canWrite && !row.accountCreated"
              type="danger"
              link
              size="small"
              :icon="Delete"
              @click="handleDelete(row)"
            >删除</el-button>
          </template>
        </el-table-column>
      </el-table>

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

    <!-- 编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑账号邀请' : '创建账号邀请'" width="520px" :close-on-click-modal="false">
      <el-alert
        v-if="!isEdit"
        class="dialog-alert"
        type="info"
        :closable="false"
        title="手机号将作为员工的登录账号；工厂和角色由本邀请锁定，员工注册时不能自行修改。"
      />
      <el-form :model="whitelistForm" label-width="100px">
        <el-form-item label="手机号" required>
          <el-input
            v-model="whitelistForm.phoneNumber"
            placeholder="请输入手机号"
            :disabled="isEdit"
            maxlength="11"
          />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="whitelistForm.name" placeholder="请输入姓名" />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="whitelistForm.role" placeholder="选择角色" style="width: 100%">
            <el-option
              v-for="option in roleOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="部门">
          <el-select v-model="whitelistForm.departmentId" placeholder="选择部门" clearable style="width: 100%">
            <el-option
              v-for="item in departments"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="过期时间">
          <el-date-picker
            v-model="whitelistForm.expirationDate"
            type="date"
            placeholder="选择日期"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="whitelistForm.notes" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="dialogLoading" @click="submitForm">
          {{ isEdit ? '保存邀请' : '创建邀请' }}
        </el-button>
      </template>
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
  grid-template-columns: repeat(3, 1fr);
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

.invitation-guide {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  margin-bottom: 16px;
  border: 1px solid #b3e0d2;
  border-radius: 10px;
  background: #f0faf7;
  color: #334155;

  .guide-step {
    display: flex;
    align-items: center;
    gap: 8px;
    flex: 1;
    min-width: 0;
    font-size: 13px;
    line-height: 1.45;

    strong {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      flex: 0 0 24px;
      width: 24px;
      height: 24px;
      border-radius: 50%;
      background: #0f8f72;
      color: #fff;
    }
  }

  .guide-arrow {
    color: #0f8f72;
    font-weight: 700;
  }
}

.dialog-alert {
  margin-bottom: 18px;
}

.el-table {
  flex: 1;
}

@media (max-width: 900px) {
  .invitation-guide {
    align-items: stretch;
    flex-direction: column;

    .guide-arrow {
      display: none;
    }
  }
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid var(--border-color-lighter, #ebeef5);
  margin-top: 16px;
}
</style>
