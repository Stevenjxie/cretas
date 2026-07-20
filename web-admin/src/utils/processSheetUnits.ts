export type ProcessSheetUnits = {
  inputUnit: string;
  outputUnit: string;
};

type UnitConfig = {
  unitOverride?: string | null;
  defaultUnit?: string | null;
  defaultOutputUnit?: string | null;
  /** @deprecated Kept only for call-site compatibility; fail-closed resolution ignores it. */
  fallbackOutputUnit?: string;
};

type WorkflowUnitPort = {
  workflowPortId?: string | null;
  unit?: string | null;
  materialKind?: 'RAW_MATERIAL' | 'SEMI_FINISHED' | 'FINISHED_GOOD' | null;
  finished?: boolean | null;
  gramsPerUnit?: number | null;
};

type WorkflowUnitConfig = {
  processName?: string | null;
  inputs?: readonly WorkflowUnitPort[] | null;
  output?: WorkflowUnitPort | null;
  outputs?: readonly WorkflowUnitPort[] | null;
};

type LabelColumn = { key: string; label: string };

const INPUT_KEYS = new Set(['outWeight', 'feedWeight', 'before', 'input', 'remain']);
const OUTPUT_KEYS = new Set(['output', 'after', 'storage', 'sample', 'remainBox', 'claim', 'actualProd']);
const DISPLAY_UNIT_ALIASES: Record<string, string> = {
  box: '盒', case: '箱', slice: '片', bag: '袋', pcs: '件', each: '件', piece: '件', portion: '份', bottle: '瓶',
  '盒': '盒', '箱': '箱', '片': '片', '袋': '袋', '件': '件', '份': '份', '瓶': '瓶',
  g: 'g', '克': 'g', kg: 'kg', '千克': 'kg', '公斤': 'kg',
};

function nonBlank(value: string | null | undefined): string | undefined {
  const unit = value?.trim();
  return unit || undefined;
}

function isMassUnit(unit: string): boolean {
  const normalized = unit.trim().toLowerCase();
  return normalized === 'g' || normalized === 'kg' || normalized === '克' || normalized === '千克';
}

function reportingUnit(port: WorkflowUnitPort, unit: string): string {
  if ((port.materialKind === 'RAW_MATERIAL' || port.materialKind === 'SEMI_FINISHED') && isMassUnit(unit)) {
    return 'kg';
  }
  return unit;
}

export function displayProcessUnit(unit: string | null | undefined): string {
  const value = nonBlank(unit);
  if (!value) return '';
  return DISPLAY_UNIT_ALIASES[value.toLowerCase()] || value;
}

export function workflowPortDisplayUnit(port: WorkflowUnitPort | null | undefined): string {
  const unit = nonBlank(port?.unit);
  if (!port || !unit) return '';
  return displayProcessUnit(reportingUnit(port, unit));
}

/** 只转换显示值；持久化快照保持原样。 */
export function normalizeMassQuantityForReporting(
  quantity: number,
  unit: string | null | undefined,
): { quantity: number; unit: string } {
  const source = nonBlank(unit);
  if (!source) return { quantity, unit: '' };
  const normalized = source.toLowerCase();
  if (normalized === 'g' || normalized === '克') {
    return { quantity: Number((quantity / 1000).toFixed(6)), unit: 'kg' };
  }
  return { quantity, unit: displayProcessUnit(source) };
}

function massQuantityInGrams(quantity: number, unit: string): number | null {
  const normalized = unit.trim().toLowerCase();
  if (normalized === 'g' || normalized === '克') return quantity;
  if (normalized === 'kg' || normalized === '千克') return quantity * 1000;
  return null;
}

export function resolveProcessSheetUnits(config: UnitConfig): ProcessSheetUnits {
  const inputUnit = nonBlank(config.unitOverride) ?? nonBlank(config.defaultUnit);
  if (!inputUnit) throw new Error('legacy 工序投入单位未配置');
  const outputUnit = nonBlank(config.defaultOutputUnit);
  if (!outputUnit) throw new Error('legacy 工序产出单位未配置');
  return { inputUnit, outputUnit };
}

/**
 * Workflow 报工的单位只认端口快照，不接受 plannedUnit、产品单位或 kg 默认值。
 * 当前保存请求只有一个 inputUnit，因此多投入必须同单位；异单位不能静默取第一个。
 */
export function resolveWorkflowProcessSheetUnits(config: WorkflowUnitConfig): ProcessSheetUnits {
  const processLabel = nonBlank(config.processName) ?? '未命名工序';
  if (!Array.isArray(config.inputs) || config.inputs.length === 0) {
    throw new Error(`工序「${processLabel}」未配置投入端口，无法确定投入单位`);
  }

  const inputUnits = config.inputs.map((port, index) => {
    const unit = nonBlank(port.unit);
    if (!unit) {
      const portLabel = nonBlank(port.workflowPortId) ?? `#${index + 1}`;
      throw new Error(`工序「${processLabel}」投入端口 ${portLabel} 缺少单位`);
    }
    return reportingUnit(port, unit);
  });
  const distinctInputUnits = [...new Set(inputUnits)];
  if (distinctInputUnits.length !== 1) {
    throw new Error(`工序「${processLabel}」投入端口单位不一致：${distinctInputUnits.join('、')}`);
  }

  const rawOutputUnit = nonBlank(config.output?.unit);
  const outputUnit = rawOutputUnit && config.output ? reportingUnit(config.output, rawOutputUnit) : undefined;
  if (!outputUnit) {
    const portLabel = nonBlank(config.output?.workflowPortId) ?? '未配置';
    throw new Error(`工序「${processLabel}」产出端口 ${portLabel} 缺少单位`);
  }
  config.outputs?.forEach((port, index) => {
    const unit = nonBlank(port.unit);
    if (!unit) {
      const portLabel = nonBlank(port.workflowPortId) ?? `#${index + 1}`;
      throw new Error(`工序「${processLabel}」产出端口 ${portLabel} 缺少单位`);
    }
  });

  return { inputUnit: distinctInputUnits[0], outputUnit };
}

export function formatWorkflowPlannedOutput(
  quantity: number | null | undefined,
  plannedUnit: string | null | undefined,
  terminalOutput: WorkflowUnitPort | null | undefined,
): string {
  if (quantity == null || quantity === 0 || !terminalOutput) return formatPlannedOutput(quantity, plannedUnit);
  const sourceUnit = nonBlank(plannedUnit);
  const targetUnit = nonBlank(terminalOutput.unit);
  if (!sourceUnit || !targetUnit) return formatPlannedOutput(quantity, plannedUnit);

  const grams = massQuantityInGrams(quantity, sourceUnit);
  if ((terminalOutput.finished === true || terminalOutput.materialKind === 'FINISHED_GOOD') && grams != null) {
    const gramsPerUnit = terminalOutput.gramsPerUnit;
    if (gramsPerUnit != null && gramsPerUnit > 0 && !isMassUnit(targetUnit)) {
      return formatPlannedOutput(Number((grams / gramsPerUnit).toFixed(6)), targetUnit);
    }
  }
  if ((terminalOutput.materialKind === 'RAW_MATERIAL' || terminalOutput.materialKind === 'SEMI_FINISHED') && grams != null) {
    return formatPlannedOutput(Number((grams / 1000).toFixed(6)), 'kg');
  }
  return formatPlannedOutput(quantity, targetUnit);
}

export function formatPlannedOutput(quantity: number | null | undefined, unit: string | null | undefined): string {
  if (quantity == null || quantity === 0) return '计划成品 —';
  const normalizedUnit = displayProcessUnit(unit) || '（单位未配置）';
  return `计划成品 ${quantity} ${normalizedUnit}`;
}

export function formatProcessOutput(quantity: number | null | undefined, unit: string | null | undefined): string {
  if (quantity == null) return '—';
  return `产出 ${Number(quantity).toFixed(2)} ${displayProcessUnit(unit) || '（单位未配置）'}`;
}

export function formatSourceFeedSummary(sourceCount: number, quantity: number, unit: string | null | undefined): string {
  if (sourceCount === 0) return '+ 来源批';
  return `${sourceCount}批 · ${Number(quantity).toFixed(1)}${displayProcessUnit(unit) || '（单位未配置）'}`;
}

export function formatFeedPlaceholder(unit: string | null | undefined): string {
  return `投料${displayProcessUnit(unit) || '（单位未配置）'}`;
}

export function withProcessSheetUnits<T extends LabelColumn>(
  cols: readonly T[],
  units: ProcessSheetUnits,
): T[] {
  return cols.map((col) => {
    const unit = INPUT_KEYS.has(col.key) ? units.inputUnit
      : OUTPUT_KEYS.has(col.key) ? units.outputUnit
        : undefined;
    if (!unit || !col.label.includes('(kg)')) return { ...col };

    let label = col.label.replace('(kg)', `(${unit})`);
    if (unit !== 'kg') label = label.replace('出库重量', '出库数量').replace('投料重量', '投料数量');
    return { ...col, label };
  });
}
