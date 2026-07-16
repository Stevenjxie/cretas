<template>
  <div
    class="material-node"
    :class="[`kind-${kind.toLowerCase()}`, { selected, 'wf-dim': isConnectDimmed, 'wf-valid': isValidConnectTarget, 'unit-error': !!unitError }]"
  >
    <Handle v-if="kind !== 'RAW_MATERIAL'" type="target" :position="Position.Left" id="input" />
    <Handle v-if="kind !== 'FINISHED_GOOD'" type="source" :position="Position.Right" id="output" />

    <button
      v-if="canWrite && selected"
      type="button"
      class="cell-delete nodrag"
      title="删除此 Cell"
      data-testid="delete-material-cell"
      @click.stop="emit('delete')"
    >✕ 删除</button>

    <div class="node-heading">
      <span class="kind-mark">{{ kindMark }}</span>
      <div>
        <div class="kind-label">{{ kindLabel }}</div>
        <div class="material-name">{{ data.name || '未命名物料' }}</div>
      </div>
      <el-tag size="small" :type="data.skuId ? 'success' : 'warning'">
        {{ data.skuId ? '已绑定' : '待绑定' }}
      </el-tag>
      <button
        v-if="canWrite && data.skuId && (kind === 'SEMI_FINISHED' || kind === 'FINISHED_GOOD')"
        type="button"
        class="quick-edit nodrag"
        title="快捷修改该 SKU"
        aria-label="快捷修改 SKU"
        @click.stop="emit('editSku')"
      >✎</button>
    </div>

    <div class="identity-row">
      <span>SKU</span>
      <strong>{{ data.skuCode || data.skuId || '尚未选择' }}</strong>
    </div>
    <div v-if="data.specification" class="specification">{{ data.specification }}</div>
    <div v-if="unitError" class="unit-error-message" data-testid="unit-error">
      {{ unitError }}
    </div>

    <el-select
      v-if="kind === 'RAW_MATERIAL' && canWrite"
      class="nodrag nowheel raw-selector"
      :model-value="data.skuId"
      placeholder="选择入口原料 SKU"
      filterable
      size="small"
      :filter-method="handleRawFilter"
      @visible-change="handleRawVisibleChange"
      @change="(value: string) => emit('selectRawSku', value)"
    >
      <el-option-group v-if="bomRawOptions.length > 0" label="本产品 BOM 原料" data-testid="bom-raw-group">
        <el-option
          v-for="option in filteredBomRawOptions"
          :key="option.id"
          :label="`${option.code ? `${option.code} — ` : ''}${option.name} · ${option.unit || '-'}`"
          :value="option.id"
        />
      </el-option-group>
      <el-option-group :label="otherRawGroupLabel" data-testid="other-raw-group">
        <el-option
          v-for="option in filteredOtherRawOptions"
          :key="option.id"
          :label="`${option.code ? `${option.code} — ` : ''}${option.name} · ${option.unit || '-'}`"
          :value="option.id"
        />
      </el-option-group>
    </el-select>
    <WorkflowSkuPicker
      v-if="(kind === 'SEMI_FINISHED' || kind === 'FINISHED_GOOD') && canWrite"
      class="sku-selector"
      test-id="material-sku-select"
      :model-value="data.skuId"
      :semi-options="semiOptions"
      :finished-options="finishedOptions"
      @change="(value) => emit('selectSku', value)"
    />

    <div v-if="kind !== 'FINISHED_GOOD' && canWrite" class="node-actions nodrag">
      <el-button size="small" text type="primary" @click="emit('addNext')">+ 后续工序</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { Handle, Position } from '@vue-flow/core';
import { usePinyinFilter } from './pinyinInitials';
import WorkflowSkuPicker, { type WorkflowSkuPickerOption } from './WorkflowSkuPicker.vue';
import type { MaterialNodeData, ProductProcessNodeKind } from './types';

const props = withDefaults(defineProps<{
  kind: Exclude<ProductProcessNodeKind, 'PROCESS'>;
  data: MaterialNodeData;
  selected?: boolean;
  canWrite: boolean;
  /** #8: 拖拽连线中源 cell 的类型；用于给非法目标 cell 灰化 */
  connectingFromKind?: '' | 'MATERIAL' | 'PROCESS';
  rawMaterialOptions: Array<{ id: string; name: string; code?: string; unit?: string }>;
  /**
   * #3 (Steve 定: BOM 原料优先、可加其他): 该产品 BOM 原辅料清单里出现过的
   * 原料 SKU id 集合 (RawMaterialType.id，与 BomItem.materialTypeId 同一业务
   * 键)。由父组件 ProductProcessWorkflowEditor 调用
   * GET /{factoryId}/bom/items/{productTypeId} 拿到后传下来 —— 该端口在
   * BomController 中已确认存在 (com.cretas.aims.controller.BomController
   * #getBomItems)，不需要走"接口缺失降级"分支。
   */
  bomRawMaterialIds?: string[];
  semiOptions: WorkflowSkuPickerOption[];
  finishedOptions: WorkflowSkuPickerOption[];
  unitError?: string;
}>(), {
  connectingFromKind: '',
  bomRawMaterialIds: () => [],
});

// #8: 物料 Cell 只有在「工序拖向物料(产出)」且自身是半成品/成品时才是合法目标；
// 「物料拖向工序」时物料互相之间都非法 → 灰化。
const isValidConnectTarget = computed(() => props.connectingFromKind === 'PROCESS'
  && (props.kind === 'SEMI_FINISHED' || props.kind === 'FINISHED_GOOD'));
const isConnectDimmed = computed(() => !!props.connectingFromKind && !isValidConnectTarget.value);

const emit = defineEmits<{
  addNext: [];
  selectRawSku: [skuId: string];
  selectSku: [skuId: string];
  delete: [];
  configBom: [];
  editSku: [];
}>();

// #3 原料 Cell = BOM 原料优先、可加其他 (soft 约束，Steve 定：BOM 优先但不硬
// 禁其它)。把候选原料拆成「本产品 BOM 原料」+「其它原料」两组，BOM 组置顶,
// 两组各自独立跑拼音首字母搜索 (#2，复用 usePinyinFilter 共享 composable)。
const bomRawMaterialIdSet = computed(() => new Set(props.bomRawMaterialIds));
const bomRawOptions = computed(() => props.rawMaterialOptions
  .filter((option) => bomRawMaterialIdSet.value.has(option.id)));
const otherRawOptions = computed(() => props.rawMaterialOptions
  .filter((option) => !bomRawMaterialIdSet.value.has(option.id)));
// BOM 为空时不硬禁选其它原料 (fool-proof-design Rule 5: 不留死胡同)，只是不再
// 有"其它"和"BOM"的区分，组名相应改成「全部原料」避免暗示一个空的 BOM 分组。
const otherRawGroupLabel = computed(() => (bomRawOptions.value.length > 0 ? '其它原料' : '全部原料'));

const bomRawFilter = usePinyinFilter(() => bomRawOptions.value, (option) => [option.name, option.code]);
const otherRawFilter = usePinyinFilter(() => otherRawOptions.value, (option) => [option.name, option.code]);

function handleRawFilter(query: string): void {
  bomRawFilter.handleFilter(query);
  otherRawFilter.handleFilter(query);
}

function handleRawVisibleChange(visible: boolean): void {
  bomRawFilter.handleVisibleChange(visible);
  otherRawFilter.handleVisibleChange(visible);
}

const filteredBomRawOptions = bomRawFilter.filtered;
const filteredOtherRawOptions = otherRawFilter.filtered;

const kindLabel = computed(() => ({
  RAW_MATERIAL: '原料 Cell',
  SEMI_FINISHED: '半成品 Cell',
  FINISHED_GOOD: '成品 Cell',
})[props.kind]);

const kindMark = computed(() => ({
  RAW_MATERIAL: '原',
  SEMI_FINISHED: '半',
  FINISHED_GOOD: '成',
})[props.kind]);
</script>

<style scoped>
/* #8 拖拽连线视觉: 灰化非法目标 / 高亮合法目标 / handle 悬停显现. 仅 opacity/transform. */
.material-node { transition: opacity 150ms ease, box-shadow 150ms ease; }
.material-node.wf-dim { opacity: 0.4; cursor: not-allowed; }
.material-node.wf-valid { box-shadow: 0 0 0 2px #1b65a8, 0 0 12px rgba(27, 101, 168, 0.35); }
.material-node.unit-error { box-shadow: 0 0 0 2px #f56c6c; }
.unit-error-message {
  margin: 8px 0;
  padding: 6px 8px;
  color: #b42318;
  background: #fef3f2;
  border: 1px solid #fecdca;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.4;
}
.material-node :deep(.vue-flow__handle) {
  width: 12px; height: 12px; opacity: 0.35;
  transition: opacity 150ms ease, transform 120ms ease;
}
.material-node:hover :deep(.vue-flow__handle),
.material-node.wf-valid :deep(.vue-flow__handle) { opacity: 1; }
.material-node :deep(.vue-flow__handle):hover { transform: scale(1.3); cursor: crosshair; }
@media (prefers-reduced-motion: reduce) {
  .material-node, .material-node :deep(.vue-flow__handle) { transition: none; }
}
/* #9 删除 Cell 按钮 (选中时出现, 右上角) */
.cell-delete {
  position: absolute; top: -10px; right: -10px; z-index: 5;
  padding: 2px 8px; font-size: 12px; line-height: 1.4;
  color: #fff; background: #f56c6c; border: none; border-radius: 12px;
  cursor: pointer; box-shadow: 0 2px 6px rgba(245, 108, 108, 0.4);
}
.cell-delete:hover { background: #f23c3c; }

.material-node {
  width: 210px;
  padding: 12px;
  border: 1px solid #d8e4ef;
  border-left: 4px solid #67c23a;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.08);
}
.material-node.selected { box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.28); }
/* 原料=蓝灰, 半成品=绿(默认), 成品=紫 */
.material-node.kind-raw_material { border-left-color: #6b7c93; }
.material-node.kind-finished_good { border-left-color: #8b5cf6; }
.node-heading { display: flex; align-items: flex-start; gap: 8px; }
.quick-edit {
  display: grid; place-items: center; width: 24px; height: 24px; flex: 0 0 auto;
  padding: 0; border: 1px solid #d8e4ef; border-radius: 6px; background: #fff;
  color: #1b65a8; cursor: pointer; font-size: 14px;
}
.quick-edit:hover { border-color: #409eff; background: #eef6ff; }
.kind-mark {
  display: grid; place-items: center; width: 28px; height: 28px; flex: 0 0 auto;
  border-radius: 7px; color: #2f8a3d; background: #edf9e8; font-weight: 700;
}
.kind-raw_material .kind-mark { color: #4a5b73; background: #eef1f6; }
.kind-finished_good .kind-mark { color: #7141d8; background: #f2ecff; }
.node-heading > div { min-width: 0; flex: 1; }
.kind-label { color: #7a8599; font-size: 11px; }
.material-name { margin-top: 2px; color: #1a2332; font-size: 14px; font-weight: 650; line-height: 1.35; }
.identity-row {
  display: flex; justify-content: space-between; gap: 8px; margin-top: 12px;
  padding: 8px; border-radius: 7px; background: #f7f9fc; font-size: 11px; color: #7a8599;
}
.identity-row strong { overflow: hidden; color: #344054; text-overflow: ellipsis; white-space: nowrap; }
.specification { margin-top: 6px; color: #7a8599; font-size: 11px; }
.raw-selector, .sku-selector { width: 100%; margin-top: 8px; }
.node-actions { display: flex; justify-content: flex-end; margin-top: 6px; }
/* #3 BOM 为空时的提示 (fool-proof-design Rule 5: 不留死胡同, 给出下一步动作) */
.bom-hint {
  display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 8px;
  padding: 6px 8px; border-radius: 7px; background: #fdf6ec; color: #b88230; font-size: 11px; line-height: 1.4;
}
.bom-hint-link {
  color: #1b65a8; font-weight: 650; text-decoration: none; cursor: pointer;
  background: none; border: none; padding: 0; font-size: inherit; font-family: inherit;
}
.bom-hint-link:hover { text-decoration: underline; }
</style>
