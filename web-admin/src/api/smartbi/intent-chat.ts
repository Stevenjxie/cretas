/**
 * Java AIIntentService chat client — single client for both Tool-Skill
 * orchestration AND LLM-fallback. Used by AIQuery.vue to unify the AI Chat
 * surface (one entry point, all 337 Tools reachable).
 *
 * Endpoint: POST /api/mobile/{factoryId}/ai-intents/execute
 */
import request from '../request';

export interface IntentExecuteResponse {
  intentRecognized: boolean;
  intentCode: string | null;
  intentName: string | null;
  status: string;            // SUCCESS / COMPLETED / NEED_MORE_INFO / NEED_CLARIFICATION / CONVERSATION_CONTINUE / FAILED / ERROR
  message: string;
  formattedText: string;
  clarificationQuestions?: string[] | null;
  sessionId?: string | null;
  /**
   * FRESH path: Tool's `buildSimpleResult(msg, data)` data lands here as
   * `{data: {download_url, summary, ...}}`. CACHE path: this is null and
   * the whole response is stringified into `message` (with "(缓存结果) " prefix).
   */
  resultData?: {
    data?: {
      download_url?: string;
      summary?: Record<string, unknown>;
      [key: string]: unknown;
    };
    [key: string]: unknown;
  } | null;
}

export interface ChatExecuteOptions {
  sessionId?: string;
  skipSlotFilling?: boolean;
  context?: Record<string, unknown>;
}

export interface IntentFeedbackPayload {
  userInput: string;
  matchedIntentCode: string;
  correctIntentCode?: string | null;
  isCorrect: boolean;
  sessionId?: string;
  userFeedback?: string;
}

export async function executeIntent(
  factoryId: string,
  userInput: string,
  options: ChatExecuteOptions = {},
): Promise<IntentExecuteResponse> {
  const body: Record<string, unknown> = { userInput };
  if (options.sessionId) body.sessionId = options.sessionId;
  if (options.skipSlotFilling) body.skipSlotFilling = true;
  if (options.context) body.context = options.context;

  const res = await request.post(
    `/${factoryId}/ai-intents/execute`,
    body,
    { timeout: 90_000 },
  );
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const envelope = res as any;
  return (envelope.data ?? envelope) as IntentExecuteResponse;
}

export async function submitIntentFeedback(
  factoryId: string,
  payload: IntentFeedbackPayload,
): Promise<boolean> {
  try {
    await request.post(`/${factoryId}/ai-intents/feedback`, payload);
    return true;
  } catch (e) {
    console.warn('[intent-feedback] submit failed:', e);
    return false;
  }
}

/** Fetch cached xlsx by cache_key for user-clicked download. */
export async function fetchCachedXlsx(
  factoryId: string,
  cacheKey: string,
): Promise<Blob> {
  const url = `/api/smartbi/${factoryId}/revenue-report/download/${cacheKey}`;
  const res = await request.get(url, {
    baseURL: '',
    responseType: 'blob',
    _keepResponse: true,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as any);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return ((res as any).data ?? res) as Blob;
}
