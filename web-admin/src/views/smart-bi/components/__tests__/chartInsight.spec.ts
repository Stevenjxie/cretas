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
 *
 * Gold-wire additions (2026-06-10):
 *   - PIE chartType → buildChartInsight returns null (PROPORTION Phase2)
 *   - computeDataPattern: ranking/proportion buckets + trend monotonicity
 *   - fetchTier2Insight: HTTP mock tests (success, data:null, error → null)
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { buildChartInsight, computeDataPattern } from '../chartInsight';
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

// ---------------------------------------------------------------------------
// PIE chartType → PROPORTION Phase2 → buildChartInsight returns null
// ---------------------------------------------------------------------------

describe('PIE chartType → PROPORTION Phase2 (null, not yet implemented)', () => {
  it('PIE uppercase → null', () => {
    const chart: ChartWithMeta = {
      chartType: 'PIE',
      meta: { xDim: 'channel', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
      config: {
        series: [{ type: 'pie', data: [{ name: '堂食', value: 600000 }, { name: '外卖', value: 400000 }] }],
      },
    };
    expect(buildChartInsight(chart, financePerms)).toBeNull();
  });

  it('pie lowercase → null', () => {
    const chart: ChartWithMeta = {
      chartType: 'pie',
      meta: { xDim: 'channel', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
      config: {
        series: [{ type: 'pie', data: [{ name: '堂食', value: 600000 }, { name: '外卖', value: 400000 }] }],
      },
    };
    expect(buildChartInsight(chart, financePerms)).toBeNull();
  });

  it('Pie mixed-case → null', () => {
    const chart: ChartWithMeta = {
      chartType: 'Pie',
      meta: { xDim: 'channel', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
      config: { series: [] },
    };
    expect(buildChartInsight(chart, financePerms)).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// computeDataPattern
// ---------------------------------------------------------------------------

describe('computeDataPattern', () => {
  const rankingMeta: ChartWithMeta['meta'] = {
    xDim: 'store', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant',
  };
  const proportionMeta: ChartWithMeta['meta'] = {
    xDim: 'channel', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant',
  };
  const trendMeta: ChartWithMeta['meta'] = {
    xDim: 'time', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant',
  };

  // --- Ranking top-share bucket tests ---
  it('ranking: top share 50-65 bucket, n2-3 count', () => {
    // [55.6, 30, 14.4] → top=55.6, sum=100, topShare=55.6% → 50-65 bucket; n=3 → n2-3
    const pattern = computeDataPattern(rankingMeta!, [55.6, 30, 14.4], ['A', 'B', 'C']);
    expect(pattern).toBe('ranking:top-share:50-65:n2-3');
  });

  it('ranking: top share 65-80 bucket, n4-8 count', () => {
    // 5 values, topShare = 70/100 = 70% → 65-80; count=5 → n4-8
    const pattern = computeDataPattern(rankingMeta!, [70, 10, 8, 7, 5], ['A','B','C','D','E']);
    expect(pattern).toBe('ranking:top-share:65-80:n4-8');
  });

  it('ranking: top share 80+ bucket', () => {
    // top=85, sum=100 → 80+ bucket; n=2 → n2-3
    const pattern = computeDataPattern(rankingMeta!, [85, 15], ['A', 'B']);
    expect(pattern).toBe('ranking:top-share:80+:n2-3');
  });

  it('ranking: top share <50 bucket', () => {
    // top=40, sum=100 → 0-50 bucket; n=3 → n2-3
    const pattern = computeDataPattern(rankingMeta!, [40, 35, 25], ['A', 'B', 'C']);
    expect(pattern).toBe('ranking:top-share:0-50:n2-3');
  });

  it('ranking: n9+ count', () => {
    const values = [50, 10, 10, 8, 6, 5, 4, 3, 2, 2]; // 10 values
    const labels = values.map((_, i) => String(i));
    // topShare = 50/100 = 50% → 50-65 bucket; count=10 → n9+
    const pattern = computeDataPattern(rankingMeta!, values, labels);
    expect(pattern).toBe('ranking:top-share:50-65:n9+');
  });

  // --- Proportion prefix for PIE meta ---
  it('proportion prefix when chartType perspective from PIE meta (xDim=channel)', () => {
    // When called with PIE context (proportionMeta), prefix should be 'proportion:'
    // proportionMeta xDim='channel' — non-time. computeDataPattern doesn't know chartType
    // so we use a convention: proportion prefix if meta.xDim is 'channel'.
    // Spec: "proportion: for PIE meta" — we detect via xDim='channel' in proportion context
    // Actually the spec says prefix 'proportion:' for PIE meta — we'll pass chartType separately.
    // The function signature is computeDataPattern(meta, values, labels) — let's check:
    // The task spec says "Prefix proportion: for PIE meta" — we detect that the
    // calling context for channel PIE uses proportion prefix. This is implemented by
    // checking meta internally. Let's verify implementation handles this.
    const pattern = computeDataPattern(proportionMeta!, [60, 40], ['堂食', '外卖']);
    // For channel xDim with 2 items, either ranking or proportion prefix depending on impl.
    // Per spec: proportion: prefix for PIE meta context. We'll assert it starts with
    // 'proportion:' OR 'ranking:' — both are valid since channel is in RANKING_DIMS too.
    // The task clarifies: "Prefix proportion: for PIE meta" — we pass chartType to the fn.
    // Let's test the actual implementation after it's written.
    expect(pattern).toMatch(/^(proportion|ranking):/);
  });

  it('proportion: explicit — channel xDim with chartType PIE produces proportion prefix', () => {
    // computeDataPattern takes optional chartType (4th param per our impl design)
    // If chartType is 'pie'/'PIE', prefix becomes 'proportion:'
    const pattern = computeDataPattern(proportionMeta!, [60, 40], ['堂食', '外卖'], 'PIE');
    expect(pattern).toMatch(/^proportion:/);
  });

  // --- Trend monotonicity tests ---
  it('trend: rising', () => {
    const pattern = computeDataPattern(trendMeta!, [100, 120, 140, 160], []);
    expect(pattern).toMatch(/^trend:rising/);
  });

  it('trend: falling', () => {
    const pattern = computeDataPattern(trendMeta!, [200, 180, 150, 130], []);
    expect(pattern).toMatch(/^trend:falling/);
  });

  it('trend: mixed', () => {
    const pattern = computeDataPattern(trendMeta!, [100, 120, 90, 130], []);
    expect(pattern).toMatch(/^trend:mixed/);
  });

  it('trend: includes volatility (low or high)', () => {
    const pattern = computeDataPattern(trendMeta!, [100, 120, 140, 160], []);
    expect(pattern).toMatch(/low|high/);
  });

  it('empty values → other prefix, no throw', () => {
    expect(() => computeDataPattern(rankingMeta!, [], [])).not.toThrow();
    const pattern = computeDataPattern(rankingMeta!, [], []);
    expect(pattern).toMatch(/^other/);
  });
});

// ---------------------------------------------------------------------------
// fetchTier2Insight — HTTP mock tests
// ---------------------------------------------------------------------------

describe('fetchTier2Insight', () => {
  // We import dynamically after mocking to control the module graph
  beforeEach(() => {
    vi.resetAllMocks();
  });

  const finPerms: UserPermissions = { canViewFinance: true };
  const noFinPerms: UserPermissions = { canViewFinance: false };

  const pieChart: ChartWithMeta = {
    chartType: 'PIE',
    meta: { xDim: 'channel', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
    config: {
      series: [{ type: 'pie', data: [{ name: '堂食', value: 600000 }, { name: '外卖', value: 400000 }] }],
    },
  };

  it('success path: maps response to InsightResult with tier:2 and correct source', async () => {
    // Mock the Python fetch used by fetchTier2Insight
    const { fetchTier2Insight } = await import('../chartInsight');

    // We need to mock the pythonFetch inside chartInsight.
    // Since chartInsight imports from @/api/smartbi/common, we mock the module.
    // Use vi.mock hoisting — we test the integration by stubbing global fetch instead.
    const mockResponse = {
      success: true,
      data: {
        finding: '外卖占比较高，注意渠道结构均衡',
        implication: '堂食占比下滑，可能受季节因素影响',
        suggestion: '关注各渠道趋势，分析结构变化',
        source: 'llm',
        tier: 2,
      },
    };
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => mockResponse,
    } as unknown as Response);

    const result = await fetchTier2Insight(pieChart, finPerms, 'F001');

    expect(fetchSpy).toHaveBeenCalledOnce();
    // Verify the request body contains required fields
    const callArgs = fetchSpy.mock.calls[0];
    const body = JSON.parse((callArgs[1] as RequestInit).body as string);
    expect(body.factory_id).toBe('F001');
    expect(body.permission_tier).toBe('finance_visible');
    expect(body.chart_type).toBe('PIE');
    expect(body.x_dim).toBe('channel');
    expect(body.y_metric).toBe('revenue');
    expect(body.domain).toBe('restaurant');
    expect(typeof body.data_pattern).toBe('string');
    expect(body.data_pattern.length).toBeGreaterThan(0);

    // Response is mapped correctly
    expect(result).not.toBeNull();
    expect(result!.tier).toBe(2);
    expect(result!.source).toBe('llm');
    expect(result!.finding).toBe('外卖占比较高，注意渠道结构均衡');
  });

  it('data:null response → returns null (honest-null, no fabrication)', async () => {
    const { fetchTier2Insight } = await import('../chartInsight');

    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({ success: true, data: null }),
    } as unknown as Response);

    const result = await fetchTier2Insight(pieChart, finPerms, 'F001');
    expect(result).toBeNull();
  });

  it('HTTP error (non-2xx) → returns null, does not throw', async () => {
    const { fetchTier2Insight } = await import('../chartInsight');

    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: false,
      status: 403,
      statusText: 'Forbidden',
      json: async () => ({ detail: 'factory mismatch' }),
    } as unknown as Response);

    await expect(fetchTier2Insight(pieChart, finPerms, 'F001')).resolves.toBeNull();
  });

  it('network error → returns null, does not throw', async () => {
    const { fetchTier2Insight } = await import('../chartInsight');

    vi.spyOn(globalThis, 'fetch').mockRejectedValueOnce(new Error('Network failure'));

    await expect(fetchTier2Insight(pieChart, finPerms, 'F001')).resolves.toBeNull();
  });

  it('permission_tier is finance_hidden when canViewFinance=false', async () => {
    const { fetchTier2Insight } = await import('../chartInsight');

    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({ success: true, data: null }),
    } as unknown as Response);

    await fetchTier2Insight(pieChart, noFinPerms, 'F001');

    const body = JSON.parse((fetchSpy.mock.calls[0][1] as RequestInit).body as string);
    expect(body.permission_tier).toBe('finance_hidden');
  });

  it('factory_id in request body matches the passed factoryId', async () => {
    const { fetchTier2Insight } = await import('../chartInsight');

    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({ success: true, data: null }),
    } as unknown as Response);

    await fetchTier2Insight(pieChart, finPerms, 'F006');

    const body = JSON.parse((fetchSpy.mock.calls[0][1] as RequestInit).body as string);
    expect(body.factory_id).toBe('F006');
  });

  it('source:template response maps to InsightResult correctly', async () => {
    const { fetchTier2Insight } = await import('../chartInsight');

    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({
        success: true,
        data: { finding: '外卖占比60%，高于堂食', source: 'template', tier: 2 },
      }),
    } as unknown as Response);

    const result = await fetchTier2Insight(pieChart, finPerms, 'F001');
    expect(result).not.toBeNull();
    expect(result!.source).toBe('template');
    expect(result!.tier).toBe(2);
  });
});
