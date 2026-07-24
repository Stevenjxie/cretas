import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  stream: vi.fn(),
  ask: vi.fn(),
  feedback: vi.fn(),
}));

vi.mock('@/api/smartbi/restaurant-synthesis', () => ({
  askRestaurantSynthesisStream: (...args: unknown[]) => mocks.stream(...args),
  askRestaurantSynthesis: (...args: unknown[]) => mocks.ask(...args),
  sendRestaurantAnswerFeedback: (...args: unknown[]) => mocks.feedback(...args),
}));

vi.mock('element-plus', () => ({
  ElInput: { name: 'ElInput', template: '<input />' },
  ElButton: { name: 'ElButton', template: '<button @click="$emit(\'click\')"><slot /></button>' },
  ElMessage: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
  ElMessageBox: { prompt: vi.fn() },
}));

// ChatBubble transitively imports utils/echarts (echarts-wordcloud), which
// requires a real canvas — mock the child components at module level so the
// panel logic can be tested in jsdom.
vi.mock('../ChatBubble.vue', () => ({
  default: {
    name: 'ChatBubble',
    props: ['turn'],
    template: '<div class="stub-bubble" :data-role="turn.role" :data-error="turn.error || \'\'">{{ turn.content }}</div>',
  },
}));
vi.mock('../GrossMarginDeclineRun.vue', () => ({
  default: { name: 'GrossMarginDeclineRun', template: '<div class="stub-run" />' },
}));

import RestaurantChatPanel from '../RestaurantChatPanel.vue';

interface StreamCallbacks {
  onStatus?: (text: string) => void;
  onChunk?: (text: string) => void;
  onCharts?: (charts: unknown[]) => void;
  onDone?: (result: unknown) => void;
  onError?: (message: string) => void;
}

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

describe('RestaurantChatPanel streaming', () => {
  beforeEach(() => {
    mocks.stream.mockReset();
    mocks.ask.mockReset();
    mocks.feedback.mockReset();
    if (typeof globalThis.crypto?.randomUUID !== 'function') {
      Object.defineProperty(globalThis, 'crypto', {
        value: { randomUUID: () => Math.random().toString(36).slice(2) },
        configurable: true,
      });
    }
  });

  it('streams chunks progressively into one ai turn and finalizes with the done payload', async () => {
    vi.useFakeTimers();
    try {
      mocks.stream.mockImplementation(async (
        _q: string, _sid: string, callbacks: StreamCallbacks,
      ) => {
        callbacks.onStatus?.('正在读取经营数据…');
        callbacks.onChunk?.('**结论：赚钱**');
        callbacks.onChunk?.('\n\n毛利率 **72.3%**');
        callbacks.onDone?.({
          success: true,
          answer: '**结论：赚钱**\n\n毛利率 **72.3%**',
          charts: [{ type: 'bar', title: 'x', option: { series: [] } }],
          alerts: [],
          source: 'synthesis',
        });
      });

      const wrapper = mountPanel();
      const vm = wrapper.vm as unknown as { sendMessage: (t?: string) => Promise<void> };
      const send = vm.sendMessage('最近赚钱吗');
      await vi.runAllTimersAsync();
      await send;
      await flushPromises();

      const bubbles = wrapper.findAll('.stub-bubble[data-role="ai"]');
      expect(bubbles).toHaveLength(1);
      expect(bubbles[0].text()).toContain('结论：赚钱');
      expect(bubbles[0].text()).toContain('72.3%');
      // stream succeeded — the non-stream endpoint must NOT be called
      expect(mocks.ask).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('shows the status text while no chunk has arrived yet', async () => {
    let release: () => void = () => {};
    mocks.stream.mockImplementation((_q: string, _sid: string, callbacks: StreamCallbacks) => {
      callbacks.onStatus?.('正在分析…');
      return new Promise<void>((resolve) => { release = () => { callbacks.onDone?.({ success: true, answer: 'ok', charts: [], alerts: [] }); resolve(); }; });
    });

    const wrapper = mountPanel();
    const vm = wrapper.vm as unknown as { sendMessage: (t?: string) => Promise<void> };
    const send = vm.sendMessage('这个月生意怎么样');
    await flushPromises();

    expect(wrapper.find('[data-testid="chat-stream-status"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="chat-stream-status"]').text()).toBe('正在分析…');

    release();
    await send;
    await flushPromises();
    expect(wrapper.find('[data-testid="chat-stream-status"]').exists()).toBe(false);
  });

  it('falls back to the non-stream endpoint when the stream fails before any chunk', async () => {
    mocks.stream.mockRejectedValue(new Error('SSE not supported by proxy'));
    mocks.ask.mockResolvedValue({
      success: true,
      answer: '非流式兜底回答',
      charts: [],
      alerts: [],
      source: 'synthesis',
    });

    const wrapper = mountPanel();
    const vm = wrapper.vm as unknown as { sendMessage: (t?: string) => Promise<void> };
    await vm.sendMessage('昨天卖了多少钱');
    await flushPromises();

    expect(mocks.ask).toHaveBeenCalledTimes(1);
    const bubbles = wrapper.findAll('.stub-bubble[data-role="ai"]');
    expect(bubbles).toHaveLength(1);
    expect(bubbles[0].text()).toContain('非流式兜底回答');
    expect(bubbles[0].attributes('data-error')).toBe('');
  });

  it('pushes an honest error turn when both stream and fallback fail', async () => {
    mocks.stream.mockRejectedValue(new Error('stream down'));
    mocks.ask.mockResolvedValue({
      success: false,
      answer: '',
      charts: [],
      alerts: [],
      error: 'Python service error: 503',
    });

    const wrapper = mountPanel();
    const vm = wrapper.vm as unknown as { sendMessage: (t?: string) => Promise<void> };
    await vm.sendMessage('q');
    await flushPromises();

    const bubbles = wrapper.findAll('.stub-bubble[data-role="ai"]');
    expect(bubbles).toHaveLength(1);
    expect(bubbles[0].text()).toContain('请求失败');
    expect(bubbles[0].text()).toContain('503');
    expect(bubbles[0].attributes('data-error')).toContain('503');
  });

  it('keeps partial streamed text and appends an honest interruption note when the stream breaks mid-answer', async () => {
    vi.useFakeTimers();
    try {
      mocks.stream.mockImplementation(async (
        _q: string, _sid: string, callbacks: StreamCallbacks,
      ) => {
        callbacks.onChunk?.('前半段回答');
        throw new Error('connection reset');
      });

      const wrapper = mountPanel();
      const vm = wrapper.vm as unknown as { sendMessage: (t?: string) => Promise<void> };
      const send = vm.sendMessage('翻台率怎么样');
      await vi.runAllTimersAsync();
      await send;
      await flushPromises();

      // mid-stream break must NOT re-ask (would duplicate a half-answered query)
      expect(mocks.ask).not.toHaveBeenCalled();
      const bubbles = wrapper.findAll('.stub-bubble[data-role="ai"]');
      expect(bubbles).toHaveLength(1);
      expect(bubbles[0].text()).toContain('前半段回答');
      expect(bubbles[0].text()).toContain('流式输出中断');
    } finally {
      vi.useRealTimers();
    }
  });
});
