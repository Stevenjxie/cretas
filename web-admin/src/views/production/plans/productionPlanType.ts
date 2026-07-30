export interface ProductionPlanTypeRow {
  sourceType?: unknown;
  sourceOrderId?: unknown;
  sourceOrderIds?: unknown;
}

export type ProductionPlanType = 'INVENTORY' | 'ORDER';

/** 显式来源优先；仅为旧数据使用销售订单关联作兼容判据。 */
export function productionPlanType(row: ProductionPlanTypeRow): ProductionPlanType {
  if (row.sourceType === 'SAFETY_STOCK') return 'INVENTORY';
  if (row.sourceType === 'CUSTOMER_ORDER') return 'ORDER';
  const hasSingleOrder = String(row.sourceOrderId ?? '').trim().length > 0;
  const hasOrderList = Array.isArray(row.sourceOrderIds) && row.sourceOrderIds.length > 0;
  return hasSingleOrder || hasOrderList ? 'ORDER' : 'INVENTORY';
}

export function productionPlanTypeLabel(row: ProductionPlanTypeRow): '库存生产' | '订单生产' {
  return productionPlanType(row) === 'ORDER' ? '订单生产' : '库存生产';
}
