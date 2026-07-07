/**
 * Restaurant chat adapter.
 *
 * The restaurant drawer used to call a separate restaurant query endpoint. Keep
 * the small UI-specific response shape here, but route all questions through
 * the unified Java intent executor so AIQuery and RestaurantV2 share one
 * Tool-Skill path.
 */
import { executeIntent } from './intent-chat';
import type {
  ChatQueryRequest,
  ChatQueryResponse,
  SectionPayload,
} from '@/types/restaurant-chat';

type FollowupLike = string | {
  label?: unknown;
  question?: unknown;
  text?: unknown;
};

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

function normalizeSections(value: unknown): SectionPayload[] {
  return Array.isArray(value) ? value as SectionPayload[] : [];
}

function normalizeFollowups(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((item: FollowupLike) => {
      if (typeof item === 'string') return item;
      if (item && typeof item === 'object') {
        const question = item.question ?? item.text ?? item.label;
        return typeof question === 'string' ? question : '';
      }
      return '';
    })
    .map((text) => text.trim())
    .filter(Boolean)
    .slice(0, 4);
}

export async function askRestaurantQuestion(
  request: ChatQueryRequest,
): Promise<ChatQueryResponse> {
  const response = await executeIntent(request.factoryId, request.query, {
    sessionId: request.sessionId,
    context: {
      subSector: request.subSector,
      uploadId: request.uploadId,
      ownerActionSessionId: request.sessionId,
      ownerActionScenario: request.ownerActionScenario,
    },
  });

  const resultData = asRecord(response.resultData);
  const nestedData = asRecord(resultData.data);
  const scenario = resultData.scenario ?? nestedData.scenario;
  const ownerActionSessionId = resultData.sessionId ?? resultData.session_id ?? nestedData.sessionId ?? nestedData.session_id;
  const sections = normalizeSections(resultData.sections ?? nestedData.sections);
  const followUpChips = [
    ...normalizeFollowups(resultData.suggestedFollowups),
    ...normalizeFollowups(resultData.followUpSuggestions),
    ...normalizeFollowups(nestedData.suggestedFollowups),
    ...normalizeFollowups(response.clarificationQuestions),
  ].filter((item, index, all) => all.indexOf(item) === index).slice(0, 4);

  return {
    success: response.status === 'SUCCESS',
    intentCode: response.intentCode ?? null,
    message: response.message || response.formattedText || '已完成分析',
    sessionId: typeof ownerActionSessionId === 'string' ? ownerActionSessionId : (response.sessionId ?? null),
    ownerActionScenario: typeof scenario === 'string' ? scenario : null,
    sections,
    followUpChips,
    error: response.status === 'ERROR' ? (response.message || '查询失败') : undefined,
  };
}
