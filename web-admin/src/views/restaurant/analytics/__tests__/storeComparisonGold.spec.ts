import { describe, it, expect } from 'vitest'
import { goldStoreComparisonToData, type GoldStoreComparisonPayload } from '../storeComparisonGold'

function payload(): GoldStoreComparisonPayload {
  return {
    stores: [
      { name: '大融城店', revenue: 20000, orderCount: 800, avgTicket: 25, discountPct: 9.1 },
      { name: '万象城店', revenue: 5000, orderCount: 250, avgTicket: 20, discountPct: 15 },
      { name: '弱店', revenue: 2000, orderCount: 200, avgTicket: 10, discountPct: 0 },
    ],
    medianRevenue: 5000,
    weakStores: ['弱店'],
  }
}

describe('goldStoreComparisonToData (WS3 #2 + fu-discount)', () => {
  it('maps stores with name/revenue/orderCount/avgTicket and real discountPct', () => {
    const d = goldStoreComparisonToData(payload())!
    expect(d.stores).toHaveLength(3)
    const flagship = d.stores.find((s) => s.name === '大融城店')!
    expect(flagship.revenue).toBe(20000)
    expect(flagship.orderCount).toBe(800)
    expect(flagship.avgTicket).toBe(25)
    expect(flagship.discountPct).toBe(9.1) // real per-store 折扣率 from gold
    const mall = d.stores.find((s) => s.name === '万象城店')!
    expect(mall.discountPct).toBe(15)
  })

  it('defaults discountPct to 0 when a pre-fix backend omits the field', () => {
    const d = goldStoreComparisonToData({
      stores: [{ name: 'A', revenue: 1, orderCount: 1, avgTicket: 1 }],
      medianRevenue: 1,
      weakStores: [],
    })!
    expect(d.stores[0].discountPct).toBe(0)
  })

  it('carries weakStores and medianRevenue', () => {
    const d = goldStoreComparisonToData(payload())!
    expect(d.weakStores).toEqual(['弱店'])
    expect(d.medianRevenue).toBe(5000)
  })

  it('returns null on empty / missing payload (honest empty)', () => {
    expect(goldStoreComparisonToData(null)).toBeNull()
    expect(goldStoreComparisonToData(undefined)).toBeNull()
    expect(goldStoreComparisonToData({ stores: [], medianRevenue: 0, weakStores: [] })).toBeNull()
  })

  it('tolerates RBAC-nulled revenue / avgTicket → coerced to 0; discountPct (rate) survives', () => {
    const stripped: GoldStoreComparisonPayload = {
      // discountPct is a rate so RBAC does NOT strip it — it stays a real value.
      stores: [{ name: '大融城店', revenue: null, orderCount: 800, avgTicket: null, discountPct: 12.5 }],
      medianRevenue: null,
      weakStores: [],
    }
    const d = goldStoreComparisonToData(stripped)!
    expect(d.stores[0].revenue).toBe(0)
    expect(d.stores[0].avgTicket).toBe(0)
    expect(d.stores[0].orderCount).toBe(800) // non-money preserved
    expect(d.stores[0].discountPct).toBe(12.5) // rate preserved through RBAC
    expect(d.medianRevenue).toBe(0)
  })

  it('defends against missing weakStores array', () => {
    const d = goldStoreComparisonToData({
      stores: [{ name: 'A', revenue: 1, orderCount: 1, avgTicket: 1 }],
      medianRevenue: 1,
      weakStores: undefined as unknown as string[],
    })!
    expect(d.weakStores).toEqual([])
  })
})
