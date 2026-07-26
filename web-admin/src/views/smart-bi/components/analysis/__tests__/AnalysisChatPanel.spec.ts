import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  askIntent: vi.fn(),
}));

vi.mock('@/api/smartbi/restaurant-synthesis', () => ({
  askRestaurantIntent: (...args: unknown[]) => mocks.askIntent(...args),
}));

vi.mock('element-plus', () => ({
  ElInput: { name: 'ElInput', template: '<input />' },
  ElButton: { name: 'ElButton', template: '<button @click="$emit(\'click\')"><slot /></button>' },
  ElRadioButton: { name: 'ElRadioButton', template: '<button><slot /></button>' },
  ElRadioGroup: { name: 'ElRadioGroup', template: '<div><slot /></div>' },
  ElMessage: { error: vi.fn(), success: vi.fn() },
}));

vi.mock('../../chat/ChatBubble.vue', () => ({
  default: {
    name: 'ChatBubble',
    props: ['turn'],
    template: '<div class="stub-bubble" :data-role="turn.role">{{ turn.content }}<slot name="followups" /></div>',
  },
}));

import AnalysisChatPanel from '../AnalysisChatPanel.vue';

const mountPanel = () => mount(AnalysisChatPanel, {
  props: {
    factoryId: 'DEMO_REST',
    pageTitle: '运营分析',
    contexts: [{
      key: 'void-audit',
      title: '撤单稽核',
      dataSummary: '撤单率 0.55%',
      dataScope: '2026-07-01 至 2026-07-27',
      unavailableMetrics: ['逐笔撤单原因'],
    }],
  },
  global: {
    stubs: {
      ChatTypingIndicator: { template: '<div class="stub-typing" />' },
    },
  },
});

describe('AnalysisChatPanel unified intent routing', () => {
  beforeEach(() => {
    mocks.askIntent.mockReset();
    if (typeof globalThis.crypto?.randomUUID !== 'function') {
      Object.defineProperty(globalThis, 'crypto', {
        value: { randomUUID: () => Math.random().toString(36).slice(2) },
        configurable: true,
      });
    }
  });

  it('routes a narrow restaurant question through the Java READ orchestrator without polluting the question', async () => {
    mocks.askIntent.mockResolvedValue({
      success: true,
      answer: '你想看哪个时间范围？请选择本月、上个月、最近7天或最近30天。',
      charts: [],
      alerts: [],
      source: 'restaurant_ops_gold',
      followUpActions: [
        { label: '最近30天', question: '最近30天哪家店业绩最好' },
      ],
    });

    const wrapper = mountPanel();
    const vm = wrapper.vm as unknown as { sendMessage: (text?: string) => Promise<void> };
    await vm.sendMessage('哪家店业绩最好');
    await flushPromises();

    expect(mocks.askIntent).toHaveBeenCalledTimes(1);
    const [factoryId, question, sessionId, context] = mocks.askIntent.mock.calls[0];
    expect(factoryId).toBe('DEMO_REST');
    expect(question).toBe('哪家店业绩最好');
    expect(sessionId).toEqual(expect.any(String));
    expect(context).toMatchObject({
      dimensionHints: ['void-audit'],
    });
    expect(context.pageContext).toContain('撤单率 0.55%');
    expect(question).not.toContain('撤单率');

    const aiBubble = wrapper.find('.stub-bubble[data-role="ai"]');
    expect(aiBubble.text()).toContain('时间范围');

    const followUp = wrapper.findAll('button')
      .find((button) => button.text() === '最近30天');
    expect(followUp).toBeDefined();
    await followUp!.trigger('click');
    await flushPromises();
    expect(mocks.askIntent.mock.calls[1][1]).toBe('最近30天哪家店业绩最好');
  });

  it('keeps comprehensive answers and charts returned by the same orchestrator', async () => {
    mocks.askIntent.mockResolvedValue({
      success: true,
      answer: '### 本轮已纳入维度\n- 营收与订单\n### 尚缺但可补充的维度\n- 物理客流',
      charts: [{ type: 'bar', title: '门店营收', option: { series: [] } }],
      alerts: [],
      source: 'deterministic_fallback',
    });

    const wrapper = mountPanel();
    const vm = wrapper.vm as unknown as { sendMessage: (text?: string) => Promise<void> };
    await vm.sendMessage('请综合分析最近30天全部门店经营情况');
    await flushPromises();

    expect(mocks.askIntent).toHaveBeenCalledWith(
      'DEMO_REST',
      '请综合分析最近30天全部门店经营情况',
      expect.any(String),
      expect.objectContaining({ dimensionHints: ['void-audit'] }),
    );
    expect(wrapper.find('.stub-bubble[data-role="ai"]').text()).toContain('尚缺但可补充的维度');
  });

  it('renders an honest failure instead of fabricating a fallback answer', async () => {
    mocks.askIntent.mockResolvedValue({
      success: false,
      answer: '',
      charts: [],
      alerts: [],
      error: 'Java intent service unavailable',
    });

    const wrapper = mountPanel();
    const vm = wrapper.vm as unknown as { sendMessage: (text?: string) => Promise<void> };
    await vm.sendMessage('哪家店业绩最好');
    await flushPromises();

    const aiBubble = wrapper.find('.stub-bubble[data-role="ai"]');
    expect(aiBubble.text()).toContain('请求失败');
    expect(aiBubble.text()).toContain('Java intent service unavailable');
  });
});
