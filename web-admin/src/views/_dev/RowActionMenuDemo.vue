<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { RowActionMenu } from '@/components/list';
import { COMMON_ACTIONS, type RowAction } from '@/types/rowActions';

/**
 * Manual smoke page for RowActionMenu (Day 2 Track H, UX-A2).
 *
 * Not wired into the production router — open via a temporary route or via
 * vite dev server URL `/_dev/row-action-menu` if a route is added.
 */
const aiOn = ref(true);
const includeDanger = ref(true);
const includeDisabled = ref(true);

const actions = computed<RowAction[]>(() => {
  const base: RowAction[] = [
    { ...COMMON_ACTIONS.CONVERT_TO_PRODUCTION },
    { ...COMMON_ACTIONS.CONVERT_TO_PURCHASE },
    { ...COMMON_ACTIONS.CONVERT_TO_OUTSOURCE },
    { ...COMMON_ACTIONS.PRINT_PDF },
    { ...COMMON_ACTIONS.COPY },
    { ...COMMON_ACTIONS.LOCK },
  ];
  if (includeDisabled.value) {
    base.push({
      ...COMMON_ACTIONS.EDIT_PRICE,
      disabled: true,
      disabledReason: '当前角色 (warehouse_manager) 不能改价格',
    });
  }
  if (includeDanger.value) {
    base.push({ ...COMMON_ACTIONS.UNDO_APPROVAL });
    base.push({ ...COMMON_ACTIONS.CANCEL });
    base.push({ ...COMMON_ACTIONS.DELETE });
  }
  return base;
});

const sampleRows = computed(() => [
  { id: 'SO-001', code: 'SO-2026-001', customer: '示例客户 A', amount: '¥12,500' },
  { id: 'SO-002', code: 'SO-2026-002', customer: '示例客户 A', amount: '¥8,200' },
  { id: 'SO-003', code: 'SO-2026-003', customer: '示例客户 B', amount: '¥3,650' },
]);

async function handleAction(actionId: string, row: { id: string; code: string }) {
  const action = actions.value.find((a) => a.id === actionId);
  if (action?.requiresConfirm) {
    try {
      await ElMessageBox.confirm(`确认对 ${row.code} 执行 "${action.label}"?`, '二次确认', {
        type: 'warning',
      });
    } catch {
      return;
    }
  }
  ElMessage.success(`${row.code} → ${actionId}`);
}

function handleAITrigger(row: { id: string; code: string }) {
  ElMessage.info(`AIChat 入口: entityType=salesOrder, entityId=${row.id}`);
}
</script>

<template>
  <div class="demo-root">
    <h2>RowActionMenu 演示 — UX-A2 Track H Day 2</h2>
 <p class="demo-note">列表行末"操作 ▾", 收纳次要动作 + 顶部 " 跟 AI 说" 入口</p>

    <div class="demo-toggles">
      <el-checkbox v-model="aiOn" label="显示 AI 入口" size="small" />
      <el-checkbox v-model="includeDanger" label="包含 danger 动作" size="small" />
      <el-checkbox v-model="includeDisabled" label="包含 disabled (改价)" size="small" />
    </div>

    <el-table :data="sampleRows" border style="margin-top: 16px;">
      <el-table-column prop="code" label="单号" width="160" />
      <el-table-column prop="customer" label="客户" />
      <el-table-column prop="amount" label="金额" width="120" />
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <RowActionMenu
            :actions="actions"
            :ai-trigger-enabled="aiOn"
            @action-click="(id: string) => handleAction(id, row)"
            @ai-trigger="() => handleAITrigger(row)"
          />
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.demo-root {
  padding: 24px;
}
.demo-note {
  color: var(--el-text-color-secondary);
  margin-bottom: 16px;
}
.demo-toggles {
  display: flex;
  gap: 16px;
  align-items: center;
}
</style>
