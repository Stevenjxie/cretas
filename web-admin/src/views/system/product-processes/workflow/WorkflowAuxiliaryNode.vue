<template>
  <!--
    🔴 2026-08-12 (Steve): 不用辅料的工序, 那个空 Cell 一直挂在上面, 看着像没配完。
    折叠成一条细窄的条 —— 但**不隐藏**: 隐藏之后「已确认不用」和「浮层还没加载出来」
    长得一模一样, 用户没法区分, 也找不到地方恢复。
    ⚠️ 这是**视图偏好**(存本地), 不是业务声明 —— 换个人看仍然会看到完整 Cell。
  -->
  <div
    v-if="collapsed"
    class="aux-node aux-node--collapsed"
    data-testid="aux-collapsed"
  >
    <Handle type="source" :position="Position.Bottom" :id="AUX_OVERLAY_SOURCE_HANDLE" />
    <span class="aux-collapsed-text">{{ data.processName }} · 本工序不用辅料</span>
    <button
      type="button"
      class="aux-collapsed-toggle nodrag"
      data-testid="aux-expand"
      @click.stop="emit('set-collapsed', false)"
    >展开</button>
  </div>

  <div v-else class="aux-node" :class="{ 'is-greyed': usageState !== 'supported' }">
    <Handle type="source" :position="Position.Bottom" :id="AUX_OVERLAY_SOURCE_HANDLE" />

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
      <button
        v-if="canWrite"
        type="button"
        class="aux-detail nodrag"
        title="本工序不用辅料 —— 折叠这个 Cell(只影响你自己的视图, 随时可展开)"
        data-testid="aux-collapse"
        @click.stop="emit('set-collapsed', true)"
      >不用辅料</button>
    </div>

    <div class="aux-subtitle" :class="{ 'is-warning': isEmptySubtitle }">{{ subtitleText }}</div>

    <!-- 联合生产: 这道工序被 >1 份配方共用, 当前展示的是"先到先得"命中的那份 ——
         没有这一行, 用户会以为在编另一个产出的配方(见 task 8 决策)。 -->
    <div v-if="data.sharedAcrossRecipes" class="aux-shared-notice" data-testid="aux-shared-recipe">
      ⚠ 与其它产出共用此工序，当前显示/编辑的是「{{ data.recipeOutputName || '未知产出' }}」的配方
    </div>

    <!-- ⛔ 三态: 「已确认不可换算」(false) 与「尚未确定/无法判断」(null) 必须分开说 ——
         二者渲染成同一句话就是给用户一个代码给不出证据的具体诊断(禁止降级处理)。 -->
    <div v-if="usageState === 'unsupported'" class="aux-greyed-reason" data-testid="aux-greyed-reason">
      标准用量不可用 —— 该工序的投入基准缺少可换算的单位契约，「每 kg 投入」没有分母可算。
      请先到该工序 Cell 的「单位关系」中补上换算契约，再回来配置辅料。
    </div>
    <!-- 🔴 缺席不是否定 (2026-08-10): 这句以前写的是「暂不能新增辅料」, 而 unknown 的真因
         往往在别处(别条工序的目录不符 / 数据没加载 / 该产品还没建过配方) —— 跟"这道工序
         能不能加辅料"没有任何关系。「没有可确认的结论」不能渲染成「已确认不行」, 更不能
         据此把入口关掉: 那是替用户下了一个代码给不出证据的结论。 -->
    <div v-else-if="usageState === 'unknown'" class="aux-greyed-reason aux-unknown-reason" data-testid="aux-unknown-reason">
      尚未建立配方，点「+ 加辅料」即可创建。已配置的辅料仍会照常显示。
    </div>

    <!-- 已配置的行必须无条件渲染 —— 灰态只应该关掉"新增"入口, 不能连已有数据一起藏起来
         (否则一道有 3 种辅料的工序在灰态下会显示成空盒子)。 -->
    <div v-if="data.rows.length === 0 && usageState === 'supported'" class="aux-empty" data-testid="aux-empty">
      <span class="aux-empty-icon">!</span>
      <span>0 种 · 尚未配置辅料</span>
    </div>

    <div v-if="data.rows.length > 0" class="aux-rows">
      <div
        v-for="row in data.rows"
        :key="row.id"
        class="aux-row nodrag"
        role="button"
        tabindex="0"
        @click="canWrite && emit('edit-row', row.id)"
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

    <!-- ⛔ 条件是 `!== 'unsupported'` 不是 `=== 'supported'`: unknown 也放行。
         只有 unsupported 才是**已确认**不可换算 —— 那时代码拿得出证据, 该挡就挡。
         打开后如果真的失败, 由 openAuxiliaryEditor 显示后端给出的真实原因。 -->
    <button
      v-if="canWrite && usageState !== 'unsupported'"
      type="button"
      class="aux-add nodrag"
      data-testid="aux-add"
      @click.stop="emit('add-row')"
    >+ 加辅料</button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { Handle, Position } from '@vue-flow/core';
import { AUX_OVERLAY_SOURCE_HANDLE } from './bomOverlay';
import type { AuxiliaryCellData, AuxiliaryCellRow } from './bomOverlayTypes';

// 沿用 bomOverlayTypes.ts 的唯一权威定义 —— deriveBomOverlay 的返回类型即本组件的 prop
// 类型, 两侧不再各自声明一份互不相干的接口。原地 re-export 保留既有导入路径
// (WorkflowAuxiliaryNode.spec.ts 等文件不需要跟着改导入)。
export type { AuxiliaryCellRow, AuxiliaryCellData } from './bomOverlayTypes';

const props = defineProps<{
  id: string;
  data: AuxiliaryCellData;
  /** 只读用户不给「加辅料」入口 —— 画布层传 canEdit 下来。 */
  canWrite: boolean;
  /** 折叠成一条细窄的条 —— 视图偏好, 由画布层持久化到本地。 */
  collapsed?: boolean;
}>();

const emit = defineEmits<{
  'add-row': [];
  'edit-row': [rowId: string];
  'open-detail': [];
  'set-collapsed': [collapsed: boolean];
}>();

const hasPotMarker = computed(() =>
  props.data.rows.some((row) => row.markers.some((marker) => marker.kind === 'pot')),
);

/**
 * 三态: 'supported' 已确认可换算 / 'unsupported' 已确认不可换算 / 'unknown' 尚未确定。
 * 后两者都灰化 + 关闭"加辅料"入口, 但只有 'unsupported' 能显示具体的"缺换算契约"诊断——
 * 'unknown' 时代码给不出这个结论 (数据未加载 / 加载失败 / 无配方 / 修订节点 id 对不上都
 * 会落到这里, 深入区分见 bomOverlayTypes.ts AuxiliaryCellData.usageSupported 的注释)。
 */
const usageState = computed<'supported' | 'unsupported' | 'unknown'>(() => {
  if (props.data.usageSupported === true) return 'supported';
  if (props.data.usageSupported === false) return 'unsupported';
  return 'unknown';
});

const isEmptySubtitle = computed(() => usageState.value === 'supported' && props.data.rows.length === 0);

const subtitleText = computed(() => {
  if (usageState.value === 'unsupported') return '标准用量不可用';
  if (usageState.value === 'unknown') return '状态尚未确定';
  if (props.data.rows.length === 0) return '0 种 · 未配';
  const base = `${props.data.rows.length} 种`;
  return hasPotMarker.value ? `${base} · 报工需录锅数` : base;
});

function formatDosage(row: AuxiliaryCellRow): string {
  return row.dosageText && row.dosageText.trim() ? row.dosageText : '未填用量';
}
</script>

<style scoped>
/* 折叠态: 一条细窄的条, 高度远小于完整 Cell —— 让它明显是「收起了」而不是「空的」。 */
.aux-node--collapsed {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 10px; min-height: 0;
  border: 1px dashed #e0cfa6; border-radius: 8px; background: #fffdf7;
}
.aux-collapsed-text {
  flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  color: #8a5a17; font-size: 11px;
}
.aux-collapsed-toggle {
  flex: none; border: 1px solid #e0cfa6; border-radius: 5px; background: #fff;
  color: #8a5a17; font-size: 11px; padding: 1px 7px; cursor: pointer;
}
.aux-collapsed-toggle:hover { background: #fdf6e6; }
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
.aux-unknown-reason { background: #eef2f7; color: #5b6b82; font-style: italic; }

.aux-shared-notice {
  margin-top: 8px; padding: 6px 10px; border-radius: 7px;
  border: 1px dashed #d9822b; background: #fff3e0; color: #a3560a;
  font-size: 11px; font-weight: 650; line-height: 1.5;
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
