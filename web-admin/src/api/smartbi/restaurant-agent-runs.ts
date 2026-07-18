import type {
  GrossMarginDeclineRunRequest,
  RestaurantAgentEventV1,
  RestaurantAgentRunMode,
  RestaurantAgentRunReplayV1,
} from '@/types/restaurant-agent-run';
import {
  RESTAURANT_AGENT_RUN_ROUTE,
  isRestaurantAgentRunId,
  parseRestaurantAgentEventV1,
  parseRestaurantAgentRunReplayV1,
} from '@/types/restaurant-agent-run';

export interface RestaurantAgentStreamCallbacks {
  onEvent: (event: RestaurantAgentEventV1) => void;
  onRunId?: (runId: string) => void;
}

export interface RestaurantAgentStreamResult {
  runId: string | null;
  lastSequence: number;
}

export function getRestaurantAgentRunMode(): RestaurantAgentRunMode {
  return import.meta.env.VITE_RESTAURANT_AGENT_RUN_MODE === 'ACTIVE' ? 'ACTIVE' : 'OFF';
}

export function isRestaurantAgentRunActive(): boolean {
  return getRestaurantAgentRunMode() === 'ACTIVE';
}

function requireActiveMode(): void {
  if (!isRestaurantAgentRunActive()) {
    throw new Error('RESTAURANT_AGENT_RUNS_OFF');
  }
}

function validateFactoryId(factoryId: string): string {
  const normalized = factoryId.trim();
  if (!normalized) throw new Error('FACTORY_ID_REQUIRED');
  if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(normalized)) {
    throw new Error('FACTORY_ID_INVALID');
  }
  return normalized;
}

function validateRequest(body: GrossMarginDeclineRunRequest): void {
  if (
    body.schemaVersion !== '1.0'
    || body.routeCode !== RESTAURANT_AGENT_RUN_ROUTE
    || !/^\d{4}-\d{2}-\d{2}$/.test(body.startDate)
    || !/^\d{4}-\d{2}-\d{2}$/.test(body.endDate)
    || body.endDate < body.startDate
    || (body.storeTopN !== undefined && (!Number.isInteger(body.storeTopN) || body.storeTopN < 1 || body.storeTopN > 50))
    || (body.dishTopN !== undefined && (!Number.isInteger(body.dishTopN) || body.dishTopN < 1 || body.dishTopN > 20))
  ) {
    throw new Error('RESTAURANT_AGENT_RUN_REQUEST_INVALID');
  }
}

function apiBaseUrl(): string {
  return (import.meta.env.VITE_API_BASE_URL || '/api/mobile').replace(/\/$/, '');
}

function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = {
    Accept: 'text/event-stream',
    'Content-Type': 'application/json',
    'X-Client-Type': 'web',
  };
  const token = localStorage.getItem('cretas_access_token');
  if (token) headers.Authorization = `Bearer ${token}`;
  return headers;
}

function errorForStatus(status: number): Error {
  if (status === 401) return new Error('登录已过期，请重新登录');
  if (status === 403) return new Error('当前账号无权查看该餐饮财务分析');
  if (status === 503) return new Error('毛利下降分析暂未启用或服务不可用');
  return new Error(`毛利下降分析请求失败 (${status})`);
}

function consumeFrame(
  frame: string,
  lastSequence: number,
  expectedRunId: string | null,
  callbacks: RestaurantAgentStreamCallbacks,
): { lastSequence: number; runId: string | null } {
  let eventName = '';
  const dataLines: string[] = [];
  for (const rawLine of frame.split(/\r?\n/)) {
    if (rawLine.startsWith('event:')) eventName = rawLine.slice(6).trim();
    if (rawLine.startsWith('data:')) dataLines.push(rawLine.slice(5).trimStart());
  }
  if (eventName !== 'agent.event.v1' || dataLines.length === 0) {
    return { lastSequence, runId: expectedRunId };
  }
  const event = parseRestaurantAgentEventV1(JSON.parse(dataLines.join('\n')));
  if (expectedRunId && event.runId !== expectedRunId) {
    throw new Error('RESTAURANT_AGENT_STREAM_RUN_ID_MISMATCH');
  }
  if (event.sequence <= lastSequence) {
    return { lastSequence, runId: event.runId };
  }
  if (event.sequence !== lastSequence + 1) {
    throw new Error('RESTAURANT_AGENT_STREAM_SEQUENCE_GAP');
  }
  callbacks.onEvent(event);
  return { lastSequence: event.sequence, runId: event.runId };
}

export async function streamGrossMarginDeclineRun(
  factoryId: string,
  body: GrossMarginDeclineRunRequest,
  callbacks: RestaurantAgentStreamCallbacks,
  signal?: AbortSignal,
): Promise<RestaurantAgentStreamResult> {
  requireActiveMode();
  const trustedFactoryId = validateFactoryId(factoryId);
  validateRequest(body);

  const response = await fetch(
    `${apiBaseUrl()}/${encodeURIComponent(trustedFactoryId)}/restaurant-agent/runs`,
    {
      method: 'POST',
      credentials: 'include',
      headers: authHeaders(),
      body: JSON.stringify(body),
      signal,
    },
  );
  if (!response.ok) throw errorForStatus(response.status);
  if (!response.headers.get('content-type')?.toLowerCase().includes('text/event-stream')) {
    throw new Error('毛利下降分析返回了无效的流式响应');
  }
  if (!response.body) throw new Error('毛利下降分析未返回事件流');

  let runId = response.headers.get('X-Agent-Run-Id');
  if (runId && !isRestaurantAgentRunId(runId)) {
    throw new Error('RESTAURANT_AGENT_STREAM_RUN_ID_INVALID');
  }
  if (runId) callbacks.onRunId?.(runId);
  let lastSequence = 0;
  let buffer = '';
  const decoder = new TextDecoder();
  const reader = response.body.getReader();

  while (true) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value, { stream: !done });
    const frames = buffer.split(/\r?\n\r?\n/);
    buffer = frames.pop() ?? '';
    for (const frame of frames) {
      const consumed = consumeFrame(frame, lastSequence, runId, callbacks);
      lastSequence = consumed.lastSequence;
      if (!runId && consumed.runId) {
        runId = consumed.runId;
        callbacks.onRunId?.(runId);
      }
    }
    if (done) break;
  }

  if (buffer.trim()) {
    const consumed = consumeFrame(buffer, lastSequence, runId, callbacks);
    lastSequence = consumed.lastSequence;
    if (!runId && consumed.runId) {
      runId = consumed.runId;
      callbacks.onRunId?.(runId);
    }
  }
  return { runId, lastSequence };
}

export async function replayGrossMarginDeclineRun(
  factoryId: string,
  runId: string,
  afterSequence = 0,
  signal?: AbortSignal,
): Promise<RestaurantAgentRunReplayV1> {
  requireActiveMode();
  const trustedFactoryId = validateFactoryId(factoryId);
  if (!isRestaurantAgentRunId(runId)) throw new Error('RUN_ID_REQUIRED');
  if (!Number.isSafeInteger(afterSequence) || afterSequence < 0) {
    throw new Error('AFTER_SEQUENCE_INVALID');
  }
  const response = await fetch(
    `${apiBaseUrl()}/${encodeURIComponent(trustedFactoryId)}/restaurant-agent/runs/${encodeURIComponent(runId)}/events?afterSequence=${afterSequence}`,
    {
      method: 'GET',
      credentials: 'include',
      headers: {
        ...authHeaders(),
        Accept: 'application/json',
      },
      signal,
    },
  );
  if (!response.ok) throw errorForStatus(response.status);
  return parseRestaurantAgentRunReplayV1(await response.json());
}
