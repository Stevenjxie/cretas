/**
 * WS5 (#13) — RevenueReport one-click default-header report.
 *
 * Verifies the prominent "一键生成默认收入管理报表 (全部历史)" button:
 *   - probes all-history range via getGoldDataRange (minDate/maxDate)
 *   - calls generateAndDownload with date_from=minDate, date_to=maxDate,
 *     store_names=[] (all stores), meal_periods=[] (all meal periods)
 *   - does NOT require the user to pre-fill the date / store / meal filters
 *
 * Mounts the real component with lightweight Element Plus stubs whose @click
 * forwards through, so we exercise the actual handleOneClickDefault wiring.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

// ── Mock auth store ──────────────────────────────────────────
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'RES_3101_009', factoryName: '青花椒' }),
}));

// ── Mock getGoldDataRange (WS1 gold probe) ───────────────────
const mockGetGoldDataRange = vi.fn();
vi.mock('@/api/smartbi/dataRange', () => ({
  getGoldDataRange: (...args: unknown[]) => mockGetGoldDataRange(...args),
}));

// ── Mock revenue-report API client ───────────────────────────
const mockGenerateAndDownload = vi.fn();
const mockListStores = vi.fn();
const mockGetAuditLog = vi.fn();
const mockPrepare = vi.fn();
const mockUploadPosFiles = vi.fn();
vi.mock('@/api/smartbi/revenue-report', () => ({
  generateAndDownload: (...args: unknown[]) => mockGenerateAndDownload(...args),
  listStores: (...args: unknown[]) => mockListStores(...args),
  getAuditLog: (...args: unknown[]) => mockGetAuditLog(...args),
  prepare: (...args: unknown[]) => mockPrepare(...args),
  uploadPosFiles: (...args: unknown[]) => mockUploadPosFiles(...args),
}));

// ── Mock element-plus message popups (no DOM toast in jsdom) ──
const elMessageError = vi.fn();
const elMessageSuccess = vi.fn();
const elMessageFn = vi.fn();
vi.mock('element-plus', () => ({
  ElMessage: Object.assign(
    (...a: unknown[]) => elMessageFn(...a),
    {
      error: (...a: unknown[]) => elMessageError(...a),
      success: (...a: unknown[]) => elMessageSuccess(...a),
      warning: vi.fn(),
    },
  ),
  ElAlert: { template: '<div class="el-alert"><slot /></div>' },
}));

// ── Element Plus + child component stubs ─────────────────────
const globalStubs = {
  // Forward @click so button presses reach the real handler.
  'el-button': {
    template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>',
  },
  'el-alert': { template: '<div class="el-alert"><slot /></div>' },
  'el-tabs': { template: '<div class="el-tabs"><slot /></div>' },
  'el-tab-pane': { template: '<div class="el-tab-pane"><slot /></div>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<div class="el-form-item"><slot /></div>' },
  'el-date-picker': { template: '<input class="el-date-picker" />' },
  'el-select': { template: '<select class="el-select"><slot /></select>' },
  'el-option': { template: '<option><slot /></option>' },
  'el-checkbox-group': { template: '<div><slot /></div>' },
  'el-checkbox': { template: '<label><slot /></label>' },
  // Render the table shell but NOT row-scoped slots — those reference `row`
  // which is undefined outside a real data iteration and would throw.
  'el-table': { template: '<table class="el-table"></table>' },
  'el-table-column': { template: '<td></td>' },
  'el-tag': { template: '<span class="el-tag"><slot /></span>' },
  'SmartBIUploader': { template: '<div class="smartbi-uploader-stub" />' },
};

// Import after the vi.mock calls so the component picks up the mocks.
import RevenueReport from '../RevenueReport.vue';

/** Find the one-click default button by its label text. */
function findOneClickButton(wrapper: ReturnType<typeof mount>) {
  return wrapper
    .findAll('button.el-button')
    .find((b) => b.text().includes('一键生成默认'));
}

describe('RevenueReport — 一键生成默认表头报表 (WS5 #13)', () => {
  beforeEach(() => {
    mockGetGoldDataRange.mockReset();
    mockGenerateAndDownload.mockReset();
    elMessageFn.mockReset();
    elMessageError.mockReset();
    elMessageSuccess.mockReset();
    // onMounted calls these; keep them inert.
    mockListStores.mockResolvedValue([]);
    mockGetAuditLog.mockResolvedValue([]);
    // jsdom lacks createObjectURL / revokeObjectURL.
    if (!URL.createObjectURL) {
      // @ts-expect-error jsdom stub
      URL.createObjectURL = vi.fn(() => 'blob:mock');
    } else {
      vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock');
    }
    if (!URL.revokeObjectURL) {
      // @ts-expect-error jsdom stub
      URL.revokeObjectURL = vi.fn();
    } else {
      vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
    }
  });

  it('renders a prominent one-click default button', async () => {
    const wrapper = mount(RevenueReport, { global: { stubs: globalStubs } });
    await flushPromises();
    const btn = findOneClickButton(wrapper);
    expect(btn).toBeTruthy();
  });

  it('renders a constructive business analysis guide above report generation', async () => {
    const wrapper = mount(RevenueReport, { global: { stubs: globalStubs } });
    await flushPromises();

    expect(wrapper.text()).toContain('经营解读');
    expect(wrapper.text()).toContain('门店营收');
    expect(wrapper.text()).toContain('同比');
    expect(wrapper.text()).toContain('缓存');
  });

  it('one-click uses all-history range + all stores + all meal periods, no pre-fill needed', async () => {
    mockGetGoldDataRange.mockResolvedValue({
      factoryId: 'RES_3101_009',
      minDate: '2025-01-01',
      maxDate: '2026-04-30',
      dayCount: 485,
    });
    mockGenerateAndDownload.mockResolvedValue({
      blob: new Blob(['xlsx-bytes']),
      cacheHit: false,
      goldMaterializedAt: '2026-04-30T00:00:00',
      storeCount: 12,
      isStale: false,
    });

    const wrapper = mount(RevenueReport, { global: { stubs: globalStubs } });
    await flushPromises();

    const btn = findOneClickButton(wrapper);
    expect(btn).toBeTruthy();

    // The user clicks WITHOUT touching the date / store / meal filters.
    await btn!.trigger('click');
    await flushPromises();

    // gold range probed for the current factory
    expect(mockGetGoldDataRange).toHaveBeenCalledWith('RES_3101_009');

    // generateAndDownload called with full-history defaults (snake_case shape)
    expect(mockGenerateAndDownload).toHaveBeenCalledTimes(1);
    expect(mockGenerateAndDownload).toHaveBeenCalledWith({
      date_from: '2025-01-01',
      date_to: '2026-04-30',
      store_names: [],
      meal_periods: [],
    });

    // no error surfaced
    expect(elMessageError).not.toHaveBeenCalled();
  });

  it('honest error (no silent fail) when gold range probe fails', async () => {
    mockGetGoldDataRange.mockRejectedValue(new Error('gold probe boom'));

    const wrapper = mount(RevenueReport, { global: { stubs: globalStubs } });
    await flushPromises();

    const btn = findOneClickButton(wrapper);
    await btn!.trigger('click');
    await flushPromises();

    expect(mockGenerateAndDownload).not.toHaveBeenCalled();
    expect(elMessageFn).toHaveBeenCalled();
    expect(String(elMessageFn.mock.calls[0][0].message)).toContain('gold probe boom');
  });

  it('honest error when gold range has no data (null minDate)', async () => {
    mockGetGoldDataRange.mockResolvedValue({
      factoryId: 'RES_3101_009',
      minDate: null,
      maxDate: null,
      dayCount: 0,
    });

    const wrapper = mount(RevenueReport, { global: { stubs: globalStubs } });
    await flushPromises();

    const btn = findOneClickButton(wrapper);
    await btn!.trigger('click');
    await flushPromises();

    expect(mockGenerateAndDownload).not.toHaveBeenCalled();
    expect(elMessageFn).toHaveBeenCalled();
  });
});
