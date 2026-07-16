import { describe, expect, it } from 'vitest'
import { buildBomPurchaseOrderPayload, buildBomSalesOrderPayload } from '../orderPayloadBuilders'

describe('BOM order payload builders', () => {
  it('uses the backend sales-order productTypeId contract', () => {
    const payload = buildBomSalesOrderPayload(
      { customerId: 'CUS-1', requiredDeliveryDate: '2026-07-17', remark: '' },
      [{ productId: 'PROD-1', productName: 'beef', quantity: 10, unit: 'box', unitPrice: 100 }],
    )

    expect(payload.items[0]).toMatchObject({ productTypeId: 'PROD-1' })
    expect(payload.items[0]).not.toHaveProperty('productId')
  })

  it('uses backend purchase-order field names and supplies orderDate', () => {
    const payload = buildBomPurchaseOrderPayload(
      { supplierId: 'SUP-1', purchaseType: 'DIRECT', expectedDate: '2026-07-17', remark: '' },
      [{ materialId: 'MAT-1', materialName: 'salt', quantity: 2, unit: 'kg', unitPrice: 3, taxRate: 9 }],
      '2026-07-16',
    )

    expect(payload).toMatchObject({
      orderDate: '2026-07-16',
      expectedDeliveryDate: '2026-07-17',
    })
    expect(payload.items[0]).toMatchObject({ materialTypeId: 'MAT-1', taxRate: 9 })
    expect(payload.items[0]).not.toHaveProperty('materialId')
    expect(payload).not.toHaveProperty('expectedDate')
  })
})
