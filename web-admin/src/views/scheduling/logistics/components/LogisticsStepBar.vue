<script setup lang="ts">
import { computed } from 'vue';
import type { LogisticsStep } from '../useLogisticsDemoState';

// hideImport: 从「调度记录」查看已归档(已确认/已导出)计划时, 隐藏第 1 步「导入订单」并重新编号,
// 只留 查看并确认路线 + 确认排班 —— 防止从记录退回导入、改到别的订单撞记录(隔绝)。
const props = defineProps<{ activeStep: LogisticsStep; hideImport?: boolean }>();

// 「查看路线」与「人工确认」已合并为一步(边看图边派车/确认), 4 步 → 3 步。
const allSteps: Array<{ id: LogisticsStep; label: string }> = [
  { id: 'import', label: '导入订单' },
  { id: 'map', label: '查看并确认路线' },
  { id: 'export', label: '确认排班' },
];
const steps = computed(() => (props.hideImport ? allSteps.filter((s) => s.id !== 'import') : allSteps));
</script>

<template>
  <nav class="step-bar" aria-label="排程步骤">
    <ol>
      <li v-for="(step, index) in steps" :key="step.id" :class="{ active: activeStep === step.id }">
        <span>{{ index + 1 }}</span>
        {{ step.label }}
      </li>
    </ol>
  </nav>
</template>

<style scoped lang="scss">
.step-bar { overflow-x: auto; }
ol { display: flex; min-width: 460px; margin: 0; padding: 0; list-style: none; }
li { display: flex; flex: 1; align-items: center; gap: 8px; color: #667085; font-size: 14px; font-weight: 650; }
li:not(:last-child)::after { flex: 1; height: 1px; margin: 0 12px; background: #d0d5dd; content: ''; }
span { display: grid; width: 24px; height: 24px; place-items: center; color: #667085; background: #f2f4f7; border-radius: 999px; }
.active { color: #1b65a8; }
.active span { color: #fff; background: #1b65a8; }
</style>
