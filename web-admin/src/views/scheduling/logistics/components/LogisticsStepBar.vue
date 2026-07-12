<script setup lang="ts">
import type { LogisticsStep } from '../useLogisticsDemoState';

defineProps<{ activeStep: LogisticsStep }>();

const steps: Array<{ id: LogisticsStep; label: string }> = [
  { id: 'import', label: '导入订单' },
  { id: 'map', label: '查看路线' },
  { id: 'confirm', label: '人工确认' },
  { id: 'export', label: '确认排班' },
];
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
ol { display: flex; min-width: 560px; margin: 0; padding: 0; list-style: none; }
li { display: flex; flex: 1; align-items: center; gap: 8px; color: #667085; font-size: 14px; font-weight: 650; }
li:not(:last-child)::after { flex: 1; height: 1px; margin: 0 12px; background: #d0d5dd; content: ''; }
span { display: grid; width: 24px; height: 24px; place-items: center; color: #667085; background: #f2f4f7; border-radius: 999px; }
.active { color: #1b65a8; }
.active span { color: #fff; background: #1b65a8; }
</style>
