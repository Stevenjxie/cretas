<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: tab 14 收件地址 (current).
  显示 Customer.shippingAddress + billingAddress (current single field).
  History 暂未实现 (entity 没有 audit log fields for address changes), 见 spec drift list.
  编辑跳转客户主编辑页 (避免重复表单实现).
-->
<template>
  <div class="ship-addr-tab">
    <div class="toolbar">
      <span class="title">收件 / 开票 地址</span>
      <el-button type="primary" plain @click="goEdit">编辑客户信息</el-button>
    </div>

    <el-descriptions :column="1" border size="default">
      <el-descriptions-item label="收件地址">
        <span v-if="customer?.shippingAddress">{{ customer.shippingAddress }}</span>
        <el-tag v-else type="info" size="small">未填写</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="开票地址">
        <span v-if="customer?.billingAddress">{{ customer.billingAddress }}</span>
        <el-tag v-else type="info" size="small">未填写</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="主要联系电话">
        {{ customer?.contactPhone || customer?.phone || '—' }}
      </el-descriptions-item>
      <el-descriptions-item label="主要联系人">
        {{ customer?.contactName || customer?.contactPerson || '—' }}
      </el-descriptions-item>
    </el-descriptions>

    <el-alert
      type="info"
      :closable="false"
      style="margin-top: 16px"
      title="地址变更历史功能尚未实现 (Sprint 5+) — 当前仅显示最新单值. 修改地址请前往客户主编辑页."
    />
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router';
import type { Customer } from '@/api/customer';

const props = defineProps<{
  customerId: string;
  customer: Customer | null;
}>();

const router = useRouter();

function goEdit() {
  router.push({
    path: '/sales/customers',
    query: { edit: props.customerId },
  });
}
</script>

<style scoped>
.ship-addr-tab { padding: 8px 0; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.toolbar .title { font-size: 14px; color: var(--el-text-color-secondary); }
</style>
