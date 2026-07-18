import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  active: vi.fn(),
  stream: vi.fn(),
  replay: vi.fn(),
}));

vi.mock('@/api/smartbi/restaurant-agent-runs', () => ({
  isRestaurantAgentRunActive: () => mocks.active(),
  streamGrossMarginDeclineRun: (...args: unknown[]) => mocks.stream(...args),
  replayGrossMarginDeclineRun: (...args: unknown[]) => mocks.replay(...args),
}));

import GrossMarginDeclineRun from '../GrossMarginDeclineRun.vue';

const event = (sequence: number, eventType: string) => ({
  schemaVersion: '1.0',
  runId: '11111111-1111-4111-8111-111111111111',
  sequence,
  eventType,
  stepId: null,
  toolName: null,
  payload: {},
});

const mountRun = (eligible = true) => mount(GrossMarginDeclineRun, {
  props: {
    factoryId: 'REST-1',
    eligible,
    startDate: '2026-07-01',
    endDate: '2026-07-19',
  },
  global: {
    stubs: {
      ElButton: {
        template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>',
      },
    },
  },
});

describe('GrossMarginDeclineRun', () => {
  beforeEach(() => {
    mocks.active.mockReset().mockReturnValue(true);
    mocks.stream.mockReset();
    mocks.replay.mockReset();
  });

  it('renders no action and sends no Run request when OFF or ineligible', async () => {
    mocks.active.mockReturnValue(false);
    const off = mountRun(true);
    expect(off.find('[data-testid="gross-margin-decline-run"]').exists()).toBe(false);

    mocks.active.mockReturnValue(true);
    const denied = mountRun(false);
    expect(denied.find('[data-testid="gross-margin-decline-run"]').exists()).toBe(false);
    expect(mocks.stream).not.toHaveBeenCalled();
  });

  it('shows the dashboard window and de-duplicates SSE plus replay events', async () => {
    mocks.stream.mockImplementation(async (_factoryId, _body, callbacks) => {
      callbacks.onRunId(event(1, 'RUN_STARTED').runId);
      callbacks.onEvent(event(1, 'RUN_STARTED'));
      return { runId: event(1, 'RUN_STARTED').runId, lastSequence: 1 };
    });
    mocks.replay.mockResolvedValue({
      schemaVersion: '1.0',
      runId: event(1, 'RUN_STARTED').runId,
      state: 'COMPLETED',
      routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
      nextEventSequence: 2,
      events: [event(1, 'RUN_STARTED'), event(2, 'RUN_COMPLETED')],
      terminalOutcome: {
        status: 'COMPLETE',
        routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
        claims: [{
          statementCode: 'MARGIN_CHANGE',
          metric: 'gross_margin_rate',
          value: '-3.2',
          unit: 'pct_point',
          evidenceId: 'ev-1',
          factId: 'fact-1',
        }],
        blockers: [],
        observations: ['margin_decline_attributed'],
        attributionSupported: true,
      },
      failureCode: null,
    });
    const wrapper = mountRun();

    expect(wrapper.text()).toContain('使用看板区间：2026-07-01 至 2026-07-19');
    await wrapper.get('[data-testid="gross-margin-run-start"]').trigger('click');
    await flushPromises();

    expect(mocks.stream).toHaveBeenCalledWith(
      'REST-1',
      {
        schemaVersion: '1.0',
        routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
        startDate: '2026-07-01',
        endDate: '2026-07-19',
      },
      expect.any(Object),
      expect.any(AbortSignal),
    );
    expect(mocks.replay).toHaveBeenCalledWith(
      'REST-1',
      event(1, 'RUN_STARTED').runId,
      1,
    );
    expect(wrapper.findAll('li').filter((item) => item.text().startsWith('#1'))).toHaveLength(1);
    expect(wrapper.text()).toContain('#2 运行完成');
    expect(wrapper.text()).toContain('持久化结论：COMPLETE');
    expect(wrapper.text()).toContain('证据引用 ev-1 / fact-1');
    expect(wrapper.text()).toContain('不代表完整 EvidenceEnvelope');
  });

  it('describes stop as local receive-stop, never server cancellation', async () => {
    mocks.stream.mockImplementation((_factoryId, _body, _callbacks, signal: AbortSignal) => (
      new Promise((_resolve, reject) => {
        signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      })
    ));
    const wrapper = mountRun();
    await wrapper.get('[data-testid="gross-margin-run-start"]').trigger('click');
    await flushPromises();
    await wrapper.get('[data-testid="gross-margin-run-stop"]').trigger('click');
    await flushPromises();

    expect(wrapper.get('[data-testid="gross-margin-run-state"]').text()).toBe('已停止接收');
    expect(wrapper.text()).toContain('不代表服务端任务已取消');
  });
});
