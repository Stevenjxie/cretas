<template>
  <div class="byproduct-node" :class="{ selected }">
    <Handle type="target" :position="Position.Top" :id="BYP_OVERLAY_TARGET_HANDLE" />

    <div class="byproduct-heading">
      <span class="step-mark">副</span>
      <div>
        <div class="eyebrow">副产 cell</div>
        <div class="byproduct-title">{{ data.outputName }} · 副产</div>
      </div>
    </div>

    <div class="byproduct-subtitle" data-testid="byp-subtitle">{{ subtitleText }}</div>

    <section v-if="data.rows.length > 0" class="byproduct-rows">
      <div
        v-for="row in data.rows"
        :key="row.id"
        class="byproduct-row nodrag"
        @click.stop="canWrite && emit('edit-row', row.id)"
      >
        <span class="row-material-name">{{ row.materialName }}</span>
        <span class="row-markers">
          <span
            v-for="marker in row.markers"
            :key="marker.kind"
            class="row-marker"
            :data-testid="`byp-marker-${marker.kind}`"
            :title="marker.title"
          >{{ marker.glyph }}</span>
        </span>
        <span class="row-qty" :data-testid="`byp-qty-${row.id}`">{{ row.yieldText }}</span>
      </div>
    </section>

    <div v-else class="byproduct-empty" data-testid="byp-empty">
      <div class="empty-headline">0 种 · 未声明</div>
      <!--
        副产是「产出声明」不是投入 —— 空着不影响 BOM 生效
        (后端 validateActivatableItems 只要求整体 ≥1 条明细, 不点名副产),
        所以这里的措辞是「未声明」而不是任何形式的缺失告警。
      -->
      <div class="empty-hint">未声明副产；有下脚料/回收品时在此登记</div>
    </div>

    <button
      v-if="canWrite"
      class="byproduct-add nodrag"
      data-testid="byp-add"
      type="button"
      @click.stop="emit('add-row')"
    >+ 加副产</button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { Handle, Position } from '@vue-flow/core';
import { BYP_OVERLAY_TARGET_HANDLE } from './bomOverlay';
import type { ByproductCellData } from './bomOverlayTypes';

export type { ByproductCellData, ByproductCellRow } from './bomOverlayTypes';

const props = defineProps<{
  data: ByproductCellData;
  selected?: boolean;
  canWrite?: boolean;
}>();

const emit = defineEmits<{
  (event: 'add-row'): void;
  (event: 'edit-row', rowId: string): void;
}>();

const canWrite = computed(() => props.canWrite !== false);

// 分母来自产出 SKU 的基本单位, 禁止硬编码 —— 缺失时上游已占位「未配」,
// 这里不再兜底成「个」之类, 否则会拼出看似合理实则错误的口径。
const subtitleText = computed(() => `${props.data.rows.length} 种 · 每 1 ${props.data.baseUnit}成品`);
</script>

<style scoped>
.byproduct-node {
  min-width: 220px;
  padding: 10px 12px;
  border: 1px dashed #7c8a99;
  border-radius: 8px;
  background: #f6f8fa;
  font-size: 12px;
}
.byproduct-node.selected { border-color: #409eff; }
.byproduct-heading { display: flex; gap: 8px; align-items: center; }
.step-mark {
  display: inline-flex; align-items: center; justify-content: center;
  width: 20px; height: 20px; border-radius: 4px;
  background: #7c8a99; color: #fff; font-size: 12px;
}
.eyebrow { color: #8a94a0; font-size: 11px; }
.byproduct-title { font-weight: 600; color: #303133; }
.byproduct-subtitle { margin-top: 6px; color: #606266; }
.byproduct-rows { margin-top: 6px; display: flex; flex-direction: column; gap: 4px; }
.byproduct-row {
  display: flex; align-items: center; gap: 6px;
  padding: 3px 4px; border-radius: 4px; cursor: pointer;
}
.byproduct-row:hover { background: #eceff3; }
.row-material-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.row-markers { display: inline-flex; gap: 2px; color: #909399; }
.row-qty { color: #303133; font-variant-numeric: tabular-nums; }
.byproduct-empty { margin-top: 6px; color: #909399; }
.empty-headline { color: #606266; }
.empty-hint { margin-top: 2px; font-size: 11px; }
.byproduct-add {
  margin-top: 8px; padding: 2px 6px; border: none; background: none;
  color: #409eff; cursor: pointer; font-size: 12px;
}
</style>
