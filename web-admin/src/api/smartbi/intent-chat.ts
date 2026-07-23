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
                             // + AI read/write tabs (2026-07-23): READ_MODE_WRITE_BLOCKED / WRITE_CONFIRM_REQUIRED /
                             //   PREVIEW / DEMO_WRITE_BLOCKED / PENDING_APPROVAL / NO_PERMISSION / PERMISSION_DENIED
  message: string;
  formattedText: string;
  clarificationQuestions?: string[] | null;
  sessionId?: string | null;
  /** AI read/write separation (2026-07-23): which mode the backend resolved. */
  aiMode?: string;           // "READ" | "WRITE"
  /** Permission code required by the blocked intent, e.g. "inventory:write". */
  requiredPermission?: string | null;
  /**
   * TCC preview-confirm binding (P1.5): present on PREVIEW responses of write
   * tools. Confirm by POSTing /ai-intents/confirm with the token header.
   */
  confirmableAction?: {
    confirmToken: string;
    commandDigest: string;
    expiresAt: string;        // ISO instant
    expiresInSeconds?: number;
    description?: string;
    previewData?: Record<string, unknown> | null;
    // 写操作影响契约 (2026-07-24): 改前/改后对比 + 影响说明 + 风险档
    currentValues?: Record<string, unknown> | null;
    newValues?: Record<string, unknown> | null;
    impactSummary?: string | null;
    actionType?: string | null;   // WRITE | UPDATE | DELETE | ...
    riskLevel?: string | null;    // LOW | MEDIUM | HIGH | CRITICAL
  } | null;
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
  /** AI read/write tabs: READ = 咨询 tab (write intents blocked), OPERATE = 操作 tab. Omitted = OPERATE (backend default). */
  mode?: 'READ' | 'OPERATE';
  /** TCC: re-send after WRITE_CONFIRM_REQUIRED to get a PREVIEW + confirmableAction without executing. */
  previewOnly?: boolean;
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
  if (options.mode) body.mode = options.mode;
  if (options.previewOnly) body.previewOnly = true;

  const res = await request.post(
    `/${factoryId}/ai-intents/execute`,
    body,
    { timeout: 90_000 },
  );
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const envelope = res as any;
  return (envelope.data ?? envelope) as IntentExecuteResponse;
}

/** Client-generated id for confirm requestId/idempotencyKey (8-128 chars, ^[A-Za-z0-9][A-Za-z0-9._:-]*$). */
function createConfirmRequestId(): string {
  return (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function')
    ? crypto.randomUUID()
    : `req-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * TCC confirm: execute a previously previewed write action.
 * Token travels in the X-Cretas-Confirmation-Token header; the body carries
 * EXACTLY {commandDigest, expiresAt, requestId, idempotencyKey} — the backend
 * rejects unknown fields.
 */
export async function confirmIntentAction(
  factoryId: string,
  confirmToken: string,
  payload: { commandDigest: string; expiresAt: string },
): Promise<IntentExecuteResponse> {
  const body = {
    commandDigest: payload.commandDigest,
    expiresAt: payload.expiresAt,
    requestId: createConfirmRequestId(),
    idempotencyKey: createConfirmRequestId(),
  };
  const res = await request.post(
    `/${factoryId}/ai-intents/confirm`,
    body,
    {
      timeout: 90_000,
      headers: { 'X-Cretas-Confirmation-Token': confirmToken },
    },
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
