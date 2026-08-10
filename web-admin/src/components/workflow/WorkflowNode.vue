<script setup lang="ts">
import { computed } from 'vue';
import type { WorkflowNode } from '@/types/workflow';
import { formatWorkflowCount, getWorkflowPalette } from './tokens';

const props = withDefaults(
  defineProps<{
    node: WorkflowNode;
    size?: 'sm' | 'md';
  }>(),
  { size: 'md' },
);

const emit = defineEmits<{
  (event: 'click', nodeId: string): void;
  (event: 'long-press', nodeId: string): void;
}>();

const palette = computed(() => getWorkflowPalette(props.node.status));
const displayCount = computed(() => formatWorkflowCount(props.node.count));

let pressTimer: number | undefined;
let longPressTriggered = false;

function startPress() {
  if (pressTimer) window.clearTimeout(pressTimer);
  longPressTriggered = false;
  pressTimer = window.setTimeout(() => {
    longPressTriggered = true;
    emit('long-press', props.node.id);
    pressTimer = undefined;
  }, 500);
}

function cancelPress() {
  if (pressTimer) {
    window.clearTimeout(pressTimer);
    pressTimer = undefined;
  }
}

function handleClick() {
  if (longPressTriggered) {
    longPressTriggered = false;
    return;
  }
  emit('click', props.node.id);
}
</script>

<template>
  <div class="workflow-node">
    <button
      type="button"
      class="status-summary-item"
      :class="`status-summary-item--${size}`"
      :style="{
        '--status-summary-bg': palette.bg,
        '--status-summary-border': palette.border,
        '--status-summary-text': palette.text,
      }"
      :aria-label="`${node.label}, ${node.count} 项`"
      @mousedown="startPress"
      @mouseup="cancelPress"
      @mouseleave="cancelPress"
      @touchstart.passive="startPress"
      @touchend="cancelPress"
      @touchcancel="cancelPress"
      @click="handleClick"
    >
      <span class="label">{{ node.label }}</span>
      <span class="count">{{ displayCount }}</span>
    </button>
  </div>
</template>

<style scoped>
.workflow-node {
  min-width: 0;
}

.status-summary-item {
  width: 100%;
  min-height: 52px;
  border: 1px solid var(--el-border-color-lighter, #edf2f7);
  border-left: 3px solid var(--status-summary-border);
  border-radius: 8px;
  background: var(--el-bg-color, #fff);
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  padding: 9px 11px;
  text-align: left;
  transition: border-color 0.16s ease, background-color 0.16s ease, transform 0.16s ease;
  user-select: none;
}

.status-summary-item--sm {
  min-height: 46px;
  padding: 7px 9px;
}

.status-summary-item:hover {
  border-color: var(--status-summary-border);
  background: var(--status-summary-bg);
  transform: translateY(-1px);
}

.status-summary-item:active {
  transform: translateY(0);
}

.status-summary-item:focus-visible {
  outline: 2px solid var(--el-color-primary, #1890ff);
  outline-offset: 2px;
}

.count {
  min-width: 34px;
  height: 28px;
  padding: 0 8px;
  border: 1px solid var(--status-summary-border);
  border-radius: 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--status-summary-bg);
  color: var(--status-summary-text);
  font-size: 15px;
  font-weight: 700;
  line-height: 1;
}

.label {
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary, #1a2332);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
