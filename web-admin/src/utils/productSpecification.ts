export interface ProductPackagingSpecInput {
  packageUnit?: string | null;
  baseUnit?: string | null;
  conversionFactor?: number | string | null;
}

export function composeProductSpecification(
  gramsPerUnit: number | string | null | undefined,
  baseUnit: string | null | undefined,
  packagingSpecs: ProductPackagingSpecInput[],
): string {
  const unit = baseUnit?.trim() || '';
  const parts: string[] = [];
  const grams = Number(gramsPerUnit);
  if (Number.isFinite(grams) && grams > 0 && unit) parts.push(`${grams}克/${unit}`);
  for (const spec of packagingSpecs) {
    const factor = Number(spec.conversionFactor);
    const packageUnit = spec.packageUnit?.trim() || '';
    if (Number.isFinite(factor) && factor > 0 && unit && packageUnit && unit !== packageUnit) {
      parts.push(`${factor}${unit}/${packageUnit}`);
    }
  }
  return parts.join(' ');
}
