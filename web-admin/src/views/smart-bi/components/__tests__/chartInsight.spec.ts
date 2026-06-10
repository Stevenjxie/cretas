/**
 * U2 — chartInsight.ts TDD spec (2026-06-10).
 *
 * Phase 1 covers TREND + RANKING families.
 * Contracts (from spec §2.3 + plan U2):
 *   - TREND (xDim='time', LINE): ≥4 points; finding = direction + 涨跌幅
 *   - RANKING (xDim ∈ store/product/category, BAR): ≥2 with measurable diff;
 *       finding = 头尾倍差 + 头部占比
 *   - Data insufficient → null (no fabrication per "禁止降级")
 *   - RBAC: yMetric ∈ {revenue,margin,cost} + !canViewFinance → only ratios/%,
 *       NEVER absolute ¥ values
 *   - Suggestion verbs: observation only; NO causal-prescriptive verbs
 *   - meta absent → null (graceful, no crash)
 */

import { describe, it, expect } from 'vitest';
import { buildChartInsight } from '../chartInsight';
import type { ChartWithMeta, UserPermissions } from '../chartInsight';

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

const financePerms: UserPermissions = { canViewFinance: true };
const noFinancePerms: UserPermissions = { canViewFinance: false };

/** Build a minimal LINE chart object (TREND family) */
function makeTrend(values: number[], xLabels?: string[]): ChartWithMeta {
  return {
    chartType: 'line',
    title: 'Test Trend',
    meta: {
      xDim: 'time',
      yMetric: 'revenue',
      aggregation: 'sum',
      domain: 'restaurant',
    },
    config: {
      xAxis: { data: xLabels ?? values.map((_, i) => `2026-0${i + 1}`) },
      series: [{ type: 'line', data: values }],
    },
  };
}

/** Build a minimal BAR chart (RANKING family) */
function makeRanking(
  entries: Array<{ label: string; value: number }>,
  yMetric: ChartWithMeta['meta']['yMetric'] = 'revenue',
): ChartWithMeta {
  return {
    chartType: 'bar',
    title: 'Test Ranking',
    meta: {
      xDim: 'store',
      yMetric,
      aggregation: 'sum',
      domain: 'restaurant',
    },
    config: {
      xAxis: { data: entries.map((e) => e.label) },
      series: [{ type: 'bar', data: entries.map((e) => e.value) }],
    },
  };
}

// ---------------------------------------------------------------------------
// TREND tests
// ---------------------------------------------------------------------------

describe('TREND family', () => {
  it('monotonically rising ≥4 points → 上升 direction + 涨幅', () => {
    const result = buildChartInsight(
      makeTrend([100, 120, 140, 160]),
      financePerms,
    );
    expect(result).not.toBeNull();
    expect(result!.tier).toBe(1);
    expect(result!.source).toBe('rules');
    expect(result!.finding).toMatch(/上升|增长|涨/);
    // Should contain growth rate
    expect(result!.finding).toMatch(/\d+(\.\d+)?%/);
  });

  it('monotonically falling ≥4 points → 下降 direction + 跌幅', () => {
    const result = buildChartInsight(
      makeTrend([200, 180, 150, 130]),
      financePerms,
    );
    expect(result).not.toBeNull();
    expect(result!.finding).toMatch(/下降|减少|跌/);
    expect(result!.finding).toMatch(/\d+(\.\d+)?%/);
  });

  it('flat trend → 平稳 wording', () => {
    const result = buildChartInsight(
      makeTrend([100, 102, 99, 101]),
      financePerms,
    );
    expect(result).not.toBeNull();
    expect(result!.finding).toMatch(/平稳|稳定/);
  });

  it('< 4 points → null (data insufficient)', () => {
    expect(buildChartInsight(makeTrend([100, 120, 140]), financePerms)).toBeNull();
    expect(buildChartInsight(makeTrend([100]), financePerms)).toBeNull();
    expect(buildChartInsight(makeTrend([]), financePerms)).toBeNull();
  });

  it('exactly 4 points → valid insight (boundary)', () => {
    const result = buildChartInsight(makeTrend([50, 60, 70, 80]), financePerms);
    expect(result).not.toBeNull();
  });

  it('no series data → null', () => {
    const chart: ChartWithMeta = {
      chartType: 'line',
      title: 'Empty',
      meta: { xDim: 'time', yMetric: 'quantity', aggregation: 'sum', domain: 'factory' },
      config: { series: [] },
    };
    expect(buildChartInsight(chart, financePerms)).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// RANKING tests
// ---------------------------------------------------------------------------

describe('RANKING family', () => {
  it('2+ stores with measurable diff → 倍差 + 头部占比', () => {
    const result = buildChartInsight(
      makeRanking([
        { label: '大融城店', value: 1_200_000 },
        { label: '万象城店', value: 400_000 },
      ]),
      financePerms,
    );
    expect(result).not.toBeNull();
    expect(result!.tier).toBe(1);
    expect(result!.source).toBe('rules');
    // Should mention ratio (≈3.0倍)
    expect(result!.finding).toMatch(/\d+(\.\d+)?倍/);
    // Should mention top-share percent
    expect(result!.finding).toMatch(/\d+(\.\d+)?%/);
  });

  it('more than 2 entries — head + tail + top share', () => {
    const result = buildChartInsight(
      makeRanking([
        { label: '大店', value: 900_000 },
        { label: '中店', value: 500_000 },
        { label: '小店', value: 300_000 },
      ]),
      financePerms,
    );
    expect(result).not.toBeNull();
    expect(result!.finding).toMatch(/\d+(\.\d+)?倍/);
    expect(result!.finding).toMatch(/\d+(\.\d+)?%/);
  });

  it('< 2 entries → null (data insufficient)', () => {
    expect(
      buildChartInsight(makeRanking([{ label: '唯一店', value: 500_000 }]), financePerms),
    ).toBeNull();
    expect(buildChartInsight(makeRanking([]), financePerms)).toBeNull();
  });

  it('near-equal values (ratio < 1.1) → still returns a finding but no 倍 claim', () => {
    const result = buildChartInsight(
      makeRanking([
        { label: 'A', value: 1_000_000 },
        { label: 'B', value: 980_000 },
      ]),
      financePerms,
    );
    // Near-equal: should still return something (even just share info) or null
    // Spec says ≥2 with *measurable* diff; 1.02 < 1.1 threshold → null is acceptable
    // We accept either null or a non-倍 finding
    if (result !== null) {
      // Must not claim a multiplier when diff is trivial
      expect(result.finding).not.toMatch(/1\.[01]\d倍/);
    }
  });

  it('product xDim → also triggers RANKING', () => {
    const chart: ChartWithMeta = {
      chartType: 'bar',
      title: 'Product ranking',
      meta: { xDim: 'product', yMetric: 'quantity', aggregation: 'sum', domain: 'factory' },
      config: {
        xAxis: { data: ['猪蹄', '牛腱'] },
        series: [{ type: 'bar', data: [8000, 2000] }],
      },
    };
    const result = buildChartInsight(chart, financePerms);
    expect(result).not.toBeNull();
    expect(result!.finding).toMatch(/\d+(\.\d+)?倍/);
  });

  it('category xDim → also triggers RANKING', () => {
    const chart: ChartWithMeta = {
      chartType: 'bar',
      title: 'Category ranking',
      meta: { xDim: 'category', yMetric: 'cost', aggregation: 'sum', domain: 'finance' },
      config: {
        xAxis: { data: ['原料', '人工', '制造费用'] },
        series: [{ type: 'bar', data: [5000, 3000, 2000] }],
      },
    };
    const result = buildChartInsight(chart, financePerms);
    expect(result).not.toBeNull();
  });
});

// ---------------------------------------------------------------------------
// RBAC tests (🔒 red line — finance metric gating)
// ---------------------------------------------------------------------------

describe('RBAC — finance metric gating (§2.6 red line)', () => {
  const CAUSAL_RE = /复制|引流|加大|扩张|推广/;
  const ABS_YUAN_RE = /¥[\d,.]+万?亿?|[\d,.]+万?亿?元/;

  it('revenue yMetric + !canViewFinance → NO absolute ¥ in any field', () => {
    const chart = makeTrend([100, 120, 140, 160]);
    chart.meta.yMetric = 'revenue';
    const result = buildChartInsight(chart, noFinancePerms);
    // Must produce something (TREND with ≥4 points) but NO absolute money
    if (result !== null) {
      expect(result.finding).not.toMatch(ABS_YUAN_RE);
      expect(result.implication ?? '').not.toMatch(ABS_YUAN_RE);
      expect(result.suggestion ?? '').not.toMatch(ABS_YUAN_RE);
    }
  });

  it('margin yMetric + !canViewFinance → only ratios/% — no absolute ¥', () => {
    const result = buildChartInsight(
      makeRanking(
        [
          { label: '商品A', value: 350_000 },
          { label: '商品B', value: 100_000 },
        ],
        'margin',
      ),
      noFinancePerms,
    );
    if (result !== null) {
      const fullText = [result.finding, result.implication, result.suggestion]
        .filter(Boolean)
        .join(' ');
      expect(fullText).not.toMatch(ABS_YUAN_RE);
    }
  });

  it('cost yMetric + !canViewFinance → only ratios/% — no absolute ¥', () => {
    const chart = makeRanking(
      [
        { label: '原料', value: 5000 },
        { label: '人工', value: 2000 },
      ],
      'cost',
    );
    const result = buildChartInsight(chart, noFinancePerms);
    if (result !== null) {
      const fullText = [result.finding, result.implication, result.suggestion]
        .filter(Boolean)
        .join(' ');
      expect(fullText).not.toMatch(ABS_YUAN_RE);
    }
  });

  it('quantity yMetric + !canViewFinance → absolute numbers OK (not a finance metric)', () => {
    const chart = makeRanking(
      [
        { label: '猪蹄', value: 8000 },
        { label: '牛腱', value: 2000 },
      ],
      'quantity',
    );
    // quantity is not a finance metric so the constraint does not apply
    const result = buildChartInsight(chart, noFinancePerms);
    expect(result).not.toBeNull(); // should produce a valid insight
  });

  it('revenue yMetric + canViewFinance → absolute ¥ allowed (finance user)', () => {
    // With finance perms, absolute values may appear — test just that no crash + valid result
    const chart = makeRanking(
      [
        { label: '大融城店', value: 1_200_000 },
        { label: '万象城店', value: 400_000 },
      ],
      'revenue',
    );
    const result = buildChartInsight(chart, financePerms);
    expect(result).not.toBeNull();
    // No constraint on ¥ presence — just no crash
  });

  // Causal-prescriptive verb regression for ALL outputs
  it('no causal-prescriptive verbs in any suggestion (all cases)', () => {
    const cases: ChartWithMeta[] = [
      makeTrend([100, 120, 140, 160]),
      makeTrend([200, 180, 150, 130]),
      makeRanking([
        { label: '大店', value: 900_000 },
        { label: '小店', value: 300_000 },
      ]),
    ];
    for (const chart of cases) {
      for (const perms of [financePerms, noFinancePerms]) {
        const r = buildChartInsight(chart, perms);
        if (r?.suggestion) {
          expect(r.suggestion).not.toMatch(CAUSAL_RE);
        }
        if (r?.implication) {
          expect(r.implication).not.toMatch(CAUSAL_RE);
        }
        if (r?.finding) {
          expect(r.finding).not.toMatch(CAUSAL_RE);
        }
      }
    }
  });
});

// ---------------------------------------------------------------------------
// Meta-absent → graceful null
// ---------------------------------------------------------------------------

describe('meta-absent graceful fallback', () => {
  it('chart with no meta → null (no crash)', () => {
    const chart = {
      chartType: 'line',
      title: 'No meta',
      config: {
        xAxis: { data: ['Jan', 'Feb', 'Mar', 'Apr'] },
        series: [{ type: 'line', data: [100, 120, 140, 160] }],
      },
    } as unknown as ChartWithMeta;
    expect(() => buildChartInsight(chart, financePerms)).not.toThrow();
    expect(buildChartInsight(chart, financePerms)).toBeNull();
  });

  it('chart with meta=null → null (no crash)', () => {
    const chart = {
      chartType: 'bar',
      title: 'Null meta',
      meta: null,
      config: {
        xAxis: { data: ['A', 'B', 'C'] },
        series: [{ type: 'bar', data: [100, 200, 300] }],
      },
    } as unknown as ChartWithMeta;
    expect(() => buildChartInsight(chart, financePerms)).not.toThrow();
    expect(buildChartInsight(chart, financePerms)).toBeNull();
  });

  it('unrecognized meta family → null', () => {
    const chart: ChartWithMeta = {
      chartType: 'scatter',
      title: 'Unknown family',
      meta: {
        xDim: 'other',
        yMetric: 'other',
        aggregation: 'count',
        domain: 'factory',
      },
      config: {
        series: [{ type: 'scatter', data: [1, 2, 3] }],
      },
    };
    expect(buildChartInsight(chart, financePerms)).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// InsightResult shape tests
// ---------------------------------------------------------------------------

describe('InsightResult shape', () => {
  it('valid insight has required fields', () => {
    const result = buildChartInsight(
      makeTrend([100, 110, 125, 140]),
      financePerms,
    );
    expect(result).not.toBeNull();
    expect(typeof result!.finding).toBe('string');
    expect(result!.finding.length).toBeGreaterThan(0);
    expect(result!.source).toBe('rules');
    expect(result!.tier).toBe(1);
  });

  it('implication and suggestion are optional strings', () => {
    const result = buildChartInsight(
      makeRanking([
        { label: 'A', value: 9000 },
        { label: 'B', value: 1000 },
      ]),
      financePerms,
    );
    if (result !== null) {
      if (result.implication !== undefined) {
        expect(typeof result.implication).toBe('string');
      }
      if (result.suggestion !== undefined) {
        expect(typeof result.suggestion).toBe('string');
      }
    }
  });
});
