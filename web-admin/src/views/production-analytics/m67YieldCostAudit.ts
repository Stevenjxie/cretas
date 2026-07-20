import { canonicalUnitCode, displayUnit } from '@/utils/unitPricing';

export type NumericValue = number | string | null | undefined;

export interface YieldConversionInput {
  inputQuantity: NumericValue;
  inputUnit?: string | null;
  outputQuantity: NumericValue;
  outputUnit?: string | null;
  inputGramsPerUnit?: NumericValue;
  outputGramsPerUnit?: NumericValue;
}

export interface CollectionDisplay {
  label: string;
  complete: boolean;
  confirmedZero: boolean;
}

const CONFIRMED_ZERO_STATUSES = new Set([
  'CONFIRMED_ZERO',
  'ZERO_CONFIRMED',
  'NOT_APPLICABLE',
  'NO_COST',
]);

const COLLECTED_STATUSES = new Set([
  'COLLECTED',
  'COMPLETE',
  'PRICED',
  'CONFIRMED',
  ...CONFIRMED_ZERO_STATUSES,
]);

const MISSING_STATUS_LABELS: Record<string, string> = {
  MISSING_PRICE: '未归集/缺少价格',
  MISSING_RATE: '未归集/缺少费率',
  NOT_CONFIGURED: '未配置/未归集',
  UNCOLLECTED: '未归集',
  PARTIAL: '部分归集',
  NO_DATA: '无归集数据',
};

export function finiteNumber(value: NumericValue): number | null {
  if (value === null || value === undefined || value === '') return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

export function displayAuditUnit(value: unknown): string {
  return displayUnit(value) || '—';
}

export function sameCanonicalUnit(left: unknown, right: unknown): boolean {
  const leftCode = canonicalUnitCode(left);
  return Boolean(leftCode) && leftCode === canonicalUnitCode(right);
}

export function quantityInKilograms(
  quantity: NumericValue,
  unit: string | null | undefined,
  gramsPerUnit?: NumericValue,
): number | null {
  const numericQuantity = finiteNumber(quantity);
  if (numericQuantity === null) return null;

  const canonicalUnit = canonicalUnitCode(unit);
  if (canonicalUnit === 'kg') return numericQuantity;
  if (canonicalUnit === 'g') return numericQuantity / 1000;

  const verifiedGramsPerUnit = finiteNumber(gramsPerUnit);
  if (!canonicalUnit || verifiedGramsPerUnit === null || verifiedGramsPerUnit <= 0) return null;
  return numericQuantity * verifiedGramsPerUnit / 1000;
}

/**
 * 跨单位出成率只接受显式的历史克重契约；缺失换算因子时 fail closed。
 */
export function calculateYieldRate(input: YieldConversionInput): number | null {
  const inputQuantity = finiteNumber(input.inputQuantity);
  const outputQuantity = finiteNumber(input.outputQuantity);
  if (inputQuantity === null || outputQuantity === null || inputQuantity <= 0) return null;

  const inputUnit = canonicalUnitCode(input.inputUnit);
  const outputUnit = canonicalUnitCode(input.outputUnit);
  if (inputUnit && inputUnit === outputUnit) return outputQuantity / inputQuantity;

  const inputKg = quantityInKilograms(inputQuantity, input.inputUnit, input.inputGramsPerUnit);
  const outputKg = quantityInKilograms(outputQuantity, input.outputUnit, input.outputGramsPerUnit);
  if (inputKg === null || outputKg === null || inputKg <= 0) return null;
  return outputKg / inputKg;
}

export function collectionDisplay(
  amount: NumericValue,
  status?: string | null,
  missingReason?: string | null,
): CollectionDisplay {
  const normalizedStatus = String(status ?? '').trim().toUpperCase();
  const numericAmount = finiteNumber(amount);
  const confirmedZero = numericAmount === 0 && CONFIRMED_ZERO_STATUSES.has(normalizedStatus);

  if (numericAmount !== null && (numericAmount !== 0 || confirmedZero || COLLECTED_STATUSES.has(normalizedStatus))) {
    return {
      label: confirmedZero ? '已确认 0' : '已归集',
      complete: true,
      confirmedZero,
    };
  }

  return {
    label: missingReason?.trim() || MISSING_STATUS_LABELS[normalizedStatus]
      || (normalizedStatus ? '未归集' : '未归集/缺少价格'),
    complete: false,
    confirmedZero: false,
  };
}

export function formatAuditMoney(value: NumericValue): string {
  const numeric = finiteNumber(value);
  return numeric === null ? '未归集' : `¥${numeric.toFixed(2)}`;
}

export function formatCollectedMoney(
  value: NumericValue,
  status?: string | null,
  missingReason?: string | null,
): string {
  return collectionDisplay(value, status, missingReason).complete ? formatAuditMoney(value) : '未归集';
}

export function formatAuditQuantity(value: NumericValue, unit?: string | null): string {
  const numeric = finiteNumber(value);
  if (numeric === null) return '—';
  return `${Number.isInteger(numeric) ? numeric.toFixed(0) : numeric.toFixed(4).replace(/0+$/, '').replace(/\.$/, '')} ${displayAuditUnit(unit)}`;
}

export function formatPercent(value: NumericValue, precision = 2): string {
  const numeric = finiteNumber(value);
  return numeric === null ? '不可计算' : `${(numeric * 100).toFixed(precision)}%`;
}
