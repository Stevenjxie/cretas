<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { useCapability } from '@/composables/useCapability';

const props = defineProps<{
  requires: string[];
  cardId: string;
  /** spec v1.2: default 'placeholder' to teach customer what's missing.
   * Use 'hide' for admin/experiment/RBAC-restricted cards. See spec §6.3. */
  fallback?: 'hide' | 'placeholder';
  /** WS2 (2026-06-02): force-render the default slot, bypassing capability
   * gating entirely. Used by the gold-backed dashboard KPI strip — gold
   * aggregation needs no upload-capability check, so it must never show the
   * "此分析需上传含..." placeholder. Default false = normal gating. */
  force?: boolean;
}>();

const router = useRouter();
const { hasAll, missingOf, available, loading, suggestions } = useCapability();

const isLoading = computed(() => !props.force && loading.value && available.value === null);
const satisfied = computed(() => props.force || hasAll(props.requires));
const missing = computed(() => missingOf(props.requires));
const fallbackMode = computed(() => props.fallback ?? 'placeholder');

/** spec v1.2: find the most specific unlock suggestion that would fix this card */
const unlockHint = computed(() => {
  if (missing.value.length === 0) return null;
  for (const s of suggestions.value) {
    if (missing.value.includes(s.missing_field)) return s;
  }
  return null;
});

function handleUnlock() {
  router.push('/smart-bi/upload');
}
</script>

<template>
  <!-- spec S6: loading slot prevents initial-load card flash -->
  <slot v-if="isLoading" name="loading">
    <el-skeleton :rows="3" animated />
  </slot>

  <!-- satisfied → render default slot -->
  <slot v-else-if="satisfied" />

  <!-- placeholder mode (default): show hint + CTA -->
  <div v-else-if="fallbackMode === 'placeholder'" class="capability-placeholder">
    <el-empty
      :description="`此分析需上传含 ${missing.join(' / ')} 的数据`"
      :image-size="80"
    >
      <el-button
        v-if="unlockHint"
        type="primary"
        size="small"
        link
        @click="handleUnlock"
      >
        {{ unlockHint.cta }}
      </el-button>
    </el-empty>
  </div>

  <!-- hide mode: render nothing (card disappears entirely) -->
</template>

<style scoped>
.capability-placeholder {
  border: 1px dashed var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 24px;
  background: var(--el-fill-color-blank);
}
</style>
