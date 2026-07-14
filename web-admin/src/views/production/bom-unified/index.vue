<script setup lang="ts">
import { ref, onMounted, watch, defineAsyncComponent } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const activeTab = ref('materials');

const BomContent = defineAsyncComponent(() => import('@/views/production/bom/index.vue'));
const ConversionContent = defineAsyncComponent(() => import('@/views/production/conversions/index.vue'));

/**
 * 把 ?tab= 映射到 activeTab。用 watch (非仅 onMounted) —— 同路由 query 变化不会重挂组件,
 * 历史 ?tab=recipe 会原位规范化到「原辅料配方 > 辅料」工序视图，其他 tab 仍支持同路由切换。
 * 只读 mount 会让 tab 不跟着切 (headed 验证抓到)。
 */
function syncTabFromQuery() {
  const tab = route.query.tab as string;
  if (tab === 'recipe') {
    activeTab.value = 'materials';
    void router.replace({
      path: '/production/bom',
      query: {
        ...route.query,
        tab: 'materials',
        category: 'AUXILIARY',
        auxView: 'process',
      },
    });
  } else if (tab === 'conversion') activeTab.value = 'conversion';
  else if (tab === 'materials') activeTab.value = 'materials';
}
onMounted(syncTabFromQuery);
watch(() => route.query.tab, syncTabFromQuery);
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
