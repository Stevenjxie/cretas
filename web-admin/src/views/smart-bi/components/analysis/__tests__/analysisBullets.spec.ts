/**
 * analysisBullets.ts unit tests (2026-07-12).
 *
 * Contracts asserted:
 *   - Empty/all-zero input → [] (never a fabricated bullet)
 *   - null money fields (RBAC/k-anon) → omitted from the sentence, never "¥0"
 *   - Summary strings are compact and stable ("暂无数据" when nothing valid)
 *   - splitMarkdownBullets recognizes -, *, • and "1." / "1、" markers;
 *     falls back to the whole trimmed text when no markers are present
 */
import { describe, it, expect } from 'vitest';
import {
  memberRfmTierBullets,
  memberRfmTierSummary,
  lifecycleBullets,
  lifecycleSummary,
  rfmScatterBullets,
  rfmScatterSummary,
  memberProfileBullets,
  memberProfileSummary,
  voidRateBullets,
  voidRateSummary,
  zoneEfficiencyBullets,
  zoneEfficiencySummary,
  platformOverviewBullets,
  platformOverviewSummary,
  platformCompareBullets,
  platformCompareSummary,
  storeRankingBullets,
  storeRankingSummary,
  reviewTrendBullets,
  reviewTrendSummary,
  financeOverviewBullets,
  financeOverviewSummary,
  salesOverviewBullets,
  salesOverviewSummary,
  trendOverviewBullets,
  trendOverviewSummary,
  kpiOverviewBullets,
  kpiOverviewSummary,
  splitMarkdownBullets,
} from '../analysisBullets';

describe('memberRfmTierBullets / memberRfmTierSummary', () => {
  it('returns [] and "暂无数据" for empty input', () => {
    expect(memberRfmTierBullets([])).toEqual([]);
    expect(memberRfmTierBullets(null)).toEqual([]);
    expect(memberRfmTierSummary([])).toBe('暂无数据');
  });

  it('highlights the largest tier and At Risk cohort with real numbers', () => {
    const rows = [
      { rfmTier: 'At Risk', memberCount: 1942, totalCumSpend: 8410000, avgSpendInterval: 60 },
      { rfmTier: 'Hibernating', memberCount: 1192, totalCumSpend: 2000000, avgSpendInterval: 40 },
      { rfmTier: 'New', memberCount: 1080, totalCumSpend: null, avgSpendInterval: null },
    ];
    const bullets = memberRfmTierBullets(rows);
    expect(bullets.length).toBeGreaterThan(0);
    expect(bullets.some((b) => b.includes('1942'))).toBe(true);
    expect(bullets.some((b) => b.includes('重要挽留客户'))).toBe(true);

    const summary = memberRfmTierSummary(rows);
    expect(summary).toContain('1942');
    expect(summary).toContain('/');
  });

  it('filters out zero-member rows and never fabricates a spend figure', () => {
    const rows = [
      { rfmTier: 'Lost', memberCount: 0, totalCumSpend: 5000, avgSpendInterval: 10 },
      { rfmTier: 'New', memberCount: 3, totalCumSpend: null, avgSpendInterval: null },
    ];
    const bullets = memberRfmTierBullets(rows);
    expect(bullets.join(' ')).not.toContain('Lost');
    // totalCumSpend null → must not print a fake ¥0
    expect(bullets.join(' ')).not.toContain('¥0');
  });
});

describe('lifecycleBullets / lifecycleSummary', () => {
  it('empty → []', () => {
    expect(lifecycleBullets([])).toEqual([]);
    expect(lifecycleSummary([])).toBe('暂无数据');
  });

  it('reports per-stage counts + dormant+churned combined risk group', () => {
    const rows = [
      { lifecycleStage: '活跃', memberCount: 5000, totalBalance: 100000 },
      { lifecycleStage: '沉睡', memberCount: 1500, totalBalance: null },
      { lifecycleStage: '流失', memberCount: 977, totalBalance: null },
    ];
    const bullets = lifecycleBullets(rows);
    expect(bullets.some((b) => b.includes('活跃会员 5000'))).toBe(true);
    expect(bullets.some((b) => b.includes('2477'))).toBe(true); // 1500+977 combined

    const summary = lifecycleSummary(rows);
    expect(summary).toContain('活跃5000人');
  });
});

describe('rfmScatterBullets / rfmScatterSummary', () => {
  it('empty → []', () => {
    expect(rfmScatterBullets([], 0)).toEqual([]);
    expect(rfmScatterSummary([], 0)).toBe('暂无数据');
  });

  it('reports the largest segment + suppressed count honestly', () => {
    const rows = [
      { rScore: 5, fScore: 1, mScore: 3, memberCount: 200, avgCumSpend: 3000 },
      { rScore: 2, fScore: 4, mScore: 5, memberCount: 50, avgCumSpend: 9000 },
    ];
    const bullets = rfmScatterBullets(rows, 12);
    expect(bullets.some((b) => b.includes('R5F1M3'))).toBe(true);
    expect(bullets.some((b) => b.includes('12'))).toBe(true);

    const summary = rfmScatterSummary(rows, 12);
    expect(summary).toContain('R5F1M3:200人');
    expect(summary).toContain('另有12人');
  });

  it('never fabricates a suppressed-count note when it is 0', () => {
    const rows = [{ rScore: 3, fScore: 3, mScore: 3, memberCount: 10, avgCumSpend: null }];
    const bullets = rfmScatterBullets(rows, 0);
    expect(bullets.join(' ')).not.toContain('隐去');
  });
});

describe('memberProfileBullets / memberProfileSummary', () => {
  it('empty/zero → []', () => {
    const empty = {
      memberCount: 0,
      totalBalance: null,
      tierDistribution: [],
      genderDistribution: [],
      birthMonthDistribution: [],
      birthMonthCoveragePct: null,
      birthMonthUnknownCount: 0,
      rechargeTrend: [],
      rechargeStoreCount: 0,
    };
    expect(memberProfileBullets(empty)).toEqual([]);
    expect(memberProfileSummary(empty)).toBe('暂无数据');
  });

  it('builds bullets from real refs, skipping null balance', () => {
    const profile = {
      memberCount: 7477,
      totalBalance: null, // RBAC-stripped
      tierDistribution: [
        { tier: '黄金卡', memberCount: 3000, totalBalance: null },
        { tier: '白银卡', memberCount: 1000, totalBalance: 50000 },
      ],
      genderDistribution: [
        { gender: '女', memberCount: 4000 },
        { gender: '男', memberCount: 3000 },
      ],
      birthMonthDistribution: [
        { birthMonth: 5, memberCount: 800 },
        { birthMonth: 1, memberCount: 300 },
      ],
      birthMonthCoveragePct: 57,
      birthMonthUnknownCount: 3215,
      rechargeTrend: [{ month: '2026-06', principal: 10000, bonus: 500 }],
      rechargeStoreCount: 1,
    };
    const bullets = memberProfileBullets(profile);
    expect(bullets.some((b) => b.includes('7477'))).toBe(true);
    expect(bullets.join(' ')).not.toContain('¥0'); // totalBalance null must not fabricate
    expect(bullets.some((b) => b.includes('黄金卡'))).toBe(true);
    expect(bullets.some((b) => b.includes('女'))).toBe(true);
    expect(bullets.some((b) => b.includes('5 月'))).toBe(true);
    expect(bullets.some((b) => b.includes('2026-06'))).toBe(true);

    const summary = memberProfileSummary(profile);
    expect(summary).toContain('7477');
    expect(summary).toContain('黄金卡3000人');
  });
});

describe('voidRateBullets / voidRateSummary', () => {
  it('empty/null voidRate → []/暂无数据', () => {
    expect(voidRateBullets({ voidRate: null, voidCount: 0, billCount: 0, breakdown: [] })).toEqual([]);
    expect(voidRateSummary({ voidRate: null, voidCount: 0, billCount: 0, breakdown: [] })).toBe('暂无数据');
  });

  it('reports the overall rate + top offending operator + most common reason', () => {
    const input = {
      voidRate: 2.35,
      voidCount: 47,
      billCount: 2000,
      breakdown: [
        { staffName: '张三', storeName: '门店A', voidCount: 20, billsHandled: 400, voidsPer100Bills: 5.0, topReason: '点错单' },
        { staffName: '李四', storeName: '门店B', voidCount: 10, billsHandled: 800, voidsPer100Bills: 1.25, topReason: '客户取消' },
        { staffName: '王五', storeName: '门店A', voidCount: 17, billsHandled: 300, voidsPer100Bills: 5.67, topReason: '点错单' },
      ],
    };
    const bullets = voidRateBullets(input);
    expect(bullets.some((b) => b.includes('2.35%'))).toBe(true);
    expect(bullets.some((b) => b.includes('王五') && b.includes('5.67'))).toBe(true); // highest rate wins, not highest raw count
    expect(bullets.some((b) => b.includes('点错单'))).toBe(true); // 20+17=37 > 客户取消's 10

    const summary = voidRateSummary(input);
    expect(summary).toContain('2.35%');
    expect(summary).toContain('王五(门店A)');
  });

  it('never fabricates a rate/reason bullet when data is absent', () => {
    const input = {
      voidRate: null,
      voidCount: 0,
      billCount: 0,
      breakdown: [{ staffName: '赵六', storeName: '门店C', voidCount: 0, billsHandled: 0, voidsPer100Bills: null, topReason: null }],
    };
    expect(voidRateBullets(input)).toEqual([]);
  });
});

describe('zoneEfficiencyBullets / zoneEfficiencySummary', () => {
  it('empty → []/暂无数据', () => {
    expect(zoneEfficiencyBullets({ totalRevenue: null, totalItemQty: 0, zones: [] })).toEqual([]);
    expect(zoneEfficiencySummary({ totalRevenue: null, totalItemQty: 0, zones: [] })).toBe('暂无数据');
  });

  it('highlights the top-revenue zone and a distinct top-quantity zone', () => {
    const input = {
      totalRevenue: 500000,
      totalItemQty: 9000,
      zones: [
        { zoneName: '一楼大厅', revenue: 300000, itemQty: 4000, revenuePct: 60 },
        { zoneName: '二楼包间', revenue: 150000, itemQty: 4500, revenuePct: 30 },
        { zoneName: '外卖', revenue: 50000, itemQty: 500, revenuePct: 10 },
      ],
    };
    const bullets = zoneEfficiencyBullets(input);
    expect(bullets.some((b) => b.includes('一楼大厅') && b.includes('60.0%'))).toBe(true);
    expect(bullets.some((b) => b.includes('二楼包间') && b.includes('4,500'))).toBe(true);

    const summary = zoneEfficiencySummary(input);
    expect(summary).toContain('一楼大厅');
    expect(summary).toContain('/4000件');
  });

  it('falls back to item-qty ranking and omits revenue bullets when revenue is RBAC-nulled', () => {
    const input = {
      totalRevenue: null,
      totalItemQty: 300,
      zones: [
        { zoneName: 'A区', revenue: null, itemQty: 200, revenuePct: null },
        { zoneName: 'B区', revenue: null, itemQty: 100, revenuePct: null },
      ],
    };
    const bullets = zoneEfficiencyBullets(input);
    expect(bullets.join(' ')).not.toContain('¥0');
    expect(bullets.join(' ')).not.toContain('营收最高');
    expect(bullets.some((b) => b.includes('A区') && b.includes('数量最高'))).toBe(true);
  });
});

describe('platformOverviewBullets / platformOverviewSummary', () => {
  it('zero totalReviews → []/暂无数据', () => {
    const empty = { totalReviews: 0, storeCount: 0, cityCount: 0, lowStarCount: 0, scoreCards: [], goodTags: [] };
    expect(platformOverviewBullets(empty)).toEqual([]);
    expect(platformOverviewSummary(empty)).toBe('暂无数据');
  });

  it('reports totals + top score dimension + low-star warning + top tag', () => {
    const input = {
      totalReviews: 19845,
      storeCount: 12,
      cityCount: 3,
      lowStarCount: 421,
      scoreCards: [
        { name: '星级', value: 4.6 },
        { name: '服务', value: 4.8 },
        { name: '环境', value: 4.5 },
      ],
      goodTags: [
        { tag: '味道好', count: 3200 },
        { tag: '分量足', count: 1800 },
      ],
    };
    const bullets = platformOverviewBullets(input);
    expect(bullets.some((b) => b.includes('19,845') || b.includes('19845'))).toBe(true);
    expect(bullets.some((b) => b.includes('服务') && b.includes('4.80'))).toBe(true); // highest dimension wins
    expect(bullets.some((b) => b.includes('421'))).toBe(true);
    expect(bullets.some((b) => b.includes('味道好'))).toBe(true);

    const summary = platformOverviewSummary(input);
    expect(summary).toContain('19845');
    expect(summary).toContain('味道好(3200)');
  });
});

describe('platformCompareBullets / platformCompareSummary', () => {
  it('empty → []/暂无数据', () => {
    expect(platformCompareBullets([])).toEqual([]);
    expect(platformCompareSummary([])).toBe('暂无数据');
  });

  it('highlights the highest-volume platform and the star-rating gap', () => {
    const rows = [
      { platform: '大众点评', reviewCount: 12000, avgStar: 4.5 },
      { platform: '美团', reviewCount: 7845, avgStar: 4.8 },
    ];
    const bullets = platformCompareBullets(rows);
    expect(bullets.some((b) => b.includes('大众点评') && b.includes('12,000'))).toBe(true);
    expect(bullets.some((b) => b.includes('美团') && b.includes('4.80'))).toBe(true);

    const summary = platformCompareSummary(rows);
    expect(summary).toContain('大众点评:12000条(4.50分)');
  });
});

describe('storeRankingBullets / storeRankingSummary', () => {
  it('empty → []/暂无数据', () => {
    expect(storeRankingBullets([])).toEqual([]);
    expect(storeRankingSummary([])).toBe('暂无数据');
  });

  it('reports the best store, the weakest store, and the most-low-star store', () => {
    const rows = [
      { store: '门店A', reviewCount: 5000, avgStar: 4.9, lowStarCount: 20 },
      { store: '门店B', reviewCount: 3000, avgStar: 4.1, lowStarCount: 150 },
    ];
    const bullets = storeRankingBullets(rows);
    expect(bullets.some((b) => b.includes('门店A') && b.includes('4.90'))).toBe(true);
    expect(bullets.some((b) => b.includes('门店B') && b.includes('补课对象'))).toBe(true);
    expect(bullets.some((b) => b.includes('门店B') && b.includes('150'))).toBe(true);
  });
});

describe('reviewTrendBullets / reviewTrendSummary', () => {
  it('empty → []/暂无数据', () => {
    expect(reviewTrendBullets([])).toEqual([]);
    expect(reviewTrendSummary([])).toBe('暂无数据');
  });

  it('reports the score delta between first/last month and the peak month', () => {
    const rows = [
      { month: '2026-01', reviewCount: 800, avgStar: 4.4 },
      { month: '2026-02', reviewCount: 1200, avgStar: 4.6 },
    ];
    const bullets = reviewTrendBullets(rows);
    expect(bullets.some((b) => b.includes('提升') && b.includes('0.20'))).toBe(true);
    expect(bullets.some((b) => b.includes('2026-02') && b.includes('1,200'))).toBe(true);
  });
});

describe('financeOverviewBullets / financeOverviewSummary', () => {
  it('zero billCount → []/暂无数据', () => {
    const empty = { totalRevenue: null, billCount: 0, avgBillValue: null, storeCount: 0, topStores: [] };
    expect(financeOverviewBullets(empty)).toEqual([]);
    expect(financeOverviewSummary(empty)).toBe('暂无数据');
  });

  it('reports total revenue + avg bill value + top store', () => {
    const input = {
      totalRevenue: 5000000,
      billCount: 12000,
      avgBillValue: 83.5,
      storeCount: 8,
      topStores: [
        { storeName: '旗舰店', revenue: 1200000, billCount: 3000 },
        { storeName: '分店A', revenue: 800000, billCount: 2000 },
      ],
    };
    const bullets = financeOverviewBullets(input);
    expect(bullets.some((b) => b.includes('12,000') && b.includes('8'))).toBe(true);
    expect(bullets.some((b) => b.includes('旗舰店'))).toBe(true);

    const summary = financeOverviewSummary(input);
    expect(summary).toContain('12000单');
  });
});

describe('salesOverviewBullets / salesOverviewSummary', () => {
  it('empty → []/暂无数据', () => {
    expect(salesOverviewBullets({ topProducts: [] })).toEqual([]);
    expect(salesOverviewSummary({ topProducts: [] })).toBe('暂无数据');
  });

  it('reports top-qty product and a distinct top-revenue product', () => {
    const input = {
      topProducts: [
        { productName: '招牌鱼', qtySold: 4000, revenue: 200000, billCount: 3000 },
        { productName: '龙虾套餐', qtySold: 500, revenue: 250000, billCount: 400 },
      ],
    };
    const bullets = salesOverviewBullets(input);
    expect(bullets.some((b) => b.includes('招牌鱼') && b.includes('4,000'))).toBe(true);
    expect(bullets.some((b) => b.includes('龙虾套餐'))).toBe(true);
  });
});

describe('trendOverviewBullets / trendOverviewSummary', () => {
  it('empty → []/暂无数据', () => {
    const empty = { dailyTrend: [], monthlyTrend: [], weekdayWeekend: { weekdayAvg: null, weekendAvg: null, weekdayDays: 0, weekendDays: 0 } };
    expect(trendOverviewBullets(empty)).toEqual([]);
    expect(trendOverviewSummary(empty)).toBe('暂无数据');
  });

  it('reports monthly growth, weekday/weekend split, and daily peak', () => {
    const input = {
      dailyTrend: [
        { date: '2026-06-01', revenue: 10000, billCount: 100 },
        { date: '2026-06-15', revenue: 25000, billCount: 220 },
      ],
      monthlyTrend: [
        { month: '2026-05', revenue: 400000 },
        { month: '2026-06', revenue: 480000 },
      ],
      weekdayWeekend: { weekdayAvg: 8000, weekendAvg: 15000, weekdayDays: 20, weekendDays: 10 },
    };
    const bullets = trendOverviewBullets(input);
    expect(bullets.some((b) => b.includes('增长'))).toBe(true);
    expect(bullets.some((b) => b.includes('周末明显更高'))).toBe(true);
    expect(bullets.some((b) => b.includes('2026-06-15'))).toBe(true);
  });
});

describe('kpiOverviewBullets / kpiOverviewSummary', () => {
  it('zero billCount → []/暂无数据', () => {
    const empty = {
      revenue: 0, billCount: 0, itemCount: 0, customerCount: 0, storeCount: 0,
      avgBillValue: null, itemsPerBill: null, avgPerCapita: null,
    };
    expect(kpiOverviewBullets(empty)).toEqual([]);
    expect(kpiOverviewSummary(empty)).toBe('暂无数据');
  });

  it('reports revenue + avg bill value + per-capita + items-per-bill', () => {
    const input = {
      revenue: 5000000, billCount: 12000, itemCount: 30000, customerCount: 8000, storeCount: 8,
      avgBillValue: 83.5, itemsPerBill: 2.5, avgPerCapita: 42.3,
    };
    const bullets = kpiOverviewBullets(input);
    expect(bullets.some((b) => b.includes('83.5') || b.includes('¥83.5'))).toBe(true);
    expect(bullets.some((b) => b.includes('42.3'))).toBe(true);
    expect(bullets.some((b) => b.includes('2.5'))).toBe(true);
  });
});

describe('splitMarkdownBullets', () => {
  it('empty/null → []', () => {
    expect(splitMarkdownBullets('')).toEqual([]);
    expect(splitMarkdownBullets(null)).toEqual([]);
    expect(splitMarkdownBullets(undefined)).toEqual([]);
  });

  it('splits "- " markdown list items', () => {
    const text = '- 第一条要点\n- 第二条要点\n- 第三条要点';
    expect(splitMarkdownBullets(text)).toEqual(['第一条要点', '第二条要点', '第三条要点']);
  });

  it('splits "* " and "• " markers', () => {
    expect(splitMarkdownBullets('* 要点A\n• 要点B')).toEqual(['要点A', '要点B']);
  });

  it('splits numbered "1." / "1、" markers', () => {
    expect(splitMarkdownBullets('1. 要点一\n2、要点二')).toEqual(['要点一', '要点二']);
  });

  it('falls back to whole trimmed text when no bullet markers found', () => {
    const text = '  这是一段没有列表标记的解读文字。  ';
    expect(splitMarkdownBullets(text)).toEqual(['这是一段没有列表标记的解读文字。']);
  });
});
