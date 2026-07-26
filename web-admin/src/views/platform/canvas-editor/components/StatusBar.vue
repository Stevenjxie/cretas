<!-- StatusBar.vue -->
<template>
  <div class="status-bar">
    <div class="status-left">
      <span :class="['status-dot', isComplete ? 'ok' : 'warn']">●</span>
      <span>{{ isComplete ? '配置完整' : '配置不完整' }}</span>
      <span v-if="dirtyCount > 0" class="dirty-label">· {{ dirtyCount }} 项未保存</span>
    </div>
    <div class="status-right">
      <el-button v-if="!hideTechnicalDetails" link size="small" @click="$emit('show-json')">JSON 预览</el-button>
      <el-button link size="small" @click="$emit('show-history')">版本历史</el-button>
      <el-button link size="small" @click="$emit('show-publish-window')">发布窗口</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useCanvasEditor } from '../composables/useCanvasEditor'

const { dirtyCount } = useCanvasEditor()
defineProps<{ isComplete?: boolean; hideTechnicalDetails?: boolean }>()
defineEmits<{ 'show-json': []; 'show-history': []; 'show-publish-window': [] }>()
</script>

<style scoped>
.status-bar {
  height: 32px; display: flex; align-items: center; justify-content: space-between;
  padding: 0 12px; border-top: 1px solid var(--el-border-color); font-size: 11px;
  flex-shrink: 0; color: var(--el-text-color-secondary);
}
.status-left { display: flex; align-items: center; gap: 6px; }
.status-dot.ok { color: var(--el-color-success); }
.status-dot.warn { color: var(--el-color-warning); }
.dirty-label { color: var(--el-color-warning); }
.status-right { display: flex; gap: 4px; }
</style>
