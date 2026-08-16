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

/**
 * 「接下来可以问什么」的一条按钮。
 *
 * 2026-08-16: 后端把条目从单一字符串拆成 `{label, question}` ——
 * `label` 是给人看的短词(「本月」)，`question` 是可独立发送的完整句
 * (「本月哪个菜卖得好」)。拆分原因(生产实测): 发裸词会撞服务端计划
 * 缓存的键，那个键不含会话上下文，于是一条对话的缓存计划被另一条命中。
 * ⇒ 芯片必须**显示 label、点击发 question** —— 两者不能再合并成一个字符串。
 */
export interface FollowupItem {
  label: string
  question: string
}

export interface SynthesisResponse {
  answer: string
  charts?: ChartPayload[]
  /**
   * 「接下来可以问什么」。后端一直在产出(`suggested_followups` ->
   * Java `suggestedFollowups`)，web-admin 也一直在渲染，
   * ⛔ 唯独**餐厅老板用的这个前端**从来没读过它 —— 形态 B: 产出端有了，
   * 消费端收不到。2026-08-16 接上，随后又发现「消费端把 label/question
   * 塌成一个字符串」的第二层问题，见 `FollowupItem`。
   */
  followups?: FollowupItem[]
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
  followups?: FollowupItem[]
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
