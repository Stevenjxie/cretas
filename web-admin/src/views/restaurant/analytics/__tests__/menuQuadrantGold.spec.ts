import { describe, it, expect } from 'vitest'
import { goldQuadrantToData, type GoldQuadrantPayload } from '../menuQuadrantGold'

function payload(): GoldQuadrantPayload {
  return {
    items: [
      { name: '招牌青花椒鱼', qty: 100, revenue: 5000, quadrant: '明星' },
      { name: '高价毛血旺', qty: 20, revenue: 6000, quadrant: '金牛' },
      { name: '走量米饭', qty: 200, revenue: 800, quadrant: '潜力' },
      { name: '冷门小菜', qty: 10, revenue: 300, quadrant: '瘦狗' },
    ],
    qtyMedian: 20,
    revenueMedian: 5000,
  }
}

describe('goldQuadrantToData (WS3 #1)', () => {
  it('maps 中文 quadrant → English key', () => {
    const d = goldQuadrantToData(payload())!
    const byName = Object.fromEntries(d.items.map((i) => [i.name, i.quadrant]))
    expect(byName['招牌青花椒鱼']).toBe('Star')
    expect(byName['高价毛血旺']).toBe('Plow')
    expect(byName['走量米饭']).toBe('Puzzle')
    expect(byName['冷门小菜']).toBe('Dog')
  })

  it('maps qty → quantity and computes unitProfit = revenue / qty', () => {
    const d = goldQuadrantToData(payload())!
    const star = d.items.find((i) => i.name === '招牌青花椒鱼')!
    expect(star.quantity).toBe(100)
    expect(star.revenue).toBe(5000)
    expect(star.unitProfit).toBe(50) // 5000 / 100
    const cow = d.items.find((i) => i.name === '高价毛血旺')!
    expect(cow.unitProfit).toBe(300) // 6000 / 20
  })

  it('builds per-quadrant summary counts', () => {
    const d = goldQuadrantToData(payload())!
    expect(d.summary).toEqual({ starCount: 1, plowCount: 1, puzzleCount: 1, dogCount: 1 })
  })

  it('carries qtyMedian and computes profitMedian (lower-middle of unitProfit)', () => {
    const d = goldQuadrantToData(payload())!
    expect(d.qtyMedian).toBe(20)
    // unitProfit values: 50, 300, 4, 30 → sorted [4,30,50,300], n=4, (n-1)//2=1 → 30
    expect(d.profitMedian).toBe(30)
  })

  it('returns null on empty / missing payload (honest empty)', () => {
    expect(goldQuadrantToData(null)).toBeNull()
    expect(goldQuadrantToData(undefined)).toBeNull()
    expect(goldQuadrantToData({ items: [], qtyMedian: 0, revenueMedian: 0 })).toBeNull()
  })

  it('tolerates RBAC-nulled revenue (non-price-view role) → revenue 0, unitProfit 0', () => {
    const stripped: GoldQuadrantPayload = {
      items: [{ name: '招牌', qty: 100, revenue: null, quadrant: '明星' }],
      qtyMedian: 100,
      revenueMedian: null,
    }
    const d = goldQuadrantToData(stripped)!
    expect(d.items[0].revenue).toBe(0)
    expect(d.items[0].unitProfit).toBe(0)
    expect(d.items[0].quadrant).toBe('Star')
  })

  it('unknown 中文 quadrant falls back to Dog (defensive)', () => {
    const d = goldQuadrantToData({
      items: [{ name: 'x', qty: 1, revenue: 1, quadrant: '???' }],
      qtyMedian: 1,
      revenueMedian: 1,
    })!
    expect(d.items[0].quadrant).toBe('Dog')
  })
})
