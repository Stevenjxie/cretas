/**
 * Restaurant chat panel types (P5 Task 5.1).
 *
 * Data flow:
 *   User input → ChatQueryRequest → Java /ai-intents/execute → ChatQueryResponse
 *   → ChatTurn[] in panel state → rendered as ChatBubble with SectionPayload[]
 */

import type { ChartConfig } from '@/api/smartbi/common';

/** Output from one Python section endpoint (one entry in sections array) */
export interface SectionPayload {
  sectionName: string;
  status: 'ok' | 'skipped' | 'failed';
  data: Record<string, unknown>;       // Shape depends on sectionName
  warnings: string[];
  fromCache: boolean;
  computedAtMs: number;
}

/** A single chat turn (user message or AI response) */
export interface ChatTurn {
  id: string;
  role: 'user' | 'ai' | 'system';
  content: string;                     // Raw user text OR AI headline
  timestamp: number;
  intentCode?: string;
  toolName?: string;
  skillName?: string;
  sections?: SectionPayload[];
  followUpChips?: string[];
  error?: string;
}

/** Request to the unified Java intent endpoint. */
export interface ChatQueryRequest {
  query: string;
  factoryId: string;
  userId: string;
  subSector?: string;
  uploadId?: string;
  sessionId?: string;
  ownerActionScenario?: string;
}

/** Normalized response consumed by the legacy restaurant chat drawer. */
export interface ChatQueryResponse {
  success: boolean;
  intentCode: string | null;
  toolName?: string;
  skillName?: string;
  message?: string;
  sessionId?: string | null;
  ownerActionScenario?: string | null;
  sections?: SectionPayload[];
  followUpChips?: string[];
  error?: string;
}

/** Request to the boss-facing restaurant owner action demo chat. */
export interface OwnerActionChatRequest {
  message: string;
  factoryId: string;
  sessionId?: string;
  demoScenario?: string;
  storeName?: string;
  subSector?: string;
  period?: string;
}

/** Response from the boss-facing restaurant owner action demo chat. */
export interface OwnerActionChatResponse {
  sessionId: string;
  scenario: string;
  answer: string;
  responseText: string;
  log_id?: number | null;
  logId?: number | null;
  followUpSuggestions: string[];
  charts?: ChartConfig[];
  chartGuide?: string;
  decisionFocus?: Record<string, unknown>;
  ownerDecisionPage?: Record<string, unknown>;
  demoActionScenarios?: string[];
}
