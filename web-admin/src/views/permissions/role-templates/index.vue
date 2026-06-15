<template>
  <section class="permission-editor">
    <aside class="permission-editor__side">
      <RoleTemplateSelector v-model="selectedRole" :roles="roles" />
    </aside>
    <main class="permission-editor__main">
      <ModulePermissionTree :modules="treeRows" :disabled="!canWrite" @change="onChange" />
      <div class="permission-editor__footer">
        <el-button type="primary" :disabled="!canWrite || !selectedRole" :loading="saving" @click="save">
          保存角色模板
        </el-button>
      </div>
    </main>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import ModulePermissionTree, { type ModulePermissionRow } from '@/components/permissions/ModulePermissionTree.vue';
import RoleTemplateSelector from '@/components/permissions/RoleTemplateSelector.vue';
import {
  listPermissionModules,
  listRoleTemplates,
  updateRoleTemplate,
  type RoleTemplateDto,
} from '@/api/permissionSettings';
import type { ModuleDefinition, PermissionLevel } from '@/config/moduleRegistry';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const modules = ref<ModuleDefinition[]>([]);
const roles = ref<RoleTemplateDto[]>([]);
const selectedRole = ref('');
const draft = ref<Record<string, PermissionLevel>>({});
const saving = ref(false);

const canWrite = computed(() => permissionStore.canWriteModuleCode('permission_role_templates'));
const currentRole = computed(() => roles.value.find(role => role.roleCode === selectedRole.value));
const treeRows = computed<ModulePermissionRow[]>(() => modules.value.map(module => ({
  module,
  permissionLevel: draft.value[module.moduleCode] || 'hidden',
  source: 'role_template',
})));

watch(currentRole, role => {
  draft.value = Object.fromEntries((role?.modules || []).map(item => [item.moduleCode, item.permissionLevel]));
});

function onChange(rows: ModulePermissionRow[]) {
  draft.value = Object.fromEntries(rows.map(row => [row.module.moduleCode, row.permissionLevel]));
}

async function load() {
  if (!authStore.factoryId) return;
  const [moduleRows, roleRows] = await Promise.all([
    listPermissionModules(authStore.factoryId),
    listRoleTemplates(authStore.factoryId),
  ]);
  modules.value = moduleRows;
  roles.value = roleRows;
  selectedRole.value = selectedRole.value || roleRows[0]?.roleCode || '';
}

async function save() {
  if (!authStore.factoryId || !selectedRole.value) return;
  saving.value = true;
  try {
    await updateRoleTemplate(authStore.factoryId, selectedRole.value, Object.entries(draft.value).map(([moduleCode, permissionLevel]) => ({
      moduleCode,
      permissionLevel,
    })));
    ElMessage.success('已保存角色模板');
    await load();
    if (selectedRole.value === permissionStore.currentRole) {
      await permissionStore.loadFromDb();
    }
  } finally {
    saving.value = false;
  }
}

onMounted(load);
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
