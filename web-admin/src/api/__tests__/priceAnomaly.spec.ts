/**
 * Unit tests for Wave2 price-anomaly API client.
 *
 * Mocks `../smartbi/common` (pythonFetch) and asserts each function builds the
 * correct Python gold URL + unwraps the {success, data} envelope. URL contracts
 * are load-bearing (a typo = 404), so these guard the path strings + param keys.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../smartbi/common', () => ({
  pythonFetch: vi.fn(),
  PYTHON_LLM_TIMEOUT_MS: 300000,
}));

import { pythonFetch } from '../smartbi/common';
import {
  detectPriceAnomalies,
  ackPriceAnomaly,
  listPriceAnomalyAcks,
  REASON_OPTIONS,
} from '../smartbi/priceAnomaly';

const mockFetch = vi.mocked(pythonFetch);

beforeEach(() => {
  mockFetch.mockReset();
});

describe('priceAnomaly API client', () => {
  it('detectPriceAnomalies builds /detect with trailing_n + epsilon_pct', async () => {
    mockFetch.mockResolvedValue({ success: true, data: [] } as never);
    await detectPriceAnomalies({ trailingN: 5, epsilonPct: 8.5, baselineMode: 'days', windowDays: 90 });
    const url = mockFetch.mock.calls[0][0] as string;
    expect(url).toContain('/api/smartbi/gold/price-anomaly/detect');
    expect(url).toContain('trailing_n=5');
    expect(url).toContain('epsilon_pct=8.5');
    expect(url).toContain('baseline_mode=days');
    expect(url).toContain('window_days=90');
  });

  it('detectPriceAnomalies omits params when not provided', async () => {
    mockFetch.mockResolvedValue({ success: true, data: [] } as never);
    await detectPriceAnomalies();
    const url = mockFetch.mock.calls[0][0] as string;
    expect(url).toBe('/api/smartbi/gold/price-anomaly/detect');
  });

  it('detectPriceAnomalies unwraps data array', async () => {
    const anomaly = {
      normalizedName: '洗洁精', newPrice: 150, deltaPct: 36.36,
      direction: 'UP', riskLevel: 'MEDIUM', consecutiveAnomalyCount: 1,
    };
    mockFetch.mockResolvedValue({ success: true, data: [anomaly] } as never);
    const out = await detectPriceAnomalies();
    expect(out).toHaveLength(1);
    expect(out[0].newPrice).toBe(150);
  });

  it('detectPriceAnomalies throws on success=false (no silent fallback)', async () => {
    mockFetch.mockResolvedValue({ success: false, message: '检测失败' } as never);
    await expect(detectPriceAnomalies()).rejects.toThrow('检测失败');
  });

  it('ackPriceAnomaly POSTs body to /ack and unwraps id', async () => {
    mockFetch.mockResolvedValue({ success: true, data: { id: 7 } } as never);
    const body = {
      normalizedName: '洗洁精', anomalyDeliveryDate: '2026-06-03',
      newPrice: 150, reasonCode: 'SEASONAL' as const,
    };
    const res = await ackPriceAnomaly(body);
    expect(res.id).toBe(7);
    const [url, opts] = mockFetch.mock.calls[0];
    expect(url).toBe('/api/smartbi/gold/price-anomaly/ack');
    expect((opts as { method: string }).method).toBe('POST');
    expect(JSON.parse((opts as { body: string }).body)).toMatchObject(body);
  });

  it('ackPriceAnomaly throws on success=false', async () => {
    mockFetch.mockResolvedValue({ success: false, message: '其他原因必填备注' } as never);
    await expect(
      ackPriceAnomaly({ normalizedName: 'x', anomalyDeliveryDate: '2026-06-03', newPrice: 1, reasonCode: 'OTHER' }),
    ).rejects.toThrow('其他原因必填备注');
  });

  it('listPriceAnomalyAcks builds /acks with optional ingredient filter', async () => {
    mockFetch.mockResolvedValue({ success: true, data: [] } as never);
    await listPriceAnomalyAcks('洗洁精');
    const url = mockFetch.mock.calls[0][0] as string;
    expect(url).toContain('/api/smartbi/gold/price-anomaly/acks?ingredient=');
  });

  it('listPriceAnomalyAcks omits filter when no ingredient', async () => {
    mockFetch.mockResolvedValue({ success: true, data: [] } as never);
    await listPriceAnomalyAcks();
    expect(mockFetch.mock.calls[0][0]).toBe('/api/smartbi/gold/price-anomaly/acks');
  });

  it('REASON_OPTIONS covers the 4 standard reason codes', () => {
    const values = REASON_OPTIONS.map((o) => o.value);
    expect(values).toEqual(['SEASONAL', 'MARKET_RISE', 'SPEC_CHANGE', 'OTHER']);
  });
});
