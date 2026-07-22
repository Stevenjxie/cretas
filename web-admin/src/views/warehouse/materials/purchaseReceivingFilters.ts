export interface ReceivingRouteFilters {
  purchaseOrderId: string;
  purchaseOrderNumber: string;
  salesOrderId: string;
  salesOrderNumber: string;
  highlightNumber: string;
  restrictToPurchase: boolean;
  restrictToCustomerSupplied: boolean;
}

function queryText(value: unknown): string {
  if (Array.isArray(value)) return String(value[0] || '').trim();
  return String(value || '').trim();
}

export function resolveReceivingRouteFilters(
  query: Record<string, unknown>,
): ReceivingRouteFilters {
  const sourceType = queryText(query.sourceType);
  const restrictToCustomerSupplied = sourceType === 'customer-supplied';
  const purchaseOrderId = restrictToCustomerSupplied ? '' : queryText(query.purchaseOrderId);
  const purchaseOrderNumber = restrictToCustomerSupplied
    ? ''
    : queryText(query.orderNo || query.orderNumber);
  const salesOrderId = queryText(query.salesOrderId);
  const salesOrderNumber = queryText(
    query.salesOrderNo || (restrictToCustomerSupplied ? query.orderNo : ''),
  );

  return {
    purchaseOrderId,
    purchaseOrderNumber,
    salesOrderId,
    salesOrderNumber,
    highlightNumber: salesOrderNumber || purchaseOrderNumber || purchaseOrderId,
    restrictToPurchase: Boolean(purchaseOrderId || purchaseOrderNumber),
    restrictToCustomerSupplied: Boolean(
      restrictToCustomerSupplied || salesOrderId || salesOrderNumber,
    ),
  };
}
