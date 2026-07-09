import type { ChartPayload, DemoLoginResponse, SynthesisResponse } from './types'

const TOKEN_STORAGE_KEY = 'cretas_rest_ai_token'

let memoryToken = sessionStorage.getItem(TOKEN_STORAGE_KEY) || ''

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

export function clearToken(): void {
  memoryToken = ''
  sessionStorage.removeItem(TOKEN_STORAGE_KEY)
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
  return { ...data, token }
}

export async function askSynthesis(
  question: string,
  sessionId: string,
  userSignal?: AbortSignal,
): Promise<SynthesisResponse> {
  const token = getToken()
  if (!token) {
    throw new Error('登录已失效，请重试。')
  }

  const requestSignal = signalWithTimeout(userSignal, 20_000)
  let response: Response
  try {
    response = await fetch('/api/smartbi/synthesis/comprehensive', {
      method: 'POST',
      credentials: 'include',
      signal: requestSignal.signal,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({
        question,
        session_id: sessionId,
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

  const data = unwrapData(payload) as Partial<SynthesisResponse>
  if (typeof data.answer !== 'string') {
    throw new Error('后端响应缺少 answer 字段。')
  }

  return {
    answer: data.answer,
    charts: normalizeCharts(data.charts),
    source: data.source,
    tokens: data.tokens,
    plan: data.plan,
    fact_check: data.fact_check,
  }
}
