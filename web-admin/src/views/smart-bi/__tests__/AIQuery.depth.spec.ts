/**
 * P1 conversational-depth tests for AIQuery.vue (2026-06-02).
 *
 * Covers:
 *   - gold-tool answer with suggestedFollowups/glossary/chartGuide renders
 *     the follow-up chips + the 字段说明 toggle.
 *   - clicking a follow-up chip sets inputQuery to the chip's `question`
 *     and re-invokes the intent pipeline (executeIntent called again).
 *   - tryLocalMetaAnswer: "X 什么意思" against the previous answer's glossary
 *     produces a local assistant bubble with ZERO additional backend calls.
 *
 * Heavy deps (echarts / marked / DOMPurify / child panels / stores / router)
 * are mocked so the mount stays light — mirrors GoldPreview.spec.ts pattern.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';

// ── executeIntent / intent-chat ──────────────────────────────
const executeIntentMock = vi.fn();
const fetchCachedXlsxMock = vi.fn();
const submitIntentFeedbackMock = vi.fn(async () => true);
vi.mock('@/api/smartbi/intent-chat', () => ({
  executeIntent: (...args: unknown[]) => executeIntentMock(...args),
  fetchCachedXlsx: (...args: unknown[]) => fetchCachedXlsxMock(...args),
  submitIntentFeedback: (...args: unknown[]) => submitIntentFeedbackMock(...args),
}));

// ── smartbi API (Python fallback path — should NOT be called in these tests) ──
const chatAnalysisMock = vi.fn(async () => ({ summary: '', charts: [] }));
const chatAnalysisStreamMock = vi.fn(() => ({ abort: vi.fn() }));
vi.mock('@/api/smartbi', () => ({
  chatAnalysis: (...a: unknown[]) => chatAnalysisMock(...a),
  chatAnalysisStream: (...a: unknown[]) => chatAnalysisStreamMock(...a),
  getUploadHistory: vi.fn(async () => []),
  deduplicateUploads: vi.fn(async () => ({})),
  nl2sql: vi.fn(async () => ({})),
  logFeedback: vi.fn(async () => ({})),
}));

// ── auth store + router ──────────────────────────────────────
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'RES_3101_009', businessDomain: 'RESTAURANT' }),
}));
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: vi.fn() }),
}));

// ── echarts + composables (no real charts in jsdom) ──────────
vi.mock('@/utils/echarts', () => ({
  default: { init: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }) },
}));
vi.mock('@/utils/echarts-fmt', () => ({ processEChartsOptions: (o: unknown) => o }));
vi.mock('@/composables/useChartResize', () => ({
  useChartResize: () => ({ observe: vi.fn(), disconnect: vi.fn() }),
}));
vi.mock('marked', () => ({ marked: (s: string) => s }));
vi.mock('dompurify', () => ({ default: { sanitize: (s: string) => s } }));

// ── child components → trivial stubs ─────────────────────────
vi.mock('@/components/smartbi', () => ({
  AIInsightPanel: { template: '<div />' },
}));
vi.mock('@/components/smartbi/SmartBIEmptyState.vue', () => ({
  default: { template: '<div />' },
}));
vi.mock('@/components/smart-bi/MaterializedAnalysisPanel.vue', () => ({
  default: { template: '<div />' },
}));

// ── materialized API (recommend-template loader on mount) ────
vi.mock('@/api/smartbi/materialized', () => ({
  listFactoryTemplates: vi.fn(async () => []),
}));

import AIQuery from '../AIQuery.vue';

// Element Plus stubs — keep buttons/links clickable, render slots.
const globalStubs = {
  'el-button': {
    props: ['icon'],
    emits: ['click'],
    template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>',
  },
  'el-link': {
    emits: ['click'],
    template: '<a class="el-link" @click="$emit(\'click\')"><slot /></a>',
  },
  'el-icon': { template: '<i><slot /></i>' },
  'el-input': { template: '<input />' },
  // el-autocomplete renders a `#default="{ item }"` slot; pass a dummy item so
  // the slot doesn't destructure undefined during mount.
  'el-autocomplete': {
    template: '<div class="el-autocomplete"><slot :item="{ value: \'\' }" /></div>',
  },
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

function goldAnswer(message: string) {
  return {
    status: 'SUCCESS',
    sessionId: 's1',
    message,
    resultData: {
      message,
      chartConfig: { type: 'pie', title: '各平台评价量占比', option: { series: [] } },
      suggestedFollowups: [
        { label: '整体评价总览', question: '客户评价怎么样' },
        { label: '回复率情况', question: '评价回复率' },
      ],
      glossary: {
        服务分: '顾客对门店服务态度/效率的评分(满分5分)。',
        平均星级: '该平台所有评价星级的算术平均(满分5分)。',
      },
      chartGuide: '扇区面积代表各平台评价量占比。',
    },
  };
}

async function mountAndAsk(question: string) {
  const wrapper = mount(AIQuery, { global: { stubs: globalStubs } });
  await flushPromises();
  // drive the message pipeline via the exposed instance state
  const vm = wrapper.vm as unknown as {
    inputQuery: string;
    handleSendMessage: () => Promise<void>;
    chatHistory: Array<Record<string, unknown>>;
  };
  vm.inputQuery = question;
  await vm.handleSendMessage();
  await flushPromises();
  return { wrapper, vm };
}

describe('AIQuery P1 conversational depth', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    executeIntentMock.mockReset();
    chatAnalysisMock.mockClear();
  });

  it('renders follow-up chips + 字段说明 toggle from a gold answer', async () => {
    executeIntentMock.mockResolvedValue(goldAnswer('各平台评价对比...'));
    const { wrapper, vm } = await mountAndAsk('各平台评价对比');

    const assistants = vm.chatHistory.filter((m) => m.role === 'assistant');
    const assistant = assistants[assistants.length - 1];
    expect(assistant).toBeTruthy();
    expect(assistant?.suggestedFollowups).toHaveLength(2);
    expect(assistant?.glossary).toBeTruthy();
    expect(assistant?.chartGuide).toContain('扇区');

    // chip labels + the toggle text are rendered
    expect(wrapper.text()).toContain('继续追问');
    expect(wrapper.text()).toContain('整体评价总览');
    expect(wrapper.text()).toContain('字段说明 / 怎么看这张图');
  });

  it('clicking a follow-up chip sends the chip question (executeIntent re-invoked)', async () => {
    executeIntentMock.mockResolvedValue(goldAnswer('各平台评价对比...'));
    const { wrapper, vm } = await mountAndAsk('各平台评价对比');
    expect(executeIntentMock).toHaveBeenCalledTimes(1);

    // find the first follow-up chip button by its label text
    const chip = wrapper.findAll('button.el-button').find((b) => b.text().includes('整体评价总览'));
    expect(chip).toBeTruthy();
    await chip!.trigger('click');
    await flushPromises();

    // second invocation with the chip's question
    expect(executeIntentMock).toHaveBeenCalledTimes(2);
    expect(executeIntentMock.mock.calls[1][1]).toBe('客户评价怎么样');
    void vm;
  });

  it('tryLocalMetaAnswer answers "服务分什么意思" with ZERO backend calls', async () => {
    executeIntentMock.mockResolvedValue(goldAnswer('各平台评价对比...'));
    const { vm } = await mountAndAsk('各平台评价对比');
    expect(executeIntentMock).toHaveBeenCalledTimes(1);
    const beforeLen = vm.chatHistory.length;

    vm.inputQuery = '服务分什么意思';
    await vm.handleSendMessage();
    await flushPromises();

    // No additional backend calls
    expect(executeIntentMock).toHaveBeenCalledTimes(1);
    expect(chatAnalysisMock).not.toHaveBeenCalled();

    // A local user+assistant pair was appended; assistant carries the glossary def
    expect(vm.chatHistory.length).toBe(beforeLen + 2);
    const last = vm.chatHistory[vm.chatHistory.length - 1];
    expect(last.role).toBe('assistant');
    expect(String(last.content)).toContain('服务分');
    expect(String(last.content)).toContain('服务态度');
  });

  it('offers same-topic time follow-ups for the sheet wording "哪个菜卖得好"', async () => {
    executeIntentMock.mockResolvedValue({
      status: 'SUCCESS',
      sessionId: 'dish-session',
      message: '近 30 天菜品销量排行',
      resultData: { message: '近 30 天菜品销量排行' },
    });

    const { wrapper } = await mountAndAsk('哪个菜卖得好');

    expect(wrapper.text()).toContain('换个时间');
    expect(wrapper.text()).toContain('上个月');
    expect(wrapper.text()).toContain('近30天');
  });

  it('renders explicit time clarification buttons and continues with the selected period', async () => {
    executeIntentMock
      .mockResolvedValueOnce({
        status: 'NEED_CLARIFICATION',
        intentRecognized: true,
        sessionId: 'dish-time-session',
        message: '你想看哪个时间范围？请选择本月、上个月、最近7天或最近30天。',
        resultData: {
          suggestedFollowups: [
            { label: '本月', question: '本月' },
            { label: '上个月', question: '上个月' },
            { label: '最近7天', question: '最近7天' },
            { label: '最近30天', question: '最近30天' },
          ],
        },
      })
      .mockResolvedValueOnce({
        status: 'SUCCESS',
        sessionId: 'dish-time-session',
        intentCode: 'RESTAURANT_OPS_GROSS_MARGIN',
        message: '本月菜品销量已给出。',
        resultData: { message: '本月菜品销量已给出。' },
      });

    const { wrapper } = await mountAndAsk('哪个菜卖得好');

    expect(wrapper.text()).toContain('你想看哪个时间范围');
    for (const label of ['本月', '上个月', '最近7天', '最近30天']) {
      expect(wrapper.text()).toContain(label);
    }

    const thisMonth = wrapper
      .findAll('button.el-button')
      .find((button) => button.text().includes('本月'));
    expect(thisMonth).toBeTruthy();
    await thisMonth!.trigger('click');
    await flushPromises();

    expect(executeIntentMock).toHaveBeenCalledTimes(2);
    expect(executeIntentMock.mock.calls[1][1]).toBe('本月');
    expect(executeIntentMock.mock.calls[1][2]?.sessionId)
      .toBe(executeIntentMock.mock.calls[0][2]?.sessionId);
  });
});
