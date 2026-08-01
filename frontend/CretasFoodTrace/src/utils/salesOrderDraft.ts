import type { ProductPackagingSpec } from '../services/api/productTypeApiClient';

export function salesUnitOptions(baseUnit: string, specs: ProductPackagingSpec[]): string[] {
  const units = new Set<string>();
  if (baseUnit) units.add(baseUnit);
  specs.filter((spec) => spec.active !== false).forEach((spec) => units.add(spec.packageUnit));
  return Array.from(units);
}

export function packagingSpecsForUnit(
  unit: string,
  specs: ProductPackagingSpec[],
): ProductPackagingSpec[] {
  return specs.filter((spec) => spec.active !== false && spec.packageUnit === unit);
}

export function optionalTaxRate(value: string): number | undefined {
  if (!value.trim()) return undefined;
  const taxRate = Number(value);
  return Number.isFinite(taxRate) && taxRate >= 0 && taxRate <= 100 ? taxRate : undefined;
}
