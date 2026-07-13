<script setup lang="ts">
/**
 * 运力诊断防呆条 —— 计划生成后展示"车队单轮运力够不够送完本批订单"，
 * 具体数字 + next action（fool-proof-design.md Rule 1/2/5：预先显示边界、带上下文数字、
 * 不留 dead-end，够/不够都要给一句明确结论 + 该做什么）。
 *
 * 三种 verdict 对应 el-alert 的 success/warning/error 三色；
 * INSUFFICIENT/UNSERVABLE 额外给一个"去管理车辆"按钮（对齐 fool-proof-design Rule 5，
 * 跳到车辆资源页补运力/补区域覆盖，不是一个说了等于没说的空提示）。
 */
import { computed } from 'vue';
import type { CapacityDiagnosis } from '@/api/logistics';

const props = defineProps<{ diagnosis: CapacityDiagnosis | null }>();
const emit = defineEmits<{ (e: 'manage-vehicles'): void }>();

const alertType = computed<'success' | 'warning' | 'error'>(() => {
  switch (props.diagnosis?.verdict) {
    case 'SUFFICIENT':
      return 'success';
    case 'INSUFFICIENT':
      return 'warning';
    case 'UNSERVABLE':
    default:
      return 'error';
  }
});

const showNextAction = computed(() => props.diagnosis?.verdict === 'INSUFFICIENT' || props.diagnosis?.verdict === 'UNSERVABLE');

function manageVehicles(): void {
  emit('manage-vehicles');
}
</script>

<template>
  <el-alert
    v-if="diagnosis"
    data-testid="capacity-diagnosis-banner"
    :data-verdict="diagnosis.verdict"
    class="capacity-diagnosis-banner"
    :type="alertType"
    :closable="alertType === 'success'"
    show-icon
  >
    <template #title>
      <div class="cdb-body">
        <span class="cdb-message">{{ diagnosis.message }}</span>
        <button
          v-if="showNextAction"
          type="button"
          data-testid="capacity-diagnosis-action"
          class="cdb-action"
          @click="manageVehicles"
        >去管理车辆</button>
      </div>
    </template>
  </el-alert>
</template>

<style scoped lang="scss">
.capacity-diagnosis-banner { align-items: center; }
.capacity-diagnosis-banner :deep(.el-alert__content) { width: 100%; }
.cdb-body { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; width: 100%; }
.cdb-message { flex: 1 1 320px; font-size: 13.5px; font-weight: 600; line-height: 1.5; }
.cdb-action {
  flex: 0 0 auto;
  padding: 6px 14px;
  color: #fff;
  font: inherit;
  font-size: 13px;
  font-weight: 650;
  white-space: nowrap;
  background: #1b65a8;
  border: 0;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s ease;
}
.cdb-action:hover { background: #14507f; }
</style>
