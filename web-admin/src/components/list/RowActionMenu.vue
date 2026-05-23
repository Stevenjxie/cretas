<script setup lang="ts">
import { computed } from 'vue';
import type { RowAction } from '@/types/rowActions';

/**
 * Web-admin "操作 ▾" dropdown for list rows. Mirrors the RN
 * RowActionBottomSheet API so useRowActions composables can feed both.
 *
 * Props:
 *  - actions:        array filtered by useRowActions (status + RBAC)
 *  - aiTriggerEnabled: prepend the "💬 跟 AI 说" entry
 *  - aiTriggerLabel:   override that entry's label
 *  - buttonLabel:      override "操作" on the trigger button
 *  - size:             el-button size, defaults to "small"
 *
 * Emits:
 *  - action-click: <id>  user picked an action (caller dispatches)
 *  - ai-trigger:         user picked the AI entry (caller opens AIChat)
 */
const props = withDefaults(
  defineProps<{
    actions: RowAction[];
    aiTriggerEnabled?: boolean;
    aiTriggerLabel?: string;
    buttonLabel?: string;
    size?: 'large' | 'default' | 'small';
  }>(),
  {
    aiTriggerEnabled: true,
 aiTriggerLabel: ' 跟 AI 说...',
    buttonLabel: '操作',
    size: 'small',
  }
);

const emit = defineEmits<{
  (e: 'action-click', actionId: string): void
  (e: 'ai-trigger'): void
}>();

const hasActions = computed(() => props.actions.length > 0);

function handleCommand(cmd: string | number | object) {
  if (cmd === '__ai__') {
    emit('ai-trigger');
    return;
  }
  if (typeof cmd === 'string') {
    // Safety: el-dropdown should suppress @command for disabled items, but
    // double-check pending here and no-op rather than fire a "(待接 API)" toast
    // if the dropdown ever calls @command for a disabled entry.
    const action = props.actions.find((a) => a.id === cmd);
    if (action?.pending) return;
    emit('action-click', cmd);
  }
}
</script>

<template>
  <el-dropdown
    trigger="click"
    placement="bottom-end"
    :disabled="!hasActions && !aiTriggerEnabled"
    @command="handleCommand"
  >
    <el-button :size="size">
      {{ buttonLabel }}
      <span class="row-action-menu__caret" aria-hidden="true">▾</span>
    </el-button>

    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item
          v-if="aiTriggerEnabled"
          command="__ai__"
          divided
        >
          <span class="row-action-menu__ai">{{ aiTriggerLabel }}</span>
        </el-dropdown-item>

        <template v-if="hasActions">
          <el-dropdown-item
            v-for="action in actions"
            :key="action.id"
            :command="action.id"
            :disabled="action.disabled || action.pending"
            :class="{
              'row-action-menu__item--danger': action.danger,
              'row-action-menu__item--pending': action.pending,
            }"
            :title="action.pending
              ? '此功能 API 开发中, 暂未上线'
              : (action.disabled ? action.disabledReason : undefined)"
          >
            <span class="row-action-menu__icon" aria-hidden="true">{{ action.icon }}</span>
            <span class="row-action-menu__label">{{ action.label }}</span>
            <span
              v-if="action.pending"
              class="row-action-menu__pending-tag"
            >(开发中)</span>
            <span
              v-else-if="action.requiresConfirm"
              class="row-action-menu__confirm-hint"
            >需确认</span>
          </el-dropdown-item>
        </template>

        <el-dropdown-item v-else disabled>
          <span class="row-action-menu__empty">无可用操作</span>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
.row-action-menu__caret {
  margin-left: 4px;
  font-size: 10px;
  color: var(--el-text-color-regular);
}
.row-action-menu__ai {
  color: var(--el-color-primary);
  font-weight: 500;
}
.row-action-menu__icon {
  display: inline-block;
  width: 20px;
  margin-right: 8px;
  text-align: center;
}
.row-action-menu__label {
  flex: 1;
}
.row-action-menu__confirm-hint {
  margin-left: 12px;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}
.row-action-menu__empty {
  color: var(--el-text-color-placeholder);
  font-size: 13px;
}
.row-action-menu__item--danger :deep(.el-dropdown-menu__item) {
  color: var(--el-color-danger);
}
.row-action-menu__item--danger {
  color: var(--el-color-danger);
}
.row-action-menu__pending-tag {
  margin-left: 8px;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}
</style>
