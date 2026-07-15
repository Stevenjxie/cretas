/**
 * WS2 Tasks 1-2 tests for Dashboard.vue (经营驾驶舱) — 2026-06-02.
 *
 * Task 1: the "选择数据源 (pick uploaded CSV)" selector is GONE — the dashboard
 *   always auto-aggregates from the gold layer with DEFAULT = all history. Mount
 *   must not render the data-source select; the gold load path (`get(...executive
 *   /custom...)`) is used (never the upload-based `getDynamicAnalysis`); the
 *   default date range = [minDate, maxDate] from `getGoldDataRange` (all history).
 *
 * Task 2: KPI cards always render gold values directly (营业额/客单价/订单数/门店数)
 *   and NEVER show the CapabilityGate "此分析需上传含..." placeholder in gold mode.
 *
 * Heavy deps (echarts / child panels / stores / router / capability) are mocked
 * so the mount stays light — mirrors AIQuery.depth.spec.ts / GoldPreview.spec.ts.
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

// Restaurant dashboards load KPI and trend data directly from the Python Gold API.
const getKpiSummaryMock = vi.fn(async (_args: unknown) => ({
  factoryId: 'RES_3101_009',
  startDate: '2025-01-01',
  endDate: '2026-04-30',
  revenue: 43210000,
  billCount: 128456,
  itemCount: 0,
  customerCount: 0,
  storeCount: 7,
  dayCount: 485,
  avgBillValue: 336.5,
  itemsPerBill: null,
  avgPerCapita: null,
}));
const getTrendBundleMock = vi.fn(async (_args: unknown) => ({
  factoryId: 'RES_3101_009',
  startDate: '2025-01-01',
  endDate: '2026-04-30',
  dailyTrend: [],
}));
vi.mock('@/api/smartbi/gold', () => ({
  getKpiSummary: (...a: unknown[]) => getKpiSummaryMock(...(a as [unknown])),
  getTrendBundle: (...a: unknown[]) => getTrendBundleMock(...(a as [unknown])),
}));

// loadLLMInsights() still uses the Java HTTP gateway; keep it empty and non-blocking.
const getMock = vi.fn(async (_url: string) => ({ success: true, data: { data: [] } }));
vi.mock('@/api/request', () => ({
  get: (...a: unknown[]) => getMock(...(a as [string])),
}));

// Keep the non-blocking insights stream inside jsdom instead of issuing a real relative fetch.
const insightsStream = [
  'data: {"type":"delta","text":"ok"}',
  '',
  'data: {"type":"done"}',
  '',
].join('\n');
vi.stubGlobal('fetch', vi.fn(async () => new Response(insightsStream, {
  status: 200,
  headers: { 'Content-Type': 'text/event-stream' },
})));

// ── smartbi API: upload-history list + dynamic analysis (must NOT drive view) ──
const getUploadHistoryMock = vi.fn(async () => ({ success: true, data: [] }));
const getDynamicAnalysisMock = vi.fn(async () => ({ success: true, data: { kpiCards: [], charts: [] } }));
vi.mock('@/api/smartbi', () => ({
  getUploadHistory: (...a: unknown[]) => getUploadHistoryMock(...a),
  getDynamicAnalysis: (...a: unknown[]) => getDynamicAnalysisMock(...a),
}));

// ── auth store: RESTAURANT tenant (gold default path) ────────
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'RES_3101_009', factoryType: 'RESTAURANT' }),
}));
// ── permission store: can view price + can upload ────────────
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({
    canWrite: () => true,
    canViewPrice: true,
  }),
}));
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

// ── capability composable: report MISSING fields so that, if any KPI card were
//    still wrapped in <CapabilityGate>, it WOULD show the "需上传" placeholder.
//    This is what Task 2 must bypass for gold cards. ──────────
vi.mock('@/composables/useCapability', () => ({
  useCapability: () => ({
    fetchCapability: vi.fn(async () => null),
    hasAll: () => false, // nothing satisfied → placeholder path if gated
    missingOf: (reqs: string[]) => reqs,
    available: { value: new Set<string>() },
    loading: { value: false },
    suggestions: { value: [] },
  }),
}));

// ── echarts + chart composables (no real charts in jsdom) ────
vi.mock('@/utils/echarts', () => ({
  default: {
    init: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }),
    connect: vi.fn(),
    graphic: { LinearGradient: class { constructor() {} } },
  },
}));
vi.mock('@/composables/useChartResize', () => ({
  useChartResize: () => ({ observe: vi.fn(), disconnect: vi.fn() }),
}));
vi.mock('@/composables/useChartEnhancer', () => ({
  enhanceChartDefaults: (o: unknown) => o,
}));

// ── child components → trivial stubs ─────────────────────────
vi.mock('@/components/smartbi/SmartBIEmptyState.vue', () => ({
  default: { template: '<div class="empty-state-stub" />' },
}));
vi.mock('@/components/smartbi/ChartSkeleton.vue', () => ({
  default: { template: '<div class="skeleton-stub" />' },
}));
vi.mock('./components/TemplateGrid.vue', () => ({
  default: { template: '<div class="template-grid-stub" />' },
}));
vi.mock('./components/RestaurantGoldGrid.vue', () => ({
  default: { template: '<div class="restaurant-gold-grid-stub" />' },
}));
vi.mock('@/components/UnlockMoreCTA.vue', () => ({
  default: { template: '<div class="unlock-cta-stub" />' },
}));
// CapabilityGate stub mirroring the real component's gold-mode bypass:
//  - `force` (gold mode) → render the default slot only, NO placeholder.
//  - otherwise (capability unsatisfied, see useCapability mock) → render the
//    real "此分析需上传含..." placeholder text so the test can detect a gold
//    card that is (wrongly) still gated.
vi.mock('@/components/CapabilityGate.vue', () => ({
  default: {
    props: ['requires', 'cardId', 'fallback', 'force'],
    template:
      '<div class="capability-gate-stub">' +
      '<template v-if="force"><slot /></template>' +
      '<template v-else>' +
      '<div class="cap-placeholder">此分析需上传含 {{ (requires || []).join(" / ") }} 的数据</div>' +
      '<slot />' +
      '</template>' +
      '</div>',
  },
}));

import Dashboard from '../Dashboard.vue';

// Element Plus stubs — keep slots rendering, expose v-model where it matters.
const globalStubs = {
  'el-card': { template: '<div class="el-card"><slot name="header" /><slot /></div>' },
  'el-row': { template: '<div class="el-row"><slot /></div>' },
  'el-col': { template: '<div class="el-col"><slot /></div>' },
  'el-button': { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
  'el-icon': { template: '<i><slot /></i>' },
  'el-tag': { template: '<span class="el-tag"><slot /></span>' },
  'el-tooltip': { template: '<div><slot /></div>' },
  'el-alert': { props: ['title'], template: '<div class="el-alert">{{ title }}<slot /></div>' },
  'el-empty': { props: ['description'], template: '<div class="el-empty">{{ description }}<slot /></div>' },
  'el-skeleton': { template: '<div class="el-skeleton" />' },
  'el-radio-group': { template: '<div class="el-radio-group"><slot /></div>' },
  'el-radio-button': { template: '<button class="el-radio-button"><slot /></button>' },
  // these two are the data-source picker controls; render so text would surface IF present
  'el-select': {
    props: ['modelValue', 'placeholder'],
    template: '<select class="el-select" :data-placeholder="placeholder"><slot /></select>',
  },
  'el-option': { props: ['label', 'value'], template: '<option :value="value">{{ label }}<slot /></option>' },
  'el-date-picker': {
    props: ['modelValue'],
    template: '<div class="el-date-picker" />',
  },
};

async function mountDashboard() {
  const wrapper = mount(Dashboard, {
    global: {
      stubs: globalStubs,
      directives: { loading: () => undefined },
    },
  });
  await flushPromises();
  await flushPromises();
  return wrapper;
}

describe('Dashboard WS2 — data-source picker removed, gold default', () => {
  beforeEach(() => {
    getGoldDataRangeMock.mockClear();
    getKpiSummaryMock.mockClear();
    getTrendBundleMock.mockClear();
    getMock.mockClear();
    getUploadHistoryMock.mockClear();
    getDynamicAnalysisMock.mockClear();
  });

  it('does NOT render the 选择数据源 / 系统数据 picker', async () => {
    const wrapper = await mountDashboard();
    const text = wrapper.text();
    expect(text).not.toContain('选择数据源');
    expect(text).not.toContain('系统数据');
    // no el-select carrying the datasource placeholder
    const selects = wrapper.findAll('select.el-select');
    const dsSelect = selects.find((s) => s.attributes('data-placeholder') === '选择数据源');
    expect(dsSelect).toBeUndefined();
  });

  it('loads from the direct Gold API, never the legacy executive or upload paths', async () => {
    await mountDashboard();
    expect(getKpiSummaryMock).toHaveBeenCalledOnce();
    expect(getTrendBundleMock).toHaveBeenCalledOnce();
    const legacyDashboardCalls = getMock.mock.calls.filter((call) => {
      const url = String(call[0]);
      return url.includes('/smart-bi/dashboard/executive') && !url.includes('/insights');
    });
    expect(legacyDashboardCalls).toEqual([]);
    expect(getDynamicAnalysisMock).not.toHaveBeenCalled();
  });

  it('defaults the date range to ALL history [minDate, maxDate] from getGoldDataRange', async () => {
    await mountDashboard();
    expect(getGoldDataRangeMock).toHaveBeenCalled();
    const expectedRange = {
      factoryId: 'RES_3101_009',
      startDate: '2025-01-01',
      endDate: '2026-04-30',
    };
    expect(getKpiSummaryMock).toHaveBeenCalledWith(expectedRange);
    expect(getTrendBundleMock).toHaveBeenCalledWith(expectedRange);
  });
});

describe('Dashboard WS2 — KPI cards always gold (no 需上传 placeholder)', () => {
  beforeEach(() => {
    getGoldDataRangeMock.mockClear();
    getKpiSummaryMock.mockClear();
    getTrendBundleMock.mockClear();
    getMock.mockClear();
    getUploadHistoryMock.mockClear();
    getDynamicAnalysisMock.mockClear();
  });

  it('renders the 4 gold KPI values without the 此分析需上传含 placeholder', async () => {
    const wrapper = await mountDashboard();
    // scope to the KPI strip (.kpi-section) — Task 2 is specifically the 4 KPI
    // cards. Chart/ranking gates below the strip keep their normal gating.
    const kpiSection = wrapper.find('.kpi-section.kpi-fade-in');
    expect(kpiSection.exists()).toBe(true);
    const kpiText = kpiSection.text();
    // 4 gold KPI labels + values present
    expect(kpiText).toContain('总营收');
    expect(kpiText).toContain('客单价');
    expect(kpiText).toContain('订单数');
    expect(kpiText).toContain('门店数');
    expect(kpiText).toContain('4321万');
    // no capability "需上传" placeholder leaked into the KPI strip (gold mode bypass)
    expect(kpiText).not.toContain('此分析需上传含');
  });
});
