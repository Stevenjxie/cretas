<template>
  <el-segmented
    :model-value="modelValue"
    :options="options"
    size="small"
    @update:model-value="$emit('update:modelValue', $event as PermissionLevel)"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { PermissionLevel } from '@/config/moduleRegistry';

const props = withDefaults(defineProps<{
  modelValue: PermissionLevel;
  writeSupported?: boolean;
  disabled?: boolean;
}>(), {
  writeSupported: true,
  disabled: false,
});

defineEmits<{
  (event: 'update:modelValue', value: PermissionLevel): void;
}>();

const options = computed(() => [
  { label: '隐藏', value: 'hidden', disabled: props.disabled },
  { label: '只读', value: 'read', disabled: props.disabled },
  { label: '可编辑', value: 'write', disabled: props.disabled || !props.writeSupported },
]);
</script>
