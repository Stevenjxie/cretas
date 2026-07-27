<template>
  <div
    class="process-node"
    :class="{ selected, 'wf-dim': isConnectDimmed, 'wf-valid': isValidConnectTarget }"
    :style="processNodeStyle"
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
      v-if="canWrite"
      type="button"
      class="cell-delete nodrag"
      title="删除此工序 Cell"
      data-testid="delete-process-cell"
      @click.stop="emit('delete')"
    >✕ 删除</button>

    <div class="process-heading">
      <span class="step-mark">序</span>
      <div>
        <div class="eyebrow">工序 Cell</div>
        <div class="process-name">{{ data.processName }}</div>
      </div>
      <button
        v-if="canWrite"
        type="button"
        class="quick-edit-process nodrag"
        title="快捷编辑工序"
        aria-label="快捷编辑工序"
        data-testid="quick-edit-process"
        @click.stop="emit('editProcess')"
      >编辑工序</button>
    </div>

    <div class="system-inference" data-testid="system-inference">
      <span class="system-inference-badge">
        可报工接口 · {{ inputPorts.length }} 个投入 · {{ outputPorts.length }} 个产出
      </span>
      <span class="system-inference-hint">正式报工时按实际情况选择，投入和产出至少各一项</span>
    </div>

    <section class="port-section">
      <div class="section-title">
        <span>投入物料</span>
        <el-button v-if="canWrite && allowAddInput" text size="small" type="primary" class="nodrag" @click="emit('addInput')">+ 来源 Cell（合流）</el-button>
      </div>
      <div class="actual-io-help">
        Workflow 只声明可投入物料；主料和替代料由生产计划固定的 BOM 自动限定。
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
      <div class="actual-io-help">
        这里不配置产出关系、主副角色或固定比例；每次报工填写本次实际产出的 SKU 和数量。
      </div>
      <div v-for="port in outputPorts" :key="port.id" class="output-entry">
        <div class="port-row output-row">
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
      </div>
    </section>

    <section class="unit-rule-section">
      <div class="section-title"><span>单位关系</span></div>
      <div class="unit-only-summary" data-testid="unit-only-summary">
        <strong>{{ unitRelationshipSummary }}</strong>
        <span>这里只约定各物料的报工单位，不预设投入产出数量；实际投入、实际产出和出成率以正式报工为准。</span>
        <small v-if="primaryOutputSpecification">
          成品 SKU 规格：{{ primaryOutputSpecification }}，计划、报工与结单需要折算时自动使用。
        </small>
      </div>
      <div class="unit-relationship-list" data-testid="unit-relationship-list">
        <div
          v-for="(port, index) in inputPorts"
          :key="`unit:${port.id}`"
          class="unit-relationship-row"
          :class="{ 'is-long-name': isLongMaterialName(port.materialName) }"
        >
          <div class="unit-port-name">
            <el-tag size="small" type="info">投入{{ index + 1 }}</el-tag>
            <span>{{ port.materialName || `投入 ${index + 1}` }}</span>
          </div>
          <div class="unit-flow-chip" data-testid="unit-flow-input">
            {{ port.unit }}投入 <span>→</span> {{ primaryOutput?.unit }}产出
          </div>
        </div>
        <div v-for="(port, index) in outputPorts" :key="`unit:${port.id}`" class="unit-relationship-row">
          <div class="unit-port-name">
            <el-tag size="small" type="success">产出{{ index + 1 }}</el-tag>
            <span>{{ port.materialName || `产出 ${index + 1}` }}</span>
          </div>
          <div class="unit-flow-chip" data-testid="unit-flow-output">
            报工单位：{{ port.unit }}
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
import { computed } from 'vue';
import { Handle, Position } from '@vue-flow/core';
import WorkflowSkuPicker, { type WorkflowSkuPickerOption } from './WorkflowSkuPicker.vue';
import { workflowSkuSpecificationEquation } from './workflowUnits';
import type { ProcessNodeData } from './types';

const props = withDefaults(defineProps<{
  data: ProcessNodeData;
  selected?: boolean;
  canWrite: boolean;
  /** #8: 拖拽连线中源 cell 的类型；用于给非法目标 cell 灰化 */
  connectingFromKind?: '' | 'MATERIAL' | 'PROCESS';
  semiOptions: WorkflowSkuPickerOption[];
  finishedOptions: WorkflowSkuPickerOption[];
  allowAddInput?: boolean;
  skuSpecifications?: Record<string, { unit: string; gramsPerUnit: number | null }>;
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
  editProcess: [];
}>();

const processNodeStyle = { minHeight: '96px' } as const;
const inputPorts = computed(() => props.data.ports.filter((port) => port.direction === 'INPUT'));
const outputPorts = computed(() => props.data.ports.filter((port) => port.direction === 'OUTPUT'));
const primaryOutput = computed(() => [...outputPorts.value].sort((a, b) => a.ordinal - b.ordinal)[0]);
const unitRelationshipSummary = computed(() => {
  const inputUnits = [...new Set(inputPorts.value.map((port) => port.unit).filter(Boolean))];
  const outputUnits = [...new Set(outputPorts.value.map((port) => port.unit).filter(Boolean))];
  const inputLabel = inputUnits.length === 1 ? inputUnits[0] : inputUnits.join(' / ');
  const outputLabel = outputUnits.length === 1 ? outputUnits[0] : outputUnits.join(' / ');
  return `投入单位：${inputLabel || '待绑定'} · 产出单位：${outputLabel || '待绑定'}`;
});
const primaryOutputSpecification = computed(() => {
  const port = primaryOutput.value;
  if (!port) return null;
  const spec = port.skuId ? props.skuSpecifications?.[port.skuId] : undefined;
  return workflowSkuSpecificationEquation(spec?.unit || port.unit, spec?.gramsPerUnit);
});

function isLongMaterialName(name: string | undefined): boolean {
  const visualLength = Array.from(name || '').reduce(
    (total, character) => total + (/^[\x00-\x7F]$/.test(character) ? 1 : 2),
    0,
  );
  return visualLength > 24;
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
  width: 18px; height: 18px; opacity: 0.5; border-width: 3px;
  transform-origin: center;
  transition: opacity 150ms ease, transform 120ms ease;
}
.process-node:hover :deep(.vue-flow__handle),
.process-node.wf-valid :deep(.vue-flow__handle) { opacity: 1; }
.process-node :deep(.vue-flow__handle-left):hover {
  opacity: 1; transform: translate(-50%, -50%) scale(1.35); cursor: crosshair;
}
.process-node :deep(.vue-flow__handle-right):hover {
  opacity: 1; transform: translate(50%, -50%) scale(1.35); cursor: crosshair;
}
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
.cell-delete, .quick-edit-process { opacity: 0; pointer-events: none; transition: opacity 120ms ease; }
.process-node:hover .cell-delete,
.process-node:hover .quick-edit-process,
.process-node:focus-within .cell-delete,
.process-node:focus-within .quick-edit-process,
.process-node.selected .cell-delete,
.process-node.selected .quick-edit-process { opacity: 1; pointer-events: auto; }
@media (hover: none) {
  .process-node .cell-delete, .process-node .quick-edit-process { opacity: 1; pointer-events: auto; }
}

.process-node {
  position: relative;
  width: 440px; padding: 14px; border: 1px solid #b9d8f4; border-left: 4px solid #409eff;
  border-radius: 10px; background: #fff; box-shadow: 0 2px 12px rgba(27, 101, 168, 0.09);
}
.process-node.selected { box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.3); }
.process-heading { display: flex; align-items: flex-start; gap: 8px; }
.process-heading > div { min-width: 0; flex: 1; }
.quick-edit-process {
  min-height: 32px; flex: 0 0 auto;
  padding: 0 10px; border: 1px solid #b9d8f4; border-radius: 6px; background: #fff;
  color: #1b65a8; cursor: pointer; font-size: 12px; font-weight: 600;
}
.quick-edit-process:hover { border-color: #409eff; background: #eef6ff; }
.step-mark { display: grid; place-items: center; width: 28px; height: 28px; border-radius: 7px; color: #1b65a8; background: #eaf4ff; font-weight: 700; }
.eyebrow { color: #7a8599; font-size: 11px; }
.process-name { color: #1a2332; font-size: 15px; font-weight: 700; }
.system-inference { display: flex; flex-direction: column; gap: 2px; margin-top: 6px; }
.system-inference-badge { width: fit-content; padding: 2px 8px; border-radius: 999px; background: #eef6ff; color: #1b65a8; font-size: 11px; font-weight: 650; }
.system-inference-hint { color: #9aa5b8; font-size: 10px; }
.port-section, .unit-rule-section { margin-top: 12px; padding-top: 10px; border-top: 1px solid #edf2f7; }
.section-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; color: #475467; font-size: 12px; font-weight: 650; }
.port-row { display: grid; grid-template-columns: minmax(0, 1fr) 76px; gap: 6px; margin-top: 6px; }
.output-row { grid-template-columns: minmax(0, 1fr) 70px; }
.output-entry { margin-top: 6px; }
.output-entry .output-row { margin-top: 0; }
.actual-io-help {
  margin: 5px 0 7px;
  padding: 8px 10px;
  border-radius: 7px;
  background: #eef6ff;
  color: #1b65a8;
  font-size: 12px;
  line-height: 1.5;
}
.output-contract-row {
  display: grid; grid-template-columns: minmax(132px, 0.8fr) minmax(190px, 1.2fr);
  align-items: center; gap: 6px; margin-top: 6px; padding: 7px 8px;
  border: 1px solid #dce8f3; border-radius: 7px; background: #f8fbff;
}
.output-contract-row label {
  display: grid; grid-template-columns: auto minmax(78px, 1fr) auto;
  align-items: center; gap: 5px; color: #475467; font-size: 12px;
}
.output-contract-row :deep(.el-select),
.output-contract-row :deep(.el-input-number) { width: 100%; }
.output-contract-help {
  margin-top: 6px; padding: 7px 8px; border-radius: 7px;
  background: #fff7e8; color: #8a6116; font-size: 11px; line-height: 1.5;
}
.port-relation { margin: 5px 0 7px; padding: 7px 8px; border-radius: 7px; background: #f7f9fc; }
.port-relation-control { display: grid; grid-template-columns: auto minmax(150px, 1fr); align-items: center; gap: 8px; color: #475467; font-size: 12px; font-weight: 650; }
.port-relation-control :deep(.el-select) { width: 100%; }
.port-relation-help { margin-top: 5px; color: #667085; font-size: 12px; line-height: 1.5; }
.port-relation-labels { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 5px; }
.quantity-rule-note {
  padding: 8px 10px; border-radius: 7px; background: #eef6ff; color: #1b65a8;
  font-size: 12px; font-weight: 600; line-height: 1.4;
}
.unit-relationship-list { display: flex; flex-direction: column; gap: 8px; margin-top: 8px; }
.unit-relationship-row {
  display: grid; grid-template-columns: minmax(0, 1fr) minmax(158px, 42%); gap: 6px;
  align-items: center; min-height: 32px; padding: 6px 8px; border: 1px solid #edf2f7; border-radius: 7px;
}
.unit-port-name { display: flex; align-items: center; gap: 6px; min-width: 0; color: #475467; font-size: 12px; }
.unit-port-name > span:last-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.unit-relationship-row.is-long-name { grid-template-columns: minmax(0, 1fr); gap: 5px; }
.unit-relationship-row.is-long-name .unit-port-name > span:last-child {
  overflow: visible; text-overflow: clip; white-space: normal; overflow-wrap: anywhere; line-height: 1.35;
}
.unit-relationship-row.is-long-name .unit-flow-chip { justify-self: end; width: min(100%, 190px); }
.unit-only-summary {
  display: flex; flex-direction: column; gap: 4px; padding: 9px 10px; border-radius: 7px;
  background: #f7f9fc; color: #667085; font-size: 12px; line-height: 1.45;
}
.unit-only-summary strong { color: #344054; }
.unit-only-summary small { color: #1b65a8; }
.unit-flow-chip {
  padding: 5px 8px; border-radius: 6px; background: #f7f9fc; color: #667085;
  font-size: 12px; font-weight: 600; text-align: center;
}
.unit-flow-chip span { color: #98a2b3; padding: 0 3px; }
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
:deep(.vue-flow__handle) { border-color: #fff; background: #1b65a8; }
</style>
