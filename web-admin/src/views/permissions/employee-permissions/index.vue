<template>
  <section class="permission-editor">
    <aside class="permission-editor__side">
      <EmployeeSelector v-model="selectedUserId" :factory-id="authStore.factoryId" />
    </aside>
    <main class="permission-editor__main">
      <ModulePermissionTree
        :modules="treeRows"
        :disabled="!canWrite || !selectedUserId"
        show-clear
        @change="onChange"
        @clear="clearOverride"
      />
      <div class="permission-editor__footer">
        <el-button type="primary" :disabled="!canWrite || !selectedUserId" :loading="saving" @click="save">
          保存员工权限
        </el-button>
      </div>
    </main>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import EmployeeSelector from '@/components/permissions/EmployeeSelector.vue';
import ModulePermissionTree, { type ModulePermissionRow } from '@/components/permissions/ModulePermissionTree.vue';
import {
  clearUserOverride,
  getUserEffectivePermissions,
  listPermissionModules,
  updateUserOverrides,
  type EffectiveUserPermissionDto,
} from '@/api/permissionSettings';
import type { ModuleDefinition, PermissionLevel } from '@/config/moduleRegistry';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';

const route = useRoute();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const selectedUserId = ref(String(route.query.userId || ''));
const modules = ref<ModuleDefinition[]>([]);
const effective = ref<EffectiveUserPermissionDto | null>(null);
const draft = ref<Record<string, PermissionLevel>>({});
const saving = ref(false);
const canWrite = computed(() => permissionStore.canWriteModuleCode('permission_employee_overrides'));
const treeRows = computed<ModulePermissionRow[]>(() => modules.value.map(module => {
  const item = effective.value?.modules.find(row => row.moduleCode === module.moduleCode);
  return {
    module,
    permissionLevel: draft.value[module.moduleCode] || item?.permissionLevel || 'hidden',
    source: item?.source,
  };
}));

watch(selectedUserId, loadEffective);

function onChange(rows: ModulePermissionRow[]) {
  draft.value = Object.fromEntries(rows.map(row => [row.module.moduleCode, row.permissionLevel]));
}

async function loadModules() {
  if (!authStore.factoryId) return;
  modules.value = await listPermissionModules(authStore.factoryId);
}

async function loadEffective() {
  if (!authStore.factoryId || !selectedUserId.value) return;
  effective.value = await getUserEffectivePermissions(authStore.factoryId, selectedUserId.value);
  draft.value = {};
}

async function save() {
  if (!authStore.factoryId || !selectedUserId.value) return;
  saving.value = true;
  try {
    effective.value = await updateUserOverrides(authStore.factoryId, selectedUserId.value, Object.entries(draft.value).map(([moduleCode, permissionLevel]) => ({
      moduleCode,
      permissionLevel,
    })));
    ElMessage.success('已保存员工权限');
    draft.value = {};
  } finally {
    saving.value = false;
  }
}

async function clearOverride(moduleCode: string) {
  if (!authStore.factoryId || !selectedUserId.value) return;
  effective.value = await clearUserOverride(authStore.factoryId, selectedUserId.value, moduleCode);
  ElMessage.success('已恢复继承');
}

onMounted(async () => {
  await loadModules();
  await loadEffective();
});
</script>

<style scoped>
.permission-editor {
  display: grid;
  gap: 16px;
  grid-template-columns: 260px minmax(0, 1fr);
}

.permission-editor__footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
