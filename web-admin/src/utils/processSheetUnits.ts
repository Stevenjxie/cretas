export type ProcessSheetUnits = {
  inputUnit: string;
  outputUnit: string;
};

type UnitConfig = {
  unitOverride?: string | null;
  defaultUnit?: string | null;
  defaultOutputUnit?: string | null;
  fallbackOutputUnit?: string;
};

type LabelColumn = { key: string; label: string };

const INPUT_KEYS = new Set(['outWeight', 'feedWeight', 'before', 'input', 'remain']);
const OUTPUT_KEYS = new Set(['output', 'after', 'storage', 'sample', 'remainBox', 'claim', 'actualProd']);

function nonBlank(value: string | null | undefined): string | undefined {
  const unit = value?.trim();
  return unit || undefined;
}

export function resolveProcessSheetUnits(config: UnitConfig): ProcessSheetUnits {
  const inputUnit = nonBlank(config.unitOverride) ?? nonBlank(config.defaultUnit) ?? 'kg';
  const outputUnit = nonBlank(config.defaultOutputUnit)
    ?? nonBlank(config.fallbackOutputUnit)
    ?? inputUnit;
  return { inputUnit, outputUnit };
}

export function formatPlannedOutput(quantity: number | null | undefined, unit: string | null | undefined): string {
  if (quantity == null || quantity === 0) return '计划成品 —';
  const normalizedUnit = nonBlank(unit) ?? 'kg';
  return `计划成品 ${quantity} ${normalizedUnit}`;
}

export function formatProcessOutput(quantity: number | null | undefined, unit: string | null | undefined): string {
  if (quantity == null) return '—';
  return `产出 ${Number(quantity).toFixed(2)} ${nonBlank(unit) ?? 'kg'}`;
}

export function formatSourceFeedSummary(sourceCount: number, quantity: number, unit: string | null | undefined): string {
  if (sourceCount === 0) return '+ 来源批';
  return `${sourceCount}批 · ${Number(quantity).toFixed(1)}${nonBlank(unit) ?? 'kg'}`;
}

export function formatFeedPlaceholder(unit: string | null | undefined): string {
  return `投料${nonBlank(unit) ?? 'kg'}`;
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
