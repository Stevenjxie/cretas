<!-- web-admin/src/components/common/UpstreamMissingHint.vue -->
<script setup lang="ts">
/**
 * UpstreamMissingHint — 上游依赖缺失的空状态引导（fool-proof Rule 5）。
 * 有目标模块权限 → 显示「去创建」按钮（emit action）；无权限 → 显示「联系谁」提示。
 * 配合 useCreateAndReturn 使用。spec: docs/superpowers/specs/2026-06-22-create-and-return-upstream-pattern-design.md
 */
import { computed } from 'vue';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';

const props = withDefaults(defineProps<{
  description: string;
  targetModule: string;
  actionText: string;
  contactText: string;
  requireWrite?: boolean;
}>(), { requireWrite: false });

const emit = defineEmits<{ (e: 'action'): void }>();

const { canReach } = useCreateAndReturn();
const allowed = computed(() => canReach(props.targetModule, { write: props.requireWrite }));
</script>

<template>
  <div class="upstream-missing-hint">
    <span class="upstream-missing-hint__desc">{{ description }}</span>
    <el-button
      v-if="allowed"
      type="primary"
      link
      size="small"
      @click="emit('action')"
    >{{ actionText }} →</el-button>
    <span v-else class="upstream-missing-hint__contact">{{ contactText }}</span>
  </div>
</template>

<style scoped>
.upstream-missing-hint {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 8px 0; font-size: 13px;
}
.upstream-missing-hint__desc { color: var(--el-color-warning-dark-2, #e6a23c); }
.upstream-missing-hint__contact { color: var(--el-text-color-secondary, #909399); }
</style>
