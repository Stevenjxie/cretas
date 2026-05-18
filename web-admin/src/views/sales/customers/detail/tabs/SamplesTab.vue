<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: tab 8 样品单 (customer 维度).

  Spec drift #6: ProductSample 实体没有 customerId 字段 (链 rd_request_id → RDRequest),
  Phase C 跳过未提供 /by-customer endpoint. 本 tab 暂渲染 placeholder 提示用户.

  防呆 R5: 给 next-action 跳到通用 samples 页 (web-admin/src/views/sales/customer-tracking 没有
  全样品页, 给 next-action 到 /sales/customers list 退路).
-->
<template>
  <div class="samples-tab">
    <el-empty :image-size="120">
      <template #description>
        <p class="title">「样品单」当前未关联到此客户档案</p>
        <p class="hint">
          ProductSample 数据模型目前通过 RD 需求单关联客户, 不直接挂客户档案.
          客户级别的样品筛选预计 Sprint 5+ 上线 (扩展 RDRequest.customerId 反向关联).
        </p>
        <p class="hint">当前查阅样品请前往研发样品列表页面.</p>
      </template>
      <el-button type="primary" plain disabled>敬请期待</el-button>
    </el-empty>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  customerId: string;
}>();
</script>

<style scoped>
.samples-tab {
  padding: 48px 16px;
}
.title {
  font-size: 16px;
  margin-bottom: 8px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin: 4px 0;
  max-width: 480px;
}
</style>
