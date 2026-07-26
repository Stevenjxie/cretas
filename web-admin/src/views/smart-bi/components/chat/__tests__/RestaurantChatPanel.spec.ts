import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  askIntent: vi.fn(),
  feedback: vi.fn(),
}));

vi.mock('@/api/smartbi/restaurant-synthesis', () => ({
  askRestaurantIntent: (...args: unknown[]) => mocks.askIntent(...args),
  sendRestaurantAnswerFeedback: (...args: unknown[]) => mocks.feedback(...args),
}));

vi.mock('element-plus', () => ({
  ElInput: { name: 'ElInput', template: '<input />' },
  ElButton: { name: 'ElButton', template: '<button @click="$emit(\'click\')"><slot /></button>' },
  ElMessage: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
  ElMessageBox: { prompt: vi.fn() },
}));

vi.mock('../ChatBubble.vue', () => ({
  default: {
    name: 'ChatBubble',
    props: ['turn'],
    template: '<div class="stub-bubble" :data-role="turn.role" :data-error="turn.error || \'\'">{{ turn.content }}<slot name="followups" /></div>',
  },
}));
vi.mock('../GrossMarginDeclineRun.vue', () => ({
  default: { name: 'GrossMarginDeclineRun', template: '<div class="stub-run" />' },
}));

import RestaurantChatPanel from '../RestaurantChatPanel.vue';

const mountPanel = () => mount(RestaurantChatPanel, {
  props: {
    factoryId: 'DEMO_REST',
    agentRunEligible: false,
    startDate: '2026-07-01',
    endDate: '2026-07-24',
  },
  global: {
    stubs: {
      ChatTypingIndicator: { template: '<div class="stub-typing" />' },
    },
  },
});

describe('RestaurantChatPanel unified intent routing', () => {
  beforeEach(() => {
    mocks.askIntent.mockReset();
    mocks.feedback.mockReset();
    if (typeof globalThis.crypto?.randomUUID !== 'function') {
      Object.defineProperty(globalThis, 'crypto', {
        value: { randomUUID: () => Math.random().toString(36).slice(2) },
        configurable: true,
      });
    }
  });

  it('sends the raw question, tenant and stable session through the canonical route', async () => {
    mocks.askIntent.mockResolvedValue({
      success: true,
      answer: '请先选择时间范围：本月、上月还是最近30天？',
      charts: [],
      alerts: [],
      followUpActions: [
        { label: '本月', question: '本月哪家店业绩最好' },
      ],
    });

    const wrapper = mountPanel();
    const vm = wrapper.vm as unknown as { sendMessage: (t?: string) => Promise<void> };
    await vm.sendMessage('哪家店业绩最好');
    await flushPromises();

    expect(mocks.askIntent).toHaveBeenCalledTimes(1);
    const [factoryId, question, sessionId, context] = mocks.askIntent.mock.calls[0];
    expect(factoryId).toBe('DEMO_REST');
    expect(question).toBe('哪家店业绩最好');
    expect(typeof sessionId).toBe('string');
    expect(context).toBeUndefined();
    expect(wrapper.find('.stub-bubble[data-role="ai"]').text()).toContain('选择时间范围');

    const followUp = wrapper.findAll('button')
      .find((button) => button.text() === '本月');
    expect(followUp).toBeDefined();
    await followUp!.trigger('click');
    await flushPromises();
    expect(mocks.askIntent.mock.calls[1][1]).toBe('本月哪家店业绩最好');
  });

  it('keeps the non-blocking status visible until the intent response arrives', async () => {
    let release: (value: unknown) => void = () => {};
    mocks.askIntent.mockReturnValue(new Promise((resolve) => { release = resolve; }));

    const wrapper = mountPanel();
    const vm = wrapper.vm as unknown as { sendMessage: (t?: string) => Promise<void> };
    const pending = vm.sendMessage('本月全部门店销量最高的菜品前10名');
    await flushPromises();

    expect(wrapper.find('[data-testid="chat-stream-status"]').text())
      .toBe('正在识别问题并读取经营数据…');

    release({ success: true, answer: 'Top 10', charts: [], alerts: [] });
    await pending;
    await flushPromises();
    expect(wrapper.find('[data-testid="chat-stream-status"]').exists()).toBe(false);
  });

  it('renders comprehensive synthesis returned by the same canonical route', async () => {
    mocks.askIntent.mockResolvedValue({
      success: true,
      answer: '综合经营诊断结论',
      charts: [{ type: 'bar', title: '营收', option: { series: [] } }],
      alerts: [],
      source: 'comprehensive_synthesis',
    });

    const wrapper = mountPanel();
    const vm = wrapper.vm as unknown as { sendMessage: (t?: string) => Promise<void> };
    await vm.sendMessage('综合分析最近30天客流、菜品、毛利并给建议');
    await flushPromises();

    expect(wrapper.find('.stub-bubble[data-role="ai"]').text()).toContain('综合经营诊断结论');
  });

  it('surfaces an honest failure without retrying a bypass endpoint', async () => {
    mocks.askIntent.mockResolvedValue({
      success: false,
      answer: '',
      charts: [],
      alerts: [],
      error: 'Java intent service error: 503',
    });

    const wrapper = mountPanel();
    const vm = wrapper.vm as unknown as { sendMessage: (t?: string) => Promise<void> };
    await vm.sendMessage('q');
    await flushPromises();

    expect(mocks.askIntent).toHaveBeenCalledTimes(1);
    const bubble = wrapper.find('.stub-bubble[data-role="ai"]');
    expect(bubble.text()).toContain('请求失败');
    expect(bubble.text()).toContain('503');
    expect(bubble.attributes('data-error')).toContain('503');
  });

  it('resets the conversation session when cleared', async () => {
    mocks.askIntent.mockResolvedValue({
      success: true,
      answer: 'ok',
      charts: [],
      alerts: [],
    });

    const wrapper = mountPanel();
    const vm = wrapper.vm as unknown as {
      sendMessage: (t?: string) => Promise<void>;
      clearConversation: () => Promise<void>;
    };
    await vm.sendMessage('第一问');
    const firstSession = mocks.askIntent.mock.calls[0][2];
    await vm.clearConversation();
    await vm.sendMessage('第二问');
    const secondSession = mocks.askIntent.mock.calls[1][2];

    expect(secondSession).not.toBe(firstSession);
    expect(wrapper.findAll('.stub-bubble[data-role="user"]')).toHaveLength(1);
  });
});
