<script setup lang="ts">
import type { Component } from 'vue';
import type { MenuItem } from './menuConfig';

defineOptions({ name: 'SidebarMenuNode' });

defineProps<{
  item: MenuItem;
  collapsed: boolean;
  iconMap: Record<string, Component>;
  titleForItem: (item: MenuItem) => string;
  badgeForItem: (item: MenuItem) => number | null;
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
      <span class="menu-title-copy">{{ titleForItem(item) }}</span>
      <span v-if="badgeForItem(item) !== null" class="menu-task-badge">{{ Math.min(badgeForItem(item) || 0, 99) }}<template v-if="(badgeForItem(item) || 0) > 99">+</template></span>
    </template>

    <SidebarMenuNode
      v-for="child in item.children"
      :key="child.path"
      :item="child"
      :collapsed="collapsed"
      :icon-map="iconMap"
      :title-for-item="titleForItem"
      :badge-for-item="badgeForItem"
      :level="level + 1"
    />
  </el-sub-menu>

  <el-menu-item v-else :index="item.path">
    <el-icon v-if="item.icon && iconMap[item.icon]">
      <component :is="iconMap[item.icon]" />
    </el-icon>
    <template #title>
      <span class="menu-title-copy">{{ titleForItem(item) }}</span>
      <span v-if="badgeForItem(item) !== null" class="menu-task-badge">{{ Math.min(badgeForItem(item) || 0, 99) }}<template v-if="(badgeForItem(item) || 0) > 99">+</template></span>
    </template>
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

.menu-title-copy { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.menu-task-badge {
  min-width: 20px;
  height: 20px;
  margin-left: auto;
  padding: 0 6px;
  border-radius: 999px;
  background: #e75555;
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  line-height: 1;
  font-weight: 700;
  box-shadow: 0 0 0 2px rgba(231, 85, 85, 0.14);
}
</style>
