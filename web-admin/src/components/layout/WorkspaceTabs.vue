<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { Plus } from '@element-plus/icons-vue';
import { useWorkspaceStore } from '@/store/modules/workspace';

const router = useRouter();
const workspace = useWorkspaceStore();

const canDuplicate = computed(() => Boolean(workspace.activeTab));

function activate(key: string): void {
  const tab = workspace.tabs.find((item) => item.key === key);
  if (tab) void router.push(tab.fullPath);
}

function close(event: MouseEvent, key: string): void {
  event.stopPropagation();
  const wasActive = workspace.activeKey === key;
  const fallback = workspace.closeTab(key);
  if (wasActive && fallback) void router.push(fallback.fullPath);
}

function closeFromKeyboard(event: KeyboardEvent, key: string): void {
  event.preventDefault();
  close(event as unknown as MouseEvent, key);
}

function duplicateCurrent(): void {
  const target = workspace.duplicateRoute();
  if (target) void router.push(target);
}

function onDragStart(event: DragEvent, key: string): void {
  workspace.beginDrag(key);
  event.dataTransfer?.setData('text/plain', key);
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'copy';
}
</script>

<template>
  <nav class="workspace-tabs" aria-label="已打开任务">
    <div class="workspace-tab-scroll">
      <button
        v-for="tab in workspace.tabs"
        :key="tab.key"
        type="button"
        class="workspace-tab"
        :class="{ active: workspace.activeKey === tab.key }"
        draggable="true"
        :title="`${tab.title} · 可拖到右侧作为只读参考`"
        @click="activate(tab.key)"
        @dragstart="onDragStart($event, tab.key)"
        @dragend="workspace.endDrag"
      >
        <span v-if="tab.dirty" class="task-dot" aria-label="未完成任务" />
        <span class="workspace-tab-title">{{ tab.title }}</span>
        <span
          v-if="workspace.tabs.length > 1"
          class="workspace-tab-close"
          role="button"
          aria-label="关闭任务"
          tabindex="0"
          @click="close($event, tab.key)"
          @keydown.enter="closeFromKeyboard($event, tab.key)"
        >×</span>
      </button>
      <el-tooltip content="复制当前页面为一个新任务；打开其他页面请直接使用左侧菜单" placement="bottom">
        <el-button
          class="new-task-button"
          :icon="Plus"
          :disabled="!canDuplicate"
          aria-label="复制当前页面为新任务"
          @click="duplicateCurrent"
        />
      </el-tooltip>
    </div>
    <span class="workspace-hint">拖动标签到右侧可同屏参考</span>
  </nav>
</template>

<style scoped lang="scss">
.workspace-tabs {
  min-height: 46px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 16px;
  background: #edf2f8;
  border-bottom: 1px solid #dfe7f1;
}

.workspace-tab-scroll {
  min-width: 0;
  flex: 1;
  display: flex;
  align-items: stretch;
  overflow-x: auto;
  scrollbar-width: thin;
}

.workspace-tab {
  min-width: 112px;
  max-width: 210px;
  min-height: 46px;
  padding: 0 12px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: #53647a;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  cursor: grab;
  white-space: nowrap;
}

.workspace-tab:hover { background: rgba(255, 255, 255, 0.6); color: #1b65a8; }
.workspace-tab.active { color: #1b65a8; background: #fff; border-bottom-color: #1b65a8; font-weight: 600; }
.workspace-tab:active { cursor: grabbing; }
.workspace-tab-title { overflow: hidden; text-overflow: ellipsis; }
.task-dot { width: 8px; height: 8px; flex: 0 0 8px; border-radius: 50%; background: #d89222; box-shadow: 0 0 0 3px rgba(216, 146, 34, 0.12); }
.workspace-tab-close { width: 24px; height: 24px; border-radius: 6px; display: inline-grid; place-items: center; color: #8a98aa; font-size: 17px; font-weight: 400; }
.workspace-tab-close:hover { color: #d84a4a; background: #fff0f0; }
.new-task-button { align-self: center; margin: 6px 8px; min-width: 34px; }
.workspace-hint { flex: 0 0 auto; color: #8795a8; font-size: 12px; }

@media (max-width: 980px) {
  .workspace-hint { display: none; }
  .workspace-tabs { padding-inline: 8px; }
}
</style>
