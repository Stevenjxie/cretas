/**
 * G4 — HealthReportView.vue unit tests.
 *
 * Network mocked. Verifies:
 *  - auto-loads on mount + renders summary criticalCount
 *  - DiagnosisCard auto-expands first 3 critical
 *  - coverage note surfaced for tooltip
 *  - POS-only degrade alert when finance missing
 *  - sub_sector select shows the constrained option set
 *  - error path shows sticky toast (duration:0, showClose)
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'RES_3101_009' }),
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

const mockFetch = vi.fn();
vi.mock('@/api/smartbi/healthCheck', () => ({
  fetchHealthCheckReport: (...args: unknown[]) => mockFetch(...args),
}));

const { elMessage } = vi.hoisted(() => ({ elMessage: vi.fn() }));
vi.mock('element-plus', () => ({
  ElMessage: elMessage,
}));

import HealthReportView from '../HealthReportView.vue';

function rx(id: string, priority: string) {
  return {
    id, title: `行动${id}`, description: '描述', owner: '店长',
    timeframe: '本周内', priority, effort: 'low', expectedImpact: '影响',
  };
}

function diag(metricKey: string, severity: string, extra: Record<string, unknown> = {}) {
  return {
    metricKey, metricNameZh: `${metricKey}名`, actualValue: 48.3,
    benchmarkMedian: 40, benchmarkRange: [35, 45], status: '偏高',
    severity, deltaPp: 8.3, deltaPct: 20.75, descriptionZh: '诊断说明',
    suggestionZh: [], rxActions: [rx('A01', 'P0'), rx('A02', 'P1')],
    subSectorNotes: [], playbookId: null, ...extra,
  };
}

function report(overrides: Record<string, unknown> = {}) {
  return {
    success: true,
    message: 'ok',
    data: {
      reportMeta: {
        factoryId: 'RES_3101_009', period: '2026-04',
        snapshotAt: '2026-06-03T10:00:00', subSector: '鱼类餐饮',
        uploadId: 99, cacheHit: false,
      },
      summary: {
        criticalCount: 2, warningCount: 1, infoCount: 0, checkedCount: 5,
        coverageNote: '成本弹性因环比数据不足跳过',
        coverage: { food_cost_ratio: 'ok', delivery_dependency: 'ok' },
      },
      diagnoses: [
        diag('delivery_dependency', 'critical'),
        diag('channel_collection_rate', 'critical'),
        diag('food_cost_ratio', 'warning'),
      ],
      ...overrides,
    },
  };
}

const globalStubs = {
  'el-icon': { template: '<i class="el-icon"><slot /></i>' },
  'el-date-picker': {
    props: ['modelValue', 'placeholder'],
    emits: ['update:modelValue'],
    template: '<input class="el-date-picker" :value="modelValue" />',
  },
  'el-select': {
    props: ['modelValue', 'placeholder'],
    emits: ['update:modelValue'],
    template: '<select class="el-select"><slot /></select>',
  },
  'el-option': {
    props: ['label', 'value'],
    template: '<option class="el-option" :value="value">{{ label }}</option>',
  },
  'el-input-number': {
    props: ['modelValue', 'min', 'max', 'placeholder'],
    emits: ['update:modelValue'],
    template: '<input class="el-input-number" :value="modelValue" />',
  },
  'el-button': {
    props: ['type', 'size', 'loading', 'plain'],
    emits: ['click'],
    template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>',
  },
  'el-alert': {
    props: ['title', 'type', 'closable', 'showIcon'],
    template: '<div class="el-alert" :data-title="title"><slot name="title" /><slot /></div>',
  },
  'el-tooltip': {
    props: ['content', 'placement'],
    template: '<div class="el-tooltip" :data-content="content"><slot /></div>',
  },
  'el-result': {
    props: ['icon', 'title', 'subTitle'],
    template: '<div class="el-result" :data-title="title">{{ subTitle }}</div>',
  },
  // DiagnosisCard real component is heavy; stub but expose expanded prop.
  DiagnosisCard: {
    props: ['diagnosis', 'defaultExpanded'],
    template: '<div class="diagnosis-card-stub" :data-key="diagnosis.metricKey" :data-expanded="defaultExpanded">{{ diagnosis.metricNameZh }}</div>',
  },
};

function mountView() {
  return mount(HealthReportView, {
    global: {
      stubs: globalStubs,
      directives: { loading: () => {} },
    },
  });
}

describe('HealthReportView', () => {
  beforeEach(() => {
    mockFetch.mockReset();
    elMessage.mockReset();
  });

  it('auto-loads on mount and renders criticalCount', async () => {
    mockFetch.mockResolvedValue(report());
    const w = mountView();
    await flushPromises();
    expect(mockFetch).toHaveBeenCalledTimes(1);
    expect(w.find('[data-test="critical-count"]').text()).toBe('2');
    expect(w.find('[data-test="warning-count"]').text()).toBe('1');
  });

  it('auto-expands first 3 critical diagnoses', async () => {
    mockFetch.mockResolvedValue(report());
    const w = mountView();
    await flushPromises();
    const cards = w.findAll('.diagnosis-card-stub');
    expect(cards.length).toBe(3);
    // 2 critical (idx 0,1) → expanded; warning (idx 2) → collapsed
    expect(cards[0].attributes('data-expanded')).toBe('true');
    expect(cards[1].attributes('data-expanded')).toBe('true');
    expect(cards[2].attributes('data-expanded')).toBe('false');
  });

  it('only first 3 critical auto-expand (4th critical stays collapsed)', async () => {
    mockFetch.mockResolvedValue(report({
      diagnoses: [
        diag('m1', 'critical'), diag('m2', 'critical'),
        diag('m3', 'critical'), diag('m4', 'critical'),
      ],
    }));
    const w = mountView();
    await flushPromises();
    const cards = w.findAll('.diagnosis-card-stub');
    expect(cards[3].attributes('data-expanded')).toBe('false');
  });

  it('surfaces coverage note for tooltip', async () => {
    mockFetch.mockResolvedValue(report());
    const w = mountView();
    await flushPromises();
    const tip = w.find('[data-test="coverage-tooltip"]');
    expect(tip.exists()).toBe(true);
    expect(tip.attributes('data-content')).toContain('环比数据不足');
  });

  it('shows POS-only alert when finance metrics missing', async () => {
    mockFetch.mockResolvedValue(report({
      summary: {
        criticalCount: 1, warningCount: 0, infoCount: 0, checkedCount: 3,
        coverageNote: '食材成本率因无财务数据跳过',
        coverage: {
          food_cost_ratio: 'skipped:无财务数据',
          labor_cost_ratio: 'skipped:无财务数据',
          delivery_dependency: 'ok',
        },
      },
      diagnoses: [diag('delivery_dependency', 'critical')],
    }));
    const w = mountView();
    await flushPromises();
    expect(w.find('[data-test="pos-only-alert"]').exists()).toBe(true);
    expect(w.find('[data-test="upload-btn"]').exists()).toBe(true);
  });

  it('does NOT show POS-only alert when finance present', async () => {
    mockFetch.mockResolvedValue(report());
    const w = mountView();
    await flushPromises();
    expect(w.find('[data-test="pos-only-alert"]').exists()).toBe(false);
  });

  it('renders all-healthy result when diagnoses empty', async () => {
    mockFetch.mockResolvedValue(report({
      summary: {
        criticalCount: 0, warningCount: 0, infoCount: 0, checkedCount: 7,
        coverageNote: '已检查 7 项指标, 均无异常',
        coverage: { food_cost_ratio: 'ok' },
      },
      diagnoses: [],
    }));
    const w = mountView();
    await flushPromises();
    expect(w.find('[data-test="all-healthy"]').exists()).toBe(true);
  });

  it('shows sticky error toast (duration:0, showClose) on failure', async () => {
    mockFetch.mockResolvedValue({ success: false, data: null, message: '无权访问 RES_3101_009 的体检报告' });
    const w = mountView();
    await flushPromises();
    await flushPromises();  // dynamic import('element-plus')
    expect(elMessage).toHaveBeenCalled();
    const arg = elMessage.mock.calls[0][0];
    expect(arg.message).toContain('无权访问');
    expect(arg.type).toBe('error');
    expect(arg.duration).toBe(0);
    expect(arg.showClose).toBe(true);
    expect(w.find('[data-test="error-alert"]').exists()).toBe(true);
  });

  it('sub_sector select renders the constrained option set (>=11)', async () => {
    mockFetch.mockResolvedValue(report());
    const w = mountView();
    await flushPromises();
    const opts = w.find('[data-test="subsector-select"]').findAll('.el-option');
    expect(opts.length).toBeGreaterThanOrEqual(11);
    const labels = opts.map((o) => o.text());
    expect(labels).toContain('鱼类餐饮');
    expect(labels).toContain('通用行业');
  });
});
