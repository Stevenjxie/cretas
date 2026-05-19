<!--
  Sprint 5 F-1 (MVP stub): tab 17 邮件列表 (HJ R-HJ Round 13 §1 item 17).

  现状: stub UI 展示 mock 邮件清单结构 + R5 next-action (跳跟踪记录).
  Backend: Sprint 6 wire to /api/mobile/{factoryId}/sales/customer-emails + IMAP/Exchange 集成.

  防呆 R2: header 含 客户名 + 编号.
  防呆 R5: 不 dead-end — "去跟踪记录手工补录" button.
-->
<template>
  <div class="emails-tab">
    <div class="header">
      <span class="title">
        邮件列表
        <el-tag v-if="customer" type="info" size="small" class="ctx">
          {{ customer.name }}{{ customer.customerCode ? ` (${customer.customerCode})` : '' }}
        </el-tag>
      </span>
      <el-button :icon="Message" type="primary" disabled @click="onSendAttempt">
        发邮件 (Sprint 6+ 上线)
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
        邮件列表功能 Sprint 6 上线 — 届时将支持:
      </p>
      <ul>
        <li>IMAP/Exchange 邮箱接入 (按客户邮箱自动归集)</li>
        <li>发送邮件 + 模板 (报价单 / 对账单 / 催款)</li>
        <li>邮件清单 + 关联订单/报价/合同</li>
      </ul>
      <p class="hint">
        当前请通过「跟踪记录」tab 选 "邮件" 类型手工补录, 内容粘贴邮件主题.
      </p>
    </el-alert>

    <el-empty description="暂无邮件记录" :image-size="80" class="empty">
      <el-button type="primary" @click="goTracking">去「跟踪记录」补录</el-button>
    </el-empty>
  </div>
</template>

<script setup lang="ts">
import { Message } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { useRouter, useRoute } from 'vue-router';
import type { Customer } from '@/api/customer';

defineProps<{
  customerId: string;
  customer: Customer | null;
}>();

const router = useRouter();
const route = useRoute();

function onSendAttempt() {
  // 4 位一体: sticky + 后端 message 风格 + next action
  ElMessage({
    message: '邮件发送功能 Sprint 6 上线 (需 IMAP 配置). 当前请用「跟踪记录」选「邮件」类型手工补录.',
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
.emails-tab {
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
