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
