export interface DemoLoginResponse {
  token: string
  refreshToken?: string
  expiresIn?: number
}

export interface ChartPayload {
  type?: string
  title?: string
  option: Record<string, unknown>
}

export interface SynthesisResponse {
  answer: string
  charts?: ChartPayload[]
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
