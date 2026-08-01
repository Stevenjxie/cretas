export type QuantitySummaryReason =
  | 'single-unit'
  | 'empty'
  | 'mixed-units'
  | 'missing-unit'
  | 'invalid-value';

export interface QuantitySummary {
  value: number | null;
  unit: string | null;
  reason: QuantitySummaryReason;
}

export function emptyQuantitySummary(): QuantitySummary {
  return { value: 0, unit: null, reason: 'empty' };
}

/**
 * Sum quantities only when every non-zero row has one compatible unit.
 *
 * This intentionally does not convert g/kg, piece/box, or any other unit pair:
 * conversion belongs to the backend unit contract. Remove this fail-closed guard
 * only after the API returns one canonical aggregate value together with its unit.
 */
export function summarizeQuantity<T extends { unit?: string | null }>(
  rows: readonly T[],
  valueOf: (row: T) => number | null | undefined,
): QuantitySummary {
  let total = 0;
  let hasQuantity = false;
  let hasMissingUnit = false;
  const units = new Map<string, string>();

  for (const row of rows) {
    const value = valueOf(row) ?? 0;
    if (!Number.isFinite(value)) {
      return { value: null, unit: null, reason: 'invalid-value' };
    }
    total += value;
    if (value === 0) continue;

    hasQuantity = true;
    const displayUnit = row.unit?.trim() ?? '';
    if (!displayUnit) {
      hasMissingUnit = true;
      continue;
    }
    const canonicalKey = displayUnit.toLocaleLowerCase('en-US');
    if (!units.has(canonicalKey)) units.set(canonicalKey, displayUnit);
  }

  if (!hasQuantity) return emptyQuantitySummary();
  if (hasMissingUnit) return { value: null, unit: null, reason: 'missing-unit' };
  if (units.size !== 1) return { value: null, unit: null, reason: 'mixed-units' };

  return {
    value: total,
    unit: units.values().next().value ?? null,
    reason: 'single-unit',
  };
}
