export type ProductionPlanSourceType =
  | 'CUSTOMER_ORDER'
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
