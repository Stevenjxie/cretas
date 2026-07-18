<template>
  <div
    ref="materialNodeRef"
    class="material-node"
    :class="[`kind-${kind.toLowerCase()}`, {
      selected,
      'wf-dim': isConnectDimmed,
      'wf-valid': isValidConnectTarget,
      'unit-error': !!unitError,
      'validation-error': !!validationError,
      'validation-attention': validationAttention,
    }]"
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
      <div class="kind-label">{{ kindLabel }}</div>
      <div class="heading-actions">
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
      <div class="material-name" :title="data.name || '未命名物料'">
        {{ data.name || '未命名物料' }}
      </div>
    </div>

    <div class="identity-row">
      <span>SKU</span>
      <strong>{{ data.skuCode || data.skuId || '尚未选择' }}</strong>
    </div>
    <div v-if="data.specification" class="specification">{{ data.specification }}</div>
    <div v-if="unitError" class="unit-error-message" data-testid="unit-error">
      {{ unitError }}
    </div>
    <div v-if="validationError" class="validation-error-message" data-testid="binding-validation-error">
      <strong>请在这里绑定 SKU</strong>
      <span>{{ validationError }}</span>
    </div>

    <div
      v-if="kind === 'RAW_MATERIAL' && canWrite && rawMaterialSegments.length > 0"
      class="raw-category-filter-shell nodrag nowheel"
      data-testid="raw-segment-filter-shell"
      @wheel.stop
    >
      <el-cascader
        ref="rawSegmentCascaderRef"
        v-model="selectedRawSegmentPath"
        class="raw-category-filter"
        :options="rawMaterialSegments"
        :props="rawSegmentCascaderProps"
        placeholder="按 L1 / L2 / L3 筛选"
        filterable
        clearable
        size="small"
        :teleported="false"
        popper-class="workflow-raw-segment-popper nowheel nodrag"
        data-testid="raw-segment-filter"
        @change="handleRawSegmentChange"
        @visible-change="handleRawSegmentVisibleChange"
        @keydown.esc.stop="closeRawSegmentDropdown"
      />
    </div>

    <el-select
      v-if="kind === 'RAW_MATERIAL' && canWrite"
      ref="rawSelectorRef"
      class="nodrag nowheel raw-selector"
      :model-value="data.skuId"
      placeholder="选择入口原料 SKU"
      filterable
      size="small"
      :teleported="false"
      popper-class="workflow-raw-selector-popper nowheel nodrag"
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
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { Handle, Position } from '@vue-flow/core';
import { usePinyinFilter } from './pinyinInitials';
import {
  filterRawMaterialsBySegment,
  type MaterialSegmentNode,
  type RawMaterialPickerOption,
} from './rawMaterialCatalog';
import WorkflowSkuPicker, { type WorkflowSkuPickerOption } from './WorkflowSkuPicker.vue';
import type { MaterialNodeData, ProductProcessNodeKind } from './types';

const props = withDefaults(defineProps<{
  kind: Exclude<ProductProcessNodeKind, 'PROCESS'>;
  data: MaterialNodeData;
  selected?: boolean;
  canWrite: boolean;
  /** #8: 拖拽连线中源 cell 的类型；用于给非法目标 cell 灰化 */
  connectingFromKind?: '' | 'MATERIAL' | 'PROCESS';
  rawMaterialOptions: RawMaterialPickerOption[];
  rawMaterialSegments?: MaterialSegmentNode[];
  excludedRawMaterialIds?: string[];
  /**
   * #3 (Steve 定: BOM 原料优先、可加其他): 该产品 BOM 原辅料清单里出现过的
   * 原料 SKU id 集合 (RawMaterialType.id，与 BomRecipeItem.materialTypeId 同一业务
   * 键)。由父组件 ProductProcessWorkflowEditor 调用
   * GET /{factoryId}/bom/recipes/by-product/{productTypeId}/current 拿到后传下来 —— 该端口在
   * BomController 中已确认存在 (com.cretas.aims.controller.BomController
   * #getBomItems)，不需要走"接口缺失降级"分支。
   */
  bomRawMaterialIds?: string[];
  semiOptions: WorkflowSkuPickerOption[];
  finishedOptions: WorkflowSkuPickerOption[];
  unitError?: string;
  validationError?: string;
  validationAttention?: boolean;
}>(), {
  connectingFromKind: '',
  bomRawMaterialIds: () => [],
  rawMaterialSegments: () => [],
  excludedRawMaterialIds: () => [],
  validationAttention: false,
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

const selectedRawSegmentPath = ref<string[]>([]);
const materialNodeRef = ref<HTMLElement | null>(null);
const rawSegmentDropdownVisible = ref(false);
interface CascaderExpose {
  togglePopperVisible: (visible?: boolean) => void;
}
const rawSegmentCascaderRef = ref<CascaderExpose | null>(null);
const rawSelectorRef = ref<{ blur: () => void } | null>(null);
const rawSegmentCascaderProps = {
  value: 'segmentCode',
  label: 'segmentLabel',
  children: 'children',
  emitPath: true,
  checkStrictly: true,
};
const rawCandidateOptions = computed(() => filterRawMaterialsBySegment(
  props.rawMaterialOptions,
  props.rawMaterialSegments,
  selectedRawSegmentPath.value,
).filter((option) => (
  option.id === props.data.skuId || !props.excludedRawMaterialIds.includes(option.id)
)));

function closeRawSegmentDropdown(): void {
  rawSegmentCascaderRef.value?.togglePopperVisible(false);
  rawSegmentDropdownVisible.value = false;
}

function handleRawSegmentVisibleChange(visible: boolean): void {
  rawSegmentDropdownVisible.value = visible;
}

function handleDocumentPointerDown(event: PointerEvent): void {
  if (!rawSegmentDropdownVisible.value) return;
  const target = event.target;
  if (target instanceof Node && materialNodeRef.value?.contains(target)) return;
  closeRawSegmentDropdown();
}

function handleCloseAllDropdowns(): void {
  closeRawSegmentDropdown();
  rawSelectorRef.value?.blur();
}

onMounted(() => {
  document.addEventListener('pointerdown', handleDocumentPointerDown, true);
  window.addEventListener('workflow-close-dropdowns', handleCloseAllDropdowns);
});
onUnmounted(() => {
  document.removeEventListener('pointerdown', handleDocumentPointerDown, true);
  window.removeEventListener('workflow-close-dropdowns', handleCloseAllDropdowns);
});

function handleRawSegmentChange(path: string[]): void {
  const selectedCode = path.at(-1);
  if (!selectedCode) return;
  const selected = findSegment(props.rawMaterialSegments, selectedCode);
  if (!selected?.children?.length) closeRawSegmentDropdown();
}

function findSegment(nodes: MaterialSegmentNode[], code: string): MaterialSegmentNode | undefined {
  for (const node of nodes) {
    if (node.segmentCode === code) return node;
    const child = findSegment(node.children || [], code);
    if (child) return child;
  }
  return undefined;
}

// #3 原料 Cell = BOM 原料优先、可加其他 (soft 约束，Steve 定：BOM 优先但不硬
// 禁其它)。把候选原料拆成「本产品 BOM 原料」+「其它原料」两组，BOM 组置顶,
// 两组各自独立跑拼音首字母搜索 (#2，复用 usePinyinFilter 共享 composable)。
const bomRawMaterialIdSet = computed(() => new Set(props.bomRawMaterialIds));
const bomRawOptions = computed(() => rawCandidateOptions.value
  .filter((option) => bomRawMaterialIdSet.value.has(option.id)));
const otherRawOptions = computed(() => rawCandidateOptions.value
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
.material-node.validation-error {
  border-color: #f56c6c;
  box-shadow: 0 0 0 2px rgba(245, 108, 108, 0.72), 0 6px 20px rgba(245, 108, 108, 0.2);
}
.material-node.validation-attention { animation: binding-attention-pulse 1.15s ease-in-out infinite; }
@keyframes binding-attention-pulse {
  0%, 100% { box-shadow: 0 0 0 2px rgba(245, 108, 108, 0.65), 0 6px 18px rgba(245, 108, 108, 0.16); }
  50% { box-shadow: 0 0 0 5px rgba(245, 108, 108, 0.22), 0 8px 26px rgba(245, 108, 108, 0.34); }
}
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
.validation-error-message {
  display: flex; flex-direction: column; gap: 2px; margin: 8px 0 0; padding: 7px 8px;
  color: #b42318; background: #fef3f2; border: 1px solid #fecdca; border-radius: 7px;
  font-size: 11px; line-height: 1.4;
}
.validation-error-message strong { font-size: 12px; }
.material-node :deep(.vue-flow__handle) {
  width: 18px; height: 18px; opacity: 0.5; border-width: 3px;
  transform-origin: center;
  transition: opacity 150ms ease, transform 120ms ease;
}
.material-node:hover :deep(.vue-flow__handle),
.material-node.wf-valid :deep(.vue-flow__handle) { opacity: 1; }
.material-node :deep(.vue-flow__handle-left):hover {
  opacity: 1; transform: translate(-50%, -50%) scale(1.35); cursor: crosshair;
}
.material-node :deep(.vue-flow__handle-right):hover {
  opacity: 1; transform: translate(50%, -50%) scale(1.35); cursor: crosshair;
}
@media (prefers-reduced-motion: reduce) {
  .material-node, .material-node :deep(.vue-flow__handle) { transition: none; }
  .material-node.validation-attention { animation: none; }
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
.node-heading {
  display: grid; grid-template-columns: 28px minmax(0, 1fr) auto;
  grid-template-rows: 28px auto; align-items: center; column-gap: 8px; row-gap: 6px;
}
.heading-actions { display: flex; align-items: center; justify-content: flex-end; gap: 6px; }
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
.node-heading > * { min-width: 0; }
.kind-label { color: #7a8599; font-size: 11px; }
.material-name {
  grid-column: 1 / -1; color: #1a2332; font-size: 14px; font-weight: 650;
  line-height: 1.45; overflow-wrap: anywhere; word-break: normal;
}
.identity-row {
  display: flex; justify-content: space-between; gap: 8px; margin-top: 12px;
  padding: 8px; border-radius: 7px; background: #f7f9fc; font-size: 11px; color: #7a8599;
}
.identity-row strong {
  min-width: 0; flex: 1; overflow: hidden; color: #344054;
  text-align: right; text-overflow: ellipsis; white-space: nowrap;
}
.specification { margin-top: 6px; color: #7a8599; font-size: 11px; }
.raw-selector, .raw-category-filter-shell, .sku-selector { width: 100%; margin-top: 8px; }
.raw-category-filter { width: 100%; }
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
