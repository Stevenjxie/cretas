<template>
  <div
    class="process-node"
    :class="{ selected, 'wf-dim': isConnectDimmed, 'wf-valid': isValidConnectTarget }"
    :style="processNodeStyle"
    @mouseenter="hovered = true"
    @mouseleave="hovered = false"
  >
    <Handle
      v-for="(port, index) in inputPorts"
      :key="port.id"
      :id="port.id"
      type="target"
      :position="Position.Left"
      :style="handleStyle(index, inputPorts.length)"
    />
    <Handle
      v-for="(port, index) in outputPorts"
      :key="port.id"
      :id="port.id"
      type="source"
      :position="Position.Right"
      :style="handleStyle(index, outputPorts.length)"
    />

    <button
      v-if="canWrite && (selected || hovered)"
      type="button"
      class="cell-delete nodrag"
      title="删除此工序 Cell"
      data-testid="delete-process-cell"
      @click.stop="emit('delete')"
    >✕ 删除</button>

    <button
      v-if="canWrite && (selected || hovered)"
      type="button"
      class="edge-output-add nodrag"
      data-testid="add-output-edge"
      aria-label="添加一个产出 Cell"
      :style="edgeOutputStyle"
      @click.stop="emit('addOutput')"
    >+</button>

    <div class="process-heading">
      <span class="step-mark">序</span>
      <div>
        <div class="eyebrow">工序 Cell</div>
        <div class="process-name">{{ data.processName }}</div>
      </div>
    </div>

    <div class="system-inference" data-testid="system-inference">
      <span class="system-inference-badge">
        系统研判 · {{ inputPorts.length }} 入{{ inputPorts.length > 1 ? '（多来源合流）' : '' }} · {{ outputPorts.length }} 出{{ outputPorts.length > 1 ? '（同时多产出）' : '' }}
      </span>
      <span class="system-inference-hint">增删左右的来源/产出 Cell 后自动更新</span>
    </div>

    <section class="port-section">
      <div class="section-title">
        <span>投入物料</span>
        <el-button v-if="canWrite && allowAddInput" text size="small" type="primary" class="nodrag" @click="emit('addInput')">+ 来源 Cell（合流）</el-button>
      </div>
      <div v-if="inputPorts.length > 1" class="port-relation nodrag" data-testid="input-port-relation">
        <div class="port-relation-control">
          <span>投入关系</span>
          <el-select
            :model-value="relationMode('INPUT')"
            :disabled="!canWrite || inputPortGroups.length > 1"
            size="small"
            data-testid="input-relation-select"
            @change="(mode: PortSelectionMode) => updatePortRelation('INPUT', mode)"
          >
            <el-option v-for="option in relationOptions('INPUT')" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </div>
        <div class="port-relation-labels">
          <el-tag v-if="inputPortGroups.length === 0" size="small" type="info">兼容旧配置 · 全部必投</el-tag>
          <el-tag v-for="group in inputPortGroups" :key="group.id" size="small" type="info">
            {{ group.label }} · {{ selectionModeLabel(group.mode, 'INPUT') }}
          </el-tag>
        </div>
      </div>
      <div v-for="port in inputPorts" :key="port.id" class="port-row">
        <el-input
          class="nodrag"
          :model-value="port.materialName || '由上游 Cell 自动带入'"
          readonly
          size="small"
        />
        <span class="unit-chip" data-testid="input-unit-chip">{{ port.unit }}</span>
      </div>
    </section>

    <section class="port-section output-section">
      <div class="section-title">
        <span>产出物料</span>
        <el-button
          v-if="canWrite"
          text
          size="small"
          type="primary"
          class="nodrag"
          data-testid="add-output-inline"
          @click.stop="emit('addOutput')"
        >+ 产出 Cell（分流）</el-button>
      </div>
      <div v-if="outputPorts.length > 1" class="port-relation nodrag" data-testid="output-port-relation">
        <div class="port-relation-control">
          <span>产出关系</span>
          <el-select
            :model-value="relationMode('OUTPUT')"
            :disabled="!canWrite || outputPortGroups.length > 1"
            size="small"
            data-testid="output-relation-select"
            @change="(mode: PortSelectionMode) => updatePortRelation('OUTPUT', mode)"
          >
            <el-option v-for="option in relationOptions('OUTPUT')" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </div>
        <div class="port-relation-labels">
          <el-tag v-if="outputPortGroups.length === 0" size="small" type="success">兼容旧配置 · 全部产出</el-tag>
          <el-tag v-for="group in outputPortGroups" :key="group.id" size="small" type="success">
            {{ group.label }} · {{ selectionModeLabel(group.mode, 'OUTPUT') }}
          </el-tag>
        </div>
      </div>
      <div v-for="port in outputPorts" :key="port.id" class="port-row output-row">
        <WorkflowSkuPicker
          class="nodrag"
          test-id="output-sku-select"
          :model-value="port.skuId || ''"
          :semi-options="semiOptions"
          :finished-options="finishedOptions"
          :disabled="!canWrite"
          placeholder="选择或现场创建产出 SKU"
          @change="(skuId) => emit('selectOutput', port.id, skuId)"
        />
        <span class="unit-chip" data-testid="output-unit-chip">{{ port.unit }}</span>
      </div>
    </section>

    <section class="quantity-rule-section">
      <div class="section-title"><span>端口标准数量</span></div>
      <div v-if="isSingleAutoConvert" class="quantity-rule-note" data-testid="quantity-rule-note">
        按系统单位换算（{{ primaryInput?.unit }} → {{ primaryOutput?.unit }}），无需填写数量关系。
      </div>
      <div v-else class="port-quantity-list" data-testid="port-quantity-list">
        <div v-for="(port, index) in inputPorts" :key="`quantity:${port.id}`" class="port-quantity-row">
          <div class="quantity-port-name">
            <el-tag size="small" type="info">{{ index === 0 ? '主投入' : '追加投入' }}</el-tag>
            <span>{{ port.materialName || `投入 ${index + 1}` }}</span>
          </div>
          <div v-if="index === 0" class="baseline-quantity" data-testid="primary-input-baseline">
            基准 1 {{ port.unit }}
          </div>
          <div v-else class="quantity-input-wrap">
            <el-input-number
              :model-value="port.standardQuantity || 1"
              :min="0.0001"
              :controls="false"
              :disabled="!canWrite"
              :aria-label="`${port.materialName || '追加投入'}标准数量`"
              @focus="selectNumericInput"
              @change="(value: number | undefined) => updatePortQuantity(port.id, value)"
            />
            <span>{{ port.unit }}</span>
          </div>
        </div>
        <div v-for="(port, index) in outputPorts" :key="`quantity:${port.id}`" class="port-quantity-row">
          <div class="quantity-port-name">
            <el-tag size="small" type="success">产出 {{ index + 1 }}</el-tag>
            <span>{{ port.materialName || `产出 ${index + 1}` }}</span>
          </div>
          <div v-if="port.quantityMode === 'AUTO_CONVERT'" class="auto-convert-chip" data-testid="auto-convert-output">
            按系统单位换算 · {{ port.unit }}
          </div>
          <div v-else class="quantity-input-wrap" data-testid="fixed-output-quantity">
            <el-input-number
              :model-value="port.standardQuantity || 1"
              :min="0.0001"
              :controls="false"
              :disabled="!canWrite"
              :aria-label="`${port.materialName || '产出'}标准数量`"
              @focus="selectNumericInput"
              @change="(value: number | undefined) => updatePortQuantity(port.id, value)"
            />
            <span>{{ port.unit }}</span>
          </div>
        </div>
      </div>
    </section>

    <div class="reporting-row nodrag">
      <span>本道是否报工</span>
      <el-switch
        :model-value="data.reportingRequired"
        :disabled="!canWrite"
        inline-prompt
        active-text="报工"
        inactive-text="免报"
        size="small"
        @change="(value: boolean) => emit('update', { reportingRequired: value })"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { Handle, Position } from '@vue-flow/core';
import WorkflowSkuPicker, { type WorkflowSkuPickerOption } from './WorkflowSkuPicker.vue';
import type { PortSelectionMode, ProcessNodeData, ProcessPortGroup } from './types';

const props = withDefaults(defineProps<{
  data: ProcessNodeData;
  selected?: boolean;
  canWrite: boolean;
  /** #8: 拖拽连线中源 cell 的类型；用于给非法目标 cell 灰化 */
  connectingFromKind?: '' | 'MATERIAL' | 'PROCESS';
  semiOptions: WorkflowSkuPickerOption[];
  finishedOptions: WorkflowSkuPickerOption[];
  allowAddInput?: boolean;
}>(), { allowAddInput: true });

// #8: 工序 Cell 只有在「物料拖向工序(投入)」时才是合法目标；「工序拖向物料」时
// 工序互相之间非法 → 灰化。
const isValidConnectTarget = computed(() => props.connectingFromKind === 'MATERIAL');
const isConnectDimmed = computed(() => !!props.connectingFromKind && !isValidConnectTarget.value);

const emit = defineEmits<{
  update: [patch: Partial<ProcessNodeData>];
  addInput: [];
  addOutput: [];
  selectOutput: [portId: string, skuId: string];
  delete: [];
}>();

const processNodeStyle = { minHeight: '96px' } as const;
const edgeOutputStyle = { top: '12px', right: '-14px' } as const;
const hovered = ref(false);
const inputPorts = computed(() => props.data.ports.filter((port) => port.direction === 'INPUT'));
const outputPorts = computed(() => props.data.ports.filter((port) => port.direction === 'OUTPUT'));
const inputPortGroups = computed(() => (props.data.portGroups ?? []).filter((group) => group.direction === 'INPUT'));
const outputPortGroups = computed(() => (props.data.portGroups ?? []).filter((group) => group.direction === 'OUTPUT'));
const primaryInput = computed(() => [...inputPorts.value].sort((a, b) => a.ordinal - b.ordinal)[0]);
const primaryOutput = computed(() => [...outputPorts.value].sort((a, b) => a.ordinal - b.ordinal)[0]);
const isSingleAutoConvert = computed(() => inputPorts.value.length === 1
  && outputPorts.value.length === 1
  && primaryOutput.value?.quantityMode === 'AUTO_CONVERT');

function relationOptions(direction: 'INPUT' | 'OUTPUT'): Array<{ value: PortSelectionMode; label: string }> {
  return [
    { value: 'ALL_REQUIRED', label: direction === 'INPUT' ? '全部必投' : '全部产出' },
    { value: 'EXACTLY_ONE', label: '互相替代（选 1）' },
    { value: 'AT_LEAST_ONE', label: '至少选 1' },
    { value: 'OPTIONAL', label: '可选' },
  ];
}

function selectionModeLabel(mode: PortSelectionMode, direction: 'INPUT' | 'OUTPUT'): string {
  return relationOptions(direction).find((option) => option.value === mode)?.label ?? mode;
}

function directionGroups(direction: 'INPUT' | 'OUTPUT'): ProcessPortGroup[] {
  return direction === 'INPUT' ? inputPortGroups.value : outputPortGroups.value;
}

function relationMode(direction: 'INPUT' | 'OUTPUT'): PortSelectionMode {
  const groups = directionGroups(direction);
  return groups.length === 1 ? groups[0].mode : 'ALL_REQUIRED';
}

function updatePortRelation(direction: 'INPUT' | 'OUTPUT', mode: PortSelectionMode): void {
  const portIds = props.data.ports
    .filter((port) => port.direction === direction)
    .sort((left, right) => left.ordinal - right.ordinal)
    .map((port) => port.id);
  if (portIds.length < 2) return;
  const bounds = mode === 'ALL_REQUIRED'
    ? { minSelections: portIds.length, maxSelections: portIds.length }
    : mode === 'EXACTLY_ONE'
      ? { minSelections: 1, maxSelections: 1 }
      : mode === 'AT_LEAST_ONE'
        ? { minSelections: 1, maxSelections: portIds.length }
        : { minSelections: 0, maxSelections: portIds.length };
  const existing = directionGroups(direction)[0];
  const group: ProcessPortGroup = {
    id: existing?.id || `port-group:${direction.toLowerCase()}:all`,
    direction,
    label: direction === 'INPUT' ? '投入关系' : '产出关系',
    mode,
    ...bounds,
    portIds,
  };
  emit('update', {
    portGroups: [
      ...(props.data.portGroups ?? []).filter((candidate) => candidate.direction !== direction),
      group,
    ],
  });
}

function updatePortQuantity(portId: string, value: number | undefined): void {
  if (!value || value <= 0) return;
  emit('update', {
    ports: props.data.ports.map((port) => (port.id === portId
      ? { ...port, quantityMode: 'FIXED_RATIO', standardQuantity: value }
      : { ...port })),
  });
}

function selectNumericInput(event: FocusEvent): void {
  const target = event.target;
  if (target instanceof HTMLInputElement) target.select();
}

function handleStyle(index: number, count: number): Record<string, string> {
  return { top: `${((index + 1) / (count + 1)) * 100}%` };
}

</script>

<style scoped>
/* #8 拖拽连线视觉: 灰化非法目标 / 高亮合法目标 / handle 悬停显现. 仅 opacity/transform. */
.process-node { transition: opacity 150ms ease, box-shadow 150ms ease; }
.process-node.wf-dim { opacity: 0.4; cursor: not-allowed; }
.process-node.wf-valid { box-shadow: 0 0 0 2px #1b65a8, 0 0 12px rgba(27, 101, 168, 0.35); }
.process-node :deep(.vue-flow__handle) {
  width: 12px; height: 12px; opacity: 0.35;
  transition: opacity 150ms ease, transform 120ms ease;
}
.process-node:hover :deep(.vue-flow__handle),
.process-node.wf-valid :deep(.vue-flow__handle) { opacity: 1; }
.process-node :deep(.vue-flow__handle):hover { transform: scale(1.3); cursor: crosshair; }
@media (prefers-reduced-motion: reduce) {
  .process-node, .process-node :deep(.vue-flow__handle) { transition: none; }
}
/* #9 删除 Cell 按钮 (选中/悬停时出现, 右上角) */
.cell-delete {
  position: absolute; top: -10px; right: -10px; z-index: 5;
  padding: 2px 8px; font-size: 12px; line-height: 1.4;
  color: #fff; background: #f56c6c; border: none; border-radius: 12px;
  cursor: pointer; box-shadow: 0 2px 6px rgba(245, 108, 108, 0.4);
}
.cell-delete:hover { background: #f23c3c; }

.process-node {
  position: relative;
  width: 440px; padding: 14px; border: 1px solid #b9d8f4; border-left: 4px solid #409eff;
  border-radius: 10px; background: #fff; box-shadow: 0 2px 12px rgba(27, 101, 168, 0.09);
}
.process-node.selected { box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.3); }
.process-heading { display: flex; align-items: flex-start; gap: 8px; }
.process-heading > div { min-width: 0; flex: 1; }
.step-mark { display: grid; place-items: center; width: 28px; height: 28px; border-radius: 7px; color: #1b65a8; background: #eaf4ff; font-weight: 700; }
.eyebrow { color: #7a8599; font-size: 11px; }
.process-name { color: #1a2332; font-size: 15px; font-weight: 700; }
.system-inference { display: flex; flex-direction: column; gap: 2px; margin-top: 6px; }
.system-inference-badge { width: fit-content; padding: 2px 8px; border-radius: 999px; background: #eef6ff; color: #1b65a8; font-size: 11px; font-weight: 650; }
.system-inference-hint { color: #9aa5b8; font-size: 10px; }
.port-section, .quantity-rule-section { margin-top: 12px; padding-top: 10px; border-top: 1px solid #edf2f7; }
.section-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; color: #475467; font-size: 12px; font-weight: 650; }
.port-row { display: grid; grid-template-columns: minmax(0, 1fr) 76px; gap: 6px; margin-top: 6px; }
.output-row { grid-template-columns: minmax(0, 1fr) 70px; }
.port-relation { margin: 5px 0 7px; padding: 7px 8px; border-radius: 7px; background: #f7f9fc; }
.port-relation-control { display: grid; grid-template-columns: auto minmax(150px, 1fr); align-items: center; gap: 8px; color: #475467; font-size: 12px; font-weight: 650; }
.port-relation-control :deep(.el-select) { width: 100%; }
.port-relation-labels { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 5px; }
.quantity-rule-note {
  padding: 8px 10px; border-radius: 7px; background: #eef6ff; color: #1b65a8;
  font-size: 12px; font-weight: 600; line-height: 1.4;
}
.port-quantity-list { display: flex; flex-direction: column; gap: 8px; }
.port-quantity-row {
  display: grid; grid-template-columns: minmax(0, 1fr) minmax(150px, 190px); gap: 10px;
  align-items: center; min-height: 32px; padding: 6px 8px; border: 1px solid #edf2f7; border-radius: 7px;
}
.quantity-port-name { display: flex; align-items: center; gap: 6px; min-width: 0; color: #475467; font-size: 12px; }
.quantity-port-name > span:last-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.baseline-quantity, .auto-convert-chip {
  padding: 5px 8px; border-radius: 6px; background: #eef6ff; color: #1b65a8;
  font-size: 12px; font-weight: 600; text-align: center;
}
.auto-convert-chip { background: #edf8f2; color: #2f855a; }
.quantity-input-wrap { display: grid; grid-template-columns: minmax(90px, 1fr) auto; align-items: center; gap: 6px; color: #667085; font-size: 12px; }
.quantity-input-wrap :deep(.el-input-number) { width: 100%; }
.quantity-input-wrap :deep(input) { user-select: text; }
.reporting-row { display: flex; align-items: center; justify-content: space-between; margin-top: 10px; color: #667085; font-size: 12px; }
.unit-chip {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 24px;
  padding: 0 8px;
  border-radius: 6px;
  background: #f0f4f9;
  color: #5b6577;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}
.edge-output-add {
  position: absolute;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 2px solid #1b65a8;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 2px 8px rgba(27, 101, 168, 0.2);
  color: #1b65a8;
  font-size: 20px;
  font-weight: 700;
  line-height: 1;
  cursor: pointer;
}
.edge-output-add:hover { background: #eaf4ff; }
:deep(.vue-flow__handle) { width: 10px; height: 10px; border: 2px solid #fff; background: #1b65a8; }
</style>
