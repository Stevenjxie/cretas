export interface CustomerMaterialReceiptForm {
  materialTypeId: string;
  receiptDate: string;
  receiptQuantity: number;
  quantityUnit: string;
  totalWeight: number;
  totalValue: number;
  warehouseId: string;
  notes: string;
}

export type CustomerMaterialReceiptPayload = CustomerMaterialReceiptForm;

export function buildCustomerMaterialReceiptPayload(
  form: CustomerMaterialReceiptForm,
  orderId: string,
): CustomerMaterialReceiptPayload {
  // The order is authoritative from the URL path. Source and supplier fields
  // are intentionally not accepted from the browser payload.
  void orderId;
  return {
    materialTypeId: form.materialTypeId,
    receiptDate: form.receiptDate,
    receiptQuantity: form.receiptQuantity,
    quantityUnit: form.quantityUnit,
    totalWeight: form.totalWeight,
    totalValue: form.totalValue,
    warehouseId: form.warehouseId,
    notes: form.notes,
  };
}
