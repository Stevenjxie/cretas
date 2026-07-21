import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';

const executeIntentMock = vi.fn();
const chatAnalysisStreamMock = vi.fn(() => ({ abort: vi.fn() }));
vi.mock('@/api/smartbi/intent-chat', () => ({
  executeIntent: (...args: unknown[]) => executeIntentMock(...args),
  fetchCachedXlsx: vi.fn(),
  submitIntentFeedback: vi.fn(async () => true),
}));

vi.mock('@/api/smartbi', () => ({
  chatAnalysis: vi.fn(async () => ({ summary: '', charts: [] })),
  chatAnalysisStream: (...args: unknown[]) => chatAnalysisStreamMock(...args),
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

async function askWithDataSource(question: string) {
  const wrapper = mount(AIQuery, { global: { stubs: globalStubs } });
  await flushPromises();
  const vm = wrapper.vm as unknown as {
    inputQuery: string;
    selectedUploadId: number | null;
    handleSendMessage: () => Promise<void>;
  };
  vm.selectedUploadId = 1;
  vm.inputQuery = question;
  await vm.handleSendMessage();
  await flushPromises();
  return wrapper;
}

async function askOnSameThread(questions: string[]) {
  const wrapper = mount(AIQuery, { global: { stubs: globalStubs } });
  await flushPromises();
  const vm = wrapper.vm as unknown as {
    inputQuery: string;
    handleSendMessage: () => Promise<void>;
  };
  for (const question of questions) {
    vm.inputQuery = question;
    await vm.handleSendMessage();
    await flushPromises();
  }
}

describe('AIQuery restaurant owner-action routing', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    executeIntentMock.mockReset();
    chatAnalysisStreamMock.mockReset();
    chatAnalysisStreamMock.mockImplementation(() => ({ abort: vi.fn() }));
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

  it('does not send owner-action context for ordinary revenue or review questions', async () => {
    executeIntentMock.mockResolvedValue({
      status: 'SUCCESS',
      sessionId: 'report-s1',
      intentCode: 'RESTAURANT_REPORT_QUERY',
      message: 'ok',
      resultData: {},
    });

    await ask('\u67e5\u8be2\u672c\u5468\u8425\u6536');
    await ask('\u5ba2\u6237\u8bc4\u4ef7\u600e\u4e48\u6837');

    expect(executeIntentMock).toHaveBeenCalledTimes(2);
    expect(executeIntentMock.mock.calls[0][2]?.context).toBeUndefined();
    expect(executeIntentMock.mock.calls[1][2]?.context).toBeUndefined();
  });

  it('keeps one analysis session when Java responses omit sessionId', async () => {
    executeIntentMock
      .mockResolvedValueOnce({
        status: 'SUCCESS',
        intentCode: 'RESTAURANT_OPS_SALES_SUMMARY',
        message: '昨天营业额高于前天。',
        resultData: { source: 'restaurant_ops_gold' },
      })
      .mockResolvedValueOnce({
        status: 'SUCCESS',
        intentCode: 'RESTAURANT_OPS_SALES_SUMMARY',
        message: '同一日期范围的毛利对比已给出。',
        resultData: { source: 'restaurant_ops_gold' },
      });

    await askOnSameThread([
      '昨天营业额比前天高还是低？',
      '那毛利呢？请沿用刚才比较的两个日期。',
    ]);

    const firstSession = executeIntentMock.mock.calls[0][2]?.sessionId;
    const secondSession = executeIntentMock.mock.calls[1][2]?.sessionId;
    expect(firstSession).toEqual(expect.any(String));
    expect(firstSession).not.toBe('');
    expect(secondSession).toBe(firstSession);
  });

  it('sends package scenario only when the user asks for a calculated owner action', async () => {
    executeIntentMock.mockResolvedValue({
      status: 'SUCCESS',
      sessionId: 'owner-action-s2',
      intentCode: 'RESTAURANT_OWNER_ACTION_CHAT',
      message: 'ok',
      resultData: {
        source: 'restaurant_owner_action',
        scenario: 'package',
        answer: 'ok',
      },
    });

    await ask('\u6839\u636e\u83dc\u54c1\u6bdb\u5229\u548c\u6210\u672c\uff0c\u5e2e\u6211\u7b97\u4e00\u4e2a\u9002\u5408\u4eca\u5929\u63a8\u7684\u5c0f\u5957\u9910');

    expect(executeIntentMock).toHaveBeenCalledTimes(1);
    expect(executeIntentMock.mock.calls[0][2]).toMatchObject({
      context: {
        ownerActionScenario: 'package',
      },
    });
  });

  it('sends revenue growth scenario for broad weekly revenue decline questions', async () => {
    executeIntentMock.mockResolvedValue({
      status: 'SUCCESS',
      sessionId: 'owner-action-revenue-session',
      intentCode: 'RESTAURANT_OWNER_ACTION_CHAT',
      message: 'revenue answer',
      resultData: {
        source: 'restaurant_owner_action',
        scenario: 'revenue_growth',
        sessionId: 'owner-action-revenue-session',
        answer: 'revenue answer',
      },
    });

    await ask('这周营收比上周差，老板应该先做什么？');

    expect(executeIntentMock).toHaveBeenCalledTimes(1);
    expect(executeIntentMock.mock.calls[0][2]).toMatchObject({
      context: {
        ownerActionScenario: 'revenue_growth',
        period: 'this_week',
      },
    });
  });

  it('keeps revenue growth owner-action session for manual leverage follow-ups', async () => {
    executeIntentMock
      .mockResolvedValueOnce({
        status: 'SUCCESS',
        sessionId: 'java-session-revenue',
        intentCode: 'RESTAURANT_OWNER_ACTION_CHAT',
        message: 'revenue answer',
        resultData: {
          source: 'restaurant_owner_action',
          scenario: 'revenue_growth',
          sessionId: 'owner-action-revenue-session',
          answer: 'revenue answer',
        },
      })
      .mockResolvedValueOnce({
        status: 'SUCCESS',
        sessionId: 'java-session-revenue-2',
        intentCode: 'RESTAURANT_OWNER_ACTION_CHAT',
        message: 'follow-up answer',
        resultData: {
          source: 'restaurant_owner_action',
          scenario: 'revenue_growth',
          sessionId: 'owner-action-revenue-session',
          answer: 'follow-up answer',
        },
      });

    await askOnSameThread([
      '这周营收比上周差，老板应该先做什么？',
      '帮我选一个营收杠杆继续拆',
    ]);

    expect(executeIntentMock).toHaveBeenCalledTimes(2);
    expect(executeIntentMock.mock.calls[1][2]).toMatchObject({
      context: {
        ownerActionSessionId: 'owner-action-revenue-session',
        ownerActionScenario: 'revenue_growth',
      },
    });
  });

  it('keeps external event scenario for weather questions even when they mention takeout', async () => {
    executeIntentMock.mockResolvedValue({
      status: 'SUCCESS',
      sessionId: 'owner-action-weather-session',
      intentCode: 'RESTAURANT_OWNER_ACTION_CHAT',
      message: 'weather answer',
      resultData: {
        source: 'restaurant_owner_action',
        scenario: 'external_event_response',
        sessionId: 'owner-action-weather-session',
        answer: 'weather answer',
      },
    });

    await ask('\u4eca\u5929\u4e0b\u96e8\uff0c\u5802\u98df\u548c\u5916\u5356\u5e94\u8be5\u5206\u522b\u600e\u4e48\u5b89\u6392\uff1f');

    expect(executeIntentMock).toHaveBeenCalledTimes(1);
    expect(executeIntentMock.mock.calls[0][2]).toMatchObject({
      context: {
        ownerActionScenario: 'external_event_response',
      },
    });
  });

  it('routes review recovery questions to the backend staff training scenario', async () => {
    executeIntentMock.mockResolvedValue({
      status: 'SUCCESS',
      sessionId: 'owner-action-training-session',
      intentCode: 'RESTAURANT_OWNER_ACTION_CHAT',
      message: 'training answer',
      resultData: {
        source: 'restaurant_owner_action',
        scenario: 'staff_training',
        sessionId: 'owner-action-training-session',
        answer: 'training answer',
      },
    });

    await ask('如果服务差评多，店长今天应该怎么培训员工？');

    expect(executeIntentMock).toHaveBeenCalledTimes(1);
    expect(executeIntentMock.mock.calls[0][2]).toMatchObject({
      context: {
        ownerActionScenario: 'staff_training',
      },
    });
  });

  it('routes inventory, store comparison, and single-item push questions to backend scenarios', async () => {
    executeIntentMock.mockResolvedValue({
      status: 'SUCCESS',
      sessionId: 'owner-action-scenario-session',
      intentCode: 'RESTAURANT_OWNER_ACTION_CHAT',
      message: 'scenario answer',
      resultData: {
        source: 'restaurant_owner_action',
        scenario: 'ok',
        sessionId: 'owner-action-scenario-session',
        answer: 'scenario answer',
      },
    });

    await ask('库存预警和采购补货今天先看什么？');
    await ask('哪家店最值得学习？它的做法能不能复制到青花椒？');
    await ask('主推单品怎么判断有没有拉动加购？');

    expect(executeIntentMock).toHaveBeenCalledTimes(3);
    expect(executeIntentMock.mock.calls[0][2]).toMatchObject({
      context: { ownerActionScenario: 'inventory_reorder' },
    });
    expect(executeIntentMock.mock.calls[1][2]).toMatchObject({
      context: { ownerActionScenario: 'store_compare' },
    });
    expect(executeIntentMock.mock.calls[2][2]).toMatchObject({
      context: { ownerActionScenario: 'single_item_push' },
    });
  });

  it('keeps owner-action context for manual package follow-ups that ask to recalculate or stop', async () => {
    executeIntentMock
      .mockResolvedValueOnce({
        status: 'SUCCESS',
        sessionId: 'owner-action-package-session',
        intentCode: 'RESTAURANT_OWNER_ACTION_CHAT',
        message: 'package answer',
        resultData: {
          source: 'restaurant_owner_action',
          scenario: 'package',
          sessionId: 'owner-action-package-session',
          answer: 'package answer',
        },
      })
      .mockResolvedValueOnce({
        status: 'SUCCESS',
        sessionId: 'owner-action-package-session',
        intentCode: 'RESTAURANT_OWNER_ACTION_CHAT',
        message: 'follow-up answer',
        resultData: {
          source: 'restaurant_owner_action',
          scenario: 'package',
          sessionId: 'owner-action-package-session',
          answer: 'follow-up answer',
        },
      })
      .mockResolvedValueOnce({
        status: 'SUCCESS',
        sessionId: 'owner-action-package-session',
        intentCode: 'RESTAURANT_OWNER_ACTION_CHAT',
        message: 'stop answer',
        resultData: {
          source: 'restaurant_owner_action',
          scenario: 'package',
          sessionId: 'owner-action-package-session',
          answer: 'stop answer',
        },
      });

    await askOnSameThread([
      '\u6839\u636e\u83dc\u54c1\u6bdb\u5229\u548c\u6210\u672c\uff0c\u5e2e\u6211\u7b97\u4e00\u4e2a\u9002\u5408\u4eca\u5929\u63a8\u7684\u5c0f\u5957\u9910',
      '\u5982\u679c\u6362\u4e00\u4e2a\u5c0f\u98df\uff0c\u600e\u4e48\u91cd\u65b0\u7b97\uff1f',
      '\u660e\u5929\u600e\u4e48\u5224\u65ad\u8fd9\u4e2a\u5957\u9910\u8981\u4e0d\u8981\u505c\uff1f',
    ]);

    expect(executeIntentMock).toHaveBeenCalledTimes(3);
    expect(executeIntentMock.mock.calls[1][2]).toMatchObject({
      context: {
        ownerActionSessionId: 'owner-action-package-session',
        ownerActionScenario: 'package',
      },
    });
    expect(executeIntentMock.mock.calls[2][2]).toMatchObject({
      context: {
        ownerActionSessionId: 'owner-action-package-session',
        ownerActionScenario: 'package',
      },
    });
  });

  it.each([
    ['NEED_CLARIFICATION', true, '请先确认翻台率还是翻台次数。'],
    ['FAILED', true, '本次查询没有获得可展示的结果。'],
    ['COMPLETED', true, '已给出今天先不要做的三项动作。'],
    ['FUTURE_STATUS', true, '已识别的新状态答复。'],
  ])(
    'handles Java status %s without falling through to uploaded-data analysis',
    async (status, intentRecognized, message) => {
      executeIntentMock.mockResolvedValue({
        status,
        intentRecognized,
        intentCode: null,
        intentName: null,
        message,
        formattedText: message,
        resultData: null,
      });

      const wrapper = await askWithDataSource('餐饮分析测试问题');

      expect(executeIntentMock).toHaveBeenCalledTimes(1);
      expect(chatAnalysisStreamMock).not.toHaveBeenCalled();
      expect(wrapper.text()).toContain(message);
    },
  );

  it('falls through only for a genuine unrecognized Java clarification', async () => {
    executeIntentMock.mockResolvedValue({
      status: 'NEED_CLARIFICATION',
      intentRecognized: false,
      intentCode: null,
      intentName: null,
      message: '没有识别出可执行的业务意图。',
      formattedText: '没有识别出可执行的业务意图。',
      resultData: null,
    });

    await askWithDataSource('请分析我刚上传的数据');

    expect(chatAnalysisStreamMock).toHaveBeenCalledTimes(1);
  });
});
