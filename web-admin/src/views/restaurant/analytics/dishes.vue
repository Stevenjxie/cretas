<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import MenuBoard from './menu-board.vue';
import GrossMargin from './gross-margin.vue';
import { resolveDishesTab, type DishesTab } from './restaurantDishesTab';

const route = useRoute();
const router = useRouter();
const activeTab = ref<DishesTab>(resolveDishesTab(route.query));

// route.query.tab → activeTab (支持旧路由 redirect 落点 + 浏览器前进后退)
watch(() => route.query.tab, () => { activeTab.value = resolveDishesTab(route.query); });

// activeTab → URL (切 tab 同步 query, 不刷新页面; 保留其它 query 如 days/store)
function onTabChange(name: string | number) {
  router.replace({ query: { ...route.query, tab: String(name) } });
}
</script>

<template>
  <div class="restaurant-dishes">
    <el-tabs v-model="activeTab" class="dishes-top-tabs" @tab-change="onTabChange">
      <el-tab-pane label="菜品四象限" name="quadrant">
        <MenuBoard :embedded="true" />
      </el-tab-pane>
      <el-tab-pane label="菜品毛利" name="margin">
        <GrossMargin />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
