export interface SupplierHistoryApiRow {
  materialTypeId?: string | null;
  materialName?: string | null;
  orderCount?: number | null;
  actuallyReceivedQuantity?: number | null;
  quantityUnit?: string | null;
  lastPurchaseDate?: string | null;
}

export interface SupplierHistoryViewRow {
  materialTypeId: string;
  materialName: string;
  purchaseCount: number;
  receivedQuantity: number | null;
  quantityUnit: string;
  lastPurchaseDate: string;
}

/**
 * Supplier history is aggregated by the backend by material and unit. It is not
 * an order-detail feed, so the UI must not treat a row as a purchase order.
 */
export function toSupplierHistoryViewRow(row: SupplierHistoryApiRow): SupplierHistoryViewRow {
  const orderCount = Number(row.orderCount ?? 0);
  const receivedQuantity = row.actuallyReceivedQuantity == null
    ? null
    : Number(row.actuallyReceivedQuantity);

  return {
    materialTypeId: String(row.materialTypeId ?? ''),
    materialName: String(row.materialName ?? '').trim(),
    purchaseCount: Number.isFinite(orderCount) && orderCount > 0 ? orderCount : 0,
    receivedQuantity: receivedQuantity !== null && Number.isFinite(receivedQuantity)
      ? receivedQuantity
      : null,
    quantityUnit: String(row.quantityUnit ?? '').trim(),
    lastPurchaseDate: String(row.lastPurchaseDate ?? '').trim(),
  };
}
