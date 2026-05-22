<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { useCapability } from '@/composables/useCapability';

const router = useRouter();
const { suggestions, available } = useCapability();

const visibleSuggestions = computed(() => suggestions.value.slice(0, 3));
const availableCount = computed(() => available.value?.size ?? 0);

function handleUpload() {
  router.push('/smart-bi/upload');
}
</script>

<template>
  <el-card v-if="visibleSuggestions.length > 0" class="unlock-cta">
    <template #header>
      <span>解锁更多分析</span>
    </template>

    <p class="current-state">
      你当前数据支持 {{ availableCount }} 个 canonical 字段.
    </p>

    <div class="suggestions">
      <div
        v-for="(s, i) in visibleSuggestions"
        :key="i"
        class="suggestion-item"
      >
        <strong>{{ s.cta }}</strong>
        <p class="examples">解锁: {{ s.unlocks_examples.slice(0, 3).join(' / ') }}</p>
      </div>
    </div>

    <el-button type="primary" @click="handleUpload">一键上传</el-button>
  </el-card>
</template>

<style scoped>
.unlock-cta {
  margin-top: 24px;
}
.current-state {
  color: var(--el-text-color-secondary);
  margin-bottom: 16px;
}
.suggestion-item {
  padding: 12px 0;
  border-bottom: 1px solid var(--el-border-color-extra-light);
}
.suggestion-item:last-child { border-bottom: none; }
.examples {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: 4px 0 0;
}
</style>
