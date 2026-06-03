/**
 * WS4 #11 (餐饮路径遗漏) test for RestaurantSalesContent.vue.
 *
 * The restaurant 销售分析（餐饮）view previously hardcoded its default date range
 * to the LAST 30 DAYS in onMounted. qhj-style tenants have real data only in a
 * historical window (e.g. 2025-01..2026-04), so the last-30-days default landed
 * on an empty range → "所选区间无销售数据".
 *
 * The factory path in SalesAnalysis.vue was already fixed to default to ALL
 * HISTORY via getGoldDataRange + resolveAllHistoryRange; this component was
 * missed. These tests assert the restaurant path now defaults to the gold full
 * range and falls back to a wide window (never 30 days) when the probe is empty.
 *
 * Mount-with-mocks pattern mirrors Dashboard.datasource.spec.ts.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

// ── gold data-range probe (drives the all-history default) ───
const getGoldDataRangeMock = vi.fn(async () => ({
  factoryId: 'RES_3101_009',
  minDate: '2025-01-01',
  maxDate: '2026-04-30',
  dayCount: 485,
}));
vi.mock('@/api/smartbi/dataRange', () => ({
  getGoldDataRange: (...a: unknown[]) => getGoldDataRangeMock(...a),
}));

// ── HTTP gateway: capture the startDate/endDate params the view requests ──
const getMock = vi.fn(async () => ({
  success: true,
  data: {
    tenantType: 'RESTAURANT',
    dateRange: { startDate: '2025-01-01', endDate: '2026-04-30', days: 485 },
    overview: { totalRevenue: 0, billCount: 0, avgPerCapita: null, storeCount: 0 },
  },
}));
vi.mock('@/api/request', () => ({
  get: (...a: unknown[]) => getMock(...(a as [string, Record<string, unknown>])),
}));

// ── auth store: RESTAURANT tenant ────────────────────────────
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'RES_3101_009', factoryType: 'RESTAURANT' }),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canViewPrice: true }),
}));

// ── echarts (no canvas / wordcloud support in jsdom) ─────────
vi.mock('@/utils/echarts', () => ({
  default: {
    init: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn(), clear: vi.fn() }),
    graphic: { LinearGradient: class { constructor() {} } },
  },
}));

// ── child components → trivial stubs ─────────────────────────
// NOTE: vi.mock paths are resolved relative to THIS test file. The component
// under test imports './DynamicChartRenderer.vue' (resolving to
// components/smartbi/DynamicChartRenderer.vue) — so mock with the parent path.
vi.mock('@/components/smartbi/DynamicChartRenderer.vue', () => ({
  default: { props: ['config', 'height'], template: '<div class="dcr-stub" />' },
}));
vi.mock('../DynamicChartRenderer.vue', () => ({
  default: { props: ['config', 'height'], template: '<div class="dcr-stub" />' },
}));
vi.mock('../SmartBIEmptyState.vue', () => ({
  default: { template: '<div class="empty-state-stub" />' },
}));
vi.mock('@/components/CapabilityGate.vue', () => ({
  default: { template: '<div class="capability-gate-stub"><slot /></div>' },
}));

import RestaurantSalesContent from '../RestaurantSalesContent.vue';

const globalStubs = {
  'el-card': { template: '<div class="el-card"><slot name="header" /><slot /></div>' },
  'el-row': { template: '<div class="el-row"><slot /></div>' },
  'el-col': { template: '<div class="el-col"><slot /></div>' },
  'el-button': { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
  'el-icon': { template: '<i><slot /></i>' },
  'el-alert': { props: ['title'], template: '<div class="el-alert">{{ title }}<slot /></div>' },
  'el-table': { template: '<table><slot /></table>' },
  'el-table-column': { template: '<td><slot /></td>' },
  'el-date-picker': { props: ['modelValue'], template: '<div class="el-date-picker" />' },
};

async function mountView() {
  const wrapper = mount(RestaurantSalesContent, { global: { stubs: globalStubs } });
  await flushPromises();
  await flushPromises();
  return wrapper;
}

/** Find the startDate/endDate the view sent to the sales endpoint. */
function lastSalesParams(): { startDate?: string; endDate?: string } {
  const calls = getMock.mock.calls.filter((c) =>
    typeof c[0] === 'string' && (c[0] as string).includes('/smart-bi/analysis/sales'),
  );
  expect(calls.length).toBeGreaterThan(0);
  const last = calls[calls.length - 1];
  const opts = last[1] as { params?: { startDate?: string; endDate?: string } } | undefined;
  return opts?.params ?? {};
}

describe('RestaurantSalesContent — 默认全部历史 (WS4 #11 餐饮路径)', () => {
  beforeEach(() => {
    getGoldDataRangeMock.mockClear();
    getMock.mockClear();
  });

  it('probes the gold data-range on mount', async () => {
    await mountView();
    expect(getGoldDataRangeMock).toHaveBeenCalledWith('RES_3101_009');
  });

  it('defaults the date range to the gold full window [minDate, maxDate], NOT last-30-days', async () => {
    await mountView();
    const { startDate, endDate } = lastSalesParams();
    expect(startDate).toBe('2025-01-01');
    expect(endDate).toBe('2026-04-30');

    // regression guard: start is NOT within the last 30 days
    const thirtyDaysAgo = new Date();
    thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30);
    const thirtyIso = thirtyDaysAgo.toISOString().slice(0, 10);
    expect(startDate! < thirtyIso).toBe(true);
  });

  it('falls back to a wide window (never last-30-days) when the gold probe is empty', async () => {
    getGoldDataRangeMock.mockResolvedValueOnce({
      factoryId: 'RES_3101_009',
      minDate: null,
      maxDate: null,
      dayCount: 0,
    });
    await mountView();
    const { startDate } = lastSalesParams();
    // wide fallback (730d) → start far earlier than 30 days ago
    const thirtyDaysAgo = new Date();
    thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30);
    const thirtyIso = thirtyDaysAgo.toISOString().slice(0, 10);
    expect(startDate! < thirtyIso).toBe(true);
  });

  it('falls back to a wide window when the gold probe throws (no crash)', async () => {
    getGoldDataRangeMock.mockRejectedValueOnce(new Error('python down'));
    await mountView();
    const { startDate, endDate } = lastSalesParams();
    expect(startDate).toBeTruthy();
    expect(endDate).toBeTruthy();
    const thirtyDaysAgo = new Date();
    thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30);
    const thirtyIso = thirtyDaysAgo.toISOString().slice(0, 10);
    expect(startDate! < thirtyIso).toBe(true);
  });
});
