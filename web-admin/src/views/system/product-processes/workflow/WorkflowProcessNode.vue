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
        <el-button v-if="canWrite" text size="small" type="primary" class="nodrag" @click="emit('addInput')">+ 来源 Cell（合流）</el-button>
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

    <section class="conversion-section">
      <div class="section-title"><span>投入产出数量关系</span></div>

      <!-- #5: 多产出自动生效, 不给用户看比例/公式选项——隐性核对, 不作可选项 -->
      <div v-if="isMultiOutput" class="conversion-auto-hint" data-testid="conversion-multi-output-auto">
        本道多产出：投入 = 各产出之和（系统自动核对）
      </div>

      <template v-else>
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
          </el-select>
        </div>

        <!-- #4: 固定比例改结构化数字输入, 单位自动带入只读 -->
        <div
          v-if="data.conversionRule.mode === 'FIXED_RATIO'"
          class="fixed-ratio-row nodrag"
          data-testid="fixed-ratio-row"
        >
          <span class="fixed-ratio-unit" data-testid="fixed-ratio-input-unit">{{ fixedRatioInputUnit }}</span>
          <el-input-number
            :model-value="fixedRatioValue"
            :min="0.0001"
            :precision="4"
            :controls="false"
            size="small"
            :disabled="!canWrite"
            placeholder="比例系数"
            data-testid="fixed-ratio-input"
            @update:model-value="handleFixedRatioInput"
          />
          <span class="fixed-ratio-eq">=</span>
          <span class="fixed-ratio-unit" data-testid="fixed-ratio-output-unit">{{ fixedRatioOutputUnit }}</span>
        </div>

        <!-- #6: 产出相加/自定义公式已下线, 存量数据只读展示 + 引导改选 -->
        <div v-else-if="isLegacyConversionMode" class="legacy-mode-hint nodrag" data-testid="legacy-mode-hint">
          <div class="legacy-mode-expr">{{ legacyModeLabel }}（只读）：{{ data.conversionRule.expression || '未填写' }}</div>
          <div class="legacy-mode-warn">此模式已下线，请改选按实际称重/固定比例</div>
        </div>

        <div v-if="conversionHint" class="conversion-hint" data-testid="conversion-sentence">{{ conversionHint }}</div>
      </template>
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
import type { ConversionMode, ProcessNodeData } from './types';

const props = defineProps<{
  data: ProcessNodeData;
  selected?: boolean;
  canWrite: boolean;
  semiOptions: WorkflowSkuPickerOption[];
  finishedOptions: WorkflowSkuPickerOption[];
}>();

const emit = defineEmits<{
  update: [patch: Partial<ProcessNodeData>];
  addInput: [];
  addOutput: [];
  selectOutput: [portId: string, skuId: string];
}>();

const processNodeStyle = { minHeight: '96px' } as const;
const edgeOutputStyle = { top: '12px', right: '-14px' } as const;
const hovered = ref(false);
const inputPorts = computed(() => props.data.ports.filter((port) => port.direction === 'INPUT'));
const outputPorts = computed(() => props.data.ports.filter((port) => port.direction === 'OUTPUT'));

function handleStyle(index: number, count: number): Record<string, string> {
  return { top: `${((index + 1) / (count + 1)) * 100}%` };
}

// #5: 多产出 (>1 个产出端口) 时数量关系隐性生效 (投入 = 各产出之和), 不再是
// 用户可选的一个模式 (对齐 fool-proof-design: 不给用户看不懂的通用选项)。
const isMultiOutput = computed(() => outputPorts.value.length > 1);

// #6: 「产出相加(SUM_OUTPUTS)」「自定义公式(FORMULA)」已从可选项下架，但
// conversionRule.mode 类型仍保留这两个枚举值 (存量数据不炸)。单产出场景下,
// 只要 mode 不是当前仍可选的两个值 (ACTUAL_WEIGHT / FIXED_RATIO), 就当成
// "已下线的历史模式" 走只读展示分支——同时覆盖 FORMULA 和"单产出但历史上被
// 存成 SUM_OUTPUTS"这种边缘情况, 不需要分别硬编码两条分支。
const isLegacyConversionMode = computed(() => !isMultiOutput.value
  && props.data.conversionRule.mode !== 'ACTUAL_WEIGHT'
  && props.data.conversionRule.mode !== 'FIXED_RATIO');

const legacyModeLabel = computed(() => (
  props.data.conversionRule.mode === 'FORMULA' ? '自定义公式' : '产出相加'
));

// #4: 固定比例改结构化数字输入——单位左右两侧自动带入只读, 中间只填一个比例
// 系数的数字。左单位 = 该工序投入口单位, 右单位 = 产出口单位 (绑定 SKU 单位)。
const fixedRatioInputUnit = computed(() => inputPorts.value[0]?.unit || props.data.inputUnit || '-');
const fixedRatioOutputUnit = computed(() => outputPorts.value[0]?.unit || props.data.outputUnit || '-');

/**
 * 把结构化的「1 投入单位 = N 产出单位」解析回一个纯数字比例系数, 用于回显时
 * 预填数字输入框。格式对空白容忍 (`1 只 = 2 半只` 和 `1只=2半只` 都能解析),
 * 单位文本本身不参与校验——单位始终以当前 fixedRatioInputUnit/OutputUnit
 * 实时渲染, 不依赖解析出的旧单位文本 (工序端口单位后续变化不会让回显对不上)。
 * 解析不出结构 (包括历史自由文本, 如 "1 只 = 1 只 / 100:90") 时返回 null,
 * 数字输入框留空, 不假装有一个"看起来正确"的默认值。
 */
function parseFixedRatioExpression(expression: string | null | undefined): number | null {
  if (!expression) return null;
  const match = /^1\s*(.*?)\s*=\s*(\d+(?:\.\d+)?)\s*(.*?)\s*$/.exec(expression.trim());
  if (!match) return null;
  const ratio = Number(match[2]);
  return Number.isFinite(ratio) && ratio > 0 ? ratio : null;
}

function buildFixedRatioExpression(ratio: number, inputUnit: string, outputUnit: string): string {
  return `1 ${inputUnit} = ${ratio} ${outputUnit}`;
}

const fixedRatioValue = computed(() => parseFixedRatioExpression(props.data.conversionRule.expression));

function handleFixedRatioInput(value: number | undefined): void {
  if (value == null || !Number.isFinite(value) || value <= 0) return;
  const expression = buildFixedRatioExpression(value, fixedRatioInputUnit.value, fixedRatioOutputUnit.value);
  emit('update', { conversionRule: { ...props.data.conversionRule, expression } });
}

// 精简后的一行提示：只说明当前模式在做什么 / 展示用户填写的比例，
// 不再重复投入产出物料名和单位（这些已经在上面的端口行/结构化输入里显示过），
// 也不再额外渲染一行「样例」说明（trim 需求：去掉 Σ/→ 符号和大段解释文字）。
// 多产出自动模式和已下线的历史模式各自有自己的提示块 (见模板), 这里返回空
// 字符串时模板不渲染这一行 (v-if="conversionHint")。
const conversionHint = computed(() => {
  const { mode, expression } = props.data.conversionRule;
  if (mode === 'ACTUAL_WEIGHT') {
    return '按实际称重录入，无需设置比例或公式';
  }
  if (mode === 'FIXED_RATIO') {
    return `固定比例：${expression || '待填写比例系数'}`;
  }
  return '';
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
.conversion-hint { margin-top: 8px; color: #8a95a8; font-size: 11px; line-height: 1.4; }
/* #5 多产出自动核对提示：不给操作，只读说明 */
.conversion-auto-hint {
  padding: 8px 10px; border-radius: 7px; background: #eef6ff; color: #1b65a8;
  font-size: 12px; font-weight: 600; line-height: 1.4;
}
/* #4 固定比例结构化输入行：投入单位(只读) 数字 = 产出单位(只读) */
.fixed-ratio-row { display: grid; grid-template-columns: auto minmax(0, 1fr) auto auto; align-items: center; gap: 6px; margin-top: 6px; }
.fixed-ratio-unit {
  display: flex; align-items: center; justify-content: center; height: 24px; padding: 0 8px;
  border-radius: 6px; background: #f0f4f9; color: #5b6577; font-size: 12px; font-weight: 600; white-space: nowrap;
}
.fixed-ratio-eq { color: #8a95a8; font-size: 13px; font-weight: 700; }
.fixed-ratio-row :deep(.el-input-number) { width: 100%; }
/* #6 已下线模式只读展示：明确告知并引导改选，不留死胡同 */
.legacy-mode-hint {
  margin-top: 6px; padding: 8px 10px; border-radius: 7px; background: #fdf6ec; color: #b88230; font-size: 11px; line-height: 1.5;
}
.legacy-mode-expr { color: #8a6d3b; font-weight: 600; word-break: break-all; }
.legacy-mode-warn { margin-top: 2px; }
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
