import { shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ref } from 'vue';
import AnalyticsStrip from '../AnalyticsStrip.vue';

const useChartInsightCalls: unknown[] = [];

vi.mock('@/composables/useFactoryId', () => ({
  useFactoryId: () => ref('DEMO_REST'),
}));

vi.mock('@/composables/useChartInsight', () => ({
  useChartInsight: (...args: unknown[]) => {
    useChartInsightCalls.push(args);
    return { insight: ref(null), loading: ref(false) };
  },
}));

vi.mock('@/utils/echarts', () => ({
  default: {
    init: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }),
    graphic: { LinearGradient: vi.fn() },
  },
}));

describe('AnalyticsStrip', () => {
  beforeEach(() => {
    useChartInsightCalls.length = 0;
  });

  it('passes the current factory id to chart insight tier2 requests', () => {
    shallowMount(AnalyticsStrip, {
      props: {
        rows: [
          { requisitionDate: '2026-06-01', requestedQuantity: 2, rawMaterialTypeId: 'fish' },
          { requisitionDate: '2026-06-02', requestedQuantity: 5, rawMaterialTypeId: 'fish' },
          { requisitionDate: '2026-06-03', requestedQuantity: 3, rawMaterialTypeId: 'pepper' },
          { requisitionDate: '2026-06-04', requestedQuantity: 8, rawMaterialTypeId: 'pepper' },
        ],
        dateField: 'requisitionDate',
        valueField: 'requestedQuantity',
        categoryField: 'rawMaterialTypeId',
      },
      global: {
        stubs: {
          'el-row': true,
          'el-col': true,
          'el-icon': true,
          ChartInsight: true,
        },
      },
    });

    expect(useChartInsightCalls).toHaveLength(2);
    for (const call of useChartInsightCalls) {
      const options = (call as unknown[])[2] as { factoryId: () => string };
      expect(options.factoryId()).toBe('DEMO_REST');
    }
  });

  it('uses operational metrics for chart insights instead of hard-coded revenue/store', () => {
    shallowMount(AnalyticsStrip, {
      props: {
        rows: [
          { stocktakingDate: '2026-06-01', differenceQuantity: -3, rawMaterialTypeId: 'fish' },
          { stocktakingDate: '2026-06-02', differenceQuantity: -1, rawMaterialTypeId: 'fish' },
          { stocktakingDate: '2026-06-03', differenceQuantity: 2, rawMaterialTypeId: 'pepper' },
        ],
        dateField: 'stocktakingDate',
        valueField: 'differenceQuantity',
        categoryField: 'rawMaterialTypeId',
        insightYMetric: 'quantity',
        rankByAbsoluteValue: true,
      },
      global: {
        stubs: {
          'el-row': true,
          'el-col': true,
          'el-icon': true,
          ChartInsight: true,
        },
      },
    });

    const trendSource = (useChartInsightCalls[0] as unknown[])[0] as () => { chart: { meta: { xDim: string; yMetric: string } } };
    const rankingSource = (useChartInsightCalls[1] as unknown[])[0] as () => { chart: { meta: { xDim: string; yMetric: string }; config: { series: Array<{ data: number[] }> } } };

    expect(trendSource().chart.meta).toMatchObject({ xDim: 'time', yMetric: 'quantity' });
    expect(rankingSource().chart.meta).toMatchObject({ xDim: 'category', yMetric: 'quantity' });
    expect(rankingSource().chart.config.series[0].data).toEqual([4, 2]);
  });
});
