export interface ProductPackagingSpecInput {
  packageUnit?: string | null;
  baseUnit?: string | null;
  conversionFactor?: number | string | null;
}

function decimalText(value: number): string {
  return Number(value.toFixed(6)).toString();
}

function massText(grams: number): string {
  return grams >= 1000
    ? `${decimalText(grams / 1000)}kg`
    : `${decimalText(grams)}g`;
}

export function composeProductSpecification(
  gramsPerUnit: number | string | null | undefined,
  baseUnit: string | null | undefined,
  packagingSpecs: ProductPackagingSpecInput[],
): string {
  const unit = baseUnit?.trim() || '';
  const parts: string[] = [];
  const grams = Number(gramsPerUnit);
  if (Number.isFinite(grams) && grams > 0 && unit) parts.push(`${massText(grams)}/${unit}`);
  for (const spec of packagingSpecs) {
    const factor = Number(spec.conversionFactor);
    const packageUnit = spec.packageUnit?.trim() || '';
    if (Number.isFinite(factor) && factor > 0 && unit && packageUnit && unit !== packageUnit) {
      parts.push(`${decimalText(factor)}${unit}/${packageUnit}`);
      if (Number.isFinite(grams) && grams > 0) {
        parts.push(`${massText(grams * factor)}/${packageUnit}`);
      }
    }
  }
  return parts.join(' ');
}
