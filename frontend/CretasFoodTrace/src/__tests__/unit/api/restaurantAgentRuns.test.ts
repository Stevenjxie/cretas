// @ts-nocheck
const mockEventSources: MockEventSource[] = [];

class MockEventSource {
  listeners: Record<string, Array<(event: any) => void>> = {};
  closed = false;

  constructor(public url: string, public options: Record<string, unknown>) {
    mockEventSources.push(this);
  }

  addEventListener(type: string, listener: (event: any) => void) {
    (this.listeners[type] ||= []).push(listener);
  }

  emit(type: string, event: any) {
    for (const listener of this.listeners[type] || []) listener(event);
  }

  close() {
    this.closed = true;
  }
}

jest.mock('react-native-sse', () => ({
  __esModule: true,
  default: MockEventSource,
}));

const mockGetSecureItem = jest.fn();
jest.mock('../../../services/storage/storageService', () => ({
  StorageService: { getSecureItem: (...args: unknown[]) => mockGetSecureItem(...args) },
}));

const mockApiGet = jest.fn();
jest.mock('../../../services/api/apiClient', () => ({
  apiClient: { get: (...args: unknown[]) => mockApiGet(...args) },
}));

jest.mock('../../../constants/config', () => ({ API_BASE_URL: 'https://api.example.test' }));

import {
  currentMonthRestaurantAgentWindow,
  replayGrossMarginDeclineRun,
  startGrossMarginDeclineRun,
} from '../../../services/api/restaurantAgentRuns';

const event = (sequence: number, eventType = 'STEP_COMPLETED') => ({
  schemaVersion: '1.0',
  runId: '11111111-1111-4111-8111-111111111111',
  sequence,
  eventType,
  stepId: null,
  toolName: null,
  payload: {},
});

describe('restaurantAgentRuns', () => {
  beforeEach(() => {
    process.env.EXPO_PUBLIC_RESTAURANT_AGENT_RUN_MODE = 'OFF';
    mockEventSources.length = 0;
    mockGetSecureItem.mockReset().mockResolvedValue('rn-token');
    mockApiGet.mockReset();
  });

  it('defaults OFF and creates no EventSource or auth read', async () => {
    await expect(startGrossMarginDeclineRun(
      'REST-1',
      {
        schemaVersion: '1.0',
        routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
        startDate: '2026-07-01',
        endDate: '2026-07-19',
      },
      { onEvent: jest.fn() },
    )).rejects.toThrow('RESTAURANT_AGENT_RUNS_OFF');
    expect(mockEventSources).toHaveLength(0);
    expect(mockGetSecureItem).not.toHaveBeenCalled();
  });

  it('uses strict POST SSE, ignores duplicate sequence, and replays afterSequence', async () => {
    process.env.EXPO_PUBLIC_RESTAURANT_AGENT_RUN_MODE = 'ACTIVE';
    const onEvent = jest.fn();
    const subscription = await startGrossMarginDeclineRun(
      'REST-1',
      {
        schemaVersion: '1.0',
        routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
        startDate: '2026-07-01',
        endDate: '2026-07-19',
      },
      { onEvent },
    );
    const source = mockEventSources[0];
    source.emit('agent.event.v1', { data: JSON.stringify(event(1, 'RUN_STARTED')) });
    source.emit('agent.event.v1', { data: JSON.stringify(event(1, 'RUN_STARTED')) });
    source.emit('agent.event.v1', { data: JSON.stringify(event(2, 'RUN_COMPLETED')) });
    const completion = await subscription.completion;

    expect(source.url).toBe('https://api.example.test/api/mobile/REST-1/restaurant-agent/runs');
    expect(source.options).toEqual(expect.objectContaining({
      method: 'POST',
      pollingInterval: 0,
      headers: expect.objectContaining({
        Accept: 'text/event-stream',
        Authorization: 'Bearer rn-token',
      }),
      body: JSON.stringify({
        schemaVersion: '1.0',
        routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
        startDate: '2026-07-01',
        endDate: '2026-07-19',
      }),
    }));
    expect(String(source.options.body)).not.toContain('factoryId');
    expect(onEvent.mock.calls.map(([item]) => item.sequence)).toEqual([1, 2]);
    expect(completion).toEqual({
      runId: event(1).runId,
      lastSequence: 2,
      stoppedReceiving: false,
    });
    expect(source.closed).toBe(true);

    const replay = {
      schemaVersion: '1.0',
      runId: event(1).runId,
      state: 'COMPLETED',
      routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
      nextEventSequence: 2,
      events: [],
      terminalOutcome: null,
      failureCode: null,
    };
    mockApiGet.mockResolvedValue(replay);
    await expect(replayGrossMarginDeclineRun('REST-1', event(1).runId, 2)).resolves.toEqual(replay);
    expect(mockApiGet).toHaveBeenCalledWith(
      `/api/mobile/REST-1/restaurant-agent/runs/${event(1).runId}/events?afterSequence=2`,
    );
  });

  it.each([
    ['REST/1', 'path separator'],
    [`R${'x'.repeat(128)}`, 'more than 128 characters'],
  ])('rejects an invalid factory id (%s: %s) before auth or network access', async (factoryId) => {
    process.env.EXPO_PUBLIC_RESTAURANT_AGENT_RUN_MODE = 'ACTIVE';

    await expect(startGrossMarginDeclineRun(
      factoryId,
      {
        schemaVersion: '1.0',
        routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
        startDate: '2026-07-01',
        endDate: '2026-07-19',
      },
      { onEvent: jest.fn() },
    )).rejects.toThrow('FACTORY_ID_INVALID');

    expect(mockEventSources).toHaveLength(0);
    expect(mockGetSecureItem).not.toHaveBeenCalled();
    expect(mockApiGet).not.toHaveBeenCalled();
  });

  it('uses an explicit current calendar-month window', () => {
    // Local-time constructors pin the intended device calendar values and
    // prove the helper does not derive the window through UTC serialization.
    expect(currentMonthRestaurantAgentWindow(new Date(2026, 6, 19, 12))).toEqual({
      startDate: '2026-07-01',
      endDate: '2026-07-19',
    });
    expect(currentMonthRestaurantAgentWindow(new Date(2027, 0, 1, 0, 5))).toEqual({
      startDate: '2027-01-01',
      endDate: '2027-01-01',
    });
  });

  it('rejects a stream gap without delivering the invalid event', async () => {
    process.env.EXPO_PUBLIC_RESTAURANT_AGENT_RUN_MODE = 'ACTIVE';
    const onEvent = jest.fn();
    const subscription = await startGrossMarginDeclineRun(
      'REST-1',
      {
        schemaVersion: '1.0',
        routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
        startDate: '2026-07-01',
        endDate: '2026-07-19',
      },
      { onEvent },
    );
    const source = mockEventSources[0];
    source.emit('agent.event.v1', { data: JSON.stringify(event(1, 'RUN_STARTED')) });
    source.emit('agent.event.v1', { data: JSON.stringify(event(3, 'RUN_COMPLETED')) });

    await expect(subscription.completion).rejects.toThrow('毛利下降分析返回了无效事件');
    expect(onEvent.mock.calls.map(([item]) => item.sequence)).toEqual([1]);
  });

  it('rejects replay events from a different run', async () => {
    process.env.EXPO_PUBLIC_RESTAURANT_AGENT_RUN_MODE = 'ACTIVE';
    mockApiGet.mockResolvedValue({
      schemaVersion: '1.0',
      runId: event(1).runId,
      state: 'RUNNING',
      routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
      nextEventSequence: 2,
      events: [{ ...event(2), runId: '22222222-2222-4222-8222-222222222222' }],
      terminalOutcome: null,
      failureCode: null,
    });
    await expect(replayGrossMarginDeclineRun('REST-1', event(1).runId, 1))
      .rejects.toThrow('RESTAURANT_AGENT_REPLAY_CONTRACT_INVALID');
  });
});
