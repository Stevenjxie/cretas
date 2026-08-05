<template>
  <div class="packaging-node" :class="{ selected }">
    <Handle type="target" :position="Position.Left" :id="PACK_OVERLAY_TARGET_HANDLE" />

    <div class="packaging-heading">
      <span class="step-mark">包</span>
      <div>
        <div class="eyebrow">包材 cell</div>
        <div class="packaging-title">{{ data.outputName }} · 包材</div>
      </div>
    </div>

    <div class="packaging-subtitle" data-testid="pack-subtitle">{{ subtitleText }}</div>

    <section v-if="data.rows.length > 0" class="packaging-rows">
      <div
        v-for="row in data.rows"
        :key="row.id"
        class="packaging-row nodrag"
        @click.stop="canWrite && emit('edit-row', row.id)"
      >
        <span class="row-material-name">{{ row.materialName }}</span>
        <span class="row-markers">
          <span
            v-for="marker in row.markers"
            :key="marker.kind"
            class="row-marker"
            :data-testid="`pack-marker-${marker.kind}`"
            :title="marker.title"
          >{{ marker.glyph }}</span>
        </span>
        <span
          class="row-qty"
          :data-testid="`pack-qty-${row.id}`"
          :title="row.naturalHint || undefined"
        >{{ quantityDisplay(row) }}</span>
      </div>
    </section>

    <div v-else class="packaging-empty" data-testid="pack-empty">
      <div class="empty-headline">0 种 · 未配</div>
      <div class="empty-consequence">缺包材，本条工艺发布不了</div>
    </div>

    <button
      v-if="canWrite"
      type="button"
      class="packaging-add nodrag"
      data-testid="pack-add"
      @click.stop="emit('add-row')"
    >+ 添加包材</button>
  </div>
</template>

<script setup lang="ts">
/**
 * 包材 cell —— BOM 浮层节点, 渲染一个终端产出的包材配置。
 *
 * 两条不可读错的约束(详见 task-5-brief.md):
 * 1. 副标题的分母必须来自 data.baseUnit, 绝不能写死「盒」——按重量卖的
 *    副产品基本单位是 kg,「1 个/盒」和「1 个/kg」不是一回事。
 * 2. 换算过的用量(dosageText, 如「0.05 个/kg」)对仓管毫无意义,原始表达
 *    (naturalHint, 如「= 1 个 / 20 kg」)必须留在 title 里；没有 naturalHint
 *    时不设 title,而不是设成空串——空 tooltip 比没有 tooltip 更具误导性。
 */
import { computed } from 'vue';
import { Handle, Position } from '@vue-flow/core';
import { PACK_OVERLAY_TARGET_HANDLE } from './bomOverlay';
import type { PackagingCellData } from './bomOverlayTypes';

// 沿用 bomOverlayTypes.ts 的唯一权威定义(理由同 WorkflowAuxiliaryNode.vue)。
// 原地 re-export 保留既有导入路径 —— WorkflowPackagingNode.spec.ts 从本文件
// `import type { PackagingCellData } from '../WorkflowPackagingNode.vue'` 不需要改。
export type { PackagingCellRow, PackagingCellData } from './bomOverlayTypes';

const props = defineProps<{
  id: string;
  data: PackagingCellData;
  selected?: boolean;
  /** 只读用户不给「添加包材」/编辑行入口 —— 画布层传 canEdit 下来。 */
  canWrite: boolean;
}>();

const emit = defineEmits<{
  'add-row': [];
  'edit-row': [rowId: string];
}>();

// 多层包装(内袋/零售盒/外箱等)是同一 SKU 包装方案的若干层, 不是 N 个互不相干
// 的物料——带 lvl 标记的行才计入「层」。
const levelRows = computed(() =>
  props.data.rows.filter((row) => row.markers.some((marker) => marker.kind === 'lvl')),
);

const subtitleText = computed(() => {
  const denominator = `每 1 ${props.data.baseUnit}成品`;
  if (levelRows.value.length > 0) {
    return `分 ${levelRows.value.length} 层 · ${denominator}`;
  }
  return `${props.data.rows.length} 种 · ${denominator}`;
});

// 禁止降级处理: 用量缺失时给出明确占位, 不能悄悄显示 0 或空白。
function quantityDisplay(row: PackagingCellRow): string {
  return row.dosageText && row.dosageText.trim() ? row.dosageText : '用量待补全';
}
</script>

<style scoped>
.packaging-node {
  position: relative;
  width: 380px;
  padding: 14px;
  border: 1px solid #e3bccb;
  border-left: 4px solid #8e3454;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 2px 12px rgba(142, 52, 84, 0.1);
}
.packaging-node.selected { box-shadow: 0 0 0 2px rgba(142, 52, 84, 0.3); }
.packaging-heading { display: flex; align-items: flex-start; gap: 8px; }
.packaging-heading > div { min-width: 0; flex: 1; }
.step-mark {
  display: grid; place-items: center; width: 28px; height: 28px; border-radius: 7px;
  color: #8e3454; background: #f8e9ee; font-weight: 700;
}
.eyebrow { color: #7a8599; font-size: 11px; }
.packaging-title { color: #1a2332; font-size: 15px; font-weight: 700; }
.packaging-subtitle {
  margin-top: 6px; padding: 2px 8px; width: fit-content; border-radius: 999px;
  background: #f8e9ee; color: #8e3454; font-size: 11px; font-weight: 650;
}
.packaging-rows { margin-top: 12px; padding-top: 10px; border-top: 1px solid #f3e2e8; display: flex; flex-direction: column; gap: 6px; }
.packaging-row {
  display: grid; grid-template-columns: minmax(0, 1fr) auto auto; align-items: center; gap: 8px;
  padding: 6px 8px; border: 1px solid #f3e2e8; border-radius: 7px; background: #fdf8fa;
  cursor: pointer;
}
.packaging-row:hover { border-color: #e3bccb; background: #fbeef2; }
.row-material-name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #475467; font-size: 12px; }
.row-markers { display: flex; gap: 3px; }
.row-marker {
  display: grid; place-items: center; width: 18px; height: 18px; border-radius: 5px;
  background: #f8e9ee; color: #8e3454; font-size: 11px; cursor: help;
}
.row-qty { color: #344054; font-size: 12px; font-weight: 650; white-space: nowrap; }
.packaging-empty {
  margin-top: 12px; padding: 10px; border-radius: 7px; background: #fdf1f1;
  display: flex; flex-direction: column; gap: 3px;
}
.empty-headline { color: #667085; font-size: 12px; font-weight: 650; }
.empty-consequence { color: #c0304a; font-size: 12px; font-weight: 700; }
.packaging-add {
  margin-top: 10px; width: 100%; padding: 6px 0; border: 1px dashed #d38aa2; border-radius: 7px;
  background: #fff; color: #8e3454; font-size: 12px; font-weight: 650; cursor: pointer;
}
.packaging-add:hover { background: #fbeef2; border-color: #8e3454; }
</style>
