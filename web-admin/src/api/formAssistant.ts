/**
 * AI 表单助手 —— 对话式建单的解析端点。
 *
 * Backend: FormAssistantController @ /api/mobile/{factoryId}/form-assistant/*
 * request.ts 的 baseURL 已经是 /api/mobile, 所以这里从 /{factoryId} 起写。
 *
 * 2026-07-28 起 7 份表单 prompt 住在后端 resources/ai/form-prompts/factory/{ENTITY}.md
 * (FormPromptRegistry)，前端只传 entityType —— 不再从浏览器发 systemPrompt。
 * 换掉的旧通道是 /api/mobile/ai/chat (裸 LLM，路径里没有 factoryId，无配额计数、无遥测)。
 */
import { post } from './request';

/** 后端 FormAssistantController.FormField 的前端镜像。 */
export interface FormAssistantField {
  name: string;
  title: string;
  /** string / number / array —— 只用于让模型知道该返回什么类型 */
  type: string;
  required: boolean;
  description?: string;
}

export interface FormParsePayload {
  userInput: string;
  entityType: string;
  formFields: FormAssistantField[];
  /**
   * 后端 DTO 收这个字段但 FormAssistantService 目前不消费它 (见 FormAssistantController
   * .parseFormInput —— 只把 userInput/entityType/formFields/factoryId 传下去)。
   * 多轮对话的历史因此必须折进 userInput，不能指望 context 带过去。
   */
  context?: Record<string, unknown>;
}

/** 后端 FormAssistantController.FormParseResponse。 */
export interface FormParseResult {
  success: boolean;
  fieldValues?: Record<string, unknown> | null;
  confidence?: number;
  unparsedText?: string | null;
  message?: string | null;
  missingRequiredFields?: string[] | null;
  suggestedQuestions?: string[] | null;
  followUpQuestion?: string | null;
}

export function parseFormInput(factoryId: string, payload: FormParsePayload) {
  return post<FormParseResult>(`/${factoryId}/form-assistant/parse`, payload);
}
