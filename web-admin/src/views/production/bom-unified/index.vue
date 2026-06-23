<script setup lang="ts">
import { ref, onMounted, defineAsyncComponent } from 'vue';
import { useRoute } from 'vue-router';

const route = useRoute();
const activeTab = ref('materials');

const BomContent = defineAsyncComponent(() => import('@/views/production/bom/index.vue'));
const RecipeContent = defineAsyncComponent(() => import('@/views/production/ProductRecipeView.vue'));
const ConversionContent = defineAsyncComponent(() => import('@/views/production/conversions/index.vue'));

onMounted(() => {
  const tab = route.query.tab as string;
  if (tab === 'recipe') activeTab.value = 'recipe';
  else if (tab === 'conversion') activeTab.value = 'conversion';
});
</script>

<template>
  <div class="bom-unified">
    <el-card shadow="never">
      <template #header>
        <span style="font-size: 16px; font-weight: 600;">BOM / 配方管理</span>
      </template>
      <el-tabs v-model="activeTab" type="border-card">
        <el-tab-pane label="原辅料配方" name="materials">
          <BomContent />
        </el-tab-pane>
        <el-tab-pane label="调料配方" name="recipe">
          <RecipeContent />
        </el-tab-pane>
        <el-tab-pane label="转换率" name="conversion">
          <ConversionContent />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.bom-unified {
  height: 100%;
}
</style>
