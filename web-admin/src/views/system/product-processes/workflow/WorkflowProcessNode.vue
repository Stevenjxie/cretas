<template>
  <div
    class="process-node"
    :class="{ selected }"
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
      <el-tag v-if="inputPorts.length > 1" size="small" type="success">{{ inputPorts.length }} 入自动混合</el-tag>
      <el-tag v-if="outputPorts.length > 1" size="small" type="warning">{{ outputPorts.length }} 出</el-tag>
    </div>

    <section class="port-section">
      <div class="section-title">
        <span>投入物料</span>
        <el-button v-if="canWrite" text size="small" type="primary" class="nodrag" @click="emit('addInput')">+ 投入</el-button>
      </div>
      <div v-for="port in inputPorts" :key="port.id" class="port-row">
        <el-input
          class="nodrag"
          :model-value="port.materialName || '由上游 Cell 自动带入'"
          readonly
          size="small"
        />
        <el-select
          class="nodrag nowheel unit-select"
          :model-value="port.unit"
          :disabled="!canWrite"
          size="small"
          @change="(unit: string) => updatePort(port.id, { unit })"
        >
          <el-option v-for="unit in unitOptions" :key="unit" :label="unit" :value="unit" />
        </el-select>
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
        >+ 产出</el-button>
      </div>
      <div v-for="port in outputPorts" :key="port.id" class="port-row output-row">
        <el-select
          class="nodrag nowheel sku-select"
          :model-value="port.skuId"
          :disabled="!canWrite"
          filterable
          size="small"
          placeholder="选择或现场创建 SKU"
          @change="(skuId: string) => emit('selectOutputSku', port.id, skuId)"
        >
          <el-option class="create-option" label="＋ 现场创建半成品 SKU" value="__CREATE__" />
          <el-option
            v-for="option in skuOptions"
            :key="option.id"
            :label="`${option.name} · ${option.unit || '-'}`"
            :value="option.id"
          />
        </el-select>
        <el-select
          class="nodrag nowheel unit-select"
          :model-value="port.unit"
          :disabled="!canWrite"
          size="small"
          @change="(unit: string) => updatePort(port.id, { unit })"
        >
          <el-option v-for="unit in unitOptions" :key="unit" :label="unit" :value="unit" />
        </el-select>
      </div>
    </section>

    <section class="conversion-section">
      <div class="section-title"><span>投入产出数量关系</span></div>
      <div class="conversion-row">
        <el-select
          class="nodrag nowheel"
          :model-value="data.conversionRule.mode"
          :disabled="!canWrite"
          size="small"
          @change="(mode: ConversionMode) => emit('update', { conversionRule: { ...data.conversionRule, mode } })"
        >
          <el-option label="按实际称重" value="ACTUAL_WEIGHT" />
          <el-option label="固定比例" value="FIXED_RATIO" />
          <el-option label="产出相加" value="SUM_OUTPUTS" />
          <el-option label="自定义公式" value="FORMULA" />
        </el-select>
        <el-input
          v-if="data.conversionRule.mode !== 'ACTUAL_WEIGHT'"
          class="nodrag"
          :model-value="data.conversionRule.expression"
          :disabled="!canWrite"
          size="small"
          placeholder="例：投入 = 产出1 + 产出2"
          @input="(expression: string) => emit('update', { conversionRule: { ...data.conversionRule, expression } })"
        />
      </div>
      <div class="conversion-example">示例：200 {{ inputPorts[0]?.unit || '-' }} → 180 {{ outputPorts[0]?.unit || '-' }}，报工记录实际数量</div>
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
import type { ConversionMode, ProcessNodeData, ProcessPort } from './types';

const props = defineProps<{
  data: ProcessNodeData;
  selected?: boolean;
  canWrite: boolean;
  skuOptions: Array<{ id: string; name: string; unit?: string }>;
}>();

const emit = defineEmits<{
  update: [patch: Partial<ProcessNodeData>];
  addInput: [];
  addOutput: [];
  selectOutputSku: [portId: string, skuId: string];
}>();

const unitOptions = ['kg', 'g', '只', '半只', '盒', '袋', '箱', '筐'];
const edgeOutputStyle = { right: '-44px' } as const;
const hovered = ref(false);
const inputPorts = computed(() => props.data.ports.filter((port) => port.direction === 'INPUT'));
const outputPorts = computed(() => props.data.ports.filter((port) => port.direction === 'OUTPUT'));

function updatePort(portId: string, patch: Partial<ProcessPort>): void {
  emit('update', {
    ports: props.data.ports.map((port) => port.id === portId ? { ...port, ...patch } : port),
    ...(patch.unit && inputPorts.value.some((port) => port.id === portId) ? { inputUnit: patch.unit } : {}),
    ...(patch.unit && outputPorts.value.some((port) => port.id === portId) ? { outputUnit: patch.unit } : {}),
  });
}

function handleStyle(index: number, count: number): Record<string, string> {
  return { top: `${((index + 1) / (count + 1)) * 100}%` };
}
</script>

<style scoped>
.process-node {
  position: relative;
  width: 390px; padding: 14px; border: 1px solid #b9d8f4; border-left: 4px solid #409eff;
  border-radius: 10px; background: #fff; box-shadow: 0 2px 12px rgba(27, 101, 168, 0.09);
}
.process-node.selected { box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.3); }
.process-heading { display: flex; align-items: flex-start; gap: 8px; }
.process-heading > div { min-width: 0; flex: 1; }
.step-mark { display: grid; place-items: center; width: 28px; height: 28px; border-radius: 7px; color: #1b65a8; background: #eaf4ff; font-weight: 700; }
.eyebrow { color: #7a8599; font-size: 11px; }
.process-name { color: #1a2332; font-size: 15px; font-weight: 700; }
.port-section, .conversion-section { margin-top: 12px; padding-top: 10px; border-top: 1px solid #edf2f7; }
.section-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; color: #475467; font-size: 12px; font-weight: 650; }
.port-row { display: grid; grid-template-columns: minmax(0, 1fr) 76px; gap: 6px; margin-top: 6px; }
.output-row { grid-template-columns: minmax(0, 1fr) 70px; }
.conversion-row { display: grid; grid-template-columns: 130px minmax(0, 1fr); gap: 6px; }
.conversion-example { margin-top: 6px; color: #8a95a8; font-size: 11px; }
.reporting-row { display: flex; align-items: center; justify-content: space-between; margin-top: 10px; color: #667085; font-size: 12px; }
.create-option { color: #409eff; font-weight: 600; }
.edge-output-add {
  position: absolute;
  top: 50%;
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
  transform: translateY(-50%);
}
.edge-output-add:hover { background: #eaf4ff; }
:deep(.vue-flow__handle) { width: 10px; height: 10px; border: 2px solid #fff; background: #1b65a8; }
</style>
