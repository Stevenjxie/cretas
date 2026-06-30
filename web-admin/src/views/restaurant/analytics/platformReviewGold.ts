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
export interface BenchmarkCard {
  title: string
  scope: string
  status: 'ready' | 'needs-data'
  headline: string
  detail: string
  action: string
}

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
  benchmarkCards: BenchmarkCard[]
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
  const benchmarkCards = buildBenchmarkCards(summary, platforms, goodTags, storeRanking, trend)

  return {
    isEmpty,
    totalReviews: total,
    summary,
    scoreCards,
    platforms,
    goodTags,
    storeRanking,
    trend,
    benchmarkCards,
  }
}

function buildBenchmarkCards(
  summary: ReviewSummary | null,
  platforms: ReviewPlatform[],
  goodTags: ReviewGoodTag[],
  storeRanking: ReviewStoreRow[],
  trend: ReviewTrendPoint[],
): BenchmarkCard[] {
  if (!summary || num(summary.totalReviews) === 0) return []

  const cards: BenchmarkCard[] = []
  const rankedStores = storeRanking
    .filter((s) => typeof s.avgStar === 'number')
    .slice()
    .sort((a, b) => (b.avgStar ?? 0) - (a.avgStar ?? 0))
  if (rankedStores.length >= 2) {
    const best = rankedStores[0]
    const weakest = rankedStores[rankedStores.length - 1]
    cards.push({
      title: '连锁内门店对标',
      scope: `${rankedStores.length} 家门店`,
      status: 'ready',
      headline: `${best.store} 口碑最高，${weakest.store} 是当前补课对象`,
      detail: `最高 ${fmt(best.avgStar)} 星，最低 ${fmt(weakest.avgStar)} 星；不要只看总分，要拆服务、环境、口味和低星占比。`,
      action: `把 ${weakest.store} 的低星评价先按“出餐慢 / 服务响应 / 口味波动”归因，再复制 ${best.store} 的高频好评做法。`,
    })
  }

  const sortedPlatforms = platforms
    .filter((p) => typeof p.avgStar === 'number')
    .slice()
    .sort((a, b) => (b.avgStar ?? 0) - (a.avgStar ?? 0))
  if (sortedPlatforms.length >= 2) {
    const top = sortedPlatforms[0]
    const bottom = sortedPlatforms[sortedPlatforms.length - 1]
    cards.push({
      title: '平台口碑对标',
      scope: '大众点评 / 美团',
      status: 'ready',
      headline: `${top.platform} 评分高于 ${bottom.platform}`,
      detail: `${top.platform} ${fmt(top.avgStar)} 星，${bottom.platform} ${fmt(bottom.avgStar)} 星；如果平台差距长期存在，说明不是单店偶发，而是平台客群或履约体验不同。`,
      action: `把 ${bottom.platform} 的低星评论单独拉出，优先检查外卖履约、套餐描述和到店服务承诺是否一致。`,
    })
  }

  if (goodTags.length > 0) {
    const topTags = goodTags.slice(0, 3).map((t) => t.tag).join(' / ')
    cards.push({
      title: '同类产品口碑对标',
      scope: '口味/品质标签',
      status: 'ready',
      headline: `当前优势集中在 ${topTags}`,
      detail: '这里展示的是评价里的口味/品质标签，不等同于菜名销量；适合判断产品心智，而不是直接决定砍菜。',
      action: '把这些标签映射到真实菜品和毛利表：高口碑高毛利做主推，高口碑低毛利调规格或套餐，高差评高销量优先整改。',
    })
  }

  if (trend.length >= 2) {
    const first = trend[0]
    const last = trend[trend.length - 1]
    const delta = typeof first.avgStar === 'number' && typeof last.avgStar === 'number'
      ? last.avgStar - first.avgStar
      : null
    cards.push({
      title: '趋势对标',
      scope: `${trend.length} 个月`,
      status: 'ready',
      headline: delta === null ? '已有评价趋势，可继续叠加营收和活动日历' : `评分${delta >= 0 ? '提升' : '下降'} ${Math.abs(delta).toFixed(2)} 分`,
      detail: '趋势不是只看升降，要和促销、排班、供应商切换、爆品上新放在同一张时间轴上看。',
      action: '下一步把月度评价变化和收入报表、采购价格、退菜/沽清记录联动，找出评分变化背后的经营动作。',
    })
  }

  cards.push({
    title: '商圈 / 同菜系外部对标',
    scope: '待授权外部基准',
    status: 'needs-data',
    headline: '需要接入周边竞品、商场/商圈、同菜系榜单数据后才能给真实排名',
    detail: '建议来源优先级：商家后台/开放平台/授权导出 > 已购买行业报告 > 合规第三方数据；不要把无授权网页爬取当长期数据底座。',
    action: '先定义商圈半径、菜系、客单价带、人均区间和竞品清单，再入库评分、评论量、套餐价、热卖标签、上榜情况。',
  })

  cards.push({
    title: '采购 / 成本 / 毛利对标',
    scope: '待接入同品类成本基准',
    status: 'needs-data',
    headline: '要把“这条鱼贵不贵、毛利正不正常”做成同品类区间对比',
    detail: '本地已有真实餐饮连锁采购和销售文件，可先沉淀成品类基准表；上线后再接供应商报价、市场价和历史采购价。',
    action: '按 SKU 标准名、规格、净重、出成率、采购城市、供应商建立基准，输出 P25/P50/P75 区间和异常原因。',
  })

  return cards
}

function fmt(v: number | null | undefined): string {
  return typeof v === 'number' && Number.isFinite(v) ? v.toFixed(2) : '-'
}
