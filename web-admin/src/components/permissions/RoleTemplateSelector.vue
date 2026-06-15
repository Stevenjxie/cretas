<template>
  <el-select
    :model-value="modelValue"
    filterable
    placeholder="选择角色模板"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <el-option
      v-for="role in roles"
      :key="role.roleCode"
      :label="`${role.roleName || role.roleCode} · 可编辑 ${editableCount(role)}`"
      :value="role.roleCode"
    />
  </el-select>
</template>

<script setup lang="ts">
import type { RoleTemplateDto } from '@/api/permissionSettings';

defineProps<{
  modelValue?: string;
  roles: RoleTemplateDto[];
}>();

defineEmits<{
  (event: 'update:modelValue', value: string): void;
}>();

function editableCount(role: RoleTemplateDto): number {
  return role.modules.filter(module => module.permissionLevel === 'write').length;
}
</script>
