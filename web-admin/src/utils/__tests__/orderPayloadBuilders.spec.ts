import { describe, expect, it } from 'vitest'
import { buildBomSalesOrderPayload } from '../orderPayloadBuilders'

describe('BOM order payload builders', () => {
  it('uses the backend sales-order productTypeId contract', () => {
    const payload = buildBomSalesOrderPayload(
      { customerId: 'CUS-1', requiredDeliveryDate: '2026-07-17', remark: '' },
      [{ productId: 'PROD-1', productName: 'beef', quantity: 10, unit: 'box', unitPrice: 100 }],
    )

    expect(payload.items[0]).toMatchObject({ productTypeId: 'PROD-1' })
    expect(payload.items[0]).not.toHaveProperty('productId')
  })
})
