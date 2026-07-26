<!-- ConfigDiffViewer.vue — Bottom bar: pending changes diff -->
<template>
  <div class="config-diff-viewer">
    <div class="diff-header">
      <strong>待应用变更 ({{ changes.length }})</strong>
      <div>
        <el-button size="small" type="primary" @click="$emit('apply')">全部应用</el-button>
        <el-button size="small" @click="$emit('discard')">放弃</el-button>
      </div>
    </div>
    <div class="diff-list">
      <div v-for="(c, i) in changes" :key="i" class="diff-row">
        <el-tag :type="tagType(c.type)" size="small">{{ c.type }}</el-tag>
        <span>{{ c.description }}</span>
        <template v-if="showTechnicalValues">
          <code v-if="c.before">{{ JSON.stringify(c.before) }}</code>
          <span v-if="c.before && c.after"> → </span>
          <code v-if="c.after">{{ JSON.stringify(c.after) }}</code>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { ConfigDiff } from '@/types/canvas'

withDefaults(
  defineProps<{ changes: ConfigDiff[]; showTechnicalValues?: boolean }>(),
  { showTechnicalValues: true },
)
defineEmits<{ apply: []; discard: [] }>()

function tagType(type: string) {
  if (type.includes('TOGGLE')) return 'warning'
  if (type.includes('CHANGE')) return 'info'
  return 'default'
}
</script>

<style scoped>
.config-diff-viewer { border-top: 2px solid var(--el-border-color); padding: 12px; background: var(--el-fill-color-lighter); max-height: 200px; overflow-y: auto; }
.diff-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.diff-list { display: flex; flex-direction: column; gap: 4px; }
.diff-row { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.diff-row code { background: var(--el-fill-color); padding: 2px 4px; border-radius: 2px; font-size: 11px; }
</style>
