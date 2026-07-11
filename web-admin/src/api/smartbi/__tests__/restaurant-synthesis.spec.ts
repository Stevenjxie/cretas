import { beforeEach, describe, expect, it, vi } from 'vitest';

const pythonFetchMock = vi.fn();
vi.mock('../common', () => ({
  pythonFetch: (...args: unknown[]) => pythonFetchMock(...args),
  PYTHON_LLM_TIMEOUT_MS: 300000,
}));

import { askRestaurantSynthesis } from '../restaurant-synthesis';

describe('askRestaurantSynthesis', () => {
  beforeEach(() => {
    pythonFetchMock.mockReset();
  });

  it('calls the comprehensive-synthesis endpoint with question + session_id', async () => {
    pythonFetchMock.mockResolvedValue({ answer: 'x', charts: [], alerts: [] });

    await askRestaurantSynthesis('这两个月成本率咋样', 'sess-1');

    expect(pythonFetchMock).toHaveBeenCalledWith(
      '/api/smartbi/synthesis/comprehensive',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ question: '这两个月成本率咋样', session_id: 'sess-1' }),
      }),
    );
  });

  it('normalizes a flat {chartType,title,xAxis,series} chart into {type,title,option}', async () => {
    pythonFetchMock.mockResolvedValue({
      answer: '本店上月营收上升。',
      charts: [
        {
          chartType: 'bar',
          title: '门店营收排行',
          xAxis: { data: ['A店', 'B店'] },
          series: [{ name: '营收', type: 'bar', data: [1, 2] }],
        },
      ],
      alerts: [],
      source: 'llm',
    });

    const result = await askRestaurantSynthesis('哪家店营收最高');

    expect(result.success).toBe(true);
    expect(result.charts).toHaveLength(1);
    expect(result.charts[0]).toEqual({
      type: 'bar',
      title: '门店营收排行',
      option: {
        xAxis: { data: ['A店', 'B店'] },
        series: [{ name: '营收', type: 'bar', data: [1, 2] }],
        // needsValueAxis: bar series + xAxis present + no yAxis → auto value axis.
        yAxis: { type: 'value' },
      },
    });
  });

  it('passes through 反回扣 alerts unmodified when title+detail are present', async () => {
    const alert = {
      type: 'cost_ratio_rising',
      level: 'medium',
      title: '领料成本率环比上升',
      detail: '领料成本率环比+0.6个百分点（当前32.0%），疑似用料增加/漏损/回扣，建议核查物料用量与供应商。',
    };
    pythonFetchMock.mockResolvedValue({ answer: 'x', charts: [], alerts: [alert] });

    const result = await askRestaurantSynthesis('成本率是不是涨了');

    expect(result.alerts).toEqual([alert]);
  });

  it('drops a chart with no renderable option (defensive, never crashes the panel)', async () => {
    pythonFetchMock.mockResolvedValue({
      answer: 'x',
      charts: [{ chartType: 'bar', title: '空图' }],
      alerts: [],
    });

    const result = await askRestaurantSynthesis('q');

    expect(result.charts).toEqual([]);
  });

  it('drops an alert missing title or detail rather than rendering it half-formed', async () => {
    pythonFetchMock.mockResolvedValue({
      answer: 'x',
      charts: [],
      alerts: [{ type: 'supplier_price_anomaly', level: 'high' }],
    });

    const result = await askRestaurantSynthesis('q');

    expect(result.alerts).toEqual([]);
  });

  it('returns an honest failure result on backend/network error, never throws', async () => {
    pythonFetchMock.mockRejectedValue(new Error('Python service error: 503 Service Unavailable'));

    const result = await askRestaurantSynthesis('q');

    expect(result.success).toBe(false);
    expect(result.answer).toBe('');
    expect(result.charts).toEqual([]);
    expect(result.alerts).toEqual([]);
    expect(result.error).toContain('503');
  });
});
