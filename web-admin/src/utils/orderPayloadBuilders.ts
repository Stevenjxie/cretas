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

export interface BomPurchaseOrderParent {
  supplierId: string
  purchaseType: string
  expectedDate: string
  remark: string
}

export interface BomPurchaseOrderChild {
  materialId: string
  materialName: string
  quantity: number | string
  unit: string
  unitPrice: number | string
  taxRate?: number | string | null
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

function normalizePurchaseType(value: string): string {
  return value === 'HQ_UNIFIED' || value === 'URGENT' ? value : 'DIRECT'
}

export function buildBomPurchaseOrderPayload(
  parent: BomPurchaseOrderParent,
  children: BomPurchaseOrderChild[],
  orderDate: string,
) {
  return {
    supplierId: parent.supplierId || null,
    purchaseType: normalizePurchaseType(parent.purchaseType),
    orderDate,
    expectedDeliveryDate: parent.expectedDate || null,
    remark: parent.remark || '',
    items: children.map((child) => ({
      materialTypeId: child.materialId,
      materialName: child.materialName,
      quantity: Number(child.quantity) || 0,
      unit: child.unit || 'kg',
      unitPrice: Number(child.unitPrice) || 0,
      taxRate: child.taxRate == null || child.taxRate === '' ? null : Number(child.taxRate),
    })),
  }
}
