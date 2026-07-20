import { displayUnit } from '@/utils/unitPricing';

export interface ProductPackagingSpecInput {
  packageUnit?: string | null;
  baseUnit?: string | null;
  conversionFactor?: number | string | null;
}

export type NetContentUnit = 'g' | 'kg' | 'ml' | 'L';

export interface NetContentValue {
  amount: number;
  unit: NetContentUnit;
}

function decimalText(value: number): string {
  return Number(value.toFixed(6)).toString();
}

export function netContentDimension(unit: NetContentUnit): 'MASS' | 'VOLUME' {
  return unit === 'g' || unit === 'kg' ? 'MASS' : 'VOLUME';
}

export function convertNetContent(
  amount: number,
  fromUnit: NetContentUnit,
  toUnit: NetContentUnit,
): number | null {
  if (!Number.isFinite(amount) || netContentDimension(fromUnit) !== netContentDimension(toUnit)) return null;
  const base = fromUnit === 'kg' || fromUnit === 'L' ? amount * 1000 : amount;
  return toUnit === 'kg' || toUnit === 'L' ? base / 1000 : base;
}

export function normalizeNetContent(amount: number, unit: NetContentUnit): NetContentValue | null {
  if (!Number.isFinite(amount) || amount <= 0) return null;
  if (unit === 'g' && amount >= 1000) return { amount: amount / 1000, unit: 'kg' };
  if (unit === 'ml' && amount >= 1000) return { amount: amount / 1000, unit: 'L' };
  return { amount, unit };
}

export function parseNetContent(
  specification: string | null | undefined,
  gramsPerUnit: number | null | undefined,
): NetContentValue {
  const match = String(specification || '').match(/^\s*(\d+(?:\.\d+)?)\s*(g|kg|ml|L)\s*\//);
  if (match) return { amount: Number(match[1]), unit: match[2] as NetContentUnit };
  return { amount: Number(gramsPerUnit) > 0 ? Number(gramsPerUnit) : 0, unit: 'g' };
}

export function displayProductSpecification(value: string | null | undefined): string {
  return String(value || '').replace(
    /(^|[^A-Za-z])(box|case|slice)(?=\/|\s|$)/gi,
    (_match, prefix: string, unit: string) => `${prefix}${displayUnit(unit)}`,
  );
}

function netContentText(amount: number, unit: NetContentUnit): string {
  const normalized = normalizeNetContent(amount, unit);
  return normalized ? `${decimalText(normalized.amount)}${normalized.unit}` : '';
}

export function composeProductSpecificationFromNetContent(
  amount: number | string | null | undefined,
  netContentUnit: NetContentUnit,
  baseUnit: string | null | undefined,
  packagingSpecs: ProductPackagingSpecInput[],
): string {
  const unit = displayUnit(baseUnit).trim();
  const parts: string[] = [];
  const numericAmount = Number(amount);
  const contentText = netContentText(numericAmount, netContentUnit);
  if (contentText && unit) parts.push(`${contentText}/${unit}`);
  for (const spec of packagingSpecs) {
    const factor = Number(spec.conversionFactor);
    const packageUnit = displayUnit(spec.packageUnit).trim();
    if (Number.isFinite(factor) && factor > 0 && unit && packageUnit && unit !== packageUnit) {
      parts.push(`${decimalText(factor)}${unit}/${packageUnit}`);
      if (contentText) parts.push(`${netContentText(numericAmount * factor, netContentUnit)}/${packageUnit}`);
    }
  }
  return parts.join(' ');
}

export function composeProductSpecification(
  gramsPerUnit: number | string | null | undefined,
  baseUnit: string | null | undefined,
  packagingSpecs: ProductPackagingSpecInput[],
): string {
  return composeProductSpecificationFromNetContent(gramsPerUnit, 'g', baseUnit, packagingSpecs);
}
