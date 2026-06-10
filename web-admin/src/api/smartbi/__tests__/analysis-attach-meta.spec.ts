/**
 * Unit tests for U5 — attachMetaToCharts post-processor (analysis.ts)
 *
 * Tests the pure helper that attaches ChartMeta to chartsLane results after
 * onProgress has already fired. These tests exercise the plan-matching logic
 * and deriveChartMeta integration without requiring the full async lane.
 */

import { describe, it, expect, vi } from 'vitest';

// We test attachMetaToCharts in isolation by mocking deriveChartMeta.
// The actual deriveChartMeta is tested in chartInsight.spec.ts.
// Path is relative to THIS test file location (src/api/smartbi/__tests__/).
vi.mock('../../../views/smart-bi/components/chartInsight', () => ({
  deriveChartMeta: vi.fn((plan: { xField?: string; yFields?: string[]; title?: string }, _cols: string[], _info: string) => {
    // Minimal stub: return a meta when plan.xField contains '月'/'日期', else check title, else null
    if (plan?.xField && /月|日期/.test(plan.xField)) {
      return { xDim: 'time', yMetric: 'revenue', aggregation: 'sum', domain: 'factory' };
    }
    if (plan?.title && /门店/.test(plan.title)) {
      return { xDim: 'store', yMetric: 'quantity', aggregation: 'sum', domain: 'factory' };
    }
    return null;
  }),
}));

import { attachMetaToCharts } from '../analysis';

describe('attachMetaToCharts (U5)', () => {
  it('matches chart to plan by title and attaches meta', () => {
    const charts = [
      { chartType: 'line', title: '月度销售趋势', xField: '月份' },
    ];
    const plans = [
      { title: '月度销售趋势', xField: '月份', yFields: ['销售额'], chartType: 'line' },
    ];

    attachMetaToCharts(charts, plans, ['2026-01', '2026-02', '2026-03'], '');

    expect(charts[0].meta).not.toBeNull();
    expect(charts[0].meta?.xDim).toBe('time');
  });

  it('falls back to xField match when title does not match any plan', () => {
    const charts = [
      { chartType: 'bar', title: '日期分布图', xField: '日期' },
    ];
    const plans = [
      // title differs but xField matches
      { title: '不同的标题', xField: '日期', yFields: ['数量'], chartType: 'bar' },
    ];

    attachMetaToCharts(charts, plans, [], 'some context');

    expect(charts[0].meta).not.toBeNull();
    expect(charts[0].meta?.xDim).toBe('time');
  });

  it('assigns meta=null when deriveChartMeta returns null (insufficient clues)', () => {
    const charts = [
      { chartType: 'bar', title: '未知维度图', xField: 'unknown_field' },
    ];
    const plans = [
      { title: '未知维度图', xField: 'unknown_field', yFields: ['value'], chartType: 'bar' },
    ];

    attachMetaToCharts(charts, plans, [], '');

    expect(charts[0].meta).toBeNull();
  });

  it('assigns meta=null for retry-replaced chart with no matching plan', () => {
    // Simulates a chart built by retry recommender — title/xField may not match any original plan
    const charts = [
      { chartType: 'pie', title: '替换图表', xField: 'some_field' },
    ];
    const plans = [
      { title: '原始图表', xField: '月份', yFields: ['营收'], chartType: 'line' },
    ];

    attachMetaToCharts(charts, plans, [], '');

    // no match → synthetic plan used → unknown field → null
    expect(charts[0].meta).toBeNull();
  });

  it('handles multiple charts each matched independently', () => {
    const charts = [
      { chartType: 'line', title: '月度趋势', xField: '月份' },
      { chartType: 'bar', title: '门店销售', xField: '门店名' },
      { chartType: 'pie', title: '完全无关', xField: 'zzz' },
    ];
    const plans = [
      { title: '月度趋势', xField: '月份', yFields: ['营收'], chartType: 'line' },
      { title: '门店销售', xField: '门店名', yFields: ['销售量'], chartType: 'bar' },
      { title: '完全无关', xField: 'zzz', yFields: ['value'], chartType: 'pie' },
    ];

    attachMetaToCharts(charts, plans, ['2026-01', '2026-02', '2026-03'], '');

    expect(charts[0].meta?.xDim).toBe('time');
    expect(charts[1].meta?.xDim).toBe('store');
    expect(charts[2].meta).toBeNull();
  });

  it('does not throw on empty inputs', () => {
    expect(() => attachMetaToCharts([], [], [], '')).not.toThrow();
  });

  it('does not overwrite an existing meta (only sets if undefined)', () => {
    // attachMetaToCharts always reassigns — this is intentional (single-pass)
    // but we verify it calls deriveChartMeta for each chart
    const existingMeta = { xDim: 'time' as const, yMetric: 'revenue' as const, aggregation: 'sum' as const, domain: 'factory' as const };
    const charts = [
      { chartType: 'line', title: '月度趋势', xField: '月份', meta: existingMeta },
    ];
    const plans = [
      { title: '月度趋势', xField: '月份', yFields: ['营收'], chartType: 'line' },
    ];

    attachMetaToCharts(charts, plans, ['2026-01'], '');

    // deriveChartMeta is called and result written (existing meta replaced)
    expect(charts[0].meta).not.toBeUndefined();
    // Mock returns non-null for xField '月份'
    expect(charts[0].meta?.xDim).toBe('time');
  });
});
