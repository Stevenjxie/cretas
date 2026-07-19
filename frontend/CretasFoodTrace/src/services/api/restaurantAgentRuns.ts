import EventSource from 'react-native-sse';
import { API_BASE_URL } from '../../constants/config';
import { StorageService } from '../storage/storageService';
import { apiClient } from './apiClient';
import type {
  GrossMarginDeclineRunRequest,
  RestaurantAgentEventV1,
  RestaurantAgentRunCancelResponse,
  RestaurantAgentRunReplayV1,
} from '../../types/restaurantAgentRun';
import {
  RESTAURANT_AGENT_RUN_ROUTE,
  isRestaurantAgentRunId,
  parseRestaurantAgentEventV1,
  parseRestaurantAgentRunCancelResponse,
  parseRestaurantAgentRunReplayV1,
} from '../../types/restaurantAgentRun';

export interface RestaurantAgentRunCallbacks {
  onEvent: (event: RestaurantAgentEventV1) => void;
  onError?: (message: string) => void;
}

export interface RestaurantAgentRunCompletion {
  runId: string | null;
  lastSequence: number;
  stoppedReceiving: boolean;
}

export interface RestaurantAgentRunSubscription {
  completion: Promise<RestaurantAgentRunCompletion>;
  stopReceiving: () => void;
}

export interface RestaurantAgentRunCheckpoint {
  runId: string;
  lastSequence: number;
}

function checkpointKey(factoryId: string, ownerUserId: string): string {
  const ownerId = ownerUserId.trim();
  if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(ownerId)) {
    throw new Error('RESTAURANT_AGENT_CHECKPOINT_OWNER_INVALID');
  }
  return `restaurant_agent_run_checkpoint:${validateFactoryId(factoryId)}:${ownerId}`;
}

export async function saveRestaurantAgentRunCheckpoint(
  factoryId: string,
  ownerUserId: string,
  checkpoint: RestaurantAgentRunCheckpoint,
): Promise<void> {
  if (!isRestaurantAgentRunId(checkpoint.runId) || !Number.isSafeInteger(checkpoint.lastSequence)
    || checkpoint.lastSequence < 0) throw new Error('RESTAURANT_AGENT_CHECKPOINT_INVALID');
  await StorageService.setObject(checkpointKey(factoryId, ownerUserId), checkpoint);
}

export async function loadRestaurantAgentRunCheckpoint(
  factoryId: string,
  ownerUserId: string,
): Promise<RestaurantAgentRunCheckpoint | null> {
  const key = checkpointKey(factoryId, ownerUserId);
  const value = await StorageService.getObject<RestaurantAgentRunCheckpoint>(key);
  if (value === null) return null;
  if (!isRestaurantAgentRunId(value.runId) || !Number.isSafeInteger(value.lastSequence)
    || value.lastSequence < 0) {
    await StorageService.removeItem(key);
    return null;
  }
  return value;
}

export async function clearRestaurantAgentRunCheckpoint(
  factoryId: string,
  ownerUserId: string,
): Promise<void> {
  await StorageService.removeItem(checkpointKey(factoryId, ownerUserId));
}

export function isRestaurantAgentRunActive(): boolean {
  return process.env.EXPO_PUBLIC_RESTAURANT_AGENT_RUN_MODE === 'ACTIVE';
}

export function currentMonthRestaurantAgentWindow(now = new Date()): { startDate: string; endDate: string } {
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return { startDate: `${year}-${month}-01`, endDate: `${year}-${month}-${day}` };
}

function requireActiveMode(): void {
  if (!isRestaurantAgentRunActive()) throw new Error('RESTAURANT_AGENT_RUNS_OFF');
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
    body.schemaVersion !== '1.0' || body.routeCode !== RESTAURANT_AGENT_RUN_ROUTE
    || !/^\d{4}-\d{2}-\d{2}$/.test(body.startDate) || !/^\d{4}-\d{2}-\d{2}$/.test(body.endDate)
    || body.endDate < body.startDate
    || (body.storeTopN !== undefined && (!Number.isInteger(body.storeTopN) || body.storeTopN < 1 || body.storeTopN > 50))
    || (body.dishTopN !== undefined && (!Number.isInteger(body.dishTopN) || body.dishTopN < 1 || body.dishTopN > 20))
  ) throw new Error('RESTAURANT_AGENT_RUN_REQUEST_INVALID');
}

function runBasePath(factoryId: string): string {
  return `/api/mobile/${encodeURIComponent(factoryId)}/restaurant-agent/runs`;
}

export async function startGrossMarginDeclineRun(
  factoryId: string,
  body: GrossMarginDeclineRunRequest,
  callbacks: RestaurantAgentRunCallbacks,
): Promise<RestaurantAgentRunSubscription> {
  requireActiveMode();
  const trustedFactoryId = validateFactoryId(factoryId);
  validateRequest(body);
  const token = await StorageService.getSecureItem('secure_access_token');
  if (!token) throw new Error('登录已过期，请重新登录');

  const source = new EventSource<'agent.event.v1'>(`${API_BASE_URL}${runBasePath(trustedFactoryId)}`, {
    method: 'POST',
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(body),
    pollingInterval: 0,
    timeout: 75_000,
  });

  let runId: string | null = null;
  let lastSequence = 0;
  let settled = false;
  let resolveCompletion: (value: RestaurantAgentRunCompletion) => void = () => undefined;
  let rejectCompletion: (reason: Error) => void = () => undefined;
  const completion = new Promise<RestaurantAgentRunCompletion>((resolve, reject) => {
    resolveCompletion = resolve;
    rejectCompletion = reject;
  });

  const finish = (stoppedReceiving: boolean) => {
    if (settled) return;
    settled = true;
    source.close();
    resolveCompletion({ runId, lastSequence, stoppedReceiving });
  };

  source.addEventListener('agent.event.v1', (event) => {
    try {
      if (!event.data) throw new Error('RESTAURANT_AGENT_EVENT_CONTRACT_INVALID');
      const parsed = parseRestaurantAgentEventV1(JSON.parse(event.data));
      if (runId && parsed.runId !== runId) {
        throw new Error('RESTAURANT_AGENT_STREAM_RUN_ID_MISMATCH');
      }
      if (parsed.sequence <= lastSequence) return;
      if (parsed.sequence !== lastSequence + 1) {
        throw new Error('RESTAURANT_AGENT_STREAM_SEQUENCE_GAP');
      }
      runId = parsed.runId;
      lastSequence = parsed.sequence;
      callbacks.onEvent(parsed);
      if (['RUN_COMPLETED', 'RUN_FAILED', 'RUN_CANCELLED', 'BUDGET_EXCEEDED'].includes(parsed.eventType)) {
        finish(false);
      }
    } catch {
      const message = '毛利下降分析返回了无效事件';
      callbacks.onError?.(message);
      if (!settled) {
        settled = true;
        source.close();
        rejectCompletion(new Error(message));
      }
    }
  });

  source.addEventListener('error', (event) => {
    if (settled) return;
    const status = 'xhrStatus' in event ? event.xhrStatus : 0;
    const message = status === 403
      ? '当前账号无权查看该餐饮财务分析'
      : status === 503
        ? '毛利下降分析暂未启用或服务不可用'
        : '毛利下降分析连接失败';
    callbacks.onError?.(message);
    settled = true;
    source.close();
    rejectCompletion(new Error(message));
  });

  return {
    completion,
    stopReceiving: () => finish(true),
  };
}

export async function replayGrossMarginDeclineRun(
  factoryId: string,
  runId: string,
  afterSequence = 0,
): Promise<RestaurantAgentRunReplayV1> {
  requireActiveMode();
  const trustedFactoryId = validateFactoryId(factoryId);
  if (!isRestaurantAgentRunId(runId)) throw new Error('RUN_ID_REQUIRED');
  if (!Number.isSafeInteger(afterSequence) || afterSequence < 0) throw new Error('AFTER_SEQUENCE_INVALID');
  const response = await apiClient.get<RestaurantAgentRunReplayV1>(
    `${runBasePath(trustedFactoryId)}/${encodeURIComponent(runId)}/events?afterSequence=${afterSequence}`,
  );
  return parseRestaurantAgentRunReplayV1(response);
}

export async function cancelGrossMarginDeclineRun(
  factoryId: string,
  runId: string,
): Promise<RestaurantAgentRunCancelResponse> {
  requireActiveMode();
  const trustedFactoryId = validateFactoryId(factoryId);
  if (!isRestaurantAgentRunId(runId)) throw new Error('RUN_ID_REQUIRED');
  const response = await apiClient.post<RestaurantAgentRunCancelResponse>(
    `${runBasePath(trustedFactoryId)}/${encodeURIComponent(runId)}/cancel`,
  );
  return parseRestaurantAgentRunCancelResponse(response);
}
