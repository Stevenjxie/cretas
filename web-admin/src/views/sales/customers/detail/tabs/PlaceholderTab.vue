<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: 通用 placeholder for 8 defer tab + tab 19 (Chat B pending).
  防呆 R5: 不 dead-end — 必带 next action button (跳替代 tab) per fool-proof-design.md.
-->
<template>
  <div class="placeholder-tab">
    <el-empty :image-size="120">
      <template #description>
        <p class="title">「{{ tabName }}」{{ status }}</p>
        <p v-if="workaroundHint" class="hint">{{ workaroundHint }}</p>
      </template>
      <el-button
        v-if="actionText"
        type="primary"
        @click="onAction"
      >
        {{ actionText }}
      </el-button>
    </el-empty>
  </div>
</template>

<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router';

const props = defineProps<{
  tabName: string;
  status: string;
  workaroundHint?: string;
  actionText?: string;
  /** Either an internal tab key (to switch within detail page) or a full route location object. */
  actionTabKey?: string;
}>();

const router = useRouter();
const route = useRoute();

function onAction() {
  if (props.actionTabKey) {
    router.replace({
      name: 'SalesCustomerDetail',
      params: { id: route.params.id },
      query: { ...route.query, tab: props.actionTabKey },
    });
  }
}
</script>

<style scoped>
.placeholder-tab {
  padding: 48px 16px;
}
.title {
  font-size: 16px;
  margin-bottom: 8px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
