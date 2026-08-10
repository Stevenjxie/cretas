<script setup lang="ts">
import { computed } from 'vue';
import { Close } from '@element-plus/icons-vue';
import type { WorkspaceTab } from '@/store/modules/workspace';

const props = defineProps<{ tab: WorkspaceTab }>();
defineEmits<{ close: [] }>();

const referenceUrl = computed(() => {
  const url = new URL(props.tab.fullPath, window.location.origin);
  url.searchParams.set('_workspaceReference', '1');
  return `${url.pathname}${url.search}${url.hash}`;
});
</script>

<template>
  <aside class="reference-pane" aria-label="同屏只读参考">
    <header>
      <div>
        <strong>同屏参考 · {{ tab.title }}</strong>
        <span>只读展示，左侧当前任务不会被替换</span>
      </div>
      <el-button :icon="Close" text aria-label="关闭同屏参考" @click="$emit('close')" />
    </header>
    <div class="reference-frame-wrap">
      <iframe :src="referenceUrl" :title="`只读参考：${tab.title}`" tabindex="-1" />
      <div class="readonly-shield" aria-hidden="true" />
    </div>
  </aside>
</template>

<style scoped lang="scss">
.reference-pane {
  min-width: 340px;
  width: 38%;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-left: 1px solid #dfe7f1;
  box-shadow: -8px 0 24px rgba(31, 55, 83, 0.07);
}

header {
  min-height: 66px;
  padding: 12px 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #edf2f7;
}

header div { min-width: 0; display: flex; flex-direction: column; gap: 4px; }
header strong { color: #1a2332; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
header span { color: #7a8599; font-size: 12px; }
.reference-frame-wrap { flex: 1; min-height: 520px; position: relative; overflow: hidden; background: #f4f6f9; }
iframe { width: 100%; height: 100%; border: 0; background: #f4f6f9; }
.readonly-shield { position: absolute; inset: 0; cursor: not-allowed; background: transparent; }

@media (max-width: 1100px) {
  .reference-pane { width: 44%; min-width: 320px; }
}
</style>
