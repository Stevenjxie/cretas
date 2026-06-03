import { describe, it, expect } from 'vitest'
import { goldTrendBundleToViewModel, type GoldTrendBundlePayload } from '../goldTrendBundle'

function fullPayload(): GoldTrendBundlePayload {
  return {
    dailyTrend: [
      { date: '2026-01-02', revenue: 1200, billCount: 30 },
      { date: '2026-01-01', revenue: 1000, billCount: 25 },
    ],
    weekdayWeekend: { weekdayAvg: 800, weekendAvg: 1500, weekdayDays: 20, weekendDays: 8 },
    monthlyTrend: [
      { month: '2026-02', revenue: 50000 },
      { month: '2026-01', revenue: 42000 },
    ],
  }
}

describe('goldTrendBundleToViewModel (WS4b #12)', () => {
  it('prefers monthly series and sorts it ascending by month', () => {
    const vm = goldTrendBundleToViewModel(fullPayload())!
    expect(vm.granularity).toBe('monthly')
    expect(vm.categories).toEqual(['2026-01', '2026-02'])
    expect(vm.revenue).toEqual([42000, 50000])
    expect(vm.pointCount).toBe(2)
  })

  it('falls back to daily (sorted) when no monthly points exist', () => {
    const p = fullPayload()
    p.monthlyTrend = []
    const vm = goldTrendBundleToViewModel(p)!
    expect(vm.granularity).toBe('daily')
    expect(vm.categories).toEqual(['2026-01-01', '2026-01-02'])
    expect(vm.revenue).toEqual([1000, 1200])
    expect(vm.pointCount).toBe(2)
  })

  it('exposes weekday/weekend averages when at least one side is non-zero', () => {
    const vm = goldTrendBundleToViewModel(fullPayload())!
    expect(vm.weekdayWeekend).toEqual({
      weekdayAvg: 800,
      weekendAvg: 1500,
      weekdayDays: 20,
      weekendDays: 8,
    })
  })

  it('drops weekday/weekend block when both averages are zero/null', () => {
    const p = fullPayload()
    p.weekdayWeekend = { weekdayAvg: 0, weekendAvg: 0, weekdayDays: 0, weekendDays: 0 }
    expect(goldTrendBundleToViewModel(p)!.weekdayWeekend).toBeNull()

    const p2 = fullPayload()
    p2.weekdayWeekend = null
    expect(goldTrendBundleToViewModel(p2)!.weekdayWeekend).toBeNull()
  })

  it('returns null on empty / missing payload (honest empty)', () => {
    expect(goldTrendBundleToViewModel(null)).toBeNull()
    expect(goldTrendBundleToViewModel(undefined)).toBeNull()
    expect(goldTrendBundleToViewModel({})).toBeNull()
    expect(goldTrendBundleToViewModel({ monthlyTrend: [], dailyTrend: [] })).toBeNull()
  })

  it('tolerates RBAC-nulled revenue → coerces to 0 (chart still renders categories)', () => {
    const p: GoldTrendBundlePayload = {
      monthlyTrend: [
        { month: '2026-01', revenue: null },
        { month: '2026-02', revenue: 30000 },
      ],
      weekdayWeekend: { weekdayAvg: null, weekendAvg: 1500, weekdayDays: 20, weekendDays: 8 },
    }
    const vm = goldTrendBundleToViewModel(p)!
    expect(vm.revenue).toEqual([0, 30000])
    // one side still non-zero → block kept, null side coerced to 0
    expect(vm.weekdayWeekend).toEqual({
      weekdayAvg: 0,
      weekendAvg: 1500,
      weekdayDays: 20,
      weekendDays: 8,
    })
  })

  it('coerces non-finite revenue (NaN/undefined) to 0 defensively', () => {
    const p: GoldTrendBundlePayload = {
      monthlyTrend: [
        { month: '2026-01', revenue: undefined as unknown as number },
        { month: '2026-02', revenue: 100 },
      ],
    }
    const vm = goldTrendBundleToViewModel(p)!
    expect(vm.revenue).toEqual([0, 100])
  })
})
