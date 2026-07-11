<template>
  <div
    class="process-node"
    :class="{ selected }"
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
        <el-button v-if="canWrite" text size="small" type="primary" class="nodrag" @click="emit('addInput')">+ 投入</el-button>
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
        >+ 产出</el-button>
      </div>
      <div v-for="port in outputPorts" :key="port.id" class="port-row output-row">
        <el-input
          class="nodrag"
          :model-value="port.materialName || '产出物料待在右侧产出 Cell 选择 SKU'"
          readonly
          size="small"
        />
        <span class="unit-chip" data-testid="output-unit-chip">{{ port.unit }}</span>
      </div>
    </section>

    <section class="conversion-section">
      <div class="section-title"><span>投入产出数量关系（可人工调整）</span></div>
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
          data-testid="conversion-expression-input"
          :model-value="data.conversionRule.expression"
          :disabled="!canWrite"
          size="small"
          :placeholder="conversionPlaceholder"
          @input="(expression: string) => emit('update', { conversionRule: { ...data.conversionRule, expression } })"
        />
      </div>
      <div class="conversion-sentence" data-testid="conversion-sentence">{{ semanticSentence }}</div>
      <div class="conversion-example" data-testid="conversion-sample">{{ sampleLine }}</div>
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
}>();

const emit = defineEmits<{
  update: [patch: Partial<ProcessNodeData>];
  addInput: [];
  addOutput: [];
}>();

const processNodeStyle = { minHeight: '96px' } as const;
const edgeOutputStyle = { top: '12px', right: '-14px' } as const;
const hovered = ref(false);
const inputPorts = computed(() => props.data.ports.filter((port) => port.direction === 'INPUT'));
const outputPorts = computed(() => props.data.ports.filter((port) => port.direction === 'OUTPUT'));

function handleStyle(index: number, count: number): Record<string, string> {
  return { top: `${((index + 1) / (count + 1)) * 100}%` };
}

function portDisplayName(port: ProcessPort | undefined, fallback: string): string {
  return port?.materialName || fallback;
}

// 数量关系表达式输入框的 placeholder：SUM_OUTPUTS 用真实产出物料名拼出提示公式
const conversionPlaceholder = computed(() => {
  const mode = props.data.conversionRule.mode;
  if (mode === 'SUM_OUTPUTS') {
    if (outputPorts.value.length > 0) {
      const names = outputPorts.value.map((port, index) => portDisplayName(port, `产出${index + 1}`));
      return `投入 = ${names.join(' + ')}`;
    }
    return '例：投入 = 产出1 + 产出2';
  }
  if (mode === 'FIXED_RATIO') {
    return '例：1 只 = 1 只 / 100:90';
  }
  return '例：自定义公式，如 (产出1 + 产出2) * 0.95';
});

// 语义句：把 mode + 真实投入/产出物料名 + 用户填写的 expression 组成一句可读的关系描述（只读展示，非表单）
const semanticSentence = computed(() => {
  const { mode, expression } = props.data.conversionRule;
  if (mode === 'ACTUAL_WEIGHT') {
    const inName = portDisplayName(inputPorts.value[0], '（投入由上游带入）');
    const inUnit = inputPorts.value[0]?.unit || props.data.inputUnit || '-';
    const outName = portDisplayName(outputPorts.value[0], '（产出待选 SKU）');
    const outUnit = outputPorts.value[0]?.unit || props.data.outputUnit || '-';
    return `按实际称重 —— 投入 ${inName}（${inUnit}）→ 产出 ${outName}（${outUnit}），报工记录实际投入/产出`;
  }
  if (mode === 'FIXED_RATIO') {
    return `固定比例：${expression || '待填，例：1 只 = 1 只 / 100:90'}`;
  }
  if (mode === 'SUM_OUTPUTS') {
    if (outputPorts.value.length === 0) {
      return '投入 = 各产出之和（暂无产出 Cell）';
    }
    const names = outputPorts.value.map((port, index) => portDisplayName(port, `产出${index + 1}`));
    return `投入 = ${names.join(' + ')}（多产出按各自实际数量）`;
  }
  return `自定义：${expression || '待填公式'}`;
});

// 样例：只读的说明性示例, 用真实物料名/单位举例, 不是可编辑输入
const sampleLine = computed(() => {
  const isMultiOutput = props.data.conversionRule.mode === 'SUM_OUTPUTS' || outputPorts.value.length > 1;
  if (isMultiOutput) {
    return '样例：投入 = Σ 各产出（如 242kg = 36kg + 200kg）';
  }
  const inName = portDisplayName(inputPorts.value[0], '投入物料');
  const inUnit = inputPorts.value[0]?.unit || props.data.inputUnit || '-';
  const outName = portDisplayName(outputPorts.value[0], '产出物料');
  const outUnit = outputPorts.value[0]?.unit || props.data.outputUnit || '-';
  return `样例：投入 ${inName} 200${inUnit} → 产出 ${outName} ~180${outUnit}（实际称重）`;
});
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
.system-inference { display: flex; flex-direction: column; gap: 2px; margin-top: 6px; }
.system-inference-badge { width: fit-content; padding: 2px 8px; border-radius: 999px; background: #eef6ff; color: #1b65a8; font-size: 11px; font-weight: 650; }
.system-inference-hint { color: #9aa5b8; font-size: 10px; }
.port-section, .conversion-section { margin-top: 12px; padding-top: 10px; border-top: 1px solid #edf2f7; }
.section-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; color: #475467; font-size: 12px; font-weight: 650; }
.port-row { display: grid; grid-template-columns: minmax(0, 1fr) 76px; gap: 6px; margin-top: 6px; }
.output-row { grid-template-columns: minmax(0, 1fr) 70px; }
.conversion-row { display: grid; grid-template-columns: 130px minmax(0, 1fr); gap: 6px; }
.conversion-sentence { margin-top: 8px; color: #475467; font-size: 12px; line-height: 1.5; }
.conversion-example { margin-top: 4px; color: #8a95a8; font-size: 11px; }
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
