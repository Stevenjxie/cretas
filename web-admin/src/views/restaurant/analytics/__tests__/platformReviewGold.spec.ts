import { describe, it, expect } from 'vitest'
import { buildPlatformReviewVM, type ReviewGoldData } from '../platformReviewGold'

function fullData(): ReviewGoldData {
  return {
    'review-summary': {
      totalReviews: 19845,
      avgStar: 4.79,
      avgService: 4.6,
      avgEnv: 4.55,
      avgTaste: 4.7,
      lowStarCount: 396,
      highStarCount: 15000,
      vipCount: 812,
      storeCount: 28,
      cityCount: 5,
      dimensionScores: [
        { name: '星级', value: 4.79 },
        { name: '服务', value: 4.6 },
        { name: '环境', value: 4.55 },
        { name: '口味', value: 4.7 },
      ],
    },
    'review-platform': {
      platforms: [
        { platform: '点评', reviewCount: 19189, avgStar: 4.8 },
        { platform: '美团', reviewCount: 656, avgStar: 4.57 },
      ],
    },
    'review-good-tags': {
      highStarCount: 15000,
      tags: [
        { tag: '味道好', count: 5998 },
        { tag: '实惠', count: 1791 },
      ],
    },
    'review-store-ranking': {
      dim: 'star',
      order: 'desc',
      stores: [
        { store: '大融城店', reviewCount: 3000, avgStar: 4.9, avgService: 4.8, avgEnv: 4.7, lowStarCount: 10 },
        { store: '万象城店', reviewCount: 2000, avgStar: 4.6, avgService: 4.5, avgEnv: 4.4, lowStarCount: 30 },
      ],
    },
    'review-trend': {
      months: [
        { month: '2025-01', reviewCount: 1500, avgStar: 4.8 },
        { month: '2025-02', reviewCount: 1700, avgStar: 4.75 },
      ],
      nullPeriodCount: 200,
    },
  }
}

describe('buildPlatformReviewVM (WS3 #3)', () => {
  it('full data → not empty, total from summary, all sections populated', () => {
    const vm = buildPlatformReviewVM(fullData())
    expect(vm.isEmpty).toBe(false)
    expect(vm.totalReviews).toBe(19845)
    expect(vm.scoreCards).toHaveLength(4)
    expect(vm.scoreCards[0]).toEqual({ name: '星级', value: 4.79 })
    expect(vm.platforms).toHaveLength(2)
    expect(vm.platforms[0].platform).toBe('点评')
    expect(vm.goodTags[0]).toEqual({ tag: '味道好', count: 5998 })
    expect(vm.storeRanking).toHaveLength(2)
    expect(vm.storeRanking[0].store).toBe('大融城店')
    expect(vm.trend).toHaveLength(2)
    expect(vm.benchmarkCards.length).toBeGreaterThanOrEqual(6)
    expect(vm.benchmarkCards.some((c) => c.title === '连锁内门店对标' && c.status === 'ready')).toBe(true)
    expect(vm.benchmarkCards.some((c) => c.title === '商圈 / 同菜系外部对标' && c.status === 'needs-data')).toBe(true)
    expect(vm.benchmarkCards.some((c) => c.title === '采购 / 成本 / 毛利对标' && c.status === 'needs-data')).toBe(true)
  })

  it('no summary → isEmpty true (honest empty for non-qhj tenant)', () => {
    const vm = buildPlatformReviewVM({})
    expect(vm.isEmpty).toBe(true)
    expect(vm.totalReviews).toBe(0)
    expect(vm.scoreCards).toEqual([])
    expect(vm.platforms).toEqual([])
    expect(vm.benchmarkCards).toEqual([])
  })

  it('summary with totalReviews=0 → isEmpty true', () => {
    const vm = buildPlatformReviewVM({ 'review-summary': { totalReviews: 0, dimensionScores: [] } })
    expect(vm.isEmpty).toBe(true)
  })

  it('null/undefined data map → isEmpty true, no throw', () => {
    expect(buildPlatformReviewVM(null).isEmpty).toBe(true)
    expect(buildPlatformReviewVM(undefined).isEmpty).toBe(true)
  })

  it('falls back to flat avg_* fields when dimensionScores missing', () => {
    const vm = buildPlatformReviewVM({
      'review-summary': {
        totalReviews: 100, avgStar: 4.5, avgService: 4.2, avgEnv: 4.1, avgTaste: 4.3,
        lowStarCount: 5, highStarCount: 80, vipCount: 10, storeCount: 2, cityCount: 1,
        dimensionScores: [],
      },
    })
    expect(vm.isEmpty).toBe(false)
    expect(vm.scoreCards).toHaveLength(4)
    expect(vm.scoreCards.map((c) => c.name)).toEqual(['星级', '服务', '环境', '口味'])
    expect(vm.scoreCards[0].value).toBe(4.5)
  })

  it('flat fallback drops null dimensions', () => {
    const vm = buildPlatformReviewVM({
      'review-summary': {
        totalReviews: 100, avgStar: 4.5, avgService: null, avgEnv: null, avgTaste: 4.3,
        lowStarCount: 5, highStarCount: 80, vipCount: 10, storeCount: 2, cityCount: 1,
        dimensionScores: [],
      },
    })
    // 服务/环境 null dropped → only 星级 + 口味
    expect(vm.scoreCards.map((c) => c.name)).toEqual(['星级', '口味'])
  })

  it('tolerates partial endpoint failure (only summary present)', () => {
    const vm = buildPlatformReviewVM({
      'review-summary': fullData()['review-summary'],
      // platform/good-tags/store-ranking/trend missing (failed calls)
    })
    expect(vm.isEmpty).toBe(false)
    expect(vm.platforms).toEqual([])
    expect(vm.goodTags).toEqual([])
    expect(vm.storeRanking).toEqual([])
    expect(vm.trend).toEqual([])
    expect(vm.benchmarkCards.map((c) => c.status)).toEqual(['needs-data', 'needs-data'])
  })
})
