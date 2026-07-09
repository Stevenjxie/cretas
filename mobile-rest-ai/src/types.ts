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
}
