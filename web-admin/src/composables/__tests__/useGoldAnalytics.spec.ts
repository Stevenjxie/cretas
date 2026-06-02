/**
 * WS1 Task 3 — unit test for useGoldAnalytics composable.
 *
 * Pins the contract between the restaurant analytics pages and the gold read
 * endpoints (/api/smartbi/gold/*):
 * - DEFAULT range=null → URL has NO start_date/end_date (= all history)
 * - explicit range → URL appends start_date / end_date
 * - multiple endpoints → data keyed per endpoint, fetched in parallel
 * - failure (thrown OR res.success===false) → error set, reload doesn't throw
 * - autoLoad:false → no fetch until reload() is called
 *
 * Mocks @/api/smartbi/common `pythonFetch` (mirrors the mock style used by
 * useLinkChipCounts.spec.ts which mocks @/api/request).
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';

// ── Top-level mocks (vi.mock is hoisted) ──────────────────────────────
const pythonFetchMock = vi.fn();
vi.mock('@/api/smartbi/common', () => ({
  pythonFetch: (...args: unknown[]) => pythonFetchMock(...args),
  PYTHON_LLM_TIMEOUT_MS: 300000,
}));

// Import composable AFTER mocks are set up.
import { useGoldAnalytics } from '../useGoldAnalytics';

describe('useGoldAnalytics', () => {
  beforeEach(() => {
    pythonFetchMock.mockReset();
  });

  it('default range=null → URL does NOT contain start_date (all history)', async () => {
    pythonFetchMock.mockResolvedValue({ totalRevenue: 123 });

    const { reload, data, range } = useGoldAnalytics({
      endpoints: ['finance-summary'],
      factoryId: 'RES_3101_009',
      autoLoad: false,
    });

    expect(range.value).toBeNull();

    await reload();

    expect(pythonFetchMock).toHaveBeenCalledOnce();
    const url = pythonFetchMock.mock.calls[0][0] as string;
    expect(url).toBe('/api/smartbi/gold/finance-summary?factory_id=RES_3101_009');
    expect(url).not.toContain('start_date');
    expect(url).not.toContain('end_date');
    expect(data.value['finance-summary']).toEqual({ totalRevenue: 123 });
  });

  it('explicit range → URL contains start_date and end_date', async () => {
    pythonFetchMock.mockResolvedValue({ points: [] });

    const { reload, range } = useGoldAnalytics({
      endpoints: ['daily-trend'],
      factoryId: 'F001',
      autoLoad: false,
    });

    range.value = ['2026-01-01', '2026-02-01'];
    await reload();

    const url = pythonFetchMock.mock.calls[0][0] as string;
    expect(url).toContain('factory_id=F001');
    expect(url).toContain('start_date=2026-01-01');
    expect(url).toContain('end_date=2026-02-01');
  });

  it('honours range passed via opts at construction', async () => {
    pythonFetchMock.mockResolvedValue({});
    const { reload, range } = useGoldAnalytics({
      endpoints: ['kpi-summary'],
      factoryId: 'F001',
      range: ['2025-03-01', '2025-03-31'],
      autoLoad: false,
    });
    expect(range.value).toEqual(['2025-03-01', '2025-03-31']);
    await reload();
    const url = pythonFetchMock.mock.calls[0][0] as string;
    expect(url).toContain('start_date=2025-03-01');
    expect(url).toContain('end_date=2025-03-31');
  });

  it('multiple endpoints → data keyed by each endpoint name; fetched in parallel', async () => {
    // Resolve based on which endpoint the URL targets.
    pythonFetchMock.mockImplementation((url: string) => {
      if (url.includes('finance-summary')) return Promise.resolve({ totalRevenue: 100 });
      if (url.includes('daily-trend')) return Promise.resolve({ points: [{ date: '2026-01-01' }] });
      if (url.includes('top-products')) return Promise.resolve({ topProducts: [] });
      return Promise.resolve(null);
    });

    const { reload, data } = useGoldAnalytics({
      endpoints: ['finance-summary', 'daily-trend', 'top-products'],
      factoryId: 'RES_3101_009',
      autoLoad: false,
    });

    await reload();

    // Promise.all → all 3 dispatched (no awaiting between them).
    expect(pythonFetchMock).toHaveBeenCalledTimes(3);
    expect(data.value['finance-summary']).toEqual({ totalRevenue: 100 });
    expect(data.value['daily-trend']).toEqual({ points: [{ date: '2026-01-01' }] });
    expect(data.value['top-products']).toEqual({ topProducts: [] });
  });

  it('res.success===false → error set, reload does NOT throw', async () => {
    pythonFetchMock.mockResolvedValue({ success: false, message: '门店数据为空' });

    const { reload, error, data } = useGoldAnalytics({
      endpoints: ['finance-summary'],
      factoryId: 'F001',
      autoLoad: false,
    });

    await expect(reload()).resolves.toBeUndefined();
    expect(error.value).toBe('门店数据为空');
    // data left as-is (empty initial map).
    expect(data.value['finance-summary']).toBeUndefined();
  });

  it('thrown error (HTTP failure via pythonFetch) → error set with fallback message', async () => {
    pythonFetchMock.mockRejectedValue(new Error('Python service error: 500 Internal Server Error'));

    const { reload, error, loading } = useGoldAnalytics({
      endpoints: ['finance-summary'],
      factoryId: 'F001',
      autoLoad: false,
    });

    await expect(reload()).resolves.toBeUndefined();
    expect(error.value).toBe('Python service error: 500 Internal Server Error');
    expect(loading.value).toBe(false);
  });

  it('autoLoad:false → pythonFetch not called until reload()', async () => {
    pythonFetchMock.mockResolvedValue({});

    const { reload } = useGoldAnalytics({
      endpoints: ['finance-summary'],
      factoryId: 'F001',
      autoLoad: false,
    });

    expect(pythonFetchMock).not.toHaveBeenCalled();
    await reload();
    expect(pythonFetchMock).toHaveBeenCalledOnce();
  });

  it('autoLoad defaults to true → pythonFetch called on creation', async () => {
    pythonFetchMock.mockResolvedValue({ totalRevenue: 1 });

    const { data } = useGoldAnalytics({
      endpoints: ['finance-summary'],
      factoryId: 'F001',
    });

    // Auto-load is fire-and-forget; flush the microtask queue.
    await vi.waitFor(() => expect(pythonFetchMock).toHaveBeenCalledOnce());
    await vi.waitFor(() => expect(data.value['finance-summary']).toEqual({ totalRevenue: 1 }));
  });
});
