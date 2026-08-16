export interface DemoLoginResponse {
  token: string
  refreshToken?: string
  expiresIn?: number
  /** Backend-issued tenant, needed to call the unified Java intent entry
   * (/api/mobile/{factoryId}/ai-intents/execute). */
  factoryId?: string
}

export interface ChartPayload {
  type?: string
  title?: string
  option: Record<string, unknown>
}

export interface SynthesisResponse {
  answer: string
  charts?: ChartPayload[]
  /**
   * 「接下来可以问什么」。后端一直在产出(`suggested_followups` ->
   * Java `suggestedFollowups`)，web-admin 也一直在渲染，
   * ⛔ 唯独**餐厅老板用的这个前端**从来没读过它 —— 形态 B: 产出端有了，
   * 消费端收不到。2026-08-16 接上。
   */
  followups?: string[]
  source?: string
  tokens?: number
  plan?: unknown
  fact_check?: unknown
  success?: boolean
  processingTimeMs?: number
}

export type MessageRole = 'user' | 'assistant'

export interface ChatMessage {
  id: string
  role: MessageRole
  content: string
  charts?: ChartPayload[]
  followups?: string[]
  source?: string
  tokens?: number
  status?: string
  isStreaming?: boolean
  createdAt: number
  // 👍/👎 反馈 (飞轮断点2, 2026-07-23) — assistant 消息回答的原问题;
  // 后端按 (租户, 问法) 关联飞轮捕获行。
  sourceQuery?: string
  feedbackValue?: 1 | -1
  feedbackPending?: boolean
}
