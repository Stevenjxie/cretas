import { describe, expect, it } from 'vitest'
import { normalizeAiOrderDates } from '../aiOrderNormalization'

describe('AI order date normalization', () => {
  it('forces purchase expected delivery to tomorrow when the user says 明日交货', () => {
    const result = normalizeAiOrderDates(
      'PURCHASE_ORDER',
      { expectedDeliveryDate: '2024-01-01' },
      '采购牛肉，明日交货',
      new Date('2026-07-16T04:00:00Z'),
      'Asia/Singapore',
    )

    expect(result.expectedDeliveryDate).toBe('2026-07-17')
    expect(result.orderDate).toBe('2026-07-16')
  })

  it('does not rewrite explicit historical dates without relative-date language', () => {
    const result = normalizeAiOrderDates(
      'SALES_ORDER',
      { requiredDeliveryDate: '2024-01-01' },
      '补录2024年1月1日的销售单',
      new Date('2026-07-16T04:00:00Z'),
      'Asia/Singapore',
    )

    expect(result.requiredDeliveryDate).toBe('2024-01-01')
  })

  it('replaces an unrequested stale model date in a new sales order', () => {
    const result = normalizeAiOrderDates(
      'SALES_ORDER',
      { orderDate: '2024-01-01', requiredDeliveryDate: '2024-01-02' },
      '给老王创建一张牛肉销售单',
      new Date('2026-07-16T04:00:00Z'),
      'Asia/Singapore',
    )

    expect(result.orderDate).toBe('2026-07-16')
    expect(result.requiredDeliveryDate).toBe('2026-07-16')
  })

  it('keeps a future date inferred by the model', () => {
    const result = normalizeAiOrderDates(
      'PURCHASE_ORDER',
      { orderDate: '2026-07-16', expectedDeliveryDate: '2026-07-20' },
      '创建一张采购单，下周交货',
      new Date('2026-07-16T04:00:00Z'),
      'Asia/Singapore',
    )

    expect(result.expectedDeliveryDate).toBe('2026-07-20')
  })
})
