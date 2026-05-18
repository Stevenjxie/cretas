<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: CustomerHeader for detail.vue.
  防呆 R2: sticky header 含 客户名 + 编号 + 评级 + 联系 + 财务 + 当前业务员
  (所有 tab dialog 可读取此 context 渲染 header).
-->
<template>
  <el-card class="customer-header" v-loading="loading" shadow="never">
    <div v-if="customer" class="header-grid">
      <div class="primary">
        <h2 class="name">{{ customer.name }}</h2>
        <div class="meta">
          <el-tag size="small">{{ customer.customerCode }}</el-tag>
          <el-tag v-if="customer.customerType || customer.type" size="small" type="info">
            {{ customer.customerType || customer.type }}
          </el-tag>
          <el-tag v-if="customer.industry" size="small" effect="plain">
            {{ customer.industry }}
          </el-tag>
          <el-rate
            v-if="customer.rating"
            :model-value="customer.rating"
            disabled
            size="small"
          />
        </div>
      </div>

      <div class="contact">
        <div>
          <el-icon><User /></el-icon>
          <span>{{ customer.contactName || customer.contactPerson || '—' }}</span>
        </div>
        <div>
          <el-icon><Phone /></el-icon>
          <span>{{ customer.contactPhone || customer.phone || '—' }}</span>
        </div>
        <div>
          <el-icon><Message /></el-icon>
          <span>{{ customer.contactEmail || customer.email || '—' }}</span>
        </div>
      </div>

      <div class="finance">
        <div>
          <span class="label">余额:</span>
          <strong v-if="canViewPrice">{{ formatMoney(customer.currentBalance) }}</strong>
          <span v-else class="masked">****</span>
        </div>
        <div>
          <span class="label">信用额:</span>
          <strong v-if="canViewPrice">{{ formatMoney(customer.creditLimit) }}</strong>
          <span v-else class="masked">****</span>
        </div>
        <div>
          <span class="label">当前业务员:</span>
          <strong>{{ resolvedSalesUserName }}</strong>
        </div>
      </div>
    </div>
    <el-skeleton v-else :rows="3" animated />
  </el-card>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { User, Phone, Message } from '@element-plus/icons-vue';
import { usePermissionStore } from '@/store/modules/permission';
import { storeToRefs } from 'pinia';
import type { Customer } from '@/api/customer';

const props = defineProps<{
  customer: Customer | null;
  loading?: boolean;
  /** Optional override — if backend customer.assignedSalesUserName is already populated, prop unused. */
  assignedSalesUserName?: string;
}>();

const permissionStore = usePermissionStore();
const { canViewPrice } = storeToRefs(permissionStore);

// Sprint 4 W1 S-CUSTOMER-TAB-1: prefer backend-resolved name from Customer.assignedSalesUserName,
// fall back to legacy prop, then "User #{id}", then "未分配".
const resolvedSalesUserName = computed(() => {
  const fromBackend = props.customer?.assignedSalesUserName;
  if (fromBackend) return fromBackend;
  if (props.assignedSalesUserName) return props.assignedSalesUserName;
  const id = props.customer?.assignedSalesUserId;
  return id ? `User #${id}` : '未分配';
});

function formatMoney(v: number | null | undefined): string {
  if (v == null) return '—';
  return Number(v).toLocaleString('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    minimumFractionDigits: 2,
  });
}
</script>

<style scoped>
.customer-header {
  margin-bottom: 16px;
  position: sticky;
  top: 0;
  z-index: 9;
  background: var(--el-bg-color);
}
.header-grid {
  display: grid;
  grid-template-columns: 1.5fr 1fr 1fr;
  gap: 24px;
}
.name {
  margin: 0 0 8px 0;
  font-size: 18px;
}
.meta {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.contact > div,
.finance > div {
  margin: 4px 0;
  display: flex;
  gap: 6px;
  align-items: center;
  font-size: 13px;
}
.label {
  color: var(--el-text-color-secondary);
}
.masked {
  color: var(--el-text-color-secondary);
  font-family: monospace;
  letter-spacing: 2px;
  user-select: none;
}
</style>
