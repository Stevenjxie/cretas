<script setup lang="ts">
import { computed } from 'vue';
import { Loading } from '@element-plus/icons-vue';
import type { WorkflowNode as WorkflowNodeData } from '@/types/workflow';
import WorkflowNode from './WorkflowNode.vue';

const props = withDefaults(
  defineProps<{
    nodes: WorkflowNodeData[];
    title?: string;
    orientation?: 'horizontal' | 'vertical';
    loading?: boolean;
    emptyHint?: string;
    aiTriggerEnabled?: boolean;
    aiTriggerLabel?: string;
  }>(),
  {
    title: '',
    orientation: 'horizontal',
    loading: false,
    emptyHint: '暂无工作流数据',
    aiTriggerEnabled: false,
    aiTriggerLabel: '跟 AI 说',
  },
);

const emit = defineEmits<{
  (event: 'node-click', nodeId: string): void;
  (event: 'node-long-press', nodeId: string): void;
  (event: 'ai-trigger'): void;
}>();

const isVertical = computed(() => props.orientation === 'vertical');
const showHeader = computed(
  () => Boolean(props.title) || props.aiTriggerEnabled,
);
</script>

<template>
  <section class="workflow-bar" :aria-label="title ? `${title} 工作流` : '工作流'">
    <header v-if="showHeader" class="workflow-bar-header">
      <h3 v-if="title" class="workflow-bar-title">{{ title }}</h3>
      <span v-else />
      <button
        v-if="aiTriggerEnabled"
        type="button"
        class="ai-trigger"
        @click="emit('ai-trigger')"
      >
        {{ aiTriggerLabel }}
      </button>
    </header>

    <div v-if="loading" class="workflow-bar-state">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>加载中…</span>
    </div>

    <div v-else-if="!nodes.length" class="workflow-bar-state">
      <span>{{ emptyHint }}</span>
    </div>

    <div
      v-else
      class="workflow-bar-nodes"
      :class="{ vertical: isVertical }"
    >
      <template v-for="node in nodes" :key="node.id">
        <WorkflowNode
          :node="node"
          @click="emit('node-click', $event)"
          @long-press="emit('node-long-press', $event)"
        />
      </template>
    </div>
  </section>
</template>

<style scoped>
.workflow-bar {
  background: var(--el-bg-color, #ffffff);
  border: 1px solid var(--el-border-color-lighter, #e5e7eb);
  border-radius: 10px;
  padding: 12px;
  margin-bottom: 12px;
}

.workflow-bar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.workflow-bar-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary, #1f2937);
  line-height: 1.4;
}

.ai-trigger {
  background: var(--el-color-primary-light-9, #e6f7ff);
  color: var(--el-color-primary-dark-2, #0050b3);
  border: 1px solid var(--el-color-primary, #1890ff);
  border-radius: 6px;
  padding: 5px 10px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.15s ease-out;
}

.ai-trigger:hover {
  opacity: 0.85;
}

.ai-trigger:active {
  opacity: 0.7;
}

.workflow-bar-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 14px 0;
  color: var(--el-text-color-secondary, #6b7280);
  font-size: 13px;
}

.workflow-bar-nodes {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(138px, 1fr));
  gap: 8px;
}

.workflow-bar-nodes.vertical {
  grid-template-columns: 1fr;
}
</style>
