<template>
  <div class="module-tree">
    <el-input v-model="keyword" clearable placeholder="搜索模块" class="module-tree__search" />
    <el-collapse v-model="openGroups">
      <el-collapse-item
        v-for="group in filteredGroups"
        :key="group.parentCode"
        :name="group.parentCode"
      >
        <template #title>
          <div class="module-tree__group-title">
            <span>{{ group.parentName }}</span>
            <div class="module-tree__actions" @click.stop>
              <el-button text size="small" @click="setGroup(group.parentCode, 'hidden')">全隐藏</el-button>
              <el-button text size="small" @click="setGroup(group.parentCode, 'read')">全只读</el-button>
              <el-button text size="small" @click="setGroup(group.parentCode, 'write')">全可编辑</el-button>
            </div>
          </div>
        </template>
        <div v-for="item in group.modules" :key="item.module.moduleCode" class="module-tree__row">
          <div class="module-tree__main">
            <div class="module-tree__name">{{ item.module.displayName }}</div>
            <div class="module-tree__path">{{ item.module.routePath }}</div>
          </div>
          <EffectivePermissionBadge :source="item.source" />
          <PermissionLevelSegment
            :model-value="item.permissionLevel"
            :write-supported="item.module.writeSupported"
            :disabled="disabled"
            @update:model-value="setLevel(item.module.moduleCode, $event)"
          />
          <el-button
            v-if="showClear && item.source === 'user_override'"
            text
            size="small"
            :disabled="disabled"
            @click="$emit('clear', item.module.moduleCode)"
          >
            继承
          </el-button>
        </div>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { ModuleDefinition, PermissionLevel } from '@/config/moduleRegistry';
import PermissionLevelSegment from './PermissionLevelSegment.vue';
import EffectivePermissionBadge from './EffectivePermissionBadge.vue';

export interface ModulePermissionRow {
  module: ModuleDefinition;
  permissionLevel: PermissionLevel;
  source?: string;
}

const props = withDefaults(defineProps<{
  modules: ModulePermissionRow[];
  disabled?: boolean;
  showClear?: boolean;
}>(), {
  disabled: false,
  showClear: false,
});

const emit = defineEmits<{
  (event: 'change', value: ModulePermissionRow[]): void;
  (event: 'clear', moduleCode: string): void;
}>();

const keyword = ref('');
const openGroups = ref<string[]>([]);
const rows = ref<ModulePermissionRow[]>([]);

watch(
  () => props.modules,
  value => {
    rows.value = value.map(item => ({ ...item }));
    openGroups.value = Array.from(new Set(value.map(item => item.module.parentCode)));
  },
  { immediate: true },
);

const filteredGroups = computed(() => {
  const normalized = keyword.value.trim().toLowerCase();
  const grouped = new Map<string, { parentCode: string; parentName: string; modules: ModulePermissionRow[] }>();
  for (const row of rows.value) {
    if (normalized) {
      const haystack = `${row.module.displayName} ${row.module.moduleCode} ${row.module.routePath}`.toLowerCase();
      if (!haystack.includes(normalized)) continue;
    }
    const group = grouped.get(row.module.parentCode) || {
      parentCode: row.module.parentCode,
      parentName: row.module.parentName,
      modules: [],
    };
    group.modules.push(row);
    grouped.set(row.module.parentCode, group);
  }
  return Array.from(grouped.values());
});

function setLevel(moduleCode: string, level: PermissionLevel) {
  rows.value = rows.value.map(row =>
    row.module.moduleCode === moduleCode ? { ...row, permissionLevel: level } : row,
  );
  emit('change', rows.value);
}

function setGroup(parentCode: string, level: PermissionLevel) {
  rows.value = rows.value.map(row => {
    if (row.module.parentCode !== parentCode) return row;
    const nextLevel = level === 'write' && !row.module.writeSupported ? 'read' : level;
    return { ...row, permissionLevel: nextLevel };
  });
  emit('change', rows.value);
}
</script>

<style scoped>
.module-tree__search {
  margin-bottom: 12px;
}

.module-tree__group-title,
.module-tree__row {
  align-items: center;
  display: flex;
  gap: 12px;
  width: 100%;
}

.module-tree__group-title {
  justify-content: space-between;
}

.module-tree__actions {
  display: flex;
  gap: 4px;
}

.module-tree__row {
  border-bottom: 1px solid var(--el-border-color-lighter);
  min-height: 48px;
  padding: 8px 0;
}

.module-tree__main {
  flex: 1;
  min-width: 0;
}

.module-tree__name {
  color: var(--el-text-color-primary);
  font-weight: 500;
}

.module-tree__path {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
