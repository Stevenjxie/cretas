<template>
  <div class="user-module-access-matrix">
    <div class="header-bar">
      <div class="title">
        <span>账号模块权限 (Layer 4)</span>
        <code>{{ factoryId }}</code>
      </div>
      <div class="actions">
        <el-select
          v-model="selectedUserId"
          filterable
          placeholder="选择账号"
          size="small"
          style="width: 260px"
          @change="loadAccess"
        >
          <el-option
            v-for="user in users"
            :key="String(user.id)"
            :label="`${user.username || user.fullName || user.id} · ${user.roleCode || '-'}`"
            :value="String(user.id)"
          />
        </el-select>
        <el-button size="small" :loading="loadingUsers || loadingAccess" @click="reload">刷新</el-button>
      </div>
    </div>

    <el-alert type="warning" :closable="false" show-icon style="margin: 8px 0">
      DENY/GRANT 覆盖账号默认角色权限；继承表示恢复角色默认。后端会在 @RequireModule 接口再次拦截。
    </el-alert>

    <el-table v-loading="loadingUsers || loadingAccess" :data="rows" border size="small" max-height="520" stripe>
      <el-table-column prop="category" label="分类" width="100" />
      <el-table-column prop="displayName" label="模块" min-width="150">
        <template #default="{ row }">
          <div class="module-name">
            <span>{{ row.displayName }}</span>
            <code>{{ row.moduleCode }}</code>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="permissionModule" label="角色矩阵" width="110" />
      <el-table-column label="角色默认" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.roleDefaultAllowed ? 'success' : 'info'" size="small">
            {{ row.roleDefaultAllowed ? '允许' : '拒绝' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="账号覆盖" width="220" align="center">
        <template #default="{ row }">
          <el-radio-group
            :model-value="row.override || 'INHERIT'"
            size="small"
            :disabled="!selectedUserId || savingKey === row.moduleCode"
            @change="(value: string | number | boolean | undefined) => updateOverride(row, String(value))"
          >
            <el-radio-button label="INHERIT">继承</el-radio-button>
            <el-radio-button label="GRANT">开通</el-radio-button>
            <el-radio-button label="DENY">关闭</el-radio-button>
          </el-radio-group>
        </template>
      </el-table-column>
      <el-table-column label="最终" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.effectiveAllowed ? 'success' : 'danger'" size="small">
            {{ row.effectiveAllowed ? '可访问' : '不可访问' }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { get } from '@/api/request';
import {
  clearUserModuleAccess,
  getUserModuleAccess,
  updateUserModuleAccess,
  type UserModuleAccessView,
  type UserModuleAccessType,
} from '@/api/permissionApi';

interface UserOption {
  id: string | number;
  username?: string;
  fullName?: string;
  roleCode?: string;
}

const props = defineProps<{ factoryId: string }>();

const users = ref<UserOption[]>([]);
const rows = ref<UserModuleAccessView[]>([]);
const selectedUserId = ref('');
const loadingUsers = ref(false);
const loadingAccess = ref(false);
const savingKey = ref('');

async function loadUsers() {
  if (!props.factoryId) return;
  loadingUsers.value = true;
  try {
    const response = await get(`/${props.factoryId}/users`, { params: { page: 1, size: 500 } });
    const content = response?.data?.content || [];
    users.value = content.map((u: any) => ({
      id: u.id,
      username: u.username,
      fullName: u.fullName,
      roleCode: u.roleCode,
    }));
    if (!selectedUserId.value && users.value.length > 0) {
      selectedUserId.value = String(users.value[0].id);
      await loadAccess();
    }
  } catch (e: any) {
    ElMessage.error('加载账号列表失败: ' + (e?.message || 'unknown'));
  } finally {
    loadingUsers.value = false;
  }
}

async function loadAccess() {
  if (!props.factoryId || !selectedUserId.value) {
    rows.value = [];
    return;
  }
  loadingAccess.value = true;
  try {
    rows.value = await getUserModuleAccess(props.factoryId, selectedUserId.value);
  } catch (e: any) {
    ElMessage.error('加载账号模块权限失败: ' + (e?.message || 'unknown'));
  } finally {
    loadingAccess.value = false;
  }
}

async function updateOverride(row: UserModuleAccessView, value: string) {
  if (!props.factoryId || !selectedUserId.value) return;
  savingKey.value = row.moduleCode;
  try {
    if (value === 'INHERIT') {
      await clearUserModuleAccess(props.factoryId, selectedUserId.value, row.moduleCode);
    } else {
      await updateUserModuleAccess(props.factoryId, selectedUserId.value, row.moduleCode, value as UserModuleAccessType);
    }
    await loadAccess();
    ElMessage.success('账号模块权限已更新');
  } catch (e: any) {
    ElMessage.error('保存失败: ' + (e?.message || 'unknown'));
  } finally {
    savingKey.value = '';
  }
}

async function reload() {
  await loadUsers();
  await loadAccess();
}

onMounted(loadUsers);
</script>

<style scoped>
.user-module-access-matrix { padding: 12px; }
.header-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}
.title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.title code,
.module-name code {
  background: var(--el-fill-color-light);
  padding: 1px 6px;
  border-radius: 3px;
}
.actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.module-name {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
</style>
