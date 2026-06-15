<template>
  <el-tag size="small" :type="tagType">{{ label }}</el-tag>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
  source?: 'super_admin' | 'user_override' | 'role_template' | 'hidden_default' | string;
}>();

const label = computed(() => ({
  super_admin: '超级管理员',
  user_override: '员工单独设置',
  role_template: '角色模板',
  hidden_default: '默认隐藏',
}[props.source || ''] || '未设置'));

const tagType = computed(() => {
  if (props.source === 'user_override') return 'warning';
  if (props.source === 'super_admin') return 'success';
  if (props.source === 'hidden_default') return 'info';
  return '';
});
</script>
