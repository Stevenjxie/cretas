/**
 * WS3 Task 3 — pure view-model derivation for the 平台口碑 (platform.vue) page.
 *
 * platform.vue defaults to showing the EXISTING gold review data (19845 条点评
 * for qhj) via useGoldAnalytics over these endpoints:
 *   review-summary / review-platform / review-good-tags / review-store-ranking
 *   / review-trend
 *
 * After pythonFetch snake→camel, the payloads are:
 *  - review-summary: { totalReviews, avgStar, avgService, avgEnv, avgTaste,
 *      lowStarCount, highStarCount, vipCount, storeCount, cityCount,
 *      dimensionScores:[{name,value}] }
 *  - review-platform: { platforms:[{platform, reviewCount, avgStar, avgService,
 *      avgEnv}] }
 *  - review-good-tags: { highStarCount, tags:[{tag, count}] }
 *  - review-store-ranking: { dim, order, stores:[{store, reviewCount, avgStar,
 *      avgService, avgEnv, avgTaste, lowStarCount}] }
 *  - review-trend: { months:[{month, reviewCount, avgStar}], nullPeriodCount }
 *
 * This module keeps the data-shaping PURE (no Vue, no fetch) so it's unit
 * testable. The component just renders the returned view-model. Honesty rules:
 *  - source note count = summary.totalReviews (the real de-duped review count)
 *  - isEmpty = no summary OR totalReviews === 0 → component shows the upload
 *    empty state instead of all-zero cards (honest empty for non-qhj tenants)
 */

export interface ReviewSummary {
  totalReviews: number
  avgStar: number | null
  avgService: number | null
  avgEnv: number | null
  avgTaste: number | null
  lowStarCount: number
  highStarCount: number
  vipCount: number
  storeCount: number
  cityCount: number
  dimensionScores: Array<{ name: string; value: number }>
}

export interface ReviewPlatform { platform: string; reviewCount: number; avgStar: number | null; avgService?: number | null; avgEnv?: number | null }
export interface ReviewGoodTag { tag: string; count: number }
export interface ReviewStoreRow { store: string; reviewCount: number; avgStar: number | null; avgService: number | null; avgEnv: number | null; lowStarCount: number }
export interface ReviewTrendPoint { month: string; reviewCount: number; avgStar: number | null }

/** The map returned by useGoldAnalytics, keyed by endpoint slug. */
export interface ReviewGoldData {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  [endpoint: string]: any
}

export interface PlatformReviewViewModel {
  isEmpty: boolean
  totalReviews: number
  summary: ReviewSummary | null
  /** 4 dimension score cards (星级/服务/环境/口味) on the 5-point scale. */
  scoreCards: Array<{ name: string; value: number | null }>
  platforms: ReviewPlatform[]
  goodTags: ReviewGoodTag[]
  /** Stores sorted best-rated first for the 门店口碑排名 table. */
  storeRanking: ReviewStoreRow[]
  trend: ReviewTrendPoint[]
}

function num(v: unknown): number {
  return typeof v === 'number' && Number.isFinite(v) ? v : 0
}

/**
 * Build the platform-review view-model from the useGoldAnalytics data map.
 * Defensive against missing endpoints (a single failed call leaves that key
 * undefined). isEmpty drives the upload-only empty state.
 */
export function buildPlatformReviewVM(data: ReviewGoldData | null | undefined): PlatformReviewViewModel {
  const summaryRaw = data?.['review-summary'] as ReviewSummary | undefined
  const total = num(summaryRaw?.totalReviews)
  const isEmpty = !summaryRaw || total === 0

  const summary = summaryRaw ?? null

  // Prefer the ready-made dimensionScores list; fall back to the flat avg_*
  // fields if a future endpoint version drops the convenience list.
  let scoreCards: Array<{ name: string; value: number | null }> = []
  if (summary) {
    if (Array.isArray(summary.dimensionScores) && summary.dimensionScores.length > 0) {
      scoreCards = summary.dimensionScores.map((d) => ({ name: d.name, value: d.value }))
    } else {
      scoreCards = [
        { name: '星级', value: summary.avgStar ?? null },
        { name: '服务', value: summary.avgService ?? null },
        { name: '环境', value: summary.avgEnv ?? null },
        { name: '口味', value: summary.avgTaste ?? null },
      ].filter((c) => c.value !== null)
    }
  }

  const platforms: ReviewPlatform[] = Array.isArray(data?.['review-platform']?.platforms)
    ? data!['review-platform'].platforms
    : []

  const goodTags: ReviewGoodTag[] = Array.isArray(data?.['review-good-tags']?.tags)
    ? data!['review-good-tags'].tags
    : []

  const storeRanking: ReviewStoreRow[] = Array.isArray(data?.['review-store-ranking']?.stores)
    ? data!['review-store-ranking'].stores
    : []

  const trend: ReviewTrendPoint[] = Array.isArray(data?.['review-trend']?.months)
    ? data!['review-trend'].months
    : []

  return {
    isEmpty,
    totalReviews: total,
    summary,
    scoreCards,
    platforms,
    goodTags,
    storeRanking,
    trend,
  }
}
