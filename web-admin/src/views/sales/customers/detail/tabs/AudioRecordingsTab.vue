<!--
  Sprint 5 F-1 (MVP stub): tab 16 谈话录音 (HJ R-HJ Round 13 §1 item 16).

  现状: stub UI 展示 "即将上线" + 录音文件清单 placeholder + R5 next-action (跳跟踪记录).
  Backend: Sprint 6 wire to /api/mobile/{factoryId}/sales/customer-audio + OSS object storage.

  防呆 R2: header 含 客户名 + 编号.
  防呆 R5: 不 dead-end — 提供"去跟踪记录手工补录"按钮.
-->
<template>
  <div class="audio-tab">
    <div class="header">
      <span class="title">
        谈话录音
        <el-tag v-if="customer" type="info" size="small" class="ctx">
          {{ customer.name }}{{ customer.customerCode ? ` (${customer.customerCode})` : '' }}
        </el-tag>
      </span>
      <el-button :icon="Upload" type="primary" disabled @click="onUploadAttempt">
        上传录音 (Sprint 6+ 上线)
      </el-button>
    </div>

    <el-alert
      title="即将上线"
      type="warning"
      :closable="false"
      show-icon
      class="banner"
    >
      <p>
        谈话录音功能 Sprint 6 上线 — 届时将支持:
      </p>
      <ul>
        <li>录音文件上传 (mp3/wav, OSS 存储)</li>
        <li>录音转文字 (讯飞 ASR 集成)</li>
        <li>录音清单 + 关联跟踪记录</li>
      </ul>
      <p class="hint">当前请通过「跟踪记录」tab 手工记录通话内容.</p>
    </el-alert>

    <el-empty description="暂无录音文件" :image-size="80" class="empty">
      <el-button type="primary" @click="goTracking">去「跟踪记录」补录</el-button>
    </el-empty>
  </div>
</template>

<script setup lang="ts">
import { Upload } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { useRouter, useRoute } from 'vue-router';
import type { Customer } from '@/api/customer';

defineProps<{
  customerId: string;
  customer: Customer | null;
}>();

const router = useRouter();
const route = useRoute();

function onUploadAttempt() {
  // 4 位一体: sticky, 后端 message 风格, 含 next action 提示
  ElMessage({
    message: '谈话录音上传功能 Sprint 6 上线. 当前请用「跟踪记录」tab 手工补录通话内容.',
    type: 'warning',
    duration: 0,
    showClose: true,
  });
}

function goTracking() {
  router.replace({
    name: 'SalesCustomerDetail',
    params: { id: route.params.id },
    query: { ...route.query, tab: 'tracking' },
  });
}
</script>

<style scoped>
.audio-tab {
  padding: 8px 0;
}
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.title {
  font-size: 14px;
  color: var(--el-text-color-secondary);
}
.ctx {
  margin-left: 8px;
}
.banner {
  margin-bottom: 16px;
}
.banner ul {
  margin: 4px 0 8px 20px;
  font-size: 13px;
}
.banner .hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.empty {
  padding: 24px 0;
}
</style>
