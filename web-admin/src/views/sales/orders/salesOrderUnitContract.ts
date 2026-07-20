import { canonicalUnitCode } from '@/utils/unitPricing';

export interface SalesOrderPackagingSpecContract {
  id: string;
  packageUnit: string;
  baseUnit: string;
  conversionFactor: number;
  active?: boolean;
}

export interface SalesOrderUnitLineContract {
  unit?: unknown;
  packagingSpecId?: string;
  packagingSpecs?: SalesOrderPackagingSpecContract[];
}

const UI_ONLY_FIELDS = [
  'packagingSpecs',
  'packagingSpecName',
  'packagingUnit',
  'packagingBaseUnit',
  'packagingFactor',
  'packagingLoadError',
] as const;

/**
 * The backend associates a selected packaging spec with its outer/package unit.
 * A spec such as 1 case = 8 boxes is therefore selectable only for a case order;
 * a base-unit box order needs no packagingSpecId even though boxQuantity may still
 * carry the informational case conversion.
 */
export function packagingOptionsForUnit<T extends SalesOrderPackagingSpecContract>(
  line: SalesOrderUnitLineContract,
): T[] {
  const transactionUnit = canonicalUnitCode(line.unit);
  if (!transactionUnit) return [];
  return ((line.packagingSpecs || []) as T[]).filter(
    (spec) => spec.active !== false
      && canonicalUnitCode(spec.packageUnit) === transactionUnit,
  );
}

export function packagingSelectionError(line: SalesOrderUnitLineContract): string | null {
  const selectedId = String(line.packagingSpecId || '').trim();
  if (!selectedId) return null;
  const selected = (line.packagingSpecs || []).find((spec) => spec.id === selectedId);
  if (!selected || selected.active === false) {
    return '所选包装规格已失效，请重新选择';
  }
  if (canonicalUnitCode(selected.packageUnit) !== canonicalUnitCode(line.unit)) {
    return '所选包装规格与下单单位不一致，请切换单位或选择“不涉及”';
  }
  return null;
}

export function canonicalSalesOrderItemPayload(
  line: SalesOrderUnitLineContract,
): Record<string, unknown> {
  const payload: Record<string, unknown> = {
    ...line,
    unit: canonicalUnitCode(line.unit),
  };
  UI_ONLY_FIELDS.forEach((field) => delete payload[field]);
  if (!String(line.packagingSpecId || '').trim()) delete payload.packagingSpecId;
  return payload;
}
