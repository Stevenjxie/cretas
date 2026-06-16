<script setup lang="ts">
import { ref, computed, reactive, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put } from '@/api/request';
import { createUser, activateUser, deactivateUser } from '@/api/factory';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Search, Refresh, View, Edit, Key } from '@element-plus/icons-vue';
import type { FormInstance, FormRules } from 'element-plus';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('system'));

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const searchForm = ref({ username: '', roleCode: '' });

// Create user dialog state
const createDialogVisible = ref(false);
const createLoading = ref(false);
const createFormRef = ref<FormInstance>();
const createForm = reactive({
  username: '',
  password: '',
  email: '',
  fullName: '',
  phone: '',
  roleCode: ''
});

const createRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]{3,20}$/, message: '只能包含字母、数字和下划线，长度3-20', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '长度在 6 到 20 个字符', trigger: 'blur' }
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }
  ],
  fullName: [
    { required: true, message: '请输入姓名', trigger: 'blur' }
  ],
  roleCode: [
    { required: true, message: '请选择角色', trigger: 'change' }
  ]
};

onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/users`, {
      params: {
        page: pagination.value.page,
        size: pagination.value.size,
        ...searchForm.value
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

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleReset() {
  searchForm.value = { username: '', roleCode: '' };
  handleSearch();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

// Must match FactoryUserRole enum values exactly
// Apr 20 Bug BR-14 fix: 补上 Phase 1 新增的 platform_admin + restaurant_manager,
// 否则用户编辑时 roleOptions 不完整, 无法切到这两个角色.
const roleOptions = [
  { value: 'platform_admin', label: '平台管理员' },
  { value: 'factory_super_admin', label: '工厂总监' },
  { value: 'hr_admin', label: 'HR管理员' },
  { value: 'procurement_manager', label: '采购主管' },
  { value: 'sales_manager', label: '销售主管' },
  { value: 'dispatcher', label: '调度员' },
  { value: 'production_manager', label: '生产主管' },
  { value: 'warehouse_manager', label: '仓储主管' },
  { value: 'equipment_admin', label: '设备管理员' },
  { value: 'quality_manager', label: '质量经理' },
  { value: 'finance_manager', label: '财务主管' },
  { value: 'restaurant_manager', label: '餐饮经理' },
  { value: 'workshop_supervisor', label: '车间主任' },
  { value: 'team_leader', label: '大组长' },
  { value: 'group_leader', label: '小组长' },
  { value: 'quality_inspector', label: '质检员' },
  { value: 'operator', label: '操作员' },
  { value: 'warehouse_worker', label: '仓库员' },
  { value: 'permission_admin', label: '权限管理员' },
  { value: 'department_admin', label: '部门管理员' },
  { value: 'viewer', label: '查看者' },
  { value: 'unactivated', label: '未激活' }
];

function getRoleText(role: string) {
  const option = roleOptions.find(o => o.value === role);
  return option?.label || role;
}

function openCreateDialog() {
  createForm.username = '';
  createForm.password = '';
  createForm.email = '';
  createForm.fullName = '';
  createForm.phone = '';
  createForm.roleCode = '';
  createDialogVisible.value = true;
}

async function handleCreateUser() {
  if (!factoryId.value) return;
  const valid = await createFormRef.value?.validate().catch(() => false);
  if (!valid) return;

  createLoading.value = true;
  try {
    const response = await createUser(factoryId.value, {
      username: createForm.username,
      password: createForm.password,
      email: createForm.email,
      fullName: createForm.fullName || undefined,
      phone: createForm.phone || undefined,
      roleCode: createForm.roleCode
    });
    if (response.success) {
      ElMessage.success('用户创建成功');
      createDialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(response.message || '创建用户失败');
    }
  } catch (error) {
    ElMessage.error('创建用户失败');
  } finally {
    createLoading.value = false;
  }
}

// View/Edit/Reset handlers
const viewDialogVisible = ref(false);
const viewUser = ref<TableRow>({});

function handleViewUser(row: TableRow) {
  viewUser.value = row;
  viewDialogVisible.value = true;
}

const editDialogVisible = ref(false);
const editLoading = ref(false);
const editFormRef = ref<FormInstance>();
const editForm = reactive({
  id: 0,
  username: '',
  email: '',
  fullName: '',
  phone: '',
  roleCode: ''
});

function handleEditUser(row: TableRow) {
  editForm.id = row.id;
  editForm.username = row.username;
  editForm.email = row.email || '';
  editForm.fullName = row.fullName || '';
  editForm.phone = row.phone || '';
  editForm.roleCode = row.roleCode || '';
  editDialogVisible.value = true;
}

async function handleSaveEdit() {
  if (!factoryId.value) return;
  const valid = await editFormRef.value?.validate().catch(() => false);
  if (!valid) return;

  editLoading.value = true;
  try {
    const response = await put(`/${factoryId.value}/users/${editForm.id}`, {
      email: editForm.email,
      fullName: editForm.fullName,
      phone: editForm.phone,
      roleCode: editForm.roleCode
    });
    if (response.success) {
      ElMessage.success('用户信息已更新');
      editDialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(response.message || '更新失败');
    }
  } catch (error) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', error);
  } finally {
    editLoading.value = false;
  }
}

async function handleResetPassword(row: TableRow) {
  if (!factoryId.value) return;
  try {
    const { value: newPassword } = await ElMessageBox.prompt(
      `确定要重置用户 "${row.username}" 的密码吗？`,
      '重置密码',
      {
        inputPattern: /^.{6,20}$/,
        inputErrorMessage: '密码长度为 6-20 个字符',
        inputType: 'password',
        inputPlaceholder: '请输入新密码',
        confirmButtonText: '确认重置',
        cancelButtonText: '取消',
        type: 'warning'
      }
    );
    const response = await post('/auth/reset-password', null, {
      params: { factoryId: factoryId.value, username: row.username, newPassword }
    });
    if (response.success) {
      ElMessage.success('密码已重置');
    } else {
      ElMessage.error(response.message || '重置失败');
    }
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('重置密码失败');
    }
  }
}

async function handleToggleActive(row: TableRow) {
  if (!factoryId.value) return;
  try {
    await ElMessageBox.confirm(
      row.isActive ? `确定停用用户「${row.username}」？停用后该用户将无法登录。` : `确定激活用户「${row.username}」？`,
      '确认操作',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    );
    const response = row.isActive
      ? await deactivateUser(factoryId.value, row.id)
      : await activateUser(factoryId.value, row.id);
    if (response.success) {
      ElMessage.success(row.isActive ? '已停用' : '已激活');
      loadData();
    } else {
      ElMessage.error(response.message || '操作失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError (request.ts).
    // Retained catch to prevent uncaught; log for debug.
    if (error !== 'cancel') console.error('[提交失败]', error);
  }
}
</script>

<template>
  <div class="page-container">
    <el-card class="search-card">
      <el-form :model="searchForm" inline>
        <el-form-item label="用户名">
          <el-input v-model="searchForm.username" placeholder="请输入用户名" clearable @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="searchForm.roleCode" placeholder="全部角色" clearable>
            <el-option
              v-for="option in roleOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-header">
          <span>账号管理</span>
          <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreateDialog">添加账号</el-button>
        </div>
      </template>

      <el-table :data="tableData" v-loading="loading" empty-text="暂无数据" stripe>
        <el-table-column prop="username" label="用户名" width="120" />
        <el-table-column prop="fullName" label="姓名" />
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column prop="roleCode" label="角色">
          <template #default="{ row }">
            <el-tag>{{ getRoleText(row.roleCode) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="departmentDisplayName" label="部门" />
        <el-table-column prop="isActive" label="状态" width="100">
          <template #default="{ row }">
            <el-switch
              v-if="canWrite"
              :model-value="row.isActive"
              active-text="启用"
              inactive-text="禁用"
              @change="handleToggleActive(row)"
            />
            <el-tag v-else :type="row.isActive ? 'success' : 'danger'" size="small">
              {{ row.isActive ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">
            {{ row.createdAt ? row.createdAt.replace('T', ' ').substring(0, 19) : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link :icon="View" @click="handleViewUser(row)">查看</el-button>
            <el-button v-if="canWrite" type="primary" link :icon="Edit" @click="handleEditUser(row)">编辑</el-button>
            <el-button v-if="canWrite" type="danger" link :icon="Key" @click="handleResetPassword(row)">重置密码</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="pagination.page"
        :page-size="pagination.size"
        :total="pagination.total"
        layout="total, prev, pager, next"
        @current-change="handlePageChange"
        class="pagination"
      />
    </el-card>

    <!-- Create User Dialog -->
    <el-dialog
      v-model="createDialogVisible"
      title="添加账号"
      width="500px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-width="80px"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="createForm.username" placeholder="字母、数字、下划线，3-20位" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="createForm.password" type="password" show-password placeholder="6-20位密码" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="createForm.email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="姓名" prop="fullName">
          <el-input v-model="createForm.fullName" placeholder="请输入真实姓名" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="createForm.phone" placeholder="请输入手机号（选填）" />
        </el-form-item>
        <el-form-item label="角色" prop="roleCode">
          <el-select v-model="createForm.roleCode" placeholder="请选择角色" style="width: 100%">
            <el-option
              v-for="option in roleOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="createLoading" @click="handleCreateUser">确认创建</el-button>
      </template>
    </el-dialog>
    <!-- View User Dialog -->
    <el-dialog v-model="viewDialogVisible" title="账号详情" width="480px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="用户名">{{ viewUser.username || '-' }}</el-descriptions-item>
        <el-descriptions-item label="姓名">{{ viewUser.fullName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ viewUser.email || '-' }}</el-descriptions-item>
        <el-descriptions-item label="手机号">{{ viewUser.phone || '-' }}</el-descriptions-item>
        <el-descriptions-item label="角色">
          <el-tag>{{ getRoleText(viewUser.roleCode) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="部门">{{ viewUser.departmentDisplayName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="viewUser.isActive ? 'success' : 'danger'" size="small">
            {{ viewUser.isActive ? '启用' : '禁用' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">
          {{ viewUser.createdAt ? viewUser.createdAt.replace('T', ' ').substring(0, 19) : '-' }}
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>

    <!-- Edit User Dialog -->
    <el-dialog v-model="editDialogVisible" title="编辑账号" width="500px" :close-on-click-modal="false">
      <el-form ref="editFormRef" :model="editForm" label-width="80px">
        <el-form-item label="用户名">
          <el-input :model-value="editForm.username" disabled />
        </el-form-item>
        <el-form-item label="邮箱" prop="email" :rules="[{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]">
          <el-input v-model="editForm.email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="姓名" prop="fullName" :rules="[{ required: true, message: '请输入姓名', trigger: 'blur' }]">
          <el-input v-model="editForm.fullName" placeholder="请输入姓名" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="editForm.phone" placeholder="请输入手机号" />
        </el-form-item>
        <el-form-item label="角色" prop="roleCode" :rules="[{ required: true, message: '请选择角色', trigger: 'change' }]">
          <el-select v-model="editForm.roleCode" placeholder="请选择角色" style="width: 100%">
            <el-option v-for="option in roleOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="editLoading" @click="handleSaveEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.page-container {
  padding: 20px;
}
.search-card {
  margin-bottom: 20px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.pagination {
  margin-top: 20px;
  justify-content: flex-end;
}
</style>
