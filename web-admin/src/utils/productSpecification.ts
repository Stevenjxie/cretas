import { displayUnit, TRANSLATED_UNIT_CODES } from '@/utils/unitPricing';

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

/**
 * 已存下来的规格串里把英文单位码翻成中文 —— 只动展示, 不改库里的值。
 *
 * 🔴 码为什么会出现在串里: 规格串由后端
 * `ProductPackagingSpecServiceImpl#composeCanonicalSpecification` 拼, 曾经直接用
 * `product.getUnit()`(规范化后的码)。后端已于 2026-08-06 改为用展示名, 但**存量行要等
 * 下次保存箱规才会重拼**, 所以展示层这条翻译仍然必要。
 *
 * ⚠️ 参与翻译的码由 {@link TRANSLATED_UNIT_CODES} 从 UNIT_LABELS 推导, 不再手抄 ——
 * 原先硬编码的 `box|case|slice` 漏了 `pack`, 客户就在规格列看到了 `1kg/pack 10pack/箱`。
 */
const TRANSLATABLE_UNIT_PATTERN = new RegExp(
  `(^|[^A-Za-z])(${TRANSLATED_UNIT_CODES.join('|')})(?=\\/|\\s|$)`,
  'gi',
);

export function displayProductSpecification(value: string | null | undefined): string {
  return String(value || '').replace(
    TRANSLATABLE_UNIT_PATTERN,
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
