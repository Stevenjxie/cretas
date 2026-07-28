import type { ChartPayload, DemoLoginResponse, SynthesisResponse } from './types'

const TOKEN_STORAGE_KEY = 'cretas_rest_ai_token'
const FACTORY_ID_STORAGE_KEY = 'cretas_rest_ai_factory_id'

let memoryToken = sessionStorage.getItem(TOKEN_STORAGE_KEY) || ''
let memoryFactoryId = sessionStorage.getItem(FACTORY_ID_STORAGE_KEY) || ''

export class RequestTimeoutError extends Error {
  constructor() {
    super('分析超时，请重试')
    this.name = 'RequestTimeoutError'
  }
}

function signalWithTimeout(userSignal: AbortSignal | undefined, ms: number): {
  signal: AbortSignal
  cleanup: () => void
  didTimeout: () => boolean
} {
  const controller = new AbortController()
  let timedOut = false
  const abortFromUser = () => controller.abort(userSignal?.reason)
  const timer = window.setTimeout(() => {
    timedOut = true
    controller.abort()
  }, ms)

  if (userSignal?.aborted) {
    abortFromUser()
  } else {
    userSignal?.addEventListener('abort', abortFromUser, { once: true })
  }

  return {
    signal: controller.signal,
    cleanup: () => {
      window.clearTimeout(timer)
      userSignal?.removeEventListener('abort', abortFromUser)
    },
    didTimeout: () => timedOut,
  }
}

function readErrorMessage(payload: unknown, fallback: string): string {
  if (payload && typeof payload === 'object') {
    const record = payload as Record<string, unknown>
    if (typeof record.message === 'string' && record.message.trim()) {
      return record.message
    }
    if (typeof record.error === 'string' && record.error.trim()) {
      return record.error
    }
  }
  return fallback
}

async function parseJson(response: Response): Promise<unknown> {
  const text = await response.text()
  if (!text) return {}

  try {
    return JSON.parse(text)
  } catch {
    return { message: text }
  }
}

function unwrapData(payload: unknown): unknown {
  if (payload && typeof payload === 'object' && 'data' in payload) {
    return (payload as { data: unknown }).data
  }
  return payload
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function hasRenderableOption(option: Record<string, unknown>): boolean {
  return ['series', 'xAxis', 'yAxis', 'dataset', 'radar', 'calendar'].some((key) => key in option)
}

function needsValueAxis(option: Record<string, unknown>): boolean {
  if (!('xAxis' in option) || 'yAxis' in option) return false
  const series = option.series
  if (!Array.isArray(series)) return false
  return series.some((item) => {
    return isRecord(item) && (item.type === 'bar' || item.type === 'line')
  })
}

function normalizeOption(raw: Record<string, unknown>): Record<string, unknown> | null {
  const candidate = isRecord(raw.option)
    ? { ...raw.option }
    : Object.fromEntries(
        Object.entries(raw).filter(([key]) => !['chartType', 'title', 'type'].includes(key)),
      )

  if (needsValueAxis(candidate)) {
    candidate.yAxis = { type: 'value' }
  }

  return hasRenderableOption(candidate) ? candidate : null
}

function normalizeCharts(rawCharts: unknown): ChartPayload[] {
  if (!Array.isArray(rawCharts)) return []

  return rawCharts.flatMap((rawChart) => {
    if (!isRecord(rawChart)) return []
    const option = normalizeOption(rawChart)
    if (!option) return []
    return [{
      type: typeof rawChart.chartType === 'string'
        ? rawChart.chartType
        : typeof rawChart.type === 'string'
          ? rawChart.type
          : undefined,
      title: typeof rawChart.title === 'string' ? rawChart.title : undefined,
      option,
    }]
  })
}

export function getToken(): string {
  return memoryToken
}

/** Tenant issued by demo-login (e.g. DEMO_REST), needed for the unified Java
 * intent entry which is path-scoped: /api/mobile/{factoryId}/ai-intents/execute. */
export function getFactoryId(): string {
  return memoryFactoryId
}

export function clearToken(): void {
  memoryToken = ''
  memoryFactoryId = ''
  sessionStorage.removeItem(TOKEN_STORAGE_KEY)
  sessionStorage.removeItem(FACTORY_ID_STORAGE_KEY)
}

export async function demoLogin(): Promise<DemoLoginResponse> {
  const response = await fetch('/api/mobile/auth/demo-login?tenant=rest', {
    method: 'POST',
    credentials: 'include',
  })
  const payload = await parseJson(response)

  if (!response.ok) {
    throw new Error(readErrorMessage(payload, '演示登录失败，请稍后重试。'))
  }

  const data = unwrapData(payload) as Partial<DemoLoginResponse> & { accessToken?: string }
  const token = data.token || data.accessToken
  if (!token) {
    throw new Error('演示登录成功但未返回 token。')
  }

  memoryToken = token
  sessionStorage.setItem(TOKEN_STORAGE_KEY, token)
  if (data.factoryId) {
    memoryFactoryId = data.factoryId
    sessionStorage.setItem(FACTORY_ID_STORAGE_KEY, data.factoryId)
  }
  return { ...data, token }
}

/**
 * Card4 (2026-07-28, 餐饮 AI 飞轮回接): free-form restaurant Q&A entry.
 *
 * Routes through the unified Java intent orchestrator
 * (`POST /api/mobile/{factoryId}/ai-intents/execute`) — the same entry
 * web-admin's RestaurantChatPanel.vue uses (`askRestaurantIntent` →
 * `executeIntent`). This is the ONLY function App.vue's chat should call;
 * calling Python synthesis directly here would bypass query planning,
 * session continuation, the tiered-first gate and cache contracts, so the
 * same question could get a different (or up to 1h stale-cached) answer
 * than through web-admin's chat.
 *
 * Direct Python synthesis helpers are intentionally absent: a future
 * non-Q&A data pull must use a separately named client with a fixed contract,
 * rather than reintroducing a second free-form answer path.
 */
export async function askIntent(
  question: string,
  sessionId: string,
  userSignal?: AbortSignal,
): Promise<SynthesisResponse> {
  const token = getToken()
  const factoryId = getFactoryId()
  if (!token || !factoryId) {
    throw new Error('登录已失效，请重试。')
  }

  // Java intent execution can run a full tiered semantic plan (T1-T3), which
  // is slower than a single Python synthesis call — align the client timeout
  // with web-admin's executeIntent (90s) rather than the old 20s Python budget.
  const requestSignal = signalWithTimeout(userSignal, 90_000)
  let response: Response
  try {
    response = await fetch(`/api/mobile/${encodeURIComponent(factoryId)}/ai-intents/execute`, {
      method: 'POST',
      credentials: 'include',
      signal: requestSignal.signal,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({
        userInput: question,
        sessionId,
        mode: 'READ',
      }),
    })
  } catch (error) {
    if (requestSignal.didTimeout()) {
      throw new RequestTimeoutError()
    }
    throw error
  } finally {
    requestSignal.cleanup()
  }
  const payload = await parseJson(response)

  if (!response.ok) {
    throw new Error(readErrorMessage(payload, '分析失败，请稍后重试。'))
  }

  const data = unwrapData(payload) as Record<string, unknown>
  const status = typeof data.status === 'string' ? data.status : ''
  const answer = typeof data.message === 'string' && data.message
    ? data.message
    : typeof data.formattedText === 'string' ? data.formattedText : ''
  if (status === 'FAILED' || status === 'ERROR') {
    throw new Error(answer || '分析失败，请稍后重试。')
  }

  const resultData = isRecord(data.resultData) ? data.resultData : {}
  const nestedData = isRecord(resultData.data) ? resultData.data : {}

  return {
    success: true,
    answer,
    charts: normalizeCharts(resultData.charts ?? nestedData.charts),
    source: typeof resultData.source === 'string'
      ? resultData.source
      : typeof nestedData.source === 'string' ? nestedData.source : undefined,
  }
}

/**
 * 👍/👎 feedback on an answer (飞轮断点2, 2026-07-23). Backend correlates by
 * (tenant-from-JWT, trim(query)) against the flywheel capture table. Returns
 * false on any failure — callers revert their optimistic UI state.
 */
export async function sendAnswerFeedback(
  query: string,
  value: 1 | -1,
): Promise<boolean> {
  const token = getToken()
  if (!token) return false
  try {
    const response = await fetch('/api/smartbi/restaurant/feedback', {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ query, value }),
    })
    return response.ok
  } catch {
    return false
  }
}
