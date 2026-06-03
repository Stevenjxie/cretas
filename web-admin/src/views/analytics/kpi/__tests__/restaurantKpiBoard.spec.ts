import { describe, it, expect } from 'vitest';
import {
  buildRestaurantKpiBoard,
  type KpiSummaryLike,
  type FinanceSummaryLike,
  type TrendBundleLike,
} from '../restaurantKpiBoard';

function kpi(): KpiSummaryLike {
  return {
    revenue: 43000000,
    billCount: 120000,
    itemCount: 350000,
    customerCount: 280000,
    storeCount: 9,
    dayCount: 365,
    avgBillValue: 358.33,
    avgPerCapita: 153.57,
  };
}

function finance(): FinanceSummaryLike {
  return {
    storeCount: 9,
    topStores: [
      { storeName: '青花椒大融城店', revenue: 9000000 },
      { storeName: '青花椒人民路店', revenue: 7000000 },
    ],
  };
}

function bundle(): TrendBundleLike {
  return {
    monthlyTrend: [
      { month: '2025-01', revenue: 3000000 },
      { month: '2025-08', revenue: 4500000 }, // peak
      { month: '2025-12', revenue: 3800000 },
    ],
  };
}

describe('buildRestaurantKpiBoard', () => {
  it('surfaces gold 经营 KPIs (营收/订单/客单价/门店/天数/峰值月) for a price-view role', () => {
    const board = buildRestaurantKpiBoard(kpi(), finance(), bundle(), true);
    expect(board.hasData).toBe(true);
    const byKey = Object.fromEntries(board.items.map((i) => [i.key, i]));
    expect(byKey.revenue.value).toBe(43000000);
    expect(byKey.revenue.kind).toBe('money');
    expect(byKey.billCount.value).toBe(120000);
    expect(byKey.avgBillValue.value).toBe(358.33);
    expect(byKey.storeCount.value).toBe(9);
    expect(byKey.dayCount.value).toBe(365);
    expect(byKey.peakMonth.text).toBe('2025-08');
    expect(board.topStoreName).toBe('青花椒大融城店');
    expect(board.peakMonth).toBe('2025-08');
  });

  it('renders all six cards for a price-view role', () => {
    const board = buildRestaurantKpiBoard(kpi(), finance(), bundle(), true);
    expect(board.items.map((i) => i.key)).toEqual([
      'revenue',
      'billCount',
      'avgBillValue',
      'storeCount',
      'dayCount',
      'peakMonth',
    ]);
  });

  it('hides money cards (营收/客单价/峰值月) for non price-view roles, keeps counts', () => {
    const board = buildRestaurantKpiBoard(kpi(), finance(), bundle(), false);
    const keys = board.items.map((i) => i.key);
    expect(keys).toEqual(['billCount', 'storeCount', 'dayCount']);
    expect(board.items.every((i) => !i.money)).toBe(true);
    // money-derived signals also suppressed at the board level
    expect(board.topStoreName).toBeNull();
    expect(board.peakMonth).toBeNull();
    // still has data → board should render (orders/stores/days present)
    expect(board.hasData).toBe(true);
  });

  it('emits value:null (renderer → "—") when revenue is RBAC-nulled but role can view price', () => {
    // Defensive: even a price-view role can receive null if the backend nulled it.
    const stripped: KpiSummaryLike = { ...kpi(), revenue: null, avgBillValue: null };
    const board = buildRestaurantKpiBoard(stripped, finance(), bundle(), true);
    const byKey = Object.fromEntries(board.items.map((i) => [i.key, i]));
    expect(byKey.revenue.value).toBeNull();
    expect(byKey.avgBillValue.value).toBeNull();
    // counts unaffected
    expect(byKey.billCount.value).toBe(120000);
  });

  it('never produces NaN from NaN/undefined inputs', () => {
    const dirty: KpiSummaryLike = {
      revenue: NaN as unknown as number,
      billCount: undefined,
      storeCount: 3,
      dayCount: 10,
    };
    const board = buildRestaurantKpiBoard(dirty, null, null, true);
    const byKey = Object.fromEntries(board.items.map((i) => [i.key, i]));
    expect(byKey.revenue.value).toBeNull();
    expect(byKey.billCount.value).toBeNull();
    expect(byKey.storeCount.value).toBe(3);
    expect(board.hasData).toBe(true); // storeCount + dayCount present
  });

  it('falls back to finance-summary storeCount when kpi-summary lacks it', () => {
    const noStore: KpiSummaryLike = { ...kpi(), storeCount: null };
    const board = buildRestaurantKpiBoard(noStore, { storeCount: 7 }, bundle(), true);
    const storeItem = board.items.find((i) => i.key === 'storeCount')!;
    expect(storeItem.value).toBe(7);
  });

  it('reports hasData=false (honest empty) when there is no usable signal', () => {
    const empty: KpiSummaryLike = {
      revenue: 0,
      billCount: 0,
      customerCount: 0,
      storeCount: 0,
      dayCount: 0,
    };
    const board = buildRestaurantKpiBoard(empty, { storeCount: 0, topStores: [] }, { monthlyTrend: [] }, true);
    expect(board.hasData).toBe(false);
    expect(board.peakMonth).toBeNull();
    expect(board.topStoreName).toBeNull();
  });

  it('skips RBAC-nulled monthly revenue when picking the peak month', () => {
    const partial: TrendBundleLike = {
      monthlyTrend: [
        { month: '2025-01', revenue: null }, // stripped — must be ignored
        { month: '2025-02', revenue: 100 },
        { month: '2025-03', revenue: 50 },
      ],
    };
    const board = buildRestaurantKpiBoard(kpi(), finance(), partial, true);
    expect(board.peakMonth).toBe('2025-02');
  });

  it('handles null kpi/finance/bundle gracefully', () => {
    const board = buildRestaurantKpiBoard(null, null, null, true);
    expect(board.hasData).toBe(false);
    expect(board.items.length).toBe(6);
    expect(board.items.every((i) => i.value === null || i.kind === 'text')).toBe(true);
  });
});
