<script setup lang="ts">
import type { Component } from 'vue';
import type { MenuItem } from './menuConfig';

defineOptions({ name: 'SidebarMenuNode' });

defineProps<{
  item: MenuItem;
  collapsed: boolean;
  iconMap: Record<string, Component>;
  titleForItem: (item: MenuItem) => string;
  level: number;
}>();
</script>

<template>
  <div v-if="item.groupLabel && !collapsed" class="menu-group-label">
    {{ item.groupLabel }}
  </div>

  <el-sub-menu
    v-if="item.children?.length"
    :index="item.path"
    popper-class="app-sidebar-menu-popper"
  >
    <template #title>
      <el-icon v-if="item.icon && iconMap[item.icon]">
        <component :is="iconMap[item.icon]" />
      </el-icon>
      <span>{{ titleForItem(item) }}</span>
    </template>

    <SidebarMenuNode
      v-for="child in item.children"
      :key="child.path"
      :item="child"
      :collapsed="collapsed"
      :icon-map="iconMap"
      :title-for-item="titleForItem"
      :level="level + 1"
    />
  </el-sub-menu>

  <el-menu-item v-else :index="item.path">
    <el-icon v-if="item.icon && iconMap[item.icon]">
      <component :is="iconMap[item.icon]" />
    </el-icon>
    <template v-if="item.icon" #title>{{ titleForItem(item) }}</template>
    <span v-if="!item.icon">{{ titleForItem(item) }}</span>
  </el-menu-item>
</template>

<style scoped>
.menu-group-label {
  padding: 8px 12px 4px 36px;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.3);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  line-height: 1;
  white-space: nowrap;
  overflow: hidden;
  user-select: none;
}

.menu-group-label:not(:first-child) {
  margin-top: 4px;
  border-top: 1px solid rgba(255, 255, 255, 0.04);
  padding-top: 10px;
}
</style>
