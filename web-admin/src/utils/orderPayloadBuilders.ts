export interface BomSalesOrderParent {
  customerId: string
  requiredDeliveryDate: string
  remark: string
}

export interface BomSalesOrderChild {
  productId: string
  productName: string
  quantity: number | string
  unit: string
  unitPrice: number | string
}

export function buildBomSalesOrderPayload(parent: BomSalesOrderParent, children: BomSalesOrderChild[]) {
  return {
    customerId: parent.customerId,
    salesperson: '',
    requiredDeliveryDate: parent.requiredDeliveryDate || null,
    remark: parent.remark || '',
    shippingIncluded: false,
    shippingFee: 0,
    extraFees: [] as unknown[],
    items: children.map((child) => ({
      productTypeId: child.productId,
      productName: child.productName,
      quantity: Number(child.quantity) || 0,
      unit: child.unit || 'kg',
      unitPrice: Number(child.unitPrice) || 0,
    })),
    customFields: {},
  }
}
