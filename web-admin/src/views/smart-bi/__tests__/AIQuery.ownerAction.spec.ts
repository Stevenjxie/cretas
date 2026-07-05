import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';

const executeIntentMock = vi.fn();
vi.mock('@/api/smartbi/intent-chat', () => ({
  executeIntent: (...args: unknown[]) => executeIntentMock(...args),
  fetchCachedXlsx: vi.fn(),
  submitIntentFeedback: vi.fn(async () => true),
}));

vi.mock('@/api/smartbi', () => ({
  chatAnalysis: vi.fn(async () => ({ summary: '', charts: [] })),
  chatAnalysisStream: vi.fn(() => ({ abort: vi.fn() })),
  getUploadHistory: vi.fn(async () => []),
  deduplicateUploads: vi.fn(async () => ({})),
  nl2sql: vi.fn(async () => ({})),
  logFeedback: vi.fn(async () => ({})),
}));

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({
    factoryId: 'DEMO_REST',
    businessDomain: 'RESTAURANT',
  }),
}));

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: vi.fn() }),
}));

vi.mock('@/utils/echarts', () => ({
  default: { init: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }) },
}));
vi.mock('@/utils/echarts-fmt', () => ({ processEChartsOptions: (o: unknown) => o }));
vi.mock('@/composables/useChartResize', () => ({
  useChartResize: () => ({ observe: vi.fn(), disconnect: vi.fn() }),
}));
vi.mock('marked', () => ({ marked: (s: string) => s }));
vi.mock('dompurify', () => ({ default: { sanitize: (s: string) => s } }));
vi.mock('@/components/smartbi', () => ({ AIInsightPanel: { template: '<div />' } }));
vi.mock('@/components/smartbi/SmartBIEmptyState.vue', () => ({ default: { template: '<div />' } }));
vi.mock('@/components/smart-bi/MaterializedAnalysisPanel.vue', () => ({ default: { template: '<div />' } }));
vi.mock('@/api/smartbi/materialized', () => ({ listFactoryTemplates: vi.fn(async () => []) }));

import AIQuery from '../AIQuery.vue';

const globalStubs = {
  'el-button': { emits: ['click'], template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>' },
  'el-link': { emits: ['click'], template: '<a class="el-link" @click="$emit(\'click\')"><slot /></a>' },
  'el-icon': { template: '<i><slot /></i>' },
  'el-input': { template: '<input />' },
  'el-autocomplete': { template: '<div class="el-autocomplete"><slot :item="{ value: \'\' }" /></div>' },
  'el-tag': { template: '<span><slot /></span>' },
  'el-tooltip': { template: '<div><slot /></div>' },
  'el-table': { template: '<table><slot /></table>' },
  'el-table-column': { template: '<td><slot /></td>' },
  'el-select': { template: '<select><slot /></select>' },
  'el-option': { template: '<option><slot /></option>' },
  'el-switch': { template: '<input type="checkbox" />' },
  'el-empty': { template: '<div><slot /></div>' },
  'el-alert': { template: '<div><slot /></div>' },
};

async function ask(question: string) {
  const wrapper = mount(AIQuery, { global: { stubs: globalStubs } });
  await flushPromises();
  const vm = wrapper.vm as unknown as {
    inputQuery: string;
    handleSendMessage: () => Promise<void>;
  };
  vm.inputQuery = question;
  await vm.handleSendMessage();
  await flushPromises();
}

describe('AIQuery restaurant owner-action routing', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    executeIntentMock.mockReset();
  });

  it('sends inferred scenario context for the first typed seating question', async () => {
    executeIntentMock.mockResolvedValue({
      status: 'SUCCESS',
      sessionId: 'owner-action-s1',
      intentCode: 'RESTAURANT_OWNER_ACTION_CHAT',
      message: 'ok',
      resultData: {
        source: 'restaurant_owner_action',
        scenario: 'seating_mix',
        answer: 'ok',
      },
    });

    await ask('\u4eca\u5929\u684c\u578b\u548c\u6392\u73ed\u600e\u4e48\u8c03\uff0c\u4e8c\u4eba\u684c\u56db\u4eba\u684c\u600e\u4e48\u5b89\u6392\uff1f');

    expect(executeIntentMock).toHaveBeenCalledTimes(1);
    expect(executeIntentMock.mock.calls[0][2]).toMatchObject({
      context: {
        ownerActionScenario: 'seating_mix',
        period: 'this_week',
      },
    });
  });
});
