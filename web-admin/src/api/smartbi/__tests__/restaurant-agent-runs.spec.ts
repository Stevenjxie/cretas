import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  replayGrossMarginDeclineRun,
  streamGrossMarginDeclineRun,
} from '../restaurant-agent-runs';

const event = (sequence: number, eventType = 'STEP_COMPLETED') => ({
  schemaVersion: '1.0',
  runId: '11111111-1111-4111-8111-111111111111',
  sequence,
  eventType,
  stepId: sequence > 1 ? `step-${sequence}` : null,
  toolName: sequence > 1 ? 'restaurant.finance.summary.read' : null,
  payload: { statusCode: 'OK' },
});

describe('restaurant agent run API', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.stubEnv('VITE_API_BASE_URL', '/api/mobile');
    localStorage.clear();
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('fails closed in default OFF mode without issuing a Run request', async () => {
    vi.stubEnv('VITE_RESTAURANT_AGENT_RUN_MODE', 'OFF');
    const fetchMock = vi.spyOn(globalThis, 'fetch');

    await expect(streamGrossMarginDeclineRun(
      'REST-1',
      {
        schemaVersion: '1.0',
        routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
        startDate: '2026-07-01',
        endDate: '2026-07-19',
      },
      { onEvent: vi.fn() },
    )).rejects.toThrow('RESTAURANT_AGENT_RUNS_OFF');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('posts the strict SSE request, consumes only event v1, and replays afterSequence', async () => {
    vi.stubEnv('VITE_RESTAURANT_AGENT_RUN_MODE', 'ACTIVE');
    localStorage.setItem('cretas_access_token', 'web-token');
    const encoder = new TextEncoder();
    const payload = [
      `id: 1\nevent: agent.event.v1\ndata: ${JSON.stringify(event(1, 'RUN_STARTED'))}\n\n`,
      'event: ignored\ndata: {"fake":true}\n\n',
      `id: 1\nevent: agent.event.v1\ndata: ${JSON.stringify(event(1, 'RUN_STARTED'))}\n\n`,
      `id: 2\nevent: agent.event.v1\ndata: ${JSON.stringify(event(2))}\n\n`,
    ];
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        for (const frame of payload) controller.enqueue(encoder.encode(frame));
        controller.close();
      },
    });
    const replay = {
      schemaVersion: '1.0',
      runId: event(1).runId,
      state: 'COMPLETED',
      routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
      nextEventSequence: 2,
      events: [],
      terminalOutcome: {
        status: 'COMPLETE',
        routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
        claims: [],
        blockers: [],
        observations: ['margin_decline_attributed'],
        attributionSupported: true,
      },
      failureCode: null,
    };
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(stream, {
        status: 200,
        headers: {
          'content-type': 'text/event-stream',
          'X-Agent-Run-Id': event(1).runId,
        },
      }))
      .mockResolvedValueOnce(new Response(JSON.stringify(replay), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }));
    const received: number[] = [];

    const result = await streamGrossMarginDeclineRun(
      'REST-1',
      {
        schemaVersion: '1.0',
        routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
        startDate: '2026-07-01',
        endDate: '2026-07-19',
      },
      { onEvent: (item) => received.push(item.sequence) },
    );
    const replayResult = await replayGrossMarginDeclineRun('REST-1', result.runId!, 2);

    expect(received).toEqual([1, 2]);
    expect(result).toEqual({ runId: event(1).runId, lastSequence: 2 });
    expect(replayResult.terminalOutcome?.attributionSupported).toBe(true);
    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/mobile/REST-1/restaurant-agent/runs',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        headers: expect.objectContaining({
          Accept: 'text/event-stream',
          Authorization: 'Bearer web-token',
        }),
        body: JSON.stringify({
          schemaVersion: '1.0',
          routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
          startDate: '2026-07-01',
          endDate: '2026-07-19',
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty('X-Internal-Secret');
    expect(fetchMock.mock.calls[0][1]?.body).not.toContain('factoryId');
    expect(fetchMock.mock.calls[1][0]).toBe(
      `/api/mobile/REST-1/restaurant-agent/runs/${event(1).runId}/events?afterSequence=2`,
    );
  });

  it.each([
    ['REST/1', 'path separator'],
    [`R${'x'.repeat(128)}`, 'more than 128 characters'],
  ])('rejects an invalid factory id (%s: %s) before auth or network access', async (factoryId) => {
    vi.stubEnv('VITE_RESTAURANT_AGENT_RUN_MODE', 'ACTIVE');
    const fetchMock = vi.spyOn(globalThis, 'fetch');
    const tokenRead = vi.spyOn(Storage.prototype, 'getItem');

    await expect(streamGrossMarginDeclineRun(
      factoryId,
      {
        schemaVersion: '1.0',
        routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
        startDate: '2026-07-01',
        endDate: '2026-07-19',
      },
      { onEvent: vi.fn() },
    )).rejects.toThrow('FACTORY_ID_INVALID');

    expect(fetchMock).not.toHaveBeenCalled();
    expect(tokenRead).not.toHaveBeenCalled();
  });

  it('rejects a stream gap without delivering or advancing the invalid event', async () => {
    vi.stubEnv('VITE_RESTAURANT_AGENT_RUN_MODE', 'ACTIVE');
    const encoder = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(
          `event: agent.event.v1\ndata: ${JSON.stringify(event(1, 'RUN_STARTED'))}\n\n`
          + `event: agent.event.v1\ndata: ${JSON.stringify(event(3, 'RUN_COMPLETED'))}\n\n`,
        ));
        controller.close();
      },
    });
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(stream, {
      status: 200,
      headers: { 'content-type': 'text/event-stream' },
    }));
    const received: number[] = [];

    await expect(streamGrossMarginDeclineRun(
      'REST-1',
      {
        schemaVersion: '1.0',
        routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
        startDate: '2026-07-01',
        endDate: '2026-07-19',
      },
      { onEvent: (item) => received.push(item.sequence) },
    )).rejects.toThrow('RESTAURANT_AGENT_STREAM_SEQUENCE_GAP');
    expect(received).toEqual([1]);
  });

  it('rejects replay events whose run or ordering disagrees with the top-level record', async () => {
    vi.stubEnv('VITE_RESTAURANT_AGENT_RUN_MODE', 'ACTIVE');
    const mismatched = {
      schemaVersion: '1.0',
      runId: event(1).runId,
      state: 'RUNNING',
      routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
      nextEventSequence: 2,
      events: [{
        ...event(2),
        runId: '22222222-2222-4222-8222-222222222222',
      }],
      terminalOutcome: null,
      failureCode: null,
    };
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify(mismatched), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    }));

    await expect(replayGrossMarginDeclineRun('REST-1', event(1).runId, 1))
      .rejects.toThrow('RESTAURANT_AGENT_REPLAY_CONTRACT_INVALID');
  });
});
