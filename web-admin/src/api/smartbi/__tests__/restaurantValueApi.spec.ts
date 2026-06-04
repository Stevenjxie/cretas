/**
 * #56 价值可视化回馈回路 — restaurantValueApi client 测试 (mock fetch)。
 *
 * 断言: 端点路径 / 信封透传 / 空态 data:null / 期间参数。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { getValueSummary, refreshValueSnapshot } from '../restaurantValueApi';

global.fetch = vi.fn();

global.localStorage = {
  getItem: vi.fn((key: string) => {
    if (key === 'cretas_access_token') return 'mock-token';
    if (key === 'cretas_user') return JSON.stringify({ factoryId: 'F-DENG' });
    return null;
  }),
  setItem: vi.fn(),
  removeItem: vi.fn(),
  clear: vi.fn(),
  length: 0,
  key: vi.fn(),
} as unknown as Storage;

function mockJson(body: unknown) {
  (global.fetch as any).mockResolvedValue({
    ok: true,
    status: 200,
    statusText: 'OK',
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response);
}

describe('getValueSummary', () => {
  beforeEach(() => vi.clearAllMocks());

  it('hits the value-summary endpoint and returns the envelope', async () => {
    mockJson({
      success: true,
      message: 'ok',
      data: {
        period_month: '2026-02',
        store_id: null,
        month: { total: 50849, shrinkage_variance: 12500, food_cost_savings: 20000, discount_savings: null },
        annual: { total: 472688, labor_rigidity: 220188 },
        diagnosis_count: 3,
        critical_count: 1,
        rx_action_count: 2,
        signal_sources: [],
        confidence_note: '预估口径',
        computed_at: null,
      },
    });

    const env = await getValueSummary({});
    const url = (global.fetch as any).mock.calls[0][0] as string;
    expect(url).toContain('/api/smartbi/restaurant-value/value-summary');
    expect(env.success).toBe(true);
    // transformKeys camelCases the data
    expect(env.data?.month.total).toBe(50849);
    expect(env.data?.annual.laborRigidity).toBe(220188);
    expect(env.data?.criticalCount).toBe(1);
  });

  it('passes period_month query param when provided', async () => {
    mockJson({ success: true, message: 'ok', data: null });
    await getValueSummary({ periodMonth: '2026-02' });
    const url = (global.fetch as any).mock.calls[0][0] as string;
    expect(url).toContain('period_month=2026-02');
  });

  it('honest empty state: data null when no snapshot', async () => {
    mockJson({ success: true, message: '暂无价值快照', data: null });
    const env = await getValueSummary({});
    expect(env.success).toBe(true);
    expect(env.data).toBeNull();
    expect(env.message).toContain('暂无');
  });
});

describe('refreshValueSnapshot', () => {
  beforeEach(() => vi.clearAllMocks());

  it('POSTs to refresh with period in body', async () => {
    mockJson({ success: true, message: 'ok', data: { total_month: 50849, total_annual: 472688 } });
    const res = await refreshValueSnapshot({ periodMonth: '2026-02' });
    const call = (global.fetch as any).mock.calls[0];
    expect(call[0]).toContain('/api/smartbi/restaurant-value/refresh');
    expect(call[1].method).toBe('POST');
    expect(JSON.parse(call[1].body)).toMatchObject({ periodMonth: '2026-02' });
    expect(res.success).toBe(true);
  });
});
