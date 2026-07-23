export type ProductionPlanSourceType =
  | 'MANUAL'
  | 'CUSTOMER_ORDER'
  | 'AI_FORECAST'
  | 'SAFETY_STOCK';

export function plannedQuantityRequired(sourceType: ProductionPlanSourceType): boolean {
  return sourceType !== 'SAFETY_STOCK';
}

export function plannedQuantityForPayload(
  sourceType: ProductionPlanSourceType,
  quantity: number | undefined,
): number | undefined {
  if (quantity !== undefined && Number.isFinite(quantity) && quantity > 0) {
    return quantity;
  }
  return plannedQuantityRequired(sourceType) ? quantity : undefined;
}
