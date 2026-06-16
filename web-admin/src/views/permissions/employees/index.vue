<template>
  <section class="permission-page">
    <div class="permission-page__toolbar">
      <el-input v-model="keyword" clearable placeholder="搜索员工" class="permission-page__search" />
      <el-button :icon="Refresh" @click="loadUsers">刷新</el-button>
      <el-button type="primary" :icon="Plus" :disabled="!canWrite" @click="createDialogVisible = true">
        新建员工
      </el-button>
    </div>

    <el-table :data="filteredUsers" v-loading="loading" border>
      <el-table-column prop="username" label="账号" min-width="140" />
      <el-table-column label="姓名" min-width="140">
        <template #default="{ row }">{{ row.fullName || row.realName || '-' }}</template>
      </el-table-column>
      <el-table-column prop="phone" label="手机号" min-width="140" />
      <el-table-column prop="roleCode" label="角色" min-width="160" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.isActive === false ? 'info' : 'success'">
            {{ row.isActive === false ? '停用' : '启用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button text :disabled="!canWrite" @click="goPermissions(row)">权限</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createDialogVisible" title="新建员工" width="520px">
      <el-form :model="form" label-width="96px">
        <el-form-item label="账号"><el-input v-model="form.username" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="form.password" type="password" show-password /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="form.fullName" /></el-form-item>
        <el-form-item label="手机号"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="角色模板"><el-input v-model="form.roleCode" placeholder="factory_super_admin / viewer ..." /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Plus, Refresh } from '@element-plus/icons-vue';
import { createPermissionEmployee, listPermissionEmployees } from '@/api/permissionSettings';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';

interface EmployeeRow {
  id: string | number;
  username?: string;
  fullName?: string;
  realName?: string;
  phone?: string;
  roleCode?: string;
  isActive?: boolean;
}

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const router = useRouter();
const loading = ref(false);
const saving = ref(false);
const keyword = ref('');
const users = ref<EmployeeRow[]>([]);
const createDialogVisible = ref(false);
const form = reactive({
  username: '',
  password: '',
  fullName: '',
  phone: '',
  roleCode: 'viewer',
});

const canWrite = computed(() => permissionStore.canWriteModuleCode('permission_employee_management'));
const factoryId = computed(() => authStore.factoryId);
const filteredUsers = computed(() => {
  const normalized = keyword.value.trim().toLowerCase();
  if (!normalized) return users.value;
  return users.value.filter(user => JSON.stringify(user).toLowerCase().includes(normalized));
});

function unwrapUsers(payload: unknown): EmployeeRow[] {
  const value = payload as { data?: unknown; records?: unknown; content?: unknown };
  const data = value?.data ?? payload;
  if (Array.isArray(data)) return data as EmployeeRow[];
  const nested = data as { records?: unknown; content?: unknown };
  if (Array.isArray(nested?.records)) return nested.records as EmployeeRow[];
  if (Array.isArray(nested?.content)) return nested.content as EmployeeRow[];
  return [];
}

async function loadUsers() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await listPermissionEmployees(factoryId.value, { page: 1, size: 500 });
    users.value = unwrapUsers(res);
  } finally {
    loading.value = false;
  }
}

async function submitCreate() {
  if (!factoryId.value) return;
  saving.value = true;
  try {
    await createPermissionEmployee(factoryId.value, form as unknown as Record<string, unknown>);
    ElMessage.success('已创建员工');
    createDialogVisible.value = false;
    await loadUsers();
  } finally {
    saving.value = false;
  }
}

function goPermissions(row: EmployeeRow) {
  router.push({ path: '/permissions/employee-permissions', query: { userId: String(row.id) } });
}

onMounted(loadUsers);
</script>

<style scoped>
.permission-page__toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.permission-page__search {
  max-width: 320px;
}
</style>
