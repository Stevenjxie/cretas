/**
 * Restaurant Chat API Client (P5 Task 5.1).
 *
 * Calls the Java AIIntentService chat endpoint, which routes through
 * the Tool-Skill pipeline and (optionally) invokes Python sections.
 *
 * post<T>(url, data?, config?) returns Promise<ApiResponse<T>>.
 * The request interceptor in api/request.ts unwraps ApiResponse automatically:
 * if response.success is false it throws ApiError, otherwise returns the
 * ApiResponse object. So we cast to ChatQueryResponse directly.
 */
import type {
  ChatQueryRequest,
  ChatQueryResponse,
  OwnerActionChatRequest,
  OwnerActionChatResponse,
} from '@/types/restaurant-chat';
import { post, pythonFetch, PYTHON_LLM_TIMEOUT_MS } from './common';

/**
 * Send a natural language query to the restaurant diagnostic chat.
 * Routes through Java AIIntentService (layers 1-8: exact/phrase/regex/
 * keyword/semantic/classifier/fusion/LLM) → Tool or Skill execution →
 * (optionally) Python section endpoints for deep analysis.
 */
export async function askRestaurantQuestion(
  request: ChatQueryRequest,
): Promise<ChatQueryResponse> {
  const response = await post<ChatQueryResponse>(
    `/${request.factoryId}/smart-bi/query`,
    {
      query: request.query,
      userId: request.userId,
      context: {
        subSector: request.subSector,
        uploadId: request.uploadId,
      },
    },
    // BUG #7 fix (Apr 15 2026): 全局 axios 30s 超时 < LLM responseText 生成耗时 (5-60s, median 31s).
    // 覆盖为 90s 保 LLM 慢查询通过 (qwen3.5-plus tail latency).
    { timeout: 90000 },
  );
  // post<T> returns ApiResponse<T>; data holds the ChatQueryResponse.
  // If backend wraps response in { success, data }, unwrap it here.
  // If backend returns ChatQueryResponse directly (success field inline), use as-is.
  const raw = response as unknown as { data?: ChatQueryResponse } & ChatQueryResponse;
  return raw.data ?? raw;
}

/**
 * Send a boss-facing owner action question to the restaurant demo chat.
 *
 * This path is deterministic for demo: it routes questions to package,
 * table mix, staffing, training, kitchen, cost, event, or single-item actions
 * and keeps follow-up context through sessionId.
 */
export async function askRestaurantOwnerActionQuestion(
  request: OwnerActionChatRequest,
): Promise<OwnerActionChatResponse> {
  const response = await pythonFetch<{ success: boolean; data: OwnerActionChatResponse }>(
    '/api/smartbi/restaurant/sections/owner-action-chat',
    {
      method: 'POST',
      timeoutMs: PYTHON_LLM_TIMEOUT_MS,
      body: JSON.stringify({
        factory_id: request.factoryId,
        message: request.message,
        sessionId: request.sessionId,
        demoScenario: request.demoScenario,
        storeName: request.storeName,
        subSector: request.subSector,
        period: request.period,
      }),
    },
  );
  return response.data;
}

/**
 * Clear conversation state for the current user session.
 * Calls the Java conversation clear endpoint which delegates to
 * ConversationStateService.clear(factoryId, userId).
 */
export async function clearRestaurantConversation(
  factoryId: string,
  userId: string,
): Promise<void> {
  await post<void>(
    `/${factoryId}/conversation/clear?userId=${encodeURIComponent(userId)}`,
    {},
  );
}
