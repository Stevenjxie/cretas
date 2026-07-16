export interface SupplierPriceForm {
  supplierId: string;
  unitPrice: number;
  taxRate: number | null | undefined;
  deliveryDays: number | null | undefined;
  remark: string;
}

export function buildSupplierPricePayload(form: SupplierPriceForm) {
  return {
    supplierId: form.supplierId,
    unitPrice: form.unitPrice,
    taxRate: form.taxRate ?? undefined,
    deliveryDays: form.deliveryDays || undefined,
    remark: form.remark || undefined,
  };
}
