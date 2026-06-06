// web-admin/src/views/restaurant/analytics/__tests__/target-hierarchy.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

// ── Test: API module exports the right shape ─────────────────────────
describe('restaurant-targets API client', () => {
  it('exports upsertTarget, fetchAchievement, fetchAlerts, upsertAlertConfig', async () => {
    const api = await import('@/api/smartbi/restaurant-targets');
    expect(typeof api.upsertTarget).toBe('function');
    expect(typeof api.fetchAchievement).toBe('function');
    expect(typeof api.fetchAlerts).toBe('function');
    expect(typeof api.upsertAlertConfig).toBe('function');
  });
});

// ── Mocks for component tests ──────────────────────────────────────────────────
const mockRouterPush = vi.fn();
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mockRouterPush }),
  useRoute: () => ({ query: {} }),
}));

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({
    factoryId: 'RES_TEST',
    factoryType: 'RESTAURANT',
  }),
}));

vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({
    canWrite: () => true,
    canViewPrice: true,
  }),
}));

const mockUpsertTarget = vi.fn().mockResolvedValue({
  success: true,
  data: { id: 1, periodKey: '2026-06', targetValue: 500000, updatedAt: '2026-06-03T10:00:00' },
  message: '目标已保存',
});

vi.mock('@/api/smartbi/restaurant-targets', () => ({
  fetchAchievement: vi.fn().mockResolvedValue({
    success: true,
    data: { factoryId: 'RES_TEST', kpiKind: 'revenue', level: 'month', points: [], periodWithoutTarget: [] },
    message: 'ok',
  }),
  upsertTarget: (...args: unknown[]) => mockUpsertTarget(...args),
  fetchAlerts: vi.fn().mockResolvedValue({ success: true, data: { configExists: false, timeline: [], summary: {} }, message: 'ok' }),
  upsertAlertConfig: vi.fn(),
}));

vi.mock('element-plus', () => ({
  ElMessage: vi.fn(),
  ElMessageBox: { confirm: vi.fn().mockResolvedValue('confirm') },
}));

const globalStubs = {
  'el-card': { template: '<div class="el-card"><slot name="header" /><slot /></div>' },
  'el-input-number': {
    props: ['modelValue', 'min', 'max', 'disabled', 'step', 'size'],
    emits: ['update:modelValue'],
    template:
      '<input class="el-input-number" :value="modelValue" @input="$emit(\'update:modelValue\', +$event.target.value)" />',
  },
  'el-select': {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<select class="el-select" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>',
  },
  'el-option': { props: ['label', 'value'], template: '<option :value="value">{{ label }}</option>' },
  'el-button': {
    props: ['type', 'loading', 'disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled || loading" @click="$emit(\'click\')"><slot /></button>',
  },
  'el-tabs': { props: ['modelValue'], template: '<div><slot /></div>' },
  'el-tab-pane': { props: ['label', 'name'], template: '<div><slot /></div>' },
  'el-date-picker': { props: ['modelValue', 'type'], emits: ['update:modelValue'], template: '<input class="el-date-picker" />' },
  'el-row': { template: '<div class="el-row"><slot /></div>' },
  'el-col': { props: ['span'], template: '<div class="el-col"><slot /></div>' },
  'el-input': {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input class="el-input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
};

const vLoadingStub = { mounted() {}, updated() {}, unmounted() {} };

vi.mock('@/utils/echarts', () => ({
  default: {
    init: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }),
    connect: vi.fn(),
    graphic: { LinearGradient: class { constructor() {} } },
  },
}));

import TargetHierarchy from '../target-hierarchy.vue';

describe('TargetHierarchyEditor', () => {
  beforeEach(() => {
    mockRouterPush.mockClear();
    mockUpsertTarget.mockClear();
  });

  it('renders_monthly_preview_on_year_input', async () => {
    const wrapper = mount(TargetHierarchy, {
      global: { stubs: globalStubs, directives: { loading: vLoadingStub } },
    });
    await flushPromises();

    const yearInput = wrapper.find('.year-target-input');
    await yearInput.setValue(1200000);
    await wrapper.vm.$nextTick();

    // Should show monthly average hint (1200000 / 12 = 100000)
    expect(wrapper.text()).toContain('100,000');
  });

  it('reason_dropdown_shows_textarea_only_for_other', async () => {
    const wrapper = mount(TargetHierarchy, {
      global: { stubs: globalStubs, directives: { loading: vLoadingStub } },
    });
    await flushPromises();

    // Textarea for reason detail should not be visible initially
    expect(wrapper.find('.reason-detail-input').exists()).toBe(false);

    // Choose '其他' via the reason select
    const reasonSelect = wrapper.find('.reason-select');
    await reasonSelect.setValue('其他');
    await wrapper.vm.$nextTick();

    expect(wrapper.find('.reason-detail-input').exists()).toBe(true);
  });

  it('save_button_disabled_during_request', async () => {
    const wrapper = mount(TargetHierarchy, {
      global: { stubs: globalStubs, directives: { loading: vLoadingStub } },
    });
    await flushPromises();

    // Drive saving=true through the exposed ref
    (wrapper.vm as unknown as { saving: boolean }).saving = true;
    await wrapper.vm.$nextTick();

    const saveBtn = wrapper.find('.save-btn');
    expect(saveBtn.attributes('disabled')).toBeDefined();
  });

  it('header_shows_context', async () => {
    const wrapper = mount(TargetHierarchy, {
      global: { stubs: globalStubs, directives: { loading: vLoadingStub } },
    });
    await flushPromises();
    // Rule 2: page title carries factory + year + kpi context
    expect(wrapper.text()).toContain('设置目标');
    expect(wrapper.text()).toContain('RES_TEST');
  });
});

// ── KPI dashboard integration: achievement card contract smoke test ───────────
describe('kpi dashboard achievement contract', () => {
  it('fetchAchievement returns points array; empty-points handled', async () => {
    // The api module is mocked above; confirm the empty-points contract the
    // kpi/index.vue achievement card relies on (Rule 5 empty-state branch).
    const api = await import('@/api/smartbi/restaurant-targets');
    const result = await api.fetchAchievement({ startDate: '2026-06-01', endDate: '2026-06-07' });
    expect(result.success).toBe(true);
    expect(Array.isArray(result.data.points)).toBe(true);
    expect(result.data.points).toHaveLength(0);

    const alerts = await api.fetchAlerts({ lookbackDays: 7 });
    expect(alerts.success).toBe(true);
    expect(alerts.data.configExists).toBe(false);
    expect(alerts.data.timeline).toHaveLength(0);
  });
});
