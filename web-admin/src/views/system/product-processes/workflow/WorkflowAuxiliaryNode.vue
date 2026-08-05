<template>
  <div class="aux-node" :class="{ 'is-greyed': !data.usageSupported }">
    <div class="aux-heading">
      <span class="aux-step-mark">辅</span>
      <div>
        <div class="aux-eyebrow">辅料 cell</div>
        <div class="aux-title">{{ data.processName }} · 辅料</div>
      </div>
      <button
        type="button"
        class="aux-detail nodrag"
        title="打开辅料详情"
        aria-label="打开辅料详情"
        data-testid="aux-open-detail"
        @click.stop="emit('open-detail')"
      >详情</button>
    </div>

    <div class="aux-subtitle" :class="{ 'is-warning': isEmptySubtitle }">{{ subtitleText }}</div>

    <div v-if="!data.usageSupported" class="aux-greyed-reason" data-testid="aux-greyed-reason">
      标准用量不可用 —— 该工序的投入基准缺少可换算的单位契约，「每 kg 投入」没有分母可算。
      请先到该工序 Cell 的「单位关系」中补上换算契约，再回来配置辅料。
    </div>

    <div v-else-if="data.rows.length === 0" class="aux-empty" data-testid="aux-empty">
      <span class="aux-empty-icon">!</span>
      <span>0 种 · 尚未配置辅料</span>
    </div>

    <div v-else class="aux-rows">
      <div
        v-for="row in data.rows"
        :key="row.id"
        class="aux-row nodrag"
        role="button"
        tabindex="0"
        @click="emit('edit-row', row.id)"
      >
        <span class="aux-row-name">{{ row.materialName }}</span>
        <span class="aux-row-markers">
          <span
            v-for="marker in row.markers"
            :key="`${row.id}:${marker.kind}`"
            class="aux-marker"
            :data-testid="`aux-marker-${marker.kind}`"
            :title="marker.title"
          >{{ marker.glyph }}</span>
        </span>
        <span class="aux-row-dosage" :class="{ 'is-placeholder': !row.dosageText }">
          {{ formatDosage(row) }}
        </span>
      </div>
    </div>

    <button
      v-if="data.usageSupported"
      type="button"
      class="aux-add nodrag"
      data-testid="aux-add"
      @click.stop="emit('add-row')"
    >+ 加辅料</button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { BomRowMarker } from './bomOverlayMarkers';

export interface AuxiliaryCellRow {
  id: string;
  materialName: string;
  /** Formatted dosage string, e.g. "2 g/kg". Missing/unresolved dosage must render an
   *  explicit placeholder — never silently fall back to "0" or a blank cell. */
  dosageText?: string | null;
  markers: BomRowMarker[];
}

export interface AuxiliaryCellData {
  processName: string;
  /** false when the process's input basis has no convertible unit contract, so
   *  "every kg of input" has no denominator — the whole cell must grey out and
   *  block adding rows instead of failing later at save time. */
  usageSupported: boolean;
  rows: AuxiliaryCellRow[];
}

const props = defineProps<{
  id: string;
  data: AuxiliaryCellData;
}>();

const emit = defineEmits<{
  'add-row': [];
  'edit-row': [rowId: string];
  'open-detail': [];
}>();

const hasPotMarker = computed(() =>
  props.data.rows.some((row) => row.markers.some((marker) => marker.kind === 'pot')),
);

const isEmptySubtitle = computed(() => props.data.usageSupported && props.data.rows.length === 0);

const subtitleText = computed(() => {
  if (!props.data.usageSupported) return '标准用量不可用';
  if (props.data.rows.length === 0) return '0 种 · 未配';
  const base = `${props.data.rows.length} 种`;
  return hasPotMarker.value ? `${base} · 报工需录锅数` : base;
});

function formatDosage(row: AuxiliaryCellRow): string {
  return row.dosageText && row.dosageText.trim() ? row.dosageText : '未填用量';
}
</script>

<style scoped>
.aux-node {
  position: relative;
  width: 320px;
  padding: 14px;
  border: 1px solid #f3d9a4;
  border-left: 4px solid #d9822b;
  border-radius: 10px;
  background: #fffaf0;
  box-shadow: 0 2px 12px rgba(217, 130, 43, 0.1);
}
.aux-node.is-greyed {
  border-left-color: #c0c4cc;
  background: #f7f7f8;
  box-shadow: none;
}
.aux-heading { display: flex; align-items: flex-start; gap: 8px; }
.aux-heading > div { min-width: 0; flex: 1; }
.aux-step-mark {
  display: grid; place-items: center; width: 28px; height: 28px;
  border-radius: 7px; color: #8a5a17; background: #fbe6c4; font-weight: 700;
}
.aux-eyebrow { color: #a3792f; font-size: 11px; }
.aux-title {
  color: #4a3110; font-size: 15px; font-weight: 700;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.aux-detail {
  flex: 0 0 auto; min-height: 28px; padding: 0 8px;
  border: 1px solid #f3d9a4; border-radius: 6px; background: #fff;
  color: #8a5a17; cursor: pointer; font-size: 12px; font-weight: 600;
}
.aux-detail:hover { border-color: #d9822b; background: #fff3e0; }

.aux-subtitle { margin-top: 6px; color: #8a6116; font-size: 12px; font-weight: 650; }
.aux-subtitle.is-warning { color: #b5590a; }

.aux-greyed-reason {
  margin-top: 8px; padding: 8px 10px; border-radius: 7px;
  background: #f0f1f3; color: #667085; font-size: 12px; line-height: 1.55;
}

.aux-empty {
  display: flex; align-items: center; gap: 6px; margin-top: 8px;
  padding: 8px 10px; border-radius: 7px; border: 1px dashed #f0b93d;
  background: #fff7e8; color: #b5590a; font-size: 12px; font-weight: 600;
}
.aux-empty-icon {
  display: grid; place-items: center; width: 16px; height: 16px; flex: 0 0 auto;
  border-radius: 50%; background: #f0b93d; color: #fff; font-size: 11px; font-weight: 800;
}

.aux-rows { display: flex; flex-direction: column; gap: 4px; margin-top: 8px; }
.aux-row {
  display: grid; grid-template-columns: minmax(0, 1fr) auto minmax(56px, auto);
  align-items: center; gap: 8px; padding: 6px 8px;
  border: 1px solid #f0e2c4; border-radius: 7px; background: #fff;
  cursor: pointer; font-size: 12px;
}
.aux-row:hover { border-color: #d9822b; background: #fff8ec; }
.aux-row-name {
  color: #4a3110; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.aux-row-markers { display: flex; gap: 4px; flex: 0 0 auto; }
.aux-marker {
  display: grid; place-items: center; width: 18px; height: 18px;
  border-radius: 5px; background: #fbe6c4; color: #8a5a17; font-size: 11px; cursor: default;
}
.aux-row-dosage {
  flex: 0 0 auto; text-align: right; font-variant-numeric: tabular-nums;
  color: #4a3110; font-weight: 650;
}
.aux-row-dosage.is-placeholder { color: #a3792f; font-weight: 500; font-style: italic; }

.aux-add {
  width: 100%; margin-top: 10px; padding: 7px 0;
  border: 1px dashed #d9822b; border-radius: 7px; background: #fff8ec;
  color: #b5590a; cursor: pointer; font-size: 12px; font-weight: 650;
}
.aux-add:hover { background: #fff0d6; }
</style>
