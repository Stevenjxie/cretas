<script setup lang="ts">
import { ref } from 'vue';
import {
  type RowMarkerColor,
  MARKER_COLORS,
  MARKER_COLOR_HEX,
  MARKER_COLOR_LABELS,
} from '@/types/rowMarker';

/**
 * U-MARKER-1 — 5-color row marker cell + popover picker.
 *
 * Renders a small color dot (current marker) that opens an el-popover with
 * 5 color choices + "清除" option on click. Emits @select with the chosen
 * color (or null to clear). Parent handles the API call.
 */
const props = defineProps<{
  value: RowMarkerColor | null | undefined;
  /** Disable picker (read-only display). */
  readonly?: boolean;
}>();

const emit = defineEmits<{
  (e: 'select', color: RowMarkerColor | null): void;
}>();

const popoverVisible = ref(false);

function pick(color: RowMarkerColor | null): void {
  emit('select', color);
  popoverVisible.value = false;
}
</script>

<template>
  <el-popover
    v-model:visible="popoverVisible"
    :disabled="readonly"
    placement="bottom"
    trigger="click"
    :width="220"
  >
    <template #reference>
      <span
        class="row-marker-dot"
        :class="{ readonly: readonly, empty: !value }"
        :style="value ? { backgroundColor: MARKER_COLOR_HEX[value] } : {}"
        :aria-label="value ? MARKER_COLOR_LABELS[value] : '无标记'"
        role="button"
        :tabindex="readonly ? -1 : 0"
      />
    </template>
    <div class="row-marker-picker">
      <button
        v-for="color in MARKER_COLORS"
        :key="color"
        type="button"
        class="row-marker-swatch"
        :style="{ backgroundColor: MARKER_COLOR_HEX[color] }"
        :title="MARKER_COLOR_LABELS[color]"
        :aria-label="MARKER_COLOR_LABELS[color]"
        @click="pick(color)"
      />
      <button
        type="button"
        class="row-marker-swatch clear"
        title="清除标记"
        aria-label="清除标记"
        @click="pick(null)"
      >
        
      </button>
    </div>
  </el-popover>
</template>

<style scoped>
.row-marker-dot {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 1px solid var(--el-border-color);
  cursor: pointer;
  vertical-align: middle;
  transition: transform 0.12s ease, box-shadow 0.12s ease;
}
.row-marker-dot.empty {
  background: transparent;
}
.row-marker-dot:hover:not(.readonly) {
  transform: scale(1.2);
  box-shadow: 0 0 0 2px var(--el-color-primary-light-7);
}
.row-marker-dot.readonly {
  cursor: default;
}
.row-marker-picker {
  display: flex;
  gap: 6px;
  align-items: center;
  justify-content: center;
}
.row-marker-swatch {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: 1px solid var(--el-border-color);
  cursor: pointer;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: white;
  transition: transform 0.12s ease;
}
.row-marker-swatch:hover {
  transform: scale(1.15);
}
.row-marker-swatch.clear {
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
}
</style>
