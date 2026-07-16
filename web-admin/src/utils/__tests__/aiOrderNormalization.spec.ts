import { describe, expect, it } from 'vitest'
import { buildAiTemporalContext, normalizeAiOrderDates } from '../aiOrderNormalization'

describe('AI order date normalization', () => {
  it('injects deterministic factory-local today and tomorrow into the prompt', () => {
    expect(buildAiTemporalContext(new Date('2026-07-16T04:00:00Z'), 'Asia/Singapore')).toContain(
      'today=2026-07-16; tomorrow=2026-07-17',
    )
  })

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
})
