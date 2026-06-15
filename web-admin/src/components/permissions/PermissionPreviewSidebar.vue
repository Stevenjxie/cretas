<template>
  <div class="permission-preview">
    <el-menu class="permission-preview__menu">
      <el-sub-menu v-for="group in groups" :key="group.parentCode" :index="group.parentCode">
        <template #title>{{ group.parentName }}</template>
        <el-menu-item v-for="item in group.modules" :key="item.moduleCode" :index="item.moduleCode">
          <span>{{ item.moduleCode }}</span>
          <el-tag size="small" :type="item.permissionLevel === 'write' ? 'success' : ''">
            {{ item.permissionLevel === 'write' ? '可编辑' : '只读' }}
          </el-tag>
        </el-menu-item>
      </el-sub-menu>
    </el-menu>
    <div class="permission-preview__lists">
      <el-tag type="danger">隐藏 {{ deniedModules.length }}</el-tag>
      <el-tag type="success">可编辑 {{ editableModules.length }}</el-tag>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { PERMISSION_MODULE_REGISTRY } from '@/config/moduleRegistry';
import type { ModulePermissionDto } from '@/api/permissionSettings';

const props = defineProps<{
  visibleModules: ModulePermissionDto[];
  deniedModules: ModulePermissionDto[];
  editableModules: ModulePermissionDto[];
}>();

const groups = computed(() => {
  const registry = new Map(PERMISSION_MODULE_REGISTRY.map(module => [module.moduleCode, module]));
  const grouped = new Map<string, { parentCode: string; parentName: string; modules: ModulePermissionDto[] }>();
  for (const item of props.visibleModules) {
    const definition = registry.get(item.moduleCode);
    const parentCode = definition?.parentCode || 'other';
    const parentName = definition?.parentName || '其他';
    const group = grouped.get(parentCode) || { parentCode, parentName, modules: [] };
    group.modules.push(item);
    grouped.set(parentCode, group);
  }
  return Array.from(grouped.values());
});
</script>

<style scoped>
.permission-preview {
  display: grid;
  gap: 12px;
}

.permission-preview__menu {
  border-right: 0;
}

.permission-preview__lists {
  display: flex;
  gap: 8px;
}
</style>
